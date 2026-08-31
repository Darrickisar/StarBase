package StarBase.Android.Forum.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import org.json.JSONObject
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.WebViewCookieJar
import StarBase.Android.Forum.ui.components.StarTile
import StarBase.Android.Forum.ui.glass.GlassBackAction
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassField
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.GlassTabs
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.openInBrowser
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * §07 登录/注册入口 - 独立账号页.
 *
 * The screen is native: the fields, tabs, captcha row and buttons are the ones
 * §07/§08 describe, drawn in the same glass system as the rest of the app. The
 * submission is not. linux.sb protects /login and /register with a CSRF token,
 * an arithmetic captcha bound to a signed token, a proof-of-work challenge, a
 * honeypot field and Cloudflare in front of all of it. Re-implementing that
 * would mean defeating the site's own anti-automation, so instead the site's
 * real page is kept alive in an off-screen WebView and this screen drives it:
 * values are typed into the real inputs, the real submit button is clicked, and
 * the page's own JavaScript does the proof-of-work. The app never posts the
 * credentials itself and never stores them - only the resulting session cookie
 * is picked up, from the same CookieManager the OkHttp jar reads.
 *
 * Anything the bridge cannot handle (a Cloudflare interstitial, a form whose
 * fields we cannot find, an OAuth hop) reveals that same WebView full-screen:
 * 「使用网页登录」. Nothing is a dead end.
 */

/** The two modes the one page switches between (§07 模式切换). */
enum class AuthMode(
    val label: String,
    val heading: String,
    val note: String,
    val action: String
) {
    LOGIN("登录", "欢迎回来", "登录烧饼社区，继续你的内容。", "登录"),
    REGISTER("注册", "创建账号", "注册烧饼账号，加入社区的讨论。", "注册")
}

/**
 * Reads the live form: the captcha question, its status line, any error the page
 * is showing, and which field names this particular form actually uses.
 */
private const val SCRAPE = """
(function(){
  function txt(s){ var e = document.querySelector(s); return e ? e.textContent.trim() : ''; }
  var answer = document.querySelector('input[name=native_captcha_answer]');
  var form = answer ? answer.form : document.querySelector('.auth-panel form, .form-panel form, form[method=post]');
  var names = [];
  if (form) {
    var els = form.querySelectorAll('input,select,textarea');
    for (var i = 0; i < els.length; i++) { if (els[i].name) names.push(els[i].name); }
  }
  var note = '';
  var boxes = document.querySelectorAll('.form-panel .error, .form-panel .form-error, .form-panel .field-error, .form-panel .alert, .form-panel .tip, .auth-panel .error, .auth-panel .alert, .flash, .flash-message');
  for (var j = 0; j < boxes.length; j++) {
    var t = (boxes[j].textContent || '').trim();
    if (t.length > 0 && t.length < 90) { note = t; break; }
  }
  return {
    form: !!form,
    path: location.pathname,
    question: txt('.native-captcha-question'),
    status: txt('.native-captcha-status'),
    note: note,
    names: names.join(','),
    title: document.title
  };
})()
"""

/** Clicks the page's own 刷新验证码 control so a fresh signed token is issued. */
private const val REFRESH_CAPTCHA = """
(function(){
  var b = document.querySelector('[data-native-captcha-refresh], .native-captcha-refresh');
  if (b) { b.click(); return 'refreshing'; }
  location.reload();
  return 'reloading';
})()
"""

/** Clicks the page's own 发送验证码 button, whatever it happens to be called. */
private const val SEND_EMAIL_CODE = """
(function(){
  var all = document.querySelectorAll('button, a, input[type=button]');
  for (var i = 0; i < all.length; i++) {
    var t = (all[i].textContent || all[i].value || '').trim();
    if (t.indexOf('发送验证码') >= 0 || t.indexOf('获取验证码') >= 0) { all[i].click(); return 'sent'; }
  }
  return 'missing';
})()
"""

/**
 * Types the user's values into the real inputs and, when [submit] is set, clicks
 * the real submit button.
 *
 * Field lookup is name-tolerant on purpose: the login form is known
 * (`username`, `password`, `native_captcha_answer`), the register form is not,
 * so each field is matched against a list of plausible names and falls back to
 * matching by input type and order. Values are set through the native value
 * setter and followed by `input`/`change` events, which is what the page's own
 * validation listens for.
 */
