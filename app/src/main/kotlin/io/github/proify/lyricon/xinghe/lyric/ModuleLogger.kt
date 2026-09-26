package io.github.proify.lyricon.xinghe.lyric

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.File

/** 统一走框架日志通道，并尽力在宿主可写目录再落一份（便于事后排查） */
class ModuleLogger(
    private val module: XposedModule,
    private val tag: String = "AstraGalaxy"
) {
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    private var sinkCache: File? = null
    private var sinkResolved = false

    /**
     * 候选落盘位置，按顺序尝试：
     *  1. Application#filesDir —— 最标准；但个别进程早期还拿不到 Context；
     *  2. /data/user/0/<pkg>/files —— 同 UID 自有目录，不需要 Context（模块代码就跑在宿主进程里）；
     *  3. /sdcard/Android/data/<pkg>/files —— 酷狗、网易云这类本来就写外部目录的播放器。
     *
     * MeloYou（com.music）不写外部目录，早期又拿不到 Context，只靠第 1、3 条会整场没有文件日志。
     */
    private fun candidates(): List<File> {
        val list = ArrayList<File>(4)
        runCatching { appContext()?.filesDir?.let { list.add(File(it, LOG_NAME)) } }
        val pkg = packageName
        if (pkg != null) {
            list.add(File("/data/user/0/$pkg/files/$LOG_NAME"))
            list.add(File("/data/data/$pkg/files/$LOG_NAME"))
            list.add(File("/sdcard/Android/data/$pkg/files/$LOG_NAME"))
        }
        return list
    }

    /**
     * 解析日志落盘位置。**只做一次**。
     *
     * ⚠️ 这里曾经在每次写日志时都跑一遍 `mkdirs()` + `appendText()`，
     * 是拖死 system_server 的第二个元凶：
     *  - `mkdirs()` 是系统调用，即使目录已存在也要走一次；
     *  - `appendText()` 每次都 open/write/close，纯同步 IO；
     *  - 开机期 system_server 里日志量极大，两者叠加直接把主线程拖住。
     *
     * 现在：路径只解析一次并缓存；`mkdirs()` 只在首次执行。
     */
    private fun sink(): File? {
        sinkCache?.let { return it }
        if (sinkResolved) return null
        var found: File? = null
        for (file in candidates()) {
            val ok = runCatching {
                // mkdirs 只在这里执行一次（本方法有 sinkResolved 短路）
                file.parentFile?.mkdirs()
                file.appendText("")
                true
            }.getOrDefault(false)
            if (ok) { found = file; break }
        }
        sinkCache = found
        // 成功或失败都只做一次，永不重试：system_server 里这条链路必然失败，
        // 重试等于每条日志白跑 4 次 mkdirs + appendText，会把开机拖死。
        sinkResolved = true
        return found
    }

    private fun appContext(): android.content.Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
    }.getOrNull()

    /**
     * 当前进程包名，**只解析一次**。
     *
     * 读 /proc/self/cmdline 是系统调用，不能每条日志都做 ——
     * system_server 开机期日志量极大，这是之前拖慢启动的一处细节。
     */
    private val packageName: String? by lazy { currentPackage() }

    /** 当前进程包名（去掉 :remote 之类的后缀），不依赖 hidden API */
    private fun currentPackage(): String? {
        val raw = runCatching {
            File("/proc/self/cmdline").readText().trimEnd('\u0000').trim()
        }.getOrNull()
        val pkg = raw?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return null
        return pkg
    }

    /**
     * 落盘。用缓冲区把内容攒起来，攒够再写，避免每次日志一次同步 IO。
     *
     * 开机期 system_server 的高频日志是压垮系统的直接原因之一，
     * 所以这里必须「攒着写」而不是「写一条开一次文件」。
     */
    private val buffer = StringBuilder(4096)
    private val bufferLock = Any()

    /** 攒到这么多字节就落一次盘 */
    private val FLUSH_THRESHOLD = 8_000

    private fun toFile(level: String, message: String) {
        val file = sink() ?: return
        val line = fmt.format(java.util.Date()) + " " + level + " " + message + "\n"
        val flushNow: Boolean
        synchronized(bufferLock) {
            buffer.append(line)
            flushNow = buffer.length >= FLUSH_THRESHOLD
        }
        if (!flushNow) return
        flush(file)
    }

    private fun flush(file: File) {
        val payload: String
        synchronized(bufferLock) {
            if (buffer.isEmpty()) return
            payload = buffer.toString()
            buffer.setLength(0)
        }
        runCatching {
            if (file.length() > 2_000_000L) file.delete()
            file.appendText(payload)
        }
    }

    fun debug(message: String) {
        module.log(Log.DEBUG, tag, message)
        toFile("D", message)
    }
    fun info(message: String) {
        module.log(Log.INFO, tag, message)
        toFile("I", message)
    }
    fun warn(message: String) {
        module.log(Log.WARN, tag, message)
        toFile("W", message)
    }
    fun error(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            module.log(Log.ERROR, tag, message)
        } else {
            module.log(Log.ERROR, tag, message, throwable)
        }
        toFile("E", message + (throwable?.let { " | " + it } ?: ""))
    }

    private companion object {
        const val LOG_NAME = "lyricon_debug.log"
    }
}
