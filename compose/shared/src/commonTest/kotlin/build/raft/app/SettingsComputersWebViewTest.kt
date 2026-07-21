package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.SlockShellStoreScope
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun computersAuthBootstrapClearsRefreshOwnerAndProvidesAccessAndServer() {
        val script = settingsComputersWebViewInjectedJavaScript(computersSession())

        assertContains(script, "slock_access_token")
        assertContains(script, "access-token")
        assertContains(script, "slock_refresh_token")
        assertContains(script, "removeItem(\"slock_refresh_token\")")
        assertFalse(script.contains("refresh-token"))
        assertContains(script, "slock_last_server_slug")
        assertContains(script, "workspace")
        assertFalse(script.contains("h-panel-header"))
        assertFalse(script.contains("MutationObserver"))
        assertFalse(script.contains("querySelector"))
    }

    @Test
    fun computersStandaloneLaunchCarriesOnlyShellIdentity() {
        val pageData =
            settingsComputersPageData(
                SlockShellStoreScope.create(
                    shellInstanceId = "shell-computers",
                    initialServerId = "server-1"
                )
            )

        assertEquals(SETTINGS_COMPUTERS_PAGE, pageData.optString("pageName"))
        assertEquals("shell-computers", pageData.optString(SLOCK_SHELL_INSTANCE_ID))
        assertEquals("server-1", pageData.optString(SLOCK_SHELL_SERVER_ID))
        assertFalse(pageData.has("payload"))
        assertFalse(pageData.has("accessToken"))
        assertFalse(pageData.has("serverSlug"))
        assertFalse(pageData.has("machines"))
    }

    @Test
    fun computersBootstrapWithoutSessionStillClearsPersistedRefreshOwner() {
        val script = settingsComputersWebViewInjectedJavaScript(null)

        assertContains(script, "removeItem(\"slock_refresh_token\")")
        assertContains(script, "removeItem(\"slock_access_token\")")
        assertContains(script, "removeItem(\"slock_host_auth_binding\")")
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
