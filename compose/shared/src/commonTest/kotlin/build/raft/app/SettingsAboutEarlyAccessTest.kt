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
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.preservingChannelMetadataFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsAboutEarlyAccessTest {
    private val about = SharedSettingsAboutSnapshot(appName = "Raft", versionLabel = "1.31.0", buildLabel = "13100001")
    private val officialApi = SharedApiEnvironmentStore.Production.apiBaseUrl

    private fun sessionWithServers(
        selectedSlug: String? = null,
        vararg slugs: String?
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "u-1", email = "member@botiverse.dev", name = "member"),
            accessToken = "tok",
            refreshToken = "ref",
            selectedServerSlug = selectedSlug,
            servers = slugs.mapIndexed { index, slug ->
                StoredAuthServer(id = "srv-$index", name = slug ?: "Server $index", slug = slug)
            }
        )

    private fun developer(
        apiBaseUrl: String = officialApi,
        updateCheck: SharedSettingsUpdateCheckSnapshot = SharedSettingsUpdateCheckSnapshot()
    ): SharedSettingsDeveloperSnapshot =
        SharedSettingsDeveloperSnapshot(
            environmentKey = "production",
            environmentLabel = "Production",
            apiBaseUrl = apiBaseUrl,
            updateCheck = updateCheck
        )

    @Test
    fun botiverseMemberDetectionRequiresOfficialProductionApiAndBotiverseSlug() {
        val officialDev = developer()
        val selfHostedDev = developer(apiBaseUrl = "https://raft.example.test/api")

        assertTrue(settingsAboutIsBotiverseMember(sessionWithServers(selectedSlug = "botiverse"), officialDev))
        assertTrue(settingsAboutIsBotiverseMember(sessionWithServers(selectedSlug = "other", "other", "Botiverse"), officialDev))
        assertFalse(settingsAboutIsBotiverseMember(sessionWithServers(selectedSlug = "other", "other", null), officialDev))
        assertFalse(settingsAboutIsBotiverseMember(sessionWithServers(selectedSlug = "botiverse", "botiverse"), selfHostedDev))
        assertFalse(settingsAboutIsBotiverseMember(null, officialDev))
        assertFalse(settingsAboutIsBotiverseMember(sessionWithServers(selectedSlug = "botiverse"), null))
    }

    @Test
    fun androidDirectInstallShowsAlphaToggleForBotiverseMembersAndRemainsVisibleForOptedInUsers() {
        val mainUpdate =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                updateChannel = "main",
                updateChannelSwitchSupported = true
            )
        val alphaUpdate = mainUpdate.copy(updateChannel = "alpha")
        val dev = developer(updateCheck = mainUpdate)

        // Regular non-botiverse member on main sees no early-access toggle.
        assertNull(
            settingsAboutEarlyAccessOption(
                session = sessionWithServers(selectedSlug = "acme", "acme"),
                developer = dev,
                updateCheck = mainUpdate,
                actionState = null
            )
        )

        // Botiverse member on main sees the toggle OFF.
        val offOption =
            assertIs<SettingsAboutEarlyAccessOption.HandsAlphaToggle>(
                settingsAboutEarlyAccessOption(
                    session = sessionWithServers(selectedSlug = "acme", "acme", "botiverse"),
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
        assertTrue(offRow.enabled)

        // Someone already on alpha still sees the toggle ON even after leaving botiverse, so they can opt out cleanly.
        val activeOption =
            assertIs<SettingsAboutEarlyAccessOption.HandsAlphaToggle>(
                settingsAboutEarlyAccessOption(
                    session = sessionWithServers(selectedSlug = "acme", "acme"),
                    developer = dev.copy(updateCheck = alphaUpdate),
                    updateCheck = alphaUpdate,
                    actionState = SharedSettingsActionState(DEVELOPER_ACTION_HANDS_UPDATE_CHANNEL, inProgress = true)
                )
            )
        assertTrue(activeOption.enabled)
        assertTrue(activeOption.busy)

        val activePayload =
            about.toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest(),
                updateCheck = alphaUpdate,
                earlyAccessOption = activeOption
            )
        val activeRow = activePayload.sections.first { it.title == "Actions" }.rows.single { it.key == SETTINGS_ABOUT_EARLY_ACCESS_TOGGLE_ACTION }
        assertEquals(true, activeRow.toggleValue)
        assertEquals("Alpha", activeRow.badgeLabel)
        assertTrue(activeRow.loading)
        assertFalse(activeRow.enabled)
    }

    @Test
    fun googlePlayAndIosSurfaceExternalEarlyAccessGuidanceForBotiverseMembersOnly() {
        val botiverse = sessionWithServers(selectedSlug = "botiverse", "botiverse")
        val nonMember = sessionWithServers(selectedSlug = "other", "other")

        val playUpdate =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                storeChannel = "play",
                updateChannel = "play",
                updateChannelSwitchSupported = false,
                earlyAccessGuideKind = RAFT_EARLY_ACCESS_GUIDE_GOOGLE_PLAY_BETA,
                earlyAccessGuideUrl = RAFT_GOOGLE_PLAY_BETA_URL
            )
        assertNull(settingsAboutEarlyAccessOption(nonMember, developer(updateCheck = playUpdate), playUpdate, null))
        val playOption =
            assertIs<SettingsAboutEarlyAccessOption.ExternalProgram>(
                settingsAboutEarlyAccessOption(botiverse, developer(updateCheck = playUpdate), playUpdate, null)
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
        val iosJoinOption =
            assertIs<SettingsAboutEarlyAccessOption.ExternalProgram>(
                settingsAboutEarlyAccessOption(botiverse, developer(updateCheck = iosJoinUpdate), iosJoinUpdate, null)
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
                settingsAboutEarlyAccessOption(botiverse, developer(updateCheck = iosActiveUpdate), iosActiveUpdate, null)
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
