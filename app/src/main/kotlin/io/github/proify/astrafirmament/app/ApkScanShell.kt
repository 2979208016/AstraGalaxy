package io.github.proify.astrafirmament.app

import io.github.proify.astrafirmament.settings.ModulePrefs
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 扫描链路的 root shell 工具（App 进程）：
 *
 * - [exec]：跑一条 `su -c`，**超时/被杀时也把已经读到的输出交回**。这是相对
 *   [io.github.proify.astrafirmament.shared.SuReader] 的关键差别：SuReader 在超时或退出码
 *   非 0 时整段丢弃，只要有一个目录慢，一次 su 扫全部目录就整轮白扫。
 * - [buildFindScript]：全部来源目录合成**一次** su 调用；每目录 `##序号` + 状态行 +
 *   `find -printf` 行，单个目录出错只影响它自己。
 * - [sourceContentKey]：root 下读原文件首尾各 256KB 算内容指纹，算法与
 *   [ApkIncomingStore.contentKey] 完全一致 —— 复制前就能命中已有副本，不必先复制几百兆。
 * - [ApkInotifyWatcher]：`/system/bin/inotifyd` 目录监听（事件只当触发信号），
 *   watcher 死掉自动重装、连续失败退避降级；扫描器的轮询始终保留作兜底。
 */
internal object ApkScanShell {
    /** 目录分隔行前缀：安装包名不会以它开头，也不会是这种形式 */
    const val DIR_MARKER = "##"

    /** 每个目录的状态行：没有安装包 / 目录不存在 / 无权限 / find 本身失败 */
    const val STATE_OK = "@ok"
    const val STATE_MISSING = "@missing"
    const val STATE_DENIED = "@denied"
    const val STATE_FAIL = "@fail"

    /** 目录块结束标记：没看到它说明这条 find 被中途杀掉，该目录本轮算失败 */
    const val STATE_END = "@end"

    /** toybox find 的 printf 格式：mtime（小数秒）\t size \t 绝对路径 */
    private const val FIND_FORMAT = "'%T@\\t%s\\t%p\\n'"

    /** 单次输出上限：find 被意外放大时也不会把内存吃光（超出的部分直接丢） */
    private const val MAX_OUTPUT_CHARS = 512 * 1024

    /** 内容指纹分块大小，必须与 [ApkIncomingStore.contentKey] 对齐 */
    private const val CONTENT_CHUNK = 256L * 1024L

    /** kill 之后读线程的等待：管道关闭后读线程很快退出，这里只是兜底 */
    private const val READER_JOIN_MS = 400L

    /** 正常退出后等读线程收尾（大输出如 500KB 需要一点时间，不能提前截断） */
    private const val DRAIN_JOIN_MS = 2_000L

    /** kill 之后等进程真正退出的宽限 */
    private const val KILL_GRACE_MS = 2_000L

    /** 一次 su 命令的结果；[timedOut] 为 true 时 [text] 只是「已经读到的部分」 */
    class Output(val text: String, val timedOut: Boolean)

