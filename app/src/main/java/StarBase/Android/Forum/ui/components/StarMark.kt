package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * The StarBase mark, drawn rather than set in a font.
 *
 * Every in-app brand spot used to be the character 「饼」 - a leftover from when the
 * app was named after the site. The launcher icon is now logo.png (四角星 + 浅弧 +
 * 倾斜轨道, see docs/logo-prompt.md), so these spots draw the same three shapes at
 * the same proportions instead. Coordinates below are measured off logo.png and
 * written against a 24-unit grid, the way NavIcon.kt is.
 */

/** Grid the coordinates are written against. */
private const val VIEW = 24f

// 星: centre, the four tips, and how full the concave sides are.
private const val STAR_CX = 12f
private const val STAR_CY = 11.7f
private const val STAR_TOP = 4.3f
private const val STAR_BOTTOM = 18.7f
private const val STAR_HALF_W = 5.7f
private const val WAIST = 0.2f

// 轨道: a shallow tilted ellipse, sized so the tilt still fits the grid.
private const val ORBIT_CX = 12.2f
private const val ORBIT_CY = 12.9f
private const val ORBIT_RX = 9.6f
private const val ORBIT_RY = 2.9f
private const val ORBIT_TILT = -17f

// 弧: the horizon the star sits on, apex meeting the star's bottom tip.
private const val ARC_LEFT = 2.6f
private const val ARC_RIGHT = 21.4f
private const val ARC_Y = 20.6f
private const val ARC_CTRL_Y = 16.6f

/** Pinpoints in the upper half. Only drawn when there is room for them. */
private val pinpoints = listOf(
    8.6f to 4.6f, 15.2f to 3.9f, 19.8f to 6.2f, 5.4f to 8.0f, 16.4f to 7.9f
)

/**
 * The mark on its own, transparent behind it.
 *
 * @param tint the star and the horizon. Defaults to the warm accent glow.
 * @param orbitTint the orbit line. Defaults to the cool room light, which is where
 *   logo.png's `#7FA0CC` orbit comes from.
 */
@Composable
fun StarMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    orbitTint: Color = Color.Unspecified
) {
    val tokens = LocalTokens.current
    val warm = if (tint == Color.Unspecified) tokens.accentGlow else tint
    val cool = if (orbitTint == Color.Unspecified) tokens.roomCool.copy(alpha = 0.85f) else orbitTint
    // Detail drops out as the mark shrinks: below ~30dp the pinpoints land on
    // half a pixel and read as noise, and below ~28dp the orbit stroke is thinner
    // than a pixel and smears into the star. 星 + 弧 always survive.
    val pins = size >= 30.dp
    val orbit = size >= 28.dp
    Canvas(modifier = modifier.size(size)) {
        drawMark(warm = warm, cool = cool, orbit = orbit, pins = pins)
    }
}

private fun DrawScope.drawMark(warm: Color, cool: Color, orbit: Boolean, pins: Boolean) {
    val u = size.minDimension / VIEW

    if (pins) {
        pinpoints.forEach { (x, y) ->
            drawCircle(color = warm.copy(alpha = 0.5f), radius = 0.34f * u, center = Offset(x * u, y * u))
        }
    }

    // 弧 first: the orbit crosses in front of it, the star sits on it.
    val horizon = Path().apply {
        moveTo(ARC_LEFT * u, ARC_Y * u)
        quadraticTo(STAR_CX * u, ARC_CTRL_Y * u, ARC_RIGHT * u, ARC_Y * u)
    }
    drawPath(
        path = horizon,
        color = warm.copy(alpha = 0.72f),
        style = Stroke(width = 1.15f * u, cap = StrokeCap.Round)
    )

    // 轨道: drawn whole, then the star is filled over it, so it reads as passing
    // behind the star without needing two arc segments.
    if (orbit) {
        rotate(degrees = ORBIT_TILT, pivot = Offset(ORBIT_CX * u, ORBIT_CY * u)) {
            drawOval(
                color = cool,
                topLeft = Offset((ORBIT_CX - ORBIT_RX) * u, (ORBIT_CY - ORBIT_RY) * u),
                size = Size(2 * ORBIT_RX * u, 2 * ORBIT_RY * u),
                style = Stroke(width = 0.62f * u, cap = StrokeCap.Round)
            )
        }
    }

    // The bloom where the star's bottom tip meets the arc.
    val bloom = 3.4f * u
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(warm.copy(alpha = 0.45f), Color.Transparent),
            center = Offset(STAR_CX * u, STAR_BOTTOM * u),
            radius = bloom
        ),
        radius = bloom,
        center = Offset(STAR_CX * u, STAR_BOTTOM * u)
    )

    drawPath(path = starPath(u), color = warm)
}

/** The four-pointed star: four quadratics whose controls sit near the centre. */
private fun starPath(u: Float): Path {
    val cx = STAR_CX * u
    val cy = STAR_CY * u
    val top = STAR_TOP * u
    val bottom = STAR_BOTTOM * u
    val left = (STAR_CX - STAR_HALF_W) * u
    val right = (STAR_CX + STAR_HALF_W) * u
    // Control point for the side between two tips: the centre, nudged out toward
    // both of them. WAIST = 0 would give needle-thin cusps.
    fun ctrl(ax: Float, ay: Float, bx: Float, by: Float) =
        Offset(cx + WAIST * ((ax - cx) + (bx - cx)), cy + WAIST * ((ay - cy) + (by - cy)))
    return Path().apply {
        moveTo(cx, top)
        ctrl(cx, top, right, cy).let { quadraticTo(it.x, it.y, right, cy) }
        ctrl(right, cy, cx, bottom).let { quadraticTo(it.x, it.y, cx, bottom) }
        ctrl(cx, bottom, left, cy).let { quadraticTo(it.x, it.y, left, cy) }
        ctrl(left, cy, cx, top).let { quadraticTo(it.x, it.y, cx, top) }
        close()
    }
}

/**
 * The mark on the warm rounded tile, i.e. [GlyphTile] with the brand mark in place
 * of a character. Used wherever the old 「饼」 tile stood.
 */
@Composable
fun StarTile(
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    corner: Dp = SbRadius.field,
    tint: Color = Color.Unspecified
) {
    val tokens = LocalTokens.current
    val colour = if (tint == Color.Unspecified) tokens.accentWarm else tint
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(colour.copy(alpha = tokens.tileFill)),
        contentAlignment = Alignment.Center
    ) {
        StarMark(size = size * 0.82f, tint = colour)
    }
}
