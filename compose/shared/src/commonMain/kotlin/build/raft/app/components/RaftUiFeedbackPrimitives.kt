package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftAppRadius
import build.raft.app.RaftStatusIndicator
import build.raft.app.RaftStatusIndicatorShape
import build.raft.app.raftArgbHex
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import androidx.compose.ui.text.font.FontWeight
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class RaftCheckMarkerShape {
    Square,
    Circle
}

internal enum class RaftCheckMarkerTone(val background: Color, val foregroundArgb: String) {
    BlackFill(RaftColor.TextBlack, "#FFFFFFFF"),
    YellowFill(RaftColor.BrandYellow, "#FF000000")
}

internal enum class RaftProgressBarTone(val color: Color) {
    Pink(RaftColor.AccentPink),
    Cyan(RaftColor.AccentCyan),
    Lime(RaftColor.AccentLime),
    Orange(RaftColor.AccentOrange)
}

internal enum class RaftAppSkeletonVariant {
    Line,
    Block,
    Circle
}

internal const val RaftSpinnerActiveSweepAngle = 46f

/**
 * raft-ui Spinner sizes: a 2x2 grid of [pixel] squares [gap] apart
 * (`--spinner-pixel` / `--spinner-gap`). [brutalDiameter] is the legacy arc's box.
 */
internal enum class RaftAppSpinnerSize(val pixel: Dp, val gap: Dp, val brutalDiameter: Dp) {
    Xs(4.dp, 1.5.dp, 10.dp),
    Sm(5.dp, 2.dp, 16.dp),
    Md(6.dp, 2.dp, 20.dp),
    Lg(8.dp, 3.dp, 32.dp);

    /** Edge of the grid: two pixels and the gap between them. */
    val extent: Dp get() = pixel * 2 + gap
}

/** raft-ui `raft-spinner-pixel` cycle: 1s, in four `steps(1)` quarters. */
internal const val RaftAppSpinnerCycleMillis = 1000
internal const val RaftAppSpinnerStepMillis = RaftAppSpinnerCycleMillis / 4

/** Lit pixel opacity and the resting opacity of the other three (`opacity-20`). */
internal const val RaftSpinnerPixelRestAlpha = 0.2f

/**
 * Grid cell (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right, the DOM order) lit at
 * [step]. raft-ui delays the children 0 / 0.25 / 0.75 / 0.5s, so the light walks clockwise:
 * top-left, top-right, bottom-right, bottom-left.
 */
internal fun raftAppSpinnerLitCell(step: Int): Int = RaftSpinnerClockwiseCells[((step % 4) + 4) % 4]

/** Animation step at [elapsedMillis] into the cycle. */
internal fun raftSpinnerStepAt(elapsedMillis: Int): Int =
    ((elapsedMillis % RaftAppSpinnerCycleMillis + RaftAppSpinnerCycleMillis) % RaftAppSpinnerCycleMillis) / RaftAppSpinnerStepMillis

private val RaftSpinnerClockwiseCells = intArrayOf(0, 1, 3, 2)

