package build.raft.app

import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses

import build.raft.app.components.RaftAnchoredMetaChip
import build.raft.app.components.RaftAttachmentPreviewCard
import build.raft.app.components.RaftAttachmentPreviewCardWidth
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftChipLoadingIndicator
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.RaftBrutalSearchField
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.components.SharedHomeSearchInput
import build.raft.app.components.RaftToolbarChip
import build.raft.app.base.Utils
import build.raft.app.base.putIfAbsent
import build.raft.app.base.reduceAttachmentDownloadProgress
import build.raft.app.data.PlatformFile
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.AuthLossSignal
import build.raft.app.data.ForwardMessageExecuteRequest
import build.raft.app.data.ForwardMessageSendMode
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetDirectoryCursor
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.ForwardMessageTargetRequiredAction
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.NetworkRaftTasksApi
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftThreadApiClient
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.RaftEventBus
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.RaftIntegrationsActionsPushNetworkApi
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.data.isRaftHostVisible
import build.raft.app.data.realtime.RealtimeRoutePolicy
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedMembersIntent
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.data.SharedMembersMemberSuggestion
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.SharedMembersStore
import build.raft.app.data.SharedHomeShellDestinationSearch
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTaskReferenceResolution
import build.raft.app.data.SharedTaskReferenceResolutionSource
import build.raft.app.data.TaskSnapshot
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.RealtimeCanonicalFinalSinkSurfaceKey
import build.raft.app.data.RealtimeScopeAuthority
import build.raft.app.data.RealtimeRoomLeaseOwner
import build.raft.app.data.AGENT_CREATE_NAME_TAKEN
import build.raft.app.data.SharedMessageTranslationStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.PendingMentionAction
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.TasksUiState
import build.raft.app.data.resolveSharedTaskReference
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isAuthSessionErrorCode
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.authLossTokenFingerprint
import build.raft.app.data.canonicalServerMembersMentionHandle
import build.raft.app.data.convertMessageToCanonicalTask
import build.raft.app.data.isSupportedCommitAction
import build.raft.app.data.isSubmittableChannelName
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ConversationKind
import build.raft.app.model.ForwardMessageAttachment
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.markdown.LocalRaftMessagePermalinkLabelResolver
import build.raft.app.markdown.LocalRaftMarkdownRenderConfig
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.markdown.LocalRaftMarkdownPlainCandidateSession
import build.raft.app.markdown.RaftMarkdownPlainCandidateSession
import build.raft.app.markdown.resolveThreadMarkdownSingleParagraphLightPathEnabled
import build.raft.app.markdown.threadMarkdownLightGateInputsOrNull
import build.raft.app.markdown.raftMarkdownProvidedValuesWith
import build.raft.app.markdown.raftTraceDiagnosticsEnabled
import build.raft.app.markdown.RaftMarkdownRenderConfig
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.prepareRaftMarkdownFullRendererBodies
import build.raft.app.markdown.preparedRaftMarkdownFullRendererContentOrNull
import build.raft.app.markdown.threadMarkdownTagDisplayLabel
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.network.sync.ThreadRepliesReadModel
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import coil3.compose.AsyncImagePainter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import build.raft.app.compat.ui.keyboardDismissModeInteractiveIOS
import androidx.compose.material3.Text
import build.raft.app.compat.ui.imageResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.toIntSize
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.module.PerformanceModule
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IPagerEventObserver
import build.raft.app.compat.pager.Pager
import build.raft.app.resources.localized
import build.raft.app.resources.stringResource
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Visual-only host for the thread message row capture route (task #189).
 *
 * Moved out of ThreadPage.kt so production sources carry no visual @Page. Compiled into commonMain
 * only when -PslockVisualFixtures=true. Fixture DATA lives in src/visualFixtureData/kotlin.
 *
 * Regenerated from main at merge time: this file is a MOVE of main's current content, not a snapshot
 * taken when the split began. A merge deletes main's copy but cannot propagate main's edits into this
 * file, so re-extracting is the only way to keep them equal.
 */

@Page("compose_thread_message_row_visual", supportInLocal = true)
internal class ComposeThreadMessageRowVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadMessageVisualScreen() }
    }
}
private val ThreadMessageMenuVisualViewportWidth = 390.dp
private val ThreadMessageMenuVisualViewportHeight = 844.dp

@Composable
internal fun ThreadMessageVisualScreen() {
    val visualFixture = (LocalActivity.current.getPager() as Pager).pageData.params.optString("visualFixture")
    ThreadReactionAtlasProvider {
        CompositionLocalProvider(
            LocalRaftMarkdownTaskStatuses provides threadVisualFixtureTaskStatuses(visualFixture)
        ) {
            ThreadMessageVisualScreenContent()
        }
    }
}
/**
 * The message of `components.thread.message-menu.task`: slock VisualTestingCases.tsx
 * `threadMessageMenuFixture(true)` inlines it (it is not in the shared fixtureData.json).
 */
