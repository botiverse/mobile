package build.raft.app

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
import build.raft.app.data.formatAttachmentSizeLabel
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
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
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
 * Visual fixture DATA for the thread message row capture route (task #189).
 *
 * Pure data: fixture payloads, their constants and self-contained builders. Wired into commonMain when
 * -PslockVisualFixtures=true and into commonTest when it is off, so fixture-contract unit tests keep
 * compiling on the ordinary unit gate while production artifacts stay free of fixture code.
 *
 * Regenerated from main at merge time, for the same reason recorded in ThreadMessageRowVisualHost.kt.
 */

internal const val THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT = "default"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO = "new_message_menu_demo"
/** Leading messages of the new-message-menu fixture that form its compact group. */
internal const val THREAD_NEW_MESSAGE_MENU_TARGET_GROUP_SIZE = 3
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_LINK_CHILD = "markdown_link_child"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_MENTION_CHILD = "markdown_mention_child"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_SAVED = "saved"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_DELETED_HUMAN = "deleted_human"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_IMAGE = "inline_image"

/** A tall phone-screenshot stand-in (9:20 SVG, yellow canvas, black block) for the inline image case. */
private const val ThreadMessageVisualInlineImageUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2090%20200%22%3E%3Crect%20width%3D%2290%22%20height%3D%22200%22%20fill%3D%22%23FDE047%22%2F%3E%3Crect%20x%3D%2212%22%20y%3D%2224%22%20width%3D%2266%22%20height%3D%2248%22%20fill%3D%22%23000000%22%2F%3E%3Crect%20x%3D%2212%22%20y%3D%2288%22%20width%3D%2266%22%20height%3D%228%22%20fill%3D%22%23F472B6%22%2F%3E%3C%2Fsvg%3E"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT = "rich_content"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_REPLIES = "inline_replies"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_SELECTION = "share_selection"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_PREVIEW = "share_preview"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE = "long_inline_code"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_LINK_REF = "md_link_ref"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP = "wide_markdown_wrap"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS = "permalink_chips"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS_NARROW = "permalink_chips_narrow"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_TASK524_MARKDOWN_VISIBLE = "task524_markdown_visible"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_GFM_TASK_LIST_SHORT = "gfm_task_list_short"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_ACTION_CARD_CATALOG = "action_card_catalog"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD = "approve_login_action_card"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD_EXECUTED = "approve_login_action_card_executed"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_SLICE1 = "md_wrap_slice1"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_CLARIFY = "md_wrap_clarify"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_TASK607 = "md_wrap_task607"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_ADJACENT = "md_wrap_adjacent"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_CJK_ADJACENT_URL = "cjk_adjacent_url"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_STATUS606 = "md_wrap_status606"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_BLOCKQUOTE = "md_blockquote"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_BLOCKQUOTE_TABLE = "md_blockquote_table"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_BLOCKQUOTE_LIST_TABLE = "md_blockquote_list_table"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_CODE_EMAIL = "md_code_email"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_CODE_URL = "md_code_url"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_CODE_BLOCK = "md_code_block"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_TABLE = "md_table"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_TABLE_TIGHT = "md_table_tight"
// Inline task refs coloured by task status (web TaskChip inline), mirroring
// artin's web screenshot: a table, a list and running text. The `_legacy`
// variant renders the same body without a task source (uniform chip).
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_TASK_STATUS_CHIPS = "md_task_status_chips"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_TASK_STATUS_CHIPS_LEGACY = "md_task_status_chips_legacy"

/** Task statuses the message-row host provides to Markdown; null = no task source. */
internal fun threadVisualFixtureTaskStatuses(fixture: String): Map<Int, TaskStatus>? =
    when (fixture) {
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_TASK_STATUS_CHIPS ->
            mapOf(
                92 to TaskStatus.InReview,
                321 to TaskStatus.InReview,
                209 to TaskStatus.Done,
                145 to TaskStatus.Done,
                224 to TaskStatus.InReview,
                12 to TaskStatus.Todo,
                34 to TaskStatus.InProgress,
                56 to TaskStatus.Closed,
            )
        else -> null
    }
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_ESCAPES = "md_escapes"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_LATEST_RELEASE = "md_latest_release"
// Mermaid diagram specimens (task #10, 2026-09-21): the native renderer landed
// in #2443 without a matrix case, so no rendered image existed anywhere.
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_MERMAID_FLOWCHART = "md_mermaid_flowchart"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_MERMAID_SEQUENCE = "md_mermaid_sequence"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_MD_MERMAID_INVALID = "md_mermaid_invalid"
/**
 * Outer padding of the message-row visual host. The React markdown specimens
 * render the MessageItem flush at (0,0) of a 390/260px column (`p-0`), so the
 * Android host must not inset those rows; the remaining cases sit inside a
 * `p-4` page on both providers.
 */
internal fun threadMessageVisualHostPadding(visualFixture: String): Dp =
    when (visualFixture) {
        THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_LINK_REF,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_SLICE1,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_STATUS606,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_ADJACENT,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_CLARIFY,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_TASK607,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_LATEST_RELEASE -> 0.dp
        else -> RaftAppSpacing.Md
    }

/**
 * Agent avatars from the shared visual fixture data
 * (packages/visual-testing/shared/fixtureData.json): every fixture agent wears
 * the `pixel:robot` avatar, which the React rows resolve from the member
 * profile. Fixture humans carry no avatar there, so they keep the fallback.
 */
private val ThreadVisualFixtureAgentAvatars: Map<String, String> =
    listOf(
        "agent-cindy",
        "agent-product-ux",
        "agent-android-dev-4",
        "agent-builder",
        "agent-kmp-expert",
        "agent-cc-wow"
    ).associateWith { "pixel:robot" }

internal val ThreadVisualMemberAvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { memberId -> ThreadVisualFixtureAgentAvatars[memberId] },
        avatarFlow = { memberId -> kotlinx.coroutines.flow.flowOf(ThreadVisualFixtureAgentAvatars[memberId]) }
    )

internal fun threadMessageVisualWidth(visualFixture: String): Dp =
    when (visualFixture) {
        THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP -> 920.dp
        THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS_NARROW -> 260.dp
        // Narrow 260dp column forces a channel/task chip to wrap to the next
        // line (the inline-box line-wrap bug these cases reproduce).
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_SLICE1 -> 260.dp
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_CLARIFY -> 260.dp
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_TASK607 -> 260.dp
        else -> 390.dp
    }
