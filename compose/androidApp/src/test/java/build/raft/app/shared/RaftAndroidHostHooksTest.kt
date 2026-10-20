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
                                    // Covers the SUSPENDED path, not the fast one. The
                                    // `runBlocking` below blocks the io thread until the test
                                    // completes `jobRef`, so the caller genuinely suspends and
                                    // `withContext` dispatches back with the cancellation observed.
                                    // Removing `ensureActive()` therefore does NOT fail this test --
                                    // it still exercises the catch branch, which is a different
                                    // contract. Kept because it pins that branch against the
                                    // original flake; see the note on coverage in #3249.
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
            // Also a SUSPENDED path, made explicit: the test waits for `entered` before
            // cancelling and for `cancelled` before `open` returns, so it cannot flake.
            // Like the case above it stays green if `ensureActive()` is removed.
            //
            // NOT COVERED ANYWHERE: the fast path `ensureActive()` exists for -- `open`
            // returning without the caller ever suspending, where `withContext` neither
            // dispatches nor checks cancellation. No test here reaches it; removing
            // `ensureActive()` turns nothing red. Its necessity rests on DispatchedCoroutine
            // semantics, not on a regression test.
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
