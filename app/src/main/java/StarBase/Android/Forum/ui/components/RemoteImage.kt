package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site

/**
 * Images are fetched from linux.sb only - never a third party - and every
 * request carries the same UA and cookie jar as the rest of the client so it
 * looks like the one browser session it is.
 *
 * Nothing is written to disk: [CachePolicy.DISABLED] on the disk cache keeps a
 * closed app from holding pictures of what was read in it, which is the same
 * rule the rest of the client follows. The in-memory cache stays on, because
 * that is what stops one avatar being fetched again on every scroll - it dies
 * with the process.
 */
private fun request(context: android.content.Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(Site.absolute(url))
        .addHeader("User-Agent", Net.userAgent())
        .addHeader("Referer", "${Site.BASE}/")
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(true)
        .build()

/**
 * A user avatar: the real one from the site when the page gave us a URL, and the
 * locally drawn initial disc while it loads or if it fails.
 */
@Composable
fun UserAvatar(
    name: String,
    url: String = "",
    size: Dp = 40.dp,
    ring: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = CircleShape
    val base = Modifier
        .size(size)
        .clip(shape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }

    if (url.isBlank()) {
        Box(modifier = base) { Avatar(name = name, size = size, ring = ring) }
        return
    }

    val context = LocalContext.current
    Box(modifier = base, contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = request(context, url),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
            loading = { Avatar(name = name, size = size, ring = ring) },
            error = { Avatar(name = name, size = size, ring = ring) }
        )
    }
}

/** An image inside a post body. Keeps its own aspect ratio, capped in height. */
@Composable
fun PostImage(
    url: String,
    alt: String = "",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = request(context, url),
            contentDescription = alt.ifBlank { "图片" },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp, max = 420.dp),
            loading = {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "图片加载中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "图片加载失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}
