package StarBase.Android.Forum.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.ui.MediaFiles
import StarBase.Android.Forum.ui.SharedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val LocalPostImages = staticCompositionLocalOf<List<String>> { emptyList() }
val LocalRecognizeImage = staticCompositionLocalOf<((String) -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGallery(urls: List<String>, selected: String, onDismiss: () -> Unit) {
    val images = remember(urls) { urls.filter(String::isNotBlank).distinct() }
    if (images.isEmpty()) return
    val context = LocalContext.current
    val recognize = LocalRecognizeImage.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = images.indexOf(selected).coerceAtLeast(0)) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SharedImage?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val image = pending
        pending = null
        if (uri != null && image != null) scope.launch {
            runCatching { MediaFiles.save(context, image, uri) }
                .onSuccess { Toast.makeText(context, "图片已保存", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
        }
    }
    fun export(sharing: Boolean) {
        if (busy) return
        val url = images[pager.currentPage]
        scope.launch {
            busy = true
            try {
                val image = MediaFiles.download(context, url)
                if (sharing) MediaFiles.share(context, image)
                else {
                    pending = image
                    save.launch("StarBase-${System.currentTimeMillis()}.${image.file.extension}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "下载失败", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭图片", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${images.size}", color = Color.White, modifier = Modifier.weight(1f))
                if (recognize != null) TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("识别文字与二维码") } }, state = rememberTooltipState()
                ) {
                    IconButton(onClick = { val url = images[pager.currentPage]; onDismiss(); recognize(url) }, enabled = !busy) {
                        Icon(Icons.Outlined.DocumentScanner, "识别文字与二维码", tint = Color.White)
                    }
                }
                IconButton(onClick = { export(false) }, enabled = !busy) { Icon(Icons.Default.Download, "保存图片", tint = Color.White) }
                IconButton(onClick = { export(true) }, enabled = !busy) { Icon(Icons.Default.Share, "分享图片", tint = Color.White) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.weight(1f)) { page ->
                key(images[page], pager.currentPage == page) {
                    ZoomImage(images[page]) { if (pager.currentPage == page) zoomed = it }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                    Icon(Icons.Default.ChevronLeft, "上一张", tint = if (pager.currentPage > 0) Color.White else Color.Gray)
                }
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < images.lastIndex) {
                    Icon(Icons.Default.ChevronRight, "下一张", tint = if (pager.currentPage < images.lastIndex) Color.White else Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun ZoomImage(url: String, onZoom: (Boolean) -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var retry by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val transforms = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else Offset(
                (offset.x + pan.x).coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2),
                (offset.y + pan.y).coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2)
            )
            onZoom(scale > 1f)
        }
        SubcomposeAsyncImage(
            model = remember(url, retry) {
                ImageRequest.Builder(context).data(url).diskCachePolicy(CachePolicy.DISABLED)
                    .addHeader("User-Agent", Net.userAgent()).addHeader("Referer", "${Site.BASE}/").build()
            },
            contentDescription = "帖子图片", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(url) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                        onZoom(scale > 1f)
                    })
                }
                .transformable(transforms, canPan = { scale > 1f })
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            error = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("图片加载失败", color = Color.White)
                    IconButton(onClick = { retry++ }) { Icon(Icons.Default.Refresh, "重试图片", tint = Color.White) }
                }
            }
        )
    }
}
