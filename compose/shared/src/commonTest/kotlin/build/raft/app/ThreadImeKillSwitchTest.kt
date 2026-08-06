package build.raft.app

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

    private fun composerState(
        nextInsetDp: Float,
        previousInsetDp: Float = 0f,
        tailFits: Boolean = true,
    ) = ThreadComposerLayoutBottomAnchorState(
        previousKeyboardInsetDp = previousInsetDp,
        nextKeyboardInsetDp = nextInsetDp,
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
    fun aFullShrinkCycleIssuesZeroProgrammaticCommands() {
        var controller = LazyViewportControllerState()
        var commands = 0

        // The keyboard rises and stays up across several passes — the window
        // in which every white capture occurred.
        listOf(0f to 150f, 150f to 299.5f, 299.5f to 299.5f, 299.5f to 299.5f).forEach { (prev, next) ->
            // Composer path.
            val decision =
                resolveThreadComposerLayoutBottomAnchor(
                    composerState(nextInsetDp = next, previousInsetDp = prev)
                )
            assertFalse(
                decision.shouldAnchor,
                "the composer must not correct while the keyboard is open (inset $prev->$next)"
            )

            // Focused session path: even genuine drift must not reanchor.
            val session =
                focusedAnchorSessionAction(
                    userInteracting = false,
                    drifted = true,
                    quietMs = 0L,
                    elapsedMs = 200L,
                    keyboardOpen = threadImeOpenSuppressesProgrammaticViewport(next),
                )
            assertFalse(
                session == FocusedAnchorSessionAction.Reanchor,
                "the focused session must not reanchor while the keyboard is open"
            )

            // Origin correction path.
            assertFalse(
                shouldCorrectFocusedAnchorAfterOriginHydration(
                    originStatus = "loaded",
                    targetIdentity = target,
                    anchoredIdentity = target,
                    correctedIdentity = null,
                    scrollInterruptedIdentity = null,
                    startUserScrollGeneration = 3,
                    currentUserScrollGeneration = 3,
                    keyboardOpen = threadImeOpenSuppressesProgrammaticViewport(next),
                ),
                "the origin correction must not run while the keyboard is open"
            )

            if (decision.shouldAnchor) {
                controller =
                    reduceLazyViewport(
                        controller,
                        LazyViewportEvent.Anchor(
                            target = LazyViewportAnchorTarget.Message("message-17"),
                            offset = 0,
                            layoutGeneration = 1L,
                            placement = LazyViewportAnchorPlacement.RenderedBottom,
                            renderedTailMessageId = "message-17",
                            renderedTailAnchorIdentity = target,
                            fallback = LazyViewportAnchorFallback.Free,
                        )
                    )
                commands += 1
            }
        }

        assertEquals(0, commands, "an entire shrink cycle must issue zero programmatic commands")
        assertEquals(null, controller.pendingCommand)
    }

    @Test
    fun theRiseCancelsAnInflightAnchoredCommandWithoutIssuingAnother() {
        // An anchored command is mid-flight when the keyboard starts to rise:
        // the cancel must clear it and hand the viewport to the user, issuing
        // nothing new. This is the production cancel primitive.
        var controller = LazyViewportControllerState()
        controller =
            reduceLazyViewport(
                controller,
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message("message-17"),
                    offset = 0,
                    layoutGeneration = 1L,
                    placement = LazyViewportAnchorPlacement.Focused,
                    renderedTailMessageId = null,
                    renderedTailAnchorIdentity = null,
                    fallback = LazyViewportAnchorFallback.Free,
                )
            )
        val inflight = requireNotNull(controller.pendingCommand).id

        controller = reduceLazyViewport(controller, LazyViewportEvent.AnchorUnavailable(inflight))

        assertEquals(null, controller.pendingCommand, "the in-flight anchor must be cancelled")
        assertEquals(
            LazyViewportIntent.UserDragging,
            controller.intent,
            "and the viewport must belong to the user, not to another command"
        )
    }

    @Test
    fun userIntentsKeepWorkingWhileTheKeyboardIsOpen() {
        // The explicit back-to-bottom button is a user intent, not a
        // programmatic correction — it must still produce its command.
        var controller = LazyViewportControllerState()
        controller =
            reduceLazyViewport(
                controller,
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)
            )
        assertTrue(
            controller.pendingCommand != null,
            "explicit back-to-bottom must keep working while the keyboard is open"
        )

        // A user drag interrupt still outranks everything in the session.
        assertEquals(
            FocusedAnchorSessionAction.Interrupt,
            focusedAnchorSessionAction(
                userInteracting = true,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                keyboardOpen = true,
            ),
            "a user drag must still interrupt, even with the keyboard open"
        )
    }

    @Test
    fun everythingReturnsToNormalOnceTheKeyboardCloses() {
        // The close transition (inset back to 0) must behave exactly as before
        // the kill-switch: the composer's restore correction runs.
        val closed =
            resolveThreadComposerLayoutBottomAnchor(
                composerState(nextInsetDp = 0f, previousInsetDp = 299.5f)
            )
        assertTrue(
            closed.shouldAnchor,
            "the close-transition correction must keep its existing behaviour"
        )

        assertEquals(
            FocusedAnchorSessionAction.Reanchor,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                keyboardOpen = false,
            ),
            "drift handling returns once the keyboard is closed"
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
                keyboardOpen = false,
            ),
            "the origin correction returns once the keyboard is closed"
        )
    }
}
