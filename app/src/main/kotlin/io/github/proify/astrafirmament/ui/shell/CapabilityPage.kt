package io.github.proify.astrafirmament.ui.shell

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.Dialog
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Item
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.theme.CouiTheme
import io.github.proify.astrafirmament.R
import io.github.proify.astrafirmament.app.ApkIncomingStore
import io.github.proify.astrafirmament.app.ThirdPartyApkScanner
import io.github.proify.astrafirmament.capability.link.BrowserChoice
import io.github.proify.astrafirmament.capability.link.InstallerChoice
import io.github.proify.astrafirmament.settings.ModulePrefs
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 智能上岛的五个子能力（真正的动作）：key 用于三级页路由，prefsKey 是它自己的开关键。 */
private data class IslandFeature(
    val key: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val prefsKey: String,
)

private val IslandFeatures = listOf(
    IslandFeature("link", "链接助手", "复制内容里的链接直接上岛", AstraGlyphs.ExternalApps, ModulePrefs.KEY_LINK),
    IslandFeature("phone", "手机号", "识别复制内容里的手机号", AstraGlyphs.Message, ModulePrefs.KEY_PHONE),
    IslandFeature("file", "智能文件", "安装包下载完成后提示安装", AstraGlyphs.Move, ModulePrefs.KEY_FILE_INSTALLER),
    IslandFeature("wifi", "WiFi", "连上时显示名称与网速", AstraGlyphs.Wifi, ModulePrefs.KEY_WIFI),
    IslandFeature("bt", "蓝牙", "设备连上或断开时提示", AstraGlyphs.Bluetooth, ModulePrefs.KEY_BLUETOOTH),
)

private const val FEATURE_FILE = "file"
private const val PAGE_LYRICS = "lyrics"
private const val PAGE_ISLAND = "island"
private const val PAGE_TEST = "test"

/** SAF 选中的目录树只支持内部存储卷（`primary:`），其它卷给这个提示 */
private const val PRIMARY_TREE_PREFIX = "primary:"
private const val UNSUPPORTED_DIR_HINT = "暂不支持该位置"
private const val STORAGE_ROOT_HINT = "请选择具体目录，不要选内部存储根"

/** 界面显示的存储根前缀（完整路径太长，列表里只显示相对部分） */
private const val STORAGE_ROOT_PREFIX = "/storage/emulated/0/"

/** 扫描层级选项：文案 + 取值，取值约定见 [ModulePrefs.apkScanDepth] */
private val ScanDepthOptions: List<Pair<String, Int>> = listOf(
    "仅目录本身" to 1,
    "向下 2 层" to 2,
    "向下 3 层" to 3,
    "向下 5 层" to 5,
    "不限层级" to ModulePrefs.APK_SCAN_DEPTH_UNLIMITED,
)

private val ScanDepthLabels: List<String> = ScanDepthOptions.map { it.first }

private fun scanDepthIndex(depth: Int): Int =
    ScanDepthOptions.indexOfFirst { it.second == depth }.coerceAtLeast(0)

/** 内部存储根不能作为来源目录：全盘 3 层扫描必然超时，整轮会变成 @denied */
private fun isInternalStorageRoot(dir: String): Boolean =
    dir.trimEnd('/') == STORAGE_ROOT_PREFIX.trimEnd('/')

/** 列表里显示的目录名：内部存储根去掉前缀 */
private fun displaySourceDir(dir: String): String =
    if (dir.length <= STORAGE_ROOT_PREFIX.length) "内部存储" else dir.removePrefix(STORAGE_ROOT_PREFIX)

/**
 * 能力页状态。全部字段都是 Compose 状态：
 * 以前用普通 `var`，点了开关/入口界面不重组，表现为「点了完全没反应」。
 */
