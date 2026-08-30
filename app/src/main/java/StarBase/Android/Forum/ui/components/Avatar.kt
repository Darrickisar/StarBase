package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A deterministic avatar drawn on-device from the display name. Used as the
 * placeholder and fallback for [UserAvatar], and whenever the page gave us a
 * name but no image URL.
 */
private val palettes = listOf(
    Color(0xFFE9A23B) to Color(0xFFC2681A),
    Color(0xFF7FA86B) to Color(0xFF4C7040),
    Color(0xFF6B93C4) to Color(0xFF3D6394),
    Color(0xFFC77B85) to Color(0xFF97505C),
    Color(0xFF9B85C4) to Color(0xFF6A5395),
    Color(0xFF4FA8A0) to Color(0xFF2C7670),
    Color(0xFFD08A5C) to Color(0xFFA25A31),
    Color(0xFF8C93A8) to Color(0xFF5C6377)
)

private fun stableHash(s: String): Int {
    var h = 0
    for (c in s) h = h * 31 + c.code
    return h
}

fun avatarInitial(name: String): String {
    val trimmed = name.trim()
    // No name at all: [Avatar] draws the StarBase mark instead of a letter.
    if (trimmed.isEmpty()) return ""
    val first = trimmed.first()
    // For latin names show one uppercase letter; CJK shows the character itself.
    return if (first.code < 128) first.uppercaseChar().toString() else first.toString()
}

@Composable
fun Avatar(
    name: String,
    size: Dp = 40.dp,
    ring: Boolean = false
) {
    val key = remember(name) { stableHash(name) }
    val (top, bottom) = palettes[((key % palettes.size) + palettes.size) % palettes.size]
    val label = remember(name) { avatarInitial(name) }
    val ringColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(top, bottom),
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height)
                    )
                )
                // faint highlight, gives the disc a little depth
                drawCircle(
                    color = Color.White.copy(alpha = 0.16f),
                    radius = this.size.minDimension * 0.62f,
                    center = Offset(this.size.width * 0.28f, this.size.height * 0.18f)
                )
                if (ring) {
                    drawCircle(
                        color = ringColor,
                        radius = this.size.minDimension / 2f - 0.6.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (label.isEmpty()) {
            StarMark(
                size = size * 0.58f,
                tint = Color.White,
                orbitTint = Color.White.copy(alpha = 0.6f)
            )
        } else {
            Text(
                text = label,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp
                )
            )
        }
    }
}
