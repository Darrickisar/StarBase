package StarBase.Android.Forum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import StarBase.Android.Forum.data.ThemeMode
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.ui.Shell
import StarBase.Android.Forum.ui.theme.StarBaseTheme

/**
 * The only Activity. Everything else is Compose.
 *
 * The app is a live client: nothing is bundled, every screen reads linux.sb when
 * it is opened. The only state that lives on the device is in [UserStore] - the
 * theme, local bookmarks and read history - and the session cookie, which the
 * WebView owns.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                Shell(store = store)
            }
        }
    }
}
