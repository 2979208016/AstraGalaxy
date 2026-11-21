package io.github.proify.astrafirmament.hook.cimu

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
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
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Executable
import java.util.concurrent.Executors

/**
 * PowerAmp（com.maxmpz.audioplayer）歌词提供者。
 *
 * 依据（PowerAmp 3.x，参考词幕聚合版 PowerAmp.java 的反编译结论）：
 *  1. 切歌靠宿主广播 `com.maxmpz.audioplayer.TRACK_CHANGED`，extras：
 *     `id`(long，-1 无效)、`title`、`artist`、`album`、`durMs`(long)、`path`(文档 id)。
 *     path 形如 `primary/Music/x.mp3`，需按 SAF 规则换成 `primary:Music/x.mp3`，
 *     再借宿主持久化的 externalstorage 授权拼出可读 Uri（PowerAmp.java formatSafPath）。
 *  2. 歌词来自音频文件的内嵌标签（键匹配 (?i)\b(LYRICS)\b 的属性表）。
 *     参考版用 com.kyant.taglib（第三方库），本项目没有该依赖，这里自写最小
 *     读取器：ID3v2 (USLT/TXXX) 与 FLAC/Ogg 的 Vorbis Comment LYRICS 字段。
 *  3. 播放状态：hook 框架 `android.media.session.MediaSession#setPlaybackState`，
 *     直接转发给星流（参考版只做这一处，进度靠 PlaybackState 自带 position）。
 *
 * 不联网、不改宿主行为；只读宿主已播放文件的标签。
 */
