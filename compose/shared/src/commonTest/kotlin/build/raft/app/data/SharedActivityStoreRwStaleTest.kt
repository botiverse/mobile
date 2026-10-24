package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #164: `GET /channels/inbox` while the read warehouse is behind. The answers are the
 * server's (botiverse/slock f6ace2b9): `routes/rwStaleRead.ts` serves a remembered first page
 * with `X-Raft-Stale: rw_overloaded`; past 5 minutes or with nothing remembered
 * `routes/errorResponse.ts` answers `503` + `Retry-After: 2` +
 * `{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}`.
 *
 * Runs the real [RealInboxRepository] (header and error parsing) under [SharedActivityStore] on
 * virtual time. The inbox's own single overload retry (2 s) is part of every 503 here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRwStaleTest {

    @Test
    fun overloadKeepsTheShownRowsAndMarksThemStale() = runTest {
        val network = ScriptedInbox(this, listOf(fresh("general" to 3, "random" to 2), overloaded(), overloaded()))
        val store = store(network)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.Refresh)
        settleOverloadRetry()

        val state = store.state.value
        assertEquals(listOf("channel:general", "channel:random"), state.items.map(InboxItem::key))
        assertEquals(5, state.totalUnreadCount, "the counts are kept, never zeroed")
        assertTrue(state.stale)
        assertEquals("server_unavailable", state.errorCode, "the typed error is attached beside the kept rows")
    }

    @Test
    fun aRememberedPageNeverReplacesShownRowsButMarksThemStale() = runTest {
        val network = ScriptedInbox(this, listOf(fresh("general" to 3), stale(fresh())))
        val store = store(network)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        val state = store.state.value
        assertEquals(listOf("channel:general"), state.items.map(InboxItem::key), "an empty remembered page is not 'No activity'")
        assertEquals(3, state.totalUnreadCount)
        assertTrue(state.stale)
        assertNull(state.errorCode)
    }

    @Test
    fun aFreshAnswerClearsStaleThroughOneBackgroundRecoveryRead() = runTest {
        val network =
            ScriptedInbox(this, listOf(fresh("general" to 3), stale(fresh("general" to 9)), fresh("general" to 1)))
        val store = store(network)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertTrue(store.state.value.stale)
        assertEquals(2, network.requestTimes.size)

        advanceTimeBy(RecoveryDelayMillis - 1)
        runCurrent()
        assertEquals(2, network.requestTimes.size, "no read before the backoff")
        advanceTimeBy(1)
        runCurrent()

        val state = store.state.value
        assertEquals(3, network.requestTimes.size, "exactly one recovery read")
        assertFalse(state.stale, "the fresh answer clears the notice")
        assertEquals(1, state.totalUnreadCount, "and its counts apply")
        advanceTimeBy(10 * RecoveryDelayMillis)
        runCurrent()
        assertEquals(3, network.requestTimes.size, "nothing more is scheduled once fresh")
    }

    @Test
    fun recoveryWaitsWhileActivityIsHidden() = runTest {
        val network = ScriptedInbox(this, listOf(fresh("general" to 3), overloaded(), overloaded(), fresh("general" to 3)))
        val store = store(network)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.Refresh)
        settleOverloadRetry()
        store.dispatch(SharedActivityIntent.SurfaceHidden)

        advanceTimeBy(10 * RecoveryDelayMillis)
        runCurrent()

        assertEquals(3, network.requestTimes.size, "no read while hidden")
        assertTrue(store.state.value.stale)
    }

    @Test
    fun coldStartWithACacheAndAnOverloadShowsTheCacheAsStale() = runTest {
        val storage = InMemoryPersistStorage()
        val first = store(ScriptedInbox(this, listOf(fresh("general" to 3, "random" to 2))), storage)
        first.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        val reopened = store(ScriptedInbox(this, listOf(overloaded(), overloaded())), storage)
        reopened.dispatch(SharedActivityIntent.LoadInitial)
        settleOverloadRetry()

        val state = reopened.state.value
        assertEquals(listOf("channel:general", "channel:random"), state.items.map(InboxItem::key))
        assertEquals(5, state.totalUnreadCount)
        assertTrue(state.stale)
    }

    @Test
    fun noCacheAndAnOverloadIsTheLoadFailedStateNotAnEmptyList() = runTest {
        val store = store(ScriptedInbox(this, listOf(overloaded(), overloaded())))
        store.dispatch(SharedActivityIntent.LoadInitial)
        settleOverloadRetry()

        val state = store.state.value
        assertNull(state.snapshot, "no rows: the page shows its load-failed state with Retry, not 'No activity'")
        assertEquals("server_unavailable", state.errorCode)
        assertFalse(state.stale, "nothing is shown, so nothing is stale")
    }

    @Test
    fun noCacheAndARememberedPageFillsTheListAsStale() = runTest {
        val store = store(ScriptedInbox(this, listOf(stale(fresh("general" to 4)))))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        val state = store.state.value
        assertEquals(listOf("channel:general"), state.items.map(InboxItem::key))
        assertEquals(4, state.totalUnreadCount)
        assertTrue(state.stale)
    }

    private fun TestScope.store(network: ScriptedInbox, storage: InMemoryPersistStorage? = null): SharedActivityStore {
        val session = InMemoryRaftAuthSessionStore(sampleSession())
        return activityStore(
            scope = backgroundScope,
            inboxRepository = RealInboxRepository(session, network),
            authStore = session,
            persistSlices = listOfNotNull(storage?.let(::createSharedActivityPersistSlice)),
            staleRecoveryDelayMillis = { RecoveryDelayMillis },
        )
    }

    /** The first 503, the inbox's own retry after `Retry-After: 2`, and the store apply. */
    private fun TestScope.settleOverloadRetry() {
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
    }

    private fun channelRow(channelId: String, unreadCount: Int): String =
        """
        {"kind":"channel","channelId":"$channelId","channelName":"$channelId","channelType":"channel",
         "lastMessageId":"m-$channelId","latestActivitySeq":"41","doneFrontierSeq":"41","firstUnreadMessageId":null,
         "firstMentionMessageId":null,"lastMessageAt":"2026-10-24T09:50:00Z","lastMessagePreview":"hi",
         "lastMessageSenderType":"user","lastMessageSenderId":"u-2","lastMessageSenderName":"U",
         "unreadCount":$unreadCount,"hasMention":false}
        """.trimIndent()

    private fun fresh(vararg rows: Pair<String, Int>): HomeNetworkResponse =
        HomeNetworkResponse(
            200,
            """{"items":[${rows.joinToString(",") { (id, unread) -> channelRow(id, unread) }}],""" +
                """"hasMore":false,"totalCount":${rows.size},"totalUnreadCount":${rows.sumOf { it.second }}}"""
        )

    /** The same body served from the server's remembered copy (`rwStaleRead.ts`). */
    private fun stale(response: HomeNetworkResponse): HomeNetworkResponse =
        response.copy(responseHeaders = mapOf("X-Raft-Stale" to listOf("rw_overloaded")))

    private fun overloaded(): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 503,
            bodyText = null,
            errorBodyText = """{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}""",
            responseHeaders = mapOf("Retry-After" to listOf("2"))
        )

    private class ScriptedInbox(
        private val testScope: TestScope,
        private val responses: List<HomeNetworkResponse>
    ) : RaftNetworkClient {
        val requestTimes = mutableListOf<Long>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            check(path == "channels/inbox" && method == "GET") { "unexpected $method $path" }
            requestTimes += testScope.testScheduler.currentTime
            return responses.getOrElse(requestTimes.size - 1) { responses.last() }
        }
    }

    private companion object {
        const val RecoveryDelayMillis = 15_000L
    }
}
