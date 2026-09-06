package StarBase.Android.Forum.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import StarBase.Android.Forum.BuildConfig
import StarBase.Android.Forum.data.Reading
import StarBase.Android.Forum.data.ThemeMode
import StarBase.Android.Forum.data.UpdateCheck
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.notify.Alarms
import StarBase.Android.Forum.net.Doh
import StarBase.Android.Forum.net.Ech
import StarBase.Android.Forum.net.Frag
import StarBase.Android.Forum.net.Github
import StarBase.Android.Forum.net.ReleaseInfo
import StarBase.Android.Forum.net.Releases
import StarBase.Android.Forum.net.Site
import StarBase.Android.Forum.net.DohAuto
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.SiteDns
import StarBase.Android.Forum.net.CronetTransport
import StarBase.Android.Forum.net.SiteException
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.apkCacheDir
import StarBase.Android.Forum.ui.canInstall
import StarBase.Android.Forum.ui.components.Chip
import StarBase.Android.Forum.ui.components.SbCard
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassField
import StarBase.Android.Forum.ui.installApk
import StarBase.Android.Forum.ui.openInstallPermission
import StarBase.Android.Forum.ui.openInBrowser
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The update check.
 *
 * One instance lives as long as the app, so a check that ran at launch is still
 * on screen when 应用设置 is opened, and opening it twice does not re-ask GitHub.
 */
class UpdateViewModel : ViewModel() {

    enum class Stage { IDLE, CHECKING, CURRENT, FOUND, DOWNLOADING, READY, FAILED }

    var stage: Stage by mutableStateOf(Stage.IDLE)
        private set

    /** The newest published release, once a check has succeeded. */
    var release: ReleaseInfo? by mutableStateOf(null)
        private set

    var note: String by mutableStateOf("")
        private set

    var noteBad: Boolean by mutableStateOf(false)
        private set

    var readBytes: Long by mutableStateOf(0L)
        private set

    var totalBytes: Long by mutableStateOf(0L)
        private set

    /** The downloaded build, waiting for the installer. */
    var apk: File? by mutableStateOf(null)
        private set

    /** What this build calls itself - the other half of every comparison. */
    val current: String get() = BuildConfig.VERSION_NAME

    val progress: Float
        get() = if (totalBytes > 0L) (readBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    private var autoTried = false

    /**
     * The scheduled check. Runs at most once per process and only when the
     * user's 检查时机 says it is owed, so 只手动检查 really is silent.
     */
    fun autoCheck(store: UserStore) {
        if (autoTried) return
        autoTried = true
        if (!store.updateCheck.due(store.lastCheckedAt, System.currentTimeMillis())) return
        check(store)
    }

    fun check(store: UserStore) {
        if (stage == Stage.CHECKING || stage == Stage.DOWNLOADING) return
        stage = Stage.CHECKING
        note = ""
        noteBad = false
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Releases.latest() }
                // The timestamp moves on a successful answer only: a failed
                // check must not push the next attempt a whole day out.
                store.markChecked(System.currentTimeMillis(), found?.tag.orEmpty())
                release = found
                val ahead = found != null && Releases.isNewer(found.tag, current)
                stage = if (ahead) Stage.FOUND else Stage.CURRENT
                note = when {
                    found == null -> "仓库里还没有发布过版本"
                    ahead -> "发现新版本 ${Releases.label(found.tag)}"
                    else -> "已经是最新版本"
                }
            } catch (e: SiteException) {
                stage = Stage.FAILED
                note = e.message ?: "检查更新失败"
                noteBad = true
            } catch (e: Exception) {
                stage = Stage.FAILED
                note = "检查更新失败"
                noteBad = true
            }
        }
    }

    /**
     * Fetches the release's `.apk` into the app's own cache, then opens the
     * installer. A build already sitting there at the right size is reused
     * rather than downloaded again.
     */
    fun download(context: Context) {
        val info = release ?: return
        val url = info.apkUrl ?: return
        if (stage == Stage.DOWNLOADING) return

        val dir = apkCacheDir(context)
        val dest = File(dir, "StarBase-${info.tag}.apk")
        if (dest.isFile && info.apkSize > 0L && dest.length() == info.apkSize) {
            apk = dest
            stage = Stage.READY
            note = "已经下载过这个版本"
            noteBad = false
            install(context)
            return
        }

        stage = Stage.DOWNLOADING
        readBytes = 0L
        totalBytes = info.apkSize
        note = ""
        noteBad = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val scope = this
                    // One build in the cache at a time; the rest are stale.
                    dir.listFiles()?.forEach { file ->
                        if (file.absolutePath != dest.absolutePath) file.delete()
                    }
                    Releases.download(url, dest) { read, total ->
                        scope.ensureActive()
                        readBytes = read
                        if (total > 0L) totalBytes = total
                    }
                }
                apk = dest
                stage = Stage.READY
                note = "下载完成"
                install(context)
            } catch (e: SiteException) {
                stage = Stage.FOUND
                note = e.message ?: "下载失败"
                noteBad = true
            } catch (e: Exception) {
                stage = Stage.FOUND
                note = "下载失败"
                noteBad = true
            }
        }
    }

    /**
     * Hands the downloaded build to the platform installer. Android 8+ needs a
     * per-app permission for this app to be allowed to even ask, so the user is
     * sent to that Settings page instead of being shown a button that fails.
     */
    fun install(context: Context) {
        val file = apk ?: return
        if (!canInstall(context)) {
            note = "系统需要你先允许本应用安装未知应用"
            noteBad = true
            openInstallPermission(context)
            return
        }
        if (!installApk(context, file)) {
            note = "系统里没有能安装应用的组件"
            noteBad = true
        }
    }
}

