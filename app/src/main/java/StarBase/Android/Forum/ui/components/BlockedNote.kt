package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.Blocked
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/**
 * 「N 条被屏蔽」, the one line every filtered list owes the reader.
 *
 * A hidden row is still counted and still reachable: 本地屏蔽 is a display rule,
 * not a deletion, and a list that silently came back three items short would be
 * indistinguishable from a thin page on the site. Tapping 看看 shows what was
 * hidden and which rule did it, so a rule that is too broad can be found rather
 * than guessed at.
 */
@Composable
fun BlockedNote(
    count: Int,
    revealed: Boolean,
    onToggle: () -> Unit
) {
    if (count <= 0) return
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$count 条被本地规则藏起来了",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (revealed) "收起" else "看看",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.accentGlow,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

/**
 * One revealed row: the title it would have had, and the rule that hid it.
 *
 * Deliberately not a full [TopicRow] - this is an explanation of a rule, not a
 * place to browse from, though it still opens.
 */
@Composable
fun BlockedRow(blocked: Blocked<TopicCard>, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SbMetrics.pagePadding, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = blocked.item.title,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTertiary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Chip(text = "${blocked.rule.kind.label}：${blocked.rule.value}")
    }
}
