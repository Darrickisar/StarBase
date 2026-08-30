package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.ForumRef
import StarBase.Android.Forum.data.HomePage
import StarBase.Android.Forum.data.SiteStats
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.ErrorPanel
import StarBase.Android.Forum.ui.Freshness
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.ListFooter
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.LoadingMark
import StarBase.Android.Forum.ui.OnReturnToForeground
import StarBase.Android.Forum.ui.Refreshable
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.freshnessText
import StarBase.Android.Forum.ui.components.PageHead
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.components.TopicRow
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.GlyphTile
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/*
 * §02 首页.
 *
 * The old page opened with a tall stats card, then eight full-width hot rows,
 * then a scrolling strip of boards - three heavy blocks before the feed even
 * started. V5 keeps the same four sections and the same data, but pays for them
 * in far less vertical space: a light hero line with the counters on one row,
 * 每日热帖 as compact numbered lines, 板块 as a 2x2 grid with 全部 behind it, and
 * 社区动态 as one continuous stream of rows separated by hairlines.
 */

/**
 * The feed tabs across the top of 首页, in the order the site lists them, with
 * the site's own paths. 精华 is a page of its own rather than a sort key, but it
 * renders the same topic list, so the same parser reads all five.
 */
enum class HomeFeed(val label: String, val path: String) {
    LATEST("新评论", "/index.php?sort=comment"),
    NEW("新帖子", "/index.php?sort=post"),
    DIGEST("精华", "/topic_featured"),
    LOTTERY("抽奖", "/index.php?sort=lucky"),
    CARD("发卡", "/index.php?sort=card")
}
class HomeViewModel : ViewModel() {

    var state by mutableStateOf<Load<HomePage>>(Load.Loading)
        private set
    var feed by mutableStateOf(HomeFeed.LATEST)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set

    val rows = mutableStateListOf<TopicCard>()
    private var page = 1
    private var lastPage = 1
    private val fresh = Freshness()

    val hasMore: Boolean get() = page < lastPage

    /** Seconds since this feed last came off the wire, for the header line. */
    val ageSeconds: Long get() = fresh.ageSeconds

    /**
     * Guards against two loaders racing. Pull-to-refresh, the resume hook and
     * the first composition can all ask at once; the site should only be asked
     * once.
     */
    private var inFlight = false

    fun load(force: Boolean = false) {
        if (state is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            page = 1
            try {
                val home = fetch(1)
                rows.clear()
                rows += home.topics
                lastPage = home.lastPage
                state = Load.Ready(home)
                fresh.mark()
            } catch (e: Throwable) {
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                refreshing = false
                inFlight = false
            }
        }
    }

    /**
     * Re-fetches only if what is on screen has had time to go stale. Called when
     * the app returns to the foreground - and once when it starts, where the
     * feed has never been read and so counts as stale.
     */
    fun refreshIfStale() {
        if (fresh.stale) load(force = true)
    }

    fun switchFeed(next: HomeFeed) {
        if (next == feed) return
        if (inFlight) return
        inFlight = true
        feed = next
        viewModelScope.launch {
            refreshing = true
            page = 1
            try {
                val home = fetch(1)
                rows.clear()
                rows += home.topics
                lastPage = home.lastPage
                // Keep the header data from the first load; only the feed changed.
                val current = (state as? Load.Ready)?.value
                state = Load.Ready(current?.copy(topics = home.topics) ?: home)
                fresh.mark()
            } catch (e: Throwable) {
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                refreshing = false
                inFlight = false
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val next = fetch(page + 1)
                val known = rows.mapTo(HashSet()) { it.id }
                rows += next.topics.filter { it.id !in known }
                page += 1
                lastPage = maxOf(lastPage, next.lastPage)
            } catch (e: Throwable) {
                // A failed page-turn should not wipe what is already on screen.
            } finally {
                loadingMore = false
            }
        }
    }

