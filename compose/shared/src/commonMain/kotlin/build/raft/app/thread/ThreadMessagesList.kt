package build.raft.app

import build.raft.app.nav.LocalRaftStackPageLeaving
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftLocatedMillis
import build.raft.app.RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY
import build.raft.app.data.ThreadFindIntent
import build.raft.app.base.Utils
import build.raft.app.data.RaftEventBus
import build.raft.app.data.map
import build.raft.app.data.SharedAdaptiveScrollAnchor
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStore
import build.raft.app.model.MemberProfile
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.RaftMarkdownDocumentWarmContext
import build.raft.app.markdown.RaftMarkdownDocumentWarmRequest
import build.raft.app.markdown.prepareRaftMarkdownFullRendererBodies
import build.raft.app.markdown.warmRaftMarkdownRichTextDocuments
import build.raft.app.model.TaskStatus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import build.raft.app.compat.ui.keyboardDismissModeInteractiveIOS
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

// raft-ui located highlight (0.22.1 #719): the jumped-to row is located for LOCATED_MS.
private const val ThreadFocusedMessageHighlightDurationMs = RaftLocatedMillis.toLong()
/** Unread-thread jump (task #73): the least space kept below the parent's replies summary block. */
private val ThreadRepliesFocusMinBottomReserve = 96.dp
/**
 * task #346: state payload of [ThreadMessagesList]. See RaftStructuralHomeShellData for why
 * the flat parameter list had to be grouped (Android dex verifier rejects the
 * un-minified giant composable frame on API 34/36 builds).
 */
private data class ThreadMessagesListData(
    val threadInstanceKey: String,
    val routeActive: Boolean,
    val hostForeground: Boolean,
    val renderConfig: ThreadTimelineRenderConfig,
    val messageBodyTextStyle: TextStyle,
    val timelineSlice: ThreadTimelineSlice,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    val activeReplyDraftParentMessageIds: Set<String>,
    val composerKeyboardInsetDp: Float,
    val linkedTasksProjection: ThreadLinkedTasksProjection, // Task #258: server-scoped identity directory for the inline task detail // panel's assignee row (same pool the thread Tasks tab filters use).,
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
    /** A4 destination contract: non-null marks this list as a detail-pane child. */
    val adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
    val adaptiveStoreScope: RaftShellStoreScope,
    val adaptiveEventBus: RaftEventBus?,
    /** Shared READY-posted flag owned by ThreadChatTabContent (A4 detail contract). */
    val adaptiveReadyPosted: MutableState<Boolean>?,
)

