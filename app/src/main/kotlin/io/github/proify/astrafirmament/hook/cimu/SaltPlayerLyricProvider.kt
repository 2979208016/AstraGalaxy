package io.github.proify.astrafirmament.hook.cimu

import android.app.Application
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import io.github.proify.astrafirmament.lyric.Constants
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.lang.reflect.Executable
import java.lang.reflect.Field

/**
 * Salt Player（宿主包名 com.salt.music）歌词提供者。
 *
 * 依据（词幕 saltprovider/SaltPlayer + MeizuProvider 反编译结论）：
 *  1. Salt Player 只把**当前一句**歌词塞进 Meizu 通知栏 tickerText，没有整份
 *     时间轴；MeizuProvider 的做法是：
 *       - 读 PackageInfo.getLongVersionCode()，>= 2026081001 的宿主自己带歌词
 *         服务，直接放弃注入（避免双推）；
 *       - 否则把 Build.BRAND/MANUFACTURER/DEVICE/DISPLAY/PRODUCT/MODEL 伪装成
 *         魅族 16th Plus，并让 SystemProperties.get("ro.product.*") 等返回魅族值，
 *         使 Salt 走「Flyme 状态栏歌词」分支；
 *       - Meizu 的 Notification.FLAG_ALWAYS_SHOW_TICKER(0x01000000) /
 *         FLAG_ONLY_UPDATE_TICKER(0x02000000) 在非魅族 Build 上不存在，需让
 *         Class#getField/getDeclaredField 返回持有这两个值的静态 int 字段；
 *       - hook NotificationManager.notify(String,int,Notification)：flags 与
 *         FLAG_MEIZU_TICKER(0x03000000) 有交集时，tickerText 就是当前歌词句。
 *  2. 元数据/进度走框架 android.media.session.MediaSession#setMetadata /
 *     #setPlaybackState（字段 android.media.metadata.TITLE / ARTIST /
 *     DURATION / MEDIA_ID）。
 *  3. 时间轴由 [TextLyricBridge] 按播放进度累积补出；ticker 里用换行分隔的
 *     第二行按译文处理。通知 extras 只在 MedataSession 还没给歌名时兜底。
 *
 * 只读宿主已拿到的数据，不联网、不改宿主播放行为（除上述版本/机型伪装）。
 *
 * 依据是词幕反编译结论，宿主版本未上机验证；类/方法缺失只告警并跳过，不影响宿主。
 */
