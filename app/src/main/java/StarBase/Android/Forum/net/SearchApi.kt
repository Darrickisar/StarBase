package StarBase.Android.Forum.net

import StarBase.Android.Forum.data.TopicCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class SearchPage(
    val topics: List<TopicCard>,
    val page: Int = 1,
    val nextPage: Int? = null
)

/** Same fresh-form flow as Api.search, retaining the site's pagination controls. */
object SearchApi {
    suspend fun search(query: String, page: Int = 1): SearchPage = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        SearchReader(
            get = { Net.getText(it) },
            post = { url, body -> Net.postForm(url, body, ajax = false) },
            checkpoint = { context.ensureActive() }
        ).search(query, page)
    }
}

/** Injectable transport keeps fixture tests entirely offline. */
internal class SearchReader(
    private val get: (String) -> String,
    private val post: (String, FormBody) -> String,
    private val checkpoint: () -> Unit = {}
) {
    fun search(query: String, page: Int = 1): SearchPage {
        require(page > 0)
        val q = query.trim()
        require(q.isNotEmpty())
        checkpoint()
        val entry = get(Site.SEARCH)
        checkpoint()
        SearchPages.check(entry)
        val form = Parse.searchForm(entry)
            ?: throw SiteException("站点没有返回搜索表单", SiteException.Kind.PARSE)

        val pageField = form.pageField.ifBlank {
            listOf("p", "page").firstOrNull { form.fields[it]?.toIntOrNull() != null }.orEmpty()
        }
        val directPage = if (pageField.isNotBlank()) page else 1
        val firstHtml = submit(form, q, form.queryField, directPage, pageField)
        val first = SearchPages.parse(firstHtml, directPage)
        if (directPage == page) return first
        if (first.nextPage == null) return SearchPage(emptyList(), page)

        // GET /search may have no page field. Discover it by submitting the fresh
        // query, then use the returned form and token rather than inventing `p`.
        val doc = Jsoup.parse(firstHtml, Site.SEARCH)
        val target = SearchPages.controls(doc).firstOrNull { it.page == page }
            ?: SearchPages.controls(doc).firstOrNull { it.page > first.page }
            ?: return SearchPage(emptyList(), page)
        val element = target.element
        val html = if (element.tagName() == "a") {
            val url = siteUrl(element.absUrl("href"))
            val field = listOf("p", "page").firstOrNull { url.queryParameter(it)?.toIntOrNull() != null }
                ?: throw SiteException("无法读取搜索分页链接", SiteException.Kind.PARSE)
            checkpoint()
            get(url.newBuilder().setQueryParameter(field, page.toString()).build().toString())
                .also { checkpoint() }
        } else {
            val owner = element.closest("form")
                ?: throw SiteException("站点没有返回搜索分页表单", SiteException.Kind.PARSE)
            val pageForm = Parse.searchForm(owner.clone().addClass("search-page-form").outerHtml())
                ?: throw SiteException("无法读取搜索分页表单", SiteException.Kind.PARSE)
            submit(pageForm, q, form.queryField, page, element.attr("name"))
        }
        return SearchPages.parse(html, page)
    }

    private fun submit(
        form: Parse.SearchForm,
        query: String,
        queryField: String,
        page: Int,
        pageField: String
    ): String {
        val fields = LinkedHashMap(form.fields)
        fields[queryField] = query
        if (pageField.isNotBlank()) fields[pageField] = page.toString()
        val url = siteUrl(form.action)
        checkpoint()
        val html = if (form.post) {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()
            post(url.toString(), body)
        } else {
            val target = url.newBuilder().apply {
                fields.forEach { (name, value) -> setQueryParameter(name, value) }
            }.build()
            get(target.toString())
        }
        checkpoint()
        return html
    }

    private fun siteUrl(value: String): HttpUrl {
        val base = Site.BASE.toHttpUrl()
        val url = base.resolve(value)
        if (url == null || url.host != base.host || url.port != base.port || url.scheme != base.scheme) {
            throw SiteException("搜索表单地址无效", SiteException.Kind.PARSE)
        }
        return url
    }
}

internal object SearchPages {
    internal data class PageControl(val page: Int, val element: Element)

    fun check(html: String) {
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) {
            throw SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)
        }
    }

    fun parse(html: String, requestedPage: Int): SearchPage {
        check(html)
        val doc = Jsoup.parse(html, Site.SEARCH)
        if (doc.selectFirst("body.search-page, .search-page-summary, .search-page-pagination, form.search-page-form") == null) {
            throw SiteException("无法读取搜索结果，请重试", SiteException.Kind.PARSE)
        }
        val current = doc.select(".search-page-pagination .active, .search-page-pagination [aria-current=page]")
            .firstNotNullOfOrNull { it.text().trim().toIntOrNull() } ?: requestedPage
        // A shrinking result set can redirect an out-of-range page to page one.
        if (current != requestedPage) return SearchPage(emptyList(), requestedPage)
        val topics = Parse.feed(html).distinctBy { it.id }
        val next = if (topics.isNotEmpty() && current < Int.MAX_VALUE && controls(doc).any { it.page > current }) {
            current + 1
        } else null
        return SearchPage(topics, current, next)
    }

    fun controls(doc: Document): List<PageControl> = doc.select(
        ".search-page-pagination [name=p], .search-page-pagination [name=page], .search-page-pagination a[href]"
    ).mapNotNull { element ->
        if ((listOf(element) + element.parents()).any {
                it.hasAttr("disabled") || it.hasClass("disabled") || it.attr("aria-disabled") == "true"
            }) return@mapNotNull null
        val owner = element.closest("form")
        if (element.tagName() == "input" && owner != null) {
            val buttons = owner.select("button, input[type=submit]")
            if (buttons.isNotEmpty() && buttons.all { it.hasAttr("disabled") || it.attr("aria-disabled") == "true" }) {
                return@mapNotNull null
            }
        }
        val value = if (element.tagName() == "a") {
            Site.SEARCH.toHttpUrl().resolve(element.attr("href"))?.let { url ->
                url.queryParameter("p")?.toIntOrNull() ?: url.queryParameter("page")?.toIntOrNull()
            }
        } else element.attr("value").toIntOrNull()
        value?.takeIf { it > 0 }?.let { PageControl(it, element) }
    }
}
