package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftTopPageHeaderContractTest {
    @Test
    fun canonicalHeaderStartPaddingKeepsBackActionsNearTheLeftEdge() {
        assertEquals(18.dp, RaftTopPageHeaderContentStartPadding)
    }

    @Test
    fun canonicalFrameKeepsAllSurfacesAtTheRaftFiftySixDpHeadWithoutSafeArea() {
        assertEquals(
            RaftTopPageHeaderFrameMetrics(totalHeight = 56.dp, contentHeight = 54.5.dp),
            raftTopPageHeaderFrameMetrics(topSafeArea = 0.dp),
        )
    }

    @Test
    fun canonicalFrameAddsSafeAreaWithoutChangingHeaderGeometry() {
        assertEquals(
            RaftTopPageHeaderFrameMetrics(totalHeight = 80.dp, contentHeight = 78.5.dp),
            raftTopPageHeaderFrameMetrics(topSafeArea = 24.dp),
        )
    }
}
