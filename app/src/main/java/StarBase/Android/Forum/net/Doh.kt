package StarBase.Android.Forum.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNS over HTTPS: the app asks a resolver of the user's choosing over TLS
 * instead of asking whatever the network handed the phone.
 *
 * This exists because linux.sb stopped resolving on some networks. It is worth
 * being precise about what it can and cannot fix, since the difference decides
 * whether turning it on helps at all:
 *
 * * **A wrong or empty answer** - the name resolves to an address that is not
 *   the site, or to nothing. That is what this fixes: the answer now comes from
 *   the chosen resolver, signed into a TLS session no middlebox can rewrite.
 * * **The connection being cut by what it asks for** - the address is right and
 *   the TLS `server_name` is what draws the reset. This does **not** fix that.
 *   The bytes still say `linux.sb` on the way out. [Frag] is the switch for that
 *   half, and the two are meant to be read together.
 *
 * It covers every request this app makes through [Net.client] - 登录 included,
 * since that is a form this app posts rather than a page a WebView loads.
 *
 * Everything above [DohResolver] is pure: a query goes in as bytes and an answer
 * comes back as bytes, so all of it is unit-tested without a network.
 */
object Doh {

    /** A DoH endpoint as 应用设置 offers it. */
    data class Server(val label: String, val url: String)

    /**
     * The endpoints on offer, in the order they are offered in.
     *
     * The order is measured rather than alphabetical. Probed directly (no proxy)
     * from the machine this was packaged on, 2026-09-02: three of the seven
     * answered a wireformat A query for linux.sb, and the other four had their
     * TLS ClientHello reset the moment it carried their own `server_name`. That
     * is the same interception this feature exists because of, not an outage on
     * their side, so they stay on the list - another network reaches them fine.
     * The three that answered come first, and the first of those is
     * [DEFAULT_SERVER].
     *
     * Two of them are picky in ways worth recording, because both are the reason
     * [fetch] looks the way it does:
     *
     * * `dns.neeb.it` answers **418** to a request without a browser-shaped
     *   `User-Agent`, so the query carries [Net.userAgent] like every other
     *   request the app makes;
     * * `dns.telekom.de` and `dns.t53.de` answer **505 HTTP Version Not
     *   Supported** to HTTP/1.1 and need HTTP/2. OkHttp negotiates h2 over TLS
     *   by itself, so this costs nothing here - it is only a warning against
     *   ever pinning this client to HTTP/1.1. The default is one of those two,
     *   which is what makes that warning load-bearing rather than trivia.
     */
    val PRESETS: List<Server> = listOf(
        Server("德国电信", "https://dns.telekom.de/dns-query"),
        Server("t53.de", "https://dns.t53.de/dns-query"),
        Server("neeb.it", "https://dns.neeb.it/dns-query"),
        Server("nashkan 堪萨斯", "https://us-kan-w-p-1.nashkan.net/dns-query"),
        Server("nashkan 纽约", "https://us-nyc-w-p-1.nashkan.net/dns-query"),
        Server("nick-slowinski", "https://dns.nick-slowinski.de/dns-query"),
        Server("vaioswolke", "https://dns.vaioswolke.xyz/dns-query")
    )

    /**
     * What a fresh install uses, since the switch is on by default.
     *
     * 德国电信: it answered every probe, and out of the three that answered it is
     * the one least likely to quietly disappear, because a carrier runs it. A
     * stored choice always wins - [UserStore.dohServer] starts blank and
     * [DohResolver.configure] reads blank as this.
     */
    val DEFAULT_SERVER: String = PRESETS.first().url

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /** `HTTPS`, the record ECH is published in. RFC 9460. */
    const val TYPE_HTTPS = 65

