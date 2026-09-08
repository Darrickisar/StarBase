package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.ui.theme.LocalTokens

/**
 * Renders a parsed post body.
 *
 * The parser has already reduced the site's HTML to a flat list of blocks, so
 * this is a straight switch - no HTML in the UI layer, and nothing executes.
 */
@Composable
fun PostBody(
    blocks: List<LiveBlock>,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {},
    onImageClick: ((String) -> Unit)? = null
) {
    var image by remember { mutableStateOf<String?>(null) }
    val inherited = LocalPostImages.current
    val images = remember(inherited, blocks) {
        (inherited + blocks.filter { it.type == LiveBlock.Type.IMAGE }.map { it.src }).distinct()
    }
    SelectionContainer {
      Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(gapBefore(block)))
            BlockView(block, onLinkClick = onLinkClick, onImageClick = { src ->
                if (onImageClick != null) onImageClick(src) else image = src
            })
        }
      }
    }
    image?.let { ImageGallery(images, it) { image = null } }
}

/**
 * A run of body text with its anchors live.
 *
 * The parser hands over the plain string plus ranges into it, so this only has to
 * paint those ranges and route their taps. It is one [Text], not a row of pieces:
 * a link has to wrap mid-sentence like any other word.
 */
@Composable
private fun LinkedText(
    block: LiveBlock,
    style: TextStyle,
    color: Color,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (block.links.isEmpty()) {
        Text(text = block.text, style = style, color = color, modifier = modifier)
        return
    }

    val tokens = LocalTokens.current
    val linkStyle = SpanStyle(
        color = tokens.accentGlow,
        textDecoration = TextDecoration.Underline
    )

    val annotated = remember(block, tokens.accentGlow) {
        buildAnnotatedString {
            var cursor = 0
            // Ranges arrive in document order; clamp anyway so a bad one cannot
            // throw here - a post body is untrusted input.
            for (link in block.links.sortedBy { it.start }) {
                val start = link.start.coerceIn(0, block.text.length)
                val end = link.end.coerceIn(start, block.text.length)
                if (start < cursor) continue
                append(block.text.substring(cursor, start))
                withLink(
                    LinkAnnotation.Clickable(
                        tag = link.href,
                        styles = TextLinkStyles(style = linkStyle),
                        linkInteractionListener = { onLinkClick(link.href) }
                    )
                ) {
                    append(block.text.substring(start, end))
                }
                cursor = end
            }
            append(block.text.substring(cursor))
        }
    }

    Text(text = annotated, style = style, color = color, modifier = modifier)
}

private fun gapBefore(block: LiveBlock) = when (block.type) {
    LiveBlock.Type.HEADING -> 16.dp
    LiveBlock.Type.IMAGE -> 12.dp
    LiveBlock.Type.VIDEO -> 12.dp
    LiveBlock.Type.CODE -> 12.dp
    LiveBlock.Type.QUOTE -> 12.dp
    LiveBlock.Type.RULE -> 16.dp
    LiveBlock.Type.LIST_ITEM -> 4.dp
    else -> 10.dp
}

@Composable
private fun BlockView(
    block: LiveBlock,
    onLinkClick: (String) -> Unit,
    onImageClick: (String) -> Unit
) {
    val tokens = LocalTokens.current
    val reader = LocalReaderPreferences.current
    fun reading(style: TextStyle) = style.copy(
        fontSize = style.fontSize * reader.fontScale,
        lineHeight = style.lineHeight * reader.fontScale * reader.lineHeightScale
    )
    when (block.type) {
        // §05: 13.5-14.5sp with a ~1.8 line height - bodyMedium is 14/25.
        LiveBlock.Type.PARA -> LinkedText(
            block = block,
            style = reading(MaterialTheme.typography.bodyMedium),
            color = tokens.textPrimary,
            onLinkClick = onLinkClick
        )

        LiveBlock.Type.HEADING -> LinkedText(
            block = block,
            style = reading(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)),
            color = tokens.textPrimary,
            onLinkClick = onLinkClick
        )

        // IntrinsicSize.Min lets the accent bar match the wrapped text's height.
        LiveBlock.Type.QUOTE -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.quoteBar)
            )
            Spacer(Modifier.width(10.dp))
            LinkedText(
                block = block,
                style = reading(MaterialTheme.typography.bodyMedium),
                color = tokens.textSecondary,
                onLinkClick = onLinkClick,
                modifier = Modifier.weight(1f)
            )
        }

        LiveBlock.Type.CODE -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.codeBg)
                .padding(12.dp)
        ) {
            val clipboard = LocalClipboardManager.current
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.ifBlank { "代码" }, style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary, modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.text)) }) {
                    Icon(Icons.Default.ContentCopy, "复制代码", tint = tokens.textSecondary)
                }
            }
            Text(
                text = block.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp * reader.fontScale,
                    lineHeight = 20.sp * reader.fontScale * reader.lineHeightScale
                ),
                color = tokens.textSecondary,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }

        LiveBlock.Type.IMAGE -> PostImage(
            url = block.src,
            alt = block.text,
            onClick = { onImageClick(block.src) }
        )

        LiveBlock.Type.VIDEO -> VideoBlock(block)

        LiveBlock.Type.LIST_ITEM -> Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "·",
                style = reading(MaterialTheme.typography.bodyMedium),
                color = tokens.accentWarm
            )
            Spacer(Modifier.width(8.dp))
            LinkedText(
                block = block,
                style = reading(MaterialTheme.typography.bodyMedium),
                color = tokens.textPrimary,
                onLinkClick = onLinkClick,
                modifier = Modifier.weight(1f)
            )
        }

        LiveBlock.Type.RULE -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.hairline)
        )

        LiveBlock.Type.LINK -> Text(
            text = block.text,
            style = reading(MaterialTheme.typography.bodyMedium).copy(
                textDecoration = TextDecoration.Underline
            ),
            color = tokens.accentGlow,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onLinkClick(block.href.ifBlank { block.text }) }
        )
    }
}
