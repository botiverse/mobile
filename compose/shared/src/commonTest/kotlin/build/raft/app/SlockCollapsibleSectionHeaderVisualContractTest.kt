package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockCollapsibleSectionHeaderVisualContractTest {
    @Test
    fun countMatchesReactMonoNormalTrackingAndBlackFortyPercent() {
        assertEquals(4.dp, SlockCollapsibleSectionContentGap)
        assertEquals(SlockMonoFamily, SlockCollapsibleSectionCountTextStyle.fontFamily)
        assertEquals(12.sp, SlockCollapsibleSectionCountTextStyle.fontSize)
        assertEquals(16.sp, SlockCollapsibleSectionCountTextStyle.lineHeight)
        assertEquals(0.sp, SlockCollapsibleSectionCountTextStyle.letterSpacing)
        assertEquals(SlockColor.TextBlack.copy(alpha = 0.4f), SlockCollapsibleSectionCountColor)
    }
}
