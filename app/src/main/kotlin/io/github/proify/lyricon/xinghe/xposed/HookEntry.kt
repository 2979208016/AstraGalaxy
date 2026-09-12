package io.github.proify.lyricon.xinghe.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed（LSPosed 102+）入口。
 * 通过 resources/META-INF/xposed/java_init.list 声明。
 *
 * 星河是星流的 LyricON「歌词提供者」，按目标进程分派：
 *  - com.music  -> MeloYou 歌词提供者（XingHeLyricProvider，读它自己导出的歌词文件）
 *  - 其他已适配播放器 -> 通用本地歌词提供者（LocalLyricProvider，读其本地歌词缓存）
 *
 * 全部为离线读取，不含任何在线歌词接口。歌词统一由星流原生「胶囊歌词」呈现。
 */
class HookEntry : XposedModule() {

    private val logger = ModuleLogger(this)
    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        logger.info(
            "XingHe loaded: process=${param.processName}, " +
                "framework=$frameworkName $frameworkVersion ($frameworkVersionCode), api=$apiVersion"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (!param.isFirstPackage) return
        if (param.applicationInfo.processName != packageName) return
        if (!installed.compareAndSet(false, true)) return

        logger.info("Package ready: $packageName, classLoader=${param.classLoader}")
        try {
            if (packageName == Constants.PLAYER_PACKAGE_NAME) {
                XingHeLyricProvider(this, logger, param.classLoader).installHooks()
                return
            }
            val recipe = Constants.recipeOf(packageName) ?: return
            LocalLyricProvider(this, logger, param.classLoader, packageName, recipe).installHooks()
        } catch (throwable: Throwable) {
            logger.error("Failed to install hooks for $packageName", throwable)
        }
    }
}
