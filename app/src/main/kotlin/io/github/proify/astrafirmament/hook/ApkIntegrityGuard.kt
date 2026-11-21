package io.github.proify.astrafirmament.hook

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 「智能文件」完整性闸门 —— 只有「确定下载完成、结构完整、此刻仍可读」的 APK 才允许出卡。
 *
 * 为什么不能只看文件存在 / 媒体库记录 / DownloadManager 的状态位：传输中的 APK 在文件
 * 系统里是**真实存在**的（浏览器和 DownloadManager 都直接往目标路径写），任何「看到
 * .apk 就弹」的判据都会在下载中途弹岛，用户点安装/分享只能拿到半个包。所以判据必须
 * 来自文件内容本身，且从同一个已打开的 fd 上取，避免「校验完又被写」的竞态：
 *  1. 头 4 字节必须是本地文件头 `PK\x03\x04`（不是占位/临时文件）；
 *  2. 尾部必须能找到 EOCD，且它声明的注释长度**正好收在文件末尾**——截断、拼接、
 *     还在追加写的文件都不可能满足这一条；
 *  3. 中央目录的偏移+大小必须落在文件范围内（zip64 除外，仍保留第 2 条保证）；
 *  4. 能解析出 AndroidManifest（`getPackageArchiveInfo` 非空），确认它是 APK 而不是
 *     恰好同后缀的普通 zip；
 *  5. `fstat` 出的 size/mtime 是「新鲜度自证」的唯一事实来源，数据库/媒体库的时间
 *     只当提示（厂商 ROM 会重写 last_modified，那是幽灵卡的来源之一）。
 *
 * 任一步失败都返回拒绝原因、调用方**不弹卡**——宁可不弹，也不弹一张点了就出错的卡。
 */
internal object ApkIntegrityGuard {

    /** 校验结论：ok=false 时 size/modifiedAt 无意义 */
    internal class Result(val size: Long, val modifiedAt: Long, val reject: Reject?) {
        val ok: Boolean get() = reject == null
    }

    /** 拒绝原因：只用于日志，出卡失败必须留证据，否则「为什么没弹」无从排查 */
    internal enum class Reject {
        NOT_OPENABLE,
        EMPTY,
        TOO_SMALL,
        SIZE_MISMATCH,
        TRUNCATED,
        MANIFEST_BROKEN
    }

    /**
     * 校验候选文件。
     *
     * @param expectedSize >0 时要求真实文件大小必须与之相等（DownloadManager 的 total）
     * @param pathHint     已解析出的真实路径，用来解析 manifest；null 时退化为纯结构校验
     * @param modifiedAtHint fstat 拿不到 mtime 时的兜底时间（毫秒），0 表示未知
     */
    fun verify(
        context: Context,
        uri: Uri,
        expectedSize: Long,
        pathHint: String?,
        modifiedAtHint: Long
    ): Result {
        // openFileDescriptor 成功本身就是前置条件：出卡后的安装/分享/删除都靠这个 uri
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return rejected(Reject.NOT_OPENABLE)
        val stat = runCatching { Os.fstat(pfd.fileDescriptor) }.getOrNull()
        val size = stat?.st_size ?: pfd.statSize
        var modifiedAt = stat?.st_mtime?.let { it * 1_000L } ?: 0L
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        try {
            if (size <= 0L) return rejected(Reject.EMPTY)
            if (size < MIN_APK_BYTES) return rejected(Reject.TOO_SMALL)
            if (expectedSize > 0L && size != expectedSize) return rejected(Reject.SIZE_MISMATCH)
            if (!zipIsComplete(stream.channel, size)) return rejected(Reject.TRUNCATED)
        } finally {
            runCatching { stream.close() }
        }
        val path = pathHint?.takeIf { it.startsWith("/") } ?: resolvePath(context, uri)
        if (path != null) {
            if (!archiveParsable(context, path)) return rejected(Reject.MANIFEST_BROKEN)
            val file = File(path)
            // 打开 fd 与解析 manifest 之间文件被改写/替换：说明这不是一个稳定的成品
            if (file.length() != size) return rejected(Reject.SIZE_MISMATCH)
            if (modifiedAt <= 0L) modifiedAt = file.lastModified()
        }
        return Result(size, if (modifiedAt > 0L) modifiedAt else modifiedAtHint, null)
    }

