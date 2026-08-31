package StarBase.Android.Forum.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import StarBase.Android.Forum.data.AccountSettings
import StarBase.Android.Forum.data.OAuthBinding
import StarBase.Android.Forum.data.ThemeMode
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.Freshness
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.Load
import StarBase.Android.Forum.ui.OnReturnToForeground
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.failureOf
import StarBase.Android.Forum.ui.freshnessText
import StarBase.Android.Forum.ui.components.SbCard
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.components.UserAvatar
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassField
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.squareJpeg
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/** The writes are the ViewModel, so its task enum is worth a short name here. */
private typealias Task = SettingsViewModel.Task

/*
 * 个人设置.
 *
 * The site keeps all of this on one page, /profile, and behind that page are
 * five separate write paths with different rules: one shared 保存 for 头像样式 /
 * 简介 / 密码, a points-charging 改名 with a form of its own, an email change
 * gated by a mailed code, and a 头像 upload that is not a form at all. This
 * screen keeps that shape rather than flattening it - one row per thing you can
 * change, one open at a time, so the page stays a page instead of a wall of
 * inputs with acres of space between them.
 */

/**
 * One [Load] for what the page says, and one 「哪个动作在跑」 for the writes.
 *
 * Every write answers with the settings page again, so a success replaces the
 * whole state rather than patching the field that was sent: what is on screen
 * afterwards is what the site stored, not what we hoped it stored.
 */
class SettingsViewModel : ViewModel() {

    /** The one write that can be in flight; the UI disables the rest while it is. */
    enum class Task { AVATAR, PRESET, UPLOAD, BIO, PASSWORD, USERNAME, CODE, EMAIL }

    var state by mutableStateOf<Load<AccountSettings>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var busy by mutableStateOf<Task?>(null)
        private set
    var note by mutableStateOf("")
        private set
    var noteBad by mutableStateOf(false)
        private set

    private val fresh = Freshness()
    private var inFlight = false

    /** Set when a 绑定 hop is handed to the WebView, so coming back re-reads. */
    private var bindPending = false

    val ageSeconds: Long get() = fresh.ageSeconds
    val settings: AccountSettings? get() = (state as? Load.Ready)?.value

    fun load(force: Boolean = false) {
        if (state is Load.Ready && !force) return
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading else refreshing = true
            try {
                state = Load.Ready(Api.accountSettings())
                fresh.mark()
            } catch (e: Throwable) {
                if (state !is Load.Ready) state = failureOf(e)
            } finally {
                refreshing = false
                inFlight = false
            }
        }
    }

    fun openOrRefresh() {
        // A binding round trip changes the page while this screen is off it.
        if (bindPending) {
            bindPending = false
            load(force = true)
        } else if (state !is Load.Ready || fresh.stale) {
            load(force = true)
        }
    }

    fun bindStarted() {
        bindPending = true
    }

    fun clearNote() {
        note = ""
        noteBad = false
    }

    /** Runs one write and takes the page it answers with as the new state. */
    private fun write(task: Task, done: String, block: suspend () -> AccountSettings) {
        if (busy != null) return
        busy = task
        clearNote()
        viewModelScope.launch {
            try {
                state = Load.Ready(block())
                fresh.mark()
                note = done
            } catch (e: Throwable) {
                note = reasonOf(e)
                noteBad = true
            } finally {
                busy = null
            }
        }
    }

    fun saveAvatar(style: String, seed: String) = write(Task.AVATAR, "头像已更新") {
        Api.saveProfile(avatarStyle = style, avatarSeed = seed)
    }

    fun pickPreset(seed: String) = write(Task.PRESET, "头像已更换") {
        Api.pickAvatarPreset(seed)
    }

    fun uploadAvatar(context: Context, uri: Uri) = write(Task.UPLOAD, "头像已上传") {
        // Decoding and scaling a photo has no business on the main thread.
        Api.uploadAvatar(withContext(Dispatchers.IO) { squareJpeg(context, uri) })
    }

    fun saveBio(bio: String) = write(Task.BIO, "简介已保存") { Api.saveProfile(bio = bio) }

    fun savePassword(password: String) = write(Task.PASSWORD, "密码已修改") {
        Api.saveProfile(password = password)
    }

    fun changeUsername(name: String, currentPassword: String) =
        write(Task.USERNAME, "用户名已修改") { Api.changeUsername(name, currentPassword) }

    fun changeEmail(email: String, code: String) = write(Task.EMAIL, "邮箱已修改") {
        Api.changeEmail(email, code)
    }

    /**
     * 发送验证码 stores nothing, so unlike the others it only leaves a note - and
     * it is the one call on this page that really does answer JSON.
     */
    fun sendEmailCode(email: String) {
        if (busy != null) return
        busy = Task.CODE
        clearNote()
        viewModelScope.launch {
            try {
                note = Api.sendEmailCode(email)
            } catch (e: Throwable) {
                note = reasonOf(e)
                noteBad = true
            } finally {
                busy = null
            }
        }
    }
}

