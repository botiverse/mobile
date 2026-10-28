package build.raft.designsystem.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** raft-ui tooltip metrics (tooltip.tsx). */
internal object RaftTooltipMetrics {
    /** The arrow's triangle (`M1 0H11L6 5.25Z` in its 12 x 6 svg). */
    val CaretSize = DpSize(10.dp, 5.25.dp)

    /** `sideOffset` 8: anchor edge to bubble edge; the caret hangs into this gap. */
    val AnchorSpacing = 8.dp

    /**
     * `px-2.5`. Material's PlainTooltip already pads 8dp each side (and 4dp top and
     * bottom, the `py-1`); this is the remaining 2dp.
     */
    val ExtraPaddingX = 2.dp

    /** How long a long press keeps it open (Material's `BasicTooltipDefaults.TooltipDuration`). */
    const val TouchDuration = 1500L
}

/** Which side of its anchor a [RaftTooltip] opens on. */
enum class RaftTooltipPlacement {
    /** Above, or below when there is no room above (raft-ui's default `side="top"`). */
    Above,

    /**
     * Below, or above when there is no room below: the top bar's items (raft-ui), where above
     * would run into the window's edge and its title-bar controls.
     */
    Below,
}

/**
 * The placement [RaftTooltip]s in the subtree take by default: [RaftTopBar] provides
 * [RaftTooltipPlacement.Below] for its items.
 */
val LocalRaftTooltipPlacement = staticCompositionLocalOf { RaftTooltipPlacement.Above }

/**
 * Opens every [RaftTooltip] in the subtree, persistent (screenshot fixtures only: an
 * offscreen frame can neither hover nor hold a long press).
 */
internal val LocalRaftTooltipPreview = staticCompositionLocalOf { false }

/**
 * The physical side of its anchor a [RaftTooltip] opens on, beside it and centred on it, with
 * the caret on the bubble's edge facing the anchor. It flips to the other side when the window
 * has no room on this one. Not public API: [RaftSideActionColumn] sets it so its tooltips open
 * toward the content, never off the screen edge the column stands on.
 */
internal enum class RaftTooltipSide { Left, Right }

/** See [RaftTooltipSide]; null (the default) opens above the anchor, below without room. */
internal val LocalRaftTooltipSide = staticCompositionLocalOf<RaftTooltipSide?> { null }

/**
 * Waits out a long-press tooltip's [RaftTooltipMetrics.TouchDuration] before it closes
 * (`delay`). Tests swap in a gate they release, as the scene's effect dispatcher takes its
 * delays from the wall clock, not `mainClock`. A mouse-opened tooltip never calls it.
 */
internal val LocalRaftTooltipTouchTimer = staticCompositionLocalOf<suspend (durationMillis: Long) -> Unit> { { delay(it) } }

