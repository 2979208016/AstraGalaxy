package io.github.proify.astrafirmament.hook.cimu

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import io.github.proify.astrafirmament.lyric.Constants
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * Gramophone（宿主包名 org.akanework.gramophone）歌词提供者。
 *
 * 依据（词幕 gramophone provider 反编译结论）：
 *  1. 切歌信号：`org.akanework.gramophone.logic.GramophonePlaybackService
 *     #onTracksChanged(androidx.media3.common.Tracks)`（method hook 的 this 就是
 *     Service）。从实例字段 `controller`（media3 MediaController）调
 *     `getCurrentMediaItem()`，再读 MediaItem 的公开字段：
 *       - `mediaId`（String）
 *       - `localConfiguration.uri`（android.net.Uri，本地文件）
 *       - `mediaMetadata.title / artist / durationMs`
 *     字段缺失/为空表示当前没有本地曲目，直接跳过。
 *  2. 歌词：Gramophone 不联网取词，歌词来自音频文件内嵌标签。词幕用
 *     com.kyant.taglib.TagLib.getMetadata(fd,false) 读属性表并匹配键名正则
 *     `(?i)\b(LYRICS)\b`（USLT/LYRICS/©lyr 都命中），取第一个 String。
 *     本模块没有 TagLib 依赖，改为 [EmbeddedLyric.read] 自己解析
 *     ID3v2 USLT/TXXX、FLAC/Ogg 注释、MP4 ©lyr、APEv2，再用 [EmbeddedLyric.toLines]
 *     解析成行（同时间戳的偶数行按原文+译文配对）。
 *  3. 进度：框架 android.media.session.MediaSession#setPlaybackState，交给
 *     [PositionTicker] 外推。
 *
 * 只读本地文件标签与宿主已拿到的数据，不联网、不修改宿主行为。
 *
 * 依据是词幕反编译结论，宿主版本未上机验证；类/方法缺失只告警并跳过，不影响宿主。
 */
