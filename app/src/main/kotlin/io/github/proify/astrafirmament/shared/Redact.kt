package io.github.proify.astrafirmament.shared

/**
 * 日志脱敏（纯 Kotlin，hook 侧与 app 侧共用，不依赖 android.*）。
 *
 * 审核要求：日志与开发者报告不得出现完整文件路径、完整网址、WiFi 名称、
 * 蓝牙设备名与 MAC、歌名歌手。这里只做"抹掉"——不保留主机名/后缀等可定位
 * 片段：日志是给开发排查用的，少了这些不影响判断，多留一个字就多一份泄露。
 *
 * 约定：所有方法 null/空串安全；入口方法 [line] 全程 runCatching，
 * 失败时返回占位符而不是原文——脱敏失败也绝不把原文交出去。
 */
object Redact {

    const val PATH = "<path>"
    const val URL = "<url>"
    const val MAC = "<mac>"
    const val SSID = "<ssid>"
    const val BT = "<bt>"
    const val SONG = "<song>"

    private const val LEN = "<n>"
    private const val FAILED = "<redact-failed>"

    /** 需要整段抹掉的敏感根目录 */
    private const val PREFIX = "storage|sdcard|data|mnt|system"

    /** 带引号的路径（允许空格）：连引号一起整段替换 */
    private val QUOTED_PATH_RX =
        Regex("""["']/(?:$PREFIX)(?![\w-])(?:/[^"'\n\r]*)?["']""")

    /**
     * 裸路径。允许路径内部含空格：空白后的续段必须像文件/目录名（含 / 或 .），
     * 否则当作日志字段收尾，避免把后面的"第 1 次"这类描述也吞掉。
     */
    private val PATH_RX = Regex(
        """(?<![\w./-])/(?:$PREFIX)(?![\w-])(?:/[^\s,，;；)）\]】"'|]*)(?:\s+(?=[^\s]*[./])[^\s,，;；)）\]】"'|]+)*"""
    )

    /**
     * 带扩展名的文件名（路径里含空格时裸路径规则会在空格处停下，
     * 剩下的"晴天 - 周杰伦.flac"尾巴同样含歌名）：一律抹成 <path>。
     */
    private val FILE_RX = Regex(
        """(?<![\w./-])[^\s"'<>|]*\.(?:flac|mp3|m4a|aac|ogg|wav|lrc|apk|zip|log|json|xml|txt|db|jpg|jpeg|png|webp)(?![\w])""",
        RegexOption.IGNORE_CASE
    )

    private val URL_RX = Regex("""https?://[^\s,，;；)）\]】"'|<>]+""", RegexOption.IGNORE_CASE)

    private val MAC_RX =
        Regex("""(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])""")

