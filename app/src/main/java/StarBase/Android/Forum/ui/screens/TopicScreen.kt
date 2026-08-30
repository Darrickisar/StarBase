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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.data.TopicDetail
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
import StarBase.Android.Forum.ui.components.MetaDot
import StarBase.Android.Forum.ui.components.MetaRow
import StarBase.Android.Forum.ui.components.ActionGlyph
import StarBase.Android.Forum.ui.components.ActionIcon
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.PostBody
import StarBase.Android.Forum.ui.components.UserAvatar
import StarBase.Android.Forum.ui.components.tierColor
import StarBase.Android.Forum.ui.components.tierLabel
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.glass.pressFeedback
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * §05 帖子详情 + §06 评论区.
 *
 * The old page wrapped every part of a thread in its own card, so a post read as
 * a stack of boxes. V5 turns it into one reading surface: the top bar is a thin
 * row of light actions, 作者/标题/标签/正文 flow into each other with no shell, and
 * comments are separated by a 1px hairline instead of being cards of their own.
 */
class TopicViewModel : ViewModel() {
    var state by mutableStateOf<Load<TopicDetail>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var posting by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set

    /** The comment the reply box is quoting, or null for a plain reply. */
    var quoting by mutableStateOf<Post?>(null)
        private set

    /**
     * Set when a reply can only be made in a browser - a Turnstile widget on the
     * form, or markup we no longer recognise. The screen opens the site page.
     */
    var browserReply by mutableStateOf("")
        private set

    /** The post whose 点赞 amount is being chosen, or null when none is. */
    var likeTarget by mutableStateOf<Post?>(null)
        private set
    var liking by mutableStateOf(false)
        private set

    /** 主楼's 打赏 presets, fetched when the picker is armed for the opening post. */
    var topicPresets by mutableStateOf<List<Int>>(emptyList())
        private set

    /** The modal's own 已打赏 … 我的积分 … line. Worth showing before spending. */
    var topicDonateInfo by mutableStateOf("")
        private set

    val comments = mutableStateListOf<Post>()
    private var topicId = 0
    private var page = 1
    private var lastPage = 1

    /** Shorter window than a feed: replies land in an open thread quickly. */
    private val fresh = Freshness(windowMs = 45_000L)

    /** One request at a time; a pull and the resume hook can coincide. */
    private var inFlight = false

    val hasMore: Boolean get() = page < lastPage
    val ageSeconds: Long get() = fresh.ageSeconds

    /**
     * Opens a topic. Coming back to the one you were just reading keeps it on
     * screen and re-fetches behind it once it has aged - a busy thread collects
     * replies while you are away from it.
     */
    fun open(id: Int) {
        if (id == topicId && state is Load.Ready) {
            if (fresh.stale) load(initial = false)
            return
        }
        topicId = id
        fresh.invalidate()
        load(initial = true)
    }

    fun refresh() = load(initial = false)

    fun refreshIfStale() {
        if (fresh.stale && state is Load.Ready) load(initial = false)
    }

