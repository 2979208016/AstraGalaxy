package io.github.proify.astrafirmament.capability.link

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.proify.astrafirmament.shared.Redact
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 开发者日志体系（内存环形缓冲 + zip 报告，废除独立日常日志文件）。
 *
 * 记录：模块进程的全链路行为——不只"发生了什么"，还有"怎么执行的、
 * 执行了什么、结果如何"（调用点、参数、分支走向、耗时、退避、终态）。
 *
 * 三个文件按内容分档（用户要求：一份压缩包 = 全部证据）：
 *  - 01_device_info.txt   机型/系统/模块版本/连接状态头（本机环境全量快照）；
 *  - 02_module_log.txt    模块进程运行日志（本内存缓冲，原文记录）；
 *  - 03_lsp_log.txt       LSPosed 框架日志（su 只读，**整份原文**收录）。
 *
 * 记录策略（审核整改 7.7 + 2026-11-02 复核）：
 *  - 【开关】默认关闭，且关闭时**一条都不写**（审核实锤项：旧版 module.log 无条件
 *    写框架日志、应用内开关关不掉）。只有用户主动开启后才采集。
 *  - 【脱敏】审核明确要求「日志去掉完整路径与用户标识」，所以 02/03 仍过
 *    [Redact.line]：完整路径、完整网址、WiFi 名称、蓝牙名与 MAC、歌名歌手一律
 *    抹成占位符。
 *  - 【详细】为了「打码了也能查」，日志本身写得更结构化：每个环节都记阶段名、
 *    类型、后缀、大小、耗时、拒绝原因、结果码等**非身份信息**（见 SmartApkIntake
 *    / LinkHook 的 stage= 打点），而不是只写一句"失败了"。
 *
 * 内存环形缓冲（3000 条）：不占日常存储、不逐条 IO；生成报告时一次性
 * 落盘。全程 runCatching：日志永远不能反过来伤害功能。
 */
internal object DevLog {
    private const val ZIP_NAME = "xinghe_report.zip"

    /** 先生成临时文件再原子替换：分享/读取永远看到完整的 zip */
    private const val ZIP_TMP_NAME = ZIP_NAME + ".tmp"

    private const val MAX_ENTRIES = 3000

    private val buffer = ArrayDeque<String>(MAX_ENTRIES)
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile var enabled = false

    // 打包/清理周期与上次执行时间（毫秒）。默认 5 分钟打包、7 天清理，
    // 具体值由 ModulePrefs 里开发者选项的两个拉条控制。
    @Volatile private var packIntervalMs = 5L * 60_000L
    @Volatile private var cleanIntervalMs = 168L * 3_600_000L
    @Volatile private var lastPackAt = 0L
    @Volatile private var lastCleanAt = 0L

    /** 在链路入口刷新开关与周期（每次投递只读一次偏好；报告一律走后台节流线程） */
    fun refresh(ctx: Context) {
        appCtx = ctx.applicationContext
        val prefs = io.github.proify.astrafirmament.settings.ModulePrefs.of(ctx)
        enabled = runCatching {
            io.github.proify.astrafirmament.settings.ModulePrefs.isDevLogEnabled(prefs)
        }.getOrDefault(false)
        readIntervals(prefs)
        if (enabled) scheduleFlush()
    }

    /** 开发者选项改动周期后立即生效（不用等下一次投递） */
    fun updateIntervals(ctx: Context) {
        appCtx = ctx.applicationContext
        readIntervals(io.github.proify.astrafirmament.settings.ModulePrefs.of(ctx))
        if (enabled) scheduleFlush()
    }

    private fun readIntervals(prefs: SharedPreferences) {
        packIntervalMs = io.github.proify.astrafirmament.settings.ModulePrefs.isLogPackMinutes(prefs)
            .toLong().coerceIn(1, 720) * 60_000L
        cleanIntervalMs = io.github.proify.astrafirmament.settings.ModulePrefs.isLogCleanHours(prefs)
            .toLong().coerceIn(1, 720) * 3_600_000L
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, t: Throwable? = null) =
        write("E", message + (t?.let { " | " + it.javaClass.simpleName + ": " + it.message } ?: ""))

