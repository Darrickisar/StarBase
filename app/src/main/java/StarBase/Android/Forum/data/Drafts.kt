package StarBase.Android.Forum.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class DraftKind { NEW_TOPIC, REPLY, DM }

/** Identifiers only. The quoted site's text is fetched again when composing a reply. */
data class DraftQuote(val postId: Int, val floor: Int = 0, val page: Int = 1)

data class DraftImage(val fileName: String, val uncertain: Boolean = false) {
    companion object {
        fun validName(name: String): Boolean =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.png").matches(name)
    }
}

data class WritingDraft(
    val id: String,
    val accountId: Int,
    val kind: DraftKind,
    val targetId: Int = 0,
    val title: String = "",
    val body: String = "",
    val forumId: Int = 0,
    val quote: DraftQuote? = null,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val localImages: List<DraftImage> = emptyList()
) {
    val hasContent: Boolean get() = title.isNotBlank() || body.isNotBlank() || quote != null || localImages.isNotEmpty()
}

/** Pure draft operations, shared by storage and JVM tests. No site responses belong here. */
object Drafts {
    fun create(accountId: Int, kind: DraftKind, targetId: Int = 0): WritingDraft =
        normalize(WritingDraft(UUID.randomUUID().toString(), accountId, kind, targetId))

    fun normalize(draft: WritingDraft): WritingDraft {
        require(draft.accountId > 0) { "A draft must belong to an identified account" }
        require(draft.id.isNotBlank()) { "Missing draft ID" }
        require(if (draft.kind == DraftKind.NEW_TOPIC) draft.targetId == 0 else draft.targetId > 0)
        require(draft.forumId >= 0)
        require(draft.localImages.size <= 8 && draft.localImages.all { DraftImage.validName(it.fileName) })
        require(draft.localImages.distinctBy { it.fileName }.size == draft.localImages.size)
        require(draft.localImages.isEmpty() || draft.kind == DraftKind.NEW_TOPIC)
        require(draft.quote == null || (draft.kind == DraftKind.REPLY &&
            draft.quote.postId >= 0 && draft.quote.floor >= 0 && draft.quote.page > 0 &&
            (draft.quote.postId > 0 || draft.quote.floor > 0)))
        return draft.copy(
            selectionStart = draft.selectionStart.coerceIn(0, draft.body.length),
            selectionEnd = draft.selectionEnd.coerceIn(0, draft.body.length)
        )
    }

    fun list(all: List<WritingDraft>, accountId: Int, kind: DraftKind? = null): List<WritingDraft> =
        if (accountId <= 0) emptyList() else all.filter {
            it.accountId == accountId && (kind == null || it.kind == kind)
        }.sortedByDescending { it.updatedAt }

    fun put(all: List<WritingDraft>, draft: WritingDraft): List<WritingDraft> {
        val valid = normalize(draft)
        return all.filterNot { it.accountId == valid.accountId && it.id == valid.id } + valid
    }

    fun remove(all: List<WritingDraft>, accountId: Int, id: String): List<WritingDraft> =
        all.filterNot { it.accountId == accountId && it.id == id }

