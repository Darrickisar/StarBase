package StarBase.Android.Forum.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import StarBase.Android.Forum.data.DraftKind
import StarBase.Android.Forum.data.DraftQuote
import StarBase.Android.Forum.data.DraftStore
import StarBase.Android.Forum.data.Drafts
import StarBase.Android.Forum.data.WritingDraft
import StarBase.Android.Forum.data.DraftImage
import java.util.UUID

class WritingDraftState internal constructor(
    val store: DraftStore,
    initial: WritingDraft,
    val missingRequestedDraft: Boolean = false
) {
    var draft by mutableStateOf(initial)
        private set

    fun updateBody(value: TextFieldValue) = update(draft.copy(
        body = value.text, selectionStart = value.selection.start, selectionEnd = value.selection.end
    ))
    fun updateTitle(title: String) = update(draft.copy(title = title))
    fun updateForum(forumId: Int) = update(draft.copy(forumId = forumId))
    fun updateQuote(quote: DraftQuote?) = update(draft.copy(quote = quote))
    fun updateImages(images: List<DraftImage>) = update(draft.copy(localImages = images))

    private fun update(next: WritingDraft) {
        if (next == draft) return
        draft = next.copy(updatedAt = System.currentTimeMillis())
        persist()
    }

    fun persist() {
        if (draft.hasContent) store.save(draft) else store.delete(draft.accountId, draft.id)
    }

    /** Call only after confirmed success, or an explicit discard. */
    fun discard() {
        store.delete(draft.accountId, draft.id)
        draft = Drafts.create(draft.accountId, draft.kind, draft.targetId).copy(forumId = draft.forumId)
    }
}

@Composable
fun rememberWritingDraft(
    accountId: Int,
    kind: DraftKind,
    targetId: Int = 0,
    draftId: String? = null,
    initialText: String = ""
): WritingDraftState {
    require(accountId > 0) { "Resolve the signed-in account before opening a draft" }
    val context = LocalContext.current
    val store = remember(context) { DraftStore.get(context) }
    val retainedId = rememberSaveable(accountId, kind, targetId, draftId, initialText) { UUID.randomUUID().toString() }
    val state = remember(accountId, kind, targetId, draftId, initialText) {
        val existing = if (draftId != null) {
            store.get(accountId, draftId)?.takeIf { it.kind == kind && it.targetId == targetId }
        } else if (store.get(accountId, retainedId) != null) {
            store.get(accountId, retainedId)
        } else if (initialText.isBlank()) {
            store.find(accountId, kind, targetId)
        } else null
        WritingDraftState(
            store = store,
            initial = existing ?: Drafts.create(accountId, kind, targetId).copy(
                id = retainedId,
                body = initialText, selectionStart = initialText.length, selectionEnd = initialText.length
            ),
            missingRequestedDraft = draftId != null && existing == null
        )
    }
    LaunchedEffect(state) { state.persist() }
    return state
}
