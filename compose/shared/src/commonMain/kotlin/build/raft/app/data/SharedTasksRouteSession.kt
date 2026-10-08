package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A retained Tasks tab belongs to one environment/account/server, not to the shell forever. */
internal data class SharedTasksRouteOwner(
    val environment: String,
    val accountId: String?,
    val serverId: String?
)

/** Disposing a route cancels its reads, mutations and projection collector together. */
internal class SharedTasksRouteSession(
    val owner: SharedTasksRouteOwner,
    parentScope: CoroutineScope,
    authStore: SlockAuthSessionStore,
    dataSource: SharedTasksDataSource,
    sharedTasksDataStore: SharedTasksDataStore,
    diagnosticsLogger: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
    )
    // A queued action from a disposed tab must not resolve the newly selected server.
    private val routeAuthStore = object : SlockAuthSessionStore by authStore {
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
        runtimeScopeOverride = scope
    )

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