private class CapabilityState(context: Context, private val prefs: SharedPreferences) {
    private val app: Context = context.applicationContext
    var lyricOn by mutableStateOf(ModulePrefs.isLyricEnabled(prefs))
    var islandOn by mutableStateOf(ModulePrefs.isSmartIslandEnabled(prefs))
    val featureOn = mutableStateMapOf<String, Boolean>().apply {
        IslandFeatures.forEach { put(it.key, prefs.getBoolean(it.prefsKey, true)) }
    }
    var page by mutableStateOf<String?>(null)
    var lingerSec by mutableIntStateOf(ModulePrefs.cardLingerSeconds(prefs))
    var cleanDays by mutableIntStateOf(ModulePrefs.apkCleanDays(prefs))
    var sourceDirs by mutableStateOf(ModulePrefs.apkSourceDirs(prefs))
    var scanDepth by mutableIntStateOf(ModulePrefs.apkScanDepth(prefs))
    var browserMode by mutableStateOf(ModulePrefs.browserMode(prefs))
    var browserPkg by mutableStateOf(ModulePrefs.browserPackage(prefs))
    var installerMode by mutableStateOf(ModulePrefs.installerMode(prefs))
    var installerPkg by mutableStateOf(ModulePrefs.installerPackage(prefs))

    fun setLyric(on: Boolean) {
        lyricOn = on
        ModulePrefs.edit(app).putBoolean(ModulePrefs.KEY_LYRIC, on).apply()
    }

    /** 总开关：一次写六个键（总开关 + 五个子能力）。 */
    fun setIsland(on: Boolean) {
        islandOn = on
        ModulePrefs.edit(app)
            .putBoolean(ModulePrefs.KEY_SMART_ISLAND, on)
            .putBoolean(ModulePrefs.KEY_LINK, on)
            .putBoolean(ModulePrefs.KEY_PHONE, on)
            .putBoolean(ModulePrefs.KEY_FILE_INSTALLER, on)
            .putBoolean(ModulePrefs.KEY_WIFI, on)
            .putBoolean(ModulePrefs.KEY_BLUETOOTH, on)
            .apply()
        IslandFeatures.forEach { featureOn[it.key] = on }
    }

    fun setFeature(key: String, on: Boolean) {
        featureOn[key] = on
        val feature = IslandFeatures.first { it.key == key }
        ModulePrefs.edit(app).putBoolean(feature.prefsKey, on).apply()
    }

    /** 全局默认显示时长（总功能设置），与 [ModulePrefs.cardLingerSeconds] 同一夹紧语义。 */
    fun setDefaultLinger(seconds: Int) {
        lingerSec = ModulePrefs.clampCardLifeSeconds(seconds)
    }

    fun commitDefaultLinger() {
        ModulePrefs.setCardLinger(ModulePrefs.edit(app), lingerSec).apply()
    }

    /** 单个子功能的显示时长（-1 = 跟随默认）。 */
    fun setFeatureLinger(key: String, seconds: Int, commit: Boolean) {
        if (!commit) return
        ModulePrefs.setFeatureLinger(ModulePrefs.edit(app), key, seconds).apply()
    }

    /** 拖动中的临时值（-2 表示未在拖动）：滑条与灰字都读它，松手才落盘。 */
    private val lingerDraft = mutableStateMapOf<String, Int>()

    /**
     * 各子功能已落盘的单独时长（[ModulePrefs.LINGER_FOLLOW_DEFAULT] = 跟随默认）。
     * 以前这里直接读 SharedPreferences，写盘不触发重组：「恢复默认时长」点完界面不动（7.10）。
     */
    private val lingerSaved = mutableStateMapOf<String, Int>().apply {
        IslandFeatures.forEach { put(it.key, ModulePrefs.featureLingerSeconds(prefs, it.key)) }
    }

    fun featureLinger(key: String): Int =
        lingerDraft[key] ?: lingerSaved[key] ?: ModulePrefs.LINGER_FOLLOW_DEFAULT

    /** 最终生效的卡片时长（已夹紧）：跟随默认时取这一级设置的默认时长。 */
    fun effectiveLinger(key: String): Int =
        lingerDraft[key] ?: lingerSaved[key]?.takeIf { it >= 0 } ?: lingerSec

    /** 拖动中：只更新界面；commit=true 时写偏好并清掉草稿。 */
    fun dragFeatureLinger(key: String, seconds: Int) {
        lingerDraft[key] = seconds
    }

