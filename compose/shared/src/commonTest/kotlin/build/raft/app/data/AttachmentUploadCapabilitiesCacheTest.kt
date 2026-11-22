package build.raft.app.data

import build.raft.app.runPortableCoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentUploadCapabilitiesCacheTest {
    private var now = 1_000L
    private val ttl = 60_000L
    private val cache = AttachmentUploadCapabilitiesCache(nowMillis = { now }, ttlMillis = ttl)
    private val authority = RaftExpectedAuthBinding("a", "t", "r", "server-1", "env")
    private val caps = AttachmentUploadCapabilities(maxBytes = 50L, directUploadEnabled = true)

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

    /**
     * web slock #15743: a probe that runs out of time no longer queues the later uploads behind it.
     * Three uploads picked together wait on one probe that never answers; the network ends it at a
     * control call's 30 s, and all three take that answer then (and fall back to multipart), not one
     * after another 30 s apart, each sending the probe again. The failure is not kept: the next
     * upload asks afresh.
     */
    @Test
    fun uploadsWaitingOnAProbeThatRunsOutOfTimeTakeItsAnswerTogetherAndTheNextOneAsksAfresh() = runTest {
        val controlCallBudgetMillis = 30_000L
        val timedOut = DataResult.Error(ScreenError(code = "network_timeout", retryable = true))
        var networkAnswers = false
        var calls = 0
        val fetch: suspend () -> DataResult<AttachmentUploadCapabilities> = {
            calls += 1
            if (networkAnswers) {
                DataResult.Content(caps)
            } else {
                // No answer: the network ends every probe sent now at a control call's budget.
                delay(controlCallBudgetMillis)
                timedOut
            }
        }

        val waiting = List(3) { async { cache.getOrFetch(authority, fetch) } }
        runCurrent()
        assertEquals(1, calls, "one probe on the wire for the three uploads")
        advanceTimeBy(controlCallBudgetMillis - 1)
        runCurrent()
        assertTrue(waiting.none { it.isCompleted }, "still waiting on the probe")
        advanceTimeBy(1)
        runCurrent()

        assertTrue(waiting.all { it.isCompleted }, "all three are answered when the probe ends")
        waiting.forEach { assertSame(timedOut, it.await()) }
        assertEquals(controlCallBudgetMillis, currentTime)
        assertEquals(1, calls)
        assertNull(cache.peek(authority))
        networkAnswers = true
        assertEquals(DataResult.Content(caps), cache.getOrFetch(authority, fetch))
        assertEquals(2, calls)
    }

    @Test
    fun aCancelledProbeLeavesTheUploadsWaitingOnItToAskThemselves() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val fetch: suspend () -> DataResult<AttachmentUploadCapabilities> = {
            calls += 1
            // The first caller's probe never answers before that caller goes away.
            if (calls == 1) awaitCancellation()
            gate.await()
            DataResult.Content(caps)
        }

        val fetcher = launch { cache.getOrFetch(authority, fetch) }
        runCurrent()
        val waiter = async { cache.getOrFetch(authority, fetch) }
        runCurrent()
        assertEquals(1, calls)

        fetcher.cancel()
        runCurrent()
        assertEquals(2, calls, "the waiter asked itself instead of taking the cancellation")
        assertTrue(waiter.isActive)
        gate.complete(Unit)
        assertEquals(DataResult.Content(caps), waiter.await())
        assertEquals(caps, cache.peek(authority))
    }
}
