package io.github.proify.lyricon.xinghe.xposed

import android.content.Context
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/**
 * 汽水音乐（com.luna.music）歌词读取。
 *
 * 汽水把歌词缓存成 JSON：
 * `cacheDir/NetCacheLoader/<子目录>/<md5("/luna/track_v2/" + 歌曲 id)>`
 *
 * ```
 * {"lyric":{"type":"krc","content":"[0,1200]…<0,120,0>…",
 *           "lang_translations":{"ZH-HANS":{"type":"lrc","content":"[00:00.00]…"}}}}
 * ```
 *
 * 这里**只读**：读缓存文件，或直接解析嗅探到的同一份 JSON。`type=krc` 按卡拉 OK
 * 行格式解析（带逐字），`type=lrc` 走普通 LRC，再按时间把系统语言的翻译贴到对应行。
 */
internal object LunaLyric {

    private const val CACHE_DIR = "NetCacheLoader"
    private const val PATH_PREFIX = "/luna/track_v2/"

    /** 行头：`[起点毫秒,时长毫秒]` */
    private val LINE_TIME = Regex("""\[(\d+),(\d+)]""")

    /** 字标签：`<相对起点毫秒,时长毫秒,音高>` */
    private val WORD_TAG = Regex("""<(\d+),(\d+),(\d+)>""")

    /** 只读查找缓存文件：拿不到返回 null，对播放器零影响 */
    fun find(context: Context, mediaId: String?): LocalLyric? {
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
        val file = cacheFile(context, id) ?: return null
        return runCatching { parsePayload(file.readText()) }.getOrNull()
    }

