package StarBase.Android.Forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.net.Parse
import StarBase.Android.Forum.net.SiteException

/**
 * The parsers run against real pages captured from linux.sb, so a selector that
 * stops matching the live markup fails here instead of showing an empty screen
 * on the phone.
 */
class ParseTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) {
            "missing test fixture $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    // ---- home ----------------------------------------------------------------

    @Test
    fun homeParsesStatsForumsAndFeed() {
        val page = Parse.home(fixture("home.html"))

        assertEquals("8906", page.stats.topics)
        assertEquals("120365", page.stats.replies)
        assertEquals("25665", page.stats.users)
        assertEquals("149", page.stats.online)
        assertEquals("alzcoms", page.stats.newestUser)

        assertTrue("expected boards, got ${page.forums.size}", page.forums.size >= 5)
        val first = page.forums.first()
        assertEquals(1, first.id)
        assertEquals("错误地方", first.name)
        assertEquals("4855", first.topicCount)

        assertTrue("expected daily hot topics", page.dailyHot.size >= 5)
        val hot = page.dailyHot.first()
        assertEquals(17425, hot.id)
        assertEquals("【0元鸡蛋】 免费领QQ会员", hot.title)
        assertEquals(58, hot.replies)

        assertTrue("expected a topic feed", page.topics.isNotEmpty())
    }

    @Test
    fun guestHomeHasNoSignedInUser() {
        assertNull(Parse.home(fixture("home.html")).me)
    }

    // ---- board page ----------------------------------------------------------

    @Test
    fun forumPageParsesRowsAndPagination() {
        val page = Parse.forum(1, 1, fixture("forum1.html"))

        assertEquals("错误地方", page.name)
        assertTrue("expected rows, got ${page.topics.size}", page.topics.size >= 10)
        assertEquals(50, page.lastPage)

        val row = page.topics.first()
        assertEquals(17842, row.id)
        assertEquals("十二点开整", row.title)
        assertEquals("yyyccc", row.author)
        assertEquals(23618, row.authorId)
        assertEquals(1, row.forumId)
        assertEquals("错误地方", row.forumName)
        assertEquals("刚刚", row.timeText)
        assertTrue(row.avatar.startsWith("https://linux.sb/app/upload/avatar_upload/"))
    }

    @Test
    fun everyForumRowHasAUsableIdAndTitle() {
        val topics = Parse.forum(1, 1, fixture("forum1.html")).topics
        topics.forEach {
            assertTrue("id must be positive: $it", it.id > 0)
            assertTrue("title must not be blank: $it", it.title.isNotBlank())
        }
        // Duplicate ids would crash a LazyColumn keyed on them.
        assertEquals(topics.size, topics.distinctBy { it.id }.size)
    }

    @Test
    fun hotRowsAreFlagged() {
        val topics = Parse.forum(1, 1, fixture("forum1.html")).topics
        assertTrue("expected at least one hot row", topics.any { it.hot })
    }

    // ---- topic page ----------------------------------------------------------

    @Test
    fun topicParsesOpeningPostWithGachaTitle() {
        val t = Parse.topic(1, 1, fixture("topic1.html"))

        assertEquals(1, t.id)
        assertEquals("LINUX SB上线 更新的理想型社区", t.title)

        val opening = assertNotNull("expected an opening post", t.opening).let { t.opening!! }
        assertEquals("痛失姓名的站长", opening.author)
        assertEquals(1, opening.authorId)
        assertEquals("2026-08-07", opening.timeText)
        assertEquals("建设者", opening.group)
        assertEquals("UID 1", opening.uid)
        assertTrue(opening.isOpening)

        val title = assertNotNull("expected a gacha title", opening.title).let { opening.title!! }
        assertEquals("真的站长", title.name)
        assertEquals("001", title.serial)
        assertEquals("ur", title.tier)

        assertEquals(
            listOf("打造更新的理想型社区"),
            opening.blocks.filter { it.type == LiveBlock.Type.PARA }.map { it.text }
        )
    }

