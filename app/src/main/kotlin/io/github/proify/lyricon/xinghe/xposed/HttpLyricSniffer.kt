package io.github.proify.lyricon.xinghe.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 流式旁路看到的前 32 字节是否「像歌词」——不像就立刻停止缓存（音频流不能被白白缓存）。
 * 认：[gzip 实体]、酷我响应头、JSON/歌词文本（首字符 `[`、含 `lyric` / `TP=` / `lrcx=`）。
 */
internal fun looksLikeLyricPayloadHead(head: ByteArray): Boolean {
    if (head.size < 4) return true
    if (KuwoLyric.isGzip(head)) return true
    if (KuwoLyric.looksLike(head)) return true
    val text = String(head, 0, minOf(head.size, 96), Charsets.ISO_8859_1)
    return text.contains('[') || text.contains("lrcx") || text.contains("TP=") ||
        text.contains("lyric", true) || text.contains("LyricContent")
}

/**
 * 网络歌词旁路嗅探。
 *
 * 有些播放器（洛雪音乐助手这类）拿到歌词只在内存里渲染，不写盘，
 * 走本地缓存通道一个字都读不到。这里在播放器进程里挂 okhttp 的
 * `ResponseBody#string()` / `#bytes()` / `#byteStream()` 与 okio 的读取出口，
 * **只读取返回值**——不拦截、不改写、不消耗数据流，返回原值，因此对播放器行为零影响。
 *
 * 认不出歌词形状（LRC / QRC / YRC / KRC / 酷我 LRCX 或含 lyric 字段的 JSON）就什么都不做；
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

    /** 响应探针日志去重（播放器会把同一份响应体取很多次） */
    private val probed = LinkedHashSet<String>()

    /** 同一份歌词响应的诊断日志只打一次 */
    private fun probedOnce(url: String, size: Int): Boolean = synchronized(probed) {
        if (probed.size > 64) probed.clear()
        probed.add(url + "|" + size)
    }

    /** 响应体 → 所属请求 URL。用弱键，避免把播放器的响应对象留住。 */
    private val bodyUrls: MutableMap<Any, String> = Collections.synchronizedMap(WeakHashMap())

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xinghe-net-lyric").apply { isDaemon = true }
    }

    fun install() {
        val bodyClass = runCatching {
            Class.forName("okhttp3.ResponseBody", false, classLoader)
        }.getOrNull()
        if (bodyClass == null) {
            logger.debug("okhttp3.ResponseBody 不存在，跳过网络歌词嗅探")
        } else {
            hookMethod(bodyClass, "string")
            hookMethod(bodyClass, "bytes")
            hookStream(bodyClass)
        }
        // 有些播放器（酷我）不一定走 ResponseBody#bytes/string，把 okio 的读取出口也挂上（只看返回值，不改行为）
        for (name in listOf("okio.RealBufferedSource", "okio.Buffer")) {
            val klass = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            hookMethod(klass, "readByteArray")
        }
        for (name in listOf("okio.RealBufferedSource")) {
            val klass = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            hookMethod(klass, "readUtf8")
        }
        installUrlLogger()
        installResponseProbe()
    }

    private fun looksLikeLyricUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".krc") || lower.contains(".qrc") || lower.contains(".lrc")) return true
        return lower.contains("lyric") || lower.contains("/krc") || lower.contains("lrc=")
    }

    /** 记录歌词相关请求网址：某些播放器（酷我）到底从哪儿取词，只能靠它确认 */
    private fun installUrlLogger() {
        val clientClass = runCatching {
            Class.forName("okhttp3.OkHttpClient", false, classLoader)
        }.getOrNull() ?: return
        val requestClass = runCatching {
            Class.forName("okhttp3.Request", false, classLoader)
        }.getOrNull() ?: return
        val newCall = runCatching {
            clientClass.getDeclaredMethod("newCall", requestClass)
        }.getOrNull() ?: return
        val urlMethod = runCatching { requestClass.getDeclaredMethod("url") }.getOrNull() ?: return
        runCatching {
            module.hook(newCall)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    runCatching {
                        val url = urlMethod.invoke(chain.args.getOrNull(0))?.toString().orEmpty()
                        if (url.isNotEmpty() && looksLikeLyricUrl(url)) {
                            logger.info("歌词相关请求：$url")
                        }
                    }
                    chain.proceed()
                }
            logger.info("网络歌词嗅探已挂载：okhttp3.OkHttpClient#newCall")
        }.onFailure { logger.debug("OkHttpClient#newCall 挂载失败：" + it.message) }
    }

    /**
     * 歌词响应探针：挂 `okhttp3.Response#body()`，把「响应体 → 请求 URL」对上号，
     * 这样一来不管播放器用哪条读法（byteStream / source / readUtf8 / 自己的解析器），
     * 只要它去取歌词响应体，我们都能先看一眼，并按需补一次旁路解码。
     *
     * `peekBody` 只把数据读进 okhttp 自己的缓冲、不消费，播放器随后照样能读到完整内容。
     */
    private fun installResponseProbe() {
        val responseClass = runCatching {
            Class.forName("okhttp3.Response", false, classLoader)
        }.getOrNull() ?: return
        val bodyMethod = runCatching { responseClass.getDeclaredMethod("body") }.getOrNull()
        if (bodyMethod == null) {
            logger.debug("okhttp3.Response#body 不存在，跳过歌词响应探针")
            return
        }
        val requestMethod = runCatching { responseClass.getDeclaredMethod("request") }.getOrNull()
        val urlMethod = runCatching {
            Class.forName("okhttp3.Request", false, classLoader).getDeclaredMethod("url")
        }.getOrNull()
        val peekBody = runCatching {
            responseClass.getDeclaredMethod("peekBody", Long::class.javaPrimitiveType)
        }.getOrNull()
        runCatching {
            module.hook(bodyMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val response = chain.thisObject
                        val url = if (requestMethod != null && urlMethod != null) {
                            urlMethod.invoke(requestMethod.invoke(response))?.toString().orEmpty()
                        } else {
                            ""
                        }
                        if (url.isNotEmpty() && looksLikeLyricUrl(url)) {
                            result?.let { bodyUrls[it] = url }
                            probe(url, response, result, peekBody)
                        }
                    }
                    result
                }
            logger.info("网络歌词嗅探已挂载：okhttp3.Response#body（歌词响应探针）")
        }.onFailure { logger.debug("Response#body 挂载失败：" + it.message) }
    }

    /** 歌词响应体：打一行诊断（类型 / 长度 / 头部 hex），并按需旁路解码一次 */
    private fun probe(url: String, response: Any?, body: Any?, peekBody: java.lang.reflect.Method?) {
        val length = callLong(body, "contentLength")
        if (length > maxPayload) {
            logger.info("歌词响应过大，跳过：len=$length $url")
            return
        }
        if (response == null || peekBody == null) return
        val limit = if (length in 1..maxPayload.toLong()) length else 64L * 1024L
        val peeked = runCatching { peekBody.invoke(response, limit) }.getOrNull() ?: return
        val bytes = runCatching {
            peeked.javaClass.getMethod("bytes").invoke(peeked) as? ByteArray
        }.getOrNull() ?: return
        if (bytes.isEmpty()) {
            logger.info("歌词响应为空：$url")
            return
        }
        if (probedOnce(url, bytes.size)) {
            logger.info(
                "歌词响应：type=${callString(body, "contentType")} len=${bytes.size} " +
                    "head=${hex(bytes, 24)}"
            )
        }
        dispatch(bytes)
    }

    /** 酷我自己读 InputStream，把 byteStream 的返回值包一层做旁路 */
    private fun hookStream(bodyClass: Class<*>) {
        val method = runCatching { bodyClass.getDeclaredMethod("byteStream") }.getOrNull()
        if (method == null) {
            logger.debug("ResponseBody#byteStream 不存在，跳过")
            return
        }
        try {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val stream = chain.proceed() as? java.io.InputStream
                    if (stream == null) stream else KuwoStreamTee(stream) { dispatch(it) }
                }
            logger.info("网络歌词嗅探已挂载：okhttp3.ResponseBody#byteStream")
        } catch (throwable: Throwable) {
            logger.error("挂载 byteStream 钩子失败", throwable)
        }
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
                    runCatching {
                        if (bodyUrls[chain.thisObject] != null) {
                            val size = (result as? ByteArray)?.size ?: (result as? String)?.length ?: -1
                            logger.info(name + " 读到的歌词响应：len=" + size + " head=" + hexPreview(result))
                        }
                    }
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
            is String -> {
                if (looksLikeKuwoText(result)) {
                    submitKuwo(result.toByteArray(Charsets.ISO_8859_1))
                } else if (looksLikeLyric(result)) {
                    submitString(result)
                }
            }
            is ByteArray -> inspectBytes(result)
        }
    }

    private fun inspectBytes(bytes: ByteArray) {
        if (bytes.size >= 2 && KuwoLyric.isGzip(bytes)) {
            // 应用自己带了 Accept-Encoding: gzip → okhttp 不做透明解压，这里补一刀
            val inflated = runCatching { java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes() }
                .getOrNull()
            if (inflated != null && inflated.isNotEmpty() && inflated.size <= maxPayload) {
                inspectBytes(inflated)
            }
            return
        }
        if (KuwoLyric.looksLike(bytes)) {
            submitKuwo(bytes)
            return
        }
        if (bytes.size < 64 || bytes.size > maxPayload) return
        if (bytes[0] == 'k'.code.toByte() || looksLikeLyric(String(bytes, Charsets.UTF_8))) {
            submitBytes(bytes)
        }
    }

    /** 流式旁路 / 响应探针拿到的整块响应：先解压，再按酷我、通用两种格式试 */
    private fun dispatch(bytes: ByteArray) {
        if (bytes.size < 32) return
        if (KuwoLyric.isGzip(bytes)) {
            inspectBytes(bytes)
            return
        }
        if (KuwoLyric.looksLike(bytes)) {
            submitKuwo(bytes)
            return
        }
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
        if (looksLikeLyric(text)) submitString(text)
    }

    /** 酷我歌词响应被当成字符串读出来（readUtf8 等）时也能认出来 */
    private fun looksLikeKuwoText(text: String): Boolean {
        if (text.length < 32) return false
        val head = text.take(64)
        return head.contains("TP=content") || head.contains("lrcx=")
    }

    /** 酷我歌词响应：先解出 LRCX 文本，再交给正常的歌词通道 */
    private fun submitKuwo(bytes: ByteArray) {
        if (!mark("kuwo:" + bytes.size + ':' + bytes[0] + ':' + bytes[bytes.size - 1])) return
        executor.execute {
            val text = KuwoLyric.decode(bytes)
            if (text == null) {
                logger.info("酷我歌词解码失败（" + bytes.size + " 字节）head=" + hex(bytes, 24))
                return@execute
            }
            val lyric = runCatching { LyricParsers.parseKuwoLrcx(text) }.getOrNull()
            if (lyric == null || !LyricParsers.isUsable(lyric.lines)) {
                logger.info("酷我歌词解析失败：" + text.take(120))
                return@execute
            }
            onLyric(lyric, "酷我")
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
            val lyric = runCatching { LyricParsers.parseAnyPayload(text) }.getOrNull()
            if (lyric == null || !LyricParsers.isUsable(lyric.lines)) {
                // 看着像歌词却解析不出来：把开头打出来，便于定位播放器的歌词格式
                logger.info("疑似歌词但解析失败（" + text.length + " 字符）：" + text.take(160))
                return@execute
            }
            onLyric(lyric, "网络")
        }
    }
    private fun submitBytes(bytes: ByteArray) {
        val key = bytes.size.toString() + ':' + bytes.take(64).joinToString(",")
        if (!mark(key)) return
        executor.execute {
            val lyric = runCatching { LyricParsers.parseAnyBytes(bytes) }.getOrNull() ?: return@execute
            if (!LyricParsers.isUsable(lyric.lines)) return@execute
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

    private fun callLong(target: Any?, name: String): Long {
        if (target == null) return -1L
        return runCatching {
            target.javaClass.getMethod(name).invoke(target) as? Long ?: -1L
        }.getOrDefault(-1L)
    }

    private fun callString(target: Any?, name: String): String {
        if (target == null) return "?"
        return runCatching {
            target.javaClass.getMethod(name).invoke(target)?.toString() ?: "?"
        }.getOrDefault("?")
    }

    private companion object {
        private val LRC_LIKE = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")
        private val QRC_LIKE = Regex("""\[\d{2,7},\d{2,7}]""")

        fun hex(bytes: ByteArray, max: Int): String {
            val n = minOf(bytes.size, max)
            val sb = StringBuilder(n * 2)
            for (i in 0 until n) sb.append("%02x".format(bytes[i]))
            return sb.toString()
        }

        fun hexPreview(result: Any?): String = when (result) {
            is ByteArray -> hex(result, 16)
            is String -> result.take(24)
            else -> "?"
        }
    }
}
