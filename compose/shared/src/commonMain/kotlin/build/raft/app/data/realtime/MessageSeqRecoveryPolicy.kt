package build.raft.app.data.realtime

data class MessageSeqEnvelope<Event>(
    val seq: Long,
    val epoch: String = DefaultMessageSeqEpoch,
    val event: Event
)

data class MessageSeqRepairBatch<Event>(
    val events: List<MessageSeqEnvelope<Event>>,
    val currentSeq: Long,
    val hasMore: Boolean,
    val epoch: String = DefaultMessageSeqEpoch
)

data class MessageSeqRecoveryState<Event>(
    val appliedSeq: Long = 0L,
    val serverSeq: Long = 0L,
    val epoch: String = DefaultMessageSeqEpoch,
    val appliedEvents: List<Event> = emptyList(),
    val deferredFrames: List<MessageSeqEnvelope<Event>> = emptyList(),
    val recoveryIncomplete: Boolean = false
) {
    val awaitingRepair: Boolean
        get() = recoveryIncomplete || deferredFrames.isNotEmpty()
}

data class MessageSeqFoldResult<Event>(
    val state: MessageSeqRecoveryState<Event>,
    val repairRequired: Boolean = false,
    val appliedCount: Int = 0
)

const val DefaultMessageSeqEpoch: String = "default"

/**
 * Tracks the persisted message resume cursor under today's production
 * `messages.seq` semantics.
 *
 * The sequence is server-global and sparse for a given client: messages in
 * rooms the user cannot see, plus database sequence gaps, can leave permanent
 * holes in the visible stream. For that reason this policy treats `seq` as a
 * monotonic max cursor only. Apparent gaps request repair, but frames still
 * fan out immediately and repair batches may also be sparse. A dense
 * `seq == applied + 1` stop gate belongs with a future per-scope difference
 * endpoint, not this app-scope ingress fold.
 */
object MessageSeqRecoveryPolicy {
    fun <Event> initial(
        appliedSeq: Long = 0L,
        serverSeq: Long = appliedSeq,
        epoch: String = DefaultMessageSeqEpoch
    ): MessageSeqRecoveryState<Event> =
        MessageSeqRecoveryState(
            appliedSeq = appliedSeq.coerceAtLeast(0L),
            serverSeq = maxOf(serverSeq.coerceAtLeast(0L), appliedSeq.coerceAtLeast(0L)),
            epoch = epoch
        )

    fun <Event> onFrame(
        state: MessageSeqRecoveryState<Event>,
        frame: MessageSeqEnvelope<Event>
    ): MessageSeqFoldResult<Event> {
        if (frame.epoch != state.epoch) return MessageSeqFoldResult(state = state)
        val probableGap = frame.seq > state.appliedSeq + 1L
        return MessageSeqFoldResult(
            state =
                state.copy(
                    appliedSeq = maxOf(state.appliedSeq, frame.seq),
                    serverSeq = maxOf(state.serverSeq, frame.seq),
                    appliedEvents = state.appliedEvents + frame.event,
                    deferredFrames = emptyList()
                ),
            repairRequired = probableGap || state.recoveryIncomplete,
            appliedCount = 1
        )
    }

    fun <Event> onRepairBatch(
        state: MessageSeqRecoveryState<Event>,
        batch: MessageSeqRepairBatch<Event>
    ): MessageSeqFoldResult<Event> {
        if (batch.epoch != state.epoch) return MessageSeqFoldResult(state = state)
        val events =
            batch.events
                .filter { it.epoch == state.epoch }
                .sortedBy { it.seq }
        val batchMaxSeq = events.maxOfOrNull { it.seq } ?: 0L
        val nextAppliedSeq =
            maxOf(
                state.appliedSeq,
                batch.currentSeq.coerceAtLeast(0L),
                batchMaxSeq
            )
        return MessageSeqFoldResult(
            state =
                state.copy(
                    appliedSeq = nextAppliedSeq,
                    serverSeq = maxOf(state.serverSeq, nextAppliedSeq),
                    appliedEvents = state.appliedEvents + events.map { it.event },
                    deferredFrames = emptyList(),
                    recoveryIncomplete = batch.hasMore
                ),
            repairRequired = batch.hasMore,
            appliedCount = events.size
        )
    }
}
