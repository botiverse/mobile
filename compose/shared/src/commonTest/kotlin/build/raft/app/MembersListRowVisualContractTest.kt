package build.raft.app

import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class MembersListRowVisualContractTest {
    @Test
    fun membersListRowMatchesReactMobileMasterDensityAndTypography() {
        // Web pads the scroll container (`px-2`) AND each row/section (`px-2`): 16px total edge
        // inset. Both halves are asserted so neither level can silently drop again (task #140).
        assertEquals(8.dp, MembersListContainerInset)
        assertEquals(8.dp, MembersListHorizontalInset)
        assertEquals(18.dp, MembersListAvatarSize)
        assertEquals(34.dp, MembersListRowMinHeight)
        assertEquals(4.dp, MembersListRowSpacing)
        assertEquals(6.dp, MembersListContentGap)
        assertEquals(0.7f, MembersListNameMaxWidthFraction)
        assertEquals(SlockType.Button.fontFamily, MembersListNameTextStyle.fontFamily)
        assertEquals(SlockType.Button.fontWeight, MembersListNameTextStyle.fontWeight)
        assertEquals(14.sp, MembersListNameTextStyle.fontSize)
        assertEquals(20.sp, MembersListNameTextStyle.lineHeight)
        assertEquals(12.sp, MembersListDescriptionTextStyle.fontSize)
        assertEquals(16.sp, MembersListDescriptionTextStyle.lineHeight)
        assertEquals(24.dp, MembersListSectionHeaderMinHeight)
        assertEquals(24.dp, MembersListSectionAddActionSize)
        assertEquals(14.dp, MembersListSectionAddActionIconSize)
        assertEquals(12.dp, MembersListMachineLabelInset)
        assertEquals(9.dp, MembersListMachineLabelIconSize)
        assertEquals(10.sp, MembersListMachineLabelTextStyle.fontSize)
        assertEquals(14.sp, MembersListMachineLabelTextStyle.lineHeight)
        assertEquals("jiachens-macbook-pro.local", membersListMachineLabel("Jiachens-MacBook-Pro.local"))
    }

    @Test
    fun membersListContentWidthCapKeepsTwentyFourDpMargins() {
        // Spec (task #12 §3): on DualPane windows Members content caps at 720dp
        // centered with >=24dp side margins. The enable condition is the window's
        // DualPane layout fact (not the slot width); the cap is measured against
        // the actual content slot. Compact windows pass through unchanged.
        assertEquals(720.dp, MembersListContentMaxWidth)
        assertEquals(24.dp, MembersListContentMinSideMargin)
        val compact = SlockAdaptivePaneLayout.Compact
        val dual = SlockAdaptivePaneLayout.DualPane
        // Compact windows: whatever the slot is, no cap, no new padding —
        // including a sub-840 window that happens to have a wide slot.
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
