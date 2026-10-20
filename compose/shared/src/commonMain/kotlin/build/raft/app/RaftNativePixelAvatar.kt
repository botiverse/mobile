package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * The face of a pixel avatar: background, then each horizontal colour span of the 8x8 grid,
 * filling the avatar's inside (the frame, ink edge and corners are [RaftAppAvatar]'s).
 *
 * Pixel art scales nearest-neighbour: every cell edge is snapped to a whole device pixel
 * ([pixelAvatarGridEdge]), so neighbouring cells meet without an anti-aliased seam and a
 * cell is never smeared across two pixels. The face box itself sits on whole pixels (layout
 * rounds it), so the snapped edges stay crisp on screen.
 */
@Composable
internal fun RaftNativePixelAvatar(
    spec: PixelAvatarSpec,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(spec.background)
        for (span in spec.spans) {
            val left = pixelAvatarGridEdge(span.startColumnIndex, size.width)
            val right = pixelAvatarGridEdge(span.startColumnIndex + span.columnCount, size.width)
            val top = pixelAvatarGridEdge(span.rowIndex, size.height)
            val bottom = pixelAvatarGridEdge(span.rowIndex + 1, size.height)
            drawRect(
                color = span.color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top)
            )
        }
    }
}
