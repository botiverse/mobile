package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** web `useCoreSearchGroups` (slock #15387) for the channels and people groups, on virtual time. */
class SharedSearchCoreGroupsStoreTest {
    @Test
    fun wordsMarkTheirGroupsPendingAtOnceThenEachGroupAnswersOnItsOwn() = runTest {
        val source = FakeSource()
        val store = store(source)

        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "  alp ", enabled = true))
        assertEquals(SearchCoreGroupStatus.Loading, store.state.value.channels.status)
        assertEquals(SearchCoreGroupStatus.Loading, store.state.value.people.status)
        assertEquals("alp", store.state.value.channels.query)
        advanceTimeBy(SharedSearchArtifactsStore.DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(emptyList(), source.calls, "no request before the pause")
        advanceUntilIdle()

        val state = store.state.value
        assertEquals(SearchCoreGroupStatus.Ok, state.channels.status)
        assertEquals(listOf("c-alp"), state.channels.items.map { it.id })
        assertEquals(37, state.channels.total)
        assertEquals("next", state.channels.nextCursor)
        assertEquals(listOf("human:u-alp"), state.people.items.map { it.key })
        // Each group alone, first page at the server's default size.
        assertEquals(setOf(Call(SearchCoreGroup.Channels, "alp", null, null), Call(SearchCoreGroup.People, "alp", null, null)), source.calls.toSet())

        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "alp", enabled = true))
        advanceUntilIdle()
        assertEquals(2, source.calls.size, "the same words ask nothing")
    }

    @Test
    fun aLeadingHashOrAtAsksOneGroupAndNoWordsOrOffAskNothing() = runTest {
        val source = FakeSource()
        val store = store(source)

        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "#eng", enabled = true))
        assertEquals(SearchCoreGroupStatus.Idle, store.state.value.people.status)
        advanceUntilIdle()
        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "@remy", enabled = true))
        advanceUntilIdle()
        assertEquals(listOf(Call(SearchCoreGroup.Channels, "#eng", null, null), Call(SearchCoreGroup.People, "@remy", null, null)), source.calls)

        source.calls.clear()
        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "#", enabled = true))
        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "eng", enabled = false))
        store.dispatch(SearchCoreGroupsIntent.Query(null, "eng", enabled = true))
        advanceUntilIdle()
        assertEquals(emptyList(), source.calls)
        assertEquals(SearchCoreGroupStatus.Idle, store.state.value.channels.status)
    }

    @Test
    fun onlyTheLatestWordsAnswer() = runTest {
        val slow = CompletableDeferred<WebSearchCoreGroupLoad<SearchChannelHit>>()
        val source = FakeSource(channels = { q, _ -> if (q == "al") slow.await() else channelPage("c-$q") })
        val store = store(source)

        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "al", enabled = true))
        advanceUntilIdle()
        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "alp", enabled = true))
        slow.complete(channelPage("c-stale"))
        advanceUntilIdle()

        assertEquals(listOf("c-alp"), store.state.value.channels.items.map { it.id })
        assertEquals("alp", store.state.value.channels.query)
    }

    @Test
    fun aFailedGroupKeepsItsErrorAndRetryAsksOnlyTheFailedOnes() = runTest {
        var fail = true
        val source =
            FakeSource(
                channels = { q, _ -> if (fail) WebSearchCoreGroupLoad.Failed(ScreenError("web_search_http_500", traceId = TraceId)) else channelPage("c-$q") },
            )
        val store = store(source)

        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "alp", enabled = true))
        advanceUntilIdle()
        assertEquals(SearchCoreGroupStatus.Failed, store.state.value.channels.status)
        assertEquals(TraceId, store.state.value.channels.error?.traceId)
        assertEquals(SearchCoreGroupStatus.Ok, store.state.value.people.status)

        fail = false
        store.dispatch(SearchCoreGroupsIntent.Retry)
        assertEquals(SearchCoreGroupStatus.Loading, store.state.value.channels.status)
        assertTrue(store.state.value.channels.retrying)
        advanceUntilIdle()

        assertEquals(SearchCoreGroupStatus.Ok, store.state.value.channels.status)
        assertFalse(store.state.value.channels.retrying)
        assertEquals(1, source.calls.count { it.group == SearchCoreGroup.People }, "the answered group isn't asked again")
        assertEquals(2, source.calls.count { it.group == SearchCoreGroup.Channels })
    }

    @Test
    fun anOlderServerWithoutTheGroupsLeavesThemIdle() = runTest {
        val store = store(FakeSource(channels = { _, _ -> WebSearchCoreGroupLoad.Unsupported }, people = { _, _ -> WebSearchCoreGroupLoad.Unsupported }))

        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "alp", enabled = true))
        advanceUntilIdle()

        assertEquals(SearchCoreGroupStatus.Idle, store.state.value.channels.status)
        assertEquals(SearchCoreGroupStatus.Idle, store.state.value.people.status)
    }

    @Test
    fun loadMorePagesWithTheCursorWithoutRepeatsAndAFailedPageOffersRetry() = runTest {
        var pageFails = true
        val source =
            FakeSource(
                channels = { _, cursor ->
                    when {
                        cursor == null -> channelPage("c1", "c2", next = "p2", total = 4)
                        pageFails -> WebSearchCoreGroupLoad.Failed(ScreenError("web_search_network", traceId = TraceId))
                        // c2 again (the order moved between pages): shown once.
                        else -> channelPage("c2", "c3", "c4", next = null, total = 4)
                    }
                },
            )
        val store = store(source)
        store.dispatch(SearchCoreGroupsIntent.Query("s-1", "c", enabled = true))
        advanceUntilIdle()

        store.dispatch(SearchCoreGroupsIntent.LoadMore(SearchCoreGroup.Channels))
        assertTrue(store.state.value.channels.paging)
        advanceUntilIdle()
        val failed = store.state.value.channels
        assertTrue(failed.pageFailed, "the section shows Retry in Load more's place")
        assertFalse(failed.paging)
        assertEquals(listOf("c1", "c2"), failed.items.map { it.id }, "the rows on screen stay")
        assertEquals("p2", failed.nextCursor)
        assertEquals(TraceId, failed.error?.traceId)

        pageFails = false
        store.dispatch(SearchCoreGroupsIntent.LoadMore(SearchCoreGroup.Channels))
        advanceUntilIdle()
        val paged = store.state.value.channels
        assertFalse(paged.pageFailed)
        assertEquals(listOf("c1", "c2", "c3", "c4"), paged.items.map { it.id })
        assertNull(paged.nextCursor)
        assertEquals(
            listOf(Call(SearchCoreGroup.Channels, "c", "p2", WEB_SEARCH_MAX_LIMIT), Call(SearchCoreGroup.Channels, "c", "p2", WEB_SEARCH_MAX_LIMIT)),
            source.calls.filter { it.cursor != null },
        )

        // Nothing more to read: Load more asks nothing.
        store.dispatch(SearchCoreGroupsIntent.LoadMore(SearchCoreGroup.Channels))
        advanceUntilIdle()
        assertEquals(2, source.calls.count { it.cursor != null })
    }

    private fun TestScope.store(source: FakeSource) =
        // Not backgroundScope: advanceUntilIdle runs only foreground work (see SharedSearchArtifactsStoreTest).
        SharedSearchCoreGroupsStore(source, CoroutineScope(StandardTestDispatcher(testScheduler)))

    private data class Call(val group: SearchCoreGroup, val q: String, val cursor: String?, val limit: Int?)

    private class FakeSource(
        private val channels: suspend (q: String, cursor: String?) -> WebSearchCoreGroupLoad<SearchChannelHit> = { q, _ -> channelPage("c-$q", next = "next", total = 37) },
        private val people: suspend (q: String, cursor: String?) -> WebSearchCoreGroupLoad<SearchPersonHit> = { q, _ -> personPage("u-$q") },
    ) : SearchCoreGroupsSource {
        val calls = mutableListOf<Call>()

        override suspend fun channels(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority): WebSearchCoreGroupLoad<SearchChannelHit> {
            assertEquals(DataRequestPriority.Visible, priority)
            calls += Call(SearchCoreGroup.Channels, q, cursor, limit)
            return channels(q, cursor)
        }

        override suspend fun people(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority): WebSearchCoreGroupLoad<SearchPersonHit> {
            calls += Call(SearchCoreGroup.People, q, cursor, limit)
            return people(q, cursor)
        }
    }

    private companion object {
        const val TraceId = "0af7651916cd43dd8448eb211c80319c"

        fun channelPage(vararg ids: String, next: String? = null, total: Int = ids.size): WebSearchCoreGroupLoad<SearchChannelHit> =
            WebSearchCoreGroupLoad.Page(ids.map { channelHit(it) }, next, total)

        fun personPage(vararg ids: String): WebSearchCoreGroupLoad<SearchPersonHit> =
            WebSearchCoreGroupLoad.Page(
                ids.map { SearchPersonHit(false, it, it, null, null, "member", false, null, false, SearchRequiredAction.CreateDm) },
                null,
                ids.size,
            )

        fun channelHit(id: String) = SearchChannelHit(id, id, null, "channel", false, true, false, false, true, null)
    }
}
