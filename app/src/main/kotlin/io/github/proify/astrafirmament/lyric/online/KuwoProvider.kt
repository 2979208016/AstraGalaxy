package io.github.proify.astrafirmament.lyric.online

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 酷我音乐：搜索 → 歌词，两跳全走 https，无需登录。
 *
 * 为什么加它：网易云丢了大批华语版权（周杰伦、五月天整片搜不到原唱，只剩翻唱），
 * 酷我曲库反而是全的；实测「晴天 周杰伦」第一条就是 `MUSIC_228908 | 晴天 | 周杰伦 | 269s`。
 *
 * 踩过的坑：
 *  - `www.kuwo.cn/api/www` 下的接口必须带 kw_token+csrf，而 www 域名在移动 UA 下会被 302 到
 *    `m.kuwo.cn/newh5app/`，拿不到 token，接口永远回「The request is illegal!」→ 放弃；
 *  - `m.kuwo.cn/newh5/singles/songinfoandlrc` 会**随机**回 `{"data":null,"status":301}`，
 *    同一个 id 连打 6 次约 3 次失败，所以必须重试（3 次能把成功率拉到 ~85%）；
 *    这种失败是「没验明」而非「确认无词」，要 [OnlineHttp.markUnverified]，否则会写死负缓存；
 *  - 它的歌词是**双语混排**：英文歌的原文行后面紧跟同时间戳的中文译文行，
 *    这里按时间戳配对拆开，原文当 text、中文当 translation（保留「原文+翻译」约定）。
 */
internal object KuwoProvider : OnlineProvider {

    override val name: String = "酷我音乐"

    private const val SEARCH_URL = "https://search.kuwo.cn/r.s"
    private const val LYRIC_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"
    private const val REFERER = "https://m.kuwo.cn/"

    /** 移动端搜索协议参数（公开接口，不带任何账号凭证） */
    private const val CLIENT_UID = "794762570"
    private const val CLIENT_VER = "kwplayer_ar_9.2.2.1"

    /** 一次搜索取几条：只在打分时有对手可比，真实下歌词的是第一名 */
    private const val PAGE_SIZE = 8

    /** 歌词接口随机拒答，3 次重试把单源成功率从 ~50% 拉到 ~85% */
    private const val MAX_LYRIC_TRIES = 3

    /** 重试间隔：同一毫秒连打更容易被判为爬虫 */
    private const val RETRY_GAP_MS = 80L

    /** 剩余预算低于它就别再发请求了 */
    private const val MIN_LEFT_MS = 700L

    private const val SECONDS_TO_MS = 1_000L

    /**
     * 译文行与原文行的时间容差（秒）：酷我两种排布并存——同时间戳成对，或中文行落在
     * 下一句原文的时间上（实测差 2.4~3s，即一句的时长）。容差放宽只对「紧邻且尚未配
     * 过译文」的行生效，不会把不相干的中文行拉进来。
     */
    private const val PAIR_WINDOW_S = 8.0

    private const val MUSIC_ID_PREFIX = "MUSIC_"

    /** 酷我在歌词正文里插的 AI 翻译声明行，不能当歌词推给用户 */
    private const val TRANSLATION_NOTICE = "翻译"

    /** 搜索页 + id 两段信息 */
    private class Song(val candidate: LyricMatch.Candidate, val musicId: String)

    /** 歌词里的原始一行：`time` 是秒（字符串小数），`text` 是正文 */
    private class Row(val second: Double, val text: String)

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

    /** 一个搜索词走完「搜 → 挑 → 下词」；拿不到可信候选就返回 null 让上层换变体 */
    private fun attempt(
        query: MatchQuery,
        keyword: String,
        deadline: Long,
        log: (String) -> Unit
    ): LyricPayload? {
        val songs = search(keyword, deadline, log)
        if (songs.isEmpty()) {
            log("$name 无搜索结果")
            return null
        }
        val picked = LyricMatch.pick(query, songs.map { it.candidate }, name, log) ?: return null
        val song = songs.firstOrNull { it.candidate.id == picked.id } ?: return null
        val lrc = lyric(song, query.title, deadline, log) ?: return null
        val (main, translation) = lrc
        val lines = OnlineLines.build(main, translation)
        if (lines == null) {
            log("$name 歌词解析失败：" + song.candidate.title)
            return null
        }
        log("$name 命中：${song.candidate.title}，${lines.size} 行")
        return LyricPayload(
            lines = lines,
            title = song.candidate.title,
            artist = song.candidate.artist,
            source = name,
            id = song.musicId,
            durationMs = song.candidate.durationMs
        )
    }

