package StarBase.Android.Forum.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分享成图.
 *
 * A screenshot of this app carries the status bar, the reply bar and half of
 * whatever comes next. This draws the post on its own instead - one composable,
 * rendered off-screen to a bitmap - so what leaves the app is a card that was
 * designed rather than a crop of a phone.
 *
 * Nothing is cached: the PNG is written into the app's own cache under `share/`,
 * handed to the chooser through [FileProvider], and the directory is swept on the
 * way in so yesterday's cards do not accumulate. It is a file on its way out, not
 * a local copy of a post.
 */
object ShareCard {

    /** Cards older than this are cleared out whenever a new one is made. */
    private const val KEEP_MS = 60L * 60 * 1000

    private const val DIR = "share"

    /**
     * Writes [bitmap] into the share cache and returns the file.
     *
     * PNG rather than JPEG: these are flat colour and text, which JPEG turns to
     * mush at exactly the edges a reader looks at.
     */
    suspend fun write(context: Context, bitmap: Bitmap, name: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            sweep(dir)
            val file = File(dir, "$name.png")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        }

    /**
     * Hands a written card to the system chooser.
     *
     * The grant is read-only and scoped to this one URI, and it is the only thing
     * the provider exposes besides the updater's APK - see `res/xml/file_paths.xml`.
     */
    fun share(context: Context, file: File, subject: String = "") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享到").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    /** A stable-ish file name. The topic and floor make it recognisable in a chooser. */
    fun nameFor(topicId: Int, floor: Int): String =
        "starbase-$topicId" + (if (floor > 0) "-$floor" else "") + "-" + (System.currentTimeMillis() / 1000)

    private fun sweep(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}
