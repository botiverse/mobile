package build.raft.app

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockTopPageHeaderContractTest {
    @Test
    fun canonicalHeaderStartPaddingKeepsBackActionsNearTheLeftEdge() {
        assertEquals(18.dp, SlockTopPageHeaderContentStartPadding)
    }

    @Test
    fun canonicalFrameKeepsAllSurfacesAtTheRaftFiftySixDpHeadWithoutSafeArea() {
        assertEquals(
            SlockTopPageHeaderFrameMetrics(totalHeight = 56.dp, contentHeight = 54.5.dp),
            slockTopPageHeaderFrameMetrics(topSafeArea = 0.dp),
        )
    }

    @Test
    fun canonicalFrameAddsSafeAreaWithoutChangingHeaderGeometry() {
        assertEquals(
            SlockTopPageHeaderFrameMetrics(totalHeight = 80.dp, contentHeight = 78.5.dp),
            slockTopPageHeaderFrameMetrics(topSafeArea = 24.dp),
        )
    }
}
