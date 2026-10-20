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
