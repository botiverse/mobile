package build.raft.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import build.raft.designsystem.tokens.RaftFade
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * raft-design spacing.md › Edges fade (raft-ui `@utility mask-fade-x`, FitToolbar
 * `useScrollGroup` `data-overflow`): a sideways-scrolling strip fades its content out over
 * [width] (`fade-edge`, 24dp) at each edge with content hidden past it, and only there: an
 * edge it has scrolled away from (start), an edge with more to reveal (end), both, or
 * neither when it all fits. A mask, not a colour gradient: the content itself turns
 * transparent, so whatever is behind it (an applied filter's wash, a card, a hover) shows
 * through without a band.
 *
 * Put it on the clipping scroller, before its `horizontalScroll([scroll])`, so the mask
 * covers the viewport rather than the scrolled content. The fade's sides follow the
 * scroll in the draw phase (no recomposition while scrolling).
 */
fun Modifier.raftScrollEdgeFade(scroll: ScrollState, width: Dp = RaftFade.Edge): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val full = width.toPx().coerceAtMost(size.width / 2f)
            // web: start while scrollLeft > 1, end while scrollLeft < max - 1.
            val startWidth = if (scroll.value > 1) full else 0f
            val endWidth = if (scroll.value < scroll.maxValue - 1) full else 0f
            val rtl = layoutDirection == LayoutDirection.Rtl
            val left = if (rtl) endWidth else startWidth
            val right = if (rtl) startWidth else endWidth
            if (left > 0f) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = left),
                    size = Size(left, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (right > 0f) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - right, endX = size.width),
                    topLeft = Offset(size.width - right, 0f),
                    size = Size(right, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * An item of a [raftScrollEdgeFade] strip that scrolls itself into view when it turns [active]
 * (web FitToolbar: an applied filter, `data-active`), stopping [padding]
 * (`scroll-padding-inline`, the fade's 24dp) clear of the edge so the fade never covers it.
 * It also reveals when its size changes while active (a filter's label becoming its new
 * value). Not on first show: a strip that opens with several filters applied stays at its
 * start rather than scrolling to whichever item composed last. The items of a
 * [RaftScrollIntoViewGroup] follow raft-ui's newer rule instead, which also shows the first
 * applied filter of a strip that opens with one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.raftScrollIntoViewWhen(active: Boolean, padding: Dp = RaftFade.Edge): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val seen = remember { RaftRevealSeen(active) }
    val pad = with(LocalDensity.current) { padding.toPx() }
    LaunchedEffect(active, size) {
        val becameActive = active && !seen.active
        val resized = active && seen.size != IntSize.Zero && size != seen.size
        seen.active = active
        if (size != IntSize.Zero) seen.size = size
        if (size != IntSize.Zero && (becameActive || resized)) {
            requester.bringIntoView(Rect(-pad, 0f, size.width + pad, size.height.toFloat()))
        }
    }
    return this.onSizeChanged { size = it }.bringIntoViewRequester(requester)
}

/** What [raftScrollIntoViewWhen] last saw: whether the item was active, and its measured size. */
private class RaftRevealSeen(var active: Boolean, var size: IntSize = IntSize.Zero)

/**
 * A [raftScrollEdgeFade] strip whose items keep an applied one in view as raft-ui's FitToolbar
 * does (`useScrollGroup` since raft-ui 0.84; the web's search filters): when the strip first
 * shows, the first item that is active then comes into view and the others stay where they are;
 * after that, the item that turns active (or is added to the strip active); and that item again
 * when it or the strip changes size, unless the person has scrolled the strip since. The strip
 * jumps there, with no animation, the item's `padding` clear of the edge it was past so the fade
 * never covers it: an item past the start edge lines up its start, one past the end edge its end.
 *
 * Remember one per strip with [rememberRaftScrollIntoViewGroup] in the composable that composes
 * the strip's items (not in a subcomposition), put [raftScrollIntoViewGroup] on the scroller
 * before its `horizontalScroll`, and give the group to each item's [raftScrollIntoViewWhen].
 */
@Stable
class RaftScrollIntoViewGroup internal constructor(private val scroll: ScrollState) {
    /** The scroller's own box, its viewport ([raftScrollIntoViewGroup]). */
    internal var viewport: LayoutCoordinates? = null

    /** The viewport's width: when it changes, the item in view is kept there. */
    internal var viewportWidth by mutableIntStateOf(0)

    /** Set once the strip has first shown: an item composed after that was added to the strip. */
    internal var shown = false
    private var firstShowTaken = false

    /** The item kept in view: the one last brought into view. */
    private var target: Any? = null

    /** Where the last reveal left the scroll: the person has scrolled the strip when it moved since. */
    private var placed = 0

    /**
     * Whether an item that is [active] when it is composed counts as just turned active: the
     * first active item of the strip's first show, and an item added to the strip later.
     */
    internal fun revealsAsComposed(active: Boolean): Boolean =
        when {
            !active -> false
            shown -> true
            firstShowTaken -> false
            else -> {
                firstShowTaken = true
                true
            }
        }

    /** Whether [item] is the one kept in view and the person has not scrolled the strip since it came into view. */
    internal fun keepsInView(item: Any): Boolean =
        target === item && !scroll.isScrollInProgress && abs(scroll.value - placed) <= 1

    /**
     * web FitToolbar `reveal`: scrolls the strip so the item laid out at [coordinates] is in view,
     * [padding] px clear of the edge it was past, and keeps [item] in view from now on.
     */
    internal suspend fun reveal(item: Any, coordinates: LayoutCoordinates, padding: Float, rtl: Boolean) {
        target = item
        val view = viewport?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        val box = view.localBoundingBoxOf(coordinates, clipBounds = false)
        val width = view.size.width.toFloat()
        // How far the content has to move left to bring the item in (negative: right).
        val shift =
            when {
                box.left - padding < 0f -> box.left - padding
                box.right + padding > width -> box.right + padding - width
                else -> 0f
            }
        // The scroll value grows as the content moves left (right in RTL); the state keeps it in range.
        if (shift != 0f) scroll.scrollTo(scroll.value + (if (rtl) -shift else shift).roundToInt())
        placed = scroll.value
    }
}

/** A [RaftScrollIntoViewGroup] for the strip that [scroll] scrolls. */
@Composable
fun rememberRaftScrollIntoViewGroup(scroll: ScrollState): RaftScrollIntoViewGroup {
    val group = remember(scroll) { RaftScrollIntoViewGroup(scroll) }
    // Runs once the composition that composed the strip's items is applied: an item composed
    // after this one was added to the strip.
    SideEffect { group.shown = true }
    return group
}

/** The scroller of a [RaftScrollIntoViewGroup] strip: put it before the scroller's `horizontalScroll`. */
fun Modifier.raftScrollIntoViewGroup(group: RaftScrollIntoViewGroup): Modifier =
    onPlaced { group.viewport = it }.onSizeChanged { group.viewportWidth = it.width }

/**
 * An item of a [RaftScrollIntoViewGroup] strip: it comes into view, [padding] clear of the edge,
 * when the group says (the first active item when the strip first shows, an item that turns
 * [active], and the item in view again when it or the strip changes size).
 */
@Composable
fun Modifier.raftScrollIntoViewWhen(
    active: Boolean,
    group: RaftScrollIntoViewGroup,
    padding: Dp = RaftFade.Edge,
): Modifier {
    // An item active as it is composed counts as just turned active only when the group says so.
    val item = remember(group) { RaftRevealItem(active = active && !group.revealsAsComposed(active)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pad = with(LocalDensity.current) { padding.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val viewportWidth = group.viewportWidth
    LaunchedEffect(group, active, size, viewportWidth) {
        // Not laid out yet: decide once it is, so a reveal is not spent before it can scroll.
        val coordinates = item.coordinates
        if (size == IntSize.Zero || coordinates == null) return@LaunchedEffect
        val becameActive = active && !item.active
        item.active = active
        if (becameActive || (active && group.keepsInView(item))) group.reveal(item, coordinates, pad, rtl)
    }
    return this.onSizeChanged { size = it }.onPlaced { item.coordinates = it }
}

/** What an item of a [RaftScrollIntoViewGroup] last saw: whether it was active, and where it is laid out. */
private class RaftRevealItem(var active: Boolean) {
    var coordinates: LayoutCoordinates? = null
}
