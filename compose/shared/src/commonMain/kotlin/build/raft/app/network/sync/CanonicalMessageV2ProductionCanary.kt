package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * Task #168: frames of one scope kept while its background snapshot loads. One more marks the scope
 * discontinuous instead (the buffer is dropped and the next frame snapshots again): never an unbounded queue.
 */
internal const val CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP: Int = 64

enum class CanonicalMessageV2CanaryMode {
    Off,
    ShadowOnly,
    Activating,
    SoleApply,
    Broken,
}

sealed interface CanonicalMessageV2CanaryOutcome {
    /** This event remains on the existing T3-shadow + legacy projection path. */
    data object NotSelected : CanonicalMessageV2CanaryOutcome

    /** The V2 commit completed and the caller may project the event after a fresh grant check. */
    data object Committed : CanonicalMessageV2CanaryOutcome

    /**
     * Task #168: the durable write of this event belongs to the scope's background snapshot (scheduled by
     * this event, or already loading and this event is buffered for it). Nothing is written yet and the
     * caller never waits for the network: it projects the event now through the legacy display path after
     * a fresh grant check, exactly as for [Committed].
     */
    data object Pending : CanonicalMessageV2CanaryOutcome

    /**
     * The durable V2 write did not happen (revoked, evicted, poisoned). Nothing was written; the caller
     * still projects the event through the legacy display path after a fresh grant check (task #168: a
     * failed durable write must never hide a message).
     */
    data class Held(val diagnostic: String) : CanonicalMessageV2CanaryOutcome
}

data class CanonicalMessageV2SnapshotRequest(
    val scope: SyncScopeKey,
    val targetFactId: String,
)

sealed interface CanonicalMessageV2SnapshotLoadResult {
    data class Loaded(val frame: CanonicalMessageV2SnapshotFrame) : CanonicalMessageV2SnapshotLoadResult
    data class Unavailable(val diagnostic: String) : CanonicalMessageV2SnapshotLoadResult
}

fun interface CanonicalMessageV2SnapshotLoader {
    suspend fun load(request: CanonicalMessageV2SnapshotRequest): CanonicalMessageV2SnapshotLoadResult
}

private class CanonicalMessageV2CanaryScopeState(
    var initialized: Boolean = false,
    var mode: CanonicalMessageV2CanaryMode = CanonicalMessageV2CanaryMode.Activating,
    var cursor: PersistedCursor? = null,
    /**
     * Task #167: the realtime transport epoch this scope last committed under. Frames delivered while the
     * socket was down never reach the writer, so a frame from a different epoch must not be appended to
     * this window; it re-snapshots first. Null until the first commit.
     */
    var committedTransportEpoch: Long? = null,
    /**
     * A window restored from disk has no known epoch: frames may have been missed while the process was dead.
     * Task #168 reuses it as the scope's "discontinuous" mark: the next frame snapshots again.
     */
    var restoredWithoutEpoch: Boolean = false,
) {
    /**
     * Task #168: the background snapshot this scope is waiting for, if any. Confined to the scope writer;
     * at most one per scope.
     */
    var pendingSnapshot: CanonicalMessageV2PendingSnapshot? = null

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

    fun appendsContinuously(currentTransportEpoch: Long?): Boolean =
        !restoredWithoutEpoch && committedTransportEpoch == currentTransportEpoch

    fun committedUnder(currentTransportEpoch: Long?) {
        committedTransportEpoch = currentTransportEpoch
        restoredWithoutEpoch = false
    }

    /** Task #168: the window may miss frames from here on; the next frame snapshots instead of appending. */
    fun markDiscontinuous() {
        restoredWithoutEpoch = true
    }

    private companion object {
        const val COMMITTED_DELIVERY_MEMORY = 256
    }
}

/** One delivery of a realtime frame: the exact decoded frame and the connection that delivered it. */
private data class CanonicalMessageV2Delivery(
    val foldInput: CanonicalMessageFoldInput,
    val frameTransportEpoch: Long?,
)

/** A frame that arrived while its scope's snapshot was loading, with the epoch it was accepted under. */
private class CanonicalMessageV2BufferedFrame(
    val delivery: CanonicalMessageV2Delivery,
    /** The frame's own epoch, else the partition's recorded epoch when it was accepted (as #2991). */
    val transportEpoch: Long?,
)

/**
 * Task #168: one background snapshot of one scope. Everything that decides whether it may still commit is
 * captured at schedule time ([partitionGeneration], [epochAtRequest], the [state] object it belongs to) and
 * re-checked inside the scope writer before the commit.
 */
