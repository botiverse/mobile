package build.raft.app.network.sync

import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.shared.data.local.SelectCanonicalFactInScope
import build.raft.app.shared.data.local.SyncCursorEntity
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /**
     * Message storage design 2026-10-27 §4.1 (Phase 1, no production caller yet): store [envelopes] and union
     * [coverage] into the scope's segments, in ONE transaction:
     * 1. upsert every fact with the existing merge rules (known beats unknown, one-way retraction and
     *    action-card keys, [insertOnly] for a realtime `New`), except a fact rewritten after the request
     *    started (stored `localRev` > [revAtRequest]: it is newer and stays). Written facts get the scope's
     *    next revision. A stored fact the page does not carry is NEVER deleted (the server has no message
     *    delete; a plan history cutoff is a display concern);
     * 2. union [coverage] (`null` = loose facts only) into the normalized segments;
     * 3. segment-aware retention (scope, then partition);
     * 4. the header: created when missing ([threadParent] names a reply thread's parent), its window
     *    columns mirror the head segment, and [userDriven] moves `lastUserAccessAtMs`.
     * [revAtRequest] is [maxLocalRev] read before the request was sent.
     */
    suspend fun applyCoverage(
        scope: SyncScopeKey,
        envelopes: List<CanonicalMessageEnvelopeV2>,
        coverage: CanonicalMessageV2Coverage?,
        revAtRequest: Long,
        userDriven: Boolean,
        threadParent: CanonicalMessageV2ThreadParent? = null,
        insertOnly: Boolean = false,
        unknownFieldsByFactId: Map<String, Set<MessageAggregateField>> = emptyMap(),
        /**
         * Phase 3a: `false` skips step 3's PARTITION retention (scope retention still runs). A realtime loose
         * writer runs it only every few writes, so one frame never scans the whole partition.
         */
        runPartitionRetention: Boolean = true,
    ): CanonicalMessageV2StoreWriteResult = throw UnsupportedOperationException("applyCoverage")

    /** The scope's stored segments, normalized, by lowSeq. */
    suspend fun readSegments(scope: SyncScopeKey): List<CanonicalMessageV2Segment> = emptyList()

    /**
     * The current fact write revision of the scope's partition (0 = none yet; it only grows, a clear or
     * prune never lowers it): read before a request, pass as revAtRequest.
     */
    suspend fun maxLocalRev(scope: SyncScopeKey): Long = 0L
}