internal fun threadVisualFixture(
    fixture: String,
    instanceId: String
): RaftThreadScreenPayload {
    val resolvedInstanceId = instanceId.ifBlank { "visual-thread-$fixture" }
    return when (fixture) {
        THREAD_MESSAGE_VISUAL_FIXTURE_NEW_MESSAGE_MENU_DEMO -> {
            val base = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, resolvedInstanceId)
            val seed = requireNotNull(base.messages.firstOrNull())
            base.copy(
                alignOwnMessagesRight = false,
                compactConsecutiveMessages = true,
                messages =
                    List(20) { index ->
                        val inTargetGroup = index < THREAD_NEW_MESSAGE_MENU_TARGET_GROUP_SIZE
                        seed.copy(
                            id = "visual-new-message-menu-$index",
                            body =
                                if (inTargetGroup) {
                                    "Compact menu target ${index + 1}"
                                } else {
                                    "Scrollable overflow message ${index + 1}"
                                },
                            authorId = if (inTargetGroup) seed.authorId else "visual-overflow-author",
                            authorName = if (inTargetGroup) seed.authorName else "Overflow Author",
                            authorDescription = null,
                            reactions = emptyList(),
                            replyCount = 0,
                            unreadReplyCount = 0,
                            firstUnreadReplyMessageId = null,
                            threadChannelId = null
                        )
                    }
            )
        }
        THREAD_MESSAGE_VISUAL_FIXTURE_SAVED -> {
            val base = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, resolvedInstanceId)
            base.copy(
                showMessageSaveAction = true,
                messages =
                    base.messages.mapIndexed { index, message ->
                        if (index == 0) message.copy(isSaved = true) else message
                    }
            )
        }
        THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_LINK_CHILD -> {
            val base = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, resolvedInstanceId)
            base.copy(
                // The child-tap capture cases probe fixed display coordinates calibrated
                // against the full-width layout. Task #278's 82% width cap for the other
                // party's messages (alignment mode on) reflows the link span off those
                // coordinates, so these tap-behavior fixtures pin alignment mode off.
                alignOwnMessagesRight = false,
                messages =
                    base.messages.mapIndexed { index, message ->
                        if (index == 0) {
                            message.copy(
                                body =
                                    "Captured the Android visual artifact for " +
                                        "[external](https://example.com) and queued React parity."
                            )
                        } else {
                            message
                        }
                    }
            )
        }
        THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_MENTION_CHILD -> {
            val base = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, resolvedInstanceId)
            base.copy(
                // See MARKDOWN_LINK_CHILD: keep the mention span on the calibrated
                // full-width geometry for the fixed-coordinate tap probe.
                alignOwnMessagesRight = false,
                messages =
                    base.messages.mapIndexed { index, message ->
                        if (index == 0) {
                            message.copy(
                                body =
                                    "Captured the Android visual artifact for\n" +
                                        "@artin and queued React parity."
                            )
                        } else {
                            message
                        }
                    }
            )
        }
        THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Thread visual fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = true,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("product"),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-agent-reply",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            isOwn = false,
                            avatarLabel = "CI",
                            timestampLabel = "06/22 10:30",
                            messageType = "Chat",
                            body = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                            threadChannelId = "thread-msg-agent-reply",
                            replyCount = 5,
                            unreadReplyCount = 2,
                            firstUnreadReplyMessageId = "reply-agent-visual-2",
                            reactions =
                                listOf(
                                    RaftThreadReactionItem(emoji = "👍", count = 1, selected = false)
                                )
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_DELETED_HUMAN ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Deleted human author fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-deleted-human",
                            authorId = "visual-human-designer",
                            authorName = "Designer",
                            authorHandle = "designer",
                            authorKind = "user",
                            avatarLabel = "DE",
                            avatarWireValue = AvatarReference.Random("visual-human-designer").wireValue,
                            senderMembershipStatus = "deleted",
                            timestampLabel = "06/26 17:15",
                            messageType = "Chat",
                            body = "Deleted human author fixture keeps the avatar muted and the sender tag aligned with React.",
                            threadChannelId = "thread-msg-deleted-human",
                            reactions =
                                listOf(
                                    RaftThreadReactionItem(emoji = "👀", count = 1, selected = false)
                                )
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "visual-human-designer",
                            displayName = "Designer",
                            handle = "designer",
                            kind = "human",
                            avatarUrl = AvatarReference.Random("visual-human-designer").wireValue,
                            avatarLabel = "DE"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_IMAGE ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Inline image attachment fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-inline-image",
                            authorId = "visual-human-designer",
                            authorName = "Designer",
                            authorHandle = "designer",
                            authorKind = "user",
                            avatarLabel = "DE",
                            avatarWireValue = AvatarReference.Random("visual-human-designer").wireValue,
                            timestampLabel = "06/26 17:15",
                            messageType = "Chat",
                            body = "Inline image keeps its reserved box, rounded hairline frame and muted letterbox.",
                            threadChannelId = "thread-msg-inline-image",
                            attachments =
                                listOf(
                                    RaftThreadAttachmentItem(
                                        id = "att-inline-image",
                                        name = "screenshot.png",
                                        mimeType = "image/png",
                                        sizeLabel = "212 KB",
                                        thumbnailUrl = ThreadMessageVisualInlineImageUri,
                                        width = 1080,
                                        height = 2400
                                    )
                                )
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "visual-human-designer",
                            displayName = "Designer",
                            handle = "designer",
                            kind = "human",
                            avatarUrl = AvatarReference.Random("visual-human-designer").wireValue,
                            avatarLabel = "DE"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Thread rich message fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = true,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("design"),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_rich_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "06/22 23:30",
                            messageType = "Chat",
                            body =
                                "Rich fixture for #design: @artin please compare the Android capture " +
                                    "against task #222 before publishing. Keep https://raft.build/visual-testing " +
                                    "in the body so markdown links and long wrapping stay covered.",
                            threadChannelId = "thread-msg-rich-visual",
                            replyCount = 7,
                            unreadReplyCount = 2,
                            firstUnreadReplyMessageId = "reply_rich_2",
                            taskId = "task_222",
                            taskNumber = 222,
                            taskStatus = "in_progress",
                            taskAssigneeId = "agent-cindy",
                            taskAssigneeName = "Cindy",
                            reactions =
                                listOf(
                                    RaftThreadReactionItem(emoji = "👍", count = 3, selected = false)
                                ),
                            attachments =
                                listOf(
                                    RaftThreadAttachmentItem(
                                        id = "att-visual-spec",
                                        name = "visual-parity-notes.pdf",
                                        mimeType = "application/pdf",
                                        sizeLabel = "184 KB",
                                        commentCount = 3
                                    ),
                                    RaftThreadAttachmentItem(
                                        id = "att-thread-preview",
                                        name = "thread-screen-preview.html",
                                        mimeType = "text/html",
                                        sizeLabel = "32 KB"
                                    )
                                )
                        )
                    ),
                tasks =
                    listOf(
                        RaftThreadTaskItem(
                            id = "task_222",
                            title = "Thread screens + rich message row fixture",
                            status = "in_progress",
                            taskNumber = 222,
                            messageId = "msg_rich_visual",
                            assigneeId = "agent-cindy",
                            assigneeName = "Cindy",
                            updatedAtLabel = "06/22 23:30"
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_REPLIES ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#mobile",
                subtitle = "Inline replies visual fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-mobile",
                sourceThreadId = "channel-mobile",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                localOffsetMinutes = 480,
                nowEpochMillis = 1_784_888_400_000L,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-inline-replies",
                            authorId = "agent-mahua",
                            authorName = "Mahua",
                            authorKind = "agent",
                            avatarLabel = "MH",
                            // Production sets timestampLabel = createdAt (MessageParser.kt:445), a
                            // raw ISO instant that the header formats through the shared time
                            // context. A pre-formatted "07/24 18:20" is a string production never
                            // produces; it only survived here because no time context existed, so
                            // the header took the Literal passthrough. With this fixture's pinned
                            // clock the header now renders like production does: 18:20 at +08:00.
                            timestampLabel = "2026-07-24T10:20:00Z",
                            messageType = "Chat",
                            body = "Native/KMP inline reply previews are ready for review.",
                            threadChannelId = "thread-msg-inline-replies",
                            replyCount = 8,
                            unreadReplyCount = 2,
                            firstUnreadReplyMessageId = "reply-inline-7",
                            taskId = "task_73",
                            taskNumber = 73,
                            taskStatus = "in_progress",
                            taskAssigneeId = "agent-nathan",
                            taskAssigneeName = "Nathan",
                            reactions =
                                listOf(
                                    RaftThreadReactionItem(emoji = "✅", count = 2, selected = true),
                                    RaftThreadReactionItem(emoji = "👍", count = 3, selected = false),
                                ),
                            inlineReplies =
                                build.raft.app.network.sync.initialThreadRepliesReadModel(
                                    replies =
                                        listOf(
                                            build.raft.app.network.sync.ReplyPreview(
                                                messageId = "reply-inline-6",
                                                seq = 6,
                                                content = "Android capture matches the shared component.",
                                                senderId = "user-artin",
                                                senderType = "user",
                                                senderName = "artin",
                                                senderAvatarUrl = null,
                                                createdAt = "2026-07-24T10:16:00Z",
                                                senderDisplayName = "Artin",
                                            ),
                                            build.raft.app.network.sync.ReplyPreview(
                                                messageId = "reply-inline-7",
                                                seq = 7,
                                                content = "Display name and avatar resolve from channel profiles.",
                                                senderId = "agent-cindy",
                                                senderType = "agent",
                                                senderName = "Cindy",
                                                senderAvatarUrl = null,
                                                createdAt = "2026-07-24T10:18:00Z",
                                                senderDisplayName = "Cindy",
                                            ),
                                            build.raft.app.network.sync.ReplyPreview(
                                                messageId = "reply-inline-8",
                                                seq = 8,
                                                content =
                                                    """
                                                    New validation
                                                    Opening dispatches canonical read state.
                                                    """.trimIndent(),
                                                senderId = "agent-nathan",
                                                senderType = "agent",
                                                senderName = "Nathan",
                                                senderAvatarUrl = null,
                                                createdAt = "2026-07-24T10:20:00Z",
                                                senderDisplayName = "Nathan",
                                            ),
                                        ),
                                    replyCount = 8,
                                    limit = build.raft.app.network.sync.InlineThreadReplyPreviewLimit,
                                ),
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-mahua",
                            displayName = "Mahua",
                            handle = "Mahua",
                            kind = "agent",
                            avatarLabel = "MH",
                        ),
                        RaftThreadMemberItem(
                            id = "user-artin",
                            displayName = "Artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR",
                        ),
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI",
                        ),
                        RaftThreadMemberItem(
                            id = "agent-nathan",
                            displayName = "Nathan",
                            handle = "Nathan",
                            kind = "agent",
                            avatarLabel = "NA",
                        ),
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false,
                    ),
            )
        // web ThreadMessageShareSelectionVisualCaseView (SHARE_SELECTION_MESSAGES): Cindy's
        // threadDefault message with a 👍 2 the owner joined, then the owner's composerSeed.
        // The web case's channel store has no #product, so the ref stays plain text.
        THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_SELECTION ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Share selected messages fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                showReactionActions = true,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-share-selection-1",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "06/22 10:30",
                            messageType = "Chat",
                            body = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                            reactions =
                                listOf(
                                    RaftThreadReactionItem(
                                        emoji = "👍",
                                        count = 2,
                                        selected = true,
                                        reactorIds = listOf("visual-user"),
                                        reactorNames = listOf("artin")
                                    )
                                )
                        ),
                        RaftThreadMessageItem(
                            id = "msg-share-selection-2",
                            authorId = "visual-user",
                            authorName = "artin",
                            authorDescription = "Owner",
                            authorKind = "human",
                            avatarLabel = "AR",
                            timestampLabel = "06/22 10:35",
                            messageType = "Chat",
                            body = "Review the Android composer crop before release."
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_PREVIEW ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Share selected messages fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-share-visual-a",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "06/27 09:20",
                            messageType = "Chat",
                            body = "Selected the two discussion points that should become a summarized handoff message."
                        ),
                        RaftThreadMessageItem(
                            id = "msg-share-visual-b",
                            authorId = "visual-user",
                            authorName = "artin",
                            authorKind = "human",
                            avatarLabel = "AR",
                            timestampLabel = "06/27 09:24",
                            messageType = "Chat",
                            body = "Generate a message from these selected items, but keep it editable in the composer before sending."
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Full markdown + long inline code fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = true,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("markdown专修"),
                knownMarkdownMentionNames = listOf("artin"),
                knownMarkdownTaskNumbers = listOf(42, 43),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_long_inline_code_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "06/25 05:55",
                            messageType = "Chat",
                            body =
                                "Plain text baseline before bold.\n\n" +
                                    "Normal: regular markdown bold sample abcdefg 123.\n\n" +
                                    "**Bold: regular markdown bold sample abcdefg 123.**\n\n" +
                                    "Normal CN: 中文粗体 对照样本 123.\n\n" +
                                    "**粗体 Bold: 中文粗体 markdown bold sample 123.**\n\n" +
                                    "Tag chip coverage: mention @artin, channel #markdown专修, " +
                                    "thread #markdown专修:692d9e88, tasks task #42 and task #43.\n\n" +
                                    "Ordinary mention regression: @KMP-专家 #520 Phase 5: delete remaining ConversationSummary launch compatibility helpers.\n\n" +
                                    "Short code should stay compact: `CODE_SPAN`, `raft.build.markdown.inlineCode`, `go test ./...`, and `git commit -s`.\n\n" +
                                    "Long path should wrap inline without ellipsis: `reply/channel-markdown/msg-inline-visual/composer-draft-scroll-realtime-read-receipt/owner-key-consumes-thread-route-identity-only/without-source-thread-fallback/max-width-wrap-proof-alpha-beta-gamma-delta-epsilon-zeta-eta-theta-iota-kappa-lambda-mu`.\n\n" +
                                    "Command should wrap with the same neutral fill and no frame: `./gradlew :shared:testDebugUnitTest --tests build.raft.app.markdown.SlockMarkdownTest --tests build.raft.app.visual.KmpVisualScreenshotCaptureTest.captureThreadMessageRowLongInlineCode`.\n\n" +
                                    "> Quote block keeps normal markdown flow beside inline `code` and Slock tags.\n\n" +
                                    "- Link: https://raft.build/visual-testing\n" +
                                    "- File: `compose/shared/src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt`\n\n" +
                                    "Task-list parity in the real message consumer:\n" +
                                    "- [ ] Pending item wraps across the message column and keeps typed task #42 independent from its read-only checkbox.\n" +
                                    "- [x] Completed lower-case marker\n" +
                                    "  - [X] Completed upper-case nested marker\n" +
                                    "1. [ ] Ordered pending item\n\n" +
                                    "[x] Bare marker stays ordinary text.",
                            threadChannelId = "thread-msg-long-inline-code-visual",
                            replyCount = 4,
                            unreadReplyCount = 1,
                            firstUnreadReplyMessageId = "reply_long_inline_code_1",
                            taskId = "task_42",
                            taskNumber = 42,
                            taskStatus = "in_review",
                            taskAssigneeId = "agent-cindy",
                            taskAssigneeName = "Cindy",
                            reactions =
                                listOf(
                                    RaftThreadReactionItem(emoji = "👍", count = 3, selected = false),
                                    RaftThreadReactionItem(emoji = "✅", count = 1, selected = true)
                                ),
                            attachments =
                                listOf(
                                    RaftThreadAttachmentItem(
                                        id = "att-markdown-fixture",
                                        name = "standard-markdown-fixture.md",
                                        mimeType = "text/markdown",
                                        sizeLabel = "6 KB"
                                    )
                                )
                        )
                    ),
                tasks =
                    listOf(
                        RaftThreadTaskItem(
                            id = "task_42",
                            title = "Markdown inline code uses standard CODE_SPAN renderer",
                            status = "in_review",
                            taskNumber = 42,
                            messageId = "msg_long_inline_code_visual",
                            assigneeId = "agent-cindy",
                            assigneeName = "Cindy",
                            updatedAtLabel = "06/25 05:55"
                        ),
                        RaftThreadTaskItem(
                            id = "task_43",
                            title = "Tighten markdown tag chip padding",
                            status = "in_review",
                            taskNumber = 43,
                            messageId = "msg_long_inline_code_visual",
                            assigneeId = "agent-cindy",
                            assigneeName = "Cindy",
                            updatedAtLabel = "07/02 22:46"
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        ),
                        RaftThreadMemberItem(
                            id = "agent-kmp-expert",
                            displayName = "KMP-专家",
                            handle = "KMP-专家",
                            kind = "agent",
                            avatarLabel = "KM"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_LINK_REF ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#visual-testing",
                subtitle = "Markdown link label owns nested refs (task #517)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "channel-visual-testing",
                sourceThreadId = "channel-visual-testing",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("visual-testing"),
                knownMarkdownMentionNames = listOf("artin"),
                knownMarkdownTaskNumbers = listOf(1487),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_link_ref_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/25 08:20",
                            messageType = "Chat",
                            // The Raft dialect contract this case guards: refs inside a
                            // Markdown link label/destination stay part of that one blue
                            // external link; only standalone refs outside links chip.
                            body =
                                "Review [#1487 feat(search): pin entities from long-press and channel settings](https://github.com/botiverse/mobile/pull/1487) then follow task #1487.\n\n" +
                                    "Channel ref in label: [notes in #visual-testing](https://raft.build/visual-testing) and outside #visual-testing.\n\n" +
                                    "Mention in label: [ping @artin](https://raft.build/mentions) and outside @artin.",
                            threadChannelId = "thread-msg-md-link-ref-visual",
                            replyCount = 0,
                            unreadReplyCount = 0,
                            taskId = "task_1487",
                            taskNumber = 1487,
                            taskStatus = "in_review",
                            taskAssigneeId = "agent-cindy",
                            taskAssigneeName = "Cindy"
                        )
                    ),
                tasks =
                    listOf(
                        RaftThreadTaskItem(
                            id = "task_1487",
                            title = "feat(search): pin entities from long-press and channel settings",
                            status = "in_review",
                            taskNumber = 1487,
                            messageId = "msg_md_link_ref_visual",
                            assigneeId = "agent-cindy",
                            assigneeName = "Cindy",
                            updatedAtLabel = "07/25 08:20"
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_SLICE1 ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#demo-visuals",
                subtitle = "Narrow markdown wrap fixture (slice-1)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                // Keep all markdown entities synthetic so this public fixture never exposes
                // real collaborators, channels, task IDs, or release metadata.
                knownMarkdownChannelNames = listOf("demo-visual-testing"),
                knownMarkdownMentionNames = listOf("DemoUser"),
                knownMarkdownTaskNumbers = listOf(101),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_wrap_slice1_visual",
                            authorId = "visual-bot",
                            authorName = "Visual Bot",
                            authorDescription = "Keeps demo fixtures stable.",
                            authorKind = "agent",
                            avatarLabel = "VB",
                            timestampLabel = "07/13 05:55",
                            messageType = "Chat",
                            body =
                                "**Synthetic slice is ready** — this message is generated for visual testing only.\n\n" +
                                    "- The synthetic CI job **passed** after a repeat check (`demo-retry-check`), and Android/OHOS host/shared checks are all green.\n" +
                                    "- **Synthetic merge record**: an automated test identity completed the sample merge; no production release is involved.\n" +
                                    "- Feature flag `demo_sync_v1` **stays off by default**; enabling it records only local demo state and remains invisible to users.\n" +
                                    "- The **data-flow sample** stays in the fixture for cross-platform rendering comparison in #demo-visual-testing.\n\n" +
                                    "**Sample data is ready.** Next, @DemoUser can compare the synthetic envelope in task #101 and continue the cross-platform check without importing real collaboration content."
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        ),
                        RaftThreadMemberItem(
                            id = "human-mahua",
                            displayName = "Mahua",
                            handle = "Mahua",
                            kind = "human",
                            avatarLabel = "MA"
                        ),
                        RaftThreadMemberItem(
                            id = "human-wangxiaoming",
                            displayName = "王小明",
                            handle = "王小明",
                            kind = "human",
                            avatarLabel = "王"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_CLARIFY ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#conversation-flow",
                subtitle = "Narrow markdown wrap fixture (clarify)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownMentionNames = listOf("Android-Developer-4"),
                knownMarkdownTaskNumbers = listOf(31, 521),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_wrap_clarify_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/13 05:56",
                            messageType = "Chat",
                            body =
                                "另外帮一个澄清避免混淆：#31（read-state→Activity，我的）和 @Android-Developer-4 的 **task #521**（`message:new`→Activity 即时新增，artin 原话\"新消息来了 activity 没刷新\"）是两个不同 gap，不是重复——Codex 早前建议的\"#521 判重关闭\"是把两者混了，AD4 已更正。#31 复用 versioned read-state fact，#521 是 SharedActivityStore 缺 message:new realtime，两条链路各修各的。"
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-android-dev-4",
                            displayName = "Android-Developer-4",
                            handle = "Android-Developer-4",
                            kind = "agent",
                            avatarLabel = "AD"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_STATUS606 ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Agent-status markdown specimen (thread-ref + hex codes)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("对话流专修"),
                knownMarkdownMentionNames = listOf("artin"),
                knownMarkdownTaskNumbers = listOf(606),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_wrap_status606_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/13 11:25",
                            messageType = "Chat",
                            body =
                                "Task #606 新真机基线已就绪：PR #785 exact `0e5a82b02` Alpha 已附在 #对话流专修:661a12bf，SHA-256 `db01c5e5b91f3696bfda64edde2fc31b3a40ffe9b508813d651a6de735e8a85b`。这是 process-owned decoded auth-session snapshot 根修包，不是日志抑制包；`8a5e542f4` 作废。\n\n等待 @artin 验普通导航/滚动及 `#/@` suggestion。若仍卡，以新 Hands 对比 auth durable cold-read 次数和 traversal/layout/draw；task #606 保持 In Progress，PR 不合。"
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_CODE_BLOCK ->
            // Fenced code block specimen (task #66): pins the block chrome and
            // the persistent top-right copy affordance.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Fenced code block + copy affordance specimen (task #66)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_code_block_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/16 11:40",
                            messageType = "Chat",
                            body =
                                "Fenced block below must show the copy icon top-right.\n\n" +
                                    "```kotlin\n" +
                                    "fun greet(name: String): String {\n" +
                                    "    return \"Hello, ${'$'}name!\"\n" +
                                    "}\n" +
                                    "```\n\n" +
                                    "Trailing paragraph closes the specimen."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_MERMAID_FLOWCHART ->
            // Mermaid flowchart: the fenced block must become a drawn diagram
            // with the zoom controls, not the source text.
            threadMermaidVisualFixture(
                resolvedInstanceId = resolvedInstanceId,
                messageId = "msg_md_mermaid_flowchart_visual",
                subtitle = "Mermaid flowchart specimen (task #10)",
                intro = "Flowchart below must render as a diagram, not as code.",
                // Kept to the 0.1.2 flowchart grammar on purpose: `A[x] --> B[y]`
                // only (no edge labels, no {} decision nodes), so this case
                // measures rendering, not parser coverage.
                mermaid =
                    "graph TD\n" +
                        "  A[Start] --> B[Parse]\n" +
                        "  B --> C[Layout]\n" +
                        "  C --> D[Draw on canvas]\n"
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_MERMAID_SEQUENCE ->
            // Mermaid sequence diagram: lifelines, arrows and labels.
            threadMermaidVisualFixture(
                resolvedInstanceId = resolvedInstanceId,
                messageId = "msg_md_mermaid_sequence_visual",
                subtitle = "Mermaid sequence specimen (task #10)",
                intro = "Sequence diagram below must render with lifelines and arrows.",
                mermaid =
                    "sequenceDiagram\n" +
                        "  Alice->>Bob: Hello\n" +
                        "  Bob-->>Alice: Hi\n"
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_MERMAID_INVALID ->
            // Unparseable mermaid: the contract is a graceful fallback to the
            // ordinary fenced code block with its copy affordance intact.
            threadMermaidVisualFixture(
                resolvedInstanceId = resolvedInstanceId,
                messageId = "msg_md_mermaid_invalid_visual",
                subtitle = "Mermaid invalid-source fallback specimen (task #10)",
                intro = "Invalid mermaid below must fall back to a plain code block.",
                mermaid =
                    "graph INVALID_SYNTAX\n" +
                        "  ??? --- !!!\n"
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_TASK_STATUS_CHIPS,
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_TASK_STATUS_CHIPS_LEGACY ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Task status chips specimen",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_task_status_chips_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "10/20 09:12",
                            messageType = "Chat",
                            body =
                                "### 二、in_review，只差最后一步\n\n" +
                                    "| # | 差什么 |\n" +
                                    "|---|---|\n" +
                                    "| #92 | 附件 |\n" +
                                    "| #321 | Thread |\n" +
                                    "| #209 / #145 | 各自 |\n\n" +
                                    "### 三、可以关掉的\n\n" +
                                    "- #224（iOS thread 不实时刷新）\n" +
                                    "- #145（Native onboarding 独立 review）\n" +
                                    "- #209（GFM task-list checkbox 视觉证据）\n\n" +
                                    "Todo #12, in progress #34, closed #56, unknown #999."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_TABLE ->
            // task #497: GFM tables reportedly don't render on Android. The
            // specimen mirrors the failing real message shape (header row,
            // separator, body rows with bold/checkmark cells) plus paragraphs
            // around it, so the capture shows exactly what a table body
            // becomes — a rendered grid, raw pipe text, or nothing.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "GFM table specimen (task #497)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_table_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/16 11:58",
                            messageType = "Chat",
                            body =
                                "Hard assertions:\n\n" +
                                    "| Assertion | Result | Evidence |\n" +
                                    "|-----------|--------|----------|\n" +
                                    "| Submit indicator | PASS | \"Submitted ff504382\" |\n" +
                                    "| No error message | PASS | zero error or retry |\n" +
                                    "| **No crash** | PASS | 28 turns stable |\n\n" +
                                    "Trailing paragraph closes the specimen."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_TABLE_TIGHT ->
            // task #497 hypothesis specimen: the failing real message has the
            // table DIRECTLY after a text line (no blank line). Spec-GFM
            // tables cannot interrupt a paragraph, so the jetbrains parser
            // may lazy-continue the paragraph and render raw pipes — while
            // web's renderer shows a table. This body mirrors that shape.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "GFM table tight-follow specimen (task #497)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_table_tight_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/16 12:15",
                            messageType = "Chat",
                            body =
                                "**Hard assertions**：\n" +
                                    "| Assertion | Result | Evidence |\n" +
                                    "|-----------|--------|----------|\n" +
                                    "| Submit indicator | PASS | \"Submitted ff504382\" |\n" +
                                    "| No error message | PASS | zero error or retry |\n\n" +
                                    "Trailing paragraph closes the specimen."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_ESCAPES ->
            // task #1048 / patrol renderer-coverage: escaped backticks and
            // unpaired tildes were silently dropped by the annotator. The
            // specimen pins all three shapes so before/after captures show
            // exactly which characters survive.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Escaped-backtick / tilde specimen (patrol #1048)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_escapes_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/16 15:05",
                            messageType = "Chat",
                            body =
                                "Type \\` to start a code span.\n\n" +
                                    "The build takes ~5 minutes on a warm cache.\n\n" +
                                    "Keep ~~struck words~~ struck, tildes intact."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_CODE_EMAIL ->
            // task #493: inline code containing an angle-bracket email
            // (`artin <artin@example.test>`) is a classic markdown trap — parsers
            // that autolink/HTML-strip inside code spans swallow the
            // "<artin@example.test>" part. The specimen pins all three shapes so the
            // capture proves: code keeps it literal, the bare form autolinks,
            // plain text stays text.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Code-span angle-bracket email specimen (task #493)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_code_email_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/15 18:05",
                            messageType = "Chat",
                            body =
                                "Inline code keeps the email literal: `artin <artin@example.test>` end.\n\n" +
                                    "Bare autolink form: <artin@example.test> end.\n\n" +
                                    "Plain text form: artin@example.test end."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_CODE_URL ->
            // task #513: exact XiShi message reported by artin. Every URL-like
            // payload wrapped in backticks must keep inline-code chrome and
            // must not inherit bare-URL link color/underline semantics.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#stamp",
                subtitle = "Exact backticked HTTP URL specimen (task #513)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-stamp",
                sourceThreadId = "channel-stamp",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_code_url_visual",
                            authorId = "agent-xishi",
                            authorName = "XiShi",
                            authorDescription = "视觉测试采集 harness 与 CI 排查。",
                            authorKind = "agent",
                            avatarLabel = "西",
                            timestampLabel = "07/22 04:32",
                            messageType = "Chat",
                            body =
                                "对，模板仓库很可能是同源问题，应该一起扫，不只修单个实例。\n\n" +
                                    "我刚对源码确认了根因：它用 `new URL(\"/login/callback\", c.req.url)` 生成 callback。手机这次入站是 `http://internal/login`，所以产出 `return_to=http://internal/login/callback`。\n\n" +
                                    "正确的公共模板合同应是：\n" +
                                    "- callback 基于显式可信 `PUBLIC_ORIGIN=https://...`（或经过严格 allowlist 的 forwarded origin），绝不直接信任入站 scheme/Host；\n" +
                                    "- HTTP 入口先 308 HTTPS 并加 HSTS，但 OAuth 正确性不能依赖浏览器自动升级；\n" +
                                    "- production 禁止 HTTP callback，localhost 开发例外要显式限定；\n" +
                                    "- 回归测试从 `http://internal/`、反代 HTTP、恶意 Host 三种入站验证，最终 `return_to` 始终等于注册的外部 HTTPS callback。\n\n" +
                                    "临时可直接开 <https://gateway.example.test/login>。@reviewer 已在查模板同类代码；应把 hotfix 落实例，同时把模板修掉，避免以后每个新 app 复制这个问题。"
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_LATEST_RELEASE ->
            // task #502: real mixed-markdown status message requested by artin.
            // Pins a leading horizontal rule, Chinese prose, bold emphasis,
            // inline-code routes containing angle-bracket placeholders, and a
            // plain PR reference in one message across Android/iOS.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Latest release landing markdown specimen (task #502)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_latest_release_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/19 13:03",
                            messageType = "Chat",
                            body =
                                "---\n\n" +
                                    "更新：发布落地页改为**不需要先创建 share token 的固定最新版本页面**，" +
                                    "路由 `/apps/<app>/latest` 始终展示当前最新版并提供下载；`/share/<token>` 保持为一次性分享页，不再做 rolling。\n\n" +
                                    "相关改动见 PR #318，复用现有 share 页的版本信息/下载体验。"
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_BLOCKQUOTE ->
            // Blockquote geometry specimen (task #62): paragraphs above and
            // below the quote pin the rail's expected bounds — the 2dp rail
            // must span exactly the two quoted lines, no overshoot.
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Blockquote rail geometry specimen (task #62)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_blockquote_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/15 11:20",
                            messageType = "Chat",
                            body =
                                "Paragraph above the quote marks the rail's upper bound.\n\n" +
                                    "> Quoted line one keeps **bold** and `code` inline.\n" +
                                    "> Quoted line two — the rail must end with this line.\n\n" +
                                    "Paragraph below the quote marks the rail's lower bound."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_BLOCKQUOTE_TABLE ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Blockquote table intrinsic-measurement regression",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_blockquote_table_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/28 19:15",
                            messageType = "Chat",
                            body =
                                "Paragraph above the quoted table.\n\n" +
                                    "> | Check | Result |\n" +
                                    "> | --- | --- |\n" +
                                    "> | Source | Ready |\n" +
                                    "> | Android | Pending |\n\n" +
                                    "Paragraph below the quoted table."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_BLOCKQUOTE_LIST_TABLE ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Nested table intrinsic-measurement regression",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_blockquote_list_table_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/28 20:40",
                            messageType = "Chat",
                            body =
                                "Paragraph above the quoted list.\n\n" +
                                    "> - Release status\n" +
                                    ">   \n" +
                                    ">   | Check | Result |\n" +
                                    ">   | --- | --- |\n" +
                                    ">   | Android | Pending |\n\n" +
                                    "Paragraph below the quoted list."
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_ADJACENT ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Adjacent-chip acceptance fixture (task #60)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("对话流专修"),
                knownMarkdownTaskNumbers = listOf(31, 521, 607),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_wrap_adjacent_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/13 05:56",
                            messageType = "Chat",
                            body =
                                "对照：#31 / #521 两块同行。\n\n紧凑：#31/#521。\n\n尾宽对照：task #607 与 #对话流专修。"
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_CJK_ADJACENT_URL ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "CJK-adjacent bare URL fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_cjk_adjacent_url_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps markdown link targets aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/22 06:00",
                            messageType = "Chat",
                            body =
                                "@alice 情绪板已经准备完成，有空可以看下" +
                                    "https://design.example.test/file/brand-exploration?node-id=372-1396&t=preview"
                        )
                    ),
                members = emptyList(),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_MD_WRAP_TASK607 ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#对话流专修",
                subtitle = "Narrow markdown wrap fixture (task607)",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("对话流专修"),
                knownMarkdownMentionNames = listOf("artin", "Android-Developer-2"),
                knownMarkdownTaskNumbers = listOf(607),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_md_wrap_task607_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/13 05:57",
                            messageType = "Chat",
                            body =
                                "@artin 建好了：**task #607**（#对话流专修，我已 claim）——slice-1 那个 mapper→network.sync 解耦 follow-up。scope + @Android-Developer-2 的 A/B 设计选择（接受耦合 vs observer-routed 重构）我贴在 task thread 了。AD2 定 A 我 close 成 by-design，定 B 我实现。V2 那条独立、等赵定信封。"
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        ),
                        RaftThreadMemberItem(
                            id = "agent-android-dev-2",
                            displayName = "Android-Developer-2",
                            handle = "Android-Developer-2",
                            kind = "agent",
                            avatarLabel = "AD"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Wide markdown wrap fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "channel-markdown-wide",
                sourceThreadId = "channel-markdown-wide",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("markdown专修"),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_wide_markdown_wrap_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Tracking Markdown width regressions.",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "07/06 01:40",
                            messageType = "Chat",
                            body =
                                "Wide-row regression: this sentence should keep flowing across the available row width before it wraps, " +
                                    "including tag chips #markdown专修, @artin, task #55, and inline code `RichTextMeasureWidthShouldUseTheActualThreadRowContentWidth` without leaving a large blank area on the right.\n\n" +
                                    "Second paragraph keeps enough plain text after the chips so screenshots reveal whether Markdown/RichText was still measured with the old narrow 680dp cap.",
                            threadChannelId = "thread-msg-wide-markdown-wrap-visual"
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        RaftThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_TASK524_MARKDOWN_VISIBLE ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#首页专修",
                subtitle = "Task #524 markdown visibility fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "thread-task524-visible",
                sourceThreadId = "thread-task524-visible",
                parentChannelId = "channel-home",
                parentMessageId = "parent-task524-visible",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("首页专修"),
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg_task524_markdown_visible",
                            authorId = "agent-cc-wow",
                            authorName = "CC-Wow",
                            authorKind = "agent",
                            avatarLabel = "CW",
                            timestampLabel = "07/03 13:24",
                            messageType = "Chat",
                            body =
                                "Review summary before the list must remain visible.\n\n" +
                                    "- Bullet item text must render after the marker.\n" +
                                    "- Second bullet keeps enough remaining row width for rich text.\n\n" +
                                    "Numbered section:\n\n" +
                                    "1. First numbered item text must not disappear.\n" +
                                    "2. Second numbered item with `inline code` must still render.\n" +
                                    "3. Third numbered item keeps Chinese text visible: 这里的正文不能只剩编号。\n" +
                                    "4. Fourth numbered item wraps to the next line without leaving marker-only rows.\n" +
                                    "5. Fifth numbered item closes the task #524 regression shape.",
                            threadChannelId = "thread-msg-task524-markdown-visible"
                        )
                    ),
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent-cc-wow",
                            displayName = "CC-Wow",
                            handle = "CC-Wow",
                            kind = "agent",
                            avatarLabel = "CW"
                        )
                    ),
                showComposer = false,
                joined = true,
                composer =
                    RaftThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_GFM_TASK_LIST_SHORT ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown-task-list",
                subtitle = "GFM task-list visual fixture",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "thread-gfm-task-list-short",
                sourceThreadId = "thread-gfm-task-list-short",
                parentChannelId = "channel-markdown",
                parentMessageId = "parent-gfm-task-list-short",
                selectedTab = "chat",
                showModeTabs = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("markdown-task-list"),
                messages = listOf(RaftThreadMessageItem(
                    id = "msg_gfm_task_list_short",
                    authorId = "agent-gfm",
                    authorName = "GFM Bot",
                    authorKind = "agent",
                    avatarLabel = "GF",
                    timestampLabel = "08/20 13:30",
                    messageType = "Chat",
                    body =
                        "Task-list parity: task #210\n\n" +
                            "- [x] Completed item wraps across the message column while its checkbox stays aligned with the first text line.\n" +
                            "- [ ] Pending item\n" +
                            "  - [X] Nested completed item",
                    threadChannelId = "thread-msg-gfm-task-list-short",
                    taskId = "task_210",
                    taskNumber = 210,
                    taskStatus = "in_review",
                    taskAssigneeId = "agent-gfm",
                    taskAssigneeName = "GFM Bot"
                )),
                members = listOf(RaftThreadMemberItem(
                    id = "agent-gfm", displayName = "GFM Bot", handle = "GFM Bot", kind = "agent", avatarLabel = "GF"
                )),
                showComposer = false,
                joined = true,
                composer = RaftThreadComposerPayload(instanceId = resolvedInstanceId, enabled = false)
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS,
        THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS_NARROW ->
            threadMessagePermalinkVisualPayload(resolvedInstanceId)
        THREAD_MESSAGE_VISUAL_FIXTURE_ACTION_CARD_CATALOG ->
            threadActionCardVisualFixture(resolvedInstanceId, threadActionCardCatalogMessages())
        THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD ->
            threadActionCardVisualFixture(resolvedInstanceId, approveLoginActionCardVisualMessages(executed = false))
        THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD_EXECUTED ->
            threadActionCardVisualFixture(resolvedInstanceId, approveLoginActionCardVisualMessages(executed = true))
        else ->
            RaftThreadScreenPayload(
                instanceId = resolvedInstanceId,
                status = "error",
                errorCode = "visual_fixture_unknown"
            )
    }
}
internal fun threadMessagePermalinkVisualPayload(instanceId: String): RaftThreadScreenPayload =
    RaftThreadScreenPayload(
        instanceId = instanceId,
        title = "#permalink",
        subtitle = "Generic inline-box permalink fixture",
        status = "content",
        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
        streamChannelId = "channel-permalink",
        sourceThreadId = "channel-permalink",
        selectedTab = "chat",
        showModeTabs = false,
        alignOwnMessagesRight = false,
        showMessageSaveAction = false,
        showTaskOpenActions = false,
        showTaskStatusActions = false,
        messages =
            listOf(
                RaftThreadMessageItem(
                    id = "msg_permalink_visual",
                    authorId = "agent-kmp-expert",
                    authorName = "KMP-专家",
                    authorDescription = "Validates generic inline boxes across renderers.",
                    authorKind = "agent",
                    avatarLabel = "KM",
                    timestampLabel = "07/11 23:55",
                    messageType = "Chat",
                    body =
                        "Single line: https://app.raft.build/s/mobile/channel/channel-proj-server-management?msg=msg-single\n\n" +
                            "Line-end collision keeps text on both sides before https://app.raft.build/s/mobile/channel/channel-proj-server-management?msg=msg-edge after.\n\n" +
                            "Thread reply: https://app.raft.build/s/mobile/channel/channel-proj-server-management?thread=channel-proj-server-management%3Amsg-parent&msg=msg-reply and DM: https://app.raft.build/s/mobile/dm/dm-mahua?msg=msg-dm.\n\n" +
                            "External negative stays a link: https://example.com/visual. Code negative stays literal: `https://app.raft.build/s/mobile/channel/channel-design?msg=msg-code`.",
                    threadChannelId = "thread-msg-permalink-visual"
                )
            ),
        members =
            listOf(
                RaftThreadMemberItem(
                    id = "agent-kmp-expert",
                    displayName = "KMP-专家",
                    handle = "KMP-专家",
                    kind = "agent",
                    avatarLabel = "KM"
                )
            ),
        showComposer = false,
        joined = true,
        composer =
            RaftThreadComposerPayload(
                instanceId = instanceId,
                enabled = false
            )
    )
