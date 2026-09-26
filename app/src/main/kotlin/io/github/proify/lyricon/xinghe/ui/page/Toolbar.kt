package io.github.proify.lyricon.xinghe.ui.page

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.proify.lyricon.xinghe.R
import io.github.proify.lyricon.xinghe.ui.widget.Motion
import io.github.proify.lyricon.xinghe.ui.widget.UIKit

/**
 * 详情页的顶部标题栏。
 * 左返回、中标题，底部一条极淡分隔线；返回按钮自带按压缩放。
 */
object Toolbar {

    fun build(activity: BaseActivity, ui: UIKit, title: String, onBack: () -> Unit): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(14), ui.dp(18), ui.dp(14))
        }

        val back = android.widget.FrameLayout(activity).apply {
            background = activity.getDrawable(R.drawable.bg_row_press)
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
            isClickable = true
            setOnClickListener { onBack() }
            clipToOutline = true
        }
        back.addView(
            ui.icon(R.drawable.ic_chevron, R.color.text, 22).apply {
                rotation = 180f
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ui.dp(22), ui.dp(22)
                ).apply { gravity = Gravity.CENTER }
            }
        )
        Motion.pressFeedback(back)
        bar.addView(back)

        val label: TextView = ui.text(title, 18f, R.color.text, bold = true).apply {
            setPadding(ui.dp(12), 0, 0, 0)
        }
        bar.addView(label, ui.weight())

        return bar
    }
}
