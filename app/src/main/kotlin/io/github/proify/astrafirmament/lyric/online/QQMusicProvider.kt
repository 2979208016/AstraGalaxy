package io.github.proify.astrafirmament.lyric.online

/**
 * QQ 音乐：搜索 → 歌词，两跳全走 https，无需任何登录态。
 *
 * 接口选择上踩过两个坑，不要随手换回去：
 *  - `c.y.qq.com/soso/fcgi-bin/client_search_cp` 现在**稳定返回空响应**（HTTP 200 但
 *    0 字节），换 UA/Referer 都没用；
 *  - `u.y.qq.com/cgi-bin/musicu.fcg` 的 `DoSearchForQQMusicDesktop` 会被风控
 *    （`req_1.code=2001`、`is_filter=-12`，返回空列表）。
 *    能稳定用的是老接口 `soso/fcgi-bin/search_for_qq_cp`，字段 songname/songmid/
 *    interval(秒)/singer[]，够打分用。
 *
 * 歌词走 `fcg_query_lyric_new.fcg`，加 `nobase64=1` 直接拿明文 LRC（不加是 base64）。
 * `trans` 字段在这个匿名接口上实测恒为空（中日英歌都试过），所以译文只是「有则贴」。
 */
internal object QQMusicProvider : OnlineProvider {

    override val name: String = "QQ音乐"

    private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
    private const val LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
    private const val REFERER = "https://y.qq.com"

    /** 搜索取 10 条：QQ 同名翻唱/remix 多，多给几条让打分挑 */
    private const val PAGE_SIZE = 10

    /** 最多为几首歌试歌词接口（每个多一跳） */
    private const val MAX_TRIES = 2

    /** 剩余预算低于它就别再发请求，发出去也读不完 */
    private const val MIN_LEFT_MS = 600L

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
        val candidates = searchSongs(keyword, deadline, log)
        if (candidates.isEmpty()) {
            log("$name 无搜索结果")
            return null
        }
        val ordered = order(query, candidates, log)
        if (ordered.isEmpty()) {
            log("$name 候选均被淘汰")
            return null
        }
        var tries = 0
        for (candidate in ordered) {
            if (tries >= MAX_TRIES) break
            if (OnlineHttp.remaining(deadline) < MIN_LEFT_MS) break
            tries++
            val root = lyricOf(candidate, deadline, log) ?: continue
            val lines = OnlineLines.build(
                root.optString("lyric"),
                root.optString("trans"),
                root.optString("roma")
            ) ?: continue
            log("$name 命中：${candidate.title}，${lines.size} 行")
            return LyricPayload(
                lines = lines,
                title = candidate.title,
                artist = candidate.artist,
                source = name,
                id = candidate.id,
                durationMs = candidate.durationMs
            )
        }
        log("$name 尝试 $tries 首均无可用歌词")
        return null
    }

    /** 先让 [LyricMatch.pick] 定一个最可信的，再把它排到最前面，失败时按分数递减续试 */
    private fun order(
        query: MatchQuery,
        candidates: List<LyricMatch.Candidate>,
        log: (String) -> Unit
    ): List<LyricMatch.Candidate> {
        val scored = candidates
            .map { it to LyricMatch.score(query, it) }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        if (scored.isEmpty()) return emptyList()
        val picked = LyricMatch.pick(query, scored, name, log) ?: return emptyList()
        val top = scored.firstOrNull { it.id == picked.id } ?: return scored
        return listOf(top) + scored.filter { it !== top }
    }

    /** 搜索接口 → 统一候选（`interval` 是秒，转成毫秒） */
    private fun searchSongs(
        keyword: String,
        deadline: Long,
        log: (String) -> Unit
    ): List<LyricMatch.Candidate> {
        if (keyword.isBlank()) return emptyList()
        val url = SEARCH_URL + "?w=" + OnlineHttp.encode(keyword) +
            "&format=json&p=1&n=" + PAGE_SIZE + "&cr=1&t=0&aggr=1&lossless=0&flag_qc=0" +
            "&catZhida=1&remoteplace=txt.yqq.song&g_tk=5381&inCharset=utf8" +
            "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val body = OnlineHttp.get(url, REFERER, deadline, log) ?: return emptyList()
        val list = OnlineHttp.jsonOf(body)
            ?.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<LyricMatch.Candidate>(list.length())
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val mid = item.optString("songmid")
            if (mid.isEmpty()) continue
            out.add(
                LyricMatch.Candidate(
                    id = mid,
                    title = item.optString("songname"),
                    artist = singersOf(item),
                    durationMs = item.optLong("interval") * 1_000L
                )
            )
        }
        log("$name 搜索到 ${out.size} 首")
        return out
    }

    /** 多歌手拼成 `A/B`，[LyricMatch.artistHit] 会按分隔符切开逐个比 */
    private fun singersOf(item: org.json.JSONObject): String {
        val singers = item.optJSONArray("singer") ?: return ""
        val names = ArrayList<String>(singers.length())
        for (index in 0 until singers.length()) {
            val one = singers.optJSONObject(index) ?: continue
            val singerName = one.optString("name")
            if (singerName.isNotEmpty()) names.add(singerName)
        }
        return names.joinToString("/")
    }

    /** 单曲歌词；`retcode` 非 0 或没有正文都返回 null */
    private fun lyricOf(
        candidate: LyricMatch.Candidate,
        deadline: Long,
        log: (String) -> Unit
    ): org.json.JSONObject? {
        val url = LYRIC_URL + "?songmid=" + OnlineHttp.encode(candidate.id) +
            "&format=json&nobase64=1"
        val root = OnlineHttp.jsonOf(OnlineHttp.get(url, REFERER, deadline, log)) ?: return null
        val retcode = root.optInt("retcode")
        if (retcode != 0) {
            log("$name 歌词 retcode=$retcode")
            return null
        }
        if (root.optString("lyric").isBlank()) {
            log("$name 该曲无歌词")
            return null
        }
        return root
    }
}
