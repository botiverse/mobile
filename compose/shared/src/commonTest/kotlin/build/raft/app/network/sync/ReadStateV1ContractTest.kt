package build.raft.app.network.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadStateV1ContractTest {
    private val scope = ReadStateV1Scope("server-1", "user-1", "channel-1")

    @Test
    fun generationHoldsBeforeBaselineThenCommitsBeforeProjection() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val requests = mutableListOf<ReadStateV1GrantWitness>()
        val authority = ReadStateV1GrantAuthority()
        val engine = engine(authority, store, events, requests)
        val witness = authority.issue(scope)

        assertEquals(
            ReadStateV1GenerationResult.BaselineRequired(requested = true),
            engine.applyIncremental(witness, candidate(ReadStateV1FactSource.MutationAck, 2, 20)),
        )
        assertEquals(
            ReadStateV1GenerationResult.BaselineRequired(requested = false),
            engine.applyIncremental(witness, candidate(ReadStateV1FactSource.OwnerRealtime, 3, 30)),
        )
        assertEquals(1, requests.size)
        assertSame(witness, requests.single(), "rebaseline must retain the original opaque witness")
        assertEquals(emptyList(), store.commits)

        assertEquals(
            ReadStateV1GenerationResult.Committed,
            engine.applySnapshot(witness, candidate(ReadStateV1FactSource.Hydrate, 4, 40)),
        )
        assertEquals(listOf("store:Snapshot:4", "project:4"), events)

        assertEquals(
            ReadStateV1GenerationResult.Committed,
            engine.applyIncremental(witness, candidate(ReadStateV1FactSource.OwnerRealtime, 5, 10)),
        )
        assertEquals(listOf("store:Snapshot:4", "project:4", "store:Incremental:5", "project:5"), events)
    }

    @Test
    fun restoreEstablishesBaselineAndConflictRequestsRebaseline() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeStore(events, restored = ReadStateV1Fact(scope.channelId, 7, 70))
        val requests = mutableListOf<ReadStateV1GrantWitness>()
        val authority = ReadStateV1GrantAuthority()
        val engine = engine(authority, store, events, requests)
        val witness = authority.issue(scope)

        assertEquals(ReadStateV1GenerationResult.Committed, engine.restore(witness))
        store.nextResult = ReadStateV1StoreResult.Conflict
        assertEquals(
            ReadStateV1GenerationResult.Conflict,
            engine.applyIncremental(witness, candidate(ReadStateV1FactSource.OwnerRealtime, 7, 71)),
        )
        assertEquals(1, requests.size)
        assertEquals(
            ReadStateV1GenerationResult.BaselineRequired(requested = false),
            engine.applyIncremental(witness, candidate(ReadStateV1FactSource.OwnerRealtime, 8, 80)),
        )
    }

    @Test
    fun staleGrantAndRevokeSuppressPersistenceAndProjection() = runBlocking {
        val events = mutableListOf<String>()
        val authority = ReadStateV1GrantAuthority()
        val store = FakeStore(events)
        val engine = engine(authority, store, events, mutableListOf())
        val old = authority.issue(scope)
        val current = authority.issue(scope)

        assertEquals(
            ReadStateV1GenerationResult.RejectedCurrentGrant,
            engine.applySnapshot(old, candidate(ReadStateV1FactSource.Hydrate, 1, 10)),
        )
        assertEquals(ReadStateV1GenerationResult.Committed, engine.applySnapshot(current, candidate(ReadStateV1FactSource.Hydrate, 2, 20)))
        engine.revoke(current)
        assertEquals(
            ReadStateV1GenerationResult.RejectedCurrentGrant,
            engine.applyIncremental(current, candidate(ReadStateV1FactSource.MutationAck, 3, 30)),
        )
        assertEquals(listOf("project:2"), events.filter { it.startsWith("project:") })
    }

    @Test
    fun corruptRestoreFailsClosedAndCoalescesCleanHydrate() = runBlocking {
        val authority = ReadStateV1GrantAuthority()
        val requests = mutableListOf<ReadStateV1Scope>()
        val witness = authority.issue(scope)
        val engine = ReadStateV1GenerationEngine(
            authority = authority,
            store = CorruptRestoreStore(),
            projectionObserver = ReadStateV1ProjectionObserver { _, _ -> error("corrupt restore must not project") },
            rebaselineRequester = ReadStateV1RebaselineRequester { requests += it.scope },
        )

        assertEquals(ReadStateV1GenerationResult.BaselineRequired(true), engine.restore(witness))
        assertEquals(ReadStateV1GenerationResult.BaselineRequired(false), engine.restore(witness))
        assertEquals(listOf(scope), requests)
    }

    private fun engine(
        authority: ReadStateV1GrantAuthority,
        store: FakeStore,
        events: MutableList<String>,
        requests: MutableList<ReadStateV1GrantWitness>,
    ) = ReadStateV1GenerationEngine(
        authority = authority,
        store = store,
        projectionObserver = ReadStateV1ProjectionObserver { _, fact -> events += "project:${fact.readStateVersion}" },
        rebaselineRequester = ReadStateV1RebaselineRequester { requests += it },
    )

    private fun candidate(source: ReadStateV1FactSource, version: Long, seq: Long) =
        ReadStateV1WireCandidate(scope.channelId, version, seq, source)

    private class FakeStore(
        private val events: MutableList<String>,
        private val restored: ReadStateV1Fact? = null,
    ) : ReadStateV1Store {
        val commits = mutableListOf<ReadStateV1Fact>()
        var nextResult: ReadStateV1StoreResult = ReadStateV1StoreResult.Accepted

        override suspend fun commit(
            scope: ReadStateV1Scope,
            fact: ReadStateV1Fact,
            mode: ReadStateV1CommitMode,
            guard: ReadStateV1CommitGuard,
        ): ReadStateV1StoreResult {
            assertTrue(guard.isCurrent())
            commits += fact
            events += "store:$mode:${fact.readStateVersion}"
            return nextResult
        }

        override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? = restored
    }

    private class CorruptRestoreStore : ReadStateV1Store {
        override suspend fun commit(
            scope: ReadStateV1Scope,
            fact: ReadStateV1Fact,
            mode: ReadStateV1CommitMode,
            guard: ReadStateV1CommitGuard,
        ): ReadStateV1StoreResult = error("not used")

        override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? {
            throw ReadStateV1StoredFactCorrupt(scope)
        }
    }
}
