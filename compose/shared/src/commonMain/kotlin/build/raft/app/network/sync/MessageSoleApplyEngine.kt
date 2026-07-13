package build.raft.app.network.sync

/**
 * Sole-apply slice-2 stage A — the per-scope apply-mode STATE MACHINE (pure engine).
 *
 * This is the decision core of the future sole-apply message lane: when eligibility flips
 * ([CANONICAL_FACT_SOLE_APPLY_ELIGIBLE], see [soleApplyLaneEligible]), each message frame will be
 * folded + durably committed BEFORE fan-out to sinks, and this engine decides — per scope — what the
 * host actor does with every frame. In slice-2 the engine is DORMANT: no production caller drives it
 * (the eligibility gate is structurally false), it exists to be exhaustively unit-tested.
 *
 * Pure and allocation-light: no coroutines, no IO, no flags, no loggers. Same reducer style as
 * [reduceReadStateSyncState] — `reduce(state, input) -> Transition(state, commands)`. All IO leaves as
 * declarative [SoleApplyCommand]s the host actor executes (cf. [SyncRequestDescription]).
 *
 * ## Locked semantics (platform-review gates — each is a MUST)
 *
 *  - **LEGACY**: frames pass through ([SoleApplyCommand.FanOut] immediately). The engine is not really
 *    in the delivery path in this mode; it is modeled for completeness.
 *  - **ACTIVATING**: after [SoleApplyInput.ActivationRequested] (a control-frame barrier enters the
 *    queue) frames are QUEUED ([SoleApplyCommand.Hold]) until the warm start resolves.
 *    [SoleApplyInput.WarmStartSucceeded] → FOLD with the queue intact (the actor replays it in order
 *    through the commit path). [SoleApplyInput.WarmStartFailed] → back to LEGACY with the queued frames
 *    released to legacy fan-out IN ORDER — activation never happened and no fold state was ever shown,
 *    so this is the one legal legacy release — plus a diagnostic.
 *  - **FOLD**: every frame holds until ITS [SoleApplyInput.CommitSucceeded] — a [SoleApplyCommand.FanOut]
 *    is only ever emitted commit-first (the cursor-never-crosses invariant).
 *    [SoleApplyInput.CommitFailed] blocks the scope: that frame and ALL subsequent frames stay held,
 *    one [SoleApplyCommand.RequestRebaseline] is emitted (single-flight) + a diagnostic. NOTHING held
 *    is ever fanned out after a failure (no post-failure leaks).
 *  - **BROKEN_CANONICAL** (repeated commit failure, or [SoleApplyInput.RebaselineFailed]): sinks stay
 *    dark — everything holds; a new [SoleApplyCommand.RequestRebaseline] may be emitted ONLY once the
 *    previous one resolved (single-flight preserved). Frames are NEVER released to legacy fan-out from
 *    FOLD/BROKEN — that would show uncommitted facts; the reviewer explicitly forbade LEGACY-degrade as
 *    a failure fallback. Availability never justifies crossing missing facts.
 *  - **Rebaseline completion** is generation-fenced. Every [SoleApplyCommand.RequestRebaseline]
 *    carries a unique generation and only the matching [SoleApplyInput.RebaselineSucceeded] /
 *    [SoleApplyInput.RebaselineFailed] may resolve it. Duplicate or stale completions are diagnostic
 *    no-ops: they cannot unlock the scope or create a second concurrent request.
 *  - **Rebaseline success** in FOLD/BROKEN clears the hold queue — the canonical snapshot supersedes
 *    every held frame — returns to FOLD, and advances the barrier generation to the request token.
 *  - **Eligibility revoke** ([SoleApplyInput.EligibilityRevoked]) is the ONLY path back to LEGACY from
 *    FOLD/BROKEN, and it is barriered: the engine enters a revoking state ([SoleApplyScopeState.revokePending],
 *    everything holds) in which a rebaseline must SUCCEED first; only then does it emit
 *    [SoleApplyCommand.DropToLegacyAtBarrier]. If rebaselines keep failing the scope stays held (dark)
 *    with diagnostics.
 *  - **Queue overflow** (bounded [SoleApplyConfig.holdQueueCapacity]) and actor-mailbox overflow
 *    ([SoleApplyInput.MailboxOverflowed]) share the same apply-failure/rebaseline transition — NEVER a
 *    bypass to legacy. An overflowing frame is not retained (the mandatory rebaseline supersedes the
 *    whole hold window); the loss is therefore modeled as supersession, never silent disappearance.
 */

