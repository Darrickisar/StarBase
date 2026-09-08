package StarBase.Android.Forum.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.data.DraftImageFiles
import StarBase.Android.Forum.net.Parse
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.theme.LocalTokens

enum class AttachmentStatus { QUEUED, PREPARING, UPLOADING, COMPLETE, FAILED, CANCELLED, UNCERTAIN }

data class QueuedAttachment(
    val id: String,
    val uri: Uri,
    val name: String = "附件",
    val mediaType: String = "application/octet-stream",
    val sourceBytes: Long = -1,
    val compress: Boolean = false,
    val status: AttachmentStatus = AttachmentStatus.QUEUED,
    val sentBytes: Long = 0,
    val totalBytes: Long = -1,
    val markdown: String = "",
    val message: String = ""
) {
    val canCompress: Boolean get() = mediaType == "image/jpeg" || mediaType == "image/png"
    val progress: Float? get() = AttachmentRules.fraction(sentBytes, totalBytes)
}

class AttachmentQueueState internal constructor(
    private val context: Context,
    val accountId: Int,
    ownerScope: CoroutineScope,
    private val uploader: suspend () -> Parse.Uploader?,
    private val onMarkdown: (String) -> Unit,
    private val localDraft: WritingDraftState? = null
) {
    private val scope = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]))
    private val serial = Semaphore(1)
    private val jobs = mutableMapOf<String, Job>()
    private var closed = false
    internal var launchPicker: (() -> Unit)? = null
    var items by mutableStateOf<List<QueuedAttachment>>(emptyList())
        private set
    var compressImages by mutableStateOf(false)
    var notice by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    val hasUnfinished: Boolean get() = items.any { it.status != AttachmentStatus.COMPLETE }

    fun pick() { if (!closed && accountId > 0) launchPicker?.invoke() }
    fun clearNotice() { notice = "" }

    fun enqueue(uris: List<Uri>, uncertainUris: Set<Uri> = emptySet()) {
        if (closed || accountId <= 0) return
        val existing = items.map { it.uri }.toSet()
        val unique = uris.distinct().filterNot { it in existing }
        val available = (AttachmentRules.MAX_FILES - items.size).coerceAtLeast(0)
        if (unique.size > available) notice = "最多选择 ${AttachmentRules.MAX_FILES} 个附件"
        unique.take(available).forEach { uri ->
            val uncertain = uri in uncertainUris
            val item = QueuedAttachment(UUID.randomUUID().toString(), uri, compress = compressImages,
                status = if (uncertain) AttachmentStatus.UNCERTAIN else AttachmentStatus.QUEUED,
                message = if (uncertain) "上次上传是否成功尚未确认" else "")
            items = items + item
            scope.launch {
                try {
                    val info = attachmentInfo(context, uri)
                    edit(item.id) { it.copy(name = info.name, mediaType = info.mediaType, sourceBytes = info.size) }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    edit(item.id) { it.copy(status = AttachmentStatus.FAILED, message = "无法读取文件，请重新选择") }
                }
            }
        }
    }

    fun setOriginal(id: String, original: Boolean) {
        if (jobs[id] != null) return
        edit(id) { if (it.status == AttachmentStatus.COMPLETE) it else it.copy(compress = !original) }
    }

    fun uploadAll() {
        items.filter { it.status == AttachmentStatus.QUEUED }.forEach { upload(it.id) }
    }

    /** UNCERTAIN attempts require a separate, explicit confirmation from the caller. */
    fun upload(id: String, confirmUncertain: Boolean = false) {
        if (closed || accountId <= 0 || jobs[id] != null) return
        val selected = items.firstOrNull { it.id == id } ?: return
        if (selected.status == AttachmentStatus.COMPLETE) return
        if (selected.status == AttachmentStatus.UNCERTAIN && !confirmUncertain) return
        edit(id) { it.copy(status = AttachmentStatus.QUEUED, message = "", sentBytes = 0, totalBytes = -1) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var submitted = false
            try {
                serial.withPermit {
                    ensureActive()
                    edit(id) { it.copy(status = AttachmentStatus.PREPARING) }
                    // A fresh identity and uploader belong to this attempt, never another account's form.
                    check(Api.me()?.id == accountId) { "登录账号已变化，请重新打开编辑器" }
                    val target = uploader() ?: error("此页面没有附件上传入口")
                    val info = attachmentInfo(context, selected.uri)
                    edit(id) { it.copy(name = info.name, mediaType = info.mediaType, sourceBytes = info.size) }
                    val file = prepareAttachment(context, selected.uri, info, selected.compress, target) { read, total ->
                        scope.launch {
                            edit(id) { if (it.status == AttachmentStatus.PREPARING) it.copy(sentBytes = read, totalBytes = total) else it }
                        }
                    }
                    ensureActive()
                    val fileName = DraftImageFiles.image(context, selected.uri)?.fileName
                    localDraft?.takeIf { draft -> draft.draft.localImages.any { it.fileName == fileName } }?.let { draft ->
                        draft.updateImages(draft.draft.localImages.map { if (it.fileName == fileName) it.copy(uncertain = true) else it })
                        check(draft.store.awaitPendingWrites()) { "附件状态保存失败，请重试保存后上传" }
                    }
                    ensureActive()
                    edit(id) { it.copy(status = AttachmentStatus.UPLOADING, sentBytes = 0, totalBytes = -1) }
                    submitted = true
                    val markdown = Api.uploadAttachment(target, file.name, file.mediaType, file.bytes) { sent, total ->
                        // OkHttp reports progress on its worker thread; snapshot state lives on Main.
                        scope.launch {
                            edit(id) { if (it.status == AttachmentStatus.UPLOADING) it.copy(sentBytes = sent, totalBytes = total) else it }
                        }
                    }
                    ensureActive()
                    edit(id) { it.copy(status = AttachmentStatus.COMPLETE, markdown = markdown,
                        sentBytes = it.totalBytes.coerceAtLeast(0), message = "已插入正文") }
                    onMarkdown(markdown)
                    forgetLocalImage(selected.uri)
                }
            } catch (e: CancellationException) {
                edit(id) { it.copy(
                    status = if (submitted) AttachmentStatus.UNCERTAIN else AttachmentStatus.CANCELLED,
                    message = if (submitted) "已取消，站点是否收到附件尚未确认" else "已取消"
                ) }
                throw e
            } catch (e: Exception) {
                val refused = e is SiteException && e.kind in setOf(SiteException.Kind.AUTH, SiteException.Kind.SERVER)
                edit(id) { it.copy(
                    status = if (submitted && !refused) AttachmentStatus.UNCERTAIN else AttachmentStatus.FAILED,
                    message = (e.message ?: "附件上传失败").take(200)
                ) }
            } finally {
                jobs.remove(id)
                busy = jobs.isNotEmpty()
            }
        }
        jobs[id] = job
        busy = true
        job.start()
    }

    fun isRunning(id: String): Boolean = jobs[id] != null
    fun cancel(id: String) { jobs[id]?.cancel() }
    fun remove(id: String) {
        jobs[id]?.cancel()
        items.firstOrNull { it.id == id }?.let { forgetLocalImage(it.uri) }
        items = items.filterNot { it.id == id }
    }

    private fun forgetLocalImage(uri: Uri) {
        val name = DraftImageFiles.image(context, uri)?.fileName ?: return
        localDraft?.let { it.updateImages(it.draft.localImages.filterNot { image -> image.fileName == name }) }
    }

    internal fun close() {
        closed = true
        launchPicker = null
        scope.cancel()
    }

    private fun edit(id: String, change: (QueuedAttachment) -> QueuedAttachment) {
        if (!closed) items = items.map { if (it.id == id) change(it) else it }
    }
}

