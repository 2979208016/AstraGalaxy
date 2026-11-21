package io.github.proify.astrafirmament.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.Dialog
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.slider.Slider
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.theme.CouiTheme
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs

/**
 * 页面行组件：全部基于 COUI 的 Group / Item / Switch / Slider / Dialog。
 * 9.0 起替代旧 hig 的 astraSection/astraLink/astraSwitch 等一套。
 * 调用面保持 LazyListScope + 组内行的写法，与旧 DSL 一一对应。
 */

/** 一个分组卡片（带组标题，可选组尾说明）。 */
fun LazyListScope.group(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    item {
        Group(title = title, footer = footer, content = content)
    }
}

/** 开关行。 */
@Composable
fun ColumnScope.SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Item(
        icon = icon?.let { { RowIcon(it) } },
        summary = summary?.let { { SummaryText(it) } },
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
    ) { TitleText(title, enabled = enabled) }
}

/** 链接行：标题 + 可选说明 + 可选当前值 + 右箭头，整行可点。 */
@Composable
fun ColumnScope.LinkRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Item(
        icon = icon?.let { { RowIcon(it) } },
        summary = summary?.let { { SummaryText(it) } },
        enabled = enabled,
        onClick = onClick,
        trailing = { TrailingValue(value, showChevron = true) },
    ) { TitleText(title, enabled = enabled) }
}

/** 纯展示行：标题 + 当前值。 */
@Composable
fun ColumnScope.ValueRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
) {
    Item(
        icon = icon?.let { { RowIcon(it) } },
        trailing = { TrailingValue(value) },
    ) { TitleText(title) }
}

/** 动作行：标题（危险操作红色），整行可点。 */
@Composable
fun ColumnScope.ActionRow(
    title: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    value: String? = null,
) {
    Item(
        enabled = enabled,
        onClick = onClick,
        trailing = { TrailingValue(value) },
    ) {
        TitleText(title, color = if (destructive) destructiveColor() else null, enabled = enabled)
    }
}

/**
 * 滑条行：标题 + 当前值 + 整宽滑条。
 * 拖动期间锁住标签页横向翻页手势（松手解锁），避免拉滑条变成切页。
 *
 * 拖动期间滑条吃自己发出的原始浮点值（[dragValue]），不取整回传：
 * 调用方普遍把 onValueChange 的值 toInt 后再喂回 value，COUI Slider 的
 * syncValue 会把这个取整回环当成外部改值，cancelGesture 掐断进行中的
 * 拖动手势——拇指卡住、弹簧反复重启（9.1 用户反馈的拉条顿挫），松手的
 * onValueChangeFinished 也随之丢失（9.2 修复：松手才清草稿回父值）。
 */
@Composable
fun ColumnScope.SliderRow(
    title: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    summary: String? = null,
) {
    // 拖动中的原始浮点值（null = 未拖动）：原样回喂滑条，syncValue 看到的就是
    // 它自己刚发出的值，不再误杀手势；标签仍经父级 onValueChange 随取整值刷新。
    var dragValue by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        onDispose { PagerGesture.locked = false }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, kind = CouiTextKind.ItemTitle, modifier = Modifier.weight(1f), maxLines = 1)
            Text(text = valueText, kind = CouiTextKind.ItemValue, color = CouiTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        summary?.let { SummaryText(it) }
        Slider(
            modifier = Modifier.fillMaxWidth(),
            value = dragValue ?: value,
            onValueChange = { v ->
                // 拖动产生首次数值变化时锁住翻页，松手解锁；锁定状态整个
                // 拖动周期保持稳定，避免翻页器在手势中途反复重组把数值拉脱。
                if (!PagerGesture.locked) PagerGesture.locked = true
                dragValue = v
                onValueChange(v)
            },
            valueRange = valueRange,
            onValueChangeFinished = {
                PagerGesture.locked = false
                dragValue = null
                onValueChangeFinished()
            },
        )
    }
}

/**
 * 选择行：点按弹出底部选择弹窗（选项带强调色高亮，超长可滚动）。
 * 统一用弹窗而不按选项数量分叉——选项可能很多（如已装浏览器列表）。
 */
@Composable
fun ColumnScope.PickerRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    Item(
        icon = icon?.let { { RowIcon(it) } },
        summary = summary?.let { { SummaryText(it) } },
        onClick = { open = true },
        trailing = { TrailingValue(options.getOrNull(selectedIndex).orEmpty(), showChevron = true) },
    ) {
        TitleText(title)
    }
    if (open) {
        Dialog(
            onDismissRequest = { open = false },
            title = title,
            confirmAction = CouiDialogAction(text = "完成", onClick = { open = false }),
            dismissAction = CouiDialogAction(text = "取消", onClick = { open = false }),
        ) {
            options.forEachIndexed { index, label ->
                Item(
                    onClick = {
                        onSelect(index)
                        open = false
                    },
                ) {
                    Text(
                        text = label,
                        kind = CouiTextKind.ItemTitle,
                        color = if (index == selectedIndex) CouiTheme.colorScheme.accent else CouiTheme.colorScheme.onSurface,
                        maxLines = 2,
                    )
                }
                if (index < options.lastIndex) Divider()
            }
        }
    }
}

/** 组内的说明文字（灰色小字，留在卡片里）。 */
@Composable
fun ColumnScope.NoteRow(text: String) {
    Text(
        text = text,
        kind = CouiTextKind.ItemSummary,
        color = CouiTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 危险动作的红字：COUI 色板没有语义化 error 色，这里给一对深浅色值。 */
@Composable
private fun destructiveColor(): Color =
    if (CouiTheme.isDark) Color(0xFFFF6B62) else Color(0xFFD92D20)

// ── 内部小件 ──

@Composable
private fun RowIcon(icon: ImageVector) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        colorFilter = ColorFilter.tint(CouiTheme.colorScheme.accent),
    )
}

@Composable
private fun TitleText(title: String, color: Color? = null, enabled: Boolean = true) {
    Text(
        text = title,
        kind = CouiTextKind.ItemTitle,
        color = (color ?: CouiTheme.colorScheme.onSurface).let { if (enabled) it else it.copy(alpha = 0.38f) },
        maxLines = 2,
    )
}

@Composable
private fun SummaryText(summary: String) {
    Text(text = summary, kind = CouiTextKind.ItemSummary, color = CouiTheme.colorScheme.onSurfaceVariant, maxLines = 3)
}

@Composable
private fun TrailingValue(value: String?, showChevron: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!value.isNullOrEmpty()) {
            Text(text = value, kind = CouiTextKind.ItemValue, color = CouiTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (showChevron) {
            Image(
                imageVector = AstraGlyphs.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                colorFilter = ColorFilter.tint(CouiTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
