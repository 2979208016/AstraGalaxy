package io.github.proify.astrafirmament.lyric.online

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.astrafirmament.lyric.LyricParsers

/**
 * 在线歌词的统一收尾：排序、补 end/duration、贴译文与音译。
 *
 * 复用本地歌词那套解析器（[LyricParsers]），避免在线/本地两条链路对同一份
 * LRC 给出不同结果。
 */
internal object LyricLines {

    /** 最后一行没有下一行可参照时给的后备时长 */
    private const val LAST_LINE_FALLBACK_MS = 4_000L

    /** 译文与正文时间戳的容差（在线库逐行对齐，比本地缓存紧一点） */
    private const val TRANSLATION_TOLERANCE_MS = 300L

    /** 音译容差放宽：网易云 romalrc 与正文常错位几百毫秒 */
    private const val ROMA_TOLERANCE_MS = 800L

    /** 排序并补齐每行/每个字的时间轴；原地改属性，返回同一个列表 */
    fun normalize(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.begin }
        sorted.forEachIndexed { index, line ->
            val next = sorted.getOrNull(index + 1)?.begin
            val end = (next ?: (line.begin + LAST_LINE_FALLBACK_MS)).coerceAtLeast(line.begin + 1)
            line.end = end
            line.duration = end - line.begin
            clampWords(line, end)
        }
        return sorted
    }

    /** 逐字时间不能越出所属行，否则消费端会把字画到下一行上 */
    private fun clampWords(line: RichLyricLine, end: Long) {
        val words = line.words ?: return
        val maxBegin = (end - 1L).coerceAtLeast(line.begin)
        for (word in words) {
            word.begin = word.begin.coerceIn(line.begin, maxBegin)
            word.end = word.end.coerceIn(word.begin + 1L, end)
            word.duration = word.end - word.begin
        }
    }

    /** 纯 LRC 文本 → (时间戳, 文本)，喂给 [LyricParsers.attachTranslations] */
    fun pairs(raw: String?): List<Pair<Long, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = runCatching { LyricParsers.parseLrcText(raw).lines }.getOrNull().orEmpty()
        return lines.mapNotNull { line ->
            val text = line.text?.trim()
            if (text.isNullOrEmpty()) null else line.begin to text
        }
    }

    /** 贴译文与音译：同一行同时有原文和译文，消费端才会画出译文行 */
    fun attach(
        lines: List<RichLyricLine>,
        translation: List<Pair<Long, String>>,
        roma: List<Pair<Long, String>>
    ) {
        LyricParsers.attachTranslations(lines, translation, TRANSLATION_TOLERANCE_MS)
        LyricParsers.attachTranslations(lines, roma, ROMA_TOLERANCE_MS) { line, text ->
            // 已有音译就不覆盖：部分接口会同时给 roma 与 romalrc
            if (line.roma.isNullOrBlank()) line.roma = text
        }
    }
}
