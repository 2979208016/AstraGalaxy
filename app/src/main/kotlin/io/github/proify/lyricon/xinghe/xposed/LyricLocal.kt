package io.github.proify.lyricon.xinghe.xposed

import android.content.Context
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.json.JSONArray
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

internal enum class LyricFormat { KRC, QRC, LRC, NETEASE, LRCX }

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
    private val QRC_WORD = Regex("""\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
    private val YRC_LINE = Regex("""^\[(\d+)\s*,\s*(\d+)]""")
    private val YRC_WORD = Regex("""\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\d+\s*\)""")
    /** 网络歌词里常见的歌词字段名 */
    private val LYRICS_KEYS = arrayOf(
        "lyric", "lrc", "yrc", "krc", "qrc", "lyricContent",
        "content", "lyrics", "text", "tlyric"
    )
    private val META_LINE = Regex("""^\[([A-Za-z]+)\s*:\s*(.*?)]$""")
    private val KUWO_LINE = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val KUWO_TAG = Regex("""<(-?\d+),(-?\d+)>""")
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

        // QRC 行：[start,dur] 文本(字偏移,字时长)...，字级时间直接映射成逐字歌词
        val tags = QRC_LINE.findAll(body).toList()
        val items = ArrayList<RichLyricLine>()
        var firstLine: String? = null
        for (i in tags.indices) {
            val start = tags[i].groupValues[1].toLongOrNull() ?: continue
            val dur = tags[i].groupValues[2].toLongOrNull() ?: 0L
            val from = tags[i].range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            if (from >= to) continue
            val line = parseQrcWords(start, dur, body.substring(from, to)) ?: continue
            val text = line.text.orEmpty().trim()
            if (text.isEmpty() || isPlaceholder(text)) continue
            if (firstLine == null) firstLine = text
            items.add(line)
        }
        if (title == null) {
            guessTitleArtist(firstLine)?.let {
                title = it.first
                artist = artist ?: it.second
            }
        }
        return LocalLyric(items, title, artist, null, firstLine)
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
        val lrc = obj.optString("lrc").takeIf { it.isNotBlank() }
        val yrc = obj.optString("yrc").takeIf { it.isNotBlank() }
        if (lrc == null && yrc == null) return null

        val id = obj.optString("musicId").takeIf { it.isNotBlank() && it != "0" }

        // 翻译：新旧字段名都认
        val translation = HashMap<Long, String>()
        for (key in arrayOf("lrcTranslateLyric", "tlyric", "translation", "yrcTranslate")) {
            obj.optString(key).takeIf { it.isNotBlank() }?.let { raw ->
                for ((t, s) in parseNeteaseLines(raw)) translation[t] = s
            }
        }

        // 音译（罗马音）
        val roma = HashMap<Long, String>()
        for (key in arrayOf("romalrc", "roma", "romaLyric")) {
            obj.optString(key).takeIf { it.isNotBlank() }?.let { raw ->
                for ((t, s) in parseNeteaseLines(raw)) roma[t] = s
            }
        }

        // 有逐字（yrc）就用逐字，否则退回普通行歌词
        val base = yrc?.let { parseNeteaseYrc(it) }.orEmpty().ifEmpty {
            lrc?.let { parseNeteaseLines(it) }.orEmpty()
                .map { (t, s) -> RichLyricLine(begin = t, end = t, text = s) }
        }
        if (base.isEmpty()) return null

        val sorted = base.sortedBy { it.begin }
        val lines = sorted.mapIndexed { i, line ->
            val end = (sorted.getOrNull(i + 1)?.begin ?: (line.begin + 3000L)).coerceAtLeast(line.begin)
            line.end = end
            line.duration = end - line.begin
            line.translation = translation[line.begin]
            line.roma = roma[line.begin]
            line
        }
        return LocalLyric(lines, null, null, id, lines.firstOrNull()?.text)
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

    /** 网易云逐字 yrc：[start,dur](字偏移,字时长,0)字… */
    fun parseNeteaseYrc(raw: String): List<RichLyricLine> {
        val out = ArrayList<RichLyricLine>()
        for (line in raw.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val m = YRC_LINE.find(l) ?: continue
            val begin = m.groupValues[1].toLongOrNull() ?: continue
            val dur = m.groupValues[2].toLongOrNull() ?: 0L
            val parsed = parseWords(begin, dur, l.substring(m.range.last + 1), YRC_WORD) ?: continue
            val text = parsed.text.orEmpty().trim()
            if (text.isEmpty() || isPlaceholder(text)) continue
            out.add(parsed)
        }
        return out
    }

    /**
     * 嗅探到的网络歌词：不假设格式，按内容自己判断。
     *
     * 支持 JSON 包装（lyric / lrc / yrc / krc / qrc / tlyric 等字段，含一层嵌套）、
     * 纯 LRC、纯 QRC、纯 YRC、KRC 二进制。
     */
    fun parseAnyPayload(text: String): LocalLyric? {
        // 汽水音乐的歌词 JSON（{"lyric":{"type":"krc","content":…}}）
        LunaLyric.parsePayload(trimmedText(text))?.let { if (isUsable(it.lines)) return it }
        // 酷我 LRCX
        parseKuwoLrcx(text)?.let { if (isUsable(it.lines)) return it }
        val trimmed = text.trim()
        if (trimmed.length < 32 || trimmed.length > 4_000_000) return null

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            if (obj != null) {
                parseNetease(trimmed)?.let { if (isUsable(it.lines)) return it }
                for (key in LYRICS_KEYS) {
                    val value = obj.optString(key).takeIf { it.isNotBlank() } ?: continue
                    parseAnyPayload(value)?.let { if (isUsable(it.lines)) return it }
                }
                for (key in obj.keys()) {
                    val nested = obj.opt(key) ?: continue
                    val value = when (nested) {
                        is JSONObject -> LYRICS_KEYS.firstNotNullOfOrNull { k ->
                            nested.optString(k).takeIf { it.isNotBlank() }
                        }
                        is JSONArray -> (0 until nested.length()).firstNotNullOfOrNull { i ->
                            nested.optString(i).takeIf { it.isNotBlank() }
                        }
                        else -> null
                    } ?: continue
                    parseAnyPayload(value)?.let { if (isUsable(it.lines)) return it }
                }
            } else {
                runCatching { JSONArray(trimmed) }.getOrNull()?.let { array ->
                    for (i in 0 until array.length()) {
                        val value = when (val element = array.opt(i)) {
                            is String -> element
                            is JSONObject -> element.toString()
                            else -> null
                        } ?: continue
                        parseAnyPayload(value)?.let { if (isUsable(it.lines)) return it }
                    }
                }
            }
        }

        if (trimmed.contains("LyricContent")) {
            parseQrcText(trimmed).let { if (isUsable(it.lines)) return it }
        }
        if (YRC_WORD.containsMatchIn(trimmed)) {
            val lines = parseNeteaseYrc(trimmed)
            if (isUsable(lines)) return LocalLyric(lines, null, null, null, lines.firstOrNull()?.text)
        }
        if (QRC_LINE.containsMatchIn(trimmed)) {
            parseQrcText(trimmed).let { if (isUsable(it.lines)) return it }
        }
        parseLrcText(trimmed).let { if (isUsable(it.lines)) return it }

        // 有些平台把歌词塞在 JSON 字符串里，换行是转义的
        val unescaped = trimmed.replace("\\n", "\n").replace("\\/", "/")
        if (unescaped != trimmed) {
            parseLrcText(unescaped).let { if (isUsable(it.lines)) return it }
        }
        return null
    }

    /**
     * 酷我 LRCX：`[00:19.706]<772,-772>就<2216,872>当<…>作…`
     * 尖括号里是 `<结束,开始>`（相对本行起点，毫秒）。
     */
    fun parseKuwoLrcx(content: String): LocalLyric? {
        if (!content.contains("[kuwo")) return null
        var title: String? = null
        var artist: String? = null
        val lines = ArrayList<RichLyricLine>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("[ti:") -> title = line.removePrefix("[ti:").trimEnd(']').trim().ifBlank { null }
                line.startsWith("[ar:") -> artist = line.removePrefix("[ar:").trimEnd(']').trim().ifBlank { null }
                line.startsWith("[kuwo:") || line.startsWith("[ver:") || line.startsWith("[al:") ||
                    line.startsWith("[by:") || line.startsWith("[offset:") || line.startsWith("[length:")
                -> continue
            }
            val match = KUWO_LINE.find(line) ?: continue
            val lineBegin = toMs(match)
            val body = line.substring(match.range.last + 1)
            val words = ArrayList<LyricWord>()
            val text = StringBuilder()
            var index = 0
            for (tag in KUWO_TAG.findAll(body)) {
                val chunk = body.substring(index, tag.range.first)
                index = tag.range.last + 1
                text.append(chunk)
                if (!isMeaningful(chunk)) continue
                val start = lineBegin + (tag.groupValues[2].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val end = lineBegin + (tag.groupValues[1].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                words.add(
                    LyricWord(
                        begin = start,
                        end = end.coerceAtLeast(start + 1),
                        duration = (end - start).coerceAtLeast(1),
                        text = chunk
                    )
                )
            }
            val tail = body.substring(index)
            text.append(tail)
            val plain = text.toString().trim()
            if (!isMeaningful(plain)) continue
            lines.add(
                RichLyricLine(begin = lineBegin, end = lineBegin, text = plain, words = words.ifEmpty { null })
            )
        }
        if (lines.size < 3) return null
        val sorted = lines.sortedBy { it.begin }
        sorted.forEachIndexed { i, line ->
            val end = (sorted.getOrNull(i + 1)?.begin ?: (line.begin + 4000L)).coerceAtLeast(line.begin)
            line.end = end
            line.duration = end - line.begin
        }
        return LocalLyric(sorted, null, null, null, title ?: artist ?: sorted.firstOrNull()?.text)
    }

    private fun trimmedText(text: String): String = text.trim()
    /** 嗅探到的二进制（KRC 等） */
    /** 嗅探到的二进制（KRC 等） */
    fun parseAnyBytes(bytes: ByteArray): LocalLyric? {
        parseKrc(bytes)?.let { if (isUsable(it.lines)) return it }
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (text.count { it == '\u0000' } > 4) return null
        return parseAnyPayload(text)
    }

    // ---------------- 工具 ----------------

    /** 明显不是歌词的内容（JSON/代码/标签残留），用于拦截 {"re":…} 这类脏数据 */
    /** 词 / 行里至少要有一个文字或数字，纯标点一律丢弃 */
    private fun isMeaningful(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /**
     * 解析结果是否可用：至少 3 行，且一半以上的行有实际文字。
     * 网络旁路拿到过整份「,」这种脏数据，一律不接受。
     */
    fun isUsable(lines: List<RichLyricLine>): Boolean {
        if (lines.size < 3) return false
        val good = lines.count { line -> line.text?.any { it.isLetterOrDigit() } == true }
        return good >= 3 && good * 2 >= lines.size
    }

    fun looksLikeNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // 纯标点/纯符号（含全角逗号、省略号）不是歌词。
        // 酷我等播放器的网络歌词里混进过整份「,」行，必须在这里挡死。
        if (!t.any { it.isLetterOrDigit() }) return true
        if (t.startsWith("{") || t.startsWith("}") || t.startsWith("[") ||
            t.startsWith("]") || (t.startsWith("<") && t.endsWith(">"))
        ) return true
        if (t.contains("\":") || t.contains(":\"") || t.contains("://") ||
            t.contains("\\u") || t.contains("&quot;")
        ) return true
        var ascii = 0
        var punct = 0
        for (c in t) {
            if (c.code < 128) {
                ascii++
                if (!c.isLetterOrDigit() && !c.isWhitespace()) punct++
            }
        }
        if (ascii == t.length && punct >= 6 && punct * 3 > t.length) return true
        return false
    }

    private fun isPlaceholder(text: String): Boolean =
        looksLikeNoise(text) || PLACEHOLDER.any { text.contains(it) }

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

    /**
     * QQ 音乐 QRC 逐字行解析（只给 QRC 用，不动 KRC / YRC）：
     * **文本在时间前面**，而且括号里的时间是**绝对**毫秒。
     *
     * 真机缓存实测两首：
     *   [383,5058]窗(383,1038)外(1421,779) - (2200,779)李(2979,1181)琛(4160,1281)
     *   [4180,4180]词(4180,836)：(5016,836)李(5852,836)勤(6688,836)
     * 原来的 parseWords 取「标签后面的文本」，于是每行的第一个字被丢掉
     * （用户反馈的「QQ 音乐歌词第 1 个字会消失」），而且把绝对时间又加了一次行起点。
     */
    private fun parseQrcWords(lineBegin: Long, lineDur: Long, body: String): RichLyricLine? {
        val tags = QRC_WORD.findAll(body).toList()
        if (tags.isEmpty()) {
            val t = body.replace(ANY_TAG, "").trim()
            if (t.isEmpty()) return null
            return RichLyricLine(begin = lineBegin, end = lineBegin + lineDur.coerceAtLeast(0L), text = t)
        }
        val words = ArrayList<LyricWord>()
        val sb = StringBuilder()
        var prev = 0
        for (m in tags) {
            if (m.range.first < prev) continue
            val chunk = body.substring(prev, m.range.first).replace(ANY_TAG, "")
            prev = m.range.last + 1
            sb.append(chunk)
            if (!isMeaningful(chunk)) continue
            val begin = (m.groupValues[1].toLongOrNull() ?: lineBegin).coerceAtLeast(0L)
            val dur = (m.groupValues[2].toLongOrNull() ?: 0L).coerceAtLeast(1L)
            words.add(LyricWord(begin = begin, end = begin + dur, duration = dur, text = chunk))
        }
        val tail = body.substring(prev).replace(ANY_TAG, "")
        sb.append(tail)
        if (isMeaningful(tail)) {
            val begin = words.lastOrNull()?.end ?: lineBegin
            val end = (lineBegin + lineDur).coerceAtLeast(begin + 1)
            words.add(LyricWord(begin = begin, end = end, duration = end - begin, text = tail))
        }
        val text = sb.toString().trim()
        if (!isMeaningful(text)) return null
        val end = if (lineDur > 0) {
            (lineBegin + lineDur).coerceAtLeast(lineBegin)
        } else {
            (words.lastOrNull()?.end ?: (lineBegin + 3000L)).coerceAtLeast(lineBegin)
        }
        return RichLyricLine(begin = lineBegin, end = end, text = text, words = words.ifEmpty { null })
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
                if (isMeaningful(text)) {
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
            if (isMeaningful(text)) {
                val b = lineBegin + offset
                words.add(LyricWord(begin = b, end = b + duration, duration = duration, text = text))
                sb.append(text)
            }
        }
        val t = sb.toString().trim()
        if (!isMeaningful(t)) return null
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

    /** 兜底：缓存「刚写入」窗口（文件名不含歌名/id 的平台，如 QQ 音乐） */
    private const val FRESH_WRITE_MS = 15_000L

    /** 兜底：允许缓存比本首歌开始时间早多久（写入耗时） */
    private const val WRITE_GRACE_MS = 2_000L

    private val HASH_SUFFIX = Regex("""-[0-9a-fA-F]{16,}$|-(\d{5,})$""")

    /** 私有目录扫描的深度与规模上限 */
    private const val MAX_SWEEP_DEPTH = 4
    private const val MAX_SWEEP_FILES = 400
    private const val MAX_SWEEP_FILE_BYTES = 2L * 1024 * 1024

    private val NAME_IN_JSON = Regex(""""Name"\s*:\s*"([^"]+)"""")

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
        songStartedAtMs: Long = 0L,
        log: (String) -> Unit
    ): LocalLyric? {
        val wantTitle = normalize(title)
        val wantCore = titleCore(title)
        val wantArtist = normalize(artist)
        val now = System.currentTimeMillis()

        // 索引快速通道：之前扫过并记下的「歌曲 id / 歌名 / 歌手 → 文件」直接命中
        indexLookup(context, recipe, mediaId, durationMs, wantTitle, wantCore, wantArtist, now, log)
            ?.let { return it }

        var bestScore = Int.MIN_VALUE
        var bestLyric: LocalLyric? = null
        var bestFile: File? = null
        var newest: Pair<File, LocalLyric>? = null
        val parsed = ArrayList<Pair<File, LocalLyric>>()

        for (src in recipe.sources) {
            val dir = resolveDir(context, src) ?: continue
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            val candidates = files
                .filter { it.isFile && matchesExt(it, src.ext) }
                .sortedByDescending { it.lastModified() }
                .take(MAX_CANDIDATES)

            for (file in candidates) {
                val lyric = parse(context, file, src.format) ?: continue
                if (lyric.lines.isEmpty()) continue
                if (newest == null || file.lastModified() > newest!!.first.lastModified()) {
                    newest = file to lyric
                }
                parsed += file to lyric
                var score = scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now)
                if (src.format == LyricFormat.QRC) {
                    // qrc 文件名是 md5、正文加密，唯一的身份证据是旁边的 <同名>.producer
                    when (producerEvidence(file, wantArtist)) {
                        true -> {
                            score += 150
                            log(file.name + " 演唱名单吻合")
                        }
                        false -> score -= 500
                        null -> Unit
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    bestLyric = lyric
                    bestFile = file
                }
            }
            log("扫描 ${dir.absolutePath}: 共 ${files.size} 个文件, 解析 ${candidates.size} 个")
        }

        if (bestLyric != null && bestScore >= ACCEPT_SCORE) {
            log("匹配成功 score=$bestScore: " + (bestFile?.name ?: ""))
            return bestLyric
        }

        // 兜底：只认领「能证明属于本首歌」的缓存。
        //  · 文件名就是歌曲 id 的平台（网易云 / OPPO）：必须写在本首歌开始之后，
        //    否则上一首刚落盘的文件会被认成这一首，这正是「词不对歌」的来源；
        //  · QQ 音乐的 qrc 用 md5 命名、正文既没有歌名也没有 id，只能靠总时长吻合，
        //    这是它唯一能出词的路径，不能像之前那样一刀切掉。
        val opaqueName = recipe.sources.any { it.format == LyricFormat.QRC }
        // 网易云这类「缓存文件名就是歌曲 id」的平台，认领规则更硬
        val idNamed = recipe.sources.any { it.format == LyricFormat.NETEASE }
        val startedAt = songStartedAtMs
        val freshWindow = if (opaqueName) RECENT_WINDOW_MS else FRESH_WRITE_MS
        val mid = normalize(mediaId)

        // 1) 总时长吻合。QQ 音乐（qrc）还要有「身份证据」才认：
        //    · 旁边的 .producer 里写着当前歌手 → 认；
        //    · 文件是本次播放期间新写入的 → 认；
        //    · 否则一律不认 —— 之前只按时长猜，把别的歌的歌词推了出去
        //      （用户反馈的「QQ 音乐歌词完全对不上歌曲」）。
        var verified: Pair<File, LocalLyric>? = null
        var verifiedDiff = Long.MAX_VALUE
        var fresh: Pair<File, LocalLyric>? = null
        var freshDiff = Long.MAX_VALUE
        if (durationMs > 0L) {
            for ((file, lyric) in parsed) {
                val last = lyric.lines.lastOrNull()?.begin ?: continue
                val diff = abs(last - durationMs)
                if (diff > DURATION_TOLERANCE_MS) continue
                if (identityConflicts(lyric, wantCore, wantArtist, mid)) {
                    log("排除 " + file.name + "：歌词自带身份与当前歌曲不符")
                    continue
                }
                val evidence = if (opaqueName) producerEvidence(file, wantArtist) else null
                if (evidence == false) {
                    log("排除 " + file.name + "：演唱名单与当前歌手不符")
                    continue
                }
                val recentlyWritten = startedAt > 0L &&
                    file.lastModified() >= startedAt - WRITE_GRACE_MS
                // 文件名就是本曲 id（网易云的 LrcCache）：最硬的证据
                val namedById = mid.isNotEmpty() &&
                    normalize(file.name.substringBeforeLast('.')) == mid
                if (evidence == true) {
                    if (diff < verifiedDiff) {
                        verifiedDiff = diff
                        verified = file to lyric
                    }
                } else if (namedById || (recentlyWritten && !idNamed)) {
                    if (diff < freshDiff) {
                        freshDiff = diff
                        fresh = file to lyric
                    }
                }
            }
        }
        val durationCandidate = verified ?: fresh
        if (durationCandidate != null) {
            val how = if (verified != null) "名单吻合" else "新写入"
            log("兜底命中(时长吻合·" + how + "): " + durationCandidate.first.name)
            return durationCandidate.second
        }

        // 2) 刚写入的缓存
        val recent = newest
        if (recent != null) {
            val writtenAt = recent.first.lastModified()
            val age = now - writtenAt
            val inSong = startedAt > 0L && writtenAt >= startedAt - WRITE_GRACE_MS
            val evidence = if (opaqueName) producerEvidence(recent.first, wantArtist) else null
            if (age <= freshWindow && (opaqueName || inSong) && evidence != false) {
                log("兜底命中(最近写入): " + recent.first.name)
                return recent.second
            }
        }

        log("未匹配 (最佳分=$bestScore)")
        // 失败时把前几名候选打出来，方便定位「为什么没认出来」
        runCatching {
            parsed.map { (file, lyric) ->
                scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now) to file.name
            }.sortedByDescending { it.first }
                .take(3)
                .joinToString(" | ") { it.second + "=" + it.first }
        }.getOrNull()?.let { log("候选: $it") }
        return null
    }
    /**
     * 兜底通道：直接扫宿主自己的私有目录（files / cache 及其外部对应目录，深度受限），
     * 找长得像歌词的文件。
     *
     * 有些播放器（酷我）的歌词缓存既不在外部存储的固定位置、请求也不走 okhttp，
     * 之前只推得出歌曲名和歌手。这里用同一套打分规则从它自己的目录里把歌词找出来。
     */
    fun findInOwnStorage(
        context: Context,
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String?,
        songStartedAtMs: Long,
        log: (String) -> Unit
    ): LocalLyric? {
        val wantTitle = normalize(title)
        if (wantTitle.isEmpty()) return null
        val wantArtist = normalize(artist)
        val wantCore = titleCore(title)
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            runCatching { context.getExternalFilesDir(null) }.getOrNull(),
            runCatching { context.getExternalCacheDir() }.getOrNull()
        )
        if (roots.isEmpty()) return null
        val now = System.currentTimeMillis()
        var bestScore = Int.MIN_VALUE
        var best: LocalLyric? = null
        var scanned = 0
        for (root in roots) {
            walkLyricFiles(root, 0) { file ->
                if (scanned >= MAX_SWEEP_FILES) return@walkLyricFiles
                val format = sweepFormatOf(file) ?: return@walkLyricFiles
                if (file.length() > MAX_SWEEP_FILE_BYTES) return@walkLyricFiles
                scanned++
                val lyric = parse(context, file, format) ?: return@walkLyricFiles
                if (lyric.lines.size < 3) return@walkLyricFiles
                var score = scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now)
                if (format == LyricFormat.QRC) {
                    when (producerEvidence(file, wantArtist)) {
                        true -> score += 150
                        false -> score -= 500
                        null -> Unit
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = lyric
                }
            }
        }
        log("私有目录扫描：" + scanned + " 个歌词文件，最佳分=" + bestScore)
        return if (best != null && bestScore >= ACCEPT_SCORE) best else null
    }

    private fun walkLyricFiles(dir: File, depth: Int, visit: (File) -> Unit) {
        if (depth > MAX_SWEEP_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) walkLyricFiles(child, depth + 1, visit)
            else if (child.isFile) visit(child)
        }
    }

    private fun sweepFormatOf(file: File): LyricFormat? {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".krc") -> LyricFormat.KRC
            name.endsWith(".qrc") -> LyricFormat.QRC
            name.endsWith(".lrc") -> LyricFormat.LRC
            name.endsWith(".lrcx") -> LyricFormat.LRCX
            name.endsWith(".alm3ll") -> LyricFormat.LRC
            else -> null
        }
    }

    /**
     * QQ 音乐的歌词目录里，每首歌词都配了一份 <同名>.producer（JSON，含演唱/作词等名字）。
     * qrc 本身加密、文件名是 md5，正文里读不到歌名，只能靠它验明正身：
     * 名单里没有当前歌手就排除，避免推出别的歌的歌词。
     */
    private fun producerEvidence(file: File, wantArtist: String): Boolean? {
        val names = producerNames(file) ?: return null
        if (wantArtist.isEmpty()) return null
        for (name in names) {
            val n = normalize(name)
            if (n.isEmpty()) continue
            if (n == wantArtist || n.contains(wantArtist) || wantArtist.contains(n)) return true
        }
        return false
    }

    private fun producerNames(file: File): List<String>? {
        val parent = file.parentFile ?: return null
        val base = file.name.substringBeforeLast('.', file.name)
        val producer = File(parent, base + ".producer")
        if (!producer.isFile || producer.length() <= 0L || producer.length() > 512 * 1024) return null
        val text = runCatching { producer.readText() }.getOrNull() ?: return null
        return NAME_IN_JSON.findAll(text).map { it.groupValues[1] }.toList()
    }

    private fun scoreOf(
        file: File,
        lyric: LocalLyric,
        wantTitle: String,
        wantCore: String,
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

        val embeddedCore = titleCore(lyric.title)
        if (embeddedCore.isNotEmpty() && wantCore.isNotEmpty()) {
            if (embeddedCore == wantCore) score += 120
            else if (embeddedCore.contains(wantCore) || wantCore.contains(embeddedCore)) score += 70
        } else if (embeddedTitle.isNotEmpty()) {
            if (embeddedTitle == wantTitle) score += 120
            else if (embeddedTitle.contains(wantTitle) || wantTitle.contains(embeddedTitle)) score += 70
        }
        if (wantCore.isNotEmpty()) {
            if (fileBase.contains(wantCore)) score += 60 else if (fileStem.contains(wantCore)) score += 30
            if (firstLine.contains(wantCore)) score += 40
        }

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

    private fun parse(context: Context, file: File, format: LyricFormat): LocalLyric? {
        val path = file.absolutePath
        val mtime = file.lastModified()
        cache[path]?.let { if (it.mtime == mtime) return it.lyric }
        val lyric = runCatching {
            when (format) {
                LyricFormat.KRC -> LyricParsers.parseKrc(file.readBytes())
                LyricFormat.QRC -> LyricParsers.parseQrcFile(file.readBytes())
                LyricFormat.LRC -> LyricParsers.parseLrcText(file.readText())
                LyricFormat.NETEASE -> LyricParsers.parseNetease(file.readText())
                LyricFormat.LRCX -> {
                    val text = file.readText()
                    LyricParsers.parseKuwoLrcx(text) ?: LyricParsers.parseLrcText(text)
                }
            }
        }.getOrNull()
        if (cache.size > 600) cache.clear()
        cache[path] = Entry(mtime, lyric)
        if (lyric != null && lyric.lines.isNotEmpty()) {
            runCatching { LyricIndex.remember(context, file, lyric) }
        }
        return lyric
    }

    /** 索引命中：直接用之前记录过的文件，跳过整目录扫描 */
    private fun indexLookup(
        context: Context,
        recipe: LocalRecipe,
        mediaId: String?,
        durationMs: Long,
        wantTitle: String,
        wantCore: String,
        wantArtist: String,
        now: Long,
        log: (String) -> Unit
    ): LocalLyric? {
        val files = LyricIndex.lookup(context, wantTitle, wantArtist, mediaId)
        if (files.isEmpty()) return null
        for (file in files) {
            val format = formatOf(recipe, file) ?: continue
            val lyric = parse(context, file, format) ?: continue
            if (lyric.lines.isEmpty()) continue
            val score = scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now)
            if (score >= ACCEPT_SCORE) {
                log("索引命中 score=$score: ${file.name}")
                return lyric
            }
        }
        return null
    }

    /** 索引里的文件不带来源信息，按后缀回溯格式，兜底用配方的第一项 */
    private fun formatOf(recipe: LocalRecipe, file: File): LyricFormat? {
        val name = file.name.lowercase()
        for (src in recipe.sources) {
            if (src.ext.isNotEmpty() && src.ext.any { name.endsWith(".$it") }) return src.format
        }
        return when {
            name.endsWith(".krc") -> LyricFormat.KRC
            name.endsWith(".qrc") -> LyricFormat.QRC
            name.endsWith(".lrcx") -> LyricFormat.LRCX
            name.endsWith(".alm3ll") -> LyricFormat.LRC
            else -> recipe.sources.firstOrNull()?.format
        }
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

    /**
     * 歌名核心词：先砍掉括号里的版本说明 / 副标题，再归一化。
     * 「此生不换 (记忆是条长线)」→「此生不换」，「情网2026(DJ咚鼓版)」→「情网2026」。
     */
    fun titleCore(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var cut = text.indexOfFirst {
            it == '(' || it == '（' || it == '[' || it == '【' || it == '《' ||
                it == '_' || it == '-' || it == '–' || it == '—'
        }
        if (cut < 0) cut = text.length
        val head = if (cut > 1) text.substring(0, cut) else text
        val core = normalize(head)
        return if (core.isNotEmpty()) core else normalize(text)
    }

    /** 两个歌名是否指向同一首歌：核心词相等或互相包含 */
    fun titlesMatch(a: String?, b: String?): Boolean {
        val x = titleCore(a)
        val y = titleCore(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    /**
     * 兜底认领前的身份校验：歌词自带的 id / 歌名 / 歌手只要与当前歌曲**明确冲突**就不认领。
     * 「按总时长猜歌」最容易在这里翻车——实测酷狗把 3 分 23 秒的《怎叹》认成了
     * 3 分 28 秒的《此生不换》，网易云把另一首歌的缓存认成了当前歌曲。
     */
    private fun identityConflicts(
        lyric: LocalLyric,
        wantCore: String,
        wantArtist: String,
        mediaId: String
    ): Boolean {
        val embeddedId = normalize(lyric.id)
        if (embeddedId.isNotEmpty() && mediaId.isNotEmpty() && embeddedId != mediaId) return true
        val embeddedCore = titleCore(lyric.title)
        if (embeddedCore.isNotEmpty() && wantCore.isNotEmpty() &&
            !(embeddedCore == wantCore || embeddedCore.contains(wantCore) || wantCore.contains(embeddedCore))
        ) return true
        val embeddedArtist = normalize(lyric.artist)
        if (embeddedArtist.isNotEmpty() && wantArtist.isNotEmpty() &&
            !(embeddedArtist.contains(wantArtist) || wantArtist.contains(embeddedArtist))
        ) return true
        return false
    }

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
