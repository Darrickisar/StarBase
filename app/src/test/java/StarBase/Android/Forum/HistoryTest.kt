package StarBase.Android.Forum

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import StarBase.Android.Forum.data.History
import StarBase.Android.Forum.data.Visit

/**
 * 浏览历史 is the app's own feature, so unlike the rest of the app there is no
 * page to compare it against - which makes these the only tests that can say it
 * behaves. Everything here is pure: the clock is a parameter.
 */
class HistoryTest {

    /** Local midnight, so the day-boundary cases do not depend on the timezone. */
    private fun today(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val day = 24L * 60 * 60 * 1000

    private fun visit(id: Int, at: Long, title: String = "帖子 $id") =
        Visit(id = id, title = title, at = at)

    // ---- recording -----------------------------------------------------------

    @Test
    fun `a visit goes to the top`() {
        var list = History.record(emptyList(), visit(1, today(9)))
        list = History.record(list, visit(2, today(10)))
        assertEquals(listOf(2, 1), list.map { it.id })
    }

    /**
     * Re-opening a topic must not add a second row. A history that lists the same
     * thread eleven times is a worse record than one saying you read it 11 times.
     */
    @Test
    fun `re-reading counts up instead of duplicating`() {
        var list = History.record(emptyList(), visit(7, today(9)))
        list = History.record(list, visit(7, today(14)))
        list = History.record(list, visit(7, today(18)))
        assertEquals(1, list.size)
        assertEquals(3, list.first().count)
        assertEquals(today(18), list.first().at)
    }

    /**
     * The id is known when a topic is tapped; the title only when the page
     * answers. The second call must not blank the title it already has.
     */
    @Test
    fun `a blank title does not erase the one on record`() {
        var list = History.record(emptyList(), visit(3, today(9), title = "真正的标题"))
        list = History.record(list, Visit(id = 3, title = "", at = today(9, 1)))
        assertEquals("真正的标题", list.first().title)
    }

    @Test
    fun `the board name is kept the same way`() {
        var list = History.record(
            emptyList(),
            Visit(id = 4, title = "标题", forumName = "灌水区", at = today(9))
        )
        list = History.record(list, Visit(id = 4, title = "标题", at = today(11)))
        assertEquals("灌水区", list.first().forumName)
    }

    @Test
    fun `the oldest entries fall off at the cap`() {
        var list = emptyList<Visit>()
        for (i in 1..History.CAP + 20) {
            list = History.record(list, visit(i, today(0) + i.toLong()))
        }
        assertEquals(History.CAP, list.size)
        // Newest kept, oldest gone.
        assertEquals(History.CAP + 20, list.first().id)
        assertTrue(list.none { it.id == 1 })
    }

    @Test
    fun `an invalid id is not recorded`() {
        assertEquals(emptyList<Visit>(), History.record(emptyList(), visit(0, today(9))))
    }

    @Test
    fun `one entry can be dropped`() {
        val list = listOf(visit(1, today(9)), visit(2, today(10)))
        assertEquals(listOf(2), History.remove(list, 1).map { it.id })
        assertEquals(list, History.remove(list, 99))
    }

    // ---- grouping ------------------------------------------------------------

    @Test
    fun `visits group by calendar day newest first`() {
        val now = today(15)
        val days = History.byDay(
            listOf(
                visit(1, today(14)),
                visit(2, today(9)),
                visit(3, today(20) - day),
                visit(4, today(12) - 3 * day)
            ),
            now
        )
        assertEquals(listOf("今天", "昨天"), days.take(2).map { it.label })
        assertEquals(listOf(1, 2), days[0].visits.map { it.id })
        assertEquals(listOf(3), days[1].visits.map { it.id })
        assertEquals(3, days.size)
    }

    /**
     * Last night is 昨天 even when it was ten hours ago, because that is how a
     * reader remembers it - a plain 24-hour subtraction would call it 今天.
     */
    @Test
    fun `late last night is yesterday not twenty-something hours`() {
        val now = today(8)
        val lastNight = today(23) - day
        val days = History.byDay(listOf(visit(1, lastNight)), now)
        assertEquals("昨天", days.first().label)
    }

    @Test
    fun `days within the week are named and older ones dated`() {
        val now = today(12)
        val threeDaysAgo = History.byDay(listOf(visit(1, today(12) - 3 * day)), now)
        assertTrue(
            "expected a weekday name, got ${threeDaysAgo.first().label}",
            threeDaysAgo.first().label.startsWith("星期")
        )
        val longAgo = History.byDay(listOf(visit(1, today(12) - 40 * day)), now)
        assertTrue(
            "expected a date, got ${longAgo.first().label}",
            longAgo.first().label.contains("月") && longAgo.first().label.contains("日")
        )
    }

    // ---- search --------------------------------------------------------------

    @Test
    fun `search matches title board and id`() {
        val list = listOf(
            Visit(id = 17536, title = "抽奖爆率调整", forumName = "公告", at = today(9)),
            Visit(id = 42, title = "Linux 内核", forumName = "技术", at = today(10))
        )
        assertEquals(listOf(17536), History.search(list, "抽奖").map { it.id })
        assertEquals(listOf(42), History.search(list, "技术").map { it.id })
        // Case-insensitive on latin text.
        assertEquals(listOf(42), History.search(list, "linux").map { it.id })
        // A number is how you look for "the one that was #17536".
        assertEquals(listOf(17536), History.search(list, "17536").map { it.id })
        assertEquals(listOf(17536), History.search(list, "175").map { it.id })
        assertEquals(list, History.search(list, "   "))
        assertEquals(emptyList<Visit>(), History.search(list, "没有这个"))
    }

    // ---- relative time -------------------------------------------------------

    @Test
    fun `ago reads as a person would say it`() {
        val now = today(15)
        assertEquals("刚刚", History.ago(now - 30_000L, now))
        assertEquals("12 分钟前", History.ago(now - 12 * 60_000L, now))
        assertEquals("3 小时前", History.ago(now - 3 * 3_600_000L, now))
        assertTrue(History.ago(today(23) - day, now).startsWith("昨天 "))
        assertTrue(History.ago(now - 40 * day, now).contains("月"))
    }

    /** A clock moved backwards must not print "-3 分钟前". */
    @Test
    fun `a future stamp falls back to a clock time`() {
        val now = today(15)
        val ahead = History.ago(now + 3 * 3_600_000L, now)
        assertTrue("got $ahead", ahead.contains(":"))
        assertTrue("got $ahead", !ahead.contains("-"))
    }

    // ---- storage -------------------------------------------------------------

    @Test
    fun `a history survives a round trip`() {
        val list = listOf(
            Visit(id = 17536, title = "抽奖爆率调整", forumName = "公告", at = today(9), count = 4),
            Visit(id = 42, title = "Linux 内核", at = today(10))
        )
        assertEquals(list, History.decode(History.encode(list)))
    }

    /**
     * The old local 收藏 list packed titles with `` separators, on the
     * grounds that no title could contain one. JSON has no such assumption, and
     * this is the case that used to break it.
     */
    @Test
    fun `a title containing separators and quotes survives`() {
        val nasty = "标题里有\"引号\"和,逗号[还有括号]"
        val list = listOf(Visit(id = 5, title = nasty, at = today(9)))
        assertEquals(nasty, History.decode(History.encode(list)).first().title)
    }

    @Test
    fun `unreadable storage decodes to an empty history`() {
        assertEquals(emptyList<Visit>(), History.decode(""))
        assertEquals(emptyList<Visit>(), History.decode("not json"))
        assertEquals(emptyList<Visit>(), History.decode("{\"id\":1}"))
        // A row without a usable id is skipped, the rest are kept.
        assertEquals(
            listOf(9),
            History.decode("""[{"t":"no id"},{"id":9,"t":"ok","at":1}]""").map { it.id }
        )
    }

    @Test
    fun `a count is never below one`() {
        assertEquals(1, History.decode("""[{"id":3,"t":"x","at":1}]""").first().count)
    }
}
