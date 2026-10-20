package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import build.raft.app.compat.ui.raftColorFromArgbHex

internal expect fun isRaftNativeTiltedBadgeChromeSupported(): Boolean

/**
 * Tilted badge chrome (hard shadow, fill, border, rotated by [tiltDegrees]) drawn with Compose; it
 * was a native Kuikly view. [supersample], [drawFilter], [layerType] and [geometry] tuned the
 * native renderers and have no effect here.
 * TODO(cmp-switch): visual check against the native chrome.
 */
@Composable
internal fun RaftNativeTiltedBadgeChrome(
    modifier: Modifier = Modifier,
    width: Dp,
    height: Dp,
    fill: String,
    border: String,
    shadow: String,
    strokeWidth: Dp,
    shadowOffset: Dp,
    tiltDegrees: Float,
    supersample: Int,
    drawFilter: Boolean = false,
    layerType: String = "none",
    geometry: String = "path",
    viewRotation: Float = 0f
) {
    val fillColor = raftColorFromArgbHex(fill)
    val borderColor = raftColorFromArgbHex(border)
    val shadowColor = raftColorFromArgbHex(shadow)
    Canvas(modifier.width(width).height(height)) {
        val stroke = strokeWidth.toPx()
        val offset = shadowOffset.toPx()
        val body = Size(size.width - offset, size.height - offset)
        rotate(tiltDegrees + viewRotation) {
            drawRect(shadowColor, topLeft = Offset(offset, offset), size = body)
            drawRect(fillColor, size = body)
            drawRect(
                borderColor,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(body.width - stroke, body.height - stroke),
                style = Stroke(stroke),
            )
        }
    }
}