    /** 缓存文件名是 md5("/luna/track_v2/" + id)，落在 NetCacheLoader 的某个子目录里 */
    fun cacheFile(context: Context, id: String): File? {
        val root = File(context.cacheDir, CACHE_DIR)
        if (!root.isDirectory) return null
        val name = md5(PATH_PREFIX + id)
        val dirs = root.listFiles() ?: return null
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
        }
        return null
    }

    /** 诊断用：缓存目录长什么样（只在日志里用，不参与逻辑） */
    fun describe(context: Context, mediaId: String?): String {
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return "没有歌曲 id"
        val root = File(context.cacheDir, CACHE_DIR)
        val builder = StringBuilder()
        builder.append("cacheDir=").append(context.cacheDir.absolutePath)
        builder.append(" NetCacheLoader=").append(if (root.isDirectory) "有" else "无")
        val dirs = root.listFiles() ?: emptyArray()
        builder.append(" 子目录=").append(dirs.size)
        for (dir in dirs.take(3)) {
            val files = dir.listFiles() ?: emptyArray()
            builder.append(" | ").append(dir.name).append("(").append(files.size).append(")")
            files.take(3).forEach { builder.append(" ").append(it.name) }
        }
        builder.append(" | 期望文件=").append(md5(PATH_PREFIX + id))
        return builder.toString()
    }

    /**
     * 解析汽水的歌词 JSON（缓存文件内容与网络响应同构）。
     * 结构不对就返回 null，调用方自行忽略。
     */
    fun parsePayload(text: String?): LocalLyric? {
        val body = text?.trim().orEmpty()
        if (body.length < 32 || !body.startsWith("{")) return null
        val lyric = runCatching { JSONObject(body).optJSONObject("lyric") }.getOrNull() ?: return null
        if (lyric.optString("content").isBlank()) return null

        val main = parseByType(lyric.optString("type"), lyric.optString("content"))
        if (main.isEmpty()) return null

        val translations = translationLines(lyric)
        if (translations.isNotEmpty()) {
            for (line in main) {
                val hit = translations.minByOrNull { abs(it.first - line.begin) } ?: continue
                if (abs(hit.first - line.begin) <= 200 && hit.second != line.text) {
                    line.translation = hit.second
                }
            }
        }

        return LocalLyric(
            lines = main,
            title = null,
            artist = null,
            id = null,
            firstLine = main.firstOrNull()?.text
        )
    }

    /** 系统语言的翻译；找不到就挑第一个可用的 */
    private fun translationLines(lyric: JSONObject): List<Pair<Long, String>> {
        val table = lyric.optJSONObject("lang_translations") ?: return emptyList()
        val keys = table.keys().asSequence().toList()
        if (keys.isEmpty()) return emptyList()

        val locale = Locale.getDefault()
        val exact = buildString {
            append(locale.language.uppercase(Locale.ROOT))
            if (locale.country.isNotEmpty()) append("-" + locale.country.uppercase(Locale.ROOT))
        }
        val chosen = keys.firstOrNull { it.equals(exact, true) }
            ?: keys.firstOrNull { it.startsWith(locale.language, true) }
            ?: keys.first()
        val entry = table.optJSONObject(chosen) ?: return emptyList()
        val lines = parseByType(entry.optString("type"), entry.optString("content"))
        return lines.map { it.begin to it.text.orEmpty() }
    }

    private fun parseByType(type: String?, content: String?): List<RichLyricLine> {
        val body = content?.takeIf { it.isNotBlank() } ?: return emptyList()
        return when (type?.lowercase(Locale.ROOT)) {
            "krc" -> parseKtv(body)
            "lrc" -> runCatching { LyricParsers.parseLrcText(body).lines }.getOrDefault(emptyList())
            else -> emptyList()
        }
    }

    /**
     * 卡拉 OK 行格式：`[行起点,行时长]正文<字相对起点,字时长,音高>…`
     *
     * 与酷狗 KRC 不同：这里所有时间都是**毫秒整数**，且标签跟在被标注的字后面。
     */
    private fun parseKtv(content: String): List<RichLyricLine> {
        val out = ArrayList<RichLyricLine>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val head = LINE_TIME.find(line) ?: continue
            val begin = head.groupValues[1].toLongOrNull() ?: continue
            val duration = head.groupValues[2].toLongOrNull() ?: 0L
            val body = line.substring(head.range.last + 1)

            val words = ArrayList<LyricWord>()
            val plain = StringBuilder()
            var index = 0
            while (index < body.length) {
                val ch = body[index]
                val tag = if (index + 1 < body.length && body[index + 1] == '<') {
                    WORD_TAG.find(body, index + 1)?.takeIf { it.range.first == index + 1 }
                } else null
                if (tag != null) {
                    val offset = tag.groupValues[1].toLongOrNull() ?: 0L
                    val wordDuration = tag.groupValues[2].toLongOrNull() ?: 0L
                    val wordBegin = begin + offset
                    words.add(
                        LyricWord(
                            begin = wordBegin,
                            end = wordBegin + wordDuration,
                            duration = wordDuration,
                            text = ch.toString()
                        )
                    )
                    plain.append(ch)
                    index = tag.range.last + 1
                    continue
                }
                // 独立标签（行音高之类）直接跳过
                if (ch == '<') {
                    val standalone = WORD_TAG.find(body, index)?.takeIf { it.range.first == index }
                    if (standalone != null) {
                        index = standalone.range.last + 1
                        continue
                    }
                }
                words.add(LyricWord(begin = begin, end = begin, duration = 0L, text = ch.toString()))
                plain.append(ch)
                index++
            }

            val text = plain.toString().trim()
            if (text.isEmpty() || LyricParsers.looksLikeNoise(text)) continue
            val end = if (duration > 0) begin + duration else (words.lastOrNull()?.end ?: (begin + 3000L))
            out.add(
                RichLyricLine(
                    begin = begin,
                    end = end.coerceAtLeast(begin + 1),
                    text = text,
                    words = words
                )
            )
        }
        return out
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) sb.append('0')
            sb.append(Integer.toHexString(value))
        }
        return sb.toString()
    }
}
