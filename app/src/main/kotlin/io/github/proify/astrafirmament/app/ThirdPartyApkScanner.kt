package io.github.proify.astrafirmament.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import io.github.proify.astrafirmament.capability.link.DevLog
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 第三方目录里的新 APK 扫描器。
 *
 * 通道设计（毫秒级推送 + 不漏检）：
 * 1. 事件通道：[ApkInotifyWatcher] 给每个来源目录挂一个 `inotifyd`，有写入事件就
 *    [EVENT_DEBOUNCE_MS] 防抖后立刻扫一轮 —— 文件落盘的瞬间开始处理。
 * 2. 轮询兜底：每 [POLL_MS] 一轮，全部目录合并成**一次** `su find`（[ApkScanShell]），
 *    事件通道不可用（root 写、跨 uid 投递失败）时靠它兜住。
 * 3. 基线差集：路径 → mtime+size 持久化在 [ApkScanBaseline]；新文件 = 不在基线或
 *    mtime/size 变了。首扫只登记存量不出卡。
 * 4. 补扫：目录记「上次完整扫到的时间」，`find -newermt` 只找那之后的文件；解锁
 *    （ACTION_USER_PRESENT）、开机/冷启动、安装卸载广播都会触发一轮，把息屏漏掉的补回来。
 *
 * 性能：一个目录慢或报错只影响它自己（每目录 `##序号 / 状态 / @end` 标记，超时也保留
 * 已经读到的部分），不再整轮丢弃。
 */
internal object ThirdPartyApkScanner {
    // ---- 节奏 ----

    /** 轮询兜底间隔：事件通道正常时它也很快，异常时必须够快才不会漏（原 25s） */
    private const val POLL_MS = 2_500L
    private const val FIRST_DELAY_MS = 1_000L

    /** 一轮 su 扫描的总超时：超时只 kill 进程，已经输出的目录照常处理 */
    private const val SCAN_TIMEOUT_MS = 10_000L

    /** 目录游标往回退一点：扫描过程中刚落盘的文件不会被 `-newermt` 漏掉 */
    private const val SCAN_OVERLAP_MS = 5_000L

    /** 文件事件后的防抖：一次下载会连着产生很多事件，只要一轮 */
    private const val EVENT_DEBOUNCE_MS = 200L

    /** 两轮事件扫描的最小间隔，防止持续写入的目录把 CPU 占满 */
    private const val EVENT_MIN_GAP_MS = 400L

    // ---- 判定 ----

    /** 未来时间戳容忍窗口：超过就认为不可信（改为记基线 + 日志），原 10min */
    private const val FUTURE_SKEW_MS = 2L * 60L * 1_000L

    /** mtime 已经安静这么久就认为写完，不必再等第二轮读数 */
    private const val SETTLE_QUIET_MS = 200L

    /** 没扫过的时长超过它就算「补扫」，一次少出几张卡，免得解锁瞬间弹一屏 */
    private const val CATCHUP_GAP_MS = 10L * 60L * 1_000L
    private const val MAX_CATCHUP_CARDS = 3
    private const val MAX_CARDS_PER_TICK = 4

    /** 失败条目重试：最多 [MAX_ATTEMPTS] 次、间隔 [RETRY_GAP_MS]，避免每 2.5s 重复拷 */
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_GAP_MS = 20_000L

    private const val ROOT_CHECK_TIMEOUT_MS = 3_000L
    private const val ROOT_RECHECK_MS = 5L * 60L * 1_000L
    private const val HEADER_TIMEOUT_MS = 4_000L

    /** 不存在的目录退避重试间隔：每轮都去 cd 一次没有意义（用户建好目录后最迟这么久被发现） */
    private const val MISSING_RETRY_MS = 5L * 60L * 1_000L

    /** ApkScanShell 的输出上限：达到它就说明后面可能被截断了 */
    private const val SCAN_TEXT_LIMIT = 512 * 1024

    private const val THREAD_NAME = "xinghe-apk-scan"
    private const val ATTEMPT_KEEP = 64
    private const val SIZE_KEEP = 256

    // ---- 目录 ----

    private const val STORAGE_ROOT = "/storage/emulated/0"

