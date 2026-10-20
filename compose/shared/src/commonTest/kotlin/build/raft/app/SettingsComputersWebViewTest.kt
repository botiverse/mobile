package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.RaftShellStoreScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun computerDetailUrlResolvesTheTargetServerInsteadOfTheSelectedServer() {
        val session =
            computersSession().copy(
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", slug = "workspace", name = "Workspace"),
                        StoredAuthServer(id = "server-2", slug = "other-space", name = "Other"),
                    ),
            )
        val target = SettingsComputerRouteIdentity(serverId = "server-2", machineId = "machine-42")

        assertEquals(
            "https://app.raft.build/s/other-space/computer/machine-42?embed=raft-settings-v1&shell=host",
            settingsComputerWebViewUrl("https://app.raft.build", session, target),
        )
        assertEquals(
            "https://app.raft.build/s/other-space/computer/machine-42?embed=raft-settings-v1&shell=host",
            settingsComputerWebViewSpec(target).buildWebUrl("https://app.raft.build", session, ""),
        )
    }

    @Test
    fun computerDetailUrlFailsBackToTheListForUnknownOrUnsafeIdentity() {
        val expected = "https://app.raft.build/s/workspace/computers?embed=raft-settings-v1&shell=host"

        assertEquals(
            expected,
            settingsComputerWebViewUrl(
                "https://app.raft.build",
                computersSession(),
                SettingsComputerRouteIdentity(serverId = "server-missing", machineId = "machine-42"),
            ),
        )
        assertEquals(
            expected,
            settingsComputerWebViewUrl(
                "https://app.raft.build",
                computersSession(),
                SettingsComputerRouteIdentity(serverId = "server-1", machineId = "machine/escape"),
            ),
        )
        assertEquals(
            expected,
            settingsComputerWebViewUrl(
                "https://app.raft.build",
                computersSession(),
                SettingsComputerRouteIdentity(serverId = "server-1", machineId = ".."),
            ),
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
                RaftShellStoreScope.create(
                    shellInstanceId = "shell-computers",
                    initialServerId = "server-1"
                )
            )

        assertEquals(SETTINGS_WEB_VIEW_PAGE, pageData.optString("pageName"))
        assertEquals("computers", pageData.optString(SETTINGS_WEB_VIEW_SURFACE))
        assertEquals("shell-computers", pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertEquals("server-1", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertFalse(pageData.has("payload"))
        assertFalse(pageData.has("accessToken"))
        assertFalse(pageData.has("serverSlug"))
        assertFalse(pageData.has("machines"))
    }

    @Test
    fun computerDetailLaunchCarriesOnlyServerAndMachineIdentity() {
        val target = settingsComputerRouteIdentity(" server-1 ", " machine-42 ")!!
        val pageData =
            settingsComputerPageData(
                storeScope =
                    RaftShellStoreScope.create(
                        shellInstanceId = "shell-computer-detail",
                        initialServerId = "stale-server",
                    ),
                target = target,
            )

        assertEquals(SettingsComputerRouteIdentity("server-1", "machine-42"), target)
        assertEquals(target, settingsComputerRouteIdentityFromPageData(pageData))
        assertEquals(SETTINGS_WEB_VIEW_PAGE, pageData.optString("pageName"))
        assertEquals("computers", pageData.optString(SETTINGS_WEB_VIEW_SURFACE))
        assertEquals("server-1", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("machine-42", pageData.optString(SETTINGS_COMPUTER_ID))
        assertEquals(
            setOf(
                "pageName",
                SETTINGS_WEB_VIEW_SURFACE,
                RAFT_SHELL_INSTANCE_ID,
                RAFT_SHELL_SERVER_ID,
                SETTINGS_COMPUTER_ID,
            ),
            Json.parseToJsonElement(pageData.toString()).jsonObject.keys,
        )
        assertFalse(pageData.has("machineName"))
        assertFalse(pageData.has("status"))
        assertFalse(pageData.has("computer"))
        assertFalse(pageData.has("payload"))
    }

    @Test
    fun partialComputerDetailIdentityIsRejected() {
        assertNull(settingsComputerRouteIdentity("server-1", " "))
        assertNull(settingsComputerRouteIdentity("", "machine-42"))
        assertNull(
            settingsComputerRouteIdentityFromPageData(
                build.raft.app.compat.json.JSONObject()
                    .put(RAFT_SHELL_SERVER_ID, "server-1"),
            ),
        )
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
