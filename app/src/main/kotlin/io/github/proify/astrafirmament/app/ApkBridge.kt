package io.github.proify.astrafirmament.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 智能文件桥接协议：system 只「发现」，读文件 / 校验 / 供 uri 都在本进程。
 *
 * 由来：system_server(uid 1000) 实测读不到 /storage/emulated/0，MediaProvider 侧
 * open 一律被拒，所以发现之后的任何文件操作都不能在 system 里做。action、extra 名、
 * uri 形状、target 协议集中在这里，两端就不会各自漂移。
 */
internal object ApkBridge {
    /** system_server 发广播时 getSentFromPackage() 的取值 */
    const val ANDROID_PACKAGE = "android"

    /** system → App：候选已发现（显式广播，只发给 [ApkDiscoveryReceiver]） */
    const val ACTION_DISCOVERED = "io.github.proify.astrafirmament.APK_DISCOVERED"
    const val RECEIVER_CLASS = "io.github.proify.astrafirmament.app.ApkDiscoveryReceiver"

    const val EXTRA_PATH = "xinghe_apk_path"
    const val EXTRA_NAME = "xinghe_apk_name"
    const val EXTRA_SIZE = "xinghe_apk_size"
    const val EXTRA_MTIME = "xinghe_apk_mtime"
    const val EXTRA_FRESH_MS = "xinghe_apk_fresh_ms"
    const val EXTRA_SOURCE = "xinghe_apk_source"
    const val EXTRA_TOKEN = "xinghe_apk_token"

    /** App → system：这次出卡的副本来自哪个原始安装包（system 用它停掉重投） */
    const val EXTRA_ORIGIN = "xinghe_apk_origin"

    /** App → system：副本就绪，请出卡（搭 LinkHook 的 ACTION_LAUNCH 回执） */
    const val KIND_READY = "file_ready"

    /** App → system：这条候选不用再重投了（文件已不存在 / 始终不完整） */
    const val KIND_ABORT = "file_abort"

    /** 「放弃」信标：system 收到就停掉该路径的重投，避免后台空转到第 6 次才结束 */
    fun abortIntent(context: android.content.Context, packageName: String, path: String): Intent =
        // 不按包定向：system_server 的动态接收器在 ColorOS 上收不到 setPackage("android")；
        // 调用方必须传 PERMISSION_ISLAND_COMMAND 作为 receiverPermission，避免路径被旁听。
        Intent(io.github.proify.astrafirmament.hook.LinkHook.ACTION_LAUNCH)
            .putExtra(io.github.proify.astrafirmament.hook.LinkHook.EXTRA_KIND, KIND_ABORT)
            .putExtra(io.github.proify.astrafirmament.hook.LinkHook.EXTRA_TARGET, path)
            .putExtra("xinghe_from", packageName)

    /** 副本目录：filesDir 下（cacheDir 会被系统清掉，卡点了就装不上） */
    const val DIR_NAME = "apk-incoming"

    /** 副本 Provider：只暴露 [DIR_NAME] 下的只读 apk（导出原因见 AndroidManifest） */
    const val AUTHORITY_SUFFIX = ".apkprovider"
    const val MIME_APK = "application/vnd.android.package-archive"

    const val APK_SUFFIX = ".apk"

    /**
     * `.apk` 之后的「尾巴」：QQ/TIM 的 `.apk.1`、微信的 `.apk.1.1`（多段数字）、
     * 浏览器撞名的 `x.apk (25).1`、系统「重复下载」的 `x.apk (2)`。
     * 统一成「任意个 `.数字` + 可选 `(数字)`」的组合，别的尾巴（`.part`/`.crdownload`）
     * 一律不认——那些是传输中的半成品。
     */
    private val APK_VARIANT_TAIL = Regex("""\.apk(?:\.\d+)*(?:\s*\(\d{1,4}\))?(?:\.\d+)*$""")

    /** target 协议：`apk:<文件名>|<uri>`，LinkHub 用 substringBefore/substringAfter 解析 */
    const val TARGET_PREFIX = "apk:"
    const val TARGET_SEPARATOR = '|'

    /** 文件名里的竖线会破坏 target 协议，替换成全角（与旧 LinkHook 行为一致） */
    fun safeName(name: String): String = name.replace(TARGET_SEPARATOR, '｜')

    fun target(name: String, uri: Uri): String = TARGET_PREFIX + safeName(name) + TARGET_SEPARATOR + uri

    fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

    /** 卡片展示用的 query 参数：包名 / 大小 / 原始路径 / 下载时间（Provider 忽略 query，只认路径） */
    const val QUERY_PACKAGE = "p"
    const val QUERY_SIZE = "s"
    const val QUERY_ORIGIN = "o"
    const val QUERY_MODIFIED = "t"

    /**
     * 副本 URI 的访问口令：URI 形状 /apk-incoming/<token>/<副本名>，
     * token = HMAC-SHA256(本机随机密钥, 副本名) 截断 [TOKEN_BYTES] 字节。
     *
     * 为什么 URI 自带凭证：Provider 必须导出（卡片按钮由 system 代发 intent，
     * AOSP 明确拒绝 system 创建 URI 授权——"the system cannot issue a Uri
     * permission grant ... use startActivityAsCaller() instead"），所以没法靠
     * 授权记录做门禁。改成「URI 本身就是凭证」：token 只随卡片链路发放，不知道
     * token 就算枚举出副本名也伪造不出可读 URI。密钥在 filesDir，仅本进程可读。
     *
     * 只在 App 进程调用（uriFor / Provider 校验）；system 侧只透传 URI，不算 token。
     */
    private const val TOKEN_BYTES = 12
    private const val KEY_FILE_NAME = "apk_provider.key"
    private const val KEY_BYTES = 32

