package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 评论行上的动作图标.
 *
 * 引用 and 点赞 used to be the words themselves, which made a comment's meta line
 * read as a sentence with two verbs buried in it. Drawn on the same 24-unit grid
 * as the bottom bar's [NavIcon] and stroked in the caller's tint, so the two sets
 * stay one family. material-icons is not on the classpath and three paths are
 * cheaper than adding it.
 *
 * [ActionGlyph.HEART] fills rather than strokes once it is on, because 已赞 has to
 * be readable at 15dp where a stroke-weight change is not.
 */

enum class ActionGlyph { HEART, QUOTE, COIN }

private const val VIEW = 24f

@Composable
fun ActionIcon(
    glyph: ActionGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / VIEW
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            ActionGlyph.HEART -> heart(tint, u, stroke, filled)
            ActionGlyph.QUOTE -> quote(tint, u, stroke)
            ActionGlyph.COIN -> coin(tint, u, stroke, filled)
        }
    }
}

/**
 * 点赞: a heart.
 *
 * Two arcs off a single apex rather than cubics - at 15dp the curve reads the same
 * and the outline stays symmetric, which a hand-fitted bezier does not.
 */
private fun DrawScope.heart(tint: Color, u: Float, stroke: Stroke, filled: Boolean) {
    val path = Path().apply {
        moveTo(12f * u, 20.2f * u)
        // Left lobe, up to the notch at the top centre.
        cubicTo(3.4f * u, 14.6f * u, 2.4f * u, 9.5f * u, 5.6f * u, 6.7f * u)
        cubicTo(8.2f * u, 4.4f * u, 11f * u, 5.6f * u, 12f * u, 8.1f * u)
        // Right lobe, mirrored.
        cubicTo(13f * u, 5.6f * u, 15.8f * u, 4.4f * u, 18.4f * u, 6.7f * u)
        cubicTo(21.6f * u, 9.5f * u, 20.6f * u, 14.6f * u, 12f * u, 20.2f * u)
        close()
    }
    drawPath(path, color = tint, style = if (filled) Fill else stroke)
}

/**
 * 引用: a quotation mark pair.
 *
 * Not a speech bubble - a bubble is what 回复 would be, and these two actions sit
 * next to each other, so they must not look like the same thing.
 */
private fun DrawScope.quote(tint: Color, u: Float, stroke: Stroke) {
    // Each mark is an open hook: down the left, across the bottom, back up.
    fun hook(x: Float) = Path().apply {
        moveTo((x + 5.2f) * u, 7.3f * u)
        lineTo((x + 1.6f) * u, 7.3f * u)
        cubicTo(
            x * u, 7.3f * u,
            x * u, 9.4f * u,
            x * u, 10.6f * u
        )
        lineTo(x * u, 15.4f * u)
        lineTo((x + 5.2f) * u, 15.4f * u)
        lineTo((x + 5.2f) * u, 10.2f * u)
    }
    drawPath(hook(4.4f), color = tint, style = stroke)
    drawPath(hook(13.2f), color = tint, style = stroke)
}

/** 打赏: a coin - ring plus a bar, so it is not mistaken for the 发现 compass. */
private fun DrawScope.coin(tint: Color, u: Float, stroke: Stroke, filled: Boolean) {
    drawCircle(
        color = tint,
        radius = 8.2f * u,
        center = Offset(12f * u, 12f * u),
        style = if (filled) Fill else stroke
    )
    if (!filled) {
        drawPath(
            Path().apply {
                moveTo(9.2f * u, 9.4f * u)
                lineTo(14.8f * u, 9.4f * u)
                moveTo(12f * u, 9.4f * u)
                lineTo(12f * u, 15.6f * u)
                moveTo(9.4f * u, 12.6f * u)
                lineTo(14.6f * u, 12.6f * u)
            },
            color = tint,
            style = stroke
        )
    }
}
