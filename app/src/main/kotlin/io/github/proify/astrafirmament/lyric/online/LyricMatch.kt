package io.github.proify.astrafirmament.lyric.online

import java.text.Normalizer

/**
 * 在线歌词的候选归一化与打分。
 *
 * 宿主（尤其 Spotify）只给「歌名 + 歌手 + 时长」，歌名还常带 `(feat. xxx)`、
 * ` - Remastered 2011`、`Live`、`伴奏` 这类后缀，直接拿原始字符串去和各平台
 * 搜索结果比几乎必错。这里把所有平台的候选与查询词收敛到同一套归一化结果上：
 *
 *  1. [normalizeTitle]：全角转半角 → 小写 → 去括号段 → 去 feat 段 → 去修饰词 →
 *     折叠拉丁重音 → 只留字母数字（含 CJK），这样 `晴天（Live）` 与 `晴天 - Live`
 *     都变成 `晴天`，`Beyoncé` 与 `Beyonce` 也归一；
 *  2. 歌名精确命中 [SCORE_EXACT]，包含命中 [SCORE_PARTIAL]；短 CJK 歌名不认包含，
 *     否则「晴天」会命中「晴天娃娃」；
 *  3. 歌手用**包含**判定（Spotify 常给 `A, B` 这种多歌手），命中加分、对不上扣分；
 *  4. 时长只加减分、**不做硬门槛**：Spotify 的时长来自它自己的母带，Live/Remaster
 *     差几十秒是常态，硬淘汰会把这些歌整首判死（v1 用 8s 红线，实测是主要漏词源）；
 *  5. 候选多了 Live/Remix/DJ 版本词而查询里没有，扣 [PENALTY_VERSION]。
 */
internal object LyricMatch {

    /** 各平台搜索结果统一映射成它，打分逻辑只认这几个字段 */
    class Candidate(
        /** 源侧 id（网易云数字 id / QQ songmid / 酷狗 hash） */
        val id: String,
        val title: String?,
        val artist: String?,
        /** 源侧时长（毫秒，0 = 未知） */
        val durationMs: Long = 0L,
        /** 别名/译名，任一命中就算歌名命中 */
        val altTitles: List<String> = emptyList()
    )

    /** 歌名归一化后完全相等 */
    const val SCORE_EXACT = 100

    /** 歌名一个包含另一个（够长才认） */
    const val SCORE_PARTIAL = 70

    /** 歌手包含命中 */
    const val SCORE_ARTIST = 40

    /** 歌手明确对不上：不直接淘汰，但基本要靠别的项补回来 */
    const val SCORE_ARTIST_MISS = -30

    /** 时长差 ≤2s / ≤5s / ≤15s 的三档加分 */
    const val SCORE_DURATION_NEAR = 30
    const val SCORE_DURATION_OK = 18
    const val SCORE_DURATION_FAR = 6

    /** 时长差超过 15s：扣分而不是淘汰（Live/Remaster/剪辑版差几十秒很常见） */
    const val SCORE_DURATION_MISMATCH = -12

    /** 候选是版本音（Live/Remix/DJ…）而查询不是 */
    const val PENALTY_VERSION = 35

    /** 候选是翻唱/改编版（标题写着「原唱」「钢琴版」）时的扣分 */
    const val PENALTY_COVER = 60

    /** 「歌名精确且唯一」兜底时允许的时长差：再远就是另一个录音（翻唱片段/钢琴版） */
    const val FALLBACK_MAX_DELTA_MS = 30_000L

    /** 达到这个分才认，低于就当没搜到（宁可不出词，也不出错的词） */
    const val ACCEPT_SCORE = 100

    /** 淘汰标记：负数分一律不参与比较 */
    private const val REJECTED = -1

    /** 歌名包含判定要求较短一方至少这么长，避免 2 字 CJK 满屏误命中 */
    private const val MIN_CONTAIN_LENGTH = 3

