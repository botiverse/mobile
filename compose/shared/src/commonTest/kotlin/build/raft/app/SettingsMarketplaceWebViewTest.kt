package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsMarketplaceWebViewTest {
    @Test
    fun marketplaceUrlUsesWorkspaceIntegrationsRoute() {
        val session = marketplaceSession()

        assertEquals(
            "https://app.raft.build/s/workspace/settings/integrations?embed=raft-settings-v1&shell=host",
            settingsMarketplaceWebViewUrl("https://app.raft.build/", session)
        )
    }

    @Test
    fun marketplaceUrlFallsBackToUnscopedIntegrationsRouteWithoutServerSlug() {
        assertEquals(
            "https://app.raft.build/settings/integrations?embed=raft-settings-v1&shell=host",
            settingsMarketplaceWebViewUrl("https://app.raft.build", null)
        )
    }

    @Test
    fun marketplaceBootstrapClearsRefreshOwnerAndProvidesAccessAndServer() {
        val session = marketplaceSession()
        val script = settingsMarketplaceWebViewInjectedJavaScript(session)

        assertContains(script, "slock_access_token")
        assertContains(script, "slock_refresh_token")
        assertContains(script, "removeItem(\"slock_refresh_token\")")
        assertFalse(script.contains(session.refreshToken))
        assertContains(script, "slock_last_server_slug")
        assertFalse(script.contains(".h-panel-header"))
        assertFalse(script.contains("MutationObserver"))
        assertFalse(script.contains("createElement(\"style\")"))
        assertFalse(script.contains("hidden-header"))
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
