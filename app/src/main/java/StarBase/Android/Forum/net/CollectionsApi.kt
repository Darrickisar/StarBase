package StarBase.Android.Forum.net

import StarBase.Android.Forum.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Injectable only to permit offline action tests; production shares Net's cookies and network stack. */
interface CommunityHttp {
    suspend fun get(url: String): String
    suspend fun post(url: String, fields: List<Pair<String, String>>): String

    object Live : CommunityHttp {
        private val client by lazy {
            Net.client.newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build()
        }

        private fun request(url: String) = Request.Builder().url(CommunityForms.sameOrigin(url))
            .header("User-Agent", Net.userAgent()).header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", "${Site.BASE}/")

        override suspend fun get(url: String): String {
            var target = CommunityForms.sameOrigin(url)
            repeat(6) {
                val result = execute(request(target).get().build())
                if (result.second.isBlank()) return result.first
                target = CommunityForms.sameOrigin(result.second, target)
            }
            throw SiteException("站点跳转次数过多", SiteException.Kind.PARSE)
        }

        override suspend fun post(url: String, fields: List<Pair<String, String>>): String {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (name, value) -> addFormDataPart(name, value) }
            }.build()
            val result = execute(request(url).header("Origin", Site.BASE)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/html;q=0.9").post(body).build())
            if (result.second.isNotBlank()) {
                val redirect = CommunityForms.sameOrigin(result.second, url)
                if (redirect.toHttpUrl().encodedPath == "/login") throw CommunityForms.loginError()
                throw SiteException("提交结果未能确认，请刷新核对后再试", SiteException.Kind.PARSE)
            }
            return result.first
        }

        private suspend fun execute(request: Request): Pair<String, String> = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkError(request))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            continuation.resume(readResponse(request, response))
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(if (e is java.io.IOException) networkError(request) else e)
                        }
                    }
                }
            })
        }

        private fun readResponse(request: Request, response: Response): Pair<String, String> {
            if (response.code == 401) throw CommunityForms.loginError()
            if (response.code == 403) throw SiteException("站点拒绝访问，当前账号可能没有权限")
            if (response.code == 429) throw SiteException("请求太频繁，请稍后再试")
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location").orEmpty()
                if (location.isBlank()) throw SiteException("站点返回了无效跳转", SiteException.Kind.PARSE)
                CommunityForms.sameOrigin(location, request.url.toString())
                return "" to location
            }
            if (!response.isSuccessful) throw SiteException("服务器返回 ${response.code}")
            return response.body?.string().orEmpty() to ""
        }

        private fun networkError(request: Request) = SiteException(
            if (request.method == "POST") "网络中断，提交结果未能确认，请刷新核对后再试" else "网络请求失败，请重试",
            SiteException.Kind.NETWORK
        )
    }
}

/** Shared by these two features only. Every action is rediscovered immediately before submission. */
internal object CommunityForms {
    fun loginError() = SiteException("登录状态已失效，请重新登录", SiteException.Kind.AUTH)

    fun sameOrigin(raw: String, base: String = Site.BASE): String {
        if (raw.isBlank() || raw.any { it.code < 32 } || '\\' in raw) {
            throw SiteException("站点链接无效", SiteException.Kind.PARSE)
        }
        val url = base.toHttpUrl().resolve(raw)
            ?: throw SiteException("站点链接无效", SiteException.Kind.PARSE)
        if (url.scheme != "https" || url.host != "linux.sb" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw SiteException("已拒绝非本站链接", SiteException.Kind.PARSE)
        }
        return url.newBuilder().fragment(null).build().toString()
    }

    fun document(html: String, url: String): Document {
        Parse.refusal(html)?.let { throw it }
        if (Parse.isLoginPage(html)) throw loginError()
        return Jsoup.parse(html, sameOrigin(url))
    }

    fun lastPage(doc: Document, url: String, page: Int): Int {
        val base = sameOrigin(url).toHttpUrl()
        return maxOf(page, doc.select(".pagination a[href], a[rel=next]").mapNotNull { link ->
            val target = runCatching { sameOrigin(link.attr("href"), url).toHttpUrl() }.getOrNull()
                ?: return@mapNotNull null
            if (target.encodedPath != base.encodedPath || target.queryParameter("tab") != base.queryParameter("tab")) null
            else target.queryParameter("p")?.toIntOrNull()?.takeIf { it > 0 }
        }.maxOrNull() ?: page)
    }

