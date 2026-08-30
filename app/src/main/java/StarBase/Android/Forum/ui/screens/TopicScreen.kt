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
        viewModelScope.launch {
            try {
                val result = Api.reply(topicId, body.trim())
                notice = result.message.ifBlank { if (result.ok) "已发表" else "已提交" }
                onSuccess()
                loadPage(lastPage)
            } catch (e: Throwable) {
                notice = e.message ?: "回复失败"
            } finally {
                posting = false
            }
        }
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
                if (s.value.canReply || signedIn) {
                    ReplyBar(
                        posting = vm.posting,
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
                    if (opening.likes > 0) {
                        Gap(14)
                        GlassChip(text = "赞 ${opening.likes}", tint = tokens.hotTint)
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
                    CommentView(post = post, onUser = onUser, onOpenLink = onOpenLink)
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
private fun CommentView(post: Post, onUser: (Int) -> Unit, onOpenLink: (String) -> Unit) {
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

/** Transient status line for reply results and refresh failures. Tap to dismiss. */
@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
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
private fun ReplyBar(posting: Boolean, onSend: (String, () -> Unit) -> Unit) {
    val tokens = LocalTokens.current
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val enabled = text.isNotBlank() && !posting

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        Hairline()
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
                        text = "写下你的评论…",
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
