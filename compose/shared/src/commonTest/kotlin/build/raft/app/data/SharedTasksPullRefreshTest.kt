package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Pull-to-refresh of the Tasks board / list: [SharedTasksRouteIntent.Refresh] on a loaded page. */
class SharedTasksPullRefreshTest {
    private val channel = ChannelId("channel-1")

    @Test
    fun refreshReloadsAtVisiblePriorityAndKeepsTheTasksWhenItFails() = runTest {
        // Each case: the reload's answer, then the tasks and notice left on screen.
        data class Case(
            val label: String,
            val answer: DataResult<TaskSnapshot>,
            val ids: List<String>,
            val navigationErrorCode: String?,
        )
        val cases =
            listOf(
                Case("content", tasks("task-2", "task-1"), listOf("task-2", "task-1"), null),
                Case("error", DataResult.Error(ScreenError("tasks_network", retryable = true)), listOf("task-1"), "tasks_network"),
            )
        cases.forEach { case ->
            val remote = GatedChannelTasks(tasks("task-1"))
            val viewModel = loadedViewModel(remote)
            val release = CompletableDeferred<Unit>()
            remote.release = release
            remote.answers += case.answer

            viewModel.dispatch(SharedTasksRouteIntent.Refresh)
            runCurrent()
            assertEquals(true, viewModel.state.value.refreshing, case.label)
            assertEquals(listOf("task-1"), ids(viewModel), "${case.label}: the loaded tasks stay while it reloads")
            assertEquals(listOf(DataRequestPriority.Visible, DataRequestPriority.Visible), remote.priorities, case.label)

            release.complete(Unit)
            runCurrent()
            assertEquals(false, viewModel.state.value.refreshing, case.label)
            assertEquals(case.ids, ids(viewModel), case.label)
            assertEquals(case.navigationErrorCode, viewModel.state.value.navigationErrorCode, case.label)
        }
    }

    @Test
    fun catchUpReloadsSilentlyAndKeepsTheTasksWhenItFails() = runTest {
        // Foreground / socket-reconnect catch-up: a forced Background reload of a loaded page.
        data class Case(val label: String, val answer: DataResult<TaskSnapshot>, val expectedIds: List<String>)
        val cases =
            listOf(
                Case("content", tasks("task-2", "task-1"), listOf("task-2", "task-1")),
                Case("error", DataResult.Error(ScreenError("tasks_network", retryable = true)), listOf("task-1")),
            )
        cases.forEach { (label, answer, expectedIds) ->
            val remote = GatedChannelTasks(tasks("task-1"))
            val viewModel = loadedViewModel(remote)
            val release = CompletableDeferred<Unit>()
            remote.release = release
            remote.answers += answer

            viewModel.dispatch(SharedTasksRouteIntent.CatchUp)
            runCurrent()
            assertEquals(listOf("task-1"), ids(viewModel), "$label: the loaded tasks stay while it reloads")
            assertEquals(listOf(DataRequestPriority.Visible, DataRequestPriority.Background), remote.priorities, label)

            release.complete(Unit)
            runCurrent()
            assertEquals(false, viewModel.state.value.refreshing, label)
            assertEquals(expectedIds, ids(viewModel), label)
        }
    }

