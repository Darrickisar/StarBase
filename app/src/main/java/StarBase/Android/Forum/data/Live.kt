package StarBase.Android.Forum.data

/**
 * Models for the live site. Everything here is produced by the Jsoup parsers in
 * [StarBase.Android.Forum.net.Parse] from the server-rendered HTML, so fields the page
 * does not show simply stay blank rather than being invented.
 */

/** A board in the top navigation. */
data class ForumRef(
    val id: Int,
    val name: String,
    val topicCount: String = ""
)

/** The gacha title badge a user carries. */
data class Title(
    val name: String,
    val serial: String = "",
    val tier: String = ""
)

/** One row in a topic list (home feed, board page, search results, profile). */
data class TopicCard(
    val id: Int,
    val title: String,
    val author: String,
    val authorId: Int = 0,
    val avatar: String = "",
    val forumId: Int = 0,
    val forumName: String = "",
    val replies: Int = 0,
    val timeText: String = "",
    val pinned: Boolean = false,
    val hot: Boolean = false,
    val stampText: String = "",
    /** 回帖 rows carry what was written; every other list leaves this blank. */
    val excerpt: String = ""
)

/**
 * Thresholds lifted from linux.sb's own front-end, so a label we draw means the
 * same thing it means on the site.
 */
object SiteRules {
    /**
     * `visibleLimit` in the site's quote_threads script: once a comment collects
     * more replies than this, the site folds the branch behind
     * 「展开剩余 N 条回复」. It is the only threshold the site itself applies to a
     * single comment, so it is what 热评 means here.
     */
    const val THREAD_COLLAPSE_LIMIT = 5
}

/** A single post inside a topic: the opening post, or a comment. */
data class Post(
    val id: String,
    val author: String,
    val authorId: Int = 0,
    val avatar: String = "",
    val group: String = "",
    val uid: String = "",
    val title: Title? = null,
    val timeText: String = "",
    /** 点赞打赏 count from this post's own donate reaction badge. */
    val likes: Int = 0,
    /** Rendered body, already reduced to our own block list. */
    val blocks: List<LiveBlock> = emptyList(),
    val isOpening: Boolean = false,
    val floor: Int = 0,
    /** The `id="post-<n>"` the site anchors quote links to; 0 when absent. */
    val replyId: Int = 0,
    /** Floor this comment answers, resolved the way the site threads quotes. */
    val parentFloor: Int = 0,
    /** Comments on this page that answer this one. */
    val replyCount: Int = 0
) {
    /**
     * 热评. The site ships no hot flag of its own for a comment, so this is its
     * own collapse threshold: a comment whose reply branch the site would fold
     * is one the site already treats as a long discussion.
     */
    val isHot: Boolean get() = replyCount > SiteRules.THREAD_COLLAPSE_LIMIT
}

/** A piece of post content. Images carry an absolute URL. */
data class LiveBlock(
    val type: Type,
    val text: String = "",
    val src: String = "",
    val href: String = ""
) {
    enum class Type { PARA, HEADING, QUOTE, CODE, IMAGE, LIST_ITEM, RULE, LINK }
}

/** A loaded topic page. */
data class TopicDetail(
    val id: Int,
    val title: String,
    val forumId: Int = 0,
    val forumName: String = "",
    val opening: Post?,
    val comments: List<Post> = emptyList(),
    val commentCount: Int = 0,
    val page: Int = 1,
    val lastPage: Int = 1,
    /** Set when the site says the comments need a session to be seen. */
    val commentsNeedLogin: Boolean = false,
    /** Token required to POST a reply; blank when not logged in. */
    val csrf: String = "",
    val canReply: Boolean = false,
    val related: List<TopicCard> = emptyList()
)

/** Site-wide counters shown on the home screen. */
data class SiteStats(
    val topics: String = "",
    val replies: String = "",
    val users: String = "",
    val online: String = "",
    val newestUser: String = ""
)

/** The home page, parsed in one pass. */
data class HomePage(
    val stats: SiteStats = SiteStats(),
    val forums: List<ForumRef> = emptyList(),
    val dailyHot: List<TopicCard> = emptyList(),
    val topics: List<TopicCard> = emptyList(),
    val lastPage: Int = 1,
    val me: Me? = null
)

