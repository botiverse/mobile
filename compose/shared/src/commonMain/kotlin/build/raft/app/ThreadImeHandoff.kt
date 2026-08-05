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
    val userScrollGeneration: Int? = null
) {
    fun owes(targetIdentity: String?): Boolean =
        phase == ThreadImeHandoffPhase.Owed && identity != null && identity == targetIdentity
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
                event.targetIdentity == null -> ThreadImeHandoffState()
                !event.focusedTransactionLive -> ThreadImeHandoffState()
                // Nothing can repay it, so nothing may take it on.
                !event.dischargeable -> ThreadImeHandoffState()
                else ->
                    ThreadImeHandoffState(
                        phase = ThreadImeHandoffPhase.Owed,
                        identity = event.targetIdentity,
                        userScrollGeneration = event.userScrollGeneration
                    )
            }

        ThreadImeHandoffEvent.CommandIssued -> ThreadImeHandoffState()

        ThreadImeHandoffEvent.KeyboardClosed -> ThreadImeHandoffState()

        ThreadImeHandoffEvent.PageCycled -> ThreadImeHandoffState()

        is ThreadImeHandoffEvent.TargetChanged ->
            if (state.owes(event.targetIdentity)) state else ThreadImeHandoffState()

        is ThreadImeHandoffEvent.UserScrolled ->
            // The user moving the list outranks a correction taken on for them.
            if (state.userScrollGeneration == event.userScrollGeneration) {
                state
            } else {
                ThreadImeHandoffState()
            }
    }

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
internal data class ThreadImeHandoffPass(
    val handoff: ThreadImeHandoffState,
    /**
     * True on the pass that took the shrink. The caller releases the focused
     * fence and ends the pass there, so the layout decision runs once, in the
     * replacement pass, rather than once in a body about to be cancelled and
     * again in its successor — two commands for one shrink.
     */
    val tookHandoff: Boolean
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
    pageCycleActive: Boolean
): ThreadImeHandoffPass {
    if (pageCycleActive) {
        return ThreadImeHandoffPass(
            handoff = reduceThreadImeHandoff(handoff, ThreadImeHandoffEvent.PageCycled),
            tookHandoff = false
        )
    }
    var next = reduceThreadImeHandoff(handoff, ThreadImeHandoffEvent.TargetChanged(targetIdentity))
    next = reduceThreadImeHandoff(next, ThreadImeHandoffEvent.UserScrolled(userScrollGeneration))
    if (keyboardInsetDp <= 0f) {
        next = reduceThreadImeHandoff(next, ThreadImeHandoffEvent.KeyboardClosed)
    }
    if (keyboardInsetDp <= previousKeyboardInsetDp) {
        return ThreadImeHandoffPass(handoff = next, tookHandoff = false)
    }
    val before = next
    next =
        reduceThreadImeHandoff(
            next,
            ThreadImeHandoffEvent.ImeOnset(
                targetIdentity = targetIdentity,
                focusedTransactionLive = focusedTransactionLive,
                dischargeable = dischargeable,
                userScrollGeneration = userScrollGeneration
            )
        )
    return ThreadImeHandoffPass(
        handoff = next,
        tookHandoff = next.owes(targetIdentity) && !before.owes(targetIdentity)
    )
}

/** Outcome of the later part of one composer pass, once the layout is known. */
internal data class ThreadImeCorrectionPass(
    val handoff: ThreadImeHandoffState,
    val decision: ThreadComposerLayoutBottomAnchorDecision
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
    layout: ThreadComposerLayoutBottomAnchorState
): ThreadImeCorrectionPass {
    val owed = handoff.owes(targetIdentity)
    val decision =
        resolveThreadComposerLayoutBottomAnchor(layout.copy(composerHandoffOwed = owed))
    val next =
        if (owed && decision.shouldAnchor) {
            reduceThreadImeHandoff(handoff, ThreadImeHandoffEvent.CommandIssued)
        } else {
            handoff
        }
    return ThreadImeCorrectionPass(handoff = next, decision = decision)
}
