package io.github.proify.astrafirmament.hook.cimu

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
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
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.lang.reflect.Executable
import java.util.concurrent.Executors

/**
 * Apple Music（com.apple.android.music）歌词提供者。
 *
 * 依据（宿主 Apple Music Android 版，参考词幕聚合版 Apple.java 的反编译结论）：
 *  1. 元数据：宿主把框架 MediaMetadata 交给自己内嵌的那份
 *     `android.support.v4.media.MediaMetadataCompat` 的**静态转换方法**
 *     （public + static + 单参 + 返回类型名含 "MediaMetadata"），
 *     args[0] 就是 android.media.MediaMetadata；另兜底 hook 框架
 *     `android.media.session.MediaSession#setMetadata`。
 *     字段：android.media.metadata.MEDIA_ID / TITLE / ARTIST / DURATION。
 *  2. 歌词：`com.apple.android.music.player.viewmodel.PlayerLyricsViewModel#buildTimeRangeToLyricsMap`，
 *     args[0].get() 是 native Song 对象，经 getSections()/getLines() 拿行文本与译文。
 *  3. 播放状态：`com.apple.android.music.playback.controller.LocalMediaPlayerController
 *     #onPlaybackStateChanged(未知类型, int, int)`，args[2] 是状态 int
 *     （-1 未知 / 0 停止 / 1 播放 / 2 暂停）；进度取
 *     `com.apple.android.music.playback.player.ExoMediaPlayer#getCurrentPosition()`，
 *     拖动落点 `#seekToPosition(long)`。
 *
 * 只读宿主已拿到的数据，不发起任何网络请求、不改宿主行为。
 */