    private fun load(initial: Boolean) {
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            if (initial) state = Load.Loading else refreshing = true
            page = 1
            try {
                val detail = Api.topic(topicId, 1)
                comments.clear()
                comments += detail.comments
                lastPage = detail.lastPage
                state = Load.Ready(detail)
                fresh.mark()
            } catch (e: Throwable) {
                if (state !is Load.Ready) state = failureOf(e)
                else notice = (e.message ?: "刷新失败")
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
                val next = Api.topic(topicId, page + 1)
                val known = comments.mapTo(HashSet()) { it.id }
                comments += next.comments.filter { it.id !in known }
                page += 1
                lastPage = maxOf(lastPage, next.lastPage)
            } catch (e: Throwable) {
                notice = e.message ?: "加载更多失败"
            } finally {
                loadingMore = false
            }
        }
    }

    /**
     * Posts a comment, then re-reads the thread so the new floor comes from the
     * server rather than being faked locally.
     *
     * It reloads the *last* page, not the first: on a long thread the comment
     * you just wrote is at the end, and reloading page 1 would look like the
     * reply vanished.
     */
    fun reply(body: String, onSuccess: () -> Unit) {
        if (posting || body.isBlank()) return
        posting = true
        val target = quoting
        viewModelScope.launch {
            try {
                val result = Api.reply(topicId, body.trim(), target)
                notice = result.message.ifBlank { "已发表" }
                quoting = null
                onSuccess()
                loadPage(lastPage)
            } catch (e: Api.NeedsBrowser) {
                // Not a failure: the site wants a browser for this one, so hand
                // the draft off instead of losing it.
                browserReply = e.url
                notice = e.message.orEmpty()
            } catch (e: Throwable) {
                notice = e.message ?: "回复失败"
            } finally {
                posting = false
            }
        }
    }

    /** Aims the reply box at a floor, or clears the aim when [post] is null. */
    fun quote(post: Post?) {
        quoting = post
    }

    /**
     * Arms the 点赞 picker for one comment. Liking is free but 投币 spends points,
     * so the amount is always an explicit choice - the site asks too.
     */
    fun askLike(post: Post) {
        likeTarget = post
        // 主楼's amounts live in the site's donate modal, not on the page, so they
        // have to be fetched before the picker can offer them. 评论 carry theirs in
        // data-tiers and need no round trip.
        topicPresets = emptyList()
        topicDonateInfo = ""
        if (post.isOpening) {
            viewModelScope.launch {
                val panel = runCatching { Api.donateOptions(topicId) }.getOrNull()
                // Only apply it if the picker is still aimed where it was aimed.
                if (likeTarget?.isOpening == true) {
                    topicPresets = panel?.presets.orEmpty()
                    topicDonateInfo = panel?.info.orEmpty()
                }
            }
        }
    }

    fun cancelLike() {
        likeTarget = null
        topicPresets = emptyList()
        topicDonateInfo = ""
    }

    /**
     * Sends the like. [points] is 0 for a plain 点赞, otherwise one of the amounts
     * the post offers.
     *
     * 主楼 and 评论 are two unrelated endpoints on this site - the opening post has
     * no form on the page at all, only a donate modal - so which one this is decides
     * the request.
     *
     * Re-reads the page afterwards rather than adjusting the count locally: the
     * site is the only thing that knows what the new count and state are, and
     * guessing would leave a wrong number on screen until the next refresh.
     */
    fun like(points: Int) {
        val target = likeTarget ?: return
        if (liking) return
        likeTarget = null
        topicPresets = emptyList()
        topicDonateInfo = ""
        liking = true
        viewModelScope.launch {
            try {
                val result = if (target.isOpening) {
                    Api.likeTopic(topicId, points)
                } else {
                    // The comment's form is on the page it is displayed on.
                    Api.like(topicId, target.replyId, points, page)
                }
                notice = result.message.ifBlank {
                    if (points > 0) "已打赏 $points 积分" else "已点赞"
                }
                fresh.invalidate()
                load(initial = false)
            } catch (e: Throwable) {
                notice = e.message ?: "点赞失败"
            } finally {
                liking = false
            }
        }
    }

    fun browserReplyHandled() {
        browserReply = ""
    }

    /**
     * Reloads one specific page. If the post spilled onto a page that did not
     * exist when we last looked, this follows it there - at most one extra
     * request, and only right after you posted.
     */
    private suspend fun loadPage(target: Int) {
        refreshing = true
        inFlight = true
        try {
            var detail = Api.topic(topicId, target)
            if (detail.lastPage > target) {
                detail = Api.topic(topicId, detail.lastPage)
            }
            comments.clear()
            comments += detail.comments
            page = detail.page
            lastPage = detail.lastPage
            state = Load.Ready(detail)
            fresh.mark()
        } catch (e: Throwable) {
            notice = e.message ?: "刷新失败"
        } finally {
            refreshing = false
            inFlight = false
        }
    }

    fun clearNotice() { notice = "" }
}

