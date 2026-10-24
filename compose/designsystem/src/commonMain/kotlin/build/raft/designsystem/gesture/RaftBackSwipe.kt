package build.raft.designsystem.gesture

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Content of one page that can take a back swipe away from the host's full-page interactive pop
 * (iOS: `UINavigationController+FDFullscreenPopGesture`).
 *
 * Compose content has no `UIScrollView`, so the native back-swipe delegate cannot see whether a
 * Compose pager, scroller or custom drag would consume a back-direction drag. Content registers
 * here; the native host asks [contentConsumesBackSwipeAt] with the drag's start point when the pop
 * recogniser is about to begin and lets the content keep the drag when the answer is true.
 *
 * - Horizontal scrollers: [rememberRaftBackSwipeScroll] (consumes while not at their start).
 * - Custom horizontal drags (seek bars, drawing surfaces, pan, galleries): [raftKeepsBackSwipe] /
 *   [raftBackSwipeRegion] on their bounds.
 * - Overlays (modals, menus, dialogs, enabled overlay back handlers): [RaftBlockBackSwipe] keeps
 *   every drag on the page, so a right swipe never pops the page underneath an open overlay.
 *
 * Provided per page by the host through [LocalRaftBackSwipeRegions]. Hosts without a content back
 * swipe (Android: the system back gesture is an OS edge gesture Compose cannot block) provide none,
 * which leaves all content exactly as it was. Main thread only.
 */
class RaftBackSwipeRegions {
    private val regions = mutableListOf<Region>()

    /** Pixels per host point (`LocalDensity.density` of the page root); the host keeps it current. */
    var density: Float = 1f

    /**
     * One registered region. [bounds] (pixels relative to the page's Compose root) is evaluated at
     * query time, so a moved or detached node never reports a stale rectangle; null means "not on
     * screen". A [wholePage] region matches every point.
     */
    class Region internal constructor(
        internal val bounds: () -> Rect?,
        internal val consumesBackSwipe: () -> Boolean,
        internal val wholePage: Boolean,
    )

    fun register(bounds: () -> Rect?, consumesBackSwipe: () -> Boolean): Region =
        Region(bounds, consumesBackSwipe, wholePage = false).also { regions += it }

    /** A region covering the whole page that keeps every back drag (an open overlay). */
    fun registerWholePage(): Region = Region({ null }, { true }, wholePage = true).also { regions += it }

    fun unregister(region: Region) {
        regions.remove(region)
    }

    /** Registered regions (tests: registration follows attach / dispose). */
    internal val regionCount: Int get() = regions.size

    /**
     * True when a back-direction drag starting at ([xPx], [yPx]) (pixels in the page root) would be
     * consumed by registered content.
     */
    fun contentConsumesBackSwipeAt(xPx: Float, yPx: Float): Boolean =
        raftBackSwipeConsumedAt(
            Offset(xPx, yPx),
            regions.map { RaftBackSwipeRegionSnapshot(it.bounds, it.consumesBackSwipe, it.wholePage) },
        )

    /** [contentConsumesBackSwipeAt] for a point in host points (iOS `CGPoint` in the page view). */
    fun contentConsumesBackSwipeAtPoint(xPt: Double, yPt: Double): Boolean =
        contentConsumesBackSwipeAt((xPt * density).toFloat(), (yPt * density).toFloat())
}

/** The page's back-swipe regions, or null when the host has no full-page back swipe. */
val LocalRaftBackSwipeRegions = staticCompositionLocalOf<RaftBackSwipeRegions?> { null }

/**
 * False inside kept-alive inactive content (a hidden app-shell tab, an inactive child page): its
 * regions stay registered but never report, since such content may keep its last bounds while it
 * is not placed. Shared's `RaftProvidePageActive` provides it alongside `LocalRaftPageActive`.
 */
val LocalRaftBackSwipeActive = compositionLocalOf { true }

/** A region as seen by the hit test. */
internal class RaftBackSwipeRegionSnapshot(
    val bounds: () -> Rect?,
    val consumesBackSwipe: () -> Boolean,
    val wholePage: Boolean = false,
)

/**
 * Pure hit test: any region containing [point] that currently consumes a back swipe keeps the drag
 * (nested content: an inner row away from its start inside a pager on its first page still keeps
 * it). Regions without bounds (never placed, detached) or empty bounds (clipped away, like an
 * off-screen pager page) never match; whole-page regions always do.
 */
