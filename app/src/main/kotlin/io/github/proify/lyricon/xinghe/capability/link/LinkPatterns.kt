package io.github.proify.lyricon.xinghe.capability.link

/**
 * 链接助手 · 链接识别（纯函数，可单元测试）。
 *
 * 从用户复制／剪切的整段文字里找出第一个可打开的链接。
 * 规则取自需求给出的三段正则：
 *   1. 带 http(s) 协议的完整 URL，最优先；
 *   2. 已知顶级域的裸域名（github.com/x、b23.tv/abc），补 https:// 前缀；
 *   3. 邮箱只识别不上岛（点了应去邮件应用，与链接助手目标不符）。
 * 另加一层「文件名／代码符号」排除，避免把 build.gradle.kts 送上去。
 *
 * 只读、不联网、不落盘。认不出来返回 null，调用方丢弃。
 */
object LinkPatterns {

    private const val COMMON_TOP_LEVEL_DOMAINS =
        "com|cn|net|org|edu|gov|io|ai|co|info|biz|me|tv|cc|app|dev|tech|site|online|" +
            "xyz|top|vip|shop|store|club|cloud|pro|mobi|asia|uk|jp|de|fr|ru|au|ca|" +
            "us|hk|tw|sg|kr|in|br|it|nl|es"

    /** 邮箱：识别得到，但不当链接上岛（点了应去邮件应用） */
    private val PATTERN_EMAIL = Regex(
        pattern = """(?i)(?<![a-z0-9._%+\-])[a-z0-9](?:[a-z0-9._%+\-]{0,62}[a-z0-9])?@(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?![a-z0-9_\-])""",
    )

    /** 带协议的完整 URL，最高优先级 */
    private val PATTERN_URL = Regex(
        pattern = """(?i)(?<![a-z0-9_])https?://[^\s<>"“”‘’（）【】《》。，；：！？、]+""",
    )

    /** 已知顶级域的裸域名 */
    private val PATTERN_DOMAIN = Regex(
        pattern = """(?i)(?<![@/:a-z0-9_\-])(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+(?:$COMMON_TOP_LEVEL_DOMAINS)(?::[0-9]{1,5})?(?:[/?#][^\s<>"“”‘’（）【】《》。，；：！？、]*)?(?![a-z0-9_\-]|\.[a-z0-9])""",
    )

    /** 文件名／代码标识符，不该当链接 */
    private val FILE_LIKE = Regex(
        """\.(?:kt|kts|java|xml|json|md|txt|log|apk|zip|jar|so|dex|pro|gradle|properties|ts|js|tsx|jsx|py|c|cpp|h|rs|go|rb|css|html|htm|yml|yaml|conf|cfg|ini|sh|bat|png|jpg|jpeg|gif|webp|mp3|mp4|flac|aac|lrc|pdf|doc|docx|xls|xlsx|ppt|pptx)$""",
        RegexOption.IGNORE_CASE
    )

    /** 文本扫描上限：超过的尾部不看，避免大段粘贴拖慢正则 */
    private const val SCAN_LIMIT = 4096

    /**
     * 找出文本里的第一个链接。
     * @return 可直接交给 Intent.ACTION_VIEW 的地址；没有则 null
     */
    fun firstLink(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val sample = text.trim().let {
            if (it.length > SCAN_LIMIT) it.substring(0, SCAN_LIMIT) else it
        }

        // 1) 带协议的优先，最不会认错
        PATTERN_URL.find(sample)?.let { m ->
            return polish(m.value, hadScheme = true)
        }

        // 2) 已知顶级域的裸域名
        PATTERN_DOMAIN.find(sample)?.let { m ->
            val raw = m.value
            if (looksLikeFile(raw)) return null
            return polish(raw, hadScheme = false)
        }
        return null
    }

    private val PATTERN_PHONE = Regex(
        "(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])"
    )

    fun firstPhone(text: CharSequence?): String? =
        text?.let { PATTERN_PHONE.find(it.take(SCAN_LIMIT))?.value }
            ?.replace(Regex("[^0-9+]"), "")

    /** 文本里有没有链接 */
    fun hasLink(text: CharSequence?): Boolean = firstLink(text) != null

    /** 只取域名，给岛上展示用 */
    fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    }.getOrDefault(url)

    /** 文本里是否有邮箱（保留给将来扩展；目前不触发上岛） */
    @Suppress("unused")
    fun hasEmail(text: CharSequence?): Boolean =
        !text.isNullOrBlank() && PATTERN_EMAIL.containsMatchIn(text)

    /**
     * 收尾：补协议头、削掉被中文标点带上的尾巴。
     * 只在「没有协议头」时做中文截断 —— 带 http(s) 的整段是用户明确复制的，
     * 里面的中文可能是路径的一部分，别乱动。
     */
    private fun polish(raw: String, hadScheme: Boolean): String {
        var url = raw.trim()
        if (!hadScheme) {
            // 裸域名后面紧跟中文（「github.com/x然后就这样」）时从第一个中文字处截。
            // 中文域名（aaa.中国）里的中文落在第一个 '/' 之前，不受影响。
            val firstCn = url.indexOfFirst { it.code in 0x4E00..0x9FFF }
            val slash = url.indexOf('/')
            val pathStart = if (slash < 0) url.length else slash
            if (firstCn > pathStart) url = url.substring(0, firstCn)
        }
        url = url.trimEnd(
            '.', ',', ';', ':', '!', '?',
            ')', ']', '}', '>',
            '。', '，', '；', '：', '！', '？', '、',
            '"', '\'', '）', '】', '》', '｜', '|'
        )
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        return url
    }

    /** 「域名形状」的片段是不是其实是个文件名 */
    private fun looksLikeFile(raw: String): Boolean =
        FILE_LIKE.containsMatchIn(raw) &&
            !raw.contains('/') && !raw.contains('?') && !raw.contains('#')
}
