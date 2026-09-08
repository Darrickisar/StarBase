package StarBase.Android.Forum.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import StarBase.Android.Forum.data.CommunityForm
import StarBase.Android.Forum.data.EssenceDetail
import StarBase.Android.Forum.data.EssencePage
import StarBase.Android.Forum.data.EssenceProgress
import StarBase.Android.Forum.net.EssenceApi
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.*
import StarBase.Android.Forum.ui.components.TopicRow
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.theme.LocalTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class EssenceViewModel(private val api: EssenceApi = EssenceApi()) : ViewModel() {
    var state by mutableStateOf<Load<EssencePage>>(Load.Loading)
        private set
    var detail by mutableStateOf<Load<EssenceDetail>>(Load.Loading)
        private set
    var topicId by mutableStateOf<Int?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var editor by mutableStateOf<CommunityForm?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    var noticeNeedsLogin by mutableStateOf(false)
        private set

    private var session by mutableStateOf<Pair<Boolean, Int>?>(null)
    private var initialBound: Int? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var writeGeneration = 0
    private var generation = 0
    private var lastLoadedAt = 0L

    fun bind(signedIn: Boolean, userId: Int, initialTopicId: Int? = null) {
        val key = signedIn to userId
        if (session == key) {
            if (initialTopicId != initialBound) {
                initialBound = initialTopicId
                initialTopicId?.let(::openTopic)
            }
            return
        }
        session = key
        initialBound = initialTopicId
        readJob?.cancel()
        writeGeneration++
        writeJob?.cancel()
        editor = null
        saving = false
        state = Load.Loading
        detail = Load.Loading
        clearNotice()
        topicId = initialTopicId?.takeIf { it > 0 } ?: topicId
        load()
    }

    fun isBoundTo(signedIn: Boolean, userId: Int): Boolean = session == (signedIn to userId)

    fun openTopic(id: Int) {
        if (id <= 0 || saving) return
        topicId = id
        detail = Load.Loading
        load()
    }

    fun backToList() {
        if (saving) return
        topicId = null
        load()
    }

    fun load(more: Boolean = false) {
        if (more && (loadingMore || refreshing || topicId != null)) return
        val id = topicId
        val previous = (state as? Load.Ready)?.value
        if (more && (previous == null || previous.page >= previous.lastPage)) return
        val page = if (more) (previous?.page ?: 1) + 1 else 1
        val request = ++generation
        readJob?.cancel()
        refreshing = !more
        loadingMore = more
        if (!more) {
            if (id == null && state !is Load.Ready) state = Load.Loading
            if (id != null && detail !is Load.Ready) detail = Load.Loading
        }
        readJob = viewModelScope.launch {
            try {
                if (id == null) {
                    val next = api.list(page)
                    if (request != generation) return@launch
                    state = Load.Ready(if (more && previous != null) next.copy(entries = (previous.entries + next.entries).distinctBy { it.topic.id }) else next)
                } else {
                    val next = api.detail(id)
                    if (request != generation) return@launch
                    detail = Load.Ready(next)
                }
                lastLoadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (request != generation) return@launch
                if (id == null && state !is Load.Ready) state = failureOf(e)
                else if (id != null && detail !is Load.Ready) detail = failureOf(e)
                else showError(e)
            } finally {
                if (request == generation) { refreshing = false; loadingMore = false }
            }
        }
    }

    fun refreshIfStale() {
        if (!saving && !refreshing && !loadingMore && lastLoadedAt > 0 && System.currentTimeMillis() - lastLoadedAt > 60_000) load()
    }

    fun edit(form: CommunityForm) { if (!saving) { clearNotice(); editor = form } }
    fun cancelEdit() { if (!saving) editor = null }
    fun clearNotice() { notice = ""; noticeNeedsLogin = false }

    fun submit(values: Map<String, String>) {
        val form = editor ?: return
        if (saving) return
        saving = true
        clearNotice()
        val request = ++writeGeneration
        writeJob = viewModelScope.launch {
            try {
                val result = api.submit(form, values)
                if (request != writeGeneration) return@launch
                notice = result.message
                editor = null
                load()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (request == writeGeneration) showError(e)
            } finally {
                if (request == writeGeneration) saving = false
            }
        }
    }

    private fun showError(e: Exception) {
        notice = e.message ?: "操作失败，请重试"
        noticeNeedsLogin = e is SiteException && e.kind == SiteException.Kind.AUTH
    }
}

