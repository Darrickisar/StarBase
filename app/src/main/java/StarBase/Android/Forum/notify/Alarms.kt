package StarBase.Android.Forum.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import StarBase.Android.Forum.data.Reminder
import StarBase.Android.Forum.data.Reminders

/**
 * 本机提醒: the platform half.
 *
 * What this sets is a clock, and that is all it is. `AlarmManager` fires a
 * broadcast at a wall-clock time; [ReminderReceiver] turns that into a local
 * notification. At no point does anything here reach the network, poll the site,
 * or run while the app is closed - between arming an alarm and it going off, this
 * app is not running.
 *
 * That is the honest shape of "remind me when the draw happens" for a client with
 * no server. The website cannot do it at all; a push notification would need a
 * backend this project does not have.
 */
object Alarms {

    const val CHANNEL_ID = "starbase_reminders"

    /** Extras on the broadcast, read back by [ReminderReceiver]. */
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_LABEL = "reminder_label"
    const val EXTRA_TOPIC = "reminder_topic"
    const val EXTRA_KIND = "reminder_kind"

    /**
     * Creates the notification channel. Safe to call repeatedly.
     *
     * Android 8+ refuses to post without one, and the channel's own name is what
     * the reader sees in system settings when they want to turn this off - so it
     * says what these notifications are rather than naming the app again.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本机提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "开奖和签到的本机闹钟。到点只弹通知，不联网。"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** True when the platform will actually show what we post. */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Whether an exact alarm is permitted right now.
     *
     * Android 12+ gates exact alarms behind a separate user grant. Rather than
     * failing, [schedule] falls back to an inexact one - a 签到 nudge that lands
     * within the hour is still useful, and a draw reminder that needs the minute
     * is the reader's call to enable.
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /**
     * Arms one reminder. Replaces any alarm already set for the same id, because
     * the PendingIntent request code is the reminder id.
     */
    fun schedule(context: Context, reminder: Reminder, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        if (!reminder.enabled) {
            cancel(context, reminder.id)
            return
        }
        val fireAt = Reminders.nextFire(reminder, now)
        // A one-shot whose moment has gone is not moved to a new time - it just
        // does not get an alarm.
        if (fireAt <= now) return

        val pending = broadcast(context, reminder)
        // setAlarmClock is the one tier the system does not defer: these are times
        // the reader chose, and a 开奖 reminder that arrives an hour late is no
        // reminder. Without the exact-alarm grant, a windowed alarm is the most
        // the platform will give, and that is still better than nothing.
        if (canScheduleExact(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        }
    }

    fun cancel(context: Context, id: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            // The data URI keeps one id's PendingIntent distinct from another's;
            // extras alone do not, so cancelling would hit the wrong alarm.
            setData(android.net.Uri.parse("starbase://reminder/$id"))
        }
        PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let {
            manager.cancel(it)
            it.cancel()
        }
    }

    /**
     * Re-arms everything. Called on launch, because alarms do not survive a reboot
     * or an app update and the stored list is the only record of them.
     */
    fun rearm(context: Context, reminders: List<Reminder>, now: Long = System.currentTimeMillis()) {
        ensureChannel(context)
        reminders.forEach { schedule(context, it, now) }
    }

    private fun broadcast(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            setData(android.net.Uri.parse("starbase://reminder/${reminder.id}"))
            putExtra(EXTRA_ID, reminder.id)
            putExtra(EXTRA_LABEL, reminder.label)
            putExtra(EXTRA_TOPIC, reminder.topicId)
            putExtra(EXTRA_KIND, reminder.kind.key)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACTION_FIRE = "StarBase.Android.Forum.REMINDER"
}