    @Test
    fun guestTopicReportsLoginGateAndNoReply() {
        val t = Parse.topic(1, 1, fixture("topic1.html"))

        assertTrue("comments should be gated for a guest", t.commentsNeedLogin)
        assertEquals("declared comment count comes from the gate card", 356, t.commentCount)
        assertFalse("a guest cannot reply", t.canReply)
        assertEquals("guest pages carry no csrf token", "", t.csrf)
        assertTrue("guest sees no comments", t.comments.isEmpty())
    }

    @Test
    fun topicPaginationIsDetected() {
        // /topic/1 is a single page; the fixture's own links are the only source.
        val t = Parse.topic(1, 1, fixture("topic1.html"))
        assertTrue("last page must be at least 1", t.lastPage >= 1)
    }

    // ---- comment threading ---------------------------------------------------

    /**
     * The comment section is only served to a signed-in visitor, so this fixture
     * is hand-built from the markup linux.sb's own quote_threads and donate
     * scripts operate on. See the comment at the top of topic-replies.html.
     */
    @Test
    fun commentsCarryTheSiteFloorReplyIdAndReactionCount() {
        val t = Parse.topic(9, 1, fixture("topic-replies.html"))

        assertEquals(10, t.comments.size)
        assertEquals("declared count falls back to what the page shows", 10, t.commentCount)
        assertTrue("a signed-in page can reply", t.canReply)

        // The opening post has no data-floor; its badge is the topic-level one.
        val opening = assertNotNull("expected an opening post", t.opening).let { t.opening!! }
        assertEquals(0, opening.floor)
        assertEquals(1, opening.replyId)
        assertEquals(3, opening.likes)

        val first = t.comments.first()
        assertEquals("floor comes from data-floor, not the row index", 1, first.floor)
        assertEquals(2, first.replyId)
        assertEquals("甲", first.author)

        val second = t.comments[1]
        assertEquals(2, second.floor)
        assertEquals("a comment's own .donate-reaction-count", 12, second.likes)
    }

    @Test
    fun quoteReferencesResolveTheWayTheSiteThreadsThem() {
        val byFloor = Parse.topic(9, 1, fixture("topic-replies.html"))
            .comments.associateBy { it.floor }

        // ?floor= link left by the 引用 button.
        assertEquals(1, byFloor.getValue(2).parentFloor)
        // ?replyid= link, mapped through the row that owns id="post-3".
        assertEquals(2, byFloor.getValue(8).parentFloor)
        // "@丙 #3" typed by hand, no link at all.
        assertEquals(3, byFloor.getValue(9).parentFloor)
        // A floor from an earlier page: kept as a reference, but not counted here.
        assertEquals(99, byFloor.getValue(10).parentFloor)
        assertEquals(0, byFloor.getValue(1).parentFloor)
    }

    @Test
    fun replyCountsAndHotFollowTheSiteCollapseThreshold() {
        val byFloor = Parse.topic(9, 1, fixture("topic-replies.html"))
            .comments.associateBy { it.floor }

        assertEquals("floors 2-7 all answer #1", 6, byFloor.getValue(1).replyCount)
        assertEquals(1, byFloor.getValue(2).replyCount)
        assertEquals(1, byFloor.getValue(3).replyCount)
        assertEquals("a cross-page parent is not counted here", 0, byFloor.getValue(10).replyCount)

        // 热 is the site's own collapse threshold, never a like count: #2 has the
        // most likes on the page and is not hot, #1 has no likes and is.
        assertTrue("#1 passed 展开剩余 N 条回复", byFloor.getValue(1).isHot)
        assertFalse("12 赞 does not make a comment hot", byFloor.getValue(2).isHot)
        assertFalse(byFloor.getValue(9).isHot)
    }

    // ---- search --------------------------------------------------------------

