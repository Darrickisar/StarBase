package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.Board
import StarBase.Android.Forum.data.BoardTab
import StarBase.Android.Forum.data.RankRowData
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.ErrorPanel
import StarBase.Android.Forum.ui.Freshness
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.LoadingMark
import StarBase.Android.Forum.ui.OnReturnToForeground
import StarBase.Android.Forum.ui.Refreshable
import StarBase.Android.Forum.ui.ageLabel
import StarBase.Android.Forum.ui.components.LightAction
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.PageHead
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.components.TopicRow
import StarBase.Android.Forum.ui.components.UserAvatar
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassField
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.GlyphTile
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/*
 * §03 发现页.
 *
 * The old version opened with a search field, a five-tile grid of equal squares
 * and eight full-width hot rows - a first screen that was mostly air. V5 keeps
 * exactly the same seven entries and the same eight topics but changes their
 * weight: the search bar sits right under the page title, the entries become a
 * mosaic with 榜单 as its anchor, and 热门话题 becomes a fixed 2 x 4 grid of
 * equal cells carrying only 编号, 标题, 回复数.
 */

/**
 * Second-level destinations reachable from 发现.
 *
 * 榜单 lives here rather than in the bottom bar. The three that are also home
 * feeds ([DIGEST], [LOTTERY], [CARD]) jump to that feed instead of opening a
 * second copy of the same list. [weight] is the mosaic rank from §3.2: the
 * anchor card, the two medium cards, then the two name-only cards.
 */
enum class DiscoverEntry(
    val label: String,
    val glyph: String,
    val hint: String,
    val weight: EntryWeight
) {
    RANK("榜单", "榜", "活跃、发帖、积分排行", EntryWeight.ANCHOR),
    DIGEST("精华", "华", "被加精的好帖", EntryWeight.MEDIUM),
    LOTTERY("抽奖", "奖", "正在进行的抽奖", EntryWeight.MEDIUM),
    // §3.2 把这两项写成“仅显示名称”，所以它们本来就没有副说明。
    CARD("发卡", "卡", "", EntryWeight.QUIET),
    GACHA("称号馆", "号", "", EntryWeight.QUIET)
}

enum class EntryWeight { ANCHOR, MEDIUM, QUIET }

class ExploreViewModel : ViewModel() {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<Load<List<TopicCard>>?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private var inFlight = false

    fun updateQuery(value: String) { query = value }

    fun search() {
        val q = query.trim()
        if (q.isBlank()) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            if (results !is Load.Ready) results = Load.Loading else refreshing = true
            try {
                results = Load.Ready(Api.search(q))
            } catch (e: Throwable) {
                if (results !is Load.Ready) results = failureOf(e)
            } finally {
                refreshing = false
                inFlight = false
            }
        }
    }

    /**
     * A pull re-runs the search you are looking at. With no search on screen the
     * page is just the five entries, which are static - so there is nothing to
     * pull for and the gesture does nothing.
     */
    fun refreshVisible() {
        if (results != null) search()
    }

    fun clearSearch() {
        query = ""
        results = null
    }
}

@Composable
fun ExploreScreen(
    vm: ExploreViewModel,
    onTopic: (Int) -> Unit,
    onUser: (Int) -> Unit,
    onEntry: (DiscoverEntry) -> Unit,
    onLogin: () -> Unit
) {
    Refreshable(refreshing = vm.refreshing, onRefresh = vm::refreshVisible) {
        ExploreList(vm, onTopic, onUser, onEntry, onLogin)
    }
}