    fun id(raw: String, section: String, base: String = Site.BASE): Int = runCatching {
        Regex("^/$section/(\\d+)/?$").matchEntire(sameOrigin(raw, base).toHttpUrl().encodedPath)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    }.getOrDefault(0)

    fun topicId(input: String): Int {
        val trimmed = input.trim()
        val number = trimmed.toIntOrNull()?.takeIf { it > 0 } ?: id(trimmed, "topic")
        if (number <= 0) throw SiteException("请输入有效的帖子编号或 linux.sb 帖子链接")
        return number
    }

    private fun disabled(element: Element): Boolean = element.hasAttr("disabled") ||
        element.parents().any { it.tagName() == "fieldset" && it.hasAttr("disabled") }

    fun forms(form: Element, source: String): List<CommunityForm> {
        val action = runCatching { sameOrigin(form.attr("action").ifBlank { source }, source) }.getOrNull()
            ?: return emptyList()
        if (!form.attr("method").equals("post", true)) return emptyList()
        val elements = form.select("input[name], textarea[name], select[name]").filterNot(::disabled)
        val hidden = elements.filter { it.attr("type") == "hidden" || it.hasAttr("readonly") }
            .map { it.attr("name") to it.`val`() }
        val fields = mutableListOf<CommunityField>()
        for (element in elements) {
            val name = element.attr("name")
            val type = element.attr("type").lowercase()
            if (name.isBlank() || type in listOf("hidden", "submit", "button", "reset") || element.hasAttr("readonly")) continue
            if (fields.any { it.name == name }) continue
            val labelElement = element.parents().firstOrNull { it.tagName() == "label" }
                ?: form.select("label[for]").firstOrNull { it.attr("for") == element.id() }
            val label = labelElement?.clone()?.apply { select("input, textarea, select, button").remove() }?.text()
                ?: element.attr("placeholder").ifBlank {
                    when (name) { "name" -> "专辑名称"; "description" -> "专辑简介"; "reason" -> "理由"; else -> name }
                }
            val group = elements.filter { it.attr("name") == name }
            val options = when {
                element.tagName() == "select" -> element.select("option").filterNot { disabled(it) || it.parent()?.hasAttr("disabled") == true }
                    .map { CommunityOption(it.`val`(), it.text()) }
                type == "radio" -> group.map { radio -> CommunityOption(radio.`val`(), radio.parent()?.text().orEmpty()) }
                else -> emptyList()
            }
            val fieldType = when {
                element.tagName() == "textarea" -> CommunityFieldType.MULTILINE
                type == "checkbox" -> CommunityFieldType.CHECKBOX
                type == "number" -> CommunityFieldType.NUMBER
                element.tagName() == "select" || type == "radio" -> CommunityFieldType.CHOICE
                else -> CommunityFieldType.TEXT
            }
            val value = when {
                type == "radio" -> group.firstOrNull { it.hasAttr("checked") }?.`val`().orEmpty()
                type == "checkbox" -> element.attr("value").ifBlank { "on" }
                element.tagName() == "select" -> element.selectFirst("option[selected]")?.`val`() ?: options.firstOrNull()?.value.orEmpty()
                else -> element.`val`()
            }
            fields += CommunityField(name, label, fieldType, value, options,
                group.any { it.hasAttr("required") },
                element.attr("maxlength").toIntOrNull() ?: if (name == "name") 80 else null,
                element.attr("min").toDoubleOrNull(), element.attr("max").toDoubleOrNull(), element.hasAttr("checked"))
        }
        val unsupported = form.selectFirst("input[type=file], input[type=password], select[multiple], .cf-turnstile, [data-sitekey]") != null ||
            form.attr("data-no-ajax") == "1" || elements.any { it.hasAttr("form") && it.attr("form") != form.id() } ||
            (form.id().isNotBlank() && form.ownerDocument()?.select("[form]")?.any { it.attr("form") == form.id() && !it.parents().contains(form) } == true) ||
            elements.filter { it.attr("type") == "checkbox" }.groupBy { it.attr("name") }.any { it.value.size > 1 }
        val submitters = form.select("button[type=submit], button:not([type]), input[type=submit]")
        return submitters.map { button ->
            val submitter = button.attr("name").takeIf(String::isNotBlank)?.let { it to button.attr("value").ifBlank { "1" } }
            val identity = hidden.filter { it.first in listOf("topic_id", "collection_id", "topic_collections_action", "action", "decision") }
            val key = listOf(action, form.id(), identity.toString(), submitter.toString(), button.text()).joinToString("|")
            val hasCsrf = hidden.any { it.first == "_csrf" && it.second.isNotBlank() }
            val enabled = hasCsrf && !unsupported && !disabled(button)
            CommunityForm(key, source, action, button.text().ifBlank { button.`val`() }.ifBlank { "提交" }, hidden,
                fields, submitter, enabled, when {
                    unsupported -> "此表单需要在网页中完成"
                    !hasCsrf -> "页面验证信息缺失，请刷新"
                    !enabled -> "当前账号暂不能执行此操作"
                    else -> ""
                }, button.attr("data-confirm").ifBlank { form.attr("data-confirm") })
        }
    }

