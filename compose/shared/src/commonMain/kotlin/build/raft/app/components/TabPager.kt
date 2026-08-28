package build.raft.app.components

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.pager.HorizontalPager
import com.tencent.kuikly.compose.foundation.pager.rememberPagerState
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.foundation.gestures.awaitEachGesture
import com.tencent.kuikly.compose.foundation.gestures.awaitFirstDown
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
    /**
     * task #233 diagnostic tap: every sync-relevant event (effect launches,
     * page transitions, sync calls and their failures) is reported here so an
     * acceptance carrier can render the event chain on screen. Production
     * callers leave it null — no strings are built when unset.
     */
    onDebugEvent: ((String) -> Unit)? = null,
    pageContent: @Composable (page: Int, settled: Boolean) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = selectedIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount },
    )
    // Round-6 (run 33158645283, group 2): the port's animateScrollToPage
    // RETURNS before the pages actually transition ("animate ok" logged before
    // current=1), so a multi-page programmatic scroll walks currentPage
    // through intermediate pages afterwards. The pager->selection sync must
    // not report those — track the programmatic target and suppress reports
    // until the pager arrives.
    val programmaticTarget = remember { mutableStateOf<Int?>(null) }

    // Tab tap (or any external selection change) -> move the pager over.
    // animateScrollToPage with a scrollToPage fallback, each swallowing its
    // own failure: task #233 round-1 showed one throwing sync call kills the
    // effect scope and freezes ALL subsequent selection updates (page moved,
    // highlight never followed). scrollToPage is the fork-proven path
    // (SlockPagerScrollProbePage); a cancellation mid-animation must not be
    // treated as failure, so CancellationException rethrows.
    LaunchedEffect(pagerState, selectedIndex) {
        onDebugEvent?.invoke("sel-effect sel=$selectedIndex cur=${pagerState.currentPage}")
        if (selectedIndex in 0 until pageCount && selectedIndex != pagerState.currentPage) {
            programmaticTarget.value = selectedIndex
            try {
                pagerState.animateScrollToPage(selectedIndex)
                onDebugEvent?.invoke("animate ok -> $selectedIndex")
            } catch (cancelled: CancellationException) {
                onDebugEvent?.invoke("animate cancelled -> $selectedIndex")
                throw cancelled
            } catch (failed: Throwable) {
                onDebugEvent?.invoke("animate ERR ${failed::class.simpleName}")
                runCatching { pagerState.scrollToPage(selectedIndex) }
                    .onSuccess { onDebugEvent?.invoke("scrollTo ok -> $selectedIndex") }
                    .onFailure { onDebugEvent?.invoke("scrollTo ERR ${it::class.simpleName}") }
            }
            // NOT cleared here: the port's animate returns early, so the
            // currentPage effect clears the target on arrival instead.
        }
    }

    // Swipe -> report the new page. task #233 round-4 evidence (run
    // 33156483643): on the Kuikly iOS renderer settledPage NEVER tracks a
    // scroll — it emitted once at composition and stayed frozen through both
    // a user swipe (current=1, no settled=) and a successful
    // animateScrollToPage (current=2, no settled=). currentPage is the signal
    // that demonstrably fires on this port, so the sync is keyed on it; a
    // sub-threshold drag never crosses the half-page point, so currentPage
    // stays put and spring-backs still report nothing. The settledPage effect
    // is retained as a harmless idempotent belt for ports where it works.
    val currentPage = pagerState.currentPage
    LaunchedEffect(currentPage) {
        val pending = programmaticTarget.value
        onDebugEvent?.invoke("current=$currentPage sel=$selectedIndex pend=${pending ?: "-"}")
        if (pending != null) {
            // Mid-programmatic-scroll transitions are the pager catching up
            // with a tab tap, not user intent: report nothing. Arrival (or a
            // fresh gesture landing exactly on the target) clears the gate.
            if (currentPage == pending) programmaticTarget.value = null
            return@LaunchedEffect
        }
        if (currentPage != selectedIndex && currentPage in 0 until pageCount) {
            onSelectedIndexChange(currentPage)
            onDebugEvent?.invoke("report -> $currentPage")
        }
    }
    // settledPage is LOG-ONLY. Round-5 evidence (run 33158645283, group 1):
    // on the Kuikly iOS renderer settledPage can regress to a stale value
    // after a completed swipe (settled=1 -> settled=0 while sel=1); a
    // reporting effect fed by it then REVERTED the correct selection and
    // animated the page back — a broken signal behind a "harmless belt" is
    // an active reverter, not redundancy. currentPage is the sole sync
    // authority; this stays only so acceptance logs keep showing what the
    // port's settle bookkeeping does.
    val settledPage = pagerState.settledPage
    LaunchedEffect(settledPage) {
        onDebugEvent?.invoke("settled=$settledPage sel=$selectedIndex")
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
                // currentPage, not settledPage: the latter is frozen on the
                // iOS port (round-4 evidence), which would leave every
                // swiped-to page permanently rendered as "not settled".
                pageContent(page, page == pagerState.currentPage)
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
private fun BoxScope.EdgeGuard(inset: Dp, alignment: Alignment) {
    if (inset <= 0.dp) return
    // Direct strip in the parent stacking Box — no full-size wrapper layer
    // that could shadow hit testing over the whole pager (task #233 hygiene).
    Box(
        modifier = Modifier
            .align(alignment)
            .width(inset)
            .fillMaxHeight()
            // Round-5 evidence (group 3): an EMPTY pointerInput does NOT keep
            // the pager from receiving edge-born drags on this port — the
            // pager paged and bounced on a drag starting at x=10pt. Actively
            // consume every change of a touch born in the inset instead, so
            // the pager never sees it and the platform edge gesture stays
            // free to win (same awaitEachGesture pattern as the AppShell
            // drawer's admission logic).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    )
}

/** Stable per-page identity helper for callers keying retained page state. */
@Composable
fun <T> rememberTabPageState(pageCount: Int, factory: (Int) -> T): List<T> =
    remember(pageCount) { List(pageCount, factory) }
