package StarBase.Android.Forum.data

import kotlinx.serialization.json.*

data class LocalBackupData(
    val theme: ThemeMode = ThemeMode.DEFAULT,
    val updates: UpdateCheck = UpdateCheck.DEFAULT,
    val keepHistory: Boolean = true,
    val keepReading: Boolean = true,
    val boardOrder: BoardOrder = BoardOrder.DEFAULT,
    val history: List<Visit> = emptyList(),
    val reading: List<ReadMark> = emptyList(),
    val blocks: List<BlockRule> = emptyList(),
    val pins: List<Int> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val reader: ReaderPreferences = ReaderPreferences(),
    val extras: JsonObject = JsonObject(emptyMap())
)

/** Explicit schema: preferences are never copied wholesale, so sessions cannot leak into a backup. */
object LocalBackup {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val json = Json { prettyPrint = true }

    fun encode(data: LocalBackupData): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", JsonPrimitive("StarBase.local"))
        put("version", JsonPrimitive(1))
        put("createdAt", JsonPrimitive(System.currentTimeMillis()))
        put("settings", buildJsonObject {
            put("theme", JsonPrimitive(data.theme.key))
            put("updates", JsonPrimitive(data.updates.key))
            put("keepHistory", JsonPrimitive(data.keepHistory))
            put("keepReading", JsonPrimitive(data.keepReading))
            put("boardOrder", JsonPrimitive(data.boardOrder.key))
            put("fontScale", JsonPrimitive(data.reader.normalized().fontScale))
            put("lineHeightScale", JsonPrimitive(data.reader.normalized().lineHeightScale))
        })
        put("history", Json.parseToJsonElement(History.encode(data.history)))
        put("reading", Json.parseToJsonElement(Reading.encode(data.reading)))
        put("blocks", Json.parseToJsonElement(Filters.encode(data.blocks)))
        put("pins", Json.parseToJsonElement(Boards.encodePins(data.pins)))
        put("reminders", Json.parseToJsonElement(Reminders.encode(data.reminders)))
        put("extras", data.extras)
    }).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 8 MB，请先清理不再需要的草稿" } }

    fun decode(text: String): LocalBackupData {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 8 MB" }
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("备份格式无效")
        require(root["format"]?.jsonPrimitive?.content == "StarBase.local") { "不是 StarBase 本机备份" }
        require(root["version"]?.jsonPrimitive?.intOrNull == 1) { "不支持这个备份版本" }
        val settings = root["settings"] as? JsonObject ?: error("备份缺少设置")
        fun string(key: String) = (settings[key] as? JsonPrimitive)?.content ?: error("设置字段无效：$key")
        fun boolean(key: String) = (settings[key] as? JsonPrimitive)?.booleanOrNull ?: error("设置字段无效：$key")
        fun array(key: String, cap: Int): JsonArray {
            val value = root[key] as? JsonArray ?: error("备份字段无效：$key")
            require(value.size <= cap) { "备份条目过多：$key" }
            return value
        }
        val historyRaw = array("history", History.CAP)
        val readingRaw = array("reading", Reading.CAP + Reading.WATCH_CAP)
        val blocksRaw = array("blocks", Filters.CAP)
        val pinsRaw = array("pins", 100)
        val remindersRaw = array("reminders", Reminders.CAP)
        val history = History.decode(historyRaw.toString())
        val reading = Reading.decode(readingRaw.toString())
        val blocks = Filters.decode(blocksRaw.toString())
        val pins = Boards.decodePins(pinsRaw.toString())
        val reminders = Reminders.decode(remindersRaw.toString())
        require(history.size == historyRaw.size && reading.size == readingRaw.size &&
            blocks.size == blocksRaw.size && pins.size == pinsRaw.size && reminders.size == remindersRaw.size) {
            "备份含有重复或无效条目"
        }
        require(history.all { it.id > 0 && it.title.length <= 4000 && it.forumName.length <= 500 && it.at >= 0 && it.count > 0 }) { "浏览记录无效" }
        require(reading.all { it.topicId > 0 && it.seenFloor >= 0 && it.seenTotal >= 0 && it.title.length <= 4000 && it.at >= 0 } &&
            reading.count { it.watched } <= Reading.WATCH_CAP) { "阅读记录无效" }
        require(blocks.all { it.value.isNotBlank() && it.value.length <= 1000 }) { "屏蔽规则无效" }
        require(reminders.all {
            it.at > 0 && it.label.length <= 4000 && when (it.kind) {
                Reminder.Kind.CHECK_IN -> it.id == Reminders.CHECK_IN_ID && it.daily && it.topicId == 0
                Reminder.Kind.DRAW -> it.topicId in 1..(Int.MAX_VALUE - 1000) &&
                    it.id == Reminders.drawId(it.topicId) && !it.daily
            }
        }) { "提醒记录无效" }
        val theme = ThemeMode.entries.firstOrNull { it.key == string("theme") } ?: error("外观设置无效")
        val updates = UpdateCheck.entries.firstOrNull { it.key == string("updates") } ?: error("更新设置无效")
        val board = BoardOrder.entries.firstOrNull { it.key == string("boardOrder") } ?: error("板块设置无效")
        val font = settings["fontScale"]?.jsonPrimitive?.floatOrNull ?: 1f
        val line = settings["lineHeightScale"]?.jsonPrimitive?.floatOrNull ?: 1f
        require(font.isFinite() && line.isFinite()) { "阅读排版无效" }
        return LocalBackupData(theme, updates, boolean("keepHistory"), boolean("keepReading"), board,
            history, reading, blocks, pins, reminders, ReaderPreferences(font, line).normalized(),
            root["extras"] as? JsonObject ?: JsonObject(emptyMap()))
    }
}
