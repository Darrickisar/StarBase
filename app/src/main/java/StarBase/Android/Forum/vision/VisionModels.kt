package StarBase.Android.Forum.vision

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

data class HelpDraft(val title: String, val body: String, val imageUri: String?)

internal object VisionLimits {
    const val INPUT_BYTES = 16 * 1024 * 1024
    const val SOURCE_PIXELS = 80_000_000L
    const val SOURCE_EDGE = 32_768
    const val DECODED_PIXELS = 4_000_000L
    const val DECODED_EDGE = 4_096
    const val EDITS = 32
    const val TEXT = 16_000
    const val QUERY = 200
    const val TITLE = 120
    const val BODY = 24_000
    const val URL = 4_096
    const val LINKS = 24

    fun sampleSize(width: Int, height: Int): Int {
        require(width in 1..SOURCE_EDGE && height in 1..SOURCE_EDGE &&
            width.toLong() * height <= SOURCE_PIXELS) { "图片尺寸过大或格式无效" }
        var sample = 1
        while (ceil(width.toDouble() / sample) * ceil(height.toDouble() / sample) > DECODED_PIXELS ||
            ceil(maxOf(width, height).toDouble() / sample) > DECODED_EDGE) sample *= 2
        return sample
    }
}

internal fun readVisionBytes(input: InputStream, limit: Int = VisionLimits.INPUT_BYTES,
    checkActive: () -> Unit = {}): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        checkActive()
        val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (count < 0) break
        if (count == 0) {
            val value = input.read()
            if (value < 0) break
            require(total < limit) { "图片超过 16 MB" }
            output.write(value)
            total++
        } else {
            require(count <= limit - total) { "图片超过 16 MB" }
            output.write(buffer, 0, count)
            total += count
        }
    }
    require(total > 0) { "图片为空" }
    checkActive()
    return output.toByteArray()
}

@Serializable
internal enum class VisionLanguage { CHINESE, LATIN }

@Serializable
internal enum class VisionTool { CROP, REDACT, ARROW }

@Serializable
internal data class ImagePoint(val x: Float, val y: Float) {
    fun valid() = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
}

internal data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

@Serializable
internal data class VisionEdit(val tool: VisionTool, val start: ImagePoint, val end: ImagePoint) {
    fun valid(): Boolean = start.valid() && end.valid() && if (tool == VisionTool.ARROW)
        hypot(end.x - start.x, end.y - start.y) >= 0.015f
    else kotlin.math.abs(start.x - end.x) >= 0.01f && kotlin.math.abs(start.y - end.y) >= 0.01f

    fun region(width: Int, height: Int): PixelRegion {
        require(valid() && width > 0 && height > 0) { "选区太小" }
        // Round outwards: a redaction must cover every touched pixel, including its edges.
        return PixelRegion(
            floor(minOf(start.x, end.x) * width).toInt().coerceIn(0, width - 1),
            floor(minOf(start.y, end.y) * height).toInt().coerceIn(0, height - 1),
            ceil(maxOf(start.x, end.x) * width).toInt().coerceIn(1, width),
            ceil(maxOf(start.y, end.y) * height).toInt().coerceIn(1, height)
        )
    }
}

internal data class ImageViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(x: Float, y: Float, clamp: Boolean = false): ImagePoint? {
        if (!x.isFinite() || !y.isFinite() || width <= 0f || height <= 0f) return null
        val nx = (x - left) / width
        val ny = (y - top) / height
        if (!clamp && (nx !in 0f..1f || ny !in 0f..1f)) return null
        return ImagePoint(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    companion object {
        fun fit(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): ImageViewport {
            require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
            val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
            val width = imageWidth * scale
            val height = imageHeight * scale
            return ImageViewport((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
        }
    }
}

/** Main-thread version gate shared by loads, edits, recognition and draft export. */
internal class VisionRevision {
    private var value = 0L
    fun advance(): Long = ++value
    fun accepts(ticket: Long): Boolean = ticket == value
}

internal object VisionText {
    fun safeLink(raw: String): String? {
        val candidate = raw.trim()
        if (candidate.isEmpty() || candidate.length > VisionLimits.URL ||
            candidate.any { it.isWhitespace() || it.isISOControl() || it in "\\<>\"" ||
                it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) return null
        if (!candidate.startsWith("https://", true) && !candidate.startsWith("http://", true)) return null
        return candidate.toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.host.isNotBlank()
        }?.toString()
    }

    fun links(text: String, codes: List<String> = emptyList()): List<String> {
        val detected = Regex("https?://[^\\s<>\"\\u3000]+", RegexOption.IGNORE_CASE)
            .findAll(text.take(VisionLimits.TEXT)).map { it.value.trimEnd('.', ',', ';', '!', ')', ']', '}',
                '\u3002', '\uff0c', '\uff1b', '\uff09', '\u300b') }.toList()
        return (codes.take(VisionLimits.LINKS) + detected).mapNotNull(::safeLink).distinct().take(VisionLimits.LINKS)
    }

    fun query(text: String): String = text.replace(Regex("[\\s\\p{Cc}]+"), " ").trim().take(VisionLimits.QUERY)

    fun selected(text: String, start: Int, end: Int): String {
        val first = minOf(start, end).coerceIn(0, text.length)
        val last = maxOf(start, end).coerceIn(first, text.length)
        return text.substring(first, last)
    }

    fun errorLine(text: String): String {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val error = Regex("error|exception|failed|failure|denied|timeout|错误|异常|失败|拒绝|超时", RegexOption.IGNORE_CASE)
        return query(lines.firstOrNull { error.containsMatchIn(it) } ?: lines.firstOrNull().orEmpty())
    }

    fun help(text: String, keyError: String): HelpDraft {
        val error = keyError.ifBlank { errorLine(text) }
        val title = if (error.isBlank()) "截图求助" else "求助：${query(error)}".take(VisionLimits.TITLE)
        val body = buildString {
            appendLine("## 问题描述"); appendLine(error); appendLine()
            appendLine("## 错误信息"); appendLine(text.trim().take(VisionLimits.TEXT)); appendLine()
            appendLine("## 环境"); appendLine("- 设备 / 系统："); appendLine("- 软件 / 版本："); appendLine()
            appendLine("## 复现步骤"); appendLine("1. "); appendLine("2. "); appendLine()
            appendLine("## 预期结果"); appendLine()
            appendLine("## 实际结果"); append(error)
        }
        return HelpDraft(title, body, null)
    }
}

@Serializable
internal data class VisionSession(
    val initialSource: String? = null,
    val source: String? = null,
    val initialized: Boolean = false,
    val pickerLaunched: Boolean = false,
    val imageHash: String? = null,
    val edits: List<VisionEdit> = emptyList(),
    val language: VisionLanguage = VisionLanguage.CHINESE,
    val text: String = "",
    val keyError: String = "",
    val codes: List<String> = emptyList(),
    val recognized: Boolean = false,
    val draftTitle: String = "",
    val draftBody: String = "",
    val draftCreated: Boolean = false,
    val includeImage: Boolean = true,
    val tab: Int = 0
)
