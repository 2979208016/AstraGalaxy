package io.github.proify.astrafirmament.hook.cimu

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
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import io.github.proify.astrafirmament.lyric.Constants
import io.github.proify.astrafirmament.lyric.online.OnlineLyricSource
import io.github.proify.astrafirmament.settings.ModulePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spotify 歌词提供者（重做版）。
 *
 * 为什么不再按 okhttp 抓头走官方接口：
 * Spotify 9.1.92 的 okhttp 被 R8 重打包，dex 里只有 4 个残留的 `Lokhttp3/...`
 * 描述符，`okhttp3.Headers` / `OkHttpClient` 根本不存在，所以原先 hook
 * `Headers.of/<init>` 截流 authorization 的路径永远抓不到头 → 官方 color-lyrics
 * 必然 401。主路径必须换成「不依赖 token 也能出词」：
 *
 *   0. 本地拦截（首选）：hook Spotify 自己的 protobuf-lite 解析入口，在它解析
 *      color-lyrics 响应的瞬间把已解析对象反射成歌词行 —— 不抓头、不联网、
 *      不怕 token 过期；见 [SpotifyProtoLyricHook] / [SpotifyProtoLyric]；
 *   1. 本地缓存（本类自己落的 JSON，按曲目 id / 歌名歌手哈希命名）；
 *   2. 公开歌词源 [OnlineLyricSource]（网易云公开接口，国内可直连，原文+译文）；
 *   3. 官方 color-lyrics —— 只有真的抓到 authorization 才尝试（最后兜底）。
 *
 * 「抓头」保留为独立旁路：混淆后的 Headers 形如 p.k050，按构造器形态扫描，
 * 抓不到是常态（R8 会继续改名），只打 debug 日志，不刷 error。
 *
 * 推送：Song + RichLyricLine（translation 原样保留，setDisplayTranslation(true)），
 * 无词时立刻推空 lyrics 占位；进度由 MediaSession.setPlaybackState 起锚 + 48ms 自推算
 * （只在 STATE_PLAYING 且 speed>0 时外推，暂停/缓冲/拖动一律冻结），避免星流进度卡住。
 * 只在 com.spotify.music 进程加载（HookEntry 按包名分派）。
 */