private class CanonicalMessageV2PendingSnapshot(
    val scope: SyncScopeKey,
    val state: CanonicalMessageV2CanaryScopeState,
    val targetFactId: String,
    val partitionGeneration: Long,
    /** The connection the snapshot is known to be continuous with: the frame's own when it names one. */
    val epochAtRequest: Long?,
    /** The delivery that scheduled it: written by the snapshot itself. */
    val trigger: CanonicalMessageV2Delivery,
    /** The loader it was scheduled with: reused when an external commit supersedes it ([onExternalCommit]). */
    val snapshotLoader: CanonicalMessageV2SnapshotLoader,
) {
    /** Writer-confined. */
    val buffered = ArrayList<CanonicalMessageV2BufferedFrame>()

    /** Writer-confined. */
    var overflowed = false

    /** Guarded by the canary's selection mutex. */
    var job: Job? = null

    /** Guarded by the canary's selection mutex: set once the job has entered the writer to commit. */
    var committing = false

    /** Guarded by the canary's selection mutex: reset / eviction / revoke dropped this snapshot. */
    var cancelled = false

    fun alreadyHolds(delivery: CanonicalMessageV2Delivery): Boolean =
        delivery == trigger || buffered.any { it.delivery == delivery }
}

private sealed interface CanonicalMessageV2AppendResult {
    data object Committed : CanonicalMessageV2AppendResult
    data object BelowWindow : CanonicalMessageV2AppendResult
    data object NotFullV2 : CanonicalMessageV2AppendResult
    data object BaselineRequired : CanonicalMessageV2AppendResult
    data object PartitionRevoked : CanonicalMessageV2AppendResult
    data object ScopeEvicted : CanonicalMessageV2AppendResult
}

/**
 * One-product-flag, one-scope production canary.
 *
 * The first eligible top-level scope on an enabled server owns that server's process-local canary slot.
 * Its first event must establish a fresh server-backed V2 snapshot. Exact later MessageNew frames commit
 * incrementally; MessageUpdated and any input that cannot prove a full V2 node take the safe snapshot path.
 * A failure never writes, and never hides the event: the caller still displays it (task #168). Other
 * scopes retain the existing shadow/legacy path.
 *
 * Task #168: display never waits for the network. A snapshot is never loaded inside [accept]: it is
 * scheduled as one serialized background job per scope ([snapshotJobScope]); frames of that scope that
 * arrive meanwhile are displayed and buffered (bounded), then replayed after the commit only when they are
 * provably continuous with it.
 */
