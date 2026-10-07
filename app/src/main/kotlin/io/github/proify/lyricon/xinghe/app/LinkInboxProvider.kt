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

class LinkInboxProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle.EMPTY
        val uid = android.os.Binder.getCallingUid()
        if (uid != android.os.Process.SYSTEM_UID && uid != android.os.Process.myUid()) return Bundle.EMPTY
        return when (method) {
            METHOD_DELIVER -> onDeliver(ctx.applicationContext, arg, extras?.getString(EXTRA_KIND))
            METHOD_HELLO -> onHello(ctx.applicationContext, extras)
            else -> Bundle.EMPTY
        }
    }

    private fun onHello(app: Context, extras: Bundle?): Bundle {
        runCatching { ModuleEnabledState.markLoaded(app, extras?.getString(EXTRA_FRAMEWORK), extras?.getInt(EXTRA_MODULE_VERSION, 0) ?: 0) }
            .onFailure { Log.e(TAG, "markLoaded failed", it) }
        return Bundle.EMPTY
    }

    private fun onDeliver(app: Context, value: String?, kind: String?): Bundle {
        val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return Bundle.EMPTY
        val prefs = ModulePrefs.of(app)
        if (!ModulePrefs.isEnabled(prefs) || !ModulePrefs.isSmartIslandEnabled(prefs)) return Bundle.EMPTY
        val accepted = when (kind) {
            KIND_PHONE -> ModulePrefs.isPhoneEnabled(prefs) && LinkHub.postPhoneBlocking(app, text, AWAIT_MS)
            else -> {
                val uri = runCatching { Uri.parse(text) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                if ((scheme != "http" && scheme != "https") || uri?.host.isNullOrBlank()) false
                else ModulePrefs.isLinkEnabled(prefs) && LinkHub.postBlocking(app, text, AWAIT_MS)
            }
        }
        return Bundle().apply { putInt(RESULT_CODE, if (accepted) 0 else -1) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "io.github.proify.lyricon.xinghe.inbox"
        const val METHOD_DELIVER = "deliver"
        const val METHOD_HELLO = "hello"
        const val EXTRA_KIND = "kind"
        const val EXTRA_FRAMEWORK = "framework"
        const val EXTRA_MODULE_VERSION = "moduleVersion"
        const val RESULT_CODE = "resultCode"
        private const val KIND_PHONE = "phone"
        private const val TAG = "XingHe"
        private const val AWAIT_MS = 5_000L
    }
}
