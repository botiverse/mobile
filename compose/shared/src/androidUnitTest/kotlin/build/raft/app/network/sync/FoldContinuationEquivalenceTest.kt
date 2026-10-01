/*
 * T-wrapper persistence — the (乙) CONTINUATION-EQUIVALENCE gate (RFC 043 §9.9), on a REAL SQLite backend.
 *
 * The store-level round-trip proves a resolved row survives persist->restore. This gate proves the stronger
 * fold-loop property AD2 required: `presentAggregates` is a wire-EVENT property (never a persisted column),
 * so restore rebuilds state from resolved VALUES alone — and continuing to fold from a restored state must
 * be byte-identical to continuing from the never-persisted in-memory state. If persist->restore drifted any
 * resolved three-state (a shared-null-preserve commentRef value, a present-overwrite list), the next frame's
 * merge would read the drifted existing value and DIVERGE — this gate catches exactly that.
 *
 * The three ingress presences (absent / explicit-null / empty-list) are produced by the PRODUCTION decoder
 * from real wire JSON (`containsKey`-based presence), then folded by the PRODUCTION `mergeCanonicalRow`:
 *   - explicit-null commentRef  -> shared-null-preserve  -> preserve the existing (restored) value;
 *   - empty-list reactions      -> present-overwrite      -> overwrite to [];
 *   - absent reactions          -> preserve               -> keep the existing (restored) list.
 */
package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.data.SlockSqlDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class FoldContinuationEquivalenceTest {

    private val scope = SyncScopeKey(
        partition = DurablePartitionKey("s1", "p1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
        scopeId = "c1",
        scopeKind = "channel",
    )

    private fun freshAdapter(): FoldPersistenceAdapter {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        build.raft.app.shared.data.local.SlockDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        val db = SlockSqlDatabase(driver)
        return DefaultFoldPersistenceAdapter(SqlDelightFoldPersistTransactionRunner(db), SqlDelightFoldPersistStore(db, now = { 100L }))
    }

    /** Resolved initial row: a non-null commentRef and a non-empty reactions list to be preserved/overwritten. */
    private fun initialRow() = CanonicalMessageRow(
        id = "m1",
        channelId = scope.scopeId,
        seq = 1,
        senderType = "user",
        senderId = "u1",
        content = "body",
        messageType = "chat",
        createdAt = "2026-07-12T00:00:01Z",
        senderName = "Alice",
        reactions = listOf(CanonicalReactionWire("👍", 1, listOf("u2"), listOf("Bob"))),
        commentRef = CanonicalCommentRefWire(
            "a1", "f.txt", "hm1",
            CanonicalCommentHostSourceWire("channel", "channel", "ch", "pm1", "tc1", "rtc1"),
            "line 3", "quoted",
        ),
    )

    /** A second `enrichedUpdated` frame on m1: all canonical-required keys + exactly the chosen aggregate keys. */
    private fun updateWire(aggregates: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("id", "m1")
        put("seq", 1L)
        put("channelId", scope.scopeId)
        put("senderType", "user")
        put("senderId", "u1")
        put("content", "body")
        put("messageType", "chat")
        put("randomId", JsonNull)
        put("threadId", JsonNull)
        put("createdAt", "2026-07-12T00:00:01Z")
        aggregates()
    }

    private fun bucket(state: MessageDomainState) = state.channelMessages[scope.scopeId].orEmpty()

    private fun foldUpdated(state: MessageDomainState, decoded: CanonicalMessageCarry) =
        applyMessageDomainEvent(state, MessageDomainEvent.Updated(decoded.row, decoded.presentAggregates))

    /** Path B middle: durably commit the state's facts through the REAL one-txn applyFrame, then restore. */
    private fun persistThenRestore(state: MessageDomainState): MessageDomainState = runBlocking {
        val facts = bucket(state)
        val adapter = freshAdapter()
        adapter.applyFrame(
            FoldPersistFrame(
                scope = scope,
                facts = facts,
                cursor = PersistedCursor(latestSeq = 1, oldestLoadedSeq = 1, newestLoadedSeq = 1),
            ),
        )
        // Rebuilt PURELY from restored facts — no presence is carried across the boundary (there is no
        // presentAggregates column to carry): that absence is the whole point of the equivalence below.
        MessageDomainState(channelMessages = mapOf(scope.scopeId to adapter.restore(scope).facts))
    }

    @Test
    fun continuationAfterPersistRestoreEqualsNeverPersistedAcrossThreeIngressPresences() {
        // state1: the resolved initial row (commentRef=X, reactions=[👍]), all aggregates present on messageNew.
        val state1 = applyMessageDomainEvent(
            createInitialMessageDomainState(),
            MessageDomainEvent.New(initialRow(), MessageDomainEvent.PRESENT_MESSAGE_NEW),
        )

        val variants = listOf(
            // explicit-null commentRef: shared-null-preserve MUST keep the existing (restored) commentRef=X.
            "explicit-null commentRef (shared-null-preserve)" to updateWire { put("commentRef", JsonNull) },
            // empty-list reactions: present-overwrite MUST clear to [].
            "empty-list reactions (present-overwrite)" to updateWire { putJsonArray("reactions") {} },
            // absent reactions: preserve the existing (restored) reactions=[👍].
            "absent reactions (preserve)" to updateWire { },
        )

        for ((label, wire) in variants) {
            val decoded = requireNotNull(decodeCanonicalMessageWire(wire, ProductionFace.EnrichedUpdated)) {
                "production decoder rejected the $label ingress frame"
            }

            // Path B middle: persist state1 through the real applyFrame and restore. First pin restore is
            // faithful to state1 — the resolved three-state (commentRef=X value, reactions=[👍], the null
            // aggregates) survives the codec+store round-trip; a drift here is what would poison continuation.
            val restoredState1 = persistThenRestore(state1)
            assertEquals(bucket(state1), bucket(restoredState1), "restore drifted state1 before continuation of $label")

            // Continue the SAME decoded frame from each state. Never-persisted vs persist->restore->continue
            // must be identical: proof that presentAggregates is neither persisted nor fabricated, and that
            // only resolved values (correctly preserved) feed the next mergeCanonicalRow.
            val neverPersisted = foldUpdated(state1, decoded)
            val afterRestore = foldUpdated(restoredState1, decoded)
            assertEquals(bucket(neverPersisted), bucket(afterRestore), "continuation diverged for $label")
        }
    }
}