    private suspend fun fetch(p: Int): HomePage = when (feed) {
        HomeFeed.LATEST -> Api.home(p)
        else -> Api.homeSorted(feed.path, p)
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onTopic: (Int) -> Unit,
    onForum: (Int) -> Unit,
    onAllForums: () -> Unit,
    onUser: (Int) -> Unit,
    onLogin: () -> Unit
) {
    // Fires once when the screen first appears, then again on every return to
    // the foreground - so the first read and the catch-up read are the same path
    // and cannot both fire at once.
    OnReturnToForeground { vm.refreshIfStale() }

    when (val s = vm.state) {
        is Load.Loading -> LoadingMark("正在连接 StarBase")
        is Load.Failed -> ErrorPanel(
            message = s.message,
            kind = s.kind,
            onRetry = { vm.load(force = true) },
            onLogin = onLogin
        )
        is Load.Ready -> Refreshable(
            refreshing = vm.refreshing,
            onRefresh = { vm.load(force = true) }
        ) {
            HomeContent(
                vm = vm,
                home = s.value,
                onTopic = onTopic,
                onForum = onForum,
                onAllForums = onAllForums,
                onUser = onUser
            )
        }
    }
}

@Composable
private fun HomeContent(
    vm: HomeViewModel,
    home: HomePage,
    onTopic: (Int) -> Unit,
    onForum: (Int) -> Unit,
    onAllForums: () -> Unit,
    onUser: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    // Infinite scroll: pull the next page once the tail is in view.
    LaunchedEffect(listState, vm.rows.size) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }.collect { atEnd -> if (atEnd) vm.loadMore() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth()
    ) {
        item("hero") {
            PageHead(
                title = "StarBase",
                action = if (vm.refreshing) "更新中" else "刷新",
                onAction = { vm.load(force = true) }
            )
            StatStrip(home.stats)
        }

        if (home.dailyHot.isNotEmpty()) {
            item("hot-header") {
                Gap(16)
                SectionHeader(title = "每日热帖")
                Gap(8)
            }
            item("hot-carousel") {
                HotCarousel(home.dailyHot.take(8), onTopic)
            }
        }

        if (home.forums.isNotEmpty()) {
            item("forums-header") {
                Gap(16)
                SectionHeader(
                    title = "板块直达",
                    trailing = "全部",
                    onTrailingClick = onAllForums
                )
                Gap(8)
            }
            item("forums-grid") {
                ForumStrip(home.forums.take(8), onForum)
            }
        }

        item("feed-header") {
            Gap(20)
            SectionHeader(title = "社区动态", subtitle = freshnessText(vm.ageSeconds, vm.refreshing))
            Gap(9)
            FeedTabs(current = vm.feed, onPick = vm::switchFeed)
            Gap(4)
        }

        if (vm.rows.isEmpty()) {
            item("feed-empty") {
                if (vm.refreshing) LoadingMark("正在切换") else EmptyPanel("这里还没有内容")
            }
        } else {
            itemsIndexed(
                items = vm.rows,
                key = { _, topic -> topic.id }
            ) { index, topic ->
                if (index > 0) Hairline(startInset = 66)
                TopicRow(
                    topic = topic,
                    onClick = { onTopic(topic.id) },
                    onAuthorClick = { onUser(topic.authorId) }
                )
            }
            item("footer") {
                ListFooter(
                    loading = vm.loadingMore,
                    hasMore = vm.hasMore,
                    onLoadMore = vm::loadMore
                )
            }
        }
        item("tail") { Gap(26) }
    }
}

/**
 * 数据总和 on one row. The old card spent ~150dp on the same four numbers; this
 * is a single low-glass strip under the page title, which is all the hero needs
 * to be.
 */
@Composable
private fun StatStrip(stats: SiteStats) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.LOW,
        padding = 13.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatCell("主题", stats.topics, Modifier.weight(1f))
            StatDivider()
            StatCell("回复", stats.replies, Modifier.weight(1f))
            StatDivider()
            StatCell("用户", stats.users, Modifier.weight(1f))
            if (stats.online.isNotBlank()) {
                StatDivider()
                StatCell("在线", stats.online, Modifier.weight(1f), highlight = true)
            }
        }
        if (stats.newestUser.isNotBlank()) {
            Gap(9)
            Text(
                text = "最新加入 · ${stats.newestUser}",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val tokens = LocalTokens.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) tokens.accentGlow else tokens.textPrimary,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary,
            maxLines = 1
        )
    }
}

@Composable
private fun StatDivider() {
    val tokens = LocalTokens.current
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(tokens.hairline)
    )
}

/**
 * 每日热帖 as a carousel: one topic per page, so the section costs a single card
 * instead of six stacked lines. It advances itself every four seconds and stops
 * doing that the moment a finger touches it.
 */
@Composable
private fun HotCarousel(topics: List<TopicCard>, onTopic: (Int) -> Unit) {
    val tokens = LocalTokens.current
    val state = rememberPagerState(pageCount = { topics.size })

    // The timer restarts from whichever page the swipe settled on, so a manual
    // swipe always gets its own full four seconds before the next auto-advance.
    if (topics.size > 1) {
        LaunchedEffect(state.settledPage, topics.size) {
            delay(4_000)
            if (!state.isScrollInProgress) {
                state.animateScrollToPage((state.settledPage + 1) % topics.size)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            contentPadding = PaddingValues(horizontal = SbMetrics.pagePadding),
            pageSpacing = 10.dp
        ) { page ->
            val topic = topics[page]
            HotSlide(rank = page + 1, topic = topic) { onTopic(topic.id) }
        }
        if (topics.size > 1) {
            Gap(9)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(topics.size) { index ->
                    val on = index == state.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (on) 6.dp else 5.dp)
                            .background(
                                color = if (on) tokens.accentWarm else tokens.textTertiary.copy(alpha = 0.45f),
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun HotSlide(rank: Int, topic: TopicCard, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        level = GlassLevel.LOW,
        padding = 13.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = if (rank <= 3) tokens.hotTint else tokens.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(22.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = topic.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = listOf(
                    topic.forumName,
                    topic.author,
                    if (topic.replies > 0) "${topic.replies} 回复" else ""
                ).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 板块直达 as one scrolling row of logo + name chips. Two 2x2 rows of cards cost
 * 141dp for four boards; this shows eight in 46dp, and 全部 in the header still
 * has the full list.
 */
@Composable
private fun ForumStrip(forums: List<ForumRef>, onForum: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SbMetrics.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        forums.forEach { forum ->
            ForumChip(forum) { onForum(forum.id) }
        }
    }
}

/** Logo and name only - the whole chip is the target. */
@Composable
private fun ForumChip(forum: ForumRef, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        level = GlassLevel.LOW,
        padding = 9.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphTile(
                glyph = forum.name.trim().take(1).ifBlank { "板" },
                size = 26.dp,
                tint = tokens.pinTint,
                corner = 8.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = forum.name,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 新评论 / 新帖子 / 精华 / 抽奖 / 发卡 */
@Composable
private fun FeedTabs(current: HomeFeed, onPick: (HomeFeed) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SbMetrics.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HomeFeed.entries.forEach { tab ->
            SegmentPill(
                label = tab.label,
                selected = tab == current,
                onClick = { onPick(tab) }
            )
        }
    }
}
