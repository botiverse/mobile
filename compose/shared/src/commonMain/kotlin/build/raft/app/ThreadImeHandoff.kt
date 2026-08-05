package build.raft.app

/**
 * task #990 — ownership of the viewport across a keyboard rise.
 *
 * Two cycles in one capture, same target and same 235px message, ended with
 * byte-identical geometry (`target 17:770+235`, `viewport 1059`, `gap 0`) and
 * opposite pixels. What differed was who still owned the viewport when the
 * keyboard began:
 *
 *  - healthy: the focused transaction had already settled (`consumed_quiet`), so
 *    one composer `RenderedBottom` corrected the shrink;
 *  - white: the initial focus command had settled 38ms earlier, so the focused
 *    session and the origin correction were both still live. They corrected the
 *    same shrink the composer was correcting, the commands superseded one
 *    another, and the frame drew blank.
 *
 * So the shrink is given exactly one owner: the stale focus work is cancelled
 * and the composer is left owing exactly one correction. Queuing the old work
 * instead would only defer the collision — focus positioning had already
 * succeeded in both cycles, and what remained was drift compensation for a
 * viewport that no longer exists.
 *
 * That debt is modelled explicitly rather than inferred from ownership flags.
 * An earlier attempt released the focused fence and assumed the composer would
 * pick the shrink up; the white sequence reaches its first coherent viewport
 * while the target is still unmeasurable, so the correction was dropped and the
 * result was zero commands instead of three — the same freeze, from the other
 * side. A debt that is written down can be retained, and — just as important —
 * can be cancelled on every path that ends the rise it belongs to.
 */
internal enum class ThreadImeHandoffPhase {
    /** No debt outstanding. */
    Idle,

    /** A shrink was taken from the focused owner and still owes one command. */
    Owed
}

internal data class ThreadImeHandoffState(
    val phase: ThreadImeHandoffPhase = ThreadImeHandoffPhase.Idle,
    /** The focus identity the debt belongs to; never compared when null. */
    val identity: String? = null,
    /** User scroll generation when the debt was taken on. */
    val userScrollGeneration: Int? = null,
    /**
     * The focus identity whose transaction was ended by a handoff.
     *
     * Deliberately a separate, longer-lived fact than the debt. Deriving the
     * retirement from [identity] meant that repaying the debt also lifted it —
     * and the focused session is a coroutine that is still alive, so its next
     * recheck would find itself un-retired and reanchor into the correction that
     * had just been issued. A transaction that was ended stays ended; only a new
     * focus request (which carries a new identity) starts a new one.
     */
    val retiredIdentity: String? = null
) {
    fun owes(targetIdentity: String?): Boolean =
        phase == ThreadImeHandoffPhase.Owed && identity != null && identity == targetIdentity

    fun retired(targetIdentity: String?): Boolean =
        retiredIdentity != null && retiredIdentity == targetIdentity
}

/**
 * Everything that can create or end a debt.
 *
 * Typed rather than a set of booleans so that adding a new way for a rise to end
 * forces every branch to be considered, instead of silently falling through to
 * "keep waiting" — which is how a debt becomes permanent.
 */
internal sealed interface ThreadImeHandoffEvent {
    /**
     * The keyboard began to rise.
     *
     * [focusedTransactionLive] is the condition that separated the two cycles.
     * [dischargeable] restricts the handoff to targets the composer can actually
     * correct — a target that will never fit against the tail could never repay
     * the debt, and taking one on for it would retain a pending correction
     * forever.
     */
    data class ImeOnset(
        val targetIdentity: String?,
        val focusedTransactionLive: Boolean,
        val dischargeable: Boolean,
        val userScrollGeneration: Int
    ) : ThreadImeHandoffEvent

    /** The composer correction was issued. */
    data object CommandIssued : ThreadImeHandoffEvent

    /**
     * The target-to-tail fit became known.
     *
     * A debt can be taken on while the fit is still unmeasurable — that window
     * is the whole reason the debt exists — so a target that turns out not to
     * fit has to be able to give it back afterwards, or the correction is
     * retained for a target the composer's tail path will never correct.
     */
    data class TailFitResolved(val fitsViewport: Boolean) : ThreadImeHandoffEvent

