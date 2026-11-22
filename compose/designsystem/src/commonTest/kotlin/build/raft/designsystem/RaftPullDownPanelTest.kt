package build.raft.designsystem

import build.raft.designsystem.components.RaftPullDownSwipe
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftPullDownPanelTest {
    @Test
    fun `a swipe up past the end of the content closes once it has gone 40 px`() {
        val swipe = RaftPullDownSwipe()
        assertFalse(swipe.onUnconsumed(-15f, 40f))
        assertFalse(swipe.onUnconsumed(-20f, 40f))
        assertTrue(swipe.onUnconsumed(-5f, 40f))
    }

    @Test
    fun `a drag that scrolled the content never closes the panel even past the threshold`() {
        val swipe = RaftPullDownSwipe()
        assertFalse(swipe.onUnconsumed(unconsumedY = 0f, thresholdPx = 40f, consumedY = -30f))
        assertFalse(swipe.onUnconsumed(unconsumedY = -60f, thresholdPx = 40f))
        swipe.reset()
        assertTrue(swipe.onUnconsumed(unconsumedY = -60f, thresholdPx = 40f))
    }
}
