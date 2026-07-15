package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.SlockSqlDatabase
import kotlinx.atomicfu.atomic

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

/** Dormant validator seam. GREEN supplies the exact decode/scalar/partition/scope/order/cursor checks. */
internal class StrictMessageScopeSnapshotValidator {
    fun validate(
        scope: SyncScopeKey,
        storedFacts: List<StrictMessageStoredFact>,
        cursor: PersistedCursor?,
    ): StrictMessageRestoreResult {
        @Suppress("UNUSED_VARIABLE")
        val seamInputs = Triple(scope, storedFacts, cursor)
        return StrictMessageRestoreResult.Corrupt(
            StrictMessageRestoreCorruption(StrictMessageRestoreCorruptionKind.ReaderNotImplemented),
        )
    }
}

internal data class StrictMessageSqlDelightReaderHooks(
    /** Synchronous only: SQLDelight's transaction callback is backed by runBlocking. */
    val afterFactsRead: () -> Unit = {},
)

/**
 * Instantiable strict SQLDelight reader seam. It is deliberately unbound from the provider/central lane and
 * returns ReaderNotImplemented until the correction REDs prove validation and one-snapshot read semantics.
 */
internal class SqlDelightStrictMessageScopeSnapshotReader(
    internal val database: SlockSqlDatabase,
    internal val validator: StrictMessageScopeSnapshotValidator = StrictMessageScopeSnapshotValidator(),
    internal val hooks: StrictMessageSqlDelightReaderHooks = StrictMessageSqlDelightReaderHooks(),
) : StrictMessageScopeSnapshotReader {
    override suspend fun read(scope: SyncScopeKey): StrictMessageRestoreResult =
        StrictMessageRestoreResult.Corrupt(
            StrictMessageRestoreCorruption(
                kind = StrictMessageRestoreCorruptionKind.ReaderNotImplemented,
                factId = scope.scopeId,
            ),
        )
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
    /** Seam-only state: construction/injection exists, but guarded behavior is intentionally not active. */
    data object NotActivated : CurrentGrantMessageShadowCommitOutcome
    data object Disabled : CurrentGrantMessageShadowCommitOutcome
    data object Committed : CurrentGrantMessageShadowCommitOutcome
    data object DurableNoChange : CurrentGrantMessageShadowCommitOutcome
    data object IgnoredMissingUpdatedBase : CurrentGrantMessageShadowCommitOutcome
    data class FailedOpen(val reason: String) : CurrentGrantMessageShadowCommitOutcome
    data object LegacyDedupCollision : CurrentGrantMessageShadowCommitOutcome
}

/**
 * T3 seam-only central lane. The provider installs one instance across its realtime owners, but no guarded
 * path invokes [commit] yet. The RED slice will bind the frozen schedules to this dormant seam before any
 * implementation is allowed to activate it.
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

    suspend fun commit(
        partition: DurablePartitionKey,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
    ): CurrentGrantMessageShadowCommitOutcome {
        @Suppress("UNUSED_VARIABLE")
        val seamInputs = Triple(partition, legacyKey, foldInput)
        return CurrentGrantMessageShadowCommitOutcome.NotActivated
    }

    companion object {
        const val DEFAULT_LEDGER_LIMIT_PER_SCOPE: Int = 256
    }
}
