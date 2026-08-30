package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.GachaAction
import StarBase.Android.Forum.data.GachaPage
import StarBase.Android.Forum.data.GachaRarity
import StarBase.Android.Forum.data.GachaResult
import StarBase.Android.Forum.data.GachaTitle
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.ErrorPanel
import StarBase.Android.Forum.ui.Freshness
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.LoadingMark
import StarBase.Android.Forum.ui.OnReturnToForeground
import StarBase.Android.Forum.ui.Refreshable
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.components.Chip
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.SbCard
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.tierColor
import StarBase.Android.Forum.ui.components.tierLabel
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.freshnessText
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/**
 * 称号馆, drawn by the app rather than handed to a WebView.
 *
 * The page is read as HTML and rendered as our own sections; the pull buttons
 * are the site's own forms, carried whole, so pressing one posts exactly what
 * the site's page would have posted.
 */
class GachaViewModel : ViewModel() {
    var state by mutableStateOf<Load<GachaPage>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** The button waiting on a 确认 tap. A pull spends currency, so it asks. */
    var pending by mutableStateOf<GachaAction?>(null)
        private set
    var pulling by mutableStateOf(false)
        private set
    var result by mutableStateOf<GachaResult?>(null)
        private set
    var pullError by mutableStateOf("")
        private set

    private val fresh = Freshness()
    private var inFlight = false

    val ageSeconds: Long get() = fresh.ageSeconds

    fun load(force: Boolean = false) {
        if (state is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            try {
                state = Load.Ready(Api.gacha())
                fresh.mark()
            } catch (e: Throwable) {
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                refreshing = false
                inFlight = false
            }
        }
    }

    /** Counters and 我的称号 both move underneath us, so a stale read re-fetches. */
    fun openOrRefresh() {
        if (state !is Load.Ready || fresh.stale) load(force = true)
    }

    fun ask(action: GachaAction) {
        pullError = ""
        pending = action
    }

    fun cancel() {
        pending = null
    }

    fun confirm() {
        val action = pending ?: return
        pending = null
        if (pulling) return
        pulling = true
        viewModelScope.launch {
            try {
                result = Api.gachaPull(action)
                // The pull changed the counters and the collection, so re-read.
                fresh.invalidate()
                load(force = true)
            } catch (e: Throwable) {
                pullError = e.message?.takeIf { it.isNotBlank() } ?: "抽取失败，请稍后再试"
            } finally {
                pulling = false
            }
        }
    }

    fun dismissResult() {
        result = null
    }
}

@Composable
fun GachaScreen(
    vm: GachaViewModel,
    signedIn: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenSite: (String) -> Unit
) {
    OnReturnToForeground(signedIn) { if (signedIn) vm.openOrRefresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DetailBar(
                title = "称号馆",
                subtitle = if (signedIn) freshnessText(vm.ageSeconds, vm.refreshing) else "",
                onBack = onBack,
                action = "刷新",
                onAction = { vm.load(force = true) },
                // 市场 / 合成 / 回收 are their own pages the app does not model.
                secondAction = "网页版",
                onSecondAction = { onOpenSite(Site.GACHA) }
            )
            if (!signedIn) {
                SignInPrompt("登录后进入称号馆", onLogin)
                return@Column
            }
            when (val s = vm.state) {
                is Load.Loading -> LoadingMark("正在打开称号馆")
                is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load(force = true) }, onLogin)
                is Load.Ready -> Refreshable(
                    refreshing = vm.refreshing,
                    onRefresh = { vm.load(force = true) }
                ) {
                    GachaList(page = s.value, vm = vm)
                }
            }
        }

        vm.result?.let { PullResultOverlay(it, vm::dismissResult) }
    }
}