    fun commitFeatureLinger(key: String, seconds: Int) {
        lingerDraft.remove(key)
        ModulePrefs.setFeatureLinger(ModulePrefs.edit(app), key, seconds).apply()
        // 落盘用的是同一套夹紧规则，界面状态也必须按它回填，否则会和下次冷启动读到的值不一致
        lingerSaved[key] =
            if (seconds < 0) ModulePrefs.LINGER_FOLLOW_DEFAULT else ModulePrefs.clampCardLifeSeconds(seconds)
    }

    fun setCleanDays(days: Int, commit: Boolean) {
        cleanDays = days
        if (commit) ModulePrefs.setApkCleanDays(ModulePrefs.edit(app), days).apply()
    }

    /** 新增来源目录：规范化 → 去重排序 → 落盘，并让扫描器下一轮立即换用新列表 */
    fun addSourceDir(dir: String): Boolean {
        val normalized = ModulePrefs.normalizeApkSourceDir(dir) ?: return false
        // 根目录 3 层 find 会扫全盘，6s 超时后整轮 @denied，扫描直接停摆
        if (isInternalStorageRoot(normalized)) return false
        writeSourceDirs((sourceDirs + normalized).distinct().sorted())
        return true
    }

    fun removeSourceDir(dir: String) {
        writeSourceDirs(sourceDirs.filterNot { it == dir })
    }

    private fun writeSourceDirs(dirs: List<String>) {
        sourceDirs = dirs
        ModulePrefs.setApkSourceDirs(ModulePrefs.edit(app), dirs).apply()
        ThirdPartyApkScanner.reload(app)
    }

    /**
     * 改扫描层级。**不能叫 setScanDepth**：同名会与委托属性 `var scanDepth` 的
     * 隐式 setter 撞同一个 JVM 签名（`setScanDepth(I)V`），编译期直接 Platform
     * declaration clash。
     */
    fun applyScanDepth(depth: Int) {
        val value = ModulePrefs.clampApkScanDepth(depth)
        scanDepth = value
        ModulePrefs.setApkScanDepth(ModulePrefs.edit(app), value).apply()
        ThirdPartyApkScanner.reload(app)
    }
}

@Composable
fun CapabilityPage() {
    val context = LocalContext.current
    val prefs = remember { ModulePrefs.of(context) }
    val state = remember { CapabilityState(context, prefs) }
    // 返回栈固定三层：一级（首页）< 二级（智能上岛 / 歌词 / 测试）< 三级（智能上岛的子功能）。
    // 三层各有固定槽位：退栈只让最上面那层消失，下面那页不会被搬到别的槽位重新组合。
    // 以前按 page 决定 parent / child 传谁，退栈时页面换槽重建 → 松手瞬间跳闪（7.10）。
    val page = state.page
    val feature = page?.let { p -> IslandFeatures.firstOrNull { it.key == p } }
    // 三级页在手时，二级槽里垫的永远是「智能上岛」
    val second = page?.let { if (feature != null) PAGE_ISLAND else it }
    val childLayer: (@Composable () -> Unit)? =
        if (second == null) null else { { SecondaryPage(context, state, second) } }
    val topLayer: (@Composable () -> Unit)? =
        if (feature == null) null else { { FeaturePage(context, state, feature) } }
    PredictivePageStack(
        canGoBack = page != null,
        onBackGesture = { state.page = if (feature != null) PAGE_ISLAND else null },
        parent = { CapabilityHome(state) },
        child = childLayer,
        top = topLayer,
    )
}

/** 二级页：歌词 / 智能上岛 / 测试功能，返回走 [PredictivePageStack] 的退栈。 */
@Composable
private fun SecondaryPage(context: Context, state: CapabilityState, page: String) {
    when (page) {
        PAGE_LYRICS -> LyricsPage(context, state)
        PAGE_ISLAND -> IslandPage(context, state)
        PAGE_TEST -> TestPage(onBack = { state.page = null })
        else -> CapabilityHome(state)
    }
}