    @Volatile
    private var cachedKey: ByteArray? = null

    fun accessToken(context: Context, copyName: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(accessKey(context), "HmacSHA256"))
        return toHex(mac.doFinal(copyName.toByteArray(Charsets.UTF_8)), TOKEN_BYTES)
    }

    /** 常量时间比对：逐字节时序会泄露 token 前缀，MessageDigest.isEqual 不会 */
    fun tokenMatches(context: Context, copyName: String, token: String): Boolean {
        if (token.length != TOKEN_BYTES * 2) return false
        val expected = accessToken(context, copyName).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, token.toByteArray(Charsets.US_ASCII))
    }

    /** 密钥首次使用时生成；写临时文件再改名，避免半写状态被当成密钥缓存 */
    private fun accessKey(context: Context): ByteArray {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val file = File(context.filesDir, KEY_FILE_NAME)
            val key = runCatching { file.readBytes() }.getOrNull()
                ?.takeIf { it.size == KEY_BYTES }
                ?: ByteArray(KEY_BYTES).also { bytes ->
                    SecureRandom().nextBytes(bytes)
                    val tmp = File(context.filesDir, "$KEY_FILE_NAME.tmp")
                    tmp.writeBytes(bytes)
                    tmp.setReadable(false, false)
                    tmp.setReadable(true, true)
                    check(tmp.renameTo(file)) { "rename key file failed" }
                }
            cachedKey = key
            return key
        }
    }

    private fun toHex(bytes: ByteArray, len: Int): String {
        val out = CharArray(len * 2)
        val digits = "0123456789abcdef"
        for (i in 0 until len) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[v shr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    fun uriFor(
        context: Context,
        copyName: String,
        originPath: String? = null,
        packageName: String? = null,
        size: Long = 0L,
        modifiedAt: Long = 0L
    ): Uri {
        val builder = Uri.Builder()
            .scheme("content")
            .authority(authority(context.packageName))
            .appendPath(DIR_NAME)
            .appendPath(accessToken(context, copyName))
            .appendPath(copyName)
        packageName?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter(QUERY_PACKAGE, it) }
        originPath?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter(QUERY_ORIGIN, it) }
        if (size > 0L) builder.appendQueryParameter(QUERY_SIZE, size.toString())
        if (modifiedAt > 0L) builder.appendQueryParameter(QUERY_MODIFIED, modifiedAt.toString())
        return builder.build()
    }

    fun isOurUri(context: Context, uri: Uri): Boolean = isOurUri(uri, context.packageName)

    /** system_server 的 getPackageName() 是 "android"，那边必须显式传模块包名 */
    fun isOurUri(uri: Uri, packageName: String): Boolean =
        uri.scheme == "content" &&
            uri.authority == authority(packageName) &&
            uri.pathSegments.firstOrNull() == DIR_NAME

    /**
     * 只接受 /storage 与 /data/media 下的 .apk：这条白名单是「帮别人把任意文件
     * 复制出来」的唯一边界；引号/换行会破坏 su 命令，一律拒绝。
     */
    /** 允许 `x.apk`、`x.apk.1`、`星流.apk (25).1` 这类安装包名（QQ 会改名，查看要能定位原件） */
    fun looksLikeApk(path: String): Boolean {
        val lower = path.lowercase()
        if (!lower.contains(APK_SUFFIX)) return false
        return lower.endsWith(APK_SUFFIX) || APK_VARIANT_TAIL.containsMatchIn(lower)
    }

    fun isAllowedPath(path: String): Boolean {
        if (path.length <= APK_SUFFIX.length) return false
        if (!path.startsWith("/storage/") && !path.startsWith("/data/media/")) return false
        if (!looksLikeApk(path)) return false
        if (path.contains("..")) return false
        return path.none { it == '\'' || it == '"' || it == '\n' || it == '\r' || it == '\u0000' }
    }

    /** system 侧发候选：显式组件 + 穿 stopped，模块被 force-stop 也能收到 */
    fun discoveredIntent(
        packageName: String,
        path: String,
        name: String,
        size: Long,
        modifiedAt: Long,
        freshForMs: Long,
        source: String,
        token: String
    ): Intent = Intent(ACTION_DISCOVERED)
        .setClassName(packageName, RECEIVER_CLASS)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra(EXTRA_PATH, path)
        .putExtra(EXTRA_NAME, name)
        .putExtra(EXTRA_SIZE, size)
        .putExtra(EXTRA_MTIME, modifiedAt)
        .putExtra(EXTRA_FRESH_MS, freshForMs)
        .putExtra(EXTRA_SOURCE, source)
        .putExtra(EXTRA_TOKEN, token)

    /** App 侧解析候选；action 不符或路径缺失返回 null */
    fun requestOf(intent: Intent): ApkRequest? {
        if (intent.action != ACTION_DISCOVERED) return null
        val path = intent.getStringExtra(EXTRA_PATH)?.takeIf { it.isNotBlank() } ?: return null
        return ApkRequest(
            path = path,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            size = intent.getLongExtra(EXTRA_SIZE, 0L),
            modifiedAt = intent.getLongExtra(EXTRA_MTIME, 0L),
            freshForMs = intent.getLongExtra(EXTRA_FRESH_MS, 0L),
            source = intent.getStringExtra(EXTRA_SOURCE).orEmpty(),
            token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        )
    }

    /** system → App 的候选载荷 */
    data class ApkRequest(
        val path: String,
        val name: String,
        val size: Long,
        val modifiedAt: Long,
        val freshForMs: Long,
        val source: String,
        val token: String
    )
}
