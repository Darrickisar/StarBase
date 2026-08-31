package StarBase.Android.Forum.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import StarBase.Android.Forum.data.AccountSettings
import StarBase.Android.Forum.data.Board
import StarBase.Android.Forum.data.Conversation
import StarBase.Android.Forum.data.FavoriteMark
import StarBase.Android.Forum.data.ForumPage
import StarBase.Android.Forum.data.GachaAction
import StarBase.Android.Forum.data.GachaPage
import StarBase.Android.Forum.data.GachaResult
import StarBase.Android.Forum.data.HomePage
import StarBase.Android.Forum.data.NotifyItem
import StarBase.Android.Forum.data.NotifyState
import StarBase.Android.Forum.data.Post
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

    /**
     * Reads what a write endpoint answered.
     *
     * The site's own scripts post these forms as XHR and treat the reply as JSON
     * unconditionally: `data.ok` decides, `data.message` is shown, `data.html` is
     * the row to append, `data.redirect` means the session is gone. Anything that
     * is not JSON therefore means the request never reached the handler - which is
     * exactly how 回帖 used to fail while reporting 「已提交，但未能确认结果」. So a
     * non-JSON body is an error here, never a maybe.
     */
    private fun writeResult(raw: String, whatFailed: String): Parse.WriteResult {
        val trimmed = raw.trim()

        if (!trimmed.startsWith("{")) {
            // A refusal panel or the login page says why; otherwise this is the
            // site rendering an ordinary page, meaning we posted to the wrong
            // place or left out a field the handler requires.
            Parse.refusal(trimmed)?.let { throw it }
            if (Parse.isLoginPage(trimmed)) {
                throw SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)
            }
            throw SiteException(
                "$whatFailed：站点没有按预期回应，可能是版面改版了",
                SiteException.Kind.PARSE
            )
        }

        val json = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw SiteException("$whatFailed：无法读取站点的回应", SiteException.Kind.PARSE)
        }

        val redirect = json.optString("redirect")
        // The site redirects an expired session to /login instead of saying so.
        if (redirect.contains("login")) {
            throw SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)
        }

        val message = json.optString("message")
            .ifBlank { json.optString("msg") }
            .ifBlank { json.optString("tip") }
        val ok = json.optBoolean("ok", json.optInt("ok", 0) == 1) ||
            json.optBoolean("success", false)

        if (!ok) throw SiteException(message.ifBlank { whatFailed }, SiteException.Kind.SERVER)

        return Parse.WriteResult(
            ok = true,
            message = message,
            html = json.optString("html"),
            redirect = redirect
        )
    }

    /**
     * Posts a form that was read off a page, with [values] written over it.
     *
     * This is the shape of every write on this site. Field names come from the
     * markup, never from here.
     */
    private fun submit(
        form: Parse.SiteForm,
        values: Map<String, String>,
        whatFailed: String
    ): Parse.WriteResult = writeResult(post(form, values, ajax = true), whatFailed)

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
     * Flips 收藏 on a topic and reports the state the site came back with.
     *
     * There is no local bookmark list behind this - 收藏 is the site's own, and
     * this posts the form the topic page renders. That form declares
     * `data-no-ajax="1"`, so the answer is a rendered page rather than JSON:
     * the new state is read out of the page the site returns, and only if that
     * page carries no 收藏 form at all is the topic re-read to find it.
     *
     * Returns null when the page has no such form - a guest, or a topic the site
     * does not offer it on.
     */
    suspend fun toggleFavorite(topicId: Int): FavoriteMark? = io {
        val page = Net.getText(Site.topic(topicId))
        val form = Parse.favoriteForm(Jsoup.parse(page, Site.BASE)) ?: return@io null

        if (form.turnstile) {
            throw NeedsBrowser(Site.topic(topicId), "这个操作需要人机验证，用网页方式收藏")
        }

        val answer = post(form, emptyMap(), ajax = false)
        Parse.refusal(answer)?.let { throw it }
        if (Parse.isLoginPage(answer)) {
            throw SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)
        }

        Parse.favoriteMark(Jsoup.parse(answer, Site.BASE))
            ?: Parse.favoriteMark(Jsoup.parse(Net.getText(Site.topic(topicId)), Site.BASE))
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

    /**
     * One 榜单. Each is its own page, so switching tabs is a fetch, not a filter
     * over something already in hand - [type] is the site's own query value and
     * an empty one asks for the default (富豪榜).
     */
    suspend fun board(type: String = ""): Board = io {
        val url = if (type.isBlank()) {
            "${Site.BASE}/leaderboard"
        } else {
            "${Site.BASE}/leaderboard?type=$type"
        }
        Parse.board(Net.getText(url))
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
        val html = Net.getText(Site.MESSAGES)
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后查看私信", SiteException.Kind.AUTH)
        }
        Parse.conversations(html)
    }

    /**
     * Finds people to start a conversation with.
     *
     * The site's own 私信 page searches by GET, and every hit links at the thread
     * URL - so there is no "create conversation" call to make: opening the thread
     * for someone you have never written to is the new conversation.
     */
    suspend fun findUsers(query: String): List<Parse.UserHit> = io {
        val q = query.trim()
        if (q.isBlank()) return@io emptyList()
        val url = "${Site.BASE}/index.php?a=direct_messages&q=" +
            java.net.URLEncoder.encode(q, "UTF-8")
        val html = Net.getText(url)
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后搜索用户", SiteException.Kind.AUTH)
        }
        Parse.userSearch(html)
    }

    /** One private-message thread, keyed by the other person's user id. */
    suspend fun thread(partnerId: Int): Parse.Thread = io {
        val html = Net.getText(Site.conversation(partnerId))
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后查看私信", SiteException.Kind.AUTH)
        }
        Parse.thread(partnerId, html)
    }

    /** Reads the sidebar user card off the home page to learn who we are. */
    suspend fun me(): StarBase.Android.Forum.data.Me? = io {
        Parse.meOf(Jsoup.parse(Net.getText("${Site.BASE}/"), Site.BASE))
    }

    /**
     * Raised when a form can only be submitted by a browser.
     *
     * A Cloudflare Turnstile token is minted by JS against the page it was served
     * on, so there is nothing native code can put in that field. The screen
     * answers this by handing the same job to a WebView rather than by failing.
     */
    class NeedsBrowser(val url: String, message: String) : Exception(message)

    /**
     * Posts a reply to a topic.
     *
     * Reads the topic page for its reply form and posts that form back with the
     * body filled in. The form is re-read immediately before posting - a stale
     * `_csrf` is the most common way this fails - and every other field the site
     * put there goes back untouched.
     *
     * Quoting a floor is not a field: the site's own script reads the parent floor
     * out of the body text, looking for `@某人 #12` at the top (see
     * [Parse.quotePrefix]), so [quoting] just prefixes the body.
     */
    suspend fun reply(topicId: Int, body: String, quoting: Post? = null): Parse.WriteResult = io {
        val doc = Jsoup.parse(Net.getText(Site.topic(topicId)), Site.BASE)

        val form = Parse.replyForm(doc)
            ?: throw replyUnavailable(doc, topicId)

        if (form.turnstile) {
            throw NeedsBrowser(Site.topic(topicId), "这个帖子的回复需要人机验证，用网页方式回复")
        }

        val field = form.bodyField
        if (field.isBlank()) {
            throw SiteException("找不到回复输入框，可能是版面改版了", SiteException.Kind.PARSE)
        }

        val text = Parse.quotePrefix(quoting) + body.trim()
        submit(form, mapOf(field to text), "回复失败")
    }

    /**
     * Uploads one attachment and returns the markdown that references it.
     *
     * Attachments are not a field on the reply or topic form - they are their own
     * endpoint, and the site's own script posts the file, takes `markdown` out of
     * the answer and appends that to the textarea. So this does the same: the
     * caller puts the returned string in the body it is about to submit.
     *
     * [uploader] carries the endpoint and the size cap read off the page, so the
     * limit is the site's rather than one written down here.
     */
    suspend fun uploadAttachment(
        uploader: Parse.Uploader,
        fileName: String,
        mediaType: String,
        bytes: ByteArray
    ): String = io {
        val capBytes = uploader.maxMb * 1024L * 1024L
        if (uploader.maxMb > 0 && bytes.size > capBytes) {
            throw SiteException(
                "附件超过 ${uploader.maxMb} MB，站点不收",
                SiteException.Kind.SERVER
            )
        }
        if (uploader.accepts.isNotEmpty()) {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext.isBlank() || uploader.accepts.none { it.equals(".$ext", true) }) {
                throw SiteException(
                    "站点只收 ${uploader.accepts.joinToString(" ")}",
                    SiteException.Kind.SERVER
                )
            }
        }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("_csrf", uploader.csrf)
            .addFormDataPart(
                "attachment",
                fileName,
                bytes.toRequestBody(mediaType.toMediaTypeOrNull())
            )
            .build()

        val raw = Net.postBody(uploader.action, body).trim()
        if (!raw.startsWith("{")) {
            Parse.refusal(raw)?.let { throw it }
            throw SiteException("上传失败：站点没有按预期回应", SiteException.Kind.PARSE)
        }
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw SiteException("上传失败：无法读取站点的回应", SiteException.Kind.PARSE)
        }
        if (!json.optBoolean("ok", json.optInt("ok", 0) == 1)) {
            throw SiteException(
                json.optString("message").ifBlank { "上传失败" },
                SiteException.Kind.SERVER
            )
        }
        json.optString("markdown").ifBlank {
            throw SiteException("站点没有返回附件引用", SiteException.Kind.PARSE)
        }
    }

    /** The attachment uploader on a topic page's reply form, or null when absent. */
    suspend fun replyUploader(topicId: Int): Parse.Uploader? = io {
        Parse.uploaderOf(Jsoup.parse(Net.getText(Site.topic(topicId)), Site.BASE))
    }

    /** The attachment uploader on the 发帖 form. */
    suspend fun newTopicUploader(): Parse.Uploader? = io {
        Parse.uploaderOf(Jsoup.parse(Net.getText(Site.NEW_TOPIC), Site.NEW_TOPIC))
    }

    /**
     * The boards a new topic can go to, read off the 发帖 page itself rather than
     * from the board list - only the ones this form offers can actually be posted
     * to.
     */
    suspend fun newTopicBoards(): List<Pair<Int, String>> = io {
        val html = Net.getText(Site.NEW_TOPIC)
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后发帖", SiteException.Kind.AUTH)
        }
        Parse.boardOptions(Jsoup.parse(html, Site.NEW_TOPIC))
    }

    /**
     * Posts a new topic.
     *
     * `topic_special_type` is set explicitly to "" - 普通帖. The site renders that
     * radio group with only `lottery` and `virtual_card` in it and neither checked,
     * adding 普通帖 from its own script, so leaving the field out risks the handler
     * reading a special type we never asked for.
     */
    suspend fun newTopic(forumId: Int, title: String, body: String): Parse.WriteResult = io {
        val html = Net.getText(Site.NEW_TOPIC)
        Parse.refusal(html)?.let { throw it }
        // Parsed against its own URL, not the site root: this form carries no
        // action, so where it posts is the address it was served from.
        val doc = Jsoup.parse(html, Site.NEW_TOPIC)
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后发帖", SiteException.Kind.AUTH)
        }

        val form = Parse.newTopicForm(doc)
            ?: throw NeedsBrowser(Site.NEW_TOPIC, "找不到发帖表单，用网页方式发布")
        if (form.turnstile) {
            throw NeedsBrowser(Site.NEW_TOPIC, "发帖需要人机验证，用网页方式发布")
        }

        val bodyField = form.bodyField
        if (bodyField.isBlank()) {
            throw SiteException("找不到正文输入框，可能是版面改版了", SiteException.Kind.PARSE)
        }

        submit(
            form,
            mapOf(
                "forum_id" to forumId.toString(),
                "title" to title.trim(),
                bodyField to body.trim(),
                "topic_special_type" to ""
            ),
            "发布失败"
        )
    }

    /**
     * 点赞 one comment, optionally with points.
     *
     * [points] is 0 for a plain like and otherwise one of the tiers the site
     * offers. The field is not in the markup - the site's own script adds it at
     * submit time - so it is named here, from the one place that knows it.
     */
    suspend fun like(
        topicId: Int,
        replyId: Int,
        points: Int = 0,
        page: Int = 1
    ): Parse.WriteResult = io {
        // The form lives on the page the comment is on, so a comment on page 3 is
        // not on page 1 - the caller passes the page it is reading.
        val doc = Jsoup.parse(Net.getText(Site.topic(topicId, page)), Site.topic(topicId, page))

        val form = Parse.donateForm(doc, replyId)
            ?: if (Parse.csrfOf(doc).isBlank()) {
                throw SiteException("请登录后点赞", SiteException.Kind.AUTH)
            } else {
                throw SiteException("这条评论上没有点赞入口", SiteException.Kind.PARSE)
            }

        submit(
            form,
            mapOf("donate_reaction_points" to points.coerceAtLeast(0).toString()),
            if (points > 0) "投币失败" else "点赞失败"
        )
    }

    /**
     * 点赞打赏 the topic itself.
     *
     * The opening post does not work like a comment. There is no form on the page -
     * just a link to `/donate?topic_id=…` - and that endpoint answers XHR with a
     * modal whose HTML holds the real form, carrying a one-shot `request_key`. So
     * this is two requests, and the key cannot be cached: it belongs to the modal
     * that was just handed out.
     *
     * [amount] is 0 for a plain 点赞, otherwise one of the presets the modal offers.
     */
    suspend fun likeTopic(topicId: Int, amount: Int = 0): Parse.WriteResult = io {
        val raw = Net.getText(Site.donateModal(topicId), ajax = true).trim()
        if (!raw.startsWith("{")) {
            Parse.refusal(raw)?.let { throw it }
            if (Parse.isLoginPage(raw)) {
                throw SiteException("请登录后点赞", SiteException.Kind.AUTH)
            }
            throw SiteException("站点没有返回打赏面板", SiteException.Kind.PARSE)
        }

        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw SiteException("无法读取打赏面板", SiteException.Kind.PARSE)
        }
        if (json.optString("redirect").contains("login")) {
            throw SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)
        }
        if (!json.optBoolean("ok", json.optInt("ok", 0) == 1)) {
            throw SiteException(
                json.optString("message").ifBlank { "打赏面板加载失败" },
                SiteException.Kind.SERVER
            )
        }

        val html = json.optJSONObject("modal")?.optString("html").orEmpty()
        if (html.isBlank()) throw SiteException("打赏面板是空的", SiteException.Kind.PARSE)

        val form = Parse.donateTopicForm(html)
            ?: throw SiteException("打赏面板里没有表单", SiteException.Kind.PARSE)

        // `amount` is the modal's own optional field: absent means a plain 点赞.
        val values = if (amount > 0) mapOf("amount" to amount.toString()) else mapOf("amount" to "")
        submit(form, values, if (amount > 0) "打赏失败" else "点赞失败")
    }

    /** The 打赏 amounts the site offers on this topic, plus what it says about it. */
    suspend fun donateOptions(topicId: Int): Parse.DonatePanel? = io {
        val raw = Net.getText(Site.donateModal(topicId), ajax = true).trim()
        if (!raw.startsWith("{")) return@io null
        val html = runCatching { JSONObject(raw).optJSONObject("modal")?.optString("html") }
            .getOrNull().orEmpty()
        if (html.isBlank()) null else Parse.donatePanel(html)
    }

    /** What the site currently says about one comment's 点赞 state. */
    suspend fun reaction(topicId: Int, replyId: Int): Parse.Reaction? = io {
        Parse.reactionOf(Jsoup.parse(Net.getText(Site.topic(topicId)), Site.BASE), replyId)
    }

    /**
     * Sends a private message in an existing conversation.
     *
     * The body field here is `content`, not `body` - it is read off the form, like
     * every other field, because the site is not consistent between its own forms.
     */
    suspend fun sendMessage(partnerId: Int, content: String): Parse.WriteResult = io {
        val url = Site.conversation(partnerId)
        val html = Net.getText(url)
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后发送私信", SiteException.Kind.AUTH)
        }

        val form = Parse.dmComposeForm(Jsoup.parse(html, Site.BASE))
            ?: throw SiteException("这个会话不能发送私信", SiteException.Kind.SERVER)

        val field = form.bodyField
        if (field.isBlank()) {
            throw SiteException("找不到私信输入框，可能是版面改版了", SiteException.Kind.PARSE)
        }

        submit(form, mapOf(field to content.trim()), "私信发送失败")
    }

    /**
     * Why the reply form is missing. The panel says which of the three it is, and
     * they need different answers from the user, so they get different messages.
     */
    private fun replyUnavailable(doc: org.jsoup.nodes.Document, topicId: Int): Exception {
        val panel = doc.selectFirst(".reply-panel")
        return when {
            panel?.selectFirst(".topic-management-reply-disabled") != null ->
                SiteException("管理员已关闭本帖回复", SiteException.Kind.SERVER)

            panel?.selectFirst(".reply-login-box") != null || Parse.csrfOf(doc).isBlank() ->
                SiteException("请登录后回复", SiteException.Kind.AUTH)

            // The panel is there but carries no form we recognise - the site
            // changed its markup, and a WebView can still do the job.
            else -> NeedsBrowser(Site.topic(topicId), "找不到回复表单，用网页方式回复")
        }
    }

    // ---- 个人设置 ------------------------------------------------------------

    /**
     * Reads the answer to a form the site posts as an ordinary page load.
     *
     * /profile, /username_change and /user_review_email_change are not ajax
     * endpoints - this site has no global submit hijack, only per-widget
     * handlers - so [writeResult]'s JSON contract does not apply to them. What
     * comes back is the settings page again, which offers the strongest
     * confirmation there is: the state that is now stored. A refusal renders the
     * site's 「消息」 panel instead, and that is an error.
     */
    private fun settingsOf(html: String, whatFailed: String): AccountSettings {
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)
        }
        return Parse.accountSettings(html) ?: throw SiteException(
            "$whatFailed：站点没有按预期回应，可能是版面改版了",
            SiteException.Kind.PARSE
        )
    }

    /** The settings page as a document, with refusals and guests already thrown. */
    private fun settingsDoc(): org.jsoup.nodes.Document {
        val html = Net.getText(Site.PROFILE)
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("请登录后修改个人设置", SiteException.Kind.AUTH)
        }
        return Jsoup.parse(html, Site.PROFILE)
    }
    /**
     * Fails loudly when a field we are about to write is not on the form.
     *
     * The standing rule here is that field names come from the markup. These
     * sections have to name a few anyway - only the caller knows which value is
     * the new 简介 and which is the new 密码 - so the compromise is to check
     * first: a renamed field stops the write, instead of posting to a handler
     * that ignores it and answers 200.
     */
    private fun requireFields(form: Parse.SiteForm, names: Collection<String>, what: String) {
        val missing = names.filterNot { form.fields.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw SiteException(
                "$what 的表单少了 ${missing.joinToString("、")}，可能是版面改版了",
                SiteException.Kind.PARSE
            )
        }
    }

    /** Posts a form read off a page and hands back the raw body. */
    private fun post(form: Parse.SiteForm, values: Map<String, String>, ajax: Boolean): String {
        val fields = form.with(*values.toList().toTypedArray())
        val body: okhttp3.RequestBody = if (form.multipart) {
            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (name, value) -> addFormDataPart(name, value) }
            }.build()
        } else {
            FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()
        }
        return Net.postBody(form.action, body, ajax = ajax)
    }

    suspend fun accountSettings(): AccountSettings = io {
        settingsOf(Net.getText(Site.PROFILE), "读取个人设置失败")
    }
    /**
     * Saves 简介 / 头像样式 / 密码.
     *
     * All three share one form and one 保存 button on the site, so a save that
     * only touches 简介 posts the avatar fields and the password pair back as
     * well. A null argument means "leave what the page had", which is exactly
     * what submitting the untouched form does - so nothing here has to know the
     * current value.
     */
    suspend fun saveProfile(
        bio: String? = null,
        avatarStyle: String? = null,
        avatarSeed: String? = null,
        password: String? = null
    ): AccountSettings = io {
        val form = Parse.profileForm(settingsDoc())
            ?: throw SiteException("找不到个人设置表单，可能是版面改版了", SiteException.Kind.PARSE)

        val values = LinkedHashMap<String, String>()
        bio?.let { values["bio"] = it }
        avatarStyle?.let { values["avatar_style"] = it }
        avatarSeed?.let { values["avatar_seed"] = it }
        // The site validates the pair and refuses a mismatch, so both halves
        // carry the same value rather than being asked for twice.
        password?.let {
            values["password"] = it
            values["password2"] = it
        }
        requireFields(form, values.keys, "个人设置")

        settingsOf(post(form, values, ajax = false), "保存失败")
    }

    /**
     * 改名. It costs points, so the site asks for the current password too.
     *
     * The form this posts holds nothing but its `_csrf`: both fields are attached
     * from outside by the HTML5 `form=` attribute, which is why [Parse.formOf]
     * had to learn about that attribute before this could work at all.
     */
    suspend fun changeUsername(newName: String, currentPassword: String): AccountSettings = io {
        val form = Parse.usernameForm(settingsDoc())
            ?: throw SiteException("找不到改名表单，可能是版面改版了", SiteException.Kind.PARSE)
        val values = mapOf("new_username" to newName.trim(), "current_password" to currentPassword)
        requireFields(form, values.keys, "改名")
        settingsOf(post(form, values, ajax = false), "改名失败")
    }
    /**
     * Asks for the code 修改邮箱 needs.
     *
     * Its own endpoint, and the one part of 个人设置 that really does answer JSON -
     * the site's script posts it with fetch and reads `ok` / `message`.
     */
    suspend fun sendEmailCode(email: String): String = io {
        val doc = settingsDoc()
        val url = Parse.emailCodeUrl(doc)
        if (url.isBlank()) {
            throw NeedsBrowser(Site.PROFILE, "发送验证码需要人机验证，用网页方式修改邮箱")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("_csrf", Parse.csrfOf(doc))
            .addFormDataPart("email", email.trim())
            .build()
        writeResult(Net.postBody(url, body), "发送验证码失败").message.ifBlank { "验证码已发送" }
    }

    suspend fun changeEmail(email: String, code: String): AccountSettings = io {
        val form = Parse.emailForm(settingsDoc())
            ?: throw SiteException("找不到修改邮箱表单，可能是版面改版了", SiteException.Kind.PARSE)
        val values = mapOf("email" to email.trim(), "email_code" to code.trim())
        requireFields(form, values.keys, "修改邮箱")
        settingsOf(post(form, values, ajax = false), "修改邮箱失败")
    }

    /**
     * Switches to one of the site's 预置头像.
     *
     * Not a form: the panel carries the endpoint and a loose `_csrf`, and the
     * site's script builds the body by hand - so these two field names come from
     * that script rather than from any markup. It answers JSON and then reloads
     * the page, so this re-reads it for the state that came of it.
     */
    suspend fun pickAvatarPreset(seed: String): AccountSettings = io {
        val target = Parse.avatarUpload(settingsDoc())
            ?: throw SiteException("找不到头像上传入口，可能是版面改版了", SiteException.Kind.PARSE)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("_csrf", target.csrf)
            .addFormDataPart("avatar_upload_action", "preset")
            .addFormDataPart("avatar_seed", seed)
            .build()
        writeResult(Net.postBody(target.url, body), "更换头像失败")
        settingsOf(Net.getText(Site.PROFILE), "更换头像失败")
    }

    /**
     * Uploads a 头像.
     *
     * [jpeg] has to be the square the site stores already - its own page crops to
     * 200×200 in a canvas before posting and the handler is written for what that
     * produces. Scaling is the caller's job; this only carries the bytes.
     */
    suspend fun uploadAvatar(jpeg: ByteArray): AccountSettings = io {
        val target = Parse.avatarUpload(settingsDoc())
            ?: throw SiteException("找不到头像上传入口，可能是版面改版了", SiteException.Kind.PARSE)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("_csrf", target.csrf)
            .addFormDataPart("avatar_upload_action", "upload")
            .addFormDataPart(
                "avatar",
                "avatar.jpg",
                jpeg.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
            .build()
        writeResult(Net.postBody(target.url, body), "上传头像失败")
        settingsOf(Net.getText(Site.PROFILE), "上传头像失败")
    }
}
