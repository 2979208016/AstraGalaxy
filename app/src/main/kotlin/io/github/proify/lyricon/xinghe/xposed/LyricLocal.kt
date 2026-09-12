package io.github.proify.lyricon.xinghe.xposed

import android.content.Context
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.json.JSONObject
import java.io.File
import java.util.zip.InflaterInputStream

/**
 * 一条本地歌词：正文 + 自带元数据（用于与当前播放歌曲匹配）。
 *
 * 各平台本地缓存的“自带信息”不同：
 *  - 酷狗 KRC / QQ QRC / 波点 LRCX：正文含 [ti:]/[ar:]，可精确匹配
 *  - OPPO(欢太) .alm3ll：正文首行是歌曲 id，无歌名
 *  - 网易云 LrcCache：文件名即歌曲 id，正文无歌名
 * 后两者靠 mediaId、最近写入时间、总时长三条线索兜底。
 */
internal class LocalLyric(
    val lines: List<RichLyricLine>,
    val title: String? = null,
    val artist: String? = null,
    val id: String? = null
)

internal enum class LyricFormat { KRC, QRC, LRC, NETEASE }

internal enum class BaseDir { EXTERNAL_FILES, FILES, CACHE }

internal class LocalSource(
    val base: BaseDir,
    val subPath: String,
    val format: LyricFormat,
    val ext: List<String> = emptyList()
)

internal class LocalRecipe(val packageName: String, val sources: List<LocalSource>)

/** 各平台本地歌词解析（全部离线，无任何网络请求）。*/
internal object LyricParsers {

