package io.github.proify.astrafirmament.hook.core

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.proify.astrafirmament.settings.ModulePrefs
import io.github.proify.astrafirmament.shared.Redact

/**
 * 统一日志：只写 LSPosed 框架通道，且受「开发者日志」开关控制。
 *
 * 审核整改（7.7）：
 *  - 【已关闭的开关要真的关掉】旧版 module.log **无条件**写，应用内开关只写
 *    SharedPreferences，等于关不掉。现在 debug/info/warn/error 先查开关，
 *    关闭时连 module.log 都不写（日志通道整体静默）。
 *  - 开关从 ModulePrefs 远程偏好读取，**限频**（[GATE_TTL_MS] 内只读一次），
 *    @Volatile 缓存 + 时间戳：不逐条读偏好（system_server 性能红线）。
 *  - 写之前统一过 [Redact.line]：完整路径、完整网址、WiFi 名称、蓝牙名与 MAC、
 *    歌名歌手一律抹成占位符（审核要求「日志去掉完整路径与用户标识」）；
 *    诊断需要的类型/后缀/大小/耗时等非身份信息照常记录。
 *  - 唯一例外：`link-hook <版本> loaded` 这行**不受开关限制**——权限中心的
 *    「模块已加载/版本」只靠它判断（[io.github.proify.astrafirmament.shared.LspLoadProbe]），
 *    关日志不能把功能状态一起关死。该行不含任何用户数据（只有版本号）。
 *
 * ⚠️ 历史教训（本类曾经的缺陷，已移除）：
 *  旧版在**宿主进程**里把日志落到宿主自己的 files/lyricon_debug.log——
 *  被勾作用域的每个进程（微信、QQ、SystemUI、播放器……）都会被写一份，
 *  日志散落 7+ 处、无开关常开、还占宿主空间。越界写别人目录既不是用户要的，
 *  也是不允许的。
 *
 * 性能红线（锁内逐条 IO 会拖死 system_server）：
 *  - 开关状态限频读取（2 秒一次），不逐条读偏好；
 *  - 不在 system_server 里做任何文件写入；全程 runCatching，日志永不反伤功能。
 *
 * 7.7 又删掉一条 7.6 遗留的死路径：原先还有一份写模块 filesDir 的
 * `xinghe_dev.log`（devWrite / refreshDevLog）。全仓没有任何读取方，
 * 而应用侧日志报告走的是 [io.github.proify.astrafirmament.capability.link.DevLog]，
 * 所以这里不再落盘，避免「写了没人读」的第二份日志。
 */
class ModuleLogger(
    private val module: XposedModule,
    private val tag: String = "AstraFirmament"
) {
    // ---------------- 开发者日志开关（远程偏好，限频缓存） ----------------

    private val gateLock = Any()

    @Volatile private var gateOn = false
    @Volatile private var gateChecked = false
    @Volatile private var gateCheckedAt = 0L

    /**
     * 加载/版本证据行：`link-hook <版本> loaded`（LinkHook.install 第一行）。
     *
     * 权限中心用它判断「模块真的加载了、加载的是哪一版」，这条链路不能随
     * 日志开关一起消失。限制得很死（整行就是这两个固定片段），不会把别的
     * 日志也放出去（原文，不含任何用户数据，本行内容只有模块版本号）。
     */
    private fun isLoadEvidence(message: String): Boolean {
        val m = message.trim()
        if (m.length > 64) return false
        return m.startsWith("link-hook ") && m.endsWith(" loaded")
    }

    /** 开关是否打开；缓存 [GATE_TTL_MS] 毫秒，最多每 2 秒读一次远程偏好。 */
    private fun devLogOn(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (gateChecked && now - gateCheckedAt < GATE_TTL_MS) return gateOn
        synchronized(gateLock) {
            val at = SystemClock.elapsedRealtime()
            if (gateChecked && at - gateCheckedAt < GATE_TTL_MS) return gateOn
            gateOn = runCatching {
                module.getRemotePreferences(ModulePrefs.NAME)
                    .getBoolean(ModulePrefs.KEY_DEV_LOG, false)
            }.getOrDefault(false)
            gateChecked = true
            gateCheckedAt = at
            return gateOn
        }
    }

    // ---------------- 统一入口 ----------------

    fun debug(message: String) = emit(Log.DEBUG, message)

    fun info(message: String) = emit(Log.INFO, message)

    fun warn(message: String) = emit(Log.WARN, message)

    fun error(message: String, throwable: Throwable? = null) =
        emit(Log.ERROR, message, throwable)

    private fun emit(priority: Int, message: String, throwable: Throwable? = null) {
        runCatching {
            if (!isLoadEvidence(message) && !devLogOn()) return
            val safe = Redact.line(message)
            if (throwable == null) {
                module.log(priority, tag, safe)
            } else {
                module.log(priority, tag, safe, throwable)
            }
        }
    }

    private companion object {
        /** 开关缓存有效期：这段时间内所有日志共用一次远程偏好读取 */
        const val GATE_TTL_MS = 2_000L
    }
}
