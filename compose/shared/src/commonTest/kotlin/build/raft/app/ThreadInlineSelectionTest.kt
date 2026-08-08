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
    fun eligibilityAcceptsPlainAndRichStandaloneMessageBodies() {
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
        assertTrue(threadInlineSelectionBodyEligible("Open [docs](https://example.com)"))
        assertTrue(threadInlineSelectionBodyEligible("First line\nSecond line"))
        assertTrue(threadInlineSelectionBodyEligible("1. Ordered item"))
        assertTrue(eligibleForBody("Open [docs](https://example.com)"))
        assertTrue(eligibleForBody("First line\nSecond line"))
        assertTrue(eligibleForBody("1. Ordered item"))
    }

    @Test
    fun richBodiesKeepTheirRendererWhilePlainBodiesUseTheSelectionFastPath() {
        assertTrue(threadInlineSelectionUsesPlainTextRenderer("Plain body"))
        assertFalse(threadInlineSelectionUsesPlainTextRenderer("Open [docs](https://example.com)"))
        assertFalse(threadInlineSelectionUsesPlainTextRenderer("First line\nSecond line"))
        assertFalse(threadInlineSelectionUsesPlainTextRenderer("1. Ordered item"))
        assertFalse(threadInlineSelectionUsesPlainTextRenderer(" padded body "))
    }

    @Test
    fun eligibilityRejectsBlankAndNonBodySurfaces() {
        assertFalse(threadInlineSelectionBodyEligible("   \n"))
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
                rootBounds = ThreadInlineSelectionRootBounds(widthPx = 300, heightPx = 400),
                selectionContainerXInRootPx = 20f,
                selectionContainerYInRootPx = 100f,
                selectionFrame = SelectionFrame(x = 10f, y = 40f, width = 100f, height = 20f),
                toolbarWidthPx = 120,
                toolbarHeightPx = 30,
                gapPx = 6
            )
        )
    }

    @Test
    fun toolbarFallsBelowAndClampsInsideThreadRootAtTopEdge() {
        assertEquals(
            ThreadInlineSelectionToolbarPlacement(xPx = 0, yPx = 30, belowSelection = true),
            threadInlineSelectionToolbarPlacement(
                rootBounds = ThreadInlineSelectionRootBounds(widthPx = 180, heightPx = 300),
                selectionContainerXInRootPx = 10f,
                selectionContainerYInRootPx = 4f,
                selectionFrame = SelectionFrame(x = 0f, y = 0f, width = 20f, height = 20f),
                toolbarWidthPx = 140,
                toolbarHeightPx = 30,
                gapPx = 6
            )
        )
    }

    @Test
    fun toolbarClampsAtRightThreadRootEdge() {
        assertEquals(
            100,
            threadInlineSelectionToolbarPlacement(
                rootBounds = ThreadInlineSelectionRootBounds(widthPx = 200, heightPx = 300),
                selectionContainerXInRootPx = 180f,
                selectionContainerYInRootPx = 100f,
                selectionFrame = SelectionFrame(x = 10f, y = 20f, width = 20f, height = 20f),
                toolbarWidthPx = 100,
                toolbarHeightPx = 30,
                gapPx = 6
            ).xPx
        )
    }

    @Test
    fun dragLifecycleHidesToolbarAndFreezesPlacementUntilSelectionEnds() {
        val firstSettledFrame = SelectionFrame(x = 12f, y = 40f, width = 80f, height = 20f)
        val finalSettledFrame = SelectionFrame(x = 24f, y = 64f, width = 120f, height = 24f)
        val settled =
            threadInlineSelectionLifecycleAfterEnd(
                threadInlineSelectionLifecycleAfterStart(ThreadInlineSelectionLifecycle()),
                firstSettledFrame
            )

        assertTrue(threadInlineSelectionToolbarVisible(settled))

        val restarted = threadInlineSelectionLifecycleAfterStart(settled)
        val moving = threadInlineSelectionLifecycleAfterChange(restarted)
        assertEquals(ThreadInlineSelectionPhase.Selecting, moving.phase)
        assertEquals(firstSettledFrame, moving.settledFrame)
        assertFalse(threadInlineSelectionToolbarVisible(moving))

        val ended = threadInlineSelectionLifecycleAfterEnd(moving, finalSettledFrame)
        assertEquals(ThreadInlineSelectionPhase.Settled, ended.phase)
        assertEquals(finalSettledFrame, ended.settledFrame)
        assertTrue(threadInlineSelectionToolbarVisible(ended))
        assertFalse(
            threadInlineSelectionToolbarVisible(threadInlineSelectionLifecycleAfterCancel())
        )
    }

    @Test
    fun toolbarPlacementUsesThreadRootInsteadOfClampingInsideSelectedMessageRow() {
        val placement =
            threadInlineSelectionToolbarPlacement(
                rootBounds =
                    ThreadInlineSelectionRootBounds(
                        xInRootPx = 0f,
                        yInRootPx = 80f,
                        widthPx = 390,
                        heightPx = 600
                    ),
                selectionContainerXInRootPx = 32f,
                selectionContainerYInRootPx = 360f,
                selectionFrame = SelectionFrame(x = 0f, y = 10f, width = 180f, height = 22f),
                toolbarWidthPx = 240,
                toolbarHeightPx = 96,
                gapPx = 6
            )

        // The selected row starts near y=340 in root coordinates. A row-owned
        // overlay would have clamped to y=0 within that row; the thread-root
        // overlay may occupy the inter-row area above it at root y=188.
        assertEquals(
            ThreadInlineSelectionToolbarPlacement(xPx = 2, yPx = 188, belowSelection = false),
            placement
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

    private fun eligibleForBody(body: String): Boolean =
        threadInlineSelectionEligible(
            body = body,
            hasAttachments = false,
            hasActionCard = false,
            hasForwardBundle = false,
            hasCommentReference = false,
            hasLocalSendState = false,
            translationPending = false,
            longMessageCollapsed = false
        )
}
