package StarBase.Android.Forum

import StarBase.Android.Forum.data.NotificationFilter
import StarBase.Android.Forum.data.NotifyItem
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.screens.NotifyViewModel
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
class NotificationsTest {
    private val models = mutableListOf<NotifyViewModel>()
    private fun model(fetch: suspend (Int) -> List<NotifyItem>) =
        NotifyViewModel(fetch, now = { 1000L }).also { models += it }
    private fun notificationTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally {
            models.forEach { it.viewModelScope.cancel() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun changingAccountsDoesNotBlockNewRequestsOrLetAnOldFinallyReleaseTheirGuard() = notificationTest {
        val old = CompletableDeferred<List<NotifyItem>>()
        val fresh = CompletableDeferred<List<NotifyItem>>()
        val calls = mutableListOf<Int>()
        val vm = model { id ->
            calls += id
            if (id == 1) withContext(NonCancellable) { old.await() } else fresh.await()
        }
        try {
            vm.bind(1)
            vm.load()
            runCurrent()
            vm.bind(2)
            vm.load()
            runCurrent()
            old.complete(listOf(NotifyItem("old", unread = true)))
            runCurrent()
            assertTrue(vm.state is Load.Loading)
            vm.load(force = true)
            runCurrent()
            assertEquals(listOf(1, 2), calls)
            fresh.complete(listOf(NotifyItem("new")))
            runCurrent()
            assertEquals("new", vm.groups.single().items.single().text)
            assertFalse(vm.refreshing)
            assertNull(vm.refreshError)
        } finally {
            old.complete(emptyList())
            fresh.complete(emptyList())
        }
    }

    @Test
    fun signingOutClearsRowsFiltersExpansionAndAnyPendingResponse() = notificationTest {
        val old = CompletableDeferred<List<NotifyItem>>()
        var calls = 0
        val vm = model {
            calls++
            withContext(NonCancellable) { old.await() }
        }
        vm.bind(1)
        vm.selectFilter(NotificationFilter.UNREAD)
        vm.toggleGroup("topic:1")
        vm.load()
        runCurrent()
        vm.bind(0)
        vm.load(force = true)
        old.complete(listOf(NotifyItem("old", href = "/topic/1", unread = true)))
        runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.groups.isEmpty())
        assertTrue(vm.expandedKeys.isEmpty())
        assertEquals(NotificationFilter.ALL, vm.filter)
        assertTrue(vm.state is Load.Loading)
    }

    @Test
    fun filtersAndExpansionNeverChangeServerUnreadStateAndReturnAlwaysRefetches() = notificationTest {
        var calls = 0
        val original = NotifyItem("mention", href = "/topic/7#post-3", kind = "提及", unread = true)
        val vm = model {
            calls++
            listOf(original.copy(unread = calls == 1), NotifyItem("reply", href = "/topic/7#post-2"))
        }
        vm.bind(1)
        vm.openOrRefresh()
        runCurrent()
        vm.toggleGroup("topic:7")
        vm.selectFilter(NotificationFilter.MENTIONS)
        assertTrue(vm.groups.single().items.single().unread)
        vm.selectFilter(NotificationFilter.UNREAD)
        assertEquals(1, vm.groups.single().unreadCount)
        assertEquals(1, calls)
        vm.openOrRefresh()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(vm.groups.isEmpty())
        vm.selectFilter(NotificationFilter.ALL)
        assertEquals(0, vm.groups.single().unreadCount)
        assertTrue("topic:7" in vm.expandedKeys)
    }

    @Test
    fun failedRefreshRetainsRowsAndErrorUntilTheSiteSucceedsAgain() = notificationTest {
        var calls = 0
        val vm = model {
            when (++calls) {
                1 -> listOf(NotifyItem("current", href = "/topic/7", unread = true))
                2 -> throw SiteException("offline", SiteException.Kind.SERVER)
                else -> emptyList()
            }
        }
        vm.bind(1)
        vm.load()
        runCurrent()
        vm.toggleGroup("topic:7")
        vm.load(force = true)
        runCurrent()
        assertEquals("current", vm.groups.single().items.single().text)
        assertEquals(1, vm.groups.single().unreadCount)
        assertNotNull(vm.refreshError)
        assertFalse(vm.refreshing)
        vm.load(force = true)
        runCurrent()
        assertNull(vm.refreshError)
        assertTrue(vm.groups.isEmpty())
        assertTrue(vm.expandedKeys.isEmpty())
    }

    @Test
    fun initialErrorsAndEmptyListsBothAllowARealRetry() = notificationTest {
        var calls = 0
        val vm = model {
            when (++calls) {
                1 -> throw SiteException("login", SiteException.Kind.AUTH)
                2 -> emptyList()
                else -> listOf(NotifyItem("arrived"))
            }
        }
        vm.bind(1)
        vm.load()
        runCurrent()
        assertEquals(SiteException.Kind.AUTH, (vm.state as Load.Failed).kind)
        vm.load(force = true)
        runCurrent()
        assertTrue(vm.state is Load.Ready)
        assertTrue(vm.groups.isEmpty())
        vm.load(force = true)
        runCurrent()
        assertEquals("arrived", vm.groups.single().items.single().text)
    }
}
