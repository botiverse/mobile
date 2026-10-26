package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #363 — per-status pagination semantics (the load-bearing teeth).
 *
 * The store holds BOTH the #345 global cursor triple (unchanged) and a per-status
 * map. These tests pin the properties that make the per-status walk safe:
 *  - a column's page advances ONLY that column's cursor;
 *  - an exhausted column is a no-op (a short column cannot spin);
 *  - an invalid cursor resets ONLY that column (never the global walk);
 *  - two columns walking concurrently never consume each other's pages;
 *  - the global triple is untouched by any per-status operation.
 */
class TasksPerStatusPaginationTest {

    private fun task(id: String, status: TaskStatus = TaskStatus.Todo) =
        TaskSummary(
            id = TaskId(id),
            title = "Task $id",
            status = status,
            taskNumber = id.filter { it.isDigit() }.toIntOrNull(),
            messageId = MessageId("message-$id"),
            channelId = ChannelId("channel-1"),
            updatedAtLabel = "now",
        )

    private fun content(vararg tasks: TaskSummary) = TasksUiState(
        loadState = ScreenLoadState.Content(
            TasksContent(summaryCounts = tasks.toList().summaryCounts(), tasks = tasks.toList())
        )
    )

    private fun seeded(status: TaskStatus, cursor: String?) =
        content().copy(statusPages = mapOf(status to TasksStatusPageState(nextCursor = cursor, hasMore = cursor != null)))

    @Test
    fun aStatusPageAdvancesOnlyItsOwnCursor() {
        // M1: if every status shared the global cursor, advancing one column would
        // move the other's position. Each column keeps its own.
        val state = seeded(TaskStatus.Todo, "todo-2")
            .copy(statusPages = mapOf(
                TaskStatus.Todo to TasksStatusPageState(nextCursor = "todo-2", hasMore = true),
                TaskStatus.InProgress to TasksStatusPageState(nextCursor = "wip-2", hasMore = true),
            ))
        val afterTodo = state.withAppendedStatusPage(TaskStatus.Todo, listOf(task("task-9")), "todo-3")

        assertEquals("todo-3", afterTodo.statusPages[TaskStatus.Todo]?.nextCursor)
        assertEquals("wip-2", afterTodo.statusPages[TaskStatus.InProgress]?.nextCursor, "the other column must not move")
        assertNull(afterTodo.nextCursor, "the global walk cursor must stay untouched")
    }

    @Test
    fun anExhaustedStatusPageIsANoOp() {
        // M2-adjacent: a short column (hasMore=false) must not fire.
        val exhausted = seeded(TaskStatus.Done, null)
        val reducerNext = exhausted.reduce(SharedTasksIntent.LoadMoreStatus(TaskStatus.Done))
        assertEquals(exhausted, reducerNext, "an exhausted column must not start a load")
    }

    @Test
    fun anAlreadyLoadingStatusPageIsANoOp() {
        // M2: single-flight. A second trigger while one is in flight must not
        // start another (the reducer refuses).
        val loading = seeded(TaskStatus.Todo, "todo-2").copy(
            statusPages = mapOf(TaskStatus.Todo to TasksStatusPageState(nextCursor = "todo-2", hasMore = true, loadingMore = true))
        )
        val reducerNext = loading.reduce(SharedTasksIntent.LoadMoreStatus(TaskStatus.Todo))
        assertEquals(loading, reducerNext, "single-flight must refuse a second in-flight page")
    }

