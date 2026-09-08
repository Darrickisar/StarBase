package StarBase.Android.Forum

import StarBase.Android.Forum.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DraftImageTest {
    private val image = DraftImage("01234567-89ab-4cde-8012-0123456789ab.png", uncertain = true)
    private fun draft() = Drafts.create(123, DraftKind.NEW_TOPIC).copy(body = "Help", localImages = listOf(image))

    @Test fun privateStorageKeepsImagesAndUploadUncertaintyButBackupsExcludeThem() {
        val original = draft()
        val local = Drafts.encode(listOf(original), includeLocalImages = true)
        assertEquals(listOf(original), Drafts.decode(local, includeLocalImages = true))
        val backup = Drafts.encode(listOf(original))
        assertFalse(backup.contains(image.fileName))
        assertEquals(listOf(original.copy(localImages = emptyList())), Drafts.validateImport(123, backup))
        assertThrows(IllegalArgumentException::class.java) { Drafts.validateImport(123, local) }
    }

    @Test fun imageReferencesCannotEscapeThePrivateImageDirectory() {
        for (name in listOf("../secret.png", "/private/file.png", "x%2Fy.png", "a.png", "content://provider/file")) {
            assertThrows(IllegalArgumentException::class.java) {
                Drafts.normalize(draft().copy(localImages = listOf(DraftImage(name))))
            }
        }
        assertTrue(draft().copy(body = "").hasContent)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun importingNewerTextRetainsThisDevicesAttachments() = runTest {
        val local = draft().copy(updatedAt = 1)
        val store = DraftStore(listOf(local), { true }, backgroundScope)
        val imported = local.copy(body = "Updated help", updatedAt = 2, localImages = emptyList())
        store.mergeImport(123, Drafts.encode(listOf(imported)))
        runCurrent()
        assertEquals(listOf(image), store.get(123, local.id)!!.localImages)
        assertEquals("Updated help", store.get(123, local.id)!!.body)
    }
}