/** Per-scope apply mode. */
enum class SoleApplyMode { LEGACY, ACTIVATING, FOLD, BROKEN_CANONICAL }

/** Engine configuration — the hold-buffer bound. Overflow = apply failure (never legacy bypass). */
data class SoleApplyConfig(val holdQueueCapacity: Int = DEFAULT_HOLD_QUEUE_CAPACITY) {
    companion object {
        const val DEFAULT_HOLD_QUEUE_CAPACITY: Int = 256
    }
}

/** Everything the engine tracks for one scope. Immutable — [reduceSoleApply] returns the next value. */
data class SoleApplyScopeState(
    val mode: SoleApplyMode = SoleApplyMode.LEGACY,
    /** Hold buffer: seqs held (arrival order), each awaiting its commit or a superseding rebaseline. */
    val heldSeqs: List<Long> = emptyList(),
    /**
     * Generation token of the outstanding rebaseline request. `null` means no request is in flight.
     * The token is both the single-flight latch and the stale/duplicate completion fence.
     */
    val rebaselineGeneration: Long? = null,
    /** Apply is blocked (commit failure / overflow): everything holds until a rebaseline SUCCEEDS. */
    val applyBlocked: Boolean = false,
    /** Revoking barrier: eligibility was revoked; drop to LEGACY only after a successful rebaseline. */
    val revokePending: Boolean = false,
    /** Monotonic barrier/generation counter — bumps at every activation/rebaseline/drop barrier. */
    val generation: Long = 0L,
) {
    val rebaselineInFlight: Boolean get() = rebaselineGeneration != null
}

/** Inputs the host feeds the engine (one at a time, per scope). */
sealed interface SoleApplyInput {
    /** A message frame arrived for this scope. */
    data class FrameArrived(val seq: Long) : SoleApplyInput

    /** Control-frame barrier: begin activation (warm start) for this scope. */
    data object ActivationRequested : SoleApplyInput

    /** The warm start (restore of the persisted fold state) completed. */
    data object WarmStartSucceeded : SoleApplyInput

    /** The warm start failed — activation never happened, no fold state was shown. */
    data object WarmStartFailed : SoleApplyInput

    /** The fold+persist commit for [seq] durably succeeded (the one-txn adapter returned). */
    data class CommitSucceeded(val seq: Long) : SoleApplyInput

    /** The fold+persist commit for [seq] failed. */
    data class CommitFailed(val seq: Long) : SoleApplyInput

    /** The actor mailbox overflowed; at least one frame is now superseded by a mandatory rebaseline. */
    data object MailboxOverflowed : SoleApplyInput

    /** The matching rebaseline generation restored a fresh canonical snapshot and fresh core. */
    data class RebaselineSucceeded(val generation: Long) : SoleApplyInput

    /** The matching rebaseline generation resolved in failure (including restore/reseed failure). */
    data class RebaselineFailed(val generation: Long) : SoleApplyInput

    /** Sole-apply eligibility was revoked — begin the barriered drop back to LEGACY. */
    data object EligibilityRevoked : SoleApplyInput
}

/** Declarative commands the host actor executes — the engine never performs IO itself. */
sealed interface SoleApplyCommand {
    /** Deliver the held frame [seq] to the sinks. Emitted ONLY commit-first (or on legal legacy release). */
    data class FanOut(val seq: Long) : SoleApplyCommand

    /** Keep the frame [seq] dark (held / superseded); nothing reaches the sinks for it yet. */
    data class Hold(val seq: Long) : SoleApplyCommand

    /** Ask the host for a scope rebaseline. [generation] must be echoed by the completion. */
    data class RequestRebaseline(val generation: Long) : SoleApplyCommand

    /** The revoke barrier passed (rebaseline succeeded): tear down and hand the scope back to legacy. */
    data object DropToLegacyAtBarrier : SoleApplyCommand

    /** Bounded closed-set diagnostic (never raw payload). */
    data class Diagnostic(val reason: String) : SoleApplyCommand
}

/** One reduction step: the next state plus the commands the host must execute, in order. */
data class SoleApplyTransition(
    val state: SoleApplyScopeState,
    val commands: List<SoleApplyCommand>,
)

