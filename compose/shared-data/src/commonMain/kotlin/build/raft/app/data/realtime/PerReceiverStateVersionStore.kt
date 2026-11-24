package build.raft.app.data.realtime

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.network.sync.ReadStateSyncApplyResult
import build.raft.app.network.sync.ReadStateSyncEvent
import build.raft.app.network.sync.ReadStateSyncState
import build.raft.app.network.sync.reduceReadStateSyncState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

enum class PerReceiverStateScope(val wireName: String) {
    ReadState("read_state"),
    NotificationPrefs("notification_prefs"),
    ServerOrder("server_order"),
    Pinned("pinned")
}

data class PerReceiverStateVersionDecision(
    val shouldApply: Boolean,
    val currentVersion: Long,
    val incomingVersion: Long
)

data class PerReceiverReadStateCheckpoint(
    val version: Long = 0L,
    val maxReadSeq: Long = 0L
)

enum class PerReceiverReadStateFactResult {
    Accepted,
    Current,
    Stale
}

/**
 * Version-gate for RFC 041 per-receiver state pushes.
 *
 * Granularity follows the server's version columns (PR #4175):
 * `read_state` and `notification_prefs` versions live on the per-(user,
 * channel) cursor row (`user_channel_read_cursors` PK (user_id, channel_id)),
 * so callers MUST pass the [scopeId] those versions are counted against.
 * `server_order` and `pinned` are one blob per (server, member), so they
 * gate at family level with no [scopeId].
 */
