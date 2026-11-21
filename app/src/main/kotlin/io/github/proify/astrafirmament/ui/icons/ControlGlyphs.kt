package io.github.proify.astrafirmament.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 控件里用到的小记号图标（描边风格，24×24 视口），自绘以免依赖外部图标库。
 * 全部走字符串 pathData（[drawnGlyph]），与 [AstraDrawnGlyphs] 同一套解析方式。
 */
internal object ControlGlyphs {

    val ChevronRight: ImageVector by lazy {
        strokeGlyph("ChevronRight", "M9 6l6 6-6 6")
    }

    val ChevronLeft: ImageVector by lazy {
        strokeGlyph("ChevronLeft", "M15 6l-6 6 6 6")
    }

    val Close: ImageVector by lazy {
        strokeGlyph("Close", "M6 6l12 12M18 6L6 18")
    }

    val Check: ImageVector by lazy {
        strokeGlyph("Check", "M5 12.5l4.5 4.5L19 7")
    }

    /** 上下箭头（排序/层级）。 */
    val Sort: ImageVector by lazy {
        strokeGlyph("Sort", "M8 5l-3.5 4h7zM16 19l3.5-4h-7z")
    }

    /** WiFi：三层弧线 + 实心圆点。 */
    val Wifi: ImageVector by lazy {
        drawnGlyph(
            "Wifi",
            GlyphStroke("M4.2 9.6a11 11 0 0 1 15.6 0", 1.8f),
            GlyphStroke("M7.2 12.9a6.8 6.8 0 0 1 9.6 0", 1.8f),
            GlyphStroke("M10.2 16.2a2.6 2.6 0 0 1 3.6 0", 1.8f),
            GlyphFill("M12 18.05a1.35 1.35 0 1 0 0.001 0z"),
        )
    }

    val Bluetooth: ImageVector by lazy {
        strokeGlyph("Bluetooth", "M7 7l10 10-5 4V3l5 4L7 17")
    }

    /** 闭眼：眼眶两道弧线 + 斜杠。 */
    val PasswordHidden: ImageVector by lazy {
        strokeGlyph(
            "PasswordHidden",
            "M3 3l18 18M10.5 6.1a9.7 9.7 0 0 1 1.5-0.1 9.9 9.9 0 0 1 8.2 4.4 10.4 10.4 0 0 1-3 3.4M6.4 7.6a10.1 10.1 0 0 0-3.6 3.7 9.9 9.9 0 0 0 8.2 4.4 9.6 9.6 0 0 0 1.8-0.2",
        )
    }

    /** 描边小记号：统一 1.8 圆头圆角描边。 */
    private fun strokeGlyph(name: String, pathData: String): ImageVector =
        drawnGlyph(name, GlyphStroke(pathData, 1.8f))
}
