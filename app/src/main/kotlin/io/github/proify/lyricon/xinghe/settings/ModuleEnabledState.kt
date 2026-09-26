package io.github.proify.lyricon.xinghe.settings

import android.content.Context
import android.content.Intent

/**
 * 判断「本模块是否已在 LSPosed 中启用」。
 *
 * 走过的弯路（都不可靠，记在这里免得再犯）：
 *  - `Settings.Global.enabled_modules`：老框架才写，LSPosed 不写（实测为 null）；
 *  - 扫 `/proc` 找 lspd 进程：本机 /proc 以 hidepid=invisible 挂载，普通应用看不见别人；
 *  - 读 `/data/adb/lspd/config/modules_config.db`：`/data/adb` 是 0700，进不去；
 *  - 模块写共享偏好：`getRemotePreferences` 是**只读**的（实测 "Read only implementation"）。
 *
 * 现在的判据：**框架有没有加载并运行过本模块**。两条来源：
 *   1. 主路径 —— 模块经 ContentProvider 的 `hello` 直接把身份写给 App。
 *      Binder 直连，**系统会按需拉起 App 进程**，所以不会丢。
 *   2. 兜底 —— 模块发的心跳广播。App 没在运行时这条必丢，仅作补充。
 *
 * ⚠️ 之前只有第 2 条，这正是「LSPosed 里已授权、星河却显示未连接框架」的根因：
 * App 冷启动时没有收到过任何一条广播，布尔值就一直是 false。
 * 加了第 1 条之后，模块只要被注入过至少一次，状态就一定能写到 App 里。
 *
 */
object ModuleEnabledState {

    /** 模块被框架加载过（由心跳接收器写入） */
    const val KEY_FRAMEWORK_LOADED = "framework_loaded"

    /** 框架名（LSPosed / EdXposed…） */
    const val KEY_FRAMEWORK_NAME = "framework_name"

    /** 记录时间 */
    const val KEY_FRAMEWORK_AT = "framework_loaded_at"

    /** 模块自报的版本号 */
    const val KEY_MODULE_VERSION = "module_version"

    fun isModuleEnabled(context: Context): Boolean = runCatching {
        ModulePrefs.of(context).getBoolean(KEY_FRAMEWORK_LOADED, false)
    }.getOrDefault(false)

    fun frameworkName(context: Context): String? = runCatching {
        ModulePrefs.of(context).getString(KEY_FRAMEWORK_NAME, null)
    }.getOrNull()

    /** 心跳的 action 与字段（模块侧发，这里收） */
    private const val ACTION_ALIVE = "io.github.proify.lyricon.xinghe.action.ALIVE"
    private const val EXTRA_FRAMEWORK = "framework"

    /**
     * 注册心跳监听（**兜底路径**）。
     *
     * 主路径是模块经 Provider 的 hello 直连写入，见 LinkInboxProvider。
     * 那条路不会丢；这条广播在 App 没运行时必丢，只用于兼容旧版模块。
     *
     * 注册一次就够，重复调用会被忽略。
     */
    @Volatile
    private var listening = false

    fun startListening(context: Context) {
        if (listening) return
        listening = true
        val app = context.applicationContext
        runCatching {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_ALIVE) return
                    markLoaded(app, intent.getStringExtra(EXTRA_FRAMEWORK))
                }
            }
            // RECEIVER_EXPORTED：心跳来自 system_server（另一个 uid），必须导出才收得到
            app.registerReceiver(
                receiver,
                android.content.IntentFilter(ACTION_ALIVE),
                Context.RECEIVER_EXPORTED
            )
        }
    }

    /**
     * 记录一次「模块确实活着」。
     *
     * 两条来源都会调它：
     *   1. [LinkInboxProvider] 的 hello —— **主路径**，Binder 直连，不会丢；
     *   2. 心跳广播 —— 兜底（旧版模块/旧版 APK 混合时还有用）。
     *
     * @param moduleVersion 模块自报的版本号，0 表示对方没带（旧版）。
     *   界面可以据此提示「模块版本过旧」。这里只记录，不做拦截。
     */
    fun markLoaded(context: Context, framework: String?, moduleVersion: Int = 0) {
        runCatching {
            ModulePrefs.of(context).edit()
                .putBoolean(KEY_FRAMEWORK_LOADED, true)
                .putString(KEY_FRAMEWORK_NAME, framework)
                .putLong(KEY_FRAMEWORK_AT, System.currentTimeMillis())
                .putInt(KEY_MODULE_VERSION, moduleVersion)
                .apply()
        }
    }

    /** 模块自报的版本号；0 表示还没收到过（或对端是旧版） */
    fun moduleVersion(context: Context): Int = runCatching {
        ModulePrefs.of(context).getInt(KEY_MODULE_VERSION, 0)
    }.getOrDefault(0)

}