/** task #346: callback payload of [ThreadMessagesList]. See ThreadMessagesListData. */
private class ThreadMessagesListCallbacks(
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

@Composable
internal fun ThreadMessagesList(
    threadInstanceKey: String,
    routeActive: Boolean,
    hostForeground: Boolean,
    renderConfig: ThreadTimelineRenderConfig,
    messageBodyTextStyle: TextStyle,
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    activeReplyDraftParentMessageIds: Set<String>,
    composerKeyboardInsetDp: Float,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    // Task #258: server-scoped identity directory for the inline task detail
    // panel's assignee row (same pool the thread Tasks tab filters use).
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
    adaptiveEventBus: RaftEventBus? = null,
    adaptiveReadyPosted: MutableState<Boolean>? = null
)
 {
    ThreadMessagesListContent(
        modifier = Modifier,
        data =
            ThreadMessagesListData(
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
                adaptiveReadyPosted = adaptiveReadyPosted,
            ),
        callbacks =
            ThreadMessagesListCallbacks(
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
private fun ThreadMessagesListContent(
    modifier: Modifier = Modifier,
    data: ThreadMessagesListData,
    callbacks: ThreadMessagesListCallbacks,
) {
    val threadInstanceKey = data.threadInstanceKey
    val routeActive = data.routeActive
    val hostForeground = data.hostForeground
    val renderConfig = data.renderConfig
    val messageBodyTextStyle = data.messageBodyTextStyle
    // Duplicate item keys crash the LazyColumn; drop them here so every consumer below (keys,
    // indexes, scroll targets) sees the same list. Logged once diagnosticsLogger is in scope.
    val messageListKeyDedupe =
        remember(data.timelineSlice.messages) {
            data.timelineSlice.messages.withUniqueMessageListKeys()
        }
    val timelineSlice =
        remember(data.timelineSlice, messageListKeyDedupe) {
            if (messageListKeyDedupe.droppedKeys.isEmpty()) {
                data.timelineSlice
            } else {
                data.timelineSlice.copy(messages = messageListKeyDedupe.messages)
            }
        }
    val replyMetadataByParentId = data.replyMetadataByParentId
    val activeReplyDraftParentMessageIds = data.activeReplyDraftParentMessageIds
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
    if (messageListKeyDedupe.droppedKeys.isNotEmpty()) {
        LaunchedEffect(messageListKeyDedupe) {
            diagnosticsLogger(
                "thread_message_list_duplicate_keys_dropped level=warn " +
                    "instance=$threadInstanceKey dropped=${messageListKeyDedupe.droppedKeys.size} " +
                    "keys=${messageListKeyDedupe.droppedKeys.distinct().take(5).joinToString(",")}"
            )
        }
    }
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
    // A4 destination side (task #18, spec §9): the ReadyImmediately leg lives in
    // ThreadChatTabContent (empty-state branch never reaches this list); this level owns
    // the RestoreThenReady leg — anchor restore against the real list state, then READY.
    // Both share the same posted flag so READY cannot fire twice.
    val closeReq = adaptiveCloseRequest
    val adaptivePager = LocalActivity.current.getPager() as Pager
    val adaptiveSharedStores = remember(adaptivePager) {
        RaftSharedStoreRegistry.get(Utils.bridgeModule(adaptivePager.pagerId))
    }
    var localAdaptiveReadyPosted by remember(threadInstanceKey) { mutableStateOf(false) }
    val adaptiveReadyPosted = data.adaptiveReadyPosted
    // A4 restore (task #18): while a staged viewport anchor is pending for this destination,
    // the initial bottom-anchor loop must not dispatch FollowLatest — it races the restore and
    // re-pins the viewport to the latest item after scrollToItem already ran (v14 non-empty
    // counterexample: restored mid-list then snapped to A4FX-probe/001). The flag is seeded
    // synchronously at composition — the anchor is staged before the open request posts, so it
    // is already visible to the destination's first frame and the bottom-anchor effect's first
    // iteration sees the restore as in-flight rather than racing it.
    var adaptiveAnchorRestoreInFlight by remember(threadInstanceKey) {
        mutableStateOf(
            run {
                val request = adaptiveCloseRequest ?: return@run false
                val identity =
                    adaptiveSharedStores
                        .adaptiveWorkspaceStore(adaptiveStoreScope)
                        .snapshot()
                        .workspace
                        ?.selection
                        ?.takeIf { sel -> sel.identity.key == request.identityKey }
                        ?.identity
                        ?: return@run false
                adaptiveSharedStores
                    .adaptiveScrollAnchorStore(adaptiveStoreScope)
                    .peek(adaptiveStoreScope.shellInstanceId, identity)
                    ?.let { !it.atLatest }
                    ?: false
            }
        )
    }
    fun postDetailReadyTrace(hop: String, extra: JSONObject.() -> Unit = {}) {
        val request = closeReq ?: return
        // Hop extras must reach the device diagnostics line too — the event-bus trace has no
        // on-disk subscription, so a hop that carries its fields only via `extra` shows its
        // name in the JSONL but not the values (Jony's v14 review point).
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
    if (timelineSlice.messages.isEmpty() && timelineSlice.originStatus == "none") {
        ThreadAfterMotionEffect(threadInstanceKey, timelineSlice.status) {
            onThreadContentReady()
        }
        val emptyHiddenHistory = rememberThreadHiddenHistory(timelineSlice.historyChannelId)
        // Task v2: the head is the conversation's first row, so it stays above its
        // loading or empty state (web: the parent slot over the replies' placeholder).
        TaskConversationHeadAboveState(LocalTaskConversationHeadRow.current) {
        if (
            timelineSlice.status == "loading" ||
            threadEmptyStandInAwaitsFirstLoad(
                status = timelineSlice.status,
                durableLastKnown = timelineSlice.durableLastKnown,
                mainLoadFailed = timelineSlice.mainLoadFailed
            )
        ) {
            if (timelineSlice.loadingIndicatorRevealed) {
                ThreadLoadingState()
            }
        } else if (emptyHiddenHistory != null) {
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
        }
        return
    }
    val imeProgrammaticViewportPolicy =
        raftPlatformThreadImeProgrammaticViewportPolicy()
    val translationState =
        translationStore?.state?.collectAsState()?.value ?: SharedMessageTranslationState()
    if (translationStore != null) {
        LaunchedEffect(threadInstanceKey, translationStore) {
            translationStore.ensureSettingsLoaded(force = true)
        }
        val translationBatchSignature =
            remember(timelineSlice.messages) {
                timelineSlice.messages.joinToString("|") { "${it.id}:${it.body.hashCode()}" }
            }
        LaunchedEffect(
            translationStore,
            translationBatchSignature,
            translationState.settings.available,
            translationState.settings.translationMode,
            translationState.settings.effectiveLanguage
        ) {
            translationStore.requestAutoTranslations(
                timelineSlice.messages
                    .filter { it.isTranslationEligible(translationState.settings) }
                    .map { it.toTranslationCandidate() }
            )
        }
    }
    val messageIndexSignature =
        remember(timelineSlice.messages) {
            timelineSlice.messageIndexSignature()
        }
    val focusedMessageTargetSignature =
        remember(
            timelineSlice.focusedMessageId,
            timelineSlice.originStatus,
            timelineSlice.originMessage?.id,
            messageIndexSignature
        ) {
            timelineSlice.focusedMessageTargetSignature()
        }
    val renderPayload = timelineSlice
    val taskHeadRow = LocalTaskConversationHeadRow.current
    // Web VirtualMessageTimeline sparseAnchor: task conversations start at the top when shorter than
    // the viewport so there is no blank space above the head, while still opening at the newest
    // message once the conversation overflows the viewport.
    val sparseTopAnchored = taskHeadRow != null || renderPayload.taskConversationSurface
    val currentSparseTopAnchoredState = rememberUpdatedState(sparseTopAnchored)
    // Free plan: where the shown history starts, the history paywall replaces "Beginning of conversation".
    val listHiddenHistory = rememberThreadHiddenHistory(renderPayload.historyChannelId)
    val historyPaywall = listHiddenHistory?.takeIf { renderPayload.threadHistoryReachesStart() }
    val peerReadReceipts = LocalThreadPeerReadReceipts.current
    val dmReadStatusMessageId = renderPayload.dmReadStatusMessageId(peerReadReceipts)
    val agentTyping = LocalThreadAgentTyping.current
    val workerConversation = LocalThreadWorkerConversation.current
    // The typing / working / Worker line over the list's end, and the room the end makes for it:
    // typing always wins the shared bottom slot; the Worker line draws when nobody is typing.
    // Not while "Generate image" captures the list.
    val activeWorkerConversation = if (agentTyping == null) workerConversation else null
    val agentTypingLineShown = (agentTyping != null || activeWorkerConversation != null) && !shareSelectionCaptureActive
    // Task #1125: offline stand-in chrome (top inset + tip overlay), see threadOfflineLastKnownChrome.
    val offlineLastKnownChrome =
        threadOfflineLastKnownChrome(
            durableLastKnown = renderPayload.durableLastKnown,
            status = renderPayload.status,
            backgroundRefreshing = renderConfig.backgroundRefreshing,
            mainLoadFailed = renderPayload.mainLoadFailed
        )
    val showReplyLoadingSkeleton =
        renderPayload.messages.isEmpty() &&
            (
                renderPayload.status == "loading" ||
                    threadEmptyStandInAwaitsFirstLoad(
                        status = renderPayload.status,
                        durableLastKnown = renderPayload.durableLastKnown,
                        mainLoadFailed = renderPayload.mainLoadFailed
                    )
            )
    val showReplyEmptyState =
        renderPayload.messages.isEmpty() && renderPayload.originStatus != "none" && !showReplyLoadingSkeleton
    val replyLoadingSkeletonVisible = showReplyLoadingSkeleton && renderPayload.loadingIndicatorRevealed
    // #proj-mobile:#390: Older and newer paging stay parallel in SharedThreadStore while the
    // timeline UI renders at most one boundary loading indicator at any moment. Covers both
    // older-loading branches (canLoadMoreBefore && olderMessagesStatus == "loading" and durable
    // stand-in loading where spec.loading == true) and newer loading (canLoadMoreAfter &&
    // newerMessagesStatus == "loading"). Note: showReplyLoadingSkeleton only runs when
    // messages.isEmpty() (mutually exclusive with older boundary items and store-driven loadNewer),
    // and originStatus == "loading" in ThreadOriginCard is a plain-text parent placeholder (no
    // spinner) rather than a timeline boundary loader.
    val olderBoundaryStatusSpec =
        if (renderPayload.hasThreadHistoryBoundaryItem()) {
            threadOlderMessagesStatusSpec(renderPayload)
        } else {
            null
        }
    val olderBoundaryLoading =
        olderBoundaryStatusSpec?.loading == true &&
            !(renderPayload.canLoadMoreBefore && renderPayload.olderMessagesStatus == "error") &&
            (renderPayload.canLoadMoreBefore || historyPaywall == null)
    val newerBoundaryLoading =
        !replyLoadingSkeletonVisible &&
            renderPayload.canLoadMoreAfter &&
            renderPayload.newerMessagesStatus == "loading"
    val visibleBoundaryLoadingSlot =
        rememberVisibleThreadBoundaryLoadingSlot(
            threadInstanceKey = threadInstanceKey,
            olderLoading = olderBoundaryLoading,
            newerLoading = newerBoundaryLoading,
        )
    val lazyItemCount =
        threadMessagesLazyItemCount(
            originStatus = renderPayload.originStatus,
            showHistoryBoundary = renderPayload.hasThreadHistoryBoundaryItem(),
            messagesCount = renderPayload.messages.size,
            canLoadMoreAfter = renderPayload.canLoadMoreAfter,
            showReplyEmptyState = showReplyEmptyState || showReplyLoadingSkeleton,
            showBottomScrollPadding = true
        )
    // Bottom-anchor signature: only the fields the anchor logic actually
    // reads. The previous remember keys listed the full payload message
    // list reference) which invalidated the cache on any list
    // replacement, even when the content was equivalent. Now the cache
    // is keyed on the content fields the underlying functions read.
    val latestItemIndex = remember(
        renderPayload.originStatus,
        renderPayload.messages.size,
        lazyItemCount
    ) {
        renderPayload.bottomScrollPaddingListItemIndex(lazyItemCount)
    }
    val latestMessageListItemIndex = renderPayload.latestMessageListItemIndex()
    val initialBottomAnchorTargetItemIndex =
        threadInitialBottomAnchorTargetItemIndex(
            latestMessageItemIndex = latestMessageListItemIndex,
            bottomPaddingItemIndex = latestItemIndex
        )
    val threadDensity = LocalDensity.current
    // Resolved once per composition, for the spacer row's height and for the scroll owners' trailing
    // padding below: both read this value, never the line's state themselves.
    val bottomSpacerAndAnchorTrailingHeight =
        threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = agentTypingLineShown)
    val initialBottomAnchorTrailingPaddingPx =
        if (latestMessageListItemIndex >= 0) {
            with(threadDensity) {
                bottomSpacerAndAnchorTrailingHeight.roundToPx()
            }
        } else {
            0
        }
    // Decided once per list instance: re-mapping indices under a live list would be wrong.
    val reverseLayoutRequested = LocalThreadReverseLayout.current
    val reverseLayout = remember(threadInstanceKey) { reverseLayoutRequested }
    // Hands 9e3ca082: this state outlives the LazyColumn below (and its node); the LazyColumn's
    // detachSafeLazyListPrefetch drops a detached node's pending prefetch, which a later scroll
    // would otherwise cancel on that node and crash in requireOwner.
    val initialFocusedItemIndex =
        renderPayload
            .focusedMessageListItemIndex()
            .takeIf { it in 0 until lazyItemCount && !adaptiveAnchorRestoreInFlight }
            ?: -1
    val threadListPrefetch = rememberDetachSafeLazyListPrefetch()
    val rawListState = rememberDetachSafeLazyListState(
        prefetch = threadListPrefetch,
        // Start at the cached focused target when present (#proj-mobile:#258) so Frame 1 never
        // paints the bottom of the conversation and jumps up (`focused_reanchor @ cache_rows_shown`).
        // Reversed lists then align the measured row synchronously before Frame 1 draws via
        // ThreadInitialFocusedPlacementOwner; non-focused opens still start at the latest viewport.
        initialFirstVisibleItemIndex =
            threadInitialFirstVisibleItemIndex(
                reverseLayout = reverseLayout,
                latestItemIndex = latestItemIndex,
                lazyItemCount = lazyItemCount,
                focusedItemIndex = initialFocusedItemIndex,
            )
    )
    val listState = remember(rawListState, reverseLayout) { ThreadListViewport(rawListState, reverseLayout) }
    val allowBottomAutoAnchorState = remember(threadInstanceKey) {
        mutableStateOf(initialFocusedItemIndex < 0)
    }
    var allowBottomAutoAnchor by allowBottomAutoAnchorState
    val initialBottomAnchorReadyState = remember(threadInstanceKey) {
        mutableStateOf(false)
    }
    var initialBottomAnchorReady by initialBottomAnchorReadyState
    // Non-empty destination path (continues the READY chain opened above): restore the
    // staged viewport anchor against this page's message ordering, then post READY. The
    // staged record is deliberately NOT consumed here: the shell consumes it only when the
    // native commit receipt reports `committed`, so a rejected or failed transfer keeps the
    // anchor available for a retry.

    val viewportControllerMutableState = remember(threadInstanceKey) {
        diagnosticsLogger("thread_viewport_state_init key=$threadInstanceKey")
        mutableStateOf(LazyViewportControllerState())
    }
    var viewportControllerState by viewportControllerMutableState
    var viewportLayoutGeneration by remember(threadInstanceKey) { mutableStateOf(0L) }
    val dispatchViewportEvent: (LazyViewportEvent) -> Unit = { event ->
        val previousIntent = viewportControllerState.intent
        viewportControllerState = reduceLazyViewport(viewportControllerState, event)
        if (viewportControllerState.intent != previousIntent) {
            diagnosticsLogger(
                "thread_viewport_intent key=$threadInstanceKey event=${event::class.simpleName} " +
                    "from=${previousIntent.logTag()} to=${viewportControllerState.intent.logTag()}"
            )
        }
    }
    suspend fun awaitViewportCommandSettlement(commandId: LazyViewportCommandId) {
        snapshotFlow {
            viewportControllerState.epoch to viewportControllerState.pendingCommand?.id
        }.first { (epoch, pendingId) ->
            epoch != commandId.epoch || pendingId == null
        }
    }
    LaunchedEffect(closeReq, renderPayload.status, renderPayload.messages.size) {
        val request = closeReq ?: return@LaunchedEffect
        val posted = adaptiveReadyPosted?.value ?: localAdaptiveReadyPosted
        if (posted) return@LaunchedEffect
        if (
            evaluateRaftAdaptiveReadyGate(
                posted,
                renderPayload.status,
                renderPayload.messages.size,
            ) != RaftAdaptiveReadyGate.RestoreThenReady
        ) {
            return@LaunchedEffect
        }
        postDetailReadyTrace("effect_enter")
        val identity =
            adaptiveSharedStores
                .adaptiveWorkspaceStore(adaptiveStoreScope)
                .snapshot()
                .workspace
                ?.selection
                ?.takeIf { sel -> sel.identity.key == request.identityKey }
                ?.identity
            ?: run {
                postDetailReadyTrace("selection_miss")
                if (adaptiveReadyPosted != null) {
                    adaptiveReadyPosted.value = true
                } else {
                    localAdaptiveReadyPosted = true
                }
                return@LaunchedEffect
            }
        val anchorStore = adaptiveSharedStores.adaptiveScrollAnchorStore(adaptiveStoreScope)
        val anchor = anchorStore.peek(adaptiveStoreScope.shellInstanceId, identity)
        // Success = the target message's item is visible AND sits at the staged
        // viewport-relative offset (item.offset, negative when scrolled above the top edge).
        // Comparing the target's own rendered offset — not firstVisibleItemIndex — keeps the
        // contract in one coordinate space and stays correct when the anchor is a partially
        // visible row above the top edge or a fully-visible row below it.
        fun verifyAnchorRestore(
            targetMessageId: String,
            targetIndex: Int,
            expectedItemOffsetPx: Int,
        ): Boolean {
            val settledIntent = viewportControllerState.intent as? LazyViewportIntent.Anchored
            if (settledIntent == null ||
                settledIntent.target != LazyViewportAnchorTarget.Message(targetMessageId)
            ) {
                postDetailReadyTrace("restore_rejected") {
                    put("anchorMessageId", targetMessageId)
                    put("reason", "intent_not_anchored")
                    put("intent", viewportControllerState.intent.logTag())
                }
                return false
            }
            val renderedItem =
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (renderedItem == null) {
                postDetailReadyTrace("restore_rejected") {
                    put("anchorMessageId", targetMessageId)
                    put("reason", "position_mismatch")
                    put("firstVisible", listState.firstVisibleItemIndex.toDouble())
                    put("targetIndex", targetIndex.toDouble())
                }
                return false
            }
            // 4px matches the focused-anchor session drift tolerance.
            if (kotlin.math.abs(renderedItem.offset - expectedItemOffsetPx) > 4) {
                postDetailReadyTrace("restore_rejected") {
                    put("anchorMessageId", targetMessageId)
                    put("reason", "offset_mismatch")
                    put("firstVisibleOffset", renderedItem.offset.toDouble())
                    put("expectedOffsetPx", expectedItemOffsetPx.toDouble())
                }
                return false
            }
            return true
        }
        if (anchor != null && !anchor.atLatest) {
            adaptiveAnchorRestoreInFlight = true
            val firstMessageIndex = renderPayload.firstMessageListItemIndex()
            val locatedIndex =
                anchor.messageId
                    ?.let { id -> renderPayload.messages.indexOfFirst { m -> m.id == id } }
                    ?.takeIf { it >= 0 }
                    ?.let { it + firstMessageIndex }
            if (locatedIndex != null) {
                postDetailReadyTrace("layout_wait_begin") {
                    put("anchorMessageId", anchor.messageId)
                    put("locatedIndex", locatedIndex.toDouble())
                }
                listState.awaitThreadMessagesLayoutFrame()
                postDetailReadyTrace("layout_wait_end")
                // Take viewport ownership through the controller, not a bare scroll: with intent
                // left at FollowingLatest, the next composer_inset_relayout replays
                // Mutation.InsetChange and yanks the restored viewport back to bottom. Anchored
                // (ExactOffset) makes later relayouts re-assert this message+offset instead
                // (forward list only; a reversed list keeps its rendered position, see
                // resolveThreadComposerInsetViewportAction).
                // Offset contract: anchor.messageOffsetPx is the captured LazyList item.offset
                // (viewport-relative, negative when scrolled above the top edge). The command
                // executor runs scrollToItem(index, -target.offset), so the event must carry the
                // raw item.offset — negating here would scroll the target BELOW the top and
                // surface the previous row as firstVisible (Jony r18: staged -37 → readback 17).
                viewportLayoutGeneration += 1L
                listState.noteDispatchOwner(ThreadViewportOwnerTag.AdaptiveRestore)
                dispatchViewportEvent(
                    LazyViewportEvent.Anchor(
                        target = LazyViewportAnchorTarget.Message(requireNotNull(anchor.messageId)),
                        offset = anchor.messageOffsetPx,
                        layoutGeneration = viewportLayoutGeneration,
                        placement = LazyViewportAnchorPlacement.ExactOffset,
                        fallback = LazyViewportAnchorFallback.Free
                    )
                )
                viewportControllerState.pendingCommand?.id?.let { awaitViewportCommandSettlement(it) }
                // Settlement returning is not proof of success: AnchorUnavailable settles too
                // (epoch bump + pending cleared, intent degrades to UserDragging). Gate READY on
                // intent still Anchored on the target and the readback actually showing it.
                // firstVisibleItemScrollOffset is always non-negative = -item.offset.
                if (
                    !verifyAnchorRestore(
                        requireNotNull(anchor.messageId),
                        locatedIndex,
                        anchor.messageOffsetPx,
                    )
                ) {
                    return@LaunchedEffect
                }
                postDetailReadyTrace("restore_applied") {
                    put("firstVisible", listState.firstVisibleItemIndex.toDouble())
                    put("firstVisibleOffset", listState.firstVisibleItemScrollOffset.toDouble())
                    put("anchorOffsetPx", anchor.messageOffsetPx.toDouble())
                    put("visibleItems", listState.layoutInfo.visibleItemsInfo.size.toDouble())
                    put("totalItems", listState.layoutInfo.totalItemsCount.toDouble())
                }
            } else if (
                anchor.fallbackIndex >= 0 &&
                anchor.fallbackIndex < (listState.layoutInfo.totalItemsCount.takeIf { it > 0 } ?: Int.MAX_VALUE)
            ) {
                postDetailReadyTrace("layout_wait_begin") {
                    put("anchorMessageId", "miss")
                    put("fallbackIndex", anchor.fallbackIndex.toDouble())
                }
                listState.awaitThreadMessagesLayoutFrame()
                postDetailReadyTrace("layout_wait_end")
                val fallbackMessageId =
                    renderPayload.messages
                        .getOrNull(anchor.fallbackIndex - firstMessageIndex)
                        ?.id
                if (fallbackMessageId != null) {
                    // Same ownership transfer as the located branch: intent must leave
                    // FollowingLatest or a later inset relayout replays Latest and yanks the
                    // restored viewport to bottom.
                    viewportLayoutGeneration += 1L
                    dispatchViewportEvent(
                        LazyViewportEvent.Anchor(
                            target = LazyViewportAnchorTarget.Message(fallbackMessageId),
                            offset = anchor.messageOffsetPx,
                            layoutGeneration = viewportLayoutGeneration,
                            placement = LazyViewportAnchorPlacement.ExactOffset,
                            fallback = LazyViewportAnchorFallback.Free
                        )
                    )
                    viewportControllerState.pendingCommand?.id?.let { awaitViewportCommandSettlement(it) }
                    if (
                        !verifyAnchorRestore(
                            fallbackMessageId,
                            anchor.fallbackIndex,
                            anchor.messageOffsetPx,
                        )
                    ) {
                        return@LaunchedEffect
                    }
                } else {
                    // No durable message identity at the fallback index — a bare scroll would
                    // leave intent at FollowingLatest and the next inset relayout would yank
                    // the viewport to bottom. Hold READY (same contract as restore_skipped):
                    // readyTimeout cancels the adoption and keeps source + anchor for retry.
                    postDetailReadyTrace("restore_skipped") {
                        put("anchorMessageId", anchor.messageId ?: "null")
                        put("fallbackIndex", anchor.fallbackIndex.toDouble())
                        put("reason", "no_message_at_fallback_index")
                        put("totalItems", listState.layoutInfo.totalItemsCount.toDouble())
                    }
                    return@LaunchedEffect
                }
                postDetailReadyTrace("restore_applied") {
                    put("restoreBasis", "fallback_index")
                    put("firstVisible", listState.firstVisibleItemIndex.toDouble())
                    put("firstVisibleOffset", listState.firstVisibleItemScrollOffset.toDouble())
                    put("anchorOffsetPx", anchor.messageOffsetPx.toDouble())
                    put("visibleItems", listState.layoutInfo.visibleItemsInfo.size.toDouble())
                    put("totalItems", listState.layoutInfo.totalItemsCount.toDouble())
                }
            } else {
                // Jony's v15 boundary: a staged anchor whose message is absent AND whose
                // fallback is out of range means the destination cannot honor the transfer —
                // READY would lie ("restored") and authorize the native commit that pops the
                // source. Hold READY; the caller's readyTimeout cancels the adoption and keeps
                // the source page + staged anchor for an explicit retry. Leave
                // adaptiveAnchorRestoreInFlight set so the bottom-anchor loop cannot claim the
                // viewport while the handoff is still pending.
                postDetailReadyTrace("restore_skipped") {
                    put("anchorMessageId", anchor.messageId ?: "null")
                    put("fallbackIndex", anchor.fallbackIndex.toDouble())
                    put("totalItems", listState.layoutInfo.totalItemsCount.toDouble())
                }
                return@LaunchedEffect
            }
            // The restore owns the viewport now: mark the initial bottom anchor settled and
            // disable auto-bottom so no in-loop FollowLatest or later auto-correction re-pins
            // the viewport to latest (in-flight coroutine exits on the next iteration).
            initialBottomAnchorReady = true
            allowBottomAutoAnchor = false
            adaptiveAnchorRestoreInFlight = false
        } else if (anchor != null) {
            // atLatest=true means the staged anchor sits at the bottom already — the default
            // bottom-anchor path can own the viewport without fighting the restore.
            adaptiveAnchorRestoreInFlight = false
        }
        if (adaptiveReadyPosted != null) {
            adaptiveReadyPosted.value = true
        } else {
            localAdaptiveReadyPosted = true
        }
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
    var measuredThreadViewportHeightDp by remember(threadInstanceKey) { mutableStateOf(0f) }
    val viewportMeasurementReady = threadViewportMeasurementReady(measuredThreadViewportHeightDp)
    // Beyond-bounds budget of about one viewport, from the rows actually measured (0 = not yet).
    // Written only when the resulting item count changes, so scrolling does not churn this scope.
    var measuredListBeyondBoundsItems by remember(threadInstanceKey) { mutableStateOf(0) }
    LaunchedEffect(threadInstanceKey, listState, threadDensity.density) {
        val density = threadDensity.density
        var smoothedRowHeightPx = 0f
        snapshotFlow {
            threadAverageVisibleRowHeightPx(listState.raw.layoutInfo.visibleItemsInfo) to measuredThreadViewportHeightDp
        }
            .distinctUntilChanged()
            .collect { (averageRowHeightPx, viewportHeightDp) ->
                if (averageRowHeightPx <= 0f || density <= 0f) return@collect
                smoothedRowHeightPx = threadSmoothedRowHeightPx(smoothedRowHeightPx, averageRowHeightPx)
                val next =
                    threadMeasuredBeyondBoundsItemCount(
                        viewportHeightDp = viewportHeightDp,
                        averageRowHeightDp = smoothedRowHeightPx / density
                    )
                if (next != measuredListBeyondBoundsItems) measuredListBeyondBoundsItems = next
            }
    }
    val threadLazyWindowConsumerCounts =
        threadLazyWindowConsumerCounts(
            itemCount = lazyItemCount,
            viewportHeightDp = measuredThreadViewportHeightDp,
            measuredListBeyondBoundsItems = measuredListBeyondBoundsItems
        )
    val coroutineScope = rememberCoroutineScope()
    val imeTailPixelProbeRunner =
        remember(threadInstanceKey, coroutineScope) {
            ThreadImeTailPixelProbeRunner(coroutineScope)
        }
    val duplicateMessageRandomIds =
        remember(renderPayload.messages) {
            renderPayload.messages.threadDuplicateRandomIds()
        }
    // Phase 1 (#172) instrumentation: per-Thread session marker that
    // uniquely identifies this Thread entry. Combined with the row-level
    // `LaunchedEffect(message.id, sessionMarker)` inside `items()`, this
    // yields exactly one log per row per Thread session, scoped to a
    // single session so later Threads do not see stale logs.
    val threadRowProbeSessionMarker = remember(threadInstanceKey) {
        ThreadRowProbeNextSessionId.value()
    }
    val firstViewportProbe = remember(threadInstanceKey) {
        ThreadFirstViewportProbe(threadRowProbeSessionMarker)
    }
    val imeTailDrawProbe = remember(threadInstanceKey) { ThreadImeTailDrawProbe() }
    val viewportPixelProbeBounds = remember(threadInstanceKey) { ThreadViewportPixelProbeBounds() }
    val firstViewportProbeActiveState = remember(threadInstanceKey) { mutableStateOf(true) }
    val postFirstFrameBeyondBoundsEnabledRef = remember(threadInstanceKey) { ThreadMutableBooleanRef(false) }
    val menuState = remember(threadInstanceKey) { ThreadMessageMenuState() }
    // Only the user's own scroll closes the author popover: a programmatic scroll such as the
    // reading-anchor correction (ThreadReadingAnchorKeeper.kt) after a Save or a reaction must not.
    ThreadUserScrollEffect(listState.raw) {
        if (menuState.authorDescriptionPopover != null) menuState.authorDescriptionPopover = null
    }

    val userScrollGeneration = remember(threadInstanceKey) { ThreadUserScrollGeneration() }
    // Expand reveal (ThreadExpandReveal.kt, artin dm:@artin:#151): after a user expand keeps its
    // control in place, this owner smoothly brings the opened content into view.
    val expandRevealOwner = remember(threadInstanceKey) { ThreadExpandRevealOwner() }
    // Long-message collapse: expanded ids, one-way collapsible latch and threshold live for the
    // whole thread open, so a row keeps its state when it scrolls away and back.
    val longMessageCollapseController =
        remember(threadInstanceKey) {
            RaftLongMessageCollapseController().apply {
                autoExpand(renderPayload.focusedMessageId)
            }
        }
    LaunchedEffect(longMessageCollapseController, renderPayload.focusedMessageId, renderPayload.focusedMessageRequestNonce) {
        // A jump to a message shows it in full (its target may sit in the clipped part).
        longMessageCollapseController.autoExpand(renderPayload.focusedMessageId)
    }
    val longMessageItemHeights = remember(threadInstanceKey) { mutableMapOf<String, Int>() }
    val longMessageToggleRowPx = with(LocalDensity.current) { RaftLongMessageCollapseToggleRowDp.dp.roundToPx() }
    val recordMessageItemHeight: (String, Int) -> Unit = { messageId, height ->
        val previousHeight = longMessageItemHeights.put(messageId, height)
        if (previousHeight != null && previousHeight != height) {
            viewportLayoutGeneration += 1L
            dispatchViewportEvent(
                LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange)
            )
        }
    }
    // Direct user action (docs/programmatic-scroll-ownership-contract.md): the toggled message's
    // top stays put and the change grows/shrinks downward. On the reversed list the message
    // becomes the reading anchor (`keepTappedRowForToggle` -> `keepReadingAnchorFor`, set below:
    // declared after this toggle); the forward list, and a collapse of a message whose top is
    // above the viewport (its top is brought back to the viewport top), request the raw position
    // in the same frame as the height change, and the viewport controller is re-anchored on the
    // message so later HeightChange replays agree.
    // Fences: an active scroll owns the viewport; a collapse while following the latest row
    // leaves bottom ownership alone.
    var keepTappedRowForLongMessage: (String, Int, Boolean) -> Unit = { _, _, _ -> }
    val toggleLongMessage: (String, Int) -> Unit = { messageId, listItemIndex ->
        val expanding = !longMessageCollapseController.isExpanded(messageId)
        val heightDelta = longMessageCollapseController.toggleHeightDelta(messageId, expanding)
        val forwardItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listItemIndex }
        val rawCount = listState.raw.layoutInfo.totalItemsCount
        val rawIndex = if (reverseLayout) reversedThreadListIndex(listItemIndex, rawCount) else listItemIndex
        val rawItem = listState.raw.layoutInfo.visibleItemsInfo.firstOrNull { it.index == rawIndex }
        longMessageCollapseController.setExpanded(messageId, expanding)
        if (forwardItem != null && !listState.isScrollInProgress) {
            listState.noteUserNavigation()
        }
        val ownsViewport =
            forwardItem != null &&
                rawItem != null &&
                !listState.isScrollInProgress &&
                (expanding || viewportControllerState.intent != LazyViewportIntent.FollowingLatest)
        val keptByReadingAnchor =
            ownsViewport && forwardItem != null && reverseLayout && (expanding || forwardItem.offset >= 0)
        if (keptByReadingAnchor && forwardItem != null) {
            keepTappedRowForLongMessage(messageId, forwardItem.offset, expanding)
        } else if (ownsViewport && forwardItem != null && rawItem != null) {
            val plan =
                raftLongMessageToggleScrollPlan(
                    reverseLayout = reverseLayout,
                    expanding = expanding,
                    forwardIndex = listItemIndex,
                    totalItemsCount = rawCount,
                    itemTopBefore = forwardItem.offset,
                    rawItemOffsetBefore = rawItem.offset,
                    heightDelta = heightDelta
                )
            plan.rawScrollRequest?.let { request ->
                listState.raw.requestScrollToItem(request.rawIndex, request.scrollOffset)
                listState.noteRawViewportWrite(ThreadViewportOwnerTag.LongMessageToggle)
            }
            dispatchViewportEvent(
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message(messageId),
                    offset = plan.desiredTop,
                    layoutGeneration = viewportLayoutGeneration,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        }
        if (expanding && forwardItem != null && !listState.isScrollInProgress) {
            // The opened text starts where the "Show more" control was (the clipped body's end).
            expandRevealOwner.request(
                firstMessageId = messageId,
                controlTopInRow = (forwardItem.size - longMessageToggleRowPx).coerceAtLeast(0),
                rowSizeBefore = forwardItem.size,
                userScrollGeneration = userScrollGeneration.value,
            )
        } else if (!expanding) {
            expandRevealOwner.cancel()
        }
        diagnosticsLogger(
            "thread_long_message_toggle messageId=${messageId.take(8)} expanded=$expanding " +
                "delta=$heightDelta anchored=$ownsViewport reverse=$reverseLayout"
        )
    }

    // Jump pills (ThreadJumpPills.kt). Page-local viewport state, like the back-to-bottom guard.
    var newMessagesBelow by remember(threadInstanceKey) { mutableStateOf(ThreadNewMessagesBelow()) }
    var unreadThreadJumpDismissedAtCount by remember(threadInstanceKey) { mutableStateOf<Int?>(null) }
    var visibleMessageIndexes by remember(threadInstanceKey) { mutableStateOf<IntRange?>(null) }
    val jumpParentCandidates =
        remember(renderPayload.messages, replyMetadataByParentId) {
            threadJumpParentCandidates(renderPayload.messages, replyMetadataByParentId)
        }
    val unreadThreadIds =
        remember(jumpInputs, jumpParentCandidates) {
            if (!jumpInputs.unreadThreadPillEnabled) {
                emptySet()
            } else {
                threadConversationUnreadThreadIds(
                    channelId = jumpInputs.channelId.orEmpty(),
                    homeFollowedThreadUnread = jumpInputs.homeFollowedThreadUnread,
                    loadedMessages = jumpParentCandidates,
                )
            }
        }
    // Web useMessagePlacement: which loaded messages are in view. Tracked only while the channel
    // has unread threads; the range changes only when a row crosses the viewport edge.
    val trackVisibleMessages = unreadThreadIds.isNotEmpty()
    LaunchedEffect(threadInstanceKey, trackVisibleMessages, renderPayload.messages.size, renderPayload.firstMessageListItemIndex()) {
        if (!trackVisibleMessages) {
            if (visibleMessageIndexes != null) visibleMessageIndexes = null
            return@LaunchedEffect
        }
        snapshotFlow {
            val info = listState.layoutInfo
            threadVisibleMessageIndexRange(
                rows = info.visibleItemsInfo.map { item -> ThreadJumpVisibleRow(item.index, item.offset, item.size) },
                viewportStart = info.viewportStartOffset,
                viewportEnd = info.viewportEndOffset,
                firstMessageListItemIndex = renderPayload.firstMessageListItemIndex(),
                messageCount = renderPayload.messages.size,
            )
        }
            .distinctUntilChanged()
            .collect { range -> if (range != visibleMessageIndexes) visibleMessageIndexes = range }
    }
    var backToBottomInteractionGuardActive by remember(threadInstanceKey) {
        mutableStateOf(false)
    }
    // Back to Bottom is loading the newest messages (the loaded window ends before them): the
    // button shows it (raft-ui busy pill) until they land or the load fails.
    val backToBottomLoadingState = remember(threadInstanceKey) { mutableStateOf(false) }
    var backToBottomLoading by backToBottomLoadingState
    var latestLocalSendBottomAnchorActiveKey by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var latestLocalSendBottomAnchorStartUserScrollGeneration by remember(threadInstanceKey) { mutableStateOf(0) }
    // Reading anchor (artin #proj-mobile #201, ThreadReadingAnchorKeeper.kt): while the reader is
    // not following the latest row, a visible row that changes height in place (Saved marker,
    // reactions, edits, cards and images resolving) no longer moves the row being read. The one
    // owner of that case; it runs after each layout pass, before the frame is drawn.
    val readingAnchorOwner = remember(listState) { ThreadReadingAnchorOwner(listState, coroutineScope) }
    val readingAnchorPayloadState = rememberUpdatedState(renderPayload)
    // Isolate LocalRaftStackPageLeaving inside a leaf composable so starting a pop slide never
    // recomposes the full ThreadMessagesListContent scope.
    val stackPageLeavingRef = remember(threadInstanceKey) { ThreadMutableBooleanRef(false) }
    ThreadPageLeavingTracker(stackPageLeavingRef)
    val readingAnchorPlacementSettledState =
        rememberUpdatedState(initialBottomAnchorReady && !shareSelectionCaptureActive && !adaptiveAnchorRestoreInFlight)
    val keepReadingAnchorAfterLayout: () -> Unit =
        remember(readingAnchorOwner) {
            {
                readingAnchorOwner.afterLayout(
                    owns = {
                        threadReadingAnchorOwns(
                            reverseLayout = listState.reverse,
                            followingLatest = viewportControllerState.intent == LazyViewportIntent.FollowingLatest,
                            scrollInProgress = listState.isScrollInProgress,
                            ownerLive = !listState.isRetired && !stackPageLeavingRef.value,
                            placementSettled = readingAnchorPlacementSettledState.value,
                        )
                    },
                    preferredMessageId = {
                        ((viewportControllerState.intent as? LazyViewportIntent.Anchored)?.target as? LazyViewportAnchorTarget.Message)
                            ?.messageId
                    },
                    messageIdAt = { index -> readingAnchorPayloadState.value.threadMessageIdAtListItemIndex(index) },
                )
            }
        }
    // The row whose control the user just tapped becomes the reading anchor (the API the long-message
    // expand/reveal path and any other in-place row action call): the viewport controller is
    // anchored on its current top, so the keeper and the controller's HeightChange replay agree.
    val keepReadingAnchorFor: (String) -> Unit =
        remember(readingAnchorOwner) {
            { messageId ->
                val index = readingAnchorPayloadState.value.threadMessageListItemIndex(messageId)
                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (
                    item != null &&
                    listState.reverse &&
                    !listState.isScrollInProgress &&
                    viewportControllerState.intent != LazyViewportIntent.FollowingLatest
                ) {
                    readingAnchorOwner.keeper.anchorOn(messageId)
                    dispatchViewportEvent(
                        LazyViewportEvent.Anchor(
                            target = LazyViewportAnchorTarget.Message(messageId),
                            offset = item.offset,
                            layoutGeneration = viewportLayoutGeneration,
                            fallback = LazyViewportAnchorFallback.Free
                        )
                    )
                }
            }
        }
    val latestOnThreadEventState = rememberUpdatedState(onThreadEvent)
    // Row actions that change the tapped row in place keep that row as the reading anchor.
    val onThreadEventKeepingAnchor =
        remember(keepReadingAnchorFor) {
            ThreadEventHandler { action, itemId, attachmentId, tab, markdownActionId, threadChannelId ->
                if (itemId != null && action in ThreadReadingAnchorRowActions) keepReadingAnchorFor(itemId)
                latestOnThreadEventState.value.invoke(action, itemId, attachmentId, tab, markdownActionId, threadChannelId)
            }
        }
    // A4 adoption (task #18): the viewport anchor provider is registered here — the only
    // place listState/timelineSlice/userScrollGeneration coexist. The page-level initiator
    // calls it under the request fence; a null holder (embedded detail child, or A4 gate
    // off upstream) leaves this a no-op.
    val adaptiveInitiationCaptureHolder = LocalThreadAdaptiveInitiationCapture.current
    // timelineSlice recomposes per frame; the provider must always see the latest slice.
    val latestTimelineSlice by rememberUpdatedState(timelineSlice)
    DisposableEffect(adaptiveInitiationCaptureHolder, threadInstanceKey) {
        val holder = adaptiveInitiationCaptureHolder
        if (holder != null) {
            holder.captureAnchor = anchor@{ requestId ->
                val first =
                    listState.threadFirstVisibleMessageAnchor(
                        timelineSlice = latestTimelineSlice,
                        userScrollGeneration = userScrollGeneration.value,
                    ) ?: return@anchor null
                // atLatest must reflect the viewport's settled intent, not instantaneous
                // geometry: during a resize/rotation frame `canScrollForward` can read
                // transiently in EITHER direction — true while FollowingLatest at bottom
                // (Jony r19 atLatest miss), or false while Anchored mid-list (which would
                // wrongly upgrade a mid anchor to follow-latest). The controller intent is
                // the only stable signal for both directions.
                val atLatest =
                    viewportControllerState.intent is LazyViewportIntent.FollowingLatest
                SharedAdaptiveScrollAnchor(
                    requestId = requestId,
                    messageId = first.messageId,
                    messageOffsetPx = first.offset,
                    fallbackIndex = listState.firstVisibleItemIndex,
                    atLatest = atLatest,
                    capturedAtMillis = defaultNowMillis(),
                )
            }
        }
        onDispose {
            if (holder != null) holder.captureAnchor = null
        }
    }
    var lastComposerKeyboardInsetDp by remember(threadInstanceKey) { mutableStateOf(composerKeyboardInsetDp) }
    // task #990 kill-switch: the kill-switch gates run inside long-lived
    // coroutines (the focused session's watch loop, the origin correction's
    // layout waits) that outlive the recomposition which delivered a new inset.
    // A captured Float parameter stays 0 forever inside them — which is exactly
    // the incident timing, where the keyboard rises milliseconds after the
    // session starts. The gates therefore read this state, whose value is live
    // at every read.
    val liveComposerKeyboardInsetDpState = rememberUpdatedState(composerKeyboardInsetDp)
    val liveComposerKeyboardInsetDp by liveComposerKeyboardInsetDpState
    var lastThreadViewportHeightDp by remember(threadInstanceKey) { mutableStateOf(measuredThreadViewportHeightDp) }
    var pendingComposerLayoutBottomAnchor by remember(threadInstanceKey) { mutableStateOf(false) }
    var composerLayoutViewportRetryGeneration by remember(threadInstanceKey) { mutableStateOf(0L) }
    var pendingKeyboardBottomAnchor by remember(threadInstanceKey) { mutableStateOf(false) }
    val activeFocusedMessageIdState = remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var activeFocusedMessageId by activeFocusedMessageIdState
    val focusedAnchorState = remember(threadInstanceKey) { ThreadFocusedAnchorState() }
    var consumedFocusedMessageAnchorIdentity by focusedAnchorState::consumedFocusedMessageAnchorIdentity
    var scrollInterruptedFocusedMessageAnchorIdentity by focusedAnchorState::scrollInterruptedFocusedMessageAnchorIdentity
    var focusedAnchorSessionIdentity by focusedAnchorState::focusedAnchorSessionIdentity
    var focusedAnchorSessionElapsedMs by focusedAnchorState::focusedAnchorSessionElapsedMs
    var focusedAnchorSessionReanchors by focusedAnchorState::focusedAnchorSessionReanchors
    var focusedAnchorStartUserScrollGeneration by focusedAnchorState::focusedAnchorStartUserScrollGeneration
    var correctedFocusedOriginIdentity by focusedAnchorState::correctedFocusedOriginIdentity
    var focusedAnchorPlacementState by focusedAnchorState::focusedAnchorPlacementState
    var bottomAnchorFocusedYieldElapsedMs by focusedAnchorState::bottomAnchorFocusedYieldElapsedMs
    val pendingFocusedMessageAnchorIdentityState =
        rememberUpdatedState(
            renderPayload.focusedMessageId
                ?.takeIf { it.isNotBlank() }
                ?.let { messageId ->
                    focusedMessageAnchorIdentity(
                        messageId = messageId,
                        requestNonce = renderPayload.focusedMessageRequestNonce
                    )
                }
        )
    // Cached focused open pre-first-draw placement (#proj-mobile:#258 / 1.30.0 P1-5): align the
    // focused row on a reversed list before Frame 1 draws so the initial paint lands directly on
    // the target (also stays armed when a reply thread paints `originMessage` before cached replies
    // arrive). Reads live snapshot state directly (and user takeover / focus retirement guards) so
    // a user drag or host settlement between recompositions permanently retires the owner.
    val initialFocusedAnchorIdentity =
        remember(threadInstanceKey) {
            renderPayload.focusedMessageId
                ?.takeIf { it.isNotBlank() }
                ?.let { messageId ->
                    focusedMessageAnchorIdentity(
                        messageId = messageId,
                        requestNonce = renderPayload.focusedMessageRequestNonce
                    )
                }
        }
    val initialFocusedPlacementOwner =
        remember(threadInstanceKey, listState) {
            ThreadInitialFocusedPlacementOwner(
                listState = listState,
                scope = coroutineScope,
                armed =
                    shouldArmThreadInitialFocusedPlacement(
                        reverseLayout = reverseLayout,
                        adaptiveAnchorRestoreInFlight = adaptiveAnchorRestoreInFlight,
                        initialFocusedItemIndex = initialFocusedItemIndex,
                        messagesEmpty = renderPayload.messages.isEmpty(),
                        focusedMessageId = renderPayload.focusedMessageId,
                    ),
            )
        }
    val initialFocusedUserNavigationCount = remember(threadInstanceKey, listState) { listState.userNavigationCount }
    val initialFocusedJumpNonceState = rememberUpdatedState(jumpInputs.jumpFocusNonce)
    val initialFocusedShareCaptureState = rememberUpdatedState(shareSelectionCaptureActive)
    val initialFocusedRepliesMinReservePx =
        with(threadDensity) { ThreadRepliesFocusMinBottomReserve.roundToPx() }
    val alignInitialFocusedAfterLayout: () -> Unit =
        remember(
            initialFocusedPlacementOwner,
            initialFocusedUserNavigationCount,
            initialFocusedRepliesMinReservePx,
            imeProgrammaticViewportPolicy,
        ) {
            {
                initialFocusedPlacementOwner.afterLayout(
                    owns = {
                        !listState.isScrollInProgress &&
                            !listState.isRetired &&
                            !stackPageLeavingRef.value &&
                            !threadImeOpenSuppressesProgrammaticViewport(
                                keyboardInsetDp = liveComposerKeyboardInsetDp,
                                policy = imeProgrammaticViewportPolicy,
                            )
                    },
                    retiredByHost = {
                        initialBottomAnchorReady ||
                            adaptiveAnchorRestoreInFlight ||
                            initialFocusedShareCaptureState.value ||
                            userScrollGeneration.value > 0 ||
                            listState.userNavigationCount != initialFocusedUserNavigationCount ||
                            scrollInterruptedFocusedMessageAnchorIdentity != null ||
                            consumedFocusedMessageAnchorIdentity != null
                    },
                    focusedForwardIndex = { readingAnchorPayloadState.value.focusedMessageListItemIndex() },
                    placement = {
                        threadFocusedAnchorBasePlacement(
                            requestNonce = readingAnchorPayloadState.value.focusedMessageRequestNonce,
                            unreadThreadJumpFocusNonce = initialFocusedJumpNonceState.value,
                        )
                    },
                    minBottomReservePx = initialFocusedRepliesMinReservePx,
                )
            }
        }
    // Open-path jitter tracing (#proj-mobile:#199): only a sampled open installs the recorder;
    // it reads this list's layout once per frame and never scrolls (ThreadOpenTrace.kt).
    ThreadMessagesListOpenTrace(
        openKey = threadInstanceKey,
        slice = renderPayload,
        backgroundRefreshing = renderConfig.backgroundRefreshing,
        listState = listState,
        flags = adaptiveSharedStores.serverFeatureFlags,
        initialServerId = adaptiveStoreScope.initialServerId,
        messageBodyFontSize = messageBodyTextStyle.fontSize.hashCode(),
        log = diagnosticsLogger,
    ) {
        val pendingFocus = pendingFocusedMessageAnchorIdentityState.value
        initialBottomAnchorReady &&
            (
                pendingFocus == null ||
                    pendingFocus == consumedFocusedMessageAnchorIdentity ||
                    pendingFocus == scrollInterruptedFocusedMessageAnchorIdentity
            )
    }
    // Search in this thread (web useThreadSearch): the bar's store reads the whole thread; the page
    // tells it what it holds, and lands on the open match (loading around it when it isn't loaded).
    val threadFind = LocalThreadFindController.current
    val threadFindWindow = remember(renderPayload) { renderPayload.threadFindWindow() }
    LaunchedEffect(threadFind, threadFindWindow) {
        if (threadFind != null && threadFindWindow != null) {
            threadFind.store.dispatch(ThreadFindIntent.Window(threadFindWindow))
        }
    }
    val threadFindState = threadFind?.store?.state?.collectAsState()?.value
    val inlineSearchQuery = threadFindState?.query.orEmpty()
    val activeInlineSearchMatch =
        threadFindState?.activeMessageId?.takeIf { inlineSearchVisible }?.let { messageId ->
            ThreadInlineSearchMatch(messageId, renderPayload.threadMessageListItemIndex(messageId))
        }
    val activeInlineSearchMessageId = activeInlineSearchMatch?.messageId
    // A match the page hasn't loaded: the thread timeline's jump loads around it, once per match.
    var threadFindJumpRequested by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeInlineSearchMatch) {
        val match = activeInlineSearchMatch
        if (match == null || match.listItemIndex >= 0 || match.messageId == renderPayload.originMessage?.id) {
            threadFindJumpRequested = null
            return@LaunchedEffect
        }
        if (threadFindJumpRequested == match.messageId) return@LaunchedEffect
        threadFindJumpRequested = match.messageId
        listState.noteUserNavigation()
        diagnosticsLogger("thread_find_jump messageId=${match.messageId.take(8)}")
        onThreadEvent(ThreadFindJumpAction, match.messageId, null, null)
    }
    // Adjacent system messages fold into one row (ThreadSystemMessageGroups.kt, web
    // systemMessageGrouping). Render-only: every message keeps its own lazy item, key and index.
    // Groups are closed by default; the open ones are kept while this conversation is shown.
    val systemMessageFolds =
        remember(renderPayload.messages) {
            threadSystemMessageFolds(renderPayload.messages)
        }
    // Task hand-off cards (web useThreadTimelineRows `handoffTimeline`, botiverse/slock #15631):
    // who held the task before each hand-off and whether it goes back.
    val taskHandoffTimelineById =
        remember(renderPayload.messages) {
            threadTaskHandoffTimeline(renderPayload.messages)
        }
    val expandedSystemMessageGroupsState = remember(threadInstanceKey) { mutableStateOf(emptySet<String>()) }
    var expandedSystemMessageGroups by expandedSystemMessageGroupsState
    // A jump or a search match on a message folded into a closed group opens the group, so the
    // target is a drawn row (web lands on the group's row). Only state: the focus anchor effect
    // and its growth retries own the scroll. Once per request, so the reader can close it again.
    var openedSystemGroupTargets by remember(threadInstanceKey) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(
        systemMessageFolds,
        renderPayload.focusedMessageId,
        renderPayload.focusedMessageRequestNonce,
        activeInlineSearchMessageId
    ) {
        val targets =
            listOfNotNull(
                renderPayload.focusedMessageId?.let { "focus:$it:${renderPayload.focusedMessageRequestNonce}" to it },
                activeInlineSearchMessageId?.let { "search:$it" to it },
            ).filter { (identity, _) -> identity !in openedSystemGroupTargets }
        val opened = mutableSetOf<String>()
        val groups = mutableSetOf<String>()
        for ((identity, messageId) in targets) {
            val group = systemMessageFolds.threadSystemGroupOf(renderPayload.messages, messageId) ?: continue
            opened += identity
            groups += group
        }
        if (opened.isEmpty()) return@LaunchedEffect
        openedSystemGroupTargets = openedSystemGroupTargets + opened
        expandedSystemMessageGroups = expandedSystemMessageGroups + groups
    }
    // A user expand/collapse of a laid-out row (artin dm:@artin:#151): the row whose control was
    // tapped becomes the reading anchor (`keepReadingAnchorFor`, ThreadReadingAnchorKeeper.kt), so it
    // keeps its top in the frame its content opens or closes and the change grows/shrinks below it;
    // the controller's HeightChange replay targets the same row. An expand while following the
    // latest row first anchors the controller on the tapped row (expanding is a reading action, as
    // on the web), so the keeper owns it; a collapse there leaves bottom ownership alone.
    val keepTappedRowForToggle: (String, Int, Boolean) -> Unit = { messageId, rowTop, expanding ->
        if (expanding && viewportControllerState.intent == LazyViewportIntent.FollowingLatest) {
            dispatchViewportEvent(
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message(messageId),
                    offset = rowTop,
                    layoutGeneration = viewportLayoutGeneration,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        }
        keepReadingAnchorFor(messageId)
    }
    keepTappedRowForLongMessage = keepTappedRowForToggle
    // Direct user action (docs/programmatic-scroll-ownership-contract.md) on the group's summary
    // row: the summary is the reading anchor ([keepTappedRowForToggle]), so the opened/closed rows
    // grow/shrink below it in the frame they change. Before, only a controller anchor was set: the
    // reversed list kept the newest row and moved the summary for a frame until the HeightChange
    // replay put it back, and an opened group stayed below the viewport. An opened group that ends
    // below the viewport is now brought into view by [expandRevealOwner].
    // Fences: the summary row is laid out and no scroll is active.
    val toggleSystemMessageGroup: (String, String, String) -> Unit = { groupId, summaryMessageId, lastMemberId ->
        val expanding = groupId !in expandedSystemMessageGroups
        expandedSystemMessageGroups =
            if (expanding) expandedSystemMessageGroups + groupId else expandedSystemMessageGroups - groupId
        val listItemIndex = renderPayload.threadMessageListItemIndex(summaryMessageId)
        val summaryItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listItemIndex }
        val ownsViewport = listItemIndex >= 0 && summaryItem != null && !listState.isScrollInProgress
        if (ownsViewport && summaryItem != null) {
            listState.noteUserNavigation()
            keepTappedRowForToggle(summaryMessageId, summaryItem.offset, expanding)
        }
        if (expanding && ownsViewport && summaryItem != null) {
            expandRevealOwner.request(
                firstMessageId = summaryMessageId,
                lastMessageId = lastMemberId,
                rowSizeBefore = summaryItem.size,
                userScrollGeneration = userScrollGeneration.value,
            )
        } else if (!expanding) {
            expandRevealOwner.cancel()
        }
        diagnosticsLogger(
            "thread_system_group_toggle messageId=${summaryMessageId.take(8)} expanded=$expanding " +
                "anchored=$ownsViewport reverse=$reverseLayout"
        )
    }
    // A row's own disclosure (a system line's details): direct user action, like the group toggle.
    // Fence: the row is laid out, no scroll is active.
    val requestRowExpandReveal: (String) -> Unit = { messageId ->
        val index = renderPayload.threadMessageListItemIndex(messageId)
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null && !listState.isScrollInProgress) {
            listState.noteUserNavigation()
            keepTappedRowForToggle(messageId, item.offset, true)
            expandRevealOwner.request(
                firstMessageId = messageId,
                rowSizeBefore = item.size,
                userScrollGeneration = userScrollGeneration.value,
            )
        }
    }
    // The expand reveal owner (ThreadExpandReveal.kt): after a user expand, the opened content is
    // scrolled into view as far as it fits under the tapped control. It and the reading anchor
    // keeper do not fight: during the reveal a scroll is in progress, and afterwards the scroll
    // position changed, so the keeper re-reads its anchor instead of correcting. Re-anchors keeper
    // and controller on the row where the reveal left it, or hands the list back to following the
    // latest row when the reveal reached the tail (web: an expansion at the bottom keeps the bottom).
    ThreadExpandRevealEffect(
        owner = expandRevealOwner,
        listState = listState,
        instanceKey = threadInstanceKey,
        listItemIndexOf = { messageId -> renderPayload.threadMessageListItemIndex(messageId) },
        userScrollGeneration = { userScrollGeneration.value },
        controllerSettled = { viewportControllerState.pendingCommand == null },
        topMarginPx = with(LocalDensity.current) { ThreadExpandRevealTopMargin.roundToPx() },
    ) { request, outcome ->
        when (outcome) {
            is ThreadExpandRevealOutcome.Revealed -> {
                if (outcome.delta > 0) {
                    if (outcome.atBottom && !renderPayload.canLoadMoreAfter) {
                        dispatchViewportEvent(LazyViewportEvent.FollowLatest(canOverrideUserDragging = false))
                    } else {
                        // The keeper re-read its anchor when the reveal scrolled; this re-anchors
                        // it and the controller on the tapped row at its new top.
                        keepReadingAnchorFor(request.firstMessageId)
                    }
                }
                diagnosticsLogger(
                    "thread_expand_reveal messageId=${request.firstMessageId.take(8)} delta=${outcome.delta} " +
                        "rowTop=${outcome.rowTop} atBottom=${outcome.atBottom}"
                )
            }
            is ThreadExpandRevealOutcome.Skipped ->
                diagnosticsLogger(
                    "thread_expand_reveal messageId=${request.firstMessageId.take(8)} skipped=${outcome.reason}"
                )
        }
    }
    val activeInlineSearchMessage =
        activeInlineSearchMessageId?.let { messageId ->
            renderPayload.originMessage?.takeIf { it.id == messageId }
                ?: renderPayload.messages.firstOrNull { it.id == messageId }
        }
    // The body as shown: the translation when one is displayed (keys the effect), plus the original.
    val activeInlineSearchShownTranslation =
        if (translationStore != null && activeInlineSearchMessage != null) {
            resolveThreadMessageTranslationUi(activeInlineSearchMessage, translationState)?.displayBody
        } else {
            null
        }
    LaunchedEffect(
        longMessageCollapseController,
        activeInlineSearchMessage?.id,
        activeInlineSearchMessage?.body,
        activeInlineSearchShownTranslation,
        inlineSearchQuery
    ) {
        val message = activeInlineSearchMessage ?: return@LaunchedEffect
        val shownTranslation = activeInlineSearchShownTranslation
        // Search matches are per message; a hit in the body may sit in the clipped part.
        if (listOfNotNull(message.body, shownTranslation).any { body -> raftLongMessageSearchHitInBody(body, inlineSearchQuery) }) {
            longMessageCollapseController.autoExpand(message.id)
        }
    }
    val latestMessageLayoutKey = remember(
        renderPayload.originStatus,
        renderPayload.canLoadMoreBefore,
        renderPayload.canLoadMoreAfter,
        renderPayload.messages.size,
        renderPayload.messages.lastOrNull()?.id,
        renderPayload.messages.lastOrNull()?.body?.length,
        renderPayload.messages.lastOrNull()?.attachments?.size,
        renderPayload.messages.lastOrNull()?.taskId,
        renderPayload.messages.lastOrNull()?.threadReactionLayoutSignature()
    ) {
        renderPayload.latestMessageLayoutKey()
    }
    val latestLocalSendBottomAnchorKey = remember(
        renderPayload.messages.size,
        renderPayload.messages.lastOrNull()?.id,
        renderPayload.messages.lastOrNull()?.deliveryState,
        renderPayload.messages.lastOrNull()?.body?.length,
        renderPayload.messages.lastOrNull()?.attachments?.size,
        renderPayload.messages.lastOrNull()?.pendingSendContext?.attachmentIds?.size
    ) {
        renderPayload.latestLocalSendBottomAnchorKey()
    }
    val revealLatestLocalSendBeforeInitialAnchor =
        shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
            initialBottomAnchorReady = initialBottomAnchorReady,
            latestLocalSendBottomAnchorKey = latestLocalSendBottomAnchorKey,
            latestItemIndex = latestItemIndex,
            messageCount = renderPayload.messages.size
        )
    val markdownTimelineSignature =
        timelineSlice.initialMarkdownPreparseCandidateSignature(contentExtrasVisible)
    val initialMarkdownPreparsePlan =
        remember(
            markdownTimelineSignature,
            focusedMessageTargetSignature,
            contentExtrasVisible
        ) {
            renderPayload.threadInitialMarkdownPreparsePlan(
                contentExtrasVisible = contentExtrasVisible
            )
        }
    LaunchedEffect(
        threadInstanceKey,
        initialMarkdownPreparsePlan.key,
        focusedMessageTargetSignature,
        initialBottomAnchorReady
    ) {
        if (initialMarkdownPreparsePlan.key.isEmpty()) {
            diagnosticsLogger(
                "thread_markdown_preparse_plan scope=priority_anchor result=empty " +
                    "messages=${renderPayload.messages.size} extrasVisible=$contentExtrasVisible"
            )
            return@LaunchedEffect
        }
        if (!initialBottomAnchorReady) {
            diagnosticsLogger(
                "thread_markdown_preparse_plan scope=priority_anchor result=awaiting_viewport " +
                    "messages=${renderPayload.messages.size}"
            )
            return@LaunchedEffect
        }
        if (initialMarkdownPreparsePlan.priorityCandidates.isEmpty()) {
            return@LaunchedEffect
        }
        val startedAt = TimeSource.Monotonic.markNow()
        val result =
            prepareRaftMarkdownFullRendererBodies(
                bodies = initialMarkdownPreparsePlan.priorityCandidates.map { candidate -> candidate.body },
                maxBodies = initialMarkdownPreparsePlan.priorityCandidates.size
            )
        diagnosticsLogger(
            "thread_markdown_preparse scope=priority_anchor " +
                "bodyCandidates=${initialMarkdownPreparsePlan.priorityCandidates.size} " +
                "parserCandidates=${result.requestedCount} parsed=${result.parsedCount} " +
                "scheduled=${result.scheduledCount} cacheHit=${result.cacheHitCount} " +
                "messages=${renderPayload.messages.size} " +
                "focused=${renderPayload.focusedMessageId?.isNotBlank() == true} " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
    }
    // The tag context the message rows render with, so the documents warmed below carry the rows'
    // exact cache keys (a context change simply misses and the row builds it, never stale).
    val markdownDocumentWarmContext =
        remember(highlightedMentions, isMarkdownTagValid, resolveMarkdownTagLabel) {
            RaftMarkdownDocumentWarmContext(
                highlightedMentions = highlightedMentions,
                isTagValid = isMarkdownTagValid,
                resolveTagLabel = resolveMarkdownTagLabel
            )
        }
    val currentMarkdownDocumentWarmContext by rememberUpdatedState(markdownDocumentWarmContext)
    // A task hand-off card's reason draws refs, @mentions and links like a message body (web #15339).
    val currentOnOpenMarkdownTag by rememberUpdatedState(onOpenMarkdownTag)
    val currentOnOpenMarkdownUrl by rememberUpdatedState(onOpenMarkdownUrl)
    val handoffReasonMarkdown =
        remember(highlightedMentions, isMarkdownTagValid, resolveMarkdownTagLabel) {
            ThreadTaskHandoffReasonMarkdown(
                highlightedMentions = highlightedMentions,
                isTagValid = isMarkdownTagValid,
                resolveTagLabel = resolveMarkdownTagLabel,
                onOpenTag = { tag, actionId -> currentOnOpenMarkdownTag(tag, actionId) },
                onOpenUrl = { url -> currentOnOpenMarkdownUrl(url) },
            )
        }
    LaunchedEffect(
        threadInstanceKey,
        markdownTimelineSignature,
        contentExtrasVisible,
        lazyItemCount,
        threadLazyWindowConsumerCounts
    ) {
        if (!contentExtrasVisible || markdownTimelineSignature.isEmpty()) {
            return@LaunchedEffect
        }
        // Per scroll frame only the visible index range is read and compared; the candidate
        // list and its joined key are built only when that range changes.
        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                null
            } else {
                visibleItems.minOf { item -> item.index } to visibleItems.maxOf { item -> item.index }
            }
        }
            .distinctUntilChanged()
            .map { visibleRange ->
                if (visibleRange == null) {
                    emptyThreadMarkdownVisiblePreparsePlan()
                } else {
                    val (firstVisibleItemIndex, lastVisibleItemIndex) = visibleRange
                    renderPayload.threadVisibleMarkdownPreparsePlan(
                        firstVisibleItemIndex = firstVisibleItemIndex,
                        lastVisibleItemIndex = lastVisibleItemIndex,
                        overscanItems = threadLazyWindowConsumerCounts.markdownPreparseOverscanItems,
                        prewarmBeforeItems = lastVisibleItemIndex - firstVisibleItemIndex + 1,
                        contentExtrasVisible = contentExtrasVisible
                    )
                }
            }
            .distinctUntilChanged()
            .collect { visiblePreparsePlan ->
                if (visiblePreparsePlan.key.isEmpty()) {
                    return@collect
                }
                val startedAt = TimeSource.Monotonic.markNow()
                val result =
                    prepareRaftMarkdownFullRendererBodies(
                        bodies = visiblePreparsePlan.candidates.map { candidate -> candidate.body },
                        maxBodies = visiblePreparsePlan.candidates.size
                    )
                // Off the main thread (Dispatchers.Default): build the rows' rich-text documents
                // so a tall row entering the viewport only looks them up.
                val documents =
                    warmRaftMarkdownRichTextDocuments(
                        requests =
                            visiblePreparsePlan.candidates.mapNotNull { candidate ->
                                candidate.inlineContentScope?.let { scope ->
                                    RaftMarkdownDocumentWarmRequest(body = candidate.body, inlineContentScope = scope)
                                }
                            },
                        context = currentMarkdownDocumentWarmContext
                    )
                diagnosticsLogger(
                    "thread_markdown_preparse scope=visible_overscan " +
                        "bodyCandidates=${visiblePreparsePlan.candidates.size} " +
                        "parserCandidates=${result.requestedCount} parsed=${result.parsedCount} " +
                        "scheduled=${result.scheduledCount} cacheHit=${result.cacheHitCount} " +
                        "documentsBuilt=${documents.builtCount} documentsCached=${documents.cacheHitCount} " +
                        "messages=${renderPayload.messages.size} " +
                        "overscan=${threadLazyWindowConsumerCounts.markdownPreparseOverscanItems} " +
                        "prewarmBefore=${visiblePreparsePlan.prewarmBeforeItems} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
    }
    val initialBottomAnchorPending =
        !initialBottomAnchorReady && latestItemIndex >= 0 &&
            !revealLatestLocalSendBeforeInitialAnchor &&
            !adaptiveAnchorRestoreInFlight
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.status,
        renderPayload.messages.size,
        renderConfig.backgroundRefreshing,
        initialBottomAnchorReady,
        latestItemIndex,
        revealLatestLocalSendBeforeInitialAnchor,
        initialBottomAnchorPending
    ) {
        if (initialBottomAnchorPending) {
            diagnosticsLogger(
                "thread_messages_visible_state instance=$threadInstanceKey " +
                    "status=${renderPayload.status} messages=${renderPayload.messages.size} " +
                    "backgroundRefreshing=${renderConfig.backgroundRefreshing} " +
                    "originStatus=${renderPayload.originStatus} latestItemIndex=$latestItemIndex " +
                    "initialBottomAnchorReady=$initialBottomAnchorReady " +
                    "revealLatestLocalSendBeforeInitialAnchor=$revealLatestLocalSendBeforeInitialAnchor " +
                    "branch=initial_bottom_anchor_pending"
            )
        }
    }
    // Wait until the initial push slide settles before signaling FirstContentReady so background
    // member hydration, secondary summary apply, and SharedForegroundLoadGate release do not
    // contend with the ~300ms push animation. Cached content still paints immediately on Frame 1.
    ThreadAfterMotionEffect(
        threadInstanceKey,
        latestItemIndex,
        initialBottomAnchorReady,
        revealLatestLocalSendBeforeInitialAnchor
    ) {
        if (initialBottomAnchorReady || latestItemIndex < 0 || revealLatestLocalSendBeforeInitialAnchor) {
            onThreadContentReady()
        }
    }
    val currentPayloadState = rememberUpdatedState(renderPayload)
    val currentLatestItemIndexState = rememberUpdatedState(latestItemIndex)
    val currentThreadInstanceKeyState = rememberUpdatedState(threadInstanceKey)
    val currentViewportControllerState = rememberUpdatedState(viewportControllerState)
    val currentViewportLayoutGenerationState = rememberUpdatedState(viewportLayoutGeneration)
    var previousViewportMessageIds by remember(threadInstanceKey) {
        mutableStateOf(renderPayload.messages.map { message -> message.id })
    }
    var previousViewportLocalSendIds by remember(threadInstanceKey) {
        mutableStateOf(renderPayload.messages.localSendIds())
    }
    LaunchedEffect(threadInstanceKey, messageIndexSignature) {
        val nextIds = renderPayload.messages.map { message -> message.id }
        val previousIds = previousViewportMessageIds
        val localSendIds = previousViewportLocalSendIds + renderPayload.messages.localSendIds()
        previousViewportMessageIds = nextIds
        previousViewportLocalSendIds = renderPayload.messages.localSendIds()
        if (previousIds == nextIds) return@LaunchedEffect
        val mutation = classifyThreadViewportMutation(previousIds, nextIds, localSendIds)
        viewportLayoutGeneration += 1L
        diagnosticsLogger(
            "thread_content_mutation key=$threadInstanceKey mutation=$mutation " +
                "intent=${viewportControllerState.intent.logTag()} " +
                "allowBottomAutoAnchor=$allowBottomAutoAnchor"
        )
        if (
            mutation == LazyViewportMutation.Append &&
            focusedAnchorPlacementState.placement == LazyViewportAnchorPlacement.RenderedBottom
        ) {
            focusedAnchorPlacementState =
                resetThreadFocusedAnchorPlacement(focusedAnchorPlacementState.identity)
        }
        dispatchViewportEvent(LazyViewportEvent.ContentMutated(mutation))
    }
    // Owner lifetime (Hands 712d8386): popping the page while this effect was still placing a
    // focused message scrolled a list whose LazyColumn was already leaving, and crashed in
    // prefetch cleanup. ThreadListScrollOwnerEffect restarts the effect (which then does nothing)
    // when the page starts leaving the stack, and retires the list before cancelling its job.
    // The only production owner allowed to mutate listState.
    ThreadListScrollOwnerEffect(listState, threadInstanceKey, viewportControllerState.pendingCommand?.id) {
        val command = viewportControllerState.pendingCommand ?: return@ThreadListScrollOwnerEffect
        // Job active, list still composed, page not leaving: checked before the command's own
        // currency, so a dead owner neither scrolls nor dispatches viewport events.
        fun commandOwnerLive(): Boolean = ownerLive()
        fun commandStillCurrent(): Boolean =
            currentViewportControllerState.value.acceptsRenderedTailCommand(
                command = command,
                currentLayoutGeneration = currentViewportLayoutGenerationState.value,
                currentTailMessageId = currentPayloadState.value.messages.lastOrNull()?.id,
                currentAnchorIdentity = pendingFocusedMessageAnchorIdentityState.value
            )
        var staleRenderedTailInvalidationDispatched = false
        fun commandIsLiveOrTerminallyInvalidate(): Boolean {
            if (!commandOwnerLive()) return false
            if (commandStillCurrent()) return true
            val anchor = command.target as? LazyViewportCommandTarget.Anchor
            val controller = currentViewportControllerState.value
            if (
                !staleRenderedTailInvalidationDispatched &&
                anchor?.renderedTailMessageId != null &&
                controller.accepts(command)
            ) {
                staleRenderedTailInvalidationDispatched = true
                if (
                    focusedAnchorPlacementState.identity == anchor.renderedTailAnchorIdentity &&
                    focusedAnchorPlacementState.placement == LazyViewportAnchorPlacement.RenderedBottom &&
                    focusedAnchorPlacementState.renderedTailMessageId == anchor.renderedTailMessageId
                ) {
                    focusedAnchorPlacementState =
                        resetThreadFocusedAnchorPlacement(anchor.renderedTailAnchorIdentity)
                }
                diagnosticsLogger(
                    "thread_rendered_tail_command result=invalidated command=${command.id.sequence} " +
                        "commandLayoutGeneration=${anchor.layoutGeneration} " +
                        "currentLayoutGeneration=${currentViewportLayoutGenerationState.value} " +
                        "expectedTail=${anchor.renderedTailMessageId} " +
                        "currentTail=${currentPayloadState.value.messages.lastOrNull()?.id ?: "none"} " +
                        "expectedIdentity=${anchor.renderedTailAnchorIdentity ?: "none"} " +
                        "currentIdentity=${pendingFocusedMessageAnchorIdentityState.value ?: "none"}"
                )
                dispatchViewportEvent(
                    LazyViewportEvent.RenderedTailCommandInvalidated(
                        commandId = command.id,
                        currentLayoutGeneration = currentViewportLayoutGenerationState.value
                    )
                )
            }
            return false
        }
        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
        listState.awaitThreadMessagesLayoutFrame()
        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
        listState.noteCommandOwner(command.target.openTraceOwnerTag())
        val executed =
            when (val target = command.target) {
                is LazyViewportCommandTarget.Top -> {
                    if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                    if (target.animated) {
                        raftExpressiveTopSettleAnchor(
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            visibleItemCount = listState.layoutInfo.visibleItemsInfo.size
                        )?.let { anchor ->
                            listState.scrollToItem(anchor)
                            if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        }
                        listState.animateScrollToItem(0)
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                    } else {
                        listState.scrollToItem(0)
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                    }
                    true
                }
                LazyViewportCommandTarget.Latest -> {
                    val targetIndex = currentLatestItemIndexState.value
                    if (targetIndex < 0) {
                        false
                    } else {
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        listState.scrollLatestItemToRenderedBottom(targetIndex) {
                            commandIsLiveOrTerminallyInvalidate()
                        }
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        true
                    }
                }
                is LazyViewportCommandTarget.Anchor -> {
                    val targetIndex = currentPayloadState.value.threadViewportListItemIndex(target.target)
                    if (targetIndex < 0 || !listState.canScrollToThreadItem(targetIndex)) {
                        dispatchViewportEvent(LazyViewportEvent.AnchorUnavailable(command.id))
                        false
                    } else {
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        val anchorExecuted =
                            when (target.placement) {
                                LazyViewportAnchorPlacement.ExactOffset -> {
                                    listState.scrollToItem(targetIndex, -target.offset)
                                    true
                                }
                                LazyViewportAnchorPlacement.Focused ->
                                    listState.scrollThreadItemToFocusedViewport(targetIndex) {
                                        commandIsLiveOrTerminallyInvalidate()
                                    }
                                LazyViewportAnchorPlacement.FocusedThreadReplies -> {
                                    val minBottomReservePx =
                                        with(threadDensity) { ThreadRepliesFocusMinBottomReserve.roundToPx() }
                                    listState.scrollThreadItemToFocusedViewport(
                                        itemIndex = targetIndex,
                                        focusedScrollOffset = { itemSize, info ->
                                            threadRepliesFocusedItemScrollOffset(
                                                itemSize = itemSize,
                                                viewportEnd = info.viewportEndOffset,
                                                minBottomReservePx = minBottomReservePx
                                            )
                                        }
                                    ) {
                                        commandIsLiveOrTerminallyInvalidate()
                                    }
                                }
                                LazyViewportAnchorPlacement.RenderedBottom ->
                                    listState.scrollThreadItemToRenderedBottom(
                                        itemIndex = targetIndex,
                                        trailingPaddingSize = target.offset.coerceAtLeast(0)
                                    ) {
                                        commandIsLiveOrTerminallyInvalidate()
                                    }
                            }
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        anchorExecuted
                    }
                }
            }
        if (commandIsLiveOrTerminallyInvalidate()) {
            if (executed) {
                dispatchViewportEvent(LazyViewportEvent.CommandConsumed(command.id))
            } else if (command.target is LazyViewportCommandTarget.Anchor) {
                dispatchViewportEvent(LazyViewportEvent.AnchorUnavailable(command.id))
            }
        }
    }
    // The head's title names the original once its row scrolls away (ThreadHeaderOriginalTitle.kt).
    ThreadHeaderOriginalReporter(listState.raw, renderPayload, renderConfig.originErrorCode)
    val headerScrollToTopRequestFence =
        remember(threadInstanceKey) { AppShellScrollToTopRequestFence(headerScrollToTopRequest) }
    val outlineOriginJumpRequestState = remember(threadInstanceKey) { mutableStateOf(0) }
    var outlineOriginJumpRequest by outlineOriginJumpRequestState
    var outlineOriginJumpConsumed by remember(threadInstanceKey) { mutableStateOf(0) }
    // A thread beside this conversation whose original is behind the history window: the same jump
    // to the start, where the history paywall card is (ThreadHeaderOriginalTitle.kt).
    ThreadHistoryStartFocusEffect(threadInstanceKey, renderPayload.historyStartFocusRequest) { outlineOriginJumpRequest += 1 }
    LaunchedEffect(threadInstanceKey, headerScrollToTopRequest, lazyItemCount) {
        if (!consumeThreadHeaderScrollToTopRequest(
                request = headerScrollToTopRequest,
                lazyItemCount = lazyItemCount,
                fence = headerScrollToTopRequestFence,
            )
        ) {
            return@LaunchedEffect
        }
        listState.noteUserNavigation()
        pendingFocusedMessageAnchorIdentityState.value?.let { pendingIdentity ->
            scrollInterruptedFocusedMessageAnchorIdentity = pendingIdentity
        }
        latestLocalSendBottomAnchorActiveKey = null
        pendingKeyboardBottomAnchor = false
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        userScrollGeneration.value += 1
        dispatchViewportEvent(LazyViewportEvent.PinTop)
        diagnosticsLogger(
            "thread_header_scroll_to_top request=$headerScrollToTopRequest " +
                "firstVisible=${listState.firstVisibleItemIndex} totalItems=$lazyItemCount"
        )
    }
    // The thread timeline's jump to the thread's first message (web `jumpToParent`): its row is the
    // list's first, so the thread's start is loaded first (older pages until there are none), then
    // the list pins its top and the row is located, as a focused message is.
    LaunchedEffect(
        threadInstanceKey,
        outlineOriginJumpRequest,
        renderPayload.canLoadMoreBefore,
        renderPayload.olderMessagesStatus,
        renderPayload.originStatus,
        lazyItemCount
    ) {
        val step =
            threadOutlineOriginJumpStep(
                request = outlineOriginJumpRequest,
                consumed = outlineOriginJumpConsumed,
                canLoadMoreBefore = renderPayload.canLoadMoreBefore,
                olderMessagesStatus = renderPayload.olderMessagesStatus,
                originRowShown = renderPayload.threadStartRowShown(),
            )
        when (step) {
            ThreadOutlineOriginJumpStep.Idle, ThreadOutlineOriginJumpStep.Wait -> Unit
            ThreadOutlineOriginJumpStep.LoadOlder -> onThreadEvent("loadOlder", null, null, null)
            ThreadOutlineOriginJumpStep.GiveUp -> outlineOriginJumpConsumed = outlineOriginJumpRequest
            ThreadOutlineOriginJumpStep.Pin -> {
                listState.noteUserNavigation()
                outlineOriginJumpConsumed = outlineOriginJumpRequest
                pendingFocusedMessageAnchorIdentityState.value?.let { pendingIdentity ->
                    scrollInterruptedFocusedMessageAnchorIdentity = pendingIdentity
                }
                latestLocalSendBottomAnchorActiveKey = null
                pendingKeyboardBottomAnchor = false
                allowBottomAutoAnchor = false
                initialBottomAnchorReady = true
                userScrollGeneration.value += 1
                dispatchViewportEvent(LazyViewportEvent.PinTop)
                activeFocusedMessageId = renderPayload.originMessage?.id
                diagnosticsLogger("thread_outline_origin_jump request=$outlineOriginJumpRequest totalItems=$lazyItemCount")
            }
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        shareSelectionCaptureActive,
        shareSelectionCaptureAnchorMessageId,
        shareSelectionCaptureAnchorBeforeTarget,
        shareSelectionCaptureScrollOffsetPx,
        messageIndexSignature,
        lazyItemCount
    ) {
        val anchorMessageId =
            shareSelectionCaptureAnchorMessageId
                ?.takeIf { shareSelectionCaptureActive && it.isNotBlank() }
                ?: return@LaunchedEffect
        val targetIndex = renderPayload.threadMessageListItemIndex(anchorMessageId)
        val scrollIndex =
            threadShareCaptureAnchorListItemIndex(
                targetIndex = targetIndex,
                anchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget
            )
        diagnosticsLogger(
            "thread_share_anchor_start messageId=$anchorMessageId targetIndex=$targetIndex " +
                "scrollIndex=$scrollIndex beforeTarget=$shareSelectionCaptureAnchorBeforeTarget " +
                "offset=$shareSelectionCaptureScrollOffsetPx total=${listState.layoutInfo.totalItemsCount}"
        )
        if (!listState.canScrollToThreadItem(scrollIndex)) {
            diagnosticsLogger(
                "thread_share_anchor_rejected messageId=$anchorMessageId scrollIndex=$scrollIndex " +
                    "total=${listState.layoutInfo.totalItemsCount}"
            )
            return@LaunchedEffect
        }
        selectedShareMessageIds.forEach { messageId ->
            onShareSelectionBounds(messageId, null)
        }
        val scrollTarget =
            threadShareCaptureAnchorTarget(
                targetIndex = targetIndex,
                anchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget,
                originVisible = renderPayload.hasThreadOriginItem(),
                historyBoundaryVisible = renderPayload.hasThreadHistoryBoundaryItem(),
                messageIds = renderPayload.messages.map { message -> message.id }
            ) ?: return@LaunchedEffect
        val desiredScrollOffset =
            if (shareSelectionCaptureAnchorBeforeTarget) 0 else shareSelectionCaptureScrollOffsetPx
        dispatchViewportEvent(
            LazyViewportEvent.Anchor(
                target = scrollTarget,
                offset = -desiredScrollOffset,
                layoutGeneration = viewportLayoutGeneration,
                fallback = LazyViewportAnchorFallback.Free
            )
        )
        val commandId = viewportControllerState.pendingCommand?.id
        if (commandId != null) {
            awaitViewportCommandSettlement(commandId)
        }
        delay(ThreadShareCaptureScrollSettleMs)
        diagnosticsLogger(
            "thread_share_anchor_complete messageId=$anchorMessageId " +
                "visible=${listState.layoutInfo.visibleItemsInfo.joinToString(",") { it.index.toString() }}"
        )
    }
    val pendingOlderPageAnchorState = remember(threadInstanceKey) { mutableStateOf<ThreadVisibleMessageAnchor?>(null) }
    val previousOlderMessagesStatusState = remember(threadInstanceKey) { mutableStateOf(renderPayload.olderMessagesStatus) }
    val olderPageCycleActiveState = remember(threadInstanceKey) { mutableStateOf(false) }
    val olderPageSettledUserScrollGenerationState = remember(threadInstanceKey) { mutableStateOf(0) }
    val newerGestureDemandTracker =
        remember(threadInstanceKey) { ThreadNewerGestureDemandTracker() }
    var newerPointerGestureActive by remember(threadInstanceKey) { mutableStateOf(false) }
    val lastSettledNewerDemandGenerationState = remember(threadInstanceKey) { mutableStateOf(0) }
    var lastSettledNewerDemandGeneration by lastSettledNewerDemandGenerationState
    var nextNewerPageCycleId by remember(threadInstanceKey) { mutableStateOf(0L) }
    var newerPageCycle by remember(threadInstanceKey) { mutableStateOf<ThreadNewerPageCycle?>(null) }
    val newerPageCycleActive = newerPageCycle != null
    suspend fun settleNewerPageCycle(
        cycleId: Long,
        outcome: ThreadNewerPageLoadOutcome
    ) {
        val cycle = newerPageCycle?.takeIf { active -> active.id == cycleId } ?: return
        if (!shouldSettleThreadNewerPageCycle(
                cycleThreadInstanceKey = cycle.threadInstanceKey,
                currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                cycleId = cycleId,
                activeCycleId = newerPageCycle?.id
            )
        ) {
            diagnosticsLogger(
                "thread_newer_page_cycle result=skipped_stale_route " +
                    "cycleInstance=${cycle.threadInstanceKey} " +
                    "currentInstance=${currentThreadInstanceKeyState.value} cycleId=$cycleId"
            )
            return
        }
        allowBottomAutoAnchor = false
        withFrameNanos { }
        if (!shouldSettleThreadNewerPageCycle(
                cycleThreadInstanceKey = cycle.threadInstanceKey,
                currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                cycleId = cycleId,
                activeCycleId = newerPageCycle?.id
            )
        ) {
            return
        }
        if (shouldRestoreThreadNewerViewportAfterSettlement(
                cycleThreadInstanceKey = cycle.threadInstanceKey,
                currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                cycleId = cycleId,
                activeCycleId = newerPageCycle?.id,
                outcome = outcome
            )
        ) {
            val applied = outcome as ThreadNewerPageLoadOutcome.Applied
            diagnosticsLogger(
                "thread_newer_page_settlement result=awaiting_owner cycleId=$cycleId " +
                    "requestEpoch=${cycle.requestViewportEpoch} requestIntent=${cycle.requestViewportIntent} " +
                    "currentEpoch=${currentViewportControllerState.value.epoch} " +
                    "currentIntent=${currentViewportControllerState.value.intent} " +
                    "pointerActive=$newerPointerGestureActive " +
                    "anchor=${cycle.anchor} applied=${applied.addedMessageIds.size}"
            )
            val settlementResult =
                snapshotFlow {
                    val activeCycle = newerPageCycle
                    if (activeCycle == null || !shouldSettleThreadNewerPageCycle(
                            cycleThreadInstanceKey = cycle.threadInstanceKey,
                            currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                            cycleId = cycleId,
                            activeCycleId = activeCycle.id
                        )
                    ) {
                        ThreadNewerViewportSettlementResult.Reject
                    } else {
                        val controller = currentViewportControllerState.value
                        resolveThreadNewerViewportSettlement(
                            requestViewportEpoch = activeCycle.requestViewportEpoch,
                            requestViewportIntent = activeCycle.requestViewportIntent,
                            currentViewportEpoch = controller.epoch,
                            currentViewportIntent = controller.intent,
                            pointerGestureActive =
                                newerPointerGestureActive ||
                                    (activeCycle.anchor?.let { anchor ->
                                        anchor.userScrollGeneration != userScrollGeneration.value
                                    } ?: false),
                            pageProjectionCommitted =
                                applied.addedMessageIds.all(previousViewportMessageIds::contains),
                            requestAnchor =
                                activeCycle.anchor?.messageId?.let { messageId ->
                                    LazyViewportAnchorTarget.Message(messageId)
                                },
                            requestAnchorOffset = activeCycle.anchor?.offset ?: 0
                        )
                    }
                }.first { result -> result != ThreadNewerViewportSettlementResult.Await }
            diagnosticsLogger(
                "thread_newer_page_settlement result=$settlementResult cycleId=$cycleId " +
                    "currentEpoch=${currentViewportControllerState.value.epoch} " +
                    "currentIntent=${currentViewportControllerState.value.intent} " +
                    "activeAnchor=${newerPageCycle?.anchor}"
            )
            if (settlementResult == ThreadNewerViewportSettlementResult.Reject) {
                lastSettledNewerDemandGeneration = newerGestureDemandTracker.generation
                newerPageCycle = null
                return
            }
            val restoreTicket = settlementResult as ThreadNewerViewportSettlementResult.Restore
            listState.awaitThreadMessagesLayoutFrame()
            if (!shouldSettleThreadNewerPageCycle(
                    cycleThreadInstanceKey = cycle.threadInstanceKey,
                    currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                    cycleId = cycleId,
                    activeCycleId = newerPageCycle?.id
                )
            ) {
                return
            }
            viewportLayoutGeneration += 1L
            listState.noteDispatchOwner(ThreadViewportOwnerTag.NewerSettle)
            dispatchViewportEvent(
                LazyViewportEvent.NewerPageSettled(
                    expectedViewportEpoch = restoreTicket.expectedViewportEpoch,
                    expectedViewportIntent = restoreTicket.expectedViewportIntent,
                    requestAnchor = restoreTicket.requestAnchor,
                    requestAnchorOffset = restoreTicket.requestAnchorOffset,
                    layoutGeneration = viewportLayoutGeneration
                )
            )
            val settlementCommandId = viewportControllerState.pendingCommand?.id
            if (settlementCommandId != null) {
                awaitViewportCommandSettlement(settlementCommandId)
            }
        }
        // Consume every newer-directed gesture or wheel scroll observed through this request,
        // including a second gesture while the page was in flight. Only a fresh gesture or
        // wheel scroll after settlement may authorize another page.
        lastSettledNewerDemandGeneration = newerGestureDemandTracker.generation
        newerPageCycle = null
    }
    val requestNewerPage: (String) -> Unit = request@{ event ->
        if (newerPageCycle != null || !renderPayload.canLoadMoreAfter) {
            return@request
        }
        val cycleId = nextNewerPageCycleId + 1L
        nextNewerPageCycleId = cycleId
        val anchor =
            listState.threadFirstVisibleMessageAnchor(
                timelineSlice = renderPayload,
                userScrollGeneration = userScrollGeneration.value
            )
        newerPageCycle =
            ThreadNewerPageCycle(
                id = cycleId,
                threadInstanceKey = threadInstanceKey,
                anchor = anchor,
                requestViewportEpoch = viewportControllerState.epoch,
                requestViewportIntent = viewportControllerState.intent
            )
        allowBottomAutoAnchor = false
        diagnosticsLogger(
            "thread_newer_page_cycle result=requested event=$event " +
                "demandGeneration=${newerGestureDemandTracker.generation} " +
                "viewportIntent=${viewportControllerState.intent} " +
                "viewportEpoch=${viewportControllerState.epoch} anchor=$anchor " +
                "userScrollGeneration=${userScrollGeneration.value}"
        )
        coroutineScope.launch {
            var outcome: ThreadNewerPageLoadOutcome = ThreadNewerPageLoadOutcome.Failed
            try {
                outcome = onLoadNewer()
            } finally {
                settleNewerPageCycle(cycleId, outcome)
            }
        }
    }
    ThreadMessagesListPagingEffects(
        threadInstanceKey = threadInstanceKey,
        renderPayload = renderPayload,
        listState = listState,
        lazyItemCount = lazyItemCount,
        latestItemIndex = latestItemIndex,
        latestMessageListItemIndex = latestMessageListItemIndex,
        viewportMeasurementReady = viewportMeasurementReady,
        threadLazyWindowConsumerCounts = threadLazyWindowConsumerCounts,
        shareSelectionCaptureActive = shareSelectionCaptureActive,
        newerPageCycleActive = newerPageCycleActive,
        agentTypingLineShown = agentTypingLineShown,
        sparseTopAnchored = sparseTopAnchored,
        imeProgrammaticViewportPolicy = imeProgrammaticViewportPolicy,
        firstViewportProbe = firstViewportProbe,
        postFirstFrameBeyondBoundsEnabledRef = postFirstFrameBeyondBoundsEnabledRef,
        stackPageLeavingRef = stackPageLeavingRef,
        userScrollGeneration = userScrollGeneration,
        newerGestureDemandTracker = newerGestureDemandTracker,
        currentSparseTopAnchoredState = currentSparseTopAnchoredState,
        currentViewportControllerState = currentViewportControllerState,
        readingAnchorPlacementSettledState = readingAnchorPlacementSettledState,
        pendingFocusedMessageAnchorIdentityState = pendingFocusedMessageAnchorIdentityState,
        dispatchViewportEvent = dispatchViewportEvent,
        requestNewerPage = requestNewerPage,
        onThreadEvent = onThreadEvent,
        onVisibleReadTailChange = onVisibleReadTailChange,
        onViewportFollowsLatestChange = onViewportFollowsLatestChange,
        diagnosticsLogger = diagnosticsLogger,
        allowBottomAutoAnchorState = allowBottomAutoAnchorState,
        initialBottomAnchorReadyState = initialBottomAnchorReadyState,
        viewportControllerMutableState = viewportControllerMutableState,
        lastSettledNewerDemandGenerationState = lastSettledNewerDemandGenerationState,
        olderPageCycleActiveState = olderPageCycleActiveState,
        olderPageSettledUserScrollGenerationState = olderPageSettledUserScrollGenerationState,
        pendingOlderPageAnchorState = pendingOlderPageAnchorState,
        previousOlderMessagesStatusState = previousOlderMessagesStatusState,
        firstViewportProbeActiveState = firstViewportProbeActiveState,
        liveComposerKeyboardInsetDpState = liveComposerKeyboardInsetDpState,
        focusedAnchorState = focusedAnchorState,
    )
    val initialInFlightLocalSendKey = remember(threadInstanceKey) { latestLocalSendBottomAnchorKey }
    LaunchedEffect(
        threadInstanceKey,
        latestLocalSendBottomAnchorKey,
        newerPageCycleActive
    ) {
        if (newerPageCycleActive) {
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        if (
            !shouldAnchorLatestLocalSendToBottom(
                latestLocalSendBottomAnchorKey,
                currentLatestItemIndexState.value
            )
        ) {
            return@LaunchedEffect
        }
        if (latestLocalSendBottomAnchorKey != initialInFlightLocalSendKey) {
            listState.noteUserNavigation()
        }
        latestLocalSendBottomAnchorStartUserScrollGeneration = userScrollGeneration.value
        latestLocalSendBottomAnchorActiveKey = latestLocalSendBottomAnchorKey
        allowBottomAutoAnchor = true
        dispatchViewportEvent(
            LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)
        )
    }
    LaunchedEffect(threadInstanceKey, latestLocalSendBottomAnchorActiveKey) {
        val anchorKey = latestLocalSendBottomAnchorActiveKey ?: return@LaunchedEffect
        val startUserScrollGeneration = latestLocalSendBottomAnchorStartUserScrollGeneration
        listState.awaitThreadMessagesLayoutFrame()
        listState.awaitThreadMessagesLayoutStability(currentLatestItemIndexState.value)
        var attempt = 0
        while (
            shouldContinueLatestLocalSendBottomAnchor(
                activeAnchorKey = latestLocalSendBottomAnchorActiveKey,
                targetAnchorKey = anchorKey,
                startUserScrollGeneration = startUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value,
                isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(
                    latestItemIndex = currentLatestItemIndexState.value,
                    allowSparseTopFit = currentSparseTopAnchoredState.value,
                ),
                isSendingAnchorVisible = currentPayloadState.value.hasSendingLocalSendAnchor(anchorKey),
                attempt = attempt
            )
        ) {
            dispatchViewportEvent(LazyViewportEvent.ContentMutated(LazyViewportMutation.Append))
            val commandId = viewportControllerState.pendingCommand?.id
            if (commandId != null) {
                awaitViewportCommandSettlement(commandId)
            }
            delay(ThreadLayoutStabilityFrameDelayMs)
            attempt += 1
        }
        if (latestLocalSendBottomAnchorActiveKey == anchorKey) {
            latestLocalSendBottomAnchorActiveKey = null
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.focusedMessageId,
        renderPayload.focusedMessageRequestNonce,
        focusedMessageTargetSignature,
        lazyItemCount,
        jumpInputs.jumpFocusNonce
    ) {
        val targetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val targetIdentity =
            focusedMessageAnchorIdentity(
                messageId = targetMessageId,
                requestNonce = renderPayload.focusedMessageRequestNonce
            )
        // Task #73: the unread-thread pill's own focus request lands on the parent's replies
        // summary; every other focus (links, search, Activity) keeps the top-aligned placement.
        val basePlacement =
            threadFocusedAnchorBasePlacement(
                requestNonce = renderPayload.focusedMessageRequestNonce,
                unreadThreadJumpFocusNonce = jumpInputs.jumpFocusNonce
            )
        if (
            !shouldRunFocusedMessageAnchor(
                targetIdentity = targetIdentity,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity
            )
        ) {
            return@LaunchedEffect
        }
        val targetIndex = renderPayload.focusedMessageListItemIndex()
        if (targetIndex < 0) {
            return@LaunchedEffect
        }
        listState.awaitThreadMessagesLayoutFrame()
        if (!listState.canScrollToThreadItem(targetIndex)) {
            return@LaunchedEffect
        }
        // A drag that starts between the guard above and this point must win:
        // programmatic scrollToItem would cancel the gesture (task #554).
        if (listState.isScrollInProgress) {
            scrollInterruptedFocusedMessageAnchorIdentity = targetIdentity
            return@LaunchedEffect
        }
        activeFocusedMessageId = targetMessageId
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        // task #990 kill-switch: on stop-loss platforms, a focus request
        // arriving after the keyboard is already open never sees a 0->open
        // transition, so the rise-cancel can never fire for it — the initial
        // anchor itself must not dispatch, and with no anchored intent the
        // HeightChange replay has no source. This is a deliberate safe
        // degradation: the request is DROPPED, not deferred — the inset is not
        // among this effect's keys, so closing the keyboard does not rerun it.
        // Platforms that keep ordinary correction skip this gate.
        if (
            suppressFocusAnchorWhileOpen(
                keyboardInsetDp = liveComposerKeyboardInsetDp,
                targetMessageId = targetMessageId,
                policy = imeProgrammaticViewportPolicy,
                log = diagnosticsLogger,
            )
        ) {
            activeFocusedMessageId = null
            return@LaunchedEffect
        }
        if (
            focusedAnchorPlacementState.identity != targetIdentity ||
            (focusedAnchorPlacementState.placement != LazyViewportAnchorPlacement.RenderedBottom &&
                focusedAnchorPlacementState.placement != basePlacement)
        ) {
            focusedAnchorPlacementState = resetThreadFocusedAnchorPlacement(targetIdentity, basePlacement)
        }
        val targetAnchor = LazyViewportAnchorTarget.Message(targetMessageId)
        val currentVisibleOffset = listState.focusedAnchorTargetOffset(targetIndex)
        if (
            shouldKeepVisibleFocusedAnchorOffset(
                placement = focusedAnchorPlacementState.placement,
                currentVisibleOffset = currentVisibleOffset,
                targetIdentity = targetIdentity,
                sessionIdentity = focusedAnchorSessionIdentity,
                initialPlacementApplied = initialFocusedPlacementOwner.applied,
                initialFocusedIdentity = initialFocusedAnchorIdentity,
            ) && currentVisibleOffset != null
        ) {
            if ((viewportControllerState.intent as? LazyViewportIntent.Anchored)?.target != targetAnchor) {
                dispatchViewportEvent(
                    LazyViewportEvent.Anchor(
                        target = targetAnchor,
                        offset = currentVisibleOffset,
                        layoutGeneration = viewportLayoutGeneration,
                        placement = LazyViewportAnchorPlacement.ExactOffset,
                        fallback = LazyViewportAnchorFallback.Free
                    )
                )
            }
            dispatchViewportEvent(
                LazyViewportEvent.LockAnchorOffset(
                    target = targetAnchor,
                    offset = currentVisibleOffset,
                    layoutGeneration = viewportLayoutGeneration
                )
            )
        } else {
            listState.noteDispatchOwner(threadFocusedDispatchOwnerTag(focusedAnchorPlacementState.placement))
            dispatchViewportEvent(
                LazyViewportEvent.Anchor(
                    target = targetAnchor,
                    offset = focusedAnchorPlacementState.offsetPx,
                    layoutGeneration = viewportLayoutGeneration,
                    placement = focusedAnchorPlacementState.placement,
                    renderedTailMessageId =
                        focusedAnchorPlacementState.renderedTailMessageId.takeIf {
                            focusedAnchorPlacementState.identity == targetIdentity &&
                                focusedAnchorPlacementState.placement ==
                                    LazyViewportAnchorPlacement.RenderedBottom
                        },
                    renderedTailAnchorIdentity =
                        focusedAnchorPlacementState.identity.takeIf {
                            it == targetIdentity &&
                                focusedAnchorPlacementState.placement ==
                                    LazyViewportAnchorPlacement.RenderedBottom &&
                                focusedAnchorPlacementState.renderedTailMessageId != null
                        },
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
            val focusCommandId = viewportControllerState.pendingCommand?.id
            if (focusCommandId != null) {
                awaitViewportCommandSettlement(focusCommandId)
            }
            val settledOffset = listState.focusedAnchorTargetOffset(targetIndex)
            if (settledOffset != null && focusedAnchorPlacementState.placement == LazyViewportAnchorPlacement.Focused) {
                dispatchViewportEvent(
                    LazyViewportEvent.LockAnchorOffset(
                        target = targetAnchor,
                        offset = settledOffset,
                        layoutGeneration = viewportLayoutGeneration
                    )
                )
            }
        }
        val didFocus = listState.focusedAnchorTargetOffset(targetIndex) != null
        if (!didFocus) {
            diagnosticsLogger(
                "thread_focused_message_anchor result=scroll_skipped_unmeasured " +
                    "messageId=$targetMessageId targetIndex=$targetIndex"
            )
            activeFocusedMessageId = null
            return@LaunchedEffect
        }
        focusedAnchorStartUserScrollGeneration = userScrollGeneration.value
        correctedFocusedOriginIdentity = null
        // task #567 first-principles invariant: once anchored, the target must
        // hold its viewport position until the user scrolls. The list is
        // item-key anchored and the target itself becomes the anchor item
        // after scrollToItem(target, 0), so drift can only come from another
        // scroll actor or an anchor handoff moment — instead of trusting any
        // single culprit fix, watch the position until it is provably quiet
        // (or the cap expires) and silently re-pin on drift. scrollToItem is
        // non-animated: corrections are invisible unless something moved the
        // viewport, in which case they restore what the user was promised.
        if (focusedAnchorSessionIdentity != targetIdentity) {
            focusedAnchorSessionIdentity = targetIdentity
            focusedAnchorSessionElapsedMs = 0L
            focusedAnchorSessionReanchors = 0
        }
        var anchoredOffset = listState.focusedAnchorTargetOffset(targetIndex)
        var quietMs = 0L
        var driftMaxPx = 0
        while (true) {
            delay(FocusedAnchorSessionRecheckIntervalMs)
            focusedAnchorSessionElapsedMs += FocusedAnchorSessionRecheckIntervalMs
            // task #1010: opening the IME hands viewport ownership from the
            // one-shot route focus to the composer. The composer effect marks
            // the identity consumed before it evaluates the inset layout; a
            // suspended focus watcher must observe that retirement before it
            // can re-pin into the shrinking viewport.
            if (consumedFocusedMessageAnchorIdentity == targetIdentity) {
                diagnosticsLogger(
                    "thread_focused_anchor_session outcome=consumed_ime_handoff " +
                        "reanchors=$focusedAnchorSessionReanchors driftMaxPx=$driftMaxPx " +
                        "elapsedMs=$focusedAnchorSessionElapsedMs messageId=$targetMessageId"
                )
                return@LaunchedEffect
            }
            val currentOffset = listState.focusedAnchorTargetOffset(targetIndex)
            val currentUserScrollGeneration = userScrollGeneration.value
            val rawScrollInProgress = listState.isScrollInProgress
            val explicitInterrupt = scrollInterruptedFocusedMessageAnchorIdentity == targetIdentity
            val action =
                focusedAnchorSessionAction(
                    keyboardOpen =
                        threadImeOpenSuppressesProgrammaticViewport(
                            keyboardInsetDp = liveComposerKeyboardInsetDp,
                            policy = imeProgrammaticViewportPolicy,
                        ),
                    userInteracting =
                        focusedAnchorSessionUserOverrideActive(
                            startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                            currentUserScrollGeneration = currentUserScrollGeneration,
                            targetIdentity = targetIdentity,
                            scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity
                        ),
                    drifted = focusedAnchorTargetDrifted(anchoredOffset, currentOffset),
                    quietMs = quietMs,
                    elapsedMs = focusedAnchorSessionElapsedMs
                )
            when (action) {
                FocusedAnchorSessionAction.Interrupt -> {
                    scrollInterruptedFocusedMessageAnchorIdentity = targetIdentity
                    diagnosticsLogger(
                        "thread_focused_anchor_session outcome=interrupted " +
                            "reanchors=$focusedAnchorSessionReanchors driftMaxPx=$driftMaxPx " +
                            "elapsedMs=$focusedAnchorSessionElapsedMs messageId=$targetMessageId " +
                            "rawScrollInProgress=$rawScrollInProgress explicitInterrupt=$explicitInterrupt " +
                            "startUserScrollGeneration=$focusedAnchorStartUserScrollGeneration " +
                            "currentUserScrollGeneration=$currentUserScrollGeneration"
                    )
                    return@LaunchedEffect
                }
                FocusedAnchorSessionAction.Consume -> {
                    consumedFocusedMessageAnchorIdentity = targetIdentity
                    val outcome =
                        if (quietMs >= FocusedAnchorSessionQuietMs) "consumed_quiet" else "consumed_cap"
                    diagnosticsLogger(
                        "thread_focused_anchor_session outcome=$outcome " +
                            "reanchors=$focusedAnchorSessionReanchors driftMaxPx=$driftMaxPx " +
                            "elapsedMs=$focusedAnchorSessionElapsedMs messageId=$targetMessageId"
                    )
                    return@LaunchedEffect
                }
                FocusedAnchorSessionAction.Reanchor -> {
                    focusedAnchorSessionReanchors += 1
                    val driftPx = focusedAnchorDriftMagnitude(anchoredOffset, currentOffset)
                    driftMaxPx = maxOf(driftMaxPx, driftPx)
                    diagnosticsLogger(
                        "thread_focused_anchor_session_drift deltaPx=$driftPx " +
                            "reanchor=$focusedAnchorSessionReanchors targetIndex=$targetIndex " +
                            "originStatus=${renderPayload.originStatus} totalItems=${listState.layoutInfo.totalItemsCount} " +
                            "firstVisible=${listState.firstVisibleItemIndex} messageId=$targetMessageId"
                    )
                    val resolvedPlacement =
                        focusedAnchorPlacementForIdentity(
                            targetIdentity = targetIdentity,
                            placementIdentity = focusedAnchorPlacementState.identity,
                            placement = focusedAnchorPlacementState.placement
                        )
                    val reanchorTarget =
                        resolveThreadFocusedReanchorTarget(
                            anchoredOffset = anchoredOffset,
                            resolvedPlacement = resolvedPlacement,
                            fallbackOffsetPx =
                                focusedAnchorPlacementOffsetForIdentity(
                                    targetIdentity = targetIdentity,
                                    placementIdentity = focusedAnchorPlacementState.identity,
                                    placement = focusedAnchorPlacementState.placement,
                                    placementOffsetPx = focusedAnchorPlacementState.offsetPx
                                ),
                        )
                    listState.noteDispatchOwner(threadFocusedDispatchOwnerTag(resolvedPlacement))
                    dispatchViewportEvent(
                        LazyViewportEvent.Anchor(
                            target = targetAnchor,
                            offset = reanchorTarget.offsetPx,
                            layoutGeneration = viewportLayoutGeneration,
                            placement = reanchorTarget.placement,
                            renderedTailMessageId = focusedAnchorRenderedTailMessageIdForIdentity(
                                targetIdentity = targetIdentity,
                                placementIdentity = focusedAnchorPlacementState.identity,
                                placement = focusedAnchorPlacementState.placement,
                                renderedTailMessageId = focusedAnchorPlacementState.renderedTailMessageId
                            ),
                            renderedTailAnchorIdentity =
                                focusedAnchorPlacementState.identity.takeIf {
                                    it == targetIdentity &&
                                        focusedAnchorPlacementState.placement ==
                                            LazyViewportAnchorPlacement.RenderedBottom &&
                                        focusedAnchorPlacementState.renderedTailMessageId != null
                                },
                            fallback = LazyViewportAnchorFallback.Free
                        )
                    )
                    val reanchorCommandId = viewportControllerState.pendingCommand?.id
                    if (reanchorCommandId != null) {
                        awaitViewportCommandSettlement(reanchorCommandId)
                    }
                    anchoredOffset = listState.focusedAnchorTargetOffset(targetIndex)
                    if (anchoredOffset != null && resolvedPlacement == LazyViewportAnchorPlacement.Focused) {
                        dispatchViewportEvent(
                            LazyViewportEvent.LockAnchorOffset(
                                target = targetAnchor,
                                offset = anchoredOffset,
                                layoutGeneration = viewportLayoutGeneration
                            )
                        )
                    }
                    quietMs = 0L
                }
                FocusedAnchorSessionAction.Continue -> {
                    quietMs += FocusedAnchorSessionRecheckIntervalMs
                }
            }
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.focusedMessageId,
        renderPayload.focusedMessageRequestNonce,
        renderPayload.originStatus,
        renderPayload.originMessage?.id,
        lazyItemCount
    ) {
        val targetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val targetIdentity =
            focusedMessageAnchorIdentity(
                messageId = targetMessageId,
                requestNonce = renderPayload.focusedMessageRequestNonce
            )
        val startUserScrollGeneration = focusedAnchorStartUserScrollGeneration ?: return@LaunchedEffect
        if (
            !shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = renderPayload.originStatus,
                targetIdentity = targetIdentity,
                anchoredIdentity = focusedAnchorSessionIdentity,
                correctedIdentity = correctedFocusedOriginIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                startUserScrollGeneration = startUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value,
                keyboardOpen =
                    threadImeOpenSuppressesProgrammaticViewport(
                        keyboardInsetDp = liveComposerKeyboardInsetDp,
                        policy = imeProgrammaticViewportPolicy,
                    )
            )
        ) {
            return@LaunchedEffect
        }
        val targetIndex = renderPayload.focusedMessageListItemIndex()
        if (targetIndex < 0) return@LaunchedEffect
        listState.awaitThreadMessagesLayoutFrame()
        listState.awaitThreadMessagesLayoutStability(targetIndex)
        if (
            !shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = renderPayload.originStatus,
                targetIdentity = targetIdentity,
                anchoredIdentity = focusedAnchorSessionIdentity,
                correctedIdentity = correctedFocusedOriginIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                startUserScrollGeneration = startUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value,
                keyboardOpen =
                    threadImeOpenSuppressesProgrammaticViewport(
                        keyboardInsetDp = liveComposerKeyboardInsetDp,
                        policy = imeProgrammaticViewportPolicy,
                    )
            ) ||
            listState.isScrollInProgress ||
            !listState.canScrollToThreadItem(targetIndex)
        ) {
            return@LaunchedEffect
        }
        correctedFocusedOriginIdentity = targetIdentity
        val originCorrectionPlacement =
            focusedAnchorPlacementForIdentity(
                targetIdentity = targetIdentity,
                placementIdentity = focusedAnchorPlacementState.identity,
                placement = focusedAnchorPlacementState.placement
            )
        listState.noteDispatchOwner(ThreadViewportOwnerTag.OriginCorrection)
        dispatchViewportEvent(
            LazyViewportEvent.Anchor(
                target = LazyViewportAnchorTarget.Message(targetMessageId),
                offset = focusedAnchorPlacementOffsetForIdentity(
                    targetIdentity = targetIdentity,
                    placementIdentity = focusedAnchorPlacementState.identity,
                    placement = focusedAnchorPlacementState.placement,
                    placementOffsetPx = focusedAnchorPlacementState.offsetPx
                ),
                layoutGeneration = viewportLayoutGeneration,
                placement = originCorrectionPlacement,
                renderedTailMessageId = focusedAnchorRenderedTailMessageIdForIdentity(
                    targetIdentity = targetIdentity,
                    placementIdentity = focusedAnchorPlacementState.identity,
                    placement = focusedAnchorPlacementState.placement,
                    renderedTailMessageId = focusedAnchorPlacementState.renderedTailMessageId
                ),
                renderedTailAnchorIdentity =
                    focusedAnchorPlacementState.identity.takeIf {
                        it == targetIdentity &&
                            focusedAnchorPlacementState.placement ==
                                LazyViewportAnchorPlacement.RenderedBottom &&
                            focusedAnchorPlacementState.renderedTailMessageId != null
                    },
                fallback = LazyViewportAnchorFallback.Free
            )
        )
        val correctionCommandId = viewportControllerState.pendingCommand?.id
        if (correctionCommandId != null) {
            awaitViewportCommandSettlement(correctionCommandId)
        }
        val correctedOffset = listState.focusedAnchorTargetOffset(targetIndex)
        if (correctedOffset != null && originCorrectionPlacement == LazyViewportAnchorPlacement.Focused) {
            dispatchViewportEvent(
                LazyViewportEvent.LockAnchorOffset(
                    target = LazyViewportAnchorTarget.Message(targetMessageId),
                    offset = correctedOffset,
                    layoutGeneration = viewportLayoutGeneration
                )
            )
        }
        val corrected = correctedOffset != null
        diagnosticsLogger(
            "thread_focused_origin_correction result=${if (corrected) "corrected" else "skipped_unmeasured"} " +
                "messageId=$targetMessageId targetIndex=$targetIndex originStatus=${renderPayload.originStatus} " +
                "userScrollGeneration=${userScrollGeneration.value}"
        )
    }
    LaunchedEffect(threadInstanceKey, inlineSearchVisible, activeInlineSearchMatch) {
        val match = activeInlineSearchMatch ?: return@LaunchedEffect
        if (!inlineSearchVisible) return@LaunchedEffect
        listState.awaitThreadMessagesLayoutFrame()
        if (!listState.canScrollToThreadItem(match.listItemIndex)) {
            return@LaunchedEffect
        }
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        listState.noteUserNavigation()
        dispatchViewportEvent(
            LazyViewportEvent.Anchor(
                target = LazyViewportAnchorTarget.Message(match.messageId),
                offset = 0,
                layoutGeneration = viewportLayoutGeneration,
                fallback = LazyViewportAnchorFallback.Free
            )
        )
    }
    LaunchedEffect(threadInstanceKey, activeFocusedMessageId) {
        val highlightedMessageId = activeFocusedMessageId ?: return@LaunchedEffect
        delay(ThreadFocusedMessageHighlightDurationMs)
        if (activeFocusedMessageId == highlightedMessageId) {
            activeFocusedMessageId = null
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.focusedMessageId,
        renderPayload.focusedMessageRequestNonce,
        focusedMessageTargetSignature,
        latestMessageLayoutKey,
        latestItemIndex,
        initialBottomAnchorTargetItemIndex,
        initialBottomAnchorTrailingPaddingPx,
        shareSelectionCaptureActive,
        newerPageCycleActive
    ) {
        if (shareSelectionCaptureActive || latestItemIndex < 0) return@LaunchedEffect
        if (newerPageCycleActive) {
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        val focusedMessageTargetIndex =
            renderPayload.focusedMessageListItemIndex()
        if (focusedMessageTargetIndex >= 0) {
            return@LaunchedEffect
        }
        // task #567: while a focused-message anchor request is pending, the
        // bottom anchor must not touch the viewport at all — it was the second
        // scroll actor racing the focused anchor on cold entry
        // (the old initial anchor was a multi-step suspend that could
        // land a scroll after the focused anchor positioned the target). The
        // yield is bounded: if the focused target never becomes anchorable
        // (deleted message, failed context fetch), normal bottom anchoring
        // resumes after the cap so the entry is never left blank.
        while (
            shouldYieldBottomAnchorToFocusedAnchor(
                pendingIdentity = pendingFocusedMessageAnchorIdentityState.value,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
            )
        ) {
            delay(FocusedAnchorSessionRecheckIntervalMs)
            bottomAnchorFocusedYieldElapsedMs += FocusedAnchorSessionRecheckIntervalMs
        }
        pendingFocusedMessageAnchorIdentityState.value?.let { pendingIdentity ->
            if (
                !shouldRunFocusedMessageAnchor(
                    targetIdentity = pendingIdentity,
                    consumedIdentity = consumedFocusedMessageAnchorIdentity,
                    scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity
                )
            ) {
                // The focused anchor consumed or the user took over while we
                // waited — either way the viewport is owned; stay out.
                return@LaunchedEffect
            }
            diagnosticsLogger(
                "thread_initial_bottom_anchor result=focused_yield_cap_fallback " +
                    "elapsedMs=$bottomAnchorFocusedYieldElapsedMs"
            )
        }
        when (
            threadInitialBottomAnchorStage(
                initialBottomAnchorReady = initialBottomAnchorReady,
                userScrollInProgress = listState.isScrollInProgress,
                latestItemVisible = listState.isThreadItemVisible(initialBottomAnchorTargetItemIndex)
            )
        ) {
            ThreadInitialBottomAnchorStage.UserOwned -> {
                // A drag during the initial-anchor window wins: reveal the list where
                // the user already is instead of yanking to the bottom (task #554
                // pattern scan — entry-window race).
                initialBottomAnchorReady = true
                return@LaunchedEffect
            }
            ThreadInitialBottomAnchorStage.AdvanceThenAnchor -> Unit
            ThreadInitialBottomAnchorStage.Anchor,
            ThreadInitialBottomAnchorStage.Settled -> Unit
        }
        if (!initialBottomAnchorReady) {
            var initialBottomAnchorRetries = 0
            while (!initialBottomAnchorReady) {
                // Bounded: a copy that can never anchor (a kept-alive, unplaced tab's thread) retried
                // every 80 ms for as long as it stayed composed. Give up and accept the viewport.
                if (++initialBottomAnchorRetries > ThreadInitialBottomAnchorMaxRetries) {
                    diagnosticsLogger(
                        "thread_initial_bottom_anchor result=retry_cap retries=$ThreadInitialBottomAnchorMaxRetries " +
                            "latestItemIndex=$latestItemIndex targetItemIndex=$initialBottomAnchorTargetItemIndex"
                    )
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                if (adaptiveAnchorRestoreInFlight) {
                    // A4 restore owns the viewport (task #18): yield this frame rather than
                    // dispatching FollowLatest on top of an in-flight staged-anchor scroll.
                    delay(ThreadInitialBottomAnchorRetryDelayMs)
                    continue
                }
                if (listState.isScrollInProgress) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                // task #990 kill-switch: stop-loss platforms read the inset
                // live on every retry. A keyboard that opened mid-loop means
                // the current viewport is accepted as-is — retrying into a
                // shrinking window is the trigger every white capture shares.
                if (
                    suppressInitialBottomRetry(
                        keyboardInsetDp = liveComposerKeyboardInsetDp,
                        policy = imeProgrammaticViewportPolicy,
                        log = diagnosticsLogger,
                    )
                ) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                listState.noteDispatchOwner(ThreadViewportOwnerTag.InitialBottomRetry, initialBottomAnchorRetries)
                dispatchViewportEvent(
                    LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)
                )
                val initialCommandId = viewportControllerState.pendingCommand?.id
                if (initialCommandId != null) {
                    awaitViewportCommandSettlement(initialCommandId)
                }
                val anchored =
                    listState.isLatestItemAtRenderedBottom(
                        latestItemIndex = initialBottomAnchorTargetItemIndex,
                        trailingPaddingSize = initialBottomAnchorTrailingPaddingPx,
                        allowSparseTopFit = currentSparseTopAnchoredState.value,
                    )
                if (anchored) {
                    diagnosticsLogger(
                        "thread_initial_bottom_anchor result=anchored latestItemIndex=$latestItemIndex " +
                            "targetItemIndex=$initialBottomAnchorTargetItemIndex " +
                            "trailingPaddingPx=$initialBottomAnchorTrailingPaddingPx " +
                            listState.threadViewportGeometryDiagnostics(initialBottomAnchorTargetItemIndex)
                    )
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                diagnosticsLogger(
                    "thread_initial_bottom_anchor result=retry latestItemIndex=$latestItemIndex " +
                        "targetItemIndex=$initialBottomAnchorTargetItemIndex " +
                        "trailingPaddingPx=$initialBottomAnchorTrailingPaddingPx " +
                        "totalItems=${listState.layoutInfo.totalItemsCount} " +
                        "visibleItems=${listState.layoutInfo.visibleItemsInfo.size} " +
                        listState.threadViewportGeometryDiagnostics(initialBottomAnchorTargetItemIndex)
                )
                delay(ThreadInitialBottomAnchorRetryDelayMs)
            }
            return@LaunchedEffect
        }
        if (!allowBottomAutoAnchor && !listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)) {
            return@LaunchedEffect
        }
        listState.awaitThreadMessagesLayoutStability(latestItemIndex)
        if (listState.isScrollInProgress && !listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)) {
            if (allowBottomAutoAnchor) {
                allowBottomAutoAnchor = false
            }
            return@LaunchedEffect
        }
        if (!listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)) {
            // task #990 kill-switch: on stop-loss platforms automatic
            // correction stays silent while the keyboard is open. Read live —
            // this coroutine awaited layout stability and can outlive the rise.
            if (
                suppressAutoBottomHeightChange(
                    keyboardInsetDp = liveComposerKeyboardInsetDp,
                    policy = imeProgrammaticViewportPolicy,
                    log = diagnosticsLogger,
                )
            ) {
                return@LaunchedEffect
            }
            dispatchViewportEvent(LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange))
        }
    }
    // #171: content now shows while the page is still sliding in, so the scroll-ahead window also
    // waits for the slide to settle: during the push only the visible rows are composed. Isolate
    // LocalRaftStackPageInMotion inside a leaf so push/pop motion transitions never recompose
    // ThreadMessagesListContent.
    ThreadScrollAheadWindowEffect(
        threadInstanceKey = threadInstanceKey,
        initialBottomAnchorReady = initialBottomAnchorReady,
        viewportMeasurementReady = viewportMeasurementReady,
        listBeyondBoundsItems = threadLazyWindowConsumerCounts.listBeyondBoundsItems,
        enabledRef = postFirstFrameBeyondBoundsEnabledRef,
        diagnosticsLogger = diagnosticsLogger,
    )
    LaunchedEffect(
        threadInstanceKey,
        pendingComposerLayoutBottomAnchor,
        measuredThreadViewportHeightDp,
        pendingFocusedMessageAnchorIdentityState.value,
        latestMessageLayoutKey,
        userScrollGeneration.value
    ) {
        if (!pendingComposerLayoutBottomAnchor) return@LaunchedEffect
        val measuredViewportHeightPx =
            with(threadDensity) { measuredThreadViewportHeightDp.dp.roundToPx() }
        // Observe Lazy layout only while a mixed epoch is pending. Ordinary
        // scroll/remeasure must not invalidate Thread composition or perturb
        // the target dispose/reinsert/draw chain that this probe measures.
        snapshotFlow {
            listState.layoutInfo.viewportStartOffset to
                listState.layoutInfo.viewportEndOffset
        }
            .distinctUntilChanged()
            .first { (layoutStartPx, layoutEndPx) ->
                shouldAdvanceThreadComposerViewportRetry(
                    pendingLayoutChange = pendingComposerLayoutBottomAnchor,
                    measuredViewportHeightPx = measuredViewportHeightPx,
                    layoutViewportStartPx = layoutStartPx,
                    layoutViewportEndPx = layoutEndPx
                )
            }
        if (pendingComposerLayoutBottomAnchor) {
            composerLayoutViewportRetryGeneration += 1L
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        composerKeyboardInsetDp,
        measuredThreadViewportHeightDp,
        composerLayoutViewportRetryGeneration,
        initialBottomAnchorReady,
        latestItemIndex,
        allowBottomAutoAnchor,
        pendingFocusedMessageAnchorIdentityState.value,
        consumedFocusedMessageAnchorIdentity,
        scrollInterruptedFocusedMessageAnchorIdentity,
        focusedAnchorSessionIdentity,
        focusedAnchorStartUserScrollGeneration,
        userScrollGeneration.value,
        latestMessageListItemIndex,
        latestMessageLayoutKey,
        bottomAnchorFocusedYieldElapsedMs,
        newerPageCycleActive
    ) {
        if (newerPageCycleActive) {
            lastComposerKeyboardInsetDp = composerKeyboardInsetDp
            lastThreadViewportHeightDp = measuredThreadViewportHeightDp
            pendingComposerLayoutBottomAnchor = false
            pendingKeyboardBottomAnchor = false
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        // task #990 kill-switch: on stop-loss platforms, the moment the
        // keyboard starts to rise an in-flight programmatic anchor is cancelled
        // rather than allowed to finish into the shrinking window; the gates
        // below then keep every correction path silent while it stays open.
        // The cancel itself lives in a shared seam so the behaviour test
        // executes this code, not a copy of it.
        cancelInflightAnchorOnImeRise(
            keyboardInsetDp = composerKeyboardInsetDp,
            lastKeyboardInsetDp = lastComposerKeyboardInsetDp,
            controllerState = viewportControllerState,
            policy = imeProgrammaticViewportPolicy,
            dispatch = { dispatchViewportEvent(it) },
            log = { diagnosticsLogger(it) },
        )
        val focusedTargetIdentity = pendingFocusedMessageAnchorIdentityState.value
        val focusedTargetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() }
        val focusedTargetItemIndex = renderPayload.focusedMessageListItemIndex()
        val focusedTailMessageId = renderPayload.messages.lastOrNull()?.id
        val imeHandoffIdentity =
            focusedAnchorImeHandoffIdentity(
                keyboardInsetDp = composerKeyboardInsetDp,
                targetIdentity = focusedTargetIdentity,
                anchoredIdentity = focusedAnchorSessionIdentity,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value
            )
        if (imeHandoffIdentity != null) {
            consumedFocusedMessageAnchorIdentity = imeHandoffIdentity
            val handoffTransfersToBottom =
                focusedAnchorImeHandoffTransfersToBottom(
                    handoffIdentity = imeHandoffIdentity,
                    targetItemIndex = focusedTargetItemIndex,
                    latestMessageItemIndex = latestMessageListItemIndex,
                    canLoadMoreAfter = renderPayload.canLoadMoreAfter
                )
            if (handoffTransfersToBottom) {
                allowBottomAutoAnchor = true
            }
            // The composer now owns the shrunken viewport. A late origin-row
            // correction belongs to the retired route-focus session and must
            // not become a second scroll actor after the handoff.
            correctedFocusedOriginIdentity = imeHandoffIdentity
            diagnosticsLogger(
                "thread_focused_anchor_ime_handoff outcome=consumed " +
                    "bottomAutoAnchor=$handoffTransfersToBottom " +
                    "messageId=${focusedTargetMessageId.orEmpty()}"
            )
        }
        val focusedAnchorOwnsViewport =
            focusedMessageAnchorOwnsViewport(
                pendingIdentity = focusedTargetIdentity,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
            )
        val measuredFocusedTailViewportHeightPx =
            with(threadDensity) { measuredThreadViewportHeightDp.dp.roundToPx() }
        var focusedTailViewportEpochCoherent =
            threadViewportEpochCoherent(
                measuredViewportHeightPx = measuredFocusedTailViewportHeightPx,
                layoutViewportStartPx = listState.layoutInfo.viewportStartOffset,
                layoutViewportEndPx = listState.layoutInfo.viewportEndOffset
            )
        var focusedTailFit =
            listState.threadFocusedTailFit(
                targetItemIndex = focusedTargetItemIndex,
                latestMessageItemIndex = latestMessageListItemIndex,
                bottomPaddingItemIndex = latestItemIndex,
                viewportHeightPx =
                    measuredFocusedTailViewportHeightPx.takeIf { focusedTailViewportEpochCoherent } ?: -1,
                trailingPaddingPx = initialBottomAnchorTrailingPaddingPx
            )
        val measurementViewportEpoch = viewportControllerState.epoch
        val measurementLayoutGeneration = viewportLayoutGeneration
        val focusedTailMeasurementLayoutChanged =
            pendingComposerLayoutBottomAnchor ||
                lastComposerKeyboardInsetDp != composerKeyboardInsetDp ||
                lastThreadViewportHeightDp != measuredThreadViewportHeightDp
        if (focusedTailMeasurementLayoutChanged) {
            if (composerKeyboardInsetDp > 0f) {
                imeTailDrawProbe.beginOrContinue(focusedTargetMessageId ?: focusedTailMessageId)
            } else {
                imeTailDrawProbe.finish()
            }
        }
        fun publishImeTailDrawProbe(phase: String) {
            val snapshot = imeTailDrawProbe.snapshot()
            if (snapshot.generation <= 0L) return
            val metadata =
                mapOf(
                    "probeGeneration" to snapshot.generation.toString(),
                    "targetMessageId" to snapshot.targetMessageId.orEmpty(),
                    "placedCount" to snapshot.placedCount.toString(),
                    "drawCount" to snapshot.drawCount.toString(),
                    "measuredViewportPx" to measuredFocusedTailViewportHeightPx.toString(),
                    "layoutViewportPx" to
                        (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toString(),
                    "viewportEpochCoherent" to focusedTailViewportEpochCoherent.toString(),
                    "targetVisible" to
                        listState.layoutInfo.visibleItemsInfo
                            .any { item -> item.index == focusedTargetItemIndex }
                            .toString()
                )
            diagnosticsLogger(
                "thread_ime_tail_draw_probe phase=$phase " +
                    metadata.entries.joinToString(separator = " ") { (key, value) -> "$key=$value" }
            )
            onComposerLayoutProbe("thread_ime_tail_draw_probe_$phase", metadata)
        }
        fun focusedTailMeasurementOwnerStillCurrent(): Boolean {
            val currentPayload = currentPayloadState.value
            if (
                currentThreadInstanceKeyState.value != threadInstanceKey ||
                currentViewportControllerState.value.epoch != measurementViewportEpoch ||
                currentViewportLayoutGenerationState.value != measurementLayoutGeneration ||
                pendingFocusedMessageAnchorIdentityState.value != focusedTargetIdentity ||
                currentPayload.focusedMessageId != focusedTargetMessageId ||
                currentPayload.messages.lastOrNull()?.id != focusedTailMessageId ||
                currentPayload.latestMessageLayoutKey() != latestMessageLayoutKey ||
                currentPayload.focusedMessageListItemIndex() != focusedTargetItemIndex
            ) {
                return false
            }
            val currentFocusedOwner =
                focusedMessageAnchorOwnsViewport(
                    pendingIdentity = focusedTargetIdentity,
                    consumedIdentity = consumedFocusedMessageAnchorIdentity,
                    scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                    yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
                )
            return (
                currentFocusedOwner &&
                    focusedActiveTailMessageComposerLayoutOwnsViewport(
                        targetIdentity = focusedTargetIdentity,
                        targetItemIndex = focusedTargetItemIndex,
                        latestMessageItemIndex = latestMessageListItemIndex,
                        targetToTailFitsViewport = true,
                        canLoadMoreAfter = currentPayload.canLoadMoreAfter,
                        anchoredIdentity = focusedAnchorSessionIdentity,
                        consumedIdentity = consumedFocusedMessageAnchorIdentity,
                        scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                        startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                        currentUserScrollGeneration = userScrollGeneration.value
                    )
                ) ||
                focusedTailMessageComposerLayoutOwnsViewport(
                    targetIdentity = focusedTargetIdentity,
                    targetItemIndex = focusedTargetItemIndex,
                    latestMessageItemIndex = latestMessageListItemIndex,
                    targetToTailFitsViewport = true,
                    canLoadMoreAfter = currentPayload.canLoadMoreAfter,
                    anchoredIdentity = focusedAnchorSessionIdentity,
                    consumedIdentity = consumedFocusedMessageAnchorIdentity,
                    scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                    startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                    currentUserScrollGeneration = userScrollGeneration.value
                )
        }
        if (
            focusedTailMeasurementLayoutChanged &&
            focusedTailFit.fitHeightPx < 0 &&
            focusedTailMeasurementOwnerStillCurrent()
        ) {
            var remainingMeasurementFrames = ThreadFocusedTailMeasurementRetryFrames
            while (remainingMeasurementFrames > 0 && focusedTailFit.fitHeightPx < 0) {
                remainingMeasurementFrames -= 1
                listState.awaitThreadMessagesLayoutFrame()
                if (!focusedTailMeasurementOwnerStillCurrent()) break
                focusedTailViewportEpochCoherent =
                    threadViewportEpochCoherent(
                        measuredViewportHeightPx = measuredFocusedTailViewportHeightPx,
                        layoutViewportStartPx = listState.layoutInfo.viewportStartOffset,
                        layoutViewportEndPx = listState.layoutInfo.viewportEndOffset
                    )
                focusedTailFit =
                    listState.threadFocusedTailFit(
                        targetItemIndex = focusedTargetItemIndex,
                        latestMessageItemIndex = latestMessageListItemIndex,
                        bottomPaddingItemIndex = latestItemIndex,
                        viewportHeightPx =
                            measuredFocusedTailViewportHeightPx
                                .takeIf { focusedTailViewportEpochCoherent }
                                ?: -1,
                        trailingPaddingPx = initialBottomAnchorTrailingPaddingPx
                    )
            }
        }
        val layoutState =
            ThreadComposerLayoutBottomAnchorState(
                previousKeyboardInsetDp = lastComposerKeyboardInsetDp,
                nextKeyboardInsetDp = composerKeyboardInsetDp,
                imeProgrammaticViewportPolicy = imeProgrammaticViewportPolicy,
                previousViewportHeightDp = lastThreadViewportHeightDp,
                nextViewportHeightDp = measuredThreadViewportHeightDp,
                pendingLayoutChange = pendingComposerLayoutBottomAnchor,
                initialBottomAnchorReady = initialBottomAnchorReady,
                latestItemIndex = latestItemIndex,
                allowBottomAutoAnchor = allowBottomAutoAnchor,
                viewportEpochCoherent = focusedTailViewportEpochCoherent,
                focusedAnchorOwnsViewport = focusedAnchorOwnsViewport,
                focusedActiveTailAnchorCanCorrectLayout =
                    focusedAnchorOwnsViewport &&
                        focusedActiveTailMessageComposerLayoutOwnsViewport(
                            targetIdentity = focusedTargetIdentity,
                            targetItemIndex = focusedTargetItemIndex,
                            latestMessageItemIndex = latestMessageListItemIndex,
                            targetToTailFitsViewport = focusedTailFit.fitsViewport,
                            canLoadMoreAfter = renderPayload.canLoadMoreAfter,
                            anchoredIdentity = focusedAnchorSessionIdentity,
                            consumedIdentity = consumedFocusedMessageAnchorIdentity,
                            scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                            startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                            currentUserScrollGeneration = userScrollGeneration.value
                        ),
                focusedTailAnchorCanCorrectLayout =
                    focusedTailMessageComposerLayoutOwnsViewport(
                        targetIdentity = focusedTargetIdentity,
                        targetItemIndex = focusedTargetItemIndex,
                        latestMessageItemIndex = latestMessageListItemIndex,
                        targetToTailFitsViewport = focusedTailFit.fitsViewport,
                        canLoadMoreAfter = renderPayload.canLoadMoreAfter,
                        anchoredIdentity = focusedAnchorSessionIdentity,
                        consumedIdentity = consumedFocusedMessageAnchorIdentity,
                        scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                        startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                        currentUserScrollGeneration = userScrollGeneration.value
                    )
            )
        val layoutDecision = resolveThreadComposerLayoutBottomAnchor(layoutState)
        val layoutChanged = threadComposerLayoutChanged(layoutState)
        // Only the parked-bottom path replays the controller intent; the focused-tail path
        // dispatches its own RenderedBottom anchor below.
        val insetViewportAction =
            if (layoutDecision.shouldAnchor && !layoutDecision.anchorFocusedTailAtRenderedBottom) {
                resolveThreadComposerInsetViewportAction(
                    reverseLayout = listState.reverse,
                    intent = viewportControllerState.intent,
                    tailAtRenderedBottom = listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)
                )
            } else {
                null
            }
        publishImeTailDrawProbe("decision")
        if (layoutChanged || layoutState.pendingLayoutChange) {
            val anchoredIntent = viewportControllerState.intent as? LazyViewportIntent.Anchored
            diagnosticsLogger(
                "thread_composer_layout_decision actor=composer_inset_relayout " +
                    "keyboardInset=${layoutState.previousKeyboardInsetDp}->${layoutState.nextKeyboardInsetDp} " +
                    "viewportHeight=${layoutState.previousViewportHeightDp}->${layoutState.nextViewportHeightDp} " +
                    "pendingBefore=${layoutState.pendingLayoutChange} pendingAfter=${layoutDecision.pendingLayoutChange} " +
                    "shouldAnchor=${layoutDecision.shouldAnchor} " +
                    "placement=${if (layoutDecision.anchorFocusedTailAtRenderedBottom) "rendered_tail_bottom" else "replay_intent"} " +
                    "targetIsLatest=${focusedTargetItemIndex >= 0 && focusedTargetItemIndex == latestMessageListItemIndex} " +
                    "targetIndex=$focusedTargetItemIndex latestMessageIndex=$latestMessageListItemIndex " +
                    "latestViewportItemIndex=$latestItemIndex trailingPaddingPx=$initialBottomAnchorTrailingPaddingPx " +
                    "fitHeightPx=${focusedTailFit.fitHeightPx} fitViewportPx=${focusedTailFit.viewportHeightPx} " +
                    "measuredViewportPx=$measuredFocusedTailViewportHeightPx " +
                    "viewportEpochCoherent=$focusedTailViewportEpochCoherent " +
                    "tailSpanPx=${focusedTailFit.trailingSpanPx} tailFits=${focusedTailFit.fitsViewport} " +
                    "measurementResult=${if (focusedTailFit.fitHeightPx >= 0) "available" else "measurement_unavailable"} " +
                    "canLoadMoreAfter=${renderPayload.canLoadMoreAfter} " +
                    "focusedOwns=$focusedAnchorOwnsViewport consumed=${consumedFocusedMessageAnchorIdentity == focusedTargetIdentity} " +
                    "interrupted=${scrollInterruptedFocusedMessageAnchorIdentity == focusedTargetIdentity} " +
                    "userGeneration=${focusedAnchorStartUserScrollGeneration ?: -1}->${userScrollGeneration.value} " +
                    "viewportIntent=${viewportControllerState.intent.threadViewportIntentDiagnosticName()} " +
                    "viewportPlacement=${anchoredIntent?.placement?.name ?: "none"} " +
                    "insetAction=${insetViewportAction?.diagnosticName ?: "none"} " +
                    listState.threadViewportGeometryDiagnostics(focusedTargetItemIndex)
            )
        }
        lastComposerKeyboardInsetDp = composerKeyboardInsetDp
        lastThreadViewportHeightDp = measuredThreadViewportHeightDp
        pendingComposerLayoutBottomAnchor = layoutDecision.pendingLayoutChange
        if (
            !layoutDecision.shouldAnchor ||
            insetViewportAction == ThreadComposerInsetViewportAction.KeepRenderedPosition
        ) {
            pendingKeyboardBottomAnchor = false
            return@LaunchedEffect
        }
        pendingKeyboardBottomAnchor = true
        viewportLayoutGeneration += 1L
        if (
            layoutDecision.anchorFocusedTailAtRenderedBottom &&
            focusedTargetMessageId != null &&
            focusedTailMessageId != null
        ) {
            focusedAnchorPlacementState =
                renderedThreadFocusedAnchorPlacement(
                    identity = requireNotNull(focusedTargetIdentity),
                    offsetPx = focusedTailFit.trailingSpanPx,
                    renderedTailMessageId = focusedTailMessageId
                )
            dispatchViewportEvent(
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message(focusedTargetMessageId),
                    offset = focusedTailFit.trailingSpanPx,
                    layoutGeneration = viewportLayoutGeneration,
                    placement = LazyViewportAnchorPlacement.RenderedBottom,
                    renderedTailMessageId = focusedTailMessageId,
                    renderedTailAnchorIdentity = focusedTargetIdentity,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        } else {
            // Parked-bottom owner. On a reversed list a stale Anchored intent is retired to
            // FollowingLatest rather than replayed (replaying top-aligns / centres the row).
            (insetViewportAction ?: ThreadComposerInsetViewportAction.ReplayIntent)
                .viewportEvent()
                ?.let { event ->
                    listState.noteDispatchOwner(ThreadViewportOwnerTag.ImeCorrection)
                    dispatchViewportEvent(event)
                }
        }
        val insetCommandId = viewportControllerState.pendingCommand?.id
        val pixelProbeThreadInstanceKey = threadInstanceKey
        val pixelProbeSnapshot = imeTailDrawProbe.snapshot()
        val pixelProbeGeneration = pixelProbeSnapshot.generation
        val pixelProbeTargetMessageId = pixelProbeSnapshot.targetMessageId
        val pixelProbeLayoutGeneration = viewportLayoutGeneration
        imeTailPixelProbeRunner.launch(
            awaitSettlement = {
                if (insetCommandId != null) {
                    awaitViewportCommandSettlement(insetCommandId)
                }
                // Command settlement commits placement, not necessarily the
                // subsequent native draw. Cross two frames before sampling.
                listState.awaitThreadMessagesLayoutFrame()
                listState.awaitThreadMessagesLayoutFrame()
            },
            runProbe = probe@{
                val settledSnapshot = imeTailDrawProbe.snapshot()
                val settledOwnerCurrent =
                    threadViewportPixelProbeOwnerCurrent(
                        requestedThreadInstanceKey = pixelProbeThreadInstanceKey,
                        currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                        requestedLayoutGeneration = pixelProbeLayoutGeneration,
                        currentLayoutGeneration = currentViewportLayoutGenerationState.value,
                        requestedProbeGeneration = pixelProbeGeneration,
                        currentProbeGeneration = settledSnapshot.generation,
                        requestedTargetMessageId = pixelProbeTargetMessageId,
                        currentTargetMessageId = settledSnapshot.targetMessageId,
                        requestedFocusedMessageId = focusedTargetMessageId,
                        currentFocusedMessageId = currentPayloadState.value.focusedMessageId,
                        requestedTailMessageId = focusedTailMessageId,
                        currentTailMessageId = currentPayloadState.value.messages.lastOrNull()?.id
                    )
                if (!settledOwnerCurrent) {
                    diagnosticsLogger(
                        "thread_ime_tail_pixel_probe generation=$pixelProbeGeneration " +
                            "status=stale_before_claim threadInstanceCurrent=false " +
                            "layoutGeneration=$pixelProbeLayoutGeneration->${currentViewportLayoutGenerationState.value} " +
                            "targetMessageId=${pixelProbeTargetMessageId.orEmpty()}"
                    )
                    return@probe
                }
                pendingKeyboardBottomAnchor = false
                diagnosticsLogger(
                    "thread_composer_layout_settled actor=composer_inset_relayout " +
                        "command=${insetCommandId?.sequence ?: -1} " +
                        "placement=${if (layoutDecision.anchorFocusedTailAtRenderedBottom) "rendered_tail_bottom" else "replay_intent"} " +
                        "targetIndex=$focusedTargetItemIndex latestMessageIndex=$latestMessageListItemIndex " +
                        "latestViewportItemIndex=$latestItemIndex fitHeightPx=${focusedTailFit.fitHeightPx} " +
                        "fitViewportPx=${focusedTailFit.viewportHeightPx} tailSpanPx=${focusedTailFit.trailingSpanPx} " +
                        "measuredViewportPx=$measuredFocusedTailViewportHeightPx " +
                        "viewportEpochCoherent=$focusedTailViewportEpochCoherent " +
                        "renderedBottom=${listState.isLatestItemAtRenderedBottom(latestMessageListItemIndex, initialBottomAnchorTrailingPaddingPx, currentSparseTopAnchoredState.value)} " +
                        "latestItemOffBottom=${listState.shouldShowThreadBackToBottom(latestItemIndex)} " +
                        "viewportIntent=${viewportControllerState.intent.threadViewportIntentDiagnosticName()} " +
                        listState.threadViewportGeometryDiagnostics(focusedTargetItemIndex)
                )
                publishImeTailDrawProbe("settled")
                if (imeTailDrawProbe.claimPixelProbe() != pixelProbeGeneration) {
                    return@probe
                }
                if (!viewportPixelProbeBounds.ready()) {
                    diagnosticsLogger(
                        "thread_ime_tail_pixel_probe generation=$pixelProbeGeneration " +
                            "status=bounds_unavailable threadInstanceCurrent=true " +
                            "layoutGeneration=$pixelProbeLayoutGeneration->${currentViewportLayoutGenerationState.value} " +
                            "targetMessageId=${pixelProbeTargetMessageId.orEmpty()}"
                    )
                    return@probe
                }
                val pixelResult =
                    withTimeoutOrNull(ThreadViewportPixelProbeTimeoutMs) {
                        runCatching {
                            onComposerPixelProbe(
                                viewportPixelProbeBounds.x,
                                viewportPixelProbeBounds.y,
                                viewportPixelProbeBounds.width,
                                viewportPixelProbeBounds.height,
                                pixelProbeGeneration
                            )
                        }.getOrNull()
                    }
                val currentPixelProbeSnapshot = imeTailDrawProbe.snapshot()
                val pixelProbeOwnerCurrent =
                    threadViewportPixelProbeOwnerCurrent(
                        requestedThreadInstanceKey = pixelProbeThreadInstanceKey,
                        currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                        requestedLayoutGeneration = pixelProbeLayoutGeneration,
                        currentLayoutGeneration = currentViewportLayoutGenerationState.value,
                        requestedProbeGeneration = pixelProbeGeneration,
                        currentProbeGeneration = currentPixelProbeSnapshot.generation,
                        requestedTargetMessageId = pixelProbeTargetMessageId,
                        currentTargetMessageId = currentPixelProbeSnapshot.targetMessageId,
                        requestedFocusedMessageId = focusedTargetMessageId,
                        currentFocusedMessageId = currentPayloadState.value.focusedMessageId,
                        requestedTailMessageId = focusedTailMessageId,
                        currentTailMessageId = currentPayloadState.value.messages.lastOrNull()?.id
                    )
                val pixelProbeReceiptMatches =
                    pixelResult?.optString("generation")?.toLongOrNull() == pixelProbeGeneration
                val acceptedPixelResult =
                    pixelResult.takeIf { pixelProbeOwnerCurrent && pixelProbeReceiptMatches }
                val diagnosticStatus =
                    when {
                        pixelResult == null -> "infra_timeout"
                        !pixelProbeOwnerCurrent -> "stale_owner"
                        !pixelProbeReceiptMatches -> "stale_receipt"
                        acceptedPixelResult?.optString("status") == "timeout" -> "infra_timeout"
                        else -> acceptedPixelResult?.optString("status").orEmpty()
                    }
                diagnosticsLogger(
                    "thread_ime_tail_pixel_probe generation=$pixelProbeGeneration " +
                        "status=$diagnosticStatus " +
                        "threadInstanceCurrent=$pixelProbeOwnerCurrent " +
                        "layoutGeneration=$pixelProbeLayoutGeneration->${currentViewportLayoutGenerationState.value} " +
                        "targetMessageId=${pixelProbeTargetMessageId.orEmpty()} " +
                        "pixelCopyResult=${acceptedPixelResult?.optString("pixelCopyResult").orEmpty()} " +
                        "sampleCount=${acceptedPixelResult?.optString("sampleCount").orEmpty()} " +
                        "nonWhiteCount=${acceptedPixelResult?.optString("nonWhiteCount").orEmpty()} " +
                        "darkCount=${acceptedPixelResult?.optString("darkCount").orEmpty()} " +
                        "whitePermille=${acceptedPixelResult?.optString("whitePermille").orEmpty()} " +
                        "descendants=${acceptedPixelResult?.optString("descendants").orEmpty()} " +
                        "visibleDescendants=${acceptedPixelResult?.optString("visibleDescendants").orEmpty()} " +
                        "attachedDescendants=${acceptedPixelResult?.optString("attachedDescendants").orEmpty()} " +
                        "layoutRequestedDescendants=${acceptedPixelResult?.optString("layoutRequestedDescendants").orEmpty()} " +
                        "rootChild=${acceptedPixelResult?.optString("rootChild").orEmpty()}"
                )
            }
        )
    }
    ThreadReactionAtlasProvider {
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        viewportPixelProbeBounds.update(coordinates)
                    }
        ) {
            LaunchedEffect(threadInstanceKey, maxHeight) {
                measuredThreadViewportHeightDp = maxHeight.value
            }
            // Search in this thread's bar (web ThreadSearchBar, the strip under the panel's header)
            // lies over the top of the list, right under the header, out of the list's flow like the
            // offline tip: the list reserves its measured height at its top, so the bar never hides
            // the first rows or the origin card, and a match brought to the top (offset 0, measured
            // after the top padding) lands just under it. The keyboard lifts only the composer.
            val threadFindBarShown = threadFind != null && inlineSearchVisible
            var threadFindBarHeightPx by remember(threadInstanceKey) { mutableIntStateOf(0) }
            val threadFindBarTopInset =
                when {
                    !threadFindBarShown -> 0.dp
                    threadFindBarHeightPx > 0 -> with(LocalDensity.current) { threadFindBarHeightPx.toDp() }
                    else -> ThreadInlineSearchBarTopInset
                }
            if (offlineLastKnownChrome.tipVisible && !shareSelectionCaptureActive) {
                // Absolute overlay, out of the list's flow (artin, task #1125); the list reserves an
                // equal top inset below so the tip never hides the first row when scrolled to the top.
                // Not while "Generate image" captures the list: the export reads the window's pixels.
                // Under the find bar while it is open.
                ThreadOfflineLastKnownTip(
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .padding(top = threadFindBarTopInset)
                            .zIndex(ThreadOfflineLastKnownTipZIndex)
                )
            }
            val inPlaceSelectionViewportHost = LocalThreadInPlaceTextSelectionHost.current
            // Task v2 (every width): the head is the list's first row, at the top of the history
            // boundary row (web `mobile-modal`: the parent row over the history boundary), so it
            // moves with the conversation; the top bar summarizes the task while it is out of view.
            if (taskHeadRow != null) {
                LaunchedEffect(taskHeadRow, listState) {
                    snapshotFlow { listState.threadTaskHeadRowInView(taskHeadRow.scrollState.rowHeadHeightPx) }
                        .distinctUntilChanged()
                        .collect { inView -> taskHeadRow.scrollState.updateHeadInView(inView) }
                }
                // No longer a row of this list: the bar does not keep a summary for it (web).
                DisposableEffect(taskHeadRow) { onDispose { taskHeadRow.scrollState.updateHeadInView(true) } }
            }
            // Drag the list toward its start to see message times (ThreadMessageTimeReveal.kt): where
            // no tab pager surrounds the list, and not while messages are being selected.
            val timeRevealState = rememberThreadTimeRevealState(threadInstanceKey)
            val timeReveal =
                timeRevealState.takeIf {
                    LocalThreadTimeRevealAvailable.current && !shareSelectionActive && !shareSelectionCaptureActive
                }
            val timeRevealModifier =
                if (timeReveal != null) {
                    Modifier.threadTimeRevealGesture(
                        state = timeReveal,
                        isListScrolling = { listState.isScrollInProgress },
                        selectionShowing = { inPlaceSelectionViewportHost?.selectionShowing == true },
                    )
                } else {
                    Modifier
                }
            LazyColumn(
                state = listState.raw,
                reverseLayout = reverseLayout,
                modifier = Modifier.fillMaxSize()
                    .detachSafeLazyListPrefetch(threadListPrefetch)
                    .then(
                        if (inPlaceSelectionViewportHost?.enabled == true) {
                            // In-place selection bar stays inside the list's visible viewport.
                            Modifier.onGloballyPositioned { coordinates ->
                                inPlaceSelectionViewportHost.updateViewport(coordinates.boundsInWindow())
                            }
                        } else {
                            Modifier
                        }
                    )
                    .raftLongMessageCollapseViewport(
                        controller = longMessageCollapseController,
                        verticalContentPaddingPx =
                            with(LocalDensity.current) {
                                (if (offlineLastKnownChrome.insetActive) ThreadOfflineLastKnownTipHeight else 0.dp).roundToPx()
                            }
                    )
                    // Initial focused alignment and reading anchor: run after layout, before the frame draws.
                    .onGloballyPositioned {
                        alignInitialFocusedAfterLayout()
                        keepReadingAnchorAfterLayout()
                    }
                    // The forward layout keeps the newest message on the composer in the same
                    // measure pass as an IME/composer resize or a realtime tail append, instead
                    // of a correction one frame later (see RaftThreadImeLayout.kt).
                    .raftThreadTailLayoutAnchor(
                        listState = listState,
                        inputs =
                            ThreadTailLayoutAnchorInputs(
                                reverseLayout = reverseLayout,
                                followingLatest =
                                    viewportControllerState.intent == LazyViewportIntent.FollowingLatest,
                                itemCount = lazyItemCount,
                                firstMessageId = renderPayload.messages.firstOrNull()?.id,
                                lastMessageId = renderPayload.messages.lastOrNull()?.id,
                                newerPageCycleActive = newerPageCycleActive,
                                newerHistoryAvailable = renderPayload.canLoadMoreAfter,
                                imeProgrammaticViewportPolicy = imeProgrammaticViewportPolicy
                            ),
                        onTransition = { transition ->
                            when (transition) {
                                is ThreadTailLayoutAnchorTransition.Started -> {
                                    val state = transition.state
                                    diagnosticsLogger(
                                        "thread_tail_layout_anchor action=pin_tail_start " +
                                            "viewport=${state.previousViewportPx}->${state.nextViewportPx} " +
                                            "items=${state.previousItemCount}->${state.nextItemCount}"
                                    )
                                }
                                is ThreadTailLayoutAnchorTransition.Ended ->
                                    diagnosticsLogger(
                                        "thread_tail_layout_anchor action=pin_tail_end " +
                                            "passes=${transition.pinnedPasses}"
                                    )
                            }
                        }
                    )
                    // Outside the list's scroll, so it sees every change first once it claimed.
                    .then(timeRevealModifier)
                    .keyboardDismissModeInteractiveIOS()
                    // A mouse wheel or trackpad scrolls without a touch: the touch listener below
                    // never sees it (task #253 / #258).
                    .threadListWheelScroll(userScrollGeneration, newerGestureDemandTracker)
                    .touchListenerWithConfiguration { type, position, touchSlop ->
                        newerGestureDemandTracker.onTouch(
                            type = type,
                            pointerY = position.y,
                            touchSlop = touchSlop
                        )
                        when (type) {
                            TouchType.Down -> {
                                newerPointerGestureActive = true
                                dispatchViewportEvent(LazyViewportEvent.PointerDown)
                            }
                            TouchType.Move -> {
                                dispatchViewportEvent(LazyViewportEvent.PointerMove)
                            // Count every direct pointer move, not inertial/programmatic
                            // scrolling. Older-page completion may safely cancel the
                            // original top fling and restore its message anchor, but a
                            // new/continued drag after the request started owns the
                            // viewport and must never be yanked back.
                            userScrollGeneration.value += 1
                            // A user drag retires the pending focused-message
                            // anchor request: growth-driven retries must never
                            // scroll against an interacting user (task #554).
                            val pendingAnchorIdentity = pendingFocusedMessageAnchorIdentityState.value
                            if (
                                pendingAnchorIdentity != null &&
                                scrollInterruptedFocusedMessageAnchorIdentity != pendingAnchorIdentity
                            ) {
                                scrollInterruptedFocusedMessageAnchorIdentity = pendingAnchorIdentity
                            }
                            if (latestLocalSendBottomAnchorActiveKey != null) {
                                latestLocalSendBottomAnchorActiveKey = null
                                allowBottomAutoAnchor = false
                            }
                            }
                            TouchType.Up -> {
                                newerPointerGestureActive = false
                                val settlingEpoch = viewportControllerState.epoch
                                coroutineScope.launch {
                                    withFrameNanos { }
                                    snapshotFlow { listState.isScrollInProgress }
                                        .first { inProgress -> !inProgress }
                                    if (
                                        viewportControllerState.epoch != settlingEpoch ||
                                        viewportControllerState.intent != LazyViewportIntent.UserDragging
                                    ) {
                                        return@launch
                                    }
                                    val anchor =
                                        listState.threadFirstVisibleMessageAnchor(
                                            timelineSlice = currentPayloadState.value,
                                            userScrollGeneration = userScrollGeneration.value
                                        )
                                    newerPageCycle
                                        ?.takeIf { cycle ->
                                            cycle.requestViewportEpoch == settlingEpoch
                                        }
                                        ?.let { cycle ->
                                            newerPageCycle = cycle.copy(anchor = anchor)
                                        }
                                    // The row being read (top in view), which the reading-anchor
                                    // keeper and the HeightChange replays then hold in place.
                                    val readingAnchor =
                                        listState.threadReadingAnchorMessage(
                                            timelineSlice = currentPayloadState.value,
                                            userScrollGeneration = userScrollGeneration.value
                                        )
                                    val settledAtBottom =
                                        listState.isLatestItemAtRenderedBottom(
                                            latestItemIndex = currentLatestItemIndexState.value,
                                            allowSparseTopFit = currentSparseTopAnchoredState.value,
                                        )
                                    dispatchViewportEvent(
                                        LazyViewportEvent.PointerSettled(
                                            epoch = settlingEpoch,
                                            atRenderedBottom = settledAtBottom,
                                            anchorMessageId = readingAnchor?.messageId,
                                            anchorOffset = readingAnchor?.offset ?: 0,
                                            layoutGeneration = viewportLayoutGeneration
                                        )
                                    )
                                }
                            }
                        }
                    }
                    .alpha(
                        threadMessagesListAlpha(
                            initialBottomAnchorReady = initialBottomAnchorReady,
                            latestItemIndex = latestItemIndex,
                            revealLatestLocalSendBeforeInitialAnchor = revealLatestLocalSendBeforeInitialAnchor
                        )
                    ),
                verticalArrangement = if (sparseTopAnchored) Arrangement.Top else Arrangement.Bottom,
                contentPadding =
                    PaddingValues(
                        top =
                            (if (offlineLastKnownChrome.insetActive) ThreadOfflineLastKnownTipHeight else 0.dp) +
                                threadFindBarTopInset,
                        // The thread timeline's rail keeps clear of message content (web `--message-rail-inset`).
                        end = if (LocalThreadOutlineState.current?.railShown == true) ThreadOutlineRailInset else 0.dp,
                    ),
            ) {
            threadMessageItems(
                renderConfig = renderConfig,
                messageBodyTextStyle = messageBodyTextStyle,
                timelineSlice = timelineSlice,
                replyMetadataByParentId = replyMetadataByParentId,
                activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
                linkedTasksProjection = linkedTasksProjection,
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
                isMarkdownTagValid = isMarkdownTagValid,
                markdownTagContextSignature = markdownTagContextSignature,
                resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                activeMarkdownTagKey = activeMarkdownTagKey,
                onThreadEvent = onThreadEvent,
                onOpenMember = onOpenMember,
                onExternalAuthor = onExternalAuthor,
                onMentionAuthor = onMentionAuthor,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                quotedPermalinkLoader = quotedPermalinkLoader,
                diagnosticsLogger = diagnosticsLogger,
                shareSelectionActive = shareSelectionActive,
                shareSelectionCaptureActive = shareSelectionCaptureActive,
                shareSelectionCaptureTailPaddingActive = shareSelectionCaptureTailPaddingActive,
                selectedShareMessageIds = selectedShareMessageIds,
                onShareSelectionBounds = onShareSelectionBounds,
                onForwardMessage = onForwardMessage,
                onToggleShareSelection = onToggleShareSelection,
                onOpenForwardBundle = onOpenForwardBundle,
                translationStore = translationStore,
                translationState = translationState,
                renderPayload = renderPayload,
                taskHeadRow = taskHeadRow,
                listHiddenHistory = listHiddenHistory,
                historyPaywall = historyPaywall,
                peerReadReceipts = peerReadReceipts,
                dmReadStatusMessageId = dmReadStatusMessageId,
                showReplyLoadingSkeleton = showReplyLoadingSkeleton,
                showReplyEmptyState = showReplyEmptyState,
                replyLoadingSkeletonVisible = replyLoadingSkeletonVisible,
                olderBoundaryStatusSpec = olderBoundaryStatusSpec,
                visibleBoundaryLoadingSlot = visibleBoundaryLoadingSlot,
                bottomSpacerAndAnchorTrailingHeight = bottomSpacerAndAnchorTrailingHeight,
                reverseLayout = reverseLayout,
                listState = listState,
                duplicateMessageRandomIds = duplicateMessageRandomIds,
                threadRowProbeSessionMarker = threadRowProbeSessionMarker,
                firstViewportProbe = firstViewportProbe,
                imeTailDrawProbe = imeTailDrawProbe,
                firstViewportProbeActiveState = firstViewportProbeActiveState,
                menuState = menuState,
                longMessageCollapseController = longMessageCollapseController,
                recordMessageItemHeight = recordMessageItemHeight,
                toggleLongMessage = toggleLongMessage,
                keepReadingAnchorAfterLayout = keepReadingAnchorAfterLayout,
                onThreadEventKeepingAnchor = onThreadEventKeepingAnchor,
                activeFocusedMessageIdState = activeFocusedMessageIdState,
                alignInitialFocusedAfterLayout = alignInitialFocusedAfterLayout,
                activeInlineSearchMessageId = activeInlineSearchMessageId,
                systemMessageFolds = systemMessageFolds,
                taskHandoffTimelineById = taskHandoffTimelineById,
                expandedSystemMessageGroupsState = expandedSystemMessageGroupsState,
                toggleSystemMessageGroup = toggleSystemMessageGroup,
                requestRowExpandReveal = requestRowExpandReveal,
                handoffReasonMarkdown = handoffReasonMarkdown,
                requestNewerPage = requestNewerPage,
                outlineOriginJumpRequestState = outlineOriginJumpRequestState,
                timeReveal = timeReveal,
            )
        }
        if (threadFind != null) {
            ThreadFindBarHost(
                controller = threadFind,
                modifier = Modifier.align(Alignment.TopCenter)
                    .zIndex(ThreadInlineSearchBarZIndex)
                    .onSizeChanged { size -> threadFindBarHeightPx = size.height }
            )
        }
        // Read through a derived state: this scope is huge, and reading canScrollForward /
        // layoutInfo here directly recomposed all of it on every scroll frame while scrolled up.
        // It now recomposes only when one of the two booleans flips.
        val backToBottomViewportSignals by rememberThreadBackToBottomViewportSignals(listState, latestItemIndex)
        val shouldShowBackToBottomButton =
            shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = initialBottomAnchorReady,
                    pendingKeyboardBottomAnchor = pendingKeyboardBottomAnchor,
                    bottomAutoAnchorOwnsViewport = allowBottomAutoAnchor,
                    canScrollTowardBottom = backToBottomViewportSignals.canScrollTowardBottom,
                    canonicalTailMissing = renderPayload.canLoadMoreAfter,
                    latestItemOffBottom = backToBottomViewportSignals.latestItemOffBottom
                )
            )
        RaftScrollFrameSignalEffect(list = "thread") { listState.isScrollInProgress }
        // One line whenever an input of the "Back to bottom" pill changes (not on every scroll frame),
        // with the raw list position at that moment: reversed lists left the pill on after a local send
        // (HuaTuo, #raft-mobile-reconcile:6dbbf8b9) and the pill's inputs were not logged anywhere.
        val bottomJumpCount =
            threadBottomJumpCount(
                canLoadMoreAfter = renderPayload.canLoadMoreAfter,
                conversationUnreadCount = jumpInputs.conversationUnreadCount,
                newMessagesBelow = newMessagesBelow.count,
            )
        val backToBottomPillInputs =
            "visible=$shouldShowBackToBottomButton count=$bottomJumpCount newBelow=${newMessagesBelow.count} " +
                "laidOut=${backToBottomViewportSignals.latestItemLaidOut} " +
                "reverse=${listState.reverse} allow=$allowBottomAutoAnchor " +
                "canScrollForward=${backToBottomViewportSignals.canScrollTowardBottom} " +
                "latestItemOffBottom=${backToBottomViewportSignals.latestItemOffBottom} " +
                "tailMissing=${renderPayload.canLoadMoreAfter} kbd=$pendingKeyboardBottomAnchor " +
                "scrolling=${listState.isScrollInProgress}"
        LaunchedEffect(threadInstanceKey, backToBottomPillInputs) {
            diagnosticsLogger(
                "thread_back_to_bottom_pill $backToBottomPillInputs " +
                    "rawFirst=${listState.raw.firstVisibleItemIndex}:${listState.raw.firstVisibleItemScrollOffset} " +
                    listState.threadViewportGeometryDiagnostics(latestItemIndex)
            )
        }
        val backToBottomMounted =
            !shareSelectionCaptureActive &&
                (!newerPageCycleActive || backToBottomInteractionGuardActive) &&
                shouldKeepThreadBackToBottomOverlayMounted(
                    shouldShowButton = shouldShowBackToBottomButton,
                    interactionGuardActive = backToBottomInteractionGuardActive
                )
        val jumpToastPager = LocalActivity.current.getPager() as? Pager
        val jumpToastBridge = remember(jumpToastPager) { jumpToastPager?.let { Utils.bridgeModule(it.pagerId) } }
        val jumpLoadFailedLabel = stringResource(Res.string.thread_jump_load_failed)
        val jumpRetryLabel = stringResource(Res.string.common_retry)
        lateinit var onBackToBottomClick: () -> Unit
        onBackToBottomClick = onClick@{
            // One tap is one jump: further taps while it loads or scrolls do nothing (no second request).
            if (backToBottomInteractionGuardActive) {
                diagnosticsLogger("thread_back_to_bottom_click result=ignored reason=in_flight loading=$backToBottomLoading")
                return@onClick
            }
            // Keep both the visual button and its native dispatch
            // capture sibling mounted until the scroll has settled.
            // Removing them from inside the release callback lets the
            // host finish the same pointer sequence on the message row
            // underneath (Hands 71862159). Activity's "x updates"
            // button follows the same lifetime rule: it clears its
            // visibility state only after the scroll completes.
            backToBottomInteractionGuardActive = true
            // Explicit user navigation: end any active open trace as UserTookOver before retiring
            // focus or jumping to latest (#proj-mobile:#258).
            listState.noteUserNavigation()
            // A focused Activity/message anchor owns the viewport for a
            // short stabilization session. Back-to-bottom is an
            // explicit replacement viewport intent, so retire that
            // session before moving the list. Otherwise its next
            // drift probe sees the focused row leave the viewport and
            // immediately re-anchors above the bottom (Hands
            // edecc106).
            val retiredFocusedAnchorIdentity =
                focusedAnchorIdentityForViewportReplacement(
                    pendingIdentity = pendingFocusedMessageAnchorIdentityState.value,
                    sessionIdentity = focusedAnchorSessionIdentity
                )
            if (retiredFocusedAnchorIdentity != null) {
                scrollInterruptedFocusedMessageAnchorIdentity = retiredFocusedAnchorIdentity
                activeFocusedMessageId = null
            }
            diagnosticsLogger(
                "thread_back_to_bottom_click latestItemIndex=$latestItemIndex " +
                    "retiredFocusedAnchor=${retiredFocusedAnchorIdentity != null} " +
                    "canLoadMoreAfter=${renderPayload.canLoadMoreAfter}"
            )
            // The newest messages are not loaded: the jump asks for them first, and says so.
            backToBottomLoading = renderPayload.canLoadMoreAfter
            coroutineScope.launch {
                try {
                    val clickUserScrollGeneration = userScrollGeneration.value
                    val outcome =
                        try {
                            onJumpToLatest()
                        } finally {
                            backToBottomLoading = false
                        }
                    val canonicalTailLoaded = outcome == ThreadLatestJumpOutcome.Landed
                    if (outcome == ThreadLatestJumpOutcome.Failed) {
                        // The button is back (not busy); the toast names the failure, with the failed
                        // request's trace ID when there is one, and offers the jump again.
                        jumpToastBridge?.errorToastWithRetry(
                            content = jumpLoadFailedLabel,
                            retryLabel = jumpRetryLabel,
                            onRetry = { onBackToBottomClick() },
                        )
                    }
                    if (
                        !shouldCompleteThreadBackToBottom(
                            canonicalTailLoaded = canonicalTailLoaded,
                            clickUserScrollGeneration = clickUserScrollGeneration,
                            currentUserScrollGeneration = userScrollGeneration.value
                        )
                    ) {
                        diagnosticsLogger(
                            "thread_back_to_bottom_settlement result=yielded " +
                                "canonicalTailLoaded=$canonicalTailLoaded " +
                                "clickUserScrollGeneration=$clickUserScrollGeneration " +
                                "currentUserScrollGeneration=${userScrollGeneration.value}"
                        )
                        return@launch
                    }
                    allowBottomAutoAnchor = true
                    dispatchViewportEvent(
                        LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)
                    )
                    val commandId = viewportControllerState.pendingCommand?.id
                    if (commandId != null) {
                        awaitViewportCommandSettlement(commandId)
                    }
                } finally {
                    // A new user drag can cancel the scroll mutation.
                    // Always release the guard, but only after Android's
                    // native release frame can no longer be retargeted.
                    withContext(NonCancellable) {
                        delay(ThreadBackToBottomReleaseGuardMillis)
                        backToBottomInteractionGuardActive = false
                    }
                }
            }
        }
        // Web ChatPanel `newMessageCount`: arrivals below while the Back to Bottom pill shows.
        ThreadNewMessagesBelowEffect(
            instanceKey = threadInstanceKey,
            tailSignature = renderPayload.messages.size to renderPayload.messages.lastOrNull()?.id,
            awayFromBottom = shouldShowBackToBottomButton,
            canLoadMoreAfter = renderPayload.canLoadMoreAfter,
            viewportMeasured = backToBottomViewportSignals.latestItemLaidOut,
            current = { newMessagesBelow },
            onChange = { newMessagesBelow = it },
            arrivals = {
                renderPayload.messages.map { message ->
                    ThreadJumpArrival(messageId = message.id, countable = !message.hasLocalSendState())
                }
            },
        )
        val unreadThreadJumpActive =
            jumpInputs.unreadThreadPillEnabled &&
                routeActive &&
                hostForeground &&
                !inlineSearchVisible &&
                !shareSelectionCaptureActive
        val unreadThreadJump =
            if (unreadThreadJumpActive) {
                projectThreadUnreadThreadJump(
                    unreadThreadIds = unreadThreadIds,
                    messages = jumpParentCandidates,
                    visibleMessageIndexes = visibleMessageIndexes,
                    dismissedAtCount = unreadThreadJumpDismissedAtCount,
                    unloadedParents = jumpInputs.unreadThreadParents,
                    loadedRange =
                        ThreadJumpLoadedRange(
                            newestCreatedAtEpochMillis = renderPayload.messages.lastOrNull()?.createdAtEpochMillis,
                            hasNewer = renderPayload.canLoadMoreAfter,
                        ),
                )
            } else {
                null
            }
        val unreadThreadPill: @Composable (ThreadUnreadThreadJump) -> Unit = { jump ->
            ThreadUnreadThreadJumpPill(
                jump = jump,
                busy = jumpInputs.jumpLoading,
                onJump = {
                    // Web: busy from the tap until the parent lands; a second tap does nothing.
                    if (!jumpInputs.jumpLoading) {
                        listState.noteUserNavigation()
                        diagnosticsLogger(
                            "thread_unread_thread_jump_click direction=${jump.direction} count=${jump.count} " +
                                "target=${jump.targetMessageId.take(8)}"
                        )
                        onThreadEvent(
                            when (jump.historyAction) {
                                ThreadUnreadJumpHistoryAction.FocusParent -> ThreadJumpToUnreadThreadParentAction
                                ThreadUnreadJumpHistoryAction.OpenThread -> ThreadOpenUnreadThreadPastHistoryAction
                                ThreadUnreadJumpHistoryAction.FocusParentAndRead -> ThreadJumpToUnreadThreadPastHistoryAction
                            },
                            jump.targetMessageId,
                            threadChannelId = jump.targetThreadChannelId,
                        )
                    }
                },
                onDismiss = { unreadThreadJumpDismissedAtCount = jump.count },
            )
        }
        val nextUnreadThread = unreadThreadJump?.takeIf { it.direction == ThreadUnreadThreadJumpDirection.Next }
        // Select to here (ThreadSelectToHere.kt, web useSelectToHere): in select mode, while the
        // anchor is out of view. Only reads the viewport; a tap changes the selection, never the
        // scroll position, so no viewport intent is taken here.
        val selectToHere = shareSelectToHere?.takeIf { shareSelectionActive && !shareSelectionCaptureActive }
        val selectToHereOrderedIds =
            remember(renderPayload.originMessage?.id, renderPayload.messages) {
                listOfNotNull(renderPayload.originMessage?.id) + renderPayload.messages.map { it.id }
            }
        val selectToHereKeyToMessageId =
            remember(renderPayload.messages, renderPayload.originMessage?.id, duplicateMessageRandomIds) {
                buildMap<Any, String> {
                    renderPayload.originMessage?.id?.let { put(ThreadOriginListItemKey, it) }
                    renderPayload.messages.forEach { put(it.messageListItemKey(duplicateMessageRandomIds), it.id) }
                }
            }
        val selectToHereVisibleRowMinPx = with(LocalDensity.current) { ThreadSelectToHereVisibleRowMin.roundToPx() }
        // What covers the list's bottom: the typing line (web `--message-list-bottom-inset`).
        val selectToHereBottomOcclusionPx =
            with(LocalDensity.current) { if (agentTypingLineShown) ThreadAgentTypingLineHeight.roundToPx() else 0 }
        val selectToHereVisibleIds: () -> List<String> = {
            val info = listState.layoutInfo
            threadSelectToHereVisibleMessageIds(
                rows = info.visibleItemsInfo.map { ThreadSelectToHereRow(it.key, it.offset, it.size) },
                viewportStart = info.viewportStartOffset,
                viewportEnd = info.viewportEndOffset - selectToHereBottomOcclusionPx,
                minVisiblePx = selectToHereVisibleRowMinPx,
                keyToMessageId = selectToHereKeyToMessageId,
            )
        }
        val selectToHereAnchorId = selectToHere?.anchorId
        val selectToHereDirection by remember(
            listState,
            selectToHereAnchorId,
            selectToHereOrderedIds,
            selectToHereKeyToMessageId,
            selectToHereVisibleRowMinPx,
            selectToHereBottomOcclusionPx,
        ) {
            derivedStateOf {
                selectToHereAnchorId?.let { anchor ->
                    threadSelectToHereDirection(selectToHereOrderedIds, anchor, selectToHereVisibleIds())
                }
            }
        }
        val selectToHerePillDirection = selectToHereDirection.takeIf { selectToHere != null }
        ThreadMessagesListOverlays(
            shareSelectionActive = shareSelectionActive,
            shareSelectionCaptureActive = shareSelectionCaptureActive,
            renderPayload = renderPayload,
            renderConfig = renderConfig,
            duplicateMessageRandomIds = duplicateMessageRandomIds,
            listState = listState,
            reverseLayout = reverseLayout,
            agentTyping = agentTyping,
            workerConversation = activeWorkerConversation,
            agentTypingLineShown = agentTypingLineShown,
            outlineOriginJumpRequestState = outlineOriginJumpRequestState,
            selectToHere = selectToHere,
            selectToHereOrderedIds = selectToHereOrderedIds,
            selectToHereVisibleIds = selectToHereVisibleIds,
            selectToHerePillDirection = selectToHerePillDirection,
            backToBottomMounted = backToBottomMounted,
            backToBottomLoadingState = backToBottomLoadingState,
            bottomJumpCount = bottomJumpCount,
            onBackToBottomClick = onBackToBottomClick,
            unreadThreadJump = unreadThreadJump,
            nextUnreadThread = nextUnreadThread,
            unreadThreadPill = unreadThreadPill,
            onThreadEvent = onThreadEventKeepingAnchor,
            diagnosticsLogger = diagnosticsLogger,
        )
        ThreadMessageMenuOverlays(
            menuState = menuState,
            renderPayload = renderPayload,
            linkedTasksProjection = linkedTasksProjection,
            replyMetadataByParentId = replyMetadataByParentId,
            activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
            writeActionsEnabled = writeActionsEnabled,
            newMessageMenuEnabled = newMessageMenuEnabled,
            translationStore = translationStore,
            translationState = translationState,
            taskAssigneeOptions = taskAssigneeOptions,
            agentStatusResolver = agentStatusResolver,
            adaptiveSharedStores = adaptiveSharedStores,
            coroutineScope = coroutineScope,
            onThreadEvent = onThreadEvent,
            onThreadEventKeepingAnchor = onThreadEventKeepingAnchor,
            onForwardMessage = onForwardMessage,
            onEnterShareSelection = onEnterShareSelection,
            diagnosticsLogger = diagnosticsLogger,
        )
    }
    }
    }
}

