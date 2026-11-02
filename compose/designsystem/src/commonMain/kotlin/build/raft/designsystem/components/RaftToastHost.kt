package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch

/**
 * The window's toast host, null outside one. The app root provides it (ui-cmp
 * `RaftCmpToastLayer`, one per window); a page shows a toast with
 * `LocalRaftToastHostState.current?.show("Saved.")`.
 */
val LocalRaftToastHostState = staticCompositionLocalOf<RaftToastHostState?> { null }

/** raft-ui viewport: the toast's bottom edge sits 1rem above what holds the bottom of the page. */
val RaftToastViewportOffset: Dp = 16.dp

/** raft-ui viewport `w-[min(24rem,calc(100vw-2rem))]`: 1rem from each side. */
val RaftToastViewportSideMargin: Dp = 16.dp

/** raft-ui `--toast-gap`, between stacked toasts. */
val RaftToastGap: Dp = 8.dp

/** Base UI's swipe threshold: a toast dragged this far down or right closes. */
internal val RaftToastSwipeThreshold: Dp = 40.dp

/**
 * raft-ui `ToastViewport` (`bottom-center`) for [state]: draws its toasts over everything before
 * it in the same parent (the page, its dialogs and their scrims; raft-ui: "toasts already draw
 * above dialogs"), and passes every touch elsewhere through. Fill the window with it.
 *
 * - Bottom centre, at most 384dp wide and 16dp from the sides, inside the horizontal safe area.
 * - Phone ([RaftScale.Mobile]): the bottom edge sits 16dp above whatever holds the bottom of the
 *   page ([raftToastAvoid], [RaftToastAvoidBottom]: the tab bar, the composer), and never lower
 *   than 16dp above the system bars and the keyboard (`WindowInsets.safeDrawing`).
 *   Desktop scale: the bottom of the window (stdrc 2026-10-23), above the safe area only.
 * - Several toasts ([RaftToastHostState.limit] > 1) stack newest at the bottom, [RaftToastGap]
 *   apart (raft-ui's expanded stack).
 * - A toast slides up as it opens (none with reduced motion), swipes away down or right, and
 *   closes after its timeout ([RaftToastHostState.runTimers]); a pointer over it or a finger on
 *   it pauses every timer. Each toast is a polite live region (an error, assertive) and offers
 *   the dismiss accessibility action in place of the swipe.
 */
@Composable
fun RaftToastHost(state: RaftToastHostState, modifier: Modifier = Modifier) {
    LaunchedEffect(state) { state.runTimers() }
    val toasts by state.toasts.collectAsState()
    // Toasts already on screen when the host appears (handed over from another window) do not
    // slide in again.
    val presentAtStart = remember(state) { state.visible.map { it.id }.toSet() }
    var hostBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    val phone = RaftTheme.scale != RaftScale.Desktop
    val avoidedPx = if (phone && !hostBottomPx.isNaN()) state.avoidedHeightPx(hostBottomPx) else 0f
    val safeBottomPx = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val bottom = with(density) { maxOf(avoidedPx, safeBottomPx).toDp() } + RaftToastViewportOffset
    Box(
        modifier.fillMaxSize().onGloballyPositioned { hostBottomPx = it.boundsInRoot().bottom },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (toasts.isEmpty()) return@Box
        // The stack leaving (its last toast gone) must not leave the timers paused.
        DisposableEffect(state) { onDispose { state.setPaused(false) } }
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(start = RaftToastViewportSideMargin, end = RaftToastViewportSideMargin, bottom = bottom)
                .widthIn(max = 384.dp)
                .raftToastPausesTimers(state),
            verticalArrangement = Arrangement.spacedBy(RaftToastGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            toasts.forEach { toast ->
                key(toast.id) { RaftToastHostItem(state, toast, appearAtOnce = toast.id in presentAtStart) }
            }
        }
    }
}

