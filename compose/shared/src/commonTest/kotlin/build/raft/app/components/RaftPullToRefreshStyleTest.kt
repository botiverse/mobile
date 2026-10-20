package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftPullToRefreshStyleTest {
    @Test
    fun switchOffUsesTheCustomImplementationOnEveryPlatform() {
        assertEquals(
            RaftPullToRefreshStyle.Custom,
            raftPullToRefreshStyle(nativeEnabled = false, platformNativeStyle = RaftPullToRefreshStyle.Material),
        )
        assertEquals(
            RaftPullToRefreshStyle.Custom,
            raftPullToRefreshStyle(nativeEnabled = false, platformNativeStyle = RaftPullToRefreshStyle.Cupertino),
        )
    }

    @Test
    fun switchOnUsesThePlatformNativeImplementation() {
        assertEquals(
            RaftPullToRefreshStyle.Material,
            raftPullToRefreshStyle(nativeEnabled = true, platformNativeStyle = RaftPullToRefreshStyle.Material),
        )
        assertEquals(
            RaftPullToRefreshStyle.Cupertino,
            raftPullToRefreshStyle(nativeEnabled = true, platformNativeStyle = RaftPullToRefreshStyle.Cupertino),
        )
    }

    @Test
    fun onlyTheCustomStyleAddsAListItem() {
        assertTrue(RaftPullToRefreshStyle.Custom.addsListItem)
        assertFalse(RaftPullToRefreshStyle.Material.addsListItem)
        assertFalse(RaftPullToRefreshStyle.Cupertino.addsListItem)
    }

    @Test
    fun customIndicatorShowsDragProgressBeforeRefreshing() {
        assertFalse(raftPullRefreshIndicatorVisible(pullProgress = 0f, isRefreshing = false))
        assertTrue(raftPullRefreshIndicatorVisible(pullProgress = 0.1f, isRefreshing = false))
        assertTrue(raftPullRefreshIndicatorVisible(pullProgress = 0f, isRefreshing = true))

        assertEquals(0f, raftPullRefreshIndicatorRotationDegrees(pullProgress = 0f, isRefreshing = false))
        assertEquals(180f, raftPullRefreshIndicatorRotationDegrees(pullProgress = 0.5f, isRefreshing = false))
        assertEquals(360f, raftPullRefreshIndicatorRotationDegrees(pullProgress = 2f, isRefreshing = false))
        assertEquals(0f, raftPullRefreshIndicatorRotationDegrees(pullProgress = 0.5f, isRefreshing = true))
    }

    @Test
    fun cupertinoSpokesAppearOneByOneUpToTheThreshold() {
        assertEquals(0, raftCupertinoRefreshVisibleSpokes(0f))
        assertEquals(1, raftCupertinoRefreshVisibleSpokes(0.05f))
        assertEquals(4, raftCupertinoRefreshVisibleSpokes(0.5f))
        assertEquals(RaftCupertinoSpokeCount, raftCupertinoRefreshVisibleSpokes(1f))
        assertEquals(RaftCupertinoSpokeCount, raftCupertinoRefreshVisibleSpokes(1.7f))
        assertEquals(0, raftCupertinoRefreshVisibleSpokes(-0.3f))
    }

    @Test
    fun cupertinoContentFollowsThePullAndNeverMovesUp() {
        assertEquals(0f, raftCupertinoRefreshContentOffsetFraction(0f))
        assertEquals(0.5f, raftCupertinoRefreshContentOffsetFraction(0.5f))
        assertEquals(1.6f, raftCupertinoRefreshContentOffsetFraction(1.6f))
        assertEquals(0f, raftCupertinoRefreshContentOffsetFraction(-1f))
    }
}
