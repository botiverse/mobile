package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftTabBarStyleTest {
    @Test
    fun settingsSectionTitleMatchesReactSidebarToken() {
        assertEquals(10.sp, RaftVerticalTabSectionTextStyle.fontSize)
        assertEquals(12.sp, RaftVerticalTabSectionTextStyle.lineHeight)
        assertEquals(1.sp, RaftVerticalTabSectionTextStyle.letterSpacing)
        assertEquals(RaftType.TabLabel.fontFamily, RaftVerticalTabSectionTextStyle.fontFamily)
        assertEquals(RaftColor.TextBlack.copy(alpha = 0.4f), RaftVerticalTabSectionTextStyle.color)
        assertEquals(4.dp, RaftVerticalTabSectionBottomPadding)
    }
}