    /**
     * The bug this pins down: /search answers a guest with 200 and the site's
     * 「消息」 panel, so parsing it for rows yields an empty list and the screen
     * says "no results" when the real answer is "sign in".
     */
    @Test
    fun aGatedPageIsReportedAsARefusalRatherThanNoResults() {
        val guest = fixture("search-guest.html")

        assertNull("a gated page carries no search form", Parse.searchForm(guest))
        val refusal = Parse.refusal(guest)
        assertNotNull("expected a refusal", refusal)
        assertEquals("请登录后操作", refusal!!.message)
        assertEquals(SiteException.Kind.AUTH, refusal.kind)

        // A page the site is willing to serve must not look like a refusal.
        assertNull(Parse.refusal(fixture("search-form.html")))
    }

    @Test
    fun searchIsSubmittedTheWayTheSiteRendersTheForm() {
        val form = Parse.searchForm(fixture("search-form.html"))
        assertNotNull("expected the search form", form)
        assertTrue("the site pages by POST, so searching is a POST", form!!.post)
        assertEquals("https://linux.sb/search", form.action)
        assertEquals("q", form.queryField)

        // The token has to ride along or the site redirects to /form_error.
        assertEquals(64, form.fields.getValue("_csrf").length)
        // The checked radio, not the last one, and not an empty required group.
        assertEquals("topic", form.fields.getValue("type"))
        assertFalse("the keyword is supplied by the caller", form.fields.containsKey("q"))
    }

    @Test
    fun searchResultsParseAsOrdinaryTopicRows() {
        val hits = Parse.feed(fixture("search-results.html"))

        assertEquals(2, hits.size)
        assertEquals(1201, hits.first().id)
        assertEquals("内核编译的一点记录", hits.first().title)
        assertEquals("甲", hits.first().author)
        assertEquals(11, hits.first().authorId)
        assertEquals(18, hits.first().replies)
        assertEquals(1188, hits[1].id)
    }

    // ---- login ---------------------------------------------------------------

    @Test
    fun loginPageExposesCsrfAndProvesAutomationIsBlocked() {
        val html = fixture("login.html")
        val doc = org.jsoup.Jsoup.parse(html, "https://linux.sb")

        assertEquals(64, Parse.csrfOf(doc).length)

        // These are why login runs in a WebView instead of being automated:
        // a signed captcha token and a proof-of-work challenge.
        assertNotNull(doc.selectFirst("input[name=native_captcha_token]"))
        assertNotNull(doc.selectFirst("input[name=native_captcha_pow]"))
        assertEquals("1", doc.selectFirst("[data-pow-required]")?.attr("data-pow-required"))
    }

    // ---- 登录页识别 -------------------------------------------------------

    /**
     * A gated route answers a guest with a 302 to /login, which OkHttp follows -
     * so the caller gets 200 and the login form. Every parser that sits behind a
     * session depends on this telling the two apart.
     */
    @Test
    fun loginPageIsRecognisedSoGatedReadsAreNotParsedAsEmpty() {
        assertTrue(Parse.isLoginPage(fixture("login.html")))

        assertFalse(Parse.isLoginPage(fixture("home.html")))
        assertFalse(Parse.isLoginPage(fixture("topic1.html")))
        assertFalse(Parse.isLoginPage(fixture("forum1.html")))
        assertFalse(Parse.isLoginPage(fixture("search-form.html")))
    }
    // ---- 称号馆 ---------------------------------------------------------------

