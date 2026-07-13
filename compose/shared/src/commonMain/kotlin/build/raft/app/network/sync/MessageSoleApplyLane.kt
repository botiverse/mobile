package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.model.RealtimeEvent
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Sole-apply slice-2 stage A — the per-scope APPLY ACTOR wrapping [reduceSoleApply]. DORMANT in slice-2:
 * production never constructs a lane (see [soleApplyLaneEligible] — structurally false while
 * [CANONICAL_FACT_SOLE_APPLY_ELIGIBLE] is false), it is built + exercised only from tests. The legacy
 * accept path and the shadow lane ([SyncCoreMessageShadowFoldLane]) stay byte-identical.
 *
 * When eligibility flips (V2 normalized envelope), a lane sits per scope BEFORE the sink fan-out in
 * [build.raft.app.data.SharedRealtimeDomainEventOwner.accept]: each message frame is folded through the
 * pure [SyncCore] messages domain and durably committed via the one-txn [FoldPersistenceAdapter.applyFrame],
 * and only THEN fanned out ([fanOut]) — commit-first, the cursor never crosses an unpersisted fact.
 *
 * The actor is deliberately THIN: every decision (hold vs fan-out, single-flight rebaseline, blocked
 * scope, revoke barrier) lives in the pure engine; the actor just feeds it inputs and executes the
 * returned [SoleApplyCommand]s. Rebaseline requests surface via [requestRebaseline] (future: a
 * scope-qualified RealtimeRefreshRequired); their resolution is fed back through [rebaselineResolved].
 */
