package StarBase.Android.Forum.net

import okhttp3.Dns
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Hides the TLS `server_name` from a middlebox that reads one packet at a time,
 * by cutting the ClientHello inside the hostname.
 *
 * This is the half of the problem [Doh] describes and cannot fix. Two separate
 * failures wear the same face - 「站点打不开」:
 *
 * * **the answer is wrong** - [DohResolver] fixes that;
 * * **the answer is right and the handshake is cut** - the address really is the
 *   site's, TCP connects, and the connection dies the moment the ClientHello
 *   goes out carrying `server_name=linux.sb`. Same address, any other name, and
 *   the handshake completes. That is this.
 *
 * There are two ways to cut, and 测试 tries both because they beat different
 * inspectors:
 *
 * * [Style.SEGMENT] - one TLS record, two TCP segments. A TLS record is a byte
 *   stream and may arrive in as many segments as the sender likes, so this is
 *   invisible to the server. It beats an inspector that reads single packets and
 *   loses to one that reassembles the stream.
 * * [Style.RECORD] - two TLS records, one segment each, carrying the halves of
 *   the same ClientHello. Legal TLS (RFC 8446 §5.1: a handshake message may be
 *   fragmented across records) and Cloudflare accepts it. It additionally beats
 *   an inspector that *does* reassemble TCP but assumes one record holds one
 *   whole handshake message - which is a common shortcut, because doing it
 *   properly means buffering. This is what the switch turns on.
 *
 * What it does not do:
 *
 * * **it is not a proxy.** The bytes still go to the site's own address, so
 *   anything blocking the address rather than the name is untouched;
 * * **it is not ECH.** A browser with DoH on fetches the site's `HTTPS` record,
 *   finds an `ech=` key in it and encrypts the real name inside the handshake, so
 *   there is no name on the wire to cut at all. That is why 「浏览器开了 DoH 就能
 *   打开」 and this app still cannot: Conscrypt has no ECH and OkHttp cannot ask
 *   for one, so the name goes out in the clear no matter what DNS said. Splitting
 *   it is the closest this app can get, and against an inspector that reassembles
 *   both TCP and records, the closest is not close enough;
 * * **it cannot forge or reorder packets.** A decoy ClientHello at a low TTL or
 *   deliberately out-of-order segments need raw sockets, which need the VPN
 *   permission this app does not ask for.
 *
 * It covers every connection [Net.client] makes and [Doh]'s own requests too:
 * four of the seven presets in [Doh.PRESETS] are unreachable for exactly this
 * reason, so the same split is what can bring them back. 登录 is one of those
 * connections now that it is a form this app posts itself.
 *
 * When the split is not enough - a middlebox that reassembles the TCP stream sees
 * the whole name however it is cut - [CronetFallbackInterceptor] is the other
 * answer: not a finer cut, a different transport.
 */
object Frag {

    init {
        // Conscrypt builds a TLS socket over an existing socket in one of two
        // ways: an engine socket, which writes its records through the underlying
        // socket's [OutputStream] - where [FragStream] is waiting - or a file
        // descriptor socket, which writes to the fd directly and never touches
        // that stream. On a device that picks the second one the split silently
        // does nothing, so ask for the first. The flag is read once when
        // Conscrypt's factory class initialises, which is why this is in an
        // `init` on the object [MainActivity] configures before the first request.
        // Both names are set because the platform copy of Conscrypt is
        // repackaged, and the repackaging rewrites strings that look like class
        // names. Neither is load-bearing: [probe] reports whether the split
        // actually happened rather than trusting that it did.
        runCatching {
            System.setProperty("org.conscrypt.useEngineSocket", "true")
            System.setProperty("com.android.org.conscrypt.useEngineSocket", "true")
        }
    }

    /** How a new socket sends the first thing written to it. */
    enum class Style {
        /** Unchanged - what every socket does until the user turns 分片 on. */
        NONE,

        /** One record, two TCP segments, cut inside the name. */
        SEGMENT,

        /** Two records, one segment each, cut inside the name. */
        RECORD
    }

    /**
     * What new sockets do.
     *
     * Read when a socket is created rather than when it is written to, so a
     * connection already in OkHttp's pool keeps the behaviour it was opened with.
     * Flipping the switch can then never split a record in mid-stream, which
     * would corrupt a live TLS session rather than hide a name.
     */
    @Volatile
    var style: Style = Style.NONE
        private set

