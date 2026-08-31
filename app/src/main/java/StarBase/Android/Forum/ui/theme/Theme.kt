package StarBase.Android.Forum.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import StarBase.Android.Forum.data.ThemeMode

/*
 * V5 Liquid Glass palette.
 *
 * The material is the same in every appearance: a base, a couple of slow
 * warm/cool light pools behind everything, and panels that are nothing but
 * translucent glass with a hairline outline. Colour carries almost no meaning
 * here - hierarchy comes from how much light a surface catches.
 *
 * Three appearances ship. 深色玻璃 and 经典深色 are §4.3's two dark rooms - the
 * second one draws the same layout with flat opaque panels. 浅色玻璃 is the
 * light room: the same glass, but the light comes from the paper instead of
 * from behind it, so the fills go from translucent white on black to translucent
 * white on a soft grey, and the ink flips.
 */

// -- ambient room, dark ------------------------------------------------------
internal val GlassBase = Color(0xFF0B0C0F)
private val BaseRaised = Color(0xFF14161B)
private val BaseFlat = Color(0xFF181A20)
private val BaseFlatHigh = Color(0xFF1F2229)

// -- accent (§08 主色 暖琥珀) -------------------------------------------------
private val Amber = Color(0xFFD8B37A)
private val AmberGlow = Color(0xFFF0D3A6)
private val AmberDeep = Color(0xFFA8814B)
private val Ember = Color(0xFFE08B62)
/** 精华's own gold: cooler and brighter than Amber, so 精 ≠ 置顶 at a glance. */
private val Gold = Color(0xFFE3C56A)

// -- text (§08) --------------------------------------------------------------
private val TextPrimary = Color(0xFFF6F3EF)
private val TextSecondary = Color(0xFFA7A9B2)
private val TextTertiary = Color(0xFF787B86)

/*
 * -- 浅色玻璃 ---------------------------------------------------------------
 *
 * A light glass room cannot simply invert the dark one: white-on-white panels
 * disappear. So the room itself steps down to a soft cool grey and the panels
 * are the bright thing on it, which keeps the same "a panel is where the light
 * collects" rule the dark room runs on.
 */
private val LightBase = Color(0xFFEDEFF4)
private val LightInk = Color(0xFF1A1C21)
private val LightInkSecondary = Color(0xFF585C66)
private val LightInkTertiary = Color(0xFF8B8F99)

/** The amber has to carry text and small marks on white, so it darkens. */
private val AmberInk = Color(0xFF8A6220)
private val AmberMid = Color(0xFFB4832F)
private val EmberInk = Color(0xFFC4552A)
private val GoldInk = Color(0xFF8F6A14)

/** §08 圆角: field / button / main container. */
object SbRadius {
    val field: Dp = 14.dp
    val button: Dp = 18.dp
    val container: Dp = 28.dp
    val small: Dp = 11.dp
}

/** §09 页面度量. */
object SbMetrics {
    val pagePadding: Dp = 16.dp
    val pagePaddingWide: Dp = 26.dp
    val section: Dp = 20.dp
    val navHeight: Dp = 78.dp

    /** Width at which a screen counts as “大屏” for §09's padding step. */
    val wideBreakpoint: Dp = 600.dp

    /**
     * §01: 桌面端只扩展阅读宽度, 不放大信息密度. So a wide window caps
     * the page here and centres it instead of stretching every row across the
     * screen.
     */
    val readingWidth: Dp = 720.dp
}

/**
 * The extra tokens the glass material and the screens read.
 *
 * [glassy] is the switch every glass surface asks before it allows itself to be
 * translucent; [light] is the one that says which way round the room is, and is
 * only needed where a value cannot be expressed as a colour - the system bar
 * icons and Material's own scheme.
 */
data class SbTokens(
    val glassy: Boolean,
    val light: Boolean,
    val base: Color,
    val glassLow: Color,
    val glass: Color,
    val glassHigh: Color,
    val strokeTop: Color,
    val strokeBottom: Color,
    /** The stroke a focused field raises to (§8.1: stroke only, no glow). */
    val strokeFocus: Color,
    val hairline: Color,
    /** The top-edge highlight on a piece of glass. */
    val sheen: Color,
    val accentWarm: Color,
    val accentGlow: Color,
    val accentDeep: Color,
    val pinTint: Color,
    val hotTint: Color,
    /** 精华. Sits between pin-amber and hot-ember so the three read apart. */
    val digestTint: Color,
    /** Text drawn on an accent-filled surface, e.g. a primary button. */
    val onAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val codeBg: Color,
    val quoteBar: Color,
    /** The three ambient light pools, already carrying their own alpha. */
    val roomWarm: Color,
    val roomCool: Color,
    val roomDeep: Color,
    /** Fill weight behind a glyph tile / chip, relative to its own colour. */
    val tileFill: Float,
    val chipFill: Float
)

