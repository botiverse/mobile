package ai.slock.compose

import androidx.compose.runtime.Composable
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
    borderWidth: Dp = SlockStatusIndicatorTokens.BorderWidth
) {
    Canvas(modifier = modifier.size(size)) {
        val stroke = borderWidth.toPx()
        val halfStroke = stroke / 2f
        when (shape) {
            SlockStatusIndicatorShape.Circle -> {
                val radius = (minOf(this.size.width, this.size.height) - stroke).coerceAtLeast(0f) / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(color = color, radius = radius, center = center, style = Fill)
                if (stroke > 0f) {
                    drawCircle(color = borderColor, radius = radius, center = center, style = Stroke(width = stroke))
                }
            }
            SlockStatusIndicatorShape.Square -> {
                val fillSize =
                    Size(
                        width = (this.size.width - stroke).coerceAtLeast(0f),
                        height = (this.size.height - stroke).coerceAtLeast(0f)
                    )
                drawRect(color = color, topLeft = Offset(halfStroke, halfStroke), size = fillSize, style = Fill)
                if (stroke > 0f) {
                    drawRect(color = borderColor, topLeft = Offset(halfStroke, halfStroke), size = fillSize, style = Stroke(width = stroke))
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
    avatar: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.size(avatarSize)) {
        Box(modifier = Modifier.align(Alignment.Center), content = avatar)
        statusColor?.let {
            SlockStatusIndicator(
                color = it,
                size = statusSize,
                shape = shape,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
