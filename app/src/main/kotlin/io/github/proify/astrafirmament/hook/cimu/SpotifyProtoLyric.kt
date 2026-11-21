package io.github.proify.astrafirmament.hook.cimu

import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.lang.reflect.Field

/**
 * Spotify protobuf-lite 歌词响应 → [RichLyricLine]。
 *
 * 不引用任何 Spotify 编译期类型：hook 拿到的是 Object，这里按 protobuf-lite 生成类
 * 在 dex 里保留的字段名反射读取（9.1.92 可见证据：
 *
 *   ColorLyricsResponse.lyrics_  → LyricsResponse
 *   LyricsResponse.lines_        → List<LyricsResponse.LyricsLine>
 *   LyricsLine.startTimeMs_ / words_（v3 还有 endTimeMs_）
 *   v3 LyricsV3Response.translation_ → Translation.lines_（按行号平行的译文）
 *
 * 字段名对不上（换版本被 R8 改名）时返回空列表，绝不猜字段。
 *
 * 归属证据（9.1.92 全量核对）：
 * 四条响应消息里**没有**任何能标识曲目的字段——ColorLyricsResponse 只有
 * lyrics_/colors_/hasVocalRemoval_；LyricsResponse 只有 syncType_/lines_/provider_/
 * providerLyricsId_/providerDisplayName_/syncLyricsUri_/alternatives_/language_/
 * isRtlLanguage_/capStatus_/translationLanguages_；LyricsWrapperResponse 只有
 * lyrics_/hasVocalRemoval_；LyricsV3Response 只有 syncType_/lines_/previewLines_/
 * provider_/translation_/language_/capStatus_。providerLyricsId_ 是歌词提供方自己的
 * 文档 id（如 Musixmatch 的 id），syncLyricsUri_ 是歌词资源地址，都不是 22 位
 * base62 的 trackId，所以响应本身无法自证曲目，只能用请求侧配对 + 时长相容校验。
 */
internal object SpotifyProtoLyric {
    private const val LINES = "lines_"
    private const val LYRICS = "lyrics_"
    private const val START = "startTimeMs_"
    private const val END = "endTimeMs_"
    private const val WORDS = "words_"
    private const val TRANSLATION = "translation_"

    /** 末行开始时间允许超出曲目时长的余量：片尾留白 / 时长元数据取整 */
    private const val FIT_TAIL_SLACK_MS = 5_000L

    /** 末行开始时间至少要走到曲目时长的这个比例，否则判为别的歌 */
    private const val FIT_MIN_RATIO = 0.35

    /** 歌词响应的接口族：v2 与 v3 是两套请求链路，配对绝不能跨族 */
    internal enum class Family { V2, V3 }

    /**
     * 响应属于哪个接口族；不是歌词响应返回 null。
     *
     * family 只按类名判定（dex 里四个生成类的类名后缀，9.1.92 实证）：
     *   v2 → ColorLyricsResponse / LyricsResponse（color-lyrics/v2/track/{trackId}）
     *   v3 → LyricsWrapperResponse / LyricsV3Response（color-lyrics/v3/track/{trackId}）
     */
    fun responseFamily(message: Any?): Family? {
        val name = message?.javaClass?.name ?: return null
        return when {
            name.endsWith("LyricsWrapperResponse") || name.endsWith("LyricsV3Response") -> Family.V3
            name.endsWith("ColorLyricsResponse") || name.endsWith("LyricsResponse") -> Family.V2
            else -> null
        }
    }

    /** 只认歌词响应：v2 wrapper / v3 wrapper / 裸 LyricsResponse 都接受 */
    fun isLyricsMessage(message: Any?): Boolean = responseFamily(message) != null

    /**
     * 时长相容校验：拦截到的（或缓存里的）歌词时间轴必须与当前曲目时长对得上。
     *
     * 末行 begin 落在 [duration * 0.35, duration + 5s] 之外就判为「这首歌的歌词不是它」。
     * 时长未知（<= 0，Spotify 元数据没带）时跳过校验，不误杀。
     */
    fun fitsDuration(lines: List<RichLyricLine>?, durationMs: Long): Boolean {
        if (durationMs <= 0L) return true
        if (lines.isNullOrEmpty()) return false
        val lastBegin = lines.maxOf { it.begin }
        if (lastBegin <= 0L) return false
        val minBegin = (durationMs * FIT_MIN_RATIO).toLong()
        return lastBegin <= durationMs + FIT_TAIL_SLACK_MS && lastBegin >= minBegin
    }

    fun extract(parsed: Any?): List<RichLyricLine> {
        val message = unwrap(parsed) ?: return emptyList()
        val rawLines = readList(message, LINES) ?: return emptyList()
        if (rawLines.isEmpty()) return emptyList()
        val translations = readTranslations(message, rawLines.size)
        val out = ArrayList<RichLyricLine>(rawLines.size)
        for (index in rawLines.indices) {
            toLine(rawLines[index], translations.getOrNull(index))?.let { out.add(it) }
        }
        return out
    }

    /** wrapper 解开一层：真正带 lines_ 的那层才是歌词本体 */
    private fun unwrap(parsed: Any?): Any? {
        parsed ?: return null
        if (fieldOf(parsed.javaClass, LINES) != null) return parsed
        val inner = readObject(parsed, LYRICS) ?: return null
        return inner.takeIf { fieldOf(it.javaClass, LINES) != null }
    }

    private fun toLine(raw: Any?, translation: String?): RichLyricLine? {
        raw ?: return null
        val start = readLong(raw, START) ?: return null
        if (start < 0L) return null
        val end = readLong(raw, END) ?: start
        val text = readString(raw, WORDS).orEmpty()
        val line = RichLyricLine(begin = start, end = end.coerceAtLeast(start), text = text)
        if (!translation.isNullOrBlank()) line.translation = translation
        return line
    }

    /** 译文数组与主歌词按行号平行；行数对不上就整组丢弃（绝不硬套错行） */
    private fun readTranslations(message: Any, lineCount: Int): List<String> {
        if (lineCount <= 0) return emptyList()
        val block = readObject(message, TRANSLATION) ?: return emptyList()
        val list = readList(block, LINES) ?: return emptyList()
        if (list.size != lineCount) return emptyList()
        return list.map { it as? String ?: "" }
    }

    private fun fieldOf(cls: Class<*>, name: String): Field? {
        val cached = runCatching { cls.getDeclaredField(name) }.getOrNull()
            ?: runCatching {
                cls.declaredFields.firstOrNull {
                    it.name.equals(name, ignoreCase = true) ||
                        it.name.replace("_", "").equals(name.replace("_", ""), ignoreCase = true)
                }
            }.getOrNull()
        return cached?.let { field -> runCatching { field.apply { isAccessible = true } }.getOrNull() }
    }

    private fun readObject(target: Any, name: String): Any? =
        runCatching { fieldOf(target.javaClass, name)?.get(target) }.getOrNull()

    private fun readLong(target: Any, name: String): Long? =
        (readObject(target, name) as? Number)?.toLong()

    private fun readString(target: Any, name: String): String? =
        readObject(target, name) as? String

    private fun readList(target: Any, name: String): List<*>? =
        readObject(target, name) as? List<*>
}
