package io.github.proify.lyricon.xinghe.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

/** 统一走框架日志通道 */
internal class ModuleLogger(
    private val module: XposedModule,
    private val tag: String = "XingHe"
) {
    fun debug(message: String) = module.log(Log.DEBUG, tag, message)
    fun info(message: String) = module.log(Log.INFO, tag, message)
    fun warn(message: String) = module.log(Log.WARN, tag, message)
    fun error(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            module.log(Log.ERROR, tag, message)
        } else {
            module.log(Log.ERROR, tag, message, throwable)
        }
    }
}
