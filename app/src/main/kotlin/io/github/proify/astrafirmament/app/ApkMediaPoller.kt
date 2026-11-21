package io.github.proify.astrafirmament.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.github.proify.astrafirmament.capability.link.DevLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 媒体库兜底通道（MediaStore）。
 *
 * 只在**确实有存储读取权限**时才启用：root 扫描走 shell 身份，媒体库这条走 App 权限，
 * 没有权限时它永远 0 命中，开着只是每 20s 空转（原实现就是这样，本来也没有声明权限）。
 * 没权限 → 记一次原因并彻底停掉轮询；之后由扫描器在解锁/补扫时调 [retryStart] 重试，
 * 权限/环境变了才可能恢复。
 */
internal object ApkMediaPoller {
    /** 只关心最近这段时间落盘的（媒体库索引可能比文件晚一会儿，留宽一点） */
    private const val FRESH_MS = 30L * 60L * 1_000L
    private const val POLL_MS = 20_000L
    private const val FIRST_DELAY_MS = 8_000L

    /** 一轮最多投递几个：媒体库一次能刷出很多历史记录，别把队列灌满 */
    private const val MAX_HITS = 8
    private const val MAX_SEEN = 64

    private const val THREAD_NAME = "xinghe-media-poll"

    private val polling = AtomicBoolean(false)
    private val scanBusy = AtomicBoolean(false)

    @Volatile private var disabledLogged = false

    /** 已提交过的路径：媒体库的 DATE_MODIFIED 会随更新变化，避免同一文件重复投递 */
    private val seen = LinkedHashSet<String>()

    private val handler = Handler(Looper.getMainLooper())

    /** 应用启动时调用；没有权限就只记一次日志，不再排 20s 空转 */
    fun start(context: Context) {
        retryStart(context)
    }

    /** 权限可能刚被授予时调用（扫描器的解锁补扫会捎带一次）：有权限就排程，没权限就安静退出 */
    fun retryStart(context: Context) {
        val app = context.applicationContext
        if (!hasMediaAccess(app)) {
            val wasPolling = polling.getAndSet(false)
            if (wasPolling || !disabledLogged) {
                disabledLogged = true
                DevLog.refresh(app)
                DevLog.w("媒体库通道未启用：缺少 READ_MEDIA_IMAGES/VIDEO 或 MANAGE_EXTERNAL_STORAGE（root 扫描不受影响）")
            }
            return
        }
        if (!polling.compareAndSet(false, true)) return
        disabledLogged = false
        DevLog.refresh(app)
        DevLog.i("媒体库通道已启用：每 ${POLL_MS / 1_000}s 兜底扫一次")
        handler.postDelayed({ tick(app) }, FIRST_DELAY_MS)
    }

    private fun tick(app: Context) {
        if (!polling.get()) return
        // 权限可能在运行中被撤销：撤销后立刻停，并说明原因
        if (!hasMediaAccess(app)) {
            polling.set(false)
            disabledLogged = true
            DevLog.w("媒体库通道已停用：存储读取权限丢失")
            return
        }
        handler.postDelayed({ tick(app) }, POLL_MS)
        if (!scanBusy.compareAndSet(false, true)) return
        Thread({
            try {
                scanOnce(app)
            } catch (error: Throwable) {
                DevLog.w("媒体库扫描异常：${error.message}")
            } finally {
                scanBusy.set(false)
            }
        }, THREAD_NAME).apply { isDaemon = true }.start()
    }

    private fun scanOnce(app: Context) {
        DevLog.refresh(app)
        val since = System.currentTimeMillis() - FRESH_MS
        val rows = collect(app, since)
        if (rows.isEmpty()) return
        val submitted = ArrayList<ApkBridge.ApkRequest>(rows.size)
        synchronized(seen) {
            for (row in rows) {
                if (submitted.size >= MAX_HITS) break
                if (!seen.add(row.path)) continue
                submitted.add(
                    ApkBridge.ApkRequest(
                        row.path,
                        row.name,
                        row.size,
                        row.modifiedAt,
                        FRESH_MS,
                        "media-poll",
                        "",
                    )
                )
            }
        }
        // seen 只是「本进程内投过谁」的短名单：按插入序淘汰最旧的，别随媒体库无限增长
        synchronized(seen) {
            while (seen.size > MAX_SEEN) {
                val iterator = seen.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
        for (request in submitted) {
            SmartApkIntake.run(app, request)
        }
        if (submitted.isNotEmpty()) DevLog.i("媒体库通道投递 ${submitted.size} 个文件")
    }

    private fun collect(app: Context, since: Long): List<Row> {
        val rows = ArrayList<Row>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = MediaStore.Files.FileColumns.DATE_MODIFIED + " >= ?"
        val arguments = arrayOf((since / 1_000L).toString())
        val order = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"
        runCatching {
            app.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                arguments,
                order,
            )?.use { cursor ->
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val timeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    if (rows.size >= MAX_HITS) break
                    val path = cursor.getString(pathIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: path.substringAfterLast('/')
                    if (!ApkBridge.looksLikeApk(name)) continue
                    if (!ApkBridge.isAllowedPath(path)) continue
                    rows.add(
                        Row(
                            path,
                            name,
                            cursor.getLong(sizeIndex),
                            cursor.getLong(timeIndex) * 1_000L,
                        )
                    )
                }
            }
        }.onFailure { DevLog.w("媒体库查询失败：${it.message}") }
        return rows
    }

    private fun hasMediaAccess(context: Context): Boolean {
        // 全盘管理权限（如果没在 Manifest 声明，系统恒为 false，这里只是分支之一）
        if (Environment.isExternalStorageManager()) return true
        // Android 13+ 的分媒体权限建常量在旧系统不存在，这里直接用字符串比对
        if (granted(context, "android.permission.READ_MEDIA_IMAGES")) return true
        if (granted(context, "android.permission.READ_MEDIA_VIDEO")) return true
        return granted(context, "android.permission.READ_EXTERNAL_STORAGE")
    }

    private fun granted(context: Context, permission: String): Boolean =
        runCatching {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private class Row(
        val path: String,
        val name: String,
        val size: Long,
        val modifiedAt: Long,
    )
}
