package StarBase.Android.Forum.data

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
 * 读到哪儿了 and 追帖看板 - two features over one record.
 *
 * linux.sb has no reading state. It knows a topic has 47 replies; it does not
 * know that *you* stopped at 32. So this is the app's own, like [History], and
 * for the same reason: there is no server-side version for it to disagree with.
 *
 * What is stored is a position and a count, never content. [seenFloor] is where
 * you were, [seenTotal] is how many replies existed when you were there. The
 * 「多了 12 条」 line is those two numbers against the live page's own count -
 * arithmetic on a fresh fetch, not a cached copy of the thread.
 */
data class ReadMark(
    val topicId: Int,
    /** Highest floor scrolled into view. 0 when only the opening post was seen. */
    val seenFloor: Int = 0,
    /** Replies the topic had at that moment, as its page reported. */
    val seenTotal: Int = 0,
    /** Epoch millis of the last time this topic was read. */
    val at: Long = 0L,
    /** Title as of that read, for the 追帖 list. Re-stamped on every open. */
    val title: String = "",
    /** True when 追帖 is on for this topic - feature 2's list is this flag. */
    val watched: Boolean = false
)

/** A 追帖 row, resolved against a freshly fetched topic. */
data class WatchStatus(
    val mark: ReadMark,
    /** Replies the topic has now. -1 when the check has not run or failed. */
    val liveTotal: Int = -1,
    /** Live title, when the check read one. */
    val liveTitle: String = "",
    /** What went wrong for this row, if anything. */
    val error: String = ""
) {
    /** How many replies landed since the last read. Never negative. */
    val fresh: Int get() = if (liveTotal < 0) 0 else (liveTotal - mark.seenTotal).coerceAtLeast(0)

    val checked: Boolean get() = liveTotal >= 0
}

/**
 * The pure half of 读到哪儿了 / 追帖看板. No Android, no clock of its own.
 */
object Reading {

    /**
     * Ceiling on stored marks. Higher than [History.CAP] would be pointless -
     * a mark for a topic no longer in history is a position you will not return
     * to - and watched topics are exempt from the trim below.
     */
    const val CAP = 300

    /**
     * Ceiling on 追帖. Each row is one request when the board is opened, so this
     * is a limit on what one refresh costs, not on storage.
     */
    const val WATCH_CAP = 20

    /** How many rows the board fetches at once. */
    const val WATCH_BATCH = 4

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Records where reading got to.
     *
     * Only ever moves forward within a visit: scrolling down to 40 and back up to
     * 12 leaves the mark at 40, because the reader has seen 40. Coming back to a
     * topic later and reading less far is still a move forward in [seenTotal],
     * which is what the 「多了 N 条」 line counts from - so that is taken from the
     * new page unconditionally.
     */
    fun mark(existing: List<ReadMark>, update: ReadMark): List<ReadMark> {
        if (update.topicId <= 0) return existing
        val previous = existing.firstOrNull { it.topicId == update.topicId }
        val merged = ReadMark(
            topicId = update.topicId,
            seenFloor = maxOf(update.seenFloor, previous?.seenFloor ?: 0),
            seenTotal = maxOf(update.seenTotal, previous?.seenTotal ?: 0),
            at = maxOf(update.at, previous?.at ?: 0L),
            title = update.title.ifBlank { previous?.title.orEmpty() },
            // 追帖 is the reader's choice and is never changed by reading.
            watched = previous?.watched ?: update.watched
        )
        if (merged == previous) return existing
        return trim(listOf(merged) + existing.filterNot { it.topicId == update.topicId })
    }

    /**
     * Called when a topic is re-read, to reset the "new since" baseline.
     *
     * Separate from [mark] because it is a different claim: [mark] says how far
     * you got, this says you have now accounted for everything the page showed.
     */
    fun catchUp(existing: List<ReadMark>, topicId: Int, total: Int, now: Long): List<ReadMark> {
        val previous = existing.firstOrNull { it.topicId == topicId } ?: return existing
        if (previous.seenTotal >= total) return existing
        return existing.map {
            if (it.topicId == topicId) it.copy(seenTotal = total, at = now) else it
        }
    }

