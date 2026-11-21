package io.github.proify.astrafirmament.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 全模块统一的偏好中心。
 *
 * 读取方是「目标进程」（被注入的音乐播放器、输入法等），写入方是「设置页」，
 * 所以两侧必须看同一份数据（Xposed 侧用 getRemotePreferences 打开同名文件）。
 * 新增能力只需要在这里加一个字段和一对 getter。
 */
object ModulePrefs {

    const val NAME = "xinghe_settings"

    const val KEY_ENABLED = "module_enabled"
    const val KEY_HIDE_ICON = "hide_launcher_icon"
    const val KEY_LYRIC = "cap_lyric_enabled"
    const val KEY_LINK = "cap_link_enabled"
    const val KEY_SMART_ISLAND = "cap_smart_island_enabled"
    const val KEY_PHONE = "cap_phone_enabled"
    const val KEY_BROWSER_MODE = "link_browser_mode"
    const val KEY_BROWSER_PACKAGE = "link_browser_package"
    /** 开发者选项：开启后智能上岛链路日志写入模块自身 filesDir（排查用） */
    const val KEY_DEV_LOG = "dev_log_enabled"

    /** 日志打包周期（分钟）：距上次打包超过该时长才重建压缩包，新的原子替换旧的 */
    const val KEY_LOG_PACK_MIN = "log_pack_minutes"

    /** 日志清理周期（小时）：到期清空内存缓冲并删除压缩包，从头开始记录 */
    const val KEY_LOG_CLEAN_HOUR = "log_clean_hours"

    // ---------------- 智能文件 ----------------
    /** 智能文件总开关：APK 下载完成后弹岛询问是否安装 */
    const val KEY_FILE_INSTALLER = "cap_file_installer_enabled"
    /** WiFi 助手独立开关：连接/断开弹岛 */
    const val KEY_WIFI = "cap_wifi_enabled"
    /** 蓝牙岛独立开关：连上蓝牙设备时弹岛 */
    const val KEY_BLUETOOTH = "cap_bluetooth_enabled"
    /** 单卡目标持久层（跨进程重启：岛还在、模块进程被回收后按钮仍能执行） */
    const val KEY_CUR_KIND = "cur_kind"
    const val KEY_CUR_TARGET = "cur_target"
    /** 默认卡片持续时长（秒），到时自动收起；单个功能没单独设置时用它 */
    const val KEY_CARD_LINGER = "card_linger_seconds"
    /** 未设置过任何时长时的出厂默认（秒） */
    const val DEFAULT_CARD_LINGER_SECONDS = 30
    /** 卡片显示时长下限（秒）：短于这个值时按钮还没看清就没了 */
    const val CARD_LIFE_MIN_SECONDS = 10
    /**
     * 卡片显示时长上限（秒）。
     *
     * 同时是前台服务能提供的最长保护窗口（shortService 上限制约），
     * 卡片的 DismissPolicy、模块侧的收起任务、IslandService 的 stopSelf
     * 三处必须都取这个值，否则「卡还在、保护窗口已过」就会出现按钮点不动。
     */
    const val CARD_LIFE_MAX_SECONDS = 150
    /** 按功能覆盖的持续时长前缀：键名 = 前缀 + 功能名（link/phone/file/wifi/bt） */
    const val KEY_LINGER_PREFIX = "linger_sec_"
    /** 用户选定的默认安装器包名（空=每次用系统默认 chooser） */
    const val KEY_INSTALLER_PACKAGE = "installer_package"
    /** 安装器策略：system（系统默认）/ pinned（固定）/ ask（每次询问）——与浏览器同款 */
    const val KEY_INSTALLER_MODE = "installer_mode"
    /** 智能文件暂存副本的自动清理窗口（天）：0 = 不按时间清理，只保留最近若干份 */
    const val KEY_APK_CLEAN_DAYS = "apk_clean_days"
    /** 自动清理窗口的出厂默认（天） */
    const val DEFAULT_APK_CLEAN_DAYS = 3
    /** 自动清理窗口的可调上限（天）：设置页拉条满格值 */
    const val APK_CLEAN_MAX_DAYS = 30
    /** 读取侧的兜底上限（天）：挡住旧数据/异常值，不参与拉条刻度 */
    const val APK_CLEAN_HARD_MAX_DAYS = 365
    /** 智能文件的扫描来源目录（StringSet 持久化；读取时稳定排序，见 [apkSourceDirs]） */
    const val KEY_APK_SOURCE_DIRS = "apk_source_dirs"
    /** 智能文件的扫描层级：0 = 不限；1 = 仅目录本身；2/3/5 = 向下几层 */
    const val KEY_APK_SCAN_DEPTH = "apk_scan_depth"
    /** 扫描层级：不限制深度（find 不加 -maxdepth） */
    const val APK_SCAN_DEPTH_UNLIMITED = 0
    /** 扫描层级出厂默认：3（QQ/TIM 偶尔会多套一层下载目录） */
    const val DEFAULT_APK_SCAN_DEPTH = 3
    /** 设置页可选的层级值，顺序与界面文案一一对应 */
    val APK_SCAN_DEPTH_VALUES: List<Int> = listOf(1, 2, 3, 5, APK_SCAN_DEPTH_UNLIMITED)

