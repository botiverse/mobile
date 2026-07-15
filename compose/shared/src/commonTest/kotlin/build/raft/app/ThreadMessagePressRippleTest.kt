package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadMessagePressRippleTest {
    @Test
    fun rippleDrawsOnlyWithVisibleAnimatedOrigin() {
        assertTrue(shouldDrawThreadMessagePressRipple(hasOrigin = true, washAlpha = 0.1f, expansion = 1f))
        assertFalse(shouldDrawThreadMessagePressRipple(hasOrigin = false, washAlpha = 0.1f, expansion = 1f))
        assertFalse(shouldDrawThreadMessagePressRipple(hasOrigin = true, washAlpha = 0f, expansion = 1f))
        assertFalse(shouldDrawThreadMessagePressRipple(hasOrigin = true, washAlpha = 0.1f, expansion = 0f))
    }

    @Test
    fun rippleRadiusInterpolatesFromMinimumToFullCoverage() {
        assertEquals(24f, threadMessageRippleRadius(minRadius = 24f, coverRadius = 100f, expansion = 0f))
        assertEquals(62f, threadMessageRippleRadius(minRadius = 24f, coverRadius = 100f, expansion = 0.5f))
        assertEquals(100f, threadMessageRippleRadius(minRadius = 24f, coverRadius = 100f, expansion = 1f))
    }
}
