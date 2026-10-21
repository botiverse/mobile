package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftLargeTitleTest {
    @Test
    fun progressRunsFromOpenAtTheTopToCollapsedOnceTheBlockIsUnderTheBar() {
        assertEquals(0f, raftLargeTitleProgress(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0, blockHeightPx = 100f))
        assertEquals(0.4f, raftLargeTitleProgress(0, 40, 100f))
        assertEquals(1f, raftLargeTitleProgress(0, 100, 100f))
        // Past the block, or with no block measured, the header stays collapsed.
        assertEquals(1f, raftLargeTitleProgress(3, 0, 100f))
        assertEquals(1f, raftLargeTitleProgress(0, 0, 0f))
    }

    @Test
    fun aScrollThatStopsPartWaySettlesAtTheNearerEnd() {
        assertEquals(0, raftLargeTitleSnapTarget(firstVisibleItemIndex = 0, offsetPx = 30, blockHeightPx = 100))
        assertEquals(100, raftLargeTitleSnapTarget(0, 60, 100))
        assertNull(raftLargeTitleSnapTarget(0, 0, 100), "already open")
        assertNull(raftLargeTitleSnapTarget(0, 100, 100), "already collapsed")
        assertNull(raftLargeTitleSnapTarget(2, 30, 100), "past the block")
    }
}
