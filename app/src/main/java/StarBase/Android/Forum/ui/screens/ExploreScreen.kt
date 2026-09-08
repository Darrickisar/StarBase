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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageSearch
import StarBase.Android.Forum.ui.components.WritingIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.Board
import StarBase.Android.Forum.data.Filters
import StarBase.Android.Forum.data.BoardTab
import StarBase.Android.Forum.data.RankRowData
import StarBase.Android.Forum.data.SearchEntry
import StarBase.Android.Forum.data.SearchLibrary
import StarBase.Android.Forum.data.SearchStore
import StarBase.Android.Forum.data.Searches
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.SearchApi
import StarBase.Android.Forum.net.SearchPage
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.SiteException
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
import StarBase.Android.Forum.ui.components.BlockedNote
import StarBase.Android.Forum.ui.components.BlockedRow
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
import StarBase.Android.Forum.ui.glass.GlassTabs
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
    GACHA("称号馆", "号", "", EntryWeight.QUIET),
    COLLECTIONS("淘帖", "淘", "", EntryWeight.QUIET),
    ESSENCE("申精", "精", "", EntryWeight.QUIET)
}

enum class EntryWeight { ANCHOR, MEDIUM, QUIET }

class ExploreViewModel(
    private val fetchSearch: suspend (String, Int) -> SearchPage = SearchApi::search
) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<Load<List<TopicCard>>?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var refreshError by mutableStateOf<Load.Failed?>(null)
        private set
    var moreError by mutableStateOf<Load.Failed?>(null)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    var accountId by mutableStateOf(-1)
        private set
    var library by mutableStateOf(SearchLibrary())
        private set
    var searchNotice by mutableStateOf("")
        private set

    private var searches: SearchStore? = null
    private var requestJob: Job? = null
    private var generation = 0L
    private var receivedQuery: Pair<Int, Long>? = null
    private var loginRevision = 0
    private var awaitingLogin = false

    fun receiveQuery(value: String, token: Long) {
        val request = accountId to token
        if (receivedQuery == request) return
        val newInput = receivedQuery?.second != token
        receivedQuery = request
        if (newInput) updateQuery(value)
        if (accountId > 0 && requestJob?.isActive != true) search()
    }

    fun bind(id: Int, storage: SearchStore? = null) {
        val normalized = id.coerceAtLeast(0)
        if (storage != null) searches = storage
        if (normalized != accountId) {
            val guestQuery = query.takeIf { accountId == 0 && normalized > 0 }
            clearSearch()
            accountId = normalized
            if (guestQuery != null) query = guestQuery else awaitingLogin = false
        }
        library = searches?.load(accountId) ?: SearchLibrary()
    }

    fun prepareLogin() { awaitingLogin = true }

    fun resumeAfterLogin(revision: Int) {
        if (revision == loginRevision) return
        loginRevision = revision
        val failedPage = moreError?.kind == SiteException.Kind.AUTH
        val needsRetry = awaitingLogin || failedPage || refreshError?.kind == SiteException.Kind.AUTH ||
            (results as? Load.Failed)?.kind == SiteException.Kind.AUTH
        awaitingLogin = false
        if (accountId <= 0 || requestJob?.isActive == true || !needsRetry) return
        if (failedPage && results is Load.Ready) loadMore() else search()
    }

    fun updateQuery(value: String) {
        if (query == value) return
        cancelRequest()
        query = value
        results = null
        nextPage = null
        searchNotice = ""
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        query = q
        updateLibrary(Searches.record(library, q, System.currentTimeMillis()))
        fetch(page = 1, keepResults = false)
    }

    fun runSearch(value: String) {
        updateQuery(value)
        search()
    }

    fun refreshVisible() {
        if (results != null && query.isNotBlank()) fetch(page = 1, keepResults = true)
    }

    fun loadMore() {
        val page = nextPage ?: return
        if (requestJob?.isActive == true || results !is Load.Ready) return
        fetch(page, keepResults = true)
    }

    private fun fetch(page: Int, keepResults: Boolean) {
        cancelRequest()
        val token = generation
        val q = query.trim()
        val owner = accountId
        val previous = (results as? Load.Ready)?.value
        val append = page > 1
        if (!keepResults || previous == null) results = Load.Loading
        if (!keepResults) nextPage = null
        refreshing = !append && keepResults && previous != null
        loadingMore = append
        requestJob = viewModelScope.launch {
            try {
                val answer = fetchSearch(q, page)
                currentCoroutineContext().ensureActive()
                if (token != generation || q != query.trim() || owner != accountId) return@launch
                val before = if (append) previous.orEmpty() else emptyList()
                val rows = if (answer.page == page) answer.topics.distinctBy { it.id } else emptyList()
                val merged = (before + rows).distinctBy { it.id }
                results = Load.Ready(merged)
                nextPage = answer.nextPage?.takeIf {
                    it > page && rows.isNotEmpty() && merged.size > before.size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != generation || owner != accountId) return@launch
                val failure = failureOf(e)
                when {
                    append -> moreError = failure
                    keepResults && previous != null -> refreshError = failure
                    else -> results = failure
                }
            } finally {
                if (token == generation) {
                    refreshing = false
                    loadingMore = false
                    requestJob = null
                }
            }
        }
    }

    private fun cancelRequest() {
        generation++
        requestJob?.cancel()
        requestJob = null
        refreshing = false
        loadingMore = false
        refreshError = null
        moreError = null
    }

    fun clearSearch() {
        cancelRequest()
        query = ""
        results = null
        nextPage = null
        searchNotice = ""
    }

    fun saveSearch(value: String = query) {
        val changed = Searches.save(library, value, System.currentTimeMillis())
        searchNotice = if (changed == library && library.saved.size >= Searches.SAVED_CAP &&
            library.saved.none { it.query == value.trim() }) "已保存搜索最多 ${Searches.SAVED_CAP} 条" else ""
        updateLibrary(changed)
    }

    fun pinSearch(value: String) = updateLibrary(Searches.pin(library, value))
    fun deleteSaved(value: String) = updateLibrary(Searches.removeSaved(library, value))
    fun deleteHistory(value: String) = updateLibrary(Searches.removeHistory(library, value))
    fun clearHistory() = updateLibrary(Searches.clearHistory(library))
    fun clearSaved() = updateLibrary(Searches.clearSaved(library))

    private fun updateLibrary(value: SearchLibrary) {
        library = value
        searches?.save(accountId, value)
    }
}

