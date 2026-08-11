package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadFocusedAnchorImeHandoffTest {
    private val identity = "latest-message:request-1"

    @Test
    fun anchoredRequestHandsViewportToComposerWhenImeOpens() {
        assertEquals(
            identity,
            handoff(
                keyboardInsetDp = 320f,
                anchoredIdentity = identity,
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4
            )
        )
    }

    @Test
    fun closedImeOrUnanchoredRequestStaysPending() {
        assertEquals(
            null,
            handoff(
                keyboardInsetDp = 0f,
                anchoredIdentity = identity,
                startUserScrollGeneration = 4,
                currentUserScrollGeneration = 4
            )
        )
        assertEquals(
            null,
            handoff(
                keyboardInsetDp = 320f,
                anchoredIdentity = null,
                startUserScrollGeneration = null,
                currentUserScrollGeneration = 4
            )
        )
    }

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

    @Test
    fun onlyCanonicalLatestTargetTransfersHandoffToBottom() {
        assertEquals(
            true,
            focusedAnchorImeHandoffTransfersToBottom(
                handoffIdentity = identity,
                targetItemIndex = 10,
                latestMessageItemIndex = 10,
                canLoadMoreAfter = false
            )
        )
        assertEquals(
            false,
            focusedAnchorImeHandoffTransfersToBottom(
                handoffIdentity = identity,
                targetItemIndex = 8,
                latestMessageItemIndex = 10,
                canLoadMoreAfter = false
            )
        )
        assertEquals(
            false,
            focusedAnchorImeHandoffTransfersToBottom(
                handoffIdentity = identity,
                targetItemIndex = 10,
                latestMessageItemIndex = 10,
                canLoadMoreAfter = true
            )
        )
        assertEquals(
            false,
            focusedAnchorImeHandoffTransfersToBottom(
                handoffIdentity = null,
                targetItemIndex = 10,
                latestMessageItemIndex = 10,
                canLoadMoreAfter = false
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
