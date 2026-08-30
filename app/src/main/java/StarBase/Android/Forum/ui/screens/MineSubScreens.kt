package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.Conversation
import StarBase.Android.Forum.data.DirectMessage
import StarBase.Android.Forum.data.NotifyItem
import StarBase.Android.Forum.data.Profile
import StarBase.Android.Forum.data.ProfileTab
import StarBase.Android.Forum.data.ThemeMode
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.Parse
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
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.ageLabel
import StarBase.Android.Forum.ui.freshnessText
import StarBase.Android.Forum.ui.components.Chip
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.SbCard
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.components.SimpleTopicRow
import StarBase.Android.Forum.ui.components.TopicRow
import StarBase.Android.Forum.ui.components.UserAvatar
import StarBase.Android.Forum.ui.components.tierColor
import StarBase.Android.Forum.ui.components.tierLabel
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassTabs
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

// ---- 通知 --------------------------------------------------------------------

class NotifyViewModel : ViewModel() {
    var state by mutableStateOf<Load<List<NotifyItem>>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private val fresh = Freshness(windowMs = Freshness.BADGE_WINDOW_MS)
    private var inFlight = false

    val ageSeconds: Long get() = fresh.ageSeconds

    fun load(force: Boolean = false) {
        if (state is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            // Only blank the screen on the first load; a refresh keeps the list
            // visible so it does not flash empty.
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            try {
                state = Load.Ready(Api.notifications())
                fresh.mark()
            } catch (e: Throwable) {
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                refreshing = false
                inFlight = false
            }
        }
    }

    /** Opening the screen always re-reads: the badge is why you came here. */
    fun openOrRefresh() {
        if (state !is Load.Ready || fresh.stale) load(force = true)
    }
}

