package build.raft.app.image

import build.raft.app.network.SharedDownloadedBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Hands 50a29f61: a cancelled image waiter must not throw away the bytes its shared fetch got. */
class RaftImageByteFlightCacheTest {
    @Test
    fun aWaiterCancelledMidFetchLeavesTheBytesForTheNextLoad() = runTest {
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val bytes = SharedDownloadedBytes(ByteArray(8) { 1 }, "image/webp")
        val logs = mutableListOf<String>()
        var now = 0L
        val cache =
            RaftImageByteFlightCache(
                fetcher = { fetches += 1; release.await(); bytes },
                logger = { logs += it },
                nowMillis = { now },
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            )

        val firstWaiter = async { cache.fetch("k", "https://cdn.example/a.webp") }
        testScheduler.advanceUntilIdle()
        firstWaiter.cancel(CancellationException("The coroutine scope left the composition"))
        assertFailsWith<CancellationException> { firstWaiter.await() }

        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        now += 5_000L
        val second = cache.fetch("k", "https://cdn.example/a.webp")

        assertSame(bytes, second)
        assertEquals(1, fetches)
        assertTrue(logs.single().startsWith("source=network status=cancelled"), logs.toString())

        // After the reuse window the image is fetched again.
        now += 31_000L
        cache.fetch("k", "https://cdn.example/a.webp")
        assertEquals(2, fetches)
    }

    @Test
    fun aFailedFetchIsNotReused() = runTest {
        var fetches = 0
        val cache =
            RaftImageByteFlightCache(
                fetcher = { fetches += 1; error("HTTP 500") },
                logger = {},
                nowMillis = { 0L },
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            )

        repeat(2) { assertFailsWith<IllegalStateException> { cache.fetch("k", "https://cdn.example/a.webp") } }
        assertEquals(2, fetches)
    }
}
