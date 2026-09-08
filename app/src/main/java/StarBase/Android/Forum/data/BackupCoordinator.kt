package StarBase.Android.Forum.data

import android.content.Context
import StarBase.Android.Forum.notify.Alarms
import kotlinx.serialization.json.*

data class PreparedBackup(
    val data: LocalBackupData,
    val accountId: Int,
    val searches: SearchLibrary?,
    val drafts: String?,
    val draftCount: Int
)

object BackupCoordinator {
    fun snapshot(context: Context, store: UserStore, accountId: Int): String {
        val reader = ReaderPreferenceStore(context).read()
        val extras = buildJsonObject {
            put("accountId", JsonPrimitive(accountId.coerceAtLeast(0)))
            put("searches", Json.parseToJsonElement(Searches.encode(SearchStore(context).snapshot(accountId))))
            if (accountId > 0) put("drafts", Json.parseToJsonElement(DraftStore.get(context).export(accountId)))
        }
        return LocalBackup.encode(store.localSnapshot(reader).copy(extras = extras))
    }

    fun prepare(text: String, accountId: Int): PreparedBackup {
        val data = LocalBackup.decode(text)
        val extras = data.extras
        val owner = extras["accountId"]?.jsonPrimitive?.intOrNull ?: 0
        val hasAccountData = extras["searches"] != null || extras["drafts"] != null
        require(!hasAccountData || owner == accountId.coerceAtLeast(0)) {
            if (owner > 0) "请先登录备份所属账号 UID $owner" else "请退出账号后导入这份访客备份"
        }
        val searches = extras["searches"]?.let {
            requireNotNull(Searches.decodeSnapshot(it.toString())) { "搜索备份无效" }
        }
        val drafts = extras["drafts"]?.toString()
        val draftCount = if (drafts != null) Drafts.validateImport(accountId, drafts).size else 0
        return PreparedBackup(data, accountId, searches, drafts, draftCount)
    }

    suspend fun restore(context: Context, store: UserStore, prepared: PreparedBackup, accountId: Int) {
        require(accountId == prepared.accountId) { "账号已变更，请重新选择备份" }
        prepared.drafts?.let {
            val drafts = DraftStore.get(context)
            drafts.mergeImport(accountId, it)
            check(drafts.awaitPendingWrites()) { "草稿保存失败，请重试导入" }
        }
        val previousAlarms = store.reminders
        store.restoreLocalData(prepared.data)
        ReaderPreferenceStore(context).update(prepared.data.reader)
        prepared.searches?.let { incoming ->
            val searchStore = SearchStore(context)
            val current = searchStore.load(accountId)
            val history = (current.history + incoming.history).groupBy { it.query }.values
                .map { it.maxBy { entry -> entry.at } }.sortedByDescending { it.at }.take(Searches.HISTORY_CAP)
            val saved = (current.saved + incoming.saved).groupBy { it.query }.values
                .map { it.maxBy { entry -> entry.at } }
                .sortedWith(compareByDescending<SearchEntry> { it.pinned }.thenByDescending { it.at })
                .take(Searches.SAVED_CAP)
            searchStore.restore(accountId, SearchLibrary(history, saved))
        }
        previousAlarms.filter { previous -> store.reminders.none { it.id == previous.id } }
            .forEach { Alarms.cancel(context, it.id) }
        Alarms.rearm(context, store.reminders)
    }
}
