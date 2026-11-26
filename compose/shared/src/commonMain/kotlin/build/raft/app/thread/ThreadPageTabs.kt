package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.raftToastAvoid
import build.raft.app.components.RaftSlotIcon
import build.raft.app.RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY
import build.raft.app.components.RaftAnchoredMetaChip
import build.raft.app.base.Utils
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.RaftEventBus
import build.raft.app.data.map
import build.raft.app.data.LocalTaskChannelWriteCheck
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedMessageTranslationStore
import build.raft.app.data.TasksUiState
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.model.MemberProfile
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.model.ChannelId
import build.raft.app.components.RaftAnchorPlacement
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.map

private fun RaftThreadFilesPayload.findThreadImageAttachmentGroup(attachmentId: String): ThreadImageAttachmentGroup? {
    val images =
        items
            .filter { raftFilePreviewIsImageAttachment(fileName = it.name, mimeType = it.mimeType) }
            .map { item ->
                RaftThreadAttachmentItem(
                    id = item.attachmentId,
                    name = item.name,
                    mimeType = item.mimeType,
                    sizeBytes = item.sizeBytes,
                    sizeLabel = item.sizeLabel,
                    thumbnailUrl = item.thumbnailUrl
                )
            }
    val currentIndex = images.indexOfFirst { it.id == attachmentId }
    return if (currentIndex >= 0) ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex) else null
}

private fun RaftThreadFilesPayload.findThreadAttachment(attachmentId: String): RaftThreadAttachmentItem? =
    items.firstOrNull { it.attachmentId == attachmentId }?.let { item ->
        RaftThreadAttachmentItem(
            id = item.attachmentId,
            name = item.name,
            mimeType = item.mimeType,
            sizeBytes = item.sizeBytes,
            sizeLabel = item.sizeLabel,
            thumbnailUrl = item.thumbnailUrl
        )
    }

private fun RaftThreadFilesPayload.shouldFetchInitialFiles(): Boolean =
    status == "loading" || (status == "error" && items.isEmpty())

/**
 * task #346: state payload of [ThreadChatTab]. See RaftStructuralHomeShellData for why
 * the flat parameter list had to be grouped (Android dex verifier rejects the
 * un-minified giant composable frame on API 34/36 builds).
 */
private data class ThreadChatTabData(
    val threadInstanceKey: String,
    val routeActive: Boolean,
    val hostForeground: Boolean,
    val renderConfig: ThreadTimelineRenderConfig,
    val messageBodyTextStyle: TextStyle,
    val timelineSlice: ThreadTimelineSlice,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val composerKeyboardInsetDp: Float,
    val linkedTasksProjection: ThreadLinkedTasksProjection, // Task #258: pass-through to ThreadMessagesList's task detail panel.,
    val taskAssigneeOptions: List<MemberProfile>,
    val highlightedMentions: Set<String>,
    val inlineReplyParticipantsById: Map<String, RaftThreadMemberItem>,
    val pendingActionCardIds: Set<String>,
    val attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    val writeActionsEnabled: Boolean,
    val currentServerSlug: String?,
    val contentExtrasVisible: Boolean,
    val longMessageCollapseEnabled: Boolean,
    val jumpInputs: ThreadJumpInputs,
    val newMessageMenuEnabled: Boolean,
    val inlineSearchVisible: Boolean,
    val markdownTagContextSignature: String,
    val activeMarkdownTagKey: String?,
    val onThreadEvent: ThreadEventHandler,
    val shareSelectionActive: Boolean,
    val shareSelectionCaptureActive: Boolean,
    val shareSelectionCaptureAnchorMessageId: String?,
    val shareSelectionCaptureAnchorBeforeTarget: Boolean,
    val shareSelectionCaptureTailPaddingActive: Boolean,
    val shareSelectionCaptureScrollOffsetPx: Int,
    val headerScrollToTopRequest: Int,
    val selectedShareMessageIds: Set<String>,
    val shareSelectToHere: ThreadShareSelectToHere?,
    val translationStore: SharedMessageTranslationStore?,
    val visibleReadTailIndex: Int?,
    /** A4 destination contract (task #18, spec §9): non-null when hosted as a detail pane. */
    val adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
    val adaptiveStoreScope: RaftShellStoreScope,
    val adaptiveEventBus: RaftEventBus,
)

/** task #346: callback payload of [ThreadChatTab]. See ThreadChatTabData. */
private class ThreadChatTabCallbacks(
    val agentStatusResolver: RaftAgentStatusResolver,
    val memberAvatarResolver: RaftMemberAvatarResolver,
    val memberModelLabelResolver: RaftMemberModelLabelResolver,
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean,
    val resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)?,
    val onLoadNewer: suspend () -> ThreadNewerPageLoadOutcome,
    val onJumpToLatest: suspend () -> ThreadLatestJumpOutcome,
    val onOpenMember: (MemberDetailIdentity) -> Unit,
    val onExternalAuthor: (RaftThreadMessageItem) -> Unit,
    val onMentionAuthor: (RaftThreadMessageItem) -> Unit,
    val onOpenMarkdownTag: (ThreadMarkdownTag, String) -> Unit,
    val onOpenMarkdownUrl: (String) -> Unit,
    val quotedPermalinkLoader: ThreadQuotedPermalinkLoader?,
    val diagnosticsLogger: (String) -> Unit,
    val onComposerLayoutProbe: (phase: String, metadata: Map<String, String>) -> Unit,
    val onComposerPixelProbe: suspend (Float, Float, Float, Float, Long) -> JSONObject?,
    val onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit,
    val onEnterShareSelection: (String) -> Unit,
    val onForwardMessage: (String) -> Unit,
    val onToggleShareSelection: (String) -> Unit,
    val onOpenForwardBundle: (String) -> Unit,
    val onThreadContentReady: () -> Unit,
    val onVisibleReadTailChange: (Int?) -> Unit,
    val onViewportFollowsLatestChange: (Boolean) -> Unit,
)