/** A board page. */
data class ForumPage(
    val id: Int,
    val name: String,
    val topics: List<TopicCard> = emptyList(),
    val page: Int = 1,
    val lastPage: Int = 1
)

/** Who the session belongs to, as printed in the sidebar user card. */
data class Me(
    val name: String,
    val id: Int = 0,
    val avatar: String = "",
    val rank: String = "",
    val points: String = "",
    val title: Title? = null
)

/** One row of any leaderboard. */
data class RankRowData(
    val rank: Int,
    val name: String,
    val userId: Int = 0,
    val avatar: String = "",
    val group: String = "",
    val count: String = ""
)

/** A leaderboard, keyed by the tab it sits under. */
data class Board(
    val key: String,
    val label: String,
    val rows: List<RankRowData> = emptyList()
)

/** Unread counters from /notify. */
data class NotifyState(
    val notifications: Int = 0,
    val messages: Int = 0,
    val signedIn: Boolean = false
)

/** A notification row. */
data class NotifyItem(
    val text: String,
    val timeText: String = "",
    val href: String = "",
    val unread: Boolean = false,
    val actor: String = "",
    val avatar: String = ""
)

/** A direct-message conversation summary. */
data class Conversation(
    val id: String,
    val peer: String,
    val peerId: Int = 0,
    val avatar: String = "",
    val preview: String = "",
    val timeText: String = "",
    val unread: Int = 0
)

/** One message inside a conversation. */
data class DirectMessage(
    val body: String,
    val timeText: String = "",
    val fromMe: Boolean = false,
    val sender: String = ""
)

/** A user's profile page. */
data class Profile(
    val id: Int,
    val name: String,
    val avatar: String = "",
    val group: String = "",
    val title: Title? = null,
    val joinedText: String = "",
    val stats: List<Pair<String, String>> = emptyList(),
    val topics: List<TopicCard> = emptyList()
)

/**
 * The three lists a profile page can show. The site switches them with `?tab=`
 * and renders all three as the same `li.post-item` rows, so one parser covers
 * them - 回帖 additionally carries an excerpt of what was written.
 */
enum class ProfileTab(val key: String, val label: String) {
    TOPICS("topics", "主题"),
    REPLIES("replies", "回帖"),
    FAVORITES("favorites", "收藏")
}

// ---- 称号馆 -------------------------------------------------------------------

/**
 * One title in the gallery. `owned` covers both the 全部称号 grid (where nothing
 * is owned) and 我的称号 (where everything is), so the same row draws in both.
 */
data class GachaTitle(
    val name: String,
    val serial: String = "",
    val tier: String = "",
    val icon: String = "",
    val description: String = "",
    val meta: String = "",
    val status: String = "",
    val equipped: Boolean = false,
    val expired: Boolean = false
)

/** One rarity in the pool table: 名称, 数量, 出率. */
data class GachaRarity(
    val label: String,
    val count: String = "",
    val rate: String = ""
)

/**
 * 称号馆. The pull buttons are read as forms rather than reconstructed, the same
 * way search is - the site owns the field names and the `_csrf`.
 */
data class GachaPage(
    val heading: String = "",
    val intro: String = "",
    val stats: List<String> = emptyList(),
    val subStats: String = "",
    val news: List<String> = emptyList(),
    val equipped: GachaTitle? = null,
    val owned: List<GachaTitle> = emptyList(),
    val ownedHeading: String = "",
    val rarities: List<GachaRarity> = emptyList(),
    val poolHeading: String = "",
    val all: List<GachaTitle> = emptyList(),
    val allHeading: String = "",
    val pulls: List<GachaAction> = emptyList()
)

/** A submit button on the gacha page, carried whole so it can be re-posted. */
data class GachaAction(
    val label: String,
    val action: String,
    val fields: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)

/** What came back from a pull: one or ten titles, plus whatever it said. */
data class GachaResult(
    val message: String = "",
    val titles: List<GachaTitle> = emptyList(),
    val ok: Boolean = true
)
