package build.raft.app

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockTopPageHeaderContractTest {
    @Test
    fun canonicalFrameKeepsAllSurfacesAtSixtyTwoDpWithoutSafeArea() {
        assertEquals(
            SlockTopPageHeaderFrameMetrics(totalHeight = 62.dp, contentHeight = 60.dp),
            slockTopPageHeaderFrameMetrics(topSafeArea = 0.dp),
        )
    }

    @Test
    fun canonicalFrameAddsSafeAreaWithoutChangingHeaderGeometry() {
        assertEquals(
            SlockTopPageHeaderFrameMetrics(totalHeight = 86.dp, contentHeight = 84.dp),
            slockTopPageHeaderFrameMetrics(topSafeArea = 24.dp),
        )
    }
}
