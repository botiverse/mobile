package build.raft.app

import build.raft.app.data.LANGUAGE_REGION_ACTION_LANGUAGE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIME_FORMAT
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIMEZONE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW
import build.raft.app.data.LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY
import build.raft.app.data.SettingsLanguageRegionPreferenceUpdateRequest
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.settings.SettingsLanguageRegionPick
import build.raft.app.settings.SettingsPickSaveResult
import build.raft.app.settings.settingsLanguageRegionApplyIntent
import build.raft.app.settings.settingsLanguageRegionBusy
import build.raft.app.settings.settingsLanguageRegionShownValue
import build.raft.app.settings.settingsPickSaveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsLanguageRegionInstantApplyTest {
    private val snapshot = languageRegionSnapshot(language = "browser", display = "automatic")

    @Test
    fun eachChoiceAppliesOnItsOwnWhenPicked() {
        assertEquals(
            SharedSettingsIntent.SetTranslationMode("manual"),
            settingsLanguageRegionApplyIntent(snapshot, pick(LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY, "manual"))
        )
        assertEquals(
            SharedSettingsIntent.SetTranslationTargetLanguage("ja"),
            settingsLanguageRegionApplyIntent(snapshot, pick(LANGUAGE_REGION_ACTION_LANGUAGE, "ja"))
        )
        assertEquals(
            SharedSettingsIntent.UpdateLanguageRegionPreference(SettingsLanguageRegionPreferenceUpdateRequest(preferredTimezone = "Asia/Tokyo")),
            settingsLanguageRegionApplyIntent(snapshot, pick(LANGUAGE_REGION_ACTION_TIMEZONE, "Asia/Tokyo"))
        )
        assertEquals(
            SharedSettingsIntent.UpdateLanguageRegionPreference(SettingsLanguageRegionPreferenceUpdateRequest(preferredTimeFormat = "12h")),
            settingsLanguageRegionApplyIntent(snapshot, pick(LANGUAGE_REGION_ACTION_TIME_FORMAT, "12h"))
        )
        assertEquals(
            SharedSettingsIntent.UpdateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTranslationDisplay = "bilingual")
            ),
            settingsLanguageRegionApplyIntent(snapshot, pick(LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW, "bilingual"))
        )
        assertEquals(
            null,
            settingsLanguageRegionApplyIntent(snapshot, pick(LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW, "translated"))
        )
    }

    @Test
    fun pickShowsAndLocksThePageUntilItsRequestSettles() {
        val picked = pick(LANGUAGE_REGION_ACTION_TIME_FORMAT, "12h")

        // Dispatched, store not started yet.
        assertEquals("12h", shown(picked, actionState = null))
        assertTrue(settingsLanguageRegionBusy(null, picked))

        val running = SharedSettingsActionState(LANGUAGE_REGION_ACTION_TIME_FORMAT, inProgress = true)
        assertEquals("12h", shown(picked, running))
        assertTrue(settingsLanguageRegionBusy(running, picked))
    }

    @Test
    fun saveStatusSaysWhatFailedAndTheControlGoesBack() {
        val picked = pick(LANGUAGE_REGION_ACTION_TIMEZONE, "Asia/Tokyo")
        val offline = SharedSettingsActionState(LANGUAGE_REGION_ACTION_TIMEZONE, errorCode = "network", screenError = ScreenError("network"))
        val refused =
            SharedSettingsActionState(
                LANGUAGE_REGION_ACTION_TIMEZONE,
                errorCode = "bad_request",
                screenError = ScreenError("bad_request", serverMessage = "Unknown timezone")
            )

        // No refusal of its own: the page's message ("Failed to update date and time preferences").
        assertEquals(SettingsPickSaveResult.Failed(serverMessage = null), settingsPickSaveResult(picked, offline))
        assertEquals("UTC", settingsLanguageRegionShownValue("UTC", LANGUAGE_REGION_ACTION_TIMEZONE, picked, offline))
        // The server's refusal, as web `apiErrorText`.
        assertEquals(SettingsPickSaveResult.Failed("Unknown timezone"), settingsPickSaveResult(picked, refused))
        assertEquals("UTC", settingsLanguageRegionShownValue("UTC", LANGUAGE_REGION_ACTION_TIMEZONE, picked, refused))
    }

    private fun pick(
        action: String,
        value: String,
        actionStateAtPick: SharedSettingsActionState? = null
    ): SettingsLanguageRegionPick = SettingsLanguageRegionPick(action, value, actionStateAtPick)

    private fun shown(
        pick: SettingsLanguageRegionPick,
        actionState: SharedSettingsActionState?,
        saved: String = "24h"
    ): String = settingsLanguageRegionShownValue(saved, pick.action, pick, actionState)

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
