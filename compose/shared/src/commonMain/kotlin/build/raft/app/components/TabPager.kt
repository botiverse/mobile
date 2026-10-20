package build.raft.app.components

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberOverscrollEffect
import build.raft.app.raftPlatformTabPagerOverscrollEnabled
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import kotlin.math.abs

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
 * parent-modifier guard claims horizontal drags (taps and vertical scrolls
 * pass to the content untouched) so platform edge gestures win. Each page
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
    keepItemAlive: Boolean = false,
    tabBar: @Composable () -> Unit = {},
    /**
     * task #233 diagnostic tap: every sync-relevant event (effect launches,
     * page transitions, sync calls and their failures) is reported here so an
     * acceptance carrier can render the event chain on screen. Production
     * callers leave it null — no strings are built when unset.
     */
    onDebugEvent: ((String) -> Unit)? = null,
    /**
     * `active` means "this page IS the pager's current page" — NOT "the settle
     * animation finished". This component deliberately offers no settle-grade
     * signal: the port's settledPage is frozen/regressing on iOS (task #233
     * round-4/5 evidence), so a caller gating expensive work on it would never
     * (or wrongly) see it fire.
     */
    pageContent: @Composable (page: Int, active: Boolean) -> Unit,
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
    // (RaftPagerScrollProbePage); a cancellation mid-animation must not be
    // treated as failure, so CancellationException rethrows.
    LaunchedEffect(pagerState, selectedIndex) {
        onDebugEvent?.invoke("sel-effect sel=$selectedIndex cur=${pagerState.currentPage}")
        if (selectedIndex in 0 until pageCount && selectedIndex != pagerState.currentPage) {
            programmaticTarget.value = selectedIndex
            try {
                pagerState.animateScrollToPage(selectedIndex)
                onDebugEvent?.invoke("animate ok -> $selectedIndex")
            } catch (cancelled: CancellationException) {
                // The animation lost its mutex (a user gesture stole the
                // scroll) or the effect is being torn down; either way the
                // pager is no longer travelling to this target, and an armed
                // gate whose arrival never comes suppresses every later swipe
                // report (#2224 review). Clear it — but only our own value: on
                // a selectedIndex restart the replacement effect may already
                // have armed the gate for the new target.
                if (programmaticTarget.value == selectedIndex) programmaticTarget.value = null
                onDebugEvent?.invoke("animate cancelled -> $selectedIndex (gate cleared)")
                throw cancelled
            } catch (failed: Throwable) {
                onDebugEvent?.invoke("animate ERR ${failed::class.simpleName}")
                runCatching { pagerState.scrollToPage(selectedIndex) }
                    .onSuccess { onDebugEvent?.invoke("scrollTo ok -> $selectedIndex") }
                    .onFailure {
                        // Both paths failed: the pager is not moving, so the
                        // arrival that clears the gate never happens.
                        if (programmaticTarget.value == selectedIndex) programmaticTarget.value = null
                        onDebugEvent?.invoke("scrollTo ERR ${it::class.simpleName} (gate cleared)")
                    }
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

    // Round-8 (task #246 G6, run 33376167427): the edge guard is a PARENT
    // modifier, not an overlay sibling. Round-7's transparent overlay strip
    // blocked tap delivery to the rows beneath it on this port EVEN WHEN IT
    // CONSUMED NOTHING — a stationary in-band tap produced no row click while
    // the same window's out-of-band probe proved the input path alive. The
    // port routes a gesture to the topmost hit sibling regardless of
    // consumption, so pass-through cannot be had from an overlay. A parent
    // modifier sits in the child's own hit path (the AppShell drawer's
    // admission logic is the in-repo, port-proven precedent), so undecided
    // gestures reach the rows untouched; claimed horizontal drags are
    // consumed in the Initial pass, which descendants (the pager) observe.
    val density = LocalDensity.current
    val guardWidthPx = remember { mutableStateOf(0) }
    // Full-page back swipe (iOS): on the first page a back-direction drag is left to the host's
    // interactive pop instead of rubber-banding the pager (which made the Compose touch recogniser
    // win against the pop); forward drags still turn the page with the pager's own snap fling. Past
    // the first page the pager keeps every drag and tells the host so. Inert on Android.
    val backSwipe = rememberRaftBackSwipeScroll(
        state = pagerState,
        flingBehavior = PagerDefaults.flingBehavior(pagerState),
        enabled = gesturePolicy.swipeEnabled,
    )
    Column(modifier = modifier) {
        tabBar()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(backSwipe.modifier)
                .onGloballyPositioned { guardWidthPx.value = it.size.width }
                .pointerInput(gesturePolicy) {
                    if (!gesturePolicy.swipeEnabled) return@pointerInput
                    val startPx = gesturePolicy.startEdgeInset.value * density.density
                    val endPx = gesturePolicy.endEdgeInset.value * density.density
                    if (startPx <= 0f && endPx <= 0f) return@pointerInput
                    awaitEachGesture {
                        var origin: Offset? = null
                        var watching = true
                        var claimed = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull()
                            if (change != null) {
                                if (origin == null && change.pressed && !change.previousPressed) {
                                    val width = guardWidthPx.value
                                    val inStart = startPx > 0f && change.position.x < startPx
                                    val inEnd = endPx > 0f && width > 0 &&
                                        change.position.x > width - endPx
                                    if (inStart || inEnd) origin = change.position else watching = false
                                }
                                val o = origin
                                if (watching && !claimed && o != null) {
                                    when (
                                        edgeGuardGestureDecision(
                                            horizontalDragDp = (change.position.x - o.x) / density.density,
                                            verticalDragDp = (change.position.y - o.y) / density.density,
                                            canScrollBackward = pagerState.currentPage > 0,
                                        )
                                    ) {
                                        EdgeGuardGestureDecision.Claim -> claimed = true
                                        EdgeGuardGestureDecision.Release -> watching = false
                                        EdgeGuardGestureDecision.Undecided -> Unit
                                    }
                                }
                                if (claimed) event.changes.forEach { it.consume() }
                            }
                            if (event.changes.none { it.pressed }) return@awaitEachGesture
                        }
                    }
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = gesturePolicy.swipeEnabled && backSwipe.userScrollEnabled,
                key = { page -> page },
                // Kuikly keepItemAlive kept every page composed.
                beyondViewportPageCount = if (keepItemAlive) pageCount else 0,
                // Kuikly's pager had no edge bounce; on iOS the CMP rubber band could stay
                // stuck at an offset (see raftPlatformTabPagerOverscrollEnabled).
                overscrollEffect = if (raftPlatformTabPagerOverscrollEnabled()) rememberOverscrollEffect() else null,
            ) { page ->
                // currentPage, not settledPage: the latter is frozen on the
                // iOS port (round-4 evidence), which would leave every
                // swiped-to page permanently rendered as inactive.
                pageContent(page, page == pagerState.currentPage)
            }
        }
    }
}

