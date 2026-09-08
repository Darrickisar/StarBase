package StarBase.Android.Forum.vision

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import StarBase.Android.Forum.ui.theme.LocalTokens
import java.security.MessageDigest

/**
 * Provide a ViewModelStoreOwner per route and retain it while pushing search. The source-specific key
 * isolates images; the null picker uses that route's store. Clear the store when the route is removed.
 */
@Composable
fun VisionScreen(source: String?, onBack: () -> Unit, onSearch: (String) -> Unit, onDraft: (HelpDraft) -> Unit) {
    val key = remember(source) {
        "vision:" + (source?.let { raw -> MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) } } ?: "picker")
    }
    val context = LocalContext.current
    var savedSession by rememberSaveable(source) { mutableStateOf<String?>(null) }
    val factory = remember(key, context.applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == VisionViewModel::class.java)
                val handle = SavedStateHandle(savedSession?.let { mapOf(VisionViewModel.STATE_KEY to it) } ?: emptyMap())
                return VisionViewModel(context.applicationContext as Application, handle) as T
            }
        }
    }
    val vm: VisionViewModel = viewModel(key = key, factory = factory)
    val state by vm.state.collectAsState()
    SideEffect { savedSession = vm.savedSession() }
    val tokens = LocalTokens.current
    val draftCallback by rememberUpdatedState(onDraft)
    var pending by remember(vm) { mutableStateOf<VisionEdit?>(null) }
    var openingLink by remember(vm) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pending = null
            vm.chooseSource(uri.toString())
        }
    }
    val launchPicker: () -> Unit = {
        vm.pickerLaunched()
        try { picker.launch(arrayOf("image/*")) }
        catch (_: Exception) { vm.message("无法打开系统图片选择器") }
    }
    LaunchedEffect(source, vm) {
        vm.openInitial(source)
        if (source == null && state.session.source == null && !state.session.pickerLaunched) launchPicker()
    }
    DisposableEffect(vm) { onDispose { vm.cancelExport() } }
    val leave: () -> Unit = { vm.discard(); onBack() }
    BackHandler(onBack = leave)
    val contentReady = !state.busy && pending == null

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            VisionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = leave)
            Text("图片识别", style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            VisionIcon(Icons.Outlined.PhotoLibrary, "选择图片", !state.busy, onClick = launchPicker)
        }
        HorizontalDivider(color = tokens.hairline)
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when { state.loading -> "正在读取图片"; state.editing -> "正在应用编辑";
                    state.recognizing -> "正在识别"; else -> "正在保存草稿图片" }, color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                VisionIcon(Icons.Outlined.Close, "取消处理", onClick = vm::cancelWork)
            }
        }
        if (state.error != null || state.message != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error ?: state.message.orEmpty(), modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else tokens.textSecondary)
                if (state.bitmap == null && state.session.source != null && !state.busy)
                    VisionIcon(Icons.Outlined.Refresh, "重新读取图片", onClick = vm::load)
            }
        }
        if (state.bitmap == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.loading) "" else "尚未选择图片", style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
                Button(onClick = launchPicker, enabled = !state.busy) {
                    Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("选择图片")
                }
            }
        } else {
            val editor: @Composable () -> Unit = {
                VisionEditor(state, pending, { pending = it }, onApply = { pending?.let(vm::applyEdit); pending = null },
                    onUndo = vm::undo, onReset = vm::reset)
            }
            val result: @Composable () -> Unit = {
                VisionResultPanel(state, contentReady, vm, onSearch = { query ->
                    VisionText.query(query).takeIf(String::isNotBlank)?.let(onSearch)
                }, onDraft = { vm.exportDraft { draft -> draftCallback(draft) } },
                    onCopy = { text ->
                        try {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("图片文字", text))
                            vm.message("已复制")
                        } catch (_: Exception) { vm.message("无法复制文字") }
                    }, onLink = { openingLink = VisionText.safeLink(it) })
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { editor() }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { result() }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { editor() } }
                        item { Column(Modifier.padding(horizontal = 16.dp)) { result() } }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
    openingLink?.let { link ->
        AlertDialog(onDismissRequest = { openingLink = null }, title = { Text("打开链接") },
            text = { SelectionContainer { Text(link, style = MaterialTheme.typography.bodyMedium) } },
            confirmButton = { TextButton(onClick = {
                openingLink = null
                VisionText.safeLink(link)?.let { safe ->
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: Exception) { vm.message("没有可打开此链接的应用") }
                }
            }) { Text("打开") } },
            dismissButton = { TextButton(onClick = { openingLink = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisionResultPanel(state: VisionUiState, enabled: Boolean, vm: VisionViewModel, onSearch: (String) -> Unit,
    onDraft: () -> Unit, onCopy: (String) -> Unit, onLink: (String) -> Unit) {
    val session = state.session
    val tokens = LocalTokens.current
    var textValue by rememberSaveable(vm, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(session.text)) }
    LaunchedEffect(session.text) {
        if (textValue.text != session.text) textValue = TextFieldValue(session.text)
    }
    val visibleText = if (textValue.text == session.text) textValue else TextFieldValue(session.text)
    val selectedText = VisionText.selected(visibleText.text, visibleText.selection.start, visibleText.selection.end)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("识别文字", "求助草稿").forEachIndexed { index, label ->
                SegmentedButton(selected = session.tab == index, onClick = { vm.selectTab(index) }, enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.testTag("vision-tab-$index")) { Text(label) }
            }
        }
        if (session.tab == 0) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                VisionLanguage.entries.forEachIndexed { index, language ->
                    SegmentedButton(selected = session.language == language, onClick = { vm.language(language) }, enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                        Text(if (language == VisionLanguage.CHINESE) "中英混合" else "拉丁文字")
                    }
                }
            }
            Button(onClick = vm::recognize, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (session.recognized) "重新识别" else "识别文字与二维码")
            }
            OutlinedTextField(value = visibleText, onValueChange = {
                if (it.text.length <= VisionLimits.TEXT) { textValue = it; vm.text(it.text) }
            }, label = { Text("识别文字") }, minLines = 4, maxLines = 12, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("vision-recognized-text"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${session.text.length} / ${VisionLimits.TEXT}", color = tokens.textSecondary,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                VisionIcon(Icons.Outlined.TextFields, "选中文字作为关键错误", enabled && selectedText.isNotBlank()) { vm.keyError(VisionText.query(selectedText)) }
                VisionIcon(Icons.Outlined.ContentCopy, if (selectedText.isNotBlank()) "复制选中文字" else "复制全部文字",
                    enabled && session.text.isNotBlank()) { onCopy(selectedText.ifBlank { session.text }) }
            }
            OutlinedTextField(value = session.keyError, onValueChange = vm::keyError, label = { Text("关键错误") },
                minLines = 1, maxLines = 3, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("vision-key-error"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (enabled) onSearch(session.keyError) }),
                trailingIcon = {
                    VisionIcon(Icons.Outlined.Search, "搜索关键错误", enabled && VisionText.query(session.keyError).isNotBlank()) {
                        onSearch(session.keyError)
                    }
                })
            val links = remember(session.text, session.codes) { VisionText.links(session.text, session.codes) }
            val otherCodes = remember(session.codes) { session.codes.filter { VisionText.safeLink(it) == null } }
            if (links.isNotEmpty()) {
                Text("检测到的链接", style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
                links.forEach { link ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(link, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        VisionIcon(Icons.Outlined.OpenInNew, "打开链接 $link", enabled) { onLink(link) }
                    }
                }
            }
            if (otherCodes.isNotEmpty()) {
                Text("二维码内容", style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
                otherCodes.forEach { code ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(code, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary,
                                maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                        VisionIcon(Icons.Outlined.ContentCopy, "复制二维码内容", enabled) { onCopy(code) }
                    }
                }
            }
        } else {
            OutlinedTextField(value = session.draftTitle, onValueChange = vm::draftTitle, label = { Text("标题") },
                maxLines = 3, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("vision-draft-title"))
            OutlinedTextField(value = session.draftBody, onValueChange = vm::draftBody, label = { Text("求助内容") },
                minLines = 12, maxLines = 22, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("vision-draft-body"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = session.includeImage, onCheckedChange = vm::includeImage, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "附上已编辑图片" })
                Text("附上已编辑图片", style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary,
                    modifier = Modifier.weight(1f))
            }
            Button(onClick = onDraft, enabled = enabled && session.draftTitle.isNotBlank() && session.draftBody.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.NoteAdd, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("继续编辑草稿")
            }
        }
    }
}
