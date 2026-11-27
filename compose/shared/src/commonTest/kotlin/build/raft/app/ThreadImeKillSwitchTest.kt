package build.raft.app

import build.raft.app.thread.FocusedAnchorSessionAction
import build.raft.app.thread.LazyViewportAnchorFallback
import build.raft.app.thread.LazyViewportAnchorTarget
import build.raft.app.thread.LazyViewportControllerState
import build.raft.app.thread.LazyViewportEvent
import build.raft.app.thread.ThreadComposerLayoutBottomAnchorState
import build.raft.app.thread.ThreadImeProgrammaticViewportPolicy
import build.raft.app.thread.cancelInflightAnchorOnImeRise
import build.raft.app.thread.focusedAnchorSessionAction
import build.raft.app.thread.reduceLazyViewport
import build.raft.app.thread.resolveThreadComposerLayoutBottomAnchor
import build.raft.app.thread.shouldCorrectFocusedAnchorAfterOriginHydration
import build.raft.app.thread.suppressAutoBottomHeightChange
import build.raft.app.thread.suppressFocusAnchorWhileOpen
import build.raft.app.thread.suppressInitialBottomRetry
import build.raft.app.thread.threadImeOpenSuppressesProgrammaticViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #990 emergency mitigation — while the keyboard is open, no programmatic
 * viewport command may be issued, and the paths a user drives must keep
 * working.
 *
 * The sequences below run the production decision functions in the order the
 * effects call them and land every would-be command on the real controller
 * reducer, so the count asserted is commands the list would actually receive.
 * Deleting any of the three kill-switch gates turns the corresponding case
 * red.
 */
class ThreadImeKillSwitchTest {

    private val target = "message-17:3"
    private val suppressWhileImeOpen =
        ThreadImeProgrammaticViewportPolicy.SuppressWhileImeOpen
    private val allowWhileImeOpen =
        ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen

    private fun composerState(
        nextInsetDp: Float,
        previousInsetDp: Float = 0f,
        tailFits: Boolean = true,
        policy: ThreadImeProgrammaticViewportPolicy = suppressWhileImeOpen,
    ) = ThreadComposerLayoutBottomAnchorState(
        previousKeyboardInsetDp = previousInsetDp,
        nextKeyboardInsetDp = nextInsetDp,
        imeProgrammaticViewportPolicy = policy,
        previousViewportHeightDp = 700f,
        nextViewportHeightDp = if (nextInsetDp > 0f) 364f else 700f,
        pendingLayoutChange = false,
        initialBottomAnchorReady = true,
        latestItemIndex = 18,
        allowBottomAutoAnchor = true,
        viewportEpochCoherent = true,
        focusedAnchorOwnsViewport = false,
        focusedActiveTailAnchorCanCorrectLayout = false,
        focusedTailAnchorCanCorrectLayout = tailFits,
    )

    @Test
    fun allowPolicyRestoresEveryAutomaticCorrectionOwnerWhileImeIsOpen() {
        val logs = mutableListOf<String>()
        val keyboardSuppressed =
            threadImeOpenSuppressesProgrammaticViewport(299.5f, allowWhileImeOpen)
        assertFalse(keyboardSuppressed)

        assertEquals(
            FocusedAnchorSessionAction.Reanchor,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                keyboardOpen = keyboardSuppressed,
            ),
            "iOS restores focused-session drift correction",
        )
        assertTrue(
            shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = "loaded",
                targetIdentity = target,
                anchoredIdentity = target,
                correctedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 3,
                keyboardOpen = keyboardSuppressed,
            ),
            "iOS restores the origin-hydration correction",
        )

        val composerDecision =
            resolveThreadComposerLayoutBottomAnchor(
                composerState(
                    nextInsetDp = 299.5f,
                    previousInsetDp = 0f,
                    policy = allowWhileImeOpen,
                )
            )
        assertTrue(composerDecision.shouldAnchor, "iOS restores the composer layout correction")

        assertFalse(
            suppressInitialBottomRetry(
                keyboardInsetDp = 299.5f,
                policy = allowWhileImeOpen,
                log = { logs += it },
            )
        )
        assertFalse(
            suppressFocusAnchorWhileOpen(
                keyboardInsetDp = 299.5f,
                targetMessageId = "message-17",
                policy = allowWhileImeOpen,
                log = { logs += it },
            )
        )
        assertFalse(
            suppressAutoBottomHeightChange(
                keyboardInsetDp = 299.5f,
                policy = allowWhileImeOpen,
                log = { logs += it },
            )
        )

        var controller =
            reduceLazyViewport(
                LazyViewportControllerState(),
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("message-17"),
                    offset = 0,
                    layoutGeneration = 1L,
                    fallback = LazyViewportAnchorFallback.Free,
                )
            )
        val pendingBefore = requireNotNull(controller.pendingCommand).id
        val riseHandled =
            cancelInflightAnchorOnImeRise(
                keyboardInsetDp = 299.5f,
                lastKeyboardInsetDp = 0f,
                controllerState = controller,
                policy = allowWhileImeOpen,
                dispatch = { controller = reduceLazyViewport(controller, it) },
                log = { logs += it },
            )

        assertFalse(riseHandled, "iOS does not arm the Android/OHOS stop-loss")
        assertEquals(pendingBefore, controller.pendingCommand?.id)
        assertTrue(logs.isEmpty(), "an enabled correction path must not emit suppression receipts")
    }
}
