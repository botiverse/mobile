package build.raft.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import build.raft.app.compat.ui.raftColorFromArgbHex
import kotlin.math.roundToInt

/*
 * Lucide icons rendered with Compose ImageVectors built from the generated path table
 * (RaftLucideIconPaths.kt, see scripts/generate_lucide_icons.py) via RaftLucideImageVectors.
 *
 * Semantics carried over from the old native Kuikly renderers:
 * - the icon is fitted (aspect preserved, centred) into the modifier's bounds; the stroke width is
 *   in 24x24 viewBox units so it scales with the icon size;
 * - `strokeWidth` (> 0) overrides the stroke width of every stroked path (the Android renderer only
 *   honoured it for `check`, the only icon callers pass a non-default width for);
 * - a non-blank `fill` switches to the filled variant when one exists (bookmark) and tints the whole
 *   icon with the fill colour;
 * - unknown names draw nothing but keep the layout slot.
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
                        animation =
                            tween(
                                durationMillis = (SlockIconSpinDurationSeconds * 1000f).roundToInt(),
                                easing = LinearEasing
                            )
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
    val iconName = remember(name) { SlockLucideIconName.fromWireName(RaftLucideImageVectors.normalizeName(name)) }
    val fillColor = fill?.takeIf { it.isNotBlank() }?.let(::raftColorFromArgbHex)
    val tint = fillColor ?: raftColorFromArgbHex(color)
    val layerModifier =
        modifier.graphicsLayer {
            rotationZ = appliedRotationDegrees
            translationY = verticalOffsetDp * density
        }
    if (iconName == null) {
        Spacer(layerModifier)
        return
    }
    val filled = fillColor != null
    val strokeOverride = strokeWidth?.takeIf { it > 0f }
    val tintable =
        remember(iconName, filled) { RaftLucideImageVectors.isTintable(RaftLucideImageVectors.spec(iconName, filled)) }
    val vector =
        if (tintable) {
            remember(iconName, filled, strokeOverride) {
                RaftLucideImageVectors.vector(iconName, filled, strokeOverride)
            }
        } else {
            remember(iconName, filled, strokeOverride, tint) {
                RaftLucideImageVectors.vector(iconName, filled, strokeOverride, currentColor = tint)
            }
        }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = layerModifier,
        contentScale = ContentScale.Fit,
        colorFilter = if (tintable) remember(tint) { ColorFilter.tint(tint) } else null
    )
}
