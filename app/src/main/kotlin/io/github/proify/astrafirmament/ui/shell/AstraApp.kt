package io.github.proify.astrafirmament.ui.shell

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.layout.Scaffold
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import com.itos.coui.theme.CouiTheme
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs
import io.github.proify.astrafirmament.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * 应用外壳：9.0 起整体换 COUI（凝光组件库，io.github.itoseo:coui）。
 * 结构 = 标签页横向翻页 + 底部悬浮导航：
 *  - API 34+ 用 COUI 凝光底栏（跟手模糊），选中/未选中用 accent/onSurfaceVariant 强对比；
 *  - API 31-33 用纯色回退底栏（凝光底栏需要 Android 14+ 的实时模糊）。
 * 顶部权限中心/开发者页仍是覆盖全屏的推入页（PredictivePageStack）。
 */

object PagerGesture {
    /** 拖动滑条等手势时锁横向翻页，松手解锁。必须是 Compose 状态：普通 var
     * 改动不触发重组，userScrollEnabled 读不到新值，锁就等于没上（9.1 的隐患）。 */
    var locked by mutableStateOf(false)
}

@Composable
fun App(initialTab: Int = 0) {
    AppTheme { AppBody(initialTab) }
}

@Composable
private fun AppBody(initialTab: Int) {
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 2), pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // 推入式覆盖页：权限中心 / 开发者页，盖在标签页上面
    var overlay by remember { mutableStateOf<String?>(null) }

    Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                val tabs = rememberAppTabs()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    CouiBottomBar(
                        tabs = tabs,
                        selectedIndex = pagerState.currentPage,
                        onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                } else {
                    PlainBottomBar(
                        tabs = tabs,
                        selectedIndex = pagerState.currentPage,
                        onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !PagerGesture.locked,
                ) { page ->
                    when (page) {
                        0 -> CapabilityPage()
                        1 -> SettingsPage(
                            onOpenPermission = { overlay = "perm" },
                            onOpenDev = { overlay = "dev" },
                        )
                        else -> AboutPage(onOpenAbout = { overlay = "about" })
                    }
                }
            }
        }
        OverlayHost(overlay, onChange = { overlay = it })
    }

private data class AppTab(val title: String, val icon: ImageVector)

@Composable
private fun rememberAppTabs() = listOf(
    AppTab("能力", AstraGlyphs.ExternalApps),
    AppTab("设置", AstraGlyphs.Gear),
    AppTab("关于", AstraGlyphs.Info),
)

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun CouiBottomBar(
    tabs: List<AppTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val renderer = remember { CouiFloatingRenderer() }
    NavigationBar(
        items = tabs.map { CouiNavigationItem(it.title, it.icon, it.icon) },
        selectedIndex = selectedIndex,
        selectedColor = CouiTheme.colorScheme.accent,
        unselectedColor = CouiTheme.colorScheme.onSurfaceVariant,
        onSelection = onSelect,
        renderer = renderer,
    )
}

/** API 31-33 回退底栏：与凝光底栏同尺寸、同选中色，只是不做实时模糊。 */
@Composable
private fun PlainBottomBar(
    tabs: List<AppTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(72.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val color = if (selected) CouiTheme.colorScheme.accent else CouiTheme.colorScheme.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) }
                    .semantics {
                        role = Role.Tab
                        contentDescription = tab.title
                        this.selected = selected
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    imageVector = tab.icon,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(color),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp),
                )
                BasicText(
                    tab.title,
                    style = TextStyle(color = color, fontSize = 12.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 覆盖页宿主：预测性返回手势下露出下面的标签页。 */
@Composable
private fun OverlayHost(overlay: String?, onChange: (String?) -> Unit) {
    if (overlay == null) return
    PredictivePageStack(
        canGoBack = true,
        onBackGesture = { onChange(null) },
        parent = {
            Box(Modifier.fillMaxSize().background(CouiTheme.colorScheme.background)) {
                when (overlay) {
                    "about" -> AboutPage(onOpenAbout = {})
                    "perm" -> SettingsPage(onOpenPermission = {}, onOpenDev = { onChange("dev") })
                    else -> SettingsPage(onOpenPermission = { onChange("perm") }, onOpenDev = {})
                }
            }
        },
        child = {
            Box(Modifier.fillMaxSize().background(CouiTheme.colorScheme.background)) {
                when (overlay) {
                    "about" -> AboutProjectPage(onBack = { onChange(null) })
                    "perm" -> PermissionCenterPage(onBack = { onChange(null) })
                    else -> DevPage(onBack = { onChange(null) })
                }
            }
        },
    )
}