class PerReceiverStateVersionStore(
    private val store: SharedPreferenceStore
) {
    fun readVersion(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        scopeId: ChannelId? = null
    ): Long =
        store.getString(key(serverId, memberId, scope, scopeId))
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L

    fun decide(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        incomingVersion: Long,
        scopeId: ChannelId? = null
    ): PerReceiverStateVersionDecision {
        val currentVersion = readVersion(serverId, memberId, scope, scopeId)
        return PerReceiverStateVersionDecision(
            shouldApply = incomingVersion > currentVersion,
            currentVersion = currentVersion,
            incomingVersion = incomingVersion
        )
    }

    fun recordApplied(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        incomingVersion: Long,
        scopeId: ChannelId? = null
    ): PerReceiverStateVersionDecision {
        val decision = decide(serverId, memberId, scope, incomingVersion, scopeId)
        if (decision.shouldApply) {
            store.putString(key(serverId, memberId, scope, scopeId), incomingVersion.toString())
        }
        return decision
    }

    /** Persist a version that the domain reducer has already accepted. */
    @RaftCoreInternalApi fun writeAcceptedVersion(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        acceptedVersion: Long,
        scopeId: ChannelId? = null
    ) {
        store.putString(key(serverId, memberId, scope, scopeId), acceptedVersion.toString())
    }

    @RaftCoreInternalApi fun readReadStateCheckpoint(
        serverId: ServerId,
        memberId: MemberId,
        scopeId: ChannelId
    ): PerReceiverReadStateCheckpoint =
        PerReceiverReadStateCheckpoint(
            version = readVersion(serverId, memberId, PerReceiverStateScope.ReadState, scopeId),
            maxReadSeq =
                store.getString(readStateMaxSeqKey(serverId, memberId, scopeId))
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
        )

    @RaftCoreInternalApi fun writeReadStateCheckpoint(
        serverId: ServerId,
        memberId: MemberId,
        scopeId: ChannelId,
        checkpoint: PerReceiverReadStateCheckpoint
    ) {
        writeAcceptedVersion(
            serverId = serverId,
            memberId = memberId,
            scope = PerReceiverStateScope.ReadState,
            acceptedVersion = checkpoint.version,
            scopeId = scopeId
        )
        store.putString(
            readStateMaxSeqKey(serverId, memberId, scopeId),
            checkpoint.maxReadSeq.coerceAtLeast(0L).toString()
        )
        ReadStateAcceptances.note(key(serverId, memberId, PerReceiverStateScope.ReadState, scopeId))
    }

    /** A mark of the read-state versions accepted so far, by any instance (Home's, Activity's), for [readStateAcceptedSince]. */
    @RaftCoreInternalApi fun readStateAcceptMark(): Long = ReadStateAcceptances.mark()

    /**
     * Some instance accepted a newer read-state version for the scope after [mark] (web
     * `hasAcceptedReadStateChangedAfter`): an answer requested before then may predate it.
     */
    @RaftCoreInternalApi fun readStateAcceptedSince(serverId: ServerId, memberId: MemberId, scopeId: ChannelId, mark: Long): Boolean =
        ReadStateAcceptances.since(key(serverId, memberId, PerReceiverStateScope.ReadState, scopeId), mark)

    /**
     * Web `consumeReadStateSnapshot` on an `absent` frontier: the server holds no read state for the scope (its
     * cursor row deleted, e.g. access lost), so the next one, from v1 again, must not be judged against the
     * version held. Returns whether a checkpoint was held.
     */
    @RaftCoreInternalApi fun clearReadStateCheckpoint(
        serverId: ServerId,
        memberId: MemberId,
        scopeId: ChannelId
    ): Boolean {
        if (readVersion(serverId, memberId, PerReceiverStateScope.ReadState, scopeId) <= 0L) return false
        store.putString(key(serverId, memberId, PerReceiverStateScope.ReadState, scopeId), null)
        store.putString(readStateMaxSeqKey(serverId, memberId, scopeId), null)
        return true
    }

    /**
     * Applies a read-state fact through the one persisted version authority shared by projections.
     * A second projection may observe [PerReceiverReadStateFactResult.Current] after the first one
     * accepted the same fact; equal-version facts with a different cursor fail closed as stale.
     */
    @RaftCoreInternalApi fun applyOrObserveReadStateFact(
        serverId: ServerId,
        memberId: MemberId,
        scopeId: ChannelId,
        incomingVersion: Long,
        incomingMaxReadSeq: Long
    ): PerReceiverReadStateFactResult {
        if (incomingVersion <= 0L || incomingMaxReadSeq < 0L) {
            return PerReceiverReadStateFactResult.Stale
        }
        val checkpoint = readReadStateCheckpoint(serverId, memberId, scopeId)
        val transition =
            reduceReadStateSyncState(
                current =
                    ReadStateSyncState(
                        readStateVersion = checkpoint.version,
                        maxReadSeq = checkpoint.maxReadSeq
                    ),
                event =
                    ReadStateSyncEvent(
                        readStateVersion = incomingVersion,
                        maxReadSeq = incomingMaxReadSeq
                    )
            )
        if (transition.result == ReadStateSyncApplyResult.Accepted) {
            writeReadStateCheckpoint(
                serverId = serverId,
                memberId = memberId,
                scopeId = scopeId,
                checkpoint =
                    PerReceiverReadStateCheckpoint(
                        version = transition.state.readStateVersion,
                        maxReadSeq = transition.state.maxReadSeq
                    )
            )
            return PerReceiverReadStateFactResult.Accepted
        }
        return if (
            checkpoint.version == incomingVersion &&
                checkpoint.maxReadSeq == incomingMaxReadSeq
        ) {
            PerReceiverReadStateFactResult.Current
        } else {
            PerReceiverReadStateFactResult.Stale
        }
    }

    private fun readStateMaxSeqKey(serverId: ServerId, memberId: MemberId, scopeId: ChannelId): String =
        key(serverId, memberId, PerReceiverStateScope.ReadState, scopeId) + ".maxReadSeq"

    private fun key(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        scopeId: ChannelId?
    ): String =
        "realtime.perReceiverStateVersion." +
            "${keySegment(serverId.value)}." +
            "${keySegment(memberId.value)}." +
            scope.wireName +
            (scopeId?.let { ".${keySegment(it.value)}" } ?: "")

    private fun keySegment(value: String): String =
        value
            .replace("%", "%25")
            .replace(".", "%2E")
}

/**
 * When each read-state checkpoint last accepted a version, process-wide: Home and Activity each hold an instance
 * over the same persisted checkpoints. In memory only (a request never outlives the process); past the cap the
 * oldest scope goes and reads as not accepted since.
 */
private object ReadStateAcceptances {
    private const val CAP = 2_000
    private val lock = SynchronizedObject()
    private var count = 0L
    private val acceptedAt = LinkedHashMap<String, Long>()

    fun mark(): Long = synchronized(lock) { count }

    fun note(key: String) {
        synchronized(lock) {
            acceptedAt.remove(key)
            acceptedAt[key] = ++count
            while (acceptedAt.size > CAP) acceptedAt.remove(acceptedAt.keys.first())
        }
    }

    fun since(key: String, mark: Long): Boolean = synchronized(lock) { (acceptedAt[key] ?: 0L) > mark }
}