    /** Flips 追帖 for a topic, creating a bare mark when there is none yet. */
    fun toggleWatch(
        existing: List<ReadMark>,
        topicId: Int,
        title: String = "",
        total: Int = 0,
        now: Long = 0L
    ): List<ReadMark> {
        if (topicId <= 0) return existing
        val previous = existing.firstOrNull { it.topicId == topicId }
        if (previous == null) {
            return trim(
                listOf(
                    ReadMark(
                        topicId = topicId,
                        seenTotal = total,
                        at = now,
                        title = title,
                        watched = true
                    )
                ) + existing
            )
        }
        return existing.map {
            if (it.topicId == topicId) {
                it.copy(
                    watched = !it.watched,
                    title = title.ifBlank { it.title },
                    // Turning 追帖 on means "tell me what arrives after now", so
                    // the baseline is what the page currently says.
                    seenTotal = if (!it.watched && total > 0) maxOf(it.seenTotal, total) else it.seenTotal
                )
            } else {
                it
            }
        }
    }

    /** Whether 追帖 has room for one more. */
    fun canWatchMore(existing: List<ReadMark>): Boolean = watched(existing).size < WATCH_CAP

    /** The 追帖 list, most recently read first. */
    fun watched(existing: List<ReadMark>): List<ReadMark> =
        existing.filter { it.watched }.sortedByDescending { it.at }

    fun of(existing: List<ReadMark>, topicId: Int): ReadMark? =
        existing.firstOrNull { it.topicId == topicId }

    fun forget(existing: List<ReadMark>, topicId: Int): List<ReadMark> =
        existing.filterNot { it.topicId == topicId }

    /**
     * Board rows sorted so the ones with new replies come first, then by how
     * recently they were read. A failed check sorts with the unchanged ones
     * rather than being hidden.
     */
    fun rank(rows: List<WatchStatus>): List<WatchStatus> =
        rows.sortedWith(
            compareByDescending<WatchStatus> { it.fresh > 0 }
                .thenByDescending { it.fresh }
                .thenByDescending { it.mark.at }
        )

    /**
     * Drops the oldest unwatched marks past [CAP]. Watched topics never fall off:
     * the reader asked for those by name.
     */
    private fun trim(marks: List<ReadMark>): List<ReadMark> {
        if (marks.size <= CAP) return marks
        val keep = marks.filter { it.watched }
        val rest = marks.filterNot { it.watched }.take((CAP - keep.size).coerceAtLeast(0))
        // Preserve the original order rather than putting all watched rows first.
        val kept = (keep + rest).toHashSet()
        return marks.filter { it in kept }
    }

    // ---- storage -------------------------------------------------------------

    fun encode(marks: List<ReadMark>): String = buildJsonArray {
        marks.forEach { m ->
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(m.topicId))
                    if (m.seenFloor > 0) put("f", JsonPrimitive(m.seenFloor))
                    if (m.seenTotal > 0) put("n", JsonPrimitive(m.seenTotal))
                    if (m.at > 0L) put("at", JsonPrimitive(m.at))
                    if (m.title.isNotBlank()) put("t", JsonPrimitive(m.title))
                    if (m.watched) put("w", JsonPrimitive(1))
                }
            )
        }
    }.toString()

    /** Anything unreadable decodes to nothing rather than throwing. */
    fun decode(raw: String): List<ReadMark> {
        if (raw.isBlank()) return emptyList()
        val array: JsonArray = runCatching {
            json.parseToJsonElement(raw).jsonArray
        }.getOrNull() ?: return emptyList()

        return array.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = row.int("id")
            if (id <= 0) return@mapNotNull null
            ReadMark(
                topicId = id,
                seenFloor = row.int("f"),
                seenTotal = row.int("n"),
                at = row.long("at"),
                title = row.text("t"),
                watched = row.int("w") == 1
            )
        }.take(CAP)
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    private fun JsonObject.int(key: String): Int =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 0

    private fun JsonObject.long(key: String): Long =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() } ?: 0L
}