private const val THREAD_MESSAGE_MENU_TASK_BODY =
    "Task conversion follow-up for #design: capture the grouped menu before publish."

@Composable
internal fun ThreadMessageVisualScreenContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val visualFixture = params.optString("visualFixture")
    val menuVariant = params.optString("menuVariant")
    val interactionProbe = params.optString("interactionProbe")
    val instanceId = params.optString("instanceId")
    val visualSingleMessage = params.optBoolean("visualSingleMessage", false)
    val messageBodyTextStyle =
        remember(params.optString("messageBodyFontSize")) {
            raftMessageBodyTextStyle(params.optString("messageBodyFontSize"))
        }
    val longPressReadyToken = params.optString("longPressReadyToken")
    val longPressMenuVisibleToken = params.optString("longPressMenuVisibleToken")
    val newMessageMenuComposerBoundaryVisible =
        params.optBoolean("newMessageMenuComposerBoundaryVisible", false)
    val newMessageMenuTargetReplyCount = params.optInt("newMessageMenuTargetReplyCount", 0)
    val newMessageMenuEnabled =
        remember(visualFixture) {
            visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO &&
                KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
                    .readNewMessageMenuEnabledSync()
        }
    val payload =
        remember(visualFixture, instanceId, newMessageMenuTargetReplyCount) {
            threadVisualFixture(fixture = visualFixture.ifBlank { THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT }, instanceId = instanceId)
                .withNewMessageMenuTargetReplyCount(newMessageMenuTargetReplyCount)
        }
    // A visual route must publish an explicit readiness boundary.  In
    // particular, the wrapped+nested GFM fixture previously let the Android
    // harness capture the host's CALL_KOTLIN fallback as a workflow-green PNG.
    // Emit only after the fixture payload has been resolved and this host
    // composition is active; the capture harness requires this token before
    // accepting a screenshot for the regression case.
    val visualReadyBridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    var lastLoggedContentBottom by remember { mutableStateOf(-1) }
    LaunchedEffect(visualFixture, instanceId) {
        if (visualFixture.isNotBlank()) {
            visualReadyBridge.log("thread_visual_ready fixtureKey=$visualFixture")
        }
    }
    val agentStatusResolver =
        remember(visualFixture) {
            threadMessageVisualAgentStatusResolver(visualFixture)
        }
    val highlightedMentions =
        remember(visualFixture) {
            threadVisualFixtureHighlightedMentions(visualFixture)
        }
    val knownMarkdownChannelNames =
        remember(payload.knownMarkdownChannelNames) {
            payload.knownMarkdownChannelNames.toKnownMarkdownChannelNameSet()
        }
    val knownMarkdownMentionNames =
        remember(payload.knownMarkdownMentionNames, payload.members, payload.memberCandidates, payload.originMessage, payload.messages) {
            payload.toKnownMarkdownMentionNameSet()
        }
    val knownMarkdownDirectMessagePeerNames =
        remember(
            payload.knownMarkdownDirectMessagePeerNames,
            payload.dmPeerName,
            payload.dmPeerIdentity,
            payload.surfaceKind,
            payload.parentSurfaceKind,
            payload.channelKind,
            payload.parentChannelKind,
            payload.members,
            payload.memberCandidates
        ) {
            payload.toKnownMarkdownDirectMessagePeerNameSet()
        }
    val knownMarkdownTaskNumbers =
        remember(payload.knownMarkdownTaskNumbers) {
            payload.toKnownMarkdownTaskNumberSetOrNull()
        }
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean =
        remember(
            knownMarkdownChannelNames,
            knownMarkdownMentionNames,
            knownMarkdownDirectMessagePeerNames,
            knownMarkdownTaskNumbers
        ) {
            { tag ->
                tag.isKnownThreadMarkdownTag(
                    knownMarkdownChannelNames,
                    knownMarkdownMentionNames,
                    knownMarkdownDirectMessagePeerNames,
                    knownMarkdownTaskNumbers
                )
            }
        }
    val markdownTagContextSignature =
        remember(
            highlightedMentions,
            knownMarkdownChannelNames,
            knownMarkdownMentionNames,
            knownMarkdownDirectMessagePeerNames,
            knownMarkdownTaskNumbers
        ) {
            threadMarkdownTagContextSignature(
                highlightedMentions = highlightedMentions,
                knownChannelNames = knownMarkdownChannelNames,
                knownMentionNames = knownMarkdownMentionNames,
                knownDirectMessagePeerNames = knownMarkdownDirectMessagePeerNames,
                knownTaskNumbers = knownMarkdownTaskNumbers
            )
        }
    val visualReplyMetadataByParentId =
        remember(payload) {
            payload.toThreadReplyMetadataMap()
        }
    if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_SELECTION) {
        val messages = payload.toThreadTimelineSlice().messages
        // React docks the selection toolbar at the bottom of the 390x844
        // viewport (web MessageList), so the rows sit at the top and the
        // toolbar at the viewport bottom.
        Column(
            modifier =
                Modifier
                    .width(390.dp)
                    .height(844.dp)
                    .background(RaftColor.SurfaceWhite)
        ) {
            // web: `padding: 16px 16px 0`, and each new-group MessageItem opens 8dp below
            // the one before (its own wash, white between rows).
            Column(
                modifier = Modifier.padding(start = RaftAppSpacing.Md, top = RaftAppSpacing.Md + 8.dp, end = RaftAppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                messages.forEach { selectedMessage ->
                    ThreadMessageCard(
                        memberAvatarResolver = ThreadVisualMemberAvatarResolver,
                        message = selectedMessage,
                        messageBodyTextStyle = messageBodyTextStyle,
                        agentStatusResolver = agentStatusResolver,
                        replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(selectedMessage),
                        highlightedMentions = highlightedMentions,
                        memberModelLabelResolver = ThreadShareSelectionVisualModelLabels,
                        shareSelectionActive = true,
                        selectedForShare = true,
                        onToggleShareSelection = {},
                        alignOwnMessageRight = payload.alignOwnMessagesRight && selectedMessage.isOwn,
                        messageAlignmentModeEnabled = payload.alignOwnMessagesRight,
                        showReplyAction = payload.showReplyActions,
                        taskOpenEnabled = payload.showTaskOpenActions,
                        taskStatusActionsEnabled = payload.showTaskStatusActions,
                        showReactionAddAction = payload.showReactionActions,
                        timestampLabel = selectedMessage.timestampLabel,
                        onShowActions = {},
                        onOpenMember = {},
                        onOpenThread = {},
                        onOpenTask = {},
                        onOpenTaskStatusMenu = {},
                        isMarkdownTagValid = isMarkdownTagValid,
                        markdownTagContextSignature = markdownTagContextSignature,
                        onToggleReaction = {},
                        onOpenAttachment = {},
                        onOpenMarkdownTag = { _, _ -> },
                        onOpenMarkdownUrl = {},
                        outerPadding = RaftAppSpacing.Sm
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            ThreadShareSelectionToolbar(
                selectedCount = messages.size,
                selectableCount = messages.size,
                onCancel = {},
                onForward = {},
                onExportImage = {},
                onCopyLinks = {},
                onCopyMarkdown = {},
                // The React case renders in a browser page with no bottom safe area
                // (`safe-bottom-action-bar` resolves to 0); the emulator's navigation bar is a
                // device inset, not part of the toolbar.
                bottomSafeArea = 0.dp
            )
        }
        return
    }
    if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_PREVIEW) {
        Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            ThreadSharePreviewModal(
                messages = payload.toThreadTimelineSlice().messages,
                image = null,
                onDismiss = {},
                onCopyMarkdown = {},
                onDownloadImage = {}
            )
        }
        return
    }
    if (
        visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_ACTION_CARD_CATALOG ||
        visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD ||
        visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD_EXECUTED
    ) {
        val messages = payload.toThreadTimelineSlice().messages
        Column(
            modifier =
                Modifier
                    .width(390.dp)
                    .background(RaftColor.SurfaceWhite)
                    .padding(RaftAppSpacing.Md)
        ) {
            messages.forEachIndexed { index, visualMessage ->
                if (index > 0) {
                    Spacer(Modifier.height(RaftAppSpacing.Sm))
                }
                ThreadMessageCard(
                    memberAvatarResolver = ThreadVisualMemberAvatarResolver,
                    message = visualMessage,
                    messageBodyTextStyle = messageBodyTextStyle,
                    agentStatusResolver = agentStatusResolver,
                    replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(visualMessage),
                    highlightedMentions = highlightedMentions,
                    alignOwnMessageRight = false,
                    showReplyAction = false,
                    taskOpenEnabled = false,
                    taskStatusActionsEnabled = false,
                    showReactionAddAction = false,
                    timestampLabel = visualMessage.timestampLabel,
                    onShowActions = {},
                    onOpenMember = {},
                    onOpenThread = {},
                    onOpenTask = {},
                    onOpenTaskStatusMenu = {},
                    isMarkdownTagValid = isMarkdownTagValid,
                    markdownTagContextSignature = markdownTagContextSignature,
                    onToggleReaction = {},
                    onOpenAttachment = {},
                    onOpenMarkdownTag = { _, _ -> },
                    onOpenMarkdownUrl = {},
                    outerPadding = RaftAppSpacing.Sm
                )
            }
        }
        return
    }
    if (
        visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO &&
        !visualSingleMessage
    ) {
        ThreadNewMessageMenuCompactGroupVisual(
            messages = payload.toThreadTimelineSlice().messages,
            replyMetadataByParentId = payload.toThreadReplyMetadataMap(),
            messageBodyTextStyle = messageBodyTextStyle,
            agentStatusResolver = agentStatusResolver,
            newMessageMenuEnabled = newMessageMenuEnabled,
            composerBoundaryVisible = newMessageMenuComposerBoundaryVisible,
            longPressMenuVisibleToken = longPressMenuVisibleToken
        )
        return
    }
    val baseMessage = payload.toThreadTimelineSlice().messages.firstOrNull()
    val message =
        remember(baseMessage, menuVariant, interactionProbe) {
            if (
                baseMessage != null &&
                (menuVariant == "task" || interactionProbe == "task-status-owner") &&
                baseMessage.taskId == null
            ) {
                baseMessage.copy(
                    // web threadMessageMenuFixture(isTaskVariant = true) posts its own text.
                    body = if (menuVariant == "task") THREAD_MESSAGE_MENU_TASK_BODY else baseMessage.body,
                    taskId = "task-menu-246",
                    taskNumber = 246,
                    taskStatus = "in_progress",
                    taskAssigneeId = "agent-cindy",
                    taskAssigneeName = "Cindy"
                )
            } else {
                baseMessage
            }
        }
    if (message != null && interactionProbe == "task-status-owner") {
        ThreadTaskStatusOwnerAnchorVisualProbe(message)
        return
    }
    var actionsMenuVisible by remember(visualFixture, menuVariant) { mutableStateOf(menuVariant.isNotBlank()) }
    var compactMenuAnchor by remember(visualFixture, message?.id) {
        mutableStateOf<RaftPopoverAnchor?>(null)
    }
    var menuRowBounds by remember(visualFixture, message?.id) { mutableStateOf<Rect?>(null) }
    val menuActions =
        remember(message, menuVariant, actionsMenuVisible, visualReplyMetadataByParentId) {
            if (message == null || !actionsMenuVisible) {
                emptyList()
            } else {
                val menuReplyMetadata =
                    visualReplyMetadataByParentId.threadReplyMetadataFor(message).let { metadata ->
                        if (menuVariant == "followed") {
                            metadata.copy(
                                threadChannelId = metadata.threadChannelId ?: "visual-followed-thread",
                                isThreadFollowed = true
                            )
                        } else {
                            metadata
                        }
                    }
                threadMessageMenuActions(
                    message = message,
                    replyMetadata = menuReplyMetadata,
                    showReplyAction = true,
                    showSaveAction = true,
                    showTaskStatusAction = menuVariant == "task",
                    showConvertToTaskAction = menuVariant != "task",
                    showCopyLinkAction = true,
                    showShareMessagesAction = true,
                    showCancelAction = false,
                    showDeleteAction = false,
                    showForwardAction = true,
                    onReply = {},
                    onCopyLink = {},
                    onCopy = {},
                    onSelectText = {},
                    onShareMessages = {},
                    onSave = {},
                    onFollowThread = {},
                    onUnfollowThread = {},
                    onTaskStatusChange = {},
                    onConvertToTask = {},
                    onCancel = {},
                    onDelete = {}
                )
            }
        }
    LaunchedEffect(longPressMenuVisibleToken, message?.id, menuActions.size) {
        if (
            longPressMenuVisibleToken.isNotBlank() &&
            message != null &&
            menuActions.isNotEmpty()
        ) {
            Utils.logToNative(
                "$longPressMenuVisibleToken messageId=${message.id} actions=${menuActions.size}"
            )
        }
    }
    val onLongPressInputReady: ((String) -> Unit)? =
        remember(longPressReadyToken) {
            if (longPressReadyToken.isBlank()) {
                null
            } else {
                { messageId: String ->
                    Utils.logToNative("$longPressReadyToken messageId=$messageId")
                }
            }
        }
    val visualMessagePermalinkLabelResolver =
        remember(visualFixture) {
            { target: RaftMessagePermalinkTarget ->
                when (target.channelId.value) {
                    "channel-proj-server-management" ->
                        if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS_NARROW) {
                            "proj"
                        } else {
                            "proj-server-management"
                        }
                    "dm-mahua" -> "Mahua"
                    else -> null
                }
            }
        }
    var openedVisualThreadMessageId by remember(visualFixture) { mutableStateOf<String?>(null) }
    var openedVisualMarkdownUrlCount by remember(visualFixture) { mutableStateOf(0) }
    var openedVisualMentionCount by remember(visualFixture) { mutableStateOf(0) }
    var ordinaryTimestampToggleCount by remember(interactionProbe) { mutableStateOf(0) }
    var inlineReplyTimestampBounds by remember(interactionProbe) {
        mutableStateOf<ThreadInlineReplyTimestampBounds?>(null)
    }
    val inheritedTimeContextProvider = LocalRaftTimeContextProvider.current
    // A fixture that pins `nowEpochMillis` owns its clock, so every relative timestamp under it
    // resolves against that fixed instant: deterministic across runs AND rendered as real digits.
    // Without a time context, resolveRaftRelativeTimeLabel() returns Unknown and the row shows the
    // literal "unknown time" — which is how a non-monospace inline reply timestamp (#2074) shipped
    // past the case built to guard that row. Fixtures that pin no clock keep the Unknown behavior.
    val visualTimeContextProvider =
        remember(payload, interactionProbe) {
            if (interactionProbe == "reply-timestamp-owner" || payload.nowEpochMillis != null) {
                RaftTimeContextProvider(payload.toRaftTimeContext())
            } else {
                null
            }
        }
    val inlineReplyTimestampReporter =
        remember(interactionProbe) {
            if (interactionProbe == "reply-timestamp-owner") {
                { messageId: String, origin: Offset, size: IntSize ->
                    if (messageId == "reply-inline-8") {
                        inlineReplyTimestampBounds =
                            ThreadInlineReplyTimestampBounds(origin = origin, size = size)
                    }
                }
            } else {
                null
            }
        }
    CompositionLocalProvider(
        LocalRaftMessagePermalinkLabelResolver provides visualMessagePermalinkLabelResolver,
        LocalRaftMarkdownRenderConfig provides
            RaftMarkdownRenderConfig(renderMessagePermalinksAsTag = true),
        LocalRaftTimeContextProvider provides (visualTimeContextProvider ?: inheritedTimeContextProvider),
        LocalThreadInlineReplyTimestampReporter provides inlineReplyTimestampReporter,
    ) {
        Box(
            modifier =
                if (menuVariant.isNotBlank()) {
                    // The web menu cases capture the whole bg-layer-canvas viewport around the
                    // portal, so the page behind the open menu is the panel, not the host cream.
                    Modifier
                        .size(ThreadMessageMenuVisualViewportWidth, ThreadMessageMenuVisualViewportHeight)
                        .background(RaftColor.Canvas)
                } else {
                    Modifier
                }
        ) {
            Column(
                modifier =
                    Modifier
                        // React renders the row 390 wide inside a p-4 page (the
                        // viewport clips its right 16px), so the padded host
                        // keeps the row itself at the fixture width.
                        .width(threadMessageVisualWidth(visualFixture) + threadMessageVisualHostPadding(visualFixture))
                        // task #415: the visual route's window gives BOUNDED height
                        // constraints (unlike production's LazyColumn, whose items
                        // measure unbounded). TextStringRichNode clamps the natively
                        // measured text height to constraints.maxHeight, but the native
                        // view still draws the full layout — so a body taller than the
                        // window overlapped the siblings placed at the clamped height.
                        // Measure unbounded like production.
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .onGloballyPositioned { coordinates ->
                            // Component-bounds crop: the capture harness waits
                            // for this bottom edge instead of guessing a fixed
                            // height for content-sized rows.
                            val bottom = (coordinates.positionInRoot().y + coordinates.size.height).toInt()
                            if (bottom != lastLoggedContentBottom) {
                                lastLoggedContentBottom = bottom
                                visualReadyBridge.log(
                                    "thread_visual_content_bounds instanceId=$instanceId contentBottomPx=$bottom"
                                )
                            }
                        }
                        .background(RaftColor.SurfaceWhite)
                        .padding(
                            start = threadMessageVisualHostPadding(visualFixture),
                            top = threadMessageVisualHostPadding(visualFixture),
                            bottom = threadMessageVisualHostPadding(visualFixture)
                        )
            ) {
            if (interactionProbe == "reply-timestamp-owner") {
                Spacer(modifier = Modifier.height(32.dp))
                RaftRelativeTimestampText(
                    value = "2026-07-24T10:05:00Z",
                    timeContext = payload.toRaftTimeContext(),
                    displayGroup = RaftTimestampDisplayGroup.Conversation,
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    onToggle = { ordinaryTimestampToggleCount += 1 },
                )
                if (ordinaryTimestampToggleCount > 0) {
                    Box(modifier = Modifier.size(12.dp).background(RaftColor.AccentCyan))
                }
            }
            if (message != null) {
                if (
                    visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_LINK_CHILD ||
                    visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_MENTION_CHILD
                ) {
                    Row {
                        if (openedVisualMarkdownUrlCount > 0) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .background(
                                            if (openedVisualMarkdownUrlCount == 1) {
                                                Color(0xFF00E5FF)
                                            } else {
                                                Color(0xFFFF0000)
                                            }
                                        )
                            )
                        }
                        if (openedVisualMentionCount > 0) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .background(
                                            if (openedVisualMentionCount == 1) {
                                                Color(0xFFFF00FF)
                                            } else {
                                                Color(0xFFFF0000)
                                            }
                                        )
                            )
                        }
                    }
                }
                if (openedVisualThreadMessageId != null) {
                    Box(
                        modifier =
                            Modifier
                                .size(24.dp)
                                .background(
                                    if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO) {
                                        // Keep the fixture's open-thread oracle distinct from colorful reactions.
                                        Color(0xFFFF00A8)
                                    } else {
                                        RaftColor.BrandYellow
                                    }
                                )
                    )
                }
                Box(
                    modifier =
                        Modifier.onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInRoot()
                            val bounds =
                                Rect(
                                    left = origin.x,
                                    top = origin.y,
                                    right = origin.x + coordinates.size.width,
                                    bottom = origin.y + coordinates.size.height
                                )
                            if (bounds != menuRowBounds) menuRowBounds = bounds
                        }
                ) {
                ThreadMessageCard(
                    memberAvatarResolver = ThreadVisualMemberAvatarResolver,
                    message = message,
                    messageBodyTextStyle = messageBodyTextStyle,
                    agentStatusResolver = agentStatusResolver,
                    replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(message),
                    highlightedMentions = highlightedMentions,
                    popupOpen = menuActions.isNotEmpty(),
                    inlineReplyParticipantsById = payload.members.associateBy(RaftThreadMemberItem::id),
                    hasReplyDraft = visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_REPLIES,
                    alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                    messageAlignmentModeEnabled = payload.alignOwnMessagesRight,
                    showReplyAction = payload.showReplyActions,
                    resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                        value = message.taskAssigneeName,
                        id = message.taskAssigneeId
                    ),
                    resolveMarkdownTagLabel =
                        remember(payload.members) {
                            val labels = threadMarkdownMentionDisplayLabels(payload.members, emptyList())
                            ({ tag: ThreadMarkdownTag -> resolveThreadMarkdownTagDisplayLabel(tag, labels) })
                        },
                    taskOpenEnabled = payload.showTaskOpenActions,
                    taskStatusActionsEnabled = payload.showTaskStatusActions,
                    showReactionAddAction = payload.showReactionActions,
                    isMarkdownTagValid = isMarkdownTagValid,
                    markdownTagContextSignature = markdownTagContextSignature,
                    timestampLabel = message.timestampLabel,
                    onShowActions = { anchor ->
                        if (newMessageMenuEnabled) {
                            compactMenuAnchor = anchor
                        } else {
                            actionsMenuVisible = true
                        }
                    },
                    onOpenMember = {},
                    onOpenThread = { openedVisualThreadMessageId = it },
                    onOpenTask = {},
                    onOpenTaskStatusMenu = {},
                    onToggleReaction = {},
                    onOpenAttachment = {},
                    onOpenMarkdownTag = { tag, _ ->
                        if (tag.kind == ThreadMarkdownTagKind.UserMention) {
                            openedVisualMentionCount += 1
                        }
                    },
                    onOpenMarkdownUrl = { openedVisualMarkdownUrlCount += 1 },
                    onLongPressInputReady = onLongPressInputReady,
                    outerPadding = RaftAppSpacing.Sm
                )
                }
                compactMenuAnchor?.let { anchor ->
                    ThreadNewMessageMenu(
                        anchor = anchor,
                        availability =
                            ThreadNewMessageMenuActionAvailability(
                                reactions = payload.showReactionActions,
                                addReaction = payload.showReactionActions,
                                reply = payload.showReplyActions,
                                save = payload.showMessageSaveAction,
                                translate = false
                            ),
                        onDismiss = { compactMenuAnchor = null },
                        onReact = {},
                        onCopy = {},
                        onReply = { openedVisualThreadMessageId = message.id },
                        onForward = {},
                        onSave = {},
                        onTranslate = {},
                        onSelect = {},
                        onMore = { compactMenuAnchor = null }
                    )
                }
            }
            }
            // web opens the message context menu at the right-click point -- the
            // capture's `contextmenu` interaction lands on the row's center -- and
            // places its top-left corner there through `placeFloatingOverlay` in the
            // 390x844 viewport (8px inset), over the pressed row.
            menuRowBounds?.takeIf { menuActions.isNotEmpty() }?.let { rowBounds ->
                val density = LocalDensity.current
                val menuWidthPx = with(density) { threadMessageMenuWidthDp(
                            payload.showReactionActions,
                            showAddReactionAction = payload.showReactionActions
                        ).dp.toPx() }
                val menuHeightPx =
                    with(density) {
                        threadMessageMenuContentHeightDp(
                            actionGroups = menuActions.map { it.group },
                            showReactionActions = payload.showReactionActions
                        ).dp.toPx()
                    }
                val viewportGapPx = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() }
                val menuX =
                    raftContextMenuAxisPosition(
                        anchor = rowBounds.center.x,
                        contentSize = menuWidthPx,
                        viewportSize = with(density) { ThreadMessageMenuVisualViewportWidth.toPx() },
                        viewportGap = viewportGapPx
                    )
                val menuY =
                    raftContextMenuAxisPosition(
                        anchor = rowBounds.center.y,
                        contentSize = menuHeightPx,
                        viewportSize = with(density) { ThreadMessageMenuVisualViewportHeight.toPx() },
                        viewportGap = viewportGapPx
                    )
                Box(modifier = Modifier.offset { IntOffset(menuX.toInt(), menuY.toInt()) }) {
                    ThreadMessageMenuChrome(
                        actions = menuActions,
                        showReactionActions = payload.showReactionActions,
                        reactionAtlasImage = LocalThreadReactionAtlasImage.current,
                        onReact = {},
                        modifier = Modifier,
                        // The reaction header ends with Add Reaction (web `reaction-add-any`).
                        onAddReaction = if (payload.showReactionActions) ({}) else null
                    )
                }
            }
            inlineReplyTimestampBounds?.let { bounds ->
                val density = LocalDensity.current.density
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    bounds.origin.x.toInt(),
                                    bounds.origin.y.toInt(),
                                )
                            }
                            .size(
                                width = (bounds.size.width / density).dp,
                                height = (bounds.size.height / density).dp,
                            )
                            .semantics {
                                contentDescription = ThreadInlineReplyTimestampHitTargetDescription
                            }
                )
            }
        }
    }
}
@Composable
internal fun ThreadNewMessageMenuCompactGroupVisual(
    messages: List<RaftThreadMessageItem>,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    messageBodyTextStyle: TextStyle,
    agentStatusResolver: RaftAgentStatusResolver,
    newMessageMenuEnabled: Boolean,
    composerBoundaryVisible: Boolean,
    longPressMenuVisibleToken: String = ""
) {
    var openedMessageId by remember { mutableStateOf<String?>(null) }
    var compactMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    // task #228: report the compact menu's own state the way the legacy menu
    // already does. The sibling case (preference-off) has always gated on this
    // token; the compact path simply never passed one, which is why its tests
    // had to infer "the menu opened" from pixels instead of being told.
    //
    // messageId is part of the payload deliberately: the three compact cases
    // press three different messages, so a shared or replayed observation shows
    // up immediately as the same id appearing twice.
    var compactMenuMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(longPressMenuVisibleToken, compactMenuMessageId) {
        val openedFor = compactMenuMessageId
        if (longPressMenuVisibleToken.isNotBlank() && openedFor != null) {
            Utils.logToNative("$longPressMenuVisibleToken messageId=$openedFor")
        }
    }
    val renderedMessages =
        if (composerBoundaryVisible) {
            messages.take(THREAD_NEW_MESSAGE_MENU_TARGET_GROUP_SIZE)
        } else {
            messages
        }
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier.width(390.dp).fillMaxSize().padding(RaftAppSpacing.Md),
                verticalArrangement =
                    if (composerBoundaryVisible) Arrangement.Bottom else Arrangement.Top
            ) {
                items(renderedMessages.size, key = { index -> renderedMessages[index].id }) { index ->
                    val message = renderedMessages[index]
                    // No fixture avatar resolver here: the compact-menu oracle
                    // needs a near-monochrome frame (task #228 saturated floor).
                    ThreadMessageCard(
                        message = message,
                        replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message),
                        messageBodyTextStyle = messageBodyTextStyle,
                        agentStatusResolver = agentStatusResolver,
                        messageIndex = index,
                        compactConsecutiveMessages = true,
                        suppressRepeatedAuthor = index in 1..2 || index >= 4,
                        showReplyAction = true,
                        taskOpenEnabled = false,
                        taskStatusActionsEnabled = false,
                        showSaveAction = false,
                        showReactionAddAction = false,
                        onShowActions = { anchor ->
                            if (newMessageMenuEnabled) {
                                compactMenuAnchor = anchor
                                compactMenuMessageId = message.id
                            }
                        },
                        onOpenMember = {},
                        onOpenThread = { openedMessageId = it },
                        onOpenTask = {},
                        onOpenTaskStatusMenu = {},
                        onToggleReaction = {},
                        onOpenAttachment = {},
                        onOpenMarkdownTag = { _, _ -> },
                        onOpenMarkdownUrl = {},
                        outerPadding = RaftAppSpacing.Sm
                    )
                }
            }
            compactMenuAnchor?.let { anchor ->
                ThreadNewMessageMenu(
                    anchor = anchor,
                    availability =
                        ThreadNewMessageMenuActionAvailability(
                            reactions = true,
                            addReaction = true,
                            reply = true,
                            save = false,
                            translate = false
                        ),
                    onDismiss = {
                        compactMenuAnchor = null
                        compactMenuMessageId = null
                    },
                    onReact = {},
                    onCopy = {},
                    onReply = {},
                    onForward = {},
                    onSave = {},
                    onTranslate = {},
                    onSelect = {},
                    onMore = {
                        compactMenuAnchor = null
                        compactMenuMessageId = null
                    }
                )
            }
            if (openedMessageId != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(Color(0xFFFF00A8))
                )
            }
        }
        if (composerBoundaryVisible) {
            Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Default).background(RaftColor.BorderBlack))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Keep the visual oracle's bottom band equal to the
                        // Composer host bounds, so compact-menu pixels are
                        // never counted as Composer overlap.
                        .height(150.dp)
                        .padding(start = RaftAppSpacing.Sm, top = 12.dp, end = RaftAppSpacing.Sm, bottom = 14.dp)
            ) {
                ThreadComposerContent(
                    payload =
                        RaftThreadComposerPayload(
                            instanceId = "visual-new-message-menu-composer",
                            placeholder = "Message",
                            streamChannelId = "visual-new-message-menu-thread"
                        ),
                    onEvent = { _, _, _, _ -> },
                    contentPaddingTop = 0.dp,
                    contentPaddingBottom = 0.dp,
                    attachmentPreviewEnabled = false
                )
            }
        }
    }
}

