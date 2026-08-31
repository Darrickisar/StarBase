package StarBase.Android.Forum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.ui.components.NavGlyph
import StarBase.Android.Forum.ui.components.NavIcon
import StarBase.Android.Forum.ui.glass.AmbientRoom
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassSource
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.glass.pageBackdrop
import StarBase.Android.Forum.ui.glass.pressFeedback
import StarBase.Android.Forum.ui.screens.AuthScreen
import StarBase.Android.Forum.data.ProfileTab
import StarBase.Android.Forum.ui.screens.DiscoverEntry
import StarBase.Android.Forum.ui.screens.ExploreScreen
import StarBase.Android.Forum.ui.screens.ExploreViewModel
import StarBase.Android.Forum.ui.screens.ForumListScreen
import StarBase.Android.Forum.ui.screens.ForumListViewModel
import StarBase.Android.Forum.ui.screens.ForumScreen
import StarBase.Android.Forum.ui.screens.ForumViewModel
import StarBase.Android.Forum.ui.screens.GachaScreen
import StarBase.Android.Forum.ui.screens.HistoryScreen
import StarBase.Android.Forum.ui.screens.GachaViewModel
import StarBase.Android.Forum.ui.screens.HomeFeed
import StarBase.Android.Forum.ui.screens.HomeScreen
import StarBase.Android.Forum.ui.screens.HomeViewModel
import StarBase.Android.Forum.ui.screens.MessagesScreen
import StarBase.Android.Forum.ui.screens.MessagesViewModel
import StarBase.Android.Forum.ui.screens.MineEntry
import StarBase.Android.Forum.ui.screens.MineScreen
import StarBase.Android.Forum.ui.screens.NewTopicScreen
import StarBase.Android.Forum.ui.screens.NewTopicViewModel
import StarBase.Android.Forum.ui.screens.NotificationsScreen
import StarBase.Android.Forum.ui.screens.NotifyViewModel
import StarBase.Android.Forum.ui.screens.ThreadScreen
import StarBase.Android.Forum.ui.screens.ThreadViewModel
import StarBase.Android.Forum.ui.screens.ProfileScreen
import StarBase.Android.Forum.ui.screens.ProfileViewModel
import StarBase.Android.Forum.ui.screens.RankScreen
import StarBase.Android.Forum.ui.screens.RankViewModel
import StarBase.Android.Forum.ui.screens.SettingsScreen
import StarBase.Android.Forum.ui.screens.SettingsViewModel
import StarBase.Android.Forum.ui.screens.UpdateViewModel
import StarBase.Android.Forum.ui.screens.AppSettingsScreen
import StarBase.Android.Forum.ui.screens.hasNewerRelease
import StarBase.Android.Forum.ui.screens.TopicScreen
import StarBase.Android.Forum.ui.screens.TopicViewModel
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/** The four bottom tabs. 榜单 is no longer one of them - it lives under 发现. */
enum class Tab(val label: String, val glyph: NavGlyph) {
    HOME("首页", NavGlyph.HOME),
    FORUMS("板块", NavGlyph.BOARDS),
    DISCOVER("发现", NavGlyph.COMPASS),
    MINE("我的", NavGlyph.PERSON)
}

/** Pushed screens. Tabs stay put; these stack on top. */
sealed interface Route {
    data class Topic(val id: Int) : Route
    data class Forum(val id: Int) : Route
    /**
     * A profile. [tab] is which of 主题 / 回帖 / 收藏 it opens on, so 我的回帖 is
     * this same screen rather than a second one.
     */
    data class User(
        val id: Int,
        val title: String = "",
        val tab: ProfileTab = ProfileTab.TOPICS
    ) : Route
    data object Rank : Route
    data object Gacha : Route
    data object Notifications : Route
    data object Messages : Route
    /** One private-message thread, keyed by the other person's user id. */
    data class Thread(val partnerId: Int) : Route
    /** 发新帖. [forumId] preselects a board when it comes from that board's page. */
    data class NewTopic(val forumId: Int = 0) : Route
    data object Settings : Route
    /** 应用设置. App-local, so it opens signed out too. */
    data object AppSettings : Route
    /** 浏览历史. The app's own record, so this one needs no session either. */
    data object History : Route
    data class Login(val register: Boolean = false) : Route
    /**
     * A site page in a WebView. [offSite] is for the one errand that has to
     * leave linux.sb and come back - 第三方登录 绑定 - because the browser this
     * would otherwise be handed to is not signed in to this session.
     */
    data class SitePage(val title: String, val url: String, val offSite: Boolean = false) : Route
}

