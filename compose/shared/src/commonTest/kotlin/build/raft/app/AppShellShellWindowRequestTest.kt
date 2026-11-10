package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.normalizedOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #4484: with "Open workspaces in separate app windows" on, a server picked in a shell opens in its
 * own window. Android's page router starts a document task; iOS's only pushes into the current
 * window, so there the page data becomes a scene request whose refusal switches in place (never
 * leaving the user on the old server).
 */
class AppShellShellWindowRequestTest {
    private val sourceScope = RaftShellStoreScope.create("legacy-app-shell", "server-a")

    private fun owner(serverId: String) =
        AuthenticatedRouteOwner(
            environmentId = "https://api.test/",
            accountId = "user-1",
            serverId = serverId,
        ).normalizedOrNull()

    /** What a server switch on the Activity tab hands the window path (setting on, A -> B). */
    private fun newWindowPageData(keepTab: String? = "activity"): JSONObject =
        assertNotNull(
            appShellServerSwitchNewWindowPageDataOrNull(
                sourceStoreScope = sourceScope,
                currentServerId = "server-a",
                sourceDocumentShell = false,
                selectedServerId = "server-b",
                multiShellHostEnabled = true,
                routeOwner = owner("server-b"),
                keepTab = keepTab,
            )
        )

    /** The page data of the setting-off path: this window's shell, switched to B. */
    private fun inPlacePageData(): JSONObject =
        appShellPageDataForServerSelection(
            sourceStoreScope = sourceScope,
            selectedServerId = "server-b",
            multiShellHostEnabled = false,
            suppressRestoreLanding = true,
            routeOwner = owner("server-b"),
        )

    @Test
    fun onlyTheHostThatRequestsScenesTakesTheScenePath() {
        assertEquals(AppShellShellWindowPath.HostSceneRequest, appShellShellWindowPath(hostRequestsScene = true))
        assertEquals(AppShellShellWindowPath.PageRouter, appShellShellWindowPath(hostRequestsScene = false))
    }

    @Test
    fun theSceneRequestCarriesTheRouteOwnerAndTheKeptTab() {
        val request = assertNotNull(appShellShellWindowRequestOrNull(newWindowPageData(), source = "server_menu"))

        assertEquals(owner("server-b"), request.routeOwner)
        assertEquals("activity", request.initialTab)
        assertTrue(request.suppressRestoreLanding)
        assertEquals("server_menu", request.source)
    }

    @Test
    fun aSwitchOnHomeRequestsNoInitialTab() {
        val request = assertNotNull(appShellShellWindowRequestOrNull(newWindowPageData(keepTab = "home"), "server_drawer"))

        assertNull(request.initialTab)
    }

    @Test
    fun theBridgeParamsAreIdentityAndIntentOnly() {
        val params = assertNotNull(appShellShellWindowRequestOrNull(newWindowPageData(), "home_switcher")).toBridgeParams()

        assertEquals(
            setOf(
                RAFT_ROUTE_ENVIRONMENT_ID,
                RAFT_ROUTE_ACCOUNT_ID,
                RAFT_ROUTE_SERVER_ID,
                AppShellInitialTabKey,
                APP_SHELL_SUPPRESS_RESTORE_LANDING,
                "source",
            ),
            params.keySet().toSet()
        )
        assertEquals("https://api.test", params.optString(RAFT_ROUTE_ENVIRONMENT_ID))
        assertEquals("user-1", params.optString(RAFT_ROUTE_ACCOUNT_ID))
        assertEquals("server-b", params.optString(RAFT_ROUTE_SERVER_ID))
        assertEquals("activity", params.optString(AppShellInitialTabKey))
        assertTrue(params.optBoolean(APP_SHELL_SUPPRESS_RESTORE_LANDING, false))
    }

    @Test
    fun pageDataThatIsNoDocumentShellIsNoSceneRequest() {
        assertNull(appShellShellWindowRequestOrNull(inPlacePageData(), "server_menu"))
        // A document flag without a complete owner names no window either.
        val ownerless = newWindowPageData().apply { remove(RAFT_ROUTE_ACCOUNT_ID) }
        assertNull(appShellShellWindowRequestOrNull(ownerless, "server_menu"))
    }

    @Test
    fun onlyAnExplicitOpenedOrReusedKeepsTheSourceWindowOnItsServer() {
        val opened = appShellShellWindowOutcome(JSONObject().put("status", "opened"))
        val reused = appShellShellWindowOutcome(JSONObject().put("status", " Reused "))
        assertEquals(AppShellShellWindowOutcome.Opened, opened)
        assertEquals(AppShellShellWindowOutcome.Reused, reused)
        assertFalse(opened.switchesInPlace)
        assertFalse(reused.switchesInPlace)
    }

