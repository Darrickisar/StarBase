package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.ForumPage
import StarBase.Android.Forum.data.ForumRef
import StarBase.Android.Forum.data.BoardOrder
import StarBase.Android.Forum.data.Filters
import StarBase.Android.Forum.data.Boards
import StarBase.Android.Forum.data.RankedBoard
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.ErrorPanel
import StarBase.Android.Forum.ui.Freshness
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.ListFooter
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.LoadingMark
import StarBase.Android.Forum.ui.OnReturnToForeground
import StarBase.Android.Forum.ui.Refreshable
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.freshnessText
import StarBase.Android.Forum.ui.components.LightAction
import StarBase.Android.Forum.ui.components.BlockedNote
import StarBase.Android.Forum.ui.components.BlockedRow
import StarBase.Android.Forum.ui.components.PageHead
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.components.TopicRow
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.GlyphTile
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.glass.pressFeedback
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/** Board list. The site prints the board index in the home sidebar. */
class ForumListViewModel : ViewModel() {
    var state by mutableStateOf<Load<List<ForumRef>>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private val fresh = Freshness()
    private var inFlight = false

    val ageSeconds: Long get() = fresh.ageSeconds

    fun load(force: Boolean = false) {
        if (state is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            // Keep the list up while re-reading; only the very first load blanks.
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            try {
                state = Load.Ready(Api.home().forums)
                fresh.mark()
            } catch (e: Throwable) {
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                refreshing = false
                inFlight = false
            }
        }
    }

    /** Board membership changes rarely, so this only re-reads once it has aged. */
    fun refreshIfStale() {
        if (fresh.stale) load(force = true)
    }
}

@Composable
fun ForumListScreen(
    vm: ForumListViewModel,
    /** 常去 comes out of 浏览历史; the pins and the mode are stored preferences. */
    store: UserStore,
    onForum: (Int) -> Unit,
    onLogin: () -> Unit
) {
    // Fires on first appearance as well as on every return to the foreground,
    // so there is no separate startup load to double up with.
    OnReturnToForeground { vm.refreshIfStale() }

    when (val s = vm.state) {
        is Load.Loading -> LoadingMark("正在读取板块")
        is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load(force = true) }, onLogin)
        is Load.Ready -> Refreshable(
            refreshing = vm.refreshing,
            onRefresh = { vm.load(force = true) }
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item("head") {
                    PageHead(
                        title = "全部板块",
                        subtitle = freshnessText(vm.ageSeconds, vm.refreshing),
                        action = if (vm.refreshing) "刷新中" else "刷新",
                        onAction = { vm.load(force = true) }
                    )
                }
                if (s.value.isEmpty()) {
                    item("empty") { EmptyPanel("没有读到板块列表", "下拉刷新或稍后再试") }
                }

                // 常去: worked out from 浏览历史, so it is empty on a fresh install
                // and for anyone who turned history off. Nothing is stored for it.
                val frequent = Boards.frequent(s.value, store.history)
                if (frequent.isNotEmpty() && store.boardOrder != BoardOrder.FREQUENT) {
                    item("frequent") {
                        Gap(4)
                        FrequentStrip(frequent = frequent, onForum = onForum)
                    }
                }

                if (s.value.isNotEmpty()) {
                    item("order") {
                        Gap(14)
                        OrderRow(store = store)
                        Gap(10)
                    }
                }

                // Two per row. A board is a name and a count, which needs about
                // half the width it had - the old one-card-per-row layout spent a
                // full 70dp strip on each of them and pushed most of the list
                // below the fold.
                val arranged = Boards.arrange(
                    boards = s.value,
                    order = store.boardOrder,
                    pinned = store.pinnedBoards,
                    history = store.history
                )
                val pairs = arranged.chunked(2)
                itemsIndexed(pairs, key = { _, pair -> pair.first().board.id }) { index, pair ->
                    if (index > 0) Gap(8)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SbMetrics.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { row ->
                            ForumCard(
                                forum = row.board,
                                modifier = Modifier.weight(1f),
                                pinned = row.pinned,
                                visits = row.visits,
                                onClick = { onForum(row.board.id) },
                                onPin = { store.toggleBoardPin(row.board.id) }
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                if (store.pinnedBoards.isNotEmpty()) {
                    item("pin-note") {
                        Gap(12)
                        PinOrderPanel(boards = s.value, store = store)
                    }
                }
                item("tail") { Gap(20) }
            }
        }
    }
}

