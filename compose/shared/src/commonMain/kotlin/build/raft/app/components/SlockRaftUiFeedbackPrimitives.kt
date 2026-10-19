package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockRadius
import build.raft.app.SlockStatusIndicator
import build.raft.app.SlockStatusIndicatorShape
import build.raft.app.slockArgbHex
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.animation.core.LinearEasing
import com.tencent.kuikly.compose.animation.core.RepeatMode
import com.tencent.kuikly.compose.animation.core.animateFloat
import com.tencent.kuikly.compose.animation.core.infiniteRepeatable
import com.tencent.kuikly.compose.animation.core.rememberInfiniteTransition
import com.tencent.kuikly.compose.animation.core.tween
import com.tencent.kuikly.compose.ui.geometry.CornerRadius
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal enum class SlockCheckMarkerShape {
    Square,
    Circle
}

internal enum class SlockCheckMarkerTone(val background: Color, val foregroundArgb: String) {
    BlackFill(SlockColor.TextBlack, "#FFFFFFFF"),
    YellowFill(SlockColor.BrandYellow, "#FF000000")
}

internal enum class SlockProgressBarTone(val color: Color) {
    Pink(SlockColor.AccentPink),
    Cyan(SlockColor.AccentCyan),
    Lime(SlockColor.AccentLime),
    Orange(SlockColor.AccentOrange)
}

internal enum class SlockSkeletonVariant {
    Line,
    Block,
    Circle
}

internal const val SlockSpinnerActiveSweepAngle = 46f

/**
 * raft-ui Spinner sizes: a 2x2 grid of [pixel] squares [gap] apart
 * (`--spinner-pixel` / `--spinner-gap`). [brutalDiameter] is the legacy arc's box.
 */
internal enum class SlockSpinnerSize(val pixel: Dp, val gap: Dp, val brutalDiameter: Dp) {
    Xs(4.dp, 1.5.dp, 10.dp),
    Sm(5.dp, 2.dp, 16.dp),
    Md(6.dp, 2.dp, 20.dp),
    Lg(8.dp, 3.dp, 32.dp);

    /** Edge of the grid: two pixels and the gap between them. */
    val extent: Dp get() = pixel * 2 + gap
}

/** raft-ui `raft-spinner-pixel` cycle: 1s, in four `steps(1)` quarters. */
internal const val SlockSpinnerCycleMillis = 1000
internal const val SlockSpinnerStepMillis = SlockSpinnerCycleMillis / 4

/** Lit pixel opacity and the resting opacity of the other three (`opacity-20`). */
internal const val SlockSpinnerPixelRestAlpha = 0.2f

/**
 * Grid cell (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right, the DOM order) lit at
 * [step]. raft-ui delays the children 0 / 0.25 / 0.75 / 0.5s, so the light walks clockwise:
 * top-left, top-right, bottom-right, bottom-left.
 */
internal fun slockSpinnerLitCell(step: Int): Int = SlockSpinnerClockwiseCells[((step % 4) + 4) % 4]

/** Animation step at [elapsedMillis] into the cycle. */
internal fun slockSpinnerStepAt(elapsedMillis: Int): Int =
    ((elapsedMillis % SlockSpinnerCycleMillis + SlockSpinnerCycleMillis) % SlockSpinnerCycleMillis) / SlockSpinnerStepMillis

private val SlockSpinnerClockwiseCells = intArrayOf(0, 1, 3, 2)

@Composable
internal fun SlockAttentionDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = SlockColor.AccentPink
) {
    if (slockRaftActive()) {
        // raft attention pixel: 9px pink (`bg-attention`) square, 2px radius, 1.5px sticker-ink
        // edge; the legacy 10dp default lands on the 9px DS pixel, smaller sizes keep a 1px edge.
        val pixel = if (size == 10.dp) SlockRaftMetrics.StatusSize else size
        SlockStatusIndicator(
            color = if (color == SlockColor.AccentPink) SlockColor.Attention else color,
            modifier = modifier,
            size = pixel,
            shape = SlockStatusIndicatorShape.Square,
            borderColor = SlockColor.StickerInk,
            borderWidth = if (pixel < 8.dp) SlockStroke.Thin else SlockStroke.Control
        )
        return
    }
    SlockStatusIndicator(color = color, modifier = modifier, size = size, borderWidth = SlockStroke.Thin)
}

