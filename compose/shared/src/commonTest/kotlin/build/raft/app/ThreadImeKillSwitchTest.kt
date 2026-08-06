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
    fun theRiseCancelsAnInflightAnchoredCommandThroughTheProductionSeam() {
        // An anchored command is mid-flight when the keyboard starts to rise.
        // The cancel is driven through the production seam the effect calls —
        // deleting the production cancel logic turns this red, where a test
        // that dispatched AnchorUnavailable itself would stay green.
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
        assertTrue(controller.pendingCommand != null)
        val logs = mutableListOf<String>()

        val roseNow =
            cancelInflightAnchorOnImeRise(
                keyboardInsetDp = 299.5f,
                lastKeyboardInsetDp = 0f,
                controllerState = controller,
                dispatch = { controller = reduceLazyViewport(controller, it) },
                log = { logs += it },
            )

        assertTrue(roseNow, "0 -> 299.5 is a rise transition")
        assertEquals(null, controller.pendingCommand, "the in-flight anchor must be cancelled")
        assertEquals(
            LazyViewportIntent.UserDragging,
            controller.intent,
            "and the viewport must belong to the user, not to another command"
        )
        assertTrue(
            logs.single().contains("cancelled_inflight"),
            "the receipt must say what was cancelled"
        )

        // While the keyboard stays open, later passes are not rise transitions
        // and must touch nothing.
        val stillOpen =
            cancelInflightAnchorOnImeRise(
                keyboardInsetDp = 299.5f,
                lastKeyboardInsetDp = 299.5f,
                controllerState = controller,
                dispatch = { throw AssertionError("no dispatch while already open") },
                log = { },
            )
        assertFalse(stillOpen)

        // A user's explicit back-to-bottom afterwards must not be collateral:
        // FollowLatest is not an Anchored intent, so a fresh rise leaves its
        // command alone.
        var userController = LazyViewportControllerState()
        userController =
            reduceLazyViewport(
                userController,
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)
            )
        val userCommand = requireNotNull(userController.pendingCommand).id
        cancelInflightAnchorOnImeRise(
            keyboardInsetDp = 299.5f,
            lastKeyboardInsetDp = 0f,
            controllerState = userController,
            dispatch = { userController = reduceLazyViewport(userController, it) },
            log = { },
        )
        assertEquals(
            userCommand,
            userController.pendingCommand?.id,
            "a user-intent command must survive the rise-cancel"
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

    @Test
    fun anOrdinaryFollowingLatestRiseArmsWithoutTouchingAnything() {
        // The common case: no focus, the list simply follows latest, and the
        // keyboard rises. The rise must arm the switch without cancelling or
        // issuing anything — FollowingLatest is not an anchored correction.
        var controller = LazyViewportControllerState()
        controller =
            reduceLazyViewport(
                controller,
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)
            )
        val pendingBefore = controller.pendingCommand?.id
        val logs = mutableListOf<String>()

        cancelInflightAnchorOnImeRise(
            keyboardInsetDp = 299.5f,
            lastKeyboardInsetDp = 0f,
            controllerState = controller,
            dispatch = { controller = reduceLazyViewport(controller, it) },
            log = { logs += it },
        )

        assertEquals(
            pendingBefore,
            controller.pendingCommand?.id,
            "a FollowingLatest command must survive an ordinary rise"
        )
        assertTrue(logs.single().contains("result=armed"))
    }

    @Test
    fun theColdInitialRetryStopsWhenTheKeyboardOpensMidLoop() {
        val logs = mutableListOf<String>()

        // Closed: the retry proceeds.
        assertFalse(suppressInitialBottomRetry(0f, { logs += it }))
        assertTrue(logs.isEmpty())

        // The keyboard opened between retries: the loop must accept the
        // current viewport and stop, with a receipt saying why.
        assertTrue(suppressInitialBottomRetry(299.5f, { logs += it }))
        assertTrue(logs.single().contains("suppressed_initial_bottom_retry"))
    }

    @Test
    fun aFocusRequestArrivingWithTheKeyboardAlreadyOpenIssuesNoAnchor() {
        // Already-open means no 0->open transition will ever occur for this
        // focus, so the rise-cancel can never fire for it — the anchor must
        // not be dispatched at all, which also starves the HeightChange
        // replay of its anchored intent.
        val logs = mutableListOf<String>()

        assertTrue(suppressFocusAnchorWhileOpen(299.5f, "message-17", { logs += it }))
        assertTrue(logs.single().contains("suppressed_focus_anchor"))
        assertTrue(logs.single().contains("message-17"))

        // Keyboard closed: focus anchors dispatch normally.
        assertFalse(suppressFocusAnchorWhileOpen(0f, "message-17", { logs += it }))
    }

    @Test
    fun theAutoBottomHeightChangeStaysSilentWhileOpen() {
        val logs = mutableListOf<String>()

        assertTrue(suppressAutoBottomHeightChange(299.5f, { logs += it }))
        assertTrue(logs.single().contains("suppressed_bottom_height_change"))

        assertFalse(suppressAutoBottomHeightChange(0f, { logs += it }))
        assertEquals(1, logs.size, "no receipt when nothing is suppressed")
    }

    @Test
    fun theBottomCollectorPassIssuesNothingWhileOpenAndCorrectsWhenClosed() {
        // The long-lived collector: an ordinary FollowingLatest viewport where
        // the list falls off the rendered bottom (true -> false). With the
        // keyboard open the pass must land nothing on the reducer; with it
        // closed the correction proceeds as before.
        var controller = LazyViewportControllerState()
        controller =
            reduceLazyViewport(
                controller,
                LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)
            )
        controller =
            reduceLazyViewport(
                controller,
                LazyViewportEvent.CommandConsumed(requireNotNull(controller.pendingCommand).id)
            )
        val sequenceBefore = controller.nextSequence
        val state =
            ThreadBottomAnchorCorrectionState(
                allowBottomAutoAnchor = true,
                isScrollInProgress = false,
                isAtRenderedBottom = false,
                focusedAnchorOwnsViewport = false,
            )
        assertTrue(shouldAutoCorrectThreadBottomAnchor(state), "the pass would correct")

        // Keyboard open: the production pass ends at the seam, nothing reaches
        // the reducer.
        if (!suppressAutoBottomHeightChange(299.5f) { }) {
            controller =
                reduceLazyViewport(
                    controller,
                    LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange)
                )
        }
        assertEquals(sequenceBefore, controller.nextSequence, "no new sequence while open")
        assertEquals(null, controller.pendingCommand, "no new pending while open")

        // Keyboard closed: the same pass corrects.
        if (!suppressAutoBottomHeightChange(0f) { }) {
            controller =
                reduceLazyViewport(
                    controller,
                    LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange)
                )
        }
        assertTrue(controller.pendingCommand != null, "the correction returns once closed")
    }
}
