package build.raft.app

import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SettingsLanguageRegionPreferenceRequestTest {
    @Test
    fun modeOnlySaveDoesNotBuildAccountRequest() {
        val request =
            buildSettingsLanguageRegionPreferenceUpdateRequest(
                snapshot = languageRegionSnapshot(language = "browser", display = "automatic"),
                languageDraft = "browser",
                displayDraft = "manual"
            )

        assertNull(request)
    }

    @Test
    fun originalModeDoesNotSubmitHiddenLanguageDraft() {
        val request =
            buildSettingsLanguageRegionPreferenceUpdateRequest(
                snapshot = languageRegionSnapshot(language = "en", display = "automatic"),
                languageDraft = "zh",
                displayDraft = "original"
            )

        assertNull(request)
    }

    @Test
    fun automaticModeCanSubmitChangedLanguageSeparately() {
        val request =
            buildSettingsLanguageRegionPreferenceUpdateRequest(
                snapshot = languageRegionSnapshot(language = "browser", display = "original"),
                languageDraft = "en",
                displayDraft = "automatic"
            )

        assertNull(request?.preferredTranslationDisplay)
        assertEquals("en", request?.preferredLanguage)
        assertFalse(request?.clearPreferredLanguage == true)
    }

    @Test
    fun browserLanguageClearsExplicitTarget() {
        val request =
            buildSettingsLanguageRegionPreferenceUpdateRequest(
                snapshot = languageRegionSnapshot(language = "ja", display = "automatic"),
                languageDraft = "browser",
                displayDraft = "automatic"
            )

        assertNull(request?.preferredLanguage)
        assertEquals(true, request?.clearPreferredLanguage)
    }

    private fun languageRegionSnapshot(
        language: String,
        display: String
    ): SettingsLanguageRegionSnapshot =
        SettingsLanguageRegionSnapshot(
            serverTranslation = toggleSnapshot(),
            autoTranslation = toggleSnapshot(),
            browserLanguage =
                SettingsMobileSelectionSnapshot(
                    selectedKey = language,
                    selectedLabel = language,
                    statusLabel = language,
                    detail = "",
                    options =
                        listOf(
                            SettingsMobileSelectionOptionSnapshot("browser", "Browser language"),
                            SettingsMobileSelectionOptionSnapshot("en", "English"),
                            SettingsMobileSelectionOptionSnapshot("ja", "Japanese")
                        )
                ),
            timezone =
                SettingsMobileSelectionSnapshot(
                    selectedKey = "UTC",
                    selectedLabel = "UTC",
                    statusLabel = "UTC",
                    detail = "",
                    options = emptyList()
                ),
            timeFormat =
                SettingsMobileSelectionSnapshot(
                    selectedKey = "24h",
                    selectedLabel = "24-hour",
                    statusLabel = "24-hour",
                    detail = "",
                    options = emptyList()
                ),
            translationDisplay =
                SettingsMobileSelectionSnapshot(
                    selectedKey = display,
                    selectedLabel = display,
                    statusLabel = display,
                    detail = "",
                    options =
                        listOf(
                            SettingsMobileSelectionOptionSnapshot("automatic", "Automatic"),
                            SettingsMobileSelectionOptionSnapshot("manual", "Manual"),
                            SettingsMobileSelectionOptionSnapshot("original", "Original")
                        )
                )
        )

    private fun toggleSnapshot(): SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = true,
            available = true,
            statusLabel = "Enabled",
            detail = ""
        )
}