// Bounded closed-set diagnostic reasons.
const val SOLE_APPLY_DIAG_WARM_START_FAILED: String = "warm_start_failed_release_legacy"
const val SOLE_APPLY_DIAG_WARM_START_FAILED_BLOCKED: String = "warm_start_failed_while_blocked"
const val SOLE_APPLY_DIAG_COMMIT_FAILED: String = "commit_failed"
const val SOLE_APPLY_DIAG_REPEATED_FAILURE: String = "repeated_apply_failure_broken_canonical"
const val SOLE_APPLY_DIAG_REBASELINE_FAILED: String = "rebaseline_failed"
const val SOLE_APPLY_DIAG_QUEUE_OVERFLOW: String = "hold_queue_overflow"
const val SOLE_APPLY_DIAG_MAILBOX_OVERFLOW: String = "mailbox_overflow"
const val SOLE_APPLY_DIAG_REVOKE_PENDING: String = "eligibility_revoked_awaiting_rebaseline"
const val SOLE_APPLY_DIAG_STALE_COMMIT: String = "stale_commit_result_dropped"
const val SOLE_APPLY_DIAG_STALE_REBASELINE_COMPLETION: String = "stale_rebaseline_completion_dropped"

/**
 * The pure reducer. Total: an input that is meaningless in the current mode folds to the unchanged
 * state with no commands (never throws) — same purity contract as the [SyncDomainConfig] folds.
 */
fun reduceSoleApply(
    state: SoleApplyScopeState,
    input: SoleApplyInput,
    config: SoleApplyConfig = SoleApplyConfig(),
): SoleApplyTransition = when (input) {
    is SoleApplyInput.FrameArrived -> reduceFrameArrived(state, input.seq, config)
    SoleApplyInput.ActivationRequested -> reduceActivationRequested(state)
    SoleApplyInput.WarmStartSucceeded -> reduceWarmStartSucceeded(state)
    SoleApplyInput.WarmStartFailed -> reduceWarmStartFailed(state)
    is SoleApplyInput.CommitSucceeded -> reduceCommitSucceeded(state, input.seq)
    is SoleApplyInput.CommitFailed -> reduceCommitFailed(state)
    SoleApplyInput.MailboxOverflowed -> reduceMailboxOverflowed(state)
    is SoleApplyInput.RebaselineSucceeded -> reduceRebaselineSucceeded(state, input.generation)
    is SoleApplyInput.RebaselineFailed -> reduceRebaselineFailed(state, input.generation)
    SoleApplyInput.EligibilityRevoked -> reduceEligibilityRevoked(state)
}

private fun unchanged(state: SoleApplyScopeState) = SoleApplyTransition(state, emptyList())

private fun reduceFrameArrived(
    state: SoleApplyScopeState,
    seq: Long,
    config: SoleApplyConfig,
): SoleApplyTransition = when (state.mode) {
    SoleApplyMode.LEGACY ->
        // Pass-through: the engine is not really in the path in LEGACY; modeled for completeness.
        SoleApplyTransition(state, listOf(SoleApplyCommand.FanOut(seq)))

    SoleApplyMode.ACTIVATING, SoleApplyMode.FOLD ->
        when {
            // Blocked (post-failure/overflow) or revoking: the frame stays dark and is NOT retained —
            // the mandatory rebaseline supersedes the whole hold window, so retention buys nothing.
            state.applyBlocked || state.revokePending ->
                SoleApplyTransition(state, listOf(SoleApplyCommand.Hold(seq)))

            state.heldSeqs.size < config.holdQueueCapacity ->
                SoleApplyTransition(state.copy(heldSeqs = state.heldSeqs + seq), listOf(SoleApplyCommand.Hold(seq)))

            // Queue overflow = apply failure: hold + single-flight rebaseline + diagnostic. NEVER a
            // legacy bypass — held frames are only ever superseded (by the rebaseline), never shown.
            else -> {
                val failed = reduceApplyFailure(state, SOLE_APPLY_DIAG_QUEUE_OVERFLOW)
                SoleApplyTransition(failed.state, listOf(SoleApplyCommand.Hold(seq)) + failed.commands)
            }
        }

    SoleApplyMode.BROKEN_CANONICAL ->
        // Sinks stay dark. Not retained (superseded by the recovery rebaseline); never legacy.
        SoleApplyTransition(state, listOf(SoleApplyCommand.Hold(seq)))
}

private fun reduceActivationRequested(state: SoleApplyScopeState): SoleApplyTransition =
    when (state.mode) {
        SoleApplyMode.LEGACY ->
            SoleApplyTransition(
                state.copy(mode = SoleApplyMode.ACTIVATING, generation = state.generation + 1),
                emptyList(),
            )
        // Already activating/active/broken: activation is idempotent at the barrier, nothing to do.
        else -> unchanged(state)
    }

