package build.raft.app.network.sync

import build.raft.app.data.RaftSqlDatabase

enum class ReadStateV1CommitMode {
    Snapshot,
    Incremental,
}

sealed interface ReadStateV1StoreResult {
    data object Accepted : ReadStateV1StoreResult
    data object Current : ReadStateV1StoreResult
    data object Stale : ReadStateV1StoreResult
    data object Conflict : ReadStateV1StoreResult
    data object RejectedCurrentGrant : ReadStateV1StoreResult
}

fun interface ReadStateV1CommitGuard {
    suspend fun isCurrent(): Boolean
}

interface ReadStateV1Store {
    suspend fun commit(
        scope: ReadStateV1Scope,
        fact: ReadStateV1Fact,
        mode: ReadStateV1CommitMode,
        guard: ReadStateV1CommitGuard,
    ): ReadStateV1StoreResult

    suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact?
}

class ReadStateV1StoredFactCorrupt(val scope: ReadStateV1Scope) :
    RuntimeException("read-state v1 stored fact is corrupt at $scope")

private object ReadStateV1GrantRevoked : RuntimeException()

internal class SqlDelightReadStateV1Store(
    private val database: RaftSqlDatabase,
    private val now: () -> Long,
    private val faultHook: PersistFaultHook = PersistFaultHook.Never,
) : ReadStateV1Store {
    override suspend fun commit(
        scope: ReadStateV1Scope,
        fact: ReadStateV1Fact,
        mode: ReadStateV1CommitMode,
        guard: ReadStateV1CommitGuard,
    ): ReadStateV1StoreResult {
        require(fact.channelId == scope.channelId) { "read-state fact must match exact scope" }
        var result: ReadStateV1StoreResult = ReadStateV1StoreResult.RejectedCurrentGrant
        try {
            database.transaction {
                if (!guard.isCurrent()) throw ReadStateV1GrantRevoked
                val stored = queries.selectSyncReadStateFact(
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    channelId = scope.channelId,
                ).executeAsOneOrNull()
                val transition =
                    if (stored == null) {
                        ReadStateV1Transition(
                            fact = fact,
                            result = ReadStateV1ApplyResult.Accepted,
                        )
                    } else {
                        val storedIsValid = isStoredValid(stored.readStateVersion, stored.maxReadSeq)
                        if (!storedIsValid && mode == ReadStateV1CommitMode.Snapshot) {
                            // Verified hydrate is the only input allowed to heal one exact corrupt durable row.
                            ReadStateV1Transition(fact, ReadStateV1ApplyResult.Accepted)
                        } else {
                            if (!storedIsValid) throw ReadStateV1StoredFactCorrupt(scope)
                            val reduced = reduceReadStateV1(
                                current = ReadStateV1Fact(scope.channelId, stored.readStateVersion, stored.maxReadSeq),
                                incoming = fact,
                            )
                            if (mode == ReadStateV1CommitMode.Snapshot && reduced.result == ReadStateV1ApplyResult.Conflict) {
                                // A verified hydrate is the authoritative equal-version conflict adjudicator.
                                // Lower snapshots remain stale; only the disputed equal pair may be replaced.
                                ReadStateV1Transition(fact, ReadStateV1ApplyResult.Accepted)
                            } else {
                                reduced
                            }
                        }
                    }
                result = transition.result.toStoreResult()
                if (transition.result == ReadStateV1ApplyResult.Accepted) {
                    queries.upsertSyncReadStateFact(
                        serverId = scope.serverId,
                        principalId = scope.principalId,
                        consumerId = scope.consumerId,
                        channelId = scope.channelId,
                        readStateVersion = transition.fact.readStateVersion,
                        maxReadSeq = transition.fact.maxReadSeq,
                        updatedAtMs = now(),
                    )
                    if (faultHook.shouldKillAt(PersistFaultPoint.BeforeCommit)) {
                        throw PersistKilled(PersistFaultPoint.BeforeCommit)
                    }
                    if (!guard.isCurrent()) throw ReadStateV1GrantRevoked
                }
            }
        } catch (_: ReadStateV1GrantRevoked) {
            return ReadStateV1StoreResult.RejectedCurrentGrant
        }
        if (result == ReadStateV1StoreResult.Accepted && faultHook.shouldKillAt(PersistFaultPoint.AfterCommit)) {
            throw PersistKilled(PersistFaultPoint.AfterCommit)
        }
        return result
    }

    override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? =
        database.queries.selectSyncReadStateFact(
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            channelId = scope.channelId,
        ).executeAsOneOrNull()?.let { row ->
            validateStored(scope, row.readStateVersion, row.maxReadSeq)
            ReadStateV1Fact(row.channelId, row.readStateVersion, row.maxReadSeq)
        }

    private fun validateStored(scope: ReadStateV1Scope, version: Long, maxReadSeq: Long) {
        if (!isStoredValid(version, maxReadSeq)) throw ReadStateV1StoredFactCorrupt(scope)
    }

    private fun isStoredValid(version: Long, maxReadSeq: Long): Boolean = version > 0L && maxReadSeq >= 0L
}

private fun ReadStateV1ApplyResult.toStoreResult(): ReadStateV1StoreResult =
    when (this) {
        ReadStateV1ApplyResult.Accepted -> ReadStateV1StoreResult.Accepted
        ReadStateV1ApplyResult.Current -> ReadStateV1StoreResult.Current
        ReadStateV1ApplyResult.Stale -> ReadStateV1StoreResult.Stale
        ReadStateV1ApplyResult.Conflict -> ReadStateV1StoreResult.Conflict
    }
