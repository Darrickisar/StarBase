package StarBase.Android.Forum.ui

import android.os.SystemClock

/**
 * How the app decides whether what is on screen is still current.
 *
 * The app holds no cache: every screen's data came from a real request at the
 * moment it was made. What can go stale is a screen you loaded and then left
 * sitting - so each loader stamps [mark] on success, and the shell asks
 * [isStale] when you come back to it.
 *
 * The window exists so that flicking between tabs does not re-fetch the same
 * page over and over. Coming back to the app after a while does re-fetch,
 * because that is the moment the content most likely moved on.
 *
 * [SystemClock.elapsedRealtime] rather than wall time: it cannot jump when the
 * clock or timezone changes.
 */
class Freshness(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var loadedAt = 0L

    /** True before anything has loaded, or once the window has passed. */
    val stale: Boolean
        get() = loadedAt == 0L || SystemClock.elapsedRealtime() - loadedAt > windowMs

    /** Seconds since the last successful load; 0 when nothing has loaded yet. */
    val ageSeconds: Long
        get() = if (loadedAt == 0L) 0 else (SystemClock.elapsedRealtime() - loadedAt) / 1000

    /** Called after a load succeeds. */
    fun mark() {
        loadedAt = SystemClock.elapsedRealtime()
    }

    /** Forces the next check to report stale, e.g. after posting a reply. */
    fun invalidate() {
        loadedAt = 0L
    }

    companion object {
        /** Long enough that tab-flicking is free, short enough to feel live. */
        const val DEFAULT_WINDOW_MS = 90_000L

        /** Badges are cheap and the thing you most want current. */
        const val BADGE_WINDOW_MS = 45_000L
    }
}