/**
 * Which screen a saved scroll position belongs to. Two appearances that share a
 * key are the same screen as far as the user is concerned, so returning to one
 * returns to where they were; the tabs are keyed apart so each keeps its own
 * place.
 */
private fun screenKey(route: Route?, tab: Tab): String = when (route) {
    null -> "tab:${tab.name}"
    is Route.Topic -> "topic:${route.id}"
    is Route.Forum -> "forum:${route.id}"
    // The tab is part of the key: 我的主题 and 我的回帖 are two lists, and each
    // should come back to where it was left.
    is Route.User -> "user:${route.id}:${route.tab.key}"
    is Route.SitePage -> "site:${route.url}"
    is Route.Login -> "login"
    Route.Rank -> "rank"
    Route.Gacha -> "gacha"
    Route.Notifications -> "notifications"
    Route.Messages -> "messages"
    is Route.Thread -> "thread:${route.partnerId}"
    is Route.NewTopic -> "new-topic"
    Route.Settings -> "settings"
    Route.AppSettings -> "app-settings"
    Route.History -> "history"
}

@Composable
fun Shell(store: UserStore) {
    val context = LocalContext.current
    val session: SessionViewModel = viewModel()
    val home: HomeViewModel = viewModel()
    val forumList: ForumListViewModel = viewModel()
    val forum: ForumViewModel = viewModel()
    val topic: TopicViewModel = viewModel()
    val explore: ExploreViewModel = viewModel()
    val rank: RankViewModel = viewModel()
    val notify: NotifyViewModel = viewModel()
    val thread: ThreadViewModel = viewModel()
    val newTopic: NewTopicViewModel = viewModel()
    val messages: MessagesViewModel = viewModel()
    val profile: ProfileViewModel = viewModel()
    val gacha: GachaViewModel = viewModel()
    val settings: SettingsViewModel = viewModel()
    // Lives as long as the app: the scheduled check runs once here, and 应用设置
    // shows what it found instead of asking again.
    val update: UpdateViewModel = viewModel()

    var tab by remember { mutableStateOf(Tab.HOME) }
    val stack = remember { mutableStateListOf<Route>() }

    // Scroll positions. Pushing a route disposes the screen underneath it, so
    // without somewhere to park its state 首页 would rewind to the top every
    // time you came back from a topic. Each tab and each route gets its own
    // slot, so every one of them keeps its own place.
    val screenState = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()

    // Who is signed in, plus the unread badges. This also covers startup: the
    // hook runs once when the shell first resumes, so there is no separate
    // launch call that would ask the site the same thing twice.
    OnReturnToForeground { session.refreshIfStale() }

    // 检查更新 on the schedule the user picked. Once per process and only if it
    // is owed, so 只手动检查 really means silent - see UpdateCheck.due.
    LaunchedEffect(Unit) { update.autoCheck(store) }

    fun push(route: Route) { stack.add(route) }

    fun pop() {
        if (stack.isEmpty()) return
        val closed = stack.removeAt(stack.lastIndex)
        // A screen you closed should open at the top next time, but the exit
        // transition keeps it composed for a moment and it saves itself again on
        // the way out - so the slot is cleared once that has happened.
        scope.launch {
            delay(400)
            if (stack.none { screenKey(it, tab) == screenKey(closed, tab) }) {
                screenState.removeState(screenKey(closed, tab))
            }
        }
    }

    fun openTopic(id: Int) {
        if (id <= 0) return
        // The id is all that is known at the tap; the title is filled in below
        // once the page answers, which History treats as the same visit.
        store.recordVisit(id)
        push(Route.Topic(id))
    }

    fun openUser(id: Int) {
        if (id > 0) push(Route.User(id))
    }

    /** Site URLs we have no native screen for open inside the app's session. */
    fun openSitePage(title: String, url: String) = push(Route.SitePage(title, url))

    /**
     * Anything behind the session goes through here, so there is exactly one way
     * into login - the same one 我的 uses. Signed out, the entry itself becomes
     * the login screen rather than opening a page that will bounce.
     */
    fun requireSignIn(action: () -> Unit) {
        if (session.me == null) push(Route.Login()) else action()
    }

    /**
     * A site page that turned out to need a session: swap it for the app's login
     * screen so 返回 goes back to whatever opened it, not to a dead page.
     */
    fun replaceWithLogin() {
        if (stack.lastOrNull() is Route.SitePage) pop()
        if (stack.lastOrNull() !is Route.Login) push(Route.Login())
    }

    /** Links inside post bodies: images and off-site links go to the browser. */
    fun openLink(url: String) {
        val abs = Site.absolute(url)
        val topicId = Regex("""/topic/(\d+)""").find(abs)?.groupValues?.get(1)?.toIntOrNull()
        when {
            topicId != null -> openTopic(topicId)
            // 登录/注册 written as an ordinary link still uses the app's entry.
            isSiteAuthUrl(abs) -> push(Route.Login(register = abs.contains("/register")))
            abs.startsWith(Site.BASE) -> openSitePage("网页", abs)
            else -> openInBrowser(context, abs)
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    val rise = with(LocalDensity.current) { 5.dp.roundToPx() }

    // One ambient room behind everything: every glass surface below repaints its
    // own slice of it, which is what makes the panels read as the same material.
    AmbientRoom {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (stack.isEmpty()) {
                    BottomBar(
                        current = tab,
                        badge = session.unreadNotifications + session.unreadMessages,
                        onPick = { picked ->
                            if (picked == tab) return@BottomBar
                            tab = picked
                            // 我的 shows the counts as numbers rather than a dot, so
                            // it is worth a look - but only once they have aged.
                            if (picked == Tab.MINE) session.refreshIfStale()
                        }
                    )
                }
            }
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    // Recorded so the navigation bar can blur the page under it.
                    .pageBackdrop()
                    .padding(bottom = padding.calculateBottomPadding())
                    .statusBarsPadding()
            ) {
                // §01/§09: a wide window only widens the reading column - the page
                // keeps §09's larger horizontal padding and stops growing at
                // readingWidth, so a desktop never stretches a row of metadata
                // across the whole screen.
                val wide = maxWidth >= SbMetrics.wideBreakpoint
                val gutter = if (wide) SbMetrics.pagePaddingWide - SbMetrics.pagePadding else 0.dp
                val page = Modifier
                    .fillMaxSize()
                    .widthIn(max = SbMetrics.readingWidth + gutter * 2)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = gutter)
                val top = stack.lastOrNull()
                AnimatedContent(
                    targetState = top,
                    modifier = page,
                    transitionSpec = {
                        // §8.2 页面切换 240-280ms, 4-6dp 上移 + 淡入.
                        (fadeIn(tween(260)) + slideInVertically(tween(260)) { rise })
                            .togetherWith(fadeOut(tween(170)))
                    },
                    label = "route"
                ) { route ->
                    screenState.SaveableStateProvider(screenKey(route, tab)) {
                        when (route) {
                            null -> TabContent(
                                tab = tab,
                                store = store,
                                session = session,
                                home = home,
                                forumList = forumList,
                                explore = explore,
                                onTopic = ::openTopic,
                                onForum = { push(Route.Forum(it)) },
                                onUser = ::openUser,
                                onAllForums = { tab = Tab.FORUMS },
                                onDiscoverEntry = { entry ->
                                    // The three that are home feeds switch the feed and
                                    // move to 首页; the other two are their own screens.
                                    val feed = when (entry) {
                                        DiscoverEntry.DIGEST -> HomeFeed.DIGEST
                                        DiscoverEntry.LOTTERY -> HomeFeed.LOTTERY
                                        DiscoverEntry.CARD -> HomeFeed.CARD
                                        else -> null
                                    }
                                    when {
                                        feed != null -> {
                                            home.switchFeed(feed)
                                            tab = Tab.HOME
                                        }
                                        entry == DiscoverEntry.RANK -> push(Route.Rank)
                                        // 称号馆 is a signed-in page: the site answers a
                                        // guest with a redirect to its own login form.
                                        else -> requireSignIn { push(Route.Gacha) }
                                    }
                                },
                                // 应用设置 is app-local: it works signed out, so it
                                // does not go through the guest redirect below.
                                onAppSettings = { push(Route.AppSettings) },
                                onMineEntry = { entry ->
                                    val me = session.me
                                    // §4.2: while signed out, tapping any of the eight
                                    // goes straight to login - no second confirmation and
                                    // no half-usable screen behind it.
                                    if (me == null) {
                                        push(Route.Login())
                                    } else when (entry) {
                                        // The profile page is the site's own home for a
                                        // user's topics, replies and points, so those
                                        // three land there rather than on invented paths.
                                        MineEntry.TOPICS -> push(Route.User(me.id, "我的主题"))
                                        MineEntry.REPLIES -> push(
                                            Route.User(me.id, "我的回帖", ProfileTab.REPLIES)
                                        )
                                        MineEntry.POINTS -> push(Route.User(me.id, "我的积分"))
                                        MineEntry.TITLES -> push(Route.Gacha)
                                        MineEntry.MESSAGES -> push(Route.Messages)
                                        // 收藏 is the site's own list, the one
                                        // /user/{id}?tab=favorites renders. There is
                                        // no local copy to show instead.
                                        MineEntry.BOOKMARKS -> push(
                                            Route.User(me.id, "我的收藏", ProfileTab.FAVORITES)
                                        )
                                        MineEntry.NOTIFICATIONS -> push(Route.Notifications)
                                        MineEntry.SETTINGS -> push(Route.Settings)
                                    }
                                },
                                onLogin = { push(Route.Login()) },
                                onRegister = { push(Route.Login(register = true)) },
                                onNewTopic = { push(Route.NewTopic()) }
                            )

                            is Route.Topic -> {
                                // 浏览历史 is the app's own, and a title only
                                // exists once the page has loaded - so the entry
                                // opened on tap is completed here rather than
                                // listing a bare id.
                                val loaded = (topic.state as? Load.Ready)?.value
                                    ?.takeIf { it.id == route.id }
                                LaunchedEffect(loaded?.id, loaded?.title) {
                                    val title = loaded?.title.orEmpty()
                                    if (title.isNotBlank()) {
                                        store.recordVisit(
                                            id = route.id,
                                            title = title,
                                            forumName = loaded?.forumName.orEmpty()
                                        )
                                    }
                                }
                                TopicScreen(
                                    topicId = route.id,
                                    vm = topic,
                                    signedIn = session.signedIn,
                                    onTopic = ::openTopic,
                                    onUser = ::openUser,
                                    onForum = { push(Route.Forum(it)) },
                                    onBack = ::pop,
                                    onLogin = { push(Route.Login()) },
                                    onRegister = { push(Route.Login(register = true)) },
                                    onOpenLink = ::openLink
                                )
                            }

                            is Route.Forum -> ForumScreen(
                                forumId = route.id,
                                vm = forum,
                                onTopic = ::openTopic,
                                onUser = ::openUser,
                                onBack = ::pop,
                                onLogin = { push(Route.Login()) },
                                // Posting from a board preselects that board.
                                onNewTopic = { push(Route.NewTopic(it)) }
                            )

                            is Route.User -> ProfileScreen(
                                userId = route.id,
                                vm = profile,
                                titleOverride = route.title,
                                startTab = route.tab,
                                onBack = ::pop,
                                onTopic = ::openTopic,
                                onLogin = { push(Route.Login()) },
                                onOpenSite = { openSitePage("个人主页", it) }
                            )

                            Route.Rank -> RankScreen(
                                vm = rank,
                                onUser = ::openUser,
                                onBack = ::pop,
                                onOpenSite = { openSitePage("排行榜", it) },
                                onLogin = { push(Route.Login()) }
                            )

                            Route.Gacha -> GachaScreen(
                                vm = gacha,
                                signedIn = session.me != null,
                                onBack = ::pop,
                                onLogin = { push(Route.Login()) },
                                onOpenSite = { openSitePage("称号馆", it) }
                            )

                            Route.Notifications -> NotificationsScreen(
                                vm = notify,
                                signedIn = session.signedIn,
                                onBack = ::pop,
                                onLogin = { push(Route.Login()) },
                                onOpenHref = ::openLink
                            )

                            Route.Messages -> MessagesScreen(
                                vm = messages,
                                signedIn = session.signedIn,
                                onBack = ::pop,
                                onLogin = { push(Route.Login()) },
                                onThread = { push(Route.Thread(it)) },
                                onOpenSite = { openSitePage("私信", it) }
                            )

                            is Route.Thread -> ThreadScreen(
                                partnerId = route.partnerId,
                                vm = thread,
                                onBack = {
                                    // Sending moves a conversation up the list, so
                                    // the list has to be re-read on the way back.
                                    messages.load(force = true)
                                    pop()
                                },
                                onUser = ::openUser,
                                onLogin = { push(Route.Login()) }
                            )

                            is Route.NewTopic -> NewTopicScreen(
                                vm = newTopic,
                                forumId = route.forumId,
                                onBack = ::pop,
                                onPosted = { topicId ->
                                    pop()
                                    // Straight into what was just written, and the
                                    // feeds behind it are now a post out of date.
                                    home.load(force = true)
                                    if (topicId > 0) openTopic(topicId)
                                },
                                onLogin = { push(Route.Login()) },
                                onOpenSite = { openSitePage("发新帖", it) }
                            )

                            Route.History -> HistoryScreen(
                                store = store,
                                onBack = ::pop,
                                onTopic = ::openTopic
                            )

                            Route.Settings -> SettingsScreen(
                                vm = settings,
                                signedIn = session.signedIn,
                                onBack = ::pop,
                                onLogin = { push(Route.Login()) },
                                onSignOut = { session.signOut { pop() } },
                                onOpenSite = { openSitePage("账号设置", it) },
                                // 绑定 leaves the site for GitHub or Google and
                                // comes back, and the name it comes back with is
                                // 我的 own - so the session is re-read after it.
                                onBindOAuth = { url ->
                                    push(Route.SitePage("第三方登录", url, offSite = true))
                                }
                            )

                            Route.AppSettings -> AppSettingsScreen(
                                store = store,
                                vm = update,
                                onBack = ::pop,
                                onHistory = { push(Route.History) }
                            )

                            is Route.Login -> AuthScreen(
                                startAtRegister = route.register,
                                onDone = { signedIn ->
                                    pop()
                                    if (signedIn) {
                                        session.refresh()
                                        home.load(force = true)
                                    }
                                }
                            )

                            is Route.SitePage -> SitePageScreen(
                                title = route.title,
                                url = route.url,
                                allowOffSite = route.offSite,
                                onBack = ::pop,
                                onLogin = ::replaceWithLogin
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(
    tab: Tab,
    store: UserStore,
    session: SessionViewModel,
    home: HomeViewModel,
    forumList: ForumListViewModel,
    explore: ExploreViewModel,
    onTopic: (Int) -> Unit,
    onForum: (Int) -> Unit,
    onUser: (Int) -> Unit,
    onAllForums: () -> Unit,
    onDiscoverEntry: (DiscoverEntry) -> Unit,
    onMineEntry: (MineEntry) -> Unit,
    onAppSettings: () -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onNewTopic: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        when (tab) {
            Tab.HOME -> HomeScreen(
                vm = home,
                onTopic = onTopic,
                onForum = onForum,
                onAllForums = onAllForums,
                onUser = onUser,
                onLogin = onLogin,
                onNewTopic = onNewTopic
            )

            Tab.FORUMS -> ForumListScreen(
                vm = forumList,
                onForum = onForum,
                onLogin = onLogin
            )

            Tab.DISCOVER -> ExploreScreen(
                vm = explore,
                onTopic = onTopic,
                onUser = onUser,
                onEntry = onDiscoverEntry,
                onLogin = onLogin
            )

            Tab.MINE -> MineScreen(
                me = session.me,
                checking = session.checking,
                onEntry = onMineEntry,
                onAppSettings = onAppSettings,
                onLogin = onLogin,
                onRegister = onRegister,
                onProfile = onUser,
                onRefresh = session::refresh,
                updateReady = hasNewerRelease(store)
            )
        }
    }
}

/**
 * §09 底部导航 78-82dp.
 *
 * One glass bar rather than an opaque surface: the ambient room keeps showing
 * through it, and a hairline - not a colour step - separates it from the content
 * scrolling underneath.
 */
@Composable
private fun BottomBar(
    current: Tab,
    badge: Int,
    onPick: (Tab) -> Unit
) {
    val tokens = LocalTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = RoundedCornerShape(
                    topStart = SbRadius.container,
                    topEnd = SbRadius.container
                ),
                level = GlassLevel.MEDIUM,
                outline = false,
                source = GlassSource.PAGE
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.hairline)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(58.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.entries.forEach { entry ->
                TabItem(
                    tab = entry,
                    selected = entry == current,
                    badge = if (entry == Tab.MINE) badge else 0,
                    modifier = Modifier.weight(1f)
                ) { onPick(entry) }
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: Tab,
    selected: Boolean,
    badge: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val tint = if (selected) tokens.accentGlow else tokens.textTertiary

    Column(
        modifier = modifier
            .pressFeedback(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .then(
                        if (selected) {
                            Modifier.liquidGlass(
                                shape = RoundedCornerShape(11.dp),
                                level = GlassLevel.HIGH,
                                refract = false,
                                tint = tokens.accentWarm.copy(alpha = 0.12f)
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                NavIcon(glyph = tab.glyph, tint = tint, selected = selected)
            }
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, end = 2.dp)
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(tokens.hotTint)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = tint
        )
    }
}
