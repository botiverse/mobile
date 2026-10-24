package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A row with its own leftward drag (the Activity row swipe: claims only past 2x the touch slop with
 * |dx| > 2|dy|, on the Main pass, and gives up once anything else consumed the stream) inside a
 * pager wired like `TabPager` on its first page. iOS 1.15.5: the pager's start-of-content forward
 * drag claimed at 1x slop, before the row could, so the whole list slid toward the next page and the
 * row never opened.
 */
@OptIn(ExperimentalTestApi::class)
class RaftBackSwipeNestedDragUiTest {
    private lateinit var pager: PagerState
    private var rowClaimed = false
    private var rowDragPx = 0f
    private var observedSlop = 0f
    private var registerRow = true

    @Composable
    private fun PagerWithSwipeRows(regions: RaftBackSwipeRegions?) {
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalRaftBackSwipeRegions provides regions) {
            pager = rememberPagerState(pageCount = { 3 })
            // Wired like TabPager.
            val nested = rememberRaftNestedHorizontalDragHost()
            val backSwipe = rememberRaftBackSwipeScroll(pager, PagerDefaults.flingBehavior(pager), nestedDrags = nested)
            Box(Modifier.size(300.dp, 400.dp).then(nested.modifier).then(backSwipe.modifier).testTag("pager")) {
                nested.ProvideScrollerConfiguration {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = backSwipe.userScrollEnabled,
                    ) { page ->
                        nested.ProvideContent {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(20) { index ->
                                    val row = Modifier.fillMaxWidth().height(60.dp)
                                    Box(if (index == 1) row.swipeRow().testTag("row$page") else row) {
                                        Text("page $page row $index")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun claimPx(slop: Float): Float = (slop * 2f).coerceIn(16f, 24f)

    private fun Modifier.swipeRow(): Modifier = raftClaimsHorizontalDragFirst(enabled = registerRow) { _, _ -> claimPx(18f) }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            observedSlop = viewConfiguration.touchSlop
            val claimPx = claimPx(viewConfiguration.touchSlop)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                val total = change.position - down.position
                if (abs(total.x) >= claimPx && abs(total.x) > 2f * abs(total.y) && total.x < 0f) {
                    rowClaimed = true
                    change.consume()
                    break
                }
            }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                rowDragPx += change.position.x - change.previousPosition.x
                change.consume()
            }
        }
    }

    private fun assertRowTakesItsLeftSwipe(regions: RaftBackSwipeRegions?, page: Int = 0) = runComposeUiTest {
        setContent { PagerWithSwipeRows(regions) }
        if (page != 0) {
            runOnIdle { runBlocking { pager.scrollToPage(page) } }
            waitForIdle()
        }
        onNodeWithTag("row$page").performTouchInput {
            down(Offset(250f, 30f))
            // Small steps, like a real finger: every threshold is crossed where it lies.
            repeat(80) { moveBy(Offset(-2f, 0f), delayMillis = 8) }
            up()
        }
        waitForIdle()
        assertTrue(rowClaimed, "the row claims its own left swipe")
        assertTrue(rowDragPx < -100f, "the row follows the finger (moved ${rowDragPx}px)")
        assertEquals(page, pager.currentPage, "the pager does not move under a row's swipe")
        assertEquals(0f, pager.currentPageOffsetFraction)
        assertEquals(18f, observedSlop, "page content keeps the platform touch slop")
    }

    @Test
    fun rowSwipeWinsOverTheFirstPageForwardDragOnABackSwipeHost() = assertRowTakesItsLeftSwipe(RaftBackSwipeRegions())

    @Test
    fun rowSwipeWinsOverThePagerPastTheFirstPageOnABackSwipeHost() = assertRowTakesItsLeftSwipe(RaftBackSwipeRegions(), page = 1)

    @Test
    fun rowSwipeWinsOverThePagerWithoutAHostBackSwipe() = assertRowTakesItsLeftSwipe(null)

    @Test
    fun forwardDragOutsideASwipeRowStillTurnsThePage() = runComposeUiTest {
        setContent { PagerWithSwipeRows(RaftBackSwipeRegions()) }
        onNodeWithTag("pager").performTouchInput {
            // Row 3, which has no swipe of its own.
            down(Offset(250f, 200f))
            repeat(20) { moveBy(Offset(-12f, 0f), delayMillis = 16) }
            up()
        }
        waitForIdle()
        assertFalse(rowClaimed)
        assertEquals(1, pager.currentPage)
    }

    @Test
    fun aDragTheRowReleasesStillReachesThePager() = runComposeUiTest {
        // Past the first page a drag toward the end is not the row's: the pager pages back once the
        // row released it.
        setContent { PagerWithSwipeRows(null) }
        runOnIdle { runBlocking { pager.scrollToPage(1) } }
        waitForIdle()
        onNodeWithTag("row1").performTouchInput {
            down(Offset(30f, 30f))
            repeat(60) { moveBy(Offset(4f, 0f), delayMillis = 8) }
            up()
        }
        waitForIdle()
        assertFalse(rowClaimed)
        assertEquals(0, pager.currentPage)
    }

    @Test
    fun anUnregisteredRowLosesItsSwipeToThePager() = runComposeUiTest {
        // The 1.15.5 bug: the pager claims at 1x slop, before the row's 2x slop gate.
        registerRow = false
        setContent { PagerWithSwipeRows(RaftBackSwipeRegions()) }
        onNodeWithTag("row0").performTouchInput {
            down(Offset(250f, 30f))
            repeat(80) { moveBy(Offset(-2f, 0f), delayMillis = 8) }
            up()
        }
        waitForIdle()
        assertFalse(rowClaimed)
        assertEquals(1, pager.currentPage)
    }

    @Test
    fun deferredSlopAndLookup() {
        assertEquals(8f, raftDeferredTouchSlop(8f, 0f))
        assertEquals(17f, raftDeferredTouchSlop(8f, 16f))
        assertEquals(30f, raftDeferredTouchSlop(30f, 16f))
        val row = RaftNestedDragRegionSnapshot({ Rect(0f, 0f, 100f, 50f) }) { p -> if (p.x < 24f) 0f else 20f }
        val empty = RaftNestedDragRegionSnapshot({ Rect(0f, 0f, 0f, 0f) }) { 99f }
        assertEquals(20f, raftNestedClaimDistanceAt(Offset(50f, 10f), listOf(row, empty)))
        assertEquals(0f, raftNestedClaimDistanceAt(Offset(10f, 10f), listOf(row)), "edge zone: no deferral")
        assertEquals(0f, raftNestedClaimDistanceAt(Offset(50f, 80f), listOf(row)), "outside: no deferral")
    }
}
