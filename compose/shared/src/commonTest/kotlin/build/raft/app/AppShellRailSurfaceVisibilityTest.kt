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
    fun `detail surface visible on dms tab so an opened dm is not hidden`() {
        // task #33: tapping a DM on the wide Slack-nav DMs tab updates the shared detail; the
        // tab must count as a visible host or the selection lands in a detail nobody sees.
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = SlockSlackNavTabs.Dms,
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
            )
        )
        assertFalse(
            appShellSharedDetailSurfaceVisible(
                pageVisible = false,
                selectedTab = SlockSlackNavTabs.Dms,
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
            )
        )
    }

    @Test
    fun `first back on dms tab dismisses the open dm before leaving the tab`() {
        assertTrue(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = SlockSlackNavTabs.Dms,
                railInlineSecondaryDestination = null,
                selectionPresent = true,
            )
        )
        assertFalse(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = SlockSlackNavTabs.Dms,
                railInlineSecondaryDestination = null,
                selectionPresent = false,
            )
        )
    }

    @Test
    fun `dms tab shows only dm conversations in its detail pane`() {
        // A channel selected on Home must not appear (with its composer) beside the DMs list.
        assertTrue(appShellDmsTabShowsDetail(KuiklyThreadSurfaceKind.DirectMessage, null))
        assertTrue(appShellDmsTabShowsDetail(KuiklyThreadSurfaceKind.ReplyThread, KuiklyThreadSurfaceKind.DirectMessage))
        assertFalse(appShellDmsTabShowsDetail(KuiklyThreadSurfaceKind.Channel, null))
        assertFalse(appShellDmsTabShowsDetail(KuiklyThreadSurfaceKind.ReplyThread, KuiklyThreadSurfaceKind.Channel))
        assertFalse(appShellDmsTabShowsDetail(null, null))
    }

    @Test
    fun `dm row highlight follows the detail selection`() {
        assertTrue(isWeChatDmRowSelected("dm-1", "dm-1"))
        assertFalse(isWeChatDmRowSelected("dm-1", "dm-2"))
        assertFalse(isWeChatDmRowSelected("dm-1", null))
        assertFalse(isWeChatDmRowSelected(null, null))
    }

    @Test
    fun `tasks board hosts the detail only while its overlay is open`() {
        // task #34: the Tasks tab counts as a visible detail host only while a task opened
        // from the board is showing in the overlay; otherwise it still suspends the detail.
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "tasks",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
                tasksDetailOverlayOpen = true,
            )
        )
        assertFalse(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "tasks",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
                tasksDetailOverlayOpen = false,
            )
        )
        assertTrue(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "tasks",
                railInlineSecondaryDestination = null,
                selectionPresent = true,
                tasksDetailOverlayOpen = true,
            )
        )
    }


    @Test
    fun `tasks overlay shows only while the board owns the open detail on a wide desktop screen`() {
        fun open(
            desktop: Boolean = true,
            dualPane: Boolean = true,
            tab: String = "tasks",
            selection: Boolean = true,
            active: Boolean = true,
        ) = appShellTasksDetailOverlayOpen(desktop, dualPane, tab, selection, active)

        assertTrue(open())
        // Not opened from the board (e.g. a conversation selected on Home): no overlay.
        assertFalse(open(active = false))
        assertFalse(open(selection = false))
        assertFalse(open(tab = "home"))
        assertFalse(open(desktop = false))
        assertFalse(open(dualPane = false))
    }


    @Test
    fun `tasks overlay ownership survives in-detail navigation and ends when the surface changes`() {
        // Navigating inside the detail (thread -> parent channel) keeps a selection on the
        // Tasks tab, so ownership holds; leaving the tab, losing the wide layout, turning
        // desktop mode off, or ending the selection releases it.
        assertTrue(appShellTasksDetailOverlayStaysActive(true, true, "tasks", true))
        assertFalse(appShellTasksDetailOverlayStaysActive(true, true, "home", true))
        assertFalse(appShellTasksDetailOverlayStaysActive(true, false, "tasks", true))
        assertFalse(appShellTasksDetailOverlayStaysActive(false, true, "tasks", true))
        assertFalse(appShellTasksDetailOverlayStaysActive(true, true, "tasks", false))
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
        // No selection -> the destination itself exits; non-rail tabs keep legacy order.
        assertFalse(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = SlockSlackNavTabs.Activity,
                railInlineSecondaryDestination = null,
                selectionPresent = false,
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
    fun `inline saved behaves like inline search on rail surfaces`() {
        // task #110: desktop-mode Saved opens as the rail-inline list pane, like Search.
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = AppShellRailSavedKey,
            )
        )
        // First Back closes an open saved message, then exits Saved.
        assertTrue(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "home",
                railInlineSecondaryDestination = AppShellRailSavedKey,
                selectionPresent = true,
            )
        )
        assertFalse(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "home",
                railInlineSecondaryDestination = AppShellRailSavedKey,
                selectionPresent = false,
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
