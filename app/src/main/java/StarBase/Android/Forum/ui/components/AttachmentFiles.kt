package StarBase.Android.Forum.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import StarBase.Android.Forum.net.Parse

internal data class AttachmentInfo(val name: String, val mediaType: String, val size: Long)
internal data class PreparedAttachment(val name: String, val mediaType: String, val bytes: ByteArray)

internal suspend fun attachmentInfo(context: Context, uri: Uri): AttachmentInfo = withContext(Dispatchers.IO) {
    var name = "attachment"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                name = cursor.getString(it).orEmpty().ifBlank { name }
            }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                size = cursor.getLong(it)
            }
        }
    }
    AttachmentInfo(name, context.contentResolver.getType(uri) ?: "application/octet-stream", size)
}

internal suspend fun prepareAttachment(
    context: Context,
    uri: Uri,
    info: AttachmentInfo,
    compress: Boolean,
    uploader: Parse.Uploader,
    onRead: (Long, Long) -> Unit
): PreparedAttachment = withContext(Dispatchers.IO) {
    val jobContext = currentCoroutineContext()
    val canCompress = compress && info.mediaType in setOf("image/jpeg", "image/png")
    val limit = if (canCompress) AttachmentRules.MAX_SOURCE_BYTES else AttachmentRules.uploadLimit(uploader.maxMb)
    require(info.size <= limit) { "文件超过 ${limit / 1024 / 1024} MB" }
    jobContext.ensureActive()
    val source = context.contentResolver.openInputStream(uri)?.use { input ->
        readAttachmentBytes(input, limit) { read -> jobContext.ensureActive(); onRead(read, info.size) }
    } ?: error("无法读取文件，请重新选择")
    jobContext.ensureActive()
    val original = PreparedAttachment(info.name, info.mediaType, source)
    val prepared = if (canCompress) compressImage(original) else original
    jobContext.ensureActive()
    AttachmentRules.validate(prepared.name, prepared.bytes.size.toLong(), uploader.maxMb, uploader.accepts)
    prepared
}

private fun compressImage(original: PreparedAttachment): PreparedAttachment {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original.bytes, 0, original.bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无效" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > AttachmentRules.IMAGE_MAX_EDGE * 2) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(original.bytes, 0, original.bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法读取图片")
    var resized: Bitmap? = null
    var oriented: Bitmap? = null
    try {
        val ratio = minOf(1f, AttachmentRules.IMAGE_MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        resized = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(original.bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
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
        oriented = if (matrix.isIdentity) resized else Bitmap.createBitmap(
            resized, 0, 0, resized.width, resized.height, matrix, true)
        // PNG remains PNG so transparent pixels are preserved. Animated formats stay original.
        val format = if (original.mediaType == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val bytes = ByteArrayOutputStream().use { output ->
            check(oriented.compress(format, 85, output)) { "图片压缩失败" }
            output.toByteArray()
        }
        return if (bytes.size < original.bytes.size) original.copy(bytes = bytes) else original
    } finally {
        oriented?.takeIf { it !== resized && it !== bitmap }?.recycle()
        resized?.takeIf { it !== bitmap }?.recycle()
        bitmap.recycle()
    }
}