/**
 * Visual-only runtime status authority for the isolated message-row capture route.
 *
 * Production message snapshots deliberately carry no volatile agent status. Keeping fixture
 * status here preserves deterministic status-dot coverage without reintroducing a snapshot
 * fallback into [RaftThreadMessageItem].
 */
private fun threadMessageVisualAgentStatusResolver(
    visualFixture: String
): RaftAgentStatusResolver {
    val resolvedFixture = visualFixture.ifBlank { THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT }
    val cindyStatus =
        when (resolvedFixture) {
            THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT,
            THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO,
            THREAD_MESSAGE_VISUAL_FIXTURE_SAVED,
            THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_LINK_CHILD,
            THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_MENTION_CHILD,
            // Shared fixture data (fixtureData.json) has Cindy `working`; the
            // React markdown specimens render her with that status badge.
            THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE,
            THREAD_MESSAGE_VISUAL_FIXTURE_MD_LINK_REF,
            THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_STATUS606,
            THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_ADJACENT,
            THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_CLARIFY,
            THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_TASK607,
            THREAD_MESSAGE_VISUAL_FIXTURE_MD_LATEST_RELEASE -> AgentStatus.Working
            THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT -> AgentStatus.Online
            else -> return EmptyRaftAgentStatusResolver
        }
    val cindyState =
        raftAgentStatusIndicatorState(
            status = cindyStatus,
            presence = cindyStatus.presence,
            external = false
        )
    return { memberId ->
        flowOf(cindyState.takeIf { memberId?.trim() == "agent-cindy" })
    }
}

