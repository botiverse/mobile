package ai.slock.compose.data.realtime

import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.ReadStateUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.SyncResumeResponse

data class RealtimeCursorState(
    val appliedLastSeq: Long = 0L,
    val serverMaxSeq: Long = 0L,
) {
    val resumeLastSeq: Long
        get() = appliedLastSeq
}

data class RealtimeCursorUpdate(
    val state: RealtimeCursorState,
    val serverAhead: Boolean = false,
)

object RealtimeCursorPolicy {
    fun fromAppliedSeq(lastSeq: Long): RealtimeCursorState =
        RealtimeCursorState(
            appliedLastSeq = lastSeq.coerceAtLeast(0L),
            serverMaxSeq = lastSeq.coerceAtLeast(0L),
        )

    fun updateFromEvent(
        state: RealtimeCursorState,
        event: RealtimeEvent,
    ): RealtimeCursorUpdate {
        val next =
            when (event) {
                is Heartbeat ->
                    state.copy(serverMaxSeq = maxOf(state.serverMaxSeq, event.seq))
                is MessageNew ->
                    state.updateAppliedSeq(event.message.seq)
                is MessageUpdated ->
                    state.updateAppliedSeq(event.message.seq)
                is ReadStateUpdated ->
                    state.updateAppliedSeq(event.seq)
                is SyncResumeResponse ->
                    state.updateAppliedSeq(event.currentSeq)
                else -> state
            }
        return RealtimeCursorUpdate(
            state = next,
            serverAhead = next.serverMaxSeq > next.appliedLastSeq,
        )
    }

    fun updateAppliedSeq(lastSeq: Long, event: RealtimeEvent): Long =
        updateFromEvent(fromAppliedSeq(lastSeq), event).state.appliedLastSeq

    private fun RealtimeCursorState.updateAppliedSeq(seq: Long?): RealtimeCursorState {
        val nextSeq = seq?.takeIf { it > 0L } ?: return this
        val applied = maxOf(appliedLastSeq, nextSeq)
        return copy(
            appliedLastSeq = applied,
            serverMaxSeq = maxOf(serverMaxSeq, applied),
        )
    }
}