internal @Composable
fun ThreadChatTab(
    threadInstanceKey: String,
    routeActive: Boolean,
    hostForeground: Boolean,
    renderConfig: ThreadTimelineRenderConfig,
    messageBodyTextStyle: TextStyle,
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    composerKeyboardInsetDp: Float,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    // Task #258: pass-through to ThreadMessagesList's task detail panel.
    taskAssigneeOptions: List<MemberProfile> = emptyList(),
    highlightedMentions: Set<String>,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver = EmptyRaftMemberModelLabelResolver,
    inlineReplyParticipantsById: Map<String, RaftThreadMemberItem> = emptyMap(),
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    writeActionsEnabled: Boolean = true,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    longMessageCollapseEnabled: Boolean = false,
    jumpInputs: ThreadJumpInputs = ThreadJumpInputs(),
    newMessageMenuEnabled: Boolean = false,
    inlineSearchVisible: Boolean = false,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    markdownTagContextSignature: String = "",
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    activeMarkdownTagKey: String? = null,
    onThreadEvent: ThreadEventHandler,
    onLoadNewer: suspend () -> ThreadNewerPageLoadOutcome,
    onJumpToLatest: suspend () -> ThreadLatestJumpOutcome,
    onOpenMember: (MemberDetailIdentity) -> Unit,
    onExternalAuthor: (RaftThreadMessageItem) -> Unit = {},
    onMentionAuthor: (RaftThreadMessageItem) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag, String) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    quotedPermalinkLoader: ThreadQuotedPermalinkLoader? = null,
    diagnosticsLogger: (String) -> Unit = {},
    onComposerLayoutProbe: (phase: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    onComposerPixelProbe: suspend (Float, Float, Float, Float, Long) -> JSONObject? = { _, _, _, _, _ -> null },
    shareSelectionActive: Boolean = false,
    shareSelectionCaptureActive: Boolean = false,
    shareSelectionCaptureAnchorMessageId: String? = null,
    shareSelectionCaptureAnchorBeforeTarget: Boolean = false,
    shareSelectionCaptureTailPaddingActive: Boolean = false,
    shareSelectionCaptureScrollOffsetPx: Int = ThreadShareCaptureTopScrollOffsetPx,
    headerScrollToTopRequest: Int = 0,
    selectedShareMessageIds: Set<String> = emptySet(),
    shareSelectToHere: ThreadShareSelectToHere? = null,
    onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit = { _, _ -> },
    onEnterShareSelection: (String) -> Unit = {},
    onForwardMessage: (String) -> Unit = {},
    onToggleShareSelection: (String) -> Unit = {},
    onOpenForwardBundle: (String) -> Unit = {},
    translationStore: SharedMessageTranslationStore? = null,
    onThreadContentReady: () -> Unit = {},
    visibleReadTailIndex: Int? = null,
    onVisibleReadTailChange: (Int?) -> Unit = {},
    onViewportFollowsLatestChange: (Boolean) -> Unit = {},
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest? = null,
    adaptiveStoreScope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
    adaptiveEventBus: RaftEventBus,
)
 {
    ThreadChatTabContent(
        modifier = Modifier,
        data =
            ThreadChatTabData(
                threadInstanceKey = threadInstanceKey,
                routeActive = routeActive,
                hostForeground = hostForeground,
                renderConfig = renderConfig,
                messageBodyTextStyle = messageBodyTextStyle,
                timelineSlice = timelineSlice,
                replyMetadataByParentId = replyMetadataByParentId,
                composerDraftPersistSlice = composerDraftPersistSlice,
                composerKeyboardInsetDp = composerKeyboardInsetDp,
                linkedTasksProjection = linkedTasksProjection,
                taskAssigneeOptions = taskAssigneeOptions,
                highlightedMentions = highlightedMentions,
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
                markdownTagContextSignature = markdownTagContextSignature,
                activeMarkdownTagKey = activeMarkdownTagKey,
                onThreadEvent = onThreadEvent,
                shareSelectionActive = shareSelectionActive,
                shareSelectionCaptureActive = shareSelectionCaptureActive,
                shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                shareSelectionCaptureAnchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget,
                shareSelectionCaptureTailPaddingActive = shareSelectionCaptureTailPaddingActive,
                shareSelectionCaptureScrollOffsetPx = shareSelectionCaptureScrollOffsetPx,
                headerScrollToTopRequest = headerScrollToTopRequest,
                selectedShareMessageIds = selectedShareMessageIds,
                shareSelectToHere = shareSelectToHere,
                translationStore = translationStore,
                visibleReadTailIndex = visibleReadTailIndex,
                adaptiveCloseRequest = adaptiveCloseRequest,
                adaptiveStoreScope = adaptiveStoreScope,
                adaptiveEventBus = adaptiveEventBus,
            ),
        callbacks =
            ThreadChatTabCallbacks(
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                memberModelLabelResolver = memberModelLabelResolver,
                isMarkdownTagValid = isMarkdownTagValid,
                resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                onLoadNewer = onLoadNewer,
                onJumpToLatest = onJumpToLatest,
                onOpenMember = onOpenMember,
                onExternalAuthor = onExternalAuthor,
                onMentionAuthor = onMentionAuthor,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                quotedPermalinkLoader = quotedPermalinkLoader,
                diagnosticsLogger = diagnosticsLogger,
                onComposerLayoutProbe = onComposerLayoutProbe,
                onComposerPixelProbe = onComposerPixelProbe,
                onShareSelectionBounds = onShareSelectionBounds,
                onEnterShareSelection = onEnterShareSelection,
                onForwardMessage = onForwardMessage,
                onToggleShareSelection = onToggleShareSelection,
                onOpenForwardBundle = onOpenForwardBundle,
                onThreadContentReady = onThreadContentReady,
                onVisibleReadTailChange = onVisibleReadTailChange,
                onViewportFollowsLatestChange = onViewportFollowsLatestChange,
            ),
    )
}