internal fun raftBackSwipeConsumedAt(point: Offset, regions: List<RaftBackSwipeRegionSnapshot>): Boolean =
    regions.any { region ->
        if (!region.consumesBackSwipe()) return@any false
        if (region.wholePage) return@any true
        val bounds = region.bounds() ?: return@any false
        !bounds.isEmpty && bounds.contains(point)
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
    val forward = if (isLtr) dxPx < 0f else dxPx > 0f
    return if (forward && canScrollForward) RaftBackSwipeYieldDecision.ClaimForward else RaftBackSwipeYieldDecision.Release
}

/** Scroll delta (positive = forward) for a horizontal finger movement of [dxPx]. */
internal fun raftForwardScrollDelta(dxPx: Float, isLtr: Boolean): Float = if (isLtr) -dxPx else dxPx

/** The finger movement past the slop at the moment a drag is claimed (applied so content does not lag). */
internal fun raftOverSlopPx(dxPx: Float, touchSlopPx: Float): Float = dxPx - sign(dxPx) * touchSlopPx

/** Forward scroll velocity for a horizontal finger velocity, clamped to [maximumFlingVelocity]. */
internal fun raftForwardFlingVelocity(fingerVelocityX: Float, isLtr: Boolean, maximumFlingVelocity: Float): Float =
    raftForwardScrollDelta(fingerVelocityX, isLtr).coerceIn(-maximumFlingVelocity, maximumFlingVelocity)

/**
 * Whether a horizontal scroller should hand its start-edge back drags to the host: only when the
 * host has a content back swipe and the scroller is at its start.
 */
internal fun raftBackSwipeYieldsAtStart(hostHasBackSwipe: Boolean, canScrollBackward: Boolean): Boolean =
    hostHasBackSwipe && !canScrollBackward

/**
 * Wiring for one horizontal scroller (pager, `LazyRow`, `horizontalScroll`) on a page with a
 * full-page back swipe. Pass [userScrollEnabled] to the scroller and put [modifier] on it (or on a
 * parent that spans it). Read both in composition: they change when the scroller leaves its start.
 */
class RaftBackSwipeScroll internal constructor(
    private val yieldingState: State<Boolean>,
    private val baseModifier: Modifier,
    private val yieldingSemantics: Modifier,
) {
    /**
     * False while the scroller is at its start on a back-swipe host: its own drag handling would
     * consume (rubber-band) a back-direction drag, which makes the Compose touch recogniser win
     * against the native pop. Forward drags are then driven by [modifier] instead.
     */
    val userScrollEnabled: Boolean get() = !yieldingState.value

    /**
     * Registers the scroller and drives forward drags at its start. While the scroller's own
     * scrolling is disabled it also carries the scroll semantics (accessibility scroll actions) the
     * disabled scroller no longer exposes.
     */
    val modifier: Modifier get() = if (yieldingState.value) baseModifier.then(yieldingSemantics) else baseModifier
}

/**
 * Makes the horizontal scroller backed by [state] cooperate with the host's full-page back swipe:
 *
 * - away from its start it keeps every drag and tells the host so (the pop does not begin);
 * - at its start a back-direction drag is left unconsumed so the host pops the page, while a
 *   forward drag still scrolls (driven here, then settled with [flingBehavior], e.g. the pager's
 *   snap fling).
 *
 * Known limitation: drags driven here at the start are not dispatched through nested scrolling,
 * so a horizontally scrolling parent does not receive their leftovers (no such nesting exists on
 * pushed pages today).
 *
 * On hosts without a content back swipe this is inert: [RaftBackSwipeScroll.userScrollEnabled]
 * stays true and the modifier is empty.
 *
 * Pass the scroller's [nestedDrags] host when its content may claim horizontal drags itself
 * ([raftClaimsHorizontalDragFirst]): the forward drag driven here then waits for that content to decide,
 * exactly like the scroller's own drag does.
 */
