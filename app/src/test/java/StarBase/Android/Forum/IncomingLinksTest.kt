package StarBase.Android.Forum

import StarBase.Android.Forum.ui.IncomingLinks
import org.junit.Assert.*
import org.junit.Test

class IncomingLinksTest {
    @Test fun sharedSiteLinksKeepReplyAndPage() {
        val address = IncomingLinks.topic("https://linux.sb/topic/20390?p=4#post-1848")!!
        assertEquals(20390, address.id)
        assertEquals(4, address.page)
        assertEquals(1848, address.replyId)
    }

    @Test fun unrelatedHostsCannotOpenInternalTopics() {
        assertNull(IncomingLinks.topic("https://example.com/topic/20390"))
        assertNull(IncomingLinks.topic("https://linux.sb.evil.example/topic/20390"))
        assertNull(IncomingLinks.topic("https://user:password@linux.sb/topic/20390"))
        assertNull(IncomingLinks.siteUrl("javascript:alert(1)"))
    }

    @Test fun sharedTextBecomesDraftAndDoesNotFetchLinksInsideText() {
        assertEquals("A useful https://example.com/article", IncomingLinks.shared("A useful https://example.com/article")!!.text)
        assertEquals("https://linux.sb/topic/1", IncomingLinks.shared("http://linux.sb/topic/1")!!.url)
    }

    @Test fun inputsAreBoundedAndInvalidTopicIdsRejected() {
        assertEquals(IncomingLinks.TEXT_LIMIT, IncomingLinks.shared("a".repeat(200_000))!!.text.length)
        assertNull(IncomingLinks.topic("https://linux.sb/topic/-1"))
        assertNull(IncomingLinks.topic("https://linux.sb/topic/999999999999999"))
    }

    @Test fun selectedTextSearchKeepsCommandsAndDoesNotNavigateUrls() {
        assertEquals("apt install --fix-broken", IncomingLinks.selected(" \napt install --fix-broken\t ")!!.search)
        val link = IncomingLinks.selected("https://linux.sb/topic/1")!!
        assertEquals("https://linux.sb/topic/1", link.search)
        assertEquals("", link.url)
        assertEquals("error EACCES", IncomingLinks.selected("error\n\tEACCES")!!.search)
    }

    @Test fun selectionRejectsEmptyInputAndBoundsLongLogs() {
        assertNull(IncomingLinks.selected(" \n\t\u0000 "))
        assertEquals(IncomingLinks.SEARCH_LIMIT, IncomingLinks.selected("x".repeat(20_000))!!.search.length)
        assertEquals("error failed", IncomingLinks.selected("error\u0000 failed")!!.search)
    }
}
