package StarBase.Android.Forum

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import StarBase.Android.Forum.data.BlockRule
import StarBase.Android.Forum.data.BoardOrder
import StarBase.Android.Forum.data.Boards
import StarBase.Android.Forum.data.Filters
import StarBase.Android.Forum.data.ForumRef
import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.data.ReadMark
import StarBase.Android.Forum.data.Reading
import StarBase.Android.Forum.data.Reminder
import StarBase.Android.Forum.data.Reminders
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.data.Visit
import StarBase.Android.Forum.data.WatchStatus
import StarBase.Android.Forum.ui.screens.drawTimeOf

/**
 * The device-local features, all of which are the app's own rather than a copy of
 * anything on linux.sb. There is no site page to check these against, so these
 * tests are the only thing that says they are right - the same footing as
 * [HistoryTest].
 *
 * Everything here takes its clock as a parameter, so it runs in plain JVM.
 */
class LocalFeaturesTest {

    // ---- 读到哪儿了 -----------------------------------------------------------

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun readMarkKeepsTheFurthestFloorReached() {
        var marks = Reading.mark(emptyList(), ReadMark(topicId = 7, seenFloor = 40, seenTotal = 40, at = 100L))
        // Scrolling back up does not un-read what was read.
        marks = Reading.mark(marks, ReadMark(topicId = 7, seenFloor = 12, seenTotal = 40, at = 200L))

        val mark = Reading.of(marks, 7)!!
        assertEquals(40, mark.seenFloor)
        assertEquals(200L, mark.at)
    }

    /**
     * The whole point of the feature: how many replies arrived while you were away
     * is the live count minus the stored one, never a cached list of posts.
     */
    @Test
    fun freshCountIsLiveMinusSeen() {
        val mark = ReadMark(topicId = 7, seenFloor = 32, seenTotal = 32)
        assertEquals(12, WatchStatus(mark, liveTotal = 44).fresh)
        assertEquals(0, WatchStatus(mark, liveTotal = 32).fresh)
        // A thread that lost posts to deletion must not report a negative.
        assertEquals(0, WatchStatus(mark, liveTotal = 20).fresh)
        // Not checked yet is not the same as nothing new.
        assertFalse(WatchStatus(mark).checked)
        assertEquals(0, WatchStatus(mark).fresh)
    }

    @Test
    fun catchUpMovesTheBaselineForwardOnly() {
        val marks = listOf(ReadMark(topicId = 7, seenFloor = 10, seenTotal = 30, at = 1L))
        assertEquals(44, Reading.catchUp(marks, 7, 44, 2L).single().seenTotal)
        // A smaller number is a stale page, not a correction.
        assertEquals(30, Reading.catchUp(marks, 7, 12, 2L).single().seenTotal)
        // A topic with no mark is not silently created.
        assertTrue(Reading.catchUp(emptyList(), 7, 44, 2L).isEmpty())
    }

    // ---- 追帖 -----------------------------------------------------------------

    @Test
    fun watchTogglesAndSetsItsOwnBaseline() {
        var marks = Reading.toggleWatch(emptyList(), 7, title = "帖子", total = 30, now = 100L)
        assertTrue(Reading.of(marks, 7)!!.watched)
        assertEquals(30, Reading.of(marks, 7)!!.seenTotal)
        assertEquals(listOf(7), Reading.watched(marks).map { it.topicId })

        marks = Reading.toggleWatch(marks, 7)
        assertFalse(Reading.of(marks, 7)!!.watched)
        assertTrue(Reading.watched(marks).isEmpty())
    }

    /**
     * Turning 追帖 on means "from here on". Without this the board would announce
     * every reply the thread already had as new.
     */
    @Test
    fun watchingAnAlreadyReadTopicStartsFromNow() {
        val read = Reading.mark(emptyList(), ReadMark(topicId = 7, seenFloor = 5, seenTotal = 5, at = 1L))
        val watched = Reading.toggleWatch(read, 7, total = 60, now = 2L)
        assertEquals(60, Reading.of(watched, 7)!!.seenTotal)
        assertEquals(0, WatchStatus(Reading.of(watched, 7)!!, liveTotal = 60).fresh)
    }

