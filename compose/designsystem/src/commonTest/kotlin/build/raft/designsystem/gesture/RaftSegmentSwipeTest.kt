package build.raft.designsystem.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftSegmentSwipeTest {
    private fun decide(
        dx: Float,
        dy: Float = 0f,
        isLtr: Boolean = true,
        back: Boolean = true,
        fwd: Boolean = true,
        host: Boolean = false,
        edge: Boolean = false,
    ) = raftSegmentedPagerDragDecision(
        dxPx = dx,
        dyPx = dy,
        touchSlopPx = 10f,
        isLtr = isLtr,
        hostHasBackSwipe = host,
        startsInLeadingEdge = edge,
        canGoBackward = back,
        canGoForward = fwd,
    )

    @Test
    fun waitsForTheSlop() {
        assertEquals(RaftPagingDragDecision.Undecided, decide(dx = -9f, dy = 5f))
    }

    @Test
    fun aHorizontalDragTowardASegmentIsClaimedEitherWay() {
        assertEquals(RaftPagingDragDecision.Claim, decide(dx = -12f))
        assertEquals(RaftPagingDragDecision.Claim, decide(dx = 12f))
    }

    @Test
    fun atTheFirstOrLastSegmentTheDragGoesToTheOuterPager() {
        assertEquals(RaftPagingDragDecision.Release, decide(dx = -12f, fwd = false))
        assertEquals(RaftPagingDragDecision.Release, decide(dx = 12f, back = false))
        // RTL: toward the end is rightward.
        assertEquals(RaftPagingDragDecision.Release, decide(dx = 12f, isLtr = false, fwd = false))
        assertEquals(RaftPagingDragDecision.Claim, decide(dx = 12f, isLtr = false, back = false))
    }

    @Test
    fun verticalDragsAreLeftToTheList() {
        assertEquals(RaftPagingDragDecision.Release, decide(dx = 2f, dy = 12f))
        assertEquals(RaftPagingDragDecision.Release, decide(dx = -11f, dy = 12f))
    }

    @Test
    fun onABackSwipeHostABackDragFromTheLeadingStripIsLeftToThePop() {
        assertEquals(RaftPagingDragDecision.Release, decide(dx = 30f, host = true, edge = true))
        // A forward drag from the strip still turns; a back drag elsewhere still turns.
        assertEquals(RaftPagingDragDecision.Claim, decide(dx = -30f, host = true, edge = true))
        assertEquals(RaftPagingDragDecision.Claim, decide(dx = 30f, host = true, edge = false))
        // Android: Back is an OS gesture, the strip turns segments as anywhere.
        assertEquals(RaftPagingDragDecision.Claim, decide(dx = 30f, host = false, edge = true))
    }

    private class Leftovers(edge: Boolean = false) {
        val driven = mutableListOf<Float>()
        val nested = RaftSegmentNestedDrag().apply { reset(edge) }

        fun leftover(dx: Float, back: Boolean = true, fwd: Boolean = true, isLtr: Boolean = true, host: Boolean = false): Float =
            nested.onLeftover(dx, isLtr, host, canGoBackward = back, canGoForward = fwd) { driven += it }
    }

    @Test
    fun aScrollerLeftoverTowardASegmentMovesTheContentWithTheFinger() {
        val t = Leftovers()
        assertEquals(-8f, t.leftover(-8f))
        assertEquals(-14f, t.leftover(-14f))
        // Forward scroll deltas: a leftward finger in LTR scrolls toward the next segment.
        assertEquals(listOf(8f, 14f), t.driven)
        assertTrue(t.nested.owned)
        assertTrue(t.nested.drives)
    }

    @Test
    fun theFirstLeftoverDecidesTheWholeGesture() {
        val t = Leftovers()
        t.leftover(-10f)
        // Back the other way in the same gesture: still the segments' (the outer pager stays put).
        assertEquals(30f, t.leftover(30f))
        assertEquals(listOf(10f, -30f), t.driven)
    }

    @Test
    fun rtlMirrorsTheLeftover() {
        val t = Leftovers()
        t.leftover(25f, isLtr = false)
        assertEquals(listOf(25f), t.driven)
    }

    @Test
    fun aNewGestureDecidesAfresh() {
        val t = Leftovers()
        t.leftover(-30f, fwd = false)
        t.nested.reset()
        assertEquals(30f, t.leftover(30f))
        assertEquals(listOf(-30f), t.driven)
    }

    @Test
    fun aGestureThePointerClaimedTakesLeftoversWithoutDrivingTwice() {
        val t = Leftovers()
        t.nested.claimedByPointer()
        assertEquals(-50f, t.leftover(-50f))
        assertEquals(emptyList(), t.driven)
        assertTrue(t.nested.owned)
        assertFalse(t.nested.drives)
    }

    @Test
    fun aSwipeOnTheSwitchPastTwiceTheSlopTurnsTheSegmentThatWay() {
        // Short and slow: still turns forward (or back), at least at the turn velocity.
        assertEquals(800f, raftSegmentSwitchSwipeVelocity(travelledPx = 40f, velocity = 100f, decidePx = 20f, turnVelocity = 800f))
        assertEquals(-800f, raftSegmentSwitchSwipeVelocity(travelledPx = -40f, velocity = 0f, decidePx = 20f, turnVelocity = 800f))
        // A faster flick the same way keeps its own velocity; one flung back the other way turns as swiped.
        assertEquals(2000f, raftSegmentSwitchSwipeVelocity(travelledPx = 40f, velocity = 2000f, decidePx = 20f, turnVelocity = 800f))
        assertEquals(800f, raftSegmentSwitchSwipeVelocity(travelledPx = 40f, velocity = -300f, decidePx = 20f, turnVelocity = 800f))
        // Under twice the slop it settles as it is.
        assertEquals(100f, raftSegmentSwitchSwipeVelocity(travelledPx = 10f, velocity = 100f, decidePx = 20f, turnVelocity = 800f))
    }

    @Test
    fun aGestureKeepsWhetherItStartedOnTheSwitchAndHowFarItsLeftoversDrove() {
        val t = Leftovers()
        t.nested.reset(startsInLeadingEdge = false, startsOnSwitch = true)
        t.leftover(-10f)
        t.leftover(-15f)
        assertTrue(t.nested.startsOnSwitch)
        assertEquals(25f, t.nested.travelled)
        t.nested.reset()
        assertFalse(t.nested.startsOnSwitch)
        assertEquals(0f, t.nested.travelled)
    }
}
