package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.tencent.kuikly.compose.animation.core.RepeatMode
import com.tencent.kuikly.compose.animation.core.animateFloat
import com.tencent.kuikly.compose.animation.core.infiniteRepeatable
import com.tencent.kuikly.compose.animation.core.rememberInfiniteTransition
import com.tencent.kuikly.compose.animation.core.tween
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
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
    // #90 React parity: bump avatar overlay size 9dp → 11dp to match React
    // `StatusDot size="lg"` (`size-[11px]`). Inline / server-unread sizes
    // match React `size="md"` (10dp ≈ 10px) and stay unchanged.
    val AvatarSize: Dp = 11.dp
    val InlineSize: Dp = 10.dp
    val ServerUnreadSize: Dp = 10.dp
    val BorderWidth: Dp = SlockStroke.Thin
    val BorderColor: Color get() = SlockColor.BorderBlack
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
    val pulseAlphaState: State<Float>? =
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "slock-status-pulse")
            transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.5f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 1000),
                        repeatMode = RepeatMode.Reverse
                    ),
                label = "slock-status-pulse-alpha"
            )
        } else {
            null
        }
    // Match React StatusDot's Tailwind `animate-pulse`: the whole dot breathes by opacity,
    // while its layout footprint stays exactly `size` for row-end and avatar overlay anchors.
    Canvas(modifier = modifier.size(size)) {
        // Read the animation state in the draw phase so pulsing only invalidates the Canvas draw,
        // not the SlockStatusIndicator composable group every frame.
        val pulseAlpha = pulseAlphaState?.value ?: 1f
        val fillColor = color.copy(alpha = color.alpha * pulseAlpha)
        val outlineColor = borderColor.copy(alpha = borderColor.alpha * pulseAlpha)
        val stroke = borderWidth.toPx()
        val halfStroke = stroke / 2f
        when (shape) {
            SlockStatusIndicatorShape.Circle -> {
                val radius = (minOf(this.size.width, this.size.height) - stroke).coerceAtLeast(0f) / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(color = fillColor, radius = radius, center = center, style = Fill)
                if (stroke > 0f) {
                    drawCircle(color = outlineColor, radius = radius, center = center, style = Stroke(width = stroke))
                }
            }
            SlockStatusIndicatorShape.Square -> {
                val fillSize =
                    Size(
                        width = (this.size.width - stroke).coerceAtLeast(0f),
                        height = (this.size.height - stroke).coerceAtLeast(0f)
                    )
                drawRect(
                    color = fillColor,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = fillSize,
                    style = Fill
                )
                if (stroke > 0f) {
                    drawRect(
                        color = outlineColor,
                        topLeft = Offset(halfStroke, halfStroke),
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
    // #90 React parity: React `StatusDot` avatar overlay uses
    // `className="absolute -bottom-0.5 -right-0.5"` — the dot is offset by
    // -0.5 units so it overflows the avatar corner. Mirror that here by
    // wrapping the dot in a Box that extends `statusSize / 2` past the
    // avatar bounds and aligning the dot to its BottomEnd.
    val statusOffset = statusSize / 2
    Box(modifier = modifier.size(avatarSize)) {
        Box(modifier = Modifier.align(Alignment.Center), content = avatar)
        statusColor?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 0.dp, bottom = 0.dp)
                    .offset(x = statusOffset / 2, y = statusOffset / 2)
                    .size(statusSize + statusOffset)
            ) {
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
}
