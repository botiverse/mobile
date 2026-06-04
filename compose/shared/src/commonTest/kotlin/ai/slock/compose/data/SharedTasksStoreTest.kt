package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SharedTasksStoreTest {
    @Test
    fun sharedTasksUseCaseReturnsTypedErrorWhenSelectedServerIsMissing() = runBlocking {
        val repository = FakeTasksRepository()
        val useCase =
            SharedTasksUseCase(
                authStore = InMemorySlockAuthSessionStore(),
                repository = repository
            )

        val result = useCase.refreshTasks(TasksLoadRequest())

        val error = assertIs<DataResult.Error>(result)
        assertEquals("selected_server_missing", error.error.code)
        assertEquals(0, repository.loadCalls)
    }

    @Test
    fun sharedTasksUseCaseLoadsThroughRepositoryWithSelectedServer() = runBlocking {
        val repository =
            FakeTasksRepository(
                loadResult =
                    DataResult.Content(
                        TaskSnapshot(
                            tasks = listOf(task("task-1", TaskStatus.Todo)),
                            summaryCounts = mapOf(TaskStatus.Todo to 1)
                        )
                    )
            )
        val useCase =
            SharedTasksUseCase(
                authStore =
                    InMemorySlockAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                            accessToken = "token",
                            refreshToken = "refresh",
                            selectedServerId = "server-1"
                        )
                    ),
                repository = repository
            )

        val result = useCase.refreshTasks(TasksLoadRequest(TaskFilter(statuses = setOf(TaskStatus.Todo))))

        assertIs<DataResult.Content<TaskSnapshot>>(result)
        assertEquals(ServerId("server-1"), repository.lastServerId)
        assertEquals(SharedTasksScope.Server, repository.lastScope)
        assertEquals(setOf(TaskStatus.Todo), repository.lastFilter?.statuses)
    }

    @Test
    fun sharedTasksUseCaseLoadsChannelScopeThroughRepository() = runBlocking {
        val repository =
            FakeTasksRepository(
                loadResult =
                    DataResult.Content(
                        TaskSnapshot(
                            tasks = listOf(task("task-1", TaskStatus.Todo)),
                            summaryCounts = mapOf(TaskStatus.Todo to 1)
                        )
                    )
            )
        val channelId = ChannelId("thread-channel-1")
        val useCase =
            SharedTasksUseCase(
                authStore =
                    InMemorySlockAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                            accessToken = "token",
                            refreshToken = "refresh",
                            selectedServerId = "server-1"
                        )
                    ),
                repository = repository,
                scope = SharedTasksScope.Channel(channelId)
            )

        val result = useCase.refreshTasks(TasksLoadRequest())

        assertIs<DataResult.Content<TaskSnapshot>>(result)
        assertEquals(ServerId("server-1"), repository.lastServerId)
        assertEquals(SharedTasksScope.Channel(channelId), repository.lastScope)
    }

    @Test
    fun sharedTasksRepositoryRefreshWritesRemoteResultIntoLocalSource() = runBlocking {
        val serverId = ServerId("server-1")
        val local = InMemorySharedTasksLocalDataSource()
        val remote =
            FakeTasksRemoteDataSource(
                loadResult =
                    DataResult.Content(
                        TaskSnapshot(
                            tasks =
                                listOf(
                                    task("task-1", TaskStatus.Todo),
                                    task("task-2", TaskStatus.Done)
                                ),
                            summaryCounts = emptyMap()
                        )
                    )
            )
        val repository =
            SharedTasksDataRepository(
                localDataSource = local,
                remoteDataSource = remote
            )

        val refreshResult =
            repository.refreshTasks(
                serverId,
                SharedTasksScope.Server,
                TaskFilter(statuses = setOf(TaskStatus.Todo))
            )
        val observed =
            repository.observeTasks(
                serverId,
                SharedTasksScope.Server,
                TaskFilter(statuses = setOf(TaskStatus.Todo))
            ).first()

        val refreshContent = assertIs<DataResult.Content<TaskSnapshot>>(refreshResult).data
        val observedContent = assertIs<DataResult.Content<TaskSnapshot>>(observed).data
        assertEquals(listOf("task-1"), refreshContent.tasks.map { it.id.value })
        assertEquals(listOf("task-1"), observedContent.tasks.map { it.id.value })
        assertEquals(TaskFilter(), remote.lastFilter)
    }

    @Test
    fun sharedTasksRepositoryKeepsChannelRefreshScopedToChannelCache() = runBlocking {
        val serverId = ServerId("server-1")
        val channelId = ChannelId("channel-thread")
        val local =
            InMemorySharedTasksLocalDataSource(
                mapOf(
                    serverId to
                        TaskSnapshot(
                            tasks = listOf(task("task-server", TaskStatus.Todo, channelName = "general")),
                            summaryCounts = emptyMap()
                        )
                )
            )
        val remote =
            FakeTasksRemoteDataSource(
                channelLoadResult =
                    DataResult.Content(
                        TaskSnapshot(
                            tasks = listOf(task("task-channel", TaskStatus.InProgress, channelName = "thread")),
                            summaryCounts = emptyMap()
                        )
                    )
            )
        val repository =
            SharedTasksDataRepository(
                localDataSource = local,
                remoteDataSource = remote
            )

        val channelResult =
            repository.refreshTasks(
                serverId = serverId,
                scope = SharedTasksScope.Channel(channelId),
                filter = TaskFilter()
            )
        val serverObserved = repository.observeTasks(serverId, SharedTasksScope.Server, TaskFilter()).first()
        val channelObserved = repository.observeTasks(serverId, SharedTasksScope.Channel(channelId), TaskFilter()).first()

        assertEquals(listOf("task-channel"), assertIs<DataResult.Content<TaskSnapshot>>(channelResult).data.tasks.map { it.id.value })
        assertEquals(listOf("task-server"), assertIs<DataResult.Content<TaskSnapshot>>(serverObserved).data.tasks.map { it.id.value })
        assertEquals(listOf("task-channel"), assertIs<DataResult.Content<TaskSnapshot>>(channelObserved).data.tasks.map { it.id.value })
        assertEquals(channelId, remote.lastChannelId)
    }

    @Test
    fun sharedTasksStoreUsesMviRuntimeNoopGateAndTransitionLog() {
        val transitions = mutableListOf<MviTransition<SharedTasksIntent>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedTasksStore(
                initialState = TasksUiState(ScreenLoadState.Loading),
                scope = scope,
                transitionLogger = transitions::add
            )

        val emitted = store.accept(SharedTasksIntent.NavigationFailed(null))

        assertFalse(emitted)
        assertEquals(TasksUiState(ScreenLoadState.Loading), store.state.value)
        assertEquals(
            MviTransition<SharedTasksIntent>(
                intent = SharedTasksIntent.NavigationFailed(null),
                emitted = false,
                reason = "unchanged"
            ),
            transitions.single()
        )
        scope.cancel()
    }

    @Test
    fun loadingKeepsVisibleContentAndMarksRefresh() {
        val state =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.Todo to 1),
                            tasks = listOf(task("task-1", TaskStatus.Todo))
                        )
                    ),
                navigationErrorCode = "missing_task_reference"
            )

        val next = state.reduce(SharedTasksIntent.StartLoading(filter = TaskStatus.InReview))

        assertEquals(TaskStatus.InReview, next.selectedFilter)
        assertEquals(true, next.refreshing)
        assertEquals(null, next.navigationErrorCode)
        assertIs<ScreenLoadState.Content<TasksContent>>(next.loadState)
    }

    @Test
    fun optimisticStatusUpdateAndFailureRestorePreviousTask() {
        val previousTask = task("task-1", TaskStatus.Todo)
        val state =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.Todo to 1),
                            tasks = listOf(previousTask)
                        )
                    )
            )

        val optimistic = state.reduce(SharedTasksIntent.UpdateTaskStatus("task-1", TaskStatus.Done))
        val optimisticContent = assertIs<ScreenLoadState.Content<TasksContent>>(optimistic.loadState).data
        assertEquals(TaskStatus.Done, optimisticContent.tasks.single().status)
        assertEquals(1, optimisticContent.summaryCounts[TaskStatus.Done])
        assertEquals("task-1", optimistic.statusUpdatingTaskId)

        val restored =
            optimistic.reduce(
                SharedTasksIntent.TaskStatusUpdateFailed(
                    previousTask = previousTask,
                    errorCode = "task_status_failed"
                )
            )
        val restoredContent = assertIs<ScreenLoadState.Content<TasksContent>>(restored.loadState).data
        assertEquals(TaskStatus.Todo, restoredContent.tasks.single().status)
        assertEquals(1, restoredContent.summaryCounts[TaskStatus.Todo])
        assertEquals(null, restored.statusUpdatingTaskId)
        assertEquals("task_status_failed", restored.statusUpdateErrorCode)
    }

    @Test
    fun setContentDerivesSummaryCountsWhenSnapshotCountsAreMissing() {
        val state = TasksUiState(ScreenLoadState.Loading)

        val next =
            state.reduce(
                SharedTasksIntent.SetContent(
                    snapshot =
                        TaskSnapshot(
                            tasks =
                                listOf(
                                    task("task-1", TaskStatus.Todo),
                                    task("task-2", TaskStatus.InProgress)
                                ),
                            summaryCounts = emptyMap()
                        ),
                    selectedFilter = TaskStatus.Todo
                )
            )

        val content = assertIs<ScreenLoadState.Content<TasksContent>>(next.loadState).data
        assertEquals(TaskStatus.Todo, next.selectedFilter)
        assertEquals(false, next.refreshing)
        assertEquals(1, content.summaryCounts[TaskStatus.Todo])
        assertEquals(1, content.summaryCounts[TaskStatus.InProgress])
    }

    @Test
    fun presentationReducerPrunesChannelsAndFiltersVisibleTasks() {
        val content =
            TasksContent(
                summaryCounts = emptyMap(),
                tasks =
                    listOf(
                        task("task-1", TaskStatus.Todo, channelName = "android"),
                        task("task-2", TaskStatus.InReview, channelName = "kmp"),
                        task("task-3", TaskStatus.Todo, channelName = "kmp")
                    )
            )

        val presentation =
            TasksPresentationState()
                .reduce(TasksPresentationIntent.ToggleChannel("kmp"))
                .reduce(TasksPresentationIntent.ToggleChannel("missing"))
                .reduce(TasksPresentationIntent.PruneUnavailableChannels(setOf("android", "kmp")))
                .reduce(TasksPresentationIntent.SelectMode(TasksViewMode.List))

        val visible =
            content.visibleContent(
                selectedFilter = TaskStatus.Todo,
                presentationState = presentation,
                showChannelFilter = true
            )

        assertEquals(TasksViewMode.List, presentation.selectedMode)
        assertEquals(setOf("kmp"), visible.selectedChannelNames)
        assertEquals(listOf("android", "kmp"), visible.channelOptions)
        assertEquals(listOf("task-3"), visible.filteredTasks.map { it.id.value })
        assertEquals(2, visible.channelScopedContent.tasks.size)
        assertEquals(1, visible.channelScopedContent.summaryCounts[TaskStatus.Todo])
        assertEquals(1, visible.channelScopedContent.summaryCounts[TaskStatus.InReview])
    }

    @Test
    fun refreshResultKeepsContentVisibleOnEmptyAndError() {
        val current =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.Todo to 1),
                            tasks = listOf(task("task-1", TaskStatus.Todo))
                        )
                    ),
                refreshing = true
            )

        val empty = current.mergeTasksRefreshResult(DataResult.Empty(EmptyReason.NoTasks), TaskStatus.Done)
        val emptyContent = assertIs<ScreenLoadState.Content<TasksContent>>(empty.loadState).data
        assertEquals(listOf("task-1"), emptyContent.tasks.map { it.id.value })
        assertEquals(TaskStatus.Done, empty.selectedFilter)
        assertEquals("NoTasks", empty.navigationErrorCode)
        assertEquals(false, empty.refreshing)

        val failed =
            current.mergeTasksRefreshResult(
                DataResult.Error(ScreenError("tasks_refresh_failed")),
                TaskStatus.InReview
            )
        assertIs<ScreenLoadState.Content<TasksContent>>(failed.loadState)
        assertEquals(TaskStatus.InReview, failed.selectedFilter)
        assertEquals("tasks_refresh_failed", failed.navigationErrorCode)
    }

    @Test
    fun taskSnapshotFilteringAndThreadTaskStateAreShared() {
        val snapshot =
            TaskSnapshot(
                tasks =
                    listOf(
                        task("task-1", TaskStatus.Todo, channelName = "android"),
                        task("task-2", TaskStatus.Done, channelName = "kmp")
                    ),
                summaryCounts = emptyMap()
            )

        val filteredByStatus = snapshot.filteredForTasksFilter(TaskStatus.Todo)
        assertEquals(listOf("task-1"), filteredByStatus.tasks.map { it.id.value })
        assertEquals(1, filteredByStatus.summaryCounts[TaskStatus.Todo])
        assertEquals(1, filteredByStatus.summaryCounts[TaskStatus.Done])

        val filteredByConversation = snapshot.filteredForConversationIds(setOf("channel-1"))
        assertEquals(listOf("task-1", "task-2"), filteredByConversation.tasks.map { it.id.value })

        val threadState = snapshot.tasks.toTasksUiStateForThreadTasks()
        val threadContent = assertIs<ScreenLoadState.Content<TasksContent>>(threadState.loadState).data
        assertEquals(2, threadContent.tasks.size)
        assertEquals(1, threadContent.summaryCounts[TaskStatus.Done])
    }

    @Test
    fun boardColumnsAndTaskDisplayLabelsAreShared() {
        val assignedTask =
            task(
                "task-42",
                TaskStatus.InProgress,
                channelName = "kmp",
                title = "Task #42: migrate state",
                assignee = member("stdrc")
            )
        val uuidAssigneeTask =
            task(
                "task-43",
                TaskStatus.InProgress,
                title = "#43",
                assignee = member("550e8400-e29b-41d4-a716-446655440000")
            )

        val content =
            TasksContent(
                summaryCounts = emptyMap(),
                tasks = listOf(assignedTask, uuidAssigneeTask, task("task-44", TaskStatus.Done))
            )

        val columns = content.boardColumns(renderLimit = 1)
        val inProgressColumn = columns.single { it.status == TaskStatus.InProgress }
        assertEquals(2, inProgressColumn.totalCount)
        assertEquals(listOf("task-42"), inProgressColumn.tasks.map { it.id.value })

        assertEquals("#kmp", assignedTask.tasksSourceLabel())
        assertEquals("#42 migrate state", assignedTask.tasksDisplayTitle())
        assertEquals("stdrc", assignedTask.tasksAssigneeDisplayName())
        assertEquals("Task #43", uuidAssigneeTask.tasksDisplayTitle())
        assertEquals(null, uuidAssigneeTask.tasksAssigneeDisplayName())
    }

    @Test
    fun taskNetworkResponseParsesCanonicalReactShape() {
        val response =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "tasks": [
                        {
                          "id": "task-65",
                          "channelId": "channel-kmp",
                          "channelName": "KMP",
                          "taskNumber": 65,
                          "title": "migrate Android UI to shared MVI",
                          "status": "in_review",
                          "createdByType": "user",
                          "createdById": "user-1",
                          "claimedById": "agent-kmp",
                          "claimedByName": "KMP-专家",
                          "claimedByType": "agent"
                        }
                      ]
                    }
                    """.trimIndent()
            )

        val result = response.toTasksResult()
        val content = assertIs<DataResult.Content<TaskSnapshot>>(result)
        val task = content.data.tasks.single()

        assertEquals("task-65", task.id.value)
        assertEquals(TaskStatus.InReview, task.status)
        assertEquals("#65 migrate Android UI to shared MVI", task.tasksDisplayTitle())
        assertEquals("KMP", task.channelName)
        assertEquals("KMP-专家", task.tasksAssigneeDisplayName())
        assertEquals(1, content.data.summaryCounts[TaskStatus.InReview])
    }

    @Test
    fun taskNetworkResponseRejectsGuessedListWrappers() {
        val response =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "data": [
                        {
                          "taskId": "task-65",
                          "number": 65,
                          "messagePreview": "Task #65: migrate Android UI to shared MVI"
                        }
                      ]
                    }
                    """.trimIndent()
            )

        val result = response.toTasksResult(source = "tasks_server")

        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_server_contract_mismatch", error.error.code)
    }

    @Test
    fun taskActionResponseRejectsGuessedActionWrappers() {
        val response =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"data":{"taskId":"task-65","messagePreview":"Task #65: migrate Android UI to shared MVI"}}"""
            )

        val result = response.toTaskActionResult(source = "tasks_status")

        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_status_contract_mismatch", error.error.code)
    }

    private fun task(
        id: String,
        status: TaskStatus,
        channelName: String? = null,
        title: String = "Task $id",
        assignee: MemberProfile? = null
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = title,
            status = status,
            taskNumber = id.substringAfter("task-").toIntOrNull(),
            messageId = MessageId("message-$id"),
            channelId = ChannelId("channel-1"),
            channelName = channelName,
            assignee = assignee,
            updatedAtLabel = "now"
        )

    private fun member(displayName: String): MemberProfile =
        MemberProfile(
            id = MemberId("member-$displayName"),
            displayName = displayName,
            handle = displayName,
            kind = MemberKind.Agent,
            presence = Presence.Active
        )

    private class FakeTasksRepository(
        private val loadResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured")),
        private val updateResult: DataResult<TaskActionResult> =
            DataResult.Error(ScreenError("not_configured")),
        private val createResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured"))
    ) : SharedTasksRepository {
        var loadCalls = 0
            private set
        var lastServerId: ServerId? = null
            private set
        var lastScope: SharedTasksScope? = null
            private set
        var lastFilter: TaskFilter? = null
            private set

        override fun observeTasks(
            serverId: ServerId,
            scope: SharedTasksScope,
            filter: TaskFilter
        ): Flow<DataResult<TaskSnapshot>> =
            flowOf(loadResult)

        override suspend fun refreshTasks(
            serverId: ServerId,
            scope: SharedTasksScope,
            filter: TaskFilter
        ): DataResult<TaskSnapshot> {
            loadCalls += 1
            lastServerId = serverId
            lastScope = scope
            lastFilter = filter
            return loadResult
        }

        override suspend fun updateTaskStatus(
            serverId: ServerId,
            taskId: TaskId,
            status: TaskStatus
        ): DataResult<TaskActionResult> =
            updateResult

        override suspend fun createTasks(
            serverId: ServerId,
            channelId: ChannelId,
            titles: List<String>
        ): DataResult<TaskSnapshot> =
            createResult
    }

    private class FakeTasksRemoteDataSource(
        private val loadResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured")),
        private val channelLoadResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured")),
        private val updateResult: DataResult<TaskActionResult> =
            DataResult.Error(ScreenError("not_configured")),
        private val createResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured"))
    ) : SharedTasksRemoteDataSource {
        var lastFilter: TaskFilter? = null
            private set
        var lastChannelId: ChannelId? = null
            private set

        override suspend fun loadTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot> {
            lastFilter = filter
            return loadResult
        }

        override suspend fun loadChannelTasks(channelId: ChannelId, filter: TaskFilter): DataResult<TaskSnapshot> {
            lastChannelId = channelId
            lastFilter = filter
            return channelLoadResult
        }

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult> =
            updateResult

        override suspend fun createTasks(channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot> =
            createResult
    }
}
