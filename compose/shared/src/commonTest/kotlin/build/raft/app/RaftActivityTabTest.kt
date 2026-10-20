package build.raft.app

import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Activity as the second app-shell bottom tab (artin 2026-10-20). The notification center stays
 * the Home head's Bell popover (task #18): Activity has no 通知 segment and its badge is Activity
 * unread only.
 */
class RaftActivityTabTest {
    @Test
    fun activityIsAKeptAliveChildTabInSecondPosition() {
        assertEquals("activity", appShellActiveChildPagerTab("activity"))
        assertEquals(
            listOf("home", "activity", "tasks", "members", "settings"),
            appShellRetainedChildTabs(
                activeChildTab = "home",
                mountedTabs = setOf("settings", "members", "tasks", "activity", "home")
            )
        )
        assertEquals("kuikly_activity", appShellBottomTabPerformanceRoute("activity"))
    }

    @Test
    fun launchSelectionRoutesActivityAliasesToTheActivityTab() {
        val cases =
            listOf(
                "activity" to "activity",
                // The old Home 动态 page name and inbox naming still resolve.
                HOME_ACTIVITY_PAGE to "activity",
                "inbox" to "activity",
                "tasks" to "tasks",
                // The notification center is the Home Bell popover, not a tab: no deep link.
                "notifications" to "home",
                "notification_center" to "home",
                "unknown" to "home",
            )
        cases.forEach { (tab, expected) ->
            val params = JSONObject().put("slockInitialTab", tab)
            assertEquals(AppShellLaunchSelection(expected), params.appShellLaunchSelection(), "tab=$tab")
        }
    }

    @Test
    fun tabBadgeIsOnlyOnActivity() {
        val tabs =
            appShellBottomTabs(
                selectedKey = "home",
                orderedTabKeys = listOf("home", "activity", "tasks"),
                activityBadgeCount = 5,
                labelFor = { it }
            )
        assertEquals(listOf(0, 5, 0), tabs.map { it.badgeCount })
        assertEquals(
            0,
            appShellBottomTabs(
                selectedKey = "home",
                orderedTabKeys = listOf("activity"),
                activityBadgeCount = -1,
                labelFor = { it }
            ).single().badgeCount
        )
    }
}
