package io.github.proify.astrafirmament.ui.shell

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.proify.astrafirmament.app.IslandDeliverReceiver
import io.github.proify.astrafirmament.capability.link.LinkHub
import io.github.proify.astrafirmament.hook.LinkHook

/** 测试用链接卡 target：LinkHub 只把它当普通 URL 展示。 */
private const val TEST_URL = "https://example.com/xinghe-test"

/** 测试用手机号：CardSpec 只做展示与拨号白名单，不会自动拨出。 */
private const val TEST_PHONE = "13800138000"

/**
 * 测试功能页：只保留链接卡与手机号卡两种出卡测试。
 *
 * 所有动作都只在点击时执行——不做开机自测、不发定时广播（审核要求高危动作必须用户主动触发）。
 */
@Composable
fun TestPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val last = remember { mutableStateOf("还没跑过测试") }
    AppSubPage(title = "测试功能", onBack = onBack, bottomClearance = TabBar.Clearance) {
        group("说明") {
            NoteRow("这些测试只在点击时执行，不会开机自测、不会自动发广播。")
            ValueRow(title = "上次结果", value = last.value)
            ActionRow(
                title = "刷新星流连接状态",
                onClick = { last.value = "星流：" + LinkHub.stateName() },
            )
        }
        group("岛卡片") {
            ActionRow(
                title = "链接卡片",
                onClick = { last.value = sendDeliver(context, LinkHook.KIND_LINK_OUT, TEST_URL) },
            )
            ActionRow(
                title = "手机号卡片",
                onClick = { last.value = sendDeliver(context, LinkHook.KIND_PHONE_OUT, TEST_PHONE) },
            )
            NoteRow("这里只出卡：卡上的按钮要自己在卡上点。")
        }
    }
}

/**
 * 发一条出卡广播（与 LinkHook.outCard 同协议：定向到本包 + 允许送达 stopped 进程）。
 *
 * 回显只表示「已发送」——能否真的出卡还取决于星流连接状态与对应功能开关。
 */
private fun sendDeliver(context: Context, kind: String, target: String): String {
    val intent = Intent(IslandDeliverReceiver.ACTION)
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra(IslandDeliverReceiver.EXTRA_KIND, kind)
        .putExtra(IslandDeliverReceiver.EXTRA_TARGET, target)
    return runCatching { context.sendBroadcast(intent) }
        .fold({ "已发送 $kind 出卡请求" }, { "发送失败：" + it.message })
}
