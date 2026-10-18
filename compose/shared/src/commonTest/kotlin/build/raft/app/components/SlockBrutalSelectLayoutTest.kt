package build.raft.app.components

import build.raft.app.SlockFloatingOverlayLayout
import build.raft.app.SlockFloatingVerticalPlacement
import build.raft.app.positionContent
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockBrutalSelectLayoutTest {
    @Test
    fun measuredTriggerWidthConvertsPixelsToDpExactly() {
        assertEquals(360.dp, slockSelectMenuWidth(triggerWidthPx = 1080f, density = 3f))
        assertEquals(220.dp, slockSelectMenuWidth(triggerWidthPx = 0f, density = 3f))
    }

    @Test
    fun longOptionListsCapAtConfiguredMenuHeight() {
        assertEquals(
            220.dp,
            slockBrutalSelectListHeight(optionCount = 30, rowHeight = 36.dp, maxMenuHeight = 220.dp)
        )
        // 3 rows of 36dp + the 6dp popup inset top and bottom.
        assertEquals(
            120.dp,
            slockBrutalSelectListHeight(optionCount = 3, rowHeight = 36.dp, maxMenuHeight = 220.dp)
        )
    }

    @Test
    fun selectPopoverKeepsTriggerWidthAndFlipsAboveBottomTrigger() {
        val viewport = SlockFloatingOverlayLayout(size = Size(width = 390f, height = 844f))
        val contentSize = Size(width = 358f, height = 224f)

        assertEquals(
            Offset(16f, 513f),
            viewport.positionContent(
                anchor = Offset(16f, 740f),
                contentSize = contentSize,
                viewportGap = 8f,
                verticalOffset = 43f,
                verticalOffsetAbove = 3f,
                verticalPlacement = SlockFloatingVerticalPlacement.Auto
            )
        )
        assertEquals(358f, contentSize.width)
    }

    @Test
    fun selectPopoverStaysAlignedInWideViewport() {
        val viewport = SlockFloatingOverlayLayout(size = Size(width = 1280f, height = 900f))

        assertEquals(
            Offset(320f, 243f),
            viewport.positionContent(
                anchor = Offset(320f, 200f),
                contentSize = Size(width = 640f, height = 224f),
                viewportGap = 8f,
                verticalOffset = 43f,
                verticalOffsetAbove = 3f,
                verticalPlacement = SlockFloatingVerticalPlacement.Auto
            )
        )
    }
}