internal const val ThreadBottomScrollPaddingListItemKey = "thread-page:bottom-scroll-padding"
/**
 * The blank end of the list under the newest message: the list's own 16dp end and, only while the
 * typing / working line shows ([typingLineShown]), the line's slot on top of it
 * ([ThreadAgentTypingListEndHeight]), as the web's AgentTypingListEnd adds its height to the
 * timeline's end. While nobody types or works here the end is the plain end, with no slot
 * (#proj-mobile:#271; 1.28 held the slot in every conversation that could show the line).
 *
 * The line coming or going moves no message a reader is looking at: the list is laid out bottom-up,
 * so a reader resting on the end stays there (the rows ride up by the slot, the newest message
 * clear of the line, and come back when it goes) and a reader scrolled into the history does not
 * see the end change. A non-following intent resting on the end is retired to following first
 * ([threadTypingLineEndFollowsLatest]), so the reading anchor keeper does not put the old position
 * back under the line.
 *
 * The one source for both sides of "the newest message at the bottom": the height of the list's
 * last (spacer) row, and the trailing padding every scroll owner uses for it
 * (`initialBottomAnchorTrailingPaddingPx`: the initial-anchor check, the focused-tail fit, the
 * RenderedBottom anchor). They must stay the same number: a spacer taller than the anchors' padding
 * parks the newest message under the line (Android 1.27.0, #proj-mobile #227).
 */
