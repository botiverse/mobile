package ai.slock.compose.data.realtime

import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
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

data class RealtimeVisibleStreamGapRequest(
    val channelId: ChannelId,
    val sinceSeq: Long,
    val serverSeq: Long
)

object RealtimeVisibleStreamGapPolicy {
    fun syncRequest(
        channelId: ChannelId?,
        visibleMaxSeq: Long,
        serverSeq: Long,
        loading: Boolean = false
    ): RealtimeVisibleStreamGapRequest? {
        if (loading) return null
        val streamId = channelId ?: return null
        val sinceSeq = visibleMaxSeq.takeIf { it > 0L } ?: return null
        if (serverSeq <= sinceSeq) return null
        return RealtimeVisibleStreamGapRequest(
            channelId = streamId,
            sinceSeq = sinceSeq,
            serverSeq = serverSeq
        )
    }
}
