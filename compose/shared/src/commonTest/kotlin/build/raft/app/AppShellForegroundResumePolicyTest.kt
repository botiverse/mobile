package build.raft.app

import build.raft.app.data.SharedHomeState
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppShellForegroundResumePolicyTest {
    @Test
    fun childTabActivityRequiresTheParentPagerToBeVisible() {
        assertTrue(
            appShellChildTabIsActive(
                parentVisible = true,
                selectedTab = "home",
                tab = "home"
            )
        )
        assertFalse(
            appShellChildTabIsActive(
                parentVisible = false,
                selectedTab = "home",
                tab = "home"
            )
        )
        assertFalse(
            appShellChildTabIsActive(
                parentVisible = true,
                selectedTab = "home",
                tab = "tasks"
            )
        )
    }

    @Test
    fun hiddenParentCannotRestartHome() {
        assertFalse(
            appShellParentShouldStartHome(
                pageVisible = false,
                selectedTab = "home",
                selectedHomeDestination = null,
                readyServerId = "server-1",
                state = SharedHomeState()
            )
        )
    }

    @Test
    fun lifecycleRefreshRequiresTheAppShellPagerToBeVisible() {
        assertTrue(
            appShellLifecycleCanRefreshVisibleRoute(
                pageVisible = true,
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleVisibleResume
            )
        )
        assertTrue(
            appShellLifecycleCanRefreshVisibleRoute(
                pageVisible = true,
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleVisibleShow
            )
        )
        assertFalse(
            appShellLifecycleCanRefreshVisibleRoute(
                pageVisible = false,
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleVisibleResume
            )
        )
        assertFalse(
            appShellLifecycleCanRefreshVisibleRoute(
                pageVisible = true,
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleHidden
            )
        )
    }

    @Test
    fun foregroundPlanScopesDependenciesToTheVisibleRoute() {
        assertEquals(
            AppShellForegroundResumePlan(
                refreshVisibleRoute = true,
                refreshActivityUnreadSummary = true,
                refreshServerMembers = true
            ),
            appShellForegroundResumePlan(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = null
            )
        )
        assertEquals(
            AppShellForegroundResumePlan(
                refreshVisibleRoute = true,
                refreshActivityUnreadSummary = false,
                refreshServerMembers = false
            ),
            appShellForegroundResumePlan(
                pageVisible = true,
                selectedTab = "tasks",
                selectedHomeDestination = null
            )
        )
        assertNull(
            appShellForegroundResumePlan(
                pageVisible = false,
                selectedTab = "home",
                selectedHomeDestination = null
            )
        )
        assertNull(
            appShellForegroundResumePlan(
                pageVisible = true,
                selectedTab = "home",
                selectedHomeDestination = "search"
            )
        )
    }

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
