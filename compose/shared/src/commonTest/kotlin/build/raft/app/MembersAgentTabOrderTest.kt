package build.raft.app

import build.raft.app.model.MemberKind
import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun activityTabBottomAnchorSurvivesAFlickThatIsNeverSampledAsScrolling() {
        // The reported shape: a reader browsing upward is thrown to the bottom when a new
        // row arrives. Clearing the anchor from scroll state requires OBSERVING
        // isScrollInProgress == true, and a flick can move both fields between two
        // snapshots, so the sampled sequence can go straight from (idle, atBottom) to
        // (idle, awayFromBottom) with the scrolling state never emitted once.
        //
        // Sampled path alone: the anchor survives at its initial `true`, which is the bug.
        var anchor = true
        anchor = membersAgentActivityNextBottomAnchor(
            current = anchor,
            isScrollInProgress = false,
            isAtBottom = false
        )
        assertEquals(
            true,
            anchor,
            "guard: the sampled path cannot clear the anchor when the scrolling state is " +
                "never observed -- this is why a pointer event has to do it"
        )

        // The pointer move is what production now clears it with, and a pointer event
        // cannot be lost to sampling.
        anchor = false

        // Idle and away from the bottom must KEEP it cleared: `else -> current` is what
        // carries the decision across the arriving row, so it must carry `false` too.
        anchor = membersAgentActivityNextBottomAnchor(
            current = anchor,
            isScrollInProgress = false,
            isAtBottom = false
        )
        assertEquals(false, anchor, "an arriving row must not scroll a reader who moved away")

        // Only genuinely reaching the bottom re-arms following.
        anchor = membersAgentActivityNextBottomAnchor(
            current = anchor,
            isScrollInProgress = false,
            isAtBottom = true
        )
        assertEquals(true, anchor)
    }
}
