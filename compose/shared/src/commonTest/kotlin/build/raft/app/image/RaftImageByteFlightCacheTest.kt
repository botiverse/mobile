package build.raft.app.image

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Hands 50a29f61: a cancelled image waiter must not throw away the bytes its shared fetch got. */
class RaftImageByteFlightCacheTest {
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
