package StarBase.Android.Forum.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * How often the app looks at GitHub for a newer build.
 *
 * The policy is [due], and it is pure so it can be tested without a clock: the
 * only inputs are when the last check happened and what time it is now.
 * Declaration order is the order the switcher draws.
 */
enum class UpdateCheck(val key: String, val label: String, val intervalMs: Long) {
    LAUNCH("launch", "每次启动", 0L),
    DAILY("daily", "每天一次", 24L * 60 * 60 * 1000),
    WEEKLY("weekly", "每周一次", 7L * 24 * 60 * 60 * 1000),
    MANUAL("manual", "只手动检查", Long.MAX_VALUE);

    /**
     * True when an automatic check is owed. [MANUAL] never is, [LAUNCH] always
     * is, and a clock that has moved *backwards* counts as owed too - otherwise
     * a wrong system date could park the next check somewhere in the future and
     * silently stop updating the app.
     */
    fun due(lastCheckedAt: Long, now: Long): Boolean = when (this) {
        LAUNCH -> true
        MANUAL -> false
        else -> lastCheckedAt <= 0L || now < lastCheckedAt || now - lastCheckedAt >= intervalMs
    }

    companion object {
        /** The default for a fresh install, and the fallback for an unknown key. */
        val DEFAULT = DAILY

        fun from(key: String?): UpdateCheck = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Device-local state. Not a cache of the site.
 *
 * No post, list or profile is kept here: every screen's content comes from a
 * request made while it was on screen. What this file holds is the settings that
 * have no server side at all - appearance, how often to look at GitHub for a
 * build, what that look last found - and 浏览历史.
 *
 * The two lists that used to be here are the reason for that distinction. 收藏
 * is the *site's* list ([StarBase.Android.Forum.net.Api.toggleFavorite]), so a
 * copy here could disagree with the website and it is gone. 浏览历史 has no
 * server side to disagree with - linux.sb does not record what you read - so it
 * is the app's own feature, and local is the only place it could live.
 */
class UserStore private constructor(private val prefs: SharedPreferences) {

    var themeMode: ThemeMode by mutableStateOf(
        ThemeMode.from(prefs.getString(KEY_THEME, null))
    )
        private set

    /** How often to look for a new release. */
    var updateCheck: UpdateCheck by mutableStateOf(
        UpdateCheck.from(prefs.getString(KEY_UPDATE_CHECK, null))
    )
        private set

    /** When GitHub was last asked, as epoch millis. 0 means never. */
    var lastCheckedAt: Long by mutableStateOf(prefs.getLong(KEY_LAST_CHECK, 0L))
        private set

    /**
     * The newest tag any check has seen. Kept so the 我的 tab can still show
     * that an update exists on a later launch, without asking GitHub again on a
     * schedule the user set to weekly.
     */
    var seenTag: String by mutableStateOf(prefs.getString(KEY_SEEN_TAG, "").orEmpty())
        private set

    /**
     * 浏览历史, newest first. The app's own record - see [History].
     *
     * Held as one immutable list rather than a mutable state list because every
     * change goes through [History], which returns a new list.
     */
    var history: List<Visit> by mutableStateOf(History.decode(prefs.getString(KEY_HISTORY, "").orEmpty()))
        private set

    /**
     * Whether visits are recorded at all. On by default; turning it off stops
     * new entries but leaves what is already there, because clearing is its own
     * decision and its own button.
     */
    var keepHistory: Boolean by mutableStateOf(prefs.getBoolean(KEY_KEEP_HISTORY, true))
        private set

    init {
        // 1.0.3 and earlier kept 收藏 and 浏览历史 as delimited id/title strings.
        // 收藏 is the site's now and never comes back; 浏览历史 was rewritten
        // (JSON, timestamps, counts) and does not read the old shape. Both keys
        // are dropped rather than left on disk unread.
        val legacy = LEGACY_KEYS.filter { prefs.contains(it) }
        if (legacy.isNotEmpty()) {
            prefs.edit().apply { legacy.forEach { remove(it) } }.apply()
        }
    }

    fun updateTheme(mode: ThemeMode) {
        if (mode == themeMode) return
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.key).apply()
    }

    /**
     * Records a visit. Does nothing when recording is off.
     *
     * The title arrives later than the id - a topic is tapped before its page
     * answers - so this is called twice for one visit, and [History.record]
     * keeps the title the second call brings without counting the visit twice.
     */
    fun recordVisit(id: Int, title: String = "", forumName: String = "", now: Long = System.currentTimeMillis()) {
        if (!keepHistory || id <= 0) return
        val already = history.firstOrNull { it.id == id }
        // The second call for the same visit fills in the title; it must not read
        // as a second visit. Anything past the window is a genuine re-open.
        val sameVisit = already != null && now - already.at < SAME_VISIT_MS
        val next = if (sameVisit) {
            History.remove(history, id).let { rest ->
                listOf(
                    already!!.copy(
                        title = title.ifBlank { already.title },
                        forumName = forumName.ifBlank { already.forumName },
                        at = now
                    )
                ) + rest
            }
        } else {
            History.record(history, Visit(id = id, title = title, forumName = forumName, at = now))
        }
        if (next == history) return
        history = next
        persistHistory()
    }

    fun forgetVisit(id: Int) {
        val next = History.remove(history, id)
        if (next == history) return
        history = next
        persistHistory()
    }

    fun clearHistory() {
        if (history.isEmpty()) return
        history = emptyList()
        persistHistory()
    }

    fun updateKeepHistory(keep: Boolean) {
        if (keep == keepHistory) return
        keepHistory = keep
        prefs.edit().putBoolean(KEY_KEEP_HISTORY, keep).apply()
    }

    private fun persistHistory() {
        prefs.edit().putString(KEY_HISTORY, History.encode(history)).apply()
    }

    fun updateCheckMode(mode: UpdateCheck) {
        if (mode == updateCheck) return
        updateCheck = mode
        prefs.edit().putString(KEY_UPDATE_CHECK, mode.key).apply()
    }

    fun markChecked(at: Long, tag: String) {
        lastCheckedAt = at
        seenTag = tag
        prefs.edit()
            .putLong(KEY_LAST_CHECK, at)
            .putString(KEY_SEEN_TAG, tag)
            .apply()
    }

    companion object {
        private const val PREFS = "starbase_local"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_UPDATE_CHECK = "update_check"
        private const val KEY_LAST_CHECK = "update_last_check"
        private const val KEY_SEEN_TAG = "update_seen_tag"
        private const val KEY_HISTORY = "visits"
        private const val KEY_KEEP_HISTORY = "keep_history"

        /**
         * Two calls land for one visit - the tap, then the loaded title - so a
         * repeat inside this window is the same visit being completed rather
         * than a re-open.
         */
        private const val SAME_VISIT_MS = 60_000L

        /** Written by 1.0.3 and earlier, in a shape nothing reads now. */
        private val LEGACY_KEYS = listOf("bookmarks", "history", "titles")

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
