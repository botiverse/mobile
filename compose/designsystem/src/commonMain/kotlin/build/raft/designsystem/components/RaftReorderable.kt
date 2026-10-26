package build.raft.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlin.coroutines.cancellation.CancellationException

// raft-ui "Dragging on a touch screen" (design/foundations/states.md; lib/touch-drag.ts
// `useHoldThenMoveTouchSensor`): a finger that holds still on a row for 500ms lifts it; moving
// then drags it, letting go drops it. A move of more than 10px before the hold is up was a
// scroll and moves nothing; a hold let go without moving moves nothing (and picks nothing).
// The lifted row is a card that left its place for a moment: `shadow-float` on the popover
// layer (design/foundations/elevation.md). For a plain Column of keyed rows, inside a
// verticalScroll or not; a LazyColumn keeps its own sort overlay.

/** raft-ui touch-drag metrics. */
object RaftReorderDefaults {
    /** `TOUCH_DRAG_HOLD_MS`: the hold that lifts a row, the moment a long-press menu opens. */
    const val HoldMillis: Long = 500L

    /** `TOUCH_DRAG_TOLERANCE_PX`: a finger that moves further during the hold is scrolling. */
    val HoldSlop: Dp = 10.dp

    /** A lifted row this close to the list's top or bottom edge scrolls the list… */
    val EdgeScrollZone: Dp = 40.dp

    /** …by this much a frame. */
    val EdgeScrollStep: Dp = 8.dp
}

/**
 * raft-ui 0.32.2 (raft-design #787): every touch drag waits the [RaftReorderDefaults.HoldMillis]
 * hold. Compose's `detectDragGesturesAfterLongPress` with that hold instead of the platform's long
 * press (400ms on Android), for a drag that doesn't use [raftReorderableItem]. As there, a lift,
 * a consumed event (the list scrolled) or leaving the element before the hold is up ends it.
 */
suspend fun PointerInputScope.raftDetectDragGesturesAfterHold(
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        try {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitTouchDragHold(down) ?: return@awaitEachGesture
            onDragStart(held.position)
            val ended =
                drag(held.id) {
                    onDrag(it, it.positionChange())
                    it.consume()
                }
            if (ended) {
                currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                onDragEnd()
            } else {
                onDragCancel()
            }
        } catch (c: CancellationException) {
            onDragCancel()
            throw c
        }
    }
}

/** The pointer still down after [RaftReorderDefaults.HoldMillis], or null when the hold ended. */
private suspend fun AwaitPointerEventScope.awaitTouchDragHold(down: PointerInputChange): PointerInputChange? {
    var last = down
    return try {
        withTimeout(RaftReorderDefaults.HoldMillis) {
            var holding = true
            while (holding) {
                val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding)) {
                    holding = false
                } else {
                    last = change
                    if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) holding = false
                }
            }
        }
        null
    } catch (_: PointerEventTimeoutCancellationException) {
        last
    }
}

/**
 * The order of a reorderable list and its lifted row. The rows are keyed by the app's ids; the
 * app owns the order ([sync] it with what the app shows) and gets a changed order back from
 * [drop] or [moveBy] to commit. All positions are pixels.
 */
@Stable
class RaftReorderState(initialOrder: List<String> = emptyList()) {
    /** The rows in the order they show, the lifted row in the slot it would drop into. */
    var order: List<String> by mutableStateOf(initialOrder)
        private set

    /** The row a hold lifted, or null. */
    var liftedKey: String? by mutableStateOf(null)
        private set

    /** How far the lifted row is drawn from its slot (positive is down). */
    var liftedOffset: Float by mutableStateOf(0f)
        private set

    /** The finger's y in root coordinates while a row is lifted. */
    internal var fingerY: Float = 0f

    /** The scrolling list's top and bottom in root coordinates (a viewport), if any. */
    internal var viewportTop: Float = 0f
    internal var viewportBottom: Float = 0f

    private val heights = mutableMapOf<String, Int>()
    private var orderAtLift: List<String> = initialOrder
    private var pendingOrder: List<String>? = null
    private var committedFrom: List<String>? = null

    val dragging: Boolean get() = liftedKey != null

    /**
     * The app's order changed (a store echo, another device). While a row is lifted the new
     * order waits for the drop, so the rows don't jump under the finger.
     */
    fun sync(incoming: List<String>) {
        // The order this list committed hasn't reached the app yet: the app's old order is
        // stale, not a revert (a failed save comes back after the app took the new one).
        val committed = committedFrom
        if (committed != null) {
            if (incoming == committed) return
            committedFrom = null
        }
        when {
            // A server came or went under the finger: the lifted row's slots are gone.
            dragging && incoming.toSet() != order.toSet() -> {
                cancel()
                order = incoming
            }
            dragging -> pendingOrder = incoming
            incoming != order -> order = incoming
        }
    }

