package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadMessagePressRippleTest {
    @Test
    fun pressWashDrawsOnlyWithOriginAndVisibleFraction() {
        assertTrue(shouldDrawThreadMessagePressWash(hasOrigin = true, washFraction = 1f))
        assertTrue(shouldDrawThreadMessagePressWash(hasOrigin = true, washFraction = 0.5f))
        assertFalse(shouldDrawThreadMessagePressWash(hasOrigin = false, washFraction = 1f))
        assertFalse(shouldDrawThreadMessagePressWash(hasOrigin = true, washFraction = 0f))
    }
}
