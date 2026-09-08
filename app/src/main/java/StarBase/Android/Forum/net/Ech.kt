package StarBase.Android.Forum.net

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ECH: the name goes out encrypted instead of going out in pieces.
 *
 * [Frag] hides the name by cutting it up, which is a bet about what one
 * particular middlebox does with the pieces. ECH does not bet - the real
 * `server_name` is sealed to a public key and the only name on the wire is the
 * operator's public one, so there is nothing left to recognise. That is the whole
 * of why a browser with secure DNS opens a site this app cannot: not DoH, ECH,
 * which the browser gated behind DoH.
 *
 * Three pieces have to line up and only one of them is ours:
 *
 * 1. **The key**, published in the site's `HTTPS` record as SvcParam 5. Cloudflare
 *    publishes one for `linux.sb` already, so nothing is needed from the operator.
 *    [Doh.httpsParam] reads it, and it is fetched **over DoH** rather than through
 *    the system resolver - a plaintext lookup can be stripped by the same box that
 *    is cutting the handshake, and ECH bootstrapped on a forgeable answer is
 *    theatre.
 * 2. **A TLS stack that can use it.** Android 17 (API 37) added
 *    `android.net.ssl.EchConfigList` and `SSLSockets.setEchConfigList`, which is
 *    exactly the shape needed here: the app supplies the config, so this waits on
 *    neither OkHttp adopting ECH nor the system's Private DNS being on. Below API
 *    37 there is no such call and [available] is false.
 * 3. **The switch**, `<domainEncryption>` in the network security config. It
 *    defaults to `disabled` for an app targeting below 37, so it is declared - in
 *    `res/xml-v37/`, because the tag does not exist to an older parser and an
 *    unknown tag in that file is a hard parse failure rather than a warning.
 *
 * Reached by reflection because `compileSdk` is 36 here; these are ordinary public
 * SDK members, not hidden ones. Both are **found by shape rather than by exact
 * signature** - their reference pages could not be read from the machine this was
 * written on, so a signature that does not match leaves [available] false instead
 * of crashing on somebody's phone.
 */
object Ech {

    /** `ech`, the SvcParam the config list travels in. RFC 9460 section 14.3.2. */
    const val PARAM_ECH = 5

    /** ECHConfig version 0xfe0d - the one RFC 9849 shipped. */
    const val VERSION = 0xFE0D

    /**
     * How long a fetched config is kept.
     *
     * Deliberately not the record's own TTL. A stale config is not a failure: a
     * server that has rotated its key answers with `retry_configs` and the
     * handshake is retried with the fresh one. Half an hour is about keeping the
     * lookup off the connection path, not about correctness.
     */
    const val TTL_MS = 30 * 60 * 1000L

    /** What the platform turned out to have, once it has been looked for. */
    private class Api(val make: Constructor<*>, val set: Method, val socketFirst: Boolean)

    private val api: Api? by lazy { discover() }

    /** Whether this device has the ECH API at all. False below Android 17. */
    val available: Boolean get() = api != null

    private class Hit(val config: ByteArray?, val until: Long)

    private val cache = ConcurrentHashMap<String, Hit>()

