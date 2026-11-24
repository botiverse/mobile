package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A retained Tasks tab belongs to one environment/account/server, not to the shell forever. */
@RaftCoreInternalApi data class SharedTasksRouteOwner(
    val environment: String,
    val accountId: String?,
    val serverId: String?
)

/** Disposing a route cancels its reads, mutations and projection collector together. */
@RaftCoreInternalApi class SharedTasksRouteSession(
    val owner: SharedTasksRouteOwner,
    parentScope: CoroutineScope,
    authStore: RaftAuthSessionStore,
    dataSource: SharedTasksDataSource,
    sharedTasksDataStore: SharedTasksDataStore,
    diagnosticsLogger: (String) -> Unit = {},
    queryPreferences: TasksPageQueryPreferenceRepository? = null,
    /** Where the Tasks page keeps its folds per server; null keeps them for the page only. */
    foldPreferences: TasksPageFoldPreferenceRepository? = null
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
    )
    // A queued action from a disposed tab must not resolve the newly selected server.
    private val routeAuthStore = object : RaftAuthSessionStore by authStore {
        override suspend fun readSession(): StoredAuthSession? =
            authStore.readSession()?.takeIf {
                it.user.id == owner.accountId && it.currentServerId == owner.serverId
            }
    }
    val viewModel = SharedTasksViewModel(
        authStore = routeAuthStore,
        dataSource = dataSource,
        sharedTasksDataStore = sharedTasksDataStore,
        diagnosticsLogger = diagnosticsLogger,
        runtimeScopeOverride = scope,
        queryPreferences = queryPreferences
    )

    /**
     * The Tasks page's folds on this route's server. A server switch makes a new route, so the
     * page opens with that server's own folds (web: read back when the server becomes current).
     */
    val folds = TasksPageFolds(serverId = owner.serverId, preferences = foldPreferences)

    fun start() {
        // A cached shared snapshot has no cursor/freshness ownership for this new
        // route. Revalidate its first page instead of inheriting the prior tab's age.
        viewModel.dispatch(
            if (viewModel.state.value.hasCompletedTasksSnapshot()) SharedTasksRouteIntent.Start
            else SharedTasksRouteIntent.Refresh
        )
    }

    fun close() = scope.cancel()
}
