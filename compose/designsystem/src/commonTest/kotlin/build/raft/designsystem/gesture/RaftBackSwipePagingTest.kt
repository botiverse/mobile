package build.raft.designsystem.gesture

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
