package build.raft.app

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageBodyTypographyTest {
    @Test
    fun preferenceMapsAllThreeRealMessageBodySteps() {
        val small = raftMessageBodyTextStyle("sm")
        val medium = raftMessageBodyTextStyle("md")
        val large = raftMessageBodyTextStyle("lg")

        // Web appearanceStore: text-sm 14/20, text-base 16/24 (raft-design text-body), text-lg 18/28.
        assertEquals(14.sp, small.fontSize)
        assertEquals(20.sp, small.lineHeight)
        assertEquals(16.sp, medium.fontSize)
        assertEquals(24.sp, medium.lineHeight)
        assertEquals(18.sp, large.fontSize)
        assertEquals(28.sp, large.lineHeight)
    }

    @Test
    fun unknownPreferenceFailsSafeToMedium() {
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle("unexpected"))
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle(null))
    }
}
