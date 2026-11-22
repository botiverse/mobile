package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMessageMenuLayoutTest {
    @Test
    fun contextMenuPlacementMatchesWebPlaceFloatingOverlay() {
        val viewport = RaftFloatingOverlayLayout(size = Size(width = 390f, height = 844f))
        fun place(anchor: Offset, content: Size) =
            viewport.positionContent(
                anchor = anchor,
                contentSize = content,
                viewportGap = 8f,
                horizontalOffset = 0f,
                verticalOffset = 0f,
                verticalOffsetAbove = 0f,
                verticalPlacement = RaftFloatingVerticalPlacement.ContextMenu,
                horizontalPlacement = RaftFloatingHorizontalPlacement.ContextMenu
            )
        // Fits right/below: the top-left corner sits on the press point.
        assertEquals(Offset(40f, 100f), place(Offset(40f, 100f), Size(248f, 375f)))
        // Neither side fits horizontally (the web capture's row-center press):
        // clamp the anchor 8 inside the viewport -> 390 - 8 - 248 = 134.
        assertEquals(Offset(134f, 86f), place(Offset(211f, 86f), Size(248f, 375f)))
        // Fits only to the left/above: end at the press point.
        assertEquals(Offset(32f, 325f), place(Offset(280f, 700f), Size(248f, 375f)))
    }
}
