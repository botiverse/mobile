package build.raft.app.thread

import build.raft.app.resources.*
import build.raft.app.settings.settingsBillingOffersUpgrade
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.thread.toRaftTimeCapabilities
import build.raft.app.toRaftTimeCapabilities
import build.raft.app.traceId
import build.raft.app.data.sideThreadParentFocus
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftEventBus
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataResult
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.unfollowThreadProjectingHome
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedReferenceFailure
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.MemberProfile
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.TaskSummary
import build.raft.app.settings.openRaftUpgradeDialog
import build.raft.app.settings.openSettingsBillingPage
import build.raft.app.resolve
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.model.ServerId
import build.raft.app.compat.pager.Pager
import build.raft.app.currentRevisionForReplyCacheOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAdaptiveDetailCloseRequest
import build.raft.app.shell.RaftLinkNavigator
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.settings.RaftUpgradeExternalContext
import build.raft.app.settings.RaftUpgradeGate
import build.raft.app.ReplyCacheProvenance
import build.raft.app.TaskCreateFromMessageFlow
import build.raft.app.agentLoungeLookupChannelId
import build.raft.app.settings.buildAppShellThreadPayload
import build.raft.app.data.SharedAdaptiveWorkspaceStore
import build.raft.app.data.SharedRealtimeRoomLeaseRegistry

/**
 * The route's thread events (`onThreadEvent`): what used to be the `ThreadPageContent@` lambda in
 * `SharedThreadRouteContentContent`, moved verbatim. [handle] keeps its checks before the `when` and
 * the branch order; each branch body is its own method (the larger ones in
 * ThreadRouteEventHandlerActions.kt), and `run ThreadPageContent@{ }` keeps the bodies'
 * `return@ThreadPageContent` as they were.
 *
 * Built every composition from the values the lambda captured (states as their [State] /
 * [MutableState], read and written when an event runs, as the lambda did); never remembered.
 */
