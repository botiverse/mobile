package build.raft.app.shared

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAndroidHostHooksTest {
    private class Lease {
        val closes = AtomicInteger()
    }

    @Test
    fun leaseIsReturnedToAnActiveCaller() = runBlocking {
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val lease = Lease()
            val opened = raftOpenRenderLease(io, open = { lease }, close = { it.closes.incrementAndGet() })
            assertSame(lease, opened)
            assertEquals(0, lease.closes.get())
        } finally {
            io.close()
        }
    }

    @Test
    fun leaseIsClosedWhenTheCallerIsCancelledWhileOpening() = runBlocking {
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val lease = Lease()
            val failure = CompletableDeferred<Throwable?>()
            // `entered` lets the test cancel only after `open` is already running, and
            // `cancelled` lets it wait for that cancellation to be observed. Without both
            // the cancel could land after the lease was handed over, the call would return
            // normally, and `failure` would be null -- a scheduling-dependent flake.
            val entered = CompletableDeferred<Job>()
            val cancelled = CompletableDeferred<Unit>()
            val job =
                launch(start = CoroutineStart.LAZY) {
                    val result =
                        runCatching {
                            raftOpenRenderLease(
                                io,
                                open = {
                                    // The request is cancelled while the lease is being acquired.
                                    entered.complete(coroutineContext[Job]!!)
                                    runBlocking { cancelled.await() }
                                    lease
                                },
                                close = { it.closes.incrementAndGet() },
                            )
                        }
                    failure.complete(result.exceptionOrNull())
                }
            job.start()
            entered.await().cancel()
            cancelled.complete(Unit)
            job.join()
            assertTrue(failure.await() is CancellationException)
            assertEquals(1, lease.closes.get())
        } finally {
            io.close()
        }
    }
}
