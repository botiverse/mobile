package build.raft.app.data

import build.raft.app.activityArrivedTopRowKeys
import build.raft.app.activityPillKeysAfterChange
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hands f6c3f3a6 (iOS 1.17.0): a refresh of a deep-loaded Activity list re-reads every loaded row
 * as sequential 100-row pages. The first page is shown as soon as it lands (spinner ends there),
 * the later pages merge in behind it, and a later page that fails keeps what was fetched.
 */
class SharedActivityStoreProgressiveRefreshTest {
    private val rows = (0 until 250).map { index -> activityItem("r$index") }

    /** Every read suspends until the test answers it, in call order. */
    private class ScriptedInboxRepository : InboxRepository {
        data class Call(val offset: Int, val limit: Int, val answer: CompletableDeferred<DataResult<InboxSnapshot>>)

        val calls = mutableListOf<Call>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> =
            Call(0, limit, CompletableDeferred()).also { calls += it }.answer.await()

        override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> =
            Call(offset, limit, CompletableDeferred()).also { calls += it }.answer.await()

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
            DataResult.Content(InboxDoneResult(channelId))

        fun answer(callIndex: Int, result: DataResult<InboxSnapshot>) {
            calls[callIndex].answer.complete(result)
        }
    }

    private fun page(items: List<InboxItem>, nextOffset: Int, hasMore: Boolean = true, totalUnreadCount: Int = 0) =
        DataResult.Content(
            snapshot(items, hasMore = hasMore, nextOffset = nextOffset, totalCount = 250, totalUnreadCount = totalUnreadCount)
        )

    /** Loads [rows] as the user scrolled them in: 100 + 100 + 50, so a refresh re-reads 250 rows. */
    private fun loadedStore(
        scope: CoroutineScope,
        inbox: ScriptedInboxRepository,
        logs: MutableList<String> = mutableListOf()
    ): SharedActivityStore {
        val store = activityStore(scope, inbox, diagnosticsLogger = { line -> logs += line })
        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.answer(0, page(rows.take(100), nextOffset = 100))
        store.dispatch(SharedActivityIntent.LoadMore)
        inbox.answer(1, page(rows.slice(100 until 200), nextOffset = 200))
        store.dispatch(SharedActivityIntent.LoadMore)
        inbox.answer(2, page(rows.slice(200 until 250), nextOffset = 250, hasMore = false))
        assertEquals(250, store.state.value.items.size)
        return store
    }

    private val SharedActivityStore.keys: List<String>
        get() = state.value.items.map(InboxItem::key)

    @Test
    fun refreshShowsTheFirstPageBeforeTheLaterPagesAndKeepsTheRowsBelow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = ScriptedInboxRepository()
        val logs = mutableListOf<String>()
        val store = loadedStore(scope, inbox, logs)
        val fresh = activityItem("fresh", unreadCount = 2)

        store.dispatch(SharedActivityIntent.Refresh)
        assertEquals(listOf(0 to 100), inbox.calls.drop(3).map { it.offset to it.limit }, "the refresh reads 250 rows from offset 0")
        assertTrue(store.state.value.refreshing)

        inbox.answer(3, page(listOf(fresh) + rows.take(99), nextOffset = 100, totalUnreadCount = 2))

        // Page 1 is on screen while page 2 is still on the network.
        assertEquals(listOf(0 to 100, 100 to 100), inbox.calls.drop(3).map { it.offset to it.limit })
        assertEquals(listOf(fresh.key) + rows.map(InboxItem::key), store.keys, "page 1 on top, the shown rows below, none twice")
        assertEquals(2, store.state.value.totalUnreadCount, "page 1's unread total is shown at once")
        assertFalse(store.state.value.refreshing)
        assertNull(store.state.value.errorCode)
        assertTrue(logs.any { "phase=page_published fetched=100" in it }, logs.joinToString("\n"))

        inbox.answer(4, page(rows.slice(99 until 199), nextOffset = 200))
        assertEquals(200 to 50, inbox.calls[5].let { it.offset to it.limit })
        inbox.answer(5, page(rows.slice(199 until 250), nextOffset = 251, hasMore = false))