@Composable
internal fun ThreadTaskStatusOwnerAnchorVisualProbe(message: RaftThreadMessageItem) {
    var result by remember(message.id) { mutableStateOf<Boolean?>(null) }
    Column(
        modifier =
            Modifier
                .width(390.dp)
                .background(RaftColor.SurfaceWhite)
                .padding(RaftAppSpacing.Md)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        when (result) {
                            true -> Color(0xFF10B981)
                            false -> Color(0xFFEF4444)
                            null -> Color(0xFFFFF7E6)
                        }
                    )
        )
        Spacer(Modifier.height(RaftAppSpacing.Md))
        ThreadMessageFooterMeta(
            message = message,
            taskOpenEnabled = false,
            taskStatusActionsEnabled = true,
            resolvedTaskAssigneeName = message.taskAssigneeName,
            alignEnd = false,
            onOpenTask = {},
            onShowActions = {},
            onOpenTaskStatusMenu = { anchor ->
                result = anchor.belongsTo(threadMessageMenuOwnerIdentity(message.id))
            }
        )
    }
}
/**
 * Gives the new-message-menu fixture's compact target group [count] replies.
 *
 * A tap opens any message's thread (stdrc, 2026-10-29); a capture case that wants
 * its thread to open on existing replies says so. A count of zero returns the
 * payload untouched, keeping every case that omits the parameter unchanged.
 */
