package StarBase.Android.Forum.ui.components

import android.annotation.SuppressLint
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import StarBase.Android.Forum.net.CapChallenge
import StarBase.Android.Forum.net.CapTransport
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.capDocument
import StarBase.Android.Forum.ui.openInBrowser
import StarBase.Android.Forum.ui.theme.LocalTokens
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CapCaptchaDialog(
    challenge: CapChallenge,
    register: Boolean,
    onSolved: (String) -> Unit,
    onDismiss: () -> Unit,
    document: ((bridgeId: String) -> String)? = null,
    client: OkHttpClient? = null
) {
    val context = LocalContext.current
    val dark = !LocalTokens.current.light
    val solved by rememberUpdatedState(onSolved)
    val http = remember(client) { client ?: Net.capClient(context) }
    var attempt by remember(challenge) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        key(challenge, register, attempt) {
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf("") }
            var finished by remember { mutableStateOf(false) }
            val ports = remember { mutableListOf<WebMessagePort>() }
            val transport = remember { CapTransport(challenge, http) }
            val scope = rememberCoroutineScope()
            val requests = remember { mutableMapOf<String, Job>() }
            val pageUrl = if (register) Site.REGISTER else Site.LOGIN
            val bridgeId = remember { "starbase-cap:" + UUID.randomUUID() }
            val html = remember { document?.invoke(bridgeId) ?: capDocument(challenge, dark, bridgeId) }
            val web = remember {
                runCatching {
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = Net.userAgent()
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setGeolocationEnabled(false)
                        }
                        webViewClient = object : WebViewClient() {
                            private var connected = false

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                val url = request.url.toString()
                                if (request.isForMainFrame && request.method == "GET" && url == pageUrl) {
                                    return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                                }
                                if (request.method != "GET" || !transport.isAsset(url)) {
                                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
                                }
                                return try {
                                    val result = transport.execute(url, "GET")
                                    WebResourceResponse(result.mimeType, null, result.status, "CAP",
                                        mapOf("Access-Control-Allow-Origin" to Site.BASE, "Cache-Control" to "no-store"),
                                        result.bytes.inputStream())
                                } catch (_: Exception) {
                                    view.post {
                                        if (!finished) { loading = false; error = "验证组件加载失败，请检查网络后重试" }
                                    }
                                    WebResourceResponse("text/plain", "UTF-8", 502, "Unavailable", emptyMap(), "".byteInputStream())
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return false
                                if (request.hasGesture() && request.url.scheme == "https" && request.url.userInfo == null) {
                                    openInBrowser(context, request.url.toString())
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (connected || finished || url != pageUrl) return
                                connected = true
                                val channel = view.createWebMessageChannel()
                                ports.addAll(channel)
                                channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                                        if (finished) return
                                        val raw = message.data.orEmpty()
                                        if (raw.length > 512 * 1024) return
                                        val data = runCatching { JSONObject(raw) }.getOrNull() ?: return
                                        when (data.optString("type")) {
                                            "cancel" -> {
                                                val id = data.optString("id")
                                                transport.cancel(id)
                                                requests.remove(id)?.cancel()
                                            }
                                            "fetch" -> {
                                                val id = data.optString("id")
                                                if (id.isEmpty() || id.length > 20 || id in requests || requests.size >= 8) return
                                                requests[id] = scope.launch {
                                                    val reply = JSONObject().put("id", id)
                                                    try {
                                                        val result = withContext(Dispatchers.IO) {
                                                            transport.execute(data.optString("url"), data.optString("method"),
                                                                data.optString("body"), id)
                                                        }
                                                        reply.put("status", result.status).put("body", result.bytes.toString(Charsets.UTF_8))
                                                    } catch (e: CancellationException) { throw e }
                                                    catch (_: Exception) { reply.put("error", true) }
                                                    finally { requests.remove(id) }
                                                    if (!finished) runCatching { port.postMessage(WebMessage(reply.toString())) }
                                                }
                                            }
                                            "ready" -> { loading = false; error = "" }
                                            "error" -> { loading = false; error = "人机验证失败，请重试" }
                                            "solved" -> {
                                                val token = data.optString("token")
                                                if (CapChallenge.validToken(token)) {
                                                    finished = true
                                                    solved(token)
                                                } else {
                                                    loading = false
                                                    error = "验证结果无效，请重试"
                                                }
                                            }
                                        }
                                    }
                                })
                                view.postWebMessage(WebMessage(bridgeId, arrayOf(channel[1])), Site.BASE.toUri())
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (request.isForMainFrame && !finished) {
                                    loading = false
                                    error = "人机验证加载失败，请重试"
                                }
                            }
                        }
                        // Serve the isolated document locally under a real HTTPS origin for the message channel.
                        loadUrl(pageUrl)
                    }
                }.getOrNull()
            }
            DisposableEffect(web) {
                onDispose {
                    finished = true
                    transport.close()
                    ports.forEach { runCatching { it.close() } }
                    web?.stopLoading()
                    web?.destroy()
                }
            }
            LaunchedEffect(web) {
                if (web == null) {
                    loading = false
                    error = "无法打开验证组件，请更新系统 WebView 后重试"
                } else {
                    delay(45_000)
                    if (loading) { loading = false; error = "人机验证加载超时，请重试" }
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("人机验证", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭人机验证") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (web != null) AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().height(128.dp))
                    if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Outlined.Refresh, null)
                        Text("重新验证", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
