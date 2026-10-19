package build.raft.app

import build.raft.app.model.MemberKind
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class MembersAgentTabOrderTest {

    @Test
    fun runtimeErrorBannerMatchesReactSharedPanelScope() {
        val error = "Agent encountered an error"

        assertEquals(
            MembersAgentRuntimeErrorBannerState(errorText = error, showViewLogs = true),
            membersAgentRuntimeErrorBannerState(
                memberKind = MemberKind.Agent,
                selectedTab = MembersAgentDetailTab.Profile,
                runtimeErrorText = error
            )
        )
        assertEquals(
            MembersAgentRuntimeErrorBannerState(errorText = error, showViewLogs = false),
            membersAgentRuntimeErrorBannerState(
                memberKind = MemberKind.Agent,
                selectedTab = MembersAgentDetailTab.Activity,
                runtimeErrorText = error
            )
        )
        assertEquals(
            MembersAgentRuntimeErrorBannerState(errorText = error, showViewLogs = true),
            membersAgentRuntimeErrorBannerState(
                memberKind = MemberKind.Agent,
                selectedTab = MembersAgentDetailTab.Chat,
                runtimeErrorText = error
            )
        )
        assertEquals(
            null,
            membersAgentRuntimeErrorBannerState(
                memberKind = MemberKind.Human,
                selectedTab = MembersAgentDetailTab.Profile,
                runtimeErrorText = error
            )
        )
    }

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
                MembersAgentDetailTab.Chat,
                MembersAgentDetailTab.Apps,
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace
            ),
            ordered
        )
    }

    @Test
    fun mapsLegacyDmsOrderKeyToChatTab() {
        val visibleTabs = membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = false)

        val ordered = membersOrderedAgentTabs(
            visibleTabs = visibleTabs,
            storedOrder = listOf("dms", "permissions", "profile")
        )

        assertEquals(
            listOf(
                MembersAgentDetailTab.Chat,
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace,
                MembersAgentDetailTab.Apps
            ),
            ordered
        )
    }

    @Test
    fun writesReactCompatibleAgentPanelTabOrderKeys() {
        val wireKeys =
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Chat,
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
                MembersAgentDetailTab.Chat,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Activity
            )

        assertEquals(
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Chat
            ),
            tabs.membersMoveTab(fromIndex = 1, toIndex = 3)
        )
    }

    @Test
    fun invalidMoveReturnsOriginalTabList() {
        val tabs = listOf(MembersAgentDetailTab.Profile, MembersAgentDetailTab.Chat)

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
                MembersAgentDetailTab.Chat,
                MembersAgentDetailTab.Apps
            )

        assertEquals(0, membersAgentTabVisibleAnchorIndex(tabs, MembersAgentDetailTab.Profile))
        assertEquals(1, membersAgentTabVisibleAnchorIndex(tabs, MembersAgentDetailTab.Chat))
        assertEquals(0, membersAgentTabVisibleAnchorIndex(tabs, MembersAgentDetailTab.Activity))
    }

    @Test
    fun detailBodyKeysSeparateDifferentTabSurfaces() {
        val profileKey = membersAgentDetailBodyItemKey(" agent-1 ", MembersAgentDetailTab.Profile)
        val chatKey = membersAgentDetailBodyItemKey("agent-1", MembersAgentDetailTab.Chat)

        assertEquals("member-detail-agent-1-tab-Profile", profileKey)
        assertEquals(profileKey, membersAgentDetailBodyItemKey("agent-1", MembersAgentDetailTab.Profile))
        assertNotEquals(profileKey, chatKey)
    }

    @Test
    fun shortAgentTabsUseSharedTopTabMinimumWidth() {
        assertEquals(98.dp, membersAgentTabMinimumWidth(MembersAgentDetailTab.Activity))
        assertEquals(98.dp, membersAgentTabMinimumWidth(MembersAgentDetailTab.Profile))
        assertEquals(98.dp, membersAgentTabMinimumWidth(MembersAgentDetailTab.Apps))
        assertEquals(112.dp, membersAgentTabMinimumWidth(MembersAgentDetailTab.Reminders))
    }

    @Test
    fun activityTabInitialIndexPinsToLatestEntry() {
        assertEquals(0, membersAgentActivityInitialFirstVisibleIndex(0))
        assertEquals(0, membersAgentActivityInitialFirstVisibleIndex(1))
        assertEquals(4, membersAgentActivityInitialFirstVisibleIndex(5))
    }

    @Test
    fun activityTabBottomAnchorRespectsManualHistoryScroll() {
        assertEquals(
            false,
            membersAgentActivityNextBottomAnchor(
                current = true,
                isScrollInProgress = true,
                isAtBottom = false
            )
        )
        assertEquals(
            false,
            membersAgentActivityNextBottomAnchor(
                current = false,
                isScrollInProgress = false,
                isAtBottom = false
            )
        )
        assertEquals(
            true,
            membersAgentActivityNextBottomAnchor(
                current = false,
                isScrollInProgress = false,
                isAtBottom = true
            )
        )
    }
}