    /** 歌手 token 太短（单字母）没有区分度 */
    private const val MIN_ARTIST_TOKEN = 2

    /** 一次最多往日志里打多少条候选，防止刷屏 */
    private const val LOG_LIMIT = 8

    /** 版本音里的「硬」关键词：候选带这些而查询不带，直接淘汰（纯音乐没词、伴奏没原唱） */
    private val HARD_TAGS = setOf("instrumental", "karaoke", "伴奏", "纯音乐")

    /**
     * 标题里的翻唱标记。网易云缺版权时整页都是「晴天 (原唱 周杰伦)」这种翻唱，
     * 括号被剥掉后歌名精确、时长也接近，只能靠这个标记把它们压下去。
     */
    private val COVER_MARK = Regex("(?i)原唱|翻唱|cover|伴奏|纯音乐|钢琴版|八音盒|铃声|女声版|男声版")

    /**
     * 英文修饰词：在 `\b` 边界上整词删除。
     *
     * 必须 IGNORE_CASE：Spotify 给的是 `Remastered 2011`、`Live`、`Deluxe Edition`
     * 这种首字母大写的写法，区分大小写就一个都删不掉。
     */
    private val ASCII_NOISE = Regex(
        "\\b(remaster|remastered|remasterizado|live|remix|mix|version|edit|edition|" +
            "deluxe|acoustic|instrumental|karaoke|cover|mono|stereo|demo|explicit|clean|" +
            "bonus|extended|radio|anniversary|soundtrack|reprise|nightcore|sped|slowed|" +
            "dj|original|digital|remake|rework)\\b\\.?\\s*\\d{0,4}",
        RegexOption.IGNORE_CASE
    )

    /** 中文修饰词：没有词边界，直接按子串删 */
    private val CJK_NOISE = Regex(
        "伴奏|纯音乐|现场版|现场|翻唱|重制版|重制|完整版|女声版|男声版|女版|男版|" +
            "高音质|试听|片段|铃声|合唱版|升调版|降调版"
    )

    /** `feat.` / `ft.` / `featuring` 之后一律视为合作者信息，连同后面内容一起丢掉 */
    private val FEAT = Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*$")

    /** 成对括号段：先半角化，所以中文括号也已变成半角 */
    private val BRACKET = Regex("[(\\[{][^)\\]}]*[)\\]}]")

    /** 版本音关键词，用于「候选多了个 Live」这种扣分 */
    private val VERSION_WORDS = listOf(
        "live", "remix", "dj", "acoustic", "instrumental", "karaoke", "cover",
        "demo", "伴奏", "纯音乐", "翻唱", "现场", "片段", "铃声"
    )

    /**
     * 英文版本词按整词匹配：`Alive`、`Olive` 不能被当成 `live`，
     * 否则好候选会平白吃一次扣分。CJK 词没有词边界，直接按子串。
     */
    private val VERSION_REGEXES: List<Pair<String, Regex>> = VERSION_WORDS.map { word ->
        val pattern = if (word.all { it.code < 0x80 }) {
            "(^|[^a-z])" + Regex.escape(word) + "([^a-z]|$)"
        } else {
            Regex.escape(word)
        }
        word to Regex(pattern)
    }

