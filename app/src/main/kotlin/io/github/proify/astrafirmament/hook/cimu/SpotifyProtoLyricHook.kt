package io.github.proify.astrafirmament.hook.cimu

import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Spotify 进程内的歌词「响应侧」拦截：不抓请求头、不读缓存文件，直接在 Spotify 自己
 * 解析 color-lyrics protobuf 的那一刻拿已解析对象。
 *
 * 定位证据（9.1.92）：
 *   - 歌词接口 v2 `p.p5e0`（`color-lyrics/v2/track/{trackId}` 与
 *     `color-lyrics/v2/download/track/{trackId}`）/ v3 都带 `Accept: application/protobuf`，
 *     返回 ColorLyricsResponse / LyricsWrapperResponse；
 *   - protobuf-lite 只有两个解析入口：`com.google.protobuf.h.m(h, byte[], int, c000)`
 *     与 `com.google.protobuf.h.parsePartialFrom(h, t5g, c000)`（byte[] 路径走 m，
 *     InputStream 路径走 parsePartialFrom；p.sc30 两条都只是转调），响应体一定经过其一；
 *   - 响应本身**不带**曲目标识（四条生成消息的字段已逐个核对，见 [SpotifyProtoLyric]），
 *     所以只能「请求侧配对 + 响应侧保守归属」。
 *
 * 归属策略（错配是本模块最严重的线上 bug，宁可没词也不能推错词）：
 *   1. 请求按接口族分成 v2 / v3 两条 FIFO：v2 仓库 `p.dce0` 的请求只与
 *      ColorLyricsResponse 配对，v3 仓库 `p.qhe0` 只与 LyricsWrapper/V3Response 配对，
 *      两族永远不互相认领（同一首歌常同时走两族，混在一起会整体错位）；
 *   2. 超时即弃：请求超过 [PENDING_TTL_MS] 未产生响应就作废（丢响应的残留请求是错位的根因）；
 *   3. 只认当前曲目：解析出的歌词必须归属到「当前正在播放的 trackId」才接受，
 *      归属失败（无请求可配 / 配到别的歌 / 没有 trackId）一律丢弃，不落 [captured]、不回调；
 *   4. 时长相容校验：末行 begin 不在曲目时长的合理区间内就丢弃，不写缓存。
 *
 * 解析对象只做了一次字符串后缀判断，真正的反射提取丢到单独线程，不阻塞解析线程。
 */
