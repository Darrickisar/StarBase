package StarBase.Android.Forum.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import StarBase.Android.Forum.data.*
import StarBase.Android.Forum.net.CollectionsApi
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.*
import StarBase.Android.Forum.ui.components.TopicRow
import StarBase.Android.Forum.ui.components.UserAvatar
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassTabs
import StarBase.Android.Forum.ui.theme.LocalTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CollectionsViewModel(private val api: CollectionsApi = CollectionsApi()) : ViewModel() {
    var state by mutableStateOf<Load<CollectionsPage>>(Load.Loading)
        private set
    var detail by mutableStateOf<Load<CollectionDetail>>(Load.Loading)
        private set
    var tab by mutableStateOf(CollectionsTab.EVERYONE)
        private set
    var collectionId by mutableStateOf<Int?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var membership by mutableStateOf<Load<TopicCollections>?>(null)
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
    private var membershipJob: Job? = null
    private var writeJob: Job? = null
    private var writeGeneration = 0
    private var generation = 0
    private var membershipGeneration = 0
    private var membershipTopic = 0
    private var lastLoadedAt = 0L

    fun bind(signedIn: Boolean, userId: Int, initialCollectionId: Int? = null) {
        val key = signedIn to userId
        if (session == key) {
            if (initialCollectionId != initialBound) {
                initialBound = initialCollectionId
                initialCollectionId?.let(::openCollection)
            }
            return
        }
        session = key
        initialBound = initialCollectionId
        readJob?.cancel()
        writeGeneration++
        writeJob?.cancel()
        membershipJob?.cancel()
        membershipGeneration++
        saving = false
        membership = null
        editor = null
        state = Load.Loading
        detail = Load.Loading
        clearNotice()
        if (!signedIn) tab = CollectionsTab.EVERYONE
        collectionId = initialCollectionId?.takeIf { it > 0 } ?: collectionId
        load()
    }

    fun isBoundTo(signedIn: Boolean, userId: Int): Boolean = session == (signedIn to userId)

    fun selectTab(value: CollectionsTab) {
        if (tab == value || saving) return
        tab = value
        state = Load.Loading
        load()
    }

    fun openCollection(id: Int) {
        if (id <= 0 || saving) return
        collectionId = id
        detail = Load.Loading
        load()
    }

    fun backToList() {
        if (saving) return
        collectionId = null
        load()
    }

    fun load(more: Boolean = false) {
        if (more && (loadingMore || refreshing)) return
        val id = collectionId
        val oldList = (state as? Load.Ready)?.value
        val oldDetail = (detail as? Load.Ready)?.value
        val currentPage = if (id == null) oldList?.page else oldDetail?.page
        val lastPage = if (id == null) oldList?.lastPage else oldDetail?.lastPage
        if (more && (currentPage == null || lastPage == null || currentPage >= lastPage)) return
        val page = if (more) (currentPage ?: 1) + 1 else 1
        val request = ++generation
        readJob?.cancel()
        loadingMore = more
        refreshing = !more
        if (!more) {
            if (id == null && state !is Load.Ready) state = Load.Loading
            if (id != null && detail !is Load.Ready) detail = Load.Loading
        }
        val requestedTab = tab
        readJob = viewModelScope.launch {
            try {
                if (id == null) {
                    val next = api.list(requestedTab, page)
                    if (request != generation) return@launch
                    state = Load.Ready(if (more && oldList != null) next.copy(collections = (oldList.collections + next.collections).distinctBy { it.id }) else next)
                } else {
                    val next = api.detail(id, page)
                    if (request != generation) return@launch
                    detail = Load.Ready(if (more && oldDetail != null) next.copy(topics = (oldDetail.topics + next.topics).distinctBy { it.id }) else next)
                }
                lastLoadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
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

    fun openTopic(id: Int) {
        if (saving || id <= 0) return
        membershipTopic = id
        membership = Load.Loading
        val request = ++membershipGeneration
        membershipJob?.cancel()
        membershipJob = viewModelScope.launch {
            try {
                val next = api.topic(id)
                if (request == membershipGeneration) membership = Load.Ready(next)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (request == membershipGeneration) membership = failureOf(e)
            }
        }
    }

    fun retryTopic() = openTopic(membershipTopic)

    fun closeTopic() {
        if (saving) return
        membershipGeneration++
        membershipJob?.cancel()
        membership = null
    }

    fun edit(form: CommunityForm) { if (!saving) { clearNotice(); editor = form } }
    fun cancelEdit() { if (!saving) editor = null }
    fun clearNotice() { notice = ""; noticeNeedsLogin = false }

    fun submit(values: Map<String, String>) {
        val form = editor ?: return
        perform { api.submit(form, values) }
    }

    fun changeMembership(choice: CollectionChoice) {
        val current = (membership as? Load.Ready)?.value ?: return
        perform { api.membership(current.topicId, choice.id, !choice.included) }
    }

    fun removeAll() {
        val current = (membership as? Load.Ready)?.value ?: return
        perform { api.removeAll(current.topicId) }
    }

    private fun perform(action: suspend () -> CommunityWriteResult) {
        if (saving) return
        saving = true
        clearNotice()
        val request = ++writeGeneration
        writeJob = viewModelScope.launch {
            try {
                val result = action()
                if (request != writeGeneration) return@launch
                editor = null
                notice = result.message
                saving = false
                if (membership != null) openTopic(membershipTopic)
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
fun CollectionsScreen(
    onBack: () -> Unit,
    onTopic: (Int) -> Unit,
    onLogin: () -> Unit,
    onOpenLink: (String) -> Unit,
    vm: CollectionsViewModel = viewModel(),
    signedIn: Boolean = false,
    userId: Int = 0,
    initialCollectionId: Int? = null,
    initialTopicId: Int? = null
) {
    LaunchedEffect(signedIn, userId, initialCollectionId, initialTopicId) {
        vm.bind(signedIn, userId, initialCollectionId)
        initialTopicId?.let(vm::openTopic)
    }
    OnReturnToForeground(signedIn to userId) { if (vm.isBoundTo(signedIn, userId)) vm.refreshIfStale() }
    if (!vm.isBoundTo(signedIn, userId)) {
        Column { DetailBar("淘帖", onBack = onBack); LoadingMark() }
        return
    }
    var pickingTopic by remember(signedIn, userId) { mutableStateOf(false) }
    val id = vm.collectionId
    val url = if (id == null) CollectionsApi.listUrl(vm.tab) else CollectionsApi.detailUrl(id)
    val back = { if (!vm.saving) { if (id == null) onBack() else vm.backToList() } }
    BackHandler(enabled = id != null || vm.saving) { back() }
    Column(Modifier.fillMaxSize()) {
        DetailBar("淘帖", onBack = back, action = "刷新", onAction = { vm.load() },
            secondAction = "网页", onSecondAction = { onOpenLink(url) })
        if (id == null) {
            GlassTabs(CollectionsTab.entries.map { it.label }, CollectionsTab.entries.indexOf(vm.tab),
                { index ->
                    val next = CollectionsTab.entries[index]
                    if (next == CollectionsTab.MINE && !signedIn) onLogin() else vm.selectTab(next)
                }, Modifier.fillMaxWidth().padding(16.dp))
        }
        CommunityNotice(vm.notice, vm.noticeNeedsLogin, onLogin, vm::clearNotice)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            GlassButton("收录帖子", { if (signedIn) pickingTopic = true else onLogin() }, compact = true, enabled = !vm.saving)
        }
        if (id == null) {
            LoadFrame(vm.state, { vm.load() }, onLogin) { page ->
                Refreshable(vm.refreshing, { vm.load() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (page.collections.isEmpty()) item { EmptyPanel("还没有淘帖专辑") }
                        items(page.collections, key = { it.id }) { card ->
                            CollectionRow(card) { vm.openCollection(card.id) }
                            Hairline(16)
                        }
                        item { ListFooter(vm.loadingMore, page.page < page.lastPage) { vm.load(more = true) } }
                    }
                }
            }
        } else {
            LoadFrame(vm.detail, { vm.load() }, onLogin) { page ->
                Refreshable(vm.refreshing, { vm.load() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(page.collection.name, style = MaterialTheme.typography.titleLarge, color = LocalTokens.current.textPrimary)
                                Text(page.collection.metadata, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.textSecondary)
                                if (page.collection.description.isNotBlank()) Text(page.collection.description, style = MaterialTheme.typography.bodyMedium, color = LocalTokens.current.textSecondary)
                                page.forms.forEach { form ->
                                    GlassButton(form.label, { if (signedIn) vm.edit(form) else onLogin() },
                                        enabled = !vm.saving, primary = false, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        if (page.topics.isEmpty()) item { EmptyPanel("专辑中还没有帖子") }
                        items(page.topics, key = { it.id }) { topic ->
                            TopicRow(topic, { onTopic(topic.id) })
                            if (signedIn) Row(Modifier.fillMaxWidth().padding(end = 16.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { vm.openTopic(topic.id) }, enabled = !vm.saving) { Text("收录管理") }
                            }
                            Hairline(16)
                        }
                        item { ListFooter(vm.loadingMore, page.page < page.lastPage) { vm.load(more = true) } }
                    }
                }
            }
        }
    }
    if (pickingTopic) CommunityTopicPicker("收录帖子", { pickingTopic = false }) {
        pickingTopic = false
        vm.openTopic(it)
    }
    if (vm.membership != null && vm.editor == null) {
        AlertDialog(
            onDismissRequest = vm::closeTopic,
            properties = DialogProperties(dismissOnBackPress = !vm.saving, dismissOnClickOutside = !vm.saving),
            title = { Text("收录到淘帖") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommunityNotice(vm.notice, vm.noticeNeedsLogin, onLogin, vm::clearNotice)
                    when (val current = vm.membership) {
                        Load.Loading -> CircularProgressIndicator(Modifier.size(28.dp))
                        is Load.Failed -> {
                            Text(current.message)
                            TextButton(onClick = vm::retryTopic) { Text("重试") }
                            if (current.kind == SiteException.Kind.AUTH) TextButton(onClick = onLogin) { Text("登录") }
                        }
                        is Load.Ready -> {
                            val value = current.value
                            Text(value.topicTitle.ifBlank { "帖子 ${value.topicId}" }, style = MaterialTheme.typography.titleSmall)
                            if (value.notice.isNotBlank()) Text(value.notice)
                            if (!signedIn) TextButton(onLogin) { Text("登录") }
                            value.choices.forEach { choice ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(choice.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    TextButton({ vm.changeMembership(choice) }, enabled = !vm.saving && value.membershipForm?.enabled == true) {
                                        Text(if (choice.included) "移出专辑" else "收录")
                                    }
                                }
                            }
                            value.createForm?.let { form ->
                                GlassButton("新建专辑", { vm.edit(form) }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth())
                            }
                            if (value.canRemoveAll) TextButton(vm::removeAll, enabled = !vm.saving) { Text("全部取消收录") }
                            if (vm.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        null -> Unit
                    }
                }
            },
            confirmButton = { TextButton(vm::closeTopic, enabled = !vm.saving) { Text("关闭") } }
        )
    }
    vm.editor?.let { form ->
        CommunityFormDialog(form, vm.saving, vm.notice, vm.noticeNeedsLogin, vm::cancelEdit,
            vm::submit, onLogin, onOpenLink)
    }
}

@Composable
private fun CollectionRow(card: CollectionCard, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UserAvatar(card.author.ifBlank { card.name }, card.avatar, size = 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (card.visibility.isNotBlank()) Text(card.visibility, style = MaterialTheme.typography.labelSmall, color = tokens.accentGlow)
            if (card.description.isNotBlank()) Text(card.description, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.metadata, style = MaterialTheme.typography.labelSmall, color = tokens.textTertiary)
        }
    }
}

@Composable
internal fun CommunityNotice(message: String, needsLogin: Boolean, onLogin: () -> Unit, onDismiss: () -> Unit) {
    if (message.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row {
            if (needsLogin) TextButton(onLogin) { Text("登录") }
            TextButton(onDismiss) { Text("关闭提示") }
        }
    }
}

@Composable
internal fun CommunityTopicPicker(title: String, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it; error = "" }, label = { Text("帖子编号或链接") }, singleLine = true,
                isError = error.isNotBlank(), modifier = Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            try { onChoose(CollectionsApi.topicId(input)) } catch (e: SiteException) { error = e.message.orEmpty() }
        }, enabled = input.isNotBlank()) { Text("继续") }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
internal fun CommunityFormDialog(
    form: CommunityForm,
    saving: Boolean,
    error: String,
    needsLogin: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, String>) -> Unit,
    onLogin: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    var values by remember(form.key) { mutableStateOf(form.initialValues()) }
    val update: (String, String) -> Unit = { key, value -> values = values + (key to value) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
        title = { Text(form.label, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (form.confirmation.isNotBlank()) Text(form.confirmation)
                if (!form.enabled) {
                    Text(form.unavailableReason)
                    TextButton(onClick = { onOpenLink(form.sourceUrl) }) { Text("打开网页") }
                }
                form.fields.forEach { field ->
                    val value = values[field.name].orEmpty()
                    when (field.type) {
                        CommunityFieldType.CHECKBOX -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(value.isNotEmpty(), { update(field.name, if (it) field.value else "") }, enabled = !saving && form.enabled)
                            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                        CommunityFieldType.CHOICE -> Column {
                            Text(field.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                            field.options.forEach { option ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = !saving && form.enabled) { update(field.name, option.value) }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(value == option.value, { update(field.name, option.value) }, enabled = !saving && form.enabled)
                                    Text(option.label.ifBlank { "未选择" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        else -> OutlinedTextField(value, { update(field.name, it) },
                            label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), enabled = !saving && form.enabled,
                            singleLine = field.type != CommunityFieldType.MULTILINE,
                            minLines = if (field.type == CommunityFieldType.MULTILINE) 3 else 1,
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.type == CommunityFieldType.NUMBER) KeyboardType.Number else KeyboardType.Text),
                            supportingText = field.maxLength?.let { max -> { Text("${value.codePointCount(0, value.length)} / $max") } })
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (needsLogin) TextButton(onLogin) { Text("登录") }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton({ onSubmit(values) }, enabled = !saving && form.enabled) { Text(if (saving) "提交中" else form.label) } },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("取消") } })
}
