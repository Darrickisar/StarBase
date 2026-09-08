# Background Topic Speech

## Integration

Public Compose entry points:

```kotlin
TopicSpeechAction(detail: TopicDetail, accountId: Int)
SpeechPlayerBar(onOpenTopic: ((Int) -> Unit)? = null)
```

Call `SpeechController.bindAccount(context, accountId)` on the main thread once
`Session.resolved` is true and whenever the resolved account changes. Use account
`0` for an actual guest/logout. Rebinding the same account preserves playback,
including Activity recreation. Do not bind a provisional guest during restoration.

Pass `detail.copy(comments = vm.comments.toList())` to include every currently
loaded reply page. The module does not fetch additional pages. Mount one player
bar above the app navigation; `onOpenTopic` receives the current topic ID. Without
that callback, an explicit intent opens the app's existing topic deep link.

The foreground Activity is required to enqueue content. Transport intents carry
only a command and a session generation, never bodies or topic objects.

Manifest integration, owned by the parent:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<queries>
    <intent>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent>
</queries>

<!-- Inside application. No intent filter or media-button receiver is needed. -->
<service
    android:name="StarBase.Android.Forum.speech.TopicSpeechService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

The module uses existing Compose, core, lifecycle and Kotlin dependencies plus
framework TTS/MediaSession. It does not require AndroidX Media or Media3 additions.
It does not request POST_NOTIFICATIONS: media-session notifications are exempt
from the runtime permission denial. Manual channel blocking remains a system setting.

## Behavior And Limits

- Opening only, opening plus author replies, or all loaded discussion. Replies
  are deduplicated and each topic keeps its loaded/total reply counts.
- One utterance at a time, at most 280 UTF-16 characters. Pause resumes from the
  beginning of the interrupted sentence/chunk; previous/next move by chunk.
  Selecting a queue row starts that topic from its first chunk.
- At most 12 topics and 240,000 spoken characters in the queue; each topic is
  capped at 80,000 characters and 800 chunks. Input work is bounded to 2,000
  blocks, 24,000 characters per block and 240,000 inspected characters per topic.
  Truncation is marked in the queue's scope label.
- Code/media/rule blocks, backtick code, URL addresses and email addresses are
  skipped. Human link labels remain readable. The shared parser loses inline
  HTML code ranges, so already-flattened inline code cannot be identified here.
- Only installed offline voices are selected. Missing engines/voices and
  initialization, synthesis, output and timeout errors clear the queue and stop
  the service. The player exposes the error and the system TTS settings entry.
- Speed is 0.5x to 2x. Sleep timers stop and clear playback. TTS initialization
  times out after 15 seconds, and a stuck utterance after 180 seconds.
- Audio focus uses speech attributes and pauses on duck requests. Transient
  focus gain resumes the interrupted sentence; permanent loss and noisy-unplug
  require explicit resume. Pause demotes the foreground service immediately and
  releases its wake lock. An idle player is cleared after five minutes; Android
  may stop a demoted background service earlier. No indefinite paused foreground
  service is retained.
- Completion, account change, stop and errors release TTS, focus, wake lock,
  receiver, media session and notification. Process death discards playback.
  No boot start, sticky restart, body persistence or synthesized audio file exists.

## Verification

`app/src/test/speech/run-offline-check.ps1 -IncludeAndroid` invokes the cached
Kotlin compiler directly in a unique system temporary directory, compiles all
speech sources and instrumentation sources, and runs the speech JUnit tests.
It does not run Gradle or write to the app build directory. The cached dependency
classpath is a supplemental check; the parent still owns the integrated Gradle
build and lint.

JUnit: `StarBase.Android.Forum.speech.SpeechTextTest` and `SpeechQueueTest` cover
mode scope, identity, filtering, hostile input, Unicode boundaries, bounds,
stale callbacks, account resets, removal, pause positions and queue completion.

Instrumentation: `StarBase.Android.Forum.speech.SpeechServiceSmokeTest` uses a
foreground test Activity, actual TTS and MediaController. It checks play/pause
and previous through headset media-button dispatch, sentence navigation, resume,
same-account binding, account clearing, actual completion, manifest privacy and
rejection of application-context queue requests/empty starts.
Voice-dependent tests skip only when an engine or an English/Chinese offline
voice is absent. An installed but failing engine is a failure, not a skip.

Parent/device checks: lock screen and background playback, wired/Bluetooth
headset buttons and unplug, competing audio/transient focus, declined notification
permission, sleep and paused timeouts, missing voices, Activity recreation,
process termination, and narrow/large-font controls. These require runtime device
verification; standalone compilation cannot establish them.

## API References

- https://developer.android.com/media/optimize/audio-focus
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/speech/tts/TextToSpeech.java