    /**
     * 补充的默认来源目录：只在内置默认值上叠加，用户手动改过目录列表就不插手。
     * Telegram 的下载默认落在这两处（下载器也可能写到 Download，已在默认列表里）。
     */
    private val EXTRA_DEFAULT_DIRS = listOf(
        "$STORAGE_ROOT/Telegram",
        "$STORAGE_ROOT/Android/media/org.telegram.messenger/Telegram",
    )

    /** 没设置过层级时的默认值（原 ModulePrefs 默认 3，深一层能覆盖 WeChat/Android/data 结构） */
    private const val DEFAULT_SCAN_DEPTH = 5

    /** 压缩容器后缀：不是可直接安装的 APK，单独识别（见 [containerOf]） */
    private val CONTAINER_EXTS = listOf(".xapk", ".apks", ".apkm")

    // ---- 运行状态 ----

    private val lock = Any()
    private val pool: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    @Volatile private var started = false
    @Volatile private var config: ScanConfig? = null
    @Volatile private var watcher: ApkInotifyWatcher? = null
    @Volatile private var rootOk = false
    @Volatile private var rootCheckedAt = 0L
    @Volatile private var eventPending = false
    @Volatile private var lastEventScanAt = 0L

    /** 上一轮各目录状态：只在变化时打日志，避免 2.5s 刷屏 */
    private val lastDirStates = HashMap<String, DirState>()

    /** 文件路径 → 上一次读到的 size / 首次读到的时间，用于「两次同大小」判定 */
    private val seenSizes = HashMap<String, LongArray>()

    /** 路径 → 尝试次数与最后一次时间（仅在扫描线程访问） */
    private val attempts = HashMap<String, LongArray>()

    /** 目录 → 上次确认不存在的时间：退避 [MISSING_RETRY_MS] 后才重新尝试 */
    private val missingAt = HashMap<String, Long>()

    // ---- 数据 ----

    private class ScanConfig(val dirs: List<String>, val depth: Int)

    private enum class DirState { OK, MISSING, DENIED, FAIL }

    private class Entry(
        val path: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        /** `.xapk` / `.apks` / `.apkm` 时是容器类型，普通 APK 为 null */
        val container: String?,
    )

    private class DirReport(val dir: String, val state: DirState, val entries: List<Entry>)

