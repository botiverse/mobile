package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Horizontal content of one page that can take a back swipe away from the host's full-page
 * interactive pop (iOS: `UINavigationController+FDFullscreenPopGesture`).
 *
 * Compose content has no `UIScrollView`, so the native back-swipe delegate cannot see whether a
 * Compose pager or scroller would consume a back-direction drag. Pages register their horizontal
 * scrollers here ([rememberRaftBackSwipeScroll], [rememberRaftBackSwipeRegion]); the native host
 * asks [contentConsumesBackSwipeAt] with the drag's start point when the pop recogniser is about to
 * begin and lets the content keep the drag when the answer is true.
 *
 * Provided per page by the host through [LocalRaftBackSwipeRegions]. Hosts without a content back
 * swipe (Android: the system back gesture is an OS edge gesture Compose cannot block) provide none,
 * which leaves every scroller exactly as it was. Main thread only.
 */
class RaftBackSwipeRegions {
    private val regions = mutableListOf<Region>()

    /** Pixels per host point (`LocalDensity.density` of the page root); the host keeps it current. */
    var density: Float = 1f

    /** One registered region; [bounds] is in pixels relative to the page's Compose root. */
    class Region internal constructor(internal val consumesBackSwipe: () -> Boolean) {
        var bounds: Rect = Rect.Zero
            internal set
    }

    fun register(consumesBackSwipe: () -> Boolean): Region = Region(consumesBackSwipe).also { regions += it }

    fun unregister(region: Region) {
        regions.remove(region)
    }

    /**
     * True when a back-direction drag starting at ([xPx], [yPx]) (pixels in the page root) would be
     * consumed by registered content (a pager past its first page, a scroller away from its start).
     */
    fun contentConsumesBackSwipeAt(xPx: Float, yPx: Float): Boolean =
        raftBackSwipeConsumedAt(Offset(xPx, yPx), regions.map { RaftBackSwipeRegionSnapshot(it.bounds, it.consumesBackSwipe) })

    /** [contentConsumesBackSwipeAt] for a point in host points (iOS `CGPoint` in the page view). */
    fun contentConsumesBackSwipeAtPoint(xPt: Double, yPt: Double): Boolean =
        contentConsumesBackSwipeAt((xPt * density).toFloat(), (yPt * density).toFloat())
}

/** The page's back-swipe regions, or null when the host has no full-page back swipe. */
val LocalRaftBackSwipeRegions = staticCompositionLocalOf<RaftBackSwipeRegions?> { null }

/** A region as seen by the hit test: its bounds and whether it would consume a back swipe now. */
internal class RaftBackSwipeRegionSnapshot(val bounds: Rect, val consumesBackSwipe: () -> Boolean)

/**
 * Pure hit test: any region containing [point] that currently consumes a back swipe keeps the drag
 * (nested scrollers: an inner chip row scrolled away from its start inside a pager on its first
 * page still keeps it). Empty bounds (unplaced, or clipped away like an off-screen pager page)
 * never match.
 */
internal fun raftBackSwipeConsumedAt(point: Offset, regions: List<RaftBackSwipeRegionSnapshot>): Boolean =
    regions.any { region ->
        !region.bounds.isEmpty && region.bounds.contains(point) && region.consumesBackSwipe()
    }

/** Decision of the start-of-content drag handler for one gesture. */
internal enum class RaftBackSwipeYieldDecision {
    /** Not enough movement yet. */
    Undecided,

    /** Forward (next page / further content) horizontal drag: drive the scroller ourselves. */
    ClaimForward,

    /** Back-direction or vertical drag, or nothing to scroll: consume nothing for this gesture. */
    Release,
}

/**
 * Pure classification for a drag that starts while a yielding scroller is at its start. The back
 * direction is physically rightward in LTR and leftward in RTL; such drags are released unconsumed
 * so the host's full-page back swipe takes them. Forward drags are claimed only when the scroller
 * can move forward. Vertical-dominant drags are released to the content (lists) underneath.
 */
internal fun raftBackSwipeYieldDecision(
    dxPx: Float,
    dyPx: Float,
    touchSlopPx: Float,
    isLtr: Boolean,
    canScrollForward: Boolean,
): RaftBackSwipeYieldDecision {
    val adx = abs(dxPx)
    val ady = abs(dyPx)
    if (adx < touchSlopPx && ady < touchSlopPx) return RaftBackSwipeYieldDecision.Undecided
    if (ady > adx) return RaftBackSwipeYieldDecision.Release
    if (adx < touchSlopPx) return RaftBackSwipeYieldDecision.Undecided
    val forward = if (isLtr) dxPx < 0f else dxPx > 0f
    return if (forward && canScrollForward) RaftBackSwipeYieldDecision.ClaimForward else RaftBackSwipeYieldDecision.Release
}

/** Scroll delta (positive = forward) for a horizontal finger movement of [dxPx]. */
internal fun raftForwardScrollDelta(dxPx: Float, isLtr: Boolean): Float = if (isLtr) -dxPx else dxPx

/**
 * Whether a horizontal scroller should hand its start-edge back drags to the host: only when the
 * host has a content back swipe and the scroller is at its start.
 */
internal fun raftBackSwipeYieldsAtStart(hostHasBackSwipe: Boolean, canScrollBackward: Boolean): Boolean =
    hostHasBackSwipe && !canScrollBackward

/**
 * Wiring for one horizontal scroller (pager, `LazyRow`, `horizontalScroll`) on a page with a
 * full-page back swipe. Pass [userScrollEnabled] to the scroller and put [modifier] on it (or on a
 * parent that spans it).
 */
