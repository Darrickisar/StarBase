package StarBase.Android.Forum

import StarBase.Android.Forum.data.*
import StarBase.Android.Forum.ui.components.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WritingTest {
    private fun reply(account: Int = 1, target: Int = 20390) = Drafts.create(account, DraftKind.REPLY, target)
        .copy(body = "Unsent reply", quote = DraftQuote(321, 12, 2), selectionStart = 2, selectionEnd = 7)

    @Test fun draftRoundTripPreservesQuoteTargetAndSelection() {
        val draft = reply()
        assertEquals(listOf(draft), Drafts.validateImport(1, Drafts.encode(listOf(draft))))
    }

    @Test fun accountsWithTheSameDraftIdCannotOverwriteEachOther() {
        val first = reply()
        val second = first.copy(accountId = 2, body = "Other account")
        val saved = Drafts.put(Drafts.put(emptyList(), first), second)
        assertEquals(listOf(first), Drafts.list(saved, 1))
        assertEquals(listOf(second), Drafts.remove(saved, 1, first.id))
        assertTrue(Drafts.list(saved, 0).isEmpty())
    }

    @Test fun importFromAnotherAccountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Drafts.validateImport(2, Drafts.encode(listOf(reply())))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun importingAgainAfterDiskFailureRetriesWithoutDuplicatingDrafts() = runTest {
        var attempts = 0
        val draft = reply()
        val store = DraftStore(emptyList(), { ++attempts > 1 }, backgroundScope)
        val payload = Drafts.encode(listOf(draft))
        assertEquals(1, store.mergeImport(1, payload))
        runCurrent()
        assertFalse(store.awaitPendingWrites())
        assertEquals(0, store.mergeImport(1, payload))
        runCurrent()
        assertTrue(store.awaitPendingWrites())
        assertEquals(2, attempts)
        assertEquals(listOf(draft), store.list(1))
    }

    @Test fun aQuoteCanUseItsFloorWhenTheSiteOmitsThePostAnchor() {
        val draft = reply().copy(quote = DraftQuote(0, 12, 2))
        assertEquals(draft, Drafts.normalize(draft))
        assertEquals(listOf(draft), Drafts.decode(Drafts.encode(listOf(draft))))
    }

    @Test fun emptyQuoteIdentityAndQuotesInMessagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Drafts.normalize(reply().copy(quote = DraftQuote(0, 0))) }
        assertThrows(IllegalArgumentException::class.java) { Drafts.normalize(reply().copy(kind = DraftKind.DM)) }
    }

    @Test fun undoRestoresTheOriginalSelectionAndNewEditsClearRedo() {
        val original = MarkdownValue("alpha beta", 0, 5)
        val history = MarkdownHistory(original)
        val formatted = formatMarkdown(original, MarkdownAction.BOLD)
        history.update(formatted)
        assertEquals("**alpha** beta", history.value.text)
        assertEquals(original, history.undo())
        assertEquals(formatted, history.redo())
        history.undo()
        history.update(insertMarkdown(history.value, "gamma"))
        assertEquals("gamma beta", history.value.text)
        assertFalse(history.canRedo)
    }

    @Test fun numberedSelectionDoesNotModifyTheNextUnselectedLine() {
        assertEquals("1. one\n2. two\nthree", formatMarkdown(MarkdownValue("one\ntwo\nthree", 0, 8), MarkdownAction.NUMBERED).text)
    }

    @Test fun inlineCodePreservesExistingBackticks() {
        val result = formatMarkdown(MarkdownValue("`quoted`", 0, 8), MarkdownAction.INLINE_CODE)
        assertEquals("`` `quoted` ``", result.text)
    }
}
