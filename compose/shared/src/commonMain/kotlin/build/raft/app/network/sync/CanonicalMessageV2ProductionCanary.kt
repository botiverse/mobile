package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Full schema-v6 is a separate apply gate; the V1 persisted envelope remains permanently ineligible. */
const val CANONICAL_MESSAGE_FULL_V2_SOLE_APPLY_ELIGIBLE: Boolean = true

/**
 * Task #168: frames of one channel kept while its enter snapshot loads. One more marks the window that
 * snapshot commits discontinuous instead (the buffer is dropped): never an unbounded queue.
 */
internal const val CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP: Int = 64

sealed interface CanonicalMessageV2CanaryOutcome {
    /** Not written: the event remains on the existing T3-shadow + legacy projection path (display only). */
    data object NotSelected : CanonicalMessageV2CanaryOutcome

    /** The V2 commit completed and the caller may project the event after a fresh grant check. */
    data object Committed : CanonicalMessageV2CanaryOutcome

    /**
     * Task #168: the channel's enter snapshot is loading; the event is buffered for it and written (or not)
     * when that snapshot commits. Nothing is written yet and the caller never waits for the network: it
     * projects the event now through the legacy display path after a fresh grant check, as for [Committed].
     */
    data object Pending : CanonicalMessageV2CanaryOutcome

    /**
     * The durable V2 write did not happen (revoked, evicted, failed). Nothing was written; the caller
     * still projects the event through the legacy display path after a fresh grant check (task #168: a
     * failed durable write must never hide a message).
     */
    data class Held(val diagnostic: String) : CanonicalMessageV2CanaryOutcome
}

/** Result of one server page fetch (enter snapshot, reply-thread snapshot). */
sealed interface CanonicalMessageV2SnapshotLoadResult {
    data class Loaded(val frame: CanonicalMessageV2SnapshotFrame) : CanonicalMessageV2SnapshotLoadResult
    data class Unavailable(val diagnostic: String) : CanonicalMessageV2SnapshotLoadResult
}

/** Task #168 (diagnostics / tests): what the realtime path does with the next frame of a scope. */
enum class CanonicalMessageV2ScopeContinuity {
    /** No window committed in this process (never opened, or restored from disk only): display only. */
    NoWindow,

    /** The window is known to hold every frame since its baseline: frames are written incrementally. */
    Continuous,

    /** The window may miss frames: display only until the user next opens the channel. */
    Discontinuous,
}

/** One delivery of a realtime frame: the exact decoded frame and the connection that delivered it. */
internal data class CanonicalMessageV2Delivery(
    val foldInput: CanonicalMessageFoldInput,
    val frameTransportEpoch: Long?,
)

/** A frame that arrived while its channel's enter snapshot was loading, with the epoch it was accepted under. */
internal class CanonicalMessageV2BufferedFrame(
    val delivery: CanonicalMessageV2Delivery,
    /** The frame's own epoch, else the partition's recorded epoch when it was accepted (as #2991). */
    val transportEpoch: Long?,
)

/**
 * Task #168: the pending external baseline of one enter snapshot, registered BEFORE its fetch
 * ([CanonicalMessageV2ProductionCanary.beginEnterBaseline]). Everything that decides whether the window it
 * commits is continuous with the realtime stream is captured at request time. All mutable fields are guarded
 * by the canary's state mutex.
 */
class CanonicalMessageV2EnterBaseline internal constructor(
    internal val partition: DurablePartitionKey,
    internal val scopeId: SyncScopeId,
    /** The recorded transport epoch when the request was sent (#2991 rules); null = none recorded yet. */
    internal val epochAtRequest: Long?,
    internal val roomLeaveCountAtRequest: Long,
    /** The conversation directory said the user is not a member when the request was sent. */
    internal val nonMemberAtRequest: Boolean,
) {
    internal val buffered = ArrayList<CanonicalMessageV2BufferedFrame>()
    internal var overflowed = false
    internal var closed = false

    /** Another writer committed into this channel while the fetch was in flight. */
    internal var superseded = false

    internal fun alreadyHolds(delivery: CanonicalMessageV2Delivery): Boolean =
        buffered.any { it.delivery == delivery }
}

/**
 * The realtime state of one scope that has a window committed in this process (task #168: one per windowed
 * channel / DM, no single selected slot). Created only by an enter-snapshot commit; mutable fields are
 * guarded by the canary's state mutex.
 */
private class CanonicalMessageV2CanaryScopeState(
    var cursor: PersistedCursor?,
    /** The connection the baseline is known to be continuous with (the enter request's epoch). */
    val committedTransportEpoch: Long?,
    /** [CanonicalMessageV2ProductionCanary.noteRoomLeft] count of this room when the baseline was requested. */
    val roomLeaveCountAtBaseline: Long,
) {
    /**
     * Task #168: the window may miss frames from here on (epoch change, room leave, external commit, failed
     * write, unapplicable update). Display only; never cleared: only the next enter commit replaces the state.
     */
    var discontinuous: Boolean = false

    /**
     * Task #168: the last delivery committed for each fact id (bounded). One realtime frame reaches the
     * canary through more than one entry (process owner and AppShell owner; on iOS / OHOS the process
     * owner twice), so a delivery equal to the last committed one for its fact is that same frame again.
     */
    private val lastCommittedDeliveryByFactId = LinkedHashMap<String, CanonicalMessageV2Delivery>()

    fun isAlreadyCommitted(delivery: CanonicalMessageV2Delivery): Boolean =
        lastCommittedDeliveryByFactId[delivery.foldInput.row.id] == delivery

    fun recordCommitted(delivery: CanonicalMessageV2Delivery) {
        val factId = delivery.foldInput.row.id
        lastCommittedDeliveryByFactId.remove(factId)
        lastCommittedDeliveryByFactId[factId] = delivery
        while (lastCommittedDeliveryByFactId.size > COMMITTED_DELIVERY_MEMORY) {
            lastCommittedDeliveryByFactId.remove(lastCommittedDeliveryByFactId.keys.first())
        }
    }

    private companion object {
        const val COMMITTED_DELIVERY_MEMORY = 256
    }
}

private sealed interface CanonicalMessageV2Route {
    /** No window and no enter in flight (or flag off without a window): display only, zero V2 work. */
    data object None : CanonicalMessageV2Route

