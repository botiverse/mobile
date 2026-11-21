package build.raft.designsystem.gesture

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Whether a swipe that started here may turn to the previous segment: not when it starts in the
 * leading-edge strip on a host whose back swipe pops the page from there (iOS), which keeps that
 * swipe for the pop. Swipes that start elsewhere, and every swipe without such a host, turn as before.
 */
internal fun raftSegmentSwipeCanGoBackward(
    canGoBackward: Boolean,
    hostHasBackSwipe: Boolean,
    startsInLeadingEdge: Boolean,
): Boolean = canGoBackward && !(hostHasBackSwipe && startsInLeadingEdge)

/**
 * Pure classification of a drag over a segmented pager ([raftSegmentedPagerSwipe]) once it has
 * moved: vertical-dominant drags go to the content (a list); a horizontal drag toward a segment that
 * exists is claimed (the content then follows the finger); toward the first or last segment's edge
 * it is released to the enclosing tab pager, and on a back-swipe host (iOS) a back drag that starts
 * in the leading [RaftBackSwipeEdgeWidth] strip is released for the host's pop
 * ([raftSegmentSwipeCanGoBackward]). The same slop and axis rule as a tab pager's own drag.
 */
internal fun raftSegmentedPagerDragDecision(
    dxPx: Float,
    dyPx: Float,
    touchSlopPx: Float,
    isLtr: Boolean,
    hostHasBackSwipe: Boolean,
    startsInLeadingEdge: Boolean,
    canGoBackward: Boolean,
    canGoForward: Boolean,
): RaftPagingDragDecision {
    val adx = abs(dxPx)
    val ady = abs(dyPx)
    if (adx < touchSlopPx && ady < touchSlopPx) return RaftPagingDragDecision.Undecided
    if (ady > adx) return RaftPagingDragDecision.Release
    val forward = if (isLtr) dxPx < 0f else dxPx > 0f
    val turns =
        if (forward) canGoForward else raftSegmentSwipeCanGoBackward(canGoBackward, hostHasBackSwipe, startsInLeadingEdge)
    return if (turns) RaftPagingDragDecision.Claim else RaftPagingDragDecision.Release
}

/**
 * The settle velocity (forward, px/s) for a swipe that started on the segment switch itself (the
 * header): the switch is much narrower than a page, so a swipe across it would rarely carry the
 * content past halfway or fast enough to flick. As the sub-tab swipe always did, once it has moved
 * [decidePx] (2x the slop) it turns to the next segment that way: the settle gets at least
 * [turnVelocity] in the direction it [travelledPx]. A shorter swipe settles as it is.
 */
internal fun raftSegmentSwitchSwipeVelocity(
    travelledPx: Float,
    velocity: Float,
    decidePx: Float,
    turnVelocity: Float,
): Float {
    if (abs(travelledPx) < decidePx) return velocity
    val direction = if (travelledPx > 0f) 1f else -1f
    return if (velocity * direction >= turnVelocity) velocity else direction * turnVelocity
}

/**
 * One gesture's hand-over of horizontal scroll a child scroller left over (the segment track itself,
 * a code block) at its end: the segments get it before the enclosing tab pager, as a nested pager
 * would. The first leftover decides the gesture: toward a segment that exists (and, on a back-swipe
 * host, not a back drag from the leading strip) the segments own it, all of its leftover and fling,
 * so the outer pager stays put, and the content follows it; toward the edge it passes through to the
 * outer pager for the whole gesture.
 */
internal class RaftSegmentNestedDrag {
    private enum class Owner { Undecided, Drives, Pointer, PassThrough }

    private var owner = Owner.Undecided
    private var startsInLeadingEdge = false

    /** Whether this gesture went down on the segment switch (the header), not on the content. */
    var startsOnSwitch: Boolean = false
        private set

    /** The forward scroll this gesture's leftovers drove so far (px). */
    var travelled: Float = 0f
        private set

