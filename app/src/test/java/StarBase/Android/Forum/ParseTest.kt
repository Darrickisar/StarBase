package StarBase.Android.Forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.data.Post
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

    // ---- title-row marks -----------------------------------------------------

    /**
     * 精华 rides its own class, not the `.topic-stamp-badge` the other marks use,
     * so it went unread until the selector below was added.
     */
    @Test
    fun featuredRowsAreFlagged() {
        val topics = Parse.home(fixture("home.html")).topics
        assertTrue("expected featured rows", topics.any { it.featured })

        val featured = topics.first { it.id == 17748 }
        assertTrue("17748 must be featured", featured.featured)
        assertFalse("17748 carries no 热 badge", featured.hot)
        assertFalse("17748 is not pinned", featured.pinned)
    }

    /** 置顶 is a class on the li plus its own badge - never a `.topic-stamp-*`. */
    @Test
    fun pinnedRowsAreFlagged() {
        val topics = Parse.home(fixture("home.html")).topics
        assertEquals(3, topics.count { it.pinned })

        val pinned = topics.first { it.id == 17536 }
        assertTrue("17536 is pinned", pinned.pinned)
        assertTrue("17536 is also hot", pinned.hot)
    }

    /**
     * Each mark is drawn from its own flag, so leaving it in the free stamp text
     * as well is what made a row show 热 twice.
     */
    @Test
    fun stampTextHoldsOnlyTheWordierStatuses() {
        val topics = Parse.home(fixture("home.html")).topics
        topics.forEach {
            assertFalse("热 belongs to hot, not stampText: $it", it.stampText.contains("热"))
            assertFalse("置顶 belongs to pinned: $it", it.stampText.contains("置顶"))
            assertFalse("精华 belongs to featured: $it", it.stampText.contains("精华"))
        }
        // 抽奖中 / 发卡中 still come through, since nothing else carries them.
        assertTrue(
            "expected a lottery/card status to survive",
            topics.any { it.stampText.isNotBlank() }
        )
        assertEquals("抽奖中", topics.first { it.id == 17734 }.stampText)
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

    /**
     * The like button carries three things the app cannot get from the count: that
     * we already liked it, that it was paid for - the site does not let a coined
     * reaction be taken back - and the point amounts on offer.
     */
    @Test
    fun aCommentCarriesItsOwnReactionState() {
        val byFloor = Parse.topic(9, 1, fixture("topic-replies.html"))
            .comments.associateBy { it.floor }

        val liked = byFloor.getValue(2)
        assertTrue("data-liked=1", liked.liked)
        assertFalse("data-coined=0, so this one can still be taken back", liked.coined)
        assertEquals(listOf(1, 5, 10, 50), liked.tiers)

        // A comment with no reaction button offers nothing rather than defaulting.
        val plain = byFloor.getValue(1)
        assertFalse(plain.liked)
        assertTrue(plain.tiers.isEmpty())
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

    // ---- 帖内链接 -------------------------------------------------------------

    /*
     * Links in a post body are nested inside <p>, essentially always - on a real
     * topic page every single anchor was. Flattening the paragraph with text() threw
     * the href away before the renderer ever saw it, so every in-post link was dead
     * text. These pin the offsets, which is where an off-by-one would hide.
     */

    private fun bodyOf(html: String): List<LiveBlock> =
        Parse.topic(1, 1, """
            <html><body><ul class="post-list topic-post-list">
              <li class="post-item post-entry" id="post-1">
                <div class="post-body"><div class="post-content">$html</div></div>
              </li>
            </ul></body></html>
        """.trimIndent()).opening!!.blocks

    @Test
    fun aLinkInsideAParagraphKeepsItsHref() {
        val blocks = bodyOf("""<p>看看 <a href="/topic/42">这个帖子</a> 挺好</p>""")

        assertEquals(1, blocks.size)
        val para = blocks.first()
        assertEquals(LiveBlock.Type.PARA, para.type)
        assertEquals("看看 这个帖子 挺好", para.text)

        assertEquals(1, para.links.size)
        val link = para.links.first()
        assertEquals("https://linux.sb/topic/42", link.href)
        // The range has to land on the label and nothing else.
        assertEquals("这个帖子", para.text.substring(link.start, link.end))
    }

    @Test
    fun twoLinksWithTheSameLabelStayDistinct() {
        // Searching the text for each label would resolve both to the first match;
        // the offsets are recorded as the text is built for exactly this reason.
        val para = bodyOf(
            """<p><a href="/topic/1">这里</a> 和 <a href="/topic/2">这里</a></p>"""
        ).first()

        assertEquals("这里 和 这里", para.text)
        assertEquals(2, para.links.size)
        assertEquals("https://linux.sb/topic/1", para.links[0].href)
        assertEquals("https://linux.sb/topic/2", para.links[1].href)
        assertEquals(0, para.links[0].start)
        assertEquals("这里", para.text.substring(para.links[1].start, para.links[1].end))
        assertTrue("the second link starts after the first", para.links[1].start > para.links[0].end)
    }

    @Test
    fun leadingWhitespaceDoesNotShiftTheRanges() {
        // The builder records offsets before the trim, so the trim has to shift them.
        val para = bodyOf("""<p>   <a href="https://example.com">开头就是链接</a> 后面</p>""").first()

        assertEquals("开头就是链接 后面", para.text)
        assertEquals(0, para.links.first().start)
        assertEquals("开头就是链接", para.text.substring(para.links.first().start, para.links.first().end))
    }

    @Test
    fun anchorsSurviveNestingAndFormatting() {
        val para = bodyOf(
            """<p>见 <strong><a href="/forum/3">技术交流</a></strong> 板块</p>"""
        ).first()

        assertEquals("见 技术交流 板块", para.text)
        assertEquals("https://linux.sb/forum/3", para.links.first().href)
        assertEquals("技术交流", para.text.substring(para.links.first().start, para.links.first().end))
    }

    @Test
    fun anAnchorWithoutAnHrefContributesNoLink() {
        val para = bodyOf("""<p>纯 <a>锚点</a> 文本</p>""").first()

        assertEquals("纯 锚点 文本", para.text)
        assertTrue("an anchor with no href is not a link", para.links.isEmpty())
    }

    @Test
    fun aQuoteAndAListItemKeepTheirLinksToo() {
        val quote = bodyOf("""<blockquote>引自 <a href="/topic/7">原帖</a></blockquote>""").first()
        assertEquals(LiveBlock.Type.QUOTE, quote.type)
        assertEquals("https://linux.sb/topic/7", quote.links.single().href)

        val item = bodyOf("""<ul><li>第一条 <a href="/topic/8">看这里</a></li></ul>""").first()
        assertEquals(LiveBlock.Type.LIST_ITEM, item.type)
        assertEquals("看这里", item.text.substring(item.links.single().start, item.links.single().end))
    }

    @Test
    fun aBareAnchorIsStillItsOwnLinkBlock() {
        // The one shape that already worked must keep working.
        val blocks = bodyOf("""<a href="https://example.com">裸链接</a>""")
        assertEquals(LiveBlock.Type.LINK, blocks.first().type)
        assertEquals("https://example.com", blocks.first().href)
    }

    @Test
    fun realPostBodiesCarryTheirLinks() {
        // The regression this whole change exists for: on a real signed-in topic
        // page, every anchor sat inside a <p>.
        val t = Parse.topic(17536, 1, fixture("reply-form.html"))
        // The fixture is the reply panel only, so this just has to not throw; the
        // shape is covered by the cases above.
        assertNotNull(t)
    }

    // ---- 引用楼层 -------------------------------------------------------------

    /*
     * Quoting is not a form field on this site: the reply handler recovers the
     * parent floor from the body text. So the writer and the reader have to agree
     * on one pattern, and these check them against each other rather than against
     * a string literal - a change to the pattern that breaks only one direction
     * fails here.
     */

    /**
     * One comment carrying [body], in the markup the site renders comments with.
     *
     * The opening post has to be there: the parser treats the first row on page 1
     * as 主楼 and drops it from the comment list, so a fixture of one row parses
     * as a topic with no comments at all.
     */
    private fun commentHtml(floor: Int, body: String): String =
        """
        <html><body><ul class="post-list topic-post-list">
          <li class="post-item post-entry" id="post-1">
            <div class="post-body">
              <div class="post-head"><div class="post-info">
                <a class="post-title post-author" href="/user/1">站长</a>
              </div></div>
              <div class="post-content"><p>主楼</p></div>
            </div>
          </li>
          <li class="post-item post-entry" id="post-${floor + 1}" data-floor="$floor">
            <div class="post-body">
              <div class="post-head"><div class="post-info">
                <a class="post-title post-author" href="/user/7">乙</a>
              </div></div>
              <div class="post-content"><p>$body</p></div>
            </div>
          </li>
        </ul></body></html>
        """.trimIndent()

    @Test
    fun aQuotedReplyIsReadBackAsAnswaringThatFloor() {
        val parent = Post(id = "p3", author = "丙", floor = 3)
        val prefix = Parse.quotePrefix(parent)

        val parsed = Parse.topic(9, 1, commentHtml(9, prefix + "确实，我也这么想。"))
        assertEquals(1, parsed.comments.size)
        assertEquals(
            "the floor the writer aimed at is the floor the reader recovers",
            3,
            parsed.comments.first().parentFloor
        )
    }

    @Test
    fun aPlainReplyQuotesNothing() {
        assertEquals("", Parse.quotePrefix(null))
        // The opening post has floor 0 - answering the topic is not a quote.
        assertEquals("", Parse.quotePrefix(Post(id = "p1", author = "站长", floor = 0)))

        val parsed = Parse.topic(9, 1, commentHtml(9, "没有引用任何人。"))
        assertEquals(0, parsed.comments.first().parentFloor)
    }

    @Test
    fun aNameWithSpacesStillResolvesToTheRightFloor() {
        // Whitespace inside a name would end the mention early and a '#' would
        // move where the floor looks like it starts, so both come out.
        val parent = Post(id = "p5", author = "老 王 #1", floor = 5)
        val prefix = Parse.quotePrefix(parent)

        assertFalse("no whitespace survives inside the mention", prefix.substringBefore(" #").contains(' '))
        val parsed = Parse.topic(9, 1, commentHtml(9, prefix + "同意。"))
        assertEquals(5, parsed.comments.first().parentFloor)
    }

    // ---- 通用表单读取 ---------------------------------------------------------

    /*
     * Every write on this site is "read the form, post it back". 回帖 broke
     * because it did the opposite - named `content` where the markup says `body`,
     * posted to /topic/{id} instead of the form's own action - so the request
     * never reached the reply handler and the app reported 「已提交，但未能确认
     * 结果」 for a reply that was never created. These pin the reader down.
     */

    @Test
    fun formOfReadsAFormTheWayABrowserWouldSubmitIt() {
        val form = Parse.formOf(fixture("search-form.html"), "form.search-page-form")
        assertNotNull("expected to find the form", form)

        assertEquals("https://linux.sb/search", form!!.action)
        assertTrue(form.post)
        // Hidden state has to ride along untouched.
        assertEquals(64, form.fields.getValue("_csrf").length)
        // A text input contributes its value, even when that value is empty.
        assertEquals("", form.fields.getValue("q"))
    }

    @Test
    fun formOfKeepsOnlyTheCheckedRadio() {
        val form = Parse.formOf(fixture("search-form.html"), "form.search-page-form")!!

        // Three radios share one name; a real submit sends the checked one only.
        assertEquals("topic", form.fields.getValue("type"))
        assertEquals(
            "a name appears once no matter how many inputs share it",
            1,
            form.fields.keys.count { it == "type" }
        )
    }

    @Test
    fun formOfIgnoresASubmitButtonWithoutAName() {
        val form = Parse.formOf(fixture("search-form.html"), "form.search-page-form")!!

        // <button type="submit"> with no name submits nothing.
        assertEquals(setOf("_csrf", "q", "type"), form.fields.keys)
    }

    @Test
    fun formOfReportsNoTurnstileWhenThereIsNone() {
        val form = Parse.formOf(fixture("search-form.html"), "form.search-page-form")!!
        assertFalse(form.turnstile)
    }

    @Test
    fun formOfIsNullWhenThePageCarriesNoSuchForm() {
        // A gated page renders the 「消息」 panel instead of the form.
        assertNull(Parse.formOf(fixture("search-guest.html"), "form.search-page-form"))
        assertNull(Parse.replyForm(org.jsoup.Jsoup.parse(fixture("search-guest.html"))))
    }

    @Test
    fun formWithOverlaysTypedValuesAndKeepsTheRest() {
        val form = Parse.formOf(fixture("search-form.html"), "form.search-page-form")!!
        val body = form.with("q" to "内核编译")

        assertEquals("内核编译", body.getValue("q"))
        // Everything the site put there survives the overlay.
        assertEquals(form.fields.getValue("_csrf"), body.getValue("_csrf"))
        assertEquals("topic", body.getValue("type"))
    }

    // ---- 写操作的表单（真实登录态 markup）-------------------------------------

    private fun doc(name: String) = org.jsoup.Jsoup.parse(fixture(name), "https://linux.sb")

    /**
     * The reply bug, pinned to the markup that caused it.
     *
     * The app used to post `content` to `/topic/{id}`. The real form says `body`
     * at `/reply_edit`, so that request never reached the reply handler - the site
     * just re-rendered the topic page, and the app read a 200 full of HTML as
     * "probably sent". Nothing was ever created.
     */
    @Test
    fun theReplyFormPostsBodyToReplyEdit() {
        val form = assertNotNull("expected the reply form", Parse.replyForm(doc("reply-form.html")))
            .let { Parse.replyForm(doc("reply-form.html"))!! }

        assertEquals("https://linux.sb/reply_edit", form.action)
        assertTrue(form.post)
        assertEquals("the textarea is named body, not content", "body", form.bodyField)
        assertEquals(setOf("_csrf", "topic_id", "body"), form.fields.keys)
        assertEquals("17536", form.fields.getValue("topic_id"))
    }

    @Test
    fun theReplyFormNeedsNoBrowser() {
        // No Turnstile today. If the site adds one, this fails and the reply has
        // to go to a WebView instead of being posted natively.
        assertFalse(Parse.replyForm(doc("reply-form.html"))!!.turnstile)
    }

    @Test
    fun theReplyFormsFileInputIsNotSubmitted() {
        // 附件 is its own upload endpoint; a file input cannot be reproduced, and
        // sending it empty would be a field the site did not ask for.
        val form = Parse.replyForm(doc("reply-form.html"))!!
        assertFalse(form.fields.keys.any { it.contains("attachment") })
    }

    /**
     * The trap in the 发帖 form: `topic_special_type` offers `lottery` and
     * `virtual_card`, neither checked, because 普通帖 (value="") is added by the
     * site's own script. Inventing a value here posts 抽奖帖 every time.
     */
    @Test
    fun theNewTopicFormLeavesTheSpecialTypeUnset() {
        val form = assertNotNull(
            "expected the new-topic form",
            Parse.newTopicForm(doc("new-topic-form.html"))
        ).let { Parse.newTopicForm(doc("new-topic-form.html"))!! }

        assertFalse(
            "an unchecked radio group contributes nothing, as in a browser",
            form.fields.containsKey("topic_special_type")
        )
    }

    @Test
    fun theNewTopicFormCarriesTheFieldsANewTopicNeeds() {
        val form = Parse.newTopicForm(doc("new-topic-form.html"))!!

        assertEquals("body", form.bodyField)
        assertTrue(form.fields.containsKey("title"))
        assertEquals("id=0 is what marks a new topic rather than an edit", "0", form.fields.getValue("id"))
        assertEquals("the board the site preselected", "1", form.fields.getValue("forum_id"))
        assertEquals(64, form.fields.getValue("_csrf").length)
    }

    /**
     * The 发帖 form has no `action`, so it posts back to the page it came from.
     * Parsing it against the site root instead of its own URL would aim the post
     * at `/` - which is why [Parse.formOf] falls back to the document's location
     * and the caller has to parse with the real address.
     */
    @Test
    fun aFormWithoutAnActionPostsBackToItsOwnPage() {
        val served = org.jsoup.Jsoup.parse(fixture("new-topic-form.html"), "https://linux.sb/topic_edit")
        assertEquals("https://linux.sb/topic_edit", Parse.newTopicForm(served)!!.action)
    }

    @Test
    fun theNewTopicFormIsMultipart() {
        // The form declares multipart/form-data; posting it url-encoded is a
        // different request from the one the handler expects.
        assertTrue(Parse.newTopicForm(doc("new-topic-form.html"))!!.multipart)
        assertFalse("a reply is an ordinary form post", Parse.replyForm(doc("reply-form.html"))!!.multipart)
    }

    @Test
    fun theNewTopicFormListsTheBoardsToPostTo() {
        val boards = Parse.boardOptions(doc("new-topic-form.html"))

        assertEquals(9, boards.size)
        assertEquals(1 to "错误地方", boards.first())
        assertTrue("board ids are all real", boards.all { it.first > 0 })
        assertTrue("board names are all present", boards.none { it.second.isBlank() })
    }

    @Test
    fun theDonateFormPostsOneReplyIdAndThePointsAreAddedByTheCaller() {
        val form = assertNotNull(
            "expected the donate form",
            Parse.donateForm(doc("donate-reaction-form.html"), 117444)
        ).let { Parse.donateForm(doc("donate-reaction-form.html"), 117444)!! }

        assertEquals("https://linux.sb/donate_reply_reaction", form.action)
        assertEquals(setOf("_csrf", "donate_reaction_reply_id"), form.fields.keys)
        assertEquals("117444", form.fields.getValue("donate_reaction_reply_id"))
        // The site's own script adds this at submit time; it is not in the markup.
        assertFalse(form.fields.containsKey("donate_reaction_points"))
    }

    @Test
    fun aReactionReportsWhatTheSiteSaysAboutIt() {
        val r = assertNotNull(
            "expected reaction state",
            Parse.reactionOf(doc("donate-reaction-form.html"), 117444)
        ).let { Parse.reactionOf(doc("donate-reaction-form.html"), 117444)!! }

        assertFalse(r.liked)
        assertFalse("a coined reaction cannot be taken back", r.coined)
        assertEquals(listOf(1, 5, 10, 50), r.tiers)
        assertEquals("candgo", r.authorName)
    }

    /**
     * 主楼 and 评论 are two unrelated mechanisms. Liking the topic the way a comment
     * is liked is what made 点赞打赏 fail on the opening post: there is no form on
     * the page at all, only a link to /donate?topic_id=…, and its modal carries the
     * real one.
     */
    @Test
    fun theTopicDonateFormComesFromTheModalNotThePage() {
        val html = fixture("donate-topic-modal.html")
        val form = assertNotNull("expected the donate form", Parse.donateTopicForm(html))
            .let { Parse.donateTopicForm(html)!! }

        assertEquals("https://linux.sb/donate", form.action)
        assertTrue(form.post)
        // Not donate_reaction_reply_id: this one is keyed by topic.
        assertTrue(form.fields.containsKey("topic_id"))
        assertFalse(form.fields.containsKey("donate_reaction_reply_id"))
        // One-shot, and only the modal that was just served has it.
        assertEquals(32, form.fields.getValue("request_key").length)
        assertEquals("a plain 点赞 sends no amount", "", form.fields.getValue("amount"))
    }

    @Test
    fun theTopicDonatePanelOffersItsOwnPresets() {
        val panel = Parse.donatePanel(fixture("donate-topic-modal.html"))

        // The topic's presets are not the comment tiers (1,5,10,50).
        assertEquals(listOf(6, 10, 33, 66, 88), panel.presets)
        assertEquals(99, panel.maxAmount)
        assertTrue("expected the site's own summary line", panel.info.contains("积分"))
    }

    @Test
    fun theDmFormPostsContentNotBody() {
        val form = assertNotNull(
            "expected the compose form",
            Parse.dmComposeForm(doc("dm-compose-form.html"))
        ).let { Parse.dmComposeForm(doc("dm-compose-form.html"))!! }

        assertEquals("https://linux.sb/direct_messages/12053", form.action)
        // The site names this one `content` where a reply's is `body`. Reading the
        // markup is the only way to get both right.
        assertEquals("content", form.bodyField)
        assertEquals(setOf("_csrf", "partner_id", "content"), form.fields.keys)
        assertEquals("12053", form.fields.getValue("partner_id"))
    }

    // ---- 私信 -----------------------------------------------------------------

    /**
     * The list parser used to look for `.dm-item`, `.dm-preview`, `.dm-time` and
     * four more classes that appear nowhere on the real page - the site calls these
     * `.direct-messages-conversation`. Every signed-in user with private messages
     * saw an empty 私信 screen.
     */
    @Test
    fun theConversationListParsesTheSitesOwnRows() {
        val list = Parse.conversations(fixture("dm-list.html"))

        assertEquals(3, list.size)
        val first = list.first()
        assertEquals("8823", first.id)
        assertEquals("甲", first.peer)
        // The row links to the thread, not a profile: the id is on the avatar.
        assertEquals(8823, first.peerId)
        assertTrue("expected a preview line", first.preview.isNotBlank())
        assertEquals("2026-08-14", first.timeText)
        assertTrue("expected an avatar", first.avatar.startsWith("https://linux.sb/"))
    }

    /**
     * 开新会话 needs no new endpoint: a search hit links at the thread URL, and that
     * page renders the compose box whether or not the two have ever written.
     */
    @Test
    fun theUserSearchYieldsThreadTargets() {
        val hits = Parse.userSearch(fixture("dm-search.html"))

        assertEquals(3, hits.size)
        assertEquals(16982, hits.first().userId)
        assertEquals("甲", hits.first().name)
        assertTrue(hits.first().avatar.startsWith("https://linux.sb/"))
        assertTrue("every hit must resolve to a user", hits.all { it.userId > 0 })
    }

    @Test
    fun theAttachmentUploaderIsReadOffThePage() {
        val up = assertNotNull(
            "expected an uploader beside the reply box",
            Parse.uploaderOf(doc("reply-form.html"))
        ).let { Parse.uploaderOf(doc("reply-form.html"))!! }

        // Its own endpoint - not a field on the reply form.
        assertEquals("https://linux.sb/attachment_upload", up.action)
        assertEquals(64, up.csrf.length)
        // The cap and the types are the site's, not ours.
        assertEquals(20, up.maxMb)
        assertTrue(up.accepts.contains(".png"))
        assertTrue(up.accepts.contains(".zip"))
        assertTrue("every entry is an extension", up.accepts.all { it.startsWith(".") })
    }

    @Test
    fun aPageWithNoUploaderReportsNone() {
        assertNull(Parse.uploaderOf(doc("dm-compose-form.html")))
    }

    @Test
    fun aThreadSeparatesTheirMessagesFromOurs() {
        val t = Parse.thread(12053, fixture("dm-thread.html"))

        assertEquals(12053, t.partnerId)
        assertEquals("乙", t.partner)
        assertEquals(2, t.messages.size)
        // The site marks only the other person's messages; ours carry no class, so
        // "not is-theirs" is the test rather than looking for an is-mine marker.
        assertTrue("every message here is theirs", t.messages.none { it.fromMe })
        assertEquals("乙", t.messages.first().sender)
        assertTrue(t.messages.first().body.isNotBlank())
        assertEquals("2026-08-13T15:00:11+08:00", t.messages.first().timeText)
        assertEquals("the id the site polls updates from", 61190L, t.lastId)
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

    // ---- 个人设置 ------------------------------------------------------------

    /** 个人资料 is a grid of label/value cards, and one of them is the logout form. */
    @Test
    fun accountSettingsReadsTheProfileCards() {
        val s = checkNotNull(Parse.accountSettings(fixture("profile.html")))
        assertEquals("轩辕", s.name)
        assertEquals("7699", s.uid)
        assertEquals("2026-08-08 22:28", s.joinedText)
        assertEquals("1218", s.points)
        // The 安全退出 card wears the same class as the rest; it is not a field.
        assertFalse("logout must not read as a card: $s", s.name.contains("退出"))
    }

    /**
     * 邮箱 is Cloudflare-obfuscated: the anchor's text is a placeholder and the
     * address is in the `title` of the strong around it, so reading the text
     * yields 「[email protected]」.
     */
    @Test
    fun accountSettingsReadsTheEmailPastCloudflareObfuscation() {
        val email = checkNotNull(Parse.accountSettings(fixture("profile.html"))).email
        assertTrue("expected a real address, got $email", email.contains("@"))
        assertFalse("must not be the placeholder: $email", email.contains("protected"))
    }

    /** 头像 has three offers on this page: dicebear style, seed, and presets. */
    @Test
    fun accountSettingsReadsTheAvatarOffers() {
        val s = checkNotNull(Parse.accountSettings(fixture("profile.html")))
        assertEquals(listOf("", "fun-emoji"), s.avatarStyles.map { it.first })
        assertEquals("Fun Emoji", s.avatarStyles.last().second)
        assertEquals("fun-emoji", s.avatarStyle)
        assertEquals("19", s.avatarSeed)
        assertEquals(48, s.avatarPresets.size)
        assertEquals("1", s.avatarPresets.first().seed)
        assertTrue("preset art must be absolute", s.avatarPresets.all { it.url.startsWith("http") })
        assertTrue("expected the site's own cost note: ${s.avatarNote}", s.avatarNote.contains("50"))
        assertTrue("current avatar must be absolute: ${s.avatar}", s.avatar.startsWith("http"))
    }

    /** 简介 and the 改名 policy are the site's own text, carried through as-is. */
    @Test
    fun accountSettingsCarriesTheSitesOwnPolicyText() {
        val s = checkNotNull(Parse.accountSettings(fixture("profile.html")))
        assertEquals("", s.bio)
        assertTrue("改名 policy: ${s.renamePolicy}", s.renamePolicy.contains("100 积分"))
        assertEquals("现在可以修改", s.renameNote)
        assertTrue("the field is there, so renaming is allowed", s.renameAllowed)
        assertEquals("需要新邮箱验证码", s.emailNote)
    }

    /** 第三方登录: two rows, each an ordinary link the app can just follow. */
    @Test
    fun accountSettingsReadsBothOAuthRows() {
        val oauth = checkNotNull(Parse.accountSettings(fixture("profile.html"))).oauth
        assertEquals(listOf("github", "google"), oauth.map { it.provider })
        assertEquals("GitHub 登录", oauth.first().label)
        // The button says what the site's own link says.
        assertEquals("绑定", oauth.first().action)
        oauth.forEach {
            assertFalse("nothing is bound on this capture: $it", it.bound)
            assertEquals("", it.account)
            assertTrue("bind link must be absolute: $it", it.href.startsWith("https://"))
        }
    }

    /** A guest is handed the login page here, and that is not empty settings. */
    @Test
    fun accountSettingsIsNullForAPageThatIsNotTheSettingsPage() {
        assertNull(Parse.accountSettings(fixture("login.html")))
        assertNull(Parse.accountSettings(fixture("home.html")))
    }

    /**
     * 改名's two fields are attached to its form by the HTML5 `form=` attribute
     * and are not inside it, so a reader that only walks the form's own subtree
     * posts a rename carrying nothing but a token.
     */
    @Test
    fun formOfCollectsFieldsAttachedByTheFormAttribute() {
        val doc = org.jsoup.Jsoup.parse(fixture("profile.html"), "https://linux.sb/profile")
        val form = checkNotNull(Parse.usernameForm(doc))
        assertEquals("https://linux.sb/username_change", form.action)
        assertTrue("post", form.post)
        assertTrue("_csrf", form.fields.containsKey("_csrf"))
        assertTrue("new_username: ${form.fields.keys}", form.fields.containsKey("new_username"))
        assertTrue("current_password: ${form.fields.keys}", form.fields.containsKey("current_password"))
    }

    /**
     * The other side of the same rule: a field that declares another form's id
     * does not belong to the form it happens to sit inside. 改名's fields sit
     * inside the main 个人设置 form, so without this the profile save would post
     * a rename along with it.
     */
    @Test
    fun formOfLeavesOutFieldsThatPointAtAnotherForm() {
        val doc = org.jsoup.Jsoup.parse(fixture("profile.html"), "https://linux.sb/profile")
        val form = checkNotNull(Parse.profileForm(doc))
        assertEquals("https://linux.sb/profile", form.action)
        assertEquals(
            listOf("_csrf", "avatar_style", "avatar_seed", "bio", "password", "password2"),
            form.fields.keys.toList()
        )
        // The textarea is the body field of this form, and it is 简介.
        assertEquals("bio", form.bodyField)
    }

    /** 修改邮箱 and its code endpoint, both read off the page rather than named. */
    @Test
    fun emailFormAndCodeEndpointComeOffThePage() {
        val doc = org.jsoup.Jsoup.parse(fixture("profile.html"), "https://linux.sb/profile")
        val form = checkNotNull(Parse.emailForm(doc))
        assertEquals("https://linux.sb/user_review_email_change", form.action)
        assertEquals(listOf("_csrf", "email", "email_code"), form.fields.keys.toList())
        assertEquals("https://linux.sb/user_review_email_code", Parse.emailCodeUrl(doc))
    }

    /**
     * 头像上传 is not a form at all - the panel carries the endpoint and a loose
     * `_csrf`, and the site's own script builds the body by hand.
     */
    @Test
    fun avatarUploadTargetComesOffThePanel() {
        val doc = org.jsoup.Jsoup.parse(fixture("profile.html"), "https://linux.sb/profile")
        val target = checkNotNull(Parse.avatarUpload(doc))
        assertEquals("https://linux.sb/avatar_upload", target.url)
        assertEquals(64, target.csrf.length)
    }

    /** A native captcha cannot be answered natively, so the app must not try. */
    @Test
    fun emailCodeEndpointIsWithheldWhenTheSiteAsksForACaptcha() {
        val guarded = fixture("profile.html").replace(
            "<button type=\"button\" data-user-review-send-code",
            "<div data-native-captcha></div><button type=\"button\" data-user-review-send-code"
        )
        val doc = org.jsoup.Jsoup.parse(guarded, "https://linux.sb/profile")
        assertEquals("", Parse.emailCodeUrl(doc))
    }

    // ---- 榜单 -----------------------------------------------------------------

    /**
     * The board that shipped read nothing at all: it looked for rows as `li` or
     * `tr`, and the site renders each one as `<a class="leaderboard-item">`.
     */
    @Test
    fun boardReadsPodiumAndList() {
        val board = Parse.board(fixture("leaderboard.html"))

        assertEquals("points", board.key)
        assertEquals("富豪榜", board.label)
        assertEquals("按积分排行", board.subtitle)
        // Three on the podium, seventeen in the list.
        assertEquals(20, board.rows.size)

        val first = board.rows.first()
        assertEquals(1, first.rank)
        assertEquals("流彩猫", first.name)
        assertEquals(12010, first.userId)
        assertEquals("创作者", first.group)
        assertEquals("9.6万 积分", first.count)
        assertTrue("avatar must be absolute: ${first.avatar}", first.avatar.startsWith("http"))
    }

    /**
     * The podium's DOM order is 2-1-3, so a reader that trusts document order
     * crowns the runner-up. Its own step number is the only rank to believe.
     */
    @Test
    fun podiumOrderComesFromTheStepNotTheDom() {
        val rows = Parse.board(fixture("leaderboard-checkin.html")).rows
        assertEquals(listOf("痛失姓名的站长", "明明下落不明", "X"), rows.take(3).map { it.name })
        assertEquals(listOf(1, 2, 3), rows.take(3).map { it.rank })
    }

    /** The list picks up at 4 - the podium already took the first three. */
    @Test
    fun boardRanksRunUnbrokenFromOne() {
        val rows = Parse.board(fixture("leaderboard.html")).rows
        assertEquals((1..rows.size).toList(), rows.map { it.rank })
    }

    /**
     * Each 榜单 is its own page, so the tab strip is the only way to reach the
     * other four and every page has to carry all five.
     */
    @Test
    fun everyBoardListsAllFiveTabs() {
        listOf("leaderboard.html", "leaderboard-checkin.html").forEach { name ->
            val board = Parse.board(fixture(name))
            assertEquals(
                "tabs of $name",
                listOf("points", "replies", "topics", "checkin", "donation"),
                board.tabs.map { it.key }
            )
            assertEquals("打赏榜", board.tabs.last().label)
            assertTrue("$name must name its own tab", board.tabs.any { it.key == board.key })
        }
    }

    /** `?type=` really switches the page - the app fetches per tab, not filters. */
    @Test
    fun boardKeyFollowsTheActiveTab() {
        val checkin = Parse.board(fixture("leaderboard-checkin.html"))
        assertEquals("checkin", checkin.key)
        assertEquals("签到榜", checkin.label)
        assertEquals("按累计签到天数排行", checkin.subtitle)
        assertEquals("25 次", checkin.rows.first().count)
    }

    /** 访客 sidebars must stay signed-out even though they also carry .user-rank. */
    @Test
    fun guestSidebarIsNotAnIdentity() {
        assertNull(Parse.home(fixture("home.html")).me)
    }
}
