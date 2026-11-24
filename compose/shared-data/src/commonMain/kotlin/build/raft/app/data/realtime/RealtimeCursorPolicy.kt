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
    /**
     * The sync horizon the next `sync:resume` sends as `sinceHorizon` ([RealtimeSyncHorizon]); null =
     * none held, the resume runs exactly as before the horizon existed. Same server and lifetime as
     * [appliedLastSeq]: dropped whenever that watermark is rebaselined or belongs to another server.
     *
     * Invariant: the horizon held must have been computed before every later read that raises
     * `lastSeq`. The starting horizon guarantees this because its ack arrives before any history
     * request is sent. Each later round uses the horizon from the `sync:resume` response, which is
     * also computed before any later history load.
     *
     * Only socket frames raise this watermark (`message:new` / `message:updated` as they are
     * delivered, and `sync:resume:response`, whose horizon comes from the same read); no HTTP history
     * load does, so a history read already in flight when a resume horizon arrives cannot raise it.
     * A `connect` keeps the horizon only for the same server and a `lastSeq` not above the watermark
     * it already holds ([RealtimeCursorPolicy.connect]).
     */
    val horizon: String? = null,
) {
    val resumeLastSeq: Long
        get() = appliedLastSeq
}

data class RealtimeCursorUpdate(
    val state: RealtimeCursorState,
    val serverAhead: Boolean = false,
    val rebaselineRequired: Boolean = false,
)

/** See [RealtimeCursorPolicy.acceptStartingHorizon]; [reason] is for logs. */
data class RealtimeStartingHorizonDecision(
    val state: RealtimeCursorState,
    val accepted: Boolean,
    val reason: String,
)

/** See [RealtimeCursorPolicy.reconnect]. */
data class RealtimeCursorReconnect(
    val state: RealtimeCursorState,
    /** > 0: send `sync:resume` from this seq once the new socket's rooms are joined. */
    val resumeLastSeq: Long,
    /** The gap cannot be replayed: Home and Activity must re-fetch once the rooms are joined. */
    val catchUpRequired: Boolean,
)

/**
 * Mobile-local frames a native realtime host fans out on the raw realtime path next to the server's
 * frames. Never sent by the server, never emitted on the socket.
 */
object RealtimeLocalEvents {
    /**
     * A socket reconnected and could not replay what it missed (`sync:resume` impossible, or its
     * answer could not be applied). Payload `{ serverId, reason }`; mapped to
     * `RealtimeRefreshRequired(Home)`, which reloads Home and Activity.
     */
    const val CatchUpRequired = "raft:realtime:catch_up_required"
}

object RealtimeCursorPolicy {
    fun fromAppliedSeq(
        lastSeq: Long,
        epoch: String = DefaultMessageSeqEpoch,
        horizon: String? = null,
    ): RealtimeCursorState =
        RealtimeCursorState(
            appliedLastSeq = lastSeq.coerceAtLeast(0L),
            serverMaxSeq = lastSeq.coerceAtLeast(0L),
            epoch = epoch.ifBlank { DefaultMessageSeqEpoch },
            horizon = horizon?.takeIf { lastSeq > 0L },
        )

    /**
     * Cursor for an explicit `connect` request. The held horizon survives only on the same server with
     * a watermark that does not move above the one it was paired with: a lower or equal `lastSeq`
     * only asks the server for more rows, while a higher one came from somewhere this horizon does not
     * cover (see [RealtimeCursorState.horizon]).
     */
    fun connect(
        previous: RealtimeCursorState,
        lastSeq: Long,
        sameServer: Boolean,
    ): RealtimeCursorState {
        val keepHorizon = sameServer && lastSeq > 0L && lastSeq <= previous.appliedLastSeq
        return fromAppliedSeq(lastSeq, horizon = previous.horizon.takeIf { keepHorizon })
    }

    /**
     * The `sync:horizon` ack of the current socket. Used only while no horizon is held and no baseline
     * history request of this server has been sent yet ([RealtimeBaselineHistoryRequests]): then the
     * server's snapshot predates every history read the client holds. Otherwise the ack is discarded and
     * the next resume runs without a horizon, as before; its response starts the next round.
     */
    fun acceptStartingHorizon(
        state: RealtimeCursorState,
        horizon: String?,
        baselineHistorySent: Boolean,
    ): RealtimeStartingHorizonDecision {
        val reason =
            when {
                horizon == null -> "no_horizon"
                state.horizon != null -> "horizon_held"
                baselineHistorySent -> "history_request_sent_first"
                else -> null
            }
        return if (reason == null) {
            RealtimeStartingHorizonDecision(state.copy(horizon = horizon), accepted = true, reason = "accepted")
        } else {
            RealtimeStartingHorizonDecision(state, accepted = false, reason = reason)
        }
    }

    fun rebaseline(
        state: RealtimeCursorState,
        nextEpoch: String
    ): RealtimeCursorUpdate =
        RealtimeCursorUpdate(
            state = fromAppliedSeq(lastSeq = 0L, epoch = nextEpoch),
            rebaselineRequired = state.appliedLastSeq > 0L || state.serverMaxSeq > 0L || state.epoch != nextEpoch
        )

    /**
     * Cursor plan for a new socket that replaces a dropped one without a new `connect` request: the
     * transport's own auto-reconnect, or the client's heartbeat-silence reconnect.
     *
     * The epoch always rotates (#255), so the logs and any epoch-scoped state see a new connection.
     * The applied watermark survives only when the seq domain is unchanged ([sameServer]): message `seq`
     * is the server database's global `bigserial` (slock `messages.seq`), it does not restart with a
     * socket, a server process or a replica, and `sync:resume` replays `seq > lastSeq` for the
     * socket's server and viewer. A watermark from another server is never resumed: it would skip
     * that server's lower seqs.
     *
     * When nothing can be resumed, [RealtimeCursorReconnect.catchUpRequired] is set: events sent while
     * the socket was down cannot be replayed, so the stores must re-fetch.
     */
    fun reconnect(
        state: RealtimeCursorState,
        nextEpoch: String,
        sameServer: Boolean,
    ): RealtimeCursorReconnect {
        val resumeLastSeq = state.appliedLastSeq.takeIf { sameServer && it > 0L } ?: 0L
        if (resumeLastSeq > 0L) {
            return RealtimeCursorReconnect(
                state = fromAppliedSeq(lastSeq = resumeLastSeq, epoch = nextEpoch, horizon = state.horizon),
                resumeLastSeq = resumeLastSeq,
                catchUpRequired = false,
            )
        }
        return RealtimeCursorReconnect(
            state = rebaseline(state, nextEpoch).state,
            resumeLastSeq = 0L,
            catchUpRequired = true,
        )
    }

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
                    // Every resume is a round's first page: its horizon starts the next round. A server
                    // without horizons answers none, and the held one (if any) stays.
                    state.updateAppliedSeq(event.currentSeq).let { next ->
                        event.horizon?.let { next.copy(horizon = it) } ?: next
                    }
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