    /**
     * 第三方安装包的默认来源目录（17 个）。多列几个成本很低，少列一个就是「下好了却不弹」：
     * QQ/TIM 新版落 Android/data、老版落 tencent；微信/企业微信同理；其余是系统浏览器、
     * 电脑传输与常见下载器的落盘目录。只有存在的目录会被扫到，不存在的记 @missing。
     */
    val DEFAULT_APK_SOURCE_DIRS: List<String> = listOf(
        // QQ
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/QQfile_recv",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/QQfile_recv",
        // TIM
        "/storage/emulated/0/Android/data/com.tencent.tim/Tencent/QQfile_recv",
        "/storage/emulated/0/Android/data/com.tencent.tim/files/QQfile_recv",
        // QQ/TIM 老版落点
        "/storage/emulated/0/tencent/QQfile_recv",
        "/storage/emulated/0/tencent/TIMfile_recv",
        // 微信（含企业微信）
        "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/Download",
        "/storage/emulated/0/tencent/MicroMsg/Download",
        "/storage/emulated/0/Android/data/com.tencent.wework/files/Download",
        // 系统浏览器 / 电脑传输 / ADB push / 多数下载器的默认目录
        "/storage/emulated/0/Download",
        // 自带下载器的浏览器与网盘
        "/storage/emulated/0/Quark/Download",
        "/storage/emulated/0/UCDownloads",
        "/storage/emulated/0/QQBrowser",
        "/storage/emulated/0/BaiduNetdiskDownload",
        "/storage/emulated/0/ThunderDownload",
        "/storage/emulated/0/Android/data/com.android.chrome/files/Download"
    )

    /** 浏览器策略：跟随系统默认 */
    const val BROWSER_SYSTEM = "system"

    /** 浏览器策略：固定在用户选定的某个浏览器 */
    const val BROWSER_PINNED = "pinned"

    /** 浏览器策略：每次让用户选 */
    const val BROWSER_ASK = "ask"