    /**
     * A new gesture went down ([startsInLeadingEdge]: in the leading [RaftBackSwipeEdgeWidth] strip;
     * [startsOnSwitch]: on the segment switch).
     */
    fun reset(startsInLeadingEdge: Boolean = false, startsOnSwitch: Boolean = false) {
        owner = Owner.Undecided
        this.startsInLeadingEdge = startsInLeadingEdge
        this.startsOnSwitch = startsOnSwitch
        travelled = 0f
    }

    /** The pointer path claimed the gesture: the rest of it belongs to the segments, driven there. */
    fun claimedByPointer() {
        owner = Owner.Pointer
    }

    /** Whether the segments own this gesture (its leftover fling is theirs too). */
    val owned: Boolean get() = owner == Owner.Drives || owner == Owner.Pointer

    /** Whether this gesture's leftovers move the content ([onLeftover] calls `drive`). */
    val drives: Boolean get() = owner == Owner.Drives

    /**
     * Horizontal leftover [dx] (pointer direction) from a child: returns how much of it the segments
     * consume, calling [drive] with the forward scroll delta when it moves the content.
     */
    fun onLeftover(
        dx: Float,
        isLtr: Boolean,
        hostHasBackSwipe: Boolean,
        canGoBackward: Boolean,
        canGoForward: Boolean,
        drive: (forwardDelta: Float) -> Unit,
    ): Float {
        if (dx == 0f || owner == Owner.PassThrough) return 0f
        if (owner == Owner.Undecided) {
            val forward = (dx < 0) == isLtr
            val turns =
                if (forward) canGoForward else raftSegmentSwipeCanGoBackward(canGoBackward, hostHasBackSwipe, startsInLeadingEdge)
            owner = if (turns) Owner.Drives else Owner.PassThrough
            if (owner == Owner.PassThrough) return 0f
        }
        if (owner == Owner.Drives) {
            val forwardDelta = raftForwardScrollDelta(dx, isLtr)
            travelled += forwardDelta
            drive(forwardDelta)
        }
        return dx
    }
}

/**
 * Drives a pager from one gesture's deltas inside a single user-priority scroll, then settles it with
 * the fling (the pager's snap) on [release]. Deltas sent before the scroll starts are kept.
 */
internal class RaftPagerDrive(
    private val state: ScrollableState,
    private val scope: CoroutineScope,
    private val fling: State<FlingBehavior>,
) {
    private var deltas: Channel<Float>? = null
    private var velocities: Channel<Float>? = null

    val active: Boolean get() = deltas != null

    fun start() {
        if (deltas != null) return
        val d = Channel<Float>(Channel.UNLIMITED)
        val v = Channel<Float>(1)
        deltas = d
        velocities = v
        scope.launch {
            state.scroll(MutatePriority.UserInput) {
                for (delta in d) scrollBy(delta)
                val velocity = v.tryReceive().getOrNull() ?: 0f
                with(fling.value) { performFling(velocity) }
            }
        }
    }

    /** Moves the content by [forwardDelta] (positive: toward the next segment). */
    fun drag(forwardDelta: Float) {
        deltas?.trySend(forwardDelta)
    }

    /** Ends the gesture: settles with [forwardVelocity]. Always call it once a drive started. */
    fun release(forwardVelocity: Float) {
        velocities?.trySend(forwardVelocity)
        deltas?.close()
        deltas = null
        velocities = null
    }
}

/** The least settle velocity of a swipe on the switch that turns a segment (twice the pager's snap threshold). */
internal val RaftSegmentSwitchTurnVelocity = 800.dp