    /** A row's laid-out height: where the rows' slots are. */
    fun setHeight(key: String, height: Int) {
        heights[key] = height
    }

    /** A hold lifted [key]. False when another row is lifted or [key] isn't in the list. */
    fun lift(key: String): Boolean {
        if (dragging || key !in order) return false
        orderAtLift = order
        liftedOffset = 0f
        liftedKey = key
        return true
    }

    /**
     * The lifted row moved by [deltaY]. It takes a neighbour's slot once its leading edge passes
     * the neighbour's middle; the neighbour moves into the slot it left.
     */
    fun dragBy(deltaY: Float) {
        val key = liftedKey ?: return
        liftedOffset += deltaY
        while (true) {
            val index = order.indexOf(key)
            val next = order.getOrNull(index + 1)
            val nextHeight = next?.let { heights[it] }?.takeIf { it > 0 }
            if (next != null && nextHeight != null && liftedOffset > nextHeight / 2f) {
                order = order.swapped(index, index + 1)
                liftedOffset -= nextHeight
                continue
            }
            val previous = order.getOrNull(index - 1)
            val previousHeight = previous?.let { heights[it] }?.takeIf { it > 0 }
            if (previous != null && previousHeight != null && liftedOffset < -previousHeight / 2f) {
                order = order.swapped(index, index - 1)
                liftedOffset += previousHeight
                continue
            }
            break
        }
    }

    /** The finger let go: the new order to commit, or null when nothing moved. */
    fun drop(): List<String>? {
        if (!dragging) return null
        val moved = order.takeIf { it != orderAtLift }
        if (moved != null) committedFrom = orderAtLift
        finish(keepOrder = moved != null)
        return moved
    }

    /** The drag ended without a drop (the list went away, a second finger): the old order back. */
    fun cancel() {
        if (!dragging) return
        order = orderAtLift
        finish(keepOrder = false)
    }

    /**
     * Moves [key] by [by] places (the screen reader's move up / move down): the new order to
     * commit, or null when it can't move that way or a row is lifted.
     */
    fun moveBy(key: String, by: Int): List<String>? {
        if (dragging) return null
        val from = order.indexOf(key)
        val to = from + by
        if (from < 0 || to !in order.indices || by == 0) return null
        val next = order.toMutableList().apply { add(to, removeAt(from)) }
        committedFrom = order
        order = next
        return next
    }

    /** Whether [key] can move by [by] places. */
    fun canMove(key: String, by: Int): Boolean {
        val from = order.indexOf(key)
        return from >= 0 && (from + by) in order.indices && by != 0
    }

    private fun finish(keepOrder: Boolean) {
        liftedKey = null
        liftedOffset = 0f
        // An order that came in while the row was lifted applies now, unless the drop just
        // replaced it (the app's echo of the drop follows).
        pendingOrder?.let { if (!keepOrder) order = it }
        pendingOrder = null
    }
}

private fun List<String>.swapped(a: Int, b: Int): List<String> =
    toMutableList().also {
        val t = it[a]
        it[a] = it[b]
        it[b] = t
    }

/**
 * Which way a lifted row near the list's edge scrolls it: negative up, positive down, 0 none.
 * [fingerY], [top] and [bottom] are in the same coordinates.
 */
fun raftReorderAutoScrollDelta(
    fingerY: Float,
    top: Float,
    bottom: Float,
    zone: Float,
    step: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): Float {
    if (bottom <= top || zone <= 0f || step <= 0f) return 0f
    return when {
        fingerY < top + zone && canScrollBackward -> -step
        fingerY > bottom - zone && canScrollForward -> step
        else -> 0f
    }
}

@Composable
fun rememberRaftReorderState(order: List<String>): RaftReorderState {
    val state = remember { RaftReorderState(order) }
    SideEffect { state.sync(order) }
    return state
}

/**
 * The scrolling list a reorderable column sits in: put it before `verticalScroll(scrollState)`.
 * While a row is lifted and the finger is near the top or bottom edge, the list scrolls and the
 * row stays under the finger.
 */