@Composable
fun NotificationsScreen(
    vm: NotifyViewModel,
    signedIn: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenHref: (String) -> Unit
) {
    // Covers both the first appearance and every return to the foreground.
    OnReturnToForeground(signedIn) { if (signedIn) vm.openOrRefresh() }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "通知",
            subtitle = if (signedIn) freshnessText(vm.ageSeconds, vm.refreshing) else "",
            onBack = onBack,
            action = "刷新",
            onAction = { vm.load(force = true) }
        )
        if (!signedIn) {
            SignInPrompt("登录后查看通知", onLogin)
            return@Column
        }
        when (val s = vm.state) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load(force = true) }, onLogin)
            is Load.Ready -> Refreshable(
                refreshing = vm.refreshing,
                onRefresh = { vm.load(force = true) }
            ) {
                if (s.value.isEmpty()) {
                    EmptyPanel("没有新通知")
                } else {
                    LazyColumn {
                        itemsIndexed(s.value, key = { i, n -> "$i-${n.text.take(24)}" }) { index, item ->
                            if (index > 0) Hairline(startInset = 16)
                            NotifyRow(item) { if (item.href.isNotBlank()) onOpenHref(item.href) }
                        }
                        item("tail") { Gap(24) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotifyRow(item: NotifyItem, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (item.unread) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tokens.hotTint)
            )
            Spacer(Modifier.width(9.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (item.timeText.isNotBlank()) {
                Gap(4)
                MetaText(item.timeText)
            }
        }
    }
}

// ---- 私信 --------------------------------------------------------------------

class MessagesViewModel : ViewModel() {
    var state by mutableStateOf<Load<List<Conversation>>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** 开新会话: what was typed, and who it found. */
    var query by mutableStateOf("")
        private set
    var hits by mutableStateOf<List<Parse.UserHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchNote by mutableStateOf("")
        private set

    private var searchInFlight = false

    fun updateQuery(value: String) {
        query = value
        if (value.isBlank()) {
            hits = emptyList()
            searchNote = ""
        }
    }

    /**
     * Finds people to write to.
     *
     * There is no conversation to create: a hit's thread page is the conversation,
     * empty until the first message. So this only has to find the user id.
     */
    fun search() {
        val q = query.trim()
        if (q.isBlank() || searchInFlight) return
        searchInFlight = true
        searching = true
        searchNote = ""
        viewModelScope.launch {
            try {
                val found = Api.findUsers(q)
                hits = found
                searchNote = if (found.isEmpty()) "没找到叫「$q」的用户" else ""
            } catch (e: Throwable) {
                hits = emptyList()
                searchNote = e.message ?: "搜索失败"
            } finally {
                searching = false
                searchInFlight = false
            }
        }
    }

    fun clearSearch() {
        query = ""
        hits = emptyList()
        searchNote = ""
    }

    private val fresh = Freshness(windowMs = Freshness.BADGE_WINDOW_MS)
    private var inFlight = false

    val ageSeconds: Long get() = fresh.ageSeconds

    fun load(force: Boolean = false) {
        if (state is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            try {
                state = Load.Ready(Api.conversations())
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
     * Re-reads on open, and again after you come back from a thread - replies
     * are written on the site's own page, so the list has to be re-read to see
     * that a conversation moved.
     */
    fun openOrRefresh() {
        if (state !is Load.Ready || fresh.stale) load(force = true)
    }
}

@Composable
fun MessagesScreen(
    vm: MessagesViewModel,
    signedIn: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onThread: (Int) -> Unit,
    onOpenSite: (String) -> Unit
) {
    // First appearance, and again on the way back from a thread opened in the
    // site page - replies there move a conversation up the list.
    OnReturnToForeground(signedIn) { if (signedIn) vm.openOrRefresh() }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "私信",
            subtitle = if (signedIn) freshnessText(vm.ageSeconds, vm.refreshing) else "",
            onBack = onBack,
            action = "刷新",
            onAction = { vm.load(force = true) }
        )
        if (!signedIn) {
            SignInPrompt("登录后查看私信", onLogin)
            return@Column
        }
        when (val s = vm.state) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load(force = true) }, onLogin)
            is Load.Ready -> Refreshable(
                refreshing = vm.refreshing,
                onRefresh = { vm.load(force = true) }
            ) {
                if (s.value.isEmpty()) {
                    // No conversations yet is exactly when 开新会话 matters, so the
                    // finder comes first and the empty state sits under it.
                    LazyColumn {
                        item("find") { FindUserBar(vm = vm, onThread = onThread) }
                        item("empty") {
                            EmptyPanel("还没有私信", "搜个用户名就能开始")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                SmallAction(text = "在网页中打开", primary = false) {
                                    onOpenSite(Site.MESSAGES)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn {
                        item("find") {
                            FindUserBar(vm = vm, onThread = onThread)
                        }
                        itemsIndexed(s.value, key = { i, c -> "${c.id}-$i" }) { index, item ->
                            Hairline(startInset = 66)
                            ConversationRow(item) {
                                // A thread is a screen of our own now, with its own
                                // compose box - it no longer hands off to the site.
                                onThread(item.peerId)
                            }
                        }
                        item("tail") { Gap(24) }
                    }
                }
            }
        }
    }
}

/**
 * 开新会话.
 *
 * Search for a username, tap the hit, and the thread opens - empty, with its compose
 * box. That is all a new conversation is on this site, so there is no separate
 * "start" step to build.
 */
@Composable
private fun FindUserBar(vm: MessagesViewModel, onThread: (Int) -> Unit) {
    val tokens = LocalTokens.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SbMetrics.pagePadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .liquidGlass(
                        shape = RoundedCornerShape(SbRadius.field),
                        level = GlassLevel.MEDIUM,
                        refract = false
                    )
                    .padding(horizontal = 13.dp, vertical = 11.dp)
            ) {
                if (vm.query.isEmpty()) {
                    Text(
                        text = "搜用户名，开始新会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textTertiary
                    )
                }
                BasicTextField(
                    value = vm.query,
                    onValueChange = vm::updateQuery,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accentWarm),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        vm.search()
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(9.dp))
            GlassButton(
                text = if (vm.searching) "搜索中" else "搜索",
                onClick = {
                    keyboard?.hide()
                    vm.search()
                },
                enabled = vm.query.isNotBlank() && !vm.searching,
                modifier = Modifier.width(72.dp)
            )
        }

        if (vm.searchNote.isNotBlank()) {
            Text(
                text = vm.searchNote,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textTertiary,
                modifier = Modifier.padding(start = SbMetrics.pagePadding, bottom = 8.dp)
            )
        }

        vm.hits.forEach { hit ->
            Hairline(startInset = 66)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.clearSearch()
                        onThread(hit.userId)
                    }
                    .padding(horizontal = SbMetrics.pagePadding, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(name = hit.name, url = hit.avatar, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                MetaText("开始对话")
            }
        }
        if (vm.hits.isNotEmpty()) Gap(6)
    }
}

