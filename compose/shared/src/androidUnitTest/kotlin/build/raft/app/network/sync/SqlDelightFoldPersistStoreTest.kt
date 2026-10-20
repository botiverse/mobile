/*
 * T-wrapper SQLite store — real-driver gates (RFC 043 §9.9). JdbcSqliteDriver + PRAGMA foreign_keys=ON.
 *
 * Proves the store-level DoD on a REAL backend (backend-neutral one-txn tests can't): one applied frame =
 * exactly one real BEGIN + COMMIT (0 rollback); mid-block process death = one real ROLLBACK to the complete
 * pre-frame state; a full 19-field + nested canonical row survives round-trip through the JSON payload; the
 * scalar columns equal what you extract from decoding that payload; every SQL touch is scoped by the full
 * durable partition + scope (serverId + principalId + consumerId + scopeKind + scopeId); a cross-scope
 * same-partition id collision fail-stops on the REAL applyFrame path; a projection-tampered
 * scalar is poisoned (restore never returns a drifted row, and the payload itself is untouched); a malformed
 * row is a counted poison-gate input; and persist->restore preserves the resolved null/empty/value states.
 */
package build.raft.app.network.sync

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMentionWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.data.RaftSqlDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightFoldPersistStoreTest {

    /** Delegates everything to a real driver and counts real transaction boundaries. */
    private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var begin = 0; private set
        var commit = 0; private set
        var rollback = 0; private set

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            begin += 1
            return delegate.newTransaction().also { result ->
                result.value.afterCommit { commit += 1 }
                result.value.afterRollback { rollback += 1 }
            }
        }
    }

    private fun freshDb(): Pair<RaftSqlDatabase, CountingDriver> {
        val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        build.raft.app.shared.data.local.RaftDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        return RaftSqlDatabase(driver) to driver
    }

    private fun scopeKey(
        serverId: String = "s1",
        principalId: String = "p1",
        consumerId: String = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
        scopeId: String = "c1",
        scopeKind: String = "channel",
    ) = SyncScopeKey(
        partition = DurablePartitionKey(serverId, principalId, consumerId, "env-test"),
        scopeId = scopeId,
        scopeKind = scopeKind,
    )

    private val scope = scopeKey()

    private fun store(db: RaftSqlDatabase, onDecodeFailure: (SyncScopeKey, String) -> Unit = { _, _ -> }) =
        SqlDelightFoldPersistStore(db, now = { 100L }, onDecodeFailure = onDecodeFailure)

    private fun adapter(db: RaftSqlDatabase, faultStore: FoldPersistStore? = null) =
        DefaultFoldPersistenceAdapter(SqlDelightFoldPersistTransactionRunner(db), faultStore ?: store(db))

    private fun row(id: String, seq: Long, scopeKey: SyncScopeKey = scope) = CanonicalMessageRow(
        id = id,
        channelId = scopeKey.scopeId,
        seq = seq,
        senderType = "user",
        senderId = "u1",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-12T00:00:0${seq}Z",
        senderName = "Alice",
    )

    /** Every canonical field + every nested shape populated distinctly — a dropped field fails round-trip. */
    private fun fullRow(scopeKey: SyncScopeKey = scope) = CanonicalMessageRow(
        id = "full",
        channelId = scopeKey.scopeId,
        seq = 42,
        senderType = "agent",
        senderId = "u1",
        content = "hello",
        messageType = "chat",
        createdAt = "2026-07-12T00:00:42Z",
        randomId = "r1",
        threadId = "t1",
        senderName = "Alice",
        senderDescription = "eng",
        senderMembershipStatus = "active",
        mentions = listOf(CanonicalMentionWire("men1", "Bob", "user")),
        attachments = listOf(CanonicalAttachmentWire("a1", "f.txt", "text/plain", 12288, 4, 5, "https://cdn/x")),
        reactions = listOf(CanonicalReactionWire("👍", 2, listOf("u2"), listOf("Bob"))),
        commentRef = CanonicalCommentRefWire("a1", "f.txt", "hm1", CanonicalCommentHostSourceWire("channel", "channel", "ch", "pm1", "tc1", "rtc1"), "line 3", "quoted"),
        actionMetadata = buildJsonObject { put("kind", JsonPrimitive("card")) },
        conversationContext = CanonicalConversationContextWire("channel", "pc1", "channel", "pm2"),
    )

    private fun frame(scopeKey: SyncScopeKey, vararg rows: CanonicalMessageRow) = FoldPersistFrame(
        scope = scopeKey,
        facts = rows.toList(),
        cursor = PersistedCursor(latestSeq = rows.maxOfOrNull { it.seq }, oldestLoadedSeq = rows.minOfOrNull { it.seq }, newestLoadedSeq = rows.maxOfOrNull { it.seq }, epoch = "e1"),
    )

    @Test
    fun oneFrameApplyIsExactlyOneRealBeginCommit() = runBlocking {
        val (db, driver) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 1), row("m2", 2)))
        assertEquals(1, driver.begin, "one frame apply = one BEGIN")
        assertEquals(1, driver.commit, "one frame apply = one COMMIT")
        assertEquals(0, driver.rollback, "a clean apply never rolls back")
    }

    @Test
    fun fullFieldRoundTripThroughPayload() = runBlocking {
        val (db, _) = freshDb()
        val fact = fullRow()
        adapter(db).applyFrame(frame(scope, fact))
        assertEquals(listOf(fact), store(db).readFacts(scope), "every canonical + nested field must survive round-trip via the JSON payload")
    }

    @Test
    fun scalarColumnsEqualExtractOfDecodedPayload() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 5)))
        val stored = db.queries.selectServerBackedMessagesForChannel(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsList().single()
        val decoded = decodeCanonicalFact(stored.canonicalFactJson)!!
        assertEquals(stored.id, decoded.id)
        assertEquals(stored.channelId, decoded.channelId)
        assertEquals(stored.seq, decoded.seq)
        assertEquals(stored.createdAt, decoded.createdAt)
    }

    @Test
    fun sqlIsFullThreePartScopeIsolated() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 1)))
        // Same server+scopeId but a DIFFERENT scopeKind must not see it (a channel vs a thread on c1).
        val sameIdOtherKind = scopeKey(scopeKind = "thread")
        assertTrue(store(db).readFacts(sameIdOtherKind).isEmpty(), "different scopeKind (same scopeId) must be isolated")
        // Different server must not see it.
        assertTrue(store(db).readFacts(scopeKey(serverId = "s2")).isEmpty(), "different server must be isolated")
        // Different channelId must not see it.
        assertTrue(store(db).readFacts(scopeKey(scopeId = "c2")).isEmpty(), "different channelId must be isolated")
        assertEquals(listOf("m1"), store(db).readFacts(scope).map { it.id })
    }

    @Test
    fun cursorIsFullThreePartScoped() = runBlocking {
        val (db, _) = freshDb()
        // A channel and a thread sharing serverId + scopeId must NOT collide on the cursor.
        adapter(db).applyFrame(frame(scope, row("m1", 5)))
        adapter(db).applyFrame(frame(scopeKey(scopeKind = "thread"), row("t1", 9)))
        assertEquals(5L, store(db).readCursor(scope)?.latestSeq)
        assertEquals(9L, store(db).readCursor(scopeKey(scopeKind = "thread"))?.latestSeq)
    }

    @Test
    fun crossScopeSameIdFailStopsOnRealApplyFrame() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("dup", 1)))
        val other = scopeKey(scopeId = "c2")
        assertFailsWith<FoldPersistScopeConflict> {
            adapter(db).applyFrame(frame(other, row("dup", 2, other)))
        }
        assertEquals(listOf("dup"), store(db).readFacts(scope).map { it.id }, "the original scope's fact is untouched")
        assertTrue(store(db).readFacts(other).isEmpty(), "the conflicting frame rolled back")
    }

    @Test
    fun midBlockDeathIsOneRealRollbackToPreFrame() = runBlocking {
        val (db, driver) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 1)))
        val beforeRollback = driver.rollback
        val dying = object : FoldPersistStore by store(db) {
            override suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>): Unit =
                throw RuntimeException("process death mid-block")
        }
        assertFailsWith<RuntimeException> { adapter(db, faultStore = dying).applyFrame(frame(scope, row("m2", 2))) }
        assertEquals(beforeRollback + 1, driver.rollback, "mid-block death must be one real ROLLBACK")
        assertEquals(listOf("m1"), store(db).readFacts(scope).map { it.id }, "rolled back to the complete pre-frame state")
    }

    @Test
    fun projectionTamperOfScalarIsPoisonedNotReturned() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("m1", 3)))
        // Tamper ONLY the scalar seq column (leave the payload intact) — a drifted projection.
        db.execute("UPDATE messageEntity SET seq = 999 WHERE id = 'm1';")
        val poisoned = mutableListOf<String>()
        val facts = store(db, onDecodeFailure = { _, id -> poisoned.add(id) }).readFacts(scope)
        assertTrue(facts.isEmpty(), "restore must not return a row whose scalar drifted from its payload")
        assertEquals(listOf("m1"), poisoned, "the drifted row is a counted poison-gate input")
        // Restore-purity: the payload itself was never mutated — it still decodes to the true fact.
        val payload = db.queries.selectServerBackedMessagesForChannel(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsList().single().canonicalFactJson
        assertEquals(3L, decodeCanonicalFact(payload)?.seq, "the payload seq is untouched; only the scalar was tampered")
    }

    @Test
    fun malformedPayloadIsFailClosedAndCounted() = runBlocking {
        val (db, _) = freshDb()
        adapter(db).applyFrame(frame(scope, row("bad", 1)))
        db.execute("UPDATE messageEntity SET canonicalFactJson = 'not-json' WHERE id = 'bad';")
        val poisoned = mutableListOf<String>()
        val facts = store(db, onDecodeFailure = { _, id -> poisoned.add(id) }).readFacts(scope)
        assertTrue(facts.isEmpty())
        assertEquals(listOf("bad"), poisoned, "a malformed payload is reported to the poison gate, not silently dropped")
    }

    @Test
    fun persistRestorePreservesResolvedNullEmptyValueStates() = runBlocking {
        val (db, _) = freshDb()
        // Resolved three states the fold can produce: null nullable + empty list; and a populated value.
        val nulls = row("nulls", 1).copy(commentRef = null, conversationContext = null, reactions = emptyList())
        val values = fullRow().copy(id = "values", channelId = "c1")
        adapter(db).applyFrame(FoldPersistFrame(scope, listOf(nulls, values), PersistedCursor(2, 1, 2, epoch = "e1")))
        val restored = store(db).readFacts(scope).associateBy { it.id }
        assertNull(restored["nulls"]!!.commentRef, "resolved null must survive as null (not [] or a value)")
        assertEquals(emptyList(), restored["nulls"]!!.reactions, "resolved empty must survive as empty")
        assertEquals(values, restored["values"], "a fully-populated resolved fact round-trips exactly")
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
