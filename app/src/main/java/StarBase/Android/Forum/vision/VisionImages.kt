package StarBase.Android.Forum.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import StarBase.Android.Forum.net.MediaLinks
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class LoadedVisionImage(val bitmap: Bitmap, val hash: String)

internal object VisionImages {
    // User-created files only. Parent exposes <files-path name="vision_drafts" path="vision_drafts/" />.
    const val DRAFT_DIRECTORY = "vision_drafts"

    private val mediaClient by lazy {
        Net.client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
    }

    suspend fun load(context: Context, source: String): LoadedVisionImage {
        require(source.length <= VisionLimits.URL && source.none(Char::isISOControl)) { "图片地址无效" }
        val uri = Uri.parse(source)
        val bytes = when (uri.scheme?.lowercase()) {
            "content" -> {
                require(!uri.authority.isNullOrBlank() && uri.fragment == null) { "图片地址无效" }
                contentBytes(context, uri)
            }
            "https" -> remoteBytes(source)
            else -> throw IllegalArgumentException("请选择系统图片或 HTTPS 图片")
        }
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            LoadedVisionImage(decode(bytes), hash).also { currentCoroutineContext().ensureActive() }
        }
    }

    private suspend fun contentBytes(context: Context, uri: Uri): ByteArray = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        val resource = AtomicReference<Closeable?>()
        cont.invokeOnCancellation {
            signal.cancel()
            runCatching { resource.getAndSet(null)?.close() }
        }
        Dispatchers.IO.dispatch(cont.context, Runnable {
            try {
                cont.context.ensureActive()
                val mime = context.contentResolver.getType(uri)
                require(mime == null || mime.startsWith("image/") && mime != "image/svg+xml") { "所选文件不是可识别的图片" }
                val asset = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw IOException("无法读取图片，请重新选择")
                resource.set(asset)
                asset.use {
                    cont.context.ensureActive()
                    require(asset.length <= VisionLimits.INPUT_BYTES) { "图片超过 16 MB" }
                    asset.createInputStream().use { input ->
                        resource.set(input)
                        cont.context.ensureActive()
                        val result = readVisionBytes(input) { cont.context.ensureActive() }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally { runCatching { resource.getAndSet(null)?.close() } }
        })
    }

    private suspend fun remoteBytes(raw: String): ByteArray = suspendCancellableCoroutine { cont ->
        val callRef = AtomicReference<Call?>()
        cont.invokeOnCancellation { callRef.getAndSet(null)?.cancel() }
        fun fetch(source: String, redirects: Int) {
            if (!cont.isActive) return
            try {
                require(redirects <= 5) { "图片重定向次数过多" }
                val url = requireNotNull(MediaLinks.https(source)) { "图片地址必须使用 HTTPS" }
                val request = Request.Builder().url(url)
                    .header("User-Agent", Net.userAgent()).header("Referer", "${Site.BASE}/")
                    .header("Accept", "image/*").header("Cache-Control", "no-store").build()
                val call = mediaClient.newCall(request)
                callRef.set(call)
                if (!cont.isActive) { call.cancel(); return }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                cont.context.ensureActive()
                                require(MediaLinks.https(response.request.url.toString()) != null) { "图片重定向不安全" }
                                if (response.code in setOf(301, 302, 303, 307, 308)) {
                                    val next = response.header("Location")?.let(response.request.url::resolve)
                                    require(next != null && next.isHttps && next.username.isEmpty() && next.password.isEmpty()) {
                                        "图片重定向不安全"
                                    }
                                    fetch(next.toString(), redirects + 1)
                                    return
                                }
                                check(response.isSuccessful) { "图片下载失败（${response.code}）" }
                                val body = checkNotNull(response.body) { "图片为空" }
                                require(body.contentType()?.type == "image" && body.contentType()?.subtype != "svg+xml") {
                                    "这个地址没有返回可识别的图片"
                                }
                                require(body.contentLength() <= VisionLimits.INPUT_BYTES) { "图片超过 16 MB" }
                                val bytes = body.byteStream().use { input -> readVisionBytes(input) { cont.context.ensureActive() } }
                                if (cont.isActive) cont.resume(bytes)
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
        fetch(raw, 0)
    }

    internal fun decode(bytes: ByteArray): Bitmap {
        require(bytes.size in 1..VisionLimits.INPUT_BYTES) { "图片为空或超过 16 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = VisionLimits.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("图片已损坏或格式不支持")
        try {
            require(decoded.width.toLong() * decoded.height <= VisionLimits.DECODED_PIXELS &&
                maxOf(decoded.width, decoded.height) <= VisionLimits.DECODED_EDGE) { "解码图片尺寸超限" }
            val orientation = try {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = orientationMatrix(orientation)
            val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0,
                decoded.width, decoded.height, matrix, true)
            if (oriented !== decoded) decoded.recycle()
            // Flatten alpha onto white so the preview, OCR and exported attachment agree.
            return try {
                Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888).also { flat ->
                    Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(oriented, 0f, 0f, null) }
                    flat.setHasAlpha(false)
                }
            } finally { oriented.recycle() }
        } catch (e: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw e
        }
    }

    internal fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    suspend fun render(original: Bitmap, edits: List<VisionEdit>): Bitmap = withContext(Dispatchers.Default) {
        require(edits.size <= VisionLimits.EDITS && edits.all(VisionEdit::valid)) { "图片编辑数量超限或选区无效" }
        var working = checkNotNull(original.copy(Bitmap.Config.ARGB_8888, true)) { "图片内存不足" }
        try {
            for (edit in edits) {
                currentCoroutineContext().ensureActive()
                if (edit.tool == VisionTool.CROP) {
                    val region = edit.region(working.width, working.height)
                    val cropped = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
                    Canvas(cropped).drawBitmap(working, -region.left.toFloat(), -region.top.toFloat(), null)
                    working.recycle()
                    working = cropped
                } else drawEdit(working, edit)
            }
            currentCoroutineContext().ensureActive()
            working
        } catch (e: Throwable) { working.recycle(); throw e }
    }

    internal fun drawEdit(bitmap: Bitmap, edit: VisionEdit) {
        require(edit.valid()) { "选区太小" }
        val canvas = Canvas(bitmap)
        if (edit.tool == VisionTool.REDACT) {
            val rect = edit.region(bitmap.width, bitmap.height)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
                Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false })
        } else if (edit.tool == VisionTool.ARROW) {
            val sx = edit.start.x * bitmap.width
            val sy = edit.start.y * bitmap.height
            val ex = edit.end.x * bitmap.width
            val ey = edit.end.y * bitmap.height
            val width = (minOf(bitmap.width, bitmap.height) * 0.009f).coerceIn(3f, 28f)
            val length = minOf(hypot(ex - sx, ey - sy) * 0.35f, width * 5f)
            val angle = atan2(ey - sy, ex - sx)
            val path = Path().apply {
                moveTo(sx, sy); lineTo(ex, ey)
                moveTo(ex - length * cos(angle - 0.55f), ey - length * sin(angle - 0.55f))
                lineTo(ex, ey)
                lineTo(ex - length * cos(angle + 0.55f), ey - length * sin(angle + 0.55f))
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            }
            paint.color = Color.WHITE; paint.strokeWidth = width + 2f
            canvas.drawPath(path, paint)
            paint.color = Color.rgb(205, 38, 52); paint.strokeWidth = width
            canvas.drawPath(path, paint)
        }
    }

    suspend fun saveDraft(context: Context, bitmap: Bitmap): String {
        val directory = File(context.filesDir, DRAFT_DIRECTORY)
        val file = File(directory, "${UUID.randomUUID()}.png")
        return try {
            withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "无法创建草稿图片目录" }
                currentCoroutineContext().ensureActive()
                file.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图片保存失败" }
                }
                currentCoroutineContext().ensureActive()
                check(file.length() in 1..VisionLimits.INPUT_BYTES.toLong()) { "草稿图片超过 16 MB" }
                FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
            }
        } catch (e: Throwable) { file.delete(); throw e }
    }
}
