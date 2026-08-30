package StarBase.Android.Forum.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * Appearance preference.
 *
 * §4.3 asks for one short segmented control, and this is what is in it. The two
 * dark rooms are the plan's own 液态玻璃 / 经典深色; [LIGHT] is the light room,
 * added because a dark-only app is unusable in daylight. The stored keys are
 * unchanged, so an older install keeps the appearance it was on.
 */
/**
 * Declaration order is the order both switchers draw, so the default comes
 * first. The stored keys are unchanged, so an existing install keeps its choice.
 */
enum class ThemeMode(val key: String, val label: String) {
    LIGHT("light", "浅色玻璃"),
    GLASS("glass", "深色玻璃"),
    CLASSIC("classic", "经典深色");

    /** True where panels are translucent - both glass rooms, not 经典深色. */
    val glassy: Boolean get() = this != CLASSIC

    companion object {
        /** The default for a fresh install, and the fallback for an unknown key. */
        val DEFAULT = LIGHT

        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Device-local preferences and bookmarks.
 *
 * Who the user *is* comes from the live session, not from here. This holds only
 * what belongs to the device: the theme, a local bookmark list, and recently
 * read topics. Nothing here is sent to the server.
 */
class UserStore private constructor(private val prefs: SharedPreferences) {

    var themeMode: ThemeMode by mutableStateOf(
        ThemeMode.from(prefs.getString(KEY_THEME, null))
    )
        private set

    /** Locally bookmarked topic ids, newest first. */
    val bookmarks: SnapshotStateList<Int> =
        readIds(KEY_BOOKMARKS).toMutableStateList()

    /** Recently opened topic ids, newest first, capped. */
    val history: SnapshotStateList<Int> =
        readIds(KEY_HISTORY).toMutableStateList()

    /** Titles for bookmarked/read topics so those lists render without a fetch. */
    private val titles: MutableMap<Int, String> = readTitles()

    fun updateTheme(mode: ThemeMode) {
        if (mode == themeMode) return
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.key).apply()
    }

    fun isBookmarked(id: Int): Boolean = bookmarks.contains(id)

    /** Returns true when the topic ended up bookmarked. */
    fun toggleBookmark(id: Int, title: String = ""): Boolean {
        val added = if (bookmarks.remove(id)) {
            false
        } else {
            bookmarks.add(0, id)
            true
        }
        if (added && title.isNotBlank()) titles[id] = title
        if (!added) titles.remove(id)
        persistIds(KEY_BOOKMARKS, bookmarks)
        persistTitles()
        return added
    }

    fun clearBookmarks() {
        bookmarks.forEach { titles.remove(it) }
        bookmarks.clear()
        persistIds(KEY_BOOKMARKS, bookmarks)
        persistTitles()
    }

    fun recordVisit(id: Int, title: String = "") {
        history.remove(id)
        history.add(0, id)
        while (history.size > HISTORY_CAP) {
            val dropped = history.removeAt(history.lastIndex)
            if (!bookmarks.contains(dropped)) titles.remove(dropped)
        }
        if (title.isNotBlank()) titles[id] = title
        persistIds(KEY_HISTORY, history)
        persistTitles()
    }

    fun clearHistory() {
        history.forEach { if (!bookmarks.contains(it)) titles.remove(it) }
        history.clear()
        persistIds(KEY_HISTORY, history)
        persistTitles()
    }

    fun titleOf(id: Int): String = titles[id].orEmpty()

    // ---- persistence ---------------------------------------------------------

    private fun readIds(key: String): List<Int> =
        prefs.getString(key, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()

    private fun persistIds(key: String, values: List<Int>) {
        prefs.edit().putString(key, values.joinToString(",")).apply()
    }

    /** Stored as "idtitle..." - separators that cannot occur in a title. */
    private fun readTitles(): MutableMap<Int, String> {
        val raw = prefs.getString(KEY_TITLES, "").orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        return raw.split(RECORD_SEP)
            .mapNotNull { entry ->
                val parts = entry.split(FIELD_SEP)
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val title = parts.getOrNull(1).orEmpty()
                if (title.isBlank()) null else id to title
            }
            .toMap()
            .toMutableMap()
    }

    private fun persistTitles() {
        val encoded = titles.entries.joinToString(RECORD_SEP.toString()) { (id, title) ->
            "$id$FIELD_SEP$title"
        }
        prefs.edit().putString(KEY_TITLES, encoded).apply()
    }

    companion object {
        private const val PREFS = "starbase_local"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY = "history"
        private const val KEY_TITLES = "titles"
        private const val HISTORY_CAP = 80
        private const val FIELD_SEP = ''
        private const val RECORD_SEP = ''

        @Volatile
        private var instance: UserStore? = null

        fun get(context: Context): UserStore =
            instance ?: synchronized(this) {
                instance ?: UserStore(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
