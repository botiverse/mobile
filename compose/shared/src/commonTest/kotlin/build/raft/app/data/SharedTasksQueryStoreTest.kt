package build.raft.app.data

import build.raft.app.data.TasksQueryTestFixtures.ViewerId
import build.raft.app.data.TasksQueryTestFixtures.person
import build.raft.app.data.TasksQueryTestFixtures.task
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Tasks page's server query store ([SharedTasksQueryStore]); virtual time throughout. */
class SharedTasksQueryStoreTest {
    private fun TestScope.store(
        repository: FakeTasksQueryRepository,
        preferences: TasksPageQueryPreferenceRepository? = null,
        scope: CoroutineScope = backgroundScope,
    ): SharedTasksQueryStore =
        SharedTasksQueryStore(
            scope = scope,
            repository = repository,
            serverId = { ServerId(TasksQueryTestFixtures.ServerUuid) },
            viewerId = { ViewerId },
            preferences = preferences,
            nowEpochMillis = { 1_794_000_000_000L },
        )

    private fun TasksQueryState.rowIds(): List<String> = sections().flatMap { section -> section.rows.map { it.id.value } }

    @Test
    fun mineReadsItsTwoSectionsAndTheCounts() = runTest {
        val repository = FakeTasksQueryRepository()
        val store = store(repository)
        store.start()
        runCurrent()

        val relations = repository.pageCalls.map { it.filters.relation }
        assertEquals(listOf(ServerTaskRelation.AssignedToMe, ServerTaskRelation.CreatedByMe, ServerTaskRelation.FromMyMessage), relations)
        // Without a status filter Mine is unfinished work; Started by you keeps a day of Done.
        assertEquals(TasksQueryUnfinishedStatuses, repository.pageCalls[0].filters.statuses)
        assertTrue(TaskStatus.Done in repository.pageCalls[1].filters.statuses)
        assertTrue(repository.pageCalls[1].filters.completedSince != null)
        assertTrue(repository.pageCalls.all { it.priority == DataRequestPriority.Visible && it.sort == ServerTaskSort.Activity })
        // The scopes' counts (All once, Mine's three lanes); the view's lanes are the same filter sets.
        assertEquals(4, repository.countCalls.size)
        assertEquals(listOf(TasksQuerySection.Assigned, TasksQuerySection.Started), store.state.value.sections().map { it.section })
    }

    @Test
    fun scopeAndSortAreKeptAndReadBack() = runTest {
        val preferences = InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All)
        val repository = FakeTasksQueryRepository()
        val store = store(repository, preferences)
        store.start()
        runCurrent()
        assertEquals(listOf(null), repository.pageCalls.map { it.filters.relation })

        store.dispatch(TasksQueryIntent.SetSort(ServerTaskSort.Created))
        runCurrent()
        assertEquals(ServerTaskSort.Created, preferences.savedSort)
        assertEquals(ServerTaskSort.Created, repository.pageCalls.last().sort)

