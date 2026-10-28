package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tasks lists as offline resources (artin: everything fetched is stored locally): a loaded list is stored, a
 * cold / offline open shows it read-only until the network answers, a failure keeps it, and only confirmed
 * facts (mutations, realtime events) reach the stored copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedTasksOfflineTest {
    private val channel = ChannelId("channel-1")
    private val scope = SharedTasksScope.Channel(channel)

    /** One device: the disk outlives every "process" (data store + view model) built over it. */
    private class Device(scope: CoroutineScope) {
        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { Environment }, scope = scope)
        val lists = SharedTasksOfflineLists(offline.resource(TaskListResourceSpec))

        fun dataStore(scope: CoroutineScope, account: String = "user-1"): SharedTasksDataStore =
            SharedTasksDataStore(
                scope = scope,
                ownerIdentityProvider = { "$Environment|$account" },
                currentServerIdProvider = { "server-1" },
                offlineLists = lists,
            )

        suspend fun storedIds(scope: SharedTasksScope, account: String = "user-1"): List<String>? =
            lists.read(TasksOfflineOwner(Environment, account), "server-1", scope)?.tasks?.map { it.id.value }
    }

    @Test
    fun coldOpenShowsTheStoredListReadOnlyThenTheNetworkReplacesIt() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, listOf(task("task-1"), task("task-2")))

        val remote = ScriptedTasks(listOf(task("task-3")), gated = true)
        val viewModel = viewModel(device.dataStore(backgroundScope), remote)
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()

        // Knife: without the stored read this is the Loading blank.
        assertEquals(listOf("task-1", "task-2"), ids(viewModel))
        assertTrue(viewModel.state.value.standIn)
        assertTrue(viewModel.state.value.refreshing)

        // Read-only: no status / assignee / create against the stand-in.
        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(task("task-1"), TaskStatus.Done))
        viewModel.dispatch(SharedTasksRouteIntent.AssignTask(task("task-1"), null))
        viewModel.dispatch(SharedTasksRouteIntent.CreateTasks(channel, listOf("new")))
        runCurrent()
        assertEquals(0, remote.mutationCalls)

        remote.release.complete(Unit)
        runCurrent()
        assertEquals(listOf("task-3"), ids(viewModel))
        assertFalse(viewModel.state.value.standIn)
        assertEquals(listOf("task-3"), device.storedIds(scope))
    }

    @Test
    fun offlineRefreshKeepsTheStoredListReadOnly() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, listOf(task("task-1")))

        val remote = ScriptedTasks(DataResult.Error(ScreenError("tasks_network", retryable = true)))
        val viewModel = viewModel(device.dataStore(backgroundScope), remote)
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()

        assertEquals(listOf("task-1"), ids(viewModel))
        assertTrue(viewModel.state.value.standIn)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals("tasks_network", viewModel.state.value.navigationErrorCode)
        assertEquals(listOf("task-1"), device.storedIds(scope))
    }

    @Test
    fun emptyOrLostListReplacesTheStandInAndDropsTheStoredCopy() = runTest {
        val answers =
            listOf<DataResult<TaskSnapshot>>(
                DataResult.Empty(EmptyReason.NoTasks),
                DataResult.NoPermission(PermissionBlocker(code = "tasks_forbidden")),
            )
        answers.forEach { answer ->
            val device = Device(backgroundScope)
            loadOnce(device, listOf(task("task-1")))

            val viewModel = viewModel(device.dataStore(backgroundScope), ScriptedTasks(answer))
            viewModel.dispatch(SharedTasksRouteIntent.Start)
            runCurrent()

            assertFalse(viewModel.state.value.loadState is ScreenLoadState.Content<*>, "stand-in replaced by $answer")
            assertFalse(viewModel.state.value.standIn)
            assertNull(device.storedIds(scope), "stored copy dropped by $answer")
        }
    }

    @Test
    fun confirmedStatusChangeIsStoredAndAFailedOneIsNot() = runTest {
        val device = Device(backgroundScope)
        val remote = ScriptedTasks(listOf(task("task-1", revision = 1L)))
        val viewModel = viewModel(device.dataStore(backgroundScope), remote)
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()
        assertEquals(listOf(TaskStatus.Todo), device.storedStatuses(scope))

        // In flight: the optimistic status is shown but not stored.
        remote.statusGate = CompletableDeferred()
        remote.statusResult = DataResult.Content(TaskActionResult(task("task-1", status = TaskStatus.Done, revision = 2L)))
        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(currentTask(viewModel, "task-1"), TaskStatus.Done))
        runCurrent()
        assertEquals(listOf(TaskStatus.Todo), device.storedStatuses(scope))

        remote.statusGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(TaskStatus.Done), device.storedStatuses(scope))

        // A failed change: rolled back in memory, the stored copy never sees it.
        remote.statusGate = null
        remote.statusResult = DataResult.Error(ScreenError("tasks_network", retryable = true))
        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(currentTask(viewModel, "task-1"), TaskStatus.InProgress))
        runCurrent()
        assertEquals(listOf(TaskStatus.Done), device.storedStatuses(scope))
    }

    @Test
    fun realtimeCreateUpdateDeleteReachTheStoredListOfTheCurrentAccount() = runTest {
        val device = Device(backgroundScope)
        val dataStore = device.dataStore(backgroundScope)
        val viewModel = viewModel(dataStore, ScriptedTasks(listOf(task("task-1", revision = 1L))))
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()

        dataStore.upsertTaskForCurrentOwner("server-1", task("task-2", revision = 1L))
        runCurrent()
        assertEquals(setOf("task-1", "task-2"), device.storedIds(scope)?.toSet())

        dataStore.upsertTaskForCurrentOwner("server-1", task("task-2", status = TaskStatus.Done, revision = 2L))
        runCurrent()
        assertEquals(
            TaskStatus.Done,
            device.lists.read(TasksOfflineOwner(Environment, "user-1"), "server-1", scope)
                ?.tasks?.single { it.id.value == "task-2" }?.status,
        )

        dataStore.removeTaskForCurrentOwner("server-1", TaskId("task-1"))
        runCurrent()
        assertEquals(listOf("task-2"), device.storedIds(scope))
        // Another account's stored lists are untouched (none here).
        assertNull(device.storedIds(scope, account = "user-2"))
    }

    @Test
    fun lostServerDropsTheListsAndTheMirrorDoesNotStoreThemAgain() = runTest {
        val device = Device(backgroundScope)
        val dataStore = device.dataStore(backgroundScope)
        val viewModel = viewModel(dataStore, ScriptedTasks(listOf(task("task-1", revision = 1L))))
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()
        assertEquals(listOf("task-1"), device.storedIds(scope))

        // The provider's order: memory first, then the offline wipe.
        dataStore.dropServer("server-1")
        device.offline.wipeServer("server-1", accountId = "user-1")
        // A later change of the store (another server) does not bring the lost server's list back.
        dataStore.upsertTaskForCurrentOwner("server-2", task("task-9", revision = 1L))
        runCurrent()

        assertNull(device.storedIds(scope))
    }

    @Test
    fun ownerIdentityParsesBackToEnvironmentAndAccount() {
        assertEquals(TasksOfflineOwner(Environment, "user-1"), TasksOfflineOwner.fromOwnerIdentity("$Environment/|user-1"))
        assertNull(TasksOfflineOwner.fromOwnerIdentity("no-separator"))
        assertNull(TasksOfflineOwner.fromOwnerIdentity("$Environment|"))
        assertNull(TasksOfflineOwner.fromOwnerIdentity(null))
    }

    private suspend fun TestScope.loadOnce(device: Device, tasks: List<TaskSummary>) {
        val viewModel = viewModel(device.dataStore(backgroundScope), ScriptedTasks(tasks))
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        runCurrent()
        assertEquals(tasks.map { it.id.value }, device.storedIds(scope))
    }

    private suspend fun Device.storedStatuses(scope: SharedTasksScope): List<TaskStatus>? =
        lists.read(TasksOfflineOwner(Environment, "user-1"), "server-1", scope)?.tasks?.map { it.status }

    private fun TestScope.viewModel(dataStore: SharedTasksDataStore, remote: ScriptedTasks): SharedTasksViewModel {
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture(selectedServerId = "server-1"))
        return SharedTasksViewModel(
            authStore = authStore,
            dataSource = SharedTasksDataSource(api = UnusedTasksApi),
            scope = scope,
            sharedTasksDataStore = dataStore,
            runtimeScopeOverride = backgroundScope,
            useCase =
                SharedTasksUseCase(
                    authStore = authStore,
                    repository = SharedTasksDataRepository(remoteDataSource = remote),
                    scope = scope
                )
        )
    }

    private fun ids(viewModel: SharedTasksViewModel): List<String> =
        assertIs<ScreenLoadState.Content<TasksContent>>(viewModel.state.value.loadState).data.tasks.map { it.id.value }

    private fun currentTask(viewModel: SharedTasksViewModel, id: String): TaskSummary =
        assertIs<ScreenLoadState.Content<TasksContent>>(viewModel.state.value.loadState).data.tasks.single { it.id.value == id }

    private fun task(id: String, status: TaskStatus = TaskStatus.Todo, revision: Long? = null): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = "Task $id",
            status = status,
            messageId = MessageId("message-$id"),
            channelId = channel,
            updatedAtLabel = "now",
            revision = revision,
        )

    /** Channel task reads answered by [first]; optionally held until [release]; status changes scripted. */
    private class ScriptedTasks(private val first: DataResult<TaskSnapshot>, gated: Boolean = false) : SharedTasksRemoteDataSource {
        constructor(tasks: List<TaskSummary>, gated: Boolean = false) :
            this(DataResult.Content(TaskSnapshot(tasks = tasks, summaryCounts = tasks.summaryCounts())), gated)

        val release = CompletableDeferred<Unit>().also { if (!gated) it.complete(Unit) }
        var mutationCalls = 0
        var statusGate: CompletableDeferred<Unit>? = null
        var statusResult: DataResult<TaskActionResult> = DataResult.Error(ScreenError("unscripted", retryable = false))

        override suspend fun loadChannelTasks(
            serverId: ServerId,
            channelId: ChannelId,
            filter: TaskFilter,
            priority: DataRequestPriority
        ): DataResult<TaskSnapshot> {
            release.await()
            return first
        }

        override suspend fun loadTasks(serverId: ServerId, filter: TaskFilter, priority: DataRequestPriority): DataResult<TaskSnapshot> =
            error("server scope is not under test")

        override suspend fun updateTaskStatus(serverId: ServerId, taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult> {
            mutationCalls += 1
            statusGate?.await()
            return statusResult
        }

        override suspend fun assignTask(
            serverId: ServerId,
            taskId: TaskId,
            assignee: MemberProfile?,
            expectedRevision: Long?
        ): DataResult<TaskActionResult> {
            mutationCalls += 1
            return DataResult.Error(ScreenError("unused", retryable = false))
        }

        override suspend fun createTasks(serverId: ServerId, channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot> {
            mutationCalls += 1
            return DataResult.Error(ScreenError("unused", retryable = false))
        }
    }

    private object UnusedTasksApi : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse = error("unused")

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse = error("unused")
    }

    private companion object {
        const val Environment = "https://api.example"
    }
}
