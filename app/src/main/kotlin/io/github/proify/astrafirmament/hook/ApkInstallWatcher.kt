package io.github.proify.astrafirmament.hook

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import io.github.proify.astrafirmament.app.ApkBridge
import java.util.Locale

/**
 * 智能文件候选：三条触发路径（安装意图 / DownloadManager / MediaStore）统一成这一个结构。
 *
 * 为什么要带这些「提示」字段：真实大小与 mtime 只能从文件本身取，而 system 侧打不开
 * 文件——所以库里给的值只做便宜的前置过滤，权威校验在 App 进程（app/SmartApkIntake）里做。
 *
 * @param expectedSize >0 时要求复制后的副本大小必须与之相等（DownloadManager 的 total 字节数）
 * @param freshForMs 候选必须在「最近这么久」内被写入过；<=0 表示不设新鲜度要求
 *        （用户主动点开安装包时，文件显然已经就绪）
 * @param modifiedAtHint 库里给的 mtime（毫秒），App 进程 su stat 之前的唯一时间依据
 * @param pathHint 真实路径（`_data` / local_uri）：App 进程 su cp 的唯一依据，null 即作废
 * @param source 日志用来源标记
 */
internal data class ApkCandidate(
    val name: String,
    val uri: Uri,
    val expectedSize: Long,
    val freshForMs: Long,
    val modifiedAtHint: Long,
    val pathHint: String?,
    val source: String
)

/**
 * APK 候选检测器：**只负责发现**，把带真实路径的候选回调给 [onApk]。
 *
 * 为什么这里不再 open/校验：system_server(uid 1000) 实测读不到 /storage/emulated/0，
 * MediaProvider 侧 open 一律被拒（旧实现在这里 openFileDescriptor，卡因此永远不弹）。
 * 读文件、校验完整性、提供 uri 全部交给有 root 的 App 进程（app/SmartApkIntake）。
 */
