package StarBase.Android.Forum.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.components.StarMark
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.theme.LocalTokens

/** What a screen's async content can be in. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String, val kind: SiteException.Kind) : Load<Nothing>
}

/**
 * The "how current is this" line under a section header.
 *
 * Says when the data actually came off the wire rather than claiming it is
 * live, because between two loads it is exactly as old as this says.
 */
fun freshnessText(ageSeconds: Long, refreshing: Boolean): String = when {
    refreshing -> "正在获取最新内容"
    ageSeconds < 10 -> "刚刚从 linux.sb 获取"
    ageSeconds < 60 -> "$ageSeconds 秒前获取 · 下拉刷新"
    else -> "${ageSeconds / 60} 分钟前获取 · 下拉刷新"
}

/** The short form, for places that already say what the content is. */
fun ageLabel(ageSeconds: Long): String = when {
    ageSeconds < 10 -> "刚刚更新"
    ageSeconds < 60 -> "$ageSeconds 秒前"
    else -> "${ageSeconds / 60} 分钟前"
}

/** Turns a thrown exception into a [Load.Failed] with a readable message. */
fun failureOf(e: Throwable): Load.Failed = when (e) {
    is SiteException -> Load.Failed(e.message ?: "出错了", e.kind)
    else -> Load.Failed(e.message ?: "出错了", SiteException.Kind.SERVER)
}

/** The pulsing StarBase mark used while a screen loads. */
@Composable
fun LoadingMark(label: String = "正在加载") {
    val tokens = LocalTokens.current
    val transition = rememberInfiniteTransition(label = "mark")
    val scale by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val fade by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fade"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .scale(scale)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.accentWarm.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            StarMark(
                modifier = Modifier.alpha(fade),
                size = 30.dp,
                tint = tokens.accentGlow
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textTertiary
        )
    }
}

/** Failure panel with a retry, and a login button when the cause was auth. */
@Composable
fun ErrorPanel(
    message: String,
    kind: SiteException.Kind = SiteException.Kind.SERVER,
    onRetry: () -> Unit,
    onLogin: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(tokens.hotTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (kind) {
                    SiteException.Kind.NETWORK -> "⚡"
                    SiteException.Kind.AUTH -> "锁"
                    else -> "!"
                },
                style = MaterialTheme.typography.titleMedium,
                color = tokens.hotTint
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction(text = "重试", primary = onLogin == null, onClick = onRetry)
            if (onLogin != null) {
                SmallAction(text = "去登录", primary = true, onClick = onLogin)
            }
        }
    }
}

/**
 * Compact pill button used by panels and empty states. It is the same material
 * as every other button in V5 - amber fill for the primary, glass for the rest -
 * so a retry inside an error panel does not look like a different widget.
 */
@Composable
fun SmallAction(text: String, primary: Boolean, onClick: () -> Unit) {
    GlassButton(text = text, onClick = onClick, primary = primary, compact = true)
}

/** Neutral empty state. */
@Composable
fun EmptyPanel(text: String, hint: String = "") {
    val tokens = LocalTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = tokens.textPrimary
        )
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Footer for an infinite list: spinner, "load more", or "that's all". */
@Composable
fun ListFooter(
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit
) {
    val tokens = LocalTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Text(
                text = "正在加载…",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textTertiary
            )
            hasMore -> Text(
                text = "加载更多",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.accentGlow,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onLoadMore)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
            else -> Text(
                text = "已经到底了",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }
    }
}

/** Full-screen container that shows loading / error / content. */
@Composable
fun <T> LoadFrame(
    state: Load<T>,
    onRetry: () -> Unit,
    onLogin: (() -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is Load.Loading -> LoadingMark()
            is Load.Failed -> ErrorPanel(
                message = state.message,
                kind = state.kind,
                onRetry = onRetry,
                onLogin = if (state.kind == SiteException.Kind.AUTH) onLogin else null
            )
            is Load.Ready -> content(state.value)
        }
    }
}

/** Thin divider used between rows. */
@Composable
fun Hairline(startInset: Int = 0) {
    val tokens = LocalTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startInset.dp)
            .height(1.dp)
            .background(tokens.hairline)
    )
}

/** Small spacer helper to keep call sites terse. */
@Composable
fun Gap(height: Int) = Spacer(Modifier.height(height.dp))

@Composable
fun GapW(width: Int) = Spacer(Modifier.width(width.dp))
