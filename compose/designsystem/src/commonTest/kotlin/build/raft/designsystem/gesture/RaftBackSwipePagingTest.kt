package build.raft.designsystem.gesture

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftBackSwipePagingTest {
    private val slop = 10f

    private fun decide(
        dx: Float,
        dy: Float = 0f,
        isLtr: Boolean = true,
        host: Boolean = true,
        edge: Boolean = false,
        canPageBackward: Boolean,
    ) = raftPagingDragDecision(dx, dy, slop, isLtr, host, edge, canPageBackward)

    @Test
    fun backDragOnTheFirstItemIsLeftToTheHost() {
        assertEquals(RaftPagingDragDecision.Release, decide(12f, canPageBackward = false))
        assertEquals(RaftPagingDragDecision.Release, decide(-12f, isLtr = false, canPageBackward = false))
    }

    @Test
    fun backDragPastTheFirstItemPagesBackFromTheBody() {
        assertEquals(RaftPagingDragDecision.Claim, decide(12f, canPageBackward = true))
        assertEquals(RaftPagingDragDecision.Claim, decide(-12f, isLtr = false, canPageBackward = true))
    }

    @Test
    fun backDragFromTheLeadingEdgeAlwaysPopsThePage() {
        assertEquals(RaftPagingDragDecision.Release, decide(12f, edge = true, canPageBackward = true))
        assertEquals(RaftPagingDragDecision.Release, decide(-12f, isLtr = false, edge = true, canPageBackward = true))
    }

    @Test
    fun forwardDragAlwaysPages() {
        assertEquals(RaftPagingDragDecision.Claim, decide(-12f, canPageBackward = false))
        assertEquals(RaftPagingDragDecision.Claim, decide(-12f, edge = true, canPageBackward = false))
        assertEquals(RaftPagingDragDecision.Claim, decide(12f, isLtr = false, canPageBackward = false))
    }

    @Test
    fun withoutAHostBackSwipeEveryHorizontalDragPages() {
        // Android: the first item keeps its rubber-band, the edge strip pages too.
        assertEquals(RaftPagingDragDecision.Claim, decide(12f, host = false, canPageBackward = false))
        assertEquals(RaftPagingDragDecision.Claim, decide(12f, host = false, edge = true, canPageBackward = true))
    }

    @Test
    fun belowSlopIsUndecidedAndVerticalIsReleased() {
        assertEquals(RaftPagingDragDecision.Undecided, decide(9f, 9f, canPageBackward = true))
        assertEquals(RaftPagingDragDecision.Release, decide(4f, 12f, canPageBackward = true))
        assertEquals(RaftPagingDragDecision.Release, decide(-11f, -14f, canPageBackward = true))
    }

    @Test
    fun leadingEdgeFollowsLayoutDirection() {
        assertTrue(raftStartsInLeadingEdge(0f, 300f, 40f, isLtr = true))
        assertTrue(raftStartsInLeadingEdge(40f, 300f, 40f, isLtr = true))
        assertFalse(raftStartsInLeadingEdge(41f, 300f, 40f, isLtr = true))
        assertTrue(raftStartsInLeadingEdge(270f, 300f, 40f, isLtr = false))
        assertFalse(raftStartsInLeadingEdge(20f, 300f, 40f, isLtr = false))
    }

    @Test
    fun trimmedRegionLeavesTheLeadingEdgeToTheHost() {
        val bounds = Rect(0f, 0f, 300f, 400f)
        assertEquals(Rect(40f, 0f, 300f, 400f), raftTrimLeadingEdge(bounds, 40f, isLtr = true))
        assertEquals(Rect(0f, 0f, 260f, 400f), raftTrimLeadingEdge(bounds, 40f, isLtr = false))
        assertEquals(bounds, raftTrimLeadingEdge(bounds, 0f, isLtr = true))
        assertTrue(raftTrimLeadingEdge(Rect(0f, 0f, 30f, 10f), 40f, isLtr = true).isEmpty)
    }
}
