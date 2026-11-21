package io.github.proify.astrafirmament.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.itos.coui.theme.CouiTheme
import kotlinx.coroutines.CancellationException

/**
 * 预测性返回的页面栈：**下一级页面盖在上一级页面上面**，系统返回手势拉起时
 * 上层页面按手势进度**纯平移**右移，一点一点「露出」下面那一级页面——
 * 拉多少露多少（1:1 跟手，没有淡入淡出、也没有缩放），中途反悔往回拉页面就跟着回来。
 *
 * 只做平移的理由（用户 2026-11-02 指定）：淡入淡出与缩放会让「连续感」断掉，
 * 拖到一半改变主意时画面还会闪一下；纯位移才是真正跟手、可打断的反馈。
 *
 * 页面按槽位固定分层（最底层 / 第二层 / 第三层），**只有最上面那一层跟手平移**。
 * 以前只有 parent / child 两个槽，退栈时要把「原来的 parent」搬到 child 槽去渲染，
 * 组件换到另一个组合位置就等于整页重新组合：LazyColumn 回顶、异步读到的列表又变回
 * 「读取中…」，松手瞬间整屏跳一下（7.10 修的侧滑返回跳闪）。槽位固定后，退栈只是
 * 最上面那层消失，下面那页一直在原来的槽里，滚动位置与已读到的数据都不会丢。
 *
 * 位移全程由同一个 [Animatable] 驱动：拖动 snapTo 跟手、提交从手指松开的位置
 * 顺势滑出再退栈、取消弹簧归位。9.1 的写法是提交后切到另一个从 0 起播的退出动画，
 * 顶层先瞬间跳回全屏盖一帧再滑出——页面越重（智能上岛的功能列表）这一帧越明显，
 * 看起来就是「先闪回来一下，再返回」（9.2 修复）。
 *
 * @param canGoBack 当前是否在二级/三级页（false 时不接管返回键，交给系统）
 * @param onBackGesture 手势完成后的真正退栈动作
 * @param parent 最底层页面（首页）
 * @param child 第二层页面（二级页）；null 表示还没有进二级页
 * @param top 第三层页面（三级页）；null 表示不在三级页
 */
@Composable
fun PredictivePageStack(
    canGoBack: Boolean,
    onBackGesture: () -> Unit,
    parent: @Composable () -> Unit,
    child: (@Composable () -> Unit)? = null,
    top: (@Composable () -> Unit)? = null,
) {
    val offset = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = canGoBack) { flow ->
        try {
            flow.collect { backEvent -> offset.snapTo(backEvent.progress) }
            // 手势提交：从手指松开的位置继续滑出屏幕，滑完再退栈
            offset.animateTo(1f, tween(durationMillis = 180, easing = FastOutLinearInEasing))
            onBackGesture()
            // 退栈后下一层成为顶层，位移归零等下一次手势
            offset.snapTo(0f)
        } catch (e: CancellationException) {
            // 手势中途取消：弹簧回弹归位
            offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    val second = child
    val third = top
    // 只有最上面那一层跟手平移；下面几层停在 0，露出的那半屏才是静止的父页
    val topIndex = when {
        third != null -> 2
        second != null -> 1
        else -> 0
    }
    Box(Modifier.fillMaxSize()) {
        // 层的位置按槽位固定：退栈只是上面那层消失，不会把下面那页换到别的槽位重新组合
        StackLayer(offset, isTop = topIndex == 0) { parent() }
        if (second != null) StackLayer(offset, isTop = topIndex == 1) { second() }
        if (third != null) StackLayer(offset, isTop = topIndex == 2) { third() }
    }
}

/**
 * 一层页面：先建图层再铺底。修饰符从左到右是「外→内」，如果 background 写在 graphicsLayer
 * 前面，底色就落在图层外面、不跟着平移，会把底下那一级页面盖成一片纯黑（7.7 第一版的 bug）。
 *
 * 位移在 graphicsLayer 块里读：手势每帧只更新图层平移，不触发整页重组。
 */
@Composable
private fun StackLayer(offset: Animatable<Float, *>, isTop: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 纯平移、1:1 跟手：finger 拖多少，页面就右移多少
                translationX = if (isTop) size.width * offset.value else 0f
                clip = true
            }
            // 页面自身是透明的：不铺底就会让下面那一级的内容透上来，看起来像两层文字叠在一起
            .background(CouiTheme.colorScheme.background),
    ) { content() }
}
