package build.raft.app

import build.raft.app.data.RaftShellStoreScope
import build.raft.app.shell.RAFT_SHELL_INSTANCE_ID
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SettingsComputersRouteTest {
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
        assertEquals(SETTINGS_COMPUTERS_PAGE, pageData.optString("pageName"))
        assertEquals("server-1", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("machine-42", pageData.optString(SETTINGS_COMPUTER_ID))
        assertEquals(
            setOf(
                "pageName",
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
}
