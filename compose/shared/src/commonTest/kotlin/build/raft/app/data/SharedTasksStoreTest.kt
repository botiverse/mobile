package build.raft.app.data

import build.raft.app.TaskOpenTarget
import build.raft.app.taskOpenSurface
import build.raft.app.taskOpenTarget
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedTasksStoreTest {

    @Test
    fun sharedTasksDataStoreProjectsChannelTasksFromServerSnapshot() = runBlocking {
        val store = SharedTasksDataStore()

        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1"), channelName = "general"),
                            task("task-2", TaskStatus.Done, channelId = ChannelId("channel-2"), channelName = "random")
                        ),
                    summaryCounts = emptyMap()
                )
        )

        val slice = store.channelProjectionFlow("server-1", "channel-1", null).first()

        assertEquals(SharedTasksDataStoreStatus.Loaded, slice.status)
        assertEquals(false, slice.isExactScopeLoaded)
        assertEquals(listOf("task-1"), slice.tasks.map { it.id.value })
    }

    @Test
    fun authoritativeChannelRefreshReconcilesServerProjectionWithoutDroppingOtherChannels() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            task("task-339", TaskStatus.InProgress, channelId = ChannelId("channel-1")),
                            task("task-other", TaskStatus.Todo, channelId = ChannelId("channel-2"))
                        ),
                    summaryCounts = emptyMap()
                )
        )

        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-339", TaskStatus.Done, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )

        val serverProjection = store.projection("server-1", SharedTasksScope.Server)
        assertEquals(SharedTasksDataStoreStatus.Loaded, serverProjection.status)
        assertEquals(
            listOf(
                "task-339" to TaskStatus.Done,
                "task-other" to TaskStatus.Todo
            ),
            serverProjection.tasks.map { task -> task.id.value to task.status }
        )
    }

    @Test
    fun sharedTasksDataStoreDoesNotLetOlderLoadedChannelMaskNewerServerSnapshot() = runBlocking {
        val store = SharedTasksDataStore()
        val oldTask = task("task-old", TaskStatus.Todo, channelId = ChannelId("channel-1"))
        val newTask = task("task-new", TaskStatus.InProgress, channelId = ChannelId("channel-1"))
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = listOf(oldTask), summaryCounts = emptyMap())
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = listOf(newTask), summaryCounts = emptyMap())
        )

        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))

        assertEquals(SharedTasksDataStoreStatus.Loaded, projection.status)
        assertEquals(false, projection.isExactScopeLoaded)
        assertEquals(listOf("task-new"), projection.tasks.map { it.id.value })
    }

    @Test
    fun sharedTasksDataStoreUpsertTaskUpdatesPublishedChannelProjection() = runBlocking {
        val store = SharedTasksDataStore()
        val original = task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1"))
        val updated = original.copy(status = TaskStatus.InReview)

        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(original),
                    summaryCounts = emptyMap()
                )
        )

        store.upsertTask("server-1", updated)

        val slice = store.channelProjectionFlow("server-1", "channel-1", null).first()

        assertEquals(SharedTasksDataStoreStatus.Loaded, slice.status)
        assertEquals(TaskStatus.InReview, slice.tasks.single().status)
    }

    @Test
    fun sharedTasksDataStoreFoldsOneCanonicalFactAcrossTwoLocalRoutes() = runBlocking {
        val store = SharedTasksDataStore()
        val canonical = task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-a"), revision = 7L)

        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask("server-a", canonical))
        assertEquals(
            SharedTaskFactFoldOutcome.StaleIgnored,
            store.upsertTask("server-b", canonical.copy(channelId = ChannelId("channel-b")))
        )

        val serverA = store.projection("server-a", SharedTasksScope.Server).tasks.single()
        val serverB = store.projection("server-b", SharedTasksScope.Server).tasks.single()
        assertEquals(ChannelId("channel-a"), serverA.channelId)
        assertEquals(ChannelId("channel-b"), serverB.channelId)
        assertEquals(serverA.copy(channelId = null), serverB.copy(channelId = null))
        assertEquals(
            ChannelId("channel-a"),
            store.mutationProjection("server-a", canonical.id, expectedRevision = 7L)?.channelId
        )
        assertEquals(
            ChannelId("channel-b"),
            store.mutationProjection("server-b", canonical.id, expectedRevision = 7L)?.channelId
        )
    }

    @Test
    fun sameRevisionTaskUpdatedThatCreatesTheConversationReplacesThePendingTask() = runBlocking {
        // artin 1.22.0: the first send into a task without a conversation creates it (POST
        // /tasks/:id/conversation, no revision bump); its `task:updated` was dropped as stale, so
        // the task kept opening as the empty pending page and the sent message was never shown.
        val store = SharedTasksDataStore()
        val pending =
            task("task-158", TaskStatus.InProgress, channelId = ChannelId("channel-1"), revision = 4L)
                .copy(conversationAnchored = true)
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = listOf(pending), summaryCounts = emptyMap())
        )
        assertEquals(
            TaskOpenTarget.PendingConversation,
            store.channelProjectionFlow("server-1", "channel-1", null).first().tasks.single().taskOpenTarget()
        )

        val conversation = ChannelId("task-conversation-158")
        assertEquals(
            SharedTaskFactFoldOutcome.Accepted,
            store.upsertTask("server-1", pending.copy(conversationChannelId = conversation))
        )

        val opened = store.channelProjectionFlow("server-1", "channel-1", null).first().tasks.single()
        assertEquals(conversation, opened.conversationChannelId)
        assertEquals(TaskOpenTarget.Conversation(conversation), opened.taskOpenTarget())
        assertEquals(null, opened.taskOpenSurface())

        // A copy of the same revision read before the conversation existed does not take it away.
        assertEquals(SharedTaskFactFoldOutcome.StaleIgnored, store.upsertTask("server-1", pending))
        assertEquals(
            conversation,
            store.channelProjectionFlow("server-1", "channel-1", null).first().tasks.single().conversationChannelId
        )
    }

    @Test
    fun sharedTasksDataStoreRejectsStaleRevisionAndFailsClosedOnEqualRevisionConflict() = runBlocking {
        val store = SharedTasksDataStore()
        val revisionOne = task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-a"), revision = 1L)
        val revisionTwo = revisionOne.copy(status = TaskStatus.Done, channelId = ChannelId("channel-b"), revision = 2L)

        store.upsertTask("server-a", revisionOne)
        store.upsertTask("server-b", revisionTwo)
        assertEquals(
            SharedTaskFactFoldOutcome.StaleIgnored,
            store.upsertTask("server-a", revisionOne)
        )
        assertEquals(
            TaskStatus.Done,
            store.projection("server-a", SharedTasksScope.Server).tasks.single().status
        )
        assertEquals(
            ChannelId("channel-a"),
            store.projection("server-a", SharedTasksScope.Server).tasks.single().channelId
        )
        assertEquals(null, store.mutationProjection("server-a", revisionOne.id, expectedRevision = 1L))

        val conflicting = revisionTwo.copy(title = "conflicting payload", channelId = ChannelId("channel-a"))
        assertEquals(SharedTaskFactFoldOutcome.Conflict, store.upsertTask("server-a", conflicting))
        val serverA = store.projection("server-a", SharedTasksScope.Server)
        val serverB = store.projection("server-b", SharedTasksScope.Server)
        assertEquals(emptyList(), serverA.tasks)
        assertEquals(emptyList(), serverB.tasks)
        assertEquals(true, serverA.needsCanonicalRefetch)
        assertEquals(true, serverB.needsCanonicalRefetch)
        assertEquals(null, store.mutationProjection("server-b", revisionTwo.id, expectedRevision = 2L))
    }

    @Test
    fun historicalJointTaskWireReadOnlyReasonBlocksClaim() {
        val readOnly =
            TaskWireDto(
                id = "task-joint-old",
                revision = 3L,
                channelId = "joint-local",
                status = "todo",
                readOnlyReason = "historical_joint_task"
            ).toTaskSummary()
        val writable = TaskWireDto(id = "task-new", revision = 1L, channelId = "joint-local", status = "todo").toTaskSummary()

        assertEquals("historical_joint_task", readOnly?.readOnlyReason)
        assertEquals(false, readOnly?.canClaim)
        assertEquals(null, writable?.readOnlyReason)
        assertEquals(true, writable?.canClaim)
    }

    @Test
    fun sharedTasksDataStoreDoesNotLoseRealtimeUpsertWhenOlderEmptyBackfillCompletes() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedTasksDataStore(appScope)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val load =
            async {
                store.loadSnapshot(
                    serverId = "server-1",
                    scope = SharedTasksScope.Channel(ChannelId("channel-1"))
                ) {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                    DataResult.Empty(EmptyReason.NoTasks)
                }
            }
        requestStarted.await()

        store.upsertTask(
            "server-1",
            task("task-recent", TaskStatus.InProgress, channelId = ChannelId("channel-1"))
        )
        releaseRequest.complete(Unit)
        val committed = assertIs<DataResult.Content<TaskSnapshot>>(load.await()).data
        assertEquals(listOf("task-recent"), committed.tasks.map { it.id.value })

        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(SharedTasksDataStoreStatus.Loaded, projection.status)
        assertEquals(listOf("task-recent"), projection.tasks.map { it.id.value })
        appScope.cancel()
    }

    @Test
    fun sharedTasksDataStoreRealtimeRemoveNormalizesOlderContentBackfillToEmpty() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedTasksDataStore(appScope)
        val stale = task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1"))
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val load =
            async {
                store.loadSnapshot(
                    serverId = "server-1",
                    scope = SharedTasksScope.Channel(ChannelId("channel-1"))
                ) {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                    DataResult.Content(TaskSnapshot(tasks = listOf(stale), summaryCounts = emptyMap()))
                }
            }
        requestStarted.await()

        store.removeTask("server-1", stale.id)
        releaseRequest.complete(Unit)

        assertIs<DataResult.Empty>(load.await())
        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(SharedTasksDataStoreStatus.Loaded, projection.status)
        assertEquals(emptyList(), projection.tasks)
        appScope.cancel()
    }

    @Test
    fun sharedTasksDataStoreRealtimeChannelMoveRemovesOldLoadedProjection() = runBlocking {
        val store = SharedTasksDataStore()
        val original = task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-a"))
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-a")),
            snapshot = TaskSnapshot(tasks = listOf(original), summaryCounts = emptyMap())
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-b")),
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )

        store.upsertTask("server-1", original.copy(channelId = ChannelId("channel-b")))

        assertEquals(
            emptyList(),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-a"))).tasks
        )
        assertEquals(
            listOf("task-1"),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-b"))).tasks.map { it.id.value }
        )
    }

    @Test
    fun sharedTasksViewModelDoesNotReuseLoadedProjectionAcrossAccountsOnSameServer() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-a", email = "a@slock.ai"),
                    accessToken = "token-a",
                    refreshToken = "refresh-a",
                    selectedServerId = "server-1"
                )
            )
        val sharedStore =
            SharedTasksDataStore(
                scope = appScope,
                ownerIdentityProvider = { authStore.readSession()?.user?.id }
            )
        sharedStore.bindCurrentOwnerIdentity()
        sharedStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-user-a", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )
        authStore.replaceAuthenticatedSession(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-b", email = "b@slock.ai"),
                accessToken = "token-b",
                refreshToken = "refresh-b",
                selectedServerId = "server-1"
            )
        )
        val userBSnapshot =
            TaskSnapshot(
                tasks = listOf(task("task-user-b", TaskStatus.InProgress, channelId = ChannelId("channel-1"))),
                summaryCounts = emptyMap()
            )
        val repository = FakeTasksRepository(loadResult = DataResult.Content(userBSnapshot))
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = noOpTasksDataSource(),
                scope = SharedTasksScope.Channel(ChannelId("channel-1")),
                sharedTasksDataStore = sharedStore,
                runtimeScopeOverride = runtimeScope,
                useCase =
                    SharedTasksUseCase(
                        authStore = authStore,
                        repository = repository,
                        scope = SharedTasksScope.Channel(ChannelId("channel-1"))
                    )
            )
        val observedTaskIds = mutableListOf<List<String>>()
        val observationJob =
            runtimeScope.launch {
                viewModel.state.collect { state ->
                    val taskIds =
                        (state.loadState as? ScreenLoadState.Content)
                            ?.data
                            ?.tasks
                            ?.map { it.id.value }
                            .orEmpty()
                    observedTaskIds += taskIds
                }
            }

        viewModel.dispatch(SharedTasksRouteIntent.Start)
        val content =
            withTimeout(2_000L) {
                viewModel.state.first { state ->
                    (state.loadState as? ScreenLoadState.Content)?.data?.tasks?.any { it.id.value == "task-user-b" } == true
                }
            }.loadState

        assertEquals(
            listOf("task-user-b"),
            assertIs<ScreenLoadState.Content<TasksContent>>(content).data.tasks.map { it.id.value }
        )
        assertEquals(1, repository.loadCalls)
        assertTrue(observedTaskIds.none { taskIds -> "task-user-a" in taskIds })
        observationJob.cancel()
        runtimeScope.cancel()
        appScope.cancel()
    }

    @Test
    fun emptyTasksResultCountsAsFreshCompletedSnapshot() {
        val emptyState = TasksUiState(ScreenLoadState.Empty(EmptyReason.NoTasks))
        val emptyResult: DataResult<TaskSnapshot> = DataResult.Empty(EmptyReason.NoTasks)

        assertTrue(emptyState.hasCompletedTasksSnapshot())
        assertTrue(emptyResult.isCompletedTasksSnapshot())
        assertEquals(
            TasksRefreshSkipReason.FreshSnapshot,
            tasksRefreshSkipReason(
                inFlight = false,
                hasSnapshot = emptyState.hasCompletedTasksSnapshot(),
                snapshotAgeMs = 1_000L,
                staleWindowMs = 15_000L,
                forceRefresh = false
            )
        )
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
    fun idempotentTerminalConflictKeepsOptimisticStatusAsSuccess() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val previousTask = task("task-1", TaskStatus.InReview)
        val authStore =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "token",
                    refreshToken = "refresh",
                    selectedServerId = "server-1"
                )
            )
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = noOpTasksDataSource(),
                runtimeScopeOverride = runtimeScope,
                useCase =
                    SharedTasksUseCase(
                        authStore = authStore,
                        repository =
                            FakeTasksRepository(
                                updateResult =
                                    DataResult.Error(
                                        ScreenError(
                                            code = TaskStatus.Closed.taskStatusAlreadyAppliedErrorCode(),
                                            retryable = false
                                        )
                                    )
                            )
                    )
            )
        viewModel.dispatch(
            SharedTasksRouteIntent.SeedContent(
                TaskSnapshot(tasks = listOf(previousTask), summaryCounts = mapOf(TaskStatus.InReview to 1))
            )
        )

        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(previousTask, TaskStatus.Closed))
        yield()

        val state = viewModel.state.value
        val content = assertIs<ScreenLoadState.Content<TasksContent>>(state.loadState).data
        assertEquals(TaskStatus.Closed, content.tasks.single().status)
        assertEquals(null, state.statusUpdatingTaskId)
        assertEquals(null, state.statusUpdateErrorCode)
        runtimeScope.cancel()
    }

    @Test
    fun taskStatusUpdateSkipReasonRejectsAlreadyAppliedAndStaleActions() {
        val currentTask = task("task-1", TaskStatus.InProgress)
        val staleTask = currentTask.copy(status = TaskStatus.Todo)
        val state =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.InProgress to 1),
                            tasks = listOf(currentTask)
                        )
                    )
            )

        assertEquals(
            TaskStatusUpdateSkipReason.AlreadyCurrent,
            state.taskStatusUpdateSkipReason(staleTask, TaskStatus.InProgress)
        )
        assertEquals(
            TaskStatusUpdateSkipReason.StaleTaskStatus,
            state.taskStatusUpdateSkipReason(staleTask, TaskStatus.InReview)
        )
        assertEquals(null, state.taskStatusUpdateSkipReason(currentTask, TaskStatus.InReview))
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
    fun seedContentFillsLoadingStateButDoesNotReplaceCompletedSnapshot() {
        val assigned = member("KMP-专家")
        val canonicalTask = task("task-1", TaskStatus.InProgress, assignee = assigned)
        val staleThreadPayloadTask = task("task-1", TaskStatus.Todo, assignee = null)
        val seedSnapshot =
            TaskSnapshot(
                tasks = listOf(staleThreadPayloadTask),
                summaryCounts = mapOf(TaskStatus.Todo to 1)
            )
        val loading = TasksUiState(ScreenLoadState.Loading)

        val seeded = loading.reduce(SharedTasksIntent.SeedContent(seedSnapshot, selectedFilter = TaskStatus.Todo))
        val seededContent = assertIs<ScreenLoadState.Content<TasksContent>>(seeded.loadState).data
        assertEquals(TaskStatus.Todo, seeded.selectedFilter)
        assertEquals(listOf(staleThreadPayloadTask), seededContent.tasks)

        val canonical =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.InProgress to 1),
                            tasks = listOf(canonicalTask)
                        )
                    ),
                selectedFilter = TaskStatus.InProgress,
                refreshing = true
            )

        val preserved = canonical.reduce(SharedTasksIntent.SeedContent(seedSnapshot, selectedFilter = TaskStatus.Todo))
        val preservedContent = assertIs<ScreenLoadState.Content<TasksContent>>(preserved.loadState).data
        assertEquals(TaskStatus.InProgress, preserved.selectedFilter)
        assertEquals(true, preserved.refreshing)
        assertEquals(listOf(canonicalTask), preservedContent.tasks)
        assertEquals(assigned, preservedContent.tasks.single().assignee)
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
    fun assigneeCandidatesDedupeTheSameGlobalHumanAcrossServerProjections() {
        val host = MemberProfile(MemberId("global-user-1"), "Artin Host", "artin", MemberKind.Human)
        val peer = MemberProfile(MemberId("global-user-1"), "Artin Peer", "artin", MemberKind.Human)
        val agent = MemberProfile(MemberId("global-user-1"), "Agent One", "agent-one", MemberKind.Agent)

        val visible =
            TasksContent(summaryCounts = emptyMap(), tasks = emptyList()).visibleContent(
                selectedFilter = null,
                presentationState = TasksPresentationState(),
                showChannelFilter = true,
                identityOptions = listOf(host, peer, agent)
            )

        assertEquals(
            setOf("user:global-user-1", "agent:global-user-1"),
            visible.assigneeOptions.mapTo(mutableSetOf()) { it.tasksIdentityFilterId() }
        )
        assertEquals(2, visible.assigneeOptions.size)
        assertEquals(visible.creatorOptions, visible.assigneeOptions)
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
        assertEquals("user:user-1", task.creator?.tasksIdentityFilterId())
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

    private fun task(
        id: String,
        status: TaskStatus,
        channelId: ChannelId? = ChannelId("channel-1"),
        channelName: String? = null,
        title: String = "Task $id",
        assignee: MemberProfile? = null,
        creator: MemberProfile? = null,
        revision: Long? = null
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = title,
            status = status,
            taskNumber = id.substringAfter("task-").toIntOrNull(),
            messageId = MessageId("message-$id"),
            channelId = channelId,
            channelName = channelName,
            assignee = assignee,
            creator = creator,
            revision = revision,
            updatedAtLabel = "now"
        )

    private fun member(displayName: String, kind: MemberKind = MemberKind.Agent): MemberProfile =
        MemberProfile(
            id = MemberId("member-$displayName"),
            displayName = displayName,
            handle = displayName,
            kind = kind,
            presence = Presence.Active
        )

    private fun noOpTasksDataSource(): SharedTasksDataSource =
        SharedTasksDataSource(
            api =
                object : RaftTasksApi {
                    override suspend fun serverTasks(
                        serverId: ServerId,
                        priority: DataRequestPriority
                    ): HomeNetworkResponse = HomeNetworkResponse(code = 500, bodyText = null)

                    override suspend fun updateTaskStatus(
                        taskId: TaskId,
                        status: TaskStatus
                    ): HomeNetworkResponse = HomeNetworkResponse(code = 500, bodyText = null)
                }
        )

    @Test
    fun assignTaskCommitsAssigneeAndClearsBusyState() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val previousTask = task("task-1", TaskStatus.Todo, revision = 4L)
        val assignee = MemberProfile(MemberId("user-2"), "Nathan Peer", "nathan", MemberKind.Human)
        val authStore =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "token",
                    refreshToken = "refresh",
                    selectedServerId = "server-1"
                )
            )
        val repository =
            FakeTasksRepository(
                assignResult =
                    DataResult.Content(
                        TaskActionResult(task("task-1", TaskStatus.Todo, assignee = assignee, revision = 5L))
                    )
            )
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = noOpTasksDataSource(),
                runtimeScopeOverride = runtimeScope,
                useCase = SharedTasksUseCase(authStore = authStore, repository = repository)
            )
        viewModel.dispatch(
            SharedTasksRouteIntent.SeedContent(
                TaskSnapshot(tasks = listOf(previousTask), summaryCounts = mapOf(TaskStatus.Todo to 1))
            )
        )

        viewModel.dispatch(SharedTasksRouteIntent.AssignTask(previousTask, assignee))
        yield()

        val state = viewModel.state.value
        val content = assertIs<ScreenLoadState.Content<TasksContent>>(state.loadState).data
        assertEquals(MemberId("user-2"), content.tasks.single().assignee?.id)
        assertEquals(null, state.statusUpdatingTaskId)
        assertEquals(null, state.statusUpdateErrorCode)
        assertEquals(1, repository.assignCalls)
        assertEquals(MemberId("user-2"), repository.lastAssignee?.id)
        assertEquals(4L, repository.lastExpectedRevision)
        runtimeScope.cancel()
    }

    @Test
    fun statusAndAssignHttpRefusalsEmitOperationAndServerMessageWithoutSilentDrop() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val previousTask = task("task-1", TaskStatus.Todo, revision = 4L)
        val assignee = MemberProfile(MemberId("user-2"), "Nathan Peer", "nathan", MemberKind.Human)
        val traceId = "62ac7fa79a3785a6bee414a074f82634"
        val authStore =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "token",
                    refreshToken = "refresh",
                    selectedServerId = "server-1"
                )
            )
        val repository =
            FakeTasksRepository(
                updateResult =
                    DataResult.NoPermission(
                        PermissionBlocker(
                            code = "tasks_403",
                            serverMessage = "Channel is archived and tasks cannot be edited.",
                            serverCode = "channel_archived",
                            traceId = traceId,
                        )
                    ),
                assignResult =
                    DataResult.Error(
                        ScreenError(
                            code = "tasks_400",
                            retryable = false,
                            serverMessage = "Assignee is not a member of this channel.",
                            serverCode = "assignee_not_in_channel",
                            traceId = traceId,
                        )
                    ),
            )
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = noOpTasksDataSource(),
                runtimeScopeOverride = runtimeScope,
                useCase = SharedTasksUseCase(authStore = authStore, repository = repository)
            )
        viewModel.dispatch(
            SharedTasksRouteIntent.SeedContent(
                TaskSnapshot(tasks = listOf(previousTask), summaryCounts = mapOf(TaskStatus.Todo to 1))
            )
        )

        val statusFailure = CompletableDeferred<SharedTasksEvent>()
        val statusCollector = launch(start = CoroutineStart.UNDISPATCHED) { statusFailure.complete(viewModel.uiEvents.first()) }
        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(previousTask, TaskStatus.Done))
        val statusEvent = assertIs<SharedTasksEvent.OperationFailed>(withTimeout(2_000L) { statusFailure.await() })
        statusCollector.cancel()
        assertEquals("tasks_403", statusEvent.code)
        assertEquals(SharedTasksOperation.UpdateStatus, statusEvent.operation)
        assertTrue(statusEvent.isStatusFailure())
        assertFalse(statusEvent.isAssignFailure())
        assertEquals("Channel is archived and tasks cannot be edited.", statusEvent.serverMessage)
        assertEquals("channel_archived", statusEvent.serverCode)
        assertEquals(traceId, statusEvent.traceId)

        val assignFailure = CompletableDeferred<SharedTasksEvent>()
        val assignCollector = launch(start = CoroutineStart.UNDISPATCHED) { assignFailure.complete(viewModel.uiEvents.first()) }
        viewModel.dispatch(SharedTasksRouteIntent.AssignTask(previousTask, assignee))
        val assignEvent = assertIs<SharedTasksEvent.OperationFailed>(withTimeout(2_000L) { assignFailure.await() })
        assignCollector.cancel()
        assertEquals("tasks_400", assignEvent.code)
        assertEquals(SharedTasksOperation.AssignTask, assignEvent.operation)
        assertTrue(assignEvent.isAssignFailure())
        assertFalse(assignEvent.isStatusFailure())
        assertEquals("Assignee is not a member of this channel.", assignEvent.serverMessage)
        assertEquals("assignee_not_in_channel", assignEvent.serverCode)
        assertEquals(traceId, assignEvent.traceId)
        val screenError = assignEvent.toScreenError()
        assertEquals("tasks_400", screenError.code)
        assertEquals("Assignee is not a member of this channel.", screenError.serverMessage)
        assertEquals("assignee_not_in_channel", screenError.serverCode)
        assertEquals(traceId, screenError.traceId)

        runtimeScope.cancel()
    }

    private class FakeTasksRepository(
        private val loadResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured")),
        private val updateResult: DataResult<TaskActionResult> =
            DataResult.Error(ScreenError("not_configured")),
        private val assignResult: DataResult<TaskActionResult> =
            DataResult.Error(ScreenError("not_configured")),
        private val createResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured"))
    ) : SharedTasksRepository {
        var loadCalls = 0
            private set
        var updateCalls = 0
            private set
        var lastServerId: ServerId? = null
            private set
        var lastScope: SharedTasksScope? = null
            private set
        var lastFilter: TaskFilter? = null
            private set
        var lastPriority: DataRequestPriority? = null
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
            filter: TaskFilter,
            priority: DataRequestPriority
        ): DataResult<TaskSnapshot> {
            loadCalls += 1
            lastServerId = serverId
            lastScope = scope
            lastFilter = filter
            lastPriority = priority
            return loadResult
        }

        override suspend fun updateTaskStatus(
            serverId: ServerId,
            taskId: TaskId,
            status: TaskStatus
        ): DataResult<TaskActionResult> {
            updateCalls += 1
            return updateResult
        }

        var assignCalls = 0
            private set
        var lastAssignee: MemberProfile? = null
            private set
        var lastExpectedRevision: Long? = null
            private set

        override suspend fun assignTask(
            serverId: ServerId,
            taskId: TaskId,
            assignee: MemberProfile?,
            expectedRevision: Long?
        ): DataResult<TaskActionResult> {
            assignCalls += 1
            lastAssignee = assignee
            lastExpectedRevision = expectedRevision
            return assignResult
        }

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
        private val assignResult: DataResult<TaskActionResult> =
            DataResult.Error(ScreenError("not_configured")),
        private val createResult: DataResult<TaskSnapshot> =
            DataResult.Error(ScreenError("not_configured"))
    ) : SharedTasksRemoteDataSource {
        var lastFilter: TaskFilter? = null
            private set
        var lastChannelId: ChannelId? = null
            private set
        var lastPriority: DataRequestPriority? = null
            private set

        override suspend fun loadTasks(
            serverId: ServerId,
            filter: TaskFilter,
            priority: DataRequestPriority
        ): DataResult<TaskSnapshot> {
            lastFilter = filter
            lastPriority = priority
            return loadResult
        }

        override suspend fun loadChannelTasks(
            serverId: ServerId,
            channelId: ChannelId,
            filter: TaskFilter,
            priority: DataRequestPriority
        ): DataResult<TaskSnapshot> {
            lastChannelId = channelId
            lastFilter = filter
            lastPriority = priority
            return channelLoadResult
        }

        override suspend fun updateTaskStatus(
            serverId: ServerId,
            taskId: TaskId,
            status: TaskStatus
        ): DataResult<TaskActionResult> =
            updateResult

        var assignCalls = 0
            private set
        var lastAssignTaskId: TaskId? = null
            private set
        var lastAssignee: MemberProfile? = null
            private set
        var lastExpectedRevision: Long? = null
            private set

        override suspend fun assignTask(
            serverId: ServerId,
            taskId: TaskId,
            assignee: MemberProfile?,
            expectedRevision: Long?
        ): DataResult<TaskActionResult> {
            assignCalls += 1
            lastAssignTaskId = taskId
            lastAssignee = assignee
            lastExpectedRevision = expectedRevision
            return assignResult
        }

        override suspend fun createTasks(
            serverId: ServerId,
            channelId: ChannelId,
            titles: List<String>
        ): DataResult<TaskSnapshot> =
            createResult
    }
}
