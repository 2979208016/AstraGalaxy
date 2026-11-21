package io.github.proify.astrafirmament.ui.shell

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.Divider
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.theme.CouiTheme
import io.github.proify.astrafirmament.BuildConfig

/**
 * 关于项目（文档页）：项目简介、如实说明、前提、免责声明全部收进这一篇，
 * 文档式分节（标题 + 正文 + 分隔线），不再拆成多张设置卡片（9.1 用户要求）。
 * 顶栏「复制」把全文放进剪贴板，方便整段贴给作者或审核。
 */

private data class DocSection(val title: String, val body: String)

private val DOC_SECTIONS: List<DocSection> = listOf(
    DocSection(
        "项目简介",
        "星穹（AstraFirmament）是一个 LSPosed 模块：把当前播放歌曲的歌词（原文与翻译）" +
            "推给星河岛显示，并把链接、手机号、安装包、WiFi、蓝牙这些常用动作做成上岛卡片。\n" +
            "当前版本 v" + BuildConfig.VERSION_NAME + "。界面基于 COUI（ColorOS 凝光组件库）。",
    ),
    DocSection(
        "读取了什么",
        "每一项都能在应用内单独关闭；全部只在本机处理，不上传。\n" +
            "· 剪贴板：在系统框架内读取全部复制内容，默认开启；只在本机比对链接、手机号来决定是否出卡，不保存原文。\n" +
            "· 网络响应：在音乐应用进程内旁路只读嗅探它收到的歌词响应（okhttp / okio / RN 桥等），不拦截、不改写、不上传。\n" +
            "· 蓝牙：只在酷我音乐、汽水音乐内把「蓝牙是否连接」的查询结果改成已连接，让播放器持续上报媒体信息与歌词；不连接任何真实设备。\n" +
            "· 媒体信息：读取播放器的歌名、歌手、时长与播放进度，用于歌词胶囊。\n" +
            "· 应用列表：读取本机可响应「打开链接 / 安装应用」的应用名，用来列默认浏览器与安装器；不读其它应用的数据。\n" +
            "· 出卡：卡片内容经系统广播交给本机的星河岛（星流）显示，不出设备。",
    ),
    DocSection(
        "Root 用途",
        "授权后以 root 读写本机文件，不是只读；不 remount、不改系统分区。\n" +
            "· 读 LSPosed 日志：权限中心判断模块是否加载（读 /data/adb/lspd/log）；生成日志报告时收集框架日志。\n" +
            "· 读 WiFi 密码：「复制 / 分享密码」时读 WifiConfigStore.xml 或 wpa_supplicant.conf。\n" +
            "· 复制安装包：把下载或从 QQ / TIM 收到的安装包（这两个目录也由 root 读取）复制到应用暂存目录，供安装、查看、分享；你点「删除」时用 root 删除原始安装包。\n" +
            "未授权时：歌词胶囊、链接与手机号、WiFi 岛与蓝牙岛照常；日志报告缺少 LSPosed 日志，WiFi 密码复制 / 分享与智能文件（安装包出卡 / 安装 / 查看 / 分享 / 删除）不可用。",
    ),
    DocSection(
        "日志",
        "开关在「设置 → 开发者选项」，默认关闭，关闭时一条都不写。\n" +
            "· 开启后：记录投递、出卡与状态变化；日志包（xinghe_report.zip，含 01 设备信息、02 模块日志、03 只保留本模块行的 LSPosed 日志）由后台按你设的打包周期常驻生成，新的自动替换旧的，不必进开发者选项现打。\n" +
            "· 清理周期到期自动清空日志缓冲并删除旧包，从头开始记录。\n" +
            "· 报告按脱敏规则写入：不含完整文件路径、WiFi 名称、蓝牙设备名与 MAC、歌名与歌手、完整网址、剪贴板内容；类型、后缀、大小、耗时照常记录。\n" +
            "· 报告只留在应用数据目录，可随时清除，不会上传。",
    ),
    DocSection(
        "前提条件",
        "· 必须是 LSPosed 环境：在 LSPosed 里启用模块并勾选作用域后重启手机；否则不上岛，也不提供歌词。\n" +
            "· 需要 root 的功能：日志报告里的 LSPosed 日志、WiFi 密码复制 / 分享、智能文件（安装包出卡 / 安装 / 查看 / 分享 / 删除）、权限中心的加载检测。\n" +
            "· 出卡需要在设备上装着星河岛（星流）；没有它，模块只提供歌词，不显示卡片。",
    ),
    DocSection(
        "我们不做的事",
        "· 不修改系统分区（不 remount、不改 /system），不写其它应用的数据目录。\n" +
            "· 不上传任何数据；唯一的外发请求是取词——向公开词源（网易云、酷狗、QQ、酷我、LRCLIB）只发歌名 / 歌手 / 时长，或按 trackId 请求 Spotify 官方接口（复用 Spotify 播放器自身的登录授权请求头，本模块不自行登录、不另存账号与设备信息）。\n" +
            "· 不使用常驻后台服务：只在出卡瞬间起一个系统托管的短时前台服务，约 3 分钟内自动结束。\n" +
            "· 不记录键盘输入；不申请通讯录、短信、相册等权限。",
    ),
    DocSection(
        "免责声明",
        "· 本模块按「现状」提供，不附带任何明示或默示担保；因使用本模块造成的任何直接或间接损失，作者不承担责任。\n" +
            "· 歌词来源为第三方公开接口，其可用性与内容准确性不受本项目控制。\n" +
            "· 请在自行评估风险后使用；如不接受上述条款，请卸载本模块。",
    ),
)

/** 全文（复制用）：各节标题加正文顺序拼接。 */
private fun fullDocumentText(): String = DOC_SECTIONS.joinToString("\n\n") {
    it.title + "\n" + it.body
}

@Composable
fun AboutProjectPage(onBack: () -> Unit) {
    val context = LocalContext.current
    AppSubPage(
        title = "关于项目",
        onBack = onBack,
        actions = {
            androidx.compose.foundation.text.BasicText(
                "复制",
                style = androidx.compose.ui.text.TextStyle(
                    color = CouiTheme.colorScheme.accent,
                    fontSize = 15.sp,
                ),
                modifier = Modifier
                    .clickableText { copyDocument(context) }
                    .padding(horizontal = 12.dp),
            )
        },
    ) {
        documentContent()
    }
}

private fun LazyListScope.documentContent() {
    DOC_SECTIONS.forEachIndexed { index, section ->
        item {
            Text(
                text = section.title,
                kind = CouiTextKind.ItemTitle,
                color = CouiTheme.colorScheme.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = if (index == 0) 4.dp else 20.dp),
            )
            Text(
                text = section.body,
                kind = CouiTextKind.ItemSummary,
                color = CouiTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 6.dp),
            )
            if (index < DOC_SECTIONS.lastIndex) {
                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 20.dp),
                )
            }
        }
    }
    item {
        Text(
            text = "全文可复制 · 以当前版本实际行为为准",
            kind = CouiTextKind.GroupFooter,
            color = CouiTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp),
        )
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    Modifier.clickable(onClick = onClick).then(this)

private fun copyDocument(context: Context) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText("星穹·关于项目", fullDocumentText()),
        )
        Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
    }
}