        assertEquals(listOf(fresh.key) + rows.map(InboxItem::key), store.keys)
        assertFalse(store.state.value.hasMore)
        assertTrue(logs.any { "refresh=true" in it && "result=content items=251" in it }, logs.joinToString("\n"))
        scope.cancel()
    }

    @Test
    fun pullSpinnerEndsWhenTheFirstPageLands() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = ScriptedInboxRepository()
        val store = loadedStore(scope, inbox)

        store.dispatch(SharedActivityIntent.PullRefresh)
        assertTrue(store.state.value.pullRefreshing)

        inbox.answer(3, page(rows.take(100), nextOffset = 100))

        assertFalse(store.state.value.pullRefreshing, "the pull spinner ends with page 1")
        assertFalse(store.state.value.refreshing)
        assertEquals(5, inbox.calls.size, "page 2 is read behind the shown list")

        // A LoadMore at the bottom waits for the refresh's own later pages instead of cancelling them.
        store.dispatch(SharedActivityIntent.LoadMore)
        assertEquals(5, inbox.calls.size)

        inbox.answer(4, page(rows.slice(100 until 200), nextOffset = 200))
        inbox.answer(5, page(rows.slice(200 until 250), nextOffset = 250, hasMore = false))
        assertEquals(rows.map(InboxItem::key), store.keys)
        assertFalse(store.state.value.pullRefreshing)
        scope.cancel()
    }

    @Test
    fun aFailedLaterPageKeepsTheFetchedRowsAndIsNotAnError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = ScriptedInboxRepository()
        val logs = mutableListOf<String>()
        val store = loadedStore(scope, inbox, logs)
        val renamedOnPage2 = rows[150].copy(channelName = "renamed")

        store.dispatch(SharedActivityIntent.PullRefresh)
        inbox.answer(3, page(rows.take(100), nextOffset = 100))
        inbox.answer(4, page(rows.slice(100 until 150) + renamedOnPage2 + rows.slice(151 until 200), nextOffset = 200))
        // The app left the foreground: the host retired page 3 (`network_request_lifecycle_retired`).
        inbox.answer(5, DataResult.Error(ScreenError("http_0")))

        assertNull(store.state.value.errorCode, "a later page's failure is not the refresh's error")
        assertEquals(rows.map(InboxItem::key), store.keys, "nothing fetched is thrown away, nothing below disappears")
        assertEquals("renamed", store.state.value.items[150].channelName, "page 2's rows are applied")
        assertTrue(store.state.value.hasMore || store.state.value.nextOffset != null)
        assertFalse(store.state.value.pullRefreshing)
        assertFalse(store.state.value.refreshing)
        assertTrue(logs.any { "result=content_partial code=http_0" in it }, logs.joinToString("\n"))
        scope.cancel()
    }

    @Test
    fun aRowMovingAcrossAPageBoundaryDuringTheRefreshIsListedOnceAndNoneIsLost() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = ScriptedInboxRepository()
        val store = loadedStore(scope, inbox)
        // r150 got a new message after page 1 was served...
        val bumped = rows[150].copy(latestActivityMessageId = MessageId("newer-r150"), unreadCount = 1)
        // ...and r160 got one before: page 1 already lists it on top.
        val early = rows[160].copy(latestActivityMessageId = MessageId("newer-r160"), unreadCount = 1)
        val served1 = listOf(early) + rows.take(99)
        val serverAfter = listOf(bumped, early) + rows.filterNot { it.key == bumped.key || it.key == early.key }

        store.dispatch(SharedActivityIntent.Refresh)
        inbox.answer(3, page(served1, nextOffset = 100, totalUnreadCount = 1))
        val afterPage1 = store.keys
        assertEquals(afterPage1.distinct(), afterPage1, "no row twice after page 1")
        assertEquals(rows.map(InboxItem::key).toSet(), afterPage1.toSet(), "no row lost after page 1")
        assertEquals(early.key, afterPage1.first())

        // Offset 100 of the newer order: r98 is on page 1 already and comes again here.
        inbox.answer(4, page(serverAfter.slice(100 until 200), nextOffset = 200))
        val afterPage2 = store.keys
        assertEquals(afterPage2.distinct(), afterPage2, "no row twice after page 2")
        assertEquals(rows.map(InboxItem::key).toSet(), afterPage2.toSet(), "no row lost after page 2")

        inbox.answer(5, page(serverAfter.slice(200 until 250), nextOffset = 250, hasMore = false))
        val final = store.keys
        assertEquals(final.distinct(), final)
        // The final list is the served order: page 1 as served, then pages 2 and 3, each row once.
        // r150 moved up into the range page 1 had already read, so no page of this read serves it
        // (offset pagination; the read before this change dropped it the same way). The realtime
        // reconcile its message:new schedules serves it on top.
        val expected = (served1 + serverAfter.slice(100 until 250)).map(InboxItem::key).distinct()
        assertEquals(expected, final)
        scope.cancel()
    }

    @Test
    fun theNewUpdatesPillCountsTheFirstPagesArrivalsOnceAndTheLaterPagesAddNone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = ScriptedInboxRepository()
        val store = loadedStore(scope, inbox)
        val bumped = rows[40].copy(lastMessageId = MessageId("newer-r40"), latestActivityMessageId = MessageId("newer-r40"), unreadCount = 1)
        val fresh = activityItem("fresh", unreadCount = 1)
        val served = listOf(fresh, bumped) + rows.filterNot { it.key == bumped.key }

        val before = store.state.value.items
        store.dispatch(SharedActivityIntent.Refresh)
        inbox.answer(3, page(served.take(100), nextOffset = 100, totalUnreadCount = 2))
        val afterPage1 = store.state.value.items
        inbox.answer(4, page(served.slice(100 until 200), nextOffset = 200))
        val afterPage2 = store.state.value.items
        inbox.answer(5, page(served.slice(200 until 251), nextOffset = 251, hasMore = false))
        val final = store.state.value.items

        assertEquals(setOf(fresh.key, bumped.key), activityArrivedTopRowKeys(before, afterPage1))
        assertEquals(emptySet(), activityArrivedTopRowKeys(afterPage1, afterPage2), "page 2 adds no pill rows")
        assertEquals(emptySet(), activityArrivedTopRowKeys(afterPage2, final), "page 3 adds no pill rows")
        // The pill as settleTopEdge folds it over the three publishes, reader scrolled away.
        val pill =
            listOf(before, afterPage1, afterPage2, final).zipWithNext().fold(emptySet<String>()) { keys, (previous, next) ->
                activityPillKeysAfterChange(keys, previous, next, listAtTop = false)
            }
        assertEquals(setOf(fresh.key, bumped.key), pill, "the same two rows a one-shot refresh would count")
        assertEquals(served.map(InboxItem::key), final.map(InboxItem::key))
        scope.cancel()
    }

    @Test
    fun aPullDuringAVisibleRefreshJoinsItAndShowsTheSpinner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = ScriptedInboxRepository()
        val logs = mutableListOf<String>()
        val store = loadedStore(scope, inbox, logs)

        store.dispatch(SharedActivityIntent.Refresh)
        assertFalse(store.state.value.pullRefreshing)

        store.dispatch(SharedActivityIntent.PullRefresh)

        assertTrue(store.state.value.pullRefreshing, "the pull shows its spinner over the running refresh")
        assertEquals(4, inbox.calls.size, "the refresh in flight is not restarted")
        assertTrue(logs.any { "phase=pull_joined" in it }, logs.joinToString("\n"))

        inbox.answer(3, page(rows.take(100), nextOffset = 100))
        assertFalse(store.state.value.pullRefreshing, "the joined pull ends with page 1")
        inbox.answer(4, page(rows.slice(100 until 200), nextOffset = 200))
        inbox.answer(5, page(rows.slice(200 until 250), nextOffset = 250, hasMore = false))
        assertEquals(rows.map(InboxItem::key), store.keys)
        scope.cancel()
    }

    @Test
    fun aPullAfterTheFirstPageIsShownStartsAFreshRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = ScriptedInboxRepository()
        val logs = mutableListOf<String>()
        val store = loadedStore(scope, inbox, logs)

        store.dispatch(SharedActivityIntent.Refresh)
        inbox.answer(3, page(rows.take(100), nextOffset = 100))

        store.dispatch(SharedActivityIntent.PullRefresh)

        assertTrue(store.state.value.pullRefreshing)
        assertEquals(0, inbox.calls.last().offset, "the pull reads from offset 0 again")
        assertTrue(logs.any { "phase=pull_supersedes reason=first_page_shown" in it }, logs.joinToString("\n"))
        inbox.answer(inbox.calls.lastIndex, page(rows.take(100), nextOffset = 100))
        assertFalse(store.state.value.pullRefreshing)
        scope.cancel()
    }
}