@Composable
fun ExploreScreen(
    vm: ExploreViewModel,
    /** 本地屏蔽 rules, applied to the search results. */
    store: UserStore,
    onTopic: (Int) -> Unit,
    onUser: (Int) -> Unit,
    onEntry: (DiscoverEntry) -> Unit,
    onLogin: () -> Unit,
    accountId: Int = 0,
    onBack: (() -> Unit)? = null,
    initialQuery: String? = null,
    queryToken: Long = 0L,
    onRecognize: (() -> Unit)? = null,
    loginRevision: Int = 0,
    sessionReady: Boolean = true
) {
    val context = LocalContext.current.applicationContext
    val searches = remember(context) { SearchStore(context) }
    LaunchedEffect(vm, accountId, searches, queryToken, loginRevision, sessionReady) {
        if (!sessionReady) return@LaunchedEffect
        vm.bind(accountId, searches)
        initialQuery?.let { vm.receiveQuery(it, queryToken) }
        vm.resumeAfterLogin(loginRevision)
    }
    if (!sessionReady || vm.accountId != accountId.coerceAtLeast(0)) {
        LoadingMark()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (onBack != null) DetailBar(title = "搜索社区", onBack = onBack)
        Refreshable(refreshing = vm.refreshing, onRefresh = vm::refreshVisible) {
            ExploreList(vm, store, onTopic, onUser, onEntry, { vm.prepareLogin(); onLogin() }, onBack == null, onRecognize)
        }
    }
}

