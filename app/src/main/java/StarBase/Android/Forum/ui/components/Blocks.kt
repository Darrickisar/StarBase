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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
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
    onImageClick: (String) -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(gapBefore(block)))
            BlockView(block, onLinkClick = onLinkClick, onImageClick = onImageClick)
        }
    }
}

private fun gapBefore(block: LiveBlock) = when (block.type) {
    LiveBlock.Type.HEADING -> 16.dp
    LiveBlock.Type.IMAGE -> 12.dp
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
    when (block.type) {
        // §05: 13.5-14.5sp with a ~1.8 line height - bodyMedium is 14/25.
        LiveBlock.Type.PARA -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textPrimary
        )

        LiveBlock.Type.HEADING -> Text(
            text = block.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tokens.textPrimary
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
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
                modifier = Modifier.weight(1f)
            )
        }

        LiveBlock.Type.CODE -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.codeBg)
                .padding(12.dp)
        ) {
            Text(
                text = block.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
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

        LiveBlock.Type.LIST_ITEM -> Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "·",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.accentWarm
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
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
            style = MaterialTheme.typography.bodyMedium.copy(
                textDecoration = TextDecoration.Underline
            ),
            color = tokens.accentGlow,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onLinkClick(block.href.ifBlank { block.text }) }
        )
    }
}