internal class ApkInstallWatcher(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit,
    private val onApk: (ApkCandidate) -> Unit
) {
    private val lock = Any()

    /**
     * 去重表：路径 -> 上次投递时的「指纹」（大小|mtime）+ 时刻，只在内存里，进程重启即清空。
     *
     * 关键：**指纹变了就必须重新投递**。浏览器是「先建文件、再流式追加」，第一次命中时
     * 文件只有半截；若此时按路径锁死十分钟，后面真正的「写完」事件全被挡在门外——
     * 5.8 线上「下载完成后不弹岛」就是这么来的。
     */
    private val seen = HashMap<String, Seen>()

    private class Seen(val fingerprint: String, val at: Long)

    /** 已排队等待 settle 的媒体行 id：同一次下载的连续进度回调合并成一次查库 */
    private val pendingMedia = HashSet<Long>()

    @Volatile private var observer: ContentObserver? = null
    @Volatile private var mediaHandler: Handler? = null
    @Volatile private var mediaRegistered = false
    @Volatile private var scanStarted = false

    /**
     * 注册 MediaStore 兜底监听（兜住「下载完成后厂商 ROM 不发任何下载广播」的情况）。
     * 注册成功后延迟补扫一次，捞「监听注册前就已经写完」的那一批。
     */
    fun startMediaObserver() {
        if (mediaRegistered) return
        synchronized(this) {
            if (mediaRegistered) return
            runCatching {
                val thread = HandlerThread("xinghe-apk-media").apply { start() }
                val handler = Handler(thread.looper)
                mediaHandler = handler
                val obs = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        runCatching { scheduleMediaCheck(uri) }
                    }
                }
                val root = MediaStore.Files.getContentUri(MEDIA_VOLUME_EXTERNAL)
                context.contentResolver.registerContentObserver(root, true, obs)
                observer = obs
                mediaRegistered = true
                log("智能文件兜底监听已注册：$root")
                // 只在注册时补扫一次「刚刚写完」的包（开机场景）；
                // 周期性补扫会误把「移动/重命名」过的旧包当新下载，5.10 起已停用。
                handler.postDelayed({ runCatching { scanRecent() } }, SCAN_DELAY_MS)
            }.onFailure { log("智能文件兜底监听注册失败：${it.message}") }
        }
    }

    /**
     * 候选入口：认名 -> 必须有真实路径（App 进程要靠它 su cp）-> 新鲜度 -> 去重。
     * 去重表在投递前就写：媒体库一轮变更会重复命中同一行，没有闸门就是复制风暴。
     */
    fun offer(c: ApkCandidate) {
        if (!enabled()) return
        if (!acceptName(c.name)) {
            log("智能文件 stage=accept 拒绝：名字不是正式安装包 ${c.name}")
            return
        }
        if (c.pathHint.isNullOrBlank()) {
            log("智能文件 stage=path 拒绝：缺少真实路径(${c.source}) ${c.name}")
            return
        }
        if (!freshEnough(c)) {
            log("智能文件 stage=fresh 拒绝(${c.source})：${c.name}")
            return
        }
        synchronized(lock) {
            if (isDuplicate(c)) {
                log("智能文件 stage=dedup 跳过(${c.source})：${c.name}")
                return
            }
            markSeen(c)
        }
        log("智能文件候选命中(${c.source})：${c.name} path=${c.pathHint}")
        runCatching { onApk(c) }
    }

    /**
     * 新鲜度：只能按库里给的 mtime 提示判断（system 打不开文件，拿不到 fstat）。
     * 权威时间由 App 进程 su stat 再判一次，这里只负责便宜地滤掉历史下载（幽灵卡）。
     */
    private fun freshEnough(c: ApkCandidate): Boolean {
        if (c.freshForMs <= 0L) return true
        val stamp = c.modifiedAtHint
        if (stamp <= 0L) return false
        val age = System.currentTimeMillis() - stamp
        // 允许少量「未来时间」（用户改过系统时钟后落盘的文件），超过容忍上限当异常丢弃
        return age <= c.freshForMs && age >= -FUTURE_SKEW_MS
    }

    /** 同一路径 + 同一指纹才算重复：文件又长了（新大小/新 mtime）就当作新候选再投一次 */
    private fun isDuplicate(c: ApkCandidate): Boolean = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        seen.entries.removeAll { now - it.value.at >= DEDUP_MS }
        val prev = seen[keyOf(c)] ?: return@synchronized false
        now - prev.at < DEDUP_MS && prev.fingerprint == fingerprintOf(c)
    }

    private fun markSeen(c: ApkCandidate) = synchronized(lock) {
        if (seen.size > SEEN_MAX) seen.clear()
        seen[keyOf(c)] = Seen(fingerprintOf(c), SystemClock.elapsedRealtime())
    }

    /** 去重主键用真实路径（App 进程 su cp 的唯一依据），没有路径时退回媒体库 uri */
    private fun keyOf(c: ApkCandidate): String =
        c.pathHint?.lowercase(Locale.US) ?: c.uri.toString()

    private fun fingerprintOf(c: ApkCandidate): String = c.expectedSize.toString() + "|" + c.modifiedAtHint

    /**
     * 认安装包名：`.apk` 与 QQ/TIM 的 `.apk.1`、`x.apk (25).1` 都算（后缀判定统一
     * 走 [ApkBridge.looksLikeApk]，与 App 侧的 intake 白名单同一套规则）。
     * 临时/占位文件（.part/.crdownload/点开头）在传输途中必然存在，一律不认。
     */
    private fun acceptName(name: String): Boolean {
        if (!ApkBridge.looksLikeApk(name)) return false
        val lower = name.lowercase(Locale.US)
        if (lower.startsWith(".")) return false
        return TRANSIENT_MARKS.none { lower.contains(it) }
    }

    private fun scheduleMediaCheck(uri: Uri?) {
        if (uri == null) return
        val id = uri.lastPathSegment?.toLongOrNull() ?: return
        val handler = mediaHandler ?: return
        synchronized(lock) { if (!pendingMedia.add(id)) return }
        // settle：MediaProvider 落行与文件写完不同步；同 id 的进度回调合并成一次查库
        handler.postDelayed({
            try {
                checkMediaUri(uri)
            } catch (t: Throwable) {
                log("智能文件媒体校验异常：${t.message}")
            } finally {
                synchronized(lock) { pendingMedia.remove(id) }
            }
        }, MEDIA_SETTLE_MS)
    }

    /**
     * 单条媒体库记录入口：只查它自己的名字/时间/路径，`_data` 是后面 App 进程
     * su cp 的唯一依据，查不到就整个候选作废（媒体库 SIZE 不可信，这里不采）。
     */
    private fun checkMediaUri(uri: Uri) {
        if (!enabled()) return
        val row = queryMediaRow(uri) ?: run {
            log("智能文件 media 记录不可用（已删除或无权读取）：$uri")
            return
        }
        if (!ApkBridge.looksLikeApk(row.name)) {
            log("智能文件 media 跳过 stage=后缀：${row.name}")
            return
        }
        offer(ApkCandidate(row.name, uri, 0L, LIVE_FRESH_MS, row.modifiedAt, row.path, "media"))
    }

    private fun queryMediaRow(uri: Uri): MediaRow? = runCatching {
        context.contentResolver.query(uri, MEDIA_COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (nameIdx < 0) return@use null
            MediaRow(
                name = if (c.isNull(nameIdx)) "" else c.getString(nameIdx),
                modifiedAt = if (dateIdx < 0 || c.isNull(dateIdx)) 0L else c.getLong(dateIdx) * 1_000L,
                path = if (dataIdx < 0 || c.isNull(dataIdx)) null else c.getString(dataIdx)
            )
        }
    }.getOrNull()

    private class MediaRow(val name: String, val modifiedAt: Long, val path: String?)

    /**
     * 进程启动后的补扫：只扫最近 [SCAN_FRESH_MS] 内写完的 APK。
     * 窗口必须窄——开机 / 进程重启时补扫历史文件，就是「幽灵卡」的第二个来源。
     */
    private fun scanRecent() {
        if (scanStarted) return
        scanStarted = true
        offerRecent(SCAN_FRESH_MS, SCAN_LIMIT)
    }

    /**
     * 周期性兜底：个别下载器写完包既不进媒体库、也不发下载完成广播，只有一个「新建文件」
     * 时的媒体库事件。这里每 [SWEEP_MS] 扫一次最近十分钟的 APK，把漏掉的那些补上；
     * 重复性由「同一路径 + 同一指纹」去重与 App 侧「已出过卡」指纹表共同挡住。
     */
    private fun sweepRecent() {
        // 5.10 起停用：补扫只看得到 mtime，而「移动/重命名」也会刷新 mtime，
        // 于是几分钟前就下好的包会被当成新下载再弹一次（用户实测 09:52 的包 09:55 又弹）。
        // 现在的兜底改由「下载事件 + 有界重投」负责，宁可不弹也不误弹。
        if (!enabled()) return
    }

    /** 补扫实现：只扫窗口内写完的 APK，条数有上限，避免整库遍历 */
    private fun offerRecent(windowMs: Long, limit: Int) {
        if (!enabled()) return
        val since = (System.currentTimeMillis() - windowMs) / 1_000L
        val root = MediaStore.Files.getContentUri(MEDIA_VOLUME_EXTERNAL)
        val selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND " +
            MediaStore.MediaColumns.DATE_MODIFIED + ">=?"
        val order = MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
        runCatching {
            context.contentResolver.query(
                // 两端都要 %：`%apk` 只匹配以 apk 结尾的名字，`x.apk.1` 这条 QQ 形态会被漏掉；
                // 真正的后缀判定交给下面那次 looksLikeApk
                root, SCAN_COLUMNS, selection, arrayOf("%$APK_MATCH%", since.toString()), order
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idIdx < 0 || nameIdx < 0) return@use
                var taken = 0
                while (taken < limit && c.moveToNext()) {
                    val name = if (c.isNull(nameIdx)) "" else c.getString(nameIdx)
                    if (!ApkBridge.looksLikeApk(name)) continue
                    val id = c.getLong(idIdx)
                    if (id <= 0L) continue
                    taken++
                    offer(
                        ApkCandidate(
                            name = name,
                            uri = ContentUris.withAppendedId(root, id),
                            expectedSize = 0L,
                            freshForMs = SCAN_FRESH_MS,
                            modifiedAtHint = if (dateIdx < 0 || c.isNull(dateIdx)) 0L
                            else c.getLong(dateIdx) * 1_000L,
                            pathHint = if (dataIdx < 0 || c.isNull(dataIdx)) null else c.getString(dataIdx),
                            source = "scan"
                        )
                    )
                }
            }
        }.onFailure { log("智能文件补扫失败：${it.message}") }
    }

    private companion object {
        /** MediaStore 卷名用字面量：MediaStore.VOLUME_EXTERNAL 是 API 29 才有的常量 */
        const val MEDIA_VOLUME_EXTERNAL = "external"
        const val APK_SUFFIX = ".apk"

        /** LIKE 用的粗筛：`%apk%` 同时命中 `.apk`、`.apk.1`，精确判定交给 looksLikeApk */
        const val APK_MATCH = "apk"

        /**
         * 同一路径 + 同一指纹的抑制窗口：只挡「同一秒内的连发」。
         * 跨窗口的重复投递交给 App 侧「已出过卡」指纹表兜底。
         */
        const val DEDUP_MS = 60 * 1_000L

        /** 媒体库变更的 settle 延迟：只等 MediaProvider 落行，写盘是否完成交给 intake 校验 */
        const val MEDIA_SETTLE_MS = 400L

        /** 补扫延迟：避开开机早期与监听刚注册时的抖动 */
        const val SCAN_DELAY_MS = 2_000L

        /** 周期性兜底扫描的间隔与窗口/条数 */
        const val SWEEP_MS = 180_000L
        const val SWEEP_FRESH_MS = 10 * 60 * 1_000L
        const val SWEEP_LIMIT = 5

        /**
         * 实时媒体库事件的新鲜度上限：文件 mtime 比这更早就不投。
         * 给到 30 分钟是因为「大文件下载」从建文件到真正写完可能拖很久，中间只有极少数
         * 事件；历史文件（真正会变幽灵卡的）mtime 都是老的，这个窗口仍然挡得住。
         */
        const val LIVE_FRESH_MS = 30 * 60 * 1_000L

        /** 开机补扫的窗口：必须窄，否则补扫历史文件就是幽灵卡的来源 */
        const val SCAN_FRESH_MS = 90 * 1_000L

        const val SCAN_LIMIT = 2

        /** 去重表上限：正常使用远达不到，超了整体清空，防止长期运行无界增长 */
        const val SEEN_MAX = 96

        /** 文件时间比现在「晚」超过这么多视为时钟异常，不弹 */
        const val FUTURE_SKEW_MS = 10 * 60 * 1_000L

        /** 传输途中的文件名标记：浏览器/下载器都先用这些后缀落盘 */
        val TRANSIENT_MARKS = arrayOf(".part", ".tmp", ".download", ".crdownload", ".partial")

        val MEDIA_COLUMNS = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )

        val SCAN_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )
    }
}
