package io.github.proify.astrafirmament.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.proify.astrafirmament.capability.link.DevLog
import io.github.proify.astrafirmament.settings.ModulePrefs
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 开发者选项：日志开关、日志状态与日志包。
 *
 * 日志包由后台按「打包周期」节流常驻生成（新的原子替换旧的），进来看到的
 * 永远是已就绪的最新包；「清理周期」到期自动清空缓冲并删除旧包从头记录。
 * 顶部分享按钮直接把现成的 zip 交给系统分享面板，不现场打包。
 */
@Composable
fun DevPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ModulePrefs.of(context) }
    var enabled by remember { mutableStateOf(ModulePrefs.isDevLogEnabled(prefs)) }
    var entries by remember { mutableIntStateOf(DevLog.entryCount()) }

    AppSubPage(title = "开发者选项", onBack = onBack) {
        group("详细日志") {
            SwitchRow(
                title = "记录详细日志",
                checked = enabled,
                summary = "记录投递、出卡、状态变化这些关键节点",
                icon = AstraGlyphs.Record,
                onCheckedChange = { want ->
                    enabled = want
                    ModulePrefs.edit(context).putBoolean(ModulePrefs.KEY_DEV_LOG, want).apply()
                },
            )
            ValueRow(title = "当前条数", value = entries.toString(), icon = AstraGlyphs.Info)
        }
        logPackGroup(context) { entries = DevLog.entryCount() }
    }
}

/** 日志包组：常驻状态 + 分享 + 打包/清理周期 + 手动清除。 */
private fun LazyListScope.logPackGroup(context: Context, onCleared: () -> Unit) {
    group("日志包") {
        LogPackRows(context, onCleared)
    }
}

@Composable
private fun ColumnScope.LogPackRows(context: Context, onCleared: () -> Unit) {
    val prefs = remember { ModulePrefs.of(context) }
    var packMin by remember { mutableIntStateOf(ModulePrefs.isLogPackMinutes(prefs)) }
    var cleanHour by remember { mutableIntStateOf(ModulePrefs.isLogCleanHours(prefs)) }
    // 包状态：后台节流线程按周期重建，进页只读一次（就绪/未生成），不做现场打包
    var reportName by remember { mutableStateOf<String?>(null) }
    var reportReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val report = withContext(Dispatchers.IO) { DevLog.currentReport(context) }
        reportName = report?.name
        reportReady = report != null
    }

    ValueRow(
        title = "压缩包状态",
        value = if (reportReady) "已就绪：$reportName" else "尚未生成（记录日志后自动打包）",
        icon = AstraGlyphs.Mail,
    )
    LinkRow(
        title = "分享日志包",
        summary = if (reportReady) "把现成的压缩包交给系统分享面板" else "等压缩包生成后即可分享",
        value = "分享",
        icon = AstraGlyphs.Mail,
        enabled = reportReady,
        onClick = { shareReport(context, reportName) },
    )
    CadenceSlider(
        title = "打包周期",
        value = packMin,
        format = ::formatMinutes,
        summary = "距上次打包超过该时长才重建压缩包，新包替换旧包",
    ) { committed ->
        ModulePrefs.edit(context).putInt(ModulePrefs.KEY_LOG_PACK_MIN, committed).apply()
        DevLog.updateIntervals(context)
    }
    CadenceSlider(
        title = "清理周期",
        value = cleanHour,
        format = ::formatHours,
        summary = "到期清空日志缓冲并删除旧压缩包，从头开始记录",
    ) { committed ->
        ModulePrefs.edit(context).putInt(ModulePrefs.KEY_LOG_CLEAN_HOUR, committed).apply()
        DevLog.updateIntervals(context)
    }
    ActionRow(
        title = "清除日志",
        destructive = true,
        onClick = {
            runCatching { DevLog.clear(context) }
            reportName = null
            reportReady = false
            onCleared()
            devToast(context, "已清除")
        },
    )
    NoteRow("日志包常驻应用数据目录，含设备信息、模块日志与 LSPosed 框架日志；文件路径、WiFi 名称、蓝牙设备名与 MAC、歌名歌手、完整网址与剪贴板内容已脱敏。")
}

/** 直接把已打好的 zip 交给系统分享面板（ReportProvider 只读暴露这一个文件）。 */
private fun shareReport(context: Context, name: String?) {
    if (name.isNullOrEmpty()) {
        devToast(context, "日志包还没生成")
        return
    }
    val uri = Uri.parse("content://${context.packageName}.reportprovider/$name")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "分享日志包").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { devToast(context, "没有可用的分享目标") }
}

/** 打包周期显示：不满一小时按分钟，满一小时按小时取整。 */
private fun formatMinutes(min: Int): String = if (min >= 60) "${min / 60} 小时" else "$min 分钟"

/** 清理周期显示：不满一天按小时，满一天按天取整。 */
private fun formatHours(hour: Int): String = if (hour >= 24) "${hour / 24} 天" else "$hour 小时"

private fun devToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/**
 * 周期拉条：拖动中只更新本地草稿，松手才写偏好并回调提交（立即生效）。
 * 打包/清理两个周期共用。
 */
@Composable
private fun ColumnScope.CadenceSlider(
    title: String,
    value: Int,
    format: (Int) -> String,
    summary: String,
    onCommit: (Int) -> Unit,
) {
    var draft by remember(value) { mutableIntStateOf(value) }
    SliderRow(
        title = title,
        value = draft.toFloat(),
        valueText = format(draft),
        valueRange = 1f..720f,
        summary = summary,
        onValueChange = { draft = it.toInt() },
        onValueChangeFinished = { onCommit(draft) },
    )
}
