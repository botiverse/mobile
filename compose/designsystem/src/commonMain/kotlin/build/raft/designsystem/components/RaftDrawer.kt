package build.raft.designsystem.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * raft-ui Drawer (bottom sheet) holding a panel of the caller's own: web `<DrawerContent>` with
 * a whole panel as its child (the assistant opened from its otter on a phone), where
 * [RaftSheet] is the Drawer with its standard title, body and actions.
 *
 * The window is [RaftSheet]'s: [RaftDrawerSurface] at the bottom of the window over the scrim;
 * Back and a tap on the scrim call [onDismissRequest]; it never goes under the status bar. It
 * moves as raft-ui's drawer (drawer.recipe.ts; Motion "A scrim moves with its surface"): it
 * slides up (240ms, `ease-slide`) once the window has drawn its first frame, with the scrim fading
 * in alongside, and [visible] false slides it back down with the scrim fading out (180ms), then
 * calls [onHidden]: keep it composed from opening until [onHidden].
 *
 * Without a [state] the surface is always 82% of the window tall (web `--drawer-content-height:
 * 82vh`) and does not follow a drag. With a [state] it has raft-ui's snap points (Base UI Drawer
 * `snapPoints`, `swipeDirection="down"`): it opens at [RaftDrawerSnapPoint.Default] (the same 82%),
 * a drag on the grab handle (or on anything the content marks with [raftDrawerDragArea], such as
 * its head) moves it, and on release it settles at the nearest snap point, or the next one in the
 * direction of a fling: up to [RaftDrawerSnapPoint.Full] (the whole window under the status bar),
 * down to the default height, further down closes it ([onDismissRequest]). The scrim fades as it
 * is dragged below the default height (`--drawer-swipe-progress`). The sheet still keeps a close
 * control, Back or a button, never only the gesture (Drawer Accessibility).
 *
 * It is at most [RaftDrawerDefaults.MaxWidth] wide, and [content] fills it under the grab handle
 * ([RaftSheetGrabHandle]): it draws its own head. [fill] is the surface's layer: raft-ui's
 * `layer-popover`, or [RaftDrawerFill.Canvas] for a panel whose head and body sit on the canvas
 * (the assistant's panel), so the grab handle's strip is the same opaque layer as the rest.
 * [clearNavigationBar] false leaves the home indicator and the keyboard to [content] (a
 * conversation whose composer already pads for both); true pads the surface for them.
 * [overlay] is drawn over the whole window, above the scrim and the surface (a notification
 * banner that must stay on top while the drawer is open).
 */
@Composable
fun RaftDrawer(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    clearNavigationBar: Boolean = true,
    visible: Boolean = true,
    onHidden: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    state: RaftDrawerState? = null,
    fill: RaftDrawerFill = RaftDrawerFill.Popover,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftSheetWindow(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        slideMotion = true,
        visible = visible,
        onHidden = onHidden,
        overlay = overlay,
        capHeight = state == null,
        scrimAlpha = { state?.dismissProgress ?: 1f },
    ) { surfaceModifier ->
        RaftDrawerSurfaceImpl(surfaceModifier, clearNavigationBar, fill, state, onDismissRequest, content)
    }
}

/** The layer a [RaftDrawer]'s surface is filled with. */
enum class RaftDrawerFill {
    /** raft-ui's Drawer: `layer-popover`. */
    Popover,

    /** `layer-canvas`: a panel whose head and body are on the canvas fills its whole sheet with it. */
    Canvas,
}

/** The heights a [RaftDrawer] with a [RaftDrawerState] rests at. */
enum class RaftDrawerSnapPoint {
    /** 82% of the window (web `--drawer-content-height: 82vh`), where it opens. */
    Default,

    /** The whole window under the status bar. */
    Full,
}

/**
 * Where a [RaftDrawer] with snap points rests ([snapPoint]) and how far it is dragged. Remember
 * one per opening ([rememberRaftDrawerState]); [animateTo] moves it from code (Back from
 * [RaftDrawerSnapPoint.Full] going back to the default height).
 */
@Stable
class RaftDrawerState(initialSnapPoint: RaftDrawerSnapPoint = RaftDrawerSnapPoint.Default) {
    /** The snap point it rests at (or last rested at, while a drag or a settle is under way). */
    var snapPoint: RaftDrawerSnapPoint by mutableStateOf(initialSnapPoint)
        private set

    /** The visible height in px while a drag or a settle holds it; null at rest. */
    private var heldExtent: Float? by mutableStateOf(null)

    // The window's heights, written by the surface's layout (not snapshot state: nothing reads
    // them to recompose, and the layout that writes them reads [extent] in the same pass).
    internal var defaultPx: Float = 0f
    internal var fullPx: Float = 0f

    internal fun anchorPx(point: RaftDrawerSnapPoint): Float =
        if (point == RaftDrawerSnapPoint.Full) fullPx else defaultPx

    /** How much of the sheet shows, in px: 0 dismissed, [defaultPx], up to [fullPx]. */
    internal val extent: Float get() = heldExtent ?: anchorPx(snapPoint)

    /** 1 at the default height or above, falling to 0 as it is dragged down to dismissed. */
    internal val dismissProgress: Float
        get() = if (defaultPx <= 0f) 1f else (extent / defaultPx).coerceIn(0f, 1f)

    internal val draggable = DraggableState { deltaDown -> moveBy(deltaDown) }

    private fun moveBy(deltaDown: Float) {
        heldExtent = (extent - deltaDown).coerceIn(0f, max(fullPx, defaultPx))
    }

    /** Moves to [target] with the drawer's slide (240ms, `ease-slide`), cancelling a settle. */
    suspend fun animateTo(target: RaftDrawerSnapPoint) {
        if (fullPx > 0f) animateExtentTo(anchorPx(target))
        snapPoint = target
        heldExtent = null
    }

    /** Slides to [px] visible; a drag (user input) takes over from it. */
    internal suspend fun animateExtentTo(px: Float) {
        draggable.drag(MutatePriority.Default) {
            var previous = extent
            animate(previous, px, animationSpec = tween(RaftSheetSlideInMillis, easing = RaftMotion.EaseSlide)) { value, _ ->
                dragBy(previous - value)
                previous = value
            }
        }
    }

    /** Settles after a drag released at [velocityDown] px/s (positive down); true when it closes. */
    internal suspend fun settle(velocityDown: Float, flingPx: Float): Boolean =
        when (raftDrawerSettle(extent, velocityDown, defaultPx, fullPx, flingPx)) {
            RaftDrawerSettle.Dismiss -> {
                animateExtentTo(0f)
                true
            }
            RaftDrawerSettle.Default -> {
                animateTo(RaftDrawerSnapPoint.Default)
                false
            }
            RaftDrawerSettle.Full -> {
                animateTo(RaftDrawerSnapPoint.Full)
                false
            }
        }
}

/** A [RaftDrawerState] for one opening of the drawer. */
@Composable
fun rememberRaftDrawerState(initialSnapPoint: RaftDrawerSnapPoint = RaftDrawerSnapPoint.Default): RaftDrawerState =
    remember { RaftDrawerState(initialSnapPoint) }

/** Where a released drawer goes. */
internal enum class RaftDrawerSettle { Dismiss, Default, Full }

/**
 * Where a drawer released with [extent] px showing goes: a fling faster than [flingPx] px/s goes
 * to the next stop in its direction (down from full: the default height; down from the default
 * height: closed); a slower release to the nearest of closed, [defaultPx] and [fullPx] (so it
 * closes once dragged more than half of the default height down).
 */
internal fun raftDrawerSettle(
    extent: Float,
    velocityDown: Float,
    defaultPx: Float,
    fullPx: Float,
    flingPx: Float,
): RaftDrawerSettle {
    val stops = listOf(RaftDrawerSettle.Dismiss to 0f, RaftDrawerSettle.Default to defaultPx, RaftDrawerSettle.Full to fullPx)
    return when {
        velocityDown > flingPx -> stops.lastOrNull { it.second < extent - 1f }?.first ?: RaftDrawerSettle.Dismiss
        velocityDown < -flingPx -> stops.firstOrNull { it.second > extent + 1f }?.first ?: RaftDrawerSettle.Full
        else -> stops.minBy { abs(it.second - extent) }.first
    }
}

/**
 * Marks a part of a [RaftDrawer]'s content as a place to drag the sheet from (its head; the grab
 * handle always is). Nothing inside a drawer without snap points, or outside a drawer.
 */
@Composable
fun Modifier.raftDrawerDragArea(): Modifier = this.then(LocalRaftDrawerDragArea.current)

private val LocalRaftDrawerDragArea = staticCompositionLocalOf<Modifier> { Modifier }

/**
 * [RaftDrawer]'s surface without its window, for fixtures: [fill] (`layer-popover` by default),
 * the dialog radius on its top corners, the `line-seam` ring, clipped to its shape. It fills the
 * height it is given (the window gives it 82%); a fixture gives it one.
 */
@Composable
fun RaftDrawerSurface(
    modifier: Modifier = Modifier,
    fill: RaftDrawerFill = RaftDrawerFill.Popover,
    content: @Composable ColumnScope.() -> Unit,
) = RaftDrawerSurfaceImpl(modifier, clearNavigationBar = false, fill = fill, state = null, onDismissRequest = {}, content = content)

/** The fling speed past which a released drawer goes on to the next snap point. */
private val RaftDrawerFlingVelocity = 400.dp

@Composable
internal fun RaftDrawerSurfaceImpl(
    modifier: Modifier,
    clearNavigationBar: Boolean,
    fill: RaftDrawerFill,
    state: RaftDrawerState?,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val fillColor: Color = if (fill == RaftDrawerFill.Canvas) colors.layerCanvas else colors.layerPopover
    val dismiss by rememberUpdatedState(onDismissRequest)
    val flingPx = with(LocalDensity.current) { RaftDrawerFlingVelocity.toPx() }
    val dragArea =
        if (state == null) {
            Modifier
        } else {
            Modifier.draggable(
                state = state.draggable,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> if (state.settle(velocity, flingPx)) dismiss() },
            )
        }
    CompositionLocalProvider(
        LocalRaftContentColor provides colors.foreground,
        LocalRaftSurfaceColor provides fillColor,
        LocalRaftDrawerDragArea provides dragArea,
    ) {
        Column(
            modifier
                .then(if (state == null) Modifier else Modifier.raftDrawerSnapLayout(state))
                .widthIn(max = RaftDrawerDefaults.MaxWidth)
                .fillMaxWidth()
                // After the window's 82% cap (or the snap layout's height): the surface takes all of it.
                .fillMaxHeight()
                .raftOverlaySurface(fillColor, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog, 0.dp)
                .then(if (clearNavigationBar) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier),
        ) {
            RaftSheetGrabHandle(Modifier.raftDrawerDragArea())
            content()
        }
    }
}

