package io.github.proify.lyricon.xinghe.capability.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.astraisland.protocol.ActivityBundle
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 链接助手 · 岛上卡片枢纽（App 进程内唯一出口）。
 *
 * 两个关键设计，针对墓碑机制（App 不在后台会被立刻冻结）：
 *
 *  1. 「点卡片空白」= PendingIntent 直达浏览器（不是中转页）。
 *     IslandClient 的 openIntent 由岛（systemui）send()，跳过我们进程，
 *     墓碑冻不冻都无所谓，点下去就能唤起浏览器。
 *
 *  2. 「打开」按钮回调进不来（进程被冻住）。补救措施是投递时先把
 *     链接落盘 + 岛就绪时补投：即使进程死透了，下次投递把进程拉起来、
 *     或岛主动广播「岛就绪」时，能把暂存的链接重新挂上去。
 *
 *  Provider 同步等待（call() 不立刻返回）也让进程在投递期间的优先级
 *  保持较高，进一步压缩墓碑窗口。
 */
object LinkHub {

    private const val TAG = "XingHe-LinkHub"

    /** 卡片 id：同一张卡反复投递即覆盖，不会在岛上堆叠 */
    private const val CARD_ID = "xinghe-link"
    private const val ACTION_OPEN = "open"
    private const val ACTION_DISMISS = "dismiss"

    private const val NOTIF_CHANNEL = "xinghe_link"
    private const val NOTIF_ID = 0x7E17

    /** 岛未就绪时暂存链接的重试节奏：先密后疏，总共约 10 秒 */
    private val RETRY_DELAYS_MS = longArrayOf(300L, 500L, 800L, 1200L, 1800L, 2500L, 3000L)

    /** 落盘的暂存链接最多保留这么久，超时不补（避免陈旧链接突然弹出） */
    private const val STALE_MS = 120_000L

    /** Provider 同步等待上岛的超时（Binder 事务期间进程的优先级是高的） */
    private const val AWAIT_TIMEOUT_MS = 5_000L

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-hub").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var client: IslandV7Client? = null
    @Volatile private var appContext: Context? = null

    /** 暂存的待投链接（URL），供重连后补投 */
    @Volatile private var pendingUrl: String? = null
    @Volatile private var pendingAt = 0L
    @Volatile private var retryIndex = 0

    /** 当前卡片上显示的 URL，点「打开」时用它跳 */
    @Volatile private var currentUrl: String? = null

    /**
     * Provider 投递：先把链接落盘，再同步等待到投送完成再返回。
     *
     * 顺序很重要：先落盘后投送 —— 如果进程在投送途中被墓碑冻死，
     * 至少链接还在偏好里；下次 ready/投递/启动时都能从 pendingUrl 补。
     *
     * @return true 表示已受理并投出（或已被限流丢弃）
     */
    fun postBlocking(app: Context, url: String, timeoutMs: Long = AWAIT_TIMEOUT_MS): Boolean {
        appContext = app
        pendingUrl = url
        pendingAt = System.currentTimeMillis()
        retryIndex = 0

        val latch = CountDownLatch(1)
        val result = AtomicInteger(-1)
        worker.execute {
            result.set(tryShow(url))
            latch.countDown()
        }
        return runCatching {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result.get() == 0 || result.get() == -1   // 0=OK；-1=已尽力排上队列
        }.getOrDefault(false)
    }

    /** App 启动时预连（可选，减少第一次投送的等待） */
    fun warmUp(app: Context) {
        appContext = app
        worker.execute { ensureClient() }
    }

    /** 设置页查询连接状态用 */
    fun isReady(): Boolean = client?.isReady == true
    fun stateName(): String = client?.state?.name ?: "未连接"
    fun protocolVersion(): Int = client?.islandProtocolVersion ?: 0

    /** 用户点了「忽略」或卡片生命周期结束时清掉 */
    fun dismiss() {
        worker.execute {
            runCatching { client?.end(CARD_ID) }
            currentUrl = null
        }
    }

    // ---------------- 内部实现 ----------------

    /** 建岛客户端（只在 App 进程，幂等；协议 v7，见 [IslandV7Client]） */
    private fun ensureClient(): IslandV7Client? {
        client?.let { return it }
        val ctx = appContext ?: return null
        return synchronized(this) {
            client ?: runCatching {
                IslandV7Client(ctx) { activityId, actionId ->
                    onAction(activityId, actionId)
                }.apply {
                    onReadyChanged = { ready ->
                        Log.i(TAG, "Island ready=$ready state=$state")
                        if (ready) main.post { flushPending() }
                    }
                    connect()
                }
            }.onFailure { Log.e(TAG, "IslandV7Client create failed", it) }
                .getOrNull()
                .also { client = it }
        }
    }

    /** 尝试投卡；不成功则排重试 */
    private fun tryShow(url: String): Int {
        val island = ensureClient() ?: run {
            Log.w(TAG, "IslandClient 不可用，稍后重试")
            scheduleRetry(url)
            return -1
        }
        if (!island.isReady) {
            runCatching { island.connect() }
            scheduleRetry(url)
            return -1
        }
        val result = runCatching { island.start(encodeCard(url)) }
            .onFailure { Log.e(TAG, "start failed", it) }
            .getOrDefault(-1)
        Log.i(TAG, "start result=$result")
        when (result) {
            0 -> {                      // RESULT_OK
                currentUrl = url
                pendingUrl = null
                retryIndex = 0
            }
            9 -> {                      // RESULT_NOT_CONNECTED：会话断了，重连后重投
                runCatching { island.connect() }
                scheduleRetry(url)
            }
            else -> {                   // 配额/限流/权限等，不再重试
                pendingUrl = null
                retryIndex = 0
            }
        }
        return result
    }