/** The site explains its own refusals, so its wording is what gets shown. */
private fun reasonOf(e: Throwable): String = when (e) {
    is Api.NeedsBrowser -> e.message ?: "这一步需要在网页里完成"
    else -> e.message ?: "出错了"
}

/**
 * 个人设置.
 *
 * [onBindOAuth] is separate from [onOpenSite] because a binding hop leaves
 * linux.sb for GitHub or Google and has to be allowed to: the ordinary site
 * page hands anything off-site to the browser, and the browser is not signed in
 * to this session.
 */
@Composable
fun SettingsScreen(
    store: UserStore,
    vm: SettingsViewModel,
    signedIn: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSite: (String) -> Unit,
    onBindOAuth: (String) -> Unit
) {
    OnReturnToForeground(signedIn) { if (signedIn) vm.openOrRefresh() }

    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> if (uri != null) vm.uploadAvatar(context, uri) }

    // One row open at a time. Collapsing also drops whatever the last action
    // said, so a note never outlives the section it belonged to.
    var open by remember { mutableStateOf("") }
    val toggle: (String) -> Unit = { key ->
        open = if (open == key) "" else key
        vm.clearNote()
    }

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        DetailBar(
            title = "个人设置",
            subtitle = if (signedIn && vm.settings != null) {
                freshnessText(vm.ageSeconds, vm.refreshing)
            } else {
                ""
            },
            onBack = onBack,
            action = if (signedIn) "刷新" else "",
            onAction = { vm.load(force = true) }
        )
        LazyColumn {
            item("account") {
                Gap(12)
                SbCard(modifier = cardModifier(), padding = 0.dp) {
                    val s = vm.settings
                    when {
                        !signedIn -> SignedOutRow(onLogin)
                        s != null -> {
                            IdentityRow(s)
                            Hairline(startInset = 14)
                            SettingRow("头像", styleLabel(s), open == "avatar", { toggle("avatar") }) {
                                AvatarSection(s, vm) {
                                    pickPhoto.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            }
                            Hairline(startInset = 14)
                            SettingRow(
                                "简介",
                                s.bio.ifBlank { "未填写" },
                                open == "bio",
                                { toggle("bio") }
                            ) { BioSection(s, vm) }
                            Hairline(startInset = 14)
                            SettingRow("用户名", s.name, open == "name", { toggle("name") }) {
                                UsernameSection(s, vm)
                            }
                            Hairline(startInset = 14)
                            SettingRow(
                                "密码",
                                "已设置",
                                open == "password",
                                { toggle("password") }
                            ) { PasswordSection(vm) }
                            Hairline(startInset = 14)
                            SettingRow(
                                "邮箱",
                                s.email.ifBlank { "未绑定" },
                                open == "email",
                                { toggle("email") }
                            ) { EmailSection(s, vm) }
                        }
                        else -> PendingRow(vm, onLogin)
                    }
                }
            }

            val oauth = vm.settings?.oauth.orEmpty()
            if (signedIn && oauth.isNotEmpty()) {
                item("oauth") {
                    Gap(12)
                    SbCard(modifier = cardModifier(), padding = 0.dp) {
                        RowTitle("第三方登录")
                        oauth.forEachIndexed { index, binding ->
                            if (index > 0) Hairline(startInset = 14)
                            OAuthRow(binding) {
                                // The page changes while the WebView has it, so the
                                // ViewModel is told to re-read on the way back.
                                vm.bindStarted()
                                onBindOAuth(binding.href)
                            }
                        }
                    }
                }
            }

            item("theme") {
                Gap(12)
                SbCard(modifier = cardModifier(), padding = 14.dp) {
                    Text(
                        text = "主题外观",
                        style = MaterialTheme.typography.titleSmall,
                        color = LocalTokens.current.textPrimary
                    )
                    Gap(10)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            SegmentPill(
                                label = mode.label,
                                selected = store.themeMode == mode,
                                onClick = { store.updateTheme(mode) }
                            )
                        }
                    }
                }
            }

            item("local") {
                Gap(12)
                SbCard(modifier = cardModifier(), padding = 14.dp) {
                    Text(
                        text = "本机数据",
                        style = MaterialTheme.typography.titleSmall,
                        color = LocalTokens.current.textPrimary
                    )
                    Gap(5)
                    Text(
                        text = "收藏 ${store.bookmarks.size} 条 · 浏览历史 ${store.history.size} 条，" +
                            "只存在这台手机上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalTokens.current.textSecondary
                    )
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallAction("清空收藏", primary = false, onClick = store::clearBookmarks)
                        SmallAction("清空历史", primary = false, onClick = store::clearHistory)
                    }
                }
            }

            item("exit") {
                Gap(12)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SbMetrics.pagePadding),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (signedIn) {
                        SmallAction("网页版设置", primary = false) { onOpenSite(Site.PROFILE) }
                        SmallAction("退出登录", primary = false, onClick = onSignOut)
                    } else {
                        SmallAction("登录", primary = true, onClick = onLogin)
                    }
                }
                Gap(26)
            }
        }
    }
}

