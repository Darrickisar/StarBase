package StarBase.Android.Forum.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 本地屏蔽 / 折叠.
 *
 * A filter, not a store: what is kept is a handful of rules, and they are applied
 * to whatever was just fetched. Nothing about a hidden topic is written down, and
 * turning a rule off brings it straight back - there is no copy to go stale.
 *
 * Worth being straight about the overlap: linux.sb *does* have a keyword filter
 * of its own (`/home_keyword_filter_settings`, the 屏蔽设置 button on the home
 * feed). This is not a replacement for it and does not touch it. Two things the
 * site's version cannot do are the reason this exists anyway - it covers the home
 * feed only, and it filters by keyword only. This one also applies to board
 * pages, search results and profile lists, and it can fold *a person's* replies
 * inside a topic, which the site has no notion of at all.
 */
data class BlockRule(
    /** What to match. A keyword, or a user name for [Kind.AUTHOR]. */
    val value: String,
    val kind: Kind = Kind.KEYWORD,
    /** Fold-and-mark rather than hide outright. */
    val fold: Boolean = true,
    val enabled: Boolean = true
) {
    enum class Kind(val key: String, val label: String) {
        /** Matches a topic title, or the body of a reply. */
        KEYWORD("kw", "关键词"),

        /** Matches the author of a topic or a reply. */
        AUTHOR("who", "用户")
    }

    /** Case-insensitive substring, trimmed. Blank never matches. */
    fun matches(text: String): Boolean {
        val needle = value.trim()
        if (needle.isEmpty() || !enabled) return false
        return text.contains(needle, ignoreCase = true)
    }

    /** [AUTHOR] compares whole names: 「张三」 must not block 「张三丰」. */
    fun matchesAuthor(name: String): Boolean {
        val needle = value.trim()
        if (needle.isEmpty() || !enabled) return false
        return name.trim().equals(needle, ignoreCase = true)
    }
}

/** Why something was hidden, so the UI can say so instead of silently dropping it. */
data class Blocked<T>(
    val item: T,
    val rule: BlockRule
)

/** A list after filtering: what survived, and what was folded away. */
data class Filtered<T>(
    val visible: List<T>,
    val hidden: List<Blocked<T>>
) {
    val hiddenCount: Int get() = hidden.size
}

object Filters {

    /** More than this and the rule list is doing something other than filtering. */
    const val CAP = 60

    private val json = Json { ignoreUnknownKeys = true }

    fun add(existing: List<BlockRule>, rule: BlockRule): List<BlockRule> {
        val value = rule.value.trim()
        if (value.isEmpty()) return existing
        val normalised = rule.copy(value = value)
        // Same value and kind is the same rule; re-adding it updates the flags
        // rather than growing a second copy that shadows the first.
        val index = existing.indexOfFirst {
            it.kind == normalised.kind && it.value.equals(value, ignoreCase = true)
        }
        return if (index >= 0) {
            existing.toMutableList().apply { this[index] = normalised }
        } else {
            (existing + normalised).take(CAP)
        }
    }

    fun remove(existing: List<BlockRule>, rule: BlockRule): List<BlockRule> =
        existing.filterNot {
            it.kind == rule.kind && it.value.equals(rule.value.trim(), ignoreCase = true)
        }

    fun toggle(existing: List<BlockRule>, rule: BlockRule): List<BlockRule> =
        existing.map {
            if (it.kind == rule.kind && it.value.equals(rule.value.trim(), ignoreCase = true)) {
                it.copy(enabled = !it.enabled)
            } else {
                it
            }
        }

    /** The first rule that hides [card], or null when none does. */
    fun ruleFor(rules: List<BlockRule>, card: TopicCard): BlockRule? = rules.firstOrNull { rule ->
        when (rule.kind) {
            BlockRule.Kind.AUTHOR -> rule.matchesAuthor(card.author)
            // Title and excerpt, not the board name: a rule for 「抽奖」 should not
            // wipe out an entire board that happens to be called that.
            BlockRule.Kind.KEYWORD -> rule.matches(card.title) || rule.matches(card.excerpt)
        }
    }

    /** The first rule that folds [post], or null when none does. */
    fun ruleFor(rules: List<BlockRule>, post: Post): BlockRule? = rules.firstOrNull { rule ->
        when (rule.kind) {
            BlockRule.Kind.AUTHOR -> rule.matchesAuthor(post.author)
            BlockRule.Kind.KEYWORD -> rule.matches(post.plainText)
        }
    }

    /**
     * Filters a topic list.
     *
     * The opening post of a thread is never filtered here - this is for lists.
     * A rule set with nothing enabled returns the input untouched, including the
     * same list instance, so the common case costs nothing.
     */
    fun topics(rules: List<BlockRule>, cards: List<TopicCard>): Filtered<TopicCard> {
        if (rules.none { it.enabled }) return Filtered(cards, emptyList())
        val visible = ArrayList<TopicCard>(cards.size)
        val hidden = ArrayList<Blocked<TopicCard>>()
        cards.forEach { card ->
            val rule = ruleFor(rules, card)
            if (rule == null) visible += card else hidden += Blocked(card, rule)
        }
        return Filtered(visible, hidden)
    }

    /**
     * Marks which replies are folded, keeping every one of them in place.
     *
     * Replies are folded rather than removed: a thread with #12 missing reads as
     * though the site lost it, and #13 answering #12 makes no sense on its own.
     * The screen draws a one-line stub the reader can open.
     */
    fun posts(rules: List<BlockRule>, posts: List<Post>): Map<String, BlockRule> {
        if (rules.none { it.enabled }) return emptyMap()
        val folded = HashMap<String, BlockRule>()
        posts.forEach { post ->
            // The opening post is the thread; folding it would leave a blank page.
            if (post.isOpening) return@forEach
            ruleFor(rules, post)?.let { folded[post.id] = it }
        }
        return folded
    }

    // ---- storage -------------------------------------------------------------

    fun encode(rules: List<BlockRule>): String = buildJsonArray {
        rules.forEach { rule ->
            add(
                buildJsonObject {
                    put("v", JsonPrimitive(rule.value))
                    put("k", JsonPrimitive(rule.kind.key))
                    if (!rule.fold) put("hide", JsonPrimitive(1))
                    if (!rule.enabled) put("off", JsonPrimitive(1))
                }
            )
        }
    }.toString()

    fun decode(raw: String): List<BlockRule> {
        if (raw.isBlank()) return emptyList()
        val array: JsonArray = runCatching {
            json.parseToJsonElement(raw).jsonArray
        }.getOrNull() ?: return emptyList()

        return array.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val value = row.text("v")
            if (value.isBlank()) return@mapNotNull null
            BlockRule(
                value = value,
                kind = BlockRule.Kind.entries.firstOrNull { it.key == row.text("k") }
                    ?: BlockRule.Kind.KEYWORD,
                fold = row.int("hide") != 1,
                enabled = row.int("off") != 1
            )
        }.take(CAP)
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    private fun JsonObject.int(key: String): Int =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 0
}
