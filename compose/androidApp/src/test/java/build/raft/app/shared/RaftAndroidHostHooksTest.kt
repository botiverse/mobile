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
                                    // The request is cancelled while the lease is being acquired.
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
}
