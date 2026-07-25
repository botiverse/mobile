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
                                placement = intent.placement
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

        val pinnedAfterAppend =
            reduceLazyViewport(
                pinned,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Append)
            )
        val anchoredAfterAppend =
            reduceLazyViewport(
                anchored,
                LazyViewportEvent.ContentMutated(LazyViewportMutation.Append)
            )

        assertEquals(pinned.pendingCommand, pinnedAfterAppend.pendingCommand)
        assertEquals(anchored.pendingCommand, anchoredAfterAppend.pendingCommand)
        assertTrue(pinnedAfterAppend.accepts(pinned.pendingCommand!!))
        assertTrue(anchoredAfterAppend.accepts(anchored.pendingCommand!!))
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