@Composable
private fun CapabilityHome(state: CapabilityState) {
    AppTabPage(title = "能力", subtitle = "常用功能放在一起，点开就能单独设置") {
        group("歌词") {
            SwitchRow(
                title = "歌词胶囊",
                checked = state.lyricOn,
                summary = "向星河岛提供当前播放歌曲的歌词（原文与翻译）",
                icon = AstraGlyphs.Music,
                onCheckedChange = state::setLyric,
            )
            LinkRow(
                title = "歌词设置与适配应用",
                summary = "适配范围、逐字与翻译说明",
                icon = AstraGlyphs.ExternalApps,
                onClick = { state.page = PAGE_LYRICS },
            )
        }
        group("智能上岛") {
            SwitchRow(
                title = "智能上岛",
                checked = state.islandOn,
                summary = "关掉它，下面这些功能都不出卡片",
                icon = AstraGlyphs.LiveActivity,
                onCheckedChange = state::setIsland,
            )
            LinkRow(
                title = "子功能与卡片设置",
                summary = "显示时长、打开方式与子功能开关",
                icon = AstraGlyphs.Record,
                onClick = { state.page = PAGE_ISLAND },
            )
        }
        group("测试") {
            LinkRow(
                title = "测试功能",
                summary = "逐项触发并回显结果，只在点击时发测试广播",
                icon = AstraGlyphs.Wrench,
                onClick = { state.page = PAGE_TEST },
            )
        }
    }
}

@Composable
private fun LyricsPage(context: Context, state: CapabilityState) {
    AppSubPage(title = "歌词胶囊", onBack = { state.page = null }, bottomClearance = TabBar.Clearance) {
        group("歌词胶囊") {
            SwitchRow(
                title = "启用",
                checked = state.lyricOn,
                summary = "向星河岛提供当前播放歌曲的歌词",
                icon = AstraGlyphs.Music,
                onCheckedChange = state::setLyric,
            )
        }
        group("适配应用") {
            context.getString(R.string.ui_apps_body)
                .split("\n\n")
                .filter { it.isNotBlank() }
                .forEach { para -> NoteRow(para) }
        }
    }
}

/** 二级页：智能上岛总体设置（默认时长、打开方式）+ 五个子功能入口。 */
@Composable
private fun IslandPage(context: Context, state: CapabilityState) {
    val browsers by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            BrowserChoice.installed(context).map { it.packageName to it.label }
        }
    }
    val installers by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            InstallerChoice.installed(context).map { it.packageName to it.label }
        }
    }
    AppSubPage(title = "智能上岛", onBack = { state.page = null }, bottomClearance = TabBar.Clearance) {
        group("总开关") {
            SwitchRow(
                title = "启用智能上岛",
                checked = state.islandOn,
                summary = "关掉它，下面这些功能都不出卡片",
                icon = AstraGlyphs.LiveActivity,
                onCheckedChange = state::setIsland,
            )
        }
        group("默认显示时长") {
            SliderRow(
                title = "默认显示时长",
                value = state.lingerSec.toFloat(),
                valueText = "${state.lingerSec} 秒",
                valueRange = ModulePrefs.CARD_LIFE_MIN_SECONDS.toFloat()..ModulePrefs.CARD_LIFE_MAX_SECONDS.toFloat(),
                summary = "没有单独设置的子功能都用这个时长",
                onValueChange = { state.setDefaultLinger(it.toInt()) },
                onValueChangeFinished = { state.commitDefaultLinger() },
            )
            NoteRow("最短 10 秒、最长 150 秒；单个子功能的时长在它自己的页面里设。")
        }
        group("子功能") {
            IslandFeatures.forEach { feature ->
                LinkRow(
                    title = feature.title,
                    summary = feature.summary,
                    value = if (state.featureOn[feature.key] == true) "已开启" else "已关闭",
                    icon = feature.icon,
                    onClick = { state.page = feature.key },
                )
            }
        }
        openWithSection(browsers, installers, state)
    }
}

private fun LazyListScope.openWithSection(
    browsers: List<Pair<String, String>>?,
    installers: List<Pair<String, String>>?,
    state: CapabilityState,
) {
    group("打开方式") {
        AppPicker("默认浏览器", AstraGlyphs.ExternalApps, browsers, state.browserMode, state.browserPkg) { m, p ->
            state.browserMode = m
            state.browserPkg = p
        }
        AppPicker("默认安装器", AstraGlyphs.Move, installers, state.installerMode, state.installerPkg) { m, p ->
            state.installerMode = m
            state.installerPkg = p
        }
    }
}

