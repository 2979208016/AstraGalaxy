package io.github.proify.astrafirmament.shared


/**
 * su 只读工具（shared 基建，纯 JVM 可测）。
 *
 * ★ 死锁教训（实锤）：绝不能先 waitFor 再读流——输出超过管道 64KB 缓冲时
 * su 写阻塞等读、waitFor 永不返回。必须【并发读流】再等退出。
 * 超时看门狗兜底；exitCode 非 0 / 超时 / 非 root 一律返回 null。
 */
object SuReader {

    /** su -c 执行并返回 stdout 全文（并发读流防死锁） */
    fun read(cmd: String, timeoutMs: Long = 8_000L): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { ls ->
                    for (l in ls) out.append(l).append('\n')
                }
            }
        }.apply { isDaemon = true }
        reader.start()
        val watcher = Thread {
            runCatching { Thread.sleep(timeoutMs); p.destroy() }
        }.apply { isDaemon = true }
        watcher.start()
        p.waitFor()
        reader.join(1_000L)
        if (p.exitValue() != 0) null else out.toString()
    }.getOrNull()

    /** su -c 判定授权是否可用（探针命令） */
    fun available(timeoutMs: Long = 1_500L): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "true"))
        val drain = Thread {
            runCatching { p.inputStream.readBytes(); p.errorStream.readBytes() }
        }.apply { isDaemon = true }
        drain.start()
        val watcher = Thread {
            runCatching { Thread.sleep(timeoutMs); p.destroy() }
        }.apply { isDaemon = true }
        watcher.start()
        p.waitFor() == 0
    }.getOrDefault(false)
}