@Composable
private fun ThreadChatTabContent(
    modifier: Modifier = Modifier,
    data: ThreadChatTabData,
    callbacks: ThreadChatTabCallbacks,
) {
    val threadInstanceKey = data.threadInstanceKey
    val routeActive = data.routeActive
    val hostForeground = data.hostForeground
    val renderConfig = data.renderConfig
    val messageBodyTextStyle = data.messageBodyTextStyle
    val timelineSlice = data.timelineSlice
    val replyMetadataByParentId = data.replyMetadataByParentId
    val composerDraftPersistSlice = data.composerDraftPersistSlice
    val composerKeyboardInsetDp = data.composerKeyboardInsetDp
    val linkedTasksProjection = data.linkedTasksProjection
    val taskAssigneeOptions = data.taskAssigneeOptions
    val highlightedMentions = data.highlightedMentions
    val agentStatusResolver = callbacks.agentStatusResolver
    val memberAvatarResolver = callbacks.memberAvatarResolver
    val memberModelLabelResolver = callbacks.memberModelLabelResolver
    val inlineReplyParticipantsById = data.inlineReplyParticipantsById
    val pendingActionCardIds = data.pendingActionCardIds
    val attachmentOpenStates = data.attachmentOpenStates
    val writeActionsEnabled = data.writeActionsEnabled
    val currentServerSlug = data.currentServerSlug
    val contentExtrasVisible = data.contentExtrasVisible
    val longMessageCollapseEnabled = data.longMessageCollapseEnabled
    val jumpInputs = data.jumpInputs
    val newMessageMenuEnabled = data.newMessageMenuEnabled
    val inlineSearchVisible = data.inlineSearchVisible
    val isMarkdownTagValid = callbacks.isMarkdownTagValid
    val markdownTagContextSignature = data.markdownTagContextSignature
    val resolveMarkdownTagLabel = callbacks.resolveMarkdownTagLabel
    val activeMarkdownTagKey = data.activeMarkdownTagKey
    val onThreadEvent = data.onThreadEvent
    val onLoadNewer = callbacks.onLoadNewer
    val onJumpToLatest = callbacks.onJumpToLatest
    val onOpenMember = callbacks.onOpenMember
    val onExternalAuthor = callbacks.onExternalAuthor
    val onMentionAuthor = callbacks.onMentionAuthor
    val onOpenMarkdownTag = callbacks.onOpenMarkdownTag
    val onOpenMarkdownUrl = callbacks.onOpenMarkdownUrl
    val quotedPermalinkLoader = callbacks.quotedPermalinkLoader
    val diagnosticsLogger = callbacks.diagnosticsLogger
    val onComposerLayoutProbe = callbacks.onComposerLayoutProbe
    val onComposerPixelProbe = callbacks.onComposerPixelProbe
    val shareSelectionActive = data.shareSelectionActive
    val shareSelectionCaptureActive = data.shareSelectionCaptureActive
    val shareSelectionCaptureAnchorMessageId = data.shareSelectionCaptureAnchorMessageId
    val shareSelectionCaptureAnchorBeforeTarget = data.shareSelectionCaptureAnchorBeforeTarget
    val shareSelectionCaptureTailPaddingActive = data.shareSelectionCaptureTailPaddingActive
    val shareSelectionCaptureScrollOffsetPx = data.shareSelectionCaptureScrollOffsetPx
    val headerScrollToTopRequest = data.headerScrollToTopRequest
    val selectedShareMessageIds = data.selectedShareMessageIds
    val shareSelectToHere = data.shareSelectToHere
    val onShareSelectionBounds = callbacks.onShareSelectionBounds
    val onEnterShareSelection = callbacks.onEnterShareSelection
    val onForwardMessage = callbacks.onForwardMessage
    val onToggleShareSelection = callbacks.onToggleShareSelection
    val onOpenForwardBundle = callbacks.onOpenForwardBundle
    val translationStore = data.translationStore
    val onThreadContentReady = callbacks.onThreadContentReady
    val visibleReadTailIndex = data.visibleReadTailIndex
    val adaptiveCloseRequest = data.adaptiveCloseRequest
    val adaptiveStoreScope = data.adaptiveStoreScope
    val adaptiveEventBus = data.adaptiveEventBus
    val onVisibleReadTailChange = callbacks.onVisibleReadTailChange
    val onViewportFollowsLatestChange = callbacks.onViewportFollowsLatestChange
    // A4 destination side (task #18, spec §9): READY must post for EVERY resolved branch —
    // including empty_state, which renders ThreadStateMessage below and never mounts
    // ThreadMessagesList (Jony's 8th QA: real empty status is "empty", not "success", and
    // it bypasses the list entirely). The restore leg for non-empty payloads still lives
    // inside the list where listState exists; both share this flag so READY can't double.
    val closeReq = adaptiveCloseRequest
    val adaptivePager = LocalActivity.current.getPager() as Pager
    val adaptiveSharedStores = remember(adaptivePager) {
        RaftSharedStoreRegistry.get(Utils.bridgeModule(adaptivePager.pagerId))
    }
    val adaptiveReadyPosted = remember(threadInstanceKey) { mutableStateOf(false) }
    fun postDetailReadyTrace(hop: String, extra: JSONObject.() -> Unit = {}) {
        val request = closeReq ?: return
        val extraJson = JSONObject().apply(extra).toString()
        diagnosticsLogger(
            formatAdaptiveTraceLine(
                side = "detail",
                hop = hop,
                identityKey = request.identityKey,
                generation = request.generation,
                extra =
                    "routeActive=$routeActive status=${timelineSlice.status} " +
                        "messagesCount=${timelineSlice.messages.size}" +
                        if (extraJson == "{}") "" else " extra=$extraJson",
            ),
        )
        adaptiveEventBus?.postAdaptiveTrace(
            side = "detail",
            hop = hop,
            identityKey = request.identityKey,
            generation = request.generation,
        ) {
            put("routeActive", routeActive)
            put("status", timelineSlice.status)
            put("messagesCount", timelineSlice.messages.size)
            extra()
        }
    }
    LaunchedEffect(closeReq, timelineSlice.status, timelineSlice.messages.size) {
        val request = closeReq ?: return@LaunchedEffect
        if (adaptiveReadyPosted.value) return@LaunchedEffect
        postDetailReadyTrace("effect_enter")
        when (evaluateRaftAdaptiveReadyGate(
            adaptiveReadyPosted.value,
            timelineSlice.status,
            timelineSlice.messages.size,
        )) {
            RaftAdaptiveReadyGate.AlreadyPosted -> return@LaunchedEffect
            RaftAdaptiveReadyGate.StatusBlocked -> {
                postDetailReadyTrace("status_gate")
                return@LaunchedEffect
            }
            // Non-empty resolved payload: the list owns anchor restore + READY.
            RaftAdaptiveReadyGate.RestoreThenReady -> return@LaunchedEffect
            RaftAdaptiveReadyGate.ReadyImmediately -> Unit
        }
        adaptiveSharedStores
            .adaptiveWorkspaceStore(adaptiveStoreScope)
            .snapshot()
            .workspace
            ?.selection
            ?.takeIf { sel -> sel.identity.key == request.identityKey }
            ?: run {
                postDetailReadyTrace("selection_miss")
                adaptiveReadyPosted.value = true
                return@LaunchedEffect
            }
        adaptiveReadyPosted.value = true
        val eventBus = adaptiveEventBus ?: return@LaunchedEffect
        postDetailReadyTrace("ready_post")
        eventBus.post(
            eventName = RAFT_ADAPTIVE_DETAIL_READY_EVENT,
            eventData =
                JSONObject()
                    .put(RAFT_ADAPTIVE_DETAIL_SHELL_ID, request.shellInstanceId)
                    .put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, request.identityKey)
                    .put(RAFT_ADAPTIVE_DETAIL_GENERATION, request.generation.toDouble()),
            crossProcess = true,
            source = "adaptive_detail_ready",
        )
    }
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    val activeDraftRecords by composerDraftPersistSlice.activeDraftRecordsState.collectAsState()
    val activeReplyDraftParentMessageIds =
        remember(activeDraftRecords) {
            activeDraftRecords.toReplyComposerDraftParentMessageIds()
        }
    val chatRenderBranch =
        when (timelineSlice.status) {
            "loading" -> if (timelineSlice.originStatus != "none") "loading_with_origin" else "loading_empty"
            "error" -> "error"
            "no_permission" -> "no_permission"
            "empty" -> if (timelineSlice.originStatus != "none") "empty_with_origin" else "empty_state"
            else -> "messages_list"
        }
    LaunchedEffect(
        threadInstanceKey,
        timelineSlice.status,
        timelineSlice.messages.size,
        renderConfig.backgroundRefreshing,
        timelineSlice.originStatus,
        chatRenderBranch
    ) {
        diagnosticsLogger(
            "thread_chat_visible_state instance=$threadInstanceKey " +
                "status=${timelineSlice.status} messages=${timelineSlice.messages.size} " +
                "backgroundRefreshing=${renderConfig.backgroundRefreshing} " +
                "originStatus=${timelineSlice.originStatus} branch=$chatRenderBranch"
        )
    }
    when (timelineSlice.status) {
        "loading" ->
            if (timelineSlice.originStatus != "none") {
                ThreadMessagesList(
                    threadInstanceKey = threadInstanceKey,
                    routeActive = routeActive,
                    hostForeground = hostForeground,
                    renderConfig = renderConfig,
                    messageBodyTextStyle = messageBodyTextStyle,
                    timelineSlice = timelineSlice,
                    replyMetadataByParentId = replyMetadataByParentId,
                    activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksProjection = linkedTasksProjection,
                    taskAssigneeOptions = taskAssigneeOptions,
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
                    onOpenMember = onOpenMember,
                    onExternalAuthor = onExternalAuthor,
                    onMentionAuthor = onMentionAuthor,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    quotedPermalinkLoader = quotedPermalinkLoader,
                    diagnosticsLogger = diagnosticsLogger,
                    onComposerLayoutProbe = onComposerLayoutProbe,
                    onComposerPixelProbe = onComposerPixelProbe,
                    shareSelectionActive = shareSelectionActive,
                    shareSelectionCaptureActive = shareSelectionCaptureActive,
                    shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                    shareSelectionCaptureAnchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget,
                    shareSelectionCaptureTailPaddingActive = shareSelectionCaptureTailPaddingActive,
                    shareSelectionCaptureScrollOffsetPx = shareSelectionCaptureScrollOffsetPx,
                    headerScrollToTopRequest = headerScrollToTopRequest,
                    selectedShareMessageIds = selectedShareMessageIds,
                    shareSelectToHere = shareSelectToHere,
                    onShareSelectionBounds = onShareSelectionBounds,
                    onEnterShareSelection = onEnterShareSelection,
                    onForwardMessage = onForwardMessage,
                    onToggleShareSelection = onToggleShareSelection,
                    onOpenForwardBundle = onOpenForwardBundle,
                    translationStore = translationStore,
                    onThreadContentReady = onThreadContentReady,
                    adaptiveCloseRequest = adaptiveCloseRequest,
                    adaptiveStoreScope = adaptiveStoreScope,
                    adaptiveEventBus = adaptiveEventBus,
                    adaptiveReadyPosted = adaptiveReadyPosted,
                )
            } else if (timelineSlice.loadingIndicatorRevealed) {
                ThreadLoadingState()
            }
        "error" ->
            if (LocalConversationUnavailableNav.current != null &&
                isConversationUnavailableErrorCode(renderConfig.errorCode)
            ) {
                val nav = checkNotNull(LocalConversationUnavailableNav.current)
                RaftConversationUnavailableState(wide = nav.wide, onBack = nav.onBack)
            } else ThreadStateActionMessage(
                title = stringResource(Res.string.thread_error_load),
                detail =
                    userFacingErrorLabels.forErrorCode(renderConfig.errorCode)
                        ?: stringResource(Res.string.common_error),
                actionLabel = stringResource(Res.string.common_retry),
                onClick = { onThreadEvent("retryThreadLoad", null, null, null) },
                traceId = renderConfig.errorTraceId
            )
        "no_permission" ->
            ThreadStateMessage(
                if (renderConfig.errorServerMessage.isEdgeBlockedMarker()) {
                    stringResource(Res.string.thread_state_edge_blocked)
                } else {
                    stringResource(Res.string.thread_state_no_permission)
                },
                if (renderConfig.errorServerMessage.isEdgeBlockedMarker()) {
                    stringResource(Res.string.error_edge_blocked)
                } else {
                    stringResource(Res.string.thread_error_no_permission)
                },
                traceId = renderConfig.errorTraceId
            )
        "empty" ->
            if (timelineSlice.originStatus != "none") {
                ThreadMessagesList(
                    threadInstanceKey = threadInstanceKey,
                    routeActive = routeActive,
                    hostForeground = hostForeground,
                    renderConfig = renderConfig,
                    messageBodyTextStyle = messageBodyTextStyle,
                    timelineSlice = timelineSlice,
                    replyMetadataByParentId = replyMetadataByParentId,
                    activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksProjection = linkedTasksProjection,
                    taskAssigneeOptions = taskAssigneeOptions,
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
                    onOpenMember = onOpenMember,
                    onExternalAuthor = onExternalAuthor,
                    onMentionAuthor = onMentionAuthor,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    quotedPermalinkLoader = quotedPermalinkLoader,
                    diagnosticsLogger = diagnosticsLogger,
                    onComposerLayoutProbe = onComposerLayoutProbe,
                    onComposerPixelProbe = onComposerPixelProbe,
                    shareSelectionActive = shareSelectionActive,
                    shareSelectionCaptureActive = shareSelectionCaptureActive,
                    shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                    shareSelectionCaptureAnchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget,
                    shareSelectionCaptureTailPaddingActive = shareSelectionCaptureTailPaddingActive,
                    shareSelectionCaptureScrollOffsetPx = shareSelectionCaptureScrollOffsetPx,
                    headerScrollToTopRequest = headerScrollToTopRequest,
                    selectedShareMessageIds = selectedShareMessageIds,
                    shareSelectToHere = shareSelectToHere,
                    onShareSelectionBounds = onShareSelectionBounds,
                    onEnterShareSelection = onEnterShareSelection,
                    onForwardMessage = onForwardMessage,
                    onToggleShareSelection = onToggleShareSelection,
                    onOpenForwardBundle = onOpenForwardBundle,
                    translationStore = translationStore,
                    onThreadContentReady = onThreadContentReady,
                    adaptiveCloseRequest = adaptiveCloseRequest,
                    adaptiveStoreScope = adaptiveStoreScope,
                    adaptiveEventBus = adaptiveEventBus,
                    adaptiveReadyPosted = adaptiveReadyPosted,
                )
            } else if (
                threadEmptyStandInAwaitsFirstLoad(
                    status = timelineSlice.status,
                    durableLastKnown = timelineSlice.durableLastKnown,
                    mainLoadFailed = timelineSlice.mainLoadFailed
                )
            ) {
                if (timelineSlice.loadingIndicatorRevealed) {
                    ThreadLoadingState()
                }
            } else {
                LaunchedEffect(threadInstanceKey, timelineSlice.status) {
                    onThreadContentReady()
                }
                val emptyOfflineChrome =
                    threadOfflineLastKnownChrome(
                        durableLastKnown = timelineSlice.durableLastKnown,
                        status = timelineSlice.status,
                        backgroundRefreshing = renderConfig.backgroundRefreshing,
                        mainLoadFailed = timelineSlice.mainLoadFailed
                    )
                val emptyHiddenHistory = rememberThreadHiddenHistory(timelineSlice.historyChannelId)
                Box(modifier = Modifier.fillMaxSize()) {
                    if (emptyHiddenHistory != null) {
                        // Every message is behind the Free plan's window: not empty, hidden.
                        ThreadHistoryPaywallEmptyState(
                            scope = timelineSlice.historyPaywallScope(),
                            hidden = emptyHiddenHistory,
                            timeContext = renderConfig.timeContext,
                            onUpgrade = { onThreadEvent(ThreadOpenPlanBillingEvent, null, null, null) }
                        )
                    } else {
                        ThreadStateMessage(
                            localizedThreadStateTitle(timelineSlice.emptyTitle),
                            localizedThreadStateDetail(timelineSlice.emptyDetail)
                        )
                    }
                    // Task #1137: the empty last-known channel carries the same offline tip as a non-empty one.
                    if (emptyOfflineChrome.tipVisible) {
                        ThreadOfflineLastKnownTip(
                            modifier = Modifier.align(Alignment.TopCenter).zIndex(ThreadOfflineLastKnownTipZIndex)
                        )
                    }
                }
            }
        else ->
            ThreadMessagesList(
                threadInstanceKey = threadInstanceKey,
                routeActive = routeActive,
                hostForeground = hostForeground,
                renderConfig = renderConfig,
                messageBodyTextStyle = messageBodyTextStyle,
                timelineSlice = timelineSlice,
                replyMetadataByParentId = replyMetadataByParentId,
                activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
                composerKeyboardInsetDp = composerKeyboardInsetDp,
                linkedTasksProjection = linkedTasksProjection,
                taskAssigneeOptions = taskAssigneeOptions,
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
                onThreadEvent = onThreadEvent,
                onLoadNewer = onLoadNewer,
                onJumpToLatest = onJumpToLatest,
                onOpenMember = onOpenMember,
                onExternalAuthor = onExternalAuthor,
                onMentionAuthor = onMentionAuthor,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                quotedPermalinkLoader = quotedPermalinkLoader,
                diagnosticsLogger = diagnosticsLogger,
                onComposerLayoutProbe = onComposerLayoutProbe,
                onComposerPixelProbe = onComposerPixelProbe,
                shareSelectionActive = shareSelectionActive,
                shareSelectionCaptureActive = shareSelectionCaptureActive,
                shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                shareSelectionCaptureAnchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget,
                shareSelectionCaptureTailPaddingActive = shareSelectionCaptureTailPaddingActive,
                shareSelectionCaptureScrollOffsetPx = shareSelectionCaptureScrollOffsetPx,
                headerScrollToTopRequest = headerScrollToTopRequest,
                selectedShareMessageIds = selectedShareMessageIds,
                shareSelectToHere = shareSelectToHere,
                onShareSelectionBounds = onShareSelectionBounds,
                onEnterShareSelection = onEnterShareSelection,
                onForwardMessage = onForwardMessage,
                onToggleShareSelection = onToggleShareSelection,
                onOpenForwardBundle = onOpenForwardBundle,
                translationStore = translationStore,
                onThreadContentReady = onThreadContentReady,
                onVisibleReadTailChange = onVisibleReadTailChange,
                onViewportFollowsLatestChange = onViewportFollowsLatestChange,
                visibleReadTailIndex = visibleReadTailIndex,
                adaptiveCloseRequest = adaptiveCloseRequest,
                adaptiveStoreScope = adaptiveStoreScope,
                adaptiveEventBus = adaptiveEventBus,
                adaptiveReadyPosted = adaptiveReadyPosted
            )
    }
}

