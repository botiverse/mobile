package build.raft.app

import build.raft.app.settings.AppShellRailSearchKey
import build.raft.app.settings.appShellDmsTabShowsDetail
import build.raft.app.settings.appShellRailSurfaceBackDismissesDetailFirst
import build.raft.app.settings.appShellSharedDetailSurfaceVisible
import build.raft.app.settings.appShellTasksPendingColumnAllowed
import build.raft.app.settings.appShellTasksPendingColumnOpen
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
    fun `detail surface visible on dms tab so an opened dm is not hidden`() {
        // task #33: tapping a DM on the wide Slack-nav DMs tab updates the shared detail; the
        // tab must count as a visible host or the selection lands in a detail nobody sees.
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "dms",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
            )
        )
        assertFalse(
            appShellSharedDetailSurfaceVisible(
                pageVisible = false,
                selectedTab = "dms",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
            )
        )
    }

    @Test
    fun `dms tab shows only dm conversations in its detail pane`() {
        // A channel selected on Home must not appear (with its composer) beside the DMs list.
        assertTrue(appShellDmsTabShowsDetail(RaftThreadSurfaceKind.DirectMessage, null))
        assertTrue(appShellDmsTabShowsDetail(RaftThreadSurfaceKind.ReplyThread, RaftThreadSurfaceKind.DirectMessage))
        assertFalse(appShellDmsTabShowsDetail(RaftThreadSurfaceKind.Channel, null))
        assertFalse(appShellDmsTabShowsDetail(RaftThreadSurfaceKind.ReplyThread, RaftThreadSurfaceKind.Channel))
        assertFalse(appShellDmsTabShowsDetail(null, null))
    }

    @Test
    fun `tasks page hosts the detail only while its task column is open`() {
        // task #34: the Tasks tab counts as a visible detail host only while a task opened
        // from the board is showing in the overlay; otherwise it still suspends the detail.
        assertTrue(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "tasks",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
                tasksDetailColumnOpen = true,
            )
        )
        assertFalse(
            appShellSharedDetailSurfaceVisible(
                pageVisible = true,
                selectedTab = "tasks",
                selectedHomeDestination = null,
                railInlineSecondaryDestination = null,
                tasksDetailColumnOpen = false,
            )
        )
        assertTrue(
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = "tasks",
                railInlineSecondaryDestination = null,
                selectionPresent = true,
                tasksDetailColumnOpen = true,
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
                selectedTab = "activity",
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
                selectedTab = "activity",
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
                selectedTab = "activity",
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
    fun `wide tasks pending column opens only on wide desktop tasks tab`() {
        assertTrue(
            appShellTasksPendingColumnAllowed(
                desktopModeOnLargeScreen = true,
                dualPane = true,
                selectedTab = "tasks",
            )
        )
        assertTrue(
            appShellTasksPendingColumnOpen(
                desktopModeOnLargeScreen = true,
                dualPane = true,
                selectedTab = "tasks",
                pendingSurfaceOpen = true,
            )
        )
        assertFalse(
            appShellTasksPendingColumnOpen(
                desktopModeOnLargeScreen = true,
                dualPane = true,
                selectedTab = "tasks",
                pendingSurfaceOpen = false,
            )
        )
        assertFalse(
            appShellTasksPendingColumnOpen(
                desktopModeOnLargeScreen = false,
                dualPane = true,
                selectedTab = "tasks",
                pendingSurfaceOpen = true,
            )
        )
        assertFalse(
            appShellTasksPendingColumnOpen(
                desktopModeOnLargeScreen = true,
                dualPane = false,
                selectedTab = "tasks",
                pendingSurfaceOpen = true,
            )
        )
        assertFalse(
            appShellTasksPendingColumnOpen(
                desktopModeOnLargeScreen = true,
                dualPane = true,
                selectedTab = "home",
                pendingSurfaceOpen = true,
            )
        )
        assertFalse(
            appShellTasksPendingColumnOpen(
                desktopModeOnLargeScreen = true,
                dualPane = true,
                selectedTab = "activity",
                pendingSurfaceOpen = true,
            )
        )
        assertFalse(
            appShellTasksPendingColumnOpen(
                desktopModeOnLargeScreen = true,
                dualPane = true,
                selectedTab = "tasks",
                pendingSurfaceOpen = true,
                railInlineSecondaryDestination = AppShellRailSearchKey,
            )
        )
    }
}