// ---- 私信会话 ----------------------------------------------------------------

class ThreadViewModel : ViewModel() {
    var state by mutableStateOf<Load<Parse.Thread>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set

    private var partnerId = 0

    /** A thread collects replies while it is open, so this window is short. */
    private val fresh = Freshness(windowMs = 45_000L)
    private var inFlight = false

    /** Same hazard as 帖子详情: see [TopicViewModel]'s own loadJob. */
    private var loadJob: Job? = null

    val ageSeconds: Long get() = fresh.ageSeconds

    fun open(id: Int) {
        if (id == partnerId && state is Load.Ready) {
            if (fresh.stale) load()
            return
        }
        loadJob?.cancel()
        loadJob = null
        inFlight = false
        partnerId = id
        notice = ""
        fresh.invalidate()
        load(initial = true)
    }

    fun refreshIfStale() {
        if (fresh.stale && state is Load.Ready) load()
    }

    fun load(initial: Boolean = false) {
        if (inFlight || partnerId == 0) return
        inFlight = true
        val requested = partnerId
        loadJob = viewModelScope.launch {
            if (initial) state = Load.Loading else refreshing = true
            try {
                val thread = Api.thread(requested)
                if (requested != partnerId) return@launch
                state = Load.Ready(thread)
                fresh.mark()
            } catch (e: Throwable) {
                if (requested != partnerId) return@launch
                if (state !is Load.Ready) state = failureOf(e) else notice = e.message.orEmpty()
            } finally {
                if (requested == partnerId) {
                    refreshing = false
                    inFlight = false
                }
            }
        }
    }

    /** Sends, then re-reads so the sent message comes from the server. */
    fun send(text: String, onSent: () -> Unit) {
        if (sending || text.isBlank()) return
        sending = true
        viewModelScope.launch {
            try {
                val result = Api.sendMessage(partnerId, text)
                notice = result.message
                onSent()
                fresh.invalidate()
                load()
            } catch (e: Throwable) {
                notice = e.message ?: "私信发送失败"
            } finally {
                sending = false
            }
        }
    }

    fun clearNotice() { notice = "" }
}

@Composable
fun ThreadScreen(
    partnerId: Int,
    vm: ThreadViewModel,
    onBack: () -> Unit,
    onUser: (Int) -> Unit,
    onLogin: () -> Unit
) {
    LaunchedEffect(partnerId) { vm.open(partnerId) }
    OnReturnToForeground(partnerId) { vm.refreshIfStale() }

    val thread = (vm.state as? Load.Ready)?.value

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = thread?.partner.orEmpty().ifBlank { "私信" },
            subtitle = freshnessText(vm.ageSeconds, vm.refreshing),
            onBack = onBack,
            action = "刷新",
            onAction = { vm.load() }
        )

        when (val s = vm.state) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load() }, onLogin)
            is Load.Ready -> Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
                if (vm.notice.isNotBlank()) {
                    NoticeBar(vm.notice) { vm.clearNotice() }
                }
                Refreshable(
                    refreshing = vm.refreshing,
                    onRefresh = { vm.load() },
                    modifier = Modifier.weight(1f)
                ) {
                    if (s.value.messages.isEmpty()) {
                        EmptyPanel("还没有消息", "写下第一句")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            item("head") { Gap(10) }
                            itemsIndexed(
                                s.value.messages,
                                key = { i, m -> "$i-${m.timeText}" }
                            ) { _, message ->
                                MessageBubble(
                                    message = message,
                                    partnerAvatar = s.value.partnerAvatar,
                                    onUser = { onUser(s.value.partnerId) }
                                )
                            }
                            item("tail") { Gap(12) }
                        }
                    }
                }
                ComposeBar(
                    sending = vm.sending,
                    partner = s.value.partner,
                    onSend = { text, done -> vm.send(text) { done() } }
                )
            }
        }
    }
}

