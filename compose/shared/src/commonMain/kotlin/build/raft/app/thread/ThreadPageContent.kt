package build.raft.app

import build.raft.app.assistant.LocalThreadAssistantPanelEmbed
import build.raft.app.assistant.ThreadAssistantPanelMessagesReport
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.raftToastAvoid
import build.raft.app.data.ThreadFindIntent
import build.raft.app.data.RaftProductEvents
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.components.raftCardSurface
import build.raft.app.data.ForwardMessageBatchResult
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetDirectoryCursor
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.RaftEventBus
import build.raft.app.data.DataResult
import build.raft.app.data.map
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedMessageTranslationStore
import build.raft.app.model.MemberProfile
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskSummary
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * task #346: state payload of [rememberThreadPageContentScaffoldSlots]. See RaftStructuralHomeShellData
 * for why the flat parameter list had to be grouped (Android dex verifier rejects the
 * un-minified giant composable frame on API 34/36 builds).
 */
internal data class ThreadPageContentData(
    val lounge: ThreadPageLounge? = null,
    val agentFollowersHeader: @Composable () -> Unit,
    /** Task v2: the task conversation's status / assignee / follow bar under the header; null elsewhere. */
    val taskConversationBar: (@Composable () -> Unit)? = null,
    /** A conversion to an external channel under way: its strip, before the announcement (ChannelConversionStrip.kt); null where none can show. */
    val channelConversionStrip: (@Composable () -> Unit)? = null,
    /** Channel / DM announcements: the strip above the timeline (ChannelAnnouncementHost.kt); null where none can show. */
    val channelAnnouncementStrip: (@Composable (onOpenSettings: () -> Unit) -> Unit)? = null,
    /** Task v2: the top bar's task title and More menu rows; null elsewhere or before the task is known. */
    val taskConversationChrome: TaskConversationHeaderChrome? = null,
    /** Task v2 on a phone: whether the head's row is in view, and the head pulled down from the bar while it is not. */
    val taskConversationHead: TaskHeadScrollState? = null,
    /** Task v2 on a phone: the head pulled down from the bar over the messages ([TaskHeadFloat]). */
    val taskConversationFloat: (@Composable () -> Unit)? = null,
    /** Task v2 on a phone: the head as the conversation's first row ([LocalTaskConversationHeadRow]). */
    val taskConversationHeadRow: TaskConversationHeadRow? = null,
    val threadInstanceKey: String,
    val routeActive: Boolean,
    val hostForeground: Boolean,
    val payload: RaftThreadScreenPayload,
    val messageBodyTextStyle: TextStyle,
    val timelineSlice: ThreadTimelineSlice,
    val accessResolution: ThreadAccessResolution,
    val canonicalAccessResolved: Boolean,
    val initialHomeAccessLookupComplete: Boolean,
    val lastRefreshResultReason: String?,
    val replyParentAuthority: ReplyParentAuthorityStatus,
    val writeActionsEnabled: Boolean,
    val jointBillingNotice: JointBillingNotice = JointBillingNotice.None,
    val jointGraceDeadlineLabel: String? = null,
    val externalFreezeState: ExternalFreezeState = ExternalFreezeState.None,
    val billingServerName: String = "",
    val billingServerPlan: String? = null,
    val billingServerRole: String? = null,
    val channelProfileUpdateStatus: ChannelProfileUpdateStatus,
    val channelProfileUpdateErrorCode: String?,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    val headerDisplay: ThreadHeaderDisplayModel,
    val composerSurface: ThreadComposerSurface,
    val baseComposerState: RaftThreadComposerPayload,
    val composerDraftViewModel: SharedThreadComposerDraftViewModel,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val resolvedAuxiliaryThreadId: String?,
    val tasksViewModel: SharedTasksViewModel?,
    val taskChannelId: ChannelId?,
    val taskIdentityOptions: List<MemberProfile>,
    val linkedTasksProjection: ThreadLinkedTasksProjection,
    val filesViewModel: SharedThreadFilesViewModel?,
    val memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    val channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    val selectedTab: RaftThreadTab,
    val availableTabs: List<RaftThreadTab>,
    val sideCardsEnabled: Boolean,
    val activityMuteState: ThreadActivityMuteUiState?,
    val activityMutedIndicatorVisible: Boolean,
    val conversationPinState: ThreadConversationPinUiState?,
    val forwardServerId: ServerId?,
    val forwardTargetRepository: ForwardMessageTargetRepository?,
    val contentExtrasVisible: Boolean,
    val highlightedMentions: Set<String>,
    val pendingActionCardIds: Set<String>,
    val markdownReferencePendingActionId: String?,
    val attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    val composerSendKeyEnabled: Boolean,
    val composerSendKey: build.raft.app.data.RaftComposerSendKey = build.raft.app.data.RaftComposerSendKey.Default,
    val longMessageCollapseEnabled: Boolean,
    val longMessageCollapseConversationSetting: RaftLongMessageCollapseConversationSetting?,
    val channelSettingsPanel: ThreadChannelSettingsPanel?,
    val jumpInputs: ThreadJumpInputs,
    val newMessageMenuEnabled: Boolean,
    val currentServerSlug: String?,
    val translationStore: SharedMessageTranslationStore?,
    val visibleReadTailIndex: Int?,
    /** A4 destination contract (task #18, spec §9): non-null when hosted as a detail pane. */
    val adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
    val adaptiveStoreScope: RaftShellStoreScope,
    val adaptiveEventBus: RaftEventBus,
    val onThreadEvent: ThreadEventHandler,
    /** The window's "something is about to open" signal: an open Tasks / Files cover closes on it. */
    val incomingNavigation: RaftIncomingNavigationSignal? = null,
)

/** task #346: callback payload of [rememberThreadPageContentScaffoldSlots]. See ThreadPageContentData. */
internal class ThreadPageContentCallbacks(
    val onSelectedTabChanged: (RaftThreadTab) -> Unit,
    val onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit,
    val onToggleActivityMute: (() -> Unit)?,
    val onOpenSettingsPage: (() -> Boolean)?,
    val onOpenTaskSummary: (TaskSummary, String) -> Unit,
    val onTasksIntent: (SharedTasksRouteIntent) -> Unit,
    val onFilesAuthRequired: (String?) -> Unit,
    val onOpenFilesAttachment: (RaftThreadAttachmentItem, ThreadImageAttachmentGroup?) -> Unit,
    val onDownloadFilesAttachment: (RaftThreadAttachmentItem) -> Unit,
    val onOpenMemberProfile: (MemberDetailIdentity) -> Unit,
    val onExternalAuthor: (RaftThreadMessageItem) -> Unit,
    val agentStatusResolver: RaftAgentStatusResolver,
    val memberAvatarResolver: RaftMemberAvatarResolver,
    val memberModelLabelResolver: RaftMemberModelLabelResolver,
    val diagnosticsLogger: (String) -> Unit,
    val onLayoutGeneration: (width: Int, height: Int) -> Unit,
    val onComposerLayoutProbe: (phase: String, metadata: Map<String, String>) -> Unit,
    val onComposerPixelProbe: suspend (Float, Float, Float, Float, Long) -> JSONObject?,
    val mentionHandleResolver: (RaftThreadMessageItem) -> String?,
    val onExportShareImage: suspend (ThreadShareCaptureRect?) -> JSONObject?,
    val onSaveShareImage: suspend (String) -> JSONObject?,
    val onFirstContentReady: () -> Unit,
    /** task #180: null when this surface has no side column to maximise. */
    val onToggleFocusMode: (() -> Unit)? = null,
    val focusModeActive: Boolean = false,
    val onVisibleReadTailChange: (Int?) -> Unit,
    val onViewportFollowsLatestChange: (Boolean) -> Unit,
    val onLoadNewer: suspend () -> ThreadNewerPageLoadOutcome,
    val onJumpToLatest: suspend () -> ThreadLatestJumpOutcome,
    val onBack: () -> Unit,
    val onUpdateChannelProfile: (String, String, String, String, String) -> Unit,
    val onClearChannelProfileUpdateStatus: () -> Unit,
    val onForwardMessagesDelivered: (ForwardMessageBatchResult) -> Unit,
    val onOpenForwardDestination: (ThreadForwardSuccessBanner) -> Unit,
    val onComposerKeyboardInsetChanged: (Float) -> Unit,
    val onComposerSendRequest: (ThreadComposerSendRequest) -> ThreadComposerSendAdmission,
    val onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    val onComposerDropFiles: (ThreadComposerFileDrop) -> Unit = { it.release() },
)

