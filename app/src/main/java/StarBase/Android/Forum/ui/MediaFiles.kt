package StarBase.Android.Forum.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import StarBase.Android.Forum.net.MediaLinks
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class SharedImage(val file: File, val mime: String)

object MediaFiles {
    private const val MAX_BYTES = 40L * 1024 * 1024

    suspend fun download(context: Context, raw: String): SharedImage = withContext(Dispatchers.IO) {
        val url = requireNotNull(MediaLinks.https(raw)) { "图片地址无效" }
        val request = Request.Builder().url(url).header("User-Agent", Net.userAgent())
            .header("Referer", "${Site.BASE}/").build()
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("media-") && it.lastModified() < System.currentTimeMillis() - 3_600_000 }
            ?.forEach { it.delete() }
        Net.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "图片下载失败：${response.code}" }
            val body = checkNotNull(response.body) { "图片为空" }
            val mime = body.contentType()?.toString()?.substringBefore(';').orEmpty()
            check(mime.startsWith("image/")) { "这个地址没有返回图片" }
            check(body.contentLength() <= MAX_BYTES) { "图片超过 40 MB" }
            val ext = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/svg+xml" -> "svg"
                "image/avif" -> "avif"
                else -> "jpg"
            }
            val file = File(dir, "media-${UUID.randomUUID()}.$ext")
            try {
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var count = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            check(count <= MAX_BYTES) { "图片超过 40 MB" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                SharedImage(file, mime)
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
    }

    fun share(context: Context, image: SharedImage) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", image.file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = image.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("图片", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享图片"))
    }

    suspend fun save(context: Context, image: SharedImage, uri: Uri) = withContext(Dispatchers.IO) {
        checkNotNull(context.contentResolver.openOutputStream(uri, "w")) { "无法写入所选位置" }.use { output ->
            image.file.inputStream().use { it.copyTo(output) }
        }
    }
}
