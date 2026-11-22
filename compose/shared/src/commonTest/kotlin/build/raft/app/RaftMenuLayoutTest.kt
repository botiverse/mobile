package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMenuLayoutTest {
    @Test
    fun taskStatusMenuFlipsAboveALowScrolledTrigger() {
        val viewport = RaftFloatingOverlayLayout(size = Size(width = 390f, height = 844f))

        assertEquals(
            RaftFloatingVerticalPlacement.Auto,
            RaftTaskStatusMenuVerticalPlacement
        )
        assertEquals(
            Offset(238f, 584f),
            viewport.positionContent(
                anchor = Offset(382f, 776f),
                contentSize = Size(width = 144f, height = 188f),
                viewportGap = 8f,
                horizontalOffset = 0f,
                verticalOffset = 4f,
                verticalPlacement = RaftTaskStatusMenuVerticalPlacement,
                horizontalAutoFlip = true
            )
        )
    }

    @Test
    fun abovePreferredSitsAboveTheAnchorAndFallsBelowOnlyWithoutRoom() {
        // Room above: the bar ends 24 above the finger.
        assertEquals(
            400f - 60f - 24f,
            raftVerticalPosition(
                localAnchorY = 400f,
                contentHeight = 60f,
                viewportHeight = 800f,
                verticalOffset = 24f,
                verticalOffsetAbove = 24f,
                viewportGap = 8f,
                verticalPlacement = RaftFloatingVerticalPlacement.AbovePreferred
            )
        )
        // Near the top: below the finger instead.
        assertEquals(
            50f + 24f,
            raftVerticalPosition(
                localAnchorY = 50f,
                contentHeight = 60f,
                viewportHeight = 800f,
                verticalOffset = 24f,
                verticalOffsetAbove = 24f,
                viewportGap = 8f,
                verticalPlacement = RaftFloatingVerticalPlacement.AbovePreferred
            )
        )
    }
}
