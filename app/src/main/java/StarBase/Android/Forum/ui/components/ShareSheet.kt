package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.ShareCard
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbRadius

/**
 * 分享成图: the sheet that previews the card and sends it.
 *
 * The card below is a real composable, drawn into a [rememberGraphicsLayer] and
 * read back as a bitmap. That is why what you preview is exactly what gets
 * shared - there is no second drawing path for the export, and no screenshot.
 *
 * The card deliberately does not carry the reader's own identity: it shows the
 * post's author, the board and the topic, because those are already public on the
 * site. It does not show who shared it.
 */
@Composable
fun SharePostSheet(
    post: Post,
    topicTitle: String,
    forumName: String,
    topicId: Int,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = LocalTokens.current
    val layer = rememberGraphicsLayer()
    var working by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // A tap on the scrim closes, but it must not fall through to the
            // thread underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
                // Swallow taps on the sheet itself.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "分享成图",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Gap(4)
            Text(
                text = "预览就是导出的图，没有状态栏和多余的半条内容",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f)
            )
            Gap(14)

            // The card, recorded as it draws so the bitmap and the preview cannot
            // drift apart.
            Box(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    }
            ) {
                PostCard(
                    post = post,
                    topicTitle = topicTitle,
                    forumName = forumName
                )
            }

            Gap(16)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    text = "取消",
                    onClick = onDismiss,
                    primary = false,
                    compact = true,
                    enabled = !working
                )
                GlassButton(
                    text = if (working) "生成中" else "分享图片",
                    onClick = {
                        if (working) return@GlassButton
                        working = true
                        scope.launch {
                            try {
                                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                                val file = ShareCard.write(
                                    context,
                                    bitmap,
                                    ShareCard.nameFor(topicId, post.floor)
                                )
                                ShareCard.share(context, file, topicTitle)
                                onDismiss()
                            } catch (e: Throwable) {
                                onNotice(e.message ?: "生成图片失败")
                            } finally {
                                working = false
                            }
                        }
                    },
                    primary = true,
                    compact = true,
                    enabled = !working
                )
            }
            Gap(24)
        }
    }
}

/**
 * The card itself.
 *
 * Drawn with explicit colours rather than the theme's glass tokens: this leaves
 * the app as a standalone image, so it has to be legible wherever it lands and
 * cannot depend on a translucent panel sampling a backdrop that will not be there.
 */
@Composable
private fun PostCard(post: Post, topicTitle: String, forumName: String) {
    val ink = Color(0xFF141118)
    val paper = Color(0xFFFBF8F3)
    val warm = Color(0xFFE0A24C)
    val muted = Color(0xFF6B6470)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    0f to paper,
                    1f to Color(0xFFF3EDE4)
                )
            )
            .padding(20.dp)
    ) {
        // Board and floor, small, at the top - the card's own dateline.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (forumName.isNotBlank()) {
                Text(
                    text = forumName,
                    style = MaterialTheme.typography.labelSmall,
                    color = warm,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = if (post.isOpening) "主楼" else "${post.floor} 楼",
                style = MaterialTheme.typography.labelSmall,
                color = muted
            )
        }

        Gap(10)
        Text(
            text = topicTitle,
            style = MaterialTheme.typography.titleMedium,
            color = ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Gap(14)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ink.copy(alpha = 0.10f))
        )
        Gap(14)

        // The body, capped. A card is a quote, not a reproduction of the thread -
        // and an unbounded one would render a 400-line post into an image nothing
        // will display.
        val body = post.plainText.trim()
        Text(
            text = if (body.length > BODY_CAP) body.take(BODY_CAP).trimEnd() + "…" else body,
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = 0.88f)
        )

        Gap(16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = post.author,
                    style = MaterialTheme.typography.labelLarge,
                    color = ink,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (post.timeText.isNotBlank()) {
                    Text(
                        text = post.timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // Where it came from. A card with no source is just a screenshot of
            // some words.
            Column(horizontalAlignment = Alignment.End) {
                StarMark(size = 20.dp, tint = warm)
                Gap(3)
                Text(
                    text = "linux.sb",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )
            }
        }
    }
}

/** Long enough for a real point, short enough to stay a card. */
private const val BODY_CAP = 900
