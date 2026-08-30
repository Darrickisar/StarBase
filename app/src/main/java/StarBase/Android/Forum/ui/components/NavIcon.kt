package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Bottom-bar icons.
 *
 * The tabs used to be single CJK characters (饼 板 探 我) set in the label font,
 * which made the bar read as five lines of text stacked on two rows. These are
 * drawn instead: four line glyphs on a 24-unit grid, stroked in the tab's own
 * tint so 选中/未选中 still comes from colour, with the stroke a touch heavier when
 * selected. No new dependency - material-icons is not on the classpath and a
 * handful of paths is cheaper than adding it.
 */

/** The four shapes the bottom bar needs. */
enum class NavGlyph { HOME, BOARDS, COMPASS, PERSON }

/** Grid the paths below are written against; everything scales off it. */
private const val VIEW = 24f

@Composable
fun NavIcon(
    glyph: NavGlyph,
    tint: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / VIEW
        val stroke = Stroke(
            width = (if (selected) 1.95f else 1.6f) * u,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        when (glyph) {
            NavGlyph.HOME -> house(tint, u, stroke)
            NavGlyph.BOARDS -> boards(tint, u, stroke)
            NavGlyph.COMPASS -> compass(tint, u, stroke)
            NavGlyph.PERSON -> person(tint, u, stroke)
        }
    }
}

/** 首页: roof, walls, door. */
private fun DrawScope.house(tint: Color, u: Float, stroke: Stroke) {
    drawPath(
        Path().apply {
            moveTo(3.5f * u, 10.7f * u)
            lineTo(12f * u, 3.6f * u)
            lineTo(20.5f * u, 10.7f * u)
            moveTo(5.9f * u, 9.6f * u)
            lineTo(5.9f * u, 20.2f * u)
            lineTo(18.1f * u, 20.2f * u)
            lineTo(18.1f * u, 9.6f * u)
            moveTo(9.9f * u, 20.2f * u)
            lineTo(9.9f * u, 14.3f * u)
            lineTo(14.1f * u, 14.3f * u)
            lineTo(14.1f * u, 20.2f * u)
        },
        color = tint,
        style = stroke
    )
}

/** 板块: a 2 x 2 grid, the same shape the 板块 list draws its cards in. */
private fun DrawScope.boards(tint: Color, u: Float, stroke: Stroke) {
    val side = 7.4f * u
    val step = side + 1.8f * u
    val origin = 3.7f * u
    for (row in 0..1) {
        for (col in 0..1) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(origin + col * step, origin + row * step),
                size = Size(side, side),
                cornerRadius = CornerRadius(2.1f * u),
                style = stroke
            )
        }
    }
}

/** 发现: a compass - ring plus a filled needle, which stays legible at 21dp. */
private fun DrawScope.compass(tint: Color, u: Float, stroke: Stroke) {
    drawCircle(color = tint, radius = 8.6f * u, center = Offset(12f * u, 12f * u), style = stroke)
    drawPath(
        Path().apply {
            moveTo(15.9f * u, 8.1f * u)
            lineTo(13.3f * u, 13.3f * u)
            lineTo(8.1f * u, 15.9f * u)
            lineTo(10.7f * u, 10.7f * u)
            close()
        },
        color = tint
    )
}

/** 我的: head and shoulders, the same silhouette UserAvatar falls back to. */
private fun DrawScope.person(tint: Color, u: Float, stroke: Stroke) {
    drawCircle(color = tint, radius = 3.4f * u, center = Offset(12f * u, 8.3f * u), style = stroke)
    drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(4.8f * u, 13.4f * u),
        size = Size(14.4f * u, 13.2f * u),
        style = stroke
    )
}