/**
 * How far (in dp) a pointer born inside an edge inset must travel before the
 * guard claims the gesture as a horizontal drag. Below this, nothing is
 * consumed, so a stationary tap (or tap with natural jitter) reaches the row
 * underneath. Deliberately smaller than a typical pager claim slop (~8dp) so
 * the guard decides first; the pager never moving on edge-born drags is
 * runtime-verified by the G-edge acceptance case, not assumed.
 */
private const val EdgeGuardClaimSlopDp = 6f

internal enum class EdgeGuardGestureDecision {
    /** Not enough movement yet — consume nothing, keep watching. */
    Undecided,

    /** Horizontal-dominant drag — consume everything until release. */
    Claim,

    /** Vertical-dominant drag — hand the whole gesture to the content below. */
    Release,
}

/**
 * Pure claim policy for the edge guard, extracted so the tap/scroll/drag
 * classification is unit-testable ([TabPagerEdgeGuardDecisionTest]) — the
 * interception around it is port behaviour only runtime acceptance can
 * verify. Ties go to Claim: the guard exists exactly for horizontal edge
 * gestures, except when [canScrollBackward] is false (e.g. at page 0), where
 * rightward drags release to the platform back gesture instead of being
 * claimed and consumed.
 */
internal fun edgeGuardGestureDecision(
    horizontalDragDp: Float,
    verticalDragDp: Float,
    claimSlopDp: Float = EdgeGuardClaimSlopDp,
    canScrollBackward: Boolean = true,
): EdgeGuardGestureDecision {
    val dx = abs(horizontalDragDp)
    val dy = abs(verticalDragDp)
    return when {
        !canScrollBackward && horizontalDragDp >= claimSlopDp && horizontalDragDp >= dy ->
            EdgeGuardGestureDecision.Release
        dx >= claimSlopDp && dx >= dy -> EdgeGuardGestureDecision.Claim
        dy >= claimSlopDp && dy > dx -> EdgeGuardGestureDecision.Release
        else -> EdgeGuardGestureDecision.Undecided
    }
}

/** Stable per-page identity helper for callers keying retained page state. */
@Composable
fun <T> rememberTabPageState(pageCount: Int, factory: (Int) -> T): List<T> =
    remember(pageCount) { List(pageCount, factory) }
