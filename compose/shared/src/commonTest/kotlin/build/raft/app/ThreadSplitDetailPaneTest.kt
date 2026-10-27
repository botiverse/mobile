package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadSplitDetailPaneTest {
    @Test
    fun onlyAMeasuredAdaptiveDetailRightOfTheWindowEdgeIsASplitPane() {
        assertTrue(threadSplitDetailPane(adaptiveDetailPage = true, originMeasured = true, windowOriginX = 345f))
        assertFalse(threadSplitDetailPane(adaptiveDetailPage = true, originMeasured = true, windowOriginX = 0f))
        assertFalse(threadSplitDetailPane(adaptiveDetailPage = true, originMeasured = false, windowOriginX = 0f))
        assertFalse(threadSplitDetailPane(adaptiveDetailPage = false, originMeasured = true, windowOriginX = 345f))
    }

    @Test
    fun aSplitChannelOrDmHasNoBackButAReplyThreadKeepsIt() {
        assertFalse(threadHeaderShowsBack(showBack = false, surfaceKind = RaftThreadSurfaceKind.Channel))
        assertFalse(threadHeaderShowsBack(showBack = false, surfaceKind = RaftThreadSurfaceKind.DirectMessage))
        assertTrue(threadHeaderShowsBack(showBack = false, surfaceKind = RaftThreadSurfaceKind.ReplyThread))
        assertTrue(threadHeaderShowsBack(showBack = true, surfaceKind = RaftThreadSurfaceKind.Channel))
    }
}
