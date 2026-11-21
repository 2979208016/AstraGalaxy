package io.github.proify.astrafirmament.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 自绘图标的一笔:实心形状([evenOdd] 为真时里面套着的形状挖空;[secondary] 为真是后面一层,画得淡一些)。 */
class GlyphFill(val pathData: String, val evenOdd: Boolean = false, val secondary: Boolean = false)

/** 自绘图标的一笔:圆头线条。 */
internal class GlyphStroke(val pathData: String, val width: Float)

/** 把几笔拼成 24×24 的图标;颜色由使用处上色(按钮、方块里的白色,底栏的选中色)。 */
fun drawnGlyph(name: String, vararg parts: Any): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        parts.forEach { part ->
            when (part) {
                is GlyphFill -> addPath(
                    pathData = PathParser().parsePathString(part.pathData).toNodes(),
                    pathFillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = SolidColor(Color.Black),
                    fillAlpha = if (part.secondary) 0.62f else 1f,
                )
                is GlyphStroke -> addPath(
                    pathData = PathParser().parsePathString(part.pathData).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = part.width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                else -> error("不认识的图标笔画：$part")
            }
        }
    }.build()