internal fun RaftThreadScreenPayload.withNewMessageMenuTargetReplyCount(
    count: Int
): RaftThreadScreenPayload =
    if (count <= 0) {
        this
    } else {
        copy(
            messages =
                messages.mapIndexed { index, message ->
                    if (index < THREAD_NEW_MESSAGE_MENU_TARGET_GROUP_SIZE) {
                        message.copy(replyCount = count)
                    } else {
                        message
                    }
                }
        )
    }
internal data class ThreadInlineReplyTimestampBounds(
    val origin: Offset,
    val size: IntSize,
)
internal const val ThreadInlineReplyTimestampHitTargetDescription =
    "Inline reply timestamp hit target"

// web visualAgents: Cindy runs gpt-5-codex on codex, which the message header names.
private val ThreadShareSelectionVisualModelLabels =
    RaftMemberModelLabelResolver(
        currentLabel = { memberId -> threadShareSelectionVisualModelLabel(memberId) },
        labelFlow = { memberId -> kotlinx.coroutines.flow.flowOf(threadShareSelectionVisualModelLabel(memberId)) }
    )

private fun threadShareSelectionVisualModelLabel(memberId: String?): String? =
    if (memberId == "agent-cindy") build.raft.app.model.getModelLabel(runtimeId = "codex", modelId = "gpt-5-codex") else null
