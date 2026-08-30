package StarBase.Android.Forum.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A file the user picked, already in memory.
 *
 * The site's cap is 20 MB and the upload is one multipart request, so reading the
 * whole thing is simpler than streaming and bounded by a limit we check before the
 * request goes out.
 */
data class PickedFile(
    val name: String,
    val mediaType: String,
    val bytes: ByteArray
) {
    // ByteArray makes the generated equals/hashCode identity-based, which is
    // misleading on a data class; compare what actually identifies the pick.
    override fun equals(other: Any?): Boolean =
        other is PickedFile && other.name == name &&
            other.mediaType == mediaType && other.bytes.size == bytes.size

    override fun hashCode(): Int = (name.hashCode() * 31 + mediaType.hashCode()) * 31 + bytes.size
}

/**
 * Reads a picked document. Null when it cannot be read or is over [maxMb].
 *
 * The size is checked before the bytes are pulled in where the provider reports one,
 * so a huge pick is refused rather than loaded and then rejected.
 */
fun readPicked(context: Context, uri: Uri, maxMb: Int): Result<PickedFile> = runCatching {
    val resolver = context.contentResolver
    var name = "attachment"
    var declared = -1L

    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }
                ?.let { if (!cursor.isNull(it)) name = cursor.getString(it) }
            cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 }
                ?.let { if (!cursor.isNull(it)) declared = cursor.getLong(it) }
        }
    }

    if (maxMb > 0 && declared > 0 && declared > maxMb * 1024L * 1024L) {
        error("文件超过 $maxMb MB")
    }

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("读不到这个文件")

    if (maxMb > 0 && bytes.size > maxMb * 1024L * 1024L) {
        error("文件超过 $maxMb MB")
    }

    PickedFile(
        name = name,
        mediaType = resolver.getType(uri) ?: "application/octet-stream",
        bytes = bytes
    )
}

/**
 * Remembers a document picker.
 *
 * [mimeTypes] filters what the system picker shows; the site's own extension list is
 * the thing that decides, and that is enforced at upload time, so this is only a
 * convenience.
 */
@Composable
fun rememberFilePicker(
    maxMb: Int,
    mimeTypes: Array<String> = arrayOf("image/*", "application/zip"),
    onPicked: (Result<PickedFile>) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onPicked(readPicked(context, uri, maxMb))
    }
    return { launcher.launch(mimeTypes) }
}