internal @Composable
fun rememberThreadPageContentScaffoldSlots(
    providedValues: Array<ProvidedValue<*>> = emptyArray(),
    foregroundBanner: ThreadRouteForegroundBannerSlot? = null,
    postScaffoldOverlays: @Composable () -> Unit = {},
    /** Agent Lounge v0 (see [ThreadPageLounge]); null on pages that never show one. */
    lounge: ThreadPageLounge? = null,
    agentFollowersHeader: @Composable () -> Unit = {},
    taskConversationBar: (@Composable () -> Unit)? = null,
    channelConversionStrip: (@Composable () -> Unit)? = null,
    channelAnnouncementStrip: (@Composable (onOpenSettings: () -> Unit) -> Unit)? = null,
    taskConversationChrome: TaskConversationHeaderChrome? = null,
    taskConversationHead: TaskHeadScrollState? = null,
    taskConversationFloat: (@Composable () -> Unit)? = null,
    taskConversationHeadRow: TaskConversationHeadRow? = null,
    threadInstanceKey: String,
    routeActive: Boolean,
    hostForeground: Boolean,
    payload: RaftThreadScreenPayload,
    messageBodyTextStyle: TextStyle,
    timelineSlice: ThreadTimelineSlice,
    accessResolution: ThreadAccessResolution,
    canonicalAccessResolved: Boolean,
    initialHomeAccessLookupComplete: Boolean = false,
    lastRefreshResultReason: String? = null,
    replyParentAuthority: ReplyParentAuthorityStatus = ReplyParentAuthorityStatus.Resolving,
    writeActionsEnabled: Boolean,
    jointBillingNotice: JointBillingNotice = JointBillingNotice.None,
    jointGraceDeadlineLabel: String? = null,
    externalFreezeState: ExternalFreezeState = ExternalFreezeState.None,
    billingServerName: String = "",
    billingServerPlan: String? = null,
    billingServerRole: String? = null,
    channelProfileUpdateStatus: ChannelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
    channelProfileUpdateErrorCode: String? = null,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    headerDisplay: ThreadHeaderDisplayModel,
    composerSurface: ThreadComposerSurface,
    baseComposerState: RaftThreadComposerPayload,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    resolvedAuxiliaryThreadId: String?,
    tasksViewModel: SharedTasksViewModel?,
    taskChannelId: ChannelId?,
    taskIdentityOptions: List<MemberProfile>,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    filesViewModel: SharedThreadFilesViewModel?,
    memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    selectedTab: RaftThreadTab,
    availableTabs: List<RaftThreadTab>,
    /** Task v2 ([conversationSideCardsEnabled]): Tasks / Files cards instead of the tab row. */
    sideCardsEnabled: Boolean = false,
    onSelectedTabChanged: (RaftThreadTab) -> Unit,
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit,
    activityMuteState: ThreadActivityMuteUiState? = null,
    activityMutedIndicatorVisible: Boolean = false,
    onToggleActivityMute: (() -> Unit)? = null,
    // A phone: opens the settings as a pushed page; false (or null) keeps the drawer.
    onOpenSettingsPage: (() -> Boolean)? = null,
    conversationPinState: ThreadConversationPinUiState? = null,
    forwardServerId: ServerId?,
    forwardTargetRepository: ForwardMessageTargetRepository?,
    contentExtrasVisible: Boolean,
    onOpenTaskSummary: (TaskSummary, String) -> Unit,
    onTasksIntent: (SharedTasksRouteIntent) -> Unit,
    onFilesAuthRequired: (String?) -> Unit,
    onOpenFilesAttachment: (RaftThreadAttachmentItem, ThreadImageAttachmentGroup?) -> Unit,
    onDownloadFilesAttachment: (RaftThreadAttachmentItem) -> Unit,
    onOpenMemberProfile: (MemberDetailIdentity) -> Unit,
    onExternalAuthor: (RaftThreadMessageItem) -> Unit = {},
    highlightedMentions: Set<String>,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver = EmptyRaftMemberModelLabelResolver,
    diagnosticsLogger: (String) -> Unit = {},
    onLayoutGeneration: (width: Int, height: Int) -> Unit = { _, _ -> },
    onComposerLayoutProbe: (phase: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    onComposerPixelProbe: suspend (Float, Float, Float, Float, Long) -> JSONObject? = { _, _, _, _, _ -> null },
    pendingActionCardIds: Set<String> = emptySet(),
    markdownReferencePendingActionId: String? = null,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    composerSendKeyEnabled: Boolean = false,
    composerSendKey: build.raft.app.data.RaftComposerSendKey = build.raft.app.data.RaftComposerSendKey.Default,
    longMessageCollapseEnabled: Boolean = false,
    longMessageCollapseConversationSetting: RaftLongMessageCollapseConversationSetting? = null,
    /** The settings drawer's members strip and preferences (null: the drawer shows the name form and actions). */
    channelSettingsPanel: ThreadChannelSettingsPanel? = null,
    jumpInputs: ThreadJumpInputs = ThreadJumpInputs(),
    newMessageMenuEnabled: Boolean = false,
    currentServerSlug: String? = null,
    translationStore: SharedMessageTranslationStore? = null,
    mentionHandleResolver: (RaftThreadMessageItem) -> String? = { message -> message.threadMentionHandle() },
    onExportShareImage: suspend (ThreadShareCaptureRect?) -> JSONObject?,
    onSaveShareImage: suspend (String) -> JSONObject?,
    onFirstContentReady: () -> Unit = {},
    visibleReadTailIndex: Int? = null,
    onVisibleReadTailChange: (Int?) -> Unit = {},
    onViewportFollowsLatestChange: (Boolean) -> Unit = {},
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest? = null,
    adaptiveStoreScope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
    adaptiveEventBus: RaftEventBus,
    incomingNavigation: RaftIncomingNavigationSignal? = null,
    onLoadNewer: suspend () -> ThreadNewerPageLoadOutcome,
    onJumpToLatest: suspend () -> ThreadLatestJumpOutcome,
    onBack: () -> Unit,
    onUpdateChannelProfile: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onClearChannelProfileUpdateStatus: () -> Unit = {},
    onForwardMessagesDelivered: (ForwardMessageBatchResult) -> Unit = {},
    onOpenForwardDestination: (ThreadForwardSuccessBanner) -> Unit = {},
    onComposerKeyboardInsetChanged: (Float) -> Unit = {},
    onThreadEvent: ThreadEventHandler,
    onComposerSendRequest: (ThreadComposerSendRequest) -> ThreadComposerSendAdmission,
    onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onComposerDropFiles: (ThreadComposerFileDrop) -> Unit = { it.release() },
    onToggleFocusMode: (() -> Unit)? = null,
    focusModeActive: Boolean = false,
): ThreadRouteScaffoldSlots =
    rememberThreadPageContentScaffoldSlotsContent(
        providedValues = providedValues,
        foregroundBanner = foregroundBanner,
        postScaffoldOverlays = postScaffoldOverlays,
        modifier = Modifier,
        onToggleFocusMode = onToggleFocusMode,
        focusModeActive = focusModeActive,
        data =
            ThreadPageContentData(
                lounge = lounge,
                agentFollowersHeader = agentFollowersHeader,
                taskConversationBar = taskConversationBar,
                channelConversionStrip = channelConversionStrip,
                channelAnnouncementStrip = channelAnnouncementStrip,
                taskConversationChrome = taskConversationChrome,
                taskConversationHead = taskConversationHead,
                taskConversationFloat = taskConversationFloat,
                taskConversationHeadRow = taskConversationHeadRow,
                threadInstanceKey = threadInstanceKey,
                routeActive = routeActive,
                hostForeground = hostForeground,
                payload = payload,
                messageBodyTextStyle = messageBodyTextStyle,
                timelineSlice = timelineSlice,
                accessResolution = accessResolution,
                canonicalAccessResolved = canonicalAccessResolved,
                initialHomeAccessLookupComplete = initialHomeAccessLookupComplete,
                lastRefreshResultReason = lastRefreshResultReason,
                replyParentAuthority = replyParentAuthority,
                writeActionsEnabled = writeActionsEnabled,
                jointBillingNotice = jointBillingNotice,
                jointGraceDeadlineLabel = jointGraceDeadlineLabel,
                externalFreezeState = externalFreezeState,
                billingServerName = billingServerName,
                billingServerPlan = billingServerPlan,
                billingServerRole = billingServerRole,
                channelProfileUpdateStatus = channelProfileUpdateStatus,
                channelProfileUpdateErrorCode = channelProfileUpdateErrorCode,
                replyMetadataByParentId = replyMetadataByParentId,
                headerDisplay = headerDisplay,
                composerSurface = composerSurface,
                baseComposerState = baseComposerState,
                composerDraftViewModel = composerDraftViewModel,
                composerDraftPersistSlice = composerDraftPersistSlice,
                resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
                tasksViewModel = tasksViewModel,
                taskChannelId = taskChannelId,
                taskIdentityOptions = taskIdentityOptions,
                linkedTasksProjection = linkedTasksProjection,
                filesViewModel = filesViewModel,
                memberSuggestionsFlow = memberSuggestionsFlow,
                channelSuggestionsFlow = channelSuggestionsFlow,
                selectedTab = selectedTab,
                availableTabs = availableTabs,
                sideCardsEnabled = sideCardsEnabled,
                activityMuteState = activityMuteState,
                activityMutedIndicatorVisible = activityMutedIndicatorVisible,
                conversationPinState = conversationPinState,
                forwardServerId = forwardServerId,
                forwardTargetRepository = forwardTargetRepository,
                contentExtrasVisible = contentExtrasVisible,
                highlightedMentions = highlightedMentions,
                pendingActionCardIds = pendingActionCardIds,
                markdownReferencePendingActionId = markdownReferencePendingActionId,
                attachmentOpenStates = attachmentOpenStates,
                composerSendKeyEnabled = composerSendKeyEnabled,
                composerSendKey = composerSendKey,
                longMessageCollapseEnabled = longMessageCollapseEnabled,
                longMessageCollapseConversationSetting = longMessageCollapseConversationSetting,
                channelSettingsPanel = channelSettingsPanel,
                jumpInputs = jumpInputs,
                newMessageMenuEnabled = newMessageMenuEnabled,
                currentServerSlug = currentServerSlug,
                translationStore = translationStore,
                visibleReadTailIndex = visibleReadTailIndex,
                adaptiveCloseRequest = adaptiveCloseRequest,
                adaptiveStoreScope = adaptiveStoreScope,
                adaptiveEventBus = adaptiveEventBus,
                onThreadEvent = onThreadEvent,
                incomingNavigation = incomingNavigation,
            ),
        callbacks =
            ThreadPageContentCallbacks(
                onToggleFocusMode = onToggleFocusMode,
                focusModeActive = focusModeActive,
                onSelectedTabChanged = onSelectedTabChanged,
                onSuggestionDemandChanged = onSuggestionDemandChanged,
                onToggleActivityMute = onToggleActivityMute,
                onOpenSettingsPage = onOpenSettingsPage,
                onOpenTaskSummary = onOpenTaskSummary,
                onTasksIntent = onTasksIntent,
                onFilesAuthRequired = onFilesAuthRequired,
                onOpenFilesAttachment = onOpenFilesAttachment,
                onDownloadFilesAttachment = onDownloadFilesAttachment,
                onOpenMemberProfile = onOpenMemberProfile,
                onExternalAuthor = onExternalAuthor,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                memberModelLabelResolver = memberModelLabelResolver,
                diagnosticsLogger = diagnosticsLogger,
                onLayoutGeneration = onLayoutGeneration,
                onComposerLayoutProbe = onComposerLayoutProbe,
                onComposerPixelProbe = onComposerPixelProbe,
                mentionHandleResolver = mentionHandleResolver,
                onExportShareImage = onExportShareImage,
                onSaveShareImage = onSaveShareImage,
                onFirstContentReady = onFirstContentReady,
                onVisibleReadTailChange = onVisibleReadTailChange,
                onViewportFollowsLatestChange = onViewportFollowsLatestChange,
                onLoadNewer = onLoadNewer,
                onJumpToLatest = onJumpToLatest,
                onBack = onBack,
                onUpdateChannelProfile = onUpdateChannelProfile,
                onClearChannelProfileUpdateStatus = onClearChannelProfileUpdateStatus,
                onForwardMessagesDelivered = onForwardMessagesDelivered,
                onOpenForwardDestination = onOpenForwardDestination,
                onComposerKeyboardInsetChanged = onComposerKeyboardInsetChanged,
                onComposerSendRequest = onComposerSendRequest,
                onComposerEvent = onComposerEvent,
                onComposerDropFiles = onComposerDropFiles,
            ),
    )

@Composable
private fun rememberThreadPageContentScaffoldSlotsContent(
    providedValues: Array<ProvidedValue<*>>,
    foregroundBanner: ThreadRouteForegroundBannerSlot? = null,
    postScaffoldOverlays: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    data: ThreadPageContentData,
    callbacks: ThreadPageContentCallbacks,
    onToggleFocusMode: (() -> Unit)? = null,
    focusModeActive: Boolean = false,
): ThreadRouteScaffoldSlots {
    val threadInstanceKey = data.threadInstanceKey
    val routeActive = data.routeActive
    val hostForeground = data.hostForeground
    // Agent Lounge v0: the Lounge this page is, or a reply thread is in (null elsewhere).
    val pageLounge = data.lounge?.lounge
    val conversationLounge = pageLounge?.takeIf { data.payload.surfaceKind == RaftThreadSurfaceKind.Channel }
    val payload =
        remember(data.payload, conversationLounge, data.accessResolution) {
            data.payload.withAgentLoungeAccess(conversationLounge, data.accessResolution)
        }
    val messageBodyTextStyle = data.messageBodyTextStyle
    val timelineSlice = data.timelineSlice
    // In the assistant's panel: a card from it still waiting here gives the otter its confirm mood.
    ThreadAssistantPanelMessagesReport(timelineSlice.messages)
    val accessResolution = data.accessResolution
    val canonicalAccessResolved = data.canonicalAccessResolved
    val replyParentAuthority = data.replyParentAuthority
    val taskConversationReadOnly =
        payload.isTaskConversationSurface() && !data.taskConversationChrome?.readOnlyReason.isNullOrBlank()
    // #proj-chat:#44: Lounge reactions and action cards follow `canPost` (`postable`), not channel membership.
    // slock #16739 / #16748: a historical joint task's participant discussion is read-only (no send, reactions, or action cards).
    val writeActionsEnabled =
        data.writeActionsEnabled && (conversationLounge?.postable != false) && !taskConversationReadOnly
    val jointBillingNotice = data.jointBillingNotice
    val jointGraceDeadlineLabel = data.jointGraceDeadlineLabel
    val externalFreezeState = data.externalFreezeState
    val billingServerName = data.billingServerName
    val billingServerPlan = data.billingServerPlan
    val billingServerRole = data.billingServerRole
    val threadLimitLocked = threadLimitLocksWrites(jointBillingNotice, externalFreezeState)
    val channelProfileUpdateStatus = data.channelProfileUpdateStatus
    val channelProfileUpdateErrorCode = data.channelProfileUpdateErrorCode
    val replyMetadataByParentId = data.replyMetadataByParentId
    val headerDisplay = data.headerDisplay
    val composerSurface = data.composerSurface
    val baseComposerState = data.baseComposerState
    val composerDraftViewModel = data.composerDraftViewModel
    val composerDraftPersistSlice = data.composerDraftPersistSlice
    val resolvedAuxiliaryThreadId = data.resolvedAuxiliaryThreadId
    val tasksViewModel = data.tasksViewModel
    val taskChannelId = data.taskChannelId
    val taskIdentityOptions = data.taskIdentityOptions
    val linkedTasksProjection = data.linkedTasksProjection
    val filesViewModel = data.filesViewModel
    val memberSuggestionsFlow = data.memberSuggestionsFlow
    val channelSuggestionsFlow = data.channelSuggestionsFlow
    val selectedTab = data.selectedTab
    val availableTabs = data.availableTabs
    // Task v2: the person's open Tasks / Files cards (app-level, not this conversation's).
    val sideCardsEnabled = data.sideCardsEnabled
    val sideCardsStore = LocalConversationSideCards.current
    val sideCardsStoreState by sideCardsStore.state.collectAsState()
    val sideCards =
        if (sideCardsEnabled) sideCardsStoreState.availableIn(RaftThreadTab.Tasks in availableTabs) else ConversationSideCardsState()
    // Task v2 on a wide page: the cards stand in a column beside the conversation instead of
    // covering it ([conversationSideCardsBeside]). In the workspace the column joins the pane grid
    // around the page, right of the reply thread, and always stands beside: the grid's row plan
    // makes room for it (#189: give way, clip, tabs; [conversationSideCardsBesideInGrid]). Known on
    // the first frame. A page on its own measures its root; until then the window's width stands in.
    val sideCardsDensity = LocalDensity.current.density
    val sideCardsWindowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    var sideCardsPageWidthDp by remember { mutableStateOf(sideCardsWindowWidthDp) }
    val sideCardsInGridRow = raftPaneGridInRow()
    val sideCardsBeside =
        sideCardsEnabled &&
            if (sideCardsInGridRow) {
                conversationSideCardsBesideInGrid(sideCardsWindowWidthDp)
            } else {
                conversationSideCardsBeside(sideCardsWindowWidthDp, sideCardsPageWidthDp)
            }
    // On a phone the cards cover the conversation: a link, notification or shortcut into a
    // conversation (or a page opened at a message) closes them, so the message is not under them.
    ConversationSideCardsIncomingNavigationEffect(
        store = sideCardsStore,
        signal = data.incomingNavigation,
        coverConversation = sideCardsEnabled && !sideCardsBeside,
        focusedMessageId = payload.focusedMessageId,
    )
    val onSelectedTabChanged = callbacks.onSelectedTabChanged
    val onSuggestionDemandChanged = callbacks.onSuggestionDemandChanged
    val activityMuteState = data.activityMuteState
    val activityMutedIndicatorVisible = data.activityMutedIndicatorVisible
    val onToggleActivityMute = callbacks.onToggleActivityMute
    val onOpenSettingsPage = callbacks.onOpenSettingsPage
    val conversationPinState = data.conversationPinState
    val forwardServerId = data.forwardServerId
    val forwardTargetRepository = data.forwardTargetRepository
    val contentExtrasVisible = data.contentExtrasVisible
    val onOpenTaskSummary = callbacks.onOpenTaskSummary
    val onTasksIntent = callbacks.onTasksIntent
    val onFilesAuthRequired = callbacks.onFilesAuthRequired
    val onOpenFilesAttachment = callbacks.onOpenFilesAttachment
    val onDownloadFilesAttachment = callbacks.onDownloadFilesAttachment
    val onOpenMemberProfile = callbacks.onOpenMemberProfile
    val onExternalAuthor = callbacks.onExternalAuthor
    val highlightedMentions = data.highlightedMentions
    val agentStatusResolver = callbacks.agentStatusResolver
    val memberAvatarResolver = callbacks.memberAvatarResolver
    val memberModelLabelResolver = callbacks.memberModelLabelResolver
    val diagnosticsLogger = callbacks.diagnosticsLogger
    val onLayoutGeneration = callbacks.onLayoutGeneration
    val onComposerLayoutProbe = callbacks.onComposerLayoutProbe
    val onComposerPixelProbe = callbacks.onComposerPixelProbe
    val pendingActionCardIds = data.pendingActionCardIds
    val markdownReferencePendingActionId = data.markdownReferencePendingActionId
    val attachmentOpenStates = data.attachmentOpenStates
    val composerSendKeyEnabled = data.composerSendKeyEnabled
    val composerSendKey = data.composerSendKey
    val longMessageCollapseEnabled = data.longMessageCollapseEnabled
    val longMessageCollapseConversationSetting = data.longMessageCollapseConversationSetting
    val channelSettingsPanel = data.channelSettingsPanel
    val jumpInputs = data.jumpInputs
    val newMessageMenuEnabled = data.newMessageMenuEnabled
    val currentServerSlug = data.currentServerSlug
    val translationStore = data.translationStore
    val mentionHandleResolver = callbacks.mentionHandleResolver
    val onExportShareImage = callbacks.onExportShareImage
    val onSaveShareImage = callbacks.onSaveShareImage
    val onFirstContentReady = callbacks.onFirstContentReady
    val visibleReadTailIndex = data.visibleReadTailIndex
    val adaptiveCloseRequest = data.adaptiveCloseRequest
    val adaptiveStoreScope = data.adaptiveStoreScope
    val adaptiveEventBus = data.adaptiveEventBus
    // Desktop / hardware keyboard: the composer takes the focus when the conversation opens; the
    // wide detail's conversation leaves it to the reply thread shown beside it, and a phone or
    // tablet leaves it alone when the open lands on one message (a message link, Activity, search).
    val composerAutofocusOnOpen =
        rememberThreadComposerAutofocusOnOpen(
            routeActive = routeActive,
            adaptiveCloseRequest = adaptiveCloseRequest,
            adaptiveStoreScope = adaptiveStoreScope,
            opensOnMessage = !timelineSlice.focusedMessageId.isNullOrBlank(),
        )
    val onVisibleReadTailChange = callbacks.onVisibleReadTailChange
    val onViewportFollowsLatestChange = callbacks.onViewportFollowsLatestChange
    val onLoadNewer = callbacks.onLoadNewer
    val onJumpToLatest = callbacks.onJumpToLatest
    val onBack = callbacks.onBack
    val onUpdateChannelProfile = callbacks.onUpdateChannelProfile
    val onClearChannelProfileUpdateStatus = callbacks.onClearChannelProfileUpdateStatus
    val onForwardMessagesDelivered = callbacks.onForwardMessagesDelivered
    val onOpenForwardDestination = callbacks.onOpenForwardDestination
    val onComposerKeyboardInsetChanged = callbacks.onComposerKeyboardInsetChanged
    val onThreadEvent = data.onThreadEvent
    val onComposerSendRequest = callbacks.onComposerSendRequest
    val onComposerEvent = callbacks.onComposerEvent
    val onComposerDropFiles = callbacks.onComposerDropFiles
    val inlineReplyParticipantsById =
        remember(payload.members, payload.memberCandidates) {
            (payload.members + payload.memberCandidates)
                .distinctBy(RaftThreadMemberItem::id)
                .associateBy(RaftThreadMemberItem::id)
        }
    val renderEarlyLoadingShell =
        payload.shouldRenderThreadLoadingShell(
            timelineSlice = timelineSlice,
            accessResolution = accessResolution
        )
    val renderAccessRetry = accessResolution.shouldRenderThreadAccessRetry()
    val accessErrorLabels = threadUserFacingErrorLabels()
    val shareExportScope = rememberCoroutineScope()
    LaunchedEffect(threadInstanceKey, selectedTab, timelineSlice.status, timelineSlice.messages.size) {
        if (
            selectedTab != RaftThreadTab.Chat &&
            payload.isReadyForThreadPerformanceGate()
        ) {
            delay(ThreadLayoutStabilityFrameDelayMs)
            onFirstContentReady()
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        timelineSlice.status,
        timelineSlice.messages.size,
        payload.backgroundRefreshing,
        timelineSlice.originStatus,
        timelineSlice.originMessage?.id,
        payload.originPreview,
        payload.selectedTab,
        selectedTab,
        renderEarlyLoadingShell
    ) {
        diagnosticsLogger(
            "thread_visible_state instance=$threadInstanceKey " +
                "threadId=${payload.sourceThreadId.orEmpty()} " +
                "streamChannelId=${payload.streamChannelId.orEmpty()} " +
                "payloadTab=${payload.selectedTab} selectedTab=${selectedTab.wireValue} " +
                "status=${timelineSlice.status} messages=${timelineSlice.messages.size} " +
                "backgroundRefreshing=${payload.backgroundRefreshing} " +
                "originStatus=${timelineSlice.originStatus} " +
                "hasOriginMessage=${timelineSlice.originMessage != null} " +
                "hasOriginPreview=${!payload.originPreview.isNullOrBlank()} " +
                "renderEarlyLoadingShell=$renderEarlyLoadingShell " +
                "accessResolution=${accessResolution.name} " +
                "canonicalAccessResolved=$canonicalAccessResolved " +
                "initialHomeAccessLookupComplete=${data.initialHomeAccessLookupComplete} " +
                "lastRefreshResult=${data.lastRefreshResultReason ?: "none"} " +
                "branch=${if (renderEarlyLoadingShell) "early_loading" else "tab_content"}"
        )
    }
    var visibleTasksStartThreadId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedTab, sideCards.tasks, taskChannelId?.value, payload.status, tasksViewModel) {
        val resolvedTaskChannelId = taskChannelId ?: return@LaunchedEffect
        tasksViewModel ?: return@LaunchedEffect
        // An open Tasks card loads the channel's tasks as the Tasks tab did.
        val tasksShownTab = if (sideCards.tasks) RaftThreadTab.Tasks else selectedTab
        if (
            !shouldStartThreadTasksJoin(
                payload = payload.copy(selectedTab = tasksShownTab.wireValue),
                startedThreadId = visibleTasksStartThreadId,
                taskChannelId = resolvedTaskChannelId
            )
        ) {
            return@LaunchedEffect
        }
        visibleTasksStartThreadId = resolvedTaskChannelId.value
        diagnosticsLogger(
            "thread_tasks_join_start threadId=${payload.sourceThreadId.orEmpty()} reason=selected_tasks_tab"
        )
        onTasksIntent(SharedTasksRouteIntent.Start)
    }
    val activeModalState = remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var activeModal by activeModalState
    // A phone opens the settings as a pushed page (ThreadSettingsPage.kt); a wide window as the drawer.
    val settingsOpensAsPage = rememberThreadSettingsOpensAsPage()
    LaunchedEffect(channelProfileUpdateStatus, activeModal) {
        if (channelProfileUpdateStatus == ChannelProfileUpdateStatus.Succeeded) {
            // web inline Save: the drawer stays open and Save reads "Changes saved" for a moment.
            if (activeModal == "settings") delay(ThreadSettingsSavedNoticeMillis)
            onClearChannelProfileUpdateStatus()
        }
    }
    // Full bundles open a dedicated pager page (task #64); capture the pager at
    // composable scope so the tap handler can route.
    val threadScreenPagerForForwardBundle = LocalActivity.current.getPager() as Pager
    val shareState = remember(threadInstanceKey) { ThreadShareForwardState() }
    val sharePngWriter = rememberRaftPngFileWriter()
    val sharePreviewOpen = activeModal == "sharePreview"
    ThreadShareImageEffects(shareState, sharePreviewOpen)
    // Search in this thread (web ThreadSearchBar): a reply thread's or a task's own bar.
    val threadFind =
        rememberThreadFindController(threadInstanceKey, taskSurface = payload.isTaskConversationSurface())
    val threadFindState by threadFind.store.state.collectAsState()
    val inlineSearchVisible = threadFindState.open
    val insertMentionRequestNonceState = remember(threadInstanceKey) { mutableStateOf(0) }
    val insertMentionRequestState = remember(threadInstanceKey) { mutableStateOf<ThreadComposerInsertMentionRequest?>(null) }
    var insertMentionRequest by insertMentionRequestState
    val shareSelectionActive = shareState.shareSelectionMode && selectedTab == RaftThreadTab.Chat
    val selectableShareMessageIds =
        remember(timelineSlice) {
            timelineSlice.threadSelectableShareMessageIds()
        }
    // Every reply is loaded: none older, none newer (web `selectableComplete`).
    val selectableShareComplete = !timelineSlice.canLoadMoreBefore && !timelineSlice.canLoadMoreAfter
    val selectedShareMessages =
        remember(timelineSlice, shareState.selectedShareMessageIds) {
            timelineSlice.threadSelectedShareMessages(shareState.selectedShareMessageIds)
        }
    val forwardMessages =
        remember(timelineSlice, shareState.forwardMessageIds) {
            timelineSlice.threadSelectedShareMessages(shareState.forwardMessageIds)
        }
    val forwardMessageSelection =
        remember(forwardMessages) {
            forwardMessages.threadForwardMessageSelection()
        }
    val selectedShareMarkdown =
        remember(selectedShareMessages) {
            selectedShareMessages.threadShareMarkdown()
        }
    val selectedShareLinks =
        remember(payload, selectedShareMessages, currentServerSlug) {
            selectedShareMessages
                .mapNotNull { message -> payload.threadMessagePermalink(message, currentServerSlug) }
                .joinToString(separator = "\n")
        }
    val forwardSourceChannelId = remember(payload) { payload.threadForwardSourceChannelId() }
    val forwardTargetItems =
        remember(shareState.forwardTargets, shareState.forwardSourceServerId, forwardSourceChannelId) {
            shareState.forwardTargets
                .threadForwardPickerTargets(shareState.forwardSourceServerId, forwardSourceChannelId)
                .map { it.toRaftForwardTargetPickerItem() }
        }
    // slock #12621 "Threads & tasks": one GET per picker open (Visible lane), never per keystroke,
    // never retried; a failure or an empty answer is no group.
    val forwardConversationLoader =
        remember(threadInstanceKey, forwardTargetRepository) {
            forwardTargetRepository?.let(::ThreadForwardConversationTargetsLoader)
        }
    val forwardConversationItems =
        remember(shareState.forwardConversationTargets, shareState.forwardSourceServerId, forwardSourceChannelId) {
            shareState.forwardConversationTargets
                .threadForwardConversationPickerTargets(shareState.forwardSourceServerId, forwardSourceChannelId)
                .map { it.toRaftForwardTargetPickerItem() }
        }
    ThreadForwardPickerEffects(
        shareState = shareState,
        payload = payload,
        forwardConversationLoader = forwardConversationLoader,
        forwardServerId = forwardServerId,
        forwardTargetRepository = forwardTargetRepository,
    )
    val clearShareSelection = {
        shareState.shareSelectionMode = false
        shareState.selectedShareMessageIds = emptySet()
        shareState.shareSelectionAnchorId = null
        shareState.selectedShareBounds.clear()
        shareState.sharePreviewImage = null
        shareState.sharePreviewLoading = false
        shareState.shareImageBitmap = null
        shareState.shareImageRendering = false
        shareState.shareCaptureMode = false
        shareState.shareCaptureTargetMessageId = null
        shareState.shareCaptureAnchorBeforeTarget = false
        shareState.shareCaptureTailPaddingActive = false
        shareState.shareCaptureScrollOffsetPx = ThreadShareCaptureTopScrollOffsetPx
        if (activeModal == "sharePreview") {
            activeModal = null
        }
    }
    val openForwardPickerForMessageIds: (Set<String>) -> Unit = openForward@{ messageIds ->
        if (!writeActionsEnabled) {
            diagnosticsLogger("thread_forward_open_rejected reason=write_actions_disabled")
            return@openForward
        }
        val candidateMessages = timelineSlice.threadSelectedShareMessages(messageIds)
        val selection = candidateMessages.threadForwardMessageSelection()
        if (selection.forwardableMessages.isEmpty()) {
            onThreadEvent(
                "forwardMessagesFailed",
                selection.threadForwardSelectionFailureCode(),
                null,
                null
            )
            return@openForward
        }
        shareState.forwardMessageIds = candidateMessages.mapTo(linkedSetOf()) { it.id }
        shareState.activeForwardSelectionMode = ThreadForwardSelectionMode.Merged
        shareState.forwardSourceServerId = forwardServerId
        shareState.forwardPickerFlow.sessionKey?.let { previous ->
            forwardTargetRepository?.releaseDirectorySnapshot(previous.directorySessionId())
        }
        shareState.forwardPickerFlow =
            shareState.forwardPickerFlow.reduce(
                RaftForwardPickerFlowEvent.Open(
                    sourceContext = threadInstanceKey,
                    serverId = forwardServerId?.value.orEmpty()
                )
            )
        shareState.forwardTargets = emptyList()
        shareState.forwardConversationTargets = emptyList()
        shareState.forwardDirectoryNextCursor = null
        shareState.forwardSelectedReceipts = emptyList()
        shareState.forwardNote = TextFieldValue()
        activeModal = "forwardTargets"
    }
    val transientBackTarget =
        threadTransientBackTarget(
            activeModal = activeModal,
            shareSelectionActive = shareSelectionActive,
            inlineSearchVisible = inlineSearchVisible,
            // Beside the conversation the cards cover nothing: Back is the page's (web: only a phone's Back closes them).
            sideCardsOpen = sideCards.anyOpen && !sideCardsBeside
        )
    ThreadTransientBackHandler(transientBackTarget) { target ->
        when (target) {
            ThreadTransientBackTarget.SideCards -> sideCardsStore.closeAll()
            ThreadTransientBackTarget.ShareSelection -> clearShareSelection()
            ThreadTransientBackTarget.InlineSearch -> threadFind.store.dispatch(ThreadFindIntent.Close)
        }
    }
    val composerKeyboardInsetDpState = remember(threadInstanceKey) { mutableStateOf(0f) }
    var composerKeyboardInsetDp by composerKeyboardInsetDpState
    val composerBottomSafeArea = raftBottomSafeAreaPadding()
    val composerKeyboardInsetExcludesBottomSafeArea = raftKeyboardInsetExcludesBottomSafeArea()
    val primaryFooterReady =
        payload.isThreadPrimaryFooterReady(
            timelineSlice = timelineSlice,
            accessResolution = accessResolution
        )
    val showComposerForSelectedTab =
        primaryFooterReady &&
        !threadLimitLocked &&
        shouldShowThreadComposerForReadyContent(
            joined = payload.joined,
            showComposer = payload.showComposer,
            selectedTab = selectedTab.wireValue
        )
    val taskPanelChannelId =
        remember(taskChannelId, threadInstanceKey) {
            taskChannelId
                ?: ChannelId(threadInstanceKey)
        }
    val threadHeaderScrollToTopRequestState = remember(threadInstanceKey) { mutableStateOf(0) }
    var threadHeaderScrollToTopRequest by threadHeaderScrollToTopRequestState
    val renderThreadTabContent: @Composable (RaftThreadTab, Modifier, Boolean) -> Unit =
        { tab, contentModifier, tabActive ->
            val activeDraftRecords by composerDraftPersistSlice.activeDraftRecordsState.collectAsState()
            val draftProjection =
                remember(activeDraftRecords) {
                    activeDraftRecords.toSharedThreadComposerDraftProjection()
                }
            val markdownTagResolution =
                threadMarkdownTagResolution(
                    payload = payload,
                    timelineSlice = timelineSlice,
                    linkedTasksProjection = linkedTasksProjection,
                    highlightedMentions = highlightedMentions,
                    markdownReferencePendingActionId = markdownReferencePendingActionId,
                    onThreadEvent = onThreadEvent,
                    loungeRefsEnabled = data.lounge?.loungesEnabled == true,
                )
            val timelineRenderConfig = rememberThreadTimelineRenderConfig(payload)
            ThreadTabContent(
                tab = tab,
                contentModifier = contentModifier,
                tabActive = tabActive,
                data = data,
                callbacks = callbacks,
                payload = payload,
                markdownTagResolution = markdownTagResolution,
                activeModalState = activeModalState,
                composerKeyboardInsetDpState = composerKeyboardInsetDpState,
                insertMentionRequestState = insertMentionRequestState,
                insertMentionRequestNonceState = insertMentionRequestNonceState,
                selectedShareMessageIdsState = shareState.selectedShareMessageIdsState,
                shareCaptureAnchorBeforeTargetState = shareState.shareCaptureAnchorBeforeTargetState,
                shareCaptureModeState = shareState.shareCaptureModeState,
                shareCaptureScrollOffsetPxState = shareState.shareCaptureScrollOffsetPxState,
                shareCaptureTailPaddingActiveState = shareState.shareCaptureTailPaddingActiveState,
                shareCaptureTargetMessageIdState = shareState.shareCaptureTargetMessageIdState,
                shareSelectionAnchorIdState = shareState.shareSelectionAnchorIdState,
                shareSelectionModeState = shareState.shareSelectionModeState,
                threadHeaderScrollToTopRequestState = threadHeaderScrollToTopRequestState,
                accessErrorLabels = accessErrorLabels,
                draftProjection = draftProjection,
                inlineReplyParticipantsById = inlineReplyParticipantsById,
                inlineSearchVisible = inlineSearchVisible,
                openForwardPickerForMessageIds = openForwardPickerForMessageIds,
                renderAccessRetry = renderAccessRetry,
                renderEarlyLoadingShell = renderEarlyLoadingShell,
                selectableShareMessageIds = selectableShareMessageIds,
                selectedShareBounds = shareState.selectedShareBounds,
                selectedShareMessages = selectedShareMessages,
                shareSelectionActive = shareSelectionActive,
                showComposerForSelectedTab = showComposerForSelectedTab,
                sideCards = sideCards,
                sideCardsBeside = sideCardsBeside,
                taskPanelChannelId = taskPanelChannelId,
                threadScreenPagerForForwardBundle = threadScreenPagerForForwardBundle,
                timelineRenderConfig = timelineRenderConfig,
                writeActionsEnabled = writeActionsEnabled,
            )
        }
    val composerSuggestionPortal =
        remember(threadInstanceKey) { ThreadComposerSuggestionPortalState() }
    // The thread timeline (botiverse/slock #13534): the reply thread's outline, read on opening the
    // thread and on coming back to it, as the rail and the ⋯ menu's Timeline both need its count.
    val threadOutline =
        rememberThreadOutlineState(
            (timelineSlice.historyChannelId ?: timelineSlice.sourceThreadId).takeIf { timelineSlice.replyThreadSurface }
        )
    val threadOutlineActive = routeActive && hostForeground
    LaunchedEffect(threadOutline, threadOutlineActive) {
        if (threadOutlineActive) threadOutline?.loadNewest()
    }
    val loungeFooterKind = agentLoungeFooterKind(conversationLounge, pageLounge)
    val archivedFooterActive = pageLounge == null && threadFooterShowsArchived(payload)
    val optimisticComposerActive =
        !primaryFooterReady &&
            !taskConversationReadOnly &&
            threadShowsOptimisticComposer(
                accessResolution = accessResolution,
                payloadStatus = payload.status,
                surfaceKind = payload.surfaceKind,
                joined = payload.joined,
                showComposer = payload.showComposer,
                selectedTab = selectedTab.wireValue,
                composerLocked = threadLimitLocked,
                channelArchived = archivedFooterActive,
                externalFrozen = externalFreezeState == ExternalFreezeState.Active,
                loungeFooterActive = loungeFooterKind != null,
                replyParentAuthority = replyParentAuthority,
            )
    val renderComposerDock =
        optimisticComposerActive ||
            (
                primaryFooterReady &&
                    writeActionsEnabled &&
                    !shareSelectionActive &&
                    loungeFooterKind == null &&
                    !archivedFooterActive &&
                    externalFreezeState != ExternalFreezeState.Active &&
                    !threadFooterShowsJoin(payload.joined, conversationLounge) &&
                    showComposerForSelectedTab
                )
    val composerDockSlot =
        if (renderComposerDock) {
            val composerRouteArgs =
                ThreadComposerRouteArgs(
                    baseComposerState = baseComposerState,
                    removedAppBot = null,
                    composerDraftViewModel = composerDraftViewModel,
                    renderedPayload = payload,
                    timelineSlice = timelineSlice,
                    resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
                    surface = composerSurface,
                    memberSuggestionsFlow = memberSuggestionsFlow,
                    channelSuggestionsFlow = channelSuggestionsFlow,
                    agentStatusResolver = agentStatusResolver,
                    onEvent = onComposerEvent,
                    onDropFiles = onComposerDropFiles,
                    onSendRequest = onComposerSendRequest,
                    insertMentionRequest = insertMentionRequest,
                    onInsertMentionConsumed = { request ->
                        if (insertMentionRequest?.nonce == request.nonce) {
                            insertMentionRequest = null
                        }
                    },
                    onSuggestionDemandChanged = onSuggestionDemandChanged,
                    composerSendKeyEnabled = composerSendKeyEnabled,
                    composerSendKey = composerSendKey,
                    onKeyboardHeightChange = { nextInset ->
                        if (nextInset != composerKeyboardInsetDp) {
                            composerKeyboardInsetDp = nextInset
                            onComposerKeyboardInsetChanged(nextInset)
                        }
                    },
                    adaptiveNativeEditingStateReader = LocalThreadAdaptiveNativeEditingReader.current,
                    autofocusOnOpen = composerAutofocusOnOpen,
                    optimisticPendingAccess = optimisticComposerActive,
                    dmAgentPeerId = headerDisplay.composerDmAgentPeerId(),
                    storeScope = adaptiveStoreScope,
                )
            ThreadRouteComposerDockSlot(
                args = composerRouteArgs,
                edgePadding = if (threadInSplitDetailPane()) ThreadSplitDetailEdgeInset else RaftAppSpacing.Sm,
                // Bottom padding follows WindowInsets.ime in the layout phase of the
                // same frame; composerKeyboardInsetDp (two frames late) only feeds the
                // page's decision state (see RaftThreadImeLayout.kt).
                composerModifier =
                    Modifier.raftThreadComposerLiveKeyboardPadding(
                        bottomSafeArea = composerBottomSafeArea,
                        keyboardInsetExcludesBottomSafeArea = composerKeyboardInsetExcludesBottomSafeArea,
                    ),
                keyboardLiftPx =
                    rememberThreadComposerKeyboardLiftPx(
                        bottomSafeArea = composerBottomSafeArea,
                        keyboardInsetExcludesBottomSafeArea = composerKeyboardInsetExcludesBottomSafeArea,
                    ),
                suggestionPortal = composerSuggestionPortal,
                agentLoungeComposerName = conversationLounge?.let { agentLoungeTitle(it.displayAgentName) },
                dmAgentPeerId = headerDisplay.composerDmAgentPeerId(),
                onFocusChanged =
                    // Task v2: focus in the composer puts the pulled-down head back up.
                    data.taskConversationHead?.let { taskHead ->
                        { hasFocus -> if (hasFocus) taskHead.closeFloat() }
                    },
            )
        } else {
            null
        }
    val scaffoldProvidedValues: Array<ProvidedValue<*>> =
        providedValues +
            arrayOf(
                LocalThreadOutlineState provides threadOutline,
                LocalThreadFindController provides
                    threadFind.takeIf { payload.threadHeaderSearchAction() == ThreadHeaderSearchAction.InlineThreadSearch },
                LocalThreadFindHighlightQuery provides threadFindState.query.takeIf { threadFindState.open },
            )
    return ThreadRouteScaffoldSlots(
        scaffoldPlan = ThreadRouteMountPlan.FeatureSurfaces.toThreadRouteScaffoldPlan(),
        providedValues = scaffoldProvidedValues,
        foregroundBanner = foregroundBanner,
        postScaffoldOverlays = postScaffoldOverlays,
        composerDock = composerDockSlot,
        rootModifier =
            Modifier.onGloballyPositioned { coordinates ->
                onLayoutGeneration(coordinates.size.width, coordinates.size.height)
                val pageWidthDp = coordinates.size.width / sideCardsDensity
                if (pageWidthDp != sideCardsPageWidthDp) sideCardsPageWidthDp = pageWidthDp
            }.then(data.taskConversationHead?.let { Modifier.taskHeadFloatOutsidePress(it) } ?: Modifier),
            header = {
                // task #180: computed once per header composition, outside the header's own scope.
                // The slice keeps reply facts out of its rows: the origin's thread channel and follow
                // state come from the page's reply metadata (without it there was never a row here).
                val originReplyMetadata =
                    timelineSlice.originMessage?.let { origin -> replyMetadataByParentId.threadReplyMetadataFor(origin) }
                val panelFollowAction =
                    threadPanelFollowAction(
                        timelineSlice.originMessage?.let { origin ->
                            originReplyMetadata?.let { origin.withThreadReplyFacts(it) } ?: origin
                        }
                    )
                val markUnreadThreadChannelId =
                    threadPanelMarkUnreadChannelId(
                        replyThreadSurface = timelineSlice.replyThreadSurface && data.taskConversationChrome == null,
                        originReplyMetadata = originReplyMetadata
                    )
                // The channel details (raft-design channel.md): on the wide layout the details cell of the
                // profile column in the conversation's row (RaftWidePanes.kt, #189 step 3), whose Settings
                // toggle is pressed while they are open and closes them when pressed again (a profile
                // above them stays); on a phone a pushed page. A DM keeps its settings page or drawer, a
                // Lounge its drawer.
                val profileColumn = LocalRaftProfileColumn.current
                val channelDetailsOpen =
                    conversationLounge == null &&
                        payload.surfaceKind != RaftThreadSurfaceKind.DirectMessage &&
                        profileColumn.showsChannelDetails(payload.sourceThreadId?.trim())
                val showSettings: () -> Unit = {
                    if (conversationLounge != null) {
                        activeModal = "settings"
                    } else if (payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
                        // A wide window, or a page that could not open: the drawer.
                        if (!(settingsOpensAsPage && onOpenSettingsPage?.invoke() == true)) activeModal = "settings"
                    } else if (channelDetailsOpen) {
                        profileColumn.closeDetails()
                    } else {
                        onThreadEvent("openChannelSettings", null, null, null)
                    }
                }
                ThreadPageHeader(
                    onOpenTimeline =
                        threadOutline?.openTimelineAction(
                            leadingMessageId = timelineSlice.parentMessageId ?: timelineSlice.originMessage?.id,
                            origin = timelineSlice.originMessage,
                            messages = timelineSlice.messages,
                        ),
                    agentFollowersHeader = data.agentFollowersHeader,
                    payload = payload,
                    headerDisplay = headerDisplay,
                    originMessage = timelineSlice.originMessage,
                    originReplyCount =
                        threadHeaderReplyCount(
                            timelineSlice,
                            replyMetadataByParentId,
                            hiddenHistoryCount = rememberThreadHiddenHistory(timelineSlice.historyChannelId)?.hiddenCount ?: 0
                        ),
                    activityMutedIndicatorVisible = activityMutedIndicatorVisible,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    showBack = !threadInSplitDetailPane(),
                    // Task v2 on a phone: the header's Back closes the cards covering the conversation
                    // first (web `headerMobileBack`), then leaves the page.
                    onBack = if (sideCards.anyOpen && !sideCardsBeside) sideCardsStore::closeAll else onBack,
                    onOpenInChannel = {
                        onThreadEvent("openParentMessage", payload.originParentMessageId, null, null)
                    },
                    onOpenMemberProfile = onOpenMemberProfile,
                    onOpenSearch = {
                        when (payload.threadHeaderSearchAction()) {
                            ThreadHeaderSearchAction.ChannelSearch ->
                                onThreadEvent("openChannelSearch", payload.threadChannelSearchChannelId(), null, null)
                            ThreadHeaderSearchAction.InlineThreadSearch -> {
                                if (selectedTab != RaftThreadTab.Chat) {
                                    onSelectedTabChanged(RaftThreadTab.Chat)
                                    onThreadEvent("selectTab", null, null, RaftThreadTab.Chat.wireValue)
                                }
                                // web: the menu's Search in thread opens the bar (its last words kept).
                                threadFind.store.dispatch(ThreadFindIntent.Open)
                            }
                            null -> Unit
                        }
                    },
                    onShowMembers = {
                        onThreadEvent("openMembersPager", null, null, null)
                    },
                    lounge = data.lounge,
                    onShowSettings = showSettings,
                    settingsOpen = channelDetailsOpen,
                    onTitleClick =
                        if (threadHeaderScrollToTopEnabled(payload.surfaceKind, selectedTab.wireValue)) {
                            { threadHeaderScrollToTopRequest += 1 }
                        } else if (
                            conversationLounge == null &&
                            payload.surfaceKind == RaftThreadSurfaceKind.Channel &&
                            threadHeaderSettingsActionVisible(payload.showSettingsAction, payload.surfaceKind)
                        ) {
                            // raft-design channel.md: the channel's name opens its details, as in other chat apps.
                            showSettings
                        } else {
                            null
                        },
                    // task #180: null on hosts without a side column, which also hides the menu item.
                    onToggleFocusMode = callbacks.onToggleFocusMode,
                    focusModeActive = callbacks.focusModeActive,
                    // task #180 (artin): the follow row is derived by a pure helper from the same
                    // per-message state the long-press menu reads, so the two cannot disagree and the
                    // decision is unit-testable. Null = nothing to follow -> row is absent.
                    threadFollowed = data.taskConversationChrome?.followed ?: panelFollowAction?.followed,
                    taskConversationChrome = data.taskConversationChrome,
                    onToggleThreadFollow =
                        data.taskConversationChrome?.takeIf { it.followed != null }?.onToggleFollow
                            ?: panelFollowAction?.let { follow ->
                            {
                                // The existing handler reads the thread channel from the attachmentId
                                // slot: onThreadEvent(action, itemId, attachmentId, tab).
                                onThreadEvent(
                                    follow.action,
                                    timelineSlice.originMessage?.id,
                                    follow.threadChannelId,
                                    null
                                )
                            }
                        },
                    onMarkThreadUnread =
                        markUnreadThreadChannelId?.let { threadChannelId ->
                            { onThreadEvent(MARK_THREAD_UNREAD_ACTION, null, threadChannelId, null) }
                        },
                    notificationLevelTarget =
                        threadNotificationLevelTarget(
                            serverId = forwardServerId?.value,
                            replyThreadOrTask = timelineSlice.replyThreadSurface || data.taskConversationChrome != null,
                            threadChannelId =
                                payload.streamChannelId?.takeIf { it.isNotBlank() }
                                    ?: panelFollowAction?.threadChannelId,
                            parentChannelId = payload.parentChannelId,
                            parentIsDirectMessage = payload.parentChannelKind == "dm",
                            muted = payload.activityMuted
                        ),
                    sideCardToggles =
                        if (sideCardsEnabled) {
                            ThreadHeaderSideCardToggles(
                                tasksAvailable = RaftThreadTab.Tasks in availableTabs,
                                cards = sideCards,
                                onToggle = { card ->
                                    if (!sideCards.isOpen(card)) clearShareSelection()
                                    sideCardsStore.toggle(card)
                                    RaftProductEvents.channelTabSelected(
                                        card.toThreadTab().productChannelTab(),
                                        sideCards.isOpen(card)
                                    )
                                }
                            )
                        } else {
                            null
                        },
                )
            },
            bodyCover =
                if (sideCardsEnabled) {
                    // Kept (empty) while the cards stand beside: the body never remounts across the breakpoint.
                    cover@{
                        if (sideCardsBeside) return@cover
                        // No header Back (a split's detail pane): the card keeps its own (web).
                        val cardBack = sideCards.shown?.takeIf { threadInSplitDetailPane() }?.let { card -> { sideCardsStore.close(card) } }
                        ConversationSideCardsCover(
                            cards = sideCards,
                            onPick = sideCardsStore::focus,
                            onBack = cardBack,
                            // iOS back swipe: what Back does here (the card's own, else every open card).
                            onSwipeClose = cardBack ?: sideCardsStore::closeAll,
                            tasksContent = {
                                renderThreadTabContent(RaftThreadTab.Tasks, Modifier.fillMaxSize(), true)
                            },
                            filesContent = {
                                renderThreadTabContent(RaftThreadTab.Files, Modifier.fillMaxSize(), true)
                            }
                        )
                    }
                } else {
                    null
                },
            sideColumns =
                if (sideCardsBeside) {
                    listOfNotNull(
                        conversationSideCardsColumn(
                            cards = sideCards,
                            onPick = sideCardsStore::focus,
                            onClose = sideCardsStore::close,
                            tasksContent = {
                                renderThreadTabContent(RaftThreadTab.Tasks, Modifier.fillMaxSize(), true)
                            },
                            filesContent = {
                                renderThreadTabContent(RaftThreadTab.Files, Modifier.fillMaxSize(), true)
                            }
                        )
                    )
                } else {
                    emptyList()
                },
            body = {
            if (externalFreezeState != ExternalFreezeState.Active && jointBillingNotice != JointBillingNotice.None) {
                val rawDeadline = jointGraceDeadlineLabel?.takeIf { it.isNotBlank() }
                val deadlineLabel = if (rawDeadline != null) raftLocalizedMediumDateTime(rawDeadline) else null
                RaftUiBanner(
                    status =
                        if (jointBillingNotice == JointBillingNotice.Locked) RaftUiBannerStatus.Destructive
                        else RaftUiBannerStatus.Warning,
                    title =
                        stringResource(
                            if (jointBillingNotice == JointBillingNotice.Locked) Res.string.joint_billing_locked_title
                            else Res.string.joint_over_limit_grace_title
                        ),
                    description =
                        if (jointBillingNotice == JointBillingNotice.Locked) {
                            stringResource(Res.string.joint_billing_locked_detail)
                        } else {
                            stringResource(Res.string.joint_over_limit_grace_detail, deadlineLabel ?: "")
                        },
                    size = RaftUiBannerSize.Medium,
                    iconName = RaftLucideIconName.TriangleAlert,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    // Web ChannelLimitBanners: Upgrade opens the upgrade dialog (read-only:
                    // `joint_channel_read_only`, in its grace period: `joint_channel_over_limit`).
                    // iOS view-only Billing: "View your plan" (the view-only Plan & Billing page).
                    action =
                        if (settingsBillingOffersUpgrade()) {
                            val gate =
                                if (jointBillingNotice == JointBillingNotice.Locked) RaftUpgradeGate.JointChannelReadOnly
                                else RaftUpgradeGate.JointChannelOverLimit
                            ({
                                RaftButton(
                                    text = stringResource(Res.string.raft_pro_upgrade),
                                    onClick = { onThreadEvent(ThreadOpenUpgradeEvent, gate.wire, null, null) },
                                    variant = RaftButtonVariant.Default,
                                    size = RaftButtonSize.Md,
                                )
                            })
                        } else {
                            val openPlan = LocalThreadActionCardSpecHost.current.openPlan
                            ({ build.raft.app.components.RaftViewPlanButton(onClick = openPlan) })
                        },
                )
            }
            // Agent Lounge v0 (slock #16450, B1): switching between Lounge and Private DM lives in
            // the header's avatar switcher menu, with the audience line under the title. Keep the
            // fallback audience banner only when the avatar switcher menu is unavailable.
            val loungeSwitch =
                data.lounge?.conversationSwitch?.takeIf { LocalThreadAssistantPanelEmbed.current == null }
            if (conversationLounge != null && loungeSwitch == null) {
                AgentLoungeAudienceNotice(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            // A conversion to an external channel under way: before the announcement, which stays next
            // to the messages; not in a Lounge (web ChatPanel `!isLounge`).
            if (conversationLounge == null) data.channelConversionStrip?.invoke()
            // The channel's or DM's pinned content: last of the top regions, right above the timeline (raft-design channel.md).
            data.channelAnnouncementStrip?.invoke {
                if (payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
                    if (!(settingsOpensAsPage && onOpenSettingsPage?.invoke() == true)) activeModal = "settings"
                } else {
                    onThreadEvent("openChannelSettings", null, null, null)
                }
            }
            val threadTabContent: @Composable ColumnScope.() -> Unit = {
            data.taskConversationBar?.invoke()
            // No tab pager around the list: dragging it left shows message times
            // (ThreadMessageTimeReveal.kt). Task v2 replaced the Chat / Tasks / Files tab row with
            // the header's Tasks / Files cards (slock #16109). On a phone the list draws the task
            // head as its first row.
            CompositionLocalProvider(
                LocalThreadTimeRevealAvailable provides true,
                LocalTaskConversationHeadRow provides data.taskConversationHeadRow,
            ) {
                renderThreadTabContent(
                    // Task v2 cards: the page under them is always the conversation.
                    if (sideCardsEnabled) RaftThreadTab.Chat else selectedTab,
                    Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)
                        // Task v2 on a phone: scrolling the messages puts the pulled-down head back up.
                        .then(data.taskConversationHead?.let { Modifier.nestedScroll(it.connection) } ?: Modifier),
                    true
                )
            }
            }
            if (payload.isTaskConversationSurface()) {
                // Task v2: the head pulled down from the top bar hangs over the messages (no scrim,
                // nothing moves under it); a press outside it, anywhere on the page, puts it back up
                // (rootModifier).
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize()) { threadTabContent() }
                    data.taskConversationFloat?.invoke()
                }
            } else {
                threadTabContent()
            }
            if (renderComposerDock) {
                // ThreadComposerDock itself lives in ThreadRouteScaffold.composerDock so
                // LoadingShell -> FeatureSurfaces never detaches the live editor; only the
                // optional upcoming-freeze banner sits at the bottom of slots.body above it.
                if (
                    primaryFooterReady &&
                    externalFreezeState is ExternalFreezeState.Upcoming &&
                    payload.surfaceKind == RaftThreadSurfaceKind.Channel &&
                    !payload.isTaskConversationSurface() &&
                    selectedTab == RaftThreadTab.Chat
                ) {
                    ThreadExternalFreezeNoticeBanner(
                        deadlineIso = externalFreezeState.deadlineIso,
                        plan = billingServerPlan,
                        role = billingServerRole,
                        onOpenUpgrade = {
                            onThreadEvent(
                                ThreadOpenUpgradeEvent,
                                RaftUpgradeGate.ExternalChannelFrozen.wire,
                                externalFreezeState.deadlineIso,
                                null,
                            )
                        },
                    )
                }
            } else if (!primaryFooterReady) {
                // Footer not ready and optimistic composer is suppressed (known error, no_permission,
                // archived, frozen, unjoined, lounge footer, or non-Chat tab).
                Unit
            } else if (
                !writeActionsEnabled &&
                payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                (replyParentAuthority == ReplyParentAuthorityStatus.Unknown ||
                    replyParentAuthority == ReplyParentAuthorityStatus.ParentDeleted)
            ) {
                // Task #228 successor direction B: "authority undetermined" is
                // never rendered as a silently vanished composer - the user
                // gets an explicit explanation and the state upgrades itself
                // event-driven when the parent directory row arrives.
                // Task #339: an authoritative directory miss says the channel
                // was deleted instead of implying permissions are resolving.
                ThreadFooterNoticeDock(
                    bottomSafeArea = composerBottomSafeArea,
                    horizontalPadding = 0.dp,
                    topPadding = 0.dp,
                    bottomPadding = 0.dp,
                ) {
                    Column(
                        modifier =
                            Modifier.fillMaxWidth()
                                .raftCardSurface()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text =
                                stringResource(
                                    when (replyParentAuthority) {
                                        ReplyParentAuthorityStatus.ParentDeleted ->
                                            Res.string.thread_parent_channel_deleted_composer
                                        else -> Res.string.thread_parent_authority_resolving
                                    }
                                ),
                            style = RaftType.Body.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = RaftColor.TextBlack.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            } else if (loungeFooterKind != null) {
                // Agent Lounge v0: an archived Lounge says so (Unarchive only for a viewer whose server
                // role may archive channels), and one you may only read has the read-only composer;
                // never a Join.
                ThreadFooterNoticeDock(bottomSafeArea = composerBottomSafeArea) {
                    AgentLoungeFooter(
                        kind = loungeFooterKind,
                        inputs = data.lounge,
                    )
                }
            } else if (archivedFooterActive) {
                // Web ChatPanel `isArchived`: the archived banner in place of the composer or the
                // Join button, for members and non-members alike (the server refuses every write).
                val archived = data.lounge?.archivedChannel
                ThreadFooterNoticeDock(bottomSafeArea = composerBottomSafeArea) {
                    ThreadArchivedChannelNotice(
                        canUnarchive = archived?.canUnarchive == true,
                        unarchiving = archived?.unarchiving == true,
                        onUnarchive = {
                            archived?.onUnarchive?.invoke { onThreadEvent("retryThreadLoad", null, null, null) }
                        },
                    )
                }
            } else if (taskConversationReadOnly && selectedTab == RaftThreadTab.Chat) {
                // Web ChatPanel / TaskConversationDialog `isJointTaskReadOnly` (slock #16739):
                // a historical joint task's participant discussion is read-only here.
                ThreadFooterNoticeDock(bottomSafeArea = composerBottomSafeArea) {
                    TaskReadOnlyDiscussionComposer()
                }
            } else if (!writeActionsEnabled) {
                Unit
            } else if (shareSelectionActive) {
                // Preserve the footer's layout height while capture owns the
                // viewport. Removing it shifts Arrangement.Bottom rows after
                // their bounds were reported, so PixelCopy starts one toolbar
                // height above the selected message.
                Box(
                    modifier =
                        Modifier
                            .onGloballyPositioned { coordinates ->
                                shareState.shareCaptureViewportBottomPx = coordinates.positionInRoot().y
                            }
                            .raftToastAvoid(enabled = !shareState.shareCaptureMode)
                            .alpha(if (shareState.shareCaptureMode) 0f else 1f)
                ) {
                    ThreadShareSelectionToolbar(
                        selectedCount = selectedShareMessages.size,
                        onCancel = clearShareSelection,
                        selectAll =
                            if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                                ThreadShareSelectAll(
                                    check = threadShareSelectAllCheck(shareState.selectedShareMessageIds, selectableShareMessageIds),
                                    complete = selectableShareComplete,
                                    enabled = shareState.selectedShareMessageIds.isNotEmpty() || selectableShareMessageIds.isNotEmpty(),
                                    onClick = {
                                        shareState.selectedShareMessageIds =
                                            threadShareSelectAllNext(shareState.selectedShareMessageIds, selectableShareMessageIds)
                                        // Web selectAll: clearing drops the anchor; selecting all keeps it.
                                        if (shareState.selectedShareMessageIds.isEmpty()) shareState.shareSelectionAnchorId = null
                                    },
                                )
                            } else {
                                null
                            },
                        onForward = {
                            openForwardPickerForMessageIds(shareState.selectedShareMessageIds)
                        },
                        forwardEnabled = writeActionsEnabled,
                        onCopyMarkdown = {
                            if (selectedShareMarkdown.isNotBlank()) {
                                onThreadEvent("copyShareSelectionMarkdown", selectedShareMarkdown, null, null)
                            }
                        },
                        onExportImage = {
                            if (threadShareImageRendersOffscreen && selectedShareMessages.isNotEmpty()) {
                                // Laid out again off screen at the picked width (the renderer in `overlays`).
                                diagnosticsLogger(
                                    "thread_share_export_click selected=${selectedShareMessages.size} " +
                                        "offscreen=true width=${shareState.shareImageWidthChoice.widthDp}"
                                )
                                shareState.sharePreviewImage = null
                                shareState.shareImageBitmap = null
                                shareState.shareImageRendering = true
                                shareState.shareImageRenderWidthDp = shareState.shareImageWidthChoice.widthDp
                                activeModal = "sharePreview"
                            } else if (selectedShareMessages.isNotEmpty() && !shareState.sharePreviewLoading) {
                                diagnosticsLogger(
                                    "thread_share_export_click selected=${selectedShareMessages.size} " +
                                        "scopeActive=${shareExportScope.coroutineContext[Job]?.isActive == true}"
                                )
                                shareState.sharePreviewLoading = true
                                shareState.sharePreviewImage = null
                                shareState.shareCaptureMode = true
                                // Start before this click hides the selection toolbar and
                                // changes capture-mode row composition. Kuikly may apply that state change before a
                                // normally-dispatched child gets its first turn, leaving capture
                                // mode to scroll the anchor without ever entering the export job.
                                shareExportScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    diagnosticsLogger("thread_share_export_job_start")
                                    var result: JSONObject? = null
                                    try {
                                        val captureRect = captureThreadShareFragments(
                                            shareState = shareState,
                                            selectedShareMessages = selectedShareMessages,
                                            timelineSlice = timelineSlice,
                                            onExportShareImage = onExportShareImage,
                                            diagnosticsLogger = diagnosticsLogger,
                                        )
                                        result =
                                            captureRect?.let { rect ->
                                                runCatching { onExportShareImage(rect) }.getOrNull()
                                            }
                                    } finally {
                                        shareState.shareCaptureMode = false
                                        shareState.shareCaptureTargetMessageId = null
                                        shareState.shareCaptureAnchorBeforeTarget = false
                                        shareState.shareCaptureTailPaddingActive = false
                                        shareState.shareCaptureScrollOffsetPx = ThreadShareCaptureTopScrollOffsetPx
                                        shareState.sharePreviewLoading = false
                                    }
                                    if (result?.optString("status") == "ok") {
                                        shareState.sharePreviewImage =
                                            ThreadSharePreviewImage(
                                                uri = result.optString("uri"),
                                                path = result.optString("path"),
                                                width = result.optString("width").toIntOrNull() ?: 0,
                                                height = result.optString("height").toIntOrNull() ?: 0
                                            )
                                        activeModal = "sharePreview"
                                    } else {
                                        onThreadEvent(
                                            "shareSelectionExportFailed",
                                            result?.optString("message")?.takeIf { it.isNotBlank() },
                                            null,
                                            null
                                        )
                                    }
                                }
                            }
                        },
                        onCopyLinks = {
                            if (selectedShareLinks.isNotBlank()) {
                                onThreadEvent("copyShareSelectionLinks", selectedShareLinks, null, null)
                            } else if (selectedShareMarkdown.isNotBlank()) {
                                onThreadEvent("copyShareSelectionMarkdown", selectedShareMarkdown, null, null)
                            }
                        }
                    )
                }
            } else if (
                externalFreezeState == ExternalFreezeState.Active &&
                selectedTab == RaftThreadTab.Chat &&
                (payload.showComposer || threadFooterShowsJoin(payload.joined, conversationLounge))
            ) {
                // Web ChannelLimitBanners `ExternalFrozenBanner` (botiverse/slock #15653):
                // replaces the composer on frozen channels, reply threads, and task conversations.
                ThreadFooterNoticeDock(bottomSafeArea = composerBottomSafeArea) {
                    ThreadExternalFrozenBanner(
                        serverName = billingServerName,
                        plan = billingServerPlan,
                        role = billingServerRole,
                        onOpenUpgrade = {
                            onThreadEvent(ThreadOpenUpgradeEvent, RaftUpgradeGate.ExternalChannelFrozen.wire, null, null)
                        },
                    )
                }
            } else if (threadFooterShowsJoin(payload.joined, conversationLounge)) {
                if (
                    externalFreezeState is ExternalFreezeState.Upcoming &&
                    payload.surfaceKind == RaftThreadSurfaceKind.Channel &&
                    !payload.isTaskConversationSurface() &&
                    selectedTab == RaftThreadTab.Chat
                ) {
                    ThreadExternalFreezeNoticeBanner(
                        deadlineIso = externalFreezeState.deadlineIso,
                        plan = billingServerPlan,
                        role = billingServerRole,
                        onOpenUpgrade = {
                            onThreadEvent(
                                ThreadOpenUpgradeEvent,
                                RaftUpgradeGate.ExternalChannelFrozen.wire,
                                externalFreezeState.deadlineIso,
                                null,
                            )
                        },
                    )
                }
                ThreadJoinFooter(
                    label = localizedThreadJoinLabel(payload.joinLabel),
                    onJoin = { onThreadEvent("joinChannel", null, null, null) },
                    joining = ThreadChannelJoinsInFlight.observeIsJoining(payload.joinChannelTargetId()),
                    bottomSafeArea = composerBottomSafeArea,
                )
            }
            },
            overlays = {
        ThreadComposerSuggestionPortalHost(composerSuggestionPortal)
        if (threadShareImageRendersOffscreen && activeModal == "sharePreview" && selectedShareMessages.isNotEmpty()) {
            val timelineRenderConfig = rememberThreadTimelineRenderConfig(payload)
            val shareMarkdownTagResolution =
                threadMarkdownTagResolution(
                    payload = payload,
                    timelineSlice = timelineSlice,
                    linkedTasksProjection = linkedTasksProjection,
                    highlightedMentions = highlightedMentions,
                    markdownReferencePendingActionId = markdownReferencePendingActionId,
                    onThreadEvent = onThreadEvent,
                    loungeRefsEnabled = data.lounge?.loungesEnabled == true,
                )
            val shareImageAuthorSuppression =
                remember(selectedShareMessages, timelineSlice.messages, timelineSlice.compactConsecutiveMessages, timelineRenderConfig) {
                    threadShareImageSuppressesAuthor(
                        timeline = timelineSlice.messages,
                        selected = selectedShareMessages,
                        compactConsecutiveMessages = timelineSlice.compactConsecutiveMessages,
                        localOffsetMinutes = timelineRenderConfig.timeContext.localOffsetMinutes,
                    )
                }
            ThreadShareImageOffscreenRenderer(
                cardWidthDp = shareState.shareImageRenderWidthDp,
                rowCount = selectedShareMessages.size,
                renderKey = selectedShareMessages,
                background = RaftColor.Canvas,
                onRendering = {
                    shareState.shareImageRendering = true
                    // Keep only the old preview on screen while the next image renders: the full
                    // bitmap (up to 64 MB) can go before the new one is allocated. Download waits.
                    shareState.shareImageBitmap = shareState.shareImageBitmap?.let { ThreadShareRenderedImage(image = it.preview, preview = it.preview) }
                },
                onRendered = { image ->
                    shareState.shareImageBitmap = image
                    shareState.shareImageRendering = false
                    diagnosticsLogger(
                        "thread_share_offscreen_rendered width=${shareState.shareImageRenderWidthDp} " +
                            "ok=${image != null} px=${image?.image?.width ?: 0}x${image?.image?.height ?: 0} " +
                            "preview=${image?.preview?.width ?: 0}x${image?.preview?.height ?: 0}"
                    )
                },
            ) { index ->
                val message = selectedShareMessages[index]
                val linkedTask = linkedTasksProjection.lookup.threadLinkedTaskForMessage(message)
                val replyInThread =
                    threadMessageIsReplyInThread(timelineSlice.replyThreadSurface, timelineSlice.originMessage?.id, message.id)
                val sourceTasks =
                    remember(message.id, linkedTasksProjection, replyInThread) {
                        if (replyInThread) emptyList() else linkedTasksProjection.sourceTasksFor(message)
                    }
                val displayMessage =
                    remember(message, linkedTasksProjection, sourceTasks.isEmpty()) {
                        message.withLinkedTaskProjection(linkedTasksProjection)
                            .let { if (sourceTasks.isEmpty()) it else it.withoutLinkedTask() }
                    }
                val isSystemMessage = remember(message.messageType) { message.isSystemMessage() }
                val actionCard = if (contentExtrasVisible) remember(message.id, message.metadata) { message.threadActionCard() } else null
                if (isSystemMessage && actionCard == null) {
                    ThreadSystemMessageRow(
                        message = displayMessage,
                        timestampValue = message.timestampLabel,
                        timeline = timelineSlice.messages,
                        messageBodyTextStyle = messageBodyTextStyle,
                        memberAvatarResolver = memberAvatarResolver,
                        memberModelLabelResolver = memberModelLabelResolver,
                        isDm = timelineSlice.directMessageSurface,
                    )
                } else {
                    ThreadShareImageMessageRow(
                        message = displayMessage,
                        messageBodyTextStyle = messageBodyTextStyle,
                        compactConsecutiveMessages = timelineSlice.compactConsecutiveMessages,
                        suppressRepeatedAuthor = shareImageAuthorSuppression.getOrElse(index) { false },
                        timestampLabel = message.timestampLabel,
                        compactTimestampLabel = timelineRenderConfig.formatCompactTimestampLabel(message),
                        replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message),
                        sourceTasks = sourceTasks,
                        actionCard = actionCard,
                        actionCardExecutedTimeLabel =
                            timelineRenderConfig.formatActionCardExecutedTimeLabel(
                                actionCard?.executedAt,
                                currentRaftTimeContextProvider?.todayEpochDay,
                                rememberRaftMessageTimeFormat()
                            ),
                        resolvedTaskAssigneeName = linkedTask?.threadLinkedTaskAssigneeDisplayName(),
                        highlightedMentions = highlightedMentions,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        memberModelLabelResolver = memberModelLabelResolver,
                        inlineReplyParticipantsById = inlineReplyParticipantsById,
                        resolveMarkdownTagLabel = shareMarkdownTagResolution.resolveMarkdownTagLabel,
                        isMarkdownTagValid = shareMarkdownTagResolution.isMarkdownTagValid,
                        markdownTagContextSignature = shareMarkdownTagResolution.markdownTagContextSignature,
                        alignOwnMessageRight = timelineSlice.alignOwnMessagesRight && message.isOwn,
                        messageAlignmentModeEnabled = timelineSlice.alignOwnMessagesRight,
                        currentServerSlug = currentServerSlug,
                        richContentEnabled = contentExtrasVisible,
                    )
                }
            }
        }
        ThreadForwardOverlays(
            shareState = shareState,
            activeModalState = activeModalState,
            threadInstanceKey = threadInstanceKey,
            payload = payload,
            writeActionsEnabled = writeActionsEnabled,
            forwardServerId = forwardServerId,
            forwardSourceChannelId = forwardSourceChannelId,
            forwardTargetRepository = forwardTargetRepository,
            forwardMessageSelection = forwardMessageSelection,
            forwardTargetItems = forwardTargetItems,
            forwardConversationItems = forwardConversationItems,
            shareExportScope = shareExportScope,
            clearShareSelection = clearShareSelection,
            onThreadEvent = onThreadEvent,
            onForwardMessagesDelivered = onForwardMessagesDelivered,
            onOpenForwardDestination = onOpenForwardDestination,
        )
        when (activeModal) {
            "settings" -> {
                // Only a DM and a Lounge open here (a channel opens its details page, ChannelSettingsPage.kt).
                // Agent Lounge v0: a Lounge's overflow is the DM's (mute, section, collapse): no
                // members, settings or edit (the server refuses rename, visibility and other agents).
                ThreadDirectMessageSettingsDrawer(
                    name = conversationLounge?.let { agentLoungeTitle(it.displayAgentName) } ?: headerDisplay.title,
                    directMessageTag = conversationLounge == null,
                    activityMuteState = activityMuteState,
                    section = channelSettingsPanel?.section,
                    sectionEnabled = channelSettingsPanel?.writeActionsEnabled == true,
                    longMessageCollapseSetting = longMessageCollapseConversationSetting,
                    onDismiss = { activeModal = null },
                    onToggleActivityMute = onToggleActivityMute,
                    onMoveSection = { moves -> channelSettingsPanel?.onMoveSection?.invoke(moves) },
                    onCreateSection = { name, emoji -> channelSettingsPanel?.onCreateSection?.invoke(name, emoji) },
                    announcement = if (conversationLounge == null) channelSettingsPanel?.dmAnnouncement else null,
                )
            }
            "sharePreview" ->
                ThreadSharePreviewOverlay(
                    shareState = shareState,
                    activeModalState = activeModalState,
                    selectedShareMessages = selectedShareMessages,
                    selectedShareMarkdown = selectedShareMarkdown,
                    sharePngWriter = sharePngWriter,
                    shareExportScope = shareExportScope,
                    onSaveShareImage = onSaveShareImage,
                    onThreadEvent = onThreadEvent,
                )
        }
            }
        )
}