/**
 * 应用设置 - everything that belongs to this app rather than to the linux.sb
 * account: the update check first, then appearance, the device-local data, and
 * 关于. Nothing here needs a session, so it opens signed out too.
 */
@Composable
fun AppSettingsScreen(
    store: UserStore,
    vm: UpdateViewModel,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onWatch: () -> Unit,
    onReminders: () -> Unit,
    onBlocks: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.autoCheck(store) }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "应用设置",
            subtitle = "当前版本 v${vm.current}",
            onBack = onBack
        )
        LazyColumn {
            item("update") { Gap(12); UpdateCard(store = store, vm = vm) }
            item("dns") { Gap(12); DnsCard(store = store) }
            item("frag") { Gap(12); FragCard(store = store) }
            item("theme") { Gap(12); ThemeCard(store = store) }
            item("history") { Gap(12); HistoryCard(store = store, onOpen = onHistory) }
            item("reading") { Gap(12); ReadingCard(store = store, onWatch = onWatch) }
            item("reminders") { Gap(12); RemindersCard(store = store, onOpen = onReminders) }
            item("blocks") { Gap(12); BlocksCard(store = store, onOpen = onBlocks) }
            item("local") { Gap(12); LocalDataCard() }
            item("about") {
                Gap(12)
                AboutCard(onOpenReleases = { openInBrowser(context, Github.releasesPage) })
                Gap(26)
            }
        }
    }
}

/** Every card on this screen sits in the same gutter. */
private fun cardWidth(): Modifier =
    Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding)

@Composable
private fun CardTitle(text: String, tail: String = "") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = LocalTokens.current.textPrimary
        )
        if (tail.isNotEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = tail,
                style = MaterialTheme.typography.labelSmall,
                color = LocalTokens.current.textSecondary
            )
        }
    }
}

/**
 * 检查更新. First card on the page, because it is the one thing here that can
 * be out of date.
 */
@Composable
private fun UpdateCard(store: UserStore, vm: UpdateViewModel) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val busy = vm.stage == UpdateViewModel.Stage.CHECKING ||
        vm.stage == UpdateViewModel.Stage.DOWNLOADING

    SbCard(modifier = cardWidth(), padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "检查更新",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary
                    )
                    if (vm.stage == UpdateViewModel.Stage.FOUND ||
                        vm.stage == UpdateViewModel.Stage.READY
                    ) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Chip(text = "新版本", tint = tokens.accentGlow)
                    }
                }
                Gap(3)
                Text(
                    text = statusLine(vm, store),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.noteBad) tokens.hotTint else tokens.textSecondary
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            GlassButton(
                text = if (vm.stage == UpdateViewModel.Stage.CHECKING) "检查中" else "立即检查",
                onClick = { vm.check(store) },
                primary = false,
                enabled = !busy,
                compact = true
            )
        }

        val info = vm.release
        if (info != null && vm.stage != UpdateViewModel.Stage.CHECKING &&
            Releases.isNewer(info.tag, vm.current)
        ) {
            Gap(12)
            Hairline()
            Gap(12)
            NewRelease(info = info, vm = vm, context = context)
        }

        Gap(12)
        Hairline()
        Gap(12)
        Text(
            text = "检查时机",
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textPrimary
        )
        Gap(3)
        Text(
            text = "自动检查只在打开 App 时进行，不会在后台跑。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(10)
        UpdateCheck.entries.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { mode ->
                    SegmentPill(
                        label = mode.label,
                        selected = store.updateCheck == mode,
                        onClick = { store.updateCheckMode(mode) }
                    )
                }
            }
        }
    }
}

