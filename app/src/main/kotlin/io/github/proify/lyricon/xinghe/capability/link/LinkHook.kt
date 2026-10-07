package io.github.proify.lyricon.xinghe.capability.link

import android.content.ClipData
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import java.util.concurrent.Executors

/** 系统框架侧智能上岛：剪贴板链接和手机号。 */
internal class LinkHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xinghe-smart-island-hook").apply { isDaemon = true } }
    @Volatile private var sysContext: Context? = null
    @Volatile private var lastText: String? = null
    @Volatile private var lastAt = 0L

    fun install(): Boolean {
        val cls = runCatching { Class.forName(CLIPBOARD_SERVICE, false, classLoader) }.getOrNull() ?: return false
        val methods = cls.declaredMethods.filter { it.name == "setPrimaryClipInternalLocked" }
        var hooked = 0
        for (m in methods) {
            runCatching {
                module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    onClipSet(chain.thisObject, chain.args)
                    chain.proceed()
                }
            }.onSuccess { hooked++ }
        }
        if (hooked > 0) logI("智能上岛已挂接：剪贴板=$hooked")
        return hooked > 0
    }

    private fun onClipSet(service: Any?, args: List<Any?>) {
        if (!isMasterEnabled()) return
        val prefs = runCatching { module.getRemotePreferences(PREFS) }.getOrNull() ?: return
        if (!ModulePrefs.isLinkEnabled(prefs) && !ModulePrefs.isPhoneEnabled(prefs)) return
        try {
            val clip = args.getOrNull(0) as? ClipData ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(null)?.toString().orEmpty()
            if (text.isBlank()) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (text == lastText && now - lastAt < DEDUP_MS) return
            lastText = text; lastAt = now
            val number = if (ModulePrefs.isPhoneEnabled(prefs)) LinkPatterns.firstPhone(text) else null
            val url = if (number == null && ModulePrefs.isLinkEnabled(prefs)) LinkPatterns.firstLink(text) else null
            if (number == null && url == null) return
            val ctx = sysContext ?: resolveContext(service)?.also { sysContext = it } ?: return
            worker.execute { if (number != null) deliver(ctx, KIND_PHONE, number) else deliver(ctx, KIND_LINK, url!!) }
        } catch (_: Throwable) { }
    }

    private fun deliver(ctx: Context, kind: String, value: String) {
        runCatching {
            ctx.contentResolver.call(android.net.Uri.parse("content://$AUTHORITY"), METHOD_DELIVER, value, android.os.Bundle().apply { putString(EXTRA_KIND, kind) })
        }.onFailure { logE("智能上岛投递失败", it) }
    }

    private fun isMasterEnabled(): Boolean = runCatching {
        val p = module.getRemotePreferences(PREFS)
        p.getBoolean(ModulePrefs.KEY_ENABLED, true) && ModulePrefs.isSmartIslandEnabled(p)
    }.getOrDefault(false)

    private fun resolveContext(service: Any?): Context? {
        var cls: Class<*>? = service?.javaClass
        while (cls != null) {
            val found = runCatching { cls!!.getDeclaredField("mContext").also { it.isAccessible = true }.get(service) as? Context }.getOrNull()
            if (found != null) return found
            cls = cls.superclass
        }
        return null
    }

    private fun logI(msg: String) = module.log(Log.INFO, TAG, msg)
    private fun logE(msg: String, t: Throwable) = module.log(Log.ERROR, TAG, msg, t)

    private companion object {
        const val PREFS = ModulePrefs.NAME
        const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
        const val AUTHORITY = "io.github.proify.lyricon.xinghe.inbox"
        const val METHOD_DELIVER = "deliver"
        const val EXTRA_KIND = "kind"
        const val KIND_LINK = "link"
        const val KIND_PHONE = "phone"
        const val DEDUP_MS = 1_000L
        const val TAG = "AstraGalaxy"
    }
}
