package build.raft.app

import build.raft.app.data.AuthSessionClearReason
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.AuthSessionTerminalSnapshot
import build.raft.app.data.RaftAuthSessionTerminalSource
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.authSessionFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthSessionTerminalRouteEffectTest {
    @Test
    fun clearDuringSubscriptionReconcileWindowTerminalizesExactlyOnce() = runPortableCoroutineTest {
        val source = FakeTerminalSource(1, authSessionFixture())
        val snapshotEntered = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val generationTwoSeen = CompletableDeferred<Unit>()
        val sentinelSeen = CompletableDeferred<Unit>()
        source.beforeSnapshot = {
            snapshotEntered.complete(Unit)
            releaseSnapshot.await()
        }
        val signals = mutableListOf<AuthSessionTerminalRouteSignal>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            collectAuthSessionTerminalSignals(source) {
                signals += it
                when (it.generation) {
                    2L -> generationTwoSeen.complete(Unit)
                    3L -> sentinelSeen.complete(Unit)
                }
            }
        }

        snapshotEntered.await()
        source.clearAndEmit(2, AuthSessionClearReason.AuthLoss)
        releaseSnapshot.complete(Unit)
        generationTwoSeen.await()
        source.clearAndEmit(3, AuthSessionClearReason.AuthLoss)
        sentinelSeen.await()

        assertEquals(listOf(2L, 3L), signals.map { it.generation })
        collector.cancelAndJoin()
    }

    @Test
    fun successorLoginBetweenSnapshotAndConditionalCommitSuppressesTerminalAction() = runPortableCoroutineTest {
        val source = FakeTerminalSource(4, null)
        val commitReturned = CompletableDeferred<Unit>()
        source.beforeCommit = {
            source.login(5, authSessionFixture(userId = "successor"))
        }
        source.afterConditionalCommit = { commitReturned.complete(Unit) }
        val signals = mutableListOf<AuthSessionTerminalRouteSignal>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            collectAuthSessionTerminalSignals(source) { signals += it }
        }
        commitReturned.await()

        assertTrue(signals.isEmpty())
        collector.cancelAndJoin()
    }
}

private class FakeTerminalSource(
    generation: Long,
    session: StoredAuthSession?,
) : RaftAuthSessionTerminalSource {
    private val mutex = Mutex()
    private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
    private var generation = generation
    private var session = session

    var beforeSnapshot: suspend () -> Unit = {}
    var beforeCommit: suspend () -> Unit = {}
    var afterConditionalCommit: suspend () -> Unit = {}

    override val authSessionTerminalEvents: SharedFlow<AuthSessionEvent> = events

    override suspend fun readAuthSessionTerminalSnapshot(): AuthSessionTerminalSnapshot {
        beforeSnapshot()
        return mutex.withLock { AuthSessionTerminalSnapshot(generation, session) }
    }

    override suspend fun runIfAuthSessionTerminalRevision(
        expectedGeneration: Long,
        action: () -> Unit,
    ): Boolean {
        beforeCommit()
        val committed = mutex.withLock {
            if (generation != expectedGeneration || session != null) return@withLock false
            action()
            true
        }
        afterConditionalCommit()
        return committed
    }

    suspend fun clearAndEmit(generation: Long, reason: AuthSessionClearReason) {
        mutex.withLock {
            this.generation = generation
            session = null
        }
        events.emit(AuthSessionEvent.Cleared(generation, reason))
    }

    suspend fun login(generation: Long, session: StoredAuthSession) {
        mutex.withLock {
            this.generation = generation
            this.session = session
        }
        events.emit(AuthSessionEvent.Authenticated(generation))
    }
}
