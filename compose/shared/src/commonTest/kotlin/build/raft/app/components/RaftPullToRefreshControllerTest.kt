package build.raft.app.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RaftPullToRefreshControllerTest {
    private val geometry = RaftPullToRefreshGeometry(triggerPx = 80f, indicatorHoldPx = 60f, coefficient = 0.5f)
    private val viewport = 800f

    /** 60 fps frames on the test scheduler's virtual clock, so springs run in virtual time. */
    private class VirtualFrameClock(private val scope: TestScope) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16)
            return onFrame(scope.testScheduler.currentTime * 1_000_000L)
        }
    }

    private fun TestScope.controller(
        style: RaftPullToRefreshStyle,
        onRefresh: () -> Unit = {},
    ): RaftPullToRefreshController {
        val animationScope: CoroutineScope = backgroundScope + VirtualFrameClock(this)
        return RaftPullToRefreshController(style, animationScope, geometry).also {
            it.viewportPx = viewport
            it.onRefresh = onRefresh
        }
    }

    /**
     * Springs run in [TestScope.backgroundScope], which `advanceUntilIdle` does not wait for; a
     * spring settles well within this much virtual time.
     */
    private fun TestScope.settleAnimations() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    /** Finger travel that pulls the content to [offset] px. */
    private fun pullFor(offset: Float) = raftRubberBandPull(offset, viewport, geometry.coefficient)

    @Test
    fun releaseShortOfTheThresholdSpringsBackWithoutRefreshing() = runTest {
        var refreshes = 0
        val c = controller(RaftPullToRefreshStyle.Cupertino) { refreshes++ }
        c.onPullDown(pullFor(50f))
        assertEquals(50f, c.contentOffset, 0.01f)

        assertEquals(0f, c.onRelease(0f))
        assertEquals(RaftPullToRefreshPhase.Settling, c.phase)
        advanceTimeBy(100)
        assertTrue(c.contentOffset in 0f..50f, "springing back, offset=${c.contentOffset}")
        settleAnimations()
        assertEquals(0f, c.contentOffset)
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
        assertEquals(0, refreshes)
    }

    @Test
    fun cupertinoRefreshHoldsTheGapWhileTheStoreRefreshesThenSpringsBack() = runTest {
        var refreshes = 0
        val c = controller(RaftPullToRefreshStyle.Cupertino) { refreshes++ }
        c.onPullDown(pullFor(120f))
        assertEquals(120f, c.contentOffset, 0.01f)

        c.onRelease(0f)
        assertEquals(1, refreshes)
        assertEquals(RaftPullToRefreshPhase.AwaitingRefresh, c.phase)
        assertTrue(c.showsRefreshing)
        c.onStoreRefreshingChanged(true)
        assertEquals(RaftPullToRefreshPhase.Refreshing, c.phase)

        // The content settles onto the hold band and stays there for as long as the store refreshes.
        advanceTimeBy(5_000)
        assertEquals(60f, c.contentOffset, 0.5f)
        assertEquals(60f, c.indicatorOffset, 0.5f)
        assertEquals(RaftPullToRefreshPhase.Refreshing, c.phase)

        c.onStoreRefreshingChanged(false)
        assertEquals(RaftPullToRefreshPhase.Settling, c.phase)
        settleAnimations()
        assertEquals(0f, c.contentOffset)
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
        assertEquals(1, refreshes)
    }

    @Test
    fun aRefreshTheStoreNeverReportsResolvesWhenTheSpringEnds() = runTest {
        var refreshes = 0
        val c = controller(RaftPullToRefreshStyle.Cupertino) { refreshes++ }
        c.onPullDown(pullFor(100f))
        c.onRelease(0f)
        assertEquals(RaftPullToRefreshPhase.AwaitingRefresh, c.phase)
        settleAnimations()
        assertEquals(1, refreshes)
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
        assertEquals(0f, c.contentOffset)
    }

    @Test
    fun anUnpulledStoreRefreshNeverMovesTheContent() = runTest {
        val cupertino = controller(RaftPullToRefreshStyle.Cupertino)
        cupertino.onStoreRefreshingChanged(true)
        advanceTimeBy(1_000)
        assertEquals(RaftPullToRefreshPhase.Refreshing, cupertino.phase)
        assertEquals(0f, cupertino.contentOffset)

        val material = controller(RaftPullToRefreshStyle.Material)
        material.onStoreRefreshingChanged(true)
        advanceTimeBy(1_000)
        assertEquals(0f, material.contentOffset)
        assertEquals(1f, material.indicatorFraction, 0.01f)
    }

    @Test
    fun aWheelOrTrackpadScrollIsAPointerAndAFingerIsNot() {
        assertTrue(raftPullToRefreshPointerScrolling(false, PointerEventType.Scroll, PointerType.Mouse))
        assertTrue(raftPullToRefreshPointerScrolling(false, PointerEventType.Scroll, PointerType.Unknown))
        assertTrue(raftPullToRefreshPointerScrolling(false, PointerEventType.Move, PointerType.Mouse))
        assertFalse(raftPullToRefreshPointerScrolling(true, PointerEventType.Press, PointerType.Touch), "a finger goes down")
        assertFalse(raftPullToRefreshPointerScrolling(true, PointerEventType.Press, PointerType.Stylus))
        // A finger moving or lifting keeps what its press decided; so does anything else.
        assertTrue(raftPullToRefreshPointerScrolling(true, PointerEventType.Move, PointerType.Touch))
        assertFalse(raftPullToRefreshPointerScrolling(false, PointerEventType.Release, PointerType.Touch))
    }
}
