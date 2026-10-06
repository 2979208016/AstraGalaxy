package io.github.proify.lyricon.xinghe.capability.link

import android.content.ClipData
import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 链接助手 · system_server 侧剪贴板钩子。
 *
 * 唯一职责：在 ClipboardService 真正写入剪贴板的那一刻拿到文字，
 * 确认里面有链接，再把链接经 ContentProvider 的 Binder 直连
 * 投递给星河 App。全程只读：不改剪贴板、不落盘、不联网。
 *
 * 崩溃防护：
 *  - hook 回调运行在系统 Binder 线程且可能在锁内被调：
 *    回调里只取文字立刻返回，后续全部扔到独立守护线程。
 *  - system_server 里没有包可见性也没有文件权限：
 *    不读 SharedPreferences（开关判断放到 App 侧），不写文件。
 *  - 只挂一个方法，不碰开机早期路径，不参与系统初始化顺序。
 *  - 回调内所有逻辑包在 runCatching 里，绝不向外抛异常。
 */
internal class LinkHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader
) {

    /** 重活专用线程：回调线程绝不阻塞 */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-linkhook").apply { isDaemon = true }
    }

    /** 从 ClipboardService 实例上读出来的系统 Context，懒缓存 */
    @Volatile
    private var sysContext: Context? = null

    /** 同一段文字的去重：同一秒内同一内容只投递一次 */
    @Volatile
    private var lastText: String? = null
    @Volatile
    private var lastAt = 0L

    /**
     * 注册 hook。命中即返回 true，没命中记录失败。
     * 在 onSystemServerStarting 里调用，此刻 ClipboardService 尚未运行，
     * 但 hook 是对方法的，之后每次调用都会走回调。
     */
    fun install(): Boolean {
        val cls = runCatching {
            Class.forName(CLIPBOARD_SERVICE, false, classLoader)
        }.getOrNull() ?: run {
            logW("找不到 $CLIPBOARD_SERVICE")
            return false
        }

        // 不预设签名：把该类所有同名方法全部挂上，谁被调谁生效。
        // ColorOS 与原生的差别只是参数个数，全挂一遍就能覆盖。
        val methods = cls.declaredMethods.filter { it.name == "setPrimaryClipInternalLocked" }
        if (methods.isEmpty()) {
            logW("ClipboardService 没有 setPrimaryClipInternalLocked")
            return false
        }

        var hooked = 0
        for (m in methods) {
            runCatching {
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        onClipSet(chain.thisObject, chain.args)
                        chain.proceed()
                    }
            }.onSuccess { hooked++ }
        }

        if (hooked > 0) logI("链接助手已挂接（${hooked} 个重载）")
        else logE("所有重载均挂不上")
        return hooked > 0
    }

    /** hook 回调：只做最轻的事，立刻返回 */
    private fun onClipSet(service: Any?, args: List<Any?>) {
        if (!isLinkEnabled()) return
        try {
            val clip = args.getOrNull(0) as? ClipData ?: return
            if (clip.itemCount == 0) return
            val text = runCatching {
                clip.getItemAt(0)?.coerceToText(null)?.toString()
            }.getOrNull() ?: return
            if (text.isBlank()) return

            // 去重：系统会对一次复制连发两次回调（剪贴板 + 相关 profile）
            val now = android.os.SystemClock.elapsedRealtime()
            if (text == lastText && now - lastAt < DEDUP_MS) return
            lastText = text
            lastAt = now

            // 有链接再唤醒工作线程；纯文字复制连线程都不起
            if (!LinkPatterns.hasLink(text)) return
            worker.execute { push(service, text) }
        } catch (_: Throwable) {
            // 回调里绝不抛，protective 模式也会兜住，但记一次日志也是开销
        }
    }

    /** 检查总开关与链接助手开关：关闭时在系统框架中完全不读取剪贴板 */
    private fun isLinkEnabled(): Boolean = runCatching {
        val prefs = module.getRemotePreferences(PREFS)
        prefs.getBoolean(KEY_ENABLED, true) && prefs.getBoolean(KEY_LINK_ENABLED, true)
    }.getOrDefault(true)

    /** 在工作线程里提取链接并投递 */
    private fun push(service: Any?, text: String) {
        val url = LinkPatterns.firstLink(text) ?: return
        val ctx = sysContext ?: resolveContext(service).also { sysContext = it }
        if (ctx == null) {
            logW("拿不到系统 Context，本条丢弃")
            return
        }
        runCatching {
            ctx.contentResolver.call(
                android.net.Uri.parse("content://" + AUTHORITY),
                METHOD_DELIVER,
                url,
                Bundle().apply { putString(EXTRA_KIND, KIND_CLIPBOARD) }
            )
            logI("链接已投递")
        }.onFailure { logE("投递失败", it) }
    }

    /**
     * 直接从 ClipboardService 实例上读 mContext。
     * ClipboardService 继承 SystemService，SystemService 的构造把 Context 存进 mContext。
     * 这条字段在 AOSP 和 ColorOS 都一致，比反射 ActivityThread 稳得多。
     */
    private fun resolveContext(service: Any?): Context? {
        if (service == null) return null
        // 先找 ClipboardService 自己声明的字段，再向上找父类 SystemService
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            val found = runCatching {
                cls!!.getDeclaredField("mContext")
                    .also { it.isAccessible = true }
                    .get(service) as? Context
            }.getOrNull()
            if (found != null) {
                logI("已从 ${cls!!.simpleName}.mContext 拿到 Context")
                return found
            }
            cls = cls.superclass
        }
        logW("在 ${service.javaClass.name} 里找不到 mContext")
        return null
    }

    // ———— 日志：system_server 里只走框架通道，绝不落盘 ————

    private fun logI(msg: String) = module.log(Log.INFO, TAG, msg)
    private fun logW(msg: String) = module.log(Log.WARN, TAG, msg)
    private fun logE(msg: String, t: Throwable? = null) {
        if (t != null) module.log(Log.ERROR, TAG, msg, t)
        else module.log(Log.ERROR, TAG, msg)
    }

    private companion object {
        const val PREFS = "xinghe_settings"
        const val KEY_ENABLED = "module_enabled"
        const val KEY_LINK_ENABLED = "cap_link_enabled"
        const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
        const val AUTHORITY = "io.github.proify.lyricon.xinghe.inbox"
        const val METHOD_DELIVER = "deliver"
        const val EXTRA_KIND = "kind"
        const val KIND_CLIPBOARD = "clipboard"
        const val DEDUP_MS = 1_000L
        const val TAG = "AstraGalaxy"
    }
}