internal fun threadBottomSpacerAndAnchorTrailingHeight(typingLineShown: Boolean): androidx.compose.ui.unit.Dp =
    if (typingLineShown) 16.dp + ThreadAgentTypingListEndHeight else 16.dp
/** The list's top inset for the find bar until the bar is measured (its usual height). */
private val ThreadInlineSearchBarTopInset = 58.dp
private const val ThreadInlineSearchBarZIndex = 2f
private fun LazyViewportIntent.threadViewportIntentDiagnosticName(): String =
    when (this) {
        LazyViewportIntent.FollowingLatest -> "following_latest"
        LazyViewportIntent.PinnedTop -> "pinned_top"
        is LazyViewportIntent.Anchored -> "anchored"
        LazyViewportIntent.UserDragging -> "user_dragging"
    }

private data class ThreadNewerPageCycle(
    val id: Long,
    val threadInstanceKey: String,
    val anchor: ThreadVisibleMessageAnchor?,
    val requestViewportEpoch: Long,
    val requestViewportIntent: LazyViewportIntent
)

private const val ThreadBackToBottomReleaseGuardMillis = 64L

private const val ThreadInitialBottomAnchorRetryDelayMs = 80L

private const val ThreadFocusedTailMeasurementRetryFrames = 2
private const val ThreadViewportPixelProbeTimeoutMs = 2_500L

