package io.github.proify.lyricon.xinghe.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.proify.lyricon.xinghe.Module
import io.github.proify.lyricon.xinghe.capability.link.LinkHook
import io.github.proify.lyricon.xinghe.lyric.Constants
import io.github.proify.lyricon.xinghe.lyric.HookCrashLog
import io.github.proify.lyricon.xinghe.lyric.LocalLyricProvider
import io.github.proify.lyricon.xinghe.lyric.ModuleLogger
import io.github.proify.lyricon.xinghe.lyric.XingHeLyricProvider
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed（LSPosed 102+）入口。
 *
 * 两条能力各自挂在自己需要的进程里，互不干扰：
 *  - 歌词线：挂在音乐播放器进程里（与 v1.22 逐字一致）。
 *  - 链接线：挂在 system_server（作用域「系统框架」/ system）里，
 *    通过 onSystemServerStarting hook ClipboardService。
 *
 * system_server 里的禁忌：
 *   - 不调用 getRemotePreferences：SELinux 会拒绝；
 *   - 不做文件 IO；
 *   - 不用 ModuleLogger：它会写文件；
 *   - 只用模块自带的 log()：写 LSPosed 日志通道，零 IO。
 *
 * 链接开关：system_server 里读不到设置，干脆不在这里判断；
 *   检测永远开，真正要不要上岛由 App 侧的 LinkInboxProvider 决定。
 */
class HookEntry : XposedModule() {

    private val logger = ModuleLogger(this)

    /** 歌词线每个进程只装一次 */
    private val lyricInstalled = lyricInstalledGate

    /** 链接线每个进程只装一次 */
    private val linkInstalled = linkInstalledGate

    @Volatile
    private var isSystemServer = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        isSystemServer = param.isSystemServer
        if (isSystemServer) {
            logSys("AstraGalaxy loaded in system_server")
            return
        }
        if (!loadedLoggedGate.compareAndSet(false, true)) return
        logger.info(
            "AstraGalaxy loaded: process=" + param.processName +
                ", framework=" + frameworkName + " " + frameworkVersion +
                " (" + frameworkVersionCode + "), api=" + apiVersion
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (!linkInstalled.compareAndSet(false, true)) {
            logSys("链接助手已装过，跳过")
            return
        }
        runCatching {
            LinkHook(module = this, classLoader = param.classLoader).install()
        }.onFailure { logSys("链接助手挂载失败: ${it.message}") }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (!param.isFirstPackage) return
        if (packageName == Module.PACKAGE_NAME) return
        if (isSystemFramework(packageName)) return

        if (!lyricInstalled.compareAndSet(false, true)) return
        if (!isEnabled()) {
            logger.info("Module disabled in settings, skip $packageName")
            return
        }

        logger.info("Package ready: $packageName, process=" + currentProcessName())
        try {
            if (packageName == Constants.PLAYER_PACKAGE_NAME) {
                XingHeLyricProvider(this, logger, param.classLoader).installHooks()
                return
            }
            LocalLyricProvider(
                module = this,
                logger = logger,
                classLoader = param.classLoader,
                hostPackage = packageName,
                processName = currentProcessName(),
                recipe = Constants.recipeOf(packageName)
            ).installHooks()
        } catch (throwable: Throwable) {
            logger.error("Failed to install hooks for $packageName", throwable)
            HookCrashLog.record("installHooks:$packageName", throwable)
        }
    }

    private fun isEnabled(): Boolean = runCatching {
        getRemotePreferences(ModulePrefs.NAME)
            .getBoolean(ModulePrefs.KEY_ENABLED, true)
    }.getOrDefault(true)

    private fun isSystemFramework(packageName: String): Boolean =
        packageName == "android" || packageName == "system" || packageName == "system_server"

    private fun currentProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
    }.getOrDefault("")

    private fun logSys(msg: String) {
        runCatching { log(Log.INFO, "AstraGalaxy", msg) }
    }

    private companion object {
        val loadedLoggedGate = AtomicBoolean(false)
        val lyricInstalledGate = AtomicBoolean(false)
        val linkInstalledGate = AtomicBoolean(false)
    }
}