@Composable
internal fun ThreadFilesTab(
    threadInstanceKey: String,
    basePayload: RaftThreadScreenPayload,
    filesStartKey: String?,
    filesViewModel: SharedThreadFilesViewModel?,
    active: Boolean,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    onAuthRequired: (String?) -> Unit,
    onOpenAttachment: (RaftThreadAttachmentItem, ThreadImageAttachmentGroup?) -> Unit,
    onDownloadAttachment: (RaftThreadAttachmentItem) -> Unit,
    onOpenFileSource: (RaftThreadFileItem) -> Unit
) {
    if (filesViewModel == null) {
        ThreadLoadingState()
        return
    }
    val vm = filesViewModel
    // Workspace Artifacts (web #11580): the conversation's own lead its files, behind the
    // server's workspace_artifacts_v0, latched while the tab is composed.
    val appLinkHost = LocalThreadAppLinkPreviewHost.current
    val artifactsServerId = appLinkHost?.serverId?.takeIf { appLinkHost.artifacts != null }
    val artifactsEnabled =
        rememberServerFeatureLatched(
            appLinkHost?.artifacts?.featureFlags,
            artifactsServerId?.let(::ServerId),
            WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY
        )
    val artifactsScope = artifactsServerId?.takeIf { artifactsEnabled }?.let(::ThreadFilesArtifactsScope)
    LaunchedEffect(
        threadInstanceKey,
        filesStartKey,
        basePayload.sourceThreadId,
        basePayload.streamChannelId,
        basePayload.instanceId,
        active,
        artifactsScope
    ) {
        if (!active) return@LaunchedEffect
        if (filesStartKey == null) return@LaunchedEffect
        vm.dispatch(
            SharedThreadFilesIntent.Start(
                basePayload = basePayload,
                artifacts = artifactsScope
            )
        )
        if (vm.state.value.payload.shouldFetchInitialFiles()) {
            vm.dispatch(SharedThreadFilesIntent.Refresh("initial_tab"))
        }
    }
    val filesState by vm.state.collectAsState()
    val payload = filesState.payload
    LaunchedEffect(payload.status, payload.errorCode) {
        if (payload.status == "no_permission" || payload.status == "error") {
            onAuthRequired(payload.errorCode)
        }
    }
    val openArtifactWebPage = rememberRaftWebPageOpener()
    val artifactWebBaseUrl = appLinkHost?.artifacts?.webBaseUrl.orEmpty()
    // The in-app viewer; the browser only when no page can open from here.
    val artifactOpener = rememberRaftArtifactOpener { openArtifactWebPage(artifactWebUrl(artifactWebBaseUrl, it)) }
    RaftArtifactViewerPrewarm(enabled = active && filesState.artifacts.items.isNotEmpty())
    ThreadFilesContent(
        payload = payload,
        attachmentOpenStates = attachmentOpenStates,
        autoLoadRouteListKey = threadInstanceKey,
        refreshing = filesState.refreshing,
        artifacts = filesState.artifacts
    ) { action, attachmentId ->
        when (action) {
            "openArtifact" -> {
                val artifact = filesState.artifacts.items.firstOrNull { it.summary.id == attachmentId } ?: return@ThreadFilesContent
                // The row opens the latest version, as the web's (`version: null`).
                val target = ArtifactLinkTarget(artifact.summary.id)
                artifactOpener.open(target, RaftArtifactViewerHeader.of(artifact.summary, target), "files_tab")
            }
            "loadMoreArtifacts" -> vm.dispatch(SharedThreadFilesIntent.LoadMoreArtifacts)
            "open" -> {
                val cleanAttachmentId = attachmentId?.takeIf { it.isNotBlank() } ?: return@ThreadFilesContent
                val attachment = payload.findThreadAttachment(cleanAttachmentId) ?: return@ThreadFilesContent
                onOpenAttachment(attachment, payload.findThreadImageAttachmentGroup(cleanAttachmentId))
            }
            "download" -> {
                val cleanAttachmentId = attachmentId?.takeIf { it.isNotBlank() } ?: return@ThreadFilesContent
                val attachment = payload.findThreadAttachment(cleanAttachmentId) ?: return@ThreadFilesContent
                onDownloadAttachment(attachment)
            }
            "source" -> {
                val cleanAttachmentId = attachmentId?.takeIf { it.isNotBlank() } ?: return@ThreadFilesContent
                val fileItem = payload.items.firstOrNull { it.attachmentId == cleanAttachmentId } ?: return@ThreadFilesContent
                onOpenFileSource(fileItem)
            }
            "loadMore" -> vm.dispatch(SharedThreadFilesIntent.LoadMore("load_more"))
            "retry" -> vm.dispatch(SharedThreadFilesIntent.Refresh("retry"))
            "pullRefresh" -> vm.dispatch(SharedThreadFilesIntent.PullRefresh)
        }
    }
}