internal class ThreadRouteEventHandler(
    val bridge: build.raft.app.base.BridgeModule,
    val dataSource: SharedThreadDataSource,
    val tasksDataSource: SharedTasksDataSource,
    val tasksDataStore: SharedTasksDataStore?,
    val serverMembersStore: SharedServerMembersStore?,
    val conversationIdentityProvider: SharedConversationIdentityProvider?,
    val storeScope: RaftShellStoreScope,
    val eventBus: RaftEventBus,
    val adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
    val onBack: () -> Unit,
    val onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit,
    val onOpenParentThread: (RaftThreadScreenPayload, String?) -> Boolean,
    val onOpenMemberProfile: (RaftThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity) -> Unit,
    val onOpenMembersPager: (RaftThreadScreenPayload, ServerId, ChannelId, RaftShellStoreScope, Boolean) -> Unit,
    val onOpenChannelSettings: (ServerId, ChannelId, RaftShellStoreScope, String) -> Unit,
    val pager: Pager,
    val sharedStores: RaftSharedStoreProvider,
    val sharedHomeStore: SharedHomeStore?,
    val realtimeEvents: RaftRealtimeEvents,
    val threadViewModel: SharedThreadViewModel,
    val renderSliceState: State<SharedThreadRenderSlice>,
    val adaptiveFocusManager: FocusManager,
    val adaptiveKeyboardController: SoftwareKeyboardController?,
    val adaptiveFocusWorkspaceStore: SharedAdaptiveWorkspaceStore,
    val payload: RaftThreadScreenPayload,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    val currentSession: StoredAuthSession?,
    val replyCacheProvenanceOwner: ReplyCacheProvenance?,
    val currentServerSlug: String?,
    val replyCacheProvenance: ReplyCacheProvenance?,
    val realtimeRoomLeaseRegistry: SharedRealtimeRoomLeaseRegistry,
    val coroutineScope: CoroutineScope,
    val pendingReactionKeysState: MutableState<Set<ThreadReactionPendingKey>>,
    val pendingReactionVisualsState: MutableState<Map<ThreadReactionPendingKey, ThreadReactionPendingVisual>>,
    val pendingActionCardIdsState: MutableState<Set<String>>,
    val createTaskFromMessage: TaskCreateFromMessageFlow,
    val pendingThreadFollowMessageIdsState: MutableState<Set<String>>,
    val markdownRefOpenStore: ThreadMarkdownRefOpenStore,
    val textSelectionMessageState: MutableState<RaftThreadMessageItem?>,
    val viewContextMessageState: MutableState<RaftThreadMessageItem?>,
    val createAgentDialogStateHolder: MutableState<ThreadCreateAgentActionDialogState?>,
    val migrateAgentDialogStateHolder: MutableState<ThreadMigrateAgentActionDialogState?>,
    val markdownTaskOpenRequestGate: MarkdownTaskNumberOpenRequestGate,
    val labels: ThreadRouteLabels,
    val taskChannelId: ChannelId?,
    val tasksViewModel: SharedTasksViewModel?,
    val currentServerId: String?,
    val markdownTaskContextChannelId: ChannelId?,
    val threadMembersServerId: ServerId?,
    val threadMembersChannelId: ChannelId?,
    val linkedTasksProjection: ThreadLinkedTasksProjection,
    val taskAssigneeOptions: List<MemberProfile>,
    val renderedThread: RaftThreadScreenPayload,
    val linkNavigator: RaftLinkNavigator,
    val renderedTimelineSlice: ThreadTimelineSlice,
    val filesViewModel: SharedThreadFilesViewModel?,
    val routeAuthRequiredFn: (DataResult.NoPermission) -> Boolean,
    val actionCardStepUp: SecondFactorStepUpCoordinator,
    val installAppDialogStore: ThreadInstallAppActionDialogStore,
    val actionCardSpecHost: ThreadActionCardSpecHost,
    val openAttachmentCommentsFn: (attachmentId: String) -> Unit,
    val openImagePreviewFn: (group: ThreadImageAttachmentGroup) -> Unit,
    val saveAttachmentExternallyFn: (attachment: RaftThreadAttachmentItem) -> Unit,
    val openAttachmentNoPreviewFn: (attachment: RaftThreadAttachmentItem) -> Boolean,
    val sendPendingLocalMessageFn: (localMessageId: String, context: RaftThreadPendingSendContext, startedFromComposer: Boolean, authority: ThreadLocalSendAuthority) -> Unit,
    val openCanonicalTask: (TaskSummary, String) -> Unit,
    val settingsPageLinkKey: String,
    val threadLimitLocked: Boolean = false,
    val onJointSurface: Boolean = false,
) {
    val renderSlice by renderSliceState
    var pendingReactionKeys by pendingReactionKeysState
    var pendingReactionVisuals by pendingReactionVisualsState
    var pendingActionCardIds by pendingActionCardIdsState
    var pendingThreadFollowMessageIds by pendingThreadFollowMessageIdsState
    var textSelectionMessage by textSelectionMessageState
    var viewContextMessage by viewContextMessageState
    var createAgentDialogState by createAgentDialogStateHolder
    var migrateAgentDialogState by migrateAgentDialogStateHolder
    val actionCardSuccessLabels = labels.actionCardSuccessLabels
    val actionCardFailureLabels = labels.actionCardFailureLabels
    val genericActionCardSuccessLabel = labels.genericActionCardSuccessLabel
    val genericActionCardFailureLabel = labels.genericActionCardFailureLabel
    val actionCardReconfirmFailedLabel = labels.actionCardReconfirmFailedLabel
    val unableToOpenRepliesLabel = labels.unableToOpenRepliesLabel
    val unableToOpenParentMessageLabel = labels.unableToOpenParentMessageLabel
    val unableToOpenTaskLabel = labels.unableToOpenTaskLabel
    val unableToOpenTargetTemplate = labels.unableToOpenTargetTemplate
    val unableToOpenTaskNumberTemplate = labels.unableToOpenTaskNumberTemplate
    val referenceNotFoundTemplate = labels.referenceNotFoundTemplate
    val referenceNoAccessTemplate = labels.referenceNoAccessTemplate
    val referenceOtherServerTemplate = labels.referenceOtherServerTemplate
    val fileSourceUnavailableLabel = labels.fileSourceUnavailableLabel
    val copiedLabel = labels.copiedLabel
    val failureDetailsCopiedLabel = labels.failureDetailsCopiedLabel
    val linksCopiedLabel = labels.linksCopiedLabel
    val forwardCompletedTemplate = labels.forwardCompletedTemplate
    val forwardJoinFirstTemplate = labels.forwardJoinFirstTemplate
    val unableToCopyLinkLabel = labels.unableToCopyLinkLabel
    val noTextToSelectLabel = labels.noTextToSelectLabel
    val followedThreadLabel = labels.followedThreadLabel
    val unableToFollowThreadLabel = labels.unableToFollowThreadLabel
    val unfollowedThreadLabel = labels.unfollowedThreadLabel
    val unableToUnfollowThreadLabel = labels.unableToUnfollowThreadLabel
    val searchUnavailableLabel = labels.searchUnavailableLabel
    val actionUnavailableLabel = labels.actionUnavailableLabel
    val unableToUpdateReactionLabel = labels.unableToUpdateReactionLabel
    val commentSourceUnavailableLabel = labels.commentSourceUnavailableLabel
    val memberNotFoundTemplate = labels.memberNotFoundTemplate
    val memberAddedLabel = labels.memberAddedLabel
    val unableToAddMemberLabel = labels.unableToAddMemberLabel
    val memberRemovedLabel = labels.memberRemovedLabel
    val stillInChannelToastStrings = labels.stillInChannelToastStrings
    val unableToRemoveMemberLabel = labels.unableToRemoveMemberLabel
    val channelArchivedLabel = labels.channelArchivedLabel
    val unableToArchiveChannelLabel = labels.unableToArchiveChannelLabel
    val channelDeletedLabel = labels.channelDeletedLabel
    val unableToDeleteChannelLabel = labels.unableToDeleteChannelLabel
    val unableToCreateTaskLabel = labels.unableToCreateTaskLabel
    val unsupportedTaskStatusLabel = labels.unsupportedTaskStatusLabel
    val unableToUpdateTaskLabel = labels.unableToUpdateTaskLabel
    val unableToJoinChannelLabel = labels.unableToJoinChannelLabel
    val joinedChannelLabel = labels.joinedChannelLabel
    val mergedForwardModeLabel = labels.mergedForwardModeLabel
    val individualForwardModeLabel = labels.individualForwardModeLabel
    val imageSavedLabel = labels.imageSavedLabel
    val unableToExportImageLabel = labels.unableToExportImageLabel
    val unableToForwardLabel = labels.unableToForwardLabel
    val unableToSaveMessageLabel = labels.unableToSaveMessageLabel
    val unableToRemoveSavedMessageLabel = labels.unableToRemoveSavedMessageLabel
    val unableToOpenThreadRefLabel = labels.unableToOpenThreadRefLabel
    fun beginMarkdownReferencePending(actionId: String?): ThreadMarkdownReferencePendingToken? =
        markdownRefOpenStore.begin(actionId)
    fun finishMarkdownReferencePending(token: ThreadMarkdownReferencePendingToken?) {
        markdownRefOpenStore.finish(token)
    }
    fun localizedUnableToOpenTarget(target: String): String =
        unableToOpenTargetTemplate.replace(ThreadLocalizedFeedbackPlaceholder, target)
    fun localizedUnableToOpenTaskNumber(taskNumber: Int): String =
        unableToOpenTaskNumberTemplate.replace(ThreadLocalizedFeedbackPlaceholder, taskNumber.toString())
    /** The toast of a reference that did not open, naming why ([fallback] when no reason applies). */
    fun localizedReferenceFailure(failure: SharedReferenceFailure, target: String, fallback: String): String =
        when (failure) {
            SharedReferenceFailure.NotFound -> referenceNotFoundTemplate
            SharedReferenceFailure.NoAccess -> referenceNoAccessTemplate
            SharedReferenceFailure.OtherServer -> referenceOtherServerTemplate
            SharedReferenceFailure.AuthRequired,
            SharedReferenceFailure.Unavailable -> null
        }?.replace(ThreadLocalizedFeedbackPlaceholder, target) ?: fallback
    fun localizedForwardModeLabel(mode: String?): String =
        if (mode?.trim()?.lowercase() == ThreadForwardSelectionMode.Individual.wireValue) {
            individualForwardModeLabel
        } else {
            mergedForwardModeLabel
        }
    fun localizedForwardCompleted(mode: String, target: String): String =
        forwardCompletedTemplate
            .replace(ThreadLocalizedFeedbackPlaceholder, mode)
            .replace("__RAFT_TARGET__", target)
    fun localizedForwardJoinFirst(target: String): String =
        forwardJoinFirstTemplate.replace(ThreadLocalizedFeedbackPlaceholder, target)
    fun localizedMemberNotFound(target: String): String =
        memberNotFoundTemplate.replace(ThreadLocalizedFeedbackPlaceholder, target)

    fun routeAuthRequired(result: DataResult.NoPermission): Boolean = routeAuthRequiredFn(result)

    fun openAttachmentComments(attachmentId: String): Unit = openAttachmentCommentsFn(attachmentId)

    fun openImagePreview(group: ThreadImageAttachmentGroup): Unit = openImagePreviewFn(group)

    fun saveAttachmentExternally(attachment: RaftThreadAttachmentItem): Unit = saveAttachmentExternallyFn(attachment)

    fun openAttachmentNoPreview(attachment: RaftThreadAttachmentItem): Boolean = openAttachmentNoPreviewFn(attachment)

    fun sendPendingLocalMessage(
        localMessageId: String,
        context: RaftThreadPendingSendContext,
        startedFromComposer: Boolean,
        authority: ThreadLocalSendAuthority
    ): Unit = sendPendingLocalMessageFn(localMessageId, context, startedFromComposer, authority)

                // An in-app open of a Raft web link target (a permalink, a spec card's channel).
                fun openThreadWebLinkTargetInApp(target: RaftThreadWebLinkTarget) {
                    val serverId = currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
                    val directory =
                        listOfNotNull(
                            serverId?.let { conversationIdentityProvider?.resolveProjection(it, target.channelId) }
                        ).toSharedConversationDirectoryState()
                    val request =
                        target.resolveMarkdownOpenRequest(
                            serverId = serverId?.value.orEmpty(),
                            conversationDirectory = directory
                        )
                    val loadingPayload = request?.let { openRequest ->
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
                            "thread_markdown_open_rejected reason=identity_unresolved " +
                                "channelId=${target.channelId} parentMessageId=${target.threadParentMessageId.orEmpty()}"
                        )
                        bridge.toast(localizedUnableToOpenTarget(target.channelId.take(32)))
                        return
                    }
                    bridge.log(
                        "thread_route_open action=markdown_url routeKind=${target.routeKind} " +
                            "targetChannelId=${target.channelId} messageId=${target.messageId.orEmpty()} " +
                            "threadParentMessageId=${target.threadParentMessageId.orEmpty()} " +
                            "targetThreadId=${loadingPayload.sourceThreadId.orEmpty()} " +
                            "parentMessageId=${loadingPayload.parentMessageId.orEmpty()} " +
                            "focusedMessageId=${loadingPayload.focusedMessageId.orEmpty()} " +
                            "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                    )
                    onOpenThreadPayload(loadingPayload)
                }

    fun handle(
        action: String,
        itemId: String?,
        attachmentId: String?,
        tab: String?,
        markdownActionId: String?,
        eventThreadChannelId: String?,
    ): Unit = run ThreadPageContent@{
                if (action == ThreadOpenPlanBillingEvent) {
                    bridge.log("thread_history_paywall_open_billing offersUpgrade=${settingsBillingOffersUpgrade()}")
                    // Upgrade to Pro: the upgrade dialog for the 30-day window (web HistoryPaywall
                    // `history_30d`, with conversation context so the owner sees how many older
                    // messages hide here and how often agents ran into the window in the past 7 days).
                    // iOS view-only Billing: "View your plan", the view-only page.
                    if (settingsBillingOffersUpgrade()) {
                        val hidden =
                            renderedTimelineSlice.historyChannelId?.let { channelId ->
                                build.raft.app.data.RaftHiddenHistoryStore.byChannel.value[channelId]
                            }
                        val loungeChannelId = renderedThread.agentLoungeLookupChannelId()
                        val isAgentLounge =
                            loungeChannelId != null &&
                                currentServerId != null &&
                                RaftAgentLounges.lounge(currentServerId, loungeChannelId) != null
                        val historyContext =
                            threadHistoryLimitContext(
                                scope = renderedTimelineSlice.historyPaywallScope(),
                                hidden = hidden,
                                channelName = renderedThread.title,
                                isAgentLounge = isAgentLounge,
                            )
                        pager.openRaftUpgradeDialog(
                            gate = RaftUpgradeGate.History30d,
                            storeScope = storeScope,
                            historyContext = historyContext,
                        )
                    } else {
                        pager.openSettingsBillingPage(storeScope)
                    }
                    return@ThreadPageContent
                }
                if (action == ThreadOpenUpgradeEvent) {
                    // A banner's Upgrade (web ChannelLimitBanners): [itemId] is the gate's wire
                    // name, and [attachmentId] carries the optional ISO freeze deadline for
                    // `ExternalFreezeNotice` (`FreezeUpcoming`).
                    RaftUpgradeGate.entries.firstOrNull { it.wire == itemId }?.let { gate ->
                        val externalContext =
                            attachmentId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(RaftUpgradeExternalContext::FreezeUpcoming)
                        pager.openRaftUpgradeDialog(gate, storeScope, externalContext)
                    }
                    return@ThreadPageContent
                }
                if (handleThreadStoreRetryEvent(action, threadViewModel::dispatch)) {
                    return@ThreadPageContent
                }
                if (!threadEventAllowed(renderSlice.writeActionsEnabled, action, limitLocked = threadLimitLocked)) {
                    bridge.log("thread_write_action_blocked action=$action reason=${if (threadLimitLocked) "channel_limit_locked" else "read_only_access_fallback"}")
                    return@ThreadPageContent
                }
                when {
                    action == "openAttachment" && !attachmentId.isNullOrBlank() -> {
                        handleOpenAttachment(attachmentId)
                    }
                    action == "downloadAttachment" && !attachmentId.isNullOrBlank() -> {
                        handleDownloadAttachment(attachmentId)
                    }
                    action == "openFileSource" && !itemId.isNullOrBlank() -> {
                        handleOpenFileSource(itemId)
                    }
                    action == ThreadOpenUnreadThreadPastHistoryAction && !itemId.isNullOrBlank() &&
                        !eventThreadChannelId.isNullOrBlank() -> {
                        handleOpenUnreadThreadPastHistory(itemId, eventThreadChannelId)
                    }
                    action == ThreadJumpToUnreadThreadPastHistoryAction && !itemId.isNullOrBlank() &&
                        !eventThreadChannelId.isNullOrBlank() -> {
                        handleJumpToUnreadThreadPastHistory(itemId, eventThreadChannelId)
                    }
                    action == ThreadJumpToUnreadThreadParentAction && !itemId.isNullOrBlank() -> {
                        handleJumpToUnreadThreadParent(itemId, eventThreadChannelId)
                    }
                    (action == ThreadOutlineJumpAction || action == ThreadFindJumpAction) && !itemId.isNullOrBlank() -> {
                        handleOutlineOrFindJump(itemId)
                    }
                    action == "openAttachmentComments" && !attachmentId.isNullOrBlank() -> {
                        handleOpenAttachmentComments(attachmentId)
                    }
                    action == "copyMessage" && !itemId.isNullOrBlank() -> {
                    handleCopyMessage(itemId)
                }
                    action == "copyLocalSendFailureDetails" && !itemId.isNullOrBlank() -> {
                        handleCopyLocalSendFailureDetails(itemId)
                    }
                action == "copyShareSelectionMarkdown" && !itemId.isNullOrBlank() -> {
                    handleCopyShareSelectionMarkdown(itemId)
                }
                action == "copyShareSelectionLinks" && !itemId.isNullOrBlank() -> {
                    handleCopyShareSelectionLinks(itemId)
                }
                action == "shareSelectionImageSaved" -> {
                    handleShareSelectionImageSaved()
                }
                action == "shareSelectionExportFailed" -> {
                    handleShareSelectionExportFailed()
                }
                action == "forwardMessagesSent" -> {
                    handleForwardMessagesSent(itemId, tab)
                }
                action == "forwardMessagesFailed" -> {
                    handleForwardMessagesFailed(itemId, traceId = tab)
                }
                action == "forwardJoinConfirmationRequired" -> {
                    handleForwardJoinConfirmationRequired(itemId)
                }
                action == "copyMessageLink" && !itemId.isNullOrBlank() -> {
                    handleCopyMessageLink(itemId)
                }
                action == "openSelectMessageText" && !itemId.isNullOrBlank() -> {
                    handleOpenSelectMessageText(itemId)
                }
                action == "openViewContext" && !itemId.isNullOrBlank() -> {
                    handleOpenViewContext(itemId)
                }
                action == "openMessageThread" && !itemId.isNullOrBlank() -> {
                    handleOpenMessageThread(itemId)
                }
                action == "followMessageThread" && !itemId.isNullOrBlank() -> {
                    handleFollowMessageThread(itemId)
                }
                action == "unfollowMessageThread" && !itemId.isNullOrBlank() && !attachmentId.isNullOrBlank() -> {
                    handleUnfollowMessageThread(itemId, attachmentId)
                }
                action == MARK_THREAD_UNREAD_ACTION && !attachmentId.isNullOrBlank() -> {
                    handleMarkThreadUnread(attachmentId)
                }
                action == "openParentMessage" -> {
                    handleOpenParentMessage()
                }
                action == "openChannelSearch" -> {
                    handleOpenChannelSearch()
                }
                action == "toggleMessageSaved" && !itemId.isNullOrBlank() -> {
                    handleToggleMessageSaved(itemId)
                }
                action == "commitActionCard" && !itemId.isNullOrBlank() -> {
                    handleCommitActionCard(itemId)
                }
                action == "toggleReaction" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    handleToggleReaction(itemId, tab)
                }
                action == "openSourceTask" && !itemId.isNullOrBlank() -> {
                    handleOpenSourceTask(itemId)
                }
                action == "openTask" && !itemId.isNullOrBlank() -> {
                    handleOpenTask(itemId, markdownActionId)
                }
                action == "openMarkdownTaskNumber" && !itemId.isNullOrBlank() -> {
                    handleOpenMarkdownTaskNumber(itemId, markdownActionId)
                }
                action == "openCommentHost" && !itemId.isNullOrBlank() -> {
                    handleOpenCommentHost(itemId)
                }
                action == "openMarkdownUrl" && !itemId.isNullOrBlank() -> {
                    handleOpenMarkdownUrl(itemId)
                }
                // A4: a spec card's navigate to a channel, as a channel link opens it.
                action == "openActionCardChannel" && !itemId.isNullOrBlank() ->
                    handleOpenActionCardChannel(itemId)
                action == "openMarkdownTag" && !itemId.isNullOrBlank() -> {
                    handleOpenMarkdownTag(itemId)
                }
                (action == "openMarkdownChannel" || action == "openMarkdownDirectMessage" || action == "openMarkdownLounge") &&
                    !itemId.isNullOrBlank() -> {
                    handleOpenMarkdownChannel(action, itemId, markdownActionId)
                }
                (
                    action == "openMarkdownThreadRef" ||
                        action == "openMarkdownDirectMessageThreadRef" ||
                        action == "openMarkdownMessageRef" ||
                        action == "openMarkdownLoungeThreadRef"
                ) &&
                    !itemId.isNullOrBlank() -> {
                    handleOpenMarkdownRef(action, itemId, tab, markdownActionId)
                }
                action == "submitAddMember" && !itemId.isNullOrBlank() -> {
                    handleSubmitAddMember(itemId, tab)
                }
                action == "removeMember" && !itemId.isNullOrBlank() -> {
                    handleRemoveMember(itemId)
                }
                action == "settingsAction" && itemId == "archive" -> {
                    handleArchiveChannel()
                }
                action == "settingsAction" && itemId == "delete" -> {
                    handleDeleteChannel()
                }
                action == "convertMessageToTask" && !itemId.isNullOrBlank() -> {
                    handleCreateTaskFromMessage(itemId)
                }
                action == "setTaskStatus" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    handleSetTaskStatus(itemId, tab)
                }
                // A move into Backlog with the reason asked for it (web TaskBacklogReasonPopover);
                // tab carries the reason, "" for none.
                action == "moveTaskToBacklog" && !itemId.isNullOrBlank() -> {
                    handleMoveTaskToBacklog(itemId, tab)
                }
                // Task #258: inline task detail panel assignee row. tab carries the
                // assignee filter id ("unassigned" sentinel or member filter id);
                // resolve against the same current-channel options rendered by the picker.
                action == "setTaskAssignee" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    handleSetTaskAssignee(itemId, tab)
                }
                action == "markTaskDone" && !itemId.isNullOrBlank() -> {
                    handleMarkTaskDone(itemId)
                }
                action == "reopenTask" && !itemId.isNullOrBlank() -> {
                    handleReopenTask(itemId)
                }
                action == "joinChannel" -> {
                    handleJoinChannel()
                }
                action == "selectTab" && !tab.isNullOrBlank() -> {
                    handleSelectTab(tab)
                }
                action == "openMembersPager" -> {
                    handleOpenMembersPager(itemId)
                }
                action == "openChannelSettings" -> {
                    handleOpenChannelSettings()
                }
                action == "loadMembers" -> {
                    handleLoadMembers()
                }
                action == "loadOlder" -> {
                    handleLoadOlder()
                }
                action == "loadNewer" -> {
                    handleLoadNewer()
                }
                action == "deleteLocalFailedSend" && !itemId.isNullOrBlank() -> {
                    handleDeleteLocalFailedSend(itemId)
                }
                action == "cancelLocalSend" && !itemId.isNullOrBlank() -> {
                    handleCancelLocalSend(itemId)
                }
                action == "retryLocalSend" && !itemId.isNullOrBlank() -> {
                    handleRetryLocalSend(itemId)
                }
                else -> {
                    handleUnsupportedEvent(action, itemId, attachmentId, tab)
                }
                }
    }

    private fun handleOpenAttachment(attachmentId: String) {
                        val imageGroup =
                            renderedTimelineSlice.findThreadImageAttachmentGroup(attachmentId)
                        if (imageGroup != null) {
                            openImagePreview(imageGroup)
                        } else {
                            val attachment =
                                renderedTimelineSlice.findThreadAttachment(attachmentId)
                                    ?: RaftThreadAttachmentItem(id = attachmentId, name = "Attachment")
                            openAttachmentNoPreview(attachment)
                        }
    }

    private fun handleDownloadAttachment(attachmentId: String) {
                        val attachment =
                            renderedTimelineSlice.findThreadAttachment(attachmentId)
                                ?: RaftThreadAttachmentItem(id = attachmentId, name = "Attachment")
                        saveAttachmentExternally(attachment)
    }

    private fun handleOpenFileSource(itemId: String): Unit = run ThreadPageContent@{
                        val fileItem =
                            filesViewModel?.state?.value?.payload?.items?.firstOrNull { it.attachmentId == itemId }
                        val sourceMessageId = fileItem?.threadFileSourceFocusMessageId()
                        if (sourceMessageId == null) {
                            bridge.toast(fileSourceUnavailableLabel)
                            return@ThreadPageContent
                        }
                        // "Jump to Original Message": the file's message is often outside the loaded
                        // window; a jump loads around it first (the page shows the load) and focuses it.
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = sourceMessageId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                                unreadThreadPill = false,
                            )
                        )
    }

    private fun handleOpenUnreadThreadPastHistory(itemId: String, eventThreadChannelId: String) {
                        // Web #11494: the parent is past the plan's history window (the channel shows
                        // the paywall there) but replies are inside it: open the thread, which reads it.
                        // Its parent is not loaded, so nothing seeds the reply page; it loads canonically.
                        bridge.log(
                            "thread_unread_thread_jump_open_past_history parent=${itemId.take(8)} " +
                                "thread=${eventThreadChannelId.take(8)}"
                        )
                        sharedHomeStore?.dispatch(SharedHomeIntent.MarkFollowedThreadRead(eventThreadChannelId))
                        onOpenThreadPayload(
                            renderedThread.replyThreadLoadingPayload(
                                parentMessageId = itemId,
                                parentChannelId = renderedThread.replyParentChannelIdForNestedOpen(),
                                threadChannelId = eventThreadChannelId,
                            )
                        )
    }

    private fun handleJumpToUnreadThreadPastHistory(itemId: String, eventThreadChannelId: String) {
                        // Web #11494: the parent and its replies are past the window; the jump lands on
                        // the paywall, where the thread can't be opened, so read it now: the count drops
                        // and the next tap moves on instead of staying on this thread.
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = itemId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                            )
                        )
                        sharedHomeStore?.dispatch(SharedHomeIntent.MarkFollowedThreadRead(eventThreadChannelId))
    }

    private fun handleJumpToUnreadThreadParent(itemId: String, eventThreadChannelId: String?) {
                        // The unread-thread pill: focus (scroll to and highlight) the parent
                        // message through the route focus session, as a search hit does; a parent
                        // that is not loaded is loaded around first (web jumpToMessage). The target
                        // thread's replies are then prewarmed so opening it renders at once (artin).
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = itemId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                                prewarmThreadChannelId = eventThreadChannelId,
                            )
                        )
    }

    private fun handleOutlineOrFindJump(itemId: String) {
                        // The thread timeline (botiverse/slock #13534), and Search in this thread's
                        // match the page hasn't loaded (web useThreadReplyContext): focus the message
                        // as a search hit does, loading around it first when it isn't loaded; top-aligned.
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = itemId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                                unreadThreadPill = false,
                            )
                        )
    }

    private fun handleOpenAttachmentComments(attachmentId: String) {
                        openAttachmentComments(attachmentId)
    }

    private fun handleCopyMessage(itemId: String) {
                    val body = renderedTimelineSlice.threadMessageById(itemId)?.body.orEmpty()
                    // Web MessageItem Copy Markdown: the menu closes, no toast (artin 2026-11-02).
                    if (body.isNotBlank()) bridge.copyToPasteboard(body)
    }

    private fun handleCopyLocalSendFailureDetails(itemId: String) {
                        val failedMessage = renderedTimelineSlice.threadMessageById(itemId)
                        val detail = failedMessage?.let {
                            threadFailureDiagnosticDetail(it.pendingSendErrorCode, it.pendingSendErrorMessage)
                        }
                        if (!detail.isNullOrBlank()) {
                            bridge.copyToPasteboard(detail)
                            bridge.toast(failureDetailsCopiedLabel)
                        }
    }

    private fun handleCopyShareSelectionMarkdown(itemId: String) {
                    bridge.copyToPasteboard(itemId)
                    bridge.briefToast(copiedLabel)
    }

    private fun handleCopyShareSelectionLinks(itemId: String) {
                    bridge.copyToPasteboard(itemId)
                    bridge.toast(linksCopiedLabel)
    }

    private fun handleShareSelectionImageSaved() {
                    bridge.toast(imageSavedLabel)
    }

    private fun handleShareSelectionExportFailed() {
                    bridge.toast(unableToExportImageLabel)
    }

    private fun handleForwardMessagesSent(itemId: String?, tab: String?) {
                    val target = itemId?.takeIf { it.isNotBlank() }
                    bridge.toast(
                        if (target != null) {
                            localizedForwardCompleted(localizedForwardModeLabel(tab), target)
                        } else {
                            Res.string.thread_forward_badge.desc().resolve()
                        }
                    )
    }

    private fun handleForwardMessagesFailed(itemId: String?, traceId: String?) {
                    // The event carries a failure code; the toast says it in this locale and only
                    // the log keeps the code itself.
                    val failure = threadForwardFailure(itemId)
                    if (failure == ThreadForwardFailure.Generic) {
                        bridge.log("thread_forward_failed code=${itemId.orEmpty()}")
                    }
                    bridge.errorToast(failure.messageRes.desc().resolve(), traceId)
    }

    private fun handleForwardJoinConfirmationRequired(itemId: String?) {
                    bridge.toast(
                        itemId?.takeIf { it.isNotBlank() }?.let { localizedForwardJoinFirst(it) } ?: unableToForwardLabel
                    )
    }

    private fun handleCopyMessageLink(itemId: String) {
                    val message =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    val permalink =
                        message?.let {
                            renderedThread.threadMessagePermalink(
                                message = it,
                                serverSlug = currentServerSlug
                            )
                        }
                    if (permalink != null) {
                        // Web MessageItem Copy Link: no toast.
                        bridge.copyToPasteboard(permalink)
                    } else {
                        bridge.toast(unableToCopyLinkLabel)
                    }
    }

    private fun handleOpenSelectMessageText(itemId: String) {
                    val message = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (message?.body.isNullOrBlank()) {
                        bridge.toast(noTextToSelectLabel)
                    } else {
                        textSelectionMessage = message
                    }
    }

    private fun handleOpenViewContext(itemId: String) {
                    val message = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (message != null && threadMessageSupportsContextView(message, onJointSurface = onJointSurface)) {
                        viewContextMessage = message
                    }
    }

    private fun handleOpenMessageThread(itemId: String): Unit = run ThreadPageContent@{
                    if (threadMessageOpenIgnoredForSurface(renderedThread.surfaceKind, renderedThread.channelKind)) {
                        bridge.log(
                            "thread_reply_open result=ignored_already_in_reply_thread " +
                                "messageId=$itemId instance=${renderedThread.instanceId}"
                        )
                        return@ThreadPageContent
                    }
                    val parentMessage =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (parentMessage == null) {
                        bridge.toast(unableToOpenRepliesLabel)
                        return@ThreadPageContent
                    }
                    val parentReplyMetadata = replyMetadataByParentId.threadReplyMetadataFor(parentMessage)
                    val parentChannelId = renderedThread.replyParentChannelIdForNestedOpen()
                    val provenance =
                        dataSource.replyCacheProvenanceState.value
                            .currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
                    if (
                        provenance == null ||
                        !dataSource.rememberReplyParentCandidate(
                            provenance = provenance,
                            serverId = currentServerId,
                            parentChannelId = parentChannelId,
                            parentMessage = parentMessage,
                            threadChannelId = parentReplyMetadata.threadChannelId,
                            replyCount = parentReplyMetadata.replyCount
                        )
                    ) {
                        bridge.logWarn("thread_reply_parent_seed_rejected reason=stale_provenance")
                        bridge.toast(unableToOpenRepliesLabel)
                        return@ThreadPageContent
                    }
                    val loadingPayload =
                        renderedThread.replyThreadLoadingPayload(
                            parentMessageId = parentMessage.id,
                            parentChannelId = parentChannelId,
                            threadChannelId = parentReplyMetadata.threadChannelId,
                            focusedMessageId = parentReplyMetadata.firstUnreadReplyMessageId
                        )
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyReplyThreadRead(
                            parentMessageId = parentMessage.id,
                            threadChannelId = parentReplyMetadata.threadChannelId
                        )
                    )
                    parentReplyMetadata.threadChannelId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { threadChannelId ->
                            coroutineScope.launch {
                                // Opening the whole reply scope clears the whole inline unread projection.
                                // The bounded preview cannot prove the authoritative latest seq (newer system
                                // rows may be intentionally hidden), so dispatch the canonical read-all intent.
                                val result = dataSource.markVisibleChannelRead(threadChannelId, latestSeq = null)
                                bridge.log(
                                    "thread_inline_reply_read_dispatch channelId=$threadChannelId " +
                                        "target=all result=${result::class.simpleName.orEmpty()}"
                                )
                            }
                        }
                    bridge.log(
                        "thread_reply_open action=message_chip parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                            "parentMessageId=${parentMessage.id} replyCount=${parentReplyMetadata.replyCount} " +
                            "unreadReplyCount=${parentReplyMetadata.unreadReplyCount} " +
                            "knownThreadChannelId=${parentReplyMetadata.threadChannelId.orEmpty()} " +
                            "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                    )
                    onOpenThreadPayload(loadingPayload)
    }

    private fun handleFollowMessageThread(itemId: String): Unit = run ThreadPageContent@{
                    if (itemId in pendingThreadFollowMessageIds) {
                        return@ThreadPageContent
                    }
                    pendingThreadFollowMessageIds = pendingThreadFollowMessageIds + itemId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.followThread(MessageId(itemId))) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyReplyThreadRead(
                                            parentMessageId = result.data.parentMessageId?.value ?: itemId,
                                            threadChannelId = result.data.threadChannelId.value
                                        )
                                    )
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageThreadFollowed(
                                            parentMessageId = result.data.parentMessageId?.value ?: itemId,
                                            threadChannelId = result.data.threadChannelId.value,
                                            followed = true,
                                            replyCount = result.data.replyCount
                                        )
                                    )
                                    // #proj-chat:#44 & slock #16474: following a thread in a Lounge lists it locally without waiting for GET /channels/dm.
                                    currentServerId?.let { serverId ->
                                        val parentChannelId =
                                            renderedThread.agentLoungeLookupChannelId()
                                                ?: renderedThread.parentChannelId?.takeIf { it.isNotBlank() }
                                                ?: renderedThread.sourceThreadId?.takeIf {
                                                    renderedThread.surfaceKind == RaftThreadSurfaceKind.Channel && it.isNotBlank()
                                                }
                                        renderedThread.agentLoungeLookupChannelId()?.let { loungeChannelId ->
                                            RaftAgentLounges.markListed(serverId, loungeChannelId)
                                        }
                                        sharedHomeStore?.dispatch(
                                            SharedHomeIntent.NoteLoungeThreadFollowed(
                                                serverId = serverId,
                                                parentChannelId = parentChannelId,
                                                threadChannelId = result.data.threadChannelId.value,
                                            )
                                        )
                                    }
                                    bridge.toast(followedThreadLabel)
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.errorToast(unableToFollowThreadLabel, result)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.errorToast(unableToFollowThreadLabel, result.blocker)
                                }
                            }
                        } finally {
                            pendingThreadFollowMessageIds = pendingThreadFollowMessageIds - itemId
                        }
                    }
    }

    private fun handleUnfollowMessageThread(itemId: String, attachmentId: String): Unit = run ThreadPageContent@{
                    val threadChannelId = attachmentId
                    if (threadChannelId in pendingThreadFollowMessageIds) {
                        return@ThreadPageContent
                    }
                    pendingThreadFollowMessageIds = pendingThreadFollowMessageIds + threadChannelId
                    coroutineScope.launch {
                        try {
                            // Web #11687 & slock #16474: the thread's unread leaves Home at once, back if this fails;
                            // confirming an unfollow in a Lounge also refreshes the DM list's `listed` state.
                            val parentChannelId =
                                renderedThread.agentLoungeLookupChannelId()
                                    ?: renderedThread.parentChannelId?.takeIf { it.isNotBlank() }
                                    ?: renderedThread.sourceThreadId?.takeIf {
                                        renderedThread.surfaceKind == RaftThreadSurfaceKind.Channel && it.isNotBlank()
                                    }
                            val unfollowResult =
                                unfollowThreadProjectingHome(
                                    ChannelId(threadChannelId),
                                    project = { intent -> sharedHomeStore?.dispatch(intent) },
                                    unfollow = dataSource::unfollowThread,
                                    parentChannelId = parentChannelId,
                                )
                            when (val result = unfollowResult) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageThreadFollowed(
                                            parentMessageId = itemId,
                                            threadChannelId = threadChannelId,
                                            followed = false
                                        )
                                    )
                                    bridge.toast(unfollowedThreadLabel)
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.errorToast(unableToUnfollowThreadLabel, result)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.errorToast(unableToUnfollowThreadLabel, result.blocker)
                                }
                            }
                        } finally {
                            pendingThreadFollowMessageIds = pendingThreadFollowMessageIds - threadChannelId
                        }
                    }
    }

    private fun handleMarkThreadUnread(attachmentId: String) {
                    // botiverse/slock #11687: the reads of this page are held for it until it leaves
                    // (#14222: a followed thread marked from a channel's list is written, not held).
                    threadViewModel.dispatch(SharedThreadIntent.MarkThreadUnread(attachmentId))
    }

    private fun handleOpenParentMessage() {
                    val sideParentFocus =
                        adaptiveCloseRequest?.let { source ->
                            adaptiveFocusWorkspaceStore.snapshot().sideThreadParentFocus(source)
                        }
                    if (sideParentFocus != null) {
                        adaptiveFocusManager.clearFocus(force = true)
                        adaptiveKeyboardController?.hide()
                        // The original, its history start (behind the window), or nothing (deleted): ThreadHeaderOriginalTitle.kt.
                        postSideThreadParentFocus(sideParentFocus, renderedThread, bridge, eventBus)
                    } else if (!onOpenParentThread(renderedThread, currentSession?.currentServerId)) {
                        bridge.logWarn(
                            "thread_parent_navigation_rejected reason=parent_conversation_unresolved " +
                                "threadId=${renderedThread.sourceThreadId.orEmpty()} " +
                                "parentChannelId=${renderedThread.parentChannelId.orEmpty()}"
                        )
                        bridge.toast(unableToOpenParentMessageLabel)
                    }
    }

    private fun handleOpenChannelSearch() {
                    if (!pager.openThreadChannelSearch(renderedThread)) {
                        bridge.toast(searchUnavailableLabel)
                    }
    }

    private fun handleToggleMessageSaved(itemId: String) {
                    val previousSaved =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)?.isSaved
                            ?: false
                    val nextSaved = !previousSaved
                    threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, nextSaved))
                    val savedOwnerSession = currentSession
                    coroutineScope.launch {
                        val unsaveOwnerSession =
                            if (!nextSaved) savedOwnerSession ?: dataSource.readSession() else null
                        val result =
                            if (nextSaved) {
                                dataSource.saveMessage(itemId)
                            } else {
                                dataSource.unsaveMessage(itemId)
                            }
                        when (result) {
                            is DataResult.Content -> {
                                // Activity's Saved filter reloads on an accepted save or unsave.
                                sharedStores.homeSavedStateCache.recordSavedWrite()
                                // A confirmed unsave leaves the Saved list's stored copy (a save shows up
                                // with the list's next refresh: the row needs the server's saved entry).
                                if (!nextSaved) {
                                    val savedServerId =
                                        unsaveOwnerSession?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
                                    if (unsaveOwnerSession != null && savedServerId != null) {
                                        sharedStores.homeSavedStateCache.recordMessageUnsaved(
                                            serverId = savedServerId,
                                            userId = unsaveOwnerSession.user.id,
                                            messageId = MessageId(itemId),
                                        )
                                    }
                                }
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, previousSaved))
                                bridge.errorToast(if (nextSaved) unableToSaveMessageLabel else unableToRemoveSavedMessageLabel, result)
                            }
                            is DataResult.NoPermission -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, previousSaved))
                                if (routeAuthRequired(result)) return@launch
                                bridge.errorToast(if (nextSaved) unableToSaveMessageLabel else unableToRemoveSavedMessageLabel, result.blocker)
                            }
                        }
                    }
    }
}
