package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.FakeConversationNetworkClient
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.conversationRepositoryTestSessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Task #259: regression tests for thread paging store bugs:
 * 1. A dropped/cancelled/cleared older-page request must never leave `olderMessagesStatus == "loading"` stuck.
 * 2. `canLoadMoreBefore` / `canLoadMoreAfter` must derive from the raw server row count, never the kept/deduped count
 *    (AGENTS.md "No silent drops of server data").
 */
class ThreadPagingStoreReliabilityTest {
    @Test
    fun droppedOlderPageAfterFailedRefreshDoesNotLeaveLoadingStuck() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val olderStarted = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = true),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(store.state.value.payload.canLoadMoreBefore)

        api.beforeMessagesStarted = olderStarted
        api.releaseBeforeMessages = releaseOlder
        val staleOlderJob = launch { store.accept(SharedThreadIntent.LoadOlder) }
        olderStarted.await()
        assertEquals("loading", store.state.value.payload.olderMessagesStatus)

        // A refresh advances routeStaleToken and fails while keeping renderable content.
        api.failChannelMessages = true
        store.accept(SharedThreadIntent.Refresh)
        assertEquals("content", store.state.value.payload.status)

        // The superseded older-page request completes and is dropped by staleTokenStillCurrent.
        api.failChannelMessages = false
        releaseOlder.complete(Unit)
        staleOlderJob.join()

        assertEquals(
            "idle",
            store.state.value.payload.olderMessagesStatus,
            "a dropped older-page request must clear loading instead of leaving LoadOlder blocked",
        )

        // A subsequent LoadOlder must run and merge the older message.
        api.beforeMessagesStarted = null
        api.releaseBeforeMessages = null
        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(listOf("older body", "current body"), store.state.value.payload.messages.map { it.body })
        assertEquals("idle", store.state.value.payload.olderMessagesStatus)
        scope.cancel()
    }

    @Test
    fun cancelledOlderPageAndStoreClearResetLoadingStatus() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val olderStarted = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = true),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        api.beforeMessagesStarted = olderStarted
        api.releaseBeforeMessages = releaseOlder
        val olderJob = launch { store.accept(SharedThreadIntent.LoadOlder) }
        olderStarted.await()
        assertEquals("loading", store.state.value.payload.olderMessagesStatus)

        olderJob.cancelAndJoin()
        assertEquals(
            "idle",
            store.state.value.payload.olderMessagesStatus,
            "cancelling an in-flight older-page request must reset olderMessagesStatus to idle",
        )

        // Subsequent LoadOlder succeeds.
        api.beforeMessagesStarted = null
        api.releaseBeforeMessages = null
        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(listOf("older body", "current body"), store.state.value.payload.messages.map { it.body })

        // clear() while olderMessagesStatus == "loading" also resets to idle.
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = false))
        val clearStarted = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        api.beforeMessagesStarted = clearStarted
        api.releaseBeforeMessages = releaseClear
        val clearingJob = launch { store.accept(SharedThreadIntent.LoadOlder) }
        clearStarted.await()
        assertEquals("loading", store.state.value.payload.olderMessagesStatus)
        store.clear()
        assertEquals("idle", store.state.value.payload.olderMessagesStatus)
        releaseClear.complete(Unit)
        clearingJob.join()
        scope.cancel()
    }

    @Test
    fun olderAndNewerPagingUseRawServerRowCountNotKeptOrDedupedRows() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val pageSize = 10
        val sessionStore = conversationRepositoryTestSessionStore()
        var olderBody =
            channelMessagesJson(
                (11L..19L).map { seq -> goodRow("m$seq", seq) } + badRow("m20", 20L),
            )
        val newerBody =
            channelMessagesJson(
                (31L..39L).map { seq -> goodRow("m$seq", seq) } + badRow("m40", 40L),
            )
        val initialBody = channelMessagesJson((21L..30L).map { seq -> goodRow("m$seq", seq) })
        val network =
            FakeConversationNetworkClient(
                responseForRequest = { req ->
                    when {
                        req.path == "messages/channel/channel-1" && req.query.any { it.first == "before" } -> olderBody
                        req.path == "messages/channel/channel-1" && req.query.any { it.first == "after" } -> newerBody
                        req.path == "messages/channel/channel-1" -> initialBody
                        req.path == "channels/channel-1" ->
                            """{"id":"channel-1","name":"general","type":"channel","joined":true,"canSend":true}"""
                        else -> """{"threads":[]}"""
                    }
                },
            )
        val repository = RealConversationRepository(sessionStore, network)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                threadPageSizeProvider = { pageSize },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(10, store.state.value.payload.messages.size)
        assertTrue(store.state.value.payload.canLoadMoreBefore)

        // 1. Server sends 10 raw rows (== limit), 1 row unreadable -> 9 kept. Must keep canLoadMoreBefore = true.
        store.accept(SharedThreadIntent.LoadOlder)
        val afterUnreadableOlder = store.state.value.payload
        assertEquals(19, afterUnreadableOlder.messages.size)
        assertTrue(
            afterUnreadableOlder.canLoadMoreBefore,
            "10 raw server rows with 1 dropped row must still report canLoadMoreBefore = true",
        )
        assertEquals("idle", afterUnreadableOlder.olderMessagesStatus)

        // 2. Server sends 10 raw rows where 1 overlaps an already-loaded message -> 9 incoming, 10 raw.
        olderBody = channelMessagesJson((2L..11L).map { seq -> goodRow("m$seq", seq) })
        store.accept(SharedThreadIntent.LoadOlder)
        val afterOverlapOlder = store.state.value.payload
        assertEquals(28, afterOverlapOlder.messages.size)
        assertTrue(
            afterOverlapOlder.canLoadMoreBefore,
            "10 raw server rows with 1 overlapping row must still report canLoadMoreBefore = true",
        )

        // 3. Server sends rows where none decode -> error state, NOT empty/canLoadMoreBefore=false.
        olderBody = channelMessagesJson(listOf(badRow("bad-1", 1L)))
        store.accept(SharedThreadIntent.LoadOlder)
        val afterAllBad = store.state.value.payload
        assertEquals("error", afterAllBad.olderMessagesStatus, "unreadable server rows must fail, not turn empty")
        assertTrue(afterAllBad.canLoadMoreBefore, "unreadable older page must keep paging open for retry")

        // 4. Genuine short server page (1 raw row < limit 10) ends older paging cleanly.
        olderBody = channelMessagesJson(listOf(goodRow("m1", 1L)))
        store.accept(SharedThreadIntent.LoadOlder)
        val afterShortOlder = store.state.value.payload
        assertFalse(afterShortOlder.canLoadMoreBefore, "short server page closes older paging")
        assertEquals("idle", afterShortOlder.olderMessagesStatus)

        // 5. Symmetric newer page: 10 raw rows with 1 unreadable -> 9 kept, canLoadMoreAfter stays true.
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = false, canLoadMoreAfter = true))
        val newerOutcome = store.loadNewer()
        assertTrue(newerOutcome is ThreadNewerPageLoadOutcome.Applied)
        assertTrue(
            store.state.value.payload.canLoadMoreAfter,
            "10 raw server rows on newer page with 1 dropped row must keep canLoadMoreAfter = true",
        )
        scope.cancel()
    }

    private fun channelMessagesJson(rows: List<String>): String =
        """{"historyLimited":false,"messages":[${rows.joinToString(",")}]}"""

    private fun goodRow(id: String, seq: Long): String =
        """{"id":"$id","seq":$seq,"channelId":"channel-1","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"body $seq","createdAt":"2026-05-22T10:00:00.000Z"}"""

    /** An attachment without its required `id`: the row fails to decode and is skipped row-by-row. */
    private fun badRow(id: String, seq: Long): String =
        """{"id":"$id","seq":$seq,"channelId":"channel-1","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"bad $seq","createdAt":"2026-05-22T10:00:00.000Z","attachments":[{"filename":"x.png"}]}"""
}
