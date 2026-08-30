package StarBase.Android.Forum.ui.glass

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbTokens

/*
 * The Liquid Glass material.
 *
 * The sampling comes from AndroidLiquidGlass (io.github.kyant0:backdrop): a panel
 * records whatever sits behind it into a graphics layer and runs blur + a
 * rounded-rect refraction shader over it, which is what bends light at the rim.
 * Two sources feed that sampling:
 *
 *  - [AmbientRoom] publishes an [AmbientBackdrop] - the room of soft light pools
 *    the whole app sits in, known analytically. Panels inside the page sample
 *    that, so a card never samples itself or its neighbours; stacking slices is
 *    what makes a glass UI look like frosted plastic.
 *  - The page body records itself through [Modifier.pageBackdrop], and surfaces
 *    floating over it (the bottom navigation) sample that layer on top of the
 *    room, so they really blur the list they cover.
 *
 * RenderEffect needs API 31 and the refraction shader API 33. Below that a panel
 * paints its own slice of the room instead: the pools are wide and soft, so an
 * unblurred slice sits within a pixel or two of a blurred one.
 */

/** §08 玻璃: three fill weights, all translucent white. */
enum class GlassLevel { LOW, MEDIUM, HIGH }

/** What a piece of glass is allowed to look at. */
enum class GlassSource {
    /** The ambient room only - the right answer for anything inside the page. */
    AMBIENT,

    /** The room plus the page body, for bars that float over the content. */
    PAGE
}

internal val supportsSampling: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

internal data class Blob(val center: Offset, val radius: Float, val color: Color)

/** The room, in root pixel coordinates, so a panel can cut its own slice out. */
internal class GlassScene(val size: IntSize, val base: Color, val blobs: List<Blob>)

internal val LocalGlassScene =
    staticCompositionLocalOf { GlassScene(IntSize.Zero, Color.Black, emptyList()) }

internal val LocalAmbientBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

internal val LocalPageBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

internal val LocalPageLayer = staticCompositionLocalOf<LayerBackdrop?> { null }

private fun sceneFor(size: IntSize, tokens: SbTokens): GlassScene {
    if (size.width == 0 || size.height == 0 || !tokens.glassy) {
        return GlassScene(size, tokens.base, emptyList())
    }
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    // Three pools: a warm one behind the top of the page (the amber the whole
    // brand runs on), a cool one at reading height to keep long bodies from
    // going muddy, and a faint warm one under the navigation bar. The colours
    // come from the tokens because the light room needs its own, brighter set -
    // the dark room's accent would only read as dirt on white.
    return GlassScene(
        size = size,
        base = tokens.base,
        blobs = listOf(
            Blob(Offset(w * 0.86f, h * -0.04f), w * 1.05f, tokens.roomWarm),
            Blob(Offset(w * -0.12f, h * 0.34f), w * 1.00f, tokens.roomCool),
            Blob(Offset(w * 0.42f, h * 1.02f), w * 0.95f, tokens.roomDeep)
        )
    )
}

/** Paints the room, or the slice of it that sits under [origin]. */
internal fun DrawScope.paintRoom(scene: GlassScene, origin: Offset, alpha: Float) {
    scene.blobs.forEach { blob ->
        val center = blob.center - origin
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(blob.color.copy(alpha = blob.color.alpha * alpha), Color.Transparent),
                center = center,
                radius = blob.radius
            )
        )
    }
}

/**
 * The room as a [Backdrop]: an opaque base plus the light pools, positioned by
 * the sampling panel's own coordinates. Feeding the room through the library
 * rather than painting it directly is what lets blur and the refraction shader
 * run over it.
 */
@Stable
internal class AmbientBackdrop(private val scene: State<GlassScene>) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val current = scene.value
        drawRect(current.base)
        paintRoom(current, coordinates?.positionInRoot() ?: Offset.Zero, 1f)
    }
}

/**
 * The page background every screen sits in. Fills the window with the base
 * colour, paints the light pools, and publishes both backdrops so the glass
 * above can sample them.
 */
@Composable
fun AmbientRoom(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = LocalTokens.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scene = remember(size, tokens) { sceneFor(size, tokens) }
    val sceneState = rememberUpdatedState(scene)
    val ambient = remember(sceneState) { AmbientBackdrop(sceneState) }
    val pageLayer = rememberLayerBackdrop()
    val page = rememberCombinedBackdrop(ambient, pageLayer)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.base)
            .onSizeChanged { size = it }
            .drawBehind { paintRoom(scene, Offset.Zero, 1f) }
    ) {
        CompositionLocalProvider(
            LocalGlassScene provides scene,
            LocalAmbientBackdrop provides ambient,
            LocalPageBackdrop provides page,
            LocalPageLayer provides pageLayer
        ) {
            content()
        }
    }
}

