package io.github.proify.lyricon.xinghe.xposed

import android.content.Context
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/**
 * 一条本地歌词：正文 + 自带元数据（用于与当前播放歌曲匹配）。
 *
 * 各平台本地缓存的「自带信息」差别很大：
 *  - 酷狗 KRC / QQ QRC / 波点 LRCX：正文含 [ti:]/[ar:]，可精确匹配；
 *    酷狗另有「歌手 - 歌名-hash.krc」这种把歌名写进文件名的缓存；
 *  - OPPO(欢太) .alm3ll：正文首行是歌曲 id；
 *  - 网易云 LrcCache：文件名与 JSON 里的 musicId 都是歌曲 id。
 */
internal class LocalLyric(
    val lines: List<RichLyricLine>,
    val title: String? = null,
    val artist: String? = null,
    val id: String? = null,
    /** 正文首行文本，很多平台首行是「歌名 - 歌手」 */
    val firstLine: String? = null
)

internal enum class LyricFormat { KRC, QRC, LRC, NETEASE }

internal enum class BaseDir { EXTERNAL_FILES, EXTERNAL_CACHE, FILES, CACHE }

internal class LocalSource(
    val base: BaseDir,
    val subPath: String,
    val format: LyricFormat,
    val ext: List<String> = emptyList()
)

internal class LocalRecipe(
    val packageName: String,
    val displayName: String,
    val sources: List<LocalSource>
)

