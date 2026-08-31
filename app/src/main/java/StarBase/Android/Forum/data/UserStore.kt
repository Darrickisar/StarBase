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
 * Device-local preferences. Not a cache.
 *
 * Nothing about linux.sb is kept here - no topics, no titles, no lists. Every
 * screen's content comes from a request made while it was on screen, so this
 * holds only settings that have no server side at all: which appearance to
 * draw, how often to look at GitHub for a build, and what that look last found.
 *
 * 收藏 used to live here as a local list. It is the site's own now
 * ([StarBase.Android.Forum.net.Api.toggleFavorite]), which is why it is gone
 * from this file: a device-local copy of it could disagree with the website.
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

    init {
        // An install upgraded from 1.0.3 still has the old local 收藏 / 浏览历史
        // / 标题 entries sitting in this file. They are no longer read, so they
        // are dropped here rather than left on disk as data the app claims not
        // to keep.
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

        /** Written by 1.0.3 and earlier; removed on first run of this build. */
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
