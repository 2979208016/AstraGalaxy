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
 *  - com.music                 -> MeloYou 歌词提供者（XingHeLyricProvider）
 *  - 通用音乐应用白名单         -> 通用在线歌词匹配（CloudLyricProvider）
 *
 * 所有歌词统一由星流原生「胶囊歌词」呈现，星河自身不含任何开关或渲染逻辑。
 */
class HookEntry : XposedModule() {

    private val logger = ModuleLogger(this)
    private val lyricInstalled = AtomicBoolean(false)
    private val cloudInstalled = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        logger.info(
            "XingHe loaded: process=${param.processName}, " +
                "framework=$frameworkName $frameworkVersion ($frameworkVersionCode), api=$apiVersion"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        when {
            packageName == Constants.PLAYER_PACKAGE_NAME -> {
                if (!param.isFirstPackage) return
                if (param.applicationInfo.processName != packageName) return
                if (!lyricInstalled.compareAndSet(false, true)) return
                logger.info("Package ready: $packageName, classLoader=${param.classLoader}")
                try {
                    XingHeLyricProvider(this, logger, param.classLoader).installHooks()
                } catch (throwable: Throwable) {
                    logger.error("Failed to install lyric provider hooks", throwable)
                }
            }
            Constants.CLOUD_PLAYER_PACKAGES.contains(packageName) -> {
                if (!param.isFirstPackage) return
                if (!cloudInstalled.compareAndSet(false, true)) return
                logger.info("Package ready: $packageName (cloud lyric), classLoader=${param.classLoader}")
                try {
                    CloudLyricProvider(this, logger, param.classLoader, packageName).installHooks()
                } catch (throwable: Throwable) {
                    logger.error("Failed to install cloud lyric hooks", throwable)
                }
            }
            else -> {
                // 其他包不处理
            }
        }
    }
}
