package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.ui.IncomingLinks

@Composable
fun SharedTextScreen(text: String, onBack: () -> Unit, onSearch: (String) -> Unit, onDraft: (String) -> Unit) {
    var value by rememberSaveable(text) { mutableStateOf(text) }
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        DetailBar(title = "分享的文字", onBack = onBack)
        OutlinedTextField(
            value = value, onValueChange = { value = it.take(IncomingLinks.TEXT_LIMIT) },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            label = { Text("文字") }
        )
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSearch(value) }, enabled = value.isNotBlank(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Search, null)
                Text("搜索社区")
            }
            OutlinedButton(onClick = { onDraft(value) }, enabled = value.isNotBlank(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.EditNote, null)
                Text("新建草稿")
            }
        }
    }
}