    /** 搜索接口 → 候选（`ALIAS` 当别名，中文歌的英文名常在这里） */
    private fun search(keyword: String, deadline: Long, log: (String) -> Unit): List<Song> {
        if (keyword.isBlank() || OnlineHttp.remaining(deadline) < MIN_LEFT_MS) return emptyList()
        val url = SEARCH_URL + "?client=kt&all=" + OnlineHttp.encode(keyword) +
            "&pn=0&rn=" + PAGE_SIZE + "&uid=" + CLIENT_UID + "&ver=" + CLIENT_VER +
            "&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012" +
            "&encoding=utf8&rformat=json&vermerge=1&mobi=1"
        val root = OnlineHttp.jsonOf(OnlineHttp.get(url, REFERER, deadline, log)) ?: return emptyList()
        val array = root.optJSONArray("abslist") ?: return emptyList()
        val songs = ArrayList<Song>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val musicId = item.optString("MUSICRID").removePrefix(MUSIC_ID_PREFIX)
            val title = item.optString("NAME")
            if (musicId.isBlank() || title.isBlank()) continue
            songs.add(
                Song(
                    candidate = LyricMatch.Candidate(
                        id = musicId,
                        title = title,
                        artist = item.optString("ARTIST"),
                        // DURATION 是秒（字符串，可能是小数），拿不到就当未知时长
                        durationMs = item.optString("DURATION").toDoubleOrNull()
                            ?.times(SECONDS_TO_MS)?.toLong() ?: 0L,
                        altTitles = aliasOf(item)
                    ),
                    musicId = musicId
                )
            )
        }
        log("$name 搜索到 ${songs.size} 首")
        return songs
    }

    /** `ALIAS` 有时是字符串、有时是数组，空串/空数组都当没有 */
    private fun aliasOf(item: JSONObject): List<String> {
        val raw = item.opt("ALIAS") ?: return emptyList()
        val list = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            else -> raw.toString().split('/', '|').map { it }
        }
        return list.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 歌词正文。两种返回形态都要处理：
     *  - 正常：`data.lrclist`；
     *  - 随机拒答：`data:null, status:301` → 重试；重试还不行就当「没验明」。
     */
    private fun lyric(
        song: Song,
        title: String,
        deadline: Long,
        log: (String) -> Unit
    ): Pair<String, String?>? {
        var status = 0
        for (attempt in 1..MAX_LYRIC_TRIES) {
            if (OnlineHttp.remaining(deadline) < MIN_LEFT_MS) return null
            if (attempt > 1) Thread.sleep(RETRY_GAP_MS)
            val url = LYRIC_URL + "?musicId=" + OnlineHttp.encode(song.musicId)
            val root = OnlineHttp.jsonOf(OnlineHttp.get(url, REFERER, deadline, log))
            status = root?.optInt("status", 0) ?: status
            val rows = parseRows(root?.optJSONObject("data")?.optJSONArray("lrclist"))
            if (rows.isNotEmpty()) return splitBilingual(rows, hasCjk(title))
            log("$name 歌词未就绪（status=$status），已试 $attempt 次")
        }
        // 连通但没数据：这次没验明，不能据此写负缓存
        OnlineHttp.markUnverified()
        return null
    }

    /** `lrclist` → 行模型；丢掉空行与酷我自己插的翻译声明 */
    private fun parseRows(array: JSONArray?): List<Row> {
        if (array == null) return emptyList()
        val rows = ArrayList<Row>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = item.optString("lineLyric").trim()
            if (text.isEmpty() || text.contains(TRANSLATION_NOTICE)) continue
            val second = item.optString("time").toDoubleOrNull() ?: continue
            rows.add(Row(second, text))
        }
        return rows
    }

    /**
     * 双语拆分：中文歌整段都是原文；外文歌里「原文行后面紧跟的中文行」当译文。
     *
     * 实测酷我两种排布并存：同时间戳成对，或中文行的时间戳等于**下一句**原文的时间
     * （`9.55 The club…` / `11.99 俱乐部…` / `11.99 So the bar…`）。按「相邻 + 时间
     * 差不超过 [PAIR_WINDOW_S]」配对两种都能覆盖，译文行沿用原文行的时间戳。
     */
    private fun splitBilingual(rows: List<Row>, chineseSong: Boolean): Pair<String, String?>? {
        if (rows.isEmpty()) return null
        if (chineseSong) {
            val lrc = toLrc(rows) ?: return null
            return lrc to null
        }
        val main = ArrayList<Row>(rows.size)
        val trans = ArrayList<Row>(rows.size)
        for (row in rows) {
            val last = main.lastOrNull()
            if (!hasCjk(row.text)) {
                main.add(row)
                continue
            }
            // 中文行：只有紧贴在原文行后面、时间也对得上才算译文，否则是纯中文行（丢弃）
            if (last != null && main.size > trans.size && sameTime(last, row)) {
                trans.add(Row(last.second, row.text))
            }
        }
        val lrc = toLrc(main) ?: return null
        val translation = if (trans.isEmpty()) null else toLrc(trans)
        return lrc to translation
    }

    /** 秒 → LRC 行；`[mm:ss.xx]正文` */
    private fun toLrc(rows: List<Row>): String? {
        if (rows.isEmpty()) return null
        val sb = StringBuilder(rows.size * 24)
        for (row in rows) {
            val minute = (row.second / 60).toInt()
            val second = row.second - minute * 60
            sb.append(String.format(Locale.US, "[%02d:%05.2f]", minute, second))
                .append(row.text).append('\n')
        }
        return sb.toString()
    }

    private fun sameTime(a: Row, b: Row): Boolean =
        kotlin.math.abs(a.second - b.second) <= PAIR_WINDOW_S

    /** 是否含中日韩文字：用来判定「这首歌是中文歌」与「这一行是译文」 */
    private fun hasCjk(text: String): Boolean = text.any { it.code in CJK_FIRST..CJK_LAST }

    private const val CJK_FIRST = 0x4E00
    private const val CJK_LAST = 0x9FFF
}