internal class CanonicalMessageV2ProductionCanary(
    private val isEnabledForServer: (String) -> Boolean,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val logger: (String) -> Unit = {},
    private val snapshotJobScope: CoroutineScope = defaultSnapshotJobScope(logger),
) {
    private data class MutexEntry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val selectionMutex = Mutex()
    private val selectedScopeByPartition = mutableMapOf<DurablePartitionKey, SyncScopeKey>()
    private val scopeMutexByKey = mutableMapOf<SyncScopeKey, MutexEntry>()
    private val stateByScope = mutableMapOf<SyncScopeKey, CanonicalMessageV2CanaryScopeState>()
    private val currentTransportEpochByPartition = mutableMapOf<DurablePartitionKey, Long>()

    /** Guarded by [selectionMutex]: the live background snapshot of each scope (cancellation handle). */
    private val pendingSnapshotByScope = mutableMapOf<SyncScopeKey, CanonicalMessageV2PendingSnapshot>()

    /** Guarded by [selectionMutex]: launched snapshot jobs not yet seen completed. */
    private val launchedSnapshotJobs = mutableListOf<Job>()

    init {
        generationEngine.addPartitionRevocationObserver(::onPartitionRevoked)
        generationEngine.addScopeInvalidationObserver(::onScopesInvalidated)
    }

    suspend fun accept(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        snapshotLoader: CanonicalMessageV2SnapshotLoader,
        frameTransportEpoch: Long? = null,
    ): CanonicalMessageV2CanaryOutcome {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val partitionGeneration = generationEngine.partitionGeneration(partition)
        val existingSelection = selectionMutex.withLock { selectedScopeByPartition[partition] }
        val scope = when {
            existingSelection?.scopeId == foldInput.row.channelId -> existingSelection
            existingSelection != null -> return CanonicalMessageV2CanaryOutcome.NotSelected
            else -> foldInput.v2ScopeOrNull(partition) ?: return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val selected = selectionMutex.withLock {
            val current = selectedScopeByPartition[partition]
            when {
                current != null -> current == scope
                !isEnabledForServer(partition.serverId) -> false
                else -> {
                    selectedScopeByPartition[partition] = scope
                    logger("canonical_v2_canary mode=activating server=${partition.serverId} scope=${scope.scopeId}")
                    true
                }
            }
        }
        if (!selected) return CanonicalMessageV2CanaryOutcome.NotSelected

        return withScopeMutex(scope) {
            generationEngine.withScopeWriter(scope) writer@{
                val state = selectionMutex.withLock {
                    stateByScope.getOrPut(scope) { CanonicalMessageV2CanaryScopeState() }
                }
                if (!isEnabledForServer(partition.serverId)) {
                    return@writer revokeSelection(scope, state)
                }
                if (!CANONICAL_MESSAGE_FULL_V2_SOLE_APPLY_ELIGIBLE) {
                    state.mode = CanonicalMessageV2CanaryMode.ShadowOnly
                    return@writer CanonicalMessageV2CanaryOutcome.NotSelected
                }

                val delivery = CanonicalMessageV2Delivery(foldInput, frameTransportEpoch?.takeIf { it > 0L })
                if (state.isAlreadyCommitted(delivery)) {
                    // The same frame through another entry: it is on disk already. Never write it again.
                    logger(
                        "canonical_v2_canary skip=duplicate_delivery server=${scope.serverId} " +
                            "scope=${scope.scopeId}",
                    )
                    return@writer CanonicalMessageV2CanaryOutcome.Committed
                }
                acceptInsideWriter(scope, partition, delivery, state, snapshotLoader, partitionGeneration)
                    .also { outcome ->
                        if (outcome == CanonicalMessageV2CanaryOutcome.Committed) state.recordCommitted(delivery)
                    }
            }
        }
    }

    private suspend fun acceptInsideWriter(
        scope: SyncScopeKey,
        partition: DurablePartitionKey,
        delivery: CanonicalMessageV2Delivery,
        state: CanonicalMessageV2CanaryScopeState,
        snapshotLoader: CanonicalMessageV2SnapshotLoader,
        partitionGeneration: Long,
    ): CanonicalMessageV2CanaryOutcome {
        val foldInput = delivery.foldInput
        if (!state.initialized) {
            val restored = try {
                generationEngine.restore(scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                state.mode = CanonicalMessageV2CanaryMode.Broken
                logger(
                    "canonical_v2_canary mode=broken server=${scope.serverId} scope=${scope.scopeId} " +
                        "reason=restore_${throwable::class.simpleName ?: "unknown"}",
                )
                return CanonicalMessageV2CanaryOutcome.Held(CANONICAL_MESSAGE_V2_SCOPE_POISONED)
            }
            state.initialized = true
            state.cursor = restored.cursor
            // A window that survived a process restart may have missed frames meanwhile.
            state.restoredWithoutEpoch = restored.cursor != null
            state.mode =
                if (restored.cursor == null) CanonicalMessageV2CanaryMode.Activating
                else CanonicalMessageV2CanaryMode.SoleApply
        }
        state.cursor = generationEngine.currentCursor(scope)
        if (state.cursor == null && state.pendingSnapshot == null) state.mode = CanonicalMessageV2CanaryMode.Activating

        // A frame that names its connection decides by that: the reconnect signal travels through
        // another coroutine and may not have been recorded yet, or may already name a newer socket.
        // Otherwise read the recorded epoch inside this scope's writer, at commit time.
        val frameEpoch = delivery.frameTransportEpoch
        val committedEpoch = state.committedTransportEpoch
        if (
            frameEpoch != null &&
            committedEpoch != null &&
            !state.restoredWithoutEpoch &&
            frameEpoch < committedEpoch
        ) {
            // A late frame of an older socket: the window already continues from a newer
            // connection, and frames that socket lost may sit between. Show it, never write it.
            // Epochs only grow within a process on every host; a restart restores without one.
            logger(
                "canonical_v2_canary skip=stale_transport server=${scope.serverId} scope=${scope.scopeId} " +
                    "frameEpoch=$frameEpoch committedEpoch=$committedEpoch",
            )
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val transportEpoch = frameEpoch ?: currentTransportEpoch(scope.partition)

        val pending = state.pendingSnapshot
        if (pending != null) return bufferWhileSnapshotLoads(scope, pending, delivery, transportEpoch)

        if (
            foldInput.kind == CanonicalMessageFoldKind.New &&
            state.cursor != null &&
            state.appendsContinuously(transportEpoch)
        ) {
            when (appendNewInsideWriter(scope, partition, foldInput, state, partitionGeneration, transportEpoch)) {
                CanonicalMessageV2AppendResult.Committed -> return CanonicalMessageV2CanaryOutcome.Committed
                // Display only (logged inside).
                CanonicalMessageV2AppendResult.BelowWindow -> return CanonicalMessageV2CanaryOutcome.NotSelected
                CanonicalMessageV2AppendResult.PartitionRevoked -> return heldAfterPartitionRevoke(scope, state)
                CanonicalMessageV2AppendResult.ScopeEvicted -> return heldAfterScopeEviction(scope, state)
                CanonicalMessageV2AppendResult.NotFullV2,
                CanonicalMessageV2AppendResult.BaselineRequired,
                -> Unit
            }
        }

        return scheduleSnapshot(scope, partition, delivery, state, snapshotLoader, partitionGeneration)
    }

    /**
     * Appends one `New` to a continuous window, inside the scope writer. Shared by the realtime path and the
     * post-snapshot replay so both keep #3002's rules (insert-only, below-window skip, committed cursor).
     */
    private suspend fun appendNewInsideWriter(
        scope: SyncScopeKey,
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        state: CanonicalMessageV2CanaryScopeState,
        partitionGeneration: Long,
        transportEpoch: Long?,
    ): CanonicalMessageV2AppendResult {
        val windowOldest = state.cursor!!.oldestLoadedSeq
        if (windowOldest != null && foldInput.row.seq < windowOldest) {
            // Older than the stored window: nothing says the messages between it and the window's
            // oldest edge are on disk, so writing it (and stretching the edge down to it) would claim
            // a range never loaded. Show it, never write it.
            logger(
                "canonical_v2_canary skip=below_window server=${scope.serverId} scope=${scope.scopeId} " +
                    "seq=${foldInput.row.seq} oldestLoadedSeq=$windowOldest",
            )
            return CanonicalMessageV2AppendResult.BelowWindow
        }
        val envelope = foldInput.toCanonicalMessageV2Envelope(partition, replyCount = 0)
            ?: return CanonicalMessageV2AppendResult.NotFullV2
        val cursor = state.cursor!!.advancedBy(envelope.fact.seq)
        return when (
            generationEngine.applyIncremental(
                // A `New` only ever adds: a stored fact may already be newer (edited, reacted).
                CanonicalMessageV2IncrementalFrame(scope, listOf(envelope), cursor, insertOnly = true),
                expectedPartitionGeneration = partitionGeneration,
            )
        ) {
            CanonicalMessageV2CommitResult.Committed -> {
                state.cursor = generationEngine.currentCursor(scope) ?: cursor
                state.committedUnder(transportEpoch)
                state.mode = CanonicalMessageV2CanaryMode.SoleApply
                logger(
                    "canonical_v2_canary mode=sole_apply server=${scope.serverId} " +
                        "scope=${scope.scopeId} source=incremental",
                )
                CanonicalMessageV2AppendResult.Committed
            }
            is CanonicalMessageV2CommitResult.BaselineRequired -> CanonicalMessageV2AppendResult.BaselineRequired
            CanonicalMessageV2CommitResult.PartitionRevoked -> CanonicalMessageV2AppendResult.PartitionRevoked
            CanonicalMessageV2CommitResult.ScopeEvicted -> CanonicalMessageV2AppendResult.ScopeEvicted
        }
    }

    /** Inside the scope writer, with [pending] still loading: display now, keep the frame for the replay. */
    private fun bufferWhileSnapshotLoads(
        scope: SyncScopeKey,
        pending: CanonicalMessageV2PendingSnapshot,
        delivery: CanonicalMessageV2Delivery,
        transportEpoch: Long?,
    ): CanonicalMessageV2CanaryOutcome {
        when {
            // The same frame through another entry: kept once (task #168 #5, one write per frame).
            pending.alreadyHolds(delivery) -> logger(
                "canonical_v2_canary skip=duplicate_delivery server=${scope.serverId} " +
                    "scope=${scope.scopeId} pending=snapshot",
            )
            pending.overflowed -> Unit
            pending.buffered.size >= CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP -> {
                pending.overflowed = true
                pending.buffered.clear()
                logger(
                    "canonical_v2_canary snapshot=buffer_overflow server=${scope.serverId} " +
                        "scope=${scope.scopeId} cap=$CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP",
                )
            }
            else -> pending.buffered += CanonicalMessageV2BufferedFrame(delivery, transportEpoch)
        }
        return CanonicalMessageV2CanaryOutcome.Pending
    }

    /** Inside the scope writer: never loads. Starts the scope's one background snapshot and returns. */
    private suspend fun scheduleSnapshot(
        scope: SyncScopeKey,
        partition: DurablePartitionKey,
        trigger: CanonicalMessageV2Delivery,
        state: CanonicalMessageV2CanaryScopeState,
        snapshotLoader: CanonicalMessageV2SnapshotLoader,
        partitionGeneration: Long,
    ): CanonicalMessageV2CanaryOutcome {
        state.mode = CanonicalMessageV2CanaryMode.Activating
        // The snapshot is only known to be continuous with the connection that was current when it was
        // requested (the frame's own connection when it names one). If the transport reconnects while it
        // loads, the next frame must snapshot again.
        val epochAtRequest = trigger.frameTransportEpoch ?: currentTransportEpoch(scope.partition)
        val pending = CanonicalMessageV2PendingSnapshot(
            scope = scope,
            state = state,
            targetFactId = trigger.foldInput.row.id,
            partitionGeneration = partitionGeneration,
            epochAtRequest = epochAtRequest,
            trigger = trigger,
            snapshotLoader = snapshotLoader,
        )
        state.pendingSnapshot = pending
        val scheduled = selectionMutex.withLock {
            // Reset / eviction may have dropped this state while the writer was waiting.
            if (stateByScope[scope] !== state) return@withLock false
            val job = snapshotJobScope.launch(start = CoroutineStart.LAZY) {
                runSnapshotJob(pending, partition, snapshotLoader)
            }
            pending.job = job
            pendingSnapshotByScope[scope] = pending
            launchedSnapshotJobs.removeAll { it.isCompleted }
            launchedSnapshotJobs += job
            true
        }
        if (!scheduled) {
            state.pendingSnapshot = null
            return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_scope_reset")
        }
        logger(
            "canonical_v2_canary snapshot=scheduled server=${scope.serverId} scope=${scope.scopeId} " +
                "epochAtRequest=$epochAtRequest",
        )
        // Started only once every handle is in place; it loads outside every lock.
        pending.job?.start()
        return CanonicalMessageV2CanaryOutcome.Pending
    }

    /** The background half: load outside every lock, then commit + replay inside the scope writer. */
    private suspend fun runSnapshotJob(
        pending: CanonicalMessageV2PendingSnapshot,
        partition: DurablePartitionKey,
        snapshotLoader: CanonicalMessageV2SnapshotLoader,
    ) {
        val scope = pending.scope
        val loaded = try {
            snapshotLoader.load(CanonicalMessageV2SnapshotRequest(scope, pending.targetFactId))
        } catch (cancelled: CancellationException) {
            // Our own cancellation (reset / eviction / revoke) ends the job; a transport that reports a
            // failure as a cancellation is a failed load, never a stuck scope.
            if (!currentCoroutineContext().isActive) throw cancelled
            CanonicalMessageV2SnapshotLoadResult.Unavailable("canonical_v2_snapshot_cancelled")
        } catch (throwable: Throwable) {
            CanonicalMessageV2SnapshotLoadResult.Unavailable(
                "canonical_v2_snapshot_${throwable::class.simpleName ?: "failed"}",
            )
        }
        // A load that returned despite a cancellation (a transport that ignores it) still reaches the writer:
        // the re-check there is the one guard against a stale write, never where a cancellation lands. The
        // lock bookkeeping always completes.
        withContext(NonCancellable) { commitInsideWriter(pending, partition, loaded) }
    }

    private suspend fun commitInsideWriter(
        pending: CanonicalMessageV2PendingSnapshot,
        partition: DurablePartitionKey,
        loaded: CanonicalMessageV2SnapshotLoadResult,
    ) {
        val scope = pending.scope
        withScopeMutex<Unit>(scope) {
            generationEngine.withScopeWriter<Unit>(scope) writer@{
                val stillCurrent = selectionMutex.withLock {
                    val current = !pending.cancelled &&
                        pendingSnapshotByScope[scope] === pending &&
                        stateByScope[scope] === pending.state &&
                        selectedScopeByPartition[scope.partition] == scope
                    if (current) {
                        pending.committing = true
                        pendingSnapshotByScope.remove(scope)
                    }
                    current
                }
                if (!stillCurrent) {
                    // Reset, server/account switch, eviction or revoke happened meanwhile: this job
                    // belongs to a session that no longer exists. Never write.
                    logger(
                        "canonical_v2_canary snapshot=stale_dropped server=${scope.serverId} " +
                            "scope=${scope.scopeId} buffered=${pending.buffered.size}",
                    )
                    // Never leave a state waiting for a job that will not commit (it would buffer forever).
                    if (pending.state.pendingSnapshot === pending) {
                        pending.state.pendingSnapshot = null
                        pending.state.markDiscontinuous()
                    }
                    pending.buffered.clear()
                    return@writer
                }
                val state = pending.state
                if (state.pendingSnapshot === pending) state.pendingSnapshot = null
                try {
                    if (!isEnabledForServer(scope.serverId)) {
                        generationEngine.rebaselineFailed(scope)
                        state.markDiscontinuous()
                        logger(
                            "canonical_v2_canary snapshot=dropped server=${scope.serverId} " +
                                "scope=${scope.scopeId} reason=flag_revoked",
                        )
                        return@writer
                    }
                    val outcome = commitSnapshot(
                        scope,
                        pending.targetFactId,
                        state,
                        loaded,
                        pending.partitionGeneration,
                        pending.epochAtRequest,
                    )
                    if (outcome == CanonicalMessageV2CanaryOutcome.Committed) {
                        state.recordCommitted(pending.trigger)
                        replayBuffered(
                            pending,
                            partition,
                            (loaded as CanonicalMessageV2SnapshotLoadResult.Loaded).frame,
                        )
                    } else {
                        // Nothing was written: whatever was buffered is lost to the window.
                        state.markDiscontinuous()
                        if (pending.buffered.isNotEmpty() || pending.overflowed) {
                            logger(
                                "canonical_v2_canary snapshot=buffer_dropped server=${scope.serverId} " +
                                    "scope=${scope.scopeId} buffered=${pending.buffered.size}",
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    generationEngine.rebaselineFailed(scope)
                    state.markDiscontinuous()
                    state.mode = CanonicalMessageV2CanaryMode.Broken
                    logger(
                        "canonical_v2_canary mode=broken server=${scope.serverId} scope=${scope.scopeId} " +
                            "reason=snapshot_job_${throwable::class.simpleName ?: "failed"}",
                    )
                } finally {
                    pending.buffered.clear()
                }
            }
        }
    }

    /**
     * Inside the scope writer, right after [pending]'s snapshot committed under its request epoch. A buffered
     * frame is appended only when it is provably continuous with that snapshot: a `New` of the same epoch
     * with a seq above the snapshot's latestSeq. A frame the snapshot already holds is not written again; an
     * older connection's frame is display-only; anything else marks the scope discontinuous.
     */
    private suspend fun replayBuffered(
        pending: CanonicalMessageV2PendingSnapshot,
        partition: DurablePartitionKey,
        snapshot: CanonicalMessageV2SnapshotFrame,
    ) {
        val scope = pending.scope
        val state = pending.state
        fun discontinuous(reason: String) {
            state.markDiscontinuous()
            logger(
                "canonical_v2_canary snapshot=replay_discontinuous server=${scope.serverId} " +
                    "scope=${scope.scopeId} reason=$reason",
            )
        }
        if (pending.overflowed) return discontinuous("buffer_overflow")
        val committedEpoch = pending.epochAtRequest
        val snapshotLatestSeq = state.cursor?.latestSeq ?: snapshot.cursor.latestSeq
        val snapshotFactIds = snapshot.envelopes.mapTo(HashSet()) { it.fact.id }
        for (buffered in pending.buffered) {
            val delivery = buffered.delivery
            val input = delivery.foldInput
            if (state.isAlreadyCommitted(delivery)) continue
            val epoch = buffered.transportEpoch
            if (epoch != null && committedEpoch != null && epoch < committedEpoch) {
                // A late frame of an older socket (as #2991): shown when it arrived, never written.
                logger(
                    "canonical_v2_canary skip=stale_transport server=${scope.serverId} scope=${scope.scopeId} " +
                        "frameEpoch=$epoch committedEpoch=$committedEpoch replay=true",
                )
                continue
            }
            if (epoch != committedEpoch) return discontinuous("epoch_mismatch")
            if (input.kind != CanonicalMessageFoldKind.New) return discontinuous("buffered_${input.kind.name.lowercase()}")
            val seq = input.row.seq
            if (snapshotLatestSeq != null && seq <= snapshotLatestSeq) {
                if (input.row.id in snapshotFactIds) {
                    // The snapshot already holds it: never written twice.
                    state.recordCommitted(delivery)
                    continue
                }
                val windowOldest = state.cursor?.oldestLoadedSeq
                if (windowOldest != null && seq < windowOldest) {
                    logger(
                        "canonical_v2_canary skip=below_window server=${scope.serverId} scope=${scope.scopeId} " +
                            "seq=$seq oldestLoadedSeq=$windowOldest replay=true",
                    )
                    continue
                }
                return discontinuous("missing_from_snapshot")
            }
            if (state.cursor == null) return discontinuous("no_window")
            when (appendNewInsideWriter(scope, partition, input, state, pending.partitionGeneration, committedEpoch)) {
                CanonicalMessageV2AppendResult.Committed -> state.recordCommitted(delivery)
                CanonicalMessageV2AppendResult.BelowWindow -> Unit
                CanonicalMessageV2AppendResult.NotFullV2 -> return discontinuous("not_full_v2")
                CanonicalMessageV2AppendResult.BaselineRequired -> return discontinuous("baseline_required")
                CanonicalMessageV2AppendResult.PartitionRevoked -> {
                    heldAfterPartitionRevoke(scope, state)
                    return
                }
                CanonicalMessageV2AppendResult.ScopeEvicted -> {
                    heldAfterScopeEviction(scope, state)
                    return
                }
            }
        }
    }

    private suspend fun <T> withScopeMutex(
        scope: SyncScopeKey,
        block: suspend () -> T,
    ): T {
        val entry = selectionMutex.withLock {
            scopeMutexByKey.getOrPut(scope) { MutexEntry() }.also { it.users += 1 }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            selectionMutex.withLock {
                entry.users -= 1
                if (entry.users == 0 && scopeMutexByKey[scope] === entry) {
                    scopeMutexByKey.remove(scope)
                }
            }
        }
    }

    /**
     * Task #167: the realtime transport serving [partition] (re)connected as [transportEpoch]. Posted by
     * the native host before any frame of the new socket. Recorded per partition; every scope compares it
     * with the epoch it last committed under, inside its own writer.
     */
    suspend fun noteTransportEpoch(partition: DurablePartitionKey, transportEpoch: Long) {
        if (transportEpoch <= 0L) return
        // Any different value is a new connection. Hosts number sockets increasingly within a process, but
        // the signal stays tolerant: a late old signal costs at most one extra snapshot.
        selectionMutex.withLock { currentTransportEpochByPartition[partition] = transportEpoch }
    }

    private suspend fun currentTransportEpoch(partition: DurablePartitionKey): Long? =
        selectionMutex.withLock { currentTransportEpochByPartition[partition] }

    /**
     * Read-only view for writers outside the canary (task #1124 enter-snapshot): whether [scope] is
     * the scope this canary currently owns for its partition. Never selects, never widens.
     */
    suspend fun isSelected(scope: SyncScopeKey): Boolean =
        selectionMutex.withLock { selectedScopeByPartition[scope.partition] == scope }

    /**
     * Task #168: a writer outside the canary (enter snapshot, adjacent page, gap catch-up) has just committed
     * into [scope]. MUST be called inside `generationEngine.withScopeWriter(scope)`, right after that commit
     * returned `Committed`, so it is serialized with every canary write of the scope.
     *
     * The canary did not produce that window, so:
     * 1. the scope is marked discontinuous: the next realtime frame re-snapshots (#2991 rules) instead of
     *    appending onto a window whose continuity with the realtime stream nothing proves;
     * 2. a background snapshot still pending for the scope is superseded by an explicit signal, never by
     *    comparing seqs: it is cancelled exactly like a reset cancels it (`cancelled`, removed from
     *    [pendingSnapshotByScope], so its in-writer re-check drops it), because it was requested before this
     *    commit and could otherwise overwrite the newer window with an older page. Its buffered frames are
     *    dropped (they were displayed when they arrived), and ONE fresh snapshot is scheduled with the same
     *    trigger: it loads after this commit, so it is never older than the external window, and it puts the
     *    trigger frame (and everything buffered behind it) on disk even when no further frame arrives.
     *
     * No-op when the canary holds no state for [scope] (not selected, or not yet initialized: a state created
     * later restores from disk without an epoch, which already re-snapshots).
     *
     * Locks: only [selectionMutex], a leaf lock (the canary's own scope mutex is ordered BEFORE the scope
     * writer, so it is never taken here). Nothing of the realtime grant / authority registry is touched.
     */
    suspend fun onExternalCommit(scope: SyncScopeKey): Unit = withContext(NonCancellable) {
        // The commit already happened: a cancellation of the caller must not skip the mark.
        noteExternalCommitInsideWriter(scope)
    }

    private suspend fun noteExternalCommitInsideWriter(scope: SyncScopeKey) {
        val state = selectionMutex.withLock {
            val state = stateByScope[scope] ?: return
            val superseded = state.pendingSnapshot
            if (superseded != null && pendingSnapshotByScope[scope] === superseded) {
                cancelPendingSnapshotsLocked { it == scope }
            }
            state
        }
        val superseded = state.pendingSnapshot
        state.pendingSnapshot = null
        state.cursor = generationEngine.currentCursor(scope)
        state.markDiscontinuous()
        logger(
            "canonical_v2_canary external_commit server=${scope.serverId} scope=${scope.scopeId} " +
                "superseded=${superseded != null}",
        )
        if (superseded == null) return
        if (superseded.buffered.isNotEmpty() || superseded.overflowed) {
            logger(
                "canonical_v2_canary snapshot=buffer_dropped server=${scope.serverId} " +
                    "scope=${scope.scopeId} buffered=${superseded.buffered.size} reason=external_commit",
            )
        }
        superseded.buffered.clear()
        scheduleSnapshot(
            scope = scope,
            partition = scope.partition,
            trigger = superseded.trigger,
            state = state,
            snapshotLoader = superseded.snapshotLoader,
            partitionGeneration = superseded.partitionGeneration,
        )
    }

    suspend fun modeFor(serverId: String, scopeId: String): CanonicalMessageV2CanaryMode =
        selectionMutex.withLock {
            val selected = selectedScopeByPartition.values.firstOrNull { it.serverId == serverId }
                ?: return@withLock CanonicalMessageV2CanaryMode.Off
            if (selected.scopeId != scopeId) return@withLock CanonicalMessageV2CanaryMode.ShadowOnly
            stateByScope[selected]?.mode ?: CanonicalMessageV2CanaryMode.Activating
        }

    /**
     * Suspends until no background snapshot job is running (including ones started by the jobs awaited).
     * Never starts, cancels or reorders work.
     */
    internal suspend fun awaitSnapshotJobs() {
        while (true) {
            val jobs = selectionMutex.withLock {
                launchedSnapshotJobs.removeAll { it.isCompleted }
                launchedSnapshotJobs.toList()
            }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    /** The scopes with a live (not cancelled, not yet committing) background snapshot. */
    internal suspend fun pendingSnapshotScopes(): Set<SyncScopeKey> =
        selectionMutex.withLock { pendingSnapshotByScope.keys.toSet() }

    /** Inside the scope writer, with the snapshot already loaded: never touches the network. */
    private suspend fun commitSnapshot(
        scope: SyncScopeKey,
        targetFactId: String,
        state: CanonicalMessageV2CanaryScopeState,
        loaded: CanonicalMessageV2SnapshotLoadResult,
        partitionGeneration: Long,
        epochAtRequest: Long?,
    ): CanonicalMessageV2CanaryOutcome =
        when (loaded) {
            is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                generationEngine.rebaselineFailed(scope)
                state.mode = CanonicalMessageV2CanaryMode.Broken
                logger(
                    "canonical_v2_canary mode=broken server=${scope.serverId} scope=${scope.scopeId} " +
                        "reason=${loaded.diagnostic}",
                )
                CanonicalMessageV2CanaryOutcome.Held(loaded.diagnostic)
            }
            is CanonicalMessageV2SnapshotLoadResult.Loaded -> {
                val frame = loaded.frame
                if (frame.scope != scope || frame.envelopes.none { it.fact.id == targetFactId }) {
                    generationEngine.rebaselineFailed(scope)
                    state.mode = CanonicalMessageV2CanaryMode.Broken
                    logger(
                        "canonical_v2_canary mode=broken server=${scope.serverId} scope=${scope.scopeId} " +
                            "reason=canonical_v2_snapshot_missing_target",
                    )
                    CanonicalMessageV2CanaryOutcome.Held("canonical_v2_snapshot_missing_target")
                } else {
                    try {
                        when (
                            generationEngine.applySnapshot(
                                frame,
                                expectedPartitionGeneration = partitionGeneration,
                            )
                        ) {
                            CanonicalMessageV2CommitResult.Committed -> {
                                state.cursor = generationEngine.currentCursor(scope) ?: frame.cursor
                                state.committedUnder(epochAtRequest)
                                state.mode = CanonicalMessageV2CanaryMode.SoleApply
                                logger(
                                    "canonical_v2_canary mode=sole_apply server=${scope.serverId} " +
                                        "scope=${scope.scopeId} source=snapshot",
                                )
                                CanonicalMessageV2CanaryOutcome.Committed
                            }
                            CanonicalMessageV2CommitResult.PartitionRevoked ->
                                heldAfterPartitionRevoke(scope, state)
                            CanonicalMessageV2CommitResult.ScopeEvicted ->
                                heldAfterScopeEviction(scope, state)
                            is CanonicalMessageV2CommitResult.BaselineRequired ->
                                error("snapshot commit cannot require a baseline")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        generationEngine.rebaselineFailed(scope)
                        state.mode = CanonicalMessageV2CanaryMode.Broken
                        CanonicalMessageV2CanaryOutcome.Held(
                            "canonical_v2_snapshot_${throwable::class.simpleName ?: "failed"}",
                        )
                    }
                }
            }
        }

    /**
     * The flag went off for the selected scope: drop its V2 window and hand the scope back to the legacy
     * path. Task #168: no snapshot barrier any more (it loaded inline, and the window it wrote was cleared
     * right after anyway); a loading snapshot is cancelled and can never write.
     */
    private suspend fun revokeSelection(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
    ): CanonicalMessageV2CanaryOutcome {
        if (state.mode == CanonicalMessageV2CanaryMode.Off) return CanonicalMessageV2CanaryOutcome.NotSelected
        selectionMutex.withLock { cancelPendingSnapshotsLocked { it == scope } }
        state.pendingSnapshot = null
        generationEngine.clearScope(scope)
        selectionMutex.withLock {
            if (selectedScopeByPartition[scope.partition] == scope) {
                selectedScopeByPartition.remove(scope.partition)
            }
            stateByScope.remove(scope)
        }
        logger("canonical_v2_canary mode=off server=${scope.serverId} scope=${scope.scopeId} reason=flag_revoked")
        return CanonicalMessageV2CanaryOutcome.NotSelected
    }

    private suspend fun onPartitionRevoked(partition: DurablePartitionKey) {
        selectionMutex.withLock {
            selectedScopeByPartition.remove(partition)
            currentTransportEpochByPartition.remove(partition)
            stateByScope.keys.removeAll { it.partition == partition }
            cancelPendingSnapshotsLocked { it.partition == partition }
        }
    }

    private suspend fun onScopesInvalidated(scopes: Set<SyncScopeKey>) {
        selectionMutex.withLock {
            selectedScopeByPartition.entries.removeAll { it.value in scopes }
            stateByScope.keys.removeAll { it in scopes }
            cancelPendingSnapshotsLocked { it in scopes }
        }
    }

    /**
     * Holding [selectionMutex]. The dropped snapshot can never commit (re-checked in its writer) and its
     * buffer dies with it. A job already committing is not cancelled: it is inside the partition mutation
     * that serializes it against the reset, whose generation check rejects its write if it came second.
     */
    private fun cancelPendingSnapshotsLocked(predicate: (SyncScopeKey) -> Boolean) {
        val dropped = pendingSnapshotByScope.entries.filter { predicate(it.key) }
        dropped.forEach { (scope, pending) ->
            pending.cancelled = true
            if (!pending.committing) pending.job?.cancel()
            pendingSnapshotByScope.remove(scope)
            logger(
                "canonical_v2_canary snapshot=cancelled server=${scope.serverId} scope=${scope.scopeId}",
            )
        }
    }

    private fun heldAfterPartitionRevoke(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
    ): CanonicalMessageV2CanaryOutcome {
        state.cursor = null
        state.mode = CanonicalMessageV2CanaryMode.Off
        logger(
            "canonical_v2_canary mode=off server=${scope.serverId} " +
                "scope=${scope.scopeId} reason=partition_revoked",
        )
        return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_partition_revoked")
    }

    private fun heldAfterScopeEviction(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
    ): CanonicalMessageV2CanaryOutcome {
        state.cursor = null
        state.mode = CanonicalMessageV2CanaryMode.Off
        logger(
            "canonical_v2_canary mode=off server=${scope.serverId} " +
                "scope=${scope.scopeId} reason=retention_evicted",
        )
        return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_scope_evicted")
    }
}

private fun defaultSnapshotJobScope(logger: (String) -> Unit): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            // The job catches every failure itself; this only keeps a bug from crashing the process.
            logger("canonical_v2_canary snapshot=job_crashed reason=${throwable::class.simpleName ?: "unknown"}")
        },
    )

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
