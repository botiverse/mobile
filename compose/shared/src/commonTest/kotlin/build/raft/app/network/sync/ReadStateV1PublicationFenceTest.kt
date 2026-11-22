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
import kotlin.test.assertTrue

class ReadStateV1PublicationFenceTest {
    private val scope = ReadStateV1Scope("server-1", "user-1", "channel-1")

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
}
