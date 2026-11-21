package io.github.proify.astrafirmament.hook

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.github.proify.astrafirmament.hook.core.ModuleLogger
import io.github.proify.astrafirmament.capability.link.LinkPatterns
import io.github.proify.astrafirmament.capability.link.LinkHub
import io.github.proify.astrafirmament.app.ApkBridge
import io.github.proify.astrafirmament.shared.SuReader

/**
 * 系统框架侧智能上岛（逻辑驻 system_server，永生）：
 *  1. 检测：剪贴板 hook（锁内零开销，其余甩 worker）+ WiFi 状态广播；
 *     智能文件走三条路：[installInstallHooks] 在 system_server 旁听安装意图
 *     （主路径，不拦截只出卡）、[startSmartFile] 注册 MediaStore 兜底监听、
 *     [installFileWatcher] 在 downloads 作用域挂 DownloadProvider.update（增强）。
 *  2. 拉活：透明 Activity 冷启动为 force-stop 唯一可穿透主通道；
 *     定向广播(0x10) + startService 为非 stopped 态并行通道。
 *  3. 代发：ACTION_LAUNCH 动态接收器——模块点按钮后广播回来，
 *     这里以 system 身份代发 startActivity（豁免 BAL）。
 *
 * 模块进程生命周期：被拉起 → 出卡 → 可回收；清后台不影响链路。
 */