/**
 * raft-ui 0.8.0 Tooltip around [content]: a short label in the dark HUD bubble with a
 * small caret pointing at [content], above it (below when there is no room above), or below it
 * with [placement] [RaftTooltipPlacement.Below] (the default inside [RaftTopBar]).
 *
 * - Look (tooltip.tsx): `layer-hud` fill, `foreground-on-hud` type, `radius-chip` (6dp),
 *   10 x 4dp padding, `text-footnote` 13/18 in medium weight, `shadow-float`, a 10 x 5.25dp
 *   caret, 8dp from the anchor.
 * - A mouse / trackpad pointer entering [content] opens it, and it stays open until the
 *   pointer leaves (raft-ui: open while hovered). A touch or stylus long press opens it
 *   for [RaftTooltipMetrics.TouchDuration] (1.5s, Material's plain tooltip); that long
 *   press does not also click. A click or tap elsewhere closes it.
 * - Not focusable and adds no semantics node: it never takes focus or swallows the next
 *   tap, and screen readers get one stop, [content] itself, which must carry the label
 *   (see [RaftIconButton], which passes it as the content description).
 *
 * Material3 [TooltipBox] / [PlainTooltip] does the positioning and the popup; the gestures
 * are ours (Material's own add an unlabeled merging semantics node round [content] and
 * close a hovered tooltip after 1.5s). The bubble's width is capped at Material's 200dp
 * (raft-ui allows 320dp): keep labels short.
 *
 * [openOnLongPress] = false leaves the long press to [content] (an icon button with its own
 * long-press action): the tooltip then opens only under a mouse or trackpad pointer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaftTooltip(
    text: String,
    modifier: Modifier = Modifier,
    openOnLongPress: Boolean = true,
    placement: RaftTooltipPlacement = LocalRaftTooltipPlacement.current,
    content: @Composable () -> Unit,
) {
    val preview = LocalRaftTooltipPreview.current
    val side = LocalRaftTooltipSide.current
    val touchTimer = LocalRaftTooltipTouchTimer.current
    val colors = RaftTheme.colors
    // Persistent: open until dismissed. The touch path bounds it with a timeout.
    val state = rememberTooltipState(initialIsVisible = preview, isPersistent = true)
    val scope = rememberCoroutineScope()
    // TooltipBox puts [modifier] on an inner box, where parent-data modifiers (`align`,
    // `weight`) would be lost: the caller's modifier goes on this outer box instead.
    Box(modifier.raftTooltipGestures(state, scope, touchTimer, openOnLongPress), propagateMinConstraints = true) {
        val label: @Composable () -> Unit = {
            RaftText(
                text = text,
                modifier = Modifier.padding(horizontal = RaftTooltipMetrics.ExtraPaddingX),
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                color = colors.foregroundOnHud,
            )
        }
        val density = LocalDensity.current
        val spacing = with(density) { RaftTooltipMetrics.AnchorSpacing.roundToPx() }
        val frame = LocalRaftFrameInsets.current.toPx(density)
        val sideProvider =
            if (side == null) {
                null
            } else {
                remember(side, spacing, frame) { RaftTooltipSidePositionProvider(side, spacing, frame) }
            }
        val placed =
            when (placement) {
                RaftTooltipPlacement.Above -> TooltipDefaults.rememberPlainTooltipPositionProvider(RaftTooltipMetrics.AnchorSpacing)
                // Material's own Below provider: its caret follows the side it picks.
                RaftTooltipPlacement.Below ->
                    TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below, RaftTooltipMetrics.AnchorSpacing)
            }
        // The host's frame insets: Material's placement, then moved out of them (none: as it is).
        val framed = remember(placed, spacing, frame) { if (frame.isZero) placed else RaftTooltipFrameProvider(placed, spacing, frame) }
        TooltipBox(
            positionProvider = sideProvider ?: framed,
            tooltip = {
                if (sideProvider == null) {
                    PlainTooltip(
                        modifier = Modifier.raftFloatShadow(RaftTheme.isDark, RaftRadius.Chip),
                        // Material3 1.4 (CMP 1.11+) replaced `caretSize` with a caret Shape;
                        // TooltipDefaults.caretShape(size) draws the same triangle.
                        caretShape = TooltipDefaults.caretShape(RaftTooltipMetrics.CaretSize),
                        shape = RoundedCornerShape(RaftRadius.Chip),
                        contentColor = colors.foregroundOnHud,
                        containerColor = colors.layerHud,
                        content = label,
                    )
                } else {
                    // Material's caret only points up or down: no caret of its own here, ours
                    // is drawn on the edge facing the anchor, on whichever side it opened.
                    PlainTooltip(
                        modifier =
                            Modifier
                                .raftFloatShadow(RaftTheme.isDark, RaftRadius.Chip)
                                .raftSideCaret(sideProvider, colors.layerHud),
                        shape = RoundedCornerShape(RaftRadius.Chip),
                        contentColor = colors.foregroundOnHud,
                        containerColor = colors.layerHud,
                        content = label,
                    )
                }
            },
            state = state,
            focusable = false,
            // No Material gestures, so no `semantics(mergeDescendants = true) { onLongClick }`
            // node round [content] (a second, unlabeled screen-reader stop).
            enableUserInput = false,
            content = content,
        )
    }
}

/**
 * Places a tooltip beside its anchor ([RaftTooltipSide]); [resolved] is the side it took (it
 * flips without room), read by the caret's draw.
 */
private class RaftTooltipSidePositionProvider(
    private val preferred: RaftTooltipSide,
    private val spacing: Int,
    private val frame: RaftFramePx,
) : PopupPositionProvider {
    var resolved by mutableStateOf(preferred)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val (offset, side) = raftTooltipSidePosition(anchorBounds, windowSize, popupContentSize, spacing, preferred, frame)
        if (resolved != side) resolved = side
        return offset
    }
}

/**
 * Where a [popup] beside [anchor] goes, [spacing] px from it on the [preferred] side, else on the
 * other side when only that one has room; vertically centred on the anchor and kept inside the
 * window. Returns the popup's top-left and the side it took.
 */
internal fun raftTooltipSidePosition(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    spacing: Int,
    preferred: RaftTooltipSide,
    frame: RaftFramePx = RaftFramePx.Zero,
): Pair<IntOffset, RaftTooltipSide> {
    val leftX = anchor.left - spacing - popup.width
    val rightX = anchor.right + spacing
    // Room on a side ends at the host's inset there (raft-ui: the tooltip flips away from it).
    val fitsLeft = leftX >= frame.left
    val fitsRight = rightX + popup.width <= window.width - frame.right
    val side =
        when (preferred) {
            RaftTooltipSide.Left -> if (!fitsLeft && fitsRight) RaftTooltipSide.Right else RaftTooltipSide.Left
            RaftTooltipSide.Right -> if (!fitsRight && fitsLeft) RaftTooltipSide.Left else RaftTooltipSide.Right
        }
    val x = if (side == RaftTooltipSide.Left) leftX else rightX
    val y = (anchor.top + (anchor.height - popup.height) / 2).coerceAtMost(window.height - popup.height).coerceAtLeast(frame.top)
    return IntOffset(x, y) to side
}

