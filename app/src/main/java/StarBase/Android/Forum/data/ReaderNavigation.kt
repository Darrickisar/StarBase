package StarBase.Android.Forum.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ReaderRow(val post: Post, val depth: Int = 0, val childCount: Int = 0)

data class ReaderContext(
    val posts: List<Post>,
    val missingFloor: Int? = null,
    val hasCycle: Boolean = false
)

/** A loaded-scope index. Missing parents stay roots; cycles lose one display edge. */
class ReaderThreadIndex(opening: Post?, comments: List<Post>) {
    private val posts = comments.distinctBy { it.id }
    private val byFloor = (listOfNotNull(opening) + posts)
        .filter { it.floor > 0 }.associateBy { it.floor }
    private val byId = posts.associateBy { it.id }
    private val parents = mutableMapOf<String, String>()
    private val children: Map<String, List<Post>>

    init {
        posts.forEach { post ->
            val parent = byFloor[post.parentFloor]
            if (parent != null && parent.id != post.id && parent.id in byId) {
                parents[post.id] = parent.id
            }
        }
        val checked = mutableSetOf<String>()
        for (post in posts) {
            val path = mutableSetOf<String>()
            var cursor: String? = post.id
            while (cursor != null && cursor !in checked) {
                if (!path.add(cursor)) {
                    parents.remove(cursor)
                    break
                }
                cursor = parents[cursor]
            }
            checked.addAll(path)
        }
        children = posts.filter { it.id in parents }.groupBy { parents.getValue(it.id) }
    }

    fun contextFor(post: Post): ReaderContext {
        val chain = mutableListOf<Post>()
        val seen = mutableSetOf(post.floor)
        var floor = post.parentFloor
        while (floor > 0) {
            if (!seen.add(floor)) return ReaderContext(chain.asReversed(), hasCycle = true)
            val parent = byFloor[floor]
                ?: return ReaderContext(chain.asReversed(), missingFloor = floor)
            chain += parent
            floor = parent.parentFloor
        }
        return ReaderContext(chain.asReversed())
    }

    fun ancestorIds(post: Post): Set<String> {
        val ancestors = mutableSetOf<String>()
        var cursor = parents[post.id]
        while (cursor != null && ancestors.add(cursor)) cursor = parents[cursor]
        return ancestors
    }

    fun rows(branches: Boolean, expanded: Set<String>): List<ReaderRow> {
        if (!branches) return posts.map { ReaderRow(it, childCount = children[it.id].orEmpty().size) }
        val result = mutableListOf<ReaderRow>()
        val pending = ArrayDeque<Pair<Post, Int>>()
        posts.filter { it.id !in parents }.asReversed().forEach { pending.addLast(it to 0) }
        while (pending.isNotEmpty()) {
            val (post, depth) = pending.removeLast()
            val replies = children[post.id].orEmpty()
            result += ReaderRow(post, depth, replies.size)
            if (post.id in expanded) {
                replies.asReversed().forEach { pending.addLast(it to depth + 1) }
            }
        }
        return result
    }
}

fun isReaderAuthor(post: Post, opening: Post?): Boolean {
    if (opening == null) return false
    if (opening.authorId > 0) return post.authorId == opening.authorId
    if (opening.uid.isNotBlank()) return post.uid == opening.uid
    return opening.author.isNotBlank() && post.author == opening.author
}

/** Do not infer a floor's page from reply count or from another page's highest floor. */
fun nextReaderPage(loadedPages: Set<Int>, lastPage: Int): Int? =
    (1..lastPage.coerceAtLeast(1)).firstOrNull { it !in loadedPages }

data class ReaderSection(val blockIndex: Int, val blocks: List<LiveBlock>) {
    val key: String get() = "reader-section:$blockIndex"
}

data class ReaderHeading(val blockIndex: Int, val text: String, val level: Int = 1) {
    val key: String get() = "reader-section:$blockIndex"
}

fun readerSections(blocks: List<LiveBlock>): List<ReaderSection> {
    if (blocks.isEmpty()) return emptyList()
    val starts = (listOf(0) + blocks.indices.filter { blocks[it].type == LiveBlock.Type.HEADING }).distinct()
    return starts.mapIndexed { index, start ->
        ReaderSection(start, blocks.subList(start, starts.getOrElse(index + 1) { blocks.size }))
    }
}

fun readerHeadings(blocks: List<LiveBlock>): List<ReaderHeading> = blocks.mapIndexedNotNull { index, block ->
    if (block.type == LiveBlock.Type.HEADING && block.text.isNotBlank()) {
        ReaderHeading(index, block.text.trim(), block.headingLevel.coerceIn(1, 6))
    } else null
}

fun readerPostKey(post: Post): String = "reader-post:${post.id}"

fun readerListKeys(opening: Post?, sections: List<ReaderSection>, rows: List<ReaderRow>): List<String> = buildList {
    add("head")
    addAll(sections.map { it.key })
    if (opening != null) add("body-actions")
    add("comments-header")
    addAll(rows.map { readerPostKey(it.post) })
}

/** Only real, visible body rows are eligible; list headers and prefetched rows are not. */
fun readerVisibleFloor(visibleKeys: List<String>, readablePosts: Map<String, Post>, enabled: Boolean): Int =
    if (!enabled) 0 else visibleKeys.maxOfOrNull { readablePosts[it]?.floor ?: 0 } ?: 0

data class ReaderResource(val url: HttpUrl, val label: String, val floor: Int, val opening: Boolean)

fun readerResourceUrl(raw: String, topicId: Int): HttpUrl? {
    val value = raw.trim()
    if (value.isEmpty() || value.any { it.isISOControl() || it == '\\' }) return null
    val base = "https://linux.sb/topic/$topicId".toHttpUrl()
    val url = base.resolve(value) ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.takeIf { it.scheme == "http" || it.scheme == "https" }
}

/** Collect only parsed links and media sources from bodies actually in memory. */
fun readerResources(topicId: Int, opening: Post?, comments: List<Post>): List<ReaderResource> {
    val found = linkedMapOf<HttpUrl, ReaderResource>()
    for (post in listOfNotNull(opening) + comments) {
        fun add(raw: String, label: String) {
            val url = readerResourceUrl(raw, topicId) ?: return
            found.putIfAbsent(url, ReaderResource(url, label.trim().ifBlank { url.host }, post.floor, post.isOpening))
        }
        for (block in post.blocks) {
            if (block.href.isNotBlank()) add(block.href, block.text)
            if (block.type == LiveBlock.Type.IMAGE && block.src.isNotBlank()) add(block.src, block.text.ifBlank { "图片" })
            if (block.type == LiveBlock.Type.VIDEO && block.src.isNotBlank()) add(block.src, block.text.ifBlank { "视频" })
            for (link in block.links) {
                val start = link.start.coerceIn(0, block.text.length)
                val end = link.end.coerceIn(start, block.text.length)
                add(link.href, block.text.substring(start, end))
            }
        }
    }
    return found.values.toList()
}