    fun payload(form: CommunityForm, values: Map<String, String>): List<Pair<String, String>> {
        sameOrigin(form.sourceUrl)
        sameOrigin(form.action)
        if (!form.enabled) throw SiteException(form.unavailableReason.ifBlank { "当前账号没有操作权限" })
        if (form.hidden.none { it.first == "_csrf" && it.second.isNotBlank() }) throw SiteException("页面验证信息已失效，请刷新")
        if (values.keys.any { key -> form.fields.none { it.name == key } }) throw SiteException("表单已变化，请重新打开", SiteException.Kind.PARSE)
        return buildList {
            addAll(form.hidden)
            form.fields.forEach { field ->
                val value = values[field.name] ?: form.initialValues()[field.name].orEmpty()
                if (field.required && value.isBlank()) throw SiteException("请填写${field.label}")
                if (field.maxLength != null && value.codePointCount(0, value.length) > field.maxLength) {
                    throw SiteException("${field.label}不能超过 ${field.maxLength} 个字符")
                }
                if (field.type == CommunityFieldType.CHOICE && field.options.none { it.value == value }) throw SiteException("请选择有效的${field.label}")
                if (field.type == CommunityFieldType.CHECKBOX && value.isNotEmpty() && value != field.value) throw SiteException("表单选项无效")
                if (field.type == CommunityFieldType.NUMBER && value.isNotBlank()) {
                    val number = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw SiteException("${field.label}必须是数字")
                    if (field.min?.let { number < it } == true || field.max?.let { number > it } == true) throw SiteException("${field.label}超出允许范围")
                }
                if (field.type != CommunityFieldType.CHECKBOX || value.isNotEmpty()) add(field.name to value)
            }
            form.submitter?.let { add(it) }
        }
    }

    fun result(raw: String): CommunityWriteResult {
        Parse.refusal(raw)?.let { throw it }
        if (Parse.isLoginPage(raw)) throw loginError()
        val json = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw SiteException("提交结果未能确认，请刷新核对后再试", SiteException.Kind.PARSE)
        fun value(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val redirect = value("redirect").takeIf(String::isNotBlank)?.let { sameOrigin(it) }.orEmpty()
        if (redirect.isNotBlank() && redirect.toHttpUrl().encodedPath in listOf("/login", "/register")) throw loginError()
        val message = Jsoup.parse(value("message").ifBlank { value("tip") }).text().take(500)
        if (value("ok") !in listOf("1", "true")) throw SiteException(message.ifBlank { "站点未确认提交成功" })
        return CommunityWriteResult(message.ifBlank { "操作成功" }, redirect)
    }
}

class CollectionsApi(private val http: CommunityHttp = CommunityHttp.Live) {
    suspend fun list(tab: CollectionsTab = CollectionsTab.EVERYONE, page: Int = 1): CollectionsPage = withContext(Dispatchers.IO) {
        val url = listUrl(tab, page)
        parseList(http.get(url), tab, page)
    }

    suspend fun detail(id: Int, page: Int = 1): CollectionDetail = withContext(Dispatchers.IO) {
        val url = detailUrl(id, page)
        parseDetail(http.get(url), id, page)
    }

