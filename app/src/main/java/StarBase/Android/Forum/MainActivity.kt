package StarBase.Android.Forum

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.Coil
import coil.ImageLoader
import StarBase.Android.Forum.data.ThemeMode
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.net.DohAuto
import StarBase.Android.Forum.net.CronetTransport
import StarBase.Android.Forum.net.Frag
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.SiteDns
import StarBase.Android.Forum.ui.Shell
import StarBase.Android.Forum.ui.IncomingContent
import StarBase.Android.Forum.ui.IncomingLinks
import StarBase.Android.Forum.ui.IncomingIntents
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
    private var pendingContent by mutableStateOf<IncomingContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingTopic = intent?.getIntExtra(EXTRA_OPEN_TOPIC, 0) ?: 0
        pendingContent = intent?.let(::incoming)

        val store = UserStore.get(this)

        // Net initialization: Cronet needs application context for engine pool.
        Net.init(this)

        // 域名解析, before anything can make a request: the resolver is a plain
        // object with no access to preferences, so this is where the stored
        // setting reaches it.
        SiteDns.configure(store.dohEnabled, store.dohChoice)

        // 自动换服务器, wired here for the same reason and with the same care about
        // which way the dependency points: [DohAuto] does the deciding and knows
        // nothing about preferences, and this is the one place that holds both.
        //
        // It is allowed to move only while the stored choice is blank - that is
        // the built-in default, which nobody picked and which is measured on one
        // network only. A server the user chose stays chosen even when it fails;
        // 应用设置 reports that instead. The result is remembered separately so a
        // phone that had to move does not rediscover it every launch.
        val main = Handler(Looper.getMainLooper())
        DohAuto.wanted = { store.dohEnabled && store.dohServer.isBlank() }
        DohAuto.adopt = { url ->
            main.post {
                if (store.dohEnabled && store.dohServer.isBlank()) {
                    store.updateDohAuto(url)
                    SiteDns.configure(store.dohEnabled, store.dohChoice)
                    Net.client.connectionPool.evictAll()
                    CronetTransport.invalidate()
                }
            }
        }

        // 分片, for the same reason and in the same breath.
        Frag.configure(store.fragEnabled)

        // Images go through the app's own client too, so that avatars and post
        // pictures resolve the same way pages do. Coil would otherwise build a
        // client of its own and ask the system resolver, which on a network that
        // answers wrongly is exactly the half that would keep failing.
        val imageContext = applicationContext
        Coil.setImageLoader {
            ImageLoader.Builder(imageContext)
                .callFactory { Net.client }
                .build()
        }

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
                    onTopicOpened = { pendingTopic = 0 },
                    incoming = pendingContent,
                    onIncomingHandled = { pendingContent = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_OPEN_TOPIC, 0).let { if (it > 0) pendingTopic = it }
        pendingContent = incoming(intent)
    }

    private fun incoming(intent: Intent): IncomingContent? = IncomingIntents.read(intent, packageName)

    companion object {
        /** Set by [StarBase.Android.Forum.notify.ReminderReceiver] on a 开奖 reminder. */
        const val EXTRA_OPEN_TOPIC = "open_topic"
    }
}