/**
 * One message. Ours sits right and carries no avatar; theirs sits left with one -
 * the asymmetry is what tells them apart at a glance, so neither needs a label.
 */
@Composable
private fun MessageBubble(
    message: DirectMessage,
    partnerAvatar: String,
    onUser: () -> Unit
) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding, vertical = 5.dp),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start
    ) {
        if (!message.fromMe) {
            UserAvatar(
                name = message.sender,
                url = partnerAvatar,
                size = 32.dp,
                onClick = onUser
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(SbRadius.field))
                    .background(
                        if (message.fromMe) tokens.accentWarm.copy(alpha = 0.16f)
                        else tokens.glassLow
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary
                )
            }
            if (message.timeText.isNotBlank()) {
                Gap(3)
                MetaText(dmTime(message.timeText))
            }
        }
    }
}

/** `2026-08-13T15:00:11+08:00` is not something to show; the clock time is. */
private fun dmTime(raw: String): String {
    val t = raw.substringAfter('T', "").take(5)
    return if (t.length == 5) "${raw.substringBefore('T')} $t" else raw
}

/** 私信输入框. Mirrors 帖子页的回复条 so the two feel like one gesture. */
@Composable
private fun ComposeBar(
    sending: Boolean,
    partner: String,
    onSend: (String, () -> Unit) -> Unit
) {
    val tokens = LocalTokens.current
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    // The site caps a private message at 500 characters.
    val tooLong = text.length > 500
    val enabled = text.isNotBlank() && !sending && !tooLong

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        Hairline()
        if (tooLong) {
            Text(
                text = "私信最多 500 字，现在 ${text.length} 字",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.hotTint,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .liquidGlass(
                        shape = RoundedCornerShape(SbRadius.field),
                        level = GlassLevel.MEDIUM,
                        refract = false
                    )
                    .padding(horizontal = 13.dp, vertical = 12.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = if (partner.isBlank()) "写下私信…" else "发给 $partner…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textTertiary
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accentWarm),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(9.dp))
            GlassButton(
                text = if (sending) "发送中" else "发送",
                onClick = {
                    keyboard?.hide()
                    onSend(text) { text = "" }
                },
                enabled = enabled,
                modifier = Modifier.width(74.dp)
            )
        }
    }
}

@Composable
private fun ConversationRow(item: Conversation, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(name = item.peer, url = item.avatar, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.peer,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.timeText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary
                    )
                }
            }
            if (item.preview.isNotBlank()) {
                Gap(4)
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (item.unread > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${item.unread}",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.base,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(tokens.hotTint)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ---- 收藏 / 浏览历史 ---------------------------------------------------------

@Composable
fun BookmarksScreen(
    store: UserStore,
    onBack: () -> Unit,
    onTopic: (Int) -> Unit
) {
    var tab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = if (tab == 0) "收藏" else "浏览历史",
            subtitle = "保存在本机，不会上传",
            onBack = onBack,
            action = if (tab == 0) "清空收藏" else "清空历史",
            onAction = { if (tab == 0) store.clearBookmarks() else store.clearHistory() }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SegmentPill("收藏 ${store.bookmarks.size}", tab == 0) { tab = 0 }
            SegmentPill("历史 ${store.history.size}", tab == 1) { tab = 1 }
        }

        val ids = if (tab == 0) store.bookmarks else store.history
        if (ids.isEmpty()) {
            EmptyPanel(
                if (tab == 0) "还没有收藏" else "还没有浏览记录",
                if (tab == 0) "在帖子页点右上角的收藏" else "看过的帖子会出现在这里"
            )
        } else {
            LazyColumn {
                itemsIndexed(ids.toList(), key = { i, id -> "$id-$i" }) { index, id ->
                    if (index > 0) Hairline(startInset = 16)
                    SimpleTopicRow(
                        title = store.titleOf(id).ifBlank { "帖子 #$id" },
                        subtitle = "#$id",
                        onClick = { onTopic(id) }
                    )
                }
                item("tail") { Gap(24) }
            }
        }
    }
}

// ---- 个人设置 ----------------------------------------------------------------