    @Test
    fun anInvalidCursorResetsOnlyThatColumn() {
        // M4: an invalid cursor for one column must NOT clear the global cursor
        // (that would stop the List walk) nor any other column.
        val state = seeded(TaskStatus.Todo, "todo-2").copy(
            nextCursor = "global-2",
            hasMore = true,
            statusPages = mapOf(
                TaskStatus.Todo to TasksStatusPageState(nextCursor = "todo-2", hasMore = true, loadingMore = true),
                TaskStatus.InProgress to TasksStatusPageState(nextCursor = "wip-2", hasMore = true),
            ),
        )
        val after = state.reduce(
            SharedTasksIntent.LoadMoreStatusFailed(TaskStatus.Todo, errorCode = "invalid_cursor", invalidCursor = true)
        )

        assertEquals(null, after.statusPages[TaskStatus.Todo]?.nextCursor, "the failing column resets")
        assertEquals(false, after.statusPages[TaskStatus.Todo]?.hasMore)
        assertEquals("wip-2", after.statusPages[TaskStatus.InProgress]?.nextCursor, "other columns are untouched")
        assertEquals("global-2", after.nextCursor, "the GLOBAL cursor must survive")
        assertTrue(after.hasMore)
    }

    @Test
    fun twoColumnsWalkingConcurrentlyKeepSeparatePages() {
        // M1 (the core): both columns advance; each page lands in its own column's
        // rows and cursor. Sharing the global cursor would make one consume the
        // other's page (duplicate rows / skipped rows).
        var state = content().copy(
            nextCursor = "global-1",
            hasMore = true,
            statusPages = mapOf(
                TaskStatus.Todo to TasksStatusPageState(nextCursor = "todo-1", hasMore = true),
                TaskStatus.InProgress to TasksStatusPageState(nextCursor = "wip-1", hasMore = true),
            ),
        )
        state = state.withAppendedStatusPage(TaskStatus.Todo, listOf(task("todo-a", TaskStatus.Todo)), "todo-2")
        state = state.withAppendedStatusPage(TaskStatus.InProgress, listOf(task("wip-a", TaskStatus.InProgress)), "wip-2")

        assertEquals("todo-2", state.statusPages[TaskStatus.Todo]?.nextCursor)
        assertEquals("wip-2", state.statusPages[TaskStatus.InProgress]?.nextCursor)
        val rows = (state.loadState as ScreenLoadState.Content).data.tasks.map { it.id.value }
        assertEquals(listOf("todo-a", "wip-a"), rows, "each page appended exactly once")
        assertEquals("global-1", state.nextCursor, "the global walk is unaffected")
    }

    @Test
    fun anEmptyColumnGetsItsOwnSeededStateAndNeverUsesTheGlobalCursor() {
        // @LoveArk: a column whose first page is EMPTY must still get its own
        // page state. Otherwise statusPages[status] == null and the sentinel
        // would fall back to the global cursor, consuming another column's page.
        val seeded = content().reduce(
            SharedTasksIntent.SeedStatusPage(TaskStatus.Done, tasks = emptyList(), nextCursor = null)
        )
        assertTrue(seeded.statusPages.containsKey(TaskStatus.Done), "an empty column is still seeded")
        assertFalse(seeded.statusPages[TaskStatus.Done]?.hasMore == true, "an empty column has no more")

        // And it must not advance the global cursor even when the global walk has
        // more pages: the column is exhausted, so its sentinel is a no-op.
        val withGlobal = seeded.copy(nextCursor = "global-2", hasMore = true)
        val afterTrigger = withGlobal.reduce(SharedTasksIntent.LoadMoreStatus(TaskStatus.Done))
        assertEquals(withGlobal, afterTrigger, "an empty column must not start a load")
        assertEquals("global-2", afterTrigger.nextCursor, "the global cursor is untouched")
    }

    @Test
    fun aColumnWithAPageButNoRowsStillReportsItsCursor() {
        // A status-filtered first page can carry a cursor with zero rows (the
        // column is empty on page 1 but has more). Rows parse to nothing; the
        // cursor still lands so the column can continue.
        val seeded = content().reduce(
            SharedTasksIntent.SeedStatusPage(TaskStatus.InProgress, tasks = emptyList(), nextCursor = "wip-2")
        )
        assertEquals("wip-2", seeded.statusPages[TaskStatus.InProgress]?.nextCursor)
        assertTrue(seeded.statusPages[TaskStatus.InProgress]?.hasMore == true)
        assertEquals(emptyList(), (seeded.loadState as ScreenLoadState.Content).data.tasks.map { it.id.value })
    }

