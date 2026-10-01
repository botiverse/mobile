/*
 * T-wrapper one-txn gate — RED tests (RFC 043 §9.9). Written first (TDD); they fail against the pending
 * DefaultFoldPersistenceAdapter and go green when the single-transaction apply lands.
 *
 * Primary assertion: one frame apply = exactly one storage transaction (count invariant). Behavioral twins:
 * kill-before-commit restores the complete pre-frame state, kill-after-commit the complete post-frame state
 * (never a torn mix). All kill injection is through the deterministic PersistFaultHook — never wall-clock.
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.model.MessageId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FoldPersistenceOneTxnTest {

    private val scope = SyncScopeKey(
        partition = DurablePartitionKey("s1", "p1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
        scopeId = "c1",
        scopeKind = "channel",
    )

    private fun row(id: String, seq: Long) = CanonicalMessageRow(
        id = id,
        channelId = "c1",
        seq = seq,
        senderType = "user",
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
            this.facts[key(scope)]?.removeAll { MessageId(it.id) in factIds }
        }
        override suspend fun clearScope(scope: SyncScopeKey) { this.facts.remove(key(scope)) }
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
        assertEquals(listOf("m1", "m2"), restored.facts.map { it.id }, "post-commit facts must be complete")
        assertEquals(2L, restored.cursor?.latestSeq, "post-commit cursor must be advanced")
    }

    @Test
    fun differenceBatchAppliesNFactsAndCursorInOneTransaction() = runBlocking {
        // 赵 note-review must-fix: a difference response applies N facts + advances the cursor as ONE ingest
        // call, so it must be one transaction (not N per-fact transactions — that reintroduces the tear).
        val (adapter, runner) = adapter()
        adapter.applyFrame(frame(row("m1", 1), row("m2", 2), row("m3", 3)))
        assertEquals(1, runner.runCount, "an N-fact difference batch + cursor must be exactly one transaction")
        assertEquals(listOf("m1", "m2", "m3"), adapter.restore(scope).facts.map { it.id })
    }

    @Test
    fun snapshotRebaselineClearsAndRebuildsInOneTransaction() = runBlocking {
        // Snapshot rebaseline = clear the scope + rebuild + advance cursor, all in one transaction.
        val (adapter, runner) = adapter()
        adapter.applyFrame(frame(row("old1", 1), row("old2", 2))) // pre-existing facts
        val rebaseline = FoldPersistFrame(
            scope = scope,
            facts = listOf(row("new1", 10), row("new2", 11)),
            cursor = PersistedCursor(latestSeq = 11, oldestLoadedSeq = 10, newestLoadedSeq = 11, epoch = "e2"),
            rebaseline = true,
        )
        adapter.applyFrame(rebaseline)
        assertEquals(2, runner.runCount, "two ingest calls = two transactions (each internally atomic)")
        val restored = adapter.restore(scope)
        assertEquals(listOf("new1", "new2"), restored.facts.map { it.id }, "rebaseline drops old facts")
        assertEquals("e2", restored.cursor?.epoch, "rebaseline advances epoch in the same txn")
    }

    @Test
    fun snapshotRebaselineKillBeforeCommitKeepsCompletePreRebaselineState() = runBlocking {
        // A torn rebaseline (scope cleared but not rebuilt) is the exact disease one-txn prevents: a
        // kill-before-commit must leave the COMPLETE pre-rebaseline state, never an emptied scope.
        val runner = CountingTransactionRunner(PersistFaultHook.Never)
        val store = RecordingFoldPersistStore()
        val adapter = DefaultFoldPersistenceAdapter(runner, store)
        adapter.applyFrame(frame(row("old1", 1), row("old2", 2)))
        // Second adapter over the SAME store, faulting before commit on the rebaseline.
        val faulting = DefaultFoldPersistenceAdapter(CountingTransactionRunner { it == PersistFaultPoint.BeforeCommit }, store)
        assertFailsWith<PersistKilled> {
            faulting.applyFrame(FoldPersistFrame(scope, listOf(row("new1", 10)), PersistedCursor(10, 10, 10, epoch = "e2"), rebaseline = true))
        }
        assertEquals(listOf("old1", "old2"), adapter.restore(scope).facts.map { it.id }, "torn rebaseline must not empty the scope")
    }

    @Test
    fun stateRoundTripIdentity() = runBlocking {
        val (adapter, _) = adapter()
        val f = frame(row("m1", 1), row("m2", 2))
        adapter.applyFrame(f)
        val restored = adapter.restore(scope)
        assertEquals(f.facts.map { it.id }, restored.facts.map { it.id })
        assertEquals(f.cursor.latestSeq, restored.cursor?.latestSeq)
        assertEquals(f.cursor.epoch, restored.cursor?.epoch)
    }
}