    @Test
    fun readingDoesNotChangeTheWatchFlag() {
        val watched = Reading.toggleWatch(emptyList(), 7, total = 10, now = 1L)
        val afterReading = Reading.mark(watched, ReadMark(topicId = 7, seenFloor = 3, seenTotal = 12, at = 2L))
        assertTrue(Reading.of(afterReading, 7)!!.watched)
    }

    @Test
    fun watchListIsCapped() {
        var marks = emptyList<ReadMark>()
        repeat(Reading.WATCH_CAP) { i ->
            marks = Reading.toggleWatch(marks, i + 1, total = 1, now = (i + 1).toLong())
        }
        assertEquals(Reading.WATCH_CAP, Reading.watched(marks).size)
        assertFalse(Reading.canWatchMore(marks))
    }

    /** Watched topics survive the trim; a position you chose to follow is not junk. */
    @Test
    fun trimKeepsWatchedMarks() {
        var marks = Reading.toggleWatch(emptyList(), 1, total = 1, now = 1L)
        // Push well past the cap with ordinary reads.
        for (i in 2..(Reading.CAP + 30)) {
            marks = Reading.mark(marks, ReadMark(topicId = i, seenTotal = 1, at = i.toLong()))
        }
        assertTrue(marks.size <= Reading.CAP)
        assertTrue("the watched topic fell off", Reading.of(marks, 1)?.watched == true)
    }

    @Test
    fun watchBoardPutsNewRepliesFirst() {
        val rows = listOf(
            WatchStatus(ReadMark(topicId = 1, seenTotal = 10, at = 900L), liveTotal = 10),
            WatchStatus(ReadMark(topicId = 2, seenTotal = 10, at = 100L), liveTotal = 13),
            WatchStatus(ReadMark(topicId = 3, seenTotal = 10, at = 800L), liveTotal = 40),
            WatchStatus(ReadMark(topicId = 4, seenTotal = 10, at = 950L), error = "读取失败")
        )
        // Most new first, then the quiet ones by how recently they were read.
        assertEquals(listOf(3, 2, 4, 1), Reading.rank(rows).map { it.mark.topicId })
    }

    @Test
    fun readMarksSurviveStorageRoundTrip() {
        val marks = listOf(
            ReadMark(topicId = 7, seenFloor = 32, seenTotal = 44, at = 1_700_000L, title = "带,逗号\"引号\"的标题", watched = true),
            ReadMark(topicId = 9, seenFloor = 0, seenTotal = 3, at = 1_600_000L)
        )
        assertEquals(marks, Reading.decode(Reading.encode(marks)))
    }

    @Test
    fun unreadableReadMarksDecodeToNothing() {
        assertTrue(Reading.decode("").isEmpty())
        assertTrue(Reading.decode("not json").isEmpty())
        assertTrue(Reading.decode("""[{"id":0}]""").isEmpty())
    }

    // ---- 本地屏蔽 -------------------------------------------------------------

    private fun card(id: Int, title: String, author: String = "某人", excerpt: String = "") =
        TopicCard(id = id, title = title, author = author, excerpt = excerpt)

    private fun post(id: String, author: String, body: String, opening: Boolean = false) = Post(
        id = id,
        author = author,
        blocks = listOf(LiveBlock(type = LiveBlock.Type.PARA, text = body)),
        isOpening = opening,
        floor = if (opening) 0 else 1
    )

    @Test
    fun keywordRuleHidesMatchingTopics() {
        val rules = listOf(BlockRule(value = "中转"))
        val result = Filters.topics(
            rules,
            listOf(card(1, "求一个中转推荐"), card(2, "正经技术帖"), card(3, "CN2 直连"))
        )
        assertEquals(listOf(2, 3), result.visible.map { it.id })
        assertEquals(1, result.hiddenCount)
        assertEquals("中转", result.hidden.single().rule.value)
    }

    @Test
    fun keywordMatchingIsCaseInsensitiveAndCoversExcerpts() {
        val rules = listOf(BlockRule(value = "vps"))
        val result = Filters.topics(
            rules,
            listOf(card(1, "便宜 VPS 一台"), card(2, "无关标题", excerpt = "其实是聊 vps 的"))
        )
        assertTrue(result.visible.isEmpty())
        assertEquals(2, result.hiddenCount)
    }

