package build.raft.app.network.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.data.SlockSqlDatabase
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.model.ServerId
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure T2 durable-partition matrix over the production SQLDelight store binding. */
class DurablePartitionStoreTest {
    private class SqlCountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var statementCount: Int = 0

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            statementCount += 1
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            statementCount += 1
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private fun scope(
        serverId: String = "server-a",
        principalId: String = "principal-a",
        consumerId: String = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
        environmentId: String = "https://api.env-a.test",
        scopeId: String = "channel-a",
        scopeKind: String = "channel",
    ) = SyncScopeKey(
        partition = DurablePartitionKey(serverId, principalId, consumerId, environmentId),
        scopeId = scopeId,
        scopeKind = scopeKind,
    )

    private fun row(id: String, seq: Long, scope: SyncScopeKey) = CanonicalMessageRow(
        id = id,
        channelId = scope.scopeId,
        seq = seq,
        senderType = "user",
        senderId = "sender-a",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-15T00:00:${seq.toString().padStart(2, '0')}Z",
        senderName = "Alice",
    )

    private fun frame(scope: SyncScopeKey, id: String = "message-a", seq: Long = 1L) = FoldPersistFrame(
        scope = scope,
        facts = listOf(row(id, seq, scope)),
        cursor = PersistedCursor(
            latestSeq = seq,
            oldestLoadedSeq = seq,
            newestLoadedSeq = seq,
            epoch = "epoch-a",
        ),
    )

    private fun freshDatabase(driver: JdbcSqliteDriver): SlockSqlDatabase {
        build.raft.app.shared.data.local.SlockDatabase.Schema.create(driver).value
        return SlockSqlDatabase(driver)
    }

    private fun adapter(database: SlockSqlDatabase) = DefaultFoldPersistenceAdapter(
        runner = SqlDelightFoldPersistTransactionRunner(database),
        store = SqlDelightFoldPersistStore(database, now = { 100L }),
    )

    private fun store(database: SlockSqlDatabase) = SqlDelightFoldPersistStore(database, now = { 100L })

    @Test
    fun crossServerPartitionsCannotReadOrOverwriteEachOther() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = freshDatabase(driver)
        val first = scope(serverId = "server-a")
        val second = scope(serverId = "server-b")

