package io.github.proify.lyricon.xinghe.capability.link

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 设置页用：列出手机上真正能打开 http(s) 链接的应用。
 * 需要在清单里声明 queries（见 AndroidManifest 的 VIEW/http 段）。
 */
object BrowserChoice {

    data class BrowserApp(val label: String, val packageName: String)

    fun installed(context: Context): List<BrowserApp> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val resolved = runCatching {
            pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                BrowserApp(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }
}
