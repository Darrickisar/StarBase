package StarBase.Android.Forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import StarBase.Android.Forum.data.UpdateCheck
import StarBase.Android.Forum.net.Releases

/**
 * The updater's two pure halves: what a version string means, and what GitHub's
 * release payload says. Both are the difference between offering an update and
 * offering a downgrade, so neither is left to a manual check on a phone.
 */
class UpdateTest {

    // --- 版本比较 ---

    @Test
    fun `a higher patch is newer`() {
        assertTrue(Releases.isNewer("v1.0.3", "1.0.2"))
        assertFalse(Releases.isNewer("v1.0.1", "1.0.2"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(Releases.isNewer("1.0.2", "1.0.2"))
        assertFalse(Releases.isNewer("v1.0.2", "1.0.2"))
    }

    @Test
    fun `segments compare as numbers, not as text`() {
        // "1.0.10" < "1.0.9" if anyone compares these as strings.
        assertTrue(Releases.isNewer("1.0.10", "1.0.9"))
        assertTrue(Releases.isNewer("1.10.0", "1.9.9"))
    }

    @Test
    fun `a missing segment counts as zero`() {
        assertEquals(0, Releases.compare("1.1", "1.1.0"))
        assertTrue(Releases.isNewer("1.1.1", "1.1"))
        assertFalse(Releases.isNewer("1.1", "1.1.1"))
    }

    @Test
    fun `a prerelease sorts below the plain version`() {
        assertTrue(Releases.compare("1.1.0-beta", "1.1.0") < 0)
        assertTrue(Releases.compare("1.1.0", "1.1.0-rc1") > 0)
        // …but it is still ahead of the release before it.
        assertTrue(Releases.isNewer("1.1.0-beta", "1.0.9"))
    }

    @Test
    fun `the project's own tag shape is understood`() {
        // Releases here are tagged StarBaseV1.0.2, not v1.0.2. A comparison that
        // only knew how to drop a leading "v" would read this as version 0 and
        // never find an update at all.
        assertTrue(Releases.isNewer("StarBaseV1.0.3", "1.0.2"))
        assertFalse(Releases.isNewer("StarBaseV1.0.2", "1.0.2"))
        assertFalse(Releases.isNewer("StarBaseV1.0.2", "1.0.3"))
        assertTrue(Releases.isNewer("StarBaseV1.0.10", "1.0.9"))
        assertEquals(0, Releases.compare("StarBaseV1.0.2", "v1.0.2"))
    }

    @Test
    fun `a name carrying its own digit does not become the version`() {
        assertEquals(0, Releases.compare("StarBase2V1.0.3", "1.0.3"))
        assertTrue(Releases.isNewer("StarBase2V1.0.4", "1.0.3"))
    }

    @Test
    fun `a tag reads back as a plain version for display`() {
        assertEquals("v1.0.3", Releases.label("StarBaseV1.0.3"))
        assertEquals("v1.0.3", Releases.label("v1.0.3"))
        assertEquals("v1.0.3", Releases.label("1.0.3"))
        assertEquals("v1.1.0-beta", Releases.label("StarBaseV1.1.0-beta"))
        // Nothing numeric in it: show whatever the tag actually says.
        assertEquals("latest", Releases.label("latest"))
    }

    @Test
    fun `an empty tag is never an update`() {
        assertFalse(Releases.isNewer("", "1.0.2"))
        assertFalse(Releases.isNewer("   ", "1.0.2"))
    }

    // --- Release JSON ---

    private fun releaseJson(assets: String, extra: String = ""): String = """
        {
          "tag_name": "v1.0.3",
          "name": "v1.0.3 应用设置",
          "body": "### 新增\n- 应用内检查更新",
          "html_url": "https://github.com/owner/repo/releases/tag/v1.0.3",
          "published_at": "2026-09-01T04:20:00Z",
          "draft": false,
          "prerelease": false,
          $extra
          "assets": [$assets]
        }
    """.trimIndent()

    private val apkAsset = """
        {
          "name": "StarBase-v1.0.3.apk",
          "size": 2230144,
          "content_type": "application/vnd.android.package-archive",
          "browser_download_url": "https://github.com/owner/repo/releases/download/v1.0.3/StarBase-v1.0.3.apk"
        }
    """.trimIndent()

    @Test
    fun `a release with an apk is read whole`() {
        val info = Releases.parse(releaseJson(apkAsset))
        assertNotNull(info)
        requireNotNull(info)
        assertEquals("v1.0.3", info.tag)
        assertEquals("v1.0.3 应用设置", info.name)
        assertTrue(info.notes.contains("应用内检查更新"))
        assertEquals("2026-09-01T04:20:00Z", info.publishedAt)
        assertEquals(
            "https://github.com/owner/repo/releases/download/v1.0.3/StarBase-v1.0.3.apk",
            info.apkUrl
        )
        assertEquals("StarBase-v1.0.3.apk", info.apkName)
        assertEquals(2230144L, info.apkSize)
        assertFalse(info.prerelease)
    }

    @Test
    fun `the apk is picked out of a release that also ships other files`() {
        val others = """
            {
              "name": "StarBase-v1.0.3.apk.sha256",
              "size": 90,
              "browser_download_url": "https://example.invalid/sum"
            },
            $apkAsset,
            {
              "name": "sources.zip",
              "size": 100,
              "browser_download_url": "https://example.invalid/zip"
            }
        """.trimIndent()
        val info = requireNotNull(Releases.parse(releaseJson(others)))
        assertEquals("StarBase-v1.0.3.apk", info.apkName)
        assertEquals(2230144L, info.apkSize)
    }

    @Test
    fun `a release with no apk still parses, with nothing to download`() {
        val info = requireNotNull(Releases.parse(releaseJson("")))
        assertEquals("v1.0.3", info.tag)
        assertNull(info.apkUrl)
        assertEquals(0L, info.apkSize)
    }

    @Test
    fun `GitHub's not-found answer is no release rather than an error`() {
        assertNull(Releases.parse("""{"message":"Not Found","status":"404"}"""))
    }

    @Test
    fun `a draft is not offered`() {
        assertNull(
            Releases.parse(
                """{"tag_name":"v9.9.9","draft":true,"assets":[]}"""
            )
        )
    }

    @Test
    fun `a release with no name falls back to its tag`() {
        val info = requireNotNull(
            Releases.parse("""{"tag_name":"v1.0.4","name":null,"assets":[]}""")
        )
        assertEquals("v1.0.4", info.name)
        assertEquals("", info.notes)
    }

    // --- 检查时机 ---

    private val hour = 60L * 60 * 1000
    private val now = 1_756_000_000_000L

    @Test
    fun `每次启动 always checks`() {
        assertTrue(UpdateCheck.LAUNCH.due(now - 1, now))
        assertTrue(UpdateCheck.LAUNCH.due(now, now))
    }

    @Test
    fun `只手动检查 never checks by itself`() {
        assertFalse(UpdateCheck.MANUAL.due(0L, now))
        assertFalse(UpdateCheck.MANUAL.due(now - 400 * hour, now))
    }

    @Test
    fun `每天一次 waits out the day`() {
        assertFalse(UpdateCheck.DAILY.due(now - 23 * hour, now))
        assertTrue(UpdateCheck.DAILY.due(now - 25 * hour, now))
    }

    @Test
    fun `每周一次 waits out the week`() {
        assertFalse(UpdateCheck.WEEKLY.due(now - 6 * 24 * hour, now))
        assertTrue(UpdateCheck.WEEKLY.due(now - 8 * 24 * hour, now))
    }

    @Test
    fun `a first run is always due`() {
        assertTrue(UpdateCheck.DAILY.due(0L, now))
        assertTrue(UpdateCheck.WEEKLY.due(0L, now))
    }

    @Test
    fun `a clock that went backwards does not park the check in the future`() {
        // A wrong system date could otherwise leave "last checked" ahead of now
        // and stop the app updating until the calendar caught up.
        assertTrue(UpdateCheck.DAILY.due(now + 500 * hour, now))
    }

    @Test
    fun `the default is once a day and unknown keys fall back to it`() {
        assertEquals(UpdateCheck.DAILY, UpdateCheck.DEFAULT)
        assertEquals(UpdateCheck.DAILY, UpdateCheck.from(null))
        assertEquals(UpdateCheck.DAILY, UpdateCheck.from("每小时"))
        assertEquals(UpdateCheck.WEEKLY, UpdateCheck.from("weekly"))
    }
}
