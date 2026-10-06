package io.github.proify.lyricon.xinghe.lyric

import io.github.proify.lyricon.xinghe.settings.ModulePrefs
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
import java.lang.ref.WeakReference
import java.lang.reflect.Executable

/**
 * MeloYou 专用歌词提供者（专属文件，重写后状态机极简）。
 *
 * MeloYou 的数据源优先级（全部只读）：
 *   1. MediaSession.setExtras 的 lyric_timestamps + lyric_texts —— 当前歌曲整首歌词
 *      （MeloYou 自己维护，会随播放刷新；上一首残留会被下一次 setExtras 覆盖）；
 *   2. nowPlaying.json（切歌时写 rid/name/artist）—— 歌曲身份，只用 name 校验；
 *   3. songLyric.json（歌词文件兜底，仅当 extras 不工作时才用）。
 *
 * 进度：setPlaybackState + 每 48ms 自推算。
 *   - lastPositionUpdateTime 补偿（position 是「上次 setState 那一刻」的值）；
 *   - PLAYING 态大幅回跳丢弃（陈旧快照）；
 *   - 首个播放态回调兜底起锚（MeloYou 起播可能长期不报 position）。
 *
 * 老版本问题清单（本次全部重写解决）：
 *   - rid 依赖 j.B 钩子 → 类名变了就完全不出词；
 *   - isSuspectTitle 误杀带括号的正常歌名 → 永远卡上一首；
 *   - 「首行必须含歌名」的拦截 → 整首歌词被丢；
 *   - 状态机太复杂（pendingSwitch / publishPending / lastPublishedSignature 多层）→ 出词不稳定。
 */
