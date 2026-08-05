package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #990 — the debt's whole lifecycle, driven as event sequences.
 *
 * The previous attempt at this fix was tested entirely through single calls to
 * pure helpers, and every one of them passed while three real defects sat in the
 * wiring: the guard that refused a handed-off owner was never given the handoff,
 * the debt could be taken on in a state where nothing could ever repay it, and
 * no path except issuing the command ever cleared it. Single-call assertions
 * cannot see any of those, so this file asserts over sequences, and the effect
 * wiring is asserted separately against a real composition.
 */
class ThreadImeHandoffTest {

    private val target = "message-17:3"
    private val other = "message-9:4"

    private fun onset(
        targetIdentity: String? = target,
        focusedTransactionLive: Boolean = true,
        dischargeable: Boolean = true,
        userScrollGeneration: Int = 3
    ) = ThreadImeHandoffEvent.ImeOnset(
        targetIdentity = targetIdentity,
        focusedTransactionLive = focusedTransactionLive,
        dischargeable = dischargeable,
        userScrollGeneration = userScrollGeneration
    )

    private fun run(vararg events: ThreadImeHandoffEvent): ThreadImeHandoffState =
        events.fold(ThreadImeHandoffState()) { state, event -> reduceThreadImeHandoff(state, event) }

    @Test
    fun aLiveFocusedTransactionAtOnsetTakesOnTheDebt() {
        // The white cycle: focus command settled 38ms before the keyboard.
        val state = run(onset())
        assertTrue(state.owes(target))
        assertEquals(3, state.userScrollGeneration)
    }

    @Test
    fun theHealthyCycleTakesOnNothing() {
        // 02:26:14 — the focused transaction had already settled, so the
        // composer was already the only owner and there is nothing to hand over.
        assertFalse(run(onset(focusedTransactionLive = false)).owes(target))
    }

    @Test
    fun noDebtIsTakenOnWhenNothingCouldEverRepayIt() {
        // A target the composer's tail path cannot correct — a history target,
        // or one with newer pages still to load. Taking the debt on would retire
        // the focused owner and then retain a correction forever.
        assertFalse(run(onset(dischargeable = false)).owes(target))
        assertFalse(run(onset(targetIdentity = null)).owes(null))
    }

    @Test
    fun oneRiseTakesTheDebtOnOnce() {
        // A second inset change inside the same rise must not restart the
        // handoff, and in particular must not move the scroll baseline forward
        // past a scroll that happened in between.
        val state = run(onset(userScrollGeneration = 3), onset(userScrollGeneration = 9))
        assertTrue(state.owes(target))
        assertEquals(3, state.userScrollGeneration, "the original baseline must survive")
    }

    @Test
    fun issuingTheCommandClearsTheDebtAndASecondPassIssuesNothing() {
        val afterIssue = run(onset(), ThreadImeHandoffEvent.CommandIssued)
        assertFalse(afterIssue.owes(target), "one shrink owes exactly one command")
        assertEquals(ThreadImeHandoffPhase.Idle, afterIssue.phase)
    }

    @Test
    fun everyWayTheRiseCanEndAlsoEndsTheDebt() {
        // Each of these left the debt outstanding in the previous attempt. A
        // debt that only ends where it is repaid is a permanent pending change.
        val enders =
            listOf(
                "keyboard closed" to ThreadImeHandoffEvent.KeyboardClosed,
                "page cycled" to ThreadImeHandoffEvent.PageCycled,
                "focus moved" to ThreadImeHandoffEvent.TargetChanged(other),
                "focus cleared" to ThreadImeHandoffEvent.TargetChanged(null),
                "user scrolled" to ThreadImeHandoffEvent.UserScrolled(4)
            )
        enders.forEach { (name, event) ->
            assertFalse(run(onset(), event).owes(target), "$name must end the debt")
        }
    }

    @Test
    fun theDebtSurvivesTheThingsThatAreNotItsEnd() {
        // The mirror of the test above: if these also cleared it, the handoff
        // would never survive long enough to be repaid and the assertions above
        // would pass for the wrong reason.
        assertTrue(
            run(onset(), ThreadImeHandoffEvent.TargetChanged(target)).owes(target),
            "the same target re-reported must not end the debt"
        )
        assertTrue(
            run(onset(userScrollGeneration = 3), ThreadImeHandoffEvent.UserScrolled(3)).owes(target),
            "the unchanged scroll generation must not end the debt"
        )
    }

