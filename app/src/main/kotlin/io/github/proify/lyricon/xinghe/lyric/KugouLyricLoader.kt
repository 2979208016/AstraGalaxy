package io.github.proify.lyricon.xinghe.lyric

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 酷狗系「歌词加载函数」直挂（词幕同款思路）。
 *
 * 酷狗把歌词文件加载交给 `com.kugou.framework.lyric.LyricManager` 里的
 * `load*(String path, boolean)`：词幕用 DexKit 按「方法体里出现字符串
 * `file is not krc or lyc or txt file`」定位它；这里不用 DexKit（要额外带
 * 一个 so），改为「等 LyricManager 类加载出来后直接反射全类方法，按签名
 * (String, Boolean/void) + 参数名兜底 + 全局限流试错」挂载。
 *
 * 拿到路径后按扩展名用项目内已有解析器解 krc / lrc（含 .lyc），
 * 解析成功才认账：这是「酷狗自己告诉我们要读哪份歌词」，证据强于按
 * mtime+时长猜文件，专门修「酷狗概念版扫错缓存 / 扫不到」的问题。
 *
 * 只作用于 com.kugou.android / com.kugou.android.lite 的 support 进程；
 * 类找不到、签名对不上时静默退化为原有目录扫描。
 */
internal class KugouLyricLoader(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val onLyric: (LocalLyric, String) -> Unit
) {

    private val hooked = AtomicBoolean(false)

    /** 试错开关：签名可疑的方法 hook 后给一次自证机会，喂进来的路径解不出歌词就永久作废 */
    private val candidateBudget = AtomicInteger(8)
    private val triedMethods = ConcurrentHashMap.newKeySet<String>()

    fun install() {
        tryHookDirect()
        if (hooked.get()) return
        hookClassLoadFallback()
    }

    /** 类已经在（support 进程往往如此）就直接挂 */
    private fun tryHookDirect() {
        val clazz = runCatching {
            Class.forName(LYRIC_MANAGER, false, classLoader)
        }.getOrNull() ?: return
        hookMethods(clazz)
    }

    /**
     * 类还没加载：挂 ClassLoader.loadClass 的返回，等系统/应用首次用到
     * LyricManager 时补挂。只观察返回值，不改行为。
     */
    private fun hookClassLoadFallback() {
        val loadClass = runCatching {
            ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java)
        }.getOrNull() ?: return
        runCatching {
            module.hook(loadClass)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!hooked.get()) {
                        val name = chain.args.getOrNull(0) as? String
                        if (name == LYRIC_MANAGER) {
                            (result as? Class<*>)?.let { hookMethods(it) }
                        }
                    }
                    result
                }
            logger.debug("酷狗歌词直挂：等待 $LYRIC_MANAGER 加载")
        }.onFailure { logger.debug("酷狗歌词直挂 fallback 失败：${it.message}") }
    }

    private fun hookMethods(clazz: Class<*>) {
        if (!hooked.compareAndSet(false, true)) return
        var installed = 0
        for (method in clazz.declaredMethods) {
            val types = method.parameterTypes
            // 词幕 DexKit 条件：参数 (String, boolean)。方法名与返回类型按版本会混淆，
            // 只认签名；静态/实例都挂。
            if (types.size != 2 || types[0] != String::class.java ||
                types[1] != java.lang.Boolean.TYPE
            ) continue
            method.isAccessible = true
            installHook(method)
            installed++
        }
        if (installed == 0) {
            hooked.set(false)
            logger.debug("酷狗歌词直挂：$LYRIC_MANAGER 里没有 (String,boolean) 方法")
            return
        }
        logger.info("酷狗歌词直挂：$LYRIC_MANAGER 挂载 $installed 个候选方法")
    }

    private fun installHook(method: java.lang.reflect.Method) {
        val key = method.name + "|" + method.declaringClass.name
        runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val path = chain.args.getOrNull(0) as? String
                    if (path != null && candidateBudget.get() > 0) {
                        handlePath(path, key)
                    }
                    result
                }
        }.onFailure { logger.debug("酷狗歌词直挂挂载失败 $key：${it.message}") }
    }

    /**
     * 词幕在 lyric 文件到达后做的事：按扩展名选解析器 → 解出歌词 → 交回管线。
     * 这里多一步「解得出才算数」：签名猜中的方法可能是别的 load(String,boolean)，
     * 用解析结果自证，喂进非歌词路径的方法从此不再理。
     */
    private fun handlePath(path: String, key: String) {
        val lower = path.lowercase()
        if (!lower.endsWith(".krc") && !lower.endsWith(".lrc") &&
            !lower.endsWith(".lyc") && !lower.endsWith(".txt")
        ) return
        if (!triedMethods.add(key)) return
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_LYRIC_BYTES) {
            markBadCandidate(key)
            return
        }
        Thread({
            val lyric = runCatching {
                val bytes = file.readBytes()
                when {
                    lower.endsWith(".krc") -> LyricParsers.parseKrc(bytes)
                    lower.endsWith(".lyc") -> LyricParsers.parseKrc(bytes)
                        ?: LyricParsers.parseLrcText(runCatching {
                            bytes.toString(Charsets.UTF_8)
                        }.getOrDefault(""))
                    else -> LyricParsers.parseLrcText(
                        runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
                    )
                }
            }.getOrNull()
            if (lyric != null && LyricParsers.isUsable(lyric.lines)) {
                logger.info(
                    "酷狗直挂命中：$key ← ${file.name}，${lyric.lines.size} 行" +
                        (if (lyric.title != null) " ti=${lyric.title}" else "")
                )
                onLyric(lyric, "酷狗·" + file.name)
            } else {
                markBadCandidate(key)
            }
        }, "xinghe-kg-load").apply { isDaemon = true }.start()
    }

    private fun markBadCandidate(key: String) {
        candidateBudget.decrementAndGet()
        logger.debug("酷狗直挂候选作废：$key（路径解不出歌词）")
    }

    private companion object {
        private const val LYRIC_MANAGER = "com.kugou.framework.lyric.LyricManager"
        private const val MAX_LYRIC_BYTES = 2L * 1024 * 1024
    }
}
