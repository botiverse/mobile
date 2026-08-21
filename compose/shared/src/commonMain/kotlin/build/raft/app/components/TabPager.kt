package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.pager.HorizontalPager
import com.tencent.kuikly.compose.foundation.pager.rememberPagerState
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

/**
 * task #232: how a [TabPager] may claim horizontal drags.
 *
 * The shared component deliberately knows nothing about platform back
 * gestures: platforms INJECT their avoidance policy here instead of the
 * component hardcoding it (iOS wants its interactive edge-back to win over
 * the pager near the screen's start edge; Android/OHOS keep 0 by default).
 */
data class TabPagerGesturePolicy(
    /** Region from the start edge where the pager must NOT claim the drag. */
    val startEdgeInset: Dp = 0.dp,
    /** Region from the end edge where the pager must NOT claim the drag. */
    val endEdgeInset: Dp = 0.dp,
    /** Master switch — false renders pages tap-switch-only (pager still animates). */
    val swipeEnabled: Boolean = true,
)

/**
 * Two-layer swipeable tab container (task #232 contract, #proj-uiux:903b03d9):
 * the caller's tab bar (SegmentedControl or any header) stays the tap surface
 * and selected-state owner; the body is a [HorizontalPager] sharing the same
 * selected index with two-way sync:
 *
 * - external [selectedIndex] change (tab tap) -> [PagerState.animateScrollToPage];
 * - a user swipe settling on a page -> [onSelectedIndexChange] once per settle.
 *
 * The pager's own gesture engine does the follow-finger tracking, threshold and
 * snap physics; [TabPagerGesturePolicy] only carves out edge regions where a
 * transparent guard claims the touch so platform edge gestures win. Each page
 * keeps its own composition alive across swipes, so page-local state
 * (scroll position, focus) is retained by normal remember/state hoisting.
 */
@Composable
fun TabPager(
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    pageCount: Int,
    modifier: Modifier = Modifier,
    gesturePolicy: TabPagerGesturePolicy = TabPagerGesturePolicy(),
    tabBar: @Composable () -> Unit = {},
    pageContent: @Composable (page: Int, settled: Boolean) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = selectedIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount },
    )

    // Tab tap (or any external selection change) -> animate the pager over.
    LaunchedEffect(pagerState, selectedIndex) {
        if (selectedIndex in 0 until pageCount && selectedIndex != pagerState.currentPage) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }

    // Swipe settling on a new page -> report exactly once per settle. settledPage
    // (not currentPage) so a half-drag that springs back reports nothing.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != selectedIndex && settled in 0 until pageCount) {
                onSelectedIndexChange(settled)
            }
        }
    }

    Column(modifier = modifier) {
        tabBar()
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = gesturePolicy.swipeEnabled,
                key = { page -> page },
            ) { page ->
                pageContent(page, page == pagerState.settledPage)
            }
            // Edge guards: claim touches born inside the injected insets so the
            // pager never sees them and the platform edge gesture wins. The
            // guard consumes nothing on its own — it only shadows the pager.
            EdgeGuard(
                inset = gesturePolicy.startEdgeInset,
                alignment = Alignment.CenterStart,
            )
            EdgeGuard(
                inset = gesturePolicy.endEdgeInset,
                alignment = Alignment.CenterEnd,
            )
        }
    }
}

@Composable
private fun EdgeGuard(inset: Dp, alignment: Alignment) {
    if (inset <= 0.dp) return
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(alignment)
                .width(inset)
                .fillMaxHeight()
                // An empty pointerInput block still participates in hit testing,
                // which is exactly the point: touches born here belong to the
                // platform (edge-back), never to the pager underneath.
                .pointerInput(Unit) {},
        )
    }
}

/** Stable per-page identity helper for callers keying retained page state. */
@Composable
fun <T> rememberTabPageState(pageCount: Int, factory: (Int) -> T): List<T> =
    remember(pageCount) { List(pageCount, factory) }
