package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.normalizedOrNull
import build.raft.app.settings.AUTH_REPLACE_SHELL_ROOT
import build.raft.app.settings.RAFT_APP_SHELL_PAGE
import build.raft.app.settings.appShellLaunchSelection
import build.raft.app.settings.appShellPageDataForServerSelection
import build.raft.app.settings.appShellServerSwitchNewWindowPageDataOrNull
import build.raft.app.shell.RAFT_ROUTE_SERVER_ID
import build.raft.app.shell.RAFT_SERVER_SWITCH_KEEP_WINDOW
import build.raft.app.shell.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
import build.raft.app.shell.RAFT_SERVER_SWITCH_WINDOW_ID
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import build.raft.app.shell.appShellServerMenuSwitchPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                    currentServerId = "server-a",
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
    fun settingOffSwitchesInPlace() {
        assertNull(
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = sourceScope,
                currentServerId = "server-a",
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
                currentServerId = "server-a",
                sourceDocumentShell = false,
                selectedServerId = " server-a ",
                multiShellHostEnabled = true,
                routeOwner = owner("server-a"),
            )
        )
    }

    private fun decide(scope: RaftShellStoreScope, currentServerId: String?, picked: String) =
        appShellServerSwitchNewWindowPageDataOrNull(
            sourceStoreScope = scope,
            currentServerId = currentServerId,
            sourceDocumentShell = false,
            selectedServerId = picked,
            multiShellHostEnabled = true,
            routeOwner = owner(picked),
        )

    @Test
    fun windowSwitchedInPlaceDecidesAgainstTheServerItShowsNotItsLaunchServer() {
        // Launched on A, switched in place to C while the setting was off.
        val launchedOnA = RaftShellStoreScope.create("android-default-app-shell", "server-a")

        assertNull(decide(launchedOnA, currentServerId = "server-c", picked = "server-c"))
        val toA = assertNotNull(decide(launchedOnA, currentServerId = "server-c", picked = "server-a"))
        assertEquals(true, toA.optBoolean("slockDocumentShell", false))
        assertEquals("server-a", toA.optString("slockRouteServerId"))
    }

    private fun switchOnTab(tab: String?, multiShellHostEnabled: Boolean = true) =
        appShellServerSwitchNewWindowPageDataOrNull(
            sourceStoreScope = sourceScope,
            currentServerId = "server-a",
            sourceDocumentShell = false,
            selectedServerId = "server-b",
            multiShellHostEnabled = multiShellHostEnabled,
            routeOwner = owner("server-b"),
            keepTab = tab,
        )

    @Test
    fun switchKeepsTheSameTabWithAndWithoutSeparateWindows() {
        for (tab in listOf("activity", "tasks", "dms", "more")) {
            val inPlace = assertNotNull(appShellServerMenuSwitchPlan("server-a", "server-b", tab))
            val newWindow = assertNotNull(switchOnTab(tab))

            assertEquals(tab, inPlace.tab)
            assertEquals(inPlace.tab, newWindow.appShellLaunchSelection().selectedTab)
        }
    }

    @Test
    fun postLoginChooserStaysInDefaultLauncherShellEvenWithSeparateWindowsEnabled() {
        val pageData =
            appShellPageDataForServerSelection(
                sourceStoreScope = RaftShellStoreScope.Legacy,
                sourceDocumentShell = false,
                selectedServerId = "server-a",
                multiShellHostEnabled = true,
                suppressRestoreLanding = true,
                routeOwner = owner("server-a"),
                fromShell = false,
            )

        assertEquals("server-a", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertFalse(pageData.optBoolean("slockDocumentShell", false))
        assertEquals(RaftShellStoreScope.Legacy.shellInstanceId, pageData.optString("slockShellInstanceId"))
        assertFalse(pageData.has(RAFT_SERVER_SWITCH_WINDOW_ID))
    }

    private fun testSession(selectedServerId: String?): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerId,
            servers =
                listOf(
                    StoredAuthServer(id = "server-a", name = "A", slug = "server-a"),
                    StoredAuthServer(id = "server-b", name = "B", slug = "server-b"),
                    StoredAuthServer(id = "server-c", name = "C", slug = "server-c"),
                ),
        )

    @Test
    fun openAppShellForSelectedServerReusesExistingWindowWithoutDisconnectingRealtime() {
        val serverBScope = RaftShellStoreScope.create("slock://shell/env/user-1/server-b", "server-b")
        var disconnected = 0
        val noted = mutableListOf<String>()
        var openedPageData: build.raft.app.compat.json.JSONObject? = null
        dispatchOpenAppShellForSelectedServer(
            readySession = testSession("server-a"),
            sourceStoreScope = serverBScope,
            sourceDocumentShell = true,
            environmentId = "https://api.test/",
            multiShellHostEnabled = true,
            fromShell = true,
            noteServerSelected = { noted += it },
            shellShowingServer = { serverId, except ->
                assertEquals("server-a", serverId)
                assertEquals(serverBScope.shellInstanceId, except)
                "android-default-app-shell"
            },
            nowEpochMillis = { 4242L },
            disconnectRealtime = { disconnected += 1 },
            openReplacingCurrentPage = { openedPageData = it },
        )

        assertEquals(0, disconnected)
        assertEquals(listOf("server-a"), noted)
        val data = assertNotNull(openedPageData)
        assertEquals("android-default-app-shell", data.optString(RAFT_SERVER_SWITCH_WINDOW_ID))
        assertTrue(data.optBoolean(RAFT_SERVER_SWITCH_KEEP_WINDOW, false))
        assertEquals(4242L, data.optLong(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, 0L))
        assertEquals("server-a", data.optString(RAFT_ROUTE_SERVER_ID))
        assertFalse(data.optBoolean(AUTH_REPLACE_SHELL_ROOT, false))
    }

    @Test
    fun sameShellSelectionFromChooserMarksReplaceShellRootOnlyForInPlaceShell() {
        val inPlaceFromShell =
            appShellPageDataForServerSelection(
                sourceStoreScope = sourceScope,
                sourceDocumentShell = false,
                selectedServerId = "server-b",
                multiShellHostEnabled = false,
                suppressRestoreLanding = true,
                routeOwner = owner("server-b"),
                fromShell = true,
            )
        assertTrue(inPlaceFromShell.optBoolean(AUTH_REPLACE_SHELL_ROOT, false))
        assertEquals("server-b", inPlaceFromShell.optString(RAFT_SHELL_SERVER_ID))

        val postLogin =
            appShellPageDataForServerSelection(
                sourceStoreScope = RaftShellStoreScope.Legacy,
                sourceDocumentShell = false,
                selectedServerId = "server-b",
                multiShellHostEnabled = false,
                suppressRestoreLanding = true,
                routeOwner = owner("server-b"),
                fromShell = false,
            )
        assertFalse(postLogin.optBoolean(AUTH_REPLACE_SHELL_ROOT, false))

        val separateWindow =
            appShellPageDataForServerSelection(
                sourceStoreScope = sourceScope,
                sourceDocumentShell = false,
                selectedServerId = "server-b",
                multiShellHostEnabled = true,
                suppressRestoreLanding = true,
                routeOwner = owner("server-b"),
                fromShell = true,
            )
        assertFalse(separateWindow.optBoolean(AUTH_REPLACE_SHELL_ROOT, false))
    }
}
