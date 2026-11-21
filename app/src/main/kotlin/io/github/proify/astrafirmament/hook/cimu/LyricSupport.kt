package io.github.proify.astrafirmament.hook.cimu

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import io.github.proify.astrafirmament.lyric.LyricParsers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * 四个宿主共用的歌词支撑件（只读宿主已拿到的数据，不联网）：
 *
 *  1. [EmbeddedLyric]：读音频文件内嵌歌词标签。Gramophone / Symfonium 这类
 *     本地播放器不上报时间轴歌词，词幕原实现用 com.kyant.taglib 读 propertyMap
 *     里 key 命中 `(?i)\b(LYRICS)\b` 的值；本模块禁引入新依赖，故自己按容器格式读：
 *       - MP3：ID3v2 的 USLT 帧 / desc 含 LYRICS 的 TXXX 帧（v2.2 的 ULT/TXX）
 *       - FLAC：VORBIS_COMMENT 块里的 LYRICS 键
 *       - Ogg Vorbis / Opus：注释包头里的 LYRICS 键
 *       - MP4/M4A：`©lyr` 原子（data 类型 1=UTF-8 / 2=UTF-16）
 *       - APEv2 尾巴上的 Lyrics 项（末尾探测）
 *
 *  2. [PositionTicker]：跟随 MediaSession 的播放态/进度，按 200ms 推 setPosition，
 *     让逐行歌词有连续时间轴（Salt/MusicFree 只给「当前一句」，时间轴靠这里补）。
 *
 *  3. [TextLyricBridge]：把「一次只有一行文本」的宿主（Salt 的 Meizu ticker、
 *     MusicFree 的 LyricView.setText）累积成 Song 快照逐行推送，并按换行拆
 *     原文/译文。
 */
internal object EmbeddedLyric {

    /** 头部读 4MB：足够覆盖 ID3v2 标签、FLAC/Ogg 注释头（专辑封面除外） */
    private const val HEAD_BYTES = 4L * 1024 * 1024

    /** 尾部读 2MB：覆盖 MP4 的 moov（未 faststart）与 APEv2 标签 */
    private const val TAIL_BYTES = 2L * 1024 * 1024

    private val LYRIC_KEYS = setOf("LYRICS", "LYRIC", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LRC")
    private val APE_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS")
    private val MP4_LYR = byteArrayOf(0xA9.toByte(), 0x6C, 0x79, 0x72) // "©lyr"
    private val APE_SIG = "APETAGEX".toByteArray(Charsets.ISO_8859_1)
    private val VORBIS_MAGIC = byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
        'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
    private val OPUS_MAGIC = "OpusTags".toByteArray(Charsets.ISO_8859_1)

    /** 从 content:// 或 file:// 读内嵌歌词原文（没找到返回 null） */
    fun read(context: Context, uri: Uri): String? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            val stream: FileInputStream = if (uri.scheme == "file") {
                FileInputStream(File(uri.path ?: return null))
            } else {
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                FileInputStream(pfd.fileDescriptor)
            }
            stream.use { readChannel(it.channel) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { pfd?.close() }
        }
    }

    /** 内嵌歌词 → RichLyricLine 列表：优先时间轴，纯文本按总时长均分 */
    fun toLines(text: String, durationMs: Long): List<RichLyricLine> {
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()
        val parsed = LyricParsers.parseLrcText(raw)
        val timed = parsed.lines
            .filter { it.text?.let { t -> t.isNotBlank() && !LyricParsers.looksLikeNoise(t) } == true }
            .sortedBy { it.begin }
        val lines = if (LyricParsers.isUsable(timed)) pairDuplicateTimes(timed) else plainLines(raw, durationMs)
        for (i in lines.indices) fixEnd(lines[i], lines.getOrNull(i + 1))
        return lines
    }

    // ---------------- 容器读取 ----------------

