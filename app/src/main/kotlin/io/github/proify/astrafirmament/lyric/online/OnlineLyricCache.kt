package io.github.proify.astrafirmament.lyric.online

import android.content.Context
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 在线歌词落盘缓存：`<cacheDir>/xinghe/lyric/online/<sha1>.json`。
 *
 * 存的是**解析好的行**（begin/end/译文/逐字），命中后不必重新解析，也能避免
 * 上游解析器升级时同一份歌词出现新旧两种结果。
 * 只缓存「确实存在」与「确实没有」两种结论，网络失败不进缓存。
 */
internal object OnlineLyricCache {

    private const val DIR_SEGMENT = "xinghe/lyric/online"

    /**
     * 缓存格式版本；解析/字段变化时 +1，旧文件自动失效。
     *
     * v2：在线源从 2 个扩到 4 个、匹配改成统一打分，v1 写下的「没词」结论
     * （很可能是当时唯一那个源挂了导致的）必须作废，否则用户要多等 30 分钟才可能重新命中。
     */
    private const val CACHE_VERSION = 2

    /** 正缓存 TTL：歌词很少变，7 天后重查顺便修正上游补的译文 */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 负缓存 TTL：短一点——断网时写下的「没词」不能把一首歌锁一整天 */
    private const val MISSING_TTL_MS = 30L * 60 * 1000

    /** 运行时存储红线：缓存文件上限，超了删最旧的 */
    private const val MAX_FILES = 400

    private const val HEX = "0123456789abcdef"

    private const val JSON_INDENT = 0

    @Volatile
    private var appContext: Context? = null

    /** 缓存查询结果：payload 有歌词、missing 表示确认没有（[reason] 记当时为什么没词） */
    class Cached(
        val payload: LyricPayload?,
        val missing: Boolean,
        val reason: String? = null
    )

    /** 热路径必须能拿到缓存目录；没 install 就退化成不缓存 */
    fun install(context: Context) {
        appContext = context.applicationContext
        prune()
    }

    /** 是否已注入 Context（宿主进程里调用方未必主动调过 install） */
    fun isInstalled(): Boolean = appContext != null

    fun read(key: String, log: (String) -> Unit): Cached? {
        val file = cacheFile(key) ?: return null
        if (!file.isFile) return null
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (obj == null || obj.optInt("v", 0) != CACHE_VERSION) {
            runCatching { file.delete() }
            return null
        }
        val age = System.currentTimeMillis() - obj.optLong("ts", 0L)
        if (age < 0L) return null
        if (obj.optBoolean("missing", false)) {
            if (age > MISSING_TTL_MS) return null
            val reason = obj.optString("reason").ifBlank { null }
            log("负缓存命中: $key" + (reason?.let { "（$it）" } ?: ""))
            return Cached(null, true, reason)
        }
        if (age > TTL_MS) return null
        val lines = parseLines(obj.optJSONArray("lines")) ?: return null
        val payload = LyricPayload(
            lines = lines,
            title = obj.optString("title").ifBlank { null },
            artist = obj.optString("artist").ifBlank { null },
            source = obj.optString("source", "cache"),
            id = obj.optString("id").ifBlank { null },
            durationMs = obj.optLong("dur", 0L)
        )
        log("缓存命中: " + payload.source + " / " + payload.title.orEmpty())
        return Cached(payload, false)
    }

    fun write(key: String, payload: LyricPayload, log: (String) -> Unit) {
        val file = cacheFile(key) ?: return
        runCatching {
            val obj = JSONObject()
                .put("v", CACHE_VERSION)
                .put("ts", System.currentTimeMillis())
                .put("source", payload.source)
                .put("dur", payload.durationMs)
                .put("lines", linesToJson(payload.lines))
            payload.title?.let { obj.put("title", it) }
            payload.artist?.let { obj.put("artist", it) }
            payload.id?.let { obj.put("id", it) }
            file.writeText(obj.toString(JSON_INDENT))
            prune()
        }.onFailure { log("缓存写入失败: " + it.message.orEmpty()) }
    }

