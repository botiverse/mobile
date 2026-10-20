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
                RaftBackSwipeRegionSnapshot({ pager }, { pagerConsumes }),
                RaftBackSwipeRegionSnapshot({ chips }, { chipsConsume }),
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
    fun emptyOrMissingBoundsNeverMatch() {
        assertFalse(raftBackSwipeConsumedAt(Offset.Zero, listOf(RaftBackSwipeRegionSnapshot({ Rect.Zero }, { true }))))
        assertFalse(raftBackSwipeConsumedAt(Offset.Zero, listOf(RaftBackSwipeRegionSnapshot({ null }, { true }))))
    }

    @Test
    fun wholePageRegionsMatchEverywhere() {
        val overlay = listOf(RaftBackSwipeRegionSnapshot({ null }, { true }, wholePage = true))
        assertTrue(raftBackSwipeConsumedAt(Offset(-5f, 9000f), overlay))
    }

    @Test
    fun overSlopAndFlingVelocity() {
        assertEquals(-4f, raftOverSlopPx(-14f, slop))
        assertEquals(3f, raftOverSlopPx(13f, slop))
        // Finger velocity left in LTR is forward, clamped to the platform maximum.
        assertEquals(800f, raftForwardFlingVelocity(-800f, isLtr = true, maximumFlingVelocity = 8000f))
        assertEquals(8000f, raftForwardFlingVelocity(-20000f, isLtr = true, maximumFlingVelocity = 8000f))
        assertEquals(-8000f, raftForwardFlingVelocity(-20000f, isLtr = false, maximumFlingVelocity = 8000f))
    }

    @Test
    fun registryConvertsHostPointsAndForgetsUnregisteredRegions() {
        val registry = RaftBackSwipeRegions()
        registry.density = 3f
        var bounds: Rect? = null
        val region = registry.register(bounds = { bounds }, consumesBackSwipe = { true })
        assertFalse(registry.contentConsumesBackSwipeAtPoint(50.0, 50.0), "unplaced region has no bounds")
        bounds = Rect(0f, 300f, 1206f, 2400f)
        assertTrue(registry.contentConsumesBackSwipeAtPoint(50.0, 200.0)) // 150, 600 px
        assertFalse(registry.contentConsumesBackSwipeAtPoint(50.0, 50.0)) // 150, 150 px: header
        registry.unregister(region)
        assertFalse(registry.contentConsumesBackSwipeAtPoint(50.0, 200.0))
        val overlay = registry.registerWholePage()
        assertTrue(registry.contentConsumesBackSwipeAtPoint(1.0, 1.0))
        registry.unregister(overlay)
        assertEquals(0, registry.regionCount)
    }
}
