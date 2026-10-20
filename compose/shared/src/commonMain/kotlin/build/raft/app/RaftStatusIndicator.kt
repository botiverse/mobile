package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class RaftStatusIndicatorShape {
    Circle,
    Square
}

internal object RaftStatusIndicatorTokens {
    // raft-ui 0.6.0 status lights are square pixels (`rounded-[2px] border-[1.5px]
    // border-sticker-ink`). The app theme is always a raft register, so the default
    // shape/edge follow [RaftAppThemeState]; brutal-only previews pass their own.
    val Shape: RaftStatusIndicatorShape
        get() = if (RaftAppThemeState.theme.isAmbient) RaftStatusIndicatorShape.Square else RaftStatusIndicatorShape.Circle
    // #90 React parity: bump avatar overlay size 9dp → 11dp to match React
    // `StatusDot size="lg"` (`size-[11px]`). Inline / server-unread sizes
    // match React `size="md"` (10dp ≈ 10px) and stay unchanged.
    val AvatarSize: Dp = 11.dp
    // raft `md` presence / attention pixel is 9px.
    val InlineSize: Dp
        get() = if (RaftAppThemeState.theme.isAmbient) 9.dp else 10.dp
    val ServerUnreadSize: Dp
        get() = if (RaftAppThemeState.theme.isAmbient) 9.dp else 10.dp
    val BorderWidth: Dp
        get() = if (RaftAppThemeState.theme.isAmbient) RaftAppStroke.Control else RaftAppStroke.Thin
    val BorderColor: Color
        get() = if (RaftAppThemeState.theme.isAmbient) RaftColor.StickerInk else RaftColor.BorderBlack

    // raft-ui 0.8.0 AvatarBadge: the 7px light on xs/2xs avatars takes a 1px ink edge and a
    // 1px canvas cut-out; larger lights keep the default edge and a 2px cut-out.
    private val SmallLightMax: Dp = 7.dp

    fun cutoutWidth(statusSize: Dp): Dp = if (statusSize <= SmallLightMax) 1.dp else 2.dp

    fun avatarBorderWidth(statusSize: Dp): Dp =
        if (statusSize <= SmallLightMax) RaftAppStroke.Thin else BorderWidth
}

@Composable
internal fun RaftStatusIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = RaftStatusIndicatorTokens.InlineSize,
    shape: RaftStatusIndicatorShape = RaftStatusIndicatorTokens.Shape,
    borderColor: Color = RaftStatusIndicatorTokens.BorderColor,
    borderWidth: Dp = RaftStatusIndicatorTokens.BorderWidth,
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
        // not the RaftStatusIndicator composable group every frame.
        val pulseAlpha = pulseAlphaState?.value ?: 1f
        val fillColor = color.copy(alpha = color.alpha * pulseAlpha)
        val outlineColor = borderColor.copy(alpha = borderColor.alpha * pulseAlpha)
        val stroke = borderWidth.toPx()
        val halfStroke = stroke / 2f
        when (shape) {
            RaftStatusIndicatorShape.Circle -> {
                val radius = (minOf(this.size.width, this.size.height) - stroke).coerceAtLeast(0f) / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(color = fillColor, radius = radius, center = center, style = Fill)
                if (stroke > 0f) {
                    drawCircle(color = outlineColor, radius = radius, center = center, style = Stroke(width = stroke))
                }
            }
            RaftStatusIndicatorShape.Square -> {
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
internal fun RaftAvatarWithStatus(
    statusColor: Color?,
    modifier: Modifier = Modifier,
    avatarSize: Dp,
    statusSize: Dp = RaftStatusIndicatorTokens.AvatarSize,
    shape: RaftStatusIndicatorShape = RaftStatusIndicatorTokens.Shape,
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
                // raft-ui AvatarBadge cuts the light out of the avatar with a canvas-coloured
                // outline (`outline-2 outline-layer-canvas`; 1px on the xs/2xs/3xs avatars).
                val cutout = RaftStatusIndicatorTokens.cutoutWidth(statusSize)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = cutout, y = cutout)
                        .size(statusSize + cutout * 2)
                        .background(
                            RaftColor.Canvas,
                            if (shape == RaftStatusIndicatorShape.Circle) CircleShape else RoundedCornerShape(cutout)
                        )
                )
                RaftStatusIndicator(
                    color = it,
                    size = statusSize,
                    shape = shape,
                    borderWidth = RaftStatusIndicatorTokens.avatarBorderWidth(statusSize),
                    pulsing = statusPulsing,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
    }
}