    private fun readChannel(ch: FileChannel): String? {
        val size = ch.size()
        if (size <= 0L) return null
        val head = ByteArray(min(size, HEAD_BYTES).toInt())
        readFully(ch, 0L, head)
        val tailStart = max(0L, size - TAIL_BYTES)
        val tail = ByteArray((size - tailStart).toInt())
        readFully(ch, tailStart, tail)
        return parseId3(head)
            ?: parseFlac(head)
            ?: parseOgg(head)
            ?: parseMp4(head, tail)
            ?: parseApe(tail)
    }

    private fun readFully(ch: FileChannel, pos: Long, dst: ByteArray) {
        val buf = ByteBuffer.wrap(dst)
        var at = pos
        while (buf.hasRemaining()) {
            val n = runCatching { ch.read(buf, at) }.getOrDefault(-1)
            if (n <= 0) break
            at += n
        }
    }

    // ---------------- ID3v2（MP3） ----------------

    private fun parseId3(d: ByteArray): String? {
        if (d.size < 10) return null
        if (d[0] != 'I'.code.toByte() || d[1] != 'D'.code.toByte() || d[2] != '3'.code.toByte()) return null
        val major = d[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = d[5].toInt() and 0xFF
        if (flags and 0x80 != 0) return null // 整标签 unsynchronisation 极少见，放弃
        val tagSize = syncSafe(d, 6)
        val end = min(d.size, 10 + tagSize)
        var off = 10
        if (flags and 0x40 != 0) off += 4 + if (major == 4) syncSafe(d, off) else beInt(d, off)
        while (end - off >= 6) {
            val id = String(d, off, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val header = if (major == 2) 6 else 10
            if (end - off < header) break
            val frameSize = when (major) {
                2 -> ((d[off + 3].toInt() and 0xFF) shl 16) or
                    ((d[off + 4].toInt() and 0xFF) shl 8) or (d[off + 5].toInt() and 0xFF)
                4 -> syncSafe(d, off + 4)
                else -> beInt(d, off + 4)
            }
            if (frameSize <= 0) break
            val body = off + header
            val stop = min(body + frameSize, end)
            if (body >= stop) break
            val text = when (id) {
                "USLT", "ULT" -> id3Lyrics(d, body, stop)
                "TXXX", "TXX" -> id3Txxx(d, body, stop)
                else -> null
            }
            if (!text.isNullOrBlank()) return text
            off = stop
        }
        return null
    }

    private fun id3Lyrics(d: ByteArray, body: Int, end: Int): String? {
        if (body + 4 >= end) return null
        val enc = d[body].toInt() and 0xFF
        val textStart = skipTerminated(d, body + 4, end, enc)
        return decodeText(d, textStart, end, enc)
    }

    private fun id3Txxx(d: ByteArray, body: Int, end: Int): String? {
        if (body + 1 >= end) return null
        val enc = d[body].toInt() and 0xFF
        val descEnd = skipTerminated(d, body + 1, end, enc)
        val desc = decodeText(d, body + 1, descEnd, enc).uppercase()
        if (!desc.contains("LYRIC") && !desc.contains("LRC")) return null
        return decodeText(d, descEnd, end, enc)
    }

    // ---------------- FLAC / Ogg ----------------

    private fun parseFlac(d: ByteArray): String? {
        if (d.size < 8) return null
        if (d[0] != 'f'.code.toByte() || d[1] != 'L'.code.toByte() ||
            d[2] != 'a'.code.toByte() || d[3] != 'C'.code.toByte()
        ) return null
        var off = 4
        while (off + 4 <= d.size) {
            val head = d[off].toInt() and 0xFF
            val len = ((d[off + 1].toInt() and 0xFF) shl 16) or
                ((d[off + 2].toInt() and 0xFF) shl 8) or (d[off + 3].toInt() and 0xFF)
            val body = off + 4
            if (body + len > d.size) return null
            if (head and 0x7F == 4) {
                val text = vorbisComment(d, body, len)
                if (!text.isNullOrBlank()) return text
            }
            if (head and 0x80 != 0) break
            off = body + len
        }
        return null
    }

    private fun parseOgg(d: ByteArray): String? {
        if (d.size < 4) return null
        if (d[0] != 'O'.code.toByte() || d[1] != 'g'.code.toByte() ||
            d[2] != 'g'.code.toByte() || d[3] != 'S'.code.toByte()
        ) return null
        for (packet in oggPackets(d)) {
            if (packet.size > VORBIS_MAGIC.size && startsWith(packet, VORBIS_MAGIC)) {
                return vorbisComment(packet, VORBIS_MAGIC.size, packet.size - VORBIS_MAGIC.size)
            }
            if (packet.size > OPUS_MAGIC.size && startsWith(packet, OPUS_MAGIC)) {
                return vorbisComment(packet, OPUS_MAGIC.size, packet.size - OPUS_MAGIC.size)
            }
        }
        return null
    }

    private fun oggPackets(d: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val cur = ByteArrayOutputStream()
        var off = 0
        while (off + 27 <= d.size && out.size < 64) {
            val segCount = d[off + 26].toInt() and 0xFF
            val segStart = off + 27
            if (segStart + segCount > d.size) break
            var total = 0
            for (i in 0 until segCount) total += d[segStart + i].toInt() and 0xFF
            val dataStart = segStart + segCount
            if (dataStart + total > d.size) break
            var pos = dataStart
            for (i in 0 until segCount) {
                val n = d[segStart + i].toInt() and 0xFF
                cur.write(d, pos, n)
                pos += n
                if (n < 255) {
                    out.add(cur.toByteArray())
                    cur.reset()
                }
            }
            off = pos
        }
        return out
    }

    private fun vorbisComment(d: ByteArray, start: Int, len: Int): String? {
        val end = min(start + len, d.size)
        var off = start
        if (off + 4 > end) return null
        off += 4 + leInt(d, off)
        if (off + 4 > end) return null
        val count = leInt(d, off)
        off += 4
        for (i in 0 until min(count, 4096)) {
            if (off + 4 > end) break
            val n = leInt(d, off)
            off += 4
            if (n <= 0 || off + n > end) break
            val item = String(d, off, n, Charsets.UTF_8)
            off += n
            val eq = item.indexOf('=')
            if (eq <= 0) continue
            if (item.substring(0, eq).trim().uppercase() in LYRIC_KEYS) return item.substring(eq + 1)
        }
        return null
    }

    // ---------------- MP4 / APEv2 ----------------

    private fun parseMp4(head: ByteArray, tail: ByteArray): String? {
        for (d in arrayOf(head, tail)) {
            var i = indexOf(d, MP4_LYR, 0)
            while (i >= 0) {
                val text = mp4LyricPayload(d, i + MP4_LYR.size)
                if (!text.isNullOrBlank()) return text
                i = indexOf(d, MP4_LYR, i + MP4_LYR.size)
            }
        }
        return null
    }

    private fun mp4LyricPayload(d: ByteArray, off: Int): String? {
        if (off + 16 > d.size) return null
        if (String(d, off + 4, 4, Charsets.ISO_8859_1) != "data") return null
        val childSize = beInt(d, off)
        if (childSize < 16) return null
        val type = beInt(d, off + 8) and 0xFFFFFF
        val start = off + 16
        val end = min(off + childSize, d.size)
        if (start >= end) return null
        val charset = if (type == 2) Charsets.UTF_16 else Charsets.UTF_8
        return String(d, start, end - start, charset)
    }

    private fun parseApe(d: ByteArray): String? {
        val footer = lastIndexOf(d, APE_SIG)
        if (footer < 0 || footer + 32 > d.size) return null
        val tagSize = leInt(d, footer + 12)
        val count = leInt(d, footer + 16)
        var off = footer - (tagSize - 32)
        if (off < 0) return null
        for (i in 0 until min(count, 1024)) {
            if (off + 8 > footer) break
            val valueLen = leInt(d, off)
            off += 8
            val keyEnd = zeroAt(d, off, footer)
            if (keyEnd < 0) break
            val key = String(d, off, keyEnd - off, Charsets.ISO_8859_1).uppercase()
            off = keyEnd + 1
            if (valueLen <= 0 || off + valueLen > d.size) break
            if (key in APE_KEYS) return String(d, off, valueLen, Charsets.UTF_8)
            off += valueLen
        }
        return null
    }

    // ---------------- LRC 规整 ----------------

    /** 同一时间戳的多行：第二行当译文（词幕同款「原文 + 译文」交错写法） */
    private fun pairDuplicateTimes(src: List<RichLyricLine>): List<RichLyricLine> {
        val out = ArrayList<RichLyricLine>(src.size)
        var i = 0
        while (i < src.size) {
            val first = src[i]
            var j = i + 1
            var trans: String? = null
            while (j < src.size && src[j].begin == first.begin) {
                if (trans == null && validTranslation(first.text, src[j].text)) trans = src[j].text
                j++
            }
            if (trans != null) first.translation = trans
            out.add(first)
            i = j
        }
        return out
    }

    /** 无时间轴的纯文本歌词：按总时长均分，至少每行 500ms */
    private fun plainLines(text: String, durationMs: Long): List<RichLyricLine> {
        val body = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty() || LyricParsers.looksLikeNoise(t)) continue
            body.add(t)
        }
        if (body.isEmpty()) return emptyList()
        val step = if (durationMs > 0) (durationMs / body.size).coerceAtLeast(500L) else 1000L
        return body.mapIndexed { i, t ->
            RichLyricLine(begin = i * step, end = (i + 1) * step, text = t)
        }
    }

