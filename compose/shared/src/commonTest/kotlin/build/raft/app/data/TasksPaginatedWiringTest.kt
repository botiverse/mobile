package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.CompletableDeferred
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
            status: String?,
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

/**
 * Task #345 — a LoadMore page must be merged into the SHARED store, not only
 * the ViewModel's private state. The shared store's long-lived projectionFlow
 * is authoritative and would otherwise overwrite (or never show) the appended
 * page.
 */
class TasksPaginatedSharedStoreMergeTest {

    private fun pageJson(prefix: String, count: Int, next: String?): String {
        val items =
            (1..count).joinToString(",") { n ->
                """{"id":"$prefix$n","messageId":"m-$prefix$n","channelId":"c1","channelName":"alpha",
                   "taskNumber":$n,"title":"T$n","status":"todo","createdByName":"x",
                   "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                   "source":"tasks","hasDescription":false,"descriptionBytes":0}"""
            }
        val cursor = next?.let { """"next_cursor":"$it"""" } ?: """"next_cursor":null"""
        return """{"tasks":[$items],$cursor}"""
    }

    private class PagingApi(private val first: String, private val second: String) : SlockTasksApi {
        var pages = 0
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 500, bodyText = null)

        override suspend fun serverTasksPage(
            serverId: ServerId,
            priority: DataRequestPriority,
            detail: String?,
            limit: Int?,
            cursor: String?,
            status: String?,
        ): HomeNetworkResponse {
            pages++
            return HomeNetworkResponse(code = 200, bodyText = if (cursor == null) first else second)
        }

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
            HomeNetworkResponse(code = 500, bodyText = null)
    }

    @Test
    fun aLoadMorePageLandsInTheSharedProjectionWithoutDuplicates() = runBlocking {
        val api = PagingApi(first = pageJson("a", 2, "cur1"), second = pageJson("b", 2, null))
        val authStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "u", email = "u@slock.ai"),
                    accessToken = "t",
                    refreshToken = "r",
                    selectedServerId = "server-1",
                )
            )
        val shared = SharedTasksDataStore()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = SharedTasksDataSource(api = api),
                sharedTasksDataStore = shared,
                runtimeScopeOverride = runtimeScope,
                diagnosticsLogger = { line -> if (line.startsWith("tasks_refresh") || line.startsWith("tasks_paginated")) println("DIAGLOG $line") },
            )

        viewModel.dispatch(SharedTasksRouteIntent.Start)
        // Wait for BOTH the first page content and the pagination seed: the
        // content commit and the cursor seed are separate state emissions.
        withTimeout(5_000) {
            viewModel.state.first { it.loadState is ScreenLoadState.Content && it.nextCursor != null }
        }

        // Trigger the next page.
        viewModel.dispatch(SharedTasksRouteIntent.LoadMore)
        withTimeout(5_000) {
            viewModel.state.first {
                val content = it.loadState as? ScreenLoadState.Content
                (content?.data?.tasks?.size ?: 0) == 4
            }
        }

        val content = viewModel.state.value.loadState as ScreenLoadState.Content
        val ids = content.data.tasks.map { it.id.value }
        assertEquals(listOf("a1", "a2", "b1", "b2"), ids, "both pages present in order")
        assertEquals(ids.toSet().size, ids.size, "no duplicate rows")

        // The shared store's authoritative projection must carry the second page
        // too (not just the ViewModel's private state).
        val projected = shared.projection("server-1", SharedTasksScope.Server).tasks.map { it.id.value }
        assertTrue(
            projected.containsAll(listOf("a1", "a2", "b1", "b2")),
            "shared projection must include the appended page; got $projected",
        )
    }
}

/**
 * Task #345 — a LoadMore page must be fenced to the OWNER it was requested for,
 * not just the server. A same-server logout/login (user A -> user B) changes the
 * store's owner identity; A's in-flight page must be discarded, never folded
 * into B's freshly cleared store (cross-user leakage).
 */
class TasksPaginatedOwnerSwitchTest {

