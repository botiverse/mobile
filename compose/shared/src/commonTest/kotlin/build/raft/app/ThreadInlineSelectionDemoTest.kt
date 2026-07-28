package build.raft.app

import com.tencent.kuikly.compose.text.SelectionFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadInlineSelectionDemoTest {
    @Test
    fun eligibilityAcceptsOnlyPlainStandaloneMessageBodies() {
        assertTrue(
            threadInlineSelectionDemoEligible(
                body = "Long press this plain message.",
                hasAttachments = false,
                hasActionCard = false,
                hasForwardBundle = false,
                hasCommentReference = false,
                hasLocalSendState = false,
                translationPending = false,
                longMessageCollapseEnabled = false
            )
        )
        assertTrue(threadInlineSelectionDemoBodyEligible("纯文本消息可以原位全选。"))
        assertTrue(threadInlineSelectionDemoBodyEligible("プレーンテキストを選択します。"))
    }

    @Test
    fun eligibilityRejectsMarkdownAndNonBodySurfaces() {
        assertFalse(threadInlineSelectionDemoBodyEligible("Open [docs](https://example.com)"))
        assertFalse(threadInlineSelectionDemoBodyEligible("First line\nSecond line"))
        assertFalse(threadInlineSelectionDemoBodyEligible("1. Ordered item"))
        assertFalse(
            threadInlineSelectionDemoEligible(
                body = "Plain body",
                hasAttachments = true,
                hasActionCard = false,
                hasForwardBundle = false,
                hasCommentReference = false,
                hasLocalSendState = false,
                translationPending = false,
                longMessageCollapseEnabled = false
            )
        )
        assertFalse(
            threadInlineSelectionDemoEligible(
                body = "Plain body",
                hasAttachments = false,
                hasActionCard = false,
                hasForwardBundle = false,
                hasCommentReference = false,
                hasLocalSendState = false,
                translationPending = false,
                longMessageCollapseEnabled = true
            )
        )
    }

    @Test
    fun toolbarCentersAboveSelectionWhenSpaceAllows() {
        assertEquals(
            ThreadInlineSelectionToolbarPlacement(xPx = 20, yPx = 104, belowSelection = false),
            threadInlineSelectionToolbarPlacement(
                cardWidthPx = 300,
                selectionContainerXInCardPx = 20f,
                selectionContainerYInCardPx = 100f,
                selectionFrame = SelectionFrame(x = 10f, y = 40f, width = 100f, height = 20f),
                toolbarWidthPx = 120,
                toolbarHeightPx = 30,
                gapPx = 6
            )
        )
    }

    @Test
    fun toolbarFallsBelowAndClampsInsideCardAtTopEdge() {
        assertEquals(
            ThreadInlineSelectionToolbarPlacement(xPx = 0, yPx = 30, belowSelection = true),
            threadInlineSelectionToolbarPlacement(
                cardWidthPx = 180,
                selectionContainerXInCardPx = 10f,
                selectionContainerYInCardPx = 4f,
                selectionFrame = SelectionFrame(x = 0f, y = 0f, width = 20f, height = 20f),
                toolbarWidthPx = 140,
                toolbarHeightPx = 30,
                gapPx = 6
            )
        )
    }

    @Test
    fun toolbarClampsAtRightCardEdge() {
        assertEquals(
            100,
            threadInlineSelectionToolbarPlacement(
                cardWidthPx = 200,
                selectionContainerXInCardPx = 180f,
                selectionContainerYInCardPx = 100f,
                selectionFrame = SelectionFrame(x = 10f, y = 20f, width = 20f, height = 20f),
                toolbarWidthPx = 100,
                toolbarHeightPx = 30,
                gapPx = 6
            ).xPx
        )
    }
}
