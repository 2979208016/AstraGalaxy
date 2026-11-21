package io.github.proify.astrafirmament.lyric.online

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线歌词用的极简 HTTP GET。
 *
 * 不抛异常：网络抖动、503、被墙都只返回 null 并记一行日志——歌词是锦上添花，
 * 不能因为拿不到就把播放链路拖住。超时按「共享截止时间」算，保证一次 search
 * 的总耗时可控（见 [timeoutUntil]）；预算用完时 [get] 直接返回 null，不再发起
 * 注定超时的请求。
 */
internal object OnlineHttp {

    /** 网易云等对非浏览器 UA 会直接返回错误页，统一伪装成手机浏览器 */
    const val MOBILE_UA: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val ACCEPT = "application/json, text/plain, */*"
    private const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

    /** 单次请求超时的上下限：太短会被慢网断掉，太长会拖垮整次 search */
    private const val MIN_TIMEOUT_MS = 1_200
    private const val MAX_TIMEOUT_MS = 3_000

    /** 响应体上限：歌词 JSON 通常几十 KB，异常大响应要及早截断 */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    private const val SUCCESS_MIN = 200
    private const val SUCCESS_MAX = 299
    private const val READ_CHUNK = 8 * 1024

    /** 查询参数编码：歌名里的 `&`、空格、中文都必须转义 */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * 本次请求线程上是否发生过「传输失败」（异常/非 2xx/读不出 body）。
     *
     * 用来区分「这个源确实没这首歌」和「这个源挂了」：前者可以写负缓存，
     * 后者写了就等于把这首歌锁死半小时。每个 worker 线程开跑前 [resetFailure]。
     */
    private val FAILED = ThreadLocal<Boolean>()

    fun resetFailure() {
        FAILED.set(false)
    }

    fun hadFailure(): Boolean = FAILED.get() == true

    private fun markFailure() {
        FAILED.set(true)
    }

    /**
     * 源侧「连得上但没拿到数据」（如酷我随机的 `status:301`）也标成没验明。
     *
     * 这类失败既不是「确认无词」，也不值得进冷却；标记后本次结果不会参与负缓存判定，
     * 否则一首歌只要赶上一次随机拒答就会被锁半小时。
     */
    fun markUnverified() {
        markFailure()
    }

    /** 剩余预算（毫秒）；已耗尽返回 ≤0，调用方据此提前放弃 */
    fun remaining(deadline: Long): Long = deadline - System.currentTimeMillis()

    /** 把「还剩多少预算」换算成这次请求的超时，避免前一个请求把时间吃光 */
    private fun timeoutUntil(deadline: Long): Int {
        val left = remaining(deadline)
        return left.coerceIn(MIN_TIMEOUT_MS.toLong(), MAX_TIMEOUT_MS.toLong()).toInt()
    }

    /**
     * 发一次 GET 并读回文本。
     *
     * [headers] 用来放各源特有的头（QQ 的 Referer、酷狗的 client 标识等），会覆盖默认值。
     */
    fun get(
        url: String,
        referer: String?,
        deadline: Long,
        log: (String) -> Unit,
        headers: Map<String, String>? = null
    ): String? {
        if (remaining(deadline) <= 0L) {
            // 没来得及发出去也算「这次没验明」，避免它被当成「确认没有歌词」
            markFailure()
            log("预算已耗尽，跳过请求 " + hostOf(url))
            return null
        }
        var opened: HttpURLConnection? = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            opened = conn
            val timeout = timeoutUntil(deadline)
            conn.requestMethod = "GET"
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", MOBILE_UA)
            conn.setRequestProperty("Accept", ACCEPT)
            conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE)
            if (!referer.isNullOrBlank()) conn.setRequestProperty("Referer", referer)
            headers?.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            val code = conn.responseCode
            if (code < SUCCESS_MIN || code > SUCCESS_MAX) {
                markFailure()
                log("HTTP $code " + hostOf(url))
                return null
            }
            conn.inputStream.use { it.readLimited() }
        } catch (e: Exception) {
            markFailure()
            log("请求失败: " + e.javaClass.simpleName + " " + e.message.orEmpty())
            null
        } finally {
            runCatching { opened?.disconnect() }
        }
    }

    /** 只取 host 用于日志，解析失败也不抛 */
    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /** 响应文本 → JSON 对象；顺带剥掉 QQ 老接口常见的 `-` 前缀与 JSONP 外壳 */
    fun jsonOf(body: String?): JSONObject? {
        val text = trimWrap(body)
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** 响应文本 → JSON 数组（LRCLIB 的搜索接口顶层就是数组） */
    fun arrayOf(body: String?): JSONArray? {
        val text = trimWrap(body)
        if (text.isEmpty()) return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    /** 剥 `-{...}` 前缀 / `callback({...})` 外壳，返回裸 JSON 文本 */
    private fun trimWrap(body: String?): String {
        var text = body?.trim().orEmpty()
        if (text.startsWith("-")) text = text.substring(1).trim()
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open > 0 && close > open && !text.startsWith("{") && !text.startsWith("[")) {
            text = text.substring(open + 1, close).trim()
        }
        return text
    }

    /** base64 文本 → UTF-8 字符串（酷狗/QQ 的歌词正文都是 base64） */
    fun decodeBase64(text: String?): String? {
        val payload = text?.trim().orEmpty()
        if (payload.isEmpty()) return null
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
        return runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    /** 有上限地读文本：坏响应不能把内存吃满 */
    private fun InputStream.readLimited(): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() >= MAX_BODY_BYTES) break
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