@Composable
internal fun ThreadTasksTab(
    tasksViewModel: SharedTasksViewModel?,
    channelId: ChannelId,
    serverId: String?,
    active: Boolean,
    identityOptions: List<MemberProfile>,
    openTaskEnabled: Boolean,
    taskWritesEnabled: Boolean = true,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    timeContext: RaftTimeContext? = null,
    onOpenTask: (TaskSummary) -> Unit,
    onIntent: (SharedTasksRouteIntent) -> Unit
) {
    val pendingTasksState = remember { TasksUiState(ScreenLoadState.Loading) }
    val state =
        if (tasksViewModel != null) {
            val collectedTasksState by tasksViewModel.state.collectAsState()
            collectedTasksState
        } else {
            pendingTasksState
        }
    // #wg-mobile-feedback:#274: SharedTasksViewModel owns RaftFreshnessSignal catch-up and outcome tracking.
    val tasksFreshnessToken = remember { Any() }
    LaunchedEffect(tasksViewModel, tasksFreshnessToken, serverId, active) {
        tasksViewModel?.dispatch(
            SharedTasksRouteIntent.ObserveFreshness(
                token = tasksFreshnessToken,
                serverId = serverId,
                visible = active,
            )
        )
    }
    DisposableEffect(tasksViewModel, tasksFreshnessToken) {
        onDispose {
            tasksViewModel?.dispatch(SharedTasksRouteIntent.StopObservingFreshness(tasksFreshnessToken))
        }
    }
    // Every task here is this channel's: one answer for all of them.
    val canWriteTaskChannel: (ChannelId?) -> Boolean = remember(taskWritesEnabled) { { taskWritesEnabled } }
    CompositionLocalProvider(LocalTaskChannelWriteCheck provides canWriteTaskChannel) {
    SharedTasksPanel(
        state = state,
        showHeader = false,
        showChannelFilter = false,
        createTasksChannelId = channelId,
        serverId = serverId,
        identityOptions = identityOptions,
        draftProjection = draftProjection,
        timeContext = timeContext,
        onOpenTask = if (openTaskEnabled) {
            onOpenTask
        } else {
            null
        },
        onIntent = onIntent
    )
    }
}


