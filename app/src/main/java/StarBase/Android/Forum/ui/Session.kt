package StarBase.Android.Forum.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import StarBase.Android.Forum.data.Me
import StarBase.Android.Forum.data.NotifyState
import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.WebViewCookieJar

/**
 * App-wide session state: who we are, and the unread badges.
 *
 * Held once at the shell level so every tab agrees on whether we are logged in.
 * A refresh is cheap - one home-page fetch - and is triggered after login, on
 * resume, and when a screen reports an auth failure.
 */
class SessionViewModel(
    private val readMe: suspend () -> Me? = Api::me,
    private val readNotifications: suspend () -> NotifyState = Api::notifyState,
    private val hasCookies: () -> Boolean = WebViewCookieJar::hasSiteCookies,
    clock: () -> Long = android.os.SystemClock::elapsedRealtime
) : ViewModel() {

    var me by mutableStateOf<Me?>(null)
        private set

    var unreadNotifications by mutableStateOf(0)
        private set

    var unreadMessages by mutableStateOf(0)
        private set

    var checking by mutableStateOf(false)
        private set

    private val fresh = Freshness(windowMs = Freshness.BADGE_WINDOW_MS, now = clock)
    private var revision = 0

    /** True once we have completed at least one check, so UI can avoid flicker. */
    var resolved by mutableStateOf(false)
        private set

    val signedIn: Boolean get() = me != null

    fun refresh() {
        if (checking) return
        val requestRevision = revision
        checking = true
        viewModelScope.launch {
            try {
                // Cheap pre-check: no cookie at all means definitely not signed in.
                if (!hasCookies()) {
                    me = null
                    unreadNotifications = 0
                    unreadMessages = 0
                } else {
                    val identity = runCatching { readMe() }
                        .onFailure { if (it is CancellationException) throw it }
                    if (requestRevision != revision) return@launch
                    identity.onSuccess { me = it }
                    val notifications = runCatching { readNotifications() }
                        .onFailure { if (it is CancellationException) throw it }
                    if (requestRevision != revision) return@launch
                    notifications.onSuccess { state ->
                        unreadNotifications = state.notifications
                        unreadMessages = state.messages
                        if (!state.signedIn) me = null
                    }
                }
                resolved = true
                fresh.mark()
            } finally {
                if (requestRevision == revision) checking = false
            }
        }
    }

    /**
     * Re-checks the badges when the app returns to the foreground, but only once
     * they have had time to be wrong. Unread counts are the one thing you notice
     * immediately if it is stale.
     */
    fun refreshIfStale() {
        if (fresh.stale) refresh()
    }

    /** Called by screens that hit a 401/403 so the badges stop lying. */
    fun invalidate() {
        revision++
        checking = false
        me = null
        unreadNotifications = 0
        unreadMessages = 0
        fresh.invalidate()
    }

    fun signOut(onDone: () -> Unit = {}) {
        WebViewCookieJar.clear()
        invalidate()
        resolved = true
        onDone()
    }
}
