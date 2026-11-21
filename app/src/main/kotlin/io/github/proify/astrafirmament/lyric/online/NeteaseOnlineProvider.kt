package io.github.proify.astrafirmament.lyric.online

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.astrafirmament.lyric.LyricParsers
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线歌词主源：网易云音乐公开接口（不需要登录）。
 *
 *   搜索  /api/search/get?s=<歌名 歌手>&type=1&limit=N
 *   歌词  /api/song/lyric?id=<id>&lv=1&kv=1&tv=1&yv=1&ytv=1&yr=1
 *
 * 它是唯一能稳定给「逐字 + 译文 + 音译」的源，所以排在并行链第一位：同分时优先
 * 用它的结果。候选打分不再自己一套，统一走 [LyricMatch]，保证和酷狗/QQ 判得一样。
 */
internal object NeteaseOnlineProvider : OnlineProvider {

    /** 展示名，同时写进 [LyricPayload.source] */
    const val SOURCE = "网易云音乐"

    override val name: String = SOURCE

    private const val REFERER = "https://music.163.com"
    private const val SEARCH_URL = "https://music.163.com/api/search/get"
    private const val LYRIC_URL = "https://music.163.com/api/song/lyric"
    private const val SEARCH_LIMIT = 10
    private const val SUCCESS_CODE = 200

    /** 最多为几首候选试歌词接口：它是主源，多试一次比换源便宜 */
    private const val MAX_TRIES = 2

    /** 剩余预算低于它就别再发请求 */
    private const val MIN_LEFT_MS = 400L

    /** 正名之外，别名/译名也算命中（中文库里这两项才写的是中文名） */
    private val NAME_KEYS = arrayOf("alias", "transNames")

    override fun fetch(
        query: MatchQuery,
        deadline: Long,
        log: (String) -> Unit
    ): LyricPayload? = runCatching {
        QueryVariants.each(query, deadline) { keyword, index ->
            if (index > 0) log("$name 换关键词重试：$keyword")
            attempt(query, keyword, deadline, log)
        }
    }.getOrElse { error ->
        log("$name 异常：" + error.javaClass.simpleName)
        null
    }

    /** 一个搜索词走完「搜 → 排序 → 试歌词」；拿不到就返回 null 让上层换变体 */
    private fun attempt(
        query: MatchQuery,
        keyword: String,
        deadline: Long,
        log: (String) -> Unit
    ): LyricPayload? {
        val songs = searchSongs(keyword, deadline, log)
        if (songs.isEmpty()) {
            log("$name 无搜索结果")
            return null
        }
        var tries = 0
        for (song in order(query, songs, log)) {
            if (tries >= MAX_TRIES) break
            if (OnlineHttp.remaining(deadline) < MIN_LEFT_MS) break
            tries++
            payloadOf(song, deadline, log)?.let { return it }
        }
        log("$name 尝试 $tries 首均无可用歌词")
        return null
    }

    /** 先按 [LyricMatch] 打分排序，再让 [LyricMatch.pick] 定一个最可信的排到最前 */
    private fun order(
        query: MatchQuery,
        songs: List<JSONObject>,
        log: (String) -> Unit
    ): List<JSONObject> {
        val ranked = songs
            .map { it to LyricMatch.score(query, candidateOf(it)) }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
        if (ranked.isEmpty()) return emptyList()
        val candidates = ranked.map { candidateOf(it.first) }
        val picked = LyricMatch.pick(query, candidates, name, log) ?: return emptyList()
        val top = ranked.firstOrNull { candidateOf(it.first).id == picked.id } ?: return ranked.map { it.first }
        return listOf(top.first) + ranked.map { it.first }.filter { it !== top.first }
    }

    /** 搜索接口 → 歌曲 JSON 列表（保留原文，后续取 id/时长仍从这里读） */
    private fun searchSongs(
        keyword: String,
        deadline: Long,
        log: (String) -> Unit
    ): List<JSONObject> {
        if (keyword.isBlank()) return emptyList()
        val url = SEARCH_URL + "?s=" + OnlineHttp.encode(keyword) + "&type=1&limit=" + SEARCH_LIMIT
        val body = OnlineHttp.get(url, REFERER, deadline, log) ?: return emptyList()
        val songs: JSONArray = OnlineHttp.jsonOf(body)
            ?.optJSONObject("result")
            ?.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<JSONObject>(songs.length())
        for (index in 0 until songs.length()) {
            songs.optJSONObject(index)?.let { out.add(it) }
        }
        log("$name 搜索到 ${out.size} 首")
        return out
    }