internal fun threadActionCardVisualFixture(
    resolvedInstanceId: String,
    messages: List<RaftThreadMessageItem>
): RaftThreadScreenPayload =
    RaftThreadScreenPayload(
        instanceId = resolvedInstanceId,
        title = "#visual-testing",
        subtitle = "Thread action card visual fixture",
        status = "content",
        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
        streamChannelId = "channel-visual-testing",
        sourceThreadId = "channel-visual-testing",
        selectedTab = "chat",
        showModeTabs = false,
        alignOwnMessagesRight = false,
        showMessageSaveAction = false,
        showTaskOpenActions = false,
        showTaskStatusActions = false,
        messages = messages,
        members =
            listOf(
                RaftThreadMemberItem(
                    id = "agent-cindy",
                    displayName = "Cindy",
                    handle = "Cindy",
                    kind = "agent",
                    avatarLabel = "CI"
                ),
                RaftThreadMemberItem(
                    id = "visual-user",
                    displayName = "artin",
                    handle = "artin",
                    kind = "human",
                    avatarLabel = "AR"
                )
            ),
        showComposer = false,
        joined = true,
        composer =
            RaftThreadComposerPayload(
                instanceId = resolvedInstanceId,
                enabled = false
            )
    )
internal fun threadActionCardCatalogMessages(): List<RaftThreadMessageItem> =
    listOf(
        threadActionCardVisualMessage(
            id = "msg-action-card-channel-create",
            body = "Prepared a channel creation action card.",
            card =
                ThreadActionCard(
                    type = "channel:create",
                    name = "release-gate",
                    description = "Coordinate release smoke checks.",
                    visibility = "private",
                    initialHumans = listOf("artin"),
                    initialAgents = listOf("Cindy")
                )
        ),
        threadActionCardVisualMessage(
            id = "msg-action-card-agent-create",
            body = "Prepared an agent creation action card.",
            card =
                ThreadActionCard(
                    type = "agent:create",
                    name = "Visual-QA",
                    description = "Compare React and Android captures.",
                    suggestedComputer = "Jiachengs-MacBook-Pro"
                )
        ),
        threadActionCardVisualMessage(
            id = "msg-action-card-add-member",
            body = "Prepared an add-member action card.",
            card =
                ThreadActionCard(
                    type = "channel:add_member",
                    channel = "visual-testing",
                    humans = listOf("artin"),
                    agents = listOf("Android-Developer-4"),
                    draftHint = "Add the implementation owner before capture starts."
                )
        ),
        threadActionCardVisualMessage(
            id = "msg-action-card-channel-done",
            body = "Completed channel creation action card.",
            card =
                ThreadActionCard(
                    type = "channel:create",
                    state = "executed",
                    name = "visual-release",
                    visibility = "public",
                    executedByUserName = "artin",
                    resultKind = "channel",
                    resultName = "visual-release"
                )
        ),
        threadActionCardVisualMessage(
            id = "msg-action-card-register-app",
            body = "Prepared a Connected App registration card.",
            card =
                ThreadActionCard(
                    type = "integration:register_app",
                    name = "Build Monitor",
                    draftHint = "Create this app for the release agent.",
                    metadataItems =
                        listOf(
                            ThreadActionCardMetadataItem("name", "Build Monitor"),
                            ThreadActionCardMetadataItem(
                                "returnUrl",
                                "https://build.example/oauth/callback"
                            ),
                            ThreadActionCardMetadataItem("scopes", "identity, profile")
                        ),
                    presentationWarning =
                        "The requesting agent becomes the initial app owner. No client secret is stored in this card.",
                    genericApprovalAllowed = true
                )
        ),
        threadActionCardVisualMessage(
            id = "msg-action-card-future-display-only",
            body = "Prepared a future action card.",
            card =
                ThreadActionCard(
                    type = "integration:future_capability",
                    presentationTitle = "Review future capability",
                    metadataItems =
                        listOf(
                            ThreadActionCardMetadataItem("target", "@release-agent"),
                            ThreadActionCardMetadataItem("riskLevel", "medium")
                        ),
                    genericApprovalAllowed = false
                )
        )
    )