    @Test
    fun theWhiteSequenceEndToEnd() {
        // onset -> viewport coherent but target unmeasurable -> target appears
        // -> command issued -> nothing further owed.
        var state = reduceThreadImeHandoff(ThreadImeHandoffState(), onset())
        assertTrue(state.owes(target), "1. the shrink is taken from the focused owner")

        // Nothing happens for a few passes: the target is not measurable yet.
        repeat(3) {
            state = reduceThreadImeHandoff(state, ThreadImeHandoffEvent.TargetChanged(target))
            state = reduceThreadImeHandoff(state, ThreadImeHandoffEvent.UserScrolled(3))
            assertTrue(state.owes(target), "2. the debt must survive an unmeasurable target")
        }

        state = reduceThreadImeHandoff(state, ThreadImeHandoffEvent.CommandIssued)
        assertFalse(state.owes(target), "3. issuing the correction repays it")

        state = reduceThreadImeHandoff(state, ThreadImeHandoffEvent.TargetChanged(target))
        assertFalse(state.owes(target), "4. and it stays repaid")
    }

    @Test
    fun aDebtIsOwedOnlyToTheTargetThatEarnedIt() {
        val state = run(onset())
        assertTrue(state.owes(target))
        assertFalse(state.owes(other), "a different target must not inherit the debt")
        assertFalse(state.owes(null), "and a cleared target must not either")
        assertFalse(
            ThreadImeHandoffState().owes(null),
            "an idle state must not read as owing when both sides are null"
        )
    }

    @Test
    fun theResolverRetainsAnOwedCorrectionThroughAnUnmeasurableTarget() {
        // The step that turns the handoff into a silent freeze if it is missing:
        // the viewport is already coherent, the focused owner has been
        // dismissed, and the target cannot be measured yet.
        fun resolve(owed: Boolean, tailFits: Boolean, pending: Boolean) =
            resolveThreadComposerLayoutBottomAnchor(
                ThreadComposerLayoutBottomAnchorState(
                    previousKeyboardInsetDp = if (pending) 336f else 0f,
                    nextKeyboardInsetDp = 336f,
                    previousViewportHeightDp = if (pending) 364f else 700f,
                    nextViewportHeightDp = 364f,
                    pendingLayoutChange = pending,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 18,
                    allowBottomAutoAnchor = false,
                    viewportEpochCoherent = true,
                    focusedAnchorOwnsViewport = false,
                    focusedActiveTailAnchorCanCorrectLayout = false,
                    focusedTailAnchorCanCorrectLayout = tailFits,
                    composerHandoffOwed = owed
                )
            )

        val unmeasurable = resolve(owed = true, tailFits = false, pending = false)
        assertFalse(unmeasurable.shouldAnchor, "an unmeasurable target cannot be anchored")
        assertTrue(unmeasurable.pendingLayoutChange, "the owed correction must be retained")

        assertFalse(
            resolve(owed = false, tailFits = false, pending = true).pendingLayoutChange,
            "without a debt the correction is still dropped — the retention is what is new"
        )

        val issued = resolve(owed = true, tailFits = true, pending = true)
        assertTrue(issued.shouldAnchor, "the retained correction is issued once measurable")
        assertTrue(issued.anchorFocusedTailAtRenderedBottom, "and as a RenderedBottom")
        assertFalse(issued.pendingLayoutChange, "issuing it clears the queue")
    }

    @Test
    fun onlyAnObservedLandingMayPublishACorrection() {
        fun mayPublish(
            outcome: ThreadViewportSettlementOutcome,
            retired: String? = null,
            visible: Boolean = true
        ) = focusedOriginCorrectionMayPublishCompletion(
            settlementOutcome = outcome,
            targetVisible = visible,
            targetIdentity = target,
            retiredIdentity = retired
        )

        assertTrue(mayPublish(ThreadViewportSettlementOutcome.ConsumedOriginal))
        // Every non-landing outcome, enumerated rather than blacklisted: the
        // string version rejected two spellings and published for everything
        // else, including "there was no command at all".
        ThreadViewportSettlementOutcome.entries
            .filter { it != ThreadViewportSettlementOutcome.ConsumedOriginal }
            .forEach { assertFalse(mayPublish(it), "$it must not publish") }

        assertFalse(
            mayPublish(ThreadViewportSettlementOutcome.ConsumedOriginal, retired = target),
            "a handed-off owner must not publish even when its command landed"
        )
        assertFalse(
            mayPublish(ThreadViewportSettlementOutcome.ConsumedOriginal, visible = false),
            "visibility stays necessary — it has only stopped being sufficient"
        )
    }

