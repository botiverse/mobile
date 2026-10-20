package build.raft.app.components

import build.raft.app.RaftFloatingOverlayLayout
import build.raft.app.RaftFloatingVerticalPlacement
import build.raft.app.positionContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftBrutalSelectLayoutTest {
    @Test
    fun measuredTriggerWidthConvertsPixelsToDpExactly() {
        assertEquals(360.dp, raftSelectMenuWidth(triggerWidthPx = 1080f, density = 3f))
        assertEquals(220.dp, raftSelectMenuWidth(triggerWidthPx = 0f, density = 3f))
    }

    @Test
    fun longOptionListsCapAtConfiguredMenuHeight() {
        assertEquals(
            220.dp,
            raftBrutalSelectListHeight(optionCount = 30, rowHeight = 36.dp, maxMenuHeight = 220.dp)
        )
        // 3 rows of 36dp + the 6dp popup inset top and bottom.
        assertEquals(
            120.dp,
            raftBrutalSelectListHeight(optionCount = 3, rowHeight = 36.dp, maxMenuHeight = 220.dp)
        )
    }

    @Test
    fun raftSelectGroupLabelsTakeTheirOwnHeight() {
        // 6 (p-1.5) + 28 (group label px-2.5 pt-2 pb-1) + 2 x 36 (min-h-9) + 6.
        assertEquals(
            112.dp,
            raftSelectListHeight(
                options =
                    listOf(
                        RaftBrutalSelectOption("group", "Group", header = true),
                        RaftBrutalSelectOption("a", "A"),
                        RaftBrutalSelectOption("b", "B")
                    ),
                maxMenuHeight = 220.dp
            )
        )
    }

    @Test
    fun selectPopoverKeepsTriggerWidthAndFlipsAboveBottomTrigger() {
        val viewport = RaftFloatingOverlayLayout(size = Size(width = 390f, height = 844f))
        val contentSize = Size(width = 358f, height = 224f)

        assertEquals(
            Offset(16f, 513f),
            viewport.positionContent(
                anchor = Offset(16f, 740f),
                contentSize = contentSize,
                viewportGap = 8f,
                verticalOffset = 43f,
                verticalOffsetAbove = 3f,
                verticalPlacement = RaftFloatingVerticalPlacement.Auto
            )
        )
        assertEquals(358f, contentSize.width)
    }

    @Test
    fun selectPopoverStaysAlignedInWideViewport() {
        val viewport = RaftFloatingOverlayLayout(size = Size(width = 1280f, height = 900f))

        assertEquals(
            Offset(320f, 243f),
            viewport.positionContent(
                anchor = Offset(320f, 200f),
                contentSize = Size(width = 640f, height = 224f),
                viewportGap = 8f,
                verticalOffset = 43f,
                verticalOffsetAbove = 3f,
                verticalPlacement = RaftFloatingVerticalPlacement.Auto
            )
        )
    }
}
