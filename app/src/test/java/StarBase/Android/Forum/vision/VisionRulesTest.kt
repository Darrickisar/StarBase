package StarBase.Android.Forum.vision

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VisionRulesTest {
    @Test fun decodingBudgetUsesBothPixelsAndEdgesWithoutIntegerOverflow() {
        assertEquals(1, VisionLimits.sampleSize(2000, 2000))
        assertEquals(4, VisionLimits.sampleSize(6000, 10000))
        assertEquals(8, VisionLimits.sampleSize(32768, 1))
        for ((width, height) in listOf(0 to 40, -1 to 20, Int.MAX_VALUE to Int.MAX_VALUE, 10000 to 10000, 32769 to 1)) {
            assertThrows(IllegalArgumentException::class.java) { VisionLimits.sampleSize(width, height) }
        }
    }

    @Test fun unknownLengthReadStopsAtTheLimitPlusOneByte() {
        var consumed = 0
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return 1 }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                consumed += count
                bytes.fill(1, offset, offset + count)
                return count
            }
        }
        assertThrows(IllegalArgumentException::class.java) { readVisionBytes(endless, 64) }
        assertEquals(65, consumed)
        assertArrayEquals(ByteArray(64) { it.toByte() }, readVisionBytes(ByteArrayInputStream(ByteArray(64) { it.toByte() }), 64))
        assertThrows(IllegalArgumentException::class.java) { readVisionBytes(ByteArrayInputStream(byteArrayOf()), 64) }
    }

    @Test fun readingHonorsCancellationBeforeTouchingTheStream() {
        val unread = object : InputStream() { override fun read(): Int = throw AssertionError("Read after cancellation") }
        assertThrows(CancellationException::class.java) {
            readVisionBytes(unread) { throw CancellationException() }
        }
    }

    @Test fun streamReturningZeroCannotSpinForeverOrBypassTheByteBudget() {
        var calls = 0
        val stream = object : InputStream() {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = 0
            override fun read(): Int = if (calls++ < 4) 42 else -1
        }
        assertArrayEquals(byteArrayOf(42, 42, 42, 42), readVisionBytes(stream, 4))
    }

    @Test fun fittedCoordinatesRejectLetterboxStartsAndClampDragEnds() {
        val wide = ImageViewport.fit(800, 400, 400f, 400f)
        assertEquals(ImageViewport(0f, 100f, 400f, 200f), wide)
        assertNull(wide.point(100f, 99f))
        assertEquals(ImagePoint(0.5f, 0.5f), wide.point(200f, 200f))
        assertEquals(ImagePoint(1f, 0f), wide.point(999f, -10f, true))
        val tall = ImageViewport.fit(400, 800, 400f, 400f)
        assertEquals(ImageViewport(100f, 0f, 200f, 400f), tall)
        assertNull(tall.point(99f, 200f))
        assertNull(tall.point(Float.NaN, 200f, true))
    }

    @Test fun cropAndRedactionRoundOutwardsForReversedDragCoordinates() {
        val edit = VisionEdit(VisionTool.REDACT, ImagePoint(0.91f, 0.82f), ImagePoint(0.11f, 0.22f))
        assertTrue(edit.valid())
        assertEquals(PixelRegion(1, 2, 10, 9), edit.region(10, 10))
        val crop = edit.copy(tool = VisionTool.CROP)
        assertEquals(edit.region(10, 10), crop.region(10, 10))
    }

    @Test fun tinyOrNonFiniteEditsCannotReachTheRenderer() {
        assertFalse(VisionEdit(VisionTool.CROP, ImagePoint(0f, 0f), ImagePoint(0f, 1f)).valid())
        assertFalse(VisionEdit(VisionTool.REDACT, ImagePoint(Float.NaN, 0f), ImagePoint(1f, 1f)).valid())
        assertFalse(VisionEdit(VisionTool.REDACT, ImagePoint(-0.1f, 0f), ImagePoint(1f, 1f)).valid())
        assertTrue(VisionEdit(VisionTool.ARROW, ImagePoint(0f, 0.5f), ImagePoint(1f, 0.5f)).valid())
    }

    @Test fun canceledOrOutOfOrderResultsCannotBecomeCurrentAfterAnotherScanOrEdit() {
        val gate = VisionRevision()
        val sourceA = gate.advance()
        val editA = gate.advance()
        assertFalse(gate.accepts(sourceA))
        assertTrue(gate.accepts(editA))
        val ocr = gate.advance()
        gate.advance()
        assertFalse(gate.accepts(ocr))
        val sourceB = gate.advance()
        assertTrue(gate.accepts(sourceB))
        assertFalse(gate.accepts(editA))
    }

    @Test fun onlyExplicitWebLinksWithoutCredentialsOrAmbiguousCharactersCanOpen() {
        assertEquals("https://example.com/path?q=1", VisionText.safeLink(" HTTPS://example.com/path?q=1 "))
        assertEquals("http://example.com/", VisionText.safeLink("http://example.com"))
        for (link in listOf("javascript:alert(1)", "intent://open", "file:///sdcard/a.png", "content://media/1",
            "data:text/html,hi", "//example.com", "https://user:secret@example.com", "https://user@example.com",
            "https://example.com\\@evil.test", "https://example.com/\nsecret", "https://example.com/\u202eevil",
            "https://", "https://example.com/" + "a".repeat(VisionLimits.URL))) {
            assertNull(link, VisionText.safeLink(link))
        }
    }

    @Test fun detectedLinksAreDeduplicatedAndNonWebQrCodesStayNonExecutable() {
        assertEquals(listOf("https://example.com/", "https://linux.sb/topic/12"), VisionText.links(
            "See https://example.com/ and (https://linux.sb/topic/12).", listOf("https://example.com", "WIFI:T:WPA;S:private;;")))
        assertEquals(VisionLimits.LINKS, VisionText.links((1..100).joinToString(" ") { "https://example.com/$it" }).size)
    }

    @Test fun selectionPreservesCopyFormattingWhileSearchIsBounded() {
        assertEquals("ERROR\n503", VisionText.selected("xERROR\n503y", 10, 1))
        assertEquals("ERROR 503", VisionText.query("\n ERROR\t503\u0000 "))
        assertEquals("", VisionText.selected("error", -20, -2))
        assertEquals(VisionLimits.QUERY, VisionText.query("a".repeat(1000)).length)
        assertEquals("连接失败 ERR_TIMEOUT", VisionText.errorLine("Settings\n网络\n连接失败 ERR_TIMEOUT\nRetry"))
    }

    @Test fun helpDraftIsStructuredEditableAndDoesNotInventEnvironmentDetails() {
        val draft = VisionText.help("Status\nError 503\nService unavailable", "Error 503")
        assertTrue(draft.title.startsWith("求助：Error 503"))
        assertNull(draft.imageUri)
        for (heading in listOf("问题描述", "错误信息", "环境", "复现步骤", "预期结果", "实际结果")) {
            assertTrue(draft.body, draft.body.lines().contains("## $heading"))
        }
        assertTrue(draft.body.contains("Status\nError 503\nService unavailable"))
        assertFalse(draft.body.contains("    ##"))
        assertTrue(draft.body.contains("- 设备 / 系统：\n- 软件 / 版本："))
    }

    @Test fun saveableSessionRoundTripsEditsAndUserCorrectionsWithoutAnImageBody() {
        val session = VisionSession(source = "content://media/123", initialized = true,
            edits = listOf(VisionEdit(VisionTool.REDACT, ImagePoint(0.1f, 0.2f), ImagePoint(0.5f, 0.6f))),
            text = "corrected error", keyError = "error", draftCreated = true, draftTitle = "title", draftBody = "body")
        val saved = Json.encodeToString(session)
        assertEquals(session, Json.decodeFromString<VisionSession>(saved))
        assertFalse(saved.contains("bitmap", ignoreCase = true))
    }
}
