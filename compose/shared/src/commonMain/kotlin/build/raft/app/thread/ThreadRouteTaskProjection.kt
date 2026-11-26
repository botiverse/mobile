package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.compat.ui.collectAsStateWhileStarted
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the page's task projection hands back to it: the collected slices and what it derives from them. */
internal class ThreadRouteTaskProjection(
    val taskProjectionSliceState: State<SharedTasksProjectionSlice>,
    val serverTaskProjectionSliceState: State<SharedTasksProjectionSlice>,
    val markdownTaskContextProjectionSliceState: State<SharedTasksProjectionSlice>,
    val markdownTaskStatuses: Map<Int, TaskStatus>?,
    val markdownTaskRefStatuses: Map<String, TaskStatus>,
    val linkedTasksProjection: ThreadLinkedTasksProjection,
)

/**
 * The page's task projection: the linked-task references, the channel and server task slices, the
 * after-motion refresh, the markdown task context slice and its load, and the statuses and linked
 * tasks derived from them. Called every composition of the page from where this code stood, so its
 * remembers and effects keep their keys and their place; only the holder is new each time.
 */
@Composable
internal fun rememberThreadRouteTaskProjection(
    bridge: BridgeModule,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?,
    threadInstanceKey: String,
    timelineSlice: ThreadTimelineSlice,
    payload: RaftThreadScreenPayload,
    baseRenderedPayload: RaftThreadScreenPayload,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    taskProjectionOwnerIdentity: String?,
    taskChannelId: ChannelId?,
    markdownTaskContextChannelId: ChannelId?,
    selectedThreadTabState: State<RaftThreadTab>,
): ThreadRouteTaskProjection {
    val selectedThreadTab by selectedThreadTabState
    val taskProjectionReferences =
        remember(timelineSlice) {
            timelineSlice.threadLinkedTaskReferences()
        }
    val initialTaskProjectionSlice =
        remember(tasksDataStore, taskProjectionOwnerIdentity, currentServerId, taskChannelId?.value) {
            val channelId = taskChannelId
            if (tasksDataStore != null && channelId != null) {
                tasksDataStore.projectionForOwner(
                    expectedOwnerIdentity = taskProjectionOwnerIdentity,
                    serverId = currentServerId,
                    scope = SharedTasksScope.Channel(channelId)
                )
            } else {
                null
            } ?: SharedTasksProjectionSlice()
        }
    val taskProjectionSliceState = remember(
        tasksDataStore,
        currentServerId,
        taskChannelId?.value,
        baseRenderedPayload.title
    ) {
        tasksDataStore
            ?.channelProjectionFlow(
                serverId = currentServerId,
                channelId = taskChannelId?.value,
                channelName = baseRenderedPayload.title
            )
            ?.distinctUntilChanged()
            ?: MutableStateFlow(SharedTasksProjectionSlice())
    }.collectAsStateWhileStarted(initialTaskProjectionSlice)
    val taskProjectionSlice by taskProjectionSliceState
    val initialServerTaskProjectionSlice =
        remember(tasksDataStore, taskProjectionOwnerIdentity, currentServerId) {
            tasksDataStore?.projectionForOwner(
                expectedOwnerIdentity = taskProjectionOwnerIdentity,
                serverId = currentServerId,
                scope = SharedTasksScope.Server
            ) ?: SharedTasksProjectionSlice()
        }
    val serverTaskProjectionSliceState = remember(tasksDataStore, currentServerId) {
        tasksDataStore
            ?.projectionFlow(
                serverId = currentServerId,
                scope = SharedTasksScope.Server
            )
            ?.distinctUntilChanged()
            ?: MutableStateFlow(SharedTasksProjectionSlice())
    }.collectAsStateWhileStarted(initialServerTaskProjectionSlice)
    val serverTaskProjectionSlice by serverTaskProjectionSliceState
    var refreshedTaskProjectionChannelId by remember(threadInstanceKey, currentServerId) {
        mutableStateOf<String?>(null)
    }
    ThreadAfterMotionEffect(
        currentServerId,
        taskChannelId?.value,
        selectedThreadTab,
        payload.status,
        refreshedTaskProjectionChannelId
    ) {
        val serverId = currentServerId ?: return@ThreadAfterMotionEffect
        val resolvedTaskChannelId = taskChannelId ?: return@ThreadAfterMotionEffect
        val sharedStore = tasksDataStore ?: return@ThreadAfterMotionEffect
        val currentPayload = payload.copy(selectedTab = selectedThreadTab.wireValue)
        if (
            !shouldRefreshThreadTasksProjection(
                payload = currentPayload,
                taskChannelId = resolvedTaskChannelId,
                refreshStarted = refreshedTaskProjectionChannelId == resolvedTaskChannelId.value
            )
        ) {
            return@ThreadAfterMotionEffect
        }
        refreshedTaskProjectionChannelId = resolvedTaskChannelId.value
        bridge.log(
            "thread_tasks_projection_refresh_start threadId=${payload.sourceThreadId.orEmpty()} " +
                "channelId=${resolvedTaskChannelId.value} status=${taskProjectionSlice.status.name} " +
                "exactLoaded=${taskProjectionSlice.isExactScopeLoaded}"
        )
        when (
            val result =
                sharedStore.loadSnapshot(
                    serverId = serverId,
                    scope = SharedTasksScope.Channel(resolvedTaskChannelId),
                    forceRefresh = true
                ) {
                    tasksDataSource.loadChannelTasks(
                        serverId = ServerId(serverId),
                        channelId = resolvedTaskChannelId,
                        priority = DataRequestPriority.Background
                    )
                }
        ) {
            is DataResult.Content -> {
                bridge.log(
                    "thread_tasks_projection_refresh_result result=content " +
                        "channelId=${resolvedTaskChannelId.value} tasks=${result.data.tasks.size}"
                )
            }
            is DataResult.Empty -> {
                bridge.log(
                    "thread_tasks_projection_refresh_result result=empty " +
                        "channelId=${resolvedTaskChannelId.value}"
                )
            }
            is DataResult.Error ->
                bridge.log(
                    "thread_tasks_projection_refresh_result result=error " +
                        "channelId=${resolvedTaskChannelId.value} code=${result.error.code}"
                )
            is DataResult.NoPermission ->
                bridge.log(
                    "thread_tasks_projection_refresh_result result=no_permission " +
                        "channelId=${resolvedTaskChannelId.value} code=${result.blocker.code}"
                )
        }
    }
    // The task context channel's tasks (Web `taskByChannelNumber` for `taskContextChannelId`). In a
    // task conversation that is the host channel, whose tasks [taskConversationTasksViewModel] loads.
    val markdownTaskContextChannelName =
        baseRenderedPayload.title.takeUnless { payload.channelKind == RaftTaskConversationChannelKind }
    val markdownTaskContextProjectionSliceState = remember(
        tasksDataStore,
        currentServerId,
        markdownTaskContextChannelId?.value,
        markdownTaskContextChannelName
    ) {
        markdownTaskContextChannelId?.let { channelId ->
            tasksDataStore
                ?.channelProjectionFlow(
                    serverId = currentServerId,
                    channelId = channelId.value,
                    channelName = markdownTaskContextChannelName
                )
                ?.distinctUntilChanged()
        } ?: MutableStateFlow(SharedTasksProjectionSlice())
    }.collectAsStateWhileStarted(SharedTasksProjectionSlice())
    val markdownTaskContextProjectionSlice by markdownTaskContextProjectionSliceState
    // A reader without write access (or whose access is still resolving) has no [taskChannelId], so
    // nothing above loads the context channel's tasks; load them for its task refs.
    LaunchedEffect(currentServerId, markdownTaskContextChannelId?.value, taskChannelId?.value, payload.status) {
        val serverId = currentServerId ?: return@LaunchedEffect
        val contextChannelId = markdownTaskContextChannelId ?: return@LaunchedEffect
        val sharedStore = tasksDataStore ?: return@LaunchedEffect
        if (
            !shouldLoadMarkdownTaskContextTasks(
                payload = payload,
                contextChannelId = contextChannelId,
                taskChannelId = taskChannelId
            )
        ) {
            return@LaunchedEffect
        }
        val result =
            sharedStore.loadSnapshot(
                serverId = serverId,
                scope = SharedTasksScope.Channel(contextChannelId),
                forceRefresh = false
            ) {
                tasksDataSource.loadChannelTasks(
                    serverId = ServerId(serverId),
                    channelId = contextChannelId,
                    priority = DataRequestPriority.Background
                )
            }
        bridge.log(
            "thread_markdown_task_context_load channelId=${contextChannelId.value} " +
                "result=${result::class.simpleName}"
        )
    }
    val markdownTaskStatuses =
        remember(markdownTaskContextProjectionSlice, serverTaskProjectionSlice, markdownTaskContextChannelId) {
            threadMarkdownTaskStatusesByNumber(
                channelTasks = markdownTaskContextProjectionSlice.tasks,
                serverTasks = serverTaskProjectionSlice.tasks,
                currentChannelId = markdownTaskContextChannelId?.value,
                loaded = threadMarkdownTaskSourceLoaded(markdownTaskContextProjectionSlice, serverTaskProjectionSlice)
            )
        }
    val markdownTaskRefStatuses =
        remember(taskProjectionSlice, serverTaskProjectionSlice, payload.dmPeerName, payload.surfaceKind, payload.channelKind) {
            threadMarkdownTaskRefStatuses(
                channelTasks = taskProjectionSlice.tasks,
                serverTasks = serverTaskProjectionSlice.tasks,
                currentDirectMessagePeerName =
                    payload.dmPeerName.takeIf {
                        payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage || payload.channelKind == "dm"
                    }
            )
        }
    val sourceTaskListsEnabled = payload.supportsChannelTasks()
    val linkedTasksProjection =
        remember(
            taskProjectionSlice,
            serverTaskProjectionSlice,
            taskProjectionReferences,
            sourceTaskListsEnabled,
            currentSession?.user?.id
        ) {
            threadLinkedTasksProjection(
                channelProjectionSlice = taskProjectionSlice,
                serverProjectionSlice = serverTaskProjectionSlice,
                references = taskProjectionReferences
            ).copy(
                sourceTaskLists =
                    if (sourceTaskListsEnabled) {
                        taskProjectionSlice.tasks.threadSourceTaskLists()
                    } else {
                        emptyMap()
                    },
                viewerUserId = currentSession?.user?.id
            )
        }
    return ThreadRouteTaskProjection(
        taskProjectionSliceState = taskProjectionSliceState,
        serverTaskProjectionSliceState = serverTaskProjectionSliceState,
        markdownTaskContextProjectionSliceState = markdownTaskContextProjectionSliceState,
        markdownTaskStatuses = markdownTaskStatuses,
        markdownTaskRefStatuses = markdownTaskRefStatuses,
        linkedTasksProjection = linkedTasksProjection,
    )
}