    private fun scheduleRetry(url: String) {
        if (retryIndex >= RETRY_DELAYS_MS.size) {
            Log.w(TAG, "放弃投递链接（重试${retryIndex}次仍未连上）")
            retryIndex = 0
            return
        }
        val delay = RETRY_DELAYS_MS[retryIndex++]
        worker.execute {
            Thread.sleep(delay)
            if (pendingUrl == url) tryShow(url)
        }
    }

    /** 岛就绪回调触发时补投暂存的链接（纯内存处理，不写本地存储） */
    private fun flushPending() {
        val url = pendingUrl ?: return
        if (System.currentTimeMillis() - pendingAt > STALE_MS) {
            pendingUrl = null
            return
        }
        worker.execute { tryShow(url) }
    }

    private fun onAction(activityId: String, actionId: String) {
        if (activityId != CARD_ID) return
        when (actionId) {
            ACTION_OPEN -> {
                val url = currentUrl ?: return
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    Log.w(TAG, "Rejected non-http/https URL in onAction")
                    dismiss()
                    return
                }
                // 按钮回调：进程被墓碑冻住时这步进不来。走中转页，
                // 起不来就用通知兜底（通知 PendingIntent 由 systemui 发出，
                // 不受墓碑影响）。
                main.post {
                    val ctx = appContext ?: return@post
                    val ok = runCatching {
                        ctx.startActivity(
                            Intent(ctx, LinkOpenActivity::class.java)
                                .setAction(Intent.ACTION_VIEW)
                                .setData(Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    }.getOrDefault(false)
                    if (!ok) postFallbackNotification(ctx, url)
                    dismiss()
                }
            }
            ACTION_DISMISS -> {
                Log.i(TAG, "用户点了忽略")
                dismiss()
            }
        }
    }

    /**
     * 「点卡片空白就打开链接」的 PendingIntent：直接指向浏览器，不经过中转页。
     *
     * 墓碑环境下 App 进程一被冻结就收不到回调，唯一能稳定唤起浏览器的方式
     * 是让岛（systemui，有权限、不冻）直接 send() 一个指向浏览器的 PendingIntent。
     * 用户的浏览器偏好（跟随系统/固定某个）在编码时就写死进 PendingIntent。
     */
    private fun openIntentFor(url: String): PendingIntent? {
        val ctx = appContext ?: return null
        val prefs = ModulePrefs.of(ctx)
        val uri = Uri.parse(url)

        val target = when (ModulePrefs.browserMode(prefs)) {
            ModulePrefs.BROWSER_PINNED -> {
                val pkg = ModulePrefs.browserPackage(prefs)
                if (pkg.isNotBlank()) Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                else Intent(Intent.ACTION_VIEW, uri)
            }
            ModulePrefs.BROWSER_ASK -> {
                // 每次询问必须走 chooser，chooser 需要一个 context 才能创建，
                // 所以这条不能直接交给岛，退回中转页模式
                Intent(ctx, LinkOpenActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(uri)
            }
            else -> Intent(Intent.ACTION_VIEW, uri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            PendingIntent.getActivity(
                ctx, url.hashCode(), target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.onFailure { Log.w(TAG, "openIntent build failed: ${it.message}") }
            .getOrNull()
    }

    /** 编一张卡片 */
    private fun encodeCard(url: String): Bundle {
        val host = LinkPatterns.hostOf(url)
        return ActivityBundle.encodeActivity(
            id = CARD_ID,
            kind = "MESSAGE",
            compactLeading = ActivityBundle.encodeSlot(
                "icon",
                icon = ActivityBundle.encodeIcon("builtin", builtin = "NAVIGATION")
            ),
            compactTrailing = ActivityBundle.encodeSlot("text", text = "链接"),
            expanded = ActivityBundle.encodeExpanded(
                template = "MESSAGE",
                title = "发现一个链接",
                subtitle = host,
                body = url,
                actions = arrayListOf(
                    ActivityBundle.encodeAction(ACTION_OPEN, "打开", primary = true),
                    ActivityBundle.encodeAction(ACTION_DISMISS, "忽略")
                )
            ),
            openIntent = openIntentFor(url),
            hideWhenSourceForeground = false,
            alertOnStart = true,
            alertOnUpdate = false
        )
    }

    /** 兜底：直接 startActivity 被拦时发通知（通知 PendingIntent 由 systemui 发，不受墓碑影响） */
    private fun postFallbackNotification(ctx: Context, url: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "链接助手", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "复制到链接后在这里打开" }
            )
            val pi = PendingIntent.getActivity(
                ctx, url.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("打开链接")
                .setContentText(LinkPatterns.hostOf(url))
                .setStyle(Notification.BigTextStyle().bigText(url))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
            Log.i(TAG, "fallback notification posted")
        }.onFailure { Log.w(TAG, "postFallbackNotification failed: ${it.message}") }
    }


}
