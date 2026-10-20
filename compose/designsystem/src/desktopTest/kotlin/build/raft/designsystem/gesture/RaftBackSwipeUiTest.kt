package build.raft.designsystem.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A [HorizontalPager] wired like `TabPager` on a host with a full-page back swipe. */
@OptIn(ExperimentalTestApi::class)
class RaftBackSwipeUiTest {
    private lateinit var pager: PagerState
    private lateinit var firstPageList: LazyListState
    private var movementConsumedAbove = false

    @Composable
    private fun BackSwipePager(regions: RaftBackSwipeRegions) {
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalRaftBackSwipeRegions provides regions) {
            pager = rememberPagerState(pageCount = { 3 })
            val backSwipe = rememberRaftBackSwipeScroll(pager, PagerDefaults.flingBehavior(pager))
            Box(
                Modifier
                    .size(300.dp, 400.dp)
                    // Records whether anything below consumed a movement (what the iOS Compose
                    // touch recogniser uses to claim a gesture against the native pop).
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.changes.any { it.isConsumed && it.position != it.previousPosition }) {
                                    movementConsumedAbove = true
                                }
                            }
                        }
                    }
                    .then(backSwipe.modifier)
                    .testTag("pager"),
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = backSwipe.userScrollEnabled,
                ) { page ->
                    if (page == 0) {
                        firstPageList = rememberLazyListState()
                        LazyColumn(Modifier.fillMaxSize(), state = firstPageList) {
                            items(60) { Text("row $it", Modifier.fillMaxWidth().height(40.dp)) }
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) { Text("page $page") }
                    }
                }
            }
        }
    }

    @Test
    fun forwardFlickOnTheFirstPageTurnsToTheNextPage() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { BackSwipePager(regions) }
        onNodeWithTag("pager").performTouchInput { swipeLeft(startX = right - 10f, endX = right - 120f, durationMillis = 80) }
        waitForIdle()
        assertEquals(1, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
        // Past the first page the pager keeps back drags: the host must not pop.
        assertTrue(regions.contentConsumesBackSwipeAt(150f, 200f))
    }

    @Test
    fun slowSubHalfDragSnapsBackToTheFirstPage() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { BackSwipePager(regions) }
        onNodeWithTag("pager").performTouchInput {
            down(Offset(250f, 200f))
            repeat(25) { moveBy(Offset(-4f, 0f), delayMillis = 60) }
            up()
        }
        waitForIdle()
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun backDirectionDragOnTheFirstPageIsLeftToTheHost() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { BackSwipePager(regions) }
        assertFalse(regions.contentConsumesBackSwipeAt(150f, 200f))
        movementConsumedAbove = false
        onNodeWithTag("pager").performTouchInput { swipeRight(startX = 20f, endX = 200f, durationMillis = 150) }
        waitForIdle()
        assertFalse(movementConsumedAbove, "a rightward drag at page 0 must stay unconsumed")
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun verticalDragGoesToTheInnerList() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { BackSwipePager(regions) }
        onNodeWithTag("pager").performTouchInput { swipeUp(startY = bottom - 20f, endY = top + 40f, durationMillis = 200) }
        waitForIdle()
        assertTrue(firstPageList.firstVisibleItemIndex > 0, "the list scrolled")
        assertEquals(0, pager.currentPage)
    }

    @Test
    fun regionsUnregisterOnDispose() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        var shown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalRaftBackSwipeRegions provides regions) {
                if (shown) {
                    Box(Modifier.size(100.dp).raftKeepsBackSwipe())
                    RaftBlockBackSwipe()
                }
            }
        }
        waitForIdle()
        assertEquals(2, regions.regionCount)
        assertTrue(regions.contentConsumesBackSwipeAt(500f, 500f), "an open overlay blocks the whole page")
        shown = false
        waitForIdle()
        assertEquals(0, regions.regionCount)
        assertFalse(regions.contentConsumesBackSwipeAt(10f, 10f))
    }

    @Test
    fun unplacedAndInactiveNodesAreNotReported() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        var active by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalRaftBackSwipeRegions provides regions) {
                // Measured, never placed.
                Layout(content = { Box(Modifier.size(100.dp).raftKeepsBackSwipe()) }) { measurables, constraints ->
                    measurables.forEach { it.measure(constraints) }
                    layout(100, 100) {}
                }
                CompositionLocalProvider(LocalRaftBackSwipeActive provides active) {
                    Box(Modifier.size(50.dp).raftKeepsBackSwipe())
                }
            }
        }
        waitForIdle()
        assertEquals(2, regions.regionCount)
        assertFalse(regions.contentConsumesBackSwipeAt(80f, 80f), "an unplaced node has no bounds")
        assertTrue(regions.contentConsumesBackSwipeAt(20f, 20f))
        active = false
        waitForIdle()
        assertFalse(regions.contentConsumesBackSwipeAt(20f, 20f), "inactive kept-alive content never reports")
    }
}