    suspend fun topic(topicId: Int): TopicCollections = withContext(Dispatchers.IO) {
        require(topicId > 0)
        parseTopic(http.get(Site.topic(topicId)), topicId)
    }

    suspend fun submit(form: CommunityForm, values: Map<String, String>): CommunityWriteResult = withContext(Dispatchers.IO) {
        val source = CommunityForms.sameOrigin(form.sourceUrl)
        val doc = CommunityForms.document(http.get(source), source)
        val fresh = collectionForms(doc, source).firstOrNull { it.key == form.key }
            ?: throw SiteException("操作权限或表单已变化，请刷新后重试")
        val fields = CommunityForms.payload(fresh, values)
        ensureActive()
        CommunityForms.result(http.post(fresh.action, fields))
    }

    suspend fun membership(topicId: Int, collectionId: Int, include: Boolean): CommunityWriteResult = withContext(Dispatchers.IO) {
        require(topicId > 0 && collectionId > 0)
        val fresh = parseTopic(http.get(Site.topic(topicId)), topicId)
        val choice = fresh.choices.firstOrNull { it.id == collectionId } ?: throw SiteException("当前账号不能管理这个专辑")
        if (choice.included == include) return@withContext CommunityWriteResult(if (include) "帖子已在专辑中" else "帖子已移出专辑")
        val form = fresh.membershipForm ?: throw SiteException("当前账号没有收录权限")
        val fields = CommunityForms.payload(form, mapOf("collection_id" to collectionId.toString()))
            .map { (name, value) -> name to if (name == "topic_collections_action") (if (include) "item_add" else "item_remove") else value }
        ensureActive()
        CommunityForms.result(http.post(form.action, fields))
    }

    suspend fun removeAll(topicId: Int): CommunityWriteResult = withContext(Dispatchers.IO) {
        require(topicId > 0)
        val fresh = parseTopic(http.get(Site.topic(topicId)), topicId)
        if (!fresh.canRemoveAll) throw SiteException("当前没有可全部取消的收录")
        val form = fresh.membershipForm ?: throw SiteException("当前账号没有收录权限")
        val fields = CommunityForms.payload(form, mapOf("collection_id" to "__topic_collections_remove_all__"))
            .map { (name, value) -> name to if (name == "topic_collections_action") "item_remove_all" else value }
        ensureActive()
        CommunityForms.result(http.post(form.action, fields))
    }