    @Test
    fun repayingTheDebtDoesNotUnRetireTheSession() {
        // The session is a coroutine that is still alive after the handoff. If
        // the retirement were derived from the debt, issuing the command would
        // lift it and the session's next recheck would reanchor into the
        // correction that had just been issued.
        val afterIssue = run(onset(), ThreadImeHandoffEvent.CommandIssued)
        assertFalse(afterIssue.owes(target), "the debt is settled")
        assertTrue(afterIssue.retired(target), "but the ended session stays ended")

        // Every other way the rise ends behaves the same way.
        listOf(
            ThreadImeHandoffEvent.KeyboardClosed,
            ThreadImeHandoffEvent.UserScrolled(4),
            ThreadImeHandoffEvent.TailFitResolved(fitsViewport = false),
            ThreadImeHandoffEvent.TargetChanged(target)
        ).forEach { event ->
            val state = run(onset(), event)
            assertTrue(state.retired(target), "$event must not resurrect the session")
        }

        // A new focus request carries a new identity, and that does start over.
        val refocused = run(onset(), ThreadImeHandoffEvent.TargetChanged(other))
        assertFalse(refocused.retired(target))
        assertFalse(refocused.retired(other))
    }

    @Test
    fun aTargetThatTurnsOutNotToReachTheTailGivesTheDebtBack() {
        // The debt can be taken while the fit is unmeasurable — that window is
        // why it exists — so a same-page history target that resolves to "does
        // not fit" has to be able to hand it back, or the correction is retained
        // for a target the composer's tail path will never correct.
        val unknownThenBad =
            run(onset(), ThreadImeHandoffEvent.TailFitResolved(fitsViewport = false))
        assertFalse(unknownThenBad.owes(target), "a target that cannot be corrected owes nothing")

        val unknownThenGood =
            run(onset(), ThreadImeHandoffEvent.TailFitResolved(fitsViewport = true))
        assertTrue(unknownThenGood.owes(target), "a target that does reach the tail keeps the debt")
    }

    @Test
    fun aYieldingFocusEffectAlwaysPublishesWhatTheNewOwnerNeeds() {
        // The defect this replaces returned before publishing, leaving the
        // composer's consumed-tail owner permanently ineligible. Asserted as an
        // invariant of the plan rather than as statement order.
        val retired = run(onset())
        val plan = threadFocusedAnchorSessionPlan(retired, target)
        assertTrue(plan.yieldToComposer)
        assertEquals(target, plan.sessionIdentityToPublish, "the session identity must be published")
        assertEquals(target, plan.consumedIdentityToPublish, "and the anchor marked consumed")

        val notRetired = threadFocusedAnchorSessionPlan(ThreadImeHandoffState(), target)
        assertFalse(notRetired.yieldToComposer, "an unaffected focus runs its own watch loop")
        assertEquals(null, notRetired.sessionIdentityToPublish)
        assertEquals(null, notRetired.consumedIdentityToPublish)

        // A retirement for another target must not divert this one.
        assertFalse(threadFocusedAnchorSessionPlan(retired, other).yieldToComposer)
    }

    @Test
    fun theControllerRecordsWhichCommandItActuallyConsumed() {
        // The receipt exists so a waiter that starts observing late can still
        // tell "landed" from "dropped" — without it, the only honest answer is
        // UnobservedBeforeConsume, and widening that into success is the false
        // positive being removed.
        val pinned = reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PinTop)
        val commandId = pinned.pendingCommand!!.id
        assertEquals(null, pinned.lastConsumedCommandId, "nothing is consumed yet")

        val consumed = reduceLazyViewport(pinned, LazyViewportEvent.CommandConsumed(commandId))
        assertEquals(commandId, consumed.lastConsumedCommandId, "consumption must leave a receipt")
        assertEquals(null, consumed.pendingCommand)

        // Clearing the pending slot for any other reason is not consumption, and
        // must not leave a receipt that says it was.
        val abandoned =
            reduceLazyViewport(
                reduceLazyViewport(LazyViewportControllerState(), LazyViewportEvent.PinTop),
                LazyViewportEvent.AnchorUnavailable(commandId)
            )
        assertEquals(
            null,
            abandoned.lastConsumedCommandId,
            "an abandoned command must not read as consumed"
        )
    }

    @Test
    fun aHandedOffSessionConsumesInsteadOfReanchoring() {
        assertEquals(
            FocusedAnchorSessionAction.Reanchor,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                handedOff = false
            ),
            "drift without a handoff must still reanchor"
        )
        assertEquals(
            FocusedAnchorSessionAction.Consume,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                handedOff = true
            )
        )
        assertEquals(
            FocusedAnchorSessionAction.Interrupt,
            focusedAnchorSessionAction(
                userInteracting = true,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                handedOff = true
            ),
            "a real user scroll must stay distinguishable from a handoff"
        )
    }

    @Test
    fun theCorrectionGateRefusesAHandedOffOwner() {
        fun correct(retired: String?) =
            shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = "loaded",
                targetIdentity = target,
                anchoredIdentity = target,
                correctedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 3,
                retiredIdentity = retired
            )

        assertTrue(correct(retired = null), "an unaffected correction must still run")
        assertTrue(correct(retired = other), "another target's handoff must not cancel it")
        assertFalse(correct(retired = target), "a handed-off correction must not dispatch")
    }
}
