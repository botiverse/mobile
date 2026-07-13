package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SettingsMarketplaceWebViewTest {
    @Test
    fun marketplaceUrlUsesWorkspaceIntegrationsRoute() {
        val session = marketplaceSession()

        assertEquals(
            "https://app.raft.build/s/workspace/settings/integrations",
            settingsMarketplaceWebViewUrl("https://app.raft.build/", session)
        )
    }

    @Test
    fun marketplaceUrlFallsBackToUnscopedIntegrationsRouteWithoutServerSlug() {
        assertEquals(
            "https://app.raft.build/settings/integrations",
            settingsMarketplaceWebViewUrl("https://app.raft.build", null)
        )
    }

    @Test
    fun marketplaceBootstrapProvidesSessionAndHidesDuplicateWebHeader() {
        val script = settingsMarketplaceWebViewInjectedJavaScript(marketplaceSession())

        assertContains(script, "slock_access_token")
        assertContains(script, "slock_refresh_token")
        assertContains(script, "slock_last_server_slug")
        assertContains(script, "data-slock-settings-tab")
        assertContains(script, "integrations")
        assertContains(script, "Connected Apps")
        assertContains(script, "连接的应用")
    }

    private fun marketplaceSession() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "workspace",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace"))
        )
}
