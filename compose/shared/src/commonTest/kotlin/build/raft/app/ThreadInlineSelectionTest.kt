package build.raft.app

import com.tencent.kuikly.compose.text.SelectionFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadInlineSelectionTest {
    @Test
    fun compactToolRowKeepsSixActionsVisibleAndOverflowOutOfThePrimaryActions() {
        assertEquals(
            listOf(
                ThreadInlineSelectionTool.Copy,
                ThreadInlineSelectionTool.Reply,
                ThreadInlineSelectionTool.Forward,
                ThreadInlineSelectionTool.Save,
                ThreadInlineSelectionTool.Translate,
                ThreadInlineSelectionTool.Select
            ),
            ThreadInlineSelectionToolOrder
        )
    }

    @Test
    fun eligibilityAcceptsOnlyPlainStandaloneMessageBodies() {
        assertTrue(
            threadInlineSelectionEligible(
                body = "Long press this plain message.",
                hasAttachments = false,
                hasActionCard = false,
                hasForwardBundle = false,
                hasCommentReference = false,
                hasLocalSendState = false,
                translationPending = false,
                longMessageCollapsed = false
            )
        )
        assertTrue(threadInlineSelectionBodyEligible("纯文本消息可以原位全选。"))
        assertTrue(threadInlineSelectionBodyEligible("プレーンテキストを選択します。"))
    }

    @Test
    fun eligibilityRejectsMarkdownAndNonBodySurfaces() {
        assertFalse(threadInlineSelectionBodyEligible("Open [docs](https://example.com)"))
        assertFalse(threadInlineSelectionBodyEligible("First line\nSecond line"))
        assertFalse(threadInlineSelectionBodyEligible("1. Ordered item"))
        assertFalse(
            threadInlineSelectionEligible(
                body = "Plain body",
                hasAttachments = true,
                hasActionCard = false,
                hasForwardBundle = false,
                hasCommentReference = false,
                hasLocalSendState = false,
                translationPending = false,
                longMessageCollapsed = false
            )
        )
        assertFalse(
            threadInlineSelectionEligible(
                body = "Plain body",
                hasAttachments = false,
                hasActionCard = false,
                hasForwardBundle = false,
                hasCommentReference = false,
                hasLocalSendState = false,
                translationPending = false,
                longMessageCollapsed = true
            )
        )
    }

    @Test
    fun settingGateKeepsTheLegacyMenuUntilExplicitlyEnabled() {
        assertFalse(enabledForPlainMessage(settingEnabled = false))
        assertTrue(enabledForPlainMessage(settingEnabled = true))
    }

    @Test
    fun expandedLongMessageCanSelectWhileCollapsedPreviewKeepsTheLegacyMenu() {
        assertFalse(enabledForPlainMessage(settingEnabled = true, longMessageCollapsed = true))
        assertTrue(enabledForPlainMessage(settingEnabled = true, longMessageCollapsed = false))
    }

    @Test
    fun actionAvailabilityDisablesOnlyUnavailableProductionActions() {
        val availability =
            ThreadInlineSelectionActionAvailability(
                reactions = false,
                reply = false,
                save = false,
                translate = false
            )

        assertFalse(availability.reactions)
        assertFalse(availability.enabled(ThreadInlineSelectionTool.Reply))
        assertFalse(availability.enabled(ThreadInlineSelectionTool.Save))
        assertFalse(availability.enabled(ThreadInlineSelectionTool.Translate))
        assertTrue(availability.enabled(ThreadInlineSelectionTool.Copy))
        assertTrue(availability.enabled(ThreadInlineSelectionTool.Forward))
        assertTrue(availability.enabled(ThreadInlineSelectionTool.Select))
    }

    @Test
    fun toolDispatchRoutesEveryVisibleActionToItsMatchingProductionCallback() {
        val routed = mutableListOf<ThreadInlineSelectionTool>()
        val callbacks =
            ThreadInlineSelectionToolCallbacks(
                onCopy = { routed += ThreadInlineSelectionTool.Copy },
                onReply = { routed += ThreadInlineSelectionTool.Reply },
                onForward = { routed += ThreadInlineSelectionTool.Forward },
                onSave = { routed += ThreadInlineSelectionTool.Save },
                onTranslate = { routed += ThreadInlineSelectionTool.Translate },
                onSelect = { routed += ThreadInlineSelectionTool.Select }
            )

        ThreadInlineSelectionToolOrder.forEach { tool -> tool.dispatch(callbacks) }

        assertEquals(ThreadInlineSelectionToolOrder, routed)
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

    private fun enabledForPlainMessage(
        settingEnabled: Boolean,
        longMessageCollapsed: Boolean = false
    ): Boolean =
        threadInlineSelectionEnabledForMessage(
            settingEnabled = settingEnabled,
            body = "Plain production message.",
            hasAttachments = false,
            hasActionCard = false,
            hasForwardBundle = false,
            hasCommentReference = false,
            hasLocalSendState = false,
            translationPending = false,
            longMessageCollapsed = longMessageCollapsed
        )
}