/** The parent a reply-thread scope's header names (its cursor's parent columns). */
data class CanonicalMessageV2ThreadParent(
    val parentScopeKind: String,
    val parentScopeId: String,
    val rootMessageId: String,
)

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
            // A cancelled writer must still release its entry, or the scope's registry leaks a user.
            withContext(NonCancellable) {
                writerRegistryMutex.withLock {
                    entry.users -= 1
                    if (entry.users == 0 && writerMutexByScope[scope] === entry) {
                        writerMutexByScope.remove(scope)
                    }
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

    /**
     * [CanonicalMessageV2Store.applyCoverage] as a partition mutation, like every other write: refused
     * ([CanonicalMessageV2CommitResult.PartitionRevoked]) when the partition was cleared since the writer read
     * [expectedPartitionGeneration]; bumps the scope's commit generation and invalidates evicted scopes.
     */
    suspend fun applyCoverage(
        scope: SyncScopeKey,
        envelopes: List<CanonicalMessageEnvelopeV2>,
        coverage: CanonicalMessageV2Coverage?,
        revAtRequest: Long,
        userDriven: Boolean,
        expectedPartitionGeneration: Long?,
        threadParent: CanonicalMessageV2ThreadParent? = null,
        insertOnly: Boolean = false,
        unknownFieldsByFactId: Map<String, Set<MessageAggregateField>> = emptyMap(),
        /** Phase 3a: see [CanonicalMessageV2Store.applyCoverage]. */
        runPartitionRetention: Boolean = true,
        /**
         * Phase 3a: `false` for a realtime loose fact. It only adds or refreshes facts and claims nothing, so it
         * never makes a page fetched before it stale ([scopeCommitGeneration] stays).
         */
        countsAsScopeCommit: Boolean = true,
    ): CanonicalMessageV2CommitResult = withPartitionMutation(scope.partition) {
        requireV2Scope(scope)
        if (!isCurrentGeneration(scope.partition, expectedPartitionGeneration)) {
            return@withPartitionMutation CanonicalMessageV2CommitResult.PartitionRevoked
        }
        val write = store.applyCoverage(
            scope, envelopes, coverage, revAtRequest, userDriven, threadParent, insertOnly, unknownFieldsByFactId,
            runPartitionRetention,
        )
        if (countsAsScopeCommit) bumpScopeCommitGeneration(scope)
        invalidateEvictedScopes(write.evictedScopes)
        if (scope in write.evictedScopes) return@withPartitionMutation CanonicalMessageV2CommitResult.ScopeEvicted
        write.committedCursor?.let { committed -> synchronized(stateLock) { cursorByScope[scope] = committed } }
        CanonicalMessageV2CommitResult.Committed
    }

    /** Pass-through of [CanonicalMessageV2Store.maxLocalRev]: read it before a request (revAtRequest). */
    suspend fun storedLocalRev(scope: SyncScopeKey): Long {
        requireV2Scope(scope)
        return store.maxLocalRev(scope)
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
            withContext(NonCancellable) {
                partitionRegistryMutex.withLock {
                    entry.users -= 1
                    if (entry.users == 0 && partitionMutexByKey[partition] === entry) {
                        partitionMutexByKey.remove(partition)
                    }
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

    override suspend fun applyCoverage(
        scope: SyncScopeKey,
        envelopes: List<CanonicalMessageEnvelopeV2>,
        coverage: CanonicalMessageV2Coverage?,
        revAtRequest: Long,
        userDriven: Boolean,
        threadParent: CanonicalMessageV2ThreadParent?,
        insertOnly: Boolean,
        unknownFieldsByFactId: Map<String, Set<MessageAggregateField>>,
        runPartitionRetention: Boolean,
    ): CanonicalMessageV2StoreWriteResult {
        requireV2Scope(scope)
        require(revAtRequest >= 0L) { "revAtRequest must be a stored revision" }
        require(envelopes.map { it.fact.id }.distinct().size == envelopes.size) { "duplicate V2 fact id in frame" }
        envelopes.forEach { envelope -> requireEnvelopeMatchesScope(scope, envelope) }
        requireUnknownFieldsInFrame(envelopes, unknownFieldsByFactId)
        val evictedScopes = mutableSetOf<SyncScopeKey>()
        var committedCursor: PersistedCursor? = null
        database.transaction {
            val header = selectHeader(scope)
            val stored = header?.toPersistedCursor()
            if (stored != null && threadParent != null && stored.threadParent() != threadParent) {
                throw CanonicalMessageV2ScopePoisoned(scope)
            }
            val base = stored ?: PersistedCursor(
                latestSeq = null,
                oldestLoadedSeq = null,
                newestLoadedSeq = null,
                hasLoadedOldest = false,
                hasLoadedNewest = false,
                epoch = null,
                parentScopeKind = threadParent?.parentScopeKind,
                parentScopeId = threadParent?.parentScopeId,
                rootMessageId = threadParent?.rootMessageId,
            )
            requireCursorMatchesScope(scope, base, envelopes)
            // 1. Facts. A fact rewritten after the request started is newer than the page: it stays.
            val localRev = if (envelopes.isEmpty()) 0L else nextLocalRevInsideTransaction(scope.partition)
            envelopes.forEach { incoming ->
                val storedRow = selectStoredFactInsideTransaction(scope, incoming.fact.id)
                if (storedRow != null && storedRow.localRev > revAtRequest) return@forEach
                writeFactInsideTransaction(
                    scope = scope,
                    incoming = incoming,
                    storedRow = storedRow,
                    insertOnly = insertOnly,
                    incomingUnknown = unknownFieldsByFactId[incoming.fact.id].orEmpty(),
                    localRev = localRev,
                )
            }
            // 2. Claims, 3. scope retention.
            val nowMs = now()
            val before = selectSegmentsInsideTransaction(scope)
            val unioned = coverage?.let { CanonicalMessageV2SegmentAlgebra.union(before, it.toSegment(nowMs)) } ?: before
            val segments = pruneScopeBySegmentsInsideTransaction(scope, unioned)
            replaceSegmentsInsideTransaction(scope, before, segments)
            // 4. Header: the window columns mirror the head segment.
            val head = CanonicalMessageV2SegmentAlgebra.head(segments)
            val written = base.mirroring(head, selectHeadFactBoundsInsideTransaction(scope, head)).copy(
                latestSeq = (listOfNotNull(base.latestSeq) + envelopes.map { it.fact.seq }).maxOrNull(),
            )
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
                updatedAtMs = nowMs,
                epoch = written.epoch,
                parentScopeKind = written.parentScopeKind,
                parentScopeId = written.parentScopeId,
                rootMessageId = written.rootMessageId,
                lastUserAccessAtMs = if (userDriven) nowMs else header?.lastUserAccessAtMs,
            )
            if (userDriven) touchParentScopeInsideTransaction(scope, written)
            if (runPartitionRetention) evictedScopes += prunePartitionInsideTransaction(scope.partition)
            committedCursor = written
            if (faultHook.shouldKillAt(PersistFaultPoint.BeforeCommit)) {
                throw PersistKilled(PersistFaultPoint.BeforeCommit)
            }
        }
        if (faultHook.shouldKillAt(PersistFaultPoint.AfterCommit)) {
            throw PersistKilled(PersistFaultPoint.AfterCommit)
        }
        return CanonicalMessageV2StoreWriteResult(evictedScopes, committedCursor)
    }

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
        requireUnknownFieldsInFrame(envelopes, unknownFieldsByFactId)
        val evictedScopes = mutableSetOf<SyncScopeKey>()
        var committedCursor: PersistedCursor? = null
        database.transaction {
            // Read before anything is written in this transaction (task #168 retention rank).
            val storedHeader = if (preserveUpdatedAt) selectHeader(scope) else null
            val keptUpdatedAtMs = storedHeader?.updatedAtMs
            // Task #14a: the stored facts of the frame's ids, read before a snapshot clears the scope.
            val storedBefore = envelopes.associate { it.fact.id to selectStoredFactInsideTransaction(scope, it.fact.id) }
            val localRev = if (envelopes.isEmpty()) 0L else nextLocalRevInsideTransaction(scope.partition)
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
                writeFactInsideTransaction(
                    scope = scope,
                    incoming = incoming,
                    storedRow = storedBefore[incoming.fact.id],
                    insertOnly = insertOnly,
                    incomingUnknown = unknownFieldsByFactId[incoming.fact.id].orEmpty(),
                    localRev = localRev,
                )
            }
            // The per-scope cap runs before the cursor is written, so the cursor that lands on disk
            // never claims a window edge the cap just pruned away.
            val prune = pruneScopeInsideTransaction(scope, cursor)
            committedCursor = prune?.cursor
            val written = committedCursor ?: cursor
            val updatedAtMs = keptUpdatedAtMs ?: now()
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
                updatedAtMs = updatedAtMs,
                epoch = written.epoch,
                parentScopeKind = written.parentScopeKind,
                parentScopeId = written.parentScopeId,
                rootMessageId = written.rootMessageId,
                // A legacy write is user-driven unless it keeps the stored rank (realtime): then the stored
                // access stays too. Until v10 both columns are the same value, so retention ranks as before.
                lastUserAccessAtMs = if (storedHeader != null) storedHeader.lastUserAccessAtMs else updatedAtMs,
            )
            // Segments stay consistent with the legacy window (Phase 1): the window is the head segment.
            syncLegacyWindowSegmentsInsideTransaction(scope, written, rebaseline, prune?.prunedThroughSeq)
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

    /**
     * One fact of a write, merged with its stored copy ([storedRow], read before the write) under the
     * task #14a / RFC 077 / manifest v8 rules. A fact this write inserts or rewrites gets [localRev]; an
     * insert-only `New` that leaves the stored fact as it is keeps the stored revision.
     */
    private fun writeFactInsideTransaction(
        scope: SyncScopeKey,
        incoming: CanonicalMessageEnvelopeV2,
        storedRow: SelectCanonicalFactInScope?,
        insertOnly: Boolean,
        incomingUnknown: Set<MessageAggregateField>,
        localRev: Long,
    ) {
        val storedUnknown = storedRow?.let { decodeUnknownFields(scope, incoming.fact.id, it.unknownFields) }.orEmpty()
        // Only a fact one side does not fully know needs the stored bytes.
        val stored = storedRow?.takeIf { storedUnknown.isNotEmpty() || incomingUnknown.isNotEmpty() }?.let {
            StoredFact(decodeStoredFact(scope, incoming.fact.id, it.canonicalFactJson), storedUnknown)
        }
        val (merged, unknown) = when {
            stored == null -> incoming to incomingUnknown
            // The stored fact stays as it is; only what it did not know is filled in.
            insertOnly -> stored.envelope.withAggregatesFrom(incoming, stored.unknown - incomingUnknown) to
                (stored.unknown intersect incomingUnknown)
            // The frame replaces the fact, except for what it does not know and the stored fact does.
            else -> incoming.withAggregatesFrom(stored.envelope, incomingUnknown - stored.unknown) to
                (incomingUnknown intersect stored.unknown)
        }
        // RFC 077: retraction is one-way. A frame without `deletedAt` for a fact stored as
        // retracted is an older copy: it keeps the retraction and never brings back the words,
        // attachments or reactions (web `keepDelegationFacts`). The key is only in a retracted
        // fact's bytes, so a live fact is never decoded for this.
        val storedRetraction = storedRow
            ?.takeIf { merged.fact.deletedAt == null && it.canonicalFactJson.contains(RETRACTION_BYTES_MARKER) }
            ?.let { stored?.envelope ?: decodeStoredFact(scope, incoming.fact.id, it.canonicalFactJson) }
            ?.fact?.takeIf { it.deletedAt != null }
        // Manifest v8: the card's optional keys are one-way. A frame whose card lacks one is an
        // older/narrower copy: the stored value stays, and confirmationVersion never goes down.
        // The keys are only in the bytes of a card that has them, so other facts are never decoded.
        val storedCard = storedRow
            ?.takeIf {
                merged.fact.actionMetadata is CanonicalActionMetadataV2.ActionCard &&
                    ACTION_CARD_ONE_WAY_BYTES_MARKERS.any(it.canonicalFactJson::contains)
            }
            ?.let { stored?.envelope ?: decodeStoredFact(scope, incoming.fact.id, it.canonicalFactJson) }
            ?.fact?.actionMetadata as? CanonicalActionMetadataV2.ActionCard
        val envelope = merged.withStoredRetraction(storedRetraction).withStoredActionCardKeys(storedCard)
        val bytes = encodeCanonicalMessageV2(envelope, scope.serverId)
        database.queries.insertCanonicalFactIfAbsent(
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
        val rewritten = !insertOnly || stored != null
        if (rewritten) database.queries.updateCanonicalFact(
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
        database.queries.setCanonicalFactUnknownFields(
            unknownFields = encodeUnknownFields(unknown),
            localRev = if (storedRow == null || rewritten) localRev else storedRow.localRev,
            id = envelope.fact.id,
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        )
        if (database.queries.countCanonicalFactInScope(
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

    private fun selectHeader(scope: SyncScopeKey): SyncCursorEntity? =
        database.queries.selectSyncCursor(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
        ).executeAsOneOrNull()

    private fun selectCursor(scope: SyncScopeKey): PersistedCursor? = selectHeader(scope)?.toPersistedCursor()

    private fun SyncCursorEntity.toPersistedCursor(): PersistedCursor =
        PersistedCursor(
            latestSeq = latestSeq,
            oldestLoadedSeq = oldestLoadedSeq,
            newestLoadedSeq = newestLoadedSeq,
            hasLoadedOldest = hasLoadedOldest != 0L,
            hasLoadedNewest = hasLoadedNewest != 0L,
            epoch = epoch,
            parentScopeKind = parentScopeKind,
            parentScopeId = parentScopeId,
            rootMessageId = rootMessageId,
        )

    private fun selectLocalRevInsideTransaction(partition: DurablePartitionKey): Long =
        database.queries.selectLocalRev(
            environmentId = partition.environmentId,
            serverId = partition.serverId,
            principalId = partition.principalId,
            consumerId = partition.consumerId,
        ).executeAsOne()

    /** The partition's next write revision, stored at once (inside the writing transaction). */
    private fun nextLocalRevInsideTransaction(partition: DurablePartitionKey): Long {
        val next = selectLocalRevInsideTransaction(partition) + 1L
        database.queries.setLocalRev(
            environmentId = partition.environmentId,
            serverId = partition.serverId,
            principalId = partition.principalId,
            consumerId = partition.consumerId,
            localRev = next,
        )
        return next
    }

    /** The head mirror's edges: the oldest / newest stored fact seq inside [head]'s range. */
    private fun selectHeadFactBoundsInsideTransaction(scope: SyncScopeKey, head: CanonicalMessageV2Segment?): Pair<Long, Long>? {
        head ?: return null
        val bounds = database.queries.selectFactSeqBoundsInRange(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
            seq = head.effectiveLowSeq,
            seq_ = head.highSeq,
        ).executeAsOne()
        val oldest = bounds.oldestSeq ?: return null
        val newest = bounds.newestSeq ?: return null
        return oldest to newest
    }

    override suspend fun maxLocalRev(scope: SyncScopeKey): Long {
        requireV2Scope(scope)
        return selectLocalRevInsideTransaction(scope.partition)
    }

    override suspend fun readSegments(scope: SyncScopeKey): List<CanonicalMessageV2Segment> {
        requireV2Scope(scope)
        return selectSegmentsInsideTransaction(scope)
    }

    private fun selectSegmentsInsideTransaction(scope: SyncScopeKey): List<CanonicalMessageV2Segment> =
        database.queries.selectSegmentsForScope(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
        ).executeAsList().map { row ->
            val source = CanonicalMessageV2SegmentSource.fromWire(row.source)
            if (source == null || row.lowSeq < 0L || row.lowSeq > row.highSeq) throw CanonicalMessageV2ScopePoisoned(scope)
            CanonicalMessageV2Segment(
                lowSeq = row.lowSeq,
                highSeq = row.highSeq,
                reachesOldest = row.reachesOldest != 0L,
                headAtVerify = row.headAtVerify != 0L,
                verifiedAtMs = row.verifiedAtMs,
                source = source,
            )
        }.also { if (!CanonicalMessageV2SegmentAlgebra.isNormalized(it)) throw CanonicalMessageV2ScopePoisoned(scope) }

    /** Writes [after] over [before] (both the scope's normalized segments): only the rows that differ. */
    private fun replaceSegmentsInsideTransaction(
        scope: SyncScopeKey,
        before: List<CanonicalMessageV2Segment>,
        after: List<CanonicalMessageV2Segment>,
    ) {
        require(CanonicalMessageV2SegmentAlgebra.isNormalized(after)) { "segments are not normalized" }
        val afterLows = after.map { it.lowSeq }.toSet()
        before.filter { it.lowSeq !in afterLows }.forEach { gone ->
            database.queries.deleteSegment(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                scopeId = scope.scopeId,
                lowSeq = gone.lowSeq,
            )
        }
        (after - before.toSet()).forEach { segment ->
            database.queries.upsertSegment(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                scopeId = scope.scopeId,
                lowSeq = segment.lowSeq,
                highSeq = segment.highSeq,
                reachesOldest = if (segment.reachesOldest) 1L else 0L,
                headAtVerify = if (segment.headAtVerify) 1L else 0L,
                verifiedAtMs = segment.verifiedAtMs,
                source = segment.source.wire,
            )
        }
    }

    /**
     * Phase 1: the legacy writers own one window, and it is the head segment. After a legacy write the
     * scope's segments are: those strictly below the window that do not touch it (none before Phase 2), then
     * the window itself. A snapshot cleared the scope's facts, so it drops every other segment; the legacy
     * cap's pruning trims every segment below the pruned edge. An unchanged window keeps its stored row.
     */
    private fun syncLegacyWindowSegmentsInsideTransaction(
        scope: SyncScopeKey,
        written: PersistedCursor,
        rebaseline: Boolean,
        prunedThroughSeq: Long?,
    ) {
        val before = selectSegmentsInsideTransaction(scope)
        var segments = if (rebaseline) emptyList() else before
        if (prunedThroughSeq != null) segments = CanonicalMessageV2SegmentAlgebra.trimBelow(segments, prunedThroughSeq)
        val window = written.legacyWindowSegment(verifiedAtMs = now())
        segments = if (window == null) {
            emptyList()
        } else {
            val unchanged = segments.firstOrNull { it.sameRangeAs(window) }
            val below = segments.filter { it.highSeq + 1L < window.effectiveLowSeq }
            (below + (unchanged ?: window)).sortedBy { it.lowSeq }
        }
        replaceSegmentsInsideTransaction(scope, before, segments)
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
            queries.clearSegmentsForPartition(
                environmentId = partition.environmentId,
                serverId = partition.serverId,
                principalId = partition.principalId,
                consumerId = partition.consumerId,
            )
            queries.clearLocalRevForPartition(
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
            consumerId___ = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
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
    /**
     * Segment-aware per-scope retention (design §6; applyCoverage). Pinned thread roots never count and are
     * never deleted. In order:
     * 1. more than [CanonicalMessageV2RetentionPolicy.maxSegmentsPerScope] segments: the least recently
     *    verified non-head segments go, with their facts;
     * 2. over [CanonicalMessageV2RetentionPolicy.maxFactsPerScope] facts: the oldest loose facts beyond
     *    [CanonicalMessageV2RetentionPolicy.looseFactAllowancePerScope];
     * 3. then whole non-head segments, least recently verified first;
     * 4. then the head's oldest facts: the head then starts above them and no longer reaches the oldest.
     * Returns the segments to store.
     */
    private fun pruneScopeBySegmentsInsideTransaction(
        scope: SyncScopeKey,
        segments: List<CanonicalMessageV2Segment>,
    ): List<CanonicalMessageV2Segment> {
        val count = database.queries.countUnpinnedFactsInScope(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsOne()
        if (count <= retention.maxFactsPerScope && segments.size <= retention.maxSegmentsPerScope) return segments
        val facts = database.queries.selectUnpinnedFactSeqsInScope(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsList().map { it.id to it.seq }.toMutableList()
        fun delete(victims: List<Pair<String, Long>>) {
            victims.forEach { (id, _) ->
                database.queries.deleteCanonicalFactInScope(
                    id = id,
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
            }
            facts.removeAll(victims.toSet())
        }
        val head = CanonicalMessageV2SegmentAlgebra.head(segments)
        val live = segments.toMutableList()
        fun leastRecentlyVerified(): CanonicalMessageV2Segment? = live
            .filter { it != head }
            .minWithOrNull(compareBy<CanonicalMessageV2Segment> { it.verifiedAtMs }.thenBy { it.lowSeq })
        fun dropSegment(victim: CanonicalMessageV2Segment) {
            delete(facts.filter { (_, seq) -> seq in victim })
            live.remove(victim)
        }
        // 1. segment cap
        while (live.size > retention.maxSegmentsPerScope) dropSegment(leastRecentlyVerified() ?: break)
        // 2. loose facts beyond the allowance, oldest first
        val looseExcess = facts.filter { (_, seq) -> CanonicalMessageV2SegmentAlgebra.memberOf(live, seq) == null }
            .let { loose -> loose.take((loose.size - retention.looseFactAllowancePerScope).coerceAtLeast(0)) }
        delete(looseExcess.take((facts.size - retention.maxFactsPerScope).coerceAtLeast(0)))
        // 3. whole non-head segments
        while (facts.size > retention.maxFactsPerScope) dropSegment(leastRecentlyVerified() ?: break)
        // 4. the head's low end
        val over = facts.size - retention.maxFactsPerScope
        if (over > 0 && head != null) {
            val trimmed = facts.filter { (_, seq) -> seq in head }.take(over)
            if (trimmed.isNotEmpty()) {
                delete(trimmed)
                val cut = trimmed.maxOf { it.second }
                val trimmedHead = CanonicalMessageV2SegmentAlgebra.trimBelow(listOf(head), cut)
                live.remove(head)
                live += trimmedHead
            }
        }
        return live.sortedBy { it.lowSeq }
    }

    private class LegacyPrune(val cursor: PersistedCursor, val prunedThroughSeq: Long)

    private fun pruneScopeInsideTransaction(scope: SyncScopeKey, cursor: PersistedCursor): LegacyPrune? {
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
        return LegacyPrune(cursor.copy(oldestLoadedSeq = oldest, hasLoadedOldest = false), newestPruned)
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
        database.queries.touchSyncCursorUserAccess(
            atMs = now(),
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
     * most recently used first: by `lastUserAccessAtMs` (schema v10; a never-opened scope ranks last), then
     * `updatedAtMs`. Every legacy write sets both to the same value, so legacy scopes rank exactly as they
     * did by `updatedAtMs`. Evicted in this order:
     * 1. `ttl`: any scope not used (opened, or for a never-opened scope written) within
     *    [CanonicalMessageV2RetentionPolicy.scopeTtlMs];
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
                lastUserAccessAtMs = row.lastUserAccessAtMs,
                factCount = row.factCount,
                byteCount = row.byteCount,
            )
        }.withDependentAccessRank()
        fun evict(stat: ScopeRetentionStats, reason: String) {
            if (stat.scope in evictedScopes) return
            logEviction(stat.scope, reason)
            val before = evictedScopes.toSet()
            evictScopeAndDependentRepliesInsideTransaction(stat.scope, evictedScopes)
            (evictedScopes - before - stat.scope).forEach { logEviction(it, "parent_evicted") }
        }

        // 1. ttl
        stats.filter { (it.lastUserAccessAtMs ?: it.updatedAtMs) < cutoff }.forEach { evict(it, "ttl") }
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
        val lastUserAccessAtMs: Long?,
        val factCount: Long,
        val byteCount: Double,
    )

    /**
     * Message storage Phase 3a: a channel / DM header written only by realtime (never opened: access NULL, ranked
     * least recently used) may be the parent of a reply thread the user did open. Evicting the parent evicts its
     * replies too (`parent_evicted`), so a top-level scope ranks by the most recent access of itself OR any of its
     * reply threads. Thread scopes keep their own rank. Same order otherwise: accessed first (newest first), then
     * by last write; a legacy scope (no dependent thread) ranks exactly as before.
     */
    private fun List<ScopeRetentionStats>.withDependentAccessRank(): List<ScopeRetentionStats> {
        val threads = filter { it.scope.scopeKind == THREAD_SCOPE_KIND && it.lastUserAccessAtMs != null }
        if (threads.isEmpty()) return this
        val childAccessByParent = mutableMapOf<SyncScopeKey, Long>()
        threads.forEach { thread ->
            val header = selectHeader(thread.scope) ?: return@forEach
            val parentKind = header.parentScopeKind ?: return@forEach
            val parentId = header.parentScopeId ?: return@forEach
            val parent = SyncScopeKey(partition = thread.scope.partition, scopeId = parentId, scopeKind = parentKind)
            val access = checkNotNull(thread.lastUserAccessAtMs)
            childAccessByParent[parent] = maxOf(childAccessByParent[parent] ?: access, access)
        }
        if (childAccessByParent.isEmpty()) return this
        return map { stat ->
            val childAccess = childAccessByParent[stat.scope] ?: return@map stat
            stat.copy(lastUserAccessAtMs = maxOf(stat.lastUserAccessAtMs ?: childAccess, childAccess))
        }.sortedWith(
            compareBy<ScopeRetentionStats> { it.lastUserAccessAtMs == null }
                .thenByDescending { it.lastUserAccessAtMs ?: Long.MIN_VALUE }
                .thenByDescending { it.updatedAtMs }
                .thenBy { it.scope.scopeKind }
                .thenBy { it.scope.scopeId },
        )
    }

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
        database.queries.clearSegmentsForScope(
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
    /** Segment-aware scope retention (applyCoverage): at most this many segments per scope. */
    val maxSegmentsPerScope: Int = 32,
    /**
     * Segment-aware scope retention: loose facts (in no segment) up to this many are kept before a
     * segment is evicted; beyond it the oldest loose facts go first.
     */
    val looseFactAllowancePerScope: Int = minOf(100, maxFactsPerScope - 1),
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
        require(maxSegmentsPerScope > 0)
        require(looseFactAllowancePerScope in 0 until maxFactsPerScope)
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

/**
 * The legacy window of a cursor as a segment: `[oldest, newest]`, or `[0, 0]` for an empty baseline
 * (both edges NULL, oldest reached). `null` = the cursor claims no window.
 */
internal fun PersistedCursor.legacyWindowSegment(
    verifiedAtMs: Long,
    source: CanonicalMessageV2SegmentSource = CanonicalMessageV2SegmentSource.Legacy,
): CanonicalMessageV2Segment? {
    val oldest = oldestLoadedSeq
    val newest = newestLoadedSeq
    return when {
        oldest != null && newest != null && oldest in 0L..newest ->
            CanonicalMessageV2Segment(oldest, newest, hasLoadedOldest, hasLoadedNewest, verifiedAtMs, source)
        oldest == null && newest == null && hasLoadedOldest ->
            CanonicalMessageV2Segment(0L, 0L, reachesOldest = true, headAtVerify = hasLoadedNewest, verifiedAtMs, source)
        else -> null
    }
}

/**
 * This cursor with its window columns mirroring [head] (the scope's head segment; `null` = no window). The
 * edges keep the legacy convention — they are seqs of stored facts ([headFactBounds]: the oldest / newest
 * stored fact inside the head's range), never a claim bound no message has; a head without a stored fact
 * (an empty baseline) has no edges. The flags are the head's.
 */
internal fun PersistedCursor.mirroring(head: CanonicalMessageV2Segment?, headFactBounds: Pair<Long, Long>?): PersistedCursor = when {
    head == null -> copy(oldestLoadedSeq = null, newestLoadedSeq = null, hasLoadedOldest = false, hasLoadedNewest = false)
    else -> copy(
        oldestLoadedSeq = headFactBounds?.first,
        newestLoadedSeq = headFactBounds?.second,
        hasLoadedOldest = head.reachesOldest,
        hasLoadedNewest = head.headAtVerify,
    )
}

private fun CanonicalMessageV2Segment.sameRangeAs(other: CanonicalMessageV2Segment): Boolean =
    lowSeq == other.lowSeq && highSeq == other.highSeq &&
        reachesOldest == other.reachesOldest && headAtVerify == other.headAtVerify

/** Task #14a: the `unknownFields` column value; `null` = every aggregate known. */
private fun encodeUnknownFields(fields: Set<MessageAggregateField>): String? =
    CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.filter { it in fields }.joinToString(",").ifEmpty { null }

/** This envelope with [fields] (allowed-unknown aggregates only) taken from [source]. */
/** The `$.fact.deletedAt` key as it appears in canonical bytes (pre-filter only; the decode decides). */
private const val RETRACTION_BYTES_MARKER = "\"deletedAt\":"

/** [retracted] (the stored copy of this fact, known retracted) over a frame that does not know it. */
private fun CanonicalMessageEnvelopeV2.withStoredRetraction(retracted: CanonicalMessageNodeV2?): CanonicalMessageEnvelopeV2 =
    if (retracted == null || fact.deletedAt != null) {
        this
    } else {
        copy(
            fact = fact.copy(
                deletedAt = retracted.deletedAt,
                content = retracted.content,
                attachments = emptyList(),
                reactions = emptyList(),
            ),
        )
    }

/** The manifest-v8 optional action-card keys as they appear in canonical bytes (pre-filter only). */
private val ACTION_CARD_ONE_WAY_BYTES_MARKERS = listOf(
    "\"sourceServerId\":", "\"targetServerName\":", "\"confirmationVersion\":", "\"presentation\":",
)

/** [storedCard] (the stored copy of this card) under a frame card that may not know its optional keys. */
private fun CanonicalMessageEnvelopeV2.withStoredActionCardKeys(
    storedCard: CanonicalActionMetadataV2.ActionCard?,
): CanonicalMessageEnvelopeV2 {
    val card = fact.actionMetadata as? CanonicalActionMetadataV2.ActionCard ?: return this
    if (storedCard == null) return this
    val kept = card.copy(
        sourceServerId = card.sourceServerId ?: storedCard.sourceServerId,
        targetServerName = card.targetServerName ?: storedCard.targetServerName,
        confirmationVersion = listOfNotNull(card.confirmationVersion, storedCard.confirmationVersion).maxOrNull(),
        presentation = card.presentation ?: storedCard.presentation,
    )
    return if (kept == card) this else copy(fact = fact.copy(actionMetadata = kept))
}

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

private fun requireUnknownFieldsInFrame(
    envelopes: List<CanonicalMessageEnvelopeV2>,
    unknownFieldsByFactId: Map<String, Set<MessageAggregateField>>,
) {
    require(
        unknownFieldsByFactId.all { (id, fields) ->
            fields.isNotEmpty() && CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.containsAll(fields) &&
                envelopes.any { it.fact.id == id }
        },
    ) { "unknown V2 aggregates outside the allowed set or the frame" }
}

private fun PersistedCursor.threadParent(): CanonicalMessageV2ThreadParent? {
    val kind = parentScopeKind ?: return null
    val id = parentScopeId ?: return null
    val root = rootMessageId ?: return null
    return CanonicalMessageV2ThreadParent(kind, id, root)
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
        is TopLevelScopeRefV2.Task -> CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND
    }