@Composable
fun Modifier.raftReorderableViewport(state: RaftReorderState, scrollState: ScrollState): Modifier {
    val density = LocalDensity.current
    val zone = with(density) { RaftReorderDefaults.EdgeScrollZone.toPx() }
    val step = with(density) { RaftReorderDefaults.EdgeScrollStep.toPx() }
    LaunchedEffect(state, state.liftedKey) {
        if (state.liftedKey == null) return@LaunchedEffect
        while (state.dragging) {
            val delta =
                raftReorderAutoScrollDelta(
                    fingerY = state.fingerY,
                    top = state.viewportTop,
                    bottom = state.viewportBottom,
                    zone = zone,
                    step = step,
                    canScrollBackward = scrollState.canScrollBackward,
                    canScrollForward = scrollState.canScrollForward,
                )
            if (delta != 0f) {
                val consumed = scrollState.dispatchRawDelta(delta)
                // The rows moved under a finger that stayed: the lifted row follows the finger.
                if (consumed != 0f) state.dragBy(consumed)
            }
            withFrameNanos { }
        }
    }
    return onGloballyPositioned {
        val bounds = it.boundsInRoot()
        state.viewportTop = bounds.top
        state.viewportBottom = bounds.bottom
    }
}

/**
 * One row of a reorderable column, under [key]: a 500ms hold lifts it (a haptic tick, the
 * `shadow-float` card on `layer-popover`), moving drags it, letting go commits a changed order
 * through [onOrderChange]. A tap stays the row's own click; a hold let go without moving
 * clicks nothing. Screen readers get [moveUpLabel] / [moveDownLabel] actions. Put it first on
 * the row's modifier, before its click.
 */
@Composable
fun Modifier.raftReorderableItem(
    state: RaftReorderState,
    key: String,
    onOrderChange: (List<String>) -> Unit,
    moveUpLabel: String? = null,
    moveDownLabel: String? = null,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val haptics = LocalHapticFeedback.current
    val commit by rememberUpdatedState(onOrderChange)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val lifted = state.liftedKey == key
    // Read here, not in the semantics block, so the actions follow the order.
    val canMoveUp = moveUpLabel != null && state.canMove(key, -1)
    val canMoveDown = moveDownLabel != null && state.canMove(key, 1)
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val colors = RaftTheme.colors
    return this
        .onSizeChanged { state.setHeight(key, it.height) }
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer { translationY = if (state.liftedKey == key) state.liftedOffset else 0f }
        .then(
            if (lifted) {
                Modifier.raftFloatShadow(RaftTheme.isDark, RaftRadius.Chip).background(colors.layerPopover, shape)
            } else {
                Modifier
            },
        )
        .semantics {
            val actions = buildList {
                if (canMoveUp && moveUpLabel != null) {
                    add(CustomAccessibilityAction(moveUpLabel) { state.moveBy(key, -1)?.let(commit) != null })
                }
                if (canMoveDown && moveDownLabel != null) {
                    add(CustomAccessibilityAction(moveDownLabel) { state.moveBy(key, 1)?.let(commit) != null })
                }
            }
            if (actions.isNotEmpty()) customActions = actions
        }
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(state, key) {
            val slop = RaftReorderDefaults.HoldSlop.toPx()
            fun root(local: Offset): Offset? = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(local)

            fun rootY(local: Offset): Float? = root(local)?.y
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (state.dragging) return@awaitEachGesture
                // The slop is measured on the screen: once the list scrolls, the row moves with
                // the finger, so the finger barely moves on the row.
                val downRoot = root(down.position) ?: down.position
                // The hold: a lift, a move past the slop, a second finger or a consumed event
                // ends it; nothing here is consumed, so taps and scrolls
                // stay the row's and the list's.
                var held = false
                try {
                    withTimeout(RaftReorderDefaults.HoldMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || !change.pressed) break
                            val at = root(change.position) ?: change.position
                            if ((at - downRoot).getDistance() > slop) break
                        }
                    }
                } catch (_: PointerEventTimeoutCancellationException) {
                    held = true
                }
                if (!held || !state.lift(key)) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                var lastY = rootY(down.position) ?: 0f
                state.fingerY = lastY
                try {
                    while (true) {
                        // The Initial pass: the row's click and the list's scroll see every
                        // event of a lifted row consumed (no click on the lift, no scroll).
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || event.changes.count { it.pressed } > 1) {
                            state.cancel()
                            break
                        }
                        if (!change.pressed) {
                            state.drop()?.let(commit)
                            break
                        }
                        val y = rootY(change.position) ?: continue
                        state.fingerY = y
                        state.dragBy(y - lastY)
                        lastY = y
                    }
                } finally {
                    // The gesture was cancelled (the list went away): nothing moves.
                    if (state.liftedKey == key) state.cancel()
                }
            }
        }
}