/** Every card on this screen sits in the same gutter. */
private fun cardModifier(): Modifier =
    Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding)

/** The label the site itself gave the selected 头像 style. */
private fun styleLabel(s: AccountSettings): String =
    s.avatarStyles.firstOrNull { it.first == s.avatarStyle }?.second?.takeIf { it.isNotBlank() }
        ?: "默认"

@Composable
private fun RowTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = LocalTokens.current.textPrimary,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 11.dp)
    )
}

/** 个人资料: the four facts the site keeps about the account, on two lines. */
@Composable
private fun IdentityRow(s: AccountSettings) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(name = s.name, url = s.avatar, size = 50.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = s.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Gap(3)
            Text(
                text = listOfNotNull(
                    s.uid.takeIf { it.isNotBlank() }?.let { "UID $it" },
                    s.points.takeIf { it.isNotBlank() }?.let { "$it 积分" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary
            )
            if (s.joinedText.isNotBlank()) {
                Gap(2)
                Text(
                    text = "注册于 ${s.joinedText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary
                )
            }
        }
    }
}

/** Signed out, the account card is the login entry and nothing else. */
@Composable
private fun SignedOutRow(onLogin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "登录后可以在这里改头像、简介、用户名、密码和邮箱。",
            style = MaterialTheme.typography.bodySmall,
            color = LocalTokens.current.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        SmallAction("登录", primary = true, onClick = onLogin)
    }
}

/** Before the page has arrived, or after it failed to. */
@Composable
private fun PendingRow(vm: SettingsViewModel, onLogin: () -> Unit) {
    val tokens = LocalTokens.current
    val failed = vm.state as? Load.Failed
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
        Text(
            text = failed?.message ?: "正在读取账号资料",
            style = MaterialTheme.typography.bodySmall,
            color = if (failed != null) tokens.hotTint else tokens.textSecondary
        )
        if (failed != null) {
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallAction("重试", primary = true) { vm.load(force = true) }
                if (failed.kind == SiteException.Kind.AUTH) {
                    SmallAction("去登录", primary = false, onClick = onLogin)
                }
            }
        }
    }
}

/**
 * One openable row: what it is, what it currently says, and the editor for it.
 *
 * Collapsed it is a single line, which is the point - six editors laid out at
 * once would be a form, and this is a settings page.
 */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (expanded) "" else value,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (expanded) "收起" else "修改",
                style = MaterialTheme.typography.labelMedium,
                color = if (expanded) tokens.accentGlow else tokens.textSecondary
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                content = content
            )
        }
    }
}

/** The site prints its own rules - cost, interval, balance - so they are quoted. */
@Composable
private fun PolicyText(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalTokens.current.textTertiary
    )
    Gap(9)
}

