package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.RelationalValue
import build.raft.app.data.SlockSqlDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightStrictMessageScopeSnapshotReaderRedTest {

    @Test
    fun factsAndCursorComeFromOneWalReadSnapshotAcrossConcurrentCommit() {
        val file = Files.createTempFile("t3-strict-reader", ".sqlite")
        val driverA = JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}")
        val driverB = JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}")
        try {
            SlockSqlDatabase.Schema.create(driverA).value
            val databaseA = SlockSqlDatabase(driverA)
            val databaseB = SlockSqlDatabase(driverB)
            runBlocking {
                assertEquals("wal", enableWal(databaseA))
                assertEquals("wal", enableWal(databaseB))
                databaseA.execute("PRAGMA busy_timeout=5000;")
                databaseB.execute("PRAGMA busy_timeout=5000;")
            }

            val scope = scope()
            val oldRow = row(id = "old", seq = 1, content = "whole-old")
            val newRow = row(id = "new", seq = 2, content = "whole-new")
            runBlocking { adapter(databaseA).applyFrame(frame(scope, oldRow, seq = 1)) }

            val factsRead = CountDownLatch(1)
            val releaseReader = CountDownLatch(1)
            val writerCommitted = CountDownLatch(1)
            val firstResult = AtomicReference<StrictMessageRestoreResult?>()
            val readerFailure = AtomicReference<Throwable?>()
            val writerFailure = AtomicReference<Throwable?>()
            val reader = SqlDelightStrictMessageScopeSnapshotReader(
                database = databaseA,
                hooks = StrictMessageSqlDelightReaderHooks(
                    afterFactsRead = {
                        factsRead.countDown()
                        check(releaseReader.await(5, TimeUnit.SECONDS)) {
                            "reader was not released after writer commit"
                        }
                    },
                ),
            )

            val readerThread = Thread({
                try {
                    firstResult.set(runBlocking { reader.read(scope) })
                } catch (throwable: Throwable) {
                    readerFailure.set(throwable)
                }
            }, "t3-strict-reader-a")
            val writerThread = Thread({
                try {
                    check(factsRead.await(5, TimeUnit.SECONDS)) { "reader never reached afterFactsRead" }
                    runBlocking { adapter(databaseB).applyFrame(frame(scope, newRow, seq = 2)) }
                    writerCommitted.countDown()
                } catch (throwable: Throwable) {
                    writerFailure.set(throwable)
                }
            }, "t3-strict-writer-b")

            readerThread.start()
            writerThread.start()
            val hookReached = factsRead.await(2, TimeUnit.SECONDS)
            try {
                assertTrue(hookReached, "strict reader must execute the synchronous hook after reading facts")
                assertTrue(
                    writerCommitted.await(5, TimeUnit.SECONDS),
                    "independent connection B must commit whole-new while A's read transaction stays open",
                )
            } finally {
                if (!hookReached) factsRead.countDown()
                releaseReader.countDown()
                readerThread.join(5_000)
                writerThread.join(5_000)
            }

            assertTrue(!readerThread.isAlive, "reader OS thread must terminate")
            assertTrue(!writerThread.isAlive, "writer OS thread must terminate")
            assertNull(readerFailure.get())
            assertNull(writerFailure.get())

            val old = assertIs<StrictMessageRestoreResult.Valid>(firstResult.get())
            assertEquals(listOf("old"), old.snapshot.facts.map { it.id })
            assertEquals("whole-old", old.snapshot.facts.single().content)
            assertEquals(1L, old.snapshot.cursor?.latestSeq)

            val fresh = assertIs<StrictMessageRestoreResult.Valid>(runBlocking { reader.read(scope) })
            assertEquals(listOf("new"), fresh.snapshot.facts.map { it.id })
            assertEquals("whole-new", fresh.snapshot.facts.single().content)
            assertEquals(2L, fresh.snapshot.cursor?.latestSeq)
        } finally {
            driverB.close()
            driverA.close()
            deleteSqliteFiles(file)
        }
    }

    private suspend fun enableWal(database: SlockSqlDatabase): String {
        val value = database.query("PRAGMA journal_mode=WAL;").rows.single().single()
        return (value as RelationalValue.Text).value.lowercase()
    }

    private fun adapter(database: SlockSqlDatabase): DefaultFoldPersistenceAdapter =
        DefaultFoldPersistenceAdapter(
            runner = SqlDelightFoldPersistTransactionRunner(database),
            store = SqlDelightFoldPersistStore(database, now = { 1L }),
        )

    private fun scope() = SyncScopeKey(
        partition = DurablePartitionKey(
            serverId = "server-1",
            principalId = "principal-1",
            consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
            environmentId = "env-test",
        ),
        scopeId = "channel-1",
        scopeKind = "channel",
    )

    private fun row(id: String, seq: Long, content: String) = CanonicalMessageRow(
        id = id,
        channelId = "channel-1",
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = content,
        messageType = "chat",
        createdAt = "2026-07-15T00:00:0${seq}Z",
        senderName = "Alice",
    )

    private fun frame(scope: SyncScopeKey, row: CanonicalMessageRow, seq: Long) = FoldPersistFrame(
        scope = scope,
        facts = listOf(row),
        cursor = PersistedCursor(
            latestSeq = seq,
            oldestLoadedSeq = seq,
            newestLoadedSeq = seq,
            hasLoadedOldest = true,
            hasLoadedNewest = true,
            epoch = "epoch-1",
        ),
        rebaseline = true,
    )

    private fun deleteSqliteFiles(file: Path) {
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
        Files.deleteIfExists(file)
    }
}