/** 应用选择行：「跟随系统」+ 已安装应用列表，弹窗选择后回写模式与包名。 */
@Composable
private fun ColumnScope.AppPicker(
    title: String,
    icon: ImageVector,
    apps: List<Pair<String, String>>?,
    mode: String,
    pkg: String,
    write: (String, String) -> Unit,
) {
    if (apps == null) {
        ValueRow(title = title, value = "读取中…", icon = icon)
        return
    }
    PickerRow(
        title = title,
        options = listOf("跟随系统") + apps.map { it.second },
        selectedIndex = pinnedIndex(mode, pkg, apps.map { it.first }),
        icon = icon,
        onSelect = { index ->
            if (index == 0) write(ModulePrefs.BROWSER_SYSTEM, "") else write(ModulePrefs.BROWSER_PINNED, apps[index - 1].first)
        },
    )
}

@Composable
private fun FeaturePage(context: Context, state: CapabilityState, feature: IslandFeature) {
    val own = state.featureLinger(feature.key)
    val shown = if (own >= 0) own else state.effectiveLinger(feature.key)
    var refresh by remember { mutableIntStateOf(0) }
    val usage = remember(refresh) { ApkIncomingStore.usage(context) }
    var sourceDirsOpen by remember { mutableStateOf(false) }
    AppSubPage(title = feature.title, onBack = { state.page = PAGE_ISLAND }, bottomClearance = TabBar.Clearance) {
        group("开关") {
            SwitchRow(
                title = "启用",
                checked = state.featureOn[feature.key] == true,
                summary = feature.summary,
                icon = feature.icon,
                onCheckedChange = { state.setFeature(feature.key, it) },
            )
        }
        group("显示时长") {
            SliderRow(
                title = "单独显示时长",
                value = shown.toFloat(),
                valueText = when {
                    own < 0 -> "跟随默认（$shown 秒）"
                    else -> "$own 秒"
                },
                valueRange = ModulePrefs.CARD_LIFE_MIN_SECONDS.toFloat()..ModulePrefs.CARD_LIFE_MAX_SECONDS.toFloat(),
                onValueChange = { state.dragFeatureLinger(feature.key, it.toInt()) },
                onValueChangeFinished = {
                    state.commitFeatureLinger(feature.key, state.featureLinger(feature.key))
                },
            )
            ActionRow(
                title = "恢复默认时长",
                enabled = own >= 0,
                onClick = {
                    state.commitFeatureLinger(feature.key, ModulePrefs.LINGER_FOLLOW_DEFAULT)
                },
            )
        }
        if (feature.key == FEATURE_FILE) {
            fileSection(
                context = context,
                state = state,
                count = usage.first,
                bytes = usage.second,
                onChanged = { refresh++ },
                onOpenSourceDirs = { sourceDirsOpen = true },
            )
        }
    }
    if (feature.key == FEATURE_FILE) {
        FileSourcePicker(context = context, state = state, visible = sourceDirsOpen) {
            sourceDirsOpen = it
        }
    }
}

private fun pinnedIndex(mode: String, pkg: String, packages: List<String>): Int {
    if (mode != ModulePrefs.BROWSER_PINNED) return 0
    val index = packages.indexOf(pkg)
    return if (index < 0) 0 else index + 1
}

