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
        // web "On {server}": Theme, then the server's Notifications; Server Profile opens the
        // workspace right after them.
        assertEquals(SettingsNativeDetailPage.ServerNotifications.key, keys[theme + 1])
        assertEquals(SettingsNativeDetailPage.Server.key, keys[theme + 2])
        assertEquals(SettingsDestinationStorage, keys[theme - 1])
    }

    @Test
    fun navigationHasNoOnServerGroupWithoutAServer() {
        val withServer = settingsHomeRowKeys(hasThemeServer = true)
        val withoutServer = settingsHomeRowKeys(hasThemeServer = false)
        assertFalse(SettingsNativeDetailPage.Theme.key in withoutServer)
        assertFalse(SettingsNativeDetailPage.ServerNotifications.key in withoutServer)
        assertEquals(
            withServer - SettingsNativeDetailPage.Theme.key - SettingsNativeDetailPage.ServerNotifications.key,
            withoutServer,
        )
    }

    @Test
    fun serverNotificationsIsANativeSettingsPageInTheWidePane() {
        assertEquals(SettingsNativeDetailPage.ServerNotifications, SettingsNativeDetailPage.fromKey("server-notifications"))
        val key = SettingsNativeDetailPage.ServerNotifications.key
        assertEquals(SettingsWidePick.Detail(key), settingsWidePick(key))
    }

    @Test
    fun documentationFollowsAboutAndLeavesRaft() {
        val keys = settingsHomeRowKeys(hasThemeServer = true)
        // web Resources: About, Documentation, Feedback, Release Notes.
        assertEquals(SettingsNativeDetailPage.About.key, keys[keys.indexOf(SettingsDestinationDocumentation) - 1])
        assertEquals(SettingsDestinationHandsFeedback, keys[keys.indexOf(SettingsDestinationDocumentation) + 1])
        // A link out, not a section: the wide layout does not open it in its pane.
        assertEquals(SettingsWidePick.Push, settingsWidePick(SettingsDestinationDocumentation))
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