    private val KRC_KEY = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110, 105
    )

    private val KRC_LINE = Regex("""^\[(\d+)\s*,\s*(\d+)](.*)$""")
    private val KRC_WORD = Regex("""<(\d+)\s*,\s*(\d+)\s*,\s*(\d+)>""")
    private val QRC_LINE = Regex("""\[(\d+)\s*,\s*(\d+)]""")
    private val QRC_WORD = Regex("""\(\s*\d+\s*,\s*\d+\s*\)""")
    private val META_LINE = Regex("""^\[([A-Za-z]+)\s*:\s*(.*?)]$""")
    private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val LRC_WORD = Regex("""<\s*-?\d+\s*,\s*-?\d+\s*>""")
    private val ANY_TAG = Regex("""<[^>]*>""")

    private val PLACEHOLDER = listOf(
        "歌曲暂无歌词", "暂无歌词", "请欣赏音乐", "纯音乐", "该歌曲为纯音乐",
        "此歌曲为没有填词的纯音乐", "未经许可,不得翻唱或使用"
    )

    // ---------------- 酷狗 KRC ----------------

    fun parseKrc(bytes: ByteArray): LocalLyric? {
        if (bytes.size <= 4) return null
        if (bytes[0] != 'k'.code.toByte() || bytes[1] != 'r'.code.toByte()) return null
        val body = ByteArray(bytes.size - 4)
        for (i in body.indices) {
            body[i] = (bytes[i + 4].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
        }
        val text = runCatching {
            InflaterInputStream(body.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return null
        return parseKrcText(text)
    }

    private fun parseKrcText(text: String): LocalLyric {
        var title: String? = null
        var artist: String? = null
        val lines = ArrayList<RichLyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lm = KRC_LINE.find(line)
            if (lm != null) {
                val begin = lm.groupValues[1].toLongOrNull() ?: continue
                val dur = lm.groupValues[2].toLongOrNull() ?: 0L
                parseWords(begin, dur, lm.groupValues[3], KRC_WORD)?.let { lines.add(it) }
                continue
            }
            val mm = META_LINE.find(line) ?: continue
            when (mm.groupValues[1].lowercase()) {
                "ti" -> title = mm.groupValues[2].trim()
                "ar" -> artist = mm.groupValues[2].trim()
            }
        }
        return LocalLyric(lines, title, artist)
    }

    // ---------------- QQ 音乐 QRC ----------------

    fun parseQrcFile(bytes: ByteArray): LocalLyric? {
        var text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        // 部分版本首行是 "==> /path/xxx.qrc <=="
        if (text.startsWith("==>")) {
            val nl = text.indexOf('\n')
            text = if (nl >= 0) text.substring(nl + 1).trim() else ""
        }
        if (text.isEmpty()) return null
        return parseQrcText(QrcDecrypt.decrypt(text) ?: return null)
    }

    /** QRC 明文：可能包在 XML 的 LyricContent 属性里，也可能是纯文本 */
    private fun parseQrcText(content: String): LocalLyric {
        val body = Regex("""LyricContent\s*=\s*"([\s\S]*?)"(?=\s*/?>)""")
            .find(content)?.groupValues?.get(1)?.replace("&quot;", "\"") ?: content

        var title: String? = null
        var artist: String? = null
        for (m in META_LINE.findAll(body)) {
            when (m.groupValues[1].lowercase()) {
                "ti" -> title = m.groupValues[2].trim()
                "ar" -> artist = m.groupValues[2].trim()
            }
        }

        // QRC 行：[start,dur] 文本(字偏移,字时长)...
        val tags = QRC_LINE.findAll(body).toList()
        val items = ArrayList<Pair<Long, String>>()
        for (i in tags.indices) {
            val start = tags[i].groupValues[1].toLongOrNull() ?: continue
            val from = tags[i].range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            if (from >= to) continue
            val text = body.substring(from, to)
                .replace(QRC_WORD, "")
                .replace(ANY_TAG, "")
                .trim()
            if (text.isEmpty()) continue
            if (PLACEHOLDER.any { text.contains(it) }) continue
            items.add(start to text)
        }
        return LocalLyric(build(items), title, artist)
    }

    // ---------------- 通用 LRC（波点 .lrcx / OPPO .alm3ll / 普通 .lrc） ----------------

    fun parseLrcText(text: String): LocalLyric {
        var title: String? = null
        var artist: String? = null
        var id: String? = null
        val items = ArrayList<Pair<Long, String>>()
        var firstChecked = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!firstChecked) {
                firstChecked = true
                // OPPO 缓存首行是纯数字歌曲 id
                if (line.length >= 4 && line.all { it.isDigit() }) id = line
            }
            val times = LRC_TIME.findAll(line).toList()
            if (times.isEmpty()) {
                val mm = META_LINE.find(line) ?: continue
                when (mm.groupValues[1].lowercase()) {
                    "ti" -> title = mm.groupValues[2].trim()
                    "ar" -> artist = mm.groupValues[2].trim()
                }
                continue
            }
            val body = line.replace(LRC_TIME, "").replace(LRC_WORD, "").replace(ANY_TAG, "").trim()
            if (body.isEmpty()) continue
            if (PLACEHOLDER.any { body.contains(it) }) continue
            for (t in times) items.add(toMs(t) to body)
        }
        return LocalLyric(build(items), title, artist, id)
    }

    // ---------------- 网易云 LrcCache ----------------

    fun parseNetease(text: String): LocalLyric? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val lrc = obj.optString("lrc").takeIf { it.isNotBlank() } ?: return null

        val translation = HashMap<Long, String>()
        obj.optString("lrcTranslateLyric").takeIf { it.isNotBlank() }?.let { trans ->
            for ((t, s) in parseNeteaseLines(trans)) translation[t] = s
        }

        val rows = parseNeteaseLines(lrc)
        if (rows.isEmpty()) return null
        val sorted = rows.sortedBy { it.first }
        val lines = sorted.mapIndexed { i, (b, s) ->
            val e = (sorted.getOrNull(i + 1)?.first ?: (b + 3000L)).coerceAtLeast(b)
            RichLyricLine(begin = b, end = e, text = s, translation = translation[b])
        }
        return LocalLyric(lines)
    }

    /** 网易云逐行 JSON：{"t":1000,"c":[{"tx":"词"},{"tx":"曲"}]} */
    private fun parseNeteaseLines(raw: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return out
        if (!trimmed.startsWith("{")) {
            // 少数情况直接给普通 LRC
            return parseLrcText(trimmed).lines.map { it.begin to (it.text ?: "") }
        }
        for (line in trimmed.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val o = runCatching { JSONObject(l) }.getOrNull() ?: continue
            val t = o.optLong("t", -1L)
            if (t < 0) continue
            val arr = o.optJSONArray("c") ?: continue
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("tx").orEmpty())
            val s = sb.toString().trim()
            if (s.isEmpty()) continue
            if (PLACEHOLDER.any { s.contains(it) }) continue
            out.add(t to s)
        }
        return out
    }

    // ---------------- 工具 ----------------

    private fun parseWords(lineBegin: Long, lineDur: Long, body: String, tag: Regex): RichLyricLine? {
        val tags = tag.findAll(body).toList()
        if (tags.isEmpty()) {
            val t = body.replace(ANY_TAG, "").trim()
            if (t.isEmpty()) return null
            return RichLyricLine(begin = lineBegin, end = lineBegin + lineDur.coerceAtLeast(0L), text = t)
        }
        val words = ArrayList<LyricWord>()
        val sb = StringBuilder()
        var prevEnd = -1
        var offset = 0L
        var duration = 0L
        for (m in tags) {
            if (prevEnd >= 0) {
                val text = body.substring(prevEnd, m.range.first)
                if (text.isNotEmpty()) {
                    val b = lineBegin + offset
                    words.add(LyricWord(begin = b, end = b + duration, duration = duration, text = text))
                    sb.append(text)
                }
            }
            offset = m.groupValues[1].toLongOrNull() ?: 0L
            duration = m.groupValues[2].toLongOrNull() ?: 0L
            prevEnd = m.range.last + 1
        }
        if (prevEnd in 0..body.length) {
            val text = body.substring(prevEnd)
            if (text.isNotEmpty()) {
                val b = lineBegin + offset
                words.add(LyricWord(begin = b, end = b + duration, duration = duration, text = text))
                sb.append(text)
            }
        }
        val t = sb.toString().trim()
        if (t.isEmpty()) return null
        val end = if (lineDur > 0) lineBegin + lineDur else (words.lastOrNull()?.end ?: (lineBegin + 3000L))
        return RichLyricLine(begin = lineBegin, end = end.coerceAtLeast(lineBegin), text = t, words = words)
    }

    private fun toMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val frac = match.groupValues[3]
        val ms = when {
            frac.isEmpty() -> 0L
            frac.length == 1 -> frac.toLong() * 100
            frac.length == 2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1000L + ms
    }

    private fun build(items: List<Pair<Long, String>>): List<RichLyricLine> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedBy { it.first }
        return sorted.mapIndexed { i, (begin, text) ->
            val end = (sorted.getOrNull(i + 1)?.first ?: (begin + 3000L)).coerceAtLeast(begin)
            RichLyricLine(begin = begin, end = end, text = text)
        }
    }
}