/**
 * Records the page body so [GlassSource.PAGE] surfaces can sample it. Put it on
 * the node that holds the screen content and nothing that floats above it - a
 * bar inside this node would end up sampling itself.
 */
@Composable
fun Modifier.pageBackdrop(): Modifier {
    val tokens = LocalTokens.current
    val layer = LocalPageLayer.current
    return if (tokens.glassy && supportsSampling && layer != null) {
        this.layerBackdrop(layer)
    } else {
        this
    }
}

/** §08 折射: rim lens + blur, weighted by how present the surface should feel. */
private fun glassEffects(level: GlassLevel, shape: Shape): BackdropEffectScope.() -> Unit = {
    val blurRadius = when (level) {
        GlassLevel.LOW -> 6f
        GlassLevel.MEDIUM -> 10f
        GlassLevel.HIGH -> 15f
    }
    blur(blurRadius.dp.toPx())
    // The refraction shader only knows how to walk a rounded rectangle.
    if (shape is CornerBasedShape) {
        val height = when (level) {
            GlassLevel.LOW -> 8f
            GlassLevel.MEDIUM -> 10f
            GlassLevel.HIGH -> 12f
        }
        val amount = when (level) {
            GlassLevel.LOW -> 12f
            GlassLevel.MEDIUM -> 16f
            GlassLevel.HIGH -> 20f
        }
        lens(height.dp.toPx(), amount.dp.toPx())
    }
}

/**
 * Makes any layout node a piece of liquid glass: sampled backdrop, translucent
 * white body, top sheen, hairline outline. In 经典深色 it collapses to a flat
 * surface with the same outline, so callers never branch on the appearance
 * themselves.
 *
 * @param refract sample the backdrop behind this panel. Turn it off for small
 *   controls sitting inside a panel that already did it.
 * @param source what the panel is allowed to look at - see [GlassSource].
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    level: GlassLevel = GlassLevel.MEDIUM,
    refract: Boolean = true,
    outline: Boolean = true,
    tint: Color = Color.Transparent,
    source: GlassSource = GlassSource.AMBIENT
): Modifier {
    val tokens = LocalTokens.current
    val fill = when (level) {
        GlassLevel.LOW -> tokens.glassLow
        GlassLevel.MEDIUM -> tokens.glass
        GlassLevel.HIGH -> tokens.glassHigh
    }
    val body = if (tokens.glassy) {
        Brush.verticalGradient(
            listOf(
                fill.copy(alpha = (fill.alpha * 1.45f).coerceAtMost(1f)),
                fill,
                fill.copy(alpha = fill.alpha * 0.72f)
            )
        )
    } else {
        Brush.verticalGradient(listOf(fill, fill))
    }
    val sheen = Brush.verticalGradient(
        0f to tokens.sheen,
        0.16f to Color.Transparent
    )
    var m: Modifier = this
    if (outline) {
        m = m.border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(tokens.strokeTop, tokens.strokeBottom)),
            shape = shape
        )
    }
    if (!tokens.glassy || !refract) {
        // Classic dark, or a control nested in a panel that already sampled.
        return m.clip(shape).drawBehind {
            drawRect(body)
            if (tint != Color.Transparent) drawRect(tint)
            if (tokens.glassy) drawRect(sheen)
        }
    }
    if (!supportsSampling) {
        // API 24-30: no RenderEffect, so paint this panel's own slice of the room.
        var origin by remember { mutableStateOf(Offset.Zero) }
        val scene = LocalGlassScene.current
        return m
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                paintRoom(scene, origin, 0.9f)
                drawRect(body)
                if (tint != Color.Transparent) drawRect(tint)
                drawRect(sheen)
            }
    }
    val backdrop = when (source) {
        GlassSource.AMBIENT -> LocalAmbientBackdrop.current
        GlassSource.PAGE -> LocalPageBackdrop.current
    }
    val shapeBlock = remember(shape) { { shape } }
    val effects = remember(level, shape) { glassEffects(level, shape) }
    val surface: DrawScope.() -> Unit = remember(body, tint, sheen) {
        {
            drawRect(body)
            if (tint != Color.Transparent) drawRect(tint)
            drawRect(sheen)
        }
    }
    // The clip has to sit above drawBackdrop: the sampled layer is a rectangle
    // grown by the blur radius, and only the shader knows about the corners.
    return m
        .clip(shape)
        .drawBackdrop(
            backdrop = backdrop,
            shape = shapeBlock,
            effects = effects,
            // §08 描边: outline only, no highlight border and no drop shadow.
            highlight = null,
            shadow = null,
            onDrawSurface = surface
        )
}

/** §8.2 按压: 0.98 缩放 + 亮度下降. No glow, no ripple colour change. */
@Composable
fun Modifier.pressFeedback(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "press-scale"
    )
    val dim by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "press-dim"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = dim
    }
}
