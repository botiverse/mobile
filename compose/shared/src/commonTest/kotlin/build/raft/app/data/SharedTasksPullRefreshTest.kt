package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
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
            error("server scope is not under test")

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