@Composable
private fun ExploreList(
    vm: ExploreViewModel,
    onTopic: (Int) -> Unit,
    onUser: (Int) -> Unit,
    onEntry: (DiscoverEntry) -> Unit,
    onLogin: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit: () -> Unit = {
        keyboard?.hide()
        vm.search()
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        // §3.1 搜索首屏: title, then the bar immediately under it. 刷新 is a light
        // action on the title row, never a block of its own - and only while a
        // search is on screen, since nothing else here reloads.
        item("head") {
            PageHead(
                title = "发现",
                action = if (vm.results != null) (if (vm.refreshing) "刷新中" else "刷新") else null,
                onAction = vm::refreshVisible
            )
            SearchRow(value = vm.query, onValue = vm::updateQuery, onSubmit = submit)
        }

        val results = vm.results
        if (results != null) {
            item("results-header") {
                Gap(16)
                SectionHeader(
                    title = "搜索结果",
                    subtitle = "“${vm.query.trim()}”",
                    trailing = "清空",
                    onTrailingClick = vm::clearSearch
                )
                Gap(4)
            }
            when (results) {
                is Load.Loading -> item("results-loading") { LoadingMark("正在搜索") }
                is Load.Failed -> item("results-error") {
                    ErrorPanel(results.message, results.kind, vm::search, onLogin)
                }
                is Load.Ready -> if (results.value.isEmpty()) {
                    item("results-empty") { EmptyPanel("没有找到相关帖子", "换个关键词试试") }
                } else {
                    itemsIndexed(results.value, key = { _, t -> t.id }) { index, topic ->
                        if (index > 0) Hairline(startInset = 66)
                        TopicRow(
                            topic = topic,
                            onClick = { onTopic(topic.id) },
                            onAuthorClick = { onUser(topic.authorId) }
                        )
                    }
                }
            }
            item("results-tail") { Gap(24) }
            return@LazyColumn
        }

        item("entries") {
            Gap(14)
            // §3.3 去掉重复性副说明: 副标题重写一遍下面五个入口的名字没有任何信息量。
            SectionHeader(title = "逛逛社区")
            Gap(10)
            EntryMosaic(onEntry)
        }

        // 热门话题 used to sit here. 首页 opens on the same list, so the second copy
        // was the only thing below 逛逛社区 and said nothing new.
        item("tail") { Gap(26) }
    }
}

/**
 * §3.1 输入框与按钮同一行, 按钮宽度固定, 手机不换行.
 */
@Composable
private fun SearchRow(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassField(
            value = value,
            onValue = onValue,
            placeholder = "搜索帖子标题、关键词",
            modifier = Modifier.weight(1f),
            glyph = "搜",
            keyboardOptions = KeyboardOptions.Default,
            imeAction = ImeAction.Search,
            onSubmit = onSubmit
        )
        Spacer(Modifier.width(9.dp))
        GlassButton(text = "搜索", onClick = onSubmit, modifier = Modifier.width(74.dp))
    }
}

/**
 * §3.2 Mosaic 入口. 榜单 is the anchor: a full-width card on the first row, so the
 * five entries read as one weighted group instead of five equal squares.
 */
@Composable
private fun EntryMosaic(onEntry: (DiscoverEntry) -> Unit) {
    val entries = DiscoverEntry.entries
    val anchor = entries.first { it.weight == EntryWeight.ANCHOR }
    val medium = entries.filter { it.weight == EntryWeight.MEDIUM }
    val quiet = entries.filter { it.weight == EntryWeight.QUIET }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        AnchorEntry(anchor) { onEntry(anchor) }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            medium.forEach { entry ->
                MediumEntry(entry, Modifier.weight(1f)) { onEntry(entry) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            quiet.forEach { entry ->
                QuietEntry(entry, Modifier.weight(1f)) { onEntry(entry) }
            }
        }
    }
}

/** 第一行横向主卡片, 权重最高. */
@Composable
private fun AnchorEntry(entry: DiscoverEntry, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        level = GlassLevel.MEDIUM,
        padding = 16.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphTile(glyph = entry.glyph, size = 42.dp, corner = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.textPrimary
                )
                Gap(3)
                Text(
                    text = entry.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.textTertiary
            )
        }
    }
}

