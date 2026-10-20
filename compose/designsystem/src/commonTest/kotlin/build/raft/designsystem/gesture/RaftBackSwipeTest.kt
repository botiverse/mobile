package build.raft.designsystem.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftBackSwipeTest {
    private val slop = 10f

    @Test
    fun backDirectionDragAtStartIsReleasedToTheHost() {
        // LTR: back is rightward; RTL: back is leftward. Neither is consumed at the start.
        assertEquals(RaftBackSwipeYieldDecision.Release, raftBackSwipeYieldDecision(12f, 1f, slop, isLtr = true, canScrollForward = true))
        assertEquals(RaftBackSwipeYieldDecision.Release, raftBackSwipeYieldDecision(-12f, 1f, slop, isLtr = false, canScrollForward = true))
    }

    @Test
    fun forwardDragAtStartStillTurnsThePage() {
        assertEquals(RaftBackSwipeYieldDecision.ClaimForward, raftBackSwipeYieldDecision(-12f, 1f, slop, isLtr = true, canScrollForward = true))
        assertEquals(RaftBackSwipeYieldDecision.ClaimForward, raftBackSwipeYieldDecision(12f, 1f, slop, isLtr = false, canScrollForward = true))
        // Ties go to the horizontal drag.
        assertEquals(RaftBackSwipeYieldDecision.ClaimForward, raftBackSwipeYieldDecision(-12f, 12f, slop, isLtr = true, canScrollForward = true))
    }

    @Test
    fun forwardDragWithNothingToScrollIsNotConsumed() {
        assertEquals(RaftBackSwipeYieldDecision.Release, raftBackSwipeYieldDecision(-12f, 0f, slop, isLtr = true, canScrollForward = false))
    }

    @Test
    fun verticalDragGoesToTheContent() {
        assertEquals(RaftBackSwipeYieldDecision.Release, raftBackSwipeYieldDecision(3f, 12f, slop, isLtr = true, canScrollForward = true))
        assertEquals(RaftBackSwipeYieldDecision.Release, raftBackSwipeYieldDecision(-11f, 14f, slop, isLtr = true, canScrollForward = true))
    }

    @Test
    fun movementBelowSlopIsUndecided() {
        assertEquals(RaftBackSwipeYieldDecision.Undecided, raftBackSwipeYieldDecision(-9f, 2f, slop, isLtr = true, canScrollForward = true))
        assertEquals(RaftBackSwipeYieldDecision.Undecided, raftBackSwipeYieldDecision(0f, 0f, slop, isLtr = true, canScrollForward = true))
    }

    @Test
    fun forwardScrollDeltaFollowsLayoutDirection() {
        // Finger moving left in LTR scrolls forward; in RTL finger moving right does.
        assertEquals(20f, raftForwardScrollDelta(-20f, isLtr = true))
        assertEquals(20f, raftForwardScrollDelta(20f, isLtr = false))
        assertEquals(-5f, raftForwardScrollDelta(5f, isLtr = true))
    }

    @Test
    fun scrollerYieldsOnlyAtItsStartOnABackSwipeHost() {
        assertTrue(raftBackSwipeYieldsAtStart(hostHasBackSwipe = true, canScrollBackward = false))
        assertFalse(raftBackSwipeYieldsAtStart(hostHasBackSwipe = true, canScrollBackward = true))
        assertFalse(raftBackSwipeYieldsAtStart(hostHasBackSwipe = false, canScrollBackward = false))
    }

    @Test
    fun hitTestMatchesOnlyConsumingRegionsContainingThePoint() {
        val pager = Rect(0f, 100f, 400f, 800f)
        val chips = Rect(0f, 120f, 400f, 160f)
        fun regions(pagerConsumes: Boolean, chipsConsume: Boolean) =
            listOf(
                RaftBackSwipeRegionSnapshot(pager) { pagerConsumes },
                RaftBackSwipeRegionSnapshot(chips) { chipsConsume },
            )
        // Pager on its first page, chip row at its start: the host takes the back swipe.
        assertFalse(raftBackSwipeConsumedAt(Offset(200f, 140f), regions(false, false)))
        // Pager past its first page keeps the drag anywhere inside it.
        assertTrue(raftBackSwipeConsumedAt(Offset(200f, 500f), regions(true, false)))
        // A nested row away from its start keeps the drag only inside the row.
        assertTrue(raftBackSwipeConsumedAt(Offset(200f, 140f), regions(false, true)))
        assertFalse(raftBackSwipeConsumedAt(Offset(200f, 500f), regions(false, true)))
        // Outside every region (the page header) nothing consumes.
        assertFalse(raftBackSwipeConsumedAt(Offset(200f, 50f), regions(true, true)))
    }

    @Test
    fun emptyBoundsNeverMatch() {
        val offscreen = listOf(RaftBackSwipeRegionSnapshot(Rect.Zero) { true })
        assertFalse(raftBackSwipeConsumedAt(Offset.Zero, offscreen))
    }

    @Test
    fun registryConvertsHostPointsAndForgetsUnregisteredRegions() {
        val registry = RaftBackSwipeRegions()
        registry.density = 3f
        val region = registry.register { true }
        assertFalse(registry.contentConsumesBackSwipeAtPoint(50.0, 50.0), "unplaced region has empty bounds")
        region.bounds = Rect(0f, 300f, 1206f, 2400f)
        assertTrue(registry.contentConsumesBackSwipeAtPoint(50.0, 200.0)) // 150, 600 px
        assertFalse(registry.contentConsumesBackSwipeAtPoint(50.0, 50.0)) // 150, 150 px: header
        registry.unregister(region)
        assertFalse(registry.contentConsumesBackSwipeAtPoint(50.0, 200.0))
    }
}