    // =====================================================================
    // 生命周期
    // =====================================================================

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
        }
        DevLog.refresh(app)
        config = readConfig(app)
        ApkScanBaseline.load(app)
        // 启动即做一次后台清理：apk-incoming 的容量/数量/TTL 不该等到第一次投递才收紧
        runCatching { ApkIncomingStore.pruneAsync(app) }
        runCatching { registerReceivers(app) }
            .onFailure { DevLog.w("APK 扫描：广播注册失败 ${it.message}") }
        pool.scheduleWithFixedDelay({ safeTick(app) }, FIRST_DELAY_MS, POLL_MS, TimeUnit.MILLISECONDS)
        runCatching {
            val w = ApkInotifyWatcher(
                log = { message -> DevLog.i("APK 扫描：$message") },
                onEvent = { onFsEvent(app) },
            )
            w.updateDirs(currentConfig(app).dirs)
            w.start()
            watcher = w
        }.onFailure { DevLog.w("APK 扫描：事件通道启动失败 ${it.message}（改用轮询）") }
        DevLog.i(
            "APK 扫描已启动：轮询 ${POLL_MS}ms，目录 ${currentConfig(app).dirs.size} 个，" +
                "深度 ${currentConfig(app).depth}"
        )
    }

    /** 设置页改完目录/层级后调用：重读配置、重建 watcher、立刻补扫一轮 */
    fun reload(context: Context) {
        val app = context.applicationContext
        val next = readConfig(app)
        config = next
        runCatching { watcher?.updateDirs(next.dirs) }
        DevLog.i("APK 扫描配置已刷新：目录 ${next.dirs.size} 个，深度 ${next.depth}")
        scheduleEventScan(app)
    }

    private fun registerReceivers(context: Context) {
        val unlockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, unlockFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(receiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, unlockFilter)
            context.registerReceiver(receiver, packageFilter)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val app = context?.applicationContext ?: return
            when (intent?.action) {
                // 解锁 / 亮屏：息屏期间不扫，这会儿用「上次扫到的时间」把漏的补回来
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    DevLog.i("APK 扫描：解锁/亮屏，触发补扫")
                    runCatching { ApkMediaPoller.retryStart(app) }
                    scheduleEventScan(app)
                }
                // 安装/卸载：包目录里的 apk 会被搬走或清掉，顺带补扫一轮
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED -> scheduleEventScan(app)
            }
        }
    }

    private fun onFsEvent(app: Context) {
        if (eventPending) return
        eventPending = true
        runCatching {
            pool.schedule({
                eventPending = false
                val now = System.currentTimeMillis()
                if (now - lastEventScanAt >= EVENT_MIN_GAP_MS) {
                    lastEventScanAt = now
                    safeTick(app)
                }
            }, EVENT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }.onFailure { eventPending = false }
    }

    private fun scheduleEventScan(app: Context) {
        if (eventPending) return
        eventPending = true
        runCatching {
            pool.schedule({
                eventPending = false
                lastEventScanAt = System.currentTimeMillis()
                safeTick(app)
            }, EVENT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }.onFailure { eventPending = false }
    }

    // =====================================================================
    // 配置
    // =====================================================================

    private fun currentConfig(context: Context): ScanConfig =
        config ?: readConfig(context).also { config = it }

    private fun readConfig(context: Context): ScanConfig {
        val prefs = ModulePrefs.ofFresh(context)
        val custom = runCatching { prefs.getStringSet(ModulePrefs.KEY_APK_SOURCE_DIRS, null) }
            .getOrNull()
        val dirs = if (custom == null) {
            // 没设置过：内置默认 + 本扫描器补充的目录（用户改过就完全以用户为准）
            (ModulePrefs.DEFAULT_APK_SOURCE_DIRS + EXTRA_DEFAULT_DIRS)
                .mapNotNull { ModulePrefs.normalizeApkSourceDir(it) }
                .distinct()
                .sorted()
        } else {
            ModulePrefs.apkSourceDirs(prefs)
        }
        val depth = if (prefs.contains(ModulePrefs.KEY_APK_SCAN_DEPTH)) {
            ModulePrefs.apkScanDepth(prefs)
        } else {
            DEFAULT_SCAN_DEPTH
        }
        return ScanConfig(dirs, depth)
    }

    // =====================================================================
    // 对外判定（SmartApkIntake 用）
    // =====================================================================

    /** 第三方路径白名单：只认配置过的来源目录、层级内、名字像安装包的文件 */
    fun isThirdPartyPath(path: String): Boolean {
        if (!path.startsWith("/storage/")) return false
        if (path.contains("..")) return false
        if (path.any { it == '\'' || it == '"' || it == '\n' || it == '\r' || it == '\u0000' }) return false
        val name = path.substringAfterLast('/')
        if (!isPackageName(name)) return false
        val cfg = config ?: return false
        val dir = cfg.dirs.firstOrNull { path.startsWith(it + "/") } ?: return false
        return withinDepth(path, dir, cfg.depth)
    }

    /** 名字看起来像安装包（含 .apk.1 / .apk! 变体与 xapk/apks/apkm 容器） */
    fun isPackageName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith(".")) return false
        if (name.any { it == '\'' || it == '"' || it == '\n' || it == '\r' || it == '\u0000' }) return false
        val lower = name.lowercase()
        // 下载器把 .!ut / .crdownload 去掉后留下的真名，如 "app.apk!"
        if (lower.endsWith(".apk!")) return true
        if (ApkBridge.looksLikeApk(name)) return true
        return CONTAINER_EXTS.any { lower.endsWith(it) }
    }

    private fun withinDepth(path: String, dir: String, depth: Int): Boolean {
        if (depth == ModulePrefs.APK_SCAN_DEPTH_UNLIMITED) return true
        val relative = path.removePrefix(dir).removePrefix("/")
        if (relative.isEmpty()) return false
        return relative.count { it == '/' } + 1 <= depth
    }

    // =====================================================================
    // 扫描
    // =====================================================================

    private fun safeTick(context: Context) {
        runCatching { tick(context) }.onFailure { error ->
            DevLog.w("APK 扫描异常：${error.message}")
            // su 管道/解析出问题时下一轮先重查 root，别在坏状态里空转
            rootOk = false
        }
    }

    private fun tick(context: Context) {
        if (!SmartApkIntake.enabled(context)) return
        DevLog.refresh(context)
        // 息屏不扫（省电）：解锁/亮屏广播会立刻补一轮，扫的是「上次完整扫到的时间」之后的文件
        if (!interactive(context)) return
        if (!rootReady()) return

        val cfg = currentConfig(context)
        if (cfg.dirs.isEmpty()) return
        ApkScanBaseline.load(context)

        val startedAt = System.currentTimeMillis()
        // 最近确认不存在的目录先不参与本轮（用户后来建好目录，最迟 MISSING_RETRY_MS 后重新纳入）
        val scanDirs = cfg.dirs.filter { dir ->
            val since = missingAt[dir]
            since == null || startedAt - since >= MISSING_RETRY_MS
        }
        if (scanDirs.isEmpty()) return
        val sinceList = scanDirs.map { ApkScanBaseline.dirScanAt(it) }
        val catchUp = sinceList.any { it > 0L && startedAt - it > CATCHUP_GAP_MS }
        val reports = scanOnce(ScanConfig(scanDirs, cfg.depth), sinceList)

        // 所有目录都是 FAIL（不是 missing/denied，那是目录本身的问题）= su 通道坏了，下一轮重查 root
        if (reports.isNotEmpty() && reports.values.all { it.state == DirState.FAIL }) {
            rootOk = false
            DevLog.w("APK 扫描：本轮所有目录都失败，按 root 不可用处理")
            writeStatus(context, startedAt, 0, 0, reports.size, 0, 0, 0, catchUp)
            return
        }

        var okDirs = 0
        var deniedDirs = 0
        var failDirs = 0
        var missingDirs = 0
        var hits = 0
        var cards = 0
        var baselined = 0
        var writing = 0
        var budget = if (catchUp) MAX_CATCHUP_CARDS else MAX_CARDS_PER_TICK
        val nextSince = HashMap<String, Long>()

        for (index in cfg.dirs.indices) {
            val dir = cfg.dirs[index]
            val report = reports[dir]
            if (report == null) {
                // 本轮因「上次不存在」被跳过
                missingDirs++
                continue
            }
            if (report.state == DirState.MISSING) {
                missingDirs++
                missingAt[dir] = startedAt
                continue
            }
            missingAt.remove(dir)
            if (report.state == DirState.DENIED) {
                deniedDirs++
                continue
            }
            if (report.state == DirState.FAIL) {
                failDirs++
                continue
            }
            okDirs++
            // 首扫：存量只登记基线，不出卡（避免一装应用就弹一堆历史安装包）
            if (!ApkScanBaseline.isKnownDir(dir)) {
                ApkScanBaseline.markDir(dir)
                var registered = 0
                for (entry in report.entries) {
                    ApkScanBaseline.remember(entry.path, entry.size, entry.mtime)
                    registered++
                }
                baselined += registered
                nextSince[dir] = startedAt - SCAN_OVERLAP_MS
                DevLog.i("APK 扫描：登记基线 $registered 个文件（目录 #$index）")
                continue
            }

            var oldestUnresolved = 0L
            val pending = ArrayList<Entry>()
            for (entry in report.entries) {
                if (ApkScanBaseline.isKnown(entry.path, entry.size, entry.mtime)) continue
                if (entry.mtime - startedAt > FUTURE_SKEW_MS) {
                    // 未来时间戳（时区/时钟错）：记基线，避免每轮反复处理
                    ApkScanBaseline.remember(entry.path, entry.size, entry.mtime)
                    baselined++
                    DevLog.w("APK 扫描：跳过未来时间戳文件（目录 #$index）")
                    continue
                }
                if (!sizeSettled(entry, startedAt)) {
                    writing++
                    continue
                }
                val container = entry.container
                if (container != null) {
                    // 容器不是能直接安装的 APK，下游完整性校验必拒；这里只校验 ZIP 头并留痕
                    if (ApkScanShell.hasApkHeader(entry.path, HEADER_TIMEOUT_MS)) {
                        DevLog.i("APK 扫描：发现安装包容器 .$container（目录 #$index），已记录不投递")
                    }
                    ApkScanBaseline.remember(entry.path, entry.size, entry.mtime)
                    continue
                }
                if (ApkIncomingStore.alreadyDelivered(context, entry.path, entry.size, entry.mtime)) {
                    ApkScanBaseline.remember(entry.path, entry.size, entry.mtime)
                    continue
                }
                pending.add(entry)
            }
            // 新的优先：解锁补扫时先把刚下载的那几个推出去
            pending.sortByDescending { it.mtime }
            for (entry in pending) {
                hits++
                if (budget <= 0) {
                    oldestUnresolved = older(oldestUnresolved, entry.mtime)
                    continue
                }
                if (attemptsExhausted(entry.path)) {
                    DevLog.w("APK 扫描：重试 $MAX_ATTEMPTS 次仍失败，记基线放弃（目录 #$index）")
                    ApkScanBaseline.remember(entry.path, entry.size, entry.mtime)
                    continue
                }
                if (!attemptAllowed(entry.path, startedAt)) {
                    oldestUnresolved = older(oldestUnresolved, entry.mtime)
                    continue
                }
                when (handle(context, entry, cfg)) {
                    SmartApkIntake.Outcome.DONE -> {
                        budget--
                        cards++
                        ApkScanBaseline.remember(entry.path, entry.size, entry.mtime)
                    }
                    SmartApkIntake.Outcome.SKIPPED ->
                        ApkScanBaseline.remember(entry.path, entry.size, entry.mtime)
                    // 失败/待重试：不记基线，下轮（间隔 RETRY_GAP_MS）再试
                    SmartApkIntake.Outcome.RETRY,
                    SmartApkIntake.Outcome.FAILED ->
                        oldestUnresolved = older(oldestUnresolved, entry.mtime)
                }
            }
            var advanced = startedAt - SCAN_OVERLAP_MS
            if (oldestUnresolved > 0L) {
                advanced = minOf(advanced, oldestUnresolved - 1_000L)
            }
            nextSince[dir] = advanced
        }

        for ((dir, at) in nextSince) ApkScanBaseline.markDirScan(dir, at)
        ApkScanBaseline.flush(context)
        logDirs(cfg.dirs, reports)
        writeStatus(context, startedAt, okDirs, deniedDirs, failDirs, missingDirs, hits, cards, catchUp)
        if (hits > 0 || catchUp) {
            DevLog.i(
                "APK 扫描：新增 $hits，出卡 $cards，写入中 $writing，基线 $baselined，" +
                    "目录 ok=$okDirs denied=$deniedDirs fail=$failDirs missing=$missingDirs"
            )
        }
    }

    /** 一次 su 扫全部目录：每目录 `##序号` + 状态行 + `find -printf` 行 + `@end` */
    private fun scanOnce(cfg: ScanConfig, since: List<Long>): Map<String, DirReport> {
        val script = ApkScanShell.buildFindScript(cfg.dirs, cfg.depth, since)
        val output = ApkScanShell.exec(script, SCAN_TIMEOUT_MS)
        var lines = output.text.split('\n')
        // 超时/输出截断时最后一行可能是半截路径：宁可不处理也不能出错
        if ((output.timedOut || output.text.length >= SCAN_TEXT_LIMIT) &&
            lines.isNotEmpty() && !output.text.endsWith("\n")
        ) {
            lines = lines.dropLast(1)
        }

        val reports = HashMap<String, DirReport>()
        var index = -1
        var state: DirState? = null
        var entries = ArrayList<Entry>()
        var endSeen = false

        // 没看到 @end 的块 = su/find 被超时杀在半路、输出被截断：这个目录本轮视为没扫全，
        // 游标不推进（下一轮从同一时间点重扫），否则它后面那些文件会被 -newermt 永久漏掉
        fun finish(complete: Boolean) {
            val dir = cfg.dirs.getOrNull(index) ?: return
            val resolved = if (!complete) DirState.FAIL else (state ?: DirState.FAIL)
            reports[dir] = DirReport(dir, resolved, if (complete) entries else emptyList())
            state = null
            entries = ArrayList()
            endSeen = false
        }

        for (raw in lines) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith(ApkScanShell.DIR_MARKER) -> {
                    finish(endSeen)
                    index = line.removePrefix(ApkScanShell.DIR_MARKER).toIntOrNull() ?: -1
                }
                line == ApkScanShell.STATE_OK -> state = DirState.OK
                line == ApkScanShell.STATE_MISSING -> state = DirState.MISSING
                line == ApkScanShell.STATE_DENIED -> state = DirState.DENIED
                line == ApkScanShell.STATE_FAIL -> state = DirState.FAIL
                line == ApkScanShell.STATE_END -> {
                    endSeen = true
                    finish(true)
                }
                else -> {
                    if (state == DirState.OK && index >= 0) {
                        parseEntry(line, cfg.dirs[index], cfg.depth)?.let { entries.add(it) }
                    }
                }
            }
        }
        finish(endSeen)
        // 没拿到 @end 的目录 = su/find 被中途杀掉：按失败算，不推进游标
        for (dir in cfg.dirs) {
            if (!reports.containsKey(dir)) reports[dir] = DirReport(dir, DirState.FAIL, emptyList())
        }
        return reports
    }

    private fun parseEntry(line: String, dir: String, depth: Int): Entry? {
        val first = line.indexOf('\t')
        if (first <= 0) return null
        val second = line.indexOf('\t', first + 1)
        if (second <= 0) return null
        val mtime = (line.substring(0, first).toDoubleOrNull()?.times(1000.0))?.toLong() ?: return null
        val size = line.substring(first + 1, second).toLongOrNull() ?: return null
        val path = line.substring(second + 1)
        if (!path.startsWith(dir + "/")) return null
        val name = path.substringAfterLast('/')
        if (!isPackageName(name)) return null
        if (!withinDepth(path, dir, depth)) return null
        return Entry(path, name, size, mtime, containerOf(name))
    }

    private fun containerOf(name: String): String? {
        val lower = name.lowercase()
        val ext = CONTAINER_EXTS.firstOrNull { lower.endsWith(it) } ?: return null
        return ext.removePrefix(".")
    }

    /**
     * 大小是否已经稳定：mtime 安静了 [SETTLE_QUIET_MS] 就直接算写完（老文件零延迟推送），
     * 否则要求连续两轮读到同样大小（下载中的文件会被判成「写入中」）。
     */
    private fun sizeSettled(entry: Entry, now: Long): Boolean {
        if (now - entry.mtime >= SETTLE_QUIET_MS) return true
        val previous = seenSizes[entry.path]
        if (previous == null || previous[0] != entry.size) {
            seenSizes[entry.path] = longArrayOf(entry.size, now)
            if (seenSizes.size > SIZE_KEEP) {
                val cutoff = now - 60_000L
                seenSizes.entries.removeAll { it.value[1] < cutoff }
            }
            return false
        }
        return true
    }

    private fun attemptAllowed(path: String, now: Long): Boolean {
        val record = attempts[path]
        if (record == null) {
            attempts[path] = longArrayOf(1L, now)
            if (attempts.size > ATTEMPT_KEEP) {
                attempts.keys.take(attempts.size - ATTEMPT_KEEP).forEach { attempts.remove(it) }
            }
            return true
        }
        val count = record[0].toInt()
        if (count >= MAX_ATTEMPTS) return false
        if (now - record[1] < RETRY_GAP_MS) return false
        attempts[path] = longArrayOf((count + 1).toLong(), now)
        return true
    }

    private fun attemptsExhausted(path: String): Boolean =
        (attempts[path]?.get(0) ?: 0L) >= MAX_ATTEMPTS

    /** 取更早的时间戳（0 = 还没有） */
    private fun older(current: Long, candidate: Long): Long =
        if (current <= 0L) candidate else minOf(current, candidate)

    private fun handle(context: Context, entry: Entry, cfg: ScanConfig): SmartApkIntake.Outcome {
        if (ApkIncomingStore.alreadyDelivered(context, entry.path, entry.size, entry.mtime)) {
            return SmartApkIntake.Outcome.SKIPPED
        }
        // 伪后缀（下载器占位、错误页存成 .apk）：4 字节头就够判断，不必先复制
        if (!ApkScanShell.hasApkHeader(entry.path, HEADER_TIMEOUT_MS)) {
            DevLog.w("APK 扫描：文件头不是 ZIP，跳过（目录 #${cfg.dirs.indexOfFirst { entry.path.startsWith(it + "/") }}）")
            return SmartApkIntake.Outcome.SKIPPED
        }
        // sizeVerified=true：大小是 find 刚读到的，且已过稳定判定，不用再轮询等待
        val request = ApkBridge.ApkRequest(
            entry.path,
            entry.name,
            entry.size,
            entry.mtime,
            0L,
            sourceOf(cfg.dirs, entry.path),
            "",
        )
        return SmartApkIntake.runFromThirdParty(context, request, sizeVerified = true)
    }

    /** 只用于 DevLog：不打印完整路径，只给来源目录名 */
    private fun sourceOf(dirs: List<String>, path: String): String {
        val dir = dirs.firstOrNull { path.startsWith(it + "/") } ?: return "scan"
        return dir.substringAfterLast('/').ifEmpty { "scan" }.lowercase()
    }

    // =====================================================================
    // root / 屏幕状态
    // =====================================================================

    private fun interactive(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.isInteractive ?: true
    }.getOrDefault(true)

    private fun rootReady(): Boolean {
        if (rootOk) return true
        val now = System.currentTimeMillis()
        if (now - rootCheckedAt < ROOT_RECHECK_MS) return false
        rootCheckedAt = now
        val output = ApkScanShell.exec("id -u", ROOT_CHECK_TIMEOUT_MS)
        rootOk = output.text.trim() == "0"
        if (!rootOk) DevLog.w("APK 扫描：root 不可用，等待重试")
        return rootOk
    }

    // =====================================================================
    // 状态 / 日志
    // =====================================================================

    private fun logDirs(dirs: List<String>, reports: Map<String, DirReport>) {
        for (index in dirs.indices) {
            val dir = dirs[index]
            val state = reports[dir]?.state ?: continue
            if (lastDirStates[dir] == state) continue
            lastDirStates[dir] = state
            when (state) {
                DirState.DENIED -> DevLog.w("APK 扫描：目录 #$index 无访问权限（已跳过，可在设置页检查来源目录）")
                DirState.FAIL -> DevLog.w("APK 扫描：目录 #$index 扫描失败（find/读取异常）")
                DirState.MISSING -> DevLog.i("APK 扫描：目录 #$index 不存在（已跳过）")
                DirState.OK -> DevLog.i("APK 扫描：目录 #$index 恢复正常")
            }
        }
    }

    private fun writeStatus(
        context: Context,
        at: Long,
        ok: Int,
        denied: Int,
        fail: Int,
        missing: Int,
        hits: Int,
        cards: Int,
        catchUp: Boolean,
    ) {
        runCatching {
            ApkScanBaseline.writeStatus(context, at, ok, denied, fail, missing, hits, cards, catchUp)
        }
    }
}

