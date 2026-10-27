package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin (#proj-mobile:34aed863): scrolling the Activity list sometimes opened a row's swipe or turned
 * the filter page. A touch that lands while the list still flings must only stop it, as on iOS and
 * RecyclerView: no row swipe and no page turn from that gesture. Wired like the Activity list: rows
 * that swipe left (claim past 2x slop with |dx| > 2|dy|) in a LazyColumn on a TabPager-like pager.
 */
@OptIn(ExperimentalTestApi::class)
class RaftScrollHoldUiTest {
    private lateinit var pager: PagerState
    private lateinit var list: LazyListState
    private var rowClaimed = false

    @Composable
    private fun PagerWithSwipeList(hold: Boolean) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            pager = rememberPagerState(pageCount = { 3 })
            val nested = rememberRaftNestedHorizontalDragHost()
            val backSwipe = rememberRaftBackSwipeScroll(pager, PagerDefaults.flingBehavior(pager), nestedDrags = nested)
            Box(Modifier.size(300.dp, 400.dp).then(nested.modifier).then(backSwipe.modifier).testTag("pager")) {
                nested.ProvideScrollerConfiguration {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = backSwipe.userScrollEnabled) { page ->
                        nested.ProvideContent {
                            val state = rememberLazyListState()
                            if (page == 0) list = state
                            val scrolling = remember(state) { { state.isScrollInProgress } }
                            val content: @Composable () -> Unit = {
                                LazyColumn(
                                    state = state,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .then(if (hold) Modifier.raftHoldsHorizontalDragsWhileScrolling(scrolling) else Modifier),
                                ) {
                                    items(200) { index ->
                                        Box(Modifier.fillMaxWidth().height(60.dp).swipeRow()) { Text("page $page row $index") }
                                    }
                                }
                            }
                            if (hold) {
                                CompositionLocalProvider(LocalRaftScrollInProgress provides scrolling, content = content)
                            } else {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }

    /** The Activity row's gate, reading [LocalRaftScrollInProgress] at the down as the row does. */
    @Composable
    private fun Modifier.swipeRow(): Modifier {
        val listScrolling by rememberUpdatedState(LocalRaftScrollInProgress.current)
        return raftClaimsHorizontalDragFirst { _, _ -> 36f }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                if (listScrolling()) return@awaitEachGesture
                val claimPx = (viewConfiguration.touchSlop * 2f).coerceIn(16f, 24f)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    val total = change.position - down.position
                    if (abs(total.x) >= claimPx && abs(total.x) > 2f * abs(total.y) && total.x < 0f) {
                        rowClaimed = true
                        change.consume()
                        return@awaitEachGesture
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.flingUp() {
        onNodeWithTag("pager").performTouchInput {
            down(Offset(150f, 380f))
            repeat(6) { moveBy(Offset(0f, -55f), delayMillis = 8) }
            up()
        }
        mainClock.advanceTimeBy(32)
    }

    private fun ComposeUiTest.leftDragOnARow() {
        onNodeWithTag("pager").performTouchInput {
            down(Offset(250f, 150f))
            repeat(60) { moveBy(Offset(-2f, 0f), delayMillis = 8) }
            up()
        }
        mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun aTouchOnAFlingingListStopsItAndIsNoSwipeAndNoPageTurn() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { PagerWithSwipeList(hold = true) }
        mainClock.advanceTimeBy(100)
        flingUp()
        assertTrue(list.isScrollInProgress, "the list still flings when the finger lands")
        leftDragOnARow()
        assertFalse(rowClaimed, "no row swipe from the touch that stops the fling")
        assertEquals(0, pager.currentPage, "no page turn either")
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    /** Without the hold, the row takes the touch that lands on the flinging list: the reported bug. */
    @Test
    fun withoutTheHoldARowTakesTheTouchOnAFlingingList() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { PagerWithSwipeList(hold = false) }
        mainClock.advanceTimeBy(100)
        flingUp()
        assertTrue(list.isScrollInProgress)
        leftDragOnARow()
        assertTrue(rowClaimed, "the row swiped from the touch that should only have stopped the list")
    }

    @Test
    fun atRestTheRowStillSwipes() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { PagerWithSwipeList(hold = true) }
        mainClock.advanceTimeBy(100)
        flingUp()
        mainClock.advanceTimeBy(5_000)
        assertFalse(list.isScrollInProgress, "the fling has settled")
        leftDragOnARow()
        assertTrue(rowClaimed, "a row swipes as before once the list is at rest")
        assertEquals(0, pager.currentPage)
    }

    @Test
    fun holdDistance() {
        assertEquals(0f, raftScrollHoldDistance(false))
        assertEquals(Float.POSITIVE_INFINITY, raftScrollHoldDistance(true))
        assertEquals(Float.POSITIVE_INFINITY, raftDeferredTouchSlop(8f, raftScrollHoldDistance(true)))
    }
}
