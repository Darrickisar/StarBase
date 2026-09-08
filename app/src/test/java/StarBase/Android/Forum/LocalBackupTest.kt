package StarBase.Android.Forum

import StarBase.Android.Forum.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalBackupTest {
    private fun sample() = LocalBackupData(
        history = listOf(Visit(20390, "A title", at = 1, count = 2)),
        reading = listOf(ReadMark(20390, 8, 12, 1, "A title", true)),
        blocks = listOf(BlockRule("muted")), pins = listOf(1, 4),
        reminders = listOf(Reminder(1001, Reminder.Kind.DRAW, 2_000_000_000_000, "Reminder", 1)),
        reader = ReaderPreferences(1.2f, 1.1f)
    )

    @Test fun roundTripPreservesActionsWithoutSessionFields() {
        val encoded = LocalBackup.encode(sample())
        assertEquals(sample(), LocalBackup.decode(encoded))
        assertFalse(encoded.contains("cookie", ignoreCase = true))
        assertFalse(encoded.contains("password", ignoreCase = true))
        assertFalse(encoded.contains("doh_server"))
    }

    @Test fun unknownVersionRejectedBeforeImport() {
        val root = Json.parseToJsonElement(LocalBackup.encode(sample())).jsonObject.toMutableMap()
        root["version"] = JsonPrimitive(99)
        assertThrows(IllegalArgumentException::class.java) { LocalBackup.decode(JsonObject(root).toString()) }
    }

    @Test fun malformedListCannotSilentlyBecomeEmpty() {
        val root = Json.parseToJsonElement(LocalBackup.encode(sample())).jsonObject.toMutableMap()
        root["history"] = JsonArray(listOf(JsonPrimitive("invalid")))
        assertThrows(Exception::class.java) { LocalBackup.decode(JsonObject(root).toString()) }
    }

    @Test fun invalidAlarmIdentityRejected() {
        val bad = sample().copy(reminders = listOf(Reminder(1, Reminder.Kind.DRAW, 2_000_000_000_000, "Invalid", 8)))
        assertThrows(IllegalArgumentException::class.java) { LocalBackup.decode(LocalBackup.encode(bad)) }
    }

    @Test fun exportRejectsDataThatExceedsTheUtf8ImportLimit() {
        val large = sample().copy(extras = buildJsonObject {
            put("drafts", JsonPrimitive("\u754c".repeat(LocalBackup.MAX_BYTES / 3 + 1)))
        })
        assertThrows(IllegalArgumentException::class.java) { LocalBackup.encode(large) }
    }
}
