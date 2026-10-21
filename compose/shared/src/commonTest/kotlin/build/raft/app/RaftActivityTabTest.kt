package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.designsystem.components.raftUnreadCountText
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
            listOf("home", "activity", "tasks", "more", "members", "settings"),
            appShellRetainedChildTabs(
                activeChildTab = "home",
                mountedTabs = setOf("settings", "members", "more", "tasks", "activity", "home")
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
    fun activityUnreadIsTheOnlyNumberedTabBadgeAndNeverOnTheSelectedTab() {
        // artin 2026-10-21 (with Jasper): the Activity tab wears the pink unread count, not a dot.
        val unselected =
            appShellBottomTabs(
                selectedKey = "home",
                orderedTabKeys = listOf("home", "activity", "tasks", "more"),
                activityUnreadCount = 5,
                labelFor = { it }
            )
        assertEquals(listOf(0, 5, 0, 0), unselected.map { it.badgeCount })
        assertEquals(listOf(false, false, false, false), unselected.map { it.attentionDot })
        // Selected: hidden, like the dot it replaces.
        val selected =
            appShellBottomTabs(
                selectedKey = "activity",
                orderedTabKeys = listOf("home", "activity"),
                activityUnreadCount = 5,
                labelFor = { it }
            )
        assertEquals(listOf(0, 0), selected.map { it.badgeCount })
        listOf(0, -1).forEach { count ->
            assertEquals(0, appShellActivityTabBadgeCount(count, selected = false), "count=$count")
        }
        // The Compose bar and rail draw it with raft-ui's count text; iOS gets the same cap.
        listOf(1 to "1", 99 to "99", 100 to "99+", 4321 to "99+").forEach { (count, text) ->
            assertEquals(text, raftUnreadCountText(count), "count=$count")
            assertEquals(text, raftNativeTabBarBadgeValue(appShellActivityTabBadgeCount(count, selected = false)))
        }
        assertEquals(null, raftNativeTabBarBadgeValue(appShellActivityTabBadgeCount(0, selected = false)))
        // More keeps its dot and never a number.
        val more =
            appShellBottomTabs(
                selectedKey = "home",
                orderedTabKeys = listOf("activity", "more"),
                activityUnreadCount = 2,
                moreAttention = setOf(RaftMoreAttention.AppUpdate),
                labelFor = { it }
            ).associateBy { it.key }
        assertEquals(2 to false, more.getValue("activity").let { it.badgeCount to it.attentionDot })
        assertEquals(0 to true, more.getValue("more").let { it.badgeCount to it.attentionDot })
    }

    @Test
    fun oldMembersAndSettingsLaunchValuesStillOpenThosePages() {
        // Deep links / notifications / account + diagnostics entry points still pass
        // slockInitialTab=members|settings: they land on the page with the bar on More. On the
        // phone a settings selection then moves to the More tab, whose root page is Settings.
        listOf("members", "settings").forEach { tab ->
            val selection = JSONObject().put("slockInitialTab", tab).appShellLaunchSelection()
            assertEquals(tab, selection.selectedTab)
            assertEquals("more", appShellPhoneBottomBarSelectedKey(selection.selectedTab))
        }
        assertEquals("more", appShellSettingsRootTab("settings", navRailActive = false))
        assertEquals("more", JSONObject().put("slockInitialTab", "more").appShellLaunchSelection().selectedTab)
        // Search is a tab (stdrc option B), so a launch value can select it.
        assertEquals("search", JSONObject().put("slockInitialTab", "search").appShellLaunchSelection().selectedTab)
    }
}
