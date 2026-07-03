package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockStatusIndicator
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
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

@Composable
internal fun SlockAttentionDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = SlockColor.AccentPink
) {
    SlockStatusIndicator(color = color, modifier = modifier, size = size, borderWidth = SlockStroke.Thin)
}

@Composable
internal fun SlockStatusDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = SlockColor.EmptyStateGray,
    pulsing: Boolean = false
) {
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

@Composable
internal fun SlockProgressBar(
    value: Int?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    tone: SlockProgressBarTone = SlockProgressBarTone.Pink,
    label: String = "",
    showPercent: Boolean = false
) {
    val percent = value?.coerceIn(0, 100)
    Column(modifier = modifier) {
        if (label.isNotBlank() || (showPercent && percent != null)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.60f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(modifier = Modifier.weight(1f))
                if (showPercent && percent != null) {
                    Text(
                        text = "$percent%",
                        style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp),
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

@Composable
internal fun SlockSkeleton(
    modifier: Modifier = Modifier,
    variant: SlockSkeletonVariant = SlockSkeletonVariant.Block
) {
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
    size: Dp = 20.dp,
    inverse: Boolean = false
) {
    val track = if (inverse) SlockColor.SurfaceWhite.copy(alpha = 0.40f) else SlockColor.TextBlack.copy(alpha = 0.20f)
    val active = if (inverse) SlockColor.SurfaceWhite else SlockColor.TextBlack
    Canvas(modifier = modifier.size(size)) {
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
            sweepAngle = 85f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke)
        )
    }
}