@Composable
fun SettingsScreen(
    store: UserStore,
    signedIn: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSite: (String) -> Unit
) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(title = "个人设置", onBack = onBack)
        LazyColumn {
            item("theme") {
                Gap(12)
                SbCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    padding = 14.dp
                ) {
                    Text(
                        text = "主题外观",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary
                    )
                    Gap(10)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            SegmentPill(
                                label = mode.label,
                                selected = store.themeMode == mode,
                                onClick = { store.updateTheme(mode) }
                            )
                        }
                    }
                }
            }

            item("local") {
                Gap(14)
                SbCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    padding = 14.dp
                ) {
                    Text(
                        text = "本机数据",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary
                    )
                    Gap(6)
                    Text(
                        text = "收藏 ${store.bookmarks.size} 条 · 浏览历史 ${store.history.size} 条。" +
                            "这两项只存在这台手机上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary
                    )
                    Gap(14)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallAction("清空收藏", primary = false, onClick = store::clearBookmarks)
                        SmallAction("清空历史", primary = false, onClick = store::clearHistory)
                    }
                }
            }

            item("account") {
                Gap(14)
                SbCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    padding = 14.dp
                ) {
                    Text(
                        text = "账号",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary
                    )
                    Gap(6)
                    Text(
                        text = if (signedIn) {
                            "资料、密码、邮箱等设置由网站页面处理，App 不保存这些内容。"
                        } else {
                            "登录后可以在这里进入网站的账号设置。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary
                    )
                    Gap(14)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (signedIn) {
                            SmallAction("网站设置", primary = true) {
                                onOpenSite("${Site.BASE}/settings")
                            }
                            SmallAction("退出登录", primary = false, onClick = onSignOut)
                        } else {
                            SmallAction("登录", primary = true, onClick = onLogin)
                        }
                    }
                }
                Gap(28)
            }
        }
    }
}

// ---- 个人主页 / 我的主题 -----------------------------------------------------

class ProfileViewModel : ViewModel() {
    var state by mutableStateOf<Load<Profile>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    /** Which of 主题 / 回帖 / 收藏 is on screen. */
    var tab by mutableStateOf(ProfileTab.TOPICS)
        private set

    private var userId = 0
    private val fresh = Freshness()
    private var inFlight = false

    /** Same hazard as 帖子详情: see [TopicViewModel]'s own loadJob. */
    private var loadJob: Job? = null

    val ageSeconds: Long get() = fresh.ageSeconds

    /**
     * Opens a profile. Coming back to one already on screen only re-reads once it
     * has aged - this page doubles as 我的主题 and 我的积分, so a stale points
     * count is worth the one request.
     */
    fun open(id: Int, want: ProfileTab = tab, force: Boolean = false) {
        val same = id == userId && want == tab
        val loaded = state is Load.Ready
        if (same && loaded && !force && !fresh.stale) return

        // Moving to another profile or tab cancels the one being left. Without
        // this the `inFlight` guard below swallowed the new request and the old
        // profile's answer published itself as the new one's.
        if (!same) {
            loadJob?.cancel()
            loadJob = null
            inFlight = false
        }
        if (inFlight) return
        inFlight = true
        if (!same) fresh.invalidate()
        userId = id
        tab = want
        loadJob = viewModelScope.launch {
            // Switching user or tab blanks the screen; re-reading does not.
            if (!same || !loaded) state = Load.Loading else refreshing = true
            try {
                val profile = Api.profile(id, want)
                if (id != userId || want != tab) return@launch
                state = Load.Ready(profile)
                fresh.mark()
            } catch (e: Throwable) {
                if (id != userId || want != tab) return@launch
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                if (id == userId && want == tab) {
                    refreshing = false
                    inFlight = false
                }
            }
        }
    }

    /** Switching tab always refetches - each one is its own page on the site. */
    fun switchTab(want: ProfileTab) {
        if (want == tab && state is Load.Ready) return
        open(userId, want, force = true)
    }

    fun refresh() = open(userId, tab, force = true)

    fun refreshIfStale() {
        if (fresh.stale && state is Load.Ready) open(userId, tab, force = true)
    }
}

