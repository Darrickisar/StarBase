package StarBase.Android.Forum.vision

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Square
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.ui.theme.LocalTokens
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun VisionEditor(
    state: VisionUiState,
    pending: VisionEdit?,
    onPending: (VisionEdit?) -> Unit,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit
) {
    val bitmap = state.bitmap ?: return
    val tokens = LocalTokens.current
    var precise by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val previewHeight = (maxWidth * (bitmap.height.toFloat() / bitmap.width)).coerceIn(180.dp, 460.dp)
            ImageEditingCanvas(bitmap, pending, !state.busy, onPending,
                Modifier.fillMaxWidth().height(previewHeight).clip(RoundedCornerShape(8.dp)).background(tokens.codeBg))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            VisionTool.entries.forEach { tool ->
                val icon = when (tool) {
                    VisionTool.CROP -> Icons.Outlined.Crop
                    VisionTool.REDACT -> Icons.Outlined.Square
                    VisionTool.ARROW -> Icons.Outlined.ArrowOutward
                }
                VisionIcon(icon, tool.label(), enabled = !state.busy, active = pending?.tool == tool) {
                    onPending(if (pending?.tool == tool) null else VisionEdit(tool, ImagePoint(0.15f, 0.2f), ImagePoint(0.85f, 0.8f)))
                }
            }
            VisionIcon(Icons.AutoMirrored.Outlined.Undo, "撤销编辑", !state.busy && state.session.edits.isNotEmpty()) {
                onPending(null); onUndo()
            }
            VisionIcon(Icons.Outlined.RestartAlt, "重置图片", !state.busy && state.session.edits.isNotEmpty()) {
                onPending(null); onReset()
            }
            VisionIcon(Icons.Outlined.Tune, "精确选区", pending != null && !state.busy, active = precise) { precise = !precise }
        }
        if (pending != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(pending.tool.label(), style = MaterialTheme.typography.labelLarge,
                    color = tokens.textPrimary, modifier = Modifier.weight(1f))
                VisionIcon(Icons.Outlined.Close, "取消选区", !state.busy) { onPending(null) }
                VisionIcon(Icons.Outlined.Check, "应用${pending.tool.label()}", !state.busy && pending.valid(), onClick = onApply)
            }
            if (precise) {
                CoordinateSlider("起点 X", pending.start.x, !state.busy) { onPending(pending.copy(start = pending.start.copy(x = it))) }
                CoordinateSlider("起点 Y", pending.start.y, !state.busy) { onPending(pending.copy(start = pending.start.copy(y = it))) }
                CoordinateSlider("终点 X", pending.end.x, !state.busy) { onPending(pending.copy(end = pending.end.copy(x = it))) }
                CoordinateSlider("终点 Y", pending.end.y, !state.busy) { onPending(pending.copy(end = pending.end.copy(y = it))) }
            }
        }
        Text("${bitmap.width} × ${bitmap.height}", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
    }
}

