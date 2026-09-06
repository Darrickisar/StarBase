package StarBase.Android.Forum

import StarBase.Android.Forum.net.BoundedSystemDns
import StarBase.Android.Forum.net.Doh
import StarBase.Android.Forum.net.DohAuto
import StarBase.Android.Forum.net.DohResolver
import StarBase.Android.Forum.net.Doh.fetchWithGetFallback
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/**
 * 域名解析 (DoH), on both halves: the wireformat codec, and the resolver that
 * decides between a DoH answer, a cached one and the system.
 *
 * Nothing here touches a network. The codec is pure, and [DohResolver] takes its
 * transport as a function, so a test can hand it a canned answer - or a failure -
 * and check what it does about it.
 */
class DohTest {

    // ---- the query on the wire ------------------------------------------------

    @Test
    fun queryIsAWellFormedDnsRequest() {
        val q = Doh.query("linux.sb")
        // 12-byte header + (1+5) + (1+2) + root + qtype + qclass
        assertEquals(12 + 6 + 3 + 1 + 4, q.size)
        // RFC 8484 asks for id 0 so two identical queries have identical bytes.
        assertEquals(0, q[0].toInt())
        assertEquals(0, q[1].toInt())
        // Recursion desired, one question, nothing else.
        assertEquals(0x01, q[2].toInt())
        assertEquals(0x00, q[3].toInt())
        assertEquals(1, ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF))
        assertEquals(0, ((q[6].toInt() and 0xFF) shl 8) or (q[7].toInt() and 0xFF))
        // The name, label by label, then the root.
        assertEquals(5, q[12].toInt())
        assertEquals("linux", String(q, 13, 5))
        assertEquals(2, q[18].toInt())
        assertEquals("sb", String(q, 19, 2))
        assertEquals(0, q[21].toInt())
        // A, IN.
        assertEquals(Doh.TYPE_A, ((q[22].toInt() and 0xFF) shl 8) or (q[23].toInt() and 0xFF))
        assertEquals(1, ((q[24].toInt() and 0xFF) shl 8) or (q[25].toInt() and 0xFF))
    }

    @Test
    fun queryCanAskForAaaaAndIgnoresATrailingDot() {
        val plain = Doh.query("linux.sb", Doh.TYPE_AAAA)
        val dotted = Doh.query("linux.sb.", Doh.TYPE_AAAA)
        assertEquals(Doh.TYPE_AAAA, ((plain[22].toInt() and 0xFF) shl 8) or (plain[23].toInt() and 0xFF))
        assertTrue(plain.contentEquals(dotted))
    }

    @Test
    fun queryRefusesNamesItCouldNotEncode() {
        // Empty labels and non-ASCII would both need something this app does not
        // have (a rule for `a..b`, punycode) - better to refuse than to send
        // bytes no resolver will answer.
        listOf("", "linux..sb", "论坛.sb", "a".repeat(64) + ".sb").forEach { bad ->
            val failed = runCatching { Doh.query(bad) }.isFailure
            assertTrue("should refuse $bad", failed)
        }
    }

    // ---- the answer on the wire -----------------------------------------------

    @Test
    fun answerReadsTheAddressesAndTheSmallestTtl() {
        val body = reply(
            "linux.sb",
            listOf(
                Record(Doh.TYPE_A, 300, byteArrayOf(104, 21, 67, -19)),
                Record(Doh.TYPE_A, 120, byteArrayOf(-84, 67, -74, -9))
            )
        )
        val answer = Doh.answer(body)
        assertEquals(0, answer.rcode)
        assertEquals(listOf("104.21.67.237", "172.67.182.247"), answer.addresses.map { it.text })
        // The shortest TTL in the set is the one the whole set expires on.
        assertEquals(120L, answer.ttlSeconds)
    }

    @Test
    fun answerSkipsRecordsThatAreNotAddresses() {
        // A CNAME in front of the A records is normal, and its rdata is a name -
        // it has to be walked past rather than read as an address.
        val body = reply(
            "linux.sb",
            listOf(
                Record(5, 300, name("edge.example.net")),
                Record(Doh.TYPE_AAAA, 300, ByteArray(16).also { it[0] = 0x26; it[1] = 0x06 }),
                Record(Doh.TYPE_A, 300, byteArrayOf(1, 2, 3, 4))
            )
        )
        val answer = Doh.answer(body)
        assertEquals(2, answer.addresses.size)
        assertEquals(Doh.TYPE_AAAA, answer.addresses[0].type)
        assertEquals("1.2.3.4", answer.addresses[1].text)
    }

    @Test
    fun answerKeepsNxdomainApart() {
        val body = reply("nope.sb", emptyList(), rcode = 3)
        val answer = Doh.answer(body)
        assertEquals(3, answer.rcode)
        assertTrue(answer.addresses.isEmpty())
    }

    @Test
    fun answerTreatsRubbishAsUnreadableRatherThanThrowing() {
        // -1 is this parser saying "not a DNS message", which the resolver reads
        // as a reason to ask the system. A crash here would take the app down.
        listOf(
            ByteArray(0),
            byteArrayOf(1, 2, 3),
            "<html>418 I am a teapot</html>".toByteArray(),
            reply("linux.sb", listOf(Record(Doh.TYPE_A, 300, byteArrayOf(1, 2, 3, 4))))
                .copyOfRange(0, 20)
        ).forEach { bad ->
            assertEquals(-1, Doh.answer(bad).rcode)
        }
    }

    @Test
    fun answerRejectsIncompleteAddressAndNonAddressRecords() {
        val body = reply("linux.sb", listOf(
            Record(Doh.TYPE_A, 300, byteArrayOf(1, 2, 3, 4)),
            Record(5, 300, name("edge.example.net"))
        ))
        for (size in 0 until body.size) {
            assertEquals("must reject a response cut at $size", -1, Doh.answer(body.copyOf(size)).rcode)
        }
    }

    @Test
    fun answerRejectsQueryPacketsAndTheTruncatedResponseFlag() {
        val body = reply("linux.sb", listOf(Record(Doh.TYPE_A, 300, byteArrayOf(1, 2, 3, 4))))
        val queryPacket = body.copyOf().also { it[2] = 0x01 }
        val truncated = body.copyOf().also { it[2] = 0x83.toByte() }
        assertEquals(-1, Doh.answer(queryPacket).rcode)
        assertEquals(-1, Doh.answer(truncated).rcode)
    }

    @Test
    fun answerDoesNotUseAddressesOutsideTheInternetClass() {
        val body = reply("linux.sb", listOf(Record(Doh.TYPE_A, 300, byteArrayOf(1, 2, 3, 4))))
        val record = Doh.query("linux.sb").size
        body[record + 5] = 3 // CLASS=CH, not IN.
        assertTrue(Doh.answer(body).addresses.isEmpty())
    }

    // ---- base64url and the custom address -------------------------------------

    @Test
    fun base64UrlMatchesTheRfcExamplesAndDropsPadding() {
        assertEquals("", Doh.base64Url(ByteArray(0)))
        assertEquals("Zg", Doh.base64Url("f".toByteArray()))
        assertEquals("Zm8", Doh.base64Url("fo".toByteArray()))
        assertEquals("Zm9v", Doh.base64Url("foo".toByteArray()))
        assertEquals("Zm9vYmFy", Doh.base64Url("foobar".toByteArray()))
        // The two characters that make it url-safe: - and _ instead of + and /.
        // 0xFBFFFF is 111110 111111 111111 111111 - index 62 then 63 three times.
        assertEquals("-___", Doh.base64Url(byteArrayOf(-5, -1, -1)))
    }

    @Test
    fun tidyUrlFillsInTheStandardPathAndRefusesTheRest() {
        assertEquals(
            "https://dns.example.com/dns-query",
            Doh.tidyUrl("  https://dns.example.com  ")
        )
        assertEquals(
            "https://dns.example.com/dns-query",
            Doh.tidyUrl("https://dns.example.com/")
        )
        assertEquals(
            "https://dns.example.com/query",
            Doh.tidyUrl("https://dns.example.com/query")
        )
        // Not over TLS is not a DoH address; the rest are not addresses at all.
        listOf("", "dns.example.com", "http://dns.example.com/dns-query", "https://",
            "https://localhost", "https://a b.com/dns-query").forEach { bad ->
            assertEquals("refused: $bad", "", Doh.tidyUrl(bad))
        }
    }

    @Test
    fun customUrlsKeepQueryParametersAndRejectUnusableAuthorities() {
        assertEquals("https://dns.example.com/dns-query?token=one",
            Doh.tidyUrl("https://DNS.example.com:443?token=one"))
        assertEquals("https://[2001:db8::1]/dns-query",
            Doh.tidyUrl("https://[2001:db8::1]"))
        listOf("https://dns.example.com:bad/query", "https://dns.example.com/#query",
            "https://user:pass@dns.example.com/query").forEach { bad ->
            assertEquals("refused: $bad", "", Doh.tidyUrl(bad))
        }
    }

    @Test
    fun aPostTransportFailureStillRetriesWithTheGetForm() {
        var postCalls = 0
        var getCalls = 0
        val body = reply("linux.sb", listOf(Record(Doh.TYPE_A, 60, byteArrayOf(1, 2, 3, 4))))

        val result = fetchWithGetFallback(
            post = { postCalls++; throw IOException("connection reset") },
            get = { getCalls++; body }
        )

        assertEquals(body.toList(), result.toList())
        assertEquals(1, postCalls)
        assertEquals(1, getCalls)
    }

    @Test
    fun aSuccessfulHttpPostWithANonDnsBodyStillRetriesGet() {
        val body = reply("linux.sb", listOf(Record(Doh.TYPE_A, 60, byteArrayOf(1, 2, 3, 4))))
        val result = fetchWithGetFallback(
            post = { "<html>POST is unavailable</html>".toByteArray() },
            get = { body }
        )
        assertTrue(body.contentEquals(result))
    }

    @Test
    fun neitherTransportMayReturnAnInvalidDnsResponse() {
        val failed = runCatching {
            fetchWithGetFallback(post = { ByteArray(12) }, get = { ByteArray(12) })
        }.exceptionOrNull()
        assertTrue(failed is IOException)
        assertTrue(failed?.suppressed?.singleOrNull() is IOException)
    }

    @Test
    fun aGetFailureKeepsThePostFailureAsSuppressedContext() {
        val failed = runCatching {
            fetchWithGetFallback(
                post = { throw IOException("POST reset") },
                get = { throw IOException("GET timeout") }
            )
        }.exceptionOrNull()

        assertTrue(failed is IOException)
        assertEquals("GET timeout", failed?.message)
        assertEquals("POST reset", failed?.suppressed?.singleOrNull()?.message)
    }

    @Test
    fun everyPresetIsAnHttpsDnsQueryAddress() {
        assertEquals(7, Doh.PRESETS.size)
        Doh.PRESETS.forEach { server ->
            assertEquals(server.url, Doh.tidyUrl(server.url))
            assertTrue(server.label.isNotBlank())
        }
        assertEquals(Doh.PRESETS.first().url, Doh.DEFAULT_SERVER)
        // Which server ships as the default is a decision, not an accident of
        // list order: it is the one that answered every probe from a network
        // where four of these seven were reset at the TLS ClientHello. Reordering
        // PRESETS should not quietly hand every fresh install somewhere else.
        assertEquals("https://dns.telekom.de/dns-query", Doh.DEFAULT_SERVER)
    }

    // ---- the resolver ---------------------------------------------------------

    @Test
    fun switchedOffItIsTheSystemResolver() {
        var asked = 0
        val resolver = DohResolver(
            transport = { _, _ -> asked++; throw IOException("must not be called") },
            system = fixedSystem
        )
        assertEquals(listOf(loopback), resolver.lookup("linux.sb"))
        assertEquals(0, asked)
    }

    @Test
    fun switchedOnItAnswersFromTheServerAndThenFromTheCache() {
        var asked = 0
        val resolver = DohResolver(
            transport = { _, _ ->
                asked++
                reply("linux.sb", listOf(Record(Doh.TYPE_A, 300, byteArrayOf(104, 21, 67, -19))))
            },
            system = failingSystem
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        val first = resolver.lookup("linux.sb")
        assertEquals("104.21.67.237", first.single().hostAddress)
        // The address keeps the name it was asked about, which is what TLS
        // verifies against - an address with no hostname would break SNI.
        assertEquals("linux.sb", first.single().hostName)
        assertEquals(1, asked)
        resolver.lookup("linux.sb")
        assertEquals("second lookup came from the cache", 1, asked)
    }

    @Test
    fun aCacheEntryExpiresOnTheRecordTtl() {
        var now = 0L
        var asked = 0
        val resolver = DohResolver(
            transport = { _, _ ->
                asked++
                reply("linux.sb", listOf(Record(Doh.TYPE_A, 60, byteArrayOf(1, 2, 3, 4))))
            },
            system = failingSystem,
            clock = { now }
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        resolver.lookup("linux.sb")
        now += 59_000L
        resolver.lookup("linux.sb")
        assertEquals(1, asked)
        now += 2_000L
        resolver.lookup("linux.sb")
        assertEquals(2, asked)
    }

    @Test
    fun aaaaIsAskedForOnlyWhenThereIsNoA() {
        val types = ArrayList<Int>()
        val resolver = DohResolver(
            transport = { _, query ->
                types += ((query[query.size - 4].toInt() and 0xFF) shl 8) or
                    (query[query.size - 3].toInt() and 0xFF)
                if (types.size == 1) {
                    reply("v6.sb", emptyList())
                } else {
                    reply("v6.sb", listOf(Record(Doh.TYPE_AAAA, 300, ByteArray(16).also { it[15] = 1 })))
                }
            },
            system = failingSystem
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        assertEquals(1, resolver.lookup("v6.sb").size)
        assertEquals(listOf(Doh.TYPE_A, Doh.TYPE_AAAA), types)
    }

    @Test
    fun aFailingServerFallsBackToTheSystemInsteadOfFailingTheRequest() {
        val resolver = DohResolver(
            transport = { _, _ -> throw IOException("dns.example.com unreachable") },
            system = fixedSystem
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        assertEquals(listOf(loopback), resolver.lookup("linux.sb"))
        assertTrue(resolver.lastError.contains("unreachable"))
    }

    @Test
    fun aServerThatFailedIsLeftAloneForAMinute() {
        // The reason this exists: DoH is on by default, so a server that cannot be
        // reached at all would otherwise cost every lookup its full timeout - once
        // per hostname, since a failure is not cached the way an answer is.
        var now = 0L
        var asked = 0
        val resolver = DohResolver(
            transport = { _, _ -> asked++; throw IOException("no route") },
            system = fixedSystem,
            clock = { now }
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        resolver.lookup("linux.sb")
        assertEquals(1, asked)
        // A name that was never asked about, so the answer cache cannot be what
        // keeps it quiet.
        now += 30_000L
        assertEquals(listOf(loopback), resolver.lookup("img.linux.sb"))
        assertEquals("still parked", 1, asked)
        // And it does come back on its own, without anyone pressing anything.
        now += 31_000L
        resolver.lookup("img.linux.sb")
        assertEquals(2, asked)
    }

    @Test
    fun anEmptyOrBrokenAnswerAlsoFallsBack() {
        listOf(
            { _: String, _: ByteArray -> reply("linux.sb", emptyList(), rcode = 2) },
            { _: String, _: ByteArray -> "not dns at all".toByteArray() }
        ).forEach { transport ->
            val resolver = DohResolver(transport = transport, system = fixedSystem)
            resolver.configure(true, Doh.DEFAULT_SERVER)
            assertEquals(listOf(loopback), resolver.lookup("linux.sb"))
        }
    }

    @Test
    fun whenBothResolversFailTheCallerSeesADnsFailure() {
        val resolver = DohResolver(
            transport = { _, _ -> throw IOException("no route") },
            system = failingSystem
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        val failed = runCatching { resolver.lookup("linux.sb") }.exceptionOrNull()
        assertTrue("expected UnknownHostException, got $failed", failed is UnknownHostException)
    }

    @Test
    fun anAddressIsNotSentToTheResolverAtAll() {
        var asked = 0
        val resolver = DohResolver(
            transport = { _, _ -> asked++; ByteArray(0) },
            system = fixedSystem
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        resolver.lookup("104.21.67.237")
        assertEquals(0, asked)
    }

    @Test
    fun aHostnameStartingWithADigitStillUsesDoh() {
        val hostname = "1.images.example.com"
        var asked = 0
        val resolver = DohResolver(
            transport = { _, _ ->
                asked++
                reply(hostname, listOf(Record(Doh.TYPE_A, 300, byteArrayOf(1, 2, 3, 4))))
            },
            system = failingSystem
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        assertEquals("1.2.3.4", resolver.lookup(hostname).single().hostAddress)
        assertEquals(1, asked)
    }

    @Test
    fun changingTheServerForgetsWhatTheOldOneSaid() {
        var asked = 0
        val resolver = DohResolver(
            transport = { _, _ ->
                asked++
                reply("linux.sb", listOf(Record(Doh.TYPE_A, 300, byteArrayOf(1, 2, 3, 4))))
            },
            system = failingSystem
        )
        resolver.configure(true, Doh.PRESETS[0].url)
        resolver.lookup("linux.sb")
        resolver.configure(true, Doh.PRESETS[1].url)
        resolver.lookup("linux.sb")
        assertEquals("the second server has to be asked itself", 2, asked)
        assertEquals(Doh.PRESETS[1].url, resolver.server)
        assertTrue(resolver.enabled)
        resolver.configure(false, "")
        assertFalse(resolver.enabled)
        // Blank means the built-in default rather than nothing at all.
        assertEquals(Doh.DEFAULT_SERVER, resolver.server)
    }

    @Test
    fun aLateAnswerFromTheOldServerDoesNotEnterTheNewCache() {
        val old = Doh.PRESETS[0].url
        val next = Doh.PRESETS[1].url
        lateinit var resolver: DohResolver
        resolver = DohResolver(transport = { server, _ ->
            val bytes = if (server == old) {
                resolver.configure(true, next)
                byteArrayOf(1, 2, 3, 4)
            } else byteArrayOf(5, 6, 7, 8)
            reply("linux.sb", listOf(Record(Doh.TYPE_A, 300, bytes)))
        }, system = failingSystem)
        resolver.configure(true, old)
        resolver.lookup("linux.sb")
        assertEquals("5.6.7.8", resolver.lookup("linux.sb").single().hostAddress)
    }

    @Test
    fun anOldServersFailureCannotParkTheNewServer() {
        val old = Doh.PRESETS[0].url
        val next = Doh.PRESETS[1].url
        lateinit var resolver: DohResolver
        resolver = DohResolver(transport = { server, _ ->
            if (server == old) {
                resolver.configure(true, next)
                throw IOException("old server failed")
            }
            reply("linux.sb", listOf(Record(Doh.TYPE_A, 300, byteArrayOf(5, 6, 7, 8))))
        }, system = fixedSystem)
        resolver.configure(true, old)
        resolver.lookup("linux.sb")
        assertTrue(resolver.lastError.isEmpty())
        assertEquals("5.6.7.8", resolver.lookup("linux.sb").single().hostAddress)
    }

    // ---- an answer outliving its TTL ------------------------------------------

    @Test
    fun anExpiredAnswerIsUsedWhenTheServerStopsAnswering() {
        var now = 0L
        var up = true
        val resolver = DohResolver(
            transport = { _, _ ->
                if (!up) throw IOException("no route")
                reply("linux.sb", listOf(Record(Doh.TYPE_A, 60, byteArrayOf(1, 2, 3, 4))))
            },
            system = fixedSystem,
            clock = { now }
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        assertEquals("1.2.3.4", resolver.lookup("linux.sb").single().hostAddress)
        up = false
        now += 61_000L
        // The TTL is up and the server is gone. What it said is still a better
        // guess than what the resolver DoH replaced would say.
        assertEquals("1.2.3.4", resolver.lookup("linux.sb").single().hostAddress)
        // Including while DoH is parked: being parked stops the app paying for a
        // dead server, it does not hand the next minute back to the system.
        now += 1_000L
        assertEquals("1.2.3.4", resolver.lookup("linux.sb").single().hostAddress)
        // Half an hour past the TTL the phone may be somewhere else entirely, and
        // then an old address stops being the better guess.
        now += 31 * 60 * 1000L
        assertEquals(listOf(loopback), resolver.lookup("linux.sb"))
    }

    @Test
    fun aNameWithNothingRememberedStillFallsBackWhileParked() {
        // The other half of the rule above: the stale tier is per name, so a name
        // this resolver never answered has nothing to fall back to but the system.
        var now = 0L
        val resolver = DohResolver(
            transport = { _, _ -> throw IOException("no route") },
            system = fixedSystem,
            clock = { now }
        )
        resolver.configure(true, Doh.DEFAULT_SERVER)
        resolver.lookup("linux.sb")
        now += 1_000L
        assertEquals(listOf(loopback), resolver.lookup("img.linux.sb"))
    }

    // ---- picking a server that answers ----------------------------------------

    @Test
    fun fastestTakesTheQuickestServerThatAnswered() {
        val asked = ConcurrentHashMap.newKeySet<String>()
        val candidates = listOf("https://a/dns-query", "https://b/dns-query", "https://c/dns-query")
        val pick = Doh.fastest(candidates) { url ->
            asked += url
            when (url) {
                candidates[0] -> bad()
                candidates[1] -> ok(300)
                else -> ok(90)
            }
        }
        assertEquals(candidates[2], pick)
        assertEquals("all of them are asked, not just up to the first that works", 3, asked.size)
    }

    @Test
    fun fastestIsNullWhenNobodyAnswers() {
        assertNull(Doh.fastest(emptyList()) { ok() })
        assertNull(Doh.fastest(listOf("https://a/dns-query")) { bad() })
        // A probe that throws is a server that did not answer, not a crash.
        assertNull(Doh.fastest(listOf("https://a/dns-query")) { throw IOException("boom") })
    }

    @Test
    fun fastestStillFindsAQuickServerWhenAnEarlierProbeUsesTheDeadline() {
        val candidates = listOf("https://slow/dns-query", "https://fast/dns-query")
        val picked = Doh.fastest(candidates, deadlineMs = 40L) { url ->
            if (url == candidates.first()) {
                Thread.sleep(250L)
                bad()
            } else {
                ok(1L)
            }
        }
        assertEquals(candidates.last(), picked)
    }

    @Test
    fun theAutomaticSwitchDoesNothingUntilItIsWiredUp() {
        var ran = false
        DohAuto.wanted = { true }
        DohAuto.search(
            Doh.DEFAULT_SERVER,
            runner = { task -> task.run(); ran = true },
            prober = { ok() }
        )
        assertFalse("no hook means no requests to seven third parties", ran)
    }

    @Test
    fun theAutomaticSwitchLeavesAChosenServerAloneAndThenPausesItself() {
        val kept = ArrayList<String>()
        var probes = 0
        val prober = { url: String ->
            probes++
            if (url == Doh.PRESETS[2].url) ok() else bad()
        }
        DohAuto.adopt = { url -> kept += url }

        // A server the user chose is a decision, and stays chosen even when it is
        // the one failing.
        DohAuto.wanted = { false }
        DohAuto.search(Doh.DEFAULT_SERVER, runner = { it.run() }, prober = prober)
        assertEquals(0, probes)
        assertTrue(kept.isEmpty())

        // Nobody chose, so it moves, and to whichever answered.
        var now = 0L
        DohAuto.wanted = { true }
        DohAuto.search(Doh.DEFAULT_SERVER, now = { now }, runner = { it.run() }, prober = prober)
        assertEquals(listOf(Doh.PRESETS[2].url), kept)

        // And then leaves it alone, rather than probing on every failed lookup.
        DohAuto.search(Doh.DEFAULT_SERVER, now = { now }, runner = { it.run() }, prober = prober)
        assertEquals(listOf(Doh.PRESETS[2].url), kept)
        now += 11 * 60 * 1000L
        DohAuto.search(Doh.DEFAULT_SERVER, now = { now }, runner = { it.run() }, prober = prober)
        assertEquals(listOf(Doh.PRESETS[2].url, Doh.PRESETS[2].url), kept)
    }

    @Test
    fun anAutomaticSearchDoesNotOverrideAChoiceMadeWhileItWasRunning() {
        val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
        val kept = ArrayList<String>()
        DohAuto.wanted = { allowed.get() }
        DohAuto.adopt = { kept += it }
        DohAuto.search(Doh.DEFAULT_SERVER, now = { 0L }, runner = { it.run() }) {
            allowed.set(false)
            ok()
        }
        assertTrue("an in-flight search must re-check the user's choice", kept.isEmpty())
    }

    /** None of the above may leak into another test, or another test class. */
    @After
    fun unwireTheAutomaticSwitch() {
        DohAuto.adopt = null
        DohAuto.wanted = { false }
        DohAuto.reset()
    }

    // ---- resolving the resolver's own name ------------------------------------

    @Test
    fun bootstrapGivesUpOnScheduleAndKeepsTheLateAnswer() {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        val slow = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                // Only the first call ever answers, so a later success can only
                // have come from the cache.
                if (calls.getAndIncrement() > 0) throw UnknownHostException(hostname)
                gate.await()
                return listOf(loopback)
            }
        }
        val dns = BoundedSystemDns(delegate = slow, timeoutMs = 50L)
        val failed = runCatching { dns.lookup("dns.example.com") }.exceptionOrNull()
        assertTrue("expected UnknownHostException, got " + failed, failed is UnknownHostException)

        gate.countDown()
        val deadline = System.currentTimeMillis() + 5_000L
        var got: List<InetAddress>? = null
        while (got == null && System.currentTimeMillis() < deadline) {
            got = runCatching { dns.lookup("dns.example.com") }.getOrNull()
            if (got == null) Thread.sleep(10)
        }
        assertEquals("the abandoned lookup still stored what it found", listOf(loopback), got)
    }

    @Test
    fun bootstrapPrefersAnOldAnswerOverNoAnswerAtAll() {
        var now = 0L
        var up = true
        val flaky = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (up) listOf(loopback) else throw UnknownHostException(hostname)
        }
        val dns = BoundedSystemDns(
            delegate = flaky, freshMs = 1_000L, staleMs = 10_000L, clock = { now }
        )
        assertEquals(listOf(loopback), dns.lookup("dns.example.com"))
        up = false
        now += 2_000L
        assertEquals(
            "an old address for a DoH endpoint beats no DoH at all",
            listOf(loopback), dns.lookup("dns.example.com")
        )
        now += 20_000L
        val failed = runCatching { dns.lookup("dns.example.com") }.exceptionOrNull()
        assertTrue("expected UnknownHostException, got " + failed, failed is UnknownHostException)
    }


    // ---- fixtures -------------------------------------------------------------

    private class Record(val type: Int, val ttl: Long, val rdata: ByteArray)

    private fun ok(ms: Long = 20L) = Doh.Probe(true, ms, listOf("1.2.3.4"), "")

    private fun bad() = Doh.Probe(false, 5L, emptyList(), "不通")

    private val loopback: InetAddress = InetAddress.getByAddress("linux.sb", byteArrayOf(127, 0, 0, 1))

    /** A system resolver that always works, so a fallback is visible. */
    private val fixedSystem = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(loopback)
    }

    /** A system resolver that never works, so a fallback cannot hide a failure. */
    private val failingSystem = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
    }

    /** The name as it goes on the wire, uncompressed. */
    private fun name(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        text.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * A response built the way a real resolver builds one: the question echoed
     * back in full, then every answer naming it with a compression pointer to
     * offset 12. The pointer is the part worth having in a fixture - it is what
     * the name-skipping has to get right.
     */
    private fun reply(question: String, records: List<Record>, rcode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0))
        out.write(0x81)
        out.write(0x80 or rcode)
        out.write(byteArrayOf(0, 1))
        out.write(byteArrayOf((records.size shr 8).toByte(), records.size.toByte()))
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(name(question))
        out.write(byteArrayOf(0, 1, 0, 1))
        records.forEach { record ->
            out.write(byteArrayOf(0xC0.toByte(), 12))
            out.write(byteArrayOf((record.type shr 8).toByte(), record.type.toByte(), 0, 1))
            out.write(
                byteArrayOf(
                    (record.ttl shr 24).toByte(), (record.ttl shr 16).toByte(),
                    (record.ttl shr 8).toByte(), record.ttl.toByte()
                )
            )
            out.write(byteArrayOf((record.rdata.size shr 8).toByte(), record.rdata.size.toByte()))
            out.write(record.rdata)
        }
        return out.toByteArray()
    }
}
