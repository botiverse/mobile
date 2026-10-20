package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksRouteOwner
import build.raft.app.data.SharedTasksRouteSession
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.TasksUiState
import kotlinx.coroutines.flow.collect

internal data class SharedTasksRouteBinding(
    val route: SharedTasksRouteSession,
    val state: TasksUiState
)

/** Keep the changing route's composition state inside its own restart group. */
@Composable
internal fun rememberSharedTasksRoute(
    owner: SharedTasksRouteOwner,
    authStore: RaftAuthSessionStore,
    dataSource: SharedTasksDataSource,
    sharedTasksDataStore: SharedTasksDataStore
): SharedTasksRouteBinding {
    val parentScope = rememberCoroutineScope()
    val route = remember(owner, authStore, dataSource, sharedTasksDataStore) {
        SharedTasksRouteSession(
            owner = owner,
            parentScope = parentScope,
            authStore = authStore,
            dataSource = dataSource,
            sharedTasksDataStore = sharedTasksDataStore,
            diagnosticsLogger = raftLogger("SlockTasks", "shared_tasks")
        )
    }
    DisposableEffect(route) {
        onDispose { route.close() }
    }
    // collectAsState remembers its prior value when its Flow changes. Explicitly
    // replace that state with the new route's initial Loading before collecting.
    var state by remember(route) { mutableStateOf(route.viewModel.state.value) }
    LaunchedEffect(route) {
        route.viewModel.state.collect { state = it }
    }
    return SharedTasksRouteBinding(route, state)
}
