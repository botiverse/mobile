package build.raft.designsystem.gesture

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftSegmentSwipeTest {
    private fun decide(dx: Float, dy: Float = 0f, isLtr: Boolean = true, back: Boolean = true, fwd: Boolean = true) =
        raftSegmentSwipeDecision(dx, dy, decidePx = 20f, isLtr = isLtr, canGoBackward = back, canGoForward = fwd)

    @Test
    fun waitsUntilTheDragHasTravelled() {
        assertEquals(RaftSegmentSwipeDecision.Undecided, decide(dx = -19f, dy = 5f))
    }

    @Test
    fun aDragTowardTheStartTurnsToTheNextSubTabInLtr() {
        assertEquals(RaftSegmentSwipeDecision.Forward, decide(dx = -24f))
        assertEquals(RaftSegmentSwipeDecision.Backward, decide(dx = 24f))
    }

    @Test
    fun rtlMirrorsTheDirection() {
        assertEquals(RaftSegmentSwipeDecision.Backward, decide(dx = -24f, isLtr = false))
        assertEquals(RaftSegmentSwipeDecision.Forward, decide(dx = 24f, isLtr = false))
    }

    @Test
    fun atTheEdgeTheSwipeGoesToTheOuterPager() {
        assertEquals(RaftSegmentSwipeDecision.Release, decide(dx = -24f, fwd = false))
        assertEquals(RaftSegmentSwipeDecision.Release, decide(dx = 24f, back = false))
    }

    @Test
    fun verticalAndDiagonalDragsAreLeftToTheList() {
        assertEquals(RaftSegmentSwipeDecision.Release, decide(dx = 2f, dy = 20f))
        assertEquals(RaftSegmentSwipeDecision.Release, decide(dx = -30f, dy = 16f))
    }
}