/** Hover (a mouse over the stack) or a press on it pauses every timer (raft-ui / Base UI). */
private fun Modifier.raftToastPausesTimers(state: RaftToastHostState): Modifier =
    pointerInput(state) {
        awaitPointerEventScope {
            var hovered = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Enter -> hovered = true
                    PointerEventType.Exit -> hovered = false
                }
                state.setPaused(hovered || event.changes.any { it.pressed })
            }
        }
    }

@Composable
private fun RaftToastHostItem(state: RaftToastHostState, toast: RaftToastData, appearAtOnce: Boolean) {
    val reducedMotion = rememberRaftReducedMotion()
    // raft-ui `data-starting-style`: translateY(120%) scale(0.98), on ease-slide.
    val entry = remember { Animatable(if (appearAtOnce || reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) { entry.animateTo(1f, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide)) }
    val swipe = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { RaftToastSwipeThreshold.toPx() }
    val onAction = toast.onAction
    RaftToast(
        title = toast.title,
        description = toast.description,
        intent = toast.intent,
        actionLabel = toast.actionLabel,
        onAction =
            onAction?.let {
                {
                    it()
                    state.dismiss(toast.id)
                }
            },
        modifier =
            Modifier
                .semantics {
                    dismiss {
                        state.dismiss(toast.id)
                        true
                    }
                }
                // Before the layer: drags are read in the untransformed toast's space.
                .pointerInput(toast.id) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val (x, y) = swipe.value
                                if (x > thresholdPx || y > thresholdPx) state.dismiss(toast.id) else swipe.animateTo(Offset.Zero)
                            }
                        },
                        onDragCancel = { scope.launch { swipe.animateTo(Offset.Zero) } },
                    ) { change, drag ->
                        change.consume()
                        // A bottom toast swipes away the way it came: down or right.
                        val next = swipe.value + drag
                        scope.launch { swipe.snapTo(Offset(next.x.coerceAtLeast(0f), next.y.coerceAtLeast(0f))) }
                    }
                }
                .graphicsLayer {
                    val closed = 1f - entry.value
                    translationX = swipe.value.x
                    translationY = size.height * 1.2f * closed + swipe.value.y
                    val scale = 1f - 0.02f * closed
                    scaleX = scale
                    scaleY = scale
                },
    )
}

/**
 * Marks what holds the bottom of the page (raft-ui `--toast-viewport-bottom`: the composer, the
 * tab bar): on a phone, the window's toasts sit 16dp above this element's top edge while it is
 * laid out and [enabled]. Pass `enabled = false` from a page that is composed but not shown
 * (a page under the top one in a stack). A no-op outside a [LocalRaftToastHostState].
 */
fun Modifier.raftToastAvoid(enabled: Boolean = true): Modifier =
    composed {
        val state = LocalRaftToastHostState.current
        val key = remember { Any() }
        DisposableEffect(state, key, enabled) {
            if (!enabled) state?.removeAvoidance(key)
            onDispose { state?.removeAvoidance(key) }
        }
        if (state == null || !enabled) {
            Modifier
        } else {
            Modifier.onGloballyPositioned { state.putAvoidance(key, RaftToastAvoidance.TopEdge(it.boundsInRoot().top)) }
        }
    }

/**
 * [raftToastAvoid] for a band Compose does not draw: [height] up from the window's bottom edge
 * (a host-drawn tab bar), while composed and [enabled].
 */
@Composable
fun RaftToastAvoidBottom(height: Dp, enabled: Boolean = true) {
    val state = LocalRaftToastHostState.current ?: return
    val key = remember { Any() }
    val heightPx = with(LocalDensity.current) { height.toPx() }
    DisposableEffect(state, key, enabled, heightPx) {
        if (enabled) state.putAvoidance(key, RaftToastAvoidance.BottomBand(heightPx)) else state.removeAvoidance(key)
        onDispose { state.removeAvoidance(key) }
    }
}