    @Test
    fun everyOtherAnswerSwitchesInPlace() {
        val answers =
            listOf(
                JSONObject().put("status", "refused").put("reason", "timeout"),
                JSONObject().put("status", "refused").put("reason", "request_error:UISceneError"),
                // The host without the bridge method (RaftCmpBridgeHost "Method not implemented").
                JSONObject().put("code", -1).put("message", "Method not implemented"),
                JSONObject().put("status", "pending"),
                JSONObject(),
                null,
            )
        for (answer in answers) {
            val outcome = appShellShellWindowOutcome(answer)
            assertEquals(AppShellShellWindowOutcome.Refused, outcome, "answer=$answer")
            assertTrue(outcome.switchesInPlace, "answer=$answer")
        }
    }

    // --- Pager.openAppShellInOwnWindow ---

    private class BridgeCall(val method: String, val params: String)

    private val bridgeCalls = mutableListOf<BridgeCall>()
    private val opened = mutableListOf<Pair<String, JsonObject>>()
    private val transport = HostBridgeTransport { method, params, _ -> bridgeCalls += BridgeCall(method, params) }
    private val navigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) {
                opened += pageName to pageData
            }

            override fun closePage() = Unit
        }

    private fun shellPager(withBridgeTransport: Boolean = true): Pair<Pager, BridgeModule> {
        val pager =
            object : Pager() {
                override fun createExternalModules(): Map<String, Module> =
                    mapOf(BridgeModule.MODULE_NAME to BridgeModule())
            }
        pager.pagerId = "shell-window-request-test"
        pager.pageData = PageData(JSONObject().withRaftShellStoreScope(sourceScope))
        pager.host =
            RaftCmpHost(
                BridgeWireHostCapabilities(transport),
                navigator,
                bridgeTransport = transport.takeIf { withBridgeTransport }
            )
        return pager to pager.acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
    }

    private var shownInWindow = 0
    private var switchedInPlace = 0

    private fun open(pager: Pager, bridge: BridgeModule, pageData: JSONObject, path: AppShellShellWindowPath) =
        pager.openAppShellInOwnWindow(
            bridge = bridge,
            pageData = pageData,
            source = "server_menu",
            path = path,
            onShownInWindow = { shownInWindow += 1 },
            onSwitchInPlace = { switchedInPlace += 1 },
        )

    @Test
    fun androidOpensTheShellPageThroughItsRouter() {
        val (pager, bridge) = shellPager()

        open(pager, bridge, newWindowPageData(), AppShellShellWindowPath.PageRouter)

        assertEquals(listOf(RAFT_APP_SHELL_PAGE), opened.map { it.first })
        assertEquals(emptyList(), bridgeCalls.map { it.method }.filter { it == BridgeModule.REQUEST_SHELL_WINDOW })
        assertEquals(1, shownInWindow)
        assertEquals(0, switchedInPlace)
    }

    @Test
    fun iosAsksTheHostForASceneInsteadOfPushingIntoThisWindow() {
        val (pager, bridge) = shellPager()

        open(pager, bridge, newWindowPageData(), AppShellShellWindowPath.HostSceneRequest)

        // Nothing opens in this window; the host answers later (opened / reused / refused).
        assertEquals(emptyList(), opened)
        val call = bridgeCalls.single { it.method == BridgeModule.REQUEST_SHELL_WINDOW }
        val params = JSONObject(call.params)
        assertEquals("server-b", params.optString(RAFT_ROUTE_SERVER_ID))
        assertEquals("activity", params.optString(AppShellInitialTabKey))
        assertEquals("server_menu", params.optString("source"))
        assertEquals(0, shownInWindow)
        assertEquals(0, switchedInPlace)
    }

    @Test
    fun iosSwitchesInPlaceAtOnceWhenThePageDataNamesNoWindow() {
        val (pager, bridge) = shellPager()

        open(pager, bridge, inPlacePageData(), AppShellShellWindowPath.HostSceneRequest)

        assertEquals(emptyList(), bridgeCalls.map { it.method }.filter { it == BridgeModule.REQUEST_SHELL_WINDOW })
        assertEquals(emptyList(), opened)
        assertEquals(1, switchedInPlace)
        assertEquals(0, shownInWindow)
    }

    @Test
    fun iosSwitchesInPlaceAtOnceWhenTheHostCannotBeAsked() {
        val (pager, bridge) = shellPager(withBridgeTransport = false)

        open(pager, bridge, newWindowPageData(), AppShellShellWindowPath.HostSceneRequest)

        assertEquals(emptyList(), opened)
        assertEquals(1, switchedInPlace)
        assertEquals(0, shownInWindow)
    }
}
