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
import org.json.JSONObject
import java.lang.reflect.Executable
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.concurrent.thread

/**
 * 通用在线歌词提供者（参考 LyricProvider 项目 cloud-provider + cloudlyric 思路）。
 *
 * 适配思路：不逐个 hook 各音乐应用的私有接口，而是统一 hook MediaSession：
 *  - setMetadata         -> 拿到歌曲名 / 歌手 / 时长
 *  - setPlaybackState    -> 拿到播放进度与状态
 * 然后用在线歌词库做匹配（QQ 音乐 + 网易云双源），把歌词按 LyricON 协议推给星流。
 *
 * 一份实现即可覆盖所有会发布 MediaSession 元数据的音乐平台（平台列表见
 * Constants.CLOUD_PLAYER_PACKAGES，新增平台只需把包名加进列表并加入 LSPosed 作用域）。
 *
 * 歌词匹配：优先取时长最接近的结果，避免同名歌曲匹配错；QQ 源优先，失败回退网易云。
 */
internal class CloudLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String
) {

    private val stateLock = Any()

    @Volatile
    private var provider: LyriconProvider? = null

    @Volatile
    private var application: Application? = null

    /** 当前歌曲签名（歌曲名|歌手|时长），变化才重新搜索歌词 */
    @Volatile
    private var currentSongSignature: String? = null

    @Volatile
    private var lyricForCurrentSong: List<RichLyricLine>? = null

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
        logger.info("Cloud lyric hooks installed for $hostPackage")
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
        logger.info("Cloud lyric provider registered, player=${hostApplication.packageName}")
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
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && it != "未知歌手" }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (title == null) return
        if (duration > 0) metadataDurationMs = duration

        val signature = "$title|$artist|$duration"
        synchronized(stateLock) {
            if (signature == currentSongSignature) return
            currentSongSignature = signature
            lyricForCurrentSong = null
        }
        // 切歌：重置进度锚点归零
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = true
        isPlaying = true

        val p = provider
        if (p != null) {
            try {
                p.player.setPosition(0L)
            } catch (t: Throwable) {
                logger.debug("Reset position failed: ${t.message}")
            }
        }

        logger.info("Metadata changed: $title - $artist ($duration)")
        searchAndPublish(title, artist, duration)
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
        try {
            p.player.setPlaybackState(isPlaying)
            p.player.setPosition(extrapolatedPosition())
        } catch (t: Throwable) {
            logger.debug("tickProgress failed: ${t.message}")
        }
    }

    // ---------------- 在线搜索与歌词（QQ + 网易云双源） ----------------

    private fun searchAndPublish(title: String, artist: String?, duration: Long) {
        thread(name = "xinghe-cloud-lyric", isDaemon = true) {
            try {

                val query = if (artist.isNullOrBlank()) title else "$title $artist"
                var lines: List<RichLyricLine>? = null
                var source = ""

                // 源 1：QQ 音乐（搜索 + QRC/LRC）
                try {
                    val songs = QqLyrics.search(query, 5)
                    val best = pickBest(songs, duration)
                    if (best != null) {
                        val l = QqLyrics.downloadLyric(best.id)
                        if (!l.isNullOrEmpty()) {
                            lines = l
                            source = "qq"
                        }
                    }
                } catch (t: Throwable) {
                    logger.debug("QQ lyric failed: ${t.message}")
                }

                // 源 2：网易云（回退）
                if (lines == null) {
                    try {
                        val songId = searchNetEaseSongId(query, duration)
                        if (songId != null) {
                            val lrc = fetchNetEaseLyric(songId)
                            val l = parseLrc(lrc)
                            if (l.isNotEmpty()) {
                                lines = l
                                source = "netease"
                            }
                        }
                    } catch (t: Throwable) {
                        logger.debug("NetEase lyric failed: ${t.message}")
                    }
                }

                if (lines.isNullOrEmpty()) {
                    logger.info("No lyric matched online: $title - $artist")
                    return@thread
                }

                synchronized(stateLock) {
                    lyricForCurrentSong = lines
                }
                publish(title, artist, duration, lines, source)
                logger.info("Cloud lyric published: $title ($source), ${lines.size} lines")
            } catch (t: Throwable) {
                logger.error("Cloud lyric search failed: ${t.message}", t)
            }
        }
    }

    /** 优先时长接近（±8s）的结果，否则取第一个 */
    private fun pickBest(
        candidates: List<QqLyrics.QqSong>,
        duration: Long
    ): QqLyrics.QqSong? {
        if (candidates.isEmpty()) return null
        var best = candidates.first()
        var bestDiff = Long.MAX_VALUE
        for (c in candidates) {
            if (duration > 0 && c.duration > 0) {
                val diff = kotlin.math.abs(c.duration - duration)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = c
                }
            }
        }
        return best
    }

    // ---------------- 网易云 ----------------

    private fun searchNetEaseSongId(query: String, duration: Long): Long? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://music.163.com/api/search/get/web?csrf_token=&s=$encoded&type=1&offset=0&limit=5"
        val text = httpGet(url) ?: return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val result = obj.optJSONObject("result") ?: return null
        val songs = result.optJSONArray("songs") ?: return null

        var firstId: Long? = null
        var bestId: Long? = null
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0L)
            if (id <= 0L) continue
            if (firstId == null) firstId = id
            val songDuration = song.optLong("duration", 0L)
            if (duration > 0 && songDuration > 0) {
                val diff = kotlin.math.abs(songDuration - duration)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestId = id
                }
            }
        }
        if (bestId != null && bestDiff <= DURATION_MATCH_MS) return bestId
        return firstId
    }

    private fun fetchNetEaseLyric(songId: Long): String? {
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
        val text = httpGet(url) ?: return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val lrc = obj.optJSONObject("lrc") ?: return null
        val lyric = lrc.optString("lyric")
        return lyric.takeIf { it.isNotBlank() }
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Referer", "https://music.163.com/")
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 解析 LRC -> RichLyricLine 列表 */
    private fun parseLrc(lrc: String?): List<RichLyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val items = ArrayList<Pair<Long, String>>()
        val timeRegex = Regex("""\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?]""")
        for (line in lrc.lines()) {
            val content = line.replace(timeRegex, "").trim()
            if (content.isEmpty()) continue
            if (PLACEHOLDER_PATTERNS.any { content.contains(it) }) continue
            for (match in timeRegex.findAll(line)) {
                val minutes = match.groupValues[1].toIntOrNull() ?: continue
                val seconds = match.groupValues[2].toIntOrNull() ?: continue
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toIntOrNull() ?: 0
                val timeMs = minutes * 60_000L + seconds * 1000L + fraction * 10L
                items.add(timeMs to content)
            }
        }
        if (items.isEmpty()) return emptyList()
        items.sortBy { it.first }
        return items.mapIndexed { index, (begin, text) ->
            val end = items.getOrNull(index + 1)?.first ?: (begin + 3000L)
            RichLyricLine(begin = begin, end = end, text = text)
        }
    }

    private fun publish(title: String, artist: String?, duration: Long, lyrics: List<RichLyricLine>, source: String) {
        val p = provider ?: return
        val song = Song().apply {
            id = "online:$title|$artist|$source"
            this.name = title
            this.artist = artist
            this.duration = duration
            this.lyrics = lyrics
        }
        try {
            p.player.setSong(song)
        } catch (t: Throwable) {
            logger.error("setSong failed", t)
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
        private const val DURATION_MATCH_MS = 8000L

        private val PLACEHOLDER_PATTERNS = listOf(
            "歌曲暂无歌词",
            "暂无歌词",
            "请欣赏音乐",
            "纯音乐",
            "该歌曲为纯音乐"
        )
    }
}
