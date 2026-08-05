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

        /**
         * What the effect writes when it releases the focused fence. Kept as the
         * identity itself rather than a boolean so the production predicates can
         * be called with the value they actually receive.
         */
        private var consumedIdentity: String? = null

        /** The healthy cycle: focus had already settled before the keyboard. */
        fun startAlreadyConsumed(identity: String) {
            focusedFenceReleased = true
            consumedIdentity = identity
        }

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
            // The effect switches on this same directive. Re-deriving "does the
            // pass stop here" is what made the first driver dispatch a command
            // the effect can never reach, so the branch is taken from the value
            // production returns rather than reconstructed.
            // The same production function the effect calls performs the fence
            // release and the retire dispatch. Doing them here instead would
            // make this a replica: deleting them from production would leave
            // every test below green.
            val endsPass =
                applyThreadImeHandoffPass(
                    pass = handoffPass,
                    targetIdentity = targetIdentity,
                    markConsumed = { consumedIdentity = it; focusedFenceReleased = true },
                    dispatch = { controller = reduceLazyViewport(controller, it) }
                )
            if (handoffPass.action == ThreadImeHandoffPassAction.EndPass) {
                previousKeyboardInsetDp = keyboardInsetDp
                previousViewportHeightDp = viewportHeightDp
                pendingLayoutChange = false
                return
            }
            if (endsPass) return

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
                                focusedTransactionLive &&
                                    focusedMessageAnchorOwnsViewport(
                                        pendingIdentity = targetIdentity,
                                        consumedIdentity = consumedIdentity,
                                        scrollInterruptedIdentity = null,
                                        yieldElapsedMs = 0L
                                    ),
                            focusedActiveTailAnchorCanCorrectLayout =
                                focusedMessageAnchorOwnsViewport(
                                    pendingIdentity = targetIdentity,
                                    consumedIdentity = consumedIdentity,
                                    scrollInterruptedIdentity = null,
                                    yieldElapsedMs = 0L
                                ) &&
                                    focusedActiveTailMessageComposerLayoutOwnsViewport(
                                        targetIdentity = targetIdentity,
                                        targetItemIndex = 17,
                                        latestMessageItemIndex = 17,
                                        targetToTailFitsViewport = tailFitAvailable,
                                        canLoadMoreAfter = false,
                                        anchoredIdentity = targetIdentity,
                                        consumedIdentity = consumedIdentity,
                                        scrollInterruptedIdentity = null,
                                        startUserScrollGeneration = anchorUserScrollGeneration,
                                        currentUserScrollGeneration = userScrollGeneration
                                    ),
                            // Computed by the production predicates rather than
                            // hand-written booleans. The first driver spelled
                            // this gate out itself and forgot the scroll
                            // generation comparison, which made it admit a
                            // correction production would refuse.
                            focusedTailAnchorCanCorrectLayout =
                                focusedTailMessageComposerLayoutOwnsViewport(
                                    targetIdentity = targetIdentity,
                                    targetItemIndex = 17,
                                    latestMessageItemIndex = 17,
                                    targetToTailFitsViewport = tailFitAvailable,
                                    canLoadMoreAfter = false,
                                    anchoredIdentity = targetIdentity,
                                    consumedIdentity = consumedIdentity,
                                    scrollInterruptedIdentity = null,
                                    startUserScrollGeneration = anchorUserScrollGeneration,
                                    currentUserScrollGeneration = userScrollGeneration
                                )
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
                        provenance =
                            LazyViewportCommandProvenance.ComposerIme(
                                ownerIdentity = requireNotNull(targetIdentity)
                            )
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
        fun dispatchInFlight(provenance: LazyViewportCommandProvenance): LazyViewportCommandId {
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
                        provenance = provenance
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
        driver.startAlreadyConsumed(target)

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
            LazyViewportCommandProvenance.FocusedSessionReanchor(target),
            LazyViewportCommandProvenance.FocusedOriginCorrection(target)
        ).forEach { provenance ->
            val driver = ComposerDriver()
            val stale = driver.dispatchInFlight(provenance)
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
            driver.dispatchInFlight(LazyViewportCommandProvenance.FocusedInitial(target))

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
            LazyViewportCommandProvenance.FocusedSessionReanchor("message-9:4"),
            LazyViewportCommandProvenance.Other
        ).forEach { provenance ->
            val driver = ComposerDriver()
            val unrelated = driver.dispatchInFlight(provenance)

            driver.pass(
                keyboardInsetDp = 336f,
                viewportHeightDp = 364f,
                focusedTransactionLive = true,
                tailFitAvailable = false
            )
            assertTrue(
                driver.stillCurrent(unrelated),
                "$provenance must not be retired by this target's handoff"
            )
        }
    }

    @Test
    fun aRetiredCorrectionIsNotRegeneratedByALaterContentMutation() {
        // Clearing the pending command is not enough on its own: the intent is
        // what produces commands, and it survives the retirement. Any content
        // mutation during the shrink's remeasure window would otherwise reissue
        // the same correction, from the same intent, with the pre-shrink offset
        // — and no second retirement is sent, because the handoff has already
        // happened.
        listOf(
            LazyViewportCommandProvenance.FocusedSessionReanchor(target),
            LazyViewportCommandProvenance.FocusedOriginCorrection(target)
        ).forEach { provenance ->
            var controller = LazyViewportControllerState()
            controller = anchor(controller, provenance)
            assertTrue(controller.pendingCommand != null, "$provenance starts pending")

            controller =
                reduceLazyViewport(
                    controller,
                    LazyViewportEvent.RetireFocusedCorrections(ownerIdentity = target)
                )
            assertEquals(null, controller.pendingCommand, "$provenance is dropped at the handoff")

            listOf(
                LazyViewportMutation.InsetChange,
                LazyViewportMutation.HeightChange,
                LazyViewportMutation.Prepend
            ).forEach { mutation ->
                controller =
                    reduceLazyViewport(controller, LazyViewportEvent.ContentMutated(mutation))
                assertEquals(
                    null,
                    controller.pendingCommand,
                    "$mutation must not regenerate a retired $provenance"
                )
            }
        }
    }

    @Test
    fun retirementDoesNotStopTheOwnersThatAreStillAllowedToReplay() {
        // The mirror: if retirement blocked replay generally, this fix would
        // have disabled ordinary anchor maintenance rather than one owner's.
        listOf(
            LazyViewportCommandProvenance.FocusedInitial(target),
            LazyViewportCommandProvenance.ComposerIme(target),
            LazyViewportCommandProvenance.Other,
            LazyViewportCommandProvenance.FocusedSessionReanchor("message-9:4")
        ).forEach { provenance ->
            var controller = LazyViewportControllerState()
            controller = anchor(controller, provenance)
            controller =
                reduceLazyViewport(
                    controller,
                    LazyViewportEvent.RetireFocusedCorrections(ownerIdentity = target)
                )
            controller.pendingCommand?.id?.let {
                controller = reduceLazyViewport(controller, LazyViewportEvent.CommandConsumed(it))
            }
            controller =
                reduceLazyViewport(
                    controller,
                    LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange)
                )
            assertTrue(
                controller.pendingCommand != null,
                "$provenance must still replay after another target's handoff"
            )
        }
    }

    private fun anchor(
        state: LazyViewportControllerState,
        provenance: LazyViewportCommandProvenance
    ): LazyViewportControllerState =
        reduceLazyViewport(
            state,
            LazyViewportEvent.Anchor(
                target = LazyViewportAnchorTarget.Message("message-17"),
                offset = 0,
                layoutGeneration = 1L,
                placement = LazyViewportAnchorPlacement.Focused,
                renderedTailMessageId = null,
                renderedTailAnchorIdentity = null,
                fallback = LazyViewportAnchorFallback.Free,
                provenance = provenance
            )
        )

    /**
     * Drives the two production seams for an over-tall canonical latest target
     * and lands the result on the real controller.
     */
    private class TallLatestDriver {
        var handoff = ThreadImeHandoffState()
        var controller = LazyViewportControllerState()
        var previousKeyboardInsetDp = 0f
        var previousViewportHeightDp = 622.8f
        var pendingLayoutChange = false
        var consumedIdentity: String? = null
        val target = "message-17:3"

        fun pass(
            keyboardInsetDp: Float,
            viewportHeightDp: Float,
            focusedTransactionLive: Boolean,
            targetVisible: Boolean
        ) {
            val handoffPass =
                threadImeHandoffPass(
                    handoff = handoff,
                    previousKeyboardInsetDp = previousKeyboardInsetDp,
                    keyboardInsetDp = keyboardInsetDp,
                    targetIdentity = target,
                    focusedTransactionLive = focusedTransactionLive && consumedIdentity != target,
                    dischargeable = true,
                    userScrollGeneration = 3,
                    pageCycleActive = false,
                    // Measured and too tall, or not measurable at all.
                    tailFitsViewport = if (targetVisible) false else null,
                    targetIsCanonicalLatest = true
                )
            handoff = handoffPass.handoff
            val ended =
                applyThreadImeHandoffPass(
                    pass = handoffPass,
                    targetIdentity = target,
                    markConsumed = { consumedIdentity = it },
                    dispatch = { controller = reduceLazyViewport(controller, it) }
                )
            if (handoffPass.action == ThreadImeHandoffPassAction.EndPass) return
            if (ended) return

            val layout =
                ThreadComposerLayoutBottomAnchorState(
                    previousKeyboardInsetDp = previousKeyboardInsetDp,
                    nextKeyboardInsetDp = keyboardInsetDp,
                    previousViewportHeightDp = previousViewportHeightDp,
                    nextViewportHeightDp = viewportHeightDp,
                    pendingLayoutChange = pendingLayoutChange,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 18,
                    allowBottomAutoAnchor = true,
                    viewportEpochCoherent = true,
                    focusedAnchorOwnsViewport = false,
                    focusedActiveTailAnchorCanCorrectLayout = false,
                    // 1236px inside a 1059px viewport never fits.
                    focusedTailAnchorCanCorrectLayout = false
                )
            val eligible =
                threadImeLatestTargetBottomAnchorEligible(
                    keyboardShrinking =
                        keyboardInsetDp > 0f &&
                            (threadComposerLayoutChanged(layout) || pendingLayoutChange),
                    viewportEpochCoherent = true,
                    targetIdentity = target,
                    targetItemIndex = 17,
                    latestMessageItemIndex = 17,
                    canLoadMoreAfter = false,
                    scrollInterruptedIdentity = null,
                    startUserScrollGeneration = 3,
                    currentUserScrollGeneration = 3,
                    sessionSettledOrConsumed = consumedIdentity == target,
                    handoffOwed = handoff.owes(target)
                )
            val correction =
                threadImeCorrectionPass(
                    handoff = handoff,
                    targetIdentity = target,
                    layout = layout,
                    latestBottomAnchorEligible = eligible
                )
            handoff = correction.handoff
            previousKeyboardInsetDp = keyboardInsetDp
            previousViewportHeightDp = viewportHeightDp
            pendingLayoutChange = correction.decision.pendingLayoutChange
            if (!correction.decision.shouldAnchor) return

            applyThreadImeCorrection(
                decision = correction.decision,
                targetMessageId = "message-17",
                targetIdentity = target,
                tailMessageId = "message-17",
                // Unmeasured reports 0; the padding is what the executor needs.
                renderedTailOffsetPx = 54,
                layoutGeneration = 1L,
                dispatch = { controller = reduceLazyViewport(controller, it) }
            )
        }

        fun consumePending() {
            controller.pendingCommand?.id?.let {
                controller = reduceLazyViewport(controller, LazyViewportEvent.CommandConsumed(it))
            }
        }
    }

    @Test
    fun aSettledTallLatestTargetIssuesARenderedBottomCommand() {
        // Cell 1: no handoff, focus already consumed, real layout change, target
        // measured at 1236 inside 1059. The command must reach the controller as
        // a RenderedBottom for this identity — not a replayed intent.
        val d = TallLatestDriver()
        d.consumedIdentity = d.target

        d.pass(
            keyboardInsetDp = 299.5f,
            viewportHeightDp = 315.3f,
            focusedTransactionLive = false,
            targetVisible = true
        )

        val pending = d.controller.pendingCommand
        assertTrue(pending != null, "a command must reach the controller")
        val anchored = d.controller.intent as? LazyViewportIntent.Anchored
        assertTrue(anchored != null, "and it must anchor rather than replay")
        assertEquals(LazyViewportAnchorPlacement.RenderedBottom, anchored.placement)
        assertEquals(LazyViewportAnchorTarget.Message("message-17"), anchored.target)
        assertEquals(54, anchored.offset, "the trailing padding must survive to the executor")
        assertEquals(
            LazyViewportCommandProvenance.ComposerIme(d.target),
            pending!!.provenance,
            "and it must be attributable to the composer for this identity"
        )

        d.consumePending()
        assertEquals(
            pending.id,
            d.controller.lastConsumedCommandId,
            "the receipt must name the command that landed"
        )
    }

    @Test
    fun anActiveTallLatestTargetTakesTheDebtAndThenIssuesOneCommand() {
        // Cell 2: the debt is taken by the real onset — not constructed by hand
        // — and the replacement pass issues exactly one RenderedBottom.
        val d = TallLatestDriver()

        d.pass(
            keyboardInsetDp = 299.5f,
            viewportHeightDp = 315.3f,
            focusedTransactionLive = true,
            targetVisible = true
        )
        assertTrue(
            d.handoff.owes(d.target),
            "a measured tall latest target must still be able to take the debt"
        )
        assertEquals(d.target, d.consumedIdentity, "and the fence must be released")
        assertEquals(null, d.controller.pendingCommand, "taking it issues nothing by itself")

        d.pass(
            keyboardInsetDp = 299.5f,
            viewportHeightDp = 315.3f,
            focusedTransactionLive = true,
            targetVisible = true
        )
        val pending = d.controller.pendingCommand
        assertTrue(pending != null, "the replacement pass must issue the correction")
        assertEquals(
            LazyViewportAnchorPlacement.RenderedBottom,
            (d.controller.intent as? LazyViewportIntent.Anchored)?.placement
        )
        assertEquals(LazyViewportCommandProvenance.ComposerIme(d.target), pending!!.provenance)
        assertFalse(d.handoff.owes(d.target), "and it repays the debt")
    }

    @Test
    fun anOpenKeyboardWithNoLayoutChangeIssuesNothing() {
        // The control for the "keyboard is open" defect: without a real layout
        // change or a pending one, an effect restart must not reissue the
        // anchor and drag the user back.
        val d = TallLatestDriver()
        d.consumedIdentity = d.target
        d.previousKeyboardInsetDp = 299.5f
        d.previousViewportHeightDp = 315.3f

        repeat(3) {
            d.pass(
                keyboardInsetDp = 299.5f,
                viewportHeightDp = 315.3f,
                focusedTransactionLive = false,
                targetVisible = true
            )
        }
        assertEquals(
            null,
            d.controller.pendingCommand,
            "an open keyboard alone must not produce a command"
        )
    }
}
