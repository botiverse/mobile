package build.raft.app.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.Density
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
    fun rubberBandFollowsTheFingerWithResistanceAndInverts() {
        assertEquals(0f, raftRubberBandOffset(0f, viewport, 0.55f))
        val small = raftRubberBandOffset(10f, viewport, 0.55f)
        assertTrue(small in 5.4f..5.5f, "slope at rest is the coefficient, got $small")
        val far = raftRubberBandOffset(2_000f, viewport, 0.55f)
        assertTrue(far < viewport && far > 400f, "approaches the viewport height, got $far")
        assertEquals(300f, raftRubberBandOffset(raftRubberBandPull(300f, viewport, 0.55f), viewport, 0.55f), 0.01f)
    }

    @Test
    fun pullFollowsTheFingerAndUpwardDragGivesItBack() = runTest {
        val c = controller(RaftPullToRefreshStyle.Material)
        assertEquals(30f, c.onPullDown(30f))
        assertEquals(RaftPullToRefreshPhase.Pulling, c.phase)
        val offset = c.contentOffset
        assertEquals(raftRubberBandOffset(30f, viewport, 0.5f), offset, 0.001f)
        assertEquals(offset, c.indicatorOffset)

        // Dragging back up is consumed by the pull before the list scrolls, down to rest.
        assertEquals(-30f, c.onPullUp(-50f))
        assertEquals(0f, c.contentOffset)
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
        // At rest an upward drag belongs to the list.
        assertEquals(0f, c.onPullUp(-10f))
    }

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
    fun materialRefreshMovesBothLayersTogetherAndSpringsBackTogether() = runTest {
        val c = controller(RaftPullToRefreshStyle.Material)
        c.onPullDown(pullFor(100f))
        // While pulling the indicator comes down exactly as far as the content.
        assertEquals(c.contentOffset, c.indicatorOffset)
        c.onRelease(0f)
        c.onStoreRefreshingChanged(true)
        var frames = 0
        while (c.isAnimating && frames < 200) {
            advanceTimeBy(16)
            assertEquals(c.contentOffset, c.indicatorOffset, 0.001f, "layers stay together, frame $frames")
            frames++
        }
        assertEquals(60f, c.contentOffset, 0.5f)
        assertEquals(1f, c.indicatorFraction, 0.01f)
        assertTrue(c.showsRefreshing)

        // Pulls during the refresh go to the platform overscroll, not to the indicator.
        assertEquals(0f, c.onPullDown(40f))

        c.onStoreRefreshingChanged(false)
        advanceTimeBy(48)
        assertEquals(c.contentOffset, c.indicatorOffset, 0.001f)
        assertTrue(c.contentOffset in 0.1f..59.9f, "springing back, offset=${c.contentOffset}")
        settleAnimations()
        assertEquals(0f, c.contentOffset)
        assertEquals(0f, c.indicatorOffset)
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
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
    fun aRefreshThatEndsBeforeTheSpringSettlesStraightBack() = runTest {
        val c = controller(RaftPullToRefreshStyle.Cupertino)
        c.onPullDown(pullFor(100f))
        c.onRelease(0f)
        c.onStoreRefreshingChanged(true)
        runCurrent()
        c.onStoreRefreshingChanged(false)
        assertEquals(RaftPullToRefreshPhase.Settling, c.phase)
        settleAnimations()
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
        assertEquals(0f, c.contentOffset)
    }

    @Test
    fun grabbingContentThatSpringsBackContinuesFromWhereItIs() = runTest {
        val c = controller(RaftPullToRefreshStyle.Cupertino)
        c.onPullDown(pullFor(70f))
        c.onRelease(0f)
        advanceTimeBy(48)
        val midSpring = c.contentOffset
        assertTrue(midSpring in 1f..69f, "mid spring, offset=$midSpring")

        c.onPullDown(1f)
        assertEquals(RaftPullToRefreshPhase.Pulling, c.phase)
        assertEquals(raftRubberBandOffset(pullFor(midSpring) + 1f, viewport, 0.5f), c.contentOffset, 0.01f)
        // The cancelled spring no longer moves it.
        val held = c.contentOffset
        advanceTimeBy(1_000)
        assertEquals(held, c.contentOffset)
    }

    @Test
    fun scrollingUpWhileRefreshingClosesTheCupertinoGapFirst() = runTest {
        val c = controller(RaftPullToRefreshStyle.Cupertino)
        c.onPullDown(pullFor(100f))
        c.onRelease(0f)
        c.onStoreRefreshingChanged(true)
        advanceTimeBy(5_000)
        assertEquals(-20f, c.onPullUp(-20f), 0.5f)
        assertEquals(40f, c.contentOffset, 0.5f)
        assertEquals(-40f, c.onPullUp(-100f), 0.5f)
        assertEquals(0f, c.contentOffset)
        // Still refreshing; the next upward drag scrolls the list.
        assertEquals(RaftPullToRefreshPhase.Refreshing, c.phase)
        assertEquals(0f, c.onPullUp(-10f))
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
    fun releaseVelocityGoesToTheListOnlyWhenFlingingUp() = runTest {
        val c = controller(RaftPullToRefreshStyle.Material)
        c.onPullDown(20f)
        assertEquals(0f, c.onRelease(-900f))
        c.onPullDown(20f)
        assertEquals(500f, c.onRelease(500f))
        // Not pulling: flings are untouched.
        settleAnimations()
        assertEquals(0f, c.onRelease(800f))
    }

    @Test
    fun disablingMidPullSpringsBackAndIgnoresDrags() = runTest {
        var refreshes = 0
        val c = controller(RaftPullToRefreshStyle.Material) { refreshes++ }
        c.onPullDown(pullFor(120f))
        c.onEnabledChanged(false)
        assertEquals(0f, c.onPullDown(10f))
        settleAnimations()
        assertEquals(0f, c.contentOffset)
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
        assertEquals(0, refreshes)
    }

    @Test
    fun pullProgressMapsTheContentOffsetOntoTheThreshold() {
        assertEquals(0f, raftPullProgress(0f, 100f))
        assertEquals(0.5f, raftPullProgress(50f, 100f))
        assertEquals(1f, raftPullProgress(140f, 100f))
        assertEquals(0f, raftPullProgress(-5f, 100f))
        assertEquals(0f, raftPullProgress(50f, 0f))
    }

    @Test
    fun cupertinoGeometryMatchesUiRefreshControlMetrics() {
        val g = raftPullToRefreshGeometry(RaftPullToRefreshStyle.Cupertino, Density(3f))
        assertEquals(300f, g.triggerPx)
        assertEquals(180f, g.indicatorHoldPx)
        assertEquals(0.55f, g.coefficient)
        assertTrue(g.triggerPx > g.indicatorHoldPx, "the indicator fills in after the gap passes its rest height")
    }

    @Test
    fun cupertinoPullArmsAtTheThresholdAndStaysArmedWhenEasedBack() = runTest {
        var refreshes = 0
        val c = controller(RaftPullToRefreshStyle.Cupertino) { refreshes++ }
        c.onPullDown(pullFor(60f))
        assertFalse(c.armed)
        assertEquals(0.75f, c.pullProgress, 0.01f)

        c.onPullDown(pullFor(81f) - pullFor(60f))
        assertTrue(c.armed, "crossing the threshold commits while the finger is down")
        assertEquals(1f, c.pullProgress)
        assertEquals(0, refreshes)

        // Easing back below the threshold, even to rest, keeps the commit.
        c.onPullUp(-pullFor(81f) + pullFor(30f))
        assertTrue(c.armed)
        c.onPullUp(-1_000f)
        assertEquals(0f, c.contentOffset)
        assertEquals(RaftPullToRefreshPhase.Pulling, c.phase)
        assertTrue(c.armed)

        c.onRelease(0f)
        assertEquals(1, refreshes)
        assertFalse(c.armed)
        assertEquals(RaftPullToRefreshPhase.AwaitingRefresh, c.phase)
        c.onStoreRefreshingChanged(true)
        advanceTimeBy(5_000)
        assertEquals(60f, c.contentOffset, 0.5f)
    }

    @Test
    fun aShortCupertinoPullNeverArms() = runTest {
        val c = controller(RaftPullToRefreshStyle.Cupertino)
        c.onPullDown(pullFor(79f))
        assertFalse(c.armed)
        c.onRelease(0f)
        assertFalse(c.armed)
        settleAnimations()
        assertEquals(RaftPullToRefreshPhase.Idle, c.phase)
    }

    @Test
    fun materialKeepsReleaseTimeThresholdWithoutArming() = runTest {
        var refreshes = 0
        val c = controller(RaftPullToRefreshStyle.Material) { refreshes++ }
        c.onPullDown(pullFor(100f))
        assertFalse(c.armed)
        c.onPullUp(-pullFor(100f) + pullFor(40f))
        c.onRelease(0f)
        assertEquals(0, refreshes)
    }
}