    /**
     * /gacha needs a session, so it cannot be captured the way the other
     * fixtures were. This markup is reconstructed from the site's own
     * plugins.css class names plus the title badge that renders publicly on a
     * profile page - which is what the parser was written against.
     */
    private val gachaHtml = """
        <html><head><title>称号馆 - LINUX SB</title></head><body>
        <div class="gacha-center-page">
          <div class="gacha-center-header">
            <h1>称号馆</h1>
            <p>消耗烧饼抽取称号。</p>
            <span class="gacha-center-stat">烧饼 1280</span>
            <span class="gacha-center-stat">已抽 42 次</span>
          </div>
          <div class="gacha-sub-stats">今日已抽 3 / 20</div>
          <div class="gacha-good-news"><div class="gacha-good-news-item">甲 抽中了 真的站长</div></div>
          <div class="gacha-sidebar-title">
            <a class="gacha-title-badge gacha-title-ur gacha-title-link" href="/gacha">
              <span class="gacha-title-icon">忍</span>
              <span class="gacha-title-name">真的站长</span>
              <span class="gacha-title-rarity">001</span>
            </a>
          </div>
          <form method="post" action="/gacha">
            <input type="hidden" name="_csrf" value="tok">
            <div class="gacha-actions">
              <button class="gacha-pull-btn" name="action" value="pull1">抽一次</button>
              <button class="gacha-pull-btn gacha-pull-10" name="action" value="pull10">抽十次</button>
              <button class="gacha-pull-btn" name="action" value="pull100" disabled>抽一百次</button>
            </div>
          </form>
          <div class="gacha-profile-collection-head"><strong>已收集 2 个</strong></div>
          <div class="gacha-profile-grid">
            <div class="gacha-profile-item">
              <span class="gacha-title-badge gacha-title-ssr">
                <span class="gacha-title-icon">星</span>
                <span class="gacha-title-name">饼圣</span>
                <span class="gacha-title-rarity">SSR</span>
              </span>
              <div class="gacha-profile-desc">吃饼无敌手</div>
              <div class="gacha-profile-meta">获得于 2026-08-01</div>
              <span class="gacha-equipped-label">佩戴中</span>
            </div>
            <div class="gacha-profile-item">
              <span class="gacha-title-badge gacha-title-r">
                <span class="gacha-title-name">学徒</span>
                <span class="gacha-title-rarity">R</span>
              </span>
              <span class="gacha-status-tag">7 天后过期</span>
              <span class="gacha-expired-label">已过期</span>
            </div>
          </div>
          <div class="gacha-pool-section">
            <h3>奖池出率</h3>
            <div class="gacha-pool-grid">
              <div class="gacha-pool-rarity">
                <span class="gacha-pool-rarity-label">UR</span>
                <span class="gacha-pool-rarity-count">3 个</span>
                <span class="gacha-pool-rarity-rate">0.5%</span>
              </div>
              <div class="gacha-pool-rarity">
                <span class="gacha-pool-rarity-label">SSR</span>
                <span class="gacha-pool-rarity-count">12 个</span>
                <span class="gacha-pool-rarity-rate">4%</span>
              </div>
            </div>
          </div>
          <div class="gacha-all-titles">
            <h3>全部称号</h3>
            <div class="gacha-all-grid">
              <div class="gacha-all-item">
                <span class="gacha-title-badge gacha-title-n">
                  <span class="gacha-title-name">路人</span>
                  <span class="gacha-title-rarity">N</span>
                </span>
              </div>
            </div>
          </div>
        </div>
        </body></html>
    """.trimIndent()
    @Test
    fun gachaPageParsesEverySection() {
        val page = Parse.gacha(gachaHtml)

        assertEquals("称号馆", page.heading)
        assertEquals("消耗烧饼抽取称号。", page.intro)
        assertEquals(listOf("烧饼 1280", "已抽 42 次"), page.stats)
        assertEquals("今日已抽 3 / 20", page.subStats)
        assertEquals(listOf("甲 抽中了 真的站长"), page.news)

        // The equipped one is the one the collection marks, not the sidebar badge.
        assertEquals("饼圣", page.equipped?.name)
        assertEquals("ssr", page.equipped?.tier)
        assertTrue(page.equipped!!.equipped)

        assertEquals("已收集 2 个", page.ownedHeading)
        assertEquals(2, page.owned.size)
        assertEquals("吃饼无敌手", page.owned[0].description)
        assertEquals("获得于 2026-08-01", page.owned[0].meta)
        assertEquals("学徒", page.owned[1].name)
        assertEquals("7 天后过期", page.owned[1].status)
        assertTrue(page.owned[1].expired)

        assertEquals("奖池出率", page.poolHeading)
        assertEquals(2, page.rarities.size)
        assertEquals("UR", page.rarities[0].label)
        assertEquals("3 个", page.rarities[0].count)
        assertEquals("0.5%", page.rarities[0].rate)

        assertEquals("全部称号", page.allHeading)
        assertEquals(listOf("路人"), page.all.map { it.name })
    }

