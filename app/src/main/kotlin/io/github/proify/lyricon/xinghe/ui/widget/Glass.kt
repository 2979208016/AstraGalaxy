package io.github.proify.lyricon.xinghe.ui.widget

import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

/**
 * 全站统一的「毛玻璃」视觉。
 *
 * 只此一种材质，不许混搭：半透明底 + 高斯模糊 + 极淡描边 + 大圆角。
 * 具体参数集中在这里，改一处全站一致。
 */
object Glass {

    /** 卡片圆角 */
    const val RADIUS_CARD = 22f

    /** 内部元素圆角 */
    const val RADIUS_INNER = 16f

    /** 胶囊圆角 */
    const val RADIUS_PILL = 100f

    /** 模糊半径（dp）；越大越糊，这里是「看得清轮廓但明显糊掉」的档位 */
    const val BLUR_RADIUS = 18f

    /**
     * 给任意 View 套上毛玻璃。
     *
     * Android 12+ 用系统 RenderEffect 真模糊；低于 12 时保持半透明底，
     * 退化成「半透明卡片」而不去模拟假模糊——统一性优先。
     */
    fun applyBlur(view: View, radiusDp: Float = BLUR_RADIUS) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = radiusDp * view.resources.displayMetrics.density
        runCatching {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        }
    }

    /**
     * 圆角裁剪。带描边的容器想要模糊内容不外溢，就得裁。
     */
    fun clipRounded(view: View, radiusDp: Float) {
        val radius = radiusDp * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
    }
}
