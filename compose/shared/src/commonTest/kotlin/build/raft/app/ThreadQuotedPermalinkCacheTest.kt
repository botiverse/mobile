package build.raft.app

import build.raft.app.data.RaftQuotedPermalinkViewer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * #proj-mobile:#383: in-memory LRU cache for successfully loaded quoted message permalinks so
 * revisiting a conversation or scrolling rows back on screen renders immediately without a
 * "Loading message…" flash or duplicate `GET /api/messages/context/:id` request.
 */
class ThreadQuotedPermalinkCacheTest {
    private val viewerA1 = RaftQuotedPermalinkViewer(userId = "user-a", serverId = "server-1", plan = "pro")
    private val viewerA2 = RaftQuotedPermalinkViewer(userId = "user-a", serverId = "server-2", plan = "pro")
    private val viewerB1 = RaftQuotedPermalinkViewer(userId = "user-b", serverId = "server-1", plan = "pro")

    private fun quotedMessage(
        id: String = "msg-1",
        body: String = "Quoted content",
        retracted: Boolean = false,
        archived: Boolean = false,
    ): ThreadQuotedPermalinkMessage =
        ThreadQuotedPermalinkMessage(
            item =
                RaftThreadMessageItem(
                    id = id,
                    authorId = "author-1",
                    authorName = "Alice",
                    authorKind = "human",
                    timestampLabel = "2026-11-25T10:00:00Z",
                    body = body,
                    deletedAt = if (retracted) "2026-11-25T10:05:00Z" else null,
                ),
            channelArchived = archived,
        )

