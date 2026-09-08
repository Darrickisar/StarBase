package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.*
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.Parse
import StarBase.Android.Forum.ui.theme.LocalTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Replies and private messages share a durable editor; their server submission stays with the screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftComposer(
    accountId: Int,
    kind: DraftKind,
    targetId: Int,
    draftId: String? = null,
    sending: Boolean,
    placeholder: String,
    quote: Post? = null,
    quotePage: Int = 1,
    onQuote: (Post?) -> Unit = {},
    uploader: (suspend () -> Parse.Uploader?)? = null,
    onNotice: (String) -> Unit,
    onSend: (String, Post?, () -> Unit) -> Unit
) {
    if (accountId <= 0) return
    val tokens = LocalTokens.current
    val draft = rememberWritingDraft(accountId, kind, targetId, draftId)
    val editor = rememberMarkdownEditorState(draft, draft.draft.body)
    val scope = rememberCoroutineScope()
    var expanded by remember(draft) { mutableStateOf(draftId != null) }
    var initialized by remember(draft) { mutableStateOf(false) }
    var resolving by remember(draft) { mutableStateOf(false) }
    var unresolved by remember(draft) { mutableStateOf(false) }
    var saving by remember(draft) { mutableStateOf(false) }
    val queue = if (uploader != null) rememberAttachmentQueue(accountId, draft, uploader) { markdown ->
        editor.insert(markdown)
        draft.updateBody(editor.value)
    } else null

    LaunchedEffect(draft) {
        if (draft.missingRequestedDraft) onNotice("原草稿已删除或不属于当前账号")
        editor.reset(draft.draft.body, draft.draft.selectionStart, draft.draft.selectionEnd)
        val saved = draft.draft.quote
        if (kind == DraftKind.REPLY && saved != null && quote == null) {
            resolving = true
            try {
                val page = Api.topic(targetId, saved.page)
                val found = (listOfNotNull(page.opening) + page.comments).firstOrNull {
                    if (saved.postId > 0) it.replyId == saved.postId else it.floor == saved.floor
                }
                if (found != null) onQuote(found) else {
                    unresolved = true
                    onNotice("草稿引用的楼层已移动或不可见，请重新选择引用或取消引用")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { unresolved = true; onNotice(e.message ?: "无法恢复引用") }
            finally { resolving = false }
        }
        initialized = true
    }
    LaunchedEffect(quote?.replyId, quote?.floor, initialized, quotePage) {
        if (quote != null) { unresolved = false; expanded = true }
        if (initialized && !unresolved) {
            draft.updateQuote(quote?.let {
                val saved = draft.draft.quote
                val page = saved?.takeIf { saved -> saved.postId == it.replyId && saved.floor == it.floor }?.page ?: quotePage
                DraftQuote(it.replyId, it.floor, page)
            })
        }
    }
    val tooLong = kind == DraftKind.DM && editor.value.text.length > 500
    val locked = sending || saving || resolving
    fun send() {
        if (locked || queue?.busy == true || queue?.hasUnfinished == true || editor.value.text.isBlank() || tooLong || unresolved) return
        scope.launch {
            saving = true
            draft.updateBody(editor.value)
            if (!draft.store.awaitPendingWrites()) {
                onNotice("草稿保存失败，未发送")
                saving = false
                return@launch
            }
            saving = false
            onSend(editor.value.text, quote) {
                draft.discard()
                editor.reset("")
                expanded = false
            }
        }
    }
    Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { expanded = true }, enabled = !sending) { Icon(Icons.Default.Edit, "编辑", tint = tokens.textPrimary) }
        Text(editor.value.text.ifBlank { placeholder }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = tokens.textSecondary, modifier = Modifier.weight(1f).clickable { expanded = true }.padding(vertical = 12.dp))
        IconButton(onClick = ::send, enabled = editor.value.text.isNotBlank() && !locked && !tooLong && !unresolved && queue?.busy != true && queue?.hasUnfinished != true) {
            Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = tokens.textPrimary)
        }
    }
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (kind == DraftKind.DM) "私信" else "回复", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { expanded = false }) { Icon(Icons.Default.Close, "收起编辑器") }
                }
                val quoteFloor = quote?.floor ?: draft.draft.quote?.floor
                if (quoteFloor != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("引用 #$quoteFloor${if (resolving) " · 加载中" else ""}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { onQuote(null); draft.updateQuote(null); unresolved = false }, enabled = !locked) { Text("取消引用") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    MarkdownEditor(editor, enabled = !locked, placeholder = placeholder, minLines = 6,
                        onAttach = queue?.let { it::pick }, onValueChange = draft::updateBody)
                    queue?.let { AttachmentQueue(it, enabled = !locked) }
                    if (tooLong) Text("私信最多 500 字，当前 ${editor.value.text.length} 字", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = ::send, enabled = editor.value.text.isNotBlank() && !locked && !tooLong && !unresolved && queue?.busy != true && queue?.hasUnfinished != true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(if (sending) "发送中" else if (saving) "保存中" else "发送")
                }
            }
        }
    }
}
