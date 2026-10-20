package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the terminal side of the composer gallery handoff.
 *
 * The real consumer is cancelled when the preview page is disposed.  Its terminal registry and
 * cleanup-ledger transition therefore has to run in a NonCancellable context; otherwise a
 * cancellation that arrives while the upload is unwinding can leave a pending session (and its
 * cache refs) behind.  This test drives the same public terminal helper from a cancelled job and
 * verifies the observable ownership state after the job joins.
 */
class RaftFilePreviewGalleryTeardownTest {
    @Test
    fun cancelledGalleryConsumerFinishesRegistryAndDrainsCleanupLedger() = runBlocking {
        val registry = RaftFilePreviewEphemeralActionRegistry()
        val ledger =
            RaftFilePreviewEphemeralCleanupLedger(
                store = InMemoryRaftKeyValueStore()
            )
        val sessionId = "gallery-cancelled"
        val threadKey = "thread-1"
        val actionRefs =
            mapOf(
                "action-1" to "file:///cache/gallery-one.jpg",
                "action-2" to "file:///cache/gallery-two.jpg"
            )

        registry.register(
            sessionId = sessionId,
            threadKey = threadKey,
            actionIds = actionRefs.keys,
            ownedTemporaryFileRefs = actionRefs.values.toSet()
        )
        val prepared =
            requireNotNull(
                registry.prepareDeliveryBatch(
                    sessionId = sessionId,
                    threadKey = threadKey,
                    fileRefByActionId = actionRefs
                )
            )
        ledger.replace(sessionId, prepared.pendingFileRefs)
        assertEquals(actionRefs.values.toSet(), prepared.pendingFileRefs)

        // Hold the same kind of mutex used by the page's event-batch consumer.  The cancellation
        // is delivered while terminal cleanup is waiting for that lock; a cancellable `withLock`
        // would abort here and strand the pending session.  NonCancellable must let the waiter
        // finish once the in-flight batch releases the lock.
        val terminalMutex = Mutex()
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val lockHolder =
            launch(start = CoroutineStart.UNDISPATCHED) {
                terminalMutex.withLock {
                    lockEntered.complete(Unit)
                    releaseLock.await()
                }
            }
        lockEntered.await()

        val terminalCleanup = CompletableDeferred<Set<String>>()
        val consumer =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    // Disposal cancellation is already active here.  The ownership transition
                    // must nevertheless finish and be observable before the job is joined.
                    val cleanupRefs =
                        withContext(NonCancellable) {
                            terminalMutex.withLock {
                                raftFilePreviewFinishComposerImageEventSession(
                                    sessionId = sessionId,
                                    transferred = false,
                                    registry = registry,
                                    cleanupLedger = ledger
                                )
                            }
                        }
                    terminalCleanup.complete(cleanupRefs)
                }
            }

        consumer.cancel()
        // Let the canceled consumer reach the mutex wait before releasing the holder.  This keeps
        // the interleaving deterministic without wall-clock sleeps.
        yield()
        assertFalse(terminalCleanup.isCompleted)
        releaseLock.complete(Unit)
        consumer.cancelAndJoin()
        lockHolder.join()

        assertEquals(actionRefs.values.toSet(), terminalCleanup.await())
        assertFalse(registry.hasSession(sessionId))
        assertTrue(registry.activeSessionIds().isEmpty())
        assertTrue(ledger.take(sessionId).isEmpty())
    }
}
