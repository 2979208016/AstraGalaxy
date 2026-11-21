package io.github.proify.astrafirmament.hook.cimu

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import io.github.proify.astrafirmament.lyric.Constants
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.lang.reflect.Executable

/**
 * MusicFree（宿主包名 fun.upup.musicfree）歌词提供者。
 *
 * 依据（词幕 musicfree provider 反编译结论）：
 *  1. 歌词：`fun.upup.musicfree.lyricUtil.LyricView#setText(String)`，宿主每次只把
 *     **当前一句**（开启译文时是「原文\n译文」两行）塞进这个自定义 View；
 *     该类在播放页首次渲染时才加载，所以 Class.forName 失败只告警不中断。
 *  2. 播放状态/进度：框架 android.media.session.MediaSession#setPlaybackState。
 *     词幕只挂这一个；本 provider 额外挂 #setMetadata 拿 TITLE/ARTIST/DURATION/
 *     MEDIA_ID，用来组 Song 快照（词幕文本模式不需要，我们需要）。
 *  3. 词幕在 onHook 里把 AudioManager#isBluetoothA2dpOn 与
 *     BluetoothAdapter#isEnabled 强改为 true，让宿主以为连着蓝牙而常驻通知栏
 *     歌词；本 provider 只在本进程内做同样的事，且受模块歌词开关控制。
 *  4. 没有整份时间轴：交给 [TextLyricBridge] 按播放进度累积成行。
 *
 * 只读宿主已拿到的歌词文本，不联网、不改宿主播放行为。
 *
 * 依据是词幕反编译结论，宿主版本未上机验证；类/方法缺失只告警并跳过，不影响宿主。
 */
internal class MusicFreeLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null

    private val bridge = TextLyricBridge("musicfree", logger) { ensureProvider() }

    fun installHooks() {
        hookApplicationLifecycle()
        hookMediaSession()
        hookLyricView()
        installBluetoothBoost()
        logger.info("MusicFree 歌词管线已装：$hostPackage / $processName")
    }

    // ================= Provider =================

    private fun ensureProvider(): LyriconProvider? {
        provider?.let { return it }
        val ctx = application ?: currentApplication() ?: return null
        return try {
            val logo = runCatching { ProviderLogo.fromSvg(Constants.ICON) }.getOrNull()
            val created = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = hostPackage,
                logo = logo,
                processName = processName
            ).apply {
                player.setDisplayTranslation(true)
                player.setDisplayRoma(false)
                register()
            }
            provider = created
            logger.info("MusicFree provider 注册成功（$processName）")
            created
        } catch (t: Throwable) {
            logger.error("MusicFree provider 注册失败", t)
            null
        }
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) {
        null
    }

    // ================= Hooks =================

    private fun hookApplicationLifecycle() {
        try {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
                (chain.args.getOrNull(0) as? Application)?.let {
                    application = it
                    ensureProvider()
                }
            }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: Application lifecycle", t)
        }
    }

    private fun hookMediaSession() {
        try {
            val metadata = MediaSession::class.java.getDeclaredMethod(
                "setMetadata", MediaMetadata::class.java
            )
            installProtectiveAfterHook(metadata, "MediaSession.setMetadata") { chain, _ ->
                bridge.onMetadata(chain.args.getOrNull(0) as? MediaMetadata)
            }
            val state = MediaSession::class.java.getDeclaredMethod(
                "setPlaybackState", PlaybackState::class.java
            )
            installProtectiveAfterHook(state, "MediaSession.setPlaybackState") { chain, _ ->
                bridge.onPlaybackState(chain.args.getOrNull(0) as? PlaybackState)
            }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: MediaSession", t)
        }
    }

    /**
     * 歌词 View 可能在播放页才加载：此处在 install 时找类（找不到就告警），
     * 同时兜底 setText(CharSequence) 以兼容不同 RN 宿主版本。
     */
    private fun hookLyricView() {
        val clazz = try {
            Class.forName("fun.upup.musicfree.lyricUtil.LyricView", false, classLoader)
        } catch (t: Throwable) {
            logger.warn("MusicFree 未找到歌词 View（宿主版本可能不匹配）：${t.message}")
            return
        }
        for (argType in listOf(String::class.java, CharSequence::class.java)) {
            val method = runCatching { clazz.getDeclaredMethod("setText", argType) }.getOrNull()
                ?: continue
            installProtectiveAfterHook(method, "LyricView.setText(${argType.simpleName})") { chain, _ ->
                bridge.onLine(chain.args.getOrNull(0)?.toString())
            }
        }
    }

    /** 让宿主以为蓝牙在连：词幕用同样的招数让歌词常驻通知栏/锁屏 */
    private fun installBluetoothBoost() {
        val targets = listOf(
            "android.media.AudioManager" to "isBluetoothA2dpOn",
            "android.bluetooth.BluetoothAdapter" to "isEnabled"
        )
        for ((className, methodName) in targets) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.getDeclaredMethod(methodName)
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (isLyricEnabled()) true else chain.proceed()
                    }
            } catch (t: Throwable) {
                logger.warn("MusicFree 蓝牙伪装失败：$className#$methodName（${t.message}）")
            }
        }
    }

    // ================= 通用 =================

    private fun isLyricEnabled(): Boolean = runCatching {
        val prefs = module.getRemotePreferences(ModulePrefs.NAME)
        prefs.getBoolean(ModulePrefs.KEY_ENABLED, true) &&
            prefs.getBoolean(ModulePrefs.KEY_LYRIC, true)
    }.getOrDefault(true)

    private fun installProtectiveAfterHook(
        executable: Executable, description: String,
        callback: (XposedInterface.Chain, Any?) -> Unit
    ) {
        try {
            module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!isLyricEnabled()) return@intercept result
                    try {
                        callback(chain, result)
                    } catch (t: Throwable) {
                        logger.error("After-hook failed: $description", t)
                    }
                    result
                }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: $description", t)
        }
    }
}
