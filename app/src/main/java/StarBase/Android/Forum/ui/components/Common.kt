package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.glass.pressFeedback
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/**
 * A glass container. Kept under the old name so every screen that already asks
 * for "a card" gets the V5 material without a rename, but it is no longer an
 * opaque surface: it is one pane of glass with a hairline outline.
 */
@Composable
fun SbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    corner: Dp = 22.dp,
    padding: Dp = 16.dp,
    level: GlassLevel = GlassLevel.MEDIUM,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassPanel(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        level = level,
        padding = padding,
        content = content
    )
}

/** Small pill label. */
@Composable
fun Chip(
    text: String,
    tint: Color = LocalTokens.current.textSecondary,
    container: Color = LocalTokens.current.glassLow,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}

/**
 * One option in a segmented row. Selected is a weak-glass highlight with amber
 * text, not a filled pill - §07 is explicit that the filled look belongs to the
 * primary button alone.
 */
@Composable
fun SegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressFeedback(interaction)
            .liquidGlass(
                shape = RoundedCornerShape(SbRadius.field),
                level = if (selected) GlassLevel.HIGH else GlassLevel.LOW,
                refract = false,
                tint = if (selected) tokens.accentWarm.copy(alpha = 0.10f) else Color.Transparent
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) tokens.accentGlow else tokens.textSecondary,
            maxLines = 1
        )
    }
}

/** Section header with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    horizontal: Dp = SbMetrics.pagePadding
) {
    Row(
        modifier = Modifier.padding(horizontal = horizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = LocalTokens.current.textPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalTokens.current.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = LocalTokens.current.accentGlow,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .let { m -> if (onTrailingClick != null) m.clickable { onTrailingClick() } else m }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/** Dot separator used inside metadata rows. */
@Composable
fun MetaDot() {
    Spacer(modifier = Modifier.width(6.dp))
    Box(
        modifier = Modifier
            .size(3.dp)
            .clip(RoundedCornerShape(50))
            .background(LocalTokens.current.textTertiary.copy(alpha = 0.45f))
    )
    Spacer(modifier = Modifier.width(6.dp))
}

/** Metadata line: small, muted, single line. */
@Composable
fun MetaRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) { content() }
}

@Composable
fun MetaText(text: String, emphasis: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (emphasis) {
            LocalTokens.current.textSecondary
        } else {
            LocalTokens.current.textTertiary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * §09 页面标题行: the page's own name on the left and at most one light action on
 * the right. Tab screens have no top app bar, so this is what tells you which
 * page you are on - and keeping the action a small glass pill is what stops the
 * first screen from opening with a big empty header block.
 */
@Composable
fun PageHead(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SbMetrics.pagePadding, end = SbMetrics.pagePadding)
            .padding(top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = tokens.textPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (action != null && onAction != null) {
            LightAction(text = action, onClick = onAction)
        }
    }
}

/** A low-key glass pill action. Used for 刷新 next to a page title. */
@Composable
fun LightAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressFeedback(interaction)
            .liquidGlass(shape = RoundedCornerShape(50), level = GlassLevel.LOW, refract = false)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textSecondary,
            maxLines = 1
        )
    }
}