@Composable
internal fun RaftAttentionDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = RaftColor.AccentPink
) {
    // raft attention pixel: 9px pink (`bg-attention`) square, 2px radius, 1.5px sticker-ink
    // edge; the legacy 10dp default lands on the 9px DS pixel, smaller sizes keep a 1px edge.
    val pixel = if (size == 10.dp) RaftMetrics.StatusSize else size
    RaftStatusIndicator(
        color = if (color == RaftColor.AccentPink) RaftColor.Attention else color,
        modifier = modifier,
        size = pixel,
        shape = RaftStatusIndicatorShape.Square,
        borderColor = RaftColor.StickerInk,
        borderWidth = if (pixel < 8.dp) RaftAppStroke.Thin else RaftAppStroke.Control
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftAppStatusDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    // react StatusDot.tsx defaults to bg-gray-400 (#99A1AF); no production call
    // site relies on this default today, only the states fixture exercises it.
    color: Color = RaftColor.StatusNeutralGray,
    pulsing: Boolean = false
) {
    // raft presence light: square 9px pixel with a 1.5px sticker-ink edge; the neutral
    // default is `--presence-offline`. Raft status lights never animate.
    RaftStatusIndicator(
        color = if (color == RaftColor.StatusNeutralGray) RaftColor.PresenceOffline else color,
        modifier = modifier,
        size = if (size == 10.dp) RaftMetrics.StatusSize else size,
        shape = RaftStatusIndicatorShape.Square,
        borderColor = RaftColor.StickerInk,
        borderWidth = RaftAppStroke.Control
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftCheckMarker(
    checked: Boolean,
    modifier: Modifier = Modifier,
    shape: RaftCheckMarkerShape = RaftCheckMarkerShape.Square,
    size: Dp = 14.dp,
    tone: RaftCheckMarkerTone = RaftCheckMarkerTone.BlackFill,
    disabled: Boolean = false
) {
    RaftLookCheckMarker(checked = checked, modifier = modifier, shape = shape, size = size, disabled = disabled)
}

/**
 * raft-ui 0.6.0 checkbox / radio indicator at the caller's size. Square = checkbox
 * (sticker radius, `--foreground` fill + inverse check when checked); circle = radio
 * (card fill, 1.5dp `--line-strong` ring, 8dp `--foreground` dot when checked; raft
 * radios never take the yellow fill). Disabled = `--foreground-disabled` on `--fill-muted`.
 */
@Composable
private fun RaftLookCheckMarker(
    checked: Boolean,
    modifier: Modifier,
    shape: RaftCheckMarkerShape,
    size: Dp,
    disabled: Boolean
) {
    val circle = shape == RaftCheckMarkerShape.Circle
    val boxShape = RoundedCornerShape(if (circle) size / 2 else RaftAppRadius.Sticker)
    val fill =
        when {
            !circle && checked && !disabled -> RaftColor.TextBlack
            !circle && checked -> RaftColor.ForegroundDisabled
            disabled -> RaftColor.FillMuted
            else -> RaftColor.Card
        }
    val edge =
        when {
            disabled -> RaftColor.ForegroundDisabled
            checked && !circle -> RaftColor.TextBlack
            else -> RaftColor.BorderBlack
        }
    Box(
        modifier =
            modifier
                .size(size)
                .background(fill, boxShape)
                .border(RaftAppStroke.Control, edge, boxShape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            if (circle) {
                val dot = (size.value * 0.4f).dp
                Box(
                    modifier =
                        Modifier
                            .size(dot)
                            .background(
                                if (disabled) RaftColor.ForegroundDisabled else RaftColor.TextBlack,
                                RoundedCornerShape(dot / 2)
                            )
                )
            } else {
                RaftLucideIcon(
                    RaftLucideIconName.Check,
                    modifier = Modifier.size((size.value * 0.7f).dp),
                    color = raftArgbHex(if (disabled) RaftColor.Card else RaftColor.ForegroundInverse),
                    strokeWidth = 2f
                )
            }
        }
    }
}

@Composable
internal fun RaftProgressBar(
    value: Int?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    tone: RaftProgressBarTone = RaftProgressBarTone.Pink,
    label: String = "",
    showPercent: Boolean = false
) {
    val percent = value?.coerceIn(0, 100)
    RaftLookProgressBar(percent = percent, modifier = modifier, tone = tone, label = label, showPercent = showPercent)
}

/**
 * raft-ui 0.6.0 Progress (web `ProgressBar`): optional header (13/18 semibold label, mono
 * 12/16 hint percent, 6dp gap), an 8dp `--fill-muted` track with sticker radius, and an
 * indicator in the tone colour with a 1.5dp sticker-ink edge. raft has no pink progress:
 * pink and cyan both map to `information`.
 */
@Composable
private fun RaftLookProgressBar(
    percent: Int?,
    modifier: Modifier,
    tone: RaftProgressBarTone,
    label: String,
    showPercent: Boolean
) {
    val indicatorColor =
        when (tone) {
            RaftProgressBarTone.Pink,
            RaftProgressBarTone.Cyan -> RaftColor.Info
            RaftProgressBarTone.Lime -> RaftColor.Success
            RaftProgressBarTone.Orange -> RaftColor.Warning
        }
    val shape = RoundedCornerShape(RaftAppRadius.Sticker)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank() || (showPercent && percent != null)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp).withRaftFontWeight(FontWeight.SemiBold),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (showPercent && percent != null) {
                    Text(
                        text = "$percent%",
                        style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                }
            }
        }
        // Indeterminate keeps the brutal 40% placeholder width (no animation here).
        val fraction = ((percent ?: 40).toFloat() / 100f).coerceIn(0f, 1f)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(shape)
                    .background(RaftColor.FillMuted, shape)
        ) {
            if (fraction > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .background(indicatorColor, shape)
                            .border(RaftAppStroke.Control, RaftColor.StickerInk, shape)
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftAppSkeleton(
    modifier: Modifier = Modifier,
    variant: RaftAppSkeletonVariant = RaftAppSkeletonVariant.Block
) {
    // raft-ui Skeleton: `--fill-muted` blocks with the sticker radius; the "circle"
    // variant is a rounded square too (raft avatars are square).
    Box(modifier = modifier.background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Sticker)))
}

@Composable
internal fun RaftSkeletonRow(
    modifier: Modifier = Modifier,
    avatar: Boolean = false,
    avatarSize: Dp = 18.dp,
    lineWidths: List<Dp> = listOf(156.dp, 96.dp)
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (avatar) {
            RaftAppSkeleton(modifier = Modifier.size(avatarSize), variant = RaftAppSkeletonVariant.Circle)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lineWidths.forEach { width ->
                RaftAppSkeleton(modifier = Modifier.width(width).height(12.dp), variant = RaftAppSkeletonVariant.Line)
            }
        }
    }
}

@Composable
internal fun RaftAppSpinner(
    modifier: Modifier = Modifier,
    size: RaftAppSpinnerSize = RaftAppSpinnerSize.Sm,
    inverse: Boolean = false,
    // A fixed step instead of the animation, so still captures are deterministic.
    stillStep: Int? = null
) {
    // The design system's spinner, in the app's theme-following ink.
    RaftSpinner(
        modifier = modifier,
        size = RaftSpinnerSize.valueOf(size.name),
        color = if (inverse) RaftColor.ForegroundInverse else RaftColor.TextBlack,
        stillStep = stillStep
    )
}