    /**
     * One address out of an answer, kept as the four or sixteen bytes the
     * resolver sent.
     *
     * Bytes rather than text because that is what [InetAddress.getByAddress]
     * wants: printing an address and parsing it back is a chance to get it
     * wrong, and the printed form is only ever needed by the 测试 line.
     */
    class Addr(val type: Int, val raw: ByteArray) {
        val text: String
            get() = if (raw.size == 4) {
                raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
            } else {
                runCatching { InetAddress.getByAddress(raw).hostAddress }.getOrNull().orEmpty()
            }
    }

    /**
     * A parsed response. [rcode] is the server's own: 0 is an answer, 3 is
     * NXDOMAIN, and -1 is this parser saying the bytes were not a DNS message.
     */
    class Answer(val rcode: Int, val addresses: List<Addr>, val ttlSeconds: Long)

    /**
     * A wireformat query, ready to POST.
     *
     * The id is 0 on purpose: RFC 8484 asks for it, because two identical
     * queries then have identical bytes and any cache in between can answer the
     * second one. Nothing about DoH needs a random id - the TLS session is what
     * makes an off-path answer impossible, not a guessed number.
     */
    fun query(name: String, type: Int = TYPE_A): ByteArray {
        val labels = name.trim().trim('.').split('.')
        require(labels.isNotEmpty() && labels.none { it.isEmpty() }) { "bad name: $name" }
        val out = ByteArrayOutputStream()
        // id 0, RD set, one question, no answer/authority/additional.
        out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            // A label the site could not have is not worth a round trip, and a
            // non-ASCII name would need punycode this app never has to write.
            require(bytes.isNotEmpty() && bytes.size <= 63) { "bad label: $label" }
            require(label.all { it.code in 33..126 }) { "not ascii: $label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    /**
     * Reads a response. Never throws: a truncated or nonsense body comes back as
     * `rcode = -1` with no addresses, which the resolver treats as "ask the
     * system instead" - the same as a network failure.
     */
    fun answer(bytes: ByteArray): Answer {
        if (bytes.size < 12) return Answer(-1, emptyList(), 0L)
        return try {
            read(bytes)
        } catch (e: RuntimeException) {
            Answer(-1, emptyList(), 0L)
        }
    }

    private fun read(b: ByteArray): Answer {
        requireResponseHeader(b)
        val rcode = b[3].toInt() and 0x0F
        val questions = u16(b, 4)
        val answers = u16(b, 6)
        var i = 12
        repeat(questions) {
            i = skipName(b, i) + 4
            require(i <= b.size)
        }
        val found = ArrayList<Addr>()
        var ttl = Long.MAX_VALUE
        repeat(answers) {
            i = skipName(b, i)
            require(i + 10 <= b.size)
            val type = u16(b, i)
            val recordClass = u16(b, i + 2)
            val recordTtl = u32(b, i + 4)
            val length = u16(b, i + 8)
            i += 10
            require(i + length <= b.size)
            if (recordClass == 1 && ((type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16))) {
                found += Addr(type, b.copyOfRange(i, i + length))
                if (recordTtl < ttl) ttl = recordTtl
            }
            i += length
        }
        return Answer(rcode, found, if (ttl == Long.MAX_VALUE) 0L else ttl)
    }

    /**
     * The value of one SvcParam out of an `HTTPS` record, or null when the
     * answer has no usable one.
     *
     * This is how ECH is bootstrapped. The key the client seals the real name
     * with is not in the app: it is published in DNS next to the addresses
     * (RFC 9460 for the record, RFC 9849 section 4 for what goes in it), which is
     * what lets the operator rotate it without anybody shipping a build. [Ech]
     * asks for key 5, `ech`.
     *
     * `ServiceMode` records only - priority 0 is an alias to another name and
     * carries no parameters of its own.
     *
     * Never throws, for the same reason [answer] does not: this sits in front of
     * a connection that is going to be attempted either way, so a record shape
     * this does not understand has to mean "no ECH" rather than "no connection".
     */
    fun httpsParam(bytes: ByteArray, key: Int): ByteArray? =
        if (bytes.size < 12) null else try {
            readParam(bytes, key)
        } catch (e: RuntimeException) {
            null
        }

    private fun readParam(b: ByteArray, key: Int): ByteArray? {
        requireResponseHeader(b)
        if (b[3].toInt() and 0x0F != 0) return null
        val questions = u16(b, 4)
        val answers = u16(b, 6)
        var i = 12
        repeat(questions) {
            i = skipName(b, i) + 4
            require(i <= b.size)
        }
        repeat(answers) {
            i = skipName(b, i)
            require(i + 10 <= b.size)
            val type = u16(b, i)
            val recordClass = u16(b, i + 2)
            val length = u16(b, i + 8)
            i += 10
            require(i + length <= b.size)
            if (type == TYPE_HTTPS && recordClass == 1) {
                param(b, i, i + length, key)?.let { return it }
            }
            i += length
        }
        return null
    }

    private fun requireResponseHeader(b: ByteArray) {
        val flags = u16(b, 2)
        // QR must be a standard response, and TC cannot provide a complete answer over HTTPS.
        require(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0200 == 0)
    }

    /**
     * Walks one record: priority, target name, then the parameter pairs.
     *
     * Keys arrive in ascending order and the wanted one may not be there at all,
     * so every pair is read rather than stopping at the first mismatch.
     */
    private fun param(b: ByteArray, start: Int, end: Int, key: Int): ByteArray? {
        if (start + 2 > end) return null
        if (u16(b, start) == 0) return null     // AliasMode carries no parameters
        var i = skipName(b, start + 2)
        while (i + 4 <= end) {
            val found = u16(b, i)
            val size = u16(b, i + 2)
            val value = i + 4
            if (value + size > end) return null
            if (found == key) return b.copyOfRange(value, value + size)
            i = value + size
        }
        return null
    }

    /**
     * Walks past one name and returns where it ended.
     *
     * A name can end in a pointer back into the message - Cloudflare's answers
     * always do, since every record repeats the question's name - and a pointer
     * is the end of that name, two bytes wide. The loop is bounded so a message
     * whose labels never terminate cannot hang the resolver.
     */
    private fun skipName(b: ByteArray, start: Int): Int {
        var i = start
        var hops = 0
        while (hops++ < 128) {
            val length = b[i].toInt() and 0xFF
            if (length == 0) return i + 1
            if (length and 0xC0 == 0xC0) {
                require(i + 2 <= b.size)
                return i + 2
            }
            require(length <= 63 && i + 1 + length <= b.size)
            i += 1 + length
        }
        throw IllegalStateException("name has no end")
    }

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    /**
     * base64url without padding, as RFC 8484 wants it in `?dns=`.
     *
     * Written out rather than taken from a library: `java.util.Base64` needs API
     * 26 and this app still runs on 24, and `android.util.Base64` cannot be
     * called from a JVM unit test. It is twenty lines and it is testable.
     */
    fun base64Url(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) {
                out.append(alphabet[((b1 and 0x0F) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            }
            if (b2 >= 0) out.append(alphabet[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }

    /**
     * Cleans up what someone typed into 自定义, or returns "" when it is not
     * usable at all.
     *
     * `https://` is required rather than assumed: a DoH address that is not
     * over TLS is not a DoH address, and quietly adding the scheme would hide
     * that. A bare host gets the standard `/dns-query` path, because that is
     * what every one of these servers uses and typing it is a nuisance.
     */
    fun tidyUrl(raw: String): String {
        val text = raw.trim()
        if (text.any { it.isWhitespace() }) return ""
        val url = text.toHttpUrlOrNull() ?: return ""
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || (!url.host.contains('.') && !url.host.contains(':'))
        ) return ""
        return url.newBuilder().apply {
            if (url.encodedPath == "/") encodedPath("/dns-query")
        }.build().toString()
    }

    private val WIRE = "application/dns-message".toMediaType()

    /**
     * The client the queries themselves go out on.
     *
     * Its DNS is the system's, since the resolver's own hostname has to be
     * resolved by something and cannot be resolved by itself - trusted for
     * exactly that one name and nothing else. It goes through
     * [BoundedSystemDns] rather than [Dns.SYSTEM] directly because that wait is
     * otherwise unbounded and comes out of the budget below: `getaddrinfo` is
     * not interruptible, so a black-holed network resolver can hold the thread
     * far past [callTimeout] before the first DoH byte is even sent.
     *
     * Timeouts are short because a lookup sits in front of a real request that
     * is already waiting on it - a slow DoH server should lose to the fallback
     * quickly, and since this is on by default, the one paying for that wait is
     * someone who never asked for it. [DohResolver] then parks DoH for a minute,
     * so a server that is simply unreachable costs this once rather than once
     * per name.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(bootstrap)
            // 分片, for the same reason the site needs it: four of [PRESETS] are
            // unreachable because their own `server_name` draws a RST, which is the
            // one thing splitting the ClientHello can do something about. Off until
            // the user turns it on, like everywhere else. See [Frag].
            .socketFactory(SiteSockets)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * One query over the wire: POST first, then the base64url GET as a retry.
     *
     * Both shapes are in RFC 8484 and servers disagree about which they like.
     * The retry is what makes a picky endpoint work instead of quietly falling
     * back to the system resolver on every lookup. Some endpoints reject POST
     * by resetting the connection, so an [IOException] from the first request
     * must also reach the GET retry rather than short-circuiting the resolver.
     */
    fun fetch(serverUrl: String, query: ByteArray): ByteArray {
        val endpoint = tidyUrl(serverUrl).toHttpUrlOrNull() ?: throw IOException("DoH 地址无效")
        val post = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/dns-message")
            .header("User-Agent", Net.userAgent())
            .post(query.toRequestBody(WIRE))
            .build()
        val get = Request.Builder()
            .url(endpoint.newBuilder().setQueryParameter("dns", base64Url(query)).build())
            .header("Accept", "application/dns-message")
            .header("User-Agent", Net.userAgent())
            .get()
            .build()
        return fetchWithGetFallback(
            post = {
                http.newCall(post).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("DoH POST ${response.code}")
                    response.body?.bytes() ?: ByteArray(0)
                }
            },
            get = {
                http.newCall(get).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("DoH GET ${response.code}")
                    response.body?.bytes() ?: ByteArray(0)
                }
            }
        )
    }

    /**
     * Executes the two RFC 8484 request shapes, keeping the first failure only
     * as context when the fallback fails too. The callbacks let tests cover
     * transport failures without depending on a public resolver.
     */
    internal fun fetchWithGetFallback(
        post: () -> ByteArray,
        get: () -> ByteArray
    ): ByteArray {
        val postFailure = try {
            post().takeIf { answer(it).rcode >= 0 }?.let { return it }
            IOException("DoH POST returned an invalid DNS response")
        } catch (e: IOException) {
            e
        }
        return try {
            get().takeIf { answer(it).rcode >= 0 }
                ?: throw IOException("DoH GET returned an invalid DNS response")
        } catch (e: IOException) {
            if (postFailure !== e) e.addSuppressed(postFailure)
            throw e
        }
    }

    /** What one press of 测试 found out. */
    class Probe(val ok: Boolean, val ms: Long, val addresses: List<String>, val error: String)

    /**
     * Asks one server for one name and reports what came back. Blocking, and
     * used by 应用设置 so the answer on screen is a real answer from that server
     * rather than a promise that it should work.
     */
    fun probe(serverUrl: String, name: String = "linux.sb"): Probe {
        val started = System.currentTimeMillis()
        return try {
            val parsed = answer(fetch(serverUrl, query(name)))
            val took = System.currentTimeMillis() - started
            when {
                parsed.rcode == -1 -> Probe(false, took, emptyList(), "返回的不是 DNS 应答")
                parsed.rcode != 0 -> Probe(false, took, emptyList(), "服务器返回 rcode ${parsed.rcode}")
                parsed.addresses.isEmpty() -> Probe(false, took, emptyList(), "没有解析到地址")
                else -> Probe(true, took, parsed.addresses.map { it.text }, "")
            }
        } catch (e: Exception) {
            Probe(
                false,
                System.currentTimeMillis() - started,
                emptyList(),
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * Asks several servers at once and returns the address of the fastest one
     * that answered, or null when none of them did.
     *
     * All at once rather than one after another: these are probed because the
     * one in use has already failed, so the phone is on a network where some of
     * [PRESETS] draw a reset and cost the full timeout. In series that is most
     * of a minute; in parallel it is the slowest single probe. [deadlineMs] is
     * the ceiling on the whole thing either way.
     *
     * Fastest rather than first-in-list because latency is the only thing that
     * can be measured from here, and a resolver sits in front of every request.
     * [prober] is a parameter so this is testable without a network.
     */
    fun fastest(
        servers: List<String>,
        deadlineMs: Long = 8_000L,
        prober: (String) -> Probe = ::probe
    ): String? {
        if (servers.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(servers.size.coerceAtMost(8)) { runnable ->
            Thread(runnable, "doh-benchmark").apply { isDaemon = true }
        }
        return try {
            val asked = servers.map { url -> url to pool.submit<Probe> { prober(url) } }
            val deadline = System.currentTimeMillis() + deadlineMs
            asked.mapNotNull { (url, future) ->
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                val result = runCatching { future.get(left, TimeUnit.MILLISECONDS) }.getOrNull()
                if (result != null && result.ok) url to result.ms else null
            }.minByOrNull { it.second }?.first
        } catch (e: Exception) {
            null
        } finally {
            // Daemon threads, so an interrupted probe cannot hold the process;
            // its own callTimeout ends it either way.
            pool.shutdownNow()
        }
    }

    /** The one bounded system resolver the queries above bootstrap through. */
    private val bootstrap: Dns = BoundedSystemDns()
}

/**
 * OkHttp's [Dns], answered over HTTPS when the user asked for that and by the
 * system otherwise.
 *
 * Four rules, in order:
 *
 * 1. switched off, or a hostname that is already an address - the system answers;
 * 2. a live cache entry - that answers, so one open topic is not fifteen lookups;
 * 3. otherwise ask the server, and if it says nothing usable, **an expired entry
 *    for the same name answers** - see [stale]. On the networks this feature
 *    exists for, a DoH answer from ten minutes ago is worth more than a fresh
 *    one from the resolver being avoided;
 * 4. and only when there is not even that, **fall back to the system resolver**
 *    rather than failing the request. A DoH server that is down must not take the
 *    app offline with it.
 *
 * A resolves first, and AAAA is asked for only when there is no A: on a v4-only
 * mobile network handing OkHttp a v6 address first buys a connect timeout, and
 * one round trip saved on every lookup is worth more than dual-stack ordering
 * this app cannot measure.
 */
class DohResolver(
    private val transport: (String, ByteArray) -> ByteArray = Doh::fetch,
    private val system: Dns = Dns.SYSTEM,
    private val clock: () -> Long = System::currentTimeMillis
) : Dns {

    /**
     * One remembered answer. [freshUntil] is the record's own TTL; [staleUntil]
     * is how far past it the addresses may still be used when the server has
     * stopped answering - see [stale].
     */
    private class Hit(
        val addresses: List<InetAddress>,
        val freshUntil: Long,
        val staleUntil: Long
    )

    private class State(val enabled: Boolean, val server: String) {
        val cache = ConcurrentHashMap<String, Hit>()
        @Volatile var lastError = ""
        @Volatile var coolOffUntil = 0L
    }

    @Volatile
    private var state = State(false, Doh.DEFAULT_SERVER)

    val enabled: Boolean get() = state.enabled
    val server: String get() = state.server

    /** Why the last DoH lookup fell back, for 应用设置 to show. "" when fine. */
    val lastError: String get() = state.lastError

    /**
     * When to start asking the DoH server again after it failed.
     *
     * Without this, a server that cannot be reached at all costs every single
     * lookup its full timeout before falling back - and since a failure is not
     * cached the way an answer is, every new hostname pays again. That is
     * tolerable for a switch someone turned on deliberately; it is not tolerable
     * for a default, which is why this exists. One failure parks DoH for a
     * minute; the next lookup after that tries again, so a server that comes back
     * is picked up on its own.
     *
     * Being parked is **not** the same as being off. A name with a [stale] entry
     * is still answered from what DoH said, because the point of the pause is to
     * stop paying for a dead server - not to hand the next minute of lookups to
     * the resolver this feature exists to get away from.
     */
    /**
     * Points the resolver at a server, or turns it off. Clears the cache either
     * way: entries from the old setting are answers from a resolver the user
     * just stopped trusting. In-flight lookups retain their own state and cannot
     * publish answers or failures into the replacement.
     */
    fun configure(on: Boolean, url: String) {
        state = State(on, url.ifBlank { Doh.DEFAULT_SERVER })
    }

    /** Drops what is remembered, without changing the setting. */
    fun forget() = state.cache.clear()

    override fun lookup(hostname: String): List<InetAddress> {
        val active = state
        if (!active.enabled || hostname.isBlank() || numeric(hostname)) return system.lookup(hostname)
        val known = active.cache[hostname]
        val now = clock()
        if (known != null && now < known.freshUntil) return known.addresses
        if (now < active.coolOffUntil) return stale(known, now) ?: system.lookup(hostname)
        val hit = try {
            resolve(hostname, active)
        } catch (e: Exception) {
            active.lastError = e.message ?: e.javaClass.simpleName
            null
        }
        if (hit != null && hit.addresses.isNotEmpty()) {
            active.cache[hostname] = hit
            active.lastError = ""
            active.coolOffUntil = 0L
            return hit.addresses
        }
        active.coolOffUntil = now + COOL_OFF_MS
        // The server in use has stopped answering. If nobody ever chose it, this
        // is where the app goes looking for one that answers; it returns at once
        // and any switch lands on a later lookup.
        if (state === active) DohAuto.search(active.server)
        // Throws UnknownHostException of its own if the system cannot do it
        // either, which is the right ending: the caller sees a DNS failure.
        return stale(known, now) ?: system.lookup(hostname)
    }

    /**
     * An expired answer, while it is still worth having.
     *
     * This is the difference between DoH being a switch that works and one that
     * quietly gives up. Without it, a single failed query hands the following
     * minute of lookups to the system resolver - which on the networks that
     * motivated this feature is the one answering wrongly, so the app goes back
     * to being unopenable for exactly the reason DoH was turned on. An address
     * that was right ten minutes ago is a far better guess than one from a
     * resolver known to be rewriting them, and the site sits behind a CDN whose
     * addresses do not move on that timescale.
     *
     * Half an hour past the TTL, and no further: past that the phone has
     * plausibly changed networks, and a stale answer stops being a better guess.
     */
    private fun stale(known: Hit?, now: Long): List<InetAddress>? =
        known?.takeIf { now < it.staleUntil && it.addresses.isNotEmpty() }?.addresses

    private fun resolve(hostname: String, active: State): Hit {
        var parsed = Doh.answer(transport(active.server, Doh.query(hostname, Doh.TYPE_A)))
        if (parsed.rcode == 0 && parsed.addresses.isEmpty()) {
            parsed = Doh.answer(transport(active.server, Doh.query(hostname, Doh.TYPE_AAAA)))
        }
        if (parsed.rcode != 0) {
            active.lastError = if (parsed.rcode == -1) "应答无法解析" else "rcode ${parsed.rcode}"
            return Hit(emptyList(), 0L, 0L)
        }
        val addresses = parsed.addresses.mapNotNull { addr ->
            runCatching { InetAddress.getByAddress(hostname, addr.raw) }.getOrNull()
        }
        // The record's own TTL, kept inside sane bounds: a five-second TTL would
        // mean a lookup per request, and a one-day TTL would outlive the network
        // the phone is on.
        val seconds = parsed.ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        val freshUntil = clock() + seconds * 1000L
        return Hit(addresses, freshUntil, freshUntil + STALE_MS)
    }

    /** Recognise address literals without resolving digit-prefixed hostnames. */
    private fun numeric(hostname: String): Boolean {
        if (hostname.contains(':')) return true
        val parts = hostname.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
        }
    }

    private companion object {
        const val MIN_TTL_SECONDS = 30L
        const val MAX_TTL_SECONDS = 600L
        const val COOL_OFF_MS = 60_000L

        /** How long past its TTL an answer stays usable as a last resort. */
        const val STALE_MS = 30 * 60 * 1000L
    }
}

/**
 * The one resolver [Net.client] asks.
 *
 * A single instance so the cache is shared and so 应用设置 can point it somewhere
 * else while the app is running. It starts switched off even though the stored
 * setting defaults to on: [MainActivity] hands over that setting before the first
 * screen draws, and a process that never got there (a boot receiver re-hanging
 * alarms) has no business reaching for a DoH server.
 */
val SiteDns: DohResolver = DohResolver()

/**
 * [Dns.SYSTEM] with a deadline, for the one name DoH cannot resolve itself.
 *
 * `getaddrinfo` on Android is not interruptible. A network whose DNS server
 * black-holes queries holds the calling thread for as long as the platform's own
 * retry schedule takes - tens of seconds, and none of it cancellable - which is
 * both longer than the whole call budget of [Doh.fetch] and spent before the
 * first DoH byte goes out. Bounding it is what stops a broken network resolver
 * from making DoH slower than the resolver it is replacing.
 *
 * The wait therefore happens on a throwaway thread and this one gives up on
 * schedule. A late answer is not wasted: the abandoned thread still writes its
 * result into the cache, so the retry that is about to happen finds it there.
 * And an expired entry is preferred over failing, for the same reason
 * [DohResolver.stale] exists - a slightly old address for a DoH endpoint is
 * worth more than no DoH at all.
 *
 * Only endpoint hostnames come through here, a handful of names that are asked
 * about repeatedly, so the cache is small and the TTLs are its own rather than
 * the record's - there is no wireformat answer at this level to read one from.
 */
internal class BoundedSystemDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val timeoutMs: Long = 1_200L,
    private val freshMs: Long = 10 * 60 * 1000L,
    private val staleMs: Long = 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) : Dns {

    private class Hit(val addresses: List<InetAddress>, val freshUntil: Long, val staleUntil: Long)

    private val cache = ConcurrentHashMap<String, Hit>()

    override fun lookup(hostname: String): List<InetAddress> {
        remembered(hostname, stale = false)?.let { return it }
        return try {
            // The task stores its own result, so it lands in the cache whether or
            // not this thread is still waiting for it by then.
            val task = FutureTask {
                delegate.lookup(hostname).sortedBy { it !is Inet4Address }.also { keep(hostname, it) }
            }
            workers.execute(task)
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            // Includes RejectedExecutionException, when every worker is already
            // stuck on a lookup that will not return. OkHttp expects an
            // UnknownHostException out of here and nothing else.
            remembered(hostname, stale = true)
                ?: throw UnknownHostException("$hostname: 系统解析没有及时回答").apply { initCause(e) }
        }
    }

    private fun remembered(hostname: String, stale: Boolean): List<InetAddress>? {
        val hit = cache[hostname] ?: return null
        val now = clock()
        if (now >= hit.staleUntil) {
            cache.remove(hostname, hit)
            return null
        }
        return hit.addresses.takeIf { it.isNotEmpty() && (now < hit.freshUntil || stale) }
    }

    private fun keep(hostname: String, addresses: List<InetAddress>) {
        if (addresses.isEmpty()) return
        if (cache.size > 64) cache.clear()
        val now = clock()
        cache[hostname] = Hit(addresses, now + freshMs, now + staleMs)
    }

    private companion object {
        /**
         * Unbounded-ish on purpose. A queue would put the next name behind the
         * lookup that is already stuck, which is the failure this class exists to
         * prevent; a thread that is stuck in `getaddrinfo` cannot be reclaimed, so
         * the only thing to do with a new name is give it a new thread. Daemon
         * threads, so none of them can keep the process alive.
         */
        val workers = ThreadPoolExecutor(
            0, 16, 30, TimeUnit.SECONDS, SynchronousQueue()
        ) { runnable -> Thread(runnable, "doh-bootstrap").apply { isDaemon = true } }
    }
}

/**
 * 自动换服务器: moving off a DoH endpoint that has stopped answering.
 *
 * [Doh.DEFAULT_SERVER] was measured on one network and answers there. On another
 * it may be one of the four in [Doh.PRESETS] whose own TLS handshake draws a
 * reset, in which case every lookup falls back and DoH is a switch that is on and
 * doing nothing. The person that hurts most is the one it was defaulted on for:
 * someone who cannot resolve linux.sb cannot read the settings page that would
 * fix it. So when the endpoint in use fails, the app asks the others and keeps
 * whichever answers.
 *
 * Both hooks are unset by default, which means no probing at all. That is
 * deliberate: firing seven requests at third parties is not something a process
 * that never wired this up (a boot receiver re-hanging alarms) should be able to
 * start, and [MainActivity] is the one place that can say whether it is wanted.
 */
object DohAuto {

    /**
     * What to do with an endpoint that answered, or null to never switch.
     *
     * Called on a background thread, so an implementation that touches app state
     * has to get itself to the main one.
     */
    @Volatile
    var adopt: ((String) -> Unit)? = null

    /**
     * Whether moving is this app's business at all right now.
     *
     * [MainActivity] answers it by asking whether the stored choice is blank. A
     * blank choice is the built-in default - nobody picked it, so replacing it
     * when it does not work is a repair. A server the user chose is a decision,
     * and this must not quietly overrule it; 应用设置 shows the failure instead.
     */
    @Volatile
    var wanted: () -> Boolean = { false }

    private val busy = AtomicBoolean(false)

    @Volatile
    private var quietUntil = 0L

    /**
     * Looks for a replacement for [current], in the background, at most once
     * every ten minutes. Returns immediately; a switch lands on a later lookup.
     *
     * Called from inside a failed lookup, which is on whatever thread OkHttp was
     * using, so nothing here may block and nothing here may throw. The seams are
     * parameters so the whole decision is testable without threads or a network.
     */
    internal fun search(
        current: String,
        now: () -> Long = System::currentTimeMillis,
        runner: (Runnable) -> Unit = { task ->
            Thread(task, "doh-autoselect").apply { isDaemon = true }.start()
        },
        prober: (String) -> Doh.Probe = Doh::probe
    ) {
        val keep = adopt ?: return
        if (!wanted()) return
        if (now() < quietUntil) return
        if (!busy.compareAndSet(false, true)) return
        runner(Runnable {
            try {
                val others = Doh.PRESETS.map { it.url }.filter { it != current }
                Doh.fastest(others, prober = prober)?.let {
                    if (it != current && wanted()) keep(it)
                }
            } catch (e: Exception) {
                // A search for a working resolver failing is not worth an app.
            } finally {
                // Timed from the end, so a search that took its full deadline
                // does not get to run again straight away.
                quietUntil = now() + QUIET_MS
                busy.set(false)
            }
        })
    }

    /** Forgets the throttle, so a fresh setting is acted on at once. */
    fun reset() {
        quietUntil = 0L
    }

    private const val QUIET_MS = 10 * 60 * 1000L
}
