package StarBase.Android.Forum.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Everything the client knows about where the site lives. */
object Site {
    const val BASE = "https://linux.sb"

    const val LOGIN = "$BASE/login"
    const val REGISTER = "$BASE/register"
    const val NOTIFY = "$BASE/notify"
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
 * The user logs in inside a real WebView - that is where the site's captcha and
 * proof-of-work scripts run - and the resulting session cookie lands in
 * Android's [CookieManager]. Reading the jar from there means native requests
 * and the WebView share one session, and it survives process death for free
 * because the WebView persists its own cookies.
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

    /** True when the jar holds anything that looks like a live session. */
    fun hasSessionCookie(): Boolean {
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

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    fun userAgent(): String = UA

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

        resp.use {
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
            return text
        }
    }
}