    /**
     * ` - Remastered 2011` / ` - Live` / ` - 2011 Remastered` 这种尾巴整段砍掉。
     *
     * 只砍「破折号 + 版本词」：`Live Forever`、`Radio Ga Ga`、`Cover Me` 这类
     * **歌名本身就带版本词**的不会被误伤，否则关键词会搜成一堆无关结果。
     */
    private val TAIL_MARK = Regex(
        "\\s+-\\s+(?:\\d{4}\\s*)?(?:remaster|remastered|live|version|edit|edition|mix|" +
            "mono|stereo|acoustic|demo|deluxe|album|ost|soundtrack|original|radio|extended|" +
            "explicit|clean|bonus|single|ep|instrumental|karaoke|from|feat\\.?|ft\\.?)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 查询用的歌名：只做「括号段 / feat 段 / 破折号尾巴」三种清洗，**保留大小写与空格**，
     * 因为要直接拼成搜索关键词发给各平台。
     *
     * 这里故意不删 Live/Cover/Radio 这类词——它们既可能是噪音也可能是歌名本身
     * （`Live Forever`），删了会把搜索词带偏；比对时由 [normalizeTitle] 再删。
     */
    fun searchTitle(raw: String?): String {
        val text = halfWidth(raw.orEmpty()).trim()
        if (text.isEmpty()) return ""
        val cleaned = TAIL_MARK.replace(FEAT.replace(stripBrackets(text), " "), " ")
        return cleaned.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '_', '.', ',', '·')
    }

    /** 比对用的歌名：在 [searchTitle] 基础上去掉修饰词，再只留字母数字并小写 */
    fun normalizeTitle(raw: String?): String {
        val text = searchTitle(raw)
        if (text.isEmpty()) return ""
        return keepAlnum(CJK_NOISE.replace(ASCII_NOISE.replace(foldLatin(text), " "), " "))
    }