    private fun validTranslation(original: String?, trans: String?): Boolean {
        val o = original?.trim().orEmpty()
        val t = trans?.trim().orEmpty()
        if (t.isEmpty() || t == o) return false
        if (t == "//" || t == "///" || t == "***") return false
        return t.any { it.isLetterOrDigit() }
    }

    private fun fixEnd(line: RichLyricLine, next: RichLyricLine?) {
        val nextBegin = next?.begin
        var end = when {
            line.end > line.begin -> line.end
            nextBegin != null && nextBegin > line.begin -> nextBegin
            else -> line.begin + 4000L
        }
        if (nextBegin != null && end > nextBegin) end = nextBegin
        if (end <= line.begin) end = line.begin + 1L
        line.end = end
        line.duration = end - line.begin
    }

    // ---------------- 字节小工具 ----------------

    private fun decodeText(d: ByteArray, start: Int, end: Int, enc: Int): String {
        val e = min(end, d.size)
        if (start >= e) return ""
        return when (enc) {
            0 -> String(d, start, e - start, Charsets.ISO_8859_1)
            1 -> String(d, start, e - start, Charsets.UTF_16)
            2 -> String(d, start, e - start, Charsets.UTF_16BE)
            else -> String(d, start, e - start, Charsets.UTF_8)
        }
    }