    /** 跑一条 `su -c`：输出边读边攒，超时只 kill 进程，已经读到的内容照样返回 */
    fun exec(command: String, timeoutMs: Long): Output {
        val process = runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        }.getOrNull() ?: return Output("", false)
        val out = Sink(MAX_OUTPUT_CHARS)
        // stderr 也要读走：不读的话管道写满会把 root 进程卡死
        val err = Sink(4096)
        val outReader = drain(process.inputStream, out, "xinghe-scan-out")
        val errReader = drain(process.errorStream, err, "xinghe-scan-err")
        val finished = runCatching {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) runCatching { process.destroy() }
        runCatching { process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS) }
        // 正常退出时读线程很快就到 EOF；被杀时只等一小会儿，避免拖慢整轮扫描
        val joinMs = if (finished) DRAIN_JOIN_MS else READER_JOIN_MS
        runCatching { outReader.join(joinMs) }
        runCatching { errReader.join(READER_JOIN_MS) }
        return Output(out.text(), !finished)
    }

    private class Sink(private val limit: Int) {
        private val builder = StringBuilder()

        @Synchronized
        fun append(chars: CharArray, length: Int) {
            if (builder.length >= limit) return
            val take = minOf(length, limit - builder.length)
            builder.append(String(chars, 0, take))
        }

        @Synchronized
        fun text(): String = builder.toString()
    }

    private fun drain(stream: InputStream, sink: Sink, name: String): Thread =
        Thread({
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        sink.append(buffer, read)
                    }
                }
            }
        }, name).apply {
            isDaemon = true
            start()
        }

    /**
     * 一次 su 扫全部目录的脚本。
     *
     * [since] 与目录一一对应：0 / 缺失 = 该目录全量扫（首扫、或上次没扫成功），
     * 否则只找 `-newermt` 之后落盘的文件 —— 息屏、进程不在的时段就在解锁/冷启动时补回来。
     * 每个目录都自带 `##序号 / 状态 / @end`，所以一个目录慢或报错不会毁掉整轮结果。
     */
    fun buildFindScript(dirs: List<String>, depth: Int, since: List<Long>): String = buildString {
        dirs.forEachIndexed { index, dir ->
            val sinceMs = since.getOrElse(index) { 0L }
            append("echo '").append(DIR_MARKER).append(index).append("'; ")
            append("if [ ! -d ").append(quote(dir)).append(" ]; then echo '")
                .append(STATE_MISSING).append("'; ")
            append("elif cd ").append(quote(dir)).append(" 2>/dev/null; then echo '")
                .append(STATE_OK).append("'; ")
            append("find ").append(quote(dir))
            if (depth != ModulePrefs.APK_SCAN_DEPTH_UNLIMITED) append(" -maxdepth ").append(depth)
            // 名字规则：*.apk* 覆盖 .apk / .apk.1 / .apk.1.1 / .apk! / 撞名的 "x.apk (25).1"，
            // .apks / .apkm 也被 *.apk* 覆盖；.xapk 不含 ".apk"，单独列一条
            append(" -type f \\( -iname '*.apk*' -o -iname '*.xapk' \\)")
            if (sinceMs > 0L) append(" -newermt '@").append(sinceMs / 1000L).append("'")
            append(" -printf ").append(FIND_FORMAT).append(" 2>/dev/null; ")
            // 退出码 >=2 才是 find 本身失败（1 只代表个别子目录读不到，不该整目录作废）
            append("rc=\$?; if [ \"\$rc\" -ge 2 ]; then echo '").append(STATE_FAIL).append("'; fi; ")
            append("echo '").append(STATE_END).append("'; ")
            append("else echo '").append(STATE_DENIED).append("'; fi; ")
        }
        append("true")
    }

    /**
     * 原文件内容指纹：`<size>:<md5(首 256KB || 尾 256KB)>`，与 [ApkIncomingStore.contentKey]
     * 同一算法（那边读副本、这边走 root 读原文件）。App uid 读不了 /storage 下的第三方目录，
     * 所以这一步必须 su；读不到的返回 null，调用方退回「先复制再算」的老路。
     */
    fun sourceContentKey(path: String, timeoutMs: Long): String? {
        val command = "p=" + quote(path) + "; " +
            "s=\$(stat -c %s \"\$p\" 2>/dev/null) || exit 0; " +
            "case \"\$s\" in ''|*[!0-9]*) exit 0;; esac; " +
            "echo \"\$s\"; " +
            "if [ \"\$s\" -le " + CONTENT_CHUNK + " ]; then head -c " + CONTENT_CHUNK + " \"\$p\" | md5sum; " +
            "else { head -c " + CONTENT_CHUNK + " \"\$p\"; tail -c " + CONTENT_CHUNK + " \"\$p\"; } | md5sum; fi"
        val lines = exec(command, timeoutMs).text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.size < 2) return null
        val size = lines[0].toLongOrNull() ?: return null
        if (size <= 0L) return null
        val hex = lines[1].substringBefore(' ').lowercase()
        if (hex.length != 32 || hex.any { it !in "0123456789abcdef" }) return null
        return size.toString() + ":" + hex
    }

    /**
     * 文件头校验：真安装包是 ZIP，头 4 字节 `PK\x03\x04`。伪后缀（下载器占位、错误页
     * 被存成 .apk）在这里就挡掉，不用先复制几百兆再让完整性校验拒绝。
     */
    fun hasApkHeader(path: String, timeoutMs: Long = 4_000L): Boolean =
        exec("head -c 4 " + quote(path) + " 2>/dev/null", timeoutMs)
            .text
            .startsWith("PK\u0003\u0004")

    /** 路径已在白名单里排除引号/换行，单引号包裹即可安全拼进 shell */
    fun quote(text: String): String = "'" + text + "'"
}

/**
 * inotifyd 目录监听（每个目录一个 root 进程）。
 *
 * 事件只当「这个目录有人在写」的触发信号：不解析文件名、也不依赖事件内容正确，
 * 只要 stdout 冒出任意一行就调 [onEvent]（扫描器 200ms 防抖后跑一轮增量扫描）。
 *
 * 自愈：supervisor 每 [SUPERVISE_MS] 检查一次，进程死了按次数退避重装；连续
 * [FAIL_LIMIT] 次失败就把该目录让给轮询兜底，[GIVE_UP_MS] 之后再试。目录增减由
 * [updateDirs] 交给 supervisor，调用线程永远不 fork su。
 *
 * 注意：inotifyd 的 stdout **只**能读到进程管道里，绝不能重定向到被监听目录内的文件
 * （写一行 → 触发 c 事件 → 无限自激，实测过）。
 */
