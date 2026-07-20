package build.raft.app.network.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadStateV1ContractTest {
    private val scope = ReadStateV1Scope("server-1", "user-1", "channel-1")

    @Test
    fun consumerAndWireNormalizationAreIndependentAndFullPairOnly() {
        assertEquals("sync-core.read-state.v1", scope.consumerId)
        assertNotEquals("sync-core.messages.v2", scope.consumerId)
        assertIs<ReadStateV1CandidateResult.Accepted>(
            normalizeReadStateV1Candidate(scope, candidate(ReadStateV1FactSource.Hydrate, 2, 20))
        )
        assertIs<ReadStateV1CandidateResult.Accepted>(
            normalizeReadStateV1Candidate(scope, candidate(ReadStateV1FactSource.MutationAck, 3, 30))
        )
        assertIs<ReadStateV1CandidateResult.Accepted>(
            normalizeReadStateV1Candidate(scope, candidate(ReadStateV1FactSource.OwnerRealtime, 4, 40))
        )
        assertEquals(
            ReadStateV1CandidateResult.Rejected(ReadStateV1CandidateRejection.UntrustedSource),
            normalizeReadStateV1Candidate(scope, candidate(ReadStateV1FactSource.PeerReceipt, 5, 50)),
        )
        val current = ReadStateV1Fact(scope.channelId, 7, 70)
        assertEquals(ReadStateV1ApplyResult.Current, reduceReadStateV1(current, current).result)
        assertEquals(ReadStateV1ApplyResult.Conflict, reduceReadStateV1(current, ReadStateV1Fact(scope.channelId, 7, 71)).result)
        assertEquals(ReadStateV1ApplyResult.Stale, reduceReadStateV1(current, ReadStateV1Fact(scope.channelId, 6, 99)).result)
        assertEquals(
            ReadStateV1Fact(scope.channelId, 8, 10),
            reduceReadStateV1(current, ReadStateV1Fact(scope.channelId, 8, 10)).fact,
            "higher version must allow a lower watermark",
        )
        assertEquals(
            ReadStateV1CandidateResult.Rejected(ReadStateV1CandidateRejection.IncompletePair),
            normalizeReadStateV1Candidate(
                scope,
                ReadStateV1WireCandidate(
                    channelId = scope.channelId,
                    readStateVersion = 6,
                    maxReadSeq = null,
                    source = ReadStateV1FactSource.MutationAck,
                ),
            ),
        )
    }

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
    fun concurrentConflictsCoalesceOneRebaselineRequest() = runBlocking {
        val authority = ReadStateV1GrantAuthority()
        val store = ConcurrentConflictStore()
        val requests = mutableListOf<ReadStateV1Scope>()
        val witness = authority.issue(scope)
        val engine = ReadStateV1GenerationEngine(
            authority = authority,
            store = store,
            projectionObserver = ReadStateV1ProjectionObserver { _, _ -> },
            rebaselineRequester = ReadStateV1RebaselineRequester { requests += it.scope },
        )
        assertEquals(
            ReadStateV1GenerationResult.Committed,
            engine.applySnapshot(witness, candidate(ReadStateV1FactSource.Hydrate, 7, 70)),
        )

        val results = listOf(
            async { engine.applyIncremental(witness, candidate(ReadStateV1FactSource.OwnerRealtime, 7, 71)) },
            async { engine.applyIncremental(witness, candidate(ReadStateV1FactSource.MutationAck, 7, 72)) },
        ).awaitAll()

        assertEquals(listOf(ReadStateV1GenerationResult.Conflict, ReadStateV1GenerationResult.Conflict), results)
        assertEquals(listOf(scope), requests)
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

    @Test
    fun revokeBeforeConflictOrCorruptRestoreRequestEmitsNothingForStaleGrant() = runBlocking {
        val conflictAuthority = ReadStateV1GrantAuthority()
        val conflictWitness = conflictAuthority.issue(scope)
        val conflictRequests = mutableListOf<ReadStateV1Scope>()
        val conflictProjections = mutableListOf<ReadStateV1Fact>()
        val conflictEngine = ReadStateV1GenerationEngine(
            authority = conflictAuthority,
            store = RevokingStore(conflictAuthority, conflictWitness, corruptOnRestore = false),
            projectionObserver = ReadStateV1ProjectionObserver { _, fact -> conflictProjections += fact },
            rebaselineRequester = ReadStateV1RebaselineRequester { conflictRequests += it.scope },
        )
        assertEquals(
            ReadStateV1GenerationResult.Committed,
            conflictEngine.applySnapshot(conflictWitness, candidate(ReadStateV1FactSource.Hydrate, 7, 70)),
        )
        assertEquals(
            ReadStateV1GenerationResult.RejectedCurrentGrant,
            conflictEngine.applyIncremental(conflictWitness, candidate(ReadStateV1FactSource.OwnerRealtime, 7, 71)),
        )
        assertEquals(emptyList(), conflictRequests)
        assertEquals(listOf(ReadStateV1Fact(scope.channelId, 7, 70)), conflictProjections)

        val corruptAuthority = ReadStateV1GrantAuthority()
        val corruptWitness = corruptAuthority.issue(scope)
        val corruptRequests = mutableListOf<ReadStateV1Scope>()
        val corruptEngine = ReadStateV1GenerationEngine(
            authority = corruptAuthority,
            store = RevokingStore(corruptAuthority, corruptWitness, corruptOnRestore = true),
            projectionObserver = ReadStateV1ProjectionObserver { _, _ -> error("revoked corrupt restore must not project") },
            rebaselineRequester = ReadStateV1RebaselineRequester { corruptRequests += it.scope },
        )
        assertEquals(ReadStateV1GenerationResult.RejectedCurrentGrant, corruptEngine.restore(corruptWitness))
        assertEquals(emptyList(), corruptRequests)
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

    private class ConcurrentConflictStore : ReadStateV1Store {
        private var incrementalCalls = 0
        private val bothIncrementalsArrived = CompletableDeferred<Unit>()

        override suspend fun commit(
            scope: ReadStateV1Scope,
            fact: ReadStateV1Fact,
            mode: ReadStateV1CommitMode,
            guard: ReadStateV1CommitGuard,
        ): ReadStateV1StoreResult {
            if (mode == ReadStateV1CommitMode.Snapshot) return ReadStateV1StoreResult.Accepted
            incrementalCalls += 1
            if (incrementalCalls == 2) bothIncrementalsArrived.complete(Unit)
            bothIncrementalsArrived.await()
            return ReadStateV1StoreResult.Conflict
        }

        override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? = null
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

    private class RevokingStore(
        private val authority: ReadStateV1GrantAuthority,
        private val witness: ReadStateV1GrantWitness,
        private val corruptOnRestore: Boolean,
    ) : ReadStateV1Store {
        override suspend fun commit(
            scope: ReadStateV1Scope,
            fact: ReadStateV1Fact,
            mode: ReadStateV1CommitMode,
            guard: ReadStateV1CommitGuard,
        ): ReadStateV1StoreResult {
            if (mode == ReadStateV1CommitMode.Snapshot) return ReadStateV1StoreResult.Accepted
            authority.revoke(witness)
            return ReadStateV1StoreResult.Conflict
        }

        override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? {
            check(corruptOnRestore)
            authority.revoke(witness)
            throw ReadStateV1StoredFactCorrupt(scope)
        }
    }
}
