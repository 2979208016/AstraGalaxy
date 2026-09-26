package io.github.proify.lyricon.xinghe.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.proify.lyricon.xinghe.R

/**
 * 自绘开关。
 *
 * 不用系统 Switch：它自带 Material 的绿色轨道和方形滑块，跟这套毛玻璃配色放一起很出戏。
 * 视觉：关是深色轨道 + 灰点，开是强调色轨道 + 白点，切换时平滑滑动。
 */
class GlassSwitch(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private var progress = 0f
    private var checkedState = false
    private var anim: ValueAnimator? = null
    private val density = resources.displayMetrics.density

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private fun dp(v: Number) = v.toFloat() * density

    init {
        layoutParams = ViewGroup.LayoutParams(dp(46).toInt(), dp(28).toInt())
        isClickable = true
        isFocusable = true
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (checkedState == value) return
        checkedState = value
        animateTo(if (value) 1f else 0f, animate)
    }

    fun isCheckedState(): Boolean = checkedState

    private fun animateTo(target: Float, animate: Boolean) {
        anim?.cancel()
        if (!animate) {
            progress = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = 220
            interpolator = Motion.EMPHASIS
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = dp(1)
        trackRect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = trackRect.height() / 2f

        val off = context.getColor(R.color.glass_inner)
        val on = context.getColor(R.color.accent_soft)
        trackPaint.color = blend(off, on, progress)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        if (progress > 0.01f) {
            trackPaint.style = Paint.Style.STROKE
            trackPaint.strokeWidth = dp(1)
            trackPaint.color = blend(
                Color.TRANSPARENT,
                context.getColor(R.color.accent),
                progress * 0.7f
            )
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
            trackPaint.style = Paint.Style.FILL
        }

        val knobRadius = radius - dp(3)
        val minX = trackRect.left + dp(3) + knobRadius
        val maxX = trackRect.right - dp(3) - knobRadius
        val cx = minX + (maxX - minX) * progress
        val cy = trackRect.centerY()

        knobPaint.color = blend(context.getColor(R.color.text_hint), Color.WHITE, progress)
        canvas.drawCircle(cx, cy, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                return true
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                setChecked(!checkedState)
                onCheckedChange?.invoke(checkedState)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt()
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }
}
