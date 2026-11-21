package io.github.proify.astrafirmament.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.proify.astrafirmament.capability.link.LinkHub
import io.github.proify.astrafirmament.shared.LspLoadProbe
import io.github.proify.astrafirmament.shared.SuReader
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs

/**
 * 权限中心。
 *
 * LSPosed 是否真的加载了模块，靠 [LspLoadProbe] 读框架日志判断（6.0 之前读一个从不落盘的
 * 内存日志，所以永远显示「未检测到」）。这里异步探测并重试，避免开机后框架还没写日志就误报。
 */
@Composable
fun PermissionCenterPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var rootState by remember { mutableStateOf<Boolean?>(null) }
    var moduleState by remember { mutableStateOf<LspLoadProbe.Result?>(null) }
    var probing by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        rootState = runCatching { SuReader.available() }.getOrDefault(false)
        probing = true
        var attempt = 0
        while (attempt < 3) {
            val result = runCatching { LspLoadProbe.probe() }.getOrNull()
            moduleState = result
            if (result?.loaded == true) break
            attempt++
            if (attempt < 3) kotlinx.coroutines.delay(3_000)
        }
        probing = false
    }

    AppSubPage(title = "权限中心", onBack = onBack) {
        group("Root 权限") {
            ValueRow(
                title = "状态",
                value = when (rootState) {
                    null -> "检查中…"
                    true -> "已授权"
                    false -> "未授权"
                },
                icon = AstraGlyphs.Lock,
            )
        }
        group("LSPosed 模块") {
            val text = when {
                probing && moduleState?.loaded != true -> "检查中…"
                moduleState == null -> "无法读取框架日志"
                moduleState!!.loaded -> moduleState!!.version?.let { "已加载（版本 $it）" } ?: "已加载"
                else -> "未检测到（可在 LSPosed 里重新启用后重启手机）"
            }
            ValueRow(title = "加载状态", value = text, icon = AstraGlyphs.Wrench)
            moduleState?.fromLog?.let { NoteRow("依据：$it") }
        }
        group("星河岛") {
            ValueRow(
                title = "连接",
                value = if (LinkHub.isReady()) "已连接星流" else "未连接星流",
                icon = AstraGlyphs.LiveActivity,
            )
        }
        group("说明") {
            // 审核只要求「Root 用途与实际一致」，这里留一句准确的简述；完整说明统一在「关于 → 如实说明」。
            NoteRow("Root 用于读取 LSPosed 日志（含 /data/adb 下的框架日志）、读取 WiFi 密码、复制安装包并在你点「删除」时删除原始安装包：以 root 读写本机文件，不是只读，但不 remount、不改系统分区。读取范围、未授权时哪些功能不可用，见「关于 → 如实说明」。")
            NoteRow("检测不到时，先在 LSPosed 里确认模块已启用并勾选作用域，然后重启手机。")
        }
    }
}