/** 各平台本地歌词解析（全部离线，无任何网络请求）。 */
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
    private val LRC_WORD_TAG = Regex("""<\s*-?\d+\s*,\s*-?\d+\s*>""")
    private val ANY_TAG = Regex("""<[^>]*>""")

    private val PLACEHOLDER = listOf(
        "歌曲暂无歌词", "暂无歌词", "请欣赏音乐", "纯音乐", "该歌曲为纯音乐",
        "此歌曲为没有填词的纯音乐", "未经许可,不得翻唱或使用"
    )

    /** 首行里不该被当作「歌名 - 歌手」的制作者行 */
    private val CREDIT_PREFIX = listOf(
        "作词", "作曲", "编曲", "制作", "混音", "母带", "录音", "和声", "监制",
        "吉他", "贝斯", "鼓", "键盘", "弦乐", "出品", "发行", "统筹", "策划",
        "词：", "曲：", "词:", "曲:", "OP", "SP", "词曲"
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
        var firstLine: String? = null
        val lines = ArrayList<RichLyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lm = KRC_LINE.find(line)
            if (lm != null) {
                val begin = lm.groupValues[1].toLongOrNull() ?: continue
                val dur = lm.groupValues[2].toLongOrNull() ?: 0L
                val parsed = parseWords(begin, dur, lm.groupValues[3], KRC_WORD) ?: continue
                val txt = parsed.text?.trim().orEmpty()
                if (txt.isEmpty() || isPlaceholder(txt)) continue
                if (firstLine == null) firstLine = txt
                lines.add(parsed)
                continue
            }
            val mm = META_LINE.find(line) ?: continue
            when (mm.groupValues[1].lowercase()) {
                "ti" -> title = mm.groupValues[2].trim().ifBlank { null }
                "ar" -> artist = mm.groupValues[2].trim().ifBlank { null }
            }
        }
        if (title == null) {
            val guess = guessTitleArtist(firstLine)
            if (guess != null) {
                title = guess.first
                artist = artist ?: guess.second
            }
        }
        return LocalLyric(lines, title, artist, null, firstLine)
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
        val plain = QrcDecrypt.decrypt(text) ?: return null
        return parseQrcText(plain)
    }

    /** QRC 明文：可能包在 XML 的 LyricContent 属性里，也可能是纯文本 */
    private fun parseQrcText(content: String): LocalLyric {
        val body = Regex("""LyricContent\s*=\s*"([\s\S]*?)"(?=\s*/?>)""")
            .find(content)?.groupValues?.get(1)
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&amp;", "&")
            ?: content

        var title: String? = null
        var artist: String? = null
        for (m in META_LINE.findAll(body)) {
            when (m.groupValues[1].lowercase()) {
                "ti" -> title = m.groupValues[2].trim().ifBlank { null }
                "ar" -> artist = m.groupValues[2].trim().ifBlank { null }
            }
        }

        // QRC 行：[start,dur] 文本(字偏移,字时长)...
        val tags = QRC_LINE.findAll(body).toList()
        val items = ArrayList<Pair<Long, String>>()
        var firstLine: String? = null
        for (i in tags.indices) {
            val start = tags[i].groupValues[1].toLongOrNull() ?: continue
            val from = tags[i].range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            if (from >= to) continue
            val text = body.substring(from, to)
                .replace(QRC_WORD, "")
                .replace(ANY_TAG, "")
                .trim()
            if (text.isEmpty() || isPlaceholder(text)) continue
            if (firstLine == null) firstLine = text
            items.add(start to text)
        }
        if (title == null) {
            guessTitleArtist(firstLine)?.let {
                title = it.first
                artist = artist ?: it.second
            }
        }
        return LocalLyric(build(items), title, artist, null, firstLine)
    }

    // ---------------- 通用 LRC（波点 .lrcx / OPPO .alm3ll / 普通 .lrc） ----------------

    fun parseLrcText(text: String): LocalLyric {
        var title: String? = null
        var artist: String? = null
        var id: String? = null
        var firstLine: String? = null
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
                    "ti" -> title = mm.groupValues[2].trim().ifBlank { null }
                    "ar" -> artist = mm.groupValues[2].trim().ifBlank { null }
                }
                continue
            }
            val body = line.replace(LRC_TIME, "")
                .replace(LRC_WORD_TAG, "")
                .replace(ANY_TAG, "")
                .trim()
            if (body.isEmpty() || isPlaceholder(body)) continue
            if (firstLine == null) firstLine = body
            for (t in times) items.add(toMs(t) to body)
        }
        if (title == null) {
            guessTitleArtist(firstLine)?.let {
                title = it.first
                artist = artist ?: it.second
            }
        }
        return LocalLyric(build(items), title, artist, id, firstLine)
    }

    // ---------------- 网易云 LrcCache ----------------

    fun parseNetease(text: String): LocalLyric? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val rawLrc = obj.optString("lrc").takeIf { it.isNotBlank() }
            ?: obj.optString("yrc").takeIf { it.isNotBlank() }
            ?: return null

        val id = obj.optString("musicId").takeIf { it.isNotBlank() && it != "0" }

        val translation = HashMap<Long, String>()
        obj.optString("lrcTranslateLyric").takeIf { it.isNotBlank() }?.let { trans ->
            for ((t, s) in parseNeteaseLines(trans)) translation[t] = s
        }

        val rows = parseNeteaseLines(rawLrc)
        if (rows.isEmpty()) return null
        val sorted = rows.sortedBy { it.first }
        val lines = sorted.mapIndexed { i, (b, s) ->
            val e = (sorted.getOrNull(i + 1)?.first ?: (b + 3000L)).coerceAtLeast(b)
            RichLyricLine(begin = b, end = e, text = s, translation = translation[b])
        }
        return LocalLyric(lines, null, null, id, sorted.firstOrNull()?.second)
    }

    /**
     * 网易云逐行 JSON：{"t":1000,"c":[{"tx":"词"},{"tx":"曲"}]}
     * 同一份 lrc 里可能同时混有普通 LRC 行，需要逐行判断。
     */
    private fun parseNeteaseLines(raw: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return out
        if (!trimmed.contains('{')) {
            return parseLrcText(trimmed).lines.map { it.begin to (it.text ?: "") }
        }
        for (line in trimmed.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("{")) {
                val o = runCatching { JSONObject(l) }.getOrNull() ?: continue
                val t = o.optLong("t", -1L)
                if (t < 0) continue
                val arr = o.optJSONArray("c") ?: continue
                val sb = StringBuilder()
                for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("tx").orEmpty())
                val s = sb.toString().trim()
                if (s.isEmpty() || isPlaceholder(s)) continue
                out.add(t to s)
            } else {
                // 混在里面的普通 LRC 行
                val times = LRC_TIME.findAll(l).toList()
                if (times.isEmpty()) continue
                val body = l.replace(LRC_TIME, "").replace(LRC_WORD_TAG, "").replace(ANY_TAG, "").trim()
                if (body.isEmpty() || isPlaceholder(body)) continue
                for (t in times) out.add(toMs(t) to body)
            }
        }
        return out
    }

    // ---------------- 工具 ----------------

    private fun isPlaceholder(text: String): Boolean = PLACEHOLDER.any { text.contains(it) }

    /**
     * 从首行猜「歌名 - 歌手」。
     * 首行常见写法：`青花瓷 - 周杰伦 (Jay Chou)`、`游京（燃情版）-lucky 小阳`、
     * `X-COOL! (Best Slowed and Reverb) - tienanh109、MC K3`。
     */
    fun guessTitleArtist(line: String?): Pair<String, String?>? {
        if (line.isNullOrBlank()) return null
        if (CREDIT_PREFIX.any { line.startsWith(it) }) return null
        if (line.length > 120) return null
        val seps = listOf(" - ", " – ", " — ", "-", "–")
        for (sep in seps) {
            val idx = line.indexOf(sep)
            if (idx <= 0) continue
            var left = line.substring(0, idx).trim().trimEnd('(', '（')
            var right = line.substring(idx + sep.length).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            if (left.length > 60 || right.length > 60) continue
            // 括号里通常是别名，去掉尾部括号内容再比较
            val rightClean = right.substringBefore(" (").substringBefore("（").trim()
            if (left.none { it.isLetterOrDigit() } || rightClean.isEmpty()) continue
            return left to rightClean
        }
        return null
    }

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

