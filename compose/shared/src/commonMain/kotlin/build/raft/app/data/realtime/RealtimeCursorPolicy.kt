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
    val epoch: String = DefaultMessageSeqEpoch,
) {
    val resumeLastSeq: Long
        get() = appliedLastSeq
}

data class RealtimeCursorUpdate(
    val state: RealtimeCursorState,
    val serverAhead: Boolean = false,
    val rebaselineRequired: Boolean = false,
)

object RealtimeCursorPolicy {
    fun fromAppliedSeq(
        lastSeq: Long,
        epoch: String = DefaultMessageSeqEpoch
    ): RealtimeCursorState =
        RealtimeCursorState(
            appliedLastSeq = lastSeq.coerceAtLeast(0L),
            serverMaxSeq = lastSeq.coerceAtLeast(0L),
            epoch = epoch.ifBlank { DefaultMessageSeqEpoch },
        )

    fun rebaseline(
        state: RealtimeCursorState,
        nextEpoch: String
    ): RealtimeCursorUpdate =
        RealtimeCursorUpdate(
            state = fromAppliedSeq(lastSeq = 0L, epoch = nextEpoch),
            rebaselineRequired = state.appliedLastSeq > 0L || state.serverMaxSeq > 0L || state.epoch != nextEpoch
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

    fun appliedSeqForEvent(event: RealtimeEvent): Long =
        updateAppliedSeq(0L, event)

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
    fun currentEpoch(serverId: ServerId): String =
        store.getString(epochKey(serverId))?.takeIf { it.isNotBlank() } ?: DefaultMessageSeqEpoch

    fun readAppliedLastSeq(serverId: ServerId, epoch: String = currentEpoch(serverId)): Long =
        store.getString(seqKey(serverId, epoch))?.toLongOrNull()?.coerceAtLeast(0L)
            ?: if (epoch == DefaultMessageSeqEpoch) {
                store.getString(legacySeqKey(serverId))?.toLongOrNull()?.coerceAtLeast(0L)
            } else {
                null
            }
            ?: 0L

    fun saveAppliedLastSeq(serverId: ServerId, seq: Long, epoch: String = currentEpoch(serverId)) {
        val nextSeq = seq.coerceAtLeast(0L)
        val normalizedEpoch = epoch.ifBlank { DefaultMessageSeqEpoch }
        val current = readAppliedLastSeq(serverId, normalizedEpoch)
        store.putString(epochKey(serverId), normalizedEpoch)
        store.putString(seqKey(serverId, normalizedEpoch), maxOf(current, nextSeq).toString())
        if (normalizedEpoch == DefaultMessageSeqEpoch) {
            store.putString(legacySeqKey(serverId), maxOf(current, nextSeq).toString())
        }
    }

    fun reset(serverId: ServerId): String {
        val nextEpoch = nextEpoch(serverId)
        store.putString(epochKey(serverId), nextEpoch)
        store.putString(seqKey(serverId, nextEpoch), null)
        return nextEpoch
    }

    private fun nextEpoch(serverId: ServerId): String {
        val nextGeneration = generation(serverId) + 1L
        store.putString(generationKey(serverId), nextGeneration.toString())
        // Cursor epochs scope persisted recovery watermarks only. Keep this
        // namespace separate from app-scope session epochs.
        return "cursor-generation-$nextGeneration"
    }

    private fun generation(serverId: ServerId): Long =
        store.getString(generationKey(serverId))?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    private fun seqKey(serverId: ServerId, epoch: String): String =
        "$SeqKeyPrefix${serverId.value}.${epoch.ifBlank { DefaultMessageSeqEpoch }}"

    private fun legacySeqKey(serverId: ServerId): String =
        "$SeqKeyPrefix${serverId.value}"

    private fun epochKey(serverId: ServerId): String =
        "$EpochKeyPrefix${serverId.value}"

    private fun generationKey(serverId: ServerId): String =
        "$GenerationKeyPrefix${serverId.value}"

    private companion object {
        const val SeqKeyPrefix = "realtime.cursor.appliedLastSeq."
        const val EpochKeyPrefix = "realtime.cursor.epoch."
        const val GenerationKeyPrefix = "realtime.cursor.generation."
    }
}
