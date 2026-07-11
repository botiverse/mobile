/*
 * T-wrapper one-txn gate — RED tests (RFC 043 §9.9). Written first (TDD); they fail against the pending
 * DefaultFoldPersistenceAdapter and go green when the single-transaction apply lands.
 *
 * Primary assertion: one frame apply = exactly one storage transaction (count invariant). Behavioral twins:
 * kill-before-commit restores the complete pre-frame state, kill-after-commit the complete post-frame state
 * (never a torn mix). All kill injection is through the deterministic PersistFaultHook — never wall-clock.
 */
package build.raft.app.network.sync

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FoldPersistenceOneTxnTest {

    private val scope = SyncScopeKey(serverId = "s1", scopeId = "c1", scopeKind = "channel")

    private fun row(id: String, seq: Long) = CanonicalMessageRow(
        id = MessageId(id),
        channelId = ChannelId("c1"),
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-11T00:00:00Z",
        senderName = "Alice",
    )

    private fun frame(vararg rows: CanonicalMessageRow) = FoldPersistFrame(
        scope = scope,
        facts = rows.toList(),
        cursor = PersistedCursor(latestSeq = rows.maxOfOrNull { it.seq }, oldestLoadedSeq = rows.minOfOrNull { it.seq }, newestLoadedSeq = rows.maxOfOrNull { it.seq }, epoch = "e1"),
    )

    /** Counts commit boundaries — the count invariant observes this directly. */
    private class CountingTransactionRunner(private val faultHook: PersistFaultHook) : FoldPersistTransactionRunner {
        var runCount = 0
            private set
        override suspend fun run(block: suspend () -> Unit) {
            runCount++
            if (faultHook.shouldKillAt(PersistFaultPoint.BeforeCommit)) throw PersistKilled(PersistFaultPoint.BeforeCommit)
            block()
            if (faultHook.shouldKillAt(PersistFaultPoint.AfterCommit)) throw PersistKilled(PersistFaultPoint.AfterCommit)
        }
    }

    /** In-memory store double. Writes are only visible after the enclosing run() completes without a tear. */
    private class RecordingFoldPersistStore : FoldPersistStore {
        private val facts = mutableMapOf<String, MutableList<CanonicalMessageRow>>()
        private val cursors = mutableMapOf<String, PersistedCursor>()
        private fun key(s: SyncScopeKey) = "${s.serverId}:${s.scopeId}"
        override suspend fun upsertFacts(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) {
            this.facts.getOrPut(key(scope)) { mutableListOf() }.apply { addAll(facts) }
        }
        override suspend fun writeCursor(scope: SyncScopeKey, cursor: PersistedCursor) { cursors[key(scope)] = cursor }
        override suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) {}
        override suspend fun prune(scope: SyncScopeKey, factIds: List<MessageId>) {
            this.facts[key(scope)]?.removeAll { it.id in factIds }
        }
        override suspend fun readFacts(scope: SyncScopeKey) = facts[key(scope)]?.sortedBy { it.seq }.orEmpty()
        override suspend fun readCursor(scope: SyncScopeKey) = cursors[key(scope)]
    }

    private fun adapter(faultHook: PersistFaultHook = PersistFaultHook.Never): Pair<DefaultFoldPersistenceAdapter, CountingTransactionRunner> {
        val runner = CountingTransactionRunner(faultHook)
        return DefaultFoldPersistenceAdapter(runner, RecordingFoldPersistStore()) to runner
    }

    @Test
    fun oneFrameApplyIsExactlyOneTransaction() = runBlocking {
        val (adapter, runner) = adapter()
        adapter.applyFrame(frame(row("m1", 1), row("m2", 2)))
        assertEquals(1, runner.runCount, "one frame apply must commit in exactly one transaction")
    }

    @Test
    fun killBeforeCommitRestoresCompletePreFrameState() = runBlocking {
        val (adapter, _) = adapter(faultHook = { it == PersistFaultPoint.BeforeCommit })
        assertFailsWith<PersistKilled> { adapter.applyFrame(frame(row("m1", 1))) }
        val restored = adapter.restore(scope)
        assertTrue(restored.facts.isEmpty(), "kill-before-commit must restore the empty pre-frame facts")
        assertEquals(null, restored.cursor, "kill-before-commit must not advance the cursor")
    }

    @Test
    fun killAfterCommitRestoresCompletePostFrameState() = runBlocking {
        val (adapter, _) = adapter(faultHook = { it == PersistFaultPoint.AfterCommit })
        assertFailsWith<PersistKilled> { adapter.applyFrame(frame(row("m1", 1), row("m2", 2))) }
        val restored = adapter.restore(scope)
        assertEquals(listOf("m1", "m2"), restored.facts.map { it.id.value }, "post-commit facts must be complete")
        assertEquals(2L, restored.cursor?.latestSeq, "post-commit cursor must be advanced")
    }

    @Test
    fun stateRoundTripIdentity() = runBlocking {
        val (adapter, _) = adapter()
        val f = frame(row("m1", 1), row("m2", 2))
        adapter.applyFrame(f)
        val restored = adapter.restore(scope)
        assertEquals(f.facts.map { it.id.value }, restored.facts.map { it.id.value })
        assertEquals(f.cursor.latestSeq, restored.cursor?.latestSeq)
        assertEquals(f.cursor.epoch, restored.cursor?.epoch)
    }
}
