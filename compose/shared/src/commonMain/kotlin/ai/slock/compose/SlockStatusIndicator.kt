package ai.slock.compose

import androidx.compose.runtime.State
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.animation.core.RepeatMode
import com.tencent.kuikly.compose.animation.core.animateFloat
import com.tencent.kuikly.compose.animation.core.infiniteRepeatable
import com.tencent.kuikly.compose.animation.core.rememberInfiniteTransition
import com.tencent.kuikly.compose.animation.core.tween
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

internal enum class SlockStatusIndicatorShape {
    Circle,
    Square
}

internal object SlockStatusIndicatorTokens {
    val Shape: SlockStatusIndicatorShape = SlockStatusIndicatorShape.Circle
    val AvatarSize: Dp = 9.dp
    val InlineSize: Dp = 10.dp
    val ServerUnreadSize: Dp = 10.dp
    val BorderWidth: Dp = SlockStroke.Thin
    val BorderColor: Color = SlockColor.BorderBlack
}

@Composable
internal fun SlockStatusIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = SlockStatusIndicatorTokens.InlineSize,
    shape: SlockStatusIndicatorShape = SlockStatusIndicatorTokens.Shape,
    borderColor: Color = SlockStatusIndicatorTokens.BorderColor,
    borderWidth: Dp = SlockStatusIndicatorTokens.BorderWidth,
    pulsing: Boolean = false
) {
    val pulseProgressState: State<Float>? =
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "slock-status-pulse")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 1150),
                        repeatMode = RepeatMode.Restart
                    ),
                label = "slock-status-pulse-progress"
            )
        } else {
            null
        }
    // Status dot layout uses a fixed `pulsePadding` slot on every render — pulse and non-pulse
    // states both occupy the same outer Canvas footprint and draw the visible dot at the same
    // x/y position inside it, so callers that anchor the indicator (row-end `Modifier.align(End)`,
    // `SlockAvatarWithStatus.align(BottomEnd)`, inline timestamp column, etc.) get identical dot
    // alignment regardless of `pulsing`. The pulse ring (alpha-blended expanding circle) is the
    // only thing that varies — it draws inside the extra `pulsePadding` area when `pulsing` is true.
    val pulsePadding = 5.dp
    Canvas(modifier = modifier.size(size + pulsePadding)) {
        // Read the animation state in the draw phase so pulsing only invalidates the Canvas draw,
        // not the SlockStatusIndicator composable group every frame.
        val pulseProgress = pulseProgressState?.value ?: 0f
        val stroke = borderWidth.toPx()
        val halfStroke = stroke / 2f
        val pulseInset = pulsePadding.toPx() / 2f
        val pulseAlpha = ((1f - pulseProgress) * 0.28f).coerceIn(0f, 0.28f)
        when (shape) {
            SlockStatusIndicatorShape.Circle -> {
                val coreDiameter = (minOf(this.size.width, this.size.height) - pulsePadding.toPx()).coerceAtLeast(0f)
                val radius = (coreDiameter - stroke).coerceAtLeast(0f) / 2f
                val maxRadius = (minOf(this.size.width, this.size.height) - stroke).coerceAtLeast(0f) / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                if (pulsing) {
                    drawCircle(
                        color = color.copy(alpha = pulseAlpha),
                        radius = radius + ((maxRadius - radius).coerceAtLeast(0f) * pulseProgress),
                        center = center,
                        style = Fill
                    )
                }
                drawCircle(color = color, radius = radius, center = center, style = Fill)
                if (stroke > 0f) {
                    drawCircle(color = borderColor, radius = radius, center = center, style = Stroke(width = stroke))
                }
            }
            SlockStatusIndicatorShape.Square -> {
                val fillSize =
                    Size(
                        width = (this.size.width - stroke - pulsePadding.toPx()).coerceAtLeast(0f),
                        height = (this.size.height - stroke - pulsePadding.toPx()).coerceAtLeast(0f)
                    )
                if (pulsing) {
                    val expandedSize =
                        Size(
                            width = (fillSize.width + (pulseInset * 2f * pulseProgress)).coerceAtLeast(0f),
                            height = (fillSize.height + (pulseInset * 2f * pulseProgress)).coerceAtLeast(0f)
                        )
                    drawRect(
                        color = color.copy(alpha = pulseAlpha),
                        topLeft =
                            Offset(
                                (this.size.width - expandedSize.width) / 2f,
                                (this.size.height - expandedSize.height) / 2f
                            ),
                        size = expandedSize,
                        style = Fill
                    )
                }
                drawRect(
                    color = color,
                    topLeft = Offset(halfStroke + pulseInset, halfStroke + pulseInset),
                    size = fillSize,
                    style = Fill
                )
                if (stroke > 0f) {
                    drawRect(
                        color = borderColor,
                        topLeft = Offset(halfStroke + pulseInset, halfStroke + pulseInset),
                        size = fillSize,
                        style = Stroke(width = stroke)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SlockAvatarWithStatus(
    statusColor: Color?,
    modifier: Modifier = Modifier,
    avatarSize: Dp,
    statusSize: Dp = SlockStatusIndicatorTokens.AvatarSize,
    shape: SlockStatusIndicatorShape = SlockStatusIndicatorTokens.Shape,
    statusPulsing: Boolean = false,
    avatar: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.size(avatarSize)) {
        Box(modifier = Modifier.align(Alignment.Center), content = avatar)
        statusColor?.let {
            SlockStatusIndicator(
                color = it,
                size = statusSize,
                shape = shape,
                pulsing = statusPulsing,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
