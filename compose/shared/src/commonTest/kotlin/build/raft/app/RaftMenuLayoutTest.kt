package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun raftStatusMenuTreatsFixedWidthAsMinimum() {
        fun items(vararg labels: String) = labels.map { RaftBaseMenuItem(id = it, label = it) }

        // Short labels keep the caller's 140dp, like web's min-w-[140px].
        assertEquals(140.dp, raftLookBaseMenuWidth(items("Todo", "Done"), fixedWidth = 140.dp, minWidth = 136.dp))
        // Bold "In Progress" plus row chrome and the trailing check does not fit 140dp.
        val inProgress = raftLookBaseMenuWidth(items("Todo", "In Progress", "Done"), fixedWidth = 140.dp, minWidth = 136.dp)
        assertTrue(inProgress > 140.dp, "status menu should grow past 140dp, was $inProgress")
        // Growth is capped at the menu max width.
        assertEquals(280.dp, raftLookBaseMenuWidth(items("x".repeat(80)), fixedWidth = 140.dp, minWidth = 136.dp))
        // Without a caller width the raft min-w-60 estimate applies.
        assertEquals(240.dp, raftLookBaseMenuWidth(items("Todo"), fixedWidth = null, minWidth = 136.dp))
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
