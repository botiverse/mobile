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

/** raft-ui Meter `data-level`: what is left decides the colour; there is no variant. */
enum class RaftMeterLevel {
    /** More than `low` left: the `info` fill, up to a full bar (never `success`). */
    Plenty,

    /** `low` or less left: the `warning` fill and a `warning-strong` value. */
    Low,

    /** Nothing left: an empty `warning-muted` track with a 1dp `warning` edge. */
    Empty,
}

/**
 * raft-ui Meter's level: what is left is `max - used` (used clamped to 0..max); `low` is "at
 * or below" and defaults to 20% of [max].
 */
fun raftMeterLevel(used: Float, max: Float = 100f, low: Float? = null): RaftMeterLevel {
    val left = max - used.coerceIn(0f, max)
    val lowAt = (low ?: (max * 0.2f)).coerceIn(0f, max)
    return when {
        left <= 0f -> RaftMeterLevel.Empty
        left <= lowAt -> RaftMeterLevel.Low
        else -> RaftMeterLevel.Plenty
    }
}

/**
 * raft-ui Meter (`wip`): how much of an allowance is left, such as a usage limit that resets.
 * It takes what is [used] (0..[max], as the provider reports it) and draws what is left, full
 * to start and draining: a `fill-muted` track (sticker radius) holding a fill that is a sticker
 * with a 1.5dp `sticker-ink` inset edge. The colour follows [raftMeterLevel].
 *
 * The optional header is [label] (footnote semibold, ink) at the start and [valueText]
 * (footnote, tabular, `foreground-muted`; `warning-strong` when low or used up) at the end,
 * 6dp above the track. [valueText] leads with what is left ("38% left · resets in 3h") and is
 * also what accessibility reads as the value; [showValue] = false keeps it out of the header.
 */
@Composable
fun RaftMeter(
    used: Float,
    valueText: String,
    modifier: Modifier = Modifier,
    max: Float = 100f,
    low: Float? = null,
    size: RaftMeterSize = RaftMeterSize.Md,
    label: String? = null,
    showValue: Boolean = true,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val safeMax = if (max > 0f) max else 1f
    val left = safeMax - used.coerceIn(0f, safeMax)
    val fraction = (left / safeMax).coerceIn(0f, 1f)
    val level = raftMeterLevel(used, safeMax, low)
    val fill = if (level == RaftMeterLevel.Low) RaftUiPalette.warning else RaftUiPalette.info
    val valueColor = if (level == RaftMeterLevel.Plenty) colors.foregroundMuted else colors.warningStrong
    val track = if (level == RaftMeterLevel.Empty) colors.warningMuted else colors.fillMuted
    val trackEdge = if (level == RaftMeterLevel.Empty) RaftUiPalette.warning else null
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
            drawMeterTrack(track, trackEdge)
            if (fraction > 0f) {
                drawMeterFill(Size(this.size.width * fraction, this.size.height), fill, edge)
            }
        }
    }
}

private fun DrawScope.drawMeterTrack(color: Color, edge: Color?) {
    val radius = CornerRadius(RaftRadius.Sticker.toPx())
    drawRoundRect(color = color, cornerRadius = radius)
    if (edge == null) return
    // inset-ring-1 inset-ring-(--meter-track-edge): the 1dp edge sits inside the track.
    val stroke = RaftStroke.Hairline.toPx()
    val half = stroke / 2f
    drawRoundRect(
        color = edge,
        topLeft = Offset(half, half),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((radius.x - half).coerceAtLeast(0f)),
        style = Stroke(width = stroke),
    )
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
