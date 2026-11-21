package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.RaftRealtimeSessionDemand
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.normalizedOrNull
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
    fun newWindowPageDataMatchesTheChooserLaunchExceptReplacement() {
        val fromShell =
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

    @Test
    fun missingRouteOwnerSwitchesInPlace() {
        assertNull(
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = sourceScope,
                currentServerId = "server-a",
                sourceDocumentShell = false,
                selectedServerId = "server-b",
                multiShellHostEnabled = true,
                routeOwner = null,
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
    fun launcherWindowWithoutLaunchServerPickingTheServerItShowsSwitchesInPlace() {
        val launcherScope = RaftShellStoreScope.create("android-default-app-shell", null)

        assertNull(decide(launcherScope, currentServerId = "server-a", picked = "server-a"))
        assertNotNull(decide(launcherScope, currentServerId = "server-a", picked = "server-b"))
    }

    @Test
    fun windowSwitchedInPlaceDecidesAgainstTheServerItShowsNotItsLaunchServer() {
        // Launched on A, switched in place to C while the setting was off.
        val launchedOnA = RaftShellStoreScope.create("android-default-app-shell", "server-a")

        assertNull(decide(launchedOnA, currentServerId = "server-c", picked = "server-c"))
        val toA = assertNotNull(decide(launchedOnA, currentServerId = "server-c", picked = "server-a"))
        assertEquals(true, toA.optBoolean("slockDocumentShell", false))
        assertEquals("server-a", toA.optString("slockRouteServerId"))
    }

    @Test
    fun serversOwnWindowShowingAnotherServerSwitchesBackInPlace() {
        // Server B's document window, switched in place to A by a tap on A's notification
        // (Hands 3a465552): picking B must not "open B's window", which is this very window.
        val serverBWindow = RaftShellStoreScope.create("slock://shell/env/user-1/server-b", "server-b")
        fun pick(picked: String) =
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = serverBWindow,
                currentServerId = "server-a",
                sourceDocumentShell = true,
                selectedServerId = picked,
                multiShellHostEnabled = true,
                routeOwner = owner(picked),
                sourceWindowServerId = "server-b",
            )

        assertNull(pick(" server-b "))
        // Any other server still opens (or brings back) its own window.
        assertEquals("server-c", assertNotNull(pick("server-c")).optString("slockRouteServerId"))
    }

    @Test
    fun pickingAServerAnotherWindowShowsNamesThatWindowAndKeepsItAsItIsUnlessATabIsKept() {
        // Device report (1.27.0): the launcher window shows A, a notification opened B in its own
        // document window; in B's window the switcher picks A.
        val serverBWindow = RaftShellStoreScope.create("slock://shell/env/user-1/server-b", "server-b")
        val windows = RaftRealtimeSessionDemand()
        windows.claim("android-default-app-shell", "server-a")
        windows.claim(serverBWindow.shellInstanceId, "server-b")
        fun pick(picked: String, keepTab: String? = null) =
            assertNotNull(
                appShellServerSwitchNewWindowPageDataOrNull(
                    sourceStoreScope = serverBWindow,
                    currentServerId = "server-b",
                    sourceDocumentShell = true,
                    selectedServerId = picked,
                    multiShellHostEnabled = true,
                    routeOwner = owner(picked),
                    keepTab = keepTab,
                    sourceWindowServerId = "server-b",
                    windowShowingPickedServer =
                        windows.shellShowing(picked, exceptShellInstanceId = serverBWindow.shellInstanceId),
                )
            )

        // The launcher window is A's window: no second one, and no destination, so as it is.
        for (tab in listOf(null, "home")) {
            val toA = pick("server-a", keepTab = tab)
            assertEquals("android-default-app-shell", toA.optString(RAFT_SERVER_SWITCH_WINDOW_ID), "keepTab=$tab")
            assertTrue(toA.optBoolean(RAFT_SERVER_SWITCH_KEEP_WINDOW, false), "keepTab=$tab")
            assertFalse(toA.has("slockInitialTab"), "keepTab=$tab")
        }
        // A kept tab is a destination: still A's one window, opened on that tab as before.
        val toAOnActivity = pick("server-a", keepTab = "activity")
        assertEquals("android-default-app-shell", toAOnActivity.optString(RAFT_SERVER_SWITCH_WINDOW_ID))
        assertFalse(toAOnActivity.has(RAFT_SERVER_SWITCH_KEEP_WINDOW))
        assertEquals("activity", toAOnActivity.optString("slockInitialTab"))
        // No window shows C: its own new window, as before.
        val toC = pick("server-c")
        assertFalse(toC.has(RAFT_SERVER_SWITCH_WINDOW_ID))
        assertFalse(toC.has(RAFT_SERVER_SWITCH_KEEP_WINDOW))
        assertEquals(true, toC.optBoolean("slockDocumentShell", false))
        assertEquals("server-c", toC.optString("slockRouteServerId"))
        // The launcher window switched in place to C: A has no window any more.
        windows.claim("android-default-app-shell", "server-c")
        assertFalse(pick("server-a").has(RAFT_SERVER_SWITCH_WINDOW_ID))
        assertEquals("android-default-app-shell", pick("server-c").optString(RAFT_SERVER_SWITCH_WINDOW_ID))
    }

    @Test
    fun unknownCurrentServerSwitchesInPlace() {
        assertNull(decide(sourceScope, currentServerId = null, picked = "server-b"))
        assertNull(decide(sourceScope, currentServerId = " ", picked = "server-b"))
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
    fun switchOnActivityOpensTheNewServerWindowOnActivity() {
        val pageData = assertNotNull(switchOnTab("activity"))

        assertEquals("activity", pageData.optString("slockInitialTab"))
        // The window the host opens reads it back as its launch tab, not the default Home.
        assertEquals("activity", pageData.appShellLaunchSelection().selectedTab)
    }

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
    fun switchOnHomeOrWithoutATabOpensHomeAndAddsNoTabKey() {
        for (tab in listOf("home", null, "not-a-tab")) {
            val pageData = assertNotNull(switchOnTab(tab))

            assertEquals(false, pageData.has("slockInitialTab"))
            assertEquals("home", pageData.appShellLaunchSelection().selectedTab)
        }
    }

    @Test
    fun keptTabDoesNotTurnAnInPlaceSwitchIntoANewWindow() {
        assertNull(switchOnTab("activity", multiShellHostEnabled = false))
    }
}
