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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
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
            // 精 and 热 are title suffixes on the site, so they follow the title
            // here too rather than leading it. 置顶 keeps the leading slot it has
            // on the site, where it sits before the title.
            val marks = buildList {
                if (topic.featured) add(TitleMark("精", tokens.digestTint))
                if (topic.hot) add(TitleMark("热", tokens.hotTint))
            }
            // A title long enough to fill both lines would swallow the suffixes,
            // so once we see that happen they move to a line of their own. Latched
            // to one direction: dropping them cannot make the title overflow
            // again, and re-measuring both ways would oscillate.
            var marksClipped by remember(topic.id) { mutableStateOf(false) }
            val marksInline = marks.isNotEmpty() && !marksClipped

            Row(verticalAlignment = Alignment.Top) {
                if (topic.pinned) {
                    Chip(
                        text = "置顶",
                        tint = tokens.pinTint,
                        container = tokens.pinTint.copy(alpha = 0.12f),
                        // Top-aligned so a two-line title does not leave the chip
                        // floating at its middle; 1dp puts it on the first line's
                        // optical centre.
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                TopicTitle(
                    title = topic.title,
                    marks = if (marksInline) marks else emptyList(),
                    onClipped = { if (marksInline) marksClipped = true },
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            if (marks.isNotEmpty() && !marksInline) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    marks.forEachIndexed { i, mark ->
                        if (i > 0) Spacer(Modifier.width(6.dp))
                        MarkChip(mark)
                    }
                }
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

            // 抽奖中 / 发卡中 only - the parser keeps 置顶/精华/热 out of this now,
            // so it no longer repeats the marks drawn above.
            if (topic.stampText.isNotBlank()) {
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

/** One single-character mark drawn after a topic title: 精 or 热. */
private data class TitleMark(val text: String, val tint: Color)

/** Mark ids for the title's inline content slots. */
private const val MARK_SLOT = "mark:"

/**
 * The title, with its marks set inline so they sit right after the last word and
 * wrap with it instead of being pushed to the far edge of the row.
 *
 * [onClipped] fires when the title needed more room than it has, which is also
 * when the trailing marks would have been eaten by the ellipsis.
 */
@Composable
private fun TopicTitle(
    title: String,
    marks: List<TitleMark>,
    onClipped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalTokens.current
    val density = LocalDensity.current
    // Placeholders are measured in sp, the marks in dp, so convert once.
    val markWidth = with(density) { MARK_WIDTH.toSp() }
    val markHeight = with(density) { MARK_HEIGHT.toSp() }

    val text = remember(title, marks) {
        buildAnnotatedString {
            append(title)
            marks.forEachIndexed { i, _ ->
                // A real space, so a line may break before a mark rather than
                // splitting the placeholder off the text awkwardly.
                append(" ")
                appendInlineContent(MARK_SLOT + i, "·")
            }
        }
    }
    val inline = remember(marks, markWidth, markHeight) {
        marks.mapIndexed { i, mark ->
            MARK_SLOT + i to InlineTextContent(
                Placeholder(
                    width = markWidth,
                    height = markHeight,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) { MarkChip(mark) }
        }.toMap()
    }

    Text(
        text = text,
        inlineContent = inline,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        color = tokens.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow) onClipped() },
        modifier = modifier
    )
}

private val MARK_WIDTH = 22.dp
private val MARK_HEIGHT = 17.dp

/** The 精 / 热 pill: same shape language as [Chip], sized to one character. */
@Composable
private fun MarkChip(mark: TitleMark) {
    Box(
        modifier = Modifier
            .size(width = MARK_WIDTH, height = MARK_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(mark.tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = mark.text,
            style = MaterialTheme.typography.labelSmall,
            color = mark.tint,
            maxLines = 1
        )
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