    /** 网易云歌曲 JSON → 统一候选（别名/译名塞进 altTitles 一起参与歌名比对） */
    private fun candidateOf(song: JSONObject): LyricMatch.Candidate = LyricMatch.Candidate(
        id = song.optLong("id", 0L).toString(),
        title = song.optString("name"),
        artist = artistNames(song),
        durationMs = song.optLong("duration", 0L),
        altTitles = altTitlesOf(song)
    )

    /** `alias`/`transNames` 两个数组里的字符串全收进来 */
    private fun altTitlesOf(song: JSONObject): List<String> {
        val titles = ArrayList<String>(2)
        for (key in NAME_KEYS) {
            val array = song.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val one = array.optString(index).trim()
                if (one.isNotEmpty()) titles.add(one)
            }
        }
        return titles
    }

    /** 取一首歌的歌词；任何一步失败返回 null，让调用方继续下一首 */
    private fun payloadOf(
        song: JSONObject,
        deadline: Long,
        log: (String) -> Unit
    ): LyricPayload? {
        val id = song.optLong("id", 0L)
        if (id <= 0L) return null
        val body = OnlineHttp.get(lyricUrl(id), REFERER, deadline, log) ?: return null
        val obj = OnlineHttp.jsonOf(body) ?: return null
        if (obj.optInt("code", 0) != SUCCESS_CODE) {
            log("$name 歌词接口 code=" + obj.optInt("code"))
            return null
        }
        val lines = buildLines(
            lrc = lyricOf(obj, "lrc"),
            yrc = lyricOf(obj, "yrc"),
            tlyric = lyricOf(obj, "tlyric"),
            romalrc = lyricOf(obj, "romalrc")
        ) ?: return null
        val payload = LyricPayload(
            lines = lines,
            title = song.optString("name").ifBlank { null },
            artist = artistNames(song),
            source = SOURCE,
            id = id.toString(),
            durationMs = song.optLong("duration", 0L)
        )
        log("$name 命中：${payload.title}，${lines.size} 行")
        return payload
    }

    /** 歌手数组拼成 `A/B`；[LyricMatch.artistHit] 会按分隔符切开逐个比 */
    private fun artistNames(song: JSONObject): String? {
        val array = song.optJSONArray("artists") ?: return null
        val names = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val singer = array.optJSONObject(index)?.optString("name").orEmpty().trim()
            if (singer.isNotEmpty()) names.add(singer)
        }
        return names.joinToString("/").ifEmpty { null }
    }

    /** 逐字优先，退回普通行；译文贴 translation、音译贴 roma */
    private fun buildLines(
        lrc: String,
        yrc: String,
        tlyric: String,
        romalrc: String
    ): List<RichLyricLine>? {
        val base: List<RichLyricLine> = when {
            yrc.isNotBlank() -> LyricParsers.parseNeteaseYrc(yrc)
            lrc.isNotBlank() -> LyricParsers.parseAnyPayload(lrc)?.lines.orEmpty()
            else -> emptyList()
        }
        if (base.size < OnlineLines.MIN_LINES) return null
        val lines = LyricLines.normalize(base)
        val translated = LyricLines.pairs(tlyric)
        val transliterated = LyricLines.pairs(romalrc)
        if (translated.isNotEmpty() || transliterated.isNotEmpty()) {
            LyricLines.attach(lines, translated, transliterated)
        }
        return lines
    }

    /** lv=普通行 kv=逐字 tv=译文 yv=逐字 ytv=逐字译文 yr=音译 */
    private fun lyricUrl(id: Long): String =
        LYRIC_URL + "?id=" + id + "&lv=1&kv=1&tv=1&yv=1&ytv=1&yr=1"

    /** 接口把歌词放在 `{lrc:{lyric:"..."}}` 这层壳里 */
    private fun lyricOf(obj: JSONObject, key: String): String =
        obj.optJSONObject(key)?.optString("lyric").orEmpty()
}
