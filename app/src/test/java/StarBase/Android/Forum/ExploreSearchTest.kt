package StarBase.Android.Forum

import StarBase.Android.Forum.data.SearchStore
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.net.SearchPage
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.screens.ExploreViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreSearchTest {
    private val models = mutableListOf<ExploreViewModel>()

    private fun model(fetch: suspend (String, Int) -> SearchPage) = ExploreViewModel(fetch).also { models += it }
    private fun topic(id: Int) = TopicCard(id, "topic $id", "author")
    private fun ExploreViewModel.ids(): List<Int> = (results as? Load.Ready)?.value.orEmpty().map { it.id }
    private fun searchTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally {
            models.forEach { it.viewModelScope.cancel() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun externalQueryWaitsForLoginAndDoesNotRepeatWhenScreenReturns() = searchTest {
        val calls = mutableListOf<String>()
        val vm = model { q, _ -> calls += q; SearchPage(emptyList()) }
        vm.bind(0)
        vm.receiveQuery("EACCES", 42L)
        runCurrent()
        assertEquals("EACCES", vm.query)
        assertTrue(calls.isEmpty())
        vm.bind(7)
        vm.receiveQuery("EACCES", 42L)
        runCurrent()
        assertEquals(listOf("EACCES"), calls)
        vm.updateQuery("ENOENT")
        vm.receiveQuery("EACCES", 42L)
        runCurrent()
        assertEquals("ENOENT", vm.query)
        assertEquals(listOf("EACCES"), calls)
        vm.receiveQuery("EACCES", 43L)
        runCurrent()
        assertEquals(listOf("EACCES", "EACCES"), calls)
    }

    @Test
    fun signingInSearchesTheCorrectedGuestQuery() = searchTest {
        val calls = mutableListOf<String>()
        val vm = model { q, _ -> calls += q; SearchPage(emptyList()) }
        vm.bind(0)
        vm.receiveQuery("EACCESS", 42L)
        vm.updateQuery("EACCES corrected")
        vm.bind(7)
        vm.receiveQuery("EACCESS", 42L)
        runCurrent()
        assertEquals("EACCES corrected", vm.query)
        assertEquals(listOf("EACCES corrected"), calls)
    }

    @Test
    fun sameAccountLoginResumesAnAuthFailureOnceAndCancelledLoginDoesNotRetry() = searchTest {
        var authenticated = false
        val calls = mutableListOf<String>()
        val vm = model { q, _ ->
            calls += q
            if (!authenticated) throw SiteException("Sign in", SiteException.Kind.AUTH)
            SearchPage(listOf(topic(9)))
        }
        vm.bind(7)
        vm.receiveQuery("original", 42L)
        runCurrent()
        vm.prepareLogin()
        vm.bind(7)
        vm.receiveQuery("original", 42L)
        vm.resumeAfterLogin(0)
        runCurrent()
        assertEquals(listOf("original"), calls)
        assertEquals(SiteException.Kind.AUTH, (vm.results as Load.Failed).kind)
        authenticated = true
        vm.resumeAfterLogin(1)
        runCurrent()
        assertEquals(listOf(9), vm.ids())
        vm.resumeAfterLogin(1)
        vm.receiveQuery("original", 42L)
        runCurrent()
        assertEquals(listOf("original", "original"), calls)
    }

    @Test
    fun loginResumesTheFailedNextPageWithoutReplacingLoadedResults() = searchTest {
        var authenticated = false
        val calls = mutableListOf<Int>()
        val vm = model { _, page ->
            calls += page
            if (page > 1 && !authenticated) throw SiteException("Sign in", SiteException.Kind.AUTH)
            SearchPage(listOf(topic(page)), page, if (page == 1) 2 else null)
        }
        vm.bind(7)
        vm.runSearch("query")
        runCurrent()
        vm.loadMore()
        runCurrent()
        vm.prepareLogin()
        authenticated = true
        vm.resumeAfterLogin(1)
        runCurrent()
        assertEquals(listOf(1, 2, 2), calls)
        assertEquals(listOf(1, 2), vm.ids())
        assertNull(vm.moreError)
    }

    @Test
    fun discoveryLoginPreservesTypedTextButChangingAnExistingAccountClearsIt() = searchTest {
        val calls = mutableListOf<String>()
        val vm = model { q, _ -> calls += q; SearchPage(emptyList()) }
        vm.bind(0)
        vm.updateQuery("corrected discovery search")
        vm.prepareLogin()
        vm.bind(7)
        vm.resumeAfterLogin(1)
        runCurrent()
        assertEquals(listOf("corrected discovery search"), calls)
        vm.receiveQuery("private query", 42L)
        runCurrent()
        vm.prepareLogin()
        vm.bind(8)
        vm.receiveQuery("private query", 42L)
        vm.resumeAfterLogin(2)
        runCurrent()
        assertEquals("", vm.query)
        assertNull(vm.results)
        assertEquals(listOf("corrected discovery search", "private query"), calls)
    }

    @Test
    fun queryChangesResetImmediatelyAndLateOldResponsesCannotClearTheNewRequest() = searchTest {
        val old = CompletableDeferred<SearchPage>()
        val fresh = CompletableDeferred<SearchPage>()
        val calls = mutableListOf<Pair<String, Int>>()
        val vm = model { q, p ->
            calls += q to p
            if (q == "old") withContext(NonCancellable) { old.await() } else fresh.await()
        }
        try {
            vm.runSearch("old")
            runCurrent()
            vm.updateQuery("new")
            assertNull(vm.results)
            assertNull(vm.nextPage)
            vm.search()
            runCurrent()
            old.complete(SearchPage(listOf(topic(1)), 1, 2))
            runCurrent()
            assertTrue(vm.results is Load.Loading)
            fresh.complete(SearchPage(listOf(topic(2)), 1))
            runCurrent()
            assertEquals(listOf(2), vm.ids())
            assertEquals(listOf("old" to 1, "new" to 1), calls)
            assertNull(vm.refreshError)
            assertFalse(vm.refreshing)
        } finally {
            old.complete(SearchPage(emptyList()))
            fresh.complete(SearchPage(emptyList()))
        }
    }

    @Test
    fun clearingDuringSearchPreventsTheResultsFromReappearing() = searchTest {
        val pending = CompletableDeferred<SearchPage>()
        val vm = model { _, _ -> withContext(NonCancellable) { pending.await() } }
        vm.runSearch("old")
        runCurrent()
        vm.clearSearch()
        pending.complete(SearchPage(listOf(topic(1)), 1, 2))
        runCurrent()
        assertEquals("", vm.query)
        assertNull(vm.results)
        assertNull(vm.nextPage)
        assertNull(vm.refreshError)
        assertFalse(vm.loadingMore)
    }

    @Test
    fun paginationRetriesTheFailedPageDeduplicatesAndStopsWhenTheServerRepeatsIt() = searchTest {
        val calls = mutableListOf<Int>()
        var failed = false
        val vm = model { _, page ->
            calls += page
            when (page) {
                1 -> SearchPage(listOf(topic(1), topic(2)), 1, 2)
                2 -> if (!failed) {
                    failed = true
                    throw SiteException("retry", SiteException.Kind.SERVER)
                } else SearchPage(listOf(topic(2), topic(3)), 2, 3)
                else -> SearchPage(listOf(topic(3)), page, page + 1)
            }
        }
        vm.runSearch("query")
        runCurrent()
        vm.loadMore()
        vm.loadMore()
        runCurrent()
        assertEquals(listOf(1, 2), calls)
        assertEquals(listOf(1, 2), vm.ids())
        assertEquals(2, vm.nextPage)
        assertNotNull(vm.moreError)
        vm.loadMore()
        runCurrent()
        assertEquals(listOf(1, 2, 3), vm.ids())
        assertNull(vm.moreError)
        vm.loadMore()
        runCurrent()
        assertNull(vm.nextPage)
        vm.loadMore()
        runCurrent()
        assertEquals(listOf(1, 2, 2, 3), calls)
        assertEquals(listOf(1, 2, 3), vm.ids())
    }

    @Test
    fun emptyOrMismatchedPagesStopAndKeepTheExistingResults() = searchTest {
        listOf(SearchPage(emptyList(), 2, 3), SearchPage(listOf(topic(8)), 1, 2)).forEach { last ->
            val vm = model { _, page -> if (page == 1) SearchPage(listOf(topic(1)), 1, 2) else last }
            vm.runSearch("query")
            runCurrent()
            vm.loadMore()
            runCurrent()
            assertEquals(listOf(1), vm.ids())
            assertNull(vm.nextPage)
        }
    }

    @Test
    fun refreshCancelsAPendingAppendAndReplacesTheWholeResultSet() = searchTest {
        var firstPageReads = 0
        val more = CompletableDeferred<SearchPage>()
        val vm = model { _, page ->
            if (page == 1) {
                firstPageReads++
                if (firstPageReads == 1) SearchPage(listOf(topic(1)), 1, 2) else SearchPage(listOf(topic(9)))
            } else withContext(NonCancellable) { more.await() }
        }
        vm.runSearch("query")
        runCurrent()
        vm.loadMore()
        runCurrent()
        vm.refreshVisible()
        runCurrent()
        more.complete(SearchPage(listOf(topic(2)), 2, 3))
        runCurrent()
        assertEquals(listOf(9), vm.ids())
        assertNull(vm.nextPage)
        assertFalse(vm.loadingMore)
        assertFalse(vm.refreshing)
        assertNull(vm.moreError)
    }

    @Test
    fun failedRefreshKeepsTheListAndSuccessfulRetryResetsPagination() = searchTest {
        var reads = 0
        val vm = model { _, _ ->
            when (++reads) {
                1 -> SearchPage(listOf(topic(1)), 1, 2)
                2 -> throw SiteException("offline", SiteException.Kind.SERVER)
                else -> SearchPage(listOf(topic(7)))
            }
        }
        vm.runSearch("query")
        runCurrent()
        vm.refreshVisible()
        runCurrent()
        assertEquals(listOf(1), vm.ids())
        assertNotNull(vm.refreshError)
        assertEquals(2, vm.nextPage)
        vm.refreshVisible()
        runCurrent()
        assertEquals(listOf(7), vm.ids())
        assertNull(vm.refreshError)
        assertNull(vm.nextPage)
    }

    @Test
    fun switchingAccountsClearsVisibleSearchesAndRejectsEvenAnOldResponseForTheSameAccount() = searchTest {
        val disk = mutableMapOf<String, String>()
        val storage = SearchStore({ disk[it].orEmpty() }, { key, value -> disk[key] = value })
        val old = CompletableDeferred<SearchPage>()
        var calls = 0
        val vm = model { _, _ ->
            if (++calls == 1) withContext(NonCancellable) { old.await() } else SearchPage(listOf(topic(8)))
        }
        try {
            vm.bind(1, storage)
            vm.runSearch("shared")
            vm.saveSearch()
            vm.pinSearch("shared")
            runCurrent()
            vm.bind(2)
            assertEquals("", vm.query)
            assertNull(vm.results)
            assertTrue(vm.library.history.isEmpty())
            assertTrue(vm.library.saved.isEmpty())
            vm.bind(1)
            assertTrue(vm.library.saved.single().pinned)
            vm.runSearch("shared")
            runCurrent()
            old.complete(SearchPage(listOf(topic(1)), 1, 2))
            runCurrent()
            assertEquals(listOf(8), vm.ids())
            assertNull(vm.nextPage)
            vm.runSearch(vm.library.saved.single().query)
            runCurrent()
            assertEquals(3, calls)
            assertEquals(1, vm.library.history.size)
        } finally { old.complete(SearchPage(emptyList())) }
    }
}
