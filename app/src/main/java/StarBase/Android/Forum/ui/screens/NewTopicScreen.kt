package StarBase.Android.Forum.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.DraftKind
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import StarBase.Android.Forum.data.DraftImageFiles
import android.net.Uri
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.ErrorPanel
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.LoadingMark
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.components.AttachmentQueue
import StarBase.Android.Forum.ui.components.MarkdownEditor
import StarBase.Android.Forum.ui.components.WritingIconButton
import StarBase.Android.Forum.ui.components.rememberAttachmentQueue
import StarBase.Android.Forum.ui.components.rememberMarkdownEditorState
import StarBase.Android.Forum.ui.components.rememberWritingDraft
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

class NewTopicViewModel(
    private val fetchBoards: suspend () -> List<Pair<Int, String>> = Api::newTopicBoards
) : ViewModel() {
    var boards by mutableStateOf<Load<List<Pair<Int, String>>>>(Load.Loading)
        private set
    var posting by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    var browserPost by mutableStateOf("")
        private set
    var outcomeUnknown by mutableStateOf(false)
        private set
    var accountId by mutableStateOf(0)
        private set
    private var loadJob: Job? = null
    private var postJob: Job? = null
    private var generation = 0

    fun load(force: Boolean = false, accountId: Int = this.accountId) {
        if (this.accountId != accountId) {
            generation++
            loadJob?.cancel()
            postJob?.cancel()
            this.accountId = accountId
            boards = Load.Loading
            posting = false
            notice = ""
            browserPost = ""
            outcomeUnknown = false
        }
        if (accountId <= 0 || (boards is Load.Ready && !force) || loadJob?.isActive == true) return
        val requestGeneration = generation
        loadJob = viewModelScope.launch {
            try {
                val available = fetchBoards()
                if (requestGeneration == generation) boards = Load.Ready(available)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (requestGeneration == generation) boards = failureOf(e)
            }
        }
    }

    fun post(forumId: Int, title: String, body: String, onPosted: (Int) -> Unit) {
        if (posting) return
        if (accountId <= 0) { notice = "请先登录"; return }
        if ((boards as? Load.Ready)?.value?.none { it.first == forumId } != false) {
            notice = "请选择当前可发帖的板块"; return
        }
        if (title.isBlank()) { notice = "标题不能为空"; return }
        if (body.isBlank()) { notice = "正文不能为空"; return }
        val owner = accountId
        val requestGeneration = generation
        posting = true
        outcomeUnknown = false
        postJob = viewModelScope.launch {
            var submitted = false
            try {
                check(Api.me()?.id == owner) { "登录账号已变化，请重新打开编辑器" }
                submitted = true
                val result = Api.newTopic(forumId, title, body)
                if (requestGeneration != generation) return@launch
                if (result.topicId <= 0) {
                    outcomeUnknown = true
                    notice = "站点未返回主题地址，请检查是否已发布。草稿已保留。"
                } else {
                    notice = result.message.ifBlank { "已发布" }
                    onPosted(result.topicId)
                }
            } catch (e: CancellationException) {
                if (requestGeneration == generation && submitted) {
                    outcomeUnknown = true
                    notice = "发布结果尚未确认，草稿已保留"
                }
                throw e
            } catch (e: Api.NeedsBrowser) {
                if (requestGeneration == generation) { browserPost = e.url; notice = e.message.orEmpty() }
            } catch (e: Exception) {
                if (requestGeneration == generation) {
                    val refused = e is SiteException && e.kind in setOf(SiteException.Kind.AUTH, SiteException.Kind.SERVER)
                    outcomeUnknown = submitted && !refused
                    notice = if (outcomeUnknown) "发布结果未知，请检查是否已发布。草稿已保留。"
                        else e.message ?: "发布失败"
                }
            } finally {
                if (requestGeneration == generation) posting = false
            }
        }
    }

    fun clearNotice() { notice = "" }
    fun showNotice(text: String) { notice = text }
    fun browserPostHandled() { browserPost = "" }
}

