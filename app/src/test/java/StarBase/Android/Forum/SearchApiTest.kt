package StarBase.Android.Forum

import StarBase.Android.Forum.net.SearchPages
import StarBase.Android.Forum.net.SearchReader
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.SiteException
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchApiTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/$name")).readText()
    private fun FormBody.fields(): Map<String, String> = (0 until size).associate { name(it) to value(it) }

    @Test
    fun fixturePaginationWinsOverResultLengthOrTheSummaryCount() {
        val page = SearchPages.parse(fixture("search-results.html"), 1)
        assertEquals(listOf(1201, 1188), page.topics.map { it.id })
        assertEquals(2, page.nextPage)
        val doc = Jsoup.parse(fixture("search-results.html"))
        doc.select(".search-page-pagination").remove()
        assertNull(SearchPages.parse(doc.outerHtml(), 1).nextPage)
    }

    @Test
    fun everyLoadMoreObtainsFreshTokensAndDiscoversTheActualPageField() {
        var cycle = 0
        val submissions = mutableListOf<Map<String, String>>()
        val reader = SearchReader(
            get = { url ->
                assertEquals(Site.SEARCH, url)
                cycle++
                Jsoup.parse(fixture("search-form.html")).apply {
                    select("input[name=q]").attr("name", "term")
                    select("input[name=_csrf]").attr("value", "entry-$cycle")
                }.outerHtml()
            },
            post = { url, body ->
                assertEquals(Site.SEARCH, url)
                val fields = body.fields()
                submissions += fields
                if (fields["page"] == "2") {
                    Jsoup.parse(fixture("search-results.html")).apply {
                        select(".active span").forEach { it.text("2") }
                        select(".search-page-pagination form").remove()
                    }.outerHtml()
                } else {
                    Jsoup.parse(fixture("search-results.html")).apply {
                        select("input[name=q]").attr("name", "term")
                        select("input[name=_csrf]").attr("value", "result-$cycle")
                        select("button[name=p]").attr("name", "page")
                    }.outerHtml()
                }
            }
        )
        repeat(2) {
            val page = reader.search("内核", 2)
            assertEquals(2, page.page)
            assertEquals(2, page.topics.size)
            assertNull(page.nextPage)
        }
        assertEquals(2, cycle)
        assertEquals(listOf("entry-1", "result-1", "entry-2", "result-2"), submissions.map { it["_csrf"] })
        assertEquals(listOf(null, "2", null, "2"), submissions.map { it["page"] })
        assertTrue(submissions.all { it["term"] == "内核" && it["type"] == "topic" })
        assertTrue(submissions.none { "p" in it })
    }

    @Test
    fun declaredPageFieldIsSentDirectlyAndGetSearchUsesStructuredQueryEncoding() {
        val entry = Jsoup.parse(fixture("search-form.html")).apply {
            select("form.search-page-form").attr("method", "get").attr("action", "/search?q=old&lang=zh")
            body().appendElement("div").addClass("search-page-pagination")
                .appendElement("input").attr("name", "page").attr("value", "1")
        }.outerHtml()
        val requests = mutableListOf<String>()
        val reader = SearchReader(
            get = { url ->
                requests += url
                if (requests.size == 1) entry else Jsoup.parse(fixture("search-results.html")).apply {
                    select(".search-page-pagination").remove()
                }.outerHtml()
            },
            post = { _, _ -> error("GET form must stay GET") }
        )
        reader.search("a & 中 + b", 3)
        assertEquals(2, requests.size)
        val url = requests.last().toHttpUrl()
        assertEquals("3", url.queryParameter("page"))
        assertEquals(listOf("a & 中 + b"), url.queryParameterValues("q"))
        assertEquals("zh", url.queryParameter("lang"))
    }

    @Test
    fun aNewQueryResetsAHiddenPageValueFromTheFreshForm() {
        val entry = Jsoup.parse(fixture("search-form.html")).apply {
            selectFirst("form.search-page-form")!!.appendElement("input")
                .attr("type", "hidden").attr("name", "page").attr("value", "9")
        }.outerHtml()
        val submissions = mutableListOf<Map<String, String>>()
        val reader = SearchReader({ entry }, { _, body ->
            submissions += body.fields()
            fixture("search-results.html")
        })
        reader.search("new query")
        assertEquals("1", submissions.single()["page"])
        assertEquals("new query", submissions.single()["q"])
    }

    @Test
    fun disabledNextButtonsEmptyPagesAndRedirectedOldPagesAreTerminal() {
        val doc = Jsoup.parse(fixture("search-results.html"))
        doc.select("button[name=p]").attr("disabled", "disabled")
        assertNull(SearchPages.parse(doc.outerHtml(), 1).nextPage)
        doc.select("button[name=p]").removeAttr("disabled")
        doc.select(".post-item").remove()
        assertNull(SearchPages.parse(doc.outerHtml(), 1).nextPage)
        val repeated = SearchPages.parse(fixture("search-results.html"), 2)
        assertEquals(2, repeated.page)
        assertTrue(repeated.topics.isEmpty())
        assertNull(repeated.nextPage)
    }

    @Test
    fun hiddenPageFieldWithADisabledSubmitDoesNotOfferLoadMore() {
        val doc = Jsoup.parse(fixture("search-results.html"))
        val form = requireNotNull(doc.selectFirst(".search-page-pagination form"))
        form.select("button").remove()
        form.appendElement("input").attr("type", "hidden").attr("name", "page").attr("value", "2")
        form.appendElement("button").attr("disabled", "disabled").text("Next")
        assertNull(SearchPages.parse(doc.outerHtml(), 1).nextPage)
    }

    @Test
    fun refusedOrUnrecognizedPagesCannotBecomeSuccessfulEmptySearches() {
        assertEquals(SiteException.Kind.AUTH, assertThrows(SiteException::class.java) {
            SearchPages.parse(fixture("search-guest.html"), 1)
        }.kind)
        assertEquals(SiteException.Kind.PARSE, assertThrows(SiteException::class.java) {
            SearchPages.parse("<html><body>upstream error</body></html>", 1)
        }.kind)
    }

    @Test
    fun cancellationAfterReadingTheFormPreventsSubmittingIt() {
        var checks = 0
        var submitted = false
        val reader = SearchReader(
            get = { fixture("search-form.html") },
            post = { _, _ -> submitted = true; fixture("search-results.html") },
            checkpoint = { if (++checks == 2) throw CancellationException("left search") }
        )
        assertThrows(CancellationException::class.java) { reader.search("内核") }
        assertFalse(submitted)
    }

    @Test
    fun paginationWithoutANextPageDoesNotSubmitAnInventedPageField() {
        var submits = 0
        val last = Jsoup.parse(fixture("search-results.html")).apply { select(".search-page-pagination").remove() }.outerHtml()
        val reader = SearchReader({ fixture("search-form.html") }, { _, _ -> submits++; last })
        val page = reader.search("内核", 2)
        assertEquals(1, submits)
        assertTrue(page.topics.isEmpty())
        assertNull(page.nextPage)
    }
}
