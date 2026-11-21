package io.github.proify.astrafirmament.lyric.online

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.astrafirmament.lyric.LyricParsers

/**
 * 一次在线查词的统一入参。
 *
 * 宿主给的原始歌名常带 `(feat. xx)`、`- Remastered 2011` 这类尾巴，直接拿去搜会把
 * 结果带偏，所以这里同时准备两份搜索词：各源先试 [cleanKeyword]，为空再退回 [keyword]。
 */
internal class MatchQuery(
    val title: String,
    val artist: String?,
    val durationMs: Long
) {
    /** 原样搜索词：`歌名 歌手`，短歌名时比清洗版更保险 */
    val keyword: String = buildKeyword(title, artist)

    /** 清洗后的歌名：去掉括号段/feat/修饰词，保留大小写与空格 */
    val cleanTitle: String = LyricMatch.searchTitle(title).ifEmpty { title.trim() }

    /** 清洗后的搜索词：按「歌名 歌手」拼，Spotify 场景命中率明显更高 */
    val cleanKeyword: String = buildKeyword(cleanTitle, artist).ifEmpty { keyword }

    /** 归一化歌名，供源侧做二次确认 */
    val normalizedTitle: String = LyricMatch.normalizeTitle(title)

    /** 多歌手串的第一位：源站按整串歌手搜经常直接 0 结果 */
    val firstArtist: String = LyricMatch.primaryArtist(artist)

    /**
     * 按优先级排好的搜索词（主查询 → 逐步降级），由 [QueryVariants] 逐个尝试。
     *
     * 顺序就是优先级：先最精确的「歌名 + 全部歌手」，不行才只留首位歌手、再不行
     * 只搜歌名。降级放在后面，保证「本来能命中的精确查询」不会被宽松查询挤掉。
     */
    val searchKeywords: List<String> by lazy(LazyThreadSafetyMode.NONE) { buildKeywords() }

    private fun buildKeywords(): List<String> {
        val out = ArrayList<String>(QueryVariants.MAX)
        fun add(value: String?) {
            val text = value?.trim().orEmpty()
            if (text.isNotEmpty() && out.size < QueryVariants.MAX && text !in out) out.add(text)
        }
        add(cleanKeyword)
        add(buildKeyword(cleanTitle, firstArtist))
        add(cleanTitle)
        add(buildKeyword(LyricMatch.foldLatin(cleanTitle), firstArtist))
        add(buildKeyword(LyricMatch.stripAllBrackets(cleanTitle), firstArtist))
        if (out.isEmpty()) add(keyword)
        return out
    }

    companion object {
        /** 歌手为空时只发歌名，避免出现尾部空格把搜索词变成两个词 */
        internal fun buildKeyword(title: String?, artist: String?): String {
            val name = title?.trim().orEmpty()
            val singer = artist?.trim().orEmpty()
            return if (singer.isEmpty()) name else if (name.isEmpty()) singer else "$name $singer"
        }
    }
}

/**
 * 搜索词变体调度：按 [MatchQuery.searchKeywords] 顺序尝试，首个非空结果即停。
 *
 * 为什么要变体：宿主标题常带 `(feat. X)`、`- Remastered 2011`、`A / B` 多歌手，
 * 而源站的搜索索引对「整串关键词」很敏感——多一个词整页就可能变成翻唱或直接 0 结果。
 * 主查询保持原样（最精确），只在拿不到可信候选时才降级。
 */
internal object QueryVariants {

    /** 一次 fetch 最多试几个变体：试太多会吃掉别的源的预算 */
    const val MAX = 4

    /** 剩余预算低于它就别再换关键词：一个往返都读不完 */
    const val MIN_LEFT_MS = 700L

    /**
     * 逐个变体调用 [attempt]，返回第一个非空结果；[attempt] 的入参是（关键词, 序号），
     * 序号 >0 表示这是降级变体，源侧可以据此调整日志或跳过额外跳转。
     */
    fun <T : Any> each(query: MatchQuery, deadline: Long, attempt: (String, Int) -> T?): T? {
        for ((index, keyword) in query.searchKeywords.withIndex()) {
            if (index > 0 && OnlineHttp.remaining(deadline) < MIN_LEFT_MS) return null
            attempt(keyword, index)?.let { return it }
        }
        return null
    }
}

/**
 * 在线歌词源统一接口：**同步阻塞**，自己的异常自己吞，拿不到词就返回 null。
 *
 * 查词链路（[OnlineLyricSource]）会把所有源并行拉起来，谁先返回有效结果用谁，
 * 所以 [fetch] 必须尊重 [deadline]（毫秒时间戳），到点立刻放弃而不是硬等。
 */
internal interface OnlineProvider {

    /** 展示给日志/UI 的源名，例如「网易云音乐」 */
    val name: String

    /** 查词；拿不到返回 null。禁止抛异常（实现内部用 runCatching 包住） */
    fun fetch(query: MatchQuery, deadline: Long, log: (String) -> Unit): LyricPayload?
}

/**
 * 各源共用的收尾：LRC 文本（+可选译文/音译）→ 有效歌词行。
 *
 * 三件事必须统一，否则不同源的行结构会打架：
 *  - 用同一套 [LyricParsers] 解析（在线/本地结果一致）；
 *  - [LyricLines.normalize] 排序并补齐 end/duration；
 *  - 译文贴 translation、音译贴 roma，没有就不贴（消费端据此决定是否画译文行）。
 */
internal object OnlineLines {

    /** 少于 3 行基本是「该歌曲暂无歌词」的占位文本，不算命中 */
    const val MIN_LINES = 3

    fun build(
        lrc: String?,
        translation: String? = null,
        roma: String? = null
    ): List<RichLyricLine>? {
        val text = lrc?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = runCatching { LyricParsers.parseAnyPayload(text) }.getOrNull() ?: return null
        val lines = runCatching { LyricLines.normalize(parsed.lines) }.getOrNull().orEmpty()
        if (lines.size < MIN_LINES) return null
        val translated = LyricLines.pairs(translation)
        val transliterated = LyricLines.pairs(roma)
        if (translated.isNotEmpty() || transliterated.isNotEmpty()) {
            runCatching { LyricLines.attach(lines, translated, transliterated) }
        }
        return lines
    }
}