    /**
     * 写负缓存。只有**所有源都明确返回「没这首歌的歌词」**时才该调用——
     * 单个源失败（超时/503）就写负缓存，等于把这首歌锁死 30 分钟。
     *
     * [reason] 记下当时的失败面（例如「2/4 源超时」），命中时打进日志方便取证。
     */
    fun writeMissing(key: String, reason: String, log: (String) -> Unit) {
        val file = cacheFile(key) ?: return
        runCatching {
            val obj = JSONObject()
                .put("v", CACHE_VERSION)
                .put("ts", System.currentTimeMillis())
                .put("missing", true)
                .put("reason", reason)
            file.writeText(obj.toString())
            log("写负缓存: $key（$reason）")
        }.onFailure { log("负缓存写入失败: " + it.message.orEmpty()) }
    }

    private fun linesToJson(lines: List<RichLyricLine>): JSONArray {
        val array = JSONArray()
        for (line in lines) {
            val obj = JSONObject()
                .put("b", line.begin)
                .put("e", line.end)
                .put("t", line.text.orEmpty())
            if (!line.translation.isNullOrBlank()) obj.put("tr", line.translation)
            if (!line.roma.isNullOrBlank()) obj.put("r", line.roma)
            val words = line.words
            if (!words.isNullOrEmpty()) obj.put("w", wordsToJson(words))
            array.put(obj)
        }
        return array
    }

    private fun wordsToJson(words: List<LyricWord>): JSONArray {
        val array = JSONArray()
        for (word in words) {
            array.put(
                JSONObject()
                    .put("b", word.begin)
                    .put("e", word.end)
                    .put("t", word.text.orEmpty())
            )
        }
        return array
    }

    /** 缓存内容不自洽（缺字段/时间轴乱）就整体丢弃，宁可重查也不要脏数据 */
    private fun parseLines(array: JSONArray?): List<RichLyricLine>? {
        if (array == null || array.length() == 0) return null
        val out = ArrayList<RichLyricLine>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val begin = obj.optLong("b", -1L)
            val text = obj.optString("t").trim()
            if (begin < 0L || text.isEmpty()) continue
            val end = obj.optLong("e", begin).coerceAtLeast(begin + 1L)
            val line = RichLyricLine(begin = begin, end = end, text = text)
            obj.optString("tr").takeIf { it.isNotBlank() }?.let { line.translation = it }
            obj.optString("r").takeIf { it.isNotBlank() }?.let { line.roma = it }
            parseWords(obj.optJSONArray("w"))?.let { line.words = it }
            out.add(line)
        }
        return out.ifEmpty { null }
    }

    private fun parseWords(array: JSONArray?): List<LyricWord>? {
        if (array == null || array.length() == 0) return null
        val out = ArrayList<LyricWord>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val begin = obj.optLong("b", -1L)
            if (begin < 0L) continue
            val end = obj.optLong("e", begin).coerceAtLeast(begin + 1L)
            out.add(LyricWord(begin = begin, end = end, duration = end - begin, text = obj.optString("t")))
        }
        return out.ifEmpty { null }
    }

    private fun cacheDir(): File? {
        val context = appContext ?: return null
        val dir = File(context.cacheDir, DIR_SEGMENT)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
        return dir
    }

    private fun cacheFile(key: String): File? {
        val dir = cacheDir() ?: return null
        return File(dir, hash(key) + ".json")
    }

    /** 文件名用散列：歌名里的中文/斜杠不能直接当文件名 */
    private fun hash(text: String): String {
        val digest = runCatching {
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return text.hashCode().toString()
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt()
            sb.append(HEX[(value shr 4) and 0x0F]).append(HEX[value and 0x0F])
        }
        return sb.toString()
    }

    private fun prune() {
        val dir = cacheDir() ?: return
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
