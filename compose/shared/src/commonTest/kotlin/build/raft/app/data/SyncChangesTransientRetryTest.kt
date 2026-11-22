package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one retry pull after a first transient failure (CTO, #proj-mobile:#112). */
class SyncChangesTransientRetryTest {
    private val preferences = SyncTestPreferences()
    private val cursors = RaftSyncCursorStore(preferences)
    private val fallbacks = mutableListOf<String>()

    /** What RaftSharedStoreProvider.requestSyncChangesPull does around a pull. */
    private fun CoroutineScope.request(
        engine: RaftSyncChangesEngine,
        retry: RaftSyncTransientRetry,
        trigger: String,
        transientRetry: Boolean = false,
    ) {
        if (!transientRetry) retry.onTriggerPull(syncTestScope)
        // The provider's long-lived scope (threadSendScope), not the finished pull's.
        val pulls = this
        pulls.launch {
            val result = engine.pull(syncTestScope, trigger)
            if (result is RaftSyncPullResult.Failed && result.needsFullRefetchFallback) fallbacks += trigger
            retry.onPullFinished(syncTestScope, result, wasRetry = transientRetry) {
                pulls.request(engine, retry, "transient_retry", transientRetry = true)
            }
        }
    }

    private fun engine(api: RaftSyncChangesApi) =
        RaftSyncChangesEngine(api = api, cursors = cursors, sink = RecordingSyncChangesSink())

    @Test
    fun `a first transient failure schedules one retry and its second failure forgets the cursor and falls back`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api = ScriptedSyncChangesApi(RaftSyncChangesFetch.Failed("http_503"), RaftSyncChangesFetch.Failed("timeout"))
        val retry = RaftSyncTransientRetry(scope = backgroundScope, delayMillis = { 45_000L })

        request(engine(api), retry, "reconnect")
        runCurrent()
        assertEquals("c1.1.1", cursors.read(syncTestScope))
        assertTrue(fallbacks.isEmpty())
        assertTrue(retry.isPending(syncTestScope))

        advanceTimeBy(44_999L)
        runCurrent()
        assertEquals(1, api.sinces.size)

        advanceTimeBy(1L)
        runCurrent()
        advanceTimeBy(SYNC_CHANGES_REQUEST_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(listOf<String?>("c1.1.1", "c1.1.1"), api.sinces)
        assertNull(cursors.read(syncTestScope))
        assertEquals(listOf("transient_retry"), fallbacks)

        // One retry only: nothing else is asked (the scripted API would fail on a third request).
        advanceTimeBy(SYNC_CHANGES_TRANSIENT_RETRY_MAX_MILLIS + 1L)
        runCurrent()
        assertFalse(retry.isPending(syncTestScope))
        assertEquals(2, api.sinces.size)
    }

    @Test
    fun `the retry waits a random 30 to 60 seconds`() {
        val random = Random(11)
        val delays = List(500) { syncTransientRetryDelayMillis(random) }
        assertTrue(delays.all { it in 30_000L..60_000L })
        assertTrue(delays.any { it < 35_000L } && delays.any { it > 55_000L })
    }
}
