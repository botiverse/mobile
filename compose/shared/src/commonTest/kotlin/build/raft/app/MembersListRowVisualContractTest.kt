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
}
