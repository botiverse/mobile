package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedTasksRouteSessionTest {
    private fun session(server: String) = StoredAuthSession(
        user = StoredAuthUser(id = "user", email = "user@example.test"),
        accessToken = "test", refreshToken = "test", selectedServerId = server
    )

    private class Api : RaftTasksApi {
        val calls = mutableListOf<String>()
        var blockedServer: String? = null
        val release = CompletableDeferred<Unit>()
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
            error("The production route must use paginated tasks")
        override suspend fun serverTasksPage(
            serverId: ServerId, priority: DataRequestPriority, detail: String?,
            limit: Int?, cursor: String?, status: String?
        ): HomeNetworkResponse {
            calls += serverId.value
            if (serverId.value == blockedServer) {
                // Model a transport completion arriving after its route was disposed.
                withContext(NonCancellable) { release.await() }
            }
            return HomeNetworkResponse(200, """{"tasks":[{
                "id":"task-${serverId.value}","messageId":"message-${serverId.value}",
                "channelId":"channel-${serverId.value}","channelName":"${serverId.value}",
                "title":"${serverId.value}","status":"in_review","taskNumber":1,
                "createdByName":"User","createdAt":"2026-10-08T00:00:00Z",
                "updatedAt":"2026-10-08T00:00:00Z","source":"tasks",
                "hasDescription":false,"descriptionBytes":0}],"next_cursor":null}""")
        }
        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
            HomeNetworkResponse(500, null)
    }

    private fun SharedTasksRouteSession.tasks() =
        (viewModel.state.value.loadState as ScreenLoadState.Content).data.tasks

    @Test
    fun switchingServerDropsFreshOldContentAndObservesNewServerStatusWithoutTabReentry() = runTest {
        val auth = InMemoryRaftAuthSessionStore(session("A"))
        val api = Api()
        val shared = SharedTasksDataStore(scope = backgroundScope)
        fun route(server: String) = SharedTasksRouteSession(
            SharedTasksRouteOwner("test-api", "user", server), backgroundScope,
            auth, SharedTasksDataSource(api), shared
        )
        val a = route("A")
        a.start()
        runCurrent()
        assertEquals("task-A", a.tasks().single().id.value)
        auth.updateSelectedServer("B", null)
        a.close()
        val b = route("B")
        assertEquals(ScreenLoadState.Loading, b.viewModel.state.value.loadState)
        b.start()
        runCurrent()
        assertEquals(listOf("A", "B"), api.calls, "A's fresh-window must not suppress B's load")
        assertEquals("task-B", b.tasks().single().id.value)
        val oldA = a.viewModel.state.value
        shared.upsertTask("A", a.tasks().single().copy(status = TaskStatus.Done, revision = 2L))
        shared.upsertTask("B", b.tasks().single().copy(status = TaskStatus.Done, revision = 2L))
        runCurrent()
        assertEquals(oldA, a.viewModel.state.value, "Disposed route must stop collecting A")
        assertEquals(TaskStatus.Done, b.tasks().single().status, "B's canonical update reaches the visible tab")
        b.close()
    }

    @Test
    fun lateOldServerResponseCannotReplaceNewServerList() = runTest {
        val auth = InMemoryRaftAuthSessionStore(session("A"))
        val api = Api().apply { blockedServer = "A" }
        val shared = SharedTasksDataStore(scope = backgroundScope)
        fun route(server: String) = SharedTasksRouteSession(
            SharedTasksRouteOwner("test-api", "user", server), backgroundScope,
            auth, SharedTasksDataSource(api), shared
        )
        val a = route("A")
        a.start()
        runCurrent()
        assertEquals(listOf("A"), api.calls)
        auth.updateSelectedServer("B", null)
        a.close()
        val b = route("B")
        b.start()
        runCurrent()
        assertEquals("task-B", b.tasks().single().id.value)
        api.release.complete(Unit)
        runCurrent()
        assertEquals("task-B", b.tasks().single().id.value)
        assertTrue(!b.viewModel.state.value.refreshing)
        b.close()
    }

    @Test
    fun returningToPreviouslyLoadedServerRevalidatesItsFirstPage() = runTest {
        val auth = InMemoryRaftAuthSessionStore(session("A"))
        val api = Api()
        val shared = SharedTasksDataStore(scope = backgroundScope)
        fun route() = SharedTasksRouteSession(
            SharedTasksRouteOwner("test-api", "user", "A"), backgroundScope,
            auth, SharedTasksDataSource(api), shared
        )
        val first = route()
        first.start()
        runCurrent()
        assertEquals("task-A", first.tasks().single().id.value)
        first.close()
        val returned = route()
        returned.start()
        runCurrent()
        assertEquals(listOf("A", "A"), api.calls, "A retained shared cache must not skip revalidation")
        assertEquals("task-A", returned.tasks().single().id.value)
        assertTrue(!returned.viewModel.state.value.refreshing)
        returned.close()
    }

    @Test
    fun aServersTasksPageFoldsComeBackAfterASwitchAndARestartWhileAnotherServerKeepsItsOwn() = runTest {
        // Web tasksPageFolds.behavior.test.ts (botiverse/slock eb63aec86e, #15507): a fold is kept for its
        // server and comes back after switching away and back; another server starts from its own.
        val auth = InMemoryRaftAuthSessionStore(session("A"))
        val shared = SharedTasksDataStore(scope = backgroundScope)
        // The device's key-value store: all that a restart leaves.
        val device = InMemoryRaftKeyValueStore()
        fun route(server: String, folds: TasksPageFoldPreferenceRepository = KeyValueTasksPageFoldPreferenceRepository(device)) =
            SharedTasksRouteSession(
                SharedTasksRouteOwner("test-api", "user", server), backgroundScope,
                auth, SharedTasksDataSource(Api()), shared, foldPreferences = folds
            )
        val defaults = TasksPresentationState.Default.collapsedStatuses
        val a = route("A")
        assertEquals(defaults, a.folds.collapsedStatuses(), "nothing folded by hand yet: the defaults")
        a.folds.setCollapsed(TaskStatus.Done, false)
        a.folds.setCollapsed(TaskStatus.Todo, true)
        val foldedOnA = defaults - TaskStatus.Done + TaskStatus.Todo
        assertEquals(foldedOnA, a.folds.collapsedStatuses())
        a.close()

        auth.updateSelectedServer("B", null)
        val b = route("B")
        assertEquals(defaults, b.folds.collapsedStatuses(), "another server starts from its own folds")
        b.folds.setCollapsed(TaskStatus.Closed, false)
        b.close()

        auth.updateSelectedServer("A", null)
        val backOnA = route("A")
        assertEquals(foldedOnA, backOnA.folds.collapsedStatuses(), "back on A, its folds come back")
        backOnA.close()

        // A restart: new routes over a new store, reading only what the device kept.
        val restarted = KeyValueTasksPageFoldPreferenceRepository(device)
        val aAfterRestart = route("A", restarted)
        val bAfterRestart = route("B", restarted)
        assertEquals(foldedOnA, aAfterRestart.folds.collapsedStatuses())
        assertEquals(defaults - TaskStatus.Closed, bAfterRestart.folds.collapsedStatuses(), "B keeps its own folds")
        aAfterRestart.close()
        bAfterRestart.close()
    }

    @Test
    fun queuedOldRouteCannotLoadForNewAccountOrServer() = runTest {
        val auth = InMemoryRaftAuthSessionStore(session("A"))
        val api = Api()
        val route = SharedTasksRouteSession(
            SharedTasksRouteOwner("test-api", "user", "A"), backgroundScope,
            auth, SharedTasksDataSource(api), SharedTasksDataStore(scope = backgroundScope)
        )
        auth.updateSelectedServer("B", null)
        route.start()
        runCurrent()
        assertTrue(api.calls.isEmpty(), "A route may not issue requests for the newly selected B")
        route.close()
    }
}
