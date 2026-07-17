package build.raft.app

import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class MembersListRowVisualContractTest {
    @Test
    fun membersListRowMatchesReactDensityAndTypography() {
        assertEquals(28.dp, MembersListAvatarSize)
        assertEquals(40.dp, MembersListRowMinHeight)
        assertEquals(SlockType.Button.fontFamily, MembersListNameTextStyle.fontFamily)
        assertEquals(FontWeight.Normal, MembersListNameTextStyle.fontWeight)
        assertEquals(14.sp, MembersListNameTextStyle.fontSize)
        assertEquals(20.sp, MembersListNameTextStyle.lineHeight)
    }
}