@Composable
fun TopicScreen(
    topicId: Int,
    vm: TopicViewModel,
    signedIn: Boolean,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onTopic: (Int) -> Unit,
    onUser: (Int) -> Unit,
    onForum: (Int) -> Unit,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    LaunchedEffect(topicId) { vm.open(topicId) }
    OnReturnToForeground(topicId) { vm.refreshIfStale() }

    // A reply the site will only take from a browser: hand it the page. The
    // notice already says why, so this opens without a second prompt.
    LaunchedEffect(vm.browserReply) {
        val url = vm.browserReply
        if (url.isNotBlank()) {
            onOpenLink(url)
            vm.browserReplyHandled()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val detail = (vm.state as? Load.Ready)?.value
        // §5.1: the bar carries the board, not the post title - the title itself
        // belongs to the reading flow below, at reading size. And it carries
        // nothing else: 返回 / 板块名 / 收藏 / 刷新 is the whole list, so the
        // comment count lives in the 全部评论 header and the refresh state lives
        // in the action's own label.
        DetailBar(
            title = detail?.forumName.orEmpty().ifBlank { "帖子" },
            onBack = onBack,
            action = if (vm.refreshing) "更新中" else "刷新",
            onAction = vm::refresh,
            secondAction = if (bookmarked) "已收藏" else "收藏",
            onSecondAction = onToggleBookmark
        )

        when (val s = vm.state) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(s.message, s.kind, vm::refresh, onLogin)
            is Load.Ready -> Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
                if (vm.notice.isNotBlank()) {
                    NoticeBar(vm.notice) { vm.clearNotice() }
                }
                Refreshable(
                    refreshing = vm.refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.weight(1f)
                ) {
                    TopicBody(
                        vm = vm,
                        detail = s.value,
                        canReply = s.value.canReply || signedIn,
                        onUser = onUser,
                        onForum = onForum,
                        onTopic = onTopic,
                        onLogin = onLogin,
                        onRegister = onRegister,
                        onOpenLink = onOpenLink
                    )
                }
                // A guest gets the §6.1 bar inside the flow instead of a composer
                // they cannot use.
                // Choosing a 点赞 amount replaces the composer while it is up: the
                // two are alternatives, and stacking both would push the thread
                // off screen.
                val likeTarget = vm.likeTarget
                if (likeTarget != null) {
                    LikePicker(
                        post = likeTarget,
                        // 主楼's amounts come from the donate modal and arrive a
                        // moment later; a comment's are already on the page.
                        amounts = if (likeTarget.isOpening) vm.topicPresets else likeTarget.tiers,
                        // Only 主楼's modal reports the point balance.
                        info = if (likeTarget.isOpening) vm.topicDonateInfo else "",
                        onPick = vm::like,
                        onCancel = vm::cancelLike
                    )
                } else if (s.value.canReply || signedIn) {
                    ReplyBar(
                        posting = vm.posting,
                        quoting = vm.quoting,
                        onCancelQuote = { vm.quote(null) },
                        onSend = { text, done -> vm.reply(text) { done() } }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicBody(
    vm: TopicViewModel,
    detail: TopicDetail,
    canReply: Boolean,
    onUser: (Int) -> Unit,
    onForum: (Int) -> Unit,
    onTopic: (Int) -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalTokens.current
    val listState = rememberLazyListState()
    val pad = SbMetrics.pagePadding

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        // §5.2 作者 -> 标题 -> 标签 -> 正文, one continuous flow, no card shell.
        item("head") {
            Gap(14)
            Column(modifier = Modifier.padding(horizontal = pad)) {
                detail.opening?.let { opening ->
                    AuthorLine(post = opening, onUser = onUser)
                    Gap(14)
                }
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = tokens.textPrimary
                )
                val badge = detail.opening?.title
                if (detail.forumName.isNotBlank() || badge != null) {
                    Gap(10)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (detail.forumName.isNotBlank()) {
                            GlassChip(
                                text = detail.forumName,
                                onClick = { onForum(detail.forumId) }
                            )
                        }
                        badge?.let { TitleBadge(it.name, it.serial, it.tier) }
                    }
                }
            }
        }

        detail.opening?.let { opening ->
            item("body") {
                Gap(16)
                Column(modifier = Modifier.padding(horizontal = pad)) {
                    if (opening.blocks.isNotEmpty()) {
                        PostBody(
                            blocks = opening.blocks,
                            onLinkClick = onOpenLink,
                            onImageClick = onOpenLink
                        )
                    }
                    // 主楼's 点赞打赏 goes through the site's donate modal, not the
                    // per-comment form, so it gets a real action here rather than
                    // the read-only count chip this used to be.
                    // The heart stays hollow here whatever we have done: unlike a
                    // comment, the site publishes no per-reader state for 主楼 - the
                    // page and the donate modal both carry only totals - so a filled
                    // heart would be an invention.
                    Gap(14)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canReply) {
                            IconAction(
                                glyph = ActionGlyph.HEART,
                                onClick = { vm.askLike(opening) },
                                description = "点赞打赏"
                            )
                        } else {
                            ActionIcon(
                                glyph = ActionGlyph.HEART,
                                tint = tokens.textTertiary,
                                filled = false
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (opening.likes > 0) {
                            MetaText("${opening.likes} 人点赞打赏")
                        }
                    }
                }
                Gap(20)
            }
        }

        item("comments-header") {
            Hairline()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "全部评论",
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${detail.commentCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textTertiary
                )
            }
        }

        when {
            detail.commentsNeedLogin && vm.comments.isEmpty() -> item("gate") {
                LoginGate(count = detail.commentCount, onLogin = onLogin)
            }
            vm.comments.isEmpty() -> item("no-comments") {
                EmptyPanel("还没有人评论", "来做第一个吧")
            }
            else -> {
                itemsIndexed(vm.comments, key = { i, p -> "${p.id}-$i" }) { index, post ->
                    // §6.5 分隔只用一条 1px 低透明度线, 不做独立卡片.
                    if (index > 0) Hairline(startInset = pad.value.toInt() + 45)
                    CommentView(
                        post = post,
                        onUser = onUser,
                        onOpenLink = onOpenLink,
                        onQuote = if (canReply) ({ vm.quote(post) }) else null,
                        onLike = if (canReply) ({ vm.askLike(post) }) else null
                    )
                }
                item("footer") {
                    ListFooter(vm.loadingMore, vm.hasMore, vm::loadMore)
                }
            }
        }

        // §6.1 未登录评论入口: after the list, one compact glass bar.
        if (!canReply) {
            item("guest-bar") {
                Gap(14)
                GuestCommentBar(onLogin = onLogin, onRegister = onRegister)
            }
        }

        if (detail.related.isNotEmpty()) {
            item("related") {
                Gap(22)
                Hairline()
                Text(
                    text = "相关帖子",
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    modifier = Modifier.padding(horizontal = pad, vertical = 12.dp)
                )
                detail.related.take(5).forEach { rel ->
                    Text(
                        text = rel.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTopic(rel.id) }
                            .padding(horizontal = pad, vertical = 9.dp)
                    )
                }
            }
        }
        item("tail") { Gap(28) }
    }
}