    /**
     * 拉丁重音折叠：`Beyoncé` → `Beyonce`、`Mötley` → `Motley`。
     *
     * 各平台的歌名写法不一致（欧美歌手名尤其明显），不折叠就等于两首歌；
     * 做法是 NFD 拆成基字母 + 组合符，再剔掉组合符，CJK 不受影响。
     */
    fun foldLatin(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            val type = Character.getType(c)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
            ) {
                continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /** 多歌手串的第一位：`A, B & C` → `A`；源站按整串歌手搜经常直接搜不到 */
    fun primaryArtist(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.split('/', '&', ',', ';', '、', '，', '·', '+', '\u00A0')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    /** 只剥括号、不动别的：给查询变体用（`歌名 (Live) [Explicit]` → `歌名`） */
    fun stripAllBrackets(raw: String?): String =
        stripBrackets(halfWidth(raw.orEmpty())).replace(Regex("\\s+"), " ").trim()

    /** 只留字母数字（CJK 也算字母），用于最终比对 */
    private fun keepAlnum(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    /** 全角 → 半角：`（`、`！`、`Ａ` 这些在 Spotify 上很常见 */
    private fun halfWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when {
                    c.code == 0x3000 -> ' '
                    c.code in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** 反复剥括号段，处理 `A (B (C))` 这种嵌套 */
    private fun stripBrackets(text: String): String {
        var out = text
        repeat(3) {
            val next = BRACKET.replace(out, " ")
            if (next == out) return out
            out = next
        }
        return out
    }

    /**
     * 从候选里挑出最可信的一个；挑不到返回 null（表示「这个源没词」，让下一个源上）。
     *
     * 判定顺序：先按 [score] 取最高分，够 [ACCEPT_SCORE] 才认；否则若「歌名精确」的
     * 候选只有一个，就无视时长红线采用它——宿主给的时长常来自另一个母带/Live 版本，
     * 差 10s 不代表不是同一首歌。逐条候选都会写进 [log]，方便真机取证。
     */
    fun pick(
        query: MatchQuery,
        candidates: List<Candidate>,
        source: String,
        log: (String) -> Unit
    ): Candidate? {
        if (candidates.isEmpty()) {
            log("$source 搜索无结果")
            return null
        }
        val pool = poolFor(query, candidates, source, log)
        var best: Candidate? = null
        var bestScore = 0
        var exact: Candidate? = null
        var exactCount = 0
        for ((index, candidate) in pool.withIndex()) {
            val scored = score(query, candidate)
            if (nameScore(query.title, candidate) == SCORE_EXACT) {
                exactCount++
                exact = candidate
            }
            if (scored > bestScore) {
                bestScore = scored
                best = candidate
            }
            if (index < LOG_LIMIT) log("$source 候选 " + describe(candidate, scored))
        }
        val winner = best
        if (winner != null && bestScore >= ACCEPT_SCORE) {
            log("$source 选中 " + describe(winner, bestScore))
            return winner
        }
        val only = exact
        if (exactCount == 1 && only != null && fallbackAllowed(query, only) &&
            !isRejected(versionTags(only.title) - versionTags(query.title))
        ) {
            log("$source 时长对不上但歌名精确且唯一，仍采用 " + describe(only, bestScore))
            return only
        }
        log("$source 无可信候选：共 ${pool.size} 个，最高 $bestScore 分（门槛 $ACCEPT_SCORE）")
        return null
    }

    /**
     * 兜底候选也要求时长别差太远。
     *
     * 实测「稻香 / 周杰伦 / 223s」在网易云只会搜到 120s 的翻唱版（版权缺失），
     * 若兜底完全不看时长，这种片段版会被当成原唱推给用户。
     */
    private fun fallbackAllowed(query: MatchQuery, candidate: Candidate): Boolean {
        if (query.durationMs <= 0L || candidate.durationMs <= 0L) return true
        return kotlin.math.abs(query.durationMs - candidate.durationMs) <= FALLBACK_MAX_DELTA_MS
    }

    /**
     * 查询带歌手时，若候选中有人歌名对得上歌手，就只在这些候选里挑。
     * 网易云缺版权时整页都是同名翻唱，不加这层会把翻唱当原唱。
     */
    private fun poolFor(
        query: MatchQuery,
        candidates: List<Candidate>,
        source: String,
        log: (String) -> Unit
    ): List<Candidate> {
        val want = query.artist
        if (want.isNullOrBlank() || candidates.size < 2) return candidates
        val hits = candidates.filter { artistHit(want, it.artist) }
        if (hits.isEmpty() || hits.size == candidates.size) return candidates
        log("$source 歌手优先：${hits.size}/${candidates.size} 个候选歌手对得上")
        return hits
    }

    /** 候选的一行日志：`晴天 / 周杰伦 / 269s → 100`；分数为负显示「淘汰」 */
    private fun describe(candidate: Candidate, scored: Int): String {
        val title = candidate.title?.takeIf { it.isNotBlank() } ?: "?"
        val artist = candidate.artist?.takeIf { it.isNotBlank() } ?: "?"
        val duration = if (candidate.durationMs > 0L) "${candidate.durationMs / 1_000}s" else "?"
        val verdict = if (scored < 0) "淘汰" else scored.toString()
        return "$title / $artist / $duration → $verdict"
    }

    /** 歌手是否对得上：按 `, & / 、` 切词后任意 token 互相包含即算命中 */
    fun artistHit(want: String?, got: String?): Boolean {
        val a = artistTokens(want)
        val b = artistTokens(got)
        if (a.isEmpty() || b.isEmpty()) return false
        for (x in a) {
            for (y in b) {
                if (x == y) return true
                if (x.length >= MIN_CONTAIN_LENGTH && y.contains(x)) return true
                if (y.length >= MIN_CONTAIN_LENGTH && x.contains(y)) return true
            }
        }
        return false
    }

    /** 歌手切词 + 归一化：`A, B & C` → [a, b, c]；重音折叠后再比，`Beyoncé`=`Beyonce` */
    private fun artistTokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('/', '&', ',', ';', '、', '，', '·', '+', ' ', '\u00A0')
            .map { keepAlnum(foldLatin(halfWidth(it))) }
            .filter { it.length >= MIN_ARTIST_TOKEN }
            .distinct()
    }

    /** 候选里出现了哪些版本词（Live/Remix/DJ…）；整词匹配，`Alive` 不算 `live` */
    fun versionTags(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val text = halfWidth(raw).lowercase()
        return VERSION_REGEXES.filterTo(LinkedHashSet()) { (_, regex) ->
            regex.containsMatchIn(text)
        }.mapTo(LinkedHashSet()) { (word, _) -> word }
    }

    /** 是否纯音乐/伴奏这类根本没有原唱歌词的候选 */
    fun isRejected(tags: Set<String>): Boolean = tags.any { it in HARD_TAGS }

    /**
     * 候选打分；返回 [REJECTED]（负数）表示直接淘汰。
     *
     * 淘汰只有两种原因：歌名对不上、版本音是纯音乐/伴奏。时长**只参与加减分**，
     * 因为宿主（Spotify）的时长来自它自己的母带，与源站差几十秒属于正常现象，
     * 拿它当硬门槛会让 Live/Remaster/剪辑版整类歌都出不了词。
     */
    fun score(query: MatchQuery, candidate: Candidate): Int {
        val name = nameScore(query.title, candidate) ?: return REJECTED
        val extraTags = versionTags(candidate.title) - versionTags(query.title)
        if (isRejected(extraTags)) return REJECTED
        var total = name
        total += artistScore(query.artist, candidate.artist)
        if (query.durationMs > 0L && candidate.durationMs > 0L) {
            total += durationScore(query.durationMs, candidate.durationMs)
        }
        if (extraTags.isNotEmpty()) total -= PENALTY_VERSION
        if (isCover(query, candidate)) total -= PENALTY_COVER
        return total
    }

    /** 候选标题带翻唱标记而查询没带 → 视为翻唱版扣分（不硬淘汰，留作同源唯一候选的兜底） */
    private fun isCover(query: MatchQuery, candidate: Candidate): Boolean {
        val got = candidate.title ?: return false
        if (!COVER_MARK.containsMatchIn(got)) return false
        return !COVER_MARK.containsMatchIn(query.title)
    }

    /** 歌名对不上返回 null（与「命中但 0 分」区分开） */
    private fun nameScore(title: String, candidate: Candidate): Int? {
        val query = normalizeTitle(title)
        if (query.isEmpty()) return null
        val names = ArrayList<String>(candidate.altTitles.size + 1)
        candidate.title?.let { names.add(it) }
        names.addAll(candidate.altTitles)
        var best: Int? = null
        for (name in names) {
            val got = normalizeTitle(name)
            if (got.isEmpty()) continue
            when {
                got == query -> return SCORE_EXACT
                containsEnough(query, got) -> best = maxOf(best ?: 0, SCORE_PARTIAL)
            }
        }
        return best
    }

    /** 包含判定：较短一方要够长，短 CJK 歌名（晴天/红豆）只认精确 */
    private fun containsEnough(a: String, b: String): Boolean {
        val short = minOf(a.length, b.length)
        if (short < MIN_CONTAIN_LENGTH) return false
        return a.contains(b) || b.contains(a)
    }

    private fun artistScore(want: String?, got: String?): Int {
        if (want.isNullOrBlank()) return 0
        val candidateArtist = got
        if (candidateArtist.isNullOrBlank()) return 0
        return if (artistHit(want, candidateArtist)) SCORE_ARTIST else SCORE_ARTIST_MISS
    }

    /**
     * 时长差只是权重：≤2s/≤5s/≤15s 三档加分，再远转为扣分。
     *
     * 15s 这档是给 Live/Remaster 留的——同一首歌的两个母带常差 10~15s，
     * 之前 8s 硬淘汰把它们全判死了。
     */
    private fun durationScore(a: Long, b: Long): Int {
        val delta = kotlin.math.abs(a - b)
        return when {
            delta <= 2_000L -> SCORE_DURATION_NEAR
            delta <= 5_000L -> SCORE_DURATION_OK
            delta <= 15_000L -> SCORE_DURATION_FAR
            else -> SCORE_DURATION_MISMATCH
        }
    }
}
