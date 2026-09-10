package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task #345 — the REAL production wiring must request the paginated summary
 * page, not the unpaginated full read.
 *
 * The server Tasks panel is constructed with `sharedTasksDataStore` present
 * (`SettingsHomePage`), so the first load goes through the shared-snapshot
 * path. That path's fetch must still be `detail=summary&limit=200` — otherwise
 * the panel silently falls back to the ~22.8 MiB full read and the scroll
 * sentinel has no cursor to follow. This test binds the real ViewModel with a
 * shared store present and asserts the wire call.
 */
class TasksPaginatedWiringTest {

    /** Records which server read the ViewModel actually issued. */
    private class RecordingTasksApi : SlockTasksApi {
        val pageCalls = mutableListOf<Pair<String?, Int?>>()
        var serverTasksCalls = 0

        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            serverTasksCalls++
            return HomeNetworkResponse(code = 500, bodyText = null)
        }

        override suspend fun serverTasksPage(
            serverId: ServerId,
            priority: DataRequestPriority,
            detail: String?,
            limit: Int?,
            cursor: String?,
        ): HomeNetworkResponse {
            pageCalls += detail to limit
            // One page, two tasks, no further cursor.
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {"tasks":[
                      {"id":"t1","messageId":"m1","channelId":"c1","channelName":"alpha","taskNumber":1,
                       "title":"A","status":"todo","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
                       "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0},
                      {"id":"t2","messageId":"m2","channelId":"c1","channelName":"alpha","taskNumber":2,
                       "title":"B","status":"todo","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
                       "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}
                    ],"next_cursor":null}
                    """.trimIndent(),
            )
        }

        override suspend fun updateTaskStatus(
            taskId: TaskId,
            status: TaskStatus,
        ): HomeNetworkResponse = HomeNetworkResponse(code = 500, bodyText = null)
    }

    @Test
    fun serverScopeWithSharedStoreRequestsThePaginatedSummaryPage() = runBlocking {
        val api = RecordingTasksApi()
        val authStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "token",
                    refreshToken = "refresh",
                    selectedServerId = "server-1",
                )
            )
        val dataSource = SharedTasksDataSource(api = api)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = dataSource,
                // The real production wiring always supplies a shared store.
                sharedTasksDataStore = SharedTasksDataStore(),
                runtimeScopeOverride = runtimeScope,
            )

        viewModel.dispatch(SharedTasksRouteIntent.Start)
        // Await the terminal load deterministically (never a fixed spin count).
        withTimeout(5_000) {
            viewModel.state.first { it.loadState is ScreenLoadState.Content }
        }

        assertTrue(api.pageCalls.isNotEmpty(), "server scope must issue the paginated page request")
        assertEquals("summary" to 200, api.pageCalls.first(), "first page must be detail=summary&limit=200")
        assertEquals(0, api.serverTasksCalls, "the unpaginated full read must NOT be used in the server scope")
    }
}