@Composable
private fun ExploreList(
    vm: ExploreViewModel,
    store: UserStore,
    onTopic: (Int) -> Unit,
    onUser: (Int) -> Unit,
    onEntry: (DiscoverEntry) -> Unit,
    onLogin: () -> Unit,
    discovery: Boolean,
    onRecognize: (() -> Unit)?
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // Cleared with the query: the hidden rows belong to one set of results.
    var revealBlocked by remember(vm.query) { mutableStateOf(false) }
    var savedTab by remember(vm.accountId) { mutableStateOf(false) }
    val submit: () -> Unit = {
        keyboard?.hide()
        if (vm.accountId > 0) vm.search() else onLogin()
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        // §3.1 搜索首屏: title, then the bar immediately under it. 刷新 is a light
        // action on the title row, never a block of its own - and only while a
        // search is on screen, since nothing else here reloads.
        item("head") {
            if (discovery) PageHead(
                title = "发现",
                action = if (vm.results != null) (if (vm.refreshing) "刷新中" else "刷新") else null,
                onAction = vm::refreshVisible
            )
            SearchRow(value = vm.query, onValue = vm::updateQuery, onSubmit = submit, onRecognize = onRecognize)
            if (vm.accountId <= 0 && !discovery) {
                Row(Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    horizontalArrangement = Arrangement.End) {
                    LightAction("登录后搜索", onClick = onLogin)
                }
            }
            if (vm.query.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    val saved = vm.library.saved.any { it.query == vm.query.trim() }
                    LightAction(if (saved) "取消保存" else "保存搜索", onClick = {
                        if (saved) vm.deleteSaved(vm.query) else vm.saveSearch()
                    })
                    LightAction("清空", onClick = vm::clearSearch)
                }
            }
            if (vm.searchNotice.isNotEmpty()) {
                Box(Modifier.padding(horizontal = SbMetrics.pagePadding)) { MetaText(vm.searchNotice) }
            }
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
            vm.refreshError?.let { failure ->
                item("refresh-error") {
                    ErrorPanel(failure.message, failure.kind, vm::refreshVisible, onLogin)
                }
            }
            when (results) {
                is Load.Loading -> item("results-loading") { LoadingMark("正在搜索") }
                is Load.Failed -> item("results-error") {
                    ErrorPanel(results.message, results.kind, vm::search, onLogin)
                }
                is Load.Ready -> if (results.value.isEmpty()) {
                    item("results-empty") { EmptyPanel("没有找到相关帖子", "换个关键词试试") }
                } else {
                    // Searching for a blocked word is the one case where hiding the
                    // hit outright would be absurd, so the note sits right there.
                    val filtered = Filters.topics(store.blockRules, results.value)
                    itemsIndexed(filtered.visible, key = { _, t -> t.id }) { index, topic ->
                        if (index > 0) Hairline(startInset = 66)
                        TopicRow(
                            topic = topic,
                            onClick = { onTopic(topic.id) },
                            onAuthorClick = { onUser(topic.authorId) }
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
                }
            }
            if (results is Load.Ready && results.value.isNotEmpty()) {
                item("results-more") {
                    val failure = vm.moreError
                    when {
                        vm.loadingMore -> LoadingMark("正在加载更多")
                        failure != null -> ErrorPanel(failure.message, failure.kind, vm::loadMore, onLogin)
                        vm.nextPage != null -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            GlassButton("加载更多", onClick = vm::loadMore, enabled = !vm.refreshing)
                        }
                        !vm.refreshing -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            MetaText("已加载 ${results.value.size} 条结果")
                        }
                    }
                }
            }
            item("results-tail") { Gap(24) }
            return@LazyColumn
        }

        item("search-library-head") {
            Gap(16)
            GlassTabs(
                labels = listOf("搜索历史", "已保存"),
                selected = if (savedTab) 1 else 0,
                onSelect = { savedTab = it == 1 },
                modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)
            )
            val entries = if (savedTab) vm.library.saved else vm.library.history
            SectionHeader(
                title = if (savedTab) "已保存搜索" else "最近搜索",
                subtitle = "${entries.size} 条",
                trailing = if (entries.isEmpty()) null else "清空",
                onTrailingClick = { if (savedTab) vm.clearSaved() else vm.clearHistory() }
            )
            if (entries.isEmpty()) {
                Box(Modifier.padding(horizontal = SbMetrics.pagePadding, vertical = 12.dp)) {
                    MetaText(if (savedTab) "暂无保存的搜索" else "暂无搜索历史")
                }
            }
        }
        val entries = if (savedTab) vm.library.saved else vm.library.history
        items(entries, key = { "search-${if (savedTab) "saved" else "history"}-${it.query}" }) { entry ->
            SearchLibraryRow(
                entry = entry,
                saved = savedTab,
                alreadySaved = vm.library.saved.any { it.query == entry.query },
                onRun = { keyboard?.hide(); vm.runSearch(entry.query) },
                onSave = { vm.saveSearch(entry.query) },
                onPin = { vm.pinSearch(entry.query) },
                onDelete = { if (savedTab) vm.deleteSaved(entry.query) else vm.deleteHistory(entry.query) }
            )
            Hairline(startInset = 16)
        }

        if (discovery) item("entries") {
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

@Composable
private fun SearchLibraryRow(
    entry: SearchEntry,
    saved: Boolean,
    alreadySaved: Boolean,
    onRun: () -> Unit,
    onSave: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding)) {
        Text(
            text = entry.query,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalTokens.current.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "搜索", onClick = onRun).padding(vertical = 14.dp)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.pinned) MetaText("已置顶")
            Spacer(Modifier.weight(1f))
            if (saved) LightAction(if (entry.pinned) "取消置顶" else "置顶", onClick = onPin)
            else if (!alreadySaved) LightAction("保存", onClick = onSave)
            LightAction("删除", onClick = onDelete)
        }
    }
}

/**
 * §3.1 输入框与按钮同一行, 按钮宽度固定, 手机不换行.
 */
@Composable
private fun SearchRow(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onRecognize: (() -> Unit)?) {
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
        GlassButton(text = "搜索", onClick = onSubmit, enabled = value.isNotBlank(), modifier = Modifier.width(74.dp))
        if (onRecognize != null) WritingIconButton(Icons.Outlined.ImageSearch, "截图求助", onClick = onRecognize)
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
        quiet.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { entry ->
                    QuietEntry(entry, Modifier.weight(1f)) { onEntry(entry) }
                }
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

/** 底部低权重入口. */
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
