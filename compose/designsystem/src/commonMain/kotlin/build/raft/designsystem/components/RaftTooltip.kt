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
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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

/**
 * Opens every [RaftTooltip] in the subtree, persistent (screenshot fixtures only: an
 * offscreen frame can neither hover nor hold a long press).
 */
internal val LocalRaftTooltipPreview = staticCompositionLocalOf { false }

/**
 * Waits out a long-press tooltip's [RaftTooltipMetrics.TouchDuration] before it closes
 * (`delay`). Tests swap in a gate they release, as the scene's effect dispatcher takes its
 * delays from the wall clock, not `mainClock`. A mouse-opened tooltip never calls it.
 */
internal val LocalRaftTooltipTouchTimer = staticCompositionLocalOf<suspend (durationMillis: Long) -> Unit> { { delay(it) } }

/**
 * raft-ui 0.8.0 Tooltip around [content]: a short label in the dark HUD bubble with a
 * small caret pointing at [content], above it (below when there is no room above).
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaftTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val preview = LocalRaftTooltipPreview.current
    val touchTimer = LocalRaftTooltipTouchTimer.current
    val colors = RaftTheme.colors
    // Persistent: open until dismissed. The touch path bounds it with a timeout.
    val state = rememberTooltipState(initialIsVisible = preview, isPersistent = true)
    val scope = rememberCoroutineScope()
    // TooltipBox puts [modifier] on an inner box, where parent-data modifiers (`align`,
    // `weight`) would be lost: the caller's modifier goes on this outer box instead.
    Box(modifier.raftTooltipGestures(state, scope, touchTimer), propagateMinConstraints = true) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(RaftTooltipMetrics.AnchorSpacing),
            tooltip = {
                PlainTooltip(
                    modifier = Modifier.raftFloatShadow(RaftTheme.isDark, RaftRadius.Chip),
                    // Material3 1.4 (CMP 1.12) replaced `caretSize` with a caret Shape;
                    // TooltipDefaults.caretShape(size) draws the same triangle.
                    caretShape = TooltipDefaults.caretShape(RaftTooltipMetrics.CaretSize),
                    shape = RoundedCornerShape(RaftRadius.Chip),
                    contentColor = colors.foregroundOnHud,
                    containerColor = colors.layerHud,
                ) {
                    RaftText(
                        text = text,
                        modifier = Modifier.padding(horizontal = RaftTooltipMetrics.ExtraPaddingX),
                        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.foregroundOnHud,
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
 * Hover (mouse) opens [state] until the pointer leaves; a touch / stylus long press opens it
 * for [RaftTooltipMetrics.TouchDuration] and eats the rest of that gesture so [content]
 * doesn't click. Runs in the Initial pass and consumes nothing else, so [content]'s own
 * clicks, presses and hover are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.raftTooltipGestures(
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
    }.pointerInput(state) {
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