@Composable
fun NewTopicScreen(
    vm: NewTopicViewModel,
    forumId: Int,
    onBack: () -> Unit,
    onPosted: (Int) -> Unit,
    onLogin: () -> Unit,
    onOpenSite: (String) -> Unit,
    accountId: Int = 0,
    initialText: String = "",
    draftId: String? = null,
    onDrafts: (() -> Unit)? = null,
    initialTitle: String = "",
    initialImage: String = ""
) {
    var draftOwner by rememberSaveable { mutableIntStateOf(0) }
    val composerState = rememberSaveableStateHolder()
    if (draftOwner == 0 && accountId > 0) SideEffect { draftOwner = accountId }
    val wrongAccount = draftOwner > 0 && accountId > 0 && draftOwner != accountId
    LaunchedEffect(accountId, wrongAccount) { vm.load(accountId = if (wrongAccount) 0 else accountId) }
    LaunchedEffect(vm.browserPost) {
        val url = vm.browserPost
        if (url.isNotBlank()) { onOpenSite(url); vm.browserPostHandled() }
    }
    if (accountId <= 0) {
        Column(Modifier.fillMaxWidth()) {
            DetailBar(title = "发新帖", onBack = onBack)
            Column(Modifier.padding(SbMetrics.pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("登录后继续写作", color = LocalTokens.current.textSecondary)
                GlassButton("登录", onClick = onLogin, primary = true)
            }
        }
        return
    }
    if (wrongAccount) {
        Column(Modifier.fillMaxWidth()) {
            DetailBar(title = "发新帖", onBack = onBack)
            Text("账号已切换，草稿保留在原账号。请返回后重新创建草稿。",
                color = LocalTokens.current.textSecondary, modifier = Modifier.padding(SbMetrics.pagePadding))
        }
        return
    }
    composerState.SaveableStateProvider("composer") {
        key(accountId, draftId, initialText) {
            NewTopicComposer(vm, forumId, accountId, initialText, draftId, onBack, onPosted, onLogin, onDrafts,
                initialTitle, initialImage)
        }
    }
}

@Composable
private fun NewTopicComposer(
    vm: NewTopicViewModel,
    initialForum: Int,
    accountId: Int,
    initialText: String,
    draftId: String?,
    onBack: () -> Unit,
    onPosted: (Int) -> Unit,
    onLogin: () -> Unit,
    onDrafts: (() -> Unit)?,
    initialTitle: String,
    initialImage: String
) {
    val draft = rememberWritingDraft(accountId, DraftKind.NEW_TOPIC, draftId = draftId, initialText = initialText)
    val editor = rememberMarkdownEditorState(draft, draft.draft.body)
    val queue = rememberAttachmentQueue(accountId, draft, Api::newTopicUploader, localDraft = draft) { markdown ->
        editor.insert("\n$markdown\n")
        draft.updateBody(editor.value)
    }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val tokens = LocalTokens.current
    val context = LocalContext.current
    var seeded by rememberSaveable { mutableStateOf(false) }
    val revision by draft.store.revision.collectAsState()
    val persistence by draft.store.persistence.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var leaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var discardDialog by remember { mutableStateOf(false) }
    var retryDialog by remember { mutableStateOf(false) }
    val locked = vm.posting || saving
    val available = if (vm.accountId == accountId) (vm.boards as? Load.Ready)?.value.orEmpty() else emptyList()

    LaunchedEffect(draft) {
        if (!seeded) {
            seeded = true
            if (draft.draft.title.isBlank() && initialTitle.isNotBlank()) draft.updateTitle(initialTitle)
            if (initialImage.isNotBlank()) {
                val image = DraftImageFiles.image(context, Uri.parse(initialImage))
                if (image != null && draft.draft.localImages.none { it.fileName == image.fileName }) {
                    draft.updateImages(draft.draft.localImages + image)
                } else if (image == null) vm.showNotice("截图无法恢复，请重新选择图片")
            }
        }
        editor.reset(draft.draft.body, draft.draft.selectionStart, draft.draft.selectionEnd)
        if (draft.draft.forumId <= 0 && initialForum > 0) draft.updateForum(initialForum)
        if (draft.missingRequestedDraft) vm.showNotice("所选草稿不存在或不属于当前账号")
    }
    LaunchedEffect(available) {
        if (draft.draft.forumId <= 0) available.firstOrNull()?.let { draft.updateForum(it.first) }
    }

    fun leave(action: () -> Unit) {
        if (locked) return
        scope.launch {
            saving = true
            try {
                draft.persist()
                if (draft.store.awaitPendingWrites()) action() else vm.showNotice("草稿未能保存，请重试后再离开")
            } finally { saving = false }
        }
    }
    fun requestLeave(action: () -> Unit) {
        if (locked) return
        if (queue.hasUnfinished) leaveAction = action else leave(action)
    }
    fun post() {
        if (locked || queue.busy || queue.hasUnfinished) return
        scope.launch {
            saving = true
            try {
                draft.persist()
                if (!draft.store.awaitPendingWrites()) { vm.showNotice("草稿保存失败，请重试后再发布"); return@launch }
                val submitted = draft.draft
                keyboard?.hide()
                vm.post(submitted.forumId, submitted.title, submitted.body) { topicId ->
                    draft.discard()
                    editor.reset("")
                    onPosted(topicId)
                }
            } finally { saving = false }
        }
    }

    BackHandler { requestLeave(onBack) }
    Column(Modifier.fillMaxWidth().imePadding()) {
        DetailBar(title = "发新帖", onBack = { requestLeave(onBack) })
        if (vm.notice.isNotBlank()) NoticeBar(vm.notice, vm::clearNotice)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = SbMetrics.pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    persistence.failed -> "草稿保存失败"
                    !draft.draft.hasContent -> "新草稿"
                    persistence.savedRevision < revision -> "保存中"
                    else -> "草稿已保存"
                }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                if (persistence.failed) WritingIconButton(Icons.Outlined.Refresh, "重试保存", !locked) { draft.store.retryPendingWrites() }
                if (onDrafts != null) WritingIconButton(Icons.Outlined.FolderOpen, "打开草稿", !locked) { requestLeave(onDrafts) }
                WritingIconButton(Icons.Outlined.DeleteOutline, "删除当前草稿", !locked && draft.draft.hasContent) { discardDialog = true }
            }
            when (val state = vm.boards) {
                Load.Loading -> LoadingMark()
                is Load.Failed -> ErrorPanel(state.message, state.kind, { vm.load(force = true, accountId = accountId) }, onLogin)
                is Load.Ready -> BoardPicker(state.value, draft.draft.forumId, !locked, draft::updateForum)
            }
            OutlinedTextField(value = draft.draft.title, onValueChange = draft::updateTitle, enabled = !locked,
                label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MarkdownEditor(editor, enabled = !locked, placeholder = "正文", minLines = 7,
                onAttach = queue::pick, onValueChange = draft::updateBody)
            AttachmentQueue(queue, enabled = !locked)
        }
        GlassButton(text = if (vm.posting) "发布中" else if (saving) "保存中" else "发布",
            onClick = { if (vm.outcomeUnknown) retryDialog = true else post() }, primary = true,
            enabled = !locked && !queue.hasUnfinished && !queue.busy &&
                available.any { it.first == draft.draft.forumId } && draft.draft.title.isNotBlank() && editor.value.text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(SbMetrics.pagePadding))
    }
    if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false }, title = { Text("删除当前草稿？") },
        confirmButton = { TextButton(onClick = {
            discardDialog = false
            queue.items.toList().forEach { queue.remove(it.id) }
            draft.discard(); editor.reset("")
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("取消") } })
    leaveAction?.let { action -> AlertDialog(onDismissRequest = { leaveAction = null }, title = { Text("保存草稿并离开？") },
        text = { Text(if (draft.draft.localImages.isNotEmpty())
            "截图会保留在草稿中。其他未完成的附件需要重新选择，正在上传的文件会取消。"
            else "未完成的附件需要重新选择，正在上传的文件会取消。") },
        confirmButton = { TextButton(onClick = { leaveAction = null; leave(action) }) { Text("保存并离开") } },
        dismissButton = { TextButton(onClick = { leaveAction = null }) { Text("继续编辑") } }) }
    if (retryDialog) AlertDialog(onDismissRequest = { retryDialog = false }, title = { Text("再次发布？") },
        text = { Text("上次发布结果未知。请先检查主题列表，再次发布可能产生重复主题。") },
        confirmButton = { TextButton(onClick = { retryDialog = false; post() }) { Text("再次发布") } },
        dismissButton = { TextButton(onClick = { retryDialog = false }) { Text("取消") } })
}

@Composable
private fun BoardPicker(boards: List<Pair<Int, String>>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(boards.firstOrNull { it.first == selected }?.second ?: "选择板块", maxLines = 2)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            boards.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