/**
 * The snap layout: given the whole height under the status bar, the surface is as tall as it
 * shows ([RaftDrawerState.extent]) from the default height up to all of it; dragged below the
 * default height it keeps that height and moves down instead, so the content's bottom (a composer)
 * goes down with it rather than being cut. Reads the extent in layout only: a drag relayouts,
 * nothing recomposes.
 */
private fun Modifier.raftDrawerSnapLayout(state: RaftDrawerState): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val full = constraints.maxHeight.toFloat()
        val default = raftSheetConstraints(constraints).maxHeight.toFloat()
        state.fullPx = full
        state.defaultPx = default
        val extent = state.extent
        val height = max(extent, default).coerceAtMost(full).roundToInt()
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, height) {
            placeable.place(0, (default - extent).coerceAtLeast(0f).roundToInt())
        }
    }

/**
 * raft-ui `DrawerSwipeHandle` (0.57.2, raft-design #944): every bottom sheet has a grab handle at
 * its top edge (Drawer Rules: "Give every bottom sheet a grab handle"; Shape: round ends, as the
 * iOS grabber and Material's drag handle). A 16dp strip (`h-4`) holding a 36 x 4dp bar in
 * `line-seam` with round ends, 6dp from the top (`before:top-1.5 before:h-1 before:w-9`),
 * centred. It is drawn only, with no semantics (`aria-hidden`). On a [RaftDrawer] with snap points
 * the strip drags the sheet; elsewhere (a [RaftSheet]) swipe to dismiss is not ported: the sheet
 * keeps its ×, Back or a button.
 */
@Composable
internal fun RaftSheetGrabHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RaftDrawerDefaults.GrabHandleHeight), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(width = 36.dp, height = 4.dp)
                .background(RaftTheme.colors.lineSeam, CircleShape),
        )
    }
}

/** Metrics of [RaftDrawer]. */
object RaftDrawerDefaults {
    /** The widest the drawer gets: [RaftSheet]'s 448dp, so a wide window keeps a phone-wide sheet. */
    val MaxWidth = 448.dp

    /** [RaftSheetGrabHandle]'s strip at the top of every bottom sheet (`h-4`). */
    val GrabHandleHeight = 16.dp
}
