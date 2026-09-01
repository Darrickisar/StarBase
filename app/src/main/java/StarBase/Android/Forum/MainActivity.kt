package StarBase.Android.Forum

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import StarBase.Android.Forum.data.ThemeMode
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.ui.Shell
import StarBase.Android.Forum.ui.theme.StarBaseTheme

/**
 * The only Activity. Everything else is Compose.
 *
 * The app is a live client: nothing is bundled and nothing is cached, so every
 * screen reads linux.sb when it is opened. The only state that lives on the
 * device is in [UserStore] - the appearance, the update-check schedule and the
 * last release tag seen - and the session cookie, which the WebView owns. No
 * post, list or profile is ever written to disk.
 */
class MainActivity : ComponentActivity() {

    /**
     * A topic a 本机提醒 asked for, handed to [Shell] once and then cleared.
     *
     * Held as state rather than read straight off the intent so that a reminder
     * tapped while the app is already open still lands - `onNewIntent` is the only
     * thing that runs in that case.
     */
    private var pendingTopic by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingTopic = intent?.getIntExtra(EXTRA_OPEN_TOPIC, 0) ?: 0

        val store = UserStore.get(this)

        // themes.xml names the light room, since that is the default appearance.
        // Someone who chose a dark one would otherwise get a pale flash before
        // the first frame, so the window background is corrected here - ahead of
        // setContent, which is the only place early enough to matter.
        if (store.themeMode != ThemeMode.LIGHT) {
            window.setBackgroundDrawableResource(R.color.glass_base)
        }

        setContent {
            // Three appearances: the light glass room (default), the dark glass
            // room, and the flat dark one. Switching is instant - one token set.
            StarBaseTheme(mode = store.themeMode) {
                Shell(
                    store = store,
                    openTopicId = pendingTopic,
                    onTopicOpened = { pendingTopic = 0 }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_OPEN_TOPIC, 0).let { if (it > 0) pendingTopic = it }
    }

    companion object {
        /** Set by [StarBase.Android.Forum.notify.ReminderReceiver] on a 开奖 reminder. */
        const val EXTRA_OPEN_TOPIC = "open_topic"
    }
}
