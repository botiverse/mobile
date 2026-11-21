package build.raft.designsystem.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import build.raft.designsystem.gesture.raftSegmentedPagerSwipe
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import kotlinx.coroutines.CancellationException

/**
 * The position of a [RaftSegmentedPager]'s content, in segments: what its switch's selection
 * indicator follows ([RaftSegmentedControl]'s `selectionPosition`).
 */
@Stable
class RaftSegmentedPagerState internal constructor(internal val pager: PagerState) {
    /** How many segments (pages) there are. */
    val segmentCount: Int get() = pager.pageCount

    /** The segment the content is on: mid-drag, the nearer one (it changes halfway). */
    val currentIndex: Int get() = pager.currentPage

    /**
     * Where the content is, in segments: `1f` on the second, `1.4f` 40% of the way from the second
     * to the third (a drag or a slide in progress). Read it where it is drawn or laid out, not in
     * composition, so a drag does not recompose its reader every frame.
     */
    val position: Float get() = raftSegmentedPagerPosition(pager.currentPage, pager.currentPageOffsetFraction, pager.pageCount)

    /** Whether the content is moving (a drag, its settle, or a slide to a tapped segment). */
    val isMoving: Boolean get() = pager.isScrollInProgress

    /** Slides the content to segment [index] (raft-ui `ease-slide`, [RaftSegmentedPagerSlideMillis]). */
    suspend fun animateToSegment(index: Int) {
        pager.animateScrollToPage(index, animationSpec = tween(RaftSegmentedPagerSlideMillis, easing = RaftMotion.EaseSlide))
    }

    /** Shows segment [index] at once. */
    suspend fun scrollToSegment(index: Int) {
        pager.scrollToPage(index)
    }
}

/**
 * How long a tapped segment's content slides in: raft-ui Motion's slides take 220ms to 380ms with
 * `ease-slide`; a page-wide slide takes the middle of that range.
 */
const val RaftSegmentedPagerSlideMillis: Int = 300

/**
 * A [RaftSegmentedPagerState] starting on [initialIndex] (saved across configuration changes).
 * [initialOffsetFraction] (-0.5..0.5) starts it part of the way to a neighbour: previews and
 * screenshots of a drag in progress.
 */
@Composable
fun rememberRaftSegmentedPagerState(
    initialIndex: Int = 0,
    initialOffsetFraction: Float = 0f,
    segmentCount: () -> Int,
): RaftSegmentedPagerState {
    val pager =
        rememberPagerState(
            initialPage = initialIndex.coerceAtLeast(0),
            initialPageOffsetFraction = initialOffsetFraction,
            pageCount = segmentCount,
        )
    return remember(pager) { RaftSegmentedPagerState(pager) }
}

private val NoPageNestedScroll = object : NestedScrollConnection {}

/** [RaftSegmentedPagerState.position]: the page plus its offset, kept within the segments. */
internal fun raftSegmentedPagerPosition(page: Int, offsetFraction: Float, pageCount: Int): Float {
    if (pageCount <= 0) return 0f
    val position = page + offsetFraction
    return if (position.isNaN()) page.toFloat() else position.coerceIn(0f, (pageCount - 1).toFloat())
}

/**
 * A segmented switch over views whose content is a pager that follows the finger (raft-ui
 * SegmentedControl's content, on a phone): the [header] (the switch, e.g. a [RaftSegmentedControl]
 * with `selectionPosition = { state.position }` so its selected sticker slides with the content,
 * and anything beside it), then the selected segment's [content].
 *
 * - The caller's switch stays the tap surface and the selection's owner, as a tab pager's tabs:
 *   [selectedIndex] changing (a tap on a segment, or the caller's own code) slides the content to it
 *   ([RaftSegmentedPagerSlideMillis] with `ease-slide`, at once with reduced motion); a swipe that
 *   comes to a segment reports it through [onSelectedIndexChange], once the content is nearer to it
 *   (halfway through a drag), so the label and the content always agree.
 * - A sideways drag moves the content with the finger and settles with the tab pager's snap fling
 *   (by velocity, or past halfway). A swipe that starts on the switch itself (the [header]) turns
 *   to the next segment once it has moved 2x the slop, as the sub-tab swipe always did: the switch
 *   is too narrow to carry the content past halfway. The segments come first; only at the first or last does the
 *   drag reach an enclosing tab pager. On iOS a back drag from the leading 40dp strip goes back
 *   instead ([build.raft.designsystem.gesture.raftSegmentedPagerSwipe]).
 * - Only the visible segments are composed (one at rest, two mid-drag), as a tab pager's pages.
 *   Pages are keyed by index.
 */
@Composable
fun RaftSegmentedPager(
    state: RaftSegmentedPagerState,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    content: @Composable (index: Int) -> Unit,
) {
    val pager = state.pager
    val reducedMotion = rememberRaftReducedMotion()
    // A tap's (or the caller's) target while the content slides there: the pages it passes are not
    // reported as a swipe would be.
    val programmaticTarget = remember { mutableStateOf<Int?>(null) }
    // Laid out at no size (a covered page), every page sits at offset 0 and the pager's current page
    // jumps to the last: layout, not intent, so nothing is reported or scrolled while it lasts.
    val hasPageSize by remember(pager) { derivedStateOf { pager.layoutInfo.pageSize > 0 } }
    val pageCount = pager.pageCount
    LaunchedEffect(pager, selectedIndex, hasPageSize) {
        if (!hasPageSize || selectedIndex !in 0 until pageCount || selectedIndex == pager.currentPage) return@LaunchedEffect
        programmaticTarget.value = selectedIndex
        try {
            if (reducedMotion) state.scrollToSegment(selectedIndex) else state.animateToSegment(selectedIndex)
        } catch (cancelled: CancellationException) {
            // A drag took the content (or the selection changed again): stop holding reports back.
            if (programmaticTarget.value == selectedIndex) programmaticTarget.value = null
            throw cancelled
        }
        if (programmaticTarget.value == selectedIndex) programmaticTarget.value = null
    }
    val currentSelected by rememberUpdatedState(selectedIndex)
    val report by rememberUpdatedState(onSelectedIndexChange)
    val currentPage = pager.currentPage
    LaunchedEffect(pager, currentPage) {
        val pending = programmaticTarget.value
        if (pending != null) {
            if (currentPage == pending) programmaticTarget.value = null
            return@LaunchedEffect
        }
        if (pager.layoutInfo.pageSize <= 0) return@LaunchedEffect
        if (currentPage != currentSelected && currentPage in 0 until pager.pageCount) report(currentPage)
    }
    // The switch's height: a swipe that starts on it turns a segment once it is decided, as the
    // narrow switch could never carry the content past halfway (raftSegmentSwitchSwipeVelocity).
    val headerHeight = remember { IntArray(1) }
    Column(modifier.raftSegmentedPagerSwipe(pager, PagerDefaults.flingBehavior(pager)) { headerHeight[0] }) {
        Column(Modifier.onSizeChanged { headerHeight[0] = it.height }) { header() }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            // Driven by raftSegmentedPagerSwipe: the pager's own drag would take back drags from
            // the iOS edge strip and could not hand a drag at its ends to the enclosing tab pager.
            userScrollEnabled = false,
            beyondViewportPageCount = 0,
            // No pager hand-over of its own: what a child scroller leaves over (and its fling)
            // goes on to raftSegmentedPagerSwipe, which moves this pager or passes it outward.
            pageNestedScrollConnection = NoPageNestedScroll,
        ) { page -> content(page) }
    }
}