/** 第二行两个中卡片: 精华 / 抽奖. */
@Composable
private fun MediumEntry(entry: DiscoverEntry, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = modifier,
        onClick = onClick,
        level = GlassLevel.LOW,
        padding = 14.dp
    ) {
        GlyphTile(glyph = entry.glyph, size = 32.dp)
        Gap(10)
        Text(
            text = entry.label,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.textPrimary
        )
        Gap(2)
        Text(
            text = entry.hint,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 底部两个低权重入口: 只显示名称. */
@Composable
private fun QuietEntry(entry: DiscoverEntry, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = modifier,
        onClick = onClick,
        level = GlassLevel.LOW,
        padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphTile(glyph = entry.glyph, size = 26.dp, corner = 9.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---- 榜单: the second-level page ---------------------------------------------

/**
 * 榜单.
 *
 * The site serves one 榜单 per page, so a tab switch is a fetch. Boards already
 * read are kept, which makes going back to one instant and lets the tab strip be
 * drawn from whichever board is in hand.
 */
class RankViewModel : ViewModel() {
    var state by mutableStateOf<Load<Board>>(Load.Loading)
        private set

    /** The site's own `type` value; blank until the first page tells us. */
    var tab by mutableStateOf("")
        private set
    var refreshing by mutableStateOf(false)
        private set

    private val fresh = Freshness()

    /**
     * The tab strip only, keyed by tab - not the rows.
     *
     * Every board page lists all five tabs, so keeping the strip lets a tab
     * switch draw its own header immediately. The rankings themselves are never
     * kept: switching back to a tab re-reads it, because a leaderboard that
     * moved while you were on another tab would otherwise show yesterday's
     * order as though it were current.
     */
    private val tabStrip = mutableMapOf<String, List<BoardTab>>()

    /** Guarded per tab: switching away must not have its request eaten. */
    private var inFlight: String? = null

    val ageSeconds: Long get() = fresh.ageSeconds

    /** The tab strip, from the board in hand - every board lists all five. */
    val tabs: List<BoardTab>
        get() = (state as? Load.Ready)?.value?.tabs
            ?: tabStrip.values.firstOrNull()
            ?: emptyList()

    fun load(force: Boolean = false) = fetch(tab, force)

    fun pick(key: String) {
        if (key == tab && state is Load.Ready) return
        tab = key
        // No copy to show: the tab is fetched, and until it answers this is a
        // load rather than a stale ranking with a spinner over it.
        state = Load.Loading
        fetch(key, force = true)
    }

    private fun fetch(key: String, force: Boolean) {
        if (inFlight == key) return
        inFlight = key
        viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            try {
                val board = Api.board(key)
                tabStrip[board.key.ifBlank { key }] = board.tabs
                // A late response for a tab the user already left must not be
                // written to the screen.
                if (inFlight == key) {
                    if (tab.isBlank()) tab = board.key
                    state = Load.Ready(board)
                    fresh.mark()
                }
            } catch (e: Throwable) {
                if (state !is Load.Ready && inFlight == key) state = failureOf(e)
            } finally {
                refreshing = false
                if (inFlight == key) inFlight = null
            }
        }
    }

    fun refreshIfStale() {
        if (fresh.stale) load(force = true)
    }
}

@Composable
fun RankScreen(
    vm: RankViewModel,
    onUser: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenSite: (String) -> Unit,
    onLogin: () -> Unit
) {
    OnReturnToForeground { vm.refreshIfStale() }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "榜单",
            subtitle = if (vm.refreshing) "正在获取最新榜单" else ageLabel(vm.ageSeconds),
            onBack = onBack,
            action = "刷新",
            onAction = { vm.load(force = true) }
        )
        // The tab strip belongs to the page, not to one board's payload: leaving
        // it up across a fetch is what makes switching feel like a switch rather
        // than a reload.
        if (vm.tabs.isNotEmpty()) {
            BoardTabs(tabs = vm.tabs, current = vm.tab, onPick = vm::pick)
        }
        when (val s = vm.state) {
            is Load.Loading -> LoadingMark("正在读取榜单")
            is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load(force = true) }, onLogin)
            is Load.Ready -> if (s.value.rows.isEmpty()) {
                Column {
                    EmptyPanel("没有读到榜单数据", "网站的排行榜结构可能变了")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        LightAction(text = "在网页中查看", onClick = { onOpenSite("${Site.BASE}/leaderboard") })
                    }
                }
            } else {
                val board = s.value
                LazyColumn {
                    if (board.subtitle.isNotBlank()) {
                        item("subtitle") {
                            Text(
                                text = board.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalTokens.current.textTertiary,
                                modifier = Modifier.padding(
                                    horizontal = SbMetrics.pagePadding,
                                    vertical = 2.dp
                                )
                            )
                            Gap(8)
                        }
                    }
                    itemsIndexed(
                        board.rows,
                        key = { _, r -> "${board.key}-${r.rank}-${r.userId}" }
                    ) { index, row ->
                        if (index > 0) Hairline(startInset = 62)
                        RankRow(row = row) { onUser(row.userId) }
                    }
                    item("tail") { Gap(24) }
                }
            }
        }
    }
}

/** The five 榜单 as one scrolling strip. */
@Composable
private fun BoardTabs(tabs: List<BoardTab>, current: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SbMetrics.pagePadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            SegmentPill(
                label = tab.label,
                selected = tab.key == current,
                onClick = { onPick(tab.key) }
            )
        }
    }
}

/** One ranked row. The medal colour follows the rank the site gave it. */
@Composable
private fun RankRow(row: RankRowData, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    val medalColor = when (row.rank) {
        1 -> tokens.hotTint
        2 -> tokens.accentWarm
        3 -> tokens.accentDeep
        else -> tokens.textTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (row.userId > 0) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = SbMetrics.pagePadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphTile(
            glyph = "${row.rank}",
            size = 26.dp,
            tint = medalColor,
            corner = 9.dp
        )
        Spacer(Modifier.width(11.dp))
        UserAvatar(name = row.name, url = row.avatar, size = 34.dp)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (row.group.isNotBlank()) {
                Gap(3)
                MetaText(row.group)
            }
        }
        if (row.count.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            GlassChip(text = row.count)
        }
    }
}
