package StarBase.Android.Forum.net

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import StarBase.Android.Forum.data.Board
import StarBase.Android.Forum.data.Conversation
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

        val stamps = li.select(".topic-stamp-badge, .community-lottery-title-status")
        val stampText = stamps.joinToString(" ") { it.text().trim() }.trim()

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
            pinned = li.selectFirst(".topic-stamp-top, .post-tag-top") != null ||
                stampText.contains("置顶"),
            hot = li.selectFirst(".topic-stamp-hot") != null,
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
    private fun blocksOf(content: Element): List<LiveBlock> {
        // Long posts are wrapped in a fold container; the real body is inside.
        val root = content.selectFirst("[data-long-content-fold]") ?: content
        val out = mutableListOf<LiveBlock>()

        fun emitImage(img: Element) {
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            if (src.isBlank()) return
            out += LiveBlock(LiveBlock.Type.IMAGE, src = Site.absolute(src), text = img.attr("alt"))
        }

        fun walk(el: Element) {
            when (el.tagName().lowercase()) {
                "p", "div" -> {
                    // A paragraph that only wraps an image should become an image.
                    val imgs = el.select("> img, > a > img")
                    val text = el.text().trim()
                    if (imgs.isNotEmpty()) {
                        imgs.forEach { emitImage(it) }
                        if (text.isNotBlank()) out += LiveBlock(LiveBlock.Type.PARA, text)
                    } else if (el.tagName() == "p") {
                        if (text.isNotBlank()) out += LiveBlock(LiveBlock.Type.PARA, text)
                    } else {
                        el.children().forEach { walk(it) }
                        if (el.children().isEmpty() && text.isNotBlank()) {
                            out += LiveBlock(LiveBlock.Type.PARA, text)
                        }
                    }
                }
                "h1", "h2", "h3", "h4", "h5", "h6" ->
                    el.text().trim().takeIf { it.isNotBlank() }?.let {
                        out += LiveBlock(LiveBlock.Type.HEADING, it)
                    }
                "blockquote" ->
                    el.text().trim().takeIf { it.isNotBlank() }?.let {
                        out += LiveBlock(LiveBlock.Type.QUOTE, it)
                    }
                "pre" ->
                    el.wholeText().trimEnd().takeIf { it.isNotBlank() }?.let {
                        out += LiveBlock(LiveBlock.Type.CODE, it)
                    }
                "ul", "ol" -> el.select("> li").forEach { li ->
                    li.text().trim().takeIf { it.isNotBlank() }?.let {
                        out += LiveBlock(LiveBlock.Type.LIST_ITEM, it)
                    }
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
                        el.text().trim().takeIf { it.isNotBlank() }?.let {
                            out += LiveBlock(LiveBlock.Type.PARA, it)
                        }
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

    fun boards(html: String): List<Board> {
        val doc = Jsoup.parse(html, Site.BASE)
        val out = mutableListOf<Board>()

        doc.select("[data-leaderboard], .leaderboard-card, .leaderboard-panel").forEach { panel ->
            val key = panel.attr("data-leaderboard").ifBlank { panel.id() }
            val label = panel.selectFirst(".leaderboard-title, .quick-title, h2, h3")
                ?.text()?.trim().orEmpty()
            val rows = panel.select("li, tr").mapIndexedNotNull { i, row ->
                val nameLink = row.selectFirst("a[href*=/user/]") ?: return@mapIndexedNotNull null
                val name = nameLink.text().trim().ifBlank {
                    row.selectFirst("img.avatar-img")?.attr("alt")?.trim().orEmpty()
                }
                if (name.isBlank()) return@mapIndexedNotNull null
                RankRowData(
                    rank = firstInt(row.textOf(".leaderboard-rank, .rank")).takeIf { it > 0 } ?: (i + 1),
                    name = name,
                    userId = idFrom(nameLink.attr("href"), "user"),
                    avatar = row.selectFirst("img.avatar-img")?.attrUrl("src").orEmpty(),
                    group = row.textOf(".user-uid-badge-group-name"),
                    count = row.textOf(".leaderboard-count, .count, .value")
                )
            }
            if (rows.isNotEmpty()) {
                out += Board(key = key.ifBlank { "board${out.size}" }, label = label, rows = rows)
            }
        }
        return out
    }

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

    fun conversations(html: String): List<Conversation> {
        val doc = Jsoup.parse(html, Site.BASE)
        return doc.select(".dm-item, .conversation-item, ul.dm-list > li").mapNotNull { li ->
            val link = li.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            val peerLink = li.selectFirst("a[href*=/user/]")
            Conversation(
                id = Regex("""(\d+)""").find(href.substringAfterLast('/'))?.value.orEmpty(),
                peer = li.textOf(".dm-peer, .peer-name").ifBlank {
                    peerLink?.text()?.trim()
                        ?: li.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                },
                peerId = idFrom(peerLink?.attr("href").orEmpty(), "user"),
                avatar = li.selectFirst("img")?.attrUrl("src").orEmpty(),
                preview = li.textOf(".dm-preview, .preview, .last-message"),
                timeText = li.textOf(".dm-time, .time, [data-performance-time]"),
                unread = firstInt(li.textOf(".dm-unread, .unread-count, .badge"))
            )
        }.filter { it.peer.isNotBlank() }
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
