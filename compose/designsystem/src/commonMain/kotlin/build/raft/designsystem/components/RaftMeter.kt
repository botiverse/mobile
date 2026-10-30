package build.raft.designsystem.components

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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/** raft-ui Meter `size`: the track height (`h-1.5` / `h-2`). */
enum class RaftMeterSize(val track: Dp) {
    /** Several meters stacked in a card, popover or sheet (the limits of one account). */
    Sm(6.dp),

    /** A meter on its own. */
    Md(8.dp),
}

/** raft-ui Meter `data-level` (0.50): what is used decides the colour; there is no variant. */
enum class RaftMeterLevel {
    /** Under `warnAt` used: the `info` fill (never `success`). */
    Normal,

    /** `warnAt` or more used: the `warning` fill and a `warning-strong` value. */
    High,

    /** All of it used: a full `warning` bar and a `warning-strong` value. */
    Full,
}

/** raft-ui Meter's default `warnAt`: it warns from 80% used (the old "20% left"). */
const val RaftMeterDefaultWarnAt: Float = 0.8f

/**
 * raft-ui Meter's level (0.50): the share used is `used / max` (used clamped to 0..max);
 * [warnAt] is the share used (0..1, "at or above") from which it warns.
 */
fun raftMeterLevel(used: Float, max: Float = 100f, warnAt: Float = RaftMeterDefaultWarnAt): RaftMeterLevel {
    val share = if (max > 0f) used.coerceIn(0f, max) / max else 1f
    return when {
        share >= 1f -> RaftMeterLevel.Full
        share >= warnAt.coerceIn(0f, 1f) -> RaftMeterLevel.High
        else -> RaftMeterLevel.Normal
    }
}

/**
 * raft-ui Meter (`wip`, 0.50): how much of an allowance is used, such as a usage limit that
 * resets. The bar fills as it is used: a `fill-muted` track (sticker radius) holding a fill of
 * [used] / [max] that is a sticker with a 1.5dp `sticker-ink` inset edge. The colour follows
 * [raftMeterLevel]: `info` until [warnAt] of [max] is used, then `warning`; used up is a full
 * `warning` bar. Never `danger`, no `success`.
 *
 * The optional header is [label] (footnote semibold, ink) at the start and [valueText]
 * (footnote, tabular, `foreground-muted`; `warning-strong` from [warnAt] and when used up) at
 * the end, 6dp above the track. [valueText] says what is used, with the word ("62% used ·
 * resets in 3h", "Used up · resets in 2h") and is also what accessibility reads as the value;
 * [showValue] = false keeps it out of the header.
 *
 * raft-ui's deprecated `low` (the amount left) is not supported here: use [warnAt], the share used.
 */
@Composable
fun RaftMeter(
    used: Float,
    valueText: String,
    modifier: Modifier = Modifier,
    max: Float = 100f,
    warnAt: Float = RaftMeterDefaultWarnAt,
    size: RaftMeterSize = RaftMeterSize.Md,
    label: String? = null,
    showValue: Boolean = true,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val safeMax = if (max > 0f) max else 1f
    val fraction = (used.coerceIn(0f, safeMax) / safeMax).coerceIn(0f, 1f)
    val level = raftMeterLevel(used, safeMax, warnAt)
    val fill = if (level == RaftMeterLevel.Normal) RaftUiPalette.info else RaftUiPalette.warning
    val valueColor = if (level == RaftMeterLevel.Normal) colors.foregroundMuted else colors.warningStrong
    val edge = RaftUiPalette.stickerInk
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            stateDescription = valueText
            label?.let { contentDescription = it }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label != null || showValue) {
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
                if (showValue) {
                    RaftText(
                        text = valueText,
                        style = typography.footnote.copy(fontFeatureSettings = "tnum"),
                        color = valueColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(size.track)) {
            drawMeterTrack(colors.fillMuted)
            if (fraction > 0f) {
                drawMeterFill(Size(this.size.width * fraction, this.size.height), fill, edge)
            }
        }
    }
}

private fun DrawScope.drawMeterTrack(color: Color) {
    drawRoundRect(color = color, cornerRadius = CornerRadius(RaftRadius.Sticker.toPx()))
}

private fun DrawScope.drawMeterFill(fillSize: Size, color: Color, edge: Color) {
    val radius = CornerRadius(RaftRadius.Sticker.toPx())
    drawRoundRect(color = color, size = fillSize, cornerRadius = radius)
    // inset-ring-[1.5px] inset-ring-sticker-ink: the stroke sits inside the fill.
    val stroke = RaftStroke.Control.toPx()
    if (fillSize.width <= stroke * 2f) return
    val half = stroke / 2f
    drawRoundRect(
        color = edge,
        topLeft = Offset(half, half),
        size = Size(fillSize.width - stroke, fillSize.height - stroke),
        cornerRadius = CornerRadius((radius.x - half).coerceAtLeast(0f)),
        style = Stroke(width = stroke),
    )
}
