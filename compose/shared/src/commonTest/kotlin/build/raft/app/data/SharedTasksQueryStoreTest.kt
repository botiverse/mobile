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

        // A new Todo task the row says matches: it joins where the sort places it, no read.
        store.onProjection(
            listOf(task("a", title = "Renamed", revision = 2), task("b", status = TaskStatus.Done, revision = 2), task("c", activityAt = "2026-11-02T00:00:00.000Z")),
            bulk = false,
        )
        advanceTimeBy(SharedTasksQueryStore.LiveRereadDelayMs + 1)
        runCurrent()
        assertEquals(listOf("c", "a"), store.state.value.rowIds())
        assertEquals(reads, repository.pageCalls.size)
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
                        created.key to readLane(listOf(c1, c2), nextCursor = "more"),
                        fromMessage.key to readLane(listOf(m1, m2), nextCursor = null),
                    ),
                tasks = listOf(c1, c2, m1, m2).associateBy { it.id.value },
            )
        val started = state.sections().single { it.section == TasksQuerySection.Started }
        // m2 (09:00) waits: a page of created_by_me still to come may hold rows above it.
        assertEquals(listOf("c1", "m1", "c2"), started.rows.map { it.id.value })
        assertTrue(started.hasMore)
        assertNull(started.total)

        // A live change moves the page end's row to the top: the cut stays where the server's rows ended.
        val patched = state.copy(tasks = state.tasks + ("c2" to c2.copy(activityAt = "2026-11-12T14:00:00.000Z", revision = 2)))
        val afterPatch = patched.sections().single { it.section == TasksQuerySection.Started }
        assertEquals(listOf("c2", "c1", "m1"), afterPatch.rows.map { it.id.value })
    }

    private val me = person(ViewerId)
    private val c1 = task("c1", activityAt = "2026-11-12T12:00:00.000Z", creator = me)
    private val c2 = task("c2", activityAt = "2026-11-12T10:00:00.000Z", creator = me)
    private val m1 = task("m1", activityAt = "2026-11-12T11:00:00.000Z")
    private val m2 = task("m2", activityAt = "2026-11-12T09:00:00.000Z")

    /** A lane as a page read leaves it: ids, keys and page end as read. */
    private fun readLane(rows: List<build.raft.app.model.TaskSummary>, nextCursor: String?): TasksLaneState =
        TasksLaneState(
            ids = rows.map { it.id.value },
            keys = rows.associate { it.id.value to tasksSortKey(ServerTaskSort.Activity, it) },
            frontier = rows.last(),
            nextCursor = nextCursor,
            loaded = true,
        )

    @Test
    fun theHeadSaysTheViewOfTheScopeWhenNarrowed() {
        val doneSince = "2026-11-11T00:00:00Z"
        val query = TasksPageQuery(scope = TasksPageScope.All, statuses = setOf(TaskStatus.Todo))
        val lanes = tasksQueryLanes(query, doneSince)
        val counts =
            mapOf(
                tasksCountsKey(tasksQueryScopeFilters(doneSince)) to FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 4, TaskStatus.Done to 6)),
                lanes.single().key to FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 4)),
            )
        val state = TasksQueryState(query = query, started = true, doneSince = doneSince, lanes = lanes, counts = counts)
        assertEquals("4 / 10", state.headNumber())
        assertEquals(10, state.scopeCounts()[TasksPageScope.All])
        assertNull(state.scopeCounts()[TasksPageScope.Mine], "Mine's counts are not read yet")
    }

    @Test
    fun aRowLeavingTheProjectionIsReadAgainNotDropped() = runTest {
        val repository = FakeTasksQueryRepository(page = { TasksPageResult.Page(listOf(task("a"), task("b")), null) })
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()
        store.onProjection(listOf(task("a"), task("b")), bulk = false)
        val reads = repository.pageCalls.size

        // A list read replaced the projection with fewer rows: b is no longer held, not deleted.
        store.onProjection(listOf(task("a")), bulk = false)
        assertEquals(listOf("a", "b"), store.state.value.rowIds())
        // The server says what is left.
        repository.page = { TasksPageResult.Page(listOf(task("a")), null) }
        advanceTimeBy(SharedTasksQueryStore.LiveRereadDelayMs + 1)
        runCurrent()
        assertEquals(DataRequestPriority.Background, repository.pageCalls.drop(reads).single().priority)
        assertEquals(listOf("a"), store.state.value.rowIds())
    }

    @Test
    fun aMovedRowIsPlacedAgainInASingleLane() = runTest {
        val a = task("a", activityAt = "2026-11-12T12:00:00.000Z")
        val b = task("b", activityAt = "2026-11-12T11:00:00.000Z")
        val repository = FakeTasksQueryRepository(page = { TasksPageResult.Page(listOf(a, b), null) })
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()
        store.onProjection(listOf(a, b), bulk = false)
        store.onProjection(listOf(a, b.copy(activityAt = "2026-11-12T13:00:00.000Z", revision = 2)), bulk = false)
        assertEquals(listOf("b", "a"), store.state.value.rowIds())
    }

    @Test
    fun anUnknownServerOrReaderIsResolvedAgainOnTheNextRead() = runTest {
        var server: ServerId? = null
        var viewer: String? = null
        val repository =
            FakeTasksQueryRepository(page = { call ->
                // The server listed it as assigned to me; the app does not know who "me" is yet.
                if (call.filters.relation == ServerTaskRelation.AssignedToMe) {
                    TasksPageResult.Page(listOf(task("mine", assignee = person(ViewerId))), null)
                } else {
                    TasksPageResult.Page(emptyList(), null)
                }
            })
        val store =
            SharedTasksQueryStore(
                scope = backgroundScope,
                repository = repository,
                serverId = { server },
                viewerId = { viewer },
                nowEpochMillis = { 1_794_000_000_000L },
            )
        store.start()
        runCurrent()
        assertEquals("selected_server_missing", store.state.value.error?.code)
        assertTrue(repository.pageCalls.isEmpty())

        server = ServerId(TasksQueryTestFixtures.ServerUuid)
        store.dispatch(TasksQueryIntent.Refresh)
        runCurrent()
        // Unknown reader: the server's rows stay rather than every relation lane reading empty.
        assertEquals(listOf("mine"), store.state.value.rowIds())

        viewer = ViewerId
        store.dispatch(TasksQueryIntent.Refresh)
        runCurrent()
        assertEquals(ViewerId, store.state.value.viewerId)
        assertEquals(listOf("mine"), store.state.value.rowIds())
    }

    @Test
    fun theMineTabNumberComesWithTheScopesReadOnAServerThatBundlesIt() = runTest {
        // slock #16585: one unfiltered read answers every task and, through mine_done_since, Mine's sections.
        val repository =
            FakeTasksQueryRepository(
                counts = { filters ->
                    val counts =
                        if (filters.mineDoneSince != null) {
                            FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 7, TaskStatus.Done to 3))
                                .copy(mine = ServerTaskMineCounts(assigned = 2, created = 3, fromMyMessage = 1))
                        } else {
                            // Anything else would be a stale Mine lane; it must not be what the tab shows.
                            FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 99))
                        }
                    ServerTaskCountsResult.Counts(counts)
                },
            )
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()

        val state = store.state.value
        // The bound sent is Started by you's day of Done, the same instant its lanes use.
        assertEquals(state.doneSince, repository.countCalls.first().mineDoneSince)
        assertEquals(10, state.scopeCounts()[TasksPageScope.All])
        assertEquals(6, state.scopeCounts()[TasksPageScope.Mine])
        // The unfiltered All view is that same read.
        assertEquals("10", state.headNumber())
        assertEquals(mapOf(TaskStatus.Backlog to 0, TaskStatus.Todo to 7, TaskStatus.InProgress to 0, TaskStatus.Done to 3, TaskStatus.Closed to 0), state.viewStatusCounts())
    }

    @Test
    fun aServerBeforeBundledCountsStillShowsTheMineTabFromItsLanes() = runTest {
        // A server before slock #16585 ignores mine_done_since and leaves `mine` out.
        val repository =
            FakeTasksQueryRepository(
                counts = { filters ->
                    val todo =
                        when (filters.relation) {
                            ServerTaskRelation.AssignedToMe -> 2
                            ServerTaskRelation.CreatedByMe -> 3
                            ServerTaskRelation.FromMyMessage -> 1
                            null -> 10
                        }
                    ServerTaskCountsResult.Counts(FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to todo)))
                },
            )
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()

        assertEquals(10, store.state.value.scopeCounts()[TasksPageScope.All])
        assertEquals(6, store.state.value.scopeCounts()[TasksPageScope.Mine])
    }

    @Test
    fun aServerWithoutTheBoardQueryIsUnsupported() = runTest {
        val repository = FakeTasksQueryRepository(counts = { ServerTaskCountsResult.Error(code = "tasks_counts_http_404") })
        val store = store(repository)
        store.start()
        runCurrent()
        assertTrue(store.state.value.unsupported)
    }

    @Test
    fun fromMyMessageReadsTheSourceSenderASummaryRowCarries() {
        val filters = ServerTaskBoardFilters(relation = ServerTaskRelation.FromMyMessage)
        val fromMe = task("t").copy(sourceSenderType = "user", sourceSenderId = ViewerId, sourceSenderReported = true)
        assertEquals(true, fromMe.matchesTasksQueryLane(filters, ViewerId))
        assertEquals(false, fromMe.copy(sourceSenderId = "someone-else").matchesTasksQueryLane(filters, ViewerId))
        // A socket frame does not say: unknown, unless the page's row did.
        val frame = task("t")
        assertNull(frame.matchesTasksQueryLane(filters, ViewerId))
        assertEquals(true, frame.withKnownSourceSender(fromMe).matchesTasksQueryLane(filters, ViewerId))
    }

    @Test
    fun anOldDoneTaskEditedLiveStaysOutOfStartedByYou() = runTest {
        val repository = FakeTasksQueryRepository()
        val store = store(repository)
        store.start()
        runCurrent()
        store.onProjection(emptyList(), bulk = false)
        val reads = repository.pageCalls.size
        // Started by you keeps Done since doneSince (2026-11-05T21:20Z here); this one was done the week before.
        val oldDone =
            task("old", status = TaskStatus.Done, creator = me, activityAt = "2026-11-06T20:00:00.000Z")
                .copy(completedAt = "2026-10-29T00:00:00.000Z")
        store.onProjection(listOf(oldDone), bulk = false)
        advanceTimeBy(SharedTasksQueryStore.LiveRereadDelayMs + 1)
        runCurrent()
        assertTrue(store.state.value.rowIds().isEmpty(), "the server's completed_since leaves it out")
        assertEquals(reads, repository.pageCalls.size)

        // Done within the day: it joins. Done without a completion time: the server decides.
        val recentDone = task("recent", status = TaskStatus.Done, creator = me).copy(completedAt = "2026-11-06T10:00:00.000Z")
        store.onProjection(listOf(oldDone, recentDone), bulk = false)
        assertEquals(listOf("recent"), store.state.value.rowIds())
        store.onProjection(listOf(oldDone, recentDone, task("unknown", status = TaskStatus.Done, creator = me)), bulk = false)
        advanceTimeBy(SharedTasksQueryStore.LiveRereadDelayMs + 1)
        runCurrent()
        // Both Started lanes bound Done by completed_since; neither can tell without the time.
        assertEquals(
            setOf(ServerTaskRelation.CreatedByMe, ServerTaskRelation.FromMyMessage),
            repository.pageCalls.drop(reads).map { it.filters.relation }.toSet(),
        )
    }

    @Test
    fun aLiveRereadNeverDowngradesAVisibleRead() = runTest {
        val gate = CompletableDeferred<TasksPageResult>()
        val repository = FakeTasksQueryRepository(page = { TasksPageResult.Page(listOf(task("a"), task("b")), null) })
        val store = store(repository, InMemoryTasksPageQueryPreferences(savedScope = TasksPageScope.All))
        store.start()
        runCurrent()
        store.onProjection(listOf(task("a"), task("b")), bulk = false)
        val reads = repository.pageCalls.size
        repository.page = { call -> if (call.priority == DataRequestPriority.Visible) gate.await() else TasksPageResult.Page(listOf(task("a")), null) }

        store.dispatch(TasksQueryIntent.Refresh)
        runCurrent()
        store.onProjection(listOf(task("a")), bulk = false)
        advanceTimeBy(SharedTasksQueryStore.LiveRereadDelayMs + 1)
        runCurrent()
        assertEquals(listOf(DataRequestPriority.Visible), repository.pageCalls.drop(reads).map { it.priority })

        gate.complete(TasksPageResult.Page(listOf(task("a"), task("b")), null))
        runCurrent()
        assertEquals(listOf(DataRequestPriority.Visible, DataRequestPriority.Background), repository.pageCalls.drop(reads).map { it.priority })
    }

    @Test
    fun returningToTasksRefetchesAndShowsUpdatedCounts() = runTest {
        var currentCounts = FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 2))
        val repository =
            FakeTasksQueryRepository(
                counts = { ServerTaskCountsResult.Counts(currentCounts) },
            )
        val store =
            SharedTasksQueryStore(
                scope = backgroundScope,
                repository = repository,
                serverId = { ServerId(TasksQueryTestFixtures.ServerUuid) },
                viewerId = { ViewerId },
                nowEpochMillis = { 1_794_000_000_000L + testScheduler.currentTime },
            )
        store.start()
        runCurrent()
        store.onProjection(emptyList(), bulk = false)
        assertEquals(2, store.state.value.scopeCounts()[TasksPageScope.All])

        // Leave Tasks; server counts change while away.
        store.setVisible(false)
        currentCounts = FakeTasksQueryRepository.zeroCounts(mapOf(TaskStatus.Todo to 5))
        store.onProjection(listOf(task("x", creator = person("someone-else"))), bulk = false)
        advanceTimeBy(SharedTasksQueryStore.CountsRefreshMinIntervalMs + 1_000L)
        runCurrent()
        assertEquals(2, store.state.value.scopeCounts()[TasksPageScope.All])

        // Return to Tasks: counts re-fetch and show the updated numbers instead of stale ones.
        store.setVisible(true)
        runCurrent()
        assertEquals(5, store.state.value.scopeCounts()[TasksPageScope.All])
    }
}
