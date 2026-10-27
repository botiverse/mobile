package build.raft.designsystem.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize

/**
 * Lets content with its own horizontal drag (an Activity row's swipe) decide before the horizontal
 * scroller it sits in (a tab pager) takes the drag.
 *
 * Compose dispatches the Main pass child first, but a scroller claims a drag at one touch slop. Content
 * that claims later than that (the Activity row waits for 2x the slop and |dx| > 2|dy|) never gets the
 * chance: the pager consumes the stream first and the content sees it consumed. On iOS 1.15.5 the whole
 * Activity list slid toward the next filter page instead of the row opening.
 *
 * Content registers with [raftClaimsHorizontalDragFirst], giving the distance it needs to decide at the
 * point where a drag went down. The scroller's host ([rememberRaftNestedHorizontalDragHost]) looks the
 * down point up and, for that gesture only, raises the scroller's touch slop to that distance: the
 * content (which sees each event first) claims or releases before the scroller may move. Elsewhere, and
 * once the content released (a vertical or diagonal drag, a drag toward the end on a closed row), the
 * scroller keeps its own slop beyond the deferral, so page swipes on headers, empty space and
 * non-swiping rows feel as before.
 */
class RaftNestedHorizontalDrags {
    private val regions = mutableListOf<Region>()

    class Region internal constructor(
        internal val bounds: () -> Rect?,
        /** Distance (px) the content needs before deciding, for a down at this root point; 0: none. */
        internal val claimDistanceAt: (Offset) -> Float,
    )

    internal fun register(bounds: () -> Rect?, claimDistanceAt: (Offset) -> Float): Region =
        Region(bounds, claimDistanceAt).also { regions += it }

    internal fun unregister(region: Region) {
        regions.remove(region)
    }

    internal val regionCount: Int get() = regions.size

    /** The largest claim distance of content under [pointInRoot]; 0 when none claims there. */
    fun claimDistanceAt(pointInRoot: Offset): Float =
        raftNestedClaimDistanceAt(pointInRoot, regions.map { RaftNestedDragRegionSnapshot(it.bounds, it.claimDistanceAt) })
}

/** The nested-drag registry of the enclosing horizontal scroller, or null outside one. */
val LocalRaftNestedHorizontalDrags = staticCompositionLocalOf<RaftNestedHorizontalDrags?> { null }

internal class RaftNestedDragRegionSnapshot(
    val bounds: () -> Rect?,
    val claimDistanceAt: (Offset) -> Float,
)

/** Pure lookup: the largest claim distance among regions whose (non-empty) bounds contain [point]. */
internal fun raftNestedClaimDistanceAt(point: Offset, regions: List<RaftNestedDragRegionSnapshot>): Float =
    regions.maxOfOrNull { region ->
        val bounds = region.bounds() ?: return@maxOfOrNull 0f
        if (bounds.isEmpty || !bounds.contains(point)) 0f else region.claimDistanceAt(point).coerceAtLeast(0f)
    } ?: 0f

/**
 * The scroller's touch slop for one gesture: its own [baseSlopPx], or the nested content's
 * [deferredPx] when larger. One pixel more than the content's distance, so the content (dispatched
 * first) has claimed or released when the scroller would cross it.
 */
internal fun raftDeferredTouchSlop(baseSlopPx: Float, deferredPx: Float): Float =
    if (deferredPx > 0f) maxOf(baseSlopPx, deferredPx + 1f) else baseSlopPx

/**
 * Wiring for one horizontal scroller whose content may hold [raftClaimsHorizontalDragFirst] regions.
 *
 * - Put [modifier] on the scroller or a parent spanning it (it records where each gesture went down).
 * - Wrap the scroller in [ProvideScrollerConfiguration] (its own drag reads the deferred slop), and
 *   each page's content in [ProvideContent] (the content gets the registry and the normal slop back).
 * - A custom drag driver on the scroller reads [touchSlop].
 */
