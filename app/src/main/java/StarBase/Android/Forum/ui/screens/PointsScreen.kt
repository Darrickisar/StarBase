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
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.PointsEntry
import StarBase.Android.Forum.data.PointsPage
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.ErrorPanel
import StarBase.Android.Forum.ui.Freshness
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.LoadingMark
import StarBase.Android.Forum.ui.OnReturnToForeground
import StarBase.Android.Forum.ui.Refreshable
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.freshnessText
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/*
 * 我的积分记录.
 *
 * 我的 used to send 积分 to `Route.User(me.id, "我的积分")`, and that route's
 * default tab is 主题 - so the entry titled 积分 showed your posts. The site keeps
 * an actual ledger at `?tab=points_rewards`: every change, its reason, the topic
 * it was about, and a signed amount. This screen is that ledger.
 *
 * It is the site's record, not a local tally. Nothing is stored here, and the
 * 「见过的积分」 idea from docs/app-ideas.md (#7) is not what this is - that one
 * would only know what the app happened to see, whereas this is the whole thing,
 * paged, straight off the page that was just fetched.
 */

class PointsViewModel : ViewModel() {
    var state by mutableStateOf<Load<PointsPage>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set

    /** Rows collected across the pages read so far, oldest request first. */
    val entries = mutableListOf<PointsEntry>()

    private var userId = 0
    private var page = 1
    private var lastPage = 1
    private val fresh = Freshness()
    private var inFlight = false

    val hasMore: Boolean get() = page < lastPage
    val ageSeconds: Long get() = fresh.ageSeconds

    /** Whose ledger. Switching accounts drops the rows already on screen. */
    fun bind(id: Int) {
        if (id == userId) return
        userId = id
        entries.clear()
        page = 1
        lastPage = 1
        state = Load.Loading
        fresh.invalidate()
    }

    fun load(force: Boolean = false) {
        if (userId <= 0) return
        if (state is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        val requested = userId
        viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            page = 1
            try {
                val fetched = Api.points(requested, 1)
                if (requested != userId) return@launch
                entries.clear()
                entries += fetched.entries
                lastPage = fetched.lastPage
                state = Load.Ready(fetched)
                fresh.mark()
            } catch (e: Throwable) {
                if (requested != userId) return@launch
                if (state !is Load.Ready) state = failureOf(e) else notice = e.message ?: "刷新失败"
            } finally {
                if (requested == userId) {
                    refreshing = false
                    inFlight = false
                }
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore || userId <= 0) return
        loadingMore = true
        val requested = userId
        viewModelScope.launch {
            try {
                val next = Api.points(requested, page + 1)
                if (requested != userId) return@launch
                // The ledger is append-only and ordered, but a row that arrived
                // while paging would otherwise show up on two pages.
                val known = entries.mapTo(HashSet()) { it.reason to it.at }
                entries += next.entries.filterNot { (it.reason to it.at) in known }
                page += 1
                lastPage = maxOf(lastPage, next.lastPage)
            } catch (e: Throwable) {
                if (requested == userId) notice = e.message ?: "加载更多失败"
            } finally {
                if (requested == userId) loadingMore = false
            }
        }
    }

    fun refreshIfStale() {
        if (fresh.stale && state is Load.Ready) load(force = true)
    }

    fun clearNotice() { notice = "" }
}

@Composable
fun PointsScreen(
    vm: PointsViewModel,
    signedIn: Boolean,
    userId: Int,
    /** Your current balance, as the session already read it off the sidebar. */
    balance: String,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onTopic: (Int) -> Unit
) {
    LaunchedEffect(userId) { if (userId > 0) vm.bind(userId) }
    OnReturnToForeground(signedIn to userId) {
        if (signedIn && userId > 0) {
            if (vm.state is Load.Ready) vm.refreshIfStale() else vm.load()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "我的积分",
            subtitle = if (signedIn) freshnessText(vm.ageSeconds, vm.refreshing) else "",
            onBack = onBack,
            action = "刷新",
            onAction = { vm.load(force = true) }
        )
        if (!signedIn) {
            SignInPrompt("登录后查看积分记录", onLogin)
            return@Column
        }
        when (val s = vm.state) {
            is Load.Loading -> LoadingMark("正在读取积分记录")
            is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load(force = true) }, onLogin)
            is Load.Ready -> Refreshable(
                refreshing = vm.refreshing,
                onRefresh = { vm.load(force = true) }
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item("balance") {
                        BalanceCard(balance = balance, entries = vm.entries)
                    }
                    if (vm.entries.isEmpty()) {
                        item("empty") {
                            EmptyPanel("还没有积分变动", "签到、打赏和抽卡都会记在这里")
                        }
                    } else {
                        item("ledger-head") {
                            Gap(16)
                            SectionHeader(title = "积分明细", subtitle = "来自 linux.sb")
                            Gap(6)
                        }
                        itemsIndexed(
                            vm.entries,
                            key = { i, e -> "$i-${e.at}-${e.delta}" }
                        ) { index, entry ->
                            if (index > 0) Hairline(startInset = 16)
                            PointsRow(entry) { if (entry.topicId > 0) onTopic(entry.topicId) }
                        }
                    }
                    if (vm.hasMore) {
                        item("more") {
                            Gap(12)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                SmallAction(
                                    text = if (vm.loadingMore) "加载中" else "加载更多",
                                    primary = false,
                                    onClick = { vm.loadMore() }
                                )
                            }
                        }
                    }
                    if (s.value.rules.isNotEmpty()) {
                        item("rules") { RuleCard(s.value) }
                    }
                    item("tail") { Gap(24) }
                }
            }
        }
    }
}

