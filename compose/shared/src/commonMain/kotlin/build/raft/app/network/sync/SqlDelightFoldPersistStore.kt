package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.SlockSqlDatabase
import build.raft.app.model.MessageId

/**
 * SQLite binding of the T-wrapper persistence (RFC 043 §9.9), canonical-native form.
 *
 * Each fact is persisted as its lossless `canonicalFactJson` envelope ([CanonicalFactCodec]) plus the
 * minimal scope/order scalars a SQL read needs — NO presentation state, NO child tables (attachments and
 * reactions live inside the fact JSON). Restore decodes the envelope; the scalar columns are a projector
 * output, never a second source of truth.
 *
 * [SqlDelightFoldPersistTransactionRunner] runs one applied frame inside exactly one
 * [SlockSqlDatabase.transaction] (real BEGIN/COMMIT), giving the §9.9 transaction-count invariant on a real
 * backend. All of this store's mutations run inside that single transaction.
 */
internal class SqlDelightFoldPersistStore(
    private val database: SlockSqlDatabase,
    private val now: () -> Long,
    private val onDecodeFailure: (scope: SyncScopeKey, factId: String) -> Unit = { _, _ -> },
) : FoldPersistStore {

    override suspend fun upsertFacts(scope: SyncScopeKey, facts: List<CanonicalMessageRow>): Unit =
        throw DurablePartitionStoreWiringPending()

    override suspend fun writeCursor(scope: SyncScopeKey, cursor: PersistedCursor): Unit =
        throw DurablePartitionStoreWiringPending()

    /** No-op: the partitioned timeline index is maintained by SQLite once the store wiring lands. */
    override suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) = Unit

    override suspend fun prune(scope: SyncScopeKey, factIds: List<MessageId>): Unit =
        throw DurablePartitionStoreWiringPending()

    override suspend fun clearScope(scope: SyncScopeKey): Unit =
        throw DurablePartitionStoreWiringPending()

    override suspend fun readFacts(scope: SyncScopeKey): List<CanonicalMessageRow> =
        throw DurablePartitionStoreWiringPending()

    override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? =
        throw DurablePartitionStoreWiringPending()
}

/** Deliberate seam-only RED: partitioned query binding lands only after Zhao validates the pure oracle. */
internal class DurablePartitionStoreWiringPending :
    IllegalStateException("durable partition store wiring pending")

/** A fact whose globally-unique id already exists in a different scope — a hard contract violation. */
internal class FoldPersistScopeConflict(scope: SyncScopeKey, factId: String) :
    RuntimeException("canonical fact $factId did not land in scope ${scope.serverId}/${scope.scopeKind}/${scope.scopeId} (id exists in another scope)")

/** Binds the fold transaction runner to the real SQLite BEGIN/COMMIT boundary. */
internal class SqlDelightFoldPersistTransactionRunner(
    private val database: SlockSqlDatabase,
) : FoldPersistTransactionRunner {
    override suspend fun run(block: suspend () -> Unit) {
        database.transaction { block() }
    }
}