@Composable
internal fun SlockStatusDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    // react StatusDot.tsx defaults to bg-gray-400 (#99A1AF); no production call
    // site relies on this default today, only the states fixture exercises it.
    color: Color = SlockColor.StatusNeutralGray,
    pulsing: Boolean = false
) {
    if (slockRaftActive()) {
        // raft presence light: square 9px pixel with a 1.5px sticker-ink edge; the neutral
        // default is `--presence-offline`. Raft status lights never animate.
        SlockStatusIndicator(
            color = if (color == SlockColor.StatusNeutralGray) SlockColor.PresenceOffline else color,
            modifier = modifier,
            size = if (size == 10.dp) SlockRaftMetrics.StatusSize else size,
            shape = SlockStatusIndicatorShape.Square,
            borderColor = SlockColor.StickerInk,
            borderWidth = SlockStroke.Control
        )
        return
    }
    SlockStatusIndicator(color = color, modifier = modifier, size = size, borderWidth = SlockStroke.Thin, pulsing = pulsing)
}

@Composable
internal fun SlockCheckMarker(
    checked: Boolean,
    modifier: Modifier = Modifier,
    shape: SlockCheckMarkerShape = SlockCheckMarkerShape.Square,
    size: Dp = 14.dp,
    tone: SlockCheckMarkerTone = SlockCheckMarkerTone.BlackFill,
    disabled: Boolean = false
) {
    if (slockRaftActive()) {
        SlockRaftCheckMarker(checked = checked, modifier = modifier, shape = shape, size = size, disabled = disabled)
        return
    }
    val borderWidth = SlockStroke.Default
    val fillColor = if (checked) tone.background else SlockColor.SurfaceWhite
    val contentAlpha = if (disabled) 0.50f else 1f
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = borderWidth.toPx()
            val halfStroke = stroke / 2f
            val fill = fillColor.copy(alpha = fillColor.alpha * contentAlpha)
            val border = SlockColor.BorderBlack.copy(alpha = contentAlpha)
            if (shape == SlockCheckMarkerShape.Circle) {
                val radius = (minOf(this.size.width, this.size.height) - stroke).coerceAtLeast(0f) / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(fill, radius = radius, center = center, style = Fill)
                drawCircle(border, radius = radius, center = center, style = Stroke(width = stroke))
            } else {
                val rectSize = Size(
                    width = (this.size.width - stroke).coerceAtLeast(0f),
                    height = (this.size.height - stroke).coerceAtLeast(0f)
                )
                drawRect(fill, topLeft = Offset(halfStroke, halfStroke), size = rectSize, style = Fill)
                drawRect(border, topLeft = Offset(halfStroke, halfStroke), size = rectSize, style = Stroke(width = stroke))
            }
        }
        if (checked) {
            SlockLucideIcon(
                SlockLucideIconName.Check,
                modifier = Modifier.size((size.value * 0.72f).dp),
                color = tone.foregroundArgb,
                strokeWidth = if (size <= 16.dp) 4f else 3f
            )
        }
    }
}

/**
 * raft-ui 0.6.0 checkbox / radio indicator at the caller's size. Square = checkbox
 * (sticker radius, `--foreground` fill + inverse check when checked); circle = radio
 * (card fill, 1.5dp `--line-strong` ring, 8dp `--foreground` dot when checked; raft
 * radios never take the yellow fill). Disabled = `--foreground-disabled` on `--fill-muted`.
 */
@Composable
private fun SlockRaftCheckMarker(
    checked: Boolean,
    modifier: Modifier,
    shape: SlockCheckMarkerShape,
    size: Dp,
    disabled: Boolean
) {
    val circle = shape == SlockCheckMarkerShape.Circle
    val boxShape = RoundedCornerShape(if (circle) size / 2 else SlockRadius.Sticker)
    val fill =
        when {
            !circle && checked && !disabled -> SlockColor.TextBlack
            !circle && checked -> SlockColor.ForegroundDisabled
            disabled -> SlockColor.FillMuted
            else -> SlockColor.Card
        }
    val edge =
        when {
            disabled -> SlockColor.ForegroundDisabled
            checked && !circle -> SlockColor.TextBlack
            else -> SlockColor.BorderBlack
        }
    Box(
        modifier =
            modifier
                .size(size)
                .background(fill, boxShape)
                .border(SlockStroke.Control, edge, boxShape),
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
                                if (disabled) SlockColor.ForegroundDisabled else SlockColor.TextBlack,
                                RoundedCornerShape(dot / 2)
                            )
                )
            } else {
                SlockLucideIcon(
                    SlockLucideIconName.Check,
                    modifier = Modifier.size((size.value * 0.7f).dp),
                    color = slockArgbHex(if (disabled) SlockColor.Card else SlockColor.ForegroundInverse),
                    strokeWidth = 2f
                )
            }
        }
    }
}