internal fun approveLoginActionCardVisualMessages(executed: Boolean): List<RaftThreadMessageItem> {
    val duplicateDraftHint =
        "LiBai is requesting human approval to use Hands. Server-installed apps do not need this approval; Marketplace apps do."
    val prepared =
        ThreadActionCard(
            type = "integration:approve_agent_login",
            state = "prepared",
            name = "LiBai",
            requestId = "login-request-visual",
            agentId = "agent-libai",
            agentName = "LiBai",
            clientId = "client-hands",
            clientKey = "hands",
            clientName = "Hands",
            scopes = listOf("identity", "openid", "profile"),
            draftHint = duplicateDraftHint
        )
    val card =
        if (executed) {
            prepared.copy(
                state = "executed",
                executedByUserName = "artin",
                resultKind = "agent-integration-login",
                resultName = "Hands"
            )
        } else {
            prepared
        }
    return listOf(
        threadActionCardVisualMessage(
            id = if (executed) "msg-action-card-approve-login-executed" else "msg-action-card-approve-login-prepared",
            body = if (executed) "Hands login approved for @LiBai" else "Approve Hands for @LiBai",
            card = card
        )
    )
}
internal fun threadActionCardVisualMessage(
    id: String,
    body: String,
    card: ThreadActionCard
): RaftThreadMessageItem =
    RaftThreadMessageItem(
        id = id,
        authorId = "agent-cindy",
        authorName = "Cindy",
        authorDescription = "Keeps visual testing release notes aligned.",
        authorKind = "agent",
        avatarLabel = "CI",
        timestampLabel = "07/08 14:30",
        messageType = "System",
        body = body,
        metadata = card.toMessageMetadata()
    )
