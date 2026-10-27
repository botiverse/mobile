package build.raft.app

import build.raft.app.data.SharedSettingsAboutSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsAboutSourceCodeTest {
    private val actionRows =
        SharedSettingsAboutSnapshot(appName = "Raft", versionLabel = "1.42.0", buildLabel = "1420")
            .toAboutPayload(webUrl = "https://app.raft.build", errorStrings = englishErrorPresentationStringsForTest())
            .sections.first { it.title == "Actions" }.rows

    @Test
    fun aboutShowsSourceCodeRowWithLicenseAsSecondaryText() {
        val row = actionRows.single { it.key == SETTINGS_ABOUT_SOURCE_CODE_ACTION }

        assertEquals("Source code", row.title)
        assertEquals("FSL-1.1-ALv2", row.detail)
        assertEquals("Open", row.status)
        assertTrue(row.enabled)
        assertTrue(row.external)
        // FSL is source-available, not OSI open source: the row never says "open source".
        assertFalse(row.title.contains("open source", ignoreCase = true))
        // It sits next to the third-party licenses row.
        assertEquals("openSourceLicenses", actionRows[actionRows.indexOf(row) + 1].key)
    }

    @Test
    fun sourceCodeRowOpensTheExactRepositoryUrl() {
        val row = actionRows.single { it.key == SETTINGS_ABOUT_SOURCE_CODE_ACTION }

        assertEquals(
            "https://github.com/botiverse/mobile",
            settingsAboutExternalUrl(row.key, row.value, webUrl = "https://app.raft.build")
        )
        assertEquals(
            "https://github.com/botiverse/mobile",
            settingsAboutExternalUrl(SETTINGS_ABOUT_SOURCE_CODE_ACTION, null, webUrl = "https://app.raft.build")
        )
    }

    @Test
    fun openRaftRowStillOpensTheWebAppAndOtherRowsOpenNothing() {
        assertEquals("https://app.raft.build", settingsAboutExternalUrl("openSlock", null, webUrl = "https://app.raft.build"))
        assertNull(settingsAboutExternalUrl("openSourceLicenses", null, webUrl = "https://app.raft.build"))
    }
}
