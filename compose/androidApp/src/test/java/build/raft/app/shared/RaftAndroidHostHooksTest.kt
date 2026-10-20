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
                                    // Covers the SUSPENDED path, not the fast one: the
                                    // `runBlocking` below blocks the io thread until the test
                                    // completes `jobRef`, so the caller genuinely suspends and
                                    // `withContext` dispatches back with the cancellation already
                                    // observed. Removing `ensureActive()` does NOT fail this test.
                                    // Kept because the catch branch it exercises is what the
                                    // original flake tripped. The fast path is covered by
                                    // leaseIsClosedWhenTheCallerIsCancelledBeforeTheHandOver.
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
    fun leaseIsClosedWhenTheCallerIsCancelledBeforeTheHandOver() = runBlocking {
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val lease = Lease()
            val failure = CompletableDeferred<Throwable?>()
            // Covers the FAST path: `open` returns synchronously and the caller never
            // suspends, so `withContext` has no reason to check cancellation and returns
            // normally. The cancel is issued from `beforeHandOver`, which runs in the
            // window after that return and before the hand-over -- the one place the
            // un-cancelled caller could otherwise still be handed a live lease.
            // Deterministic by construction: the cancel point is pinned by the hook, not
            // by scheduling. Removing `ensureActive()` makes this fail with closes == 0.
            val jobRef = CompletableDeferred<Job>()
            val job =
                launch(start = CoroutineStart.LAZY) {
                    val result =
                        runCatching {
                            raftOpenRenderLease(
                                io,
                                open = { lease },
                                close = { it.closes.incrementAndGet() },
                                beforeHandOver = {
                                    // Runs after `open` returned and before the hand-over.
                                    runBlocking { jobRef.await() }.cancel()
                                },
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
            // Also a suspended path, made explicit: the test waits for `entered` before
            // cancelling and for `cancelled` before `open` returns, so it cannot flake.
            // It stays green if `ensureActive()` is removed. The fast path that check
            // exists for is covered by leaseIsClosedWhenTheCallerIsCancelledBeforeTheHandOver.
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