/** §5.2 作者行: identity only, at the head of the reading flow. */
@Composable
private fun AuthorLine(post: Post, onUser: (Int) -> Unit) {
    val tokens = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(
            name = post.author,
            url = post.avatar,
            size = 38.dp,
            onClick = if (post.authorId > 0) ({ onUser(post.authorId) }) else null
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.author.ifBlank { "匿名" },
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (post.group.isNotBlank()) {
                    Spacer(Modifier.width(7.dp))
                    GlassChip(text = post.group, tint = tokens.textSecondary)
                }
            }
            Gap(3)
            MetaRow {
                if (post.timeText.isNotBlank()) MetaText(post.timeText)
                if (post.uid.isNotBlank()) {
                    MetaDot()
                    MetaText(post.uid)
                }
            }
        }
    }
}

/**
 * §06 一条评论.
 *
 * A: 头像 36dp + 用户名 + 楼层/时间. B: 回复数是右侧一个小数字块, 不是大按钮.
 * C: 正文直接接在作者信息下面, 左边缘 45dp. D: 热门标记只出现在已经热的评论上,
 * 颜色减弱. The row has no background of its own - the divider does the work.
 */
@Composable
private fun CommentView(
    post: Post,
    onUser: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
    onQuote: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    val hot = post.isHot
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding, vertical = 14.dp)
    ) {
        UserAvatar(
            name = post.author,
            url = post.avatar,
            size = 36.dp,
            onClick = if (post.authorId > 0) ({ onUser(post.authorId) }) else null
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.author.ifBlank { "匿名" },
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                post.title?.let { title ->
                    Spacer(Modifier.width(6.dp))
                    GlassChip(text = title.name, tint = tierColor(title.tier))
                }
                if (hot) {
                    Spacer(Modifier.width(6.dp))
                    // §6.4 热标签: 与其他标签同一形制, 颜色弱化到不抢用户名。
                    GlassChip(text = "热", tint = tokens.hotTint.copy(alpha = 0.72f))
                }
            }
            Gap(2)
            MetaRow {
                if (post.isOpening) {
                    MetaText("楼主", emphasis = true)
                } else if (post.floor > 0) {
                    MetaText("${post.floor} 楼")
                }
                if (post.parentFloor > 0) {
                    MetaDot()
                    MetaText("回复 #${post.parentFloor}")
                }
                if (post.timeText.isNotBlank()) {
                    if (post.isOpening || post.floor > 0) MetaDot()
                    MetaText(post.timeText)
                }
                if (post.likes > 0) {
                    MetaDot()
                    MetaText("${post.likes} 赞")
                }
                // 引用 and 点赞 sit in the meta line rather than being buttons of
                // their own - §06 keeps this row free of anything button-shaped.
                if (onQuote != null && post.floor > 0) {
                    MetaDot()
                    IconAction(
                        glyph = ActionGlyph.QUOTE,
                        onClick = onQuote,
                        description = "引用 #${post.floor}"
                    )
                }
                if (onLike != null && post.replyId > 0) {
                    MetaDot()
                    IconAction(
                        // A coined reaction cannot be taken back, so it shows as a
                        // coin rather than a heart that looks like it would toggle.
                        glyph = if (post.coined) ActionGlyph.COIN else ActionGlyph.HEART,
                        onClick = onLike,
                        description = when {
                            post.coined -> "已投币"
                            post.liked -> "已点赞"
                            else -> "点赞"
                        },
                        filled = post.liked || post.coined,
                        enabled = !post.coined
                    )
                }
            }
            if (post.blocks.isNotEmpty()) {
                Gap(9)
                PostBody(
                    blocks = post.blocks,
                    onLinkClick = onOpenLink,
                    onImageClick = onOpenLink
                )
            }
        }
        if (post.replyCount > 0) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${post.replyCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hot) tokens.hotTint.copy(alpha = 0.85f) else tokens.textSecondary
                )
                Text(
                    text = "回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary
                )
            }
        }
    }
}

