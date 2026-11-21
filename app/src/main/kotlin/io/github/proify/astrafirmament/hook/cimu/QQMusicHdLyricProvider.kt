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
import io.github.proify.astrafirmament.lyric.LyricParsers
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.io.File
import java.lang.reflect.Executable
import java.util.concurrent.Executors

/**
 * QQ 音乐 HD / Pad 版（com.tencent.qqmusicpad）歌词提供者。
 *
 * 依据（QQ 音乐 Pad 版，参考词幕聚合版 QQMusicHD.java 的反编译结论）：
 *  1. 歌曲 id：`com.tencent.qqmusic.qplayer.core.player.controller.RemoteControlManager`
 *     里签名为 (com.tencent.qqmusic.openapisdk.model.SongInfo,
 *     com.tencent.qqmusic.openapisdk.core.player.IMediaMetaDataInterface) 的方法，
 *     其 SongInfo 参数有 `getSongId()`（另有 getSongMid/getMid 可做文件名候选）。
 *  2. 元数据：hook 框架 `android.media.session.MediaSession#setMetadata(MediaMetadata)`，
 *     读 TITLE / ARTIST / DURATION；id 换成新歌时才推。
 *  3. 歌词：参考版走自家网络服务拿 QRC，本项目不联网，改读宿主自己缓存的
 *     `qqmusic/qrc/<songmid>.qrc`（外部 files 与内部 files 都试），
 *     用项目内 LyricParsers.parseQrcFile 解密解析（含逐字时间）。
 *  4. 翻译开关：宿主 SharedPreferences "qqmusic" 的 `KEY_OPEN_TRANSLATION`
 *     （0 = 开启），运行期变化 hook `com.tencent.mmkv.MMKV#putInt(String,int)`。
 *  5. 播放状态：hook `MediaSession#setPlaybackState(PlaybackState)` 后转发。
 *
 * 只读宿主已缓存的文件与已上报的元数据，不改宿主行为。
 */
