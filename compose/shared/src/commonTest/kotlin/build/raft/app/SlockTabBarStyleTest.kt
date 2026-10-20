package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockTabBarStyleTest {
    @Test
    fun settingsSectionTitleMatchesReactSidebarToken() {
        assertEquals(10.sp, SlockVerticalTabSectionTextStyle.fontSize)
        assertEquals(12.sp, SlockVerticalTabSectionTextStyle.lineHeight)
        assertEquals(1.sp, SlockVerticalTabSectionTextStyle.letterSpacing)
        assertEquals(SlockType.TabLabel.fontFamily, SlockVerticalTabSectionTextStyle.fontFamily)
        assertEquals(SlockColor.TextBlack.copy(alpha = 0.4f), SlockVerticalTabSectionTextStyle.color)
        assertEquals(4.dp, SlockVerticalTabSectionBottomPadding)
    }
}
