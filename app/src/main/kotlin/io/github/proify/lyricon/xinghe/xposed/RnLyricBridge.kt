package io.github.proify.lyricon.xinghe.xposed
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.util.concurrent.Executors

/**
 * React Native 播放器的歌词嗅探（洛雪音乐助手这类）。
 *
 * 这类播放器的歌词只在 JS 内存里流转：磁盘上没有歌词文件，HTTP 层（被 R8 重整到
 * 单字母包名）按类名找不到。于是在「Java 侧一定能看到」的几个出口上**只读返回值**：
 *
 *  1. `cn.toside.music.mobile.lyric.LyricModule#setLyric(lrc, tlrc, rlrc, promise)`
 *     —— 洛雪把整首歌词（原文 / 翻译 / 音译）交给原生侧渲染时必经此处；
 *  2. 自定义音源回调 `cn.toside.music.mobile.userApi.m`：参数 `[id, action, data]`，
 *     音源脚本返回的歌词就在这里；
 *  3. RN 桥出口：`PromiseImpl#resolve` / `CallbackImpl#invoke` / `CxxCallbackImpl#invoke`；
 *  4. `ReactContext#emitDeviceEvent`：网络模块的响应体（`didReceiveNetworkData`）走这里。
 *
 * 全部只读返回值，不拦截、不改写、不阻断，对播放器零影响；认不出歌词形状就什么都不做。
 * 类要等 React 实例 / 首次调用后才出现，因此挂载失败会按 500ms 重试约两分钟。
 */
