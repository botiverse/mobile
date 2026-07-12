/*
 * T-wrapper SQLite store — real-driver gates (RFC 043 §9.9). JdbcSqliteDriver + PRAGMA foreign_keys=ON.
 *
 * Proves the store-level DoD on a REAL backend (backend-neutral one-txn tests can't): one applied frame =
 * exactly one real BEGIN/COMMIT; mid-block process death rolls back to the complete pre-frame state; the
 * full canonical row survives round-trip through the JSON payload; the scalar columns equal what you extract
 * from decoding that payload; every SQL touch is 3-part scoped; a cross-scope same-id collision fail-stops
 * on the REAL applyFrame path; and a malformed row is a counted poison-gate input, never a silent drop.
 */
package build.raft.app.network.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.SlockSqlDatabase
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightFoldPersistStoreTest {

    /** Delegates everything to a real driver and counts real transaction starts (BEGIN). */
    private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var beginCount = 0
            private set
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (sql.trimStart().startsWith("BEGIN", ignoreCase = true)) beginCount++
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private fun freshDb(): Pair<SlockSqlDatabase, CountingDriver> {
        val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        build.raft.app.shared.data.local.SlockDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        return SlockSqlDatabase(driver) to driver
    }

    private val scope = SyncScopeKey(serverId = "s1", scopeId = "c1", scopeKind = "channel")
    private val otherScope = SyncScopeKey(serverId = "s1", scopeId = "c2", scopeKind = "channel")

    private fun store(db: SlockSqlDatabase, onDecodeFailure: (SyncScopeKey, String) -> Unit = { _, _ -> }) =
        SqlDelightFoldPersistStore(db, now = { 100L }, onDecodeFailure = onDecodeFailure)

    private fun adapter(db: SlockSqlDatabase, faultStore: FoldPersistStore? = null) =
        DefaultFoldPersistenceAdapter(SqlDelightFoldPersistTransactionRunner(db), faultStore ?: store(db))

    private fun row(id: String, seq: Long, scopeKey: SyncScopeKey = scope) = CanonicalMessageRow(
        id = MessageId(id),
        channelId = ChannelId(scopeKey.scopeId),
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-12T00:00:0${seq}Z",
        senderName = "Alice",
    )

    private fun frame(scopeKey: SyncScopeKey, vararg rows: CanonicalMessageRow) = FoldPersistFrame(
        scope = scopeKey,
        facts = rows.toList(),
        cursor = PersistedCursor(latestSeq = rows.maxOfOrNull { it.seq }, oldestLoadedSeq = rows.minOfOrNull { it.seq }, newestLoadedSeq = rows.maxOfOrNull { it.seq }, epoch = "e1"),
    )

    @Test
    fun oneFrameApplyIsExactlyOneRealTransaction() = runBlocking {
        val (db, driver) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 1), row("m2", 2)))
        assertEquals(1, driver.beginCount, "one frame apply must be exactly one real BEGIN/COMMIT")
    }

    @Test
    fun fullFieldRoundTripThroughPayload() = runBlocking {
        val (db, _) = freshDb()
        val fact = row("m1", 7).copy(
            senderMembershipStatus = "active",
            attachments = listOf(CanonicalAttachmentWire("a1", "f.txt", "text/plain", 12288, 4, 5, "https://cdn/x")),
            conversationContext = CanonicalConversationContextWire("channel", "pc1", "channel", "pm1"),
        )
        adapter(db).applyFrame(frame(scope, fact))
        assertEquals(listOf(fact), store(db).readFacts(scope), "the full canonical row must survive round-trip via the JSON payload")
    }

    @Test
    fun scalarColumnsEqualExtractOfDecodedPayload() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 5)))
        val storedRow = db.queries.selectServerBackedMessagesForChannel("s1", "channel", "c1").executeAsList().single()
        val decoded = decodeCanonicalFact(storedRow.canonicalFactJson)!!
        assertEquals(storedRow.seq, decoded.seq, "scalar seq must equal extract(decode(payload))")
        assertEquals(storedRow.createdAt, decoded.createdAt, "scalar createdAt must equal extract(decode(payload))")
    }

    @Test
    fun sqlIsThreePartScopeIsolated() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 1)))
        assertTrue(store(db).readFacts(otherScope).isEmpty(), "a different scope (channelId) must not see this scope's facts")
    }

    @Test
    fun crossScopeSameIdFailStopsOnRealApplyFrame() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("dup", 1))) // id "dup" committed in scope c1
        // A frame in scope c2 carrying the SAME globally-unique id: INSERT-ignored, scoped UPDATE 0 rows —
        // must poison the real apply, not silently succeed.
        assertFailsWith<FoldPersistScopeConflict> {
            adapter(db).applyFrame(frame(otherScope, row("dup", 2, otherScope)))
        }
        assertEquals(listOf("dup"), store(db).readFacts(scope).map { it.id.value }, "the original scope's fact is untouched")
        assertTrue(store(db).readFacts(otherScope).isEmpty(), "the conflicting frame rolled back — no fact in the other scope")
    }

    @Test
    fun midBlockDeathRollsBackToCompletePreFrameState() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 1))) // pre-frame: one committed fact
        // A store that dies mid-block (between upsertFacts and writeCursor — maintainTimelineIndex runs
        // there): the real transaction must ROLLBACK, leaving the complete pre-frame state.
        val dying = object : FoldPersistStore by store(db) {
            override suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>): Unit =
                throw RuntimeException("process death mid-block")
        }
        assertFailsWith<RuntimeException> { adapter(db, faultStore = dying).applyFrame(frame(scope, row("m2", 2))) }
        assertEquals(listOf("m1"), store(db).readFacts(scope).map { it.id.value }, "mid-block death must roll back the whole frame")
    }

    @Test
    fun malformedRowIsFailClosedAndCounted() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("good", 1)))
        // Corrupt one row's payload directly (a poison specimen); readFacts must drop it + report, not decode.
        db.execute("UPDATE messageEntity SET canonicalFactJson = 'not-json' WHERE id = 'good';")
        val failures = mutableListOf<String>()
        val facts = store(db, onDecodeFailure = { _, id -> failures.add(id) }).readFacts(scope)
        assertTrue(facts.isEmpty(), "a malformed payload must not decode into a partial row")
        assertEquals(listOf("good"), failures, "the malformed row must be reported to the poison gate, not silently dropped")
    }

    @Test
    fun restoreYieldsNullCursorBeforeFirstWrite() = runBlocking {
        val (db, _) = freshDb()
        assertNull(store(db).readCursor(scope))
        adapter(db).applyFrame(frame(scope, row("m1", 3)))
        assertEquals(3L, store(db).readCursor(scope)?.latestSeq)
        assertEquals("e1", store(db).readCursor(scope)?.epoch)
    }
}