    private fun rejected(reason: Reject) = Result(0L, 0L, reason)

    /** 头 + 尾的结构校验：只有完整 zip 才可能同时满足本地文件头与收在 EOF 的 EOCD */
    private fun zipIsComplete(ch: FileChannel, size: Long): Boolean {
        val head = readAt(ch, 0L, LOCAL_HEADER.size) ?: return false
        if (!head.contentEquals(LOCAL_HEADER)) return false
        val tailLen = minOf(size, EOCD_SCAN_BYTES.toLong()).toInt()
        val tail = readAt(ch, size - tailLen, tailLen) ?: return false
        val idx = validEocdIndex(tail)
        if (idx < 0) return false
        val cdrSize = le32(tail, idx + CDR_SIZE_OFFSET)
        val cdrOffset = le32(tail, idx + CDR_OFFSET_OFFSET)
        // zip64 时这两个字段是哨兵值，中央目录位置在 zip64 记录里，只保留「EOCD 收在 EOF」
        if (cdrSize == ZIP64_MARK || cdrOffset == ZIP64_MARK) return true
        val eocdAt = size - tailLen + idx
        return cdrOffset > 0L && cdrOffset + cdrSize <= eocdAt
    }

    /**
     * 从尾往前找 EOCD。注释长度必须正好收在末尾——这一条把「半个文件」全部挡掉：
     * 传输中断、拼接、还在追加写的文件，长度都与 EOCD 的声明对不上。
     */
    private fun validEocdIndex(tail: ByteArray): Int {
        for (i in tail.size - EOCD_MIN_LEN downTo 0) {
            if (!isEocdAt(tail, i)) continue
            val commentLen = le16(tail, i + COMMENT_LEN_OFFSET)
            if (i + EOCD_MIN_LEN + commentLen == tail.size) return i
        }
        return -1
    }

    private fun isEocdAt(b: ByteArray, off: Int): Boolean =
        b[off] == EOCD_HEADER[0] && b[off + 1] == EOCD_HEADER[1] &&
            b[off + 2] == EOCD_HEADER[2] && b[off + 3] == EOCD_HEADER[3]

    private fun readAt(ch: FileChannel, offset: Long, len: Int): ByteArray? = runCatching {
        val buf = ByteBuffer.allocate(len)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n <= 0) return null
            pos += n
        }
        buf.array()
    }.getOrNull()

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /**
     * content:// 反查真实路径，只为让 getPackageArchiveInfo 能解析 manifest。
     * 查不到就退化成纯结构校验——不影响「完整」判定，也不因此拒卡。
     */
    private fun resolvePath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (!c.moveToFirst() || c.isNull(0)) null else c.getString(0) }
        }.getOrNull()?.takeIf { it.startsWith("/") }
    }

    /** APK 自证：能解析出包名才算「是 APK」，普通 zip / 目录型坏包都过不去 */
    private fun archiveParsable(context: Context, path: String): Boolean = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        }
        !info?.packageName.isNullOrBlank()
    }.getOrDefault(false)

    /** 1 KiB 以下不可能是可安装 APK（manifest + 中央目录 + 签名块都塞不下），纯粗筛 */
    private const val MIN_APK_BYTES = 1_024L

    /** EOCD 固定 22 字节 */
    private const val EOCD_MIN_LEN = 22

    /** EOCD 之后最多还能跟 65535 字节注释 */
    private const val EOCD_SCAN_BYTES = 22 + 65_535

    /** zip64 在 EOCD 里用 0xFFFFFFFF 占位 */
    private const val ZIP64_MARK = 0xFFFF_FFFFL

    /** EOCD 内 4 字节字段的偏移（相对 EOCD 起点） */
    private const val CDR_SIZE_OFFSET = 12
    private const val CDR_OFFSET_OFFSET = 16
    private const val COMMENT_LEN_OFFSET = 20

    private val LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val EOCD_HEADER = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
}
