package build.raft.app.network.sync

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.RaftSqlDatabase
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SqlDelightReadStateV1StoreTest {
    private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var begin = 0
        var commit = 0
        var rollback = 0

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            begin += 1
            return delegate.newTransaction().also { result ->
                result.value.afterCommit { commit += 1 }
                result.value.afterRollback { rollback += 1 }
            }
        }
    }

    private val scope = ReadStateV1Scope("server-a", "principal-a", "channel-a")
    private val current = ReadStateV1CommitGuard { true }

    @Test
    fun acceptedFactUsesOneRealTransactionAndVersionAloneAllowsRewind() = runBlocking {
        val (database, driver) = freshDatabase()
        val store = SqlDelightReadStateV1Store(database, now = { 100L })

        assertEquals(
            ReadStateV1StoreResult.Accepted,
            store.commit(scope, fact(4, 40), ReadStateV1CommitMode.Snapshot, current),
        )
        assertEquals(
            ReadStateV1StoreResult.Accepted,
            store.commit(scope, fact(5, 20), ReadStateV1CommitMode.Incremental, current),
        )
        assertEquals(fact(5, 20), store.restore(scope))
        assertEquals(2, driver.begin)
        assertEquals(2, driver.commit)
        assertEquals(0, driver.rollback)
    }

    @Test
    fun lowerAndCurrentDoNotWriteWhileEqualDifferentConflicts() = runBlocking {
        val (database, _) = freshDatabase()
        val store = SqlDelightReadStateV1Store(database, now = { 100L })
        store.commit(scope, fact(7, 70), ReadStateV1CommitMode.Snapshot, current)

        assertEquals(ReadStateV1StoreResult.Stale, store.commit(scope, fact(6, 99), ReadStateV1CommitMode.Incremental, current))
        assertEquals(ReadStateV1StoreResult.Current, store.commit(scope, fact(7, 70), ReadStateV1CommitMode.Incremental, current))
        assertEquals(ReadStateV1StoreResult.Conflict, store.commit(scope, fact(7, 71), ReadStateV1CommitMode.Incremental, current))
        assertEquals(fact(7, 70), store.restore(scope))
    }

    @Test
    fun exactServerPrincipalAndChannelPartitionsAreIsolated() = runBlocking {
        val (database, _) = freshDatabase()
        val store = SqlDelightReadStateV1Store(database, now = { 100L })
        store.commit(scope, fact(2, 20), ReadStateV1CommitMode.Snapshot, current)

        assertNull(store.restore(scope.copy(serverId = "server-b")))
        assertNull(store.restore(scope.copy(principalId = "principal-b")))
        assertNull(store.restore(scope.copy(channelId = "channel-b")))
        assertEquals(fact(2, 20), store.restore(scope))
    }

    @Test
    fun fileBackedRestartRestoresExactCompletePair() = runBlocking {
        val path = Files.createTempFile("read-state-v1", ".db")
        path.deleteIfExists()
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                build.raft.app.shared.data.local.RaftDatabase.Schema.create(driver).value
                SqlDelightReadStateV1Store(RaftSqlDatabase(driver), now = { 100L })
                    .commit(scope, fact(9, 90), ReadStateV1CommitMode.Snapshot, current)
            }
            JdbcSqliteDriver(url).use { driver ->
                assertEquals(fact(9, 90), SqlDelightReadStateV1Store(RaftSqlDatabase(driver), now = { 200L }).restore(scope))
            }
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun beforeCommitCrashAndRevokedGrantBothRollback() = runBlocking {
        val (database, driver) = freshDatabase()
        val killed = SqlDelightReadStateV1Store(
            database,
            now = { 100L },
            faultHook = PersistFaultHook { it == PersistFaultPoint.BeforeCommit },
        )
        assertFailsWith<PersistKilled> {
            killed.commit(scope, fact(3, 30), ReadStateV1CommitMode.Snapshot, current)
        }
        assertNull(SqlDelightReadStateV1Store(database, now = { 100L }).restore(scope))

        var checks = 0
        val revokedBeforeCommit = ReadStateV1CommitGuard { ++checks == 1 }
        assertEquals(
            ReadStateV1StoreResult.RejectedCurrentGrant,
            SqlDelightReadStateV1Store(database, now = { 100L })
                .commit(scope, fact(4, 40), ReadStateV1CommitMode.Snapshot, revokedBeforeCommit),
        )
        assertNull(SqlDelightReadStateV1Store(database, now = { 100L }).restore(scope))
        assertEquals(2, driver.rollback)
    }

    @Test
    fun snapshotKeepsVersionOrderingResolvesEqualConflictAndCorruptStoredRowFailsClosed() = runBlocking {
        val (database, _) = freshDatabase()
        val store = SqlDelightReadStateV1Store(database, now = { 100L })
        store.commit(scope, fact(10, 100), ReadStateV1CommitMode.Snapshot, current)
        assertEquals(
            ReadStateV1StoreResult.Stale,
            store.commit(scope, fact(2, 5), ReadStateV1CommitMode.Snapshot, current),
        )
        assertEquals(fact(10, 100), store.restore(scope))
        assertEquals(
            ReadStateV1StoreResult.Accepted,
            store.commit(scope, fact(10, 99), ReadStateV1CommitMode.Snapshot, current),
        )
        assertEquals(fact(10, 99), store.restore(scope))

        database.queries.upsertSyncReadStateFact(
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            channelId = scope.channelId,
            readStateVersion = 0,
            maxReadSeq = -1,
            updatedAtMs = 200,
        )
        assertFailsWith<ReadStateV1StoredFactCorrupt> { store.restore(scope) }
        Unit
    }

    @Test
    fun corruptRestoreRequestsVerifiedSnapshotWhichHealsAndRestores() = runBlocking {
        val (database, _) = freshDatabase()
        database.queries.upsertSyncReadStateFact(
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            channelId = scope.channelId,
            readStateVersion = 0,
            maxReadSeq = -1,
            updatedAtMs = 100,
        )
        val authority = ReadStateV1GrantAuthority()
        val witness = authority.issue(scope)
        val requests = mutableListOf<ReadStateV1GrantWitness>()
        val projections = mutableListOf<ReadStateV1Fact>()
        val engine = ReadStateV1GenerationEngine(
            authority = authority,
            store = SqlDelightReadStateV1Store(database, now = { 200L }),
            projectionObserver = ReadStateV1ProjectionObserver { _, fact -> projections += fact },
            rebaselineRequester = ReadStateV1RebaselineRequester { requests += it },
        )

        assertEquals(ReadStateV1GenerationResult.BaselineRequired(true), engine.restore(witness))
        assertEquals(listOf(witness), requests)
        assertEquals(
            ReadStateV1GenerationResult.Committed,
            engine.applySnapshot(
                witness,
                ReadStateV1WireCandidate(
                    channelId = scope.channelId,
                    readStateVersion = 5,
                    maxReadSeq = 50,
                    source = ReadStateV1FactSource.Hydrate,
                ),
            ),
        )
        assertEquals(fact(5, 50), SqlDelightReadStateV1Store(database, now = { 300L }).restore(scope))
        assertEquals(listOf(fact(5, 50)), projections)
    }

    private fun fact(version: Long, seq: Long) = ReadStateV1Fact(scope.channelId, version, seq)

    private fun freshDatabase(): Pair<RaftSqlDatabase, CountingDriver> {
        val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        build.raft.app.shared.data.local.RaftDatabase.Schema.create(driver).value
        return RaftSqlDatabase(driver) to driver
    }
}
