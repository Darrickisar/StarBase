package StarBase.Android.Forum

import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.data.ReaderPreferences
import StarBase.Android.Forum.data.ReaderThreadIndex
import StarBase.Android.Forum.data.isReaderAuthor
import StarBase.Android.Forum.data.nextReaderPage
import StarBase.Android.Forum.data.readerHeadings
import StarBase.Android.Forum.data.readerListKeys
import StarBase.Android.Forum.data.readerPostKey
import StarBase.Android.Forum.data.readerResourceUrl
import StarBase.Android.Forum.data.readerResources
import StarBase.Android.Forum.data.readerSections
import StarBase.Android.Forum.data.readerVisibleFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNavigationTest {
    private fun post(floor: Int, parent: Int = 0) = Post("post-$floor", "Author", floor = floor, parentFloor = parent)

    @Test
    fun aMissingParentKeepsItsLoadedBranchReachable() {
        val posts = listOf(post(4, 2), post(5, 4), post(6))
        val index = ReaderThreadIndex(null, posts)
        assertEquals(listOf(4, 6), index.rows(true, emptySet()).map { it.post.floor })
        val expanded = index.rows(true, setOf("post-4"))
        assertEquals(listOf(4, 5, 6), expanded.map { it.post.floor })
        assertEquals(listOf(0, 1, 0), expanded.map { it.depth })
        val context = index.contextFor(posts[1])
        assertEquals(listOf(4), context.posts.map { it.floor })
        assertEquals(2, context.missingFloor)
    }

    @Test
    fun cyclesAndSelfReferencesNeverHideAllRowsOrLoop() {
        val posts = listOf(post(1, 2), post(2, 1), post(3, 3), post(4, 2))
        val index = ReaderThreadIndex(null, posts)
        val rows = index.rows(true, posts.map { it.id }.toSet())
        assertEquals(setOf(1, 2, 3, 4), rows.map { it.post.floor }.toSet())
        assertEquals(4, rows.size)
        assertTrue(index.contextFor(posts[0]).hasCycle)
        assertTrue(index.contextFor(posts[2]).hasCycle)
    }

    @Test
    fun openingANestedBranchAlsoExposesItsAncestors() {
        val posts = listOf(post(1), post(2, 1), post(3, 2))
        val index = ReaderThreadIndex(null, posts)
        val expanded = index.ancestorIds(posts[1]) + posts[1].id
        assertEquals(listOf(1, 2, 3), index.rows(true, expanded).map { it.post.floor })
    }

    @Test
    fun veryDeepBranchesUseAnIterativeWalk() {
        val posts = (1..10_000).map { post(it, it - 1) }
        val index = ReaderThreadIndex(null, posts)
        assertEquals(10_000, index.rows(true, posts.map { it.id }.toSet()).size)
        val context = index.contextFor(posts.last())
        assertEquals(9_999, context.posts.size)
        assertFalse(context.hasCycle)
        assertNull(context.missingFloor)
    }

    @Test
    fun authorFilteringPrefersIdentityOverADuplicateDisplayName() {
        val opening = post(0).copy(authorId = 42)
        assertFalse(isReaderAuthor(post(1).copy(authorId = 99), opening))
        assertTrue(isReaderAuthor(post(2).copy(author = "Renamed", authorId = 42), opening))
        assertFalse(isReaderAuthor(post(1).copy(author = ""), post(0).copy(author = "")))
        assertFalse(isReaderAuthor(post(1), null))
    }

    @Test
    fun pageSearchVisitsHolesEvenAfterTheLastPageWasLoaded() {
        assertEquals(1, nextReaderPage(setOf(7), 7))
        assertEquals(3, nextReaderPage(setOf(1, 2, 7), 7))
        assertNull(nextReaderPage((1..7).toSet(), 7))
    }

    @Test
    fun outlineTargetsKeepBlockIdentityAndHeadingLevel() {
        val blocks = listOf(
            LiveBlock(LiveBlock.Type.PARA, "Intro"),
            LiveBlock(LiveBlock.Type.HEADING, "Repeated", headingLevel = 2),
            LiveBlock(LiveBlock.Type.IMAGE, src = "https://linux.sb/one.png"),
            LiveBlock(LiveBlock.Type.HEADING, "Repeated", headingLevel = 3),
            LiveBlock(LiveBlock.Type.VIDEO, src = "https://linux.sb/one.mp4")
        )
        val sections = readerSections(blocks)
        assertEquals(blocks, sections.flatMap { it.blocks })
        assertEquals(listOf(0, 1, 3), sections.map { it.blockIndex })
        val headings = readerHeadings(blocks)
        assertEquals(listOf(2, 3), headings.map { it.level })
        assertEquals(listOf("reader-section:1", "reader-section:3"), headings.map { it.key })
        val opening = post(0).copy(isOpening = true, blocks = blocks)
        val rows = ReaderThreadIndex(opening, listOf(post(9))).rows(false, emptySet())
        val keys = readerListKeys(opening, sections, rows)
        assertEquals("reader-post:post-9", keys.last())
        assertEquals(6, keys.indexOf(readerPostKey(post(9))))
        assertEquals(2, readerListKeys(null, emptyList(), rows).indexOf(readerPostKey(post(9))))
    }

    @Test
    fun prefetchedRowsHeadersAndSuppressedViewsDoNotAdvanceVisibleFloor() {
        val posts = listOf(post(2), post(50), post(900)).associateBy(::readerPostKey)
        assertEquals(0, readerVisibleFloor(listOf("head", "reader-section:0", "comments-header"), posts, true))
        assertEquals(2, readerVisibleFloor(listOf("body-actions", readerPostKey(post(2))), posts, true))
        assertEquals(0, readerVisibleFloor(listOf(readerPostKey(post(900))), posts, false))
        val unfoldedOnly = posts - readerPostKey(post(50))
        assertEquals(2, readerVisibleFloor(listOf(readerPostKey(post(2)), readerPostKey(post(50))), unfoldedOnly, true))
    }

    @Test
    fun resourceUrlsResolveStructurallyAndRejectExecutableSchemesAndCredentials() {
        assertEquals("https://linux.sb/files/a.zip?download=1", readerResourceUrl("/files/a.zip?download=1", 7).toString())
        assertEquals("https://cdn.example.org/a.png", readerResourceUrl("//cdn.example.org/a.png", 7).toString())
        assertEquals("https://linux.sb/topic/7?page=3", readerResourceUrl("?page=3", 7).toString())
        for (unsafe in listOf("javascript:alert(1)", "data:text/html,test", "file:///a", "intent://x",
            "https://user:pass@example.org", "https://example.org/\\evil", "https://example.org/a\nb")) {
            assertNull(unsafe, readerResourceUrl(unsafe, 7))
        }
    }

    @Test
    fun resourcesUseLoadedParsedBodiesDeduplicateAndTolerateInvalidLinkRanges() {
        val opening = post(0).copy(isOpening = true, blocks = listOf(
            LiveBlock(LiveBlock.Type.LINK, "Download", href = "/files/a.zip"),
            LiveBlock(LiveBlock.Type.PARA, "Link", links = listOf(LiveBlock.Link(-5, 999, "https://linux.sb/files/a.zip"))),
            LiveBlock(LiveBlock.Type.CODE, "https://not-a-parsed-link.example")
        ))
        val reply = post(20).copy(blocks = listOf(
            LiveBlock(LiveBlock.Type.IMAGE, src = "https://cdn.example.org/image.png"),
            LiveBlock(LiveBlock.Type.VIDEO, src = "https://cdn.example.org/video.mp4"),
            LiveBlock(LiveBlock.Type.LINK, "Unsafe", href = "javascript:alert(1)"),
            LiveBlock(LiveBlock.Type.PARA, "Short", links = listOf(LiveBlock.Link(99, -1, "/other")))
        ))
        val resources = readerResources(7, opening, listOf(reply))
        assertEquals(4, resources.size)
        assertTrue(resources.first().opening)
        assertEquals("Download", resources.first().label)
        assertEquals(20, resources[1].floor)
        assertEquals(1, readerResources(7, opening, emptyList()).size)
    }

    @Test
    fun importedPreferencesClampBadAndNonFiniteValues() {
        assertEquals(ReaderPreferences(0.85f, 1.4f), ReaderPreferences(-3f, 100f).normalized())
        assertEquals(ReaderPreferences(), ReaderPreferences(Float.NaN, Float.POSITIVE_INFINITY).normalized())
        assertEquals(ReaderPreferences(1.2f, 0.9f), ReaderPreferences(1.2f, 0.9f).normalized())
    }
}
