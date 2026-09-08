package StarBase.Android.Forum

import StarBase.Android.Forum.data.NotificationFilter
import StarBase.Android.Forum.data.NotificationGroups
import StarBase.Android.Forum.data.NotifyItem
import StarBase.Android.Forum.net.Parse
import StarBase.Android.Forum.net.Site
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGroupsTest {
    @Test
    fun fixtureFiltersUseOnlyTheSitesUnreadMarkerAndKindBadge() {
        val doc = Jsoup.parse(requireNotNull(javaClass.getResource("/notifications-tab.html")).readText())
        doc.select(".notification-item")[0].addClass("unread")
        val rows = Parse.notifications(doc.outerHtml())
        assertEquals(listOf(rows[0]), NotificationGroups.group(rows, NotificationFilter.UNREAD).flatMap { it.items })
        assertEquals(listOf(rows[1]), NotificationGroups.group(rows, NotificationFilter.MENTIONS).flatMap { it.items })
        assertTrue(rows[0].unread)
        assertFalse(rows[1].unread)
        assertEquals(18587, NotificationGroups.group(rows, NotificationFilter.MENTIONS).single().topicId)
    }

    @Test
    fun sameTopicGroupsAcrossPagesAndReplyAnchorsWithoutLosingOriginalTargets() {
        val first = NotifyItem("latest", href = "${Site.BASE}/topic/42?p=3#post-8", unread = true)
        val other = NotifyItem("other", href = "/topic/99")
        val earlier = NotifyItem("earlier", href = "/topic/42#post-2")
        val groups = NotificationGroups.group(listOf(first, other, earlier))
        assertEquals(listOf(42, 99), groups.map { it.topicId })
        assertEquals(listOf(first, earlier), groups.first().items)
        assertEquals(1, groups.first().unreadCount)
        assertEquals("${Site.BASE}/topic/42?p=3#post-8", groups.first().items.first().href)
    }

    @Test
    fun missingTopicsAndOffSiteLookalikesRemainIndependentEvents() {
        val rows = listOf(
            NotifyItem("a", href = "/user/7"),
            NotifyItem("b", href = "/user/7"),
            NotifyItem("c"),
            NotifyItem("d", href = "https://example.com/topic/12"),
            NotifyItem("e", href = "/topic/12/extra"),
            NotifyItem("f", href = "/topic/0")
        )
        val groups = NotificationGroups.group(rows)
        assertEquals(rows.size, groups.size)
        assertEquals(rows.size, groups.map { it.key }.toSet().size)
        assertTrue(groups.all { it.topicId == null })
        assertNull(NotificationGroups.topicId("/user/2?next=/topic/12"))
    }

    @Test
    fun mentioningInProseIsNotASiteMentionAndFilteringKeepsStableGroupKeys() {
        val mention = NotifyItem("普通内容", href = "/topic/8", kind = "提及")
        val prose = NotifyItem("@你，提到了你", href = "/topic/9", kind = "通知", unread = true)
        val noTopic = NotifyItem("event", unread = true)
        val rows = listOf(mention, prose, noTopic)
        val mentions = NotificationGroups.group(rows, NotificationFilter.MENTIONS)
        assertEquals(listOf(mention), mentions.flatMap { it.items })
        val all = NotificationGroups.group(rows)
        val unread = NotificationGroups.group(rows, NotificationFilter.UNREAD)
        assertEquals(all.last().key, unread.last().key)
        assertEquals(rows, NotificationGroups.group(rows).flatMap { it.items })
    }
}
