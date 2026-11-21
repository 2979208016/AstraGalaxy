package io.github.proify.astrafirmament.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.proify.astrafirmament.Module
import io.github.proify.astrafirmament.hook.LinkHook
import io.github.proify.astrafirmament.lyric.Constants
import io.github.proify.astrafirmament.hook.core.HookCrashLog
import io.github.proify.astrafirmament.hook.cimu.AppleMusicLyricProvider
import io.github.proify.astrafirmament.hook.cimu.GramophoneLyricProvider
import io.github.proify.astrafirmament.hook.cimu.KuwoLyricProvider
import io.github.proify.astrafirmament.hook.cimu.MusicFreeLyricProvider
import io.github.proify.astrafirmament.hook.cimu.PowerAmpLyricProvider
import io.github.proify.astrafirmament.hook.cimu.QQMusicHdLyricProvider
import io.github.proify.astrafirmament.hook.cimu.SaltPlayerLyricProvider
import io.github.proify.astrafirmament.hook.cimu.SpotifyLyricProvider
import io.github.proify.astrafirmament.hook.cimu.SymfoniumLyricProvider
import io.github.proify.astrafirmament.hook.cimu.LocalLyricProvider
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import io.github.proify.astrafirmament.hook.cimu.XingHeLyricProvider
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import io.github.proify.astrafirmament.hook.core.HookRegistry


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

    @Volatile private var linkInstalled = false

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        scheduleLinkInstall("boot", param.classLoader)
    }

    /**
     * 补挂通道：LSPosed 对「更新替换过的模块」可能残留旧 APK 路径，重启后
     * 跳过 onSystemServerStarting。onPackageReady("android") 与之独立触发，
     * 在这里做第二次补挂（幂等）。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (isSystemFramework(param.packageName) &&
            param.packageName == "android" &&
            !linkInstalled
        ) {
            scheduleLinkInstall("package-ready", param.classLoader)
        }
        // 智能文件增强路径：DownloadProvider 跑在 com.android.providers.downloads
        // 进程。scope 勾了才生效；system 侧还有「安装意图旁听 + MediaStore 兜底」
        // 两条主路径（LinkHook.install() 里挂），不依赖本分支。
        if (param.packageName == "com.android.providers.downloads" && !fileHookInstalled) {
            installFileHook(param.classLoader)
        }
        handlePlayerPackage(param)
    }

    @Volatile private var fileHookInstalled = false

    /** 智能文件增强路径：DownloadProvider.update（下载完成状态点，只投广播出卡） */
    private fun installFileHook(classLoader: ClassLoader) {
        if (!fileHookInstalledGate.compareAndSet(false, true)) return
        Thread({
            runCatching {
                val hook = LinkHook(module = this, classLoader = classLoader)
                val ok = hook.installFileWatcher()
                fileHookInstalled = ok
                logger.info("智能文件增强路径(ok=$ok, in downloads provider)")
            }.onFailure {
                logger.error("file hook failed: " + it.message)
            }
        }, "xinghe-file-install").apply { isDaemon = true }.start()
    }

    private val fileHookInstalledGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 智能上岛 hook 的装机延迟：只用于避开开机早期竞态，不参与检测链路。 */
    private val LINK_INSTALL_DELAY_MS = 3_000L

    private fun scheduleLinkInstall(source: String, classLoader: ClassLoader) {
        if (!linkInstalledGate.compareAndSet(false, true)) return
        // 8.0：15s 固定延迟会让「开机后第一次检测」白等十几秒。保留一个短延迟
        // 避开开机早期竞态窗口（历史上半安装 APK 解析曾拖崩 system_server），
        // 但压到 3s；挂载失败只影响智能上岛，绝不影响系统。
        logger.info("link install scheduled from $source")
        Thread({
            Thread.sleep(LINK_INSTALL_DELAY_MS)
            runCatching {
                val hook = LinkHook(module = this@HookEntry, classLoader = classLoader)
                val ok = hook.install()
                linkInstalled = ok
                if (ok) hook.registerAllReceivers()
                logger.info("挂载结果 ok=$ok (from $source)")
            }.onFailure {
                logger.error("link hook failed: " + it.message)
                logger.error("挂载失败(from $source)", it)
            }
        }, "xinghe-link-install").apply {
            isDaemon = true
            start()
        }
    }

    private fun handlePlayerPackage(param: PackageReadyParam) {
        val packageName = param.packageName
        if (packageName == Module.PACKAGE_NAME) return

        if (isSystemFramework(packageName)) return
        if (!isSupportedPlayer(packageName)) return

        if (!isEnabled()) {
            logger.info("Module disabled in settings, skip " + packageName)
            return
        }

        val installKey = packageName + "|" + currentProcessName()
        if (!installedPackages.add(installKey)) return
        logger.info("Package ready: " + packageName + ", process=" + currentProcessName())
        // 统一注册表（hook/core）：独立 try-catch、失败不互相阻断；
        // 进程级失败仍进 HookCrashLog 留证
        val registry = HookRegistry(this, "AstraFirmament-Registry")
        when (packageName) {
            Constants.PLAYER_PACKAGE_NAME -> registry.installFeature("lyric:xinghe") {
                XingHeLyricProvider(this, logger, param.classLoader).installHooks()
            }
            "com.spotify.music" -> registry.installFeature("lyric:spotify") {
                SpotifyLyricProvider(
                    module = this,
                    logger = logger,
                    classLoader = param.classLoader,
                    hostPackage = packageName,
                    processName = currentProcessName()
                ).installHooks()
            }
            Constants.KUWO_PACKAGE -> registry.installFeature("lyric:kuwo") {
                KuwoLyricProvider(
                    module = this,
                    logger = logger,
                    classLoader = param.classLoader,
                    hostPackage = packageName,
                    processName = currentProcessName()
                ).installHooks()
            }
            else -> if (packageName in Constants.LOCAL_PLAYER_PACKAGES) {
                registry.installFeature("lyric:local") {
                    LocalLyricProvider(
                        module = this,
                        logger = logger,
                        classLoader = param.classLoader,
                        hostPackage = packageName,
                        processName = currentProcessName(),
                        recipe = Constants.recipeOf(packageName)
                    ).installHooks()
                }
            } else {
                // 其余独立适配的宿主：每个包一套 hook，见 installExtended
                registry.installFeature("lyric:$packageName") {
                    installExtended(packageName, param.classLoader)
                }
            }
        }
        registry.failedNames.forEach { name ->
            logger.error("Failed to install hooks for $name (pkg=$packageName)")
            // 注册表把失败异常记在 registry 里；HookCrashLog 要非空 Throwable，
            // 拿不到就报"注册失败"语义的通用异常，不留空
            val t = registry.failureOf(name) ?: IllegalStateException("feature install failed: $name")
            HookCrashLog.record("installFeature:$name", t)
        }
        if (registry.failedNames.isEmpty()) {
            logger.info("Hooks installed for $packageName: ${registry.registeredNames}")
        }
    }

    private fun isEnabled(): Boolean = runCatching {
        getRemotePreferences(ModulePrefs.NAME)
            .getBoolean(ModulePrefs.KEY_ENABLED, true)
    }.getOrDefault(true)

    /** 受支持的播放器宿主：本模块只为这些包装 hook，作用域外的包进不来 */
    private fun isSupportedPlayer(packageName: String): Boolean =
        packageName == Constants.PLAYER_PACKAGE_NAME ||
            packageName == Constants.KUWO_PACKAGE ||
            packageName == Constants.SPOTIFY_PACKAGE ||
            packageName in Constants.EXTENDED_PLAYER_PACKAGES ||
            packageName in Constants.LOCAL_PLAYER_PACKAGES

    /**
     * 独立适配宿主的 hook 分派。
     *
     * 这些宿主没有可复用的本地缓存配方（宿主内部对象 / 内嵌标签 / 自带缓存 / 在线源），
     * 每个包一个 provider 类；新增宿主时只在这里补一行。
     */
    private fun installExtended(packageName: String, loader: ClassLoader) {
        val process = currentProcessName()
        when (packageName) {
            Constants.APPLE_MUSIC_PACKAGE ->
                AppleMusicLyricProvider(this, logger, loader, packageName, process).installHooks()
            Constants.POWERAMP_PACKAGE ->
                PowerAmpLyricProvider(this, logger, loader, packageName, process).installHooks()
            Constants.QQ_MUSIC_HD_PACKAGE ->
                QQMusicHdLyricProvider(this, logger, loader, packageName, process).installHooks()
            Constants.SALT_PLAYER_PACKAGE ->
                SaltPlayerLyricProvider(this, logger, loader, packageName, process).installHooks()
            Constants.MUSICFREE_PACKAGE ->
                MusicFreeLyricProvider(this, logger, loader, packageName, process).installHooks()
            Constants.GRAMOPHONE_PACKAGE ->
                GramophoneLyricProvider(this, logger, loader, packageName, process).installHooks()
            Constants.SYMFONIUM_PACKAGE ->
                SymfoniumLyricProvider(this, logger, loader, packageName, process).installHooks()
            else -> logger.info("No extended lyric provider for $packageName")
        }
    }

    private fun isSystemFramework(packageName: String): Boolean =
        packageName == "android" || packageName == "system" || packageName == "system_server"

    private fun currentProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trimEnd(' ')
    }.getOrDefault("")

    private fun logSys(msg: String) {
        runCatching { log(Log.INFO, "AstraFirmament", msg) }
    }

    private companion object {
        val loadedLoggedGate = AtomicBoolean(false)
        val linkInstalledGate = AtomicBoolean(false)
        val installedPackages = ConcurrentHashMap.newKeySet<String>()
    }
}