@Composable
private fun GachaList(page: GachaPage, vm: GachaViewModel) {
    val nothingToShow = page.stats.isEmpty() && page.owned.isEmpty() &&
        page.all.isEmpty() && page.pulls.isEmpty() && page.rarities.isEmpty()

    LazyColumn {
        if (page.heading.isNotBlank() || page.intro.isNotBlank() || page.stats.isNotEmpty()) {
            item("head") {
                Gap(12)
                GachaHead(page)
            }
        }

        page.equipped?.let { equipped ->
            item("equipped") {
                Gap(18)
                SectionHeader(title = "当前佩戴")
                Gap(8)
                Box(modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)) {
                    TitleCard(equipped)
                }
            }
        }

        if (page.news.isNotEmpty()) {
            item("news") {
                Gap(18)
                SectionHeader(title = "好消息")
                Gap(8)
                NewsCarousel(page.news)
            }
        }

        if (page.pulls.isNotEmpty()) {
            item("pulls") {
                Gap(18)
                SectionHeader(title = "抽取称号", subtitle = "抽取会消耗积分")
                Gap(8)
                PullBox(page.pulls, vm)
            }
        }

        if (page.owned.isNotEmpty()) {
            item("owned-head") {
                Gap(18)
                SectionHeader(
                    title = "我的称号",
                    subtitle = page.ownedHeading.ifBlank { "共 ${page.owned.size} 个" }
                )
                Gap(8)
            }
            itemsIndexed(page.owned, key = { i, t -> "own-$i-${t.name}" }) { index, title ->
                if (index > 0) Gap(8)
                Box(modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)) {
                    TitleCard(title)
                }
            }
        }

        if (page.rarities.isNotEmpty()) {
            item("pool") {
                Gap(18)
                SectionHeader(title = page.poolHeading.ifBlank { "奖池出率" })
                Gap(8)
                RarityCard(page.rarities)
            }
        }

        if (page.all.isNotEmpty()) {
            item("all-head") {
                Gap(18)
                SectionHeader(
                    title = page.allHeading.ifBlank { "全部称号" },
                    subtitle = "共 ${page.all.size} 个"
                )
                Gap(8)
            }
            // Two per row, badge and tier only. The catalogue is for browsing what
            // exists - 我的称号 above is where the descriptions matter.
            val rows = page.all.chunked(2)
            itemsIndexed(rows, key = { i, row -> "all-$i-${row.first().name}" }) { index, row ->
                if (index > 0) Gap(8)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SbMetrics.pagePadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { title -> TitleTile(title, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (nothingToShow) {
            item("empty") {
                EmptyPanel("这个页面暂时没有可显示的内容", "可以用右上角的「网页版」打开原页面")
            }
        }

        item("tail") { Gap(28) }
    }
}

/** The header block: name, one line of copy, and the counters as chips. */
@Composable
private fun GachaHead(page: GachaPage) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)) {
        SbCard {
            if (page.heading.isNotBlank()) {
                Text(
                    text = page.heading,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary
                )
            }
            if (page.intro.isNotBlank()) {
                Gap(6)
                Text(
                    text = page.intro,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary
                )
            }
            if (page.stats.isNotEmpty()) {
                Gap(12)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    page.stats.forEach { stat ->
                        Chip(
                            text = stat,
                            tint = tokens.accentGlow,
                            container = tokens.accentWarm.copy(alpha = 0.10f)
                        )
                    }
                }
            }
            if (page.subStats.isNotBlank()) {
                Gap(10)
                MetaText(page.subStats)
            }
        }
    }
}

/**
 * 好消息 as a carousel. The site runs these as a marquee, and there can be a
 * dozen of them - unrolled into a list they pushed the pull buttons off the
 * screen, so one line shows at a time and advances itself.
 */
