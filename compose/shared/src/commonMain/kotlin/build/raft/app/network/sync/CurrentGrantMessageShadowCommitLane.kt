package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.RaftSqlDatabase
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compatibility event kind used by the existing process-local realtime dedup key. */
internal enum class CanonicalMessageFoldKind {
    New,
    Updated,
}

/**
 * The exact legacy realtime dedup string. This is deliberately not named or treated as a producer mutation
 * identity: Updated frames do not currently carry one.
 */
internal data class LegacyRealtimeDedupKey(val value: String) {
    init {
        require(value.isNotBlank()) { "legacy realtime dedup key must not be blank" }
    }
}

/** The complete canonical input that can affect the messages fold; presentation/task projections are absent. */
internal data class CanonicalMessageFoldInput(
    val kind: CanonicalMessageFoldKind,
    val row: CanonicalMessageRow,
    val presentAggregates: List<MessageAggregateField>,
) {
    companion object {
        fun from(kind: CanonicalMessageFoldKind, carry: CanonicalMessageCarry): CanonicalMessageFoldInput =
            CanonicalMessageFoldInput(
                kind = kind,
                row = carry.row,
                presentAggregates = carry.presentAggregates.sortedBy { aggregate -> aggregate.name },
            )
    }
}

/** Hash is an index hint only; callers must compare [structuralInput] after a hash match. */
internal data class CanonicalMessageFoldFingerprint(
    val structuralHash: Int,
    val structuralInput: CanonicalMessageFoldInput,
) {
    companion object {
        fun from(input: CanonicalMessageFoldInput): CanonicalMessageFoldFingerprint =
            CanonicalMessageFoldFingerprint(input.hashCode(), input)
    }
}

/** One fact+cursor snapshot read from one durable S/P/C scope. */
internal data class CommittedMessageScopeSnapshot(
    val scope: SyncScopeKey,
    val facts: List<CanonicalMessageRow>,
    val cursor: PersistedCursor?,
)

internal enum class StrictMessageRestoreCorruptionKind {
    ReaderNotImplemented,
    FactDecodeFailed,
    FactScalarMismatch,
    FactPartitionMismatch,
    FactScopeMismatch,
    DuplicateFactId,
    FactOrderInvalid,
    CursorMissing,
    CursorPrecedesFacts,
}

internal data class StrictMessageRestoreCorruption(
    val kind: StrictMessageRestoreCorruptionKind,
    val factId: String? = null,
)

/** A strict facts+cursor read performed under one consistent database read snapshot. */
internal sealed interface StrictMessageRestoreResult {
    data class Valid(val snapshot: CommittedMessageScopeSnapshot) : StrictMessageRestoreResult
    data class Corrupt(val corruption: StrictMessageRestoreCorruption) : StrictMessageRestoreResult
}

internal fun interface StrictMessageScopeSnapshotReader {
    suspend fun read(scope: SyncScopeKey): StrictMessageRestoreResult

    companion object {
        val Unavailable = StrictMessageScopeSnapshotReader { scope ->
            StrictMessageRestoreResult.Corrupt(
                StrictMessageRestoreCorruption(
                    kind = StrictMessageRestoreCorruptionKind.ReaderNotImplemented,
                    factId = scope.scopeId,
                ),
            )
        }
    }
}

/** Raw stored columns consumed by the strict validator before any lossy canonical mapping. */
internal data class StrictMessageStoredFact(
    val serverId: String,
    val principalId: String,
    val consumerId: String,
    val id: String,
    val channelId: String,
    val scopeKind: String,
    val seq: Long,
    val createdAt: String,
    val canonicalFactJson: String,
)