/**
 * 本地歌词扫描与匹配（完全离线）。
 *
 * 匹配按「证据强度」打分，而不是碰运气：
 *   精确歌名(正文 [ti:])       +120
 *   文件名/首行含歌名          +60 / +40
 *   文件名含歌手                +20
 *   mediaId / 文件名 == 歌曲 id +150
 *   最近写入(≤2min / ≤15min)   +25 / +8
 *   总时长吻合(≤5s / ≤15s)     +25 / +12
 * 分数达到 [ACCEPT_SCORE] 才认；一个都匹配不上时，仅在「正文完全无歌名」
 * 且文件刚刚写入的情况下兜底，避免推错歌。
 */
internal object LocalLyricFinder {

    /** 单次匹配最多解析的候选文件数（按修改时间倒序） */
    private const val MAX_CANDIDATES = 120

    /** 认定为「匹配上」的最低分 */
    private const val ACCEPT_SCORE = 60

    /** 兜底：刚写入的缓存认领时间窗 */
    private const val RECENT_WINDOW_MS = 120_000L

    /** 兜底：按总时长匹配的容差 */
    private const val DURATION_TOLERANCE_MS = 8_000L

    private val HASH_SUFFIX = Regex("""-[0-9a-fA-F]{16,}$|-(\d{5,})$""")

    /** path -> (mtime, 解析结果)，避免重复解析 */
    private val cache = ConcurrentHashMap<String, Entry>()

    private class Entry(val mtime: Long, val lyric: LocalLyric?)

    fun find(
        context: Context,
        recipe: LocalRecipe,
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String?,
        log: (String) -> Unit
    ): List<RichLyricLine>? {
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)
        val now = System.currentTimeMillis()

