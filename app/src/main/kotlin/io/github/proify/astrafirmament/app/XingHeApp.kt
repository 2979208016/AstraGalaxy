package io.github.proify.astrafirmament.app

import android.app.Application
import android.util.Log
import io.github.proify.astrafirmament.capability.link.LinkHub
import io.github.proify.astrafirmament.settings.ModulePrefs

/**
 * 星穹的应用进程入口。
 *
 * 只做两件事：链接助手开启时预连星河岛，减少第一次投送的等待；拉起 QQ/TIM 安装包扫描
 * （见 [ThirdPartyApkScanner]，只读它们的落盘目录，不 hook 对方进程）。
 *
 * 不主动做任何后台常驻：连接由接入库管理，用户不开启链接助手就完全不连；扫描是进程内的
 * 轻量轮询，息屏与进程不在时什么都不做。
 */
class XingHeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = ModulePrefs.of(this)
        if (ModulePrefs.isEnabled(prefs) && ModulePrefs.isSmartIslandEnabled(prefs)) {
            runCatching { LinkHub.warmUp(this) }
                .onFailure { Log.w(TAG, "island warm-up failed: ${it.message}") }
        }
        // 扫描器自己的开关每轮现读，所以这里无条件拉起：用户中途打开「文件安装」不必重启进程
        runCatching { ThirdPartyApkScanner.start(this) }
            .onFailure { Log.w(TAG, "apk scan start failed: ${it.message}") }
        // 媒体库兜底轮询：system 侧的发现链路在部分 ROM 上会被削掉（广播投递/进程冻结），
        // 这里在 App 进程里直接查最近落盘的安装包，保证「智能文件」不依赖跨进程投递
        runCatching { ApkMediaPoller.start(this) }
            .onFailure { Log.w(TAG, "media poll start failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "AstraFirmament"
    }
}
