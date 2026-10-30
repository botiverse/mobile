package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftNavDestination
import build.raft.app.data.RaftPageRoute
import build.raft.app.data.RaftProductPageView
import build.raft.app.data.RaftSettingsTab
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
        assertNull(appShellTabProductPageView("dms"))
        assertEquals(view(RaftPageRoute.Search), appShellRailInlineProductPageView(AppShellRailSearchKey))
        assertEquals(view(RaftPageRoute.Saved), appShellRailInlineProductPageView(AppShellRailSavedKey))
        assertNull(appShellRailInlineProductPageView(null))
    }

    @Test
    fun navKeysMapToDestinations() {
        val keys =
            mapOf(
                "home" to RaftNavDestination.Home,
                "activity" to RaftNavDestination.Activity,
                "tasks" to RaftNavDestination.Tasks,
                AppShellSearchTabKey to RaftNavDestination.Search,
                AppShellRailSearchKey to RaftNavDestination.Search,
                "more" to RaftNavDestination.More,
                "members" to RaftNavDestination.Members,
                "settings" to RaftNavDestination.Settings,
            )
        keys.forEach { (key, destination) -> assertEquals(destination, appShellNavDestination(key), key) }
        assertNull(appShellNavDestination("dms"))
        // Every phone bar slot and rail entry has a destination.
        (AppShellPhoneBottomBarKeys + AppShellRailTabKeys).forEach { key ->
            assertEquals(true, appShellNavDestination(key) != null, key)
        }
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

    @Test
    fun pushedPagesMapToRoutesAndSettingsTabs() {
        val never: (String) -> Boolean = { false }
        fun page(route: AuthenticatedProductRoute, params: JSONObject = JSONObject()) =
            authenticatedProductPageView(route, params, never)

        assertEquals(view(RaftPageRoute.Search), page(AuthenticatedProductRoute.HomeSearch))
        assertEquals(
            view(RaftPageRoute.ConversationSearch),
            page(AuthenticatedProductRoute.HomeSearch, JSONObject().apply { put(HOME_SEARCH_SELECTED_CHANNEL_ID, "c1") }),
        )
        assertEquals(view(RaftPageRoute.Saved), page(AuthenticatedProductRoute.HomeSaved))
        assertEquals(view(RaftPageRoute.Activity), page(AuthenticatedProductRoute.HomeActivity))
        assertEquals(view(RaftPageRoute.Members), page(AuthenticatedProductRoute.Members))
        assertEquals(view(RaftPageRoute.Agent), page(AuthenticatedProductRoute.MemberDetail, JSONObject().apply { put("memberKind", "agent") }))
        assertEquals(
            view(RaftPageRoute.Human),
            page(
                AuthenticatedProductRoute.MemberDetail,
                JSONObject().apply { put("payload", JSONObject().apply { put("memberKind", "human") }.toString()) },
            ),
        )
        assertEquals(view(RaftPageRoute.ReleaseNotes), page(AuthenticatedProductRoute.ReleaseNotes))
        assertEquals(view(RaftPageRoute.Computers), page(AuthenticatedProductRoute.SettingsComputers))
        assertEquals(
            view(RaftPageRoute.Computer),
            page(
                AuthenticatedProductRoute.SettingsComputers,
                JSONObject().apply {
                    put(RAFT_SHELL_SERVER_ID, "srv-a")
                    put(SETTINGS_COMPUTER_ID, "m1")
                },
            ),
        )
        assertEquals(view(RaftPageRoute.Settings, RaftSettingsTab.Account), page(AuthenticatedProductRoute.SettingsAccount))
        assertEquals(view(RaftPageRoute.Settings, RaftSettingsTab.Storage), page(AuthenticatedProductRoute.SettingsStorage))
        assertEquals(view(RaftPageRoute.Settings, RaftSettingsTab.Feedback), page(AuthenticatedProductRoute.SettingsHandsFeedback))
        listOf(
            AuthenticatedProductRoute.ThreadMembers,
            AuthenticatedProductRoute.ChannelSettings,
            AuthenticatedProductRoute.ThreadForwardBundle,
            AuthenticatedProductRoute.FilePreview,
        ).forEach { assertNull(page(it), it.name) }
    }

    @Test
    fun settingsSectionsMapToRegistryTabs() {
        val native =
            mapOf(
                SettingsNativeDetailPage.LanguageRegion to RaftSettingsTab.LanguageRegion,
                SettingsNativeDetailPage.Appearance to RaftSettingsTab.Appearance,
                SettingsNativeDetailPage.Notifications to RaftSettingsTab.Notifications,
                SettingsNativeDetailPage.Mobile to RaftSettingsTab.Mobile,
                SettingsNativeDetailPage.Server to RaftSettingsTab.Server,
                SettingsNativeDetailPage.About to RaftSettingsTab.About,
            )
        SettingsNativeDetailPage.entries.forEach { page ->
            val params = JSONObject().apply { put(SETTINGS_NATIVE_DETAIL_PAGE_KEY, page.key) }
            val expected = native[page]?.let { view(RaftPageRoute.Settings, it) }
            if (page != SettingsNativeDetailPage.LocalFeatureOverride) {
                assertEquals(expected, authenticatedProductPageView(AuthenticatedProductRoute.SettingsNativeDetail, params) { false }, page.key)
            }
        }
        val web =
            mapOf(
                SettingsWebViewSurface.Billing to view(RaftPageRoute.Settings, RaftSettingsTab.Billing),
                SettingsWebViewSurface.Administration to view(RaftPageRoute.Settings, RaftSettingsTab.Administration),
                SettingsWebViewSurface.ImBridges to view(RaftPageRoute.Settings, RaftSettingsTab.ImBridges),
                SettingsWebViewSurface.Marketplace to view(RaftPageRoute.Settings, RaftSettingsTab.Integrations),
                SettingsWebViewSurface.Feedback to view(RaftPageRoute.Settings, RaftSettingsTab.Feedback),
                SettingsWebViewSurface.Computers to view(RaftPageRoute.Computers),
                SettingsWebViewSurface.ReleaseNotes to view(RaftPageRoute.ReleaseNotes),
                SettingsWebViewSurface.Onboarding to null,
            )
        web.forEach { (surface, expected) ->
            val params = JSONObject().apply { put(SETTINGS_WEB_VIEW_SURFACE, surface.diagnosticsName) }
            assertEquals(expected, authenticatedProductPageView(AuthenticatedProductRoute.SettingsWebView, params) { false }, surface.name)
        }
    }
}
