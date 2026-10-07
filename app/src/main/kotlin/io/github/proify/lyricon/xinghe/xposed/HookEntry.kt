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
import io.github.proify.lyricon.xinghe.lyric.KuwoLyricProvider
import io.github.proify.lyricon.xinghe.lyric.LocalLyricProvider
import io.github.proify.lyricon.xinghe.lyric.ModuleLogger
import io.github.proify.lyricon.xinghe.lyric.XingHeLyricProvider
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


class HookEntry : XposedModule() {

    private val logger = ModuleLogger(this)

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
        if (!linkInstalledGate.compareAndSet(false, true)) {
            logSys("link already installed, skip")
            return
        }
        runCatching {
            LinkHook(module = this, classLoader = param.classLoader).install()
        }.onFailure { logSys("link hook failed: " + it.message) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (packageName == Module.PACKAGE_NAME) return
        if (isSystemFramework(packageName)) return
        if (packageName != Constants.PLAYER_PACKAGE_NAME &&
            packageName != Constants.KUWO_PACKAGE &&
            packageName !in Constants.LOCAL_PLAYER_PACKAGES
        ) return

        if (!isEnabled()) {
            logger.info("Module disabled in settings, skip " + packageName)
            return
        }

        val installKey = packageName + "|" + currentProcessName()
        if (!installedPackages.add(installKey)) return
        logger.info("Package ready: " + packageName + ", process=" + currentProcessName())
        try {
            if (packageName == Constants.PLAYER_PACKAGE_NAME) {
                XingHeLyricProvider(this, logger, param.classLoader).installHooks()
                return
            }
            if (packageName == Constants.KUWO_PACKAGE) {
                KuwoLyricProvider(
                    module = this,
                    logger = logger,
                    classLoader = param.classLoader,
                    hostPackage = packageName,
                    processName = currentProcessName()
                ).installHooks()
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
            logger.error("Failed to install hooks for " + packageName, throwable)
            HookCrashLog.record("installHooks:" + packageName, throwable)
        }
    }

    private fun isEnabled(): Boolean = runCatching {
        getRemotePreferences(ModulePrefs.NAME)
            .getBoolean(ModulePrefs.KEY_ENABLED, true)
    }.getOrDefault(true)

    private fun isSystemFramework(packageName: String): Boolean =
        packageName == "android" || packageName == "system" || packageName == "system_server"

    private fun currentProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trimEnd(' ')
    }.getOrDefault("")

    private fun logSys(msg: String) {
        runCatching { log(Log.INFO, "AstraGalaxy", msg) }
    }

    private companion object {
        val loadedLoggedGate = AtomicBoolean(false)
        val linkInstalledGate = AtomicBoolean(false)
        val installedPackages = ConcurrentHashMap.newKeySet<String>()
    }
}
