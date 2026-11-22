package build.raft.designsystem

import build.raft.designsystem.components.RaftMessageListJumpSide
import build.raft.designsystem.components.raftMessageListJumpLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** raft-ui `messageListJump` controls: one row while the pills fit, else stacked, first pill nearest the edge. */
class RaftMessageListJumpTest {
    private val backToBottom = 120 to 32
    private val nextUnreadThread = 200 to 32

    @Test
    fun pillsThatDoNotFitStackTwelveApartWithTheFirstNearestTheEdge() {
        val bottom = raftMessageListJumpLayout(listOf(backToBottom, nextUnreadThread), maxWidth = 300, rowGap = 8, stackGap = 12, side = RaftMessageListJumpSide.Bottom)

        assertTrue(bottom.stacked)
        assertEquals(200, bottom.width)
        assertEquals(76, bottom.height)
        // Back to Bottom (first) lowest, centred; the unread-thread pill above it.
        assertEquals(listOf(40 to 44, 0 to 0), bottom.positions)

        val top = raftMessageListJumpLayout(listOf(backToBottom, nextUnreadThread), maxWidth = 300, rowGap = 8, stackGap = 12, side = RaftMessageListJumpSide.Top)
        assertEquals(listOf(40 to 0, 0 to 44), top.positions)
    }
}
