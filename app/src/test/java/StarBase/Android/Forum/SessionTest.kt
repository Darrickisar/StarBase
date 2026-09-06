package StarBase.Android.Forum

import StarBase.Android.Forum.data.Me
import StarBase.Android.Forum.data.NotifyState
import StarBase.Android.Forum.ui.SessionViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    @Test
    fun invalidationDiscardsAnIdentityResponseAlreadyInFlight() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<Me?>()
        val vm = SessionViewModel({ pending.await() }, { NotifyState(7, 8, true) }, { true }, { 1000L })
        try {
            vm.refresh()
            runCurrent()
            vm.invalidate()
            pending.complete(Me("Old member", 1))
            runCurrent()
            assertNull(vm.me)
            assertEquals(0, vm.unreadNotifications)
            assertEquals(0, vm.unreadMessages)
            assertFalse(vm.checking)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aNewSessionCanRefreshWhileAnOldBadgeResponseIsPending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val oldBadges = CompletableDeferred<NotifyState>()
        var identities = 0
        var badgeRequests = 0
        val vm = SessionViewModel(
            { identities++; Me("Member $identities", identities) },
            { if (++badgeRequests == 1) oldBadges.await() else NotifyState(1, 2, true) },
            { true },
            { 1000L }
        )
        try {
            vm.refresh()
            runCurrent()
            vm.invalidate()
            vm.refresh()
            runCurrent()
            oldBadges.complete(NotifyState(99, 99, true))
            runCurrent()
            assertEquals(2, vm.me?.id)
            assertEquals(1, vm.unreadNotifications)
            assertEquals(2, vm.unreadMessages)
            assertFalse(vm.checking)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
