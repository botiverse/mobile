package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import build.raft.app.thread.ThreadMessageMenuReactionGap
import build.raft.app.thread.ThreadMessageMenuViewportGap
import build.raft.app.thread.ThreadNewMessageMenuColumnHorizontalPadding
import build.raft.app.thread.ThreadNewMessageMenuReactionButtonSize
import build.raft.app.thread.ThreadNewMessageMenuReactionHorizontalPadding
import build.raft.app.thread.ThreadNewMessageMenuWidth
import build.raft.app.thread.ThreadQuickReactionEmojis
import build.raft.app.thread.threadMessageMenuWidthDp
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

    @Test
    fun quickReactionBarAndMessageMenusFitAllEightQuickReactions() {
        // 8 quick reactions without Add Reaction: 8*36 + 7*2 + 2*3 + 2*4 = 316dp.
        assertEquals(316f, threadMessageMenuWidthDp(showReactionActions = true, showAddReactionAction = false))
        // 8 quick reactions + Add Reaction: 9*36 + 8*2 + 2*3 + 2*4 = 354dp (fits in 390dp phone with 8dp gaps).
        val classicWithAdd = threadMessageMenuWidthDp(showReactionActions = true, showAddReactionAction = true)
        assertEquals(354f, classicWithAdd)
        assertEquals(true, classicWithAdd + ThreadMessageMenuViewportGap.value * 2 <= 390f)

        // Compact menu inner reaction row width fits all 9 buttons (8 quick reactions + Add Reaction) with >= 2dp gap.
        val compactSlotCount = ThreadQuickReactionEmojis.size + 1
        val compactInnerWidth =
            ThreadNewMessageMenuWidth.value -
                ThreadNewMessageMenuColumnHorizontalPadding.value * 2 -
                ThreadNewMessageMenuReactionHorizontalPadding.value * 2
        val compactButtonsWidth = compactSlotCount * ThreadNewMessageMenuReactionButtonSize.value
        val compactMinGapsWidth = (compactSlotCount - 1) * ThreadMessageMenuReactionGap.value
        assertEquals(true, compactButtonsWidth + compactMinGapsWidth <= compactInnerWidth)
    }
}
