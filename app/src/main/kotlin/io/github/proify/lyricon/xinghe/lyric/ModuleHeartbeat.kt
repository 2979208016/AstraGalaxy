package io.github.proify.lyricon.xinghe.lyric

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xinghe.Module
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块心跳上报：把「本模块已被框架加载并正在工作」一次性写给 App 端。
 *
 * 为什么不在 HookEntry#onPackageReady 里发：那个时机 ActivityThread.currentApplication()
 * 还可能为 null；而这里由「provider 已经注册成功」触发，Context 必然可用。
 *
 * 每个进程最多发一次（gate），目标进程可以是任意被 hook 的播放器 ——
 * 只要有一个应用装了模块 hook 并注册 provider，就证明模块活着。
 * 跨进程 call 到自己 App 的 ContentProvider 是 Binder 直连，App 没在跑也会被拉起。
 */
internal object ModuleHeartbeat {
    private val sent = AtomicBoolean(false)

    fun report(context: Context, module: XposedModule, logger: ModuleLogger) {
        if (!sent.compareAndSet(false, true)) return
        val extras = android.os.Bundle().apply {
            putString("framework", module.frameworkName + " " + module.frameworkVersion)
            putInt("moduleVersion", 45)
        }
        val authority = Module.PACKAGE_NAME + ".inbox"
        // 主路径：ContentProvider call。先 resolve 确认 authority 对当前进程可见 ——
        // 部分 ROM 对未运行的 provider 直接 call 会抛 "Failed to find provider info"，
        // 这时就得退回广播。
        val resolvable = runCatching {
            context.packageManager.resolveContentProvider(authority, 0) != null
        }.getOrDefault(false)
        var delivered = false
        if (resolvable) {
            delivered = runCatching {
                context.contentResolver.call(
                    android.net.Uri.parse("content://" + authority),
                    "hello", null, extras
                )
                true
            }.getOrDefault(false)
        }
        if (!delivered) {
            // 广播兜底：必须显式带 package，否则 ROM 会拦下第三方应用间的隐式广播
            delivered = runCatching {
                context.sendBroadcast(
                    android.content.Intent("io.github.proify.lyricon.xinghe.action.ALIVE")
                        .setPackage(Module.PACKAGE_NAME)
                        .putExtra("framework", module.frameworkName + " " + module.frameworkVersion)
                )
                true
            }.getOrDefault(false)
        }
        logger.info("心跳上报" + (if (delivered) "成功" else "失败") + "：framework=" + module.frameworkName)
    }
}
