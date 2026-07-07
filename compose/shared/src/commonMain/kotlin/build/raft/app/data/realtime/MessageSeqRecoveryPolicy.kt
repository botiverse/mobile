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
        if (frame.seq <= state.appliedSeq) return MessageSeqFoldResult(state = state)
        if (state.awaitingRepair || frame.seq > state.appliedSeq + 1L) {
            val deferred =
                (state.deferredFrames + frame)
                    .distinctBy { it.epoch to it.seq }
                    .sortedBy { it.seq }
            return MessageSeqFoldResult(
                state = state.copy(
                    serverSeq = maxOf(state.serverSeq, frame.seq),
                    deferredFrames = deferred
                ),
                repairRequired = true
            )
        }
        return MessageSeqFoldResult(
            state = applySequential(state, frame),
            appliedCount = 1
        )
    }

    fun <Event> onRepairBatch(
        state: MessageSeqRecoveryState<Event>,
        batch: MessageSeqRepairBatch<Event>
    ): MessageSeqFoldResult<Event> {
        if (batch.epoch != state.epoch) return MessageSeqFoldResult(state = state)
        val afterBatch =
            batch.events
                .filter { it.epoch == state.epoch }
                .sortedBy { it.seq }
                .fold(state.copy(serverSeq = maxOf(state.serverSeq, batch.currentSeq.coerceAtLeast(0L)))) { current, frame ->
                    if (frame.seq == current.appliedSeq + 1L) {
                        applySequential(current, frame)
                    } else {
                        current
                    }
                }
                .copy(recoveryIncomplete = batch.hasMore)
        val restitched =
            restitchDeferred(afterBatch)
        return MessageSeqFoldResult(
            state = restitched.state,
            repairRequired = restitched.state.awaitingRepair,
            appliedCount = restitched.appliedCount
        )
    }

    private fun <Event> restitchDeferred(
        state: MessageSeqRecoveryState<Event>
    ): MessageSeqFoldResult<Event> {
        var current = state
        var appliedCount = 0
        val remaining = mutableListOf<MessageSeqEnvelope<Event>>()
        state.deferredFrames.sortedBy { it.seq }.forEach { frame ->
            when {
                frame.epoch != current.epoch -> Unit
                frame.seq <= current.appliedSeq -> Unit
                frame.seq == current.appliedSeq + 1L && !current.recoveryIncomplete -> {
                    current = applySequential(current, frame)
                    appliedCount += 1
                }
                else -> remaining += frame
            }
        }
        return MessageSeqFoldResult(
            state = current.copy(deferredFrames = remaining),
            repairRequired = current.recoveryIncomplete || remaining.isNotEmpty(),
            appliedCount = appliedCount
        )
    }

    private fun <Event> applySequential(
        state: MessageSeqRecoveryState<Event>,
        frame: MessageSeqEnvelope<Event>
    ): MessageSeqRecoveryState<Event> =
        state.copy(
            appliedSeq = frame.seq,
            serverSeq = maxOf(state.serverSeq, frame.seq),
            appliedEvents = state.appliedEvents + frame.event
        )
}