internal class ApkInotifyWatcher(
    private val log: (String) -> Unit,
    private val onEvent: () -> Unit
) {
    private companion object {
        const val INOTIFYD = "/system/bin/inotifyd"

        /** toybox 0.8.12 实测掩码：目录内 n 新建 / c 写入 / m 移入 / y 移出 / d 删除 */
        const val MASK = "ncmyd"
        const val SUPERVISE_MS = 5_000L
        const val FAIL_LIMIT = 3
        const val GIVE_UP_MS = 10L * 60L * 1_000L
        const val RESTART_BACKOFF_MS = 5_000L

        /** watcher 上限：每个都是常驻 root 进程，别为十几个不存在的目录全铺开 */
        const val MAX_WATCHERS = 12

        /** 活过这段时间就认为它稳定，清零失败计数（否则历史失败会让它永久退避） */
        const val HEALTHY_MS = 60_000L
    }

    private class Watch(val dir: String, val process: Process, val startedAt: Long)

    private val lock = Any()
    private val watches = HashMap<String, Watch>()
    private val failures = HashMap<String, Int>()
    private val retryAt = HashMap<String, Long>()

    @Volatile private var dirs: List<String> = emptyList()

    @Volatile private var stopped = false

    @Volatile private var supervisor: Thread? = null

    @Volatile private var events = 0L

    fun start() {
        synchronized(this) {
            if (!stopped && supervisor != null) return
            stopped = false
        }
        supervisor = Thread({ supervise() }, "xinghe-apk-watch").apply {
            isDaemon = true
            start()
        }
        log("inotify 事件通道已启动（每目录一个 watcher，失败自动降级轮询）")
    }

    fun updateDirs(value: List<String>) {
        dirs = value.distinct().take(MAX_WATCHERS)
    }

    fun stop() {
        stopped = true
        synchronized(lock) {
            watches.values.forEach { runCatching { it.process.destroy() } }
            watches.clear()
        }
    }

    /** 当前真正在听的目录数：0 = 事件通道不可用（退化为轮询） */
    fun activeCount(): Int = synchronized(lock) { watches.size }

    fun eventCount(): Long = events

    private fun supervise() {
        while (!stopped) {
            runCatching { reconcile() }.onFailure { log("inotify 监督异常：${it.message}") }
            runCatching { Thread.sleep(SUPERVISE_MS) }
        }
    }

    private fun reconcile() {
        val now = System.currentTimeMillis()
        val wanted = dirs
        // 1) 摘掉已退出的进程，记一次失败
        val dead = synchronized(lock) {
            val gone = watches.values.filter { !it.process.isAlive }
            gone.forEach { watches.remove(it.dir) }
            gone
        }
        dead.forEach { onFailure(it.dir, now) }
        // 2) 稳定运行过的 watcher 清零失败计数
        synchronized(lock) {
            watches.values.forEach { if (now - it.startedAt > HEALTHY_MS) failures.remove(it.dir) }
        }
        // 3) 需要但没在跑的目录：过了退避期就重装
        for (dir in wanted) {
            if (synchronized(lock) { watches.containsKey(dir) }) continue
            if (now < synchronized(lock) { retryAt[dir] ?: 0L }) continue
            spawn(dir, now)
        }
        // 4) 配置里已删除的目录：停掉它
        synchronized(lock) { watches.keys.filter { it !in wanted } }.forEach { stopOne(it) }
    }

    private fun onFailure(dir: String, now: Long) {
        val count = synchronized(lock) {
            val next = (failures[dir] ?: 0) + 1
            failures[dir] = next
            val delay = if (next >= FAIL_LIMIT) GIVE_UP_MS else RESTART_BACKOFF_MS * next
            retryAt[dir] = now + delay
            next
        }
        if (count == FAIL_LIMIT) {
            log("inotify watcher 连续失败 $count 次，该目录改用轮询兜底（${GIVE_UP_MS / 60_000} 分钟后再试）")
        } else {
            log("inotify watcher 退出（第 $count 次），${RESTART_BACKOFF_MS * count / 1_000}s 后重装")
        }
    }

    private fun spawn(dir: String, now: Long) {
        // `exec` 让 su 进程直接被 inotifyd 替换：kill 掉 su 就等于收掉 watcher
        val command = "exec $INOTIFYD - " + ApkScanShell.quote(dir) + ":" + MASK
        val process = runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        }.getOrNull()
        if (process == null) {
            onFailure(dir, now)
            return
        }
        synchronized(lock) { watches[dir] = Watch(dir, process, now) }
        Thread({ readEvents(process) }, "xinghe-apk-watch-io").apply {
            isDaemon = true
            start()
        }
        Thread({ drainErrors(process.errorStream) }, "xinghe-apk-watch-err").apply {
            isDaemon = true
            start()
        }
    }

    private fun readEvents(process: Process) {
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    events++
                    onEvent()
                }
            }
        }
    }

    /** stderr 必须读走否则会写满管道卡死 root 进程；失败原因由 supervisor 的计数体现 */
    private fun drainErrors(stream: InputStream) {
        runCatching {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                while (reader.read(buffer) > 0) {
                    // 丢弃
                }
            }
        }
    }

    private fun stopOne(dir: String) {
        val watch = synchronized(lock) { watches.remove(dir) } ?: return
        runCatching { watch.process.destroy() }
    }
}
