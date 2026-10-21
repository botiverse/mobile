package build.raft.app.network.sync

import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.RaftSqlDatabase
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CanonicalMessageV2IncrementalDecision {
    data object Apply : CanonicalMessageV2IncrementalDecision
    data class Hold(
        val diagnostic: String = CANONICAL_MESSAGE_V2_BASELINE_REQUIRED,
        val requestRebaseline: Boolean,
    ) : CanonicalMessageV2IncrementalDecision
}

/** Per-scope V2 generation gate. V1 state is neither consulted nor accepted as a baseline. */
class CanonicalMessageV2BaselineCoordinator {
    private data class ScopeState(
        val baselined: Boolean = false,
        val rebaselineInFlight: Boolean = false,
    )

    private val states = mutableMapOf<SyncScopeKey, ScopeState>()
    private val lock = SynchronizedObject()

    fun onIncremental(scope: SyncScopeKey): CanonicalMessageV2IncrementalDecision = synchronized(lock) {
        requireV2Scope(scope)
        val state = states[scope] ?: ScopeState()
        if (state.baselined) return@synchronized CanonicalMessageV2IncrementalDecision.Apply
        states[scope] = state.copy(rebaselineInFlight = true)
        CanonicalMessageV2IncrementalDecision.Hold(requestRebaseline = !state.rebaselineInFlight)
    }

    fun onSnapshotCommitted(scope: SyncScopeKey): Unit = synchronized(lock) {
        requireV2Scope(scope)
        states[scope] = ScopeState(baselined = true, rebaselineInFlight = false)
        Unit
    }

    fun onRebaselineFailed(scope: SyncScopeKey): Unit = synchronized(lock) {
        requireV2Scope(scope)
        states[scope] = ScopeState(baselined = false, rebaselineInFlight = false)
        Unit
    }

    fun invalidate(scope: SyncScopeKey): Unit = synchronized(lock) {
        requireV2Scope(scope)
        states.remove(scope)
        Unit
    }

    fun invalidate(partition: DurablePartitionKey): Unit = synchronized(lock) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        states.keys.removeAll { it.partition == partition }
        Unit
    }

    fun isBaselined(scope: SyncScopeKey): Boolean = synchronized(lock) {
        states[scope]?.baselined == true
    }
}

data class CanonicalMessageV2SnapshotFrame(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor,
)

data class CanonicalMessageV2IncrementalFrame(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor,
    /**
     * Task #168: a realtime `New` only adds a fact that is not stored yet. A stored fact may already be
     * newer (edited / reacted, from a snapshot or a later frame), so a late `New` never overwrites it.
     */
    val insertOnly: Boolean = false,
    /**
     * Task #168: a realtime write keeps the scope's stored `updatedAtMs`, so retention ranks scopes by the last
     * USER-driven write (enter / paging / catch-up): a busy channel nobody opens never pushes out a quiet
     * channel the user reads. A scope without a stored cursor gets `now` as before.
     */
    val preserveUpdatedAt: Boolean = false,
    /**
     * Task #14a: per fact id, the aggregates this frame does not know (a subset of
     * [CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES]; absent = all known). The envelope holds neutral
     * placeholders for them. The store merges so that a known value is never replaced by an unknown one.
     */
    val unknownFieldsByFactId: Map<String, Set<MessageAggregateField>> = emptyMap(),
)

data class RestoredCanonicalMessageV2Scope(
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor?,
    /** Task #14a: the partial facts' unknown aggregates (the stored marker); absent id = every aggregate known. */
    val unknownFieldsByFactId: Map<String, Set<MessageAggregateField>> = emptyMap(),
)

interface CanonicalMessageV2Store {
    suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult
    suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult
    suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope
    suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey>
    suspend fun clearPartition(partition: DurablePartitionKey)
    suspend fun knownPartitions(): List<DurablePartitionKey> = emptyList()

    /**
     * Scope kinds that hold a committed baseline cursor for `scopeId` inside `partition`, in
     * deterministic order. The thread screen enters by channel id alone and needs this to name the
     * [SyncScopeKey] it may restore. Empty means "no baseline", never "unknown".
     */
    suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> = emptyList()

    /**
     * The committed cursor of [scope] alone, without decoding its facts (task #168 step 3: a reply
     * thread page needs its stored parent before it can be converted). `null` = no baseline.
     */
    suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? = restore(scope).cursor

    /** Task #168: whether fact [factId] is stored in [scope], without decoding the scope's facts. */
    suspend fun containsFact(scope: SyncScopeKey, factId: String): Boolean =
        restore(scope).envelopes.any { it.fact.id == factId }
}

data class CanonicalMessageV2StoreWriteResult(
    val evictedScopes: Set<SyncScopeKey> = emptySet(),
    /**
     * Task #168: the cursor the store actually committed, when it differs from the frame's (the per-scope
     * cap pruned the oldest end, so the oldest edge moved and `hasLoadedOldest` was cleared). `null` = the
     * frame's cursor was committed unchanged.
     */
    val committedCursor: PersistedCursor? = null,
)

fun interface CanonicalMessageV2SnapshotCommitObserver {
    fun onSnapshotCommitted(scope: SyncScopeKey)
}

