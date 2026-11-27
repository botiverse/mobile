package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.HomeMessageOpenRequest
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftBackHandler
import build.raft.app.RaftTimeContextProvider
import build.raft.app.TaskConversationHeadRow
import build.raft.app.TaskConversationHeaderChrome
import build.raft.app.TaskHeadPlacement
import build.raft.app.TaskHeadScrollState
import build.raft.app.base.BridgeModule
import build.raft.app.settings.buildAppShellThreadPayload
import build.raft.app.canBeDeletedBy
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskConversationHeaderViewModel
import build.raft.app.data.TasksUiState
import build.raft.app.data.canWriteTasksInChannel
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary
import build.raft.app.rememberTaskConversationHeadRow
import build.raft.app.taskChannelOpenRequest
import build.raft.app.taskConversationHeaderChrome
import build.raft.app.taskViewInChannelAnchor
import build.raft.app.thread.toRaftTimeCapabilities
import build.raft.app.toRaftTimeCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * The task conversation's head: the task channel's members and whether the reader may write it, the
 * way out to a place in that channel, the head's scroll state and Back handler, the top bar's chrome
 * and the head as the bar, the pull-down and the first row. Called every composition of the page's
 * feature slots from where this code stood, so its remembers and its Back handler keep their keys
 * and their place; only the holder is new each time. The page's keyboard inset comes as its
 * `State` and is read live by the head, as before.
 */
internal class ThreadTaskConversationHead(
    val taskHeadScrollState: TaskHeadScrollState,
    val taskConversationChrome: TaskConversationHeaderChrome?,
    val taskConversationBar: (@Composable () -> Unit)?,
    val taskConversationFloat: (@Composable () -> Unit)?,
    val taskConversationHeadRow: TaskConversationHeadRow,
)

