package StarBase.Android.Forum.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import StarBase.Android.Forum.MainActivity
import StarBase.Android.Forum.R
import StarBase.Android.Forum.data.Reminder
import StarBase.Android.Forum.data.UserStore

/**
 * What happens when a 本机提醒 goes off, and what happens after a reboot.
 *
 * Two things, and neither one touches the network:
 *
 * - [Alarms.ACTION_FIRE]: post one local notification. It says the time has come;
 *   it does not know or claim to know whether anything actually changed on the
 *   site, because finding that out would mean a request, and a notification is not
 *   a good enough reason to make one behind the reader's back.
 * - `BOOT_COMPLETED`: alarms are cleared by a reboot, so the stored list is read
 *   back and re-armed. This is the only reason the reminder list is persisted.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> rearm(context)

            Alarms.ACTION_FIRE -> fire(context, intent)
        }
    }

    private fun rearm(context: Context) {
        val store = UserStore.get(context)
        store.pruneReminders()
        Alarms.rearm(context, store.reminders)
    }

    private fun fire(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Alarms.EXTRA_ID, 0)
        if (id == 0) return
        val label = intent.getStringExtra(Alarms.EXTRA_LABEL).orEmpty()
        val topicId = intent.getIntExtra(Alarms.EXTRA_TOPIC, 0)
        val kind = intent.getStringExtra(Alarms.EXTRA_KIND).orEmpty()

        Alarms.ensureChannel(context)

        val title = when (kind) {
            Reminder.Kind.CHECK_IN.key -> "该签到了"
            else -> "该开奖了"
        }
        // Deliberately hedged: this app has not looked at the site, so it cannot
        // say the draw has happened - only that the time it was told has arrived.
        val body = label.ifBlank {
            if (kind == Reminder.Kind.CHECK_IN.key) "去 linux.sb 签到" else "去看看开奖结果"
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (topicId > 0) putExtra(MainActivity.EXTRA_OPEN_TOPIC, topicId)
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Alarms.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        // Posting without the runtime permission throws on 13+; the reader may have
        // revoked it since the alarm was set.
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }

        // A daily reminder has to book its own next occurrence: AlarmManager's
        // repeating alarms are inexact and get batched, which is the wrong trade
        // for a time the reader picked.
        val store = UserStore.get(context)
        store.reminder(id)?.let { reminder ->
            if (reminder.daily) {
                Alarms.schedule(context, reminder, System.currentTimeMillis())
            } else {
                store.pruneReminders()
            }
        }
    }
}