    /**
     * A UR badge puts its serial where every other tier puts the tier code, so
     * the digits have to be read as a serial rather than as a rarity.
     */
    @Test
    fun urBadgeSerialIsNotMistakenForATier() {
        // With the collection gone, the sidebar badge is what 当前佩戴 falls back to.
        val page = Parse.gacha(
            gachaHtml.replace("class=\"gacha-profile-grid\"", "class=\"gone\"")
        )
        assertEquals("真的站长", page.equipped?.name)
        assertEquals("ur", page.equipped?.tier)
        assertEquals("001", page.equipped?.serial)
        assertEquals("忍", page.equipped?.icon)
    }
    /**
     * The pull buttons are re-posted rather than reconstructed - guessing field
     * names is what broke search - so each one has to carry its form whole.
     */
    @Test
    fun pullButtonsCarryTheirFormWholeIncludingCsrf() {
        val pulls = Parse.gacha(gachaHtml).pulls

        assertEquals(listOf("抽一次", "抽十次", "抽一百次"), pulls.map { it.label })
        assertEquals("https://linux.sb/gacha", pulls[0].action)
        assertEquals("tok", pulls[0].fields["_csrf"])
        assertEquals("pull1", pulls[0].fields["action"])
        assertEquals("pull10", pulls[1].fields["action"])
        // A disabled button still parses; the screen greys it out instead.
        assertTrue(pulls[0].enabled)
        assertFalse(pulls[2].enabled)
    }

    @Test
    fun gachaResultReadsBothTheSingleCardAndTheTenGrid() {
        val single = Parse.gachaResult(
            """
            <div class="gacha-result-card">
              <span class="gacha-result-icon">星</span>
              <span class="gacha-result-rarity">SSR</span>
              <span class="gacha-result-name">饼圣</span>
              <span class="gacha-result-desc">吃饼无敌手</span>
              <span class="gacha-result-quantity">x1</span>
            </div>
            """.trimIndent()
        )
        assertTrue(single.ok)
        assertEquals(1, single.titles.size)
        assertEquals("饼圣", single.titles[0].name)
        assertEquals("SSR", single.titles[0].tier)
        assertEquals("x1", single.titles[0].meta)

        val ten = Parse.gachaResult(
            """
            <div class="gacha-pull-10-grid">
              <div class="gacha-pull-10-item">
                <span class="gacha-pull-10-rarity">N</span>
                <span class="gacha-pull-10-name">路人</span>
                <span class="gacha-pull-10-quantity">x2</span>
              </div>
              <div class="gacha-pull-10-item">
                <span class="gacha-pull-10-rarity">R</span>
                <span class="gacha-pull-10-name">学徒</span>
              </div>
            </div>
            """.trimIndent()
        )
        assertEquals(listOf("路人", "学徒"), ten.titles.map { it.name })
    }

    // ---- 我的回帖 -------------------------------------------------------------

    /**
     * `?tab=replies` renders the same `li.post-item` rows as 主题, so one parser
     * covers all three tabs. What it adds is the excerpt - without it a 回帖 row
     * would say nothing about the reply it stands for.
     */
    @Test
    fun repliesTabParsesRowsWithTheirExcerpt() {
        val profile = Parse.profile(1, fixture("user1-replies.html"))

        assertTrue("expected reply rows, got ${profile.topics.size}", profile.topics.size >= 5)

        val first = profile.topics.first()
        assertEquals(18078, first.id)
        assertEquals("积分BUG", first.title)
        // The title href is /topic/18078?replyid=122223 - the topic id still wins.
        assertEquals("纯牛马", first.author)
        assertEquals(14518, first.authorId)
        assertTrue(
            "expected an excerpt on a 回帖 row",
            first.excerpt.startsWith("以后也不会主动删内容了")
        )

        // Every row on this tab is a reply, so every row should carry one.
        assertTrue(profile.topics.all { it.excerpt.isNotBlank() })
    }