internal class SpotifyLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)

    /** 单线程查词：同一时刻只有一首在查，切歌靠 generation 丢弃过期结果 */
    private val lookupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-spotify-lyric").apply { isDaemon = true }
    }

    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null
    @Volatile private var activeSession: WeakReference<MediaSession>? = null

    // 当前曲目（mediaKey 是缓存种子；trackId 只在通过 22 位 base62 校验后才非空）
    @Volatile private var currentMediaKey: String = ""
    @Volatile private var currentTrackId: String? = null
    @Volatile private var currentTitle: String? = null
    @Volatile private var currentArtist: String? = null
    @Volatile private var currentDurationMs: Long = 0L

    // 歌词：null = 还没结果；emptyList = 已确认无词
    @Volatile private var lyricLines: List<RichLyricLine>? = null
    @Volatile private var lyricSource: String? = null
    @Volatile private var publishedSignature: String? = null

    // 抓头旁路：只在抓到 authorization 时官方接口才可用
    private val capturedHeaders = ConcurrentHashMap<String, String>()
    @Volatile private var authCaptured = false

    // 响应侧拦截：Spotify 自己解析歌词 protobuf 时顺手抄一份（首选来源）
    private var protoHook: SpotifyProtoLyricHook? = null

    // 进度锚点：anchorPosition 是 anchorRealtime（elapsedRealtime 基准）那一刻的位置。
    // anchorPlaying 是推给星流的播放态，anchorAdvancing 才是「位置要不要随墙钟走」——
    // 缓冲/拖动期间星流照旧显示播放，但进度必须冻住，否则歌词会跑到人声前面。
    @Volatile private var anchorPosition = 0L
    @Volatile private var anchorRealtime = 0L
    @Volatile private var anchorPlaying = false
    @Volatile private var anchorAdvancing = false
    @Volatile private var playbackSpeed = 1.0f
    @Volatile private var hasAnchor = false
    @Volatile private var lastPushedPlaying: Boolean? = null
    @Volatile private var songSwitchAt = 0L

    private val progressTicker = object : Runnable {
        override fun run() {
            tickProgress()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    fun installHooks() {
        hookApplicationLifecycle()
        hookMediaSession()
        installHeaderCapture()
        installProtoLyricHook()
        logger.info("Spotify lyric provider installed in $processName")
    }

    // ================= Provider =================

    private fun hookApplicationLifecycle() {
        runCatching {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
                (chain.args.getOrNull(0) as? Application)?.let { application = it }
            }
        }.onFailure { logger.error("Spotify Application hook failed", it) }
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) {
        null
    }

    private fun ensureProvider(): LyriconProvider? {
        provider?.let { return it }
        val ctx = application ?: currentApplication()?.also { application = it } ?: return null
        return runCatching {
            val logo = runCatching { ProviderLogo.fromSvg(Constants.ICON) }.getOrNull()
            LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = hostPackage,
                logo = logo,
                processName = processName
            ).apply {
                // translation 默认放行：显示不显示是显示侧的开关，数据层先带上
                player.setDisplayTranslation(true)
                player.setDisplayRoma(false)
                register()
            }.also {
                provider = it
                logger.info("Spotify provider registered in $processName")
            }
        }.onFailure {
            logger.error("Spotify provider registration failed in $processName", it)
        }.getOrNull()
    }

    // ================= MediaSession =================

    private fun hookMediaSession() {
        runCatching {
            val setMeta = MediaSession::class.java.getDeclaredMethod(
                "setMetadata", MediaMetadata::class.java
            )
            installProtectiveAfterHook(setMeta, "MediaSession.setMetadata") { chain, _ ->
                rememberSession(chain.thisObject as? MediaSession)
                onMetadata(chain.args.getOrNull(0) as? MediaMetadata)
            }
            val setState = MediaSession::class.java.getDeclaredMethod(
                "setPlaybackState", PlaybackState::class.java
            )
            installProtectiveAfterHook(setState, "MediaSession.setPlaybackState") { chain, _ ->
                onPlaybackState(
                    chain.thisObject as? MediaSession,
                    chain.args.getOrNull(0) as? PlaybackState
                )
            }
        }.onFailure { logger.error("Spotify MediaSession hook failed", it) }
    }

    private fun rememberSession(session: MediaSession?) {
        session ?: return
        activeSession = WeakReference(session)
    }

    private fun isActiveSession(session: MediaSession?): Boolean {
        val current = activeSession?.get() ?: return true
        return session == null || session === current
    }

    private fun onMetadata(meta: MediaMetadata?) {
        meta ?: return
        val rawId = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.trim()?.takeIf { it.isNotEmpty() && it != "未知歌曲" }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.trim()?.takeIf { it.isNotEmpty() && it != "未知歌手" }
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val trackId = extractSpotifyTrackId(rawId)
        val nonMusic = isNonMusicMedia(rawId)
        if (title == null && trackId == null && !nonMusic) return

        val key = when {
            nonMusic -> "non:$rawId"
            trackId != null -> TRACK_KEY_PREFIX + trackId
            else -> "q:${normalize(title)}|${normalize(artist)}"
        }
        if (key == currentMediaKey) {
            // 同一首：Spotify 会反复发 metadata，时长可能后到，只补时长
            if (duration > 0L) currentDurationMs = duration
            return
        }

        currentMediaKey = key
        currentTrackId = trackId
        currentTitle = title
        currentArtist = artist
        currentDurationMs = duration
        logger.info(
            "Spotify 切歌：$title - $artist (${duration}ms) mediaId=$rawId trackId=${trackId ?: "无"} nonMusic=$nonMusic"
        )
        startLoad(skipLookup = nonMusic)
    }

    private fun startLoad(skipLookup: Boolean = false) {
        val gen = generation.incrementAndGet()
        // 切歌时间点：没有 trackId 归属的拦截结果只接受这之后的
        protoHook?.onTrackSwitch()
        lyricLines = null
        lyricSource = null
        publishedSignature = null
        // 切歌一律按 0 起锚：Spotify 下一个 setPlaybackState 可能隔很久，不起锚进度会停在
        // 上一首的位置（星流侧看起来像「进度卡住」，高亮还挂在上一首那一句）
        val wasPlaying = anchorPlaying
        resetProgress()
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        anchorPlaying = wasPlaying
        anchorAdvancing = wasPlaying
        hasAnchor = true
        pushSong() // 先推占位：立刻清掉上一首的歌词，进度不受查词影响
        startTicker()
        lookupExecutor.execute { resolveLyric(gen, skipLookup) }
    }

    private fun onPlaybackState(session: MediaSession?, state: PlaybackState?) {
        state ?: return
        if (!isActiveSession(session)) return
        ensureProvider() ?: return

        val now = SystemClock.elapsedRealtime()
        val reported = state.playbackSpeed
        // 只有「真在播」才按墙钟推进：BUFFERING/SEEKING 时 position 就是卡住/拖到的那一刻，
        // speed 也常报 0，继续外推会让歌词跑到人声前面
        val advancing = state.state == PlaybackState.STATE_PLAYING && reported > 0f
        val playing = when (state.state) {
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR -> false
            PlaybackState.STATE_NONE -> anchorPlaying
            else -> true
        }
        // PlaybackState 没有 STATE_SEEKING（那是 PlaybackStateCompat 也没有的旧概念），
        // 真正的前跳/后跳靠下面的 drift 判定；这里只把快进/倒退标成 seeking。
        val seeking = state.state == PlaybackState.STATE_FAST_FORWARDING ||
            state.state == PlaybackState.STATE_REWINDING
        // position 是 lastPositionUpdateTime（elapsedRealtime 基准）那一刻的值；
        // 只有推进中才补流逝部分，暂停/缓冲补了等于凭空多走一段
        val age = if (state.lastPositionUpdateTime in 1..now) now - state.lastPositionUpdateTime else -1L
        val raw = state.position
        val pos = when {
            raw < 0L -> -1L
            !advancing -> raw
            age in 0..POSITION_REPORT_MAX_AGE_MS -> raw + (age * reported.toDouble()).toLong()
            else -> raw
        }
        // 报告年龄小于 FRESH_STATE_MS：宿主刚结算的当前位置，属权威值（拖进度条会立刻
        // setPlaybackState）；更旧的报告只能当参考，不能拿它把进度整段回拨
        val fresh = raw >= 0L && age in 0..FRESH_STATE_MS
        // 切歌后短时间内的大 position 多半是上一首残留，新鲜报告不适用
        val staleAfterSwitch = !fresh && now - songSwitchAt < SWITCH_POS_GRACE_MS &&
            pos > SWITCH_POS_MAX_MS

        applyAnchor(
            pos = if (staleAfterSwitch) -1L else pos,
            playing = playing,
            advancing = advancing,
            seeking = seeking,
            fresh = fresh,
            speed = reported,
            now = now
        )
        startTicker()
        tickProgress()
    }

    /**
     * 用一次状态上报决定新锚点：报告值和本地外推谁更可信，按状态分流。
     *
     * 旧实现每次上报都直接采信 position，于是一个重发的旧快照就能把进度往回拽好几秒，
     * 歌词因此总落后半拍；反过来缓冲/拖动期间继续外推又会让歌词超前。现在只有
     * 「大步差 / 拖动 / 刚结算的报告」能修正锚点，旧快照一律不采信（否则同一份旧报告
     * 重发几次就会把进度累积着往回拽），偏差交给下一次新鲜报告修正。
     */
    private fun applyAnchor(
        pos: Long,
        playing: Boolean,
        advancing: Boolean,
        seeking: Boolean,
        fresh: Boolean,
        speed: Float,
        now: Long
    ) {
        val expected = extrapolatedPosition()
        if (pos >= 0L) {
            if (!hasAnchor) {
                anchorPosition = clampPosition(pos)
                hasAnchor = true
            } else {
                val drift = pos - expected
                anchorPosition = clampPosition(
                    when {
                        // 与推算值差一大截 = 拖动/跳转，报告值权威
                        drift > SEEK_JUMP_MS || drift < -SEEK_JUMP_MS -> pos
                        // 不推进：拖动值和刚结算的报告优先，其余停在已经显示到的位置
                        !advancing -> if (seeking || fresh || drift >= 0L) pos else expected
                        // 刚结算的报告就是「这一刻在哪」的权威值，超前落后都采纳（只错一次）
                        fresh -> pos
                        // 报告超前 = 之前少推了（冻结后恢复），立刻对齐
                        drift >= 0L -> pos
                        // 更旧又落后 = 旧快照重发：不采纳，停在推算值，别把歌词拽回上一句
                        else -> expected
                    }
                )
            }
            anchorRealtime = now
        } else if (hasAnchor && !advancing) {
            // 没有可用位置又要停止推进：冻结在已经显示到的位置，别退回旧锚点
            anchorPosition = expected
            anchorRealtime = now
        }
        anchorPlaying = playing
        anchorAdvancing = advancing
        playbackSpeed = speed
    }

    // ================= 歌词查找主流程 =================

    /** 本地拦截 → 缓存 → 公开源 → 官方接口；任一命中落盘并推送，全空落负缓存 */
    private fun resolveLyric(gen: Int, skipLookup: Boolean) {
        if (gen != generation.get()) return
        val key = currentMediaKey
        val title = currentTitle
        val artist = currentArtist
        val duration = currentDurationMs
        val trackId = currentTrackId

        // 0) 本地拦截：已经抓到的 protobuf 歌词（最高优先级）
        protoHook?.takeReady(trackId)?.let {
            applyIntercepted(gen, key, it, title)
            return
        }

        val cached = readCache(key)
        if (cached != null) {
            if (cached.none) {
                logger.info("Spotify 命中「近期无词」缓存，不再联网：${title ?: key}")
                return
            }
            cached.lines?.let {
                logger.info("Spotify 歌词来自缓存：${it.size} 行（${title ?: key}）")
                postApply(gen, it, "缓存")
                return
            }
        }
        if (skipLookup) {
            logger.info("Spotify 非音乐媒体（播客/广告），跳过查词")
            return
        }

        // 1) 本地拦截（在途）：刚发出过歌词请求时，给 Spotify 的解析留一个短窗口
        val hook = protoHook
        if (hook != null && hook.hasRecentRequest(trackId)) {
            hook.awaitLyrics(trackId, PROTO_WAIT_MS)?.let {
                applyIntercepted(gen, key, it, title)
                return
            }
        }

        // 2) 公开词源；3) 官方接口（抓到 authorization 才可用，最后兜底）
        val online = searchOnline(title, artist, duration)
        val found = online.found ?: fetchOfficial(trackId)
        if (found == null) {
            // 只有「所有源都明确答复没有」才写负缓存；否则下次同曲还会再试
            if (online.confirmed) {
                writeNegativeCache(key)
                logger.info("spotify 无歌词（拦截/缓存/在线源/官方均未命中）：$title - $artist")
            } else {
                logger.info("spotify 本次未验明（在线源超时/拒答），不写负缓存：$title - $artist")
            }
            return
        }
        writeCache(key, found.lines, found.source)
        postApply(gen, found.lines, found.source)
    }

    /**
     * 公开歌词源：国内可直连，返回的 RichLyricLine 已带 translation/roma。
     *
     * 搜索词交给 [OnlineLyricSource] 的变体机制（去 feat/去括号/只用首位歌手），
     * 这里只负责把「是否验明无词」原样带回给调用方。
     */
    private fun searchOnline(title: String?, artist: String?, duration: Long): Online {
        val miss = Online(null, false)
        if (title.isNullOrBlank()) return miss
        val query = cleanSearchTitle(title)
        val outcome = try {
            // 契约（lead 定稿）：同步阻塞、失败返回 null；这里再兜一层异常，别让查词炸掉 provider
            OnlineLyricSource.searchChecked(query, artist.orEmpty(), duration) { message ->
                logger.debug("spotify 在线源：$message")
            }
        } catch (t: Throwable) {
            logger.warn("spotify 在线歌词源不可用：${t.message}")
            return miss
        }
        val payload = outcome.payload ?: return Online(null, outcome.confirmedMissing)

        // 公开词源常常给的是「同名的另一个版本」（翻唱/DJ/现场），行时间轴与
        // 正在播放的版本整体错位 → 表现为歌词比歌快/慢。按总时长比例把整条
        // 时间轴缩放到当前曲目时长，能把系统性偏移压掉。
        val lines = sanitizeLines(rescaleLines(payload.lines, payload.durationMs, duration))
        if (lines.isEmpty()) return miss
        val translated = lines.count { !it.translation.isNullOrBlank() }
        logger.info(
            "spotify 在线命中：$query - ${artist.orEmpty()}，${lines.size} 行，" +
                "有译文 $translated 行（源 ${payload.source}）"
        )
        return Online(Found(lines, "公开歌词源"), false)
    }

    /** 按「词源总时长 / 当前曲目时长」整体缩放时间轴（只在偏差 >0.5s 且比例合理时做）。 */
    private fun rescaleLines(
        lines: List<RichLyricLine>,
        sourceDurationMs: Long,
        playDurationMs: Long,
    ): List<RichLyricLine> {
        if (sourceDurationMs < 10_000L || playDurationMs < 10_000L) return lines
        val delta = playDurationMs - sourceDurationMs
        if (delta in -500L..500L) return lines
        val ratio = playDurationMs.toDouble() / sourceDurationMs.toDouble()
        if (ratio !in 0.90..1.10) return lines
        return lines.map { line ->
            val begin = (line.begin * ratio).toLong()
            val end = (line.end * ratio).toLong()
            RichLyricLine(
                begin = begin,
                end = end,
                text = line.text,
                words = line.words,
                translation = line.translation,
            )
        }
    }

    // ================= 官方 color-lyrics（可选路径） =================

    private fun fetchOfficial(trackId: String?): Found? {
        trackId ?: return null
        if (capturedHeaders["authorization"].isNullOrBlank()) {
            logger.debug("Spotify 未捕获 authorization，跳过官方 color-lyrics（常态，不算错误）")
            return null
        }
        // trackId 在 onMetadata 已通过 22 位 base62 校验，只有这里才允许拼 URL
        val url = API_BASE + trackId +
            "?vocalRemoval=false&preview=false&clientLanguage=" +
            Locale.getDefault().toLanguageTag()
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("accept", "application/json")
            conn.setRequestProperty("app-platform", "WebPlayer")
            for ((name, value) in capturedHeaders) conn.setRequestProperty(name, value)
            val code = conn.responseCode
            if (code !in 200..299) {
                logger.debug("Spotify 官方接口 HTTP $code（$trackId），放弃官方路径")
                return@runCatching null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val lines = sanitizeLines(parseColorLyrics(body))
            if (lines.isEmpty()) null else Found(lines, "color-lyrics")
        }.onFailure {
            logger.warn("Spotify 官方接口请求失败：${it.message}")
        }.getOrNull()
    }

    private fun parseColorLyrics(body: String): List<RichLyricLine> {
        val arr = runCatching {
            JSONObject(body).optJSONObject("lyrics")?.optJSONArray("lines")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<RichLyricLine>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val begin = o.optLong("startTimeMs", -1L)
            val text = o.optString("words").trim()
            if (begin < 0L || text.isEmpty()) continue
            out.add(RichLyricLine(begin = begin, end = begin, text = text))
        }
        return out
    }

    // ================= 响应侧拦截（首选来源） =================

    private fun installProtoLyricHook() {
        runCatching {
            val hook = SpotifyProtoLyricHook(
                module, logger, classLoader, ::isLyricEnabled,
                ::currentTrackForHook, ::currentDurationForHook, ::onProtoLyricCaptured
            )
            if (!hook.install()) {
                logger.debug("Spotify 响应侧拦截不可用（protobuf 解析入口未命中）")
                return@runCatching
            }
            protoHook = hook
        }.onFailure { logger.warn("Spotify 响应侧拦截挂载失败：${it.message}") }
    }

    /**
     * 给拦截器读「当前曲目 / 时长」：归属必须在响应到达那一刻取值，
     * 不能提前快照，否则切歌瞬间的响应会按上一首判断。
     */
    private fun currentTrackForHook(): String? = currentTrackId

    private fun currentDurationForHook(): Long = currentDurationMs

    /**
     * 拦截线程回调。拦截器自己做「配到的请求 == 当前曲目」与时长校验，
     * 这里再兜一层：归属不一致或结果过期一律丢弃，绝不下发给显示侧。
     */
    private fun onProtoLyricCaptured(trackId: String?, raw: List<RichLyricLine>) {
        if (trackId == null || raw.isEmpty()) return
        val expected = currentTrackId
        if (expected != trackId) {
            logger.warn("spotify proto 拦截结果归属不一致（$trackId != $expected），丢弃")
            return
        }
        val gen = generation.get()
        val key = currentMediaKey
        if (key.isEmpty()) return
        applyIntercepted(gen, key, raw, currentTitle)
    }

    /**
     * 本地拦截结果统一出口：时长校验 → 清洗 → 缓存（来源标记）→ 推送。
     *
     * 时长校验是最后一道错词闸门：时间轴与本曲时长不相容说明归属还是错的，
     * 这种结果既不推给星流也不许落缓存（旧版错词就是这么被长期固化的）。
     */
    private fun applyIntercepted(gen: Int, key: String, raw: List<RichLyricLine>, title: String?) {
        if (key.isEmpty()) return
        val lines = sanitizeLines(raw)
        if (lines.isEmpty()) return
        val duration = currentDurationMs
        if (!SpotifyProtoLyric.fitsDuration(lines, duration)) {
            logger.warn(
                "Spotify 拦截歌词与本曲时长不符，丢弃不写缓存：${lines.size} 行，" +
                    "末行 ${lines.last().begin}ms，时长 ${duration}ms（${title ?: key}）"
            )
            return
        }
        logger.info("Spotify 歌词来自本地拦截：${lines.size} 行（${title ?: key}）")
        writeCache(key, lines, "本地拦截")
        // 没有 trackId 的曲目用 q: 键：顺手按 trackId 再存一份，下次同曲切歌直接命中缓存，
        // 不用再等 Spotify 发请求、再等拦截回调
        val trackId = currentTrackId
        if (trackId != null && key != TRACK_KEY_PREFIX + trackId) {
            writeCache(TRACK_KEY_PREFIX + trackId, lines, "本地拦截")
        }
        postApply(gen, lines, "本地拦截")
    }

    // ================= 抓头旁路（可失败，失败是常态） =================

    private fun installHeaderCapture() {
        for (name in HEADER_CLASS_CANDIDATES) {
            val cls = loadClass(name) ?: continue
            if (hookHeaderClass(cls)) {
                logger.info("Spotify 头捕获已挂载：$name")
                return
            }
        }
        logger.debug("Spotify 头捕获不可用（okhttp 被 R8 重打包，属常态）；官方接口将自动跳过")
    }

    /** okhttp 的 Headers 构造器形态是「单个数组参数」；按形态匹配，不依赖方法名 */
    private fun hookHeaderClass(cls: Class<*>): Boolean {
        val candidates = ArrayList<Executable>()
        candidates.addAll(cls.declaredMethods.filter { it.name == "of" })
        candidates.addAll(cls.constructors.toList())
        for (executable in candidates) {
            val types = executable.parameterTypes
            if (types.size != 1 || !types[0].isArray) continue
            installProtectiveAfterHook(executable, "header capture ${cls.name}") { chain, _ ->
                (chain.args.firstOrNull() as? Array<*>)?.let { captureHeaderPairs(*it) }
            }
            return true
        }
        return false
    }

    /** Headers 的实参是扁平 key1,value1,key2,value2…；只留需要复用的四个头 */
    private fun captureHeaderPairs(vararg args: Any?) {
        if (args.size % 2 != 0) return
        var i = 0
        while (i + 1 < args.size) {
            val name = args[i]?.toString()?.lowercase(Locale.ENGLISH)
            val value = args[i + 1]?.toString()
            if (name != null && value != null && name in CAPTURED_HEADERS) {
                capturedHeaders[name] = value
                if (name == "authorization") onAuthorizationCaptured()
            }
            i += 2
        }
    }

    /** 头通常比歌曲元数据晚到：抓到后用官方接口再补一次（已出词就不打扰） */
    private fun onAuthorizationCaptured() {
        if (authCaptured) return
        authCaptured = true
        logger.info("Spotify 已捕获 authorization（官方 color-lyrics 路径可用）")
        val trackId = currentTrackId ?: return
        val gen = generation.get()
        val key = currentMediaKey
        lookupExecutor.execute {
            if (gen != generation.get() || lyricLines?.isNotEmpty() == true) return@execute
            val found = fetchOfficial(trackId) ?: return@execute
            writeCache(key, found.lines, found.source)
            postApply(gen, found.lines, found.source)
        }
    }

    // ================= 推送 =================

    private fun postApply(gen: Int, lines: List<RichLyricLine>, source: String) {
        mainHandler.post {
            if (gen != generation.get()) {
                logger.debug("Spotify 丢弃过期歌词结果（已切歌）")
                return@post
            }
            lyricLines = lines
            lyricSource = source
            pushSong(lines)
        }
    }

    /** 统一推送出口：lines 为空即占位；签名含歌词指纹，占位→完整词必须再推一次 */
    private fun pushSong(lines: List<RichLyricLine> = emptyList()) {
        val registered = ensureProvider() ?: return
        val title = currentTitle
        val trackId = currentTrackId
        if (title == null && trackId == null) return
        val songId = trackId?.let { "spotify:track:$it" }
            ?: "spotify:noid|${title.orEmpty()}|${currentArtist.orEmpty()}"
        val signature = "$songId|${lines.size}|${lines.lastOrNull()?.text.orEmpty()}"
        if (signature == publishedSignature) return
        publishedSignature = signature

        val song = Song().apply {
            id = songId
            this.name = title ?: songId
            this.artist = currentArtist
            // 时长以元数据为准，缺失时退回歌词末尾，避免进度条走到头
            val lyricEnd = lines.lastOrNull()?.end ?: 0L
            this.duration = if (currentDurationMs > 0L) currentDurationMs else lyricEnd
            this.lyrics = lines.map { it.copy() }
        }
        val ok = runCatching { registered.player.setSong(song) }.getOrDefault(false)
        if (lines.isEmpty()) {
            logger.info("Spotify 推送占位：$title - ${currentArtist.orEmpty()}（ok=$ok）")
        } else {
            logger.info(
                "Spotify 推送歌词：$title - ${currentArtist.orEmpty()}，${lines.size} 行" +
                    "（${lyricSource ?: "未知"}，ok=$ok id=$songId）"
            )
        }
    }

    // ================= 进度 =================

    private fun resetProgress() {
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        anchorPlaying = false
        anchorAdvancing = false
        playbackSpeed = 1.0f
        hasAnchor = false
        lastPushedPlaying = null
        songSwitchAt = SystemClock.elapsedRealtime()
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    private fun extrapolatedPosition(): Long {
        if (!hasAnchor) return 0L
        // 暂停/缓冲/拖动（或 speed<=0）时位置不随墙钟走，返回冻结值
        if (!anchorAdvancing || playbackSpeed <= 0f) return clampPosition(anchorPosition)
        val elapsed = (SystemClock.elapsedRealtime() - anchorRealtime).coerceAtLeast(0L)
        return clampPosition(anchorPosition + (elapsed * playbackSpeed.toDouble()).toLong())
    }

    /** 位置夹在 0..时长；时长未知（0）时不夹上界 */
    private fun clampPosition(pos: Long): Long {
        val duration = currentDurationMs
        val capped = if (duration > 0L) pos.coerceAtMost(duration) else pos
        return capped.coerceAtLeast(0L)
    }

    private fun tickProgress() {
        val registered = provider ?: return
        if (!hasAnchor) return
        if (lastPushedPlaying != anchorPlaying) {
            runCatching { registered.player.setPlaybackState(anchorPlaying) }
            lastPushedPlaying = anchorPlaying
        }
        runCatching { registered.player.setPosition(extrapolatedPosition()) }
    }

    // ================= 清洗与工具 =================

    /** 在线源/官方源统一清洗：去空行/占位行，按时间排序并校正行尾；不动 translation */
    private fun sanitizeLines(raw: List<RichLyricLine>?): List<RichLyricLine> {
        raw ?: return emptyList()
        val out = ArrayList<RichLyricLine>(raw.size)
        for (line in raw) {
            val text = line.text?.trim().orEmpty()
            if (text.isEmpty() || line.begin < 0L || isPlaceholderText(text)) continue
            out.add(line)
        }
        out.sortBy { it.begin }
        for (i in out.indices) {
            val line = out[i]
            val next = out.getOrNull(i + 1)?.begin
            var end = line.end
            if (next != null && (end <= line.begin || end > next)) end = next
            if (end <= line.begin) end = line.begin + FALLBACK_LINE_MS
            line.end = end
            line.duration = end - line.begin
        }
        return out
    }

    private fun isPlaceholderText(text: String): Boolean {
        if (PLACEHOLDER_PHRASES.any { text.contains(it) }) return true
        return text.none { it.isLetterOrDigit() } // "♪♪♪" / "***" 之类
    }

    /**
     * 星流侧看到的 media id 不一定带 spotify:track: 前缀，还可能是
     * `track:歌名|歌手` 这种非 id 形态 —— 只有 22 位 base62 才算真 track id，
     * 否则绝不拿它拼官方 URL（会 404 或查到别的歌）。
     */
    private fun extractSpotifyTrackId(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val core = when {
            value.startsWith(SPOTIFY_TRACK_PREFIX) -> value.substring(SPOTIFY_TRACK_PREFIX.length)
            value.startsWith(LEGACY_TRACK_PREFIX) -> value.substring(LEGACY_TRACK_PREFIX.length)
            else -> value
        }.substringBefore('|').substringBefore(' ').trim()
        return core.takeIf { SPOTIFY_ID_PATTERN.matches(it) }
    }

    private fun isNonMusicMedia(raw: String?): Boolean {
        val value = raw ?: return false
        return NON_MUSIC_PREFIXES.any { value.startsWith(it) }
    }

    private fun normalize(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.ENGLISH)

    /** 搜索前去掉 (feat. …) / (Live) / (Remastered) 之类噪声，标题本身不动 */
    private fun cleanSearchTitle(raw: String): String {
        val cleaned = TITLE_NOISE.replace(raw, " ").trim()
        return cleaned.ifEmpty { raw.trim() }
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    // ================= 本地缓存（上限 + 负缓存过期） =================

    private class Cached(val lines: List<RichLyricLine>?, val none: Boolean)

    private class Found(val lines: List<RichLyricLine>, val source: String)

    /**
     * 一次公开源查询的结果。
     *
     * [confirmed] 只在线上游「全部源都明确答复且没有任何传输失败」时为真——
     * 只有这种结论才配写负缓存，否则源临时抽风会把这首歌锁死半小时。
     */
    private class Online(val found: Found?, val confirmed: Boolean)

    private fun cacheDir(context: Context): File {
        val dir = File(File(context.cacheDir, "xinghe/lyric"), "spotify")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun cacheFile(context: Context, key: String): File =
        File(cacheDir(context), sha1(key) + ".json")

    private fun readCache(key: String): Cached? {
        val ctx = application ?: return null
        val file = cacheFile(ctx, key)
        if (!file.isFile) return null
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        // 版本不对就当没缓存：归一化/打分改版后，旧的「近期无词」记录会挡住新源
        if (obj.optInt("v", 0) != CACHE_VERSION) {
            logger.debug("spotify 缓存版本过期，丢弃：$key")
            runCatching { file.delete() }
            return null
        }
        if (obj.optBoolean("none", false)) {
            val age = System.currentTimeMillis() - obj.optLong("ts", 0L)
            if (age in 0..NEGATIVE_CACHE_TTL_MS) return Cached(null, true)
            runCatching { file.delete() }
            return null
        }
        val lines = parseCachedLines(obj) ?: return null
        // 时长校验：v2 及更早版本可能把「错歌」的拦截歌词写了进来（归属错位），
        // 这类条目的时间轴与本曲时长不相容，读到就删，不再采纳
        val duration = currentDurationMs
        if (!SpotifyProtoLyric.fitsDuration(lines, duration)) {
            logger.warn(
                "spotify 缓存歌词与本曲时长不符，删除：$key（末行 ${lines.last().begin}ms，时长 ${duration}ms）"
            )
            runCatching { file.delete() }
            return null
        }
        return Cached(lines, false)
    }

    private fun parseCachedLines(obj: JSONObject): List<RichLyricLine>? {
        val arr = obj.optJSONArray("lines") ?: return null
        if (arr.length() == 0) return null
        val out = ArrayList<RichLyricLine>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("t").trim()
            val begin = o.optLong("b", -1L)
            if (text.isEmpty() || begin < 0L) continue
            val line = RichLyricLine(
                begin = begin,
                end = o.optLong("e", begin).coerceAtLeast(begin + 1),
                text = text
            )
            val translation = o.optString("tr")
            if (translation.isNotBlank()) line.translation = translation
            out.add(line)
        }
        return out.ifEmpty { null }
    }

    private fun writeCache(key: String, lines: List<RichLyricLine>, source: String? = null) {
        val ctx = application ?: return
        runCatching {
            val arr = JSONArray()
            for (line in lines) {
                val o = JSONObject()
                o.put("b", line.begin)
                o.put("e", line.end)
                o.put("t", line.text.orEmpty())
                line.translation?.takeIf { it.isNotBlank() }?.let { o.put("tr", it) }
                arr.put(o)
            }
            val obj = JSONObject().put("v", CACHE_VERSION).put("lines", arr)
            if (!source.isNullOrBlank()) obj.put("src", source)
            cacheFile(ctx, key).writeText(obj.toString())
            pruneCache(ctx)
        }.onFailure { logger.warn("Spotify 歌词缓存写入失败：${it.message}") }
    }

    private fun writeNegativeCache(key: String) {
        val ctx = application ?: return
        runCatching {
            val obj = JSONObject()
                .put("v", CACHE_VERSION)
                .put("none", true)
                .put("ts", System.currentTimeMillis())
            cacheFile(ctx, key).writeText(obj.toString())
            pruneCache(ctx)
        }
    }

    /** 运行时存储红线：缓存文件上限，超了删最旧的 */
    private fun pruneCache(context: Context) {
        val files = cacheDir(context).listFiles() ?: return
        if (files.size <= CACHE_MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - CACHE_MAX_FILES)
            .forEach { it.delete() }
    }

    // ================= 通用 hook 工具 =================

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
        /** 标准前缀；个别版本/中转链路只给 track:，两种都认 */
        private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
        private const val LEGACY_TRACK_PREFIX = "track:"

        /** Spotify 曲目 id 固定 22 位 base62；不匹配就不是 id，别拼 URL */
        private val SPOTIFY_ID_PATTERN = Regex("[0-9A-Za-z]{22}")

        /** 官方 color-lyrics（仅抓到 authorization 时使用） */
        private const val API_BASE =
            "https://guc3-spclient.spotify.com/color-lyrics/v2/track/"
        private const val HTTP_TIMEOUT_MS = 8_000

        /** 抓头候选：p.k050 是本版本混淆名，类名随版本变，抓不到无副作用 */
        private val HEADER_CLASS_CANDIDATES = arrayOf("p.k050", "okhttp3.Headers")
        private val CAPTURED_HEADERS = setOf(
            "authorization", "client-token", "user-agent", "x-client-id"
        )

        /** 播客/广告/本地文件没有音乐歌词，别拿标题去搜（也别污染缓存） */
        private val NON_MUSIC_PREFIXES = listOf(
            "spotify:episode:", "spotify:show:", "spotify:ad:", "spotify:local:"
        )

        private const val TICK_MS = 48L

        /**
         * 与当前推算值差超过它就是拖动/跳转（毫秒）。
         * 小于它的偏差不是 seek —— 那是宿主重发旧快照或外推误差，不该整段回拨。
         */
        private const val SEEK_JUMP_MS = 2_000L

        private const val SWITCH_POS_GRACE_MS = 2_500L
        private const val SWITCH_POS_MAX_MS = 12_000L
        private const val POSITION_REPORT_MAX_AGE_MS = 8_000L

        /** 报告年龄小于它 = 宿主刚上报的当前位置，属权威值；超过就无法与旧歌残留区分 */
        private const val FRESH_STATE_MS = 1_500L

        private const val FALLBACK_LINE_MS = 3_000L

        private const val CACHE_MAX_FILES = 300

        /**
         * 缓存格式版本：升它等于让全部旧缓存失效。
         * v3 = 拦截歌词按曲目严格归属 + 读写都做时长相容校验：v2 及更早的条目里混着
         * 「归属错位」写进去的别曲歌词，必须整体作废（读到时版本不符即删）。
         */
        private const val CACHE_VERSION = 3

        /**
         * 负缓存 30 分钟而不是 12 小时：一次失败可能只是源临时抽风/没验明，
         * 锁一整天等于这首歌词今天彻底没救。
         */
        private const val NEGATIVE_CACHE_TTL_MS = 30L * 60 * 1000

        /** trackId 缓存 key 前缀（与 [onMetadata] 的 key 规则保持一致） */
        private const val TRACK_KEY_PREFIX = "id:"

        /** 刚发出歌词请求时，等 Spotify 自己解析完响应再回退到公开源（毫秒） */
        private const val PROTO_WAIT_MS = 1_200L

        private val PLACEHOLDER_PHRASES = listOf(
            "暂无歌词", "请欣赏音乐", "纯音乐", "没有填词", "未经许可"
        )

        /** (feat. X) / [Live] / （Remastered）这类对搜索有害的修饰 */
        private val TITLE_NOISE = Regex(
            """\s*[(\[（【][^)\]）】]*(?:feat|ft\.|with|remaster|live|version|伴奏|纯音乐|版)[^)\]）】]*[)\]）】]""",
            RegexOption.IGNORE_CASE
        )
    }
}
