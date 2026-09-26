package io.github.proify.lyricon.xinghe.lyric

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * hook 回调里逃出来的异常，落盘留证。
 *
 * 为什么不用 logcat：`logcat -b crash` 会被系统按策略裁剪，上一轮就是因为
 * 拿不到完整堆栈，只能靠推测定位 MeloYou 的闪退，绕了很大一圈。
 * 写文件则是确定能拿到的东西 —— 代价只是几次 IO。
 *
 * 而且：**模块拦截器里抛出的异常会被框架归属成应用的 AndroidRuntime 崩溃**，
 * 在 `logcat -b crash` 里看起来就像 MeloYou 自己崩了。所以这里要把
 * 进程名、hook 描述、完整堆栈一并记下，才能分清到底是谁的问题。
 *
 * 落盘位置按顺序尝试（第一个能写的就用）：
 *   1. /data/local/tmp/xinghe_hook_err.log —— 777，system_server 也能写；
 *   2. 宿主自己 files 目录下的 xinghe_hook_err.log —— 兜底。
 *
 * 只追加、有大小上限，不做任何删除以外的事。
 */
internal object HookCrashLog {

    private const val LOG_NAME = "xinghe_hook_err.log"
    private const val MAX_BYTES = 512_000L

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var sink: File? = null

    @Volatile
    private var sinkResolved = false

    private fun resolveSink(): File? {
        sink?.let { return it }
        if (sinkResolved) return null

        // 只试 /data/local/tmp（777，system_server 也能写）。
        // 不再试 /sdcard：那里在 system_server 里必然失败，
        // 多试一次就多一次系统调用；而且崩溃日志本来也只在这个目录取。
        val candidates = listOf(
            File("/data/local/tmp/$LOG_NAME"),
        )
        for (file in candidates) {
            val ok = runCatching {
                // mkdirs 只在首次解析时执行一次（本方法有 sinkResolved 短路）
                file.parentFile?.mkdirs()
                file.appendText("")
                true
            }.getOrDefault(false)
            if (ok) {
                sink = file
                sinkResolved = true
                return file
            }
        }
        sinkResolved = true
        return null
    }

    fun record(where: String, throwable: Throwable) {
        val file = resolveSink() ?: return
        runCatching {
            if (file.length() > MAX_BYTES) file.delete()
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            file.appendText(
                "==== " + fmt.format(Date()) + " ====\n" +
                    "process=" + processName() + " uid=" + android.os.Process.myUid() + "\n" +
                    "where=" + where + "\n" +
                    sw.toString() + "\n"
            )
        }
    }

    private fun processName(): String = runCatching {
        File("/proc/self/cmdline").readText().trimEnd('\u0000').trim()
    }.getOrDefault("?")
}