    private class GatedApi(
        private val firstPage: String,
        private val secondPage: String,
        private val allowSecondPage: CompletableDeferred<Unit>,
    ) : SlockTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 500, bodyText = null)

        override suspend fun serverTasksPage(
            serverId: ServerId,
            priority: DataRequestPriority,
            detail: String?,
            limit: Int?,
            cursor: String?,
            status: String?,
        ): HomeNetworkResponse {
            if (cursor == null) return HomeNetworkResponse(code = 200, bodyText = firstPage)
            allowSecondPage.await()
            return HomeNetworkResponse(code = 200, bodyText = secondPage)
        }

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
            HomeNetworkResponse(code = 500, bodyText = null)
    }

    @Test
    fun aPageIsDiscardedWhenTheOwnerChangesOnTheSameServer() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first =
            """{"tasks":[{"id":"a1","messageId":"m-a1","channelId":"c1","channelName":"alpha","taskNumber":1,
               "title":"A1","status":"todo","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
               "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}],
               "next_cursor":"curA"}"""
        val second =
            """{"tasks":[{"id":"a2","messageId":"m-a2","channelId":"c1","channelName":"alpha","taskNumber":2,
               "title":"A2","status":"todo","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
               "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}],
               "next_cursor":null}"""
        val api = GatedApi(firstPage = first, secondPage = second, allowSecondPage = gate)
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-A", email = "a@slock.ai"),
                accessToken = "t",
                refreshToken = "r",
                selectedServerId = "server-1",
            )
        val authStore = InMemorySlockAuthSessionStore(session)
        // Production-like owner identity: api base + principal, on the SAME server.
        val shared =
            SharedTasksDataStore(
                ownerIdentityProvider = {
                    authStore.readSession()?.let { s -> "base|${s.user.id}" }
                },
                currentServerIdProvider = { authStore.readSession()?.currentServerId },
            )
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = SharedTasksDataSource(api = api),
                sharedTasksDataStore = shared,
                runtimeScopeOverride = runtimeScope,
            )

        viewModel.dispatch(SharedTasksRouteIntent.Start)
        withTimeout(5_000) {
            viewModel.state.first { it.loadState is ScreenLoadState.Content && it.nextCursor != null }
        }

        viewModel.dispatch(SharedTasksRouteIntent.LoadMore)
        kotlinx.coroutines.yield()
        // Same server, different principal: user A -> user B.
        authStore.saveSession(
            authStore.readSession()!!.copy(user = StoredAuthUser(id = "user-B", email = "b@slock.ai"))
        )
        kotlinx.coroutines.yield()
        gate.complete(Unit)
        withTimeout(5_000) { viewModel.state.first { !it.loadingMore } }

        val vmIds = ((viewModel.state.value.loadState as? ScreenLoadState.Content)?.data?.tasks ?: emptyList())
            .map { it.id.value }
        assertTrue(vmIds.none { it == "a2" }, "stale owner's page row must not be appended; got $vmIds")
    }

    /**
     * Task #345 round 5 — the owner fence must include the owner GENERATION, not
     * just the identity string.
     *
     * Same principal, same server, but a logout -> login cycle: the identity
     * string is restored IDENTICAL, so an identity-only fence passes and a page
     * fetched before logout would be folded into the new session. The generation
     * bump on clearOwnerData is what must reject it.
     */
    @Test
    fun aPageFetchedBeforeLogoutIsDiscardedAfterTheSamePrincipalLogsBackIn() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first =
            """{"tasks":[{"id":"a1","messageId":"m-a1","channelId":"c1","channelName":"alpha","taskNumber":1,
               "title":"A1","status":"todo","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
               "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}],
               "next_cursor":"curA"}"""
        val stale =
            """{"tasks":[{"id":"a2","messageId":"m-a2","channelId":"c1","channelName":"alpha","taskNumber":2,
               "title":"A2","status":"todo","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
               "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}],
               "next_cursor":null}"""
        val api = GatedApi(firstPage = first, secondPage = stale, allowSecondPage = gate)
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-A", email = "a@slock.ai"),
                accessToken = "t",
                refreshToken = "r",
                selectedServerId = "server-1",
            )
        val authStore = InMemorySlockAuthSessionStore(session)
        // Identity is derived from the SAME principal both times, so the identity
        // string is identical across the logout/login; only the generation moves.
        val shared =
            SharedTasksDataStore(
                ownerIdentityProvider = { authStore.readSession()?.let { s -> "base|${s.user.id}" } },
                currentServerIdProvider = { authStore.readSession()?.currentServerId },
            )
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = SharedTasksDataSource(api = api),
                sharedTasksDataStore = shared,
                runtimeScopeOverride = runtimeScope,
            )

        viewModel.dispatch(SharedTasksRouteIntent.Start)
        withTimeout(5_000) {
            viewModel.state.first { it.loadState is ScreenLoadState.Content && it.nextCursor != null }
        }

        viewModel.dispatch(SharedTasksRouteIntent.LoadMore)
        kotlinx.coroutines.yield()
        // Logout: clear the owner's data (bumps the generation). Then the SAME
        // principal logs back in — the identity string is restored identical.
        shared.clearOwnerData()
        kotlinx.coroutines.yield()
        val identityBefore = shared.currentOwnerIdentity()
        assertEquals("base|user-A", identityBefore, "same principal restores the same identity string")

        gate.complete(Unit)
        withTimeout(5_000) { viewModel.state.first { !it.loadingMore } }

        val vmIds = ((viewModel.state.value.loadState as? ScreenLoadState.Content)?.data?.tasks ?: emptyList())
            .map { it.id.value }
        assertTrue(
            vmIds.none { it == "a2" },
            "a page fetched before logout must not be folded into the re-login session; got $vmIds"
        )
    }
}

