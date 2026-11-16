package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** web `useAppSearchGroups` (slock staging 93207e216) for the Artifacts group, on virtual time. */
class SharedSearchArtifactsStoreTest {
    @Test
    fun withArtifactsOffNothingIsAsked() = runTest {
        val source = FakeSource()
        val store = store(source)

        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = false))
        advanceUntilIdle()
        store.dispatch(SearchArtifactsIntent.Query(null, "budget", enabled = true))
        advanceUntilIdle()

        assertEquals(emptyList(), source.calls)
        assertEquals(SearchArtifactsStatus.Idle, store.state.value.status)
        assertFalse(store.state.value.shown)
    }

    @Test
    fun wordsWaitForAPauseShowTheSkeletonThenTheirResults() = runTest {
        val source = FakeSource { _, cursor -> page(listOf("a", "b"), next = "c1").takeIf { cursor == null } ?: error("no paging here") }
        val store = store(source, limit = 5)

        store.dispatch(SearchArtifactsIntent.Query("s-1", "  budget ", enabled = true))
        assertEquals(SearchArtifactsStatus.Loading, store.state.value.status)
        assertTrue(store.state.value.shown, "the heading waits with its skeleton")
        assertEquals("budget", store.state.value.query)
        advanceTimeBy(SharedSearchArtifactsStore.DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(emptyList(), source.calls, "no request before the pause")
        advanceUntilIdle()

        val state = store.state.value
        assertEquals(SearchArtifactsStatus.Ok, state.status)
        assertEquals(listOf("a", "b"), state.hits.map { it.id })
        assertEquals("c1", state.nextCursor)
        assertEquals(listOf(Call("s-1", "budget", null, 5, DataRequestPriority.Visible)), source.calls)

        // The same words again (a recomposition) ask nothing.
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        advanceUntilIdle()
        assertEquals(1, source.calls.size)
    }

    @Test
    fun onlyTheLatestWordsAnswer() = runTest {
        val slow = CompletableDeferred<WebSearchArtifactsLoad>()
        val source = FakeSource { q, _ -> if (q == "bud") slow.await() else page(listOf("budget")) }
        val store = store(source)

        store.dispatch(SearchArtifactsIntent.Query("s-1", "bu", enabled = true))
        store.dispatch(SearchArtifactsIntent.Query("s-1", "bud", enabled = true))
        advanceTimeBy(SharedSearchArtifactsStore.DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("bud"), source.calls.map { it.q }, "typing on cancels the earlier words' wait")

        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        assertTrue(store.state.value.hits.isEmpty(), "the old words' rows leave at once")
        advanceUntilIdle()
        slow.complete(page(listOf("stale")))
        advanceUntilIdle()

        assertEquals("budget", store.state.value.query)
        assertEquals(listOf("budget"), store.state.value.hits.map { it.id })
    }

    @Test
    fun aGroupThatTimedOutShowsItsErrorAndRetryReadsItAgainAtOnce() = runTest {
        val error = ScreenError("web_search_group_timeout", traceId = "0af7651916cd43dd8448eb211c80319c")
        var answer: WebSearchArtifactsLoad = WebSearchArtifactsLoad.GroupFailed(timedOut = true, error = error)
        val source = FakeSource { _, _ -> answer }
        val store = store(source)
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        advanceUntilIdle()

        val failed = store.state.value
        assertEquals(SearchArtifactsStatus.Failed, failed.status)
        assertTrue(failed.timedOut)
        assertEquals("0af7651916cd43dd8448eb211c80319c", failed.error?.traceId)
        assertTrue(failed.shown)

        answer = page(listOf("a"))
        store.dispatch(SearchArtifactsIntent.Retry)
        assertEquals(SearchArtifactsStatus.Loading, store.state.value.status)
        runCurrent()
        assertEquals(2, source.calls.size, "Retry doesn't wait for a pause")
        advanceUntilIdle()
        assertEquals(SearchArtifactsStatus.Ok, store.state.value.status)
        assertNull(store.state.value.error)
        assertEquals(listOf("a"), store.state.value.hits.map { it.id })
    }

    @Test
    fun noAnswerAtAllIsAFailureWithItsError() = runTest {
        val source = FakeSource { _, _ -> WebSearchArtifactsLoad.Failed(ScreenError("web_search_http_429", serverCode = "RATE_LIMITED", traceId = "t")) }
        val store = store(source)
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        advanceUntilIdle()

        assertEquals(SearchArtifactsStatus.Failed, store.state.value.status)
        assertFalse(store.state.value.timedOut)
        assertEquals("RATE_LIMITED", store.state.value.error?.serverCode)
    }

    @Test
    fun noGroupOrNothingFoundHasNoSection() = runTest {
        var answer: WebSearchArtifactsLoad = WebSearchArtifactsLoad.NoGroup
        val store = store(FakeSource { _, _ -> answer })
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        advanceUntilIdle()
        assertEquals(SearchArtifactsStatus.Idle, store.state.value.status)
        assertFalse(store.state.value.shown)

        answer = page(emptyList())
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budgets", enabled = true))
        advanceUntilIdle()
        assertEquals(SearchArtifactsStatus.Ok, store.state.value.status)
        assertFalse(store.state.value.shown, "web: an app with nothing for these words has no section")
    }

    @Test
    fun loadMoreAppendsTheNextPageWithoutRepeats() = runTest {
        val source = FakeSource { _, cursor -> if (cursor == null) page(listOf("a", "b"), next = "c1") else page(listOf("b", "c"), next = null) }
        val store = store(source)
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        advanceUntilIdle()

        store.dispatch(SearchArtifactsIntent.LoadMore)
        assertTrue(store.state.value.paging)
        store.dispatch(SearchArtifactsIntent.LoadMore)
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), store.state.value.hits.map { it.id })
        assertNull(store.state.value.nextCursor)
        assertFalse(store.state.value.paging)
        assertEquals(listOf(null, "c1"), source.calls.map { it.cursor }, "one page at a time")
        assertEquals(DataRequestPriority.Visible, source.calls.last().priority)
    }

    @Test
    fun loadMoreThatFailsKeepsTheRowsAndSaysSoOnce() = runTest {
        val error = ScreenError("web_search_http_500", traceId = "t-500")
        val source = FakeSource { _, cursor -> if (cursor == null) page(listOf("a"), next = "c1") else WebSearchArtifactsLoad.Failed(error) }
        val store = store(source)
        val events = mutableListOf<SearchArtifactsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(events) }
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        advanceUntilIdle()

        store.dispatch(SearchArtifactsIntent.LoadMore)
        advanceUntilIdle()

        assertEquals(SearchArtifactsStatus.Ok, store.state.value.status)
        assertEquals(listOf("a"), store.state.value.hits.map { it.id })
        assertEquals("c1", store.state.value.nextCursor, "Load more stays to try again")
        assertEquals(error, assertIs<SearchArtifactsEvent.MoreFailed>(events.single()).error)
    }

    @Test
    fun turningOffOrSwitchingServerDropsWhatWasOnItsWay() = runTest {
        val slow = CompletableDeferred<WebSearchArtifactsLoad>()
        val source = FakeSource { _, _ -> slow.await() }
        val store = store(source)
        store.dispatch(SearchArtifactsIntent.Query("s-1", "budget", enabled = true))
        advanceUntilIdle()
        assertEquals(1, source.calls.size)

        store.dispatch(SearchArtifactsIntent.Query("s-2", "", enabled = true))
        slow.complete(page(listOf("s-1's")))
        advanceUntilIdle()

        assertEquals("s-2", store.state.value.serverId)
        assertEquals(SearchArtifactsStatus.Idle, store.state.value.status)
        assertTrue(store.state.value.hits.isEmpty())
        assertEquals(1, source.calls.size, "no words, no request")
    }

    private fun TestScope.store(source: FakeSource, limit: Int? = null) =
        SharedSearchArtifactsStore(source, backgroundScope, limit = limit)

    private data class Call(val serverId: String, val q: String, val cursor: String?, val limit: Int?, val priority: DataRequestPriority)

    private class FakeSource(
        private val answer: suspend (q: String, cursor: String?) -> WebSearchArtifactsLoad = { _, _ -> page(emptyList()) },
    ) : SearchArtifactsSource {
        val calls = mutableListOf<Call>()

        override suspend fun search(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority): WebSearchArtifactsLoad {
            calls += Call(serverId, q, cursor, limit, priority)
            return answer(q, cursor)
        }
    }

    private companion object {
        fun page(ids: List<String>, next: String? = null): WebSearchArtifactsLoad =
            WebSearchArtifactsLoad.Page(
                hits = ids.map { id -> (parseWebSearchAnswer(hitAnswer(id))!!.artifacts as WebSearchArtifactsGroup.Page).hits.single() },
                nextCursor = next,
            )

        fun hitAnswer(id: String): String =
            """{ "groups": [ { "app": "artifacts", "items": [ { "card": ${artifactsHomeCardJson(id, "Budget $id")}, "matches": [] } ], "nextCursor": null } ] }"""
    }
}
