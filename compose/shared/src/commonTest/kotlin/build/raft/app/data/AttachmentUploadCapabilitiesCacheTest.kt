package build.raft.app.data

import build.raft.app.runPortableCoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AttachmentUploadCapabilitiesCacheTest {
    private var now = 1_000L
    private val ttl = 60_000L
    private val cache = AttachmentUploadCapabilitiesCache(nowMillis = { now }, ttlMillis = ttl)
    private val authority = RaftExpectedAuthBinding("a", "t", "r", "server-1", "env")
    private val caps = AttachmentUploadCapabilities(maxBytes = 50L, directUploadEnabled = true)

    @Test
    fun peekNeverFetchesAndIsNullWhenCold() {
        assertNull(cache.peek(authority))
    }

    @Test
    fun getOrFetchCachesUntilTtlThenRefetches() = runPortableCoroutineTest {
        var calls = 0
        val fetch: suspend () -> DataResult<AttachmentUploadCapabilities> = {
            calls++
            DataResult.Content(caps)
        }
        cache.getOrFetch(authority, fetch)
        now += ttl - 1
        assertEquals(caps, cache.peek(authority))
        cache.getOrFetch(authority, fetch)
        assertEquals(1, calls)
        now += 1
        assertNull(cache.peek(authority))
        cache.getOrFetch(authority, fetch)
        assertEquals(2, calls)
    }

    @Test
    fun errorsAreNotCached() = runPortableCoroutineTest {
        var calls = 0
        val failing: suspend () -> DataResult<AttachmentUploadCapabilities> = {
            calls++
            DataResult.Error(build.raft.app.data.ScreenError(code = "timeout", retryable = true))
        }
        assertIs<DataResult.Error>(cache.getOrFetch(authority, failing))
        assertIs<DataResult.Error>(cache.getOrFetch(authority, failing))
        assertEquals(2, calls)
        assertNull(cache.peek(authority))
    }

    @Test
    fun otherServerDoesNotSeeCachedValue() = runPortableCoroutineTest {
        cache.getOrFetch(authority) { DataResult.Content(caps) }
        assertNull(cache.peek(authority.copy(serverId = "server-2")))
    }

    @Test
    fun peekIsAvailableWhileFetchIsStillInFlightAndConcurrentCallersShareOneRequest() =
        runPortableCoroutineTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val fetch: suspend () -> DataResult<AttachmentUploadCapabilities> = {
                calls++
                gate.await()
                DataResult.Content(caps)
            }
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { cache.getOrFetch(authority, fetch) }
                val second = async(start = CoroutineStart.UNDISPATCHED) { cache.getOrFetch(authority, fetch) }
                yield()
                // Slow network: nothing has completed, and the pick path (peek) does not block.
                assertNull(cache.peek(authority))
                assertEquals(1, calls)
                gate.complete(Unit)
                assertEquals(DataResult.Content(caps), first.await())
                assertEquals(DataResult.Content(caps), second.await())
            }
            assertEquals(1, calls)
        }
}
