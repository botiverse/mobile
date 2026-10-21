package build.raft.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/** raft-ui Progress `size`: the track height (`h-1.5` / `h-2` / `h-2.5`). */
enum class RaftProgressBarSize(val track: Dp) {
    Sm(6.dp),
    Md(8.dp),
    Lg(10.dp),
}

/**
 * raft-ui Progress `variant`. `information` and `accent` are the same as [Primary] and are
 * not repeated; `danger` is left out (the Progress doc forbids it).
 */
enum class RaftProgressBarVariant {
    /** Progress of any kind: the `info` (cyan) fill. */
    Primary,

    /** Usage at 90% of a limit or more: the `warning` (orange) fill. */
    Warning,
}

/** Indeterminate sweep cycle (`raft-progress-indeterminate 1.4s`). */
const val RaftProgressBarIndeterminateMillis = 1400

/**
 * raft-ui Progress: a `fill-muted` track (sticker radius) holding a fill that is a sticker
 * with a 1.5dp `sticker-ink` inset edge, optionally under a header row: [label] (footnote
 * semibold, ink) at the start and [valueText] (Space Mono meta, `foreground-hint`) at the end.
 *
 * - [value] is the fraction done, 0..1. When it reaches 1 the fill turns `success` (lime) on
 *   its own, as raft-ui's `data-complete`.
 * - [value] = null is the indeterminate bar: a half-width fill sweeping across the track.
 *   The Progress doc prefers a [RaftSpinner] when no percentage is known; use it only where
 *   a bar is already showing (a download waiting for its first byte). [animated] = false
 *   freezes the sweep (screenshots, reduced motion).
 *
 * Semantics: a progress bar with its value (indeterminate without one), named by [label]
 * or [contentDescription].
 */
@Composable
fun RaftProgressBar(
    value: Float?,
    modifier: Modifier = Modifier,
    variant: RaftProgressBarVariant = RaftProgressBarVariant.Primary,
    size: RaftProgressBarSize = RaftProgressBarSize.Md,
    label: String? = null,
    valueText: String? = null,
    contentDescription: String? = label,
    animated: Boolean = true,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val fraction = value?.coerceIn(0f, 1f)
    val fill =
        when {
            fraction != null && fraction >= 1f -> RaftUiPalette.success
            variant == RaftProgressBarVariant.Warning -> RaftUiPalette.warning
            else -> RaftUiPalette.info
        }
    val track = colors.fillMuted
    val edge = RaftUiPalette.stickerInk
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            progressBarRangeInfo =
                if (fraction == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f)
            contentDescription?.let { this.contentDescription = it }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (valueText != null) {
                    RaftText(
                        text = valueText,
                        style = typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                    )
                }
            }
        }
        if (fraction == null) {
            val sweep =
                if (animated) {
                    val transition = rememberInfiniteTransition(label = "raft-progress")
                    transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(RaftProgressBarIndeterminateMillis, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart,
                            ),
                        label = "raft-progress-sweep",
                    ).value
                } else {
                    RaftProgressBarStillSweep
                }
            Canvas(Modifier.fillMaxWidth().height(size.track)) {
                drawTrack(track)
                // The half-width fill enters from the start edge and leaves past the end.
                val segment = this.size.width / 2f
                val start = -segment + (this.size.width + segment) * sweep
                clipRect { drawFill(Offset(start, 0f), Size(segment, this@Canvas.size.height), fill, edge) }
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height(size.track)) {
                drawTrack(track)
                if (fraction > 0f) {
                    drawFill(Offset.Zero, Size(this.size.width * fraction, this.size.height), fill, edge)
                }
            }
        }
    }
}

/** Sweep position of a frozen indeterminate bar: the fill centred on the track. */
internal const val RaftProgressBarStillSweep = 0.5f

private fun DrawScope.drawTrack(color: Color) {
    drawRoundRect(color = color, cornerRadius = CornerRadius(RaftRadius.Sticker.toPx()))
}

private fun DrawScope.drawFill(topLeft: Offset, fillSize: Size, color: Color, edge: Color) {
    val radius = CornerRadius(RaftRadius.Sticker.toPx())
    drawRoundRect(color = color, topLeft = topLeft, size = fillSize, cornerRadius = radius)
    // inset-ring-[1.5px] inset-ring-sticker-ink: the stroke sits inside the fill.
    val stroke = RaftStroke.Control.toPx()
    if (fillSize.width <= stroke * 2f) return
    val half = stroke / 2f
    drawRoundRect(
        color = edge,
        topLeft = Offset(topLeft.x + half, topLeft.y + half),
        size = Size(fillSize.width - stroke, fillSize.height - stroke),
        cornerRadius = CornerRadius((radius.x - half).coerceAtLeast(0f)),
        style = Stroke(width = stroke),
    )
}
