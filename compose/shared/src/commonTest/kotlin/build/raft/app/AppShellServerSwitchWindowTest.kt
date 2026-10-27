package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.normalizedOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A server picked inside a shell (server menu, wide top bar, Home switcher, drawer header) honours
 * "open workspaces in separate app windows" the way the Choose workspace page does.
 */
class AppShellServerSwitchWindowTest {
    private val sourceScope = RaftShellStoreScope.create("android-default-app-shell", "server-a")

    private fun owner(serverId: String) =
        AuthenticatedRouteOwner(
            environmentId = "https://api.test/",
            accountId = "user-1",
            serverId = serverId,
        ).normalizedOrNull()

    @Test
    fun settingOnAndOtherServerOpensItsOwnWindowWithoutReplacingTheSourceShell() {
        val pageData =
            assertNotNull(
                appShellServerSwitchNewWindowPageDataOrNull(
                    sourceStoreScope = sourceScope,
                    sourceDocumentShell = false,
                    selectedServerId = "server-b",
                    multiShellHostEnabled = true,
                    routeOwner = owner("server-b"),
                )
            )

        assertEquals(RAFT_APP_SHELL_PAGE, pageData.optString("pageName"))
        assertEquals("server-b", pageData.optString(RAFT_SHELL_SERVER_ID))
        // Same document-shell identity the chooser launch produces, so the host reuses an
        // already-open window for this owner.
        assertEquals(true, pageData.optBoolean("slockDocumentShell", false))
        assertEquals("", pageData.optString("slockShellInstanceId"))
        assertEquals("https://api.test", pageData.optString("slockRouteEnvironmentId"))
        assertEquals("user-1", pageData.optString("slockRouteAccountId"))
        assertEquals("server-b", pageData.optString("slockRouteServerId"))
        // The source window stays open.
        assertEquals(false, pageData.optBoolean("slockReplaceCurrentPage", false))
    }

    @Test
    fun newWindowPageDataMatchesTheChooserLaunchExceptReplacement() {
        val fromShell =
            assertNotNull(
                appShellServerSwitchNewWindowPageDataOrNull(
                    sourceStoreScope = sourceScope,
                    sourceDocumentShell = false,
                    selectedServerId = "server-b",
                    multiShellHostEnabled = true,
                    routeOwner = owner("server-b"),
                )
            )
        val fromChooser =
            appShellPageDataForServerSelection(
                sourceStoreScope = sourceScope,
                selectedServerId = "server-b",
                multiShellHostEnabled = true,
                suppressRestoreLanding = true,
                routeOwner = owner("server-b"),
            )
        fromChooser.remove("slockReplaceCurrentPage")

        assertEquals(fromChooser.toString(), fromShell.toString())
    }

    @Test
    fun settingOffSwitchesInPlace() {
        assertNull(
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = sourceScope,
                sourceDocumentShell = false,
                selectedServerId = "server-b",
                multiShellHostEnabled = false,
                routeOwner = owner("server-b"),
            )
        )
    }

    @Test
    fun sameServerSwitchesInPlaceEvenWithSettingOn() {
        assertNull(
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = sourceScope,
                sourceDocumentShell = false,
                selectedServerId = " server-a ",
                multiShellHostEnabled = true,
                routeOwner = owner("server-a"),
            )
        )
    }

    @Test
    fun missingRouteOwnerSwitchesInPlace() {
        assertNull(
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = sourceScope,
                sourceDocumentShell = false,
                selectedServerId = "server-b",
                multiShellHostEnabled = true,
                routeOwner = null,
            )
        )
    }
}
