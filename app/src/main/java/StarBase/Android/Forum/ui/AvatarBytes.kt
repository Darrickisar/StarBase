package StarBase.Android.Forum.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
    val resolver = context.contentResolver

    // Ask for the size first so a 12-megapixel photo is never fully decoded.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: throw IOException("读不到这张图片")
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
    val upright = decoded.upright(orientationOf(resolver, uri))
    val edge = min(upright.width, upright.height)
    val square = Bitmap.createBitmap(
        upright,
        (upright.width - edge) / 2,
        (upright.height - edge) / 2,
        edge,
        edge
    )
    val scaled = if (edge == side) square else Bitmap.createScaledBitmap(square, side, side, true)

    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

@Suppress("DEPRECATION")
private fun orientationOf(resolver: ContentResolver, uri: Uri): Int = runCatching {
    resolver.openInputStream(uri)?.use { stream ->
        android.media.ExifInterface(stream).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    } ?: android.media.ExifInterface.ORIENTATION_NORMAL
}.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

@Suppress("DEPRECATION")
private fun Bitmap.upright(orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
