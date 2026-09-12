package io.github.proify.lyricon.xinghe.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 网络歌词旁路嗅探。
 *
 * 有些播放器（洛雪音乐助手这类）拿到歌词只在内存里渲染，不写盘，
 * 走本地缓存通道一个字都读不到。这里在播放器进程里挂 okhttp 的
 * `ResponseBody#string()` / `#bytes()`，**只读取返回值**——不拦截、不改写、
 * 不消耗数据流，返回原值，因此对播放器行为零影响。
 *
 * 认不出歌词形状（LRC / QRC / YRC / KRC 或含 lyric 字段的 JSON）就什么都不做；
 * 嗅探失败也不会影响本地缓存通道。
 */
internal class HttpLyricSniffer(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val onLyric: (LocalLyric, String) -> Unit
) {

    /** 单条响应最大处理长度：歌词 JSON 远小于这个数，超了直接放过 */
    private val maxPayload = 1_500_000

    private val seen = LinkedHashSet<String>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xinghe-net-lyric").apply { isDaemon = true }
    }

    fun install() {
        val bodyClass = runCatching {
            Class.forName("okhttp3.ResponseBody", false, classLoader)
        }.getOrNull()
        if (bodyClass == null) {
            logger.debug("okhttp3.ResponseBody 不存在，跳过网络歌词嗅探")
            return
        }
        hookMethod(bodyClass, "string")
        hookMethod(bodyClass, "bytes")
    }

    private fun hookMethod(target: Class<*>, name: String) {
        val method = runCatching { target.getDeclaredMethod(name) }.getOrNull()
        if (method == null) {
            logger.debug("$target#$name 不存在，跳过")
            return
        }
        try {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        inspect(result)
                    } catch (throwable: Throwable) {
                        logger.error("嗅探 $name 失败", throwable)
                    }
                    result
                }
            logger.info("网络歌词嗅探已挂载：${target.name}#$name")
        } catch (throwable: Throwable) {
            logger.error("无法挂载嗅探钩子：$name", throwable)
        }
    }

    // ---------------- 内容判定 ----------------

    private fun inspect(result: Any?) {
        when (result) {
            is String -> if (looksLikeLyric(result)) submitString(result)
            is ByteArray -> {
                if (result.size < 64 || result.size > maxPayload) return
                if (result[0] == 'k'.code.toByte() || looksLikeLyric(String(result, Charsets.UTF_8))) {
                    submitBytes(result)
                }
            }
        }
    }

    /** 在调用线程上跑的「像不像歌词」粗筛，必须很快 */
    private fun looksLikeLyric(text: String): Boolean {
        val length = text.length
        if (length < 64 || length > maxPayload) return false
        if (!text.contains('[') && !text.contains("lyric", true) && !text.contains("lrc", true)) return false
        return LRC_LIKE.containsMatchIn(text) || QRC_LIKE.containsMatchIn(text) ||
            text.contains("LyricContent") || text.contains("tlyric") ||
            text.contains("lang_translations")
    }

    private fun submitString(text: String) {
        if (!mark(text)) return
        executor.execute {
            val lyric = runCatching { LyricParsers.parseAnyPayload(text) }.getOrNull() ?: return@execute
            if (lyric.lines.size < 3) return@execute
            onLyric(lyric, "网络")
        }
    }

    private fun submitBytes(bytes: ByteArray) {
        val key = bytes.size.toString() + ':' + bytes.take(64).joinToString(",")
        if (!mark(key)) return
        executor.execute {
            val lyric = runCatching { LyricParsers.parseAnyBytes(bytes) }.getOrNull() ?: return@execute
            if (lyric.lines.size < 3) return@execute
            onLyric(lyric, "网络")
        }
    }

    /** 同一份歌词会被多个请求/多次重试拿到，去重避免重复推送 */
    private fun mark(payload: String): Boolean {
        val sum = runCatching {
            MessageDigest.getInstance("MD5")
                .digest(payload.take(4096).toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrElse { payload.take(256) }
        synchronized(seen) {
            if (seen.contains(sum)) return false
            if (seen.size > 256) seen.clear()
            seen.add(sum)
        }
        return true
    }

    private companion object {
        private val LRC_LIKE = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")
        private val QRC_LIKE = Regex("""\[\d{2,7},\d{2,7}]""")
    }
}
