package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MembersAgentTabOrderTest {
    @Test
    fun ordersAgentTabsFromReactSidebarOrderKeys() {
        val visibleTabs = membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = false)

        val ordered = membersOrderedAgentTabs(
            visibleTabs = visibleTabs,
            storedOrder = listOf("activity", "chat", "integrations", "profile")
        )

        assertEquals(
            listOf(
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Dms,
                MembersAgentDetailTab.Apps,
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Permissions,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace
            ),
            ordered
        )
    }

    @Test
    fun writesReactCompatibleAgentPanelTabOrderKeys() {
        val wireKeys =
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Dms,
                MembersAgentDetailTab.Apps,
                MembersAgentDetailTab.Activity
            ).toMembersAgentPanelTabOrderWireKeys()

        assertEquals(listOf("profile", "chat", "integrations", "activity"), wireKeys)
    }

    @Test
    fun movesAgentTabToRequestedIndex() {
        val tabs =
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Dms,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Activity
            )

        assertEquals(
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Dms
            ),
            tabs.membersMoveTab(fromIndex = 1, toIndex = 3)
        )
    }

    @Test
    fun invalidMoveReturnsOriginalTabList() {
        val tabs = listOf(MembersAgentDetailTab.Profile, MembersAgentDetailTab.Dms)

        assertSame(tabs, tabs.membersMoveTab(fromIndex = -1, toIndex = 1))
        assertSame(tabs, tabs.membersMoveTab(fromIndex = 0, toIndex = 2))
        assertSame(tabs, tabs.membersMoveTab(fromIndex = 1, toIndex = 1))
    }

    @Test
    fun selectedAgentTabScrollAnchorKeepsPriorTabVisible() {
        val tabs =
            listOf(
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Dms,
                MembersAgentDetailTab.Apps
            )

        assertEquals(0, membersAgentTabVisibleAnchorIndex(tabs, MembersAgentDetailTab.Profile))
        assertEquals(1, membersAgentTabVisibleAnchorIndex(tabs, MembersAgentDetailTab.Dms))
        assertEquals(0, membersAgentTabVisibleAnchorIndex(tabs, MembersAgentDetailTab.Activity))
    }
}
