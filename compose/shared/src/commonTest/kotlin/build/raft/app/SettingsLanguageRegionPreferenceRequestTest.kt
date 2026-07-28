package build.raft.app

import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsLanguageRegionPreferenceRequestTest {
    @Test
    fun modeOnlySaveDoesNotBuildTargetUpdate() {
        val target =
            resolveSettingsTranslationTargetUpdate(
                snapshot = languageRegionSnapshot(language = "browser", display = "automatic"),
                languageDraft = "browser",
                displayDraft = "manual"
            )

        assertNull(target)
    }

    @Test
    fun originalModeDoesNotSubmitHiddenLanguageDraft() {
        val target =
            resolveSettingsTranslationTargetUpdate(
                snapshot = languageRegionSnapshot(language = "en", display = "automatic"),
                languageDraft = "zh",
                displayDraft = "original"
            )

        assertNull(target)
    }

    @Test
    fun automaticModeCanSaveChangedLocalTargetSeparately() {
        val target =
            resolveSettingsTranslationTargetUpdate(
                snapshot = languageRegionSnapshot(language = "browser", display = "original"),
                languageDraft = "en",
                displayDraft = "automatic"
            )

        assertEquals("en", target)
    }

    @Test
    fun browserLanguageBecomesLocalBrowserTarget() {
        val target =
            resolveSettingsTranslationTargetUpdate(
                snapshot = languageRegionSnapshot(language = "ja", display = "automatic"),
                languageDraft = "browser",
                displayDraft = "automatic"
            )

        assertEquals("browser", target)
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
                            SettingsMobileSelectionOptionSnapshot("browser", "System language"),
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