private fun fillScript(
    user: String,
    mail: String,
    code: String,
    pass: String,
    again: String,
    answer: String,
    submit: Boolean
): String {
    val data = JSONObject()
        .put("user", user)
        .put("mail", mail)
        .put("code", code)
        .put("pass", pass)
        .put("again", again)
        .put("answer", answer)
        .put("submit", submit)
        .toString()
    return """
(function(){
  var d = $data;
  var cap = document.querySelector('input[name=native_captcha_answer]');
  var form = cap ? cap.form : document.querySelector('.auth-panel form, .form-panel form, form[method=post]');
  if (!form) return 'noform';
  function set(el, v){
    if (!el || !v) return false;
    var p = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
    if (p && p.set) { p.set.call(el, v); } else { el.value = v; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }
  function named(list){
    for (var i = 0; i < list.length; i++) {
      var e = form.querySelector('[name="' + list[i] + '"]');
      if (e) return e;
    }
    return null;
  }
  var pw = form.querySelectorAll('input[type=password]');
  var user = named(['username','user','account','login','name','email_or_username']);
  var mail = named(['email','mail','user_email','reg_email']) || form.querySelector('input[type=email]');
  var code = named(['email_code','code','verify_code','verification_code','email_verify_code','mail_code','email_captcha']);
  var pass = named(['password','pass','passwd']) || (pw.length > 0 ? pw[0] : null);
  var again = named(['password2','password_confirm','confirm_password','password_again','repassword','password_repeat','password_confirmation','confirm']) || (pw.length > 1 ? pw[1] : null);
  set(user, d.user);
  set(mail, d.mail);
  set(code, d.code);
  set(pass, d.pass);
  set(again, d.again);
  set(cap, d.answer);
  if (!d.submit) return 'filled';
  var btn = form.querySelector('button[type=submit], input[type=submit]');
  if (!btn) {
    var bs = form.querySelectorAll('button');
    for (var k = 0; k < bs.length; k++) {
      if (bs[k].getAttribute('type') !== 'button') { btn = bs[k]; break; }
    }
  }
  if (btn) { btn.click(); return 'submitted'; }
  form.submit();
  return 'posted';
})()
"""
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthScreen(
    startAtRegister: Boolean = false,
    onDone: (signedIn: Boolean) -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val rise = with(LocalDensity.current) { 5.dp.roundToPx() }

    var mode by remember {
        mutableStateOf(if (startAtRegister) AuthMode.REGISTER else AuthMode.LOGIN)
    }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var mail by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }

    // What the live page currently says.
    var question by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var pageNote by remember { mutableStateOf("") }
    var formFound by remember { mutableStateOf(false) }
    var challenged by remember { mutableStateOf(false) }

    var progress by remember { mutableStateOf(0) }
    var submitting by remember { mutableStateOf(false) }
    var submittedAt by remember { mutableStateOf(0L) }
    var hint by remember { mutableStateOf("") }
    var webShown by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    var loadSeq by remember { mutableStateOf(0) }
    var seqAtSubmit by remember { mutableStateOf(0) }


    /** Called for every page the auth WebView settles on. */
    fun onSettled(url: String) {
        val path = url.removePrefix(Site.BASE)
        val stillAuthing = path.startsWith("/login") ||
            path.startsWith("/register") ||
            path.startsWith("/password_recovery")
        when {
            !stillAuthing && WebViewCookieJar.hasSessionCookie() && !settled -> {
                settled = true
                onDone(true)
            }
            // The site answers a completed registration by sending you to the
            // login form, so the page switch is the success signal.
            stillAuthing && path.startsWith("/login") && mode == AuthMode.REGISTER -> {
                mode = AuthMode.LOGIN
                submitting = false
                pass = ""
                again = ""
                answer = ""
                code = ""
                hint = "注册流程已完成，请用新账号登录"
            }
        }
    }

    val webView = remember {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true          // the captcha and the PoW are JS
            domStorageEnabled = true
            userAgentString = Net.userAgent()
            // Never a cached login page: its _csrf, its arithmetic captcha and
            // its proof-of-work challenge are all one-shot, and a page replayed
            // from cache submits tokens the server has already retired.
            cacheMode = WebSettings.LOAD_NO_CACHE
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // OAuth leaves the site and comes back, so the hop needs its cookies.
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // This WebView exists to finish an authentication, and GitHub or
                // Google sign-in necessarily leaves linux.sb - so navigation is
                // allowed to follow, and the page is revealed while it does.
                if (!request.url.toString().startsWith(Site.BASE)) webShown = true
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress = 12
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress = 100
                loadSeq += 1
                onSettled(url)
            }
        }
        view.loadUrl(if (startAtRegister) Site.REGISTER else Site.LOGIN)
        view
    }

    fun run(js: String) = webView.evaluateJavascript(js, null)

    /** §07 模式切换: same container, the page behind it changes too. */
    fun switchTo(next: AuthMode) {
        if (next == mode || submitting) return
        mode = next
        answer = ""
        question = ""
        status = ""
        pageNote = ""
        hint = ""
        formFound = false
        progress = 12
        webView.loadUrl(if (next == AuthMode.REGISTER) Site.REGISTER else Site.LOGIN)
    }

    fun refreshCaptcha() {
        answer = ""
        status = ""
        run(REFRESH_CAPTCHA)
    }

    fun sendEmailCode() {
        if (mail.isBlank()) {
            hint = "请先填写邮箱地址"
            return
        }
        // The site issues the code against the values in its own form, so those
        // go in first and its own button does the sending.
        run(fillScript(user, mail, "", "", "", answer, submit = false))
        run(SEND_EMAIL_CODE)
        hint = "已请求发送验证码，请查收邮箱"
    }

    fun submit() {
        val problem = when {
            user.isBlank() -> if (mode == AuthMode.LOGIN) "请输入用户名或邮箱" else "请输入用户名"
            mode == AuthMode.REGISTER && mail.isBlank() -> "请输入邮箱"
            mode == AuthMode.REGISTER && code.isBlank() -> "请输入邮箱验证码"
            pass.isBlank() -> "请输入密码"
            mode == AuthMode.REGISTER && again != pass -> "两次输入的密码不一致"
            answer.isBlank() -> "请填写人机验证的计算结果"
            !agreed -> "请先确认服务条款与隐私说明"
            !formFound -> "站点页面还没准备好，可以改用网页登录"
            else -> ""
        }
        if (problem.isNotBlank()) {
            hint = problem
            return
        }
        hint = ""
        seqAtSubmit = loadSeq
        submittedAt = System.currentTimeMillis()
        submitting = true
        run(
            fillScript(
                user = user,
                mail = if (mode == AuthMode.REGISTER) mail else "",
                code = if (mode == AuthMode.REGISTER) code else "",
                pass = pass,
                again = if (mode == AuthMode.REGISTER) again else "",
                answer = answer,
                submit = true
            )
        )
    }

    // The page is the source of truth for the captcha, its status line and any
    // error, so it is read on a slow tick rather than through a JS bridge.
    LaunchedEffect(webView) {
        while (true) {
            webView.evaluateJavascript(SCRAPE) { raw ->
                val page = runCatching { JSONObject(raw) }.getOrNull()
                if (page != null) {
                    formFound = page.optBoolean("form", false)
                    question = page.optString("question", "")
                    status = page.optString("status", "")
                    pageNote = page.optString("note", "")
                    // No form after a finished load means the site put something
                    // else in front of it - a security check, most likely.
                    challenged = !formFound && progress >= 100
                    if (submitting && loadSeq > seqAtSubmit) {
                        submitting = false
                        answer = ""
                        hint = pageNote.ifBlank { "提交没有通过，请检查填写内容后重试" }
                    }
                }
            }
            if (submitting && System.currentTimeMillis() - submittedAt > 20_000L) {
                submitting = false
                hint = "站点还没有返回结果，可以改用网页登录继续"
            }
            delay(650)
        }
    }

    // Nothing native can be done about a security check, so the real page takes
    // over instead of leaving the user on a form that cannot submit.
    LaunchedEffect(challenged) {
        if (challenged && !webShown) {
            delay(1200)
            if (challenged) {
                webShown = true
                hint = "站点正在做安全校验，请在网页中完成"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        when {
            webShown && webView.canGoBack() -> webView.goBack()
            webShown -> webShown = false
            else -> onDone(WebViewCookieJar.hasSessionCookie())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SbMetrics.pagePadding)
        ) {
            Spacer(Modifier.height(8.dp))
            // 1 返回我的 - a light button, not a bar in a card.
            GlassBackAction(
                text = "返回我的",
                onClick = { onDone(WebViewCookieJar.hasSessionCookie()) }
            )

            Spacer(Modifier.height(26.dp))
            // 2 品牌标识
            BrandMark()

            Spacer(Modifier.height(22.dp))
            // 3 页标题 + 4 页说明
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(260)) { rise })
                        .togetherWith(fadeOut(tween(160)))
                },
                label = "auth-heading"
            ) { current ->
                Column {
                    Text(
                        text = current.heading,
                        style = MaterialTheme.typography.headlineMedium,
                        color = tokens.textPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = current.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            // 5 模式切换
            GlassTabs(
                labels = AuthMode.entries.map { it.label },
                selected = mode.ordinal,
                onSelect = { switchTo(AuthMode.entries[it]) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            // 6/7 字段 - one container per §08, placeholders carry the names.
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(280)) + slideInVertically(tween(280)) { rise })
                        .togetherWith(fadeOut(tween(170)))
                },
                label = "auth-form"
            ) { current ->
                GlassPanel(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
                    if (current == AuthMode.LOGIN) {
                        GlassField(
                            value = user,
                            onValue = { user = it },
                            placeholder = "用户名或邮箱",
                            glyph = "号",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassField(
                            value = pass,
                            onValue = { pass = it },
                            placeholder = "密码",
                            glyph = "密",
                            password = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // §7.2 层级 4-7 的顺序：用户名 → 邮箱地址 → 设置密码 →
                        // 验证码。确认密码跟在密码后面，因为站点的注册表单
                        // 本身要求它。
                        GlassField(
                            value = user,
                            onValue = { user = it },
                            placeholder = "用户名（不超过20个汉字或英文）",
                            glyph = "名",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassField(
                            value = mail,
                            onValue = { mail = it },
                            placeholder = "邮箱地址",
                            glyph = "邮",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassField(
                            value = pass,
                            onValue = { pass = it },
                            placeholder = "设置密码",
                            glyph = "密",
                            password = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassField(
                            value = again,
                            onValue = { again = it },
                            placeholder = "确认密码",
                            glyph = "复",
                            password = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        // §8.1 验证码: one input plus one small button, one row.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassField(
                                value = code,
                                onValue = { code = it },
                                placeholder = "6 位邮箱验证码",
                                glyph = "码",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            GlassButton(
                                text = "获取验证码",
                                onClick = { sendEmailCode() },
                                primary = false,
                                compact = true,
                                modifier = Modifier.width(96.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 人机验证 - the site's own question, answered here.
            CaptchaBlock(
                question = question,
                status = status,
                answer = answer,
                onAnswer = { answer = it },
                onRefresh = { refreshCaptcha() },
                onSubmit = { submit() }
            )

            if (hint.isNotBlank() || pageNote.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                HintBar(text = hint.ifBlank { pageNote })
            }

            Spacer(Modifier.height(14.dp))
            // 8 服务确认
            ConfirmRow(checked = agreed, onToggle = { agreed = !agreed })

            Spacer(Modifier.height(16.dp))
            // 9 主操作
            GlassButton(
                text = if (submitting) "正在提交…" else mode.action,
                onClick = { submit() },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            // 10 切换入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (mode == AuthMode.LOGIN) "没有账号？" else "已有账号？",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary
                )
                Text(
                    text = if (mode == AuthMode.LOGIN) "去注册" else "去登录",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.accentWarm,
                    modifier = Modifier
                        .clip(RoundedCornerShape(SbRadius.small))
                        .clickable {
                            switchTo(
                                if (mode == AuthMode.LOGIN) AuthMode.REGISTER else AuthMode.LOGIN
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
                if (mode == AuthMode.LOGIN) {
                    Text(
                        text = "忘记密码？",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(SbRadius.small))
                            .clickable {
                                webShown = true
                                webView.loadUrl("${Site.BASE}/password_recovery_forgot")
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            OAuthRow(
                onProvider = { provider ->
                    webShown = true
                    webView.loadUrl("${Site.BASE}/oauth_login?provider=$provider")
                }
            )

            Spacer(Modifier.height(20.dp))
            // 11 底部说明
            Text(
                text = "登录入口只出现在三个地方：“我的”页的身份卡、“我的内容”里的受限项，" +
                    "以及帖子评论区的参与入口。账号与验证码由 linux.sb 官方页面校验，App 不保存密码。",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "使用网页登录",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SbRadius.small))
                    .clickable { webShown = true }
                    .padding(vertical = 8.dp)
            )
            Spacer(Modifier.height(28.dp))
        }

        // The site's own page. Off-screen while the native form drives it,
        // full-screen the moment anything needs the user to see the real thing.
        Box(
            modifier = if (webShown) {
                Modifier
                    .fillMaxSize()
                    .background(tokens.base)
            } else {
                Modifier
                    .size(1.dp)
                    .alpha(0f)
            }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (webShown) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "返回",
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.accentWarm,
                            modifier = Modifier
                                .clip(RoundedCornerShape(SbRadius.small))
                                .clickable { webShown = false }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                        Text(
                            text = "linux.sb 官方页面",
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.textPrimary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 6.dp)
                        )
                        Text(
                            text = "浏览器",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(SbRadius.small))
                                .clickable { openInBrowser(context, webView.url ?: Site.LOGIN) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = tokens.accentWarm,
                            trackColor = tokens.hairline
                        )
                    }
                }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** §07 品牌标识: the StarBase mark over a spaced-out account line. */
@Composable
private fun BrandMark() {
    val tokens = LocalTokens.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StarTile(size = 46.dp, corner = SbRadius.field)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "COMMUNITY ACCOUNT",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.4.sp
            ),
            color = tokens.textTertiary
        )
    }
}

/**
 * 人机验证 as the site poses it: the question comes off the live page, the answer
 * is typed here, and 刷新验证码 clicks the page's own control so the signed token
 * and the question stay in step.
 */
@Composable
private fun CaptchaBlock(
    question: String,
    status: String,
    answer: String,
    onAnswer: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit
) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        level = GlassLevel.LOW,
        shape = RoundedCornerShape(SbRadius.button),
        padding = 13.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "人机验证",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "请输入计算结果",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "刷新验证码",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.accentWarm,
                modifier = Modifier
                    .clip(RoundedCornerShape(SbRadius.small))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .liquidGlass(
                        shape = RoundedCornerShape(SbRadius.field),
                        level = GlassLevel.HIGH,
                        refract = false,
                        tint = tokens.accentWarm.copy(alpha = 0.08f)
                    )
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                Text(
                    text = question.ifBlank { "正在取题" },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (question.isBlank()) tokens.textTertiary else tokens.accentGlow
                )
            }
            Spacer(Modifier.width(10.dp))
            GlassField(
                value = answer,
                onValue = onAnswer,
                placeholder = "答案",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                imeAction = ImeAction.Done,
                onSubmit = onSubmit,
                modifier = Modifier.weight(1f)
            )
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary
            )
        }
    }
}

/** §07 服务确认: one checkbox, one line of text, no legal wall. */
@Composable
private fun ConfirmRow(checked: Boolean, onToggle: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SbRadius.small))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (checked) tokens.accentWarm.copy(alpha = 0.9f)
                    else Color.Transparent
                )
                .liquidGlass(
                    shape = RoundedCornerShape(6.dp),
                    level = GlassLevel.LOW,
                    refract = false,
                    outline = !checked
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.base
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = "我已阅读并同意社区的服务条款与隐私说明",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textSecondary
        )
    }
}

/** Whatever the page or the form wants to say, in one line. */
@Composable
private fun HintBar(text: String) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = RoundedCornerShape(SbRadius.field),
                level = GlassLevel.LOW,
                refract = false,
                tint = tokens.hotTint.copy(alpha = 0.08f)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.hotTint
        )
    }
}

/** OAuth 登录 - the two providers the site offers. */
@Composable
private fun OAuthRow(onProvider: (String) -> Unit) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "OAuth 登录",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                text = "GitHub",
                onClick = { onProvider("github") },
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                text = "Google",
                onClick = { onProvider("google") },
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
