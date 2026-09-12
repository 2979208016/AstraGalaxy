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


    /** 上一次切歌时刻（用于判断嗅到的歌词是否属于当前歌曲） */
    @Volatile
    private var songSwitchAt: Long = 0L

    // ---------------- 进度锚点：自行推算，高频写入共享内存 ----------------

    @Volatile
    private var anchorPosition: Long = 0L

    @Volatile
    private var anchorRealtime: Long = 0L

    @Volatile
    private var anchorPlaying: Boolean = false

    @Volatile
    private var playbackSpeed: Float = 1.0f

    @Volatile
    private var hasAnchor: Boolean = false

    /** 上一次同步给星流的播放状态，避免重复 IPC */
    private var lastPushedPlaying: Boolean? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            tickProgress()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }
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
        installBluetoothBoost()
        logger.info(
            "Local lyric hooks installed for $hostPackage " +
                "(${recipe?.displayName ?: "通用嗅探"}) in $processName"
        )
    }

    /**
     * 酷我、汽水这类播放器只在「检测到无线音频输出」时才上报完整媒体信息，
     * 否则 MediaSession 里永远没有歌名。这里只把两个查询方法改成恒为 true，
     * 不连接、不开启任何真实设备，对播放器本身没有副作用。
     */
    private fun installBluetoothBoost() {
        if (hostPackage !in Constants.BLUETOOTH_BOOST_PACKAGES) return
        val targets = listOf(
            "android.media.AudioManager" to "isBluetoothA2dpOn",
            "android.bluetooth.BluetoothAdapter" to "isEnabled"
        )
        for ((className, methodName) in targets) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.getDeclaredMethod(methodName)
                module.hook(method).intercept { true }
                logger.info("蓝牙输出伪装已挂载：$className#$methodName")
            } catch (throwable: Throwable) {
                logger.warn("蓝牙输出伪装失败：$className#$methodName（${throwable.message}）")
            }
        }
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
        val sniffedAt = SystemClock.elapsedRealtime()
        pendingLyric = Triple(lyric, source, sniffedAt)
        val signature = currentSignature ?: return
        if (lyricFound) return
        val parts = signature.split('|')
        val title = parts.getOrNull(0).orEmpty()
        if (title.isBlank()) return
        val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }
        val duration = metadataDurationMs
        val mediaId = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "null" }
            ?: currentMediaId
        logger.info("[$source] 嗅到歌词：${lyric.lines.size} 行，标题=<${lyric.title ?: lyric.firstLine}>")
        if (!belongsToCurrentSong(lyric, sniffedAt, title, artist)) {
            logger.info("[$source] 丢弃非当前歌曲的歌词（当前=$title - $artist）")
            return
        }
        mainHandler.post {
            if (currentSignature != signature || lyricFound) return@post
            publish(title, artist, duration, mediaId, lyric.lines, source)
        }
    }

    /**
     * 判断嗅到的歌词是否属于当前歌曲，避免把上一首/下一首的歌词推成当前歌曲：
     *  - 歌词自带标题或歌手时，必须与当前歌曲对得上；
     *  - 没有标题信息时，只接受本次切歌之后才嗅到的内容。
     */
    private fun belongsToCurrentSong(
        lyric: LocalLyric,
        sniffedAt: Long,
        title: String,
        artist: String?
    ): Boolean {
        val want = LocalLyricFinder.normalize(title)
        val sniffed = LocalLyricFinder.normalize(lyric.title)
        val sniffedArtist = LocalLyricFinder.normalize(lyric.artist)
        if (sniffed.isNotEmpty()) {
            if (sniffed == want || sniffed.contains(want) || want.contains(sniffed)) return true
            val wantArtist = LocalLyricFinder.normalize(artist)
            if (wantArtist.isNotEmpty() && sniffedArtist.isNotEmpty() &&
                (sniffedArtist.contains(wantArtist) || wantArtist.contains(sniffedArtist))
            ) return true
            return false
        }
        // 没有标题信息：接受切歌瞬间前后很短窗口内嗅到的内容（很多播放器先拉歌词、后报元数据）
        return sniffedAt >= songSwitchAt - SWITCH_GRACE_MS
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

        // 签名不含时长：波点这类播放器同一首歌会重复上报 metadata，
        // 只有时长/音质不同不该被当成新歌，否则歌词会被反复清空重查。
        val signature = "$title|$artist|$mediaId"
        synchronized(stateLock) {
            if (signature == currentSignature) return
            currentSignature = signature
        }

        currentMediaId = mediaId
        lyricFound = false
        fallbackSent = false
        attemptIndex.set(0)
        pendingLyric = null
        songSwitchAt = SystemClock.elapsedRealtime()

        // 切歌：进度归零，重新起锚
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = true
        lastPushedPlaying = null

        val registered = ensureProvider()
        if (registered == null) {
            logger.warn("No provider for $title - $artist in $processName")
            return
        }
        runCatching { registered.player.setPosition(0L) }

        logger.info("Metadata changed: $title - $artist ($duration) id=$mediaId in $processName")
        // 先推「只有歌曲信息、没有歌词」的快照，立刻清掉上一首的歌词，
        // 拿到真歌词后再覆盖（星流接受同一 id 的后续更新）。
        publishPlaceholder(title, artist, duration, mediaId)
        publishPending(title, artist, duration, mediaId)
        startProgressTicker()
        mainHandler.removeCallbacks(retryTask)
        mainHandler.post(retryTask)
    }

    private fun onPlaybackStateChanged(state: PlaybackState?) {
        state ?: return
        val registered = ensureProvider() ?: return

        val position = state.position
        val playing = state.state == PlaybackState.STATE_PLAYING
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1.0f

        if (position >= 0L) {
            anchorPosition = position
            anchorRealtime = SystemClock.elapsedRealtime()
            hasAnchor = true
        }
        anchorPlaying = playing
        playbackSpeed = speed

        runCatching { registered.player.setPlaybackState(state) }
        runCatching { registered.player.setPosition(extrapolatedPosition()) }
        lastPushedPlaying = playing
        startProgressTicker()
    }

    /**
     * 星流每帧从共享内存读进度，只在播放器发 setPlaybackState 时同步一次会「卡住」，
     * 因此这里按固定间隔自行推算并写入。
     */
    private fun startProgressTicker() {
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    private fun extrapolatedPosition(): Long {
        if (!hasAnchor) return 0L
        if (!anchorPlaying) return anchorPosition
        val elapsed = SystemClock.elapsedRealtime() - anchorRealtime
        val pos = anchorPosition + (elapsed.toDouble() * playbackSpeed.toDouble()).toLong()
        val duration = metadataDurationMs
        return if (duration > 0) pos.coerceIn(0L, duration) else pos.coerceAtLeast(0L)
    }

    private fun tickProgress() {
        val registered = provider ?: return
        if (!hasAnchor) return
        runCatching {
            if (lastPushedPlaying != anchorPlaying) {
                registered.player.setPlaybackState(anchorPlaying)
                lastPushedPlaying = anchorPlaying
            }
            registered.player.setPosition(extrapolatedPosition())
        }
    }

    // ---------------- 歌词查找（本地缓存 + 退避重试） ----------------

    private fun tryLookup() {
        if (lyricFound) return
        val signature = currentSignature ?: return
        val parts = signature.split('|')
        val title = parts.getOrNull(0).orEmpty()
        val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }
        val duration = metadataDurationMs
        val mediaId = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "null" }
            ?: currentMediaId
        if (title.isBlank()) return

        val index = attemptIndex.getAndIncrement()
        val delay = RETRY_DELAYS_MS.getOrElse(index) { RETRY_DELAYS_MS.last() }
        val lastAttempt = index >= RETRY_DELAYS_MS.size

        val localRecipe = recipe
        if (localRecipe == null && hostPackage != Constants.LUNA_PACKAGE) {
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
            if (hostPackage == Constants.LUNA_PACKAGE) {
                // 汽水音乐：歌词是它自己按歌曲 id 命名的 JSON 缓存，读不到就等下一次重试
                val luna = runCatching { LunaLyric.find(context, mediaId) }.getOrNull()
                val cost = SystemClock.elapsedRealtime() - started
                if (luna != null && luna.lines.size >= 3) {
                    if (currentSignature != signature) {
                        logger.info("Dropped stale lyric (song changed)")
                        return@execute
                    }
                    lyricFound = true
                    mainHandler.post {
                        publish(title, artist, duration, mediaId, luna.lines, "汽水音乐")
                    }
                    return@execute
                }
                logger.info("汽水音乐诊断#" + (index + 1) + "：" + LunaLyric.describe(context, mediaId))
                logger.info("汽水音乐：缓存里还没有歌词（attempt #${index + 1}, ${cost}ms）")
                if (!lyricFound && currentSignature == signature) {
                    if (lastAttempt) {
                        mainHandler.post { publishFallback(title, artist, duration, mediaId) }
                    } else {
                        mainHandler.postDelayed(retryTask, delay)
                    }
                }
                return@execute
            }
            val target = localRecipe!!
            val lines = try {
                LocalLyricFinder.find(context, target, title, artist, duration, mediaId) {
                    logger.info("[${target.displayName}] $it")
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
    /** 歌词先到、歌曲信息后到：把缓存的那份拿出来用 */
    private fun publishPending(title: String, artist: String?, durationMs: Long, mediaId: String?) {
        val cached = pendingLyric ?: return
        val (lyric, source, at) = cached
        val age = SystemClock.elapsedRealtime() - at
        if (age > PENDING_TTL_MS) {
            pendingLyric = null
            return
        }
        if (!belongsToCurrentSong(lyric, at, title, artist)) return
        logger.info("[$source] 使用缓存歌词（${age}ms 前嗅到）")
        publish(title, artist, durationMs, mediaId, lyric.lines, source)
    }

    /** 切歌时先推「只有歌曲信息、没有歌词」的快照，清掉上一首的歌词 */
    private fun publishPlaceholder(title: String, artist: String?, durationMs: Long, mediaId: String?) {
        val registered = ensureProvider() ?: return
        val song = Song().apply {
            id = mediaId ?: title
            name = title
            this.artist = artist
            this.duration = durationMs
            this.lyrics = emptyList()
        }
        val ok = runCatching { registered.player.setSong(song) }.getOrDefault(false)
        logger.info("切歌占位：推送歌曲信息 $title - $artist, ok=$ok")
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
            .filter {
                !it.text.isNullOrBlank() && it.begin >= 0 &&
                    !LyricParsers.looksLikeNoise(it.text.orEmpty())
            }
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
        /** 缓存歌词有效期（仅用于「歌词先到、歌曲信息后到」的场景） */
        /** 无标题歌词的「切歌前后」容忍窗口：很多播放器先拉歌词、后报元数据 */
        private const val SWITCH_GRACE_MS = 4_000L
        private const val PENDING_TTL_MS = 30_000L

        /** 进度写入间隔：星流按帧读共享内存，41ms 是它期望的默认节奏 */
        private const val TICK_INTERVAL_MS = 48L

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
