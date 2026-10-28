package build.raft.designsystem

import build.raft.designsystem.components.RaftPullDownSwipe
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `what the content scrolls itself does not count`() {
        val swipe = RaftPullDownSwipe()
        assertFalse(swipe.onUnconsumed(0f, 40f))
        assertFalse(swipe.onUnconsumed(0f, 40f))
        assertEquals(0f, swipe.travelledPx)
    }

    @Test
    fun `a drag back down or a new drag starts the swipe over`() {
        val swipe = RaftPullDownSwipe()
        swipe.onUnconsumed(-30f, 40f)
        assertFalse(swipe.onUnconsumed(4f, 40f))
        assertEquals(0f, swipe.travelledPx)
        swipe.onUnconsumed(-30f, 40f)
        swipe.reset()
        assertFalse(swipe.onUnconsumed(-30f, 40f))
    }
}