/** 本地歌词扫描与匹配（完全离线）。*/
internal object LocalLyricFinder {

    /** 单次匹配最多解析的候选文件数（按修改时间倒序） */
    private const val MAX_CANDIDATES = 80

    /** 无元数据格式：认领“最近写入”缓存的时间窗 */
    private const val RECENT_WINDOW_MS = 30_000L

    /** 无元数据格式：按总时长匹配的容差 */
    private const val DURATION_TOLERANCE_MS = 12_000L

    /** path -> (mtime, 解析结果)，避免重复解析 */
    private val cache = HashMap<String, Pair<Long, LocalLyric?>>()

    fun find(
        context: Context,
        recipe: LocalRecipe,
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String?
    ): List<RichLyricLine>? {
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)
        var newest: Pair<File, LocalLyric>? = null
        var byDuration: Pair<LocalLyric, Long>? = null

        for (src in recipe.sources) {
            val dir = resolveDir(context, src) ?: continue
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            val candidates = files
                .filter { it.isFile && matchesExt(it, src.ext) }
                .sortedByDescending { it.lastModified() }
                .take(MAX_CANDIDATES)

            for (file in candidates) {
                val lyric = parse(file, src.format) ?: continue
                if (lyric.lines.isEmpty()) continue
                if (newest == null || file.lastModified() > newest!!.first.lastModified()) {
                    newest = file to lyric
                }

                val fileTitle = lyric.title
                if (!fileTitle.isNullOrBlank()) {
                    // 1) 正文自带歌名：精确匹配
                    if (normalize(fileTitle) == wantTitle && artistMatches(wantArtist, lyric.artist)) {
                        return lyric.lines
                    }
                } else if (!mediaId.isNullOrBlank()) {
                    // 2) 无歌名但有 mediaId：文件名 / 首行 id 匹配
                    val name = file.name.substringBeforeLast('.')
                    if (lyric.id == mediaId || name == mediaId) return lyric.lines
                }

                // 3) 记下“总时长最接近”的候选，作为最后兜底
                if (fileTitle.isNullOrBlank() && durationMs > 0) {
                    val last = lyric.lines.last().begin
                    val diff = kotlin.math.abs(last - durationMs)
                    if (byDuration == null || diff < byDuration!!.second) byDuration = lyric to diff
                }
            }
        }

        val recent = newest?.takeIf {
            it.second.title.isNullOrBlank() &&
                System.currentTimeMillis() - it.first.lastModified() <= RECENT_WINDOW_MS
        }
        if (recent != null) return recent.second.lines

        val dur = byDuration
        if (dur != null && dur.second <= DURATION_TOLERANCE_MS) return dur.first.lines
        return null
    }

    private fun parse(file: File, format: LyricFormat): LocalLyric? {
        val path = file.absolutePath
        val mtime = file.lastModified()
        cache[path]?.let { (t, v) -> if (t == mtime) return v }
        val lyric = runCatching {
            when (format) {
                LyricFormat.KRC -> LyricParsers.parseKrc(file.readBytes())
                LyricFormat.QRC -> LyricParsers.parseQrcFile(file.readBytes())
                LyricFormat.LRC -> LyricParsers.parseLrcText(file.readText())
                LyricFormat.NETEASE -> LyricParsers.parseNetease(file.readText())
            }
        }.getOrNull()
        if (cache.size > 400) cache.clear()
        cache[path] = mtime to lyric
        return lyric
    }

    private fun resolveDir(context: Context, src: LocalSource): File? {
        val base = when (src.base) {
            BaseDir.EXTERNAL_FILES -> context.getExternalFilesDir(null)
            BaseDir.FILES -> context.filesDir
            BaseDir.CACHE -> context.cacheDir
        } ?: return null
        return File(base, src.subPath)
    }

    private fun matchesExt(file: File, ext: List<String>): Boolean {
        if (ext.isEmpty()) return true
        val name = file.name.lowercase()
        return ext.any { name.endsWith(".$it") }
    }

    private fun artistMatches(want: String, got: String?): Boolean {
        if (want.isBlank() || got.isNullOrBlank()) return true
        val g = normalize(got)
        return g.contains(want) || want.contains(g)
    }

    /** 归一化：只保留字母/数字/中日韩文字，忽略大小写与符号 */
    private fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }
}
