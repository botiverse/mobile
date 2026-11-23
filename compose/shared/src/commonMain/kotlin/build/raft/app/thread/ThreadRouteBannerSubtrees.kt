package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.assistant.ThreadAssistantOtter
import build.raft.app.assistant.ThreadAssistantPanelConversationMissing
import build.raft.app.assistant.ThreadAssistantPanelSheet
import build.raft.app.assistant.assistantOtterDmOpenRequest
import build.raft.app.base.BridgeModule
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskConversationHeaderViewModel
import build.raft.app.data.TasksUiState
import build.raft.app.data.taskHeadSourceMessageId
import build.raft.app.data.valueOrNull
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary

// Subtrees of the banner host's content lambda in `SharedThreadRouteContentContent`, moved verbatim
// (dedent only) and called where they were: the route reads like a list of its parts, and its
// method stays under the JVM's size limit. Non-restartable, so each runs whenever the route's
// content does, never skipped on its own.

/**
 * The assistant over this page (web AssistantOtter's phone Drawer): its head, then its DM as the
 * conversation page itself (THREAD_PAGE embedded, without its head). [bannerHandoff] is the
 * route's foreground banner, drawn over the panel while it is open.
 */
@Composable
@NonRestartableComposable
internal fun ThreadRouteAssistantPanel(
    otter: ThreadAssistantOtter,
    bannerHandoff: RaftForegroundBannerHandoff?,
    renderedThread: RaftThreadScreenPayload,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
    bridge: BridgeModule,
    storeScope: RaftShellStoreScope,
) {
    val coverOwner = remember { Any() }
    DisposableEffect(bannerHandoff, coverOwner) {
        onDispose { bannerHandoff?.setWindowCover(coverOwner, false) }
    }
    ThreadAssistantPanelSheet(
        otter,
        // A phone's drawer covers the page: its banner goes over the drawer instead.
        onCoversPageChange = { covers -> bannerHandoff?.setWindowCover(coverOwner, covers) },
        // The page's banner stays over the drawer window for the full time the drawer owns
        // coverage (including its closing slide), avoiding both Frame-1 double-draw and
        // close-frame blank gaps.
        windowOverlay = {
            val handoff = bannerHandoff
            handoff?.banner?.takeIf { handoff.isCoveredByWindow }?.let { banner ->
                RaftForegroundMessageBannerView(
                    banner = banner,
                    topSafeArea = handoff.topSafeArea,
                    serverMembersStore = handoff.serverMembersStore,
                    currentServerId = foregroundBannerMemberServerId(banner, handoff.currentServerId),
                    onClick = { handoff.onClick(banner) },
                    onDismiss = { handoff.onDismiss(banner) },
                )
            }
        },
    ) {
        val dmPageData =
            remember(otter.serverId, otter.dmChannelId) {
                val request = assistantOtterDmOpenRequest(otter.serverId, otter.dmChannelId, conversationIdentityProvider)
                val launch =
                    request
                        ?.let {
                            buildAppShellThreadPayload(
                                conversation = it.conversationIdentity,
                                timeContext = renderedThread.toRaftTimeCapabilities().staticContext(),
                                alignOwnMessagesRight = renderedThread.alignOwnMessagesRight,
                                compactConsecutiveMessages = renderedThread.compactConsecutiveMessages,
                                onWarn = bridge::logWarn
                            )
                        }
                        ?.toWireThreadLaunchPayloadOrNull()
                launch?.let {
                    threadLaunchPageData(it)
                        .withRaftShellStoreScope(RaftShellStoreScope.create(storeScope.shellInstanceId, otter.serverId))
                }
            }
        LaunchedEffect(dmPageData == null) {
            if (dmPageData == null) bridge.logWarn("assistant_panel_dm_unresolved dm=${otter.dmChannelId}")
        }
        if (dmPageData == null) {
            ThreadAssistantPanelConversationMissing(otter.assistantName)
        } else {
            RaftChildPage(
                pageName = THREAD_PAGE,
                pageData = dmPageData,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * A task conversation's head at [placement]: the page's effects (no room above the conversation),
 * its first row, or pulled down from the bar ([TaskHeadPlacement]).
 */
@Composable
@NonRestartableComposable
internal fun ThreadRouteTaskHead(
    placement: TaskHeadPlacement,
    taskConversationTask: TaskSummary,
    taskConversationHeaderState: TaskConversationHeaderState,
    taskConversationDetailsState: TaskConversationDetailsState,
    taskConversationTasksState: TasksUiState?,
    taskConversationMembersSlice: SharedServerChannelMembersSlice,
    taskConversationTimeContext: RaftTimeContext,
    taskConversationTasksViewModel: SharedTasksViewModel?,
    taskConversationDetailsViewModel: TaskConversationDetailsViewModel?,
    taskConversationHeaderViewModel: TaskConversationHeaderViewModel?,
    taskHeadScrollState: TaskHeadScrollState,
    taskConversationChannelWritable: Boolean,
    adaptivePageKeyboardInsetDp: Float,
    renderedThread: RaftThreadScreenPayload,
    renderedTimelineSlice: ThreadTimelineSlice,
    currentServerId: String?,
    onOpenMemberProfile: (RaftThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity) -> Unit,
    openTaskInChannel: (TaskSummary, MessageId?, String) -> Unit,
    onOpenTaskConversationTarget: (HomeMessageOpenRequest?, String) -> Unit,
    onTaskConversationDirectory: (TaskSummary) -> SharedConversationDirectoryState,
    viewerUserId: String? = null,
    agentStatusResolver: RaftAgentStatusResolver? = null,
) {
    // The route's names for these, so the moved code reads as it did there.
    fun openTaskConversationTarget(request: HomeMessageOpenRequest?, action: String) = onOpenTaskConversationTarget(request, action)
    fun taskConversationDirectory(task: TaskSummary): SharedConversationDirectoryState = onTaskConversationDirectory(task)
    TaskConversationHeadRoute(
        task = taskConversationTask,
        state = taskConversationHeaderState,
        details = taskConversationDetailsState,
        statusUpdating = taskConversationTasksState?.statusUpdatingTaskId == taskConversationTask.id.value,
        assigneeCandidates = taskConversationMembersSlice.members,
        timeContext = taskConversationTimeContext,
        onUpdateStatus = { status ->
            taskConversationTasksViewModel?.let { TaskHeadEdits(it::dispatch).updateStatus(taskConversationTask, status) }
        },
        onMoveToBacklog = { reason ->
            taskConversationTasksViewModel?.let { TaskHeadEdits(it::dispatch).moveToBacklog(taskConversationTask, reason) }
        },
        onAssign = { member ->
            taskConversationTasksViewModel?.let { TaskHeadEdits(it::dispatch).assign(taskConversationTask, member) }
        },
        onOpenSource = {
            openTaskInChannel(taskConversationTask, taskConversationTask.taskHeadSourceMessageId(), "source")
        },
        // The creator's face and name: their profile, as a message author's avatar opens it.
        onOpenCreator = { person ->
            onOpenMemberProfile(
                renderedThread,
                renderedTimelineSlice,
                MemberDetailIdentity(MemberId(person.id), person.kind, currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)),
            )
        },
        onOpenEarlierDiscussion = {
            val earlier = taskConversationDetailsState.earlierDiscussion.valueOrNull
            openTaskConversationTarget(
                taskConversationTask.taskEarlierDiscussionOpenRequest(
                    serverId = currentServerId.orEmpty(),
                    threadChannelId = earlier?.threadChannelId,
                    conversationDirectory = taskConversationDirectory(taskConversationTask),
                ),
                "earlier_discussion",
            )
        },
        scrollState = taskHeadScrollState,
        // Typing on a phone: the pulled-down head goes back up.
        keyboardUp = adaptivePageKeyboardInsetDp > 0f,
        onRetryHistory = { taskConversationDetailsViewModel?.retryHistory() },
        // The offline stand-in refuses writes: its status and assignee are plain facts.
        editable = taskConversationTasksState?.standIn != true,
        canWriteChannel = taskConversationChannelWritable,
        placement = placement,
        serverId = currentServerId,
        onAmend = taskConversationTasksViewModel.taskHeadAmend(taskConversationTask),
        // Once deleted the page closes, as on `task:deleted` (web closes the dialog).
        onDelete =
            taskConversationTasksViewModel.taskHeadDelete(taskConversationTask) {
                taskConversationHeaderViewModel?.onTaskDeleted(taskConversationTask.id, null)
            },
        viewerUserId = viewerUserId,
        agentStatusResolver = agentStatusResolver,
        onOpenAssigneeProfile = { person ->
            onOpenMemberProfile(
                renderedThread,
                renderedTimelineSlice,
                MemberDetailIdentity(MemberId(person.id), person.kind, currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)),
            )
        },
    )
}
