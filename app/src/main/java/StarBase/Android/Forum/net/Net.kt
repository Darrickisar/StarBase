package StarBase.Android.Forum.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Everything the client knows about where the site lives. */
object Site {
    const val BASE = "https://linux.sb"

    const val LOGIN = "$BASE/login"
    const val REGISTER = "$BASE/register"

    /**
     * Unread counters, as JSON. This is the badge endpoint only.
     *
     * Fetched as a *page* it answers 200 with a body reading 「用户不存在」 and no
     * notification rows, which is why the 通知 screen was empty: the list lives on
     * the profile tab ([userTab]), not here.
     */
    const val NOTIFY = "$BASE/notify"

    /** The site's own live badge poll. Answers `{"ok":1,"unread":N}`. */
    const val NOTIFY_BADGE = "$BASE/notification_live_badge_status"

    const val MESSAGES = "$BASE/direct_messages"
    const val GACHA = "$BASE/gacha"
    const val SEARCH = "$BASE/search"

    /**
     * 个人设置. The sidebar links it as `/profile`; there is no `/settings` on
     * this site, which is where the app's 「网站设置」 button used to send people.
     */
    const val PROFILE = "$BASE/profile"

    /** 发新帖. The site calls this `topic_edit`; `id=0` in the form means "new". */
    const val NEW_TOPIC = "$BASE/topic_edit"

    /** One private-message thread, keyed by the other person's user id. */
    fun conversation(partnerId: Int) = "$MESSAGES/$partnerId"

    /**
     * 点赞打赏 panel for a topic. Answers XHR with JSON holding the real form - the
     * opening post has no form on the page itself, unlike a comment.
     */
    fun donateModal(topicId: Int) = "$BASE/donate?topic_id=$topicId"

    fun forum(id: Int, page: Int = 1, sort: String = "") = buildString {
        append("$BASE/forum/$id")
        val q = mutableListOf<String>()
        if (sort.isNotBlank()) q += "sort=$sort"
        if (page > 1) q += "p=$page"
        if (q.isNotEmpty()) append("?").append(q.joinToString("&"))
    }

    fun topic(id: Int, page: Int = 1) =
        if (page > 1) "$BASE/topic/$id?p=$page" else "$BASE/topic/$id"

    fun user(id: Int) = "$BASE/user/$id"

    /**
     * One tab of a profile page, optionally paged.
     *
     * The site hangs 我的通知 and 我的积分 off here as `?tab=`, exactly like 主题 /
     * 回帖 / 收藏 - there is no separate address for either.
     */
    fun userTab(id: Int, tab: String, page: Int = 1) = buildString {
        append("$BASE/user/$id")
        if (tab.isNotBlank()) append("?tab=").append(tab)
        if (page > 1) append(if (tab.isNotBlank()) "&" else "?").append("p=").append(page)
    }

    fun loginWithRedirect(path: String) =
        "$LOGIN?redirect=" + java.net.URLEncoder.encode(path, "UTF-8")

    /** Turns a site-relative src/href into an absolute URL. */
    fun absolute(raw: String): String = when {
        raw.isBlank() -> ""
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$BASE$raw"
        else -> "$BASE/$raw"
    }
}

/**
 * Bridges OkHttp to the WebView's cookie store.
 *
 * Native login saves the site's cookies in Android's [CookieManager]. Native
 * requests and the remaining in-app WebView share that store, including its
 * persistence across process restarts.
 */
object WebViewCookieJar : CookieJar {

    private val manager: CookieManager get() = CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = runCatching { manager.getCookie(url.toString()) }.getOrNull()
        if (header.isNullOrBlank()) return emptyList()
        return header.split(';').mapNotNull { pair ->
            Cookie.parse(url, pair.trim())
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val target = url.toString()
        runCatching {
            cookies.forEach { manager.setCookie(target, it.toString()) }
            manager.flush()
        }
    }

    /** A cheap request pre-check only. Anonymous cookies do not prove authentication. */
    fun hasSiteCookies(): Boolean {
        val header = runCatching { manager.getCookie(Site.BASE) }.getOrNull().orEmpty()
        if (header.isBlank()) return false
        return header.split(';')
            .map { it.trim() }
            .any { it.contains('=') && it.substringAfter('=').isNotBlank() }
    }

    /** Drops every cookie for the site - used by 退出登录. */
    fun clear() {
        runCatching {
            manager.removeAllCookies(null)
            manager.flush()
        }
    }
}

/** Raised for anything the UI should show as a friendly message. */
class SiteException(message: String, val kind: Kind = Kind.SERVER) : Exception(message) {
    enum class Kind { NETWORK, AUTH, SERVER, PARSE }
}

object Net {

