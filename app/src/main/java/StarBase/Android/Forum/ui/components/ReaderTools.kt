package StarBase.Android.Forum.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.data.ReaderContext
import StarBase.Android.Forum.data.ReaderHeading
import StarBase.Android.Forum.data.ReaderPreferences
import StarBase.Android.Forum.data.ReaderResource
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.theme.LocalTokens
import kotlin.math.roundToInt

val LocalReaderPreferences = staticCompositionLocalOf { ReaderPreferences() }

enum class ReaderPanel(val title: String) {
    OUTLINE("文章目录"), RESOURCES("资源与链接"), JUMP("跳转楼层"), PREFERENCES("阅读排版")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content
    )
}

@Composable
fun ReaderToolbar(
    onlyAuthor: Boolean,
    branches: Boolean,
    canFilterAuthor: Boolean,
    onOnlyAuthor: (Boolean) -> Unit,
    onBranches: (Boolean) -> Unit,
    onPanel: (ReaderPanel) -> Unit
) {
    val tokens = LocalTokens.current
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ReaderTooltip("只看楼主") {
            IconToggleButton(checked = onlyAuthor, onCheckedChange = onOnlyAuthor, enabled = canFilterAuthor) {
                NavIcon(NavGlyph.PERSON, if (onlyAuthor) tokens.accentWarm else tokens.textSecondary, onlyAuthor,
                    Modifier.semantics { contentDescription = "只看楼主" })
            }
        }
        ReaderTooltip("讨论分支") {
            IconToggleButton(checked = branches, onCheckedChange = onBranches) {
                Icon(Icons.Outlined.AccountTree, "讨论分支", tint = if (branches) tokens.accentWarm else tokens.textSecondary)
            }
        }
        ReaderTooltip("文章目录") {
            IconButton(onClick = { onPanel(ReaderPanel.OUTLINE) }) {
                NavIcon(NavGlyph.BOARDS, tokens.textSecondary, false, Modifier.semantics { contentDescription = "文章目录" })
            }
        }
        ReaderTooltip("资源与链接") {
            IconButton(onClick = { onPanel(ReaderPanel.RESOURCES) }) {
                ActionIcon(ActionGlyph.CLIP, tokens.textSecondary, modifier = Modifier.semantics { contentDescription = "资源与链接" })
            }
        }
        ReaderTooltip("跳转楼层") {
            IconButton(onClick = { onPanel(ReaderPanel.JUMP) }) {
                NavIcon(NavGlyph.COMPASS, tokens.textSecondary, false, Modifier.semantics { contentDescription = "跳转楼层" })
            }
        }
        ReaderTooltip("阅读排版") {
            IconButton(onClick = { onPanel(ReaderPanel.PREFERENCES) }) {
                Icon(Icons.Outlined.Tune, "阅读排版", tint = tokens.textSecondary)
            }
        }
    }
    Hairline()
}

@Composable
fun ReaderBranchToggle(count: Int, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        Text(if (expanded) "收起 $count 条已加载回复" else "展开 $count 条已加载回复")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Hairline()
            content()
        }
    }
}

