package StarBase.Android.Forum.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.Parse
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.components.StarTile
import StarBase.Android.Forum.ui.components.CapCaptchaDialog
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
 * Credentials and submission use the native network client. CAP verification
 * runs in a visible, isolated WebView using the site's own widget; only its
 * one-use verification result returns to this form. Legacy arithmetic forms
 * still render their question inline.
 *
 * The app posts the credentials but never stores them; what it keeps is the
 * session cookie, in the same CookieManager the OkHttp jar reads. Anything the
 * native form cannot do - OAuth, 忘记密码 - opens the site in the browser.
 */

/** The two modes the one page switches between (§07 模式切换). */
enum class AuthMode(
    val label: String,
    val heading: String,
    val note: String,
    val action: String
) {
    LOGIN("登录", "欢迎回来", "登录 linux.sb，继续参与社区讨论。", "登录"),
    REGISTER("注册", "创建账号", "注册 linux.sb 账号，加入社区讨论。", "注册")
}

@Composable
fun AuthScreen(
    startAtRegister: Boolean = false,
    onDone: (signedIn: Boolean) -> Unit,
    loginFormLoader: suspend (Boolean) -> Parse.LoginForm = { Api.loginForm(it) },
    loginSubmitter: suspend (String, String, Parse.LoginForm, String, String, (String) -> Unit) -> Unit = Api::login,
    captchaDocument: ((String) -> String)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tokens = LocalTokens.current
    val rise = with(LocalDensity.current) { 5.dp.roundToPx() }
    val scope = rememberCoroutineScope()

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

    // The live form. Null until the first fetch lands, and replaced wholesale by
    // every refresh - none of its fields outlive the response they came in.
    var form by remember { mutableStateOf<Parse.LoginForm?>(null) }
    var loadingForm by remember { mutableStateOf(true) }
    var formRevision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var verificationRevision by remember { mutableStateOf<Int?>(null) }

    /** Fetches the page for [target] and takes its captcha. */
    fun loadForm(target: AuthMode, clearHint: Boolean = true) {
        val revision = ++formRevision
        loadingForm = true
        form = null
        status = ""
        answer = ""
        verificationRevision = null
        scope.launch {
            try {
                val loaded = loginFormLoader(target == AuthMode.REGISTER)
                if (revision != formRevision) return@launch
                form = loaded
                if (clearHint) hint = ""
            } catch (e: SiteException) {
                if (revision != formRevision) return@launch
                form = null
                hint = e.message ?: "登录页加载失败"
            } finally {
                if (revision == formRevision) loadingForm = false
            }
        }
    }

    LaunchedEffect(Unit) { loadForm(mode) }

    /** §07 模式切换: same container, a different page behind it. */
    fun switchTo(next: AuthMode) {
        if (next == mode || submitting) return
        mode = next
        answer = ""
        hint = ""
        loadForm(next)
    }

    fun refreshCaptcha() {
        val current = form ?: return loadForm(mode)
        if (!current.captchaRequired) return loadForm(mode, clearHint = false)
        val revision = ++formRevision
        loadingForm = true
        answer = ""
        status = ""
        scope.launch {
            try {
                val refreshed = Api.refreshCaptcha(current)
                if (revision == formRevision) form = refreshed
            } catch (e: SiteException) {
                // A refresh that the site will not serve is not a dead end: the
                // whole page can always be fetched again.
                if (revision == formRevision) loadForm(mode)
            } finally {
                if (revision == formRevision) loadingForm = false
            }
        }
    }

    fun sendEmailCode() {
        if (mail.isBlank()) {
            hint = "请先填写邮箱地址"
            return
        }
        val current = form
        if (current == null) {
            hint = "页面还没准备好，请稍候"
            return
        }
        scope.launch {
            hint = try {
                Api.sendEmailCode(mail.trim(), current)
            } catch (e: SiteException) {
                e.message ?: "发送验证码失败"
            }
        }
    }

    fun submit(capToken: String = "") {
        if (submitting || loadingForm) return
        val current = form
        val problem = when {
            user.isBlank() -> if (mode == AuthMode.LOGIN) "请输入用户名或邮箱" else "请输入用户名"
            mode == AuthMode.REGISTER && mail.isBlank() -> "请输入邮箱"
            mode == AuthMode.REGISTER && current?.fields?.contains("email_code") == true && code.isBlank() -> "请输入邮箱验证码"
            pass.isBlank() -> "请输入密码"
            mode == AuthMode.REGISTER && again != pass -> "两次输入的密码不一致"
            current?.captchaRequired == true && answer.isBlank() -> "请填写人机验证的计算结果"
            !agreed -> "请先确认服务条款与隐私说明"
            current == null -> "站点页面还没准备好，可以改用网页登录"
            else -> ""
        }
        if (problem.isNotBlank()) {
            hint = problem
            return
        }
        if (current?.capChallenge != null && capToken.isBlank()) {
            focusManager.clearFocus()
            keyboard?.hide()
            hint = ""
            verificationRevision = formRevision
            return
        }
        hint = ""
        submitting = true
        scope.launch {
            try {
                if (mode == AuthMode.LOGIN) {
                    loginSubmitter(user.trim(), pass, current!!, answer, capToken) { status = it }
                    onDone(true)
                } else {
                    Api.register(
                        username = user.trim(),
                        password = pass,
                        passwordAgain = again,
                        email = mail.trim(),
                        emailCode = code,
                        form = current!!,
                        answer = answer,
                        capToken = capToken
                    ) { status = it }
                    // The site signs nobody in on registration; it sends you to
                    // the login form, so the screen follows it there.
                    mode = AuthMode.LOGIN
                    pass = ""
                    again = ""
                    code = ""
                    hint = "注册流程已完成，请用新账号登录"
                    loadForm(AuthMode.LOGIN)
                }
            } catch (e: SiteException) {
                hint = e.message ?: "提交失败，请重试"
                // The captcha is one-shot whether it was right or wrong, so a
                // failed submit needs a fresh question before the next try.
                answer = ""
                refreshCaptcha()
            } finally {
                submitting = false
                status = ""
            }
        }
    }

    BackHandler { if (verificationRevision != null) verificationRevision = null else onDone(false) }

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
                onClick = { onDone(false) }
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
                        if (form?.fields?.contains("email_code") == true) {
                            Spacer(Modifier.height(10.dp))
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
            }

            if (form?.captchaRequired == true) {
                Spacer(Modifier.height(12.dp))
                CaptchaBlock(
                    question = if (loadingForm) "" else form?.question.orEmpty(),
                    status = status,
                    answer = answer,
                    onAnswer = { answer = it },
                    onRefresh = { if (!submitting) refreshCaptcha() },
                    onSubmit = { submit() }
                )
            }

            if (hint.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                HintBar(text = hint)
            }

            Spacer(Modifier.height(14.dp))
            // 8 服务确认
            ConfirmRow(checked = agreed, onToggle = { agreed = !agreed })

            Spacer(Modifier.height(16.dp))
            // 9 主操作
            GlassButton(
                text = if (submitting) status.ifBlank { "正在提交…" } else mode.action,
                onClick = { submit() },
                enabled = !submitting && !loadingForm,
                modifier = Modifier.fillMaxWidth().testTag("auth-submit")
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
                                openInBrowser(context, "${Site.BASE}/password_recovery_forgot")
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            // OAuth redirects are handled by the browser.
            OAuthRow(
                onProvider = { provider ->
                    openInBrowser(context, "${Site.BASE}/oauth_login?provider=$provider")
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
                text = "在浏览器里打开登录页",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SbRadius.small))
                    .clickable {
                        openInBrowser(
                            context,
                            if (mode == AuthMode.REGISTER) Site.REGISTER else Site.LOGIN
                        )
                    }
                    .padding(vertical = 8.dp)
            )
            Spacer(Modifier.height(28.dp))
        }
    }
    val cap = form?.capChallenge
    val verification = verificationRevision
    if (cap != null && verification != null) {
        CapCaptchaDialog(
            challenge = cap,
            register = mode == AuthMode.REGISTER,
            onSolved = { token ->
                verificationRevision = null
                if (verification == formRevision && !loadingForm) submit(token)
            },
            onDismiss = { verificationRevision = null },
            document = captchaDocument
        )
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
