# Thread Channel Tasks Reuse Spec

## Context

The Home Tasks tab already uses the shared Tasks stack:

`SharedTasksScreen -> SharedTasksPanel -> SharedTasksViewModel -> SharedTasksUseCase -> SharedTasksRepository -> SharedTasksDataSource`.

The Thread Tasks tab currently reuses `SharedTasksPanel` only as a presentation shell. It builds a `TasksUiState` from `RaftThreadScreenPayload.tasks` through `ThreadTasksTabCache`, then ignores `Start`, `Refresh`, and `CreateTasks` route intents. That means channel-scoped tasks can look like the Home Tasks UI, but they cannot reliably load from the canonical channel tasks API, refresh, or create tasks through the shared Tasks data path.

This explains the current failure mode: tasks can appear stale or empty in a thread even when the Home Tasks tab works.

## Goal

Use one shared Tasks surface for both:

- Home Tasks: server-wide / cross-channel tasks.
- Thread channel Tasks: tasks scoped to the current channel/thread, with channel selection disabled.

The two contexts should differ only by scope and allowed controls, not by separate task rendering or status handling logic.

## Non-Goals

- Do not create a second Thread-only tasks board.
- Do not hand-roll task cards or status menus inside `ThreadPage.kt`.
- Do not make UI call raw APIs or parse task payloads directly.
- Do not add channel selection inside a single-channel Thread context.

## Proposed Shape

Introduce a Tasks route scope:

```kotlin
sealed interface SharedTasksScope {
    data object Server : SharedTasksScope
    data class Channel(val channelId: ChannelId) : SharedTasksScope
}
```

Thread should instantiate the same Tasks ViewModel/store with `SharedTasksScope.Channel(currentChannelId)`. Home should keep using `SharedTasksScope.Server`.

The shared chain becomes:

`UI -> SharedTasksPanel -> SharedTasksViewModel(scope) -> SharedTasksUseCase -> SharedTasksRepository -> SharedTasksDataSource`.

`SharedTasksUseCase.refreshTasks` selects the repository method by scope:

- `Server`: load server tasks.
- `Channel(channelId)`: load channel tasks.

`SharedTasksUseCase.createTasks` is only enabled when scope is channel-backed. In Home/server scope, creation remains hidden unless a caller passes an explicit channel id.

## UI Contract

`SharedTasksPanel` should remain the single reusable surface. Add an explicit config instead of Thread-specific branches:

```kotlin
data class SharedTasksPanelConfig(
    val showHeader: Boolean,
    val showChannelFilter: Boolean,
    val createMode: CreateMode,
    val emptyTitle: String = "No tasks",
)

sealed interface CreateMode {
    data object Hidden : CreateMode
    data class FixedChannel(val channelId: ChannelId) : CreateMode
}
```

Home Tasks config:

- `showHeader = true`
- `showChannelFilter = true`
- `createMode = Hidden` unless a specific channel is selected by a future flow

Thread Tasks config:

- `showHeader = false`
- `showChannelFilter = false`
- `createMode = FixedChannel(threadChannelId)`
- Use the same board/list mode, task card, status menu, assignee filter, empty/loading/error/no-permission states.

## Data Contract

Repository APIs should expose typed domain results only:

```kotlin
interface SharedTasksRepository {
    fun observeTasks(scope: SharedTasksScope, filter: TaskFilter): Flow<DataResult<TaskSnapshot>>
    suspend fun refreshTasks(scope: SharedTasksScope, filter: TaskFilter): DataResult<TaskSnapshot>
    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult>
    suspend fun createTasks(channelId: ChannelId, titles: List<String>): DataResult<TaskSnapshot>
}
```

Implementation details:

- Server scope uses `/api/servers/:serverId/tasks`.
- Channel scope uses `/api/channels/:channelId/tasks`.
- Local cache keys must include scope, so a channel-scoped refresh cannot replace the server-wide Home board.
- Status update and realtime task events should patch both caches when applicable:
  - Update the scoped channel cache if it contains the task.
  - Update the server cache if the task belongs to the active server.

## Thread Integration

Replace `ThreadTasksTabCache` as the primary data source.

Thread page should:

1. Create or obtain a `SharedTasksViewModel` keyed by `threadChannelId`.
2. Dispatch `Start` when the Tasks tab becomes active for that channel.
3. Dispatch `Refresh` on pull/manual refresh.
4. Pass the real `tasksState` into `SharedTasksPanel`.
5. Pass `CreateMode.FixedChannel(threadChannelId)` so "New Task" creates in the current channel without a channel selector.
6. Keep `onOpenTask` and `UpdateStatus` routed through the shared task intents.

`payload.tasks` should become optional bootstrap data only:

- It may seed the local channel cache while the network request is in flight.
- It must not be the only source of truth.
- Refresh failure should keep the last cached tasks and show route/action error, matching Home Tasks behavior.

## Acceptance Criteria

- Home Tasks and Thread Tasks render through the same `SharedTasksPanel`, `SharedTaskCard`, status menu, filters, loading/empty/error/no-permission components.
- Thread Tasks loads from channel-scoped repository/API, not only from `payload.tasks`.
- Thread Tasks has no channel filter UI.
- Thread Tasks can create a new task in the current channel without asking for a channel.
- Status changes in Thread Tasks use the same typed repository path as Home Tasks.
- Refresh failure keeps old Thread Tasks content and surfaces an error.
- Realtime task create/update/delete patches the channel task state without bypassing the store reducer.
- Home server-wide tasks remain server-scoped and are not replaced by a single channel snapshot.

## Suggested Tests

- `SharedTasksUseCaseTest`: server scope calls server tasks; channel scope calls channel tasks.
- `SharedTasksRepositoryTest`: channel refresh writes only the channel cache; server refresh writes only the server cache.
- `SharedTasksStoreTest`: refresh failure preserves content for channel scope.
- `ThreadTasksTabTest`: Tasks tab dispatches `Start`, hides channel filter, enables fixed-channel create.
- Connected/canonical smoke: open Thread -> Tasks tab -> tasks load; create task -> card appears; change status -> card updates; refresh preserves content on failure.

## Rollout

1. Add `SharedTasksScope` and config objects while keeping existing Home behavior.
2. Move `SharedTasksViewModel` load/observe paths to scope-aware requests.
3. Switch Thread Tasks tab from payload-only cache to scoped `SharedTasksViewModel`.
4. Keep `payload.tasks` as bootstrap cache input for initial paint.
5. Add focused unit tests, then run shared compile and Android connected compile.