@Composable
private fun rememberThreadTimelineRenderConfig(payload: RaftThreadScreenPayload): ThreadTimelineRenderConfig {
    val effectiveTimeFormat = currentRaftDateTimeContext(null)?.timeFormat ?: payload.timeFormat
    return remember(
        payload.backgroundRefreshing,
        payload.errorCode,
        payload.errorServerMessage,
        payload.errorTraceId,
        payload.originPreview,
        payload.originErrorCode,
        payload.localOffsetMinutes,
        payload.todayEpochDay,
        payload.nowEpochMillis,
        effectiveTimeFormat,
        payload.members
    ) {
        val config = payload.toThreadTimelineRenderConfig()
        if (config.timeContext.timeFormat == effectiveTimeFormat) {
            config
        } else {
            config.copy(timeContext = config.timeContext.withTimeFormat(effectiveTimeFormat))
        }
    }
}

// The share image's effects (ThreadPageContentContent), verbatim and at the same place.
@Composable
@NonRestartableComposable
private fun ThreadShareImageEffects(
    shareState: ThreadShareForwardState,
    sharePreviewOpen: Boolean,
) {
    LaunchedEffect(shareState.shareImageWidthChoice) {
        val widthDp = shareState.shareImageWidthChoice.widthDp
        if (widthDp != shareState.shareImageRenderWidthDp) {
            // A drag of the custom slider lays the image out again once it pauses.
            if (shareState.shareImageWidthChoice.preset == ThreadShareImageWidth.Custom) delay(ThreadShareImageWidthDebounceMs)
            shareState.shareImageRenderWidthDp = widthDp
        }
    }
    // The share image (up to 16M px) is held only while Share preview is open: however it closes
    // (dismiss, saved, another modal), the bitmap goes with it.
    LaunchedEffect(sharePreviewOpen) {
        if (!sharePreviewOpen) {
            shareState.shareImageBitmap = null
            shareState.shareImageRendering = false
        }
    }
}

