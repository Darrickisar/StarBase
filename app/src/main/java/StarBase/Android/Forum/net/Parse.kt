package StarBase.Android.Forum.net

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import StarBase.Android.Forum.data.AccountSettings
import StarBase.Android.Forum.data.AvatarPreset
import StarBase.Android.Forum.data.Board
import StarBase.Android.Forum.data.BoardTab
import StarBase.Android.Forum.data.Conversation
import StarBase.Android.Forum.data.DirectMessage
import StarBase.Android.Forum.data.ForumPage
import StarBase.Android.Forum.data.ForumRef
import StarBase.Android.Forum.data.GachaAction
import StarBase.Android.Forum.data.GachaPage
import StarBase.Android.Forum.data.GachaRarity
import StarBase.Android.Forum.data.GachaResult
import StarBase.Android.Forum.data.GachaTitle
import StarBase.Android.Forum.data.HomePage
import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.data.Me
import StarBase.Android.Forum.data.NotifyItem
import StarBase.Android.Forum.data.OAuthBinding
import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.data.Profile
import StarBase.Android.Forum.data.RankRowData
import StarBase.Android.Forum.data.SiteStats
import StarBase.Android.Forum.data.Title
import StarBase.Android.Forum.data.TopicCard
import StarBase.Android.Forum.data.TopicDetail

/**
 * Jsoup parsers for linux.sb's server-rendered HTML.
 *
 * The site has no JSON API, so every screen is built by reading the same markup
 * a browser renders. Selectors are written against classes the site's own CSS
 * depends on (`post-item`, `post-content`, `post-meta`, ...) because those are
 * the least likely to be renamed casually. Anything missing degrades to a blank
 * field rather than throwing - a redesigned sidebar should not blank the feed.
 */
object Parse {

    // ---- small helpers -------------------------------------------------------

    private val digits = Regex("""\d[\d,]*""")

    private fun Element.attrUrl(name: String): String =
        Site.absolute(attr(name).trim())

