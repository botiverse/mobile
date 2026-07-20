package build.raft.app.network.sync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadStateV1PublicationFenceTest {
    private val scope = ReadStateV1Scope("server-1", "user-1", "channel-1")

    @Test
    fun projectionPublicationFirstMakesRevokeWaitForTheExactWitness() = runBlocking {
        val authority = ReadStateV1GrantAuthority()
        val witness = authority.issue(scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<ReadStateV1GrantWitness>()
        val engine = engine(
            authority = authority,
            store = AcceptingStore(),
            projection = { observedWitness, _ ->
                observed += observedWitness
                entered.complete(Unit)
                runBlocking { release.await() }
            },
        )

        val publication = async(Dispatchers.Default) {
            engine.applySnapshot(witness, candidate(ReadStateV1FactSource.Hydrate, 1, 10))
        }
        withTimeout(2_000) { entered.await() }
        assertTrue(
            withTimeout(2_000) { async(Dispatchers.Default) { authority.isCurrent(witness) }.await() },
            "the authority current lock must remain available while the destination callback is blocked",
        )
        val revoke = async(Dispatchers.Default) { engine.revoke(witness) }
        delay(100)
        assertFalse(revoke.isCompleted, "revoke must wait for a publication that already linearized")

        release.complete(Unit)
        assertEquals(ReadStateV1GenerationResult.Committed, withTimeout(2_000) { publication.await() })
        withTimeout(2_000) { revoke.await() }
        assertEquals(listOf(witness), observed)
        assertEquals(
            ReadStateV1GenerationResult.RejectedCurrentGrant,
            engine.applySnapshot(witness, candidate(ReadStateV1FactSource.Hydrate, 2, 20)),
        )
        assertEquals(listOf(witness), observed, "revoke return is a terminal old-witness publication barrier")
    }

    @Test
    fun rebaselinePublicationFirstMakesRevokeWaitForTheExactWitness() = runBlocking {
        val authority = ReadStateV1GrantAuthority()
        val witness = authority.issue(scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requested = mutableListOf<ReadStateV1GrantWitness>()
        val engine = engine(
            authority = authority,
            store = AcceptingStore(),
            rebaseline = { observedWitness ->
                requested += observedWitness
                entered.complete(Unit)
                runBlocking { release.await() }
            },
        )

        val publication = async(Dispatchers.Default) {
            engine.applyIncremental(witness, candidate(ReadStateV1FactSource.MutationAck, 1, 10))
        }
        withTimeout(2_000) { entered.await() }
        val revoke = async(Dispatchers.Default) { engine.revoke(witness) }
        delay(100)
        assertFalse(revoke.isCompleted, "revoke must wait for a rebaseline request that already linearized")

        release.complete(Unit)
        assertEquals(ReadStateV1GenerationResult.BaselineRequired(true), withTimeout(2_000) { publication.await() })
        withTimeout(2_000) { revoke.await() }
        assertEquals(listOf(witness), requested)
        assertEquals(
            ReadStateV1GenerationResult.RejectedCurrentGrant,
            engine.applyIncremental(witness, candidate(ReadStateV1FactSource.MutationAck, 2, 20)),
        )
        assertEquals(listOf(witness), requested, "revoke return is also a terminal rebaseline barrier")
    }

    @Test
    fun revokeFirstSuppressesProjectionAfterDurableCommit() = runBlocking {
        val authority = ReadStateV1GrantAuthority()
        val witness = authority.issue(scope)
        val store = BlockingCommitStore()
        val observed = mutableListOf<ReadStateV1GrantWitness>()
        val engine = engine(authority, store, projection = { observedWitness, _ -> observed += observedWitness })

        val apply = async(Dispatchers.Default) {
            engine.applySnapshot(witness, candidate(ReadStateV1FactSource.Hydrate, 1, 10))
        }
        withTimeout(2_000) { store.committed.await() }
        engine.revoke(witness)
        store.release.complete(Unit)

        assertEquals(ReadStateV1GenerationResult.RejectedCurrentGrant, withTimeout(2_000) { apply.await() })
        assertEquals(emptyList(), observed)
    }

    @Test
    fun sameScopeReplacementSuppressesOldProjectionAndRebaselineButPublishesNewWitness() = runBlocking {
        val projectionAuthority = ReadStateV1GrantAuthority()
        val oldProjection = projectionAuthority.issue(scope)
        val projectionStore = BlockingCommitStore()
        val projected = mutableListOf<ReadStateV1GrantWitness>()
        val projectionEngine = engine(
            projectionAuthority,
            projectionStore,
            projection = { observedWitness, _ -> projected += observedWitness },
        )
        val oldProjectionApply = async(Dispatchers.Default) {
            projectionEngine.applySnapshot(oldProjection, candidate(ReadStateV1FactSource.Hydrate, 1, 10))
        }
        withTimeout(2_000) { projectionStore.committed.await() }
        val newProjection = projectionAuthority.issue(scope)
        projectionStore.release.complete(Unit)
        assertEquals(
            ReadStateV1GenerationResult.RejectedCurrentGrant,
            withTimeout(2_000) { oldProjectionApply.await() },
        )
        assertEquals(
            ReadStateV1GenerationResult.Committed,
            projectionEngine.applySnapshot(newProjection, candidate(ReadStateV1FactSource.Hydrate, 2, 20)),
        )
        assertEquals(listOf(newProjection), projected)

        val rebaselineAuthority = ReadStateV1GrantAuthority()
        val oldRebaseline = rebaselineAuthority.issue(scope)
        val restoreStore = BlockingCorruptRestoreStore()
        val requested = mutableListOf<ReadStateV1GrantWitness>()
        val rebaselineEngine = engine(
            rebaselineAuthority,
            restoreStore,
            rebaseline = { observedWitness -> requested += observedWitness },
        )
        val oldRestore = async(Dispatchers.Default) { rebaselineEngine.restore(oldRebaseline) }
        withTimeout(2_000) { restoreStore.restoreEntered.await() }
        val newRebaseline = rebaselineAuthority.issue(scope)
        restoreStore.release.complete(Unit)
        assertEquals(ReadStateV1GenerationResult.RejectedCurrentGrant, withTimeout(2_000) { oldRestore.await() })
        assertEquals(
            ReadStateV1GenerationResult.BaselineRequired(true),
            rebaselineEngine.applyIncremental(
                newRebaseline,
                candidate(ReadStateV1FactSource.OwnerRealtime, 2, 20),
            ),
        )
        assertEquals(listOf(newRebaseline), requested)
    }

    @Test
    fun concurrentIssueRevokeAndCommitTerminatesWithAtMostOneActiveGeneration() = runBlocking {
        val authority = ReadStateV1GrantAuthority()
        val counterLock = SynchronizedObject()
        var active = 0
        var maxActive = 0
        val engine = engine(
            authority,
            AcceptingStore(),
            projection = { _, _ ->
                synchronized(counterLock) {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                }
                runBlocking { delay(1) }
                synchronized(counterLock) { active -= 1 }
            },
        )

        withTimeout(2_000) {
            coroutineScope {
                (1L..40L).map { version ->
                    async(Dispatchers.Default) {
                        val witness = authority.issue(scope)
                        val apply = async(Dispatchers.Default) {
                            engine.applySnapshot(
                                witness,
                                candidate(ReadStateV1FactSource.Hydrate, version, version),
                            )
                        }
                        val revoke = async(Dispatchers.Default) { engine.revoke(witness) }
                        awaitAll(apply, revoke)
                    }
                }.awaitAll()
            }
        }

        assertTrue(maxActive <= 1, "same-scope publications must have at most one active generation")
        assertEquals(0, active)
    }

    private fun engine(
        authority: ReadStateV1GrantAuthority,
        store: ReadStateV1Store,
        projection: (ReadStateV1GrantWitness, ReadStateV1Fact) -> Unit = { _, _ -> },
        rebaseline: (ReadStateV1GrantWitness) -> Unit = {},
    ) = ReadStateV1GenerationEngine(
        authority = authority,
        store = store,
        projectionObserver = ReadStateV1ProjectionObserver(projection),
        rebaselineRequester = ReadStateV1RebaselineRequester(rebaseline),
    )

    private fun candidate(source: ReadStateV1FactSource, version: Long, seq: Long) =
        ReadStateV1WireCandidate(scope.channelId, version, seq, source)

    private open class AcceptingStore : ReadStateV1Store {
        override suspend fun commit(
            scope: ReadStateV1Scope,
            fact: ReadStateV1Fact,
            mode: ReadStateV1CommitMode,
            guard: ReadStateV1CommitGuard,
        ): ReadStateV1StoreResult = ReadStateV1StoreResult.Accepted

        override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? = null
    }

    private class BlockingCommitStore : AcceptingStore() {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun commit(
            scope: ReadStateV1Scope,
            fact: ReadStateV1Fact,
            mode: ReadStateV1CommitMode,
            guard: ReadStateV1CommitGuard,
        ): ReadStateV1StoreResult {
            committed.complete(Unit)
            release.await()
            return ReadStateV1StoreResult.Accepted
        }
    }

    private class BlockingCorruptRestoreStore : AcceptingStore() {
        val restoreEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? {
            restoreEntered.complete(Unit)
            release.await()
            throw ReadStateV1StoredFactCorrupt(scope)
        }
    }
}