/** Strict all-or-corrupt validation of one raw durable scope snapshot. */
internal class StrictMessageScopeSnapshotValidator {
    fun validate(
        scope: SyncScopeKey,
        storedFacts: List<StrictMessageStoredFact>,
        cursor: PersistedCursor?,
    ): StrictMessageRestoreResult {
        val decoded = ArrayList<Pair<StrictMessageStoredFact, CanonicalMessageRow>>(storedFacts.size)
        for (stored in storedFacts) {
            val row = decodeCanonicalFact(stored.canonicalFactJson)
                ?: return corrupt(StrictMessageRestoreCorruptionKind.FactDecodeFailed, stored.id)
            decoded += stored to row
        }

        val decodedIds = mutableSetOf<String>()
        decoded.firstOrNull { (_, row) -> !decodedIds.add(row.id) }
            ?.let { (_, row) -> return corrupt(StrictMessageRestoreCorruptionKind.DuplicateFactId, row.id) }

        for ((stored, row) in decoded) {
            if (stored.id != row.id || stored.seq != row.seq || stored.createdAt != row.createdAt) {
                return corrupt(StrictMessageRestoreCorruptionKind.FactScalarMismatch, stored.id)
            }
            if (
                stored.serverId != scope.serverId ||
                stored.principalId != scope.principalId ||
                stored.consumerId != scope.consumerId
            ) {
                return corrupt(StrictMessageRestoreCorruptionKind.FactPartitionMismatch, stored.id)
            }
            if (
                stored.scopeKind != scope.scopeKind ||
                stored.channelId != scope.scopeId ||
                row.channelId != scope.scopeId
            ) {
                return corrupt(StrictMessageRestoreCorruptionKind.FactScopeMismatch, stored.id)
            }
        }

        val rows = decoded.map { (_, row) -> row }
        for (index in 1 until rows.size) {
            val previous = rows[index - 1]
            val current = rows[index]
            if (previous.seq > current.seq || (previous.seq == current.seq && previous.id >= current.id)) {
                return corrupt(StrictMessageRestoreCorruptionKind.FactOrderInvalid, current.id)
            }
        }

        if (rows.isNotEmpty()) {
            val durableCursor = cursor
                ?: return corrupt(StrictMessageRestoreCorruptionKind.CursorMissing, rows.last().id)
            val minimumSeq = rows.minOf { row -> row.seq }
            val maximumSeq = rows.maxOf { row -> row.seq }
            if (
                durableCursor.latestSeq == null || durableCursor.latestSeq < maximumSeq ||
                durableCursor.newestLoadedSeq == null || durableCursor.newestLoadedSeq < maximumSeq ||
                durableCursor.oldestLoadedSeq?.let { oldest -> oldest > minimumSeq } == true
            ) {
                return corrupt(StrictMessageRestoreCorruptionKind.CursorPrecedesFacts, rows.last().id)
            }
        }

        return StrictMessageRestoreResult.Valid(
            CommittedMessageScopeSnapshot(scope = scope, facts = rows, cursor = cursor),
        )
    }

    private fun corrupt(
        kind: StrictMessageRestoreCorruptionKind,
        factId: String?,
    ): StrictMessageRestoreResult.Corrupt =
        StrictMessageRestoreResult.Corrupt(StrictMessageRestoreCorruption(kind, factId))
}

internal data class StrictMessageSqlDelightReaderHooks(
    /** Synchronous only: SQLDelight's transaction callback is backed by runBlocking. */
    val afterFactsRead: () -> Unit = {},
)

/**
 * Strict SQLDelight reader. Facts and cursor are read and validated inside one database read transaction, so
 * a concurrent writer cannot produce an old-facts/new-cursor mixed snapshot.
 */
