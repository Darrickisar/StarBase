package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Text as CommonMarkText
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import StarBase.Android.Forum.ui.theme.LocalTokens

object MarkdownPreviewRenderer {
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
    fun parse(markdown: String): Node = parser.parse(markdown)
    fun html(markdown: String): String = renderer.render(parse(markdown))

    fun link(raw: String): String? = runCatching {
        val base = URI("https://linux.sb/")
        val uri = base.resolve(raw)
        uri.takeIf { it.scheme.lowercase() in setOf("http", "https", "mailto") }?.toASCIIString()
    }.getOrNull()

    fun image(raw: String): String? = link(raw)?.takeIf {
        val uri = URI(it)
        uri.scheme == "https" && uri.host.equals("linux.sb", true) && uri.userInfo == null
    }
}

@Composable
fun MarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    val document = remember(markdown) { MarkdownPreviewRenderer.parse(markdown) }
    SelectionContainer(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (markdown.isBlank()) Text("暂无内容", color = LocalTokens.current.textTertiary,
                style = MaterialTheme.typography.bodyMedium)
            MarkdownBlocks(document)
        }
    }
}

private fun children(node: Node): List<Node> = buildList {
    var child = node.firstChild
    while (child != null) { add(child); child = child.next }
}

@Composable
private fun MarkdownBlocks(parent: Node, depth: Int = 0) {
    val tokens = LocalTokens.current
    for (node in children(parent)) {
        when (node) {
            is Heading -> MarkdownInline(node, MaterialTheme.typography.titleSmall.copy(
                fontSize = when (node.level) { 1 -> 22.sp; 2 -> 20.sp; else -> 17.sp },
                fontWeight = FontWeight.SemiBold
            ))
            is Paragraph -> MarkdownInline(node)
            is FencedCodeBlock -> MarkdownCode(node.literal)
            is IndentedCodeBlock -> MarkdownCode(node.literal)
            is HtmlBlock -> Text(node.literal, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
            is ThematicBreak -> HorizontalDivider(color = tokens.hairline)
            is BlockQuote -> Column(
                Modifier.fillMaxWidth().background(tokens.codeBg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { MarkdownBlocks(node, depth + 1) }
            is BulletList, is OrderedList -> {
                children(node).forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (node is OrderedList) "${node.startNumber + index}." else "•",
                            color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MarkdownBlocks(item, depth + 1)
                        }
                    }
                }
            }
            else -> if (node.firstChild != null) MarkdownBlocks(node, depth + 1)
        }
    }
}

@Composable
private fun MarkdownCode(code: String) {
    Text(code.trimEnd('\n'), color = LocalTokens.current.textSecondary,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().background(LocalTokens.current.codeBg)
            .padding(12.dp).horizontalScroll(rememberScrollState()))
}

@Composable
private fun MarkdownInline(node: Node, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val tokens = LocalTokens.current
    val imageNodes = remember(node) { buildList<Image> {
        fun visit(parent: Node) { children(parent).forEach { if (it is Image) add(it) else visit(it) } }
        visit(node)
    } }
    val text = remember(node, tokens) {
        buildAnnotatedString {
            fun visit(parent: Node) {
                children(parent).forEach { child ->
                    when (child) {
                        is CommonMarkText -> append(child.literal)
                        is SoftLineBreak -> append(" ")
                        is HardLineBreak -> append("\n")
                        is HtmlInline -> append(child.literal)
                        is Code -> {
                            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = tokens.codeBg))
                            append(child.literal)
                            pop()
                        }
                        is Image -> if (MarkdownPreviewRenderer.image(child.destination) == null) visit(child)
                        else -> {
                            var pushed = false
                            when (child) {
                                is StrongEmphasis -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); pushed = true }
                                is Emphasis -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); pushed = true }
                                is Link -> MarkdownPreviewRenderer.link(child.destination)?.let { url ->
                                    pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(
                                        color = tokens.accentGlow, textDecoration = TextDecoration.Underline
                                    ))))
                                    pushed = true
                                }
                            }
                            visit(child)
                            if (pushed) pop()
                        }
                    }
                }
            }
            visit(node)
        }
    }
    if (text.isNotEmpty()) Text(text, style = style, color = tokens.textPrimary)
    imageNodes.forEach { image ->
        val url = MarkdownPreviewRenderer.image(image.destination)
        if (url != null) PostImage(url = url, alt = plainText(image))
    }
}

private fun plainText(node: Node): String = children(node).joinToString("") {
    if (it is CommonMarkText) it.literal else plainText(it)
}
