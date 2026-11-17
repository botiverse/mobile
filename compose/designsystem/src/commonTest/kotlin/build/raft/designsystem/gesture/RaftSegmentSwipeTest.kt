package build.raft.designsystem.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private class Turns {
        val turns = mutableListOf<Boolean>()
        val nested = RaftSegmentNestedSwipe(decidePx = 20f)

        fun leftover(dx: Float, back: Boolean = true, fwd: Boolean = true, isLtr: Boolean = true): Float =
            nested.onLeftover(dx, isLtr, canGoBackward = back, canGoForward = fwd) { turns += it }
    }

    @Test
    fun aScrollerLeftoverTowardASubTabTurnsItOnceAndKeepsTheGesture() {
        val t = Turns()
        assertEquals(-8f, t.leftover(-8f))
        assertEquals(emptyList(), t.turns)
        assertEquals(-14f, t.leftover(-14f))
        assertEquals(listOf(true), t.turns)
        // The rest of the gesture (and its fling) stays with the sub-tabs: the outer pager never moves.
        assertEquals(-40f, t.leftover(-40f))
        assertEquals(listOf(true), t.turns)
        assertTrue(t.nested.owned)
    }

    @Test
    fun aScrollerLeftoverPastTheLastSubTabGoesToTheOuterPager() {
        val t = Turns()
        assertEquals(0f, t.leftover(-30f, fwd = false))
        assertEquals(0f, t.leftover(-30f, fwd = false))
        assertEquals(emptyList(), t.turns)
        assertFalse(t.nested.owned)
    }

    @Test
    fun aScrollerLeftoverTowardTheStartTurnsBackInRtlMirrored() {
        val t = Turns()
        t.leftover(25f, isLtr = false)
        assertEquals(listOf(true), t.turns)
        val back = Turns()
        back.leftover(25f)
        assertEquals(listOf(false), back.turns)
    }

    @Test
    fun aNewGestureDecidesAfresh() {
        val t = Turns()
        t.leftover(-30f, fwd = false)
        t.nested.reset()
        assertEquals(30f, t.leftover(30f))
        assertEquals(listOf(false), t.turns)
    }

    @Test
    fun aDragTheSubTabsAlreadyTurnedTakesNoSecondTurn() {
        val t = Turns()
        t.nested.claimedByDrag()
        assertEquals(-50f, t.leftover(-50f))
        assertEquals(emptyList(), t.turns)
        assertTrue(t.nested.owned)
    }
}
