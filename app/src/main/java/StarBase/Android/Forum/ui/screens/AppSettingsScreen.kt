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
import StarBase.Android.Forum.net.Github
import StarBase.Android.Forum.net.ReleaseInfo
import StarBase.Android.Forum.net.Releases
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