    /**
     * 审核要求：报告不得出现完整路径与用户标识。规则集中在 [Redact]，
     * hook 侧与 app 侧同一份；抹成占位符后仍保留「类型/后缀/计数」等诊断信息。
     */
    internal fun redact(raw: String): String =
        runCatching { Redact.line(raw) }.getOrDefault("<redact-failed>")

    private fun write(level: String, line: String) {
        if (!enabled) return
        val safe = redact(line)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(
                fmt.format(Date()) + " " + level + " [" +
                    Thread.currentThread().name + "] " + safe
            )
        }
        // 数据目录里【始终存在最新 zip】（用户要求：日志默认以压缩包形式
        // 存在数据目录，不是点分享才生成）。节流 5s 刷新，写入走后台线程。
        scheduleFlush()
    }

    @Volatile private var flushPending = false

    /** clear() 时自增，让在途的 5s 刷新线程作废（否则清完又被写回来） */
    @Volatile private var bufferEpoch = 0L

    @Volatile private var appCtx: Context? = null

    /**
     * 后台节流线程：睡到「打包 / 清理」中先到期的那个时间点再执行，
     * 循环直到开关关闭或手动清除。压缩包因此常驻数据目录，不用进开发者
     * 选项才现打；超打包周期才重建（原子替换旧包），超清理周期则清空重来。
     */
    private fun scheduleFlush() {
        val ctx = appCtx ?: return
        if (flushPending) return
        flushPending = true
        val epoch = bufferEpoch
        Thread({
            try {
                while (enabled && epoch == bufferEpoch) {
                    val now = System.currentTimeMillis()
                    val packDue = lastPackAt + packIntervalMs
                    val cleanDue = lastCleanAt + cleanIntervalMs
                    val wait = minOf(packDue, cleanDue) - now
                    if (wait > 0) {
                        runCatching { Thread.sleep(wait) }
                        continue
                    }
                    if (now >= cleanDue) {
                        cleanDue(ctx)
                        continue
                    }
                    if (now >= packDue) {
                        runCatching { buildReport(ctx) }
                        lastPackAt = System.currentTimeMillis()
                    }
                }
            } finally {
                flushPending = false
            }
        }, "xinghe-log-flush").apply { isDaemon = true }.start()
    }

    /** 到期清理：清空内存缓冲并删除压缩包（含临时文件），时间戳重置。 */
    private fun cleanDue(ctx: Context) {
        synchronized(lock) {
            buffer.clear()
            bufferEpoch++
        }
        for (name in listOf(ZIP_NAME, ZIP_TMP_NAME)) {
            runCatching { java.io.File(ctx.filesDir, name).delete() }
        }
        lastCleanAt = System.currentTimeMillis()
        lastPackAt = 0L
    }

    // ---------------- 报告生成 ----------------

    /** 01：运行环境全量快照（排查兼容性问题时这些字段缺一不可，一律如实写）。 */
    private fun deviceInfoText(ctx: Context): String = buildString {
        appendLine("========== 星穹运行环境 ==========")
        appendLine("生成时间        : " + fmtFull.format(Date()))
        appendLine("厂商/品牌       : ${Build.MANUFACTURER} / ${Build.BRAND}")
        appendLine("机型/设备       : ${Build.MODEL} / ${Build.DEVICE}")
        appendLine("产品/主板       : ${Build.PRODUCT} / ${Build.BOARD}")
        appendLine("系统构建        : ${Build.DISPLAY} / ${Build.ID}")
        appendLine("系统指纹        : 已省略（审核要求去掉用户标识）")
        appendLine("Android 版本    : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, 补丁 ${Build.VERSION.SECURITY_PATCH})")
        appendLine("增量版本        : ${Build.VERSION.INCREMENTAL}")
        appendLine("ABI             : ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("SELinux         : " + selinuxStatus())
        appendLine("开机时长        : " + uptimeText())
        appendLine("内存            : " + memoryText(ctx))
        appendLine("模块版本        : " + moduleVersion(ctx))
        appendLine("模块包名        : " + ctx.packageName)
        appendLine("进程            : " + currentProcess())
        appendLine("Root 授权状态   : " + rootStatus())
        appendLine("LSPosed         : " + lsposedStatus())
        appendLine("日志开关        : " + if (enabled) "已开启" else "已关闭（不采集）")
        appendLine("日志脱敏        : 路径/网址/SSID/蓝牙名+MAC/歌名歌手 → 占位符；类型/后缀/大小/耗时照常记录")
        appendLine("星河岛连接      : ${LinkHub.stateName()}")
        appendLine("缓冲条目数      : ${entryCount()}")
        appendLine("=================================")
    }

    private fun selinuxStatus(): String = runCatching {
        java.io.File("/sys/fs/selinux/enforce").readText().trim().let {
            if (it == "1") "enforcing（强制）" else "permissive（宽容）"
        }
    }.getOrDefault("未知")

    private fun uptimeText(): String = runCatching {
        val ms = android.os.SystemClock.elapsedRealtime()
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        "${h}h${m}m"
    }.getOrDefault("未知")

    private fun memoryText(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        "可用 ${info.availMem / (1024 * 1024)}MB / 阈值 ${info.threshold / (1024 * 1024)}MB / 低内存=${info.lowMemory}"
    }.getOrDefault("未知")

    private fun lsposedStatus(): String = runCatching {
        val r = io.github.proify.astrafirmament.shared.LspLoadProbe.probe()
        when {
            !r.loaded -> "未找到加载证据（模块可能未在 LSPosed 中启用）"
            r.version == null -> "已加载（仅入口行，未记录版本）；日志=${r.fromLog}"
            else -> "已加载 v${r.version}；日志=${r.fromLog}"
        }
    }.getOrDefault("未知")

    private fun moduleVersion(ctx: Context): String = runCatching {
        val pm = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "v${pm.versionName} (${pm.versionCode})"
    }.getOrDefault("未知")

    private fun currentProcess(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000').trim()
    }.getOrDefault("未知")

    private fun rootStatus(): String =
        if (io.github.proify.astrafirmament.shared.SuReader.available()) "已授权" else "未授权/不可用"

    /**
     * 03：LSPosed 框架日志。su 只读（应用 uid 即便授权 root 也读不了 /data/adb，
     * 必须走 su）。**只收录本模块行 + 脱敏**：modules_*.log 是全局日志，含其它
     * 模块与系统进程输出，整包带走既超出「我的日志」范围也是审核点；归属判定用
     * 既有标识（模块 TAG / LinkHook 前缀 / 历史包名），查不到归属的行丢弃。
     */
    private fun lspLogText(): String = runCatching {
        val listing = suRead("ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1")
            ?: return@runCatching "（LSPosed 日志不可读：需在授权管理里给本应用 root 权限）\n"
        val latest = listing.trim().lines().firstOrNull()?.trim().orEmpty()
        if (latest.isBlank()) return@runCatching "（未找到 LSPosed 日志文件）\n"
        val content = suRead("cat '$latest'") ?: return@runCatching "（LSPosed 日志读取失败）\n"
        buildString {
            appendLine("========== LSPosed 框架日志 ==========")
            appendLine("文件            : $latest")
            val own = content.lineSequence().filter { isOwnModuleLine(it) }.joinToString("\n")
            appendLine("本模块行数      : " + own.count { it == '\n' })
            appendLine("======================================")
            if (own.isBlank()) {
                appendLine("（本次框架日志中没有本模块的记录）")
            } else {
                appendLine(redact(own))
            }
        }
    }.getOrElse { "（LSPosed 日志读取失败：${it.message ?: "未知错误"}）\n" }

    /**
     * LSPosed 的 modules_*.log 是**全局**日志：其它模块的输出、系统进程日志都在里面。
     * 报告只允许带本模块的行，否则分享出去的就不是"我的日志"而是整机模块日志。
     *
     * 判定看行内是否出现本模块的既有标识（大小写不敏感）：
     *  - `AstraFirmament`：ModuleLogger 默认 tag / LinkHook.TAG / 模块名；
     *  - `link-hook`：LinkHook 的 BUILD_TAG 前缀；
     *  - `io.github.proify.astrafirmament` / `io.github.proify.lyricon.xinghe`：包名（含旧包名）。
     */
    private fun isOwnModuleLine(line: String): Boolean {
        if (line.isBlank()) return false
        val lower = line.lowercase(Locale.US)
        if (lower.contains("astrafirmament")) return true
        if (lower.contains("link-hook")) return true
        return lower.contains("io.github.proify.lyricon.xinghe")
    }

    /**
     * su 只读执行一条命令。返回 stdout 全文。
     * ★ 死锁教训（实锤）：绝不能先 waitFor 再读流——输出超过管道 64KB
     * 缓冲时 su 写阻塞等读、waitFor 永不返回（LSP 日志 169KB > 64KB，
     * 这正是"给了 root 仍读取失败"的真根因）。必须【并发读流】再等退出。
     */
    private fun suRead(cmd: String): String? =
        io.github.proify.astrafirmament.shared.SuReader.read(cmd, timeoutMs = 8_000L)

    /** 内存缓冲快照（原文，不做任何加工） */
    private fun snapshot(): String = synchronized(lock) {
        if (buffer.isEmpty()) "（暂无模块进程日志——请先开启日志开关并触发功能）\n"
        else buffer.joinToString("\n") + "\n"
    }

    /**
     * 生成完整报告 zip（应用数据目录）：
     *  01 机型信息 / 02 模块运行日志（内存缓冲）/ 03 LSPosed 框架日志（原文）。
     *
     * 先写 [ZIP_TMP_NAME] 再原子替换为 [ZIP_NAME]：分享端可能同时在读，
     * 不能让它拿到半个 zip。
     */
    fun buildReport(ctx: Context): java.io.File? = runCatching {
        val zip = java.io.File(ctx.filesDir, ZIP_NAME)
        val tmp = java.io.File(ctx.filesDir, ZIP_TMP_NAME)
        if (tmp.isFile && !tmp.delete()) return@runCatching null
        ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
            fun entry(name: String, text: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            entry("01_device_info.txt", deviceInfoText(ctx))
            entry("02_module_log.txt", snapshot())
            entry("03_lsp_log.txt", lspLogText())
        }
        if (!tmp.renameTo(zip)) {
            // 个别 ROM 上跨挂载点 rename 会失败：回退到复制再删临时文件
            if (zip.isFile) zip.delete()
            tmp.copyTo(zip, overwrite = true)
            tmp.delete()
        }
        zip
    }.getOrNull()

    /** 当前缓冲条目数（状态卡显示） */
    fun entryCount(): Int = synchronized(lock) { buffer.size }

    /** 报告 zip 是否已生成 */
    fun reportExists(ctx: Context): Boolean =
        java.io.File(ctx.filesDir, ZIP_NAME).isFile

    /** 当前日志包文件（不存在返回 null）：进开发者选项只读状态，不现场打包 */
    fun currentReport(ctx: Context): java.io.File? =
        java.io.File(ctx.filesDir, ZIP_NAME).takeIf { it.isFile }

    /**
     * 一键清除：清内存缓冲与报告（含未完成的临时文件）。
     * 同时作废在途的 5s 刷新，否则清完几秒后 zip 又出现。
     */
    fun clear(ctx: Context): Boolean = runCatching {
        synchronized(lock) {
            buffer.clear()
            bufferEpoch++
        }
        flushPending = false
        lastPackAt = 0L
        lastCleanAt = System.currentTimeMillis()
        var ok = true
        for (name in listOf(ZIP_NAME, ZIP_TMP_NAME)) {
            val f = java.io.File(ctx.filesDir, name)
            if (f.exists() && !f.delete()) ok = false
        }
        ok
    }.getOrDefault(false)
}
