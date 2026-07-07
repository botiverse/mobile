package build.raft.app.data.realtime

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.model.Heartbeat
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse

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

    fun advanceGlobal(
        state: RealtimeCursorState,
        event: RealtimeEvent
    ): RealtimeCursorUpdate =
        updateFromEvent(state, event)

    /**
     * Returns the visible stream tail for `/messages/sync`.
     *
     * [globalAppliedSeq] is intentionally not used: a foreground channel/window
     * must repair from its own loaded tail, even when the app-scope global cursor
     * has already advanced beyond that window.
     */
    fun visibleStreamSinceSeq(
        visibleMaxSeq: Long,
        globalAppliedSeq: Long
    ): Long =
        visibleMaxSeq.coerceAtLeast(0L)

    fun shouldSyncVisibleStream(
        channelId: ChannelId?,
        visibleMaxSeq: Long,
        serverSeq: Long,
        loading: Boolean = false
    ): Boolean =
        RealtimeVisibleStreamGapPolicy.syncRequest(
            channelId = channelId,
            visibleMaxSeq = visibleMaxSeq,
            serverSeq = serverSeq,
            loading = loading
        ) != null

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

class RealtimeAppliedSeqCursorStore(
    private val store: SharedPreferenceStore
) {
    fun readAppliedLastSeq(serverId: ServerId): Long =
        store.getString(key(serverId))?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    fun saveAppliedLastSeq(serverId: ServerId, seq: Long) {
        val nextSeq = seq.coerceAtLeast(0L)
        val current = readAppliedLastSeq(serverId)
        store.putString(key(serverId), maxOf(current, nextSeq).toString())
    }

    private fun key(serverId: ServerId): String =
        "$KeyPrefix${serverId.value}"

    private companion object {
        const val KeyPrefix = "realtime.cursor.appliedLastSeq."
    }
}
