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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The window's toast host, null outside one. The app root provides it (ui-cmp
 * `RaftCmpToastLayer`, one per window); a page shows a toast with
 * `LocalRaftToastHostState.current?.show("Saved.")`.
 */
val LocalRaftToastHostState = staticCompositionLocalOf<RaftToastHostState?> { null }

/**
 * Whether [raftToastAvoid] and [RaftToastAvoidBottom] in this subtree report into
 * [LocalRaftToastHostState]. False on inactive kept-alive pages (provided by shared's
 * `RaftProvidePageActive`) and inside separate OS windows ([RaftWindowDialog]), where
 * `positionInRoot()` is in the dialog's own coordinate space rather than the host window's.
 */
val LocalRaftToastAvoidanceActive = compositionLocalOf { true }

/**
 * Supplies the live bottom safe-area + keyboard inset (px) read by [RaftToastHost] during layout
 * placement, so toasts follow the exact same IME source as the host/page (Android's dispatched
 * `RaftHostImeInsets`, iOS's curve-matched keyboard slide tracker) while still flooring at
 * `WindowInsets.safeDrawing` (e.g. for an iPad hardware keyboard shortcut bar).
 */
@Composable
fun RaftToastBottomInsetProvider(bottomInsetPx: Density.() -> Float) {
    val state = LocalRaftToastHostState.current ?: return
    val key = remember { Any() }
    val currentProvider by rememberUpdatedState(bottomInsetPx)
    DisposableEffect(state, key) {
        val reader: Density.() -> Float = { currentProvider() }
        state.putBottomInsetOverride(key, reader)
        onDispose { state.removeBottomInsetOverride(key) }
    }
}

/** raft-ui viewport: the toast's bottom edge sits 1rem above what holds the bottom of the page. */
val RaftToastViewportOffset: Dp = 16.dp

/** raft-ui viewport `w-[min(24rem,calc(100vw-2rem))]`: 1rem from each side. */
val RaftToastViewportSideMargin: Dp = 16.dp

/** raft-ui `--toast-gap`, between stacked toasts. */
val RaftToastGap: Dp = 8.dp

/** Base UI's swipe threshold: a toast dragged this far down or right closes. */
internal val RaftToastSwipeThreshold: Dp = 40.dp

/**
 * Effective bottom safe-area + keyboard inset for [RaftToastHost]: [overridePx] tracks live host
 * keyboard motion, but never drops below [safeDrawingBottomPx] (such as an iPad hardware keyboard
 * shortcut bar when the host's software keyboard height is 0).
 */
internal fun raftToastSafeBottomPx(overridePx: Float?, safeDrawingBottomPx: Float): Float =
    maxOf(overridePx ?: 0f, safeDrawingBottomPx)

/**
 * raft-ui `ToastViewport` (`bottom-center`) for [state]: draws its toasts over everything before
 * it in the same parent (the page, its dialogs and their scrims; raft-ui: "toasts already draw
 * above dialogs"), and passes every touch elsewhere through. Fill the window with it.
 *
 * - Bottom centre, at most 384dp wide and 16dp from the sides, inside the horizontal safe area.
 * - At every width the bottom edge sits 16dp above whatever holds the bottom of the page
 *   ([raftToastAvoid], [RaftToastAvoidBottom]: the tab bar, the composer; the highest of them, so a
 *   conversation beside a thread clears both), or the bottom of the window where nothing does
 *   (raft-design #1217: on a wide window too, not only on a phone). While a toast shows it only
 *   moves up, for something taller, never down when the page changes; never lower than 16dp above
 *   the system bars and the keyboard (`maxOf(RaftToastBottomInsetProvider, WindowInsets.safeDrawing)`),
 *   which it still follows.
 * - Several toasts ([RaftToastHostState.limit] > 1) stack newest at the bottom, [RaftToastGap]
 *   apart (raft-ui's expanded stack).
 * - A toast slides up as it opens (none with reduced motion), swipes away down or right, and
 *   closes after its timeout ([RaftToastHostState.runTimers]); a pointer over it or a finger on
 *   it pauses every timer. Each toast is a polite live region (an error, assertive) and offers
 *   the dismiss accessibility action in place of the swipe.
 *
 * [onToastBoundsChange] is intended for window-level hit-testing (for example, a dedicated
 * pass-through overlay window): when non-null, it reports the window-pixel bounding union of all
 * currently visible toast cards including slide-in and drag-dismiss translation, only when that
 * union changes (`null` when the last toast leaves or when this host is disposed so the caller
 * never keeps stale bounds, without emitting duplicate consecutive `null` callbacks). When `null`
 * (the default), no layout bounds tracking is installed.
 */