private fun String.threadTaskAssigneeKey(): String =
    trim()
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '@' || it == '.' || it == '-' || it == '_' }
        .ifBlank { "unknown" }

@Composable
internal fun ThreadJoinFooter(
    label: String,
    onJoin: () -> Unit,
    joining: Boolean = false,
    bottomSafeArea: Dp = raftBottomSafeAreaPadding(),
) {
    // task #176: last element of the page, like the composer it replaces, so it
    // keeps clear of the system navigation bar.
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(RaftColor.Canvas)
            .raftSafeAreaHorizontalPadding()
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = bottomSafeArea.coerceAtLeast(10.dp))
            .raftToastAvoid(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // slock #12426: "Joining…" and disabled while the join is in flight.
        RaftButton(
            text = if (joining) stringResource(Res.string.thread_action_joining) else label,
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !joining,
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.JoinChannel) },
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg
        )
    }
}

// Task #258 (artin msg=bd6a3a0b): the channel/thread inline task chip opens this
// combined panel — task description + status switcher + assignee switcher — so
// assignee editing is reachable from conversation context, not only the board.
// Status rows mirror TasksStatusDialog (#250); the assignee list mirrors the
// board assignee pill's rules (task #244 / web ruling stdrc msg=4c357cb1):
// "unassigned" sentinel always first and never filtered, humans before agents,
// search matches the visible display name.
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun ThreadTaskDetailDialog(
    task: TaskSummary,
    assigneeOptions: List<MemberProfile>,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    onDismiss: () -> Unit,
    onStatusSelect: (String) -> Unit,
    onAssigneeSelect: (MemberProfile?) -> Unit
) {
    val statusLabel = stringResource(Res.string.tasks_label_status)
    val assigneeLabel = stringResource(Res.string.tasks_chip_assignee)
    val creatorLabel = stringResource(Res.string.tasks_chip_creator)
    val unassignedLabel = stringResource(Res.string.tasks_assignee_unassigned)
    val searchPlaceholder = stringResource(Res.string.tasks_search_assignees)
    // Task #258 (artin msg=6f430fed/9c943070/909777d9): status and assignee pills
    // reuse the task-card RaftAnchoredMetaChip (i18n, vertical centering) and are
    // tap-to-open-menu controls — the status pill opens the exact same menu as the
    // thread inline chip (RaftTaskStatusMenuOverlay). Creator shows read-only.
    // Task #258 successor (artin "修窄一点", Mahua/Nathan ruling msg=a2232561/ed8b7d79):
    // the assignee pill toggles an IN-DIALOG expanding section instead of the
    // Tasks-page RaftFilterPopover. The popover opens a second Kuikly native Modal
    // window stacked on the dialog window, and its plain-`clickable` rows are
    // swallowed by the outer dismiss barrier (row taps dismissed the whole stack
    // without selecting; the Tasks page main window is unaffected). Keeping the
    // picker inside the dialog's own window avoids nested-Modal dispatch entirely;
    // the status menu stays on the shared overlay because its Button rows win
    // native dispatch (RaftMenu.kt contract) and already work.
    var statusMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    // Task #258 successor (artin msg=f65282d2): assignee picker is a standalone
    // centered modal, so the flag only tracks open/closed (no popover anchor).
    var assigneeMenuOpen by remember { mutableStateOf<Boolean?>(null) }
    var assigneeMenuSearch by remember { mutableStateOf(TextFieldValue("")) }
    val assigneeRows = threadTaskAssigneeRows(assigneeOptions, assigneeMenuSearch.text)
    val selectedAssigneeId = task.assignee?.tasksIdentityFilterId() ?: TasksUnassignedAssigneeFilterId
    val statusMeta = task.status.sharedTaskStatusChipMeta()
    // Task #258 successor (artin msg=478e8978, web ref): the header shows only
    // the channel identifier (small muted line) and the localized task number —
    // never the task title, which can be too long for the header. The task title
    // and description render inside the body instead.
    val taskNumberLabel = task.taskNumber?.let { number ->
        stringResource(Res.string.tasks_detail_task_identifier, number)
    }
    val headerOverline = task.taskNumber?.let { task.channelName?.takeIf { name -> name.isNotBlank() }?.let { "#$it" } }
    val dialogTitle = taskNumberLabel ?: task.channelName.orEmpty()
    RaftUnifiedDialog(
        title = dialogTitle,
        // Long press copies what is shown: "Task #5" (localized), "#general".
        titleCopyText = dialogTitle.trim().ifEmpty { null },
        uppercaseTitle = false,
        overline = headerOverline,
        overlineCopyText = headerOverline,
        titleMaxLines = 1,
        onDismiss = onDismiss,
        contentSpacing = RaftAppSpacing.Sm
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            item {
                // Task title now lives in the body (header is identifier-only).
                Text(
                    text = task.tasksMeaningfulDisplayTitle(),
                    modifier = Modifier.raftCopyable(task.tasksMeaningfulDisplayTitle()),
                    style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Task #258 (artin msg=9c943070/909777d9): reuse the exact task-card
                    // status/assignee chips (RaftAnchoredMetaChip, i18n labels) with the
                    // label outside the pill, each pair as one wrap unit so labels stay
                    // vertically centered with their chip.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusLabel,
                            style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = RaftColor.TextMutedGray
                        )
                        RaftTaskStatusChip(
                            status = task.status,
                            onClick = { anchor -> statusMenuAnchor = anchor },
                            anchorOwnerIdentity = threadTaskDetailMenuOwnerIdentity(task.id, "status"),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = assigneeLabel,
                            style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = RaftColor.TextMutedGray
                        )
                        RaftAnchoredMetaChip(
                            label = task.assignee?.displayName?.let { "@$it" }
                                ?: unassignedLabel,
                            iconName = null,
                            color = RaftColor.SurfaceWhite,
                            onClick = { _ -> assigneeMenuOpen = true },
                            anchorOwnerIdentity = threadTaskDetailMenuOwnerIdentity(task.id, "assignee"),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .widthIn(max = 160.dp),
                            trailingIconName = RaftLucideIconName.Pencil,
                            minHeight = 24.dp,
                            horizontalPadding = 8.dp,
                            labelFontSize = 12.sp,
                            labelLineHeight = 16.sp,
                            borderWidth = RaftAppStroke.Thin,
                            borderColor = RaftColor.BorderHairline,
                            anchorPlacement = RaftAnchorPlacement.BottomEnd
                        )
                    }
                    task.creator?.let { creator ->
                        // Same 24dp chip height as the status/assignee rows above so all
                        // three wrap units center-align on a shared FlowRow line.
                        Row(
                            modifier = Modifier.height(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = creatorLabel,
                                style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                color = RaftColor.TextMutedGray
                            )
                            Text(
                                text = creator.displayName,
                                style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
                                    .withRaftFontWeight(FontWeight.Bold),
                                color = RaftColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp).raftCopyable(creator.displayName)
                            )
                        }
                    }
                }
            }
            task.description?.trim()?.takeIf { it.isNotBlank() }?.let { description ->
                item {
                    Text(
                        text = description,
                        style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = RaftColor.TextBlack.copy(alpha = 0.65f),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    statusMenuAnchor?.let { anchor ->
        RaftTaskStatusMenuOverlay(
            anchor = anchor,
            items = threadTaskStatusMenuItems(task.statusMenuChoices()),
            selectedId = statusMeta.key,
            onDismiss = { statusMenuAnchor = null },
            onSelect = { selectedId ->
                statusMenuAnchor = null
                onStatusSelect(selectedId)
            }
        )
    }
    assigneeMenuOpen?.let { open ->
        // Task #258 successor (artin msg=ed597ee6/f65282d2): standalone centered
        // assignee modal reusing the channel add-member interaction code. Row
        // shaping (unassigned sentinel first, then agents/humans sections) stays
        // here; search value resets when the modal closes or a row applies.
        RaftTaskAssigneeMenuOverlay(
            open = open,
            onDismiss = { assigneeMenuOpen = null },
            rows = assigneeRows,
            selectedId = selectedAssigneeId,
            agentStatusResolver = agentStatusResolver,
            searchValue = assigneeMenuSearch,
            onSearchValueChange = { assigneeMenuSearch = it },
            searchPlaceholder = searchPlaceholder,
            modalTitle = stringResource(Res.string.tasks_assignee_picker_title),
            unassignedLabel = unassignedLabel,
            humansHeaderLabel = stringResource(Res.string.thread_members_humans),
            agentsHeaderLabel = stringResource(Res.string.thread_members_agents),
            onSelect = { selectedId ->
                assigneeMenuOpen = null
                assigneeMenuSearch = TextFieldValue("")
                if (selectedId != selectedAssigneeId) {
                    // No member matches the unassigned sentinel → onAssigneeSelect(null).
                    onAssigneeSelect(
                        assigneeOptions.firstOrNull { it.tasksIdentityFilterId() == selectedId }
                    )
                }
            }
        )
    }
}

internal fun threadTaskDetailMenuOwnerIdentity(taskId: TaskId, slot: String): String =
    "thread_task_detail:${taskId.value}:$slot"

/**
 * Stable anchor-owner identity for the task detail dialog's status/assignee
 * popover anchors, so multiple concurrent overlays stay scoped per task slot.
 */
