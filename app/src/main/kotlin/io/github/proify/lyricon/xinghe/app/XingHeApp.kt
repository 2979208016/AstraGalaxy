package io.github.proify.lyricon.xinghe.app

import android.app.Application
import android.util.Log
import io.github.proify.lyricon.xinghe.capability.link.LinkHub
import io.github.proify.lyricon.xinghe.settings.ModuleEnabledState
import io.github.proify.lyricon.xinghe.settings.ModulePrefs

/**
 * 星河的应用进程入口。
 *
 * 只做两件事：
 *  1. 注册框架心跳监听（LSPosed 加载状态上报）；
 *  2. 链接助手开启时预连星河岛，减少第一次投送的等待。
 *
 * 不主动做任何后台常驻：连接由接入库管理，用户不开启链接助手就完全不连。
 */
class XingHeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ModuleEnabledState.startListening(this)
        val prefs = ModulePrefs.of(this)
        if (ModulePrefs.isEnabled(prefs) && ModulePrefs.isSmartIslandEnabled(prefs)) {
            runCatching { LinkHub.warmUp(this) }
                .onFailure { Log.w(TAG, "island warm-up failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "XingHe"
    }
}