    /** The keyboard went away before the debt could be repaid. */
    data object KeyboardClosed : ThreadImeHandoffEvent

    /** Focus moved, or was cleared. */
    data class TargetChanged(val targetIdentity: String?) : ThreadImeHandoffEvent

    /** The user moved the list themselves. */
    data class UserScrolled(val userScrollGeneration: Int) : ThreadImeHandoffEvent

    /** The thread reloaded around a newer page. */
    data object PageCycled : ThreadImeHandoffEvent
}

/**
 * The whole lifecycle of one debt.
 *
 * Every event that ends a rise clears it. The alternative — clearing only where
 * the debt is repaid — leaves the resolver retaining a pending correction that
 * nothing will ever issue, and a later composer command that yanks the list back
 * after the user has already moved on.
 */
internal fun reduceThreadImeHandoff(
    state: ThreadImeHandoffState,
    event: ThreadImeHandoffEvent
): ThreadImeHandoffState =
    when (event) {
        is ThreadImeHandoffEvent.ImeOnset ->
            when {
                // Idempotent within one rise: a second inset change must not
                // restart the handoff or move its scroll baseline.
                state.owes(event.targetIdentity) -> state
                // Declining to take a debt must not also lift a retirement that
                // is already in force for this target: the session it ended is
                // still alive and would revive on its next recheck.
                event.targetIdentity == null -> ThreadImeHandoffState()
                !event.focusedTransactionLive -> state.clearDebt()
                // Nothing can repay it, so nothing may take it on.
                !event.dischargeable -> state.clearDebt()
                else ->
                    ThreadImeHandoffState(
                        phase = ThreadImeHandoffPhase.Owed,
                        identity = event.targetIdentity,
                        userScrollGeneration = event.userScrollGeneration,
                        retiredIdentity = event.targetIdentity
                    )
            }

        // The debt is settled; the retirement is not undone. The session
        // coroutine is still alive and would otherwise revive on its next
        // recheck, reanchoring into the command just issued.
        ThreadImeHandoffEvent.CommandIssued -> state.clearDebt()

        ThreadImeHandoffEvent.KeyboardClosed -> state.clearDebt()

        // A reloaded thread has no focus transaction left to have retired.
        ThreadImeHandoffEvent.PageCycled -> ThreadImeHandoffState()

        is ThreadImeHandoffEvent.TailFitResolved ->
            if (event.fitsViewport) state else state.clearDebt()

        is ThreadImeHandoffEvent.TargetChanged ->
            when {
                state.owes(event.targetIdentity) -> state
                // A new focus request carries a new identity, so the previous
                // retirement is spent along with everything else.
                state.retiredIdentity != null && state.retiredIdentity != event.targetIdentity ->
                    ThreadImeHandoffState()
                else -> state.clearDebt()
            }

        is ThreadImeHandoffEvent.UserScrolled ->
            // The user moving the list outranks a correction taken on for them,
            // but does not resurrect a session that was already ended.
            if (state.userScrollGeneration == event.userScrollGeneration) {
                state
            } else {
                state.clearDebt()
            }
    }

private fun ThreadImeHandoffState.clearDebt(): ThreadImeHandoffState =
    copy(
        phase = ThreadImeHandoffPhase.Idle,
        identity = null,
        userScrollGeneration = null
    )

/**
 * How a settlement ended.
 *
 * Typed because the caller's question is "did my command land", and a string
 * comparison answered that by listing the failures it happened to know about —
 * so an unrecognised or absent outcome read as success.
 */
internal enum class ThreadViewportSettlementOutcome {
    /** Seen pending, then cleared on its own epoch. The only success. */
    ConsumedOriginal,

    /** A different command took the pending slot within the same epoch. */
    ReplacedSameEpoch,

    /** The epoch moved on: this command never applied. */
    SupersededEpoch,

