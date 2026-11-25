package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** web `ArtifactsDashboard.tsx` `useAllArtifacts` / `allReducer` + #16153/#16182/#16137 behaviour, on virtual time. */
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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(events) }

        api.answer = { _, _ -> ArtifactsHomeLoad.Failed(ScreenError("x")) }
        store.dispatch(ArtifactsHomeIntent.SetSort(ArtifactsHomeSort.Updated))
        advanceUntilIdle()

        assertEquals(ArtifactsHomeSort.Opened, store.state.value.query.sort)
        assertEquals(listOf("a"), store.state.value.rows?.map { it.summary.id }, "the rows that sort never replaced stay")
        assertEquals(listOf(ArtifactsHomeSort.Updated, ArtifactsHomeSort.Opened), chosen)
        assertIs<ArtifactsHomeEvent.SortFailed>(events.single())
    }

    @Test
    fun typingWaitsForAPauseAndClearingSearchAppliesImmediately() = runTest {
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

        // Clearing search applies with 0ms debounce.
        store.dispatch(ArtifactsHomeIntent.Search(""))
        runCurrent()
        assertEquals("", store.state.value.query.q)
        assertEquals("", api.calls.last().query.q)
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
    fun catchUpReadsAgainSilentlyKeepingTheRows() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(rows("a")) }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()

        api.answer = { _, _ -> ok(rows("b", "a")) }
        store.dispatch(ArtifactsHomeIntent.CatchUp("other-server"))
        advanceUntilIdle()
        assertEquals(1, api.calls.size, "another server's catch-up is left alone")

        store.dispatch(ArtifactsHomeIntent.CatchUp("s-1"))
        assertEquals(listOf("a"), store.state.value.rows?.map { it.summary.id }, "rows kept while it reads")
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), store.state.value.rows?.map { it.summary.id })
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
    fun anotherServerStartsWithItsOwnPersistedFiltersAndNoCounts() = runTest {
        val prefs = InMemoryPrefStore()
        val filterRepo = ArtifactsHomeFilterRepository(prefs)
        val settledRepo = ArtifactsHomeSettledSeenRepository(prefs)
        val api = FakeDashboardApi()
        val authorId = "11111111-1111-1111-1111-111111111111"
        api.answer = { _, _ -> ok(rows("a"), counts = ArtifactsHomeCounts(3, 3, 0)) }
        val store = store(api, filterRepo = filterRepo, settledRepo = settledRepo)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        store.dispatch(ArtifactsHomeIntent.SetAuthor(actor(authorId, displayName = "Kirby")))
        store.dispatch(ArtifactsHomeIntent.SetPlace(ArtifactsHomePlace.Home(ArtifactsHomeScope.Server)))
        store.dispatch(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.All))
        advanceUntilIdle()

        // Switch to s-2 -> clean defaults; switch back to s-1 -> restores all persisted filters!
        store.dispatch(ArtifactsHomeIntent.Start("s-2", "r-1"))
        assertNull(store.state.value.counts)
        advanceUntilIdle()
        assertEquals(ArtifactsHomeQuery(), store.state.value.query)
        assertEquals(ArtifactsHomeStatusFilter.Unsettled, store.state.value.statusFilter)
        assertNull(store.state.value.authorActor)
        assertEquals("s-2", api.calls.last().serverId)

        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        assertEquals(ArtifactsHomePlace.Home(ArtifactsHomeScope.Server), store.state.value.query.place)
        assertEquals("user:$authorId", store.state.value.query.author)
        assertEquals("Kirby", store.state.value.resolvedAuthorActor?.displayName)
        assertEquals(ArtifactsHomeStatusFilter.All, store.state.value.statusFilter)
        assertEquals("user:$authorId", api.calls.last().query.author)
    }

    @Test
    fun clearAllResetsAllFiveFiltersCancelsSearchDebounceAndRemovesPersistedKey() = runTest {
        val prefs = InMemoryPrefStore()
        val filterRepo = ArtifactsHomeFilterRepository(prefs)
        val api = FakeDashboardApi()
        val store = store(api, filterRepo = filterRepo)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.SetPlace(ArtifactsHomePlace.Home(ArtifactsHomeScope.Public)))
        store.dispatch(ArtifactsHomeIntent.SetAuthor(actor("u-1")))
        store.dispatch(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.Settled))
        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        advanceUntilIdle()

        // Type a search string that is STILL pending debounce, then immediately ClearFilters (#16182).
        val callsBefore = api.calls.size
        store.dispatch(ArtifactsHomeIntent.Search("unfired"))
        store.dispatch(ArtifactsHomeIntent.ClearFilters)
        advanceUntilIdle()

        assertEquals(ArtifactsHomeQuery(), store.state.value.query)
        assertEquals("", store.state.value.search)
        assertEquals(ArtifactsHomeStatusFilter.Unsettled, store.state.value.statusFilter)
        assertNull(store.state.value.authorActor)
        assertFalse(store.state.value.hasToolbarFilter)
        assertFalse(prefs.values.containsKey("raft:artifacts:filters:v1:r-1:s-1"))
        // No spurious request carrying q="unfired" should have fired.
        assertTrue(api.calls.drop(callsBefore).none { it.query.q == "unfired" })

        // Empty-state ClearSearchAndFilters also clears all five filters including relevant & status (#16182).
        store.dispatch(ArtifactsHomeIntent.Search("plan"))
        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        store.dispatch(ArtifactsHomeIntent.SetStatus(ArtifactsHomeStatusFilter.All))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.ClearSearchAndFilters)
        advanceUntilIdle()
        assertEquals(ArtifactsHomeQuery(), store.state.value.query)
        assertEquals("", store.state.value.search)
        assertEquals(ArtifactsHomeStatusFilter.Unsettled, store.state.value.statusFilter)
    }

    @Test
    fun emptyReloadPreservesPersistedAuthorAndSettledEnabledWithoutExtraAuthorsCall() = runTest {
        val prefs = InMemoryPrefStore()
        val filterRepo = ArtifactsHomeFilterRepository(prefs)
        val settledRepo = ArtifactsHomeSettledSeenRepository(prefs)
        val kirby = actor("11111111-1111-1111-1111-111111111111", displayName = "Kirby")
        filterRepo.write(
            "r-1",
            "s-1",
            ArtifactsHomeFilters(
                search = "no-match",
                author = kirby.filterValue,
                authorActor = kirby,
                status = ArtifactsHomeStatusFilter.All,
            ),
        )
        settledRepo.observe("r-1", "s-1", detected = true, rowsLoaded = true)

        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(emptyList()) }
        var authorsCalls = 0
        api.authorsAnswer = {
            authorsCalls++
            ArtifactsHomeLoad.Ok(emptyList())
        }

        val store = store(api, filterRepo = filterRepo, settledRepo = settledRepo)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()

        assertEquals("Kirby", store.state.value.resolvedAuthorActor?.displayName)
        assertTrue(store.state.value.settledEnabled, "settled-seen survives empty reload so Status picker stays visible")
        assertEquals(0, authorsCalls, "persisted authorActor avoids redundant dashboardAuthors request")
    }

    @Test
    fun legacyPersistedAuthorWithoutActorResolvesViaAuthorsAndLiveMetadataWinsOverPersisted() = runTest {
        val prefs = InMemoryPrefStore()
        val filterRepo = ArtifactsHomeFilterRepository(prefs)
        val legacyId = "22222222-2222-2222-2222-222222222222"
        val liveId = "33333333-3333-3333-3333-333333333333"
        // Simulate legacy entry without authorActor.
        prefs.values["raft:artifacts:filters:v1:r-1:s-1"] = """{"author":"user:$legacyId"}"""

        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(emptyList()) }
        val resolvedLegacy = ArtifactsHomeActor("user", legacyId, "legacy", "Legacy Person")
        api.authorsAnswer = { ArtifactsHomeLoad.Ok(listOf(resolvedLegacy)) }

        val store = store(api, filterRepo = filterRepo)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        assertEquals("Legacy Person", store.state.value.resolvedAuthorActor?.displayName)

        // Now test that live row metadata beats a stale persisted authorActor (never downgrades).
        val stalePersisted = ArtifactsHomeActor("user", liveId, "old", "Old Name")
        val freshFromRows = ArtifactsHomeActor("user", liveId, "new", "Fresh Name", avatarUrl = "https://cdn.raft.build/new.png")
        filterRepo.write("r-1", "s-2", ArtifactsHomeFilters(author = "user:$liveId", authorActor = stalePersisted))
        api.answer = { _, _ -> ok(listOf(row("x", author = freshFromRows))) }
        store.dispatch(ArtifactsHomeIntent.Start("s-2", "r-1"))
        advanceUntilIdle()
        assertEquals("Fresh Name", store.state.value.resolvedAuthorActor?.displayName)
        assertEquals("https://cdn.raft.build/new.png", store.state.value.resolvedAuthorActor?.avatarUrl)
    }

    @Test
    fun serverSwitchDoesNotLeakSettledEnabledFromPreviousServer() = runTest {
        val prefs = InMemoryPrefStore()
        val settledRepo = ArtifactsHomeSettledSeenRepository(prefs)
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(listOf(row("a", settledShown = true))) }
        val store = store(api, settledRepo = settledRepo)
        store.dispatch(ArtifactsHomeIntent.Start("s-on", "r-1"))
        advanceUntilIdle()
        assertTrue(store.state.value.settledEnabled)

        // Switch to a server where settled is OFF.
        api.answer = { _, _ -> ok(listOf(row("b", settledShown = false))) }
        store.dispatch(ArtifactsHomeIntent.Start("s-off", "r-1"))
        assertFalse(store.state.value.settledEnabled, "reset before new server rows land")
        advanceUntilIdle()
        assertFalse(store.state.value.settledEnabled)
    }

    @Test
    fun setSettledIsSingleFlightPatchesImmediatelyRefreshesAndReportsErrors() = runTest {
        val api = FakeDashboardApi()
        val settledObj = ArtifactSettled(actor("u-1"), "2026-11-20T09:00:00.000Z", 1)
        api.answer = { _, _ -> ok(listOf(row("a", settledShown = true))) }
        val settleGate = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomeSettlement>>()
        var settleCalls = 0
        api.settleAnswer = { _, _, _ ->
            settleCalls++
            settleGate.await()
        }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        val events = mutableListOf<ArtifactsHomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(events) }

        // Two rapid settle clicks -> single-flight (only 1 network call).
        store.dispatch(ArtifactsHomeIntent.SetSettled("a", true))
        store.dispatch(ArtifactsHomeIntent.SetSettled("a", true))
        runCurrent()
        assertEquals(1, settleCalls)
        assertTrue("a" in store.state.value.settlingIds)

        // Complete settle -> immediate row patch + silent refresh on page 1.
        val pageCallsBefore = api.calls.size
        api.answer = { _, _ -> ok(listOf(row("a", settled = settledObj, settledShown = true))) }
        settleGate.complete(ArtifactsHomeLoad.Ok(ArtifactsHomeSettlement(settledObj)))
        advanceUntilIdle()
        assertFalse("a" in store.state.value.settlingIds)
        assertEquals(settledObj, store.state.value.rows?.single()?.summary?.settled)
        assertEquals(pageCallsBefore + 1, api.calls.size, "silent refresh after settle when not extended")

        // Failure emits SettleFailed with forbidden flag.
        api.settleAnswer = { _, _, _ -> ArtifactsHomeLoad.Failed(ScreenError("nope", serverCode = "FORBIDDEN")) }
        store.dispatch(ArtifactsHomeIntent.SetSettled("a", false))
        advanceUntilIdle()
        val failEvent = assertIs<ArtifactsHomeEvent.SettleFailed>(events.single())
        assertTrue(failEvent.forbidden)
    }

    @Test
    fun clearAndServerSwitchCancelMultipleInFlightSettleJobsCleanly() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(listOf(row("a", settledShown = true), row("b", settledShown = true))) }
        val cancelledIds = mutableListOf<String>()
        val gate = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomeSettlement>>()
        api.settleAnswer = { _, artifactId, _ ->
            try {
                gate.await()
            } finally {
                cancelledIds += artifactId
            }
        }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()

        store.dispatch(ArtifactsHomeIntent.SetSettled("a", true))
        store.dispatch(ArtifactsHomeIntent.SetSettled("b", true))
        runCurrent()
        assertEquals(setOf("a", "b"), store.state.value.settlingIds)

        // Switching servers cancels all in-flight settle jobs without ConcurrentModificationException.
        store.dispatch(ArtifactsHomeIntent.Start("s-2", "r-1"))
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), cancelledIds.toSet())
        assertEquals(emptySet(), store.state.value.settlingIds)

        // Clear cancels multiple in-flight settle jobs cleanly as well.
        cancelledIds.clear()
        store.dispatch(ArtifactsHomeIntent.SetSettled("a", true))
        store.dispatch(ArtifactsHomeIntent.SetSettled("b", true))
        runCurrent()
        store.clear()
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), cancelledIds.toSet())
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

    /** Review H2: an answer for the server the page left never shows under the next one. */
    @Test
    fun aLateAnswerFromTheServerLeftIsDropped() = runTest {
        val api = FakeDashboardApi()
        val late = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomePage>>()
        api.answer = { _, _ -> late.await() }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("server-a"))
        runCurrent()

        store.dispatch(ArtifactsHomeIntent.Start(null))
        late.complete(ok(rows("from-a"), counts = ArtifactsHomeCounts(9, 9, 1)))
        advanceUntilIdle()

        assertNull(store.state.value.rows)
        assertNull(store.state.value.counts)
        assertEquals(ArtifactsHomeStatus.Idle, store.state.value.status)

        api.answer = { _, _ -> ok(rows("from-b")) }
        store.dispatch(ArtifactsHomeIntent.Start("server-b"))
        advanceUntilIdle()
        assertEquals(listOf("from-b"), store.state.value.rows?.map { it.summary.id })
    }

    /** Review M2: a filter that fails is the failed state; it doesn't put the sort back or say the sort failed. */
    @Test
    fun aFailedFilterReadLeavesTheSortAlone() = runTest {
        val api = FakeDashboardApi()
        val chosen = mutableListOf<ArtifactsHomeSort>()
        val store = started(api, onSortChosen = { chosen += it })
        val events = mutableListOf<ArtifactsHomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(events) }

        api.answer = { query, _ -> if (query.relevant) ArtifactsHomeLoad.Failed(ScreenError("x")) else ok(rows("a")) }
        store.dispatch(ArtifactsHomeIntent.SetSort(ArtifactsHomeSort.Updated))
        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        advanceUntilIdle()

        assertEquals(ArtifactsHomeStatus.Failed, store.state.value.status)
        assertEquals(ArtifactsHomeSort.Updated, store.state.value.query.sort)
        assertEquals(listOf(ArtifactsHomeSort.Updated), chosen)
        assertEquals(emptyList(), events)
    }

    /** Review L2: a page that lists one twice still shows it once. */
    @Test
    fun aPageWithARepeatShowsItOnce() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, cursor -> if (cursor == null) ok(rows("a"), next = "c1") else ok(rows("b", "b", "a")) }
        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()
        store.dispatch(ArtifactsHomeIntent.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), store.state.value.rows?.map { it.summary.id })
    }

    @Test
    fun setFollowingIsSingleFlightPatchesRowsEmitsEventsAndNotifiesCallback() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(listOf(row("a", following = true), row("b", following = false))) }
        val gate = CompletableDeferred<ArtifactsHomeLoad<Boolean>>()
        var followCalls = 0
        api.followAnswer = { _, _, _ ->
            followCalls++
            gate.await()
        }
        val committed = mutableListOf<Triple<String, String, Boolean>>()
        val store = store(api, onOwnFollowCommitted = { srv, id, f -> committed += Triple(srv, id, f) })
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        val events = mutableListOf<ArtifactsHomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(events) }

        // Rapid double click is single-flight.
        store.dispatch(ArtifactsHomeIntent.SetFollowing("a", false))
        store.dispatch(ArtifactsHomeIntent.SetFollowing("a", false))
        runCurrent()
        assertEquals(1, followCalls)
        assertTrue("a" in store.state.value.followingIds)

        gate.complete(ArtifactsHomeLoad.Ok(false))
        advanceUntilIdle()
        assertFalse("a" in store.state.value.followingIds)
        assertEquals(false, store.state.value.rows?.first { it.summary.id == "a" }?.summary?.following)
        assertEquals(listOf(Triple("s-1", "a", false)), committed)
        assertEquals(listOf<ArtifactsHomeEvent>(ArtifactsHomeEvent.Unfollowed("a")), events)

        // Follow emits Followed.
        api.followAnswer = { _, _, next -> ArtifactsHomeLoad.Ok(next) }
        store.dispatch(ArtifactsHomeIntent.SetFollowing("a", true))
        advanceUntilIdle()
        assertEquals(true, store.state.value.rows?.first { it.summary.id == "a" }?.summary?.following)
        assertEquals(ArtifactsHomeEvent.Followed("a"), events.last())

        // Failed follow emits FollowFailed with traceId.
        api.followAnswer = { _, _, _ -> ArtifactsHomeLoad.Failed(ScreenError("err", traceId = "tr-9")) }
        store.dispatch(ArtifactsHomeIntent.SetFollowing("a", false))
        advanceUntilIdle()
        val failed = assertIs<ArtifactsHomeEvent.FollowFailed>(events.last())
        assertEquals("tr-9", failed.error?.traceId)
    }

    @Test
    fun unfollowingUnderRelevantRetainsItemAcrossBackgroundRefreshesAndClearsOnReload() = runTest {
        val api = FakeDashboardApi()
        val rowTop = row("top", following = true)
        val rowA = row("a", following = true, news = ArtifactsHomeNews(mentioned = false, newerVersion = true, newComments = 2))
        val rowMentioned = row("m", following = true, news = ArtifactsHomeNews(mentioned = true, newerVersion = true, newComments = 1))
        val rowB = row("b", following = true)
        api.answer = { _, _ -> ok(listOf(rowTop, rowA, rowMentioned, rowB)) }
        api.followAnswer = { _, _, next -> ArtifactsHomeLoad.Ok(next) }

        val store = store(api)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        advanceUntilIdle()
        val initialGen = store.state.value.reloadGeneration

        // Unfollow `a` (follow-only) and `m` (mentioned) under Relevant to me: both stay in rows and enter unfollowedIds.
        store.dispatch(ArtifactsHomeIntent.SetFollowing("a", false))
        store.dispatch(ArtifactsHomeIntent.SetFollowing("m", false))
        advanceUntilIdle()
        val afterUnfollow = store.state.value
        assertEquals(listOf("top", "a", "m", "b"), afterUnfollow.rows?.map { it.summary.id })
        assertEquals(setOf("a", "m"), afterUnfollow.unfollowedIds)
        assertEquals(false, afterUnfollow.rows!!.first { it.summary.id == "a" }.summary.following)

        // Background refresh (#16397, keepRows = true) where server omits `a` (follow-only) and returns `m` with stale `following = true`:
        // - `a` stays in place right after `top` with non-mention news cleared (web `mergeRetainedUnfollowed`)
        // - `m` keeps local `following = false` and preserves mention news (web `withOwnFollows`)
        api.answer = { _, _ ->
            ok(
                listOf(
                    rowTop,
                    row("m", following = true, news = ArtifactsHomeNews(mentioned = true, newerVersion = true, newComments = 4)),
                    rowB,
                ),
            )
        }
        store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1"))
        advanceUntilIdle()
        val afterBg = store.state.value
        assertEquals(initialGen, afterBg.reloadGeneration, "background refresh must not advance reloadGeneration")
        assertEquals(listOf("top", "a", "m", "b"), afterBg.rows?.map { it.summary.id })
        assertEquals(setOf("a", "m"), afterBg.unfollowedIds)
        val restoredA = afterBg.rows!![1]
        assertEquals(false, restoredA.summary.following)
        assertEquals(ArtifactsHomeNews(), restoredA.news, "follow-only omitted item clears stale news on restore")
        val keptM = afterBg.rows!![2]
        assertEquals(false, keptM.summary.following)
        assertTrue(keptM.news.mentioned)

        // Undo re-follows `a`, removes from unfollowedIds, and stays retained even if an immediate background refresh omits it.
        store.dispatch(ArtifactsHomeIntent.SetFollowing("a", true))
        advanceUntilIdle()
        assertFalse("a" in store.state.value.unfollowedIds)
        assertEquals(true, store.state.value.rows!!.first { it.summary.id == "a" }.summary.following)

        api.answer = { _, _ -> ok(listOf(rowTop, rowB)) }
        store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1"))
        advanceUntilIdle()
        assertEquals(listOf("top", "a", "m", "b"), store.state.value.rows?.map { it.summary.id })
        assertEquals(true, store.state.value.rows!![1].summary.following)

        // Unfollow `a` again, then trigger explicit filter reload (keepRows = false): retention clears & server omission applies.
        store.dispatch(ArtifactsHomeIntent.SetFollowing("a", false))
        advanceUntilIdle()
        assertTrue("a" in store.state.value.unfollowedIds)

        store.dispatch(ArtifactsHomeIntent.SetRelevant(false))
        advanceUntilIdle()
        assertTrue(store.state.value.reloadGeneration > initialGen)
        assertEquals(emptySet(), store.state.value.unfollowedIds)
        assertEquals(listOf("top", "b"), store.state.value.rows?.map { it.summary.id })
    }

    @Test
    fun externalOwnFollowEventUpdatesHomeRowsAndRetainsUnfollowedUnderRelevant() = runTest {
        val api = FakeDashboardApi()
        api.answer = { _, _ -> ok(listOf(row("a", following = true), row("b", following = true))) }
        val ownFollows = MutableSharedFlow<ArtifactOwnFollowEvent>(extraBufferCapacity = 8)
        val store = store(api, ownFollowEvents = ownFollows)
        store.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        store.dispatch(ArtifactsHomeIntent.SetRelevant(true))
        advanceUntilIdle()

        // Unfollowed from a chat message card on the same server.
        ownFollows.emit(ArtifactOwnFollowEvent(serverId = "s-1", artifactId = "a", following = false, seq = 1L))
        advanceUntilIdle()
        assertEquals(false, store.state.value.rows!!.first { it.summary.id == "a" }.summary.following)
        assertTrue("a" in store.state.value.unfollowedIds)

        // Event for another server is ignored.
        ownFollows.emit(ArtifactOwnFollowEvent(serverId = "s-other", artifactId = "b", following = false, seq = 2L))
        advanceUntilIdle()
        assertEquals(true, store.state.value.rows!!.first { it.summary.id == "b" }.summary.following)
        assertFalse("b" in store.state.value.unfollowedIds)

        // Background refresh omitting `a` still retains it because the external unfollow registered it.
        api.answer = { _, _ -> ok(listOf(row("b", following = true))) }
        store.dispatch(ArtifactsHomeIntent.ActivityChanged("s-1"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), store.state.value.rows?.map { it.summary.id })
        assertTrue("a" in store.state.value.unfollowedIds)
    }

    @Test
    fun snapshotCacheSeedsFrameZeroRefreshesKeepingRowsAndIsolatesByServerReaderEnvAndQuery() = runTest {
        val cache = ArtifactsHomeSnapshotCache(maxEntries = 2)
        val api1 = FakeDashboardApi()
        api1.answer = { _, _ -> ok(rows("a"), next = "c1", counts = ArtifactsHomeCounts(4, 4, 1)) }
        val first =
            store(
                api = api1,
                snapshotCache = cache,
                environmentIdProvider = { "https://api.example" },
                initialServerId = "s-1",
                initialReaderId = "r-1",
            )
        assertNull(first.state.value.rows)
        first.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        advanceUntilIdle()
        assertEquals(listOf("a"), first.state.value.rows?.map { it.summary.id })

        // Reopening Artifacts Home on a fresh store renders cached rows on Frame 0 while refreshing.
        val gate = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomePage>>()
        val api2 = FakeDashboardApi()
        api2.answer = { _, _ -> gate.await() }
        val reopened =
            store(
                api = api2,
                snapshotCache = cache,
                environmentIdProvider = { "https://api.example" },
                initialServerId = "s-1",
                initialReaderId = "r-1",
            )
        assertEquals(ArtifactsHomeStatus.Ok, reopened.state.value.status)
        assertEquals(listOf("a"), reopened.state.value.rows?.map { it.summary.id })
        assertEquals(4, reopened.state.value.counts?.total)

        reopened.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        runCurrent()
        assertEquals(listOf("a"), reopened.state.value.rows?.map { it.summary.id }, "cached rows stay visible while refreshing")
        assertEquals(1, api2.calls.size)
        gate.complete(ok(rows("b", "a"), counts = ArtifactsHomeCounts(5, 5, 1)))
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), reopened.state.value.rows?.map { it.summary.id })

        // Strict isolation by server, reader, environment, and query.
        val otherServer =
            store(
                api = FakeDashboardApi(),
                snapshotCache = cache,
                environmentIdProvider = { "https://api.example" },
                initialServerId = "s-2",
                initialReaderId = "r-1",
            )
        assertNull(otherServer.state.value.rows)
        val otherReader =
            store(
                api = FakeDashboardApi(),
                snapshotCache = cache,
                environmentIdProvider = { "https://api.example" },
                initialServerId = "s-1",
                initialReaderId = "r-2",
            )
        assertNull(otherReader.state.value.rows)
        val otherEnv =
            store(
                api = FakeDashboardApi(),
                snapshotCache = cache,
                environmentIdProvider = { "https://staging.example" },
                initialServerId = "s-1",
                initialReaderId = "r-1",
            )
        assertNull(otherEnv.state.value.rows)

        // Switching servers inside a live store restores cached rows when returning to s-1.
        api2.answer = { _, _ -> ok(rows("s2-row")) }
        reopened.dispatch(ArtifactsHomeIntent.Start("s-2", "r-1"))
        advanceUntilIdle()
        assertEquals(listOf("s2-row"), reopened.state.value.rows?.map { it.summary.id })

        val backGate = CompletableDeferred<ArtifactsHomeLoad<ArtifactsHomePage>>()
        api2.answer = { _, _ -> backGate.await() }
        reopened.dispatch(ArtifactsHomeIntent.Start("s-1", "r-1"))
        runCurrent()
        assertEquals(listOf("b", "a"), reopened.state.value.rows?.map { it.summary.id }, "returning to s-1 paints cached rows immediately")
        backGate.complete(ArtifactsHomeLoad.Off)
        advanceUntilIdle()
        assertEquals(ArtifactsHomeStatus.Off, reopened.state.value.status)

        // Off drops s-1 entries; s-2 remains until dropServer / clear.
        val afterOff =
            store(
                api = FakeDashboardApi(),
                snapshotCache = cache,
                environmentIdProvider = { "https://api.example" },
                initialServerId = "s-1",
                initialReaderId = "r-1",
            )
        assertNull(afterOff.state.value.rows)
        cache.dropServer("s-2")
        val afterDrop =
            store(
                api = FakeDashboardApi(),
                snapshotCache = cache,
                environmentIdProvider = { "https://api.example" },
                initialServerId = "s-2",
                initialReaderId = "r-1",
            )
        assertNull(afterDrop.state.value.rows)
    }

    private fun TestScope.store(
        api: FakeDashboardApi,
        onSortChosen: (ArtifactsHomeSort) -> Unit = {},
        filterRepo: ArtifactsHomeFilterRepository? = null,
        settledRepo: ArtifactsHomeSettledSeenRepository? = null,
        ownFollowEvents: SharedFlow<ArtifactOwnFollowEvent>? = null,
        onOwnFollowCommitted: (String, String, Boolean) -> Unit = { _, _, _ -> },
        snapshotCache: ArtifactsHomeSnapshotCache? = null,
        environmentIdProvider: () -> String = { "" },
        initialServerId: String? = null,
        initialReaderId: String? = null,
    ) =
        SharedArtifactsHomeStore(
            api = api,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            onSortChosen = onSortChosen,
            filterRepository = filterRepo,
            settledSeenRepository = settledRepo,
            ownFollowEvents = ownFollowEvents,
            onOwnFollowCommitted = onOwnFollowCommitted,
            snapshotCache = snapshotCache,
            environmentIdProvider = environmentIdProvider,
            initialServerId = initialServerId,
            initialReaderId = initialReaderId,
        )

    private fun TestScope.started(api: FakeDashboardApi, onSortChosen: (ArtifactsHomeSort) -> Unit = {}): SharedArtifactsHomeStore {
        val store = store(api, onSortChosen)
        store.dispatch(ArtifactsHomeIntent.Start("s-1"))
        advanceUntilIdle()
        return store
    }

    private data class Call(val serverId: String, val query: ArtifactsHomeQuery, val cursor: String?, val priority: DataRequestPriority)

    private class InMemoryPrefStore : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeDashboardApi : ArtifactsHomeSource {
        val calls = mutableListOf<Call>()
        var answer: suspend (ArtifactsHomeQuery, String?) -> ArtifactsHomeLoad<ArtifactsHomePage> = { _, _ -> ok(rows("a")) }
        var authorsAnswer: suspend (String) -> ArtifactsHomeLoad<List<ArtifactsHomeActor>> = { ArtifactsHomeLoad.Ok(emptyList()) }
        var conversationsAnswer: suspend (String) -> ArtifactsHomeLoad<List<ArtifactsHomeConversation>> = { ArtifactsHomeLoad.Ok(emptyList()) }
        var settleAnswer: suspend (String, String, Boolean) -> ArtifactsHomeLoad<ArtifactsHomeSettlement> = { _, _, _ ->
            ArtifactsHomeLoad.Ok(ArtifactsHomeSettlement(null))
        }
        var followAnswer: suspend (String, String, Boolean) -> ArtifactsHomeLoad<Boolean> = { _, _, next ->
            ArtifactsHomeLoad.Ok(next)
        }

        override suspend fun page(serverId: String, query: ArtifactsHomeQuery, cursor: String?, priority: DataRequestPriority): ArtifactsHomeLoad<ArtifactsHomePage> {
            calls += Call(serverId, query, cursor, priority)
            return answer(query, cursor)
        }

        override suspend fun authors(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeActor>> = authorsAnswer(q)

        override suspend fun conversations(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeConversation>> = conversationsAnswer(q)

        override suspend fun setSettled(serverId: String, artifactId: String, settled: Boolean): ArtifactsHomeLoad<ArtifactsHomeSettlement> =
            settleAnswer(serverId, artifactId, settled)

        override suspend fun setFollowing(serverId: String, artifactId: String, following: Boolean): ArtifactsHomeLoad<Boolean> =
            followAnswer(serverId, artifactId, following)
    }
}

private fun actor(id: String, displayName: String = id.uppercase()) =
    ArtifactsHomeActor(type = "user", id = id, name = id, displayName = displayName)

private fun row(
    id: String,
    author: ArtifactsHomeActor = actor("u-1"),
    settled: ArtifactSettled? = null,
    settledShown: Boolean = false,
    following: Boolean? = null,
    news: ArtifactsHomeNews = ArtifactsHomeNews(),
) =
    ArtifactsHomeRow(
        summary =
            ArtifactSummary(
                id = id,
                title = id,
                description = null,
                kind = ArtifactKind.Html,
                version = 1,
                pinned = false,
                latestVersion = 1,
                home = ArtifactHome.Server,
                settled = settled,
                settledShown = settledShown,
                following = following,
            ),
        author = author,
        openedAt = null,
        news = news,
    )

private fun rows(vararg ids: String) = ids.map { row(it) }

private fun ok(rows: List<ArtifactsHomeRow>, next: String? = null, counts: ArtifactsHomeCounts? = null): ArtifactsHomeLoad<ArtifactsHomePage> =
    ArtifactsHomeLoad.Ok(ArtifactsHomePage(rows, next, counts))
