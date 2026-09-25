package build.raft.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task #312: the coroutine keep-alive property must be pinned early enough that the
 * kotlinx.coroutines DefaultScheduler never enters tryTerminateWorker, making the
 * setName-inside-synchronized(workers) SuspendThreadByPeer deadlock path unreachable.
 */
class SlockCoroutineWorkerKeepAliveTest {
    @After
    fun restoreProperty() {
        System.clearProperty(COROUTINE_WORKER_KEEP_ALIVE_PROPERTY)
    }

    @Test
    fun keepAlivePropertyIsPinnedBeforeCoroutinesInitialize() {
        installCoroutineWorkerKeepAlive()
        assertEquals(
            COROUTINE_WORKER_KEEP_ALIVE_SEC,
            System.getProperty(COROUTINE_WORKER_KEEP_ALIVE_PROPERTY)
        )
    }

    @Test
    fun keepAliveIsLargeEnoughToPreventWorkerTerminationWithoutOverflow() {
        val keepAliveSec = COROUTINE_WORKER_KEEP_ALIVE_SEC.toLong()
        // Well beyond any realistic idle window (default is 60s), so workers never terminate.
        assertTrue(keepAliveSec >= 86_400L)
        // nanoTime() + keepAliveNs must not overflow Long: keepAliveNs is seconds * 1e9, so
        // keepAliveSec must stay comfortably under Long.MAX_VALUE / 1e9 (~9.2e9).
        assertTrue(keepAliveSec < Long.MAX_VALUE / 1_000_000_000L)
    }
}