sealed interface CanonicalMessageV2CommitResult {
    data object Committed : CanonicalMessageV2CommitResult
    data object PartitionRevoked : CanonicalMessageV2CommitResult
    data object ScopeEvicted : CanonicalMessageV2CommitResult
    data class BaselineRequired(val requestRebaseline: Boolean) : CanonicalMessageV2CommitResult
}

/** Integrates the baseline gate with the disjoint V2 store while remaining unbound to production routing. */
class CanonicalMessageV2GenerationEngine(
    private val coordinator: CanonicalMessageV2BaselineCoordinator,
    private val store: CanonicalMessageV2Store,
    private val snapshotCommitObserver: CanonicalMessageV2SnapshotCommitObserver,
) {
    private data class MutexEntry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val writerRegistryMutex = Mutex()
    private val writerMutexByScope = mutableMapOf<SyncScopeKey, MutexEntry>()
    private val partitionRegistryMutex = Mutex()
    private val partitionMutexByKey = mutableMapOf<DurablePartitionKey, MutexEntry>()
    private val partitionGenerationByKey = mutableMapOf<DurablePartitionKey, Long>()
    private val stateLock = SynchronizedObject()
    private val cursorByScope = mutableMapOf<SyncScopeKey, PersistedCursor>()
    /**
     * Task #14b: per-scope commit generation, bumped (under [stateLock]) by every store write of the scope —
     * snapshot, incremental (enter, paging, gap catch-up, realtime, prewarm) and scope clear. Monotonic for
     * the life of the process; never reset. A writer whose page was fetched before a commit it did not see
     * compares it to refuse a stale page (see [scopeCommitGeneration]).
     */
    private val commitGenerationByScope = mutableMapOf<SyncScopeKey, Long>()
    private val partitionRevocationObservers = mutableListOf<suspend (DurablePartitionKey) -> Unit>()
    private val scopeInvalidationObservers = mutableListOf<suspend (Set<SyncScopeKey>) -> Unit>()

    suspend fun <T> withScopeWriter(scope: SyncScopeKey, block: suspend () -> T): T {
        requireV2Scope(scope)
        val entry = writerRegistryMutex.withLock {
            writerMutexByScope.getOrPut(scope) { MutexEntry() }.also { it.users += 1 }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            writerRegistryMutex.withLock {
                entry.users -= 1
                if (entry.users == 0 && writerMutexByScope[scope] === entry) {
                    writerMutexByScope.remove(scope)
                }
            }
        }
    }

    fun addPartitionRevocationObserver(observer: suspend (DurablePartitionKey) -> Unit) {
        synchronized(stateLock) { partitionRevocationObservers.add(observer) }
    }

    fun addScopeInvalidationObserver(observer: suspend (Set<SyncScopeKey>) -> Unit) {
        synchronized(stateLock) { scopeInvalidationObservers.add(observer) }
    }

    suspend fun partitionGeneration(partition: DurablePartitionKey): Long =
        partitionRegistryMutex.withLock { partitionGenerationByKey[partition] ?: 0L }

    fun currentCursor(scope: SyncScopeKey): PersistedCursor? =
        synchronized(stateLock) { cursorByScope[scope] }

    /**
     * Task #14b: the [scope]'s commit generation (0 before its first write in this process). Read it before
     * fetching a page and compare under the scope writer: a different value means some write landed on the
     * scope after the fetch started, so the page may be older than what is on disk.
     */
    fun scopeCommitGeneration(scope: SyncScopeKey): Long =
        synchronized(stateLock) { commitGenerationByScope[scope] ?: 0L }

    private fun bumpScopeCommitGeneration(scope: SyncScopeKey) {
        synchronized(stateLock) { commitGenerationByScope[scope] = (commitGenerationByScope[scope] ?: 0L) + 1L }
    }

    suspend fun applySnapshot(
        frame: CanonicalMessageV2SnapshotFrame,
        expectedPartitionGeneration: Long? = null,
    ): CanonicalMessageV2CommitResult = withPartitionMutation(frame.scope.partition) {
        if (!isCurrentGeneration(frame.scope.partition, expectedPartitionGeneration)) {
            return@withPartitionMutation CanonicalMessageV2CommitResult.PartitionRevoked
        }
        val write = store.applySnapshot(frame)
        bumpScopeCommitGeneration(frame.scope)
        invalidateEvictedScopes(write.evictedScopes)
        if (frame.scope in write.evictedScopes) {
            return@withPartitionMutation CanonicalMessageV2CommitResult.ScopeEvicted
        }
        coordinator.onSnapshotCommitted(frame.scope)
        synchronized(stateLock) { cursorByScope[frame.scope] = write.committedCursor ?: frame.cursor }
        snapshotCommitObserver.onSnapshotCommitted(frame.scope)
        CanonicalMessageV2CommitResult.Committed
    }

    suspend fun applyIncremental(
        frame: CanonicalMessageV2IncrementalFrame,
        expectedPartitionGeneration: Long? = null,
    ): CanonicalMessageV2CommitResult = withPartitionMutation(frame.scope.partition) {
        if (!isCurrentGeneration(frame.scope.partition, expectedPartitionGeneration)) {
            return@withPartitionMutation CanonicalMessageV2CommitResult.PartitionRevoked
        }
        when (val decision = coordinator.onIncremental(frame.scope)) {
            CanonicalMessageV2IncrementalDecision.Apply -> {
                val write = store.applyIncremental(frame)
                bumpScopeCommitGeneration(frame.scope)
                invalidateEvictedScopes(write.evictedScopes)
                if (frame.scope in write.evictedScopes) {
                    return@withPartitionMutation CanonicalMessageV2CommitResult.ScopeEvicted
                }
                synchronized(stateLock) { cursorByScope[frame.scope] = write.committedCursor ?: frame.cursor }
                CanonicalMessageV2CommitResult.Committed
            }
            is CanonicalMessageV2IncrementalDecision.Hold ->
                CanonicalMessageV2CommitResult.BaselineRequired(decision.requestRebaseline)
        }
    }

    suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
        withPartitionMutation(scope.partition) {
            val restored = store.restore(scope)
            if (restored.cursor != null) {
                coordinator.onSnapshotCommitted(scope)
                synchronized(stateLock) { cursorByScope[scope] = restored.cursor }
                snapshotCommitObserver.onSnapshotCommitted(scope)
            } else {
                synchronized(stateLock) { cursorByScope.remove(scope) }
            }
            restored
        }

    fun rebaselineFailed(scope: SyncScopeKey) {
        coordinator.onRebaselineFailed(scope)
    }

    fun invalidate(scope: SyncScopeKey) {
        coordinator.invalidate(scope)
    }

    suspend fun clearScope(scope: SyncScopeKey) {
        withPartitionMutation(scope.partition) {
            val cleared = store.clearScope(scope)
            bumpScopeCommitGeneration(scope)
            invalidateEvictedScopes(cleared)
        }
    }

    suspend fun clearPartition(partition: DurablePartitionKey) {
        withPartitionMutation(partition) {
            partitionRegistryMutex.withLock {
                partitionGenerationByKey[partition] = (partitionGenerationByKey[partition] ?: 0L) + 1L
            }
            store.clearPartition(partition)
            coordinator.invalidate(partition)
            val observers = synchronized(stateLock) {
                cursorByScope.keys.removeAll { it.partition == partition }
                partitionRevocationObservers.toList()
            }
            observers.forEach { observer -> observer(partition) }
        }
    }

    suspend fun knownPartitions(): List<DurablePartitionKey> = store.knownPartitions()

    /** Pass-through of [CanonicalMessageV2Store.baselinedScopeKinds]; reads only, no coordinator effect. */
    suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> =
        store.baselinedScopeKinds(partition, scopeId)

    /** Pass-through of [CanonicalMessageV2Store.containsFact]; reads only, no coordinator effect. */
    suspend fun storedFactExists(scope: SyncScopeKey, factId: String): Boolean {
        requireV2Scope(scope)
        return store.containsFact(scope, factId)
    }

    /** Pass-through of [CanonicalMessageV2Store.readCursor]; reads only, no coordinator effect. */
    suspend fun storedCursor(scope: SyncScopeKey): PersistedCursor? {
        requireV2Scope(scope)
        return store.readCursor(scope)
    }

    private suspend fun <T> withPartitionMutation(
        partition: DurablePartitionKey,
        block: suspend () -> T,
    ): T {
        val entry = partitionRegistryMutex.withLock {
            partitionMutexByKey.getOrPut(partition) { MutexEntry() }.also { it.users += 1 }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            partitionRegistryMutex.withLock {
                entry.users -= 1
                if (entry.users == 0 && partitionMutexByKey[partition] === entry) {
                    partitionMutexByKey.remove(partition)
                }
            }
        }
    }

    private suspend fun isCurrentGeneration(
        partition: DurablePartitionKey,
        expected: Long?,
    ): Boolean = expected == null || partitionGeneration(partition) == expected

    private suspend fun invalidateEvictedScopes(scopes: Set<SyncScopeKey>) {
        if (scopes.isEmpty()) return
        scopes.forEach(coordinator::invalidate)
        val observers = synchronized(stateLock) {
            scopes.forEach(cursorByScope::remove)
            scopeInvalidationObservers.toList()
        }
        observers.forEach { observer -> observer(scopes) }
    }
}

