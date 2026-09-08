package StarBase.Android.Forum

import StarBase.Android.Forum.data.SearchEntry
import StarBase.Android.Forum.data.SearchLibrary
import StarBase.Android.Forum.data.SearchStore
import StarBase.Android.Forum.data.Searches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchesTest {
    @Test
    fun rerunningAQueryMovesItToTheFrontWithoutChangingSavedPins() {
        var library = Searches.save(SearchLibrary(), "内核", 1L)
        library = Searches.pin(library, "内核")
        library = Searches.record(library, "内核", 2L)
        library = Searches.record(library, "驱动", 3L)
        library = Searches.record(library, "  内核  ", 4L)
        assertEquals(listOf("内核", "驱动"), library.history.map { it.query })
        assertEquals(4L, library.history.first().at)
        assertTrue(library.saved.single().pinned)
        assertEquals(1L, library.saved.single().at)
        assertEquals(library, Searches.record(library, " \n ", 5L))
    }

    @Test
    fun storageRoundTripPreservesQuotesWhitespaceAndPinnedOrder() {
        val query = "中 文 \"x\" \\ y\nsecond\tline"
        var library = Searches.record(SearchLibrary(), query, 1L)
        library = Searches.save(library, query, 1L)
        library = Searches.save(library, "recent", 2L)
        library = Searches.pin(library, query)
        assertEquals(query, library.saved.first().query)
        assertEquals(library, Searches.decode(Searches.encode(library)))
        assertEquals(library, Searches.decodeSnapshot(Searches.encode(library)))
        assertEquals("recent", Searches.pin(library, query).saved.first().query)
    }

    @Test
    fun clearingOrDeletingOneCollectionDoesNotRemoveTheOther() {
        var library = Searches.record(SearchLibrary(), "kept", 1L)
        library = Searches.save(library, "kept", 1L)
        assertTrue(Searches.removeHistory(library, "kept").history.isEmpty())
        assertEquals(library.saved, Searches.clearHistory(library).saved)
        assertTrue(Searches.removeSaved(library, "kept").saved.isEmpty())
        assertEquals(library.history, Searches.clearSaved(library).history)
        assertEquals(library, Searches.save(library, " kept ", 2L))
    }

    @Test
    fun historiesAreBoundedAndSavingAtCapacityCannotEvictAPinnedEntry() {
        var library = SearchLibrary()
        repeat(Searches.HISTORY_CAP + 5) { library = Searches.record(library, "q$it", it.toLong()) }
        assertEquals(Searches.HISTORY_CAP, library.history.size)
        assertFalse(library.history.any { it.query == "q0" })
        repeat(Searches.SAVED_CAP) { library = Searches.save(library, "saved$it", it.toLong()) }
        library = Searches.pin(library, "saved0")
        assertEquals(library, Searches.save(library, "overflow", 999L))
        assertTrue(Searches.decode(Searches.encode(library)).saved.first().pinned)
    }

    @Test
    fun accountSnapshotsSurviveNewStoreInstancesAndRestoreOnlyTheDestination() {
        val disk = mutableMapOf<String, String>()
        fun store() = SearchStore({ disk[it].orEmpty() }, { key, value -> disk[key] = value })
        val first = SearchLibrary(history = listOf(SearchEntry("first", 1L)))
        val second = SearchLibrary(saved = listOf(SearchEntry("second", 2L, true)))
        store().save(0, SearchLibrary(history = listOf(SearchEntry("guest", 1L))))
        store().save(7, first)
        store().save(8, second)
        assertEquals(setOf("v1.guest", "v1.account.7", "v1.account.8"), disk.keys)
        assertEquals(first, store().snapshot(7))
        store().restore(7, second)
        assertEquals(second, store().snapshot(7))
        assertEquals(second, store().snapshot(8))
        assertEquals("guest", store().snapshot(0).history.single().query)
        store().restore(7, SearchLibrary())
        assertEquals(SearchLibrary(), store().snapshot(7))
        assertEquals(second, store().snapshot(8))
    }

    @Test
    fun corruptLocalRowsAreSalvagedButInvalidBackupsAreRejected() {
        val raw = """{"history":[null,{"query":5},{"query":"ok","at":3,"pinned":false}],"saved":[]}"""
        assertEquals(listOf(SearchEntry("ok", 3L)), Searches.decode(raw).history)
        assertEquals(SearchLibrary(), Searches.decode("invalid"))
        assertNull(Searches.decodeSnapshot(raw))
        assertNull(Searches.decodeSnapshot("{}"))
        assertNull(Searches.decodeSnapshot("invalid"))
        assertEquals(SearchLibrary(), Searches.decodeSnapshot("""{"history":[],"saved":[]}"""))
        listOf(
            """{"query":"a","at":-1,"pinned":false}""",
            """{"query":"a","at":"1","pinned":false}""",
            """{"query":"a","at":1,"pinned":"true"}""",
            """{"query":"a","at":1,"pinned":true}"""
        ).forEach { row ->
            assertNull(Searches.decodeSnapshot("""{"history":[$row],"saved":[]}"""))
        }
        val row = """{"query":"a","at":1,"pinned":false}"""
        assertNull(Searches.decodeSnapshot("""{"history":[$row,$row],"saved":[]}"""))
        val many = (0..Searches.SAVED_CAP).joinToString(",") {
            """{"query":"q$it","at":1,"pinned":false}"""
        }
        assertNull(Searches.decodeSnapshot("""{"history":[],"saved":[$many]}"""))
    }
}