@Composable
internal fun SlockProgressBar(
    value: Int?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    tone: SlockProgressBarTone = SlockProgressBarTone.Pink,
    label: String = "",
    showPercent: Boolean = false
) {
    val percent = value?.coerceIn(0, 100)
    if (slockRaftActive()) {
        SlockRaftProgressBar(percent = percent, modifier = modifier, tone = tone, label = label, showPercent = showPercent)
        return
    }
    Column(modifier = modifier) {
        if (label.isNotBlank() || (showPercent && percent != null)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.60f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(modifier = Modifier.weight(1f))
                if (showPercent && percent != null) {
                    Text(
                        text = "$percent%",
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.60f),
                        maxLines = 1
                    )
                }
            }
        }
        val shadowOffset = 2.dp
        Box(modifier = Modifier.fillMaxWidth().height(16.dp + shadowOffset).padding(end = shadowOffset, bottom = shadowOffset)) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
            Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
                val stroke = SlockStroke.Default.toPx()
                val halfStroke = stroke / 2f
                val trackSize = Size(
                    width = (size.width - stroke).coerceAtLeast(0f),
                    height = (size.height - stroke).coerceAtLeast(0f)
                )
                val innerWidth = (size.width - stroke * 2f).coerceAtLeast(0f)
                val innerHeight = (size.height - stroke * 2f).coerceAtLeast(0f)
                val fillFraction = ((percent ?: 40).coerceIn(0, 100).toFloat() / 100f).coerceIn(0f, 1f)
                drawRect(
                    color = SlockColor.SurfaceWhite,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = trackSize,
                    style = Fill
                )
                if (fillFraction > 0f) {
                    drawRect(
                        color = tone.color,
                        topLeft = Offset(stroke, stroke),
                        size = Size(width = innerWidth * fillFraction, height = innerHeight),
                        style = Fill
                    )
                }
                drawRect(
                    color = SlockColor.BorderBlack,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = trackSize,
                    style = Stroke(width = stroke)
                )
            }
        }
    }
}

/**
 * raft-ui 0.6.0 Progress (web `ProgressBar`): optional header (13/18 semibold label, mono
 * 12/16 hint percent, 6dp gap), an 8dp `--fill-muted` track with sticker radius, and an
 * indicator in the tone colour with a 1.5dp sticker-ink edge. raft has no pink progress:
 * pink and cyan both map to `information`.
 */
@Composable
private fun SlockRaftProgressBar(
    percent: Int?,
    modifier: Modifier,
    tone: SlockProgressBarTone,
    label: String,
    showPercent: Boolean
) {
    val indicatorColor =
        when (tone) {
            SlockProgressBarTone.Pink,
            SlockProgressBarTone.Cyan -> SlockColor.Info
            SlockProgressBarTone.Lime -> SlockColor.Success
            SlockProgressBarTone.Orange -> SlockColor.Warning
        }
    val shape = RoundedCornerShape(SlockRadius.Sticker)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank() || (showPercent && percent != null)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.SemiBold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (showPercent && percent != null) {
                    Text(
                        text = "$percent%",
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.ForegroundHint,
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
                    .background(SlockColor.FillMuted, shape)
        ) {
            if (fraction > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .background(indicatorColor, shape)
                            .border(SlockStroke.Control, SlockColor.StickerInk, shape)
                )
            }
        }
    }
}

