package build.raft.designsystem

import build.raft.designsystem.components.RaftDrawerSettle
import build.raft.designsystem.components.raftDrawerSettle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a released [build.raft.designsystem.components.RaftDrawer] with snap points goes. */
class RaftDrawerSettleTest {
    private val default = 640f
    private val full = 780f
    private val fling = 400f

    private fun settle(extent: Float, velocityDown: Float = 0f) = raftDrawerSettle(extent, velocityDown, default, full, fling)

    @Test
    fun aSlowReleaseGoesToTheNearestSnapPoint() {
        assertEquals(RaftDrawerSettle.Full, settle(750f))
        assertEquals(RaftDrawerSettle.Default, settle(700f))
        assertEquals(RaftDrawerSettle.Default, settle(330f))
        // Dragged more than half of the default height down: it closes.
        assertEquals(RaftDrawerSettle.Dismiss, settle(310f))
    }

    @Test
    fun aFlingGoesToTheNextSnapPointInItsDirection() {
        assertEquals(RaftDrawerSettle.Full, settle(650f, velocityDown = -1_000f))
        assertEquals(RaftDrawerSettle.Default, settle(770f, velocityDown = 1_000f))
        assertEquals(RaftDrawerSettle.Dismiss, settle(630f, velocityDown = 1_000f))
        // From the default height a fling down closes; from full it stops at the default height first.
        assertEquals(RaftDrawerSettle.Dismiss, settle(default, velocityDown = 1_000f))
        assertEquals(RaftDrawerSettle.Default, settle(full, velocityDown = 1_000f))
        assertEquals(RaftDrawerSettle.Full, settle(full, velocityDown = -1_000f))
    }
}
