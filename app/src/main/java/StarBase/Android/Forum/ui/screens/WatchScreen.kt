package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.History
import StarBase.Android.Forum.data.ReadMark
import StarBase.Android.Forum.data.Reading
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.data.WatchStatus
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/*
 * 追帖看板.
 *
 * The site has no notion of "threads I am following", so this is the app's own -
 * and what makes it worth having is precisely that it is a client: opening this
 * page fetches the handful of topics you marked, all at once, and reports which
 * ones grew. The website will never do that for you, because a page load is one
 * page.
 *
 * The honest limits, stated in the UI rather than hidden:
 *
 * - **No background anything.** Nothing is checked until this screen is open and
 *   you asked. There is no service, no poll, no push - see 本机提醒 for the one
 *   thing that can reach you with the app closed, and even that is a local clock.
 * - **N topics is N requests.** Capped at [Reading.WATCH_CAP], fetched
 *   [Reading.WATCH_BATCH] at a time so a board of 20 does not open 20 sockets.
 * - **Only a count is stored.** 「多了 12 条」 is the live reply count minus the
 *   number recorded when you last read it. No post is kept.
 */

class WatchViewModel : ViewModel() {

    /** One row per watched topic, in the order [Reading.rank] puts them. */
    var rows by mutableStateOf<List<WatchStatus>>(emptyList())
        private set

    var checking by mutableStateOf(false)
        private set

    /** How many of this pass have come back, for the progress line. */
    var done by mutableStateOf(0)
        private set

    var notice by mutableStateOf("")
        private set

    /** True once a pass has completed, so "nothing new" can be said honestly. */
    var checked by mutableStateOf(false)
        private set

    private var inFlight = false

    /**
     * Shows the stored list straight away, unchecked.
     *
     * The rows appear with their titles and their last-read time before anything
     * is fetched: that part is already known, and a spinner over a list we can
     * draw would be worse.
     */
    fun show(marks: List<ReadMark>) {
        val watched = Reading.watched(marks)
        val byId = rows.associateBy { it.mark.topicId }
        rows = Reading.rank(
            watched.map { mark ->
                // Keep any live number already fetched for this topic.
                byId[mark.topicId]?.copy(mark = mark) ?: WatchStatus(mark)
            }
        )
    }

    /**
     * Fetches every watched topic and reports what grew.
     *
     * Deliberately explicit: this is the only thing in the app that makes several
     * requests off one tap, so it runs in small batches and shows its progress.
     */
    fun check(store: UserStore) {
        if (inFlight) return
        val watched = Reading.watched(store.readMarks)
        if (watched.isEmpty()) {
            rows = emptyList()
            checked = true
            return
        }
        inFlight = true
        checking = true
        done = 0
        notice = ""
        viewModelScope.launch {
            val results = ArrayList<WatchStatus>(watched.size)
            try {
                watched.chunked(Reading.WATCH_BATCH).forEach { batch ->
                    val batchResults = coroutineScope {
                        batch.map { mark ->
                            async {
                                try {
                                    val detail = Api.topic(mark.topicId, 1)
                                    WatchStatus(
                                        mark = mark,
                                        liveTotal = detail.commentCount,
                                        liveTitle = detail.title
                                    )
                                } catch (e: Throwable) {
                                    // One dead topic must not sink the board.
                                    WatchStatus(mark = mark, error = e.message ?: "读取失败")
                                }
                            }
                        }.awaitAll()
                    }
                    results += batchResults
                    done = results.size
                    rows = Reading.rank(results + watched.drop(results.size).map { WatchStatus(it) })
                }
                rows = Reading.rank(results)
                // The live titles are worth keeping: a renamed topic should not sit
                // in the list under its old name forever.
                results.forEach { row ->
                    if (row.checked && row.liveTitle.isNotBlank() && row.liveTitle != row.mark.title) {
                        store.recordRead(
                            topicId = row.mark.topicId,
                            total = row.mark.seenTotal,
                            title = row.liveTitle,
                            now = row.mark.at.coerceAtLeast(1L)
                        )
                    }
                }
                val failed = results.count { it.error.isNotBlank() }
                if (failed > 0) notice = "$failed 个帖子没读到"
                checked = true
            } finally {
                checking = false
                inFlight = false
            }
        }
    }

    fun clearNotice() { notice = "" }
}

@Composable
fun WatchScreen(
    vm: WatchViewModel,
    store: UserStore,
    onBack: () -> Unit,
    onTopic: (Int) -> Unit
) {
    // The stored list first, then one check. Re-entering does not re-fetch: this
    // costs several requests, so it happens when asked for.
    LaunchedEffect(store.readMarks) { vm.show(store.readMarks) }
    LaunchedEffect(Unit) { if (!vm.checked) vm.check(store) }

    val tokens = LocalTokens.current
    val watched = Reading.watched(store.readMarks)

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "追帖",
            subtitle = when {
                vm.checking -> "正在看 ${vm.done}/${watched.size}"
                vm.checked && watched.isNotEmpty() -> "${watched.size}/${Reading.WATCH_CAP} 个帖子"
                else -> ""
            },
            onBack = onBack,
            action = if (vm.checking) "检查中" else "检查更新",
            onAction = { vm.check(store) }
        )

        if (vm.notice.isNotBlank()) {
            NoticeBar(vm.notice) { vm.clearNotice() }
        }

        if (watched.isEmpty()) {
            EmptyPanel(
                "还没有追的帖子",
                "在帖子页右上角点「追帖」，这一页就会告诉你它们有没有新回复"
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item("head") {
                Gap(6)
                SectionHeader(
                    title = "有新回复",
                    subtitle = if (vm.checked) {
                        val fresh = vm.rows.count { it.fresh > 0 }
                        if (fresh > 0) "$fresh 个" else "都看过了"
                    } else {
                        "打开这页才会去看"
                    }
                )
                Gap(8)
            }

            items(vm.rows, key = { it.mark.topicId }) { row ->
                WatchRow(
                    row = row,
                    onOpen = { onTopic(row.mark.topicId) },
                    onDrop = { store.toggleWatch(row.mark.topicId) }
                )
                Gap(8)
            }

            item("note") {
                Gap(10)
                Text(
                    text = "这一页只在你打开或点「检查更新」时联网，一个帖子一次请求。" +
                        "App 没有后台任务，关掉就什么都不做。",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary,
                    modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)
                )
                Gap(24)
            }
        }
    }
}

@Composable
private fun WatchRow(row: WatchStatus, onOpen: () -> Unit, onDrop: () -> Unit) {
    val tokens = LocalTokens.current
    val fresh = row.fresh
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding),
        onClick = onOpen,
        level = if (fresh > 0) GlassLevel.MEDIUM else GlassLevel.LOW,
        shape = RoundedCornerShape(16.dp),
        padding = 13.dp
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.liveTitle.ifBlank { row.mark.title }.ifBlank { "#${row.mark.topicId}" },
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (fresh > 0) {
                    Spacer(Modifier.width(8.dp))
                    GlassChip(text = "+$fresh", tint = tokens.hotTint)
                }
            }
            Gap(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaText(
                    when {
                        row.error.isNotBlank() -> row.error
                        !row.checked -> "还没检查"
                        fresh > 0 -> "自你上次看之后多了 $fresh 条"
                        else -> "没有新回复"
                    }
                )
                Spacer(Modifier.weight(1f))
                if (row.mark.seenFloor > 0) {
                    MetaText("读到 ${row.mark.seenFloor} 楼")
                    Spacer(Modifier.width(8.dp))
                }
                SmallAction("不追了", primary = false, onClick = onDrop)
            }
        }
    }
}
