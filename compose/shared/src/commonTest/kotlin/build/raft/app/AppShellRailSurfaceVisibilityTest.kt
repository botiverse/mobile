package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #13 (PR #2747 follow-up): the rail surfaces that host the shared adaptive detail
 * slot — Home (without a pushed secondary page), the Activity tab, and an inline rail
 * secondary destination (Search) — must keep the workspace parentVisible so the detail is
 * not suspended mid-open; other destinations or a backgrounded host still suspend it.
 * Back/close on those surfaces must dismiss the detail before exiting the destination.
 */
class AppShellRailSurfaceVisibilityTest {
    @Test
    fun `detail surface visible on home without secondary destination`() {
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
            )
        )
    }

    @Test
    fun `detail surface visible on activity tab and inline search`() {
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = SlockSlackNavTabs.Activity,
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
            )
        )
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = AppShellRailSearchKey,
            )
        )
    }

    @Test
    fun `detail surface suspends on unrelated tab pushed destination or background`() {
        assertFalse(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "tasks",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
            )
        )
        assertFalse(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = HomeDestinationSearch,
                railInlineSecondaryDestination = null,
            )
        )
        assertFalse(
            appShellSharedDetailSurfaceVisible(
                pageVisible = false,
                selectedTab = SlockSlackNavTabs.Activity,
                selectedHomeDestination = null,
                railInlineSecondaryDestination = AppShellRailSearchKey,
            )
        )
    }

    @Test
    fun `first back on rail surfaces dismisses detail before exiting`() {
        // Selection present on Activity tab or inline Search -> dismiss first.
        assertTrue(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = SlockSlackNavTabs.Activity,
                railInlineSecondaryDestination = null,
                selectionPresent = true,
            )
        )
        assertTrue(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "home",
                railInlineSecondaryDestination = AppShellRailSearchKey,
                selectionPresent = true,
            )
        )
        // Wide Home surface hosting the shared detail (Markdown reply permalink
        // landing) — the Alpha76 wide-mode BACK regression — must dismiss the
        // detail first instead of falling through to close_page.
        assertTrue(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "home",
                railInlineSecondaryDestination = null,
                selectionPresent = true,
                selectedHomeDestination = null,
            )
        )
        // No selection -> the destination itself exits; non-rail tabs keep legacy order.
        assertFalse(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = SlockSlackNavTabs.Activity,
                railInlineSecondaryDestination = null,
                selectionPresent = false,
            )
        )
        // Compact-mode / legacy home behaviour unchanged: a selection while a home
        // secondary destination is open is not a rail-surface detail.
        assertFalse(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "home",
                railInlineSecondaryDestination = null,
                selectionPresent = true,
                selectedHomeDestination = HomeDestinationSearch,
            )
        )
        assertFalse(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "home",
                railInlineSecondaryDestination = AppShellRailSearchKey,
                selectionPresent = false,
            )
        )
        assertFalse(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "tasks",
                railInlineSecondaryDestination = null,
                selectionPresent = true,
            )
        )
    }

    @Test
    fun `activity destination survives narrowing once reached`() {
        val legacy = SlockSlackNavTabs.LegacyOrder
        // Wide rail: Activity allowed even under legacy keys.
        assertTrue(
            SlockSlackNavTabs.Activity in
                appShellAllowedTabKeys(legacy, railActive = true, railInlineSecondaryDestination = null, activityReachedThisSession = false)
        )
        // Narrow while an inline destination covers the middle pane: still allowed.
        assertTrue(
            SlockSlackNavTabs.Activity in
                appShellAllowedTabKeys(legacy, railActive = false, railInlineSecondaryDestination = AppShellRailSearchKey, activityReachedThisSession = false)
        )
        // Narrow after the destination was reached earlier this session: still allowed.
        assertTrue(
            SlockSlackNavTabs.Activity in
                appShellAllowedTabKeys(legacy, railActive = false, railInlineSecondaryDestination = null, activityReachedThisSession = true)
        )
        // Narrow with Activity never reached: legacy clamp unchanged.
        assertFalse(
            SlockSlackNavTabs.Activity in
                appShellAllowedTabKeys(legacy, railActive = false, railInlineSecondaryDestination = null, activityReachedThisSession = false)
        )
        // Slack order already contains Activity: never duplicated.
        val slack = SlockSlackNavTabs.DefaultOrder
        assertTrue(
            appShellAllowedTabKeys(slack, railActive = true, railInlineSecondaryDestination = null, activityReachedThisSession = false).count {
                it == SlockSlackNavTabs.Activity
            } == 1
        )
    }
}

class AppShellServerDrawerBackProjectionTest {
    /**
     * The Alpha79 drawer-BACK defect: `popMainRoute` read `serverSwitcherExpanded`
     * from the render projection, which strips the drawer runtime when the app-shell
     * drawer host owns it — so Back always saw `false` and fell through to
     * close_page even with the drawer settled open. Back must consume from the
     * live shell-store snapshot; this pins both sides of the trap: the projection
     * reported closed while the store snapshot (the production read path) reports
     * expanded.
     */
    @Test
    fun `live store reports drawer expanded while render projection strips it`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val liveHome =
            build.raft.app.data.SharedHomeState(
                serverSwitcherExpanded = true,
                serverDrawer = build.raft.app.data.HomeServerDrawerState(expanded = true),
                serverDrawerCommandIntent =
                    build.raft.app.data.HomeServerDrawerCommandIntent(
                        desiredOpen = true,
                        generation = 7L,
                    ),
            )
        val store =
            build.raft.app.data.SharedHomeShellStore(
                homeState = kotlinx.coroutines.flow.MutableStateFlow(liveHome),
                activityState =
                    kotlinx.coroutines.flow.MutableStateFlow(build.raft.app.data.SharedActivityState()),
                searchSavedState =
                    kotlinx.coroutines.flow.MutableStateFlow(
                        build.raft.app.data.SharedHomeSearchSavedState()
                    ),
                dispatchHome = { _ -> },
                dispatchActivity = { _ -> },
                dispatchSearchSaved = { _ -> },
                refreshActivitySummary = { _, _ -> },
                resetActivity = {},
                scope = scope,
            )
        // The read path popMainRoute now takes for the Back consume check.
        assertTrue(store.state.value.home.serverSwitcherExpanded)
        val projected = store.state.value.withoutServerDrawerRuntimeForAppShellRender()
        assertFalse(projected.home.serverSwitcherExpanded)
        assertFalse(projected.home.serverDrawer.expanded)
        assertFalse(projected.home.serverDrawerCommandIntent.desiredOpen)
    }
}