    @Test
    fun storeOwnedFreshnessHandlesChannelForegroundDeferralAndFailedRetry() = runTest {
        val remote = GatedChannelTasks(tasks("task-1"))
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        var clock = 1_000L
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture(selectedServerId = "server-1"))
        val scope = SharedTasksScope.Channel(channel)
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = SharedTasksDataSource(api = UnusedTasksApi),
                scope = scope,
                runtimeScopeOverride = backgroundScope,
                useCase =
                    SharedTasksUseCase(
                        authStore = authStore,
                        repository = SharedTasksDataRepository(remoteDataSource = remote),
                        scope = scope
                    ),
                freshness = freshness,
                freshnessNowMillis = { clock },
                freshnessLiveConnectionToken = { null },
            )
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()
        assertEquals(listOf(DataRequestPriority.Visible), remote.priorities)

        val token = Any()
        viewModel.dispatch(SharedTasksRouteIntent.ObserveFreshness(token = token, serverId = "server-1", visible = true))
        runCurrent()

        // Failed Foreground catch-up must NOT burn the 10 s coalescing window before NetworkRestored.
        remote.answers += DataResult.Error(ScreenError("offline", retryable = true))
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        runCurrent()
        assertEquals(2, remote.priorities.size)
        assertEquals(listOf("task-1"), ids(viewModel))

        clock += 1_000L
        remote.answers += tasks("task-2", "task-1")
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(3, remote.priorities.size, "failed foreground must not block immediate NetworkRestored retry")
        assertEquals(listOf("task-2", "task-1"), ids(viewModel))

        // Reconnect while hidden waits until the tab becomes visible again.
        viewModel.dispatch(SharedTasksRouteIntent.ObserveFreshness(token = token, serverId = "server-1", visible = false))
        remote.answers += tasks("task-3", "task-2", "task-1")
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 2L))
        runCurrent()
        assertEquals(3, remote.priorities.size, "hidden tasks tab defers reconnect catch-up")

        viewModel.dispatch(SharedTasksRouteIntent.ObserveFreshness(token = token, serverId = "server-1", visible = true))
        runCurrent()
        assertEquals(4, remote.priorities.size, "visible tasks tab flushes deferred reconnect")
        assertEquals(listOf("task-3", "task-2", "task-1"), ids(viewModel))

        // Stopping observation unsubscribes cleanly.
        viewModel.dispatch(SharedTasksRouteIntent.StopObservingFreshness(token))
        clock += 60_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        runCurrent()
        assertEquals(4, remote.priorities.size)
    }

    @Test
    fun serverScopeFreshnessSkipsForegroundAndCatchesUpOnReconnect() = runTest {
        val remote = GatedChannelTasks(tasks("task-1"))
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture(selectedServerId = "server-1"))
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = SharedTasksDataSource(api = UnusedTasksApi),
                scope = SharedTasksScope.Server,
                runtimeScopeOverride = backgroundScope,
                useCase =
                    SharedTasksUseCase(
                        authStore = authStore,
                        repository = SharedTasksDataRepository(remoteDataSource = remote),
                        scope = SharedTasksScope.Server
                    ),
                freshness = freshness,
                freshnessNowMillis = { 1_000L },
                freshnessLiveConnectionToken = { null },
            )
        val token = Any()
        viewModel.dispatch(SharedTasksRouteIntent.ObserveFreshness(token = token, serverId = "server-1", visible = true))
        runCurrent()

        // Server scope leaves Foreground to AppShell's visible-route refresh.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        runCurrent()
        assertEquals(0, remote.serverPageCalls)

        // Own-server Reconnect triggers a catch-up read.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 1L))
        runCurrent()
        assertEquals(1, remote.serverPageCalls)

        viewModel.dispatch(SharedTasksRouteIntent.StopObservingFreshness(token))
    }

    private fun TestScope.loadedViewModel(remote: GatedChannelTasks): SharedTasksViewModel {
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture(selectedServerId = "server-1"))
        val scope = SharedTasksScope.Channel(channel)
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = SharedTasksDataSource(api = UnusedTasksApi),
                scope = scope,
                runtimeScopeOverride = backgroundScope,
                useCase =
                    SharedTasksUseCase(
                        authStore = authStore,
                        repository = SharedTasksDataRepository(remoteDataSource = remote),
                        scope = scope
                    )
            )
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()
        assertEquals(listOf("task-1"), ids(viewModel))
        return viewModel
    }

    private fun ids(viewModel: SharedTasksViewModel): List<String> =
        assertIs<ScreenLoadState.Content<TasksContent>>(viewModel.state.value.loadState).data.tasks.map { it.id.value }

    private fun tasks(vararg ids: String): DataResult<TaskSnapshot> =
        DataResult.Content(
            TaskSnapshot(
                tasks =
                    ids.map { id ->
                        TaskSummary(
                            id = TaskId(id),
                            title = "Task $id",
                            status = TaskStatus.Todo,
                            messageId = MessageId("message-$id"),
                            channelId = channel,
                            updatedAtLabel = "now"
                        )
                    },
                summaryCounts = emptyMap()
            )
        )

    /** Channel task reads answered in order; each waits for [release] when it is set. */
    private class GatedChannelTasks(first: DataResult<TaskSnapshot>) : SharedTasksRemoteDataSource {
        val answers = ArrayDeque(listOf(first))
        val priorities = mutableListOf<DataRequestPriority>()
        var serverPageCalls = 0
        var release: CompletableDeferred<Unit>? = null

        override suspend fun loadChannelTasks(
            serverId: ServerId,
            channelId: ChannelId,
            filter: TaskFilter,
            priority: DataRequestPriority
        ): DataResult<TaskSnapshot> {
            priorities += priority
            release?.await()
            return answers.removeFirstOrNull() ?: error("unscripted channel tasks read")
        }

        override suspend fun loadTasks(serverId: ServerId, filter: TaskFilter, priority: DataRequestPriority): DataResult<TaskSnapshot> =
            error("server scope uses paginated loadServerTasksPage")

        override suspend fun loadServerTasksPage(
            serverId: ServerId,
            limit: Int,
            cursor: String?,
            priority: DataRequestPriority,
            status: String?
        ): TasksPageResult {
            serverPageCalls += 1
            return TasksPageResult.Page(tasks = emptyList(), nextCursor = null)
        }

        override suspend fun updateTaskStatus(serverId: ServerId, taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult> =
            error("unused")

        override suspend fun assignTask(
            serverId: ServerId,
            taskId: TaskId,
            assignee: MemberProfile?,
            expectedRevision: Long?
        ): DataResult<TaskActionResult> = error("unused")

        override suspend fun createTasks(serverId: ServerId, channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot> =
            error("unused")
    }

    private object UnusedTasksApi : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse = error("unused")

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse = error("unused")
    }
}