internal class RnLyricBridge(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val onLyric: (LocalLyric, String) -> Unit
) {

    private val seen = LinkedHashSet<Int>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xinghe-rn-lyric").apply { isDaemon = true }
    }

    /** 诊断期日志配额，避免刷爆日志 */
    private var budget = 300

    fun install() {
        val pending = arrayListOf<Pair<String, () -> Boolean>>(
            "洛雪歌词模块" to ::hookLyricModule,
            "自定义音源回调" to ::hookScriptCall,
            "RN 桥出口" to ::hookReactBridge,
            "RN 事件出口" to ::hookDeviceEvent
        )
        Thread {
            var round = 0
            while (pending.isNotEmpty() && round < 240) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val (name, installer) = iterator.next()
                    if (runCatching { installer() }.getOrDefault(false)) {
                        logger.info("RN 桥接嗅探已挂载：$name")
                        iterator.remove()
                    }
                }
                if (pending.isEmpty()) return@Thread
                Thread.sleep(500)
                round++
            }
            if (pending.isNotEmpty()) {
                logger.debug("RN 桥接嗅探未挂载（非 RN 应用可忽略）：" + pending.joinToString { it.first })
            }
        }.apply {
            name = "xinghe-rn-hook"
            isDaemon = true
        }.start()
    }

    // ---------------- 洛雪歌词模块 ----------------

    private fun hookLyricModule(): Boolean {
        val clazz = loadClass("cn.toside.music.mobile.lyric.LyricModule") ?: return false
        var hooked = false
        for (method in clazz.declaredMethods) {
            if (Modifier.isStatic(method.modifiers)) continue
            val types = method.parameterTypes
            when {
                method.name == "setLyric" && types.size >= 3 &&
                    types[0] == String::class.java && types[1] == String::class.java -> {
                    installAfterHook(method, "LyricModule#setLyric") { args ->
                        val lrc = args.getOrNull(0) as? String
                        val tlrc = args.getOrNull(1) as? String
                        val rlrc = args.getOrNull(2) as? String
                        note("setLyric lrc=${lrc?.length ?: -1} t=${tlrc?.length ?: -1} r=${rlrc?.length ?: -1}")
                        if (!lrc.isNullOrBlank()) submitLx(lrc, tlrc, rlrc)
                    }
                    hooked = true
                }

                method.name == "play" && types.size >= 1 && types[0] == Integer.TYPE -> {
                    installAfterHook(method, "LyricModule#play") { args ->
                        note("play line=" + args.getOrNull(0))
                    }
                    hooked = true
                }
            }
        }
        return hooked
    }

    /** 洛雪给的是「原文 / 翻译 / 音译」三份 LRC，按行序对齐后一起推给星流 */
    private fun submitLx(lrc: String, tlrc: String?, rlrc: String?) {
        val key = lrc.length * 31 + lrc.hashCode()
        synchronized(seen) {
            if (seen.contains(key)) return
            if (seen.size > 64) seen.clear()
            seen.add(key)
        }
        executor.execute {
            val lyric = runCatching { LyricParsers.parseLrcText(lrc) }.getOrNull() ?: return@execute
            if (lyric.lines.size < 3) return@execute
            runCatching {
                merge(lrc, lyric.lines, tlrc, rlrc)
                onLyric(lyric, "洛雪歌词")
            }.onFailure { logger.error("洛雪歌词投递失败", it) }
        }
    }

    /** 翻译 / 音译与原文行数一致，按序号对齐 */
    private fun merge(
        lrc: String,
        lines: List<io.github.proify.lyricon.lyric.model.RichLyricLine>,
        tlrc: String?,
        rlrc: String?
    ) {
        fill(lines, tlrc) { line, text -> line.translation = text }
        fill(lines, rlrc) { line, text -> if (line.roma == null) line.roma = text }
    }

    private fun fill(
        lines: List<io.github.proify.lyricon.lyric.model.RichLyricLine>,
        extra: String?,
        apply: (io.github.proify.lyricon.lyric.model.RichLyricLine, String) -> Unit
    ) {
        val text = extra?.takeIf { it.isNotBlank() } ?: return
        val parsed = runCatching { LyricParsers.parseLrcText(text) }.getOrNull() ?: return
        if (parsed.lines.size == lines.size) {
            for (i in lines.indices) {
                val row = parsed.lines[i].text?.takeIf { it.isNotBlank() } ?: continue
                apply(lines[i], row)
            }
            return
        }
        // 行数对不上：按时间戳就近匹配
        for (row in parsed.lines) {
            var best: io.github.proify.lyricon.lyric.model.RichLyricLine? = null
            var bestDiff = Long.MAX_VALUE
            for (line in lines) {
                val diff = kotlin.math.abs(line.begin - row.begin)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = line
                }
            }
            val target = best
            val rowText = row.text
            if (target != null && bestDiff <= 800L && !rowText.isNullOrBlank()) apply(target, rowText)
        }
    }

    // ---------------- 自定义音源回调 ----------------

    private fun hookScriptCall(): Boolean {
        val clazz = loadClass("cn.toside.music.mobile.userApi.m") ?: return false
        var hooked = false
        for (method in clazz.declaredMethods) {
            if (Modifier.isStatic(method.modifiers)) continue
            if (method.returnType != Any::class.java) continue
            val types = method.parameterTypes
            if (types.size != 1 || types[0] != Array<Any>::class.java) continue
            installAfterHook(method, "自定义音源回调 ${clazz.simpleName}#${method.name}") { args ->
                val payload = args.getOrNull(0) as? Array<*> ?: return@installAfterHook
                handle(payload.getOrNull(2), "自定义音源" + (payload.getOrNull(1) as? String)?.let { "($it)" }.orEmpty())
            }
            hooked = true
        }
        return hooked
    }

    // ---------------- RN 桥出口 ----------------

    private fun hookReactBridge(): Boolean {
        val promise = loadClass("com.facebook.react.bridge.PromiseImpl")
        val callback = loadClass("com.facebook.react.bridge.CallbackImpl")
        val catalyst = loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        if (promise == null && callback == null && catalyst == null) return false
        var hooked = false

        promise?.declaredMethods
            ?.firstOrNull { it.name == "resolve" && it.parameterTypes.size == 1 }
            ?.let { method ->
                installAfterHook(method, "PromiseImpl.resolve") { args ->
                    handle(args.getOrNull(0), "Promise")
                }
                hooked = true
            }

        loadClass("com.facebook.react.bridge.CxxCallbackImpl")?.declaredMethods
            ?.firstOrNull { it.name == "invoke" && it.parameterTypes.size == 1 }
            ?.let { method ->
                installAfterHook(method, "CxxCallbackImpl.invoke") { args ->
                    handle(args.getOrNull(0), "CxxCallback")
                }
                hooked = true
            }

        callback?.declaredMethods
            ?.firstOrNull { it.name == "invoke" && it.parameterTypes.size == 1 }
            ?.let { method ->
                installAfterHook(method, "CallbackImpl.invoke") { args ->
                    handle(args.getOrNull(0), "Callback")
                }
                hooked = true
            }

        catalyst?.declaredMethods
            ?.filter { it.name == "callFunction" && it.parameterTypes.size == 3 }
            ?.forEach { method ->
                installAfterHook(method, "CatalystInstanceImpl.callFunction") { args ->
                    if ((args.getOrNull(1) as? String) != "emit") return@installAfterHook
                    handle(args.getOrNull(2), "RN事件")
                }
                hooked = true
            }

        return hooked
    }

    private fun hookDeviceEvent(): Boolean {
        val clazz = loadClass("com.facebook.react.bridge.ReactContext") ?: return false
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "emitDeviceEvent" && it.parameterTypes.size == 2
        } ?: return false
        installAfterHook(method, "ReactContext.emitDeviceEvent") { args ->
            val name = args.getOrNull(0) as? String
            handle(args.getOrNull(1), "RN事件" + (name?.let { "($it)" } ?: ""))
        }
        return true
    }

    // ---------------- 判定与投递 ----------------

    /** 诊断期日志 */
    private fun note(message: String) {
        if (!Constants.VERBOSE_BRIDGE) return
        synchronized(this) {
            if (budget <= 0) return
            budget--
            if (budget == 0) logger.info("RN 桥接诊断日志已满，后续静默")
        }
        logger.info("RN桥 " + message)
    }

    /** 只做最便宜的筛查，真正的解析丢到专用线程，别拖慢 JS 线程 */
    private fun handle(value: Any?, source: String) {
        if (value == null) return
        val strings = ArrayList<String>(4)
        runCatching { collect(value, 0, strings) }
        if (strings.isEmpty()) return
        if (Constants.VERBOSE_BRIDGE && budget > 0) {
            for (text in strings.take(2)) {
                note("$source <" + text.length + "> " + text.take(120).replace('\n', '⏎'))
            }
        }
        val candidate = strings.firstOrNull { looksLikeLyric(it) } ?: return
        val key = candidate.hashCode()
        synchronized(seen) {
            if (seen.contains(key)) return
            if (seen.size > 64) seen.clear()
            seen.add(key)
        }
        executor.execute {
            val lyric = runCatching {
                LyricParsers.parseAnyPayload(candidate)
            }.getOrNull() ?: return@execute
            if (lyric.lines.size < 3) return@execute
            runCatching { onLyric(lyric, source) }
                .onFailure { logger.error("RN 桥接歌词投递失败", it) }
        }
    }

    private fun collect(value: Any?, depth: Int, out: MutableList<String>) {
        if (value == null || depth > 4 || out.size >= 24) return
        when (value) {
            is String -> {
                if (value.length >= 8) out.add(value)
                return
            }
            is CharSequence -> {
                collect(value.toString(), depth, out)
                return
            }
            is Array<*> -> {
                for (item in value) collect(item, depth + 1, out)
                return
            }
            is Iterable<*> -> {
                for (item in value) collect(item, depth + 1, out)
                return
            }
            is Map<*, *> -> {
                for (item in value.values) collect(item, depth + 1, out)
                return
            }
            is android.os.Bundle -> {
                for (key in value.keySet()) collect(value.get(key), depth + 1, out)
                return
            }
        }
        val readableArray = READABLE_ARRAY
        if (readableArray != null && readableArray.isInstance(value)) {
            val size = runCatching {
                readableArray.getMethod("size").invoke(value) as? Int
            }.getOrNull() ?: return
            val getType = runCatching {
                readableArray.getMethod("getType", Int::class.java).invoke(value, 0)?.toString()
            }.getOrNull()
            for (index in 0 until minOf(size, 16)) {
                val item = runCatching {
                    readableArray.getMethod("getString", Int::class.java).invoke(value, index) as? String
                }.getOrNull()
                if (item != null) out.add(item)
                else collect(readAny(value, index), depth + 1, out)
            }
            if (getType != null) return
            return
        }
        val readableMap = READABLE_MAP
        if (readableMap != null && readableMap.isInstance(value)) {
            val iterator = runCatching {
                readableMap.getMethod("keySetIterator").invoke(value)
            }.getOrNull() ?: return
            val hasNext = iterator.javaClass.getMethod("hasNextKey")
            val next = iterator.javaClass.getMethod("nextKey")
            var guard = 0
            while (runCatching { hasNext.invoke(iterator) as Boolean }.getOrDefault(false) && guard < 24) {
                guard++
                val key = runCatching { next.invoke(iterator) as? String }.getOrNull() ?: continue
                val item = runCatching {
                    readableMap.getMethod("getString", String::class.java).invoke(value, key) as? String
                }.getOrNull()
                if (item != null) out.add(item) else collect(readAny(value, key), depth + 1, out)
            }
            return
        }
        val name = value.javaClass.name
        if (name.startsWith("com.facebook.react.bridge.")) {
            toList(value)?.forEach { collect(it, depth + 1, out) }
            toMap(value)?.values?.forEach { collect(it, depth + 1, out) }
        }
    }

    private fun readAny(container: Any, key: Any): Any? = runCatching {
        val method = container.javaClass.methods.firstOrNull {
            it.name == "get" && it.parameterTypes.size == 1
        } ?: return@runCatching null
        method.invoke(container, key)
    }.getOrNull()

    /** 便宜的特征：够长、带方括号、含歌词字段名或两处以上时间戳 */
    private fun looksLikeLyric(text: String): Boolean {
        if (text.length < 24 || text.length > 2_000_000) return false
        if (!text.contains('[')) return false
        if (text.contains("\"lyrics\"") || text.contains("\"lyric\"") || text.contains("\"tlyric\"")) return true
        return TIME_TAG.findAll(text).take(2).count() >= 2
    }

    private fun toList(value: Any?): List<Any?>? {
        value ?: return null
        (value as? List<*>)?.let { return it as List<Any?> }
        return runCatching {
            value.javaClass.getMethod("toArrayList").invoke(value) as? List<Any?>
        }.getOrNull()
    }

    private fun toMap(value: Any?): Map<*, *>? {
        value ?: return null
        (value as? Map<*, *>)?.let { return it }
        return runCatching {
            value.javaClass.getMethod("toHashMap").invoke(value) as? Map<*, *>
        }.getOrNull()
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private fun installAfterHook(
        executable: Executable,
        description: String,
        callback: (List<Any?>) -> Unit
    ) {
        module.hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    callback(chain.args)
                } catch (t: Throwable) {
                    logger.error("RN 桥接后置钩子异常: $description", t)
                }
                result
            }
        logger.debug("Installed RN bridge hook: $description")
    }

    private companion object {
        private val READABLE_ARRAY = runCatching {
            Class.forName("com.facebook.react.bridge.ReadableArray")
        }.getOrNull()
        private val READABLE_MAP = runCatching {
            Class.forName("com.facebook.react.bridge.ReadableMap")
        }.getOrNull()
        private val TIME_TAG = Regex("""\[\d{1,3}:\d{1,2}([.:]\d{1,3})?]""")
    }
}
