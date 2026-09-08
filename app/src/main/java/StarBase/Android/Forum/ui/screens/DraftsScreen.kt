package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import StarBase.Android.Forum.data.DraftKind
import StarBase.Android.Forum.data.DraftStore
import StarBase.Android.Forum.data.WritingDraft
import StarBase.Android.Forum.ui.components.WritingIconButton
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

@Composable
fun DraftsScreen(accountId: Int, onBack: () -> Unit, onResume: (WritingDraft) -> Unit, onLogin: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(context) { DraftStore.get(context) }
    val revision by store.revision.collectAsState()
    val persistence by store.persistence.collectAsState()
    val drafts = remember(accountId, revision) { store.list(accountId) }
    var deleting by remember(accountId) { mutableStateOf<WritingDraft?>(null) }
    val tokens = LocalTokens.current
    Column(Modifier.fillMaxWidth()) {
        DetailBar(title = "草稿", subtitle = if (accountId > 0) "${drafts.size} 份" else "", onBack = onBack)
        if (accountId <= 0) {
            GlassButton("登录", onClick = onLogin, primary = true, modifier = Modifier.padding(SbMetrics.pagePadding))
        } else {
            if (persistence.failed) Row(Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding), verticalAlignment = Alignment.CenterVertically) {
                Text("草稿保存失败", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f))
                WritingIconButton(Icons.Outlined.Refresh, "重试保存") { store.retryPendingWrites() }
            }
            if (drafts.isEmpty()) Text("暂无草稿", style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary,
                modifier = Modifier.padding(SbMetrics.pagePadding))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(drafts, key = { it.id }) { draft ->
                    Row(Modifier.fillMaxWidth().clickable {
                        store.get(accountId, draft.id)?.let(onResume)
                    }.padding(start = SbMetrics.pagePadding, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(draft.title.ifBlank { draftLabel(draft) }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
                            if (draft.body.isNotBlank()) Text(draft.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                            val time = remember(draft.updatedAt) { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(draft.updatedAt)) }
                            Text("${draftLabel(draft)} · $time", style = MaterialTheme.typography.labelSmall, color = tokens.textTertiary)
                        }
                        WritingIconButton(Icons.Outlined.DeleteOutline, "删除草稿") { deleting = draft }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = SbMetrics.pagePadding), color = tokens.hairline)
                }
            }
        }
    }
    deleting?.let { draft -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除草稿？") },
        text = { Text(draft.title.ifBlank { draftLabel(draft) }) },
        confirmButton = { TextButton(onClick = { store.delete(accountId, draft.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

private fun draftLabel(draft: WritingDraft): String = when (draft.kind) {
    DraftKind.NEW_TOPIC -> if (draft.forumId > 0) "新主题 · 板块 ${draft.forumId}" else "新主题"
    DraftKind.REPLY -> "回复主题 ${draft.targetId}" + (draft.quote?.let { " · #${it.floor}" } ?: "")
    DraftKind.DM -> "私信用户 ${draft.targetId}"
}