internal class AppleMusicLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lookupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-apple-lyric").apply { isDaemon = true }
    }

    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null

    // 当前歌曲
    @Volatile private var currentId: String? = null
    @Volatile private var currentTitle: String? = null
    @Volatile private var currentArtist: String? = null
    @Volatile private var currentDuration: Long = 0L
    @Volatile private var signature: String? = null

    /** adamId → 已解析歌词（只留最近几首，纯内存） */
    private val lyricCache = LinkedHashMap<String, List<RichLyricLine>>()

    /** 宿主歌词 ViewModel（触发下载用，懒创建） */
    @Volatile private var lyricsViewModel: Any? = null

    // 播放状态与进度锚点
    @Volatile private var playing = false
    @Volatile private var anchorPosition = 0L
    @Volatile private var anchorRealtime = 0L
    @Volatile private var hasAnchor = false
    @Volatile private var pushedPlaying: Boolean? = null
    @Volatile private var ticking = false
    @Volatile private var exoPlayer: Any? = null
    @Volatile private var positionMethod: java.lang.reflect.Method? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            tickProgress()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    fun installHooks() {
        hookApplicationLifecycle()
        hookMetadata()
        hookLyricMethod()
        hookPlayback()
        logger.info("Apple Music 歌词管线已装：$hostPackage / $processName")
    }

    // ================= Provider =================

    private fun ensureProvider(): LyriconProvider? {
        provider?.let { return it }
        val ctx = application ?: currentApplication() ?: return null
        return try {
            val logo = runCatching { ProviderLogo.fromSvg(Constants.ICON) }.getOrNull()
            val created = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = hostPackage,
                logo = logo,
                processName = processName
            ).apply {
                // 译文始终随行推送，显不显示交给显示侧
                player.setDisplayTranslation(true)
                player.setDisplayRoma(false)
                register()
            }
            provider = created
            logger.info("Apple provider 注册成功（$processName）")
            created
        } catch (t: Throwable) {
            logger.error("Apple provider 注册失败", t)
            null
        }
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) {
        null
    }

    private fun hookApplicationLifecycle() {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java
        )
        installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
            (chain.args.getOrNull(0) as? Application)?.let { application = it }
        }
    }

    // ================= 元数据 =================

    private fun hookMetadata() {
        val compat = loadClass("android.support.v4.media.MediaMetadataCompat")
        val convert = compat?.declaredMethods?.firstOrNull { m ->
            java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 1 &&
                m.returnType.simpleName.contains("MediaMetadata")
        }
        if (convert != null) {
            installProtectiveAfterHook(convert, "MediaMetadataCompat 静态转换") { chain, _ ->
                (chain.args.getOrNull(0) as? MediaMetadata)?.let { onMetadata(it) }
            }
        } else {
            logger.debug("Apple：未找到 MediaMetadataCompat 静态转换方法，走 MediaSession 兜底")
        }
        // 兜底：无论宿主怎么包一层，最终都会调框架 MediaSession.setMetadata
        runCatching {
            val setMetadata = MediaSession::class.java
                .getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            installProtectiveAfterHook(setMetadata, "MediaSession.setMetadata(Apple)") { chain, _ ->
                (chain.args.getOrNull(0) as? MediaMetadata)?.let { onMetadata(it) }
            }
        }.onFailure { logger.debug("Apple：MediaSession 兜底挂载失败 " + it.message) }
    }

    private fun onMetadata(md: MediaMetadata) {
        val id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.takeIf { it.isNotBlank() && it != "null" } ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val sig = "$id|$title|$artist|$duration"
        if (sig == signature) return
        signature = sig
        currentId = id
        currentTitle = title
        currentArtist = artist
        currentDuration = duration
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = false
        logger.info("Apple 切歌：$title - $artist (${duration}ms) id=$id")
        if (ensureProvider() == null) return
        val cached = synchronized(lyricCache) { lyricCache[id] }
        mainHandler.post { pushSong(cached ?: emptyList()) }
        if (cached == null) mainHandler.post { requestLyrics(id) }
        updateTicker()
    }

    /**
     * 触发宿主自己下载这份歌词：new 一个空宿主 Song 填 id + hasLyrics，
     * 交给 PlayerLyricsViewModel.loadLyrics。失败只记日志（宿主版本变了就不催）。
     */
    private fun requestLyrics(mediaId: String) {
        runCatching {
            val vm = lyricsViewModel ?: createLyricsViewModel() ?: return
            val hostSongClass = classLoader.loadClass("com.apple.android.music.model.Song")
            val hostSong = hostSongClass.getDeclaredConstructor().newInstance()
            invokeSet(hostSong, "setId", mediaId)
            invokeSet(hostSong, "setHasLyrics", true)
            val load = vm.javaClass.declaredMethods.firstOrNull {
                it.name == "loadLyrics" && it.parameterCount == 1
            } ?: return
            load.isAccessible = true
            load.invoke(vm, hostSong)
        }.onFailure { logger.debug("Apple：触发歌词下载失败 ${it.message}") }
    }

    private fun createLyricsViewModel(): Any? {
        val clazz = loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            ?: return null
        val ctor = clazz.declaredConstructors.firstOrNull { c ->
            c.parameterCount == 1 && Application::class.java.isAssignableFrom(c.parameterTypes[0])
        } ?: return null
        ctor.isAccessible = true
        val created = ctor.newInstance(application ?: currentApplication() ?: return null)
        lyricsViewModel = created
        return created
    }

    private fun invokeSet(obj: Any?, name: String, value: Any?): Boolean {
        obj ?: return false
        val m = obj.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && argFits(it.parameterTypes[0], value)
        } ?: return false
        return runCatching {
            m.isAccessible = true
            m.invoke(obj, value)
            true
        }.getOrDefault(false)
    }

    // ================= 歌词解析 =================

    private fun hookLyricMethod() {
        val clazz = loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
        if (clazz == null) {
            logger.warn("Apple：找不到 PlayerLyricsViewModel，仅推送歌曲信息")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { it.name == "buildTimeRangeToLyricsMap" }
        if (method == null) {
            logger.warn("Apple：找不到 buildTimeRangeToLyricsMap，歌词行不可用")
            return
        }
        method.isAccessible = true
        installProtectiveAfterHook(method, "PlayerLyricsViewModel.buildTimeRangeToLyricsMap") { chain, _ ->
            val holder = chain.args.getOrNull(0) ?: return@installProtectiveAfterHook
            val nativeSong = invokeGet(holder, "get") ?: return@installProtectiveAfterHook
            lookupExecutor.execute { parseAndPush(nativeSong) }
        }
    }

    private fun parseAndPush(nativeSong: Any) {
        try {
            val id = asString(invokeGet(nativeSong, "getAdamId"))
            if (id.isNullOrBlank()) return
            val lines = parseNativeSong(nativeSong)
            if (lines.isEmpty()) return
            synchronized(lyricCache) {
                lyricCache[id] = lines
                if (lyricCache.size > CACHE_SONGS) {
                    lyricCache.keys.firstOrNull()?.let { lyricCache.remove(it) }
                }
            }
            if (id == currentId) {
                mainHandler.post { if (currentId == id) pushSong(lines) }
            }
        } catch (t: Throwable) {
            logger.error("Apple 歌词解析失败", t)
        }
    }

    /** native Song → 行列表：getSections().get(i).get() → getLines().get(j).get() */
    private fun parseNativeSong(song: Any): List<RichLyricLine> {
        val out = ArrayList<RichLyricLine>()
        for (rawSection in readList(invokeGet(song, "getSections"))) {
            val section = unwrap(rawSection) ?: continue
            for (rawLine in readList(invokeGet(section, "getLines"))) {
                val line = unwrap(rawLine) ?: continue
                buildLine(line)?.let { out.add(it) }
            }
        }
        out.sortBy { it.begin }
        normalize(out)
        return out
    }

    private fun buildLine(line: Any): RichLyricLine? {
        val text = stripHtml(asString(invokeGet(line, "getHtmlLineText"))) ?: return null
        val begin = asLong(invokeGet(line, "getBegin")).coerceAtLeast(0L)
        var end = asLong(invokeGet(line, "getEnd"))
        if (end <= begin) {
            val dur = asLong(invokeGet(line, "getDuration"))
            end = if (dur > 0) begin + dur else begin + 1
        }
        val built = RichLyricLine(begin = begin, end = end, text = text)
        // 译文/音译：宿主给的是 HTML，去标签后非空才有意义；译文与原文相同则不填
        stripHtml(asString(invokeGet(line, "getHtmlTranslationLineText")))
            ?.takeIf { it != text }?.let { built.translation = it }
        stripHtml(asString(invokeGet(line, "getHtmlPronunciationLineText")))
            ?.let { built.roma = it }
        return built
    }

    /** 行尾规整：不越过下一行起点，duration 与 begin/end 保持一致 */
    private fun normalize(lines: MutableList<RichLyricLine>) {
        for (i in lines.indices) {
            val line = lines[i]
            if (line.begin < 0) line.begin = 0
            var end = if (line.end > line.begin) line.end else line.begin + 1
            val next = lines.getOrNull(i + 1)?.begin
            if (next != null && end > next) end = next
            if (end <= line.begin) end = line.begin + 1
            line.end = end
            line.duration = end - line.begin
        }
    }

    // ================= 播放状态 / 进度 =================

    private fun hookPlayback() {
        hookExoPlayer()
        hookLocalController()
        // 宿主翻译开关变化时同步给显示侧（歌词数据本身一直带译文）
        runCatching {
            val cls = classLoader.loadClass("com.apple.android.music.utils.AppSharedPreferences")
            val m = cls.declaredMethods.firstOrNull {
                it.name == "setLyricsTranslationSelected" && it.parameterCount == 1
            } ?: return@runCatching
            installProtectiveAfterHook(m, "AppSharedPreferences.setLyricsTranslationSelected") { chain, _ ->
                val on = chain.args.getOrNull(0) as? Boolean ?: true
                provider?.player?.setDisplayTranslation(on)
            }
        }
    }

    private fun hookExoPlayer() {
        val clazz = loadClass("com.apple.android.music.playback.player.ExoMediaPlayer") ?: return
        for (ctor in clazz.declaredConstructors) {
            installProtectiveAfterHook(ctor, "ExoMediaPlayer()") { chain, _ ->
                exoPlayer = chain.thisObject
                // 参考实现用 getDeclaredMethod（可能非 public），这里按名+无参扫全部方法
                positionMethod = runCatching {
                    chain.thisObject!!.javaClass.declaredMethods
                        .firstOrNull { it.name == "getCurrentPosition" && it.parameterCount == 0 }
                        ?.apply { isAccessible = true }
                }.getOrNull()
            }
        }
        val seek = clazz.declaredMethods.firstOrNull {
            it.name == "seekToPosition" && it.parameterCount == 1
        }
        if (seek != null) {
            installProtectiveAfterHook(seek, "ExoMediaPlayer.seekToPosition") { chain, _ ->
                val pos = asLong(chain.args.getOrNull(0))
                if (pos >= 0) {
                    anchorPosition = pos
                    anchorRealtime = SystemClock.elapsedRealtime()
                    hasAnchor = true
                    tickProgress()
                }
            }
        }
    }

    private fun hookLocalController() {
        val clazz = loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
        if (clazz == null) {
            logger.warn("Apple：找不到 LocalMediaPlayerController，播放状态靠进度轮询推断")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "onPlaybackStateChanged" && it.parameterCount == 3 &&
                it.parameterTypes[1] == Integer.TYPE && it.parameterTypes[2] == Integer.TYPE
        }
        if (method == null) {
            logger.warn("Apple：onPlaybackStateChanged 签名不符（版本可能变了）")
            return
        }
        method.isAccessible = true
        installProtectiveAfterHook(method, "LocalMediaPlayerController.onPlaybackStateChanged") { chain, _ ->
            onHostPlaybackState(asLong(chain.args.getOrNull(2)).toInt())
        }
    }

    /** 宿主状态 int：-1 未知 / 0 停止 / 1 播放 / 2 暂停 */
    private fun onHostPlaybackState(state: Int) {
        if (state < -1 || state > 2) return
        playing = state == 1
        if (!playing) anchorPosition = currentPosition()
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = true
        tickProgress()
        updateTicker()
    }

    private fun currentPosition(): Long {
        val player = exoPlayer
        val method = positionMethod
        if (playing && player != null && method != null) {
            val raw = runCatching { (method.invoke(player) as? Number)?.toLong() }.getOrNull()
            if (raw != null && raw >= 0) {
                anchorPosition = raw
                anchorRealtime = SystemClock.elapsedRealtime()
                hasAnchor = true
                return raw
            }
        }
        if (!hasAnchor) return 0L
        if (!playing) return anchorPosition
        val elapsed = SystemClock.elapsedRealtime() - anchorRealtime
        val pos = anchorPosition + elapsed
        return if (currentDuration > 0) pos.coerceIn(0L, currentDuration) else pos
    }

    private fun tickProgress() {
        val p = provider ?: return
        if (pushedPlaying != playing) {
            runCatching { p.player.setPlaybackState(playing) }
            pushedPlaying = playing
        }
        runCatching { p.player.setPosition(currentPosition()) }
    }

    private fun updateTicker() {
        if (playing && !ticking) {
            ticking = true
            mainHandler.removeCallbacks(ticker)
            mainHandler.post(ticker)
        } else if (!playing && ticking) {
            ticking = false
            mainHandler.removeCallbacks(ticker)
        }
    }

    // ================= 推送 =================

    private fun pushSong(lines: List<RichLyricLine>) {
        val p = ensureProvider() ?: return
        val id = currentId ?: return
        val song = Song().apply {
            this.id = id
            name = currentTitle
            artist = currentArtist
            duration = currentDuration
            lyrics = lines
        }
        val ok = runCatching { p.player.setSong(song) }.getOrDefault(false)
        logger.info("Apple 推送：${lines.size} 行 id=$id ok=$ok")
    }

    // ================= 反射工具 =================

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    /** 调用无参/带参 getter；签名对不上就换下一个同名方法 */
    private fun invokeGet(obj: Any?, name: String, vararg args: Any?): Any? {
        obj ?: return null
        for (m in obj.javaClass.methods) {
            if (m.name != name || m.parameterCount != args.size) continue
            val ok = args.indices.all { i -> argFits(m.parameterTypes[i], args[i]) }
            if (!ok) continue
            val r = runCatching {
                m.isAccessible = true
                m.invoke(obj, *args)
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    private fun argFits(type: Class<*>, arg: Any?): Boolean = when {
        arg == null -> !type.isPrimitive
        type == Integer.TYPE -> arg is Int
        type == java.lang.Long.TYPE -> arg is Long
        type == java.lang.Boolean.TYPE -> arg is Boolean
        else -> type.isInstance(arg)
    }

    /** 宿主列表是 native 容器：size() / get(long)；元素还要额外 .get() 一层 */
    private fun readList(container: Any?): List<Any> {
        container ?: return emptyList()
        if (container is List<*>) return container.filterNotNull()
        val size = asLong(invokeGet(container, "size")).toInt()
        if (size <= 0) return emptyList()
        val out = ArrayList<Any>(size.coerceAtMost(4096))
        for (i in 0 until size) {
            val e = invokeGet(container, "get", i.toLong()) ?: continue
            out.add(e)
        }
        return out
    }

    private fun unwrap(element: Any?): Any? {
        element ?: return null
        if (element is List<*> || element is Map<*, *>) return element
        return invokeGet(element, "get") ?: element
    }

    private fun asLong(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun asString(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is CharSequence -> v.toString()
        else -> v.toString()
    }

    /** 宿主歌词文本是 HTML（<b>、<br>、实体），转纯文本并清掉纯占位行 */
    private fun stripHtml(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&#39;", "'")
        s = s.trim()
        return s.ifBlank { null }
    }

    private fun isLyricEnabled(): Boolean = runCatching {
        val prefs = module.getRemotePreferences(ModulePrefs.NAME)
        prefs.getBoolean(ModulePrefs.KEY_ENABLED, true) &&
            prefs.getBoolean(ModulePrefs.KEY_LYRIC, true)
    }.getOrDefault(true)

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
                    if (!isLyricEnabled()) return@intercept result
                    try {
                        callback(chain, result)
                    } catch (t: Throwable) {
                        logger.error("Apple after-hook 失败：$description", t)
                    }
                    result
                }
        } catch (t: Throwable) {
            logger.error("Apple hook 挂载失败：$description", t)
        }
    }

    private companion object {
        private const val TICK_MS = 100L
        private const val CACHE_SONGS = 4
    }
}