/**
 * 扫描基线（SharedPreferences 持久化）：
 *
 * - `d:<dir>`：该目录上次**完整**扫到的时间，下一轮用它做 `find -newermt` 的起点。
 *   息屏、进程被杀、失败目录都不推进它，所以下次一定会补扫回来。
 * - `f:<path>`：文件基线 `mtime|size`，只有它变了才算「新文件」。
 * - `s:*`：最近一轮扫描结果（设置页可见状态用，只写不读）。
 *
 * 只在扫描线程访问，内部再加锁是为了 flush 期间读其它线程时安全。
 */
private object ApkScanBaseline {
    private const val PREFS = "xinghe_apk_baseline"
    private const val KEY_DIR = "d:"
    private const val KEY_FILE = "f:"
    private const val KEY_STATUS = "s:"
    private const val SEP = "|"

    /** 基线条目上限：超出就淘汰最早写入的（文件早被删了，留着没用） */
    private const val ENTRY_LIMIT = 512

    private val lock = Any()
    private var loaded = false
    private var prefs: SharedPreferences? = null

    private val dirDone = HashMap<String, Long>()
    private val fileStamps = LinkedHashMap<String, String>()
    private val pendingDirs = HashMap<String, Long>()
    private val pendingFiles = HashMap<String, String>()

    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            val store = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = store
            for ((key, value) in store.all) {
                if (key.startsWith(KEY_DIR) && value is Long) {
                    dirDone[key.removePrefix(KEY_DIR)] = value
                } else if (key.startsWith(KEY_FILE) && value is String) {
                    fileStamps[key.removePrefix(KEY_FILE)] = normalizeStamp(value)
                }
            }
            loaded = true
        }
    }

    /**
     * 老版本存的是 `size|mtime|recordedAt`，新版是 `mtime|size`；不迁移的话升级后
     * 存量文件会被当成新文件再弹一轮卡。这里把三段的旧格式折算成新格式。
     */
    private fun normalizeStamp(value: String): String {
        val parts = value.split(SEP)
        if (parts.size == 2) return value
        if (parts.size >= 3) {
            val size = parts[0]
            val mtime = parts[1]
            if (size.toLongOrNull() != null && mtime.toLongOrNull() != null) return mtime + SEP + size
        }
        return value
    }

    /** 目录上次完整扫到的时间；0 = 从没扫过（下一轮全量） */
    fun dirScanAt(dir: String): Long = synchronized(lock) { dirDone[dir] ?: 0L }

    fun isKnownDir(dir: String): Boolean = synchronized(lock) { dirDone.containsKey(dir) }

    /** 首扫时先登记目录（值为 0），这样 [markDirScan] 不会把它当成异常回退 */
    fun markDir(dir: String) {
        synchronized(lock) {
            if (dirDone.containsKey(dir)) return
            dirDone[dir] = 0L
            pendingDirs[dir] = 0L
        }
    }

    /**
     * 写入目录游标。**允许回退**：游标只是 `-newermt` 的起点，回退最多多扫几秒
     * （已在基线里的文件直接跳过），但「只前进」会在同一秒边界上把还没处理完的文件
     * 永久挡在 `-newermt` 之外 —— 那正是要修的漏检。
     */
    fun markDirScan(dir: String, at: Long) {
        synchronized(lock) {
            if (at <= 0L) return
            if (dirDone[dir] == at) return
            dirDone[dir] = at
            pendingDirs[dir] = at
        }
    }

    fun isKnown(path: String, size: Long, mtime: Long): Boolean =
        synchronized(lock) { fileStamps[path] == stampOf(size, mtime) }

    fun remember(path: String, size: Long, mtime: Long) {
        synchronized(lock) {
            val value = stampOf(size, mtime)
            if (fileStamps[path] == value) return
            fileStamps[path] = value
            pendingFiles[path] = value
        }
    }

    /** 批量落盘；没变化就直接返回，扫安静的目录时零写入 */
    fun flush(context: Context) {
        synchronized(lock) {
            if (pendingDirs.isEmpty() && pendingFiles.isEmpty()) return
            val store = prefs ?: context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .also { prefs = it }
            val editor = store.edit()
            for ((key, value) in pendingDirs) editor.putLong(KEY_DIR + key, value)
            for ((key, value) in pendingFiles) {
                // 空串是「删除」标记（trimLocked 淘汰的条目）
                if (value.isEmpty()) editor.remove(KEY_FILE + key) else editor.putString(KEY_FILE + key, value)
            }
            pendingDirs.clear()
            pendingFiles.clear()
            // trimLocked 新标出的删除标记留到下一轮 flush —— 本轮 editor 已经构建好了
            trimLocked()
            runCatching { editor.apply() }
        }
    }

    fun writeStatus(
        context: Context,
        at: Long,
        ok: Int,
        denied: Int,
        fail: Int,
        missing: Int,
        hits: Int,
        cards: Int,
        catchUp: Boolean,
    ) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_STATUS + "last_at", at)
                .putInt(KEY_STATUS + "last_ok", ok)
                .putInt(KEY_STATUS + "last_denied", denied)
                .putInt(KEY_STATUS + "last_fail", fail)
                .putInt(KEY_STATUS + "last_missing", missing)
                .putInt(KEY_STATUS + "last_hits", hits)
                .putInt(KEY_STATUS + "last_cards", cards)
                .putBoolean(KEY_STATUS + "last_catchup", catchUp)
                .apply()
        }
    }

    private fun stampOf(size: Long, mtime: Long): String = "$mtime$SEP$size"

    /** 调用方已持锁 */
    private fun trimLocked() {
        val iterator = fileStamps.entries.iterator()
        while (fileStamps.size > ENTRY_LIMIT && iterator.hasNext()) {
            val eldest = iterator.next().key
            iterator.remove()
            pendingFiles[eldest] = ""
        }
    }
}