private fun reduceWarmStartSucceeded(state: SoleApplyScopeState): SoleApplyTransition =
    when {
        state.mode != SoleApplyMode.ACTIVATING -> unchanged(state)
        // Queue intact: the actor now replays the held frames IN ORDER through the commit path; each
        // fans out only on its own CommitSucceeded. (If blocked by an activation-window overflow, the
        // scope enters FOLD still blocked and recovers via the already-requested rebaseline.)
        else -> SoleApplyTransition(state.copy(mode = SoleApplyMode.FOLD), emptyList())
    }

private fun reduceWarmStartFailed(state: SoleApplyScopeState): SoleApplyTransition =
    when {
        state.mode != SoleApplyMode.ACTIVATING -> unchanged(state)
        // Overflow already happened during the activation window: the hold buffer is INCOMPLETE
        // (overflowed frames were superseded-not-retained), so releasing it to legacy would show a
        // gap — crossing missing facts. Stay dark; the requested rebaseline is the recovery.
        state.applyBlocked ->
            SoleApplyTransition(
                state.copy(mode = SoleApplyMode.BROKEN_CANONICAL),
                listOf(SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_WARM_START_FAILED_BLOCKED)),
            )
        // The one legal legacy release: activation never happened, no fold state was ever shown, so
        // the queued frames flow to legacy fan-out IN ORDER.
        else ->
            SoleApplyTransition(
                state.copy(
                    mode = SoleApplyMode.LEGACY,
                    heldSeqs = emptyList(),
                    revokePending = false,
                    generation = state.generation + 1,
                ),
                state.heldSeqs.map { SoleApplyCommand.FanOut(it) } +
                    SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_WARM_START_FAILED),
            )
    }

private fun reduceCommitSucceeded(state: SoleApplyScopeState, seq: Long): SoleApplyTransition =
    when {
        state.mode != SoleApplyMode.FOLD -> unchanged(state)
        // Post-failure a late success result is stale: gate 2 (no post-failure leaks) wins — the
        // rebaseline snapshot will re-cover the committed fact, nothing is lost by staying dark.
        state.applyBlocked || state.revokePending ->
            SoleApplyTransition(state, listOf(SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_STALE_COMMIT)))
        // In-order commit of the queue head: this is the ONLY place a FOLD-mode FanOut is born.
        state.heldSeqs.firstOrNull() == seq ->
            SoleApplyTransition(
                state.copy(heldSeqs = state.heldSeqs.drop(1)),
                listOf(SoleApplyCommand.FanOut(seq)),
            )
        // Out-of-order/unknown commit ack: never fan out a frame that is not the committed head.
        else -> SoleApplyTransition(state, listOf(SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_STALE_COMMIT)))
    }

private fun reduceCommitFailed(state: SoleApplyScopeState): SoleApplyTransition =
    when (state.mode) {
        SoleApplyMode.LEGACY, SoleApplyMode.ACTIVATING -> unchanged(state)
        else -> reduceApplyFailure(
            state,
            if (state.applyBlocked || state.mode == SoleApplyMode.BROKEN_CANONICAL) {
                SOLE_APPLY_DIAG_REPEATED_FAILURE
            } else {
                SOLE_APPLY_DIAG_COMMIT_FAILED
            },
        )
    }

private fun reduceMailboxOverflowed(state: SoleApplyScopeState): SoleApplyTransition =
    when (state.mode) {
        SoleApplyMode.LEGACY -> unchanged(state)
        else -> reduceApplyFailure(state, SOLE_APPLY_DIAG_MAILBOX_OVERFLOW)
    }

/** Commit failure, hold-buffer overflow, and mailbox overflow share one deterministic recovery path. */
private fun reduceApplyFailure(state: SoleApplyScopeState, diagnostic: String): SoleApplyTransition {
    val nextMode =
        if (state.applyBlocked || state.mode == SoleApplyMode.BROKEN_CANONICAL) {
            SoleApplyMode.BROKEN_CANONICAL
        } else {
            state.mode
        }
    val requestGeneration = state.rebaselineGeneration ?: (state.generation + 1L)
    val commands = buildList {
        if (!state.rebaselineInFlight) add(SoleApplyCommand.RequestRebaseline(requestGeneration))
        add(SoleApplyCommand.Diagnostic(diagnostic))
    }
    return SoleApplyTransition(
        state.copy(
            mode = nextMode,
            applyBlocked = true,
            rebaselineGeneration = requestGeneration,
        ),
        commands,
    )
}

