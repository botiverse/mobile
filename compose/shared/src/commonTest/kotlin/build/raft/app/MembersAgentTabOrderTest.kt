package build.raft.app

import build.raft.app.model.MemberKind
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
    fun tabDropIndexCrossesNeighbourMidpointsUsingTheirOwnWidths() {
        // Tabs 100, 40, 200, 60 px wide, 2 px apart; midpoints 50, 122, 244, 376.
        val widths = listOf(100, 40, 200, 60)
        val gap = 2f
        data class Case(val from: Int, val offset: Float, val expected: Int)
        listOf(
            // Index 0 (centre 50): crossing the narrow neighbour's midpoint (122) needs > 72 px,
            // not the dragged tab's own width.
            Case(from = 0, offset = 71f, expected = 0),
            Case(from = 0, offset = 73f, expected = 1),
            // Reaching the wide tab's midpoint (244) needs > 194 px; then the last tab (376).
            Case(from = 0, offset = 193f, expected = 1),
            Case(from = 0, offset = 195f, expected = 2),
            Case(from = 0, offset = 10_000f, expected = 3),
            // Leftward from the last tab (centre 376).
            Case(from = 3, offset = -131f, expected = 3),
            Case(from = 3, offset = -133f, expected = 2),
            Case(from = 3, offset = -10_000f, expected = 0),
            Case(from = 2, offset = 0f, expected = 2),
            // Out-of-range start is returned unchanged.
            Case(from = 7, offset = 50f, expected = 7)
        ).forEach { case ->
            assertEquals(
                case.expected,
                membersAgentTabDropIndex(widths, gap, case.from, case.offset),
                "drop index for $case"
            )
        }
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
