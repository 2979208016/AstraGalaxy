package io.github.proify.lyricon.xinghe.xposed

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 酷我歌词响应解码。
 *
 * 抓包实测：`http://mlyric.kuwo.cn/mobi.s?f=kuwo&q=…` 的响应体是
 * `TP=content\r\nlrcx=1\r\n\r\n` + zlib(base64(XOR("yeelion", UTF-8 的 LRCX 文本)))，
 * 解出来是 `[kuwo:124]` / `[ti:…]` / `[00:19.706]<772,-772>就<…>当…`。
 *
 * 但不同版本、以及「应用自己带 Accept-Encoding: gzip，okhttp 不做透明解压」的情况下，
 * 头部与压缩层次都会变，所以这里不赌单一格式：解不出就换管道与起点再试，
 * 只有结果确实像歌词（含 `[mm:ss` 时间轴或 `[kuwo:`／`[ti:` 标签）才认。
 */
internal object KuwoLyric {

    private val KEY = "yeelion".toByteArray(Charsets.US_ASCII)

    /** 解出来的文本必须带时间轴，避免把随便一段二进制当成歌词 */
    private val LRC_TIME = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")
    private val TAG_LINE = Regex("""\[(kuwo|ti|ar|al|by|offset)\s*:""")

    /** 快速判断是不是酷我的歌词响应（在钩子线程上跑，必须很便宜） */
    fun looksLike(bytes: ByteArray): Boolean {
        if (bytes.size < 32 || bytes.size > 4 * 1024 * 1024) return false
        if (isGzip(bytes)) return true
        val head = String(bytes, 0, minOf(bytes.size, 32), Charsets.ISO_8859_1)
        return head.contains("TP=content") || head.contains("lrcx=")
    }

    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    fun decode(bytes: ByteArray): String? {
        val payload = if (isGzip(bytes)) inflateGzip(bytes) ?: bytes else bytes
        for (offset in startOffsets(payload)) {
            val body = payload.copyOfRange(offset, payload.size)
            for (candidate in pipelines(body)) {
                if (plausible(candidate)) return candidate
            }
        }
        return null
    }

    /** 候选起点：整个 payload、响应头（空行）之后、前几个换行之后 */
    private fun startOffsets(bytes: ByteArray): List<Int> {
        val offsets = LinkedHashSet<Int>()
        offsets.add(0)
        headerEnd(bytes)?.let { offsets.add(it) }
        var seen = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                offsets.add(i + 1)
                if (++seen >= 3) break
            }
        }
        return offsets.filter { it in 0 until bytes.size }.sorted()
    }

    private fun pipelines(body: ByteArray): List<String> {
        val out = ArrayList<String>(6)
        inflateZlib(body)?.let { inflated ->
            val text = String(inflated, Charsets.ISO_8859_1)
            base64Decode(text)?.let { out.add(xor(it)) }
            base64Decode(text)?.let { out.add(String(it, Charsets.UTF_8)) }
            out.add(String(inflated, Charsets.UTF_8))
        }
        base64Decode(String(body, Charsets.ISO_8859_1))?.let { raw ->
            inflateZlib(raw)?.let { out.add(xor(it)) }
            inflateZlib(raw)?.let { out.add(String(it, Charsets.UTF_8)) }
            out.add(xor(raw))
            out.add(String(raw, Charsets.UTF_8))
        }
        out.add(String(body, Charsets.UTF_8))
        return out
    }

    private fun plausible(text: String): Boolean = when {
        text.length < 24 -> false
        TAG_LINE.containsMatchIn(text) -> true
        else -> LRC_TIME.containsMatchIn(text)
    }

    private fun xor(data: ByteArray): String {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun base64Decode(text: String): ByteArray? {
        if (text.length < 24) return null
        val cleaned = StringBuilder(text.length)
        for (c in text) {
            if (c.isLetterOrDigit() || c == '+' || c == '/' || c == '=') cleaned.append(c)
        }
        var body = cleaned.toString()
        val cut = body.lastIndexOf('=')
        if (cut > 0) body = body.substring(0, cut + 1)
        if (body.length < 24) return null
        return runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull()
    }

    private fun headerEnd(bytes: ByteArray): Int? {
        var i = 0
        while (i + 3 < bytes.size) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
            ) return i + 4
            i++
        }
        return null
    }

    private fun inflateGzip(data: ByteArray): ByteArray? = inflate(data, 10, true)

    private fun inflateZlib(data: ByteArray): ByteArray? = inflate(data, 0, false)

    /** zlib（或 gzip）解压；解不出来返回 null */
    private fun inflate(data: ByteArray, offset: Int, raw: Boolean): ByteArray? {
        if (data.size <= offset) return null
        val inflater = Inflater(raw)
        inflater.setInput(data, offset, data.size - offset)
        val out = ByteArrayOutputStream(data.size * 3 + 64)
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read <= 0) break
                if (out.size() > 8 * 1024 * 1024) return null
                out.write(buffer, 0, read)
            }
        } catch (throwable: Throwable) {
            return null
        } finally {
            inflater.end()
        }
        val result = out.toByteArray()
        return if (result.isEmpty()) null else result
    }
}