internal class SpotifyProtoLyricHook(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val enabled: () -> Boolean,
    private val currentTrackId: () -> String?,
    private val currentDurationMs: () -> Long,
    private val onLines: (trackId: String?, lines: List<RichLyricLine>) -> Unit
) {
    private class Pending(val trackId: String, val at: Long)

    private class Captured(val lines: List<RichLyricLine>, val at: Long)

    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-spotify-proto").apply { isDaemon = true }
    }
    private val captured = ConcurrentHashMap<String, Captured>()
    private val pendingLock = Any()
    private val pendingV2 = ArrayDeque<Pending>()
    private val pendingV3 = ArrayDeque<Pending>()

    @Volatile private var lastRequestTrackId: String? = null
    @Volatile private var lastRequestAt = 0L
    @Volatile private var parseReady = false

    fun install(): Boolean {
        val parseHooked = runCatching { installParseHooks() }
            .onFailure { logger.warn("spotify proto 解析 hook 失败：${it.message}") }
            .getOrDefault(false)
        val requestHooked = runCatching { installRequestHooks() }
            .onFailure { logger.warn("spotify proto 请求 hook 失败：${it.message}") }
            .getOrDefault(false)
        if (parseHooked) logger.info("spotify proto lyrics 拦截已挂载（protobuf 解析入口）")
        if (requestHooked) logger.debug("spotify proto lyrics 请求跟踪已挂载（v2/v3 仓库）")
        if (parseHooked && !requestHooked) {
            logger.warn("spotify proto 请求跟踪不可用：拦截结果无法归属曲目，全部丢弃（不走猜测）")
        }
        return parseHooked || requestHooked
    }

    /**
     * 切歌：清空两条在途请求队列。
     *
     * 上一首没等到响应的请求（404 / Spotify 命中自己的内存缓存 / 预取 / 离线下载）如果留着，
     * 就会占掉下一首的响应位置，队列整体错位一格 —— 这是「歌词和歌对不上」的根因，
     * 所以切歌时不讲情面地全清（清掉只是少一次拦截机会，留着会推错词）。
     */
    fun onTrackSwitch() {
        val now = SystemClock.elapsedRealtime()
        synchronized(pendingLock) {
            pendingV2.clear()
            pendingV3.clear()
        }
        // Spotify 几乎总在新曲开始后立刻发一次歌词请求：先乐观地认为「已经发了」，
        // 让查词线程给响应留一个短窗口（真请求一到就用真实时间戳覆盖）。
        // 只是「愿不愿意等一下」的判断，不影响归属：归属仍要求 request == 当前曲目。
        val current = currentTrackId()
        lastRequestTrackId = current
        lastRequestAt = now
        // 抓到的结果只对当前曲目有意义，别的曲目留着只是内存垃圾
        for (key in captured.keys) {
            if (key != current) captured.remove(key)
        }
    }

    /** 当前曲目最近是否发过歌词请求（没有就不必干等；解析 hook 没挂上时也不等） */
    fun hasRecentRequest(trackId: String?): Boolean {
        if (!parseReady) return false
        trackId ?: return false
        return lastRequestTrackId == trackId &&
            SystemClock.elapsedRealtime() - lastRequestAt <= PENDING_TTL_MS
    }

    /** 取一首已抓到的歌词（非阻塞）；trackId 为空直接不给（无法自证归属） */
    fun takeReady(trackId: String?): List<RichLyricLine>? {
        val key = trackId ?: return null
        return take(key, SystemClock.elapsedRealtime())
    }

    /** 在途歌词请求的短等待（查词线程上调用，最多 timeoutMs） */
    fun awaitLyrics(trackId: String?, timeoutMs: Long): List<RichLyricLine>? {
        val key = trackId ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            take(key, SystemClock.elapsedRealtime())?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return null
            }
        }
    }

    // ================= 解析侧 =================

    private fun installParseHooks(): Boolean {
        val protoClass = loadClass(PROTOBUF_CLASS) ?: return false
        val methods = protoClass.declaredMethods
        val byteParse = methods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 4 &&
                it.parameterTypes[1] == ByteArray::class.java &&
                it.parameterTypes[2] == Integer.TYPE
        }
        val streamParse = methods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == "parsePartialFrom" && it.parameterCount == 3
        }
        if (byteParse == null && streamParse == null) return false
        byteParse?.let { hookParse(it, "byte[]") }
        streamParse?.let { hookParse(it, "stream") }
        parseReady = true
        return true
    }

    private fun hookParse(method: Method, label: String) {
        try {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (SpotifyProtoLyric.isLyricsMessage(result)) {
                        captureExecutor.execute { capture(result as Any) }
                    }
                    result
                }
            logger.debug("spotify proto 解析 hook：$label")
        } catch (t: Throwable) {
            logger.warn("spotify proto 解析 hook 安装失败（$label）：${t.message}")
        }
    }

    /**
     * 解析完成后的归属。只有「配到的请求 == 当前曲目」且时长对得上时才接受，
     * 其余一律丢弃（错词比没词更糟，且错词会顺着缓存长期污染）。
     */
    private fun capture(message: Any) {
        if (!enabled()) return
        // 先消费一条在途请求：每条歌词响应都必须吃掉一条请求，否则队列整体错位一格，
        // 之后每一首都会归属到前一格（这就是「偶发错配」的根因）
        val request = takePending(SpotifyProtoLyric.responseFamily(message))
        val current = currentTrackId()
        val lines = runCatching { SpotifyProtoLyric.extract(message) }.getOrNull()
        val fit = lines != null && lines.isNotEmpty() &&
            SpotifyProtoLyric.fitsDuration(lines, currentDurationMs())
        logger.info(
            "spotify proto captured: request=${request ?: NONE} current=${current ?: NONE}" +
                " lines=${lines?.size ?: 0} fit=${if (fit) "yes" else "no"}"
        )
        if (lines == null) {
            logger.warn("spotify proto lyrics 提取失败：字段名对不上")
            return
        }
        if (lines.isEmpty()) return
        // 归属失败：没有可配的请求、当前曲目未知、或配到别的歌 —— 全部丢弃
        if (request == null || current == null || request != current) return
        if (!fit) return
        captured[current] = Captured(lines, SystemClock.elapsedRealtime())
        onLines(current, lines)
    }

    /** 取出该接口族里最早一条仍在途的请求（超时的直接作废，不参与配对） */
    private fun takePending(family: SpotifyProtoLyric.Family?): String? {
        val queue = when (family) {
            SpotifyProtoLyric.Family.V2 -> pendingV2
            SpotifyProtoLyric.Family.V3 -> pendingV3
            null -> return null
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(pendingLock) {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (now - item.at <= PENDING_TTL_MS) return item.trackId
            }
        }
        return null
    }

    // ================= 请求侧（只记录 trackId，不碰参数/头） =================

    private fun installRequestHooks(): Boolean {
        var hooked = false
        for ((className, family) in REPO_CLASSES) {
            val cls = loadClass(className) ?: continue
            for (method in cls.declaredMethods) {
                if (method.name != "a" && method.name != "b") continue
                if (method.parameterCount !in 1..2) continue
                if (method.parameterTypes[0] != String::class.java) continue
                if (Modifier.isStatic(method.modifiers)) continue
                hookRequest(method, className, family)
                hooked = true
            }
        }
        return hooked
    }

    private fun hookRequest(method: Method, owner: String, family: SpotifyProtoLyric.Family) {
        try {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    recordRequest(chain.args.getOrNull(0) as? String, family)
                    chain.proceed()
                }
        } catch (t: Throwable) {
            logger.warn("spotify proto 请求 hook 失败（$owner.${method.name}）：${t.message}")
        }
    }

    private fun recordRequest(uri: String?, family: SpotifyProtoLyric.Family) {
        val trackId = parseTrackId(uri) ?: return
        val now = SystemClock.elapsedRealtime()
        val queue = if (family == SpotifyProtoLyric.Family.V3) pendingV3 else pendingV2
        synchronized(pendingLock) {
            queue.addLast(Pending(trackId, now))
            while (queue.size > MAX_PENDING) queue.removeFirst()
        }
        lastRequestTrackId = trackId
        lastRequestAt = now
        logger.debug("spotify proto request: $trackId (${if (family == SpotifyProtoLyric.Family.V3) "v3" else "v2"})")
    }

    private fun parseTrackId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val core = value.substringAfterLast(':').substringBefore('|').trim()
        return core.takeIf { ID_PATTERN.matches(it) }
    }

    private fun take(key: String, now: Long): List<RichLyricLine>? {
        val item = captured.remove(key) ?: return null
        if (now - item.at > CAPTURE_TTL_MS) return null
        if (!SpotifyProtoLyric.fitsDuration(item.lines, currentDurationMs())) {
            logger.warn("spotify proto 已抓结果与当前时长不符，丢弃：$key")
            return null
        }
        return item.lines
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private companion object {
        private const val PROTOBUF_CLASS = "com.google.protobuf.h"

        /** v2 仓库：持有 `p.p5e0`（color-lyrics/v2 的 track 与 download 两条，返回 ColorLyricsResponse） */
        private const val REPO_V2 = "p.dce0"

        /** v3 仓库：返回 LyricsWrapperResponse / LyricsV3Response */
        private const val REPO_V3 = "p.qhe0"
        private val REPO_CLASSES = arrayOf(
            REPO_V2 to SpotifyProtoLyric.Family.V2,
            REPO_V3 to SpotifyProtoLyric.Family.V3
        )

        private const val NONE = "-"

        /**
         * 请求的「在途」寿命：正常歌词响应远早于此。超过就作废——残留请求是错位根因，
         * 留得越久，后面别的歌的响应越容易被它认领。
         */
        private const val PENDING_TTL_MS = 20_000L
        private const val CAPTURE_TTL_MS = 60_000L
        private const val MAX_PENDING = 8
        private const val POLL_MS = 80L
        private val ID_PATTERN = Regex("[0-9A-Za-z]{22}")
    }
}