@Composable
fun EssenceScreen(
    onBack: () -> Unit,
    onTopic: (Int) -> Unit,
    onLogin: () -> Unit,
    onOpenLink: (String) -> Unit,
    vm: EssenceViewModel = viewModel(),
    signedIn: Boolean = false,
    userId: Int = 0,
    initialTopicId: Int? = null
) {
    LaunchedEffect(signedIn, userId, initialTopicId) { vm.bind(signedIn, userId, initialTopicId) }
    OnReturnToForeground(signedIn to userId) { if (vm.isBoundTo(signedIn, userId)) vm.refreshIfStale() }
    if (!vm.isBoundTo(signedIn, userId)) {
        Column { DetailBar("申精", onBack = onBack); LoadingMark() }
        return
    }
    var pickingTopic by remember(signedIn, userId) { mutableStateOf(false) }
    val id = vm.topicId
    val back = { if (!vm.saving) { if (id == null) onBack() else vm.backToList() } }
    BackHandler(enabled = id != null || vm.saving) { back() }
    Column(Modifier.fillMaxSize()) {
        DetailBar(if (id == null) "申精" else "申精详情", onBack = back,
            action = "刷新", onAction = { vm.load() }, secondAction = "网页",
            onSecondAction = { onOpenLink(if (id == null) EssenceApi.URL else Site.topic(id)) })
        CommunityNotice(vm.notice, vm.noticeNeedsLogin, onLogin, vm::clearNotice)
        if (id == null) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                GlassButton("查看帖子申精", { pickingTopic = true }, compact = true, enabled = !vm.saving)
            }
            LoadFrame(vm.state, { vm.load() }, onLogin) { page ->
                Refreshable(vm.refreshing, { vm.load() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (page.entries.isEmpty()) item { EmptyPanel("暂时没有申精帖子") }
                        items(page.entries, key = { it.topic.id }) { entry ->
                            TopicRow(entry.topic, { vm.openTopic(entry.topic.id) })
                            Column(Modifier.fillMaxWidth().padding(start = 66.dp, end = 16.dp, bottom = 12.dp)) {
                                entry.progress?.let { EssenceProgressRow(it) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onTopic(entry.topic.id) }) { Text("阅读帖子") }
                                    TextButton(onClick = { vm.openTopic(entry.topic.id) }) { Text("查看评议") }
                                }
                            }
                            Hairline(16)
                        }
                        item { ListFooter(vm.loadingMore, page.page < page.lastPage) { vm.load(more = true) } }
                    }
                }
            }
        } else {
            LoadFrame(vm.detail, { vm.load() }, onLogin) { page ->
                Refreshable(vm.refreshing, { vm.load() }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Text(page.title.ifBlank { "帖子 ${page.topicId}" }, style = MaterialTheme.typography.titleLarge, color = LocalTokens.current.textPrimary)
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(page.statusLabel, style = MaterialTheme.typography.titleSmall, color = LocalTokens.current.accentGlow, modifier = Modifier.weight(1f))
                                TextButton({ onTopic(page.topicId) }) { Text("阅读帖子") }
                            }
                        }
                        page.progress?.let { progress -> item { EssenceProgressRow(progress) } }
                        items(page.metadata) { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalTokens.current.textSecondary) }
                        items(page.notes) { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.textSecondary) }
                        if (!signedIn) item { GlassButton("登录", onLogin, modifier = Modifier.fillMaxWidth()) }
                        if (signedIn) items(page.forms, key = { it.key }) { form ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassButton(form.label, { vm.edit(form) }, modifier = Modifier.fillMaxWidth(), enabled = !vm.saving, primary = form.enabled)
                                if (!form.enabled) Text(form.unavailableReason, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.textTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
    if (pickingTopic) CommunityTopicPicker("查看帖子申精", { pickingTopic = false }) {
        pickingTopic = false
        vm.openTopic(it)
    }
    vm.editor?.let { form ->
        CommunityFormDialog(form, vm.saving, vm.notice, vm.noticeNeedsLogin, vm::cancelEdit,
            vm::submit, onLogin, onOpenLink)
    }
}

@Composable
private fun EssenceProgressRow(progress: EssenceProgress) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("申精 ${progress.points} / ${progress.required}", style = MaterialTheme.typography.labelLarge, color = LocalTokens.current.textPrimary)
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    }
}