@Composable
fun rememberRaftBackSwipeScroll(
    state: ScrollableState,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    enabled: Boolean = true,
    nestedDrags: RaftNestedHorizontalDragHost? = null,
): RaftBackSwipeScroll {
    val regions = LocalRaftBackSwipeRegions.current
    val hostHasBackSwipe = regions != null && enabled
    val yielding = remember(state, hostHasBackSwipe) {
        derivedStateOf { raftBackSwipeYieldsAtStart(hostHasBackSwipe, state.canScrollBackward) }
    }
    if (!hostHasBackSwipe) return remember(yielding) { RaftBackSwipeScroll(yielding, Modifier, Modifier) }
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()
    val currentFling = rememberUpdatedState(flingBehavior)
    // Remembered: a new pointerInput lambda would reset the handler and cancel a drag in flight
    // when the page recomposes (the yielding flag flips as soon as the drag leaves the start).
    return remember(state, isLtr, yielding, scope, nestedDrags) {
        RaftBackSwipeScroll(
            yieldingState = yielding,
            baseModifier = Modifier
                .raftBackSwipeRegion { state.canScrollBackward }
                .raftForwardDragAtStart(state, isLtr, yielding, scope, currentFling, nestedDrags),
            yieldingSemantics = Modifier.raftYieldingScrollSemantics(state, isLtr, scope, currentFling),
        )
    }
}

/**
 * At the start of [state] (while [yielding]), claims forward horizontal drags and drives [state]
 * with them, settling with [fling] on release. Back-direction and vertical drags are never consumed.
 * Over [nestedDrags] content the claim waits for the slop the content asked for.
 */