@Composable
private fun NewsCarousel(lines: List<String>) {
    val tokens = LocalTokens.current
    val state = rememberPagerState(pageCount = { lines.size })

    // Restarting from the settled page gives a manual swipe its own full delay
    // before the next auto-advance takes over.
    if (lines.size > 1) {
        LaunchedEffect(state.settledPage, lines.size) {
            delay(3_600)
            if (!state.isScrollInProgress) {
                state.animateScrollToPage((state.settledPage + 1) % lines.size)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            contentPadding = PaddingValues(horizontal = SbMetrics.pagePadding),
            pageSpacing = 10.dp
        ) { page ->
            SbCard(level = GlassLevel.LOW, padding = 12.dp) {
                Text(
                    text = lines[page],
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (lines.size > 1) {
            Gap(7)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(lines.size) { index ->
                    val on = index == state.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (on) 6.dp else 5.dp)
                            .background(
                                color = if (on) tokens.accentWarm
                                else tokens.textTertiary.copy(alpha = 0.45f),
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

/**
 * One title at catalogue size: badge and tier only, two to a row. 全部称号 can
 * run to dozens of entries, and at full-card size the section alone was longer
 * than the rest of the page put together.
 */
@Composable
private fun TitleTile(title: GachaTitle, modifier: Modifier = Modifier) {
    val tint = tierColor(title.tier)
    SbCard(modifier = modifier, corner = 16.dp, level = GlassLevel.LOW, padding = 10.dp) {
        TitleBadge(title)
        if (title.tier.isNotBlank()) {
            Gap(6)
            Text(
                text = tierLabel(title.tier),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The pull buttons. A tap arms the button rather than firing it, because this
 * spends the account's points and there is no undo on the site's side.
 */
@Composable
private fun PullBox(actions: List<GachaAction>, vm: GachaViewModel) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)) {
        SbCard(level = GlassLevel.LOW, padding = 14.dp) {
            val armed = vm.pending
            if (armed != null) {
                Text(
                    text = "确认执行「${armed.label}」？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary
                )
                Gap(10)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        text = "确认",
                        onClick = vm::confirm,
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                    GlassButton(
                        text = "取消",
                        onClick = vm::cancel,
                        modifier = Modifier.weight(1f),
                        primary = false,
                        compact = true
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    actions.forEachIndexed { index, action ->
                        GlassButton(
                            text = if (vm.pulling) "正在抽取…" else action.label,
                            onClick = { vm.ask(action) },
                            modifier = Modifier.fillMaxWidth(),
                            primary = index == 0,
                            enabled = action.enabled && !vm.pulling
                        )
                    }
                }
            }
            if (vm.pullError.isNotBlank()) {
                Gap(10)
                Text(
                    text = vm.pullError,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.hotTint
                )
            }
        }
    }
}

/** 出率 as a plain three-column read: 名称 / 数量 / 出率. */
@Composable
private fun RarityCard(rarities: List<GachaRarity>) {
    Column(modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)) {
        SbCard(level = GlassLevel.LOW, padding = 14.dp) {
            rarities.forEachIndexed { index, rarity ->
                if (index > 0) Gap(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        text = rarity.label,
                        tint = tierColor(rarity.label),
                        container = tierColor(rarity.label).copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (rarity.count.isNotBlank()) MetaText(rarity.count)
                    }
                    if (rarity.rate.isNotBlank()) {
                        Text(
                            text = rarity.rate,
                            style = MaterialTheme.typography.labelLarge,
                            color = tierColor(rarity.label)
                        )
                    }
                }
            }
        }
    }
}

/**
 * One title. The badge keeps the site's own shape - icon, name, tier - and the
 * card adds whatever the page printed around it.
 */
@Composable
private fun TitleCard(title: GachaTitle) {
    val tokens = LocalTokens.current
    val tint = tierColor(title.tier)
    SbCard(level = GlassLevel.LOW, padding = 13.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitleBadge(title)
            Spacer(Modifier.weight(1f))
            if (title.equipped) {
                Chip(
                    text = "佩戴中",
                    tint = tokens.accentGlow,
                    container = tokens.accentWarm.copy(alpha = 0.12f)
                )
            } else if (title.expired) {
                Chip(
                    text = "已过期",
                    tint = tokens.textTertiary,
                    container = tokens.glassLow
                )
            }
        }
        if (title.tier.isNotBlank() || title.serial.isNotBlank()) {
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title.tier.isNotBlank()) {
                    Text(
                        text = tierLabel(title.tier),
                        style = MaterialTheme.typography.labelMedium,
                        color = tint
                    )
                }
                if (title.serial.isNotBlank()) {
                    if (title.tier.isNotBlank()) Spacer(Modifier.width(8.dp))
                    MetaText("编号 ${title.serial}")
                }
            }
        }
        if (title.description.isNotBlank()) {
            Gap(7)
            Text(
                text = title.description,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary
            )
        }
        if (title.meta.isNotBlank() || title.status.isNotBlank()) {
            Gap(7)
            MetaText(listOf(title.meta, title.status).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
}

/** The badge itself, tinted by tier the way the site tints it. */
@Composable
private fun TitleBadge(title: GachaTitle) {
    val tokens = LocalTokens.current
    val tint = tierColor(title.tier)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (title.icon.isNotBlank()) {
            Text(text = title.icon, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = title.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (title.expired) tokens.textTertiary else tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * What the pull returned, over a scrim. The site answers a pull with a whole
 * page; this is the part of it worth seeing.
 */
@Composable
private fun PullResultOverlay(result: GachaResult, onDismiss: () -> Unit) {
    val tokens = LocalTokens.current
    val scrimTap = remember { MutableInteractionSource() }
    val cardTap = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.base.copy(alpha = 0.62f))
            .clickable(interactionSource = scrimTap, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // The card takes its own taps, so pressing it does not dismiss.
        GlassPanel(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .clickable(interactionSource = cardTap, indication = null) { },
            shape = RoundedCornerShape(24.dp),
            level = GlassLevel.HIGH,
            padding = 18.dp
        ) {
            Text(
                text = if (result.titles.isNotEmpty()) "抽取结果" else "已提交",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.textPrimary
            )
            if (result.message.isNotBlank()) {
                Gap(8)
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary
                )
            }
            if (result.titles.isEmpty() && result.message.isBlank()) {
                Gap(8)
                Text(
                    text = "站点没有回传称号信息，下拉刷新可以看到最新的收藏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary
                )
            }
            result.titles.take(10).forEach { title ->
                Gap(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TitleBadge(title)
                    if (title.meta.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        MetaText(title.meta)
                    }
                }
                if (title.description.isNotBlank()) {
                    Gap(4)
                    Text(
                        text = title.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Gap(16)
            SmallAction("知道了", primary = true, onClick = onDismiss)
        }
    }
}
