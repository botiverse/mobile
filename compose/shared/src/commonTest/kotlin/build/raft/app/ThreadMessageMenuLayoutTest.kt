package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMessageMenuLayoutTest {
    @Test
    fun contentHeightIsExactBeforeFirstPlacement() {
        // web MessageItem context menu: 8 rows x 36 + 2 group separators (my-1.5 + 1dp
        // hairline) x 13 + reaction header (h-9 + 13) + 2 x 6 (p-1.5) inset.
        assertEquals(
            375f,
            threadMessageMenuContentHeightDp(
                actionGroups = listOf("copy", "copy", "copy", "message", "message", "task", "task", "task"),
                showReactionActions = true
            )
        )
    }

    @Test
    fun contentHeightTracksRowsGroupDividersAndOptionalReactionHeader() {
        // 3 rows x 36 + 1 group separator x 13 + 2 x 6 inset.
        assertEquals(
            133f,
            threadMessageMenuContentHeightDp(
                actionGroups = listOf("copy", "copy", "message"),
                showReactionActions = false
            )
        )
        assertEquals(
            182f,
            threadMessageMenuContentHeightDp(
                actionGroups = listOf("copy", "copy", "message"),
                showReactionActions = true
            )
        )
    }

    @Test
    fun exactHeightChoosesFinalSideBeforeMenuIsDrawn() {
        val viewport = RaftFloatingOverlayLayout(size = Size(width = 390f, height = 844f))
        val anchor = Offset(x = 300f, y = 500f)

        assertEquals(
            508f,
            viewport.positionContent(
                anchor = anchor,
                contentSize = Size(width = 244f, height = 250f),
                viewportGap = 8f,
                verticalOffset = 8f,
                verticalOffsetAbove = 8f,
                verticalPlacement = RaftFloatingVerticalPlacement.Auto,
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
                verticalPlacement = RaftFloatingVerticalPlacement.Auto,
                horizontalAutoFlip = true
            )?.y
        )
    }

    @Test
    fun menuWidthIsMinW60OrTheQuickReactionRow() {
        // raft-ui MessageReactionQuickRow: 7 x 36 (size-9) + 6 x 2 (gap-0.5) + 2 x 3 (px-0.75)
        // + 2 x 6 (p-1.5) = 282.
        assertEquals(282f, threadMessageMenuWidthDp(showReactionActions = true))
        assertEquals(240f, threadMessageMenuWidthDp(showReactionActions = false))
    }

    @Test
    fun theSentAtLabelAddsItsRowToTheHeight() {
        // RaftMenuLabel: 8 + 16 + 4.
        assertEquals(
            133f + 28f,
            threadMessageMenuContentHeightDp(
                actionGroups = listOf("copy", "copy", "message"),
                showReactionActions = false,
                showSentAtLabel = true
            )
        )
    }

    @Test
    fun theSentAtLabelIsTheFullLocalDateAndTime() {
        val utc8 = build.raft.app.model.RaftTimeContext(localOffsetMinutes = 8 * 60)
        assertEquals("2026-10-26 10:20", threadMessageMenuSentAtLabel("2026-10-26T02:20:00Z", utc8))
        assertEquals(
            "2026-10-26 10:20 AM",
            threadMessageMenuSentAtLabel(
                "2026-10-26T02:20:00Z",
                utc8.copy(timeFormat = build.raft.app.model.RaftTimeFormat12Hour)
            )
        )
        // A display clock (fixtures) or no time authority yet: no label.
        assertEquals(null, threadMessageMenuSentAtLabel("10:38", utc8))
        assertEquals(null, threadMessageMenuSentAtLabel("2026-10-26T02:20:00Z", null))
    }

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

    @Test
    fun threadMenuOwnerIdentityIsMessageScoped() {
        assertEquals("thread_message:message-1", threadMessageMenuOwnerIdentity("message-1"))
        assertEquals("thread_message:message-2", threadMessageMenuOwnerIdentity("message-2"))
    }

    @Test
    fun horizontalPositionCentersWithinViewport() {
        // Viewport 390 wide, content 288 wide, gap 8 -> max X is 390 - 288 - 8 = 94. Center is (390 - 288)/2 = 51
        val centeredX = raftHorizontalPosition(
            localAnchorX = 350f,
            contentWidth = 288f,
            viewportWidth = 390f,
            viewportGap = 8f,
            horizontalPlacement = RaftFloatingHorizontalPlacement.Center
        )
        assertEquals(51f, centeredX)

        // Anchor mode places relative to localAnchorX
        val anchorX = raftHorizontalPosition(
            localAnchorX = 50f,
            contentWidth = 288f,
            viewportWidth = 390f,
            viewportGap = 8f,
            horizontalPlacement = RaftFloatingHorizontalPlacement.Anchor
        )
        assertEquals(50f, anchorX)
    }
}