    /** Whether new sockets split at all. */
    val enabled: Boolean get() = style != Style.NONE

    fun configure(on: Boolean) {
        style = if (on) Style.RECORD else Style.NONE
    }

    /**
     * How long to wait between the two halves.
     *
     * `TCP_NODELAY` is already set, so each write should already be its own
     * segment - this is here because "should" is doing a lot of work in that
     * sentence, and 20 ms once per *new* connection is not something anybody can
     * feel. Connections are pooled, so most requests pay nothing at all.
     */
    const val SPLIT_DELAY_MS = 20L

    /**
     * Where the hostname sits inside a ClientHello, or null when this buffer is
     * not one: not a handshake record, not a ClientHello, a record that continues
     * in a later write, or no `server_name` extension in it.
     *
     * Null means "send it unchanged", and that is the right answer to every one of
     * those - a connection with no name in it has no name to hide.
     *
     * Never throws. A buffer that runs out mid-structure is a buffer this does not
     * understand, which is the same as not being a ClientHello.
     */
    fun sniRange(b: ByteArray, off: Int, len: Int): IntRange? =
        try {
            findSni(b, off, len)
        } catch (e: RuntimeException) {
            null
        }
    private fun findSni(b: ByteArray, off: Int, len: Int): IntRange? {
        // record type(1) version(2) length(2) | handshake type(1) length(3) |
        // client_version(2) random(32) - 47 bytes before anything variable-length.
        if (len < 47) return null
        if ((b[off].toInt() and 0xFF) != HANDSHAKE) return null
        if ((b[off + 5].toInt() and 0xFF) != CLIENT_HELLO) return null
        val end = off + 5 + u16(b, off + 3)
        // The rest of the record is in a write this one cannot see, so the
        // hostname may not even be here yet. Passing it through whole is right:
        // a ClientHello already spread over two writes is already split.
        if (end > off + len) return null
        var i = off + 43
        i += 1 + (b[i].toInt() and 0xFF)        // session_id
        i += 2 + u16(b, i)                      // cipher_suites
        i += 1 + (b[i].toInt() and 0xFF)        // compression_methods
        if (i + 2 > end) return null
        val extensions = minOf(i + 2 + u16(b, i), end)
        i += 2
        while (i + 4 <= extensions) {
            val type = u16(b, i)
            val size = u16(b, i + 2)
            val data = i + 4
            if (data + size > extensions) return null
            if (type == SERVER_NAME) return hostName(b, data, size)
            i = data + size
        }
        return null
    }

    /**
     * The `host_name` entry out of a `server_name` extension.
     *
     * The extension is a list whose entries are tagged, even though `host_name` is
     * the only tag anyone has ever sent - so this walks the list rather than
     * assuming the first entry is the name.
     */
    private fun hostName(b: ByteArray, data: Int, size: Int): IntRange? {
        val listEnd = data + size
        var i = data + 2                        // past server_name_list length
        while (i + 3 <= listEnd) {
            val nameType = b[i].toInt() and 0xFF
            val nameLen = u16(b, i + 1)
            val start = i + 3
            if (start + nameLen > listEnd) return null
            // A one-byte name has no inside to cut into.
            if (nameType == HOST_NAME && nameLen >= 2) return start until start + nameLen
            i = start + nameLen
        }
        return null
    }
    /**
     * Where to cut this buffer, or null to send it whole.
     *
     * The middle of the hostname. Cutting anywhere earlier would leave the whole
     * name sitting in the second segment, where an inspector reading one packet at
     * a time finds it just as easily - so "split the ClientHello" is not the point,
     * "split the name" is. Both halves have to be non-empty, or the two segments
     * this is built on are one segment.
     */
    fun cutAt(b: ByteArray, off: Int, len: Int): Int? {
        val name = sniRange(b, off, len) ?: return null
        val at = name.first + (name.last - name.first + 1) / 2
        return if (at > off && at < off + len) at else null
    }