/**
 * The swipe of a segmented pager ([build.raft.designsystem.components.RaftSegmentedPager]): put it on
 * the element spanning the segment switch and the pages, over a pager backed by [state] whose own
 * scrolling is off (`userScrollEnabled = false`). A sideways drag moves the content with the finger
 * and settles with [flingBehavior] (the pager's snap, as a tab pager's), so:
 *
 * - The segments come first: a drag toward a segment that exists is theirs for the whole gesture,
 *   and only at the first or last segment does it reach the enclosing tab pager (released
 *   unconsumed, before that pager may claim: its slop waits for this one, [raftClaimsHorizontalDragFirst]).
 * - A drag a child took is left to it: a list's vertical scroll, or a horizontal scroller (the
 *   segment track, a code block) while it scrolls; what such a scroller leaves over at its end moves
 *   the segments before the tab pager ([RaftSegmentNestedDrag]), so a swipe on the switch itself
 *   turns the segment too.
 * - On a host with a full-page back swipe (iOS) a back drag that starts in the leading
 *   [RaftBackSwipeEdgeWidth] strip is never claimed: it is left to the host's pop. The rest of the
 *   bounds is registered as back-swipe content while there is a segment before
 *   ([raftBackSwipeRegion]). Android's Back is an OS gesture: the strip turns segments as anywhere.
 *
 * Scroll semantics (accessibility scrolling) are provided here, since the pager's own are off.
 */
