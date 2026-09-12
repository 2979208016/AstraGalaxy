package io.github.proify.lyricon.xinghe.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed（LSPosed 102+）入口。通过 resources/META-INF/xposed/java_init.list 声明。
 *
 * AstraGalaxy 是星流（AstraFlow）的 LyricON「歌词提供者」，按目标进程分派：
 *  - com.music          -> MeloYou 歌词提供者（读它自己导出的歌词文件）
 *  - 已适配播放器        -> 通用提供者：本地缓存优先 + 网络旁路
 *  - 其它任意被勾选的应用 -> 通用提供者：只走网络旁路（读不到也不影响对方）
 *
 * 模块本体不请求任何在线歌词接口：网络歌词来自播放器自己刚收到的响应。
 */
class HookEntry : XposedModule() {

    private val logger = ModuleLogger(this)
    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        logger.info(
            "AstraGalaxy loaded: process=${param.processName}, " +
                "framework=$frameworkName $frameworkVersion ($frameworkVersionCode), api=$apiVersion"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (!param.isFirstPackage) return
        if (packageName == Constants.PROVIDER_PACKAGE_NAME) return
        if (!installed.compareAndSet(false, true)) return

        if (!isEnabled()) {
            logger.info("Module disabled in settings, skip $packageName")
            return
        }

        logger.info("Package ready: $packageName, process=${currentProcessName()}")
        try {
            if (packageName == Constants.PLAYER_PACKAGE_NAME) {
                XingHeLyricProvider(this, logger, param.classLoader).installHooks()
                return
            }
            // 作用域是开放的：适配表里有配方的走「本地缓存 + 网络」，
            // 其它应用一律走「网络旁路」，让用户自己勾选就能试。
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
        }
    }

    /** 设置页的「启用模块」开关（默认开启） */
    private fun isEnabled(): Boolean = runCatching {
        getRemotePreferences(Constants.PREFS_NAME)
            .getBoolean(Constants.KEY_ENABLED, true)
    }.getOrDefault(true)

    /** 当前进程名（/proc/self/cmdline 最稳，任何进程、任何 API 级别都可用） */
    private fun currentProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
    }.getOrDefault("")
}
