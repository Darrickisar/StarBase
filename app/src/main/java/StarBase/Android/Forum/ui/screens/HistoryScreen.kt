package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.History
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.data.Visit
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassField
import StarBase.Android.Forum.ui.glass.pressFeedback
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * 浏览历史 - the app's own feature, not the site's.
 *
 * linux.sb keeps no reading history, so this is the one screen whose content
 * comes from the device instead of a request. What it stores is a record of what
 * *you* did - id, the title at the time, when, how many times - and never a copy
 * of a topic: tapping a row opens the live page, and the title on it is re-read
 * from that page.
 *
 * Grouped by day and searchable, because a flat list of 300 rows is a log rather
 * than something you can find last Tuesday's thread in.
 */

@Composable
fun HistoryScreen(
    store: UserStore,
    onBack: () -> Unit,
    onTopic: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    // Stamped once per composition of the screen rather than per row: 300 rows
    // asking the clock separately could straddle a minute and label two entries
    // from one moment differently.
    val now = remember(store.history) { System.currentTimeMillis() }

    val matches = History.search(store.history, query)
    val days = History.byDay(matches, now)

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "浏览历史",
            subtitle = subtitleOf(store, matches.size, query),
            onBack = onBack,
            action = if (store.history.isEmpty()) "" else "清空",
            onAction = store::clearHistory
        )

        // The switch lives on this screen as well as in 应用设置: turning
        // recording off is most often decided while looking at the record.
        RecordingRow(store = store)

        if (store.history.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding)) {
                GlassField(
                    value = query,
                    onValue = { query = it },
                    placeholder = "搜标题、板块，或者帖子号",
                    glyph = "搜",
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                )
            }
            Gap(4)
        }

        when {
            store.history.isEmpty() -> EmptyPanel(
                "还没有浏览记录",
                if (store.keepHistory) "看过的帖子会出现在这里，只存在这台手机上"
                else "记录已关闭，打开上面的开关才会记"
            )

            matches.isEmpty() -> EmptyPanel("没有匹配的记录", "换个词，或者清掉搜索框")

            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    item("day-${day.label}") {
                        DayHeader(label = day.label, count = day.visits.size)
                    }
                    itemsIndexed(day.visits) { index, visit ->
                        if (index > 0) Hairline(startInset = 16)
                        VisitRow(
                            visit = visit,
                            now = now,
                            onClick = { onTopic(visit.id) },
                            onForget = { store.forgetVisit(visit.id) }
                        )
                    }
                }
                item("tail") { Gap(26) }
            }
        }
    }
}

private fun subtitleOf(store: UserStore, shown: Int, query: String): String = when {
    store.history.isEmpty() -> "只存在本机"
    query.isNotBlank() -> "$shown / ${store.history.size} 条"
    else -> "${store.history.size} 条，只存在本机"
}

/** itemsIndexed for a plain list, since only the lazy-list overload exists here. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    visits: List<Visit>,
    crossinline row: @Composable (Int, Visit) -> Unit
) = visits.forEachIndexed { index, visit ->
    item("visit-${visit.id}") { row(index, visit) }
}

@Composable
private fun RecordingRow(store: UserStore) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "记录我看过的帖子",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary
            )
            Gap(2)
            Text(
                text = "不上传，不同步，站点看不到这份记录",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }
        Spacer(Modifier.width(10.dp))
        SmallAction(
            text = if (store.keepHistory) "已开启" else "已关闭",
            primary = store.keepHistory,
            onClick = { store.updateKeepHistory(!store.keepHistory) }
        )
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SbMetrics.pagePadding, end = SbMetrics.pagePadding, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textSecondary
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(tokens.textTertiary.copy(alpha = 0.14f))
                .padding(horizontal = 7.dp, vertical = 1.dp)
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }
    }
}

@Composable
private fun VisitRow(
    visit: Visit,
    now: Long,
    onClick: () -> Unit,
    onForget: () -> Unit
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(SbRadius.field))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = SbMetrics.pagePadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // A visit recorded before its page answered has no title. The id
                // is what we know, so that is what it says.
                text = visit.title.ifBlank { "帖子 #${visit.id}" },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaText(History.ago(visit.at, now))
                if (visit.forumName.isNotBlank()) {
                    Spacer(Modifier.width(7.dp))
                    MetaText("· ${visit.forumName}")
                }
                if (visit.count > 1) {
                    Spacer(Modifier.width(7.dp))
                    GlassChip(text = "看过 ${visit.count} 次", tint = tokens.textTertiary)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // Deleting one row is a small, reversible-by-revisiting action, so it is
        // one tap without a confirm. 清空 in the bar is the one that is not.
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onForget),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.textTertiary
            )
        }
    }
}