@Composable
fun ReaderToolsSheet(
    panel: ReaderPanel,
    headings: List<ReaderHeading>,
    resources: List<ReaderResource>,
    loadedReplies: Int,
    hasMore: Boolean,
    loading: Boolean,
    message: String,
    preferences: ReaderPreferences,
    onPreferences: (ReaderPreferences) -> Unit,
    onHeading: (ReaderHeading) -> Unit,
    onResource: (Uri) -> Unit,
    onFloor: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalTokens.current
    ReaderSheet(panel.title, onDismiss) {
        when (panel) {
            ReaderPanel.OUTLINE -> LazyColumn(Modifier.fillMaxWidth()) {
                if (headings.isEmpty()) item { ReaderMessage("主楼正文没有标题") }
                items(headings, key = { it.blockIndex }) { heading ->
                    Text(
                        heading.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onHeading(heading) }
                            .padding(start = (16 + (heading.level - 1) * 10).dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                    )
                    Hairline()
                }
            }
            ReaderPanel.RESOURCES -> LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    ReaderMessage("主楼与已加载的 $loadedReplies 条评论" + if (hasMore) " · 尚有未加载评论" else "")
                }
                if (resources.isEmpty()) item { ReaderMessage("已加载正文中没有资源或链接") }
                items(resources, key = { it.url.toString() }) { resource ->
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onResource(Uri.parse(resource.url.toString())) }.padding(16.dp)) {
                            Text(resource.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(resource.url.toString(), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (resource.opening) "主楼" else "#${resource.floor}", color = tokens.textTertiary,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onFloor(if (resource.opening) 0 else resource.floor) }) {
                            NavIcon(NavGlyph.COMPASS, tokens.textSecondary, false,
                                Modifier.semantics { contentDescription = "跳到资源所在楼层" })
                        }
                    }
                    Hairline()
                }
                if (hasMore) item {
                    TextButton(onClick = onLoadMore, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (loading) "加载中" else "加载更多评论")
                    }
                }
            }
            ReaderPanel.JUMP -> {
                var input by rememberSaveable { mutableStateOf("") }
                var submitted by remember { mutableStateOf(false) }
                val floor = input.toIntOrNull()
                val valid = floor != null && floor >= 0
                val submit = { submitted = true; if (valid && !loading) onFloor(floor!!) }
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { if (it.length <= 10 && it.all(Char::isDigit)) input = it },
                            label = { Text("楼层号（0 为主楼）") },
                            singleLine = true,
                            isError = submitted && !valid,
                            supportingText = { if (submitted && !valid) Text("请输入有效楼层号") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { submit() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = submit, enabled = valid && !loading, modifier = Modifier.fillMaxWidth()) {
                            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(if (loading) "查找中" else "跳转")
                        }
                        if (message.isNotBlank()) ReaderMessage(message)
                    }
                }
            }
            ReaderPanel.PREFERENCES -> LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("字号 ${(preferences.fontScale * 100).roundToInt()}%")
                    Slider(value = preferences.fontScale, onValueChange = { onPreferences(preferences.copy(fontScale = it)) },
                        valueRange = 0.85f..1.5f, steps = 12, modifier = Modifier.semantics { contentDescription = "阅读字号" })
                    Text("行距 ${(preferences.lineHeightScale * 100).roundToInt()}%")
                    Slider(value = preferences.lineHeightScale, onValueChange = { onPreferences(preferences.copy(lineHeightScale = it)) },
                        valueRange = 0.85f..1.4f, steps = 10, modifier = Modifier.semantics { contentDescription = "阅读行距" })
                    Hairline()
                    Text("星海之间，文字自有回响。\n慢慢读，也别错过值得记住的讨论。",
                        fontSize = (14 * preferences.fontScale).sp,
                        lineHeight = (25 * preferences.fontScale * preferences.lineHeightScale).sp,
                        modifier = Modifier.padding(vertical = 20.dp))
                    TextButton(onClick = { onPreferences(ReaderPreferences()) }) { Text("恢复默认") }
                }
            }
        }
    }
}

@Composable
fun ReaderContextSheet(
    floor: Int,
    context: ReaderContext,
    loading: Boolean,
    message: String,
    onLoadMissing: (Int) -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
    postContent: @Composable (Post) -> Unit
) {
    ReaderSheet("#$floor 的回复上下文", onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            if (context.hasCycle) item { ReaderMessage("引用关系存在循环，已停止追溯") }
            context.missingFloor?.let { missing ->
                item {
                    ReaderMessage("#$missing 尚未加载或已删除")
                    TextButton(onClick = { onLoadMissing(missing) }, enabled = !loading,
                        modifier = Modifier.fillMaxWidth()) { Text(if (loading) "查找中" else "查找 #$missing") }
                }
            }
            if (message.isNotBlank()) item { ReaderMessage(message) }
            items(context.posts, key = { it.id }) { post ->
                postContent(post)
                TextButton(onClick = { onJump(post.floor) }, modifier = Modifier.fillMaxWidth()) { Text("跳到 #${post.floor}") }
                Hairline()
            }
            if (context.posts.isEmpty() && context.missingFloor == null && !context.hasCycle) {
                item { ReaderMessage("没有可显示的父楼层") }
            }
        }
    }
}

@Composable
private fun ReaderMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(16.dp))
}
