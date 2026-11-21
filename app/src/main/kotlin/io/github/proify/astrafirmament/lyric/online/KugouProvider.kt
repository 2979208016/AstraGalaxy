package io.github.proify.astrafirmament.lyric.online

/**
 * 酷狗音乐：搜索 → 歌词候选 → 下载 LRC，三跳全走 https。
 *
 * 踩过的坑，改之前务必看清：
 *  - 搜索串用的 `mobiles.kugou.com` 是 `/api/v3/search/song` 的 https 镜像；
 *    `mobilecdn.kugou.com` 同名接口的 https 证书链在这台机器上直接握手失败
 *    （curl code=000），只有明文 http 能用，而 targetSdk 36 默认禁明文，所以必须走镜像；
 *  - 歌词正文是 base64 的 LRC（`fmt=lrc`）。`fmt=krc` 也能下，但 KRC 要解密、
 *    且逐字对在线场景收益不大，这里只用 LRC；
 *  - 酷狗不提供译文（歌词候选的 transuid 恒为 0），所以不贴 translation。
 */
internal object KugouProvider : OnlineProvider {

    override val name: String = "酷狗音乐"

    private const val SEARCH_URL = "https://mobiles.kugou.com/api/v3/search/song"
    private const val CANDIDATE_URL = "https://krcs.kugou.com/search"
    private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"
    private const val REFERER = "https://www.kugou.com/"

    /** 一次搜索取几条：再多也只会试前两条下歌词，取值偏大只为打分时有对手可比 */
    private const val PAGE_SIZE = 8

    /** 最多为几首歌尝试下载歌词：每个候选都要多花两跳，预算内只能试这么多次 */
    private const val MAX_TRIES = 2

    /** 剩余预算低于这个数就别再发请求了，发出去也读不完 */
    private const val MIN_LEFT_MS = 600L

    /** 酷狗搜索结果里，歌词候选要用的两个 id */
    private class Song(val candidate: LyricMatch.Candidate, val albumAudioId: String)

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

    /** 一个搜索词走完「搜 → 排序 → 试下载」；拿不到就返回 null 让上层换变体 */
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
        val ordered = orderSongs(query, songs, log)
        if (ordered.isEmpty()) {
            log("$name 候选均被淘汰")
            return null
        }
        var tries = 0
        for (song in ordered) {
            if (tries >= MAX_TRIES) break
            if (OnlineHttp.remaining(deadline) < MIN_LEFT_MS) break
            tries++
            val text = lyricText(song, deadline, log) ?: continue
            val lines = OnlineLines.build(text) ?: continue
            log("$name 命中：${song.candidate.title}，${lines.size} 行")
            return LyricPayload(
                lines = lines,
                title = song.candidate.title,
                artist = song.candidate.artist,
                source = name,
                id = song.candidate.id,
                durationMs = song.candidate.durationMs
            )
        }
        log("$name 尝试 $tries 首均无可用歌词")
        return null
    }

    /** 搜索并按可信度排序：胜出的候选排第一，其余按分数递减（下歌词失败时接着试） */
    private fun orderSongs(
        query: MatchQuery,
        songs: List<Song>,
        log: (String) -> Unit
    ): List<Song> {
        val scored = songs
            .map { it to LyricMatch.score(query, it.candidate) }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        if (scored.isEmpty()) return emptyList()
        val picked = LyricMatch.pick(query, scored.map { it.candidate }, name, log) ?: return emptyList()
        val top = scored.firstOrNull { it.candidate.id == picked.id } ?: return scored
        return listOf(top) + scored.filter { it !== top }
    }

    /** 搜索接口 → 候选列表（时长为 0 的条目照样收，打分时按「未知」处理） */
    private fun searchSongs(
        keyword: String,
        deadline: Long,
        log: (String) -> Unit
    ): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val url = SEARCH_URL + "?format=json&keyword=" + OnlineHttp.encode(keyword) +
            "&page=1&pagesize=" + PAGE_SIZE
        val body = OnlineHttp.get(url, REFERER, deadline, log) ?: return emptyList()
        val info = OnlineHttp.jsonOf(body)?.optJSONObject("data")?.optJSONArray("info")
            ?: return emptyList()
        val songs = ArrayList<Song>(info.length())
        for (index in 0 until info.length()) {
            val item = info.optJSONObject(index) ?: continue
            val hash = item.optString("hash")
            if (hash.isEmpty()) continue
            songs.add(
                Song(
                    candidate = LyricMatch.Candidate(
                        id = hash,
                        title = item.optString("songname"),
                        artist = item.optString("singername"),
                        durationMs = durationOf(item)
                    ),
                    albumAudioId = item.optString("album_audio_id")
                )
            )
        }
        log("$name 搜索到 ${songs.size} 首")
        return songs
    }

    /** `timelength` 是毫秒，`duration` 是秒（偶尔是字符串），优先用前者 */
    private fun durationOf(item: org.json.JSONObject): Long {
        val millis = item.optLong("timelength")
        if (millis > 0L) return millis
        val seconds = item.optLong("duration")
        return if (seconds > 0L) seconds * 1_000L else 0L
    }

    /** 拿这首歌的 LRC 正文：先查歌词候选，再逐个下载，首个成功的直接返回 */
    private fun lyricText(song: Song, deadline: Long, log: (String) -> Unit): String? {
        val entries = lyricCandidates(song, deadline, log)
        for ((id, accessKey) in entries) {
            if (OnlineHttp.remaining(deadline) < MIN_LEFT_MS) return null
            val url = DOWNLOAD_URL + "?ver=1&client=pc&id=" + OnlineHttp.encode(id) +
                "&accesskey=" + OnlineHttp.encode(accessKey) + "&fmt=lrc&charset=utf8"
            val root = OnlineHttp.jsonOf(OnlineHttp.get(url, REFERER, deadline, log)) ?: continue
            val status = root.optInt("status")
            if (status != 200) {
                log("$name 下载歌词 status=$status")
                continue
            }
            val text = OnlineHttp.decodeBase64(root.optString("content"))
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /** 歌词候选：官方推荐优先（分数加个大偏移），其次按匹配分 */
    private fun lyricCandidates(
        song: Song,
        deadline: Long,
        log: (String) -> Unit
    ): List<Pair<String, String>> {
        val seconds = song.candidate.durationMs / 1_000L
        val url = CANDIDATE_URL + "?ver=1&man=yes&client=mobi&hash=" +
            OnlineHttp.encode(song.candidate.id) + "&duration=" + seconds +
            "&album_audio_id=" + OnlineHttp.encode(song.albumAudioId)
        val body = OnlineHttp.get(url, REFERER, deadline, log) ?: return emptyList()
        val array = OnlineHttp.jsonOf(body)?.optJSONArray("candidates") ?: return emptyList()
        val ranked = ArrayList<Ranked>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val accessKey = item.optString("accesskey")
            if (id.isEmpty() || accessKey.isEmpty()) continue
            val official = item.optString("product_from").contains("官方")
            val score = item.optInt("score")
            ranked.add(Ranked(id, accessKey, score + if (official) OFFICIAL_BONUS else 0))
        }
        if (ranked.isEmpty()) {
            log("$name 无歌词候选")
            return emptyList()
        }
        return ranked.sortedByDescending { it.weight }
            .take(MAX_TRIES)
            .map { it.id to it.accessKey }
    }

    /** 酷狗歌词候选的排序权重 */
    private class Ranked(val id: String, val accessKey: String, val weight: Int)

    /** 官方推荐歌词（有人工校对的滚动歌词）优先于 UGC */
    private const val OFFICIAL_BONUS = 1_000
}