    /**
     * The same ClientHello re-framed as two TLS records, cut inside the name, or
     * null when this buffer is not one to re-frame.
     *
     * The handshake bytes are untouched and so is their order: only the record
     * headers around them change, and the two lengths still add up to the one the
     * single record had. A server reassembles the handshake message out of the
     * records it arrives in, which is why [Style.SEGMENT] and this look identical
     * to the far end and quite different to a middlebox.
     *
     * Anything in the buffer after the record - a second record the TLS stack put
     * in the same write - rides along behind the second half, unaltered.
     */
    fun records(b: ByteArray, off: Int, len: Int): Pair<ByteArray, ByteArray>? {
        val at = cutAt(b, off, len) ?: return null
        val body = off + 5
        val end = body + u16(b, off + 3)
        // cutAt only answers for a record whose own length fits in this buffer,
        // and the cut is inside the name, which is inside the record - so both
        // halves hold at least one byte.
        return (header(b, off, at - body) + b.copyOfRange(body, at)) to
            (header(b, off, end - at) + b.copyOfRange(at, off + len))
    }

    /** A record header copying the type and version of the one at [off]. */
    private fun header(b: ByteArray, off: Int, length: Int): ByteArray = byteArrayOf(
        b[off], b[off + 1], b[off + 2], (length shr 8).toByte(), length.toByte()
    )

    /**
     * Writes one buffer the way [style] asks for. True when it went out in two
     * pieces.
     *
     * Kept off the socket so a test can hand it a stream that records where each
     * write ended: which bytes land in which segment is the entire feature, and
     * that is not something a unit test can ask a socket.
     */
    fun write(
        out: OutputStream,
        b: ByteArray,
        off: Int,
        len: Int,
        style: Style = Style.SEGMENT
    ): Boolean {
        val halves = when (style) {
            Style.NONE -> null
            Style.RECORD -> records(b, off, len)
            Style.SEGMENT -> cutAt(b, off, len)?.let { at ->
                b.copyOfRange(off, at) to b.copyOfRange(at, off + len)
            }
        }
        if (halves == null) {
            out.write(b, off, len)
            out.flush()
            return false
        }
        out.write(halves.first)
        out.flush()
        pause()
        out.write(halves.second)
        out.flush()
        return true
    }

    private fun pause() {
        try {
            Thread.sleep(SPLIT_DELAY_MS)
        } catch (e: InterruptedException) {
            // The write is what the caller asked for; losing the pause is not
            // worth dropping it. The flag goes back so the caller still sees it.
            Thread.currentThread().interrupt()
        }
    }

    /** What one press of 测试 found out, one way round. */
    class Probe(
        val ok: Boolean,
        val ms: Long,
        val detail: String,
        val error: String,
        /** The address it actually connected to. */
        val address: String = "",
        /** Whether the split reached the wire, when one was asked for. */
        val note: String = ""
    )

    /**
     * What the two resolvers say the name is.
     *
     * [text] is the line 应用设置 prints above the three connections, and [polluted]
     * is what decides which failure is on screen. 「都不通」 means one thing when DoH
     * and the system agree about the address - the address is the site's and the
     * name is what draws the reset - and something else entirely when they
     * disagree, where the app is dialling an address the network made up and no
     * amount of splitting will help. The two were indistinguishable until this
     * existed.
     */
    class Addresses(val text: String, val polluted: Boolean)

    /**
     * Asks both resolvers where [host] is. Blocking.
     *
     * It asks the DoH server itself rather than reading [DohResolver]'s cache: a
     * cached answer is what the app used last time, and the question here is what
     * the two of them say right now.
     */
    fun addresses(host: String): Addresses {
        val system = try {
            Dns.SYSTEM.lookup(host).mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            return Addresses("系统 DNS 解析不出 $host：$why", false)
        }
        val fromSystem = system.joinToString(" / ")
        if (!SiteDns.enabled) {
            return Addresses("地址：$fromSystem（系统 DNS，直连解析关着）", false)
        }
        val doh = Doh.probe(SiteDns.server, host)
        if (!doh.ok) {
            return Addresses("DoH 没答上（${doh.error}），只能用系统 DNS：$fromSystem", false)
        }
        val fromDoh = doh.addresses.joinToString(" / ")
        // Cloudflare hands out different addresses out of the same anycast pool to
        // different resolvers, so "not identical" is not pollution. Sharing nothing
        // at all is.
        return if (doh.addresses.any { it in system }) {
            Addresses("地址：$fromDoh（DoH，与系统 DNS 一致）", false)
        } else {
            Addresses("地址：DoH $fromDoh · 系统 $fromSystem —— 两边不一样", true)
        }
    }

