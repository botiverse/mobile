package build.raft.app.compat.pager

import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftPageWindowMetricsTest {
    @Test
    fun applyRaftWindowMetricsFillsEveryWindowFactAPageReads() {
        val pageData = PageData(JSONObject())
        val insets = EdgeInsets(top = 42f, left = 0f, bottom = 24f, right = 0f)

        pageData.applyRaftWindowMetrics(
            RaftPageWindowMetrics(
                density = 3.375f,
                statusBarHeight = 42f,
                navigationBarHeight = 24f,
                safeAreaInsets = insets,
                width = 391f,
                height = 844f,
            )
        )

        assertEquals(3.375f, pageData.density)
        assertEquals(42f, pageData.statusBarHeight)
        assertEquals(24f, pageData.androidBottomBavBarHeight)
        assertEquals(insets, pageData.safeAreaInsets)
        assertEquals(391f, pageData.pageViewWidth)
        assertEquals(844f, pageData.pageViewHeight)
        assertEquals(391f, pageData.deviceWidth)
        assertEquals(844f, pageData.activityHeight)
    }
}