    /**
     * Terminal without ever being observed pending, and without a consumption
     * receipt naming this command.
     *
     * Kept distinct from [ConsumedOriginal] rather than renamed into it: not
     * seeing something happen is not evidence that it happened, and this state
     * cannot be told apart from a command that was dropped. If it turns up in
     * practice the answer is to make the landing observable — the controller now
     * writes a receipt for exactly this reason — not to widen what counts as
     * success.
     */
    UnobservedBeforeConsume,

    /** There was no command to wait for. */
    NoCommand
}

/**
 * Whether a focused origin correction may report itself settled.
 *
 * Publication previously rested on "is the target visible afterwards", which is
 * true for a command that was superseded before it ever applied — the white
 * cycle settled on a superseded command, still reported corrected, and that
 * false success released the composer. Visibility is still necessary; it has
 * stopped being sufficient.
 */
internal fun focusedOriginCorrectionMayPublishCompletion(
    settlementOutcome: ThreadViewportSettlementOutcome,
    targetVisible: Boolean,
    targetIdentity: String,
    retiredIdentity: String?
): Boolean =
    targetVisible &&
        retiredIdentity != targetIdentity &&
        settlementOutcome == ThreadViewportSettlementOutcome.ConsumedOriginal

/**
 * Outcome of the early part of one composer pass: everything that happens
 * before the layout is measured.
 */
/**
 * What the caller must do after the early part of a pass.
 *
 * Returned rather than left to the caller to infer, because a test driver that
 * re-implements these branches is a simulation of the effect, not the effect —
 * and a simulation drifts. Two of the three defects CI caught in the first
 * driver were exactly that: it ran past an early return the effect performs.
 */
internal enum class ThreadImeHandoffPassAction {
    /** Carry on to the layout decision. */
    Continue,

    /**
     * Stop here without carrying the inset forward, so the replacement pass
     * still sees this rise. Used when the shrink has just been taken: the writes
     * that record it are keys of the effect, so continuing would run the layout
     * decision in a body about to be cancelled and again in its successor.
     */
    EndPassRetainingRise,

    /** Stop here and carry the inset forward; nothing is owed. */
    EndPass
}

internal data class ThreadImeHandoffPass(
    val handoff: ThreadImeHandoffState,
    /**
     * True on the pass that took the shrink. The caller releases the focused
     * fence and ends the pass there, so the layout decision runs once, in the
     * replacement pass, rather than once in a body about to be cancelled and
     * again in its successor — two commands for one shrink.
     */
    val tookHandoff: Boolean,
    val action: ThreadImeHandoffPassAction
)

/**
 * Applies every lifecycle event of one composer pass, in the order the pass
 * produces them: the ways a rise can end first, then the onset itself.
 *
 * Extracted so the sequence a test drives is the sequence production runs. The
 * defects in the previous attempt were all in this derivation — an event that
 * was never fed, a comparison that never fired — and none of them are visible
 * from the reducer alone, which only ever sees events someone remembered to
 * send it.
 */
