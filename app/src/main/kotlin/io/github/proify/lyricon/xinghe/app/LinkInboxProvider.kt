package io.github.proify.lyricon.xinghe.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.proify.lyricon.xinghe.capability.link.LinkHub
import io.github.proify.lyricon.xinghe.settings.ModuleEnabledState
import io.github.proify.lyricon.xinghe.settings.ModulePrefs

/**
 * 链接投递入口（ContentProvider，Binder 直连）。
 *
 * system_server 没有用户身份，普通广播会被 ColorOS 的启动管控拦下；
 * Provider 调用是 Binder 直连，不经过广播队列，也不要求 App 进程活着。
 *
 * call() 现在**同步等待**到投送完成再返回，原因：
 *  - 用户墓碑机制会在 Binder 调用一结束就把进程压回缓存态，异步线程里
 *    再去连岛根本来不及，进程被冻住就发不出去；
 *  - 同步等待期间进程处于「正在处理跨进程调用」状态，墓碑不会冻它；
 *  - 5 秒内能连上就把卡片投出去，超时也要尽力把任务排上队（下次投递或
 *    App 启动时补发）。
 *  - system_server 侧投递方（LinkHook）本来就把 call 放在独立工作线程里，
 *    这里没有阻塞主线程的风险。
 *
 * 链接开关在这里判断（App 进程能正常读 SharedPreferences，
 * system_server 侧读了会崩，所以不在那边读）。
 */
class LinkInboxProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle.EMPTY
        return when (method) {
            METHOD_DELIVER -> onDeliver(ctx.applicationContext, arg)
            METHOD_HELLO -> onHello(ctx.applicationContext, extras)
            else -> Bundle.EMPTY
        }
    }

    /** 模块自报身份：写「模块确实被框架加载了」的状态 */
    private fun onHello(app: Context, extras: Bundle?): Bundle {
        val framework = extras?.getString(EXTRA_FRAMEWORK)
        val version = extras?.getInt(EXTRA_MODULE_VERSION, 0) ?: 0
        runCatching { ModuleEnabledState.markLoaded(app, framework, version) }
            .onFailure { Log.e(TAG, "markLoaded failed", it) }
        return Bundle.EMPTY
    }

    /** 收链接：开关判断 + 同步等待 LinkHub 投送完成 */
    private fun onDeliver(app: Context, url: String?): Bundle {
        val link = url?.takeIf { it.isNotBlank() } ?: return Bundle.EMPTY
        val prefs = ModulePrefs.of(app)
        if (!ModulePrefs.isEnabled(prefs) || !ModulePrefs.isLinkEnabled(prefs)) {
            Log.i(TAG, "链接助手已关闭，忽略投递")
            return Bundle.EMPTY
        }
        Log.i(TAG, "Link received: $link")
        val ok = LinkHub.postBlocking(app, link, AWAIT_MS)
        return Bundle().apply { putInt(RESULT_CODE, if (ok) 0 else -1) }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "io.github.proify.lyricon.xinghe.inbox"
        const val METHOD_DELIVER = "deliver"
        const val METHOD_HELLO = "hello"
        const val EXTRA_FRAMEWORK = "framework"
        const val EXTRA_MODULE_VERSION = "moduleVersion"
        const val RESULT_CODE = "resultCode"
        private const val TAG = "XingHe"
        /** call() 同步等待上岛的最长时间 */
        private const val AWAIT_MS = 5_000L
    }
}
