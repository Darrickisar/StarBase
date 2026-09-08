package StarBase.Android.Forum.data

import StarBase.Android.Forum.net.Site
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class NotificationFilter(val label: String) {
    ALL("全部"), UNREAD("未读"), MENTIONS("提及")
}

data class NotificationGroup(
    val key: String,
    val topicId: Int?,
    val items: List<NotifyItem>
) {
    val unreadCount: Int get() = items.count { it.unread }
}

object NotificationGroups {
    fun matches(item: NotifyItem, filter: NotificationFilter): Boolean = when (filter) {
        NotificationFilter.ALL -> true
        NotificationFilter.UNREAD -> item.unread
        NotificationFilter.MENTIONS -> item.kind.trim() == "提及"
    }

    fun topicId(href: String): Int? {
        val base = Site.BASE.toHttpUrlOrNull() ?: return null
        val url = base.resolve(href) ?: return null
        if (url.host != base.host || url.port != base.port || url.scheme != base.scheme) return null
        val parts = url.pathSegments.filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0] != "topic") return null
        return parts[1].toIntOrNull()?.takeIf { it > 0 }
    }

    /** Keep server order. Missing topic links are independent events, not one synthetic topic. */
    fun group(items: List<NotifyItem>, filter: NotificationFilter = NotificationFilter.ALL): List<NotificationGroup> {
        val grouped = linkedMapOf<String, MutableList<NotifyItem>>()
        val topicIds = mutableMapOf<String, Int?>()
        items.forEachIndexed { index, item ->
            if (!matches(item, filter)) return@forEachIndexed
            val topic = topicId(item.href)
            val key = topic?.let { "topic:$it" } ?: "event:$index:${item.href}:${item.text}"
            grouped.getOrPut(key) { mutableListOf() }.add(item)
            topicIds[key] = topic
        }
        return grouped.map { (key, rows) -> NotificationGroup(key, topicIds[key], rows.toList()) }
    }
}
