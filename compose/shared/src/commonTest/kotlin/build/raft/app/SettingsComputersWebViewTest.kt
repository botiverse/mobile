package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsComputersWebViewTest {
    @Test
    fun authenticatedComputersUrlUsesSelectedServerAndHostShellContract() {
        assertEquals(
            "https://app.raft.build/s/workspace/computers?embed=raft-settings-v1&shell=host",
            settingsComputersWebViewUrl("https://app.raft.build/", computersSession())
        )
    }

    @Test
    fun computersUrlFallsBackToUnscopedWebRouteWithoutSession() {
        assertEquals(
            "https://app.raft.build/computers?embed=raft-settings-v1&shell=host",
            settingsComputersWebViewUrl("https://app.raft.build", null)
        )
    }

    @Test
    fun computersAuthBootstrapContainsTokensAndSelectedServerWithoutDomRewriting() {
        val script = settingsComputersWebViewInjectedJavaScript(computersSession())

        assertContains(script, "slock_access_token")
        assertContains(script, "access-token")
        assertContains(script, "slock_refresh_token")
        assertContains(script, "refresh-token")
        assertContains(script, "slock_last_server_slug")
        assertContains(script, "workspace")
        assertFalse(script.contains("h-panel-header"))
        assertFalse(script.contains("MutationObserver"))
        assertFalse(script.contains("querySelector"))
    }

    @Test
    fun computersBackConsumesWebHistoryBeforeLeavingSettings() {
        assertTrue(settingsComputersShouldConsumeBack("computers", canGoBack = true))
        assertFalse(settingsComputersShouldConsumeBack("computers", canGoBack = false))
        assertFalse(settingsComputersShouldConsumeBack("mobile", canGoBack = true))
    }
}

private fun computersSession() =
    StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
        accessToken = "access-token",
        refreshToken = "refresh-token",
        selectedServerId = "server-1",
        selectedServerSlug = "workspace",
        servers = listOf(StoredAuthServer(id = "server-1", slug = "workspace", name = "Workspace"))
    )