internal class SaltPlayerLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null

    private val bridge = TextLyricBridge("salt", logger) { ensureProvider() }

    fun installHooks() {
        if (nativeLyricSupported()) {
            logger.info("Salt Player $hostPackage 已自带歌词服务，跳过注入")
            return
        }
        mockFlymeBuild()
        hookSystemProperties()
        hookClassFieldFlags()
        hookApplicationLifecycle()
        hookMediaSession()
        hookNotification()
        logger.info("Salt Player 歌词管线已装：$hostPackage / $processName")
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
            logger.info("Salt provider 注册成功（$processName）")
            created
        } catch (t: Throwable) {
            logger.error("Salt provider 注册失败", t)
            null
        }
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) {
        null
    }

    // ================= 版本 / 机型伪装 =================

    /** 宿主 2026081001 起自带歌词服务，再挂会与宿主原生日志互抢（词幕同款阈值） */
    private fun nativeLyricSupported(): Boolean {
        val ctx = currentApplication() ?: return false
        return try {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageInfo(hostPackage, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            code >= NATIVE_LYRIC_MIN_VERSION
        } catch (_: Throwable) {
            false
        }
    }

    private fun mockFlymeBuild() {
        if (Build.BRAND.equals("meizu", true)) return
        val values = mapOf(
            "BRAND" to "meizu",
            "MANUFACTURER" to "Meizu",
            "DEVICE" to "m1892",
            "DISPLAY" to "Flyme",
            "PRODUCT" to "meizu_16thPlus_CN",
            "MODEL" to "meizu 16th Plus"
        )
        for ((name, value) in values) {
            try {
                val field = Build::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(null, value)
            } catch (t: Throwable) {
                logger.warn("Salt 伪装 Flyme 失败：Build.$name（${t.message}）")
            }
        }
    }

    private fun hookSystemProperties() {
        val props = mapOf(
            "ro.product.model" to "meizu 16th Plus",
            "ro.product.brand" to "meizu",
            "ro.product.manufacturer" to "Meizu",
            "ro.product.device" to "m1892",
            "ro.build.display.id" to "Flyme",
            "ro.build.product" to "meizu_16thPlus_CN",
            "ro.meizu.product.model" to "m1892"
        )
        val clazz = try {
            Class.forName("android.os.SystemProperties", false, classLoader)
        } catch (_: Throwable) {
            null
        } ?: return
        for (argTypes in listOf(arrayOf(String::class.java), arrayOf(String::class.java, String::class.java))) {
            try {
                val method = clazz.getDeclaredMethod("get", *argTypes)
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val key = chain.args.getOrNull(0) as? String
                        props[key] ?: chain.proceed()
                    }
            } catch (t: Throwable) {
                logger.warn("Salt 伪装 SystemProperties 失败：${argTypes.size} 参（${t.message}）")
            }
        }
    }

    /** 让 Class#getField("FLAG_ALWAYS_SHOW_TICKER") 拿到持有 0x01000000 的静态字段 */
    private fun hookClassFieldFlags() {
        for (name in listOf("getField", "getDeclaredField")) {
            try {
                val method = Class::class.java.getDeclaredMethod(name, String::class.java)
                installBeforeHook(method, "Class.$name") { chain ->
                    val fieldName = chain.args.getOrNull(0) as? String
                    if (fieldName != null) MEIZU_FLAG_FIELDS[fieldName] else null
                }
            } catch (t: Throwable) {
                logger.error("Unable to install hook: Class.$name", t)
            }
        }
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

    private fun hookNotification() {
        try {
            val method = NotificationManager::class.java.getDeclaredMethod(
                "notify", String::class.java, Int::class.javaPrimitiveType!!, Notification::class.java
            )
            installProtectiveAfterHook(method, "NotificationManager.notify") { chain, _ ->
                val notification = chain.args.getOrNull(2) as? Notification
                    ?: return@installProtectiveAfterHook
                if ((notification.flags and FLAG_MEIZU_TICKER) == 0) return@installProtectiveAfterHook
                applyNotificationFallback(notification)
                bridge.onLine(notification.tickerText?.toString())
            }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: NotificationManager.notify", t)
        }
    }

    /** 有的版本 MediaSession 不给歌名，用通知 extras 的标题兜底（只补一次） */
    private fun applyNotificationFallback(notification: Notification) {
        if (bridge.hasSong()) return
        val raw = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val title = raw?.takeIf { it.isNotEmpty() && it != "Salt Player" } ?: return
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .build()
        bridge.onMetadata(metadata)
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

    /** before 钩子返回非 null 即替换返回值（用于查询类方法，避免走原实现） */
    private fun installBeforeHook(
        executable: Executable, description: String,
        before: (XposedInterface.Chain) -> Any?
    ) {
        try {
            module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val replaced = runCatching { before(chain) }.getOrNull()
                    replaced ?: chain.proceed()
                }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: $description", t)
        }
    }

    private companion object {
        /** 宿主该版本起自带歌词服务（词幕 NATIVE_LYRIC_MIN_VERSION） */
        const val NATIVE_LYRIC_MIN_VERSION = 2026081001L
        const val FLAG_MEIZU_TICKER = 0x03000000

        /** 谎报字段：Class#getField 拿到的静态 int 字段，值即魅族通知 flags */
        val MEIZU_FLAG_FIELDS: Map<String, Field> = mapOf(
            "FLAG_ALWAYS_SHOW_TICKER" to MeizuFlagHolder::class.java.getDeclaredField("FLAG_ALWAYS_SHOW_TICKER"),
            "FLAG_ONLY_UPDATE_TICKER" to MeizuFlagHolder::class.java.getDeclaredField("FLAG_ONLY_UPDATE_TICKER")
        )
    }
}

/** Meizu Notification flags 的替身（非魅族 Build 上没有这两个字段） */
private object MeizuFlagHolder {
    @JvmField val FLAG_ALWAYS_SHOW_TICKER: Int = 0x01000000
    @JvmField val FLAG_ONLY_UPDATE_TICKER: Int = 0x02000000
}