    /**
     * A desktop-class UA string. The site serves the same HTML either way, but a
     * plausible browser UA keeps the client from standing out to Cloudflare -
     * we are reading pages a browser would read, at browser-like volume.
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * The application context, for the one thing in this client that needs one.
     *
     * Only [CronetFallbackInterceptor] does: a Cronet engine is built against a
     * context. Set from `MainActivity.onCreate` before anything can make a request.
     * A null here is not fatal - the client is built without the QUIC fallback
     * rather than not at all, so a request from somewhere that never called this
     * still goes out over TCP.
     */
    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar)
            // 域名解析. [SiteDns] is the system resolver until the user turns DoH on
            // in 应用设置, and falls back to it whenever a DoH lookup comes up empty.
            .dns(SiteDns)
            // 分片. [SiteSockets] is an ordinary socket factory until the user turns
            // that on too - the half of 「打不开」 that DoH cannot reach, where the
            // address is right and the handshake is what gets cut. See [Frag].
            .socketFactory(SiteSockets)
            // Bound TLS setup separately from response reads; enable ECH when available.
            // SiteTls retains the platform socket type and default certificate validation.
            .apply {
                val trust = Ech.trustManager()
                if (trust != null) sslSocketFactory(SiteTls, trust)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // A network interceptor runs after connecting and before writing headers.
            // From this point a failed POST may already have reached the server.
            .addNetworkInterceptor { chain ->
                chain.request().tag(CronetAttempt::class.java)?.mayHaveSent = true
                chain.proceed(chain.request())
            }
            // QUIC/HTTP3 after TCP fails, with short-lived reuse of a working route. A network that
            // resets the TCP handshake on the name in the ClientHello often leaves
            // UDP/443 alone, which is the one mitigation here that does not depend
            // on guessing what a middlebox does with fragments. See [CronetFallback].
            .apply {
                appContext?.let { ctx ->
                    addInterceptor(
                        CronetFallbackInterceptor(
                            cookieJar = WebViewCookieJar,
                            dns = SiteDns,
                            context = ctx
                        )
                    )
                }
            }
            .build()
    }

    fun userAgent(): String = UA

    /** Same DoH/TLS transport as native pages; the public CAP service needs no session cookies. */
    internal fun capClient(context: android.content.Context): OkHttpClient = client.newBuilder()
        .proxy(java.net.Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply {
            interceptors().removeAll { it is CronetFallbackInterceptor }
            addInterceptor(CronetFallbackInterceptor(context = context.applicationContext, followRedirects = false))
        }.build()

    private fun base(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .header("Referer", Site.BASE + "/")

    /** GET returning the response body as text. Blocking - call from IO. */
    fun getText(url: String, ajax: Boolean = false): String {
        val req = base(url)
            .apply {
                if (ajax) {
                    header("X-Requested-With", "XMLHttpRequest")
                    header("Accept", "application/json, text/javascript, */*; q=0.01")
                } else {
                    header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                }
            }
            .get()
            .build()
        return execute(req)
    }

    /**
     * POST of an already-built body of any kind. Blocking - call from IO.
     *
     * Separate from [postForm] because some of the site's forms declare
     * `multipart/form-data` and posting those url-encoded is a different request
     * from the one the handler was written for.
     */
    fun postBody(url: String, body: okhttp3.RequestBody, ajax: Boolean = true): String {
        val req = base(url)
            .apply {
                if (ajax) header("X-Requested-With", "XMLHttpRequest")
                header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
                header("Origin", Site.BASE)
            }
            .post(body)
            .build()
        return execute(req)
    }

    /** Uploads can be cancelled while connecting, sending bytes or awaiting the answer. */
    suspend fun postBodyCancellable(url: String, body: okhttp3.RequestBody): String =
        suspendCancellableCoroutine { continuation ->
            val request = base(url).header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .header("Origin", Site.BASE).post(body).build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        SiteException("上传连接中断：${e.message.orEmpty()}", SiteException.Kind.NETWORK)
                    )
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    try {
                        val text = readResponse(response)
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    /** POST of an already-built form body. Blocking - call from IO. */
    fun postForm(url: String, body: okhttp3.FormBody, ajax: Boolean = true): String {
        val req = base(url)
            .apply {
                if (ajax) header("X-Requested-With", "XMLHttpRequest")
                header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
                header("Origin", Site.BASE)
            }
            .post(body)
            .build()
        return execute(req)
    }

    private fun execute(req: Request): String {
        val resp: Response = try {
            client.newCall(req).execute()
        } catch (e: java.net.UnknownHostException) {
            throw SiteException("连不上网络，请检查连接", SiteException.Kind.NETWORK)
        } catch (e: java.net.SocketTimeoutException) {
            throw SiteException("服务器响应超时，请重试", SiteException.Kind.NETWORK)
        } catch (e: java.io.IOException) {
            throw SiteException("网络请求失败：${e.message ?: "未知原因"}", SiteException.Kind.NETWORK)
        }

        return readResponse(resp)
    }

    private fun readResponse(resp: Response): String = resp.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: java.io.IOException) {
                throw SiteException("读取响应失败", SiteException.Kind.NETWORK)
            }

            if (it.code == 401 || it.code == 403) {
                throw SiteException("需要登录后才能查看", SiteException.Kind.AUTH)
            }
            if (it.code == 429) {
                throw SiteException("请求太频繁，稍后再试", SiteException.Kind.SERVER)
            }
            if (!it.isSuccessful) {
                throw SiteException("服务器返回 ${it.code}", SiteException.Kind.SERVER)
            }
            text
    }
}