@Composable
private fun CoordinateSlider(label: String, value: Float, enabled: Boolean, onValue: (Float) -> Unit) {
    Column {
        Text("$label  ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = LocalTokens.current.textSecondary)
        Slider(value = value, onValueChange = onValue, valueRange = 0f..1f, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}

@Composable
internal fun ImageEditingCanvas(bitmap: Bitmap, pending: VisionEdit?, enabled: Boolean,
    onPending: (VisionEdit?) -> Unit, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val accent = LocalTokens.current.accentWarm
    val tool = pending?.tool
    val currentPending by rememberUpdatedState(pending)
    val updatePending by rememberUpdatedState(onPending)
    Canvas(modifier.semantics { contentDescription = "图片编辑预览" }
        .pointerInput(bitmap, tool, enabled) {
            if (tool == null || !enabled) return@pointerInput
            var start: ImagePoint? = null
            var previous: VisionEdit? = null
            detectDragGestures(
                onDragStart = { offset ->
                    previous = currentPending
                    val viewport = ImageViewport.fit(bitmap.width, bitmap.height, size.width.toFloat(), size.height.toFloat())
                    start = viewport.point(offset.x, offset.y)
                    start?.let { updatePending(VisionEdit(tool, it, it)) }
                },
                onDragCancel = { start = null; updatePending(previous) },
                onDragEnd = { start = null },
                onDrag = { change, _ ->
                    start?.let { origin ->
                        change.consume()
                        val viewport = ImageViewport.fit(bitmap.width, bitmap.height, size.width.toFloat(), size.height.toFloat())
                        viewport.point(change.position.x, change.position.y, clamp = true)?.let { updatePending(VisionEdit(tool, origin, it)) }
                    }
                }
            )
        }) {
        val viewport = ImageViewport.fit(bitmap.width, bitmap.height, size.width, size.height)
        drawImage(image, dstOffset = IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt()),
            dstSize = IntSize(viewport.width.roundToInt().coerceAtLeast(1), viewport.height.roundToInt().coerceAtLeast(1)))
        if (pending != null) {
            val start = Offset(viewport.left + pending.start.x * viewport.width, viewport.top + pending.start.y * viewport.height)
            val end = Offset(viewport.left + pending.end.x * viewport.width, viewport.top + pending.end.y * viewport.height)
            val topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
            val box = Size(kotlin.math.abs(start.x - end.x), kotlin.math.abs(start.y - end.y))
            when (pending.tool) {
                VisionTool.CROP -> {
                    val shade = Color.Black.copy(alpha = 0.5f)
                    drawRect(shade, Offset(viewport.left, viewport.top), Size(viewport.width, topLeft.y - viewport.top))
                    drawRect(shade, Offset(viewport.left, topLeft.y + box.height),
                        Size(viewport.width, viewport.top + viewport.height - topLeft.y - box.height))
                    drawRect(shade, Offset(viewport.left, topLeft.y), Size(topLeft.x - viewport.left, box.height))
                    drawRect(shade, Offset(topLeft.x + box.width, topLeft.y),
                        Size(viewport.left + viewport.width - topLeft.x - box.width, box.height))
                    drawRect(accent, topLeft, box, style = Stroke(2.dp.toPx()))
                }
                VisionTool.REDACT -> { drawRect(Color.Black, topLeft, box); drawRect(accent, topLeft, box, style = Stroke(1.dp.toPx())) }
                VisionTool.ARROW -> {
                    val angle = atan2(end.y - start.y, end.x - start.x)
                    val stroke = (minOf(bitmap.width, bitmap.height) * 0.009f).coerceIn(3f, 28f) * viewport.width / bitmap.width
                    val length = minOf(hypot(end.x - start.x, end.y - start.y) * 0.35f, stroke * 5f)
                    val a = Offset(end.x - length * cos(angle - 0.55f), end.y - length * sin(angle - 0.55f))
                    val b = Offset(end.x - length * cos(angle + 0.55f), end.y - length * sin(angle + 0.55f))
                    for ((color, width) in listOf(Color.White to stroke + 2f, Color(0xFFCD2634) to stroke)) {
                        drawLine(color, start, end, width); drawLine(color, a, end, width); drawLine(color, end, b, width)
                    }
                }
            }
            drawCircle(accent, 5.dp.toPx(), start)
            drawCircle(accent, 5.dp.toPx(), end)
        }
    }
}

internal fun VisionTool.label() = when (this) {
    VisionTool.CROP -> "裁剪"
    VisionTool.REDACT -> "不透明遮盖"
    VisionTool.ARROW -> "箭头"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisionIcon(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { selected = active }) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp),
                tint = when { !enabled -> LocalTokens.current.textTertiary; active -> LocalTokens.current.accentWarm
                    else -> LocalTokens.current.textPrimary })
        }
    }
}
