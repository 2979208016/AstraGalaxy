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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通用「本地歌词」提供者。
 *
 * 流程：
 *  1. 每个已勾选进程都挂 MediaSession 钩子；
 *  2. **谁真正收到回调，谁才创建 Provider**（见 [ensureProvider]）——避免多进程重复注册，
 *     这是参考实现 LyricProvider 明确规避的坑；
 *  3. 拿到 曲名/歌手/时长/mediaId 后，到宿主 App 自己的歌词缓存里按证据强度匹配歌词；
 *  4. 匹配到就 setSong 推给 SystemUI/星流，并同步播放状态。
 *
 * 匹配不中会按退避节奏重试：播放器普遍是「先切歌、后拉歌词落盘」，
 * 只在切歌瞬间查一次必然大量漏词。
 */
internal class LocalLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String,
    private val recipe: LocalRecipe
) {

    private val stateLock = Any()

    @Volatile
    private var provider: LyriconProvider? = null

    @Volatile
    private var application: Application? = null

    /** 当前歌曲签名（标题|歌手|时长|mediaId），变化才重新查找歌词 */
    @Volatile
    private var currentSignature: String? = null

    @Volatile
    private var currentMediaId: String? = null

    @Volatile
    private var metadataDurationMs: Long = 0L

    @Volatile
    private var lyricFound = false

    private val attemptIndex = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lookupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xinghe-lyric-lookup").apply { isDaemon = true }
    }

    private val retryTask = Runnable { tryLookup() }

    fun installHooks() {
        hookApplicationLifecycle()
        hookMediaSession()
        logger.info("Local lyric hooks installed for $hostPackage (${recipe.displayName}) in $processName")
    }

    // ---------------- Provider（惰性注册） ----------------

    /**
     * 惰性创建并注册 Provider。
     *
     * 不在 Application#onCreate 里注册：一个 App 往往有多个进程被勾选，
     * 挨个注册会让 SystemUI 侧出现同一播放器的多个提供者实例，
     * 歌词从 A 进程发出、SystemUI 却绑定在 B 进程的实例上，最终一个字都显示不出来。
     * 这里改成「谁收到媒体回调谁注册」，天然只有一个进程注册。
     */
    private fun ensureProvider(): LyriconProvider? {
        provider?.let { return it }
        synchronized(stateLock) {
            provider?.let { return it }
            val context = application ?: currentApplication()
            if (context == null) {
                logger.warn("Provider not created: no context in $processName")
                return null
            }
            return try {
                val logo = runCatching { ProviderLogo.fromSvg(Constants.ICON) }.getOrNull()
                val created = LyriconFactory.createProvider(
                    context = context,
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
                logger.info("Provider registered: player=$hostPackage, process=$processName")
                created
            } catch (throwable: Throwable) {
                logger.error("Provider registration failed in $processName", throwable)
                null
            }
        }
    }

    /** 拿不到 Application 实例时的兜底（任何进程只要 Application 已创建就能拿到） */
    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    } catch (throwable: Throwable) {
        null
    }

    // ---------------- 应用生命周期 ----------------

    private fun hookApplicationLifecycle() {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java
        )
        installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
            val hostApplication = chain.args.getOrNull(0) as? Application
            if (hostApplication != null) application = hostApplication
        }
    }

    // ---------------- MediaSession ----------------

    private fun hookMediaSession() {
        val metadataMethod = MediaSession::class.java.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java
        )
        installProtectiveAfterHook(metadataMethod, "MediaSession.setMetadata") { chain, _ ->
            val md = chain.args.getOrNull(0) as? MediaMetadata
            logger.debug("setMetadata size=" + md?.size() + ", title=" + md?.getString(MediaMetadata.METADATA_KEY_TITLE))
            onMetadataChanged(md)
        }

        val playbackMethod = MediaSession::class.java.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java
        )
        installProtectiveAfterHook(playbackMethod, "MediaSession.setPlaybackState") { chain, _ ->
            val st = chain.args.getOrNull(0) as? PlaybackState
            logger.debug("setPlaybackState state=" + st?.state + ", pos=" + st?.position)
            onPlaybackStateChanged(st)
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
            if (signature == currentSignature) return
            currentSignature = signature
        }

        currentMediaId = mediaId
        lyricFound = false
        attemptIndex.set(0)

        val registered = ensureProvider()
        if (registered == null) {
            logger.warn("No provider for $title - $artist in $processName")
            return
        }
        runCatching { registered.player.setPosition(0L) }

        logger.info("Metadata changed: $title - $artist ($duration) id=$mediaId in $processName")
        mainHandler.removeCallbacks(retryTask)
        mainHandler.post(retryTask)
    }

    private fun onPlaybackStateChanged(state: PlaybackState?) {
        state ?: return
        val registered = ensureProvider() ?: return
        // 直接转发框架的播放状态对象，让远端自己推算进度（与参考实现一致）
        runCatching { registered.player.setPlaybackState(state) }
    }

    // ---------------- 本地歌词查找（带退避重试） ----------------

    private fun tryLookup() {
        if (lyricFound) return
        val signature = currentSignature ?: return
        val parts = signature.split('|')
        val title = parts.getOrNull(0).orEmpty()
        val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }
        val duration = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val mediaId = parts.getOrNull(3)?.takeIf { it.isNotBlank() && it != "null" }
        if (title.isBlank()) return

        val index = attemptIndex.getAndIncrement()
        val delay = RETRY_DELAYS_MS.getOrElse(index) { RETRY_DELAYS_MS.last() }

        lookupExecutor.execute {
            val context = application ?: currentApplication()
            if (context == null) {
                logger.warn("Lookup skipped: no context in $processName")
                return@execute
            }
            val started = SystemClock.elapsedRealtime()
            val lines = try {
                LocalLyricFinder.find(context, recipe, title, artist, duration, mediaId) {
                    logger.info("[${recipe.displayName}] $it")
                }
            } catch (t: Throwable) {
                logger.error("Local lyric lookup failed: ${t.message}", t)
                null
            }
            val cost = SystemClock.elapsedRealtime() - started
            if (lines.isNullOrEmpty()) {
                logger.info("No local lyric (attempt #${index + 1}, ${cost}ms): $title - $artist")
            } else {
                if (currentSignature != signature) {
                    logger.info("Dropped stale lyric (song changed)")
                    return@execute
                }
                lyricFound = true
                publish(title, artist, duration, mediaId, lines)
            }
            if (!lyricFound && currentSignature == signature) {
                mainHandler.postDelayed(retryTask, delay)
            }
        }
    }

    private fun publish(
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String?,
        lyrics: List<RichLyricLine>
    ) {
        val registered = ensureProvider()
        if (registered == null) {
            logger.warn("Lyric dropped: provider not registered in $processName")
            return
        }
        val lines = sanitize(lyrics)
        if (lines.isEmpty()) {
            logger.warn("Lyric dropped: no usable line for $title")
            return
        }
        val lastEnd = lines.last().end
        val song = Song().apply {
            // 用宿主自己的歌曲 id：SystemUI 侧要靠它把歌词和当前媒体会话对上号
            id = mediaId ?: title
            name = title
            this.artist = artist
            this.duration = if (durationMs > 0) durationMs else lastEnd
            this.lyrics = lines
        }
        val ok = try {
            registered.player.setSong(song)
        } catch (t: Throwable) {
            logger.error("setSong threw", t)
            false
        }
        logger.info(
            "Local lyric published: $title, ${lines.size} lines, id=${song.id}, " +
                "duration=${song.duration}, active=${registered.player.isActive}, ok=$ok"
        )
    }

    /**
     * 规范化歌词行，保证交给 SystemUI 的数据「形状」合法：
     * 去掉空行、时间非降序、行尾越界（压到下一行行首之内）。
     */
    private fun sanitize(raw: List<RichLyricLine>): List<RichLyricLine> {
        val cleaned = raw
            .filter { !it.text.isNullOrBlank() && it.begin >= 0 }
            .sortedBy { it.begin }
        if (cleaned.isEmpty()) return emptyList()

        val out = ArrayList<RichLyricLine>(cleaned.size)
        for (i in cleaned.indices) {
            val line = cleaned[i]
            val nextBegin = cleaned.getOrNull(i + 1)?.begin
            var end = if (line.end > line.begin) line.end else line.begin + 1
            if (nextBegin != null && end > nextBegin) end = nextBegin
            if (end <= line.begin) end = line.begin + 1
            line.end = end
            line.duration = end - line.begin
            out.add(line)
        }
        return out
    }

    // ---------------- 工具 ----------------

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
        /**
         * 重试节奏（毫秒，每项是「这一次查完后再等多久」）。
         * 总窗口约 2 分钟，足够覆盖播放器拉取歌词并落盘的过程。
         */
        private val RETRY_DELAYS_MS = longArrayOf(
            600L, 1200L, 2000L, 3000L, 4500L, 6500L, 9000L,
            12000L, 16000L, 20000L, 25000L, 30000L
        )
    }
}
