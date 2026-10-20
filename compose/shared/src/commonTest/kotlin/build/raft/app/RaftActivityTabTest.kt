package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Activity as the second app-shell bottom tab, with the 通知 segment (artin 2026-10-20). */
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
    fun launchSelectionRoutesActivityAndNotificationCenterAliasesToTheActivityTab() {
        data class Case(val tab: String, val segment: String?, val expected: AppShellLaunchSelection)
        val cases =
            listOf(
                Case("activity", null, AppShellLaunchSelection("activity")),
                Case("activity", "notifications", AppShellLaunchSelection("activity", activityNotificationsSegment = true)),
                Case("activity", "unknown", AppShellLaunchSelection("activity")),
                // The old Home 动态 page name and inbox naming still resolve.
                Case(HOME_ACTIVITY_PAGE, null, AppShellLaunchSelection("activity")),
                Case("inbox", null, AppShellLaunchSelection("activity")),
                // The notification center was a Home popover; asking for it lands on 通知.
                Case("notifications", null, AppShellLaunchSelection("activity", activityNotificationsSegment = true)),
                Case("notification_center", null, AppShellLaunchSelection("activity", activityNotificationsSegment = true)),
                // A segment without the Activity tab is ignored.
                Case("tasks", "notifications", AppShellLaunchSelection("tasks")),
                Case("unknown", "notifications", AppShellLaunchSelection("home")),
            )
        cases.forEach { case ->
            val params = JSONObject().put("slockInitialTab", case.tab)
            case.segment?.let { params.put("slockInitialActivitySegment", it) }
            assertEquals(case.expected, params.appShellLaunchSelection(), "case=$case")
        }
    }

    @Test
    fun tabBadgeAddsActivityUnreadAndNotificationCenterItems() {
        assertEquals(0, raftActivityTabBadgeCount(activityUnreadCount = 0, notificationCount = 0))
        assertEquals(5, raftActivityTabBadgeCount(activityUnreadCount = 3, notificationCount = 2))
        assertEquals(2, raftActivityTabBadgeCount(activityUnreadCount = -1, notificationCount = 2))
        val tabs =
            appShellBottomTabs(
                selectedKey = "home",
                orderedTabKeys = listOf("home", "activity", "tasks"),
                activityBadgeCount = 5,
                labelFor = { it }
            )
        assertEquals(listOf(0, 5, 0), tabs.map { it.badgeCount })
    }

    @Test
    fun notificationSegmentListsTheBellItemsWithoutTheAppUpdate() {
        val items =
            listOf(
                RaftHomeNotificationItem(id = APP_UPDATE_HOME_NOTIFICATION_ID, title = "Update"),
                RaftHomeNotificationItem(id = "machine-offline:m1", title = "Offline"),
                RaftHomeNotificationItem(id = "blank"),
            )
        assertEquals(listOf("machine-offline:m1"), items.raftActivityNotificationItems().map { it.id })
    }

    @Test
    fun notificationViewKeepsTheBellPopoverRouting() {
        val opened = mutableListOf<String>()
        fun route(item: RaftHomeNotificationItem, appUpdate: (() -> Unit)? = { opened += "app-update" }) =
            routeRaftHomeNotificationView(
                notification = item,
                onAppUpdateOpen = appUpdate,
                onOpenComputers = { opened += "computers" },
                onOpenUrl = { opened += "url:$it" }
            )
        route(RaftHomeNotificationItem(id = APP_UPDATE_HOME_NOTIFICATION_ID))
        route(RaftHomeNotificationItem(id = "machine-outdated:m1"))
        route(RaftHomeNotificationItem(id = "n1", targetId = "https://app.raft.build/x"))
        route(RaftHomeNotificationItem(id = "n2", targetId = "channel-1"))
        route(RaftHomeNotificationItem(id = APP_UPDATE_HOME_NOTIFICATION_ID), appUpdate = null)
        assertEquals(listOf("app-update", "computers", "url:https://app.raft.build/x"), opened)
    }
}
