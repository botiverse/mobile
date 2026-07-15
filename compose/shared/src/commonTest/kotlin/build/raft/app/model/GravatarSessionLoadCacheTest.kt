package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GravatarSessionLoadCacheTest {
    @Test
    fun failedHashIsSuppressedOnlyDuringCooldown() {
        var nowMillis = 1_000L
        val cache =
            GravatarSessionLoadCache(
                maxEntries = 2,
                failureCooldownMillis = 500L,
                nowMillis = { nowMillis }
            )
        val hash = "A".repeat(64)

        assertEquals(0L, cache.retryDelayMillis(hash))
        cache.recordFailure(hash)
        assertEquals(500L, cache.retryDelayMillis(hash.lowercase()))

        nowMillis += 499L
        assertEquals(1L, cache.retryDelayMillis(hash))
        nowMillis += 1L
        assertEquals(0L, cache.retryDelayMillis(hash))
        assertEquals(0, cache.failureCount())
    }

    @Test
    fun successWinsOverLateConcurrentFailure() {
        val cache =
            GravatarSessionLoadCache(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val hash = "B".repeat(64)

        cache.recordFailure(hash)
        cache.recordSuccess(hash)
        cache.recordFailure(hash)

        assertEquals(0L, cache.retryDelayMillis(hash))
        assertEquals(0, cache.failureCount())
    }

    @Test
    fun failureCooldownCacheIsBoundedAndRefreshesRecentFailures() {
        val cache =
            GravatarSessionLoadCache(
                maxEntries = 2,
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val first = "1".repeat(64)
        val second = "2".repeat(64)
        val third = "3".repeat(64)

        cache.recordFailure(first)
        cache.recordFailure(second)
        cache.recordFailure(first)
        cache.recordFailure(third)

        assertEquals(2, cache.failureCount())
        assertEquals(500L, cache.retryDelayMillis(first))
        assertEquals(0L, cache.retryDelayMillis(second))
        assertEquals(500L, cache.retryDelayMillis(third))
    }

    @Test
    fun invalidHashesAreIgnored() {
        val cache = GravatarSessionLoadCache()

        cache.recordFailure("not-a-hash")
        cache.recordSuccess("not-a-hash")

        assertEquals(0, cache.failureCount())
        assertEquals(0L, cache.retryDelayMillis("not-a-hash"))
    }
}
