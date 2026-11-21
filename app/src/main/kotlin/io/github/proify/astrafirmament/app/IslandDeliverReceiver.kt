package io.github.proify.astrafirmament.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.proify.astrafirmament.capability.link.DevLog

/**
 * 智能上岛的非 stopped 态轻量通道（manifest 广播接收器）。
 *
 * 广播带 FLAG_INCLUDE_STOPPED_PACKAGES(0x10)。本 receiver 只做转发：
 * 收到后立即转交 IslandService（FGS 保活），自身不跑出卡——保证出卡后
 * 进程仍活到岛收起（按钮点击落在活进程上）。
 *
 * 安全边界（审核整改 7.7）：manifest 里用 `android:permission` 绑定本应用
 * 声明并自持的**签名级**自定义权限（[io.github.proify.astrafirmament.hook.LinkHook.PERMISSION_ISLAND_COMMAND]）。
 * 只有同签名的应用（即本模块自己）和 system_server（core uid 不受组件权限限制）
 * 能送达；其它应用即使在 API < 34 上发广播，也会在 AMS 分发前被拒。
 * 不再依赖「取发送方包名」——那套在取不到时只能放行或一律丢弃，两头都不对。
 */
class IslandDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action != ACTION) return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET)?.trim()?.takeIf { it.isNotBlank() } ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        DevLog.refresh(app)
        DevLog.i("deliver received kind=$kind")
        // 转交 IslandService：它带 FGS 保活（进程活到岛收起，按钮点击落在
        // 活进程上）。本 receiver 只做转发，不自己跑出卡（出卡后无保活窗口）。
        runCatching {
            val svc = Intent(app, IslandService::class.java)
                .putExtra(IslandService.EXTRA_KIND, kind)
                .putExtra(IslandService.EXTRA_TARGET, target)
                .putExtra(IslandService.EXTRA_TOKEN, token)
            app.startService(svc)
        }.onFailure { DevLog.e("deliver→service failed", it) }
    }

    companion object {
        /** 与 LinkHook 的广播协议常量逐字对齐 */
        const val ACTION = "io.github.proify.astrafirmament.DELIVER"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TOKEN = "token"
    }
}
