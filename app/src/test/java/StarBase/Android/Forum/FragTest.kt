package StarBase.Android.Forum

import StarBase.Android.Forum.net.Frag
import StarBase.Android.Forum.net.FragStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Test

/**
 * 分片 (TLS): where the hostname is, where the cut lands, and what reaches the
 * stream on either side of it.
 *
 * The whole feature is which bytes end up in which TCP segment, and that is not
 * something a socket can be asked afterwards. [Frag.write] takes an
 * [OutputStream] for exactly this reason: [Segments] stands in for one and keeps
 * every write separately, so the boundary is something a test can look at.
 * Nothing here opens a connection.
 */
class FragTest {

    // ---- finding the name -----------------------------------------------------

    @Test
    fun sniRangePointsAtTheHostnameItself() {
        val hello = clientHello("linux.sb")
        val range = Frag.sniRange(hello, 0, hello.size)
        assertNotNull(range)
        assertEquals("linux.sb", text(hello, range!!))
    }

    @Test
    fun sniRangeReadsInsideABiggerBuffer() {
        // The stream is handed a shared buffer with the record somewhere in it, so
        // an offset is the ordinary case rather than the awkward one.
        val hello = clientHello("linux.sb")
        val buffer = ByteArray(hello.size + 40) { 0x7F }
        hello.copyInto(buffer, 9)
        val range = Frag.sniRange(buffer, 9, hello.size)
        assertNotNull(range)
        assertEquals("linux.sb", text(buffer, range!!))
    }

    @Test
    fun sniRangeWalksPastEarlierExtensions() {
        val hello = clientHello(
            "linux.sb",
            before = listOf(
                0x0017 to ByteArray(0),                              // extended_master_secret
                0x0010 to byteArrayOf(0x00, 0x03, 0x02, 0x68, 0x32)  // ALPN: h2
            )
        )
        assertEquals("linux.sb", text(hello, Frag.sniRange(hello, 0, hello.size)!!))
    }

    @Test
    fun sniRangeWalksPastAnEntryThatIsNotAHostName() {
        // `server_name` is a list of tagged entries. host_name is the only tag
        // anyone sends, which is not the same as it being the first one.
        val hello = clientHello("linux.sb", decoyEntry = true)
        assertEquals("linux.sb", text(hello, Frag.sniRange(hello, 0, hello.size)!!))
    }

    @Test
    fun sniRangeIsNullForEverythingThatIsNotAClientHelloWithAName() {
        val hello = clientHello("linux.sb")
        // An application record rather than a handshake one.
        val data = hello.copyOf().also { it[0] = 0x17 }
        assertNull(Frag.sniRange(data, 0, data.size))
        // A handshake record carrying something else - a ServerHello, say.
        val server = hello.copyOf().also { it[5] = 0x02 }
        assertNull(Frag.sniRange(server, 0, server.size))
        // A record whose own length says it continues in a write this one cannot
        // see: the name may not even be here, and two writes are already two
        // segments, so it goes out untouched.
        assertNull(Frag.sniRange(hello, 0, hello.size - 12))
        // A ClientHello with no server_name has no name to hide.
        val nameless = clientHello(null)
        assertNull(Frag.sniRange(nameless, 0, nameless.size))
        // A one-character name has no inside to cut into.
        val tiny = clientHello("a")
        assertNull(Frag.sniRange(tiny, 0, tiny.size))
        // Too short to hold even the fixed part.
        assertNull(Frag.sniRange(hello, 0, 46))
    }

    @Test
    fun sniRangeNeverThrows() {
        // This is read on the way to a live write. A buffer it does not understand
        // has to come back as null, because an exception here would break
        // connections that were working fine.
        val hello = clientHello("linux.sb")
        for (len in 0..hello.size) {
            Frag.sniRange(hello, 0, len)
        }
        // Handshake header, ClientHello tag, a plausible record length - and
        // garbage everywhere the lengths inside are read from.
        val noise = ByteArray(120) { (it * 37).toByte() }
        noise[0] = 0x16
        noise[3] = 0x00
        noise[4] = (noise.size - 5).toByte()
        noise[5] = 0x01
        assertNull(Frag.sniRange(noise, 0, noise.size))
        assertNull(Frag.sniRange(ByteArray(0), 0, 0))
    }

    // ---- where the cut goes ---------------------------------------------------

    @Test
    fun cutAtLandsInsideTheNameSoNeitherHalfHoldsIt() {
        val hello = clientHello("linux.sb")
        val name = Frag.sniRange(hello, 0, hello.size)!!
        val at = Frag.cutAt(hello, 0, hello.size)
        assertNotNull(at)
        assertTrue("the cut has to be past the first byte of the name", at!! > name.first)
        assertTrue("the cut has to be at or before the last byte", at <= name.last)
        // The point of all of it. Splitting the record anywhere earlier would leave
        // the whole name sitting in the second segment.
        assertFalse(String(hello, 0, at, Charsets.US_ASCII).contains("linux.sb"))
        assertFalse(String(hello, at, hello.size - at, Charsets.US_ASCII).contains("linux.sb"))
    }

