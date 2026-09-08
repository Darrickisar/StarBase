package StarBase.Android.Forum.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

object IncomingIntents {
    fun read(intent: Intent, packageName: String): IncomingContent? = runCatching {
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> IncomingLinks.selected(
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty())
            Intent.ACTION_VIEW -> IncomingLinks.siteUrl(intent.dataString.orEmpty())?.let { IncomingContent(url = it) }
            Intent.ACTION_SEND -> when {
                intent.type == "text/plain" -> IncomingLinks.shared(
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
                intent.type?.startsWith("image/") == true -> {
                    val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    uri?.takeIf {
                        it.scheme == "content" && !it.authority.isNullOrBlank() &&
                            it.authority != "$packageName.files" && it.toString().length <= 8192
                    }?.let { IncomingContent(image = it.toString()) }
                }
                else -> null
            }
            else -> null
        }
    }.getOrNull()
}
