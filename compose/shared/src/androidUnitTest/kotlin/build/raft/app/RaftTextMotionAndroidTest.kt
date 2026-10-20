package build.raft.app

import androidx.compose.ui.text.style.TextMotion
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftTextMotionAndroidTest {
    @Test
    fun androidPositionsGlyphsAtSubpixelOffsets() {
        assertEquals(TextMotion.Animated, raftPlatformTextMotion())
        assertEquals(TextMotion.Animated, RaftType.Body.textMotion)
    }
}
