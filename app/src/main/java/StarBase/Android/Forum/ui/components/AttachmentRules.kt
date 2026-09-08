package StarBase.Android.Forum.ui.components

import java.io.ByteArrayOutputStream
import java.io.InputStream

object AttachmentRules {
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_SOURCE_BYTES = 40L * 1024 * 1024
    const val IMAGE_MAX_EDGE = 2048

    fun uploadLimit(siteMaxMb: Int): Long = if (siteMaxMb > 0) {
        minOf(MAX_FILE_BYTES, siteMaxMb.toLong() * 1024 * 1024)
    } else MAX_FILE_BYTES

    fun validate(name: String, size: Long, siteMaxMb: Int, accepts: List<String>) {
        require(size > 0) { "不能上传空文件" }
        require(size <= uploadLimit(siteMaxMb)) { "附件超过 ${uploadLimit(siteMaxMb) / 1024 / 1024} MB" }
        val extension = name.substringAfterLast('.', "")
        require(accepts.isEmpty() || (extension.isNotBlank() && accepts.any { it.equals(".$extension", true) })) {
            "站点仅接受 ${accepts.joinToString(" ")}"
        }
    }

    fun fraction(sent: Long, total: Long): Float? =
        if (total <= 0) null else (sent.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/** Bounds unknown-length providers during reading, not after allocating the entire file. */
fun readAttachmentBytes(input: InputStream, maxBytes: Long, onRead: (Long) -> Unit = {}): ByteArray {
    require(maxBytes in 1..AttachmentRules.MAX_SOURCE_BYTES)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
        if (count < 0) break
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) break
            total++
            require(total <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MB" }
            output.write(byte)
        } else {
            total += count
            require(total <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MB" }
            output.write(buffer, 0, count)
        }
        onRead(total)
    }
    return output.toByteArray()
}
