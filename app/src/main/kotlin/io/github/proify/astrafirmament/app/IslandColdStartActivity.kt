package io.github.proify.astrafirmament.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import io.github.proify.astrafirmament.capability.link.DevLog
import io.github.proify.astrafirmament.hook.LinkHook

/**
 * 智能上岛的冷启动中转页（透明、无 UI、即起即灭）。
 *
 * 为什么是 Activity：本机（ColorOS）实测——清后台（force-stop）后，
 * 广播 / startService / Provider 全部被系统丢弃，**唯独 startActivity
 * 能穿透**（用户启动应用的系统级设计，force-stop 不拦）。实测实锤：
 * stopped=true 时 am start 直接拉活进程并把 stopped 置回 false。
 *
 * 流程：system_server 检测命中 → startActivity 拉起本页（豁免 BAL）→
 * onCreate 拉起 IslandService（Service 拿 FGS 保活到岛收起，按钮点击
 * 落在活进程上）→ 立即 finish。进程拉活后包退出 stopped 态，后续复制
 * 连广播都恢复了。
 *
 * ### 这个页面必须做到「零可见、零触摸」，否则就是全屏假死
 * 它是**全屏 Activity**，只要窗口还活着，手指就全落在它身上。历史事故：
 *  - pre-31 的启动窗口（preview window）铺 `windowBackground`，主题里没关
 *    `windowDisablePreview` → 一块全屏底板先盖上来；
 *  - 31+ 的 SplashScreen 由系统独立绘制、不读 windowBackground 的透明值，
 *    `windowSplashScreenBackground` 默认取 `?android:colorBackground`（深色主题
 *    下近乎纯黑）→ 冷启动那几秒整屏变黑且点不动，用户描述为「透明界面有后台了」。
 * 资源侧由 values/themes.xml（windowDisablePreview）与 values-v31/themes.xml
 * （windowSplashScreen* 全透明）覆盖。
 *
 * 代码侧再兜两层：
 *  1. 窗口本身 NOT_TOUCHABLE / NOT_FOCUSABLE —— 即使某些 ROM 仍然把窗口
 *     留在屏幕上，触摸也直接穿透给下层的岛/桌面，不会出现「整屏点不了」；
 *  2. finish 看门狗 —— 任何异常路径（Service 启动抛错、系统卡顿）都在
 *     [FINISH_TIMEOUT_MS] 内强制 finishAndRemoveTask，绝不长期驻留。
 */
class IslandColdStartActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishTask = Runnable { finishSafely() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        makeWindowInvisible()
        val kind = intent?.getStringExtra(EXTRA_KIND)
        val target = intent?.getStringExtra(EXTRA_TARGET)
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (!kind.isNullOrBlank() && !target.isNullOrBlank()) {
            DevLog.refresh(applicationContext)
            DevLog.i("cold start kind=$kind")
            // Activity 没有 startForeground（那是 Service 的方法）——保活
            // 交给 IslandService：它带 FGS（shortService），进程活到岛收起。
            runCatching {
                val svc = Intent(applicationContext, IslandService::class.java)
                    .putExtra(IslandService.EXTRA_KIND, kind)
                    .putExtra(IslandService.EXTRA_TARGET, target)
                    .putExtra(IslandService.EXTRA_TOKEN, token)
                applicationContext.startService(svc)
            }.onFailure { DevLog.e("cold start service failed", it) }
        }
        handler.postDelayed(finishTask, FINISH_TIMEOUT_MS)
        finishSafely()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishTask)
        super.onDestroy()
    }

    /**
     * 窗口级别的「不可见、不可点」：即使本页因为任何原因没能在首帧前结束，
     * 也只是一层透明壳，触摸与焦点都穿透到下面。
     */
    private fun makeWindowInvisible() {
        runCatching {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0f)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        }.onFailure { DevLog.w("cold start window setup failed: ${it.message}") }
    }

    private fun finishSafely() {
        handler.removeCallbacks(finishTask)
        runCatching { finishAndRemoveTask() }
            .recoverCatching { finish() }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TOKEN = "token"

        /** finish 看门狗：正常路径下一帧内就结束，这里只兜异常。 */
        private const val FINISH_TIMEOUT_MS = 800L

        /** 同一次出卡的多条投递通道可能连着来，避免反复闪这个中转页。 */
        private const val LAUNCH_DEDUP_MS = 800L

        @Volatile
        private var lastLaunchKey: String = ""

        @Volatile
        private var lastLaunchAt: Long = 0L

        /** system_server 侧入口：显式启动本页（穿透 force-stop 的唯一通道） */
        fun startFromSystem(context: android.content.Context, kind: String, target: String, token: String) {
            val key = "$kind\u0000$target"
            val now = System.currentTimeMillis()
            synchronized(IslandColdStartActivity::class.java) {
                if (key == lastLaunchKey && now - lastLaunchAt < LAUNCH_DEDUP_MS) {
                    DevLog.i("cold start skipped (recent launch) kind=$kind")
                    return
                }
                lastLaunchKey = key
                lastLaunchAt = now
            }
            runCatching {
                val intent = Intent().apply {
                    setClassName(
                        LinkHook.MODULE_PACKAGE,
                        "io.github.proify.astrafirmament.app.IslandColdStartActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(EXTRA_KIND, kind)
                    putExtra(EXTRA_TARGET, target)
                    putExtra(EXTRA_TOKEN, token)
                }
                context.startActivity(intent)
            }
        }
    }
}