/**
 * A tappable glyph in a comment's meta line.
 *
 * The tap target is padded out to 32dp while the glyph stays at 15dp, so the row
 * keeps its metadata weight without the icons being hard to hit. [description] is
 * for screen readers only - the shapes carry the meaning visually.
 */
@Composable
private fun IconAction(
    glyph: ActionGlyph,
    onClick: () -> Unit,
    description: String,
    filled: Boolean = false,
    enabled: Boolean = true
) {
    val tokens = LocalTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (enabled) {
                    Modifier.clickable(onClickLabel = description, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        ActionIcon(
            glyph = glyph,
            tint = when {
                !enabled -> tokens.accentWarm.copy(alpha = 0.55f)
                filled -> tokens.accentWarm
                else -> tokens.textSecondary
            },
            filled = filled,
            modifier = Modifier.semantics { contentDescription = description }
        )
    }
}

/** 称号: a low-saturation warm label, per §05 的标签规范. */
@Composable
private fun TitleBadge(name: String, serial: String, tier: String) {
    val tokens = LocalTokens.current
    val color = tierColor(tier)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(SbRadius.small))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = tierLabel(tier),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f)
        )
        if (serial.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = serial,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }
    }
}

/** Shown when the site hides the whole comment list behind a session. */
@Composable
private fun LoginGate(count: Int, onLogin: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding, vertical = 8.dp),
        level = GlassLevel.LOW,
        padding = 18.dp
    ) {
        Text(
            text = if (count > 0) "共有 $count 条评论，登录后可见" else "登录后可见评论",
            style = MaterialTheme.typography.titleSmall,
            color = tokens.textPrimary
        )
        Gap(5)
        Text(
            text = "这是网站自己的规则，App 只是照着显示。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTertiary
        )
        Gap(14)
        GlassButton(text = "登录", onClick = onLogin, compact = true, modifier = Modifier.width(96.dp))
    }
}

