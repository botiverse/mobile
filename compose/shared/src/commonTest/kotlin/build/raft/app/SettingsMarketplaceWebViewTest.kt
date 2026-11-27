package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.settings.settingsBillingWebViewSpec
import build.raft.app.settings.settingsMarketplaceWebViewSpec
import build.raft.app.settings.settingsMarketplaceWebViewUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsMarketplaceWebViewTest {
    @Test
    fun marketplaceUrlCarriesInstalledAppQueryWhenProvided() {
        val session = marketplaceSession()

        assertEquals(
            "https://app.raft.build/s/workspace/apps?embed=raft-settings-v1&shell=host&hostOpen=app&installed_app=stamp-app",
            settingsMarketplaceWebViewUrl("https://app.raft.build/", session, installedAppId = "stamp-app")
        )
        assertEquals(
            "https://app.raft.build/s/workspace/apps?embed=raft-settings-v1&shell=host&hostOpen=app&installed_app=app%2F1%26b%3D2",
            settingsMarketplaceWebViewUrl("https://app.raft.build/", session, installedAppId = " app/1&b=2 ")
        )
        assertEquals(
            "https://app.raft.build/s/workspace/apps?embed=raft-settings-v1&shell=host&hostOpen=app",
            settingsMarketplaceWebViewUrl("https://app.raft.build/", session, installedAppId = "   ")
        )
        assertEquals(
            "https://app.raft.build/settings/integrations?embed=raft-settings-v1&shell=host&hostOpen=app",
            settingsMarketplaceWebViewUrl("https://app.raft.build/", session = null)
        )
    }

    @Test
    fun marketplaceSpecLocksMainFrameToRaftWebOriginAndEnablesAppEntriesOnlyThere() {
        assertTrue(settingsMarketplaceWebViewSpec.lockToWebOrigin)
        assertTrue(settingsMarketplaceWebViewSpec.opensAppEntries)
        assertTrue(settingsMarketplaceWebViewSpec("stamp-app").lockToWebOrigin)
        assertTrue(settingsMarketplaceWebViewSpec("stamp-app").opensAppEntries)
        assertFalse(settingsBillingWebViewSpec.lockToWebOrigin)
        assertFalse(settingsBillingWebViewSpec.opensAppEntries)
    }

    private fun marketplaceSession() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "native-refresh-secret",
            selectedServerId = "server-1",
            selectedServerSlug = "workspace",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace"))
        )
}
