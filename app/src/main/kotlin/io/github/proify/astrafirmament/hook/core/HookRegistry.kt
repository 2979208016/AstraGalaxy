package io.github.proify.astrafirmament.hook.core

import io.github.libxposed.api.XposedModule

/**
 * Hook 统一注册表（规范第 5 条：集中注册/注销、独立 try-catch、整块回滚）。
 *
 * 约定：
 *  - 每个子功能一个注册函数（install()），全部经由本表登记；
 *  - 子功能失败时抛异常 → 本表捕获并整块标记失败（不影响其他子功能）；
 *  - unhook 由 libxposed 的 hook 返回句柄持有——本表保存句柄，注销时统一调。
 *
 * libxposed 的 unhooker 语义：模块进程生命周期内 Hook 随进程消亡，
 * 这里提供机制（登记/注销）而非强制时机——播放器进程的 Hook 通常活到
 * 进程死，注销入口留给「开关关闭」场景。
 */
class HookRegistry(
    private val module: XposedModule,
    private val tag: String
) {
    /** 已注册子功能：名字 → 注销块（null=该功能不可注销/进程级 Hook） */
    private val registered = LinkedHashMap<String, (() -> Unit)?>()
    private val failures = LinkedHashMap<String, Throwable>()

    val registeredNames: List<String> get() = registered.keys.toList()
    val failedNames: List<String> get() = failures.keys.toList()

    /**
     * 注册一个子功能：独立 try-catch，失败记录不抛出（不阻断其他功能）。
     * @param unhook 注销块（null 表示该功能的 Hook 进程级存活，无注销入口）
     */
    fun installFeature(name: String, unhook: (() -> Unit)? = null, install: () -> Unit) {
        if (registered.containsKey(name) || failures.containsKey(name)) return
        try {
            install()
            registered[name] = unhook
        } catch (t: Throwable) {
            failures[name] = t
            module.log(5, tag, "feature install failed: $name (${t.message})")  // 5=Log.WARN
        }
    }

    /** 注销指定子功能（登记了注销块的才有效） */
    fun uninstallFeature(name: String) {
        val unhook = registered.remove(name) ?: return
        runCatching { unhook() }
            .onFailure { module.log(5, tag, "unhook failed: $name") }  // 5=Log.WARN
    }

    /** 指定子功能的失败异常（未失败返回 null） */
    fun failureOf(name: String): Throwable? = failures[name]

    /** 状态快照（诊断/日志用） */
    fun describe(): String =
        "hook-registry ok=" + registered.keys + " failed=" + failures.keys
}
