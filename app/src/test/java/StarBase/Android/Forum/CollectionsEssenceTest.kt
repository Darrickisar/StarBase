package StarBase.Android.Forum

import StarBase.Android.Forum.data.CollectionsTab
import StarBase.Android.Forum.net.CollectionsApi
import StarBase.Android.Forum.net.CommunityForms
import StarBase.Android.Forum.net.CommunityHttp
import StarBase.Android.Forum.net.EssenceApi
import StarBase.Android.Forum.net.SiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CollectionsEssenceTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/collections-essence/$name.html"))
        .use { it.readBytes().toString(Charsets.UTF_8) }

    private class OfflineHttp(var html: String, var response: String = """{"ok":1,"message":"测试成功"}""") : CommunityHttp {
        val gets = mutableListOf<String>()
        val posts = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        override suspend fun get(url: String): String { gets += url; return html }
        override suspend fun post(url: String, fields: List<Pair<String, String>>): String { posts += url to fields; return response }
    }

    private fun topicWithChoices(): String {
        val doc = Jsoup.parse(fixture("topic-collections"))
        doc.selectFirst("select[name=collection_id]")!!.append("""
            <option value="7" data-included="1">示例专辑一</option>
            <option value="8" data-included="0">示例专辑二</option>
            <option value="9" disabled>不可选专辑</option>
            <option value="__topic_collections_remove_all__">全部取消收录</option>
        """.trimIndent())
        doc.selectFirst("button[data-topic-collections-add-btn]")!!.removeAttr("disabled")
        return doc.outerHtml()
    }

    private fun failure(block: () -> Unit): SiteException = assertThrows(SiteException::class.java, block)

    @Test fun publicCollectionsKeepMetadataAndOwnPagination() {
        val doc = Jsoup.parse(fixture("collections-list"))
        doc.selectFirst(".pagination")!!.append("""
            <a href="https://elsewhere.invalid/topic_collections?tab=everyone&amp;p=999">wrong origin</a>
            <a href="/topic_collections?tab=mine&amp;p=999">wrong tab</a>
            <a href="/topic/101?p=999">wrong path</a>
        """)
        val page = CollectionsApi.parseList(doc.outerHtml())
        assertEquals(4, page.lastPage)
        assertEquals(CollectionsTab.EVERYONE, page.tab)
        assertEquals(7, page.collections.single().id)
        assertEquals("公开", page.collections.single().visibility)
        assertEquals(2, page.collections.single().authorId)
        assertTrue(page.collections.single().metadata.contains("3 篇文章"))
    }

    @Test fun publicDetailHasTopicAndActualSubscriptionForm() {
        val page = CollectionsApi.parseDetail(fixture("collection-detail"), 7)
        assertEquals(101, page.topics.single().id)
        assertEquals(2, page.lastPage)
        assertFalse(page.canManage)
        val form = page.forms.single()
        assertEquals("https://linux.sb/topic_collections_action", form.action)
        assertEquals("subscription_add", form.hidden.toMap()["topic_collections_action"])
        assertTrue(form.enabled)
        assertFalse(form.toString().contains("fixture-csrf"))
    }

    @Test fun membershipOptionsFollowObservedJavascriptIncludedFlag() {
        val page = CollectionsApi.parseTopic(topicWithChoices(), 101)
        assertEquals(listOf(7, 8), page.choices.map { it.id })
        assertTrue(page.choices[0].included)
        assertFalse(page.choices[1].included)
        assertTrue(page.canRemoveAll)
        assertNotNull(page.createForm)
    }

    @Test fun creationRetainsItsTopicAndOnlyCheckedPrivateField() {
        val form = CollectionsApi.parseTopic(fixture("topic-collections"), 101).createForm!!
        assertEquals("collection_create_add", form.hidden.toMap()["topic_collections_action"])
        assertEquals("101", form.hidden.toMap()["topic_id"])
        val public = CommunityForms.payload(form, mapOf("name" to "测试专辑", "description" to "", "private" to ""))
        assertFalse(public.any { it.first == "private" })
        val private = CommunityForms.payload(form, mapOf("name" to "测试专辑", "private" to "1"))
        assertEquals("1", private.toMap()["private"])
        failure { CommunityForms.payload(form, mapOf("name" to " ")) }
        failure { CommunityForms.payload(form, mapOf("name" to "a".repeat(81))) }
        failure { CommunityForms.payload(form, mapOf("name" to "测试", "_csrf" to "override")) }
    }

    @Test fun serverRenderedEditorPreservesDescriptionAndDiscriminatorWithoutGuessing() = runBlocking {
        // A synthetic permission variant of the captured create form, not a claimed live edit contract.
        val doc = Jsoup.parse(fixture("collection-detail"))
        doc.select(".topic-collections-subscribe").remove()
        val editor = Jsoup.parse(fixture("topic-collections")).selectFirst(".topic-collections-create-modal-form")!!.clone()
        editor.selectFirst("input[name=topic_collections_action]")!!.`val`("fixture-only-edit-discriminator")
        editor.selectFirst("input[name=topic_id]")!!.attr("name", "collection_id").`val`("7")
        editor.selectFirst("input[name=name]")!!.`val`("原专辑")
        editor.selectFirst("textarea[name=description]")!!.text("原简介")
        doc.selectFirst(".topic-collections-collection-actions")!!.appendChild(editor)
        val html = doc.outerHtml()
        val page = CollectionsApi.parseDetail(html, 7)
        assertTrue(page.canManage)
        val form = page.forms.single()
        assertEquals("原简介", form.initialValues()["description"])
        assertFalse(form.fields.first { it.name == "description" }.label.contains("原简介"))
        val http = OfflineHttp(html)
        CollectionsApi(http).submit(form, form.initialValues() + ("name" to "新专辑"))
        assertEquals("fixture-only-edit-discriminator", http.posts.single().second.toMap()["topic_collections_action"])
        assertEquals("原简介", http.posts.single().second.toMap()["description"])
    }

    @Test fun subscriptionRefetchesCsrfBeforePosting() = runBlocking {
        val old = CollectionsApi.parseDetail(fixture("collection-detail"), 7).forms.single()
        val fresh = Jsoup.parse(fixture("collection-detail"))
        fresh.selectFirst("input[name=_csrf]")!!.`val`("fixture-csrf-fresh")
        val http = OfflineHttp(fresh.outerHtml())
        val result = CollectionsApi(http).submit(old, emptyMap())
        assertEquals("测试成功", result.message)
        assertEquals(listOf(old.sourceUrl), http.gets)
        assertEquals("fixture-csrf-fresh", http.posts.single().second.toMap()["_csrf"])
    }

    @Test fun permissionRemovedDuringDialogDoesNotPost() = runBlocking {
        val form = CollectionsApi.parseDetail(fixture("collection-detail"), 7).forms.single()
        val doc = Jsoup.parse(fixture("collection-detail")); doc.select("form").remove()
        val http = OfflineHttp(doc.outerHtml())
        failure { runBlocking { CollectionsApi(http).submit(form, emptyMap()) } }
        assertTrue(http.posts.isEmpty())
    }

    @Test fun membershipUsesFreshOptionStateAndDoesNotToggleAnAlreadyAppliedChange() = runBlocking {
        val http = OfflineHttp(topicWithChoices())
        val api = CollectionsApi(http)
        api.membership(101, 8, true)
        assertEquals("item_add", http.posts.single().second.toMap()["topic_collections_action"])
        assertEquals("8", http.posts.single().second.toMap()["collection_id"])
        http.posts.clear()
        api.membership(101, 7, true)
        assertTrue(http.posts.isEmpty())
        api.membership(101, 7, false)
        assertEquals("item_remove", http.posts.single().second.toMap()["topic_collections_action"])
    }

    @Test fun removedMembershipPermissionAndInvalidChoiceNeverPost() = runBlocking {
        val http = OfflineHttp(topicWithChoices())
        failure { runBlocking { CollectionsApi(http).membership(101, 9, true) } }
        assertTrue(http.posts.isEmpty())
        http.html = fixture("topic-collections")
        failure { runBlocking { CollectionsApi(http).membership(101, 7, true) } }
        assertTrue(http.posts.isEmpty())
    }

    @Test fun serverDisabledMembershipSubmitterIsNotEnabledByNativeClient() = runBlocking {
        val doc = Jsoup.parse(topicWithChoices())
        doc.selectFirst("button[data-topic-collections-add-btn]")!!.attr("disabled", "")
        val http = OfflineHttp(doc.outerHtml())
        failure { runBlocking { CollectionsApi(http).membership(101, 8, true) } }
        failure { runBlocking { CollectionsApi(http).removeAll(101) } }
        assertTrue(http.posts.isEmpty())
    }

    @Test fun removeAllRequiresRenderedSpecialOption() = runBlocking {
        val http = OfflineHttp(topicWithChoices())
        CollectionsApi(http).removeAll(101)
        assertEquals("item_remove_all", http.posts.single().second.toMap()["topic_collections_action"])
        http.posts.clear()
        http.html = fixture("topic-collections")
        failure { runBlocking { CollectionsApi(http).removeAll(101) } }
        assertTrue(http.posts.isEmpty())
    }

    @Test fun essenceListPreservesNegativePointsAndDoesNotClaimApprovalAtThreshold() {
        val list = EssenceApi.parseList(fixture("essence-list"))
        assertEquals(2, list.lastPage)
        assertEquals(-13, list.entries.first().progress!!.points)
        assertEquals(0f, list.entries.first().progress!!.fraction)
        val detail = EssenceApi.parseDetail(fixture("essence-pending"), 101)
        assertEquals("pending_approval", detail.status)
        assertEquals("待审批", detail.statusLabel)
        assertEquals(1f, detail.progress!!.fraction)
        assertTrue(detail.forms.isEmpty())
    }

    @Test fun eligibleVoteUsesExactActionChoicesAndReasonConstraints() {
        val detail = EssenceApi.parseDetail(fixture("essence-voting"), 101)
        assertEquals(2, detail.progress!!.points)
        assertTrue(detail.metadata.contains("奖池共 700 分"))
        val form = detail.forms.single()
        assertEquals("https://linux.sb/topic_essence_review_vote", form.action)
        assertEquals(listOf("support", "oppose"), form.fields.first { it.name == "vote" }.options.map { it.value })
        assertTrue(form.enabled)
        failure { CommunityForms.payload(form, mapOf("vote" to "support", "reason" to " ")) }
        failure { CommunityForms.payload(form, mapOf("vote" to "invented", "reason" to "有效理由")) }
        failure { CommunityForms.payload(form, mapOf("reason" to "a".repeat(301))) }
        assertEquals("oppose", CommunityForms.payload(form, mapOf("vote" to "oppose", "reason" to "测试理由")).toMap()["vote"])
    }

    @Test fun ineligibleApplicationStaysDisabledAndExplainsWhy() = runBlocking {
        val detail = EssenceApi.parseDetail(fixture("essence-ineligible"), 101)
        assertFalse(detail.forms.single().enabled)
        assertTrue(detail.notes.any { it.contains("不足 300 字") })
        val http = OfflineHttp(fixture("essence-ineligible"))
        failure { runBlocking { EssenceApi(http).submit(detail.forms.single(), emptyMap()) } }
        assertTrue(http.posts.isEmpty())
    }

    @Test fun voteCannotSubmitAfterVotingCloses() = runBlocking {
        val form = EssenceApi.parseDetail(fixture("essence-voting"), 101).forms.single()
        val http = OfflineHttp(fixture("essence-pending"))
        failure { runBlocking { EssenceApi(http).submit(form, mapOf("reason" to "测试理由")) } }
        assertTrue(http.posts.isEmpty())
    }

    @Test fun voteRefetchesCsrfAndRequiresExplicitJsonSuccess() = runBlocking {
        val form = EssenceApi.parseDetail(fixture("essence-voting"), 101).forms.single()
        val doc = Jsoup.parse(fixture("essence-voting")); doc.selectFirst("input[name=_csrf]")!!.`val`("fixture-vote-fresh")
        val http = OfflineHttp(doc.outerHtml(), """{"ok":0,"message":"已经投票"}""")
        failure { runBlocking { EssenceApi(http).submit(form, mapOf("reason" to "测试理由")) } }
        assertEquals("fixture-vote-fresh", http.posts.single().second.toMap()["_csrf"])
        assertEquals(1, http.posts.size)
    }

    @Test fun missingCsrfDisabledFieldsetAndExternalActionsCannotSubmit() {
        val doc = Jsoup.parse(fixture("essence-voting"))
        doc.select("input[name=_csrf]").remove()
        assertFalse(EssenceApi.parseDetail(doc.outerHtml(), 101).forms.single().enabled)
        val hostile = Jsoup.parse(fixture("essence-voting"))
        hostile.selectFirst("form")!!.attr("action", "https://elsewhere.invalid/vote")
        assertTrue(EssenceApi.parseDetail(hostile.outerHtml(), 101).forms.isEmpty())
        val disabled = Jsoup.parse(fixture("essence-voting"))
        disabled.selectFirst("button")!!.wrap("<fieldset disabled></fieldset>")
        assertFalse(EssenceApi.parseDetail(disabled.outerHtml(), 101).forms.single().enabled)
    }

    @Test fun unsafeUrlsAndUnconfirmedResponsesAreRejected() {
        listOf("http://linux.sb/topic/1", "https://linux.sb.evil.invalid/topic/1", "//elsewhere.invalid/topic/1", "https://user@linux.sb/topic/1", "https://linux.sb:444/topic/1", "javascript:alert(1)").forEach {
            failure { CommunityForms.sameOrigin(it) }
        }
        assertEquals(101, CollectionsApi.topicId(" https://linux.sb/topic/101?p=2#post-1 "))
        assertEquals(101, CollectionsApi.topicId("101"))
        failure { CollectionsApi.topicId("-1") }
        listOf("<html>操作成功</html>", "{}", "{broken", """{"ok":false}""", """{"ok":1,"redirect":"https://elsewhere.invalid/"}""").forEach {
            failure { CommunityForms.result(it) }
        }
        assertEquals(SiteException.Kind.AUTH, failure { CommunityForms.result("""{"ok":1,"redirect":"/login"}""") }.kind)
    }

    @Test fun loginRefusalAndMissingMarkupDoNotBecomeEmptyLists() {
        val login = "<title>登录</title><form data-slot='login.form'><input name='password'></form>"
        assertEquals(SiteException.Kind.AUTH, failure { CollectionsApi.parseList(login, CollectionsTab.MINE) }.kind)
        failure { EssenceApi.parseList("<section class='form-error-panel'><p>权限不足</p></section>") }
        failure { CollectionsApi.parseList("<html><body>changed</body></html>") }
        failure { EssenceApi.parseList("<html><body>changed</body></html>") }
        failure { EssenceApi.parseList("<div class='forum-main'><ul class='post-list'></ul></div>") }
        failure { CollectionsApi.parseTopic("<html><title>Just a moment</title></html>", 101) }
    }

    @Test fun onlyTheSelectedSubmitButtonIsSent() {
        val doc = Jsoup.parse(fixture("essence-voting"))
        val button = doc.selectFirst("button[type=submit]")!!
        button.attr("name", "fixture_decision").attr("value", "first")
        val second = button.clone().attr("value", "second").text("测试选项二")
        button.after(second)
        val forms = EssenceApi.parseDetail(doc.outerHtml(), 101).forms
        assertEquals(2, forms.size)
        val fields = CommunityForms.payload(forms.last(), mapOf("reason" to "测试理由"))
        assertEquals(listOf("second"), fields.filter { it.first == "fixture_decision" }.map { it.second })
    }

    @Test fun differentTopicOrCollectionIdsDoNotAuthorizeActions() {
        val topic = Jsoup.parse(fixture("essence-voting"))
        topic.selectFirst("input[name=topic_id]")!!.`val`("102")
        assertTrue(EssenceApi.parseDetail(topic.outerHtml(), 101).forms.isEmpty())
        val collection = Jsoup.parse(fixture("collection-detail"))
        collection.selectFirst("input[name=collection_id]")!!.`val`("8")
        assertTrue(CollectionsApi.parseDetail(collection.outerHtml(), 7).forms.isEmpty())
    }

    @Test fun cancelledFreshFormReadNeverProceedsToPosting() = runBlocking {
        val html = fixture("essence-voting")
        val form = EssenceApi.parseDetail(html, 101).forms.single()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var writes = 0
        val http = object : CommunityHttp {
            override suspend fun get(url: String): String { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return html }
            override suspend fun post(url: String, fields: List<Pair<String, String>>): String { writes++; return "{\"ok\":1}" }
        }
        val job = launch(Dispatchers.Default) { EssenceApi(http).submit(form, mapOf("reason" to "测试理由")) }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertEquals(0, writes)
    }
}