        // The same sort again is no read.
        val reads = repository.pageCalls.size
        store.dispatch(TasksQueryIntent.SetSort(ServerTaskSort.Created))
        runCurrent()
        assertEquals(reads, repository.pageCalls.size)
    }

    @Test
    fun searchShowsAtOnceAndReadsOncePaused() = runTest {
        val repository = FakeTasksQueryRepository()
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()
        val before = repository.pageCalls.size

        store.dispatch(TasksQueryIntent.SetSearch("sh"))
        advanceTimeBy(100)
        store.dispatch(TasksQueryIntent.SetSearch("ship"))
        assertEquals("ship", store.state.value.searchDraft)
        advanceTimeBy(SharedTasksQueryStore.SearchDebounceMs - 1)
        runCurrent()
        assertEquals(before, repository.pageCalls.size, "the search waits for typing to pause")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("ship"), repository.pageCalls.drop(before).map { it.filters.query })

        // Emptied, it reads at once.
        store.dispatch(TasksQueryIntent.SetSearch(""))
        runCurrent()
        assertEquals("", repository.pageCalls.last().filters.query)
    }

    @Test
    fun aResponseToAnOlderQueryIsDropped() = runTest {
        val slow = CompletableDeferred<TasksPageResult>()
        val repository = FakeTasksQueryRepository()
        repository.page = { call ->
            if (call.filters.query == "old") slow.await() else TasksPageResult.Page(listOf(task("new")), null)
        }
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.dispatch(TasksQueryIntent.SetSearch("old"))
        advanceTimeBy(SharedTasksQueryStore.SearchDebounceMs + 1)
        store.start()
        runCurrent()
        store.dispatch(TasksQueryIntent.SetSearch("new"))
        advanceTimeBy(SharedTasksQueryStore.SearchDebounceMs + 1)
        runCurrent()
        assertEquals(listOf("new"), store.state.value.rowIds())

        slow.complete(TasksPageResult.Page(listOf(task("old")), null))
        runCurrent()
        assertEquals(listOf("new"), store.state.value.rowIds(), "the older query's page never lands")
    }

    @Test
    fun aFailedReadKeepsTheRowsAndCarriesTheTraceId() = runTest {
        val traceId = "0af7651916cd43dd8448eb211c80319c"
        val repository = FakeTasksQueryRepository(page = { TasksPageResult.Page(listOf(task("a"), task("b")), null) })
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()
        assertEquals(listOf("a", "b"), store.state.value.rowIds())

        repository.page = { TasksPageResult.Error(code = "tasks_page_http_500", traceId = traceId) }
        store.dispatch(TasksQueryIntent.ToggleStatus(TaskStatus.Todo))
        runCurrent()
        assertEquals(listOf("a", "b"), store.state.value.rowIds())
        assertEquals(traceId, store.state.value.error?.traceId)
        assertEquals("tasks_page_http_500", store.state.value.error?.code)
    }

    @Test
    fun pagesFollowTheCursorOneReadAtATime() = runTest {
        val repository = FakeTasksQueryRepository()
        repository.page = { call ->
            if (call.cursor == null) TasksPageResult.Page(listOf(task("a")), "next") else TasksPageResult.Page(listOf(task("b")), null)
        }
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()
        store.dispatch(TasksQueryIntent.LoadMore)
        store.dispatch(TasksQueryIntent.LoadMore)
        runCurrent()
        assertEquals(listOf(null, "next"), repository.pageCalls.map { it.cursor })
        assertEquals(listOf("a", "b"), store.state.value.rowIds())
        assertEquals(false, store.state.value.sections().single().hasMore)
    }

    @Test
    fun liveChangesPatchLeaveAndRereadTheLanes() = runTest {
        val repository = FakeTasksQueryRepository(page = { TasksPageResult.Page(listOf(task("a"), task("b")), null) })
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.dispatch(TasksQueryIntent.ToggleStatus(TaskStatus.Todo))
        store.start()
        runCurrent()
        store.onProjection(listOf(task("a"), task("b")), bulk = false)
        val reads = repository.pageCalls.size

        // a is retitled (patched), b moves to Done (leaves the Todo lane).
        store.onProjection(listOf(task("a", title = "Renamed", revision = 2), task("b", status = TaskStatus.Done, revision = 2)), bulk = false)
        assertEquals(listOf("a"), store.state.value.rowIds())
        assertEquals("Renamed", store.state.value.tasks["a"]?.title)
        advanceTimeBy(SharedTasksQueryStore.LiveRereadDelayMs + 1)
        runCurrent()
        assertEquals(reads, repository.pageCalls.size, "nothing new to place: no read")

        // A new Todo task the lane doesn't list: the lane reads its rows again, in the background.
        store.onProjection(listOf(task("a", title = "Renamed", revision = 2), task("b", status = TaskStatus.Done, revision = 2), task("c")), bulk = false)
        advanceTimeBy(SharedTasksQueryStore.LiveRereadDelayMs + 1)
        runCurrent()
        assertEquals(DataRequestPriority.Background, repository.pageCalls.drop(reads).single().priority)
    }

    @Test
    fun anOlderProjectionRowNeverReplacesThePagesRow() = runTest {
        val repository = FakeTasksQueryRepository(page = { TasksPageResult.Page(listOf(task("a", title = "New", revision = 5)), null) })
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()
        store.onProjection(listOf(task("a", title = "Old", revision = 3)), bulk = true)
        assertEquals("New", store.state.value.tasks["a"]?.title)
    }

    @Test
    fun startedByYouMergesItsLanesByTheSortUpToTheEarliestPageEnd() {
        val doneSince = "2026-11-11T00:00:00Z"
        val query = TasksPageQuery(scope = TasksPageScope.Mine)
        val lanes = tasksQueryLanes(query, doneSince)
        val me = person(ViewerId)
        val created = lanes[1]
        val fromMessage = lanes[2]
        val state =
            TasksQueryState(
                query = query,
                started = true,
                viewerId = ViewerId,
                doneSince = doneSince,
                lanes = lanes,
                laneStates =
                    mapOf(
                        lanes[0].key to TasksLaneState(loaded = true),
                        // created_by_me: two rows, more to read after 10:00.
                        created.key to TasksLaneState(ids = listOf("c1", "c2"), nextCursor = "more", loaded = true),
                        fromMessage.key to TasksLaneState(ids = listOf("m1", "m2"), loaded = true),
                    ),
                tasks =
                    mapOf(
                        "c1" to task("c1", activityAt = "2026-11-12T12:00:00.000Z", creator = me),
                        "c2" to task("c2", activityAt = "2026-11-12T10:00:00.000Z", creator = me),
                        "m1" to task("m1", activityAt = "2026-11-12T11:00:00.000Z"),
                        "m2" to task("m2", activityAt = "2026-11-12T09:00:00.000Z"),
                    ),
            )
        val started = state.sections().single { it.section == TasksQuerySection.Started }
        // m2 (09:00) waits: a page of created_by_me still to come may hold rows above it.
        assertEquals(listOf("c1", "m1", "c2"), started.rows.map { it.id.value })
        assertTrue(started.hasMore)
        assertNull(started.total)
    }

    @Test
    fun theHeadSaysTheViewOfTheScopeWhenNarrowed() {
        val doneSince = "2026-11-11T00:00:00Z"
        val query = TasksPageQuery(scope = TasksPageScope.All, statuses = setOf(TaskStatus.Todo))
        val lanes = tasksQueryLanes(query, doneSince)
        val counts =
            mapOf(
                tasksCountsKey(ServerTaskBoardFilters()) to FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 4, TaskStatus.Done to 6)),
                lanes.single().key to FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 4)),
            )
        val state = TasksQueryState(query = query, started = true, doneSince = doneSince, lanes = lanes, counts = counts)
        assertEquals("4 / 10", state.headNumber())
        assertEquals(10, state.scopeCounts()[TasksPageScope.All])
        assertNull(state.scopeCounts()[TasksPageScope.Mine], "Mine's counts are not read yet")
    }
}
