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
 * 通用歌词提供者。两条通道，先本地、后网络：
 *
 *  1. **本地缓存**（已适配平台）：读宿主 App 自己缓存好的歌词文件，
 *     先用落盘索引找，找不到再按证据强度扫目录；
 *  2. **网络旁路**（通用）：在宿主进程里嗅探它刚收到的网络歌词，
 *     给「只在内存里显示歌词」的播放器兜底。
 *
 * 其它约定：
 *  - 每个已勾选进程都挂 MediaSession 钩子，**谁真正收到回调谁才创建 Provider**，
 *    避免多进程重复注册；
 *  - 切歌后按退避节奏重试约 2 分钟（播放器普遍「先切歌、后拉歌词」）；
 *  - 两条通道都拿不到歌词时，兜底推送「歌名 - 歌手」，让星流至少有信息可显示。
 */
internal class LocalLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String,
    private val recipe: LocalRecipe?
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

    /** 兜底歌曲信息是否已经推送过 */
    @Volatile
    private var fallbackSent = false

    private val attemptIndex = AtomicInteger(0)

    /** 最近一次嗅到的歌词：洛雪这类播放器会「先给歌词、后报歌曲信息」 */
    @Volatile
    private var pendingLyric: Triple<LocalLyric, String, Long>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lookupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xinghe-lyric-lookup").apply { isDaemon = true }
    }

    private val retryTask = Runnable { tryLookup() }

    fun installHooks() {
        installRnBridge()
        hookApplicationLifecycle()
        hookMediaSession()
        installSniffer()
        logger.info(
            "Local lyric hooks installed for $hostPackage " +
                "(${recipe?.displayName ?: "通用嗅探"}) in $processName"
        )
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
                    player.setDisplayRoma(true)
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

    // ---------------- 网络旁路 ----------------

    private fun installSniffer() {
        runCatching {
            HttpLyricSniffer(module, logger, classLoader) { lyric, source ->
                onNetworkLyric(lyric, source)
            }.install()
        }.onFailure { logger.error("网络歌词嗅探挂载失败", it) }
    }

    /** React Native 播放器（洛雪这类）：歌词模块 + JS 桥出口 */
    private fun installRnBridge() {
        runCatching {
            RnLyricBridge(module, logger, classLoader) { lyric, source -> onNetworkLyric(lyric, source) }.install()
        }.onFailure { logger.error("RN 歌词嗅探挂载失败", it) }
    }

    private fun onNetworkLyric(lyric: LocalLyric, source: String) {
        pendingLyric = Triple(lyric, source, SystemClock.elapsedRealtime())
        val signature = currentSignature ?: return
        if (lyricFound) return
        val parts = signature.split('|')
        val title = parts.getOrNull(0).orEmpty()
        if (title.isBlank()) return
        val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }
        val duration = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val mediaId = parts.getOrNull(3)?.takeIf { it.isNotBlank() && it != "null" }
        logger.info("[$source] 嗅到歌词：${lyric.lines.size} 行，标题=<${lyric.title ?: lyric.firstLine}>")
        mainHandler.post {
            if (currentSignature != signature || lyricFound) return@post
            publish(title, artist, duration, mediaId, lyric.lines, source)
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
        fallbackSent = false
        attemptIndex.set(0)

        val registered = ensureProvider()
        if (registered == null) {
            logger.warn("No provider for $title - $artist in $processName")
            return
        }
        runCatching { registered.player.setPosition(0L) }

        logger.info("Metadata changed: $title - $artist ($duration) id=$mediaId in $processName")
        publishPending(title, artist, duration, mediaId)
        mainHandler.removeCallbacks(retryTask)
        mainHandler.post(retryTask)
    }

    private fun onPlaybackStateChanged(state: PlaybackState?) {
        state ?: return
        val registered = ensureProvider() ?: return
        // 直接转发框架的播放状态对象，让远端自己推算进度（与参考实现一致）
        runCatching { registered.player.setPlaybackState(state) }
    }

    // ---------------- 歌词查找（本地缓存 + 退避重试） ----------------

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
        val lastAttempt = index >= RETRY_DELAYS_MS.size

        val localRecipe = recipe
        if (localRecipe == null) {
            // 没有本地配方：全程等网络嗅探，窗口结束还没等到就兜底推歌曲信息
            if (lastAttempt) publishFallback(title, artist, duration, mediaId)
            else mainHandler.postDelayed(retryTask, delay)
            return
        }

        lookupExecutor.execute {
            val context = application ?: currentApplication()
            if (context == null) {
                logger.warn("Lookup skipped: no context in $processName")
                return@execute
            }
            val started = SystemClock.elapsedRealtime()
            val lines = try {
                LocalLyricFinder.find(context, localRecipe, title, artist, duration, mediaId) {
                    logger.info("[${localRecipe.displayName}] $it")
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
                mainHandler.post { publish(title, artist, duration, mediaId, lines, localRecipe.displayName) }
                return@execute
            }
            if (!lyricFound && currentSignature == signature) {
                if (lastAttempt) {
                    mainHandler.post { publishFallback(title, artist, duration, mediaId) }
                } else {
                    mainHandler.postDelayed(retryTask, delay)
                }
            }
        }
    }

    private fun publish(
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String?,
        lyrics: List<RichLyricLine>,
        source: String
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
        val words = lines.count { !it.words.isNullOrEmpty() }
        val translated = lines.count { !it.translation.isNullOrBlank() }
        val ok = try {
            registered.player.setSong(song)
        } catch (t: Throwable) {
            logger.error("setSong threw", t)
            false
        }
        lyricFound = true
        logger.info(
            "Lyric published from $source: $title, ${lines.size} lines " +
                "(逐字 $words 行, 翻译 $translated 行), id=${song.id}, " +
                "duration=${song.duration}, active=${registered.player.isActive}, ok=$ok"
        )
    }

    /** 两条通道都没歌词时的兜底：只推歌曲信息，至少让星流有东西可显示 */
    /** 歌词先到、歌曲信息后到：把缓存的那份拿出来用 */
    private fun publishPending(title: String, artist: String?, durationMs: Long, mediaId: String?) {
        val cached = pendingLyric ?: return
        val (lyric, source, at) = cached
        val age = SystemClock.elapsedRealtime() - at
        if (age > PENDING_TTL_MS) {
            pendingLyric = null
            return
        }
        val sniffed = lyric.title?.let { LocalLyricFinder.normalize(it) }.orEmpty()
        val want = LocalLyricFinder.normalize(title)
        val matched = sniffed.isNotEmpty() &&
            (sniffed == want || sniffed.contains(want) || want.contains(sniffed))
        if (!matched && age > PENDING_QUICK_MS) return
        logger.info("[$source] 使用缓存歌词（${age}ms 前嗅到，匹配=$matched）")
        publish(title, artist, durationMs, mediaId, lyric.lines, source)
    }

    private fun publishFallback(title: String, artist: String?, durationMs: Long, mediaId: String?) {
        if (lyricFound || fallbackSent) return
        val registered = ensureProvider() ?: return
        fallbackSent = true
        val song = Song().apply {
            id = mediaId ?: title
            name = title
            this.artist = artist
            this.duration = durationMs
            this.lyrics = emptyList()
        }
        val ok = runCatching { registered.player.setSong(song) }.getOrDefault(false)
        logger.info("无歌词兜底：推送歌曲信息 $title - $artist, ok=$ok")
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
            clampWords(line)
            out.add(line)
        }
        return out
    }

    /** 逐字时间也要收进本行范围内，否则星流侧的逐字进度会跑飞 */
    private fun clampWords(line: RichLyricLine) {
        val words = line.words ?: return
        for (word in words) {
            if (word.begin < line.begin) word.begin = line.begin
            if (word.end <= word.begin) word.end = word.begin + 1
            if (word.end > line.end) word.end = line.end
            if (word.end <= word.begin) word.end = word.begin + 1
            word.duration = word.end - word.begin
        }
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
        /** 缓存歌词的有效期与「快速窗口」 */
        private const val PENDING_TTL_MS = 90_000L
        private const val PENDING_QUICK_MS = 15_000L
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
