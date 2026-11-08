package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.gesture.raftKeepsHorizontalDrags
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.roundToInt

/**
 * A slider: picks a whole number from [valueRange] by dragging, tapping the track, the arrow
 * keys (one [step]; ←/→ follow the layout direction), Page Up / Page Down (a tenth of the range,
 * in whole steps) or Home / End. raft-ui has no slider; this one is built from the parts it does have:
 * - the [RaftProgressBar] header row: [label] (footnote semibold, ink) at the start and
 *   [valueText] (Space Mono meta, `foreground-hint`) at the end;
 * - a 6dp `fill-muted` track (sticker radius) whose filled part is the `selected` (yellow)
 *   sticker with its 1.5dp `sticker-ink` inset edge, as a Progress fill;
 * - the [RaftSwitch] knob as the thumb: a 22dp `sticker-paper` circle in a 1.5dp `sticker-ink`
 *   ring, with the focus ring under keyboard focus.
 * The touch target is 44dp tall. Disabled greys the fill and the thumb's ring. Right to left, the
 * track fills from the right and the thumb starts there.
 *
 * [onValueChange] fires on every move (a drag reports as it goes); a caller that does heavy work
 * per value debounces it. Semantics: a range with its value and its [step]s ([stateDescription],
 * by default [valueText], as its state), adjustable through `setProgress`, named by [label] or
 * [contentDescription], disabled when not [enabled]. [stateDescription] gives a screen reader the
 * value when the caller shows it elsewhere (no [valueText] header).
 */