    @Test
    fun theSeedLandsItsFirstPageRowsAndCursorTogether() {
        // @LoveArk's blocker: the seed used to publish ONLY the cursor, so the
        // column's first page rows were silently lost and the column started at
        // page 2. Rows and cursor must land in one update.
        val seeded = content().reduce(
            SharedTasksIntent.SeedStatusPage(
                status = TaskStatus.Todo,
                tasks = listOf(task("seed-1"), task("seed-2")),
                nextCursor = "todo-2"
            )
        )
        val rows = (seeded.loadState as ScreenLoadState.Content).data.tasks.map { it.id.value }
        assertEquals(listOf("seed-1", "seed-2"), rows, "the seed's FIRST-PAGE rows must be visible")
        assertEquals("todo-2", seeded.statusPages[TaskStatus.Todo]?.nextCursor, "and its cursor lands with them")
    }

    @Test
    fun theSeedDeduplicatesAgainstRowsAlreadyLoaded() {
        val seeded = content(task("seed-1", TaskStatus.Todo), task("other"))
            .reduce(
                SharedTasksIntent.SeedStatusPage(
                    status = TaskStatus.Todo,
                    tasks = listOf(task("seed-1", TaskStatus.Todo), task("seed-2", TaskStatus.Todo)),
                    nextCursor = "todo-2"
                )
            )
        val rows = (seeded.loadState as ScreenLoadState.Content).data.tasks.map { it.id.value }
        assertEquals(listOf("seed-1", "other", "seed-2"), rows, "an overlapping seed row merges, no duplicate")
    }

    @Test
    fun aStatusPageMergesByIdWithoutDuplicating() {
        val first = content(task("t1")).withAppendedStatusPage(TaskStatus.Todo, listOf(task("t2")), "todo-2")
        val merged = first.withAppendedStatusPage(TaskStatus.Todo, listOf(task("t1", TaskStatus.Todo), task("t3")), null)
        val rows = (merged.loadState as ScreenLoadState.Content).data.tasks.map { it.id.value }
        assertEquals(listOf("t1", "t2", "t3"), rows, "overlap merges by id, no duplicate")
        assertEquals(false, merged.statusPages[TaskStatus.Todo]?.hasMore, "null cursor terminates that column")
    }

    @Test
    fun aTransientStatusFailureKeepsThatColumnsCursor() {
        val state = seeded(TaskStatus.Todo, "todo-2").copy(
            statusPages = mapOf(TaskStatus.Todo to TasksStatusPageState(nextCursor = "todo-2", hasMore = true, loadingMore = true))
        )
        val after = state.reduce(SharedTasksIntent.LoadMoreStatusFailed(TaskStatus.Todo, errorCode = "network"))
        assertEquals("todo-2", after.statusPages[TaskStatus.Todo]?.nextCursor, "fail-open: still walkable")
        assertTrue(after.statusPages[TaskStatus.Todo]?.hasMore == true)
        assertEquals("network", after.statusPages[TaskStatus.Todo]?.loadMoreErrorCode)
    }
}

/**
 * The Board WIRING half (@LoveArk): entering the Board must seed EVERY rendered
 * column — including one whose first page is empty — through its OWN
 * status-filtered request, and List mode must issue none.
 */
class TasksPerStatusBoardSeedWiringTest {

    private class RecordingApi(val emptyStatuses: Set<String>) : RaftTasksApi {
        val requests = mutableListOf<Pair<String?, String?>>() // (cursor, status)
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 500, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
            HomeNetworkResponse(code = 500, bodyText = null)