/**
 * Task #345 — the Board sentinel must be single-flight at the HOST level: many
 * columns (uneven / short / empty) reaching their end, plus repeated callbacks,
 * must produce exactly ONE follow-up page request while one is in flight.
 *
 * Each column's `onReachedEnd` is a plain callback; the single-flight gate is
 * the host's `loadMoreJob?.isActive`. This counts the actual page requests, so a
 * repeated/uneven trigger that leaked a second request goes RED.
 */
class TasksPaginatedSentinelSingleFlightTest {

    private class CountedGatedApi(
        private val firstPage: String,
        private val gate: CompletableDeferred<Unit>,
        private val secondPage: String,
    ) : SlockTasksApi {
        var pageCalls = 0
            private set

        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 500, bodyText = null)

        override suspend fun serverTasksPage(
            serverId: ServerId,
            priority: DataRequestPriority,
            detail: String?,
            limit: Int?,
            cursor: String?,
            status: String?,
        ): HomeNetworkResponse {
            pageCalls++
            if (cursor == null) return HomeNetworkResponse(code = 200, bodyText = firstPage)
            gate.await() // hold page 2 in flight
            return HomeNetworkResponse(code = 200, bodyText = secondPage)
        }

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
            HomeNetworkResponse(code = 500, bodyText = null)
    }

    private fun page(prefix: String, count: Int, next: String?): String {
        val items = (1..count).joinToString(",") { n ->
            """{"id":"$prefix$n","messageId":"m-$prefix$n","channelId":"c1","channelName":"alpha",
               "taskNumber":$n,"title":"T","status":"todo","createdByName":"x",
               "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
               "source":"tasks","hasDescription":false,"descriptionBytes":0}"""
        }
        return """{"tasks":[$items],${next?.let { "\"next_cursor\":\"$it\"" } ?: "\"next_cursor\":null"}}"""
    }

    @Test
    fun repeatedAndUnevenColumnEndCallbacksIssueExactlyOnePageRequest() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val api = CountedGatedApi(firstPage = page("a", 2, "cur1"), gate = gate, secondPage = page("b", 1, null))
        val authStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "u", email = "u@slock.ai"),
                    accessToken = "t",
                    refreshToken = "r",
                    selectedServerId = "server-1",
                )
            )
        val viewModel =
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = SharedTasksDataSource(api = api),
                sharedTasksDataStore = SharedTasksDataStore(
                    ownerIdentityProvider = { "base|u" },
                    currentServerIdProvider = { "server-1" },
                ),
                runtimeScopeOverride = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            )

        viewModel.dispatch(SharedTasksRouteIntent.Start)
        withTimeout(5_000) {
            viewModel.state.first { it.loadState is ScreenLoadState.Content && it.nextCursor != null }
        }
        assertEquals(1, api.pageCalls, "first page fetched once")

        // Simulate many columns (uneven/short/empty) reaching their end, with
        // repeated callbacks while the page request is in flight.
        repeat(6) { viewModel.dispatch(SharedTasksRouteIntent.LoadMore) }
        kotlinx.coroutines.yield()
        repeat(4) { viewModel.dispatch(SharedTasksRouteIntent.LoadMore) }

        // Exactly one follow-up page request, despite 10 triggers.
        assertEquals(2, api.pageCalls, "single-flight: only one page-2 request despite repeated/uneven triggers")

        gate.complete(Unit)
        withTimeout(5_000) { viewModel.state.first { !it.loadingMore } }
        assertEquals(2, api.pageCalls, "no extra request after completion")
    }
}
