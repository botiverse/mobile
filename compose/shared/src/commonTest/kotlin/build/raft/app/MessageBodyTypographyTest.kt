package build.raft.app

import androidx.compose.ui.unit.sp
import build.raft.designsystem.theme.RaftScale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageBodyTypographyTest {
    @AfterTest
    fun restorePhoneScale() {
        RaftAppTypeScale.scale = RaftScale.Compact
    }

    @Test
    fun phoneStepsAreUnchanged() {
        RaftAppTypeScale.scale = RaftScale.Compact
        val small = raftMessageBodyTextStyle("sm")
        val medium = raftMessageBodyTextStyle("md")
        val large = raftMessageBodyTextStyle("lg")

        // The phone keeps exactly the sizes it had: 14/20, 16/24 (raft-design text-body), 18/28.
        assertEquals(14.sp, small.fontSize)
        assertEquals(20.sp, small.lineHeight)
        assertEquals(16.sp, medium.fontSize)
        assertEquals(24.sp, medium.lineHeight)
        assertEquals(18.sp, large.fontSize)
        assertEquals(28.sp, large.lineHeight)
    }

    @Test
    fun wideStepsSitTwoEitherSideOfTheWideBody() {
        // Web appearanceStore: sm / lg = text-body -/+ 2px, line -/+ 4px. On a wide window the body is
        // 14/22, so Small is smaller than Medium there too (it used to be a fixed 14/20).
        RaftAppTypeScale.scale = RaftScale.Wide
        val small = raftMessageBodyTextStyle("sm")
        val medium = raftMessageBodyTextStyle("md")
        val large = raftMessageBodyTextStyle("lg")

        assertEquals(12.sp, small.fontSize)
        assertEquals(18.sp, small.lineHeight)
        assertEquals(14.sp, medium.fontSize)
        assertEquals(22.sp, medium.lineHeight)
        assertEquals(16.sp, large.fontSize)
        assertEquals(26.sp, large.lineHeight)
    }

    @Test
    fun tiersKeepTheBodyFaceAndWeight() {
        for (scale in listOf(RaftScale.Compact, RaftScale.Wide)) {
            RaftAppTypeScale.scale = scale
            val medium = raftMessageBodyTextStyle("md")
            for (key in listOf("sm", "lg")) {
                val tier = raftMessageBodyTextStyle(key)
                assertEquals(medium.fontFamily, tier.fontFamily)
                assertEquals(medium.fontWeight, tier.fontWeight)
                assertEquals(medium.color, tier.color)
                assertEquals(medium.localeList, tier.localeList)
            }
        }
    }

    @Test
    fun unknownPreferenceFailsSafeToMedium() {
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle("unexpected"))
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle(null))
    }
}