    /** Buffered for the channel's loading enter snapshot. */
    data object Buffered : CanonicalMessageV2Route

    data class Window(val scope: SyncScopeKey) : CanonicalMessageV2Route

    /** The flag went off for a scope that has a window. */
    data class Revoke(val scope: SyncScopeKey) : CanonicalMessageV2Route
}

private sealed interface CanonicalMessageV2Plan {
    data object DisplayOnly : CanonicalMessageV2Plan
    data object AlreadyCommitted : CanonicalMessageV2Plan
    data class Write(val state: CanonicalMessageV2CanaryScopeState, val cursor: PersistedCursor) : CanonicalMessageV2Plan
}

private sealed interface CanonicalMessageV2AppendResult {
    data object Committed : CanonicalMessageV2AppendResult
    data object BelowWindow : CanonicalMessageV2AppendResult
    data object NotFullV2 : CanonicalMessageV2AppendResult
    data object BaselineRequired : CanonicalMessageV2AppendResult
    data object PartitionRevoked : CanonicalMessageV2AppendResult
    data object ScopeEvicted : CanonicalMessageV2AppendResult
    data class Failed(val reason: String) : CanonicalMessageV2AppendResult
}

/**
 * Task #168 step 3: realtime messages are written to the durable offline cache for EVERY top-level channel /
 * DM that has a window committed in this process, behind the existing `sync_core_messages` flag.
 *
 * - Which scopes: those whose window an enter snapshot committed in this process ([stateByScope], the
 *   in-memory index). A scope without one — never opened, or only restored from disk after a cold start —
 *   is display only: no request, no write, no store lookup on the display path. A restored window may have
 *   missed frames while the process was dead, so it is treated exactly like a discontinuous one until the
 *   user opens the channel again (its enter commit creates the state).
 * - Continuous (same epoch as the baseline, never marked): `New` appends (insert-only, below-window skip,
 *   cap edge, duplicate-delivery skip, #3002); `Updated` replaces the stored fact when the frame is full,
 *   the fact is in the window and has no reply thread; otherwise it marks the scope discontinuous, or is
 *   ignored when it lies below the window.
 * - Discontinuous: display only. Nothing here ever requests the network (no first-frame activation, no
 *   background re-snapshot): the next enter snapshot is the new baseline.
 * - Hole A: the enter snapshot registers a pending baseline with its request epoch before it fetches;
 *   frames of that channel meanwhile are displayed and buffered (bounded), then replayed inside the scope
 *   writer right after the enter commit by #3007's rules.
 */
