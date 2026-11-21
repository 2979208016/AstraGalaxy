package io.github.proify.astrafirmament.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.proify.astrafirmament.capability.link.DevLog

/**
 * system → App 的候选接收器。
 *
 * 安全边界（审核整改 7.7）：
 *  1. 来源门禁在 manifest：`android:permission` 绑定本应用声明并自持的签名级
 *     自定义权限，只有 system_server（core uid，不受组件权限限制）与同签名应用
 *     能送达；API 34 以下同样生效（不再依赖 getSentFromPackage 这种「取不到就
 *     放行/丢弃」的运行时判断）。
 *  2. 路径必须过 [ApkBridge.isAllowedPath] 白名单——两条都满足才可能被 su 复制
 *     到私有目录，即「任意应用让本进程把任意文件搬出来」这条路不存在。
 *
 * 为什么用 goAsync：su 复制几百兆要几秒到几十秒，onReceive 同步返回会被 ANR，
 * pendingResult 保证进程在复制期间不被回收。
 */
class ApkDiscoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkBridge.ACTION_DISCOVERED) return
        val app = context.applicationContext
        val request = ApkBridge.requestOf(intent) ?: return
        DevLog.refresh(app)
        DevLog.i(
            "智能文件 stage=receiver source=${request.source} name=${request.name} " +
                "size=${request.size} path=${request.path}"
        )
        if (!SmartApkIntake.enabled(app)) {
            DevLog.i("智能文件开关已关，丢弃候选")
            return
        }
        val pending = goAsync()
        Thread({
            try {
                SmartApkIntake.run(app, request)
            } finally {
                runCatching { pending.finish() }
            }
        }, THREAD_NAME).start()
    }

    private companion object {
        const val THREAD_NAME = "xinghe-apk-intake"
    }
}