class CanonicalMessageV2ScopePoisoned(
    val scope: SyncScopeKey,
    val factId: String? = null,
) : RuntimeException("$CANONICAL_MESSAGE_V2_SCOPE_POISONED at $scope fact=${factId.orEmpty()}")

/**
 * Existing message/cursor tables, but a disjoint V2 consumer partition and a strict full-v6 row codec.
 *
 * Task #14a partial facts: a fact may be stored with some aggregates unknown (`unknownFields`, only ever a
 * subset of [CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES]). Every write merges with the stored fact
 * of the same id so a known value always beats an unknown one: a full write (snapshot, page, catch-up, full
 * `message:updated`) completes a partial fact and clears its marker (`canonical_v2_partial_completed`); a
 * partial write never erases what the stored fact knows; an insert-only `New` keeps the stored fact and
 * only fills in what it did not know.
 */
internal class SqlDelightCanonicalMessageV2Store(
    private val database: RaftSqlDatabase,
    private val now: () -> Long,
    private val faultHook: PersistFaultHook = PersistFaultHook.Never,
    private val retention: CanonicalMessageV2RetentionPolicy = CanonicalMessageV2RetentionPolicy(),
    /** One `scope_evicted` line per retention eviction (scope, scopeKind, reason). */
    private val logger: (String) -> Unit = {},
    /** Task #14a: one self-tagged `canonical_v2_partial_completed` line per completed partial fact. */
    private val factLogger: (String) -> Unit = {},
) : CanonicalMessageV2Store {
    override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame) =
        applyFrame(frame.scope, frame.envelopes, frame.cursor, rebaseline = true, insertOnly = false, preserveUpdatedAt = false)

    override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame) =
        applyFrame(
            frame.scope,
            frame.envelopes,
            frame.cursor,
            rebaseline = false,
            insertOnly = frame.insertOnly,
            preserveUpdatedAt = frame.preserveUpdatedAt,
            unknownFieldsByFactId = frame.unknownFieldsByFactId,
        )

    private suspend fun applyFrame(
        scope: SyncScopeKey,
        envelopes: List<CanonicalMessageEnvelopeV2>,
        cursor: PersistedCursor,
        rebaseline: Boolean,
        insertOnly: Boolean,
        preserveUpdatedAt: Boolean,
        unknownFieldsByFactId: Map<String, Set<MessageAggregateField>> = emptyMap(),
    ): CanonicalMessageV2StoreWriteResult {
        requireV2Scope(scope)
        require(envelopes.map { it.fact.id }.distinct().size == envelopes.size) { "duplicate V2 fact id in frame" }
        envelopes.forEach { envelope -> requireEnvelopeMatchesScope(scope, envelope) }
        requireCursorMatchesScope(scope, cursor, envelopes)
        require(
            unknownFieldsByFactId.all { (id, fields) ->
                fields.isNotEmpty() && CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.containsAll(fields) &&
                    envelopes.any { it.fact.id == id }
            },
        ) { "unknown V2 aggregates outside the allowed set or the frame" }
        val evictedScopes = mutableSetOf<SyncScopeKey>()
        var committedCursor: PersistedCursor? = null
        database.transaction {
            // Read before anything is written in this transaction (task #168 retention rank).
            val keptUpdatedAtMs = if (preserveUpdatedAt) selectCursorUpdatedAtMs(scope) else null
            // Task #14a: the stored facts of the frame's ids, read before a snapshot clears the scope.
            val storedBefore = envelopes.associate { it.fact.id to selectStoredFactInsideTransaction(scope, it.fact.id) }
            if (rebaseline) {
                queries.clearUnpinnedCanonicalFactsForScope(
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
            }
            envelopes.forEach { incoming ->
                val incomingUnknown = unknownFieldsByFactId[incoming.fact.id].orEmpty()
                val storedRow = storedBefore[incoming.fact.id]
                val storedUnknown = storedRow?.let { decodeUnknownFields(scope, incoming.fact.id, it.unknownFields) }.orEmpty()
                // Only a fact one side does not fully know needs the stored bytes.
                val stored = storedRow?.takeIf { storedUnknown.isNotEmpty() || incomingUnknown.isNotEmpty() }?.let {
                    StoredFact(decodeStoredFact(scope, incoming.fact.id, it.canonicalFactJson), storedUnknown)
                }
                val (envelope, unknown) = when {
                    stored == null -> incoming to incomingUnknown
                    // The stored fact stays as it is; only what it did not know is filled in.
                    insertOnly -> stored.envelope.withAggregatesFrom(incoming, stored.unknown - incomingUnknown) to
                        (stored.unknown intersect incomingUnknown)
                    // The frame replaces the fact, except for what it does not know and the stored fact does.
                    else -> incoming.withAggregatesFrom(stored.envelope, incomingUnknown - stored.unknown) to
                        (incomingUnknown intersect stored.unknown)
                }
                val bytes = encodeCanonicalMessageV2(envelope, scope.serverId)
                queries.insertCanonicalFactIfAbsent(
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    id = envelope.fact.id,
                    channelId = scope.scopeId,
                    scopeKind = scope.scopeKind,
                    seq = envelope.fact.seq,
                    createdAt = envelope.fact.createdAt,
                    canonicalFactJson = bytes,
                )
                // A snapshot replaces (its scope was cleared above), a page refreshes; an insert-only
                // frame leaves an already stored fact exactly as it is, unless it completes a partial one.
                if (!insertOnly || stored != null) queries.updateCanonicalFact(
                    seq = envelope.fact.seq,
                    createdAt = envelope.fact.createdAt,
                    canonicalFactJson = bytes,
                    id = envelope.fact.id,
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
                queries.setCanonicalFactUnknownFields(
                    unknownFields = encodeUnknownFields(unknown),
                    id = envelope.fact.id,
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
                if (queries.countCanonicalFactInScope(
                        id = envelope.fact.id,
                        environmentId = scope.environmentId,
                        serverId = scope.serverId,
                        principalId = scope.principalId,
                        consumerId = scope.consumerId,
                        scopeKind = scope.scopeKind,
                        channelId = scope.scopeId,
                    ).executeAsOne() != 1L
                ) throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
                val completed = CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.filter { it in storedUnknown && it !in unknown }
                if (completed.isNotEmpty()) {
                    factLogger(
                        "canonical_v2_partial_completed server=${scope.serverId} scope=${scope.scopeId} " +
                            "scopeKind=${scope.scopeKind} msg=${envelope.fact.id} fields=${completed.joinToString(",")}",
                    )
                }
            }
            // The per-scope cap runs before the cursor is written, so the cursor that lands on disk
            // never claims a window edge the cap just pruned away.
            committedCursor = pruneScopeInsideTransaction(scope, cursor)
            val written = committedCursor ?: cursor
            queries.upsertSyncCursor(
                scopeId = scope.scopeId,
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                latestSeq = written.latestSeq,
                oldestLoadedSeq = written.oldestLoadedSeq,
                newestLoadedSeq = written.newestLoadedSeq,
                hasLoadedOldest = if (written.hasLoadedOldest) 1L else 0L,
                hasLoadedNewest = if (written.hasLoadedNewest) 1L else 0L,
                updatedAtMs = keptUpdatedAtMs ?: now(),
                epoch = written.epoch,
                parentScopeKind = written.parentScopeKind,
                parentScopeId = written.parentScopeId,
                rootMessageId = written.rootMessageId,
            )
            touchParentScopeInsideTransaction(scope, cursor)
            evictedScopes += prunePartitionInsideTransaction(scope.partition)
            if (faultHook.shouldKillAt(PersistFaultPoint.BeforeCommit)) {
                throw PersistKilled(PersistFaultPoint.BeforeCommit)
            }
        }
        if (faultHook.shouldKillAt(PersistFaultPoint.AfterCommit)) {
            throw PersistKilled(PersistFaultPoint.AfterCommit)
        }
        return CanonicalMessageV2StoreWriteResult(evictedScopes, committedCursor)
    }

    override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope {
        requireV2Scope(scope)
        val rows = database.queries.selectServerBackedMessagesForChannel(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsList()
        val envelopes = rows.map { row ->
            val accepted = decodeCanonicalMessageV2(row.canonicalFactJson, scope.serverId)
                as? CanonicalMessageV2DecodeResult.Accepted
                ?: throw CanonicalMessageV2ScopePoisoned(scope, row.id)
            val envelope = accepted.envelope
            if (
                row.id != envelope.fact.id ||
                row.environmentId != scope.environmentId ||
                row.seq != envelope.fact.seq ||
                row.createdAt != envelope.fact.createdAt ||
                row.channelId != scope.scopeId ||
                row.scopeKind != scope.scopeKind
            ) throw CanonicalMessageV2ScopePoisoned(scope, row.id)
            requireEnvelopeMatchesScope(scope, envelope)
            envelope
        }
        val unknownFieldsByFactId = rows.mapNotNull { row ->
            decodeUnknownFields(scope, row.id, row.unknownFields).takeIf { it.isNotEmpty() }?.let { row.id to it }
        }.toMap()
        val cursor = selectCursor(scope)
        if (envelopes.isNotEmpty() && cursor == null) throw CanonicalMessageV2ScopePoisoned(scope)
        return RestoredCanonicalMessageV2Scope(envelopes = envelopes, cursor = cursor, unknownFieldsByFactId = unknownFieldsByFactId)
    }

    private class StoredFact(val envelope: CanonicalMessageEnvelopeV2, val unknown: Set<MessageAggregateField>)

    private fun selectStoredFactInsideTransaction(scope: SyncScopeKey, id: String) =
        database.queries.selectCanonicalFactInScope(
            id = id,
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsOneOrNull()

    private fun decodeStoredFact(scope: SyncScopeKey, id: String, bytes: String): CanonicalMessageEnvelopeV2 =
        (decodeCanonicalMessageV2(bytes, scope.serverId) as? CanonicalMessageV2DecodeResult.Accepted)
            ?.envelope?.takeIf { it.fact.id == id }
            ?: throw CanonicalMessageV2ScopePoisoned(scope, id)

    /** The stored marker: only names of the allowed set, each once, in canonical order; else poisoned. */
    private fun decodeUnknownFields(scope: SyncScopeKey, id: String, encoded: String?): Set<MessageAggregateField> {
        if (encoded == null) return emptySet()
        val fields = encoded.split(",").map { name ->
            CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.firstOrNull { it.name == name }
                ?: throw CanonicalMessageV2ScopePoisoned(scope, id)
        }
        if (encodeUnknownFields(fields.toSet()) != encoded) throw CanonicalMessageV2ScopePoisoned(scope, id)
        return fields.toSet()
    }

    override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? {
        requireV2Scope(scope)
        return selectCursor(scope)
    }

    override suspend fun containsFact(scope: SyncScopeKey, factId: String): Boolean {
        requireV2Scope(scope)
        return database.queries.countCanonicalFactInScope(
            id = factId,
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsOne() == 1L
    }

    private fun selectCursorUpdatedAtMs(scope: SyncScopeKey): Long? =
        database.queries.selectSyncCursor(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
        ).executeAsOneOrNull()?.updatedAtMs

    private fun selectCursor(scope: SyncScopeKey): PersistedCursor? =
        database.queries.selectSyncCursor(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
        ).executeAsOneOrNull()?.let {
            PersistedCursor(
                latestSeq = it.latestSeq,
                oldestLoadedSeq = it.oldestLoadedSeq,
                newestLoadedSeq = it.newestLoadedSeq,
                hasLoadedOldest = it.hasLoadedOldest != 0L,
                hasLoadedNewest = it.hasLoadedNewest != 0L,
                epoch = it.epoch,
                parentScopeKind = it.parentScopeKind,
                parentScopeId = it.parentScopeId,
                rootMessageId = it.rootMessageId,
            )
        }

    override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> {
        requireV2Scope(scope)
        val clearedScopes = mutableSetOf<SyncScopeKey>()
        database.transaction {
            evictScopeAndDependentRepliesInsideTransaction(scope, clearedScopes)
        }
        return clearedScopes
    }

    override suspend fun clearPartition(partition: DurablePartitionKey) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        database.transaction {
            queries.clearCanonicalFactsForPartition(
                environmentId = partition.environmentId,
                serverId = partition.serverId,
                principalId = partition.principalId,
                consumerId = partition.consumerId,
            )
            queries.clearSyncCursorsForPartition(
                environmentId = partition.environmentId,
                serverId = partition.serverId,
                principalId = partition.principalId,
                consumerId = partition.consumerId,
            )
            queries.clearMessageViewerOverlaysForPartition(
                environmentId = partition.environmentId,
                serverId = partition.serverId,
                principalId = partition.principalId,
                consumerId = partition.consumerId,
            )
        }
    }

    override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        requireOpaqueDurableIdentity("scopeId", scopeId)
        return database.queries.selectSyncCursorScopeKindsForScopeId(
            environmentId = partition.environmentId,
            serverId = partition.serverId,
            principalId = partition.principalId,
            consumerId = partition.consumerId,
            scopeId = scopeId,
        ).executeAsList()
    }

    override suspend fun knownPartitions(): List<DurablePartitionKey> =
        database.queries.selectCanonicalMessageV2Partitions(
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            consumerId_ = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            consumerId__ = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
        ).executeAsList().map { row ->
            DurablePartitionKey(
                environmentId = row.environmentId,
                serverId = row.serverId,
                principalId = row.principalId,
                consumerId = row.consumerId,
            )
        }

    /**
     * Per-scope cap: keeps the newest [CanonicalMessageV2RetentionPolicy.maxFactsPerScope] facts (pinned
     * thread roots excluded). When it removes facts the window no longer reaches [cursor]'s oldest edge,
     * so it returns the cursor to commit instead (task #168 — the adjacent page writer's surviving-oldest
     * rule): its oldest edge is the oldest fact newer than everything pruned, and `hasLoadedOldest` is
     * cleared. `null` = nothing was pruned; commit [cursor] as it is.
     */
    private fun pruneScopeInsideTransaction(scope: SyncScopeKey, cursor: PersistedCursor): PersistedCursor? {
        val pruned = database.queries.selectCanonicalFactIdsBeyondLimit(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
            value_ = retention.maxFactsPerScope.toLong(),
        ).executeAsList()
        if (pruned.isEmpty()) return null
        pruned.forEach { fact ->
            database.queries.deleteCanonicalFactInScope(
                id = fact.id,
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
        }
        val newestPruned = pruned.maxOf { it.seq }
        val survivingOldest = database.queries.selectOldestCanonicalFactSeqAbove(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
            seq = newestPruned,
        ).executeAsOne().oldestSeq
        val oldest = listOfNotNull(cursor.oldestLoadedSeq, survivingOldest).maxOrNull()
        return cursor.copy(oldestLoadedSeq = oldest, hasLoadedOldest = false)
    }

    /**
     * A reply-thread write is a use of its parent scope (task #168 step 3): the parent channel / DM
     * cursor's `updatedAtMs` moves with it, in the same transaction, so a thread write can never make
     * its own parent the least-recently-written scope and cascade-evict itself with it.
     */
    private fun touchParentScopeInsideTransaction(scope: SyncScopeKey, cursor: PersistedCursor) {
        if (scope.scopeKind != THREAD_SCOPE_KIND) return
        val parentKind = cursor.parentScopeKind ?: return
        val parentId = cursor.parentScopeId ?: return
        database.queries.touchSyncCursorUpdatedAt(
            updatedAtMs = now(),
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = parentKind,
            scopeId = parentId,
        )
    }

    /**
     * Partition retention (task #168 step 3: thread scopes have their own budget). Scopes are ranked
     * by `updatedAtMs`, newest first, and evicted in this order:
     * 1. `ttl`: any scope not written within [CanonicalMessageV2RetentionPolicy.scopeTtlMs];
     * 2. `thread_cap`: thread scopes beyond [CanonicalMessageV2RetentionPolicy.maxThreadScopesPerPartition],
     *    least recently written first — never a channel to make room for a thread;
     * 3. `fact_cap` / `byte_cap`: while the partition is over a cap, the least recently written
     *    THREAD scope goes first;
     * 4. channel / DM scopes, newest first, exactly as before threads existed: a scope is kept while it
     *    fits [CanonicalMessageV2RetentionPolicy.maxScopesPerPartition] (counting channel / DM scopes
     *    only) and the remaining fact / byte budget (`scope_cap` / `fact_cap` / `byte_cap`).
     * Evicting a channel / DM also evicts the reply threads that depend on it (`parent_evicted`).
     */
    private fun prunePartitionInsideTransaction(partition: DurablePartitionKey): Set<SyncScopeKey> {
        val cutoff = now() - retention.scopeTtlMs
        val evictedScopes = mutableSetOf<SyncScopeKey>()
        val stats = database.queries.selectCanonicalScopeRetentionStats(
            environmentId = partition.environmentId,
            serverId = partition.serverId,
            principalId = partition.principalId,
            consumerId = partition.consumerId,
        ).executeAsList().map { row ->
            ScopeRetentionStats(
                scope = SyncScopeKey(partition = partition, scopeId = row.scopeId, scopeKind = row.scopeKind),
                updatedAtMs = row.updatedAtMs,
                factCount = row.factCount,
                byteCount = row.byteCount,
            )
        }
        fun evict(stat: ScopeRetentionStats, reason: String) {
            if (stat.scope in evictedScopes) return
            logEviction(stat.scope, reason)
            val before = evictedScopes.toSet()
            evictScopeAndDependentRepliesInsideTransaction(stat.scope, evictedScopes)
            (evictedScopes - before - stat.scope).forEach { logEviction(it, "parent_evicted") }
        }

        // 1. ttl
        stats.filter { it.updatedAtMs < cutoff }.forEach { evict(it, "ttl") }
        // 2. thread cap (stats are newest first)
        val threads = stats.filter { it.scope.scopeKind == THREAD_SCOPE_KIND && it.scope !in evictedScopes }
        threads.drop(retention.maxThreadScopesPerPartition).forEach { evict(it, "thread_cap") }
        // 3. fact / byte pressure is relieved by threads first, least recently written first.
        fun live() = stats.filter { it.scope !in evictedScopes }
        while (true) {
            val alive = live()
            val reason = when {
                alive.sumOf { it.factCount } > retention.maxFactsPerPartition -> "fact_cap"
                alive.sumOf { it.byteCount } > retention.maxBytesPerPartition -> "byte_cap"
                else -> null
            } ?: break
            val victim = alive.lastOrNull { it.scope.scopeKind == THREAD_SCOPE_KIND } ?: break
            evict(victim, reason)
        }
        // 4. channel / DM scopes: the pre-thread greedy pass, with the surviving threads' facts and
        //    bytes already spent from the budget.
        val keptThreads = live().filter { it.scope.scopeKind == THREAD_SCOPE_KIND }
        var retainedScopes = 0
        var retainedFacts = keptThreads.sumOf { it.factCount }
        var retainedBytes = keptThreads.sumOf { it.byteCount }
        stats.filter { it.scope.scopeKind != THREAD_SCOPE_KIND }.forEach { stat ->
            if (stat.scope in evictedScopes) return@forEach
            val reason = when {
                retainedScopes >= retention.maxScopesPerPartition -> "scope_cap"
                retainedFacts + stat.factCount > retention.maxFactsPerPartition -> "fact_cap"
                retainedBytes + stat.byteCount > retention.maxBytesPerPartition -> "byte_cap"
                else -> null
            }
            if (reason == null) {
                retainedScopes += 1
                retainedFacts += stat.factCount
                retainedBytes += stat.byteCount
            } else {
                evict(stat, reason)
            }
        }
        return evictedScopes
    }

    private data class ScopeRetentionStats(
        val scope: SyncScopeKey,
        val updatedAtMs: Long,
        val factCount: Long,
        val byteCount: Double,
    )

    private fun logEviction(scope: SyncScopeKey, reason: String) {
        logger(
            "outcome=scope_evicted server=${scope.serverId} scope=${scope.scopeId} " +
                "scopeKind=${scope.scopeKind} reason=$reason",
        )
    }

    private fun evictScopeAndDependentRepliesInsideTransaction(
        scope: SyncScopeKey,
        evictedScopes: MutableSet<SyncScopeKey>,
    ) {
        if (!evictedScopes.add(scope)) return
        if (scope.scopeKind != "thread") {
            database.queries.selectReplyScopesDependingOnParent(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                parentScopeKind = scope.scopeKind,
                parentScopeId = scope.scopeId,
            ).executeAsList().forEach { dependent ->
                val replyScope = SyncScopeKey(
                    partition = scope.partition,
                    scopeId = dependent.scopeId,
                    scopeKind = dependent.scopeKind,
                )
                if (evictedScopes.add(replyScope)) clearScopeInsideTransaction(replyScope)
            }
        }
        clearScopeInsideTransaction(scope)
    }

    private fun clearScopeInsideTransaction(scope: SyncScopeKey) {
        database.queries.clearCanonicalFactsForScope(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        )
        database.queries.deleteSyncCursorForScope(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
        )
        database.queries.deleteMessageViewerOverlaysForScope(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
        )
    }
}

internal data class CanonicalMessageV2RetentionPolicy(
    val maxFactsPerScope: Int = 500,
    /** Channel / DM scopes only; thread scopes have [maxThreadScopesPerPartition]. */
    val maxScopesPerPartition: Int = 64,
    /** Reply-thread scopes (task #168 step 3); evicted before any channel / DM scope. */
    val maxThreadScopesPerPartition: Int = 24,
    val maxFactsPerPartition: Long = 20_000,
    val maxBytesPerPartition: Double = 32.0 * 1024.0 * 1024.0,
    val scopeTtlMs: Long = 30L * 24L * 60L * 60L * 1_000L,
) {
    init {
        require(maxFactsPerScope > 0)
        require(maxScopesPerPartition > 0)
        require(maxThreadScopesPerPartition > 0)
        require(maxFactsPerPartition >= maxFactsPerScope)
        require(maxBytesPerPartition > 0.0)
        require(scopeTtlMs > 0L)
    }
}

sealed interface AttachmentCommentsCacheLookupV2 {
    data object Unknown : AttachmentCommentsCacheLookupV2
    data class Loaded(val commentIds: List<String>) : AttachmentCommentsCacheLookupV2
}

/** Receiver-private typed read cache. A room-stripped null is represented by doing nothing. */
class AttachmentCommentsReadCacheV2 {
    private data class Entry(val parentScope: SyncScopeKey, val commentIds: List<String>)
    private val entries = mutableMapOf<AttachmentCommentsReadKeyV2, Entry>()

    fun put(key: AttachmentCommentsReadKeyV2, parentScope: SyncScopeKey, commentIds: List<String>) {
        require(key.partition == parentScope.partition)
        entries[key] = Entry(parentScope, commentIds.toList())
    }

    fun roomStrippedNull(key: AttachmentCommentsReadKeyV2) = Unit

    fun get(key: AttachmentCommentsReadKeyV2): AttachmentCommentsCacheLookupV2 =
        entries[key]?.let { AttachmentCommentsCacheLookupV2.Loaded(it.commentIds) }
            ?: AttachmentCommentsCacheLookupV2.Unknown

    fun invalidateParent(parentScope: SyncScopeKey) {
        entries.entries.removeAll { it.value.parentScope == parentScope }
    }
}

internal fun requireV2Scope(scope: SyncScopeKey) {
    require(scope.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) {
        "V2 must not read or write the V1 durable partition"
    }
}

private const val THREAD_SCOPE_KIND: String = "thread"

/** Task #14a: the `unknownFields` column value; `null` = every aggregate known. */
private fun encodeUnknownFields(fields: Set<MessageAggregateField>): String? =
    CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.filter { it in fields }.joinToString(",").ifEmpty { null }

/** This envelope with [fields] (allowed-unknown aggregates only) taken from [source]. */
private fun CanonicalMessageEnvelopeV2.withAggregatesFrom(
    source: CanonicalMessageEnvelopeV2,
    fields: Set<MessageAggregateField>,
): CanonicalMessageEnvelopeV2 =
    fields.fold(this) { merged, field ->
        when (field) {
            MessageAggregateField.SenderDescription ->
                merged.copy(fact = merged.fact.copy(senderDescription = source.fact.senderDescription))
            MessageAggregateField.Reactions -> merged.copy(fact = merged.fact.copy(reactions = source.fact.reactions))
            else -> error("$field is not an allowed unknown V2 aggregate")
        }
    }

private fun requireEnvelopeMatchesScope(scope: SyncScopeKey, envelope: CanonicalMessageEnvelopeV2) {
    val diagnostic = validateCanonicalMessageV2(envelope, scope.serverId)
    if (diagnostic != null) throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
    val matches = when (val context = envelope.context) {
        is CanonicalConversationContextV2.TopLevel ->
            context.scope.scopeId == scope.scopeId && context.scope.scopeKind == scope.scopeKind
        is CanonicalConversationContextV2.Thread ->
            context.replyScope.scopeId == scope.scopeId && scope.scopeKind == "thread"
    }
    if (!matches) throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
}

private fun requireCursorMatchesScope(
    scope: SyncScopeKey,
    cursor: PersistedCursor,
    envelopes: List<CanonicalMessageEnvelopeV2>,
) {
    if (scope.scopeKind != "thread") {
        if (cursor.parentScopeKind != null || cursor.parentScopeId != null || cursor.rootMessageId != null) {
            throw CanonicalMessageV2ScopePoisoned(scope)
        }
        return
    }
    val parentKind = cursor.parentScopeKind?.takeIf { it in setOf("channel", "private", "dm", "joint") }
        ?: throw CanonicalMessageV2ScopePoisoned(scope)
    val parentScopeId = cursor.parentScopeId?.takeIf { it.isNotBlank() }
        ?: throw CanonicalMessageV2ScopePoisoned(scope)
    val rootMessageId = cursor.rootMessageId?.takeIf { it.isNotBlank() }
        ?: throw CanonicalMessageV2ScopePoisoned(scope)
    envelopes.forEach { envelope ->
        val context = envelope.context as? CanonicalConversationContextV2.Thread
            ?: throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
        if (
            context.parentTopLevelScope.scopeKind != parentKind ||
            context.parentTopLevelScope.scopeId != parentScopeId ||
            context.rootMessageRef.messageId != rootMessageId
        ) throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
    }
}

private val TopLevelScopeRefV2.scopeKind: String
    get() = when (this) {
        is TopLevelScopeRefV2.Channel -> "channel"
        is TopLevelScopeRefV2.Private -> "private"
        is TopLevelScopeRefV2.DirectMessage -> "dm"
        is TopLevelScopeRefV2.Joint -> "joint"
    }
