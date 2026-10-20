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

        assertEquals(12.sp, small.fontSize)
        assertEquals(18.sp, small.lineHeight)
        assertEquals(14.sp, medium.fontSize)
        assertEquals(20.sp, medium.lineHeight)
        assertEquals(16.sp, large.fontSize)
        assertEquals(24.sp, large.lineHeight)
    }

    @Test
    fun unknownPreferenceFailsSafeToMedium() {
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle("unexpected"))
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle(null))
    }
}
