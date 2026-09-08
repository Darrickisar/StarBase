package StarBase.Android.Forum.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal data class VisionRecognitionResult(val text: String, val codes: List<String>, val warning: String? = null)

/** Both entry points use the bundled clients. ML Kit never receives a URL or original unedited pixels. */
internal class VisionRecognition : Closeable {
    private val chineseClient = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val latinClient = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val scannerClient = lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    private val mutex = Mutex()
    private var closed = false

    suspend fun recognize(bitmap: Bitmap, language: VisionLanguage): VisionRecognitionResult = mutex.withLock {
        check(!closed) { "识别器已关闭" }
        currentCoroutineContext().ensureActive()
        val input = InputImage.fromBitmap(bitmap, 0)
        var text = ""
        var codes = emptyList<String>()
        val warnings = mutableListOf<String>()
        try {
            val client = if (language == VisionLanguage.CHINESE) chineseClient.value else latinClient.value
            text = client.process(input).awaitCompletion().text.take(VisionLimits.TEXT)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { warnings += "文字识别失败，可重试" }
        currentCoroutineContext().ensureActive()
        try {
            codes = scannerClient.value.process(input).awaitCompletion().mapNotNull { it.rawValue }
                .filter { it.length <= VisionLimits.URL }.distinct().take(VisionLimits.LINKS)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { warnings += "二维码识别失败，可重试" }
        currentCoroutineContext().ensureActive()
        VisionRecognitionResult(text, codes, warnings.takeIf { it.isNotEmpty() }?.joinToString("；"))
    }

    override fun close() {
        closed = true
        // Closing clients is supported with pending tasks; callers never recycle their input bitmap.
        if (chineseClient.isInitialized()) chineseClient.value.close()
        if (latinClient.isInitialized()) latinClient.value.close()
        if (scannerClient.isInitialized()) scannerClient.value.close()
    }
}

private val directTaskExecutor = Executor { it.run() }

// ML Kit tasks cannot cancel inference. Keep the mutex/input alive until completion, then check the
// coroutine and image revision before consuming results. This also bounds successive canceled scans.
private suspend fun <T> Task<T>.awaitCompletion(): T = suspendCoroutine { continuation ->
    addOnCompleteListener(directTaskExecutor) { task ->
        when {
            task.isSuccessful -> continuation.resume(task.result)
            task.isCanceled -> continuation.resumeWithException(CancellationException("识别已取消"))
            else -> continuation.resumeWithException(task.exception ?: IllegalStateException("识别失败"))
        }
    }
}
