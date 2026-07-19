package build.raft.app

import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class MembersListRowVisualContractTest {
    @Test
    fun membersListRowMatchesReactMobileMasterDensityAndTypography() {
        assertEquals(8.dp, MembersListHorizontalInset)
        assertEquals(18.dp, MembersListAvatarSize)
        assertEquals(34.dp, MembersListRowMinHeight)
        assertEquals(4.dp, MembersListRowSpacing)
        assertEquals(6.dp, MembersListContentGap)
        assertEquals(0.7f, MembersListNameMaxWidthFraction)
        assertEquals(SlockType.Button.fontFamily, MembersListNameTextStyle.fontFamily)
        assertEquals(FontWeight.Normal, MembersListNameTextStyle.fontWeight)
        assertEquals(14.sp, MembersListNameTextStyle.fontSize)
        assertEquals(20.sp, MembersListNameTextStyle.lineHeight)
        assertEquals(12.sp, MembersListDescriptionTextStyle.fontSize)
        assertEquals(16.sp, MembersListDescriptionTextStyle.lineHeight)
        assertEquals(24.dp, MembersListSectionHeaderMinHeight)
        assertEquals(24.dp, MembersListSectionActionSize)
        assertEquals(14.dp, MembersListSectionActionIconSize)
        assertEquals(12.dp, MembersListMachineLabelInset)
        assertEquals(9.dp, MembersListMachineLabelIconSize)
        assertEquals(10.sp, MembersListMachineLabelTextStyle.fontSize)
        assertEquals(14.sp, MembersListMachineLabelTextStyle.lineHeight)
        assertEquals("jiachens-macbook-pro.local", membersListMachineLabel("Jiachens-MacBook-Pro.local"))
    }
}