/** The found release: what it is, what it says, and the two things to do with it. */
@Composable
private fun NewRelease(info: ReleaseInfo, vm: UpdateViewModel, context: Context) {
    val tokens = LocalTokens.current
    val downloading = vm.stage == UpdateViewModel.Stage.DOWNLOADING

    Text(
        text = info.name,
        style = MaterialTheme.typography.labelLarge,
        color = tokens.textPrimary
    )
    Gap(3)
    Text(
        text = listOfNotNull(
            "v${vm.current} → ${Releases.label(info.tag)}",
            info.publishedAt.take(10).ifEmpty { null },
            if (info.apkSize > 0L) sizeText(info.apkSize) else null,
            if (info.prerelease) "预发布" else null
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = tokens.textSecondary
    )

    if (info.notes.isNotEmpty()) {
        Gap(10)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.glassLow, RoundedCornerShape(12.dp))
                .heightIn(max = 190.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            Text(
                text = info.notes,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary
            )
        }
    }

    if (downloading) {
        Gap(11)
        LinearProgressIndicator(
            progress = { vm.progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = tokens.accentWarm,
            trackColor = tokens.hairline
        )
        Gap(6)
        Text(
            text = if (vm.totalBytes > 0L) {
                "正在下载 ${sizeText(vm.readBytes)} / ${sizeText(vm.totalBytes)}"
            } else {
                "正在下载 ${sizeText(vm.readBytes)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
    }

    Gap(11)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (info.apkUrl == null) {
            // Nothing to install, so the发布页 is the whole offer.
            SmallAction("打开发布页", primary = true) { openInBrowser(context, info.pageUrl) }
        } else {
            if (vm.stage == UpdateViewModel.Stage.READY) {
                SmallAction("立即安装", primary = true) { vm.install(context) }
            } else {
                SmallAction(
                    text = if (downloading) "下载中…" else "下载并安装",
                    primary = true
                ) { if (!downloading) vm.download(context) }
            }
            SmallAction("查看发布页", primary = false) { openInBrowser(context, info.pageUrl) }
        }
    }
    if (info.apkUrl == null) {
        Gap(7)
        Text(
            text = "这个版本没有附带 APK，只能到发布页自取。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
    }
}

/**
 * One line under 检查更新: whatever the last check said, or - before there is
 * anything to say - when it last happened.
 */
private fun statusLine(vm: UpdateViewModel, store: UserStore): String = when {
    vm.stage == UpdateViewModel.Stage.CHECKING -> "正在向 GitHub 询问…"
    vm.note.isNotEmpty() -> vm.note
    store.lastCheckedAt > 0L -> "上次检查 ${timeText(store.lastCheckedAt)}"
    store.updateCheck == UpdateCheck.MANUAL -> "只在你点「立即检查」时看一眼"
    else -> "还没有检查过"
}

private fun timeText(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

private fun sizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * 域名解析 (DoH).
 *
 * The one setting on this page that changes how every other request behaves, so
 * it says plainly what it fixes and what it does not: a wrong DNS answer, yes;
 * a connection cut for what its TLS handshake asked for, no. The 测试 button is
 * here because the honest way to answer 「这个服务器能用吗」 is to ask it.
 */
@Composable
private fun DnsCard(store: UserStore) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen = store.dohChoice.ifBlank { Doh.DEFAULT_SERVER }
    val preset = Doh.PRESETS.any { it.url == chosen }
    var custom by remember { mutableStateOf(if (preset) "" else chosen) }
    var probing by remember { mutableStateOf(false) }
    var probeLine by remember { mutableStateOf("") }
    var probeBad by remember { mutableStateOf(false) }

    // The store keeps the setting and the resolver acts on it; nothing else
    // connects the two, so every change here has to hand it over.
    fun apply(enabled: Boolean, url: String) {
        store.updateDoh(enabled, url)
        SiteDns.configure(store.dohEnabled, store.dohChoice)
        Net.client.connectionPool.evictAll()
        // The QUIC fallback pins each engine to one address, and those addresses
        // came from the resolver that just changed. Dropping the pool means the
        // next fallback resolves again instead of reusing an answer from the old
        // resolver. In-flight requests finish on their own engine.
        CronetTransport.invalidate()
        // Something changed on purpose, so the automatic search is allowed to act
        // on it now rather than after whatever is left of its ten-minute pause.
        DohAuto.reset()
        probeLine = ""
        probeBad = false
    }

    fun test(url: String) {
        if (probing) return
        probing = true
        probeLine = "正在问 ${host(url)}…"
        probeBad = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { Doh.probe(url) }
            probing = false
            probeBad = !result.ok
            probeLine = if (result.ok) {
                "能用 · ${result.ms} ms · " + result.addresses.joinToString(" / ")
            } else {
                "不通 · ${result.ms} ms · ${result.error}"
            }
        }
    }

    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(text = "域名解析 (DoH)", tail = if (store.dohEnabled) "开着" else "关着")
        Gap(5)
        Text(
            text = "DNS 答案不对，站点就打不开，所以默认开着：域名交给你选的服务器解析，路上改不了。" +
                "不想让第三方知道你解析了哪些域名，关掉就回系统 DNS。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(7)
        Text(
            text = "登录、页面和图片共用这套解析。按 SNI 掐断的连接还需要 TLS 分片或 QUIC 回退。",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
        Gap(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction(
                text = if (store.dohEnabled) "直连解析：开" else "直连解析：关",
                primary = store.dohEnabled,
                onClick = { apply(!store.dohEnabled, store.dohServer) }
            )
            SmallAction(
                text = if (probing) "测试中…" else "测试",
                primary = false,
                onClick = { test(chosen) }
            )
        }

        if (probeLine.isNotEmpty()) {
            Gap(9)
            Text(
                text = probeLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (probeBad) tokens.hotTint else tokens.textSecondary
            )
            Gap(3)
            Text(
                text = "问的是 linux.sb 的 A 记录，用的就是 App 平时解析的那条路。",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }

        if (store.dohEnabled && SiteDns.lastError.isNotBlank()) {
            Gap(9)
            Text(
                text = "上一次解析没成，先用之前的答案顶着，没有才回系统 DNS：${SiteDns.lastError}",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }

        // Only while the choice is blank, because that is the only case where the
        // app is allowed to move - see [DohAuto].
        if (store.dohServer.isBlank() && store.dohAuto.isNotBlank()) {
            Gap(9)
            Text(
                text = "默认那台在这个网络上答不上来，已经自己换成 ${host(store.dohAuto)}。" +
                    "想固定一台，下面点一下就好。",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }

        Gap(12)
        Hairline()
        Gap(12)
        Text(
            text = "选服务器",
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textPrimary
        )
        Gap(3)
        Text(
            text = "都是公开的第三方，只解析域名。靠前的实测答得上来，但换个网络就未必——按「测试」问最准。" +
                "没自己选过的话，用的那台不通时 App 会自己挑一台答得上来的；选过就一直用你选的。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(10)
        Doh.PRESETS.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { server ->
                    SegmentPill(
                        label = server.label,
                        selected = chosen == server.url,
                        onClick = { apply(store.dohEnabled, server.url) }
                    )
                }
            }
        }
        Gap(2)
        Text(
            text = "自己填一个",
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textPrimary
        )
        Gap(6)
        GlassField(
            value = custom,
            onValue = { custom = it },
            placeholder = "https://…/dns-query",
            glyph = "解",
            modifier = Modifier.fillMaxWidth()
        )
        Gap(9)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction(
                text = "用这个",
                primary = false,
                onClick = {
                    val tidy = Doh.tidyUrl(custom)
                    if (tidy.isEmpty()) {
                        probeBad = true
                        probeLine = "地址得是 https:// 开头的完整域名"
                    } else {
                        custom = tidy
                        apply(store.dohEnabled, tidy)
                    }
                }
            )
            SmallAction(
                text = "先测一下",
                primary = false,
                onClick = {
                    val tidy = Doh.tidyUrl(custom)
                    if (tidy.isEmpty()) {
                        probeBad = true
                        probeLine = "地址得是 https:// 开头的完整域名"
                    } else {
                        custom = tidy
                        test(tidy)
                    }
                }
            )
        }
        Gap(7)
        Text(
            text = "现在用的是 " + host(chosen) + if (store.dohEnabled) "" else "（已关掉，走系统 DNS）",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )

        Gap(12)
        Hairline()
    }
}

/**
 * 分片 (TLS).
 *
 * Directly under 域名解析 because it is the other half of the same question, and
 * because 「DoH 开了还是打不开」 is answered on this card rather than that one. The
 * 测试 button asks both resolvers where the site is and then connects three ways -
 * two splits and a control - because 「分片有用」, 「这条线路本来就通」, 「地址被改了」
 * and 「分片根本没发出去」 all look identical from one line of output.
 */
@Composable
private fun FragCard(store: UserStore) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var addressLine by remember { mutableStateOf("") }
    var echLine by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Pair<String, Boolean>>()) }
    var verdict by remember { mutableStateOf("") }

    fun apply(enabled: Boolean) {
        store.updateFrag(enabled)
        Frag.configure(store.fragEnabled)
        addressLine = ""
        echLine = ""
        progress = ""
        results = emptyList()
        verdict = ""
    }

    fun test() {
        if (probing) return
        probing = true
        addressLine = ""
        echLine = ""
        results = emptyList()
        verdict = ""
        progress = "正在问两边的 DNS…"
        scope.launch {
            // Where the site is, before anything is dialled. A wrong address and a
            // cut handshake both come back as 「不通」, and this is what tells them
            // apart.
            val where = withContext(Dispatchers.IO) { Frag.addresses(SITE_HOST) }
            addressLine = where.text
            // ECH first, and only when it can really be armed. It is the one run
            // here that puts no name on the wire at all, so it is both the answer
            // most worth having and the run least likely to leave the address shut
            // for the ones after it. Unarmed it would be the control, and running
            // the control first is exactly what the ordering below avoids.
            progress = "正在取 ECH 公钥…"
            val ech = withContext(Dispatchers.IO) { echStatus(SITE_HOST) }
            echLine = ech.text
            var echProbe: Frag.Probe? = null
            if (ech.usable) {
                progress = "正在连（ECH）…"
                val probe = withContext(Dispatchers.IO) {
                    Frag.probe(SITE_HOST, Frag.Style.NONE, ech = true)
                }
                echProbe = probe
                results = results + ("ECH（名字加密）：" + line(probe) to !probe.ok)
            }
            // Split first, both ways. The un-split run is a deliberate hit against
            // whatever is doing the cutting, and some of those keep the address shut
            // for a while afterwards - so the answers that matter are measured
            // before the control that might spoil them.
            val probes = ArrayList<Pair<Frag.Style, Frag.Probe>>()
            for ((label, style) in STYLES) {
                progress = "正在连（$label）…"
                val probe = withContext(Dispatchers.IO) { Frag.probe(SITE_HOST, style) }
                probes += style to probe
                results = results + ("$label：" + line(probe) to !probe.ok)
            }
            progress = ""
            verdict = verdictOf(probes, where.polluted, echProbe)
            probing = false
        }
    }
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(text = "分片 (TLS)", tail = if (store.fragEnabled) "开着" else "关着")
        Gap(5)
        Text(
            text = "DoH 只管解析。地址本来就对、连上去才被掐断的那一种，是 TLS 握手里带着 " +
                "$SITE_HOST 这个名字被认出来了——把带名字的那一段拆成两截发出去，" +
                "逐包看的中间设备就拼不出这个名字。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(7)
        Text(
            text = "有用没用取决于你这条线路，所以默认关着：按「测试」它先问两边的 DNS、" +
                "再去取站点的 ECH 公钥，然后一次一次地连——把名字加密发（取到公钥、" +
                "这台设备也支持的时候）、拆成两个 TLS 记录、拆成两个 TCP 包、完全不拆——" +
                "连到哪个地址、名字到底是加密还是拆开发的，都印在下面。",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
        Gap(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction(
                text = if (store.fragEnabled) "分片：开" else "分片：关",
                primary = store.fragEnabled,
                onClick = { apply(!store.fragEnabled) }
            )
            SmallAction(
                text = if (probing) "测试中…" else "测试",
                primary = false,
                onClick = { test() }
            )
        }

        if (addressLine.isNotEmpty()) {
            Gap(9)
            Text(
                text = addressLine,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary
            )
        }
        if (echLine.isNotEmpty()) {
            Gap(3)
            Text(
                text = echLine,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary
            )
        }
        results.forEach { (text, bad) ->
            Gap(3)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (bad) tokens.hotTint else tokens.textSecondary
            )
        }
        if (progress.isNotEmpty()) {
            Gap(3)
            Text(
                text = progress,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textTertiary
            )
        }
        if (verdict.isNotEmpty()) {
            Gap(5)
            Text(
                text = verdict,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }

        Gap(12)
        Hairline()
        Gap(12)
        Text(
            text = "它治不了什么",
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textPrimary
        )
        Gap(3)
        Text(
            text = "这不是代理：包还是发到站点自己的地址，按地址封的、或者会把 TCP 流重新拼起来" +
                "再看的，它都治不了。伪造包和乱序要的权限这个 App 不申请。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(7)
        Text(
            text = "分片不是 ECH。浏览器开了安全 DNS 之后能打开，是因为它取到了站点的 " +
                "HTTPS 记录，用里面的公钥把名字加密在握手里，线路上根本没有名字可看。" +
                "这个 App 现在也这么做，而且不需要你开什么：系统的 TLS 从 Android 17 起才有" +
                "这个接口，有就自动用上——上面那行 ECH 写的就是这台设备到底用上了没有。" +
                "用不上的时候剩下的才是分片：名字还是明文，只是拆成两截发，" +
                "碰上会重新拼流的设备就不够了。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(7)
        // The other half of 「重组型设备这一刀不够」: not a finer cut, a different
        // transport. Said here because this is the card where that failure is read.
        Text(
            text = "会把 TCP 流重新拼起来的设备，这一刀就不够了。那种情况下还有一条路：TLS 走不通的" +
                "时候，App 会用 QUIC（HTTP/3，UDP 的 443）把同一个请求再发一次——名字装在加密的包里，" +
                "只看 TCP 的设备看不见它。那是自动的，没有开关，而且只在 TCP 已经失败之后才发生；" +
                "不少网络直接把 UDP 的 443 封掉，所以它也只是多一次机会，不是保证。",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
    }
}

/**
 * The three ways 测试 frames a plaintext ClientHello, in the order it sends them.
 *
 * Both splits before the control, and the record split first: it is the one the
 * switch turns on, and the one that beats an inspector the segment split does not.
 * The ECH run is not in here - it is not a way of framing the name, it is the name
 * not being there, and it runs ahead of all three.
 */
private val STYLES = listOf(
    "分片（两个记录）" to Frag.Style.RECORD,
    "分片（两个 TCP 包）" to Frag.Style.SEGMENT,
    "对照（完全不拆）" to Frag.Style.NONE
)

/** One [Frag.Probe] as one line: what happened, where, and whether the cut landed. */
private fun line(probe: Frag.Probe): String = buildString {
    append(if (probe.ok) "通" else "不通")
    append(" · ${probe.ms} ms")
    if (probe.address.isNotEmpty()) append(" · ${probe.address}")
    append(" · ")
    append(if (probe.ok) probe.detail else probe.error)
    if (probe.note.isNotEmpty()) append(" · ${probe.note}")
}

/** What the ECH half of 测试 found, before anything is dialled. */
private class EchState(val text: String, val usable: Boolean)

/**
 * Whether ECH can be armed for [host], and why not when it cannot.
 *
 * Both halves are reported, not just whichever fails first. 「这台设备太老」 and
 * 「DNS 里没公钥」 send the user somewhere completely different, and an old phone
 * still deserves to know whether the site publishes a key at all - that is the half
 * of the answer which survives changing the phone.
 */
private fun echStatus(host: String): EchState {
    val config = Ech.configFor(host)
    val names = if (config == null) emptyList() else Ech.publicNames(config)
    val found = when {
        config == null -> "ECH：DNS 里没取到这个站的 ech= 公钥"
        // Bytes that arrived but do not parse are worth telling apart from bytes
        // that never came: the first is a config this app cannot read, the second
        // is a record with nothing in it.
        names.isEmpty() -> "ECH：取到公钥了（${config.size} 字节，但里面没读出公开名字）"
        else -> "ECH：取到公钥了，线路上只会看到 ${names.joinToString("、")}"
    }
    return when {
        config != null && Ech.available -> EchState("$found，这台设备也有这个接口。", true)
        config != null ->
            EchState("$found，但这台设备没有这个接口——要 Android 17。", false)
        Ech.available ->
            EchState("$found，所以加密不了：DoH 这边没给出 HTTPS 记录，或者记录里没这一项。", false)
        else -> EchState("$found，这台设备也没有这个接口（要 Android 17）。", false)
    }
}

/**
 * What the lines add up to.
 *
 * The card printed the raw lines before this existed and left the reading to the
 * user, which was fine while there were two of them and no way to be wrong. There
 * are now up to four, and three of the readings are traps: a split that never
 * reached the wire makes identical rows that look like 「分片没用」, a polluted
 * answer makes every row fail in a way that looks like 「名字被拦」, and an ECH row
 * that failed while a split won looks like ECH being useless when it is the one
 * row that proves whether the name is the trigger at all. Each would send the user
 * off to fix the wrong thing.
 */
private fun verdictOf(
    probes: List<Pair<Frag.Style, Frag.Probe>>,
    polluted: Boolean,
    ech: Frag.Probe?
): String {
    val plain = probes.firstOrNull { it.first == Frag.Style.NONE }?.second
    val splits = probes.filter { it.first != Frag.Style.NONE }
    val won = splits.firstOrNull { it.second.ok }
    val reached = splits.any { it.second.note.startsWith("分片生效") }
    val rows = if (ech == null) "三行" else "四行"
    return when {
        // The ECH rows come first because they answer a different question from the
        // splits: not 「拆开够不够」 but 「名字是不是真的原因」.
        ech?.ok == true && plain?.ok != true ->
            "ECH 测试连接成功，明文握手失败。登录、页面和图片请求共用 ECH 设置，可以尝试关闭分片。"
        ech?.ok == true ->
            "加密发和明文发都通：这条线路现在没在按名字掐你。ECH 一直开着也没坏处，分片没必要。"
        won != null && plain?.ok != true ->
            "就是它救的：${STYLES.first { it.second == won.first }.first} 通了，不拆不通。" +
                "把分片开着。"
        plain?.ok == true && won != null ->
            "拆和不拆都通：这条线路没在按名字掐你，开着也没坏处，但没必要。现在打不开是别的原因。"
        plain?.ok == true ->
            "不拆反而通了，拆了不通：这条线路不按名字拦，但会因为拆开而丢包。关掉分片。"
        polluted ->
            "${rows}都不通，而且上面那行说 DoH 和系统 DNS 给的地址不一样——先治解析：" +
                "把 域名解析 (DoH) 开着，或者换一台服务器，再回来试。"
        // Armed and still dead is the row that settles it: there was no name on
        // the wire to recognise, so whatever cut this was not reading the name.
        ech != null ->
            "连 ECH 都不通：名字是加密发出去的，线路上根本没有 $SITE_HOST 可看，还是被掐——" +
                "那就不是按名字拦的，分片和 ECH 都治不了。两边的地址也是一样的，" +
                "所以剩下的可能是按地址封，或者这个地址本身就连不上。"
        !reached ->
            "${rows}都不通，但分片没真的发出去（上面写了原因），所以这几次其实是同一种连接，" +
                "还不能说明分片没用。"
        else ->
            "${rows}都不通，地址也是对的：这条线路不是逐包看的，两种拆法它都能拼回去，" +
                "分片治不了。真正的解法是 ECH——把名字加密在握手里，浏览器就是这么开的——" +
                "上面那行 ECH 写了这台设备为什么用不上；用得上的设备（Android 17）这个 App 会自己用。"
    }
}

/** The site's hostname, which is the name the handshake is cut for. */
private val SITE_HOST: String = host(Site.BASE)

/** The host out of a DoH address, which is the only part worth showing. */
private fun host(url: String): String =
    url.removePrefix("https://").substringBefore('/')

/** 外观. Moved here from 我的 - it is an app preference, not forum content. */
@Composable
private fun ThemeCard(store: UserStore) {
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(text = "外观", tail = "只保存在本机")
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

/**
 * 浏览历史. The app's own feature - linux.sb records nothing of the kind - so it
 * belongs on this page rather than in 我的内容, which is the site's content.
 */
@Composable
private fun HistoryCard(store: UserStore, onOpen: () -> Unit) {
    val tokens = LocalTokens.current
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(
            text = "浏览历史",
            tail = if (store.history.isEmpty()) "" else "${store.history.size} 条"
        )
        Gap(5)
        Text(
            text = "App 自己记的，站点没有这个功能，也看不到这份记录。只存在这台手机上，" +
                "点开哪一条都是去取最新的那一页。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("查看", primary = true, onClick = onOpen)
            SmallAction(
                text = if (store.keepHistory) "记录：开" else "记录：关",
                primary = false,
                onClick = { store.updateKeepHistory(!store.keepHistory) }
            )
            if (store.history.isNotEmpty()) {
                SmallAction("清空", primary = false, onClick = store::clearHistory)
            }
        }
    }
}

/**
 * 读到哪儿了 + 追帖.
 *
 * Both live off one stored list, so they share a card. The number that matters to
 * someone reading this page is how many topics are being watched, because that is
 * the only thing here that costs requests - and only when the board is opened.
 */
@Composable
private fun ReadingCard(store: UserStore, onWatch: () -> Unit) {
    val tokens = LocalTokens.current
    val watched = Reading.watched(store.readMarks).size
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(
            text = "读到哪儿了 / 追帖",
            tail = if (watched == 0) "" else "追 $watched 个"
        )
        Gap(5)
        Text(
            text = "记的是你自己读到第几楼、当时有多少条回复，好在下次打开时问一句要不要接着看。" +
                "站点没有这个，所以它只在这台手机上，也不会传上去。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(7)
        Text(
            text = "追帖看板会把你追的帖子一次全取回来，看哪些多了回复——" +
                "一个帖子一次请求，而且只在你打开那一页的时候。没有后台任务。",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
        Gap(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("追帖看板", primary = true, onClick = onWatch)
            SmallAction(
                text = if (store.keepReadMarks) "记录：开" else "记录：关",
                primary = false,
                onClick = { store.updateKeepReadMarks(!store.keepReadMarks) }
            )
            if (store.readMarks.isNotEmpty()) {
                SmallAction("清空", primary = false, onClick = store::clearReadMarks)
            }
        }
    }
}

/** 本机提醒. A clock on this device, which the card says in as many words. */
@Composable
private fun RemindersCard(store: UserStore, onOpen: () -> Unit) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val count = store.reminders.size
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(text = "本机提醒", tail = if (count == 0) "" else "$count 个")
        Gap(5)
        Text(
            text = "到点弹一条本机通知，用的是系统的定时器。不联网、不轮询、也不是推送，" +
                "App 关着的时候它什么都不做，响的时候也没去站点看过。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        if (!Alarms.canNotify(context)) {
            Gap(7)
            Text(
                text = "系统通知现在是关着的，提醒不会弹出来。",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary
            )
        }
        Gap(12)
        SmallAction("管理提醒", primary = true, onClick = onOpen)
    }
}

/** 本地屏蔽. The card names the site's own filter rather than implying uniqueness. */
@Composable
private fun BlocksCard(store: UserStore, onOpen: () -> Unit) {
    val tokens = LocalTokens.current
    val enabled = store.blockRules.count { it.enabled }
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(text = "本地屏蔽", tail = if (enabled == 0) "" else "$enabled 条")
        Gap(5)
        Text(
            text = "命中的帖子在列表里藏起来，命中的回帖折叠成一行，点开还能看。" +
                "只是显示的时候不给你看，没有删掉任何东西，规则一关就全回来了。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(7)
        Text(
            text = "网页版首页也有一个「屏蔽设置」，那份存在站点上、只管首页、只按关键词。" +
                "这里的规则板块页和搜索结果也算，还能折叠某个人的回帖。两者互不影响。",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
        Gap(12)
        SmallAction("管理规则", primary = true, onClick = onOpen)
    }
}

/**
 * 本机数据. Kept as a card because the honest answer is short and worth stating:
 * the app stores no linux.sb content at all, so there is nothing here to clear.
 */
@Composable
private fun LocalDataCard() {
    val tokens = LocalTokens.current
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(text = "本机数据")
        Gap(5)
        Text(
            text = "站点的内容一律不落地：帖子、收藏、通知、私信都是每次打开现取的。" +
                "本机存的是外观、检查更新的时机、上次查到的版本号，以及上面那几样——" +
                "浏览历史、读到哪儿了、追的帖子、提醒、屏蔽规则。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(7)
        Text(
            text = "收藏是网站上的收藏，不是本机的列表，所以在网页上取消，App 里也就没了。" +
                "上面那几样反过来——记的都是你在这台手机上做过什么，" +
                "不是站点内容的副本，站点也看不到。",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary
        )
    }
}

/** 关于. Moved here from 我的, with the version and the repository added. */
@Composable
private fun AboutCard(onOpenReleases: () -> Unit) {
    val tokens = LocalTokens.current
    SbCard(modifier = cardWidth(), padding = 14.dp) {
        CardTitle(text = "关于", tail = "v${BuildConfig.VERSION_NAME}")
        Gap(6)
        Text(
            text = "烧饼社区的第三方 Android 客户端，内容实时来自 linux.sb。" +
                "登录表单由网站自己处理，App 不保存你的密码。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(9)
        Text(
            text = "更新来自 ${Github.REPO} 的 Releases，除此之外 App 不连接任何第三方服务。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary
        )
        Gap(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("发布页", primary = false, onClick = onOpenReleases)
        }
    }
}

/**
 * Whether the 我的 tab should hint that an update exists.
 *
 * Read from the stored tag rather than from a live check, so the hint survives a
 * restart on a weekly schedule instead of quietly disappearing until the next
 * check is due.
 */
fun hasNewerRelease(store: UserStore): Boolean =
    Releases.isNewer(store.seenTag, BuildConfig.VERSION_NAME)
