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
    fun activityUnreadIsADotNeverANumberAndNeverOnTheSelectedTab() {
        // raft-design MobileNav / Badge + web MainLayout ActivityTabUnreadDot.
        val unselected =
            appShellBottomTabs(
                selectedKey = "home",
                orderedTabKeys = listOf("home", "activity", "tasks"),
                activityUnreadCount = 5,
                labelFor = { it }
            )
        assertEquals(listOf(false, true, false), unselected.map { it.attentionDot })
        assertEquals(listOf(0, 0, 0), unselected.map { it.badgeCount })
        val selected =
            appShellBottomTabs(
                selectedKey = "activity",
                orderedTabKeys = listOf("home", "activity"),
                activityUnreadCount = 5,
                labelFor = { it }
            )
        assertEquals(listOf(false, false), selected.map { it.attentionDot })
        listOf(0, -1).forEach { count ->
            assertEquals(
                false,
                appShellBottomTabs(
                    selectedKey = "home",
                    orderedTabKeys = listOf("activity"),
                    activityUnreadCount = count,
                    labelFor = { it }
                ).single().attentionDot,
                "count=$count"
            )
        }
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