@Composable
fun RaftToastHost(
    state: RaftToastHostState,
    modifier: Modifier = Modifier,
    onToastBoundsChange: ((Rect?) -> Unit)? = null,
) {
    LaunchedEffect(state) { state.runTimers() }
    val toasts by state.toasts.collectAsState()
    // Toasts already on screen when the host appears (handed over from another window) do not
    // slide in again.
    val presentAtStart = remember(state) { state.visible.map { it.id }.toSet() }
    val boundsReporter =
        if (onToastBoundsChange != null) {
            val currentOnBoundsChange by rememberUpdatedState(onToastBoundsChange)
            val reporter = remember { RaftToastBoundsReporter() }
            DisposableEffect(reporter) {
                reporter.attach { rect -> currentOnBoundsChange(rect) }
                onDispose { reporter.dispose() }
            }
            reporter
        } else {
            null
        }
    var hostBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    val safeDrawing = WindowInsets.safeDrawing
    val horizontalInsets = rememberRaftStableWindowInsets(safeDrawing.only(WindowInsetsSides.Horizontal))
    // `onPlaced` runs during the parent Box's placement pass, so `hostBottomPx` and any avoided bar's
    // `topPx` are ready before the Column lays out — and `TopEdge.liveKeyboardPx` lets this layout
    // pass track live keyboard motion in the very same frame even when a deeply nested composer runs
    // its `onPlaced` after this overlay Column.
    Box(
        modifier.fillMaxSize().onPlaced { coordinates ->
            if (coordinates.size.height > 0) {
                hostBottomPx = coordinates.positionInRoot().y + coordinates.size.height
            }
        },
    ) {
        if (toasts.isEmpty()) {
            if (boundsReporter != null) {
                LaunchedEffect(boundsReporter) { boundsReporter.clear() }
            }
            return@Box
        }
        // The stack leaving (its last toast gone) must not leave the timers paused.
        DisposableEffect(state) { onDispose { state.setPaused(false) } }
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                    val layoutHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
                    layout(layoutWidth, layoutHeight) {
                        val safeBottomPx =
                            raftToastSafeBottomPx(
                                overridePx = state.bottomInsetOverrideOrNull()?.invoke(this@layout),
                                safeDrawingBottomPx = safeDrawing.getBottom(this@layout).toFloat(),
                            )
                        val clearancePx = state.resolveBottomClearancePx(hostBottomPx, safeBottomPx)
                        val bottomPx = (clearancePx + RaftToastViewportOffset.toPx()).roundToInt()
                        val x =
                            Alignment.CenterHorizontally
                                .align(placeable.width, layoutWidth, layoutDirection)
                                .coerceAtLeast(0)
                        val y = (layoutHeight - placeable.height - bottomPx).coerceAtLeast(0)
                        placeable.place(x, y)
                    }
                }
                .windowInsetsPadding(horizontalInsets)
                .padding(horizontal = RaftToastViewportSideMargin)
                .widthIn(max = 384.dp)
                .raftToastPausesTimers(state),
            verticalArrangement = Arrangement.spacedBy(RaftToastGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            toasts.forEach { toast ->
                key(toast.id) {
                    RaftToastHostItem(
                        state,
                        toast,
                        appearAtOnce = toast.appearAtOnce || toast.id in presentAtStart,
                        boundsReporter = boundsReporter,
                    )
                }
            }
        }
    }
}

internal fun raftToastTransformedBounds(
    baseRect: Rect,
    entryProgress: Float,
    swipeOffset: Offset,
): Rect {
    if (baseRect.isEmpty) return Rect.Zero
    val closed = (1f - entryProgress).coerceIn(0f, 1f)
    val scale = 1f - 0.02f * closed
    val centerX = (baseRect.left + baseRect.right) * 0.5f
    val centerY = (baseRect.top + baseRect.bottom) * 0.5f
    val halfWidth = baseRect.width * 0.5f * scale
    val halfHeight = baseRect.height * 0.5f * scale
    val translationX = swipeOffset.x
    val translationY = baseRect.height * 1.2f * closed + swipeOffset.y
    return Rect(
        left = centerX - halfWidth + translationX,
        top = centerY - halfHeight + translationY,
        right = centerX + halfWidth + translationX,
        bottom = centerY + halfHeight + translationY,
    )
}

internal fun raftToastUnionBounds(rects: Collection<Rect>): Rect? {
    var union: Rect? = null
    for (rect in rects) {
        if (rect.isEmpty) continue
        union =
            if (union == null) {
                rect
            } else {
                Rect(
                    left = minOf(union.left, rect.left),
                    top = minOf(union.top, rect.top),
                    right = maxOf(union.right, rect.right),
                    bottom = maxOf(union.bottom, rect.bottom),
                )
            }
    }
    return union
}

private class RaftToastBoundsReporter {
    private val itemBounds = LinkedHashMap<Long, Rect>()
    private var lastReported: Rect? = null
    private var hasReported = false
    private var listener: ((Rect?) -> Unit)? = null

    fun attach(onBoundsChange: (Rect?) -> Unit) {
        listener = onBoundsChange
    }

    fun update(toastId: Long, rect: Rect?) {
        if (rect == null || rect.isEmpty) {
            itemBounds.remove(toastId)
        } else {
            itemBounds[toastId] = rect
        }
        emitIfChanged()
    }