/** 智能文件专属：来源目录与扫描层级 + 暂存占用 + 立即清理 + 自动清理天数。 */
private fun LazyListScope.fileSection(
    context: Context,
    state: CapabilityState,
    count: Int,
    bytes: Long,
    onChanged: () -> Unit,
    onOpenSourceDirs: () -> Unit,
) {
    group("扫描来源") {
        LinkRow(
            title = "来源目录",
            summary = "只扫描这些目录里新增的安装包",
            value = "${state.sourceDirs.size} 个目录",
            icon = AstraGlyphs.Move,
            onClick = onOpenSourceDirs,
        )
        PickerRow(
            title = "扫描层级",
            options = ScanDepthLabels,
            selectedIndex = scanDepthIndex(state.scanDepth),
            summary = "层级越深扫描越慢；Download 这类目录建议 2 层",
            icon = AstraGlyphs.Sort,
            onSelect = { index -> state.applyScanDepth(ScanDepthOptions[index].second) },
        )
        NoteRow("只提示新增文件，首次扫描只登记；需要 root，目录列表只存本机。")
    }
    group("暂存副本") {
        ValueRow(
            title = "当前占用",
            value = if (count == 0) "暂无" else "$count 个 · ${bytes / 1024 / 1024} MB",
        )
        ActionRow(
            title = "立即清理",
            enabled = count > 0,
            onClick = {
                runCatching { ApkIncomingStore.clearAll(context) }
                onChanged()
            },
        )
        SliderRow(
            title = "自动清理",
            value = state.cleanDays.toFloat(),
            valueText = if (state.cleanDays <= 0) "不自动清理" else "${state.cleanDays} 天",
            valueRange = 0f..ModulePrefs.APK_CLEAN_MAX_DAYS.toFloat(),
            onValueChange = { state.setCleanDays(it.toInt(), commit = false) },
            onValueChangeFinished = { state.setCleanDays(state.cleanDays, commit = true) },
        )
        NoteRow("副本只在提示安装期间保留，删源文件时一起删。")
    }
}

/** 来源目录弹窗 + SAF 选择器：弹窗确认后即拉起系统选择器。 */
@Composable
private fun FileSourcePicker(
    context: Context,
    state: CapabilityState,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
) {
    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dir = treeUriToSourceDir(uri)
        if (dir == null) {
            Toast.makeText(context, UNSUPPORTED_DIR_HINT, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (isInternalStorageRoot(dir)) {
            Toast.makeText(context, STORAGE_ROOT_HINT, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (!state.addSourceDir(dir)) {
            Toast.makeText(context, UNSUPPORTED_DIR_HINT, Toast.LENGTH_SHORT).show()
        }
    }
    if (!visible) return
    Dialog(
        onDismissRequest = { onVisibleChange(false) },
        title = "来源目录",
        confirmAction = CouiDialogAction(text = "完成", onClick = { onVisibleChange(false) }),
        dismissAction = CouiDialogAction(text = "取消", onClick = { onVisibleChange(false) }),
    ) {
        if (state.sourceDirs.isEmpty()) {
            Text(
                text = "还没有来源目录",
                kind = CouiTextKind.ItemSummary,
                color = CouiTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            state.sourceDirs.forEachIndexed { index, dir ->
                Item(
                    onClick = { state.removeSourceDir(dir) },
                    trailing = {
                        Image(
                            imageVector = AstraGlyphs.Close,
                            contentDescription = "移除目录",
                            modifier = Modifier.size(17.dp),
                        )
                    },
                ) {
                    Text(text = displaySourceDir(dir), kind = CouiTextKind.ItemTitle, maxLines = 2)
                }
                if (index < state.sourceDirs.lastIndex) Divider()
            }
        }
        if (state.sourceDirs.isNotEmpty()) Divider()
        Item(
            onClick = {
                onVisibleChange(false)
                directoryPicker.launch(null)
            },
            trailing = {
                Image(
                    imageVector = AstraGlyphs.ChevronRight,
                    contentDescription = "选择目录",
                    modifier = Modifier.size(13.dp),
                )
            },
        ) {
            Text(text = "添加目录", kind = CouiTextKind.ItemTitle, color = CouiTheme.colorScheme.accent)
        }
        Text(
            text = "点按目录即可移除，可随时重新添加。",
            kind = CouiTextKind.ItemSummary,
            color = CouiTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * SAF 目录树 → 内部存储绝对路径。
 * 只认 `primary:` 卷（形如 `primary:Download/xx`）；SD 卡/其它卷或转换失败返回 null，
 * 由调用方提示「暂不支持该位置」。这里不读文件内容，只需要路径给 su 用。
 */
private fun treeUriToSourceDir(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    if (!docId.startsWith(PRIMARY_TREE_PREFIX)) return null
    val relative = docId.substring(PRIMARY_TREE_PREFIX.length).trim('/')
    val root = storageRoot() ?: return null
    return if (relative.isEmpty()) root else "$root/$relative"
}

/** 内部存储根路径：SAF 只给相对 docId，这里取系统的挂载路径 */
@Suppress("DEPRECATION") // 内部存储根没有公开替代（MediaStore 也不给根路径）
private fun storageRoot(): String? =
    runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