class RaftBackSwipeScroll internal constructor(
    private val yieldingState: State<Boolean>,
    /** Registers the region and, at the start, drives forward drags in place of the scroller. */
    val modifier: Modifier,
) {
    /**
     * False while the scroller is at its start on a back-swipe host: its own drag handling would
     * consume (rubber-band) a back-direction drag, which makes the Compose touch recogniser win
     * against the native pop. Forward drags are then driven by [modifier] instead.
     */
    val userScrollEnabled: Boolean get() = !yieldingState.value
}

/**
 * Makes the horizontal scroller backed by [state] cooperate with the host's full-page back swipe:
 *
 * - away from its start it keeps every drag and tells the host so (the pop does not begin);
 * - at its start a back-direction drag is left unconsumed so the host pops the page, while a
 *   forward drag still scrolls (driven here, then settled with [flingBehavior], e.g. the pager's
 *   snap fling).
 *
 * On hosts without a content back swipe this is inert: [RaftBackSwipeScroll.userScrollEnabled]
 * stays true and the modifier is empty.
 */
@Composable
fun rememberRaftBackSwipeScroll(
    state: ScrollableState,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    enabled: Boolean = true,
): RaftBackSwipeScroll {
    val regions = LocalRaftBackSwipeRegions.current
    val hostHasBackSwipe = regions != null && enabled
    val yielding = remember(state, hostHasBackSwipe) {
        derivedStateOf { raftBackSwipeYieldsAtStart(hostHasBackSwipe, state.canScrollBackward) }
    }
    if (!hostHasBackSwipe) return remember(yielding) { RaftBackSwipeScroll(yielding, Modifier) }
    val regionModifier = rememberRaftBackSwipeRegion { state.canScrollBackward }
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()
    val currentFling = rememberUpdatedState(flingBehavior)
    // Remembered: a new pointerInput lambda would reset the handler and cancel a drag in flight
    // when the page recomposes (the yielding flag flips as soon as the drag leaves the start).
    val dragModifier = remember(state, isLtr, yielding, scope) {
        Modifier.raftForwardDragAtStart(state, isLtr, yielding, scope, currentFling)
    }
    val modifier = remember(regionModifier, dragModifier) { regionModifier.then(dragModifier) }
    return remember(yielding, modifier) { RaftBackSwipeScroll(yielding, modifier) }
}

/**
 * At the start of [state] (while [yielding]), claims forward horizontal drags and drives [state]
 * with them, settling with [fling] on release. Back-direction and vertical drags are never consumed.
 */
private fun Modifier.raftForwardDragAtStart(
    state: ScrollableState,
    isLtr: Boolean,
    yielding: State<Boolean>,
    scope: CoroutineScope,
    fling: State<FlingBehavior>,
): Modifier = pointerInput(state, isLtr) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        // Decided per gesture: away from the start the scroller's own handling is enabled.
        if (!yielding.value) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var claimed = false
        while (!claimed) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            val total = change.position - down.position
            when (raftBackSwipeYieldDecision(total.x, total.y, slop, isLtr, state.canScrollForward)) {
                RaftBackSwipeYieldDecision.Undecided -> Unit
                RaftBackSwipeYieldDecision.Release -> return@awaitEachGesture
                RaftBackSwipeYieldDecision.ClaimForward -> claimed = true
            }
            if (claimed) change.consume()
        }
        val deltas = Channel<Float>(Channel.UNLIMITED)
        val velocities = Channel<Float>(1)
        scope.launch {
            state.scroll(MutatePriority.UserInput) {
                for (delta in deltas) scrollBy(delta)
                val velocity = velocities.tryReceive().getOrNull() ?: 0f
                with(fling.value) { performFling(velocity) }
            }
        }
        val tracker = VelocityTracker()
        var finalVelocity = 0f
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPointerInputChange(change)
                if (!change.pressed) {
                    finalVelocity = raftForwardScrollDelta(tracker.calculateVelocity().x, isLtr)
                    change.consume()
                    break
                }
                val dx = change.position.x - change.previousPosition.x
                if (dx != 0f) deltas.trySend(raftForwardScrollDelta(dx, isLtr))
                change.consume()
            }
        } finally {
            // Also on cancellation (pointer input reset): the scroll block must always end and
            // settle, or it would hold the scroll mutex at UserInput priority.
            velocities.trySend(finalVelocity)
            deltas.close()
        }
    }
}

/**
 * Registers the composable this modifier is placed on as content that keeps back-direction drags
 * while [consumesBackSwipe] is true (e.g. an image gallery past its first image driven by a custom
 * horizontal drag). Inert when the host has no content back swipe.
 */
@Composable
fun rememberRaftBackSwipeRegion(consumesBackSwipe: () -> Boolean): Modifier {
    val regions = LocalRaftBackSwipeRegions.current ?: return Modifier
    val current by rememberUpdatedState(consumesBackSwipe)
    val holder = remember { RaftBackSwipeRegionHolder() }
    // Registered once the composition is committed (never from an abandoned one).
    DisposableEffect(regions, holder) {
        val region = regions.register { current() }
        region.bounds = holder.lastBounds
        holder.region = region
        onDispose {
            regions.unregister(region)
            holder.region = null
        }
    }
    return remember(holder) {
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            holder.lastBounds = bounds
            holder.region?.bounds = bounds
        }
    }
}

private class RaftBackSwipeRegionHolder {
    var region: RaftBackSwipeRegions.Region? = null
    var lastBounds: Rect = Rect.Zero
}