@Composable
fun RaftSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    label: String? = null,
    valueText: String? = null,
    contentDescription: String? = label,
    stateDescription: String? = valueText,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    // Read unconditionally: a composable read inside `&&` gets no group of its own (#3356).
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val first = valueRange.first
    val last = maxOf(valueRange.last, first + 1)
    val current = value.coerceIn(first, last)
    val fraction = (current - first).toFloat() / (last - first)
    val latestValue by rememberUpdatedState(current)
    val latestChange by rememberUpdatedState(onValueChange)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Whole steps between the ends (the ends excluded), as ProgressBarRangeInfo counts them.
    val steps = ((last - first) / step.coerceAtLeast(1) - 1).coerceAtLeast(0)
    val pageStep = raftSliderPageStep(first, last, step)
    fun snap(raw: Float): Int {
        val stepped = first + (((raw - first) / step).roundToInt() * step)
        return stepped.coerceIn(first, last)
    }
    fun emit(next: Int) {
        if (next != latestValue) latestChange(next)
    }
    val fill = if (enabled) RaftUiPalette.selected else colors.foregroundDisabled
    val edge = if (enabled) RaftUiPalette.stickerInk else colors.foregroundDisabled
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (label != null || valueText != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = label.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = if (enabled) colors.foreground else colors.foregroundDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (valueText != null) {
                    RaftText(text = valueText, style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1)
                }
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(RaftSliderTouchHeight)
                .semantics(mergeDescendants = true) {
                    progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), first.toFloat()..last.toFloat(), steps = steps)
                    contentDescription?.let { this.contentDescription = it }
                    stateDescription?.let { this.stateDescription = it }
                    if (!enabled) disabled()
                    if (enabled) {
                        setProgress { target ->
                            emit(snap(target))
                            true
                        }
                    }
                }
                .then(
                    if (enabled) {
                        Modifier
                            .raftKeepsHorizontalDrags()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                // ← / → move toward the track's start / end as drawn: mirrored right to left.
                                val forward = if (rtl) Key.DirectionLeft else Key.DirectionRight
                                val backward = if (rtl) Key.DirectionRight else Key.DirectionLeft
                                when (event.key) {
                                    backward, Key.DirectionDown -> emit((latestValue - step).coerceIn(first, last)).let { true }
                                    forward, Key.DirectionUp -> emit((latestValue + step).coerceIn(first, last)).let { true }
                                    Key.PageDown -> emit(snap((latestValue - pageStep).toFloat())).let { true }
                                    Key.PageUp -> emit(snap((latestValue + pageStep).toFloat())).let { true }
                                    Key.MoveHome -> emit(first).let { true }
                                    Key.MoveEnd -> emit(last).let { true }
                                    else -> false
                                }
                            }
                            .focusable(interactionSource = source)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumb = RaftSliderThumbSize
            val travel = (maxWidth - thumb).coerceAtLeast(0.dp)
            val trackPointer =
                if (enabled) {
                    Modifier
                        .pointerInput(first, last, step, rtl) {
                            val inset = thumb.toPx() / 2f
                            val span = (size.width - thumb.toPx()).coerceAtLeast(1f)
                            fun valueAt(x: Float): Int = snap(first + raftSliderFraction(x, size.width.toFloat(), inset, span, rtl) * (last - first))
                            detectTapGestures { offset -> emit(valueAt(offset.x)) }
                        }
                        .pointerInput(first, last, step, rtl) {
                            val inset = thumb.toPx() / 2f
                            val span = (size.width - thumb.toPx()).coerceAtLeast(1f)
                            fun valueAt(x: Float): Int = snap(first + raftSliderFraction(x, size.width.toFloat(), inset, span, rtl) * (last - first))
                            detectHorizontalDragGestures(
                                onDragStart = { offset -> emit(valueAt(offset.x)) },
                            ) { change, _ ->
                                change.consume()
                                emit(valueAt(change.position.x))
                            }
                        }
                } else {
                    Modifier
                }
            Box(Modifier.fillMaxWidth().height(RaftSliderTouchHeight).then(trackPointer), contentAlignment = Alignment.CenterStart) {
                Canvas(Modifier.fillMaxWidth().height(RaftSliderTrackHeight)) {
                    val radius = CornerRadius(RaftRadius.Sticker.toPx())
                    drawRoundRect(color = colors.fillMuted, cornerRadius = radius)
                    // The fill reaches the thumb's centre.
                    val fillWidth = thumb.toPx() / 2f + travel.toPx() * fraction
                    if (fillWidth > 0f) {
                        // A Canvas does not mirror: right to left, the fill grows from the right edge.
                        val fillLeft = if (rtl) size.width - fillWidth else 0f
                        drawRoundRect(color = fill, topLeft = Offset(fillLeft, 0f), size = Size(fillWidth, size.height), cornerRadius = radius)
                        val stroke = RaftStroke.Control.toPx()
                        if (fillWidth > stroke * 2f) {
                            val half = stroke / 2f
                            drawRoundRect(
                                color = edge,
                                topLeft = Offset(fillLeft + half, half),
                                size = Size(fillWidth - stroke, size.height - stroke),
                                cornerRadius = CornerRadius((radius.x - half).coerceAtLeast(0f)),
                                style = Stroke(width = stroke),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .offset(x = travel * fraction)
                        .size(thumb)
                        .raftFocusRing(focused, thumb / 2, colors)
                        .raftOuterRing(edge, RaftStroke.Control, thumb / 2)
                        .background(if (enabled) RaftUiPalette.stickerPaper else colors.fillMuted, CircleShape),
                )
            }
        }
    }
}

/**
 * Where along the track a pointer at [x] (px from the left of a [width] px track) sits, 0 at the
 * start and 1 at the end: [inset] in from each end (the thumb's half), over [span] px; the start
 * is the right edge right to left.
 */
internal fun raftSliderFraction(x: Float, width: Float, inset: Float, span: Float, rtl: Boolean): Float {
    val fromStart = if (rtl) width - x else x
    return ((fromStart - inset) / span).coerceIn(0f, 1f)
}

/** Page Up / Page Down move a tenth of the range, in whole steps (at least one). */
internal fun raftSliderPageStep(first: Int, last: Int, step: Int): Int {
    val unit = step.coerceAtLeast(1)
    return unit * ((last - first) / unit / 10).coerceAtLeast(1)
}

/** The slider's touch target height (the 44dp minimum). */
private val RaftSliderTouchHeight = 44.dp
private val RaftSliderTrackHeight = 6.dp
private val RaftSliderThumbSize = 22.dp
