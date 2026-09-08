package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbRadius

class MarkdownEditorState(initialText: String = "") {
    private var history = MarkdownHistory(MarkdownValue(initialText))
    var value by mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun update(next: TextFieldValue) {
        history.update(MarkdownValue(next.text, next.selection.start, next.selection.end))
        value = next
        refreshHistory()
    }

    fun insert(markdown: String) = apply(insertMarkdown(history.value, markdown))
    fun format(action: MarkdownAction) = apply(formatMarkdown(history.value, action))
    fun undo() { value = history.undo().fieldValue(); refreshHistory() }
    fun redo() { value = history.redo().fieldValue(); refreshHistory() }

    fun reset(text: String, selectionStart: Int = text.length, selectionEnd: Int = selectionStart) {
        history = MarkdownHistory(MarkdownValue(text, selectionStart, selectionEnd))
        value = history.value.fieldValue()
        refreshHistory()
    }

    private fun apply(next: MarkdownValue) { update(next.fieldValue()) }
    private fun refreshHistory() { canUndo = history.canUndo; canRedo = history.canRedo }
    private fun MarkdownValue.fieldValue() = TextFieldValue(text, TextRange(start, end))
}

@Composable
fun rememberMarkdownEditorState(key: Any?, initialText: String = ""): MarkdownEditorState =
    remember(key) { MarkdownEditorState(initialText) }

@Composable
fun MarkdownEditor(
    state: MarkdownEditorState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "正文",
    minLines: Int = 5,
    onAttach: (() -> Unit)? = null,
    onValueChange: (TextFieldValue) -> Unit = {}
) {
    val tokens = LocalTokens.current
    var preview by rememberSaveable(state) { mutableStateOf(false) }
    fun changed(action: () -> Unit) { action(); onValueChange(state.value) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Tab(selected = !preview, onClick = { preview = false }, modifier = Modifier.weight(1f),
                text = { Text("编辑") })
            Tab(selected = preview, onClick = { preview = true }, modifier = Modifier.weight(1f),
                text = { Text("预览") })
        }
        if (preview) {
            MarkdownPreview(state.value.text, Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(12.dp))
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                WritingIconButton(Icons.AutoMirrored.Outlined.Undo, "撤销", enabled && state.canUndo) { changed(state::undo) }
                WritingIconButton(Icons.AutoMirrored.Outlined.Redo, "重做", enabled && state.canRedo) { changed(state::redo) }
                val actions = listOf(
                    Triple(Icons.Outlined.FormatBold, "加粗", MarkdownAction.BOLD),
                    Triple(Icons.Outlined.FormatItalic, "斜体", MarkdownAction.ITALIC),
                    Triple(Icons.Outlined.Title, "标题", MarkdownAction.HEADING),
                    Triple(Icons.Outlined.FormatQuote, "引用", MarkdownAction.QUOTE),
                    Triple(Icons.AutoMirrored.Outlined.FormatListBulleted, "无序列表", MarkdownAction.BULLET),
                    Triple(Icons.Outlined.FormatListNumbered, "有序列表", MarkdownAction.NUMBERED),
                    Triple(Icons.Outlined.Code, "行内代码", MarkdownAction.INLINE_CODE),
                    Triple(Icons.Outlined.DataObject, "代码块", MarkdownAction.CODE_BLOCK),
                    Triple(Icons.Outlined.Link, "链接", MarkdownAction.LINK)
                )
                actions.forEach { (icon, label, action) ->
                    WritingIconButton(icon, label, enabled) { changed { state.format(action) } }
                }
                if (onAttach != null) WritingIconButton(Icons.Outlined.AttachFile, "添加附件", enabled, onAttach)
            }
            BasicTextField(
                value = state.value,
                onValueChange = { state.update(it); onValueChange(state.value) },
                enabled = enabled,
                minLines = minLines.coerceAtLeast(1),
                maxLines = 16,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.accentWarm),
                modifier = Modifier.fillMaxWidth()
                    .liquidGlass(RoundedCornerShape(SbRadius.field), GlassLevel.LOW)
                    .padding(14.dp).semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box {
                        if (state.value.text.isEmpty()) Text(placeholder,
                            style = MaterialTheme.typography.bodyMedium, color = tokens.textTertiary)
                        inner()
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WritingIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp),
                tint = if (enabled) LocalTokens.current.textSecondary else LocalTokens.current.textTertiary)
        }
    }
}