    @Test
    fun cacheHitServesSynchronouslyAndSkipsDuplicateNetworkFetch() = runTest {
        val timeSource = TestTimeSource()
        val cache = ThreadQuotedPermalinkCache(maxEntries = 10, ttl = 5.minutes, timeSource = timeSource)
        var fetchCalls = 0
        val expected = quotedMessage(id = "msg-1", body = "Ship it")

        assertNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-1"))

        val first =
            cache.getOrFetch(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-1",
            ) {
                fetchCalls += 1
                expected
            }
        assertEquals(expected, first)
        assertEquals(1, fetchCalls)

        // Synchronous peek returns immediately (zero loading state) and getOrFetch skips network.
        assertEquals(expected, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-1"))
        val second =
            cache.getOrFetch(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-1",
            ) {
                fetchCalls += 1
                quotedMessage(id = "msg-1", body = "Should not fetch")
            }
        assertEquals(expected, second)
        assertEquals(1, fetchCalls)

        // Retracted quoted message maps directly to Retracted UI state without loading flash.
        val retracted = quotedMessage(id = "msg-retracted", retracted = true)
        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-retracted", retracted)
        val peekedRetracted = assertNotNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-retracted"))
        assertEquals(ThreadQuotedPermalinkUiState.Retracted, peekedRetracted.toQuotedPermalinkUiState())
        assertIs<ThreadQuotedPermalinkUiState.Loaded>(expected.toQuotedPermalinkUiState())
    }

    @Test
    fun entriesAreIsolatedByViewerServerAndChannelAndNeverStoredWithoutScope() = runTest {
        val cache = ThreadQuotedPermalinkCache(maxEntries = 10, ttl = 5.minutes)
        val msgA1 = quotedMessage(id = "msg-1", body = "User A Server 1")
        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-1", msgA1)

        // Exact match hits.
        assertEquals(msgA1, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-1"))
        // Another server, another user, or another channel never sees User A Server 1's entry.
        assertNull(cache.peek(viewerA2.userId, viewerA2.serverId, "chan-1", "msg-1"))
        assertNull(cache.peek(viewerB1.userId, viewerB1.serverId, "chan-1", "msg-1"))
        assertNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-2", "msg-1"))

        // Missing or blank user/server/message ids fail closed and never store or read.
        cache.put(null, viewerA1.serverId, "chan-1", "msg-blank", msgA1)
        cache.put("  ", viewerA1.serverId, "chan-1", "msg-blank", msgA1)
        cache.put(viewerA1.userId, null, "chan-1", "msg-blank", msgA1)
        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", " ", msgA1)
        assertNull(cache.peek(null, viewerA1.serverId, "chan-1", "msg-blank"))
        assertNull(cache.peek(viewerA1.userId, null, "chan-1", "msg-blank"))
    }

    @Test
    fun lruEvictsLeastRecentlyUsedEntryAndPromotesOnPeek() {
        val cache = ThreadQuotedPermalinkCache(maxEntries = 3, ttl = 5.minutes)
        val m1 = quotedMessage("m1")
        val m2 = quotedMessage("m2")
        val m3 = quotedMessage("m3")
        val m4 = quotedMessage("m4")

        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", "m1", m1)
        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", "m2", m2)
        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", "m3", m3)

        // Touch m1 via peek so m2 becomes the least-recently-used entry.
        assertEquals(m1, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "m1"))

        // Inserting m4 evicts m2, keeping m1, m3, m4.
        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", "m4", m4)
        assertNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "m2"))
        assertEquals(m1, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "m1"))
        assertEquals(m3, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "m3"))
        assertEquals(m4, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "m4"))
    }

    @Test
    fun transientUnavailableFailuresAreNeverCachedSoRetriesSucceed() = runTest {
        val cache = ThreadQuotedPermalinkCache(maxEntries = 10, ttl = 5.minutes)
        var attempts = 0
        val recovered = quotedMessage("msg-retry", body = "Back online")

        val first =
            cache.getOrFetch(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-retry",
            ) {
                attempts += 1
                null // transient Unavailable failure
            }
        assertNull(first)
        assertEquals(1, attempts)
        assertNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-retry"))

        val second =
            cache.getOrFetch(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-retry",
            ) {
                attempts += 1
                recovered
            }
        assertEquals(recovered, second)
        assertEquals(2, attempts)
        assertEquals(recovered, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-retry"))
    }

    @Test
    fun ttlExpirationRefreshesEntryAndClearOrServerDropFencesInFlightFetches() = runTest {
        val timeSource = TestTimeSource()
        val cache = ThreadQuotedPermalinkCache(maxEntries = 10, ttl = 5.minutes, timeSource = timeSource)
        val initial = quotedMessage("msg-ttl", body = "Original")
        val updated = quotedMessage("msg-ttl", body = "Edited")
        var fetches = 0

        cache.put(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-ttl", initial)
        timeSource += 4.minutes + 59.seconds
        assertEquals(initial, cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-ttl"))

        timeSource += 1.seconds
        assertNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-ttl"))

        val refreshed =
            cache.getOrFetch(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-ttl",
            ) {
                fetches += 1
                updated
            }
        assertEquals(updated, refreshed)
        assertEquals(1, fetches)

        // dropServer removes only that server's entries.
        val server2Msg = quotedMessage("msg-s2", body = "Server 2")
        cache.put(viewerA2.userId, viewerA2.serverId, "chan-1", "msg-s2", server2Msg)
        cache.dropServer(serverId = "server-1", accountUserId = "user-a")
        assertNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-ttl"))
        assertEquals(server2Msg, cache.peek(viewerA2.userId, viewerA2.serverId, "chan-1", "msg-s2"))

        // Sign-out clear() fences an in-flight fetch started before sign-out.
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var activeViewer: RaftQuotedPermalinkViewer? = viewerA1
        val inflight =
            launch {
                val result =
                    cache.getOrFetch(
                        viewerProvider = { activeViewer },
                        channelId = "chan-1",
                        messageId = "msg-inflight",
                    ) {
                        fetchStarted.complete(Unit)
                        releaseFetch.await()
                        quotedMessage("msg-inflight", body = "Stale Account A quote")
                    }
                assertNull(result, "viewer switch during fetch must discard stale quote")
            }
        fetchStarted.await()
        cache.clear()
        activeViewer = viewerB1
        releaseFetch.complete(Unit)
        inflight.join()

        assertNull(cache.peek(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-inflight"))
        assertNull(cache.peek(viewerB1.userId, viewerB1.serverId, "chan-1", "msg-inflight"))
        assertNull(cache.peek(viewerA2.userId, viewerA2.serverId, "chan-1", "msg-s2"))
    }

    @Test
    fun definitiveUnavailableCachesForShortTtlWhileTransientFailuresStayUncachedAndClearFencesNegativeEntries() = runTest {
        val timeSource = TestTimeSource()
        val cache =
            ThreadQuotedPermalinkCache(
                maxEntries = 10,
                ttl = 5.minutes,
                negativeTtl = 60.seconds,
                timeSource = timeSource,
            )
        var calls = 0

        // Definitive 404/403 is cached as KnownUnavailable for negativeTtl (60s).
        val first =
            cache.getOrFetchOutcome(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-404",
            ) {
                calls += 1
                ThreadQuotedPermalinkFetchOutcome.DefinitiveUnavailable
            }
        assertNull(first)
        assertEquals(1, calls)
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.KnownUnavailable,
            cache.peekLookup(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-404"),
        )
        // Other viewers/servers remain isolated (Miss).
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.Miss,
            cache.peekLookup(viewerB1.userId, viewerB1.serverId, "chan-1", "msg-404"),
        )

        // Second call within 60s skips network.
        timeSource += 59.seconds
        val second =
            cache.getOrFetchOutcome(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-404",
            ) {
                calls += 1
                ThreadQuotedPermalinkFetchOutcome.Loaded(quotedMessage("msg-404"))
            }
        assertNull(second)
        assertEquals(1, calls)

        // After 60s TTL expiry, peekLookup returns Miss and re-fetch succeeds.
        timeSource += 1.seconds
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.Miss,
            cache.peekLookup(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-404"),
        )
        val recovered = quotedMessage("msg-404", body = "Restored")
        val third =
            cache.getOrFetchOutcome(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-404",
            ) {
                calls += 1
                ThreadQuotedPermalinkFetchOutcome.Loaded(recovered)
            }
        assertEquals(recovered, third)
        assertEquals(2, calls)
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.Hit(recovered),
            cache.peekLookup(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-404"),
        )

        // Transient 5xx/network failure is NEVER cached.
        var transientCalls = 0
        val transientFirst =
            cache.getOrFetchOutcome(
                viewerProvider = { viewerA1 },
                channelId = "chan-1",
                messageId = "msg-503",
            ) {
                transientCalls += 1
                ThreadQuotedPermalinkFetchOutcome.TransientFailure
            }
        assertNull(transientFirst)
        assertEquals(1, transientCalls)
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.Miss,
            cache.peekLookup(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-503"),
        )

        // dropServer and clear evict negative entries too.
        cache.putUnavailable(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-403-a")
        cache.putUnavailable(viewerA2.userId, viewerA2.serverId, "chan-1", "msg-403-b")
        cache.dropServer(serverId = "server-1", accountUserId = "user-a")
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.Miss,
            cache.peekLookup(viewerA1.userId, viewerA1.serverId, "chan-1", "msg-403-a"),
        )
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.KnownUnavailable,
            cache.peekLookup(viewerA2.userId, viewerA2.serverId, "chan-1", "msg-403-b"),
        )
        cache.clear()
        assertEquals(
            ThreadQuotedPermalinkCacheLookup.Miss,
            cache.peekLookup(viewerA2.userId, viewerA2.serverId, "chan-1", "msg-403-b"),
        )
    }
}