/** One board, half the row wide: glyph, name, count. No chevron - the whole cell
 *  is the target, and at this size an arrow only crowds the name. */
@Composable
private fun ForumCard(
    forum: ForumRef,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    /** Visits recorded in this board, for the 常去 line. 0 hides it. */
    visits: Int = 0,
    onClick: () -> Unit,
    onPin: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        level = if (pinned) GlassLevel.MEDIUM else GlassLevel.LOW,
        padding = 11.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphTile(
                glyph = forum.name.trim().take(1).ifBlank { "板" },
                size = 28.dp,
                tint = tokens.pinTint,
                corner = 9.dp
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = forum.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // The count comes off the page; the visit tally is the app's own,
                // so they are not run together into one line.
                val line = when {
                    visits > 0 && forum.topicCount.isNotBlank() -> "${forum.topicCount} · 去过 $visits 次"
                    visits > 0 -> "去过 $visits 次"
                    else -> forum.topicCount
                }
                if (line.isNotBlank()) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onPin != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (pinned) "已置顶" else "置顶",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pinned) tokens.pinTint else tokens.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onPin)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 常去板块.
 *
 * Computed from 浏览历史 every time this screen draws - there is no stored "常去"
 * list, and turning history off makes this strip disappear, which is the truthful
 * behaviour rather than a stale leftover.
 */
@Composable
private fun FrequentStrip(frequent: List<RankedBoard>, onForum: (Int) -> Unit) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SbMetrics.pagePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "常去",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "按你自己的浏览历史算的",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }
        Gap(8)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = SbMetrics.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(frequent, key = { it.board.id }) { row ->
                GlassChip(
                    text = row.board.name,
                    tint = tokens.pinTint,
                    onClick = { onForum(row.board.id) }
                )
            }
        }
    }
}

/** The 排序 switch. Three keys, one stored string. */
@Composable
private fun OrderRow(store: UserStore) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardOrder.entries.forEach { option ->
                SegmentPill(
                    label = option.label,
                    selected = option == store.boardOrder,
                    onClick = { store.updateBoardOrder(option) }
                )
            }
        }
        if (store.boardOrder == BoardOrder.FREQUENT) {
            Gap(6)
            Text(
                text = "没去过的板块保持站点的顺序，不会被打乱。",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }
    }
}

/**
 * Reordering the pinned boards.
 *
 * Only the pins can be moved by hand: a full drag-to-sort would need a stored
 * position for every board on the site, and pinning the few that matter gets the
 * same result for one integer each - see [Boards.movePin].
 */
@Composable
private fun PinOrderPanel(boards: List<ForumRef>, store: UserStore) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.LOW,
        shape = RoundedCornerShape(16.dp),
        padding = 13.dp
    ) {
        Column {
            Text(
                text = "置顶的板块",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary
            )
            Gap(3)
            Text(
                text = "置顶的排在最前面，哪种排序都一样。存的只是几个板块 id。",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
            Gap(9)
            store.pinnedBoards.forEachIndexed { index, id ->
                val name = boards.firstOrNull { it.id == id }?.name
                if (index > 0) Gap(6)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // A pin whose board is gone from the site keeps its slot
                        // rather than vanishing silently.
                        text = name ?: "#$id（不在列表里）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (name == null) tokens.textTertiary else tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (index > 0) {
                        SmallAction("上移", primary = false, onClick = {
                            store.moveBoardPin(id, up = true)
                        })
                        Spacer(Modifier.width(6.dp))
                    }
                    if (index < store.pinnedBoards.lastIndex) {
                        SmallAction("下移", primary = false, onClick = {
                            store.moveBoardPin(id, up = false)
                        })
                        Spacer(Modifier.width(6.dp))
                    }
                    SmallAction("取消", primary = false, onClick = { store.toggleBoardPin(id) })
                }
            }
        }
    }
}

