package io.github.proify.lyricon.xinghe.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.File

/** 统一走框架日志通道，并尽力在宿主可写目录再落一份（便于事后排查） */
internal class ModuleLogger(
    private val module: XposedModule,
    private val tag: String = "AstraGalaxy"
) {
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    private var sinkCache: File? = null
    private var failedAttempts = 0

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
        val pkg = currentPackage()
        if (pkg != null) {
            list.add(File("/data/user/0/$pkg/files/$LOG_NAME"))
            list.add(File("/data/data/$pkg/files/$LOG_NAME"))
            list.add(File("/sdcard/Android/data/$pkg/files/$LOG_NAME"))
        }
        return list
    }

    private fun sink(): File? {
        sinkCache?.let { return it }
        if (failedAttempts > 20) return null
        for (file in candidates()) {
            val ok = runCatching {
                file.parentFile?.mkdirs()
                if (file.length() > 2_000_000L) file.delete()
                file.appendText("==== " + fmt.format(java.util.Date()) + " ====\n")
                true
            }.getOrDefault(false)
            if (ok) {
                sinkCache = file
                return file
            }
        }
        failedAttempts++
        return null
    }

    private fun appContext(): android.content.Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
    }.getOrNull()

    /** 当前进程包名（去掉 :remote 之类的后缀），不依赖 hidden API */
    private fun currentPackage(): String? {
        val raw = runCatching {
            File("/proc/self/cmdline").readText().trimEnd('\u0000').trim()
        }.getOrNull()
        val pkg = raw?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return null
        return pkg
    }

    private fun toFile(level: String, message: String) {
        val file = sink() ?: return
        runCatching {
            if (file.length() > 2_000_000L) file.delete()
            file.appendText(fmt.format(java.util.Date()) + " " + level + " " + message + "\n")
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