    /** A board named after a blocked word must not wipe out its own topics. */
    @Test
    fun keywordRuleIgnoresTheBoardName() {
        val rules = listOf(BlockRule(value = "灌水"))
        val card = TopicCard(id = 1, title = "认真提问", author = "某人", forumName = "灌水区")
        assertEquals(1, Filters.topics(rules, listOf(card)).visible.size)
    }

    @Test
    fun authorRuleMatchesWholeNamesOnly() {
        val rules = listOf(BlockRule(value = "张三", kind = BlockRule.Kind.AUTHOR))
        val result = Filters.topics(
            rules,
            listOf(card(1, "甲", author = "张三"), card(2, "乙", author = "张三丰"))
        )
        assertEquals(listOf(2), result.visible.map { it.id })
    }

    @Test
    fun disabledRulesDoNothing() {
        val rules = listOf(BlockRule(value = "中转", enabled = false))
        val cards = listOf(card(1, "求一个中转推荐"))
        val result = Filters.topics(rules, cards)
        assertEquals(1, result.visible.size)
        assertEquals(0, result.hiddenCount)
        // Nothing enabled returns the input as-is.
        assertEquals(cards, Filters.topics(emptyList(), cards).visible)
    }

    /**
     * Replies are folded in place, never dropped: a thread missing #12 reads as
     * though the site lost it, and the opening post can never be folded away.
     */
    @Test
    fun postsAreFoldedNotRemovedAndTheOpeningPostIsExempt() {
        val rules = listOf(BlockRule(value = "吵架王", kind = BlockRule.Kind.AUTHOR))
        val posts = listOf(
            post("op", "吵架王", "主楼正文", opening = true),
            post("c1", "吵架王", "又来了"),
            post("c2", "别人", "正常回帖")
        )
        val folded = Filters.posts(rules, posts)
        assertEquals(setOf("c1"), folded.keys)
        assertNull(folded["op"])
    }

    @Test
    fun keywordRuleFoldsOnReplyText() {
        val rules = listOf(BlockRule(value = "广告"))
        val folded = Filters.posts(rules, listOf(post("c1", "某人", "这是广告内容")))
        assertEquals("广告", folded["c1"]?.value)
    }

    @Test
    fun addingTheSameRuleTwiceUpdatesItInPlace() {
        var rules = Filters.add(emptyList(), BlockRule(value = "中转"))
        rules = Filters.add(rules, BlockRule(value = " 中转 ", fold = false))
        assertEquals(1, rules.size)
        assertFalse(rules.single().fold)
        // Same word, different kind, is a different rule.
        rules = Filters.add(rules, BlockRule(value = "中转", kind = BlockRule.Kind.AUTHOR))
        assertEquals(2, rules.size)
    }

    @Test
    fun blankRulesAreRefused() {
        assertTrue(Filters.add(emptyList(), BlockRule(value = "   ")).isEmpty())
    }

    @Test
    fun blockRulesSurviveStorageRoundTrip() {
        val rules = listOf(
            BlockRule(value = "中转", kind = BlockRule.Kind.KEYWORD, fold = true, enabled = true),
            BlockRule(value = "某人", kind = BlockRule.Kind.AUTHOR, fold = false, enabled = false)
        )
        assertEquals(rules, Filters.decode(Filters.encode(rules)))
        assertTrue(Filters.decode("nonsense").isEmpty())
    }

    // ---- 常去板块 / 板块排序 ---------------------------------------------------

    private val boards = listOf(
        ForumRef(id = 1, name = "技术"),
        ForumRef(id = 2, name = "灌水"),
        ForumRef(id = 3, name = "公告")
    )

    private val visits = listOf(
        Visit(id = 10, title = "a", forumName = "灌水", at = 5L, count = 4),
        Visit(id = 11, title = "b", forumName = "技术", at = 4L, count = 1),
        Visit(id = 12, title = "c", forumName = "灌水", at = 3L, count = 2)
    )

    @Test
    fun frequentOrderComesOutOfHistory() {
        val ranked = Boards.arrange(boards, BoardOrder.FREQUENT, emptyList(), visits)
        assertEquals(listOf(2, 1, 3), ranked.map { it.board.id })
        assertEquals(6, ranked.first().visits)
    }