@Composable
fun Modifier.raftSegmentedPagerSwipe(
    state: ScrollableState,
    flingBehavior: FlingBehavior,
    switchHeightPx: () -> Int = { 0 },
): Modifier {
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val hostHasBackSwipe = LocalRaftBackSwipeRegions.current != null
    val edgePx = with(LocalDensity.current) { RaftBackSwipeEdgeWidth.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val maxFlingVelocity = LocalViewConfiguration.current.maximumFlingVelocity
    val turnVelocity = with(LocalDensity.current) { RaftSegmentSwitchTurnVelocity.toPx() }
    val switchHeight by rememberUpdatedState(switchHeightPx)
    val outerDrags = LocalRaftNestedHorizontalDrags.current
    val scope = rememberCoroutineScope()
    val currentFling = rememberUpdatedState(flingBehavior)
    val drive = remember(state, scope) { RaftPagerDrive(state, scope, currentFling) }
    val nested = remember { RaftSegmentNestedDrag() }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentHostHasBackSwipe by rememberUpdatedState(hostHasBackSwipe)
    val connection =
        remember(state, drive, nested, isLtr, maxFlingVelocity, turnVelocity, slop) {
            object : NestedScrollConnection {
                private fun settleVelocity(velocity: Float): Float =
                    if (nested.startsOnSwitch) raftSegmentSwitchSwipeVelocity(nested.travelled, velocity, slop * 2, turnVelocity) else velocity

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    // A gesture the segments own keeps its fling leftovers too: passed on, the
                    // enclosing pager's own hand-over would end the child's fling with an exception.
                    if (source != NestedScrollSource.UserInput) return if (nested.owned) Offset(available.x, 0f) else Offset.Zero
                    val taken =
                        nested.onLeftover(
                            dx = available.x,
                            isLtr = isLtr,
                            hostHasBackSwipe = currentHostHasBackSwipe,
                            canGoBackward = state.canScrollBackward,
                            canGoForward = state.canScrollForward,
                        ) { forwardDelta ->
                            drive.start()
                            drive.drag(forwardDelta)
                        }
                    return Offset(taken, 0f)
                }

                // The child at its end has nothing to fling: the segments take the whole fling and
                // settle with it, before the child (or anything above) may spend it.
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!nested.owned) return Velocity.Zero
                    if (nested.drives) {
                        drive.release(settleVelocity(raftForwardFlingVelocity(available.x, isLtr, maxFlingVelocity)))
                        nested.reset()
                    }
                    return Velocity(available.x, 0f)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    // Settles a drive that no pre-fling ended (it always should).
                    if (nested.drives) {
                        drive.release(settleVelocity(raftForwardFlingVelocity(available.x, isLtr, maxFlingVelocity)))
                        nested.reset()
                    }
                    return Velocity.Zero
                }
            }
        }
    return this
        .onGloballyPositioned { coordinates[0] = it }
        .raftBackSwipeRegion(RaftBackSwipeEdgeWidth) { state.canScrollBackward }
        // The enclosing tab pager waits until this has claimed or released the drag.
        .raftClaimsHorizontalDragFirst { _, _ -> slop }
        .raftYieldingScrollSemantics(state, isLtr, scope, currentFling)
        .nestedScroll(connection)
        .pointerInput(state, drive, isLtr, hostHasBackSwipe, edgePx, outerDrags) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A drive whose child never flung (cancelled) still settles on a page.
                if (drive.active) drive.release(0f)
                val startsInLeadingEdge = raftStartsInLeadingEdge(down.position.x, size.width.toFloat(), edgePx, isLtr)
                val startsOnSwitch = down.position.y < switchHeight()
                nested.reset(startsInLeadingEdge, startsOnSwitch)
                // Content that decides on horizontal drags itself (registered with the tab pager's
                // host, as this is) gets to first; this element's own registration makes it slop + 1.
                val root = coordinates[0]?.takeIf { it.isAttached }
                val deferred = if (outerDrags == null || root == null) 0f else outerDrags.claimDistanceAt(root.localToRoot(down.position))
                val touchSlop = raftDeferredTouchSlop(viewConfiguration.touchSlop, deferred)
                val tracker = VelocityTracker()
                tracker.addPointerInputChange(down)
                var overSlop = 0f
                var claimed = false
                while (!claimed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed) return@awaitEachGesture
                    // A second finger, or a child (a list's scroll, a horizontal scroller) took the
                    // drag: not this pager's; a scroller's leftover comes through nested scrolling.
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    tracker.addPointerInputChange(change)
                    val total = change.position - down.position
                    when (
                        raftSegmentedPagerDragDecision(
                            dxPx = total.x,
                            dyPx = total.y,
                            touchSlopPx = touchSlop,
                            isLtr = isLtr,
                            hostHasBackSwipe = hostHasBackSwipe,
                            startsInLeadingEdge = startsInLeadingEdge,
                            canGoBackward = state.canScrollBackward,
                            canGoForward = state.canScrollForward,
                        )
                    ) {
                        RaftPagingDragDecision.Undecided -> Unit
                        RaftPagingDragDecision.Release -> return@awaitEachGesture
                        RaftPagingDragDecision.Claim -> {
                            claimed = true
                            overSlop = raftOverSlopPx(total.x, touchSlop)
                            change.consume()
                        }
                    }
                }
                nested.claimedByPointer()
                drive.start()
                var travelled = raftForwardScrollDelta(overSlop, isLtr)
                if (overSlop != 0f) drive.drag(travelled)
                var finalVelocity = 0f
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPointerInputChange(change)
                        if (!change.pressed) {
                            val velocity =
                                raftForwardFlingVelocity(
                                    tracker.calculateVelocity().x,
                                    isLtr,
                                    viewConfiguration.maximumFlingVelocity,
                                )
                            // From the switch, past 2x the slop of the whole swipe: the next segment.
                            finalVelocity =
                                if (startsOnSwitch) {
                                    raftSegmentSwitchSwipeVelocity(travelled + touchSlop * kotlin.math.sign(travelled), velocity, viewConfiguration.touchSlop * 2, turnVelocity)
                                } else {
                                    velocity
                                }
                            change.consume()
                            break
                        }
                        val dx = change.position.x - change.previousPosition.x
                        if (dx != 0f) {
                            val forwardDelta = raftForwardScrollDelta(dx, isLtr)
                            travelled += forwardDelta
                            drive.drag(forwardDelta)
                        }
                        change.consume()
                    }
                } finally {
                    // Also on cancellation (pointer input reset): the scroll must end and settle on
                    // a segment, or it would hold the scroll mutex at UserInput priority.
                    drive.release(finalVelocity)
                }
            }
        }
}
