package io.github.proify.astrafirmament.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import io.github.proify.astrafirmament.capability.link.DevLog
import io.github.proify.astrafirmament.capability.link.LinkHub
import io.github.proify.astrafirmament.hook.LinkHook
import java.util.concurrent.atomic.AtomicInteger

/**
 * 智能上岛的宿主服务（模块进程）。
 *
 * 进程模型：system 侧检测命中后显式 startService 拉活本进程，本服务负责
 * startForeground 保活 → 出卡 → 收按钮回调。
 *
 * 保活模型：看门狗只回答「手上还有活吗」——pendingShows>0 或卡片仍存活就
 * 每秒复查；两者归零才 stopSelf。重复投递只是把看门狗多续一秒。
 *
 * FGS 类型：manifest 声明的是 shortService（0x800），这里必须传同一个值。
 * 7.11 传了 SPECIAL_USE（0x40000000），类型校验直接抛异常 → FGS 从未建立 →
 * 卡片期间进程是 cached → 按钮回调无人接收。见 8.0 交接文档。
 */
class IslandService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 正在处理中的出卡请求数（worker 线程回调递减，故用原子量）。 */
    private val pendingShows = AtomicInteger(0)

    /** 看门狗累计复查次数：防止任何异常路径把 pending 卡住后无限留在前台。 */
    private var watchTicks = 0

    /** startForeground 是否成功；失败时不得假装「有保护」继续挂着。 */
    private var foregroundOk = false

    private val watchTask = object : Runnable {
        override fun run() {
            val pending = pendingShows.get()
            val remainMs = LinkHub.remainingCardMs()
            if (pending > 0 || remainMs > 0L) {
                if (++watchTicks > MAX_WATCH_TICKS) {
                    DevLog.w("island keep-alive budget exhausted pending=$pending remain=${remainMs}ms")
                    runCatching { stopSelf() }
                    return
                }
                DevLog.i("island keep-alive pending=$pending remain=${remainMs}ms")
                mainHandler.postDelayed(this, WATCH_MS)
                return
            }
            DevLog.i("island keep-alive end, stopping service")
            runCatching { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: return START_NOT_STICKY
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        DevLog.refresh(this)
        LinkHub.attachContext(this)
        DevLog.i("IslandService started kind=$kind startId=$startId")
        // 同一实例只做一次前台化：shortService 重复 startForeground 会重算
        // FGS 允许状态，部分 ROM 会直接抛异常（把一次正常出卡误判成失败）。
        if (!foregroundOk) foregroundOk = enterForeground(kind)
        if (!foregroundOk) {
            // 没有 FGS 归属时继续出卡只会产生「卡在、进程被回收、按钮点不动」。
            // 立刻收掉这次请求并让用户看见失败，而不是静默留一张死卡。
            DevLog.e("island abort: foreground unavailable kind=$kind")
            notifyForegroundFailure()
            LinkHub.abortShow("startForeground failed")
            stopSelf()
            return START_NOT_STICKY
        }
        pendingShows.incrementAndGet()
        armWatchdog()
        LinkHub.showCardFromModule(applicationContext, kind, target, token) { shown ->
            pendingShows.decrementAndGet()
            DevLog.i("island show done shown=$shown remain=${LinkHub.remainingCardMs()}ms kind=$kind")
            armWatchdog(if (shown) WATCH_MS else 0L)
        }
        return START_NOT_STICKY
    }

    /**
     * 进入前台。先按 manifest 声明的 shortService 类型调用；仍然失败时退到
     * 无类型 startForeground（部分 ROM 对类型校验更宽）。两者都失败返回 false。
     */
    private fun enterForeground(kind: String): Boolean {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                startForeground(NOTIF_ID, notification, FGS_TYPE_SHORT_SERVICE)
                return true
            }.onFailure { DevLog.e("startForeground(shortService) failed kind=$kind", it) }
        }
        return runCatching {
            startForeground(NOTIF_ID, notification)
            true
        }.getOrElse {
            DevLog.e("startForeground(plain) failed kind=$kind", it)
            false
        }
    }

    /** shortService 超时（约 3 分钟）由系统回调；主动收工，避免被系统记过。 */
    @RequiresApi(34)
    override fun onTimeout(startId: Int, fgsType: Int) {
        DevLog.w("island fgs timeout startId=$startId type=$fgsType")
        runCatching { stopSelf(startId) }
    }

    /** 重排看门狗（单实例，同一时刻只有一个待触发的复查）。 */
    private fun armWatchdog(delayMs: Long = WATCH_MS) {
        mainHandler.removeCallbacks(watchTask)
        mainHandler.postDelayed(watchTask, delayMs)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(watchTask)
        super.onDestroy()
    }

    private fun ensureChannel(id: String, name: String, importance: Int): NotificationManager? {
        val manager = getSystemService(NotificationManager::class.java) ?: return null
        manager.createNotificationChannel(
            NotificationChannel(id, name, importance).apply {
                description = "智能上岛的短时工作通道"
                setShowBadge(false)
            }
        )
        return manager
    }

    private fun buildForegroundNotification(): Notification {
        ensureChannel(CHANNEL_ID, "智能上岛", NotificationManager.IMPORTANCE_MIN)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("星穹")
            .setContentText("智能上岛处理中")
            .setSound(null)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    /** FGS 失败必须让用户看见：否则现象就是「推送来了但按钮没反应」。 */
    private fun notifyForegroundFailure() {
        val manager = ensureChannel(ERROR_CHANNEL_ID, "智能上岛异常", NotificationManager.IMPORTANCE_DEFAULT)
            ?: return
        runCatching {
            manager.notify(
                ERROR_NOTIF_ID,
                Notification.Builder(this, ERROR_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("智能上岛保活失败")
                    .setContentText("本次卡片已取消，请检查系统是否限制了后台运行")
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TOKEN = "token"
        private const val NOTIF_ID = 0x5102
        private const val ERROR_NOTIF_ID = 0x5103
        private const val CHANNEL_ID = "xinghe_island_fg"
        private const val ERROR_CHANNEL_ID = "xinghe_island_error"

        /** 看门狗复查间隔：1s 足够跟住「卡片到点收起」与出卡退避链。 */
        private const val WATCH_MS = 1_000L

        /** 看门狗最大复查次数（240 × 1s），卡片寿命封顶 150s，留足余量。 */
        private const val MAX_WATCH_TICKS = 240

        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE 的官方值（API 34）。 */
        private const val FGS_TYPE_SHORT_SERVICE = 0x0000_0800

        /**
         * system_server 侧入口：显式 startService 拉活模块进程出卡。
         * 全程 runCatching：拉活失败不影响系统。
         */
        /** @return startService 是否真的发出去了（失败时调用方回退透明 Activity） */
        fun connectAndShow(
            sysContext: Context,
            kind: String,
            target: String,
            token: String
        ): Boolean = runCatching {
            val intent = Intent().apply {
                setClassName(
                    LinkHook.MODULE_PACKAGE,
                    "io.github.proify.astrafirmament.app.IslandService"
                )
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_TOKEN, token)
            }
            sysContext.startService(intent)
            true
        }.getOrDefault(false)
    }
}
