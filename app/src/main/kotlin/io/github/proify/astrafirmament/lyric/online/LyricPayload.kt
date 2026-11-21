package io.github.proify.astrafirmament.lyric.online

import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * 在线歌词结果。
 *
 * [lines] 已按 begin 升序、end/duration 补齐，译文贴在 `translation`、音译贴在
 * `roma`、逐字贴在 `words` 上，调用方直接塞进 Song 推送即可，不需要再加工。
 * 译文与原文必须落在同一行——这是星流/词幕消费端的前提。
 */
class LyricPayload(
    /** 逐行歌词：begin/end/text 就绪，译文在 translation */
    val lines: List<RichLyricLine>,
    /** 命中的歌名（来自在线库，可能与查询词写法不同） */
    val title: String? = null,
    /** 命中的歌手 */
    val artist: String? = null,
    /** 来源名，用于日志/调试，如「网易云音乐」 */
    val source: String = "online",
    /** 来源侧的歌曲 id，便于排查 */
    val id: String? = null,
    /** 来源侧给出的总时长（毫秒，0 = 未知） */
    val durationMs: Long = 0L
) {
    /** 是否有译文；消费端据此决定要不要显示翻译行 */
    val hasTranslation: Boolean = lines.any { !it.translation.isNullOrBlank() }
}