private fun staleRebaselineCompletion(state: SoleApplyScopeState): SoleApplyTransition =
    SoleApplyTransition(
        state,
        listOf(SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_STALE_REBASELINE_COMPLETION)),
    )

private fun reduceRebaselineSucceeded(state: SoleApplyScopeState, generation: Long): SoleApplyTransition =
    when {
        state.rebaselineGeneration != generation -> staleRebaselineCompletion(state)
        state.mode == SoleApplyMode.LEGACY -> staleRebaselineCompletion(state)
        else -> when (state.mode) {
        SoleApplyMode.ACTIVATING, SoleApplyMode.FOLD, SoleApplyMode.BROKEN_CANONICAL -> {
            // The canonical snapshot supersedes every held frame: queue cleared, barrier bumps.
            val recovered = state.copy(
                heldSeqs = emptyList(),
                rebaselineGeneration = null,
                applyBlocked = false,
                generation = generation,
            )
            if (state.revokePending) {
                // Revoke barrier passed: ONLY now may the scope drop back to legacy.
                SoleApplyTransition(
                    recovered.copy(mode = SoleApplyMode.LEGACY, revokePending = false),
                    listOf(SoleApplyCommand.DropToLegacyAtBarrier),
                )
            } else {
                SoleApplyTransition(
                    recovered.copy(mode = if (state.mode == SoleApplyMode.ACTIVATING) SoleApplyMode.ACTIVATING else SoleApplyMode.FOLD),
                    emptyList(),
                )
            }
        }
        SoleApplyMode.LEGACY -> staleRebaselineCompletion(state)
        }
    }

private fun reduceRebaselineFailed(state: SoleApplyScopeState, generation: Long): SoleApplyTransition =
    when {
        state.rebaselineGeneration != generation -> staleRebaselineCompletion(state)
        state.mode == SoleApplyMode.LEGACY -> staleRebaselineCompletion(state)
        else -> {
            // The previous request RESOLVED (in failure) — single-flight allows exactly one re-emit.
            // Retry timing/backoff is the host's (§3 retry boundary); the engine only re-arms. It never
            // gives up silently: it keeps the scope dark and keeps asking (escalation via Diagnostic).
            val retryGeneration = generation + 1L
            SoleApplyTransition(
                state.copy(
                    mode = SoleApplyMode.BROKEN_CANONICAL,
                    rebaselineGeneration = retryGeneration,
                    applyBlocked = true,
                ),
                listOf(
                    SoleApplyCommand.RequestRebaseline(retryGeneration),
                    SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_REBASELINE_FAILED),
                ),
            )
        }
    }

private fun reduceEligibilityRevoked(state: SoleApplyScopeState): SoleApplyTransition =
    when (state.mode) {
        SoleApplyMode.LEGACY -> unchanged(state)
        SoleApplyMode.ACTIVATING ->
            if (state.applyBlocked) {
                // Incomplete hold window (overflow) — cannot release to legacy; barrier via rebaseline.
                SoleApplyTransition(
                    state.copy(revokePending = true),
                    listOf(SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_REVOKE_PENDING)),
                )
            } else {
                // Activation never completed and no fold state was shown: release the queue to legacy
                // in order (same legality as WarmStartFailed) and drop at the barrier immediately.
                SoleApplyTransition(
                    state.copy(
                        mode = SoleApplyMode.LEGACY,
                        heldSeqs = emptyList(),
                        revokePending = false,
                        generation = state.generation + 1,
                    ),
                    state.heldSeqs.map { SoleApplyCommand.FanOut(it) } + SoleApplyCommand.DropToLegacyAtBarrier,
                )
            }
        SoleApplyMode.FOLD, SoleApplyMode.BROKEN_CANONICAL -> {
            // The ONLY road back to LEGACY from FOLD/BROKEN: hold everything and require a SUCCESSFUL
            // rebaseline before DropToLegacyAtBarrier. Availability never justifies crossing missing
            // facts — if rebaselines keep failing the scope stays held.
            val requestGeneration = state.rebaselineGeneration ?: (state.generation + 1L)
            val commands = buildList {
                if (!state.rebaselineInFlight) add(SoleApplyCommand.RequestRebaseline(requestGeneration))
                add(SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_REVOKE_PENDING))
            }
            SoleApplyTransition(
                state.copy(
                    revokePending = true,
                    applyBlocked = true,
                    rebaselineGeneration = requestGeneration,
                ),
                commands,
            )
        }
    }