        adapter(database).applyFrame(frame(first))
        assertEquals(listOf("message-a"), store(database).readFacts(first).map { it.id })
        assertTrue(store(database).readFacts(second).isEmpty())
    }

    @Test
    fun crossPrincipalPartitionsCannotReadOrOverwriteEachOther() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = freshDatabase(driver)
        val first = scope(principalId = "principal-a")
        val second = scope(principalId = "principal-b")

        adapter(database).applyFrame(frame(first))
        assertEquals(listOf("message-a"), store(database).readFacts(first).map { it.id })
        assertTrue(store(database).readFacts(second).isEmpty())
    }

    /**
     * Task #1109 (offline cache ②): the same principal + server + consumer reached through two API
     * environments (staging vs production) are two partitions. Before the fix every write-path partition
     * defaulted `environmentId` to one constant, so this read would have returned the other environment's row.
     */
    @Test
    fun crossEnvironmentPartitionsCannotReadOrOverwriteEachOther() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = freshDatabase(driver)
        val staging = scope(environmentId = "https://api.staging.test")
        val production = scope(environmentId = "https://api.production.test")

        adapter(database).applyFrame(frame(staging, id = "staging-message", seq = 3L))
        adapter(database).applyFrame(frame(production, id = "production-message", seq = 1L))

        assertEquals(listOf("staging-message"), store(database).readFacts(staging).map { it.id })
        assertEquals(listOf("production-message"), store(database).readFacts(production).map { it.id })
        assertEquals(3L, store(database).readCursor(staging)?.latestSeq)
        assertEquals(1L, store(database).readCursor(production)?.latestSeq)
    }

    @Test
    fun crossConsumerPartitionsCannotReadOrOverwriteEachOther() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = freshDatabase(driver)
        val first = scope(consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID)
        val second = scope(consumerId = "sync-core.messages.audit.v1")

        adapter(database).applyFrame(frame(first))
        assertEquals(listOf("message-a"), store(database).readFacts(first).map { it.id })
        assertTrue(store(database).readFacts(second).isEmpty())
    }

    @Test
    fun restartRestoresFactsAndCursorOnlyFromExactPartition() = runBlocking {
        val path = Files.createTempFile("slock-durable-partition", ".db")
        path.deleteIfExists()
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val exact = scope()
        try {
            JdbcSqliteDriver(url).use { firstDriver ->
                val firstDatabase = freshDatabase(firstDriver)
                adapter(firstDatabase).applyFrame(frame(exact, id = "restart-message", seq = 7L))
            }

            JdbcSqliteDriver(url).use { secondDriver ->
                val secondDatabase = SlockSqlDatabase(secondDriver)
                assertEquals(listOf("restart-message"), store(secondDatabase).readFacts(exact).map { it.id })
                assertEquals(7L, store(secondDatabase).readCursor(exact)?.latestSeq)
                assertTrue(store(secondDatabase).readFacts(scope(principalId = "principal-b")).isEmpty())
            }
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun samePrincipalReauthReusesTheSameDurablePartition() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = freshDatabase(driver)
        val beforeReauth = scope()
        val afterReauth = DurablePartitionKey(
            serverId = beforeReauth.serverId,
            principalId = beforeReauth.principalId,
            consumerId = beforeReauth.consumerId,
            environmentId = beforeReauth.environmentId,
        ).let { partition -> SyncScopeKey(partition, beforeReauth.scopeId, beforeReauth.scopeKind) }

        adapter(database).applyFrame(frame(beforeReauth, id = "reauth-message", seq = 9L))
        assertEquals(listOf("reauth-message"), store(database).readFacts(afterReauth).map { it.id })
        assertEquals(9L, store(database).readCursor(afterReauth)?.latestSeq)
    }

    @Test
    fun serverOnlyLegacyMmkvCursorNeverSeedsAFreshDurablePartition() = runBlocking {
        val keyValueStore = InMemorySlockKeyValueStore()
        keyValueStore.putString("realtime.cursor.appliedLastSeq.server-a", "42")
        val legacy = RealtimeAppliedSeqCursorStore(keyValueStore)
        assertEquals(42L, legacy.readAppliedLastSeq(ServerId("server-a")))

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = freshDatabase(driver)
        assertNull(store(database).readCursor(scope()), "legacy server-only state must not seed S/P/C")
    }

    @Test
    fun samePartitionSameMessageIdAcrossScopesFailsStop(): Unit = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = freshDatabase(driver)
        val channelA = scope(scopeId = "channel-a")
        val channelB = scope(scopeId = "channel-b")

        adapter(database).applyFrame(frame(channelA, id = "collision", seq = 1L))
        assertFailsWith<FoldPersistScopeConflict> {
            adapter(database).applyFrame(frame(channelB, id = "collision", seq = 2L))
        }
    }

    @Test
    fun mismatchedFactScopeFailsBeforeTransactionAndCursorWrite() = runBlocking {
        val declaredScope = scope(scopeId = "channel-a")
        val mismatchedFact = row("mismatched", 1L, scope(scopeId = "channel-b"))
        var transactions = 0
        var cursorWrites = 0
        val recordingStore = object : FoldPersistStore {
            override suspend fun upsertFacts(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) = Unit
            override suspend fun writeCursor(scope: SyncScopeKey, cursor: PersistedCursor) { cursorWrites += 1 }
            override suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) = Unit
            override suspend fun prune(scope: SyncScopeKey, factIds: List<build.raft.app.model.MessageId>) = Unit
            override suspend fun clearScope(scope: SyncScopeKey) = Unit
            override suspend fun readFacts(scope: SyncScopeKey): List<CanonicalMessageRow> = emptyList()
            override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? = null
        }
        val adapter = DefaultFoldPersistenceAdapter(
            runner = FoldPersistTransactionRunner { block -> transactions += 1; block() },
            store = recordingStore,
        )

        val failure = runCatching {
            adapter.applyFrame(
                FoldPersistFrame(
                    scope = declaredScope,
                    facts = listOf(mismatchedFact),
                    cursor = PersistedCursor(1L, 1L, 1L, epoch = "epoch-a"),
                ),
            )
        }.exceptionOrNull()

        assertEquals("FoldPersistScopeMismatch", failure?.let { it::class.simpleName })
        assertTrue(failure?.message.orEmpty().contains("channelId"))
        assertEquals(0, transactions, "invalid frame must fail before BEGIN")
        assertEquals(0, cursorWrites, "invalid frame must never advance cursor")
    }

    @Test
    fun directStoreMismatchFailsBeforeAnySql() = runBlocking {
        val counting = SqlCountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        build.raft.app.shared.data.local.SlockDatabase.Schema.create(counting).value
        val database = SlockSqlDatabase(counting)
        // Database construction now forces lazy SQLDelight initialization. This assertion is
        // scoped to the rejected store mutation, so reset the witness after bootstrap.
        counting.statementCount = 0
        val declaredScope = scope(scopeId = "channel-a")
        val mismatchedFact = row("mismatched", 1L, scope(scopeId = "channel-b"))

        val failure = runCatching {
            store(database).upsertFacts(declaredScope, listOf(mismatchedFact))
        }.exceptionOrNull()

        assertEquals("FoldPersistScopeMismatch", failure?.let { it::class.simpleName })
        assertTrue(failure?.message.orEmpty().contains("channelId"))
        assertEquals(0, counting.statementCount, "direct mismatch must fail before every SQL operation")
    }
}