@Composable
internal fun SlockSkeleton(
    modifier: Modifier = Modifier,
    variant: SlockSkeletonVariant = SlockSkeletonVariant.Block
) {
    if (slockRaftActive()) {
        // raft-ui Skeleton: `--fill-muted` blocks with the sticker radius; the "circle"
        // variant is a rounded square too (raft avatars are square).
        Box(modifier = modifier.background(SlockColor.FillMuted, RoundedCornerShape(SlockRadius.Sticker)))
        return
    }
    when (variant) {
        SlockSkeletonVariant.Line,
        SlockSkeletonVariant.Block -> Box(modifier = modifier.background(SlockColor.TextBlack.copy(alpha = 0.10f)))
        SlockSkeletonVariant.Circle -> {
            Canvas(modifier = modifier) {
                val stroke = SlockStroke.Default.toPx()
                val radius = (minOf(size.width, size.height) - stroke).coerceAtLeast(0f) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(SlockColor.TextBlack.copy(alpha = 0.05f), radius = radius, center = center, style = Fill)
                drawCircle(SlockColor.BorderBlack, radius = radius, center = center, style = Stroke(width = stroke))
            }
        }
    }
}

@Composable
internal fun SlockSkeletonRow(
    modifier: Modifier = Modifier,
    avatar: Boolean = false,
    avatarSize: Dp = 18.dp,
    lineWidths: List<Dp> = listOf(156.dp, 96.dp)
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (avatar) {
            SlockSkeleton(modifier = Modifier.size(avatarSize), variant = SlockSkeletonVariant.Circle)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lineWidths.forEach { width ->
                SlockSkeleton(modifier = Modifier.width(width).height(12.dp), variant = SlockSkeletonVariant.Line)
            }
        }
    }
}

@Composable
internal fun SlockSpinner(
    modifier: Modifier = Modifier,
    size: SlockSpinnerSize = SlockSpinnerSize.Sm,
    inverse: Boolean = false,
    // A fixed step instead of the animation, so still captures are deterministic.
    stillStep: Int? = null
) {
    if (slockRaftActive()) {
        SlockRaftPixelSpinner(modifier = modifier, size = size, inverse = inverse, stillStep = stillStep)
        return
    }
    val track = if (inverse) SlockColor.SurfaceWhite.copy(alpha = 0.40f) else SlockColor.TextBlack.copy(alpha = 0.20f)
    val active = if (inverse) SlockColor.SurfaceWhite else SlockColor.TextBlack
    Canvas(modifier = modifier.size(size.brutalDiameter)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(width = this.size.width - stroke, height = this.size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = active,
            startAngle = -90f,
            sweepAngle = SlockSpinnerActiveSweepAngle,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke)
        )
    }
}

/**
 * raft-ui 0.7 Spinner: four [SlockSpinnerSize.pixel] squares (1dp radius) in a 2x2 grid,
 * drawn in the current ink (`--foreground`, or `--foreground-inverse` for `inverse`). Each
 * rests at 20% and one at a time is lit at full ink for a quarter of a 1s cycle, stepping
 * (no fade) clockwise from the top-left.
 */
@Composable
private fun SlockRaftPixelSpinner(
    modifier: Modifier,
    size: SlockSpinnerSize,
    inverse: Boolean,
    stillStep: Int?
) {
    val ink = if (inverse) SlockColor.ForegroundInverse else SlockColor.TextBlack
    val animatedStep: State<Int>? =
        if (stillStep == null) {
            val elapsed =
                rememberInfiniteTransition(label = "slock-spinner").animateFloat(
                    initialValue = 0f,
                    targetValue = SlockSpinnerCycleMillis.toFloat(),
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis = SlockSpinnerCycleMillis, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                    label = "slock-spinner-elapsed"
                )
            // The elapsed time changes every frame, the step only four times a cycle: draw
            // observes the derived step alone, so the canvas redraws 4x a second.
            remember(elapsed) { derivedStateOf { slockSpinnerStepAt(elapsed.value.toInt()) } }
        } else {
            null
        }
    Canvas(modifier = modifier.size(size.extent)) {
        // Read the step in the draw phase so a step change only redraws the canvas.
        val step = stillStep ?: animatedStep?.value ?: 0
        val lit = slockSpinnerLitCell(step)
        val pixel = size.pixel.toPx()
        val pitch = pixel + size.gap.toPx()
        val radius = CornerRadius(1.dp.toPx())
        for (cell in 0 until 4) {
            drawRoundRect(
                color = if (cell == lit) ink else ink.copy(alpha = ink.alpha * SlockSpinnerPixelRestAlpha),
                topLeft = Offset((cell % 2) * pitch, (cell / 2) * pitch),
                size = Size(pixel, pixel),
                cornerRadius = radius
            )
        }
    }
}
