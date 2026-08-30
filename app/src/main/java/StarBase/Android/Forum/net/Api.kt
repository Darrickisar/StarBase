package StarBase.Android.Forum.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.Jsoup
import StarBase.Android.Forum.data.Board
import StarBase.Android.Forum.data.Conversation
import StarBase.Android.Forum.data.ForumPage
import StarBase.Android.Forum.data.GachaAction
import StarBase.Android.Forum.data.GachaPage
import StarBase.Android.Forum.data.GachaResult
import StarBase.Android.Forum.data.HomePage
import StarBase.Android.Forum.data.NotifyItem
import StarBase.Android.Forum.data.NotifyState
import StarBase.Android.Forum.data.Profile
import StarBase.Android.Forum.data.ProfileTab
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.data.TopicDetail

/**
 * The one place the app talks to linux.sb.
 *
 * Every call is a suspend function on [Dispatchers.IO] and either returns a
 * model or throws [SiteException] with a message fit for display. No caching
 * layer: screens fetch when opened and on pull-to-refresh, which is what
 * "live" means here.
 */
object Api {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun home(page: Int = 1): HomePage = io {
        val url = if (page > 1) "${Site.BASE}/?p=$page" else "${Site.BASE}/"
        Parse.home(Net.getText(url))
    }

    /**
     * One of the other home feeds - `/index.php?sort=post`, `/topic_featured`
     * and friends. They render the same page furniture as `/`, so this reuses
     * the home parser and the caller keeps the header data it already has.
     */
    suspend fun homeSorted(path: String, page: Int = 1): HomePage = io {
        val sep = if (path.contains('?')) "&" else "?"
        val url = Site.BASE + path + if (page > 1) "${sep}p=$page" else ""
        Parse.home(Net.getText(url))
    }

    suspend fun forum(id: Int, page: Int = 1, sort: String = ""): ForumPage = io {
        Parse.forum(id, page, Net.getText(Site.forum(id, page, sort)))
    }

    suspend fun topic(id: Int, page: Int = 1): TopicDetail = io {
        Parse.topic(id, page, Net.getText(Site.topic(id, page)))
    }

    /**
     * Runs a search the way the site's own page does. /search does **not** answer
     * a `?q=` GET with results: it renders a form carrying `_csrf` and a type
     * radio group, and it pages by POST. So read the form, then submit it. The
     * route is also gated - a visitor who is not signed in is handed
     * 「请登录后操作」 instead of the form.
     */
    suspend fun search(q: String, page: Int = 1): List<TopicCard> = io {
        val entry = Net.getText(Site.SEARCH)
        Parse.refusal(entry)?.let { throw it }
        val form = Parse.searchForm(entry)
            ?: throw SiteException("站点没有返回搜索表单", SiteException.Kind.PARSE)

        val values = LinkedHashMap(form.fields)
        values[form.queryField] = q
        if (page > 1 && form.pageField.isNotBlank()) values[form.pageField] = page.toString()

        val html = if (form.post) {
            val body = FormBody.Builder()
            values.forEach { (name, value) -> body.add(name, value) }
            Net.postForm(form.action, body.build(), ajax = false)
        } else {
            val query = values.entries.joinToString("&") { (name, value) ->
                URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            }
            Net.getText(form.action + (if (form.action.contains('?')) "&" else "?") + query)
        }
        Parse.refusal(html)?.let { throw it }
        Parse.feed(html)
    }

    /**
     * A profile page. The site switches 主题 / 回帖 / 收藏 with `?tab=`, and all
     * three render the same rows, so the tab is just part of the URL.
     */
    suspend fun profile(id: Int, tab: ProfileTab = ProfileTab.TOPICS): Profile = io {
        val url = if (tab == ProfileTab.TOPICS) Site.user(id) else "${Site.user(id)}?tab=${tab.key}"
        val html = Net.getText(url)
        Parse.refusal(html)?.let { throw it }
        Parse.profile(id, html)
    }

