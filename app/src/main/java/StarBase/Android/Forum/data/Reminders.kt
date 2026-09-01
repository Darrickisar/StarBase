package StarBase.Android.Forum.data

import java.util.Calendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 抽奖开奖提醒 / 签到提醒.
 *
 * A clock on this device, and nothing else. There is no server here to push
 * anything: what this schedules is an `AlarmManager` alarm that fires locally and
 * posts a local notification. No polling, no background fetch, no network at the
 * moment it fires - the notification just says "go look", and looking is the
 * ordinary request the app already makes when you open the screen.
 *
 * That is the whole reason this is the right shape for the feature. The website
 * cannot remind you of anything while it is closed; a client with a timer can,
 * without pretending to have a push channel it does not have.
 */
data class Reminder(
    /** Stable id, also the AlarmManager request code. */
    val id: Int,
    val kind: Kind,
    /** When it fires, epoch millis. */
    val at: Long,
    /** What the notification says. */
    val label: String,
    /** The topic to open, for [Kind.DRAW]. 0 for 签到. */
    val topicId: Int = 0,
    val enabled: Boolean = true,
    /** [Kind.CHECK_IN] repeats daily; a draw fires once. */
    val daily: Boolean = false
) {
    enum class Kind(val key: String, val label: String) {
        /** 抽奖开奖. One shot, at the time the topic printed. */
        DRAW("draw", "开奖提醒"),

        /** 签到. Every day at a time you pick. */
        CHECK_IN("checkin", "签到提醒")
    }

    /** Fired and not repeating - nothing more will come of it. */
    fun spent(now: Long): Boolean = !daily && at <= now
}

object Reminders {

    /** Enough for a handful of draws plus 签到. */
    const val CAP = 40

    /** 签到 defaults to mid-morning rather than midnight. */
    const val CHECK_IN_HOUR = 9
    const val CHECK_IN_MINUTE = 30

    /** The fixed id for 签到, so re-scheduling replaces it instead of stacking. */
    const val CHECK_IN_ID = 1

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Alarm id for a draw on a topic. Derived from the topic id so the same topic
     * cannot end up with two alarms, and offset clear of [CHECK_IN_ID].
     */
    fun drawId(topicId: Int): Int = 1000 + topicId

    /**
     * Adds or replaces a reminder.
     *
     * Replacing by id matters: a draw time that the site moved should update the
     * alarm rather than add a second one for the same topic.
     */
    fun put(existing: List<Reminder>, reminder: Reminder): List<Reminder> {
        val index = existing.indexOfFirst { it.id == reminder.id }
        return if (index >= 0) {
            existing.toMutableList().apply { this[index] = reminder }
        } else {
            (existing + reminder).take(CAP)
        }
    }

    fun remove(existing: List<Reminder>, id: Int): List<Reminder> =
        existing.filterNot { it.id == id }

    fun of(existing: List<Reminder>, id: Int): Reminder? = existing.firstOrNull { it.id == id }

    /**
     * Drops one-shot reminders whose time has passed.
     *
     * Called on launch. A 签到 reminder is never dropped - it repeats - and a draw
     * is kept for a while after firing so the list can still show it happened,
     * which is why this takes a grace period rather than comparing to `now`.
     */
    fun prune(existing: List<Reminder>, now: Long, graceMs: Long = DAY): List<Reminder> =
        existing.filter { it.daily || it.at > now - graceMs }

    /** Next to fire, soonest first. Disabled and spent ones are left out. */
    fun upcoming(existing: List<Reminder>, now: Long): List<Reminder> =
        existing.filter { it.enabled && (it.daily || it.at > now) }
            .sortedBy { nextFire(it, now) }

    /**
     * When this reminder next goes off.
     *
     * A daily reminder's stored [Reminder.at] is a time of day: if today's has
     * already passed, the answer is tomorrow's. A one-shot returns its own time
     * whether or not that is in the past - the caller decides what a past alarm
     * means, and silently moving it would be worse than showing it as spent.
     */
    fun nextFire(reminder: Reminder, now: Long): Long {
        if (!reminder.daily) return reminder.at
        val cal = Calendar.getInstance().apply { timeInMillis = reminder.at }
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }

    /** Epoch millis for a time of day today, used when 签到 is first turned on. */
    fun todayAt(hour: Int, minute: Int, now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Whether a draw at [drawAt] is worth an alarm.
     *
     * A draw already past cannot be reminded about, and one more than a month out
     * is far enough that the reader will have opened the app again long before.
     */
    fun drawWorthScheduling(drawAt: Long, now: Long): Boolean =
        drawAt > now + 60_000L && drawAt < now + 31L * DAY

    /** `9 月 3 日 20:00`, or `今天 20:00` / `明天 20:00` when it is close. */
    fun whenText(at: Long, now: Long): String {
        val today = startOfDay(now)
        val day = startOfDay(at)
        val clock = two(field(at, Calendar.HOUR_OF_DAY)) + ":" + two(field(at, Calendar.MINUTE))
        return when {
            day == today -> "今天 $clock"
            day == today + DAY -> "明天 $clock"
            day == today - DAY -> "昨天 $clock"
            else -> "${field(at, Calendar.MONTH) + 1} 月 ${field(at, Calendar.DAY_OF_MONTH)} 日 $clock"
        }
    }

    /** `还有 3 小时 20 分` / `已过期`. */
    fun countdownText(at: Long, now: Long): String {
        val gap = at - now
        if (gap <= 0L) return "已过期"
        val hours = gap / 3_600_000L
        val minutes = (gap % 3_600_000L) / 60_000L
        return when {
            hours >= 24 -> "还有 ${gap / DAY} 天"
            hours > 0 -> "还有 $hours 小时 $minutes 分"
            else -> "还有 $minutes 分"
        }
    }

    // ---- storage -------------------------------------------------------------

    fun encode(reminders: List<Reminder>): String = buildJsonArray {
        reminders.forEach { r ->
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(r.id))
                    put("k", JsonPrimitive(r.kind.key))
                    put("at", JsonPrimitive(r.at))
                    put("l", JsonPrimitive(r.label))
                    if (r.topicId > 0) put("t", JsonPrimitive(r.topicId))
                    if (!r.enabled) put("off", JsonPrimitive(1))
                    if (r.daily) put("d", JsonPrimitive(1))
                }
            )
        }
    }.toString()

    fun decode(raw: String): List<Reminder> {
        if (raw.isBlank()) return emptyList()
        val array: JsonArray = runCatching {
            json.parseToJsonElement(raw).jsonArray
        }.getOrNull() ?: return emptyList()

        return array.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = row.int("id")
            val at = row.long("at")
            if (id == 0 || at <= 0L) return@mapNotNull null
            Reminder(
                id = id,
                kind = Reminder.Kind.entries.firstOrNull { it.key == row.text("k") }
                    ?: Reminder.Kind.DRAW,
                at = at,
                label = row.text("l"),
                topicId = row.int("t"),
                enabled = row.int("off") != 1,
                daily = row.int("d") == 1
            )
        }.take(CAP)
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    private fun JsonObject.int(key: String): Int =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 0

    private fun JsonObject.long(key: String): Long =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() } ?: 0L

    // ---- calendar ------------------------------------------------------------

    private const val DAY = 24L * 60 * 60 * 1000

    private fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun field(at: Long, field: Int): Int =
        Calendar.getInstance().apply { timeInMillis = at }.get(field)

    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()
}
