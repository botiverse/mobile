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
            val jobRef = CompletableDeferred<Job>()
            val job =
                launch(start = CoroutineStart.LAZY) {
                    val result =
                        runCatching {
                            raftOpenRenderLease(
                                io,
                                open = {
                                    // Covers the FAST path: `open` returns synchronously and the
                                    // cancel is issued from inside it, so `withContext` may return
                                    // before the cancellation is observed. `ensureActive()` in
                                    // `raftOpenRenderLease` is what makes this deterministic --
                                    // without it the lease would be handed to a cancelled caller.
                                    runBlocking { jobRef.await() }.cancel()
                                    lease
                                },
                                close = { it.closes.incrementAndGet() },
                            )
                        }
                    failure.complete(result.exceptionOrNull())
                }
            jobRef.complete(job)
            job.start()
            job.join()
            assertTrue(failure.await() is CancellationException)
            assertEquals(1, lease.closes.get())
        } finally {
            io.close()
        }
    }

    @Test
    fun leaseIsClosedWhenTheCallerIsCancelledWhileOpenIsSuspended() = runBlocking {
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val lease = Lease()
            val failure = CompletableDeferred<Throwable?>()
            // Covers the SLOW path: the cancel is observed only after `open` is already
            // suspended, so `withContext(NonCancellable)` still completes and the boundary
            // check inside it has nothing to report. This case cannot flake because the
            // test waits for `entered` before cancelling and for `cancelled` before `open`
            // returns; it stays green even if `ensureActive()` is removed.
            val entered = CompletableDeferred<Job>()
            val cancelled = CompletableDeferred<Unit>()
            val job =
                launch(start = CoroutineStart.LAZY) {
                    val result =
                        runCatching {
                            raftOpenRenderLease(
                                io,
                                open = {
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
