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
     * live shell-store snapshot. This test only pins the projection/store
     * divergence on the shared consume seam — the production call site is
     * covered by source review and device re-verify, not by this test.
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
        // The production Back consume decision runs on the live store snapshot;
        // feeding it the stripped render projection is the Alpha79 defect.
        assertTrue(appShellServerDrawerConsumesBack(store.state.value))
        val projected = store.state.value.withoutServerDrawerRuntimeForAppShellRender()
        assertFalse(appShellServerDrawerConsumesBack(projected))
        assertFalse(projected.home.serverDrawer.expanded)
        assertFalse(projected.home.serverDrawerCommandIntent.desiredOpen)
    }
}

class AppShellMembersDetailPaneTest {
    /**
     * task #58 web-style Members list|detail: the pane gate mirrors the shared
     * adaptive contract — desktop mode on + DualPane only — but is keyed to the
     * member selection, not the conversation workspace, so switching tabs never
     * clobbers either selection.
     */
    @Test
    fun `members detail pane only on desktop dual pane`() {
        assertTrue(
            appShellMembersDetailPaneVisible(
                desktopModeOnLargeScreen = true,
                layout = SlockAdaptivePaneLayout.DualPane,
            )
        )
        assertFalse(
            appShellMembersDetailPaneVisible(
                desktopModeOnLargeScreen = false,
                layout = SlockAdaptivePaneLayout.DualPane,
            )
        )
        assertFalse(
            appShellMembersDetailPaneVisible(
                desktopModeOnLargeScreen = true,
                layout = SlockAdaptivePaneLayout.Compact,
            )
        )
    }

    @Test
    fun `member detail close event only clears the matching live selection`() {
        val selection =
            AppShellMemberDetailSelection(
                serverId = "s1",
                memberId = "m1",
                kind = "agent",
                pageData = com.tencent.kuikly.core.nvi.serialization.json.JSONObject(),
                generation = 3L,
            )
        // Live close: same key + same generation clears.
        assertTrue(appShellMemberDetailCloseApplies("s1:agent:m1", 3L, selection))
        // Blank or foreign keys never clear.
        assertFalse(appShellMemberDetailCloseApplies("", 3L, selection))
        assertFalse(appShellMemberDetailCloseApplies("s1:human:m1", 3L, selection))
        assertFalse(appShellMemberDetailCloseApplies("s1:agent:m2", 3L, selection))
        // A close posted by a previous generation (incl. same-member reopen)
        // must not clear the newer selection.
        assertFalse(appShellMemberDetailCloseApplies("s1:agent:m1", 2L, selection))
        assertFalse(appShellMemberDetailCloseApplies("s1:agent:m1", 0L, selection))
        // A close against no live selection is a no-op.
        assertFalse(appShellMemberDetailCloseApplies("s1:agent:m1", 3L, null))
    }

    @Test
    fun `member detail open generation is shell-monotonic and invalidates stale writes`() {
        val generation = AppShellMemberDetailOpenGeneration()
        // Click A stamps 1; click B (same or different member) stamps 2.
        val openA = generation.next()
        val openB = generation.next()
        // Slow A completing after B was stamped must fail the fence — no
        // shared write (selection, pageData, agent-detail seed) may apply.
        assertTrue(openB == generation.current())
        assertFalse(openA == generation.current())
        // Closing the detail while a request is in flight invalidates it.
        generation.invalidate()
        assertFalse(openB == generation.current())
        // The generation is monotonic across the shell lifetime — including
        // the list being destroyed while the single-pane detail shows (narrow /
        // desktop-off), which previously reset a list-local counter to 0 and
        // let a reopened member replay an old close number.
        val reopened = generation.next()
        assertTrue(reopened > openB)
        assertFalse(openB == generation.current())
        assertTrue(openB != reopened)
    }

    @Test
    fun `stale member detail completion writes no shared detail or selection`() {
        // Exercises the exact production completion path
        // (applyMemberDetailOpenCompletion): a slow open resolving after a
        // newer click must run neither the agent-detail seed nor the selection
        // apply — if the fence were moved back behind the seed this fails.
        val generation = AppShellMemberDetailOpenGeneration()
        val openA = generation.next()
        generation.next() // newer click B wins
        var seeded = false
        var applied = false
        var staleLogged = false
        val result =
            applyMemberDetailOpenCompletion(
                generation = generation,
                openGeneration = openA,
                seedSharedDetail = { seeded = true },
                applySelection = { applied = true },
                onStale = { staleLogged = true },
            )
        assertFalse(result)
        assertFalse(seeded)
        assertFalse(applied)
        assertTrue(staleLogged)

        // The live completion seeds and applies exactly once.
        val openB = generation.next()
        var seededB = false
        var appliedB = false
        assertTrue(
            applyMemberDetailOpenCompletion(
                generation = generation,
                openGeneration = openB,
                seedSharedDetail = { seededB = true },
                applySelection = { appliedB = true },
            )
        )
        assertTrue(seededB)
        assertTrue(appliedB)
    }

    @Test
    fun `pending member detail open is cancelled by close or server switch`() {
        val generation = AppShellMemberDetailOpenGeneration()
        // First open in flight: no selection exists yet, but the pending
        // marker is shell-visible so Back/server-switch can cancel it.
        val open = generation.next()
        assertTrue(generation.pending == open)
        generation.invalidate()
        assertTrue(generation.pending == null)
        // The cancelled request can no longer apply.
        var applied = false
        assertFalse(
            applyMemberDetailOpenCompletion(
                generation = generation,
                openGeneration = open,
                seedSharedDetail = {},
                applySelection = { applied = true },
            )
        )
        assertFalse(applied)
        // A stale completion never clears a newer pending marker.
        val openA = generation.next()
        generation.settle(openA)
        assertTrue(generation.pending == null)
        val openB = generation.next()
        generation.settle(openA) // stale settle — no-op
        assertTrue(generation.pending == openB)
        generation.settle(openB)
        assertTrue(generation.pending == null)
    }

    @Test
    fun `member selection identity key distinguishes server kind member`() {
        val selection =
            AppShellMemberDetailSelection(
                serverId = "s1",
                memberId = "m1",
                kind = "agent",
                pageData = com.tencent.kuikly.core.nvi.serialization.json.JSONObject(),
                generation = 3L,
            )
        assertTrue(selection.key == "s1:agent:m1")
        val other =
            selection.copy(serverId = "s2")
        assertTrue(other.key != selection.key)
    }
}
