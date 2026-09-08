package StarBase.Android.Forum.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object DraftImageFiles {
    fun image(context: Context, uri: Uri): DraftImage? {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.files" ||
            uri.query != null || uri.fragment != null) return null
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != "vision_drafts" || !DraftImage.validName(parts[1])) return null
        return DraftImage(parts[1])
    }

    fun uri(context: Context, image: DraftImage): Uri {
        require(DraftImage.validName(image.fileName))
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file(context, image.fileName))
    }

    fun cleanRemoved(context: Context, before: List<WritingDraft>, after: List<WritingDraft>) {
        val retained = after.flatMap { it.localImages }.map { it.fileName }.toSet()
        before.flatMap { it.localImages }.map { it.fileName }.distinct()
            .filterNot { it in retained }.filter(DraftImage::validName)
            .forEach { file(context, it).delete() }
        // An edited image can be abandoned before it is attached to a draft.
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        File(context.filesDir, "vision_drafts").listFiles()?.forEach { candidate ->
            if (candidate.isFile && DraftImage.validName(candidate.name) && candidate.name !in retained &&
                candidate.lastModified() < cutoff) candidate.delete()
        }
    }

    private fun file(context: Context, name: String) = File(File(context.filesDir, "vision_drafts"), name)
}
