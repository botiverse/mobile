package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.settings.SettingsThemePageSection
import build.raft.app.settings.SettingsThemePageSections
import build.raft.app.settings.settingsAppearanceShowsModeCard
import build.raft.app.settings.settingsThemeDecorSwitchShown
import build.raft.app.settings.settingsThemePageSections
import build.raft.app.shell.raftAdaptiveNavRailActive
import build.raft.app.shell.raftWindowWide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > Theme as the web has it after botiverse/slock#11355: its own page in the "On
 * {server}" group, light and dark at its top, and Appearance showing light and dark only where
 * there is no Theme page.
 */
class SettingsThemePageTest {
    @Test
    fun themePageLeadsWithLightAndDarkThenTheThemesThenTheDecor() {
        assertEquals(
            listOf(
                SettingsThemePageSection.Mode,
                SettingsThemePageSection.Basics,
                SettingsThemePageSection.Avatars,
                SettingsThemePageSection.Decor,
            ),
            settingsThemePageSections(hasServer = true, decorSwitch = true),
        )
        assertEquals(SettingsThemePageSection.Mode, SettingsThemePageSections.first())
    }

    @Test
    fun aPhoneHasThePixelArtSwitchWhereItsWindowHasTheRail() {
        // #307: the frame's pixel art is drawn on the rail and the wide top bar. A phone held
        // sideways (874x402) is wide enough for the rail, so it draws the art and needs the switch
        // to turn it off; held upright it has neither (settings cleanup, 1.27.0).
        fun railShown(widthDp: Int, heightDp: Int): Boolean =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = true,
                paneState =
                    reduceRaftAdaptivePane(
                        RaftAdaptivePaneState(),
                        RaftAdaptivePaneEvent(widthDp * 3, heightDp * 3, 3f, RaftAdaptivePanePosture.Flat),
                    ),
            )
        fun phoneHasSwitch(widthDp: Int): Boolean =
            SettingsThemePageSection.Decor in
                settingsThemePageSections(
                    hasServer = true,
                    decorSwitch = settingsThemeDecorSwitchShown(largeScreen = false, windowWide = raftWindowWide(widthDp.dp)),
                )
        assertTrue(railShown(874, 402))
        assertTrue(phoneHasSwitch(874), "held sideways, with the rail")
        assertFalse(railShown(402, 874))
        assertFalse(phoneHasSwitch(402), "held upright")
        // A large screen keeps it in any window, a split-screen half included.
        assertTrue(settingsThemeDecorSwitchShown(largeScreen = true, windowWide = false))
    }

    @Test
    fun appearanceShowsLightAndDarkWithNoServer() {
        assertTrue(settingsAppearanceShowsModeCard(session(selectedServerId = null, servers = emptyList())))
    }

    private fun session(
        selectedServerId: String?,
        servers: List<StoredAuthServer> = listOf(StoredAuthServer(id = "server-1", name = "Acme", slug = "acme")),
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@raft.test"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = selectedServerId,
            servers = servers,
        )
}