    @Test
    fun siteOrderIsLeftAlone() {
        assertEquals(
            boards.map { it.id },
            Boards.arrange(boards, BoardOrder.SITE, emptyList(), visits).map { it.board.id }
        )
    }

    /** A pin is explicit and outranks a computed order. */
    @Test
    fun pinnedBoardsLeadInEveryMode() {
        val ranked = Boards.arrange(boards, BoardOrder.FREQUENT, listOf(3), visits)
        assertEquals(listOf(3, 2, 1), ranked.map { it.board.id })
        assertTrue(ranked.first().pinned)
    }

    @Test
    fun pinsKeepTheOrderTheyWerePinnedIn() {
        var pins = Boards.togglePin(emptyList(), 3)
        pins = Boards.togglePin(pins, 1)
        assertEquals(listOf(3, 1), pins)
        assertEquals(listOf(3, 1), Boards.arrange(boards, BoardOrder.SITE, pins, visits).take(2).map { it.board.id })

        pins = Boards.movePin(pins, 1, up = true)
        assertEquals(listOf(1, 3), pins)
        // Already at the edge: nothing moves rather than wrapping around.
        assertEquals(listOf(1, 3), Boards.movePin(pins, 1, up = true))
        assertEquals(listOf(1, 3), Boards.movePin(pins, 3, up = false))
        // A board that is not pinned cannot be moved.
        assertEquals(listOf(1, 3), Boards.movePin(pins, 2, up = true))

        pins = Boards.togglePin(pins, 1)
        assertEquals(listOf(3), pins)
    }

    @Test
    fun frequentStripIsEmptyWithoutHistory() {
        assertTrue(Boards.frequent(boards, emptyList()).isEmpty())
        // A board never visited is not in it either.
        assertEquals(listOf(2, 1), Boards.frequent(boards, visits).map { it.board.id })
    }

    @Test
    fun pinsSurviveStorageRoundTrip() {
        assertEquals(listOf(3, 1, 7), Boards.decodePins(Boards.encodePins(listOf(3, 1, 7))))
        assertTrue(Boards.decodePins("nope").isEmpty())
        // A duplicate id would draw the same board twice.
        assertEquals(listOf(3), Boards.decodePins("[3,3]"))
    }

    // ---- 本机提醒 -------------------------------------------------------------

    @Test
    fun dailyReminderRollsToTomorrowOncePassed() {
        val now = at(2026, 9, 1, 14, 0)
        val morning = Reminder(
            id = Reminders.CHECK_IN_ID,
            kind = Reminder.Kind.CHECK_IN,
            at = at(2026, 9, 1, 9, 30),
            label = "签到",
            daily = true
        )
        assertEquals(at(2026, 9, 2, 9, 30), Reminders.nextFire(morning, now))

        // Before the time, it is still today's.
        val early = at(2026, 9, 1, 8, 0)
        assertEquals(at(2026, 9, 1, 9, 30), Reminders.nextFire(morning, early))
    }

    /** A one-shot in the past reports its own time; moving it would hide the miss. */
    @Test
    fun oneShotReminderDoesNotReschedule() {
        val past = at(2026, 8, 20, 20, 0)
        val draw = Reminder(id = Reminders.drawId(5), kind = Reminder.Kind.DRAW, at = past, label = "开奖")
        assertEquals(past, Reminders.nextFire(draw, at(2026, 9, 1)))
        assertTrue(draw.spent(at(2026, 9, 1)))
        assertFalse(draw.copy(daily = true).spent(at(2026, 9, 1)))
    }

    @Test
    fun putReplacesAReminderForTheSameTopic() {
        val first = Reminder(id = Reminders.drawId(5), kind = Reminder.Kind.DRAW, at = 100L, label = "开奖")
        val moved = first.copy(at = 200L)
        val list = Reminders.put(Reminders.put(emptyList(), first), moved)
        assertEquals(1, list.size)
        assertEquals(200L, list.single().at)
    }

    @Test
    fun drawAndCheckInIdsNeverCollide() {
        assertTrue(Reminders.drawId(1) != Reminders.CHECK_IN_ID)
        assertTrue(Reminders.drawId(1) != Reminders.drawId(2))
    }