internal fun threadImeHandoffPass(
    handoff: ThreadImeHandoffState,
    previousKeyboardInsetDp: Float,
    keyboardInsetDp: Float,
    targetIdentity: String?,
    focusedTransactionLive: Boolean,
    dischargeable: Boolean,
    userScrollGeneration: Int,
    pageCycleActive: Boolean,
    /**
     * Whether the target reaches the tail within the viewport, or null while
     * that is still unmeasurable.
     *
     * Null is not treated as false: the unmeasurable window is exactly what the
     * debt exists to survive. But once the answer is known to be false — a
     * same-page history target whose span exceeds the viewport — the debt is
     * given back, because the composer's tail path will never correct it.
     */
    tailFitsViewport: Boolean? = null,
    /**
     * Whether the target is the canonical latest message.
     *
     * A latest target that does not fit is still repayable — it is bottom
     * anchored against the composer instead of tail-fitted — so "does not fit"
     * must not disqualify it here. History targets keep the old rule: nothing
     * can repay them, so no debt may be taken on for them.
     */
    targetIsCanonicalLatest: Boolean = false
): ThreadImeHandoffPass {
    if (pageCycleActive) {
        return ThreadImeHandoffPass(
            handoff = reduceThreadImeHandoff(handoff, ThreadImeHandoffEvent.PageCycled),
            tookHandoff = false,
            action = ThreadImeHandoffPassAction.EndPass
        )
    }
    var next = reduceThreadImeHandoff(handoff, ThreadImeHandoffEvent.TargetChanged(targetIdentity))
    next = reduceThreadImeHandoff(next, ThreadImeHandoffEvent.UserScrolled(userScrollGeneration))
    if (keyboardInsetDp <= 0f) {
        next = reduceThreadImeHandoff(next, ThreadImeHandoffEvent.KeyboardClosed)
    }
    // A canonical latest target keeps its debt whatever the fit resolves to:
    // the bottom anchor repays it without needing the target to fit.
    if (tailFitsViewport != null && !targetIsCanonicalLatest) {
        next =
            reduceThreadImeHandoff(
                next,
                ThreadImeHandoffEvent.TailFitResolved(fitsViewport = tailFitsViewport)
            )
    }
    if (keyboardInsetDp <= previousKeyboardInsetDp) {
        return ThreadImeHandoffPass(
            handoff = next,
            tookHandoff = false,
            action = ThreadImeHandoffPassAction.Continue
        )
    }
    val before = next
    next =
        reduceThreadImeHandoff(
            next,
            ThreadImeHandoffEvent.ImeOnset(
                targetIdentity = targetIdentity,
                focusedTransactionLive = focusedTransactionLive,
                // A target already known not to reach the tail can never be
                // corrected by the composer's tail path, so it may not take the
                // shrink even at the onset.
                dischargeable =
                    dischargeable && (targetIsCanonicalLatest || tailFitsViewport != false),
                userScrollGeneration = userScrollGeneration
            )
        )
    val tookHandoff = next.owes(targetIdentity) && !before.owes(targetIdentity)
    return ThreadImeHandoffPass(
        handoff = next,
        tookHandoff = tookHandoff,
        action =
            if (tookHandoff) {
                ThreadImeHandoffPassAction.EndPassRetainingRise
            } else {
                ThreadImeHandoffPassAction.Continue
            }
    )
}

/**
 * Which correction a pass issues.
 *
 * VC1000400 showed the handoff working and the correction still never being
 * issued: with the keyboard up, the target sat at offset 1803 in a 1059px
 * viewport, so it was outside `visibleItemsInfo` and had no measured geometry —
 * `fitHeightPx=-1` for the whole rise. The rendered-tail correction needs that
 * geometry, so it waited for a measurement that could not arrive precisely
 * because the target was off-screen. The debt was retained correctly and then
 * voided when the keyboard closed, leaving nobody competing and nobody
 * correcting.
 *
 * Bringing an off-screen item back does not need it measured first — the
 * executor already falls back to `scrollToItem(index)` when the item is not
 * visible. So an owed correction whose target cannot be measured is issued as a
 * focused anchor by index instead of waiting.
 */
internal enum class ThreadImeCorrectionKind {
    /** Nothing to issue this pass. */
    None,

    /** The precise correction: target measured, anchored against the tail. */
    RenderedTail,

    /**
     * The canonical latest message, anchored against the composer even though it
     * does not fit the shrunken viewport.
     *
     * VC1000400 produced 981‰ white on a target measuring 1236px inside a 1059px
     * viewport: too tall to "fit", so the tail path was skipped and the anchor
     * was replayed instead, parking the viewport in the blank middle of one long
     * message. Not fitting is not a reason to show the middle of it — the end is
     * the part with content and the part the user is about to type after.
     *
     * The same `RenderedBottom` executor also covers a target that is not
     * measurable yet: it scrolls to the index first and bottom-aligns on the
     * following pass, so this is one behaviour rather than two.
     */
    LatestBottomAnchor
}

/**
 * Whether an over-tall or unmeasurable target may still be bottom-anchored.
 *
 * Deliberately narrow. It applies only to the canonical latest message: dragging
 * a history target to the tail would move the reader somewhere they did not ask
 * to go, so history keeps its existing behaviour. A user scroll, an interrupted
 * anchor, a superseded identity or a pending newer page all disqualify it.
 */