/** [RaftFrameInsets] in px. */
@Immutable
internal data class RaftFramePx(val top: Int = 0, val left: Int = 0, val right: Int = 0) {
    val isZero: Boolean get() = top <= 0 && left <= 0 && right <= 0

    companion object {
        val Zero = RaftFramePx()
    }
}

internal fun RaftFrameInsets.toPx(density: Density): RaftFramePx =
    if (isZero) {
        RaftFramePx.Zero
    } else {
        with(density) {
            RaftFramePx(top.coerceAtLeast(0.dp).roundToPx(), left.coerceAtLeast(0.dp).roundToPx(), right.coerceAtLeast(0.dp).roundToPx())
        }
    }

/** Material's placement ([inner]), then moved out of the host's [frame] insets ([raftTooltipClearOfFrame]). */
private class RaftTooltipFrameProvider(
    private val inner: PopupPositionProvider,
    private val spacing: Int,
    private val frame: RaftFramePx,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset =
        raftTooltipClearOfFrame(
            inner.calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize),
            anchorBounds,
            windowSize,
            popupContentSize,
            spacing,
            frame,
        )
}

/**
 * A tooltip at [placed] moved out of the host's [frame] insets (raft-ui #860, up to the inset, no
 * gap): one that would open up into the top band opens [spacing] below [anchor] instead, and one
 * reaching into a side inset slides along toward the middle, as far as the window allows.
 */
internal fun raftTooltipClearOfFrame(
    placed: IntOffset,
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    spacing: Int,
    frame: RaftFramePx,
): IntOffset {
    if (frame.isZero) return placed
    val y = if (placed.y < frame.top && placed.y < anchor.top) anchor.bottom + spacing else placed.y
    val minX = frame.left
    val maxX = window.width - frame.right - popup.width
    val x = if (minX <= maxX) placed.x.coerceIn(minX, maxX) else placed.x
    return IntOffset(x, y)
}

/**
 * The tooltip's caret ([RaftTooltipMetrics.CaretSize], the same triangle turned on its side) in
 * [color], hanging off the bubble's edge toward the anchor and centred on it, outside the
 * bubble's bounds (as Material's own caret and the float shadow are).
 */
private fun Modifier.raftSideCaret(provider: RaftTooltipSidePositionProvider, color: Color): Modifier =
    drawBehind {
        val width = RaftTooltipMetrics.CaretSize.width.toPx()
        val depth = RaftTooltipMetrics.CaretSize.height.toPx()
        val cy = size.height / 2f
        // The bubble is left of the anchor: the caret is on its right edge, pointing right.
        val pointsRight = provider.resolved == RaftTooltipSide.Left
        val base = if (pointsRight) size.width else 0f
        val tip = if (pointsRight) base + depth else base - depth
        val path =
            Path().apply {
                moveTo(base, cy - width / 2f)
                lineTo(tip, cy)
                lineTo(base, cy + width / 2f)
                close()
            }
        drawPath(path, color)
    }

/** Opens [state] for [RaftTooltipMetrics.TouchDuration] (outside the restricted pointer scope). */
@OptIn(ExperimentalMaterial3Api::class)
private fun CoroutineScope.showForTouch(
    state: TooltipState,
    timer: suspend (Long) -> Unit,
) {
    launch {
        val show = launch { state.show(MutatePriority.UserInput) }
        timer(RaftTooltipMetrics.TouchDuration)
        // Cancelling the show closes it (no-op if a hover or another press took over).
        show.cancel()
    }
}

/**
 * Hover (mouse) opens [state] until the pointer leaves; with [onLongPress], a touch / stylus
 * long press opens it for [RaftTooltipMetrics.TouchDuration] and eats the rest of that
 * gesture so [content] doesn't click. Runs in the Initial pass and consumes nothing else, so
 * [content]'s own clicks, presses and hover are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.raftTooltipGestures(
    state: TooltipState,
    scope: CoroutineScope,
    touchTimer: suspend (Long) -> Unit,
    onLongPress: Boolean,
): Modifier =
    then(if (onLongPress) Modifier.raftTooltipLongPress(state, scope, touchTimer) else Modifier)
        .raftTooltipHover(state, scope)

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.raftTooltipLongPress(
    state: TooltipState,
    scope: CoroutineScope,
    touchTimer: suspend (Long) -> Unit,
): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val pass = PointerEventPass.Initial
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            if (down.type != PointerType.Touch && down.type != PointerType.Stylus) return@awaitEachGesture
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(pass) }
            } catch (_: PointerEventTimeoutCancellationException) {
                scope.showForTouch(state, touchTimer)
                // The long press was the tooltip's: swallow it up to the release.
                do {
                    val event = awaitPointerEvent(pass)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.raftTooltipHover(
    state: TooltipState,
    scope: CoroutineScope,
): Modifier =
    pointerInput(state) {
        var hover: Job? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter -> {
                        hover?.cancel()
                        hover = scope.launch { state.show(MutatePriority.UserInput) }
                    }
                    PointerEventType.Exit -> {
                        hover?.cancel()
                        hover = null
                        state.dismiss()
                    }
                }
            }
        }
    }