/**
 * Whether the message list may open its scroll-ahead (beyond-bounds) window: after the initial
 * bottom placement and the viewport measurement, and not while the page is still sliding in (#171),
 * so a push composes only the rows on screen.
 */
internal fun threadScrollAheadWindowMayOpen(
    initialBottomAnchorReady: Boolean,
    viewportMeasurementReady: Boolean,
    pageInMotion: Boolean
): Boolean = initialBottomAnchorReady && viewportMeasurementReady && !pageInMotion

internal class ThreadMutableBooleanRef(
    var value: Boolean = false,
)

/**
 * Mirrors [LocalRaftStackPageLeaving] into [ref] inside a leaf composable so starting a pop-out
 * transition does not invalidate the enclosing [ThreadMessagesListContent] scope.
 */
@Composable
internal fun ThreadPageLeavingTracker(
    ref: ThreadMutableBooleanRef,
) {
    val leaving = LocalRaftStackPageLeaving.current
    SideEffect { ref.value = leaving }
}

/**
 * Opens the post-first-frame scroll-ahead window inside a leaf composable so reading
 * [build.raft.app.nav.LocalRaftStackPageInMotion] and latching [enabledRef] never invalidate the
 * enclosing [ThreadMessagesListContent] scope.
 */
@Composable
internal fun ThreadScrollAheadWindowEffect(
    threadInstanceKey: String,
    initialBottomAnchorReady: Boolean,
    viewportMeasurementReady: Boolean,
    listBeyondBoundsItems: Int,
    enabledRef: ThreadMutableBooleanRef,
    diagnosticsLogger: (String) -> Unit,
) {
    val listPageInMotion = build.raft.app.nav.LocalRaftStackPageInMotion.current
    val currentListBeyondBoundsItems by rememberUpdatedState(listBeyondBoundsItems)
    val currentDiagnosticsLogger by rememberUpdatedState(diagnosticsLogger)
    LaunchedEffect(
        threadInstanceKey,
        initialBottomAnchorReady,
        viewportMeasurementReady,
        listPageInMotion,
    ) {
        if (
            !threadScrollAheadWindowMayOpen(
                initialBottomAnchorReady = initialBottomAnchorReady,
                viewportMeasurementReady = viewportMeasurementReady,
                pageInMotion = listPageInMotion,
            ) ||
            enabledRef.value
        ) {
            return@LaunchedEffect
        }
        // The first frame only needs visible rows. Let that committed viewport
        // draw before enabling the normal scroll-ahead window, so expensive
        // card/Markdown composition does not compete with first paint.
        withFrameNanos { }
        withFrameNanos { }
        enabledRef.value = true
        currentDiagnosticsLogger(
            "thread_initial_beyond_bounds result=enabled_after_first_frame " +
                "items=$currentListBeyondBoundsItems"
        )
    }
}
