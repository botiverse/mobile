/*
 * Owner-generation ↔ COMMIT linearization — real-SQLite gates (#875 root 4 correction).
 *
 * The #875 hole: the owner check passed at transaction start, the owner lock was released, and the writes +
 * COMMIT ran afterwards — so "A check=true → B rotate → A commit" persisted a stale owner's writes. Its test
 * paused BEFORE the check (a false green). These tests target the required window: AFTER the check has
 * passed, BEFORE the writes/COMMIT.
 *
 * With the gate held across the whole transaction that window is not merely detected — it is
 * unrepresentable. The proofs, all on a real JdbcSqliteDriver:
 *   1. a rotation that lands first ⇒ the stale apply is rejected with ZERO statements (no BEGIN, facts/
 *      cursor/index untouched);
 *   2. a rotation requested inside the check→COMMIT window blocks and serializes strictly AFTER the COMMIT
 *      (order witness on real BEGIN/COMMIT events) — never between check and commit;
 *   3. a commit failure is one real ROLLBACK, leaves the generation unchanged, and releases the gate;
 *   4. a burned (logout) generation rejects a late applier leaving the previously committed state
 *      byte-identical (zero statements).
 */
package build.raft.app.network.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.SlockSqlDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerCommitLinearizationTest {

    /** Delegates to a real driver; records every transaction boundary into an order-witness log. */
    private class WitnessingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val events = CopyOnWriteArrayList<String>()
        var begin = 0; private set
        var commit = 0; private set
        var rollback = 0; private set
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            when (sql.trim().uppercase().substringBefore(' ')) {
                "BEGIN" -> { begin++; events.add("begin") }
                "COMMIT" -> { commit++; events.add("commit") }
                "ROLLBACK" -> { rollback++; events.add("rollback") }
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private fun freshDb(): Pair<SlockSqlDatabase, WitnessingDriver> {
        val driver = WitnessingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        build.raft.app.shared.data.local.SlockDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        return SlockSqlDatabase(driver) to driver
    }

    private val scope = SyncScopeKey(serverId = "s1", scopeId = "c1", scopeKind = "channel")

    private fun store(db: SlockSqlDatabase) = SqlDelightFoldPersistStore(db, now = { 100L })

    private fun bound(db: SlockSqlDatabase, gate: OwnerCommitGate, store: FoldPersistStore) =
        OwnerBoundFoldPersistenceAdapter(
            gate = gate,
            inner = DefaultFoldPersistenceAdapter(SqlDelightFoldPersistTransactionRunner(db), store),
        )

    private fun row(id: String, seq: Long) = CanonicalMessageRow(
        id = id,
        channelId = scope.scopeId,
        seq = seq,
        senderType = "user",
        senderId = "u1",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-14T00:00:0${seq}Z",
        senderName = "Alice",
    )

    private fun frame(vararg rows: CanonicalMessageRow) = FoldPersistFrame(
        scope = scope,
        facts = rows.toList(),
        cursor = PersistedCursor(latestSeq = rows.maxOfOrNull { it.seq }, oldestLoadedSeq = rows.minOfOrNull { it.seq }, newestLoadedSeq = rows.maxOfOrNull { it.seq }, epoch = "e1"),
    )

    @Test
    fun rotationBeforeApplyRejectsStaleApplierWithZeroStatements() = runBlocking {
        val (db, driver) = freshDb()
        val gate = OwnerCommitGate()
        val granted = gate.currentGeneration()
        gate.rotate() // B connects: the granted generation is now stale

        val failure = assertFailsWith<StaleOwnerGeneration> {
            bound(db, gate, store(db)).applyFrame(frame(row("m1", 1)), granted)
        }
        assertEquals(granted, failure.expected)
        assertEquals(granted + 1, failure.current)
        // Fail-closed means ZERO statements reached the database — not "wrote then rolled back".
        assertEquals(0, driver.begin, "a stale apply must never open a transaction")
        assertEquals(0, driver.commit)
        assertEquals(0, driver.rollback)
        assertTrue(store(db).readFacts(scope).isEmpty(), "facts must be zero")
        assertNull(store(db).readCursor(scope), "cursor must be zero")
    }

    @Test
    fun rotationRequestedAfterCheckPassedSerializesStrictlyAfterCommit() = runBlocking {
        val (db, driver) = freshDb()
        val gate = OwnerCommitGate()
        val real = store(db)
        // Pause the apply INSIDE the transaction: check already passed, BEGIN executed, no fact row written
        // yet — exactly the window #875's released-lock design left open.
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val pausing = object : FoldPersistStore by real {
            override suspend fun upsertFacts(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) {
                writeEntered.complete(Unit)
                releaseWrite.await()
                real.upsertFacts(scope, facts)
            }
        }
        val adapter = bound(db, gate, pausing)

        val apply = launch(Dispatchers.Default) {
            adapter.applyFrame(frame(row("m1", 1)), OwnerCommitGate.FIRST_GENERATION)
        }
        writeEntered.await()

        val rotateStarted = CompletableDeferred<Unit>()
        val rotation = async(Dispatchers.Default) {
            rotateStarted.complete(Unit)
            driver.events.add("rotate-requested")
            gate.rotate().also { driver.events.add("rotate-returned") }
        }
        rotateStarted.await()
        // Settle window so a broken gate WOULD complete the rotation here; on the correct gate this is
        // deterministic — the rotation cannot complete while the apply holds the gate, however long we wait.
        delay(100)
        assertFalse(rotation.isCompleted, "rotate must not complete inside another owner's check→COMMIT window")

        releaseWrite.complete(Unit)
        apply.join()
        assertEquals(OwnerCommitGate.FIRST_GENERATION + 1, rotation.await())

        // Order witness: the COMMIT happened strictly before the rotation returned — the only legal
        // serialization. "rotate between check and commit" has no representable schedule.
        val commitAt = driver.events.indexOf("commit")
        val rotateAt = driver.events.indexOf("rotate-returned")
        assertTrue(commitAt in 0 until rotateAt, "rotation must serialize after COMMIT, got ${driver.events}")
        // And this schedule is the legal one: the applier was still the current owner at COMMIT.
        assertEquals(listOf("m1"), real.readFacts(scope).map { it.id })
        assertEquals(1L, real.readCursor(scope)?.latestSeq)
    }

    @Test
    fun commitFailureIsOneRealRollbackKeepsGenerationAndReleasesGate() = runBlocking {
        val (db, driver) = freshDb()
        val gate = OwnerCommitGate()
        val real = store(db)
        val dying = object : FoldPersistStore by real {
            override suspend fun writeCursor(scope: SyncScopeKey, cursor: PersistedCursor): Unit =
                throw RuntimeException("process death after facts, before commit")
        }
        assertFailsWith<RuntimeException> {
            bound(db, gate, dying).applyFrame(frame(row("m1", 1)), OwnerCommitGate.FIRST_GENERATION)
        }
        assertEquals(1, driver.rollback, "mid-transaction death must be one real ROLLBACK")
        assertTrue(real.readFacts(scope).isEmpty(), "the failed apply's facts must not survive")

        // A failed commit must not burn the generation (the owner is still the owner) nor wedge the gate:
        // the SAME generation retries and commits cleanly.
        assertEquals(OwnerCommitGate.FIRST_GENERATION, gate.currentGeneration())
        bound(db, gate, real).applyFrame(frame(row("m1", 1)), OwnerCommitGate.FIRST_GENERATION)
        assertEquals(listOf("m1"), real.readFacts(scope).map { it.id })
    }

    @Test
    fun burnedGenerationRejectsLateApplierLeavingCommittedStateUntouched() = runBlocking {
        val (db, driver) = freshDb()
        val gate = OwnerCommitGate()
        val real = store(db)
        bound(db, gate, real).applyFrame(frame(row("m1", 1)), OwnerCommitGate.FIRST_GENERATION)
        val beginAfterCommit = driver.begin

        gate.rotate() // logout/reset burns the generation
        assertFailsWith<StaleOwnerGeneration> {
            bound(db, gate, real).applyFrame(frame(row("late", 2)), OwnerCommitGate.FIRST_GENERATION)
        }
        // The late applier changed NOTHING: no new transaction, prior committed state byte-identical.
        assertEquals(beginAfterCommit, driver.begin, "a burned-generation apply must not open a transaction")
        assertEquals(listOf("m1"), real.readFacts(scope).map { it.id })
        assertEquals(1L, real.readCursor(scope)?.latestSeq)
    }
}