internal class LinkHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xinghe-smart-island-hook").apply { isDaemon = true } }

    /**
     * APK 重投定时器：App 侧发现「文件还在写」时会等一小会儿就退回，等不到就靠这里的
     * 有界重投收尾（浏览器下载大包可能拖好几分钟，光靠一次事件永远等不到）。
     */
    private val apkRetryScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xinghe-apk-retry").apply { isDaemon = true }
    }

    /** 还有待重投的原始路径：App 回执成功后清掉，避免同一份文件被反复复制 */
    private val apkRetryPaths = HashSet<String>()

    @Volatile private var sysContext: Context? = null

    // 智能文件：MediaStore 兜底监听（由 system_server 侧的 LinkHook 持有）
    @Volatile private var fileWatcher: ApkInstallWatcher? = null

    // 安装意图入口的锁内去重（hook 里不做任何重活）
    @Volatile private var lastIntentKey: String? = null
    @Volatile private var lastIntentAt = 0L

    // downloads provider 增强路径的去重（独立进程，与上面互不可见）
    @Volatile private var lastDownloadId = 0L
    @Volatile private var lastDownloadAt = 0L

    /**
     * 「正在下载」关注表（downloads 进程内）：insert 时记下可能是安装包的下载 id，
     * 完成事件到来时连同候选 id 一起复查。
     *
     * 为什么需要：部分 ROM / 下载器把完成事件交给 DownloadManager 时，update 的
     * 参数里捞不出 id（selection 被改写、ContentValues 只带一部分列），只靠
     * [candidateDownloadIds] 会静默漏卡。有关注表，完成事件至少能把「刚才在下
     * 的那几个」挨个查一遍。表很小、带 TTL、只在出卡判断里做输入，不参与其他逻辑。
     */
    private val followedDownloads = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    // 智能文件一次性口令：system 邀请 App 落地时发放，App 回执出卡时核销
    private val apkTokens = java.util.HashMap<String, Long>()

    /** 刚出过卡的路径（path → 出卡时刻）：媒体库/补扫/重投再报同一文件时直接忽略 */
    private val apkDeliveredAt = java.util.HashMap<String, Long>()

    /** 已排定但未执行的重投：清掉时要真的 cancel，否则「已经出过卡又冒一张」 */
    private val apkRetryFutures = java.util.HashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    // 去重只存内容哈希，不存原文 —— 复制的整段文字不能长期留在 system_server 内存里
    @Volatile private var lastHash: Int = 0
    @Volatile private var lastAt: Long = 0L

    @Volatile private var launchRegistered = false

    fun install(): Boolean {
        // companion 里的静态工具函数（WiFi SSID 解析）也要能记日志：注入同一个实例 logger
        staticLogger = logger
        logI("$BUILD_TAG loaded")
        var ok = false
        // 剪贴板 hook
        runCatching {
            val cls = Class.forName(CLIPBOARD_SERVICE, false, classLoader)
            val methods = cls.declaredMethods.filter { it.name == "setPrimaryClipInternalLocked" }
            var hooked = 0
            for (m in methods) {
                runCatching {
                    module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        onClipSet(chain.thisObject, chain.args)
                        chain.proceed()
                    }
                }.onSuccess { hooked++ }
            }
            if (hooked > 0) { logI("智能上岛已挂接：剪贴板=$hooked"); ok = true }
        }
        // 智能文件主路径：旁听安装意图 + MediaStore 兜底监听。与剪贴板取并集——
        // 任一通道可用就算本进程挂载成功（旧实现只认剪贴板，剪贴板挂不上时
        // 连 launch 接收器都不会注册）。
        runCatching { installInstallHooks() }
            .onSuccess { hookedCount ->
                if (hookedCount > 0) logI("智能文件已挂接：安装意图入口×$hookedCount")
                if (hookedCount > 0 || fileWatcher != null) ok = true
            }
            .onFailure { logE("智能文件挂载失败", it) }
        return ok
    }

    /**
     * 智能文件增强路径（只在 com.android.providers.downloads 作用域就绪时执行，
     * 由 HookEntry 分派）：挂 DownloadProvider.update，下载完成 status 置
     * SUCCESSFUL 必经此点。**本进程绝不起 Activity**（后台启动会被 BAL 拦），
     * 只把候选广播给模块进程出卡。
     *
     * 旧实现三处硬伤：
     *  - dm.getUriForDownloadedFile(id) 对非下载发起方抛 SecurityException，
     *    异常被 runCatching 吞掉 -> 静默无效果；
     *  - 从 provider 进程 startActivity 被后台启动限制拦掉；
     *  - whereArgs.firstOrNull() 假设 selection 恒为 `_id=?`。
     */
    fun installFileWatcher(): Boolean {
        var hooked = 0
        runCatching {
            val cls = Class.forName(DOWNLOAD_PROVIDER, false, classLoader)
            val methods = cls.declaredMethods.filter { it.name == "update" }
            for (m in methods) {
                runCatching {
                    module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val rows = chain.proceed()
                        runCatching { onDownloadUpdate(chain.thisObject, chain.args) }
                        rows
                    }
                }.onSuccess { hooked++ }
            }
            // 关注「正在下载」：insert 时记下可能是安装包的 id（完成事件可能捞不到 id）
            for (m in cls.declaredMethods.filter { it.name == "insert" }) {
                runCatching {
                    module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val result = chain.proceed()
                        runCatching { rememberFollowedDownload(chain.args, result) }
                        result
                    }
                }.onSuccess { hooked++ }
            }
        }.onFailure { logE("智能文件挂载失败", it) }
        if (hooked > 0) logI("智能文件已挂接：DownloadProvider.update/insert×$hooked")
        return hooked > 0
    }

    /**
     * 记下一条「正在下载」的安装包（downloads 进程内，纯内存）。
     *
     * 只记疑似安装包的记录：insert 的参数里通常带 mime / uri，非安装包不占位，
     * 免得完成事件把一堆图片视频挨个查一遍、日志里全是「非安装包」。
     */
    private fun rememberFollowedDownload(args: List<Any?>, result: Any?) {
        if (!isFileInstallerOn()) return
        val id = (result as? Uri)?.lastPathSegment?.toLongOrNull() ?: return
        if (id <= 0L) return
        val values = args.filterIsInstance<ContentValues>().firstOrNull() ?: return
        // 用 COLUMN_MEDIA_TYPE（与下面查库同一列）：COLUMN_MIME_TYPE 在 compileSdk 37 的
        // 公开桩里已取不到。insert 阶段这列常常还没写，取空也无妨——下面还有 URI 兜底。
        val mime = values.getAsString(DownloadManager.COLUMN_MEDIA_TYPE).orEmpty()
        val hint = values.getAsString(DownloadManager.COLUMN_URI).orEmpty()
        if (!isApkDownload(hint, mime, hint)) return
        val now = System.currentTimeMillis()
        followedDownloads[id] = now
        if (followedDownloads.size > MAX_FOLLOWED_DOWNLOADS) {
            followedDownloads.entries
                .sortedBy { it.value }
                .take(followedDownloads.size - MAX_FOLLOWED_DOWNLOADS)
                .forEach { followedDownloads.remove(it.key) }
        }
        pruneFollowedDownloads(now)
        logI("智能文件关注下载中 #$id mime=$mime")
    }

    /** 关注表 TTL：下载超过两小时还没完成，就不再跟着它了 */
    private fun pruneFollowedDownloads(now: Long) {
        followedDownloads.entries
            .filter { now - it.value > FOLLOW_DOWNLOAD_TTL_MS }
            .forEach { followedDownloads.remove(it.key) }
    }

    /**
     * 下载状态变更回调（downloads 进程内）：update 置 SUCCESSFUL 后，防御式
     * 找出受影响的下载 id（whereArgs / 字面量 / 数字参数 / ContentValues），
     * 逐条查库确认，APK 则投广播给模块进程——不在这个进程里起岛。
     */
    private fun onDownloadUpdate(provider: Any?, args: List<Any?>) {
        // 先做纯内存过滤：update 会被下载进度高频调用，读偏好（binder）必须放在闸门之后
        val values = args.filterIsInstance<ContentValues>()
            .firstOrNull { it.containsKey(DownloadManager.COLUMN_STATUS) } ?: return
        if (values.getAsInteger(DownloadManager.COLUMN_STATUS) != DownloadManager.STATUS_SUCCESSFUL) return
        if (!isFileInstallerOn()) return
        val ctx = sysContext ?: resolveContext(provider)?.also { sysContext = it } ?: return
        // 查库 + 完整性校验统一在 deliverDownloadedApk 里做：这里只看 id，
        // 避免「先查一次名字、再查一次状态」两次读库之间的状态漂移。
        // 参数里捞不出 id 时（厂商改写 selection / 只给部分 ContentValues），
        // 退回到「刚才关注过的那几条」挨个复查，别让下载完成白白静默。
        pruneFollowedDownloads(System.currentTimeMillis())
        val ids = LinkedHashSet(candidateDownloadIds(args))
        ids.addAll(followedDownloads.keys)
        for (id in ids.take(MAX_DOWNLOAD_IDS + MAX_FOLLOWED_DOWNLOADS)) {
            deliverDownloadedApk(ctx, id, fromSystem = false)
        }
    }

    /**
     * 从 update 参数里捞下载 id：兼容 whereArgs 顺序、selection 里写死的
     * `_id=123`、以及把 id 直接当数字参数传的厂商变体。只做格式判断，不查库。
     */
    private fun candidateDownloadIds(args: List<Any?>): List<Long> {
        val out = LinkedHashSet<Long>()
        val literal = Regex("""_id\s*=\s*(\d+)""")
        for (arg in args) {
            when (arg) {
                is Long -> out.add(arg)
                is Int -> out.add(arg.toLong())
                is Array<*> -> for (v in arg) (v as? String)?.toLongOrNull()?.let { out.add(it) }
                is String -> literal.findAll(arg).forEach { out.add(it.groupValues[1].toLong()) }
                is ContentValues -> arg.getAsLong(DownloadManager.COLUMN_ID)?.let { out.add(it) }
            }
        }
        return out.filter { it > 0L }.take(MAX_DOWNLOAD_IDS)
    }

    /**
     * 按 id 查下载记录，确认「真的下完了」并产出候选：
     *  - 状态必须 SUCCESSFUL；
     *  - total>0 时已下载字节必须等于 total（传输中 total 已定而字节数还在涨）；
     *  - 文件大小由 [ApkIntegrityGuard] 拿同一个 total 再核一次，排除「记录说完了、
     *    文件没落盘或被后处理替换」；文件 mtime 才是新鲜度的唯一事实来源。
     */
    private fun downloadCandidate(ctx: Context, id: Long): ApkCandidate? {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: run {
            logI("智能文件 stage=download 拒绝 #$id：DownloadManager 不可用")
            return null
        }
        return dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use rejectDownload(id, "记录不存在")
            if (colLong(c, DownloadManager.COLUMN_STATUS) != DownloadManager.STATUS_SUCCESSFUL.toLong()) {
                return@use rejectDownload(id, "状态未成功")
            }
            val total = colLong(c, DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val soFar = colLong(c, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            // 传输中的记录：total 已经定下来、字节数还没写满，这一步在任何文件访问前就挡掉
            if (total > 0L && soFar != total) {
                return@use rejectDownload(id, "字节未写满 $soFar/$total")
            }
            val title = colText(c, DownloadManager.COLUMN_TITLE)
            val mime = colText(c, DownloadManager.COLUMN_MEDIA_TYPE)
            val local = colText(c, DownloadManager.COLUMN_LOCAL_URI)
            val path = localPath(local)
                ?: mediaDataPath(ctx, local)
                ?: colText(c, DownloadManager.COLUMN_LOCAL_FILENAME).takeIf { it.startsWith("/") }
            // 记录里的标题常常是 URL 或下载器自起的名字，真实文件名才算数
            val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val rawName = fileName?.takeIf { ApkBridge.looksLikeApk(it) } ?: title
            if (!isApkDownload(rawName, mime, local)) {
                // 只打 scheme：content:// 的 uri 里可能带用户目录信息，日志出口还要过脱敏
                return@use rejectDownload(
                    id, "非安装包 name=$rawName mime=$mime scheme=${local.substringBefore(':')}"
                )
            }
            if (path == null) {
                // 记录是安装包却没有可 stat 的绝对路径：App 侧拿不到真文件，得知道断在这一环
                logI("智能文件 stage=download 路径缺失 #$id：${rawName}")
            }
            // 名字只用于展示与去重：已经像安装包（含 .apk.1 这类）就原样保留，
            // 标题实在没有后缀才补 `.apk`，别把 `x.apk.1` 变成 `x.apk.1.apk`。
            var name = rawName.ifBlank { "download-$id$APK_SUFFIX" }
            if (!ApkBridge.looksLikeApk(name)) name += APK_SUFFIX
            ApkCandidate(
                name = name,
                uri = Uri.parse("content://downloads/all_downloads/$id"),
                expectedSize = if (total > 0L) total else 0L,
                freshForMs = FILE_FRESH_MS,
                modifiedAtHint = colLong(c, DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP) * 1_000L,
                pathHint = path,
                source = "download"
            )
        }
    }

    /** 下载记录的拒绝原因：合并成一句「忽略下载」时，用户根本看不出是哪一环断的 */
    private fun rejectDownload(id: Long, reason: String): ApkCandidate? {
        logI("智能文件 stage=download 拒绝 #$id：$reason")
        return null
    }

    /**
     * APK 判定：mime + 标题 + 本地 URI，三者任一命中即可。
     * 后缀用 [ApkBridge.looksLikeApk] 而不是 `endsWith(".apk")`：QQ/TIM 会把安装包
     * 改名成 `x.apk.1`、撞名时是 `x.apk (25).1`，这些同样是安装包。
     */
    private fun isApkDownload(title: String, mime: String, localUri: String): Boolean =
        mime == APK_MIME || ApkBridge.looksLikeApk(title) || ApkBridge.looksLikeApk(localUri)

    /** 本地 uri 只认 file://；content:// 要去媒体库换真实路径，不能直接 Uri.path 当文件 */
    private fun localPath(localUri: String): String? {
        if (localUri.isBlank()) return null
        val uri = runCatching { Uri.parse(localUri) }.getOrNull() ?: return null
        if (uri.scheme != "file") return null
        return uri.path?.takeIf { it.startsWith("/") }
    }

    /**
     * content:// 下载记录 → 真实路径。Android 10+ 的 COLUMN_LOCAL_URI 普遍是
     * `content://media/external/downloads/<id>`，把它当路径用必然 stat 失败
     * （这是浏览器下载「记录在、卡不来」的另一半原因），只能查 `_data` 换真路径。
     */
    private fun mediaDataPath(ctx: Context, localUri: String): String? {
        if (localUri.isBlank()) return null
        val uri = runCatching { Uri.parse(localUri) }.getOrNull() ?: return null
        if (uri.scheme != "content") return null
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx < 0 || c.isNull(idx)) null else c.getString(idx)
                }
        }.getOrNull()?.takeIf { it.startsWith("/") }
    }

    private fun colText(c: Cursor, column: String): String {
        val i = c.getColumnIndex(column)
        return if (i >= 0 && !c.isNull(i)) c.getString(i).orEmpty() else ""
    }

    private fun colLong(c: Cursor, column: String): Long {
        val i = c.getColumnIndex(column)
        return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
    }

    /**
     * 下载完成 -> 出卡。system 侧直接进检测器；downloads provider 进程里只投广播
     * （那边后台起 Activity 会被拦，模块的 IslandService 又未导出）。
     *
     * 去重只在「确认是完整 APK」之后才记：记录刚置 SUCCESSFUL 但字节数还没写满时先
     * 返回，随后 system 侧的 ACTION_DOWNLOAD_COMPLETE 再进来仍然有效——否则完成事件
     * 会被一次过早的 no-op 占掉 10 分钟去重位。
     */
    private fun deliverDownloadedApk(ctx: Context, id: Long, fromSystem: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (id == lastDownloadId && now - lastDownloadAt < FILE_DEDUP_MS) return
        val candidate = runCatching { downloadCandidate(ctx, id) }.getOrNull()
        if (candidate == null) {
            // 具体断点由 downloadCandidate 打（stage=download），这里只留一个汇总点
            logI("智能文件 stage=download 结束 #$id：无候选，未投递")
            return
        }
        lastDownloadId = id
        lastDownloadAt = now
        // 这条下载已经走到出卡判断了，关注表里不再留它
        followedDownloads.remove(id)
        if (fromSystem) {
            // 同一进程：直接进检测器，和 media/intent 两条路共享去重与完整性校验
            ensureFileWatcher(ctx)
            fileWatcher?.offer(candidate)
        } else {
            // 跨进程：只发信号，由 system 侧重查库 + 校验后统一出卡
            broadcastFileDetected(ctx, candidate, id)
        }
    }

    /**
     * downloads 进程 -> system：只发「发现 APK」信号，不碰 Activity / startService。
     *
     * 新增的两个 extra 只给本类自己的接收方用（LinkHub 不消费，无需改动）：
     *  - [EXTRA_FILE_DOWNLOAD_ID]：接收方按 id 重查下载库，**不信任广播里的名字/URI**；
     *  - [EXTRA_FILE_SENT_AT]：发送时刻（elapsedRealtime），接收方据此丢弃滞留/回放信号。
     * 老的 NAME/URI 保留，只当日志与旧接收方兜底。
     */
    private fun broadcastFileDetected(ctx: Context, candidate: ApkCandidate, id: Long) {
        runCatching {
            val b = Intent(ACTION_FILE_DETECTED)
                .addFlags(0x00000010)
                .putExtra(EXTRA_FILE_DOWNLOAD_ID, id)
                .putExtra(EXTRA_FILE_SENT_AT, SystemClock.elapsedRealtime())
                .putExtra(EXTRA_FILE_NAME, candidate.name)
                .putExtra(EXTRA_FILE_URI, candidate.uri.toString())
            ctx.sendBroadcast(b)
            logI("智能文件信号已投递（downloads 进程）：${candidate.name}")
        }.onFailure { logE("智能文件信号投递失败", it) }
    }

    /**
     * 智能文件主路径：system_server 侧旁听安装意图入口。
     * 只在「ACTION_VIEW / ACTION_INSTALL_PACKAGE + APK」时命中，**绝不拦截**——
     * chain.proceed() 照常执行，用户装 App 不受影响；命中只甩 worker 出卡。
     * 方法签名按名字+参数宽松匹配（ColorOS 与 AOSP 有差异），全部 runCatching。
     */
    private fun installInstallHooks(): Int {
        if (sysContext == null) sysContext = resolveSystemContext()
        sysContext?.let { ensureFileWatcher(it) }
        var hooked = 0
        for (target in INSTALL_HOOK_CLASSES) {
            runCatching {
                val cls = Class.forName(target, false, classLoader)
                for (m in cls.declaredMethods) {
                    if (!isInstallEntry(m)) continue
                    runCatching {
                        module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            runCatching { inspectStart(chain.args) }
                            chain.proceed()
                        }
                    }.onSuccess { hooked++ }
                }
            }.onFailure { logE("安装意图入口挂载失败：$target", it) }
        }
        return hooked
    }

    /** 名字命中 + 参数里有 Intent（executeRequest 例外：Intent 藏在 Request 字段里） */
    private fun isInstallEntry(m: java.lang.reflect.Method): Boolean {
        if (m.name !in INSTALL_ENTRY_NAMES) return false
        if (m.name == "executeRequest") return true
        return m.parameterTypes.any { it == Intent::class.java || (it.isArray && it.componentType == Intent::class.java) }
    }

    /**
     * 安装意图入口回调 —— 可能持有 WM 全局锁，**这里只做纯内存判断**：
     * 取 Intent（含 ActivityStarter$Request 的字段）、匹配 APK 安装、甩 worker。
     */
    private fun inspectStart(args: List<Any?>) {
        val intent = extractIntent(args) ?: return
        // 我们自己发起的安装/查看意图会带着这个标记，别再弹一张「下载完成」的卡
        if (intent.getBooleanExtra(EXTRA_SELF_INSTALL, false)) return
        if (!looksLikeApkInstall(intent)) return
        val uri = intent.data ?: return
        val key = uri.toString()
        val now = SystemClock.elapsedRealtime()
        if (key == lastIntentKey && now - lastIntentAt < INTENT_DEDUP_MS) return
        lastIntentKey = key
        lastIntentAt = now
        worker.execute { handleInstallIntent(uri, intent.type) }
    }

    private fun extractIntent(args: List<Any?>): Intent? {
        for (arg in args) {
            when (arg) {
                is Intent -> if (arg.action != null) return arg
                is Array<*> -> (arg.firstOrNull() as? Intent)?.let { return it }
                is String, null -> Unit
                else -> intentField(arg)?.let { return it }
            }
        }
        return null
    }

    /** ActivityStarter$Request 之类把 Intent 藏在字段里：按类型捞，优先字段名 intent */
    private fun intentField(obj: Any): Intent? {
        var cls: Class<*>? = obj.javaClass
        var depth = 0
        while (cls != null && depth < 3) {
            val fields = cls.declaredFields
                .filter { it.type == Intent::class.java }
                .sortedBy { if (it.name == "intent" || it.name == "mIntent") 0 else 1 }
            for (f in fields) {
                val v = runCatching {
                    f.isAccessible = true
                    f.get(obj) as? Intent
                }.getOrNull() ?: continue
                if (v.action != null) return v
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    private fun looksLikeApkInstall(intent: Intent, depth: Int = 0): Boolean {
        if (depth > 1) return false
        // 卡片上的「安装」会以 ACTION_VIEW 回到这里，别自己弹自己
        if (intent.getBooleanExtra(EXTRA_SELF_INSTALL, false)) return false
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE -> Unit
            Intent.ACTION_CHOOSER -> {
                val inner = chooserTarget(intent) ?: return false
                return looksLikeApkInstall(inner, depth + 1)
            }
            else -> return false
        }
        if (intent.type == APK_MIME) return true
        // QQ/TIM 的 `x.apk.1` 安装意图同样算安装包（后缀判定统一走 ApkBridge）
        if (ApkBridge.looksLikeApk(intent.data?.toString().orEmpty())) return true
        // 国内文件管理器常把 APK 的 MIME 报成 octet-stream 甚至空：这里在 WM 锁内，
        // 不能查库也不能 stat，只放「含糊 MIME + 可打开 scheme」进 worker 再判后缀。
        // 误判由 worker 侧的后缀判定与白名单兜住，不会因此弹卡。
        return isOpenerMime(intent.type) && openableScheme(intent.data)
    }

    /** 文件管理器给安装包报的含糊 MIME：只有这几种才值得再进 worker 判一次 */
    private fun isOpenerMime(type: String?): Boolean =
        type.isNullOrEmpty() || type == "application/octet-stream" || type == "*/*"

    private fun openableScheme(data: Uri?): Boolean =
        data != null && (data.scheme == "file" || data.scheme == "content")

    private fun chooserTarget(intent: Intent): Intent? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    /** worker 线程：偏好闸门 -> 反查文件名/路径 -> 交给检测器（校验与出卡在 App 进程） */
    private fun handleInstallIntent(uri: Uri, type: String?) {
        runCatching {
            if (!isFileInstallerOn()) return
            val ctx = sysContext ?: return
            ensureFileWatcher(ctx)
            val queried = queryNameSize(ctx, uri)?.first?.takeIf { it.isNotBlank() }
            // 名字拿不到时只有 MIME 明确是 APK 才敢猜名：「含糊 MIME」也会进到这里，
            // 对任意文件都猜成 .apk 的话，打开图片/文档都会白跑一次复制
            val name = queried ?: if (type == APK_MIME) guessName(uri, type) else ""
            if (!ApkBridge.looksLikeApk(name)) {
                logI("智能文件 stage=intent 跳过：名字不像安装包 name=$name type=$type")
                return
            }
            // 用户主动点开安装包：不设新鲜度门槛（几小时前下载的也能装），
            // 但完整性照样要过——点到的要是半个包，装也装不上、分享出去也是坏的
            val candidate = ApkCandidate(
                name = name,
                uri = uri,
                expectedSize = 0L,
                freshForMs = 0L,
                modifiedAtHint = 0L,
                pathHint = resolvePathHint(ctx, uri),
                source = "intent"
            )
            fileWatcher?.offer(candidate)
            logI("智能文件 stage=intent 已交给检测器：$name")
        }.onFailure { logE("安装意图处理失败", it) }
    }

    /**
     * 意图给的 content:// 没有路径，但 MediaStore / Downloads 的 DATA 列有；App 进程
     * 只能按真实路径 su 复制，所以这里必须把路径反查出来。查不到返回 null，由检测器
     * 丢弃——不能再退回「system 自己 open 试试」那条路（uid 1000 读不到 /storage）。
     */
    private fun resolvePathHint(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return runCatching {
            val cols = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 意图 URI 反查文件名/大小（OpenableColumns 标准列）；查不到返回 null */
    private fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long>? {
        val cols = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
        return ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0).orEmpty()
            val size = if (c.isNull(1)) 0L else c.getLong(1)
            name to size
        }
    }

    private fun guessName(uri: Uri, type: String?): String {
        val tail = uri.lastPathSegment.orEmpty()
        if (ApkBridge.looksLikeApk(tail)) return tail
        if (tail.isNotBlank()) return "$tail$APK_SUFFIX"
        return if (type == APK_MIME) "install$APK_SUFFIX" else "package$APK_SUFFIX"
    }

    /** 首次拿到 Context 时创建检测器并启动 MediaStore 兜底（幂等） */
    private fun ensureFileWatcher(ctx: Context) {
        if (fileWatcher != null) return
        synchronized(this) {
            if (fileWatcher != null) return
            val watcher = ApkInstallWatcher(
                ctx,
                { isFileInstallerOn() },
                { msg -> logI(msg) },
                { candidate -> onApkFound(candidate) }
            )
            fileWatcher = watcher
            watcher.startMediaObserver()
        }
    }

    /** 检测器回调（media HandlerThread）：统一甩 worker，落地串行、一次一个 */
    private fun onApkFound(candidate: ApkCandidate) {
        val path = candidate.pathHint
        if (path != null) {
            val at = synchronized(apkDeliveredAt) { apkDeliveredAt[path] }
            if (at != null && System.currentTimeMillis() - at < APK_DELIVERED_QUIET_MS) {
                logI("智能文件刚出过卡，忽略重复命中：${candidate.name}")
                return
            }
        }
        worker.execute { deliverToApp(candidate, 1) }
    }

    /**
     * 排下一次重投：App 侧一次处理不完（大文件还在写）就按 [APK_RETRY_DELAYS_MS] 递增间隔
     * 再投几次；App 一旦回执出卡就清掉这条路径，之后不再重投。
     */
    private fun scheduleApkRetry(candidate: ApkCandidate, attempt: Int) {
        val path = candidate.pathHint ?: return
        if (attempt > APK_RETRY_DELAYS_MS.size) {
            synchronized(apkRetryPaths) { apkRetryPaths.remove(path) }
            logI("智能文件放弃重投（已试 ${attempt - 1} 次）：${candidate.name}")
            return
        }
        // 同一个路径同时只留一个待执行的重投：媒体库/意图/重投三条路会重复投递，
        // 不做这个闸门就会变成「一次下载排十几个定时器」，日志里全是同一秒的重投。
        val first = synchronized(apkRetryPaths) { apkRetryPaths.add(path) }
        if (!first) return
        val delay = APK_RETRY_DELAYS_MS[attempt - 1]
        val future = apkRetryScheduler.schedule({
            runCatching {
                synchronized(apkRetryPaths) { apkRetryPaths.remove(path) }
                synchronized(apkRetryFutures) { apkRetryFutures.remove(path) }
                if (!isFileInstallerOn()) return@runCatching
                logI("智能文件第 $attempt 次重投：${candidate.name}")
                worker.execute { deliverToApp(candidate, attempt) }
            }
        }, delay, TimeUnit.MILLISECONDS)
        synchronized(apkRetryFutures) { apkRetryFutures[path] = future }
    }

    /** App 侧明确放弃（文件没了 / 校验始终不过）：停止重投，别在后台空转 */
    private fun onApkAbort(origin: String) {
        synchronized(apkRetryPaths) { apkRetryPaths.remove(origin) }
        synchronized(apkRetryFutures) { apkRetryFutures.remove(origin)?.cancel(false) }
        logI("智能文件 App 放弃，停止重投：$origin")
    }

    /** App 已出卡：这条路径到此为止，别再来第二张卡 */
    private fun clearApkRetry(origin: String) {
        synchronized(apkRetryPaths) { apkRetryPaths.remove(origin) }
        synchronized(apkRetryFutures) { apkRetryFutures.remove(origin)?.cancel(false) }
    }

    /**
     * system 只负责「发现」，真正读文件的是有 root 的 App 进程。
     *
     * 这里绝不能再 open 一次做复检：system_server(uid 1000) 读不到 /storage，
     * 那样每个候选都会在最后一步被自己否掉（5.6 的失效就是这一条）。剩下能做的
     * 只有纯字符串级判断——路径白名单与新鲜度——然后把候选交给 App。
     */
    private fun deliverToApp(candidate: ApkCandidate, attempt: Int) {
        runCatching {
            val ctx = sysContext ?: return
            val path = candidate.pathHint?.takeIf { it.startsWith("/") }
            if (path == null) {
                logI("智能文件缺少真实路径，无法交给 App：${candidate.name}")
                return
            }
            if (!ApkBridge.isAllowedPath(path)) {
                logI("智能文件路径不在白名单，丢弃：$path")
                return
            }
            val age = System.currentTimeMillis() - candidate.modifiedAtHint
            val stale = candidate.freshForMs > 0L && candidate.modifiedAtHint > 0L &&
                (age > candidate.freshForMs || age < -FILE_FUTURE_SKEW_MS)
            if (stale) {
                logI("智能文件不新鲜，丢弃：${candidate.name}")
                return
            }
            val intent = ApkBridge.discoveredIntent(
                MODULE_PACKAGE,
                path,
                candidate.name,
                candidate.expectedSize,
                candidate.modifiedAtHint,
                candidate.freshForMs,
                candidate.source,
                newApkToken()
            )
            ctx.sendBroadcast(intent)
            logI("已把智能文件候选交给 App(${candidate.source})：${candidate.name} path=$path 第 $attempt 次")
            scheduleApkRetry(candidate, attempt + 1)
        }.onFailure { logE("智能文件投递失败", it) }
    }

    /**
     * App 出卡回执：必须带「刚投递过的一次性口令」且 target 指向我方副本 Provider，
     * 否则任意 App 一条广播就能往岛上塞任意卡片。
     */
    private fun onFileReady(ctx: Context, target: String, token: String, origin: String?) {
        if (!consumeApkToken(token)) {
            logI("智能文件出卡口令无效或已过期，丢弃：$target")
            return
        }
        if (!target.startsWith(ApkBridge.TARGET_PREFIX)) return
        val uri = targetUri(target)
        if (uri == null || !ApkBridge.isOurUri(uri, MODULE_PACKAGE)) {
            logI("智能文件出卡 uri 不是我方 Provider，丢弃：$target")
            return
        }
        if (!origin.isNullOrBlank()) {
            clearApkRetry(origin)
            synchronized(apkDeliveredAt) { apkDeliveredAt[origin] = System.currentTimeMillis() }
        }
        outCard(ctx, KIND_FILE_OUT, target)
    }

    /** 一次性口令：把「system 邀请过的候选」与「App 回执」绑在一起，按 TTL 与上限回收 */
    private fun newApkToken(): String {
        val token = java.util.UUID.randomUUID().toString()
        synchronized(apkTokens) {
            val now = System.currentTimeMillis()
            apkTokens.entries.removeAll { now - it.value > APK_TOKEN_TTL_MS }
            if (apkTokens.size >= APK_TOKEN_MAX) {
                // 只回收最老的那些，绝不整表清空：清空会把另一条链路正在 su 复制中的
                // 有效口令一起作废，App 回执时 consumeApkToken 返回 false，那张卡就静默不弹了
                val overflow = apkTokens.size - APK_TOKEN_MAX + 1
                apkTokens.entries.sortedBy { it.value }.take(overflow).forEach { apkTokens.remove(it.key) }
            }
            apkTokens[token] = now
        }
        return token
    }

    private fun consumeApkToken(token: String): Boolean {
        if (token.isEmpty()) return false
        val issuedAt = synchronized(apkTokens) { apkTokens.remove(token) } ?: return false
        return System.currentTimeMillis() - issuedAt <= APK_TOKEN_TTL_MS
    }

    /**
     * 出卡投递（system 身份）。正常路径**只**走「定向广播 + startService」，
     * 不拉透明 Activity——那个全屏窗口正是 7.11「时不时卡一下、整屏点不了」的来源。
     * 只有包处于 force-stop/disabled 态（广播与 startService 都会被丢弃）才用
     * 透明 Activity 穿透；判断走 ActivityManagerInternal.isAppStartModeDisabled。
     * 三条腿共用同一个 token，模块侧 10s 内按 token 只处理一次。
     */
    private fun outCard(ctx: Context, kind: String, target: String) {
        val token = java.util.UUID.randomUUID().toString()
        val forceStopped = isModuleStartDisabled(ctx)
        if (forceStopped) {
            runCatching {
                io.github.proify.astrafirmament.app.IslandColdStartActivity
                    .startFromSystem(ctx, kind, target, token)
            }.onFailure { logE("冷启动出卡失败 kind=$kind", it) }
        }
        runCatching {
            val broadcast = Intent(io.github.proify.astrafirmament.app.IslandDeliverReceiver.ACTION)
                .setPackage(MODULE_PACKAGE)
                .addFlags(0x00000010)
                .putExtra(io.github.proify.astrafirmament.app.IslandDeliverReceiver.EXTRA_KIND, kind)
                .putExtra(io.github.proify.astrafirmament.app.IslandDeliverReceiver.EXTRA_TARGET, target)
                .putExtra(io.github.proify.astrafirmament.app.IslandDeliverReceiver.EXTRA_TOKEN, token)
            ctx.sendBroadcast(broadcast)
        }.onFailure { logE("出卡广播失败 kind=$kind", it) }
        val serviceFailed = !io.github.proify.astrafirmament.app.IslandService
            .connectAndShow(ctx, kind, target, token)
        if (serviceFailed) {
            logE("出卡 startService 失败 kind=$kind", IllegalStateException("connectAndShow returned false"))
            // 广播也送达不了（stopped 态判断失误/ROM 限制）时兜底透明页
            runCatching {
                io.github.proify.astrafirmament.app.IslandColdStartActivity
                    .startFromSystem(ctx, kind, target, token)
            }.onFailure { logE("兜底冷启动失败 kind=$kind", it) }
        }
        logI("已投递出卡请求 kind=$kind forceStopped=$forceStopped token=${token.take(8)}")
    }

    /**
     * 包是否处于 force-stop / 被禁用（此时只有 startActivity 能穿透）。
     * 通过 system_server 内部的 ActivityManagerInternal 判断；反射失败时保守返回
     * true（多拉一次透明页可以接受，丢掉 force-stop 场景则整条链路失效）。
     */
    private fun isModuleStartDisabled(ctx: Context): Boolean = runCatching {
        // com.android.server.* 在 system_server 的 PathClassLoader 里；模块自己的
        // classloader 未必看得见，按「当前线程 → system context 的 loader → 默认」逐个试。
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            ctx.javaClass.classLoader,
            javaClass.classLoader
        )
        fun load(name: String): Class<*> {
            var last: Throwable? = null
            for (l in loaders) {
                runCatching { return Class.forName(name, false, l) }.onFailure { last = it }
            }
            throw last ?: ClassNotFoundException(name)
        }
        val localServices = load("com.android.server.LocalServices")
        val amInternalClass = load("com.android.server.am.ActivityManagerInternal")
        val service = localServices.getMethod("getService", Class::class.java)
            .invoke(null, amInternalClass) ?: return@runCatching false
        val uid = ctx.packageManager.getPackageUid(MODULE_PACKAGE, 0)
        amInternalClass
            .getMethod("isAppStartModeDisabled", Int::class.javaPrimitiveType, String::class.java)
            .invoke(service, uid, MODULE_PACKAGE) as? Boolean ?: false
    }.getOrDefault(true) // 判断失败时保守走 Activity（宁可多一个透明页，也不能丢 force-stop 场景）

    /**
     * system_server 里没有现成 Context（旧实现要等一次剪贴板事件才赋值，导致
     * 开机 registerAllReceivers 全空转）。ActivityThread / AppGlobals 都是隐藏
     * API，走反射；拿不到就返回 null，由调用方降级。
     */
    private fun resolveSystemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
        at.getMethod("getSystemContext").invoke(thread) as? Context
    }.getOrNull() ?: runCatching {
        val globals = Class.forName("android.app.AppGlobals")
        globals.getMethod("getInitialApplication").invoke(null) as? Context
    }.getOrNull()

    /**
     * 剪贴板 hook 回调 —— **必须在调用方锁内零开销**。
     * setPrimaryClipInternalLocked 持有 ClipboardService 内部锁调用本回调；
     * 只做纯内存三步（itemCount / description 哈希 / 甩 worker），其余一切
     * （文本提取、正则、偏好、bindService、startActivity）在 worker 线程，
     * 避免与系统反向链交叉成死锁（历史事故：锁内 registerReceiver 拖死 system）。
     */
    private fun onClipSet(service: Any?, args: List<Any?>) {
        try {
            val clip = args.getOrNull(0) as? ClipData ?: return
            if (clip.itemCount == 0) return
            val now = android.os.SystemClock.elapsedRealtime()
            val hash = clip.description?.hashCode() ?: return
            if (hash == lastHash && now - lastAt < DEDUP_MS) return
            lastHash = hash; lastAt = now
            worker.execute { handleClipOffLock(service, clip) }
        } catch (_: Throwable) { }
    }

    /** 锁外全流程：识别 → 拉活模块出卡（全 worker 线程） */
    private fun handleClipOffLock(service: Any?, clip: ClipData) {
        runCatching {
            val ctx = sysContext ?: resolveContext(service)?.also { sysContext = it } ?: return
            if (!isMasterEnabled()) return
            val text = clip.getItemAt(0)?.coerceToText(null)?.toString().orEmpty()
            if (text.isBlank()) return

            val prefs = runCatching { module.getRemotePreferences(PREFS) }.getOrNull() ?: return
            val linkOn = ModulePrefs.isLinkEnabled(prefs)
            val phoneOn = ModulePrefs.isPhoneEnabled(prefs)
            if (!linkOn && !phoneOn) return

            val number = if (phoneOn) LinkPatterns.firstPhone(text) else null
            val url = if (number == null && linkOn) LinkPatterns.firstLink(text) else null
            logI("剪贴板识别：len=${text.length} phone=${number != null} link=${url != null}")
            if (number == null && url == null) return

            // 代发接收器：按钮点击的回传通道（首次拿到 ctx 时注册，双检防重）
            ensureLauncherReceiver(ctx)

            // 拉活模块进程出卡（三通道，含清后台全覆盖；细节见 [outCard]：
            // ① 透明 Activity 穿 force-stop/BAL，② 定向广播 0x10，③ startService）
            val kind = if (number != null) KIND_PHONE_OUT else KIND_LINK_OUT
            val target = number ?: url!!
            outCard(ctx, kind, target)
        }.onFailure { logE("剪贴板处理失败", it) }
    }


    /**
     * 注册按钮代发接收器（system_server 里动态注册，RECEIVER_EXPORTED：
     * 广播来自模块进程——另一个 uid）。**只在 worker 线程被调**，
     * registerReceiver 会向 AMS 申请服务，绝不能出现在剪贴板锁内。
     */
    @Volatile private var btRegistered = false
    @Volatile private var lastBtKey: String? = null
    @Volatile private var lastBtAt = 0L
    @Volatile private var wifiRegistered = false
    @Volatile private var downloadRegistered = false

    /** WiFi 边沿检测状态：稳定身份（connected:<ssid> / disconnected）与上次出卡时刻 */
    @Volatile private var lastWifiIdentity: String? = null
    @Volatile private var lastWifiAt = 0L

    /** HookEntry 挂载成功后主动调用：立即注册全部 receiver（不依赖首次复制） */
    fun registerAllReceivers() {
        // 旧实现 sysContext 为空就整体 return（开机时必然为空），wifi/下载监听全丢
        val ctx = sysContext ?: resolveSystemContext()?.also { sysContext = it } ?: return
        worker.execute { ensureLauncherReceiver(ctx) }
    }

    private fun ensureLauncherReceiver(ctx: Context) {
        // MediaStore 兜底监听一起挂上（不依赖剪贴板事件）
        ensureFileWatcher(ctx)
        synchronized(this) {
            registerLaunchReceiver(ctx)
            registerDownloadReceiver(ctx)
            registerWifiReceiver(ctx)
            registerBluetoothReceiver(ctx)
            registerFileSignalReceiver(ctx)
        }
    }

    /**
     * 统一注册入口：API 33+ 显式标记导出，旧版本走旧重载。
     *
     * [broadcastPermission] 非空时由系统在分发前校验发送方权限——这是导出型
     * receiver 唯一可靠的来源门禁（`getSentFromPackage()` 在部分 ROM 上返回
     * null，用它兜底等于「取不到来源就放行」，正是审核指出的问题）。
     */
    private fun registerCompat(
        ctx: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        broadcastPermission: String? = null
    ): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, broadcastPermission, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter, broadcastPermission, null)
            }
            true
        }.getOrDefault(false)

    private fun registerLaunchReceiver(ctx: Context) {
        if (launchRegistered) return
        // 只接受持有本模块签名级权限的发送方（模块自己；system_server 属 core uid 不受组件权限限制）
        if (registerCompat(ctx, launchReceiver, IntentFilter(ACTION_LAUNCH), PERMISSION_ISLAND_COMMAND)) {
            launchRegistered = true
            logI("拉起代发接收器已注册")
        } else {
            logE("拉起接收器注册失败", IllegalStateException("registerReceiver failed"))
        }
    }

    private fun registerDownloadReceiver(ctx: Context) {
        if (downloadRegistered) return
        val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (registerCompat(ctx, downloadReceiver, filter)) {
            downloadRegistered = true
            logI("下载监听接收器已注册")
        } else {
            logE("下载监听注册失败", IllegalStateException("registerReceiver failed"))
        }
    }

    private fun registerWifiReceiver(ctx: Context) {
        if (wifiRegistered) return
        val filter = IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
        if (registerCompat(ctx, wifiReceiver, filter)) {
            wifiRegistered = true
            logI("WiFi 状态接收器已注册")
        } else {
            logE("WiFi 接收器注册失败", IllegalStateException("registerReceiver failed"))
        }
    }

    /** 只认「音频类 profile 已连接」：A2DP / 头戴 / LE Audio，不走 ACL，避免手表手环也弹岛 */
    private fun registerBluetoothReceiver(ctx: Context) {
        if (btRegistered) return
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED")
        }
        if (registerCompat(ctx, btReceiver, filter)) {
            btRegistered = true
            logI("蓝牙连接接收器已注册")
        } else {
            logE("蓝牙接收器注册失败", IllegalStateException("registerReceiver failed"))
        }
    }

    private fun registerFileSignalReceiver(ctx: Context) {
        if (fileSignalRegistered) return
        if (registerCompat(ctx, fileSignalReceiver, IntentFilter(ACTION_FILE_DETECTED))) {
            fileSignalRegistered = true
            logI("智能文件信号接收器已注册")
        } else {
            logE("智能文件信号接收器注册失败", IllegalStateException("registerReceiver failed"))
        }
    }

    /** 下载完成 → APK 弹岛（智能文件；系统进程内直接出卡，查询走防御式 helper） */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                if (!isFileInstallerOn()) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id <= 0L) return
                worker.execute { onDownloadComplete(context, id) }
            }.onFailure { logE("download receiver failed", it) }
        }
    }

    private fun onDownloadComplete(context: Context, id: Long) {
        // 广播回调的 Context 是受限包装，查库与出卡一律用 system 主 Context
        val ctx = sysContext ?: context
        deliverDownloadedApk(ctx, id, fromSystem = true)
    }

    @Volatile private var fileSignalRegistered = false

    /**
     * downloads 进程 → system 的「发现 APK」信号。广播内容不可信：API>=34 校验
     * 发送方包名（getSentFromPackage），免得任意应用伪造把卡弹出来；收到后也不采信
     * 里面的名字/URI，一律按 id 重查下载库并重新校验。
     */
    private val fileSignalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                // ★ 这里**不能**用 getSentFromPackage() 当门禁：它在 Android 15+
                // (API 35 起废弃) 对绝大多数发送方直接返回 null，等于把 downloads
                // 进程发来的真信号也丢掉——7.7 的「智能文件完全不检测」就是这个原因。
                // 信号本身不可信也无所谓：下面一律按 id 重查下载库、重新校验路径与
                // 新鲜度，广播里的名字/URI 只当日志；伪造一条信号最多让一张本来就会
                // 出现的卡晚到，搬不出任何文件（复制仍要过 ApkBridge.isAllowedPath）。
                // 滞留/回放的信号（进程被冻结、系统繁忙）一律当过期丢掉：
                // 用户早就没在下载了，这时弹出来的只能是「之前的」那张幽灵卡
                val sentAt = intent.getLongExtra(EXTRA_FILE_SENT_AT, 0L)
                if (sentAt > 0L && SystemClock.elapsedRealtime() - sentAt > SIGNAL_FRESH_MS) {
                    logI("忽略过期的智能文件信号")
                    return
                }
                val id = intent.getLongExtra(EXTRA_FILE_DOWNLOAD_ID, -1L)
                val name = intent.getStringExtra(EXTRA_FILE_NAME)
                val uri = intent.getStringExtra(EXTRA_FILE_URI)?.let { Uri.parse(it) }
                if (id <= 0L && (name.isNullOrBlank() || uri == null)) return
                worker.execute { onFileSignal(context, id, name, uri) }
            }.onFailure { logE("file signal receiver failed", it) }
        }
    }

    /** system 侧收信号：按 id 重查下载库；只有查库本身失败才退回广播里的 name/uri */
    private fun onFileSignal(context: Context, id: Long, name: String?, uri: Uri?) {
        // 广播回调的 Context 是受限包装，检测器一律用 system 主 Context
        val ctx = sysContext ?: context
        val candidate = if (id > 0L) {
            val queried = runCatching { downloadCandidate(ctx, id) }
            if (queried.isSuccess) queried.getOrNull() else legacyFileCandidate(name, uri)
        } else {
            legacyFileCandidate(name, uri)
        }
        if (candidate == null) {
            logI("智能文件信号未通过校验（#$id）")
            return
        }
        ensureFileWatcher(ctx)
        fileWatcher?.offer(candidate)
    }

    /**
     * 旧版 downloads 进程只发 name+uri（没有 id）：仍然过完整性校验，
     * 新鲜度另加 10 分钟文件 mtime 上限，历史下载照样弹不出来。
     */
    private fun legacyFileCandidate(name: String?, uri: Uri?): ApkCandidate? {
        if (name.isNullOrBlank() || uri == null) return null
        return ApkCandidate(name, uri, 0L, FILE_FRESH_MS, 0L, null, "download")
    }

    /** 蓝牙音频设备连接 → 弹岛（设备名 + 断开/关闭蓝牙/设置） */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                if (!isBluetoothOn()) return
                if (!isMasterEnabled()) return
                // 连接态枚举设备要调蓝牙栈，不能占 system_server 主线程
                worker.execute { onBluetoothState(context, intent) }
            }.onFailure { logE("bluetooth receiver failed", it) }
        }
    }

    /** 蓝牙 profile 状态变更（worker 线程）：连接才出卡，断开只记日志 */
    private fun onBluetoothState(context: Context, intent: Intent) {
        val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
            android.bluetooth.BluetoothDevice.EXTRA_DEVICE
        )
        if (state != 2 || device == null) {
            logI("bluetooth profile state=$state (not connected)")
            return
        }
        val name = runCatching { device.name }.getOrNull()
            ?.replace(BT_TARGET_SEP, BT_NAME_SAFE_SEP)?.trim()
            ?.takeIf { it.isNotBlank() } ?: "蓝牙设备"
        val mac = runCatching { device.address }.getOrNull().orEmpty()
        // 设备刚连上：把 5.7 可能写坏的 A2DP 优先级修回 AUTO_CONNECT，否则媒体音频会留在扬声器
        runCatching {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?.let { adapter -> restoreAudioRouting(context, adapter, device) }
        }.onFailure { logE("bluetooth restore audio routing failed", it) }
        val key = name + "|" + mac
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (key == lastBtKey && now - lastBtAt < BT_DEDUP_MS) return
            lastBtKey = key; lastBtAt = now
        }
        val target = BT_TARGET_PREFIX + name + BT_TARGET_SEP + mac
        logI("bluetooth connected: $name ($mac)")
        outCard(context, KIND_BT_OUT, target)
    }

    private fun isBluetoothOn(): Boolean = runCatching {
        val p = module.getRemotePreferences(PREFS)
        io.github.proify.astrafirmament.settings.ModulePrefs.isBluetoothEnabled(p)
    }.getOrDefault(true)

    /** WiFi 连接/断开弹岛（SSID+网速；断开带查看设置） */
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                if (!isMasterEnabled()) return
                if (!isWifiOn()) return
                // SSID 解析与 su 兜底都在后面：不能占 system_server 主线程
                worker.execute { onWifiState(context) }
            }.onFailure { logE("wifi receiver failed", it) }
        }
    }

    /**
     * WiFi 状态变更（worker 线程）：解析 SSID、测速，再出岛。
     *
     * CONNECTIVITY_ACTION 在部分 ROM 上会被高频重发（同一状态一秒好几条），
     * 旧实现每条都出卡：卡片反复重建、每次都重排收起定时器，旧的前台服务定时器
     * 提前收工 —— 用户看到的就是「卡放着不动，过一会儿按钮全失效」。
     * 这里按**稳定身份**（连上:<ssid> / 断开）做边沿检测：只有状态真的变了才出卡，
     * 另加最小间隔兜底同一状态下的大量重发。
     */
    private fun onWifiState(context: Context) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val ssid = if (connected) resolveWifiSsid(cm) else null
        val identity =
            if (connected) WIFI_CONNECTED_PREFIX + (ssid ?: WIFI_SSID_UNKNOWN) else WIFI_DISCONNECTED
        val now = android.os.SystemClock.elapsedRealtime()
        if (identity == lastWifiIdentity && now - lastWifiAt < WIFI_STATE_MIN_INTERVAL_MS) {
            return
        }
        lastWifiIdentity = identity
        lastWifiAt = now
        // 8.0：target 不再带「实测网速」——那是每次 2s 采样 + 必然会变的字段，
        // 既拖慢出卡，又把去重 / 持久化目标污染成一次性值。按钮只需要 SSID。
        val target = if (connected) {
            val safe = (ssid ?: WIFI_SSID_UNKNOWN).replace(WIFI_TARGET_SEP, WIFI_SSID_SAFE_SEP)
            WIFI_CONNECTED_PREFIX + safe
        } else {
            WIFI_DISCONNECTED
        }
        logI("wifi state: connected=$connected ssid=${ssid ?: "unknown"}")
        // 与其它功能统一走三通道（冷启动 Activity + 定向广播 + startService）：
        // 单通道在模块进程被冻结或受后台限制时出不了卡
        outCard(context, KIND_WIFI_OUT, target)
    }

    /** 当前 SSID：系统 extraInfo 优先；无定位权限时它给 <unknown ssid>，再走 root/配置库 */
    private fun resolveWifiSsid(cm: android.net.ConnectivityManager): String? {
        @Suppress("DEPRECATION")
        val extra = cm.activeNetworkInfo?.extraInfo
        cleanSsid(extra)?.let { return it }
        return currentWifiSsid()
    }

    private fun isWifiOn(): Boolean = runCatching {
        val p = module.getRemotePreferences(PREFS)
        io.github.proify.astrafirmament.settings.ModulePrefs.isWifiEnabled(p)
    }.getOrDefault(true)

    private fun isFileInstallerOn(): Boolean = runCatching {
        val p = module.getRemotePreferences(PREFS)
        p.getBoolean(io.github.proify.astrafirmament.settings.ModulePrefs.KEY_FILE_INSTALLER, true) &&
            io.github.proify.astrafirmament.settings.ModulePrefs.isSmartIslandEnabled(p)
    }.getOrDefault(true)

    /** 模块 → system 的按钮代发：只抽参数，startActivity 甩 worker（防广播超时） */
    private val launchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                // 来源门禁在注册时由系统完成：本 receiver 以签名级自定义权限
                // PERMISSION_ISLAND_COMMAND 注册，非本签名的应用连广播都送不进来，
                // 发送方取不到（getSentFromPackage() 返回 null）也不再是放行理由。
                val kind = intent.getStringExtra(EXTRA_KIND) ?: return
                val target = intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() } ?: return
                if (kind == ApkBridge.KIND_ABORT) {
                    worker.execute { onApkAbort(target) }
                    return
                }
                if (kind == ApkBridge.KIND_READY) {
                    // 副本就绪回执：动作由 App 进程发起，这里只做口令核销 + uri 归属校验后出卡
                    val token = intent.getStringExtra(ApkBridge.EXTRA_TOKEN).orEmpty()
                    worker.execute {
                        onFileReady(
                            context.applicationContext,
                            target,
                            token,
                            intent.getStringExtra(ApkBridge.EXTRA_ORIGIN)
                        )
                    }
                    return
                }
                val pkg = intent.getStringExtra(EXTRA_PKG)
                val ask = intent.getBooleanExtra(EXTRA_ASK, false)
                logI("launch 广播到达 kind=$kind")
                worker.execute { launch(context, kind, target, pkg, ask) }
            }.onFailure { logE("launch receiver failed", it) }
        }
    }

    /** 白名单校验 + 组装 intent + system 身份 startActivity */
    private fun launch(context: Context, kind: String, target: String, pkg: String?, ask: Boolean) {
        val ctx = context.applicationContext
        // 自执行型分支（文件三动作/WiFi 设置/剪贴板/文本分享）：完成即 return，不混入 intent 赋值
        when (kind) {
            KIND_SHARE -> { launchShare(ctx, target); return }
            KIND_DELETE -> { launchDelete(ctx, target); return }
            KIND_FILE -> { launchInstall(ctx, target); return }
            KIND_FILE_VIEW -> { launchFileView(ctx, target); return }
            KIND_WIFI_SETTINGS -> { launchWifiSettings(ctx); return }
            KIND_CLIP -> { launchClip(ctx, target); return }
            KIND_TEXT_SHARE -> { launchTextShare(ctx, target); return }
            KIND_BT_DISCONNECT -> { launchBtDisconnect(ctx, target); return }
            KIND_BT_OFF -> { launchBtOff(ctx); return }
            KIND_BT_SETTINGS -> { launchBtSettings(ctx); return }
        }
        val intent = when (kind) {
            KIND_DIAL -> {
                if (!PHONE_RE.matches(target)) return
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(target)))
            }
            KIND_CONTACTS -> {
                if (!PHONE_RE.matches(target)) return
                Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    this.type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, target)
                }
            }
            KIND_LINK -> {
                if (target.length > 2048) return
                val uri = runCatching { Uri.parse(target) }.getOrNull() ?: return
                val scheme = uri.scheme?.lowercase()
                if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return
                when {
                    ask -> Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "选择浏览器")
                    !pkg.isNullOrBlank() -> Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                    else -> Intent(Intent.ACTION_VIEW, uri)
                }
            }
            else -> return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onSuccess { logI("launch[$kind] ok (system)") }
            .onFailure { logE("launch[$kind] failed", it) }
    }

    /** 分享安装包：系统分享面板 */
    private fun launchShare(ctx: Context, target: String) {
        // 卡片 uri 的 query 里带着本机完整下载路径（QUERY_ORIGIN）——那是给本应用自己
        // 判断「查看是否开原件」用的。分享出去的接收方是任意第三方应用，必须先把 query 摘干净，
        // 否则等于把用户的下载目录结构随安装包一起送出去。
        val uri = targetUri(target)?.buildUpon()?.clearQuery()?.build() ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // EXTRA_STREAM 的读授权必须落在 ClipData 上：部分 ROM 不做 EXTRA_STREAM
            // -> ClipData 的迁移，只靠 flag 时接收方拿到 URI 却读不到内容（分享失败）
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(share, "分享安装包")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess { logI("launch[share] ok") }
            .onFailure { logE("launch[share] failed", it) }
    }

    /** 删除安装包 */
    private fun launchDelete(ctx: Context, target: String) {
        val uri = targetUri(target) ?: return
        runCatching {
            if (uri.scheme == "content") ctx.contentResolver.delete(uri, null, null)
            else java.io.File(uri.path ?: return@runCatching).delete()
        }.onSuccess { logI("launch[delete] ok") }
            .onFailure { logE("launch[delete] failed", it) }
    }

    /** 安装：调用户选定的安装器（三档策略） */
    private fun launchInstall(ctx: Context, target: String) {
        val uri = targetUri(target) ?: return
        // 安装必须给「数据分区上的副本」的 content://：
        // 原始包在 /storage/emulated/0（外部存储），安装器抽取 native lib 时会跨设备拷贝，
        // 真机上直接失败（INSTALL_FAILED_CONTAINER_ERROR, res=-18 = EXDEV）。
        // 副本落在 /data，fd 可正常读取；「查看」才用 file:// 让文件管理器定位原始目录。
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 标记自发起：安装意图拦截据此跳过，避免「点安装→又弹一张卡」
            putExtra(EXTRA_SELF_INSTALL, true)
            clipData = ClipData.newRawUri("apk", uri)
        }
        val prefs = runCatching { module.getRemotePreferences(PREFS) }.getOrNull()
        val mode = prefs?.let { io.github.proify.astrafirmament.settings.ModulePrefs.installerMode(it) }
            ?: io.github.proify.astrafirmament.settings.ModulePrefs.BROWSER_SYSTEM
        val pinned = prefs?.let { io.github.proify.astrafirmament.settings.ModulePrefs.installerPackage(it) }.orEmpty()
        runCatching {
            when {
                mode == io.github.proify.astrafirmament.settings.ModulePrefs.BROWSER_ASK ->
                    ctx.startActivity(Intent.createChooser(install, "选择安装器")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                mode == io.github.proify.astrafirmament.settings.ModulePrefs.BROWSER_PINNED && pinned.isNotBlank() ->
                    ctx.startActivity(install.setPackage(pinned).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else ->
                    ctx.startActivity(install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.onSuccess { logI("launch[install] ok") }
            .onFailure { logE("launch[install] failed", it) }
    }

    /** 跳系统 WiFi 设置页 */
    /**
     * 断开蓝牙音频设备：profile 代理是唯一公开路径（BluetoothDevice 没有公开 disconnect）。
     *
     * 注意：getProfileProxy 的 ServiceListener 回调投递在**主线程**，而本方法就在
     * system_server 主线程执行——用闩同步等回调必然超时（还会卡住 system_server），
     * 所以整条链路改成纯异步：在回调里直接断开，失败再链到下一个 profile。
     */
    private fun launchBtDisconnect(ctx: Context, target: String) {
        val mac = target.substringAfter(BT_TARGET_SEP, "").trim()
        @Suppress("DEPRECATION")
        val adapter = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !adapter.isEnabled) {
            logI("bluetooth disconnect skipped: adapter unavailable")
            return
        }
        val device = if (mac.isEmpty()) null else runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        if (device == null) {
            logE("bluetooth disconnect: bad mac=$mac", IllegalStateException("bad mac"))
            launchBtSettings(ctx)
            return
        }
        disconnectProfileAt(ctx, adapter, device, btDisconnectProfiles(), 0, 0)
    }

    /**
     * 断开设备在所有已连接 profile 上的连接。
     *
     * 只断开「第一个接受了请求」的 profile 不够：A2DP 断开后 HFP 还连着，耳机会
     * 立刻通过另一条 profile 维持连接，用户看到的就是「点了没断开」。这里把设备
     * 连着的 profile 全部断一遍，最后再判断有没有任何一个真的接受了请求。
     */
    private fun disconnectProfileAt(
        ctx: Context,
        adapter: android.bluetooth.BluetoothAdapter,
        device: android.bluetooth.BluetoothDevice,
        profiles: IntArray,
        index: Int,
        accepted: Int
    ) {
        if (index >= profiles.size) {
            if (accepted == 0) {
                logI("bluetooth disconnect: no profile accepted ${device.address}")
                launchBtSettings(ctx)
            } else {
                logI("bluetooth disconnect requested on $accepted profile(s) ${device.address}")
                hardDisconnectDevice(device)
            }
            return
        }
        val profile = profiles[index]
        var advanced = false
        fun next(count: Int) {
            if (advanced) return
            advanced = true
            disconnectProfileAt(ctx, adapter, device, profiles, index + 1, count)
        }
        val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: android.bluetooth.BluetoothProfile?) {
                if (proxy == null) {
                    next(accepted)
                    return
                }
                val connected = runCatching {
                    proxy.connectedDevices?.any { it?.address == device.address } == true
                }.getOrDefault(false)
                val requested = if (!connected) false else runCatching {
                    val method = proxy.javaClass.getMethod(
                        "disconnect", android.bluetooth.BluetoothDevice::class.java
                    )
                    method.invoke(proxy, device) as? Boolean ?: true
                }.onFailure { logE("bluetooth disconnect via profile $profileId failed", it) }
                    .getOrDefault(false)
                runCatching { adapter.closeProfileProxy(profileId, proxy) }
                logI("bluetooth disconnect profile=$profileId connected=$connected requested=$requested")
                next(if (requested) accepted + 1 else accepted)
            }

            override fun onServiceDisconnected(profileId: Int) = next(accepted)
        }
        val bound = runCatching { adapter.getProfileProxy(ctx, listener, profile) }.getOrDefault(false)
        if (!bound) next(accepted)
    }

    /**
     * profile 断开只断了那一条服务：耳机断了 A2DP，HFP 还挂着，音频一恢复又自己连回来
     * （真机日志里 11 秒后重连 J08）。设备级 ACL 断开才是用户理解的「断开连接」，
     * 公开 API 没有这个方法，只能反射隐藏实现，存在与否因 ROM 而异。
     */
    private fun hardDisconnectDevice(device: android.bluetooth.BluetoothDevice) {
        val ok = runCatching {
            val method = device.javaClass.getMethod("disconnect")
            method.invoke(device) as? Boolean ?: true
        }.onFailure { logE("bluetooth hard disconnect failed", it) }.getOrDefault(false)
        logI("bluetooth hard disconnect -> $ok")
    }

    /**
     * 把设备在某个 profile 上的优先级改回 PRIORITY_AUTO_CONNECT(1000)。
     *
     * 背景（5.7 的血案）：5.7 在断开前调用 setPriority(device, 0) 想压制自动重连，
     * 但 PRIORITY_OFF 在系统里是「用户禁用该 profile」的持久语义——A2DP 被禁用后，
     * 设备重连只连上 HFP，媒体音频就永远留在扬声器上，用户怎么重连都没用。
     * 5.8 起断开流程绝不碰优先级，并且对已被写坏（=0）的设备做一次回补。
     * 隐藏 API，各 ROM 差异大：拿不到方法就当没做成，不影响其它逻辑。
     */
    private fun setProfilePriority(
        proxy: android.bluetooth.BluetoothProfile,
        device: android.bluetooth.BluetoothDevice,
        profileId: Int,
        priority: Int
    ): Boolean = runCatching {
        val method = proxy.javaClass.getMethod(
            "setPriority",
            android.bluetooth.BluetoothDevice::class.java,
            Int::class.javaPrimitiveType
        )
        method.invoke(proxy, device, priority) as? Boolean ?: true
    }.onFailure { logE("bluetooth setPriority $priority failed profile=$profileId", it) }
        .getOrDefault(false)

    /**
     * 音频路由自愈：把 A2DP 的优先级恢复成 AUTO_CONNECT，没连上就主动连一次。
     *
     * 只在「设备刚连上」时跑（那时音频还没真正开始），用户观感就是耳机正常出声；
     * 对从没被写坏过的设备，这次调用等价于「保持自动连接」，无副作用。
     */
    private fun restoreAudioRouting(ctx: Context, adapter: android.bluetooth.BluetoothAdapter, device: android.bluetooth.BluetoothDevice) {
        val bound = runCatching {
            adapter.getProfileProxy(ctx, object : android.bluetooth.BluetoothProfile.ServiceListener {
                private var done = false
                override fun onServiceConnected(profileId: Int, proxy: android.bluetooth.BluetoothProfile?) {
                    if (done || proxy == null) return
                    done = true
                    fixA2dp(adapter, proxy, device)
                }

                override fun onServiceDisconnected(profileId: Int) = Unit
            }, android.bluetooth.BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!bound) logI("bluetooth restore audio routing：A2DP proxy 绑定失败")
    }

    private fun fixA2dp(
        adapter: android.bluetooth.BluetoothAdapter,
        proxy: android.bluetooth.BluetoothProfile,
        device: android.bluetooth.BluetoothDevice
    ) {
        val restored = setProfilePriority(proxy, device, android.bluetooth.BluetoothProfile.A2DP, PRIORITY_AUTO_CONNECT)
        val connected = runCatching {
            proxy.connectedDevices?.any { it?.address == device.address } == true
        }.getOrDefault(false)
        val reconnected = if (connected) false else runCatching {
            val method = proxy.javaClass.getMethod("connect", android.bluetooth.BluetoothDevice::class.java)
            method.invoke(proxy, device) as? Boolean ?: true
        }.onFailure { logE("bluetooth A2DP reconnect failed", it) }.getOrDefault(false)
        logI("bluetooth audio routing restored=$restored connected=$connected reconnect=$reconnected ${device.address}")
        runCatching { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, proxy) }
    }

    /** 断开候选 profile：A2DP / 头戴 / LE Audio（按「音频输出最可能连的那个」排序） */
    private fun btDisconnectProfiles(): IntArray = if (android.os.Build.VERSION.SDK_INT >= 33) {
        intArrayOf(
            android.bluetooth.BluetoothProfile.A2DP,
            android.bluetooth.BluetoothProfile.HEADSET,
            BT_PROFILE_LE_AUDIO
        )
    } else {
        intArrayOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)
    }

    /** 关闭蓝牙（system 身份调适配器，普通应用会被权限/策略拦） */
    private fun launchBtOff(ctx: Context) {
        runCatching {
            @Suppress("DEPRECATION")
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            val off = adapter?.disable() ?: false
            logI("bluetooth disable -> $off")
        }.onFailure { logE("bluetooth disable failed", it) }
    }

    private fun launchBtSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { logE("open bluetooth settings failed", it) }
    }

    private fun launchWifiSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess { logI("launch[wifi_settings] ok") }
            .onFailure { logE("launch[wifi_settings] failed", it) }
    }

    /** 代写剪贴板：模块进程在后台，Android 10+ 自己写会被 appops 静默丢掉 */
    private fun launchClip(ctx: Context, text: String) {
        runCatching {
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("xinghe", text))
        }.onSuccess { logI("launch[clip] ok") }
            .onFailure { logE("launch[clip] failed", it) }
    }

    /** 代发文本分享面板：模块后台 startActivity 会被 BAL 拦，system 身份才放行 */
    private fun launchTextShare(ctx: Context, text: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(share, "分享 WiFi 密码")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess { logI("launch[text_share] ok") }
            .onFailure { logE("launch[text_share] failed", it) }
    }

    /** target 格式 apk:<文件名>|<uri> 的 URI 段解析 */
    /**
     * 「查看」：用用户选定的文件管理器（或系统选择器）打开这份安装包。
     *
     * 为什么先给 file:// 原始路径：文件管理器要靠它「定位到文件」，而 content:// 只让它们
     * 读到字节、无法定位。file:// 在某些 ROM 会触发 FileUriExposedException，所以失败时
     * 退回我方 Provider 的 content:// + 读授权——两条路都不通再记日志放弃。
     */
    /**
     * 「查看」交给用户自己挑应用（系统面板）。给的是**原始路径** file://：
     * 目标应用自己去读，才能定位到用户真实的下载目录；给 content:// 时它会打开我方
     * 私有副本目录，用户看到的是一堆删不掉的中间文件（5.9 的真机反馈）。
     */
    private fun launchFileView(ctx: Context, target: String) {
        val uri = targetUri(target) ?: return
        val origin = originFileOf(uri)
        val data = origin?.let { Uri.fromFile(it) } ?: uri
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(data, ApkBridge.MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(EXTRA_SELF_INSTALL, true)
        if (origin == null) view.clipData = ClipData.newRawUri("apk", uri)
        val sent = runCatching {
            ctx.startActivity(
                Intent.createChooser(view, "打开安装包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure { logE("查看安装包失败", it) }.getOrDefault(false)
        logI("查看安装包：sent=$sent origin=${origin?.absolutePath ?: "-"}")
    }

    /** 卡片 target 里带的原始路径：优先给安装器/文件管理器用真实文件，避免「私有副本」观感 */
    private fun originFileOf(uri: Uri): java.io.File? = runCatching {
        val origin = uri.getQueryParameter(ApkBridge.QUERY_ORIGIN) ?: return null
        if (!ApkBridge.isAllowedPath(origin)) return null
        java.io.File(origin).takeIf { it.isFile }
    }.getOrNull()

    private fun targetUri(target: String): Uri? {
        val uriPart = target.substringAfter('|', "")
        if (uriPart.isBlank()) return null
        return runCatching { Uri.parse(uriPart) }.getOrNull()
    }

    private fun isMasterEnabled(): Boolean = runCatching {
        val p = module.getRemotePreferences(PREFS)
        p.getBoolean(ModulePrefs.KEY_ENABLED, true) && ModulePrefs.isSmartIslandEnabled(p)
    }.getOrDefault(false)

    private fun resolveContext(service: Any?): Context? {
        var cls: Class<*>? = service?.javaClass
        while (cls != null) {
            val found = runCatching { cls!!.getDeclaredField("mContext").also { it.isAccessible = true }.get(service) as? Context }.getOrNull()
            if (found != null) return found
            cls = cls.superclass
        }
        return null
    }

    /**
     * 链路日志：一律走 [ModuleLogger]——它统一做两件事：
     *  1) 应用内「开发者日志」关掉时**连 module.log 都不写**（审核点：日志常开、关不掉）；
     *  2) 写之前过 [io.github.proify.astrafirmament.shared.Redact]，抹掉路径/网址/
     *     SSID/蓝牙名与 MAC/歌名歌手。本类原先直接调 module.log，两条都没走。
     */
    private val logger: ModuleLogger by lazy { ModuleLogger(module) }

    private fun logI(msg: String) {
        logger.info(msg)
    }

    private fun logE(msg: String, t: Throwable) {
        logger.error(msg, t)
    }

    private fun logW(msg: String) {
        logger.warn(msg)
    }

    companion object {
        /** 版本标记：system_server 常驻旧 dex，靠这行日志判断到底加载了哪一版 */
        /**
         * 版本标记：system_server 常驻旧 dex，靠这行日志判断到底加载了哪一版。
         * 直接取 [io.github.proify.astrafirmament.lyric.Constants.MODULE_VERSION]
         * （BuildConfig 在本工程未开启，且 docs/checks.py 会校验该常量与 version.properties 一致）。
         */
        private val BUILD_TAG = "link-hook " + io.github.proify.astrafirmament.lyric.Constants.MODULE_VERSION
        private const val PREFS = ModulePrefs.NAME
        private const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
        const val MODULE_PACKAGE = "io.github.proify.astrafirmament"

        /**
         * 静态工具函数的日志出口。
         *
         * [logI]/[logW] 是实例方法（需要 [ModuleLogger] 与 [XposedModule]），companion 里的
         * `currentWifiSsid()` 一类静态解析函数拿不到实例，7.6 时它们直接写 `Log.i(TAG, ...)`——
         * 那条路径**绕过了「开发者日志」开关**。这里改成由 [install] 注入同一份 logger：
         * 模块进程（App 侧调用 SSID 解析）没有注入时静默，绝不绕开开关去写框架日志。
         */
        @Volatile private var staticLogger: ModuleLogger? = null

        /** 静态上下文记日志（受开关与脱敏约束，见 [staticLogger]） */
        private fun logStatic(msg: String) {
            staticLogger?.info(msg)
        }

        /**
         * 模块 ↔ system 之间的签名级自定义权限。
         *
         * 导出的广播接收器（manifest 里的 IslandDeliverReceiver / ApkDiscoveryReceiver、
         * system_server 里动态注册的 launchReceiver）都以它做门禁：只有本签名应用
         * 能投递，取不到发送方也不再放行。system_server 属 core uid，不受组件权限限制。
         */
        const val PERMISSION_ISLAND_COMMAND = "io.github.proify.astrafirmament.permission.ISLAND_COMMAND"

        /** 投递 kind（system → 模块）与 IslandService/LinkHub 对齐 */
        const val KIND_PHONE_OUT = "phone"
        const val KIND_LINK_OUT = "link"

        /** 代发 kind（模块 → system）与 LinkHub.sendLaunch 对齐 */
        const val KIND_DIAL = "dial"
        const val KIND_FILE = "file"
        const val KIND_FILE_VIEW = "file_view"
        const val KIND_SHARE = "share"
        const val KIND_DELETE = "delete"
        const val KIND_WIFI_SETTINGS = "wifi_settings"
        /** 剪贴板/文本分享也由 system 代做：模块进程在后台会被 appops / BAL 拦 */
        const val KIND_CLIP = "clip"
        const val KIND_TEXT_SHARE = "text_share"
        /** 蓝牙岛动作（模块 → system 代做）：均由 system 身份调蓝牙栈 */
        const val KIND_BT_DISCONNECT = "bt_disconnect"
        const val KIND_BT_OFF = "bt_off"
        const val KIND_BT_SETTINGS = "bt_settings"
        /** 投递 kind（system → 模块） */
        const val KIND_WIFI_OUT = "wifi"
        const val KIND_BT_OUT = "bt"
        const val KIND_FILE_OUT = "file"
        const val KIND_CONTACTS = "contacts"
        const val KIND_LINK = "link"

        /** 广播协议（与 LinkHub 常量逐字对齐） */
        const val ACTION_LAUNCH = "io.github.proify.astrafirmament.LAUNCH"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TARGET = "xinghe_target"
        const val EXTRA_PKG = "xinghe_pkg"
        const val EXTRA_ASK = "xinghe_ask"

        private const val DEDUP_MS = 1_000L
        private const val DOWNLOAD_PROVIDER = "com.android.providers.downloads.DownloadProvider"
        private const val TAG = "AstraFirmament"

        // ---------------- 智能文件（安装包） ----------------

        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val APK_SUFFIX = ".apk"

        /** 卡片「安装」自发起标记：安装意图拦截见到它就跳过（防空转回环） */
        private const val EXTRA_SELF_INSTALL = "xinghe_self_install"

        /** 安装意图入口的锁内去重窗口（同一次点击可能经过多个入口） */
        private const val INTENT_DEDUP_MS = 2_000L

        /** 三条路径共享的同一 APK 去重窗口 */
        private const val FILE_DEDUP_MS = 10 * 60 * 1_000L

        /**
         * 下载完成路径的新鲜度上限：文件 mtime 比这更早就是历史下载（幽灵卡）。
         * 用文件自己的写入时刻判断，而不是下载记录的 last_modified —— 后者会被
         * 厂商 ROM / 二次扫描重写，历史下载因此「变新」。
         */
        private const val FILE_FRESH_MS = 10 * 60 * 1_000L

        /**
         * 跨进程信号的滞留容忍：广播从 downloads 进程到这里正常 <1s，
         * 超过这个数说明信号被冻结/积压过，一律当过期丢弃。
         */
        private const val SIGNAL_FRESH_MS = 30_000L

        /** 单次 update 最多验证的下载 id（防御式解析可能捞出脏值） */
        private const val MAX_DOWNLOAD_IDS = 8

        /** 「正在下载」关注表容量与寿命：只为了给完成事件兜底，不参与出卡逻辑 */
        private const val MAX_FOLLOWED_DOWNLOADS = 16
        private const val FOLLOW_DOWNLOAD_TTL_MS = 2 * 60 * 60 * 1_000L

        /** 候选 mtime 允许多「未来」（时钟偏差/厂商 ROM 重写），超出即当脏数据 */
        private const val FILE_FUTURE_SKEW_MS = 10 * 60 * 1_000L

        /**
         * APK 重投计划：浏览器写大文件时只有「建文件」那一刻有事件，写完不保证再发事件，
         * 所以由系统侧按递增间隔补投，直到 App 回执出卡为止（合计约 6 分钟）。
         */
        /**
         * 智能文件重投间隔：第一次几乎立刻重试（大量场景是「文件刚写完」），
         * 之后才指数退避。7.11 从 6s 起步，用户感知是「要等半分钟才上岛」。
         */
        private val APK_RETRY_DELAYS_MS = longArrayOf(
            800L, 1_500L, 3_000L, 6_000L, 12_000L, 30_000L, 60_000L
        )

        /** 出卡回执口令的有效期与并发上限：够一轮 su 复制，又不会长期可重放 */
        private const val APK_TOKEN_TTL_MS = 180_000L

        /** 同一路径出过卡后的静默期：媒体库重扫/重投再报都当作重复事件 */
        private const val APK_DELIVERED_QUIET_MS = 30 * 60 * 1_000L
        private const val APK_TOKEN_MAX = 24

        /** downloads 进程 → system 的「发现 APK」内部广播（跨进程汇聚到同一去重表） */
        private const val ACTION_FILE_DETECTED = "io.github.proify.astrafirmament.FILE_DETECTED"
        private const val EXTRA_FILE_NAME = "xinghe_file_name"
        private const val EXTRA_FILE_URI = "xinghe_file_uri"

        /**
         * 「文件已就绪」字段：接收方按 id 重查下载库，不信广播里的名字/URI；
         * SENT_AT 是发送时刻（elapsedRealtime），用于丢弃滞留/回放的信号。
         * 均为本类内部协议，LinkHub 不消费。
         */
        private const val EXTRA_FILE_DOWNLOAD_ID = "xinghe_file_download_id"
        private const val EXTRA_FILE_SENT_AT = "xinghe_file_sent_at"

        /**
         * 安装动作在 system_server 的入口类（本机 ColorOS 16 实测存在）。
         * 名字按 [INSTALL_ENTRY_NAMES] 过滤 + 参数含 Intent 才挂；
         * PackageInstallerService 没有 createSession（实测），不列。
         */
        private val INSTALL_HOOK_CLASSES = arrayOf(
            "com.android.server.wm.ActivityTaskManagerService",
            "com.android.server.wm.ActivityStarter",
            "com.android.server.wm.ActivityTaskManagerService\$LocalService"
        )

        private val INSTALL_ENTRY_NAMES = setOf(
            "startActivity",
            "startActivityAsUser",
            "startActivityAsCaller",
            "startActivityWithConfig",
            "startActivityAndWait",
            "startActivityFromGameSession",
            "executeRequest",
            "notifyStartActivity",
            "notifyStartActivityAsUser",
            "notifyStartActivityInPackage"
        )

        /** WiFi 卡 target 协议（与 LinkHub 解析逐字对齐） */
        const val WIFI_CONNECTED_PREFIX = "connected:"
        const val WIFI_DISCONNECTED = "disconnected"
        const val WIFI_TARGET_SEP = " | "
        /** SSID 自带分隔符时换成安全字符，否则按钮端 split 会切错 */
        private const val WIFI_SSID_SAFE_SEP = " · "
        /** 解析不到 SSID 的占位：绝不能把字面量 "WiFi" 冒充 SSID 传给按钮 */
        const val WIFI_SSID_UNKNOWN = "?"
        /**
         * 同一 WiFi 状态的最小出卡间隔：CONNECTIVITY_ACTION 在部分 ROM 上高频重发，
         * 每次重发都出卡会让卡片反复重建、前台服务定时器互相踩，最终表现成
         * 「卡放着不动，过一会儿按钮失效」。状态真的变了（身份不同）不受此限。
         */
        private const val WIFI_STATE_MIN_INTERVAL_MS = 5_000L
        private const val WIFI_CONFIG_STORE = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"
        /** 蓝牙卡 target 协议（与 LinkHub 解析逐字对齐）：bt:<设备名>|<MAC> */
        const val BT_TARGET_PREFIX = "bt:"
        const val BT_TARGET_SEP = "|"
        const val BT_PROFILE_LE_AUDIO = 22
        /** 设备名自带分隔符时替换，避免按钮端 split 切错 */
        private const val BT_NAME_SAFE_SEP = " · "
        /** 同一设备重复上报的去重窗口（连接态会连发多条 profile 回调） */
        /** BluetoothProfile.PRIORITY_AUTO_CONNECT：恢复「自动连接」，音频才会回到耳机 */
    private const val PRIORITY_AUTO_CONNECT = 1000
    private const val BT_DEDUP_MS = 8_000L

        /** 手机号白名单：与 LinkPatterns.firstPhone 的产出兼容 */
        val PHONE_RE = Regex("""^[+0-9][0-9+\-()\s*]{0,31}$""")

        // ---------------- WiFi SSID 解析（system 侧才算权威，模块侧借用） ----------------

        /**
         * 当前连接 SSID（多路兜底，任一成功即返回）：从「最贴近 Wifi 服务状态」
         * 排到「最兜底的历史配置」。system_server 里 su 不一定可用（PATH/授权差异），
         * 所以每路独立尝试、失败只记日志，全失败返回 null 交给调用方降级。
         */
        fun currentWifiSsid(): String? {
            rootRead("cmd wifi status", 4_000L)?.let { out ->
                ssidFromWifiStatus(out)?.let { logSsid("cmd wifi status", it); return it }
            }
            rootRead("dumpsys wifi", 6_000L)?.let { out ->
                ssidFromDumpsys(out)?.let { logSsid("dumpsys wifi", it); return it }
            }
            rootRead("iw dev wlan0 link", 3_000L)?.let { out ->
                ssidFromIwLink(out)?.let { logSsid("iw link", it); return it }
            }
            rootRead("cat $WIFI_CONFIG_STORE", 6_000L)?.let { out ->
                ssidFromConfigStore(out)?.let { logSsid("config store", it); return it }
            }
            logStatic("wifi ssid unresolved: root 不可用或输出格式不识别")
            return null
        }

        /** 只读 root 命令；无 root / 超时 → null（失败不抛，继续下一路） */
        private fun rootRead(cmd: String, timeoutMs: Long): String? =
            runCatching { SuReader.read(cmd, timeoutMs = timeoutMs) }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun logSsid(source: String, ssid: String) {
            logStatic("wifi ssid via $source: $ssid")
        }

        /** `cmd wifi status`：`Wifi is connected to "X"` 与 `WifiInfo: SSID: "X"` */
        private fun ssidFromWifiStatus(out: String): String? =
            ssidFromLine(out.lineSequence().firstOrNull { it.contains("SSID:") })
                ?: CONNECTED_TO_RE.find(out)?.let { cleanSsid(it.groupValues[1]) }

        /** `dumpsys wifi`：活动连接在 mWifiInfo 那条；扫描结果里认 Currently Connected */
        private fun ssidFromDumpsys(out: String): String? {
            val lines = out.lines()
            lines.firstOrNull { it.contains("mWifiInfo SSID") }?.let { line ->
                ssidFromLine(line)?.let { return it }
            }
            lines.firstOrNull { CURRENTLY_CONNECTED_RE.containsMatchIn(it) && SSID_FIELD_RE.containsMatchIn(it) }
                ?.let { line -> ssidFromLine(line)?.let { return it } }
            return null
        }

        /** `iw dev wlan0 link`：`SSID: X`（无引号，最干净的一路） */
        private fun ssidFromIwLink(out: String): String? {
            if (!out.contains("Connected to")) return null
            return ssidFromLine(out.lineSequence().firstOrNull { it.trimStart().startsWith("SSID:") })
        }

        /**
         * 最后兜底：配置库里 IsMostRecentlyConnected=true 的块。不依赖 Wifi 服务查询，
         * 只有「root 能读文件」这条路可走时也能给出正确网络（该标记全局唯一）。
         */
        private fun ssidFromConfigStore(xml: String): String? {
            for (block in xml.split("<WifiConfiguration>")) {
                if (!MOST_RECENT_RE.containsMatchIn(block)) continue
                SSID_XML_RE.find(block)?.let { return cleanSsid(unescapeSsid(it.groupValues[1])) }
                // 少数 ROM 无独立 SSID 字段，ConfigKey 形如 "SSID"WPA_PSK
                CONFIG_KEY_XML_RE.find(block)?.let { m ->
                    XML_QUOTED_RE.find(unescapeSsid(m.groupValues[1]))
                        ?.let { return cleanSsid(it.groupValues[1]) }
                }
            }
            return null
        }

        /** 从一行里取 SSID（兼容 `SSID: "x"` / `SSID: <x>` / `SSID: x`） */
        private fun ssidFromLine(line: String?): String? {
            val m = line?.let { SSID_FIELD_RE.find(it) } ?: return null
            val value = (1..3).firstNotNullOfOrNull { i ->
                m.groupValues.getOrNull(i)?.takeIf { it.isNotBlank() }
            }
            return cleanSsid(value)
        }

        /** 过滤占位值：无定位权限时系统给 <unknown ssid>，隐藏网络给 0x */
        fun cleanSsid(raw: String?): String? {
            val s = raw?.trim()?.trim('"')?.trim() ?: return null
            if (s.isEmpty() || s == "0x") return null
            if (s.equals("<unknown ssid>", true) || s.equals("unknown ssid", true)) return null
            return s
        }

        /** 只解 SSID 会用到的实体（&amp; 必须最后，防二次转义） */
        private fun unescapeSsid(s: String): String = s
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        private val SSID_FIELD_RE = Regex("""\bSSID:\s*(?:"([^"]*)"|<([^>]*)>|([^,;\r\n]*))""")
        private val CONNECTED_TO_RE = Regex("""connected to "([^"]+)"""")
        private val CURRENTLY_CONNECTED_RE = Regex("""Currently Connected:\s*true""")
        private val SSID_XML_RE = Regex("""<string name="SSID">([^<]*)</string>""")
        private val CONFIG_KEY_XML_RE = Regex("""<string name="ConfigKey">([^<]*)</string>""")
        private val MOST_RECENT_RE = Regex("""<boolean name="IsMostRecentlyConnected" value="true"""")
        private val XML_QUOTED_RE = Regex("\"([^\"]*)\"")
    }
}