    @Suppress("DEPRECATION")  // 跨进程读新值必需（UI 进程写 → 常驻模块进程读）
    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_MULTI_PROCESS)

    /**
     * 强制重读版：跨进程写入后，常驻进程的 SP 内存缓存不会自动刷新
     * （UI 进程关开关 → 模块进程旧快照仍放行 = 『关了仍生效』的根因）。
     * MODE_MULTI_PROCESS 每次读都会 reload——用在闸门等关键判定处。
     */
    @Suppress("DEPRECATION")  // 同上：闸门判定处强制重读
    fun ofFresh(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_MULTI_PROCESS)

    // ---------------- 默认值（集中定义，避免各处不一致） ----------------

    fun isEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_ENABLED, true)

    fun isHideIcon(s: SharedPreferences): Boolean = s.getBoolean(KEY_HIDE_ICON, false)

    /** 歌词胶囊：原有行为，默认开 */
    fun isLyricEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_LYRIC, true)

    /**
     * 链接助手：默认开。
     *
     * 以前默认关，结果用户装了模块、勾了作用域，长按选链接也「没反应」——
     * 因为这一路 hook 压根没装。链接助手只做只读观察、匹配不上就丢弃，
     * 没有副作用，默认开着才对；不想要的人可以在设置页关掉。
     */
    fun isSmartIslandEnabled(s: SharedPreferences): Boolean =
        s.getBoolean(KEY_SMART_ISLAND, s.getBoolean(KEY_LINK, true))

    fun isLinkEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_LINK, true)

    fun isPhoneEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_PHONE, true)


    const val DEFAULT_LOG_PACK_MIN = 5
    const val DEFAULT_LOG_CLEAN_HOUR = 168

    /** 开发者日志：默认关。开启后 LinkHub 的链路日志落盘 */
    fun isDevLogEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_DEV_LOG, false)

    fun isLogPackMinutes(s: SharedPreferences): Int =
        runCatching { s.getInt(KEY_LOG_PACK_MIN, DEFAULT_LOG_PACK_MIN) }.getOrDefault(DEFAULT_LOG_PACK_MIN)

    fun isLogCleanHours(s: SharedPreferences): Int =
        runCatching { s.getInt(KEY_LOG_CLEAN_HOUR, DEFAULT_LOG_CLEAN_HOUR) }.getOrDefault(DEFAULT_LOG_CLEAN_HOUR)

    /** 智能文件：APK 下载完成弹岛。默认开（真实高频需求） */
    fun isFileInstallerEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_FILE_INSTALLER, true)

    /** WiFi 助手：连接/断开弹岛。默认开 */
    fun isWifiEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_WIFI, true)

    /** 蓝牙岛：连上音频类蓝牙设备即弹岛。默认开 */
    fun isBluetoothEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_BLUETOOTH, true)

    /**
     * 把任意历史值夹紧到可用的卡片时长。
     *
     * 旧版本用 0 表示「常驻」，但前台服务撑不了那么久（卡还在、保护窗口已过
     * → 按钮点不动），所以常驻已移除：0 与负数一律按最长时长处理，
     * 其余值夹在 [CARD_LIFE_MIN_SECONDS]..[CARD_LIFE_MAX_SECONDS]。
     */
    fun clampCardLifeSeconds(seconds: Int): Int =
        if (seconds <= 0) CARD_LIFE_MAX_SECONDS
        else seconds.coerceIn(CARD_LIFE_MIN_SECONDS, CARD_LIFE_MAX_SECONDS)

    /** 默认卡片持续时长（秒），已夹紧；到时自动 end */
    fun cardLingerSeconds(s: SharedPreferences): Int =
        clampCardLifeSeconds(s.getInt(KEY_CARD_LINGER, DEFAULT_CARD_LINGER_SECONDS))

    /** 写默认卡片持续时长（秒）：与 [cardLingerSeconds] 同一夹紧语义 */
    fun setCardLinger(editor: SharedPreferences.Editor, seconds: Int): SharedPreferences.Editor =
        editor.putInt(KEY_CARD_LINGER, clampCardLifeSeconds(seconds))

    // ---------------- 按功能覆盖的收起时长 ----------------

    /** 该功能没有单独设置时的标记值（落到默认时长） */
    const val LINGER_FOLLOW_DEFAULT = -1

    /**
     * 功能的收起时长（秒）——卡片生命周期的唯一真值。
     *
     * 卡片 DismissPolicy、模块侧收起任务、IslandService 的 stopSelf 全部读它，
     * 三处取值必须一致，否则卡会活得比前台服务保护窗口更久（按钮失效）。
     */
    fun lingerSecondsFor(s: SharedPreferences, feature: String): Int {
        val raw = s.getInt(KEY_LINGER_PREFIX + feature, LINGER_FOLLOW_DEFAULT)
        return clampCardLifeSeconds(
            if (raw >= 0) raw else s.getInt(KEY_CARD_LINGER, DEFAULT_CARD_LINGER_SECONDS)
        )
    }

    /**
     * 该功能是否单独设过时长（设置页要显示「默认」还是具体秒数）。
     *
     * 跟随默认时返回 [LINGER_FOLLOW_DEFAULT]；设过的返回已夹紧的秒数
     * （旧数据里的 0 = 常驻会显示成最长时长，不再是 0 秒）。
     */
    fun featureLingerSeconds(s: SharedPreferences, feature: String): Int {
        val raw = s.getInt(KEY_LINGER_PREFIX + feature, LINGER_FOLLOW_DEFAULT)
        return if (raw < 0) LINGER_FOLLOW_DEFAULT else clampCardLifeSeconds(raw)
    }

    fun setFeatureLinger(
        editor: SharedPreferences.Editor,
        feature: String,
        seconds: Int
    ): SharedPreferences.Editor =
        if (seconds < 0) editor.remove(KEY_LINGER_PREFIX + feature)
        else editor.putInt(KEY_LINGER_PREFIX + feature, clampCardLifeSeconds(seconds))

    /** 自动清理窗口（天），0 = 只按份数上限清理 */
    fun apkCleanDays(s: SharedPreferences): Int =
        s.getInt(KEY_APK_CLEAN_DAYS, DEFAULT_APK_CLEAN_DAYS).coerceIn(0, APK_CLEAN_HARD_MAX_DAYS)

    /** 写自动清理窗口（天）：与 [apkCleanDays] 同一夹紧语义，免得写入拉条范围外的值 */
    fun setApkCleanDays(editor: SharedPreferences.Editor, days: Int): SharedPreferences.Editor =
        editor.putInt(KEY_APK_CLEAN_DAYS, days.coerceIn(0, APK_CLEAN_HARD_MAX_DAYS))

    /**
     * 扫描来源目录：没设置过时用 [DEFAULT_APK_SOURCE_DIRS]；存的是无序 StringSet，
     * 这里统一去重并按字典序排列，保证每次读到的顺序一致（空集合 = 用户手动删光了）。
     */
    fun apkSourceDirs(s: SharedPreferences): List<String> {
        val saved = s.getStringSet(KEY_APK_SOURCE_DIRS, null) ?: return DEFAULT_APK_SOURCE_DIRS
        return saved.mapNotNull(::normalizeApkSourceDir).distinct().sorted()
    }

    /** 写扫描来源目录：与 [apkSourceDirs] 同一套规范化，非法项直接丢弃 */
    fun setApkSourceDirs(
        editor: SharedPreferences.Editor,
        dirs: Collection<String>,
    ): SharedPreferences.Editor = editor.putStringSet(
        KEY_APK_SOURCE_DIRS,
        dirs.mapNotNull(::normalizeApkSourceDir).distinct().toSet(),
    )

    /**
     * 目录规范化：只接受内部存储的绝对路径，去掉首尾空白与末尾 `/`；
     * 含 `..` 或引号/换行的丢弃（这些路径会被拼进 su 命令）。
     */
    fun normalizeApkSourceDir(raw: String): String? {
        val dir = raw.trim().trimEnd('/')
        if (!dir.startsWith("/storage/")) return null
        if (dir.contains("..")) return null
        if (dir.any { it == '\'' || it == '"' || it == '\n' || it == '\r' || it == '\u0000' }) return null
        return dir
    }

    /** 扫描层级，非法值回落到 [DEFAULT_APK_SCAN_DEPTH] */
    fun apkScanDepth(s: SharedPreferences): Int =
        clampApkScanDepth(s.getInt(KEY_APK_SCAN_DEPTH, DEFAULT_APK_SCAN_DEPTH))

    /** 层级取值约定：0 = 不限，1..N = find -maxdepth N（1 = 仅目录本身） */
    fun clampApkScanDepth(depth: Int): Int =
        if (depth in APK_SCAN_DEPTH_VALUES) depth else DEFAULT_APK_SCAN_DEPTH

    /** 写扫描层级：与 [apkScanDepth] 同一夹紧语义 */
    fun setApkScanDepth(editor: SharedPreferences.Editor, depth: Int): SharedPreferences.Editor =
        editor.putInt(KEY_APK_SCAN_DEPTH, clampApkScanDepth(depth))

    fun installerMode(s: SharedPreferences): String =
        s.getString(KEY_INSTALLER_MODE, BROWSER_SYSTEM) ?: BROWSER_SYSTEM

    fun installerPackage(s: SharedPreferences): String =
        s.getString(KEY_INSTALLER_PACKAGE, "").orEmpty()

    fun browserMode(s: SharedPreferences): String =
        s.getString(KEY_BROWSER_MODE, BROWSER_SYSTEM) ?: BROWSER_SYSTEM

    fun browserPackage(s: SharedPreferences): String =
        s.getString(KEY_BROWSER_PACKAGE, "").orEmpty()

    fun edit(context: Context): SharedPreferences.Editor = of(context).edit()
}