    @Test
    fun pruneDropsSpentOneShotsButKeepsDaily() {
        val now = at(2026, 9, 1)
        val list = listOf(
            Reminder(id = 2, kind = Reminder.Kind.DRAW, at = at(2026, 8, 1), label = "旧开奖"),
            Reminder(id = 3, kind = Reminder.Kind.DRAW, at = at(2026, 9, 5), label = "将来开奖"),
            Reminder(id = Reminders.CHECK_IN_ID, kind = Reminder.Kind.CHECK_IN, at = at(2026, 1, 1, 9, 30), label = "签到", daily = true)
        )
        val kept = Reminders.prune(list, now).map { it.id }
        assertTrue(3 in kept)
        assertTrue(Reminders.CHECK_IN_ID in kept)
        assertFalse(2 in kept)
    }

    @Test
    fun onlyASensibleDrawTimeIsWorthAnAlarm() {
        val now = at(2026, 9, 1, 12, 0)
        assertTrue(Reminders.drawWorthScheduling(at(2026, 9, 1, 20, 0), now))
        // Already gone.
        assertFalse(Reminders.drawWorthScheduling(at(2026, 8, 31, 20, 0), now))
        // Far enough out that the app will have been opened again anyway.
        assertFalse(Reminders.drawWorthScheduling(at(2026, 12, 1), now))
    }

    @Test
    fun upcomingSkipsDisabledAndPastOneShots() {
        val now = at(2026, 9, 1, 12, 0)
        val list = listOf(
            Reminder(id = 2, kind = Reminder.Kind.DRAW, at = at(2026, 9, 3), label = "三号"),
            Reminder(id = 3, kind = Reminder.Kind.DRAW, at = at(2026, 9, 2), label = "二号"),
            Reminder(id = 4, kind = Reminder.Kind.DRAW, at = at(2026, 8, 1), label = "过去"),
            Reminder(id = 5, kind = Reminder.Kind.DRAW, at = at(2026, 9, 4), label = "关掉的", enabled = false)
        )
        assertEquals(listOf(3, 2), Reminders.upcoming(list, now).map { it.id })
    }

    @Test
    fun whenTextNamesTodayAndTomorrow() {
        val now = at(2026, 9, 1, 12, 0)
        assertEquals("今天 20:00", Reminders.whenText(at(2026, 9, 1, 20, 0), now))
        assertEquals("明天 08:05", Reminders.whenText(at(2026, 9, 2, 8, 5), now))
        assertEquals("9 月 9 日 20:00", Reminders.whenText(at(2026, 9, 9, 20, 0), now))
    }

    @Test
    fun countdownTextSaysWhenItHasPassed() {
        val now = at(2026, 9, 1, 12, 0)
        assertEquals("已过期", Reminders.countdownText(at(2026, 9, 1, 11, 0), now))
        assertEquals("还有 30 分", Reminders.countdownText(at(2026, 9, 1, 12, 30), now))
        assertEquals("还有 2 小时 0 分", Reminders.countdownText(at(2026, 9, 1, 14, 0), now))
        assertEquals("还有 3 天", Reminders.countdownText(at(2026, 9, 4, 12, 0), now))
    }

    @Test
    fun remindersSurviveStorageRoundTrip() {
        val list = listOf(
            Reminder(id = 1, kind = Reminder.Kind.CHECK_IN, at = 1_700_000L, label = "签到", daily = true),
            Reminder(id = 1005, kind = Reminder.Kind.DRAW, at = 1_800_000L, label = "开奖：\"带引号\"", topicId = 5, enabled = false)
        )
        assertEquals(list, Reminders.decode(Reminders.encode(list)))
        assertTrue(Reminders.decode("[]").isEmpty())
        assertTrue(Reminders.decode("""[{"id":1}]""").isEmpty())
    }

    // ---- post body as text ----------------------------------------------------

