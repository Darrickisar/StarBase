package StarBase.Android.Forum.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Gacha-title rarity colours. The site prints the tier as a bare letter code
 * (ur / ssr / sr / r / n); these are our own readable stand-ins for it.
 */
fun tierColor(tier: String): Color = when (tier.lowercase()) {
    "ur" -> Color(0xFFA855F7)
    "ssr" -> Color(0xFFE0A32E)
    "sr" -> Color(0xFF4C8DD9)
    "r" -> Color(0xFF5FA36A)
    else -> Color(0xFF8A7C6D)
}

/** Human label for a tier code, used in the 称号 catalogue headers. */
fun tierLabel(tier: String): String = when (tier.lowercase()) {
    "ur" -> "UR · 极珍"
    "ssr" -> "SSR · 稀世"
    "sr" -> "SR · 稀有"
    "r" -> "R · 精良"
    "n" -> "N · 普通"
    else -> tier.uppercase().ifBlank { "未分级" }
}
