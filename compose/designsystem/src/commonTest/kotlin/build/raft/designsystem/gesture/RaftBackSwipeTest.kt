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
    fun edgePopModeLeavesABackDragFromTheLeadingStripToTheHostEvenPastTheStart() {
        // Past the first tab (can scroll back): from the strip a back drag pops, from the body it pages back.
        assertEquals(
            RaftBackSwipeYieldDecision.Release,
            raftEdgePopDragDecision(12f, 1f, slop, isLtr = true, startsInLeadingEdge = true, canScrollBackward = true, canScrollForward = true),
        )
        assertEquals(
            RaftBackSwipeYieldDecision.ClaimForward,
            raftEdgePopDragDecision(12f, 1f, slop, isLtr = true, startsInLeadingEdge = false, canScrollBackward = true, canScrollForward = true),
        )
        // RTL: back is leftward, the strip is the right edge.
        assertEquals(
            RaftBackSwipeYieldDecision.Release,
            raftEdgePopDragDecision(-12f, 1f, slop, isLtr = false, startsInLeadingEdge = true, canScrollBackward = true, canScrollForward = true),
        )
        // Nothing before: released wherever it starts (the first tab).
        assertEquals(
            RaftBackSwipeYieldDecision.Release,
            raftEdgePopDragDecision(12f, 1f, slop, isLtr = true, startsInLeadingEdge = false, canScrollBackward = false, canScrollForward = true),
        )
    }

    @Test
    fun verticalDragGoesToTheContent() {
        assertEquals(RaftBackSwipeYieldDecision.Release, raftBackSwipeYieldDecision(3f, 12f, slop, isLtr = true, canScrollForward = true))
        assertEquals(RaftBackSwipeYieldDecision.Release, raftBackSwipeYieldDecision(-11f, 14f, slop, isLtr = true, canScrollForward = true))
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

    @Test
    fun onlyTheRailKeepsItsTouchesFromTheEdgeBackGesture() {
        val registry = RaftBackSwipeRegions()
        registry.density = 3f
        // The app rail on the leading edge (72 pt), and a horizontal strip that keeps back drags.
        val rail = registry.register(bounds = { Rect(0f, 0f, 216f, 2400f) }, consumesBackSwipe = { true }, keepsTouches = true)
        registry.register(bounds = { Rect(216f, 300f, 1206f, 600f) }, consumesBackSwipe = { true })
        assertTrue(registry.keepsEdgeTouchesAtPoint(10.0, 400.0), "a touch on the rail")
        assertFalse(registry.keepsEdgeTouchesAtPoint(100.0, 150.0), "the strip keeps back drags, not touches")
        assertFalse(registry.keepsEdgeTouchesAtPoint(100.0, 700.0), "beside the rail the edge gesture still gets it")
        registry.unregister(rail)
        assertFalse(registry.keepsEdgeTouchesAtPoint(10.0, 400.0), "with no rail (a pushed page) the edge is the gesture's")
        registry.registerWholePage()
        assertFalse(registry.keepsEdgeTouchesAtPoint(10.0, 400.0), "an open overlay keeps back drags, not touches")
    }
}