    @Test
    fun cutAtCountsFromTheOffsetItWasGiven() {
        val hello = clientHello("linux.sb")
        val buffer = ByteArray(hello.size + 16) { 0x7F }
        hello.copyInto(buffer, 5)
        assertEquals(Frag.cutAt(hello, 0, hello.size)!! + 5, Frag.cutAt(buffer, 5, hello.size)!!)
    }

    @Test
    fun cutAtIsNullWhenThereIsNoNameToSplit() {
        val nameless = clientHello(null)
        assertNull(Frag.cutAt(nameless, 0, nameless.size))
    }

    // ---- what reaches the stream ----------------------------------------------

    @Test
    fun writeSendsAClientHelloAsTwoSegmentsWithTheBytesIntact() {
        val hello = clientHello("linux.sb")
        val out = Segments()
        assertTrue(Frag.write(out, hello, 0, hello.size, Frag.Style.SEGMENT))
        assertEquals(2, out.writes.size)
        // Split, not altered: the server has to see the same handshake either way.
        assertTrue(hello.contentEquals(out.joined()))
        assertEquals(Frag.cutAt(hello, 0, hello.size)!!, out.writes[0].size)
        out.writes.forEach {
            // Two empty-and-full segments would be one segment with extra work.
            assertTrue(it.isNotEmpty())
            assertFalse(String(it, Charsets.US_ASCII).contains("linux.sb"))
        }
    }

    @Test
    fun writePassesAnythingElseThroughWhole() {
        val record = ByteArray(64) { 0x33 }.also { it[0] = 0x17 }
        val out = Segments()
        assertFalse(Frag.write(out, record, 0, record.size, Frag.Style.SEGMENT))
        assertEquals(1, out.writes.size)
        assertTrue(record.contentEquals(out.joined()))
    }

    @Test
    fun writeHonoursTheOffsetAndLengthItWasGiven() {
        val hello = clientHello("linux.sb")
        val buffer = ByteArray(hello.size + 24) { 0x7F }
        hello.copyInto(buffer, 11)
        val out = Segments()
        assertTrue(Frag.write(out, buffer, 11, hello.size, Frag.Style.SEGMENT))
        // The record and only the record: the padding around it in the caller's
        // buffer is not ours to send.
        assertTrue(hello.contentEquals(out.joined()))
    }

    @Test
    fun styleNoneSendsEverythingWhole() {
        val hello = clientHello("linux.sb")
        val out = Segments()
        assertFalse(Frag.write(out, hello, 0, hello.size, Frag.Style.NONE))
        assertEquals(1, out.writes.size)
        assertTrue(hello.contentEquals(out.joined()))
    }

    // ---- two records instead of two segments ----------------------------------

    @Test
    fun writeSendsTheHandshakeAsTwoRecordsWithTheBytesIntact() {
        val hello = clientHello("linux.sb")
        val out = Segments()
        assertTrue(Frag.write(out, hello, 0, hello.size, Frag.Style.RECORD))
        assertEquals(2, out.writes.size)
        out.writes.forEach {
            // Both are handshake records of the same version as the one they came
            // from - a server reassembles the message out of whatever records carry
            // it, which is why this is legal and why it looks nothing like the
            // original to a middlebox.
            assertEquals(0x16, it[0].toInt() and 0xFF)
            assertEquals(hello[1], it[1])
            assertEquals(hello[2], it[2])
            // A record whose header describes no payload is not a half of anything.
            assertTrue(it.size > 5)
            assertEquals("the header has to describe its own payload", it.size - 5, u16(it, 3))
            assertFalse(String(it, Charsets.US_ASCII).contains("linux.sb"))
        }
        // Only the framing changed: the handshake bytes and their order did not.
        val body = ByteArrayOutputStream()
        out.writes.forEach { body.write(it, 5, it.size - 5) }
        assertTrue(hello.copyOfRange(5, hello.size).contentEquals(body.toByteArray()))
    }

    @Test
    fun recordsKeepWhatFollowsTheRecordWithTheSecondHalf() {
        // The TLS stack may put a second record in the same write. It is not ours
        // to re-frame, so it rides along behind the second half untouched.
        val hello = clientHello("linux.sb")
        val tail = ByteArray(9) { 0x17 }
        val buffer = hello + tail
        val (first, second) = Frag.records(buffer, 0, buffer.size)!!
        assertTrue(tail.contentEquals(second.copyOfRange(second.size - tail.size, second.size)))
        // Which means the second record's length covers its share of the handshake
        // and stops there, leaving the tail outside both records where it began.
        assertEquals(second.size - tail.size - 5, u16(second, 3))
        assertEquals(hello.size - 5, u16(first, 3) + u16(second, 3))
    }

    @Test
    fun recordsIsNullForAnythingWithNoNameInIt() {
        val nameless = clientHello(null)
        assertNull(Frag.records(nameless, 0, nameless.size))
        val application = ByteArray(64) { 0x33 }.also { it[0] = 0x17 }
        assertNull(Frag.records(application, 0, application.size))
    }

