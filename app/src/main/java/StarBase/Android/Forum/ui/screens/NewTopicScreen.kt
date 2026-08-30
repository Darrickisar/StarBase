package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.Parse
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.ui.ErrorPanel
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.LoadingMark
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * 发新帖.
 *
 * The boards come from the 发帖 page itself rather than from the board list: only
 * the ones that form offers can actually be posted to. Everything else about the
 * post - the csrf, the `id=0` that marks it new, the special-type field - rides
 * along from the form, so this screen only collects 板块 / 标题 / 正文.
 */

class NewTopicViewModel : ViewModel() {
    /** The boards the 发帖 form offers, as id to name. */
    var boards by mutableStateOf<Load<List<Pair<Int, String>>>>(Load.Loading)
        private set
    var posting by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set

    /** Set when the site will only take this post from a browser. */
    var browserPost by mutableStateOf("")
        private set

    private var inFlight = false

    fun load(force: Boolean = false) {
        if (boards is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            if (boards !is Load.Ready) boards = Load.Loading
            try {
                boards = Load.Ready(Api.newTopicBoards())
            } catch (e: Throwable) {
                boards = failureOf(e)
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * Posts, and reports the new topic's id when the site says it - a successful
     * 发帖 answers with a redirect to the topic that was just created.
     */
    fun post(forumId: Int, title: String, body: String, onPosted: (Int) -> Unit) {
        if (posting) return
        if (forumId <= 0) { notice = "先选一个板块"; return }
        if (title.isBlank()) { notice = "标题不能为空"; return }
        if (body.isBlank()) { notice = "正文不能为空"; return }

        posting = true
        viewModelScope.launch {
            try {
                val result = Api.newTopic(forumId, title, body)
                notice = result.message.ifBlank { "已发布" }
                onPosted(result.topicId)
            } catch (e: Api.NeedsBrowser) {
                browserPost = e.url
                notice = e.message.orEmpty()
            } catch (e: Throwable) {
                notice = e.message ?: "发布失败"
            } finally {
                posting = false
            }
        }
    }

    fun clearNotice() { notice = "" }
    fun browserPostHandled() { browserPost = "" }
}

@Composable
fun NewTopicScreen(
    vm: NewTopicViewModel,
    forumId: Int,
    onBack: () -> Unit,
    onPosted: (Int) -> Unit,
    onLogin: () -> Unit,
    onOpenSite: (String) -> Unit
) {
    LaunchedEffect(Unit) { vm.load() }

    LaunchedEffect(vm.browserPost) {
        val url = vm.browserPost
        if (url.isNotBlank()) {
            onOpenSite(url)
            vm.browserPostHandled()
        }
    }

    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    // 0 until the board list arrives; the caller's board wins when it sent one.
    var board by remember { mutableStateOf(forumId) }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(title = "发新帖", onBack = onBack)

        when (val s = vm.boards) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(s.message, s.kind, { vm.load(force = true) }, onLogin)
            is Load.Ready -> {
                // Whatever the site preselected, unless the caller named a board.
                LaunchedEffect(s.value) {
                    if (board <= 0) board = s.value.firstOrNull()?.first ?: 0
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .imePadding()
                ) {
                    if (vm.notice.isNotBlank()) {
                        NoticeBar(vm.notice) { vm.clearNotice() }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SectionHeader(title = "板块", subtitle = "发到哪里")
                        Gap(8)
                        BoardPicker(
                            boards = s.value,
                            selected = board,
                            onSelect = { board = it }
                        )

                        Gap(18)
                        SectionHeader(title = "标题")
                        Gap(8)
                        Field(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = "一句话说清楚这帖是什么",
                            singleLine = true
                        )

                        Gap(18)
                        SectionHeader(title = "正文")
                        Gap(8)
                        Field(
                            value = body,
                            onValueChange = { body = it },
                            placeholder = "正文支持站点的 Markdown",
                            singleLine = false,
                            minHeight = 180.dp
                        )
                        Gap(20)
                    }
                    PostBar(
                        posting = vm.posting,
                        enabled = board > 0 && title.isNotBlank() && body.isNotBlank(),
                        onPost = { vm.post(board, title, body, onPosted) }
                    )
                }
            }
        }
    }
}

/**
 * The boards to post to.
 *
 * Wrapped rather than side-scrolled: there are nine of them and picking one is the
 * point of this section, so a board that needs a swipe to discover is a board that
 * gets missed. Three per row fits the site's own board names.
 */
@Composable
private fun BoardPicker(
    boards: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = SbMetrics.pagePadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        boards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, name) ->
                    SegmentPill(
                        label = name,
                        selected = id == selected,
                        onClick = { onSelect(id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp
) {
    val tokens = LocalTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding)
            .liquidGlass(
                shape = RoundedCornerShape(SbRadius.field),
                level = GlassLevel.MEDIUM,
                refract = false
            )
            .heightIn(min = minHeight)
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textTertiary
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
            cursorBrush = SolidColor(tokens.accentWarm),
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PostBar(posting: Boolean, enabled: Boolean, onPost: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SbMetrics.pagePadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassButton(
            text = if (posting) "发布中" else "发布",
            onClick = {
                keyboard?.hide()
                onPost()
            },
            enabled = enabled && !posting,
            primary = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

