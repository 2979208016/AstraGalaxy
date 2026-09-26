package io.github.proify.lyricon.xinghe.ui.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.proify.lyricon.xinghe.R

/**
 * 能力卡：首页的主角。
 *
 * 一张卡 = 一个能力。左侧图标，中间名字和一行状态，右侧开关。
 * 开关关掉时整卡降透明度，让「没开」这件事一眼可见；
 * 点卡片任意处进详情，点开关只切开关。
 *
 * 右下角有一个箭头：卡片能点开二级界面这件事不写出来用户不会知道，
 * 箭头是全站统一的「这里还能进去」的记号（和设置页的行一致）。
 */
class CapabilityCard(
    private val ui: UIKit,
    private val context: Context
) {

    class Handle(
        val root: LinearLayout,
        val switch: GlassSwitch,
        val statusText: TextView,
        val content: LinearLayout
    )

    fun build(
        iconRes: Int,
        tintRes: Int,
        title: CharSequence,
        subtitle: CharSequence,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
        onOpen: () -> Unit
    ): Handle {
        val card = ui.glassCard()
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        head.addView(ui.iconBadge(iconRes, tintRes))

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), 0, ui.dp(10), 0)
        }
        val status = ui.text(subtitle, 12f, R.color.text_sub)
        col.addView(ui.text(title, 16.5f, R.color.text, bold = true))
        col.addView(status.apply { setPadding(0, ui.dp(5), 0, 0) })
        head.addView(col, ui.weight())

        val switch = GlassSwitch(context).apply {
            setChecked(checked, animate = false)
            onCheckedChange = { value ->
                onToggle(value)
                applyDim(card, value)
            }
        }
        head.addView(switch)

        card.addView(head)

        // 卡片内容区：之后各能力可以往里塞自己的东西
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(content)

        // 进详情的引导：一行小字 + 箭头，和开关在同一视觉层级之下
        card.addView(buildOpenHint())

        // 点卡片进详情（点开关不会触发，因为开关自己消费了触摸）
        card.isClickable = true
        card.background.level = 0
        card.setOnClickListener { onOpen() }
        Motion.pressFeedback(card)

        applyDim(card, checked)

        return Handle(card, switch, status, content)
    }

    /** 右下角的「还能进去」提示：小字 + 箭头，低调但看得见 */
    private fun buildOpenHint(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(0, ui.dp(10), ui.dp(2), 0)

        addView(
            ui.text(context.getString(R.string.ui_card_tap_hint), 11.5f, R.color.text_hint)
        )
        addView(ui.chevron().apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply {
                marginStart = ui.dp(2)
            }
        })
    }

    /** 关掉的能力整卡压暗，但仍然可点（用户还能进去看说明并打开） */
    private fun applyDim(card: View, enabled: Boolean) {
        card.animate()
            .alpha(if (enabled) 1f else 0.55f)
            .setDuration(180)
            .start()
    }
}
