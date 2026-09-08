package StarBase.Android.Forum.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.net.MediaLinks
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.ui.openInBrowser
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag

@Composable
fun VideoBlock(block: LiveBlock) {
    var playing by remember(block.src) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF141719))
            .clickable { playing = true }, contentAlignment = Alignment.Center
    ) {
        if (block.poster.isNotBlank()) PostImage(url = block.poster, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PlayArrow, "播放视频", tint = Color.White, modifier = Modifier.size(56.dp))
            Text(block.text.ifBlank { MediaLinks.provider(block.src) }, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    if (playing) VideoDialog(block) { playing = false }
}

@Composable
fun VideoDialog(block: LiveBlock, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var retry by remember(block.src) { mutableIntStateOf(0) }
    fun leaveFullScreen() {
        (customView?.parent as? ViewGroup)?.removeView(customView)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }
    Dialog(
        onDismissRequest = { if (customView != null) leaveFullScreen() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler { if (customView != null) leaveFullScreen() else onDismiss() }
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (customView != null) leaveFullScreen() else onDismiss() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭视频", tint = Color.White)
                }
                Text(block.text.ifBlank { "视频" }, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { leaveFullScreen(); retry++ }) {
                    Icon(Icons.Default.Refresh, "重新加载视频", tint = Color.White)
                }
                IconButton(onClick = { openInBrowser(context, block.src) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "在浏览器打开视频", tint = Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
              key(block.src, retry) {
                if (block.embedded) {
                    EmbeddedVideo(block.src,
                        onShowCustom = { view, callback ->
                            leaveFullScreen()
                            customView = view
                            customCallback = callback
                        }, onHideCustom = ::leaveFullScreen)
                } else NativeVideo(block)
              }
                customView?.let { full ->
                    AndroidView(factory = {
                        (full.parent as? ViewGroup)?.removeView(full)
                        full
                    }, modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun NativeVideo(block: LiveBlock) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(block.src) { mutableStateOf("") }
    var buffering by remember { mutableStateOf(true) }
    val player = remember(block.src) {
        val dataSource = OkHttpDataSource.Factory(Net.client).setDefaultRequestProperties(
            mapOf("User-Agent" to Net.userAgent(), "Referer" to "${Site.BASE}/")
        )
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build().apply {
            val mime = block.mediaType.ifBlank {
                when (MediaLinks.https(block.src)?.encodedPath?.substringAfterLast('.')?.lowercase()) {
                    "m3u8" -> MimeTypes.APPLICATION_M3U8
                    "mpd" -> MimeTypes.APPLICATION_MPD
                    else -> ""
                }
            }
            setMediaItem(MediaItem.Builder().setUri(block.src).apply { if (mime.isNotBlank()) setMimeType(mime) }.build())
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error = "视频播放失败：${e.errorCodeName}" }
                override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            })
            setHandleAudioBecomingNoisy(true)
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { PlayerView(it).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            this.player = player
            keepScreenOn = true
        } },
            update = { it.player = player }, modifier = Modifier.fillMaxSize())
        if (buffering && error.isBlank()) CircularProgressIndicator()
        if (error.isNotBlank()) PlayerError(error) { error = ""; player.prepare(); player.play() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedVideo(
    raw: String,
    onShowCustom: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustom: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val url = remember(raw) { MediaLinks.embed(raw) }
    var loading by remember(raw) { mutableStateOf(true) }
    var error by remember(raw) { mutableStateOf("") }
    val web = remember(raw) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = Net.userAgent()
        }
    }
    val show by rememberUpdatedState(onShowCustom)
    val hide by rememberUpdatedState(onHideCustom)
    fun load() {
        if (url == null) { error = "不支持这个视频播放器"; loading = false; return }
        loading = true
        error = ""
        val frame = Element(Tag.valueOf("iframe"), Site.BASE).attr("src", url)
            .attr("allow", "fullscreen; encrypted-media; picture-in-picture")
            .attr("allowfullscreen", "").attr("referrerpolicy", "origin")
            .attr("style", "position:absolute;inset:0;width:100%;height:100%;border:0")
        val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
            "<body style=\"margin:0;background:#000\">${frame.outerHtml()}</body></html>"
        web.loadDataWithBaseURL("${Site.BASE}/", html, "text/html", "UTF-8", null)
    }
    DisposableEffect(web, lifecycle) {
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
            override fun onPageFinished(view: WebView, url: String?) { loading = false }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                if (request.isForMainFrame || request.url.toString() == url) { error = "播放器加载失败"; loading = false }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame || request.url.toString() == url) {
                    error = "播放器加载失败：${response.statusCode}"
                    loading = false
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val next = request.url.toString()
                if (!request.isForMainFrame) return MediaLinks.https(next) == null
                if (next == "${Site.BASE}/" || next == "about:blank" || MediaLinks.embed(next) != null) return false
                if (request.hasGesture() && MediaLinks.https(next) != null) openInBrowser(context, next)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { show(view, callback) }
            override fun onHideCustomView() { hide() }
        }
        var suspended = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (!suspended) {
                        suspended = true
                        hide()
                        web.stopLoading()
                        // Clearing the document also stops audio in cross-origin player frames.
                        web.loadUrl("about:blank")
                        web.onPause()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    web.onResume()
                    if (suspended) { suspended = false; load() }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        var disposed = false
        // Providers size their player on first load, so wait for the native viewport.
        web.doOnLayout { if (!disposed) load() }
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
            hide()
            web.stopLoading()
            web.loadUrl("about:blank")
            web.onPause()
            web.removeAllViews()
            web.destroy()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        if (loading) CircularProgressIndicator()
        if (error.isNotBlank()) PlayerError(error, ::load)
    }
}

@Composable
private fun PlayerError(message: String, onRetry: () -> Unit) {
    Column(Modifier.background(Color.Black).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Color.White)
        IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试播放", tint = Color.White) }
    }
}
