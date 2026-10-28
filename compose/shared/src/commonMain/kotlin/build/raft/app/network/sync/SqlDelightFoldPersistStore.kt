package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.RaftSqlDatabase
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
 * [RaftSqlDatabase.transaction] (real BEGIN/COMMIT), giving the §9.9 transaction-count invariant on a real
 * backend. All of this store's mutations run inside that single transaction.
 */
internal class SqlDelightFoldPersistStore(
    private val database: RaftSqlDatabase,
    private val now: () -> Long,
    private val onDecodeFailure: (scope: SyncScopeKey, factId: String) -> Unit = { _, _ -> },
) : FoldPersistStore {

    override suspend fun upsertFacts(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) {
        // Direct callers receive the same fail-stop as FoldPersistFrame, before any execute/executeQuery.
        requireFactsMatchScope(scope, facts)
        val q = database.queries
        for (fact in facts) {
            val json = encodeCanonicalFact(fact)
            // INSERT-OR-IGNORE + scoped UPDATE = an in-place upsert without replacing a foreign-scope row.
            q.insertCanonicalFactIfAbsent(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                id = fact.id,
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
                id = fact.id,
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
            val present = q.countCanonicalFactInScope(
                id = fact.id,
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            ).executeAsOne()
            if (present == 0L) throw FoldPersistScopeConflict(scope, fact.id)
        }
    }

    override suspend fun writeCursor(scope: SyncScopeKey, cursor: PersistedCursor) {
        database.queries.upsertSyncCursor(
            scopeId = scope.scopeId,
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            latestSeq = cursor.latestSeq,
            oldestLoadedSeq = cursor.oldestLoadedSeq,
            newestLoadedSeq = cursor.newestLoadedSeq,
            hasLoadedOldest = if (cursor.hasLoadedOldest) 1L else 0L,
            hasLoadedNewest = if (cursor.hasLoadedNewest) 1L else 0L,
            updatedAtMs = now(),
            epoch = cursor.epoch,
            parentScopeKind = cursor.parentScopeKind,
            parentScopeId = cursor.parentScopeId,
            rootMessageId = cursor.rootMessageId,
            lastUserAccessAtMs = null,
        )
    }

    /** No-op: the partitioned timeline index is maintained by SQLite. */
    override suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) = Unit

    override suspend fun prune(scope: SyncScopeKey, factIds: List<MessageId>) {
        val q = database.queries
        for (id in factIds) {
            q.deleteCanonicalFactInScope(
                id = id.value,
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
        }
    }

    override suspend fun clearScope(scope: SyncScopeKey) {
        database.queries.clearCanonicalFactsForScope(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        )
    }

    override suspend fun readFacts(scope: SyncScopeKey): List<CanonicalMessageRow> =
        database.queries
            .selectServerBackedMessagesForChannel(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
            .executeAsList()
            .mapNotNull { row ->
                val decoded = decodeCanonicalFact(row.canonicalFactJson)
                    ?: return@mapNotNull run { onDecodeFailure(scope, row.id); null }
                val consistent =
                    row.serverId == scope.serverId &&
                        row.environmentId == scope.environmentId &&
                        row.principalId == scope.principalId &&
                        row.consumerId == scope.consumerId &&
                        row.scopeKind == scope.scopeKind &&
                        row.id == decoded.id &&
                        row.channelId == decoded.channelId &&
                        row.seq == decoded.seq &&
                        row.createdAt == decoded.createdAt &&
                        decoded.channelId == scope.scopeId
                if (!consistent) return@mapNotNull run { onDecodeFailure(scope, row.id); null }
                decoded
            }

    override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? =
        database.queries
            .selectSyncCursor(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                scopeId = scope.scopeId,
            )
            .executeAsOneOrNull()
            ?.let { cursor ->
                PersistedCursor(
                    latestSeq = cursor.latestSeq,
                    oldestLoadedSeq = cursor.oldestLoadedSeq,
                    newestLoadedSeq = cursor.newestLoadedSeq,
                    hasLoadedOldest = cursor.hasLoadedOldest != 0L,
                    hasLoadedNewest = cursor.hasLoadedNewest != 0L,
                    epoch = cursor.epoch,
                    parentScopeKind = cursor.parentScopeKind,
                    parentScopeId = cursor.parentScopeId,
                    rootMessageId = cursor.rootMessageId,
                )
            }
}

/** A same-partition fact id already exists in a different scope — a hard contract violation. */
internal class FoldPersistScopeConflict(scope: SyncScopeKey, factId: String) :
    RuntimeException(
        "canonical fact $factId did not land in scope " +
            "${scope.serverId}/${scope.principalId}/${scope.consumerId}/${scope.scopeKind}/${scope.scopeId} " +
            "(id exists in another scope of the same durable partition)",
    )

/** Binds the fold transaction runner to the real SQLite BEGIN/COMMIT boundary. */
internal class SqlDelightFoldPersistTransactionRunner(
    private val database: RaftSqlDatabase,
) : FoldPersistTransactionRunner {
    override suspend fun run(block: suspend () -> Unit) {
        database.transaction { block() }
    }
}
