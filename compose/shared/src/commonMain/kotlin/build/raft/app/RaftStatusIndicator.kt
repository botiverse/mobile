package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
    // shape/edge are the raft ones; brutal-only previews pass their own.
    val Shape: RaftStatusIndicatorShape = RaftStatusIndicatorShape.Square
    // Avatar presence lights size per avatar step: see [RaftAvatarStep] / [RaftAvatarWithStatus].
    // raft `md` presence / attention pixel is 9px.
    val InlineSize: Dp = 9.dp
    val ServerUnreadSize: Dp = 9.dp
    val BorderWidth: Dp = RaftAppStroke.Control
    val BorderColor: Color
        get() = RaftColor.StickerInk
}

@Composable
internal fun RaftStatusIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = RaftStatusIndicatorTokens.InlineSize,
    shape: RaftStatusIndicatorShape = RaftStatusIndicatorTokens.Shape,
    borderColor: Color = RaftStatusIndicatorTokens.BorderColor,
    borderWidth: Dp = RaftStatusIndicatorTokens.BorderWidth,
    pulsing: Boolean = false,
    // raft-ui status pixel: `rounded-[2px]`; the 6px Status xs takes `rounded-[1px]`.
    cornerRadius: Dp = if (size <= 6.dp) 1.dp else 2.dp
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
                val radius = cornerRadius.toPx()
                val fillSize =
                    Size(
                        width = (this.size.width - stroke).coerceAtLeast(0f),
                        height = (this.size.height - stroke).coerceAtLeast(0f)
                    )
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = fillSize,
                    cornerRadius = CornerRadius((radius - halfStroke).coerceAtLeast(0f)),
                    style = Fill
                )
                if (stroke > 0f) {
                    drawRoundRect(
                        color = outlineColor,
                        topLeft = Offset(halfStroke, halfStroke),
                        size = fillSize,
                        cornerRadius = CornerRadius((radius - halfStroke).coerceAtLeast(0f)),
                        style = Stroke(width = stroke)
                    )
                }
            }
        }
    }
}

/**
 * raft-ui 0.8.0 Avatar + AvatarBadge: [avatar] in an [avatarSize] box with the presence
 * light in its bottom-right corner. The light's size, ink edge, canvas cut-out and overhang
 * follow the raft-ui step nearest [avatarSize] ([RaftAvatarStep]): 11dp on md, 9dp on sm,
 * 7dp with a 1dp edge and 1dp cut-out on xs/2xs, 14dp on lg, 20dp on xl. [statusSize]
 * overrides the light's size only. [cutoutColor] is the surface the avatar sits on, so the
 * light reads as cut out of it. Takes exactly [avatarSize]; the light overhangs it.
 */
@Composable
internal fun RaftAvatarWithStatus(
    statusColor: Color?,
    modifier: Modifier = Modifier,
    avatarSize: Dp,
    statusSize: Dp? = null,
    shape: RaftStatusIndicatorShape = RaftStatusIndicatorTokens.Shape,
    statusPulsing: Boolean = false,
    cutoutColor: Color = RaftColor.Canvas,
    avatar: @Composable BoxScope.() -> Unit
) {
    val step = RaftAvatarStep.nearest(avatarSize)
    val lightSize = statusSize ?: step.badgeSize
    val square = shape == RaftStatusIndicatorShape.Square
    Box(modifier = modifier.size(avatarSize)) {
        Box(modifier = Modifier.align(Alignment.Center), content = avatar)
        statusColor?.let {
            val cutout = step.badgeCutout
            val radius = step.badgeRadius
            RaftStatusIndicator(
                color = it,
                size = lightSize,
                shape = shape,
                borderWidth = if (square) step.badgeBorder else RaftStatusIndicatorTokens.BorderWidth,
                pulsing = statusPulsing,
                cornerRadius = radius,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = step.badgeOverhang, y = step.badgeOverhang)
                        // `outline-2 outline-layer-canvas` (1px on xs/2xs/3xs): the outline
                        // follows the light's corners, so its outer radius is radius + width.
                        .drawBehind {
                            val c = cutout.toPx()
                            if (square) {
                                drawRoundRect(
                                    color = cutoutColor,
                                    topLeft = Offset(-c, -c),
                                    size = Size(size.width + c * 2, size.height + c * 2),
                                    cornerRadius = CornerRadius(radius.toPx() + c)
                                )
                            } else {
                                drawCircle(color = cutoutColor, radius = size.minDimension / 2f + c)
                            }
                        }
                )
        }
    }
}