internal fun threadImeLatestTargetBottomAnchorEligible(
    keyboardShrinking: Boolean,
    viewportEpochCoherent: Boolean,
    targetIdentity: String?,
    targetItemIndex: Int,
    latestMessageItemIndex: Int,
    canLoadMoreAfter: Boolean,
    scrollInterruptedIdentity: String?,
    startUserScrollGeneration: Int?,
    currentUserScrollGeneration: Int,
    sessionSettledOrConsumed: Boolean,
    handoffOwed: Boolean
): Boolean =
    keyboardShrinking &&
        viewportEpochCoherent &&
        targetIdentity != null &&
        targetItemIndex >= 0 &&
        // Canonical latest only.
        targetItemIndex == latestMessageItemIndex &&
        !canLoadMoreAfter &&
        scrollInterruptedIdentity != targetIdentity &&
        startUserScrollGeneration != null &&
        startUserScrollGeneration == currentUserScrollGeneration &&
        (sessionSettledOrConsumed || handoffOwed)

/** Outcome of the later part of one composer pass, once the layout is known. */
internal data class ThreadImeCorrectionPass(
    val handoff: ThreadImeHandoffState,
    val decision: ThreadComposerLayoutBottomAnchorDecision,
    val kind: ThreadImeCorrectionKind
)

/**
 * Resolves the layout with the debt taken into account, and repays it at the
 * moment the command is issued.
 *
 * Repaying here rather than after settlement is deliberate: settlement suspends,
 * and a pass that ran in between would still see the debt outstanding and issue
 * a second command for the same shrink.
 */
internal fun threadImeCorrectionPass(
    handoff: ThreadImeHandoffState,
    targetIdentity: String?,
    layout: ThreadComposerLayoutBottomAnchorState,
    /**
     * Whether the canonical latest target may be bottom-anchored even though it
     * does not fit — see [threadImeLatestTargetBottomAnchorEligible].
     */
    latestBottomAnchorEligible: Boolean = false
): ThreadImeCorrectionPass {
    val owed = handoff.owes(targetIdentity)
    val decision =
        resolveThreadComposerLayoutBottomAnchor(
            layout.copy(
                composerHandoffOwed = owed,
                latestBottomAnchorEligible = latestBottomAnchorEligible
            )
        )
    val kind =
        when {
            // Expressed through the resolver's own decision rather than a
            // separate exit, so the eligible case runs the existing dispatch,
            // settlement and pixel probe instead of a bypass that would lose
            // exactly the evidence the next capture needs.
            decision.shouldAnchor && decision.anchorFocusedTailAtRenderedBottom &&
                latestBottomAnchorEligible -> ThreadImeCorrectionKind.LatestBottomAnchor
            decision.shouldAnchor -> ThreadImeCorrectionKind.RenderedTail
            else -> ThreadImeCorrectionKind.None
        }
    val next =
        if (owed && kind != ThreadImeCorrectionKind.None) {
            reduceThreadImeHandoff(handoff, ThreadImeHandoffEvent.CommandIssued)
        } else {
            handoff
        }
    return ThreadImeCorrectionPass(handoff = next, decision = decision, kind = kind)
}

/**
 * What the initial focus effect must do when it resumes from awaiting its focus
 * command.
 *
 * The keyboard can arrive during that await. When it has, the effect must not
 * start the watch loop — but it must still publish the session identity and mark
 * the anchor consumed *first*, because the composer's consumed-tail owner is
 * only eligible once both exist. A previous attempt returned before publishing
 * them, which left the incoming owner permanently ineligible: not three
 * competing commands but none, and a pending correction that never retires.
 *
 * Expressed as data rather than as statement order so the invariant can be
 * asserted directly — a yield that publishes nothing is unrepresentable in a
 * passing test rather than merely unlikely.
 */
internal data class ThreadFocusedAnchorSessionPlan(
    val yieldToComposer: Boolean,
    val sessionIdentityToPublish: String?,
    val consumedIdentityToPublish: String?
)

