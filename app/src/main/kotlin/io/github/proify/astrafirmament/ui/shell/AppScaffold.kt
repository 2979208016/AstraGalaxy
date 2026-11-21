package io.github.proify.astrafirmament.ui.shell

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Scaffold
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.LargeTopAppBar
import com.itos.coui.topappbar.TopAppBar
import com.itos.coui.topappbar.TopAppBarButton
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs

/** 悬浮底栏在屏幕底部占用的可视高度：页面正文要留出这段距离，内容才不钻进底栏下面。 */
object TabBar {
    val Clearance = 108.dp
}

/**
 * 页面骨架：全部页面统一走 [AppTabPage] / [AppSubPage] 两个入口，
 * 结构 = COUI Scaffold + 顶栏 + LazyColumn。9.0 起替代旧 hig 骨架。
 */

/** 一级标签页：大标题顶栏 + 列表正文。 */
@Composable
fun AppTabPage(
    title: String,
    subtitle: String? = null,
    bottomClearance: Dp = TabBar.Clearance,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = title, kind = CouiTextKind.HomeTitle, maxLines = 1) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentPadding = PaddingValues(bottom = bottomClearance + 28.dp),
        ) {
            subtitle?.let { item(key = "subtitle") { PageSubtitle(it) } }
            content()
        }
    }
}

/** 二级/三级子页：小标题顶栏 + 返回按钮 + 列表正文。 */
@Composable
fun AppSubPage(
    title: String,
    onBack: () -> Unit,
    bottomClearance: Dp = 0.dp,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = title, kind = CouiTextKind.TopBar, maxLines = 1) },
                navigationIcon = { AppBackButton(onBack) },
                actions = { actions?.invoke(this) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentPadding = PaddingValues(bottom = bottomClearance + 28.dp),
        ) { content() }
    }
}

/** 标签页大标题下面的一行说明文字。 */
@Composable
private fun PageSubtitle(text: String) {
    Text(
        text = text,
        kind = CouiTextKind.ItemSummary,
        color = CouiTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
    )
}

/**
 * 返回按钮：API 34+ 用 COUI 顶栏凝光按钮，31-33 用普通可点图形。
 * 图形都是同一枚自绘左箭头。
 */
@Composable
private fun AppBackButton(onBack: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        CouiBackButton(onBack)
    } else {
        Image(
            imageVector = AstraGlyphs.ChevronLeft,
            contentDescription = "返回",
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onBack)
                .padding(10.dp),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun CouiBackButton(onBack: () -> Unit) {
    TopAppBarButton(onClick = onBack) {
        Image(
            imageVector = AstraGlyphs.ChevronLeft,
            contentDescription = "返回",
            modifier = Modifier.size(20.dp),
        )
    }
}
