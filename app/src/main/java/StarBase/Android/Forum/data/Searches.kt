package StarBase.Android.Forum.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class SearchEntry(val query: String, val at: Long = 0L, val pinned: Boolean = false)

data class SearchLibrary(
    val history: List<SearchEntry> = emptyList(),
    val saved: List<SearchEntry> = emptyList()
)

/** Local queries only. Running an entry always makes a fresh site request. */
object Searches {
    const val HISTORY_CAP = 40
    const val SAVED_CAP = 40

    fun record(library: SearchLibrary, query: String, now: Long): SearchLibrary {
        val q = query.trim()
        if (q.isEmpty()) return library
        return library.copy(history = (listOf(SearchEntry(q, now)) +
            library.history.filterNot { it.query == q }).take(HISTORY_CAP))
    }

    fun save(library: SearchLibrary, query: String, now: Long): SearchLibrary {
        val q = query.trim()
        if (q.isEmpty() || library.saved.any { it.query == q } || library.saved.size >= SAVED_CAP) {
            return library
        }
        return library.copy(saved = order(listOf(SearchEntry(q, now)) + library.saved))
    }

    fun pin(library: SearchLibrary, query: String): SearchLibrary = library.copy(
        saved = order(library.saved.map { if (it.query == query.trim()) it.copy(pinned = !it.pinned) else it })
    )

    fun removeHistory(library: SearchLibrary, query: String): SearchLibrary =
        library.copy(history = library.history.filterNot { it.query == query.trim() })

    fun removeSaved(library: SearchLibrary, query: String): SearchLibrary =
        library.copy(saved = library.saved.filterNot { it.query == query.trim() })

    fun clearHistory(library: SearchLibrary): SearchLibrary = library.copy(history = emptyList())

    fun clearSaved(library: SearchLibrary): SearchLibrary = library.copy(saved = emptyList())

    private fun order(entries: List<SearchEntry>): List<SearchEntry> =
        entries.sortedWith(compareByDescending<SearchEntry> { it.pinned }.thenByDescending { it.at })

    fun encode(library: SearchLibrary): String = buildJsonObject {
        put("history", encodeEntries(library.history.take(HISTORY_CAP)))
        put("saved", encodeEntries(library.saved.take(SAVED_CAP)))
    }.toString()

    private fun encodeEntries(entries: List<SearchEntry>): JsonArray = buildJsonArray {
        entries.forEach { entry ->
            add(buildJsonObject {
                put("query", JsonPrimitive(entry.query))
                put("at", JsonPrimitive(entry.at))
                put("pinned", JsonPrimitive(entry.pinned))
            })
        }
    }

    fun decode(raw: String): SearchLibrary {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return SearchLibrary()
        return SearchLibrary(
            history = decodeEntries(root["history"] as? JsonArray).map { it.copy(pinned = false) }
                .sortedByDescending { it.at }.take(HISTORY_CAP),
            saved = order(decodeEntries(root["saved"] as? JsonArray)).take(SAVED_CAP)
        )
    }

    /** Strict backup reader: a malformed snapshot must never become a valid empty restore. */
    fun decodeSnapshot(raw: String): SearchLibrary? {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val history = root["history"] as? JsonArray ?: return null
        val saved = root["saved"] as? JsonArray ?: return null
        if (history.size > HISTORY_CAP || saved.size > SAVED_CAP) return null
        for (entries in listOf(history, saved)) {
            val queries = mutableSetOf<String>()
            for (element in entries) {
                val row = element as? JsonObject ?: return null
                val query = (row["query"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                val at = (row["at"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
                val pinned = (row["pinned"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
                if (query.isBlank() || !queries.add(query.trim()) || at < 0L || (entries === history && pinned)) return null
            }
        }
        return decode(raw)
    }

    private fun decodeEntries(array: JsonArray?): List<SearchEntry> = array.orEmpty().mapNotNull {
        val row = it as? JsonObject ?: return@mapNotNull null
        val q = (row["query"] as? JsonPrimitive)?.takeIf { value -> value.isString }
            ?.contentOrNull?.trim().orEmpty()
        if (q.isEmpty()) return@mapNotNull null
        SearchEntry(
            query = q,
            at = (row["at"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L) ?: 0L,
            pinned = (row["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
        )
    }.distinctBy { it.query }
}

/** The guest namespace is separate from every signed-in account. */
class SearchStore(
    private val read: (String) -> String,
    private val write: (String, String) -> Unit
) {
    private constructor(preferences: SharedPreferences) : this(
        { key -> preferences.getString(key, "").orEmpty() },
        { key, value -> preferences.edit().putString(key, value).apply() }
    )

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("searches", Context.MODE_PRIVATE)
    )

    fun load(accountId: Int): SearchLibrary = Searches.decode(read(key(accountId)))

    fun save(accountId: Int, library: SearchLibrary) = write(key(accountId), Searches.encode(library))

    fun snapshot(accountId: Int): SearchLibrary = load(accountId)

    /** Replaces only the destination account. The backup coordinator validates first. */
    fun restore(accountId: Int, snapshot: SearchLibrary) = save(accountId, snapshot)

    private fun key(accountId: Int): String =
        if (accountId > 0) "v1.account.$accountId" else "v1.guest"
}
