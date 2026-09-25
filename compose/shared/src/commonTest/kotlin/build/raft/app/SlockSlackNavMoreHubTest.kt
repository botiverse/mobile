package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #401 (supersedes the #386 hub page): in Slack-nav mode the More tab is an action menu.
 * Every More tap — including a re-tap while More is already selected — must reopen the menu,
 * so the user can never get stuck on the last-opened sub-page.
 */
class SlockSlackNavMoreHubTest {

    @Test
    fun everyMoreTapInSlackNavOpensMenu() {
        assertTrue(slockSlackNavMoreTapOpensMenu(slackNavMode = true, tappedTab = SlockSlackNavTabs.More))
    }

    @Test
    fun otherTabsAndLegacyModeKeepNormalTabSwitching() {
        assertFalse(slockSlackNavMoreTapOpensMenu(slackNavMode = true, tappedTab = SlockSlackNavTabs.Home))
        assertFalse(slockSlackNavMoreTapOpensMenu(slackNavMode = true, tappedTab = SlockSlackNavTabs.Dms))
        assertFalse(slockSlackNavMoreTapOpensMenu(slackNavMode = true, tappedTab = SlockSlackNavTabs.Activity))
        // Gate off: legacy four-tab bar has no More tab; nothing is intercepted.
        assertFalse(slockSlackNavMoreTapOpensMenu(slackNavMode = false, tappedTab = SlockSlackNavTabs.More))
        assertFalse(slockSlackNavMoreTapOpensMenu(slackNavMode = false, tappedTab = "settings"))
    }

    @Test
    fun menuOffersExactlyTasksMembersSettings() {
        assertEquals(
            listOf("Tasks", "Members", "Settings"),
            SlockSlackNavMoreDestination.entries.map { it.name }
        )
    }
}
