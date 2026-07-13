package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.model.RealtimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
    /** Surface a [SoleApplyCommand.RequestRebaseline] to the host (single-flight, engine-enforced). */
    private val requestRebaseline: (SyncScopeKey) -> Unit,
    laneScope: CoroutineScope,
    private val config: SoleApplyConfig = SoleApplyConfig(),
    private val logger: (String) -> Unit = {},
    // A per-lane pure core (one lane = one scope); state is keyed by channel scope id, as in the shadow lane.
    private val core: SyncCore = createSyncCore(SyncCoreConfig(domains = listOf(messagesSyncDomain()))),
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

    private sealed interface LaneMessage {
        data class Enqueued(val frame: Frame) : LaneMessage
        data class Control(val input: SoleApplyInput) : LaneMessage
    }

    // Bounded mailbox: the engine's hold buffer is the semantic bound; the channel bound only guards
    // the mailbox itself. Overflow inside the engine is apply-failure, never a legacy bypass.
    private val mailbox = Channel<LaneMessage>(capacity = config.holdQueueCapacity)

    private var state: SoleApplyScopeState = initialState

    /** Frames currently dark, keyed by seq, so a later FanOut(seq) can deliver the exact held event. */
    private val heldEvents = LinkedHashMap<Long, RealtimeEvent>()

    private val job: Job = laneScope.launch {
        for (message in mailbox) {
            when (message) {
                is LaneMessage.Enqueued -> consume(message.frame)
                is LaneMessage.Control -> dispatch(message.input)
            }
        }
    }

    /** Hand one accepted message frame to the actor. Never blocks the caller (bounded trySend). */
    fun enqueue(frame: Frame) {
        mailbox.trySend(LaneMessage.Enqueued(frame))
    }

    /**
     * Feed the resolution of a previously surfaced rebaseline request back into the engine. On success
     * the engine clears its hold window (the canonical snapshot supersedes it); reseeding the pure core
     * + persisted scope from that snapshot ([SyncCore.ingestSnapshot] / [FoldPersistFrame.rebaseline])
     * is host wiring that lands with the activation slice — stage A only proves the decision semantics.
     */
    fun rebaselineResolved(success: Boolean) {
        mailbox.trySend(
            LaneMessage.Control(
                if (success) SoleApplyInput.RebaselineSucceeded else SoleApplyInput.RebaselineFailed,
            ),
        )
    }

    /** Feed the eligibility-revoke barrier into the engine (drop to legacy only after a good rebaseline). */
    fun eligibilityRevoked() {
        mailbox.trySend(LaneMessage.Control(SoleApplyInput.EligibilityRevoked))
    }

    /** Engine state snapshot for tests/diagnostics. */
    fun currentState(): SoleApplyScopeState = state

    fun shutdown() {
        mailbox.close()
        job.cancel()
    }

    private suspend fun consume(frame: Frame) {
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

    private fun dispatch(input: SoleApplyInput) {
        val transition = reduceSoleApply(state, input, config)
        state = transition.state
        transition.commands.forEach(::execute)
        // Anything the engine no longer tracks (fanned out, or superseded — post-failure arrival /
        // rebaseline queue clear) is released from the held map so a superseded event can never
        // surface later. FanOut has already delivered its event above.
        heldEvents.keys.retainAll(state.heldSeqs.toSet())
    }

    private fun execute(command: SoleApplyCommand) {
        when (command) {
            is SoleApplyCommand.FanOut -> heldEvents.remove(command.seq)?.let(fanOut)
            is SoleApplyCommand.Hold -> Unit // dark by construction: nothing delivered until FanOut
            SoleApplyCommand.RequestRebaseline -> requestRebaseline(scopeKey)
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
            requestRebaseline: (SyncScopeKey) -> Unit,
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
