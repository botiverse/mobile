package build.raft.app.data

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
    fun sharedTasksUseCaseReturnsTypedErrorWhenSelectedServerIsMissing() = runBlocking {
        val repository = FakeTasksRepository()
        val useCase =
            SharedTasksUseCase(
                authStore = InMemoryRaftAuthSessionStore(),
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
                    InMemoryRaftAuthSessionStore(
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
                    InMemoryRaftAuthSessionStore(
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
    fun sharedTasksUseCasePropagatesBackgroundRefreshPriority() = runBlocking {
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
                    InMemoryRaftAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                            accessToken = "token",
                            refreshToken = "refresh",
                            selectedServerId = "server-1"
                        )
                    ),
                repository = repository
            )

        val result = useCase.refreshTasks(TasksLoadRequest(priority = DataRequestPriority.Background))

        assertIs<DataResult.Content<TaskSnapshot>>(result)
        assertEquals(DataRequestPriority.Background, repository.lastPriority)
    }

    @Test
    fun visibleTasksLoadCanUpgradeBackgroundRefresh() {
        assertTrue(DataRequestPriority.Visible.canUpgradeTasksLoad(DataRequestPriority.Background))
        assertTrue(DataRequestPriority.Foreground.canUpgradeTasksLoad(DataRequestPriority.Background))
        assertFalse(DataRequestPriority.Background.canUpgradeTasksLoad(DataRequestPriority.Visible))
        assertFalse(DataRequestPriority.Visible.canUpgradeTasksLoad(DataRequestPriority.Visible))
        assertFalse(DataRequestPriority.Visible.canUpgradeTasksLoad(null))
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
        assertEquals(DataRequestPriority.Visible, remote.lastPriority)
    }

    @Test
    fun sharedTasksRepositoryUsesRequestedRefreshPriority() = runBlocking {
        val serverId = ServerId("server-1")
        val local = InMemorySharedTasksLocalDataSource()
        val remote =
            FakeTasksRemoteDataSource(
                channelLoadResult =
                    DataResult.Content(
                        TaskSnapshot(
                            tasks = listOf(task("task-1", TaskStatus.Todo)),
                            summaryCounts = mapOf(TaskStatus.Todo to 1)
                        )
                    )
            )
        val repository =
            SharedTasksDataRepository(
                localDataSource = local,
                remoteDataSource = remote
            )

        repository.refreshTasks(
            serverId,
            SharedTasksScope.Channel(ChannelId("channel-1")),
            TaskFilter(),
            DataRequestPriority.Background
        )

        assertEquals(ChannelId("channel-1"), remote.lastChannelId)
        assertEquals(DataRequestPriority.Background, remote.lastPriority)
    }

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
    fun sharedTasksDataStoreProjectsServerScopeFromServerSnapshot() = runBlocking {
        val store = SharedTasksDataStore()

        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1")),
                            task("task-2", TaskStatus.InReview, channelId = ChannelId("channel-2"))
                        ),
                    summaryCounts = emptyMap()
                )
        )

        val slice = store.projectionFlow("server-1", SharedTasksScope.Server).first()

        assertEquals(SharedTasksDataStoreStatus.Loaded, slice.status)
        assertEquals(listOf("task-1", "task-2"), slice.tasks.map { it.id.value })
    }

    @Test
    fun sharedTasksDataStorePrefersChannelSnapshotOverServerProjection() = runBlocking {
        val store = SharedTasksDataStore()

        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-server", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-channel", TaskStatus.InProgress, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )

        val slice = store.channelProjectionFlow("server-1", "channel-1", null).first()

        assertEquals(SharedTasksDataStoreStatus.Loaded, slice.status)
        assertEquals(true, slice.isExactScopeLoaded)
        assertEquals(listOf("task-channel"), slice.tasks.map { it.id.value })
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
    fun sharedTasksDataStoreLoadsExactChannelBehindLoadedServerProjection() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        var remoteCalls = 0

        val result =
            store.loadSnapshot(
                serverId = "server-1",
                scope = SharedTasksScope.Channel(ChannelId("channel-1"))
            ) {
                remoteCalls += 1
                DataResult.Content(
                    TaskSnapshot(
                        tasks = listOf(task("task-channel", TaskStatus.InProgress, channelId = ChannelId("channel-1"))),
                        summaryCounts = emptyMap()
                    )
                )
            }

        assertEquals(1, remoteCalls)
        assertEquals(
            listOf("task-channel"),
            assertIs<DataResult.Content<TaskSnapshot>>(result).data.tasks.map { it.id.value }
        )
        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(true, projection.isExactScopeLoaded)
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
    fun sharedTasksDataStoreKeepsFreshChannelLoadedAcrossUnrelatedRealtimeMutation() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-channel-1", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )
        store.upsertTask(
            serverId = "server-1",
            task = task("task-channel-2", TaskStatus.InProgress, channelId = ChannelId("channel-2"))
        )
        var remoteCalls = 0

        val result =
            store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) {
                remoteCalls += 1
                DataResult.Error(ScreenError(code = "unexpected_request", retryable = false))
            }

        assertEquals(0, remoteCalls)
        assertEquals(
            listOf("task-channel-1"),
            assertIs<DataResult.Content<TaskSnapshot>>(result).data.tasks.map { it.id.value }
        )
        assertEquals(
            true,
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))).isExactScopeLoaded
        )
    }

    @Test
    fun sharedTasksDataStoreRealtimeMutationDoesNotBlessAlreadyStaleChannelSnapshot() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-old", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-server", TaskStatus.InProgress, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )
        store.upsertTask(
            serverId = "server-1",
            task = task("task-realtime", TaskStatus.InReview, channelId = ChannelId("channel-1"))
        )

        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))

        assertEquals(false, projection.isExactScopeLoaded)
        assertEquals(
            listOf("task-server", "task-realtime"),
            projection.tasks.map { it.id.value }
        )
    }

    @Test
    fun sharedTasksDataStoreLateChannelContentCannotMaskNewerServerSnapshot() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedTasksDataStore(appScope)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val oldTask = task("task-old", TaskStatus.Todo, channelId = ChannelId("channel-1"))
        val newTask = task("task-new", TaskStatus.InProgress, channelId = ChannelId("channel-1"))
        val load =
            async {
                store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                    DataResult.Content(TaskSnapshot(tasks = listOf(oldTask), summaryCounts = emptyMap()))
                }
            }
        requestStarted.await()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = listOf(newTask), summaryCounts = emptyMap())
        )
        releaseRequest.complete(Unit)

        assertEquals(
            listOf("task-new"),
            assertIs<DataResult.Content<TaskSnapshot>>(load.await()).data.tasks.map { it.id.value }
        )
        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(true, projection.isExactScopeLoaded)
        assertEquals(listOf("task-new"), projection.tasks.map { it.id.value })
        appScope.cancel()
    }

    @Test
    fun sharedTasksDataStoreLateChannelEmptyCannotMaskNewerServerSnapshot() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedTasksDataStore(appScope)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val newTask = task("task-new", TaskStatus.InProgress, channelId = ChannelId("channel-1"))
        val load =
            async {
                store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                    DataResult.Empty(EmptyReason.NoTasks)
                }
            }
        requestStarted.await()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = listOf(newTask), summaryCounts = emptyMap())
        )
        releaseRequest.complete(Unit)

        assertEquals(
            listOf("task-new"),
            assertIs<DataResult.Content<TaskSnapshot>>(load.await()).data.tasks.map { it.id.value }
        )
        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(true, projection.isExactScopeLoaded)
        assertEquals(listOf("task-new"), projection.tasks.map { it.id.value })
        appScope.cancel()
    }

    @Test
    fun sharedTasksDataStoreNameOnlyMutationDoesNotFreshenIdKeyedChannelSnapshot() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        store.upsertTask(
            serverId = "server-1",
            task =
                task(
                    "task-name-only",
                    TaskStatus.InReview,
                    channelId = null,
                    channelName = "general"
                )
        )

        val projection = store.channelProjectionFlow("server-1", "channel-1", "general").first()

        assertEquals(false, projection.isExactScopeLoaded)
        assertEquals(listOf("task-name-only"), projection.tasks.map { it.id.value })
    }

    @Test
    fun sharedTasksDataStoreUnroutableMutationsKeepInflightContentAndEmptyStale() = runBlocking {
        val mutations =
            listOf(
                task(
                    "task-name-only",
                    TaskStatus.InReview,
                    channelId = null,
                    channelName = "general"
                ),
                task(
                    "task-message-only",
                    TaskStatus.InProgress,
                    channelId = null,
                    channelName = null
                )
            )
        mutations.forEach { mutation ->
            listOf<DataResult<TaskSnapshot>>(
                DataResult.Empty(EmptyReason.NoTasks),
                DataResult.Content(
                    TaskSnapshot(
                        tasks = listOf(task("task-old", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                        summaryCounts = emptyMap()
                    )
                )
            ).forEach { staleResult ->
                listOf("no_server_refresh", "server_before_mutation", "server_after_mutation").forEach { ordering ->
                    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val store = SharedTasksDataStore(appScope)
                    store.publishSnapshot(
                        serverId = "server-1",
                        scope = SharedTasksScope.Server,
                        snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
                    )
                    val requestStarted = CompletableDeferred<Unit>()
                    val releaseRequest = CompletableDeferred<Unit>()
                    val load =
                        async {
                            store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) {
                                requestStarted.complete(Unit)
                                releaseRequest.await()
                                staleResult
                            }
                        }
                    requestStarted.await()
                    if (ordering == "server_before_mutation") {
                        store.publishSnapshot(
                            serverId = "server-1",
                            scope = SharedTasksScope.Server,
                            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
                        )
                    }
                    store.upsertTask("server-1", mutation)
                    if (ordering == "server_after_mutation") {
                        store.publishSnapshot(
                            serverId = "server-1",
                            scope = SharedTasksScope.Server,
                            snapshot = TaskSnapshot(tasks = listOf(mutation), summaryCounts = emptyMap())
                        )
                    }
                    releaseRequest.complete(Unit)
                    load.await()

                    val projection = store.channelProjectionFlow("server-1", "channel-1", "general").first()
                    val context = "mutation=${mutation.id} result=$staleResult ordering=$ordering"

                    assertEquals(false, projection.isExactScopeLoaded, context)
                    assertEquals(
                        listOf(mutation.id.value),
                        store.projection("server-1", SharedTasksScope.Server).tasks.map { it.id.value },
                        context
                    )
                    appScope.cancel()
                }
            }
        }
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
    fun sharedTasksDataStoreUpsertTaskCreatesMissingChannelProjection() = runBlocking {
        val store = SharedTasksDataStore()
        val task = task("task-1", TaskStatus.Done, channelId = ChannelId("channel-1"))

        store.upsertTask("server-1", task)

        val slice = store.channelProjectionFlow("server-1", "channel-1", null).first()

        assertEquals(SharedTasksDataStoreStatus.Partial, slice.status)
        assertEquals(listOf("task-1"), slice.tasks.map { it.id.value })
        assertEquals(TaskStatus.Done, slice.tasks.single().status)
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
    fun equalRevisionIgnoresServerLocalPresentationAndKeepsAuthorizationLocal() = runBlocking {
        val store = SharedTasksDataStore()
        val hostAssignee =
            MemberProfile(MemberId("global-user-1"), "Artin Host", "artin", MemberKind.Human)
        val peerAssignee =
            MemberProfile(MemberId("global-user-1"), "Artin Peer", "artin", MemberKind.Human)
        val host =
            task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-a"), revision = 7L)
                .copy(assignee = hostAssignee, updatedAtLabel = "just now", canClaim = true)
        val peer =
            host.copy(
                channelId = ChannelId("channel-b"),
                assignee = peerAssignee,
                updatedAtLabel = "1 minute ago",
                canClaim = false
            )

        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask("server-a", host))
        assertEquals(SharedTaskFactFoldOutcome.StaleIgnored, store.upsertTask("server-b", peer))

        val serverA = store.projection("server-a", SharedTasksScope.Server).tasks.single()
        val serverB = store.projection("server-b", SharedTasksScope.Server).tasks.single()
        assertEquals(ChannelId("channel-a"), serverA.channelId)
        assertEquals(ChannelId("channel-b"), serverB.channelId)
        assertTrue(serverA.canClaim)
        assertFalse(serverB.canClaim)
        assertFalse(store.projection("server-a", SharedTasksScope.Server).needsCanonicalRefetch)
        assertFalse(store.projection("server-b", SharedTasksScope.Server).needsCanonicalRefetch)
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
    fun sharedTasksDataStoreFailsClosedWhenLegacyNullRevisionFactsDisagree() {
        val store = SharedTasksDataStore()
        val first = task("task-legacy", TaskStatus.Todo, channelId = ChannelId("channel-a"), revision = null)
        val conflicting = first.copy(status = TaskStatus.Done, channelId = ChannelId("channel-b"))

        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask("server-a", first))
        assertEquals(SharedTaskFactFoldOutcome.Conflict, store.upsertTask("server-b", conflicting))

        assertEquals(emptyList(), store.projection("server-a", SharedTasksScope.Server).tasks)
        assertEquals(emptyList(), store.projection("server-b", SharedTasksScope.Server).tasks)
        assertTrue(store.projection("server-a", SharedTasksScope.Server).needsCanonicalRefetch)
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
    fun readOnlyJointProjectionIsServerLocalAndHasNoMutationRoute() {
        val store = SharedTasksDataStore()
        val host =
            task("task-joint", TaskStatus.Todo, channelId = ChannelId("channel-host"), revision = 5L)
                .copy(messageId = MessageId("message-joint"))
        val participant =
            host.copy(channelId = ChannelId("channel-peer"), canClaim = false, readOnlyReason = "historical_joint_task")

        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask("server-host", host))
        // The read-only marker is projection-local, so it never turns one revision into a conflict.
        assertEquals(false, store.upsertTask("server-peer", participant) == SharedTaskFactFoldOutcome.Conflict)

        assertEquals(null, store.projection("server-host", SharedTasksScope.Server).tasks.single().readOnlyReason)
        assertEquals(
            "historical_joint_task",
            store.projection("server-peer", SharedTasksScope.Server).tasks.single().readOnlyReason
        )
        assertEquals(ChannelId("channel-host"), store.mutationProjection("server-host", host.id, expectedRevision = 5L)?.channelId)
        assertEquals(null, store.mutationProjection("server-peer", host.id, expectedRevision = 5L))
    }

    @Test
    fun sharedTasksNotificationFactDedupesCanonicalMessageAndRetainsBothLocalRoutes() {
        val store = SharedTasksDataStore()
        val host =
            task("task-joint", TaskStatus.Todo, channelId = ChannelId("channel-host"), revision = 8L)
                .copy(messageId = MessageId("message-joint"), parentMessageId = MessageId("message-joint"))

        store.upsertTask("server-host", host)
        store.upsertTask("server-peer", host.copy(channelId = ChannelId("channel-peer")))

        val projection = store.notificationProjection(host.id)
        assertEquals(MessageId("message-joint"), projection?.messageId)
        assertEquals(8L, projection?.revision)
        assertEquals(
            listOf(
                SharedTaskNotificationRoute("server-host", ChannelId("channel-host")),
                SharedTaskNotificationRoute("server-peer", ChannelId("channel-peer"))
            ),
            projection?.routes
        )
        assertEquals(
            ChannelId("channel-host"),
            store.mutationProjection("server-host", host.id, expectedRevision = 8L)?.channelId
        )
        assertEquals(
            ChannelId("channel-peer"),
            store.mutationProjection("server-peer", host.id, expectedRevision = 8L)?.channelId
        )
    }

    @Test
    fun sharedTasksBatchNotificationProjectionMatchesSingleTaskProjection() {
        val store = SharedTasksDataStore()
        val tasks =
            (1..40).map { index ->
                task("task-$index", TaskStatus.Todo, channelId = ChannelId("channel-$index"), revision = index.toLong())
                    .copy(messageId = MessageId("message-$index"))
            }
        tasks.forEach { store.upsertTask("server-host", it) }
        tasks.filter { it.id.value.endsWith("0") }.forEach { task ->
            store.upsertTask("server-peer", task.copy(channelId = ChannelId("peer-${task.id.value}")))
        }
        val withoutMessage = task("task-no-message", TaskStatus.Todo, channelId = ChannelId("channel-x")).copy(messageId = null)
        store.upsertTask("server-host", withoutMessage)

        val requested = tasks.map { it.id } + withoutMessage.id + TaskId("task-unknown")
        val batch = store.notificationProjections(requested)

        assertEquals(tasks.size, batch.size)
        requested.forEach { taskId ->
            assertEquals(store.notificationProjection(taskId), batch[taskId])
        }
        assertEquals(
            listOf(
                SharedTaskNotificationRoute("server-host", ChannelId("channel-10")),
                SharedTaskNotificationRoute("server-peer", ChannelId("peer-task-10"))
            ),
            batch[TaskId("task-10")]?.routes
        )
    }

    @Test
    fun authoritativeSnapshotRemovesOnlyItsMissingLocalRouteAndPreservesPeerRoute() = runBlocking {
        val store = SharedTasksDataStore()
        val canonical = task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-a"), revision = 7L)
        store.upsertTask("server-a", canonical)
        store.upsertTask("server-b", canonical.copy(channelId = ChannelId("channel-b")))

        store.publishSnapshot(
            serverId = "server-a",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )

        assertEquals(null, store.mutationProjection("server-a", canonical.id, expectedRevision = 7L))
        assertEquals(
            ChannelId("channel-b"),
            store.mutationProjection("server-b", canonical.id, expectedRevision = 7L)?.channelId
        )
        assertEquals(emptyList(), store.projection("server-a", SharedTasksScope.Server).tasks)
        assertEquals(1, store.projection("server-b", SharedTasksScope.Server).tasks.size)
    }

    @Test
    fun sharedTasksDataStoreRealtimeUpsertStaysPartialUntilChannelSnapshotBackfills() = runBlocking {
        val store = SharedTasksDataStore()
        val recentTask = task("task-recent", TaskStatus.InProgress, channelId = ChannelId("channel-1"))
        val historicalTask = task("task-history", TaskStatus.Todo, channelId = ChannelId("channel-1"))

        store.upsertTask("server-1", recentTask)

        val partial = store.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(SharedTasksDataStoreStatus.Partial, partial.status)
        assertEquals(listOf("task-recent"), partial.tasks.map { it.id.value })

        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(historicalTask, recentTask),
                    summaryCounts = emptyMap()
                )
        )

        val loaded = store.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(SharedTasksDataStoreStatus.Loaded, loaded.status)
        assertEquals(listOf("task-history", "task-recent"), loaded.tasks.map { it.id.value })
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
    fun sharedTasksDataStoreRealtimeStatusWinsOverOlderContentBackfill() = runBlocking {
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

        store.upsertTask("server-1", stale.copy(status = TaskStatus.InReview))
        releaseRequest.complete(Unit)
        assertIs<DataResult.Content<TaskSnapshot>>(load.await())

        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(SharedTasksDataStoreStatus.Loaded, projection.status)
        assertEquals(TaskStatus.InReview, projection.tasks.single().status)
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
    fun sharedTasksDataStoreRealtimeRemoveClearsLoadedChannelProjection() = runBlocking {
        val store = SharedTasksDataStore()
        val task = task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1"))
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = listOf(task), summaryCounts = emptyMap())
        )

        store.removeTask("server-1", task.id)

        val projection = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(SharedTasksDataStoreStatus.Loaded, projection.status)
        assertEquals(emptyList(), projection.tasks)
    }

    @Test
    fun sharedTasksDataStoreSingleFlightsChannelBackfillAcrossOwners() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedTasksDataStore(appScope)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        var remoteCalls = 0
        val snapshot =
            TaskSnapshot(
                tasks = listOf(task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                summaryCounts = emptyMap()
            )
        suspend fun fetch(): DataResult<TaskSnapshot> {
            remoteCalls += 1
            requestStarted.complete(Unit)
            releaseRequest.await()
            return DataResult.Content(snapshot)
        }
        val chatOwner =
            async {
                store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) { fetch() }
            }
        requestStarted.await()
        val tasksOwner =
            async {
                store.loadSnapshot(
                    "server-1",
                    SharedTasksScope.Channel(ChannelId("channel-1")),
                    forceRefresh = true
                ) { fetch() }
            }

        yield()
        assertEquals(1, remoteCalls)
        releaseRequest.complete(Unit)
        assertIs<DataResult.Content<TaskSnapshot>>(chatOwner.await())
        assertIs<DataResult.Content<TaskSnapshot>>(tasksOwner.await())
        assertEquals(1, remoteCalls)
        appScope.cancel()
    }

    @Test
    fun sharedTasksDataStoreCallerCancellationDoesNotCancelSharedChannelLoad() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedTasksDataStore(appScope)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        var remoteCalls = 0
        val snapshot =
            TaskSnapshot(
                tasks = listOf(task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                summaryCounts = emptyMap()
            )
        suspend fun fetch(): DataResult<TaskSnapshot> {
            remoteCalls += 1
            requestStarted.complete(Unit)
            releaseRequest.await()
            return DataResult.Content(snapshot)
        }
        val firstOwner =
            async {
                store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) { fetch() }
            }
        requestStarted.await()
        firstOwner.cancelAndJoin()

        val replacementOwner =
            async {
                store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) { fetch() }
            }
        yield()
        assertEquals(1, remoteCalls)
        releaseRequest.complete(Unit)
        assertIs<DataResult.Content<TaskSnapshot>>(replacementOwner.await())
        assertEquals(1, remoteCalls)
        appScope.cancel()
    }

    @Test
    fun sharedTasksViewModelUsesLoadedSharedProjectionWithoutRemoteRequest() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sharedStore = SharedTasksDataStore()
        val snapshot =
            TaskSnapshot(
                tasks = listOf(task("task-1", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                summaryCounts = emptyMap()
            )
        sharedStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = snapshot
        )
        val repository = FakeTasksRepository(loadResult = DataResult.Content(snapshot))
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
        viewModel.dispatch(SharedTasksRouteIntent.Start)
        val content =
            withTimeout(2_000L) {
                viewModel.state.first { state -> state.loadState is ScreenLoadState.Content }
            }.loadState

        assertEquals(
            listOf("task-1"),
            assertIs<ScreenLoadState.Content<TasksContent>>(content).data.tasks.map { it.id.value }
        )
        assertFalse(viewModel.state.value.refreshing)
        assertEquals(0, repository.loadCalls)
        runtimeScope.cancel()
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
    fun sharedTasksDataStoreRejectsLateOldOwnerFlightAfterIdentitySwitch() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var ownerIdentity = "env|user-a"
        val store =
            SharedTasksDataStore(
                scope = appScope,
                ownerIdentityProvider = { ownerIdentity }
            )
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val oldOwnerLoad =
            async {
                store.loadSnapshot("server-1", SharedTasksScope.Channel(ChannelId("channel-1"))) {
                    requestStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRequest.await() }
                    DataResult.Content(
                        TaskSnapshot(
                            tasks = listOf(task("task-user-a", TaskStatus.Todo, channelId = ChannelId("channel-1"))),
                            summaryCounts = emptyMap()
                        )
                    )
                }
            }
        requestStarted.await()

        ownerIdentity = "env|user-b"
        store.bindCurrentOwnerIdentity()
        releaseRequest.complete(Unit)
        oldOwnerLoad.join()

        assertEquals(
            SharedTasksProjectionSlice(),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        )
        appScope.cancel()
    }

    @Test
    fun sharedTasksDataStoreFirstFrameProjectionRequiresExactBoundOwner() = runBlocking {
        var ownerIdentity = "https://api.example.test|user-a"
        val store = SharedTasksDataStore(ownerIdentityProvider = { ownerIdentity })
        store.bindCurrentOwnerIdentity()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot =
                TaskSnapshot(
                    tasks = listOf(task("task-a", TaskStatus.InReview, channelId = ChannelId("channel-1"))),
                    summaryCounts = emptyMap()
                )
        )

        val accepted =
            store.projectionForOwner(
                expectedOwnerIdentity = "https://api.example.test|user-a",
                serverId = "server-1",
                scope = SharedTasksScope.Channel(ChannelId("channel-1"))
            )

        assertEquals(listOf("task-a"), accepted?.tasks?.map { task -> task.id.value })
        assertEquals(SharedTasksDataStoreStatus.Loaded, accepted?.status)
        assertNull(
            store.projectionForOwner(
                expectedOwnerIdentity = "https://api.example.test|user-b",
                serverId = "server-1",
                scope = SharedTasksScope.Channel(ChannelId("channel-1"))
            )
        )

        ownerIdentity = "https://api.example.test|user-b"
        store.bindCurrentOwnerIdentity()

        assertNull(
            store.projectionForOwner(
                expectedOwnerIdentity = "https://api.example.test|user-a",
                serverId = "server-1",
                scope = SharedTasksScope.Channel(ChannelId("channel-1"))
            )
        )
        assertEquals(
            SharedTasksProjectionSlice(),
            store.projectionForOwner(
                expectedOwnerIdentity = "https://api.example.test|user-b",
                serverId = "server-1",
                scope = SharedTasksScope.Channel(ChannelId("channel-1"))
            )
        )
    }

    @Test
    fun sharedTasksDataStoreReusesLoadedServerSnapshotBehindEarlierPartialChannelProjection() = runBlocking {
        val store = SharedTasksDataStore()
        val recentTask = task("task-recent", TaskStatus.InReview, channelId = ChannelId("channel-1"))
        val historicalTask = task("task-history", TaskStatus.Todo, channelId = ChannelId("channel-1"))

        store.upsertTask("server-1", recentTask)
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks = listOf(historicalTask),
                    summaryCounts = emptyMap()
                )
        )

        val projection = store.channelProjectionFlow("server-1", "channel-1", null).first()

        assertEquals(SharedTasksDataStoreStatus.Loaded, projection.status)
        assertEquals(false, projection.isExactScopeLoaded)
        assertEquals(listOf("task-history", "task-recent"), projection.tasks.map { it.id.value })
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
    fun tasksRefreshGateSkipsDuplicateInFlightAndFreshSnapshotButAllowsManualRefresh() {
        assertEquals(
            TasksRefreshSkipReason.InFlight,
            tasksRefreshSkipReason(
                inFlight = true,
                hasSnapshot = false,
                snapshotAgeMs = null,
                staleWindowMs = 15_000L,
                forceRefresh = false
            )
        )
        assertEquals(
            TasksRefreshSkipReason.FreshSnapshot,
            tasksRefreshSkipReason(
                inFlight = false,
                hasSnapshot = true,
                snapshotAgeMs = 1_000L,
                staleWindowMs = 15_000L,
                forceRefresh = false
            )
        )
        assertEquals(
            null,
            tasksRefreshSkipReason(
                inFlight = false,
                hasSnapshot = true,
                snapshotAgeMs = 1_000L,
                staleWindowMs = 15_000L,
                forceRefresh = true
            )
        )
        assertEquals(
            null,
            tasksRefreshSkipReason(
                inFlight = false,
                hasSnapshot = true,
                snapshotAgeMs = 15_000L,
                staleWindowMs = 15_000L,
                forceRefresh = false
            )
        )
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
    fun sharedTasksStoreNoopsEquivalentSeedContentSnapshot() {
        val transitions = mutableListOf<MviTransition<SharedTasksIntent>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot =
            TaskSnapshot(
                tasks = listOf(task("task-1", TaskStatus.Todo)),
                summaryCounts = mapOf(TaskStatus.Todo to 1)
            )
        val store =
            SharedTasksStore(
                initialState = TasksUiState(ScreenLoadState.Loading),
                scope = scope,
                transitionLogger = transitions::add
            )
        val seed = SharedTasksIntent.SetContent(snapshot, selectedFilter = TaskStatus.Todo)

        assertTrue(store.accept(seed))
        val seeded = store.state.value
        assertFalse(store.accept(seed))

        assertTrue(seeded === store.state.value)
        assertEquals(true, transitions.first().emitted)
        assertEquals(null, transitions.first().reason)
        assertEquals(false, transitions.last().emitted)
        assertEquals("unchanged", transitions.last().reason)
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
    fun taskStatusUpdateSuccessRefreshesScopedProjectionTask() {
        val staleTask = task("task-1", TaskStatus.Todo)
        val canonicalTask = staleTask.copy(status = TaskStatus.Done)
        val state =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.Todo to 1),
                            tasks = listOf(staleTask)
                        )
                    ),
                statusUpdatingTaskId = "task-1",
                statusUpdateErrorCode = "previous_error"
            )

        val next = state.reduce(SharedTasksIntent.TaskStatusUpdateSucceeded(canonicalTask))

        val content = assertIs<ScreenLoadState.Content<TasksContent>>(next.loadState).data
        assertEquals(TaskStatus.Done, content.tasks.single().status)
        assertEquals(1, content.summaryCounts[TaskStatus.Done])
        assertEquals(0, content.summaryCounts[TaskStatus.Todo])
        assertEquals(null, next.statusUpdatingTaskId)
        assertEquals(null, next.statusUpdateErrorCode)
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
    fun successfulStatusWithoutProjectionKeepsOptimisticStatusAsSuccess() = runBlocking {
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
                                            code = TASKS_STATUS_APPLIED_WITHOUT_PROJECTION,
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
    fun unloadedChatMutationWithoutLocalProjectionMakesZeroNetworkCalls() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sharedStore = SharedTasksDataStore()
        val previousTask = task("task-1", TaskStatus.InReview, channelId = ChannelId("channel-1"))
        val repository =
            FakeTasksRepository(
                updateResult =
                    DataResult.Error(
                        ScreenError(
                            code = TASKS_STATUS_APPLIED_WITHOUT_PROJECTION,
                            retryable = false
                        )
                    )
            )
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

        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(previousTask, TaskStatus.Closed))
        yield()

        val projection = sharedStore.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        assertEquals(0, repository.updateCalls)
        assertEquals(emptyList(), projection.tasks)
        assertEquals(null, viewModel.state.value.statusUpdatingTaskId)
        runtimeScope.cancel()
    }

    @Test
    fun unloadedChatMutationOwnerRollsBackOrdinaryFailure() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sharedStore = SharedTasksDataStore()
        val previousTask = task("task-1", TaskStatus.InReview, channelId = ChannelId("channel-1"))
        val repository =
            FakeTasksRepository(
                updateResult = DataResult.Error(ScreenError(code = "tasks_500", retryable = true))
            )
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
        sharedStore.upsertTask("server-1", previousTask)

        viewModel.dispatch(SharedTasksRouteIntent.UpdateStatus(previousTask, TaskStatus.Closed))
        yield()

        val projection =
            sharedStore
                .projectionFlow("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
                .first()
        assertEquals(1, repository.updateCalls)
        assertEquals(TaskStatus.InReview, projection.tasks.single().status)
        assertEquals("tasks_500", viewModel.state.value.statusUpdateErrorCode)
        runtimeScope.cancel()
    }

    @Test
    fun updateTaskStatusMatchesTaskByMessageId() {
        val task = task("task-1", TaskStatus.Todo)
        val state =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.Todo to 1),
                            tasks = listOf(task)
                        )
                    )
            )

        val next = state.reduce(SharedTasksIntent.UpdateTaskStatus("message-task-1", TaskStatus.Done))

        val content = assertIs<ScreenLoadState.Content<TasksContent>>(next.loadState).data
        assertEquals(TaskStatus.Done, content.tasks.single().status)
        assertEquals(1, content.summaryCounts[TaskStatus.Done])
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
        assertEquals("user:user-1", task.creator?.tasksIdentityFilterId())
        assertEquals("KMP-专家", task.tasksAssigneeDisplayName())
        assertEquals(1, content.data.summaryCounts[TaskStatus.InReview])
    }

    @Test
    fun taskNetworkResponseOffUiParseEmitsRefreshDiagnosticsMarkers() = runBlocking {
        val logs = mutableListOf<String>()
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
                          "status": "in_review"
                        }
                      ]
                    }
                    """.trimIndent()
            )

        val result =
            response.toTasksResultOffUi(
                source = "tasks_server",
                diagnosticsLogger = logs::add
            )

        val content = assertIs<DataResult.Content<TaskSnapshot>>(result)
        assertEquals(1, content.data.tasks.size)
        assertTrue(logs.any { it.contains("tasks_network_body_received source=tasks_server") })
        assertTrue(logs.any { it.contains("tasks_parse_start source=tasks_server") && it.contains("dispatcher=JsonParse") })
        assertTrue(logs.any { it.contains("tasks_parse_result source=tasks_server result=content tasks=1") })
        assertTrue(logs.any { it.contains("tasks_map_result source=tasks_server result=content tasks=1") })
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
    fun assignTaskWithSameAssigneeIsSkippedWithoutNetworkCall() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val assignee = MemberProfile(MemberId("user-2"), "Nathan Peer", "nathan", MemberKind.Human)
        val previousTask = task("task-1", TaskStatus.Todo, assignee = assignee, revision = 4L)
        val authStore =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "token",
                    refreshToken = "refresh",
                    selectedServerId = "server-1"
                )
            )
        val repository = FakeTasksRepository()
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

        assertEquals(0, repository.assignCalls)
        runtimeScope.cancel()
    }

    @Test
    fun assignTaskUnassignSendsNullAssignee() = runBlocking {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val assignee = MemberProfile(MemberId("user-2"), "Nathan Peer", "nathan", MemberKind.Human)
        val previousTask = task("task-1", TaskStatus.Todo, assignee = assignee, revision = 4L)
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
                        TaskActionResult(task("task-1", TaskStatus.Todo, assignee = null, revision = 5L))
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

        viewModel.dispatch(SharedTasksRouteIntent.AssignTask(previousTask, null))
        yield()

        val state = viewModel.state.value
        val content = assertIs<ScreenLoadState.Content<TasksContent>>(state.loadState).data
        assertEquals(null, content.tasks.single().assignee)
        assertEquals(null, state.statusUpdateErrorCode)
        assertEquals(1, repository.assignCalls)
        assertEquals(null, repository.lastAssignee)
        assertEquals(4L, repository.lastExpectedRevision)
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