/**
 * 当前积分 plus what the rows on screen add up to.
 *
 * The sum is labelled as being about the rows that were read, not about a
 * period: only the pages fetched so far are in it, and saying 「本月」 when page
 * two has not been read would be wrong.
 */
@Composable
private fun BalanceCard(balance: String, entries: List<PointsEntry>) {
    val tokens = LocalTokens.current
    val gained = entries.filter { it.delta > 0 }.sumOf { it.delta }
    val spent = entries.filter { it.delta < 0 }.sumOf { -it.delta }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.MEDIUM,
        padding = 16.dp
    ) {
        Column {
            Text(
                text = "当前积分",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textTertiary
            )
            Gap(4)
            Text(
                text = balance.ifBlank { "—" },
                style = MaterialTheme.typography.headlineSmall,
                color = tokens.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            if (entries.isNotEmpty()) {
                Gap(12)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "+$gained",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.hotTint,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "-$spent",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "已读 ${entries.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun PointsRow(entry: PointsEntry, onTopic: () -> Unit) {
    val tokens = LocalTokens.current
    val up = entry.delta > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (entry.topicId > 0) Modifier.clickable(onClick = onTopic) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.reason.ifBlank { "积分变动" },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.topicTitle.isNotBlank()) {
                Gap(3)
                Text(
                    text = entry.topicTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.timeText.isNotBlank()) {
                Gap(3)
                MetaText(entry.timeText)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = (if (up) "+" else "") + entry.delta.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = if (up) tokens.hotTint else tokens.textSecondary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 积分规则, in the site's own words so a rule change here needs no code change. */
@Composable
private fun RuleCard(page: PointsPage) {
    val tokens = LocalTokens.current
    Gap(18)
    SectionHeader(title = "积分规则", subtitle = "站点规则")
    Gap(6)
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.LOW,
        padding = 14.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            if (page.ruleNote.isNotBlank()) {
                Text(
                    text = page.ruleNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary
                )
                Gap(10)
            }
            page.rules.forEachIndexed { index, rule ->
                if (index > 0) Gap(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.action,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = rule.value,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (rule.disabled) tokens.textTertiary else tokens.textPrimary
                    )
                }
            }
        }
    }
}