internal class SqlDelightStrictMessageScopeSnapshotReader(
    internal val database: RaftSqlDatabase,
    internal val validator: StrictMessageScopeSnapshotValidator = StrictMessageScopeSnapshotValidator(),
    internal val hooks: StrictMessageSqlDelightReaderHooks = StrictMessageSqlDelightReaderHooks(),
) : StrictMessageScopeSnapshotReader {
    override suspend fun read(scope: SyncScopeKey): StrictMessageRestoreResult {
        var result: StrictMessageRestoreResult? = null
        database.transaction {
            val storedFacts = database.queries
                .selectServerBackedMessagesForChannel(
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
                .executeAsList()
                .map { row ->
                    StrictMessageStoredFact(
                        serverId = row.serverId,
                        principalId = row.principalId,
                        consumerId = row.consumerId,
                        id = row.id,
                        channelId = row.channelId,
                        scopeKind = row.scopeKind,
                        seq = row.seq,
                        createdAt = row.createdAt,
                        canonicalFactJson = row.canonicalFactJson,
                    )
                }
            hooks.afterFactsRead()
            val cursor = database.queries
                .selectSyncCursor(
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    scopeId = scope.scopeId,
                )
                .executeAsOneOrNull()
                ?.let { stored ->
                    PersistedCursor(
                        latestSeq = stored.latestSeq,
                        oldestLoadedSeq = stored.oldestLoadedSeq,
                        newestLoadedSeq = stored.newestLoadedSeq,
                        hasLoadedOldest = stored.hasLoadedOldest != 0L,
                        hasLoadedNewest = stored.hasLoadedNewest != 0L,
                        epoch = stored.epoch,
                    )
                }
            result = validator.validate(scope, storedFacts, cursor)
        }
        return checkNotNull(result) { "strict message scope read completed without a result" }
    }
}

/** Observed before restore/write so rollback and pre-publish faults cannot erase collision evidence. */
internal data class ObservedLegacyMessageInput(
    val key: LegacyRealtimeDedupKey,
    val fingerprint: CanonicalMessageFoldFingerprint,
)

/** Process-local acceleration only. Durable restore never reconstructs this entry. */
internal data class CommittedMessageFoldAcceleration(
    val key: LegacyRealtimeDedupKey,
    val fingerprint: CanonicalMessageFoldFingerprint,
)

internal sealed interface MessageShadowWorkingState {
    data object Cold : MessageShadowWorkingState
    data class Ready(val snapshot: CommittedMessageScopeSnapshot) : MessageShadowWorkingState
    data class NeedsRestore(val reason: String) : MessageShadowWorkingState
    data class Poisoned(val corruption: StrictMessageRestoreCorruption) : MessageShadowWorkingState
}

internal data class MessageShadowWorkingCell(
    val state: MessageShadowWorkingState = MessageShadowWorkingState.Cold,
    val observedLegacyInputs: List<ObservedLegacyMessageInput> = emptyList(),
    val inFlight: CanonicalMessageFoldFingerprint? = null,
)

/**
 * One immutable published value. Snapshot visibility and the bounded committed acceleration ledger move in
 * the same non-suspending atomic assignment; observers receive only [snapshotsByScope].
 */
internal data class PublishedMessageShadowState(
    val snapshotsByScope: Map<SyncScopeKey, CommittedMessageScopeSnapshot> = emptyMap(),
    val committedAccelerationByScope: Map<SyncScopeKey, List<CommittedMessageFoldAcceleration>> = emptyMap(),
)

internal class AtomicPublishedMessageShadow(
    initial: PublishedMessageShadowState = PublishedMessageShadowState(),
) {
    private val state = atomic(initial)

    fun value(): PublishedMessageShadowState = state.value

    /** The caller owns the lane mutex; [beforeSwap] is the deterministic pre-publication fault seam. */
    fun replace(next: PublishedMessageShadowState, beforeSwap: () -> Unit = {}) {
        beforeSwap()
        state.value = next
    }
}

internal data class CurrentGrantMessageShadowFaultHooks(
    val beforePublishedSwap: () -> Unit = {},
)

internal sealed interface CurrentGrantMessageShadowCommitOutcome {
    data object Disabled : CurrentGrantMessageShadowCommitOutcome
    data object Committed : CurrentGrantMessageShadowCommitOutcome
    data object DurableNoChange : CurrentGrantMessageShadowCommitOutcome
    data object IgnoredMissingUpdatedBase : CurrentGrantMessageShadowCommitOutcome
    data class FailedOpen(val reason: String) : CurrentGrantMessageShadowCommitOutcome
    data object LegacyDedupCollision : CurrentGrantMessageShadowCommitOutcome
}

/** Rich prepared-only disposition. The old [CurrentGrantMessageShadowCommitOutcome] API remains unchanged. */
internal sealed interface PreparedCurrentGrantMessageCommitDisposition {
    data object FinalizableCommitted : PreparedCurrentGrantMessageCommitDisposition
    data object FinalizableDurableNoChange : PreparedCurrentGrantMessageCommitDisposition

    sealed interface LegacyFallback : PreparedCurrentGrantMessageCommitDisposition {
        data object MissingUpdatedBase : LegacyFallback
        data object LegacyDedupCollision : LegacyFallback
        data class DefinitelyPrewrite(val reason: String) : LegacyFallback
    }

    sealed interface Indeterminate : PreparedCurrentGrantMessageCommitDisposition {
        data class CommitOutcomeUnknown(val reason: String) : Indeterminate
        data class PostCommitReadbackFailed(val reason: String) : Indeterminate
        data class PostCommitReadbackCorrupt(val reason: String) : Indeterminate
        data class PostCommitPublishFailed(val reason: String) : Indeterminate
    }
}

private sealed interface CurrentGrantMessageShadowCommitResult {
    data object Committed : CurrentGrantMessageShadowCommitResult
    data object DurableNoChange : CurrentGrantMessageShadowCommitResult
    data object IgnoredMissingUpdatedBase : CurrentGrantMessageShadowCommitResult
    data object LegacyDedupCollision : CurrentGrantMessageShadowCommitResult
    data class PrewriteFailed(val reason: String) : CurrentGrantMessageShadowCommitResult
    data class CommitOutcomeUnknown(val reason: String) : CurrentGrantMessageShadowCommitResult
    data class PostCommitReadbackFailed(val reason: String) : CurrentGrantMessageShadowCommitResult
    data class PostCommitReadbackCorrupt(val reason: String) : CurrentGrantMessageShadowCommitResult
    data class PostCommitPublishFailed(val reason: String) : CurrentGrantMessageShadowCommitResult
}

private fun CurrentGrantMessageShadowCommitResult.toLegacyOutcome(): CurrentGrantMessageShadowCommitOutcome =
    when (this) {
        CurrentGrantMessageShadowCommitResult.Committed -> CurrentGrantMessageShadowCommitOutcome.Committed
        CurrentGrantMessageShadowCommitResult.DurableNoChange -> CurrentGrantMessageShadowCommitOutcome.DurableNoChange
        CurrentGrantMessageShadowCommitResult.IgnoredMissingUpdatedBase ->
            CurrentGrantMessageShadowCommitOutcome.IgnoredMissingUpdatedBase
        CurrentGrantMessageShadowCommitResult.LegacyDedupCollision ->
            CurrentGrantMessageShadowCommitOutcome.LegacyDedupCollision
        is CurrentGrantMessageShadowCommitResult.PrewriteFailed -> CurrentGrantMessageShadowCommitOutcome.FailedOpen(reason)
        is CurrentGrantMessageShadowCommitResult.CommitOutcomeUnknown ->
            CurrentGrantMessageShadowCommitOutcome.FailedOpen(reason)
        is CurrentGrantMessageShadowCommitResult.PostCommitReadbackFailed ->
            CurrentGrantMessageShadowCommitOutcome.FailedOpen(reason)
        is CurrentGrantMessageShadowCommitResult.PostCommitReadbackCorrupt ->
            CurrentGrantMessageShadowCommitOutcome.FailedOpen(reason)
        is CurrentGrantMessageShadowCommitResult.PostCommitPublishFailed ->
            CurrentGrantMessageShadowCommitOutcome.FailedOpen(reason)
    }

private fun CurrentGrantMessageShadowCommitResult.toPreparedDisposition():
    PreparedCurrentGrantMessageCommitDisposition =
    when (this) {
        CurrentGrantMessageShadowCommitResult.Committed ->
            PreparedCurrentGrantMessageCommitDisposition.FinalizableCommitted
        CurrentGrantMessageShadowCommitResult.DurableNoChange ->
            PreparedCurrentGrantMessageCommitDisposition.FinalizableDurableNoChange
        CurrentGrantMessageShadowCommitResult.IgnoredMissingUpdatedBase ->
            PreparedCurrentGrantMessageCommitDisposition.LegacyFallback.MissingUpdatedBase
        CurrentGrantMessageShadowCommitResult.LegacyDedupCollision ->
            PreparedCurrentGrantMessageCommitDisposition.LegacyFallback.LegacyDedupCollision
        is CurrentGrantMessageShadowCommitResult.PrewriteFailed ->
            PreparedCurrentGrantMessageCommitDisposition.LegacyFallback.DefinitelyPrewrite(reason)
        is CurrentGrantMessageShadowCommitResult.CommitOutcomeUnknown ->
            PreparedCurrentGrantMessageCommitDisposition.Indeterminate.CommitOutcomeUnknown(reason)
        is CurrentGrantMessageShadowCommitResult.PostCommitReadbackFailed ->
            PreparedCurrentGrantMessageCommitDisposition.Indeterminate.PostCommitReadbackFailed(reason)
        is CurrentGrantMessageShadowCommitResult.PostCommitReadbackCorrupt ->
            PreparedCurrentGrantMessageCommitDisposition.Indeterminate.PostCommitReadbackCorrupt(reason)
        is CurrentGrantMessageShadowCommitResult.PostCommitPublishFailed ->
            PreparedCurrentGrantMessageCommitDisposition.Indeterminate.PostCommitPublishFailed(reason)
    }

/**
 * Provider-central current-grant shadow commit lane. A caller already holds the realtime authority registry
 * mutex; this lane adds one process-wide mutex before the SQLDelight transaction, so every owner sharing the
 * database observes the same restore/fold/COMMIT/readback/publication order.
 */
internal class CurrentGrantMessageShadowCommitLane(
    internal val strictReader: StrictMessageScopeSnapshotReader = StrictMessageScopeSnapshotReader.Unavailable,
    internal val persistence: FoldPersistenceAdapter? = null,
    internal val isEnabledForServer: (String) -> Boolean = { false },
    internal val faultHooks: CurrentGrantMessageShadowFaultHooks = CurrentGrantMessageShadowFaultHooks(),
    internal val observedLedgerLimitPerScope: Int = DEFAULT_LEDGER_LIMIT_PER_SCOPE,
    internal val committedLedgerLimitPerScope: Int = DEFAULT_LEDGER_LIMIT_PER_SCOPE,
) {
    init {
        require(observedLedgerLimitPerScope > 0) { "observed ledger limit must be positive" }
        require(committedLedgerLimitPerScope > 0) { "committed ledger limit must be positive" }
    }

    internal val published = AtomicPublishedMessageShadow()
    internal val workingByScope = mutableMapOf<SyncScopeKey, MessageShadowWorkingCell>()
    private val mutex = Mutex()

    suspend fun commit(
        partition: DurablePartitionKey,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
    ): CurrentGrantMessageShadowCommitOutcome {
        if (!isEnabledForServer(partition.serverId)) {
            return CurrentGrantMessageShadowCommitOutcome.Disabled
        }
        val scope = SyncScopeKey(
            partition = partition,
            scopeId = foldInput.row.channelId,
            scopeKind = "channel",
        )
        val fingerprint = CanonicalMessageFoldFingerprint.from(foldInput)
        return mutex.withLock {
            commitLocked(scope, legacyKey, foldInput, fingerprint).toLegacyOutcome()
        }
    }

    /**
     * Prepared T4 entry. Authority and the captured two-flag decision are proven by the provider token before
     * this method is called, so this API never re-evaluates rollout and cannot return Disabled.
     */
    suspend fun commitPrepared(
        partition: DurablePartitionKey,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
    ): PreparedCurrentGrantMessageCommitDisposition {
        val scope = SyncScopeKey(
            partition = partition,
            scopeId = foldInput.row.channelId,
            scopeKind = "channel",
        )
        val fingerprint = CanonicalMessageFoldFingerprint.from(foldInput)
        return mutex.withLock {
            commitLocked(scope, legacyKey, foldInput, fingerprint).toPreparedDisposition()
        }
    }

    private suspend fun commitLocked(
        scope: SyncScopeKey,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
        fingerprint: CanonicalMessageFoldFingerprint,
    ): CurrentGrantMessageShadowCommitResult {
        val initialCell = workingByScope[scope] ?: MessageShadowWorkingCell()
        val observed = initialCell.observedLegacyInputs.firstOrNull { entry -> entry.key == legacyKey }
        if (observed != null && observed.fingerprint.structuralInput != fingerprint.structuralInput) {
            return CurrentGrantMessageShadowCommitResult.LegacyDedupCollision
        }
        var cell = initialCell.copy(
            observedLegacyInputs = touchObserved(initialCell.observedLegacyInputs, legacyKey, fingerprint),
        )
        workingByScope[scope] = cell

        val committed = published.value().committedAccelerationByScope[scope]
            .orEmpty()
            .firstOrNull { entry -> entry.key == legacyKey }
        if (committed != null && committed.fingerprint.structuralInput != fingerprint.structuralInput) {
            return CurrentGrantMessageShadowCommitResult.LegacyDedupCollision
        }
        if (committed != null && cell.state is MessageShadowWorkingState.Ready) {
            val current = published.value()
            published.replace(
                current.copy(
                    committedAccelerationByScope = current.committedAccelerationByScope +
                        (scope to touchCommitted(
                            current.committedAccelerationByScope[scope].orEmpty(),
                            legacyKey,
                            fingerprint,
                        )),
                ),
            )
            return CurrentGrantMessageShadowCommitResult.DurableNoChange
        }

        val base = when (val state = cell.state) {
            is MessageShadowWorkingState.Ready -> state.snapshot
            is MessageShadowWorkingState.Poisoned ->
                return CurrentGrantMessageShadowCommitResult.PrewriteFailed("restore_poisoned")
            MessageShadowWorkingState.Cold,
            is MessageShadowWorkingState.NeedsRestore -> {
                val restored = try {
                    strictReader.read(scope)
                } catch (cancellation: CancellationException) {
                    workingByScope[scope] = cell.copy(inFlight = null)
                    throw cancellation
                } catch (throwable: Throwable) {
                    workingByScope[scope] = cell.copy(
                        state = MessageShadowWorkingState.NeedsRestore("restore_failed"),
                        inFlight = null,
                    )
                    return CurrentGrantMessageShadowCommitResult.PrewriteFailed(
                        throwable::class.simpleName ?: "restore_failed",
                    )
                }
                when (restored) {
                    is StrictMessageRestoreResult.Corrupt -> {
                        workingByScope[scope] = cell.copy(
                            state = MessageShadowWorkingState.Poisoned(restored.corruption),
                            inFlight = null,
                        )
                        return CurrentGrantMessageShadowCommitResult.PrewriteFailed(
                            "restore_corrupt_${restored.corruption.kind.name}",
                        )
                    }
                    is StrictMessageRestoreResult.Valid -> {
                        cell = cell.copy(
                            state = MessageShadowWorkingState.Ready(restored.snapshot),
                            inFlight = null,
                        )
                        workingByScope[scope] = cell
                        publishRestoredSnapshot(scope, restored.snapshot)
                        restored.snapshot
                    }
                }
            }
        }

        if (
            foldInput.kind == CanonicalMessageFoldKind.Updated &&
            base.facts.none { fact -> fact.id == foldInput.row.id }
        ) {
            workingByScope[scope] = cell.copy(
                state = MessageShadowWorkingState.Ready(base),
                inFlight = null,
            )
            return CurrentGrantMessageShadowCommitResult.IgnoredMissingUpdatedBase
        }

        val currentState = MessageDomainState(channelMessages = mapOf(scope.scopeId to base.facts))
        val event = when (foldInput.kind) {
            CanonicalMessageFoldKind.New ->
                MessageDomainEvent.New(foldInput.row, foldInput.presentAggregates.toSet())
            CanonicalMessageFoldKind.Updated ->
                MessageDomainEvent.Updated(foldInput.row, foldInput.presentAggregates.toSet())
        }
        val candidateState = applyMessageDomainEvent(currentState, event)
        val candidateFacts = candidateState.channelMessages[scope.scopeId].orEmpty()
        if (candidateFacts == base.facts) {
            val ready = cell.copy(
                state = MessageShadowWorkingState.Ready(base),
                inFlight = null,
            )
            workingByScope[scope] = ready
            publishCommitted(scope, base, legacyKey, fingerprint)
            return CurrentGrantMessageShadowCommitResult.DurableNoChange
        }

        cell = cell.copy(
            state = MessageShadowWorkingState.Ready(base),
            inFlight = fingerprint,
        )
        workingByScope[scope] = cell
        val candidate = CommittedMessageScopeSnapshot(
            scope = scope,
            facts = candidateFacts,
            cursor = cursorAfterFold(base.cursor, foldInput),
        )
        val adapter = persistence
        if (adapter == null) {
            workingByScope[scope] = cell.copy(inFlight = null)
            return CurrentGrantMessageShadowCommitResult.PrewriteFailed("persistence_unavailable")
        }

        try {
            adapter.applyFrame(
                FoldPersistFrame(
                    scope = scope,
                    facts = candidate.facts,
                    cursor = checkNotNull(candidate.cursor),
                ),
            )
        } catch (cancellation: CancellationException) {
            workingByScope[scope] = cell.copy(
                state = MessageShadowWorkingState.Ready(base),
                inFlight = null,
            )
            throw cancellation
        } catch (throwable: Throwable) {
            workingByScope[scope] = cell.copy(
                state = MessageShadowWorkingState.NeedsRestore("commit_outcome_unknown"),
                inFlight = null,
            )
            return CurrentGrantMessageShadowCommitResult.CommitOutcomeUnknown(
                throwable::class.simpleName ?: "commit_failed",
            )
        }

        val readback = try {
            strictReader.read(scope)
        } catch (cancellation: CancellationException) {
            markNeedsRestore(scope, cell, "post_commit_readback_cancelled")
            throw cancellation
        } catch (throwable: Throwable) {
            markNeedsRestore(scope, cell, "post_commit_readback_failed")
            return CurrentGrantMessageShadowCommitResult.PostCommitReadbackFailed(
                throwable::class.simpleName ?: "readback_failed",
            )
        }
        val committedSnapshot = when (readback) {
            is StrictMessageRestoreResult.Corrupt -> {
                markNeedsRestore(scope, cell, "post_commit_readback_corrupt")
                return CurrentGrantMessageShadowCommitResult.PostCommitReadbackCorrupt(
                    "readback_corrupt_${readback.corruption.kind.name}",
                )
            }
            is StrictMessageRestoreResult.Valid -> readback.snapshot
        }

        val nextPublished = published.value().let { current ->
            current.copy(
                snapshotsByScope = current.snapshotsByScope + (scope to committedSnapshot),
                committedAccelerationByScope = current.committedAccelerationByScope +
                    (scope to touchCommitted(
                        current.committedAccelerationByScope[scope].orEmpty(),
                        legacyKey,
                        fingerprint,
                    )),
            )
        }
        try {
            published.replace(nextPublished, faultHooks.beforePublishedSwap)
        } catch (cancellation: CancellationException) {
            markNeedsRestore(scope, cell, "post_commit_publish_cancelled")
            throw cancellation
        } catch (throwable: Throwable) {
            markNeedsRestore(scope, cell, "post_commit_publish_failed")
            return CurrentGrantMessageShadowCommitResult.PostCommitPublishFailed(
                throwable::class.simpleName ?: "publish_failed",
            )
        }
        workingByScope[scope] = cell.copy(
            state = MessageShadowWorkingState.Ready(committedSnapshot),
            inFlight = null,
        )
        return CurrentGrantMessageShadowCommitResult.Committed
    }

    private fun publishRestoredSnapshot(
        scope: SyncScopeKey,
        snapshot: CommittedMessageScopeSnapshot,
    ) {
        val current = published.value()
        published.replace(
            current.copy(snapshotsByScope = current.snapshotsByScope + (scope to snapshot)),
        )
    }

    private fun publishCommitted(
        scope: SyncScopeKey,
        snapshot: CommittedMessageScopeSnapshot,
        key: LegacyRealtimeDedupKey,
        fingerprint: CanonicalMessageFoldFingerprint,
    ) {
        val current = published.value()
        published.replace(
            current.copy(
                snapshotsByScope = current.snapshotsByScope + (scope to snapshot),
                committedAccelerationByScope = current.committedAccelerationByScope +
                    (scope to touchCommitted(
                        current.committedAccelerationByScope[scope].orEmpty(),
                        key,
                        fingerprint,
                    )),
            ),
        )
    }

    private fun markNeedsRestore(
        scope: SyncScopeKey,
        cell: MessageShadowWorkingCell,
        reason: String,
    ) {
        workingByScope[scope] = cell.copy(
            state = MessageShadowWorkingState.NeedsRestore(reason),
            inFlight = null,
        )
    }

    private fun cursorAfterFold(
        current: PersistedCursor?,
        input: CanonicalMessageFoldInput,
    ): PersistedCursor =
        if (input.kind == CanonicalMessageFoldKind.Updated && current != null) {
            current
        } else {
            val seq = input.row.seq
            PersistedCursor(
                latestSeq = maxNullable(current?.latestSeq, seq),
                oldestLoadedSeq = minNullable(current?.oldestLoadedSeq, seq),
                newestLoadedSeq = maxNullable(current?.newestLoadedSeq, seq),
                hasLoadedOldest = current?.hasLoadedOldest ?: false,
                hasLoadedNewest = current?.hasLoadedNewest ?: true,
                epoch = current?.epoch,
            )
        }

    private fun maxNullable(current: Long?, incoming: Long): Long =
        current?.let { maxOf(it, incoming) } ?: incoming

    private fun minNullable(current: Long?, incoming: Long): Long =
        current?.let { minOf(it, incoming) } ?: incoming

    private fun touchObserved(
        current: List<ObservedLegacyMessageInput>,
        key: LegacyRealtimeDedupKey,
        fingerprint: CanonicalMessageFoldFingerprint,
    ): List<ObservedLegacyMessageInput> =
        (current.filterNot { entry -> entry.key == key } + ObservedLegacyMessageInput(key, fingerprint))
            .takeLast(observedLedgerLimitPerScope)

    private fun touchCommitted(
        current: List<CommittedMessageFoldAcceleration>,
        key: LegacyRealtimeDedupKey,
        fingerprint: CanonicalMessageFoldFingerprint,
    ): List<CommittedMessageFoldAcceleration> =
        (current.filterNot { entry -> entry.key == key } + CommittedMessageFoldAcceleration(key, fingerprint))
            .takeLast(committedLedgerLimitPerScope)

    companion object {
        const val DEFAULT_LEDGER_LIMIT_PER_SCOPE: Int = 256
    }
}
