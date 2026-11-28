package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftNavDestination
import build.raft.app.data.RaftPageRoute
import build.raft.app.data.RaftProductPageView
import build.raft.app.data.RaftSettingsTab
import build.raft.app.settings.AppShellRailSavedKey
import build.raft.app.settings.AppShellRailSearchKey
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.settings.SettingsWebViewSurface
import build.raft.app.shell.AppShellSearchTabKey
import build.raft.app.shell.appShellNavDestination
import build.raft.app.shell.appShellRailInlineProductPageView
import build.raft.app.shell.appShellTabProductPageView
import build.raft.app.shell.authenticatedProductPageView
import build.raft.app.shell.productPageView
import build.raft.app.shell.productSettingsTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The app's page identities -> registry `page_viewed` routes / `nav_entry_selected` destinations. */
class RaftProductPageRoutesTest {
    private fun view(route: RaftPageRoute, tab: RaftSettingsTab? = null) = RaftProductPageView(route, tab)

    private fun threadParams(surfaceKind: String?, instanceId: String = "shared-shell-thread-t1"): JSONObject =
        JSONObject().apply {
            val payload = JSONObject().apply {
                put("instanceId", instanceId)
                surfaceKind?.let { put("surfaceKind", it) }
                put("threadId", "t1")
            }
            put("payload", payload.toString())
            put("instanceId", instanceId)
        }

    @Test
    fun shellTabsAndRailPanesMapToTheirRoutes() {
        val tabs =
            mapOf(
                "home" to RaftPageRoute.Home,
                "activity" to RaftPageRoute.Activity,
                "tasks" to RaftPageRoute.Tasks,
                AppShellSearchTabKey to RaftPageRoute.Search,
                "more" to RaftPageRoute.More,
                "members" to RaftPageRoute.Members,
                "settings" to RaftPageRoute.Settings,
            )
        tabs.forEach { (tab, route) -> assertEquals(view(route), appShellTabProductPageView(tab), tab) }
        listOf("dms", "computers", "apps", "artifacts").forEach { tab ->
            assertNull(appShellTabProductPageView(tab), tab)
        }
        assertEquals(RaftNavDestination.Artifacts, appShellNavDestination("artifacts"))
        assertEquals(RaftNavDestination.Apps, appShellNavDestination("apps"))
        assertEquals(view(RaftPageRoute.Search), appShellRailInlineProductPageView(AppShellRailSearchKey))
        assertEquals(view(RaftPageRoute.Saved), appShellRailInlineProductPageView(AppShellRailSavedKey))
        assertNull(appShellRailInlineProductPageView(null))
        assertEquals(RaftSettingsTab.Theme, SettingsNativeDetailPage.Theme.productSettingsTab())
        assertEquals(RaftSettingsTab.ServerNotifications, SettingsNativeDetailPage.ServerNotifications.productSettingsTab())
        assertEquals(
            view(RaftPageRoute.Settings, RaftSettingsTab.AuthorizedAccounts),
            SettingsWebViewSurface.AppAuthorizations.productPageView(),
        )
        assertEquals(
            view(RaftPageRoute.Apps),
            SettingsWebViewSurface.Marketplace.productPageView(),
        )
        assertEquals(
            view(RaftPageRoute.Artifacts),
            authenticatedProductPageView(AuthenticatedProductRoute.ArtifactsHome, JSONObject()) { false },
        )
    }

    @Test
    fun threadPagesMapBySurfaceAndTaskOpens() {
        val never: (String) -> Boolean = { false }
        val cases =
            listOf(
                RaftThreadSurfaceKind.Channel to RaftPageRoute.Channel,
                null to RaftPageRoute.Channel,
                RaftThreadSurfaceKind.DirectMessage to RaftPageRoute.Dm,
                RaftThreadSurfaceKind.ReplyThread to RaftPageRoute.Thread,
            )
        cases.forEach { (surface, route) ->
            assertEquals(view(route), authenticatedProductPageView(AuthenticatedProductRoute.Thread, threadParams(surface), never), "$surface")
        }
        val marked = mutableSetOf("shared-shell-thread-t1")
        assertEquals(
            view(RaftPageRoute.Task),
            authenticatedProductPageView(AuthenticatedProductRoute.Thread, threadParams(RaftThreadSurfaceKind.ReplyThread)) { marked.remove(it) },
        )
        assertNull(authenticatedProductPageView(AuthenticatedProductRoute.Thread, JSONObject(), never))
    }
}
