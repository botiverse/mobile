package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class MembersListRowVisualContractTest {
    @Test
    fun membersListMetricsMatchTheWebMembersPage() {
        // Web pads the scroll container (`px-2`); the rows and headers are RaftSidebarItem /
        // RaftSidebarSectionHeader, which carry the row/section `px-2` (task #140).
        assertEquals(8.dp, MembersListContainerInset)
        // raft-ui Avatar `2xs` in the row's 19dp leading slot.
        assertEquals(19.dp, MembersListAvatarSize)
        // Machine group toggle: `mt-1.5 mb-0.5 px-2 gap-1.5`, 12px icons.
        assertEquals(8.dp, MembersListMachineLabelInset)
        assertEquals(6.dp, MembersListMachineLabelTop)
        assertEquals(2.dp, MembersListMachineLabelBottom)
        assertEquals(6.dp, MembersListMachineLabelGap)
        assertEquals(12.dp, MembersListMachineLabelIconSize)
        assertEquals(190.dp, MembersCreateAgentMenuMinWidth)
    }

    @Test
    fun membersListContentWidthCapKeepsTwentyFourDpMargins() {
        // Spec (task #12 §3): on DualPane windows Members content caps at 720dp
        // centered with >=24dp side margins. The enable condition is the window's
        // DualPane layout fact (not the slot width); the cap is measured against
        // the actual content slot. Compact windows pass through unchanged.
        assertEquals(720.dp, MembersListContentMaxWidth)
        assertEquals(24.dp, MembersListContentMinSideMargin)
        val compact = RaftAdaptivePaneLayout.Compact
        val dual = RaftAdaptivePaneLayout.DualPane
        // Compact windows: whatever the slot is, no cap, no new padding —
        // including a sub-768 window that happens to have a wide slot.
        assertEquals(360.dp, membersListContentMaxWidth(360.dp, compact))
        assertEquals(750.dp, membersListContentMaxWidth(750.dp, compact))
        assertEquals(800.dp, membersListContentMaxWidth(800.dp, compact))
        assertEquals(1200.dp, membersListContentMaxWidth(1200.dp, compact))
        // DualPane windows, wide slot: cap binds at 720dp (margins >=24dp).
        assertEquals(720.dp, membersListContentMaxWidth(1200.dp, dual))
        assertEquals(720.dp, membersListContentMaxWidth(768.dp, dual))
        // DualPane window where rail chrome narrows the slot (e.g. 840dp window
        // minus a 96dp rail = 744dp slot): margin clause binds so margins stay
        // exactly 24dp.
        assertEquals(696.dp, membersListContentMaxWidth(744.dp, dual))
        // Slot wide enough that even after reserving 48dp margins the cap binds.
        assertEquals(720.dp, membersListContentMaxWidth(800.dp, dual))
        assertEquals(720.dp, membersListContentMaxWidth(816.dp, dual))
    }
}
