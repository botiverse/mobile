package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp

/**
 * Pixel avatar drawn with Compose (it was a native Kuikly view on each platform): background,
 * then each horizontal colour span of the 8x8 grid.
 */
@Composable
internal fun RaftNativePixelAvatar(
    spec: PixelAvatarSpec,
    modifier: Modifier = Modifier,
    avatarSize: Dp,
    borderWidth: Dp = RaftAppStroke.Thin
) {
    Canvas(
        modifier
            .size(width = avatarSize, height = avatarSize)
            .border(borderWidth, RaftColor.BorderBlack)
    ) {
        drawRect(spec.background)
        val rows = spec.grid.size.coerceAtLeast(1)
        val columns = spec.grid.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val cellWidth = size.width / columns
        val cellHeight = size.height / rows
        for (span in spec.spans) {
            drawRect(
                color = span.color,
                topLeft = Offset(span.startColumnIndex * cellWidth, span.rowIndex * cellHeight),
                size = Size(span.columnCount * cellWidth, cellHeight),
            )
        }
    }
}
