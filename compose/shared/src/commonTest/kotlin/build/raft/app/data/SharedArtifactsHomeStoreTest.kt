package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** web `ArtifactsDashboard.tsx` `useAllArtifacts` / `allReducer` behaviour, on virtual time. */
class SharedArtifactsHomeStoreTest {
    @Test
    fun startReadsTheFirstPageWithTheSkeletonFirstAndCountsAfter() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(rows("a", "b"), next = "c1", counts = ArtifactsHomeCounts(24, 24, 2)) }
        val store = store(api)

        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        assertEquals(ArtifactsHomeStatus.Loading, store.state.value.status)
        assertNull(store.state.value.rows, "skeleton while the first page loads")
        advanceUntilIdle()

        val state = store.state.value
        assertEquals(ArtifactsHomeStatus.Ok, state.status)
        assertEquals(listOf("a", "b"), state.rows!!.map { it.summary.id })
        assertEquals("c1", state.nextCursor)
        assertEquals(24, state.counts!!.total)
        assertTrue(state.rowsByOpened, "the default sort is recently opened")
        assertEquals(listOf(Call("s-1", ArtifactsHomeQuery(), null, DataRequestPriority.Foreground)), api.calls)
    }

    @Test
    fun aNewFilterShowsTheSkeletonButAnotherSortKeepsTheRows() = runTest {
        val api = FakeDashboardApi()
        val store = started(api)

        store.dispatch(ArtifactsHomeIntent.SetSort(ArtifactsHomeSort.Updated))
        assertEquals(listOf("a"), store.state.value.rows?.map { it.summary.id }, "same filter, another sort: rows stay")
        advanceUntilIdle()
        assertEquals(false, store.state.value.rowsByOpened)

        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        assertNull(store.state.value.rows, "another filter: back to the skeleton")
        advanceUntilIdle()
        assertEquals(ArtifactsHomeQuery(sort = ArtifactsHomeSort.Updated, relevant = true), api.calls.last().query)
    }

    @Test
    fun aSortThatFailsGoesBackToTheLastAnsweredOne() = runTest {
        val api = FakeDashboardApi()
        val chosen = mutableListOf<ArtifactsHomeSort>()
        val store = started(api, onSortChosen = { chosen += it })
        val events = mutableListOf<ArtifactsHomeEvent>()
        backgroundScope.launch { store.events.toList(events) }

        api.answer = { _, _ -> ArtifactsHomeLoad.Failed(ScreenError("x")) }
        store.dispatch(ArtifactsHomeIntent.SetSort(ArtifactsHomeSort.Updated))
        advanceUntilIdle()

        assertEquals(ArtifactsHomeSort.Opened, store.state.value.query.sort)
        assertEquals(listOf("a"), store.state.value.rows?.map { it.summary.id }, "the rows that sort never replaced stay")
        assertEquals(listOf(ArtifactsHomeSort.Updated, ArtifactsHomeSort.Opened), chosen)
        assertIs<ArtifactsHomeEvent.SortFailed>(events.single())
    }

    @Test
    fun typingWaitsForAPauseAndOnlyTheLatestAnswerLands() = runTest {
        val api = FakeDashboardApi()
        val store = started(api)
        val slow = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomePage>>()
        api.answer = { query, _ -> if (query.q == "pl") slow.await() else ok(rows("plan")) }

        store.dispatch(ArtifactsHomeIntent.Search("pl"))
        advanceTimeBy(SharedArtifactsHomeStore.SEARCH_DEBOUNCE_MILLIS - 1)
        assertEquals(1, api.calls.size, "no read before the pause")
        advanceTimeBy(2)
        runCurrent()
        assertEquals("pl", api.calls.last().query.q)

        store.dispatch(ArtifactsHomeIntent.Search("plan"))
        advanceTimeBy(SharedArtifactsHomeStore.SEARCH_DEBOUNCE_MILLIS + 1)
        runCurrent()
        slow.complete(ok(rows("stale")))
        advanceUntilIdle()

        assertEquals("plan", store.state.value.query.q)
        assertEquals(listOf("plan"), store.state.value.rows?.map { it.summary.id })
    }

    @Test
    fun loadMoreContinuesTheShownQueryAndListsNoneTwice() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, cursor -> if (cursor == null) ok(rows("a", "b"), next = "c1") else ok(rows("b", "c"), next = null) }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.LoadMore)
        assertTrue(store.state.value.paging)
        advanceUntilIdle()

        val state = store.state.value
        assertEquals(listOf("a", "b", "c"), state.rows!!.map { it.summary.id })
        assertNull(state.nextCursor)
        assertTrue(state.extended)
        assertEquals(Call("s-1", ArtifactsHomeQuery(), "c1", DataRequestPriority.Visible), api.calls.last())
    }

    @Test
    fun aPageForRowsThatWereReplacedIsDropped() = runTest {
        val api = FakeDashboardApi()
        val more = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomePage>>()
        api.answer = { _, cursor -> if (cursor == null) ok(rows("a"), next = "c1") else more.await() }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.LoadMore)
        runCurrent()
        api.answer = { _, _ -> ok(rows("x")) }
        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        advanceUntilIdle()
        more.complete(ok(rows("late")))
        advanceUntilIdle()

        assertEquals(listOf("x"), store.state.value.rows?.map { it.summary.id })
    }

    @Test
    fun activityReadsAgainOnlyWhenNothingWouldBeLost() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, cursor -> if (cursor == null) ok(rows("a"), next = "c1") else ok(rows("b")) }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.ActivityChanged("other-server"))
        advanceUntilIdle()
        assertEquals(1, api.calls.size, "another server's activity is left alone")

        store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1"))
        assertEquals(listOf("a"), store.state.value.rows?.map { it.summary.id }, "rows kept while it reads")
        advanceUntilIdle()
        assertEquals(2, api.calls.size)

        store.dispatch(ArtifactsHomeIntent.ViewerOpened)
        store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1"))
        advanceUntilIdle()
        assertEquals(2, api.calls.size, "none under the viewer")
        store.dispatch(ArtifactsHomeIntent.ViewerClosed)
        advanceUntilIdle()
        assertEquals(3, api.calls.size, "closing the viewer reads again")

        store.dispatch(ArtifactsHomeIntent.LoadMore)
        advanceUntilIdle()
        store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1"))
        advanceUntilIdle()
        assertEquals(4, api.calls.size, "none past the first page (only the Load More)")
    }

    @Test
    fun activityDuringARead_queuesOneMore() = runTest {
        val api = FakeDashboardApi()
        val store = started(api)
        val gate = CompletableDeferred<Unit>()
        api.answer = { _, _ -> gate.await(); ok(rows("a")) }

        repeat(3) { store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1")) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, api.calls.size, "the start, one read, one queued")
    }

    @Test
    fun offIsTheOffStateAndFiltersReadNothing() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ArtifactsHomeLoad.Off }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()
        assertEquals(ArtifactsHomeStatus.Off, store.state.value.status)

        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aFailedReadKeepsTheRowsAndRetryReadsAgain() = runTest {
        val api = FakeDashboardApi()
        val store = started(api)
        api.answer = { _, _ -> ArtifactsHomeLoad.Failed(ScreenError("x", traceId = "t1")) }
        store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1"))
        advanceUntilIdle()
        assertEquals(ArtifactsHomeStatus.Failed, store.state.value.status)
        assertEquals("t1", store.state.value.error?.traceId)
        assertEquals(listOf("a"), store.state.value.rows?.map { it.summary.id })

        api.answer = { _, _ -> ok(rows("b")) }
        store.dispatch(ArtifactsHomeIntent.Retry)
        advanceUntilIdle()
        assertEquals(listOf("b"), store.state.value.rows?.map { it.summary.id })
        assertNull(store.state.value.error)
    }

    @Test
    fun anotherServerStartsWithNoFiltersAndNoCounts() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(rows("a"), counts = ArtifactsHomeCounts(3, 3, 0)) }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()
        store.dispatch(ArtifactsHomeIntent.SetAuthor(actor("u-1")))
        store.dispatch(ArtifactsHomeIntent.SetPlace(ArtifactsHomePlace.Home(ArtifactsHomeScope.Server)))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.Start("s-2"))
        assertNull(store.state.value.counts)
        advanceUntilIdle()

        assertEquals(ArtifactsHomeQuery(), store.state.value.query)
        assertNull(store.state.value.authorActor)
        assertEquals("s-2", api.calls.last().serverId)
    }

    @Test
    fun clearingTakesTheRightThingsAway() = runTest {
        val api = FakeDashboardApi()
        val store = started(api)
        store.dispatch(ArtifactsHomeIntent.Search("plan"))
        store.dispatch(ArtifactsHomeIntent.SetAuthor(actor("u-1")))
        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.ClearSearchAndFilters)
        advanceUntilIdle()
        assertEquals(ArtifactsHomeQuery(relevant = true), store.state.value.query, "the empty state's clear keeps the toggle")
        assertEquals("", store.state.value.search)

        store.dispatch(ArtifactsHomeIntent.SetPlace(ArtifactsHomePlace.Home(ArtifactsHomeScope.Public)))
        store.dispatch(ArtifactsHomeIntent.ClearFilters)
        advanceUntilIdle()
        assertEquals(ArtifactsHomeQuery(), store.state.value.query)
    }

    @Test
    fun filterChoicesAreAskedWhenOpenAndAnOlderServerFallsBackToTheRowsOnScreen() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(listOf(row("a", actor("u-1")), row("b", actor("u-2")), row("c", actor("u-2")))) }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()

        api.conversationsAnswer = { q -> ArtifactsHomeLoad.Ok(listOf(ArtifactsHomeConversation("c-1", "proj-$q", "channel"))) }
        store.dispatch(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Conversation))
        advanceUntilIdle()
        assertEquals(listOf("#proj-"), store.state.value.shownConversationChoices.map { it.label })

        store.dispatch(ArtifactsHomeIntent.ChoicesQuery(ArtifactsHomeChoiceKind.Conversation, "x"))
        assertEquals(ArtifactsHomeChoicesPhase.Pending, store.state.value.conversationChoices.phase)
        advanceTimeBy(SharedArtifactsHomeStore.CHOICES_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("#proj-x"), store.state.value.shownConversationChoices.map { it.label })

        api.authorsAnswer = { _ -> ArtifactsHomeLoad.Missing(ScreenError("404")) }
        store.dispatch(ArtifactsHomeIntent.OpenChoices(ArtifactsHomeChoiceKind.Author))
        advanceUntilIdle()
        assertEquals(ArtifactsHomeChoicesPhase.Missing, store.state.value.authorChoices.phase)
        assertEquals(listOf("user:u-2", "user:u-1"), store.state.value.shownAuthorChoices.map { it.filterValue }, "most frequent first")
    }

    private fun TestScope.store(api: FakeDashboardApi, onSortChosen: (ArtifactsHomeSort) -> Unit = {}) =
        SharedArtifactsHomeStore(api, backgroundScope, onSortChosen = onSortChosen)

    private fun TestScope.started(api: FakeDashboardApi, onSortChosen: (ArtifactsHomeSort) -> Unit = {}): SharedArtifactsHomeStore {
        val store = store(api, onSortChosen)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()
        return store
    }

    private data class Call(val serverId: String, val query: ArtifactsHomeQuery, val cursor: String?, val priority: DataRequestPriority)

    private class FakeDashboardApi : ArtifactsHomeSource {
        val calls = mutableListOf<Call>()
        var answer: suspend (ArtifactsHomeQuery, String?) -> ArtifactsHomeLoad<ArtifactsHomePage> = { _, _ -> ok(rows("a")) }
        var authorsAnswer: suspend (String) -> ArtifactsHomeLoad<List<ArtifactsHomeActor>> = { ArtifactsHomeLoad.Ok(emptyList()) }
        var conversationsAnswer: suspend (String) -> ArtifactsHomeLoad<List<ArtifactsHomeConversation>> = { ArtifactsHomeLoad.Ok(emptyList()) }

        override suspend fun page(serverId: String, query: ArtifactsHomeQuery, cursor: String?, priority: DataRequestPriority): ArtifactsHomeLoad<ArtifactsHomePage> {
            calls += Call(serverId, query, cursor, priority)
            return answer(query, cursor)
        }

        override suspend fun authors(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeActor>> = authorsAnswer(q)

        override suspend fun conversations(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeConversation>> = conversationsAnswer(q)
    }
}

private fun actor(id: String) = ArtifactsHomeActor(type = "user", id = id, name = id, displayName = id.uppercase())

private fun row(id: String, author: ArtifactsHomeActor = actor("u-1")) =
    ArtifactsHomeRow(
        summary = ArtifactSummary(id = id, title = id, description = null, kind = ArtifactKind.Html, version = 1, pinned = false, latestVersion = 1, home = ArtifactHome.Server),
        author = author,
        openedAt = null,
        news = ArtifactsHomeNews(),
    )

private fun rows(vararg ids: String) = ids.map { row(it) }

private fun ok(rows: List<ArtifactsHomeRow>, next: String? = null, counts: ArtifactsHomeCounts? = null): ArtifactsHomeLoad<ArtifactsHomePage> =
    ArtifactsHomeLoad.Ok(ArtifactsHomePage(rows, next, counts))
