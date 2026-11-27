package build.raft.app

import build.raft.app.thread.ThreadComposerLayoutBottomAnchorDecision
import build.raft.app.thread.ThreadComposerLayoutBottomAnchorState
import build.raft.app.thread.ThreadImeProgrammaticViewportPolicy
import build.raft.app.thread.focusedAnchorImeHandoffIdentity
import build.raft.app.thread.focusedAnchorImeHandoffTransfersToBottom
import build.raft.app.thread.focusedMessageAnchorOwnsViewport
import build.raft.app.thread.focusedTailMessageComposerLayoutOwnsViewport
import build.raft.app.thread.resolveThreadComposerLayoutBottomAnchor
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadFocusedAnchorImeHandoffTest {
    private val identity = "latest-message:request-1"

    @Test
    fun consumedOrUserOwnedRequestCannotHandoffAgain() {
        assertEquals(
            null,
            handoff(
                keyboardInsetDp = 320f,
                anchoredIdentity = identity,
                scrollInterruptedIdentity = identity,
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4
            )
        )
        assertEquals(
            null,
            handoff(
                keyboardInsetDp = 320f,
                anchoredIdentity = identity,
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 5
            )
        )
        assertEquals(
            null,
            handoff(
                keyboardInsetDp = 320f,
                anchoredIdentity = identity,
                consumedIdentity = identity,
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4
            )
        )
    }

    @Test
    fun handoffRetiresRouteOwnerAndKeepsComposerTailCorrection() {
        val consumedIdentity =
            handoff(
                keyboardInsetDp = 320f,
                anchoredIdentity = identity,
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4
            )

        assertEquals(identity, consumedIdentity)
        assertEquals(
            false,
            focusedMessageAnchorOwnsViewport(
                pendingIdentity = identity,
                consumedIdentity = consumedIdentity,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = 0L
            )
        )
        val bottomOwnerTransferred =
            focusedAnchorImeHandoffTransfersToBottom(
                handoffIdentity = consumedIdentity,
                targetItemIndex = 10,
                latestMessageItemIndex = 10,
                canLoadMoreAfter = false
            )
        assertEquals(true, bottomOwnerTransferred)
        val composerOwnsTail =
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = identity,
                targetItemIndex = 10,
                latestMessageItemIndex = 10,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = identity,
                consumedIdentity = consumedIdentity,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4
            )
        assertEquals(true, composerOwnsTail)
        assertEquals(
            ThreadComposerLayoutBottomAnchorDecision(
                shouldAnchor = true,
                pendingLayoutChange = false,
                anchorFocusedTailAtRenderedBottom = true
            ),
            resolveThreadComposerLayoutBottomAnchor(
                ThreadComposerLayoutBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 320f,
                    imeProgrammaticViewportPolicy =
                        ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen,
                    previousViewportHeightDp = 900f,
                    nextViewportHeightDp = 580f,
                    pendingLayoutChange = false,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 11,
                    allowBottomAutoAnchor = bottomOwnerTransferred,
                    viewportEpochCoherent = true,
                    focusedAnchorOwnsViewport = false,
                    focusedActiveTailAnchorCanCorrectLayout = false,
                    focusedTailAnchorCanCorrectLayout = composerOwnsTail
                )
            )
        )
    }

    private fun handoff(
        keyboardInsetDp: Float,
        anchoredIdentity: String?,
        consumedIdentity: String? = null,
        scrollInterruptedIdentity: String? = null,
        startUserScrollGeneration: Int?,
        currentUserScrollGeneration: Int
    ): String? =
        focusedAnchorImeHandoffIdentity(
            keyboardInsetDp = keyboardInsetDp,
            targetIdentity = identity,
            anchoredIdentity = anchoredIdentity,
            consumedIdentity = consumedIdentity,
            scrollInterruptedIdentity = scrollInterruptedIdentity,
            startUserScrollGeneration = startUserScrollGeneration,
            currentUserScrollGeneration = currentUserScrollGeneration
        )
}
