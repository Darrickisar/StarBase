package StarBase.Android.Forum.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.min

/**
 * Turns a picked image into the square JPEG /avatar_upload is written for.
 *
 * The site's own page opens a crop dialog, draws the result into a 200x200
 * canvas and posts `canvas.toBlob('image/jpeg', 0.9)`. The app has no crop
 * dialog, so it takes the largest centred square instead - but the size, the
 * format and the quality are the site's numbers, not invented ones, because the
 * handler on the other end was written for what that canvas produces.
 *
 * Runs off the main thread: the caller is a ViewModel write.
 */
fun squareJpeg(context: Context, uri: Uri, side: Int = 200, quality: Int = 90): ByteArray {
    require(side > 0 && quality in 0..100)
    val resolver = context.contentResolver

    // Ask for the size first so a 12-megapixel photo is never fully decoded.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: throw IOException("读不到这张图片")
    // Bounds-only decoding returns null even for a valid image.
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    val shortest = min(bounds.outWidth, bounds.outHeight)
    if (shortest <= 0) throw IOException("这张图片无法解码")

    var sample = 1
    while (shortest / (sample * 2) >= side) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: throw IOException("读不到这张图片")

    // BitmapFactory ignores the EXIF orientation, and a phone camera writes one
    // for nearly every portrait shot - without this the avatar arrives sideways.
    val bitmaps = arrayListOf(decoded)
    try {
        val upright = decoded.upright(orientationOf(resolver, uri)).also { bitmaps += it }
        val edge = min(upright.width, upright.height)
        val square = Bitmap.createBitmap(
            upright,
            (upright.width - edge) / 2,
            (upright.height - edge) / 2,
            edge,
            edge
        ).also { bitmaps += it }
        val scaled = (if (edge == side) square else Bitmap.createScaledBitmap(square, side, side, true))
            .also { bitmaps += it }

        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("图片编码失败")
        return out.toByteArray()
    } finally {
        bitmaps.distinct().forEach { it.recycle() }
    }
}

private fun orientationOf(resolver: ContentResolver, uri: Uri): Int = runCatching {
    resolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun Bitmap.upright(orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
