package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.RaftRealtimeSessionDemand
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
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

    @Test
    fun chooserFromShellReusesExistingLauncherOrDocumentWindowWithKeepWindow() {
        val serverBScope = RaftShellStoreScope.create("slock://shell/env/user-1/server-b", "server-b")
        val toLauncher =
            appShellPageDataForServerSelection(
                sourceStoreScope = serverBScope,
                sourceDocumentShell = true,
                selectedServerId = "server-a",
                multiShellHostEnabled = true,
                suppressRestoreLanding = true,
                routeOwner = owner("server-a"),
                fromShell = true,
                windowShowingPickedServer = "android-default-app-shell",
            )

        assertTrue(toLauncher.optBoolean("slockReplaceCurrentPage", false))
        assertEquals("android-default-app-shell", toLauncher.optString(RAFT_SERVER_SWITCH_WINDOW_ID))
        assertTrue(toLauncher.optBoolean(RAFT_SERVER_SWITCH_KEEP_WINDOW, false))
        assertEquals("server-a", toLauncher.optString(RAFT_ROUTE_SERVER_ID))

        // A window showing the source itself must not set RAFT_SERVER_SWITCH_WINDOW_ID.
        val selfIgnored =
            appShellPageDataForServerSelection(
                sourceStoreScope = serverBScope,
                sourceDocumentShell = true,
                selectedServerId = "server-c",
                multiShellHostEnabled = true,
                suppressRestoreLanding = true,
                routeOwner = owner("server-c"),
                fromShell = true,
                windowShowingPickedServer = serverBScope.shellInstanceId,
            )
        assertFalse(selfIgnored.has(RAFT_SERVER_SWITCH_WINDOW_ID))
        assertFalse(selfIgnored.has(RAFT_SERVER_SWITCH_KEEP_WINDOW))
    }

    @Test
    fun documentShellStoreScopeFallsBackToRouteServerIdWhenShellServerIdIsBlank() {
        val raw =
            build.raft.app.compat.json.JSONObject()
                .put(RAFT_SHELL_INSTANCE_ID, "slock://shell/env/user-1/server-b")
                .put(RAFT_DOCUMENT_SHELL, true)
                .put(RAFT_SHELL_SERVER_ID, "")
                .put(RAFT_ROUTE_SERVER_ID, "server-b")

        val scope = raw.raftShellStoreScopeOrNull()
        assertNotNull(scope)
        assertEquals("server-b", scope.initialServerId)
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
    fun openAppShellForSelectedServerClosesPageWhenPickingSameServerInShell() {
        var closed = 0
        var disconnected = 0
        var opened = 0
        dispatchOpenAppShellForSelectedServer(
            readySession = testSession("server-a"),
            sourceStoreScope = sourceScope,
            sourceDocumentShell = false,
            environmentId = "https://api.test/",
            multiShellHostEnabled = true,
            fromShell = true,
            onSameServerInShell = { closed += 1 },
            disconnectRealtime = { disconnected += 1 },
            openReplacingCurrentPage = { opened += 1 },
        )

        assertEquals(1, closed)
        assertEquals(0, disconnected)
        assertEquals(0, opened)
    }

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
    }

    @Test
    fun openAppShellForSelectedServerOpensNewDocumentShellOrDisconnectsWhenMultiWindowOff() {
        // Multi-window ON, no existing window -> document shell, no disconnect.
        var multiDisconnected = 0
        var multiOpened: build.raft.app.compat.json.JSONObject? = null
        dispatchOpenAppShellForSelectedServer(
            readySession = testSession("server-c"),
            sourceStoreScope = sourceScope,
            sourceDocumentShell = false,
            environmentId = "https://api.test/",
            multiShellHostEnabled = true,
            fromShell = true,
            shellShowingServer = { _, _ -> null },
            nowEpochMillis = { 7777L },
            disconnectRealtime = { multiDisconnected += 1 },
            openReplacingCurrentPage = { multiOpened = it },
        )
        assertEquals(0, multiDisconnected)
        val docData = assertNotNull(multiOpened)
        assertTrue(docData.optBoolean(RAFT_DOCUMENT_SHELL, false))
        assertEquals("server-c", docData.optString(RAFT_ROUTE_SERVER_ID))
        assertEquals(7777L, docData.optLong(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, 0L))

        // Multi-window OFF -> disconnects realtime and switches in place.
        val singleReasons = mutableListOf<String>()
        var singleOpened: build.raft.app.compat.json.JSONObject? = null
        dispatchOpenAppShellForSelectedServer(
            readySession = testSession("server-c"),
            sourceStoreScope = sourceScope,
            sourceDocumentShell = false,
            environmentId = "https://api.test/",
            multiShellHostEnabled = false,
            fromShell = true,
            disconnectRealtime = { singleReasons += it },
            openReplacingCurrentPage = { singleOpened = it },
        )
        assertEquals(listOf("server_changed"), singleReasons)
        val singleData = assertNotNull(singleOpened)
        assertFalse(singleData.optBoolean(RAFT_DOCUMENT_SHELL, false))
        assertEquals("server-c", singleData.optString(RAFT_SHELL_SERVER_ID))
    }

    @Test
    fun serverJoinPostAcceptPlanCoversSameServerIsolatedOpenFallbackAndPostLogin() {
        val loaded = testSession("server-a")

        // Multi-window ON + joined the workspace this window already shows -> close page.
        assertEquals(
            ServerJoinPostAcceptPlan.CloseSameWindow,
            resolveServerJoinPostAcceptPlan(
                joinedServerId = " server-a ",
                currentSourceServerId = "server-a",
                multiShellHostEnabled = true,
                loadedSession = loaded,
            ),
        )

        // Multi-window ON + joined another workspace + session available -> isolated session without mutating stored selection.
        val openIsolated =
            resolveServerJoinPostAcceptPlan(
                joinedServerId = "server-b",
                currentSourceServerId = "server-a",
                multiShellHostEnabled = true,
                loadedSession = loaded,
            )
        assertTrue(openIsolated is ServerJoinPostAcceptPlan.OpenWithSession)
        assertTrue(openIsolated.fromShell)
        assertEquals("server-b", openIsolated.readySession.selectedServerId)
        assertEquals("server-b", openIsolated.readySession.selectedServerSlug)

        // Multi-window ON + joined server not yet in loaded session -> falls back to selectServer().
        assertEquals(
            ServerJoinPostAcceptPlan.SelectServerThenOpen(fromShell = true),
            resolveServerJoinPostAcceptPlan(
                joinedServerId = "server-new",
                currentSourceServerId = "server-a",
                multiShellHostEnabled = true,
                loadedSession = loaded,
            ),
        )

        // Multi-window OFF from shell -> selects server in place.
        assertEquals(
            ServerJoinPostAcceptPlan.SelectServerThenOpen(fromShell = true),
            resolveServerJoinPostAcceptPlan(
                joinedServerId = "server-b",
                currentSourceServerId = "server-a",
                multiShellHostEnabled = false,
                loadedSession = loaded,
            ),
        )

        // Pre-shell / no source server -> selects server with fromShell = false.
        assertEquals(
            ServerJoinPostAcceptPlan.SelectServerThenOpen(fromShell = false),
            resolveServerJoinPostAcceptPlan(
                joinedServerId = "server-b",
                currentSourceServerId = null,
                multiShellHostEnabled = true,
                loadedSession = loaded,
            ),
        )
    }
}