    /**
     * 称号馆. Signed out the site answers with 「请登录后操作」 or a redirect to
     * its login page, both of which come back as an AUTH failure rather than an
     * empty gallery.
     */
    suspend fun gacha(): GachaPage = io {
        val html = Net.getText(Site.GACHA)
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后查看称号馆", SiteException.Kind.AUTH)
        }
        Parse.gacha(html)
    }

    /**
     * Runs one of the gacha page's own buttons. The form was copied off the page
     * whole, `_csrf` included, so this only has to post it back.
     */
    suspend fun gachaPull(action: GachaAction): GachaResult = io {
        val body = FormBody.Builder()
        action.fields.forEach { (name, value) -> body.add(name, value) }
        val html = Net.postForm(action.action, body.build(), ajax = false)
        Parse.refusal(html)?.let { throw it }
        Parse.gachaResult(html)
    }

    suspend fun boards(): List<Board> = io {
        Parse.boards(Net.getText("${Site.BASE}/leaderboard"))
    }

    /**
     * Unread counters. `/notify` answers JSON and, for a guest, redirects to
     * /login - which we report as "not signed in" rather than an error.
     */
    suspend fun notifyState(): NotifyState = io {
        val body = Net.getText(Site.NOTIFY, ajax = true).trim()
        if (!body.startsWith("{")) return@io NotifyState(signedIn = false)
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return@io NotifyState(signedIn = false)
        }
        if (json.optString("redirect").contains("login")) {
            return@io NotifyState(signedIn = false)
        }
        NotifyState(
            notifications = json.optInt("count", json.optInt("notifications", 0)),
            messages = json.optInt("messages", json.optInt("dm", 0)),
            signedIn = true
        )
    }

    suspend fun notifications(): List<NotifyItem> = io {
        Parse.notifications(Net.getText(Site.NOTIFY))
    }

    suspend fun conversations(): List<Conversation> = io {
        Parse.conversations(Net.getText(Site.MESSAGES))
    }

    /** Reads the sidebar user card off the home page to learn who we are. */
    suspend fun me(): StarBase.Android.Forum.data.Me? = io {
        Parse.meOf(Jsoup.parse(Net.getText("${Site.BASE}/"), Site.BASE))
    }

    /**
     * Posts a reply to a topic.
     *
     * The token is re-read from the topic page immediately before posting rather
     * than reused from an older load, because a stale `_csrf` is the most common
     * way this fails.
     */
    suspend fun reply(topicId: Int, body: String, replyToId: String = ""): ReplyResult = io {
        val page = Net.getText(Site.topic(topicId))
        val doc = Jsoup.parse(page, Site.BASE)
        val csrf = Parse.csrfOf(doc)
        if (csrf.isBlank()) {
            throw SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)
        }

        val form = FormBody.Builder()
            .add("_csrf", csrf)
            .add("topic_id", topicId.toString())
            .add("content", body)
            .apply { if (replyToId.isNotBlank()) add("reply_id", replyToId) }
            .build()

        val resp = Net.postForm(Site.topic(topicId), form)
        val trimmed = resp.trim()

        // The site answers either JSON (ajax) or a redirect/HTML page.
        if (trimmed.startsWith("{")) {
            val json = try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                return@io ReplyResult(true, "")
            }
            val ok = json.optInt("ok", if (json.optBoolean("success", false)) 1 else 0) == 1
            val message = json.optString("message").ifBlank {
                json.optString("msg")
            }
            if (!ok) {
                throw SiteException(message.ifBlank { "回复失败，请稍后再试" }, SiteException.Kind.SERVER)
            }
            return@io ReplyResult(true, message)
        }

        // HTML came back: treat the presence of our text on the page as success.
        val posted = Jsoup.parse(trimmed, Site.BASE)
            .select(".post-content")
            .any { it.text().contains(body.take(24)) }
        ReplyResult(posted, if (posted) "" else "已提交，但未能确认结果")
    }

    data class ReplyResult(val ok: Boolean, val message: String)
}
