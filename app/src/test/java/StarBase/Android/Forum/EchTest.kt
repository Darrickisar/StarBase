package StarBase.Android.Forum

import StarBase.Android.Forum.net.Doh
import StarBase.Android.Forum.net.Ech
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * ECH: the key coming out of DNS, and the names coming back out of the key.
 *
 * The platform call itself cannot be reached from here - it is Android 17 and there
 * is no socket on this side - so what is left is the two parsers, which is also
 * where a wrong answer would be silent. A misread SvcParam does not throw, it just
 * hands back bytes that are not a config, and the handshake then fails for a reason
 * that looks nothing like the cause. Both are read straight off a response built
 * the way a resolver builds one.
 *
 * The wire numbers are written out as literals here rather than taken from [Ech]
 * and [Doh]: a test that spells the constant the same way the code does would agree
 * with it while both were wrong.
 */
class EchTest {

    // ---- the key out of DNS ---------------------------------------------------

    @Test
    fun httpsParamFindsEchAmongTheOtherParameters() {
        val config = configList(echConfig("cloudflare-ech.com"))
        val bytes = reply(
            "linux.sb",
            listOf(
                Record(
                    TYPE_HTTPS,
                    svcb(
                        1,
                        listOf(
                            ALPN to byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte()),
                            KEY_ECH to config,
                            IPV4HINT to byteArrayOf(104, 16, 0, 1)
                        )
                    )
                )
            )
        )
        assertArrayEquals(config, Doh.httpsParam(bytes, Ech.PARAM_ECH))
    }

    @Test
    fun httpsParamReadsPastRecordsWithNothingInThemForIt() {
        // Addresses come back in the same answer, and a zone can publish more than
        // one HTTPS record - the one with the key is not promised to be first.
        val config = configList(echConfig("cloudflare-ech.com"))
        val bytes = reply(
            "linux.sb",
            listOf(
                Record(1, byteArrayOf(104, 16, 0, 1)),
                Record(TYPE_HTTPS, svcb(1, listOf(ALPN to byteArrayOf(2, 0x68, 0x32)))),
                Record(TYPE_HTTPS, svcb(2, listOf(KEY_ECH to config)))
            )
        )
        assertArrayEquals(config, Doh.httpsParam(bytes, Ech.PARAM_ECH))
    }

    @Test
    fun httpsParamIsNullWhenTheZonePublishesNoKey() {
        val bytes = reply(
            "linux.sb",
            listOf(
                Record(
                    TYPE_HTTPS,
                    svcb(1, listOf(ALPN to byteArrayOf(2, 0x68, 0x32), IPV4HINT to byteArrayOf(1, 2, 3, 4)))
                )
            )
        )
        assertNull(Doh.httpsParam(bytes, Ech.PARAM_ECH))
    }

    @Test
    fun httpsParamIsNullForAliasMode() {
        // Priority 0 is a pointer at another name and carries no parameters at all.
        // What follows the target there is not a parameter list, so reading it as
        // one is how a config gets invented out of somebody else's bytes.
        val config = configList(echConfig("cloudflare-ech.com"))
        val alias = svcb(0, listOf(KEY_ECH to config), target = "ech.linux.sb")
        assertNull(Doh.httpsParam(reply("linux.sb", listOf(Record(TYPE_HTTPS, alias))), Ech.PARAM_ECH))
    }

    @Test
    fun httpsParamNeverThrows() {
        // This runs on the way to a connection. A response it cannot make sense of
        // has to come back as null: an exception here would take out a handshake
        // that would otherwise just have gone out without ECH.
        val bytes = reply(
            "linux.sb",
            listOf(Record(TYPE_HTTPS, svcb(1, listOf(KEY_ECH to configList(echConfig("a.example"))))))
        )
        for (len in 0..bytes.size) {
            Doh.httpsParam(bytes.copyOf(len), Ech.PARAM_ECH)
        }
        // A header that says there is one question and one answer, and garbage
        // everywhere the lengths are read from.
        val noise = ByteArray(140) { (it * 31).toByte() }
        noise[4] = 0
        noise[5] = 1
        noise[6] = 0
        noise[7] = 1
        Doh.httpsParam(noise, Ech.PARAM_ECH)
        assertNull(Doh.httpsParam(ByteArray(0), Ech.PARAM_ECH))
        assertNull(Doh.httpsParam(ByteArray(11), Ech.PARAM_ECH))
    }

    // ---- the names out of the key ----------------------------------------------

    @Test
    fun publicNamesReadsTheNameThatGoesOnTheWireInstead() {
        val names = Ech.publicNames(configList(echConfig("cloudflare-ech.com")))
        assertEquals(listOf("cloudflare-ech.com"), names)
    }

    @Test
    fun publicNamesReadsEveryConfigInTheList() {
        // A rotation publishes the old key and the new one together, and either may
        // be the one a client picks.
        val list = configList(echConfig("cloudflare-ech.com"), echConfig("second.example"))
        assertEquals(listOf("cloudflare-ech.com", "second.example"), Ech.publicNames(list))
    }

    @Test
    fun publicNamesSkipsAVersionItDoesNotKnow() {
        // 0xfe0d is the one RFC 9849 shipped. A draft-numbered config in the same
        // list is not this app's to read, and reading it anyway would print a name
        // taken from bytes laid out some other way.
        val list = configList(echConfig("draft.example", version = 0xFE0A), echConfig("real.example"))
        assertEquals(listOf("real.example"), Ech.publicNames(list))
        assertTrue(Ech.publicNames(configList(echConfig("draft.example", version = 0xFE0A))).isEmpty())
    }

    @Test
    fun publicNamesDropsAConfigThatDoesNotFitRatherThanGuessing() {
        val full = configList(echConfig("cloudflare-ech.com"))
        for (len in 0 until full.size) {
            assertTrue(
                "a config cut short is not a config",
                Ech.publicNames(full.copyOf(len)).isEmpty()
            )
        }
        assertEquals(listOf("cloudflare-ech.com"), Ech.publicNames(full))
    }

    @Test
    fun publicNamesNeverThrows() {
        // Only 测试 prints these, but it prints them next to a failed handshake,
        // which is the worst moment for the printing itself to be the thing that
        // breaks.
        val noise = ByteArray(96) { (it * 41).toByte() }
        Ech.publicNames(noise)
        for (len in 0..noise.size) {
            Ech.publicNames(noise.copyOf(len))
        }
        assertTrue(Ech.publicNames(ByteArray(0)).isEmpty())
        assertTrue(Ech.publicNames(byteArrayOf(0x7F)).isEmpty())
    }

    // ---- the platform half ------------------------------------------------------

    @Test
    fun availableIsFalseWhereThereIsNoPlatformApi() {
        // There is no android.net.ssl on a JVM, which is the same shape as an
        // Android below 17: the whole point of looking the API up by reflection is
        // that its absence is an answer rather than an exception.
        assertFalse(Ech.available)
    }

    // ---- fixtures ---------------------------------------------------------------

    /** `HTTPS`, RFC 9460 section 14.1. */
    private val TYPE_HTTPS = 65

    /** `alpn`, `ipv4hint`, `ech` - SvcParamKeys 1, 4 and 5. */
    private val ALPN = 1
    private val IPV4HINT = 4
    private val KEY_ECH = 5

    private class Record(val type: Int, val rdata: ByteArray)

    /** The name as it goes on the wire, uncompressed. */
    private fun name(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        text.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * A response built the way a resolver builds one: the question echoed back in
     * full, then every answer naming it with a compression pointer to offset 12.
     * The pointer is the part worth having - it is what the name-skipping in front
     * of the record has to get right before any of the rest is reached.
     */
    private fun reply(question: String, records: List<Record>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0))
        out.write(0x81)
        out.write(0x80)
        out.u16(1)
        out.u16(records.size)
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(name(question))
        out.u16(TYPE_HTTPS)
        out.u16(1)
        records.forEach { record ->
            out.write(byteArrayOf(0xC0.toByte(), 12))
            out.u16(record.type)
            out.u16(1)
            out.write(byteArrayOf(0, 0, 1, 0x2C))
            out.u16(record.rdata.size)
            out.write(record.rdata)
        }
        return out.toByteArray()
    }

    /**
     * One `HTTPS` record's RDATA: priority, target name, then the parameters in
     * ascending key order, which is the order the wire format requires.
     *
     * An empty [target] is the single zero byte meaning 「the owner name itself」,
     * which is what a zone apex publishes.
     */
    private fun svcb(
        priority: Int,
        params: List<Pair<Int, ByteArray>>,
        target: String = ""
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(priority)
        if (target.isEmpty()) out.write(0) else out.write(name(target))
        params.sortedBy { it.first }.forEach { (key, value) ->
            out.u16(key)
            out.u16(value.size)
            out.write(value)
        }
        return out.toByteArray()
    }

    /** The list wrapper: a uint16 of everything after it. RFC 9849 section 4. */
    private fun configList(vararg configs: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        configs.forEach { body.write(it) }
        val inner = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.u16(inner.size)
        out.write(inner)
        return out.toByteArray()
    }

    /**
     * One ECHConfig, laid out the way RFC 9849 section 4 lays it out: version and
     * length, then the key config, the name-length cap, the public name, and an
     * empty extension list.
     *
     * The lengths are real rather than plausible - the parser walks past the key and
     * the cipher suites by adding them up, so a fixture with a made-up length would
     * be testing a different layout from the one Cloudflare publishes.
     */
    private fun echConfig(publicName: String, version: Int = 0xFE0D): ByteArray {
        val contents = ByteArrayOutputStream()
        contents.write(0x2A)                            // config_id
        contents.u16(0x0020)                            // kem_id: DHKEM(X25519)
        contents.u16(32)                                // public_key
        contents.write(ByteArray(32) { 0x11 })
        contents.u16(4)                                 // cipher_suites: one suite
        contents.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        contents.write(64)                              // maximum_name_length
        val label = publicName.toByteArray(Charsets.US_ASCII)
        contents.write(label.size)
        contents.write(label)
        contents.u16(0)                                 // extensions
        val body = contents.toByteArray()
        val out = ByteArrayOutputStream()
        out.u16(version)
        out.u16(body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
}
