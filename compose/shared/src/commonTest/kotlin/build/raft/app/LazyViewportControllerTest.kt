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

    @Test
    fun lockAnchorOffsetSettlesFocusedToExactOffsetWithoutCommandAndNeverReplaysFocused() {
        val target = LazyViewportAnchorTarget.Message("focused-near-tail")
        val focused =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = target,
                    offset = 0,
                    layoutGeneration = 5L,
                    placement = LazyViewportAnchorPlacement.Focused,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val consumed =
            reduceLazyViewport(
                focused,
                LazyViewportEvent.CommandConsumed(focused.pendingCommand!!.id)
            )

        // Lock to the bottom-clamped settled visual offset (e.g. 184px from top).
        val locked =
            reduceLazyViewport(
                consumed,
                LazyViewportEvent.LockAnchorOffset(
                    target = target,
                    offset = 184,
                    layoutGeneration = 6L
                )
            )
        val lockedIntent = assertIs<LazyViewportIntent.Anchored>(locked.intent)
        assertEquals(target, lockedIntent.target)
        assertEquals(184, lockedIntent.offset)
        assertEquals(6L, lockedIntent.layoutGeneration)
        assertEquals(LazyViewportAnchorPlacement.ExactOffset, lockedIntent.placement)
        assertEquals(LazyViewportAnchorFallback.Free, lockedIntent.fallback)
        assertNull(locked.pendingCommand, "LockAnchorOffset must never emit a scroll command")

        // Subsequent Prepend / HeightChange / InsetChange replay ExactOffset(184), never Focused(0).
        listOf(
            LazyViewportMutation.Prepend,
            LazyViewportMutation.HeightChange,
            LazyViewportMutation.InsetChange,
        ).forEach { mutation ->
            val afterMutation =
                reduceLazyViewport(
                    locked,
                    LazyViewportEvent.ContentMutated(mutation)
                )
            val commandTarget = assertIs<LazyViewportCommandTarget.Anchor>(afterMutation.pendingCommand?.target)
            assertEquals(target, commandTarget.target, mutation.toString())
            assertEquals(184, commandTarget.offset, mutation.toString())
            assertEquals(6L, commandTarget.layoutGeneration, mutation.toString())
            assertEquals(LazyViewportAnchorPlacement.ExactOffset, commandTarget.placement, mutation.toString())
        }

        // Append leaves the locked ExactOffset anchor untouched without emitting a command.
        val afterAppend =
            reduceLazyViewport(
                locked,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Append)
            )
        assertEquals(locked, afterAppend)
        assertNull(afterAppend.pendingCommand)
    }

    @Test
    fun lockAnchorOffsetIgnoresOtherIntentsOrTargetsAndCancelsStalePendingCommand() {
        val target = LazyViewportAnchorTarget.Message("m1")
        val otherTarget = LazyViewportAnchorTarget.Message("m2")

        // Ignored on FollowingLatest, PinnedTop, UserDragging.
        val following = LazyViewportControllerState()
        assertEquals(following, reduceLazyViewport(following, LazyViewportEvent.LockAnchorOffset(target, 42, 1L)))
        val pinned = reduceLazyViewport(following, LazyViewportEvent.PinTop)
        assertEquals(pinned, reduceLazyViewport(pinned, LazyViewportEvent.LockAnchorOffset(target, 42, 1L)))
        val dragging = reduceLazyViewport(following, LazyViewportEvent.PointerDown)
        assertEquals(dragging, reduceLazyViewport(dragging, LazyViewportEvent.LockAnchorOffset(target, 42, 1L)))

        // Ignored when anchored on a different target.
        val anchored =
            reduceLazyViewport(
                following,
                LazyViewportEvent.Anchor(
                    target = target,
                    offset = 0,
                    layoutGeneration = 2L,
                    placement = LazyViewportAnchorPlacement.Focused,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val staleCommand = anchored.pendingCommand!!
        assertEquals(anchored, reduceLazyViewport(anchored, LazyViewportEvent.LockAnchorOffset(otherTarget, 99, 3L)))

        // Locking the same target cancels the pending Focused command without emitting a new one.
        val locked = reduceLazyViewport(anchored, LazyViewportEvent.LockAnchorOffset(target, 99, 3L))
        assertNull(locked.pendingCommand)
        assertFalse(locked.accepts(staleCommand))
        assertEquals(LazyViewportAnchorPlacement.ExactOffset, assertIs<LazyViewportIntent.Anchored>(locked.intent).placement)

        // Idempotent when already locked at the same offset and layout generation with no pending command.
        assertEquals(locked, reduceLazyViewport(locked, LazyViewportEvent.LockAnchorOffset(target, 99, 3L)))
    }

    @Test
    fun focusedAnchorKeepAndReanchorHelpersPreserveSettledExactOffset() {
        val id = "m10:1"
        // Restart after already anchored with target still visible -> keep ExactOffset.
        assertTrue(
            shouldKeepVisibleFocusedAnchorOffset(
                placement = LazyViewportAnchorPlacement.Focused,
                currentVisibleOffset = 140,
                targetIdentity = id,
                sessionIdentity = id,
                initialPlacementApplied = false,
                initialFocusedIdentity = null,
            )
        )
        // Initial pre-first-draw placement applied for this exact focus target -> keep ExactOffset.
        assertTrue(
            shouldKeepVisibleFocusedAnchorOffset(
                placement = LazyViewportAnchorPlacement.Focused,
                currentVisibleOffset = 0,
                targetIdentity = id,
                sessionIdentity = null,
                initialPlacementApplied = true,
                initialFocusedIdentity = id,
            )
        )
        // Target fell off-screen -> must anchor normally.
        assertFalse(
            shouldKeepVisibleFocusedAnchorOffset(
                placement = LazyViewportAnchorPlacement.Focused,
                currentVisibleOffset = null,
                targetIdentity = id,
                sessionIdentity = id,
                initialPlacementApplied = true,
                initialFocusedIdentity = id,
            )
        )
        // New request nonce / different message -> must anchor normally.
        assertFalse(
            shouldKeepVisibleFocusedAnchorOffset(
                placement = LazyViewportAnchorPlacement.Focused,
                currentVisibleOffset = 140,
                targetIdentity = "m10:2",
                sessionIdentity = id,
                initialPlacementApplied = true,
                initialFocusedIdentity = id,
            )
        )
        // FocusedThreadReplies / RenderedBottom keep their existing placement path.
        assertFalse(
            shouldKeepVisibleFocusedAnchorOffset(
                placement = LazyViewportAnchorPlacement.FocusedThreadReplies,
                currentVisibleOffset = 140,
                targetIdentity = id,
                sessionIdentity = id,
                initialPlacementApplied = true,
                initialFocusedIdentity = id,
            )
        )
        assertFalse(
            shouldKeepVisibleFocusedAnchorOffset(
                placement = LazyViewportAnchorPlacement.RenderedBottom,
                currentVisibleOffset = 140,
                targetIdentity = id,
                sessionIdentity = id,
                initialPlacementApplied = true,
                initialFocusedIdentity = id,
            )
        )

        // Reanchor restores anchoredOffset under ExactOffset for Focused, and preserves other placements.
        assertEquals(
            ThreadFocusedReanchorTarget(LazyViewportAnchorPlacement.ExactOffset, 210),
            resolveThreadFocusedReanchorTarget(
                anchoredOffset = 210,
                resolvedPlacement = LazyViewportAnchorPlacement.Focused,
                fallbackOffsetPx = 0,
            ),
        )
        assertEquals(
            ThreadFocusedReanchorTarget(LazyViewportAnchorPlacement.Focused, 0),
            resolveThreadFocusedReanchorTarget(
                anchoredOffset = null,
                resolvedPlacement = LazyViewportAnchorPlacement.Focused,
                fallbackOffsetPx = 0,
            ),
        )
        assertEquals(
            ThreadFocusedReanchorTarget(LazyViewportAnchorPlacement.FocusedThreadReplies, 0),
            resolveThreadFocusedReanchorTarget(
                anchoredOffset = 210,
                resolvedPlacement = LazyViewportAnchorPlacement.FocusedThreadReplies,
                fallbackOffsetPx = 0,
            ),
        )
        assertEquals(
            ThreadFocusedReanchorTarget(LazyViewportAnchorPlacement.RenderedBottom, 120),
            resolveThreadFocusedReanchorTarget(
                anchoredOffset = 210,
                resolvedPlacement = LazyViewportAnchorPlacement.RenderedBottom,
                fallbackOffsetPx = 120,
            ),
        )

        assertEquals(ThreadViewportOwnerTag.FocusedReanchor, threadFocusedDispatchOwnerTag(LazyViewportAnchorPlacement.Focused))
        assertEquals(ThreadViewportOwnerTag.JumpPill, threadFocusedDispatchOwnerTag(LazyViewportAnchorPlacement.FocusedThreadReplies))
        assertEquals(ThreadViewportOwnerTag.RenderedTail, threadFocusedDispatchOwnerTag(LazyViewportAnchorPlacement.RenderedBottom))
    }
}
