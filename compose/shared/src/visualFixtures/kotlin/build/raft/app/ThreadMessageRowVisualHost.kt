package build.raft.app

import build.raft.app.components.SlockAnchoredMetaChip
import build.raft.app.components.SlockAttachmentPreviewCard
import build.raft.app.components.SlockAttachmentPreviewCardWidth
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockChipLoadingIndicator
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalSearchField
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockRaftUiBanner
import build.raft.app.components.SlockRaftUiBannerSize
import build.raft.app.components.SlockRaftUiBannerStatus
import build.raft.app.components.SlockRaftUiControlledMenu
import build.raft.app.components.SlockRaftUiMenuItem
import build.raft.app.components.SharedHomeSearchInput
import build.raft.app.components.SlockToolbarChip
import build.raft.app.base.Utils
import build.raft.app.base.putIfAbsent
import build.raft.app.base.bridgeModule
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
import build.raft.app.data.NetworkSlockTasksApi
import build.raft.app.data.NetworkSlockThreadApi
import build.raft.app.data.SlockThreadApiClient
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SlockEventBus
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.KeyValueThreadReadAnchorStore
import build.raft.app.data.ThreadReadAnchor
import build.raft.app.data.ThreadReadAnchorOwner
import build.raft.app.data.ThreadReadAnchorStore
import build.raft.app.data.ThreadUnreadAnchorMessage
import build.raft.app.data.projectThreadUnreadJump
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAgentsNetworkApi
import build.raft.app.data.KuiklyRealtimeEvents
import build.raft.app.data.formatAttachmentSizeLabel
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.SlockIntegrationsActionsPushApiClient
import build.raft.app.data.SlockIntegrationsActionsPushNetworkApi
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.data.isSlockHostVisible
import build.raft.app.data.realtime.RealtimeRoutePolicy
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.slockSharedPreferenceStore
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
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockSharedStoreRegistry
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
import build.raft.app.markdown.LocalSlockMessagePermalinkLabelResolver
import build.raft.app.markdown.LocalSlockMarkdownRenderConfig
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.markdown.LocalSlockMarkdownPlainCandidateSession
import build.raft.app.markdown.SlockMarkdownPlainCandidateSession
import build.raft.app.markdown.resolveThreadMarkdownSingleParagraphLightPathEnabled
import build.raft.app.markdown.threadMarkdownLightGateInputsOrNull
import build.raft.app.markdown.slockMarkdownProvidedValuesWith
import build.raft.app.markdown.slockTraceDiagnosticsEnabled
import build.raft.app.markdown.SlockMarkdownRenderConfig
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.prepareSlockMarkdownFullRendererBodies
import build.raft.app.markdown.preparedSlockMarkdownFullRendererContentOrNull
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
import build.raft.app.model.formatSlockTimestampEpochMillis
import build.raft.app.model.formatSlockTimestampLabel
import build.raft.app.network.sync.ThreadRepliesReadModel
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.model.slockEpochMillisUtcIso
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.resources.MR
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
import com.tencent.kuikly.compose.animation.core.animateFloatAsState
import com.tencent.kuikly.compose.animation.core.tween
import com.tencent.kuikly.compose.coil3.AsyncImagePainter
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.gestures.awaitEachGesture
import com.tencent.kuikly.compose.foundation.gestures.awaitFirstDown
import com.tencent.kuikly.compose.foundation.gestures.awaitLongPressOrCancellation
import com.tencent.kuikly.compose.foundation.gestures.scrollBy
import com.tencent.kuikly.compose.foundation.hoverable
import com.tencent.kuikly.compose.foundation.interaction.MutableInteractionSource
import com.tencent.kuikly.compose.foundation.interaction.collectIsHoveredAsState
import com.tencent.kuikly.compose.foundation.interaction.collectIsPressedAsState
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.ExperimentalLayoutApi
import com.tencent.kuikly.compose.foundation.layout.FlowRow
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.layout.wrapContentHeight
import com.tencent.kuikly.compose.foundation.layout.wrapContentWidth
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.foundation.text.SelectableText
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.extension.keyboardDismissModeInteractiveIOS
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.resources.imageResource
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.draw.drawWithContent
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.ImageBitmap
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.input.pointer.PointerInputScope
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.semantics.testTag
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.compose.ui.unit.toIntSize
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.PerformanceModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
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
@Composable
internal fun ThreadMessageVisualScreen() {
    ThreadReactionAtlasProvider {
        ThreadMessageVisualScreenContent()
    }
}
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
            slockMessageBodyTextStyle(params.optString("messageBodyFontSize"))
        }
    val longPressReadyToken = params.optString("longPressReadyToken")
    val longPressMenuVisibleToken = params.optString("longPressMenuVisibleToken")
    val singleTapOpensThreadEnabled = params.optBoolean("singleTapOpensThreadEnabled", false)
    val newMessageMenuComposerBoundaryVisible =
        params.optBoolean("newMessageMenuComposerBoundaryVisible", false)
    val newMessageMenuTargetReplyCount = params.optInt("newMessageMenuTargetReplyCount", 0)
    val newMessageMenuEnabled =
        remember(visualFixture) {
            visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO &&
                KeyValueChatLayoutPreferenceStore(slockSharedPreferenceStore())
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
        remember(payload.knownMarkdownDirectMessagePeerNames) {
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
        // task #415: measure unbounded (see the default fixture Column below).
        Column(
            modifier =
                Modifier
                    .width(390.dp)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .background(SlockColor.SurfaceWhite)
        ) {
            Column(modifier = Modifier.padding(SlockSpacing.Md)) {
                messages.forEach { selectedMessage ->
                    ThreadMessageCard(
                        message = selectedMessage,
                        messageBodyTextStyle = messageBodyTextStyle,
                        agentStatusResolver = agentStatusResolver,
                        replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(selectedMessage),
                        highlightedMentions = highlightedMentions,
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
                        outerPadding = 0.dp
                    )
                }
            }
            ThreadShareSelectionToolbar(
                selectedCount = messages.size,
                selectableCount = messages.size,
                onCancel = {},
                onForward = {},
                onExportImage = {},
                onCopyLinks = {},
                onCopyMarkdown = {}
            )
        }
        return
    }
    if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_PREVIEW) {
        Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
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
                    .background(SlockColor.SurfaceWhite)
                    .padding(SlockSpacing.Md)
        ) {
            messages.forEachIndexed { index, visualMessage ->
                if (index > 0) {
                    Spacer(Modifier.height(SlockSpacing.Sm))
                }
                ThreadMessageCard(
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
                    outerPadding = 0.dp
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
            singleTapOpensThreadEnabled = singleTapOpensThreadEnabled,
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
        mutableStateOf<SlockPopoverAnchor?>(null)
    }
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
            { target: SlockMessagePermalinkTarget ->
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
    val inheritedTimeContextProvider = LocalSlockTimeContextProvider.current
    // A fixture that pins `nowEpochMillis` owns its clock, so every relative timestamp under it
    // resolves against that fixed instant: deterministic across runs AND rendered as real digits.
    // Without a time context, resolveSlockRelativeTimeLabel() returns Unknown and the row shows the
    // literal "unknown time" — which is how a non-monospace inline reply timestamp (#2074) shipped
    // past the case built to guard that row. Fixtures that pin no clock keep the Unknown behavior.
    val visualTimeContextProvider =
        remember(payload, interactionProbe) {
            if (interactionProbe == "reply-timestamp-owner" || payload.nowEpochMillis != null) {
                SlockTimeContextProvider(payload.toSlockTimeContext())
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
        LocalSlockMessagePermalinkLabelResolver provides visualMessagePermalinkLabelResolver,
        LocalSlockMarkdownRenderConfig provides
            SlockMarkdownRenderConfig(renderMessagePermalinksAsTag = true),
        LocalSlockTimeContextProvider provides (visualTimeContextProvider ?: inheritedTimeContextProvider),
        LocalThreadInlineReplyTimestampReporter provides inlineReplyTimestampReporter,
    ) {
        Box {
            Column(
                modifier =
                    Modifier
                        .width(threadMessageVisualWidth(visualFixture))
                        // task #415: the visual route's window gives BOUNDED height
                        // constraints (unlike production's LazyColumn, whose items
                        // measure unbounded). TextStringRichNode clamps the natively
                        // measured text height to constraints.maxHeight, but the native
                        // view still draws the full layout — so a body taller than the
                        // window overlapped the siblings placed at the clamped height.
                        // Measure unbounded like production.
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .background(SlockColor.SurfaceWhite)
                        .padding(SlockSpacing.Md)
            ) {
            if (interactionProbe == "reply-timestamp-owner") {
                Spacer(modifier = Modifier.height(32.dp))
                SlockRelativeTimestampText(
                    value = "2026-07-24T10:05:00Z",
                    timeContext = payload.toSlockTimeContext(),
                    displayGroup = SlockTimestampDisplayGroup.Conversation,
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    onToggle = { ordinaryTimestampToggleCount += 1 },
                )
                if (ordinaryTimestampToggleCount > 0) {
                    Box(modifier = Modifier.size(12.dp).background(SlockColor.AccentCyan))
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
                                        SlockColor.BrandYellow
                                    }
                                )
                    )
                }
                ThreadMessageCard(
                    message = message,
                    messageBodyTextStyle = messageBodyTextStyle,
                    agentStatusResolver = agentStatusResolver,
                    replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(message),
                    highlightedMentions = highlightedMentions,
                    inlineReplyParticipantsById = payload.members.associateBy(KuiklyThreadMemberItem::id),
                    hasReplyDraft = visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_REPLIES,
                    alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                    messageAlignmentModeEnabled = payload.alignOwnMessagesRight,
                    showReplyAction = payload.showReplyActions,
                    singleTapOpensThreadEnabled = singleTapOpensThreadEnabled,
                    resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                        value = message.taskAssigneeName,
                        id = message.taskAssigneeId
                    ),
                    resolveMarkdownTagLabel =
                        remember(payload.members) {
                            { tag: ThreadMarkdownTag -> payload.resolveMarkdownTagDisplayLabel(tag) }
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
                    outerPadding = 0.dp
                )
                compactMenuAnchor?.let { anchor ->
                    ThreadNewMessageMenu(
                        anchor = anchor,
                        availability =
                            ThreadNewMessageMenuActionAvailability(
                                reactions = payload.showReactionActions,
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
                if (menuActions.isNotEmpty()) {
                    Spacer(Modifier.height(SlockSpacing.Sm))
                    ThreadMessageMenuChrome(
                        actions = menuActions,
                        showReactionActions = payload.showReactionActions,
                        reactionAtlasImage = LocalThreadReactionAtlasImage.current,
                        onReact = {},
                        modifier = Modifier
                    )
                }
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
    messages: List<KuiklyThreadMessageItem>,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    messageBodyTextStyle: TextStyle,
    agentStatusResolver: SlockAgentStatusResolver,
    newMessageMenuEnabled: Boolean,
    singleTapOpensThreadEnabled: Boolean,
    composerBoundaryVisible: Boolean,
    longPressMenuVisibleToken: String = ""
) {
    var openedMessageId by remember { mutableStateOf<String?>(null) }
    var compactMenuAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
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
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier.width(390.dp).fillMaxSize().padding(SlockSpacing.Md),
                verticalArrangement =
                    if (composerBoundaryVisible) Arrangement.Bottom else Arrangement.Top
            ) {
                items(renderedMessages.size, key = { index -> renderedMessages[index].id }) { index ->
                    val message = renderedMessages[index]
                    ThreadMessageCard(
                        message = message,
                        replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message),
                        messageBodyTextStyle = messageBodyTextStyle,
                        agentStatusResolver = agentStatusResolver,
                        messageIndex = index,
                        compactConsecutiveMessages = true,
                        suppressRepeatedAuthor = index in 1..2 || index >= 4,
                        showReplyAction = true,
                        singleTapOpensThreadEnabled = singleTapOpensThreadEnabled,
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
                        outerPadding = 0.dp
                    )
                }
            }
            compactMenuAnchor?.let { anchor ->
                ThreadNewMessageMenu(
                    anchor = anchor,
                    availability =
                        ThreadNewMessageMenuActionAvailability(
                            reactions = true,
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
            Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Keep the visual oracle's bottom band equal to the
                        // Composer host bounds, so compact-menu pixels are
                        // never counted as Composer overlap.
                        .height(150.dp)
                        .padding(start = SlockSpacing.Sm, top = 12.dp, end = SlockSpacing.Sm, bottom = 14.dp)
            ) {
                ThreadComposerContent(
                    payload =
                        KuiklyThreadComposerPayload(
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
 * fallback into [KuiklyThreadMessageItem].
 */
private fun threadMessageVisualAgentStatusResolver(
    visualFixture: String
): SlockAgentStatusResolver {
    val resolvedFixture = visualFixture.ifBlank { THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT }
    val cindyStatus =
        when (resolvedFixture) {
            THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT,
            THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO,
            THREAD_MESSAGE_VISUAL_FIXTURE_SAVED,
            THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_LINK_CHILD,
            THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_MENTION_CHILD -> AgentStatus.Working
            THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT -> AgentStatus.Online
            else -> return EmptySlockAgentStatusResolver
        }
    val cindyState =
        slockAgentStatusIndicatorState(
            status = cindyStatus,
            presence = cindyStatus.presence,
            external = false
        )
    return { memberId ->
        flowOf(cindyState.takeIf { memberId?.trim() == "agent-cindy" })
    }
}

@Composable
internal fun ThreadTaskStatusOwnerAnchorVisualProbe(message: KuiklyThreadMessageItem) {
    var result by remember(message.id) { mutableStateOf<Boolean?>(null) }
    Column(
        modifier =
            Modifier
                .width(390.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Md)
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
        Spacer(Modifier.height(SlockSpacing.Md))
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
 * Single-tap thread opening only applies to messages that already have replies,
 * so a capture case that exercises it has to say so. A count of zero returns the
 * payload untouched, keeping every case that omits the parameter unchanged.
 */
internal fun KuiklyThreadScreenPayload.withNewMessageMenuTargetReplyCount(
    count: Int
): KuiklyThreadScreenPayload =
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