private val GlassTokens = SbTokens(
    glassy = true,
    light = false,
    base = GlassBase,
    // §08 玻璃: rgba 白 0.04-0.09
    glassLow = Color.White.copy(alpha = 0.04f),
    glass = Color.White.copy(alpha = 0.065f),
    glassHigh = Color.White.copy(alpha = 0.09f),
    // §08 描边: rgba 白 0.07-0.12, 只描边不加高光边框
    strokeTop = Color.White.copy(alpha = 0.12f),
    strokeBottom = Color.White.copy(alpha = 0.07f),
    strokeFocus = Color.White.copy(alpha = 0.22f),
    hairline = Color.White.copy(alpha = 0.08f),
    sheen = Color.White.copy(alpha = 0.075f),
    accentWarm = Amber,
    accentGlow = AmberGlow,
    accentDeep = AmberDeep,
    pinTint = Amber,
    hotTint = Ember,
    digestTint = Gold,
    onAccent = Color(0xFF241A0C),
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    textTertiary = TextTertiary,
    codeBg = Color.White.copy(alpha = 0.05f),
    quoteBar = Amber.copy(alpha = 0.55f),
    roomWarm = Amber.copy(alpha = 0.20f),
    roomCool = Color(0xFF6E86A8).copy(alpha = 0.16f),
    roomDeep = AmberDeep.copy(alpha = 0.13f),
    tileFill = 0.13f,
    chipFill = 0.10f
)

private val ClassicTokens = GlassTokens.copy(
    glassy = false,
    glassLow = BaseFlat,
    glass = BaseFlat,
    glassHigh = BaseFlatHigh,
    strokeTop = Color.White.copy(alpha = 0.07f),
    strokeBottom = Color.White.copy(alpha = 0.05f),
    strokeFocus = Color.White.copy(alpha = 0.16f),
    hairline = Color.White.copy(alpha = 0.07f),
    codeBg = Color(0xFF101216),
    tileFill = 0.16f
)

/**
 * 浅色玻璃. Same three fill weights, but they are now most of the way to opaque
 * white - on a light room a 6% white panel is invisible, while a 60% one still
 * lets the light pools tint it, which is the whole point of the material. The
 * outline gains a dark bottom edge, because on paper a panel is separated from
 * the page by a shadowed edge rather than by a bright one.
 */
private val LightTokens = SbTokens(
    glassy = true,
    light = true,
    base = LightBase,
    glassLow = Color.White.copy(alpha = 0.46f),
    glass = Color.White.copy(alpha = 0.62f),
    glassHigh = Color.White.copy(alpha = 0.78f),
    strokeTop = Color.White.copy(alpha = 0.75f),
    strokeBottom = Color.Black.copy(alpha = 0.07f),
    strokeFocus = AmberMid.copy(alpha = 0.45f),
    hairline = Color.Black.copy(alpha = 0.07f),
    sheen = Color.White.copy(alpha = 0.30f),
    accentWarm = AmberInk,
    accentGlow = AmberInk,
    accentDeep = AmberMid,
    pinTint = AmberMid,
    hotTint = EmberInk,
    digestTint = GoldInk,
    onAccent = Color(0xFFFFF8EC),
    textPrimary = LightInk,
    textSecondary = LightInkSecondary,
    textTertiary = LightInkTertiary,
    codeBg = Color(0xFFF3F0E9),
    quoteBar = AmberMid.copy(alpha = 0.60f),
    roomWarm = Color(0xFFE7B65E).copy(alpha = 0.30f),
    roomCool = Color(0xFF7FA0CC).copy(alpha = 0.26f),
    roomDeep = Color(0xFFD9A277).copy(alpha = 0.22f),
    tileFill = 0.14f,
    chipFill = 0.13f
)

val LocalTokens = staticCompositionLocalOf { GlassTokens }

/**
 * Material's own scheme still backs anything that reads colorScheme directly.
 * The container steps sit deliberately close together: in a glass room the
 * elevation story is told by light, not by six shades of grey.
 */