internal class MessageSoleApplyLane(
    private val scopeKey: SyncScopeKey,
    private val adapter: FoldPersistenceAdapter,
    /** Post-commit delivery to the legacy sinks (executed for [SoleApplyCommand.FanOut] only). */
    private val fanOut: (RealtimeEvent) -> Unit,
    /** Surface a generation-fenced [SoleApplyCommand.RequestRebaseline] to the host. */
    private val requestRebaseline: (SyncScopeKey, Long) -> Unit,
    laneScope: CoroutineScope,
    private val config: SoleApplyConfig = SoleApplyConfig(),
    private val logger: (String) -> Unit = {},
    /** Deterministic androidUnitTest seam; production uses the no-op default. */
    private val beforeFrameOffer: () -> Unit = {},
    // A per-lane pure core (one lane = one scope); state is keyed by channel scope id, as in the shadow lane.
    initialCore: SyncCore = createMessageSyncCore(),
    // Stage A starts the engine directly in FOLD (a warm-started lane); the ACTIVATING warm-start
    // orchestration (restore + queued replay) is host wiring that lands with the activation slice.
    initialState: SoleApplyScopeState = SoleApplyScopeState(mode = SoleApplyMode.FOLD, generation = 1L),
) {

    /** One enqueued message frame: the sink-facing event plus its canonical decode (decoded ONCE upstream). */
    data class Frame(
        val event: RealtimeEvent,
        val decoded: CanonicalMessageCarry,
        val updated: Boolean,
        val epoch: SyncEpoch?,
    )

    /** Frame plus the actor barrier generation observed at enqueue time. */
    private data class QueuedFrame(val frame: Frame, val generation: Long)

    private sealed interface LaneControl {
        data class MailboxOverflowed(val observedGeneration: Long?) : LaneControl
        data class RebaselineResolved(val generation: Long, val success: Boolean) : LaneControl
        data object EligibilityRevoked : LaneControl
    }

    // Frames are bounded; an overflowed frame is synchronously converted into one coalesced CONTROL
    // signal and then superseded by the mandatory rebaseline. Controls use a separate non-dropping
    // channel: protocol cardinality is bounded (one overflow signal, one rebaseline completion, one
    // revoke intent) and duplicate/stale completions are fenced by the engine generation.
    private val frames = Channel<QueuedFrame>(capacity = config.holdQueueCapacity)
    private val controls = Channel<LaneControl>(capacity = Channel.UNLIMITED)
    private val mailboxOverflowSignalPending = atomic(false)
    private val mailboxGeneration = atomic(initialState.generation)
    private val rebaselineBarrierLock = SynchronizedObject()
    private var enqueueVisibleRebaselineGeneration: Long? = initialState.rebaselineGeneration
    private var dirtyEnqueueGeneration: Long? = null

    private var state: SoleApplyScopeState = initialState
    private var core: SyncCore = initialCore

    /** Frames currently dark, keyed by seq, so a later FanOut(seq) can deliver the exact held event. */
    private val heldEvents = LinkedHashMap<Long, RealtimeEvent>()

    private val job: Job = laneScope.launch {
        while (true) {
            // Controls win at every message boundary. In particular, an overflow observed while a
            // commit was suspended is reduced before the next queued frame can attempt another fold.
            val immediateControl = controls.tryReceive().getOrNull()
            if (immediateControl != null) {
                consumeControl(immediateControl)
                continue
            }
            val next = select<Any?> {
                controls.onReceiveCatching { it.getOrNull() }
                frames.onReceiveCatching { it.getOrNull() }
            } ?: break
            when (next) {
                is LaneControl -> consumeControl(next)
                is QueuedFrame -> consume(next)
            }
        }
    }

    /**
     * Hand one accepted message frame to the actor. Never blocks the caller. A full frame mailbox is
     * not a silent drop: the frame is declared superseded by a coalesced [SoleApplyInput.MailboxOverflowed]
     * transition, which blocks apply and requests the same generation-fenced rebaseline as a commit failure.
     */
    fun enqueue(frame: Frame) {
        beforeFrameOffer()
        val (offered, observedGeneration) = synchronized(rebaselineBarrierLock) {
            val activeGeneration = enqueueVisibleRebaselineGeneration
            activeGeneration?.let { dirtyEnqueueGeneration = it }
            val queued = QueuedFrame(frame, mailboxGeneration.value)
            // Sampling the active flight, tagging the queued frame, and the actual non-blocking offer
            // are one linearization point. Recovery cannot start in a sample->offer gap.
            frames.trySend(queued) to activeGeneration
        }
        if (offered.isFailure && job.isActive && mailboxOverflowSignalPending.compareAndSet(expect = false, update = true)) {
            if (controls.trySend(LaneControl.MailboxOverflowed(observedGeneration)).isFailure) {
                mailboxOverflowSignalPending.value = false
                logger("sole_apply_diag scope=${scopeKey.scopeId} reason=mailbox_closed")
            }
        } else if (offered.isFailure && !job.isActive) {
            logger("sole_apply_diag scope=${scopeKey.scopeId} reason=mailbox_closed")
        }
    }

    /**
     * Feed the resolution of a previously surfaced rebaseline request back into the actor. The host
     * must echo the request [generation]. A success is not delivered to the engine until the actor has
     * restored durable canonical facts, seeded a FRESH core, and atomically replaced the polluted core
     * reference. Restore/reseed failure is reduced as [SoleApplyInput.RebaselineFailed], keeping the
     * scope dark and rearming exactly one new generation.
     */
    fun rebaselineResolved(generation: Long, success: Boolean) {
        sendControl(LaneControl.RebaselineResolved(generation, success))
    }

    /** Feed the eligibility-revoke barrier into the engine (drop to legacy only after a good rebaseline). */
    fun eligibilityRevoked() {
        sendControl(LaneControl.EligibilityRevoked)
    }

    /** Engine state snapshot for tests/diagnostics. */
    fun currentState(): SoleApplyScopeState = state

    fun shutdown() {
        controls.close()
        frames.close()
        job.cancel()
    }

    private fun sendControl(control: LaneControl) {
        if (controls.trySend(control).isFailure) {
            logger("sole_apply_diag scope=${scopeKey.scopeId} reason=control_mailbox_closed")
        }
    }

    private suspend fun consumeControl(control: LaneControl) {
        when (control) {
            is LaneControl.MailboxOverflowed -> {
                mailboxOverflowSignalPending.value = false
                val currentGeneration = state.rebaselineGeneration
                when {
                    // Overflow created before any recovery flight: if no other flight has started,
                    // this signal owns the transition that blocks and requests one.
                    control.observedGeneration == null && currentGeneration == null ->
                        dispatch(SoleApplyInput.MailboxOverflowed)
                    // Overflow happened inside this exact flight: attribute it once to that flight.
                    control.observedGeneration != null && control.observedGeneration == currentGeneration ->
                        dispatch(SoleApplyInput.MailboxOverflowed)
                    // The loss was already covered by/rearmed from an older generation. Never apply
                    // the delayed control to the new flight and manufacture an unnecessary g+1.
                    else -> logger(
                        "sole_apply_diag scope=${scopeKey.scopeId} reason=stale_mailbox_overflow_control",
                    )
                }
            }
            is LaneControl.RebaselineResolved -> resolveRebaseline(control)
            LaneControl.EligibilityRevoked -> dispatch(SoleApplyInput.EligibilityRevoked)
        }
    }

    private suspend fun resolveRebaseline(control: LaneControl.RebaselineResolved) {
        // Avoid storage IO for a duplicate/stale completion. The reducer remains the authority and
        // emits the bounded stale-completion diagnostic; this pre-check only prevents a stale callback
        // from restoring/replacing core state before that deterministic decision.
        if (state.rebaselineGeneration != control.generation) {
            dispatch(
                if (control.success) SoleApplyInput.RebaselineSucceeded(control.generation)
                else SoleApplyInput.RebaselineFailed(control.generation),
            )
            return
        }
        if (!control.success) {
            finishFailedRebaseline(control.generation)
            return
        }

        val restoredCore = runCatching { restoreFreshCore() }
        if (restoredCore.isSuccess) {
            finishSuccessfulRebaseline(control.generation, restoredCore.getOrThrow())
        } else {
            logger("sole_apply_diag scope=${scopeKey.scopeId} reason=rebaseline_restore_failed")
            finishFailedRebaseline(control.generation)
        }
    }

    private fun finishSuccessfulRebaseline(generation: Long, restoredCore: SyncCore) {
        val commands = synchronized(rebaselineBarrierLock) {
            // Close the snapshot-cut race with enqueue: every enqueue that observed this flight marks
            // it dirty under the same lock. A dirty completion installs the restored core as a safe
            // baseline but the engine immediately rearms the next generation instead of unlocking.
            if (dirtyEnqueueGeneration == generation) {
                reduceOnly(SoleApplyInput.RebaselineDirtied(generation))
                dirtyEnqueueGeneration = null
            }
            core = restoredCore
            mailboxGeneration.value = generation
            val next = reduceOnly(SoleApplyInput.RebaselineSucceeded(generation))
            enqueueVisibleRebaselineGeneration = state.rebaselineGeneration
            next
        }
        executeCommands(commands)
    }

    private fun finishFailedRebaseline(generation: Long) {
        val commands = synchronized(rebaselineBarrierLock) {
            // The retry request is issued after this failed flight, so all arrivals observed during it
            // belong before the new snapshot cut and do not dirty the retry generation by themselves.
            if (dirtyEnqueueGeneration == generation) dirtyEnqueueGeneration = null
            val next = reduceOnly(SoleApplyInput.RebaselineFailed(generation))
            enqueueVisibleRebaselineGeneration = state.rebaselineGeneration
            next
        }
        executeCommands(commands)
    }

    /** Build and fully seed a fresh core before publishing it; the old polluted core has no reuse path. */
    private suspend fun restoreFreshCore(): SyncCore {
        val restored = adapter.restore(scopeKey)
        val cursor = requireNotNull(restored.cursor) { "rebaseline restore missing cursor" }
        require(restored.facts.all { it.channelId == scopeKey.scopeId }) {
            "rebaseline restore contained a fact outside scope ${scopeKey.scopeId}"
        }
        val orderedFacts = restored.facts.sortedWith(compareBy({ it.seq }, { it.id }))
        val maxFactSeq = orderedFacts.lastOrNull()?.seq
        val watermark = cursor.latestSeq ?: cursor.newestLoadedSeq ?: maxFactSeq ?: 0L
        require(maxFactSeq == null || watermark >= maxFactSeq) {
            "rebaseline cursor $watermark precedes restored fact $maxFactSeq"
        }
        val freshCore = createMessageSyncCore()
        val outcome = freshCore.ingestSnapshot(
            MESSAGES_SYNC_DOMAIN,
            SyncSnapshot(
                scopeId = scopeKey.scopeId,
                watermark = watermark,
                epoch = cursor.epoch,
                state = MessageDomainState(channelMessages = mapOf(scopeKey.scopeId to orderedFacts)),
            ),
        )
        check(outcome is SyncIngestOutcome.Applied) { "fresh-core rebaseline was not applied: $outcome" }
        return freshCore
    }

    private suspend fun consume(queued: QueuedFrame) {
        if (queued.generation != state.generation) {
            logger("sole_apply_diag scope=${scopeKey.scopeId} reason=stale_mailbox_frame_superseded")
            return
        }
        val frame = queued.frame
        val seq = frame.decoded.row.seq
        heldEvents[seq] = frame.event
        dispatch(SoleApplyInput.FrameArrived(seq))
        // Commit only when the engine actually retained the frame at the head of an unblocked FOLD
        // queue — the actor is sequential, so the head is always the frame just consumed.
        val shouldCommit = state.mode == SoleApplyMode.FOLD &&
            !state.applyBlocked && !state.revokePending &&
            state.heldSeqs.firstOrNull() == seq
        if (shouldCommit) {
            val committed = runCatching { foldAndCommit(frame, seq) }
            dispatch(
                if (committed.isSuccess) SoleApplyInput.CommitSucceeded(seq)
                else SoleApplyInput.CommitFailed(seq),
            )
        }
    }

    /** Fold via the pure core, then durably commit rows+cursor in the adapter's single transaction. */
    private suspend fun foldAndCommit(frame: Frame, seq: Long) {
        val row = frame.decoded.row
        val channelId = row.channelId
        val domainEvent: MessageDomainEvent =
            if (frame.updated) MessageDomainEvent.Updated(row, frame.decoded.presentAggregates)
            else MessageDomainEvent.New(row, frame.decoded.presentAggregates)
        core.ingestFrame(
            MESSAGES_SYNC_DOMAIN,
            SyncFrame(scopeId = channelId, seq = seq, epoch = frame.epoch, event = domainEvent),
        )
        val facts = (core.state(MESSAGES_SYNC_DOMAIN, channelId) as? MessageDomainState)
            ?.channelMessages
            ?.get(channelId)
            .orEmpty()
        adapter.applyFrame(
            FoldPersistFrame(
                scope = scopeKey,
                facts = facts,
                cursor = PersistedCursor(
                    latestSeq = seq,
                    oldestLoadedSeq = facts.firstOrNull()?.seq,
                    newestLoadedSeq = facts.lastOrNull()?.seq ?: seq,
                    hasLoadedOldest = false,
                    hasLoadedNewest = true,
                    epoch = frame.epoch,
                ),
                rebaseline = false,
                prunedFactIds = emptyList(),
            ),
        )
    }

    private fun reduceOnly(input: SoleApplyInput): List<SoleApplyCommand> {
        val transition = reduceSoleApply(state, input, config)
        state = transition.state
        return transition.commands
    }

    private fun dispatch(input: SoleApplyInput) {
        val commands = synchronized(rebaselineBarrierLock) {
            val next = reduceOnly(input)
            enqueueVisibleRebaselineGeneration = state.rebaselineGeneration
            next
        }
        executeCommands(commands)
    }

    private fun executeCommands(commands: List<SoleApplyCommand>) {
        commands.forEach(::execute)
        // Anything the engine no longer tracks (fanned out, or superseded by a recovery barrier) is
        // released only AFTER FanOut commands had a chance to deliver the exact held event.
        heldEvents.keys.retainAll(state.heldSeqs.toSet())
    }

    private fun execute(command: SoleApplyCommand) {
        when (command) {
            is SoleApplyCommand.FanOut -> heldEvents.remove(command.seq)?.let(fanOut)
            is SoleApplyCommand.Hold -> Unit // dark by construction: nothing delivered until FanOut
            is SoleApplyCommand.RequestRebaseline -> requestRebaseline(scopeKey, command.generation)
            SoleApplyCommand.DropToLegacyAtBarrier ->
                logger("sole_apply_drop_to_legacy scope=${scopeKey.scopeId} generation=${state.generation}")
            is SoleApplyCommand.Diagnostic ->
                logger("sole_apply_diag scope=${scopeKey.scopeId} reason=${command.reason}")
        }
    }

    companion object {
        /**
         * The ONLY production construction path. Returns null — no lane, no actor, no channel — unless
         * [soleApplyLaneEligible] passes; while [CANONICAL_FACT_SOLE_APPLY_ELIGIBLE] is `false` that is
         * structurally always, so slice-2 stage A cannot activate in production. Tests construct the
         * lane directly to exercise the dormant machinery.
         */
        fun createIfEligible(
            perScopeControl: Boolean,
            scopeKey: SyncScopeKey,
            adapter: FoldPersistenceAdapter,
            fanOut: (RealtimeEvent) -> Unit,
            requestRebaseline: (SyncScopeKey, Long) -> Unit,
            laneScope: CoroutineScope,
            config: SoleApplyConfig = SoleApplyConfig(),
            logger: (String) -> Unit = {},
        ): MessageSoleApplyLane? {
            if (!soleApplyLaneEligible(perScopeControl)) return null
            return MessageSoleApplyLane(
                scopeKey = scopeKey,
                adapter = adapter,
                fanOut = fanOut,
                requestRebaseline = requestRebaseline,
                laneScope = laneScope,
                config = config,
                logger = logger,
            )
        }
    }
}

/**
 * The mechanical V1 sole-apply protection (the canonical fact codec's KDoc contract made code): ANY future
 * sole-apply switch MUST require [CANONICAL_FACT_SOLE_APPLY_ELIGIBLE]. While the codec const is `false`
 * (V1 wire-faithful shadow-only envelope) this returns false for EVERY input, so nothing can create a
 * [MessageSoleApplyLane] — dormancy is structural, not conventional. [perScopeControl] is the future
 * per-scope rollout control ANDed on top; it can never widen the gate.
 */
internal fun soleApplyLaneEligible(perScopeControl: Boolean): Boolean =
    CANONICAL_FACT_SOLE_APPLY_ELIGIBLE && perScopeControl

private fun createMessageSyncCore(): SyncCore =
    createSyncCore(SyncCoreConfig(domains = listOf(messagesSyncDomain())))
