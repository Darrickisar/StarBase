package StarBase.Android.Forum

import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.data.TopicDetail
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.screens.TopicViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicReaderTest {
    private fun page(id: Int, number: Int, last: Int, vararg floors: Int) = TopicDetail(
        id = id, title = "Topic $id", opening = null, page = number, lastPage = last,
        comments = floors.map { Post("$id-$it", "Author", floor = it) }
    )

    @Test
    fun jumpsFindAnExactFloorOnAnotherPageEvenIfAHigherFloorIsAlreadyLoaded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val requests = mutableListOf<Int>()
        val vm = TopicViewModel { id, number ->
            requests += number
            if (number == 1) page(id, number, 2, 1, 50) else page(id, number, 2, 2, 3)
        }
        try {
            vm.open(1)
            runCurrent()
            vm.requestJumpTo(2)
            runCurrent()
            assertEquals(listOf(1, 2), requests)
            assertEquals(2, vm.jumpTo)
            assertEquals(listOf(1, 2, 3, 50), vm.comments.map { it.floor })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun missingFloorsDoNotSilentlyJumpToANeighbour() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = TopicViewModel { id, number -> page(id, number, 2, if (number == 1) 1 else 10) }
        try {
            vm.open(1)
            runCurrent()
            vm.requestJumpTo(7)
            runCurrent()
            assertEquals(0, vm.jumpTo)
            assertEquals(0, vm.locatingFloor)
            assertFalse(vm.hasMore)
            assertTrue(vm.navigationMessage.contains("#7"))
            assertTrue(vm.navigationMessage.contains("未找到"))
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aLongSearchStopsAtItsBudgetAndAnExplicitRetryContinues() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val requests = mutableListOf<Int>()
        val vm = TopicViewModel { id, number -> requests += number; page(id, number, 10, number) }
        try {
            vm.open(1)
            runCurrent()
            vm.requestJumpTo(10)
            runCurrent()
            assertEquals((1..9).toList(), requests)
            assertEquals(0, vm.jumpTo)
            assertEquals(0, vm.locatingFloor)
            assertTrue(vm.hasMore)
            vm.requestJumpTo(10)
            runCurrent()
            assertEquals((1..10).toList(), requests)
            assertEquals(10, vm.jumpTo)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun loadingParentContextDoesNotRequestAScroll() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = TopicViewModel { id, number -> page(id, number, 2, number) }
        try {
            vm.open(1)
            runCurrent()
            vm.loadUntilFloor(2)
            runCurrent()
            assertEquals(listOf(1, 2), vm.comments.map { it.floor })
            assertEquals(0, vm.jumpTo)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun switchingTopicsCancelsTheOldFloorSearch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val delayed = CompletableDeferred<TopicDetail>()
        val vm = TopicViewModel { id, number ->
            if (id == 1 && number == 2) delayed.await() else page(id, number, 2, 1)
        }
        try {
            vm.open(1)
            runCurrent()
            vm.requestJumpTo(2)
            runCurrent()
            assertEquals(2, vm.locatingFloor)
            vm.open(2)
            runCurrent()
            delayed.complete(page(1, 2, 2, 2))
            runCurrent()
            assertEquals(2, (vm.state as Load.Ready).value.id)
            assertEquals(listOf("2-1"), vm.comments.map { it.id })
            assertEquals(0, vm.locatingFloor)
            assertEquals(0, vm.jumpTo)
            assertFalse(vm.loadingMore)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun refreshingCancelsPagingAndDoesNotMixAnOldPageIntoTheNewSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val delayed = CompletableDeferred<TopicDetail>()
        var firstPageReads = 0
        val vm = TopicViewModel { id, number ->
            if (number == 2) delayed.await() else page(id, 1, 2, ++firstPageReads * 10)
        }
        try {
            vm.open(1)
            runCurrent()
            vm.loadMore()
            runCurrent()
            assertTrue(vm.loadingMore)
            vm.refresh()
            runCurrent()
            delayed.complete(page(1, 2, 2, 99))
            runCurrent()
            assertEquals(listOf(20), vm.comments.map { it.floor })
            assertFalse(vm.loadingMore)
            assertFalse(vm.refreshing)
            assertTrue(vm.hasMore)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aReplyLinkWithoutAPageFindsTheReplyAcrossPages() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = TopicViewModel { id, number ->
            page(id, number, 3, number).let { it.copy(comments = it.comments.map { post -> post.copy(replyId = number * 100) }) }
        }
        try {
            vm.open(1)
            runCurrent()
            vm.navigateAddress(1, 0, 300)
            runCurrent()
            assertEquals(3, vm.jumpTo)
            assertEquals(0, vm.locatingFloor)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun anAddressFetchThatFinishesAfterCancellationCannotPolluteAnotherTopic() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val delayed = CompletableDeferred<TopicDetail>()
        val vm = TopicViewModel { id, number ->
            if (id == 1 && number == 2) withContext(NonCancellable) { delayed.await() }
            else page(id, number, 2, 1)
        }
        try {
            vm.open(1)
            runCurrent()
            vm.navigateAddress(2, 2, 0)
            runCurrent()
            vm.open(2)
            runCurrent()
            delayed.complete(page(1, 2, 2, 2))
            runCurrent()
            assertEquals(listOf("2-1"), vm.comments.map { it.id })
            assertEquals(0, vm.jumpTo)
            assertEquals(0, vm.locatingFloor)
        } finally {
            delayed.complete(page(1, 2, 2, 2))
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