private fun scheme(tokens: SbTokens) = if (tokens.light) {
    lightColorScheme(
        primary = tokens.accentWarm,
        onPrimary = tokens.onAccent,
        primaryContainer = tokens.accentWarm.copy(alpha = 0.14f),
        onPrimaryContainer = tokens.accentWarm,
        secondary = Color(0xFF4C6580),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0x1F4C6580),
        onSecondaryContainer = Color(0xFF2B3A47),
        tertiary = tokens.hotTint,
        background = tokens.base,
        onBackground = tokens.textPrimary,
        surface = Color(0xFFF7F8FB),
        onSurface = tokens.textPrimary,
        surfaceVariant = Color(0xFFE3E6EC),
        onSurfaceVariant = tokens.textSecondary,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFAFBFD),
        surfaceContainer = Color(0xFFF2F4F8),
        surfaceContainerHigh = Color(0xFFEBEEF3),
        surfaceContainerHighest = Color(0xFFE4E7ED),
        outline = tokens.textTertiary,
        outlineVariant = Color(0x1F000000),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF)
    )
} else {
    darkColorScheme(
        primary = tokens.accentWarm,
        onPrimary = tokens.onAccent,
        primaryContainer = tokens.accentWarm.copy(alpha = 0.16f),
        onPrimaryContainer = tokens.accentGlow,
        secondary = Color(0xFF9FB3C8),
        onSecondary = Color(0xFF15202B),
        secondaryContainer = Color(0x1F9FB3C8),
        onSecondaryContainer = Color(0xFFC9D7E4),
        tertiary = tokens.hotTint,
        background = tokens.base,
        onBackground = tokens.textPrimary,
        surface = if (tokens.glassy) GlassBase else BaseRaised,
        onSurface = tokens.textPrimary,
        surfaceVariant = if (tokens.glassy) Color(0x14FFFFFF) else Color(0xFF23262E),
        onSurfaceVariant = tokens.textSecondary,
        surfaceContainerLowest = if (tokens.glassy) Color(0x0AFFFFFF) else Color(0xFF13151A),
        surfaceContainerLow = if (tokens.glassy) Color(0x0DFFFFFF) else Color(0xFF161920),
        surfaceContainer = if (tokens.glassy) Color(0x11FFFFFF) else BaseFlat,
        surfaceContainerHigh = if (tokens.glassy) Color(0x17FFFFFF) else BaseFlatHigh,
        surfaceContainerHighest = if (tokens.glassy) Color(0x1FFFFFFF) else Color(0xFF262A32),
        outline = tokens.textTertiary,
        outlineVariant = Color(0x1FFFFFFF),
        error = Color(0xFFE98D7C),
        onError = Color(0xFF2B120D)
    )
}

/**
 * §09 正文行高 1.75-1.85. Trim.None keeps that leading above the first line too,
 * which is what makes a wall of comments read as one column instead of rows
 * jammed against their dividers.
 */
private fun body(size: Int, weight: FontWeight, height: Int, spacing: Double = 0.0) =
    TextStyle(
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = height.sp,
        letterSpacing = spacing.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )

val SbTypography = Typography(
    displaySmall = body(28, FontWeight.SemiBold, 36, -0.4),
    headlineMedium = body(25, FontWeight.SemiBold, 34, -0.3),
    // §05 帖子标题 22-24sp
    headlineSmall = body(23, FontWeight.SemiBold, 32, -0.2),
    titleLarge = body(19, FontWeight.SemiBold, 27),
    titleMedium = body(17, FontWeight.Medium, 24),
    titleSmall = body(15, FontWeight.Medium, 22),
    // §05/§09 正文 13.5-14.5sp
    bodyLarge = body(15, FontWeight.Normal, 27),
    bodyMedium = body(14, FontWeight.Normal, 25),
    bodySmall = body(13, FontWeight.Normal, 23),
    labelLarge = body(14, FontWeight.Medium, 19),
    labelMedium = body(12, FontWeight.Medium, 17),
    labelSmall = body(11, FontWeight.Normal, 15)
)

@Composable
fun StarBaseTheme(
    mode: ThemeMode = ThemeMode.DEFAULT,
    content: @Composable () -> Unit
) {
    val tokens = when (mode) {
        ThemeMode.GLASS -> GlassTokens
        ThemeMode.LIGHT -> LightTokens
        ThemeMode.CLASSIC -> ClassicTokens
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                // Dark icons only in the light room - the bars are transparent, so
                // they sit directly on the page.
                isAppearanceLightStatusBars = tokens.light
                isAppearanceLightNavigationBars = tokens.light
            }
            // themes.xml can only name one window background, and it is the dark
            // one; without this the light room would open through a black flash
            // on a cold start and on every rotation.
            window.setBackgroundDrawable(ColorDrawable(tokens.base.toArgb()))
        }
    }
    CompositionLocalProvider(LocalTokens provides tokens) {
        MaterialTheme(colorScheme = scheme(tokens), typography = SbTypography, content = content)
    }
}
