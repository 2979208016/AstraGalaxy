package io.github.proify.lyricon.xinghe.xposed

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import java.lang.reflect.Executable
import kotlin.concurrent.thread

/**
 * 通用「本地歌词」提供者。
 *
 * 与旧版「在线匹配」不同，本实现完全不联网：
 *  1. 通过 MediaSession 只读取当前歌曲的 标题 / 歌手 / 时长 / mediaId；
 *  2. 在宿主 App 自己的本地歌词缓存目录里查找该歌曲的歌词文件；
 *  3. 解析（酷狗 KRC 解密、QQ QRC 解密、波点 LRCX、OPPO/网易云缓存）后按 LyricON 协议推给星流。
 *
 * 不同平台的缓存格式与匹配方式见 [Constants.LOCAL_RECIPES]。
 * 找不到本地歌词时不推送任何内容，绝不回落在线接口。
 */
internal class LocalLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val recipe: LocalRecipe
) {

    private val stateLock = Any()

    @Volatile
    private var provider: LyriconProvider? = null

    @Volatile
    private var application: Application? = null

    /** 当前歌曲签名（标题|歌手|时长|mediaId），变化才重新查找歌词 */
    @Volatile
    private var currentSongSignature: String? = null

    @Volatile
    private var metadataDurationMs: Long = 0L

    // ---------------- 进度锚点 ----------------

    @Volatile
    private var anchorPosition: Long = 0L

    @Volatile
    private var anchorRealtime: Long = 0L

    @Volatile
    private var isPlaying: Boolean = false

    @Volatile
    private var hasAnchor: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressTicker = object : Runnable {
        override fun run() {
            tickProgress()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun installHooks() {
        hookApplicationLifecycle()
        hookMediaSession()
        logger.info("Local lyric hooks installed for $hostPackage")
    }

    // ---------------- 应用生命周期 ----------------

    private fun hookApplicationLifecycle() {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java
        )
        installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
            val hostApplication = chain.args.getOrNull(0) as? Application
            if (hostApplication != null) onApplicationCreated(hostApplication)
        }
    }

    private fun onApplicationCreated(hostApplication: Application) {
        application = hostApplication
        logger.info("Application created: ${hostApplication.packageName}")
        try {
            setupProvider(hostApplication)
        } catch (throwable: Throwable) {
            logger.error("Provider initialization failed", throwable)
            return
        }
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    private fun setupProvider(hostApplication: Application) {
        val created = LyriconFactory.createProvider(
            context = hostApplication,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = hostApplication.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            player.setDisplayTranslation(true)
            player.setDisplayRoma(true)
            register()
        }
        provider = created
        logger.info("Local lyric provider registered, player=${hostApplication.packageName}")
    }

    // ---------------- MediaSession ----------------

    private fun hookMediaSession() {
        val metadataMethod = MediaSession::class.java.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java
        )
        installProtectiveAfterHook(metadataMethod, "MediaSession.setMetadata") { chain, _ ->
            onMetadataChanged(chain.args.getOrNull(0) as? MediaMetadata)
        }

        val playbackMethod = MediaSession::class.java.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java
        )
        installProtectiveAfterHook(playbackMethod, "MediaSession.setPlaybackState") { chain, _ ->
            onPlaybackStateChanged(chain.args.getOrNull(0) as? PlaybackState)
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() && it != "未知歌曲" }
            ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && it != "未知歌手" }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.takeIf { it.isNotBlank() }

        if (duration > 0) metadataDurationMs = duration

        val signature = "$title|$artist|$duration|$mediaId"
        synchronized(stateLock) {
            if (signature == currentSongSignature) return
            currentSongSignature = signature
        }

        // 切歌：重置进度锚点
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = true
        isPlaying = true

        provider?.let {
            runCatching { it.player.setPosition(0L) }
        }

        logger.info("Metadata changed: $title - $artist ($duration) id=$mediaId")
        loadLocalLyric(title, artist, duration, mediaId)
    }

    private fun onPlaybackStateChanged(state: PlaybackState?) {
        state ?: return
        val position = state.position
        if (position >= 0L) {
            anchorPosition = position
            anchorRealtime = SystemClock.elapsedRealtime()
            isPlaying = state.state == PlaybackState.STATE_PLAYING
            hasAnchor = true
        } else {
            isPlaying = state.state == PlaybackState.STATE_PLAYING
        }
        tickProgress()
    }

    // ---------------- 进度推算 ----------------

    private fun extrapolatedPosition(): Long {
        if (!hasAnchor) return 0L
        if (!isPlaying) return anchorPosition
        val elapsed = SystemClock.elapsedRealtime() - anchorRealtime
        val pos = anchorPosition + elapsed
        return if (metadataDurationMs > 0) pos.coerceAtMost(metadataDurationMs) else pos
    }

    private fun tickProgress() {
        val p = provider ?: return
        if (!hasAnchor) return
        runCatching {
            p.player.setPlaybackState(isPlaying)
            p.player.setPosition(extrapolatedPosition())
        }
    }

    // ---------------- 本地歌词查找 ----------------

    private fun loadLocalLyric(title: String, artist: String?, duration: Long, mediaId: String?) {
        thread(name = "xinghe-local-lyric", isDaemon = true) {
            try {
                val context = application ?: return@thread
                val lines = LocalLyricFinder.find(context, recipe, title, artist, duration, mediaId)
                if (lines.isNullOrEmpty()) {
                    logger.info("No local lyric for: $title - $artist")
                    return@thread
                }
                publish(title, artist, duration, lines)
                logger.info("Local lyric published: $title, ${lines.size} lines")
            } catch (t: Throwable) {
                logger.error("Local lyric lookup failed: ${t.message}", t)
            }
        }
    }

    private fun publish(title: String, artist: String?, duration: Long, lyrics: List<RichLyricLine>) {
        val p = provider ?: return
        val song = Song().apply {
            id = "local:${hostPackage}:$title|$artist"
            name = title
            this.artist = artist
            this.duration = duration
            this.lyrics = lyrics
        }
        runCatching { p.player.setSong(song) }
            .onFailure { logger.error("setSong failed", it) }
    }

    private fun installProtectiveAfterHook(
        executable: Executable,
        description: String,
        callback: (XposedInterface.Chain, Any?) -> Unit
    ) {
        try {
            module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        callback(chain, result)
                    } catch (t: Throwable) {
                        logger.error("After-hook failed: $description", t)
                    }
                    result
                }
            logger.debug("Installed protective hook: $description")
        } catch (t: Throwable) {
            logger.error("Unable to install hook: $description", t)
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 400L
    }
}