/** The task conversation's head: see [ThreadTaskConversationHead]. */
@Composable
internal fun rememberThreadTaskConversationHead(
    bridge: BridgeModule,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    serverMembersStore: SharedServerMembersStore?,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
    renderedThread: RaftThreadScreenPayload,
    renderedTimelineSlice: ThreadTimelineSlice,
    timeContextProvider: RaftTimeContextProvider,
    agentStatusResolver: RaftAgentStatusResolver,
    unableToOpenTaskLabel: String,
    onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit,
    onOpenMemberProfile: (RaftThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity) -> Unit,
    adaptivePageKeyboardInsetDpState: State<Float>,
    taskConversationId: ChannelId?,
    taskConversationHeaderViewModel: TaskConversationHeaderViewModel?,
    taskConversationHeaderState: TaskConversationHeaderState?,
    taskConversationParentChannelId: ChannelId?,
    taskConversationTasksViewModel: SharedTasksViewModel?,
    taskConversationTasksState: TasksUiState?,
    taskConversationTask: TaskSummary?,
    taskConversationDetailsViewModel: TaskConversationDetailsViewModel?,
    taskConversationDetailsState: TaskConversationDetailsState,
): ThreadTaskConversationHead {
    val adaptivePageKeyboardInsetDp by adaptivePageKeyboardInsetDpState
    // Task v2: the task channel's people and agents for the assignee picker (web
    // `useChannelMembers(task.channelId)`); a task conversation has no members of its own.
    val taskConversationMembersServerId = currentServerId?.let(::ServerId)
    val taskConversationMembersSlice by remember(serverMembersStore, taskConversationMembersServerId, taskConversationParentChannelId) {
        val store = serverMembersStore
        if (store != null && taskConversationMembersServerId != null && taskConversationParentChannelId != null) {
            store.channelMemberSliceFlow(taskConversationMembersServerId, taskConversationParentChannelId)
                .onStart {
                    store.loadChannelMembers(taskConversationMembersServerId, taskConversationParentChannelId, DataRequestPriority.Visible)
                }
        } else {
            MutableStateFlow(SharedServerChannelMembersSlice(serverId = currentServerId, channelId = taskConversationParentChannelId?.value.orEmpty()))
        }
    }.collectAsState(SharedServerChannelMembersSlice(serverId = currentServerId, channelId = taskConversationParentChannelId?.value.orEmpty()))
    // Web canWriteTasksInChannel (slock #15155): status, assignee, title / description edit and
    // Delete only where the reader may write the task's channel (joined, not archived). A channel
    // this page does not know stays writable; the server refuses with its sentence and trace id.
    val taskConversationChannelWritable by remember(conversationIdentityProvider, taskConversationMembersServerId, taskConversationParentChannelId) {
        val provider = conversationIdentityProvider
        val server = taskConversationMembersServerId
        val channelId = taskConversationParentChannelId
        if (provider == null || server == null || channelId == null) {
            MutableStateFlow(true)
        } else {
            provider.observeServer(server).map { directory -> directory.canWriteTasksInChannel(server, channelId) }.distinctUntilChanged()
        }
    }.collectAsState(true)
    // Task v2: leave the task for a place in its channel (web `onLeave` + `nav.toMessage`): its
    // source / host message, the channel itself, or a pre-v2 task's earlier discussion thread.
    fun openTaskConversationTarget(request: HomeMessageOpenRequest?, action: String) {
        val loadingPayload =
            request?.let { openRequest ->
                buildAppShellThreadPayload(
                    conversation = openRequest.conversationIdentity,
                    timeContext = renderedThread.toRaftTimeCapabilities().staticContext(),
                    alignOwnMessagesRight = renderedThread.alignOwnMessagesRight,
                    compactConsecutiveMessages = renderedThread.compactConsecutiveMessages,
                    anchorMessageId = openRequest.anchorMessageId,
                    originFallback = openRequest.originFallback,
                    onWarn = bridge::logWarn
                )
            }
        if (loadingPayload == null) {
            bridge.logWarn(
                "task_conversation_open_rejected action=$action reason=identity_unresolved " +
                    "taskId=${taskConversationTask?.id?.value.orEmpty()}"
            )
            bridge.toast(unableToOpenTaskLabel)
            return
        }
        bridge.log(
            "task_conversation_open action=$action taskId=${taskConversationTask?.id?.value.orEmpty()} " +
                "targetThreadId=${loadingPayload.sourceThreadId.orEmpty()} " +
                "focusedMessageId=${loadingPayload.focusedMessageId.orEmpty()}"
        )
        onOpenThreadPayload(loadingPayload)
    }
    fun taskConversationDirectory(task: TaskSummary): SharedConversationDirectoryState =
        listOfNotNull(
            currentServerId?.let(::ServerId)?.let { server ->
                task.channelId?.let { conversationIdentityProvider?.resolveProjection(server, it.value) }
            }
        ).toSharedConversationDirectoryState()
    val openTaskInChannel: ((TaskSummary, MessageId?, String) -> Unit) = { task, anchor, action ->
        openTaskConversationTarget(
            task.taskChannelOpenRequest(currentServerId.orEmpty(), anchor, taskConversationDirectory(task)),
            action,
        )
    }
    // Task v2 on a phone: the head is the conversation's first row; while that row is out of
    // view the top bar names the task's state and pulls the head down over the messages.
    val taskHeadScrollState = remember(taskConversationId) { TaskHeadScrollState() }
    // Back (and Escape) puts the pulled-down head back up before it leaves the page.
    RaftBackHandler(enabled = taskHeadScrollState.floatOpen, label = "task_head_float") {
        taskHeadScrollState.closeFloat()
    }
    val taskConversationChrome: TaskConversationHeaderChrome? =
        if (taskConversationTask != null && taskConversationHeaderState != null && !taskConversationHeaderState.removed) {
            taskConversationTask.taskConversationHeaderChrome(
                state = taskConversationHeaderState,
                summarized = taskHeadScrollState.collapsed,
                headFloatOpen = taskHeadScrollState.floatOpen,
                onToggleHeadFloat = { taskHeadScrollState.toggleFloat() },
                onViewInChannel = {
                    openTaskInChannel(taskConversationTask, taskConversationTask.taskViewInChannelAnchor(), "view_in_channel")
                },
                onToggleFollow = { followed -> taskConversationHeaderViewModel?.toggleFollow(followed) },
                serverId = currentServerId,
                viewerUserId = currentSession?.user?.id,
                // Web #14115: the creator, or a server owner / admin (`deleteAnyTask`); asks first.
                onDeleteTask =
                    { taskHeadScrollState.deleteConfirming = true }.takeIf {
                        taskConversationTasksViewModel != null &&
                            taskConversationTask.canBeDeletedBy(
                                currentSession?.user?.id,
                                currentSession?.servers?.firstOrNull { it.id == currentServerId }?.role,
                                canWriteChannel = taskConversationChannelWritable,
                            )
                    },
            )
        } else {
            null
        }
    // The page's live clock supplies "today": the static context has none, and without it the
    // head's Source and History times fell back to the raw wire timestamp (1.17.0 build 3 smoke).
    val taskConversationTimeContext =
        timeContextProvider.dateContext ?: renderedThread.toRaftTimeCapabilities().staticContext()
    // The head as the conversation's first row at every width, and pulled down from the bar
    // ([TaskHeadPlacement]); the page's placement holds its effects.
    val taskConversationHeadRoute: (@Composable (placement: TaskHeadPlacement) -> Unit)? =
        if (taskConversationTask != null && taskConversationHeaderState != null && !taskConversationHeaderState.removed) {
            { placement ->
                ThreadRouteTaskHead(
                    placement = placement,
                    taskConversationTask = taskConversationTask,
                    taskConversationHeaderState = taskConversationHeaderState,
                    taskConversationDetailsState = taskConversationDetailsState,
                    taskConversationTasksState = taskConversationTasksState,
                    taskConversationMembersSlice = taskConversationMembersSlice,
                    taskConversationTimeContext = taskConversationTimeContext,
                    taskConversationTasksViewModel = taskConversationTasksViewModel,
                    taskConversationDetailsViewModel = taskConversationDetailsViewModel,
                    taskConversationHeaderViewModel = taskConversationHeaderViewModel,
                    taskHeadScrollState = taskHeadScrollState,
                    taskConversationChannelWritable = taskConversationChannelWritable,
                    adaptivePageKeyboardInsetDp = adaptivePageKeyboardInsetDp,
                    renderedThread = renderedThread,
                    renderedTimelineSlice = renderedTimelineSlice,
                    currentServerId = currentServerId,
                    onOpenMemberProfile = onOpenMemberProfile,
                    openTaskInChannel = openTaskInChannel,
                    onOpenTaskConversationTarget = ::openTaskConversationTarget,
                    onTaskConversationDirectory = ::taskConversationDirectory,
                    viewerUserId = currentSession?.user?.id,
                    agentStatusResolver = agentStatusResolver,
                )
            }
        } else {
            null
        }
    val taskConversationBar: (@Composable () -> Unit)? = taskConversationHeadRoute?.let { route -> { route(TaskHeadPlacement.Page) } }
    val taskConversationFloat: (@Composable () -> Unit)? = taskConversationHeadRoute?.let { route -> { route(TaskHeadPlacement.PullDown) } }
    val taskConversationHeadRow =
        rememberTaskConversationHeadRow(taskHeadScrollState) { taskConversationHeadRoute?.invoke(TaskHeadPlacement.FirstRow) }
    return ThreadTaskConversationHead(
        taskHeadScrollState = taskHeadScrollState,
        taskConversationChrome = taskConversationChrome,
        taskConversationBar = taskConversationBar,
        taskConversationFloat = taskConversationFloat,
        taskConversationHeadRow = taskConversationHeadRow,
    )
}