        var bestScore = Int.MIN_VALUE
        var bestLyric: LocalLyric? = null
        var newest: Pair<File, LocalLyric>? = null

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
                val score = scoreOf(file, lyric, wantTitle, wantArtist, mediaId, durationMs, now)
                if (score > bestScore) {
                    bestScore = score
                    bestLyric = lyric
                }
            }
            log("扫描 ${dir.absolutePath}: 共 ${files.size} 个文件, 解析 ${candidates.size} 个")
        }

        if (bestLyric != null && bestScore >= ACCEPT_SCORE) {
            log("匹配成功 score=$bestScore")
            return bestLyric.lines
        }

        // 兜底 1：正文完全没有歌名，且文件刚写入（OPPO/网易云这类无元数据缓存）
        val recent = newest
        if (recent != null && recent.second.title.isNullOrBlank() &&
            now - recent.first.lastModified() <= RECENT_WINDOW_MS
        ) {
            log("兜底命中(最近写入): ${recent.first.name}")
            return recent.second.lines
        }

        // 兜底 2：正文无歌名但总时长吻合
        if (recent != null && recent.second.title.isNullOrBlank() && durationMs > 0) {
            val last = recent.second.lines.last().begin
            if (abs(last - durationMs) <= DURATION_TOLERANCE_MS) {
                log("兜底命中(时长吻合): ${recent.first.name}")
                return recent.second.lines
            }
        }

        log("未匹配 (最佳分=$bestScore)")
        return null
    }

    private fun scoreOf(
        file: File,
        lyric: LocalLyric,
        wantTitle: String,
        wantArtist: String,
        mediaId: String?,
        durationMs: Long,
        now: Long
    ): Int {
        if (wantTitle.isEmpty()) return 0
        var score = 0

        val fileStem = normalize(file.name.substringBeforeLast('.'))
        val fileBase = normalize(stripHash(file.name.substringBeforeLast('.')))
        val embeddedTitle = normalize(lyric.title)
        val embeddedArtist = normalize(lyric.artist)
        val firstLine = normalize(lyric.firstLine)
        val embeddedId = normalize(lyric.id)

        if (embeddedTitle.isNotEmpty()) {
            if (embeddedTitle == wantTitle) score += 120
            else if (embeddedTitle.contains(wantTitle) || wantTitle.contains(embeddedTitle)) score += 70
        }
        if (fileBase.contains(wantTitle)) score += 60 else if (fileStem.contains(wantTitle)) score += 30
        if (firstLine.contains(wantTitle)) score += 40

        if (wantArtist.length > 1) {
            if (embeddedArtist == wantArtist) score += 30
            else if (embeddedArtist.isNotEmpty() &&
                (embeddedArtist.contains(wantArtist) || wantArtist.contains(embeddedArtist))
            ) score += 15
            if (fileBase.contains(wantArtist)) score += 20
            if (firstLine.contains(wantArtist)) score += 15
        }

        if (!mediaId.isNullOrBlank()) {
            val mid = normalize(mediaId)
            if (mid.isNotEmpty() &&
                (embeddedId == mid || fileStem == mid || fileStem.endsWith(mid))
            ) score += 150
        }

        val age = now - file.lastModified()
        if (age <= RECENT_WINDOW_MS) score += 25 else if (age <= 900_000L) score += 8

        if (durationMs > 0) {
            val diff = abs(lyric.lines.last().begin - durationMs)
            if (diff <= 5_000L) score += 25 else if (diff <= 15_000L) score += 12
        }
        return score
    }

    private fun parse(file: File, format: LyricFormat): LocalLyric? {
        val path = file.absolutePath
        val mtime = file.lastModified()
        cache[path]?.let { if (it.mtime == mtime) return it.lyric }
        val lyric = runCatching {
            when (format) {
                LyricFormat.KRC -> LyricParsers.parseKrc(file.readBytes())
                LyricFormat.QRC -> LyricParsers.parseQrcFile(file.readBytes())
                LyricFormat.LRC -> LyricParsers.parseLrcText(file.readText())
                LyricFormat.NETEASE -> LyricParsers.parseNetease(file.readText())
            }
        }.getOrNull()
        if (cache.size > 600) cache.clear()
        cache[path] = Entry(mtime, lyric)
        return lyric
    }

    private fun resolveDir(context: Context, src: LocalSource): File? {
        val base = when (src.base) {
            BaseDir.EXTERNAL_FILES -> context.getExternalFilesDir(null)
            BaseDir.EXTERNAL_CACHE -> context.externalCacheDir
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

    /** 去掉「歌名-hash」里的 hash 尾巴 */
    private fun stripHash(stem: String): String = HASH_SUFFIX.replace(stem, "")

    /** 归一化：只保留字母/数字/中日韩文字，忽略大小写与符号 */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }
}