    /**
     * Opens a real connection to the site and reports whether the handshake
     * survived, sending the ClientHello the way [style] asks. Blocking.
     *
     * It asks for the page too, not just the handshake. A reset that arrives a beat
     * late - after the handshake, on the first application record - looks exactly
     * like success to anything that stops at [SSLSocket.startHandshake].
     *
     * The addresses come from [SiteDns], so this measures the path the app actually
     * uses rather than a second opinion about where the site lives, and it walks
     * all of them the way OkHttp does: stopping after the first would report a
     * failure the app itself would have got past.
     */
    fun probe(host: String, style: Style, ech: Boolean = false): Probe {
        val started = System.currentTimeMillis()
        val addresses = try {
            SiteDns.lookup(host)
        } catch (e: Exception) {
            return Probe(false, System.currentTimeMillis() - started, "", reason(e))
        }
        if (addresses.isEmpty()) {
            return Probe(false, System.currentTimeMillis() - started, "", "没有解析到地址")
        }
        var last: Probe? = null
        for (address in addresses) {
            val attempt = attempt(host, address, style, ech)
            if (attempt.ok) return attempt
            last = attempt
        }
        return last!!
    }

    /** One connection to one address. */
    private fun attempt(host: String, address: InetAddress, style: Style, ech: Boolean): Probe {
        val started = System.currentTimeMillis()
        val raw = FragSocket(style)
        var open: Socket = raw
        val ip = address.hostAddress.orEmpty()
        return try {
            raw.connect(InetSocketAddress(address, HTTPS_PORT), PROBE_TIMEOUT_MS)
            raw.soTimeout = PROBE_TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // Layered over the socket this connected, so the handshake goes out
            // through [FragStream] rather than through a socket of the factory's own.
            val tls = factory.createSocket(raw, host, HTTPS_PORT, true) as SSLSocket
            open = tls
            // Before the handshake or not at all. False here is reported rather
            // than swallowed: 「ECH：不通」 on a device with no ECH API would read
            // as ECH having been tried and failed.
            val armed = ech && Ech.apply(tls, host)
            // Conscrypt takes the name above as the SNI already. Saying it again
            // makes it something this depends on out loud rather than quietly.
            runCatching {
                tls.sslParameters = tls.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                }
            }
            tls.startHandshake()
            val version = tls.session.protocol
            tls.outputStream.write(
                ("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: ${Net.userAgent()}\r\n" +
                    "Accept: text/html\r\nConnection: close\r\n\r\n").toByteArray()
            )
            tls.outputStream.flush()
            val line = firstLine(tls.inputStream)
            val took = System.currentTimeMillis() - started
            if (line.isEmpty()) {
                Probe(false, took, "", "握手成功了，之后被断开", ip, note(raw, host, style, ech, armed))
            } else {
                Probe(true, took, "$version · $line", "", ip, note(raw, host, style, ech, armed))
            }
        } catch (e: Exception) {
            val took = System.currentTimeMillis() - started
            Probe(false, took, "", reason(e), ip, note(raw, host, style, ech, false))
        } finally {
            runCatching { open.close() }
        }
    }

    /**
     * Whether the split this attempt asked for actually happened - the one thing
     * the old 测试 could not say.
     *
     * 「分片：不通」 and 「对照：不通」 with the same reason on both lines reads as
     * 「拦的不是名字」. It reads exactly the same when the two runs were in fact
     * identical, because the TLS stack wrote to the file descriptor and never
     * touched the stream the split lives in - which is what happens on a device
     * whose Conscrypt ignores the flag this object's `init` sets. A wrong
     * conclusion is worse than a slow one, so this reports the fact instead of
     * letting the reader infer it.
     */
    private fun note(
        socket: FragSocket,
        host: String,
        style: Style,
        ech: Boolean,
        armed: Boolean
    ): String = when {
        ech && armed -> "ECH 生效：名字是加密发出去的"
        ech && !Ech.available -> "ECH 没生效：这台设备没有 ECH 接口（要 Android 17）"
        ech && Ech.configFor(host) == null -> "ECH 没生效：DNS 里没取到 ech= 公钥"
        ech -> "ECH 没生效：系统没接受这份配置"
        style == Style.NONE -> ""
        socket.split -> if (style == Style.RECORD) "分片生效（两个记录）" else "分片生效（两段）"
        socket.wrote -> "分片没生效：第一个写出去的不是 ClientHello"
        else -> "分片没生效：TLS 库没走这条流"
    }

    /**
     * What to print for a failure.
     *
     * A reset is the whole point of this feature and Java says it as
     * `SocketException: Connection reset`, which is worth naming: 「被 RST」 and
     * 「超时」 say two different things about the network and lead to two different
     * next moves.
     */
    private fun reason(e: Exception): String {
        val text = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return if (text.contains("reset", ignoreCase = true)) "连接被重置（RST）" else text
    }

    /** The status line, and nothing else - it is all this needs to report. */
    private fun firstLine(input: InputStream): String {
        val out = StringBuilder(64)
        while (out.length < 128) {
            val c = try {
                input.read()
            } catch (e: IOException) {
                return out.toString().trim()
            }
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) out.append(c.toChar())
        }
        return out.toString().trim()
    }

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private const val HANDSHAKE = 0x16
    private const val CLIENT_HELLO = 0x01
    private const val SERVER_NAME = 0x0000
    private const val HOST_NAME = 0x00
    private const val HTTPS_PORT = 443
    private const val PROBE_TIMEOUT_MS = 8_000
}

