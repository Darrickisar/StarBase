package StarBase.Android.Forum.vision

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class VisionUiState(
    val session: VisionSession = VisionSession(),
    val bitmap: Bitmap? = null,
    val loading: Boolean = false,
    val editing: Boolean = false,
    val recognizing: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val busy get() = loading || editing || recognizing || exporting
}

internal class VisionViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val json = Json { ignoreUnknownKeys = true }
    private val restored = runCatching {
        savedState.get<String>(STATE_KEY)?.let { json.decodeFromString<VisionSession>(it) }
    }.getOrNull()?.takeIf {
        it.edits.size <= VisionLimits.EDITS && it.edits.all(VisionEdit::valid) &&
            it.text.length <= VisionLimits.TEXT && it.draftBody.length <= VisionLimits.BODY &&
            it.source.orEmpty().length <= VisionLimits.URL
    } ?: VisionSession()
    private val mutableState = MutableStateFlow(VisionUiState(session = restored))
    val state = mutableState.asStateFlow()
    private val revision = VisionRevision()
    private val recognition = VisionRecognition()
    private var original: Bitmap? = null
    private var work: Job? = null
    private var export: Job? = null
    private var attemptedLoad = false
    private var preparedUri: String? = null

    private val current get() = mutableState.value

    fun openInitial(source: String?) {
        if (!current.session.initialized || current.session.initialSource != source) {
            cancelWork()
            original = null
            preparedUri = null
            attemptedLoad = false
            update(VisionUiState(session = VisionSession(initialized = true, initialSource = source, source = source)))
        }
        if (current.bitmap == null && current.session.source != null && !attemptedLoad) load()
    }

    fun pickerLaunched() = changeSession(current.session.copy(pickerLaunched = true))

    fun chooseSource(source: String) {
        cancelWork()
        original = null
        preparedUri = null
        attemptedLoad = false
        update(VisionUiState(session = VisionSession(initialized = true, initialSource = current.session.initialSource,
            source = source, pickerLaunched = true)))
        load()
    }

    fun load() {
        val source = current.session.source ?: return
        cancelWork()
        attemptedLoad = true
        val ticket = revision.advance()
        val previousSession = current.session
        update(current.copy(loading = true, error = null, message = null))
        work = viewModelScope.launch {
            try {
                val loaded = VisionImages.load(getApplication(), source)
                val bitmap = loaded.bitmap
                val changed = previousSession.imageHash != null && previousSession.imageHash != loaded.hash
                val edits = if (changed) emptyList() else previousSession.edits
                val edited = if (edits.isEmpty()) bitmap else VisionImages.render(bitmap, edits)
                if (revision.accepts(ticket)) {
                    original = bitmap
                    if (changed) clearRecognition()
                    update(current.copy(bitmap = edited, loading = false,
                        session = current.session.copy(edits = edits, imageHash = loaded.hash),
                        message = if (changed) "原图片已变更，编辑和识别结果已清空" else null))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision.accepts(ticket)) update(current.copy(loading = false, error = friendlyError(e)))
            } catch (_: OutOfMemoryError) {
                if (revision.accepts(ticket)) update(current.copy(loading = false, error = "图片内存不足，请选择更小的图片"))
            }
        }
    }

    fun applyEdit(edit: VisionEdit) {
        if (current.busy || current.bitmap == null) return
        if (!edit.valid()) { message("选区太小"); return }
        if (current.session.edits.size >= VisionLimits.EDITS) { message("已达到 32 次编辑上限，可撤销或重置"); return }
        render(current.session.edits + edit)
    }

    fun undo() {
        if (!current.busy && current.session.edits.isNotEmpty()) render(current.session.edits.dropLast(1))
    }

    fun reset() {
        if (!current.busy && current.session.edits.isNotEmpty()) render(emptyList())
    }

    private fun render(edits: List<VisionEdit>) {
        val base = original ?: return
        cancelWork()
        val ticket = revision.advance()
        clearRecognition()
        preparedUri = null
        update(current.copy(editing = true, error = null, message = null))
        work = viewModelScope.launch {
            try {
                val bitmap = if (edits.isEmpty()) base else VisionImages.render(base, edits)
                if (revision.accepts(ticket)) update(current.copy(bitmap = bitmap, editing = false,
                    session = current.session.copy(edits = edits), message = "图片已更新，识别结果已清空"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision.accepts(ticket)) update(current.copy(editing = false, error = friendlyError(e)))
            } catch (_: OutOfMemoryError) {
                if (revision.accepts(ticket)) update(current.copy(editing = false, error = "图片内存不足，编辑未应用"))
            }
        }
    }

    fun recognize() {
        val bitmap = current.bitmap ?: return
        if (current.busy) return
        cancelWork()
        val ticket = revision.advance()
        val language = current.session.language
        clearRecognition()
        update(current.copy(recognizing = true, error = null, message = null))
        work = viewModelScope.launch {
            try {
                val result = recognition.recognize(bitmap, language)
                if (revision.accepts(ticket)) update(current.copy(recognizing = false,
                    session = current.session.copy(text = result.text, codes = result.codes,
                        recognized = true, keyError = VisionText.errorLine(result.text)),
                    message = result.warning ?: if (result.text.isBlank() && result.codes.isEmpty()) "未识别到文字或二维码" else null))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision.accepts(ticket)) update(current.copy(recognizing = false, error = friendlyError(e)))
            } catch (_: OutOfMemoryError) {
                if (revision.accepts(ticket)) update(current.copy(recognizing = false, error = "识别内存不足，请先裁剪图片"))
            }
        }
    }

    fun language(value: VisionLanguage) {
        if (current.busy || value == current.session.language) return
        revision.advance()
        clearRecognition()
        changeSession(current.session.copy(language = value))
    }

    fun text(value: String) {
        if (!current.busy) changeSession(current.session.copy(text = value.take(VisionLimits.TEXT)))
    }

    fun keyError(value: String) {
        if (!current.busy) changeSession(current.session.copy(keyError = value.take(VisionLimits.QUERY)))
    }

    fun selectTab(tab: Int) {
        if (current.busy) return
        if (tab == 1 && !current.session.draftCreated) {
            val draft = VisionText.help(current.session.text, current.session.keyError)
            changeSession(current.session.copy(tab = 1, draftCreated = true, draftTitle = draft.title, draftBody = draft.body))
        } else changeSession(current.session.copy(tab = tab.coerceIn(0, 1)))
    }

    fun draftTitle(value: String) = changeSession(current.session.copy(draftTitle = value.take(VisionLimits.TITLE)))
    fun draftBody(value: String) = changeSession(current.session.copy(draftBody = value.take(VisionLimits.BODY)))
    fun includeImage(value: Boolean) = changeSession(current.session.copy(includeImage = value))

    fun exportDraft(onReady: (HelpDraft) -> Unit) {
        if (current.busy || current.session.draftTitle.isBlank() || current.session.draftBody.isBlank()) return
        val session = current.session
        val bitmap = current.bitmap
        if (session.includeImage && bitmap == null) { message("请先选择图片"); return }
        val ticket = revision.advance()
        update(current.copy(exporting = true, error = null, message = null))
        export = viewModelScope.launch {
            try {
                // Each composer owns its file; an earlier composer may already have removed it.
                val uri = if (session.includeImage) VisionImages.saveDraft(getApplication(), bitmap!!) else null
                if (revision.accepts(ticket)) {
                    preparedUri = uri ?: preparedUri
                    update(current.copy(exporting = false))
                    onReady(HelpDraft(session.draftTitle.trim(), session.draftBody.trim(), uri))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision.accepts(ticket)) update(current.copy(exporting = false, error = friendlyError(e)))
            }
        }
    }

    fun cancelExport() {
        if (current.exporting) {
            revision.advance()
            export?.cancel()
            update(current.copy(exporting = false))
        }
    }

    fun cancelWork() {
        revision.advance()
        work?.cancel()
        export?.cancel()
        update(current.copy(loading = false, editing = false, recognizing = false, exporting = false))
    }

    fun message(value: String) = update(current.copy(message = value, error = null))

    fun savedSession(): String? = savedState[STATE_KEY]

    /** Explicit route exit only; composition disposal for search must retain the session. */
    fun discard() {
        cancelWork()
        original = null
        preparedUri = null
        attemptedLoad = false
        update(VisionUiState())
    }

    private fun clearRecognition() = changeSession(current.session.copy(text = "", keyError = "", codes = emptyList(),
        recognized = false, draftTitle = "", draftBody = "", draftCreated = false, tab = 0))

    private fun changeSession(session: VisionSession) = update(current.copy(session = session))

    private fun update(next: VisionUiState) {
        if (current.session != next.session) savedState[STATE_KEY] = json.encodeToString(next.session)
        mutableState.value = next
    }

    override fun onCleared() {
        revision.advance()
        work?.cancel()
        export?.cancel()
        recognition.close()
        original = null
        super.onCleared()
    }

    private fun friendlyError(error: Exception): String = when (error) {
        is SecurityException -> "图片访问权限已失效，请重新选择"
        is java.io.FileNotFoundException -> "图片已移除或无法访问，请重新选择"
        is IllegalArgumentException, is IllegalStateException -> error.message?.take(120) ?: "图片处理失败"
        is java.io.IOException -> "图片读取失败，请重试或重新选择"
        else -> "图片处理失败，请重试"
    }

    companion object { const val STATE_KEY = "vision.session" }
}
