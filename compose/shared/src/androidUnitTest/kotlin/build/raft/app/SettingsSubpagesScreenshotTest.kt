package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.SettingsSectionAvailability
import build.raft.app.data.SettingsServerProfileSnapshot
import build.raft.app.data.SettingsServerSectionSnapshot
import build.raft.app.data.SettingsServerSnapshot
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings sub-pages built from design-system parts (the first RaftBrutal migration batch,
 * docs/raftbrutal-migration.md), light and dark, on fixed fixture data:
 * - Language & region: the translation-language and timezone pickers (raft-ui ComboboxSelect)
 *   and the two Save buttons, one enabled;
 * - Server profile: the name field, the read-only slug, Save, and the danger zone's Leave
 *   (warning) and Delete (danger) rows;
 * - API environment (developer): the four text fields;
 * - a load-state card with Retry, and the settings web view's error state.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class SettingsSubpagesScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun languageRegionLight() = capture("settings.language-region", RaftAppAppearance.Light) { LanguageRegionFixture() }

    @Test
    fun languageRegionDark() = capture("settings.language-region.dark", RaftAppAppearance.Dark) { LanguageRegionFixture() }

    @Test
    fun serverProfileLight() = capture("settings.server-profile", RaftAppAppearance.Light) { ServerProfileFixture() }

    @Test
    fun serverProfileDark() = capture("settings.server-profile.dark", RaftAppAppearance.Dark) { ServerProfileFixture() }

    @Test
    fun apiEnvironmentLight() = capture("settings.api-environment", RaftAppAppearance.Light) { ApiEnvironmentFixture() }

    @Test
    fun apiEnvironmentDark() = capture("settings.api-environment.dark", RaftAppAppearance.Dark) { ApiEnvironmentFixture() }

    @Test
    fun statesLight() = capture("settings.states", RaftAppAppearance.Light) { StatesFixture() }

    @Test
    fun statesDark() = capture("settings.states.dark", RaftAppAppearance.Dark) { StatesFixture() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { content() }
            }
        }
}

@Composable
private fun LanguageRegionFixture() {
    SettingsLanguageRegionContent(
        state = ScreenLoadState.Content(languageRegionFixture),
        mobileState = ScreenLoadState.Loading,
        actionState = null,
        languageDraft = "",
        displayDraft = "",
        timezoneDraft = "Asia/Tokyo",
        timeFormatDraft = "24h",
        bottomContentPadding = 16.dp,
        onLanguageDraftChange = {},
        onDisplayDraftChange = {},
        onTimezoneDraftChange = {},
        onTimeFormatDraftChange = {},
        onServerTranslationChange = {},
        onLocaleChange = {},
        onSaveLanguage = {},
        onSaveTime = {},
        onRetry = {},
    )
}

@Composable
private fun ServerProfileFixture() {
    SharedSettingsServerContent(
        payload = RaftSharedSettingsPayload(instanceId = "settings-server-screenshot", heroLabel = "Raft HQ"),
        profileName = "Raft HQ Design",
        onProfileNameChange = {},
        serverSnapshot =
            SettingsServerSnapshot(
                profile = SettingsServerProfileSnapshot(name = "Raft HQ", slug = "raft-hq", canSave = true),
                sections =
                    listOf(
                        SettingsServerSectionSnapshot(
                            key = "danger",
                            title = StringDesc.Resource(Res.string.settings_section_danger_zone_title),
                            detail = "",
                            state = SettingsSectionAvailability.Available,
                        ),
                    ),
            ),
        serverRole = "admin",
        actionState = null,
        expectedServerSlug = "raft-hq",
        onAction = { _, _ -> },
    )
}

@Composable
private fun ApiEnvironmentFixture() {
    SharedSettingsApiEnvironmentContent(
        state =
            ScreenLoadState.Content(
                SharedSettingsDeveloperSnapshot(
                    environmentKey = "custom",
                    environmentLabel = "Staging",
                    apiBaseUrl = "https://api.staging.raft.build/api/",
                    socketBaseUrl = "https://api.staging.raft.build",
                    webUrl = "",
                ),
            ),
        actionState = null,
        onSaveApiEnvironment = {},
    )
}

@Composable
private fun StatesFixture() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        Column(Modifier.padding(16.dp)) {
            SettingsLoadStateCard(
                iconName = RaftLucideIconName.TriangleAlert,
                title = "Language & region unavailable",
                detail = "The server did not respond in time.",
                retryLabel = "Retry",
                onRetry = {},
            )
        }
        SettingsWebViewErrorContent(actionLabel = "Retry", actionIcon = RaftLucideIconName.RefreshCw, onAction = {})
    }
}

private val languageRegionFixture =
    SettingsLanguageRegionSnapshot(
        serverTranslation = SettingsMobileToggleSnapshot(enabled = true, available = true, statusLabel = "On", detail = ""),
        autoTranslation = SettingsMobileToggleSnapshot(enabled = true, available = true, statusLabel = "On", detail = ""),
        browserLanguage =
            SettingsMobileSelectionSnapshot(
                selectedKey = "ja",
                selectedLabel = "日本語",
                statusLabel = "日本語",
                detail = "",
                options =
                    listOf(
                        SettingsMobileSelectionOptionSnapshot("system", "System"),
                        SettingsMobileSelectionOptionSnapshot("en", "English"),
                        SettingsMobileSelectionOptionSnapshot("zh-CN", "简体中文"),
                        SettingsMobileSelectionOptionSnapshot("ja", "日本語"),
                    ),
            ),
        timezone =
            SettingsMobileSelectionSnapshot(
                selectedKey = "UTC",
                selectedLabel = "UTC",
                statusLabel = "UTC",
                detail = "",
                options =
                    listOf(
                        SettingsMobileSelectionOptionSnapshot("UTC", "UTC"),
                        SettingsMobileSelectionOptionSnapshot("Asia/Shanghai", "Asia/Shanghai (UTC+8)"),
                        SettingsMobileSelectionOptionSnapshot("Asia/Tokyo", "Asia/Tokyo (UTC+9)"),
                    ),
            ),
        timeFormat =
            SettingsMobileSelectionSnapshot(
                selectedKey = "24h",
                selectedLabel = "24-hour",
                statusLabel = "24-hour",
                detail = "",
                options =
                    listOf(
                        SettingsMobileSelectionOptionSnapshot("12h", "12-hour"),
                        SettingsMobileSelectionOptionSnapshot("24h", "24-hour"),
                    ),
            ),
    )