/** Whatever the last action on this section said, in its own colour. */
@Composable
private fun ColumnScope.SectionNote(vm: SettingsViewModel) {
    if (vm.note.isBlank()) return
    Gap(9)
    Text(
        text = vm.note,
        style = MaterialTheme.typography.labelMedium,
        color = if (vm.noteBad) LocalTokens.current.hotTint else LocalTokens.current.accentGlow
    )
}

/** 简介 is a textarea on the site, so it is a box here rather than a line. */
@Composable
private fun MultilineField(value: String, onValue: (String) -> Unit, placeholder: String) {
    val tokens = LocalTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = RoundedCornerShape(SbRadius.field),
                level = GlassLevel.LOW,
                refract = false
            )
            .heightIn(min = 84.dp)
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textTertiary
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
            cursorBrush = SolidColor(tokens.accentWarm),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 头像. Three offers in one section, which is how the site presents them: a
 * dicebear style and its seed, its own 48 预置头像, and an upload.
 */
@Composable
private fun ColumnScope.AvatarSection(
    s: AccountSettings,
    vm: SettingsViewModel,
    onPickPhoto: () -> Unit
) {
    var style by remember(s.avatarStyle) { mutableStateOf(s.avatarStyle) }
    var seed by remember(s.avatarSeed) { mutableStateOf(s.avatarSeed) }
    val running = vm.busy

    PolicyText(s.avatarNote)
    if (s.avatarStyles.isNotEmpty()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            s.avatarStyles.forEach { (value, label) ->
                SegmentPill(
                    label = label.ifBlank { "默认" },
                    selected = value == style,
                    onClick = { style = value }
                )
            }
        }
        Gap(9)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassField(
            value = seed,
            onValue = { seed = it },
            placeholder = "随机种子",
            modifier = Modifier.weight(1f),
            imeAction = ImeAction.Done,
            onSubmit = { vm.saveAvatar(style, seed) }
        )
        Spacer(Modifier.width(9.dp))
        GlassButton(
            text = if (running == Task.AVATAR) "保存中" else "保存",
            onClick = { vm.saveAvatar(style, seed) },
            compact = true,
            enabled = running == null && (style != s.avatarStyle || seed != s.avatarSeed)
        )
    }
    if (s.avatarPresets.isNotEmpty()) {
        Gap(11)
        Text(
            text = "预置头像 · 点一个立刻换",
            style = MaterialTheme.typography.labelSmall,
            color = LocalTokens.current.textTertiary
        )
        Gap(7)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.avatarPresets, key = { it.seed }) { preset ->
                UserAvatar(
                    name = preset.seed,
                    url = preset.url,
                    size = 40.dp,
                    onClick = { if (vm.busy == null) vm.pickPreset(preset.seed) }
                )
            }
        }
    }
    Gap(11)
    GlassButton(
        text = if (running == Task.UPLOAD) "上传中" else "从相册上传",
        onClick = onPickPhoto,
        modifier = Modifier.fillMaxWidth(),
        primary = false,
        compact = true,
        enabled = running == null
    )
    SectionNote(vm)
}

/** 简介. Its own field on the shared form, so it saves on its own. */
@Composable
private fun ColumnScope.BioSection(s: AccountSettings, vm: SettingsViewModel) {
    var bio by remember(s.bio) { mutableStateOf(s.bio) }
    MultilineField(value = bio, onValue = { bio = it }, placeholder = "介绍一下自己")
    Gap(10)
    GlassButton(
        text = if (vm.busy == Task.BIO) "保存中" else "保存简介",
        onClick = { vm.saveBio(bio) },
        modifier = Modifier.fillMaxWidth(),
        compact = true,
        enabled = vm.busy == null && bio != s.bio
    )
    SectionNote(vm)
}

/**
 * 改名. It costs points and the site asks for the current password, so both of
 * its own sentences - the cost and whether the interval has elapsed - are shown
 * above the fields rather than paraphrased.
 */