@Composable
fun ProfileScreen(
    userId: Int,
    vm: ProfileViewModel,
    titleOverride: String = "",
    startTab: ProfileTab = ProfileTab.TOPICS,
    onBack: () -> Unit,
    onTopic: (Int) -> Unit,
    onLogin: () -> Unit,
    onOpenSite: (String) -> Unit
) {
    LaunchedEffect(userId, startTab) { vm.open(userId, startTab) }
    OnReturnToForeground(userId) { vm.refreshIfStale() }

    Column(modifier = Modifier.fillMaxWidth()) {
        val loaded = (vm.state as? Load.Ready)?.value
        DetailBar(
            title = titleOverride.ifBlank { loaded?.name.orEmpty().ifBlank { "个人主页" } },
            subtitle = if (vm.refreshing) {
                "正在获取最新资料"
            } else {
                listOf(loaded?.group.orEmpty(), ageLabel(vm.ageSeconds))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            },
            onBack = onBack,
            action = "刷新",
            onAction = vm::refresh
        )
        when (val s = vm.state) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(s.message, s.kind, vm::refresh, onLogin)
            is Load.Ready -> LazyColumn {
                item("head") {
                    Gap(12)
                    ProfileCard(s.value) { onOpenSite(Site.user(userId)) }
                }
                // The site's own three tabs. 我的回帖 opens straight on 回帖, and
                // from there the other two are one tap away.
                item("tabs") {
                    Gap(16)
                    GlassTabs(
                        labels = ProfileTab.entries.map { it.label },
                        selected = ProfileTab.entries.indexOf(vm.tab),
                        onSelect = { vm.switchTab(ProfileTab.entries[it]) },
                        modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)
                    )
                }
                item("list-header") {
                    Gap(14)
                    SectionHeader(
                        title = when (vm.tab) {
                            ProfileTab.TOPICS -> "发布的主题"
                            ProfileTab.REPLIES -> "参与的回帖"
                            ProfileTab.FAVORITES -> "收藏的主题"
                        },
                        subtitle = if (s.value.topics.isEmpty()) {
                            "这里还没有内容"
                        } else {
                            "${s.value.topics.size} 条"
                        }
                    )
                    Gap(4)
                }
                if (s.value.topics.isEmpty()) {
                    item("empty") {
                        EmptyPanel(
                            when (vm.tab) {
                                ProfileTab.TOPICS -> "还没有发布过主题"
                                ProfileTab.REPLIES -> "还没有回过帖"
                                ProfileTab.FAVORITES -> "还没有收藏"
                            },
                            "下拉刷新或稍后再试"
                        )
                    }
                } else {
                    itemsIndexed(s.value.topics, key = { index, t -> "${t.id}-$index" }) { index, topic ->
                        if (index > 0) Hairline(startInset = 66)
                        TopicRow(
                            topic = topic,
                            onClick = { onTopic(topic.id) },
                            showForum = true
                        )
                    }
                }
                item("tail") { Gap(24) }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Profile, onOpenSite: () -> Unit) {
    val tokens = LocalTokens.current
    SbCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        padding = 18.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(name = profile.name, url = profile.avatar, size = 54.dp, ring = true)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name.ifBlank { "用户 #${profile.id}" },
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Gap(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.group.isNotBlank()) {
                        Chip(
                            text = profile.group,
                            tint = tokens.accentGlow,
                            container = tokens.accentWarm.copy(alpha = 0.10f)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    MetaText("UID ${profile.id}")
                }
            }
        }

        profile.title?.let { title ->
            Gap(12)
            val color = tierColor(title.tier)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.11f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title.name, style = MaterialTheme.typography.labelLarge, color = color)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tierLabel(title.tier),
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.85f)
                )
            }
        }

        if (profile.stats.isNotEmpty()) {
            Gap(14)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                profile.stats.take(4).forEach { (label, value) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = value.ifBlank { "—" },
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.textPrimary
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.textSecondary
                        )
                    }
                }
            }
        }

        if (profile.joinedText.isNotBlank()) {
            Gap(10)
            MetaText(profile.joinedText)
        }

        Gap(14)
        SmallAction("在网页中查看", primary = false, onClick = onOpenSite)
    }
}

// ---- shared ------------------------------------------------------------------

@Composable
fun SignInPrompt(text: String, onLogin: () -> Unit) {
    val tokens = LocalTokens.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary
        )
        Gap(16)
        SmallAction("登录", primary = true, onClick = onLogin)
    }
}