    fun encode(all: List<WritingDraft>, includeLocalImages: Boolean = false): String = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("drafts", buildJsonArray {
            all.forEach { draft -> add(buildJsonObject {
                put("id", JsonPrimitive(draft.id))
                put("accountId", JsonPrimitive(draft.accountId))
                put("kind", JsonPrimitive(draft.kind.name))
                put("targetId", JsonPrimitive(draft.targetId))
                put("title", JsonPrimitive(draft.title))
                put("body", JsonPrimitive(draft.body))
                put("forumId", JsonPrimitive(draft.forumId))
                put("selectionStart", JsonPrimitive(draft.selectionStart))
                put("selectionEnd", JsonPrimitive(draft.selectionEnd))
                put("updatedAt", JsonPrimitive(draft.updatedAt))
                if (includeLocalImages && draft.localImages.isNotEmpty()) put("localImages", buildJsonArray {
                    draft.localImages.forEach { image -> add(buildJsonObject {
                        put("fileName", JsonPrimitive(image.fileName))
                        put("uncertain", JsonPrimitive(image.uncertain))
                    }) }
                })
                draft.quote?.let { quote -> put("quote", buildJsonObject {
                    put("postId", JsonPrimitive(quote.postId))
                    put("floor", JsonPrimitive(quote.floor))
                    put("page", JsonPrimitive(quote.page))
                }) }
            }) }
        })
    }.toString()

    fun decode(raw: String, includeLocalImages: Boolean = false): List<WritingDraft> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyList()
        if (root.number("version") != 1) return emptyList()
        val rows = root["drafts"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { element ->
            runCatching {
                val row = element as? JsonObject ?: return@runCatching null
                val quote = (row["quote"] as? JsonObject)?.let {
                    DraftQuote(it.number("postId"), it.number("floor"), it.number("page", 1))
                }
                normalize(WritingDraft(
                    id = row.string("id"),
                    accountId = row.number("accountId"),
                    kind = DraftKind.valueOf(row.string("kind")),
                    targetId = row.number("targetId"),
                    title = row.string("title"),
                    body = row.string("body"),
                    forumId = row.number("forumId"),
                    quote = quote,
                    selectionStart = row.number("selectionStart"),
                    selectionEnd = row.number("selectionEnd"),
                    updatedAt = (row["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    localImages = if (includeLocalImages) (row["localImages"] as? JsonArray).orEmpty().map {
                        val image = it as JsonObject
                        DraftImage(image.string("fileName"), (image["uncertain"] as? JsonPrimitive)?.booleanOrNull ?: false)
                    } else emptyList()
                ))
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }.distinctBy { it.accountId to it.id }
    }

    /** Strict import validation happens before any mutation, including account ownership. */
    fun validateImport(accountId: Int, payload: String): List<WritingDraft> {
        require(accountId > 0) { "请先登录" }
        val root = Json.parseToJsonElement(payload) as? JsonObject ?: error("草稿备份格式无效")
        require(root.number("version") == 1) { "不支持此草稿备份版本" }
        val rows = root["drafts"] as? JsonArray ?: error("草稿备份缺少记录")
        rows.forEach { element ->
            val row = element as? JsonObject ?: error("草稿记录无效")
            require("localImages" !in row) { "备份不能导入本机图片路径" }
            require(row.number("accountId") == accountId) { "只能导入当前账号的草稿" }
            listOf("id", "kind", "title", "body").forEach { name ->
                require((row[name] as? JsonPrimitive)?.isString == true) { "草稿字段无效：$name" }
            }
            listOf("accountId", "targetId", "forumId", "selectionStart", "selectionEnd").forEach { name ->
                require((row[name] as? JsonPrimitive)?.intOrNull != null) { "草稿字段无效：$name" }
            }
            require((row["updatedAt"] as? JsonPrimitive)?.longOrNull != null) { "草稿时间无效" }
            row["quote"]?.let { quote ->
                require(quote is JsonObject) { "草稿引用无效" }
                listOf("postId", "floor", "page").forEach { name ->
                    require((quote[name] as? JsonPrimitive)?.intOrNull != null) { "草稿引用无效" }
                }
            }
        }
        val decoded = decode(payload)
        require(decoded.size == rows.size) { "草稿备份含有无效或重复记录" }
        return decoded
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String, fallback: Int = 0) =
        (get(key) as? JsonPrimitive)?.intOrNull ?: fallback
}

data class DraftPersistence(val attemptedRevision: Long = 0, val savedRevision: Long = 0, val failed: Boolean = false)

/**
 * Mutations are immediately observable. A process-owned, serial writer commits the newest
 * snapshot off the UI thread; screen disposal does not cancel pending disk writes.
 */
class DraftStore internal constructor(
    initial: List<WritingDraft>,
    private val write: (String) -> Boolean,
    scope: CoroutineScope,
    private val afterWrite: (List<WritingDraft>, List<WritingDraft>) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private var records = initial
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes.asStateFlow()
    private val disk = MutableStateFlow(DraftPersistence())
    val persistence: StateFlow<DraftPersistence> = disk.asStateFlow()
    private data class Pending(val revision: Long, val drafts: List<WritingDraft>)
    private val pending = Channel<Pending>(Channel.CONFLATED)

    init {
        scope.launch {
            var persisted = initial
            for (request in pending) {
                val ok = runCatching { write(Drafts.encode(request.drafts, includeLocalImages = true)) }.getOrDefault(false)
                if (ok) {
                    runCatching { afterWrite(persisted, request.drafts) }
                    persisted = request.drafts
                }
                disk.value = DraftPersistence(
                    attemptedRevision = request.revision,
                    savedRevision = if (ok) request.revision else disk.value.savedRevision,
                    failed = !ok
                )
            }
        }
    }

    fun list(accountId: Int, kind: DraftKind? = null): List<WritingDraft> = synchronized(lock) {
        Drafts.list(records, accountId, kind)
    }

    fun get(accountId: Int, id: String): WritingDraft? = synchronized(lock) {
        records.firstOrNull { accountId > 0 && it.accountId == accountId && it.id == id }
    }

    fun find(accountId: Int, kind: DraftKind, targetId: Int): WritingDraft? =
        list(accountId, kind).firstOrNull { it.targetId == targetId }

    fun save(draft: WritingDraft) = synchronized(lock) {
        val next = Drafts.put(records, draft)
        if (next != records) publish(next)
    }

    fun delete(accountId: Int, id: String) = synchronized(lock) {
        val next = Drafts.remove(records, accountId, id)
        if (next != records) publish(next)
    }

    fun export(accountId: Int): String = Drafts.encode(list(accountId))

    /** Validated as a whole; a newer local edit wins a conflict with a backup. */
    fun mergeImport(accountId: Int, payload: String): Int {
        val imported = Drafts.validateImport(accountId, payload)
        return synchronized(lock) {
            var next = records
            var count = 0
            imported.forEach { draft ->
                val current = next.firstOrNull { it.accountId == accountId && it.id == draft.id }
                if (current == null || draft.updatedAt > current.updatedAt) {
                    next = Drafts.put(next, draft.copy(localImages = current?.localImages.orEmpty()))
                    count++
                }
            }
            if (next != records) publish(next) else if (disk.value.failed) retryPendingWrites()
            count
        }
    }

    fun retryPendingWrites() = synchronized(lock) {
        changes.value += 1
        pending.trySend(Pending(changes.value, records))
        Unit
    }

    /** True only after every mutation preceding this call has reached disk successfully. */
    suspend fun awaitPendingWrites(): Boolean {
        val expected = changes.value
        val result = disk.first { it.attemptedRevision >= expected }
        return result.savedRevision >= expected
    }

    private fun publish(next: List<WritingDraft>) {
        records = next
        changes.value += 1
        pending.trySend(Pending(changes.value, next))
    }

    companion object {
        @Volatile private var instance: DraftStore? = null

        fun get(context: Context): DraftStore = instance ?: synchronized(this) {
            instance ?: run {
                val prefs = context.applicationContext.getSharedPreferences("starbase_drafts", Context.MODE_PRIVATE)
                DraftStore(
                    initial = Drafts.decode(prefs.getString("records_v1", "").orEmpty(), includeLocalImages = true),
                    write = { prefs.edit().putString("records_v1", it).commit() },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    afterWrite = { old, fresh -> DraftImageFiles.cleanRemoved(context.applicationContext, old, fresh) }
                )
            }.also { instance = it }
        }
    }
}
