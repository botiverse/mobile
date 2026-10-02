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
