package io.github.proify.lyricon.xinghe.xposed

import android.app.Application
import android.app.Instrumentation
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Executable

/**
 * MeloYou 歌词提供者核心。
 *
 * 数据来源（均为 MeloYou 自身行为，模块只做只读旁路）：
 *  1. `com.bumptech.glide.manager.j.B(Context, json, "nowPlaying.json")` —— 切歌时写入歌曲信息，rid 为稳定标识。
 *  2. `com.bumptech.glide.manager.j.B(Context, json, "songLyric.json")` —— 歌词数组 [{"lineLyric","time"}]。
 *  3. MediaSession.setMetadata / setPlaybackState —— 时长与播放进度。
 *
 * 稳定性修复（v4.x）：
 *  A. 「歌词突然跳到开头」：
 *     - 发布签名排除 duration，纯时长变化不触发重新发布；
 *     - 切歌后短暂窗口内忽略旧歌残留的 position（MeloYou 切歌时会先发一个旧歌位置），
 *       避免新歌被推到几十秒处（表现为「歌词对不上歌」）；
 *     - 歌词文件晚于歌曲信息到达时，延迟发布，避免先发空歌词再发完整歌词的二次 setSong。
 *  B. 「歌词卡在某句不动」：
 *     - 脏锚点（播放中大幅回跳 / position=0）被拒绝时，不再同步 isPlaying 状态，
 *       避免偶发的脏 PAUSED 状态把推算进度冻结；
 *     - 小幅回跳（≤2s 抖动）接受时按推算值平滑，保持词幕端滚动单调不来回跳。
 */