@Composable
private fun ColumnScope.UsernameSection(s: AccountSettings, vm: SettingsViewModel) {
    var name by remember(s.name) { mutableStateOf("") }
    var password by remember(s.name) { mutableStateOf("") }
    val allowed = s.renameAllowed

    PolicyText(
        listOf(s.renamePolicy, s.renameNote).filter { it.isNotBlank() }.joinToString(" · ")
    )
    GlassField(
        value = name,
        onValue = { name = it },
        placeholder = "新用户名",
        modifier = Modifier.fillMaxWidth(),
        enabled = allowed
    )
    Gap(8)
    GlassField(
        value = password,
        onValue = { password = it },
        placeholder = "当前密码",
        modifier = Modifier.fillMaxWidth(),
        password = true,
        enabled = allowed,
        imeAction = ImeAction.Done,
        onSubmit = { vm.changeUsername(name, password) }
    )
    Gap(10)
    GlassButton(
        text = if (vm.busy == Task.USERNAME) "提交中" else "确认改名",
        onClick = { vm.changeUsername(name, password) },
        modifier = Modifier.fillMaxWidth(),
        compact = true,
        enabled = allowed && vm.busy == null && name.isNotBlank() && password.isNotBlank()
    )
    SectionNote(vm)
}

/**
 * 密码. Two fields because the site validates a pair; the mismatch is caught
 * here so a typo does not cost a round trip.
 */
@Composable
private fun ColumnScope.PasswordSection(vm: SettingsViewModel) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = again.isNotEmpty() && password != again

    GlassField(
        value = password,
        onValue = { password = it },
        placeholder = "新密码",
        modifier = Modifier.fillMaxWidth(),
        password = true
    )
    Gap(8)
    GlassField(
        value = again,
        onValue = { again = it },
        placeholder = "再输入一次",
        modifier = Modifier.fillMaxWidth(),
        password = true,
        imeAction = ImeAction.Done,
        onSubmit = { if (!mismatch) vm.savePassword(password) }
    )
    if (mismatch) {
        Gap(7)
        Text(
            text = "两次输入的密码不一致",
            style = MaterialTheme.typography.labelMedium,
            color = LocalTokens.current.hotTint
        )
    }
    Gap(10)
    GlassButton(
        text = if (vm.busy == Task.PASSWORD) "保存中" else "保存密码",
        onClick = { vm.savePassword(password) },
        modifier = Modifier.fillMaxWidth(),
        compact = true,
        enabled = vm.busy == null && password.isNotBlank() && password == again
    )
    SectionNote(vm)
}

/** 修改邮箱: the site mails a code to the new address first. */
@Composable
private fun ColumnScope.EmailSection(s: AccountSettings, vm: SettingsViewModel) {
    var mail by remember(s.email) { mutableStateOf(s.email) }
    var code by remember(s.email) { mutableStateOf("") }

    PolicyText(s.emailNote)
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassField(
            value = mail,
            onValue = { mail = it },
            placeholder = "新邮箱地址",
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        Spacer(Modifier.width(9.dp))
        GlassButton(
            text = if (vm.busy == Task.CODE) "发送中" else "发验证码",
            onClick = { vm.sendEmailCode(mail) },
            primary = false,
            compact = true,
            enabled = vm.busy == null && mail.isNotBlank()
        )
    }
    Gap(8)
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassField(
            value = code,
            onValue = { code = it },
            placeholder = "邮箱验证码",
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            imeAction = ImeAction.Done,
            onSubmit = { vm.changeEmail(mail, code) }
        )
        Spacer(Modifier.width(9.dp))
        GlassButton(
            text = if (vm.busy == Task.EMAIL) "提交中" else "保存",
            onClick = { vm.changeEmail(mail, code) },
            compact = true,
            enabled = vm.busy == null && mail.isNotBlank() && code.isNotBlank()
        )
    }
    SectionNote(vm)
}

/**
 * One 第三方登录 row. The link is the site's own, and its label is too - 绑定
 * today, whatever it says once something is bound.
 */
@Composable
private fun OAuthRow(binding: OAuthBinding, onBind: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = binding.label.ifBlank { binding.provider },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Gap(2)
            Text(
                text = if (binding.bound) {
                    listOf("已绑定", binding.account).filter { it.isNotBlank() }.joinToString(" ")
                } else {
                    "未绑定"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (binding.bound) tokens.accentGlow else tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        SmallAction(
            text = binding.action.ifBlank { if (binding.bound) "管理" else "绑定" },
            primary = !binding.bound,
            onClick = onBind
        )
    }
}
