package io.github.proify.lyricon.xinghe.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.proify.lyricon.xinghe.R

/**
 * 界面原子：统一间距、字号、圆角，避免每个页面各写一套。
 * 全部用代码建 View，不引 XML 布局。
 */
class UIKit(private val context: Context) {

    val density: Float get() = context.resources.displayMetrics.density

    fun dp(value: Number): Int = (value.toFloat() * density).toInt()

    fun color(res: Int): Int = context.getColor(res)

    fun text(
        value: CharSequence,
        sizeSp: Float,
        colorRes: Int,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color(colorRes))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    /** 毛玻璃卡片。全站只有这一种卡片材质。 */
    fun glassCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.getDrawable(R.drawable.bg_glass_card)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        clipToOutline = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    fun innerBox(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.getDrawable(R.drawable.bg_glass_inner)
        clipToOutline = true
    }

    /**
     * 通用行：左图标 + 标题/副标题 + 右控件。
     * 可点行带按压缩放；不可点则完全不响应，视觉上更安静。
     */
    fun row(
        iconRes: Int?,
        title: CharSequence,
        subtitle: CharSequence? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(15))
        }
        if (iconRes != null) row.addView(icon(iconRes, R.color.icon))
        row.addView(column(title, subtitle), weight())
        if (onClick != null) {
            row.isClickable = true
            row.background = context.getDrawable(R.drawable.bg_row_press)
            row.setOnClickListener { onClick() }
            Motion.pressFeedback(row)
        }
        return row
    }

    fun column(title: CharSequence, subtitle: CharSequence?): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(text(title, 16f, R.color.text))
            if (!subtitle.isNullOrBlank()) {
                addView(text(subtitle, 12f, R.color.text_sub).apply { setPadding(0, dp(5), 0, 0) })
            }
        }

    fun chevron(): ImageView = icon(R.drawable.ic_chevron, R.color.text_hint)

    fun icon(res: Int, tintRes: Int, sizeDp: Int = 22): ImageView =
        ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color(tintRes))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

    /** 带底色圆角的应用图标位（能力卡用） */
    fun iconBadge(iconRes: Int, tintRes: Int): FrameLayout {
        val badge = FrameLayout(context).apply {
            background = context.getDrawable(R.drawable.bg_glass_inner)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            clipToOutline = true
        }
        badge.addView(
            icon(iconRes, tintRes, 20).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = Gravity.CENTER
                }
            }
        )
        return badge
    }

    fun divider(startInsetDp: Int = 62): View = View(context).apply {
        setBackgroundColor(color(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            marginStart = dp(startInsetDp)
            marginEnd = dp(14)
        }
    }

    fun gap(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    fun pill(label: CharSequence, colorRes: Int = R.color.accent): TextView =
        text(label, 12f, colorRes).apply {
            background = context.getDrawable(R.drawable.bg_pill)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

    /** 呼吸的状态点 */
    fun statusDot(colorRes: Int = R.color.ok): View {
        val dot = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color(colorRes))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        Motion.breathe(dot)
        return dot
    }
}
