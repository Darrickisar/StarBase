package StarBase.Android.Forum.speech

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import StarBase.Android.Forum.data.TopicDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun TopicSpeechAction(onClick: () -> Unit) {
    SpeechIcon(Icons.Outlined.Headphones, "\u6717\u8bfb\u5e16\u5b50", onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicSpeechSheet(detail: TopicDetail, accountId: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SpeechMode.OPENING) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val player by SpeechController.state.collectAsStateWithLifecycle()
    val enqueue: (Boolean) -> Unit = { replace ->
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                try {
                    val topic = withContext(Dispatchers.Default) { SpeechText.topic(detail, mode) }
                    if (topic == null) error = "\u5df2\u52a0\u8f7d\u5185\u5bb9\u4e2d\u6ca1\u6709\u53ef\u6717\u8bfb\u7684\u6587\u672c"
                    else if (SpeechController.enqueue(context, accountId, topic, replace)) onDismiss()
                    else error = SpeechController.state.value.error
                } finally { busy = false }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SheetTitle("\u6717\u8bfb\u5e16\u5b50", onDismiss) }
            item { Text(detail.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            item {
                Text("\u5df2\u52a0\u8f7d ${detail.comments.distinctBy { it.id }.size} / ${detail.commentCount.coerceAtLeast(detail.comments.size)} \u6761\u56de\u590d",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Column(Modifier.selectableGroup()) {
                    SpeechMode.entries.forEach { choice ->
                        val enabled = !busy && (choice == SpeechMode.LOADED || detail.opening != null)
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(mode == choice, enabled = enabled, role = Role.RadioButton, onClick = { mode = choice }),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == choice, onClick = null, enabled = enabled)
                            Text(choice.label, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            item {
                Button(onClick = { enqueue(false) }, enabled = !busy && (detail.opening != null || mode == SpeechMode.LOADED), modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(if (player.current == null) Icons.Outlined.PlayArrow else Icons.AutoMirrored.Outlined.PlaylistAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (player.current == null) "\u5f00\u59cb\u6717\u8bfb" else "\u52a0\u5165\u961f\u5217")
                }
            }
            if (player.current != null) item {
                TextButton(onClick = { enqueue(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("\u66ff\u6362\u961f\u5217\u5e76\u64ad\u653e")
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Place once above the app's bottom navigation; the optional callback uses existing navigation. */
@Composable
fun SpeechPlayerBar(onOpenTopic: ((Int) -> Unit)? = null) {
    val state by SpeechController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val current = state.current
    if (current == null && state.error == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (current == null) Icons.Outlined.ErrorOutline else Icons.Outlined.Headphones, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).clickable { if (current != null) expanded = true else openSpeechSettings(context) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(current?.title ?: state.error.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (current != null) Text("${statusLabel(state.status)} \u00b7 ${state.sentenceIndex + 1}/${current.sentences.size} \u53e5",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (current != null) SpeechIcon(if (state.active) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (state.active) "\u6682\u505c\u6717\u8bfb" else "\u7ee7\u7eed\u6717\u8bfb", onClick = { SpeechController.toggle(context) })
                SpeechIcon(Icons.Outlined.Close, if (current == null) "\u5173\u95ed\u9519\u8bef" else "\u505c\u6b62\u5e76\u6e05\u7a7a\u6717\u8bfb", onClick = { SpeechController.clear() })
            }
            if (current != null) LinearProgressIndicator(
                progress = { state.sentenceIndex.toFloat() / current.sentences.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        }
    }
    if (expanded && current != null) PlayerSheet(state, onDismiss = { expanded = false }, onOpenTopic = { id ->
        expanded = false
        if (onOpenTopic != null) onOpenTopic(id) else openTopic(context, id)
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(state: SpeechState, onDismiss: () -> Unit, onOpenTopic: (Int) -> Unit) {
    val current = state.current ?: return
    val context = LocalContext.current
    var speed by remember(state.speed) { mutableStateOf(state.speed) }
    var timerMenu by remember { mutableStateOf(false) }
    var remaining by remember(state.sleepDeadline) { mutableStateOf(0L) }
    LaunchedEffect(state.sleepDeadline) {
        val deadline = state.sleepDeadline ?: return@LaunchedEffect
        do {
            remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(1_000L)
        } while (remaining > 0)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SheetTitle("\u5e16\u5b50\u6717\u8bfb", onDismiss) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    SpeechIcon(Icons.AutoMirrored.Outlined.OpenInNew, "\u6253\u5f00\u5f53\u524d\u5e16\u5b50", onClick = { onOpenTopic(current.topicId) })
                }
                Text(current.scopeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                state.sentence?.let { sentence ->
                    Text(sentence.author + if (sentence.floor > 0) " \u00b7 #${sentence.floor}" else "", style = MaterialTheme.typography.labelMedium)
                    Text(sentence.text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                LinearProgressIndicator(progress = { state.sentenceIndex.toFloat() / current.sentences.size }, modifier = Modifier.fillMaxWidth())
                Text("${statusLabel(state.status)} \u00b7 ${state.sentenceIndex + 1} / ${current.sentences.size} \u53e5", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    SpeechIcon(Icons.Outlined.SkipPrevious, "\u4e0a\u4e00\u53e5", state.hasPrevious, SpeechController::previous)
                    SpeechIcon(if (state.active) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (state.active) "\u6682\u505c\u6717\u8bfb" else "\u7ee7\u7eed\u6717\u8bfb", onClick = { SpeechController.toggle(context) })
                    SpeechIcon(Icons.Outlined.SkipNext, "\u4e0b\u4e00\u53e5", state.hasNext, SpeechController::next)
                    SpeechIcon(Icons.Outlined.Stop, "\u505c\u6b62\u5e76\u6e05\u7a7a\u6717\u8bfb", onClick = SpeechController::clear)
                }
            }
            if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            item {
                Text("\u8bed\u901f ${String.format(Locale.ROOT, "%.1fx", speed)}", style = MaterialTheme.typography.labelLarge)
                Slider(value = speed, onValueChange = { speed = it }, onValueChangeFinished = { SpeechController.setSpeed(speed) },
                    valueRange = 0.5f..2f, steps = 14, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "\u6717\u8bfb\u8bed\u901f" })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = { timerMenu = true }) {
                            Icon(Icons.Outlined.Timer, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.sleepDeadline == null) "\u5b9a\u65f6\u505c\u6b62: \u5173\u95ed" else "\u5269\u4f59 ${(remaining + 59_999L) / 60_000L} \u5206\u949f")
                        }
                        DropdownMenu(expanded = timerMenu, onDismissRequest = { timerMenu = false }) {
                            listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
                                DropdownMenuItem(text = { Text(if (minutes == 0) "\u5173\u95ed" else "$minutes \u5206\u949f") }, onClick = {
                                    SpeechController.setSleepTimer(minutes.takeIf { it > 0 }); timerMenu = false
                                })
                            }
                        }
                    }
                    SpeechIcon(Icons.Outlined.Settings, "\u7cfb\u7edf\u6717\u8bfb\u8bbe\u7f6e", onClick = { openSpeechSettings(context) })
                }
                if (state.voice.isNotEmpty()) Text(state.voice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("\u961f\u5217 (${state.topics.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    SpeechIcon(Icons.Outlined.DeleteOutline, "\u6e05\u7a7a\u6717\u8bfb\u961f\u5217", onClick = SpeechController::clear)
                }
            }
            items(state.topics, key = { it.id }) { topic ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { SpeechController.select(topic.id); SpeechController.play(context) }
                        .semantics { stateDescription = if (topic.id == current.id) "\u5f53\u524d\u5e16\u5b50" else "\u961f\u5217\u5e16\u5b50" }.padding(vertical = 8.dp)) {
                        Text(topic.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (topic.id == current.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(topic.scopeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SpeechIcon(Icons.Outlined.Close, "\u79fb\u9664 ${topic.title}", onClick = { SpeechController.remove(topic.id) })
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SheetTitle(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SpeechIcon(Icons.Outlined.Close, "\u5173\u95ed", onClick = onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
    }
}

private fun statusLabel(status: SpeechStatus): String = when (status) {
    SpeechStatus.STARTING -> "\u51c6\u5907\u4e2d"
    SpeechStatus.PLAYING -> "\u6b63\u5728\u6717\u8bfb"
    SpeechStatus.PAUSED -> "\u5df2\u6682\u505c"
    SpeechStatus.ERROR -> "\u6717\u8bfb\u5931\u8d25"
    SpeechStatus.IDLE -> "\u5df2\u505c\u6b62"
}

private fun openSpeechSettings(context: Context) {
    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

private fun openTopic(context: Context, id: Int) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    runCatching { context.startActivity(launch.setAction(Intent.ACTION_VIEW).setData(Uri.parse("https://linux.sb/topic/$id"))
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
}