    private fun skipTerminated(d: ByteArray, start: Int, end: Int, enc: Int): Int {
        if (enc == 1 || enc == 2) {
            var i = start
            while (i + 1 < end) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) return i + 2
                i += 2
            }
            return end
        }
        var i = start
        while (i < end) {
            if (d[i].toInt() == 0) return i + 1
            i++
        }
        return end
    }

    private fun beInt(d: ByteArray, off: Int): Int {
        if (off + 4 > d.size) return 0
        return ((d[off].toInt() and 0xFF) shl 24) or ((d[off + 1].toInt() and 0xFF) shl 16) or
            ((d[off + 2].toInt() and 0xFF) shl 8) or (d[off + 3].toInt() and 0xFF)
    }

    private fun leInt(d: ByteArray, off: Int): Int {
        if (off + 4 > d.size) return 0
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or ((d[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun syncSafe(d: ByteArray, off: Int): Int {
        if (off + 4 > d.size) return 0
        return ((d[off].toInt() and 0x7F) shl 21) or ((d[off + 1].toInt() and 0x7F) shl 14) or
            ((d[off + 2].toInt() and 0x7F) shl 7) or (d[off + 3].toInt() and 0x7F)
    }

    private fun indexOf(d: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty() || d.size < pattern.size) return -1
        outer@ for (i in max(from, 0)..d.size - pattern.size) {
            for (j in pattern.indices) if (d[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun lastIndexOf(d: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || d.size < pattern.size) return -1
        outer@ for (i in d.size - pattern.size downTo 0) {
            for (j in pattern.indices) if (d[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun zeroAt(d: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to && i < d.size) {
            if (d[i].toInt() == 0) return i
            i++
        }
        return -1
    }

    private fun startsWith(d: ByteArray, prefix: ByteArray): Boolean {
        if (d.size < prefix.size) return false
        for (i in prefix.indices) if (d[i] != prefix[i]) return false
        return true
    }
}

/**
 * 媒体进度跟随：宿主 MediaSession 只在切歌/播放暂停时上报 position，
 * 这里按锚点外推并每 200ms 推一次，保证逐行歌词的时间轴连续。
 */
internal class PositionTicker(
    private val ensureProvider: () -> LyriconProvider?
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var anchorPos = 0L
    @Volatile private var anchorAt = 0L
    @Volatile private var speed = 1f
    @Volatile private var playing = false
    @Volatile private var anchored = false
    @Volatile private var duration = 0L
    private var lastPlaying: Boolean? = null
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            if (playing) handler.postDelayed(this, TICK_MS)
        }
    }

    fun reset(durationMs: Long) {
        handler.post {
            duration = durationMs
            anchorPos = 0L
            anchorAt = 0L
            playing = false
            anchored = false
            lastPlaying = null
            stopTicker()
        }
    }

    fun onPlaybackState(state: PlaybackState?) {
        handler.post { applyState(state) }
    }

    fun position(): Long {
        if (!anchored) return 0L
        val elapsed = if (playing) SystemClock.elapsedRealtime() - anchorAt else 0L
        val pos = anchorPos + (elapsed * speed).toLong()
        return if (duration > 0) pos.coerceIn(0L, duration) else pos.coerceAtLeast(0L)
    }

    fun stop() {
        handler.post { stopTicker() }
    }

    private fun applyState(state: PlaybackState?) {
        state ?: return
        playing = when (state.state) {
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR, PlaybackState.STATE_NONE -> false
            else -> true
        }
        speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        var pos = state.position
        val now = SystemClock.elapsedRealtime()
        val age = if (state.lastPositionUpdateTime in 1..now) now - state.lastPositionUpdateTime else -1L
        if (pos >= 0L && playing && age in 0..8_000L) pos += (age * speed).toLong()
        if (pos >= 0L) {
            anchorPos = pos
            anchorAt = now
            anchored = true
        } else if (playing) {
            anchorPos = 0L
            anchorAt = now
            anchored = true
        }
        startTicker()
        tick()
    }

    private fun startTicker() {
        running = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        running = false
        handler.removeCallbacks(ticker)
    }

    private fun tick() {
        val p = ensureProvider() ?: return
        if (!anchored) return
        if (lastPlaying != playing) {
            runCatching { p.player.setPlaybackState(playing) }
            lastPlaying = playing
        }
        runCatching { p.player.setPosition(position()) }
    }

    private companion object {
        const val TICK_MS = 200L
    }
}

/**
 * 「一次只给一行文本」的歌词桥：Salt 的 Meizu ticker、MusicFree 的
 * LyricView.setText 都属于这类，拿不到整份时间轴。
 *
 * 做法：以当前播放进度为每行起点累积，每来一行就推一份 Song 快照；
 * 文本里的换行拆成原文 + 译文（宿主开译文时通常一行原文一行译文）。
 * 没有歌名之前不推（避免脏快照），元数据到了再补推。
 */
internal class TextLyricBridge(
    private val tag: String,
    private val logger: ModuleLogger,
    private val ensureProvider: () -> LyriconProvider?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = PositionTicker(ensureProvider)

    @Volatile private var title: String? = null
    private var artist: String? = null
    private var duration = 0L
    private var mediaId: String? = null
    private var signature: String? = null
    private val lines = ArrayList<RichLyricLine>()
    private var lastText: String? = null
    private var lastPushedKey: String? = null

    fun onMetadata(md: MediaMetadata?) {
        if (md != null) handler.post { applyMetadata(md) }
    }

    fun onPlaybackState(state: PlaybackState?) {
        handler.post { ticker.onPlaybackState(state) }
    }

    fun onLine(raw: String?) {
        handler.post { applyLine(raw) }
    }

    fun stop() {
        handler.post { ticker.stop() }
    }

    /** 是否已有歌名（宿主用通知/会话给元数据前，不要用歌词行反推标题） */
    fun hasSong(): Boolean = title != null

    private fun applyMetadata(md: MediaMetadata) {
        val newTitle = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
        val newArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
        val newDuration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val newId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.takeIf { it.isNotBlank() && it != "null" }
        val newSig = "$newTitle|$newArtist|$newDuration|$newId"
        if (newSig == signature) return
        val switched = signature != null && newTitle != null && newTitle != title
        signature = newSig
        artist = newArtist
        duration = newDuration
        mediaId = newId
        if (switched) {
            // 换歌：清掉上一首的累积，先推无词占位
            title = newTitle
            lines.clear()
            lastText = null
            ticker.reset(newDuration)
            publish(empty = true)
            return
        }
        title = newTitle
        ticker.reset(newDuration)
        if (title != null) publish(empty = lines.isEmpty())
    }

    private fun applyLine(raw: String?) {
        val text = raw?.trim() ?: return
        if (text.isEmpty()) return
        val (orig, trans) = splitBilingual(text)
        if (orig.isEmpty() || orig == lastText) return
        if (title != null && (orig == title || orig == artist)) return
        if (LyricParsers.looksLikeNoise(orig)) return
        if (title == null) return // 歌名未知先攒着，metadata 到了会重推
        val pos = ticker.position()
        val begin = maxOf(pos, (lines.lastOrNull()?.begin ?: -1L) + 1L)
        val prev = lines.lastOrNull()
        if (prev != null && begin > prev.begin) {
            prev.end = begin
            prev.duration = begin - prev.begin
        }
        if (lines.size >= MAX_LINES) lines.removeAt(0)
        val line = RichLyricLine(begin = begin, end = begin + 1, text = orig)
        line.translation = trans
        lines.add(line)
        lastText = orig
        publish(empty = false)
    }

    private fun splitBilingual(text: String): Pair<String, String?> {
        val parts = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "" to null
        val orig = parts.first()
        val trans = parts.getOrNull(1)?.takeIf { validTranslation(orig, it) }
        return orig to trans
    }

    private fun publish(empty: Boolean) {
        val p = ensureProvider() ?: return
        val name = title ?: return
        val list = if (empty) emptyList() else lines.sortedBy { it.begin }.map { it.copy() }
        val id = mediaId ?: "$tag|$name|$artist"
        val key = "$id|$name|${list.size}|${list.lastOrNull()?.begin}|${list.lastOrNull()?.text}"
        if (key == lastPushedKey) return
        val song = Song().apply {
            this.id = id
            this.name = name
            this.artist = artist
            this.duration = if (duration > 0) duration else (list.lastOrNull()?.end ?: 0L)
            this.lyrics = list
        }
        val ok = runCatching { p.player.setSong(song) }.getOrDefault(false)
        if (ok) lastPushedKey = key
        logger.debug("$tag 推歌词：${list.size} 行 ok=$ok")
    }

    private fun validTranslation(original: String, trans: String): Boolean {
        if (trans == original) return false
        if (trans == "//" || trans == "///" || trans == "***") return false
        return trans.any { it.isLetterOrDigit() }
    }

    private companion object {
        const val MAX_LINES = 800
    }
}