// ---- a single board ---------------------------------------------------------

/**
 * Sort keys the board pages actually accept. The site offers exactly these two
 * tabs on a board, so the app offers exactly these two.
 */
enum class ForumSort(val label: String, val key: String) {
    DEFAULT("新评论", "comment"),
    NEW("新帖子", "post")
}

class ForumViewModel : ViewModel() {
    var state by mutableStateOf<Load<ForumPage>>(Load.Loading)
        private set
    var sort by mutableStateOf(ForumSort.DEFAULT)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set

    val rows = mutableStateListOf<TopicCard>()
    private var forumId = 0
    private var page = 1
    private var lastPage = 1
    private val fresh = Freshness()

    /** One request at a time: the resume hook and a pull can arrive together. */
    private var inFlight = false

    /** Same hazard as 帖子详情: see [TopicViewModel]'s own loadJob. */
    private var loadJob: Job? = null

    val hasMore: Boolean get() = page < lastPage
    val ageSeconds: Long get() = fresh.ageSeconds

    /**
     * Opens a board. Re-entering the board you were just on keeps the list on
     * screen but re-fetches behind it if it has aged, so you never scroll a
     * board that has since moved on.
     */
    fun open(id: Int) {
        if (id == forumId && state is Load.Ready) {
            if (fresh.stale) reload(initial = false)
            return
        }
        // Same hazard as 帖子详情: the board we are leaving must not publish its
        // answer here, and its request must not eat this one via the guard.
        loadJob?.cancel()
        loadJob = null
        inFlight = false

        forumId = id
        rows.clear()
        page = 1
        lastPage = 1
        sort = ForumSort.DEFAULT
        fresh.invalidate()
        reload(initial = true)
    }

    fun applySort(next: ForumSort) {
        if (next == sort) return
        sort = next
        reload(initial = false)
    }

    fun refresh() = reload(initial = false)

    fun refreshIfStale() {
        if (fresh.stale && state is Load.Ready) reload(initial = false)
    }

