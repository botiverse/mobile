package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #990 — the shrink driven end to end through the functions the composer
 * effect actually calls, with the commands landing in the real viewport
 * controller.
 *
 * This exists because the previous attempt was tested entirely with single calls
 * to pure helpers and passed while three defects sat in the derivation between
 * them: an event that was never fed, a guard that was never given its argument,
 * and an early return that left the incoming owner permanently ineligible. None
 * of those are visible to a reducer, which only sees events someone remembered
 * to send. So the passes below are the production passes, in production order,
 * and the assertion is about commands reaching the controller rather than about
 * any helper's return value.
 */
class ThreadImeShrinkSequenceTest {

    private val target = "message-17:3"

    /**
     * One composer pass. Mirrors the effect's order — lifecycle and onset first,
     * then, if the shrink was not taken this pass, the layout decision — and
     * dispatches into the real controller reducer.
     */
    private class ComposerDriver(
        var handoff: ThreadImeHandoffState = ThreadImeHandoffState(),
        var controller: LazyViewportControllerState = LazyViewportControllerState(),
        var previousKeyboardInsetDp: Float = 0f,
        var previousViewportHeightDp: Float = 700f,
        var pendingLayoutChange: Boolean = false
    ) {
        val commands = mutableListOf<LazyViewportCommandId>()
        var focusedFenceReleased = false

        /** Scroll generation the focused anchor was taken at. */
        private val anchorUserScrollGeneration = 3

        fun pass(
            keyboardInsetDp: Float,
            viewportHeightDp: Float,
            focusedTransactionLive: Boolean,
            tailFitAvailable: Boolean,
            viewportEpochCoherent: Boolean = true,
            userScrollGeneration: Int = 3,
            targetIdentity: String? = "message-17:3",
            dischargeable: Boolean = true,
            pageCycleActive: Boolean = false
        ) {
            val handoffPass =
                threadImeHandoffPass(
                    handoff = handoff,
                    previousKeyboardInsetDp = previousKeyboardInsetDp,
                    keyboardInsetDp = keyboardInsetDp,
                    targetIdentity = targetIdentity,
                    // Once the fence is released the focused transaction is no
                    // longer live, exactly as the effect recomputes it.
                    focusedTransactionLive = focusedTransactionLive && !focusedFenceReleased,
                    dischargeable = dischargeable,
                    userScrollGeneration = userScrollGeneration,
                    pageCycleActive = pageCycleActive
                )
            handoff = handoffPass.handoff
            if (pageCycleActive) {
                // Production returns from the effect here, before the layout
                // decision: a thread reloading around a newer page resets the
                // pending anchors rather than correcting the old viewport.
                // Running on past this point made the driver dispatch a command
                // the effect would never reach.
                previousKeyboardInsetDp = keyboardInsetDp
                previousViewportHeightDp = viewportHeightDp
                pendingLayoutChange = false
                return
            }
            if (handoffPass.tookHandoff) {
                // The effect releases the fence, retires whatever the old owners
                // already put in flight, and ends the pass here — leaving the
                // inset carry-forward unreached so the next pass still sees the
                // rise.
                focusedFenceReleased = true
                targetIdentity?.let {
                    controller =
                        reduceLazyViewport(
                            controller,
                            LazyViewportEvent.RetireFocusedCorrections(ownerIdentity = it)
                        )
                }
                return
            }

            val correctionPass =
                threadImeCorrectionPass(
                    handoff = handoff,
                    targetIdentity = targetIdentity,
                    layout =
                        ThreadComposerLayoutBottomAnchorState(
                            previousKeyboardInsetDp = previousKeyboardInsetDp,
                            nextKeyboardInsetDp = keyboardInsetDp,
                            previousViewportHeightDp = previousViewportHeightDp,
                            nextViewportHeightDp = viewportHeightDp,
                            pendingLayoutChange = pendingLayoutChange,
                            initialBottomAnchorReady = true,
                            latestItemIndex = 18,
                            // The focused route disables the bottom auto anchor.
                            allowBottomAutoAnchor = false,
                            viewportEpochCoherent = viewportEpochCoherent,
                            focusedAnchorOwnsViewport =
                                focusedTransactionLive && !focusedFenceReleased,
                            focusedActiveTailAnchorCanCorrectLayout = false,
                            // Production also requires the user not to have
                            // scrolled since the anchor was taken
                            // (focusedTailMessageComposerLayoutOwnsViewport
                            // compares the scroll generations). Leaving that out
                            // made this driver more permissive than the code it
                            // stands in for.
                            focusedTailAnchorCanCorrectLayout =
                                focusedFenceReleased &&
                                    tailFitAvailable &&
                                    userScrollGeneration == anchorUserScrollGeneration
                        )
                )
            handoff = correctionPass.handoff
            previousKeyboardInsetDp = keyboardInsetDp
            previousViewportHeightDp = viewportHeightDp
            pendingLayoutChange = correctionPass.decision.pendingLayoutChange
            if (!correctionPass.decision.shouldAnchor) return

            controller =
                reduceLazyViewport(
                    controller,
                    LazyViewportEvent.Anchor(
                        target = LazyViewportAnchorTarget.Message("message-17"),
                        offset = 0,
                        layoutGeneration = 1L,
                        placement =
                            if (correctionPass.decision.anchorFocusedTailAtRenderedBottom) {
                                LazyViewportAnchorPlacement.RenderedBottom
                            } else {
                                LazyViewportAnchorPlacement.Focused
                            },
                        renderedTailMessageId = "message-17",
                        renderedTailAnchorIdentity = targetIdentity,
                        fallback = LazyViewportAnchorFallback.Free,
                        provenance = LazyViewportCommandProvenance.ComposerIme,
                        ownerIdentity = targetIdentity
                    )
                )
            controller.pendingCommand?.id?.let { commands += it }
        }

        /** The list settles: whatever was dispatched is consumed. */
        fun settle() {
            controller.pendingCommand?.id?.let {
                controller = reduceLazyViewport(controller, LazyViewportEvent.CommandConsumed(it))
            }
        }

        /** An owner puts a command in flight before the keyboard arrives. */
        fun dispatchInFlight(
            provenance: LazyViewportCommandProvenance,
            ownerIdentity: String?
        ): LazyViewportCommandId {
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
                        provenance = provenance,
                        ownerIdentity = ownerIdentity
                    )
                )
            return controller.pendingCommand!!.id
        }

        /** Whether the list could still apply [commandId] — the executor's own check. */
        fun stillCurrent(commandId: LazyViewportCommandId): Boolean =
            controller.pendingCommand?.id == commandId && controller.epoch == commandId.epoch
    }

    @Test
    fun theWhiteSequenceProducesExactlyOneCommand() {
        val driver = ComposerDriver()

        // 1. The keyboard rises while the focused transaction is still live —
        //    the condition that separated the white cycle from the healthy one.
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        assertTrue(driver.focusedFenceReleased, "the shrink must be taken from the focused owner")
        assertTrue(driver.handoff.owes(target), "and one correction must now be owed")
        assertEquals(emptyList(), driver.commands, "taking the shrink must not itself issue a command")

        // 2. The replacement pass: viewport already coherent, target not yet
        //    measurable. This is where a naive handoff silently drops the
        //    correction and produces nothing at all.
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        assertEquals(emptyList(), driver.commands, "an unmeasurable target cannot be corrected yet")
        assertTrue(driver.pendingLayoutChange, "but the correction must still be queued")
        assertTrue(driver.handoff.owes(target), "and still owed")

        // 3. The target becomes measurable.
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = true
        )
        assertEquals(1, driver.commands.size, "the shrink must be corrected exactly once")
        assertFalse(driver.handoff.owes(target), "and the debt repaid")

        driver.settle()

        // 4. Further passes must not produce a second command for one shrink.
        repeat(3) {
            driver.pass(
                keyboardInsetDp = 336f,
                viewportHeightDp = 364f,
                focusedTransactionLive = true,
                tailFitAvailable = true
            )
        }
        assertEquals(1, driver.commands.size, "one shrink owes exactly one command")
    }

    @Test
    fun theHealthyCycleStillCorrectsItsOwnShrinkWithoutAHandoff() {
        // 02:26:14 — the focused transaction had already settled, so no debt is
        // taken and the composer's own path issues the single correction. If
        // this went quiet, the fix would have broken the working case.
        val driver = ComposerDriver()
        driver.focusedFenceReleased = true

        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = false,
            tailFitAvailable = true
        )

        assertFalse(driver.handoff.owes(target), "nothing to hand over")
        assertEquals(1, driver.commands.size, "the healthy path still corrects its shrink")
    }

    @Test
    fun aUserScrollDuringTheShrinkCancelsTheOwedCorrection() {
        val driver = ComposerDriver()
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        assertTrue(driver.handoff.owes(target))

        // The user drags before the target became measurable. The correction
        // must be abandoned, not replayed later into a list they have moved.
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = true,
            userScrollGeneration = 4
        )
        assertFalse(driver.handoff.owes(target), "the user's scroll outranks the owed correction")
        assertEquals(emptyList(), driver.commands, "and nothing may yank the list back")
    }

    @Test
    fun aKeyboardThatCloseBeforeTheTargetAppearsLeavesNothingOutstanding() {
        val driver = ComposerDriver()
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        assertTrue(driver.handoff.owes(target))

        driver.pass(
            keyboardInsetDp = 0f,
            viewportHeightDp = 700f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        assertFalse(driver.handoff.owes(target), "the debt must not outlive its rise")

        // And the restored viewport must not later be corrected for a keyboard
        // that is gone.
        driver.pass(
            keyboardInsetDp = 0f,
            viewportHeightDp = 700f,
            focusedTransactionLive = true,
            tailFitAvailable = true
        )
        assertEquals(emptyList(), driver.commands)
    }

    @Test
    fun aTargetTheComposerCanNeverCorrectNeverTakesTheShrink() {
        // A history target: the composer's tail path could not repay the debt,
        // so taking it on would retire the focused owner and then retain a
        // pending correction forever.
        val driver = ComposerDriver()
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false,
            dischargeable = false
        )

        assertFalse(driver.focusedFenceReleased, "the focused owner must keep the shrink")
        assertFalse(driver.handoff.owes(target), "and no debt may be recorded")
        assertEquals(emptyList(), driver.commands, "and the composer must not correct over it")
        // pendingLayoutChange stays true here, and correctly so: the focused
        // owner is still live, and deferring the composer's relayout while it
        // owns the viewport is the behaviour that predates this fix. The debt
        // is what must not exist, not the deferral.
    }

    @Test
    fun aPageCycleDuringTheShrinkAbandonsTheOwedCorrection() {
        val driver = ComposerDriver()
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        assertTrue(driver.handoff.owes(target))

        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = true,
            pageCycleActive = true
        )
        assertFalse(driver.handoff.owes(target), "a reloaded thread owes nothing")
        assertEquals(emptyList(), driver.commands)
    }

    @Test
    fun anIncoherentViewportDefersRatherThanDroppingTheCorrection() {
        val driver = ComposerDriver()
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = true,
            viewportEpochCoherent = false
        )
        assertEquals(emptyList(), driver.commands, "a mixed epoch must not be corrected against")
        assertTrue(driver.pendingLayoutChange, "the correction is deferred")
        assertTrue(driver.handoff.owes(target), "and still owed")

        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = true
        )
        assertEquals(1, driver.commands.size, "then issued once the epoch agrees")
    }

    @Test
    fun theOnsetRetiresAStaleCorrectionThatIsAlreadyInFlight() {
        // Forbidding the retired owners from issuing anything new is not enough:
        // a correction they dispatched before the keyboard arrived is still
        // pending, and during the window where the target cannot be measured it
        // would apply to the already-shrunken viewport — the original race, run
        // in full, inside the debt's own window.
        listOf(
            LazyViewportCommandProvenance.FocusedSessionReanchor,
            LazyViewportCommandProvenance.FocusedOriginCorrection
        ).forEach { provenance ->
            val driver = ComposerDriver()
            val stale = driver.dispatchInFlight(provenance, ownerIdentity = target)
            assertTrue(driver.stillCurrent(stale), "$provenance is current before the onset")

            driver.pass(
                keyboardInsetDp = 336f,
                viewportHeightDp = 364f,
                focusedTransactionLive = true,
                tailFitAvailable = false
            )
            assertFalse(driver.stillCurrent(stale), "$provenance must stop being current at the onset")

            // And it must stay dead through the unmeasurable window.
            driver.pass(
                keyboardInsetDp = 336f,
                viewportHeightDp = 364f,
                focusedTransactionLive = true,
                tailFitAvailable = false
            )
            assertFalse(driver.stillCurrent(stale), "$provenance must not revive while the target is missing")
        }
    }

    @Test
    fun theOnsetLeavesFocusPositioningAloneAndStillCorrectsOnce() {
        // If the keyboard lands while the initial focus command is in flight,
        // that command must still apply — it is the one that succeeded in both
        // captures, and dropping it would leave the target unplaced.
        val driver = ComposerDriver()
        val positioning =
            driver.dispatchInFlight(
                LazyViewportCommandProvenance.FocusedInitial,
                ownerIdentity = target
            )

        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = false
        )
        assertTrue(driver.stillCurrent(positioning), "focus positioning must survive the handoff")

        driver.settle()
        driver.pass(
            keyboardInsetDp = 336f,
            viewportHeightDp = 364f,
            focusedTransactionLive = true,
            tailFitAvailable = true
        )
        assertEquals(1, driver.commands.size, "and the composer still corrects the shrink exactly once")
    }

    @Test
    fun anotherTargetsCorrectionIsNotCollateral() {
        // Retirement is identity-scoped: a command belonging to a different
        // focus target, or to no focus at all, must be left alone.
        listOf(
            LazyViewportCommandProvenance.FocusedSessionReanchor to "message-9:4",
            LazyViewportCommandProvenance.Other to null
        ).forEach { (provenance, owner) ->
            val driver = ComposerDriver()
            val unrelated = driver.dispatchInFlight(provenance, ownerIdentity = owner)

            driver.pass(
                keyboardInsetDp = 336f,
                viewportHeightDp = 364f,
                focusedTransactionLive = true,
                tailFitAvailable = false
            )
            assertTrue(
                driver.stillCurrent(unrelated),
                "$provenance owned by $owner must not be retired by this target's handoff"
            )
        }
    }
}