internal class CanonicalMessageV2ProductionCanary(
    private val isEnabledForServer: (String) -> Boolean,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val logger: (String) -> Unit = {},
) {
    /** Leaf lock: never held while calling the engine's suspending writes or another lock. */
    private val stateMutex = Mutex()

    /** Guarded by [stateMutex]: the windowed index (every scope with a window committed in this process). */
    private val stateByScope = mutableMapOf<SyncScopeKey, CanonicalMessageV2CanaryScopeState>()

    /** Guarded by [stateMutex]: the enter snapshot in flight for each channel id. */
    private val enterBaselineByChannel =
        mutableMapOf<Pair<DurablePartitionKey, SyncScopeId>, CanonicalMessageV2EnterBaseline>()

    /** Guarded by [stateMutex]. */
    private val currentTransportEpochByPartition = mutableMapOf<DurablePartitionKey, Long>()

    private val roomLeaveLock = SynchronizedObject()

    /** Guarded by [roomLeaveLock]: how many times the socket left each (server, room). */
    private val roomLeaveCountByRoom = mutableMapOf<Pair<String, String>, Long>()

    init {
        generationEngine.addPartitionRevocationObserver(::onPartitionRevoked)
        generationEngine.addScopeInvalidationObserver(::onScopesInvalidated)
    }

    suspend fun accept(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        frameTransportEpoch: Long? = null,
    ): CanonicalMessageV2CanaryOutcome {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (!CANONICAL_MESSAGE_FULL_V2_SOLE_APPLY_ELIGIBLE) return CanonicalMessageV2CanaryOutcome.NotSelected
        val delivery = CanonicalMessageV2Delivery(foldInput, frameTransportEpoch?.takeIf { it > 0L })
        // Fast path: no writer, no store. Only a scope with a window (or an enter in flight) goes further.
        val scope = when (val route = stateMutex.withLock { routeLocked(partition, delivery) }) {
            CanonicalMessageV2Route.None -> return CanonicalMessageV2CanaryOutcome.NotSelected
            CanonicalMessageV2Route.Buffered -> return CanonicalMessageV2CanaryOutcome.Pending
            is CanonicalMessageV2Route.Window -> route.scope
            is CanonicalMessageV2Route.Revoke -> route.scope
        }
        return generationEngine.withScopeWriter(scope) writer@{
            // Re-routed inside the writer: an enter commit may have replaced the state meanwhile.
            when (val route = stateMutex.withLock { routeLocked(partition, delivery) }) {
                CanonicalMessageV2Route.None -> CanonicalMessageV2CanaryOutcome.NotSelected
                CanonicalMessageV2Route.Buffered -> CanonicalMessageV2CanaryOutcome.Pending
                is CanonicalMessageV2Route.Revoke ->
                    if (route.scope == scope) revokeInsideWriter(scope) else CanonicalMessageV2CanaryOutcome.NotSelected
                is CanonicalMessageV2Route.Window ->
                    if (route.scope == scope) acceptInsideWriter(scope, partition, delivery)
                    else CanonicalMessageV2CanaryOutcome.NotSelected
            }
        }
    }

    /** Holding [stateMutex]. Buffers for a loading enter snapshot right here (no write, no writer needed). */
    private fun routeLocked(partition: DurablePartitionKey, delivery: CanonicalMessageV2Delivery): CanonicalMessageV2Route {
        val input = delivery.foldInput
        val explicit = input.v2ScopeOrNull(partition)
        if (explicit == null && input.row.conversationContext != null) {
            // A reply (thread scopes are written by their own enter / paging paths) or an unsupported kind.
            return CanonicalMessageV2Route.None
        }
        val scope = explicit ?: run {
            // No producer context: the frame names only its channel id. It can never be written, but a
            // window of that channel id must not silently miss it.
            stateByScope.keys.singleOrNull { it.partition == partition && it.scopeId == input.row.channelId }
        }
        if (!isEnabledForServer(partition.serverId)) {
            return if (scope != null && scope in stateByScope) CanonicalMessageV2Route.Revoke(scope) else CanonicalMessageV2Route.None
        }
        val baseline = enterBaselineByChannel[partition to input.row.channelId]
        if (baseline != null && !baseline.closed) {
            bufferLocked(baseline, delivery)
            return CanonicalMessageV2Route.Buffered
        }
        return if (scope != null && scope in stateByScope) CanonicalMessageV2Route.Window(scope) else CanonicalMessageV2Route.None
    }

    /** Holding [stateMutex]: display now, keep the frame for the enter commit's replay. */
    private fun bufferLocked(baseline: CanonicalMessageV2EnterBaseline, delivery: CanonicalMessageV2Delivery) {
        val scopeId = baseline.scopeId
        when {
            // The same frame through another entry: kept once (one write per frame).
            baseline.alreadyHolds(delivery) -> logger(
                "canonical_v2_canary skip=duplicate_delivery server=${baseline.partition.serverId} " +
                    "scope=$scopeId pending=enter",
            )
            baseline.overflowed -> Unit
            baseline.buffered.size >= CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP -> {
                baseline.overflowed = true
                baseline.buffered.clear()
                logger(
                    "canonical_v2_canary enter=buffer_overflow server=${baseline.partition.serverId} " +
                        "scope=$scopeId cap=$CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP",
                )
            }
            else -> baseline.buffered += CanonicalMessageV2BufferedFrame(
                delivery,
                delivery.frameTransportEpoch ?: currentTransportEpochByPartition[baseline.partition],
            )
        }
    }

    private suspend fun acceptInsideWriter(
        scope: SyncScopeKey,
        partition: DurablePartitionKey,
        delivery: CanonicalMessageV2Delivery,
    ): CanonicalMessageV2CanaryOutcome {
        val plan = stateMutex.withLock { planLocked(scope, partition, delivery) }
        val (state, cursor) = when (plan) {
            CanonicalMessageV2Plan.DisplayOnly -> return CanonicalMessageV2CanaryOutcome.NotSelected
            // The same frame through another entry: it is on disk already. Never write it again.
            CanonicalMessageV2Plan.AlreadyCommitted -> return CanonicalMessageV2CanaryOutcome.Committed
            is CanonicalMessageV2Plan.Write -> plan.state to plan.cursor
        }
        val input = delivery.foldInput
        return when (input.kind) {
            CanonicalMessageFoldKind.New -> {
                when (val result = appendNewInsideWriter(scope, state, cursor, input)) {
                    CanonicalMessageV2AppendResult.Committed -> {
                        stateMutex.withLock { state.recordCommitted(delivery) }
                        CanonicalMessageV2CanaryOutcome.Committed
                    }
                    // Display only (logged inside); continuity unchanged.
                    CanonicalMessageV2AppendResult.BelowWindow -> CanonicalMessageV2CanaryOutcome.NotSelected
                    CanonicalMessageV2AppendResult.NotFullV2 -> {
                        markDiscontinuous(scope, state, "not_full_v2")
                        CanonicalMessageV2CanaryOutcome.NotSelected
                    }
                    CanonicalMessageV2AppendResult.BaselineRequired -> {
                        markDiscontinuous(scope, state, "baseline_required")
                        CanonicalMessageV2CanaryOutcome.NotSelected
                    }
                    CanonicalMessageV2AppendResult.PartitionRevoked -> heldAfterWindowLost(scope, state, "partition_revoked")
                    CanonicalMessageV2AppendResult.ScopeEvicted -> heldAfterWindowLost(scope, state, "retention_evicted")
                    is CanonicalMessageV2AppendResult.Failed -> {
                        markDiscontinuous(scope, state, result.reason)
                        CanonicalMessageV2CanaryOutcome.Held("canonical_v2_write_failed")
                    }
                }
            }
            CanonicalMessageFoldKind.Updated -> applyUpdatedInsideWriter(scope, state, cursor, delivery)
        }
    }

    /** Holding [stateMutex]: every continuity decision for one frame, before any write. */
    private fun planLocked(
        scope: SyncScopeKey,
        partition: DurablePartitionKey,
        delivery: CanonicalMessageV2Delivery,
    ): CanonicalMessageV2Plan {
        val state = stateByScope[scope] ?: return CanonicalMessageV2Plan.DisplayOnly
        if (state.isAlreadyCommitted(delivery)) {
            logger("canonical_v2_canary skip=duplicate_delivery server=${scope.serverId} scope=${scope.scopeId}")
            return CanonicalMessageV2Plan.AlreadyCommitted
        }
        val frameEpoch = delivery.frameTransportEpoch
        val committedEpoch = state.committedTransportEpoch
        if (frameEpoch != null && committedEpoch != null && frameEpoch < committedEpoch) {
            // A late frame of an older socket: the window already continues from a newer connection, and
            // frames that socket lost may sit between. Show it, never write it, change nothing (also while
            // the scope is discontinuous). Epochs only grow within a process on every host.
            logger(
                "canonical_v2_canary skip=stale_transport server=${scope.serverId} scope=${scope.scopeId} " +
                    "frameEpoch=$frameEpoch committedEpoch=$committedEpoch",
            )
            return CanonicalMessageV2Plan.DisplayOnly
        }
        if (!state.discontinuous && roomLeaveCount(scope.serverId, scope.scopeId) != state.roomLeaveCountAtBaseline) {
            markDiscontinuousLocked(scope, state, "room_left")
        }
        if (!state.discontinuous) {
            // A frame that names its connection decides by that; otherwise the recorded epoch, read here.
            val transportEpoch = frameEpoch ?: currentTransportEpochByPartition[partition]
            if (transportEpoch != committedEpoch) {
                markDiscontinuousLocked(scope, state, "epoch_changed frameEpoch=$transportEpoch committedEpoch=$committedEpoch")
            }
        }
        if (state.discontinuous) {
            logger("canonical_v2_canary skip=discontinuous server=${scope.serverId} scope=${scope.scopeId}")
            return CanonicalMessageV2Plan.DisplayOnly
        }
        val cursor = generationEngine.currentCursor(scope) ?: state.cursor
        if (cursor == null) {
            markDiscontinuousLocked(scope, state, "no_window")
            return CanonicalMessageV2Plan.DisplayOnly
        }
        state.cursor = cursor
        return CanonicalMessageV2Plan.Write(state, cursor)
    }

    /**
     * Appends one `New` to a continuous window, inside the scope writer. Shared by the realtime path and the
     * enter replay so both keep #3002's rules (insert-only, below-window skip, committed cursor). Never
     * refreshes the scope's retention rank (task #168: ranking = last user-driven write).
     */
    private suspend fun appendNewInsideWriter(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        cursor: PersistedCursor,
        foldInput: CanonicalMessageFoldInput,
    ): CanonicalMessageV2AppendResult {
        val windowOldest = cursor.oldestLoadedSeq
        if (windowOldest != null && foldInput.row.seq < windowOldest) {
            // Older than the stored window: nothing says the messages between it and the window's oldest
            // edge are on disk, so writing it (and stretching the edge down to it) would claim a range never
            // loaded. Show it, never write it.
            logger(
                "canonical_v2_canary skip=below_window server=${scope.serverId} scope=${scope.scopeId} " +
                    "seq=${foldInput.row.seq} oldestLoadedSeq=$windowOldest",
            )
            return CanonicalMessageV2AppendResult.BelowWindow
        }
        val envelope = foldInput.toCanonicalMessageV2Envelope(scope.partition, replyCount = 0)
            ?: return CanonicalMessageV2AppendResult.NotFullV2
        val advanced = cursor.advancedBy(envelope.fact.seq)
        val result = commitIncremental(
            // A `New` only ever adds: a stored fact may already be newer (edited, reacted).
            CanonicalMessageV2IncrementalFrame(
                scope,
                listOf(envelope),
                advanced,
                insertOnly = true,
                preserveUpdatedAt = true,
            ),
        ) ?: return CanonicalMessageV2AppendResult.Failed("write_failed")
        return when (result) {
            CanonicalMessageV2CommitResult.Committed -> {
                stateMutex.withLock { state.cursor = generationEngine.currentCursor(scope) ?: advanced }
                logger("canonical_v2_canary written server=${scope.serverId} scope=${scope.scopeId} source=incremental")
                CanonicalMessageV2AppendResult.Committed
            }
            is CanonicalMessageV2CommitResult.BaselineRequired -> CanonicalMessageV2AppendResult.BaselineRequired
            CanonicalMessageV2CommitResult.PartitionRevoked -> CanonicalMessageV2AppendResult.PartitionRevoked
            CanonicalMessageV2CommitResult.ScopeEvicted -> CanonicalMessageV2AppendResult.ScopeEvicted
        }
    }

    /**
     * Task #168: one `message:updated` on a continuous window, inside the scope writer. Explicit fields only:
     * - below the window's oldest edge → ignored (the window never claimed it);
     * - names a reply thread (`threadId`), so its reply count is unknown → discontinuous;
     * - partial (not a full V2 node) → discontinuous;
     * - its fact is not in the window although its seq is inside the window's range → discontinuous;
     * - otherwise the stored fact is replaced (never insert-only), cursor and retention rank unchanged.
     */
    private suspend fun applyUpdatedInsideWriter(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        cursor: PersistedCursor,
        delivery: CanonicalMessageV2Delivery,
    ): CanonicalMessageV2CanaryOutcome {
        val row = delivery.foldInput.row
        val windowOldest = cursor.oldestLoadedSeq
        if (windowOldest != null && row.seq < windowOldest) {
            logger(
                "canonical_v2_canary skip=outside_window server=${scope.serverId} scope=${scope.scopeId} " +
                    "seq=${row.seq} oldestLoadedSeq=$windowOldest",
            )
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        if (row.threadId != null) {
            markDiscontinuous(scope, state, "updated_thread")
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val envelope = delivery.foldInput.toCanonicalMessageV2Envelope(scope.partition, replyCount = 0)
        if (envelope == null) {
            markDiscontinuous(scope, state, "updated_partial")
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val stored = try {
            generationEngine.storedFactExists(scope, row.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            markDiscontinuous(scope, state, "read_failed")
            return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_read_failed")
        }
        if (!stored) {
            markDiscontinuous(scope, state, "updated_missing")
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val result = commitIncremental(
            CanonicalMessageV2IncrementalFrame(scope, listOf(envelope), cursor, insertOnly = false, preserveUpdatedAt = true),
        )
        return when (result) {
            null -> {
                markDiscontinuous(scope, state, "write_failed")
                CanonicalMessageV2CanaryOutcome.Held("canonical_v2_write_failed")
            }
            CanonicalMessageV2CommitResult.Committed -> {
                stateMutex.withLock {
                    state.cursor = generationEngine.currentCursor(scope) ?: cursor
                    state.recordCommitted(delivery)
                }
                logger("canonical_v2_canary written server=${scope.serverId} scope=${scope.scopeId} source=updated")
                CanonicalMessageV2CanaryOutcome.Committed
            }
            is CanonicalMessageV2CommitResult.BaselineRequired -> {
                markDiscontinuous(scope, state, "baseline_required")
                CanonicalMessageV2CanaryOutcome.NotSelected
            }
            CanonicalMessageV2CommitResult.PartitionRevoked -> heldAfterWindowLost(scope, state, "partition_revoked")
            CanonicalMessageV2CommitResult.ScopeEvicted -> heldAfterWindowLost(scope, state, "retention_evicted")
        }
    }

    /** `null` = the write threw (nothing committed: one SQLite transaction). */
    private suspend fun commitIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2CommitResult? =
        try {
            generationEngine.applyIncremental(frame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            logger(
                "canonical_v2_canary write_failed server=${frame.scope.serverId} scope=${frame.scope.scopeId} " +
                    "reason=${throwable::class.simpleName ?: "unknown"}",
            )
            null
        }

    /**
     * Task #167: the realtime transport serving [partition] (re)connected as [transportEpoch]. Posted by
     * the native host before any frame of the new socket. Recorded per partition; every scope compares it
     * with the epoch its baseline was requested under, inside its own writer.
     */
    suspend fun noteTransportEpoch(partition: DurablePartitionKey, transportEpoch: Long) {
        if (transportEpoch <= 0L) return
        stateMutex.withLock { currentTransportEpochByPartition[partition] = transportEpoch }
    }

    /**
     * Task #168: the socket left room [roomId] on [serverId] (a `leave:channel` transition; since #2994 only
     * for rooms the user is not a member of). Its later frames may be missing from the stream, so every
     * window of that channel id is discontinuous from here on, and an enter in flight cannot become
     * continuous. Non-suspending: called from the room-lease registry's lock.
     */
    fun noteRoomLeft(serverId: String, roomId: String) {
        val count = synchronized(roomLeaveLock) {
            val key = serverId to roomId
            ((roomLeaveCountByRoom[key] ?: 0L) + 1L).also { roomLeaveCountByRoom[key] = it }
        }
        logger("canonical_v2_canary room_left server=$serverId scope=$roomId count=$count")
    }

    private fun roomLeaveCount(serverId: String, roomId: String): Long =
        synchronized(roomLeaveLock) { roomLeaveCountByRoom[serverId to roomId] ?: 0L }

    /**
     * Task #168 hole A: an enter snapshot of [scopeId] is about to be requested. MUST be called before the
     * fetch. Records the request epoch; from now until [onEnterCommitted] / [abandonEnterBaseline], frames of
     * that channel are displayed and buffered instead of written. `null` = one is already in flight (the
     * caller then commits as a plain external commit).
     */
    suspend fun beginEnterBaseline(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        nonMember: Boolean,
    ): CanonicalMessageV2EnterBaseline? = stateMutex.withLock {
        val key = partition to scopeId
        if (enterBaselineByChannel[key]?.closed == false) return@withLock null
        val baseline = CanonicalMessageV2EnterBaseline(
            partition = partition,
            scopeId = scopeId,
            epochAtRequest = currentTransportEpochByPartition[partition],
            roomLeaveCountAtRequest = roomLeaveCount(partition.serverId, scopeId),
            nonMemberAtRequest = nonMember,
        )
        enterBaselineByChannel[key] = baseline
        logger(
            "canonical_v2_canary enter=registered server=${partition.serverId} scope=$scopeId " +
                "epochAtRequest=${baseline.epochAtRequest} nonMember=$nonMember",
        )
        baseline
    }

    /**
     * Task #168: [baseline]'s enter snapshot [snapshot] has just committed. MUST be called inside
     * `generationEngine.withScopeWriter(snapshot.scope)`, right after `applySnapshot` returned `Committed`.
     * The scope gets a fresh state continuous under the request epoch, then the buffered frames are replayed
     * by #3007's rules. It stays discontinuous instead when the user is not a member of the channel (the
     * server joins its room asynchronously on open, so the page may miss messages never pushed), the room was
     * left meanwhile, another writer committed meanwhile, or the buffer overflowed.
     */
    suspend fun onEnterCommitted(
        baseline: CanonicalMessageV2EnterBaseline,
        snapshot: CanonicalMessageV2SnapshotFrame,
        nonMemberAtCommit: Boolean = false,
    ): Unit = withContext(NonCancellable) {
        val scope = snapshot.scope
        val (state, buffered, reason) = stateMutex.withLock {
            val open = !baseline.closed && enterBaselineByChannel[baseline.partition to baseline.scopeId] === baseline
            closeLocked(baseline)
            val cursor = generationEngine.currentCursor(scope)
            if (!open || cursor == null) {
                // Dropped by a reset meanwhile, or the window is gone: no state, nothing replayed.
                markChannelLocked(baseline, "enter_dropped")
                stateByScope.remove(scope)
                return@withContext
            }
            val state = CanonicalMessageV2CanaryScopeState(
                cursor = cursor,
                committedTransportEpoch = baseline.epochAtRequest,
                roomLeaveCountAtBaseline = baseline.roomLeaveCountAtRequest,
            )
            stateByScope[scope] = state
            val reason = when {
                baseline.nonMemberAtRequest || nonMemberAtCommit -> "non_member"
                roomLeaveCount(scope.serverId, scope.scopeId) != baseline.roomLeaveCountAtRequest -> "room_left"
                baseline.superseded -> "external_commit"
                baseline.overflowed -> "buffer_overflow"
                else -> null
            }
            if (reason != null) state.discontinuous = true
            Triple(state, baseline.buffered.toList(), reason)
        }
        logger(
            "canonical_v2_canary enter=committed server=${scope.serverId} scope=${scope.scopeId} " +
                "epoch=${baseline.epochAtRequest} buffered=${buffered.size} " +
                (reason?.let { "continuity=discontinuous reason=$it" } ?: "continuity=continuous"),
        )
        if (reason == null) replayBuffered(baseline, scope, state, snapshot, buffered)
    }

    /**
     * Task #168: [baseline]'s enter snapshot will not commit (failed fetch, identity / flag change, refused
     * commit). No-op once committed. Frames displayed during the fetch are not on disk, so any window of that
     * channel is discontinuous from here on.
     */
    suspend fun abandonEnterBaseline(baseline: CanonicalMessageV2EnterBaseline): Unit = withContext(NonCancellable) {
        stateMutex.withLock {
            if (baseline.closed) return@withLock
            closeLocked(baseline)
            if (baseline.buffered.isNotEmpty() || baseline.overflowed) markChannelLocked(baseline, "enter_failed")
            logger(
                "canonical_v2_canary enter=abandoned server=${baseline.partition.serverId} " +
                    "scope=${baseline.scopeId} buffered=${baseline.buffered.size}",
            )
        }
    }

    /** Holding [stateMutex]. */
    private fun closeLocked(baseline: CanonicalMessageV2EnterBaseline) {
        baseline.closed = true
        val key = baseline.partition to baseline.scopeId
        if (enterBaselineByChannel[key] === baseline) enterBaselineByChannel.remove(key)
    }

    /** Holding [stateMutex]: every window of [baseline]'s channel id may now miss frames. */
    private fun markChannelLocked(baseline: CanonicalMessageV2EnterBaseline, reason: String) {
        stateByScope.entries
            .filter { it.key.partition == baseline.partition && it.key.scopeId == baseline.scopeId }
            .forEach { (scope, state) -> markDiscontinuousLocked(scope, state, reason) }
    }

    /**
     * Inside the scope writer, right after the enter snapshot committed under its request epoch. A buffered
     * frame is appended only when it is provably continuous with that snapshot: a `New` of the same epoch
     * with a seq above the snapshot's latestSeq. A frame the snapshot already holds is not written again; an
     * older connection's frame is display-only; anything else marks the scope discontinuous.
     */
    private suspend fun replayBuffered(
        baseline: CanonicalMessageV2EnterBaseline,
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        snapshot: CanonicalMessageV2SnapshotFrame,
        buffered: List<CanonicalMessageV2BufferedFrame>,
    ) {
        suspend fun discontinuous(reason: String) = markDiscontinuous(scope, state, "replay_$reason")
        val committedEpoch = baseline.epochAtRequest
        val snapshotLatestSeq = snapshot.cursor.latestSeq
        val snapshotFactIds = snapshot.envelopes.mapTo(HashSet()) { it.fact.id }
        for (frame in buffered) {
            val delivery = frame.delivery
            val input = delivery.foldInput
            val frameScope = input.v2ScopeOrNull(scope.partition)
            if (frameScope != null && frameScope != scope) continue // another scope kind of the same id
            val epoch = frame.transportEpoch
            if (epoch != null && committedEpoch != null && epoch < committedEpoch) {
                // A late frame of an older socket (as #2991): shown when it arrived, never written.
                logger(
                    "canonical_v2_canary skip=stale_transport server=${scope.serverId} scope=${scope.scopeId} " +
                        "frameEpoch=$epoch committedEpoch=$committedEpoch replay=true",
                )
                continue
            }
            if (epoch != committedEpoch) return discontinuous("epoch_mismatch")
            if (frameScope == null) return discontinuous("not_full_v2")
            if (input.kind != CanonicalMessageFoldKind.New) return discontinuous("buffered_${input.kind.name.lowercase()}")
            if (stateMutex.withLock { state.isAlreadyCommitted(delivery) }) continue
            val seq = input.row.seq
            val cursor = stateMutex.withLock { state.cursor } ?: return discontinuous("no_window")
            if (snapshotLatestSeq != null && seq <= snapshotLatestSeq) {
                if (input.row.id in snapshotFactIds) {
                    // The snapshot already holds it: never written twice.
                    stateMutex.withLock { state.recordCommitted(delivery) }
                    continue
                }
                val windowOldest = cursor.oldestLoadedSeq
                if (windowOldest != null && seq < windowOldest) {
                    logger(
                        "canonical_v2_canary skip=below_window server=${scope.serverId} scope=${scope.scopeId} " +
                            "seq=$seq oldestLoadedSeq=$windowOldest replay=true",
                    )
                    continue
                }
                return discontinuous("missing_from_snapshot")
            }
            when (val result = appendNewInsideWriter(scope, state, cursor, input)) {
                CanonicalMessageV2AppendResult.Committed -> stateMutex.withLock { state.recordCommitted(delivery) }
                CanonicalMessageV2AppendResult.BelowWindow -> Unit
                CanonicalMessageV2AppendResult.NotFullV2 -> return discontinuous("not_full_v2")
                CanonicalMessageV2AppendResult.BaselineRequired -> return discontinuous("baseline_required")
                CanonicalMessageV2AppendResult.PartitionRevoked -> {
                    heldAfterWindowLost(scope, state, "partition_revoked")
                    return
                }
                CanonicalMessageV2AppendResult.ScopeEvicted -> {
                    heldAfterWindowLost(scope, state, "retention_evicted")
                    return
                }
                is CanonicalMessageV2AppendResult.Failed -> return discontinuous(result.reason)
            }
        }
    }

    /**
     * Task #168: a writer outside the canary that moves the newest edge without a baseline (newer adjacent
     * page, gap catch-up, an enter that could not register) has just committed into [scope]. MUST be called inside
     * `generationEngine.withScopeWriter(scope)`, right after that commit returned `Committed`, so it is
     * serialized with every canary write of the scope.
     *
     * The canary did not produce that window and nothing proves its continuity with the realtime stream, so
     * the scope is marked discontinuous (#3008): display only until the next enter commit. Nothing is
     * rescheduled (task #168: no background snapshot). An enter in flight for the channel cannot become
     * continuous either. No-op for a scope without a state (thread scopes, never-opened channels).
     *
     * Locks: only the canary's leaf state mutex. Nothing of the realtime grant / authority registry is touched.
     */
    suspend fun onExternalCommit(scope: SyncScopeKey): Unit = withContext(NonCancellable) {
        // The commit already happened: a cancellation of the caller must not skip the mark.
        stateMutex.withLock {
            enterBaselineByChannel[scope.partition to scope.scopeId]?.superseded = true
            val state = stateByScope[scope] ?: return@withLock
            state.cursor = generationEngine.currentCursor(scope)
            markDiscontinuousLocked(scope, state, "external_commit")
        }
    }

    /**
     * Task #168: an older adjacent page has just committed into [scope] (inside its scope writer). It only
     * moved the oldest edge — the newest edge, where realtime frames are appended, is the one this window
     * already had — so continuity is unchanged; only the cached cursor is refreshed.
     */
    suspend fun onOlderPageCommitted(scope: SyncScopeKey): Unit = withContext(NonCancellable) {
        stateMutex.withLock {
            val state = stateByScope[scope] ?: return@withLock
            state.cursor = generationEngine.currentCursor(scope)
            logger(
                "canonical_v2_canary older_page server=${scope.serverId} scope=${scope.scopeId} " +
                    "continuity=${if (state.discontinuous) "discontinuous" else "continuous"}",
            )
        }
    }

    /** What the realtime path does with [scope]'s next frame (diagnostics / tests; never selects or widens). */
    internal suspend fun continuityOf(scope: SyncScopeKey): CanonicalMessageV2ScopeContinuity =
        stateMutex.withLock {
            val state = stateByScope[scope] ?: return@withLock CanonicalMessageV2ScopeContinuity.NoWindow
            if (state.discontinuous) CanonicalMessageV2ScopeContinuity.Discontinuous
            else CanonicalMessageV2ScopeContinuity.Continuous
        }

    /** The scopes of [partition] with a window committed in this process (the in-memory index). */
    internal suspend fun windowedScopes(partition: DurablePartitionKey): Set<SyncScopeKey> =
        stateMutex.withLock { stateByScope.keys.filterTo(mutableSetOf()) { it.partition == partition } }

    private suspend fun markDiscontinuous(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        reason: String,
    ) = stateMutex.withLock { markDiscontinuousLocked(scope, state, reason) }

    /** Holding [stateMutex]. Only the state still installed for [scope] is marked. */
    private fun markDiscontinuousLocked(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        reason: String,
    ) {
        if (stateByScope[scope] !== state || state.discontinuous) return
        state.discontinuous = true
        logger("canonical_v2_canary discontinuous server=${scope.serverId} scope=${scope.scopeId} reason=$reason")
    }

    /**
     * The flag went off for a scope with a window: drop its V2 window and hand the scope back to the legacy
     * path. Never loads.
     */
    private suspend fun revokeInsideWriter(scope: SyncScopeKey): CanonicalMessageV2CanaryOutcome {
        generationEngine.clearScope(scope)
        stateMutex.withLock { stateByScope.remove(scope) }
        logger("canonical_v2_canary mode=off server=${scope.serverId} scope=${scope.scopeId} reason=flag_revoked")
        return CanonicalMessageV2CanaryOutcome.NotSelected
    }

    private suspend fun onPartitionRevoked(partition: DurablePartitionKey) {
        stateMutex.withLock {
            currentTransportEpochByPartition.remove(partition)
            stateByScope.keys.removeAll { it.partition == partition }
            enterBaselineByChannel.entries.removeAll { (key, baseline) ->
                (key.first == partition).also { if (it) baseline.closed = true }
            }
        }
    }

    private suspend fun onScopesInvalidated(scopes: Set<SyncScopeKey>) {
        stateMutex.withLock { stateByScope.keys.removeAll { it in scopes } }
    }

    private suspend fun heldAfterWindowLost(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        reason: String,
    ): CanonicalMessageV2CanaryOutcome {
        stateMutex.withLock { if (stateByScope[scope] === state) stateByScope.remove(scope) }
        logger("canonical_v2_canary mode=off server=${scope.serverId} scope=${scope.scopeId} reason=$reason")
        return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_$reason")
    }
}

private fun CanonicalMessageFoldInput.v2ScopeOrNull(partition: DurablePartitionKey): SyncScopeKey? {
    val context = row.conversationContext ?: return null
    val kind = context.channelType
    if (kind !in setOf("channel", "private", "dm", "joint")) return null
    if (
        context.parentChannelId != null ||
        context.parentChannelType != null ||
        context.parentMessageId != null
    ) return null
    return SyncScopeKey(partition = partition, scopeId = row.channelId, scopeKind = kind)
}

internal fun CanonicalMessageFoldInput.toCanonicalMessageV2Envelope(
    partition: DurablePartitionKey,
    replyCount: Int,
    producerContextOverride: CanonicalConversationContextWire? = null,
): CanonicalMessageEnvelopeV2? {
    if (replyCount < 0) return null
    if (!row.hasCanonicalV2Scalars()) return null
    val requiredAggregates = setOf(
        MessageAggregateField.SenderName,
        MessageAggregateField.SenderDescription,
        MessageAggregateField.SenderMembershipStatus,
        MessageAggregateField.Mentions,
        MessageAggregateField.Attachments,
        MessageAggregateField.Reactions,
        MessageAggregateField.ActionMetadata,
    )
    if (!presentAggregates.containsAll(requiredAggregates)) return null
    if (producerContextOverride == null && MessageAggregateField.ConversationContext !in presentAggregates) return null
    val context = producerContextOverride ?: row.conversationContext ?: return null
    val normalizedReactions = normalizeLegacyReactionRoster(
        context = ReactionNormalizationContext(
            serverId = partition.serverId,
            messageId = row.id,
            parentScopeKey = ReactionParentScopeKey(partition.serverId, row.channelId, context.channelType),
            authority = ReactionIngressAuthority.SharedRoom,
        ),
        reactions = row.reactions.map { it.toLegacyReactionRosterDto() },
    ) as? ReactionNormalizationOutcome.Normalized ?: return null
    val actionMetadata = row.actionMetadata.toCanonicalActionMetadataV2OrNull() ?: if (row.actionMetadata != null) return null else null
    val fact = CanonicalMessageNodeV2(
        id = row.id,
        seq = row.seq,
        senderType = row.senderType,
        senderId = row.senderId,
        content = row.content,
        messageType = row.messageType,
        createdAt = row.createdAt,
        randomId = row.randomId,
        senderName = row.senderName,
        senderDescription = row.senderDescription,
        senderMembershipStatus = row.senderMembershipStatus,
        mentions = row.mentions.map { CanonicalMentionV2(it.id, it.name, it.type) },
        attachments = row.attachments.map {
            CanonicalAttachmentV2(
                id = it.id,
                filename = it.filename,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                width = it.width,
                height = it.height,
                thumbnailUrl = it.thumbnailUrl,
            )
        },
        reactions = normalizedReactions.value.sharedFact,
        actionMetadata = actionMetadata,
        replyCount = replyCount,
    )
    return (normalizeCanonicalMessageV2(
        CanonicalMessageV2NormalizationInput(
            partition = partition,
            scopeId = row.channelId,
            fact = fact,
            producerContext = context,
            childReplyScopeId = row.threadId,
        ),
    ) as? CanonicalMessageV2NormalizationResult.Normalized)?.envelope
}

/** Only for a seq at or above the window's oldest edge (below-window frames are never written). */
private fun PersistedCursor.advancedBy(seq: Long): PersistedCursor =
    copy(
        latestSeq = maxOf(latestSeq ?: seq, seq),
        oldestLoadedSeq = oldestLoadedSeq ?: seq,
        newestLoadedSeq = maxOf(newestLoadedSeq ?: seq, seq),
        hasLoadedNewest = true,
    )

private val canonicalV2ProducerJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class CanonicalActionMetadataProducer(
    val kind: String? = null,
    val version: Int? = null,
    val action: CanonicalActionCardActionV2? = null,
    val state: String = "prepared",
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    val result: CanonicalActionCardResultV2? = null,
    val forwardedItems: List<CanonicalForwardedBundleProducerItem> = emptyList(),
)

@Serializable
private data class CanonicalForwardedBundleProducerItem(
    val index: Int? = null,
    val sourceAuthorSnapshot: CanonicalForwardedAuthorProducer? = null,
    val sourceCreatedAt: String? = null,
    val sourceTargetSnapshot: CanonicalForwardedTargetProducer? = null,
    val contentSnapshot: String = "",
    val attachmentSnapshots: List<CanonicalForwardedAttachmentSnapshotV2> = emptyList(),
)

@Serializable
private data class CanonicalForwardedAuthorProducer(
    val type: String? = null,
    val name: String? = null,
    val uniqueName: String? = null,
)

@Serializable
private data class CanonicalForwardedTargetProducer(
    val type: String? = null,
    val label: String = "",
    val labelVisibility: String? = null,
)

private fun JsonElement?.toCanonicalActionMetadataV2OrNull(): CanonicalActionMetadataV2? {
    val element = this ?: return null
    if (!element.hasSupportedCanonicalActionProducerShape()) return null
    val producer = runCatching {
        canonicalV2ProducerJson.decodeFromString<CanonicalActionMetadataProducer>(element.toString())
    }.getOrNull() ?: return null
    return when (producer.kind) {
        "action-card" -> {
            val action = producer.action ?: return null
            if (action.type.isBlank()) return null
            CanonicalActionMetadataV2.ActionCard(
                version = producer.version,
                action = action,
                state = producer.state,
                executedAt = producer.executedAt,
                executedByUserName = producer.executedByUserName,
                result = producer.result,
            )
        }
        "forwarded-bundle" -> CanonicalActionMetadataV2.ForwardedBundle(
            version = producer.version,
            items = producer.forwardedItems.mapIndexed { fallbackIndex, item ->
                CanonicalForwardedBundleItemV2(
                    index = item.index ?: fallbackIndex,
                    author = item.sourceAuthorSnapshot?.let {
                        CanonicalForwardedAuthorSnapshotV2(it.type, it.name, it.uniqueName)
                    },
                    createdAt = item.sourceCreatedAt,
                    target = item.sourceTargetSnapshot?.let {
                        CanonicalForwardedTargetSnapshotV2(it.type, it.label, it.labelVisibility)
                    },
                    content = item.contentSnapshot,
                    attachments = item.attachmentSnapshots,
                )
            },
        )
        else -> null
    }
}

private fun CanonicalMessageRow.hasCanonicalV2Scalars(): Boolean =
    seq >= 0L &&
        listOf(id, channelId, senderType, senderId, messageType, createdAt, senderName).all { it.isCanonicalV2Value() } &&
        randomId?.isCanonicalV2Value() != false &&
        threadId?.isCanonicalV2Value() != false &&
        mentions.all { it.id.isCanonicalV2Value() && it.name.isCanonicalV2Value() && it.type.isCanonicalV2Value() } &&
        attachments.all {
            it.id.isCanonicalV2Value() &&
                it.filename.isCanonicalV2Value() &&
                it.mimeType.isCanonicalV2Value() &&
                it.sizeBytes >= 0L &&
                it.width?.let { width -> width >= 0 } != false &&
                it.height?.let { height -> height >= 0 } != false
        }

private fun String.isCanonicalV2Value(): Boolean = isNotBlank() && this == trim()

private fun JsonElement.hasSupportedCanonicalActionProducerShape(): Boolean {
    val root = this as? JsonObject ?: return false
    val kind = (root["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return when (kind) {
        "action-card" -> {
            if (!root.keys.all(ACTION_CARD_ROOT_KEYS::contains)) return false
            val action = root["action"] as? JsonObject ?: return false
            if (!action.keys.all(ACTION_CARD_ACTION_KEYS::contains)) return false
            val result = root["result"]
            result == null || result is JsonNull ||
                ((result as? JsonObject)?.keys?.all(ACTION_CARD_RESULT_KEYS::contains) == true)
        }
        "forwarded-bundle" -> {
            if (!root.keys.all(FORWARDED_ROOT_KEYS::contains)) return false
            val items = root["forwardedItems"] as? JsonArray ?: return false
            items.all { itemElement ->
                val item = itemElement as? JsonObject ?: return@all false
                if (!item.keys.all(FORWARDED_ITEM_KEYS::contains)) return@all false
                val author = item["sourceAuthorSnapshot"]
                if (author != null && author !is JsonNull &&
                    (author as? JsonObject)?.keys?.all(FORWARDED_AUTHOR_KEYS::contains) != true
                ) return@all false
                val target = item["sourceTargetSnapshot"]
                if (target != null && target !is JsonNull &&
                    (target as? JsonObject)?.keys?.all(FORWARDED_TARGET_KEYS::contains) != true
                ) return@all false
                val attachments = item["attachmentSnapshots"] as? JsonArray ?: return@all false
                attachments.all { attachment ->
                    (attachment as? JsonObject)?.keys?.all(FORWARDED_ATTACHMENT_KEYS::contains) == true
                }
            }
        }
        else -> false
    }
}

private val ACTION_CARD_ROOT_KEYS = setOf(
    "kind", "version", "action", "state", "executedAt", "executedByUserName", "result",
)
private val ACTION_CARD_ACTION_KEYS = setOf(
    "type", "name", "description", "visibility", "channel", "suggestedComputer", "requiredComputer",
    "humans", "agents", "initialHumans", "initialAgents", "draftHint", "requestId", "agentId",
    "agentName", "clientId", "clientKey", "clientName", "scopes",
)
private val ACTION_CARD_RESULT_KEYS = setOf(
    "kind", "id", "name", "channelId", "channelName", "addedHumanIds", "addedAgentIds",
)
private val FORWARDED_ROOT_KEYS = setOf("kind", "version", "forwardedItems")
private val FORWARDED_ITEM_KEYS = setOf(
    "index", "sourceMessageId", "sourceAuthorSnapshot", "sourceCreatedAt", "sourceTargetSnapshot",
    "contentSnapshot", "attachmentSnapshots", "provenanceState",
)
private val FORWARDED_AUTHOR_KEYS = setOf("type", "id", "name", "uniqueName")
private val FORWARDED_TARGET_KEYS = setOf("id", "type", "label", "labelVisibility")
private val FORWARDED_ATTACHMENT_KEYS = setOf("filename", "mimeType")
