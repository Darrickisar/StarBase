package StarBase.Android.Forum.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 常去板块 / 板块自选排序.
 *
 * The site's board list has one fixed order for everybody. Two ways to change
 * that here, and neither one stores a board:
 *
 * - **常去** is computed, not stored. It comes out of [History] - the boards your
 *   own visits landed in most - so it costs nothing on disk and no extra request.
 * - **置顶** is a list of ids you chose. That is a preference, the same kind of
 *   thing as which theme you are on.
 *
 * The names and counts still come from the fetched page every time. What is
 * stored is an order, and an id that no longer exists simply never matches.
 */
enum class BoardOrder(val key: String, val label: String) {
    /** The site's own order, untouched. */
    SITE("site", "站点顺序"),

    /** Most-visited first, worked out from 浏览历史. */
    FREQUENT("frequent", "常去优先"),

    /** Whatever you dragged it to. */
    CUSTOM("custom", "自定顺序");

    companion object {
        val DEFAULT = SITE

        fun from(key: String?): BoardOrder = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** A board plus why it is where it is, so the list can show that. */
data class RankedBoard(
    val board: ForumRef,
    /** Visits recorded in this board. 0 when history has none. */
    val visits: Int = 0,
    val pinned: Boolean = false
)

object Boards {

    /** Beyond this, "常去" stops meaning anything. */
    const val FREQUENT_SHOWN = 6

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Counts visits per board name.
     *
     * By name rather than by id: [Visit] records the board a topic was in as the
     * page printed it, and the id is not always on that page. Names are what both
     * sides have.
     */
    fun visitCounts(history: List<Visit>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        history.forEach { visit ->
            val name = visit.forumName.trim()
            if (name.isEmpty()) return@forEach
            counts[name] = (counts[name] ?: 0) + visit.count.coerceAtLeast(1)
        }
        return counts
    }

    /**
     * Puts the board list in the order asked for.
     *
     * Pinned boards lead in every mode, in the order they were pinned - a pin is
     * an explicit instruction and outranks a computed one. [BoardOrder.SITE]
     * with nothing pinned returns the input order untouched.
     */
    fun arrange(
        boards: List<ForumRef>,
        order: BoardOrder,
        pinned: List<Int>,
        history: List<Visit>
    ): List<RankedBoard> {
        val counts = visitCounts(history)
        val ranked = boards.map { board ->
            RankedBoard(
                board = board,
                visits = counts[board.name.trim()] ?: 0,
                pinned = board.id in pinned
            )
        }

        val pinnedFirst = pinned.mapNotNull { id -> ranked.firstOrNull { it.board.id == id } }
        val rest = ranked.filterNot { it.pinned }

        val tail = when (order) {
            BoardOrder.SITE -> rest
            // Ties keep the site's order, so an unvisited list is not reshuffled
            // into something arbitrary.
            BoardOrder.FREQUENT -> rest.sortedByDescending { it.visits }
            BoardOrder.CUSTOM -> rest
        }
        return pinnedFirst + tail
    }

    /**
     * The 常去 strip: boards with at least one recorded visit, most first.
     *
     * Empty when history has nothing to say, which is the honest answer for a
     * fresh install or for someone who turned 浏览历史 off.
     */
    fun frequent(boards: List<ForumRef>, history: List<Visit>, limit: Int = FREQUENT_SHOWN): List<RankedBoard> {
        val counts = visitCounts(history)
        return boards
            .mapNotNull { board ->
                val n = counts[board.name.trim()] ?: 0
                if (n <= 0) null else RankedBoard(board = board, visits = n)
            }
            .sortedByDescending { it.visits }
            .take(limit)
    }

    fun togglePin(pinned: List<Int>, id: Int): List<Int> =
        if (id in pinned) pinned - id else pinned + id

    /**
     * Moves a pinned board one place up or down.
     *
     * Only pinned boards can be reordered by hand. Dragging within the unpinned
     * tail would need a stored position for every board on the site, and pinning
     * the two or three you care about is the same result for one integer each.
     */
    fun movePin(pinned: List<Int>, id: Int, up: Boolean): List<Int> {
        val index = pinned.indexOf(id)
        if (index < 0) return pinned
        val target = if (up) index - 1 else index + 1
        if (target !in pinned.indices) return pinned
        return pinned.toMutableList().apply {
            this[index] = this[target].also { this[target] = this[index] }
        }
    }

    fun encodePins(pinned: List<Int>): String = buildJsonArray {
        pinned.forEach { add(JsonPrimitive(it)) }
    }.toString()

    fun decodePins(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        val array: JsonArray = runCatching {
            json.parseToJsonElement(raw).jsonArray
        }.getOrNull() ?: return emptyList()
        return array.mapNotNull {
            runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull()
        }.filter { it > 0 }.distinct()
    }
}