/**
 * A socket whose first array write is split the way [style] says.
 *
 * The style is fixed when the socket is made, not read again when it is written
 * to, so a pooled connection keeps what it was opened with and flipping the
 * switch can never cut a record in the middle of a live session.
 *
 * [wrote] and [split] are what [Frag.note] reports: between them they say whether
 * the TLS stack used this stream at all, and whether what it wrote was a
 * ClientHello worth cutting.
 */
class FragSocket(private val style: Frag.Style) : Socket() {

    private var stream: FragStream? = null

    /** Whether the TLS stack wrote anything through this socket's stream. */
    val wrote: Boolean get() = stream?.wrote == true

    /** Whether a write actually went out in two pieces. */
    val split: Boolean get() = stream?.split == true

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        super.connect(endpoint, timeout)
        // Two writes are two segments only while Nagle is off. OkHttp sets this
        // itself, but 测试 builds sockets directly, and this is where both meet.
        runCatching { tcpNoDelay = true }
    }

    @Synchronized
    override fun getOutputStream(): OutputStream {
        stream?.let { return it }
        return FragStream(super.getOutputStream(), style).also { stream = it }
    }
}

/**
 * Splits the first array write and passes everything after it straight through.
 *
 * Only the first: after the ClientHello the rest of the handshake and the whole
 * session go through here too, and cutting a record in mid-session would corrupt
 * it. [Frag.write] decides whether the buffer is one to cut at all.
 */
class FragStream(
    private val out: OutputStream,
    private val style: Frag.Style
) : OutputStream() {

    @Volatile
    var wrote = false
        private set

    @Volatile
    var split = false
        private set

    private var first = true

    override fun write(b: Int) {
        first = false
        wrote = true
        out.write(b)
    }

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!first || style == Frag.Style.NONE) {
            wrote = true
            out.write(b, off, len)
            return
        }
        first = false
        wrote = true
        split = Frag.write(out, b, off, len, style)
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}

/**
 * The factory [Net.client] builds its sockets through.
 *
 * One instance for the whole app, because OkHttp keys connection reuse on the
 * factory: a new one per request would mean a new pool every time.
 */
class FragSocketFactory : SocketFactory() {

    private fun open() = FragSocket(Frag.style)

    override fun createSocket(): Socket = open()

    override fun createSocket(host: String, port: Int): Socket =
        open().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        open().also {
            it.bind(InetSocketAddress(localHost, localPort))
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(address: InetAddress, port: Int): Socket =
        open().also { it.connect(InetSocketAddress(address, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = open().also {
        it.bind(InetSocketAddress(localAddress, localPort))
        it.connect(InetSocketAddress(address, port))
    }
}

/** The one the app uses. */
val SiteSockets: SocketFactory = FragSocketFactory()
