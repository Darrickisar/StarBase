package StarBase.Android.Forum.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.ui.theme.LocalTokens

/**
 * The site's own sign-in pages. A page we open in here is allowed to redirect
 * anywhere on the site except these: the app has one login entry, and letting a
 * WebView draw the site's login form would be a second one.
 */
private val authPaths = listOf("/login", "/register", "/password_recovery")

/** True for the site's own login, register and password pages. */
fun isSiteAuthUrl(raw: String): Boolean {
    val path = raw.removePrefix(Site.BASE).substringBefore('?').substringBefore('#')
    return authPaths.any { path == it || path.startsWith("$it/") }
}

/** Full-screen WebView for site pages the app has no native screen for. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SitePageScreen(
    title: String,
    url: String,
    onBack: () -> Unit,
    onLogin: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    var progress by remember { mutableStateOf(0) }

    // The client below is built once with the webView, so it reads the callback
    // through a box rather than closing over the first composition's copy.
    val login = remember { mutableStateOf(onLogin) }
    login.value = onLogin

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = Net.userAgent()
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val target = request.url.toString()
                    return when {
                        isSiteAuthUrl(target) -> {
                            login.value()
                            true
                        }
                        target.startsWith(Site.BASE) -> false
                        else -> {
                            openInBrowser(view.context, target)
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    // A 302 from the server never reaches shouldOverrideUrlLoading,
                    // and 称号馆 while signed out is exactly that redirect.
                    if (isSiteAuthUrl(url)) {
                        view.stopLoading()
                        login.value()
                        return
                    }
                    progress = 12
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progress = 100
                }
            }
            loadUrl(url)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler { if (webView.canGoBack()) webView.goBack() else onBack() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = "返回",
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.accentGlow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable(onClick = onBack)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "浏览器",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { openInBrowser(context, url) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                AnimatedVisibility(visible = progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = tokens.accentGlow,
                        trackColor = tokens.hairline
                    )
                }
            }
        }
    ) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