        override suspend fun serverTasksPage(
            serverId: ServerId,
            priority: DataRequestPriority,
            detail: String?,
            limit: Int?,
            cursor: String?,
            status: String?,
        ): HomeNetworkResponse {
            requests += cursor to status
            if (cursor != null) return HomeNetworkResponse(code = 200, bodyText = page("p", 1, null))
            // An "empty" status returns zero rows but still has no more pages.
            val rows = if (status in emptyStatuses) "" else
                """{"id":"$status-1","messageId":"m","channelId":"c1","channelName":"alpha","taskNumber":1,
                    "title":"T","status":"${(status ?: "todo").lowercase()}","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
                    "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}"""
            return HomeNetworkResponse(code = 200, bodyText = """{"tasks":[$rows],"next_cursor":null}""")
        }

        private fun page(prefix: String, count: Int, next: String?): String {
            val items = (1..count).joinToString(",") { n ->
                """{"id":"$prefix$n","messageId":"m-$prefix$n","channelId":"c1","channelName":"alpha","taskNumber":$n,"title":"T","status":"todo","createdByName":"x","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}"""
            }
            return """{"tasks":[$items],${next?.let { "\"next_cursor\":\"$it\"" } ?: "\"next_cursor\":null"}}"""
        }
    }

    private fun viewModel(api: RecordingApi) = SharedTasksViewModel(
        authStore = InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "u", email = "u@slock.ai"),
                accessToken = "t", refreshToken = "r", selectedServerId = "server-1",
            )
        ),
        dataSource = SharedTasksDataSource(api = api),
        sharedTasksDataStore = SharedTasksDataStore(
            ownerIdentityProvider = { "base|u" }, currentServerIdProvider = { "server-1" },
        ),
        runtimeScopeOverride = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test
    fun enteringTheBoardSeedsEveryColumnIncludingEmptyOnes() = runBlocking {
        val api = RecordingApi(emptyStatuses = setOf("done", "closed"))
        val vm = viewModel(api)
        vm.dispatch(SharedTasksRouteIntent.Start)
        withTimeout(5_000) { vm.state.first { it.loadState is ScreenLoadState.Content } }
        val callsBeforeBoard = api.requests.size

        vm.dispatch(SharedTasksRouteIntent.EnterBoard)
        // The observable contract (@LoveArk): one first-page request PER rendered
        // column, each with its OWN status filter (wire value), never a cursor.
        // Columns that are EMPTY are included -- that is the fix: otherwise their
        // later column-end would fall back to the global cursor.
        withTimeout(5_000) {
            while (api.requests.drop(callsBeforeBoard).mapNotNull { it.second }.toSet() !=
                TaskStatus.entries.map { it.toWireValue() }.toSet()
            ) {
                kotlinx.coroutines.delay(5)
            }
        }
        val seeded = api.requests.drop(callsBeforeBoard)
        assertEquals(TaskStatus.entries.map { it.toWireValue() }.toSet(),
            seeded.mapNotNull { it.second }.toSet(),
            "every rendered column (incl. empty ones) is seeded with its own status filter")
        assertTrue(seeded.all { it.first == null }, "each seed is a first page (cursor=null)")
        assertEquals(seeded.size, seeded.mapNotNull { it.second }.toSet().size,
            "exactly one first-page request per column")
    }

    @Test
    fun listModeNeverSeeds() = runBlocking {
        val api = RecordingApi(emptyStatuses = emptySet())
        val vm = viewModel(api)
        vm.dispatch(SharedTasksRouteIntent.Start)
        withTimeout(5_000) { vm.state.first { it.loadState is ScreenLoadState.Content } }
        val calls = api.requests.size
        // A global page request must not seed any column.
        vm.dispatch(SharedTasksRouteIntent.LoadMore)
        kotlinx.coroutines.yield()
        assertTrue(vm.state.value.statusPages.isEmpty(),
            "the global walk must not create per-status page state: ${vm.state.value.statusPages.keys}")
        assertTrue(api.requests.size >= calls)
    }
}