    /** Images contribute nothing to a keyword match or to a shared card. */
    @Test
    fun plainTextSkipsImages() {
        val p = Post(
            id = "1",
            author = "某人",
            blocks = listOf(
                LiveBlock(type = LiveBlock.Type.PARA, text = "第一段"),
                LiveBlock(type = LiveBlock.Type.IMAGE, src = "https://linux.sb/x.png"),
                LiveBlock(type = LiveBlock.Type.PARA, text = "第二段")
            )
        )
        assertEquals("第一段\n第二段", p.plainText)
    }

    // ---- 开奖时间 ---------------------------------------------------------------

    /**
     * The arithmetic both sources of a draw time go through - the site's own 抽奖卡
     * and the prose fallback - so a month the calendar does not have is refused
     * once rather than twice. Refusing is the point: [Calendar] would happily roll
     * 13 月 over into next January and ring at a moment nobody was promised.
     */
    @Test
    fun epochAtRefusesPartsThatAreNotAMoment() {
        val at = Reminders.epochAt(2026, 9, 4, 9, 12)
        assertTrue("a real moment must come back", at > 0L)
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(9, cal.get(Calendar.MONTH) + 1)
        assertEquals(4, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(12, cal.get(Calendar.MINUTE))

        assertEquals(0L, Reminders.epochAt(2026, 13, 5, 20, 0))
        assertEquals(0L, Reminders.epochAt(2026, 0, 5, 20, 0))
        assertEquals(0L, Reminders.epochAt(2026, 9, 32, 20, 0))
        assertEquals(0L, Reminders.epochAt(2026, 9, 5, 25, 0))
        assertEquals(0L, Reminders.epochAt(2026, 9, 5, 20, 60))
        assertEquals(0L, Reminders.epochAt(2026, 2, 29, 20, 0))
        assertEquals(0L, Reminders.epochAt(2026, 4, 31, 20, 0))
        assertTrue(Reminders.epochAt(2028, 2, 29, 20, 0) > 0L)
        // A year that low is a parse gone wrong, not a draw.
        assertEquals(0L, Reminders.epochAt(1999, 9, 5, 20, 0))
    }

    /**
     * The prose reader, which is now the *fallback*: a site-made lottery prints its
     * time as a field on the 抽奖卡 (see ParseTest), and only a hand-written 抽奖帖
     * types it into the body. The shapes people actually type all have to land, and
     * anything else has to return 0 rather than a wrong date: an alarm at the wrong
     * hour is worse than none.
     */
    @Test
    fun drawTimeIsReadFromTheOpeningPostsProse() {
        fun opening(text: String) = Post(
            id = "1",
            author = "楼主",
            isOpening = true,
            blocks = listOf(LiveBlock(type = LiveBlock.Type.PARA, text = text))
        )

        fun at(y: Int, mo: Int, d: Int, h: Int, min: Int): Long =
            Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, min) }.timeInMillis

        assertEquals(
            at(2026, 9, 5, 20, 0),
            drawTimeOf(opening("参与方式随便回复，开奖时间：2026-09-05 20:00"))
        )
        // 年月日 and a slash date are the same sentence written two other ways.
        assertEquals(
            at(2026, 9, 5, 20, 30),
            drawTimeOf(opening("开奖时间 2026年9月5日 20:30 准时"))
        )
        assertEquals(at(2026, 9, 5, 0, 0), drawTimeOf(opening("开奖：2026/09/05")))

        // Nothing to go on, and nothing invented.
        assertEquals(0L, drawTimeOf(opening("这是个抽奖帖，满 50 楼开奖")))
        assertEquals(0L, drawTimeOf(opening("")))
        // A date the calendar does not have must not roll over into one it does.
        assertEquals(0L, drawTimeOf(opening("开奖时间：2026-13-05 20:00")))
        assertEquals(0L, drawTimeOf(opening("开奖时间：2026-09-05 25:00")))
        assertEquals(0L, drawTimeOf(opening("开奖时间：2026-02-30 20:00")))
    }

    /** An image-only opening post has no prose, so there is nothing to schedule. */
    @Test
    fun drawTimeIgnoresImages() {
        val p = Post(
            id = "1",
            author = "楼主",
            isOpening = true,
            blocks = listOf(LiveBlock(type = LiveBlock.Type.IMAGE, src = "https://linux.sb/开奖时间.png"))
        )
        assertEquals(0L, drawTimeOf(p))
    }
}
