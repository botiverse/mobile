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
     * Terminal without ever being observed pending. Distinct from
     * [ConsumedOriginal] because it cannot be told apart from a command that was
     * dropped, and folding it into success is exactly the false positive this
     * enum exists to remove.
     */
    ClearedWithoutObservation,

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
