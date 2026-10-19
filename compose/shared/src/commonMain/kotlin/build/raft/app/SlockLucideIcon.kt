package build.raft.app

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import build.raft.app.compat.ui.raftColorFromArgbHex
import kotlin.math.roundToInt

/*
 * TODO(cmp-switch): TEMPORARY stub. The Lucide icon used to be a native Kuikly view
 * (MakeKuiklyComposeNode + per-platform renderers); the ImageVector port is being done separately.
 * Until it lands, every icon renders as a rounded outline in its colour so layout and tint stay
 * visible. The public API (SlockLucideIcon / SlockLucideIconByName) is unchanged.
 */

private const val SlockIconSpinDurationSeconds = 0.9f

@Composable
@NonRestartableComposable
internal fun SlockLucideIcon(
    name: SlockLucideIconName,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null,
    verticalOffsetDp: Float = 0f,
    spinning: Boolean = false,
    rotationDegrees: Float = 0f,
    rotationAnimationDurationSeconds: Float = 0f
) {
    SlockLucideIconByName(
        name = name.wireName,
        modifier = modifier,
        color = color,
        fill = fill,
        strokeWidth = strokeWidth,
        verticalOffsetDp = verticalOffsetDp,
        spinning = spinning,
        rotationDegrees = rotationDegrees,
        rotationAnimationDurationSeconds = rotationAnimationDurationSeconds
    )
}

@Composable
@NonRestartableComposable
internal fun SlockLucideIcon(
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null,
    verticalOffsetDp: Float = 0f,
    spinning: Boolean = false,
    rotationDegrees: Float = 0f,
    rotationAnimationDurationSeconds: Float = 0f
) {
    SlockLucideIconByName(
        name = name,
        modifier = modifier,
        color = color,
        fill = fill,
        strokeWidth = strokeWidth,
        verticalOffsetDp = verticalOffsetDp,
        spinning = spinning,
        rotationDegrees = rotationDegrees,
        rotationAnimationDurationSeconds = rotationAnimationDurationSeconds
    )
}

@Composable
internal fun SlockLucideIconByName(
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null,
    verticalOffsetDp: Float = 0f,
    spinning: Boolean = false,
    rotationDegrees: Float = 0f,
    rotationAnimationDurationSeconds: Float = 0f
) {
    val appliedRotationDegrees =
        if (spinning) {
            val transition = rememberInfiniteTransition(label = "slock-icon-spin")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = (SlockIconSpinDurationSeconds * 1000f).roundToInt())
                    ),
                label = "slock-icon-spin-rotation"
            )
            rotation
        } else if (rotationAnimationDurationSeconds > 0f) {
            val rotation by animateFloatAsState(
                targetValue = rotationDegrees,
                animationSpec = tween(durationMillis = (rotationAnimationDurationSeconds * 1000f).roundToInt()),
                label = "slock-icon-rotation"
            )
            rotation
        } else {
            rotationDegrees
        }
    val tint = raftColorFromArgbHex(color)
    val fillColor = fill?.takeIf { it.isNotBlank() }?.let(::raftColorFromArgbHex)
    Canvas(
        modifier.graphicsLayer {
            rotationZ = appliedRotationDegrees
            translationY = verticalOffsetDp * density
        }
    ) {
        // Placeholder glyph: inset rounded square (filled when the icon has a fill colour).
        val inset = size.minDimension * 0.18f
        val stroke = (strokeWidth?.takeIf { it > 0f } ?: 2f) * size.minDimension / 24f
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        val glyph = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val radius = CornerRadius(size.minDimension * 0.15f)
        if (fillColor != null) drawRoundRect(fillColor, topLeft, glyph, radius)
        drawRoundRect(tint, topLeft, glyph, radius, style = Stroke(width = stroke))
    }
}