    /**
     * The config list for [host], or null when there is none to be had.
     *
     * Null is cached too. Without that, a host with no `HTTPS` record costs a DoH
     * round trip on every single connection - a real price for a feature that was
     * never going to work for it.
     */
    fun configFor(host: String): ByteArray? {
        val now = System.currentTimeMillis()
        cache[host]?.let { if (it.until > now) return it.config }
        val fetched = runCatching {
            Doh.httpsParam(Doh.fetch(SiteDns.server, Doh.query(host, Doh.TYPE_HTTPS)), PARAM_ECH)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        cache[host] = Hit(fetched, now + TTL_MS)
        return fetched
    }

    /**
     * Arms [socket] to encrypt [host] in its handshake. True when it was armed.
     *
     * False means the handshake goes out the way it would have anyway - an older
     * device, no published key, or a platform that would not take the config.
     * None of those is a reason to fail a connection.
     */
    fun apply(socket: SSLSocket, host: String): Boolean {
        val handles = api ?: return false
        val config = configFor(host) ?: return false
        return runCatching {
            val list = handles.make.newInstance(config)
            if (handles.socketFirst) handles.set.invoke(null, socket, list)
            else handles.set.invoke(null, list, socket)
            true
        }.getOrDefault(false)
    }

    /** Drops what is remembered, so the next connection looks the key up again. */
    fun forget() = cache.clear()

    /**
     * The public names inside a config list - what will be on the wire in place
     * of the real one.
     *
     * Only 测试 prints these. Reading them back proves the bytes really are a
     * config list rather than an empty parameter or something base64 mangled,
     * which is worth showing before anybody concludes ECH did not help.
     */
    fun publicNames(config: ByteArray): List<String> = try {
        names(config)
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun names(b: ByteArray): List<String> {
        if (b.size < 2) return emptyList()
        val end = minOf(2 + u16(b, 0), b.size)
        val found = ArrayList<String>()
        var i = 2
        while (i + 4 <= end) {
            val version = u16(b, i)
            val length = u16(b, i + 2)
            val body = i + 4
            if (body + length > end) break
            if (version == VERSION) publicName(b, body, body + length)?.let { found += it }
            i = body + length
        }
        return found
    }

    /**
     * Walks one ECHConfig to its `public_name`: the key config, then the length
     * cap, then the name. RFC 9849 section 4.
     */
    private fun publicName(b: ByteArray, start: Int, end: Int): String? {
        var i = start + 1 + 2                       // config_id, kem_id
        if (i + 2 > end) return null
        i += 2 + u16(b, i)                          // public_key
        if (i + 2 > end) return null
        i += 2 + u16(b, i)                          // cipher_suites
        i += 1                                      // maximum_name_length
        if (i + 1 > end) return null
        val size = b[i].toInt() and 0xFF
        val at = i + 1
        if (size == 0 || at + size > end) return null
        return String(b, at, size, Charsets.US_ASCII)
    }

    /**
     * The platform's own trust manager, which [Net] has to hand OkHttp alongside
     * a socket factory OkHttp did not build itself.
     *
     * The default one, deliberately. The point of the custom factory is one call
     * on the socket before the handshake; nothing about which certificates this
     * app accepts is supposed to change.
     */
    fun trustManager(): X509TrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
    }.getOrNull()

    private fun discover(): Api? = runCatching {
        val listClass = Class.forName("android.net.ssl.EchConfigList")
        val make = listClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == ByteArray::class.java
        } ?: return@runCatching null
        val set = Class.forName("android.net.ssl.SSLSockets").declaredMethods.firstOrNull {
            it.name == "setEchConfigList" && it.parameterTypes.size == 2 &&
                it.parameterTypes.any { p -> p.isAssignableFrom(SSLSocket::class.java) } &&
                it.parameterTypes.any { p -> p == listClass }
        } ?: return@runCatching null
        make.isAccessible = true
        Api(make, set, socketFirst = set.parameterTypes[0] != listClass)
    }.getOrNull()

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}

/**
 * The platform's TLS factory with one thing added: [Ech.apply] on every socket it
 * makes, before anybody can start a handshake on it.
 *
 * OkHttp only ever calls the layer-over-an-existing-socket overload, which is also
 * the one that knows the hostname. The rest are here because the class is abstract;
 * they delegate unarmed, since without a name there is nothing to look a key up by.
 */
class EchSocketFactory(
    private val inner: SSLSocketFactory,
    private val handshakeTimeoutMs: Int = 0,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = inner.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = inner.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        arm(inner.createSocket(s, host, port, autoClose), host, s.soTimeout)

    override fun createSocket(host: String, port: Int): Socket =
        arm(inner.createSocket(host, port), host)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = arm(inner.createSocket(host, port, localHost, localPort), host)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        inner.createSocket(host, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = inner.createSocket(address, port, localAddress, localPort)

    private fun arm(socket: Socket, host: String, readTimeoutMs: Int = socket.soTimeout): Socket {
        if (socket is SSLSocket) Ech.apply(socket, host)
        // OkHttp restores the caller's read timeout when it creates the HTTP codec after TLS.
        if (handshakeTimeoutMs > 0) {
            socket.soTimeout = if (readTimeoutMs == 0) handshakeTimeoutMs else minOf(readTimeoutMs, handshakeTimeoutMs)
        }
        return socket
    }
}

/** Platform TLS with a bounded handshake and ECH where supported. */
val SiteTls: SSLSocketFactory by lazy {
    EchSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory, handshakeTimeoutMs = 4_000)
}
