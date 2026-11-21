package io.github.proify.astrafirmament.lyric.online

/**
 * 兜底源：lrclib.net（公开、无需 key、海外直连，国内也能通但要给足超时）。
 *
 * 实测结论（决定参数怎么传）：
 *  - `?track_name=X&artist_name=Y` 召回质量最好，20 条里大多是同名同歌手；
 *  - `?q=` 会把标题带回「歌手 - 歌名」「[Official Video]」这类脏数据，反而更难匹配；
 *  - 带 `(Live)` 后缀去查会直接没有结果，所以传的是 [MatchQuery.cleanTitle]；
 *  - 它按整秒（甚至小数）给时长，与宿主差几秒属正常，交给 [LyricMatch] 判定。
 *
 * 它经常 503/超时，失败后进入冷却，避免每首歌都白等一次超时。只收 `syncedLyrics`。
 */
internal object LrclibProvider : OnlineProvider {

    /** 展示名，同时写进 [LyricPayload.source] */
    const val SOURCE = "LRCLIB"

    override val name: String = SOURCE

    private const val REFERER = "https://lrclib.net"
    private const val SEARCH_URL = "https://lrclib.net/api/search"

    /** 失败后的冷却：期间直接跳过，不浪费 search 的时间预算 */
    private const val COOLDOWN_MS = 10L * 60L * 1_000L

    /** 剩余预算低于它就别再发请求 */
    private const val MIN_LEFT_MS = 400L

    private const val SECONDS_TO_MS = 1_000.0

    @Volatile
    private var cooldownUntil = 0L

    override fun fetch(
        query: MatchQuery,
        deadline: Long,
        log: (String) -> Unit
    ): LyricPayload? = runCatching {
        if (System.currentTimeMillis() < cooldownUntil) {
            log("$name 冷却中，跳过")
            return null
        }
        // 它的接口把歌名/歌手分成两个参数，所以变体是「参数组合」而不是关键词：
        // 整串歌手搜不到时退到首位歌手，再退到只给歌名（它自己按标题召回）。
        for ((index, pair) in variants(query).withIndex()) {
            if (OnlineHttp.remaining(deadline) < MIN_LEFT_MS) return null
            if (index > 0) log("$name 换参数重试：${pair.first} / ${pair.second}")
            attempt(query, pair.first, pair.second, deadline, log)?.let { return it }
        }
        null
    }.getOrElse { error ->
        log("$name 异常：" + error.javaClass.simpleName)
        null
    }

    /** 参数组合的优先级：整串歌手 → 首位歌手 → 只给歌名（去重、保持顺序） */
    private fun variants(query: MatchQuery): List<Pair<String, String>> {
        val title = query.cleanTitle
        return listOf(
            title to query.artist.orEmpty().trim(),
            title to query.firstArtist,
            title to ""
        ).distinct()
    }

    /** 一组参数走完「搜 → 挑 → 建 payload」；没结果返回 null 让上层换组合 */
    private fun attempt(
        query: MatchQuery,
        title: String,
        artist: String,
        deadline: Long,
        log: (String) -> Unit
    ): LyricPayload? {
        val array = search(title, artist, deadline, log) ?: return null
        if (array.length() == 0) {
            log("$name 无搜索结果")
            return null
        }
        val picked = pickSynced(query, array, log) ?: return null
        val lines = OnlineLines.build(picked.optString("syncedLyrics"))
        if (lines == null) {
            log("$name 歌词解析失败: " + picked.optString("trackName"))
            return null
        }
        val payload = LyricPayload(
            lines = lines,
            title = picked.optString("trackName").ifBlank { null },
            artist = picked.optString("artistName").ifBlank { null },
            source = SOURCE,
            id = picked.optLong("id", 0L).takeIf { it > 0L }?.toString(),
            durationMs = durationOf(picked)
        )
        log("$name 命中：${payload.title}，${lines.size} 行")
        return payload
    }

    /** 搜索；传输失败进冷却，返回 null 表示「这次彻底没戏」 */
    private fun search(
        title: String,
        artist: String,
        deadline: Long,
        log: (String) -> Unit
    ): org.json.JSONArray? {
        val url = SEARCH_URL + "?track_name=" + OnlineHttp.encode(title) +
            "&artist_name=" + OnlineHttp.encode(artist)
        val body = OnlineHttp.get(url, REFERER, deadline, log)
        if (body.isNullOrBlank()) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
            log("$name 不可用，进入冷却")
            return null
        }
        val array = OnlineHttp.arrayOf(body)
        if (array == null) {
            // 404 之类的错误体也是「没数据」，不算服务故障，不冷却
            log("$name 返回非 JSON 数组")
            return null
        }
        return array
    }

    /** 用 [LyricMatch] 挑最快照：只要 syncedLyrics、排除纯音乐 */
    private fun pickSynced(
        query: MatchQuery,
        array: org.json.JSONArray,
        log: (String) -> Unit
    ): org.json.JSONObject? {
        val items = ArrayList<org.json.JSONObject>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("syncedLyrics").isBlank()) continue
            if (item.optBoolean("instrumental", false)) continue
            items.add(item)
        }
        if (items.isEmpty()) {
            log("$name 无同步歌词")
            return null
        }
        val candidates = items.map { item ->
            LyricMatch.Candidate(
                id = item.optLong("id", 0L).toString(),
                title = item.optString("trackName"),
                artist = item.optString("artistName"),
                durationMs = durationOf(item)
            )
        }
        val picked = LyricMatch.pick(query, candidates, name, log) ?: return null
        return items.firstOrNull { it.optLong("id", 0L).toString() == picked.id }
    }

    /** LRCLIB 的 `duration` 是秒（可能是小数） */
    private fun durationOf(item: org.json.JSONObject): Long =
        (item.optDouble("duration", 0.0) * SECONDS_TO_MS).toLong()
}
