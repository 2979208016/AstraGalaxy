package io.github.proify.lyricon.xinghe.capability.link

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.proify.lyricon.xinghe.settings.ModulePrefs

/**
 * 打开链接的透明中转页。
 *
 * 存在理由：Android 10+ 会拦掉后台应用直接 startActivity（BAL_BLOCK，
 * 不报错，表现是「卡片消失但浏览器没开」）。由星河岛通过 PendingIntent
 * 把它拉起来，就已处于合法语境，再由它跳浏览器就不会再被拦。
 */
class LinkOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.dataString
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        Log.i(TAG, "opening $url")
        openInBrowser(url)
        finish()
    }

    /** 按设置页的策略打开：跟随系统 / 固定浏览器 / 每次询问 */
    private fun openInBrowser(url: String) {
        val prefs = ModulePrefs.of(this)
        val mode = ModulePrefs.browserMode(prefs)
        val uri = Uri.parse(url)

        when (mode) {
            ModulePrefs.BROWSER_PINNED -> {
                val pkg = ModulePrefs.browserPackage(prefs)
                if (pkg.isNotBlank()) {
                    val ok = runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                                .setPackage(pkg)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    }.getOrDefault(false)
                    if (ok) return
                    Log.w(TAG, "pinned browser $pkg unavailable")
                }
                startDefault(uri)
            }
            ModulePrefs.BROWSER_ASK -> {
                val probe = Intent(Intent.ACTION_VIEW, uri)
                val chooser = Intent.createChooser(probe, "选择浏览器")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(chooser) }
            }
            else -> startDefault(uri)
        }
    }

    private fun startDefault(uri: Uri) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open browser failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "XingHe-Link"
    }
}
