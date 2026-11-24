package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RaftInstalledAppOriginsCacheTest {
    private val scopeA = RaftInstalledAppScope(accountId = "acct-1", environmentKey = "prod", serverId = "srv-1")
    private val scopeAServer2 = RaftInstalledAppScope(accountId = "acct-1", environmentKey = "prod", serverId = "srv-2")
    private val scopeAccount2 = RaftInstalledAppScope(accountId = "acct-2", environmentKey = "prod", serverId = "srv-1")
    private val scopeStaging = RaftInstalledAppScope(accountId = "acct-1", environmentKey = "staging", serverId = "srv-1")

    private val stampMatch =
        RaftInstalledAppOriginMatch(
            origin = "https://stamp.build",
            appName = "Stamp",
            logoUrl = "https://api.raft.build/logos/stamp.webp",
        )

    @Test
    fun warmAndLookupSyncPromoteInstalledOriginAndRejectUninstalled() = runTest {
        var fetches = 0
        val cache =
            RaftInstalledAppOriginsCache(
                fetchOrigins = { serverId ->
                    fetches += 1
                    assertEquals("srv-1", serverId)
                    RaftInstalledAppOriginsFetchResult(
                        origins = mapOf("https://stamp.build" to stampMatch),
                        complete = true,
                    )
                },
                scope = backgroundScope,
            )

        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeA, "https://stamp.build"))
        cache.warm(scopeA)
        runCurrent()

        assertEquals(1, fetches)
        assertEquals(
            RaftInstalledAppSyncLookup.Matched(stampMatch, "srv-1"),
            cache.lookupSync(scopeA, "https://stamp.build"),
        )
        assertEquals(
            RaftInstalledAppSyncLookup.KnownNotInstalled,
            cache.lookupSync(scopeA, "https://github.com"),
        )
        // Warm while fresh does not refetch.
        cache.warm(scopeA)
        runCurrent()
        assertEquals(1, fetches)
    }

    @Test
    fun coalescesConcurrentWarmAndLookupRequestsPerServer() = runTest {
        var fetches = 0
        val gate = CompletableDeferred<RaftInstalledAppOriginsFetchResult?>()
        val cache =
            RaftInstalledAppOriginsCache(
                fetchOrigins = {
                    fetches += 1
                    gate.await()
                },
                scope = backgroundScope,
            )

        cache.warm(scopeA)
        val firstLookup = async { cache.lookup(scopeA, "https://stamp.build") }
        val secondLookup = async { cache.lookup(scopeA, "https://other.example") }
        runCurrent()
        assertEquals(1, fetches)

        gate.complete(
            RaftInstalledAppOriginsFetchResult(
                origins = mapOf("https://stamp.build" to stampMatch),
                complete = true,
            ),
        )
        assertEquals(stampMatch, firstLookup.await())
        assertNull(secondLookup.await())
        assertEquals(1, fetches)
    }

    @Test
    fun accountOrEnvironmentSwitchClearsCacheAndInvalidatesInFlightFetch() = runTest {
        var fetches = 0
        val firstGate = CompletableDeferred<RaftInstalledAppOriginsFetchResult?>()
        val secondGate = CompletableDeferred<RaftInstalledAppOriginsFetchResult?>()
        val cache =
            RaftInstalledAppOriginsCache(
                fetchOrigins = {
                    fetches += 1
                    when (fetches) {
                        1 -> firstGate.await()
                        2 -> secondGate.await()
                        else ->
                            RaftInstalledAppOriginsFetchResult(
                                origins = mapOf("https://stamp.build" to stampMatch),
                                complete = true,
                            )
                    }
                },
                scope = backgroundScope,
            )

        // Populate scopeA after first fetch finishes.
        cache.warm(scopeA)
        runCurrent()
        firstGate.complete(
            RaftInstalledAppOriginsFetchResult(
                origins = mapOf("https://stamp.build" to stampMatch),
                complete = true,
            ),
        )
        runCurrent()
        assertIs<RaftInstalledAppSyncLookup.Matched>(cache.lookupSync(scopeA, "https://stamp.build"))

        // Switching account clears cached entries immediately.
        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeAccount2, "https://stamp.build"))
        // Switching back to original account also sees empty cache (previous scope was wiped).
        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeA, "https://stamp.build"))

        // Start an in-flight fetch for scopeA, switch environment before it completes, then complete it:
        // stale fetch must never pollute the cache.
        cache.warm(scopeA)
        runCurrent()
        cache.onAccountOrEnvironmentChanged(accountId = "acct-1", environmentKey = "staging")
        secondGate.complete(
            RaftInstalledAppOriginsFetchResult(
                origins = mapOf("https://stamp.build" to stampMatch),
                complete = true,
            ),
        )
        runCurrent()
        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeStaging, "https://stamp.build"))
        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeA, "https://stamp.build"))
    }

    @Test
    fun dropServerInvalidatesOnlyTargetServer() = runTest {
        val cache =
            RaftInstalledAppOriginsCache(
                fetchOrigins = { serverId ->
                    RaftInstalledAppOriginsFetchResult(
                        origins =
                            if (serverId == "srv-1") {
                                mapOf("https://stamp.build" to stampMatch)
                            } else {
                                mapOf("https://linear.example" to RaftInstalledAppOriginMatch("https://linear.example", "Linear"))
                            },
                        complete = true,
                    )
                },
                scope = backgroundScope,
            )

        cache.warm(scopeA)
        cache.warm(scopeAServer2)
        runCurrent()

        cache.dropServer("srv-1")
        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeA, "https://stamp.build"))
        assertIs<RaftInstalledAppSyncLookup.Matched>(cache.lookupSync(scopeAServer2, "https://linear.example"))
    }

    @Test
    fun partialEndpointFailureReturnsMatchWithoutPoisoningNegativeCache() = runTest {
        var fetches = 0
        val cache =
            RaftInstalledAppOriginsCache(
                fetchOrigins = {
                    fetches += 1
                    if (fetches == 1) {
                        RaftInstalledAppOriginsFetchResult(
                            origins = mapOf("https://stamp.build" to stampMatch),
                            complete = false,
                        )
                    } else {
                        RaftInstalledAppOriginsFetchResult(
                            origins = mapOf("https://stamp.build" to stampMatch),
                            complete = true,
                        )
                    }
                },
                scope = backgroundScope,
            )

        // Incomplete fetch still returns verified match for this lookup, but does NOT cache it.
        assertEquals(stampMatch, cache.lookup(scopeA, "https://stamp.build"))
        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeA, "https://other.example"))

        // Subsequent complete fetch populates the cache.
        assertEquals(stampMatch, cache.lookup(scopeA, "https://stamp.build"))
        assertEquals(2, fetches)
        assertEquals(RaftInstalledAppSyncLookup.KnownNotInstalled, cache.lookupSync(scopeA, "https://other.example"))
    }

    @Test
    fun ttlExpiryRefetchesInstalledAppOrigins() = runTest {
        var now = 1_000L
        var fetches = 0
        val cache =
            RaftInstalledAppOriginsCache(
                fetchOrigins = {
                    fetches += 1
                    RaftInstalledAppOriginsFetchResult(
                        origins = mapOf("https://stamp.build" to stampMatch),
                        complete = true,
                    )
                },
                scope = backgroundScope,
                ttlMillis = 60_000L,
                nowMillis = { now },
            )

        cache.warm(scopeA)
        runCurrent()
        assertEquals(1, fetches)
        assertIs<RaftInstalledAppSyncLookup.Matched>(cache.lookupSync(scopeA, "https://stamp.build"))

        now += 60_000L
        assertEquals(RaftInstalledAppSyncLookup.NotLoaded, cache.lookupSync(scopeA, "https://stamp.build"))
        assertEquals(stampMatch, cache.lookup(scopeA, "https://stamp.build"))
        assertEquals(2, fetches)
    }

    @Test
    fun lookupTimesOutWithoutCancellingSharedWarmFetch() = runTest {
        val gate = CompletableDeferred<RaftInstalledAppOriginsFetchResult?>()
        var fetches = 0
        val cache =
            RaftInstalledAppOriginsCache(
                fetchOrigins = {
                    fetches += 1
                    gate.await()
                },
                scope = backgroundScope,
                defaultLookupTimeoutMillis = 500L,
            )

        val timedOut = async { cache.lookup(scopeA, "https://stamp.build") }
        advanceTimeBy(600L)
        runCurrent()
        assertNull(timedOut.await())

        // Shared warm fetch still completes in background so next tap is a 0ms hit.
        gate.complete(
            RaftInstalledAppOriginsFetchResult(
                origins = mapOf("https://stamp.build" to stampMatch),
                complete = true,
            ),
        )
        runCurrent()
        assertEquals(1, fetches)
        assertEquals(
            RaftInstalledAppSyncLookup.Matched(stampMatch, "srv-1"),
            cache.lookupSync(scopeA, "https://stamp.build"),
        )
    }

    @Test
    fun pendingLookupGateCancelsSupersededTapAndDropsStaleCompletion() = runTest {
        val gate = RaftPendingInstalledAppLookupGate()
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        val results = mutableListOf<String>()

        gate.launchLatest(
            scope = backgroundScope,
            load = { first.await() },
            onComplete = { results += "first:$it" },
        )
        runCurrent()
        gate.launchLatest(
            scope = backgroundScope,
            load = { second.await() },
            onComplete = { results += "second:$it" },
        )
        runCurrent()

        first.complete("stale")
        second.complete("fresh")
        runCurrent()

        assertEquals(listOf("second:fresh"), results)
    }
}
