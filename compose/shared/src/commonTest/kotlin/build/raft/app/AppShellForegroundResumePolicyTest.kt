package build.raft.app

import build.raft.app.settings.AppShellForegroundResumePlan
import build.raft.app.settings.appShellForegroundResumePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppShellForegroundResumePolicyTest {
    @Test
    fun foregroundPlanRefreshesActivityDestinationWithoutHomeDependencies() {
        // task #383: the Activity destination joins the visible-route refresh,
        // while unread summary and members stay scoped to the Home route.
        assertEquals(
            AppShellForegroundResumePlan(
                refreshVisibleRoute = true,
                refreshActivityUnreadSummary = false,
                refreshServerMembers = false
            ),
            appShellForegroundResumePlan(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = "activity"
            )
        )
        assertNull(
            appShellForegroundResumePlan(
                pageVisible = false,
                selectedTab = "home",
                selectedHomeDestination = "activity"
            )
        )
        assertNull(
            appShellForegroundResumePlan(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = "saved"
            )
        )
    }
}