// The forward picker's target loading (ThreadPageContentContent), verbatim and at the same place.
@Composable
@NonRestartableComposable
private fun ThreadForwardPickerEffects(
    shareState: ThreadShareForwardState,
    payload: RaftThreadScreenPayload,
    forwardConversationLoader: ThreadForwardConversationTargetsLoader?,
    forwardServerId: ServerId?,
    forwardTargetRepository: ForwardMessageTargetRepository?,
) {
    LaunchedEffect(shareState.forwardPickerFlow.sessionKey, shareState.forwardSourceServerId, forwardConversationLoader) {
        val sessionKey = shareState.forwardPickerFlow.sessionKey
        val serverId = shareState.forwardSourceServerId
        val loader = forwardConversationLoader
        if (sessionKey == null || serverId == null || loader == null || sessionKey.serverId != serverId.value) {
            shareState.forwardConversationTargets = emptyList()
            return@LaunchedEffect
        }
        shareState.forwardConversationTargets =
            loader.load(
                sessionKey = sessionKey,
                serverId = serverId,
                currentChannelId = payload.threadForwardConversationCurrentChannelId()
            )
    }
    LaunchedEffect(
        shareState.forwardPickerFlow.sessionKey,
        shareState.forwardPickerFlow.query,
        shareState.forwardPickerFlow.requestGeneration,
        shareState.forwardSourceServerId,
        forwardServerId,
        forwardTargetRepository
    ) {
        val sessionKey = shareState.forwardPickerFlow.sessionKey ?: return@LaunchedEffect
        if (shareState.forwardPickerFlow.step == RaftForwardPickerStep.Closed) return@LaunchedEffect
        val requestGeneration = shareState.forwardPickerFlow.requestGeneration
        val repository = forwardTargetRepository
        val serverId = shareState.forwardSourceServerId
        if (repository == null || serverId == null) {
            shareState.forwardPickerFlow =
                shareState.forwardPickerFlow.reduce(
                    RaftForwardPickerFlowEvent.LoadFailed(
                        generation = requestGeneration,
                        errorCode = "forward_target_repository_unavailable"
                    )
                )
            return@LaunchedEffect
        }
        if (forwardServerId != serverId) {
            repository.releaseDirectorySnapshot(sessionKey.directorySessionId())
            shareState.forwardDirectoryNextCursor = null
            shareState.forwardPickerFlow =
                shareState.forwardPickerFlow.reduce(
                    RaftForwardPickerFlowEvent.LoadFailed(
                        generation = requestGeneration,
                        errorCode = "forward_source_server_changed"
                    )
                )
            return@LaunchedEffect
        }
        val query = shareState.forwardPickerFlow.query
        val append = query.isBlank() && shareState.forwardPickerFlow.appending && shareState.forwardDirectoryNextCursor != null
        val cursor =
            if (append) {
                shareState.forwardDirectoryNextCursor ?: ForwardMessageTargetDirectoryCursor()
            } else {
                ForwardMessageTargetDirectoryCursor()
            }
        val requestIdentity =
            RaftForwardPickerRequestIdentity(
                serverId = serverId.value,
                sessionKey = sessionKey,
                query = query,
                generation = requestGeneration
            )
        val isCurrentRequest = {
            val liveSession = shareState.forwardPickerFlow.sessionKey
            liveSession != null &&
                requestIdentity.accepts(
                    serverId = forwardServerId?.value.orEmpty(),
                    sessionKey = liveSession,
                    query = shareState.forwardPickerFlow.query,
                    generation = shareState.forwardPickerFlow.requestGeneration
                )
        }
        // Search: local DM/channel matches show at once (display-only while Loading), the
        // server's ranked list replaces them after the debounce. This effect restarts per
        // keystroke, cancelling the previous server request; isCurrent drops stale answers.
        val result: DataResult<ThreadForwardTargetLoadPage> =
            if (query.isBlank()) {
                raftForwardPickerGatedLoad<DataResult<ThreadForwardTargetLoadPage>>(
                    debounce = false,
                    isCurrent = isCurrentRequest
                ) {
                    when (
                        val page =
                            repository.directoryTargets(
                                serverId = serverId,
                                sessionId = sessionKey.directorySessionId(),
                                cursor = cursor,
                                pageSize = 30
                            )
                    ) {
                        is DataResult.Content ->
                            DataResult.Content(
                                ThreadForwardTargetLoadPage(
                                    items = page.data.items,
                                    nextCursor = page.data.nextCursor,
                                    reset = page.data.reset
                                )
                            )
                        is DataResult.Empty -> DataResult.Empty(page.reason)
                        is DataResult.Error -> DataResult.Error(page.error)
                        is DataResult.NoPermission -> DataResult.NoPermission(page.blocker)
                    }
                }
            } else {
                when (
                    val page =
                        raftForwardPickerProgressiveSearch(
                            repository = repository,
                            serverId = serverId,
                            query = query,
                            limit = 60,
                            isCurrent = isCurrentRequest,
                            onInterim = { interim ->
                                shareState.forwardTargets = interim
                                shareState.forwardDirectoryNextCursor = null
                            }
                        )
                ) {
                    null -> null
                    is DataResult.Content -> DataResult.Content(ThreadForwardTargetLoadPage(items = page.data))
                    is DataResult.Empty -> DataResult.Empty(page.reason)
                    is DataResult.Error -> DataResult.Error(page.error)
                    is DataResult.NoPermission -> DataResult.NoPermission(page.blocker)
                }
            } ?: return@LaunchedEffect
        when (result) {
            is DataResult.Content -> {
                val nextItems = result.data.items
                shareState.forwardTargets =
                    if (append && !result.data.reset) {
                        (shareState.forwardTargets + nextItems).distinctBy { it.threadForwardSelectionKey() }
                    } else {
                        nextItems
                    }
                shareState.forwardDirectoryNextCursor =
                    if (query.isBlank()) result.data.nextCursor else null
                shareState.forwardDirectoryGeneration += 1
                shareState.forwardPickerFlow =
                    shareState.forwardPickerFlow.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(requestGeneration))
            }
            is DataResult.Empty -> {
                if (!append) shareState.forwardTargets = emptyList()
                shareState.forwardDirectoryNextCursor = null
                shareState.forwardDirectoryGeneration += 1
                shareState.forwardPickerFlow =
                    shareState.forwardPickerFlow.reduce(RaftForwardPickerFlowEvent.LoadSucceeded(requestGeneration))
            }
            is DataResult.Error -> {
                if (result.error.code == "forward_directory_snapshot_expired") {
                    shareState.forwardDirectoryNextCursor = null
                }
                shareState.forwardPickerFlow =
                    shareState.forwardPickerFlow.reduce(
                        RaftForwardPickerFlowEvent.LoadFailed(requestGeneration, result.error.code)
                    )
            }
            is DataResult.NoPermission -> {
                shareState.forwardPickerFlow =
                    shareState.forwardPickerFlow.reduce(
                        RaftForwardPickerFlowEvent.LoadFailed(requestGeneration, result.blocker.code)
                    )
            }
        }
    }
}

private data class ThreadForwardTargetLoadPage(
    val items: List<ForwardMessageTarget>,
    val nextCursor: ForwardMessageTargetDirectoryCursor? = null,
    val reset: Boolean = false
)

/** web inline Save: how long "Changes saved" shows before the form is idle again. */
private const val ThreadSettingsSavedNoticeMillis = 1500L

@Composable
private fun localizedThreadJoinLabel(value: String): String =
    if (value == "Join channel") {
        stringResource(Res.string.thread_action_join_channel)
    } else {
        value
    }
