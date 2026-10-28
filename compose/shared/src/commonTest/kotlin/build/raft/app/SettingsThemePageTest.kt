package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
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
            settingsThemePageSections(hasServer = true),
        )
        assertEquals(SettingsThemePageSection.Mode, SettingsThemePageSections.first())
    }

    @Test
    fun themePageWithoutAServerKeepsOnlyLightAndDark() {
        assertEquals(listOf(SettingsThemePageSection.Mode), settingsThemePageSections(hasServer = false))
    }

    @Test
    fun appearanceHidesLightAndDarkWhenAServerIsCurrent() {
        assertFalse(settingsAppearanceShowsModeCard(session(selectedServerId = "server-1")))
        // No server picked: the session's first server is current, so the Theme page is there.
        assertFalse(settingsAppearanceShowsModeCard(session(selectedServerId = null)))
    }

    @Test
    fun appearanceShowsLightAndDarkWithNoServer() {
        assertTrue(settingsAppearanceShowsModeCard(session(selectedServerId = null, servers = emptyList())))
    }

    @Test
    fun appearanceWaitsForTheSessionBeforeShowingLightAndDark() {
        assertFalse(settingsAppearanceShowsModeCard(null))
    }

    @Test
    fun navigationPlacesThemeAfterThePersonalRowsAndBeforeTheWorkspace() {
        val keys = settingsHomeRowKeys(hasThemeServer = true)
        val theme = keys.indexOf(SettingsNativeDetailPage.Theme.key)
        assertTrue(theme > keys.indexOf(SettingsNativeDetailPage.Appearance.key))
        assertTrue(theme > keys.indexOf(SettingsNativeDetailPage.Notifications.key))
        // The app has no server Notifications row (its mute is on Notifications), so Theme is the
        // last of "On {server}" and Server Profile opens the workspace right after it.
        assertEquals(SettingsNativeDetailPage.Server.key, keys[theme + 1])
        assertEquals(SettingsDestinationStorage, keys[theme - 1])
    }

    @Test
    fun navigationHasNoThemeWithoutAServer() {
        val withServer = settingsHomeRowKeys(hasThemeServer = true)
        val withoutServer = settingsHomeRowKeys(hasThemeServer = false)
        assertFalse(SettingsNativeDetailPage.Theme.key in withoutServer)
        assertEquals(withServer - SettingsNativeDetailPage.Theme.key, withoutServer)
    }

    @Test
    fun themeIsANativeSettingsPage() {
        assertEquals(SettingsNativeDetailPage.Theme, SettingsNativeDetailPage.fromKey("theme"))
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