internal fun threadFocusedAnchorSessionPlan(
    handoff: ThreadImeHandoffState,
    targetIdentity: String
): ThreadFocusedAnchorSessionPlan =
    if (handoff.retired(targetIdentity)) {
        ThreadFocusedAnchorSessionPlan(
            yieldToComposer = true,
            sessionIdentityToPublish = targetIdentity,
            consumedIdentityToPublish = targetIdentity
        )
    } else {
        ThreadFocusedAnchorSessionPlan(
            yieldToComposer = false,
            sessionIdentityToPublish = null,
            consumedIdentityToPublish = null
        )
    }

/**
 * Performs the side effects a taken handoff owes, and reports whether the pass
 * ends here.
 *
 * The dispatch and the fence release live in this function rather than inline in
 * the effect, because a test that calls `RetireFocusedCorrections` itself is a
 * replica: deleting the production dispatch would leave it green. Here the test
 * and the effect run the same code, so removing the retire dispatch — or the
 * consumed write — fails the sequence tests.
 */
internal fun applyThreadImeHandoffPass(
    pass: ThreadImeHandoffPass,
    targetIdentity: String?,
    markConsumed: (String) -> Unit,
    dispatch: (LazyViewportEvent) -> Unit
): Boolean {
    when (pass.action) {
        ThreadImeHandoffPassAction.Continue -> return false
        ThreadImeHandoffPassAction.EndPass -> return true
        ThreadImeHandoffPassAction.EndPassRetainingRise -> Unit
    }
    val identity = targetIdentity ?: return true
    // Ownership is released in the same pass the debt is recorded: a fence that
    // expires one session recheck later is the window the white cycle fell into.
    markConsumed(identity)
    // Whatever the retired owners already put in flight stops being current, and
    // the controller keeps refusing to replay their intent afterwards.
    dispatch(LazyViewportEvent.RetireFocusedCorrections(ownerIdentity = identity))
    return true
}

/**
 * Applies an initial-focus plan and reports whether the effect yields.
 *
 * Same reason as above: the publications happen here, so deleting either of them
 * fails the tests that assert the composer can take over. A test that applied
 * them itself would not notice.
 */
internal fun applyThreadFocusedAnchorSessionPlan(
    plan: ThreadFocusedAnchorSessionPlan,
    publishSessionIdentity: (String) -> Unit,
    publishConsumedIdentity: (String) -> Unit
): Boolean {
    plan.sessionIdentityToPublish?.let(publishSessionIdentity)
    plan.consumedIdentityToPublish?.let(publishConsumedIdentity)
    return plan.yieldToComposer
}

/**
 * Issues the composer's correction for one shrink.
 *
 * The dispatch lives here rather than inline in the effect so the sequence
 * tests execute it instead of performing their own equivalent — deleting the
 * RenderedBottom branch turns those tests red. A test that built the Anchor
 * event itself would stay green through exactly that deletion, which is how
 * three earlier defects survived review.
 *
 * Returns the placement it issued, so the caller can record it without
 * re-deriving the branch.
 */
internal fun applyThreadImeCorrection(
    decision: ThreadComposerLayoutBottomAnchorDecision,
    targetMessageId: String?,
    targetIdentity: String?,
    tailMessageId: String?,
    renderedTailOffsetPx: Int,
    layoutGeneration: Long,
    dispatch: (LazyViewportEvent) -> Unit
): LazyViewportAnchorPlacement? {
    if (
        decision.anchorFocusedTailAtRenderedBottom &&
        targetMessageId != null &&
        targetIdentity != null &&
        tailMessageId != null
    ) {
        dispatch(
            LazyViewportEvent.Anchor(
                target = LazyViewportAnchorTarget.Message(targetMessageId),
                offset = renderedTailOffsetPx,
                layoutGeneration = layoutGeneration,
                placement = LazyViewportAnchorPlacement.RenderedBottom,
                renderedTailMessageId = tailMessageId,
                renderedTailAnchorIdentity = targetIdentity,
                fallback = LazyViewportAnchorFallback.Free,
                provenance = LazyViewportCommandProvenance.ComposerIme(ownerIdentity = targetIdentity)
            )
        )
        return LazyViewportAnchorPlacement.RenderedBottom
    }
    dispatch(LazyViewportEvent.ContentMutated(LazyViewportMutation.InsetChange))
    return null
}