@Composable
fun rememberAttachmentQueue(
    accountId: Int,
    targetKey: Any?,
    uploader: suspend () -> Parse.Uploader?,
    localDraft: WritingDraftState? = null,
    onMarkdown: (String) -> Unit
): AttachmentQueueState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val currentUploader by rememberUpdatedState(uploader)
    val currentInsertion by rememberUpdatedState(onMarkdown)
    val state = remember(accountId, targetKey) {
        AttachmentQueueState(context, accountId, scope, { currentUploader() }, { currentInsertion(it) }, localDraft)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { state.enqueue(it) }
    LaunchedEffect(state, localDraft?.draft?.localImages) {
        val images = localDraft?.draft?.localImages.orEmpty()
        val uris = images.associateWith { DraftImageFiles.uri(context, it) }
        state.enqueue(uris.values.toList(), uris.filterKeys { it.uncertain }.values.toSet())
    }
    SideEffect { state.launchPicker = { launcher.launch(arrayOf("*/*")) } }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

@Composable
fun AttachmentQueue(state: AttachmentQueueState, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tokens = LocalTokens.current
    var retryId by remember(state) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("附件", style = MaterialTheme.typography.labelLarge, color = tokens.textPrimary, modifier = Modifier.weight(1f))
            Checkbox(checked = state.compressImages, onCheckedChange = { state.compressImages = it }, enabled = enabled)
            Text("压缩图片", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            WritingIconButton(Icons.Outlined.AttachFile, "选择附件", enabled && state.items.size < AttachmentRules.MAX_FILES, state::pick)
            WritingIconButton(Icons.Outlined.CloudUpload, "上传待处理附件", enabled && state.items.any { it.status == AttachmentStatus.QUEUED }, state::uploadAll)
        }
        if (state.notice.isNotBlank()) Text(state.notice, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        state.items.forEach { item ->
            HorizontalDivider(color = tokens.hairline)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = tokens.textPrimary,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(attachmentStatus(item), color = if (item.status == AttachmentStatus.UNCERTAIN || item.status == AttachmentStatus.FAILED)
                            MaterialTheme.colorScheme.error else tokens.textSecondary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.isRunning(item.id)) {
                        WritingIconButton(Icons.Outlined.Cancel, "取消上传") { state.cancel(item.id) }
                    } else {
                        if (item.status != AttachmentStatus.COMPLETE) WritingIconButton(
                            if (item.status == AttachmentStatus.QUEUED) Icons.Outlined.CloudUpload else Icons.Outlined.Refresh,
                            if (item.status == AttachmentStatus.QUEUED) "上传附件" else "重试附件", enabled
                        ) {
                            if (item.status == AttachmentStatus.UNCERTAIN) retryId = item.id else state.upload(item.id)
                        }
                        WritingIconButton(Icons.Outlined.Close, "移除附件", enabled) { state.remove(item.id) }
                    }
                }
                if (item.status in setOf(AttachmentStatus.PREPARING, AttachmentStatus.UPLOADING)) {
                    val fraction = item.progress
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                if (item.message.isNotBlank()) Text(item.message, style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary)
                if (item.canCompress && item.status != AttachmentStatus.COMPLETE) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = !item.compress, onCheckedChange = { state.setOriginal(item.id, it) },
                        enabled = enabled && !state.isRunning(item.id))
                    Text("原图", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                    Text(if (item.sourceBytes >= 0) "  ${formatAttachmentSize(item.sourceBytes)}" else "",
                        style = MaterialTheme.typography.labelSmall, color = tokens.textTertiary)
                }
            }
        }
    }
    retryId?.let { id ->
        AlertDialog(onDismissRequest = { retryId = null }, title = { Text("重新上传？") },
            text = { Text("上次上传结果未知，重新上传可能产生重复附件。") },
            confirmButton = { TextButton(onClick = { retryId = null; state.upload(id, confirmUncertain = true) }) { Text("重新上传") } },
            dismissButton = { TextButton(onClick = { retryId = null }) { Text("取消") } })
    }
}

private fun attachmentStatus(item: QueuedAttachment): String = when (item.status) {
    AttachmentStatus.QUEUED -> "等待上传"
    AttachmentStatus.PREPARING -> "准备中" + (item.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
    AttachmentStatus.UPLOADING -> if (item.progress == 1f) "等待站点确认" else "上传中" + (item.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
    AttachmentStatus.COMPLETE -> "上传完成"
    AttachmentStatus.FAILED -> "上传失败"
    AttachmentStatus.CANCELLED -> "已取消"
    AttachmentStatus.UNCERTAIN -> "结果未知"
}

private fun formatAttachmentSize(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB"
    else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
