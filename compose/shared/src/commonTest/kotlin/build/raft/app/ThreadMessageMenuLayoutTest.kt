package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMessageMenuLayoutTest {
    @Test
    fun contentHeightIsExactBeforeFirstPlacement() {
        assertEquals(
            400f,
            threadMessageMenuContentHeightDp(
                actionGroups = listOf("copy", "copy", "copy", "message", "message", "task", "task", "task"),
                showReactionActions = true
            )
        )
    }

    @Test
    fun contentHeightTracksRowsGroupDividersAndOptionalReactionHeader() {
        assertEquals(
            141f,
            threadMessageMenuContentHeightDp(
                actionGroups = listOf("copy", "copy", "message"),
                showReactionActions = false
            )
        )
        assertEquals(
            179f,
            threadMessageMenuContentHeightDp(
                actionGroups = listOf("copy", "copy", "message"),
                showReactionActions = true
            )
        )
    }

    @Test
    fun exactHeightChoosesFinalSideBeforeMenuIsDrawn() {
        val viewport = SlockFloatingOverlayLayout(size = Size(width = 390f, height = 844f))
        val anchor = Offset(x = 300f, y = 500f)

        assertEquals(
            508f,
            viewport.positionContent(
                anchor = anchor,
                contentSize = Size(width = 244f, height = 250f),
                viewportGap = 8f,
                verticalOffset = 8f,
                verticalOffsetAbove = 8f,
                verticalPlacement = SlockFloatingVerticalPlacement.Auto,
                horizontalAutoFlip = true
            )?.y
        )
        assertEquals(
            92f,
            viewport.positionContent(
                anchor = anchor,
                contentSize = Size(width = 244f, height = 400f),
                viewportGap = 8f,
                verticalOffset = 8f,
                verticalOffsetAbove = 8f,
                verticalPlacement = SlockFloatingVerticalPlacement.Auto,
                horizontalAutoFlip = true
            )?.y
        )
    }
}