    private fun reload(initial: Boolean) {
        if (inFlight) return
        inFlight = true
        // Which board this request is for; see [ForumViewModel.open].
        val requested = forumId
        loadJob = viewModelScope.launch {
            if (initial) state = Load.Loading else refreshing = true
            page = 1
            try {
                val fp = Api.forum(requested, 1, sort.key)
                if (requested != forumId) return@launch
                rows.clear()
                rows += fp.topics
                lastPage = fp.lastPage
                state = Load.Ready(fp)
                fresh.mark()
            } catch (e: Throwable) {
                if (requested != forumId) return@launch
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                if (requested == forumId) {
                    refreshing = false
                    inFlight = false
                }
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        val requested = forumId
        viewModelScope.launch {
            try {
                val next = Api.forum(requested, page + 1, sort.key)
                // A page of the board we were reading must not be appended to the
                // one we are reading now.
                if (requested != forumId) return@launch
                val known = rows.mapTo(HashSet()) { it.id }
                rows += next.topics.filter { it.id !in known }
                page += 1
                lastPage = maxOf(lastPage, next.lastPage)
            } catch (e: Throwable) {
                // keep what is on screen
            } finally {
                if (requested == forumId) loadingMore = false
            }
        }
    }
}

@Composable
fun ForumScreen(
    forumId: Int,
    vm: ForumViewModel,
    /** 本地屏蔽 rules, applied as the list is drawn. */
    store: UserStore,
    onTopic: (Int) -> Unit,
    onUser: (Int) -> Unit,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    /** Opens 发新帖 with this board already chosen. */
    onNewTopic: (Int) -> Unit
) {
    LaunchedEffect(forumId) { vm.open(forumId) }
    OnReturnToForeground(forumId) { vm.refreshIfStale() }

    // Reset on the way to another board: what one board's rules caught says
    // nothing about the next one.
    var revealBlocked by remember(forumId) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = (vm.state as? Load.Ready)?.value?.name.orEmpty().ifBlank { "板块" },
            subtitle = freshnessText(vm.ageSeconds, vm.refreshing),
            onBack = onBack,
            action = "刷新",
            onAction = vm::refresh,
            secondAction = "发帖",
            onSecondAction = { onNewTopic(forumId) }
        )
        when (val s = vm.state) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(s.message, s.kind, vm::refresh, onLogin)
            is Load.Ready -> Refreshable(
                refreshing = vm.refreshing,
                onRefresh = vm::refresh
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, vm.rows.size) {
                    snapshotFlow {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= listState.layoutInfo.totalItemsCount - 4
                    }.collect { atEnd -> if (atEnd) vm.loadMore() }
                }
                LazyColumn(state = listState) {
                    item("sorts") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = SbMetrics.pagePadding, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ForumSort.entries.forEach { option ->
                                SegmentPill(
                                    label = option.label,
                                    selected = option == vm.sort,
                                    onClick = { vm.applySort(option) }
                                )
                            }
                        }
                    }
                    if (vm.rows.isEmpty()) {
                        item("empty") {
                            if (vm.refreshing) LoadingMark("正在切换") else EmptyPanel("这个板块还没有帖子")
                        }
                    } else {
                        val filtered = Filters.topics(store.blockRules, vm.rows)
                        itemsIndexed(filtered.visible, key = { _, t -> t.id }) { index, topic ->
                            if (index > 0) Hairline(startInset = 66)
                            TopicRow(
                                topic = topic,
                                onClick = { onTopic(topic.id) },
                                onAuthorClick = { onUser(topic.authorId) },
                                showForum = false
                            )
                        }
                        if (filtered.hiddenCount > 0) {
                            item("blocked") {
                                Hairline(startInset = 66)
                                BlockedNote(
                                    count = filtered.hiddenCount,
                                    revealed = revealBlocked,
                                    onToggle = { revealBlocked = !revealBlocked }
                                )
                            }
                            if (revealBlocked) {
                                items(filtered.hidden, key = { "blocked-" + it.item.id }) { blocked ->
                                    BlockedRow(blocked) { onTopic(blocked.item.id) }
                                }
                            }
                        }
                        item("footer") {
                            ListFooter(vm.loadingMore, vm.hasMore, vm::loadMore)
                        }
                    }
                    item("tail") { Gap(20) }
                }
            }
        }
    }
}

/**
 * §05 顶部栏: 返回, 标题, 至多两个轻动作 (收藏 / 刷新), 高度 54-64dp.
 *
 * It is deliberately not a Material app bar: one row of light glass pills over
 * the ambient room, closed by a hairline, so a detail page opens on its own
 * content rather than on a heavy header.
 */
@Composable
fun DetailBar(
    title: String,
    subtitle: String = "",
    onBack: () -> Unit,
    action: String = "",
    onAction: (() -> Unit)? = null,
    secondAction: String = "",
    onSecondAction: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val backInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .pressFeedback(backInteraction)
                    .liquidGlass(
                        shape = RoundedCornerShape(50),
                        level = GlassLevel.LOW,
                        refract = false
                    )
                    .clickable(
                        interactionSource = backInteraction,
                        indication = null,
                        onClick = onBack
                    )
                    .size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "‹",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.textSecondary
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (secondAction.isNotBlank() && onSecondAction != null) {
                LightAction(text = secondAction, onClick = onSecondAction)
                Spacer(Modifier.width(6.dp))
            }
            if (action.isNotBlank() && onAction != null) {
                LightAction(text = action, onClick = onAction)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.hairline)
        )
    }
}