    /** Trailing integer of an href like /topic/17842 or /user/23618?tab=x */
    private fun idFrom(href: String, segment: String): Int {
        val m = Regex("""/$segment/(\d+)""").find(href) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    private fun firstInt(s: String): Int =
        digits.find(s)?.value?.replace(",", "")?.toIntOrNull() ?: 0

    /**
     * The number attached to a unit, e.g. 58 from "近 24 小时 58 回复".
     * Falls back to the last number in the string, which is where counts sit.
     */
    private fun countBefore(s: String, unit: String): Int {
        Regex("""(\d[\d,]*)\s*${Regex.escape(unit)}""").find(s)?.let {
            return it.groupValues[1].replace(",", "").toIntOrNull() ?: 0
        }
        return digits.findAll(s).lastOrNull()?.value?.replace(",", "")?.toIntOrNull() ?: 0
    }

    private fun Element.textOf(css: String): String =
        selectFirst(css)?.text()?.trim().orEmpty()

    /** The site treats only 1, 2, 3 … as a floor; anything else is not a reference. */
    private val floorText = Regex("""^[1-9]\d*$""")

    /** A hand-written quote leaves "@某人 #12" at the top of the body. */
    private val mentionedFloor = Regex("""@[^\s#]+\s+#([1-9]\d*)""")

    /**
     * The quote line to put in front of a reply body, or "" for a plain reply.
     *
     * Quoting is not a form field on this site: the reply handler reads the parent
     * floor back out of the body text, and [mentionedFloor] is the shape it looks
     * for. Writing it here rather than in the UI keeps the two directions in one
     * file, so a change to the pattern cannot break only one of them.
     *
     * The author's name goes in verbatim except for whitespace, which would split
     * the mention, and a '#', which would move where the floor appears to start.
     */
    fun quotePrefix(post: Post?): String {
        if (post == null || post.floor <= 0) return ""
        val name = post.author.replace(Regex("""[\s#]+"""), "").ifBlank { "楼主" }
        return "@$name #${post.floor}\n"
    }

    private val postAnchor = Regex("""^post-([1-9]\d*)$""")

    private fun validFloor(value: String?): Int =
        value?.trim()?.takeIf(floorText::matches)?.toIntOrNull() ?: 0

    private fun queryParam(url: String, name: String): String {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return ""
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == name) return part.substring(eq + 1)
        }
        return ""
    }

    /**
     * The gacha badge, if this scope carries one.
     *
     * The site renders the same badge under two class names: `.gacha-title-post-badge`
     * on a post header, and a plain `.gacha-title-badge` inside `.gacha-sidebar-title`
     * on the sidebar card. Matching only the first is what glued 「SSR伪装者」 into
     * the sidebar's rank line - the badge went unrecognised, so its text stayed
     * behind in the leftover read.
     */
    private fun gachaOf(scope: Element): Title? {
        val badge = scope.selectFirst(
            "a.gacha-title-post-badge, .gacha-title-post-badge, " +
                ".gacha-sidebar-title .gacha-title-badge, .gacha-title-badge"
        ) ?: return null
        val name = badge.textOf(".gacha-title-name").ifBlank {
            // A badge with no inner spans is just its own text.
            badge.text().trim().takeIf { badge.selectFirst("span") == null }.orEmpty()
        }
        if (name.isBlank()) return null
        // The rarity slot holds the tier for a normal title and a bare serial
        // number for a UR - the same quirk the 称号馆 parser handles.
        val rarity = badge.textOf(".gacha-title-rarity")
        return Title(
            name = name,
            serial = badge.textOf(".gacha-title-serial").ifBlank {
                rarity.takeIf { it.isNotBlank() && it.all(Char::isDigit) }.orEmpty()
            },
            // Tier rides along as a class: gacha-title-ur / -ssr / -sr / -r / -n
            tier = tierOf(badge).ifBlank {
                rarity.takeUnless { it.isBlank() || it.all(Char::isDigit) }.orEmpty()
            }
        )
    }

    // ---- topic rows ----------------------------------------------------------

    /**
     * One `li.post-item` from a home feed, board page, search result or profile.
     * Returns null for rows that carry no topic link (ads, separators).
     */
    private fun topicCard(li: Element): TopicCard? {
        val link = li.selectFirst("a.post-title") ?: return null
        val id = idFrom(link.attr("href"), "topic")
        if (id == 0) return null

        val authorLink = li.selectFirst(".post-avatar a.avatar-profile-link")
            ?: li.selectFirst(".post-meta a[href*=/user/]")
        val authorId = idFrom(authorLink?.attr("href").orEmpty(), "user")
        val avatarEl = li.selectFirst(".post-avatar img.avatar-img")
        val author = avatarEl?.attr("alt")?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: li.selectFirst(".post-meta a[href*=/user/]")?.text()?.trim().orEmpty()

        val forumLink = li.selectFirst("a.post-forum-badge")
            ?: li.selectFirst(".post-forum-meta a[href*=/forum/]")

        // The reply counter is the meta span that holds only a number.
        val replies = li.select(".post-meta > span").asSequence()
            .filter { it.selectFirst("a") == null }
            .map { it.text().trim() }
            .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toIntOrNull() ?: 0

        val timeText = li.selectFirst(".post-meta [data-performance-time]")?.text()?.trim()
            ?: li.select(".post-meta > span").lastOrNull()?.text()?.trim().orEmpty()

        // The title row carries up to five separate marks, each with its own
        // class: 置顶 (.topic-badge.pinned, plus topic-pinned on the li itself),
        // 精华 (.topic-management-featured-badge), 热 (.topic-stamp-hot), and the
        // two wordier statuses 抽奖中 / 发卡中. The first three get their own flag
        // so the row can place them; only the rest becomes free stamp text.
        val pinned = li.hasClass("topic-pinned") || li.selectFirst(".topic-badge.pinned") != null
        val featured = li.selectFirst(".topic-management-featured-badge") != null
        val hot = li.selectFirst(".topic-stamp-hot") != null
        val stampText = li.select(".community-lottery-title-status, .virtual-card-title-status")
            .joinToString(" ") { it.text().trim() }
            .trim()

        return TopicCard(
            id = id,
            title = link.text().trim(),
            author = author,
            authorId = authorId,
            avatar = avatarEl?.attrUrl("src").orEmpty(),
            forumId = idFrom(forumLink?.attr("href").orEmpty(), "forum"),
            forumName = forumLink?.text()?.trim().orEmpty(),
            replies = replies,
            timeText = timeText,
            pinned = pinned,
            hot = hot,
            featured = featured,
            stampText = stampText,
            // Only 回帖 rows have this: what was written, under the topic title.
            excerpt = li.textOf(".profile-reply-excerpt")
        )
    }

    private fun topicCards(scope: Element): List<TopicCard> =
        scope.select("ul.post-list > li.post-item").mapNotNull { topicCard(it) }
            // Search results are a bare list, not the home feed's wrapper.
            .ifEmpty { scope.select("li.post-item").mapNotNull { topicCard(it) } }

    // ---- shared page furniture ----------------------------------------------

    private fun forumsOf(doc: Document): List<ForumRef> {
        // The sidebar list carries topic counts, so prefer it.
        val sidebar = doc.select(".forum-enhancements-sidebar-list li > a").mapNotNull { a ->
            val id = idFrom(a.attr("href"), "forum")
            if (id == 0) return@mapNotNull null
            ForumRef(
                id = id,
                name = a.textOf(".forum-enhancements-sidebar-name").ifBlank { a.text().trim() },
                topicCount = a.textOf(".forum-enhancements-sidebar-count")
            )
        }
        if (sidebar.isNotEmpty()) return sidebar.distinctBy { it.id }

        return doc.select("nav.forum-nav a.forum-link").mapNotNull { a ->
            val id = idFrom(a.attr("href"), "forum")
            if (id == 0) null else ForumRef(id, a.text().trim())
        }.distinctBy { it.id }
    }

    private fun statsOf(doc: Document): SiteStats {
        // "主题 8906 · 回复 120365 · 用户 25665"
        val sub = doc.textOf(".stats-sub")
        val nums = digits.findAll(sub).map { it.value.replace(",", "") }.toList()
        return SiteStats(
            topics = nums.getOrElse(0) { "" },
            replies = nums.getOrElse(1) { "" },
            users = nums.getOrElse(2) { "" },
            online = firstInt(doc.textOf(".online-users-count")).takeIf { it > 0 }?.toString().orEmpty(),
            newestUser = doc.selectFirst(".new-users .nu-name")?.text()?.trim().orEmpty()
        )
    }

    /** 积分 110 / 金币：20 - a labelled counter the sidebar prints as loose text. */
    private val counterPhrase =
        Regex("""(积分|金币|威望|经验|声望|余额|贡献)\s*[:：]?\s*(\d[\d,]*)""")

    /**
     * The sidebar's `.user-rank` is not one string. The site's plugins inject the
     * user-group badge and the gacha title badge into it - the CSS carries a
     * `.user-rank .user-uid-badge-group` rule for exactly that - and the points
     * follow as loose text. Read as one `.text()` it comes out glued:
     * 「隐藏大佬SSR伪装者·积分110」. So the badges are lifted out by class, the
     * labelled counters by pattern, and the group is whatever is left.
     *
     * Returns 用户组 to 计数器.
     */
    private fun rankPartsOf(card: Element): Pair<String, String> {
        val box = card.selectFirst(".user-rank")
            ?: card.selectFirst(".user-header-info")
            ?: return "" to ""
        // A clone, because the badges are wanted elsewhere in the same document.
        val rest = box.clone()
        rest.select(
            ".user-uid-badge-group, .user-uid-badge, .gacha-title-post-badge, " +
                ".gacha-title-badge, .gacha-sidebar-title, .user-state-tags, " +
                ".user-name, .side-auth"
        ).remove()

        val leftover = rest.text().trim()
        // Counters come out by pattern, so they split even without a separator.
        val counters = counterPhrase.findAll(leftover)
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .toList()
        val withoutCounters = counterPhrase.replace(leftover, " ")

        val group = box.textOf(".user-uid-badge-group-name")
            .ifBlank {
                // The badge's tooltip is 「用户组：建设者」 when the span is absent.
                box.selectFirst(".user-uid-badge-group")
                    ?.attr("title")?.substringAfter('：', "")?.trim().orEmpty()
            }
            .ifBlank {
                withoutCounters.split('·', '|', '•', '/')
                    .map { it.trim() }
                    // An unlabelled number is a counter we did not recognise, not
                    // a group name - better blank than a stray 「110」 as a group.
                    .firstOrNull { it.isNotEmpty() && !digits.containsMatchIn(it) }
                    .orEmpty()
            }

        return group to counters.joinToString(" · ")
    }

    /**
     * Who the session belongs to. The sidebar prints 访客 plus a 登录/注册 pair
     * when there is no session, so the presence of `.side-auth` is the signal.
     */
    fun meOf(doc: Document): Me? {
        val card = doc.selectFirst(".sidebar-card.user-card") ?: return null
        if (card.selectFirst(".side-auth") != null) return null
        val name = card.textOf(".user-name")
        if (name.isBlank() || name == "访客") return null
        val profile = card.selectFirst("a[href*=/user/]")
        val (group, counters) = rankPartsOf(card)
        return Me(
            name = name,
            id = idFrom(profile?.attr("href").orEmpty(), "user"),
            avatar = card.selectFirst("img.avatar-img")?.attrUrl("src").orEmpty(),
            rank = group,
            points = card.select(".user-stat, .user-points").firstOrNull()
                ?.text()?.trim().orEmpty()
                .ifBlank { counters },
            title = gachaOf(card)
        )
    }

    /** Highest `p=` seen on links for this path - i.e. the last page. */
    private fun lastPageOf(doc: Document, pathPrefix: String): Int {
        val max = doc.select("a[href*=p=]").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.contains(pathPrefix)) return@mapNotNull null
            Regex("""[?&]p=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 1
        return maxOf(1, max)
    }

    fun csrfOf(doc: Document): String =
        doc.selectFirst("input[name=_csrf]")?.attr("value").orEmpty()

    /**
     * The site answers a page it will not serve by swapping the content for a
     * 「消息」 panel, and it redirects a rejected form to /form_error, which
     * renders the same panel. Reporting it keeps a refusal from reaching the
     * screen disguised as an empty list.
     */
    fun refusal(html: String): SiteException? {
        val panel = Jsoup.parse(html, Site.BASE).selectFirst(".form-error-panel") ?: return null
        val text = panel.textOf("p").ifBlank { panel.text().trim() }
        return SiteException(
            text.ifBlank { "站点拒绝了这次请求" },
            if (text.contains("登录")) SiteException.Kind.AUTH else SiteException.Kind.SERVER
        )
    }

    /**
     * True when what came back is the site's login page.
     *
     * A gated route answers a guest with a 302 to `/login`, which OkHttp follows
     * - so the status is 200 and the body is the login form. Without this check a
     * caller would parse that form as an empty version of the page it asked for.
     */
    fun isLoginPage(html: String): Boolean {
        val doc = Jsoup.parse(html, Site.BASE)
        // The form posts to itself, so it has no action to match on - the password
        // field plus one of the page's own markers is what identifies it.
        if (doc.selectFirst("form input[name=password]") == null) return false
        return doc.selectFirst("form[data-slot^=login], form[data-slot^=register]") != null ||
            doc.selectFirst("a[href*=/password_recovery]") != null ||
            doc.title().startsWith("登录") ||
            doc.title().startsWith("注册")
    }

    // ---- forms we submit -----------------------------------------------------

    /**
     * A form read off a page, ready to be posted back.
     *
     * Every write on this site (回帖, 发帖, 点赞, 私信) works the same way: the
     * server renders a form carrying `_csrf` and a pile of hidden state, and the
     * site's own JS posts `new FormData(form)` at `form.action`. Guessing field
     * names instead is how 回帖 silently broke - it sent `content` where the
     * markup says `body`, to `/topic/{id}` instead of the form's own action, so
     * the POST never reached the reply handler at all.
     *
     * So: never name a field from memory. Read the form, set the one or two
     * values the user typed, post everything else back untouched.
     */
    data class SiteForm(
        val action: String,
        val post: Boolean,
        /** Every submittable field, in document order, with the site's own values. */
        val fields: Map<String, String>,
        /**
         * Names of the form's `<textarea>`s, in document order. The body field of
         * a reply or a new topic is the first one; naming it is the caller's job
         * only when a form has several.
         */
        val textareas: List<String>,
        /**
         * True when the form declares `multipart/form-data`. 发帖 does, and posting
         * it url-encoded is a different request from the one the site expects.
         */
        val multipart: Boolean,
        /**
         * True when the form carries a Cloudflare Turnstile widget. Its token is
         * minted by JS in a browser, so such a form cannot be posted natively -
         * the caller has to hand the job to a WebView instead of failing.
         */
        val turnstile: Boolean
    ) {
        /** The conventional body field: a form's first textarea. */
        val bodyField: String get() = textareas.firstOrNull().orEmpty()

        /** This form's fields with [values] applied on top. */
        fun with(vararg values: Pair<String, String>): Map<String, String> =
            LinkedHashMap(fields).apply { values.forEach { (k, v) -> put(k, v) } }
    }

    /**
     * Reads the first form matching [css], the way a browser would submit it.
     *
     * Mirrors `new FormData(form)`: unchecked checkboxes and radios contribute
     * nothing, a `<select>` contributes its selected option, and a disabled field
     * contributes nothing. Textareas are collected empty - the caller fills them.
     * A submit button's `name`/`value` is included only when the site put one
     * there, because some handlers switch on it.
     */
    fun formOf(doc: Document, css: String): SiteForm? {
        val form = doc.selectFirst(css) ?: return null

        val fields = LinkedHashMap<String, String>()
        val textareas = mutableListOf<String>()

        // A browser submits the fields the HTML5 `form=` attribute attaches to
        // this form as well as the ones nested inside it, and skips a nested
        // field that points somewhere else. 用户名修改 on /profile is written the
        // first way - `#username-change-form` holds nothing but its `_csrf`, and
        // `new_username` / `current_password` live outside it - so a reader that
        // only walks the subtree posts a rename with no name in it.
        val id = form.id()
        val submittable = doc.select("input[name], textarea[name], select[name], button[name]")
            .filter { el ->
                val declared = el.attr("form")
                if (declared.isNotBlank()) id.isNotBlank() && declared == id
                else el.parents().firstOrNull { it.normalName() == "form" } === form
            }

        for (el in submittable) {
            val name = el.attr("name")
            if (name.isBlank() || el.hasAttr("disabled")) continue

            when (el.tagName()) {
                "textarea" -> {
                    textareas += name
                    fields[name] = el.wholeText()
                }

                "select" -> {
                    val option = el.selectFirst("option[selected]") ?: el.selectFirst("option")
                    fields[name] = option?.let {
                        if (it.hasAttr("value")) it.attr("value") else it.text().trim()
                    }.orEmpty()
                }

                "button" -> {
                    // Only a submit button submits, and only the one that was
                    // pressed. Keeping the first is the closest we get.
                    val type = el.attr("type").ifBlank { "submit" }
                    if (type == "submit" && !fields.containsKey(name)) {
                        fields[name] = el.attr("value")
                    }
                }

                else -> when (el.attr("type").lowercase().ifBlank { "text" }) {
                    // An unchecked box or radio is simply absent from a real submit.
                    "checkbox" -> if (el.hasAttr("checked")) {
                        fields[name] = el.attr("value").ifBlank { "on" }
                    }
                    "radio" -> if (el.hasAttr("checked")) fields[name] = el.attr("value")
                    // A file input cannot be reproduced; attachments are their own
                    // upload endpoint, so drop it rather than send an empty part.
                    "file" -> Unit
                    "submit", "button", "reset", "image" -> Unit
                    else -> fields[name] = el.attr("value")
                }
            }
        }

        // An unchecked radio group contributes nothing, exactly as in a browser -
        // this reader must not invent a choice the user did not make. 发帖 is why:
        // `topic_special_type` ships with `lottery` and `virtual_card` and neither
        // checked, because 普通帖 (value="") is added by the site's own script. A
        // "take the group's first option" rule would post every new topic as a
        // 抽奖帖. Callers that need a default set it themselves.

        return SiteForm(
            // A form with no action posts back to the page it came from - 发帖 is
            // served that way, so the document's own URL is the fallback.
            action = Site.absolute(form.attr("action")).ifBlank { doc.location().ifBlank { Site.BASE } },
            post = !form.attr("method").equals("get", ignoreCase = true),
            fields = fields,
            textareas = textareas,
            multipart = form.attr("enctype").contains("multipart", ignoreCase = true),
            turnstile = form.selectFirst(".cf-turnstile, [data-sitekey]") != null
        )
    }

    fun formOf(html: String, css: String): SiteForm? = formOf(Jsoup.parse(html, Site.BASE), css)

    /**
     * The reply form on a topic page.
     *
     * `.ajax-reply-form` is the class the site's own submit handler matches on
     * (see the site's index.js), which makes it the one selector guaranteed to
     * identify the form that actually accepts a reply.
     */
    fun replyForm(doc: Document): SiteForm? =
        formOf(doc, "form.ajax-reply-form")
            ?: formOf(doc, ".reply-panel form:has(textarea)")

    /**
     * The 发新帖 form at /topic_edit.
     *
     * The route is `topic_edit`, not `topic_new`, and the form carries no `action`
     * at all - it posts back to the page it was served on. `id=0` is what marks
     * this as a new topic rather than an edit.
     */
    fun newTopicForm(doc: Document): SiteForm? =
        formOf(doc, "form:has(select[name=forum_id]):has(textarea[name=body])")
            ?: formOf(doc, "form:has(input[name=title]):has(textarea)")

    /** Boards the 发帖 form offers, in the order it lists them. */
    fun boardOptions(doc: Document): List<Pair<Int, String>> =
        doc.select("select[name=forum_id] option").mapNotNull { option ->
            val id = option.attr("value").toIntOrNull() ?: return@mapNotNull null
            id to option.text().trim()
        }

    /**
     * The 点赞 form on one comment, found by the reply id the site anchors it to.
     *
     * Every comment carries its own copy of this form, so the reply id is the only
     * way to tell them apart.
     */
    fun donateForm(doc: Document, replyId: Int): SiteForm? =
        formOf(doc, "form.donate-reaction-form:has([data-reply-id=$replyId])")
            ?: formOf(doc, "form:has(input[name=donate_reaction_reply_id][value=$replyId])")

    /**
     * The 打赏 form inside the topic-level donate modal.
     *
     * The opening post has no form on the page - only a link to `/donate?topic_id=…`
     * whose JSON carries this. The `request_key` in it is one-shot, so the form has
     * to be read from the response that is about to be posted back, never cached.
     */
    fun donateTopicForm(modalHtml: String): SiteForm? =
        formOf(Jsoup.parse(modalHtml, Site.BASE), "form[data-donate-form]")
            ?: formOf(Jsoup.parse(modalHtml, Site.BASE), "form[action*=donate]")

    /** The topic-level 打赏 panel: what it offers and what it says. */
    data class DonatePanel(
        /** Preset amounts, from the modal's own buttons. */
        val presets: List<Int> = emptyList(),
        /** The 已打赏 … 我的积分 … line, shown as-is. */
        val info: String = "",
        /** Largest custom amount the input accepts; 0 when it says nothing. */
        val maxAmount: Int = 0
    )

    fun donatePanel(modalHtml: String): DonatePanel {
        val doc = Jsoup.parse(modalHtml, Site.BASE)
        return DonatePanel(
            presets = doc.select("[data-amount]")
                .mapNotNull { it.attr("data-amount").toIntOrNull() }
                .filter { it > 0 },
            info = doc.textOf(".donate-modal-info"),
            maxAmount = doc.selectFirst("input[name=amount]")?.attr("max")?.toIntOrNull() ?: 0
        )
    }

    /**
     * The attachment uploader that sits beside a body field.
     *
     * Not part of the form it appears in: the file goes to its own endpoint, and
     * what comes back is markdown to paste into the body. Everything here is read
     * off the page, so the size cap and the accepted extensions are the site's.
     */
    data class Uploader(
        val action: String,
        val csrf: String,
        /** Size cap in MB; 0 when the page states none. */
        val maxMb: Int,
        /** Accepted extensions, `.png` style, as the file input lists them. */
        val accepts: List<String>
    )

    fun uploaderOf(doc: Document): Uploader? {
        val box = doc.selectFirst("[data-upload-url]") ?: return null
        val action = Site.absolute(box.attr("data-upload-url"))
        if (action.isBlank()) return null
        val csrf = csrfOf(doc)
        if (csrf.isBlank()) return null
        return Uploader(
            action = action,
            csrf = csrf,
            maxMb = box.attr("data-upload-max-mb").toIntOrNull() ?: 0,
            accepts = box.selectFirst("input[type=file][accept]")
                ?.attr("accept").orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.startsWith(".") }
        )
    }

    /**
     * What the 点赞 button on one comment currently is.
     *
     * A reaction that was paid for cannot be taken back - the site says so with
     * `data-coined` - so the app has to read this rather than assume a toggle.
     */
    data class Reaction(
        val replyId: Int,
        val liked: Boolean,
        val coined: Boolean,
        /** Point amounts the site offers, from `data-tiers`; 0 is a plain like. */
        val tiers: List<Int>,
        val authorName: String
    )

    fun reactionOf(doc: Document, replyId: Int): Reaction? {
        val button = doc.selectFirst("[data-donate-reaction][data-reply-id=$replyId]")
            ?: return null
        return Reaction(
            replyId = replyId,
            liked = button.attr("data-liked") == "1",
            coined = button.attr("data-coined") == "1",
            tiers = button.attr("data-tiers").split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 },
            authorName = button.attr("data-author-name").trim()
        )
    }

    /**
     * The 私信 compose box on a conversation page.
     *
     * Its body field is `content`, where a reply's is `body` - the site is not
     * consistent across its own forms, which is why none of these names are
     * written down anywhere but the markup.
     */
    fun dmComposeForm(doc: Document): SiteForm? =
        formOf(doc, "form.direct-messages-compose")
            ?: formOf(doc, "form:has(textarea[name=content]):not(.direct-messages-block-form)")

    /**
     * What the site answers a write with.
     *
     * All four write endpoints answer XHR with the same JSON shape: `ok`, plus a
     * `message` to show, sometimes `html` for the row to append and a `redirect`
     * that means the session died. Anything that is not JSON means the request
     * never reached the handler.
     */
    data class WriteResult(
        val ok: Boolean,
        val message: String = "",
        /** Rendered markup for what was just created, when the site sends it. */
        val html: String = "",
        /** Set when the site wants the browser to navigate - a dead session. */
        val redirect: String = ""
    ) {
        /**
         * Topic this write landed in, when the site said. 发帖 answers with a
         * redirect to the new topic, which is the only place its id appears.
         */
        val topicId: Int
            get() = Regex("""/topic/(\d+)""").find(redirect)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/topic/(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
    }

    // ---- search --------------------------------------------------------------

    /**
     * The form /search renders. A search is not a `?q=` GET on this site: the
     * page ships a real form carrying a `_csrf` token and a type radio group,
     * and it pages by POST, so the only request that works is the one built out
     * of the form the site actually served.
     */
    data class SearchForm(
        val action: String,
        val post: Boolean,
        /** Name of the keyword input. */
        val queryField: String,
        /** Every other submittable field, with the value the site pre-selected. */
        val fields: Map<String, String>,
        /** Name of the page field, when the form pages by POST. */
        val pageField: String
    )

    /** Null when /search carried no form, which is what a refusal looks like. */
    fun searchForm(html: String): SearchForm? {
        val doc = Jsoup.parse(html, Site.BASE)
        val form = doc.selectFirst("form.search-page-form")
            ?: doc.selectFirst(".search-page-head form")
            ?: doc.selectFirst("form:has(.search-page-query)")
            ?: doc.selectFirst("form[action*=search]")
            ?: return null

        val queryField = form.select("input[type=text], input[type=search], input:not([type])")
            .firstOrNull { it.attr("name").isNotBlank() }
            ?.attr("name")
            ?: "q"

        val fields = LinkedHashMap<String, String>()
        // Hidden fields first - this is where _csrf lives.
        form.select("input[type=hidden][name]").forEach { fields[it.attr("name")] = it.attr("value") }
        // Keep the type the site pre-selected, and fall back to the group's first
        // choice so a required radio is never submitted empty.
        form.select("input[type=radio][name]").groupBy { it.attr("name") }.forEach { (name, group) ->
            fields[name] = (group.firstOrNull { it.hasAttr("checked") } ?: group.first()).attr("value")
        }
        form.select("select[name]").forEach { select ->
            val option = select.selectFirst("option[selected]") ?: select.selectFirst("option")
            fields[select.attr("name")] = option?.attr("value").orEmpty()
        }
        form.select("input[type=checkbox][name][checked]").forEach {
            fields[it.attr("name")] = it.attr("value").ifBlank { "1" }
        }
        fields.remove(queryField)

        return SearchForm(
            action = Site.absolute(form.attr("action").ifBlank { "/search" }),
            post = !form.attr("method").equals("get", ignoreCase = true),
            queryField = queryField,
            fields = fields,
            pageField = doc.selectFirst(
                ".search-page-pagination [name=p], .search-page-pagination [name=page]"
            )?.attr("name").orEmpty()
        )
    }

    // ---- pages ---------------------------------------------------------------

    fun home(html: String): HomePage {
        val doc = Jsoup.parse(html, Site.BASE)
        val hot = doc.select(".daily-hot-topics-list li > a").mapNotNull { a ->
            val id = idFrom(a.attr("href"), "topic")
            if (id == 0) return@mapNotNull null
            TopicCard(
                id = id,
                title = a.textOf(".daily-hot-topics-title").ifBlank { a.text().trim() },
                author = "",
                replies = countBefore(a.textOf(".daily-hot-topics-count"), "回复"),
                timeText = "近 24 小时"
            )
        }
        return HomePage(
            stats = statsOf(doc),
            forums = forumsOf(doc),
            dailyHot = hot,
            topics = topicCards(doc),
            lastPage = lastPageOf(doc, "/?"),
            me = meOf(doc)
        )
    }

    fun forum(id: Int, page: Int, html: String): ForumPage {
        val doc = Jsoup.parse(html, Site.BASE)
        return ForumPage(
            id = id,
            name = doc.selectFirst(".forum-enhancements-title-row h1")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim().orEmpty(),
            topics = topicCards(doc),
            page = page,
            lastPage = maxOf(lastPageOf(doc, "/forum/$id"), page)
        )
    }

    /** Feed pages that only differ by sort key reuse the home list markup. */
    fun feed(html: String): List<TopicCard> = topicCards(Jsoup.parse(html, Site.BASE))

    // ---- post content --------------------------------------------------------

    /** Reduces a post body to our own block list, keeping images and code. */
    /** Tags that are part of a run of text rather than a block of their own. */
    private val inlineTags = setOf(
        "a", "b", "strong", "i", "em", "u", "s", "del", "ins", "small", "span",
        "code", "sub", "sup", "mark", "br", "font", "abbr", "kbd"
    )

    private fun blocksOf(content: Element): List<LiveBlock> {
        // Long posts are wrapped in a fold container; the real body is inside.
        val root = content.selectFirst("[data-long-content-fold]") ?: content
        val out = mutableListOf<LiveBlock>()

        fun emitImage(img: Element) {
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            if (src.isBlank()) return
            out += LiveBlock(LiveBlock.Type.IMAGE, src = Site.absolute(src), text = img.attr("alt"))
        }

        /**
         * The text of an inline run, plus where its anchors sit in it.
         *
         * The offsets have to line up with the string exactly, so this builds both
         * in one pass instead of running `text()` and then searching for each label -
         * two links with the same label would be indistinguishable that way.
         */
        fun inline(el: Element): Pair<String, List<LiveBlock.Link>> {
            val sb = StringBuilder()
            val links = mutableListOf<LiveBlock.Link>()

            // Jsoup's own text() collapses runs of whitespace to one space; matching
            // that keeps paragraphs reading the way they did before this change.
            fun append(raw: String) {
                for (ch in raw) {
                    val c = if (ch == ' ') ' ' else ch
                    if (c.isWhitespace()) {
                        if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    } else {
                        sb.append(c)
                    }
                }
            }

            fun descend(node: org.jsoup.nodes.Node) {
                when (node) {
                    is org.jsoup.nodes.TextNode -> append(node.wholeText)
                    is Element -> when (node.tagName().lowercase()) {
                        // A <br> is a line break inside a paragraph, not a space.
                        "br" -> if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                        "a" -> {
                            val start = sb.length
                            node.childNodes().forEach { descend(it) }
                            val href = node.attr("href").trim()
                            if (sb.length > start && href.isNotBlank()) {
                                links += LiveBlock.Link(start, sb.length, Site.absolute(href))
                            }
                        }
                        // Images inside a paragraph are emitted as their own blocks
                        // by the caller; they contribute no text here.
                        "img" -> Unit
                        else -> node.childNodes().forEach { descend(it) }
                    }
                }
            }

            el.childNodes().forEach { descend(it) }

            // The ranges were recorded against the untrimmed builder, so dropping
            // leading whitespace has to shift them by the same amount or every label
            // ends up off by one.
            val raw = sb.toString()
            val lead = raw.length - raw.trimStart().length
            val text = raw.trim()
            val shifted = links
                .map { LiveBlock.Link(it.start - lead, it.end - lead, it.href) }
                .filter { it.start >= 0 && it.end <= text.length && it.start < it.end }
            return text to shifted
        }

        /** A paragraph-like run, keeping whatever links it holds. */
        fun emitPara(el: Element, type: LiveBlock.Type = LiveBlock.Type.PARA) {
            val (text, links) = inline(el)
            if (text.isNotBlank()) out += LiveBlock(type, text = text, links = links)
        }

        fun walk(el: Element) {
            when (el.tagName().lowercase()) {
                "p", "div" -> {
                    // A paragraph that only wraps an image should become an image.
                    val imgs = el.select("> img, > a > img")
                    val text = el.text().trim()
                    if (imgs.isNotEmpty()) {
                        imgs.forEach { emitImage(it) }
                        emitPara(el)
                    } else if (el.tagName() == "p") {
                        emitPara(el)
                    } else {
                        el.children().forEach { walk(it) }
                        if (el.children().isEmpty() && text.isNotBlank()) {
                            emitPara(el)
                        }
                    }
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> emitPara(el, LiveBlock.Type.HEADING)
                // A quote is where a linked source most often sits, so it keeps its
                // anchors too.
                "blockquote" -> emitPara(el, LiveBlock.Type.QUOTE)
                "pre" ->
                    el.wholeText().trimEnd().takeIf { it.isNotBlank() }?.let {
                        out += LiveBlock(LiveBlock.Type.CODE, it)
                    }
                "ul", "ol" -> el.select("> li").forEach { li ->
                    emitPara(li, LiveBlock.Type.LIST_ITEM)
                }
                "img" -> emitImage(el)
                "hr" -> out += LiveBlock(LiveBlock.Type.RULE)
                "br" -> Unit
                "a" -> {
                    val inner = el.select("> img")
                    if (inner.isNotEmpty()) {
                        inner.forEach { emitImage(it) }
                    } else {
                        val t = el.text().trim()
                        if (t.isNotBlank()) {
                            out += LiveBlock(
                                LiveBlock.Type.LINK,
                                text = t,
                                href = Site.absolute(el.attr("href"))
                            )
                        }
                    }
                }
                else -> {
                    if (el.children().isEmpty()) {
                        emitPara(el)
                    } else if (el.selectFirst("> a[href]") != null && el.select("> *").all {
                            it.tagName().lowercase() in inlineTags
                        }) {
                        // An inline-only wrapper (a bare <span>/<strong> holding a
                        // link) is one run of text, not a container to descend into -
                        // descending would emit the anchor as its own block and lose
                        // the words around it.
                        emitPara(el)
                    } else {
                        el.children().forEach { walk(it) }
                    }
                }
            }
        }

        root.children().forEach { walk(it) }

        // A body with no element children at all (bare text node) still has text.
        if (out.isEmpty()) {
            root.text().trim().takeIf { it.isNotBlank() }?.let {
                out += LiveBlock(LiveBlock.Type.PARA, it)
            }
        }
        return out
    }

    /**
     * The floor a comment answers, resolved exactly the way linux.sb's own
     * quote_threads script does it: the explicit attribute first, then the
     * ?floor= / ?replyid= link the 引用 button leaves in the body, then the
     * "@某人 #12" line a hand-written quote leaves behind. A reference to a floor
     * that is not on this page is what the site marks 跨页; we return it anyway
     * and let the caller decide, so the label still reads "回复 #12".
     */
    private fun parentFloorOf(li: Element, floorByReplyId: Map<Int, Int>): Int {
        validFloor(li.attr("data-quote-threads-parent-floor")).let { if (it > 0) return it }
        for (anchor in li.select(".post-content a[href]")) {
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            validFloor(queryParam(href, "floor")).let { if (it > 0) return it }
            val replyId = validFloor(queryParam(href, "replyid"))
                .takeIf { it > 0 } ?: validFloor(queryParam(href, "reply_id"))
            if (replyId > 0) floorByReplyId[replyId]?.let { if (it > 0) return it }
        }
        return mentionedFloor.find(li.textOf(".post-content"))
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun post(
        li: Element,
        floor: Int,
        opening: Boolean,
        parentFloor: Int = 0,
        replyCount: Int = 0
    ): Post {
        val authorLink = li.selectFirst("a.post-author") ?: li.selectFirst("a[href*=/user/]")
        val avatar = li.selectFirst(".post-avatar img.avatar-img")
        val uidBadge = li.select(".user-uid-badge").firstOrNull { it.text().contains("UID") }
        val content = li.selectFirst(".post-content")
        // Carries whether we have liked this one, whether it was paid for, and the
        // point amounts on offer. All three are attributes, not text.
        val reactionButton = li.selectFirst("[data-donate-reaction]")

        return Post(
            id = li.id().ifBlank { "floor-$floor" },
            author = authorLink?.text()?.trim().orEmpty()
                .ifBlank { avatar?.attr("alt")?.trim().orEmpty() },
            authorId = idFrom(authorLink?.attr("href").orEmpty(), "user"),
            avatar = avatar?.attrUrl("src").orEmpty(),
            group = li.textOf(".user-uid-badge-group-name"),
            uid = uidBadge?.text()?.trim().orEmpty(),
            title = gachaOf(li),
            timeText = li.textOf(".post-time"),
            // The opening post's badge is .donate-topic-reaction-count; a comment
            // carries the plain .donate-reaction-count, hidden by the site at 0.
            likes = firstInt(
                li.textOf(".donate-topic-reaction-count")
                    .ifBlank { li.textOf(".donate-reaction-count") }
            ),
            blocks = content?.let { blocksOf(it) }.orEmpty(),
            isOpening = opening,
            floor = floor,
            replyId = postAnchor.find(li.id())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            liked = reactionButton?.attr("data-liked") == "1",
            coined = reactionButton?.attr("data-coined") == "1",
            tiers = reactionButton?.attr("data-tiers").orEmpty()
                .split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 },
            parentFloor = parentFloor,
            replyCount = replyCount
        )
    }

    fun topic(id: Int, page: Int, html: String): TopicDetail {
        val doc = Jsoup.parse(html, Site.BASE)
        val rows = doc.select("ul.topic-post-list > li.post-item, ul.post-list > li.post-entry")
            .ifEmpty { doc.select("li.post-item.post-entry") }

        // The site puts the real floor on the row; only fall back to counting when
        // it is missing, which is the case for the opening post.
        val floors = rows.mapIndexed { index, li ->
            val declared = validFloor(li.attr("data-floor"))
            if (declared > 0) declared else if (page > 1) index + 1 else index
        }
        val floorByReplyId = HashMap<Int, Int>(rows.size)
        rows.forEachIndexed { index, li ->
            postAnchor.find(li.id())?.groupValues?.get(1)?.toIntOrNull()?.let {
                floorByReplyId[it] = floors[index]
            }
        }
        val parentFloors = rows.mapIndexed { index, li ->
            parentFloorOf(li, floorByReplyId).takeIf { it != floors[index] } ?: 0
        }
        // Only references the site can resolve on this page become a reply count;
        // a parent on another page is 跨页 there and counted on that page instead.
        val onThisPage = floors.toHashSet()
        val childCount = parentFloors.filter { it > 0 && it in onThisPage }
            .groupingBy { it }
            .eachCount()

        val posts = rows.mapIndexed { index, li ->
            post(
                li = li,
                floor = floors[index],
                opening = page == 1 && index == 0,
                parentFloor = parentFloors[index],
                replyCount = childCount[floors[index]] ?: 0
            )
        }
        val opening = posts.firstOrNull()?.takeIf { it.isOpening }
        val comments = if (opening != null) posts.drop(1) else posts

        val loginGate = doc.selectFirst(".replies-login-visible")
        val declaredCount = loginGate?.let { firstInt(it.textOf(".replies-login-visible-count")) }

        // The reply panel is rendered but `hidden` for guests; a textarea or a
        // csrf field is the honest signal that posting is actually available.
        val csrf = csrfOf(doc)
        val replyBox = doc.selectFirst(".reply-panel")
        val canReply = csrf.isNotBlank() &&
            replyBox != null &&
            replyBox.selectFirst(".reply-login-box") == null

        val pageTitle = doc.selectFirst("h1.post-topic-title")?.text()?.trim()
            ?: doc.selectFirst(".post-content-title")?.text()?.trim()
            ?: doc.selectFirst("a.post-topic-title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim().orEmpty()

        val crumbForum = doc.selectFirst(".breadcrumb a[href*=/forum/]")
            ?: doc.selectFirst("a.post-forum-badge")
            ?: doc.selectFirst(".post-forum-meta a[href*=/forum/]")

        return TopicDetail(
            id = id,
            title = pageTitle,
            forumId = idFrom(crumbForum?.attr("href").orEmpty(), "forum"),
            forumName = crumbForum?.text()?.trim().orEmpty(),
            opening = opening,
            comments = comments,
            commentCount = declaredCount ?: comments.size,
            page = page,
            lastPage = maxOf(lastPageOf(doc, "/topic/$id"), page),
            commentsNeedLogin = loginGate != null,
            csrf = csrf,
            canReply = canReply,
            related = emptyList()
        )
    }

    // ---- leaderboards, notifications, messages, profiles ---------------------

    /**
     * 榜单, off `/leaderboard?type=…`.
     *
     * Two things about this page are easy to get wrong, and the previous reader
     * got both. First, each 榜单 is its **own page** reached by a `type` query -
     * not several panels stacked on one page, so this returns one [Board] and
     * lists the other four in [Board.tabs] rather than trying to find them here.
     * Second, the top three are **not** in `.leaderboard-list`: they are a
     * separate `.leaderboard-podium`, in the visual order 2-1-3. Reading only the
     * list yields a board that starts at rank 4.
     */
    fun board(html: String): Board {
        val doc = Jsoup.parse(html, Site.BASE)
        val page = doc.selectFirst(".leaderboard-page")
            ?: return Board(key = "", label = "")

        val tabs = page.select(".leaderboard-tab").mapNotNull { a ->
            val key = queryValue(a.attr("href"), "type")
            if (key.isBlank()) null else BoardTab(key = key, label = a.text().trim())
        }
        val activeTab = page.selectFirst(".leaderboard-tab.active")
        val key = queryValue(activeTab?.attr("href").orEmpty(), "type")
            .ifBlank { tabs.firstOrNull()?.key.orEmpty() }

        // The podium's DOM order is 2-1-3, so its own step number is the rank -
        // never the position it was found in.
        val podium = page.select(".leaderboard-podium-col").mapNotNull { col ->
            val rank = firstInt(col.textOf(".leaderboard-podium-step"))
            if (rank == 0) return@mapNotNull null
            RankRowData(
                rank = rank,
                name = col.textOf(".leaderboard-podium-name"),
                userId = idFrom(col.attr("href"), "user"),
                avatar = col.selectFirst("img.avatar-img")?.attrUrl("src").orEmpty(),
                group = col.textOf(".leaderboard-podium-group"),
                count = col.textOf(".leaderboard-podium-count")
            )
        }

        val rest = page.select(".leaderboard-item").mapIndexedNotNull { i, row ->
            val name = row.textOf(".leaderboard-name")
                .ifBlank { row.selectFirst("img.avatar-img")?.attr("alt")?.trim().orEmpty() }
            if (name.isBlank()) return@mapIndexedNotNull null
            RankRowData(
                // The badge holds the rank; the index is only a fallback, and it
                // has to account for the three the podium already took.
                rank = firstInt(row.textOf(".leaderboard-rank-badge")).takeIf { it > 0 }
                    ?: (podium.size + i + 1),
                name = name,
                userId = idFrom(row.attr("href"), "user"),
                avatar = row.selectFirst("img.avatar-img")?.attrUrl("src").orEmpty(),
                group = row.textOf(".leaderboard-group"),
                count = row.textOf(".leaderboard-count")
            )
        }

        return Board(
            key = key,
            label = activeTab?.text()?.trim().orEmpty()
                .ifBlank { page.textOf(".leaderboard-title") },
            subtitle = page.textOf(".leaderboard-subtitle"),
            tabs = tabs,
            rows = (podium + rest).sortedBy { it.rank }
        )
    }

    /** Reads one query parameter out of a link the site rendered. */
    private fun queryValue(href: String, name: String): String =
        Regex("[?&]${Regex.escape(name)}=([^&#]*)").find(href)?.groupValues?.get(1).orEmpty()

    fun notifications(html: String): List<NotifyItem> {
        val doc = Jsoup.parse(html, Site.BASE)
        return doc.select(".notify-item, .notification-item, ul.notify-list > li").map { li ->
            NotifyItem(
                text = li.text().trim(),
                timeText = li.textOf(".notify-time, .time, [data-performance-time]"),
                href = li.selectFirst("a")?.attrUrl("href").orEmpty(),
                unread = li.hasClass("unread") || li.selectFirst(".unread") != null,
                actor = li.selectFirst("a[href*=/user/]")?.text()?.trim().orEmpty(),
                avatar = li.selectFirst("img")?.attrUrl("src").orEmpty()
            )
        }.filter { it.text.isNotBlank() }
    }

    /**
     * The conversation list on /direct_messages.
     *
     * Each row is an `<a class="direct-messages-conversation">` whose href is the
     * thread, and the partner's user id is on the avatar as `data-online-user-id` -
     * the row does not link to the profile, so that attribute is the only place the
     * id appears.
     */
    fun conversations(html: String): List<Conversation> {
        val doc = Jsoup.parse(html, Site.BASE)
        return doc.select("a.direct-messages-conversation").mapNotNull { row ->
            val href = row.attr("href")
            val threadId = Regex("""/direct_messages/(\d+)""").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val main = row.selectFirst(".direct-messages-list-main")
            val peerId = row.selectFirst("[data-online-user-id]")
                ?.attr("data-online-user-id")?.toIntOrNull()
                ?: threadId.toIntOrNull() ?: 0
            Conversation(
                id = threadId,
                peer = main?.textOf("strong")
                    ?: row.selectFirst("img")?.attr("alt")?.trim().orEmpty(),
                peerId = peerId,
                avatar = row.selectFirst("img")?.attrUrl("src").orEmpty(),
                preview = main?.textOf("small").orEmpty(),
                timeText = main?.textOf("time").orEmpty(),
                unread = firstInt(row.textOf(".direct-messages-unread, .unread-count, .badge"))
            )
        }.filter { it.peer.isNotBlank() }
    }

    /**
     * Someone found by the 私信 user search.
     *
     * Starting a conversation is not a separate action on this site: each result
     * links straight at `/direct_messages/<uid>`, which renders the same thread page
     * an existing conversation does, compose box included. So a new conversation is
     * an empty one, and the app needs nothing beyond navigating there.
     */
    data class UserHit(
        val userId: Int,
        val name: String,
        val avatar: String = ""
    )

    fun userSearch(html: String): List<UserHit> {
        val doc = Jsoup.parse(html, Site.BASE)
        return doc.select("a.direct-messages-search-result").mapNotNull { row ->
            val id = Regex("""/direct_messages/(\d+)""").find(row.attr("href"))
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: row.selectFirst("[data-online-user-id]")
                    ?.attr("data-online-user-id")?.toIntOrNull()
                ?: return@mapNotNull null
            UserHit(
                userId = id,
                name = row.textOf("strong").ifBlank {
                    row.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                },
                avatar = row.selectFirst("img")?.attrUrl("src").orEmpty()
            )
        }.filter { it.name.isNotBlank() }
    }

    /** One private-message thread. */
    data class Thread(
        val partnerId: Int,
        val partner: String,
        val partnerAvatar: String = "",
        val messages: List<DirectMessage> = emptyList(),
        /** Id of the newest message, which is what the site polls updates from. */
        val lastId: Long = 0
    )

    /**
     * The messages in one thread.
     *
     * The site marks the other person's messages `is-theirs` and leaves ours
     * unmarked, so "not theirs" is the test - there is no `is-mine` class to look
     * for.
     */
    fun thread(partnerId: Int, html: String): Thread {
        val doc = Jsoup.parse(html, Site.conversation(partnerId))
        val head = doc.selectFirst(".direct-messages-thread-user")

        val messages = doc.select("article.direct-messages-message").map { article ->
            val theirs = article.hasClass("is-theirs")
            DirectMessage(
                body = article.textOf(".direct-messages-content"),
                timeText = article.selectFirst(".direct-messages-meta time")
                    ?.let { it.attr("datetime").ifBlank { it.text() } }
                    ?.trim().orEmpty(),
                fromMe = !theirs,
                sender = article.textOf(".direct-messages-meta strong")
            )
        }

        return Thread(
            partnerId = idFrom(head?.attr("href").orEmpty(), "user").takeIf { it > 0 } ?: partnerId,
            partner = head?.textOf("strong").orEmpty()
                .ifBlank { doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim().orEmpty() },
            partnerAvatar = head?.selectFirst("img")?.attrUrl("src").orEmpty(),
            messages = messages,
            lastId = doc.selectFirst(".direct-messages-thread")
                ?.attr("data-last-id")?.toLongOrNull() ?: 0
        )
    }

    fun profile(id: Int, html: String): Profile {
        val doc = Jsoup.parse(html, Site.BASE)
        val head = doc.selectFirst(".user-profile, .profile-card, .user-card") ?: doc.body()
        val stats = doc.select(".profile-stat, .user-stat-item").mapNotNull { s ->
            val label = s.textOf(".label, .stat-label")
            val value = s.textOf(".value, .stat-value")
            if (label.isBlank() && value.isBlank()) null else label to value
        }
        return Profile(
            id = id,
            name = head.textOf(".user-name, .profile-name, h1").ifBlank {
                doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim().orEmpty()
            },
            avatar = head.selectFirst("img.avatar-img, img")?.attrUrl("src").orEmpty(),
            group = head.textOf(".user-uid-badge-group-name"),
            title = gachaOf(head),
            joinedText = head.textOf(".user-joined, .joined"),
            stats = stats,
            topics = topicCards(doc)
        )
    }

    // ---- 个人设置 ------------------------------------------------------------

    /**
     * 个人设置, off `/profile`.
     *
     * The page reads as one settings form and is five write paths wearing one
     * coat, so this reads it as five sections. Each one keeps the site's own
     * wording for its policy - what a 头像 costs, how long between renames -
     * rather than a copy of the rule, which would go stale silently.
     *
     * Null when the page is not the settings page at all: a guest gets the login
     * form here, and the caller has to be able to tell that apart from a page
     * whose fields all happened to be empty.
     */
    fun accountSettings(html: String): AccountSettings? {
        val doc = Jsoup.parse(html, Site.PROFILE)
        val panel = doc.selectFirst(".form-panel:has(.profile-account-grid)") ?: return null

        // 个人资料 is a grid of label/value cards. The last one is the logout
        // form wearing the same class, so cards holding a form are skipped.
        val cards = panel.select(".profile-account-card")
            .filter { it.selectFirst("form") == null }
            .associate { card ->
                val value = card.selectFirst("strong")?.let { strong ->
                    // 邮箱 is Cloudflare-obfuscated: the anchor's text is a
                    // placeholder and the real address is in the title.
                    strong.attr("title").ifBlank { strong.text().trim() }
                }.orEmpty()
                card.textOf("span") to value
            }

        val picker = panel.selectFirst(".avatar-picker")
        val upload = doc.selectFirst("[data-avatar-upload]")
        val rename = doc.selectFirst("[data-username-change-panel]")
        val emailCard = doc.selectFirst(".user-review-email-card")

        return AccountSettings(
            name = cards["用户名"].orEmpty(),
            uid = cards["用户 UID"].orEmpty(),
            email = cards["邮箱"].orEmpty(),
            joinedText = cards["注册时间"].orEmpty(),
            points = cards["积分"].orEmpty(),
            avatar = (upload ?: picker)?.selectFirst("img.avatar-img")?.attrUrl("src").orEmpty(),
            bio = panel.selectFirst("textarea[name=bio]")?.wholeText().orEmpty(),
            avatarStyles = picker?.select("select[name=avatar_style] option")?.map {
                it.attr("value") to it.text().trim()
            }.orEmpty(),
            avatarStyle = picker?.selectFirst("select[name=avatar_style] option[selected]")
                ?.attr("value").orEmpty(),
            avatarSeed = picker?.selectFirst("input[name=avatar_seed]")?.attr("value").orEmpty(),
            avatarPresets = upload?.select("[data-avatar-preset-seed]")?.mapNotNull { option ->
                val seed = option.attr("data-avatar-preset-seed")
                if (seed.isBlank()) null else AvatarPreset(
                    seed = seed,
                    url = option.selectFirst("img")?.attrUrl("src").orEmpty()
                )
            }.orEmpty(),
            avatarNote = upload?.textOf("p").orEmpty(),
            renamePolicy = rename?.textOf("p").orEmpty(),
            renameNote = rename?.textOf(".username-change-summary-meta small").orEmpty(),
            // The field, not the sentence: a refusal's wording belongs to the
            // site, but a missing or disabled input means the same thing in any
            // wording.
            renameAllowed = rename?.selectFirst("input[name=new_username]:not([disabled])") != null,
            emailNote = emailCard?.textOf(".profile-disclosure-heading small").orEmpty(),
            oauth = doc.select(".oauth-login-profile-card").mapNotNull { card ->
                val link = card.selectFirst("a[href*=oauth_login]") ?: return@mapNotNull null
                val provider = queryValue(link.attr("href"), "provider")
                if (provider.isBlank()) return@mapNotNull null
                val state = card.textOf(".profile-disclosure-heading small")
                OAuthBinding(
                    provider = provider,
                    label = card.selectFirst(".profile-disclosure-heading > span")?.text()?.trim()
                        .orEmpty(),
                    // 已绑定 / 未绑定 is the site's own word for it; the account it
                    // prints beside that only appears once bound.
                    bound = state.startsWith("已"),
                    account = if (state.startsWith("已")) state.removePrefix("已绑定").trim() else "",
                    href = Site.absolute(link.attr("href")),
                    action = link.text().trim()
                )
            }
        )
    }

    /** The main 个人设置 form: 头像 dicebear pick, 简介 and 密码 share one submit. */
    fun profileForm(doc: Document): SiteForm? =
        formOf(doc, ".form-panel form:has(textarea[name=bio])")

    /** 改名. Its two fields live outside the form, attached by `form=`. */
    fun usernameForm(doc: Document): SiteForm? = formOf(doc, "form[action*=username_change]")

    /** 修改邮箱. */
    fun emailForm(doc: Document): SiteForm? = formOf(doc, "form[action*=user_review_email_change]")

    /**
     * Where 修改邮箱 asks for its code, read off the button the site put it on.
     *
     * Blank when the page renders a native captcha next to it: its answer is
     * minted in a browser, so the app has to hand that hop over rather than
     * post a request that cannot pass.
     */
    fun emailCodeUrl(doc: Document): String {
        val button = doc.selectFirst("[data-user-review-send-code]") ?: return ""
        if (button.closest("form")?.selectFirst("[data-native-captcha]") != null) return ""
        return Site.absolute(button.attr("data-url"))
    }

    /**
     * 头像 upload endpoint and its own token.
     *
     * This one is not a form: the panel carries `data-upload-url` and a loose
     * `_csrf`, and the site's script posts a hand-built FormData at it. So the
     * two field names it needs - `avatar_upload_action` and the file part's
     * `avatar` - are named by the caller, from what that script sends.
     */
    fun avatarUpload(doc: Document): AvatarUpload? {
        val panel = doc.selectFirst("[data-avatar-upload]") ?: return null
        val url = Site.absolute(panel.attr("data-upload-url"))
        if (url.isBlank()) return null
        return AvatarUpload(
            url = url,
            csrf = panel.selectFirst("input[name=_csrf]")?.attr("value").orEmpty()
        )
    }

    /** [avatarUpload]'s answer: where to post a new 头像, and with which token. */
    data class AvatarUpload(val url: String, val csrf: String)

    // ---- 称号馆 ---------------------------------------------------------------

    /**
     * The tier rides along as a class on the badge: `gacha-title-ur` and friends.
     * Read off the badge's own classes rather than a data attribute, which is how
     * the site's CSS itself distinguishes them.
     */
    private fun tierOf(badge: Element): String = badge.classNames()
        .firstOrNull { it.startsWith("gacha-title-") && it.length <= "gacha-title-".length + 3 }
        ?.removePrefix("gacha-title-")
        .orEmpty()

    /**
     * One `.gacha-title-badge`: icon, name, and a rarity slot that holds the tier
     * for a normal title and a serial number for a UR.
     */
    private fun titleBadge(badge: Element): GachaTitle? {
        val name = badge.textOf(".gacha-title-name").ifBlank {
            // A badge with no inner spans is just its own text.
            badge.text().trim().takeIf { badge.selectFirst("span") == null }.orEmpty()
        }
        if (name.isBlank()) return null
        val rarity = badge.textOf(".gacha-title-rarity")
        val serial = badge.textOf(".gacha-title-serial").ifBlank {
            // UR badges put the serial in the rarity slot as a bare number.
            rarity.takeIf { it.isNotBlank() && it.all(Char::isDigit) }.orEmpty()
        }
        return GachaTitle(
            name = name,
            serial = serial,
            tier = tierOf(badge).ifBlank { rarity.takeUnless { it.all(Char::isDigit) }.orEmpty() },
            icon = badge.textOf(".gacha-title-icon")
        )
    }

    /** A card in 我的称号 / 全部称号: a badge plus whatever surrounds it. */
    private fun titleCard(item: Element): GachaTitle? {
        val badge = item.selectFirst(".gacha-title-badge") ?: return null
        val base = titleBadge(badge) ?: return null
        return base.copy(
            description = item.textOf(".gacha-profile-desc"),
            meta = item.textOf(".gacha-profile-meta"),
            status = item.textOf(".gacha-status-tag"),
            equipped = item.selectFirst(".gacha-equipped-label") != null,
            expired = item.selectFirst(".gacha-expired-label") != null ||
                item.selectFirst(".gacha-status-expired") != null
        )
    }

    /**
     * A submit button, carried whole with the hidden fields of the form it sits
     * in. Reconstructing the post from field names we guessed is what broke
     * search; here the form is copied as it stands, `_csrf` included.
     */
    private fun actionOf(button: Element): GachaAction? {
        val form = button.closest("form") ?: return null
        val label = button.text().trim().ifBlank { button.attr("value").trim() }
        if (label.isBlank()) return null
        val fields = LinkedHashMap<String, String>()
        form.select("input[name], select[name]").forEach { input ->
            if (input.hasAttr("disabled")) return@forEach
            val type = input.attr("type").lowercase()
            if (type == "checkbox" || type == "radio") {
                if (!input.hasAttr("checked")) return@forEach
            }
            val value = if (input.tagName() == "select") {
                val option = input.selectFirst("option[selected]")
                    ?: input.selectFirst("option")
                option?.let { it.attr("value").ifBlank { it.text().trim() } }.orEmpty()
            } else {
                input.attr("value")
            }
            fields[input.attr("name")] = value
        }
        // The button itself is usually the thing that names the operation.
        val name = button.attr("name")
        if (name.isNotBlank()) fields[name] = button.attr("value")
        val action = form.attr("action").trim().ifBlank { Site.GACHA }
        return GachaAction(
            label = label,
            action = Site.absolute(action),
            fields = fields,
            enabled = !button.hasAttr("disabled")
        )
    }

    /**
     * 称号馆 (`/gacha`).
     *
     * Every section is optional: the page grows sub-tools (市场, 合成, 回收) for
     * some users and not others, and a signed-out read is a refusal rather than a
     * page. So each block is looked up on its own and simply comes back empty.
     */
    fun gacha(html: String): GachaPage {
        val doc = Jsoup.parse(html, Site.BASE)
        val page = doc.selectFirst(".gacha-center-page, .gacha-profile-page") ?: doc.body()
        val header = page.selectFirst(".gacha-center-header")

        val owned = page.select(".gacha-profile-grid .gacha-profile-item")
            .mapNotNull { titleCard(it) }
        val all = page.select(".gacha-all-grid .gacha-all-item")
            .mapNotNull { titleCard(it) }

        return GachaPage(
            heading = header?.textOf("h1, h2, .gacha-header-title").orEmpty()
                .ifBlank { doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim().orEmpty() },
            intro = header?.textOf("p").orEmpty(),
            stats = page.select(".gacha-center-stat").map { it.text().trim() }
                .filter { it.isNotBlank() },
            subStats = page.textOf(".gacha-sub-stats"),
            news = page.select(".gacha-good-news-item").map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            // The equipped one is marked inside the collection; the sidebar badge
            // is the fallback for a page that does not list it.
            equipped = owned.firstOrNull { it.equipped }
                ?: page.selectFirst(".gacha-sidebar-title .gacha-title-badge")?.let { titleBadge(it) },
            owned = owned,
            ownedHeading = page.textOf(".gacha-profile-collection-head strong")
                .ifBlank { page.textOf(".gacha-profile-titles h3") },
            rarities = page.select(".gacha-pool-grid .gacha-pool-rarity").map { r ->
                GachaRarity(
                    label = r.textOf(".gacha-pool-rarity-label"),
                    count = r.textOf(".gacha-pool-rarity-count"),
                    rate = r.textOf(".gacha-pool-rarity-rate")
                )
            }.filter { it.label.isNotBlank() },
            poolHeading = page.textOf(".gacha-pool-section h3"),
            all = all,
            allHeading = page.textOf(".gacha-all-titles h3"),
            pulls = page.select(".gacha-actions .gacha-pull-btn, .gacha-actions button")
                .mapNotNull { actionOf(it) }
                .distinctBy { it.label }
        )
    }

    /** What a pull returned. One card for a single, a grid of ten for the other. */
    fun gachaResult(html: String): GachaResult {
        val doc = Jsoup.parse(html, Site.BASE)
        val single = doc.select(".gacha-result-card").mapNotNull { card ->
            val name = card.textOf(".gacha-result-name")
            if (name.isBlank()) null else GachaTitle(
                name = name,
                tier = card.textOf(".gacha-result-rarity"),
                icon = card.textOf(".gacha-result-icon"),
                description = card.textOf(".gacha-result-desc"),
                meta = card.textOf(".gacha-result-quantity")
            )
        }
        val ten = doc.select(".gacha-pull-10-grid .gacha-pull-10-item").mapNotNull { item ->
            val name = item.textOf(".gacha-pull-10-name")
            if (name.isBlank()) null else GachaTitle(
                name = name,
                tier = item.textOf(".gacha-pull-10-rarity"),
                icon = item.textOf(".gacha-pull-10-icon"),
                meta = item.textOf(".gacha-pull-10-quantity")
            )
        }
        val titles = (single + ten).distinctBy { it.name + it.serial + it.meta }
        return GachaResult(
            message = doc.textOf(".form-error-panel p, .toast, .alert, .flash-message"),
            titles = titles,
            ok = titles.isNotEmpty() || doc.selectFirst(".form-error-panel") == null
        )
    }
}