    /** 主题 rows are the same markup without the excerpt, and must stay blank. */
    @Test
    fun topicRowsCarryNoExcerpt() {
        assertTrue(Parse.home(fixture("home.html")).topics.all { it.excerpt.isBlank() })
    }

    // ---- 侧栏身份 -------------------------------------------------------------

    /**
     * The signed-in sidebar the site renders. `.user-rank` is not one string: the
     * plugins inject the user-group badge and the gacha badge into it and let the
     * points follow as loose text, so `.text()` on it comes back glued -
     * 「隐藏大佬SSR伪装者·积分110」. This locks down the split.
     */
    private val sidebar = """
        <div class="sidebar-card user-card">
          <a href="/user/1"><img class="avatar-img" src="/uploads/avatar/1.webp"></a>
          <div class="user-name">纯牛马</div>
          <div class="user-rank">
            <span class="user-uid-badge-group" title="用户组：隐藏大佬"
              ><span class="user-uid-badge-group-name">隐藏大佬</span></span
            ><span class="gacha-sidebar-title"><span class="gacha-title-badge"
              ><span class="gacha-title-rarity">SSR</span
              ><span class="gacha-title-name">伪装者</span></span></span>·积分110
          </div>
        </div>
    """.trimIndent()

    @Test
    fun sidebarSplitsGroupTitleAndPointsTheSiteGluesTogether() {
        // What the old one-shot read produced, kept as the thing being fixed.
        val glued = org.jsoup.Jsoup.parse(sidebar).selectFirst(".user-rank")!!.text()
        assertEquals("隐藏大佬SSR伪装者·积分110", glued)

        val me = checkNotNull(Parse.home(sidebar).me)
        assertEquals("纯牛马", me.name)
        assertEquals(1, me.id)
        // 用户组 alone - no tier, no title, no counter riding along.
        assertEquals("隐藏大佬", me.rank)
        // The counter splits on its label, so it separates with no delimiter there.
        assertEquals("积分 110", me.points)
        val title = checkNotNull(me.title)
        assertEquals("伪装者", title.name)
        // The sidebar badge carries no gacha-title-ssr class, so the tier has to
        // come out of the rarity slot.
        assertEquals("SSR", title.tier)
        assertEquals("", title.serial)
    }

    /**
     * Two fallbacks: the group comes from the badge tooltip when the inner span is
     * absent, and an unrecognised bare number stays out of 用户组 - a stray 「110」
     * printed as a group is worse than no group at all.
     */
    @Test
    fun sidebarFallsBackToTheBadgeTooltipAndNeverShowsBareNumbersAsGroup() {
        val groupSpan = "<span class=\"user-uid-badge-group-name\">隐藏大佬</span>"
        val tooltipOnly = sidebar.replace(groupSpan, "")
        assertEquals("隐藏大佬", checkNotNull(Parse.home(tooltipOnly).me).rank)

        val noBadge = sidebar
            .replace("class=\"user-uid-badge-group\" title=\"用户组：隐藏大佬\"", "class=\"x\"")
            .replace(groupSpan, "110")
        val me = checkNotNull(Parse.home(noBadge).me)
        assertEquals("", me.rank)
        assertEquals("积分 110", me.points)
    }

    /** 访客 sidebars must stay signed-out even though they also carry .user-rank. */
    @Test
    fun guestSidebarIsNotAnIdentity() {
        assertNull(Parse.home(fixture("home.html")).me)
    }
}