internal class XingHeLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader
) {
    private val stateLock = Any()

    @Volatile private var application: Application? = null
    @Volatile private var provider: LyriconProvider? = null

    // 当前歌曲
    @Volatile private var currentName: String? = null
    @Volatile private var currentArtist: String? = null
    @Volatile private var currentDuration: Long = 0L
    @Volatile private var currentRid: String? = null        // nowPlaying.json 的 rid（可空）
    @Volatile private var currentMediaId: String? = null

    // 歌词
    @Volatile private var lyricLines: List<RichLyricLine>? = null
    @Volatile private var lyricFromExtras = false           // 是否来自会话 extras
    @Volatile private var publishedSongId: String? = null   // 已发布的 song.id（诊断用）
    @Volatile private var publishedSignature: String? = null  // song.id + 歌词指纹

    // MediaSession 归属（MeloYou 进程里有多个会话，只认当前歌曲那个）
    @Volatile private var activeSession: WeakReference<MediaSession>? = null
    @Volatile private var activeSessionHash = 0

    // 进度锚点
    @Volatile private var anchorPosition = 0L
    @Volatile private var anchorRealtime = 0L
    @Volatile private var anchorPlaying = false
    @Volatile private var playbackSpeed = 1.0f
    @Volatile private var hasAnchor = false
    @Volatile private var lastPushedPlaying: Boolean? = null
    @Volatile private var songSwitchAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            tickProgress()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }
    private val diskRetry = Runnable { refreshFromDisk() }
    private var diskAttempts = 0
    private var diskRetryActive = false

    fun installHooks() {
        hookApplicationLifecycle()
        hookMediaSession()
        hookMeloYouFiles()
        logger.info("MeloYou provider hooks installed")
        mainHandler.postDelayed({ ensureStarted() }, 700L)
    }

    // ================= Provider =================

    private fun ensureStarted(context: Context? = null) {
        if (provider != null) return
        val ctx = application ?: context?.applicationContext ?: context ?: currentApplication() ?: return
        if (application == null) application = ctx.applicationContext as? Application ?: ctx as? Application
        try {
            val logo = runCatching { ProviderLogo.fromSvg(Constants.ICON) }.getOrNull()
            provider = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = Constants.PLAYER_PACKAGE_NAME,
                logo = logo,
                processName = null
            ).apply {
                player.setDisplayTranslation(false)
                player.setDisplayRoma(false)
                register()
            }
            logger.info("MeloYou provider registered")
            ModuleHeartbeat.report(ctx, module, logger)
        } catch (t: Throwable) {
            logger.error("MeloYou provider register failed", t)
        }
        // 启动兜底：磁盘补读一次
        replayFromDisk()
        startTicker()
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) { null }

    private fun hookApplicationLifecycle() {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java
        )
        installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
            (chain.args.getOrNull(0) as? Application)?.let {
                application = it
                ensureStarted()
            }
        }
    }

    // ================= MediaSession =================

    private fun hookMediaSession() {
        val md = MediaSession::class.java.getDeclaredMethod(
            "setMetadata", MediaMetadata::class.java
        )
        installProtectiveAfterHook(md, "MediaSession.setMetadata") { chain, _ ->
            onMetadataChanged(
                chain.thisObject as? MediaSession,
                chain.args.getOrNull(0) as? MediaMetadata
            )
        }

        val pb = MediaSession::class.java.getDeclaredMethod(
            "setPlaybackState", PlaybackState::class.java
        )
        installProtectiveAfterHook(pb, "MediaSession.setPlaybackState") { chain, _ ->
            onPlaybackStateChanged(
                chain.thisObject as? MediaSession,
                chain.args.getOrNull(0) as? PlaybackState
            )
        }

        // MeloYou 的「当前歌词」通道：lyric_timestamps + lyric_texts
        val extras = MediaSession::class.java.getDeclaredMethod(
            "setExtras", Bundle::class.java
        )
        installProtectiveAfterHook(extras, "MediaSession.setExtras") { chain, _ ->
            onSessionExtras(
                chain.thisObject as? MediaSession,
                chain.args.getOrNull(0) as? Bundle
            )
        }
    }

    private fun onMetadataChanged(session: MediaSession?, md: MediaMetadata?) {
        md ?: return
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) currentDuration = duration

        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() && it != "未知歌曲" } ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && it != "未知歌手" }
        val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.takeIf { it.isNotBlank() && it != "null" }

        ensureStarted()
        rememberActiveSession(session)

        val newName = title
        val changed = currentName != newName ||
            (artist != null && currentArtist != artist) ||
            (mediaId != null && currentMediaId != mediaId)
        if (!changed) return

        logger.info("MeloYou 元数据：$title - $artist (${duration}ms) id=$mediaId")
        synchronized(stateLock) {
            if (currentName != newName) {
                // 真换歌
                lyricLines = null
                lyricFromExtras = false
                publishedSongId = null
                publishedSignature = null
                currentRid = null
                songSwitchAt = SystemClock.elapsedRealtime()
                diskAttempts = 0
            }
            currentName = newName
            currentArtist = artist ?: currentArtist
            currentMediaId = mediaId ?: currentMediaId
        }
        startDiskRetry(restart = true)
        pushIfReady()
    }

    private fun onPlaybackStateChanged(session: MediaSession?, st: PlaybackState?) {
        st ?: return
        if (!isActiveSession(session)) return
        ensureStarted()

        val playing = when (st.state) {
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR -> st.position >= 0L && false
            PlaybackState.STATE_NONE -> anchorPlaying
            else -> true
        }
        val speed = st.playbackSpeed.takeIf { it > 0f } ?: 1.0f

        val now = SystemClock.elapsedRealtime()
        val age = if (st.lastPositionUpdateTime in 1..now) now - st.lastPositionUpdateTime else -1L
        val rawPos = st.position
        val pos = if (rawPos >= 0L && playing && age in 0..8_000L) {
            rawPos + (age * speed.toDouble()).toLong()
        } else rawPos

        if (pos >= 0L) {
            val expected = extrapolatedPosition()
            // 播放中大幅回跳 = 陈旧快照，丢弃；暂停/缓冲时是真 seek，采纳
            val staleDrop = hasAnchor && playing &&
                pos < expected - STALE_TOLERANCE_MS &&
                st.state == PlaybackState.STATE_PLAYING
            // 切歌保护：metadata 刚切完 ~2.5s 内，position 还可能是旧歌残留。
            val justSwitched = now - songSwitchAt < SWITCH_POS_GRACE_MS &&
                pos > SWITCH_POS_MAX_MS
            if (!staleDrop && !justSwitched) {
                anchorPosition = pos
                anchorRealtime = now
                hasAnchor = true
            }
            anchorPlaying = playing
            playbackSpeed = speed
        } else {
            anchorPlaying = playing
        }

        // 兜底起锚：MeloYou 起播可能很久不报 position
        if (!hasAnchor && playing) {
            anchorPosition = pos.coerceAtLeast(0L)
            anchorRealtime = now
            hasAnchor = true
            logger.info("MeloYou 兜底起锚 pos=$anchorPosition")
        }

        startTicker()
        tickProgress()
    }

    /**
     * MeloYou 的「当前歌词」通道：整首歌词写在 extras 里。
     * lyric_timestamps（long[]，毫秒）+ lyric_texts（String[]）一一对应。
     * 这个 extras 是 MeloYou 自己维护的当前歌曲歌词，直接信任：
     *   - 上一首残留会被下一次 setExtras 覆盖，天然自我纠正；
     *   - 不需要歌名匹配（它只反映当前播放）。
     */
    private fun onSessionExtras(session: MediaSession?, extras: Bundle?) {
        extras ?: return
        // MeloYou 进程里可能有多个 MediaSession（控制会话 + 播放会话），
        // 但只要 extras 里有歌词字段，它就是「当前歌曲的歌词通道」，
        // 不再严格校验会话身份 —— 否则控制会话发歌词时被整条丢掉。
        val hasLyricField = extras.containsKey("lyric_timestamps") ||
            extras.containsKey("lyric_texts") ||
            !extras.getString("lyrics").isNullOrBlank() ||
            !extras.getString("android.media.metadata.LYRICS").isNullOrBlank()
        if (!hasLyricField) return

        // MeloYou extras 里歌词有三个形态，按优先级取：
        //   1. lyric_timestamps + lyric_texts（f6946c0 非空时）—— 逐行结构化
        //   2. "lyrics"（String，JSON 数组 [{"time":ms,"text":"词"}]）—— 始终存在
        //   3. "android.media.metadata.LYRICS"（f6948e0 字符串，多为 LRC 文本或单行）
        var lines: List<RichLyricLine>? = null

        val times = extras.getLongArray("lyric_timestamps")
        val texts = extras.getStringArray("lyric_texts")
        if (times != null && texts != null) {
            val count = minOf(times.size, texts.size)
            val items = ArrayList<Pair<Long, String>>(count)
            for (i in 0 until count) {
                val text = texts[i]?.trim().orEmpty()
                if (text.isEmpty() || PLACEHOLDER.any { text.contains(it) }) continue
                items.add(times[i] to text)
            }
            if (items.isNotEmpty()) {
                items.sortBy { it.first }
                lines = items.mapIndexed { i, (begin, text) ->
                    val end = items.getOrNull(i + 1)?.first ?: (begin + 3000L)
                    RichLyricLine(begin = begin, end = end, text = text)
                }
            }
        }

        if (lines == null) {
            // "lyrics"：JSON 数组 [{"time":ms,"text":"词"}]
            val json = extras.getString("lyrics")?.takeIf { it.isNotBlank() && it != "[]" }
            if (json != null) {
                lines = runCatching {
                    val arr = JSONArray(json)
                    val items = ArrayList<Pair<Long, String>>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val text = o.optString("text").trim()
                        if (text.isEmpty() || PLACEHOLDER.any { text.contains(it) }) continue
                        items.add(o.optLong("time", 0L) to text)
                    }
                    if (items.isEmpty()) null else {
                        items.sortBy { it.first }
                        items.mapIndexed { i, (begin, text) ->
                            val end = items.getOrNull(i + 1)?.first ?: (begin + 3000L)
                            RichLyricLine(begin = begin, end = end, text = text)
                        }
                    }
                }.getOrNull()
            }
        }

        if (lines == null) {
            // LYRICS 字段：可能是 LRC 文本 / 单行
            val lrc = extras.getString("android.media.metadata.LYRICS")
                ?.takeIf { it.isNotBlank() && it.length > 8 }
            if (lrc != null) {
                lines = runCatching {
                    LyricParsers.parseLrcText(lrc).lines.takeIf { it.size >= 3 }
                }.getOrNull()
            }
        }

        lines ?: return
        synchronized(stateLock) {
            lyricLines = lines
            lyricFromExtras = true
        }
        pushIfReady()
    }

    // ================= MeloYou 文件（rid + 兜底歌词） =================

    private fun hookMeloYouFiles() {
        try {
            val fileUtilClass = Class.forName("com.bumptech.glide.manager.j", false, classLoader)
            val writeMethod = fileUtilClass.getDeclaredMethod(
                "B", Context::class.java, String::class.java, String::class.java
            )
            installProtectiveAfterHook(writeMethod, "j.B(file write)") { chain, _ ->
                val ctx = chain.args.getOrNull(0) as? Context
                val content = chain.args.getOrNull(1) as? String
                val filename = chain.args.getOrNull(2) as? String
                when (filename) {
                    Constants.NOW_PLAYING_FILE -> onNowPlayingWritten(content)
                    Constants.SONG_LYRIC_FILE -> onLyricsWritten(content)
                }
            }
        } catch (t: Throwable) {
            logger.warn("j.B hook 不可用（类名可能变了），改用磁盘轮询兜底：${t.message}")
        }
    }

    private fun onNowPlayingWritten(content: String?) {
        content ?: return
        val obj = runCatching { JSONObject(content) }.getOrNull() ?: return
        val rid = obj.optString("rid").takeIf { it.isNotBlank() && it != "null" }
        val name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" && it != "未知歌曲" }
        val artist = obj.optString("artist").takeIf { it.isNotBlank() && it != "null" && it != "未知歌手" }

        var songChanged = false
        synchronized(stateLock) {
            if (rid != null && rid != currentRid) {
                songChanged = currentRid != null
                currentRid = rid
            }
            if (name != null && name != currentName) songChanged = true
            if (name != null) currentName = name
            if (artist != null) currentArtist = artist
            val dur = obj.optLong("duration", 0L)
            if (dur > 0) currentDuration = dur
        }

        if (songChanged) {
            logger.info("MeloYou nowPlaying：id=$rid name=$name")
            synchronized(stateLock) {
                lyricLines = null
                lyricFromExtras = false
                publishedSongId = null
                publishedSignature = null
                songSwitchAt = SystemClock.elapsedRealtime()
                diskAttempts = 0
            }
            // 切歌重置进度
            anchorPosition = 0L
            anchorRealtime = SystemClock.elapsedRealtime()
            hasAnchor = true
            pushIfReady()
        }
    }

    private fun onLyricsWritten(content: String?) {
        content ?: return
        val lines = parseMeloYouLyrics(content) ?: return
        synchronized(stateLock) {
            // extras 的歌词优先（它是当前歌曲的），文件歌词只兜底
            if (!lyricFromExtras) lyricLines = lines
        }
        pushIfReady()
    }

    private fun refreshFromDisk() {
        val context = application ?: return
        try {
            val nowPlaying = readAppFile(context, Constants.NOW_PLAYING_FILE)
            val songLyric = readAppFile(context, Constants.SONG_LYRIC_FILE)
            nowPlaying?.let { onNowPlayingWritten(it) }
            // songLyric 只在 extras 还没给歌词时才兜底（不覆盖 extras）
            synchronized(stateLock) {
                if (!lyricFromExtras && lyricLines == null && songLyric != null) {
                    onLyricsWritten(songLyric)
                }
            }
        } catch (t: Throwable) {
            logger.error("MeloYou refreshFromDisk failed", t)
        }
        if (lyricLines == null && diskAttempts < DISK_RETRY_DELAYS.size) {
            mainHandler.postDelayed(diskRetry, DISK_RETRY_DELAYS[diskAttempts])
            diskAttempts++
        } else {
            diskRetryActive = false
        }
    }

    private fun startDiskRetry(restart: Boolean = false) {
        if (diskRetryActive && !restart) return
        diskAttempts = 0
        diskRetryActive = true
        mainHandler.removeCallbacks(diskRetry)
        mainHandler.post(diskRetry)
    }

    private fun replayFromDisk() {
        val ctx = application ?: return
        try {
            readAppFile(ctx, Constants.NOW_PLAYING_FILE)?.let { onNowPlayingWritten(it) }
            readAppFile(ctx, Constants.SONG_LYRIC_FILE)?.let { onLyricsWritten(it) }
        } catch (_: Throwable) {}
    }

    // ================= 发布 =================

    /** 推送当前歌曲（占位或带歌词）。签名：rid|name|artist|歌词行数。 */
    private fun pushIfReady() {
        val p = provider ?: return
        val name: String?
        val artist: String?
        val rid: String?
        val mid: String?
        val lines: List<RichLyricLine>?
        synchronized(stateLock) {
            name = currentName
            artist = currentArtist
            rid = currentRid
            mid = currentMediaId
            lines = lyricLines
        }
        if (name.isNullOrBlank()) return

        // Song.id：优先 rid，其次 mediaId，最后「歌名|歌手」兜底
        val songId = when {
            !rid.isNullOrBlank() -> "meloyou:$rid"
            !mid.isNullOrBlank() -> "meloyou:$mid"
            else -> "meloyou:noid|$name|${artist ?: ""}"
        }
        // 签名带上歌词指纹：占位（无词）与完整歌词、以及 extras 后到的歌词变化，
        // 都必须再次推送 —— 否则上一首的歌词会一直挂在新歌上（「词不对歌」）。
        val lyricSig = (lines?.size ?: -1).toString() + ":" + (lines?.lastOrNull()?.text ?: "")
        val fullSig = "$songId|$lyricSig"
        if (fullSig == publishedSignature) return
        publishedSignature = fullSig

        val song = Song().apply {
            id = songId
            this.name = name
            this.artist = artist
            this.duration = currentDuration.coerceAtLeast(lines?.lastOrNull()?.end ?: 0L)
            this.lyrics = lines?.map { it.copy().apply { translation = null } } ?: emptyList()
        }
        val ok = runCatching { p.player.setSong(song) }.getOrDefault(false)
        publishedSongId = songId
        logger.info("MeloYou 推送：$name - $artist，歌词 ${lines?.size ?: 0} 行（ok=$ok id=$songId）")
    }

    // ================= 进度 =================

    private fun startTicker() {
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    private fun extrapolatedPosition(): Long {
        if (!hasAnchor) return 0L
        if (!anchorPlaying) return anchorPosition
        val elapsed = SystemClock.elapsedRealtime() - anchorRealtime
        val pos = anchorPosition + (elapsed * playbackSpeed.toDouble()).toLong()
        val dur = currentDuration
        return if (dur > 0) pos.coerceIn(0L, dur) else pos.coerceAtLeast(0L)
    }

    private fun tickProgress() {
        val p = provider ?: return
        if (!hasAnchor) return
        if (lastPushedPlaying != anchorPlaying) {
            runCatching { p.player.setPlaybackState(anchorPlaying) }
            lastPushedPlaying = anchorPlaying
        }
        runCatching { p.player.setPosition(extrapolatedPosition()) }
    }

    // ================= 会话归属 =================

    private fun rememberActiveSession(session: MediaSession?) {
        session ?: return
        activeSession = WeakReference(session)
        val hash = System.identityHashCode(session)
        if (hash != activeSessionHash) {
            activeSessionHash = hash
            logger.debug("MeloYou 当前会话：$hash")
        }
    }

    private fun isActiveSession(session: MediaSession?): Boolean {
        val cur = activeSession?.get() ?: return true
        return session == null || session === cur
    }

    // ================= 工具 =================

    private fun parseMeloYouLyrics(content: String): List<RichLyricLine>? {
        return try {
            val array = JSONArray(content)
            val items = ArrayList<Pair<Long, String>>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val text = obj.optString("lineLyric").trim()
                if (text.isEmpty() || PLACEHOLDER.any { text.contains(it) }) continue
                val seconds = obj.optString("time", "0").toDoubleOrNull() ?: 0.0
                items.add((seconds * 1000).toLong() to text)
            }
            if (items.isEmpty()) return null
            items.sortBy { it.first }
            items.mapIndexed { i, (begin, text) ->
                val end = items.getOrNull(i + 1)?.first ?: (begin + 3000L)
                RichLyricLine(begin = begin, end = end, text = text)
            }
        } catch (t: Throwable) {
            logger.error("Parse songLyric.json failed: ${t.message}")
            null
        }
    }

    private fun readAppFile(context: Context, name: String): String? {
        return try {
            context.openFileInput(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) { null }
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
                    try { callback(chain, result) }
                    catch (t: Throwable) { logger.error("After-hook failed: $description", t) }
                    result
                }
        } catch (t: Throwable) {
            logger.error("Unable to install hook: $description", t)
        }
    }

    private companion object {
        private const val TICK_MS = 48L
        private const val STALE_TOLERANCE_MS = 3_500L
        private const val SWITCH_POS_GRACE_MS = 2_500L
        private const val SWITCH_POS_MAX_MS = 12_000L
        private val DISK_RETRY_DELAYS = longArrayOf(600L, 1200L, 2000L, 3500L, 6000L, 9000L, 15000L)
        private val PLACEHOLDER = listOf(
            "歌曲暂无歌词", "暂无歌词", "请欣赏音乐", "纯音乐", "该歌曲为纯音乐"
        )
    }
}
