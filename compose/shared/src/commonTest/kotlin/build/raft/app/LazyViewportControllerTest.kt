package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LazyViewportControllerTest {
    @Test
    fun pinnedTopPrependKeepsAbsoluteTopAndNeverRestoresOldAnchor() {
        val pinned = reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PinTop)
        assertEquals(LazyViewportCommandTarget.Top(animated = true), pinned.pendingCommand?.target)
        val afterPrepend =
            reduceLazyViewport(
                pinned,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Prepend)
            )

        assertEquals(LazyViewportIntent.PinnedTop, afterPrepend.intent)
        assertEquals(LazyViewportCommandTarget.Top(animated = false), afterPrepend.pendingCommand?.target)
        assertTrue(afterPrepend.accepts(afterPrepend.pendingCommand!!))
    }

    @Test
    fun anchoredPrependAndHeightChangeCoalesceAtSameMessageOffset() {
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("message-7"),
                    offset = -24,
                    layoutGeneration = 11L,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val afterPrepend =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Prepend)
            )
        val prependCommand = afterPrepend.pendingCommand!!
        val afterHeightChange =
            reduceLazyViewport(
                afterPrepend,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange)
            )

        assertEquals(anchored.epoch, afterHeightChange.epoch)
        assertNotEquals(prependCommand.id, afterHeightChange.pendingCommand?.id)
        assertEquals(
            LazyViewportCommandTarget.Anchor(
                LazyViewportAnchorTarget.Message("message-7"),
                -24,
                11L
            ),
            afterHeightChange.pendingCommand?.target
        )
        assertFalse(afterHeightChange.accepts(prependCommand))
        assertTrue(afterHeightChange.accepts(afterHeightChange.pendingCommand!!))
        assertEquals(
            LazyViewportAnchorSnapshot(
                epoch = anchored.epoch,
                target = LazyViewportAnchorTarget.Message("message-7"),
                offset = -24,
                layoutGeneration = 11L
            ),
            afterHeightChange.anchorSnapshotOrNull()
        )
    }

    @Test
    fun staleRenderedTailCommand_isTerminalAndCannotReplayOldTrailingSpan() {
        fun anchored(): LazyViewportControllerState =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("focused-message"),
                    offset = 224,
                    layoutGeneration = 21L,
                    placement = LazyViewportAnchorPlacement.RenderedBottom,
                    renderedTailMessageId = "tail-message-3",
                    renderedTailAnchorIdentity = "focused-message:request-7",
                    fallback = LazyViewportAnchorFallback.Free
                )
            )

        fun invalidate(
            state: LazyViewportControllerState,
            command: LazyViewportCommand,
            currentLayoutGeneration: Long
        ): LazyViewportControllerState {
            val invalidated =
                reduceLazyViewport(
                    state,
                    LazyViewportEvent.RenderedTailCommandInvalidated(
                        commandId = command.id,
                        currentLayoutGeneration = currentLayoutGeneration
                    )
                )
            val intent = assertIs<LazyViewportIntent.Anchored>(invalidated.intent)
            assertNull(invalidated.pendingCommand)
            assertEquals(LazyViewportAnchorTarget.Message("focused-message"), intent.target)
            assertEquals(0, intent.offset)
            assertEquals(currentLayoutGeneration, intent.layoutGeneration)
            assertEquals(LazyViewportAnchorPlacement.Focused, intent.placement)
            assertNull(intent.renderedTailMessageId)
            assertNull(intent.renderedTailAnchorIdentity)
            assertFalse(invalidated.accepts(command))
            return invalidated
        }

        val appendOwner = anchored()
        val appendCommand = appendOwner.pendingCommand!!
        val afterAppend =
            reduceLazyViewport(
                appendOwner,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Append)
            )
        val appendIntent = assertIs<LazyViewportIntent.Anchored>(afterAppend.intent)
        assertNull(afterAppend.pendingCommand)
        assertEquals(LazyViewportAnchorPlacement.Focused, appendIntent.placement)
        assertEquals(0, appendIntent.offset)
        assertNull(appendIntent.renderedTailMessageId)
        assertNull(appendIntent.renderedTailAnchorIdentity)
        assertFalse(afterAppend.accepts(appendCommand))

        val layoutOwner = anchored()
        invalidate(layoutOwner, layoutOwner.pendingCommand!!, currentLayoutGeneration = 22L)

        val tailOwner = anchored()
        invalidate(tailOwner, tailOwner.pendingCommand!!, currentLayoutGeneration = 21L)

        val nonceOwner = anchored()
        invalidate(nonceOwner, nonceOwner.pendingCommand!!, currentLayoutGeneration = 21L)

        listOf(
            LazyViewportMutation.InsetChange,
            LazyViewportMutation.HeightChange
        ).forEach { mutation ->
            val replay = reduceLazyViewport(afterAppend, LazyViewportEvent.ContentMutated(mutation))
            val target = assertIs<LazyViewportCommandTarget.Anchor>(replay.pendingCommand?.target)
            assertEquals(0, target.offset, mutation.toString())
            assertEquals(21L, target.layoutGeneration, mutation.toString())
            assertEquals(LazyViewportAnchorPlacement.Focused, target.placement, mutation.toString())
            assertNull(target.renderedTailMessageId, mutation.toString())
            assertNull(target.renderedTailAnchorIdentity, mutation.toString())
        }
    }

    @Test
    fun draggingRejectsAppendAndInsetCommandsUntilPointerSettles() {
        val dragging = reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PointerDown)
        val moved = reduceLazyViewport(dragging, LazyViewportEvent.PointerMove)
        val afterAppend =
            reduceLazyViewport(
                moved,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Append)
            )
        val afterInset =
            reduceLazyViewport(
                afterAppend,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.InsetChange)
            )

        assertEquals(LazyViewportIntent.UserDragging, afterInset.intent)
        assertEquals(dragging.epoch, moved.epoch)
        assertNull(afterInset.pendingCommand)

        val settled =
            reduceLazyViewport(
                afterInset,
                LazyViewportEvent.PointerSettled(
                    epoch = afterInset.epoch,
                    atRenderedBottom = false,
                    anchorMessageId = "message-4",
                    anchorOffset = 18,
                    layoutGeneration = 9L
                )
            )
        val anchor = assertIs<LazyViewportIntent.Anchored>(settled.intent)
        assertEquals(LazyViewportAnchorTarget.Message("message-4"), anchor.target)
        assertNull(settled.pendingCommand)
    }

    @Test
    fun missingAnchorUsesExplicitFallbackAndNeverSilentlyFollowsLatest() {
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("missing"),
                    offset = 0,
                    layoutGeneration = 3L,
                    fallback = LazyViewportAnchorFallback.PinnedTop
                )
            )
        val fallback =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.AnchorUnavailable(anchored.pendingCommand!!.id)
            )

        assertEquals(LazyViewportIntent.PinnedTop, fallback.intent)
        assertEquals(LazyViewportCommandTarget.Top(animated = false), fallback.pendingCommand?.target)
    }

    @Test
    fun pointerSettleAtRenderedBottomReturnsToFollowingLatestWithoutScrollCommand() {
        val dragging = reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PointerDown)
        val settled =
            reduceLazyViewport(
                dragging,
                LazyViewportEvent.PointerSettled(
                    epoch = dragging.epoch,
                    atRenderedBottom = true,
                    anchorMessageId = "ignored",
                    anchorOffset = 4,
                    layoutGeneration = 5L
                )
            )

        assertEquals(LazyViewportIntent.FollowingLatest, settled.intent)
        assertNull(settled.pendingCommand)
    }

    @Test
    fun newerPageSettlementRestoresStableAnchorButNeverMovesActiveDrag() {
        val enteredAnchor =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("message-11"),
                    offset = 29,
                    layoutGeneration = 8L,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val anchored =
            reduceLazyViewport(
                enteredAnchor,
                LazyViewportEvent.CommandConsumed(enteredAnchor.pendingCommand!!.id)
            )
        val restored =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.NewerPageSettled(
                    expectedViewportEpoch = anchored.epoch,
                    expectedViewportIntent = anchored.intent,
                    requestAnchor = LazyViewportAnchorTarget.Message("request-anchor"),
                    requestAnchorOffset = 51,
                    layoutGeneration = 9L
                )
            )

        assertEquals(anchored.intent, restored.intent)
        assertEquals(
            LazyViewportCommandTarget.Anchor(
                target = LazyViewportAnchorTarget.Message("message-11"),
                offset = 29,
                layoutGeneration = 8L
            ),
            restored.pendingCommand?.target
        )
        assertTrue(restored.accepts(restored.pendingCommand!!))

        val staleSettlementCommand = restored.pendingCommand!!
        val dragging = reduceLazyViewport(restored, LazyViewportEvent.PointerDown)
        assertFalse(dragging.accepts(staleSettlementCommand))
        val afterSettlement =
            reduceLazyViewport(
                dragging,
                LazyViewportEvent.NewerPageSettled(
                    expectedViewportEpoch = dragging.epoch,
                    expectedViewportIntent = dragging.intent,
                    requestAnchor = LazyViewportAnchorTarget.Message("message-11"),
                    requestAnchorOffset = 29,
                    layoutGeneration = 8L
                )
            )
        assertEquals(LazyViewportIntent.UserDragging, afterSettlement.intent)
        assertNull(afterSettlement.pendingCommand)
    }

    @Test
    fun localSendFollowHandsOffStableAnchorButPreservesActiveDragAndLaterPointerCancels() {
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("history"),
                    offset = -20,
                    layoutGeneration = 2L,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val following =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)
            )
        val latestCommand = following.pendingCommand!!
        val draggingOwner =
            reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PointerDown)
        val followingFromDrag =
            reduceLazyViewport(
                draggingOwner,
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)
            )
        val explicitFollowingFromDrag =
            reduceLazyViewport(
                draggingOwner,
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)
            )

        assertEquals(LazyViewportIntent.FollowingLatest, following.intent)
        assertEquals(LazyViewportCommandTarget.Latest, latestCommand.target)
        assertEquals(LazyViewportIntent.UserDragging, followingFromDrag.intent)
        assertNull(followingFromDrag.pendingCommand)
        assertEquals(LazyViewportIntent.FollowingLatest, explicitFollowingFromDrag.intent)
        assertEquals(LazyViewportCommandTarget.Latest, explicitFollowingFromDrag.pendingCommand?.target)

        val dragging = reduceLazyViewport(following, LazyViewportEvent.PointerDown)
        assertEquals(LazyViewportIntent.UserDragging, dragging.intent)
        assertNull(dragging.pendingCommand)
        assertFalse(dragging.accepts(latestCommand))
    }
}
