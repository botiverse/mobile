package build.raft.app.network.sync

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
class SqlDelightFoldPersistStore(
    private val database: SlockSqlDatabase,
    private val now: () -> Long,
    private val onDecodeFailure: (scope: SyncScopeKey, factId: String) -> Unit = { _, _ -> },
) : FoldPersistStore {

    override suspend fun upsertFacts(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) {
        val q = database.queries
        for (fact in facts) {
            val json = encodeCanonicalFact(fact)
            // INSERT-OR-IGNORE + scoped UPDATE = a CASCADE-safe in-place upsert (the dialect has no UPSERT).
            q.insertCanonicalFactIfAbsent(
                id = fact.id.value,
                serverId = scope.serverId,
                channelId = scope.scopeId,
                scopeKind = scope.scopeKind,
                seq = fact.seq,
                createdAt = fact.createdAt,
                canonicalFactJson = json,
            )
            q.updateCanonicalFact(
                seq = fact.seq,
                createdAt = fact.createdAt,
                canonicalFactJson = json,
                id = fact.id.value,
                serverId = scope.serverId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
            // Fail-stop: if this id already exists in ANOTHER scope, the IGNORE kept the foreign row and the
            // scoped UPDATE touched 0 rows — so the fact did NOT land in THIS scope. That must poison the
            // apply (throw, abort the transaction), never silently succeed. Server ids are globally unique
            // by contract, so this is a hard violation, not an expected merge.
            val present = q.countCanonicalFactInScope(
                id = fact.id.value,
                serverId = scope.serverId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            ).executeAsOne()
            if (present == 0L) {
                throw FoldPersistScopeConflict(scope, fact.id.value)
            }
        }
    }

    override suspend fun writeCursor(scope: SyncScopeKey, cursor: PersistedCursor) {
        database.queries.upsertSyncCursor(
            scopeId = scope.scopeId,
            serverId = scope.serverId,
            scopeKind = scope.scopeKind,
            latestSeq = cursor.latestSeq,
            oldestLoadedSeq = cursor.oldestLoadedSeq,
            newestLoadedSeq = cursor.newestLoadedSeq,
            hasLoadedOldest = if (cursor.hasLoadedOldest) 1L else 0L,
            hasLoadedNewest = if (cursor.hasLoadedNewest) 1L else 0L,
            updatedAtMs = now(),
            epoch = cursor.epoch,
        )
    }

    /** No-op: the `(serverId, channelId, seq, id)` timeline index is maintained by SQLite from the rows. */
    override suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) = Unit

    override suspend fun prune(scope: SyncScopeKey, factIds: List<MessageId>) {
        val q = database.queries
        for (id in factIds) {
            q.deleteCanonicalFactInScope(
                id = id.value,
                serverId = scope.serverId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
        }
    }

    override suspend fun clearScope(scope: SyncScopeKey) {
        database.queries.clearCanonicalFactsForScope(
            serverId = scope.serverId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        )
    }

    override suspend fun readFacts(scope: SyncScopeKey): List<CanonicalMessageRow> =
        database.queries
            .selectServerBackedMessagesForChannel(scope.serverId, scope.scopeKind, scope.scopeId)
            .executeAsList()
            .mapNotNull { row ->
                // Per-row fail-closed: a payload that will not decode is a poison-gate input, counted and
                // dropped — never silently reshaped into a partial row.
                val decoded = decodeCanonicalFact(row.canonicalFactJson)
                    ?: return@mapNotNull run { onDecodeFailure(scope, row.id); null }
                // Scalar/payload consistency: the scalar columns are a deterministic PROJECTION of the
                // payload (the restore source). If they disagree, the row is corrupt — treat it as a
                // poison-gate input, not a trusted partial. Restore still yields only the decoded payload.
                if (row.seq != decoded.seq || row.createdAt != decoded.createdAt) {
                    return@mapNotNull run { onDecodeFailure(scope, row.id); null }
                }
                decoded
            }

    override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? =
        database.queries.selectSyncCursor(scope.serverId, scope.scopeId).executeAsOneOrNull()?.let { c ->
            PersistedCursor(
                latestSeq = c.latestSeq,
                oldestLoadedSeq = c.oldestLoadedSeq,
                newestLoadedSeq = c.newestLoadedSeq,
                hasLoadedOldest = c.hasLoadedOldest != 0L,
                hasLoadedNewest = c.hasLoadedNewest != 0L,
                epoch = c.epoch,
            )
        }
}

/** A fact whose globally-unique id already exists in a different scope — a hard contract violation. */
class FoldPersistScopeConflict(scope: SyncScopeKey, factId: String) :
    RuntimeException("canonical fact $factId did not land in scope ${scope.serverId}/${scope.scopeKind}/${scope.scopeId} (id exists in another scope)")

/** Binds the fold transaction runner to the real SQLite BEGIN/COMMIT boundary. */
class SqlDelightFoldPersistTransactionRunner(
    private val database: SlockSqlDatabase,
) : FoldPersistTransactionRunner {
    override suspend fun run(block: suspend () -> Unit) {
        database.transaction { block() }
    }
}
