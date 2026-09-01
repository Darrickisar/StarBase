package StarBase.Android.Forum.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.Reminder
import StarBase.Android.Forum.data.Reminders
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.notify.Alarms
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics

/*
 * 本机提醒.
 *
 * The list of alarms this device is holding, plus the 签到 switch. What this page
 * is careful to be clear about is what a reminder actually is here: a clock, not a
 * subscription. Nothing checks the site on your behalf; when the time comes the
 * phone shows a notification and the app is still closed.
 *
 * 开奖 reminders are set from a topic - the draw time is printed there - so this
 * page manages them rather than creating them.
 */

@Composable
fun RemindersScreen(
    store: UserStore,
    onBack: () -> Unit,
    onTopic: (Int) -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    var notice by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()

    // Asked for at the moment it is needed, which is the first time 签到 is turned
    // on - not at launch, where it would be a permission prompt with no context.
    val askNotify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notice = if (granted) "" else "没有通知权限，提醒不会弹出来"
    }

    fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Alarms.canNotify(context)) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) { store.pruneReminders() }

    val checkIn = store.reminder(Reminders.CHECK_IN_ID)
    val draws = store.reminders
        .filter { it.kind == Reminder.Kind.DRAW }
        .sortedBy { Reminders.nextFire(it, now) }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailBar(
            title = "本机提醒",
            subtitle = if (Alarms.canNotify(context)) "" else "通知已关闭",
            onBack = onBack
        )
        if (notice.isNotBlank()) {
            NoticeBar(notice) { notice = "" }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item("what") {
                Gap(6)
                GlassPanel(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    level = GlassLevel.LOW,
                    shape = RoundedCornerShape(16.dp),
                    padding = 13.dp
                ) {
                    Text(
                        text = "提醒是这台手机上的一个闹钟：到点弹一条本机通知，" +
                            "不联网、不轮询、也不是推送。App 关着的时候它什么都不做，" +
                            "只有系统的定时器在等。",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary
                    )
                }
            }

            item("checkin") {
                Gap(18)
                SectionHeader(title = "签到提醒", subtitle = "每天一次")
                Gap(8)
                CheckInCard(
                    reminder = checkIn,
                    now = now,
                    onToggle = {
                        if (checkIn == null) {
                            ensureNotifyPermission()
                            val at = Reminders.todayAt(
                                Reminders.CHECK_IN_HOUR,
                                Reminders.CHECK_IN_MINUTE,
                                now
                            )
                            val reminder = Reminder(
                                id = Reminders.CHECK_IN_ID,
                                kind = Reminder.Kind.CHECK_IN,
                                at = at,
                                label = "去 linux.sb 签到",
                                daily = true
                            )
                            store.putReminder(reminder)
                            Alarms.schedule(context, reminder)
                        } else {
                            Alarms.cancel(context, Reminders.CHECK_IN_ID)
                            store.removeReminder(Reminders.CHECK_IN_ID)
                        }
                    },
                    onHour = { hour ->
                        ensureNotifyPermission()
                        val reminder = Reminder(
                            id = Reminders.CHECK_IN_ID,
                            kind = Reminder.Kind.CHECK_IN,
                            at = Reminders.todayAt(hour, 0, now),
                            label = "去 linux.sb 签到",
                            daily = true
                        )
                        store.putReminder(reminder)
                        Alarms.schedule(context, reminder)
                    }
                )
            }

            item("draws-head") {
                Gap(18)
                SectionHeader(
                    title = "开奖提醒",
                    subtitle = if (draws.isEmpty()) "在抽奖帖里设置" else "${draws.size} 个"
                )
                Gap(8)
            }

            if (draws.isEmpty()) {
                item("draws-empty") {
                    EmptyPanel(
                        "还没有开奖提醒",
                        "打开一个写着开奖时间的抽奖帖，右上角就能设一个"
                    )
                }
            } else {
                items(draws, key = { it.id }) { reminder ->
                    DrawCard(
                        reminder = reminder,
                        now = now,
                        onOpen = { if (reminder.topicId > 0) onTopic(reminder.topicId) },
                        onRemove = {
                            Alarms.cancel(context, reminder.id)
                            store.removeReminder(reminder.id)
                        }
                    )
                    Gap(8)
                }
            }

            if (!Alarms.canScheduleExact(context)) {
                item("exact") {
                    Gap(14)
                    Text(
                        text = "系统没有给本应用「精确闹钟」权限，提醒可能晚几分钟到。" +
                            "签到没关系，开奖想准就去系统设置里允许。",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary,
                        modifier = Modifier.padding(horizontal = SbMetrics.pagePadding)
                    )
                }
            }
            item("tail") { Gap(24) }
        }
    }
}

@Composable
private fun CheckInCard(
    reminder: Reminder?,
    now: Long,
    onToggle: () -> Unit,
    onHour: (Int) -> Unit
) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.LOW,
        shape = RoundedCornerShape(16.dp),
        padding = 13.dp
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (reminder == null) "没有开" else "每天 " +
                            Reminders.whenText(Reminders.nextFire(reminder, now), now)
                                .substringAfterLast(' '),
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary
                    )
                    if (reminder != null) {
                        Gap(3)
                        MetaText(
                            "下次 " + Reminders.whenText(Reminders.nextFire(reminder, now), now)
                        )
                    }
                }
                SmallAction(
                    text = if (reminder == null) "打开" else "关掉",
                    primary = reminder == null,
                    onClick = onToggle
                )
            }
            if (reminder != null) {
                Gap(12)
                Text(
                    text = "提醒时间",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary
                )
                Gap(6)
                val hour = java.util.Calendar.getInstance()
                    .apply { timeInMillis = reminder.at }
                    .get(java.util.Calendar.HOUR_OF_DAY)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 9, 12, 20, 22).forEach { h ->
                        SegmentPill(
                            label = "$h:00",
                            selected = h == hour,
                            onClick = { onHour(h) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawCard(
    reminder: Reminder,
    now: Long,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val tokens = LocalTokens.current
    val fireAt = Reminders.nextFire(reminder, now)
    val spent = reminder.spent(now)
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        onClick = onOpen,
        level = GlassLevel.LOW,
        shape = RoundedCornerShape(16.dp),
        padding = 13.dp
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reminder.label.ifBlank { "开奖提醒" },
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                GlassChip(
                    text = if (spent) "已过" else Reminders.countdownText(fireAt, now),
                    tint = if (spent) tokens.textTertiary else tokens.hotTint
                )
            }
            Gap(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaText(Reminders.whenText(fireAt, now))
                Spacer(Modifier.weight(1f))
                SmallAction("删除", primary = false, onClick = onRemove)
            }
        }
    }
}
