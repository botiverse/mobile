package build.raft.app

import com.tencent.kuikly.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageBodyTypographyTest {
    @Test
    fun preferenceMapsAllThreeRealMessageBodySteps() {
        val small = slockMessageBodyTextStyle("sm")
        val medium = slockMessageBodyTextStyle("md")
        val large = slockMessageBodyTextStyle("lg")

        assertEquals(12.sp, small.fontSize)
        assertEquals(18.sp, small.lineHeight)
        assertEquals(14.sp, medium.fontSize)
        assertEquals(20.sp, medium.lineHeight)
        assertEquals(16.sp, large.fontSize)
        assertEquals(22.sp, large.lineHeight)
    }

    @Test
    fun unknownPreferenceFailsSafeToMedium() {
        assertEquals(slockMessageBodyTextStyle("md"), slockMessageBodyTextStyle("unexpected"))
        assertEquals(slockMessageBodyTextStyle("md"), slockMessageBodyTextStyle(null))
    }
}