internal class PowerAmpLyricProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val hostPackage: String,
    private val processName: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lookupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-poweramp-lyric").apply { isDaemon = true }
    }

    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var application: Application? = null
    @Volatile private var trackReceiver: BroadcastReceiver? = null

    @Volatile private var currentId: String? = null
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

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            tickProgress()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    fun installHooks() {
        hookApplicationLifecycle()
        hookMediaSession()
        logger.info("PowerAmp 歌词管线已装：$hostPackage / $processName")
    }

    // ================= 生命周期与广播 =================

    private fun hookApplicationLifecycle() {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java
        )
        installProtectiveAfterHook(method, "Instrumentation.callApplicationOnCreate") { chain, _ ->
            (chain.args.getOrNull(0) as? Application)?.let {
                application = it
                ensureProvider()
                registerTrackReceiver()
            }
        }
    }

    private fun registerTrackReceiver() {
        if (trackReceiver != null) return
        val app = application ?: currentApplication() ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                try {
                    onTrackChanged(intent)
                } catch (t: Throwable) {
                    logger.error("PowerAmp 广播处理失败", t)
                }
            }
        }
        try {
            val filter = IntentFilter(ACTION_TRACK_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            trackReceiver = receiver
            logger.info("PowerAmp 广播已注册：$ACTION_TRACK_CHANGED")
        } catch (t: Throwable) {
            logger.error("PowerAmp 广播注册失败", t)
        }
    }

    private fun onTrackChanged(intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id == -1L) return
        val title = intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: return
        val artist = intent.getStringExtra("artist")?.takeIf { it.isNotBlank() }
        val duration = intent.getLongExtra("durMs", 0L)
        val path = intent.getStringExtra("path")
        val sig = "$id|$title|$artist|$duration|$path"
        if (sig == signature) return
        signature = sig
        currentId = id.toString()
        currentTitle = title
        currentArtist = artist
        currentDuration = duration
        anchorPosition = 0L
        anchorRealtime = SystemClock.elapsedRealtime()
        hasAnchor = false
        logger.info("PowerAmp 切歌：$title - $artist (${duration}ms) path=$path")
        if (ensureProvider() == null) return
        val songId = id.toString()
        mainHandler.post { pushSong(emptyList()) }
        lookupExecutor.execute { loadEmbeddedLyric(songId, path) }
    }

    // ================= 内嵌歌词 =================

    private fun loadEmbeddedLyric(songId: String, path: String?) {
        try {
            if (path.isNullOrBlank()) return
            val raw = readLyricTag(path) ?: return
            val lines = buildLines(raw, currentDuration)
            if (lines.isEmpty()) {
                logger.debug("PowerAmp：标签里有歌词但解析不出行 $songId")
                return
            }
            if (songId != currentId) return
            mainHandler.post { if (songId == currentId) pushSong(lines) }
        } catch (t: Throwable) {
            logger.error("PowerAmp 读内嵌歌词失败", t)
        }
    }

    /** 先当普通路径读，再按 SAF 文档 id 借宿主持久授权读 */
    private fun readLyricTag(path: String): String? {
        val app = application ?: currentApplication() ?: return null
        for (file in candidateFiles(path)) {
            if (!file.isFile || !file.canRead()) continue
            val tag = runCatching {
                file.inputStream().use { readLyricTagFromStream(it) }
            }.getOrNull()
            if (!tag.isNullOrBlank()) return tag
        }
        val uri = resolveSafUri(app, path) ?: return null
        return runCatching {
            app.contentResolver.openInputStream(uri)?.use { readLyricTagFromStream(it) }
        }.getOrNull()
    }

    /** `/storage/emulated/0/...` 与 `primary:Music/...` 两种形态都还原成真实路径 */
    private fun candidateFiles(path: String): List<File> {
        val out = ArrayList<File>(3)
        val raw = path.trim()
        if (raw.startsWith("/")) out.add(File(raw))
        val docId = formatSafPath(raw) ?: return out
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        if (relative.isEmpty()) return out
        volumeDir(volume)?.let { out.add(File(it, relative)) }
        return out
    }

    private fun volumeDir(volume: String): String? = when {
        volume.equals("primary", true) -> "/storage/emulated/0"
        volume.all { it.isDigit() } && volume.isNotEmpty() -> "/storage/emulated/$volume"
        else -> null
    }

    /** 宿主给的 path 是文档 id 形式；`/` 开头或没有 `/` 的一律不认（与原实现一致） */
    private fun formatSafPath(path: String): String? {
        val trimmed = path.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("/")) return null
        val idx = trimmed.indexOf('/')
        if (idx <= 0) return null
        val volume = trimmed.substring(0, idx)
        val rest = trimmed.substring(idx + 1)
        if (volume.isEmpty() || rest.isEmpty()) return null
        return "$volume:$rest"
    }

    private fun resolveSafUri(app: Application, path: String): Uri? {
        val docId = formatSafPath(path) ?: return null
        val volume = docId.substringBefore(':')
        val resolver = app.contentResolver
        val permissions = runCatching { resolver.persistedUriPermissions }.getOrNull() ?: return null
        for (permission in permissions) {
            if (!permission.isReadPermission) continue
            val tree = permission.uri
            if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) continue
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            if (!treeId.substringBefore(':').equals(volume, true)) continue
            val uri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            }.getOrNull() ?: continue
            // 权限可能只是子树，能真的打开才认
            val readable = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (readable) return uri
        }
        logger.debug("PowerAmp：找不到可用的 SAF 授权 $docId")
        return null
    }

    // ================= 标签读取（最小 ID3v2 / Vorbis） =================

    private fun readLyricTagFromStream(stream: InputStream): String? {
        val input = if (stream is BufferedInputStream) stream else BufferedInputStream(stream, BUFFER)
        val magic = ByteArray(4)
        if (!readFully(input, magic)) return null
        // FLAC 的 "fLaC" 只有 4 字节，后面紧接元数据块；ID3 头共 10 字节
        if (String(magic, Charsets.ISO_8859_1) == "fLaC") return readFlacLyrics(input)
        if (String(magic, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val rest = ByteArray(6)
        if (!readFully(input, rest)) return null
        return readId3Lyrics(input, magic + rest)
    }

    private fun readId3Lyrics(input: InputStream, header: ByteArray): String? {
        val version = header[3].toInt() and 0xff
        if (version < 3) return null
        val flags = header[5].toInt() and 0xff
        val size = synchsafe(header, 6)
        if (size <= 0 || size > MAX_TAG_BYTES) return null
        val body = ByteArray(size)
        if (!readFully(input, body)) return null
        val tag = if (flags and 0x80 != 0) undoUnsync(body) else body
        var pos = 0
        if (flags and 0x40 != 0) {
            if (tag.size < 4) return null
            // v2.4 的扩展头大小含自身 4 字节，v2.3 不含
            val ext = if (version == 4) synchsafe(tag, 0) else beInt(tag, 0)
            pos = if (version == 4) ext else 4 + ext
            if (pos < 0 || pos >= tag.size) return null
        }
        while (pos + 10 <= tag.size) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            // 全 0 或非字母数字即进填充区，停止扫描
            if (!id.all { it.isLetterOrDigit() }) break
            val frameSize = if (version == 4) synchsafe(tag, pos + 4) else beInt(tag, pos + 4)
            if (frameSize <= 0 || pos + 10 + frameSize > tag.size) return null
            val frame = tag.copyOfRange(pos + 10, pos + 10 + frameSize)
            when (id) {
                "USLT", "ULT" -> decodeLyricFrame(frame, 4)?.let { return it }
                "TXXX", "TXX" -> {
                    val enc = frame.firstOrNull()?.toInt()?.and(0xff) ?: return null
                    val pair = splitTextFrame(frame, 1, enc)
                    if (pair != null && LYRIC_KEYS.any { pair.first.contains(it, true) }) {
                        val value = pair.second.trim()
                        if (value.isNotEmpty()) return value
                    }
                }
            }
            pos += 10 + frameSize
        }
        return null
    }

    private fun readFlacLyrics(input: InputStream): String? {
        var budget = MAX_TAG_BYTES
        while (budget > 0) {
            val head = ByteArray(4)
            if (!readFully(input, head)) return null
            budget -= 4
            val last = (head[0].toInt() and 0x80) != 0
            val type = head[0].toInt() and 0x7f
            val len = ((head[1].toInt() and 0xff) shl 16) or
                ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
            if (len < 0 || len > budget) return null
            if (type == 4) {
                val block = ByteArray(len)
                if (!readFully(input, block)) return null
                return parseVorbisComment(block)
            }
            skipFully(input, len.toLong())
            budget -= len
            if (last) return null
        }
        return null
    }

    private fun parseVorbisComment(block: ByteArray): String? {
        var pos = 0
        if (pos + 4 > block.size) return null
        val vendorLen = leInt(block, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen + 4 > block.size) return null
        pos += vendorLen
        val count = leInt(block, pos)
        pos += 4
        if (count < 0) return null
        for (i in 0 until count.coerceAtMost(4096)) {
            if (pos + 4 > block.size) return null
            val len = leInt(block, pos)
            pos += 4
            if (len < 0 || pos + len > block.size) return null
            val entry = String(block, pos, len, Charsets.UTF_8)
            pos += len
            val key = entry.substringBefore('=').trim()
            if (LYRIC_KEYS.any { key.equals(it, true) }) {
                val value = entry.substringAfter('=', "")
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private fun decodeLyricFrame(frame: ByteArray, headerLen: Int): String? {
        val enc = frame.firstOrNull()?.toInt()?.and(0xff) ?: return null
        return splitTextFrame(frame, headerLen, enc)?.second?.trim()?.ifEmpty { null }
    }

    /** 文本帧：编码字节 + 说明(以 0 结尾) + 正文；返回 (说明, 正文) */
    private fun splitTextFrame(frame: ByteArray, start: Int, enc: Int): Pair<String, String>? {
        if (frame.size <= start) return null
        val wide = enc == 1 || enc == 2
        val descEnd = findTerminator(frame, start, wide)
        val desc = if (descEnd < 0) "" else decodeText(frame, start, descEnd, enc)
        val textStart = if (descEnd < 0) start else descEnd + if (wide) 2 else 1
        if (textStart >= frame.size) return null
        return desc to decodeText(frame, textStart, frame.size, enc)
    }

    private fun findTerminator(bytes: ByteArray, from: Int, wide: Boolean): Int {
        var i = from
        while (i < bytes.size) {
            if (wide) {
                if (i + 1 < bytes.size && bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
                i += 2
            } else {
                if (bytes[i].toInt() == 0) return i
                i++
            }
        }
        return -1
    }

    private fun decodeText(bytes: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        val chunk = bytes.copyOfRange(from, to)
        return runCatching {
            when (enc) {
                1 -> if (chunk.size >= 2 && chunk[0] == 0xFF.toByte() && chunk[1] == 0xFE.toByte()) {
                    String(chunk, 2, chunk.size - 2, Charsets.UTF_16LE)
                } else if (chunk.size >= 2 && chunk[0] == 0xFE.toByte() && chunk[1] == 0xFF.toByte()) {
                    String(chunk, 2, chunk.size - 2, Charsets.UTF_16BE)
                } else {
                    String(chunk, Charsets.UTF_16LE)
                }
                2 -> String(chunk, Charsets.UTF_16BE)
                3 -> String(chunk, Charsets.UTF_8)
                else -> String(chunk, Charsets.ISO_8859_1)
            }.trim('\u0000')
        }.getOrDefault("")
    }

    // ================= 解析成行 =================

    private fun buildLines(raw: String, durationMs: Long): List<RichLyricLine> {
        val text = raw.trim('\u0000', '\uFEFF', ' ', '\n', '\r', '\t')
        if (text.isBlank()) return emptyList()
        val parsed = runCatching { LyricParsers.parseLrcText(text).lines }.getOrDefault(emptyList())
        val lines = if (parsed.count { !it.text.isNullOrBlank() } >= 2) {
            parsed
        } else {
            synthesizePlain(text, durationMs)
        }
        val merged = mergeSameTime(lines)
        merged.forEach {
            if (it.end <= it.begin) it.end = it.begin + 1
            it.duration = it.end - it.begin
        }
        return merged
    }

    /** 同一时间戳的第二行视为译文（常见双语 LRC），挂到原文行 translation 上 */
    private fun mergeSameTime(input: List<RichLyricLine>): List<RichLyricLine> {
        val out = ArrayList<RichLyricLine>(input.size)
        var lastBegin = -1L
        var last: RichLyricLine? = null
        for (line in input) {
            if (line.text.isNullOrBlank()) continue
            if (line.begin == lastBegin && last != null) {
                if (last.translation.isNullOrBlank()) last.translation = line.text?.trim().orEmpty()
                continue
            }
            out.add(line)
            lastBegin = line.begin
            last = line
        }
        return out
    }

    /** 没有时间戳的纯文本歌词：按歌曲时长均分，保证星流至少能逐行滚 */
    private fun synthesizePlain(text: String, durationMs: Long): List<RichLyricLine> {
        if (durationMs < 2000L) return emptyList()
        val parts = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") && it.length <= 120 }
        if (parts.size < 2) return emptyList()
        val per = durationMs / parts.size
        return parts.mapIndexed { i, s ->
            val begin = i * per
            RichLyricLine(begin = begin, end = (begin + per).coerceAtMost(durationMs), text = s)
        }
    }

    // ================= 播放状态 =================

    private fun hookMediaSession() {
        runCatching {
            val method = MediaSession::class.java
                .getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            installProtectiveAfterHook(method, "MediaSession.setPlaybackState(PowerAmp)") { chain, _ ->
                (chain.args.getOrNull(0) as? PlaybackState)?.let { onPlaybackState(it) }
            }
        }.onFailure { logger.debug("PowerAmp：MediaSession 挂载失败 " + it.message) }
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
            val logo = runCatching { ProviderLogo.fromSvg(Constants.ICON) }.getOrNull()
            val created = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = hostPackage,
                logo = logo,
                processName = processName
            ).apply {
                player.setDisplayTranslation(true)
                player.setDisplayRoma(false)
                register()
            }
            provider = created
            registerTrackReceiver()
            logger.info("PowerAmp provider 注册成功（$processName）")
            created
        } catch (t: Throwable) {
            logger.error("PowerAmp provider 注册失败", t)
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
        val id = currentId ?: return
        val song = Song().apply {
            this.id = id
            name = currentTitle
            artist = currentArtist
            duration = currentDuration
            lyrics = lines
        }
        val ok = runCatching { p.player.setSong(song) }.getOrDefault(false)
        logger.info("PowerAmp 推送：${lines.size} 行 id=$id ok=$ok")
    }

    // ================= 小工具 =================

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) return
                left -= 1
            } else {
                left -= skipped
            }
        }
    }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        var v = 0
        for (i in 0 until 4) v = (v shl 7) or (bytes[offset + i].toInt() and 0x7f)
        return v
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    /** ID3v2.3 的整体 unsynchronisation（0xFF 0x00 → 0xFF） */
    private fun undoUnsync(body: ByteArray): ByteArray {
        val out = ByteArray(body.size)
        var w = 0
        var i = 0
        while (i < body.size) {
            out[w++] = body[i]
            if (body[i] == 0xFF.toByte() && i + 1 < body.size && body[i + 1].toInt() == 0) i++
            i++
        }
        return out.copyOf(w)
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
                        logger.error("PowerAmp after-hook 失败：$description", t)
                    }
                    result
                }
        } catch (t: Throwable) {
            logger.error("PowerAmp hook 挂载失败：$description", t)
        }
    }

    private companion object {
        const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val TICK_MS = 100L
        const val BUFFER = 64 * 1024
        const val MAX_TAG_BYTES = 8 * 1024 * 1024
        val LYRIC_KEYS = listOf("LYRICS", "LYRIC", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")
    }
}
