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

    @Test
    fun threadMenuOwnerIdentityIsMessageScoped() {
        assertEquals("thread_message:message-1", threadMessageMenuOwnerIdentity("message-1"))
        assertEquals("thread_message:message-2", threadMessageMenuOwnerIdentity("message-2"))
    }

    @Test
    fun horizontalPositionCentersWithinViewport() {
        // Viewport 390 wide, content 288 wide, gap 8 -> max X is 390 - 288 - 8 = 94. Center is (390 - 288)/2 = 51
        val centeredX = slockHorizontalPosition(
            localAnchorX = 350f,
            contentWidth = 288f,
            viewportWidth = 390f,
            viewportGap = 8f,
            horizontalPlacement = SlockFloatingHorizontalPlacement.Center
        )
        assertEquals(51f, centeredX)

        // Anchor mode places relative to localAnchorX
        val anchorX = slockHorizontalPosition(
            localAnchorX = 50f,
            contentWidth = 288f,
            viewportWidth = 390f,
            viewportGap = 8f,
            horizontalPlacement = SlockFloatingHorizontalPlacement.Anchor
        )
        assertEquals(50f, anchorX)
    }
}