private fun Modifier.raftForwardDragAtStart(
    state: ScrollableState,
    isLtr: Boolean,
    yielding: State<Boolean>,
    scope: CoroutineScope,
    fling: State<FlingBehavior>,
    nestedDrags: RaftNestedHorizontalDragHost?,
): Modifier = pointerInput(state, isLtr, nestedDrags) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        // Decided per gesture: away from the start the scroller's own handling is enabled.
        if (!yielding.value) return@awaitEachGesture
        // Content that decides on horizontal drags itself (an Activity row) gets to first.
        val slop = nestedDrags?.touchSlop(viewConfiguration.touchSlop) ?: viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var overSlop = 0f
        var claimed = false
        while (!claimed) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            tracker.addPointerInputChange(change)
            val total = change.position - down.position
            when (raftBackSwipeYieldDecision(total.x, total.y, slop, isLtr, state.canScrollForward)) {
                RaftBackSwipeYieldDecision.Undecided -> Unit
                RaftBackSwipeYieldDecision.Release -> return@awaitEachGesture
                RaftBackSwipeYieldDecision.ClaimForward -> {
                    claimed = true
                    overSlop = raftOverSlopPx(total.x, slop)
                    change.consume()
                }
            }
        }
        val deltas = Channel<Float>(Channel.UNLIMITED)
        val velocities = Channel<Float>(1)
        if (overSlop != 0f) deltas.trySend(raftForwardScrollDelta(overSlop, isLtr))
        scope.launch {
            state.scroll(MutatePriority.UserInput) {
                for (delta in deltas) scrollBy(delta)
                val velocity = velocities.tryReceive().getOrNull() ?: 0f
                with(fling.value) { performFling(velocity) }
            }
        }
        var finalVelocity = 0f
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPointerInputChange(change)
                if (!change.pressed) {
                    finalVelocity = raftForwardFlingVelocity(
                        tracker.calculateVelocity().x,
                        isLtr,
                        viewConfiguration.maximumFlingVelocity,
                    )
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
 * Scroll semantics for a scroller whose own scrolling is disabled at its start (a disabled
 * `LazyRow`/pager drops its scroll actions): VoiceOver scrolling still moves it, then settles.
 */
internal fun Modifier.raftYieldingScrollSemantics(
    state: ScrollableState,
    isLtr: Boolean,
    scope: CoroutineScope,
    fling: State<FlingBehavior>,
): Modifier = semantics {
    horizontalScrollAxisRange = ScrollAxisRange(
        value = { if (state.canScrollBackward) 1f else 0f },
        maxValue = { if (state.canScrollForward) 2f else if (state.canScrollBackward) 1f else 0f },
        reverseScrolling = !isLtr,
    )
    scrollBy { x, _ ->
        val delta = if (isLtr) x else -x
        if (delta == 0f) return@scrollBy false
        scope.launch {
            state.animateScrollBy(delta)
            state.scroll { with(fling.value) { performFling(0f) } }
        }
        true
    }
}

/**
 * Registers the element this modifier is on as content that keeps back-direction drags while
 * [consumesBackSwipe] is true (e.g. an image gallery past its first image). Bounds are read when
 * the host asks, from the node's current layout coordinates. Inert when the host has no content
 * back swipe.
 */
fun Modifier.raftBackSwipeRegion(consumesBackSwipe: () -> Boolean): Modifier =
    this then RaftBackSwipeRegionElement(consumesBackSwipe)

/**
 * [raftBackSwipeRegion] whose reported bounds leave out the element's leading [edgeWidth] strip, so
 * a back drag starting there still reaches the host's back swipe (swipe-paged content: a gallery
 * past its first image pages back from its body but pops the page from its leading edge; an
 * Activity row whose own swipe toward the end keeps the drag everywhere but its leading edge).
 */
fun Modifier.raftBackSwipeRegion(edgeWidth: Dp, consumesBackSwipe: () -> Boolean): Modifier =
    this then RaftBackSwipeRegionElement(consumesBackSwipe, leadingEdgeExclusion = edgeWidth)

/**
 * [bounds] without its leading [edgePx] strip (left in LTR, right in RTL); empty when the strip
 * covers it.
 */
internal fun raftTrimLeadingEdge(bounds: Rect, edgePx: Float, isLtr: Boolean): Rect {
    if (edgePx <= 0f) return bounds
    if (edgePx >= bounds.width) return Rect(bounds.left, bounds.top, bounds.left, bounds.bottom)
    return if (isLtr) {
        Rect(bounds.left + edgePx, bounds.top, bounds.right, bounds.bottom)
    } else {
        Rect(bounds.left, bounds.top, bounds.right - edgePx, bounds.bottom)
    }
}

/**
 * The element this modifier is on keeps every back-direction drag that starts on it while
 * [enabled]: custom horizontal drag handlers (seek bars, drawing / pan surfaces, resize handles,
 * a focused text field's cursor and selection drags) that would otherwise race the host's pop.
 */
fun Modifier.raftKeepsBackSwipe(enabled: Boolean = true): Modifier =
    if (enabled) raftBackSwipeRegion(RaftAlwaysConsumes) else this

private val RaftAlwaysConsumes: () -> Boolean = { true }

private data class RaftBackSwipeRegionElement(
    val consumesBackSwipe: () -> Boolean,
    val leadingEdgeExclusion: Dp = 0.dp,
) : ModifierNodeElement<RaftBackSwipeRegionNode>() {
    override fun create(): RaftBackSwipeRegionNode = RaftBackSwipeRegionNode(consumesBackSwipe, leadingEdgeExclusion)

    override fun update(node: RaftBackSwipeRegionNode) {
        node.consumesBackSwipe = consumesBackSwipe
        node.leadingEdgeExclusion = leadingEdgeExclusion
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "raftBackSwipeRegion"
    }
}

private class RaftBackSwipeRegionNode(
    var consumesBackSwipe: () -> Boolean,
    var leadingEdgeExclusion: Dp,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var registry: RaftBackSwipeRegions? = null
    private var region: RaftBackSwipeRegions.Region? = null

    override fun onAttach() {
        val regions = currentValueOf(LocalRaftBackSwipeRegions) ?: return
        registry = regions
        region = regions.register(bounds = ::currentBounds, consumesBackSwipe = ::currentlyConsumes)
    }

    override fun onDetach() {
        region?.let { registry?.unregister(it) }
        region = null
        registry = null
        coordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    private fun currentBounds(): Rect? {
        if (!isAttached) return null
        val current = coordinates?.takeIf { it.isAttached } ?: return null
        val bounds = current.boundsInRoot()
        if (leadingEdgeExclusion <= 0.dp) return bounds
        val edgePx = with(currentValueOf(LocalDensity)) { leadingEdgeExclusion.toPx() }
        return raftTrimLeadingEdge(bounds, edgePx, isLtr = currentValueOf(LocalLayoutDirection) == LayoutDirection.Ltr)
    }

    private fun currentlyConsumes(): Boolean =
        isAttached && currentValueOf(LocalRaftBackSwipeActive) && consumesBackSwipe()
}

/**
 * While composed with [enabled], keeps every back-direction drag on the page (anywhere): put it in
 * overlays (modal dialogs, menus, full-screen previews, enabled overlay back handlers) so a right
 * swipe never pops the page underneath an open overlay. Inactive kept-alive content never blocks.
 */
@Composable
fun RaftBlockBackSwipe(enabled: Boolean = true) {
    val regions = LocalRaftBackSwipeRegions.current ?: return
    if (!enabled || !LocalRaftBackSwipeActive.current) return
    DisposableEffect(regions) {
        val region = regions.registerWholePage()
        onDispose { regions.unregister(region) }
    }
}
