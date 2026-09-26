package io.github.proify.lyricon.xinghe.ui.widget

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/**
 * 全站的动效原语。
 *
 * 统一节奏，避免每处各写一套 duration：
 *  - [pressFeedback] 按下去轻微缩一下，松开弹回
 *  - [breathe]       缓慢呼吸（给状态点、光晕用）
 *  - [fadeInUp]      入场：淡入 + 轻微上移
 */
object Motion {

    /** 跟系统的「快速」一致，手感最自然 */
    val EMPHASIS = PathInterpolator(0.2f, 0f, 0f, 1f)

    const val PRESS_SCALE = 0.975f
    const val PRESS_DURATION = 110L
    const val RELEASE_DURATION = 180L

    /**
     * 按压反馈：缩放 + 轻微变暗。
     * 不用涟漪——涟漪在深色毛玻璃上会显脏。
     */
    fun pressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                        .setDuration(PRESS_DURATION)
                        .setInterpolator(EMPHASIS)
                        .start()
                    v.alpha = 0.92f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(RELEASE_DURATION)
                        .setInterpolator(EMPHASIS)
                        .start()
                    v.alpha = 1f
                }
            }
            false
        }
    }

    /**
     * 呼吸：透明度在 0.35~1.0 之间缓慢往复。
     * 给「已生效」的小圆点、顶部光晕用，安静但能让人感觉到「活着」。
     */
    fun breathe(view: View, minAlpha: Float = 0.35f, periodMs: Long = 2200L) {
        val animator = ValueAnimator.ofFloat(minAlpha, 1f).apply {
            duration = periodMs / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = PathInterpolator(0.4f, 0f, 0.6f, 1f)
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
        // 跟视图生命周期绑定，避免页面销毁后还在跑
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = animator.cancel()
        })
    }

    /** 入场：淡入 + 从下方轻微上移。延迟用于列表错落出现。 */
    fun fadeInUp(view: View, delayMs: Long = 0L) {
        view.alpha = 0f
        view.translationY = 18f * view.resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(320)
            .setInterpolator(EMPHASIS)
            .start()
    }

    /** 开关轨道滑动：位置插值，不用「啪」一下 */
    fun animateTrack(knob: View, toX: Float, durationMs: Long = 220L) {
        knob.animate()
            .translationX(toX)
            .setDuration(durationMs)
            .setInterpolator(EMPHASIS)
            .start()
    }
}
