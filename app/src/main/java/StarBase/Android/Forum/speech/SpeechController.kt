package StarBase.Android.Forum.speech

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechStatus { IDLE, STARTING, PLAYING, PAUSED, ERROR }

data class SpeechState(
    val accountId: Int? = null,
    val topics: List<SpeechTopic> = emptyList(),
    val topicIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val status: SpeechStatus = SpeechStatus.IDLE,
    val speed: Float = 1f,
    val sleepDeadline: Long? = null,
    val voice: String = "",
    val error: String? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false
) {
    val current: SpeechTopic? get() = topics.getOrNull(topicIndex)
    val sentence: SpeechSentence? get() = current?.sentences?.getOrNull(sentenceIndex)
    val active: Boolean get() = status == SpeechStatus.STARTING || status == SpeechStatus.PLAYING
}

/** All text and playback positions die with this process. No saved state or preferences. */
object SpeechController {
    internal val queue = SpeechQueue()
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutableState.asStateFlow()
    private var service: TopicSpeechService? = null
    private var status = SpeechStatus.IDLE
    private var error: String? = null
    private var voice = ""
    private var speed = 1f
    private var sleepDeadline: Long? = null
    internal var requestId = 0L
        private set
    private val startTimeout = Runnable {
        if (service == null && status == SpeechStatus.STARTING) finish("\u6717\u8bfb\u670d\u52a1\u672a\u80fd\u542f\u52a8\uff0c\u8bf7\u91cd\u8bd5")
    }

    @MainThread
    fun bindAccount(context: Context, accountId: Int) {
        checkMain()
        if (queue.bindAccount(accountId)) {
            speed = 1f
            finish()
        } else publish()
    }

    /** Only the visible Activity can add content; transport intents never carry topic text. */
    @MainThread
    fun enqueue(context: Context, accountId: Int, topic: SpeechTopic, replace: Boolean = false): Boolean {
        checkMain()
        if (!isForeground(context)) return reject("\u8bf7\u5728\u5e94\u7528\u524d\u53f0\u6dfb\u52a0\u6717\u8bfb")
        if (queue.accountId == null) queue.bindAccount(accountId)
        if (queue.accountId != accountId) return reject("\u8d26\u53f7\u5df2\u5207\u6362\uff0c\u8bf7\u91cd\u65b0\u6253\u5f00\u5e16\u5b50")
        if (!queue.accepts(topic, replace)) return reject("\u6717\u8bfb\u961f\u5217\u5df2\u6ee1\u6216\u5185\u5bb9\u8fc7\u957f")
        val wasEmpty = queue.current == null
        if (replace) {
            service?.interrupt()
            queue.clear()
        }
        if (!queue.enqueue(topic)) return reject("\u6717\u8bfb\u961f\u5217\u5df2\u6ee1\uff0c\u8bf7\u79fb\u9664\u90e8\u5206\u5e16\u5b50\u540e\u91cd\u8bd5")
        error = null
        if (wasEmpty || replace) {
            if (service != null) service?.play() else start(context)
        } else {
            publish()
            service?.refresh()
        }
        return status != SpeechStatus.ERROR
    }

    @MainThread
    fun toggle(context: Context) {
        checkMain()
        if (state.value.active) {
            if (service != null) service?.pause() else finish()
        } else play(context)
    }

    @MainThread
    fun play(context: Context) {
        checkMain()
        if (queue.current == null) return
        if (service != null) service?.play()
        else if (isForeground(context)) start(context)
    }

    @MainThread
    fun previous() = move { queue.previous() }

    @MainThread
    fun next() = move { queue.next() }

    @MainThread
    fun select(id: Long) = move { queue.select(id) }

    @MainThread
    fun remove(id: Long) {
        checkMain()
        if (queue.current?.id == id) move { queue.remove(id) }
        else { queue.remove(id); publish(); service?.refresh() }
    }

    @MainThread
    fun clear() { checkMain(); finish() }

    @MainThread
    fun dismissError() { checkMain(); error = null; if (queue.current == null) status = SpeechStatus.IDLE; publish() }

    @MainThread
    fun setSpeed(value: Float) {
        checkMain()
        if (!value.isFinite()) return
        speed = value.coerceIn(0.5f, 2f)
        publish()
        service?.speedChanged()
    }

    @MainThread
    fun setSleepTimer(minutes: Int?) {
        checkMain()
        sleepDeadline = if (queue.current != null && minutes != null && minutes > 0) {
            SystemClock.elapsedRealtime() + minutes.coerceAtMost(120) * 60_000L
        } else null
        publish()
        service?.scheduleSleep()
    }

    internal fun attach(value: TopicSpeechService) {
        service = value
        main.removeCallbacks(startTimeout)
    }

    internal fun owns(value: TopicSpeechService): Boolean = service === value

    internal fun destroyed(value: TopicSpeechService) {
        if (service !== value) return
        service = null
        finish()
    }

    internal fun update(next: SpeechStatus, message: String? = null, voiceName: String = voice) {
        status = next
        error = message
        voice = voiceName
        publish()
    }

    internal fun finish(message: String? = null) {
        requestId++
        main.removeCallbacks(startTimeout)
        queue.clear()
        sleepDeadline = null
        voice = ""
        error = message
        status = if (message == null) SpeechStatus.IDLE else SpeechStatus.ERROR
        val old = service
        service = null
        publish()
        old?.shutdown()
    }

    internal fun publish() {
        mutableState.value = SpeechState(
            queue.accountId, queue.topics, queue.topicIndex, queue.sentenceIndex,
            status, speed, sleepDeadline, voice, error, queue.hasPrevious, queue.hasNext
        )
    }

    private fun move(change: () -> Unit) {
        checkMain()
        val active = state.value.active
        service?.interrupt()
        change()
        if (queue.current == null) finish()
        else if (active) { publish(); service?.play() }
        else { publish(); service?.refresh() }
    }

    private fun start(context: Context) {
        requestId++
        update(SpeechStatus.STARTING)
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, 10_000L)
        try {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context, TopicSpeechService::class.java)
                    .setAction(TopicSpeechService.ACTION_START)
                    .putExtra(TopicSpeechService.EXTRA_REQUEST, requestId))
        } catch (_: RuntimeException) {
            finish("\u7cfb\u7edf\u672a\u5141\u8bb8\u542f\u52a8\u6717\u8bfb\uff0c\u8bf7\u4fdd\u6301\u5e94\u7528\u5728\u524d\u53f0\u540e\u91cd\u8bd5")
        }
    }

    private fun reject(message: String): Boolean { error = message; publish(); return false }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun isForeground(context: Context): Boolean {
        var candidate = context
        while (candidate is ContextWrapper && candidate !is Activity) {
            val base = candidate.baseContext
            if (base === candidate) break
            candidate = base
        }
        val activity = candidate as? Activity ?: return false
        return !activity.isFinishing && !activity.isDestroyed &&
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
    }
}
