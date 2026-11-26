package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.Pager
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * One tab's body of the thread page (Chat / Tasks / Files), or the access / loading state in its
 * place. Behind ThreadPageContentContent's `renderThreadTabContent` lambda, so the scaffold slots
 * are unchanged.
 *
 * [NonRestartableComposable]: it used to be that lambda's body, never skipped on its own and with
 * its state reads invalidating the lambda's scope. It keeps that: no restart scope or skipping of
 * its own. Its body remembers (Select to here), reads composition locals and logs from callbacks.
 * The `by` states come in as [State]/[MutableState] objects, so each read still happens where it
 * did (e.g. inside the CompositionLocalProvider content) and writes reach the parent's state.
 */
@Composable
@NonRestartableComposable
internal fun ThreadTabContent(
    tab: RaftThreadTab,
    contentModifier: Modifier,
    tabActive: Boolean,
    data: ThreadPageContentData,
    callbacks: ThreadPageContentCallbacks,
    payload: RaftThreadScreenPayload,
    markdownTagResolution: ThreadMarkdownTagResolution,
    activeModalState: MutableState<String?>,
    composerKeyboardInsetDpState: State<Float>,
    insertMentionRequestState: MutableState<ThreadComposerInsertMentionRequest?>,
    insertMentionRequestNonceState: MutableState<Int>,
    selectedShareMessageIdsState: MutableState<Set<String>>,
    shareCaptureAnchorBeforeTargetState: State<Boolean>,
    shareCaptureModeState: State<Boolean>,
    shareCaptureScrollOffsetPxState: State<Int>,
    shareCaptureTailPaddingActiveState: State<Boolean>,
    shareCaptureTargetMessageIdState: State<String?>,
    shareSelectionAnchorIdState: MutableState<String?>,
    shareSelectionModeState: MutableState<Boolean>,
    threadHeaderScrollToTopRequestState: State<Int>,
    accessErrorLabels: ThreadUserFacingErrorLabels,
    draftProjection: SharedThreadComposerDraftProjection,
    inlineReplyParticipantsById: Map<String, RaftThreadMemberItem>,
    inlineSearchVisible: Boolean,
    openForwardPickerForMessageIds: (Set<String>) -> Unit,
    renderAccessRetry: Boolean,
    renderEarlyLoadingShell: Boolean,
    selectableShareMessageIds: Set<String>,
    selectedShareBounds: MutableMap<String, ThreadShareCaptureRect>,
    selectedShareMessages: List<RaftThreadMessageItem>,
    shareSelectionActive: Boolean,
    showComposerForSelectedTab: Boolean,
    sideCards: ConversationSideCardsState,
    sideCardsBeside: Boolean,
    taskPanelChannelId: ChannelId,
    threadScreenPagerForForwardBundle: Pager,
    timelineRenderConfig: ThreadTimelineRenderConfig,
    writeActionsEnabled: Boolean = data.writeActionsEnabled,
) {
    // Everything below is what the `renderThreadTabContent` lambda captured from ThreadPageContentContent.
    val threadInstanceKey = data.threadInstanceKey
    val routeActive = data.routeActive
    val hostForeground = data.hostForeground
    val messageBodyTextStyle = data.messageBodyTextStyle
    val timelineSlice = data.timelineSlice
    val accessResolution = data.accessResolution
    val canonicalAccessResolved = data.canonicalAccessResolved
    val replyMetadataByParentId = data.replyMetadataByParentId
    val composerDraftPersistSlice = data.composerDraftPersistSlice
    val resolvedAuxiliaryThreadId = data.resolvedAuxiliaryThreadId
    val tasksViewModel = data.tasksViewModel
    val taskIdentityOptions = data.taskIdentityOptions
    val linkedTasksProjection = data.linkedTasksProjection
    val filesViewModel = data.filesViewModel
    val selectedTab = data.selectedTab
    val forwardServerId = data.forwardServerId
    val contentExtrasVisible = data.contentExtrasVisible
    val highlightedMentions = data.highlightedMentions
    val pendingActionCardIds = data.pendingActionCardIds
    val attachmentOpenStates = data.attachmentOpenStates
    val longMessageCollapseEnabled = data.longMessageCollapseEnabled
    val jumpInputs = data.jumpInputs
    val newMessageMenuEnabled = data.newMessageMenuEnabled
    val currentServerSlug = data.currentServerSlug
    val translationStore = data.translationStore
    val visibleReadTailIndex = data.visibleReadTailIndex
    val adaptiveCloseRequest = data.adaptiveCloseRequest
    val adaptiveStoreScope = data.adaptiveStoreScope
    val adaptiveEventBus = data.adaptiveEventBus
    val onThreadEvent = data.onThreadEvent
    val onOpenTaskSummary = callbacks.onOpenTaskSummary
    val onTasksIntent = callbacks.onTasksIntent
    val onFilesAuthRequired = callbacks.onFilesAuthRequired
    val onOpenFilesAttachment = callbacks.onOpenFilesAttachment
    val onDownloadFilesAttachment = callbacks.onDownloadFilesAttachment
    val onOpenMemberProfile = callbacks.onOpenMemberProfile
    val onExternalAuthor = callbacks.onExternalAuthor
    val agentStatusResolver = callbacks.agentStatusResolver
    val memberAvatarResolver = callbacks.memberAvatarResolver
    val memberModelLabelResolver = callbacks.memberModelLabelResolver
    val diagnosticsLogger = callbacks.diagnosticsLogger
    val onComposerLayoutProbe = callbacks.onComposerLayoutProbe
    val onComposerPixelProbe = callbacks.onComposerPixelProbe
    val mentionHandleResolver = callbacks.mentionHandleResolver
    val onFirstContentReady = callbacks.onFirstContentReady
    val onVisibleReadTailChange = callbacks.onVisibleReadTailChange
    val onViewportFollowsLatestChange = callbacks.onViewportFollowsLatestChange
    val onLoadNewer = callbacks.onLoadNewer
    val onJumpToLatest = callbacks.onJumpToLatest
    val activeMarkdownTagKey = markdownTagResolution.activeMarkdownTagKey
    val openMarkdownTag = markdownTagResolution.openMarkdownTag
    val openMarkdownUrl = markdownTagResolution.openMarkdownUrl
    val isMarkdownTagValid = markdownTagResolution.isMarkdownTagValid
    val markdownTagContextSignature = markdownTagResolution.markdownTagContextSignature
    val resolveMarkdownTagLabel = markdownTagResolution.resolveMarkdownTagLabel
    var activeModal by activeModalState
    val composerKeyboardInsetDp by composerKeyboardInsetDpState
    var insertMentionRequest by insertMentionRequestState
    var insertMentionRequestNonce by insertMentionRequestNonceState
    var selectedShareMessageIds by selectedShareMessageIdsState
    val shareCaptureAnchorBeforeTarget by shareCaptureAnchorBeforeTargetState
    val shareCaptureMode by shareCaptureModeState
    val shareCaptureScrollOffsetPx by shareCaptureScrollOffsetPxState
    val shareCaptureTailPaddingActive by shareCaptureTailPaddingActiveState
    val shareCaptureTargetMessageId by shareCaptureTargetMessageIdState
    var shareSelectionAnchorId by shareSelectionAnchorIdState
    var shareSelectionMode by shareSelectionModeState
    val threadHeaderScrollToTopRequest by threadHeaderScrollToTopRequestState
    Box(
        modifier = contentModifier
            .fillMaxWidth()
            .background(RaftColor.SurfaceWhite)
    ) {
        val unavailableNav = LocalConversationUnavailableNav.current
        if (renderAccessRetry && unavailableNav != null && isConversationUnavailableErrorCode(payload.errorCode)) {
            RaftConversationUnavailableState(wide = unavailableNav.wide, onBack = unavailableNav.onBack)
        } else if (renderAccessRetry) {
            ThreadStateActionMessage(
                title = stringResource(Res.string.thread_error_load),
                detail =
                    accessErrorLabels.forErrorCode(payload.errorCode)
                        ?: stringResource(Res.string.thread_error_access_resolution),
                actionLabel = stringResource(Res.string.common_retry),
                onClick = {
                    onThreadEvent(
                        threadRetryAction(canonicalAccessResolved),
                        null,
                        null,
                        null
                    )
                },
                traceId = payload.errorTraceId
            )
        } else if (accessResolution == ThreadAccessResolution.NoPermission) {
            ThreadStateMessage(
                if (payload.errorServerMessage.isEdgeBlockedMarker()) {
                    stringResource(Res.string.thread_state_edge_blocked)
                } else {
                    stringResource(Res.string.thread_state_no_permission)
                },
                if (payload.errorServerMessage.isEdgeBlockedMarker()) {
                    stringResource(Res.string.error_edge_blocked)
                } else {
                    stringResource(Res.string.thread_error_no_permission)
                },
                traceId = payload.errorTraceId
            )
        } else if (renderEarlyLoadingShell) {
            if (timelineSlice.loadingIndicatorRevealed) {
                ThreadLoadingState()
            }
        } else when (tab) {
            RaftThreadTab.Chat ->
                // Rows drop viewer-only previews while "Generate image" rasterizes them.
                CompositionLocalProvider(LocalThreadShareCaptureActive provides shareCaptureMode) {
                ThreadChatTab(
                    threadInstanceKey = threadInstanceKey,
                    routeActive = routeActive && tabActive,
                    hostForeground = hostForeground,
                    renderConfig = timelineRenderConfig,
                    messageBodyTextStyle = messageBodyTextStyle,
                    timelineSlice = timelineSlice,
                    replyMetadataByParentId = replyMetadataByParentId,
                    composerDraftPersistSlice = composerDraftPersistSlice,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksProjection = linkedTasksProjection,
                    taskAssigneeOptions = taskIdentityOptions,
                    highlightedMentions = highlightedMentions,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    memberModelLabelResolver = memberModelLabelResolver,
                    inlineReplyParticipantsById = inlineReplyParticipantsById,
                    pendingActionCardIds = pendingActionCardIds,
                    attachmentOpenStates = attachmentOpenStates,
                    writeActionsEnabled = writeActionsEnabled,
                    currentServerSlug = currentServerSlug,
                    contentExtrasVisible = contentExtrasVisible,
                    longMessageCollapseEnabled = longMessageCollapseEnabled,
                    jumpInputs = jumpInputs,
                    newMessageMenuEnabled = newMessageMenuEnabled,
                    inlineSearchVisible = inlineSearchVisible,
                    isMarkdownTagValid = isMarkdownTagValid,
                    markdownTagContextSignature = markdownTagContextSignature,
                    resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                    activeMarkdownTagKey = activeMarkdownTagKey,
                    onThreadEvent = onThreadEvent,
                    onLoadNewer = onLoadNewer,
                    onJumpToLatest = onJumpToLatest,
                    onOpenMember = onOpenMemberProfile,
                    onExternalAuthor = onExternalAuthor,
                    onMentionAuthor = { message ->
                        val handle = mentionHandleResolver(message)
                        if (showComposerForSelectedTab && handle != null) {
                            insertMentionRequestNonce += 1
                            insertMentionRequest =
                                ThreadComposerInsertMentionRequest(
                                    nonce = insertMentionRequestNonce,
                                    handle = handle,
                                    mention = message.threadAuthorStructuredMention(handle)
                                )
                        } else if (handle == null) {
                            diagnosticsLogger(
                                "thread_direct_mention_rejected reason=canonical_handle_unavailable " +
                                    "messageId=${message.id} authorId=${message.authorId}"
                            )
                        }
                    },
                    onOpenMarkdownTag = openMarkdownTag,
                    onOpenMarkdownUrl = openMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger,
                    onComposerLayoutProbe = onComposerLayoutProbe,
                    onComposerPixelProbe = onComposerPixelProbe,
                    shareSelectionActive = shareSelectionActive && !shareCaptureMode,
                    shareSelectionCaptureActive = shareCaptureMode,
                    shareSelectionCaptureAnchorMessageId =
                        shareCaptureTargetMessageId ?: selectedShareMessages.firstOrNull()?.id,
                    shareSelectionCaptureAnchorBeforeTarget = shareCaptureAnchorBeforeTarget,
                    shareSelectionCaptureTailPaddingActive = shareCaptureTailPaddingActive,
                    shareSelectionCaptureScrollOffsetPx = shareCaptureScrollOffsetPx,
                    headerScrollToTopRequest = threadHeaderScrollToTopRequest,
                    selectedShareMessageIds = selectedShareMessageIds,
                    shareSelectToHere =
                        remember(shareSelectionAnchorId, selectableShareMessageIds) {
                            ThreadShareSelectToHere(
                                anchorId = shareSelectionAnchorId,
                                selectableIds = selectableShareMessageIds,
                                onSelectRange = { ids, edgeId ->
                                    if (shareSelectionMode) {
                                        selectedShareMessageIds = selectedShareMessageIds + ids
                                        shareSelectionAnchorId = edgeId
                                    }
                                },
                            )
                        },
                    onShareSelectionBounds = { messageId, rect ->
                        if (rect == null) {
                            selectedShareBounds.remove(messageId)
                        } else {
                            selectedShareBounds[messageId] = rect
                            diagnosticsLogger(
                                "thread_share_bounds_update messageId=$messageId " +
                                    "x=${rect.x} y=${rect.y} width=${rect.width} height=${rect.height}"
                            )
                        }
                    },
                    onEnterShareSelection = { messageId ->
                        if (!payload.isRetractedMessage(messageId)) {
                            selectedShareMessageIds = setOf(messageId)
                            shareSelectionAnchorId = messageId
                            shareSelectionMode = true
                            activeModal = null
                        }
                    },
                    onForwardMessage = { messageId ->
                        openForwardPickerForMessageIds(setOf(messageId))
                    },
                    onToggleShareSelection = { messageId ->
                        if (messageId in selectedShareMessageIds) {
                            selectedShareMessageIds = selectedShareMessageIds - messageId
                            // Deselecting the anchor leaves none (a range from it would re-add it).
                            if (shareSelectionAnchorId == messageId) shareSelectionAnchorId = null
                        } else if (!payload.isRetractedMessage(messageId)) {
                            // RFC 077 PR 2b: nothing of a retracted message can be shared.
                            selectedShareMessageIds = selectedShareMessageIds + messageId
                            shareSelectionAnchorId = messageId
                        }
                    },
                    onOpenForwardBundle = { messageId ->
                        val serverId = forwardServerId?.value
                        val channelId = payload.resolveThreadRouteIdentity().timelineChannelId
                        if (serverId != null && channelId != null) {
                            threadScreenPagerForForwardBundle.openThreadForwardBundlePage(
                                ThreadForwardBundleRoute(
                                    serverId = ServerId(serverId),
                                    channelId = ChannelId(channelId),
                                    messageId = MessageId(messageId)
                                )
                            )
                        } else {
                            diagnosticsLogger(
                                "thread_forward_bundle_open_rejected reason=route_identity_missing " +
                                    "messageId=$messageId serverId=${serverId.orEmpty()} channelId=${channelId.orEmpty()}"
                            )
                        }
                    },
                    translationStore = translationStore,
                    onThreadContentReady = onFirstContentReady,
                    onVisibleReadTailChange = onVisibleReadTailChange,
                    onViewportFollowsLatestChange = onViewportFollowsLatestChange,
                    visibleReadTailIndex = visibleReadTailIndex,
                    adaptiveCloseRequest = adaptiveCloseRequest,
                    adaptiveStoreScope = adaptiveStoreScope,
                    adaptiveEventBus = adaptiveEventBus
                )
                }
            RaftThreadTab.Tasks ->
                ThreadTasksTab(
                    tasksViewModel = tasksViewModel,
                    channelId = taskPanelChannelId,
                    serverId = forwardServerId?.value,
                    active = tab == selectedTab || tab.conversationSideCard()?.let { sideCardShowing(sideCards, sideCardsBeside, it) } == true,
                    identityOptions = taskIdentityOptions,
                    openTaskEnabled = payload.showTaskOpenActions,
                    // Web canWriteTasksInChannel: New task, status and assignee only where the reader may
                    // write this channel's tasks (the message menu's own gate).
                    taskWritesEnabled = payload.channelTaskWritesShown(),
                    draftProjection = draftProjection,
                    timeContext = payload.toRaftTimeCapabilities().staticContext(),
                    onOpenTask = { task -> onOpenTaskSummary(task, "tasks_panel") },
                    onIntent = onTasksIntent
                )
            RaftThreadTab.Files ->
                ThreadFilesTab(
                    threadInstanceKey = threadInstanceKey,
                    basePayload = payload,
                    filesStartKey = resolvedAuxiliaryThreadId,
                    filesViewModel = filesViewModel,
                    active = tab == selectedTab || tab.conversationSideCard()?.let { sideCardShowing(sideCards, sideCardsBeside, it) } == true,
                    attachmentOpenStates = attachmentOpenStates,
                    onAuthRequired = onFilesAuthRequired,
                    onOpenAttachment = onOpenFilesAttachment,
                    onDownloadAttachment = onDownloadFilesAttachment,
                    onOpenFileSource = { fileItem -> onThreadEvent("openFileSource", fileItem.attachmentId, null, null) }
                )
        }
    }
}
