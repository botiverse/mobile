package build.raft.app

import build.raft.app.data.DEVELOPER_ACTION_HANDS_UPDATE_CHANNEL
import build.raft.app.data.RAFT_EARLY_ACCESS_GUIDE_GOOGLE_PLAY_BETA
import build.raft.app.data.RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_ACTIVE
import build.raft.app.data.RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_JOIN
import build.raft.app.data.RAFT_GOOGLE_PLAY_BETA_URL
import build.raft.app.data.RAFT_IOS_TESTFLIGHT_JOIN_URL
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.preservingChannelMetadataFrom
import build.raft.app.settings.SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION
import build.raft.app.settings.SETTINGS_ABOUT_EARLY_ACCESS_TOGGLE_ACTION
import build.raft.app.settings.SettingsAboutEarlyAccessOption
import build.raft.app.settings.settingsAboutEarlyAccessChannelForToggle
import build.raft.app.settings.settingsAboutEarlyAccessOption
import build.raft.app.settings.settingsAboutExternalUrl
import build.raft.app.settings.toAboutPayload
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsAboutEarlyAccessTest {
    private val about = SharedSettingsAboutSnapshot(appName = "Raft", versionLabel = "1.31.0", buildLabel = "13100001")

    private fun developer(
        apiBaseUrl: String = SharedApiEnvironmentStore.Production.apiBaseUrl,
        updateCheck: SharedSettingsUpdateCheckSnapshot = SharedSettingsUpdateCheckSnapshot()
    ): SharedSettingsDeveloperSnapshot =
        SharedSettingsDeveloperSnapshot(
            environmentKey = "production",
            environmentLabel = "Production",
            apiBaseUrl = apiBaseUrl,
            updateCheck = updateCheck
        )

    @Test
    fun androidDirectInstallShowsBetaToggleOnlyWhenServerVisibilityFlagIsEnabled() {
        val mainUpdate =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                updateChannel = "main",
                updateChannelSwitchSupported = true
            )
        val betaUpdate = mainUpdate.copy(updateChannel = "beta")
        val alphaUpdate = mainUpdate.copy(updateChannel = "alpha")
        val dev = developer(updateCheck = mainUpdate)

        // When mobile_early_access_visible_v0 is off, the row stays hidden regardless of channel.
        assertNull(
            settingsAboutEarlyAccessOption(
                earlyAccessVisible = false,
                developer = dev,
                updateCheck = mainUpdate,
                actionState = null
            )
        )
        assertNull(
            settingsAboutEarlyAccessOption(
                earlyAccessVisible = false,
                developer = dev.copy(updateCheck = betaUpdate),
                updateCheck = betaUpdate,
                actionState = null
            )
        )
        assertNull(
            settingsAboutEarlyAccessOption(
                earlyAccessVisible = false,
                developer = dev.copy(updateCheck = alphaUpdate),
                updateCheck = alphaUpdate,
                actionState = null
            )
        )

        // Visible + on main shows the toggle OFF.
        val offOption =
            assertIs<SettingsAboutEarlyAccessOption.HandsBetaToggle>(
                settingsAboutEarlyAccessOption(
                    earlyAccessVisible = true,
                    developer = dev,
                    updateCheck = mainUpdate,
                    actionState = null
                )
            )
        assertFalse(offOption.enabled)
        assertFalse(offOption.busy)

        val offPayload =
            about.toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest(),
                updateCheck = mainUpdate,
                earlyAccessOption = offOption
            )
        val offRow = offPayload.sections.first { it.title == "Actions" }.rows.single { it.key == SETTINGS_ABOUT_EARLY_ACCESS_TOGGLE_ACTION }
        assertEquals(false, offRow.toggleValue)
        assertEquals("", offRow.badgeLabel)
        assertTrue(offRow.detail.contains("beta channel"))
        assertTrue(offRow.enabled)

        // Visible + on beta shows the toggle ON with Beta badge (and busy disables interaction during switch).
        val activeOption =
            assertIs<SettingsAboutEarlyAccessOption.HandsBetaToggle>(
                settingsAboutEarlyAccessOption(
                    earlyAccessVisible = true,
                    developer = dev.copy(updateCheck = betaUpdate),
                    updateCheck = betaUpdate,
                    actionState = SharedSettingsActionState(DEVELOPER_ACTION_HANDS_UPDATE_CHANNEL, inProgress = true)
                )
            )
        assertTrue(activeOption.enabled)
        assertTrue(activeOption.busy)

        val activePayload =
            about.toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest(),
                updateCheck = betaUpdate,
                earlyAccessOption = activeOption
            )
        val activeRow = activePayload.sections.first { it.title == "Actions" }.rows.single { it.key == SETTINGS_ABOUT_EARLY_ACCESS_TOGGLE_ACTION }
        assertEquals(true, activeRow.toggleValue)
        assertEquals("Beta", activeRow.badgeLabel)
        assertTrue(activeRow.detail.contains("beta channel"))
        assertTrue(activeRow.loading)
        assertFalse(activeRow.enabled)

        // Engineering alpha builds are not reported as opted into the human Beta channel.
        val alphaOption =
            assertIs<SettingsAboutEarlyAccessOption.HandsBetaToggle>(
                settingsAboutEarlyAccessOption(
                    earlyAccessVisible = true,
                    developer = dev.copy(updateCheck = alphaUpdate),
                    updateCheck = alphaUpdate,
                    actionState = null
                )
            )
        assertFalse(alphaOption.enabled)

        // Toggling Early access ON writes "beta"; toggling OFF writes "main".
        assertEquals("beta", settingsAboutEarlyAccessChannelForToggle(enableBeta = true))
        assertEquals("main", settingsAboutEarlyAccessChannelForToggle(enableBeta = false))
    }

    @Test
    fun googlePlayAndIosSurfaceExternalEarlyAccessGuidanceOnlyWhenVisibleFlagIsEnabled() {
        val playUpdate =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                storeChannel = "play",
                updateChannel = "play",
                updateChannelSwitchSupported = false,
                earlyAccessGuideKind = RAFT_EARLY_ACCESS_GUIDE_GOOGLE_PLAY_BETA,
                earlyAccessGuideUrl = RAFT_GOOGLE_PLAY_BETA_URL
            )
        assertNull(settingsAboutEarlyAccessOption(earlyAccessVisible = false, developer(updateCheck = playUpdate), playUpdate, null))
        val playOption =
            assertIs<SettingsAboutEarlyAccessOption.ExternalProgram>(
                settingsAboutEarlyAccessOption(earlyAccessVisible = true, developer(updateCheck = playUpdate), playUpdate, null)
            )
        assertEquals(RAFT_GOOGLE_PLAY_BETA_URL, playOption.url)
        assertFalse(playOption.alreadyActive)
        assertEquals(
            RAFT_GOOGLE_PLAY_BETA_URL,
            settingsAboutExternalUrl(SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION, playOption.url, "https://app.raft.build")
        )

        val iosJoinUpdate =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                storeChannel = "app_store",
                earlyAccessGuideKind = RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_JOIN,
                earlyAccessGuideUrl = RAFT_IOS_TESTFLIGHT_JOIN_URL
            )
        assertNull(settingsAboutEarlyAccessOption(earlyAccessVisible = false, developer(updateCheck = iosJoinUpdate), iosJoinUpdate, null))
        val iosJoinOption =
            assertIs<SettingsAboutEarlyAccessOption.ExternalProgram>(
                settingsAboutEarlyAccessOption(earlyAccessVisible = true, developer(updateCheck = iosJoinUpdate), iosJoinUpdate, null)
            )
        assertFalse(iosJoinOption.alreadyActive)
        val iosJoinRow =
            about.toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest(),
                updateCheck = iosJoinUpdate,
                earlyAccessOption = iosJoinOption
            ).sections.first { it.title == "Actions" }.rows.single { it.key == SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION }
        assertEquals("TestFlight", iosJoinRow.status)
        assertEquals("", iosJoinRow.badgeLabel)
        assertTrue(iosJoinRow.external)

        val iosActiveUpdate = iosJoinUpdate.copy(earlyAccessGuideKind = RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_ACTIVE)
        val iosActiveOption =
            assertIs<SettingsAboutEarlyAccessOption.ExternalProgram>(
                settingsAboutEarlyAccessOption(earlyAccessVisible = true, developer(updateCheck = iosActiveUpdate), iosActiveUpdate, null)
            )
        assertTrue(iosActiveOption.alreadyActive)
        val iosActiveRow =
            about.toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest(),
                updateCheck = iosActiveUpdate,
                earlyAccessOption = iosActiveOption
            ).sections.first { it.title == "Actions" }.rows.single { it.key == SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION }
        assertEquals("TestFlight", iosActiveRow.badgeLabel)
        assertEquals("Open", iosActiveRow.status)
    }

    @Test
    fun preservingChannelMetadataKeepsHostChannelAndGuideAcrossStoreRefreshes() {
        val hostMeta =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                updateChannel = "play",
                updateChannelSwitchSupported = false,
                earlyAccessGuideKind = RAFT_EARLY_ACCESS_GUIDE_GOOGLE_PLAY_BETA,
                earlyAccessGuideUrl = RAFT_GOOGLE_PLAY_BETA_URL
            )
        val storeRefresh =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                storeChannel = "play",
                detail = "Raft is up to date."
            )
        val merged = storeRefresh.preservingChannelMetadataFrom(hostMeta)
        assertEquals("play", merged.updateChannel)
        assertEquals(RAFT_EARLY_ACCESS_GUIDE_GOOGLE_PLAY_BETA, merged.earlyAccessGuideKind)
        assertEquals(RAFT_GOOGLE_PLAY_BETA_URL, merged.earlyAccessGuideUrl)
        assertEquals("Raft is up to date.", merged.detail)
    }
}
