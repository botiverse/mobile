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
    fun pinnedTopAppendDoesNotMoveOrExitAbsoluteTop() {
        val pinned = reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PinTop)
        val consumed =
            reduceLazyViewport(
                pinned,
                LazyViewportEvent.CommandConsumed(pinned.pendingCommand!!.id)
            )
        val afterAppend =
            reduceLazyViewport(
                consumed,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Append)
            )

        assertEquals(LazyViewportIntent.PinnedTop, afterAppend.intent)
        assertNull(afterAppend.pendingCommand)
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
    fun focusedTailRenderedBottomReplaysInsetWithoutChangingAnchoredIntent() {
        val target = LazyViewportAnchorTarget.Message("latest-message")
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = target,
                    offset = 16,
                    layoutGeneration = 21L,
                    placement = LazyViewportAnchorPlacement.RenderedBottom,
                    renderedTailMessageId = "latest-message",
                    renderedTailAnchorIdentity = "latest-message:request-1",
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val firstCommand = anchored.pendingCommand!!
        val consumed =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.CommandConsumed(firstCommand.id)
            )
        val afterInset =
            reduceLazyViewport(
                consumed,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.InsetChange)
            )

        val intent = assertIs<LazyViewportIntent.Anchored>(afterInset.intent)
        assertEquals(target, intent.target)
        assertEquals(LazyViewportAnchorPlacement.RenderedBottom, intent.placement)
        assertEquals("latest-message", intent.renderedTailMessageId)
        assertEquals("latest-message:request-1", intent.renderedTailAnchorIdentity)
        assertEquals(
            LazyViewportCommandTarget.Anchor(
                target = target,
                offset = 16,
                layoutGeneration = 21L,
                placement = LazyViewportAnchorPlacement.RenderedBottom,
                renderedTailMessageId = "latest-message",
                renderedTailAnchorIdentity = "latest-message:request-1"
            ),
            afterInset.pendingCommand?.target
        )
        assertTrue(afterInset.accepts(afterInset.pendingCommand!!))

        val settled =
            reduceLazyViewport(
                afterInset,
                LazyViewportEvent.CommandConsumed(afterInset.pendingCommand!!.id)
            )
        assertNull(settled.pendingCommand)
        assertEquals(LazyViewportAnchorPlacement.RenderedBottom, assertIs<LazyViewportIntent.Anchored>(settled.intent).placement)

        val driftCorrection =
            reduceLazyViewport(
                settled,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange)
            )
        assertEquals(
            LazyViewportAnchorPlacement.RenderedBottom,
            assertIs<LazyViewportCommandTarget.Anchor>(driftCorrection.pendingCommand?.target).placement
        )
        assertTrue(driftCorrection.accepts(driftCorrection.pendingCommand!!))
    }

    @Test
    fun renderedTailCommand_requiresLiveLayoutGenerationAndTailIdentity() {
        val anchored =
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
        val command = anchored.pendingCommand!!

        assertTrue(
            anchored.acceptsRenderedTailCommand(
                command = command,
                currentLayoutGeneration = 21L,
                currentTailMessageId = "tail-message-3",
                currentAnchorIdentity = "focused-message:request-7"
            )
        )
        assertFalse(
            anchored.acceptsRenderedTailCommand(
                command = command,
                currentLayoutGeneration = 22L,
                currentTailMessageId = "tail-message-3",
                currentAnchorIdentity = "focused-message:request-7"
            )
        )
        assertFalse(
            anchored.acceptsRenderedTailCommand(
                command = command,
                currentLayoutGeneration = 21L,
                currentTailMessageId = "tail-message-4",
                currentAnchorIdentity = "focused-message:request-7"
            )
        )
        assertFalse(
            anchored.acceptsRenderedTailCommand(
                command = command,
                currentLayoutGeneration = 21L,
                currentTailMessageId = "tail-message-3",
                currentAnchorIdentity = "focused-message:request-8"
            )
        )

        val afterAppend =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Append)
            )
        val afterAppendIntent = assertIs<LazyViewportIntent.Anchored>(afterAppend.intent)
        assertNull(afterAppend.pendingCommand)
        assertEquals(LazyViewportAnchorPlacement.Focused, afterAppendIntent.placement)
        assertEquals(0, afterAppendIntent.offset)
        assertNull(afterAppendIntent.renderedTailMessageId)
        assertNull(afterAppendIntent.renderedTailAnchorIdentity)
        assertFalse(afterAppend.accepts(command))

        val dragging = reduceLazyViewport(anchored, LazyViewportEvent.PointerDown)
        assertFalse(
            dragging.acceptsRenderedTailCommand(
                command = command,
                currentLayoutGeneration = 21L,
                currentTailMessageId = "tail-message-3",
                currentAnchorIdentity = "focused-message:request-7"
            )
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
    fun adaptiveAnchorRestore_insetChangeReassertsAnchorInsteadOfReplayingLatest() {
        // A4 restore regression (task #18): the destination pane used to scrollToItem directly
        // while the controller intent stayed FollowingLatest, so the first composer_inset_relayout
        // ContentMutated(InsetChange) replayed a Latest command and yanked the restored viewport
        // back to bottom. Restore now transfers intent to Anchored(ExactOffset) via the
        // controller, so the same mutation must re-assert the restored anchor.
        val restored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("A4FX-025"),
                    offset = -0,
                    layoutGeneration = 1L,
                    placement = LazyViewportAnchorPlacement.ExactOffset,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val consumed =
            reduceLazyViewport(
                restored,
                LazyViewportEvent.CommandConsumed(restored.pendingCommand!!.id)
            )
        val afterInset =
            reduceLazyViewport(
                consumed,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.InsetChange)
            )

        val intent = assertIs<LazyViewportIntent.Anchored>(afterInset.intent)
        assertEquals(LazyViewportAnchorTarget.Message("A4FX-025"), intent.target)
        assertEquals(
            LazyViewportCommandTarget.Anchor(
                target = LazyViewportAnchorTarget.Message("A4FX-025"),
                offset = -0,
                layoutGeneration = 1L,
                placement = LazyViewportAnchorPlacement.ExactOffset
            ),
            afterInset.pendingCommand?.target
        )
        assertTrue(afterInset.accepts(afterInset.pendingCommand!!))
        assertNotEquals(LazyViewportCommandTarget.Latest, afterInset.pendingCommand?.target)
    }

    @Test
    fun anchorCommand_exactOffsetNegatesOffsetForScrollToItem() {
        // Coordinate contract (task #18): LazyViewportEvent.Anchor.offset carries the captured
        // LazyList item.offset (viewport-relative, negative when the row is scrolled above the
        // top edge). The executor runs scrollToItem(index, -target.offset), so a captured -37
        // must become scrollOffset=+37 — the row sits 37px above the viewport top. Dispatching
        // the negated value would invert it twice and land the row below the top edge, surfacing
        // the previous item as firstVisible (Jony r18).
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("A4FX-019"),
                    offset = -37,
                    layoutGeneration = 3L,
                    placement = LazyViewportAnchorPlacement.ExactOffset,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val command = assertIs<LazyViewportCommandTarget.Anchor>(anchored.pendingCommand?.target)
        assertEquals(-37, command.offset)
        assertEquals(LazyViewportAnchorPlacement.ExactOffset, command.placement)
        // scrollToItem receives -command.offset = +37 — documented here as the pairing contract.
        assertEquals(37, -command.offset)
    }

    @Test
    fun renderedTailInvalidation_ignoresNonMatchingCommandId() {
        val anchored =
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

        assertEquals(
            anchored,
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.RenderedTailCommandInvalidated(
                    commandId = anchored.pendingCommand!!.id.copy(sequence = 999L),
                    currentLayoutGeneration = 22L
                )
            )
        )
    }

    @Test
    fun ordinaryAnchoredCommand_keepsExistingAcceptanceWithoutRenderedTailFence() {
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("history-message"),
                    offset = -24,
                    layoutGeneration = 7L,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )

        assertTrue(
            anchored.acceptsRenderedTailCommand(
                command = anchored.pendingCommand!!,
                currentLayoutGeneration = 99L,
                currentTailMessageId = "different-tail",
                currentAnchorIdentity = "different-anchor"
            )
        )
    }

    @Test
    fun userPointerInvalidatesFocusedTailRenderedBottomCommand() {
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("latest-message"),
                    offset = 16,
                    layoutGeneration = 22L,
                    placement = LazyViewportAnchorPlacement.RenderedBottom,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        val staleCommand = anchored.pendingCommand!!
        val dragging = reduceLazyViewport(anchored, LazyViewportEvent.PointerDown)

        assertEquals(LazyViewportIntent.UserDragging, dragging.intent)
        assertNull(dragging.pendingCommand)
        assertFalse(dragging.accepts(staleCommand))
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
    fun staleSuspendCompletionCannotExecuteAfterIntentEpochChanges() {
        val following =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)
            )
        val staleCommand = following.pendingCommand!!
        val pinned = reduceLazyViewport(following, LazyViewportEvent.PinTop)

        assertFalse(pinned.accepts(staleCommand))
        assertTrue(pinned.accepts(pinned.pendingCommand!!))

        val consumedStale =
            reduceLazyViewport(pinned, LazyViewportEvent.CommandConsumed(staleCommand.id))
        assertEquals(pinned.pendingCommand, consumedStale.pendingCommand)
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
    fun stalePointerSettleCannotReplaceANewerDragEpoch() {
        val firstDrag = reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PointerDown)
        val secondDrag = reduceLazyViewport(firstDrag, LazyViewportEvent.PointerDown)
        val staleSettle =
            reduceLazyViewport(
                secondDrag,
                LazyViewportEvent.PointerSettled(
                    epoch = firstDrag.epoch,
                    atRenderedBottom = true,
                    anchorMessageId = null,
                    anchorOffset = 0,
                    layoutGeneration = 2L
                )
            )

        assertEquals(secondDrag, staleSettle)
    }

    @Test
    fun contentMutationPolicyMatrixIsExplicitForEveryIntent() {
        val anchoredIntent =
            LazyViewportIntent.Anchored(
                target = LazyViewportAnchorTarget.Message("message-2"),
                offset = -13,
                layoutGeneration = 7L,
                placement = LazyViewportAnchorPlacement.ExactOffset,
                fallback = LazyViewportAnchorFallback.Free
            )
        val intents =
            listOf(
                LazyViewportIntent.FollowingLatest,
                LazyViewportIntent.PinnedTop,
                anchoredIntent,
                LazyViewportIntent.UserDragging
            )
        val mutations = LazyViewportMutation.entries

        intents.forEach { intent ->
            mutations.forEach { mutation ->
                val result =
                    reduceLazyViewport(
                        LazyViewportControllerState(intent = intent, epoch = 4L),
                        LazyViewportEvent.ContentMutated(mutation)
                    )
                val expectedTarget =
                    when (intent) {
                        LazyViewportIntent.FollowingLatest -> LazyViewportCommandTarget.Latest
                        LazyViewportIntent.PinnedTop ->
                            if (mutation == LazyViewportMutation.Append) null
                            else LazyViewportCommandTarget.Top(animated = false)
                        is LazyViewportIntent.Anchored ->
                            if (mutation == LazyViewportMutation.Append) null
                            else LazyViewportCommandTarget.Anchor(
                                target = intent.target,
                                offset = intent.offset,
                                layoutGeneration = intent.layoutGeneration,
                                placement = intent.placement,
                                renderedTailMessageId = intent.renderedTailMessageId
                            )
                        LazyViewportIntent.UserDragging -> null
                    }

                assertEquals(intent, result.intent, "$intent x $mutation changed intent")
                assertEquals(expectedTarget, result.pendingCommand?.target, "$intent x $mutation")
                assertEquals(4L, result.epoch, "$intent x $mutation changed epoch")
            }
        }
    }

    @Test
    fun appendPreservesAnInFlightPinnedOrAnchoredEntryCommand() {
        val pinned = reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PinTop)
        val anchored =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("message-9"),
                    offset = -7,
                    layoutGeneration = 3L,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )

        listOf(LazyViewportMutation.Append).forEach { mutation ->
            val pinnedAfterAppend =
                reduceLazyViewport(
                    pinned,
                    LazyViewportEvent.ContentMutated(mutation)
                )
            val anchoredAfterAppend =
                reduceLazyViewport(
                    anchored,
                    LazyViewportEvent.ContentMutated(mutation)
                )

            assertEquals(pinned.pendingCommand, pinnedAfterAppend.pendingCommand, mutation.toString())
            assertEquals(anchored.pendingCommand, anchoredAfterAppend.pendingCommand, mutation.toString())
            assertTrue(pinnedAfterAppend.accepts(pinned.pendingCommand!!), mutation.toString())
            assertTrue(anchoredAfterAppend.accepts(anchored.pendingCommand!!), mutation.toString())
        }

        val pinnedAfterSettlement =
            reduceLazyViewport(
                pinned,
                LazyViewportEvent.NewerPageSettled(
                    expectedViewportEpoch = pinned.epoch,
                    expectedViewportIntent = pinned.intent,
                    requestAnchor = LazyViewportAnchorTarget.Message("request-anchor"),
                    requestAnchorOffset = 12,
                    layoutGeneration = 4L
                )
            )
        val anchoredAfterSettlement =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.NewerPageSettled(
                    expectedViewportEpoch = anchored.epoch,
                    expectedViewportIntent = anchored.intent,
                    requestAnchor = LazyViewportAnchorTarget.Message("request-anchor"),
                    requestAnchorOffset = 12,
                    layoutGeneration = 4L
                )
            )
        assertEquals(pinned.pendingCommand, pinnedAfterSettlement.pendingCommand)
        assertEquals(anchored.pendingCommand, anchoredAfterSettlement.pendingCommand)
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
    fun newerPageSettlementAnchorsAnIncompleteTailThatLookedLikeFollowingLatest() {
        val following = LazyViewportControllerState(intent = LazyViewportIntent.FollowingLatest)
        val settled =
            reduceLazyViewport(
                following,
                LazyViewportEvent.NewerPageSettled(
                    expectedViewportEpoch = following.epoch,
                    expectedViewportIntent = following.intent,
                    requestAnchor = LazyViewportAnchorTarget.Message("message-11"),
                    requestAnchorOffset = -23,
                    layoutGeneration = 12L
                )
            )

        assertEquals(
            LazyViewportIntent.Anchored(
                target = LazyViewportAnchorTarget.Message("message-11"),
                offset = -23,
                layoutGeneration = 12L,
                placement = LazyViewportAnchorPlacement.ExactOffset,
                fallback = LazyViewportAnchorFallback.Free
            ),
            settled.intent
        )
        assertTrue(settled.accepts(settled.pendingCommand!!))

        val genuineFollowingLatest =
            reduceLazyViewport(
                following,
                LazyViewportEvent.NewerPageSettled(
                    expectedViewportEpoch = following.epoch,
                    expectedViewportIntent = following.intent,
                    requestAnchor = null,
                    requestAnchorOffset = 0,
                    layoutGeneration = 12L
                )
            )
        assertEquals(LazyViewportIntent.FollowingLatest, genuineFollowingLatest.intent)
        assertEquals(LazyViewportCommandTarget.Latest, genuineFollowingLatest.pendingCommand?.target)
    }

    @Test
    fun newerPageSettlementTicketRejectsALaterGestureThatAlreadySettledFollowingLatest() {
        val originalOwner = LazyViewportControllerState(intent = LazyViewportIntent.FollowingLatest)
        val staleSettlement =
            LazyViewportEvent.NewerPageSettled(
                expectedViewportEpoch = originalOwner.epoch,
                expectedViewportIntent = originalOwner.intent,
                requestAnchor = LazyViewportAnchorTarget.Message("message-11"),
                requestAnchorOffset = -23,
                layoutGeneration = 12L
            )
        val dragging = reduceLazyViewport(originalOwner, LazyViewportEvent.PointerDown)
        val laterFollowing =
            reduceLazyViewport(
                dragging,
                LazyViewportEvent.PointerSettled(
                    epoch = dragging.epoch,
                    atRenderedBottom = true,
                    anchorMessageId = "message-19",
                    anchorOffset = -5,
                    layoutGeneration = 13L
                )
            )

        val rejected = reduceLazyViewport(laterFollowing, staleSettlement)

        assertEquals(laterFollowing, rejected)
        assertEquals(LazyViewportIntent.FollowingLatest, rejected.intent)
        assertNull(rejected.pendingCommand)
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