internal class QQMusicHdLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lookupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-qqmusichd-lyric").apply { isDaemon = true }
    }

    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null

    /** SongInfo 里抓到的 id 候选（songid / songmid / mid） */
    @Volatile private var pendingIds: List<String> = emptyList()

    @Volatile private var currentIds: List<String> = emptyList()
    @Volatile private var currentKey: String? = null
    @Volatile private var currentTitle: String? = null
    @Volatile private var currentArtist: String? = null
    @Volatile private var currentDuration: Long = 0L
    @Volatile private var signature: String? = null

    @Volatile private var playing = false
    @Volatile private var anchorPosition = 0L
    @Volatile private var anchorRealtime = 0L
    @Volatile private var hasAnchor = false
    @Volatile private var pushedPlaying: Boolean? = null
    @Volatile private var ticking = false
    @Volatile private var translationEnabled = true

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            tickProgress()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    fun installHooks() {
        hookApplicationLifecycle()
        hookSongInfo()
        hookMetadata()
        hookPlaybackState()
        hookTranslationSwitch()
        logger.info("QQMusicHD 歌词管线已装：$hostPackage / $processName")
    }

    // ================= 生命周期 =================

    private fun hookApplicationLifecycle() {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java
        )
        installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
            (chain.args.getOrNull(0) as? Application)?.let { application = it }
        }
    }

    // ================= 歌曲 id 抓取 =================

    private fun hookSongInfo() {
        val clazz = loadClass("com.tencent.qqmusic.qplayer.core.player.controller.RemoteControlManager")
        if (clazz == null) {
            logger.warn("QQMusicHD：找不到 RemoteControlManager，只能用 MediaSession 的 mediaId")
            return
        }
        var installed = 0
        for (method in clazz.declaredMethods) {
            val types = method.parameterTypes
            if (types.none { it.name.contains("SongInfo") }) continue
            method.isAccessible = true
            installProtectiveAfterHook(method, "RemoteControlManager.${method.name}") { chain, _ ->
                captureSongInfo(chain.args, types)
            }
            installed++
        }
        if (installed == 0) logger.warn("QQMusicHD：RemoteControlManager 里没有 SongInfo 方法")
    }

    private fun captureSongInfo(args: List<Any?>, types: Array<Class<*>>) {
        for (i in args.indices) {
            val type = types.getOrNull(i) ?: continue
            if (!type.name.contains("SongInfo")) continue
            val ids = extractIds(args[i]) ?: continue
            pendingIds = ids
            logger.debug("QQMusicHD 抓到歌曲 id 候选：$ids")
        }
    }

    /** 从 SongInfo 上抓若干 id 候选，用于猜 qrc 文件名 */
    private fun extractIds(source: Any?): List<String>? {
        source ?: return null
        val out = ArrayList<String>(4)
        for (name in ID_GETTERS) {
            val value = invokeGet(source, name) ?: continue
            val text = value.toString().trim()
            if (text.isEmpty() || text == "0" || text == "-1" || text == "null") continue
            out.add(text)
        }
        return out.distinct().ifEmpty { null }
    }

    // ================= 元数据 =================

    private fun hookMetadata() {
        runCatching {
            val method = MediaSession::class.java
                .getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            installProtectiveAfterHook(method, "MediaSession.setMetadata(QQMusicHD)") { chain, _ ->
                (chain.args.getOrNull(0) as? MediaMetadata)?.let { onMetadata(it) }
            }
        }.onFailure { logger.debug("QQMusicHD：MediaSession 挂载失败 " + it.message) }
    }

    private fun onMetadata(md: MediaMetadata) {
        val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
        // mediaId 最准，SongInfo 抓到的排后面
        val ids = (listOfNotNull(mediaId) + pendingIds).distinct()
        if (ids.isEmpty()) return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val key = ids.first()
        val sig = "$key|$title|$artist|$duration"
        if (sig == signature) return
        signature = sig
        currentIds = ids
        currentKey = key
        currentTitle = title
        currentArtist = artist
        currentDuration = duration
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = false
        logger.info("QQMusicHD 切歌：$title - $artist (${duration}ms) ids=$ids")
        if (ensureProvider() == null) return
        mainHandler.post { pushSong(emptyList()) }
        lookupExecutor.execute { loadLyric(ids, key) }
    }

    // ================= 歌词（宿主 qrc 缓存） =================

    private fun loadLyric(ids: List<String>, key: String) {
        try {
            val file = findQrcFile(ids)
            if (file == null) {
                logger.debug("QQMusicHD：没有本地 qrc 缓存（ids=$ids），保持占位")
                return
            }
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
            val parsed = runCatching { LyricParsers.parseQrcFile(bytes) }.getOrNull()
            val lines = mergeLines(parsed?.lines ?: emptyList())
            if (lines.isEmpty()) {
                logger.debug("QQMusicHD：qrc 解析不出行 ${file.name}")
                return
            }
            if (key != currentKey) return
            mainHandler.post { if (key == currentKey) pushSong(lines) }
            logger.info("QQMusicHD 命中本地 qrc：${file.absolutePath} → ${lines.size} 行")
        } catch (t: Throwable) {
            logger.error("QQMusicHD 读本地 qrc 失败", t)
        }
    }

    /** 宿主把 qrc 缓存在 files/qqmusic/qrc（内外两条路径），文件名一般是 songmid */
    private fun findQrcFile(ids: List<String>): File? {
        if (ids.isEmpty()) return null
        for (dir in qrcDirectories()) {
            if (!dir.isDirectory) continue
            for (id in ids) {
                dir.listFiles { f -> f.isFile && nameMatches(f.name, id) }
                    ?.firstOrNull()?.let { return it }
            }
            // 再扫一层子目录（部分版本按前缀分桶）
            val subs = dir.listFiles { f -> f.isDirectory }?.take(SUB_DIR_LIMIT).orEmpty()
            for (sub in subs) {
                for (id in ids) {
                    sub.listFiles { f -> f.isFile && nameMatches(f.name, id) }
                        ?.firstOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun qrcDirectories(): List<File> {
        val app = application ?: return emptyList()
        val roots = ArrayList<File>(2)
        runCatching { app.getExternalFilesDir(null) }.getOrNull()?.let { roots.add(it) }
        roots.add(app.filesDir)
        val out = ArrayList<File>(roots.size * QRC_SUB_DIRS.size)
        for (root in roots) {
            for (sub in QRC_SUB_DIRS) out.add(File(root, sub))
        }
        return out
    }

    private fun nameMatches(fileName: String, id: String): Boolean {
        if (fileName.startsWith(id)) return true
        val base = fileName.substringBeforeLast('.')
        return base == id || base.endsWith(id)
    }

    /**
     * QRC 里译文常作为「同一时间戳的第二行」出现；
     * 合并成原文行的 translation，并丢掉宿主的无效译文占位 `//`。
     */
    private fun mergeLines(raw: List<RichLyricLine>): List<RichLyricLine> {
        val out = ArrayList<RichLyricLine>(raw.size)
        var last: RichLyricLine? = null
        for (line in raw) {
            val text = line.text?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (text == INVALID_TRANSLATION) continue
            line.text = text
            val previous = last
            if (previous != null && previous.begin == line.begin &&
                previous.translation.isNullOrBlank()
            ) {
                previous.translation = text
                continue
            }
            out.add(line)
            last = line
        }
        normalize(out)
        return out
    }

    private fun normalize(lines: MutableList<RichLyricLine>) {
        for (i in lines.indices) {
            val line = lines[i]
            if (line.begin < 0) line.begin = 0
            val next = lines.getOrNull(i + 1)?.begin
            var end = line.end
            if (end <= line.begin) end = next?.takeIf { it > line.begin } ?: (line.begin + 3000L)
            if (next != null && end > next) end = next
            if (end <= line.begin) end = line.begin + 1
            line.end = end
            line.duration = end - line.begin
        }
    }

    // ================= 翻译开关 =================

    private fun readTranslationEnabled(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREF_QQMUSIC, Context.MODE_PRIVATE)
            .getInt(KEY_OPEN_TRANSLATION, 1) == 0
    }.getOrDefault(true)

    private fun hookTranslationSwitch() {
        runCatching {
            val clazz = loadClass("com.tencent.mmkv.MMKV") ?: return@runCatching
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "putInt" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Integer.TYPE
            } ?: return@runCatching
            method.isAccessible = true
            installProtectiveAfterHook(method, "MMKV.putInt(QQMusicHD)") { chain, _ ->
                val key = chain.args.getOrNull(0) as? String
                val value = chain.args.getOrNull(1) as? Int
                if (key == KEY_OPEN_TRANSLATION && value != null) {
                    translationEnabled = value == 0
                    provider?.player?.setDisplayTranslation(translationEnabled)
                }
            }
        }.onFailure { logger.debug("QQMusicHD：MMKV 翻译开关挂载失败 " + it.message) }
    }

    // ================= 播放状态 =================

    private fun hookPlaybackState() {
        runCatching {
            val method = MediaSession::class.java
                .getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            installProtectiveAfterHook(method, "MediaSession.setPlaybackState(QQMusicHD)") { chain, _ ->
                (chain.args.getOrNull(0) as? PlaybackState)?.let { onPlaybackState(it) }
            }
        }.onFailure { logger.debug("QQMusicHD：播放状态挂载失败 " + it.message) }
    }

    private fun onPlaybackState(state: PlaybackState) {
        playing = when (state.state) {
            PlaybackState.STATE_PLAYING -> true
            PlaybackState.STATE_NONE -> playing
            else -> false
        }
        if (state.position >= 0L) {
            anchorPosition = state.position
            anchorRealtime = SystemClock.elapsedRealtime()
            hasAnchor = true
        }
        if (ensureProvider() == null) return
        tickProgress()
        updateTicker()
    }

    private fun currentPosition(): Long {
        if (!hasAnchor) return 0L
        if (!playing) return anchorPosition
        val pos = anchorPosition + (SystemClock.elapsedRealtime() - anchorRealtime)
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

    // ================= Provider 与推送 =================

    private fun ensureProvider(): LyriconProvider? {
        provider?.let { return it }
        val ctx = application ?: currentApplication() ?: return null
        return try {
            translationEnabled = readTranslationEnabled(ctx)
            val logo = runCatching { ProviderLogo.fromSvg(Constants.ICON) }.getOrNull()
            val created = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = hostPackage,
                logo = logo,
                processName = processName
            ).apply {
                player.setDisplayTranslation(translationEnabled)
                player.setDisplayRoma(false)
                register()
            }
            provider = created
            logger.info("QQMusicHD provider 注册成功（$processName）")
            created
        } catch (t: Throwable) {
            logger.error("QQMusicHD provider 注册失败", t)
            null
        }
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) {
        null
    }

    private fun pushSong(lines: List<RichLyricLine>) {
        val p = ensureProvider() ?: return
        val id = currentKey ?: return
        val song = Song().apply {
            this.id = id
            name = currentTitle ?: ""
            artist = currentArtist
            duration = currentDuration
            lyrics = lines
        }
        val ok = runCatching { p.player.setSong(song) }.getOrDefault(false)
        logger.info("QQMusicHD 推送：${lines.size} 行 id=$id ok=$ok")
    }

    // ================= 小工具 =================

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private fun invokeGet(obj: Any?, name: String, vararg args: Any?): Any? {
        obj ?: return null
        for (m in obj.javaClass.methods) {
            if (m.name != name || m.parameterCount != args.size) continue
            val fits = args.indices.all { i -> argFits(m.parameterTypes[i], args[i]) }
            if (!fits) continue
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
                        logger.error("QQMusicHD after-hook 失败：$description", t)
                    }
                    result
                }
        } catch (t: Throwable) {
            logger.error("QQMusicHD hook 挂载失败：$description", t)
        }
    }

    private companion object {
        const val PREF_QQMUSIC = "qqmusic"
        const val KEY_OPEN_TRANSLATION = "KEY_OPEN_TRANSLATION"
        const val INVALID_TRANSLATION = "//"
        const val TICK_MS = 100L
        const val SUB_DIR_LIMIT = 32

        /** SongInfo 上可能的 id getter，命中哪个用哪个做 qrc 文件名候选 */
        val ID_GETTERS = listOf("getSongId", "getSongMid", "getSongmid", "getMid", "getMediaId")

        val QRC_SUB_DIRS = listOf("qqmusic/qrc", "qrc", "qqmusic/lyric")
    }
}