    /** bluetooth connected: 设备名 (MAC) */
    private val BT_RX = Regex(
        """(\bbluetooth\s+connected\s*[=:]\s*)[^\n(（]*\S(?=\s|[(（]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** MAC= / BSSID= / bt-mac= */
    private val MAC_KV_RX = Regex(
        """(\b(?:bssid|mac|bt[-_ ]?mac)\s*[=:]\s*)[^\s,，;；|)\]\n]+""",
        RegexOption.IGNORE_CASE
    )

    /** ssid= / wifi ssid= / real ssid: / hint= */
    private val SSID_KV_RX = Regex(
        """(\b(?:wifi[-_ ]?)?(?:ssid|hint)\w*\s*[=:]\s*)(?:"[^"\n]*"|'[^'\n]*'|[^\s,，;；|)\]\n]+)""",
        RegexOption.IGNORE_CASE
    )

    /** 没有键、直接跟值的 wifi 形态（LinkHub 的几条日志） */
    private val WIFI_VERB_RX = Regex(
        """(wifi\s+(?:has no password|password copied|password shared|credential matched by real ssid|credential fallback to most-recent)[:：]\s*)[^\s,，;；|)\]\n]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * title= / artist= / ti= / 解析后= / 当前= / 标题= / 首行=。
     * 中文键前不能用 \b（Java 的 \w 只认 ASCII），用 ASCII 词边界代替。
     */
    private val SONG_KV_RX = Regex(
        """(?<![A-Za-z0-9_])((?:title|artist|ti|ar|解析后|当前|标题|首行)\s*[=:]\s*)(?:<[^>\n]*>|[^\n]*?)(?=\s+\w+\s*[=:]|[)）\]\r\n]|$)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 没有键、整段拼在消息里的歌曲形态（"前缀：歌名 - 歌手"）。
     * 这些行拿不到 title=/artist= 键，只能按前缀认；统一抹到行尾。
     */
    private val SONG_EOL_RX = Regex(
        """((?:启动后首曲立即提交|直接提交|直接按切歌收尾|切歌待定|不再联网|歌词来自(?:缓存|本地拦截)|使用暂存的歌词|命中本会话歌词缓存|No provider yet for|No local lyric[^\n:：]*|no usable line for)\s*[：:]?\s*)[^\n]*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 前缀 + 歌名歌手 + 后续可读字段（时长/id/ok/行数）。只抹歌名歌手，
     * 保留后面的诊断字段；值开始就是 title= 时跳过（交给 SONG_KV_RX 按键处理）。
     */
    private val SONG_TAIL_RX = Regex(
        """((?:Spotify 推送(?:占位|歌词)|MeloYou 推送|Apple 切歌|元数据|Metadata changed|歌词暂存[^\n:：]*|Kuwo 歌名匹配命中|Kuwo 歌名对但歌手不符)\s*[：:]\s*)(?!title=)[^\n]*?(?=\s*\(\d|\s*\(\s*\d|,\s*ok\s*=|\s+ok\s*=|，歌词|，\s*\d+\s*行|\s+(?:ti|ar|title|artist|id)\s*[=:]|[（(]|[\r\n]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** "推送歌曲信息 <歌名 - 歌手>, ok=..."：这里没有冒号，值直接跟在空格后 */
    private val SONG_PUSH_RX = Regex(
        """(推送歌曲信息\s+)(?!title=)[^\n]*?(?=,\s*ok\s*=|\s+ok\s*=|，\s*\d+\s*行|[\r\n]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** "Kuwo 排除 <文件名>：ar=..." —— 文件名同样是歌名歌手 */
    private val KUWO_EXCLUDE_RX = Regex(
        """(Kuwo 排除\s*)[^\n]*?(?=\s*[：:]|[\r\n]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** 兜底：《歌名》 */
    private val BOOK_TITLE_RX = Regex("""(《)[^》\n]*(》)""")

    /** 歌名与歌手的箭头形态（→ 晴天 / 周杰伦） */
    private val ARROW_RX = Regex("""(→\s*)[^\n)）]*""")

    /** 剪贴板识别：len=42 */
    private val CLIP_LEN_RX = Regex("""(剪贴板识别[^\n]*?\blen\s*=\s*)\d+""", RegexOption.IGNORE_CASE)

    // ---------------- 单类抹除 ----------------

    /** 抹掉字符串里的完整文件路径；没有路径则原样返回（空输入返回空串）。 */
    fun path(p: String?): String {
        if (p.isNullOrEmpty()) return ""
        if (!p.contains('/') && !p.contains('.')) return p
        return runCatching {
            var s = QUOTED_PATH_RX.replace(p, PATH)
            s = PATH_RX.replace(s, PATH)
            s = FILE_RX.replace(s, PATH)
            s
        }.getOrDefault(PATH)
    }

    /** 抹掉字符串里的 http/https 整条网址；没有网址则原样返回。 */
    fun url(u: String?): String {
        if (u.isNullOrEmpty()) return ""
        return runCatching { URL_RX.replace(u, URL) }.getOrDefault(URL)
    }

    /** 抹掉字符串里的 MAC（冒号/连字符分隔均可）；没有则原样返回。 */
    fun mac(m: String?): String {
        if (m.isNullOrEmpty()) return ""
        return runCatching { MAC_RX.replace(m, MAC) }.getOrDefault(MAC)
    }

    /** 整段 WiFi 名称 → `<ssid>`；空输入返回空串。 */
    fun ssid(s: String?): String = if (s.isNullOrBlank()) "" else SSID

    /** 整段蓝牙设备名 → `<bt>`；空输入返回空串。 */
    fun btName(s: String?): String = if (s.isNullOrBlank()) "" else BT

    /** 整段歌名/歌手 → `<song>`；空输入返回空串。 */
    fun song(s: String?): String = if (s.isNullOrBlank()) "" else SONG

    /**
     * 整行脱敏：依次处理网址 → 路径 → 蓝牙名 → `MAC=`/`BSSID=` → `ssid=`/`hint=`
     * → wifi 动词形态 → `title=`/`artist=` 等歌曲键值 → 整段歌名形态 → 箭头歌曲 → 剪贴板长度
     * → 剩余裸 MAC。永不抛异常。
     */
    fun line(msg: String?): String {
        if (msg.isNullOrEmpty()) return ""
        return runCatching { redact(msg) }.getOrDefault(FAILED)
    }

    private fun redact(msg: String): String {
        // 快路径：日志热路径上（system_server / 宿主进程）每行都跑，先用
        // 关键字挡住绝大多数规则；没有可疑字样就基本只剩一次 lowercase。
        val low = msg.lowercase()
        var s = msg
        if (low.contains("http://") || low.contains("https://")) s = URL_RX.replace(s, URL)
        if (s.contains('/')) {
            s = QUOTED_PATH_RX.replace(s, PATH)
            s = PATH_RX.replace(s, PATH)
        }
        if (FILE_HINTS.any { low.contains(it) }) s = FILE_RX.replace(s, PATH)
        if (low.contains("bluetooth")) s = BT_RX.replace(s) { it.groupValues[1] + BT }
        if (low.contains("bssid") || low.contains("mac"))
            s = MAC_KV_RX.replace(s) { it.groupValues[1] + MAC }
        if (low.contains("ssid") || low.contains("hint"))
            s = SSID_KV_RX.replace(s) { it.groupValues[1] + SSID }
        if (low.contains("wifi")) s = WIFI_VERB_RX.replace(s) { it.groupValues[1] + SSID }
        if (SONG_KEY_HINTS.any { low.contains(it) })
            s = SONG_KV_RX.replace(s) { it.groupValues[1] + SONG }
        if (SONG_EOL_HINTS.any { low.contains(it) })
            s = SONG_EOL_RX.replace(s) { it.groupValues[1] + SONG }
        if (SONG_TAIL_HINTS.any { low.contains(it) })
            s = SONG_TAIL_RX.replace(s) { it.groupValues[1] + SONG }
        if (low.contains("推送歌曲信息")) s = SONG_PUSH_RX.replace(s) { it.groupValues[1] + SONG }
        if (low.contains("kuwo 排除")) s = KUWO_EXCLUDE_RX.replace(s) { it.groupValues[1] + SONG }
        if (s.contains('《')) s = BOOK_TITLE_RX.replace(s) { it.groupValues[1] + SONG + it.groupValues[2] }
        if (s.contains('→')) s = ARROW_RX.replace(s) { it.groupValues[1] + SONG }
        if (low.contains("剪贴板识别")) s = CLIP_LEN_RX.replace(s) { it.groupValues[1] + LEN }
        if (s.contains(':') || s.contains('-')) s = MAC_RX.replace(s, MAC)
        return s
    }

    /** 触发性的关键字（小写）；只是判断"值不值得跑正则"，命中后才做替换 */
    private val FILE_HINTS = listOf(
        ".flac", ".mp3", ".m4a", ".aac", ".ogg", ".wav", ".lrc", ".apk", ".zip",
        ".log", ".json", ".xml", ".txt", ".db", ".jpg", ".jpeg", ".png", ".webp",
    )
    private val SONG_KEY_HINTS = listOf(
        "title", "artist", "解析后", "当前", "标题", "首行",
        "ti=", "ti:", "ti ", "ar=", "ar:", "ar ",
    )
    private val SONG_EOL_HINTS = listOf(
        "启动后首曲立即提交", "直接提交", "直接按切歌收尾", "切歌待定", "不再联网",
        "歌词来自", "使用暂存的歌词", "命中本会话歌词缓存", "no provider yet for", "no local lyric", "no usable line for",
    )
    private val SONG_TAIL_HINTS = listOf(
        "spotify 推送", "meloyou 推送", "apple 切歌", "元数据", "metadata changed",
        "歌词暂存", "kuwo 歌名匹配命中", "kuwo 歌名对但歌手不符",
    )
}
