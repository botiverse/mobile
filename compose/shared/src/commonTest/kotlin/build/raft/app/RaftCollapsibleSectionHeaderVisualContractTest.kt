package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftCollapsibleSectionHeaderVisualContractTest {
    @Test
    fun countMatchesReactMonoNormalTrackingAndBlackFortyPercent() {
        assertEquals(4.dp, RaftCollapsibleSectionContentGap)
        assertEquals(RaftMonoFamily, RaftCollapsibleSectionCountTextStyle.fontFamily)
        assertEquals(12.sp, RaftCollapsibleSectionCountTextStyle.fontSize)
        assertEquals(16.sp, RaftCollapsibleSectionCountTextStyle.lineHeight)
        assertEquals(0.sp, RaftCollapsibleSectionCountTextStyle.letterSpacing)
        assertEquals(RaftColor.TextBlack.copy(alpha = 0.4f), RaftCollapsibleSectionCountColor)
    }
}