    companion object {
        fun listUrl(tab: CollectionsTab = CollectionsTab.EVERYONE, page: Int = 1): String {
            require(page > 0)
            return "${Site.BASE}/topic_collections?tab=${tab.value}&p=$page"
        }

        fun detailUrl(id: Int, page: Int = 1): String {
            require(id > 0 && page > 0)
            return "${Site.BASE}/topic_collection/$id?p=$page"
        }

        fun topicId(input: String): Int = CommunityForms.topicId(input)

        fun parseList(html: String, tab: CollectionsTab = CollectionsTab.EVERYONE, page: Int = 1): CollectionsPage {
            val url = listUrl(tab, page)
            val doc = CommunityForms.document(html, url)
            if (doc.selectFirst(".topic-collections-collection-list") == null) throw SiteException("未能读取淘帖列表，请刷新或查看网页", SiteException.Kind.PARSE)
            val cards = doc.select(".topic-collections-collection-row").mapNotNull { row ->
                val link = row.selectFirst("a.post-title[href]") ?: return@mapNotNull null
                val id = CommunityForms.id(link.attr("href"), "topic_collection")
                if (id == 0) return@mapNotNull null
                val user = row.selectFirst(".post-meta a[href*=/user/]")
                CollectionCard(id, link.text(), row.selectFirst(".topic-collections-card-desc")?.text().orEmpty(), user?.text().orEmpty(),
                    CommunityForms.id(user?.attr("href").orEmpty(), "user"), row.selectFirst("img")?.absUrl("src").orEmpty(),
                    row.selectFirst(".post-meta")?.text().orEmpty(), row.select(".topic-collections-tag").text())
            }.distinctBy { it.id }
            return CollectionsPage(cards, tab, page, CommunityForms.lastPage(doc, url, page))
        }

        fun parseDetail(html: String, id: Int, page: Int = 1): CollectionDetail {
            val url = detailUrl(id, page)
            val doc = CommunityForms.document(html, url)
            val head = doc.selectFirst(".topic-collections-collection-head") ?: throw SiteException("未能读取专辑，可能已被删除或设为私密", SiteException.Kind.PARSE)
            val user = head.selectFirst(".topic-collections-collection-meta a[href*=/user/]")
            val card = CollectionCard(id, head.selectFirst("h1")?.text().orEmpty(),
                head.selectFirst(".topic-collections-collection-description")?.text().orEmpty(), user?.text().orEmpty(),
                CommunityForms.id(user?.attr("href").orEmpty(), "user"), metadata = head.selectFirst(".topic-collections-collection-meta")?.text().orEmpty(),
                visibility = head.select(".topic-collections-tag").text())
            val forms = collectionForms(doc, url)
            val topicList = doc.selectFirst(".topic-collections-topic-list")
                ?: throw SiteException("未能读取专辑帖子列表，请刷新或查看网页", SiteException.Kind.PARSE)
            val allowedTopics = topicList.select("a.post-title[href]").map { CommunityForms.id(it.attr("href"), "topic") }.toSet()
            val topics = Parse.feed(topicList.outerHtml()).filter { it.id > 0 && it.id in allowedTopics }.distinctBy { it.id }
            return CollectionDetail(card, topics, forms, page, CommunityForms.lastPage(doc, url, page),
                (card.authorId > 0 && Parse.meOf(doc)?.id == card.authorId) || forms.any { it.fields.any { field -> field.name == "name" } })
        }

        fun parseTopic(html: String, topicId: Int): TopicCollections {
            require(topicId > 0)
            val url = Site.topic(topicId)
            val doc = CommunityForms.document(html, url)
            if (doc.selectFirst(".post-content-title, .post-topic-title") == null) throw SiteException("未能读取帖子，请刷新或查看网页", SiteException.Kind.PARSE)
            val panel = doc.selectFirst(".topic-collections-panel")
                ?: return TopicCollections(topicId, doc.selectFirst(".post-content-title")?.text().orEmpty(), emptyList(), null, null, false, "当前页面没有可用的淘帖操作")
            val add = panel.selectFirst("form[data-topic-collections-add-form]")
            val form = add?.let { CommunityForms.forms(it, url).firstOrNull() }
                ?.takeIf { f -> f.hidden.any { it.first == "topic_id" && it.second == topicId.toString() } && f.hidden.any { it.first == "topic_collections_action" && it.second in listOf("item_add", "item_remove", "item_remove_all") } }
            val select = add?.selectFirst("select[name=collection_id]:not([disabled])")
            val choices = select?.select("option:not([disabled])")?.mapNotNull { option ->
                val id = option.`val`().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                CollectionChoice(id, option.text(), option.attr("data-included") == "1")
            }.orEmpty()
            val create = collectionForms(doc, url).firstOrNull { f -> f.hidden.any { it.first == "topic_collections_action" && it.second == "collection_create_add" } }
            return TopicCollections(topicId, doc.selectFirst(".post-content-title")?.text().orEmpty(), choices, form, create,
                select?.selectFirst("option[value=__topic_collections_remove_all__]:not([disabled])") != null,
                if (form == null && create == null) panel.select("p, .topic-collections-note").text().ifBlank { "当前账号没有可用的淘帖操作" } else "")
        }

        private fun collectionForms(doc: Document, source: String): List<CommunityForm> = doc.select("form").filter { form ->
            val topicId = CommunityForms.id(source, "topic")
            val collectionId = CommunityForms.id(source, "topic_collection")
            (topicId == 0 || form.selectFirst("input[name=topic_id]")?.`val`() == topicId.toString()) &&
                (collectionId == 0 || form.selectFirst("input[name=collection_id]")?.`val`() == collectionId.toString()) &&
                form.selectFirst("input[name=topic_collections_action]") != null &&
                (form.hasClass("topic-collections-subscribe") ||
                    (form.selectFirst("input[name=name]") != null && form.selectFirst("textarea[name=description]") != null))
        }.flatMap { CommunityForms.forms(it, source) }
    }
}
