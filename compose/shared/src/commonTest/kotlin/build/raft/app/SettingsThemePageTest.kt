package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals
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
            settingsThemePageSections(hasServer = true, largeScreen = true),
        )
        assertEquals(SettingsThemePageSection.Mode, SettingsThemePageSections.first())
    }

    @Test
    fun aPhoneHasNoPixelArtSwitch() {
        // The frame's pixel art shows on wide screens only: an ordinary phone never sees it.
        assertEquals(
            listOf(SettingsThemePageSection.Mode, SettingsThemePageSection.Basics, SettingsThemePageSection.Avatars),
            settingsThemePageSections(hasServer = true, largeScreen = false),
        )
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
