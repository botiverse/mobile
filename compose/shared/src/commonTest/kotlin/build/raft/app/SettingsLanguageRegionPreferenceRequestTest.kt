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
    fun displayOnlySaveDoesNotClearLanguage() {
        val request =
            buildSettingsLanguageRegionPreferenceUpdateRequest(
                snapshot = languageRegionSnapshot(language = "other", display = "translated"),
                languageDraft = "other",
                displayDraft = "original"
            )

        assertEquals("original", request?.preferredTranslationDisplay)
        assertNull(request?.preferredLanguage)
        assertFalse(request?.clearPreferredLanguage == true)
    }

    @Test
    fun originalDisplayDoesNotSubmitHiddenLanguageDraft() {
        val request =
            buildSettingsLanguageRegionPreferenceUpdateRequest(
                snapshot = languageRegionSnapshot(language = "en", display = "translated"),
                languageDraft = "zh",
                displayDraft = "original"
            )

        assertEquals("original", request?.preferredTranslationDisplay)
        assertNull(request?.preferredLanguage)
        assertFalse(request?.clearPreferredLanguage == true)
    }

    @Test
    fun translatedDisplayCanSubmitChangedLanguageSeparately() {
        val request =
            buildSettingsLanguageRegionPreferenceUpdateRequest(
                snapshot = languageRegionSnapshot(language = "other", display = "original"),
                languageDraft = "en",
                displayDraft = "translated"
            )

        assertEquals("translated", request?.preferredTranslationDisplay)
        assertEquals("en", request?.preferredLanguage)
        assertFalse(request?.clearPreferredLanguage == true)
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
                            SettingsMobileSelectionOptionSnapshot("other", "System"),
                            SettingsMobileSelectionOptionSnapshot("en", "English")
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
                            SettingsMobileSelectionOptionSnapshot("translated", "Translated"),
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
