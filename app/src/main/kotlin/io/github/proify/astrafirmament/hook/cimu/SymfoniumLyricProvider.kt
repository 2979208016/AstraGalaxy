package io.github.proify.astrafirmament.hook.cimu

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.Executors

/**
 * Symfonium（宿主包名 app.symfonik.music.player）歌词提供者。
 *
 * 依据（词幕 symfonium provider 反编译结论）：
 *  1. 本地曲目信号：`android.net.Uri#parse(String)`。宿主播放本地文件时会拿到
 *     `content://media/external/audio/...` 形式的 MediaStore Uri；词幕就在这个
 *     after hook 里认出「当前本地曲目」（与上一次不同才处理）。本 provider 额外
 *     兼容 Android 10+ 常见的 `content://media/external_primary/audio/...`。
 *  2. 元数据：框架 android.media.session.MediaSession#setMetadata，
 *     键位 android.media.metadata.TITLE / ARTIST / DURATION。
 *  3. 歌词：与 Gramophone 一样来自音频文件内嵌标签（词幕用 com.kyant.taglib
 *     读属性表、匹配 `(?i)\b(LYRICS)\b`）；本模块改用 [EmbeddedLyric.read] 自己
 *     解析 ID3v2 USLT/TXXX、FLAC/Ogg 注释、MP4 ©lyr、APEv2，再
 *     [EmbeddedLyric.toLines] 解析（同时间戳的偶数行按原文+译文配对）。
 *     读取放在单线程池，避免阻塞 UI；结果按 Uri 缓存。
 *  4. 进度：MediaSession#setPlaybackState + [PositionTicker]。
 *  5. 同一首歌只在内容变化时 setSong（宿主 TITLE 可能反复上报）。
 *
 * 只读本地文件标签与宿主已拿到的数据，不联网、不修改宿主行为。
 *
 * 依据是词幕反编译结论，宿主版本未上机验证；类/方法缺失只告警并跳过，不影响宿主。
 */
internal class SymfoniumLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-symfonium-lyric").apply { isDaemon = true }
    }
    private val ticker = PositionTicker { ensureProvider() }

    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null
    @Volatile private var currentUri: String? = null
    @Volatile private var currentTitle: String? = null
    @Volatile private var currentArtist: String? = null
    @Volatile private var currentDuration = 0L
    @Volatile private var pendingLines: List<RichLyricLine>? = null
    @Volatile private var generation = 0
    @Volatile private var lastPushed: String? = null
    @Volatile private var enabledCached = true
    @Volatile private var enabledCheckedAt = 0L
    @Volatile private var lastStateAt = 0L

    private val cache = LinkedHashMap<String, List<RichLyricLine>>()

    fun installHooks() {
        hookApplicationLifecycle()
        hookMetadata()
        hookPlaybackState()
        hookUriParse()
        logger.info("Symfonium 歌词管线已装：$hostPackage / $processName")
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
            logger.info("Symfonium provider 注册成功（$processName）")
            created
        } catch (t: Throwable) {
            logger.error("Symfonium provider 注册失败", t)
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

    private fun hookMetadata() {
        try {
            val method = MediaSession::class.java.getDeclaredMethod(
                "setMetadata", MediaMetadata::class.java
            )
            installProtectiveAfterHook(method, "MediaSession.setMetadata") { chain, _ ->
                (chain.args.getOrNull(0) as? MediaMetadata)?.let { applyMetadata(it) }
            }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: MediaSession.setMetadata", t)
        }
    }

    private fun hookPlaybackState() {
        try {
            val method = MediaSession::class.java.getDeclaredMethod(
                "setPlaybackState", PlaybackState::class.java
            )
            installProtectiveAfterHook(method, "MediaSession.setPlaybackState") { chain, _ ->
                lastStateAt = SystemClock.elapsedRealtime()
                ticker.onPlaybackState(chain.args.getOrNull(0) as? PlaybackState)
            }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: MediaSession.setPlaybackState", t)
        }
    }

    /** Uri.parse 调用极频繁：前缀不过滤就直接返回，不做任何重活 */
    private fun hookUriParse() {
        try {
            val method = Uri::class.java.getDeclaredMethod("parse", String::class.java)
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val raw = chain.args.getOrNull(0) as? String
                    if (raw != null && isMediaUri(raw)) {
                        runCatching { onMediaUri(raw, result as? Uri) }
                            .onFailure { logger.error("Symfonium 处理媒体 Uri 失败", it) }
                    }
                    result
                }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: Uri.parse", t)
        }
    }

    // ================= 宿主数据 =================

    private fun isMediaUri(raw: String): Boolean {
        for (prefix in MEDIA_URI_PREFIXES) {
            if (raw.startsWith(prefix)) return true
        }
        return false
    }

    private fun applyMetadata(md: MediaMetadata) {
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val switched = title != null && currentTitle != null && title != currentTitle
        currentTitle = title
        currentArtist = artist
        currentDuration = duration
        if (switched) {
            // 换歌：上一首的歌词作废，先推无词占位（本地 Uri 解析到后再补）
            pendingLines = null
            lastPushed = null
            currentUri = null
            generation++
            ticker.reset(duration)
            pushSong(emptyList())
            return
        }
        pushSong(pendingLines ?: emptyList())
    }

    private fun onMediaUri(raw: String, uri: Uri?) {
        if (uri == null || raw == currentUri) return
        if (!lyricEnabledCached()) return
        // 启动时曲库扫描也会走 Uri.parse；没有元数据、也没见过播放态时不去读文件标签
        val playbackSeen = currentTitle != null ||
            (lastStateAt > 0L && SystemClock.elapsedRealtime() - lastStateAt < 60_000L)
        if (!playbackSeen) return
        currentUri = raw
        pendingLines = null
        lastPushed = null
        generation++
        ticker.reset(currentDuration)
        requestLyric(raw, uri, currentDuration)
    }

    private fun requestLyric(uriString: String, uri: Uri, duration: Long) {
        cached(uriString)?.let {
            pendingLines = it
            mainHandler.post { pushSong(it) }
            return
        }
        val seq = generation
        ioExecutor.execute {
            val ctx = application
            val text = if (ctx == null) null else runCatching { EmbeddedLyric.read(ctx, uri) }.getOrNull()
            val lines = if (text.isNullOrBlank()) emptyList() else EmbeddedLyric.toLines(text, duration)
            cachePut(uriString, lines)
            mainHandler.post {
                if (seq != generation || currentUri != uriString) return@post
                pendingLines = lines
                pushSong(lines)
            }
        }
    }

    private fun pushSong(lines: List<RichLyricLine>) {
        val p = ensureProvider() ?: return
        val name = currentTitle ?: return
        val id = currentUri ?: "symfonium|$name|$currentArtist"
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
        logger.debug("Symfonium 推歌词：${lines.size} 行 ok=$ok")
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

    // ================= 通用 =================

    /** Uri.parse 可能被曲库批量调用，开关状态做 3 秒缓存，避免逐条 IPC */
    private fun lyricEnabledCached(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - enabledCheckedAt < 3_000L) return enabledCached
        enabledCached = isLyricEnabled()
        enabledCheckedAt = now
        return enabledCached
    }

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

        /** MediaStore 本地音频 Uri（external_primary 是 Android 10+ 的叫法） */
        val MEDIA_URI_PREFIXES = arrayOf(
            "content://media/external/audio/",
            "content://media/external_primary/audio/"
        )
    }
}