    // ---- the stream on the socket ---------------------------------------------

    @Test
    fun fragStreamSplitsTheFirstWriteAndNothingAfterIt() {
        val out = Segments()
        val stream = FragStream(out, Frag.Style.RECORD)
        val hello = clientHello("linux.sb")
        stream.write(hello)
        assertTrue(stream.wrote)
        assertTrue(stream.split)
        assertEquals(2, out.writes.size)
        // The same bytes again are not a ClientHello this time, whatever they look
        // like: opening a record in the middle of a live session corrupts it.
        stream.write(hello)
        assertEquals(3, out.writes.size)
    }

    @Test
    fun fragStreamSaysWhenTheFirstWriteWasNotAClientHello() {
        // This is what 测试 prints instead of letting the reader conclude that
        // splitting did not help, when in fact splitting never happened.
        val out = Segments()
        val stream = FragStream(out, Frag.Style.RECORD)
        val data = ByteArray(64) { 0x33 }.also { it[0] = 0x17 }
        stream.write(data)
        assertTrue(stream.wrote)
        assertFalse(stream.split)
        assertEquals(1, out.writes.size)
    }

    @Test
    fun fragStreamLeavesEverythingAloneWhenTheStyleIsNone() {
        val out = Segments()
        val stream = FragStream(out, Frag.Style.NONE)
        val hello = clientHello("linux.sb")
        stream.write(hello)
        assertTrue(stream.wrote)
        assertFalse(stream.split)
        assertEquals(1, out.writes.size)
        assertTrue(hello.contentEquals(out.joined()))
    }

    // ---- the switch -----------------------------------------------------------

    @Test
    fun configureIsWhatDecidesWhetherNewSocketsSplit() {
        val before = Frag.enabled
        try {
            Frag.configure(true)
            assertTrue(Frag.enabled)
            // The switch turns on the record split, not the segment one: it beats
            // an inspector that reassembles TCP, which the segment split does not.
            assertEquals(Frag.Style.RECORD, Frag.style)
            Frag.configure(false)
            assertFalse(Frag.enabled)
            assertEquals(Frag.Style.NONE, Frag.style)
        } finally {
            Frag.configure(before)
        }
    }

    // ---- helpers --------------------------------------------------------------

    /** Stands in for the socket's stream, keeping one entry per write. */
    private class Segments : OutputStream() {

        val writes = ArrayList<ByteArray>()

        override fun write(b: Int) {
            writes += byteArrayOf(b.toByte())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writes += b.copyOfRange(off, off + len)
        }

        /** Everything that was written, in order, as one buffer. */
        fun joined(): ByteArray {
            val all = ByteArrayOutputStream()
            writes.forEach { all.write(it) }
            return all.toByteArray()
        }
    }

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun text(b: ByteArray, range: IntRange): String =
        String(b, range.first, range.last - range.first + 1, Charsets.US_ASCII)

    /**
     * A ClientHello shaped like a real one: the fixed part, a session id, one
     * cipher suite, the null compression method, then extensions.
     *
     * [host] goes in a `server_name` after whatever [before] puts in front of it,
     * and null leaves the extension out altogether. [decoyEntry] puts an entry
     * that is not a `host_name` first inside it.
     */
    private fun clientHello(
        host: String?,
        before: List<Pair<Int, ByteArray>> = emptyList(),
        decoyEntry: Boolean = false
    ): ByteArray {
        val extensions = ByteArrayOutputStream()
        before.forEach { (type, data) ->
            extensions.u16(type)
            extensions.u16(data.size)
            extensions.write(data)
        }
        if (host != null) {
            val name = host.toByteArray(Charsets.US_ASCII)
            val entries = ByteArrayOutputStream()
            if (decoyEntry) {
                entries.write(0x01)
                entries.u16(4)
                entries.write(ByteArray(4) { 0x5A })
            }
            entries.write(0x00)                 // host_name
            entries.u16(name.size)
            entries.write(name)
            val list = entries.toByteArray()
            extensions.u16(0x0000)              // server_name
            extensions.u16(list.size + 2)
            extensions.u16(list.size)           // server_name_list length
            extensions.write(list)
        }
        return record(extensions.toByteArray())
    }

    /** The fixed part, then [extensions], wrapped in the two headers. */
    private fun record(extensions: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x03, 0x03))     // client_version
        body.write(ByteArray(32) { 0x41 })      // random
        body.write(32)                          // session_id
        body.write(ByteArray(32) { 0x42 })
        body.u16(2)                             // cipher_suites
        body.write(byteArrayOf(0x13, 0x01))
        body.write(1)                           // compression_methods
        body.write(0x00)
        body.u16(extensions.size)
        body.write(extensions)
        val hello = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0x16)                         // handshake
        out.write(byteArrayOf(0x03, 0x01))      // record version
        out.u16(hello.size + 4)
        out.write(0x01)                         // client_hello
        out.write((hello.size shr 16) and 0xFF)
        out.u16(hello.size and 0xFFFF)
        out.write(hello)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }





}