    fun remove(toastId: Long) {
        if (itemBounds.remove(toastId) != null) {
            emitIfChanged()
        }
    }

    fun clear() {
        itemBounds.clear()
        emitIfChanged()
    }

    fun dispose() {
        itemBounds.clear()
        val shouldEmitNull = !hasReported || lastReported != null
        lastReported = null
        hasReported = true
        val target = listener
        listener = null
        if (shouldEmitNull) {
            target?.invoke(null)
        }
    }

    private fun emitIfChanged() {
        val next = raftToastUnionBounds(itemBounds.values)
        if (!hasReported && next == null) return
        if (!hasReported || next != lastReported) {
            hasReported = true
            lastReported = next
            listener?.invoke(next)
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
private fun RaftToastHostItem(
    state: RaftToastHostState,
    toast: RaftToastData,
    appearAtOnce: Boolean,
    boundsReporter: RaftToastBoundsReporter? = null,
) {
    val reducedMotion = rememberRaftReducedMotion()
    // raft-ui `data-starting-style`: translateY(120%) scale(0.98), on ease-slide.
    val entry = remember { Animatable(if (appearAtOnce || reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entry.value < 1f) {
            entry.animateTo(1f, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide))
        }
    }
    val swipe = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { RaftToastSwipeThreshold.toPx() }
    val onAction = toast.onAction
    val boundsModifier =
        if (boundsReporter != null) {
            var baseWindowBounds by remember(toast.id) { mutableStateOf<Rect?>(null) }
            DisposableEffect(boundsReporter, toast.id) {
                onDispose { boundsReporter.remove(toast.id) }
            }
            LaunchedEffect(boundsReporter, toast.id, entry, swipe) {
                snapshotFlow {
                    baseWindowBounds?.let { base ->
                        raftToastTransformedBounds(base, entry.value, swipe.value)
                    }
                }.collect { rect ->
                    boundsReporter.update(toast.id, rect)
                }
            }
            Modifier.onGloballyPositioned { coordinates ->
                val base =
                    if (coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0) {
                        coordinates.boundsInWindow()
                    } else {
                        null
                    }
                baseWindowBounds = base
                boundsReporter.update(
                    toast.id,
                    base?.let { raftToastTransformedBounds(it, entry.value, swipe.value) },
                )
            }
        } else {
            Modifier
        }
    RaftToast(
        title = toast.title,
        description = toast.description,
        footnote = toast.footnote,
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
                .then(boundsModifier)
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
 * tab bar): the window's toasts sit 16dp above this element's top edge while it is
 * laid out, [enabled], and [LocalRaftToastAvoidanceActive] is true. Pass `enabled = false` from a
 * page that is composed but not shown (a page under the top one in a stack). [keyboardPx]: the
 * keyboard under an element that rides on it (a composer), read both on placement (so a toast
 * does not stay up after the keyboard closes) and live during [RaftToastHost]'s layout pass;
 * [keyboardPx] must read Compose snapshot state (for example `WindowInsets.ime`,
 * `LocalRaftHostImeInsets`, or Compose `State`), otherwise keyboard changes will not invalidate
 * [RaftToastHost] to re-layout on the same frame. A no-op outside a [LocalRaftToastHostState].
 */
fun Modifier.raftToastAvoid(enabled: Boolean = true, keyboardPx: () -> Float = { 0f }): Modifier =
    composed {
        val state = LocalRaftToastHostState.current
        val active = enabled && LocalRaftToastAvoidanceActive.current
        val key = remember { Any() }
        DisposableEffect(state, key, active) {
            if (!active) state?.removeAvoidance(key)
            onDispose { state?.removeAvoidance(key) }
        }
        if (state == null || !active) {
            Modifier
        } else {
            val currentKeyboardPx by rememberUpdatedState(keyboardPx)
            val liveKeyboardReader = remember { { currentKeyboardPx() } }
            Modifier.onPlaced { coordinates ->
                val size = coordinates.size
                if (size.width <= 0 || size.height <= 0) {
                    state.removeAvoidance(key)
                } else {
                    state.putAvoidance(
                        key,
                        RaftToastAvoidance.TopEdge(
                            topPx = coordinates.positionInRoot().y,
                            keyboardPx = currentKeyboardPx(),
                            liveKeyboardPx = liveKeyboardReader,
                        ),
                    )
                }
            }
        }
    }

/**
 * [raftToastAvoid] for a band Compose does not draw: [height] up from the window's bottom edge
 * (a host-drawn tab bar), while composed and [enabled].
 */
@Composable
fun RaftToastAvoidBottom(height: Dp, enabled: Boolean = true) {
    val state = LocalRaftToastHostState.current ?: return
    val active = enabled && LocalRaftToastAvoidanceActive.current
    val key = remember { Any() }
    val heightPx = with(LocalDensity.current) { height.toPx() }
    DisposableEffect(state, key, active, heightPx) {
        if (active) state.putAvoidance(key, RaftToastAvoidance.BottomBand(heightPx)) else state.removeAvoidance(key)
        onDispose { state.removeAvoidance(key) }
    }
}