internal fun threadVisualFixtureHighlightedMentions(
    visualFixture: String
): Set<String> =
    if (visualFixture.requiresViewerSelfMentionContext()) {
        // Visual fixtures must be deterministic and match the React fixture's
        // declared viewer. Do not let a runner's persisted login (or the lack
        // of one) change self-mention chrome between captures.
        setOf("artin")
    } else {
        emptySet()
    }
internal fun String.requiresViewerSelfMentionContext(): Boolean =
    this == THREAD_MESSAGE_VISUAL_FIXTURE_MARKDOWN_MENTION_CHILD ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_MD_LINK_REF ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_PERMALINK_CHIPS_NARROW

/**
 * One-message thread payload whose body is a fenced `mermaid` block between two
 * plain paragraphs, mirroring the real chat shape the renderer sees.
 */
private fun threadMermaidVisualFixture(
    resolvedInstanceId: String,
    messageId: String,
    subtitle: String,
    intro: String,
    mermaid: String
): RaftThreadScreenPayload =
    RaftThreadScreenPayload(
        instanceId = resolvedInstanceId,
        title = "#markdown",
        subtitle = subtitle,
        status = "content",
        surfaceKind = RaftThreadSurfaceKind.Channel,
        streamChannelId = "channel-markdown",
        sourceThreadId = "channel-markdown",
        selectedTab = "chat",
        showModeTabs = false,
        alignOwnMessagesRight = false,
        showMessageSaveAction = false,
        showTaskOpenActions = false,
        showTaskStatusActions = false,
        messages =
            listOf(
                RaftThreadMessageItem(
                    id = messageId,
                    authorId = "agent-cindy",
                    authorName = "Cindy",
                    authorDescription = "Keeps visual testing release notes aligned.",
                    authorKind = "agent",
                    avatarLabel = "CI",
                    timestampLabel = "09/21 07:20",
                    messageType = "Chat",
                    body = intro + "\n\n```mermaid\n" + mermaid + "```\n\nTrailing paragraph closes the specimen."
                )
            ),
        members = emptyList(),
        showComposer = false,
        joined = true,
        composer =
            RaftThreadComposerPayload(
                instanceId = resolvedInstanceId,
                enabled = false
            )
    )
