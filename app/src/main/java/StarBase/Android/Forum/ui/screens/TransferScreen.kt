package StarBase.Android.Forum.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.*
import StarBase.Android.Forum.ui.theme.LocalTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun TransferScreen(store: UserStore, accountId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedBackup?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri != null && text != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "w")) { "无法写入备份" }
                        .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                notice = "备份已导出"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "导出失败" }
            finally { busy = false }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取备份" }.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(output.size() + n <= LocalBackup.MAX_BYTES) { "备份超过 8 MB" }
                            output.write(buffer, 0, n)
                        }
                        output.toString("UTF-8")
                    }
                }
                prepared = BackupCoordinator.prepare(text, accountId)
                notice = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "无法读取这份备份" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        DetailBar("本机数据迁移", onBack = onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (accountId > 0) "UID $accountId" else "访客", style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary)
            TransferCount("浏览历史", store.history.size)
            TransferCount("阅读位置", store.readMarks.size)
            TransferCount("追帖", store.readMarks.count { it.watched })
            TransferCount("屏蔽规则", store.blockRules.size)
            TransferCount("本机提醒", store.reminders.size)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotBlank()) Text(notice, color = tokens.textPrimary)
            Button(onClick = {
                runCatching { BackupCoordinator.snapshot(context, store, accountId) }
                    .onSuccess {
                        pendingExport = it
                        export.launch("StarBase-backup-${System.currentTimeMillis()}.json")
                    }.onFailure { notice = it.message ?: "备份失败" }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("导出备份")
            }
            OutlinedButton(onClick = { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("选择备份")
            }
            HorizontalDivider()
            OutlinedButton(onClick = {
                val intent = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${context.packageName}"))
                } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }.onFailure { notice = "无法打开系统设置" }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("默认打开 linux.sb 链接")
            }
        }
    }
    prepared?.let { backup ->
        AlertDialog(onDismissRequest = { if (!busy) prepared = null }, title = { Text("合并导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferCount("浏览历史", backup.data.history.size)
                    TransferCount("阅读位置", backup.data.reading.size)
                    TransferCount("屏蔽规则", backup.data.blocks.size)
                    TransferCount("提醒", backup.data.reminders.size)
                    TransferCount("草稿", backup.draftCount)
                    TransferCount("保存的搜索", backup.searches?.saved?.size ?: 0)
                }
            }, confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            BackupCoordinator.restore(context, store, backup, accountId)
                            prepared = null
                            notice = "导入完成"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { notice = e.message ?: "导入失败"; prepared = null }
                        finally { busy = false }
                    }
                }, enabled = !busy) { Text("合并导入") }
            }, dismissButton = { TextButton(onClick = { prepared = null }, enabled = !busy) { Text("取消") } })
    }
}

@Composable
private fun TransferCount(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = LocalTokens.current.textSecondary)
        Text(count.toString(), color = LocalTokens.current.textPrimary)
    }
}
