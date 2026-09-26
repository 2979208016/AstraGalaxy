package io.github.proify.lyricon.xinghe.settings

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
    const val KEY_BROWSER_MODE = "link_browser_mode"
    const val KEY_BROWSER_PACKAGE = "link_browser_package"
    const val KEY_LINK_HINT_ONLY = "cap_link_hint_only"

    /** 浏览器策略：跟随系统默认 */
    const val BROWSER_SYSTEM = "system"

    /** 浏览器策略：固定在用户选定的某个浏览器 */
    const val BROWSER_PINNED = "pinned"

    /** 浏览器策略：每次让用户选 */
    const val BROWSER_ASK = "ask"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

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
    fun isLinkEnabled(s: SharedPreferences): Boolean = s.getBoolean(KEY_LINK, true)

    /** 只在岛上给个安静提示，不自动展开、不响铃 */
    fun isHintOnly(s: SharedPreferences): Boolean = s.getBoolean(KEY_LINK_HINT_ONLY, true)

    fun browserMode(s: SharedPreferences): String =
        s.getString(KEY_BROWSER_MODE, BROWSER_SYSTEM) ?: BROWSER_SYSTEM

    fun browserPackage(s: SharedPreferences): String =
        s.getString(KEY_BROWSER_PACKAGE, "").orEmpty()

    fun edit(context: Context): SharedPreferences.Editor = of(context).edit()
}
