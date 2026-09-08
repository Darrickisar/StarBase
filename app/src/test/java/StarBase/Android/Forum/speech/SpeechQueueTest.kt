package StarBase.Android.Forum.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechQueueTest {
    private fun topic(id: Long, count: Int = 2) = SpeechTopic(id, id.toInt(), "Topic $id", SpeechMode.OPENING,
        List(count) { SpeechSentence("Sentence $it.", "1", "Author", 1) }, 0, 0)

    @Test fun initialAndSameAccountBindingPreservePositionButAccountChangeClears() {
        val queue = SpeechQueue()
        assertFalse(queue.bindAccount(7))
        queue.enqueue(topic(1))
        queue.next()
        val current = queue.position
        assertFalse(queue.bindAccount(7))
        assertEquals(current, queue.position)
        assertTrue(queue.bindAccount(8))
        assertTrue(queue.topics.isEmpty())
        assertNull(queue.sentence)
        assertFalse(queue.complete(current!!))
        assertEquals(8, queue.accountId)
    }

    @Test fun logoutIsAnAccountChange() {
        val queue = SpeechQueue()
        queue.bindAccount(9)
        queue.enqueue(topic(1))
        assertTrue(queue.bindAccount(0))
        assertNull(queue.current)
    }

    @Test fun pauseInvalidatesCallbacksAndResumesTheSameSentence() {
        val queue = SpeechQueue()
        queue.enqueue(topic(1))
        val speaking = queue.position!!
        queue.invalidate()
        assertFalse(queue.complete(speaking))
        assertEquals(0, queue.sentenceIndex)
        assertTrue(queue.complete(queue.position!!))
        assertEquals(1, queue.sentenceIndex)
    }

    @Test fun completedQueueReleasesEveryTopicAndDoesNotLoop() {
        val queue = SpeechQueue()
        queue.enqueue(topic(1))
        queue.enqueue(topic(2))
        repeat(4) { assertTrue(queue.complete(queue.position!!)) }
        assertNull(queue.position)
        assertTrue(queue.topics.isEmpty())
        assertFalse(queue.hasNext)
        assertFalse(queue.hasPrevious)
    }

    @Test fun appendingAndRemovingOtherTopicsDoNotInterruptTheCurrentUtterance() {
        val queue = SpeechQueue()
        queue.enqueue(topic(1))
        val speaking = queue.position!!
        queue.enqueue(topic(2))
        queue.enqueue(topic(3))
        queue.remove(2)
        assertTrue(queue.complete(speaking))
        assertEquals(listOf(1L, 3L), queue.topics.map { it.id })
    }

    @Test fun removalBeforeCurrentPreservesUtteranceAndAdjustsTheIndex() {
        val queue = SpeechQueue()
        queue.enqueue(topic(1))
        queue.enqueue(topic(2))
        queue.select(2)
        val speaking = queue.position!!
        queue.remove(1)
        assertEquals(0, queue.topicIndex)
        assertTrue(queue.complete(speaking))
        assertEquals(1, queue.sentenceIndex)
    }

    @Test fun removingCurrentTopicAdvancesAndRejectsTheOldCompletion() {
        val queue = SpeechQueue()
        queue.enqueue(topic(1))
        queue.enqueue(topic(2))
        val stale = queue.position!!
        queue.remove(1)
        assertEquals(2L, queue.current!!.id)
        assertEquals(0, queue.sentenceIndex)
        assertFalse(queue.complete(stale))
        queue.remove(2)
        assertTrue(queue.topics.isEmpty())
    }

    @Test fun previousCrossesTopicsAndReplayRejectsOldCallbacks() {
        val queue = SpeechQueue()
        queue.enqueue(topic(1))
        queue.enqueue(topic(2))
        val stale = queue.position!!
        queue.select(2)
        queue.previous()
        assertEquals(1L, queue.current!!.id)
        assertEquals(1, queue.sentenceIndex)
        queue.previous()
        assertEquals(0, queue.sentenceIndex)
        assertFalse(queue.complete(stale))
    }

    @Test fun errorOrStopClearsAndStaleDoneCannotAffectANewQueue() {
        val queue = SpeechQueue()
        queue.enqueue(topic(1))
        val old = queue.position!!
        queue.clear()
        queue.enqueue(topic(2))
        assertFalse(queue.complete(old))
        assertEquals(0, queue.sentenceIndex)
    }

    @Test fun duplicateOversizedAndEmptyTopicsAreRejectedWithoutMutatingQueue() {
        val queue = SpeechQueue()
        assertTrue(queue.enqueue(topic(1)))
        assertFalse(queue.enqueue(topic(1)))
        assertFalse(queue.enqueue(topic(2, 0)))
        assertFalse(queue.enqueue(topic(3).copy(sentences = listOf(SpeechSentence("a".repeat(4000), "", "", 0)))))
        assertEquals(listOf(1L), queue.topics.map { it.id })
    }

    @Test fun queueBoundsApplyAcrossTopicsAndReplacementCanRecoverCapacity() {
        val queue = SpeechQueue()
        repeat(SpeechQueue.MAX_TOPICS) { assertTrue(queue.enqueue(topic(it.toLong() + 1))) }
        assertFalse(queue.enqueue(topic(100)))
        assertTrue(queue.accepts(topic(100), replace = true))
        queue.clear()
        val large = topic(101).copy(sentences = List(400) { SpeechSentence("a".repeat(200), "", "", 0) })
        repeat(3) { assertTrue(queue.enqueue(large.copy(id = it.toLong()))) }
        assertFalse(queue.enqueue(topic(200)))
    }
}