internal class GramophoneLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-gramophone-lyric").apply { isDaemon = true }
    }
    private val ticker = PositionTicker { ensureProvider() }

    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null
    @Volatile private var currentUri: String? = null
    @Volatile private var currentId: String? = null
    @Volatile private var currentTitle: String? = null
    @Volatile private var currentArtist: String? = null
    @Volatile private var currentDuration = 0L
    @Volatile private var generation = 0
    @Volatile private var pendingUri: String? = null
    @Volatile private var lastPushed: String? = null

    /** uri → 解析结果；空列表也缓存（表示该文件没有内嵌歌词） */
    private val cache = LinkedHashMap<String, List<RichLyricLine>>()

    fun installHooks() {
        hookApplicationLifecycle()
        hookTracks()
        hookPlaybackState()
        logger.info("Gramophone 歌词管线已装：$hostPackage / $processName")
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
            logger.info("Gramophone provider 注册成功（$processName）")
            created
        } catch (t: Throwable) {
            logger.error("Gramophone provider 注册失败", t)
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

    private fun hookTracks() {
        val serviceClass = loadClass("org.akanework.gramophone.logic.GramophonePlaybackService")
        val tracksClass = loadClass("androidx.media3.common.Tracks")
        if (serviceClass == null || tracksClass == null) {
            logger.warn("Gramophone 宿主类缺失（media3/版本不匹配），跳过歌词注入")
            return
        }
        val method = findMethod(serviceClass, "onTracksChanged", tracksClass)
        if (method == null) {
            logger.warn("Gramophone 未找到 onTracksChanged（宿主版本可能不匹配）")
            return
        }
        installProtectiveAfterHook(method, "GramophonePlaybackService.onTracksChanged") { chain, _ ->
            chain.thisObject?.let { self ->
                runCatching { onTracksChanged(self) }
                    .onFailure { logger.error("Gramophone 解析 Tracks 失败", it) }
            }
        }
    }

    private fun hookPlaybackState() {
        try {
            val method = MediaSession::class.java.getDeclaredMethod(
                "setPlaybackState", PlaybackState::class.java
            )
            installProtectiveAfterHook(method, "MediaSession.setPlaybackState") { chain, _ ->
                ticker.onPlaybackState(chain.args.getOrNull(0) as? PlaybackState)
            }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: MediaSession.setPlaybackState", t)
        }
    }

    // ================= 宿主数据 =================

    private fun onTracksChanged(service: Any) {
        val controller = readField(service, "controller") ?: return
        val item = invokeNoArg(controller, "getCurrentMediaItem") ?: return
        val uri = extractUri(item)
        val meta = extractMeta(item)
        val title = meta.first ?: uri?.lastPathSegment
        currentTitle = title
        currentArtist = meta.second
        currentDuration = meta.third
        currentId = readField(item, "mediaId") as? String
        val uriString = uri?.toString()
        val switched = uriString != currentUri
        currentUri = uriString
        if (switched) {
            ticker.reset(currentDuration)
            generation++
            pendingUri = null
            lastPushed = null
        }
        if (uri == null || uriString == null) {
            pushSong(emptyList())
        } else {
            requestLyric(uriString, uri, currentDuration)
        }
    }

    private fun extractUri(item: Any): Uri? {
        val local = readField(item, "localConfiguration") ?: return null
        return readField(local, "uri") as? Uri
    }

    /** title / artist / durationMs（media3 MediaMetadata 的公开字段） */
    private fun extractMeta(item: Any): Triple<String?, String?, Long> {
        val metadata = readField(item, "mediaMetadata") ?: return Triple(null, null, 0L)
        val title = readField(metadata, "title")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val artist = readField(metadata, "artist")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val duration = (readField(metadata, "durationMs") as? Long) ?: 0L
        return Triple(title, artist, duration)
    }

    private fun requestLyric(uriString: String, uri: Uri, duration: Long) {
        cached(uriString)?.let {
            pushSong(it)
            return
        }
        if (uriString == pendingUri) return
        pendingUri = uriString
        val seq = generation
        ioExecutor.execute {
            val ctx = application
            val text = if (ctx == null) null else runCatching { EmbeddedLyric.read(ctx, uri) }.getOrNull()
            val lines = if (text.isNullOrBlank()) emptyList() else EmbeddedLyric.toLines(text, duration)
            cachePut(uriString, lines)
            mainHandler.post {
                pendingUri = null
                if (seq != generation || currentUri != uriString) return@post
                pushSong(lines)
            }
        }
    }

    private fun pushSong(lines: List<RichLyricLine>) {
        val p = ensureProvider() ?: return
        val name = currentTitle ?: return
        val id = currentId ?: currentUri ?: return
        val key = "$id|${lines.size}|$name|${lines.lastOrNull()?.end}"
        if (key == lastPushed) return
        val song = Song().apply {
            this.id = id
            this.name = name
            this.artist = currentArtist
            this.duration = if (currentDuration > 0) currentDuration else (lines.lastOrNull()?.end ?: 0L)
            this.lyrics = if (lines.isEmpty()) emptyList() else lines
        }
        val ok = runCatching { p.player.setSong(song) }.getOrDefault(false)
        if (ok) lastPushed = key
        logger.debug("Gramophone 推歌词：${lines.size} 行 ok=$ok")
    }

    private fun cached(uri: String): List<RichLyricLine>? =
        synchronized(cache) { cache[uri] }

    private fun cachePut(uri: String, lines: List<RichLyricLine>) {
        synchronized(cache) {
            cache[uri] = lines
            while (cache.size > CACHE_MAX) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    // ================= 反射小工具 =================

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            runCatching { current.getDeclaredMethod(name, *paramTypes) }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun readField(target: Any, name: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(target) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = findMethod(target.javaClass, name) ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) }.getOrNull()
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

    private companion object {
        const val CACHE_MAX = 8
    }
}
