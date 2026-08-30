package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/**
 * One row in any topic list. Avatar, title, then a single muted metadata line.
 * The row is deliberately flat - no card - so a long list reads as one surface
 * instead of a stack of boxes.
 */
@Composable
fun TopicRow(
    topic: TopicCard,
    onClick: () -> Unit,
    onAuthorClick: (() -> Unit)? = null,
    showForum: Boolean = true
) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SbMetrics.pagePadding, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        UserAvatar(
            name = topic.author.ifBlank { topic.title },
            url = topic.avatar,
            size = 38.dp,
            onClick = if (topic.authorId > 0) onAuthorClick else null
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (topic.pinned) {
                    Chip(
                        text = "置顶",
                        tint = tokens.pinTint,
                        container = tokens.pinTint.copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (topic.hot) {
                    Chip(
                        text = "热",
                        tint = tokens.hotTint,
                        container = tokens.hotTint.copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = topic.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            // 回帖 rows carry what was written. It sits under the topic title, so
            // the row reads as "this reply, on that topic".
            if (topic.excerpt.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = topic.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(6.dp))
            MetaRow {
                if (topic.author.isNotBlank()) {
                    MetaText(topic.author)
                }
                if (showForum && topic.forumName.isNotBlank()) {
                    if (topic.author.isNotBlank()) MetaDot()
                    MetaText(topic.forumName)
                }
                if (topic.timeText.isNotBlank()) {
                    if (topic.author.isNotBlank() || (showForum && topic.forumName.isNotBlank())) MetaDot()
                    MetaText(topic.timeText)
                }
            }

            if (topic.stampText.isNotBlank() && !topic.stampText.contains("置顶")) {
                Spacer(Modifier.height(6.dp))
                Chip(
                    text = topic.stampText,
                    tint = tokens.accentGlow,
                    container = tokens.accentWarm.copy(alpha = 0.10f)
                )
            }
        }

        if (topic.replies > 0) {
            Spacer(Modifier.width(10.dp))
            ReplyBadge(topic.replies)
        }
    }
}

/** The reply counter that sits at the end of a row. */
@Composable
fun ReplyBadge(count: Int) {
    val tokens = LocalTokens.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .liquidGlass(
                shape = RoundedCornerShape(SbRadius.small),
                level = GlassLevel.LOW,
                refract = false
            )
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = if (count > 999) "999+" else count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textSecondary
        )
        Text(
            text = "回复",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
    }
}

/**
 * A numbered row for the daily-hot list, where the site gives us a title and a
 * reply count but no author.
 */
@Composable
fun RankedTopicRow(
    index: Int,
    topic: TopicCard,
    onClick: () -> Unit
) {
    val tokens = LocalTokens.current
    val medal = when (index) {
        0 -> tokens.hotTint
        1 -> tokens.accentWarm
        2 -> tokens.pinTint
        else -> tokens.textTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SbMetrics.pagePadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(medal.copy(alpha = if (index < 3) 0.14f else 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = medal
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = topic.title,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (topic.replies > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${topic.replies}",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textTertiary
            )
        }
    }
}

/** A compact row used by 收藏 / 浏览历史, where only the id and a title exist. */
@Composable
fun SimpleTopicRow(
    title: String,
    subtitle: String = "",
    trailing: String = "",
    onClick: () -> Unit
) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SbMetrics.pagePadding, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                MetaText(subtitle)
            }
        }
        if (trailing.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textTertiary
            )
        }
    }
}

/** Horizontal spacing helper used by row-heavy layouts. */
@Composable
fun RowGap(width: Int = 8) = Spacer(Modifier.width(width.dp))

/** Vertical rhythm helper matching the row padding. */
@Composable
fun ColumnGap(height: Int = 8) = Spacer(Modifier.height(height.dp))

/** A pill row of chips laid out with consistent gaps. */
@Composable
fun ChipRow(
    items: List<String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { Chip(text = it) }
    }
}
