package StarBase.Android.Forum.ui

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class IncomingContent(
    val url: String = "", val text: String = "", val search: String = "",
    val image: String = "", val token: Long = System.nanoTime()
)

data class TopicAddress(val id: Int, val page: Int = 1, val floor: Int = 0, val replyId: Int = 0)

object IncomingLinks {
    const val TEXT_LIMIT = 100_000
    const val SEARCH_LIMIT = 256

    fun selected(text: String): IncomingContent? {
        val query = text.take(TEXT_LIMIT).map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
            .joinToString("").trim().replace(Regex(" +"), " ").take(SEARCH_LIMIT)
            .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        return query.takeIf { it.isNotEmpty() }?.let { IncomingContent(search = it) }
    }

    fun siteUrl(raw: String): String? = raw.trim().toHttpUrlOrNull()?.takeIf {
        it.host == "linux.sb" && it.username.isEmpty() && it.password.isEmpty() && it.port in setOf(80, 443)
    }?.newBuilder()?.scheme("https")?.port(443)?.build()?.toString()

    fun topic(raw: String): TopicAddress? {
        val url = siteUrl(raw)?.toHttpUrlOrNull() ?: return null
        if (url.pathSegments.firstOrNull() != "topic" || url.pathSegments.size != 2) return null
        val id = url.pathSegments[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val page = url.queryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val floor = url.queryParameter("floor")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val reply = (url.queryParameter("replyid") ?: url.queryParameter("reply_id"))?.toIntOrNull()
            ?: url.fragment?.takeIf { it.startsWith("post-") }?.removePrefix("post-")?.toIntOrNull() ?: 0
        return TopicAddress(id, page, floor, reply.coerceAtLeast(0))
    }

    fun shared(text: String): IncomingContent? {
        val value = text.trim().take(TEXT_LIMIT)
        if (value.isBlank()) return null
        return siteUrl(value)?.let { IncomingContent(url = it) } ?: IncomingContent(text = value)
    }
}
