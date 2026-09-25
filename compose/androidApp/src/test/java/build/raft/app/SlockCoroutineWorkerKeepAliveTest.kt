package build.raft.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * task #312: the coroutine keep-alive property must be pinned early enough that the
 * kotlinx.coroutines DefaultScheduler never enters tryTerminateWorker, making the
 * setName-inside-synchronized(workers) SuspendThreadByPeer deadlock path unreachable.
 *
 * These tests intentionally reflect into the library's internal `TasksKt` to prove the
 * scheduler actually consumed the pinned value — not just that we set a property. If a
 * future coroutines upgrade renames or moves the field, they fail loudly as a canary.
 * `IDLE_WORKER_KEEP_ALIVE_NS` is read once at `TasksKt` class init, so this class must run
 * before any other coroutine use in its JVM; run it in its own forked JVM (forkEvery) or
 * first in the suite.
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

    @Test
    fun schedulerConsumesThePinnedKeepAlive() {
        installCoroutineWorkerKeepAlive()

        // Read the internal field the DefaultScheduler actually uses. Loading TasksKt reads
        // the system property once, so this must happen before any Dispatchers/Default use.
        val tasksClass = Class.forName("kotlinx.coroutines.scheduling.TasksKt")
        val field = tasksClass.getDeclaredField("IDLE_WORKER_KEEP_ALIVE_NS")
        field.isAccessible = true
        val keepAliveNs = field.getLong(null)

        assertEquals(
            TimeUnit.SECONDS.toNanos(COROUTINE_WORKER_KEEP_ALIVE_SEC.toLong()),
            keepAliveNs
        )
    }
}