/**
 * §6.1 未登录评论入口: a compact glass bar closing the comment list. Status text on
 * the left, 注册 / 登录 on the right - 登录 is the primary of the pair.
 */
@Composable
private fun GuestCommentBar(onLogin: () -> Unit, onRegister: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.LOW,
        padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "登录后参与讨论",
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary
                )
                Gap(2)
                Text(
                    text = "回帖、点赞和私信都需要账号",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            GlassButton(
                text = "注册",
                onClick = onRegister,
                primary = false,
                compact = true,
                modifier = Modifier.width(62.dp)
            )
            Spacer(Modifier.width(7.dp))
            GlassButton(
                text = "登录",
                onClick = onLogin,
                compact = true,
                modifier = Modifier.width(62.dp)
            )
        }
    }
}

/**
 * 点赞 / 投币 amount picker.
 *
 * A plain 点赞 is free and 投币 spends points, so the two are never one tap: this
 * mirrors what the site's own dialog offers - 直接点赞 plus the tiers the comment
 * carries - and puts the free choice first.
 */
@Composable
private fun LikePicker(
    post: Post,
    amounts: List<Int>,
    info: String,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Hairline()
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (post.floor > 0) "给 #${post.floor} ${post.author}" else "给 ${post.author}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.accentWarm,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (info.isNotBlank()) {
                Gap(5)
                Text(
                    text = info,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary,
                    maxLines = 2
                )
            }
            Gap(9)
            GlassButton(
                // 主楼 has no un-like: the site offers only 直接点赞, and publishes no
                // state to un-like from. A comment does toggle.
                text = if (!post.isOpening && post.liked) "取消点赞" else "直接点赞",
                onClick = { onPick(0) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                compact = true
            )
            if (amounts.isNotEmpty()) {
                Gap(9)
                Text(
                    text = "或者打赏积分，打赏过的不能取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary
                )
                Gap(7)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    amounts.forEach { amount ->
                        Box(modifier = Modifier.weight(1f)) {
                            GlassButton(
                                text = "$amount",
                                onClick = { onPick(amount) },
                                modifier = Modifier.fillMaxWidth(),
                                primary = false,
                                compact = true
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Transient status line for reply results and refresh failures. Tap to dismiss. */
@Composable
internal fun NoticeBar(text: String, onDismiss: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.accentWarm.copy(alpha = 0.12f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = SbMetrics.pagePadding, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "关闭",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
    }
}

/**
 * The composer pinned to the bottom of a topic. One glass row: a field that grows
 * to five lines and a 发送 button. Only reachable when the site says we may reply,
 * so it carries no login branch of its own - that is §6.1's bar inside the list.
 */
@Composable
private fun ReplyBar(
    posting: Boolean,
    quoting: Post?,
    onCancelQuote: () -> Unit,
    onSend: (String, () -> Unit) -> Unit
) {
    val tokens = LocalTokens.current
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val enabled = text.isNotBlank() && !posting

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        Hairline()
        // Who we are answering, when it is not the topic itself. The quote line
        // goes into the body at send time, so it is not shown in the field.
        if (quoting != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "回复 #${quoting.floor} ${quoting.author}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.accentWarm,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onCancelQuote)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
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
                        text = if (quoting == null) "写下你的评论…" else "回复 ${quoting.author}…",
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
                text = if (posting) "发送中" else "发送",
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
