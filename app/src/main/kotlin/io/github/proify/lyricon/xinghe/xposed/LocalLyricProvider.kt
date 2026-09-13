package io.github.proify.lyricon.xinghe.xposed

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
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
    private var lastLoggedState = -1
    private var pendingSwitch: PendingSwitch? = null
    private var pendingSwitchAt = 0L
    private var pendingSwitchLyric: LocalLyric? = null

    /** 本次启动是否已经真正提交过一首歌（第一首不做待定，开播即出词） */
    private var hasCommittedSong = false
    private var sawPositionSinceSwitch = false
    private var lastReport = -1L
    private var lastReportAt = 0L

    /** 清洗后的当前歌曲名 / 歌手（酷狗、QQ 会把歌词行伪装成标题） */
    @Volatile
    private var currentTitle: String? = null

    @Volatile
    private var currentArtist: String? = null

    /** 当前已发布歌词的归一化行集合：命中它的一定是歌词行，不是歌名 */
    @Volatile
    private var knownLyricLines: Set<String> = emptySet()

    /** ARTIST 是「歌名-歌手」（QQ 音乐）还是「歌手-歌名」（酷狗） */
    private val songFirstArtistLast: Boolean = hostPackage == Constants.QQ_PACKAGE

    /** 平台是否会把「歌名-歌手」写进 ARTIST 字段（酷狗系、QQ 音乐） */
    private val trustArtistStructure: Boolean =
        hostPackage in Constants.ARTIST_STRUCTURE_PACKAGES

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

    /** 酷我/酷狗 提前发来的下一首歌词：留着，真切到那首歌再推 */
    private var preloadedLyric: Triple<LocalLyric, String, Long>? = null

    /** 本次启动是否成功推过歌词（第一首放宽身份校验） */
    private var everPublished = false


    /** 上一次切歌时刻（用于判断嗅到的歌词是否属于当前歌曲） */
    @Volatile
    private var songSwitchAt: Long = 0L

    /** 上一次切歌的挂钟时刻：本地歌词文件「是不是本次播放期间写下的」必须用挂钟时间比对 */
    @Volatile
    private var songSwitchWall: Long = 0L

    /** 上一次「真正切歌」的时刻（用于忽略旧歌残留的 position） */
    @Volatile
    private var lastSongSwitchAt: Long = 0L

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
                    // 翻译 / 音译开关归星流管：这里不再主动打开，
                    // 否则默认就把原文换成译文显示（用户反馈「中文歌被翻成韩文 / 英文」）。
                    player.setDisplayTranslation(false)
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

    private fun onNetworkLyric(lyric: LocalLyric, source: String, trustedBySource: Boolean = false) {
        val sniffedAt = SystemClock.elapsedRealtime()
        pendingLyric = Triple(lyric, source, sniffedAt)
        val signature = currentSignature ?: return
        val trusted = trustedBySource || hasTrustworthyIdentity(lyric)
        // 已经出过词、新来的又没有更强证据（自带歌名）→ 不打扰
        if (lyricFound && !trusted) return
        val parts = signature.split('|')
        val title = parts.getOrNull(0).orEmpty()
        if (title.isBlank()) return
        val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }
        val duration = metadataDurationMs
        val mediaId = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "null" }
            ?: currentMediaId
        logger.info("[$source] 嗅到歌词：${lyric.lines.size} 行，标题=<${lyric.title ?: lyric.firstLine}>")
        val mustVerify = !trustedBySource ||
            hostPackage == "cn.kuwo.player" || hostPackage == "com.netease.cloudmusic"
        if (mustVerify && !belongsToCurrentSong(lyric, sniffedAt, title, artist, duration, mediaId)) {
            logger.info("[$source] 丢弃非当前歌曲的歌词（当前=$title - $artist）")
            return
        }
        mainHandler.post {
            if (currentSignature != signature) return@post
            // 同一首歌只推第一份（原文）。播放器随后请求的「翻译版 lrc / 翻译接口」时间戳和
            // 原曲一致、只是文字变了，绝不能拿它替换正文（用户反馈「中文歌突然变英文歌词」）。
            // 译文要显示与否交给星流自己的翻译开关。
            if (lyricFound) return@post
            everPublished = true
            publish(title, artist, duration, mediaId, lyric.lines, source, lyric.title)
        }
    }

    /** 歌词自带歌名，且与当前歌曲对得上 → 证据强于「无标题只能靠时间窗口」的猜测 */
    private fun hasTrustworthyIdentity(lyric: LocalLyric): Boolean {
        val want = LocalLyricFinder.normalize(currentTitle)
        if (want.isEmpty()) return false
        val candidate = LocalLyricFinder.normalize(lyric.title)
        if (candidate.isEmpty()) return false
        return candidate == want || candidate.contains(want) || want.contains(candidate)
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
        artist: String?,
        durationMs: Long,
        mediaId: String? = null
    ): Boolean {
        val sniffed = LocalLyricFinder.normalize(lyric.title)
        val sniffedArtist = LocalLyricFinder.normalize(lyric.artist)
        // 歌词自带歌曲 id：和当前歌曲 id 对不上，一定是别的歌（网易云的歌词 JSON 带 musicId）
        val wantId = LocalLyricFinder.normalize(mediaId)
        val sniffedId = LocalLyricFinder.normalize(lyric.id)
        if (wantId.isNotEmpty() && sniffedId.isNotEmpty() && wantId != sniffedId) return false
        if (sniffed.isNotEmpty()) {
            if (LocalLyricFinder.titlesMatch(lyric.title, title)) return true
            val wantArtist = LocalLyricFinder.normalize(artist)
            if (wantArtist.isNotEmpty() && sniffedArtist.isNotEmpty() &&
                (sniffedArtist.contains(wantArtist) || wantArtist.contains(sniffedArtist))
            ) return true
            return false
        }
        // 没有标题信息时：先看总时长——歌词最后一行和本曲时长吻合，同样足以认定是当前歌曲；
        // 否则只接受切歌前后极小窗口内嗅到的内容（窗口给大了会把上一首在途的响应算进来）。
        val lastLine = lyric.lines.lastOrNull()?.begin ?: 0L
        if (durationMs > 0L && lastLine > 0L) {
            val diff = if (lastLine > durationMs) lastLine - durationMs else durationMs - lastLine
            if (diff <= 8_000L) return true
            // 时长明显不吻合：酷我/酷狗 会提前把下一首的歌词发过来，
            // 这种歌词的首行往往就是那首歌的歌名，对不上就绝不能推给当前歌曲。
            val guess = lyric.firstLine?.trim().orEmpty()
            if (guess.isNotEmpty() && guess.length <= 24 && !guess.startsWith("[")) {
                val g = LocalLyricFinder.normalize(guess)
                val w = LocalLyricFinder.normalize(title)
                if (g.isNotEmpty() && w.isNotEmpty() && !(g.contains(w) || w.contains(g))) {
                    preloadedLyric = Triple(lyric, "预载", sniffedAt)
                    logger.info("暂存下一首歌词：首行=<$guess> 当前=$title")
                    return false
                }
            }
        }
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

    /**
     * 有些播放器（酷我）会把整份歌词塞进 MediaMetadata 的附加字段里，
     * 本地文件通道和 okhttp 通道都抓不到，只能从这里捞一把。
     */
    private fun dumpMetadataExtras(metadata: MediaMetadata) {
        val extras = runCatching {
            MediaMetadata::class.java.getMethod("getExtras").invoke(metadata) as? Bundle
        }.getOrNull() ?: return
        if (extras.size() == 0) return
        logger.debug("元数据附加字段(" + extras.size() + "): " + extras.keySet().joinToString(","))
        for (key in extras.keySet()) {
            val lower = key.lowercase()
            if (!lower.contains("lyric") && !lower.contains("krc") && !lower.contains("lrc")) continue
            val value = extras.get(key)
            val text = when (value) {
                is String -> value
                is ByteArray -> runCatching { String(value, Charsets.UTF_8) }.getOrNull()
                else -> null
            } ?: continue
            if (text.length < 32) continue
            logger.info("元数据里带歌词字段：$key（${text.length} 字符）")
            submitMetadataLyric(text, key)
        }
    }

    private fun submitMetadataLyric(text: String, key: String) {
        mainHandler.post {
            val lyric = runCatching { LyricParsers.parseAnyPayload(text) }.getOrNull()
                ?: runCatching { LyricParsers.parseAnyBytes(text.toByteArray()) }.getOrNull()
                ?: return@post
            if (!LyricParsers.isUsable(lyric.lines)) return@post
            onNetworkLyric(lyric, "元数据·" + key, trustedBySource = true)
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata?) {
        metadata ?: return
        dumpMetadataExtras(metadata)
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() && it != "未知歌曲" }
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && it != "未知歌手" }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.takeIf { it.isNotBlank() }

        if (duration > 0) metadataDurationMs = duration

        // 酷狗 / QQ 音乐会把「当前歌词行」和「作词：xxx」这类制作信息当成标题发出来，
        // 真正的歌曲信息藏在歌手字段里（详见 PlayerTitle）。这里统一清洗：
        // 既不能让歌词行变成歌名，也不能让每一句歌词都触发一次「切歌」。
        val resolved = PlayerTitle.resolve(
            title = rawTitle,
            artist = rawArtist,
            currentName = currentTitle,
            currentArtist = currentArtist,
            lyricLines = knownLyricLines,
            songFirstArtistLast = songFirstArtistLast,
            trustArtistStructure = trustArtistStructure
        )
        val title = resolved.name
        if (title == null) {
            logger.info(
                "元数据无法解析，忽略：title=$rawTitle artist=$rawArtist " +
                    "(suspect=${resolved.suspectTitle})"
            )
            return
        }
        val artist = resolved.artist

        // 清洗后歌名没变（含「歌词行伪装 + 从歌手字段还原出同一首歌」）= 同一首歌，不重查歌词。
        // 注意不能只看 mediaId：个别播放器会给所有歌发同一个 id，
        // 那样一旦按 mediaId 判同曲，切歌就再也认不出来了。
        //
        // 反过来也要小心：网易云在一首歌里也会反复发 metadata（标题会临时变成当前歌词行），
        // 实测它的 mediaId 在中途也会变 —— 曾经按「mediaId 变了就当切歌」处理，结果一拖
        // 进度条就被当成换歌，锚点被清零、整首歌词对不上（用户反馈）。所以这里只认
        // 「歌名/歌手变了」，mediaId 仅用于日志与观察。
        val idChanged = mediaId != null && currentMediaId != null && mediaId != currentMediaId
        val sameSong = resolved.sameSong
        if (idChanged) {
            logger.info("mediaId 变化（$currentMediaId → $mediaId），仍按同一首处理：$title - $artist")
        }
        logger.info("元数据：title=$rawTitle artist=$rawArtist id=$mediaId 时长=$duration 解析后=$title / $artist")

        if (sameSong) {
            if (resolved.suspectTitle) {
                logger.info(
                    "同一首歌的元数据（title=$rawTitle artist=$rawArtist → $title / $artist），跳过重查"
                )
            }
            if (mediaId != null) currentMediaId = mediaId
            synchronized(stateLock) {
                if (resolved.suspectTitle && !artist.isNullOrBlank()) {
                    // 歌词行伪装的元数据：只借用它更完整的歌手信息，其余一概不动
                    currentArtist = artist
                }
                    currentSignature = songSignature(title, artist, mediaId ?: currentMediaId)
            }
            return
        }

        // 真正切歌。网易云会先把 metadata 换成下一首、音频却还在放上一首（实测能差十几秒），
        // 所以这里先「待定」，由接下来的位置回调决定：位置和上一首的推算值连得上 → 音频没切，
        // 继续保留上一首歌词；位置跳了 → 音频真的切了，才清歌词、归零进度。
        currentMediaId = mediaId
        synchronized(stateLock) {
            currentSignature = songSignature(title, artist, mediaId)
            currentTitle = title
            currentArtist = artist
        }
        songSwitchAt = SystemClock.elapsedRealtime()
        songSwitchWall = System.currentTimeMillis()
        lastSongSwitchAt = songSwitchAt
        preloadedLyric?.let { p ->
            val stale = SystemClock.elapsedRealtime() - p.third > 120_000L
            val last = p.first.lines.lastOrNull()?.begin ?: 0L
            val mate = duration > 0L && last > 0L && kotlin.math.abs(last - duration) <= 8_000L
            if (!stale && mate) {
                logger.info("使用暂存的歌词：<$title> ${p.first.lines.size} 行")
                mainHandler.post {
                    if (currentTitle != title) return@post
                    everPublished = true
                    publish(title, artist, duration, mediaId, p.first.lines, p.second, p.first.title)
                }
            }
        }
        preloadedLyric = null
        if (!hasCommittedSong) {
            // 刚启动时没有「上一首」的位置可以比较，待定只会让开播一直不出词，直接提交。
            logger.info("启动后首曲立即提交：$title - $artist")
            pendingSwitchAt = songSwitchAt
            commitSwitch(PendingSwitch(title, artist, duration, mediaId))
            startProgressTicker()
            return
        }
        pendingLyric = null
        pendingSwitchLyric = null
        pendingSwitch = PendingSwitch(title, artist, duration, mediaId)
        pendingSwitchAt = songSwitchAt
        sawPositionSinceSwitch = false
        mainHandler.removeCallbacks(switchFallback)
        mainHandler.postDelayed(switchFallback, SWITCH_HOLD_FALLBACK_MS)
        logger.info("切歌待定：" + title + " - " + artist + "（等位置回调确认音频已切）")
        startProgressTicker()
        return

        knownLyricLines = emptySet()
        lyricFound = false
        fallbackSent = false
        attemptIndex.set(0)

        // 切歌：进度归零，重新起锚
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = true
        lastPushedPlaying = null

        val registered = ensureProvider()
        if (registered == null) {
            // 星流此刻可能还没连上：状态已经推进，绝不能就这么把这首歌丢掉，
            // 否则表现就是「切歌后歌词再也不刷新」。这里照样往下走，
            // 由占位推送与歌词重查在后续 tick / 重试里补上。
            logger.warn("No provider yet for $title - $artist in $processName（稍后补推）")
        } else {
            runCatching { registered.player.setPosition(0L) }
        }

        logger.info("Metadata changed: $title - $artist ($duration) id=$mediaId in $processName")
        // 先推「只有歌曲信息、没有歌词」的快照，立刻清掉上一首的歌词，
        // 拿到真歌词后再覆盖（星流接受同一 id 的后续更新）。
        if (registered != null) publishPlaceholder(title, artist, duration, mediaId)
        publishPending(title, artist, duration, mediaId)
        startProgressTicker()
        mainHandler.removeCallbacks(retryTask)
        mainHandler.post(retryTask)
    }

    private data class PendingSwitch(
        val title: String,
        val artist: String?,
        val duration: Long,
        val mediaId: String?
    )

    /** 音频确实切过来了：清歌词、归零进度，并把待定期间暂存的歌词补推上去 */
    private fun commitSwitch(fallback: PendingSwitch) {
        hasCommittedSong = true
        val held = pendingSwitchLyric
        pendingSwitch = null
        pendingSwitchAt = 0L
        pendingSwitchLyric = null
        knownLyricLines = emptySet()
        lyricFound = false
        fallbackSent = false
        attemptIndex.set(0)
        songSwitchAt = SystemClock.elapsedRealtime()
        songSwitchWall = System.currentTimeMillis()
        lastSongSwitchAt = songSwitchAt
        val now = SystemClock.elapsedRealtime()
        // 触发补做的位置不可信（经常还是上一首的残留），新歌一律从 0 起锚；
        // 真的「继续上次播放位置」时，后续连续上报会在 1s 内纠正过来。
        val start = 0L
        anchorPosition = start
        anchorRealtime = now
        hasAnchor = true
        lastPushedPlaying = null
        val registered = ensureProvider()
        if (registered != null) runCatching { registered.player.setPosition(start) }
        logger.info("Metadata changed: " + fallback.title + " - " + fallback.artist +
            " (" + fallback.duration + ") id=" + fallback.mediaId + " 起锚=" + start)
        if (registered != null) {
            publishPlaceholder(fallback.title, fallback.artist, fallback.duration, fallback.mediaId)
        }
        if (held != null) {
            logger.info("使用待定期间暂存的歌词：" + held.lines.size + " 行")
            publish(fallback.title, fallback.artist, fallback.duration, fallback.mediaId, held.lines, "网络", held.title)
        }
        publishPending(fallback.title, fallback.artist, fallback.duration, fallback.mediaId)
        startProgressTicker()
        mainHandler.removeCallbacks(retryTask)
        mainHandler.post(retryTask)
    }

    /** 切歌后一直等不到位置回调时（部分播放器只在切换瞬间回调一次），按正常切歌收尾 */
    private val switchFallback = Runnable {
        val waiting = pendingSwitch ?: return@Runnable
        if (sawPositionSinceSwitch) return@Runnable
        logger.info("切歌后没有位置回调，直接按切歌收尾：" + waiting.title)
        commitSwitch(waiting)
    }

    private fun onPlaybackStateChanged(state: PlaybackState?) {
        state ?: return
        val registered = ensureProvider() ?: return

        val rawPosition = state.position
        val playing = playingOf(state.state, rawPosition)
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1.0f
        // 播放器上报的 position 是「它上次 setState 那一刻」的值，不是「现在」的值。
        // 直接当锚点用，播放中就会一直慢一截（酷我特别明显：每句歌词都晚一拍才切）。
        // 这里用 updateTime 把它补到当前时刻，和系统媒体通知的算法保持一致。
        val nowMs = SystemClock.elapsedRealtime()
        val reportAge = if (state.lastPositionUpdateTime in 1..nowMs) {
            nowMs - state.lastPositionUpdateTime
        } else -1L
        val position = if (rawPosition >= 0L && playing && reportAge in 0..8_000L) {
            rawPosition + (reportAge.toDouble() * speed.toDouble()).toLong()
        } else rawPosition
        val waiting = pendingSwitch
        if (waiting != null && position >= 0L) {
            sawPositionSinceSwitch = true
            val expected = extrapolatedPosition()
            val delta = if (position > expected) position - expected else expected - position
            val waited = SystemClock.elapsedRealtime() - pendingSwitchAt
            if (delta > SWITCH_HOLD_TOLERANCE_MS || waited > SWITCH_HOLD_TIMEOUT_MS) {
                logger.info(
                    "音频已切过来（位置=" + position + " 上一首推算=" + expected +
                        " 等待=" + waited + "ms），补做切歌"
                )
                mainHandler.removeCallbacks(switchFallback)
                commitSwitch(waiting)
            }
        }

        val predicted = extrapolatedPosition()
        val delta = position - predicted
        if (position >= 0L && (delta > 3000 || delta < -3000 || state.state != lastLoggedState)) {
            lastLoggedState = state.state
            logger.info(
                "位置回调：state=" + state.state + " pos=" + position + " 推算=" + predicted +
                    " 差=" + delta + " 接受=" + shouldAcceptAnchor(position, playing)
            )
        }
        if (position >= 0L && shouldAcceptAnchor(position, playing)) {
            val expected = extrapolatedPosition()
            // 轻微回跳（≤1.5s 抖动）按推算值平滑，保持歌词滚动单调
            val accepted = if (position < expected &&
                position >= expected - BACKWARD_TOLERANCE_MS
            ) expected else position
            anchorPosition = accepted
            anchorRealtime = SystemClock.elapsedRealtime()
            hasAnchor = true
        }
        anchorPlaying = playing
        playbackSpeed = speed

        runCatching { registered.player.setPlaybackState(playing) }
        runCatching { registered.player.setPosition(extrapolatedPosition()) }
        lastPushedPlaying = playing
        startProgressTicker()
    }

    /**
     * 播放器在切歌瞬间（以及个别时刻）会用同一个 MediaSession 补发上一首的残留位置，
     * 实测网易云能差 30s 以上：一旦采纳，整首歌的歌词就全对不上了。规则：
     *  - 和推算值相差 ≤3s：正常抖动 / 小 seek，直接对齐；
     *  - 暂停态：一定是用户自己拖的，直接对齐；
     *  - 快进 / 快退 / 跳转状态：播放器明确在 seek，直接对齐；
     *  - 其余大跳变：必须「连续两次上报、并且按 1x 正常推进」才认。
     *    真「继续上次播放位置」会在 1s 内被纠正，脏数据会被丢掉。
     */
    /** 注意：这个函数目前没有任何调用点，真正决定锚点是否采纳的是 shouldAcceptAnchor。 */
    private fun canAdoptPosition(position: Long, playing: Boolean, state: Int, now: Long): Boolean {
        val expected = extrapolatedPosition()
        val delta = if (position > expected) position - expected else expected - position
        if (delta <= SYNC_TOLERANCE_MS) return true
        if (!playing) return true
        when (state) {
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> return true
        }
        // 酷狗概念版：拖动进度条后只上报一次跳转，等「连续两次一致」会漏掉这一次，
        // 表现为第一次拉进度条歌词对不上、再拉一次才正常。只对这个包放行大跳变。
        if (hostPackage == "com.kugou.android.lite") return true
        if (lastReportAt <= 0L) return false
        val gap = now - lastReportAt
        if (gap !in 1..JUMP_CONFIRM_WINDOW_MS) return false
        val move = position - lastReport
        val moveAbs = if (move > 0) move else -move
        if (moveAbs < JUMP_MIN_MOVE_MS) return false
        val step = (gap.toDouble() * playbackSpeed.toDouble()).toLong()
        val drift = if (move > step) move - step else step - move
        if (drift > JUMP_CONFIRM_SLACK_MS) {
            logger.info("忽略可疑位置：pos=" + position + " 推算=" + expected +
                " 差=" + (position - expected))
            return false
        }
        return true
    }

    /**
     * 播放状态判定：只有真正的暂停 / 停止 / 出错才算「没在播」。
     * 缓冲、连接中、切歌过渡（BUFFERING / CONNECTING / SKIPPING_*）都按继续播处理，
     * 否则酷我这类播放器一缓冲就把进度冻住，表现就是「歌词卡在某一句不动」。
     */
    private fun playingOf(state: Int, position: Long): Boolean = when (state) {
        // 暂停 / 停止 / 出错：只有位置有效才算真的停了；位置未知（-1）多是 seek、
        // 缓冲这类过渡回调（酷我尤其常见），此时保持上一次状态，否则一拖进度条
        // 就把进度冻住，表现就是「歌词卡住 / 对不上歌」。
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_ERROR -> if (position >= 0L) false else anchorPlaying

        PlaybackState.STATE_NONE -> anchorPlaying
        else -> true
    }

    /**
     * 锚点保护：
     *  - 切歌后 2.5s 内只接受从头附近的位置（旧歌残留 position 会让新歌歌词乱跳）；
     *  - 播放中大幅回跳视为脏数据（网易云等会周期性回调带旧 position）；
     *  - 暂停态下的回跳按真实 seek 处理。
     */
    private fun shouldAcceptAnchor(newPosition: Long, newPlaying: Boolean): Boolean {
        if (!hasAnchor) return true
        // 播放器给的显式位置一律采纳：拉进度条、seek、切歌后恢复都靠它。
        // 曾经在这里拒绝「播放中的大幅回跳 / 归零」，结果酷狗、网易云这类很少回调
        // 位置的播放器一旦拖动进度条，锚点永远停在旧位置，歌词再也追不上
        //（用户反馈的「拉进度条就卡住 / 对不上歌」）。
        // 只保留最保守的一条：切歌后极短时间内的、明显属于上一首的大 position。
        val now = SystemClock.elapsedRealtime()
        val sinceSwitch = now - lastSongSwitchAt
        // 这几个包起播/切歌后只上报一次真实位置，等 400ms 保护会把这次位置整条丢掉
        val noGrace = hostPackage == "com.kugou.android.lite" ||
            hostPackage == "com.kugou.android" || hostPackage == "com.music"
        if (!noGrace && sinceSwitch < ANCHOR_SWITCH_GRACE_MS &&
            newPosition > SWITCH_MAX_ACCEPT_MS
        ) return false
        return true
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

    private var lastDiagAt = 0L
    private var lastAlignAt = 0L
    private var publishedLines: List<RichLyricLine> = emptyList()

    private fun tickProgress() {
        val registered = provider ?: return
        if (!hasAnchor) return
        // 诊断：每 10s 打一次当前进度，用来确认 seek 后模块的时钟有没有跟上
        val diagNow = SystemClock.elapsedRealtime()
        if (diagNow - lastAlignAt >= 3_000L && publishedLines.isNotEmpty()) {
            lastAlignAt = diagNow
            val clock = extrapolatedPosition()
            var index = -1
            for (i in publishedLines.indices) {
                if (publishedLines[i].begin <= clock) index = i else break
            }
            logger.info(
                "对齐: clock=" + clock + " " + (if (anchorPlaying) "播放" else "暂停") +
                    " 第" + (index + 1) + "/" + publishedLines.size + "行 [" +
                    (publishedLines.getOrNull(index)?.text ?: "") + "]"
            )
        }
        if (diagNow - lastDiagAt >= 10_000L) {
            lastDiagAt = diagNow
            logger.debug(
                "进度: pos=${extrapolatedPosition()} playing=$anchorPlaying " +
                    "anchor=$anchorPosition speed=$playbackSpeed " +
                    "lyrics=${if (lyricFound) "有" else "无"}"
            )
        }
        runCatching {
            if (lastPushedPlaying != anchorPlaying) {
                registered.player.setPlaybackState(anchorPlaying)
                lastPushedPlaying = anchorPlaying
            }
            registered.player.setPosition(extrapolatedPosition())
        }
    }

    /**
     * 歌曲签名：`歌名|歌手|mediaId`，空值与字面量 "null" 一律折叠成空串。
     *
     * 必须只有这一个入口：以前「同一首歌」分支用 `mediaId ?: currentMediaId`、
     * 「真正切歌」分支用 `mediaId ?: ""`，酷狗这类 mediaId 恒为 null 的播放器
     * 两处会拼出 `…|null` 与 `…|` 两个不同字符串，于是每次 setMetadata（酷狗每秒十几次）
     * 都会把正在查歌词的签名改掉，刚匹配到的歌词立刻被判成
     * `Dropped stale lyric (song changed)` 丢掉 —— 表现就是歌词永远卡住。
     */
    private fun songSignature(title: String?, artist: String?, mediaId: String?): String {
        fun fold(value: String?): String =
            value?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        return fold(title) + "|" + fold(artist) + "|" + fold(mediaId)
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
            var found = try {
                LocalLyricFinder.find(
                    context, target, title, artist, duration, mediaId,
                    songStartedAtMs = songSwitchWall
                ) {
                    logger.info("[${target.displayName}] $it")
                }
            } catch (t: Throwable) {
                logger.error("Local lyric lookup failed: ${t.message}", t)
                null
            }
            if (found == null) {
                // 配方没命中：再扫一遍宿主自己的私有目录。
                // 酷我这类「歌词既不在外部存储固定位置、请求也不走 okhttp」的播放器全靠这条。
                found = try {
                    LocalLyricFinder.findInOwnStorage(
                        context, title, artist, duration, mediaId,
                        songStartedAtMs = songSwitchWall
                    ) {
                        logger.info("[${target.displayName}·私有目录] $it")
                    }
                } catch (t: Throwable) {
                    logger.error("私有目录扫描失败: ${t.message}", t)
                    null
                }
            }
            val lines = found?.lines
            val cost = SystemClock.elapsedRealtime() - started
            if (lines.isNullOrEmpty()) {
                logger.info("No local lyric (attempt #${index + 1}, ${cost}ms): $title - $artist")
            } else {
                if (currentSignature != signature) {
                    logger.info("Dropped stale lyric (song changed)")
                    return@execute
                }
                lyricFound = true
                mainHandler.post {
                    publish(title, artist, duration, mediaId, lines, localRecipe.displayName, found.title)
                }
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
        source: String,
        nameHint: String? = null
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
        // 只推原文：译文 / 音译不主动推送，交给星流自己的翻译开关
        for (line in lines) line.translation = null
        if (pendingSwitchAt != 0L) {
            pendingSwitchLyric = LocalLyric(lines, null, null, null, title)
            logger.info("歌词暂存（音频还没切过来）：" + title + "，" + lines.size + " 行")
            return
        }
        // 标题可疑（歌词行 / 制作信息）时，用歌词文件自带的歌名纠正，绝不把歌词当歌名
        val displayTitle = if (!nameHint.isNullOrBlank() &&
            PlayerTitle.isSuspectTitle(title, knownLyricLines) &&
            !PlayerTitle.isSuspectTitle(nameHint)
        ) nameHint else title
        knownLyricLines = lines.mapNotNullTo(HashSet(lines.size)) {
            PlayerTitle.normalize(it.text).takeIf { text -> text.isNotEmpty() }
        }
        if (displayTitle != title) currentTitle = displayTitle

        val lastEnd = lines.last().end
        val song = Song().apply {
            // 用宿主自己的歌曲 id：SystemUI 侧要靠它把歌词和当前媒体会话对上号
            id = mediaId ?: title
            name = displayTitle
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
        publishedLines = lines
        lastAlignAt = 0L
        logger.info(
            "Lyric published from $source: $displayTitle, ${lines.size} lines " +
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
        if (!belongsToCurrentSong(lyric, at, title, artist, durationMs, mediaId)) return
        logger.info("[$source] 使用缓存歌词（${age}ms 前嗅到）")
        publish(title, artist, durationMs, mediaId, lyric.lines, source, lyric.title)
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
        private const val SWITCH_GRACE_MS = 1_500L

        /** 锚点回跳容忍：≤1.5s 视为正常抖动 */
        private const val BACKWARD_TOLERANCE_MS = 1_500L

        /** position=0 且推算进度超过该值时视为脏数据 */
        private const val ZERO_RESET_IGNORE_MS = 3_000L

        /** 切歌后忽略旧 position 残留的窗口 */
        private const val SONG_SWITCH_IGNORE_MS = 2_500L

        /** 切歌后只忽略这么久的「上一首残留 position」，再久就必须听播放器的 */
        private const val ANCHOR_SWITCH_GRACE_MS = 400L

        /** 切歌窗口内允许的最大 position（超过视为旧歌残留） */
        private const val SWITCH_MAX_ACCEPT_MS = 8_000L
        /** 待定期间「位置和上一首推算值」允许的偏差：超过就认为音频真的切了 */
        private const val SWITCH_HOLD_TOLERANCE_MS = 3_000L
        /** 和推算值相差在这个范围内：正常抖动 / 小跳，直接对齐 */
        private const val SYNC_TOLERANCE_MS = 3_000L
        /** 大跳变确认窗口：两次上报必须挨得这么近 */
        private const val JUMP_CONFIRM_WINDOW_MS = 2_000L
        /** 大跳变确认：两次上报必须真的移动过 */
        private const val JUMP_MIN_MOVE_MS = 2_000L
        /** 大跳变确认：两次上报之间必须按 1x 正常推进（允许偏差） */
        private const val JUMP_CONFIRM_SLACK_MS = 800L

        /** 待定最长时间，超时按切歌处理 */
        private const val SWITCH_HOLD_TIMEOUT_MS = 25_000L
        /** 切歌后完全等不到位置回调时的兜底时间 */
        private const val SWITCH_HOLD_FALLBACK_MS = 1_500L
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