class RaftNestedHorizontalDragHost internal constructor(
    val drags: RaftNestedHorizontalDrags,
    private val base: ViewConfiguration,
) {
    private var coordinates: LayoutCoordinates? = null
    private var deferredPx = 0f

    /** The scroller's slop for the current gesture. */
    fun touchSlop(baseSlopPx: Float = base.touchSlop): Float = raftDeferredTouchSlop(baseSlopPx, deferredPx)

    private val scrollerConfiguration: ViewConfiguration =
        object : ViewConfiguration by base {
            override val touchSlop: Float get() = this@RaftNestedHorizontalDragHost.touchSlop(base.touchSlop)
        }

    val modifier: Modifier =
        Modifier
            .onGloballyPositioned { coordinates = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Initial pass: before the scroller (and its content) see the down.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val coords = coordinates?.takeIf { it.isAttached }
                    deferredPx = if (coords == null) 0f else drags.claimDistanceAt(coords.localToRoot(down.position))
                }
            }

    @Composable
    fun ProvideScrollerConfiguration(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalViewConfiguration provides scrollerConfiguration, content = content)
    }

    @Composable
    fun ProvideContent(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalViewConfiguration provides base,
            LocalRaftNestedHorizontalDrags provides drags,
            content = content,
        )
    }
}

/** A [RaftNestedHorizontalDragHost] for the scroller composed here. */
@Composable
fun rememberRaftNestedHorizontalDragHost(): RaftNestedHorizontalDragHost {
    val base = LocalViewConfiguration.current
    return remember(base) { RaftNestedHorizontalDragHost(RaftNestedHorizontalDrags(), base) }
}

/**
 * Registers the element this modifier is on as content that decides on horizontal drags itself before
 * the enclosing horizontal scroller may take them: for a drag that goes down at a local point of an
 * element of a given size, [claimDistancePxAt] returns how far (px) the content needs to decide, or 0 where
 * it never claims (its edge zones). Inert outside a [RaftNestedHorizontalDragHost] or when not [enabled].
 */
fun Modifier.raftClaimsHorizontalDragFirst(
    enabled: Boolean = true,
    claimDistancePxAt: (local: Offset, size: IntSize) -> Float,
): Modifier = if (enabled) this then RaftNestedDragElement(claimDistancePxAt) else this

/**
 * Marks a vertical scroller ([isScrolling]: its `isScrollInProgress`) inside a horizontal scroller: a
 * gesture that goes down on it while it still moves (a fling) only stops it, as on iOS (UIScrollView)
 * and Android (RecyclerView): the enclosing horizontal scroller takes no drag from that gesture
 * (its slop for that gesture is infinite). Its items read [LocalRaftScrollInProgress] (provide it
 * with the same [isScrolling]) so a row that swipes sideways itself lets that gesture go too.
 */
fun Modifier.raftHoldsHorizontalDragsWhileScrolling(isScrolling: () -> Boolean): Modifier =
    raftClaimsHorizontalDragFirst { _, _ -> raftScrollHoldDistance(isScrolling()) }

/**
 * Whether the vertical scroller around this content is moving, read at a gesture's down: content
 * with its own sideways drag claims nothing from a gesture that went down while it was (that touch
 * only stops the scroll). Always false outside a scroller that provides it.
 */
val LocalRaftScrollInProgress = compositionLocalOf<() -> Boolean> { { false } }

/** The claim distance of a vertical scroller: none at rest, all of it while it moves. */
internal fun raftScrollHoldDistance(scrolling: Boolean): Float = if (scrolling) Float.POSITIVE_INFINITY else 0f

private data class RaftNestedDragElement(
    val claimDistancePxAt: (Offset, IntSize) -> Float,
) : ModifierNodeElement<RaftNestedDragNode>() {
    override fun create(): RaftNestedDragNode = RaftNestedDragNode(claimDistancePxAt)

    override fun update(node: RaftNestedDragNode) {
        node.claimDistancePxAt = claimDistancePxAt
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "raftClaimsHorizontalDragFirst"
    }
}

private class RaftNestedDragNode(
    var claimDistancePxAt: (Offset, IntSize) -> Float,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var registry: RaftNestedHorizontalDrags? = null
    private var region: RaftNestedHorizontalDrags.Region? = null

    override fun onAttach() {
        val drags = currentValueOf(LocalRaftNestedHorizontalDrags) ?: return
        registry = drags
        region = drags.register(bounds = ::currentBounds, claimDistanceAt = ::claimDistanceAtRoot)
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
        return coordinates?.takeIf { it.isAttached }?.boundsInRoot()
    }

    private fun claimDistanceAtRoot(pointInRoot: Offset): Float {
        val current = coordinates?.takeIf { isAttached && it.isAttached } ?: return 0f
        val local = current.localPositionOf(current.findRootCoordinates(), pointInRoot)
        return claimDistancePxAt(local, current.size)
    }
}