internal class XingHeLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader
) {

    private val stateLock = Any()

    @Volatile
    private var application: Application? = null

    @Volatile
    private var provider: LyriconProvider? = null

    /** 当前歌曲稳定标识（nowPlaying.json 的 rid） */
    @Volatile
    private var currentSongId: String? = null

    /** 与 currentSongId 绑定的歌词 */
    private var lyricForCurrentSong: List<RichLyricLine>? = null

    /** 当前歌曲元信息（name/artist/duration） */
    private var currentMeta: SongMeta? = null

    private var lastPublishedSignature: String? = null

    /** 当前歌曲所在的 MediaSession 身份（MeloYou 进程里可能同时存在多个会话） */
    @Volatile
    private var activeSessionHash: Int = 0

    /** 当前歌曲的 MediaSession 实例（只读查询它的 extras 用） */
    @Volatile
    private var activeSessionRef: java.lang.ref.WeakReference<MediaSession>? = null

    /** 会话 extras 里还没认领的歌词（歌曲信息未到时先缓存） */
    @Volatile
    private var pendingSessionLyrics: List<RichLyricLine>? = null

    /** 诊断日志节流用 */
    private var diagLastAt: Long = 0L

    /** 最近一次从 MediaMetadata 提取到的时长（毫秒） */
    @Volatile
    private var metadataDurationMs: Long = 0L

    // ---------------- 进度锚点（自行推算真实进度，消除 Auto 漂移） ----------------

    @Volatile
    private var anchorPosition: Long = 0L

    @Volatile
    private var anchorRealtime: Long = 0L

    @Volatile
    private var isPlaying: Boolean = false

    @Volatile
    private var hasAnchor: Boolean = false

    @Volatile
    private var playbackSpeed: Float = 1.0f

    /** 已确认的最后一次有效锚点位置（用于脏数据过滤） */
    @Volatile
    private var confirmedPosition: Long = 0L

    /** 最近一次切歌时刻（用于忽略切歌后旧 position 残留） */
    @Volatile
    private var lastSongSwitchRealtime: Long = 0L

    /** 空歌词延迟发布任务是否已排队 */
    private var delayedPublishQueued = false

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
        hookMeloYouFiles()
        hookMeloYouSessionDiag()
        hookApplicationOnCreate()
        logger.info("All MeloYou hooks installed")
        mainHandler.postDelayed({ ensureStarted() }, 700L)
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

    /** 备用启动点：部分 ROM/加固不走 Instrumentation#callApplicationOnCreate */
    private fun hookApplicationOnCreate() {
        runCatching {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            installProtectiveAfterHook(onCreate, "Application.onCreate") { chain, _ ->
                val app = chain.thisObject as? Application
                if (app != null && app.packageName == Constants.PLAYER_PACKAGE_NAME) {
                    if (application == null) application = app
                    ensureStarted(app)
                }
            }
        }.onFailure { logger.warn("Application.onCreate hook unavailable: ${it.message}") }
    }

    private fun onApplicationCreated(hostApplication: Application) {
        if (application == null) application = hostApplication
        logger.info("MeloYou Application created: ${hostApplication.packageName}")
        ensureStarted(hostApplication)
    }

    private var startAttempts = 0

    /**
     * 惰性启动。
     *
     * 有些 ROM／加固会绕开 Instrumentation#callApplicationOnCreate，
     * 只依赖那个回调会让 Provider 永远不注册（表现为「歌词完全失效」）。
     * 这里不赌单一回调：Instrumentation、Application#onCreate、
     * MeloYou 写文件时传入的 Context 三处都会来启动，谁先到谁生效。
     */
    private fun ensureStarted(context: Context? = null) {
        if (provider != null) return
        val ctx = application ?: context?.applicationContext ?: context ?: currentApplication()
        if (ctx == null) {
            if (startAttempts++ < 20) {
                logger.info("ensureStarted: 暂时拿不到 Context（第 ${startAttempts} 次），稍后重试")
                mainHandler.postDelayed({ ensureStarted() }, 1500L)
            }
            return
        }
        if (application == null) {
            application = ctx.applicationContext as? Application ?: (ctx as? Application)
        }
        if (provider == null) {
            try {
                setupProvider(ctx)
            } catch (throwable: Throwable) {
                logger.error("Provider initialization failed", throwable)
                return
            }
        }
        replayFromDisk(ctx)
        logger.info(
            "MeloYou 启动补读完成：歌曲=${currentMeta?.name} 歌词=${lyricForCurrentSong?.size ?: 0} 行 provider=${provider != null}"
        )
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    /** 拿不到 Application 实例时的兜底（任何进程只要 Application 已创建就能拿到） */
    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    } catch (throwable: Throwable) {
        null
    }

    private fun setupProvider(context: Context) {
        val created = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            // 翻译 / 音译开关归星流管，不再主动打开
            player.setDisplayTranslation(false)
            player.setDisplayRoma(false)
            register()
        }
        provider = created
        logger.info("Lyricon provider registered, player=${context.packageName}")
    }

    // ---------------- 进度推算 ----------------

    /** 当前推算出的播放位置（毫秒） */
    private fun extrapolatedPosition(): Long {
        if (!hasAnchor) return 0L
        if (!isPlaying) return anchorPosition
        val elapsed = SystemClock.elapsedRealtime() - anchorRealtime
        val pos = anchorPosition + (elapsed.toDouble() * playbackSpeed.toDouble()).toLong()
        val duration = metadataDurationMs.coerceAtLeast(currentMeta?.duration ?: 0L)
        return if (duration > 0) pos.coerceAtMost(duration) else pos
    }

    private fun tickProgress() {
        val p = provider ?: return
        if (!hasAnchor) return

        val current = extrapolatedPosition()
        try {
            p.player.setPlaybackState(isPlaying)
            p.player.setPosition(current)
        } catch (throwable: Throwable) {
            logger.error("tickProgress failed", throwable)
        }
    }

    // ---------------- MediaSession（时长 + 播放状态 + 进度） ----------------

    private fun hookMediaSession() {
        val metadataMethod = MediaSession::class.java.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java
        )
        installProtectiveAfterHook(metadataMethod, "MediaSession.setMetadata") { chain, _ ->
            onMetadataChanged(
                chain.thisObject as? MediaSession,
                chain.args.getOrNull(0) as? MediaMetadata
            )
        }

        val playbackMethod = MediaSession::class.java.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java
        )
        installProtectiveAfterHook(playbackMethod, "MediaSession.setPlaybackState") { chain, _ ->
            onPlaybackStateChanged(
                chain.thisObject as? MediaSession,
                chain.args.getOrNull(0) as? PlaybackState
            )
        }

        // MeloYou 不走「标准」歌词通道：它把当前歌词直接塞进 MediaSession 的 extras
        // （lyric_timestamps 毫秒数组 + lyric_texts 文本数组），这是它自己认定的当前歌曲歌词。
        // songLyric.json 只留「最近一首」，会话 extras 才是随歌曲信息一起刷新的那份。
        val extrasMethod = MediaSession::class.java.getDeclaredMethod(
            "setExtras",
            Bundle::class.java
        )
        installProtectiveAfterHook(extrasMethod, "MediaSession.setExtras") { chain, _ ->
            onSessionExtras(
                chain.thisObject as? MediaSession,
                chain.args.getOrNull(0) as? Bundle
            )
        }
    }

    /** 最近一次 MediaMetadata 的歌曲签名（歌名|歌手） */
    @Volatile
    private var lastMetadataSignature: String? = null

    /** 上一次记录过的歌曲信息（同一首歌会反复写 nowPlaying.json，避免刷屏） */
    private var lastSongInfoKey: String? = null

    /** MeloYou 歌词文件重试计数 */
    private var meloAttempts = 0

    private val meloRetry = Runnable { refreshFromDisk() }

    /** 重读循环是否正在跑（避免几条触发路径互相重置计数） */
    private var meloRetryActive = false

    /** 主动重读磁盘上的歌曲信息 / 歌词；restart=true 表示换歌，强制重新开始计数 */
    private fun scheduleMeloRetry(restart: Boolean = false) {
        if (meloRetryActive && !restart) return
        meloAttempts = 0
        meloRetryActive = true
        mainHandler.removeCallbacks(meloRetry)
        mainHandler.post(meloRetry)
    }

    /**
     * MeloYou 的触发点。
     *
     * 它只在「真正拉到新歌词」时才重写 songLyric.json，同一首歌再次播放时不会重写，
     * 因此不能只依赖文件写入回调；这里以 MediaSession 的歌名/歌手变化作为切歌信号，
     * 主动去读它自己导出的 nowPlaying.json（歌曲信息）与 songLyric.json（歌词）。
     */
    private fun onMetadataChanged(session: MediaSession?, metadata: MediaMetadata?) {
        metadata ?: return
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) metadataDurationMs = duration

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() && it != "未知歌曲" } ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && it != "未知歌手" }

        ensureStarted()
        // 歌词行 / 制作信息行被当成标题时不能算切歌（酷狗、QQ 都会这么干）
        if (PlayerTitle.isSuspectTitle(title) && currentMeta != null) {
            logger.debug("忽略可疑标题（疑似歌词行 / 制作信息）：$title")
            return
        }
        rememberActiveSession(session)
        val signature = "$title|$artist"
        if (signature == lastMetadataSignature) return
        lastMetadataSignature = signature
        logger.info("MeloYou metadata changed: $title - $artist")

        var sameSong = false
        synchronized(stateLock) {
            sameSong = currentMeta?.name?.let { normalize(it) == normalize(title) } == true
            if (!sameSong) {
                lyricForCurrentSong = null
                currentSongId = null
                lastPublishedSignature = null
                lastSongSwitchWall = System.currentTimeMillis()
            pendingSessionLyrics = null
            }
            currentMeta = SongMeta(
                id = if (sameSong) currentMeta?.id else null,
                name = title,
                artist = artist ?: currentMeta?.artist,
                duration = if (duration > 0) duration else (currentMeta?.duration ?: 0L)
            )
        }

        // 同一首歌重发元数据（冷启动继续播上一首必定发生）不能推空歌词占位：
        // 那会把刚落地的歌词清掉，而且签名去重会让它再也补不回来（表现为「卡在一句不动」）。
        if (!sameSong || !isLyricReady()) {
            publishPlaceholder()
        } else {
            logger.debug("同歌元数据到达：已有歌词，跳过占位快照")
        }
        scheduleMeloRetry(restart = true)
    }

    /**
     * 从磁盘读取 MeloYou 自己写的歌曲信息与歌词。
     * songLyric.json 里只保存「最近一首」，所以先核对歌名，避免把上一首的歌词推成当前歌曲。
     */
    private fun refreshFromDisk() {
        val context = application ?: return
        try {
            val nowPlaying = readAppFile(context, Constants.NOW_PLAYING_FILE)
            val fileSong = nowPlaying
                ?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }
                ?.takeIf { it.isNotBlank() && it != "null" }
            val want = currentMeta?.name
            val sameSong = want.isNullOrBlank() || fileSong.isNullOrBlank() ||
                normalize(fileSong).contains(normalize(want)) ||
                normalize(want).contains(normalize(fileSong))

            if (sameSong) {
                nowPlaying?.let { onNowPlayingWritten(it) }
                readAppFile(context, Constants.SONG_LYRIC_FILE)?.let { onLyricsWritten(context, it) }
            } else {
                logger.info("MeloYou 歌词文件属于其它歌曲（当前=$want, 文件=$fileSong），继续等待")
            }
        } catch (throwable: Throwable) {
            logger.error("MeloYou refreshFromDisk failed", throwable)
        }

        // 会话 extras 里 MeloYou 自己维护的当前歌词（比磁盘文件可靠）
        pendingSessionLyrics?.let { lines ->
            if (!currentMeta?.name.isNullOrBlank() && belongsToCurrentSong(lines)) {
                pendingSessionLyrics = null
                synchronized(stateLock) { lyricForCurrentSong = lines }
                publishIfReady()
            }
        }

        if (!isLyricReady() && meloAttempts < MELO_RETRY_DELAYS_MS.size) {
            val delay = MELO_RETRY_DELAYS_MS[meloAttempts]
            meloAttempts++
            mainHandler.postDelayed(meloRetry, delay)
        } else {
            meloRetryActive = false
        }
    }

    private fun isLyricReady(): Boolean =
        synchronized(stateLock) { !lyricForCurrentSong.isNullOrEmpty() }

    /** 归一化：只保留字母/数字/中日韩文字，忽略大小写与符号 */
    private fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }


    private fun onPlaybackStateChanged(session: MediaSession?, state: PlaybackState?) {
        state ?: return

        ensureStarted()
        // MeloYou 切歌/起播瞬间会同时刷新不止一个 MediaSession 的进度，
        // 只有「当前歌曲所在的那个会话」才可信，否则会把上一首或预载流的进度当成当前进度。
        if (DIAG_LOG) {
            logger.info(
                "DIAG state: session=" + System.identityHashCode(session) +
                    " pos=" + state.position + " state=" + state.state +
                    " speed=" + state.playbackSpeed + " upd=" + state.lastPositionUpdateTime
            )
        }
        if (!isActiveSession(session)) {
            logger.debug("忽略非当前 MediaSession 的进度：pos=${state.position}")
            return
        }
        val position = state.position
        val newPlaying = playingOf(state.state, position)

        if (position >= 0L) {
            // 播放器给的显式位置一律采纳。以前这里会拒绝「和推算值差太远」的位置，
            // 结果 MeloYou 这类播放器一拖进度条，锚点就永远停在旧位置，
            // 歌词越跑越偏（用户反馈的「拉了进度条歌词就对不上歌」）。
            val expected = extrapolatedPosition()
            // 轻微回跳（≤2s 抖动）时按推算值平滑，保持滚动单调，避免来回跳
            val accepted = if (hasAnchor && position < expected &&
                position >= expected - BACKWARD_TOLERANCE_MS
            ) {
                expected
            } else {
                position
            }
            anchorPosition = accepted
            anchorRealtime = SystemClock.elapsedRealtime()
            confirmedPosition = accepted
            hasAnchor = true
            isPlaying = newPlaying
            playbackSpeed = state.playbackSpeed.takeIf { it > 0f } ?: 1.0f
            logger.debug(
                "Anchor accepted: position=$position playing=$newPlaying speed=$playbackSpeed"
            )
        } else {
            isPlaying = newPlaying
        }
        // 立即同步一次，让状态/seek 即时生效
        tickProgress()
    }

    /**
     * 锚点单调性保护：
     *  - 无锚点 -> 接受；
     *  - 切歌后短暂窗口内出现大幅前进 -> 拒绝（旧歌残留 position）；
     *  - 正常前进 -> 接受；
     *  - 轻微回跳（≤2s，seek 抖动）-> 接受；
     *  - 播放中大幅回跳（尤其归零）-> 拒绝，视为脏数据。
     */
    /**
     * 播放状态判定：
     *  - 只有真正的暂停 / 停止 / 出错才算「没在播」；
     *  - 缓冲 / 连接 / 切歌过渡（BUFFERING / CONNECTING / SKIPPING_*）都按继续播处理，
     *    否则 MeloYou、酷我一缓冲就把进度冻住，表现就是「拉了进度条歌词就卡住」；
     *  - 暂停 / 停止但位置未知（-1）：保持上一次状态，这类回调多出现在 seek 过渡中。
     */
    private fun playingOf(state: Int, position: Long): Boolean = when (state) {
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_ERROR -> if (position >= 0L) false else isPlaying

        PlaybackState.STATE_NONE -> isPlaying
        else -> true
    }

    private fun shouldAcceptAnchor(newPosition: Long, newPlaying: Boolean): Boolean {
        if (!hasAnchor) return true
        // 同 LocalLyricProvider：显式位置一律采纳，否则拖动进度条后
        // 歌词会永远停在旧锚点上（「卡住 / 对不上歌」）。
        val now = SystemClock.elapsedRealtime()
        if (now - lastSongSwitchRealtime < ANCHOR_SWITCH_GRACE_MS &&
            newPosition > SWITCH_MAX_ACCEPT_MS
        ) return false
        return true
    }

    private fun isRecentSongSwitch(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now - lastSongSwitchRealtime < SONG_SWITCH_IGNORE_MS
    }

    // ---------------- MeloYou 文件写入管线 ----------------

    private fun hookMeloYouFiles() {
        try {
            val fileUtilClass = Class.forName("com.bumptech.glide.manager.j", false, classLoader)
            val writeMethod = fileUtilClass.getDeclaredMethod(
                "B",
                Context::class.java,
                String::class.java,
                String::class.java
            )
            installProtectiveAfterHook(writeMethod, "j.B(file write)") { chain, _ ->
                val context = chain.args.getOrNull(0) as? Context
                val content = chain.args.getOrNull(1) as? String
                val filename = chain.args.getOrNull(2) as? String
                when (filename) {
                    Constants.NOW_PLAYING_FILE -> onNowPlayingWritten(content)
                    Constants.SONG_LYRIC_FILE -> onLyricsWritten(context, content)
                }
            }
        } catch (throwable: Throwable) {
            logger.error("Hook j.B unavailable", throwable)
        }
    }

    private fun onNowPlayingWritten(content: String?) {
        if (content.isNullOrBlank()) return
        val obj = runCatching { JSONObject(content) }.getOrNull() ?: return
        val rid = obj.optString("rid").takeIf { it.isNotBlank() && it != "null" }
        val name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" && it != "未知歌曲" }
        val artist = obj.optString("artist").takeIf { it.isNotBlank() && it != "null" && it != "未知歌手" }

        var songChanged = false
        synchronized(stateLock) {
            if (rid != null && rid != currentSongId) {
                songChanged = currentSongId != null && currentSongId != rid
                currentSongId = rid
                lyricForCurrentSong = null
            }
            val fileDuration = obj.optLong("duration", 0L)
            val effectiveDuration = when {
                metadataDurationMs > 0 -> metadataDurationMs
                fileDuration > 0 -> fileDuration
                else -> currentMeta?.duration ?: 0L
            }
            currentMeta = SongMeta(
                id = rid ?: currentSongId,
                name = name ?: currentMeta?.name,
                artist = artist ?: currentMeta?.artist,
                duration = effectiveDuration
            )
        }

        val infoKey = "$rid|$name|$artist|$songChanged"
        if (infoKey != lastSongInfoKey) {
            lastSongInfoKey = infoKey
            logger.info("MeloYou 歌曲信息：id=$rid name=$name artist=$artist 换歌=$songChanged")
        }
        if (!isLyricReady()) scheduleMeloRetry()

        // 切歌：记录切歌时刻（用于忽略旧 position 残留），并重置进度锚点归零
        if (songChanged) {
            lastSongSwitchRealtime = SystemClock.elapsedRealtime()
            lastSongSwitchWall = System.currentTimeMillis()
            pendingSessionLyrics = null
            anchorPosition = 0L
            anchorRealtime = SystemClock.elapsedRealtime()
            hasAnchor = true
            confirmedPosition = 0L
            isPlaying = true
            try {
                provider?.player?.setPosition(0L)
            } catch (throwable: Throwable) {
                logger.error("Reset position failed", throwable)
            }
            publishPlaceholder()
            logger.debug("Song changed, reset anchor position to 0")
        }

        publishIfReady()
    }

    private fun onLyricsWritten(context: Context?, content: String?) {
        val text = content ?: run {
            context?.let { readAppFile(it, Constants.SONG_LYRIC_FILE) }
        } ?: return

        val lines = parseMeloYouLyrics(text) ?: return

        if (!belongsToCurrentSong(lines)) {
            logger.info("忽略不匹配的 MeloYou 歌词（当前=${currentMeta?.name}）")
            return
        }
        synchronized(stateLock) {
            lyricForCurrentSong = lines
        }
        publishIfReady()
    }

    private fun replayFromDisk(context: Context) {
        val nowPlaying = readAppFile(context, Constants.NOW_PLAYING_FILE)
        val songLyric = readAppFile(context, Constants.SONG_LYRIC_FILE)
        logger.info(
            "MeloYou 启动补读：nowPlaying=" + (nowPlaying?.length ?: -1) + "B songLyric=" +
                (songLyric?.length ?: -1) + "B 已认歌曲=" + currentMeta?.name
        )
        if (!nowPlaying.isNullOrBlank()) onNowPlayingWritten(nowPlaying)
        if (!songLyric.isNullOrBlank()) onLyricsWritten(context, songLyric)
    }

    // ---------------- 数据装配与发布 ----------------

    /** 上一次切歌的挂钟时刻（用于判断 songLyric.json 是否是本次切歌后写的） */
    @Volatile
    private var lastSongSwitchWall: Long = 0L

    /** 占位快照签名，避免重复推送 */
    private var lastPlaceholderSignature: String? = null

    /** 切歌时先推「只有歌曲信息、没有歌词」的快照，清掉上一首的歌词 */
    private fun publishPlaceholder() {
        val p = provider ?: return
        val meta = synchronized(stateLock) { currentMeta } ?: return
        val id = meta.id ?: return
        val signature = "$id|${meta.name}|${meta.artist}"
        if (signature == lastPlaceholderSignature) return
        lastPlaceholderSignature = signature
        val song = Song().apply {
            this.id = "meloyou:$id"
            this.name = meta.name
            this.artist = meta.artist
            this.duration = meta.duration
            this.lyrics = emptyList()
        }
        val ok = runCatching { p.player.setSong(song) }.getOrDefault(false)
        // 占位快照不是「歌词发布」：清掉发布签名，之后歌词一到（会话 extras / 磁盘文件）
        // 必须还能补推一次，否则星流那边就只剩这个没有歌词的空快照。
        lastPublishedSignature = null
        logger.info("切歌占位：$id ${meta.name}, ok=$ok")
    }

    /**
     * 判断 songLyric.json 是否属于当前歌曲。该文件只保存最近一首，
     * 切歌瞬间可能还是上一首的内容，因此满足任一条才认：
     *  - 首行是「歌名 - 歌手」（MeloYou 会给第一行写歌名）且包含当前歌名核心词；
     *  - nowPlaying.json 里的歌名与当前一致；
     *  - 文件是在本次切歌之后写入的（拿到新歌词时它会立刻重写这个文件）。
     */
    private fun belongsToCurrentSong(lines: List<RichLyricLine>): Boolean {
        val want = currentMeta?.name ?: return true
        val wantKey = coreTitle(want)
        if (wantKey.isEmpty()) return true

        // 1) 首行自带歌名：MeloYou 会把「歌名 - 歌手」写在第一行，最可靠
        val head = normalize(lines.firstOrNull()?.text)
        if (head.contains(wantKey)) {
            logger.debug("MeloYou 歌词首行匹配：<" + head + "> ~ <" + wantKey + ">")
            return true
        }

        val context = application
        val songId = currentSongId
        if (context != null) {
            val lyricFile = runCatching {
                context.getFileStreamPath(Constants.SONG_LYRIC_FILE)
            }.getOrNull()
            val stamp = if (lyricFile != null && lyricFile.isFile) {
                lyricFile.lastModified() to lyricFile.length()
            } else null

            // 2) 同一首歌已经验证过这个文件（MeloYou 对同一首歌不会重写歌词文件）
            if (stamp != null && songId != null && acceptedLyricFiles[songId] == stamp) return true

            // 3) 歌词文件不早于 nowPlaying.json：说明它就是随本次歌曲信息一起写的
            val nowPlayingAt = runCatching {
                context.getFileStreamPath(Constants.NOW_PLAYING_FILE).lastModified()
            }.getOrDefault(0L)
            if (lyricFile != null && lyricFile.isFile &&
                lyricFile.lastModified() >= nowPlayingAt - FILE_FRESH_TOLERANCE_MS
            ) {
                if (stamp != null && songId != null) acceptedLyricFiles[songId] = stamp
                return true
            }

            // 其余情况：文件还是上一首的（MeloYou 没重写），等它写新的
            logger.info(
                "MeloYou 歌词文件属于其它歌曲（当前=$want, 文件时间=${lyricFile?.lastModified()}, " +
                    "歌曲信息时间=$nowPlayingAt）"
            )
            return false
        }
        return System.currentTimeMillis() - lastSongSwitchWall <= FILE_FRESH_WINDOW_MS
    }

    /** 已为某首歌验证过的歌词文件指纹（mtime + 大小） */
    private val acceptedLyricFiles = HashMap<String, Pair<Long, Long>>()

    /** 歌名核心词：去掉《》与括号说明、后半段副标题 */
    private fun coreTitle(raw: String): String {
        var text = raw
        val cut = text.indexOfFirst {
            it == '-' || it == '–' || it == '_' || it == '《' ||
                it == '(' || it == '（' || it == '[' || it == '【'
        }
        if (cut > 1) text = text.substring(0, cut)
        return normalize(text)
    }

    private fun publishIfReady() {
        val p = provider ?: return
        val meta: SongMeta
        val lyrics: List<RichLyricLine>?
        synchronized(stateLock) {
            meta = currentMeta ?: return
            if (meta.id.isNullOrBlank()) return
            lyrics = lyricForCurrentSong
        }

        // 歌词还没写入（MeloYou 先写歌曲信息、后写歌词，间隔通常 <0.5s）：
        // 延迟一小段时间再发布，避免「先空歌词、再完整歌词」的两次 setSong
        // 导致词幕端误判新歌、把歌词位置重置回开头。
        if (lyrics.isNullOrEmpty()) {
            scheduleDelayedPublish()
            schedulePlaceholderFallback()
            return
        }

        // 签名排除 duration：仅元数据（时长）变化不触发重新发布，避免词幕端误判新歌
        val signature = buildString {
            append(meta.id).append('|')
            append(meta.name).append('|')
            append(meta.artist).append('|')
            append(lyrics?.size ?: -1).append('|')
            append(lyrics?.lastOrNull()?.text)
        }
        if (signature == lastPublishedSignature) return
        lastPublishedSignature = signature

        // 只推原文：译文 / 音译交给星流的开关
        lyrics?.forEach { it.translation = null }

        val song = Song().apply {
            this.id = meta.id!!.let { "meloyou:$it" }
            this.name = meta.name
            this.artist = meta.artist
            this.duration = meta.duration
            this.lyrics = lyrics
        }

        try {
            p.player.setSong(song)
            logger.debug(
                "Published: id=${song.id}, name=${song.name}, artist=${song.artist}, " +
                    "duration=${song.duration}, lyricLines=${lyrics?.size ?: 0}"
            )
        } catch (throwable: Throwable) {
            logger.error("setSong failed", throwable)
        }
    }
    /**
     * 歌曲信息已经拿到、歌词却迟迟不到时，先推一条「只有歌曲信息」的快照。
     *
     * 冷启动继续播上一首时最容易踩到：MeloYou 不重发元数据，也可能不再重写歌词文件，
     * 星流若一直没收到这个歌曲 id，就会把上一首的歌词一直挂在胶囊上（用户反馈的「卡在一句不动」）。
     * 同一首歌只在没有歌词时推一次，因此不会把已经显示出来的歌词清掉。

     */
    private val placeholderFallback = Runnable {
        val meta = synchronized(stateLock) { currentMeta } ?: return@Runnable
        val id = meta.id ?: return@Runnable
        if (isLyricReady()) return@Runnable
        if (lastPublishedSignature?.startsWith("$id|") == true) return@Runnable
        if (lastPlaceholderSignature == "$id|" + meta.name + "|" + meta.artist) return@Runnable
        logger.info("歌词未到，先推歌曲信息占位：" + id + " " + meta.name)
        publishPlaceholder()
    }

    private fun schedulePlaceholderFallback() {
        mainHandler.removeCallbacks(placeholderFallback)
        mainHandler.postDelayed(placeholderFallback, PLACEHOLDER_FALLBACK_MS)
    }

    private fun scheduleDelayedPublish() {
        if (delayedPublishQueued) return
        delayedPublishQueued = true
        mainHandler.postDelayed({
            delayedPublishQueued = false
            publishIfReady()
        }, DELAYED_PUBLISH_MS)
    }

    /** 解析 MeloYou songLyric.json -> RichLyricLine 列表；无有效歌词返回 null */
    private fun parseMeloYouLyrics(content: String): List<RichLyricLine>? {
        return try {
            val array = JSONArray(content)
            val items = ArrayList<Pair<Long, String>>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val text = obj.optString("lineLyric").trim()
                if (text.isEmpty()) continue
                if (PLACEHOLDER_PATTERNS.any { text.contains(it) }) continue
                val seconds = obj.optString("time", "0").toDoubleOrNull() ?: 0.0
                items.add((seconds * 1000).toLong() to text)
            }
            if (items.isEmpty()) return null
            items.sortBy { it.first }
            items.mapIndexed { index, (begin, text) ->
                val end = items.getOrNull(index + 1)?.first ?: (begin + 3000L)
                RichLyricLine(begin = begin, end = end, text = text)
            }
        } catch (throwable: Throwable) {
            logger.error("Parse songLyric.json failed: ${throwable.message}")
            null
        }
    }

    private fun readAppFile(context: Context, name: String): String? {
        return try {
            context.openFileInput(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (throwable: Throwable) {
            null
        }
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
                    } catch (throwable: Throwable) {
                        logger.error("After-hook failed: $description", throwable)
                    }
                    result
                }
            logger.debug("Installed protective hook: $description")
        } catch (throwable: Throwable) {
            logger.error("Unable to install hook: $description", throwable)
        }
    }

    private data class SongMeta(
        val id: String?,
        val name: String?,
        val artist: String?,
        val duration: Long = 0L
    )

    // ---------------- MediaSession 会话歌词（MeloYou 专属） ----------------

    /** 记下当前歌曲所在的 MediaSession（MeloYou 进程里不止一个会话在刷新） */
    private fun rememberActiveSession(session: MediaSession?) {
        if (session == null) return
        activeSessionRef = java.lang.ref.WeakReference(session)
        val hash = System.identityHashCode(session)
        if (hash != activeSessionHash) {
            activeSessionHash = hash
            logger.debug("当前 MediaSession 会话：$hash")
        }
    }

    /** 该会话是否是当前歌曲所在的会话（还没认出来之前一律接受） */
    private fun isActiveSession(session: MediaSession?): Boolean {
        val current = activeSessionRef?.get() ?: return true
        return session == null || session === current
    }

    /**
     * MeloYou 把当前歌词写进 MediaSession 的 extras：
     *  - lyric_timestamps：long[]（毫秒），lyric_texts：String[]，即当前歌曲整首歌词；
     *  - current_lyric / current_lyric_time / current_lyric_index：当前这一句。
     * 这是它自己认定的「当前歌曲」歌词，优先用它，磁盘文件只当兜底。
     */
    private fun onSessionExtras(session: MediaSession?, extras: Bundle?) {
        extras ?: return
        if (!isActiveSession(session)) {
            logger.debug("忽略非当前 MediaSession 的歌词 extras")
            return
        }
        val lines = parseSessionLyrics(extras) ?: return
        if (DIAG_LOG) {
            logger.info(
                "DIAG extras: 行数=" + lines.size + " 首行=" + lines.firstOrNull()?.text +
                    " 末行=" + lines.lastOrNull()?.text +
                    " 当前句=" + extras.getString("current_lyric") +
                    " 当前句时间=" + extras.getLong("current_lyric_time") +
                    " 当前句下标=" + extras.getInt("current_lyric_index", -1)
            )
        }
        val current = synchronized(stateLock) { currentMeta }
        if (current?.name.isNullOrBlank()) {
            pendingSessionLyrics = lines
            logger.info("等待歌曲信息，先缓存会话歌词（首行=${lines.firstOrNull()?.text}）")
            return
        }
        if (!belongsToCurrentSong(lines)) {
            logger.info(
                "忽略不匹配的 MeloYou 会话歌词（当前=${current?.name}，会话首行=${lines.firstOrNull()?.text}）"
            )
            return
        }
        val changed = synchronized(stateLock) {
            val old = lyricForCurrentSong
            if (old != null && old.size == lines.size &&
                old.lastOrNull()?.text == lines.lastOrNull()?.text
            ) {
                false
            } else {
                lyricForCurrentSong = lines
                true
            }
        }
        if (changed) {
            pendingSessionLyrics = null
            logger.info("MeloYou 会话歌词：${lines.size} 行（首行=${lines.firstOrNull()?.text}）")
        }
        publishIfReady()
    }

    /** 解析会话 extras 里的歌词数组；没有歌词返回 null */
    private fun parseSessionLyrics(extras: Bundle): List<RichLyricLine>? {
        val times = extras.getLongArray("lyric_timestamps") ?: return null
        val texts = extras.getStringArray("lyric_texts") ?: return null
        val count = minOf(times.size, texts.size)
        if (count <= 0) return null
        val items = ArrayList<Pair<Long, String>>(count)
        for (i in 0 until count) {
            val text = texts[i]?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (PLACEHOLDER_PATTERNS.any { text.contains(it) }) continue
            items.add(times[i] to text)
        }
        if (items.isEmpty()) return null
        items.sortBy { it.first }
        return items.mapIndexed { index, (begin, text) ->
            val end = items.getOrNull(index + 1)?.first ?: (begin + 3000L)
            RichLyricLine(begin = begin, end = end, text = text)
        }
    }

    /** 诊断：MeloYou 自己的播放进度取值点（只读旁路，交付前移除） */
    private fun hookMeloYouSessionDiag() {
        if (!DIAG_LOG) return
        runCatching {
            val method = Class.forName("H1.a", false, classLoader).getDeclaredMethod("a")
            installProtectiveAfterHook(method, "DIAG H1.a.a") { _, result ->
                val value = (result as? Long) ?: -1L
                val now = SystemClock.elapsedRealtime()
                if (now - diagLastAt > 1500L) {
                    diagLastAt = now
                    logger.info("DIAG 播放器进度 a()=$value")
                }
            }
        }.onFailure { logger.warn("DIAG 无法挂钩播放器进度：${it.message}") }
    }
    companion object {


        /** 诊断开关（交付前必须置回 false） */
        private const val DIAG_LOG = true

        /** 进度推送间隔：足够密以保持歌词跟手，又不至于过度 IPC（毫秒） */
        private const val TICK_INTERVAL_MS = 48L

        /** songLyric.json 在切歌后这段时间内被写入就认作当前歌曲 */
        private const val FILE_FRESH_WINDOW_MS = 6000L

        /** 锚点回跳容忍：≤2s 视为正常抖动/小幅 seek */
        private const val BACKWARD_TOLERANCE_MS = 2000L

        /** position=0 且推算进度超过该值时视为脏数据 */
        private const val ZERO_RESET_IGNORE_MS = 3000L

        /** 切歌后忽略旧 position 残留的窗口 */
        private const val SONG_SWITCH_IGNORE_MS = 2500L

        /** 切歌后只忽略这么久的「上一首残留 position」 */
        private const val ANCHOR_SWITCH_GRACE_MS = 400L

        /** 切歌窗口内允许的最大 position（超过视为旧歌残留） */
        private const val SWITCH_MAX_ACCEPT_MS = 8000L

        /**
         * MeloYou 歌词文件重试节奏（毫秒）。MeloYou 是「先切歌、后拉歌词」，
         * 缓存命中几乎立刻落盘，首次在线拉取可能要几十秒，
         * 因此退避到约 2 分钟——退得太早就是「歌词刷新不及时」。
         */
        private val MELO_RETRY_DELAYS_MS = longArrayOf(
            800L, 1200L, 1800L, 2500L, 3500L, 5000L, 7000L,
            9000L, 12000L, 16000L, 20000L, 25000L, 30000L, 30000L
        )

        /** 歌词文件比 nowPlaying.json 新（含容差）→ 一定属于当前歌曲 */
        private const val FILE_FRESH_TOLERANCE_MS = 1500L

        /** 歌词未到时延迟发布的时长 */
        private const val DELAYED_PUBLISH_MS = 1200L

        /** 歌曲信息已到、歌词迟迟未到时，先推一条占位快照的延迟 */
        private const val PLACEHOLDER_FALLBACK_MS = 1500L

        private val PLACEHOLDER_PATTERNS = listOf(
            "歌曲暂无歌词",
            "暂无歌词",
            "请欣赏音乐",
            "纯音乐",
            "该歌曲为纯音乐"
        )
    }
}
