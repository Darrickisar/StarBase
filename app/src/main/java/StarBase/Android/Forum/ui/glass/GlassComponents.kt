package StarBase.Android.Forum.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * The small set of components the whole app is built from. AndroidLiquidGlass
 * ships no components on purpose ("you will need to create your own"), so this
 * is that layer: panel, button, tabs, field, chip, glyph tile.
 */

/** A glass container. The replacement for the old opaque card. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(SbRadius.container),
    level: GlassLevel = GlassLevel.MEDIUM,
    refract: Boolean = true,
    outline: Boolean = true,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    var m = modifier
    if (onClick != null) m = m.pressFeedback(interaction)
    m = m.liquidGlass(shape = shape, level = level, refract = refract, outline = outline)
    if (onClick != null) {
        m = m.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }
    Column(modifier = m.padding(padding), content = content)
}

/**
 * §08 主操作: a solid warm-amber button. [primary] false gives the same shape in
 * glass, which is what 注册 next to 登录 uses - same level, less light.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (compact) SbRadius.field else SbRadius.button)
    val height = if (compact) 40.dp else 48.dp
    val label = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge

    var m = modifier
        .pressFeedback(interaction)
        .heightIn(min = height)
    m = if (primary) {
        val fill by animateColorAsState(
            targetValue = if (enabled) tokens.accentWarm else tokens.accentWarm.copy(alpha = 0.28f),
            animationSpec = tween(160),
            label = "button-fill"
        )
        m.clip(shape).background(fill)
    } else {
        m.liquidGlass(shape = shape, level = GlassLevel.MEDIUM, refract = false)
    }

    Box(
        modifier = m
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = if (compact) 14.dp else 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = label,
            color = when {
                primary && enabled -> MaterialTheme.colorScheme.onPrimary
                primary -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)
                enabled -> tokens.textPrimary
                else -> tokens.textTertiary
            },
            maxLines = 1
        )
    }
}

/**
 * §07 模式切换 / §4.3 外观: one short segmented control. The current option is a
 * weak-glass highlight, never a filled pill - a filled pill reads as a button.
 */
@Composable
fun GlassTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalTokens.current
    val shape = RoundedCornerShape(SbRadius.field)
    Row(
        modifier = modifier
            .liquidGlass(shape = shape, level = GlassLevel.LOW)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val interaction = remember { MutableInteractionSource() }
            var m: Modifier = Modifier.weight(1f).heightIn(min = 36.dp)
            m = m.pressFeedback(interaction)
            if (active) {
                m = m.liquidGlass(
                    shape = RoundedCornerShape(SbRadius.small),
                    level = GlassLevel.HIGH,
                    refract = false,
                    outline = false,
                    tint = tokens.accentWarm.copy(alpha = 0.10f)
                )
            }
            Box(
                modifier = m.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onSelect(index) }
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = if (active) tokens.accentGlow else tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * §8.1 输入框: ~48dp tall, 13dp inner padding, one glyph on the left, and the
 * field name carried by the placeholder rather than by a label above it. Focus
 * only raises the stroke and the inner brightness - no glow, no colour change.
 */
@Composable
fun GlassField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    glyph: String = "",
    password: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    imeAction: ImeAction = ImeAction.Next,
    onSubmit: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(SbRadius.field)
    val strokeTop by animateColorAsState(
        targetValue = if (focused) tokens.strokeFocus else tokens.strokeTop,
        animationSpec = tween(180),
        label = "field-stroke"
    )
    Row(
        modifier = modifier
            .border(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(strokeTop, tokens.strokeBottom)
                ),
                shape = shape
            )
            .liquidGlass(
                shape = shape,
                level = if (focused) GlassLevel.HIGH else GlassLevel.LOW,
                refract = false,
                outline = false
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (glyph.isNotBlank()) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) tokens.textSecondary else tokens.textTertiary
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                enabled = enabled,
                singleLine = true,
                interactionSource = interaction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.accentWarm),
                visualTransformation = if (password) PasswordVisualTransformation()
                else VisualTransformation.None,
                keyboardOptions = keyboardOptions.copy(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onDone = { onSubmit?.invoke() },
                    onGo = { onSubmit?.invoke() },
                    onSearch = { onSubmit?.invoke() }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A single-glyph tile: the app uses one Chinese character where an icon set would go. */
@Composable
fun GlyphTile(
    glyph: String,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    tint: Color = Color.Unspecified,
    corner: Dp = SbRadius.small
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
        Text(
            text = glyph,
            style = if (size >= 34.dp) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.labelMedium,
            color = colour
        )
    }
}

/** A low-key label. Used for board names, groups, tags below a post title. */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    val colour = if (tint == Color.Unspecified) tokens.accentWarm else tint
    var m = modifier
        .clip(RoundedCornerShape(SbRadius.small))
        .background(colour.copy(alpha = tokens.chipFill))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(modifier = m.padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            // On paper the label has to stay legible, so it keeps its full ink.
            color = if (tokens.light) colour else colour.copy(alpha = 0.92f),
            maxLines = 1
        )
    }
}

/** A light top-left action, e.g. §07 返回我的. Never inside a big card. */
@Composable
fun GlassBackAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .pressFeedback(interaction)
            .liquidGlass(shape = RoundedCornerShape(50), level = GlassLevel.LOW, refract = false)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .defaultMinSize(minHeight = 34.dp)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textSecondary,
            maxLines = 1
        )
    }
}
