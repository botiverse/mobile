package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A swipe-paged gallery (file preview image viewer): a [HorizontalPager] driven by
 * [raftBackSwipePaging], on a host with or without a full-page back swipe.
 */
@OptIn(ExperimentalTestApi::class)
class RaftBackSwipePagingUiTest {
    private lateinit var pager: PagerState
    private var movementConsumed = false

    /**
     * [contentConsumes] decides, per pointer x position, whether the page content (a zoomed image)
     * consumes the movement; null content never consumes.
     */
    @Composable
    private fun Gallery(
        regions: RaftBackSwipeRegions?,
        initialPage: Int = 0,
        contentConsumes: ((x: Float) -> Boolean)? = null,
    ) {
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalRaftBackSwipeRegions provides regions) {
            pager = rememberPagerState(initialPage = initialPage, pageCount = { 3 })
            Box(
                Modifier
                    .size(300.dp, 400.dp)
                    // What the iOS Compose touch recogniser uses to claim a gesture against the pop.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.changes.any { it.isConsumed && it.position != it.previousPosition }) {
                                    movementConsumed = true
                                }
                            }
                        }
                    }
                    .testTag("gallery"),
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().raftBackSwipePaging(pager, PagerDefaults.flingBehavior(pager)),
                    userScrollEnabled = false,
                ) {
                    Box(
                        Modifier.fillMaxSize().then(
                            if (contentConsumes == null) {
                                Modifier
                            } else {
                                Modifier.pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        while (true) {
                                            val change = awaitPointerEvent().changes.first()
                                            if (!change.pressed) break
                                            if (contentConsumes(change.position.x)) change.consume()
                                        }
                                    }
                                }
                            },
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun backSwipeOnTheFirstPageIsLeftToTheHost() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { Gallery(regions) }
        waitForIdle()
        assertFalse(regions.contentConsumesBackSwipeAt(150f, 200f), "the host may begin its pop")
        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 100f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertFalse(movementConsumed, "no rubber-band: the drag must stay unconsumed for the native pop")
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun pastTheFirstPageTheBodyPagesBack() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { Gallery(regions, initialPage = 1) }
        waitForIdle()
        assertTrue(regions.contentConsumesBackSwipeAt(150f, 200f), "body: the gallery keeps the drag")
        assertFalse(regions.contentConsumesBackSwipeAt(20f, 200f), "edge strip: the host pops")
        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 100f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertTrue(movementConsumed)
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun pastTheFirstPageAnEdgeBackSwipeIsLeftToTheHost() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { Gallery(regions, initialPage = 1) }
        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 10f, endX = 200f, durationMillis = 150) }
        waitForIdle()
        assertFalse(movementConsumed, "an edge back swipe is left to the host")
        assertEquals(1, pager.currentPage)
    }

    @Test
    fun theNextPageFollowsTheFingerThenSnaps() = runComposeUiTest {
        setContent { Gallery(RaftBackSwipeRegions()) }
        onNodeWithTag("gallery").performTouchInput {
            down(Offset(250f, 200f))
            repeat(10) { moveBy(Offset(-20f, 0f), delayMillis = 16) }
        }
        waitForIdle()
        // Mid-drag the pager has moved with the finger (the next page is partly in view).
        val midDrag = pager.currentPageOffsetFraction + pager.currentPage
        assertTrue(midDrag > 0.3f && midDrag < 1f, "pager position mid-drag: $midDrag")
        onNodeWithTag("gallery").performTouchInput { up() }
        waitForIdle()
        assertEquals(1, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction, "settled on a page")
    }

    @Test
    fun slowShortDragSnapsBack() = runComposeUiTest {
        setContent { Gallery(RaftBackSwipeRegions()) }
        onNodeWithTag("gallery").performTouchInput {
            down(Offset(250f, 200f))
            repeat(10) { moveBy(Offset(-5f, 0f), delayMillis = 60) }
            up()
        }
        waitForIdle()
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun verticalDragIsLeftToTheContent() = runComposeUiTest {
        setContent { Gallery(RaftBackSwipeRegions()) }
        onNodeWithTag("gallery").performTouchInput { swipeDown(startY = 50f, endY = 350f, durationMillis = 150) }
        waitForIdle()
        assertFalse(movementConsumed)
        assertEquals(0, pager.currentPage)
    }

    @Test
    fun contentThatConsumesTheDragKeepsThePagerStill() = runComposeUiTest {
        setContent { Gallery(RaftBackSwipeRegions(), contentConsumes = { true }) }
        onNodeWithTag("gallery").performTouchInput { swipeLeft(startX = 280f, endX = 20f, durationMillis = 150) }
        waitForIdle()
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun contentThatStopsConsumingMidGestureHandsTheDragToThePager() = runComposeUiTest {
        // A zoomed image pans until its edge is reached (here: until the finger passes x = 200),
        // then leaves the rest of the drag to the pager.
        setContent { Gallery(RaftBackSwipeRegions(), contentConsumes = { x -> x > 200f }) }
        onNodeWithTag("gallery").performTouchInput { swipeLeft(startX = 290f, endX = 10f, durationMillis = 150) }
        waitForIdle()
        assertEquals(1, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun withoutAHostBackSwipeTheFirstPageClaimsBackDrags() = runComposeUiTest {
        setContent { Gallery(regions = null) }
        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 100f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertTrue(movementConsumed, "Android: no host pop to leave it to")
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }
}
