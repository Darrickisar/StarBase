package StarBase.Android.Forum

import StarBase.Android.Forum.net.UploadBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UploadBodyTest {
    @Test fun progressReportsActualBytesAndBodyCannotBeAutomaticallyReplayed() {
        val bytes = ByteArray(30_000) { (it % 127).toByte() }
        val progress = mutableListOf<Pair<Long, Long>>()
        val body = UploadBody(bytes.toRequestBody()) { sent, total -> progress += sent to total }
        val sink = Buffer()
        body.writeTo(sink)
        assertArrayEquals(bytes, sink.readByteArray())
        assertEquals(0L to bytes.size.toLong(), progress.first())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertTrue(body.isOneShot())
    }
}
