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
 * 浏览历史 - the one thing on the device that is the app's own rather than the
 * site's.
 *
 * linux.sb has no reading history, so there is nothing to read one from and
 * nothing this could disagree with. That makes it the opposite case from 收藏,
 * which is the site's list and is therefore never kept here.
 *
 * [title] is a note of what this entry was called when it was opened, not a copy
 * of the topic kept for reading. Opening one is always a fresh request for the
 * live page: a renamed topic shows its new title the moment it is opened, and
 * the entry is re-stamped with it.
 */
data class Visit(
    val id: Int,
    val title: String,
    /** The board it was in, when the page said. Blank when it did not. */
    val forumName: String = "",
    /** Epoch millis of the most recent visit. */
    val at: Long = 0L,
    /** How many times it has been opened. 1 for a first visit. */
    val count: Int = 1
)

/** One day's worth of visits, newest day first. */
data class VisitDay(
    val label: String,
    val visits: List<Visit>
)

/**
 * The pure half of 浏览历史: everything that decides what the list looks like,
 * with the clock passed in so it can be tested without one.
 */
object History {

    /** Beyond this the oldest entries fall off. */
    const val CAP = 300

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Records a visit, newest first.
     *
     * Re-opening a topic moves it to the top and counts up rather than adding a
     * second row - a history that lists the same topic eleven times is a worse
     * record of what you read than one that says you read it eleven times. A
     * blank [Visit.title] does not overwrite a title already recorded: the id is
     * known the moment a topic is tapped, the title only once the page answers.
     */
    fun record(existing: List<Visit>, visit: Visit): List<Visit> {
        if (visit.id <= 0) return existing
        val previous = existing.firstOrNull { it.id == visit.id }
        val merged = Visit(
            id = visit.id,
            title = visit.title.ifBlank { previous?.title.orEmpty() },
            forumName = visit.forumName.ifBlank { previous?.forumName.orEmpty() },
            at = visit.at,
            count = (previous?.count ?: 0) + 1
        )
        return (listOf(merged) + existing.filterNot { it.id == visit.id }).take(CAP)
    }

    /** Drops one entry. */
    fun remove(existing: List<Visit>, id: Int): List<Visit> = existing.filterNot { it.id == id }

    /**
     * Groups by calendar day for display.
     *
     * Calendar days rather than "24 hours ago": something read last night belongs
     * under 昨天 even when that was only ten hours back, because that is how a
     * reader remembers it.
     */
    fun byDay(visits: List<Visit>, now: Long): List<VisitDay> {
        val today = startOfDay(now)
        val yesterday = today - DAY
        return visits
            .sortedByDescending { it.at }
            .groupBy { startOfDay(it.at) }
            .toSortedMap(reverseOrder())
            .map { (day, sameDay) ->
                VisitDay(
                    label = when {
                        day >= today -> "今天"
                        day >= yesterday -> "昨天"
                        day >= today - 6 * DAY -> weekday(day)
                        else -> date(day)
                    },
                    visits = sameDay
                )
            }
    }

    /**
     * Filters by title or id. A query of digits also matches an id, because "the
     * one that was #17536" is a real way to look for a topic.
     */
    fun search(visits: List<Visit>, query: String): List<Visit> {
        val q = query.trim()
        if (q.isEmpty()) return visits
        return visits.filter { visit ->
            visit.title.contains(q, ignoreCase = true) ||
                visit.forumName.contains(q, ignoreCase = true) ||
                visit.id.toString() == q ||
                visit.id.toString().startsWith(q)
        }
    }

    /** `刚刚` / `12 分钟前` / `3 小时前`, then the clock, then the date. */
    fun ago(at: Long, now: Long): String {
        val gap = now - at
        return when {
            gap < 0L -> time(at)
            gap < 60_000L -> "刚刚"
            gap < 3_600_000L -> "${gap / 60_000L} 分钟前"
            at >= startOfDay(now) -> "${gap / 3_600_000L} 小时前"
            at >= startOfDay(now) - DAY -> "昨天 " + time(at)
            else -> date(startOfDay(at)) + " " + time(at)
        }
    }

    // ---- storage -------------------------------------------------------------

    /**
     * Stored as JSON rather than a delimited string.
     *
     * The old local 收藏 list packed its titles with `` separators on the
     * grounds that a title could not contain one. That holds until a title does,
     * and then the whole file decodes into nonsense; a JSON array cannot be
     * broken by its own content. Read as a tree, like the release JSON, so R8
     * needs no keep rules for it.
     */
    fun encode(visits: List<Visit>): String = buildJsonArray {
        visits.forEach { visit ->
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(visit.id))
                    put("t", JsonPrimitive(visit.title))
                    if (visit.forumName.isNotBlank()) put("f", JsonPrimitive(visit.forumName))
                    put("at", JsonPrimitive(visit.at))
                    if (visit.count > 1) put("n", JsonPrimitive(visit.count))
                }
            )
        }
    }.toString()

    /** Anything unreadable decodes to an empty history rather than throwing. */
    fun decode(raw: String): List<Visit> {
        if (raw.isBlank()) return emptyList()
        val array: JsonArray = runCatching {
            json.parseToJsonElement(raw).jsonArray
        }.getOrNull() ?: return emptyList()

        return array.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = row.int("id")
            if (id <= 0) return@mapNotNull null
            Visit(
                id = id,
                title = row.text("t"),
                forumName = row.text("f"),
                at = row.long("at"),
                count = row.int("n").coerceAtLeast(1)
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

    private fun weekday(at: Long): String = when (field(at, Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "星期一"
        Calendar.TUESDAY -> "星期二"
        Calendar.WEDNESDAY -> "星期三"
        Calendar.THURSDAY -> "星期四"
        Calendar.FRIDAY -> "星期五"
        Calendar.SATURDAY -> "星期六"
        else -> "星期日"
    }

    private fun date(at: Long): String =
        "${field(at, Calendar.MONTH) + 1} 月 ${field(at, Calendar.DAY_OF_MONTH)} 日"

    private fun time(at: Long): String =
        two(field(at, Calendar.HOUR_OF_DAY)) + ":" + two(field(at, Calendar.MINUTE))
}
