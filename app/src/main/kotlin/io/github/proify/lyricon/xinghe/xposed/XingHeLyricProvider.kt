package io.github.proify.lyricon.xinghe.xposed

import android.app.Application
import android.app.Instrumentation
import android.content.Context
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
        logger.info("All MeloYou hooks installed")
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
        logger.info("MeloYou Application created: ${hostApplication.packageName}")
        try {
            setupProvider(hostApplication)
        } catch (throwable: Throwable) {
            logger.error("Provider initialization failed", throwable)
            return
        }
        replayFromDisk(hostApplication)
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
        logger.info("Lyricon provider registered, player=${hostApplication.packageName}")
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
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) {
            metadataDurationMs = duration
            synchronized(stateLock) {
                val meta = currentMeta
                if (meta != null && meta.duration <= 0L) {
                    currentMeta = meta.copy(duration = duration)
                }
            }
            // 纯时长变化不触发重新发布（避免词幕端误判为新歌导致歌词跳回开头）
        }
    }

    private fun onPlaybackStateChanged(state: PlaybackState?) {
        state ?: return

        val position = state.position
        val newPlaying = state.state == PlaybackState.STATE_PLAYING

        if (position >= 0L) {
            if (shouldAcceptAnchor(position, newPlaying)) {
                val expected = extrapolatedPosition()
                // 轻微回跳（≤2s 抖动）时按推算值平滑，保持滚动单调，避免来回跳
                val accepted = if (position < expected && position >= expected - BACKWARD_TOLERANCE_MS) {
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
                // 脏锚点：完全忽略（不采纳位置，也不改动播放状态），保持原锚点继续推算
                logger.debug(
                    "Anchor REJECTED (dirty): position=$position playing=$newPlaying " +
                        "extrapolated=${extrapolatedPosition()}"
                )
            }
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
    private fun shouldAcceptAnchor(newPosition: Long, newPlaying: Boolean): Boolean {
        if (!hasAnchor) return true
        // 切歌后 2.5s 内：只接受从头附近的位置，旧歌残留的大 position 一律忽略
        if (isRecentSongSwitch() && newPosition > SWITCH_MAX_ACCEPT_MS) return false
        val expected = extrapolatedPosition()
        // 进度几乎没动（±2s 内）或前进 -> 正常
        if (newPosition >= expected - BACKWARD_TOLERANCE_MS) return true
        // 播放中大幅回跳 -> 拒绝（同时不改动 isPlaying，避免进度冻结）
        if (isPlaying && newPlaying) return false
        // 暂停态下回跳：可能是真实 seek；若回跳到 0 且之前已播很久，仍按脏数据处理
        if (newPosition <= 0L && expected > ZERO_RESET_IGNORE_MS) return false
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

        // 切歌：记录切歌时刻（用于忽略旧 position 残留），并重置进度锚点归零
        if (songChanged) {
            lastSongSwitchRealtime = SystemClock.elapsedRealtime()
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
            logger.debug("Song changed, reset anchor position to 0")
        }

        publishIfReady()
    }

    private fun onLyricsWritten(context: Context?, content: String?) {
        val text = content ?: run {
            context?.let { readAppFile(it, Constants.SONG_LYRIC_FILE) }
        } ?: return

        val lines = parseMeloYouLyrics(text) ?: return

        synchronized(stateLock) {
            lyricForCurrentSong = lines
        }
        publishIfReady()
    }

    private fun replayFromDisk(context: Context) {
        readAppFile(context, Constants.NOW_PLAYING_FILE)?.let(::onNowPlayingWritten)
        readAppFile(context, Constants.SONG_LYRIC_FILE)?.let { onLyricsWritten(context, it) }
    }

    // ---------------- 数据装配与发布 ----------------

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

    companion object {
        /** 进度推送间隔：足够密以保持歌词跟手，又不至于过度 IPC（毫秒） */
        private const val TICK_INTERVAL_MS = 400L

        /** 锚点回跳容忍：≤2s 视为正常抖动/小幅 seek */
        private const val BACKWARD_TOLERANCE_MS = 2000L

        /** position=0 且推算进度超过该值时视为脏数据 */
        private const val ZERO_RESET_IGNORE_MS = 3000L

        /** 切歌后忽略旧 position 残留的窗口 */
        private const val SONG_SWITCH_IGNORE_MS = 2500L

        /** 切歌窗口内允许的最大 position（超过视为旧歌残留） */
        private const val SWITCH_MAX_ACCEPT_MS = 8000L

        /** 歌词未到时延迟发布的时长 */
        private const val DELAYED_PUBLISH_MS = 1200L

        private val PLACEHOLDER_PATTERNS = listOf(
            "歌曲暂无歌词",
            "暂无歌词",
            "请欣赏音乐",
            "纯音乐",
            "该歌曲为纯音乐"
        )
    }
}
