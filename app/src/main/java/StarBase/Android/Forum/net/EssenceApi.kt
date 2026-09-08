package StarBase.Android.Forum.net

import StarBase.Android.Forum.data.CommunityForm
import StarBase.Android.Forum.data.CommunityWriteResult
import StarBase.Android.Forum.data.EssenceDetail
import StarBase.Android.Forum.data.EssenceEntry
import StarBase.Android.Forum.data.EssencePage
import StarBase.Android.Forum.data.EssenceProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

class EssenceApi(private val http: CommunityHttp = CommunityHttp.Live) {
    suspend fun list(page: Int = 1): EssencePage = withContext(Dispatchers.IO) {
        parseList(http.get(listUrl(page)), page)
    }

    suspend fun detail(topicId: Int): EssenceDetail = withContext(Dispatchers.IO) {
        require(topicId > 0)
        parseDetail(http.get(Site.topic(topicId)), topicId)
    }

    suspend fun submit(form: CommunityForm, values: Map<String, String>): CommunityWriteResult = withContext(Dispatchers.IO) {
        val source = CommunityForms.sameOrigin(form.sourceUrl)
        val topicId = CommunityForms.id(source, "topic")
        if (topicId <= 0) throw SiteException("申精操作必须来自帖子页面", SiteException.Kind.PARSE)
        val doc = CommunityForms.document(http.get(source), source)
        val fresh = reviewForms(doc, source, topicId).firstOrNull { it.key == form.key }
            ?: throw SiteException("申精状态或操作权限已变化，请刷新后重试")
        val fields = CommunityForms.payload(fresh, values)
        ensureActive()
        CommunityForms.result(http.post(fresh.action, fields))
    }

    companion object {
        const val URL = "${Site.BASE}/topic_essence_review_list"

        fun listUrl(page: Int = 1): String {
            require(page > 0)
            return "$URL?p=$page"
        }

        fun parseProgress(text: String): EssenceProgress? {
            val match = Regex("(-?\\d+)\\s*/\\s*(\\d+)").find(text) ?: return null
            val points = match.groupValues[1].toIntOrNull() ?: return null
            val required = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return EssenceProgress(points, required)
        }

        fun parseList(html: String, page: Int = 1): EssencePage {
            val url = listUrl(page)
            val doc = CommunityForms.document(html, url)
            val selectedTab = doc.select("a.tab.active[href]").any {
                runCatching { CommunityForms.sameOrigin(it.attr("href")).toHttpUrl().encodedPath == "/topic_essence_review_list" }.getOrDefault(false)
            }
            if (!selectedTab) throw SiteException("站点未返回申精列表，请刷新或查看网页", SiteException.Kind.PARSE)
            val list = doc.selectFirst(".forum-main .post-list, .main-panel .post-list")
                ?: throw SiteException("未能读取申精列表，请刷新或查看网页", SiteException.Kind.PARSE)
            val progress = list.select(".post-item").associate { row ->
                CommunityForms.id(row.selectFirst(".post-title[href]")?.attr("href").orEmpty(), "topic") to
                    parseProgress(row.select(".topic-essence-review-progress").text())
            }
            val topics = Parse.feed(list.outerHtml()).filter { it.id in progress && it.id > 0 }.distinctBy { it.id }
            return EssencePage(topics.map { EssenceEntry(it, progress[it.id]) }, page, CommunityForms.lastPage(doc, url, page))
        }

        fun parseDetail(html: String, topicId: Int): EssenceDetail {
            require(topicId > 0)
            val url = Site.topic(topicId)
            val doc = CommunityForms.document(html, url)
            val title = doc.selectFirst(".post-content-title")?.text().orEmpty()
            val panel = doc.selectFirst(".topic-essence-review-panel")
            if (panel == null) {
                if (doc.selectFirst(".post-content-title, .post-topic-title") == null) throw SiteException("未能读取帖子或申精状态", SiteException.Kind.PARSE)
                return EssenceDetail(topicId, title, "unavailable", "暂无申精", null, emptyList(), listOf("当前帖子没有可用的申精操作"), emptyList())
            }
            val status = panel.attr("data-status")
            val statusLabel = panel.selectFirst(".topic-essence-review-status")?.text().orEmpty().ifBlank {
                when (status) {
                    "not_applied" -> "未申请"
                    "voting" -> "投票中"
                    "pending_approval" -> "待审批"
                    "approved" -> "已通过"
                    "rejected" -> "未通过"
                    else -> "申精状态"
                }
            }
            val progress = parseProgress(panel.select(".topic-essence-review-progress-row strong").text())
                ?: panel.selectFirst("progress")?.let { parseProgress("${it.attr("value")} / ${it.attr("max")}") }
            val metadata = panel.select(".topic-essence-review-meta > span, .topic-essence-review-pool-summary > div > span").map { it.text() }.filter(String::isNotBlank)
            val notes = panel.select(".topic-essence-review-note, .topic-essence-review-reasons li, .topic-essence-review-vote-note").map { it.text() }.filter(String::isNotBlank).distinct()
            return EssenceDetail(topicId, title, status, statusLabel, progress, metadata, notes, reviewForms(doc, url, topicId))
        }

        private fun reviewForms(doc: Document, url: String, topicId: Int): List<CommunityForm> =
            doc.select(".topic-essence-review-panel form").filter { form ->
                form.selectFirst("input[name=topic_id]")?.`val`() == topicId.toString()
            }.flatMap { CommunityForms.forms(it, url) }
    }
}
