package ai.slock.compose

import ai.slock.compose.components.SlockAnchoredMetaChip
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.components.SlockToolbarChip
import ai.slock.compose.base.Utils
import ai.slock.compose.base.bridgeModule
import ai.slock.compose.data.appendSharedThreadSentMessageFromPage
import ai.slock.compose.data.parseSharedThreadPickedAttachmentFiles
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.NetworkSlockTasksApi
import ai.slock.compose.data.NetworkSlockThreadApi
import ai.slock.compose.data.RealChannelRepository
import ai.slock.compose.data.RealConversationRepository
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.data.RealSavedRepository
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.ThreadApiResult
import ai.slock.compose.data.SlockAgentsApiClient
import ai.slock.compose.data.SlockAgentsNetworkApi
import ai.slock.compose.data.formatAttachmentSizeLabel
import ai.slock.compose.data.SlockComputersApiClient
import ai.slock.compose.data.SlockComputersNetworkApi
import ai.slock.compose.data.ThreadAttachmentCommentItem
import ai.slock.compose.data.ThreadAttachmentCommentsPage
import ai.slock.compose.data.SlockIntegrationsActionsPushApiClient
import ai.slock.compose.data.SlockIntegrationsActionsPushNetworkApi
import ai.slock.compose.data.ThreadActionCard
import ai.slock.compose.data.SharedComputersIntent
import ai.slock.compose.data.SharedComputersLoadStatus
import ai.slock.compose.data.SharedComputersPickerSlice
import ai.slock.compose.data.SharedComputersStore
import ai.slock.compose.data.SharedMembersIntent
import ai.slock.compose.data.SharedMembersChannelSuggestion
import ai.slock.compose.data.SharedMembersMemberSuggestion
import ai.slock.compose.data.SharedMembersStore
import ai.slock.compose.data.SharedTasksDataSource
import ai.slock.compose.data.SharedTasksRouteIntent
import ai.slock.compose.data.SharedTasksScope
import ai.slock.compose.data.SharedTasksViewModel
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.SlockSharedStoreRegistry
import ai.slock.compose.data.realtime.RealtimeEventMapper
import ai.slock.compose.data.realtime.RealtimeRoutePolicy
import ai.slock.compose.data.PendingMentionAction
import ai.slock.compose.data.PendingMentionActionExecuteResult
import ai.slock.compose.data.TaskSnapshot
import ai.slock.compose.data.TasksUiState
import ai.slock.compose.data.isAuthSessionBlocker
import ai.slock.compose.data.isAuthSessionErrorCode
import ai.slock.compose.data.actionCardFromMessageMetadata
import ai.slock.compose.data.isSupportedCommitAction
import ai.slock.compose.data.runCatchingPreservingCancellation
import ai.slock.compose.data.summaryCounts
import ai.slock.compose.network.SlockNetworkModule
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.markdown.threadMarkdownTagDisplayLabel
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.formatSlockTimestampLabel
import ai.slock.compose.model.SlockTimeFormat24Hour
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
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
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.extension.bouncesEnable
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.Path
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.PerformanceModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val THREAD_PAGE = "compose_thread"
private const val THREAD_REALTIME_EVENT = "slockRealtimeEvent"
private const val THREAD_MESSAGE_VISUAL_PAGE = "compose_thread_message_visual"
private const val THREAD_MESSAGE_ROW_VISUAL_PAGE = "compose_thread_message_row_visual"
private const val THREAD_HEADER_VISUAL_PAGE = "compose_thread_header_visual"
private const val CHANNEL_MEMBERS_PANEL_VISUAL_PAGE = "compose_channel_members_panel_visual"
private const val CHANNEL_SETTINGS_PANEL_VISUAL_PAGE = "compose_channel_settings_panel_visual"
private const val CHANNEL_ADD_MEMBER_PANEL_VISUAL_PAGE = "compose_channel_add_member_panel_visual"
private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
private const val ThreadMessagesMaxBeyondBoundsItemCount = 2
private const val ThreadPerformanceSlowThresholdMs = 4L
private const val ThreadFirstContentExtrasDelayMs = 96L

internal fun shouldLogThreadPerformance(durationMs: Long): Boolean =
    DEBUG_THREAD_VIRTUAL_RENDER_LOG || durationMs >= ThreadPerformanceSlowThresholdMs

internal fun KuiklyThreadScreenPayload.shouldRenderThreadEarlyLoadingShell(): Boolean =
    status == "loading" &&
        messages.isEmpty() &&
        originMessage == null &&
        originPreview.isNullOrBlank()

internal fun KuiklyThreadScreenPayload.shouldStageThreadFirstContentExtras(): Boolean =
    status == "content"

/**
 * Phase 1 (#172) instrumentation helper: monotonic counter that yields
 * a fresh session id for each Thread entry. Keeping this as a separate
 * object (instead of a static field on a Composable scope) avoids any
 * recomposition surprises.
 */
private object ThreadRowProbeNextSessionId {
    private var counter: Int = 0
    fun value(): String {
        counter += 1
        return "threadRowProbeSession-${counter}"
    }
}

/**
 * One-shot first-viewport probe for #202.
 *
 * This deliberately keeps mutable counters outside Compose state: the values are
 * diagnostic-only and must not invalidate row composition while measuring it.
 */
private class ThreadFirstViewportProbe(
    val sessionMarker: String
) {
    private val composedMessageKeys = mutableSetOf<String>()
    private val placedMessageKeys = mutableSetOf<String>()
    private var firstComposedMessageIndex: Int = Int.MAX_VALUE
    private var lastComposedMessageIndex: Int = -1
    private var firstPlacedMessageIndex: Int = Int.MAX_VALUE
    private var lastPlacedMessageIndex: Int = -1
    private var logged: Boolean = false

    fun recordComposedMessage(messageId: String, index: Int) {
        if (composedMessageKeys.add(messageId)) {
            firstComposedMessageIndex = minOf(firstComposedMessageIndex, index)
            lastComposedMessageIndex = maxOf(lastComposedMessageIndex, index)
        }
    }

    fun recordPlacedMessage(messageId: String, index: Int) {
        if (placedMessageKeys.add(messageId)) {
            firstPlacedMessageIndex = minOf(firstPlacedMessageIndex, index)
            lastPlacedMessageIndex = maxOf(lastPlacedMessageIndex, index)
        }
    }

    fun markLogged(): Boolean {
        if (logged) return false
        logged = true
        return true
    }

    fun toDiagnosticsString(
        result: String,
        payload: KuiklyThreadScreenPayload,
        lazyItemCount: Int,
        stats: ThreadLazyWindowStats
    ): String =
        "thread_first_viewport_probe result=$result sessionMarker=$sessionMarker " +
            "threadId=${payload.sourceThreadId.orEmpty()} instanceId=${payload.instanceId} " +
            "originStatus=${payload.originStatus} hasOrigin=${payload.originMessage != null} " +
            "messagesTotal=${payload.messages.size} totalItems=$lazyItemCount " +
            "beyondBounds=${threadMessagesBeyondBoundsItemCount(lazyItemCount)} " +
            "composedMessageRows=${composedMessageKeys.size} " +
            "composedMessageRange=${firstComposedMessageIndex.asThreadProbeRangeStart()}..$lastComposedMessageIndex " +
            "placedMessageRows=${placedMessageKeys.size} " +
            "placedMessageRange=${firstPlacedMessageIndex.asThreadProbeRangeStart()}..$lastPlacedMessageIndex " +
            "visibleItems=${stats.visibleItems} visibleItemRange=${stats.firstVisibleItem}..${stats.lastVisibleItem} " +
            "visibleMessages=${stats.visibleMessages} " +
            "visibleMessageRange=${stats.firstVisibleMessage}..${stats.lastVisibleMessage}"
}

private fun Int.asThreadProbeRangeStart(): Int =
    if (this == Int.MAX_VALUE) -1 else this

private object ThreadLocalSendNextId {
    private var counter: Int = 0
    fun value(instanceId: String): String {
        counter += 1
        val scope = instanceId.takeIf { it.isNotBlank() } ?: "thread"
        return "local-send-$scope-$counter"
    }
}

/**
 * Phase 1 instrumentation flag for #172 (Thread virtual rendering v1).
 *
 * When true, each `ThreadMessageCard` / `ThreadSystemMessageRow`
 * composition emits a single `thread_row_compose` log via the shared
 * bridge, gated by a per-row `LaunchedEffect(message.id, sessionMarker)`
 * so the log fires exactly once per row per Thread entry (no scroll
 * spam). This isolates the proof to a one-shot diagnostic path; the
 * flag is meant to be removed / feature-flagged off once the
 * composition-vs-payload contract is verified.
 */
internal const val DEBUG_THREAD_VIRTUAL_RENDER_LOG = false
internal const val ThreadReplyPageTitle = "Thread"
internal const val ThreadReplyEmptyTitle = "No replies yet"
internal const val ThreadReplyEmptyDetail = ""
private val threadPageJson = Json { ignoreUnknownKeys = true }
private val ThreadComposerBottomBasePadding = 12.dp
private val ThreadComposerBottomSafeAreaLift = 2.dp
private val threadHeaderUuidRegex =
    Regex("^[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$")
private val threadHeaderShortIdRegex = Regex("^[A-Fa-f0-9]{8}$")
private val ThreadHeaderActionSize = 30.dp
private val ThreadHeaderActionShadowOffset = 2.dp
private val ThreadHeaderActionIconSize = 15.dp
private val ThreadHeaderActionLabelMinWidth = 46.dp
private val ThreadHeaderChannelTileSize = 34.dp
private val ThreadHeaderChannelIconSize = 16.dp

internal fun threadComposerBottomPadding(keyboardInsetDp: Float, bottomSafeArea: Dp): Dp {
    val keyboardInset = keyboardInsetDp.coerceAtLeast(0f).dp
    val safeAreaInset = if (keyboardInsetDp > 0f) 0.dp else bottomSafeArea
    return ThreadComposerBottomBasePadding + ThreadComposerBottomSafeAreaLift + keyboardInset + safeAreaInset
}

internal fun threadChannelHeaderSubtitle(channelName: String?): String? {
    val raw = channelName?.trim().orEmpty()
    if (raw.isBlank() || raw.startsWith("Origin:", ignoreCase = true)) return null
    val name = raw.removePrefix("#").substringBefore(":").trim()
    if (name.isBlank()) return null
    if (name.equals(ThreadReplyPageTitle, ignoreCase = true)) return null
    if (name.equals("Threads", ignoreCase = true)) return null
    if (name.equals("Thread", ignoreCase = true) || name.equals("Channel", ignoreCase = true)) return null
    if (threadHeaderUuidRegex.matches(name) || threadHeaderShortIdRegex.matches(name)) return null
    return "#$name"
}

internal fun existingThreadChannelHeaderSubtitle(subtitle: String?): String? =
    subtitle?.trim()?.takeIf { it.startsWith("#") }?.let(::threadChannelHeaderSubtitle)

internal fun threadHeaderDisplayTitle(title: String): String =
    title.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: title

@Page(THREAD_PAGE, supportInLocal = true)
internal class ComposeThreadPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadPageScreen() }
    }
}

@Page(THREAD_MESSAGE_VISUAL_PAGE, supportInLocal = true)
internal class ComposeThreadMessageVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadMessageVisualScreen() }
    }
}

@Page(THREAD_MESSAGE_ROW_VISUAL_PAGE, supportInLocal = true)
internal class ComposeThreadMessageRowVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadMessageVisualScreen() }
    }
}

@Page(THREAD_HEADER_VISUAL_PAGE, supportInLocal = true)
internal class ComposeThreadHeaderVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadHeaderVisualScreen() }
    }
}

@Page(CHANNEL_MEMBERS_PANEL_VISUAL_PAGE, supportInLocal = true)
internal class ComposeChannelMembersPanelVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelMembersPanelVisualScreen() }
    }
}

@Page(CHANNEL_SETTINGS_PANEL_VISUAL_PAGE, supportInLocal = true)
internal class ComposeChannelSettingsPanelVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelSettingsPanelVisualScreen() }
    }
}

@Page(CHANNEL_ADD_MEMBER_PANEL_VISUAL_PAGE, supportInLocal = true)
internal class ComposeChannelAddMemberPanelVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelAddMemberPanelVisualScreen() }
    }
}

@Serializable
internal data class KuiklyThreadScreenPayload(
    val instanceId: String = "",
    val title: String = "Thread",
    val subtitle: String? = null,
    val status: String = "loading",
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
    val channelKind: String = "channel",
    val sourceThreadId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val memberScopedMentions: Boolean = false,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val showModeTabs: Boolean = true,
    val showReplyActions: Boolean = true,
    val alignOwnMessagesRight: Boolean = false,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
    val messages: List<KuiklyThreadMessageItem> = emptyList(),
    val originMessage: KuiklyThreadMessageItem? = null,
    val originPreview: String? = null,
    val originParentMessageId: String? = null,
    val focusedMessageId: String? = null,
    val originStatus: String = "none",
    val originErrorCode: String? = null,
    val tasks: List<KuiklyThreadTaskItem> = emptyList(),
    val knownMarkdownChannelNames: List<String> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val olderMessagesStatus: String = "idle",
    val newerMessagesStatus: String = "idle",
    val errorCode: String? = null,
    val emptyTitle: String = "No messages yet",
    val emptyDetail: String = "This conversation has no messages yet.",
    val filesPayload: String = "{}",
    val composer: KuiklyThreadComposerPayload = KuiklyThreadComposerPayload(),
    val showComposer: Boolean = true,
    val joined: Boolean = true,
    val canSend: Boolean = true,
    val membersCount: Int = 0,
    val members: List<KuiklyThreadMemberItem> = emptyList(),
    val memberCandidates: List<KuiklyThreadMemberItem> = emptyList(),
    val canAddMembers: Boolean = true,
    val addMemberStatus: String = "idle",
    val addMemberErrorCode: String? = null,
    val removeMemberStatus: String = "idle",
    val removingMemberId: String? = null,
    val removeMemberErrorCode: String? = null,
    val settingsActions: List<KuiklyThreadSettingsAction> = emptyList(),
    val showMembersAction: Boolean = true,
    val showSettingsAction: Boolean = true,
    val showOpenInChannelAction: Boolean = false,
    val showMessageSaveAction: Boolean = true,
    val showTaskOpenActions: Boolean = true,
    val showTaskStatusActions: Boolean = true,
    val joinLabel: String = "Join channel",
    val dmPeerName: String? = null,
    val dmPeerAvatarUrl: String? = null,
    val dmPeerAvatarLabel: String? = null,
    val dmPeerGravatarUrl: String? = null
)

@Serializable
internal data class WireThreadLaunchPayload(
    val instanceId: String = "",
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
    val threadId: String? = null,
    val sourceThreadId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val originParentMessageId: String? = null,
    val focusedMessageId: String? = null,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val alignOwnMessagesRight: Boolean = false,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour
) {
    fun hasRouteIdentity(): Boolean =
        listOf(threadId, sourceThreadId, streamChannelId, parentChannelId, parentMessageId)
            .any { !it.isNullOrBlank() }

    fun toInitialPayload(fallbackInstanceId: String): KuiklyThreadScreenPayload {
        val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
        val sourceStreamCandidate =
            sourceThreadId.nonBlank()?.takeUnless { isReplyThread && it == parentChannelId.nonBlank() }
        val resolvedStreamId =
            streamChannelId.nonBlank()
                ?: sourceStreamCandidate
                ?: if (isReplyThread) null else threadId.nonBlank()
        val resolvedSourceId = sourceStreamCandidate ?: resolvedStreamId
        val resolvedInstanceId =
            instanceId.nonBlank()
                ?: fallbackInstanceId.nonBlank()
                ?: resolvedSourceId?.let { "thread-$it" }
                ?: "thread-launch"
        val resolvedTitle =
            SharedThreadHeaderContextStore
                .launchContext(
                    surfaceKind = surfaceKind,
                    parentMessageId = originParentMessageId.nonBlank() ?: parentMessageId.nonBlank()
                )
                .title
        val resolvedComposer =
            KuiklyThreadComposerPayload(
                surfaceKind = surfaceKind,
                enabled = true,
                showAsTaskToggle = true,
                mentionChannelId = parentChannelId.nonBlank(),
                parentMessageId = parentMessageId.nonBlank(),
                streamChannelId = resolvedStreamId
            )
        return KuiklyThreadScreenPayload(
            instanceId = resolvedInstanceId,
            title = resolvedTitle,
            subtitle = null,
            status = "loading",
            surfaceKind = surfaceKind,
            sourceThreadId = resolvedSourceId,
            streamChannelId = resolvedStreamId,
            parentChannelId = parentChannelId.nonBlank(),
            parentMessageId = parentMessageId.nonBlank(),
            memberScopedMentions = surfaceKind == KuiklyThreadSurfaceKind.DirectMessage,
            originParentMessageId =
                if (isReplyThread) {
                    originParentMessageId.nonBlank() ?: parentMessageId.nonBlank()
                } else {
                    null
                },
            focusedMessageId = focusedMessageId.nonBlank(),
            selectedTab = selectedTab,
            navigationLabel = navigationLabel,
            showModeTabs = !isReplyThread,
            showReplyActions = !isReplyThread,
            alignOwnMessagesRight = alignOwnMessagesRight,
            localOffsetMinutes = localOffsetMinutes,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            timeFormat = timeFormat,
            filesPayload = "{}",
            composer = resolvedComposer,
            showMembersAction = !isReplyThread && surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
            showSettingsAction = !isReplyThread && surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
            showOpenInChannelAction = isReplyThread && parentMessageId.nonBlank() != null,
            emptyTitle = "Loading conversation",
            emptyDetail = "Loading messages from this conversation."
        ).withLaunchHeaderContext()
    }
}

internal fun KuiklyThreadScreenPayload.withSharedShellEventCapabilities(): KuiklyThreadScreenPayload =
    copy(
        subtitle =
            if (showModeTabs) {
                subtitle
            } else {
                existingThreadChannelHeaderSubtitle(subtitle)
            },
        showOpenInChannelAction =
            showOpenInChannelAction ||
                (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread && originParentMessageId?.isNotBlank() == true),
        showSettingsAction = showSettingsAction && showModeTabs,
        showMembersAction = showMembersAction && showModeTabs
    )

internal fun KuiklyThreadScreenPayload.toWireThreadLaunchPayload(): WireThreadLaunchPayload =
    WireThreadLaunchPayload(
        instanceId = instanceId,
        surfaceKind = surfaceKind,
        threadId =
            when (surfaceKind) {
                KuiklyThreadSurfaceKind.ReplyThread,
                KuiklyThreadSurfaceKind.DirectMessage -> streamChannelId.nonBlank() ?: sourceThreadId.nonBlank()
                else -> null
            },
        sourceThreadId = sourceThreadId.nonBlank(),
        streamChannelId = streamChannelId.nonBlank(),
        parentChannelId = parentChannelId.nonBlank(),
        parentMessageId = parentMessageId.nonBlank(),
        originParentMessageId = originParentMessageId.nonBlank() ?: parentMessageId.nonBlank(),
        focusedMessageId = focusedMessageId.nonBlank(),
        selectedTab = selectedTab,
        navigationLabel = navigationLabel,
        alignOwnMessagesRight = alignOwnMessagesRight,
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

private fun WireThreadLaunchPayload.toThreadPageData(): JSONObject =
    JSONObject().apply {
        put("payload", threadPageJson.encodeToString(this@toThreadPageData))
        put("instanceId", instanceId)
    }

internal fun KuiklyThreadScreenPayload.toThreadMembersProfilePageData(selectedMemberId: String): JSONObject? {
    val selected = selectedMemberId.takeIf { it.isNotBlank() } ?: return null
    val memberProfiles = threadMemberProfilesForProfilePage(selected)
    if (memberProfiles.none { it.id.value == selected }) return null
    val membersPayload =
        KuiklyMembersPayload(
            instanceId = "thread-members-${instanceId.ifBlank { sourceThreadId.orEmpty() }}-$selected",
            title = "Members",
            members = memberProfiles,
            selectedMemberId = selected,
            localOffsetMinutes = localOffsetMinutes,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            timeFormat = timeFormat,
            canCreateAgents = false
        )
    return JSONObject().apply {
        put("payload", threadPageJson.encodeToString(membersPayload))
        put("instanceId", membersPayload.instanceId)
    }
}

private fun KuiklyThreadScreenPayload.threadMemberProfilesForProfilePage(selectedMemberId: String): List<MemberProfile> =
    (members.map { it.toMemberProfile() } + listOfNotNull(findThreadAuthorProfile(selectedMemberId)))
        .distinctBy { it.id.value }

private fun KuiklyThreadScreenPayload.findThreadAuthorProfile(memberId: String): MemberProfile? =
    (listOfNotNull(originMessage) + messages)
        .firstOrNull { it.authorId == memberId }
        ?.toMemberProfile()

private fun KuiklyThreadMemberItem.toMemberProfile(): MemberProfile =
    MemberProfile(
        id = MemberId(id),
        displayName = displayName.ifBlank { handle.ifBlank { id } },
        handle = handle.ifBlank { displayName.ifBlank { id } },
        kind = threadMemberKind(),
        description = roleLabel.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl,
        avatarLabel = avatarLabel.ifBlank { displayName.ifBlank { handle.ifBlank { id } }.take(1).uppercase() },
        serverId = sourceServerId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        serverName = sourceServerLabel?.takeIf { it.isNotBlank() }
    )

private fun KuiklyThreadMessageItem.toMemberProfile(): MemberProfile =
    MemberProfile(
        id = MemberId(authorId),
        displayName = authorName.ifBlank { authorId },
        handle = authorName.ifBlank { authorId },
        kind = threadAuthorKind(),
        gravatarHash = gravatarHash,
        avatar = avatarWireValue?.takeIf { it.isNotBlank() }?.let(AvatarReference::fromWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.ifBlank { authorId }.take(1).uppercase() }
    )

private fun KuiklyThreadMemberItem.threadMemberKind(): MemberKind =
    if (kind.equals("agent", ignoreCase = true)) MemberKind.Agent else MemberKind.Human

private fun KuiklyThreadMessageItem.threadAuthorKind(): MemberKind =
    if (authorKind.equals("agent", ignoreCase = true)) MemberKind.Agent else MemberKind.Human

internal fun KuiklyThreadScreenPayload.replyThreadHeaderSubtitle(): String? =
    threadChannelHeaderSubtitle(title)

@Serializable
internal data class KuiklyThreadMessageItem(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorKind: String = "",
    val isOwn: Boolean = false,
    val avatarLabel: String = "",
    val avatarWireValue: String? = null,
    val gravatarHash: String? = null,
    val timestampLabel: String = "",
    val seq: Long? = null,
    val messageType: String = "Chat",
    val systemEventType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val body: String = "",
    val threadChannelId: String? = null,
    val replyCount: Int = 0,
    val unreadReplyCount: Int = 0,
    val firstUnreadReplyMessageId: String? = null,
    val isSaved: Boolean = false,
    val taskId: String? = null,
    val taskNumber: Int? = null,
    val taskStatus: String? = null,
    val taskAssigneeId: String? = null,
    val taskAssigneeName: String? = null,
    val reactions: List<KuiklyThreadReactionItem> = emptyList(),
    val attachments: List<KuiklyThreadAttachmentItem> = emptyList(),
    val commentRef: KuiklyThreadMessageCommentRef? = null,
    val deliveryState: String = ThreadMessageDeliverySent,
    val pendingSendContext: KuiklyThreadPendingSendContext? = null,
    val pendingSendErrorCode: String? = null
)

internal const val ThreadMessageDeliverySending = "sending"
internal const val ThreadMessageDeliverySent = "sent"
internal const val ThreadMessageDeliveryFailed = "failed"

@Serializable
internal data class KuiklyThreadPendingSendContext(
    val channelId: String,
    val body: String,
    val asTask: Boolean = false,
    val attachmentIds: List<String> = emptyList(),
    val attachments: List<KuiklyPendingAttachmentItem> = emptyList(),
    val mentionChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null,
    val memberScopedMentions: Boolean = false
)

internal fun KuiklyThreadMessageItem.hasLocalSendState(): Boolean =
    deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) ||
        deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) ||
        pendingSendContext != null

private fun KuiklyThreadMessageItem.canRetryLocalSend(): Boolean =
    deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) && pendingSendContext != null

private fun buildThreadPendingSendMessage(
    localMessageId: String,
    context: KuiklyThreadPendingSendContext,
    session: StoredAuthSession?
): KuiklyThreadMessageItem {
    val user = session?.user
    val authorName = user?.bestDisplayName ?: "You"
    return KuiklyThreadMessageItem(
        id = localMessageId,
        authorId = user?.id ?: "local-user",
        authorName = authorName,
        authorKind = "human",
        isOwn = true,
        avatarLabel = authorName.take(1).uppercase(),
        avatarWireValue = user?.avatarUrl,
        gravatarHash = user?.gravatarHash,
        timestampLabel = "Now",
        body = context.body,
        // Phase 3 #3 #3 (#280): explicit nullable instead of \`context.streamChannelId ?: context.channelId\`
        // (反例 3, intentionally left during #278 send flow scope). v0.2 ReplyThread contract
        // requires an explicit threadChannelId; falling back to channelId silently degrades the
        // pending send bubble's thread identity. Callers of buildThreadPendingSendMessage must
        // supply a non-blank context.streamChannelId or this leaves threadChannelId null,
        // which the thread local store will surface as "stream missing" UX (no silent fallback).
        threadChannelId = context.streamChannelId?.takeIf { it.isNotBlank() },
        attachments =
            context.attachments.map { attachment ->
                KuiklyThreadAttachmentItem(
                    id = attachment.id,
                    name = attachment.fileName,
                    mimeType = attachment.mimeType.orEmpty(),
                    sizeLabel = attachment.sizeLabel
                )
            },
        deliveryState = ThreadMessageDeliverySending,
        pendingSendContext = context,
        pendingSendErrorCode = null
    )
}

private data class ThreadDeferredAttachmentSend(
    val localMessageId: String,
    val context: KuiklyThreadPendingSendContext
)

@Serializable
internal data class KuiklyThreadMessageCommentRef(
    val attachmentId: String,
    val filename: String,
    val hostMessageId: String? = null,
    val hostSource: KuiklyThreadMessageCommentHostSource? = null,
    val anchorLabel: String? = null
)

@Serializable
internal data class KuiklyThreadMessageCommentHostSource(
    val type: String,
    val routeKind: String,
    val channelId: String,
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null
)

@Serializable
internal data class KuiklyThreadReactionItem(
    val emoji: String,
    val count: Int = 0,
    val selected: Boolean = false
)

@Serializable
internal data class KuiklyThreadAttachmentItem(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val sizeLabel: String = "",
    val thumbnailUrl: String? = null,
    val commentCount: Int = 0
)

private data class ThreadImagePreviewUrl(
    val url: String,
    val expiresAt: String?
)

private data class ThreadImagePreviewState(
    val images: List<KuiklyThreadAttachmentItem>,
    val currentIndex: Int,
    val loadingAttachmentIds: Set<String> = emptySet(),
    val failedAttachmentIds: Set<String> = emptySet()
) {
    val current: KuiklyThreadAttachmentItem? get() = images.getOrNull(currentIndex)
}

private data class ThreadAttachmentNoPreviewState(
    val attachment: KuiklyThreadAttachmentItem,
    val markdownStatus: String = "idle",
    val markdownBody: String? = null,
    val markdownError: String? = null
)

private const val ThreadAttachmentPreviewOpenWithActionId = "open-with"

private data class ThreadAttachmentCommentsDialogState(
    val attachment: KuiklyThreadAttachmentItem,
    val parentMessage: KuiklyThreadMessageItem,
    val status: String = "loading",
    val page: ThreadAttachmentCommentsPage? = null,
    val filter: String = "all",
    val draft: String = "",
    val sending: Boolean = false,
    val resolvingCommentIds: Set<String> = emptySet(),
    val pendingAnchorLabel: String? = null,
    val pendingQuoteLabel: String? = null,
    val errorCode: String? = null
)

private data class ThreadCreateAgentActionDialogState(
    val messageId: String,
    val card: ThreadActionCard,
    val submitting: Boolean = false,
    val errorCode: String? = null
)

private data class ThreadImageAttachmentGroup(
    val images: List<KuiklyThreadAttachmentItem>,
    val currentIndex: Int
)

private data class ThreadAttachmentCommentTarget(
    val attachment: KuiklyThreadAttachmentItem,
    val parentMessage: KuiklyThreadMessageItem
)

private data class ThreadAttachmentOpenResult(
    val status: String,
    val message: String = ""
)

private fun <T> DataResult<T>.threadActionErrorCode(fallback: String): String =
    when (this) {
        is DataResult.Content -> fallback
        is DataResult.Empty -> reason.name.lowercase()
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
    }

private fun KuiklyThreadScreenPayload.taskStatusLabel(taskId: String): String? =
    messages.firstOrNull { it.taskId == taskId }?.taskStatus
        ?: originMessage?.takeIf { it.taskId == taskId }?.taskStatus
        ?: tasks.firstOrNull { it.id == taskId }?.status

internal data class SlockThreadWebLinkTarget(
    val channelId: String,
    val messageId: String? = null
)

@Serializable
internal data class KuiklyThreadTaskItem(
    val id: String,
    val title: String,
    val status: String,
    val taskNumber: Int? = null,
    val messageId: String? = null,
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val updatedAtLabel: String = ""
)

@Serializable
internal data class KuiklyThreadMemberItem(
    val id: String,
    val displayName: String,
    val handle: String = "",
    val roleLabel: String = "",
    val avatarUrl: String? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val kind: String = "human",
    val sourceServerId: String? = null,
    val sourceServerLabel: String? = null,
    val canRemove: Boolean = true
)

@Serializable
internal data class KuiklyThreadSettingsAction(
    val id: String,
    val enabled: Boolean = true,
    val permission: String? = null
)

internal data class ThreadSettingsActionUi(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: SlockLucideIconName,
    val destructive: Boolean
)

private fun KuiklyThreadScreenPayload.findMarkdownMentionMember(tag: ThreadMarkdownTag): KuiklyThreadMemberItem? {
    val key = tag.label.removePrefix("@").trim().lowercase()
    if (key.isBlank()) return null
    return members.firstOrNull { member ->
        member.id.equals(key, ignoreCase = true) ||
            member.handle.removePrefix("@").equals(key, ignoreCase = true) ||
            member.displayName.equals(key, ignoreCase = true)
    }
}

internal fun KuiklyThreadScreenPayload.resolveMarkdownTagDisplayLabel(tag: ThreadMarkdownTag): String =
    when (tag.kind) {
        ThreadMarkdownTagKind.UserMention ->
            findMarkdownMentionMember(tag)
                ?.displayName
                ?.trim()
                ?.removePrefix("@")
                ?.takeIf { it.isNotBlank() }
                ?.let { "@$it" }
                ?: threadMarkdownTagDisplayLabel(tag.label)
        else -> threadMarkdownTagDisplayLabel(tag.label)
    }

private fun List<String>.toKnownMarkdownChannelNameSet(): Set<String> =
    mapNotNull { it.trim().takeIf { name -> name.isNotBlank() }?.lowercase() }
        .toSet()

internal fun KuiklyThreadScreenPayload.resolveTaskAssigneeDisplayName(
    value: String?,
    id: String? = null
): String? {
    val normalizedId = id?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val normalizedValue = value?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val member =
        listOfNotNull(normalizedId, normalizedValue)
            .firstNotNullOfOrNull { candidate ->
                members.firstOrNull { member ->
                    member.id.equals(candidate, ignoreCase = true) ||
                        member.handle.removePrefix("@").equals(candidate, ignoreCase = true) ||
                        member.displayName.equals(candidate, ignoreCase = true)
                }
            }
    return member?.displayName?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        ?: normalizedValue?.takeIf { it.isDisplaySafeTaskAssigneeName(normalizedId) }
}

internal fun TaskSummary.threadLinkedTaskAssigneeDisplayName(): String? {
    val assignee = assignee ?: return null
    val assigneeId = assignee.id.value.trim().removePrefix("@").takeIf { it.isNotBlank() }
    val displayName = assignee.displayName.trim().removePrefix("@").takeIf { it.isNotBlank() } ?: return null
    if (assigneeId != null && displayName.equals(assigneeId, ignoreCase = true)) return null
    return displayName.takeIf { it.isDisplaySafeTaskAssigneeName(assigneeId) }
}

internal fun KuiklyThreadMessageItem.withLinkedTask(linkedTask: TaskSummary?): KuiklyThreadMessageItem {
    linkedTask ?: return this
    return copy(
        taskId = linkedTask.id.value,
        taskNumber = linkedTask.taskNumber ?: taskNumber,
        taskStatus = linkedTask.status.name,
        taskAssigneeId = linkedTask.assignee?.id?.value,
        taskAssigneeName = linkedTask.threadLinkedTaskAssigneeDisplayName()
    )
}

internal fun TasksUiState.threadLinkedTasksByMessageId(): Map<String, TaskSummary> {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return emptyMap()
    return content.tasks.threadLinkedTasksByMessageId()
}

internal fun List<TaskSummary>.threadLinkedTasksByMessageId(): Map<String, TaskSummary> =
    mapNotNull { task ->
        val messageId = task.messageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        messageId to task
    }.toMap()

internal fun KuiklyThreadScreenPayload.supportsChannelTasks(): Boolean =
    composer.showAsTaskToggle || showTaskOpenActions || showTaskStatusActions

internal fun shouldStartThreadTasksJoin(
    payload: KuiklyThreadScreenPayload,
    startedThreadId: String?,
    taskChannelId: ChannelId?
): Boolean =
    taskChannelId != null &&
    payload.status == "content" &&
        payload.supportsChannelTasks() &&
        startedThreadId != taskChannelId.value &&
        payload.resolvedAuxiliaryThreadId()?.let { it == taskChannelId.value } == true

internal fun KuiklyThreadScreenPayload.resolvedAuxiliaryThreadId(): String? {
    val candidate = streamChannelId.nonBlank() ?: sourceThreadId.nonBlank() ?: return null
    if (!isReplyThreadWithExplicitParentPair()) return candidate
    if (candidate == parentChannelId.nonBlank()) return null
    if (status == "loading") return null
    return candidate
}

private fun KuiklyThreadScreenPayload.isReplyThreadWithExplicitParentPair(): Boolean =
    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        parentChannelId.nonBlank() != null &&
        replyParentMessageIdForAuxiliary() != null

private fun KuiklyThreadScreenPayload.replyParentMessageIdForAuxiliary(): String? =
    parentMessageId.nonBlank() ?: originParentMessageId.nonBlank()

private fun TasksUiState.threadTasksJoinDiagnosticsResult(): Pair<String, Int>? =
    when (val state = loadState) {
        ScreenLoadState.Loading -> null
        is ScreenLoadState.Content -> "content" to state.data.tasks.size
        is ScreenLoadState.Empty -> "empty:${state.reason.name}" to 0
        is ScreenLoadState.Error -> "error:${state.error.code}" to 0
        is ScreenLoadState.NoPermission -> "no_permission:${state.blocker.code}" to 0
    }

private fun KuiklyThreadScreenPayload.threadRenderDiagnosticsSource(): String =
    when {
        status == "loading" -> "loading"
        status == "content" && messages.isNotEmpty() -> "content"
        status == "content" -> "empty_content"
        isRenderableEmptyReplyThread() -> "empty_reply"
        errorCode != null -> "error"
        else -> status.ifBlank { "unknown" }
    }

private fun KuiklyThreadScreenPayload.isRenderableEmptyReplyThread(): Boolean =
    status == "empty" && surfaceKind == KuiklyThreadSurfaceKind.ReplyThread

private fun KuiklyThreadScreenPayload.isReadyForThreadPerformanceGate(): Boolean =
    status == "content" || isRenderableEmptyReplyThread()

private fun requestKuiklyThreadPerformanceSnapshot(
    bridge: ai.slock.compose.base.BridgeModule,
    threadId: String,
    reason: String,
    status: String
) {
    runCatching {
        PagerManager.getCurrentPager()
            .acquireModule<PerformanceModule>(PerformanceModule.MODULE_NAME)
            .getPerformanceData { data ->
                val summary = bridge.reportKuiklyPerformanceSnapshot(
                    routeName = "kuikly_thread",
                    reason = reason,
                    threadId = threadId,
                    data = data
                )
                bridge.performanceMarker(
                    phase = "thread_first_frame",
                    routeName = "kuikly_thread",
                    event = "instant",
                    threadId = threadId,
                    durationMs = data?.pageLoadTime?.firstPaintCost?.toLong(),
                    metadata =
                        mapOf(
                            "reason" to reason,
                            "status" to status
                        )
                )
                bridge.log("kuikly_perf_snapshot $summary")
            }
    }.onFailure { error ->
        val summary = bridge.reportKuiklyPerformanceSnapshot(
            routeName = "kuikly_thread",
            reason = reason,
            threadId = threadId,
            data = null,
            statusOverride = "failed"
        )
        bridge.performanceMarker(
            phase = "thread_first_frame",
            routeName = "kuikly_thread",
            event = "instant",
            threadId = threadId,
            metadata =
                mapOf(
                    "reason" to reason,
                    "status" to "failed"
                )
        )
        bridge.log("kuikly_perf_snapshot $summary error=${error.message.orEmpty()}")
    }
}

private fun KuiklyThreadScreenPayload.findMarkdownTaskId(tag: ThreadMarkdownTag): String? {
    val key = tag.label.removePrefix("#").trim()
    val taskNumber = key.toIntOrNull()
    return tasks.firstOrNull { task ->
        task.id.equals(key, ignoreCase = true) ||
            task.taskNumber == taskNumber
    }?.id
        ?: messages.firstOrNull { message ->
            message.taskId?.equals(key, ignoreCase = true) == true ||
                message.taskNumber == taskNumber
        }?.taskId
        ?: originMessage?.takeIf { message ->
            message.taskId?.equals(key, ignoreCase = true) == true ||
                message.taskNumber == taskNumber
        }?.taskId
}

private fun KuiklyThreadScreenPayload.findTaskParentMessage(taskId: String): KuiklyThreadMessageItem? =
    messages.firstOrNull { message ->
        message.id == taskId || message.taskId == taskId
    } ?: originMessage?.takeIf { message ->
        message.id == taskId || message.taskId == taskId
    }

private enum class KuiklyThreadTab(val wireValue: String, val label: String) {
    Chat("chat", "Chat"),
    Tasks("tasks", "Tasks"),
    Files("files", "Files");

    companion object {
        fun fromWireValue(value: String?): KuiklyThreadTab =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Chat
    }
}

internal fun shouldShowThreadComposerForTab(
    joined: Boolean,
    showComposer: Boolean,
    selectedTab: String?
): Boolean =
    joined &&
        showComposer &&
        KuiklyThreadTab.fromWireValue(selectedTab) == KuiklyThreadTab.Chat

internal data class SharedThreadRealtimeEventSignal(
    val sequence: Long,
    val event: RealtimeEvent
)

internal data class ThreadRouteUiState(
    val thread: KuiklyThreadScreenPayload,
    val membersLoadStatus: LoadMembersStatus
)

private val KuiklyThreadTab.icon: SlockLucideIconName
    get() =
        when (this) {
            KuiklyThreadTab.Chat -> SlockLucideIconName.MessageSquare
            KuiklyThreadTab.Tasks -> SlockLucideIconName.ListTodo
            KuiklyThreadTab.Files -> SlockLucideIconName.Paperclip
        }

@Composable
private fun ThreadPageScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    var payloadText by remember { mutableStateOf(params.optString("payload")) }
    var instanceId by remember { mutableStateOf(params.optString("instanceId")) }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != THREAD_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let { payloadText = it }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let { instanceId = it }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payload = remember(payloadText, instanceId) {
        parseThreadScreenPayload(
            payload = payloadText,
            instanceId = instanceId
        )
    }
    val realtimeEventMapper = remember { RealtimeEventMapper() }
    var threadRealtimeEventSequence by remember { mutableStateOf(0L) }
    var threadRealtimeEventSignal by remember { mutableStateOf<SharedThreadRealtimeEventSignal?>(null) }
    DisposableEffect(pager, realtimeEventMapper) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != THREAD_REALTIME_EVENT) return
                    if (eventData.optString("action").isNotBlank()) return
                    val event =
                        decodeSharedShellRealtimeEvent(
                            eventData = eventData,
                            mapper = realtimeEventMapper,
                            log = { message -> bridge.log("thread_$message") }
                        ) ?: return
                    if (!RealtimeRoutePolicy.shouldForwardToActiveThread(event)) return
                    threadRealtimeEventSequence += 1
                    threadRealtimeEventSignal =
                        SharedThreadRealtimeEventSignal(
                            sequence = threadRealtimeEventSequence,
                            event = event
                        )
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authStore = sharedStores.authStore
    val threadLocalStore =
        remember(sharedStores, authStore) {
            sharedStores.createThreadLocalDataStore(
                currentServerId = {
                    authStore.readSession()
                        ?.currentServerId
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::ServerId)
                }
            )
        }
    val localWriteThrough =
        remember(threadLocalStore) {
            ComposeLocalWriteThrough(
                threadLocalStore,
                logger = { message -> bridge.logDebug("shared_thread_cache $message") }
            )
        }
    val networkModule = remember { pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME) }
    val networkClient =
        remember {
            KuiklySlockNetworkClient(
                sessionStore = authStore,
                networkModule = networkModule,
                warningLogger = { message -> bridge.log("kuikly_network $message") }
            )
        }
    val agentsApiClient = remember(networkClient) {
        SlockAgentsApiClient(
            transport = SlockAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("thread_agents_api $message") }
        )
    }
    val computersApiClient = remember(networkClient) {
        SlockComputersApiClient(SlockComputersNetworkApi(networkClient))
    }
    val routeScope = rememberCoroutineScope()
    val createAgentCoordinator = remember(agentsApiClient, computersApiClient) {
        ai.slock.compose.data.SharedCreateAgentCoordinator(
            agentsApiClient = agentsApiClient,
            computersApiClient = computersApiClient,
            logger = { message -> bridge.log("thread_create_agent $message") }
        )
    }
    val memberRepository =
        remember(authStore, networkClient) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("thread_member_repository $message") }
            )
        }
    val channelRepository =
        remember(authStore, networkClient) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("thread_channel_repository $message") }
            )
        }
    val membersStore =
        remember(authStore, memberRepository, channelRepository, routeScope) {
            SharedMembersStore(
                authStore = authStore,
                memberRepository = memberRepository,
                channelRepository = channelRepository,
                scope = routeScope,
                logger = { message -> bridge.log(message) }
            )
        }
    val computersStore =
        remember(computersApiClient, routeScope) {
            SharedComputersStore(
                computersApiClient = computersApiClient,
                scope = routeScope,
                logger = { message -> bridge.log("thread_$message") }
            )
        }
    val dataSource =
        remember(networkClient, authStore, localWriteThrough, memberRepository, channelRepository, createAgentCoordinator) {
            SharedThreadDataSource(
                networkApi = NetworkSlockThreadApi(networkClient),
                sessionStore = authStore,
                localWriteThrough = localWriteThrough,
                memberRepository = memberRepository,
                conversationRepository =
                    RealConversationRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        warningLogger = { message -> bridge.log("thread_conversation_repository $message") }
                    ),
                channelRepository = channelRepository,
                agentsApiClient = SlockAgentsApiClient(
                    transport = SlockAgentsNetworkApi(networkClient),
                    diagnosticsLogger = { message -> bridge.log("thread_agents_api $message") }
                ),
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(
                    SlockIntegrationsActionsPushNetworkApi(networkClient)
                ),
                savedStatusRepository =
                    RealSavedRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        warningLogger = { message -> bridge.log("thread_saved_repository $message") }
                    ),
                createAgentCoordinator = createAgentCoordinator,
                diagnosticsLogger = { message -> bridge.log("thread_api $message") }
            )
        }
    val tasksDataSource =
        remember {
            SharedTasksDataSource(
                api = NetworkSlockTasksApi(networkClient)
            )
        }
    val routerModule = remember(pager) {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
    }
    val threadPageCoroutineScope = rememberCoroutineScope()
    fun openStandaloneThreadPayload(nextPayload: KuiklyThreadScreenPayload) {
        val launchPayload = nextPayload.toWireThreadLaunchPayload()
        if (!launchPayload.hasRouteIdentity()) {
            bridge.toast("Unable to open thread")
            bridge.logWarn("thread_open_skip reason=missing_route_identity instanceId=${nextPayload.instanceId}")
            return
        }
        routerModule.openPage(THREAD_PAGE, launchPayload.toThreadPageData())
    }
    SharedThreadRouteContent(
        initialPayload = payload,
        bridge = bridge,
        dataSource = dataSource,
        networkModule = networkModule,
        authStore = authStore,
        tasksDataSource = tasksDataSource,
        computersStore = computersStore,
        membersStore = membersStore,
        onBack = {
            routerModule.closePage()
        },
        onOpenThreadPayload = ::openStandaloneThreadPayload,
        onReplaceThreadPayload = {},
        onAuthRequired = { code ->
            bridge.logWarn("thread_auth_required code=$code route=standalone instanceId=${payload.instanceId}")
            bridge.toast("Session expired")
            threadPageCoroutineScope.launch {
                authStore.clearSession()
            }
            routerModule.openPage(LOGIN_PAGE, JSONObject().apply { put("pageName", LOGIN_PAGE) })
        },
        realtimeEventSignal = threadRealtimeEventSignal
    )
}

@Composable
private fun ThreadMessageVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val visualFixture = params.optString("visualFixture")
    val instanceId = params.optString("instanceId")
    val payload =
        remember(visualFixture, instanceId) {
            threadVisualFixture(fixture = visualFixture.ifBlank { THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT }, instanceId = instanceId)
        }
    val highlightedMentions =
        remember(visualFixture) {
            if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT) setOf("artin") else emptySet()
        }
    val message = payload.messages.firstOrNull()
    Column(modifier = Modifier.width(342.dp).background(SlockColor.SurfaceWhite).padding(SlockSpacing.Md)) {
        if (message != null) {
            ThreadMessageCard(
                message = message,
                highlightedMentions = highlightedMentions,
                alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                showReplyAction = payload.showReplyActions,
                resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                    value = message.taskAssigneeName,
                    id = message.taskAssigneeId
                ),
                resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                taskOpenEnabled = payload.showTaskOpenActions,
                taskStatusActionsEnabled = payload.showTaskStatusActions,
                timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onToggleSaved = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = {},
                onOpenMarkdownUrl = {},
                outerPadding = 0.dp
            )
        }
    }
}

@Composable
private fun ThreadHeaderVisualScreen() {
    val payload = remember {
        KuiklyThreadScreenPayload(
            instanceId = "visual-thread-header",
            title = "#首页专修",
            subtitle = "Home shell, server badge, and bottom dock polish",
            status = "content",
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            selectedTab = "chat",
            showModeTabs = true,
            showSettingsAction = true,
            showMembersAction = true,
            membersCount = 4,
            showOpenInChannelAction = false
        )
    }
    Box(modifier = Modifier.width(390.dp).background(SlockColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            onBack = {},
            onOpenInChannel = {},
            onShowMembers = {},
            onShowSettings = {}
        )
    }
}

@Composable
private fun ChannelMembersPanelVisualScreen() {
    val members = remember { channelPanelVisualMembers() }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        ThreadMembersModal(
            members = members,
            membersCount = members.size,
            canAddMembers = true,
            removeMemberStatus = "idle",
            removingMemberId = null,
            removeMemberErrorCode = null,
            onDismiss = {},
            onAddMember = {},
            onRemoveMember = {}
        )
    }
}

@Composable
private fun ChannelSettingsPanelVisualScreen() {
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        ThreadSettingsModal(
            channelName = "design",
            subtitle = "Product and UI decisions",
            actions = channelPanelVisualSettingsActions(),
            onDismiss = {},
            onOpenAction = {}
        )
    }
}

@Composable
private fun ChannelAddMemberPanelVisualScreen() {
    val members = remember { channelPanelVisualMembers() }
    val candidates = remember { channelPanelVisualCandidates() }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        ThreadAddMemberModal(
            members = members,
            candidates = candidates,
            canAddMembers = true,
            addMemberStatus = "idle",
            addMemberErrorCode = null,
            initialSelectedId = "user_designer",
            onDismiss = {},
            onSubmit = {}
        )
    }
}

private fun channelPanelVisualMembers(): List<KuiklyThreadMemberItem> =
    listOf(
        KuiklyThreadMemberItem(
            id = "agent_android",
            displayName = "Android Developer",
            handle = "Android-Developer",
            roleLabel = "Online",
            avatarLabel = "AD",
            kind = "agent"
        ),
        KuiklyThreadMemberItem(
            id = "user_artin",
            displayName = "Artin",
            handle = "artin",
            roleLabel = "Project owner",
            avatarLabel = "A",
            kind = "human"
        )
    )

private fun channelPanelVisualCandidates(): List<KuiklyThreadMemberItem> =
    listOf(
        KuiklyThreadMemberItem(
            id = "agent_reviewer",
            displayName = "Review Agent",
            handle = "Review-Agent",
            roleLabel = "online",
            avatarLabel = "RA",
            kind = "agent"
        ),
        KuiklyThreadMemberItem(
            id = "user_designer",
            displayName = "Product UX Designer",
            handle = "Product-UX-Designer",
            roleLabel = "Admin",
            avatarLabel = "PU",
            kind = "human"
        )
    )

private fun channelPanelVisualSettingsActions(): List<KuiklyThreadSettingsAction> =
    defaultThreadSettingsChannelActionCapabilities()

@Composable
private fun ThreadMessageVisualCard(
    message: KuiklyThreadMessageItem,
    highlightedMentions: Set<String> = emptySet(),
    resolvedTaskAssigneeName: String? = null,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    knownMarkdownChannelNames: Set<String>? = null,
    timestampLabel: String = message.timestampLabel,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    outerPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Sm
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = outerPadding, vertical = SlockSpacing.Sm)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .padding(horizontal = SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ThreadMessageStaticAvatar(
                    avatarLabel = message.avatarLabel,
                    avatarWireValue = message.resolvedThreadAvatarWireValue(),
                    showStatus = message.showsAgentStatusIndicator()
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.threadMessageContentWidth(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                    ) {
                        Text(
                            text = message.authorName,
                            style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp)
                        )
                        Text(
                            text = timestampLabel,
                            style =
                                SlockType.Caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = SlockColor.TextBlack.copy(alpha = 0.4f)
                                ),
                            color = SlockColor.TextBlack.copy(alpha = 0.4f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (message.body.isNotBlank()) {
                        Box(modifier = Modifier.threadMessageContentWidth(1f)) {
                            SlockMarkdownBody(
                                body = message.body,
                                textStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                                highlightedMentions = highlightedMentions,
                                knownChannelNames = knownMarkdownChannelNames,
                                resolveTagLabel = resolveMarkdownTagLabel,
                                onOpenTag = onOpenMarkdownTag,
                                onOpenUrl = onOpenMarkdownUrl
                            )
                        }
                    }
                    Box(modifier = Modifier.threadMessageContentWidth(1f)) {
                        ThreadMessageAttachments(
                            attachments = message.attachments,
                            alignEnd = false,
                            onOpenAttachment = {}
                        )
                    }
                    Box(modifier = Modifier.threadMessageContentWidth(1f)) {
                        ThreadMessageFooterMeta(
                            message = message,
                            showReplyAction = true,
                            taskOpenEnabled = false,
                            taskStatusActionsEnabled = false,
                            resolvedTaskAssigneeName = resolvedTaskAssigneeName,
                            alignEnd = false,
                            onOpenThread = {},
                            onOpenTask = {},
                            onShowActions = {},
                            onOpenTaskStatusMenu = {}
                        )
                    }
                }
            }
        }
}
}

@Composable
internal fun SharedThreadRouteContent(
    initialPayload: KuiklyThreadScreenPayload,
    bridge: ai.slock.compose.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    networkModule: SlockNetworkModule,
    authStore: SlockAuthSessionStore,
    tasksDataSource: SharedTasksDataSource,
    computersStore: SharedComputersStore? = null,
    membersStore: SharedMembersStore? = null,
    conversationHeaderState: SharedConversationHeaderState = SharedConversationHeaderState(),
    onBack: () -> Unit,
    onOpenThreadPayload: (KuiklyThreadScreenPayload) -> Unit = {},
    onReplaceThreadPayload: (KuiklyThreadScreenPayload) -> Unit = onOpenThreadPayload,
    onOpenParentThread: () -> Boolean = { false },
    onAuthRequired: (String) -> Unit = {},
    realtimeEventSignal: SharedThreadRealtimeEventSignal? = null,
    active: Boolean = true,
    onResolvedAuxiliaryThreadIdChanged: (String?) -> Unit = {},
    onOpenMemberProfile: (KuiklyThreadScreenPayload, String) -> Unit = { _, _ -> }
) {
    val pager = LocalActivity.current.getPager() as Pager
    PerformanceSceneEffect(bridge, "kuikly_thread", active = active)
    val threadViewModel: SharedThreadViewModel =
        viewModel(key = "shared-thread-${initialPayload.sourceThreadId ?: initialPayload.instanceId}") {
            SharedThreadViewModel(dataSource, diagnosticsLogger = { bridge.log(it) })
        }
    val threadState by threadViewModel.state.collectAsState()
    val payload = threadState.payload
    val renderedPayload = remember(payload, conversationHeaderState) {
        payload
            .withSharedShellEventCapabilities()
            .withConversationHeader(conversationHeaderState.entryFor(payload))
    }
    val resolvedAuxiliaryThreadId = payload.resolvedAuxiliaryThreadId()
    val auxiliaryRouteKey = resolvedAuxiliaryThreadId ?: "pending-${initialPayload.instanceId}"
    LaunchedEffect(resolvedAuxiliaryThreadId, active) {
        if (active) {
            onResolvedAuxiliaryThreadIdChanged(resolvedAuxiliaryThreadId)
        }
    }
    var pendingReactionKeys by remember(initialPayload.instanceId) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingActionCardIds by remember(initialPayload.instanceId) { mutableStateOf<Set<String>>(emptySet()) }
    var createAgentDialogState by remember(initialPayload.instanceId) { mutableStateOf<ThreadCreateAgentActionDialogState?>(null) }
    var createAgentComputerServerId by remember(initialPayload.instanceId) { mutableStateOf<String?>(null) }
    var createAgentComputerServerResolved by remember(initialPayload.instanceId) { mutableStateOf(false) }
    var attachmentOpenStates by remember(initialPayload.instanceId) {
        mutableStateOf<Map<String, ThreadAttachmentOpenUiState>>(emptyMap())
    }
    var attachmentCommentsDialogState by remember(initialPayload.instanceId) {
        mutableStateOf<ThreadAttachmentCommentsDialogState?>(null)
    }
    var currentSession by remember(initialPayload.instanceId) {
        mutableStateOf<StoredAuthSession?>(null)
    }
    var currentServerSlug by remember(initialPayload.instanceId) {
        mutableStateOf<String?>(null)
    }
    var firstRenderLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var firstContentLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var performanceSnapshotLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var startedTasksJoinThreadId by remember { mutableStateOf<String?>(null) }
    var lastTasksJoinResultKey by remember { mutableStateOf<String?>(null) }
    var tasksJoinStartedAt by remember { mutableStateOf<TimeMark?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val threadRouteKey =
        payload.sourceThreadId
            ?: initialPayload.sourceThreadId
            ?: payload.instanceId.ifBlank { initialPayload.instanceId }

    LaunchedEffect(authStore, initialPayload.instanceId) {
        val session = authStore.readSession()
        currentSession = session
        currentServerSlug = session?.currentServerSlug()
        createAgentComputerServerId = session?.currentServerId?.takeIf { it.isNotBlank() }
        createAgentComputerServerResolved = true
    }
    LaunchedEffect(initialPayload) {
        bridge.performanceMarker(
            phase = "thread_open_start",
            routeName = "kuikly_thread",
            event = "instant",
            threadId = initialPayload.sourceThreadId ?: initialPayload.instanceId,
            metadata =
                mapOf(
                    "status" to initialPayload.status.ifBlank { "unknown" },
                    "source" to initialPayload.threadRenderDiagnosticsSource()
                )
        )
        pendingActionCardIds = emptySet()
        createAgentDialogState = null
        attachmentOpenStates = emptyMap()
        attachmentCommentsDialogState = null
        threadViewModel.dispatch(SharedThreadIntent.Start(initialPayload))
    }
    LaunchedEffect(threadRouteKey, payload.status, payload.messages.size) {
        if (firstRenderLoggedThreadId != threadRouteKey) {
            firstRenderLoggedThreadId = threadRouteKey
            bridge.performanceMarker(
                phase = "thread_first_render",
                routeName = "kuikly_thread",
                event = "instant",
                threadId = threadRouteKey,
                metadata =
                    mapOf(
                        "status" to payload.status.ifBlank { "unknown" },
                        "messages" to payload.messages.size.toString(),
                        "source" to payload.threadRenderDiagnosticsSource()
                    )
            )
            bridge.log(
                "thread_first_render threadId=${threadRouteKey.orEmpty()} status=${payload.status} " +
                    "messages=${payload.messages.size} source=${payload.threadRenderDiagnosticsSource()}"
            )
        }
        if (payload.isReadyForThreadPerformanceGate() && firstContentLoggedThreadId != threadRouteKey) {
            firstContentLoggedThreadId = threadRouteKey
            bridge.performanceMarker(
                phase = "thread_payload_ready",
                routeName = "kuikly_thread",
                event = "instant",
                threadId = threadRouteKey,
                metadata =
                    mapOf(
                        "status" to payload.status.ifBlank { "unknown" },
                        "source" to payload.threadRenderDiagnosticsSource(),
                        "messages" to payload.messages.size.toString(),
                        "tasks" to payload.tasks.size.toString()
                    )
            )
            bridge.log(
                "thread_payload_ready_gate threadId=${threadRouteKey.orEmpty()} " +
                    "status=${payload.status} messages=${payload.messages.size} " +
                    "source=${payload.threadRenderDiagnosticsSource()}"
            )
            if (performanceSnapshotLoggedThreadId != threadRouteKey) {
                performanceSnapshotLoggedThreadId = threadRouteKey
                delay(ThreadFirstContentTaskJoinDelayMs)
                val firstFrameReason =
                    if (payload.isRenderableEmptyReplyThread()) {
                        "thread_first_empty_reply"
                    } else {
                        "thread_first_content"
                    }
                requestKuiklyThreadPerformanceSnapshot(
                    bridge = bridge,
                    threadId = threadRouteKey.orEmpty(),
                    reason = firstFrameReason,
                    status = payload.status.ifBlank { "unknown" }
                )
            }
        }
    }
    val taskChannelId = resolvedAuxiliaryThreadId?.let(::ChannelId)
    val tasksViewModel: SharedTasksViewModel? =
        taskChannelId?.let { resolvedTaskChannelId ->
            viewModel(key = "shared-thread-tasks-${resolvedTaskChannelId.value}") {
                SharedTasksViewModel(
                    authStore = authStore,
                    dataSource = tasksDataSource,
                    scope = SharedTasksScope.Channel(resolvedTaskChannelId)
                )
            }
        }
    val memberSuggestionScopePayload =
        if (
            payload.threadComposerMentionScopeChannelId() != null ||
            payload.memberScopedMentions ||
            payload.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage
        ) {
            payload
        } else {
            initialPayload
    }
    val memberSuggestionScopeChannelId = memberSuggestionScopePayload.threadComposerMentionScopeChannelId()
    val memberSuggestionScopedMentions = memberSuggestionScopePayload.threadComposerMemberScopedMentions()
    val emptyMemberSuggestionsFlow = remember { MutableStateFlow(emptyList<SharedMembersMemberSuggestion>()) }
    val memberSuggestionsFlow =
        remember(
        membersStore,
        memberSuggestionScopeChannelId,
        memberSuggestionScopedMentions
    ) {
        membersStore?.memberSuggestionSliceFlow(
            mentionChannelId = memberSuggestionScopeChannelId,
            memberScopedMentions = memberSuggestionScopedMentions
        )
            ?.map { slice -> slice.suggestions }
            ?.distinctUntilChanged()
            ?: emptyMemberSuggestionsFlow
    }
    val emptyChannelSuggestionsFlow = remember { MutableStateFlow(emptyList<SharedMembersChannelSuggestion>()) }
    val channelSuggestionsFlow = remember(membersStore) {
        membersStore?.channelSuggestionSliceFlow()
            ?.map { slice -> slice.suggestions }
            ?.distinctUntilChanged()
            ?: emptyChannelSuggestionsFlow
    }
    val filesViewModel: SharedThreadFilesViewModel =
        viewModel(key = "shared-thread-files-$auxiliaryRouteKey") {
            SharedThreadFilesViewModel(dataSource, diagnosticsLogger = { bridge.log(it) })
        }
    val composerDraftViewModel: SharedThreadComposerDraftViewModel =
        viewModel(key = "shared-thread-composer-$auxiliaryRouteKey") {
            SharedThreadComposerDraftViewModel()
        }
    val emptyComputerPickerSliceFlow = remember { MutableStateFlow(SharedComputersPickerSlice()) }
    val computerPickerSlice by remember(computersStore, createAgentComputerServerId) {
        computersStore?.computerPickerSliceFlow(createAgentComputerServerId) ?: emptyComputerPickerSliceFlow
    }.collectAsState(SharedComputersPickerSlice())

    fun routeAuthRequired(code: String? = null): Boolean {
        if (!code.isAuthSessionErrorCode()) return false
        onAuthRequired(code ?: "auth_required")
        return true
    }

    fun routeAuthRequired(result: DataResult.NoPermission): Boolean =
        if (result.blocker.isAuthSessionBlocker()) {
            onAuthRequired(result.blocker.code)
            true
        } else {
            false
        }

    LaunchedEffect(
        computersStore,
        createAgentDialogState?.messageId,
        createAgentComputerServerId,
        createAgentComputerServerResolved
    ) {
        if (createAgentDialogState == null) return@LaunchedEffect
        if (!createAgentComputerServerResolved) return@LaunchedEffect
        val serverId = createAgentComputerServerId
        if (serverId.isNullOrBlank()) return@LaunchedEffect
        computersStore?.dispatch(SharedComputersIntent.EnsureComputerPicker(serverId))
    }

    LaunchedEffect(createAgentDialogState?.messageId, computerPickerSlice.errorCode) {
        if (createAgentDialogState == null) return@LaunchedEffect
        val code = computerPickerSlice.errorCode ?: return@LaunchedEffect
        routeAuthRequired(code)
    }

    LaunchedEffect(composerDraftViewModel, initialPayload.instanceId, resolvedAuxiliaryThreadId) {
        composerDraftViewModel.dispatch(
            SharedThreadComposerDraftIntent.Start(
                renderedPayload
                    .resolveThreadComposer()
                    .copy(streamChannelId = resolvedAuxiliaryThreadId)
            )
        )
    }

    LaunchedEffect(membersStore, memberSuggestionScopeChannelId, memberSuggestionScopedMentions) {
        membersStore?.dispatch(
            SharedMembersIntent.EnsureSuggestions(
                mentionChannelId = memberSuggestionScopeChannelId,
                memberScopedMentions = memberSuggestionScopedMentions
            )
        )
    }
    LaunchedEffect(realtimeEventSignal?.sequence, initialPayload.instanceId) {
        val signal = realtimeEventSignal ?: return@LaunchedEffect
        val activeReplyParentMessageId =
            initialPayload.originParentMessageId
                ?.takeIf { it.isNotBlank() }
                ?.let(::MessageId)
        threadViewModel.dispatch(
            SharedThreadIntent.ApplyRealtimeEvent(
                event = signal.event,
                activeReplyParentMessageId = activeReplyParentMessageId
            )
        )
        if (signal.event.refreshesThreadMemberSuggestions(memberSuggestionScopeChannelId)) {
            membersStore?.dispatch(
                SharedMembersIntent.RefreshSuggestions(
                    mentionChannelId = memberSuggestionScopeChannelId,
                    memberScopedMentions = memberSuggestionScopedMentions
                )
            )
        }
    }
    LaunchedEffect(payload.sourceThreadId, payload.tasks) {
        if (tasksViewModel != null && payload.tasks.isNotEmpty()) {
            val taskSummaries = payload.tasks.map { it.toTaskSummary() }
            tasksViewModel.dispatch(
                SharedTasksRouteIntent.SeedContent(
                    TaskSnapshot(
                        tasks = taskSummaries,
                        summaryCounts = taskSummaries.summaryCounts()
                    )
                )
            )
        }
    }
    LaunchedEffect(threadRouteKey, taskChannelId?.value, payload.status) {
        if (!shouldStartThreadTasksJoin(payload, startedTasksJoinThreadId, taskChannelId)) {
            return@LaunchedEffect
        }
        delay(ThreadFirstContentTaskJoinDelayMs)
        if (!shouldStartThreadTasksJoin(payload, startedTasksJoinThreadId, taskChannelId)) {
            return@LaunchedEffect
        }
        startedTasksJoinThreadId = taskChannelId?.value
        lastTasksJoinResultKey = null
        tasksJoinStartedAt = TimeSource.Monotonic.markNow()
        bridge.log(
            "thread_tasks_join_start threadId=${threadRouteKey.orEmpty()} reason=after_first_content"
        )
        tasksViewModel?.dispatch(SharedTasksRouteIntent.Start)
    }
    ThreadTasksJoinResultLogger(
        tasksViewModel = tasksViewModel,
        taskChannelId = taskChannelId,
        startedTasksJoinThreadId = startedTasksJoinThreadId,
        lastTasksJoinResultKey = lastTasksJoinResultKey,
        tasksJoinStartedAt = tasksJoinStartedAt,
        threadRouteKey = threadRouteKey,
        onLogged = { resultKey -> lastTasksJoinResultKey = resultKey },
        log = { message -> bridge.log(message) }
    )

    val routeFilesPayload =
        remember(payload.filesPayload, payload.instanceId) {
            payload.filesPayload
                .trim()
                .takeIf { it.isNotBlank() && it != "{}" }
                ?.let { parseThreadFilesPayload(it, payload.instanceId) }
        }
    LaunchedEffect(
        renderedPayload.instanceId,
        renderedPayload.sourceThreadId,
        renderedPayload.streamChannelId,
        renderedPayload.filesPayload,
        resolvedAuxiliaryThreadId
    ) {
        if (resolvedAuxiliaryThreadId == null) return@LaunchedEffect
        filesViewModel.dispatch(
            SharedThreadFilesIntent.Start(
                basePayload = renderedPayload,
                initialPayload = routeFilesPayload
            )
        )
    }
    var imagePreviewState by remember(payload.instanceId) { mutableStateOf<ThreadImagePreviewState?>(null) }
    var imagePreviewUrlCache by remember(payload.instanceId) { mutableStateOf<Map<String, ThreadImagePreviewUrl>>(emptyMap()) }
    var attachmentNoPreviewState by remember(payload.instanceId) { mutableStateOf<ThreadAttachmentNoPreviewState?>(null) }
    val baseComposerState =
        remember(
            renderedPayload.composer,
            renderedPayload.instanceId,
            renderedPayload.title,
            renderedPayload.surfaceKind,
            renderedPayload.sourceThreadId,
            renderedPayload.streamChannelId,
            renderedPayload.parentChannelId,
            renderedPayload.parentMessageId,
            renderedPayload.originParentMessageId,
            renderedPayload.canSend
        ) {
            renderedPayload.resolveThreadComposer()
        }
    val scopedBaseComposerState =
        remember(baseComposerState, renderedPayload.canSend, resolvedAuxiliaryThreadId) {
            baseComposerState.copy(
                streamChannelId = resolvedAuxiliaryThreadId,
                enabled = renderedPayload.canSend && resolvedAuxiliaryThreadId != null
            )
        }
    val composerSurface =
        remember(scopedBaseComposerState, resolvedAuxiliaryThreadId, renderedPayload.parentChannelId) {
            scopedBaseComposerState.toThreadComposerSurface(
                channelId = resolvedAuxiliaryThreadId.orEmpty(),
                parentChannelId = renderedPayload.parentChannelId
            )
        }
    fun currentComposerPayload(): KuiklyThreadComposerPayload =
        scopedBaseComposerState.withThreadComposerDraftState(
            draftState = composerDraftViewModel.state.value,
            renderedPayload = renderedPayload,
            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId
        )
    var deferredAttachmentSend by remember(payload.instanceId) {
        mutableStateOf<ThreadDeferredAttachmentSend?>(null)
    }
    val routeUiState =
        remember(renderedPayload, threadState.membersLoadStatus) {
            ThreadRouteUiState(
                thread = renderedPayload,
                membersLoadStatus = threadState.membersLoadStatus
            )
        }
    val renderedThread = routeUiState.thread
    var firstContentExtrasVisible by remember(threadRouteKey, renderedThread.status) {
        mutableStateOf(!renderedThread.shouldStageThreadFirstContentExtras())
    }
    LaunchedEffect(threadRouteKey, renderedThread.status) {
        if (renderedThread.shouldStageThreadFirstContentExtras()) {
            firstContentExtrasVisible = false
            delay(ThreadFirstContentExtrasDelayMs)
            firstContentExtrasVisible = true
        } else {
            firstContentExtrasVisible = true
        }
    }
    val attachmentCommentsEnabled = currentServerSlug.attachmentCommentsEnabledForThread()

    fun setAttachmentCommentsState(next: ThreadAttachmentCommentsDialogState) {
        attachmentCommentsDialogState =
            attachmentCommentsDialogState
                ?.takeIf { current -> current.attachment.id == next.attachment.id }
                ?.let { next }
    }

    fun loadAttachmentCommentsIntoDialog(state: ThreadAttachmentCommentsDialogState) {
        coroutineScope.launch {
            val nextState =
                when (val result = dataSource.loadAttachmentComments(state.attachment.id)) {
                    is DataResult.Content ->
                        state.copy(
                            status = "loaded",
                            page = result.data,
                            sending = false,
                            resolvingCommentIds = emptySet(),
                            errorCode = null
                        )
                    is DataResult.Empty ->
                        state.copy(
                            status = "loaded",
                            page = ThreadAttachmentCommentsPage(comments = emptyList(), threadChannelId = null, viewer = null),
                            sending = false,
                            resolvingCommentIds = emptySet(),
                            errorCode = null
                        )
                    is DataResult.Error ->
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet(),
                            errorCode = result.error.code
                        )
                    is DataResult.NoPermission -> {
                        if (routeAuthRequired(result)) return@launch
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet(),
                            errorCode = result.blocker.code
                        )
                    }
                }
            setAttachmentCommentsState(nextState)
        }
    }

    fun openAttachmentComments(attachmentId: String) {
        if (!attachmentCommentsEnabled) return
        val target = renderedThread.findAttachmentCommentTarget(attachmentId)
        if (target == null) {
            bridge.toast("Comments are unavailable")
            return
        }
        val state =
            ThreadAttachmentCommentsDialogState(
                attachment = target.attachment,
                parentMessage = target.parentMessage,
                status = "loading",
                pendingAnchorLabel = target.attachment.name
            )
        attachmentCommentsDialogState = state
        loadAttachmentCommentsIntoDialog(state)
    }

    fun reloadAttachmentComments() {
        val current = attachmentCommentsDialogState ?: return
        val state = current.copy(status = "loading", errorCode = null)
        attachmentCommentsDialogState = state
        loadAttachmentCommentsIntoDialog(state)
    }

    fun sendAttachmentComment() {
        val current = attachmentCommentsDialogState ?: return
        val cleanDraft = current.draft.trim()
        if (cleanDraft.isBlank() || current.sending) return
        attachmentCommentsDialogState = current.copy(sending = true, errorCode = null)
        coroutineScope.launch {
            when (val result = dataSource.sendAttachmentComment(current.attachment.id, cleanDraft)) {
                is DataResult.Content -> {
                    val loadingState =
                        current.copy(
                            status = "loading",
                            draft = "",
                            sending = false,
                            pendingQuoteLabel = null,
                            errorCode = null
                        )
                    attachmentCommentsDialogState = loadingState
                    loadAttachmentCommentsIntoDialog(loadingState)
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    val code = (result as? DataResult.Error)?.error?.code ?: "thread_attachment_comment_send_failed"
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false, errorCode = code)
                    bridge.toast("Unable to send comment")
                }
                is DataResult.NoPermission -> {
                    if (routeAuthRequired(result)) return@launch
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false, errorCode = result.blocker.code)
                    bridge.toast("Unable to send comment")
                }
            }
        }
    }

    fun resolveAttachmentComment(comment: ThreadAttachmentCommentItem) {
        val current = attachmentCommentsDialogState ?: return
        if (comment.id.isBlank() || comment.isThreadAttachmentCommentResolved()) return
        if (comment.id in current.resolvingCommentIds) return
        attachmentCommentsDialogState =
            current.copy(resolvingCommentIds = current.resolvingCommentIds + comment.id, errorCode = null)
        coroutineScope.launch {
            when (val result = dataSource.setMessageReaction(comment.id, "✅", selected = true)) {
                is DataResult.Content -> {
                    val loadingState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(status = "loading", resolvingCommentIds = emptySet(), errorCode = null)
                            ?: return@launch
                    attachmentCommentsDialogState = loadingState
                    loadAttachmentCommentsIntoDialog(loadingState)
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    val code = (result as? DataResult.Error)?.error?.code ?: "thread_attachment_comment_resolve_failed"
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.let { state ->
                                state.copy(
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id,
                                    errorCode = code
                                )
                            }
                    bridge.toast("Unable to resolve comment")
                }
                is DataResult.NoPermission -> {
                    if (routeAuthRequired(result)) return@launch
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.let { state ->
                                state.copy(
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id,
                                    errorCode = result.blocker.code
                                )
                            }
                    bridge.toast("Unable to resolve comment")
                }
            }
        }
    }

    LaunchedEffect(
        renderedThread.status,
        renderedThread.errorCode,
        renderedThread.olderMessagesStatus,
        renderedThread.newerMessagesStatus
    ) {
        if (
            renderedThread.status == "no_permission" ||
            renderedThread.status == "error" ||
            renderedThread.olderMessagesStatus == "error" ||
            renderedThread.newerMessagesStatus == "error"
        ) {
            routeAuthRequired(renderedThread.errorCode)
        }
    }
    fun requestImagePreviewUrl(attachmentId: String) {
        if (attachmentId in imagePreviewUrlCache) return
        val currentPreview = imagePreviewState ?: return
        if (attachmentId in currentPreview.loadingAttachmentIds) return
        imagePreviewState =
            currentPreview.copy(
                loadingAttachmentIds = currentPreview.loadingAttachmentIds + attachmentId,
                failedAttachmentIds = currentPreview.failedAttachmentIds - attachmentId
            )
        coroutineScope.launch {
            when (val response = dataSource.attachmentUrl(attachmentId)) {
                is ThreadApiResult.Success -> {
                    imagePreviewUrlCache =
                        imagePreviewUrlCache +
                            (attachmentId to ThreadImagePreviewUrl(response.data.url, response.data.expiresAt))
                    imagePreviewState =
                        imagePreviewState?.takeIf { preview -> preview.images.any { it.id == attachmentId } }?.let { preview ->
                            preview.copy(
                                loadingAttachmentIds = preview.loadingAttachmentIds - attachmentId,
                                failedAttachmentIds = preview.failedAttachmentIds - attachmentId
                            )
                        }
                }
                is ThreadApiResult.Failure -> {
                    imagePreviewState =
                        imagePreviewState?.takeIf { preview -> preview.images.any { it.id == attachmentId } }?.let { preview ->
                            preview.copy(
                                loadingAttachmentIds = preview.loadingAttachmentIds - attachmentId,
                                failedAttachmentIds = preview.failedAttachmentIds + attachmentId
                            )
                        }
                    if (imagePreviewState?.current?.id == attachmentId && imagePreviewState?.current?.thumbnailUrl.isNullOrBlank()) {
                        bridge.toast("Unable to load image")
                    }
                }
            }
        }
    }
    fun openImagePreview(group: ThreadImageAttachmentGroup) {
        if (group.images.isEmpty()) return
        val currentIndex = group.currentIndex.coerceIn(0, group.images.lastIndex)
        val attachment = group.images[currentIndex]
        imagePreviewState = null
        attachmentNoPreviewState = null
        pager.openSlockFilePreviewPage(attachment.toThreadAttachmentLazyPreviewPayload(kind = "image"))
    }
    fun selectImagePreviewIndex(index: Int) {
        val preview = imagePreviewState ?: return
        if (preview.images.isEmpty()) return
        val nextIndex = index.coerceIn(0, preview.images.lastIndex)
        imagePreviewState = preview.copy(currentIndex = nextIndex)
        requestImagePreviewUrl(preview.images[nextIndex].id)
    }
    fun openAttachmentExternally(attachment: KuiklyThreadAttachmentItem) {
        if (attachment.id.isBlank() || attachmentOpenStates[attachment.id].threadAttachmentIsOpening()) return
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(status = "downloading"))
        coroutineScope.launch {
            val result =
                openSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment
                )
            if (result.status == "opened") {
                attachmentOpenStates = attachmentOpenStates - attachment.id
            } else {
                attachmentOpenStates =
                    attachmentOpenStates +
                        (attachment.id to ThreadAttachmentOpenUiState(status = result.status, message = result.message))
                bridge.toast(result.message.ifBlank { "Unable to open attachment" })
            }
        }
    }
    fun openAttachmentNoPreview(attachment: KuiklyThreadAttachmentItem) {
        if (attachment.id.isBlank()) return
        imagePreviewState = null
        attachmentNoPreviewState = null
        if (!attachment.isMarkdownAttachment()) {
            attachmentNoPreviewState = ThreadAttachmentNoPreviewState(attachment = attachment)
            return
        }
        pager.openSlockFilePreviewPage(attachment.toThreadAttachmentLazyPreviewPayload(kind = "markdown"))
    }

    fun openComposerAttachmentPreview(attachmentId: String) {
        val attachment = currentComposerPayload().pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        val name = attachment.fileName.ifBlank { "Attachment" }
        val meta = listOf(attachment.sizeLabel, attachment.mimeType.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        if (!attachment.isReadyThreadAttachmentForSend()) {
            pager.openSlockFilePreviewPage(
                SlockFilePreviewPayload(
                    kind = if (attachment.status == "failed") "error" else "loading",
                    title = name,
                    subtitle = meta,
                    name = name,
                    path = name,
                    meta = meta,
                    message =
                        when (attachment.status) {
                            "failed" -> "Attachment upload failed. Retry the upload from the composer."
                            else -> "Preparing attachment preview..."
                        },
                    error =
                        if (attachment.status == "failed") {
                            "attachment_upload_failed"
                        } else {
                            null
                        },
                    externalFileName = name,
                    externalMimeType = attachment.mimeType.orEmpty()
                )
            )
            return
        }
        val threadAttachment =
            KuiklyThreadAttachmentItem(
                id = attachment.id,
                name = name,
                mimeType = attachment.mimeType.orEmpty(),
                sizeLabel = attachment.sizeLabel
            )
        if (threadAttachment.isImageAttachment()) {
            openImagePreview(ThreadImageAttachmentGroup(images = listOf(threadAttachment), currentIndex = 0))
        } else {
            openAttachmentNoPreview(threadAttachment)
        }
    }
    // Render-axis signature: only the fields the render log actually reads.
    // Members / settings / actions / realtime metadata / files payload
    // are intentionally excluded so they do not retrigger the render log.
    // See ThreadRenderSignature / Test.
    val renderSignature = remember(
        renderedThread.instanceId,
        renderedThread.sourceThreadId,
        renderedThread.status,
        renderedThread.selectedTab,
        renderedThread.messages.size,
        renderedThread.tasks.size,
        renderedThread.showReplyActions
    ) {
        renderedThread.toRenderSignature()
    }
    LaunchedEffect(renderSignature) {
        bridge.log(
            "thread_render instanceId=${renderSignature.instanceId} status=${renderSignature.status} tab=${renderSignature.selectedTab} " +
                "messages=${renderSignature.messagesSize} tasks=${renderSignature.tasksSize} " +
                "showReplyActions=${renderSignature.showReplyActions} replyRows=${renderSignature.replyRowsSize} " +
                "firstReply=${if (renderSignature.firstReplyId8 == null) "none" else "${renderSignature.firstReplyId8}:${renderSignature.firstReplyCount}/${renderSignature.firstReplyUnreadCount}"}"
        )
    }
    val visibleReadAllMarkerKey =
        remember(
            renderedThread.sourceThreadId,
            renderedThread.status,
            renderedThread.messages.size,
            renderedThread.messages.lastOrNull()?.id,
            renderedThread.messages.lastOrNull()?.seq
        ) {
            renderedThread.visibleReadAllMarkerKey()
        }
    LaunchedEffect(visibleReadAllMarkerKey) {
        if (visibleReadAllMarkerKey != null) {
            threadViewModel.dispatch(SharedThreadIntent.MarkVisibleReadAll)
        }
    }
    fun sendPendingLocalMessage(
        localMessageId: String,
        context: KuiklyThreadPendingSendContext,
        startedFromComposer: Boolean
    ) {
        val sendStartedAt = TimeSource.Monotonic.markNow()
        bridge.performanceMarker(
            phase = "composer_send",
            routeName = "kuikly_thread",
            event = "start",
            threadId = context.channelId,
            metadata =
                mapOf(
                    "asTask" to context.asTask.toString(),
                    "attachments" to context.attachmentIds.size.toString(),
                    "hasText" to context.body.isNotBlank().toString(),
                    "localMessageId" to localMessageId
                )
        )
        coroutineScope.launch {
            fun markFailed(errorCode: String?) {
                threadViewModel.dispatch(SharedThreadIntent.ApplyLocalSendFailure(localMessageId, errorCode))
                if (startedFromComposer) {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
                }
            }

            val sendResponse =
                runCatchingPreservingCancellation {
                    dataSource.sendMessage(
                        channelId = context.channelId,
                        body = context.body,
                        asTask = context.asTask,
                        attachmentIds = context.attachmentIds
                    )
                }.getOrElse {
                    bridge.performanceMarker(
                        phase = "composer_send",
                        routeName = "kuikly_thread",
                        event = "end",
                        threadId = context.channelId,
                        durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                        metadata = mapOf("result" to "exception", "localMessageId" to localMessageId)
                    )
                    markFailed("exception")
                    return@launch
                }
            val sentPage =
                when (sendResponse) {
                    is ThreadApiResult.Success -> sendResponse.data
                    is ThreadApiResult.Failure -> {
                        val errorCode =
                            sendResponse.contractCode
                                ?: sendResponse.errorBodyText?.takeIf { it.isNotBlank() }
                                ?: "send_failed"
                        bridge.performanceMarker(
                            phase = "composer_send",
                            routeName = "kuikly_thread",
                            event = "end",
                            threadId = context.channelId,
                            durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                            metadata =
                                mapOf(
                                    "result" to "failure",
                                    "code" to errorCode,
                                    "localMessageId" to localMessageId
                                )
                        )
                        markFailed(errorCode)
                        return@launch
                    }
                }
            if (sentPage.messages.isEmpty()) {
                bridge.performanceMarker(
                    phase = "composer_send",
                    routeName = "kuikly_thread",
                    event = "end",
                    threadId = context.channelId,
                    durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                    metadata = mapOf("result" to "empty", "localMessageId" to localMessageId)
                )
                markFailed("empty_response")
                return@launch
            }
            threadViewModel.dispatch(SharedThreadIntent.ReplaceLocalSendWithSentPage(localMessageId, sentPage))
            threadViewModel.dispatch(SharedThreadIntent.Refresh)
            bridge.performanceMarker(
                phase = "composer_send",
                routeName = "kuikly_thread",
                event = "end",
                threadId = context.channelId,
                durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                metadata =
                    mapOf(
                        "result" to "success",
                        "messages" to sentPage.messages.size.toString(),
                        "localMessageId" to localMessageId
                    )
            )
            val pendingMentionActions = sentPage.pendingMentionActions.map { it.toKuiklyPendingMentionActionItem() }
            if (startedFromComposer) {
                composerDraftViewModel.dispatch(
                    SharedThreadComposerDraftIntent.CompleteSend(pendingMentionActions = pendingMentionActions)
                )
            } else {
                composerDraftViewModel.dispatch(
                    SharedThreadComposerDraftIntent.ApplyPendingMentionActions(pendingMentionActions)
                )
            }
        }
    }
    fun markDeferredAttachmentSendFailed(errorCode: String?) {
        val pending = deferredAttachmentSend ?: return
        deferredAttachmentSend = null
        threadViewModel.dispatch(SharedThreadIntent.ApplyLocalSendFailure(pending.localMessageId, errorCode))
        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
    }
    fun handleAttachmentUploadFailure(errorCode: String) {
        if (deferredAttachmentSend != null) {
            markDeferredAttachmentSendFailed(errorCode)
        } else {
            bridge.toast("Attachment upload failed: $errorCode")
        }
    }
    LaunchedEffect(
        deferredAttachmentSend?.localMessageId,
        scopedBaseComposerState,
        renderedPayload,
        resolvedAuxiliaryThreadId
    ) {
        val pendingLocalMessageId = deferredAttachmentSend?.localMessageId ?: return@LaunchedEffect
        val readyComposer =
            composerDraftViewModel.state
                .map { draftState ->
                    scopedBaseComposerState.withThreadComposerDraftState(
                        draftState = draftState,
                        renderedPayload = renderedPayload,
                        resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId
                    )
                }
                .first { composer ->
                    val pending = deferredAttachmentSend?.takeIf { it.localMessageId == pendingLocalMessageId }
                        ?: return@first false
                    if (composer.pendingAttachments.hasBlockingThreadAttachmentUpload()) {
                        return@first false
                    }
                    val attachmentIds = composer.pendingAttachments.readyThreadAttachmentIdsForSend()
                    pending.context.attachments.isEmpty() || attachmentIds.isNotEmpty()
                }
        val pending = deferredAttachmentSend?.takeIf { it.localMessageId == pendingLocalMessageId }
            ?: return@LaunchedEffect
        val attachmentIds = readyComposer.pendingAttachments.readyThreadAttachmentIdsForSend()
        val readyContext =
            pending.context.copy(
                attachmentIds = attachmentIds,
                attachments = readyComposer.pendingAttachments
            )
        deferredAttachmentSend = null
        threadViewModel.dispatch(
            SharedThreadIntent.ApplyLocalSendStarted(
                buildThreadPendingSendMessage(
                    localMessageId = pending.localMessageId,
                    context = readyContext,
                    session = currentSession
                )
            )
        )
        sendPendingLocalMessage(pending.localMessageId, readyContext, startedFromComposer = true)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        ThreadPageContent(
            payload = renderedThread,
            composerSurface = composerSurface,
            baseComposerState = scopedBaseComposerState,
            composerDraftViewModel = composerDraftViewModel,
            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
            tasksViewModel = tasksViewModel,
            taskChannelId = taskChannelId,
            filesViewModel = filesViewModel,
            memberSuggestionsFlow = memberSuggestionsFlow,
            channelSuggestionsFlow = channelSuggestionsFlow,
            contentExtrasVisible = firstContentExtrasVisible,
            onTasksIntent = { intent -> tasksViewModel?.dispatch(intent) },
            onFilesAuthRequired = { code -> routeAuthRequired(code) },
            onOpenFilesAttachment = { attachment, imageGroup ->
                if (imageGroup != null) {
                    openImagePreview(imageGroup)
                } else {
                    openAttachmentNoPreview(attachment)
                }
            },
            onDownloadFilesAttachment = { attachment -> openAttachmentExternally(attachment) },
            onOpenMemberProfile = { memberId -> onOpenMemberProfile(renderedThread, memberId) },
            highlightedMentions = threadState.highlightedMentions,
            diagnosticsLogger = { bridge.log(it) },
            pendingActionCardIds = pendingActionCardIds,
            attachmentOpenStates = attachmentOpenStates,
            attachmentCommentsEnabled = attachmentCommentsEnabled,
            currentServerSlug = currentServerSlug,
            onBack = onBack,
            onThreadEvent = { action, itemId, attachmentId, tab ->
                when {
                    action == "openAttachment" && !attachmentId.isNullOrBlank() -> {
                        val imageGroup =
                            renderedThread.findThreadImageAttachmentGroup(attachmentId)
                        if (imageGroup != null) {
                            openImagePreview(imageGroup)
                        } else {
                            val attachment =
                                renderedThread.findThreadAttachment(attachmentId)
                                    ?: KuiklyThreadAttachmentItem(id = attachmentId, name = "Attachment")
                            openAttachmentNoPreview(attachment)
                        }
                    }
                    action == "downloadAttachment" && !attachmentId.isNullOrBlank() -> {
                        val attachment =
                            renderedThread.findThreadAttachment(attachmentId)
                                ?: KuiklyThreadAttachmentItem(id = attachmentId, name = "Attachment")
                        openAttachmentExternally(attachment)
                    }
                    action == "openAttachmentComments" && !attachmentId.isNullOrBlank() -> {
                        openAttachmentComments(attachmentId)
                    }
                action == "copyMessage" && !itemId.isNullOrBlank() -> {
                    val body = renderedThread.messages.firstOrNull { it.id == itemId }?.body.orEmpty()
                    if (body.isNotBlank()) {
                        bridge.copyToPasteboard(body)
                        bridge.toast("Copied")
                    }
                }
                action == "openMessageThread" && !itemId.isNullOrBlank() -> {
                    val parentMessage =
                        renderedThread.messages.firstOrNull { it.id == itemId }
                            ?: renderedThread.originMessage?.takeIf { it.id == itemId }
                    if (parentMessage == null) {
                        bridge.toast("Unable to open replies")
                        return@ThreadPageContent
                    }
                    val loadingPayload =
                        renderedThread.replyThreadLoadingPayload(
                            parentMessage = parentMessage,
                            focusedMessageId = parentMessage.firstUnreadReplyMessageId
                        )
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyReplyThreadRead(
                            parentMessageId = parentMessage.id,
                            threadChannelId = parentMessage.threadChannelId
                        )
                    )
                    onOpenThreadPayload(loadingPayload)
                    coroutineScope.launch {
                        when (val result = dataSource.createOrOpenReplyThread(renderedThread, parentMessage)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyReplyThreadRead(
                                        parentMessageId = parentMessage.id,
                                        threadChannelId = result.data.streamChannelId ?: result.data.sourceThreadId
                                    )
                                )
                                onReplaceThreadPayload(result.data)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_reply_open_failed")
                                )
                                bridge.toast("Unable to open replies")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_reply_open_failed")
                                )
                                bridge.toast("Unable to open replies")
                            }
                        }
                    }
                }
                action == "openParentMessage" -> {
                    if (!onOpenParentThread()) {
                        val parentMessageId =
                            itemId?.takeIf { it.isNotBlank() }
                                ?: renderedThread.originParentMessageId?.takeIf { it.isNotBlank() }
                                ?: renderedThread.parentMessageId?.takeIf { it.isNotBlank() }
                        if (parentMessageId.isNullOrBlank()) {
                            bridge.toast("Unable to open parent message")
                            return@ThreadPageContent
                        }
                        val loadingPayload = renderedThread.parentMessageContextLoadingPayload(parentMessageId)
                        onOpenThreadPayload(loadingPayload)
                        coroutineScope.launch {
                            when (val result = dataSource.openMessageContext(parentMessageId, loadingPayload)) {
                                is DataResult.Content -> onReplaceThreadPayload(result.data)
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    onReplaceThreadPayload(
                                        loadingPayload.copy(status = "error", errorCode = "thread_parent_context_failed")
                                    )
                                    bridge.toast("Unable to open parent message")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    onReplaceThreadPayload(
                                        loadingPayload.copy(status = "error", errorCode = "thread_parent_context_failed")
                                    )
                                    bridge.toast("Unable to open parent message")
                                }
                            }
                        }
                    }
                }
                action == "toggleMessageSaved" && !itemId.isNullOrBlank() -> {
                    val previousSaved =
                        renderedThread.threadActionMessage(itemId)?.isSaved
                            ?: renderedThread.originMessage?.takeIf { it.id == itemId }?.isSaved
                            ?: false
                    val nextSaved = !previousSaved
                    threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, nextSaved))
                    coroutineScope.launch {
                        val result =
                            if (nextSaved) {
                                dataSource.saveMessage(itemId)
                            } else {
                                dataSource.unsaveMessage(itemId)
                            }
                        when (result) {
                            is DataResult.Content -> Unit
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, previousSaved))
                                bridge.toast(if (nextSaved) "Unable to save message" else "Unable to remove saved message")
                            }
                            is DataResult.NoPermission -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, previousSaved))
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast(if (nextSaved) "Unable to save message" else "Unable to remove saved message")
                            }
                        }
                    }
                }
                action == "commitActionCard" && !itemId.isNullOrBlank() -> {
                    if (itemId in pendingActionCardIds) {
                        return@ThreadPageContent
                    }
                    val actionCard =
                        renderedThread.messages.firstOrNull { it.id == itemId }?.threadActionCard()
                            ?: renderedThread.originMessage?.takeIf { it.id == itemId }?.threadActionCard()
                    if (actionCard == null) {
                        bridge.toast("Action is no longer available")
                        return@ThreadPageContent
                    }
                    if (!actionCard.isSupportedCommitAction()) {
                        bridge.toast("Action is not supported yet")
                        return@ThreadPageContent
                    }
                    if (actionCard.type.equals("agent:create", ignoreCase = true)) {
                        val dialogState =
                            ThreadCreateAgentActionDialogState(
                                messageId = itemId,
                                card = actionCard,
                                submitting = false,
                                errorCode = null
                        )
                        createAgentDialogState = dialogState
                        return@ThreadPageContent
                    }
                    pendingActionCardIds = pendingActionCardIds + itemId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.commitThreadActionCard(renderedThread, itemId, actionCard)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyActionCard(itemId, result.data)
                                    )
                                    bridge.toast(actionCard.actionCardSuccessToast())
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast(actionCard.actionCardFailureToast())
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast(actionCard.actionCardFailureToast())
                                }
                            }
                        } finally {
                            pendingActionCardIds = pendingActionCardIds - itemId
                        }
                    }
                }
                action == "toggleReaction" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    val reactionKey = "$itemId:$tab"
                    if (reactionKey in pendingReactionKeys) {
                        return@ThreadPageContent
                    }
                    val previousPayload = renderedThread
                    val toggleTarget = previousPayload.threadReactionToggleTarget(itemId, tab)
                    if (toggleTarget == null) {
                        return@ThreadPageContent
                    }
                    val nextSelected = toggleTarget.nextSelected
                    val previousReactions = toggleTarget.message.reactions
                    pendingReactionKeys = pendingReactionKeys + reactionKey
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyMessageReactionSelection(itemId, tab, selected = nextSelected)
                    )
                    bridge.log(
                        "thread_reaction_toggle messageId=$itemId emoji=$tab selected=$nextSelected"
                    )
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.setMessageReaction(itemId, tab, selected = nextSelected)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(SharedThreadIntent.ApplyMessageMutation(result.data))
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageReactions(itemId, previousReactions)
                                    )
                                    bridge.toast("Unable to update reaction")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageReactions(itemId, previousReactions)
                                    )
                                    bridge.toast("Unable to update reaction")
                                }
                            }
                        } finally {
                            pendingReactionKeys = pendingReactionKeys - reactionKey
                        }
                    }
                }
                action == "openTask" && !itemId.isNullOrBlank() -> {
                    val parentMessage = renderedThread.findTaskParentMessage(itemId)
                    if (parentMessage == null) {
                        bridge.toast("Unable to open task")
                        return@ThreadPageContent
                    }
                    val loadingPayload =
                        renderedThread.replyThreadLoadingPayload(
                            parentMessage = parentMessage,
                            focusedMessageId = parentMessage.firstUnreadReplyMessageId
                        )
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyReplyThreadRead(
                            parentMessageId = parentMessage.id,
                            threadChannelId = parentMessage.threadChannelId
                        )
                    )
                    onOpenThreadPayload(loadingPayload)
                    coroutineScope.launch {
                        when (val result = dataSource.createOrOpenReplyThread(renderedThread, parentMessage)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyReplyThreadRead(
                                        parentMessageId = parentMessage.id,
                                        threadChannelId = result.data.streamChannelId ?: result.data.sourceThreadId
                                    )
                                )
                                onReplaceThreadPayload(result.data)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed")
                                )
                                bridge.toast("Unable to open task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed")
                                )
                                bridge.toast("Unable to open task")
                            }
                        }
                    }
                }
                action == "openCommentHost" && !itemId.isNullOrBlank() -> {
                    val commentRef =
                        renderedThread.threadActionMessage(itemId)?.commentRef
                            ?: renderedThread.messages.firstOrNull { it.id == itemId }?.commentRef
                            ?: renderedThread.originMessage?.takeIf { it.id == itemId }?.commentRef
                    val hostMessageId = commentRef?.hostMessageId?.takeIf { it.isNotBlank() }
                    val loadingPayload = commentRef?.let { renderedThread.commentHostLoadingPayload(it) }
                    if (hostMessageId == null || loadingPayload == null) {
                        bridge.toast("Comment source unavailable")
                        return@ThreadPageContent
                    }
                    onOpenThreadPayload(loadingPayload)
                    coroutineScope.launch {
                        when (val result = dataSource.openCommentHost(hostMessageId, loadingPayload)) {
                            is DataResult.Content -> onReplaceThreadPayload(result.data)
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_comment_host_open_failed")
                                )
                                bridge.toast("Unable to open comment source")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_comment_host_open_failed")
                                )
                                bridge.toast("Unable to open comment source")
                            }
                        }
                    }
                }
                action == "openMarkdownUrl" && !itemId.isNullOrBlank() -> {
                    val target = itemId.toSlockThreadWebLinkTarget()
                    if (target == null) {
                        bridge.openUrl(itemId, null)
                    } else {
                        val loadingPayload = renderedThread.webLinkLoadingPayload(target)
                        onOpenThreadPayload(loadingPayload)
                        coroutineScope.launch {
                            when (val result = dataSource.fetchWebLinkThreadPayload(loadingPayload, target)) {
                                is DataResult.Content -> onReplaceThreadPayload(result.data)
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    onReplaceThreadPayload(
                                        loadingPayload.copy(status = "error", errorCode = "thread_weblink_open_failed")
                                    )
                                    bridge.toast("Unable to open link")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    onReplaceThreadPayload(
                                        loadingPayload.copy(status = "error", errorCode = "thread_weblink_open_failed")
                                    )
                                    bridge.toast("Unable to open link")
                                }
                            }
                        }
                    }
                }
                action == "openMarkdownTag" && !itemId.isNullOrBlank() -> {
                    bridge.toast("Unable to open ${itemId.take(32)}")
                }
                action == "openMarkdownChannel" && !itemId.isNullOrBlank() -> {
                    bridge.toast("Unable to open #${itemId.removePrefix("#").take(31)}")
                }
                action == "openMarkdownThreadRef" && !itemId.isNullOrBlank() -> {
                    bridge.toast("Unable to open ${itemId.take(32)}")
                }
                action == "submitAddMember" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedThread
                    threadViewModel.dispatch(
                        SharedThreadIntent.SetAddMemberStatus(status = "adding", errorCode = null)
                    )
                    coroutineScope.launch {
                        when (val result = dataSource.addChannelMember(previousPayload, itemId, tab)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyMembersPatch(
                                        membersCount = result.data.membersCount,
                                        members = result.data.members,
                                        memberCandidates = result.data.memberCandidates,
                                        canAddMembers = result.data.canAddMembers
                                    )
                                )
                                bridge.toast("Member added")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.toast("Unable to add member")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.toast("Unable to add member")
                            }
                        }
                    }
                }
                action == "removeMember" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedThread
                    val member = previousPayload.members.firstOrNull { it.id == itemId }
                    threadViewModel.dispatch(
                        SharedThreadIntent.SetRemoveMemberStatus(
                            status = "removing",
                            removingMemberId = itemId,
                            errorCode = null
                        )
                    )
                    coroutineScope.launch {
                        when (val result = dataSource.removeChannelMember(previousPayload, itemId, member?.kind)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyMembersPatch(
                                        membersCount = result.data.membersCount,
                                        members = result.data.members,
                                        memberCandidates = result.data.memberCandidates,
                                        canAddMembers = result.data.canAddMembers
                                    )
                                )
                                bridge.toast("Member removed")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                val errorCode = result.threadActionErrorCode("member_remove_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetRemoveMemberStatus(
                                        status = "failed",
                                        removingMemberId = itemId,
                                        errorCode = errorCode
                                    )
                                )
                                bridge.toast("Unable to remove member")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                val errorCode = result.threadActionErrorCode("member_remove_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetRemoveMemberStatus(
                                        status = "failed",
                                        removingMemberId = itemId,
                                        errorCode = errorCode
                                    )
                                )
                                bridge.toast("Unable to remove member")
                            }
                        }
                    }
                }
                action == "settingsAction" && itemId == "visibility" -> {
                    bridge.toast("Channel visibility updates are not wired yet")
                }
                action == "settingsAction" && itemId == "archive" -> {
                    val previousPayload = renderedThread
                    coroutineScope.launch {
                        when (val result = dataSource.archiveChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast("Channel archived")
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast("Unable to archive channel")
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast("Unable to archive channel")
                            }
                        }
                    }
                }
                action == "settingsAction" && itemId == "delete" -> {
                    val previousPayload = renderedThread
                    coroutineScope.launch {
                        when (val result = dataSource.deleteChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast("Channel deleted")
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast("Unable to delete channel")
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast("Unable to delete channel")
                            }
                        }
                    }
                }
                action == "setTaskStatus" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    val status = tab.toSharedThreadTaskStatus()
                    if (status == null) {
                        bridge.toast("Unsupported task status")
                        return@ThreadPageContent
                    }
                    val previousPayload = renderedThread
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, status.name))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, status)) {
                            is DataResult.Content -> {
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "markTaskDone" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedThread
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, TaskStatus.Done.name))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Done)) {
                            is DataResult.Content -> {
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "reopenTask" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedThread
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, TaskStatus.Todo.name))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Todo)) {
                            is DataResult.Content -> {
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "selectTab" -> Unit
                action == "loadMembers" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadMembers)
                }
                action == "loadOlder" || action == "retryLoadOlder" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadOlder)
                }
                action == "loadNewer" || action == "retryLoadNewer" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadNewer)
                }
                action == "retryLocalSend" && !itemId.isNullOrBlank() -> {
                    val message = renderedThread.messages.firstOrNull { it.id == itemId } ?: return@ThreadPageContent
                    val pendingContext = message.pendingSendContext ?: return@ThreadPageContent
                    if (!message.canRetryLocalSend()) {
                        return@ThreadPageContent
                    }
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyLocalSendStarted(
                            message.copy(
                                deliveryState = ThreadMessageDeliverySending,
                                pendingSendErrorCode = null
                            )
                        )
                    )
                    sendPendingLocalMessage(message.id, pendingContext, startedFromComposer = false)
                }
                else -> {
                    bridge.log(
                        "thread_event_unsupported instanceId=${payload.instanceId} action=$action itemId=${itemId.orEmpty()} attachmentId=${attachmentId.orEmpty()} tab=${tab.orEmpty()}"
                    )
                }
                }
            },
            onComposerEvent = { action, value, asTask, attachmentId ->
                val currentComposer = currentComposerPayload()
                val currentDraftState = composerDraftViewModel.state.value
                val composerInstanceId = currentComposer.instanceId.ifBlank { payload.instanceId }
                when (action) {
                "change" -> {
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(
                            seedText = value,
                            seedAsTask = asTask
                        )
                    )
                }
                "toggleAsTask" -> {
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(seedAsTask = asTask)
                    )
                }
                "send" -> {
                    val threadId = composerSurface.sendChannelId
                    val draftText = currentDraftState.seedText.trim()
                    val attachmentIds = currentComposer.pendingAttachments.readyThreadAttachmentIdsForSend()
                    val hasBlockingAttachmentUpload = currentComposer.pendingAttachments.hasBlockingThreadAttachmentUpload()
                    if (!payload.canSend || threadId == null ||
                        (draftText.isBlank() && attachmentIds.isEmpty() && !hasBlockingAttachmentUpload)
                    ) {
                        return@ThreadPageContent
                    }
                    val draftAsTask = currentDraftState.seedAsTask
                    val pendingAttachments =
                        if (hasBlockingAttachmentUpload) {
                            currentComposer.pendingAttachments.withThreadAttachmentUploadWaitingState()
                        } else {
                            currentComposer.pendingAttachments
                        }
                    val pendingContext =
                        KuiklyThreadPendingSendContext(
                            channelId = threadId,
                            body = draftText,
                            asTask = draftAsTask,
                            attachmentIds = attachmentIds,
                            attachments = pendingAttachments,
                            mentionChannelId = composerSurface.mentionScopeChannelId,
                            parentMessageId = composerSurface.parentMessageId,
                            streamChannelId = composerSurface.streamChannelId,
                            memberScopedMentions = payload.memberScopedMentions
                        )
                    val localMessageId = ThreadLocalSendNextId.value(composerInstanceId)
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyLocalSendStarted(
                            buildThreadPendingSendMessage(
                                localMessageId = localMessageId,
                                context = pendingContext,
                                session = currentSession
                            )
                        )
                    )
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.BeginSend)
                    if (hasBlockingAttachmentUpload) {
                        deferredAttachmentSend =
                            ThreadDeferredAttachmentSend(
                                localMessageId = localMessageId,
                                context = pendingContext
                            )
                    } else {
                        sendPendingLocalMessage(localMessageId, pendingContext, startedFromComposer = true)
                    }
                }
                "dismissMention" -> {
                    value?.takeIf { it.isNotBlank() }?.let { resolutionId ->
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.DismissMentionAction(resolutionId))
                    }
                }
                "inviteMention", "notifyMention" -> {
                    val resolutionId = value?.takeIf { it.isNotBlank() } ?: return@ThreadPageContent
                    if (resolutionId in currentDraftState.executingMentionActionIds) {
                        return@ThreadPageContent
                    }
                    val pendingAction =
                        currentDraftState.pendingMentionActions.firstOrNull { it.resolutionId == resolutionId }
                            ?: return@ThreadPageContent
                    val verb = if (action == "inviteMention") "invite" else "notify"
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.BeginMentionAction(resolutionId))
                    coroutineScope.launch {
                        val result =
                            runCatchingPreservingCancellation {
                                dataSource.executePendingMentionActions(
                                    action = verb,
                                    resolutionIds = listOf(resolutionId)
                                )
                            }.getOrNull()
                        val rowResult =
                            (result as? ThreadApiResult.Success)
                                ?.data
                                ?.firstOrNull { it.resolutionId == resolutionId }
                        val delivered = rowResult?.status == "delivered"
                        val notice =
                            if (delivered) {
                                pendingAction.mentionActionDeliveredNotice(verb, composerSurface.mentionScopeChannelLabel())
                            } else {
                                formatMentionActionStatusNotice(rowResult)
                            }
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.FinishMentionAction(
                                resolutionId = resolutionId,
                                notice = notice,
                                delivered = delivered
                            )
                        )
                    }
                }
                "retryThreadLoad" -> {
                    threadViewModel.dispatch(SharedThreadIntent.Refresh)
                }
                "attachImage", "attachFile" -> {
                    coroutineScope.launch {
                        val channelId = composerSurface.sendChannelId
                        if (channelId == null) {
                            bridge.toast("Attachment picker unavailable")
                            return@launch
                        }
                        val response =
                            bridge.threadAttachmentUpload(
                                channelId = channelId,
                                imageOnly = action == "attachImage"
                            )
                        val files = parseSharedThreadPickedAttachmentFiles(response)
                        if (files.isEmpty()) {
                            val errorCode = response?.optString("errorCode")?.takeIf { it.isNotBlank() }
                            when {
                                errorCode != null ->
                                    bridge.toast("Attachment upload failed: $errorCode")
                                response == null ->
                                    bridge.toast("Attachment picker unavailable")
                            }
                            return@launch
                        }
                        val localAttachments =
                            files
                                .filter { file -> file.id.isNotBlank() }
                                .map { file ->
                                    SharedThreadComposerAttachment(
                                        id = file.id,
                                        fileName = file.fileName,
                                        sizeLabel = file.sizeBytes?.let(::formatAttachmentSizeLabel).orEmpty(),
                                        mimeType = file.mimeType?.takeIf { it.isNotBlank() },
                                        status = "uploading"
                                    )
                                }
                        if (localAttachments.isNotEmpty()) {
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.AddLocalAttachments(localAttachments)
                            )
                        }
                        when (val uploadResult = dataSource.uploadAttachments(channelId, files)) {
                            is DataResult.Content -> {
                                val replacedAttachmentIds = files.map { file -> file.id }.filter { it.isNotBlank() }.toSet()
                                val uploaded =
                                    uploadResult.data.attachments.map { attachment ->
                                        SharedThreadComposerAttachment(
                                            id = attachment.id.value,
                                            fileName = attachment.name,
                                            sizeLabel = attachment.sizeLabel,
                                            mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                            status = "ready"
                                        )
                                    }
                                if (uploaded.isEmpty()) {
                                    handleAttachmentUploadFailure("empty_upload_result")
                                    return@launch
                                }
                                composerDraftViewModel.dispatch(
                                    SharedThreadComposerDraftIntent.AddUploadedAttachments(
                                        attachments = uploaded,
                                        replacedAttachmentIds = replacedAttachmentIds
                                    )
                                )
                            }
                            is DataResult.Error ->
                                handleAttachmentUploadFailure(uploadResult.error.code)
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(uploadResult)) return@launch
                                handleAttachmentUploadFailure(uploadResult.blocker.code)
                            }
                            is DataResult.Empty ->
                                handleAttachmentUploadFailure("empty_upload_result")
                        }
                    }
                }
                "removeAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(id))
                    }
                }
                "retryAttachments" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(id))
                    }
                }
                "previewAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let(::openComposerAttachmentPreview)
                }
                "blur" -> {
                    bridge.log(
                        "thread_composer_blur instanceId=$composerInstanceId"
                    )
                }
                else -> {
                    bridge.log(
                        "thread_composer_event_unsupported instanceId=$composerInstanceId action=$action attachmentId=${attachmentId.orEmpty()}"
                    )
                }
                }
            }
        )
        imagePreviewState?.let { preview ->
            ThreadImagePreviewOverlay(
                state = preview,
                resolvedUrls = imagePreviewUrlCache,
                onDismiss = { imagePreviewState = null },
                onOpenExternally = { attachment ->
                    openAttachmentExternally(attachment)
                },
                onSelectIndex = ::selectImagePreviewIndex
            )
        }
        attachmentNoPreviewState?.let { preview ->
            ThreadAttachmentNoPreviewOverlay(
                state = preview,
                openState = attachmentOpenStates[preview.attachment.id],
                onDismiss = { attachmentNoPreviewState = null },
                onOpenWithSystem = { openAttachmentExternally(preview.attachment) },
                onOpenUrl = { bridge.openUrl(it, null) }
            )
        }
        attachmentCommentsDialogState?.let { dialogState ->
            ThreadAttachmentCommentsDialog(
                state = dialogState,
                onDismiss = { attachmentCommentsDialogState = null },
                onFilterChange = { filter ->
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == dialogState.attachment.id }
                            ?.copy(filter = filter)
                },
                onDraftChange = { draft ->
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == dialogState.attachment.id }
                            ?.copy(draft = draft, errorCode = null)
                },
                onRetry = ::reloadAttachmentComments,
                onSend = ::sendAttachmentComment,
                onResolve = ::resolveAttachmentComment,
                onQuote = { comment ->
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == dialogState.attachment.id }
                            ?.let { current ->
                                val quote = formatThreadMessageQuote(comment.content)
                                current.copy(
                                    draft = appendThreadQuoteToComposer(current.draft, quote),
                                    pendingQuoteLabel = comment.senderName,
                                    pendingAnchorLabel = comment.anchorLabel ?: current.attachment.name,
                                    errorCode = null
                                )
                            }
                }
            )
        }
        createAgentDialogState?.let { dialogState ->
            val machinesErrorCode =
                when {
                    computersStore != null &&
                        createAgentComputerServerResolved &&
                        createAgentComputerServerId.isNullOrBlank() -> "server_id_missing"
                    computerPickerSlice.status == SharedComputersLoadStatus.Error -> computerPickerSlice.errorCode
                    else -> null
                }
            SharedCreateAgentDialog(
                submitting = dialogState.submitting,
                errorMessage = dialogState.errorCode?.let(::threadCreateAgentDialogErrorCopy),
                initialName = dialogState.card.name.removePrefix("@"),
                initialDescription = dialogState.card.description.orEmpty(),
                draftHint = dialogState.card.draftHint,
                suggestedComputer = dialogState.card.suggestedComputer,
                requiredComputer = dialogState.card.requiredComputer,
                machines = computerPickerSlice.machines,
                machinesLoading =
                    computersStore != null &&
                        (!createAgentComputerServerResolved ||
                            computerPickerSlice.status == SharedComputersLoadStatus.InFlight),
                machinesErrorMessage = machinesErrorCode?.let(::threadCreateAgentMachinesErrorCopy),
                onDismiss = {
                    if (!dialogState.submitting) {
                        createAgentDialogState = null
                    }
                },
                onSubmit = { request ->
                    if (dialogState.submitting) {
                        return@SharedCreateAgentDialog
                    }
                    val normalizedName = request.name.trim().removePrefix("@")
                    if (normalizedName.isBlank()) {
                        createAgentDialogState = dialogState.copy(errorCode = "agent_name_required")
                        return@SharedCreateAgentDialog
                    }
                    val submitCard =
                        dialogState.card.copy(
                            name = normalizedName,
                            description = request.description,
                            suggestedComputer =
                                if (dialogState.card.requiredComputer.isNullOrBlank()) {
                                    request.machineId
                                } else {
                                    dialogState.card.suggestedComputer
                                }
                        )
                    val submittingState =
                        dialogState.copy(
                            card = submitCard,
                            submitting = true,
                            errorCode = null
                        )
                    createAgentDialogState = submittingState
                    pendingActionCardIds = pendingActionCardIds + dialogState.messageId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.commitAgentCreateActionCard(dialogState.messageId, submitCard)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyActionCard(dialogState.messageId, result.data)
                                    )
                                    createAgentDialogState = null
                                    bridge.toast(submitCard.actionCardSuccessToast())
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    createAgentDialogState =
                                        submittingState.copy(
                                            submitting = false,
                                            errorCode = result.threadActionErrorCode("agent_create_failed")
                                        )
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    createAgentDialogState =
                                        submittingState.copy(
                                            submitting = false,
                                            errorCode = result.blocker.code
                                        )
                                }
                            }
                        } finally {
                            pendingActionCardIds = pendingActionCardIds - dialogState.messageId
                        }
                    }
                }
            )
        }
    }
}

private suspend fun openSharedThreadAttachment(
    bridge: ai.slock.compose.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    attachment: KuiklyThreadAttachmentItem
): ThreadAttachmentOpenResult {
    val attachmentId = attachment.id.takeIf { it.isNotBlank() }
        ?: return ThreadAttachmentOpenResult(status = "failed", message = "Unable to open attachment")
    return when (val response = dataSource.attachmentUrl(attachmentId, disposition = "attachment")) {
        is ThreadApiResult.Success -> {
            val nativeResult =
                bridge.openAttachment(
                    url = response.data.url,
                    attachmentId = attachmentId,
                    fileName = attachment.name.ifBlank { "attachment-${attachmentId.take(8)}" },
                    mimeType = attachment.mimeType
                )
            when (nativeResult?.optString("status").orEmpty()) {
                "opened" -> ThreadAttachmentOpenResult(status = "opened")
                "no_app" ->
                    ThreadAttachmentOpenResult(
                        status = "no_app",
                        message = nativeResult?.optString("message").orEmpty().ifBlank { "No app can open this file" }
                    )
                "storage_failed" ->
                    ThreadAttachmentOpenResult(
                        status = "storage_failed",
                        message = nativeResult?.optString("message").orEmpty().ifBlank { "Storage unavailable" }
                    )
                else ->
                    ThreadAttachmentOpenResult(
                        status = "failed",
                        message = nativeResult?.optString("message").orEmpty().ifBlank { "Unable to download attachment" }
                    )
            }
        }
        is ThreadApiResult.Failure ->
            ThreadAttachmentOpenResult(status = "failed", message = "Unable to download attachment")
    }
}

internal fun String.toSlockThreadWebLinkTarget(): SlockThreadWebLinkTarget? {
    val trimmed = trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val withoutScheme = trimmed.substring(schemeEnd + 3)
    val pathStart = withoutScheme.indexOf('/')
    val host = if (pathStart >= 0) withoutScheme.substring(0, pathStart) else withoutScheme
    if (host.substringBefore(':').lowercase() !in setOf("app.slock.ai", "app.raft.build")) return null
    val pathAndQuery = if (pathStart >= 0) withoutScheme.substring(pathStart) else ""
    val path = pathAndQuery.substringBefore('?')
    val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    val segments = path.split('/').filter { it.isNotBlank() }
    val channelIndex = segments.indexOf("channel")
    val channelId = segments.getOrNull(channelIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
    val messageId =
        query.split('&')
            .firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                pieces.getOrNull(1)?.takeIf { pieces.firstOrNull() == "msg" && it.isNotBlank() }
            }
    return SlockThreadWebLinkTarget(channelId = channelId, messageId = messageId)
}

internal fun KuiklyThreadScreenPayload.webLinkLoadingPayload(target: SlockThreadWebLinkTarget): KuiklyThreadScreenPayload {
    val composer =
        KuiklyThreadComposerPayload(
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            enabled = canSend,
            streamChannelId = target.channelId
        )
    return copy(
        instanceId = "shared-shell-thread-${target.channelId}",
        title = SharedThreadHeaderContextStore.launchContext(KuiklyThreadSurfaceKind.Channel, null).title,
        subtitle = null,
        status = "loading",
        surfaceKind = KuiklyThreadSurfaceKind.Channel,
        sourceThreadId = target.channelId,
        streamChannelId = target.channelId,
        parentChannelId = null,
        parentMessageId = null,
        selectedTab = KuiklyThreadTab.Chat.wireValue,
        messages = emptyList(),
        originMessage = null,
        originPreview = null,
        originParentMessageId = null,
        originStatus = "none",
        originErrorCode = null,
        errorCode = null,
        filesPayload = "{}",
        composer = composer
    ).withLaunchHeaderContext()
}

private fun KuiklyThreadScreenPayload.commentHostLoadingPayload(
    ref: KuiklyThreadMessageCommentRef
): KuiklyThreadScreenPayload? {
    if (!ref.hasThreadCommentHostSource()) return null
    val source = ref.hostSource ?: return null
    val hostMessageId = ref.hostMessageId?.takeIf { it.isNotBlank() } ?: return null
    val rootThreadId = source.rootThreadChannelId?.takeIf { it.isNotBlank() }
    val threadStreamId = source.threadChannelId?.takeIf { it.isNotBlank() } ?: rootThreadId
    val opensReplyThread = rootThreadId != null || source.type == "thread"
    if (opensReplyThread) {
        val parentMessageId =
            source.parentMessageId?.takeIf { it.isNotBlank() }
                ?: rootThreadId?.let { hostMessageId }
                ?: return null
        val streamId = threadStreamId ?: return null
        return copy(
            instanceId = "shared-shell-thread-$streamId",
            title = ThreadReplyPageTitle,
            subtitle = replyThreadHeaderSubtitle(),
            status = "loading",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = streamId,
            streamChannelId = streamId,
            parentChannelId = source.channelId,
            parentMessageId = parentMessageId,
            navigationLabel = "Close",
            selectedTab = KuiklyThreadTab.Chat.wireValue,
            showModeTabs = false,
            showReplyActions = false,
            showOpenInChannelAction = true,
            originMessage = null,
            originPreview = null,
            originParentMessageId = parentMessageId,
            focusedMessageId = hostMessageId,
            originStatus = "loading",
            originErrorCode = null,
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            errorCode = null,
            filesPayload = "{}",
            composer =
                buildThreadSurfaceComposerPayload(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    displayName = ThreadReplyPageTitle,
                    fallbackId = parentMessageId,
                    enabled = canSend,
                    mentionChannelId = source.channelId,
                    parentMessageId = parentMessageId,
                    streamChannelId = streamId
                )
        )
    }
    val surfaceKind =
        if (source.routeKind == "dm") {
            KuiklyThreadSurfaceKind.DirectMessage
        } else {
            KuiklyThreadSurfaceKind.Channel
        }
    return copy(
        instanceId = "shared-shell-thread-${source.channelId}",
        title = SharedThreadHeaderContextStore.launchContext(surfaceKind, null).title,
        subtitle = null,
        status = "loading",
        surfaceKind = surfaceKind,
        sourceThreadId = source.channelId,
        streamChannelId = source.channelId,
        parentChannelId = null,
        parentMessageId = null,
        selectedTab = KuiklyThreadTab.Chat.wireValue,
        messages = emptyList(),
        originMessage = null,
        originPreview = null,
        originParentMessageId = null,
        focusedMessageId = hostMessageId,
        originStatus = "none",
        originErrorCode = null,
        errorCode = null,
        filesPayload = "{}",
        composer =
            KuiklyThreadComposerPayload(
                surfaceKind = surfaceKind,
                enabled = canSend,
                streamChannelId = source.channelId
            )
    ).withLaunchHeaderContext()
}

private fun KuiklyThreadScreenPayload.findThreadImageAttachmentGroup(attachmentId: String): ThreadImageAttachmentGroup? {
    val messageCandidates = (listOfNotNull(originMessage) + messages).distinctBy { it.id }
    messageCandidates.forEach { message ->
        val images = message.attachments.filter { it.isImageAttachment() }
        val currentIndex = images.indexOfFirst { it.id == attachmentId }
        if (currentIndex >= 0) {
            return ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex)
        }
    }
    return null
}

private fun KuiklyThreadScreenPayload.findThreadAttachment(attachmentId: String): KuiklyThreadAttachmentItem? =
    (listOfNotNull(originMessage) + messages)
        .asSequence()
        .flatMap { it.attachments.asSequence() }
        .firstOrNull { it.id == attachmentId }

private fun KuiklyThreadFilesPayload.findThreadImageAttachmentGroup(attachmentId: String): ThreadImageAttachmentGroup? {
    val images =
        items
            .filter { it.mimeType.startsWith("image/", ignoreCase = true) }
            .map { item ->
                KuiklyThreadAttachmentItem(
                    id = item.attachmentId,
                    name = item.name,
                    mimeType = item.mimeType,
                    sizeLabel = item.sizeLabel,
                    thumbnailUrl = item.thumbnailUrl
                )
            }
    val currentIndex = images.indexOfFirst { it.id == attachmentId }
    return if (currentIndex >= 0) ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex) else null
}

private fun KuiklyThreadFilesPayload.findThreadAttachment(attachmentId: String): KuiklyThreadAttachmentItem? =
    items.firstOrNull { it.attachmentId == attachmentId }?.let { item ->
        KuiklyThreadAttachmentItem(
            id = item.attachmentId,
            name = item.name,
            mimeType = item.mimeType,
            sizeLabel = item.sizeLabel,
            thumbnailUrl = item.thumbnailUrl
        )
    }

@Composable
private fun ThreadImagePreviewOverlay(
    state: ThreadImagePreviewState,
    resolvedUrls: Map<String, ThreadImagePreviewUrl>,
    onDismiss: () -> Unit,
    onOpenExternally: (KuiklyThreadAttachmentItem) -> Unit,
    onSelectIndex: (Int) -> Unit
) {
    val current = state.current ?: return
    val thumbnailUrl = current.thumbnailUrl?.takeIf { it.isNotBlank() }
    val fullUrl = resolvedUrls[current.id]?.url?.takeIf { it.isNotBlank() }
    val displayUrl = fullUrl ?: thumbnailUrl
    val hasMultiple = state.images.size > 1
    val isLoading = current.id in state.loadingAttachmentIds
    val failedWithoutImage = current.id in state.failedAttachmentIds && displayUrl == null
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val swipeThresholdPx =
        with(LocalDensity.current) {
            56.dp.toPx()
        }
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SlockTopPageHeader(
                title = current.name.ifBlank { "Image" },
                subtitle = current.genericAttachmentMetaLine().takeIf { it.isNotBlank() },
                iconName = SlockLucideIconName.Image,
                backgroundColor = SlockColor.SurfaceWhite,
                leadingContent = {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.ArrowLeft,
                        onClick = onDismiss,
                        size = 30.dp,
                        shadowOffset = 2.dp
                    )
                },
                trailingContent = {
                    SlockFilePreviewOverflowMenuButton(
                        onOpenMenu = { anchor ->
                            menuAnchor = anchor
                            menuOpen = true
                        }
                    )
                }
            )
            Column(modifier = Modifier.fillMaxSize()) {
                val previewBottomPadding = if (hasMultiple) 0.dp else bottomSafeArea
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .weight(1f)
                        .padding(
                            start = SlockSpacing.Md,
                            top = SlockSpacing.Md,
                            end = SlockSpacing.Md,
                            bottom = SlockSpacing.Md + previewBottomPadding
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .then(
                                if (hasMultiple) {
                                    Modifier.pointerInput(state.currentIndex, state.images.size) {
                                        var dragDistance = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { dragDistance = 0f },
                                            onDragEnd = {
                                                when {
                                                    dragDistance <= -swipeThresholdPx && state.currentIndex < state.images.lastIndex -> {
                                                        onSelectIndex(state.currentIndex + 1)
                                                    }

                                                    dragDistance >= swipeThresholdPx && state.currentIndex > 0 -> {
                                                        onSelectIndex(state.currentIndex - 1)
                                                    }
                                                }
                                            },
                                            onDragCancel = { dragDistance = 0f }
                                        ) { _, dragAmount ->
                                            dragDistance += dragAmount
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        if (displayUrl != null) {
                            SlockCachedImage(
                                source = displayUrl,
                                contentDescription = current.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(8.dp)
                            )
                        } else {
                            ThreadImagePreviewEmptyState(
                                title = if (failedWithoutImage) "Image unavailable" else "Loading image...",
                                detail = current.name
                            )
                        }
                    }
                    if (isLoading && displayUrl != null) {
                        ThreadImagePreviewLoadingPill(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = if (hasMultiple) 56.dp else 12.dp)
                        )
                    }
                    if (hasMultiple) {
                        ThreadImagePreviewNavigationPill(
                            label = "${state.currentIndex + 1}/${state.images.size}",
                            canGoPrevious = state.currentIndex > 0,
                            canGoNext = state.currentIndex < state.images.lastIndex,
                            onPrevious = { onSelectIndex(state.currentIndex - 1) },
                            onNext = { onSelectIndex(state.currentIndex + 1) },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        )
                    }
                }
                if (hasMultiple) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth()
                            .height(74.dp + bottomSafeArea)
                            .background(SlockColor.SurfaceWhite)
                            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + bottomSafeArea),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(state.images.size, key = { state.images[it].id }) { index ->
                            ThreadImagePreviewThumbnail(
                                attachment = state.images[index],
                                selected = index == state.currentIndex,
                                resolvedUrl = resolvedUrls[state.images[index].id]?.url,
                                onClick = { onSelectIndex(index) }
                            )
                        }
                    }
                }
            }
        }
        if (menuOpen) {
            SlockBrutalDropdownMenu(
                anchor = menuAnchor,
                items =
                    listOf(
                        SlockBrutalDropdownMenuItem(
                            id = ThreadAttachmentPreviewOpenWithActionId,
                            label = "Open with...",
                            icon = SlockLucideIconName.ExternalLink
                        )
                    ),
                onSelect = { id ->
                    menuOpen = false
                    if (id == ThreadAttachmentPreviewOpenWithActionId) {
                        onOpenExternally(current)
                    }
                },
                onDismiss = { menuOpen = false },
                widthDp = 180f
            )
        }
    }
}

@Composable
private fun ThreadImagePreviewEmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.widthIn(max = 260.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(SlockLucideIconName.Image, modifier = Modifier.size(34.dp), color = "#FF000000")
        Text(
            text = title,
            style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadImagePreviewLoadingPill(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading full image...",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun ThreadImagePreviewNavigationPill(
    label: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThreadImagePreviewInlineNavButton(
            icon = SlockLucideIconName.ChevronLeft,
            enabled = canGoPrevious,
            onClick = onPrevious
        )
        Text(
            text = label,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
        ThreadImagePreviewInlineNavButton(
            icon = SlockLucideIconName.ChevronRight,
            enabled = canGoNext,
            onClick = onNext
        )
    }
}

@Composable
private fun ThreadImagePreviewInlineNavButton(
    icon: SlockLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(icon, modifier = Modifier.size(16.dp), color = "#FF000000")
    }
}

@Composable
private fun ThreadImagePreviewThumbnail(
    attachment: KuiklyThreadAttachmentItem,
    selected: Boolean,
    resolvedUrl: String?,
    onClick: () -> Unit
) {
    val source = attachment.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedUrl?.takeIf { it.isNotBlank() }
    Button(
        onClick = onClick,
        pressOffset = 2.dp,
        modifier = Modifier.size(54.dp)
            .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
            .padding(if (selected) 3.dp else 1.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Thin, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (source != null) {
                SlockAspectFitImagePreviewSurface(
                    source = source,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    borderWidth = 0.dp
                )
            } else {
                SlockLucideIcon(SlockLucideIconName.Image, modifier = Modifier.size(20.dp), color = "#FF000000")
            }
        }
    }
}

@Composable
private fun ThreadAttachmentNoPreviewOverlay(
    state: ThreadAttachmentNoPreviewState,
    openState: ThreadAttachmentOpenUiState?,
    onDismiss: () -> Unit,
    onOpenWithSystem: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val attachment = state.attachment
    val isOpening = openState.threadAttachmentIsOpening()
    val isMarkdown = attachment.isMarkdownAttachment()
    SlockFilePreviewPageScaffold(
        title = attachment.name.ifBlank { "Attachment" },
        subtitle = attachment.genericAttachmentMetaLine().takeIf { it.isNotBlank() },
        iconName = if (isMarkdown) SlockLucideIconName.FileText else SlockLucideIconName.File,
        onBack = onDismiss,
        overflowMenuItems =
            listOf(
                SlockBrutalDropdownMenuItem(
                    id = ThreadAttachmentPreviewOpenWithActionId,
                    label = if (isOpening) "Preparing..." else "Open with...",
                    icon = if (isOpening) SlockLucideIconName.Loader else SlockLucideIconName.ExternalLink,
                    enabled = !isOpening
                )
            ),
        onOverflowMenuSelect = { id ->
            if (id == ThreadAttachmentPreviewOpenWithActionId) {
                onOpenWithSystem()
            }
        }
    ) {
        if (isMarkdown) {
            SlockFilePreviewMarkdownPanel(
                name = attachment.name.ifBlank { "Markdown" },
                meta = attachment.genericAttachmentMetaLine(),
                statusLabel = openState.threadAttachmentStatusLabel(),
                status = state.markdownStatus,
                body = state.markdownBody,
                error = state.markdownError,
                onOpenUrl = onOpenUrl,
                modifier = Modifier.weight(1f),
                performanceKey = "attachment:${attachment.id.take(8)}"
            )
        } else {
            SlockFilePreviewGenericCard(
                iconName = SlockLucideIconName.File,
                name = attachment.name.ifBlank { "File" },
                meta = attachment.genericAttachmentMetaLine(),
                message = "No preview is available for this file type.",
                statusLabel = openState.threadAttachmentStatusLabel(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

internal fun KuiklyThreadScreenPayload.replyThreadLoadingPayload(
    parentMessage: KuiklyThreadMessageItem,
    focusedMessageId: String? = null
): KuiklyThreadScreenPayload {
    val composer =
        buildThreadSurfaceComposerPayload(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            displayName = ThreadReplyPageTitle,
            fallbackId = parentMessage.id,
            enabled = canSend,
            mentionChannelId = sourceThreadId,
            parentMessageId = parentMessage.id,
            streamChannelId = null
        )
    return copy(
        instanceId = "shared-shell-thread-pending-${parentMessage.id}",
        title = ThreadReplyPageTitle,
        subtitle = replyThreadHeaderSubtitle(),
        status = "loading",
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        sourceThreadId = null,
        streamChannelId = null,
        parentChannelId = sourceThreadId,
        parentMessageId = parentMessage.id,
        focusedMessageId = focusedMessageId?.takeIf { it.isNotBlank() },
        navigationLabel = "Close",
        selectedTab = "chat",
        showModeTabs = false,
        showReplyActions = false,
        showOpenInChannelAction = true,
        originMessage = parentMessage,
        originParentMessageId = parentMessage.id,
        originStatus = "loaded",
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        emptyTitle = "Opening replies",
        emptyDetail = "Loading the message thread.",
        composer = composer
    )
}

internal fun KuiklyThreadScreenPayload.parentMessageContextLoadingPayload(
    parentMessageId: String
): KuiklyThreadScreenPayload {
    val focusedParentMessageId = parentMessageId.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: this.parentMessageId?.takeIf { it.isNotBlank() }
    val parentThreadId = parentChannelId?.takeIf { it.isNotBlank() }
    val parentSurfaceKind =
        if (channelKind.equals("dm", ignoreCase = true) || memberScopedMentions && parentThreadId == null) {
            KuiklyThreadSurfaceKind.DirectMessage
        } else {
            KuiklyThreadSurfaceKind.Channel
        }
    return copy(
        instanceId = parentThreadId?.let { "shared-shell-thread-$it" }
            ?: focusedParentMessageId?.let { "shared-shell-parent-message-$it" }
            ?: "shared-shell-parent-message",
        title = SharedThreadHeaderContextStore.launchContext(parentSurfaceKind, null).title,
        subtitle = null,
        status = "loading",
        surfaceKind = parentSurfaceKind,
        sourceThreadId = parentThreadId,
        streamChannelId = parentThreadId,
        parentChannelId = null,
        parentMessageId = null,
        originParentMessageId = null,
        focusedMessageId = focusedParentMessageId,
        navigationLabel = "Back",
        selectedTab = KuiklyThreadTab.Chat.wireValue,
        showModeTabs = true,
        showReplyActions = true,
        showOpenInChannelAction = false,
        originMessage = null,
        originPreview = null,
        originStatus = "none",
        originErrorCode = null,
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        errorCode = null,
        filesPayload = "{}",
        composer =
            KuiklyThreadComposerPayload(
                surfaceKind = parentSurfaceKind,
                enabled = canSend,
                streamChannelId = parentThreadId
            ),
        emptyTitle = "Opening conversation",
        emptyDetail = "Loading the parent message context."
    ).withLaunchHeaderContext()
}

private fun KuiklyThreadFilesPayload.shouldFetchInitialFiles(): Boolean =
    status == "loading" || (status == "error" && items.isEmpty())

private fun String.toSharedThreadTaskStatus(): TaskStatus? =
    when (trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")) {
        "todo" -> TaskStatus.Todo
        "inprogress" -> TaskStatus.InProgress
        "inreview" -> TaskStatus.InReview
        "done", "complete", "completed" -> TaskStatus.Done
        "closed" -> TaskStatus.Closed
        else -> null
    }

internal fun parseThreadScreenPayload(
    payload: String,
    instanceId: String
): KuiklyThreadScreenPayload {
    val source = payload.trim().takeIf { it.isNotEmpty() } ?: "{}"
    parseThreadLaunchPayload(source, instanceId)?.let { return it }
    return KuiklyThreadScreenPayload(
        instanceId = instanceId,
        status = "error",
        errorCode = "payload_parse_failed"
    )
}

internal fun parseThreadLaunchPayload(source: String, instanceId: String): KuiklyThreadScreenPayload? =
    runCatching {
        threadPageJson
            .decodeFromString(WireThreadLaunchPayload.serializer(), source)
            .takeIf { it.hasRouteIdentity() }
            ?.toInitialPayload(instanceId)
    }.getOrNull()

internal const val THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT = "default"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT = "rich_content"

internal fun threadVisualFixture(
    fixture: String,
    instanceId: String
): KuiklyThreadScreenPayload {
    val resolvedInstanceId = instanceId.ifBlank { "visual-thread-$fixture" }
    return when (fixture) {
        THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Thread visual fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                alignOwnMessagesRight = true,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "msg-agent-reply",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorKind = "agent",
                            isOwn = false,
                            avatarLabel = "CI",
                            timestampLabel = "06/22 10:30",
                            messageType = "Chat",
                            body = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                            threadChannelId = "thread-msg-agent-reply",
                            reactions =
                                listOf(
                                    KuiklyThreadReactionItem(emoji = "+1", count = 2, selected = false)
                                )
                        )
                    ),
                members =
                    listOf(
                        KuiklyThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        KuiklyThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                filesPayload = "{}",
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Thread rich message fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = true,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = true,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "msg_rich_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
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
                                    KuiklyThreadReactionItem(emoji = "👍", count = 3, selected = false)
                                ),
                            attachments =
                                listOf(
                                    KuiklyThreadAttachmentItem(
                                        id = "att-visual-spec",
                                        name = "visual-parity-notes.pdf",
                                        mimeType = "application/pdf",
                                        sizeLabel = "184 KB",
                                        commentCount = 3
                                    ),
                                    KuiklyThreadAttachmentItem(
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
                        KuiklyThreadTaskItem(
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
                        KuiklyThreadMemberItem(
                            id = "agent-cindy",
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = "agent",
                            avatarLabel = "CI"
                        ),
                        KuiklyThreadMemberItem(
                            id = "visual-user",
                            displayName = "artin",
                            handle = "artin",
                            kind = "human",
                            avatarLabel = "AR"
                        )
                    ),
                showComposer = false,
                joined = true,
                filesPayload = "{}",
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        else ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                status = "error",
                errorCode = "visual_fixture_unknown"
            )
    }
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun KuiklyThreadScreenPayload.threadComposerMentionScopeChannelId(): String? {
    val resolvedComposer = resolveThreadComposer()
    return resolvedComposer.mentionChannelId.nonBlank()
        ?: resolvedAuxiliaryThreadId()
        ?: resolvedComposer.streamChannelId.nonBlank()
        ?: streamChannelId.nonBlank()
        ?: sourceThreadId.nonBlank()
        ?: parentChannelId.nonBlank()
}

private fun KuiklyThreadScreenPayload.threadComposerMemberScopedMentions(): Boolean =
    memberScopedMentions || surfaceKind == KuiklyThreadSurfaceKind.DirectMessage

internal fun RealtimeEvent.refreshesThreadMemberSuggestions(mentionChannelId: String?): Boolean =
    when (this) {
        is RealtimeRefreshRequired ->
            when (scope) {
                RealtimeRefreshScope.ChannelMembers ->
                    mentionChannelId != null &&
                        (channelId == null || channelId.value == mentionChannelId)
                RealtimeRefreshScope.ServerMembers -> true
                else -> false
            }
        else -> false
    }

internal fun StoredAuthSession?.threadSelfMentionNames(): Set<String> =
    this?.user?.let { user ->
        listOfNotNull(
            user.displayName,
            user.name,
            user.email.substringBefore("@").ifBlank { null },
            user.bestDisplayName
        )
            .map { it.removePrefix("@").trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }.orEmpty()

internal fun PendingMentionAction.toKuiklyPendingMentionActionItem(): KuiklyPendingMentionActionItem =
    KuiklyPendingMentionActionItem(
        resolutionId = resolutionId,
        messageId = messageId?.value,
        targetType = targetType,
        targetHandle = targetHandle,
        reason = reason,
        availableActions = availableActions,
        expiresAt = expiresAt
    )

internal fun KuiklyPendingMentionActionItem.mentionActionDeliveredNotice(
    action: String,
    channelLabel: String
): String {
    val label = targetLabel()
    return if (action == "invite") {
        "$label was added to $channelLabel"
    } else {
        "Notified $label once - still not in $channelLabel"
    }
}

internal fun formatMentionActionStatusNotice(result: PendingMentionActionExecuteResult?): String =
    when (result?.reason) {
        "sender_lacks_channel_access" -> "You no longer have access to that channel, so this mention action was not applied."
        "target_lacks_read_access" -> "That person cannot read this conversation, so they were not notified."
        "invite_not_allowed_for_surface" -> "That person cannot be added from this mention action. Use the channel member controls instead."
        "invite_requires_human_member_authority" -> "Agents cannot add channel members from mention actions. Ask a human member to add them."
        "invite_requires_member_management_authority" -> "Only channel admins can add members from this mention action."
        "target_already_member" -> "That target is already in the channel, so no action is needed."
        "target_unavailable" -> "That target is no longer available."
        "expired" -> "This mention action has expired."
        "not_found" -> "This mention action is no longer available."
        "ambiguous" -> "This mention action matched more than one target. Refresh and try again."
        else -> "This mention action could not be applied."
    }

private fun KuiklyThreadComposerPayload.withThreadComposerDraftState(
    draftState: SharedThreadComposerDraftState,
    renderedPayload: KuiklyThreadScreenPayload,
    resolvedAuxiliaryThreadId: String?
): KuiklyThreadComposerPayload {
    val pendingAttachments =
        (pendingAttachments.map { attachment ->
            SharedThreadComposerAttachment(
                id = attachment.id,
                fileName = attachment.fileName,
                sizeLabel = attachment.sizeLabel,
                mimeType = attachment.mimeType,
                status = attachment.status
            )
        } + draftState.uploadedAttachments)
            .distinctBy { attachment -> attachment.id }
            .filterNot { attachment -> attachment.id in draftState.removedAttachmentIds }
    return copy(
        instanceId = instanceId.ifBlank { renderedPayload.instanceId },
        seedText = draftState.seedText,
        seedAsTask = draftState.seedAsTask,
        syncNonce = draftState.syncNonce,
        pendingAttachments =
            pendingAttachments.map { attachment ->
                KuiklyPendingAttachmentItem(
                    id = attachment.id,
                    fileName = attachment.fileName,
                    sizeLabel = attachment.sizeLabel,
                    mimeType = attachment.mimeType,
                    status = attachment.status
                )
            },
        composerMode = if (composerMode == "failed") "failed" else composerMode,
        statusLabel = statusLabel,
        pendingMentionActions = draftState.pendingMentionActions,
        mentionActionNotice = draftState.mentionActionNotice,
        executingMentionActionIds = draftState.executingMentionActionIds.toList(),
        streamChannelId = resolvedAuxiliaryThreadId,
        enabled = renderedPayload.canSend && resolvedAuxiliaryThreadId != null
    )
}

@Composable
private fun ThreadTasksJoinResultLogger(
    tasksViewModel: SharedTasksViewModel?,
    taskChannelId: ChannelId?,
    startedTasksJoinThreadId: String?,
    lastTasksJoinResultKey: String?,
    tasksJoinStartedAt: TimeMark?,
    threadRouteKey: String?,
    onLogged: (String) -> Unit,
    log: (String) -> Unit
) {
    val pendingTasksState = remember { TasksUiState(ScreenLoadState.Loading) }
    val tasksState =
        if (tasksViewModel != null) {
            val collectedTasksState by tasksViewModel.state.collectAsState()
            collectedTasksState
        } else {
            pendingTasksState
        }
    LaunchedEffect(startedTasksJoinThreadId, taskChannelId?.value, tasksState.loadState) {
        val resolvedTaskChannelId = taskChannelId ?: return@LaunchedEffect
        if (startedTasksJoinThreadId != resolvedTaskChannelId.value) {
            return@LaunchedEffect
        }
        val (result, taskCount) = tasksState.threadTasksJoinDiagnosticsResult() ?: return@LaunchedEffect
        val resultKey = "${resolvedTaskChannelId.value}:$result:$taskCount"
        if (lastTasksJoinResultKey == resultKey) {
            return@LaunchedEffect
        }
        onLogged(resultKey)
        val durationMs = tasksJoinStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L
        log(
            "thread_tasks_join_result threadId=${threadRouteKey.orEmpty()} result=$result " +
                "tasks=$taskCount durationMs=$durationMs"
        )
    }
}

@Composable
private fun ThreadPageContent(
    payload: KuiklyThreadScreenPayload,
    composerSurface: ThreadComposerSurface,
    baseComposerState: KuiklyThreadComposerPayload,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    resolvedAuxiliaryThreadId: String?,
    tasksViewModel: SharedTasksViewModel?,
    taskChannelId: ChannelId?,
    filesViewModel: SharedThreadFilesViewModel,
    memberSuggestionsFlow: Flow<List<SharedMembersMemberSuggestion>>,
    channelSuggestionsFlow: Flow<List<SharedMembersChannelSuggestion>>,
    contentExtrasVisible: Boolean,
    onTasksIntent: (SharedTasksRouteIntent) -> Unit,
    onFilesAuthRequired: (String?) -> Unit,
    onOpenFilesAttachment: (KuiklyThreadAttachmentItem, ThreadImageAttachmentGroup?) -> Unit,
    onDownloadFilesAttachment: (KuiklyThreadAttachmentItem) -> Unit,
    onOpenMemberProfile: (String) -> Unit,
    highlightedMentions: Set<String>,
    diagnosticsLogger: (String) -> Unit = {},
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    onBack: () -> Unit,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit
) {
    val renderEarlyLoadingShell = payload.shouldRenderThreadEarlyLoadingShell()
    var selectedTab by remember(payload.selectedTab) {
        mutableStateOf(KuiklyThreadTab.fromWireValue(payload.selectedTab))
    }
    val availableTabs =
        remember(payload.showTaskOpenActions, payload.showTaskStatusActions, payload.composer.showAsTaskToggle) {
            KuiklyThreadTab.entries.filter { tab ->
                tab != KuiklyThreadTab.Tasks || payload.supportsChannelTasks()
            }
        }
    LaunchedEffect(availableTabs, selectedTab) {
        if (selectedTab !in availableTabs) {
            selectedTab = KuiklyThreadTab.Chat
            onThreadEvent("selectTab", null, null, KuiklyThreadTab.Chat.wireValue)
        }
    }
    var activeModal by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var composerKeyboardInsetDp by remember(payload.instanceId) { mutableStateOf(0f) }
    val composerBottomSafeArea = slockBottomSafeAreaPadding()
    val taskPanelChannelId =
        remember(taskChannelId, payload.sourceThreadId, payload.instanceId) {
            taskChannelId
                ?: payload.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: ChannelId(payload.instanceId)
        }
    val openMarkdownTag: (ThreadMarkdownTag) -> Unit = { tag ->
        when (tag.kind) {
            ThreadMarkdownTagKind.UserMention -> {
                onThreadEvent("openMarkdownTag", tag.label, null, tag.kind.name)
            }
            ThreadMarkdownTagKind.Task -> {
                val taskId = payload.findMarkdownTaskId(tag)
                if (!taskId.isNullOrBlank()) {
                    onThreadEvent("openTask", taskId, null, null)
                } else {
                    onThreadEvent("openMarkdownTag", tag.label, null, tag.kind.name)
                }
            }
            ThreadMarkdownTagKind.Channel -> {
                onThreadEvent("openMarkdownChannel", tag.channelName ?: tag.label, null, tag.kind.name)
            }
            ThreadMarkdownTagKind.ChannelThread -> {
                onThreadEvent("openMarkdownThreadRef", tag.label, null, tag.threadShortId)
            }
        }
    }
    val openMarkdownUrl: (String) -> Unit = { url ->
        onThreadEvent("openMarkdownUrl", url, null, null)
    }
    val knownMarkdownChannelNames =
        remember(payload.knownMarkdownChannelNames) {
            payload.knownMarkdownChannelNames.toKnownMarkdownChannelNameSet()
        }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(
            modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
        ) {
            ThreadPageHeader(
                payload = payload,
                onBack = onBack,
                onOpenInChannel = {
                    onThreadEvent("openParentMessage", payload.originParentMessageId, null, null)
                },
                onShowMembers = {
                    activeModal = "members"
                    onThreadEvent("loadMembers", null, null, null)
                },
                onShowSettings = {
                    activeModal = "settings"
                }
            )
            if (payload.showModeTabs) {
                ThreadPageTabs(
                    selectedTab = selectedTab,
                    tabs = availableTabs,
                    onSelect = { tab ->
                        selectedTab = tab
                        onThreadEvent("selectTab", null, null, tab.wireValue)
                    }
                )
            }
            val linkedTasksByMessageId = remember(payload.tasks) {
                payload.tasks.map { task -> task.toTaskSummary() }.threadLinkedTasksByMessageId()
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceWhite)
            ) {
                if (renderEarlyLoadingShell) {
                    ThreadStateMessage("Loading thread…", null)
                } else when (selectedTab) {
                    KuiklyThreadTab.Chat ->
                        ThreadChatTab(
                            payload = payload,
                            composerKeyboardInsetDp = composerKeyboardInsetDp,
                            linkedTasksByMessageId = linkedTasksByMessageId,
                            highlightedMentions = highlightedMentions,
                            pendingActionCardIds = pendingActionCardIds,
                            attachmentOpenStates = attachmentOpenStates,
                            attachmentCommentsEnabled = attachmentCommentsEnabled,
                            currentServerSlug = currentServerSlug,
                            contentExtrasVisible = contentExtrasVisible,
                            onThreadEvent = onThreadEvent,
                            onOpenMember = onOpenMemberProfile,
                            onOpenMarkdownTag = openMarkdownTag,
                            onOpenMarkdownUrl = openMarkdownUrl,
                            diagnosticsLogger = diagnosticsLogger
                        )
                    KuiklyThreadTab.Tasks ->
                        ThreadTasksTab(
                            tasksViewModel = tasksViewModel,
                            channelId = taskPanelChannelId,
                            openTaskEnabled = payload.showTaskOpenActions,
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onIntent = onTasksIntent
                        )
                    KuiklyThreadTab.Files ->
                        ThreadFilesTab(
                            filesViewModel = filesViewModel,
                            attachmentOpenStates = attachmentOpenStates,
                            onAuthRequired = onFilesAuthRequired,
                            onOpenAttachment = onOpenFilesAttachment,
                            onDownloadAttachment = onDownloadFilesAttachment
                        )
                }
            }
            if (!payload.joined) {
                ThreadJoinFooter(
                    label = payload.joinLabel,
                    onJoin = { onThreadEvent("joinChannel", null, null, null) }
                )
            } else if (
                payload.status != "loading" &&
                contentExtrasVisible &&
                shouldShowThreadComposerForTab(payload.joined, payload.showComposer, selectedTab.wireValue)
            ) {
                Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
                    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(
                                start = SlockSpacing.Sm,
                                top = 12.dp,
                                end = SlockSpacing.Sm,
                                bottom = threadComposerBottomPadding(composerKeyboardInsetDp, composerBottomSafeArea)
                            )
                    ) {
                        ThreadComposerRoute(
                            baseComposerState = baseComposerState,
                            composerDraftViewModel = composerDraftViewModel,
                            renderedPayload = payload,
                            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
                            surface = composerSurface,
                            memberSuggestionsFlow = memberSuggestionsFlow,
                            channelSuggestionsFlow = channelSuggestionsFlow,
                            onEvent = onComposerEvent,
                            onKeyboardHeightChange = { nextInset ->
                                if (nextInset != composerKeyboardInsetDp) {
                                    composerKeyboardInsetDp = nextInset
                                }
                            }
                        )
                    }
                }
            }
        }
        when (activeModal) {
            "members" ->
                ThreadMembersModal(
                    members = payload.members,
                    membersCount = payload.membersCount,
                    canAddMembers = payload.canAddMembers,
                    onDismiss = { activeModal = null },
                    onAddMember = {
                        activeModal = "addMember"
                    },
                    removeMemberStatus = payload.removeMemberStatus,
                    removingMemberId = payload.removingMemberId,
                    removeMemberErrorCode = payload.removeMemberErrorCode,
                    onRemoveMember = { memberId ->
                        onThreadEvent("removeMember", memberId, null, null)
                    }
                )
            "addMember" ->
                ThreadAddMemberModal(
                    members = payload.members,
                    candidates = payload.memberCandidates,
                    canAddMembers = payload.canAddMembers,
                    addMemberStatus = payload.addMemberStatus,
                    addMemberErrorCode = payload.addMemberErrorCode,
                    onDismiss = { activeModal = "members" },
                    onSubmit = { member ->
                        onThreadEvent("submitAddMember", member.id, null, member.kind)
                    }
                )
            "settings" ->
                ThreadSettingsModal(
                    channelName = payload.title,
                    subtitle = payload.subtitle,
                    actions = payload.settingsActions,
                    onDismiss = { activeModal = null },
                    onOpenAction = { actionId ->
                        activeModal = null
                        onThreadEvent("settingsAction", actionId, null, null)
                    }
                )
        }
    }
}

@Composable
private fun ThreadPageHeader(
    payload: KuiklyThreadScreenPayload,
    onBack: () -> Unit,
    onOpenInChannel: () -> Unit,
    onShowMembers: () -> Unit,
    onShowSettings: () -> Unit
) {
    val topSafeArea = slockTopSafeAreaPadding()
    val displayTitle = threadHeaderDisplayTitle(payload.title)
    Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(
                    start = SlockSpacing.Md,
                    top = topSafeArea + SlockSpacing.Sm,
                    end = SlockSpacing.Md,
                    bottom = SlockSpacing.Sm
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            ThreadHeaderIconButton(icon = SlockLucideIconName.ArrowLeft, onClick = onBack)
            if (payload.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) {
                ThreadDmPeerAvatarTile(
                    avatarWireValue = payload.dmPeerAvatarUrl,
                    avatarLabel = payload.dmPeerAvatarLabel ?: payload.dmPeerName ?: "",
                    gravatarUrl = payload.dmPeerGravatarUrl
                )
            } else if (payload.showModeTabs) {
                ThreadChannelTile(payload.channelKind)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = displayTitle,
                    style = SlockType.ButtonBold.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                payload.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (payload.showSettingsAction) {
                ThreadHeaderIconButton(icon = SlockLucideIconName.Settings, onClick = onShowSettings)
            }
            if (payload.showMembersAction) {
                ThreadMembersHeaderButton(count = payload.membersCount, onClick = onShowMembers)
            }
            if (payload.showOpenInChannelAction) {
                ThreadHeaderIconButton(icon = SlockLucideIconName.ExternalLink, onClick = onOpenInChannel)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun ThreadChannelTile(channelKind: String) {
    Box(
        modifier = Modifier.size(ThreadHeaderChannelTileSize)
            .background(SlockColor.BrandYellow)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            channelKind.threadChannelHeaderIcon(),
            modifier = Modifier.size(ThreadHeaderChannelIconSize),
            color = "#FF141111"
        )
    }
}

internal fun String?.threadChannelHeaderIcon(): SlockLucideIconName =
    when (this?.lowercase()) {
        "private" -> SlockLucideIconName.Lock
        "joint" -> SlockLucideIconName.GitBranch
        else -> SlockLucideIconName.Hash
    }

@Composable
private fun ThreadDmPeerAvatarTile(
    avatarWireValue: String?,
    avatarLabel: String,
    gravatarUrl: String? = null
) {
    SlockAvatar(
        avatarWireValue = avatarWireValue,
        label = avatarLabel,
        avatarSize = ThreadHeaderChannelTileSize,
        borderWidth = SlockStroke.Default,
        gravatarUrl = gravatarUrl
    )
}

@Composable
private fun ThreadHeaderIconButton(icon: SlockLucideIconName, onClick: () -> Unit) {
    ThreadHeaderCompactAction(icon = icon, onClick = onClick)
}

@Composable
private fun ThreadComposerRoute(
    baseComposerState: KuiklyThreadComposerPayload,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    renderedPayload: KuiklyThreadScreenPayload,
    resolvedAuxiliaryThreadId: String?,
    surface: ThreadComposerSurface,
    memberSuggestionsFlow: Flow<List<SharedMembersMemberSuggestion>>,
    channelSuggestionsFlow: Flow<List<SharedMembersChannelSuggestion>>,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit
) {
    val composerDraftState by composerDraftViewModel.state.collectAsState()
    val composerState =
        remember(baseComposerState, composerDraftState, renderedPayload.canSend, resolvedAuxiliaryThreadId) {
            baseComposerState.withThreadComposerDraftState(
                draftState = composerDraftState,
                renderedPayload = renderedPayload,
                resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId
            )
        }
    ThreadComposerContentWithSuggestions(
        surface = surface,
        state = composerState.toThreadComposerUiState(),
        memberSuggestionsFlow = memberSuggestionsFlow,
        channelSuggestionsFlow = channelSuggestionsFlow,
        onEvent = onEvent,
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = 0.dp,
        contentPaddingBottom = 0.dp
    )
}

@Composable
private fun ThreadComposerContentWithSuggestions(
    surface: ThreadComposerSurface,
    state: ThreadComposerUiState,
    memberSuggestionsFlow: Flow<List<SharedMembersMemberSuggestion>>,
    channelSuggestionsFlow: Flow<List<SharedMembersChannelSuggestion>>,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit,
    contentPaddingTop: Dp,
    contentPaddingBottom: Dp
) {
    val memberSuggestions by memberSuggestionsFlow.collectAsState(emptyList())
    val channelSuggestions by channelSuggestionsFlow.collectAsState(emptyList())
    val loadedMemberSuggestions =
        remember(memberSuggestions) {
            memberSuggestions.map { suggestion ->
                KuiklyComposerMemberItem(
                    displayName = suggestion.displayName,
                    handle = suggestion.handle,
                    inMentionChannel = suggestion.inMentionChannel,
                    sourceServerLabel = suggestion.sourceServerLabel
                )
            }
        }
    val loadedChannelSuggestions =
        remember(channelSuggestions) {
            channelSuggestions.map { suggestion ->
                KuiklyComposerChannelItem(
                    name = suggestion.name,
                    subtitle = suggestion.subtitle
                )
            }
        }
    val renderedState =
        remember(state, loadedMemberSuggestions, loadedChannelSuggestions) {
            state.copy(
                memberSuggestions = loadedMemberSuggestions.takeIf { it.isNotEmpty() } ?: state.memberSuggestions,
                channelSuggestions = loadedChannelSuggestions.takeIf { it.isNotEmpty() } ?: state.channelSuggestions
            )
        }
    ThreadComposerContent(
        surface = surface,
        state = renderedState,
        onEvent = onEvent,
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = contentPaddingTop,
        contentPaddingBottom = contentPaddingBottom
    )
}

@Composable
private fun ThreadMembersHeaderButton(count: Int, onClick: () -> Unit) {
    ThreadHeaderCompactAction(
        icon = SlockLucideIconName.Users,
        onClick = onClick,
        label = count.takeIf { it > 0 }?.toString()
    )
}

@Composable
private fun ThreadHeaderCompactAction(
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    label: String? = null
) {
    Box(modifier = Modifier.padding(end = ThreadHeaderActionShadowOffset, bottom = ThreadHeaderActionShadowOffset)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = ThreadHeaderActionShadowOffset, y = ThreadHeaderActionShadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        Button(
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = ThreadHeaderActionSize)
                .widthIn(min = if (label == null) ThreadHeaderActionSize else ThreadHeaderActionLabelMinWidth)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = if (label == null) 0.dp else SlockSpacing.Xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs, Alignment.CenterHorizontally)
            ) {
                SlockLucideIcon(
                    name = icon,
                    modifier = Modifier.size(ThreadHeaderActionIconSize),
                    color = "#FF000000"
                )
                label?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Medium).copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadHeaderAction(label: String, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = label,
        onClick = onClick,
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 30.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(fontSize = 12.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun ThreadPageTabs(
    selectedTab: KuiklyThreadTab,
    tabs: List<KuiklyThreadTab> = KuiklyThreadTab.entries,
    onSelect: (KuiklyThreadTab) -> Unit
) {
    SlockTopTabBar(
        items = tabs.map { tab ->
            SlockTopTabBarItem(
                key = tab.wireValue,
                label = tab.label,
                iconName = tab.icon
            )
        },
        selectedKey = selectedTab.wireValue,
        onSelectedKeyChange = { key ->
            tabs.firstOrNull { it.wireValue == key }?.let(onSelect)
        },
        itemKeyPrefix = "thread-tab"
    )
}

@Composable
private fun ThreadChatTab(
    payload: KuiklyThreadScreenPayload,
    composerKeyboardInsetDp: Float,
    linkedTasksByMessageId: Map<String, TaskSummary>,
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    when (payload.status) {
        "loading" ->
            if (payload.originStatus != "none") {
                ThreadMessagesList(
                    payload = payload,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksByMessageId = linkedTasksByMessageId,
                    highlightedMentions = highlightedMentions,
                    pendingActionCardIds = pendingActionCardIds,
                    attachmentOpenStates = attachmentOpenStates,
                    attachmentCommentsEnabled = attachmentCommentsEnabled,
                    currentServerSlug = currentServerSlug,
                    contentExtrasVisible = contentExtrasVisible,
                    onThreadEvent = onThreadEvent,
                    onOpenMember = onOpenMember,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger
                )
            } else {
                ThreadStateMessage("Loading thread…", null)
            }
        "error" ->
            ThreadStateActionMessage(
                title = "Failed to load thread",
                detail = payload.errorCode ?: "unknown_error",
                actionLabel = "Retry",
                onClick = { onThreadEvent("retryThreadLoad", null, null, null) }
            )
        "no_permission" -> ThreadStateMessage("No permission", payload.errorCode ?: "missing capability")
        "empty" ->
            if (payload.originStatus != "none") {
                ThreadMessagesList(
                    payload = payload,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksByMessageId = linkedTasksByMessageId,
                    highlightedMentions = highlightedMentions,
                    pendingActionCardIds = pendingActionCardIds,
                    attachmentOpenStates = attachmentOpenStates,
                    attachmentCommentsEnabled = attachmentCommentsEnabled,
                    currentServerSlug = currentServerSlug,
                    contentExtrasVisible = contentExtrasVisible,
                    onThreadEvent = onThreadEvent,
                    onOpenMember = onOpenMember,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger
                )
            } else {
                ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
            }
        else ->
            ThreadMessagesList(
                payload = payload,
                composerKeyboardInsetDp = composerKeyboardInsetDp,
                linkedTasksByMessageId = linkedTasksByMessageId,
                highlightedMentions = highlightedMentions,
                pendingActionCardIds = pendingActionCardIds,
                attachmentOpenStates = attachmentOpenStates,
                attachmentCommentsEnabled = attachmentCommentsEnabled,
                currentServerSlug = currentServerSlug,
                contentExtrasVisible = contentExtrasVisible,
                onThreadEvent = onThreadEvent,
                onOpenMember = onOpenMember,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                diagnosticsLogger = diagnosticsLogger
            )
    }
}

@Composable
private fun ThreadMessagesList(
    payload: KuiklyThreadScreenPayload,
    composerKeyboardInsetDp: Float,
    linkedTasksByMessageId: Map<String, TaskSummary>,
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    if (payload.messages.isEmpty() && payload.originStatus == "none") {
        ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
        return
    }
    // Bottom-anchor signature: only the fields the anchor logic actually
    // reads. The previous remember keys listed `payload.messages` (the
    // list reference) which invalidated the cache on any list
    // replacement, even when the content was equivalent. Now the cache
    // is keyed on the content fields the underlying functions read.
    val topAnchoredOriginContent = payload.hasThreadOriginItem()
    val latestItemIndex = remember(
        payload.originStatus,
        payload.messages.size
    ) {
        payload.latestMessageListItemIndex()
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex =
            threadInitialFirstVisibleItemIndex(
                latestItemIndex = latestItemIndex,
                topAnchoredContent = topAnchoredOriginContent
            )
    )
    val coroutineScope = rememberCoroutineScope()
    // Phase 1 (#172) instrumentation: per-Thread session marker that
    // uniquely identifies this Thread entry. Combined with the row-level
    // `LaunchedEffect(message.id, sessionMarker)` inside `items()`, this
    // yields exactly one log per row per Thread session, scoped to a
    // single session so later Threads do not see stale logs.
    val threadRowProbeSessionMarker = remember(payload.sourceThreadId, payload.instanceId) {
        ThreadRowProbeNextSessionId.value()
    }
    val firstViewportProbe = remember(payload.sourceThreadId, payload.instanceId) {
        ThreadFirstViewportProbe(threadRowProbeSessionMarker)
    }
    var actionMessageId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var actionMenuAnchor by remember(payload.instanceId) { mutableStateOf(Offset(16f, 16f)) }
    var taskStatusMenuMessageId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var taskStatusMenuAnchor by remember(payload.instanceId) { mutableStateOf(Offset(16f, 16f)) }
    var allowBottomAutoAnchor by remember(payload.instanceId, topAnchoredOriginContent) {
        mutableStateOf(!topAnchoredOriginContent)
    }
    var initialBottomAnchorReady by remember(payload.instanceId, topAnchoredOriginContent) {
        mutableStateOf(topAnchoredOriginContent)
    }
    var lastComposerKeyboardInsetDp by remember(payload.instanceId) { mutableStateOf(composerKeyboardInsetDp) }
    var pendingKeyboardBottomAnchor by remember(payload.instanceId) { mutableStateOf(false) }
    val latestMessageLayoutKey = remember(
        payload.originStatus,
        payload.canLoadMoreBefore,
        payload.canLoadMoreAfter,
        payload.messages.size,
        payload.messages.lastOrNull()?.id,
        payload.messages.lastOrNull()?.body?.length,
        payload.messages.lastOrNull()?.attachments?.size,
        payload.messages.lastOrNull()?.taskId,
        payload.messages.lastOrNull()?.replyCount,
        payload.messages.lastOrNull()?.unreadReplyCount,
        payload.messages.lastOrNull()?.threadReactionLayoutSignature()
    ) {
        payload.latestMessageLayoutKey()
    }
    val latestLocalSendBottomAnchorKey = remember(
        payload.messages.size,
        payload.messages.lastOrNull()?.id,
        payload.messages.lastOrNull()?.deliveryState,
        payload.messages.lastOrNull()?.body?.length,
        payload.messages.lastOrNull()?.attachments?.size,
        payload.messages.lastOrNull()?.pendingSendContext?.attachmentIds?.size
    ) {
        payload.latestLocalSendBottomAnchorKey()
    }
    val density = LocalDensity.current
    val initialContentLayoutKey = remember(
        payload.originStatus,
        payload.canLoadMoreBefore,
        payload.canLoadMoreAfter,
        payload.messages
    ) {
        payload.threadInitialContentLayoutKey()
    }
    val initialMessageMinHeightsPx = remember(initialContentLayoutKey, density) {
        payload.threadInitialMessageMinHeightsPx(density)
    }
    val showReplyEmptyState = payload.messages.isEmpty() && payload.originStatus != "none"
    val lazyItemCount =
        remember(
            payload.originStatus,
            payload.canLoadMoreBefore,
            payload.messages.size,
            payload.canLoadMoreAfter,
            showReplyEmptyState
        ) {
            threadMessagesLazyItemCount(
                originStatus = payload.originStatus,
                showHistoryBoundary = payload.hasThreadHistoryBoundaryItem(),
                messagesCount = payload.messages.size,
                canLoadMoreAfter = payload.canLoadMoreAfter
            ) + if (showReplyEmptyState) 1 else 0
        }
    var pendingOlderPageAnchor by remember(payload.instanceId) { mutableStateOf<ThreadVisibleMessageAnchor?>(null) }
    var previousOlderMessagesStatus by remember(payload.instanceId) { mutableStateOf(payload.olderMessagesStatus) }
    LaunchedEffect(payload.instanceId, payload.messages.size, lazyItemCount) {
        repeat(ThreadBottomAnchorMinimumAttempts + 1) { attempt ->
            listState.awaitThreadMessagesLayoutFrame()
            val stats = listState.threadLazyWindowStats(payload, lazyItemCount)
            if (stats.visibleItems > 0) {
                diagnosticsLogger(stats.toDiagnosticsString(result = "laid_out"))
                if (firstViewportProbe.markLogged()) {
                    diagnosticsLogger(
                        firstViewportProbe.toDiagnosticsString(
                            result = "laid_out",
                            payload = payload,
                            lazyItemCount = lazyItemCount,
                            stats = stats
                        )
                    )
                }
                return@LaunchedEffect
            }
            if (attempt >= ThreadBottomAnchorMinimumAttempts) {
                diagnosticsLogger(stats.toDiagnosticsString(result = "missing_layout"))
                if (firstViewportProbe.markLogged()) {
                    diagnosticsLogger(
                        firstViewportProbe.toDiagnosticsString(
                            result = "missing_layout",
                            payload = payload,
                            lazyItemCount = lazyItemCount,
                            stats = stats
                        )
                    )
                }
            }
            delay(ThreadLayoutStabilityFrameDelayMs)
        }
    }
    LaunchedEffect(payload.instanceId, latestItemIndex) {
        if (topAnchoredOriginContent) return@LaunchedEffect
        snapshotFlow {
            listState.isScrollInProgress to listState.isLatestItemAtRenderedBottom(latestItemIndex)
        }
            .distinctUntilChanged()
            .collect { (isScrollInProgress, isAtBottom) ->
                val nextAllow =
                    when {
                        isScrollInProgress && !isAtBottom -> false
                        isAtBottom -> true
                        else -> allowBottomAutoAnchor
                    }
                if (allowBottomAutoAnchor != nextAllow) {
                    allowBottomAutoAnchor = nextAllow
                }
            }
    }
    LaunchedEffect(
        payload.instanceId,
        payload.canLoadMoreBefore,
        payload.olderMessagesStatus,
        initialBottomAnchorReady,
        payload.messages.size
    ) {
        if (!initialBottomAnchorReady ||
            !payload.canLoadMoreBefore ||
            payload.olderMessagesStatus != "idle"
        ) {
            return@LaunchedEffect
        }
        val boundaryIndex = payload.threadHistoryBoundaryListItemIndex()
        snapshotFlow { listState.isThreadOlderAutoLoadThreshold(boundaryIndex) }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    if (allowBottomAutoAnchor) {
                        allowBottomAutoAnchor = false
                    }
                    onThreadEvent("loadOlder", null, null, null)
                }
            }
    }
    LaunchedEffect(
        payload.instanceId,
        payload.olderMessagesStatus,
        payload.messages.size,
        payload.messages.firstOrNull()?.id
    ) {
        val previousStatus = previousOlderMessagesStatus
        if (payload.olderMessagesStatus == "loading" && previousStatus != "loading") {
            pendingOlderPageAnchor = listState.threadFirstVisibleMessageAnchor(payload)
            allowBottomAutoAnchor = false
        } else if (previousStatus == "loading" && payload.olderMessagesStatus != "loading") {
            val anchor = pendingOlderPageAnchor
            pendingOlderPageAnchor = null
            allowBottomAutoAnchor = false
            if (anchor != null) {
                val targetIndex = payload.threadMessageListItemIndex(anchor.messageId)
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex, -anchor.offset)
                }
            }
        }
        previousOlderMessagesStatus = payload.olderMessagesStatus
    }
    LaunchedEffect(payload.instanceId, latestLocalSendBottomAnchorKey, latestItemIndex) {
        if (topAnchoredOriginContent) return@LaunchedEffect
        if (latestLocalSendBottomAnchorKey == null || latestItemIndex < 0) return@LaunchedEffect
        allowBottomAutoAnchor = true
        listState.awaitThreadMessagesLayoutFrame()
        listState.awaitThreadMessagesLayoutStability(latestItemIndex)
        repeat(ThreadBackToBottomAnchorAttempts) { attempt ->
            listState.scrollLatestItemToRenderedBottom(latestItemIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
            if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                listState.isLatestItemAtRenderedBottom(latestItemIndex)
            ) {
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(payload.instanceId, latestMessageLayoutKey, latestItemIndex) {
        if (topAnchoredOriginContent) {
            initialBottomAnchorReady = true
            return@LaunchedEffect
        }
        if (latestItemIndex < 0) return@LaunchedEffect
        if (!initialBottomAnchorReady) {
            repeat(ThreadInitialBottomAnchorAttempts) { attempt ->
                listState.awaitThreadMessagesLayoutFrame()
                val contentReady =
                    listState.isInitialThreadContentReady(latestItemIndex, initialMessageMinHeightsPx)
                if (contentReady) {
                    listState.scrollLatestItemToRenderedBottom(latestItemIndex)
                } else if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                    listState.canScrollToThreadItem(latestItemIndex)
                ) {
                    listState.scrollToItem(latestItemIndex)
                }
                delay(ThreadLayoutStabilityFrameDelayMs)
                if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                    contentReady &&
                    listState.isLatestItemAtRenderedBottom(latestItemIndex)
                ) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
            }
            if (listState.canScrollToThreadItem(latestItemIndex)) {
                listState.scrollToItem(latestItemIndex)
                listState.awaitThreadMessagesLayoutStability(latestItemIndex, initialMessageMinHeightsPx)
                listState.scrollLatestItemToRenderedBottom(latestItemIndex)
            }
            initialBottomAnchorReady = true
            return@LaunchedEffect
        }
        if (!allowBottomAutoAnchor && !listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
            return@LaunchedEffect
        }
        listState.awaitThreadMessagesLayoutStability(latestItemIndex)
        if (listState.isScrollInProgress && !listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
            if (allowBottomAutoAnchor) {
                allowBottomAutoAnchor = false
            }
            return@LaunchedEffect
        }
        if (!listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
            listState.scrollLatestItemToRenderedBottom(latestItemIndex)
        }
    }
    LaunchedEffect(payload.instanceId, composerKeyboardInsetDp, latestItemIndex) {
        if (topAnchoredOriginContent) return@LaunchedEffect
        if (composerKeyboardInsetDp == lastComposerKeyboardInsetDp) {
            return@LaunchedEffect
        }
        val wasAtBottom = initialBottomAnchorReady &&
            latestItemIndex >= 0 &&
            allowBottomAutoAnchor
        lastComposerKeyboardInsetDp = composerKeyboardInsetDp
        if (!wasAtBottom) {
            pendingKeyboardBottomAnchor = false
            return@LaunchedEffect
        }
        allowBottomAutoAnchor = true
        pendingKeyboardBottomAnchor = true
        listState.awaitThreadMessagesLayoutStability(latestItemIndex)
        repeat(ThreadKeyboardBottomAnchorAttempts) { attempt ->
            listState.scrollLatestItemToRenderedBottom(latestItemIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
            if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                listState.isLatestItemAtRenderedBottom(latestItemIndex)
            ) {
                pendingKeyboardBottomAnchor = false
                return@LaunchedEffect
            }
        }
        pendingKeyboardBottomAnchor = false
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .alpha(if (initialBottomAnchorReady || latestItemIndex < 0) 1f else 0f)
                    .bouncesEnable(false),
                verticalArrangement = if (topAnchoredOriginContent) Arrangement.Top else Arrangement.Bottom,
                beyondBoundsItemCount = threadMessagesBeyondBoundsItemCount(lazyItemCount)
            ) {
            if (payload.hasThreadOriginItem()) {
                item(key = ThreadOriginListItemKey) {
                    ThreadOriginCard(
                        payload = payload,
                        highlightedMentions = highlightedMentions,
                        pendingActionCardIds = pendingActionCardIds,
                        attachmentOpenStates = attachmentOpenStates,
                        attachmentCommentsEnabled = attachmentCommentsEnabled,
                        currentServerSlug = currentServerSlug,
                        contentExtrasVisible = contentExtrasVisible,
                        onThreadEvent = onThreadEvent,
                        onOpenMember = onOpenMember,
                        onShowActions = { message, anchor ->
                            actionMenuAnchor = anchor
                            actionMessageId = message.id
                            diagnosticsLogger(
                                "thread_origin_menu_tap messageId=${message.id} rootX=${anchor.x} rootY=${anchor.y}"
                            )
                        },
                        onOpenTaskStatusMenu = { message, anchor ->
                            taskStatusMenuMessageId = message.id
                            taskStatusMenuAnchor = anchor
                        },
                        onOpenMarkdownTag = onOpenMarkdownTag,
                        onOpenMarkdownUrl = onOpenMarkdownUrl
                    )
                }
            }
            if (payload.hasThreadHistoryBoundaryItem()) {
                item(key = ThreadOlderMessagesListItemKey) {
                    when {
                        payload.canLoadMoreBefore && payload.olderMessagesStatus == "error" ->
                            ThreadInlineActionRow(
                                label = "Retry older messages",
                                onClick = { onThreadEvent("retryLoadOlder", null, null, null) }
                            )
                        payload.canLoadMoreBefore ->
                            ThreadHistoryStatusRow(
                                label = "Loading older messages…",
                                iconName = SlockLucideIconName.LoaderCircle
                            )
                        else ->
                            ThreadHistoryStatusRow(
                                label = "Beginning of conversation",
                                iconName = SlockLucideIconName.Flag
                            )
                    }
                }
            }
            if (showReplyEmptyState) {
                item(key = ThreadReplyEmptyListItemKey) {
                    ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
                }
            }
            items(payload.messages.size, key = { payload.messages[it].messageListItemKey() }) { index ->
                val message = payload.messages[index]
                SideEffect {
                    firstViewportProbe.recordComposedMessage(message.id, index)
                }
                val messagePerformanceKey = threadMessagePerformanceKey(message.id, index)
                val isSystemMessage = remember(message.messageType) { message.isSystemMessage() }
                val actionCardSignature =
                    if (contentExtrasVisible) {
                        remember(message.id, message.metadata) { message.threadActionCardSignature() }
                    } else {
                        null
                    }
                val actionCard =
                    if (contentExtrasVisible) {
                        remember(actionCardSignature, messagePerformanceKey) {
                            val startedAt = TimeSource.Monotonic.markNow()
                            message.threadActionCard()
                                .also { card ->
                                    val durationMs = startedAt.elapsedNow().inWholeMilliseconds
                                    if (shouldLogThreadPerformance(durationMs)) {
                                        diagnosticsLogger(
                                            "thread_action_card_parse key=$messagePerformanceKey " +
                                                "hasActionCard=${card != null} durationMs=$durationMs"
                                        )
                                    }
                                }
                        }
                    } else {
                        null
                    }
                // Phase 1 (#172) instrumentation: one-shot per-row log gated
                // by DEBUG_THREAD_VIRTUAL_RENDER_LOG so the diagnostic is
                // not a hot path. Fires on first composition of this row
                // per Thread sessionMarker; does not re-fire on scroll.
                if (DEBUG_THREAD_VIRTUAL_RENDER_LOG) {
                    androidx.compose.runtime.LaunchedEffect(message.id, threadRowProbeSessionMarker) {
                        diagnosticsLogger(
                            "thread_row_compose sessionMarker=${threadRowProbeSessionMarker} " +
                                "index=$index id=${message.id} messageType=${message.messageType} " +
                                "bodyLength=${message.body.length} isSystem=${isSystemMessage} " +
                                "hasActionCard=${actionCard != null} " +
                                "attachmentCount=${message.attachments.size} " +
                                "threadId=${payload.sourceThreadId.orEmpty()} " +
                                "messagesTotal=${payload.messages.size}"
                        )
                    }
                }
                val linkedTask = linkedTasksByMessageId[message.id]
                val displayMessage =
                    remember(message, linkedTask, messagePerformanceKey) {
                        val startedAt = TimeSource.Monotonic.markNow()
                        message.withLinkedTask(linkedTask)
                            .also {
                                val durationMs = startedAt.elapsedNow().inWholeMilliseconds
                                if (shouldLogThreadPerformance(durationMs)) {
                                    diagnosticsLogger(
                                        "thread_row_heavy_work stage=linked_task key=$messagePerformanceKey " +
                                            "hasLinkedTask=${linkedTask != null} durationMs=$durationMs"
                                    )
                                }
                            }
                    }
                val linkedTaskAssigneeName = linkedTask?.threadLinkedTaskAssigneeDisplayName()
                Box(
                    modifier = Modifier.onGloballyPositioned {
                        firstViewportProbe.recordPlacedMessage(message.id, index)
                    }
                ) {
                    if (isSystemMessage && actionCard == null) {
                        ThreadSystemMessageRow(
                            message = displayMessage,
                            highlightedMentions = highlightedMentions,
                            showReplyAction = payload.showReplyActions && contentExtrasVisible,
                            timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                            resolvedTaskAssigneeName = linkedTaskAssigneeName,
                            resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                            taskOpenEnabled = payload.showTaskOpenActions && contentExtrasVisible,
                            taskStatusActionsEnabled = payload.showTaskStatusActions && contentExtrasVisible,
                            onOpenThread = { onThreadEvent("openMessageThread", message.id, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor ->
                                taskStatusMenuMessageId = message.id
                                taskStatusMenuAnchor = anchor
                            },
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl
                        )
                    } else {
                        ThreadMessageCard(
                            message = displayMessage,
                            actionCard = actionCard,
                            messageIndex = index,
                            diagnosticsLogger = diagnosticsLogger,
                            highlightedMentions = highlightedMentions,
                            alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                            showReplyAction = payload.showReplyActions,
                            resolvedTaskAssigneeName = linkedTaskAssigneeName,
                            resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                            taskOpenEnabled = payload.showTaskOpenActions && contentExtrasVisible,
                            taskStatusActionsEnabled = payload.showTaskStatusActions && contentExtrasVisible,
                            knownMarkdownChannelNames = payload.knownMarkdownChannelNames.toKnownMarkdownChannelNameSet(),
                            onShowActions = { anchor ->
                                if (!message.hasLocalSendState()) {
                                    actionMenuAnchor = anchor
                                    actionMessageId = message.id
                                    diagnosticsLogger(
                                        "thread_message_menu_tap messageId=${message.id} rootX=${anchor.x} rootY=${anchor.y}"
                                    )
                                }
                            },
                            onOpenMember = onOpenMember,
                            onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor ->
                                taskStatusMenuMessageId = message.id
                                taskStatusMenuAnchor = anchor
                            },
                            onToggleReaction = { emoji -> onThreadEvent("toggleReaction", message.id, null, emoji) },
                            onToggleSaved = { onThreadEvent("toggleMessageSaved", message.id, null, null) },
                            onOpenCommentHost = { onThreadEvent("openCommentHost", message.id, null, null) },
                            timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                            onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                            attachmentCommentsEnabled = attachmentCommentsEnabled,
                            onOpenAttachmentComments = { onThreadEvent("openAttachmentComments", message.id, it, null) },
                            onRetryLocalSend = { onThreadEvent("retryLocalSend", message.id, null, null) },
                            attachmentOpenStates = if (contentExtrasVisible) attachmentOpenStates else emptyMap(),
                            pendingActionCardIds = if (contentExtrasVisible) pendingActionCardIds else emptySet(),
                            onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
                            currentServerSlug = currentServerSlug,
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl,
                            richContentEnabled = contentExtrasVisible
                        )
                    }
                }
            }
            if (payload.canLoadMoreAfter) {
                item(key = ThreadNewerMessagesListItemKey) {
                    ThreadInlineActionRow(
                        label = when (payload.newerMessagesStatus) {
                            "loading" -> "Loading new messages…"
                            "error" -> "Retry new messages"
                            else -> "Load new messages"
                        },
                        onClick = {
                            onThreadEvent(
                                if (payload.newerMessagesStatus == "error") "retryLoadNewer" else "loadNewer",
                                null,
                                null,
                                null
                            )
                        }
                    )
                }
            }
        }
        if (!initialBottomAnchorReady && latestItemIndex >= 0) {
            ThreadStateMessage("Loading messages", "Rendering conversation layout.")
        }
        if (!topAnchoredOriginContent &&
            initialBottomAnchorReady &&
            !pendingKeyboardBottomAnchor &&
            listState.shouldShowThreadBackToBottom(latestItemIndex)
        ) {
            ThreadBackToBottomButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = SlockSpacing.Md, bottom = SlockSpacing.Md),
                onClick = {
                    coroutineScope.launch {
                        allowBottomAutoAnchor = true
                        repeat(ThreadBackToBottomAnchorAttempts) { attempt ->
                            listState.scrollLatestItemToRenderedBottom(latestItemIndex)
                            if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                                listState.isLatestItemAtRenderedBottom(latestItemIndex)
                            ) {
                                return@launch
                            }
                            delay(16)
                        }
                    }
                }
            )
        }
        actionMessageId?.let { messageId ->
            payload.threadActionMessage(messageId)?.let { message ->
                ThreadMessageActionMenu(
                    message = message,
                    anchor = actionMenuAnchor,
                    showReplyAction = payload.showReplyActions,
                    showSaveAction = payload.showMessageSaveAction && !message.hasLocalSendState(),
                    showTaskStatusAction = payload.showTaskStatusActions,
                    onDismiss = { actionMessageId = null },
                    onReply = {
                        actionMessageId = null
                        onThreadEvent("openMessageThread", message.id, null, null)
                    },
                    onCopy = {
                        actionMessageId = null
                        onThreadEvent("copyMessage", message.id, null, null)
                    },
                    onSave = {
                        actionMessageId = null
                        onThreadEvent("toggleMessageSaved", message.id, null, null)
                    },
                    onReact = { emoji ->
                        actionMessageId = null
                        onThreadEvent("toggleReaction", message.id, null, emoji)
                    },
                    onTaskStatusChange = { action ->
                        actionMessageId = null
                        onThreadEvent(action, message.taskId, null, null)
                    },
                    diagnosticsLogger = diagnosticsLogger
                )
            }
        }
        taskStatusMenuMessageId?.let { messageId ->
            payload.threadActionMessage(messageId)?.let { message ->
                val selectedStatus = message.taskStatus.threadTaskStatusOption()
                SlockBaseMenuOverlay(
                    anchor = taskStatusMenuAnchor,
                    items = ThreadTaskStatusMenuItems,
                    selectedId = selectedStatus.key,
                    selectedRowColor = SlockColor.BrandYellow,
                    onDismiss = { taskStatusMenuMessageId = null },
                    onSelect = { selectedId ->
                        taskStatusMenuMessageId = null
                        val taskId = message.taskId?.takeIf { it.isNotBlank() }
                        val status = selectedId.toSharedThreadTaskStatus()
                        if (taskId != null && status != null && status.name != selectedStatus.key) {
                            onThreadEvent("setTaskStatus", taskId, null, selectedId)
                        }
                    }
                )
            }
        }
    }
}
}

private const val ThreadOlderMessagesListItemKey = "thread-page:older-messages"
private const val ThreadNewerMessagesListItemKey = "thread-page:newer-messages"
private const val ThreadReplyEmptyListItemKey = "thread-page:reply-empty"
private const val ThreadOriginListItemKey = "thread-page:origin"

internal fun threadMessagesLazyItemCount(
    originStatus: String,
    showHistoryBoundary: Boolean,
    messagesCount: Int,
    canLoadMoreAfter: Boolean
): Int =
    (if (originStatus != "none") 1 else 0) +
        (if (showHistoryBoundary) 1 else 0) +
        messagesCount.coerceAtLeast(0) +
        (if (canLoadMoreAfter) 1 else 0)

internal fun threadMessagesBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceAtLeast(0)
        .coerceAtMost(ThreadMessagesMaxBeyondBoundsItemCount)

private data class ThreadLazyWindowStats(
    val totalItems: Int,
    val totalMessages: Int,
    val beyondBoundsItems: Int,
    val visibleItems: Int,
    val firstVisibleItem: Int,
    val lastVisibleItem: Int,
    val firstVisibleMessage: Int,
    val lastVisibleMessage: Int,
    val visibleMessages: Int
)

private fun threadMessagePerformanceKey(messageId: String, index: Int): String =
    "message:$index:${messageId.take(8)}"

private fun LazyListState.threadLazyWindowStats(
    payload: KuiklyThreadScreenPayload,
    lazyItemCount: Int
): ThreadLazyWindowStats {
    val visibleItems = layoutInfo.visibleItemsInfo
    val firstVisibleItem = visibleItems.firstOrNull()?.index ?: -1
    val lastVisibleItem = visibleItems.lastOrNull()?.index ?: -1
    val firstMessageListItemIndex = payload.firstMessageListItemIndex()
    val visibleMessageIndexes =
        visibleItems.mapNotNull { item ->
            (item.index - firstMessageListItemIndex)
                .takeIf { it in payload.messages.indices }
        }
    return ThreadLazyWindowStats(
        totalItems = lazyItemCount,
        totalMessages = payload.messages.size,
        beyondBoundsItems = threadMessagesBeyondBoundsItemCount(lazyItemCount),
        visibleItems = visibleItems.size,
        firstVisibleItem = firstVisibleItem,
        lastVisibleItem = lastVisibleItem,
        firstVisibleMessage = visibleMessageIndexes.firstOrNull() ?: -1,
        lastVisibleMessage = visibleMessageIndexes.lastOrNull() ?: -1,
        visibleMessages = visibleMessageIndexes.size
    )
}

private fun ThreadLazyWindowStats.toDiagnosticsString(result: String): String =
    "thread_lazy_window result=$result totalItems=$totalItems totalMessages=$totalMessages " +
        "beyondBounds=$beyondBoundsItems visibleItems=$visibleItems " +
        "visibleItemRange=$firstVisibleItem..$lastVisibleItem " +
        "visibleMessageRange=$firstVisibleMessage..$lastVisibleMessage " +
        "visibleMessages=$visibleMessages"

internal fun threadInitialFirstVisibleItemIndex(
    latestItemIndex: Int,
    topAnchoredContent: Boolean = false
): Int =
    if (topAnchoredContent) 0 else latestItemIndex.coerceAtLeast(0)

private fun KuiklyThreadMessageItem.messageListItemKey(): String =
    "thread-page:message:$id"

internal data class ThreadMessageActionCardSignature(
    val messageId: String,
    val metadata: Map<String, String>
)

internal fun KuiklyThreadMessageItem.threadActionCardSignature(): ThreadMessageActionCardSignature =
    ThreadMessageActionCardSignature(
        messageId = id,
        metadata = metadata
    )

internal fun KuiklyThreadMessageItem.threadActionCard(): ThreadActionCard? =
    actionCardFromMessageMetadata(metadata)

private fun KuiklyThreadScreenPayload.threadActionMessage(messageId: String): KuiklyThreadMessageItem? =
    messages.firstOrNull { it.id == messageId }
        ?: originMessage?.takeIf { originStatus != "none" && it.id == messageId }

internal fun formatThreadMessageQuote(text: String): String =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .trimEnd()
        .takeIf { it.trim().isNotBlank() }
        ?.let { normalized ->
            normalized
                .split('\n')
                .joinToString("\n") { line ->
                    if (line.trim().isNotEmpty()) {
                        "> $line"
                    } else {
                        ">"
                    }
                }
            }
        .orEmpty()

internal fun appendThreadQuoteToComposer(existing: String, quote: String): String {
    val cleanQuote = quote.takeIf { it.isNotBlank() } ?: return existing
    val base = existing.trimEnd()
    return if (base.isBlank()) {
        "$cleanQuote\n\n"
    } else {
        "$base\n\n$cleanQuote\n\n"
    }
}

private fun KuiklyThreadScreenPayload.hasThreadHistoryBoundaryItem(): Boolean =
    messages.isNotEmpty()

private fun KuiklyThreadScreenPayload.hasThreadOriginItem(): Boolean =
    originStatus != "none"

private fun KuiklyThreadScreenPayload.threadHistoryBoundaryListItemIndex(): Int =
    if (hasThreadOriginItem()) 1 else 0

private fun KuiklyThreadScreenPayload.firstMessageListItemIndex(): Int {
    var index = 0
    if (hasThreadOriginItem()) index += 1
    if (hasThreadHistoryBoundaryItem()) index += 1
    return index
}

private fun KuiklyThreadScreenPayload.threadMessageListItemIndex(messageId: String): Int {
    val messageIndex = messages.indexOfFirst { it.id == messageId }
    if (messageIndex < 0) return -1
    return firstMessageListItemIndex() + messageIndex
}

internal fun KuiklyThreadScreenPayload.latestMessageListItemIndex(): Int {
    if (messages.isEmpty()) return -1
    return firstMessageListItemIndex() + messages.lastIndex
}

internal fun KuiklyThreadScreenPayload.latestMessageLayoutKey(): String {
    val latest = messages.lastOrNull()
        ?: return "empty:$originStatus:$canLoadMoreBefore:$canLoadMoreAfter"
    return buildString {
        append(originStatus)
        append(':')
        append(hasThreadHistoryBoundaryItem())
        append(':')
        append(canLoadMoreAfter)
        append(':')
        append(messages.size)
        append(':')
        append(latest.id)
        append(':')
        append(latest.body.length)
        append(':')
        append(latest.attachments.size)
        append(':')
        append(latest.taskId.orEmpty())
        append(':')
        append(latest.replyCount)
        append(':')
        append(latest.unreadReplyCount)
        append(':')
        append(latest.threadReactionLayoutSignature())
    }
}

private fun KuiklyThreadScreenPayload.latestLocalSendBottomAnchorKey(): String? {
    val latest = messages.lastOrNull() ?: return null
    if (!latest.deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) ||
        latest.pendingSendContext == null
    ) {
        return null
    }
    return buildString {
        append(latest.id)
        append(':')
        append(latest.body.length)
        append(':')
        append(latest.attachments.size)
        append(':')
        append(latest.pendingSendContext.attachmentIds.size)
    }
}

private fun KuiklyThreadScreenPayload.threadInitialContentLayoutKey(): String =
    buildString {
        append(originStatus)
        append(':')
        append(hasThreadHistoryBoundaryItem())
        append(':')
        append(canLoadMoreAfter)
        append(':')
        append(messages.size)
        messages.forEach { message ->
            append('|')
            append(message.id)
            append(':')
            append(message.messageType)
            append(':')
            append(message.body.length)
            append(':')
            append(message.attachments.size)
            append(':')
            append(message.taskId.orEmpty())
            append(':')
            append(message.replyCount)
            append(':')
            append(message.unreadReplyCount)
        }
    }

private fun KuiklyThreadScreenPayload.threadInitialMessageMinHeightsPx(density: Density): Map<Int, Int> {
    if (messages.isEmpty()) return emptyMap()
    val firstMessageIndex = firstMessageListItemIndex()
    return messages.mapIndexed { index, message ->
        val minHeightPx =
            with(density) {
                message.threadInitialMessageMinHeightDp().toPx().roundToInt()
            }
        firstMessageIndex + index to minHeightPx
    }.toMap()
}

private fun KuiklyThreadMessageItem.threadInitialMessageMinHeightDp(): Dp {
    if (isSystemMessage()) {
        val bodyLines = body.threadInitialMarkdownLineEstimate().coerceAtMost(3)
        return (32f + bodyLines * 16f).dp
    }
    val bodyLines = body.threadInitialMarkdownLineEstimate()
    val bodyHeight = if (bodyLines > 0) bodyLines * 16f else 0f
    val attachmentHeight = attachments.size.coerceAtMost(2) * 44f
    val footerHeight =
        if (replyCount > 0 || unreadReplyCount > 0 || !taskId.isNullOrBlank() || reactions.any { it.count > 0 }) {
            28f
        } else {
            0f
        }
    return (48f + bodyHeight + attachmentHeight + footerHeight)
        .coerceAtLeast(58f)
        .coerceAtMost(260f)
        .dp
}

private fun String.threadInitialMarkdownLineEstimate(): Int {
    if (isBlank()) return 0
    val softWrappedLines =
        lineSequence()
            .map { line -> (line.length / ThreadInitialMarkdownEstimatedCharsPerLine) + 1 }
            .sum()
    return softWrappedLines.coerceIn(1, ThreadInitialMarkdownMaxEstimatedLines)
}

private fun KuiklyThreadMessageItem.threadReactionLayoutSignature(): String =
    reactions
        .filter { reaction -> reaction.count > 0 }
        .joinToString(separator = "|") { reaction ->
            "${reaction.emoji}:${reaction.count}:${reaction.selected}"
        }

internal fun KuiklyThreadScreenPayload.visibleReadAllMarkerKey(): String? {
    val threadId = sourceThreadId?.takeIf { it.isNotBlank() } ?: return null
    if (status != "content") return null
    val latest = messages.lastOrNull() ?: return null
    return buildString {
        append(threadId)
        append(':')
        append(latest.seq?.toString() ?: "no-seq")
        append(':')
        append(latest.id)
    }
}

internal fun KuiklyThreadScreenPayload.toSlockTimeContext(): SlockTimeContext =
    SlockTimeContext(
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

internal fun KuiklyThreadScreenPayload.formatTimestampLabel(value: String): String =
    formatSlockTimestampLabel(value, toSlockTimeContext())

private data class ThreadVisibleMessageAnchor(
    val messageId: String,
    val offset: Int
)

private fun LazyListState.threadFirstVisibleMessageAnchor(
    payload: KuiklyThreadScreenPayload
): ThreadVisibleMessageAnchor? {
    val firstMessageListItemIndex = payload.firstMessageListItemIndex()
    val messageCount = payload.messages.size
    if (messageCount <= 0) return null
    val item =
        layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                item.index >= firstMessageListItemIndex &&
                    item.index < firstMessageListItemIndex + messageCount
            }
            ?: return null
    val message = payload.messages.getOrNull(item.index - firstMessageListItemIndex) ?: return null
    return ThreadVisibleMessageAnchor(messageId = message.id, offset = item.offset)
}

private fun LazyListState.isThreadOlderAutoLoadThreshold(boundaryIndex: Int): Boolean {
    if (boundaryIndex < 0) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= boundaryIndex) return false
    val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return false
    return firstVisibleIndex <= boundaryIndex + ThreadOlderAutoLoadVisibleItemThreshold
}

private suspend fun LazyListState.awaitThreadMessagesLayoutFrame(): Boolean {
    repeat(ThreadLayoutStabilityMaxFrames) {
        val layoutInfo = layoutInfo
        if (layoutInfo.totalItemsCount > 0 &&
            layoutInfo.visibleItemsInfo.isNotEmpty() &&
            layoutInfo.viewportEndOffset > layoutInfo.viewportStartOffset
        ) {
            return true
        }
        delay(ThreadLayoutStabilityFrameDelayMs)
    }
    return false
}

private fun LazyListState.isThreadItemVisible(index: Int): Boolean {
    if (index < 0) return false
    return layoutInfo.visibleItemsInfo.any { it.index == index }
}

private fun LazyListState.canScrollToThreadItem(index: Int): Boolean =
    index >= 0 && layoutInfo.totalItemsCount > index

private suspend fun LazyListState.scrollLatestItemToRenderedBottom(latestItemIndex: Int) {
    if (latestItemIndex < 0) return
    if (layoutInfo.totalItemsCount <= latestItemIndex) {
        return
    }
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val offset =
        latestItem
            ?.let {
                threadLatestItemBottomAnchorScrollOffset(
                    itemSize = it.size,
                    viewportHeight = viewportHeight,
                    visibleItemCount = layoutInfo.visibleItemsInfo.size,
                    totalItemCount = layoutInfo.totalItemsCount
                )
            }
            ?: 0
    scrollToItem(latestItemIndex, offset)
}

private suspend fun LazyListState.awaitThreadMessagesLayoutStability(
    latestItemIndex: Int,
    minHeightsByListItemIndex: Map<Int, Int> = emptyMap()
): Boolean {
    var previous: ThreadMessagesLayoutStabilitySnapshot? = null
    var stableFrames = 0
    repeat(ThreadLayoutStabilityMaxFrames) {
        val current = threadMessagesLayoutStabilitySnapshot(latestItemIndex)
        val contentReady = isInitialThreadContentReady(latestItemIndex, minHeightsByListItemIndex)
        if (current.canAnchor && contentReady && current == previous) {
            stableFrames += 1
            if (stableFrames >= ThreadLayoutStabilityRequiredFrames) {
                return true
            }
        } else {
            previous = current
            stableFrames = 0
        }
        delay(ThreadLayoutStabilityFrameDelayMs)
    }
    return false
}

private fun LazyListState.isInitialThreadContentReady(
    latestItemIndex: Int,
    minHeightsByListItemIndex: Map<Int, Int>
): Boolean {
    val layoutInfo = layoutInfo
    if (latestItemIndex < 0 || layoutInfo.totalItemsCount <= latestItemIndex) return false
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.none { it.index == latestItemIndex }) return false
    if (minHeightsByListItemIndex.isEmpty()) return true
    return visibleItems
        .asSequence()
        .filter { item -> minHeightsByListItemIndex.containsKey(item.index) }
        .all { item ->
            val minHeight = minHeightsByListItemIndex[item.index] ?: 0
            item.size + ThreadInitialContentHeightTolerancePx >= minHeight
        }
}

private fun LazyListState.threadMessagesLayoutStabilitySnapshot(
    latestItemIndex: Int
): ThreadMessagesLayoutStabilitySnapshot {
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val first = visibleItems.firstOrNull()
    val last = visibleItems.lastOrNull()
    val latest = visibleItems.firstOrNull { it.index == latestItemIndex }
    return ThreadMessagesLayoutStabilitySnapshot(
        totalItemCount = layoutInfo.totalItemsCount,
        viewportStartOffset = layoutInfo.viewportStartOffset,
        viewportEndOffset = layoutInfo.viewportEndOffset,
        visibleItemCount = visibleItems.size,
        firstItemIndex = first?.index ?: -1,
        firstItemOffset = first?.offset ?: 0,
        firstItemSize = first?.size ?: 0,
        lastItemIndex = last?.index ?: -1,
        lastItemOffset = last?.offset ?: 0,
        lastItemSize = last?.size ?: 0,
        latestItemIndex = latest?.index ?: -1,
        latestItemOffset = latest?.offset ?: 0,
        latestItemSize = latest?.size ?: 0,
        canAnchor = latestItemIndex >= 0 &&
            layoutInfo.totalItemsCount > latestItemIndex &&
            visibleItems.isNotEmpty() &&
            layoutInfo.viewportEndOffset > layoutInfo.viewportStartOffset
    )
}

private data class ThreadMessagesLayoutStabilitySnapshot(
    val totalItemCount: Int,
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
    val visibleItemCount: Int,
    val firstItemIndex: Int,
    val firstItemOffset: Int,
    val firstItemSize: Int,
    val lastItemIndex: Int,
    val lastItemOffset: Int,
    val lastItemSize: Int,
    val latestItemIndex: Int,
    val latestItemOffset: Int,
    val latestItemSize: Int,
    val canAnchor: Boolean
)

internal fun threadLatestItemBottomAnchorScrollOffset(
    itemSize: Int,
    viewportHeight: Int,
    visibleItemCount: Int,
    totalItemCount: Int
): Int {
    if (itemSize <= 0 || viewportHeight <= 0) return 0
    val rawOffset = itemSize - viewportHeight
    if (rawOffset >= 0 || visibleItemCount < totalItemCount) return rawOffset
    return 0
}

private fun LazyListState.shouldShowThreadBackToBottom(latestItemIndex: Int): Boolean {
    if (latestItemIndex <= 0) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= latestItemIndex) return false
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val latestBottom = latestItem?.let { it.offset + it.size } ?: return true
    return latestBottom < layoutInfo.viewportEndOffset - ThreadBackToBottomTolerancePx
}

private fun LazyListState.isLatestItemAtRenderedBottom(latestItemIndex: Int): Boolean {
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= latestItemIndex) return false
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex } ?: return false
    val latestBottom = latestItem.offset + latestItem.size
    return kotlin.math.abs(latestBottom - layoutInfo.viewportEndOffset) <= ThreadBackToBottomTolerancePx
}

@Composable
private fun ThreadBackToBottomButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SlockBrutalActionButton(
        label = "Back to bottom",
        onClick = onClick,
        modifier = modifier,
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 30.dp,
        shadowOffset = 4.dp,
        textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
        trailingContent = {
            SlockLucideIcon(
                "chevron-down",
                modifier = Modifier.size(14.dp),
                color = "#FF000000"
            )
        }
    )
}

private const val ThreadBackToBottomTolerancePx = 24
private const val ThreadBottomAnchorMinimumAttempts = 2
private const val ThreadInitialBottomAnchorAttempts = 4
private const val ThreadLayoutStabilityFrameDelayMs = 16L
private const val ThreadFirstContentTaskJoinDelayMs = 96L
private const val ThreadLayoutStabilityMaxFrames = 30
private const val ThreadLayoutStabilityRequiredFrames = 3
private const val ThreadBackToBottomAnchorAttempts = 12
private const val ThreadKeyboardBottomAnchorAttempts = 8
private const val ThreadOlderAutoLoadVisibleItemThreshold = 1
private const val ThreadInitialContentHeightTolerancePx = 8
private const val ThreadInitialMarkdownEstimatedCharsPerLine = 44
private const val ThreadInitialMarkdownMaxEstimatedLines = 8

@Composable
private fun ThreadSystemMessageRow(
    message: KuiklyThreadMessageItem,
    highlightedMentions: Set<String>,
    showReplyAction: Boolean,
    timestampLabel: String,
    resolvedTaskAssigneeName: String?,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)?,
    taskOpenEnabled: Boolean,
    taskStatusActionsEnabled: Boolean,
    onOpenThread: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .clickable { expanded = !expanded }
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = if (expanded) message.expandedSystemSummary(timestampLabel) else message.collapsedSystemSummary(timestampLabel),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Medium).copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
        )
        ThreadMessageFooterMeta(
            message = message,
            showReplyAction = showReplyAction,
            taskOpenEnabled = taskOpenEnabled,
            taskStatusActionsEnabled = taskStatusActionsEnabled,
            resolvedTaskAssigneeName = resolvedTaskAssigneeName,
            alignEnd = false,
            onOpenThread = onOpenThread,
            onOpenTask = onOpenTask,
            onShowActions = {},
            onOpenTaskStatusMenu = onOpenTaskStatusMenu
        )
    }
}

@Composable
private fun ThreadInlineActionRow(label: String, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 36.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(fontSize = 12.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun ThreadHistoryStatusRow(
    label: String,
    iconName: SlockLucideIconName
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            iconName,
            modifier = Modifier.size(14.dp),
            color = "#FF6B7280"
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun ThreadMessageCard(
    message: KuiklyThreadMessageItem,
    actionCard: ThreadActionCard? = message.threadActionCard(),
    messageIndex: Int = -1,
    diagnosticsLogger: (String) -> Unit = {},
    highlightedMentions: Set<String> = emptySet(),
    alignOwnMessageRight: Boolean = false,
    showReplyAction: Boolean = true,
    resolvedTaskAssigneeName: String? = null,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    taskOpenEnabled: Boolean = true,
    taskStatusActionsEnabled: Boolean = true,
    showSaveAction: Boolean = true,
    knownMarkdownChannelNames: Set<String>? = null,
    timestampLabel: String = message.timestampLabel,
    onShowActions: (Offset) -> Unit,
    onOpenMember: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
    onToggleReaction: (String) -> Unit,
    onToggleSaved: () -> Unit,
    onOpenCommentHost: (KuiklyThreadMessageCommentRef) -> Unit = {},
    onOpenAttachment: (String) -> Unit,
    attachmentCommentsEnabled: Boolean = false,
    onOpenAttachmentComments: (String) -> Unit = {},
    onRetryLocalSend: () -> Unit = {},
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    pendingActionCardIds: Set<String> = emptySet(),
    onCommitActionCard: (String) -> Unit = {},
    currentServerSlug: String? = null,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    richContentEnabled: Boolean = true,
    outerPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Sm
) {
    val horizontalArrangement =
        if (alignOwnMessageRight) Arrangement.spacedBy(12.dp, Alignment.End) else Arrangement.spacedBy(12.dp)
    val contentAlignment = if (alignOwnMessageRight) Alignment.End else Alignment.Start
    val messageMaxWidthFraction = if (alignOwnMessageRight) 0.82f else 1f
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).padding(horizontal = outerPadding, vertical = SlockSpacing.Sm)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .padding(horizontal = SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = horizontalArrangement
            ) {
                if (!alignOwnMessageRight) {
                    ThreadMessageAvatar(
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = message.resolvedThreadAvatarWireValue(),
                        showStatus = message.showsAgentStatusIndicator(),
                        onClick = { onOpenMember(message.authorId) }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .threadMessageLongPressActions(message.id, onShowActions),
                        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                        horizontalAlignment = contentAlignment
                    ) {
                        Button(
                            modifier =
                                Modifier
                                    .threadMessageContentWidth(messageMaxWidthFraction)
                                    .padding(end = if (showSaveAction && richContentEnabled) 30.dp else 0.dp)
                                    .background(Color.Transparent),
                            pressFeedback = false
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement =
                                    if (alignOwnMessageRight) {
                                        Arrangement.spacedBy(SlockSpacing.Sm, Alignment.End)
                                    } else {
                                        Arrangement.spacedBy(SlockSpacing.Sm)
                                    }
                            ) {
                                Text(
                                    text = message.authorName,
                                    style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                                    color = SlockColor.TextBlack,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp).clickable { onOpenMember(message.authorId) }
                                )
                                Text(
                                    text = timestampLabel,
                                    style =
                                        SlockType.Caption.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = SlockColor.TextBlack.copy(alpha = 0.4f)
                                        ),
                                    color = SlockColor.TextBlack.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (richContentEnabled && actionCard != null) {
                            ThreadActionCardView(
                                card = actionCard,
                                pending = message.id in pendingActionCardIds,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                performanceKey = threadMessagePerformanceKey(message.id, messageIndex),
                                diagnosticsLogger = diagnosticsLogger,
                                onCommit = { onCommitActionCard(message.id) }
                            )
                        } else if (message.body.isNotBlank()) {
                            if (richContentEnabled) {
                                message.commentRef?.let { commentRef ->
                                    ThreadMessageCommentRefChip(
                                        ref = commentRef,
                                        enabled = commentRef.hasThreadCommentHostSource(),
                                        modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                        onOpen = { onOpenCommentHost(commentRef) }
                                    )
                                }
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .threadMessageContentWidth(messageMaxWidthFraction)
                                        .background(Color.Transparent)
                            ) {
                                if (richContentEnabled) {
                                    SlockMarkdownBody(
                                        body = message.body,
                                        textStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                                        highlightedMentions = highlightedMentions,
                                        knownChannelNames = knownMarkdownChannelNames,
                                        resolveTagLabel = resolveMarkdownTagLabel,
                                        onOpenTag = onOpenMarkdownTag,
                                        onOpenUrl = onOpenMarkdownUrl,
                                        performanceKey = threadMessagePerformanceKey(message.id, messageIndex),
                                        performanceLogger = diagnosticsLogger
                                    )
                                } else {
                                    Text(
                                        text = message.body,
                                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                                        color = SlockColor.TextBlack
                                    )
                                }
                            }
                        }
                        if (richContentEnabled) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                ThreadMessageAttachments(
                                    attachments = message.attachments,
                                    alignEnd = alignOwnMessageRight,
                                    attachmentOpenStates = attachmentOpenStates,
                                    commentsEnabled = attachmentCommentsEnabled,
                                    onOpenComments = onOpenAttachmentComments,
                                    onOpenAttachment = onOpenAttachment
                                )
                            }
                        }
                        if (message.hasLocalSendState()) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                ThreadMessageLocalSendStatus(
                                    message = message,
                                    alignEnd = alignOwnMessageRight,
                                    onRetry = onRetryLocalSend
                                )
                            }
                        }
                        if (richContentEnabled) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                if (!message.hasLocalSendState()) {
                                    ThreadMessageFooterMeta(
                                        message = message,
                                        showReplyAction = showReplyAction,
                                        taskOpenEnabled = taskOpenEnabled,
                                        taskStatusActionsEnabled = taskStatusActionsEnabled,
                                        resolvedTaskAssigneeName = resolvedTaskAssigneeName,
                                        alignEnd = alignOwnMessageRight,
                                        onOpenThread = { onOpenThread(message.id) },
                                        onOpenTask = onOpenTask,
                                        onShowActions = onShowActions,
                                        onOpenTaskStatusMenu = onOpenTaskStatusMenu,
                                        onToggleReaction = onToggleReaction
                                    )
                                }
                            }
                        }
                    }
                    if (showSaveAction && richContentEnabled && !message.hasLocalSendState()) {
                        ThreadMessageBookmarkButton(
                            saved = message.isSaved,
                            onClick = onToggleSaved,
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
                if (alignOwnMessageRight) {
                    ThreadMessageAvatar(
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = message.resolvedThreadAvatarWireValue(),
                        showStatus = message.showsAgentStatusIndicator(),
                        onClick = { onOpenMember(message.authorId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageLocalSendStatus(
    message: KuiklyThreadMessageItem,
    alignEnd: Boolean,
    onRetry: () -> Unit
) {
    val isFailed = message.deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true)
    val label =
        if (isFailed) {
            "Send failed · Retry"
        } else if (message.pendingSendContext?.asTask == true) {
            "Sending as task..."
        } else {
            "Sending..."
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { if (message.canRetryLocalSend()) onRetry() },
            enabled = isFailed,
            pressFeedback = isFailed,
            modifier = Modifier.heightIn(min = 28.dp).background(Color.Transparent)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SlockLucideIcon(
                    if (isFailed) SlockLucideIconName.RefreshCw else SlockLucideIconName.LoaderCircle,
                    modifier = Modifier.size(13.dp),
                    color = if (isFailed) "#FFF97264" else SlockColor.TextMutedGrayArgb
                )
                Text(
                    text = label,
                    style =
                        SlockType.Caption.copy(
                            color = if (isFailed) SlockColor.AccentRed else SlockColor.TextMutedGray
                        ),
                    color = if (isFailed) SlockColor.AccentRed else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThreadMessageBookmarkButton(
    saved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        modifier =
            modifier
                .size(24.dp)
                .background(Color.Transparent),
        onClick = onClick,
        pressFeedback = false
    ) {
        SlockLucideIcon(
            name = "bookmark",
            modifier = Modifier.size(13.dp),
            color = if (saved) "#FFF8A16F" else "#66000000",
            fill = if (saved) "#FFF8A16F" else null
        )
    }
}

private data class ThreadActionCardRenderCopy(
    val title: String,
    val details: String,
    val pendingLabel: String,
    val verb: String,
    val committedLabel: String
)

@Composable
private fun ThreadActionCardView(
    card: ThreadActionCard,
    pending: Boolean,
    modifier: Modifier = Modifier,
    performanceKey: String? = null,
    diagnosticsLogger: (String) -> Unit = {},
    onCommit: () -> Unit
) {
    val renderCopy =
        remember(card, performanceKey) {
            val startedAt = TimeSource.Monotonic.markNow()
            ThreadActionCardRenderCopy(
                title = card.actionCardTitle(),
                details = card.actionCardDetails(),
                pendingLabel = card.actionCardPendingLabel(),
                verb = card.actionCardVerb(),
                committedLabel = card.actionCardCommittedLabel()
            ).also { copy ->
                val durationMs = startedAt.elapsedNow().inWholeMilliseconds
                if (performanceKey != null && shouldLogThreadPerformance(durationMs)) {
                    diagnosticsLogger(
                        "thread_action_card_compute key=${performanceKey.orEmpty()} " +
                            "type=${card.type} titleLength=${copy.title.length} " +
                            "detailsLength=${copy.details.length} durationMs=$durationMs"
                    )
                }
            }
        }
    Column(
        modifier =
            modifier
                .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
                .background(SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = renderCopy.title,
                style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (card.isExecuted) {
                ThreadActionCardDoneBadge()
            }
        }
        val details = renderCopy.details
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        card.draftHint?.takeIf { it.isNotBlank() }?.let { hint ->
            Text(
                text = hint,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                modifier =
                    Modifier.fillMaxWidth()
                        .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
                        .padding(SlockSpacing.Xs)
            )
        }
        if (card.isExecuted) {
            val committed = renderCopy.committedLabel
            if (committed.isNotBlank()) {
                Text(
                    text = committed,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        } else {
            SlockBrutalActionButton(
                label = if (pending) renderCopy.pendingLabel else renderCopy.verb,
                onClick = onCommit,
                enabled = !pending && card.isSupportedCommitAction(),
                backgroundColor = SlockColor.AccentPink,
                disabledBackgroundColor = SlockColor.SurfaceCream,
                minWidth = 0.dp,
                minHeight = 34.dp,
                shadowOffset = 2.dp,
                textStyle = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack)
            )
        }
    }
}

@Composable
private fun ThreadActionCardDoneBadge() {
    Row(
        modifier =
            Modifier
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .background(SlockColor.AccentLime)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(10.dp), color = "#FF000000")
        Text(
            text = "Done",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

private fun ThreadActionCard.actionCardVerb(): String =
    when (type) {
        "channel:create" -> "Create Channel"
        "agent:create" -> "Create Agent"
        "channel:add_member" -> "Add Members"
        else -> "Action"
    }

private fun ThreadActionCard.actionCardPendingLabel(): String =
    when (type) {
        "channel:add_member" -> "Adding..."
        else -> "Creating..."
    }

private fun ThreadActionCard.actionCardSuccessToast(): String =
    when (type) {
        "channel:create" -> "Channel created"
        "agent:create" -> "Agent created"
        "channel:add_member" -> "Members added"
        else -> "Action completed"
    }

private fun ThreadActionCard.actionCardFailureToast(): String =
    when (type) {
        "channel:create" -> "Unable to create channel"
        "agent:create" -> "Unable to create agent"
        "channel:add_member" -> "Unable to add members"
        else -> "Unable to complete action"
    }

private fun ThreadActionCard.actionCardTitle(): String =
    when (type) {
        "channel:create" -> "Create ${visibility ?: "public"} channel #$name"
        "agent:create" -> "Create Agent @${name.removePrefix("@")}"
        "channel:add_member" -> {
            val count = humans.size + agents.size
            "Add $count member${if (count == 1) "" else "s"} to ${channel?.let { "#${it.removePrefix("#")}" } ?: "channel"}"
        }
        else -> actionCardVerb()
    }

private fun ThreadActionCard.actionCardDetails(): String =
    buildList {
        description?.takeIf { it.isNotBlank() }?.let { add("Description: $it") }
        if (type.equals("agent:create", ignoreCase = true)) {
            requiredComputer?.takeIf { it.isNotBlank() }?.let { add("Required computer: $it") }
            if (requiredComputer.isNullOrBlank()) {
                suggestedComputer?.takeIf { it.isNotBlank() }?.let { add("Suggested computer: $it") }
            }
            if (description.isNullOrBlank()) add("No description prefilled.")
        }
        if (initialHumans.isNotEmpty()) add("Humans: ${initialHumans.joinToString(", ")}")
        if (initialAgents.isNotEmpty()) add("Agents: ${initialAgents.joinToString(", ")}")
        if (humans.isNotEmpty()) add("Humans: ${humans.joinToString(", ")}")
        if (agents.isNotEmpty()) add("Agents: ${agents.joinToString(", ")}")
    }.joinToString("\n")

private fun ThreadActionCard.actionCardCommittedLabel(): String =
    buildString {
        executedByUserName?.takeIf { it.isNotBlank() }?.let { append("Committed by $it") }
        val result = when (resultKind) {
            "agent" -> resultName?.let { "@${it.removePrefix("@")}" }
            "channel" -> resultName?.let { "#${it.removePrefix("#")}" }
            "channel-members" -> {
                val total = resultAddedHumanIds.size + resultAddedAgentIds.size
                resultChannelName?.let { "$total added to #${it.removePrefix("#")}" }
            }
            else -> null
        }
        if (!result.isNullOrBlank()) {
            if (isNotBlank()) append(" -> ") else append("Result: ")
            append(result)
        }
    }

private fun threadCreateAgentDialogErrorCopy(code: String): String =
    when (code) {
        "agent_name_required",
        "action_card_agent_name_missing" -> "Agent name is required."
        "action_card_agents_api_missing" -> "Create Agent is not available on this build."
        "action_card_actions_api_missing" -> "Action card completion is not available on this build."
        "action_card_mark_executed_empty",
        "action_mark_executed_contract_mismatch" -> "Agent was created, but the action card could not be marked done. Try refreshing this thread."
        "agent_create_empty" -> "Agent was not created."
        else -> "Unable to create agent: $code"
    }

private fun threadCreateAgentMachinesErrorCopy(code: String): String =
    when (code) {
        "server_id_missing" -> "Select a server before loading computers."
        "auth_required",
        "auth_session_missing",
        "auth_session_expired" -> "Sign in again to load computers."
        "server_forbidden" -> "You do not have permission to view computers."
        else -> "Failed to load computers: $code"
    }

@Composable
private fun ThreadReactionChip(
    reaction: KuiklyThreadReactionItem,
    onClick: (() -> Unit)? = null
) {
    val background = if (reaction.selected) SlockColor.AccentPink.copy(alpha = 0.2f) else Color(0xFFEEEDE6)
    val chipModifier = Modifier
            .background(background, RoundedCornerShape(SlockRadius.Sm))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    val chipContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ThreadQuickReactionGlyph(threadDisplayReactionEmoji(reaction.emoji), modifier = Modifier.size(15.dp))
            if (reaction.count > 0) {
                Text(
                    text = reaction.count.toString(),
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (onClick != null) {
        Button(onClick = onClick, pressFeedback = false) {
            Box(
                modifier = chipModifier,
                contentAlignment = Alignment.Center
            ) {
                chipContent()
            }
        }
    } else {
        Box(
            modifier = chipModifier,
            contentAlignment = Alignment.Center
        ) {
            chipContent()
        }
    }
}

@Composable
private fun ThreadReactionAddChip(onClick: (Offset) -> Unit) {
    Button(
        onClick2 = onClick,
        modifier = Modifier.background(Color.Transparent)
    ) {
        ThreadReactionAddChipChrome()
    }
}

@Composable
private fun ThreadReactionAddChipChrome() {
    Box(
        modifier = Modifier
            .background(SlockColor.EmptyStateGray, RoundedCornerShape(SlockRadius.Sm))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack
        )
    }
}

private fun Modifier.threadMessageContentWidth(maxWidthFraction: Float): Modifier =
    widthIn(max = 680.dp).fillMaxWidth(maxWidthFraction)

@Composable
private fun Modifier.threadMessageLongPressActions(
    messageId: String,
    onShowActions: (Offset) -> Unit
): Modifier {
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { rootPos = it.positionInRoot() }
        .pointerInput(messageId) {
            detectTapGestures(
                onLongPress = { localOffset ->
                    onShowActions(Offset(rootPos.x + localOffset.x, rootPos.y + localOffset.y))
                }
            )
        }
}

internal fun KuiklyThreadMessageItem.resolvedThreadAvatarWireValue(): String? {
    val isAgent = authorKind.equals("agent", ignoreCase = true) || authorId.isLikelyThreadAgentId()
    return avatarWireValueForAvatarSlot(
        kind = if (isAgent) MemberKind.Agent else MemberKind.Human,
        id = authorId.ifBlank { authorName },
        avatarWireValue = avatarWireValue,
        gravatarHash = gravatarHash
    )
}

private fun String.isLikelyThreadAgentId(): Boolean =
    startsWith("agent", ignoreCase = true) ||
        startsWith("ai-", ignoreCase = true) ||
        contains("_agent", ignoreCase = true) ||
        contains("-agent", ignoreCase = true)

@Composable
private fun ThreadMessageFooterMeta(
    message: KuiklyThreadMessageItem,
    showReplyAction: Boolean,
    taskOpenEnabled: Boolean,
    taskStatusActionsEnabled: Boolean,
    resolvedTaskAssigneeName: String? = null,
    alignEnd: Boolean,
    onOpenThread: () -> Unit,
    onOpenTask: (String) -> Unit,
    onShowActions: (Offset) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
    onToggleReaction: ((String) -> Unit)? = null
) {
    val taskLabel = threadMessageTaskMetaLabel(message, resolvedTaskAssigneeName)
    val hasTask = taskLabel != null
    val hasReplies = message.replyCount > 0
    val visibleReactions = message.reactions.filter { it.count > 0 }
    val hasReactions = visibleReactions.isNotEmpty()
    if (!hasTask && !hasReplies && !hasReactions) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (alignEnd) Arrangement.spacedBy(6.dp, Alignment.End) else Arrangement.spacedBy(6.dp)
    ) {
        if (taskLabel != null) {
            val selectedStatus = message.taskStatus.threadTaskStatusOption()
            val taskChipClick: ((Offset) -> Unit)? =
                when {
                    taskStatusActionsEnabled -> onOpenTaskStatusMenu
                    taskOpenEnabled -> { _ -> message.taskId?.let(onOpenTask) }
                    else -> null
                }
            SlockAnchoredMetaChip(
                label = taskLabel,
                iconName = selectedStatus.icon,
                iconSize = selectedStatus.iconSize,
                color = selectedStatus.color,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 260.dp),
                onClick = taskChipClick
            )
        }
        if (hasReplies) {
            val replyLabel = threadMessageRepliesMetaLabel(message)
            SlockAnchoredMetaChip(
                label = replyLabel,
                iconName = SlockLucideIconName.MessageSquare,
                iconSize = 12.dp,
                color = SlockColor.SurfaceWhite,
                modifier = Modifier.widthIn(max = 220.dp),
                pressFeedback = false,
                onClick = if (showReplyAction) { _ -> onOpenThread() } else null
            )
        }
        if (hasReactions) {
            visibleReactions.forEach { reaction ->
                ThreadReactionChip(
                    reaction = reaction,
                    onClick = onToggleReaction?.let { callback -> { callback(reaction.emoji) } }
                )
            }
        }
    }
}

internal fun threadMessageTaskMetaLabel(
    message: KuiklyThreadMessageItem,
    resolvedTaskAssigneeName: String? = null
): String? {
    val number = message.taskNumber?.let { "#$it" }
    val assignee =
        resolvedTaskAssigneeName
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
    if (number == null && assignee == null && message.taskStatus.isNullOrBlank() && message.taskId.isNullOrBlank()) {
        return null
    }
    return buildString {
        append(number ?: "Task")
        assignee?.let {
            append(" @")
            append(it)
        }
    }
}

internal fun shouldRenderThreadTaskMetaChip(
    message: KuiklyThreadMessageItem,
    resolvedTaskAssigneeName: String? = null
): Boolean =
    threadMessageTaskMetaLabel(message, resolvedTaskAssigneeName) != null

internal fun String.isDisplaySafeTaskAssigneeName(assigneeId: String? = null): Boolean {
    val trimmed = trim().removePrefix("@")
    if (trimmed.isBlank()) return false
    if (trimmed.isUuidLike()) return false
    val normalizedId = assigneeId?.trim()?.removePrefix("@")
    return normalizedId == null || !trimmed.equals(normalizedId, ignoreCase = true) || !normalizedId.isUuidLike()
}

private fun String.isUuidLike(): Boolean {
    val trimmed = trim()
    if (trimmed.length != 36) return false
    val dashPositions = setOf(8, 13, 18, 23)
    return trimmed.withIndex().all { (index, char) ->
        if (index in dashPositions) {
            char == '-'
        } else {
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }
    }
}

internal fun threadMessageRepliesMetaLabel(message: KuiklyThreadMessageItem): String =
    buildString {
        append(message.replyCount)
        append(if (message.replyCount == 1) " reply" else " replies")
        if (message.unreadReplyCount > 0) {
            append(" · ")
            append(message.unreadReplyCount)
            append(" new")
        }
    }

private data class ThreadTaskStatusOption(
    val key: String,
    val label: String,
    val icon: SlockLucideIconName,
    val color: Color,
    val iconSize: Dp = 10.dp
)

private val threadTaskStatusOptions =
    listOf(
        ThreadTaskStatusOption("Todo", "Todo", SlockLucideIconName.Circle, SlockColor.AccentOrange),
        ThreadTaskStatusOption("InProgress", "In progress", SlockLucideIconName.Play, SlockColor.AccentCyan),
        ThreadTaskStatusOption("InReview", "In review", SlockLucideIconName.Eye, SlockColor.AccentLavender),
        ThreadTaskStatusOption("Done", "Done", SlockLucideIconName.CircleCheckBig, SlockColor.AccentLime),
        ThreadTaskStatusOption("Closed", "Closed", SlockLucideIconName.Ban, SlockColor.AccentStone)
    )

private val ThreadTaskStatusMenuItems: List<SlockBaseMenuItem> =
    threadTaskStatusOptions.map { option ->
        SlockBaseMenuItem(id = option.key, label = option.label)
    }

private fun String?.threadTaskStatusOption(): ThreadTaskStatusOption {
    val normalized =
        this?.trim()?.lowercase().orEmpty().replace("-", "").replace("_", "").replace(" ", "")
    return threadTaskStatusOptions.firstOrNull { option ->
        option.key.lowercase() == normalized ||
            option.label.lowercase().replace(" ", "") == normalized
    } ?: threadTaskStatusOptions.first()
}

internal fun KuiklyThreadMessageItem.isSystemMessage(): Boolean =
    messageType.equals("System", ignoreCase = true) || messageType.equals("system", ignoreCase = true)

internal data class ThreadReactionToggleTarget(
    val message: KuiklyThreadMessageItem,
    val nextSelected: Boolean
)

internal fun KuiklyThreadScreenPayload.threadReactionToggleTarget(
    messageId: String,
    emoji: String
): ThreadReactionToggleTarget? {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return null
    val candidates =
        (messages + listOfNotNull(originMessage))
            .filter { message -> message.id == cleanMessageId && !message.isSystemMessage() }
    if (candidates.isEmpty()) return null
    val selectedCandidate =
        candidates.firstOrNull { message ->
            message.reactions.any { reaction -> reaction.emoji == cleanEmoji && reaction.selected }
        }
    val message = selectedCandidate ?: candidates.first()
    return ThreadReactionToggleTarget(
        message = message,
        nextSelected = selectedCandidate == null
    )
}

internal fun KuiklyThreadMessageItem.collapsedSystemSummary(timestamp: String): String =
    listOf(timestamp, body.sanitizeThreadSystemText())
        .filter { it.isNotBlank() }
        .joinToString(" ")

internal fun KuiklyThreadMessageItem.expandedSystemSummary(timestamp: String): String =
    listOf(collapsedSystemSummary(timestamp), expandedSystemDetails())
        .filter { it.isNotBlank() }
        .joinToString("\n")

internal fun KuiklyThreadMessageItem.expandedSystemDetails(): String =
    buildList {
        systemEventType?.takeIf { it.isNotBlank() }?.let { add("Type: ${it.sanitizeThreadSystemText()}") }
        metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                add("${key.sanitizeThreadSystemText()}: ${value.sanitizeThreadSystemText()}")
            }
        }
    }.joinToString("\n")

internal fun String.sanitizeThreadSystemText(): String =
    replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .decodeBasicHtmlEntities()
        .collapseThreadInlineWhitespace()
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

private fun String.collapseThreadInlineWhitespace(): String =
    buildString(length) {
        var pendingSpace = false
        for (char in this@collapseThreadInlineWhitespace) {
            if (char.isThreadInlineWhitespace()) {
                pendingSpace = true
            } else {
                if (pendingSpace && isNotEmpty()) {
                    append(' ')
                }
                append(char)
                pendingSpace = false
            }
        }
    }

private fun Char.isThreadInlineWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\u000B' || this == '\u000C' || this == '\r'

private fun String.decodeBasicHtmlEntities(): String =
    replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

private data class ThreadMessageMenuAction(
    val label: String,
    val icon: SlockLucideIconName,
    val group: String,
    val onClick: () -> Unit
)

private val ThreadMessageMenuWidth = 208.dp
private const val ThreadMessageMenuFallbackHeight = 210f
private const val ThreadMessageMenuAnchorGap = 8f
private const val ThreadMessageMenuHorizontalNudge = 16f
internal val ThreadQuickReactionEmojis = listOf("👍", "❤️", "🎉", "👀", "🔥", "😂", "✅")

@Composable
private fun ThreadMessageActionMenu(
    message: KuiklyThreadMessageItem,
    anchor: Offset,
    showReplyAction: Boolean,
    showSaveAction: Boolean,
    showTaskStatusAction: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onReact: (String) -> Unit,
    onTaskStatusChange: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    val actions =
        buildList {
            if (showReplyAction) {
                add(ThreadMessageMenuAction("Reply", SlockLucideIconName.MessageSquare, "message", onReply))
            }
            add(ThreadMessageMenuAction("Copy markdown", SlockLucideIconName.Copy, "copy", onCopy))
            if (showSaveAction) {
                add(
                    ThreadMessageMenuAction(
                        label = if (message.isSaved) "Remove from saved" else "Save message",
                        icon = SlockLucideIconName.Bookmark,
                        group = "message",
                        onClick = onSave
                    )
                )
            }
            message.taskId?.takeIf { showTaskStatusAction && it.isNotBlank() }?.let {
                val isDone = message.taskStatus?.trim()?.lowercase() in setOf("done", "closed")
                add(
                    ThreadMessageMenuAction(
                        label = if (isDone) "Reopen task" else "Mark done",
                        icon = if (isDone) SlockLucideIconName.RefreshCw else SlockLucideIconName.CircleCheckBig,
                        group = "task",
                        onClick = { onTaskStatusChange(if (isDone) "reopenTask" else "markTaskDone") }
                    )
                )
            }
        }
    var menuSize by remember { mutableStateOf(Size.Zero) }
    val density = LocalDensity.current
    val menuWidth = menuSize.width.takeIf { it > 0f } ?: ThreadMessageMenuWidth.value
    val menuHeight = menuSize.height.takeIf { it > 0f } ?: ThreadMessageMenuFallbackHeight
    val contentSize = Size(
        width = menuWidth + 4f,
        height = menuHeight + 4f
    )
    SlockAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        viewportGap = ThreadMessageMenuAnchorGap,
        horizontalOffset = -ThreadMessageMenuHorizontalNudge,
        verticalOffset = ThreadMessageMenuAnchorGap,
        verticalPlacement = SlockFloatingVerticalPlacement.Auto
    ) { popoverModifier, position ->
        LaunchedEffect(message.id, anchor, position, menuSize) {
            diagnosticsLogger(
                "thread_message_menu_position messageId=${message.id} rootX=${anchor.x} rootY=${anchor.y} " +
                    "overlayX=${position.overlayLayout.origin.x} overlayY=${position.overlayLayout.origin.y} " +
                    "localX=${position.localAnchor.x} localY=${position.localAnchor.y} " +
                    "overlayW=${position.overlayLayout.size.width} overlayH=${position.overlayLayout.size.height} " +
                    "menuW=$menuWidth menuH=$menuHeight finalX=${position.offset.x} finalY=${position.offset.y}"
            )
        }
        Box(
            modifier = popoverModifier
                .padding(end = 4.dp, bottom = 4.dp)
                .width(ThreadMessageMenuWidth)
                .onGloballyPositioned { coordinates ->
                    menuSize = coordinates.size.toSlockDpSize(density)
                }
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 4f, y = 4f)
                    .background(SlockColor.ShadowBlack)
            )
            Column(
                modifier = Modifier
                    .width(ThreadMessageMenuWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .slockFloatingContentClickTarget()
            ) {
                ThreadMessageReactionRow(onReact = onReact)
                ThreadMessageMenuDivider()
                actions.forEachIndexed { index, action ->
                    val previousGroup = actions.getOrNull(index - 1)?.group
                    if (index > 0 && previousGroup != action.group) {
                        ThreadMessageMenuDivider()
                    }
                    ThreadMessageMenuItem(action)
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageReactionRow(onReact: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ThreadQuickReactionEmojis.forEach { reaction ->
            Button(
                onClick = { onReact(reaction) },
                pressFeedback = false,
                modifier = Modifier
                    .size(28.dp)
            ) {
                ThreadQuickReactionGlyph(reaction, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun threadDisplayReactionEmoji(emoji: String): String =
    when (emoji) {
        "+1" -> "👍"
        else -> emoji
    }

@Composable
private fun ThreadQuickReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val unit = size.width / 24f
        fun scaledSize(width: Float, height: Float) = Size(width * unit, height * unit)
        fun scaledOffset(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun path(block: Path.() -> Unit): Path = Path().apply(block)
        fun Path.m(x: Float, y: Float) = moveTo(x * unit, y * unit)
        fun Path.l(x: Float, y: Float) = lineTo(x * unit, y * unit)
        fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            cubicTo(x1 * unit, y1 * unit, x2 * unit, y2 * unit, x3 * unit, y3 * unit)
        val ink = SlockColor.ShadowBlack
        val yellow = SlockColor.BrandYellow
        val cream = Color(0xFFFFF8E7)
        val blueGray = Color(0xFF8FC4D9)
        val red = SlockColor.AccentRed
        val cyan = SlockColor.AccentCyan
        val orange = SlockColor.AccentOrange
        val lavender = SlockColor.AccentLavender
        val stroke2 = Stroke(width = 2f * unit)
        val stroke15 = Stroke(width = 1.5f * unit)

        when (emoji) {
            "👍" -> {
                val hand = path {
                    m(8f, 10f)
                    l(11f, 10f)
                    l(12.3f, 4.8f)
                    c(12.5f, 4f, 13.3f, 3.5f, 14.1f, 3.7f)
                    c(15.1f, 3.9f, 15.7f, 4.9f, 15.4f, 5.9f)
                    l(14.5f, 10f)
                    l(19f, 10f)
                    c(20.1f, 10f, 21f, 10.9f, 21f, 12f)
                    l(21f, 13.2f)
                    c(21f, 13.6f, 20.9f, 14f, 20.7f, 14.3f)
                    l(18.8f, 20f)
                    c(18.5f, 20.6f, 17.8f, 21f, 17.1f, 21f)
                    l(8f, 21f)
                    l(8f, 10f)
                    close()
                }
                val highlight = path {
                    m(13.2f, 5.3f)
                    c(13.4f, 4.6f, 14.1f, 4.2f, 14.8f, 4.5f)
                    c(14.9f, 4.6f, 15f, 4.6f, 15.1f, 4.7f)
                    c(14.5f, 4.7f, 14.1f, 5.1f, 13.9f, 5.7f)
                    l(13f, 9f)
                    l(11.8f, 9f)
                    l(13.2f, 5.3f)
                    close()
                }
                drawPath(hand, yellow, style = Fill)
                drawPath(highlight, cream, style = Fill)
                drawRect(blueGray, topLeft = scaledOffset(3f, 10f), size = scaledSize(5f, 11f))
                drawPath(hand, ink, style = stroke2)
                drawRect(ink, topLeft = scaledOffset(3f, 10f), size = scaledSize(5f, 11f), style = stroke2)
            }
            "❤️", "❤" -> {
                val heart = path {
                    m(12f, 21f)
                    l(4.4f, 13.4f)
                    c(2.3f, 11.3f, 2.2f, 8f, 4.1f, 6.1f)
                    c(5.8f, 4.4f, 8.4f, 4.5f, 10f, 6.1f)
                    l(12f, 8.1f)
                    l(14f, 6.1f)
                    c(15.6f, 4.5f, 18.2f, 4.4f, 19.9f, 6.1f)
                    c(21.8f, 8f, 21.7f, 11.3f, 19.6f, 13.4f)
                    l(12f, 21f)
                    close()
                }
                val highlight = path {
                    m(6.1f, 7.2f)
                    c(6.9f, 6.4f, 8.1f, 6.4f, 9f, 7.1f)
                    l(6f, 10.1f)
                    c(5.5f, 9.2f, 5.6f, 7.9f, 6.1f, 7.2f)
                    close()
                }
                drawPath(heart, red, style = Fill)
                drawPath(highlight, cream, style = Fill)
                drawPath(heart, ink, style = stroke2)
            }
            "🎉" -> {
                val cone = path {
                    m(4f, 20f)
                    l(8.5f, 8.5f)
                    l(16f, 16f)
                    l(4f, 20f)
                    close()
                }
                val stripe = path {
                    m(6.8f, 12.2f)
                    l(11.8f, 17.2f)
                    l(8.3f, 18.3f)
                    l(5.7f, 15.4f)
                    close()
                }
                val confettiLines = path {
                    m(14f, 5f)
                    l(17f, 5f)
                    m(18f, 9f)
                    l(20.3f, 6.7f)
                    m(19f, 13f)
                    l(21.5f, 13f)
                }
                drawPath(cone, lavender, style = Fill)
                drawPath(stripe, yellow, style = Fill)
                drawPath(cone, ink, style = stroke2)
                drawPath(confettiLines, ink, style = stroke2)
                drawRect(red, topLeft = scaledOffset(18f, 3f), size = scaledSize(2.5f, 2.5f))
                drawRect(cyan, topLeft = scaledOffset(13f, 6f), size = scaledSize(2.5f, 2.5f))
            }
            "👀" -> {
                val leftEye = path {
                    m(3f, 11f)
                    c(3f, 7.9f, 5.2f, 6f, 7.8f, 6f)
                    c(10.4f, 6f, 12.6f, 7.9f, 12.6f, 11f)
                    c(12.6f, 14.1f, 10.4f, 16f, 7.8f, 16f)
                    c(5.2f, 16f, 3f, 14.1f, 3f, 11f)
                    close()
                }
                val rightEye = path {
                    m(11.4f, 11f)
                    c(11.4f, 7.9f, 13.6f, 6f, 16.2f, 6f)
                    c(18.8f, 6f, 21f, 7.9f, 21f, 11f)
                    c(21f, 14.1f, 18.8f, 16f, 16.2f, 16f)
                    c(13.6f, 16f, 11.4f, 14.1f, 11.4f, 11f)
                    close()
                }
                drawPath(leftEye, cyan, style = Fill)
                drawPath(rightEye, cyan, style = Fill)
                drawRect(cream, topLeft = scaledOffset(5.2f, 9.2f), size = scaledSize(2f, 2f))
                drawRect(cream, topLeft = scaledOffset(13.6f, 9.2f), size = scaledSize(2f, 2f))
                drawRect(ink, topLeft = scaledOffset(7.8f, 8.8f), size = scaledSize(2.8f, 4.6f))
                drawRect(ink, topLeft = scaledOffset(16.2f, 8.8f), size = scaledSize(2.8f, 4.6f))
                drawPath(leftEye, ink, style = stroke2)
                drawPath(rightEye, ink, style = stroke2)
            }
            "🔥" -> {
                val flame = path {
                    m(12f, 22f)
                    c(7.6f, 22f, 4.4f, 19f, 4.4f, 14.9f)
                    c(4.4f, 11.8f, 6.3f, 9.5f, 8.5f, 7.8f)
                    c(8.8f, 9.8f, 9.7f, 11f, 10.8f, 11.7f)
                    c(10.5f, 8.4f, 11.8f, 5.4f, 14.9f, 3f)
                    c(15.4f, 6f, 17f, 7.7f, 18.3f, 9.3f)
                    c(19.4f, 10.7f, 19.6f, 12.2f, 19.6f, 14.4f)
                    c(19.6f, 18.8f, 16.5f, 22f, 12f, 22f)
                    close()
                }
                val core = path {
                    m(12.1f, 19.8f)
                    c(9.9f, 19.8f, 8.3f, 18.4f, 8.3f, 16.3f)
                    c(8.3f, 14.8f, 9.2f, 13.6f, 10.4f, 12.7f)
                    c(10.6f, 13.7f, 11f, 14.4f, 11.7f, 14.7f)
                    c(11.8f, 12.9f, 12.5f, 11.5f, 14f, 10.3f)
                    c(14.3f, 11.8f, 15f, 12.7f, 15.7f, 13.5f)
                    c(16.3f, 14.2f, 16.5f, 14.9f, 16.5f, 16f)
                    c(16.5f, 18.3f, 14.8f, 19.8f, 12.1f, 19.8f)
                    close()
                }
                drawPath(flame, orange, style = Fill)
                drawPath(core, yellow, style = Fill)
                drawPath(flame, ink, style = stroke2)
                drawPath(core, ink, style = stroke15)
            }
            "😂" -> {
                val smile = path {
                    m(8f, 16f)
                    c(9f, 17.2f, 10.2f, 17.8f, 12f, 17.8f)
                    c(13.8f, 17.8f, 15f, 17.2f, 16f, 16f)
                }
                drawRect(yellow, topLeft = scaledOffset(4f, 5f), size = scaledSize(16f, 14f))
                drawRect(ink, topLeft = scaledOffset(7f, 9f), size = scaledSize(3.2f, 2.4f))
                drawRect(ink, topLeft = scaledOffset(13.8f, 9f), size = scaledSize(3.2f, 2.4f))
                drawRect(ink, topLeft = scaledOffset(8f, 14f), size = scaledSize(8f, 2f))
                drawRect(cyan, topLeft = scaledOffset(3.5f, 12f), size = scaledSize(3f, 5f))
                drawRect(cyan, topLeft = scaledOffset(17.5f, 12f), size = scaledSize(3f, 5f))
                drawRect(ink, topLeft = scaledOffset(4f, 5f), size = scaledSize(16f, 14f), style = stroke2)
                drawPath(smile, ink, style = stroke15)
            }
            "✅", "☑", "☑️", "✔", "✔️", "✓" -> {
                val box = path {
                    m(5f, 4f)
                    l(19f, 4f)
                    c(19.6f, 4f, 20f, 4.4f, 20f, 5f)
                    l(20f, 19f)
                    c(20f, 19.6f, 19.6f, 20f, 19f, 20f)
                    l(5f, 20f)
                    c(4.4f, 20f, 4f, 19.6f, 4f, 19f)
                    l(4f, 5f)
                    c(4f, 4.4f, 4.4f, 4f, 5f, 4f)
                    close()
                }
                val check = path {
                    m(7f, 12.2f)
                    l(10.3f, 15.6f)
                    l(17.3f, 8.4f)
                }
                val highlight = path {
                    m(6f, 5.5f)
                    l(12f, 5.5f)
                    l(6f, 11.5f)
                    close()
                }
                drawPath(box, SlockColor.AccentLime, style = Fill)
                drawPath(highlight, cream, style = Fill)
                drawPath(box, ink, style = stroke2)
                drawPath(check, ink, style = Stroke(width = 2.6f * unit))
            }
            else -> {
                drawCircle(yellow, radius = 9f * unit, center = scaledOffset(12f, 12f))
                drawCircle(ink, radius = 9f * unit, center = scaledOffset(12f, 12f), style = stroke2)
            }
        }
    }
}

@Composable
private fun ThreadMessageMenuItem(action: ThreadMessageMenuAction) {
    Button(
        onClick = action.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .padding(horizontal = SlockSpacing.Sm, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SlockLucideIcon(action.icon, modifier = Modifier.size(16.dp), color = "#FF000000")
            Text(
                text = action.label,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ThreadMessageMenuDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
}

@Composable
private fun ThreadOriginCard(
    payload: KuiklyThreadScreenPayload,
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onShowActions: (KuiklyThreadMessageItem, Offset) -> Unit,
    onOpenTaskStatusMenu: (KuiklyThreadMessageItem, Offset) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (payload.originStatus) {
            "loading" -> {
                Text(
                    text = "Refreshing original message...",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
                )
            }
            "missing" -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    Text(
                        text = payload.originPreview?.ifBlank { "Original message is unavailable." }
                            ?: "Original message is unavailable.",
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack
                    )
                    payload.originErrorCode?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "Refresh failed: $it",
                            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray
                        )
                    }
                }
            }
            else -> {
                payload.originMessage?.let { origin ->
                    val originActionCard = if (contentExtrasVisible) origin.threadActionCard() else null
                    if (origin.isSystemMessage() && originActionCard == null) {
                        ThreadSystemMessageRow(
                            message = origin,
                            highlightedMentions = highlightedMentions,
                            showReplyAction = false,
                            timestampLabel = payload.formatTimestampLabel(origin.timestampLabel),
                            resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                                value = origin.taskAssigneeName,
                                id = origin.taskAssigneeId
                            ),
                            resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                            taskOpenEnabled = payload.showTaskOpenActions && contentExtrasVisible,
                            taskStatusActionsEnabled = payload.showTaskStatusActions && contentExtrasVisible,
                            onOpenThread = { onThreadEvent("openMessageThread", origin.id, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor -> onOpenTaskStatusMenu(origin, anchor) },
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl
                        )
                    } else {
                        ThreadMessageCard(
                            message = origin,
                            actionCard = originActionCard,
                            highlightedMentions = highlightedMentions,
                            showReplyAction = false,
                            resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                                value = origin.taskAssigneeName,
                                id = origin.taskAssigneeId
                            ),
                            resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                            taskOpenEnabled = payload.showTaskOpenActions && contentExtrasVisible,
                            taskStatusActionsEnabled = payload.showTaskStatusActions && contentExtrasVisible,
                            showSaveAction = payload.showMessageSaveAction,
                            knownMarkdownChannelNames = payload.knownMarkdownChannelNames.toKnownMarkdownChannelNameSet(),
                            onShowActions = { anchor -> onShowActions(origin, anchor) },
                            onOpenMember = onOpenMember,
                            onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor -> onOpenTaskStatusMenu(origin, anchor) },
                            onToggleReaction = { emoji -> onThreadEvent("toggleReaction", origin.id, null, emoji) },
                            onToggleSaved = { onThreadEvent("toggleMessageSaved", origin.id, null, null) },
                            onOpenCommentHost = { onThreadEvent("openCommentHost", origin.id, null, null) },
                            timestampLabel = payload.formatTimestampLabel(origin.timestampLabel),
                            onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                            attachmentCommentsEnabled = attachmentCommentsEnabled,
                            onOpenAttachmentComments = { onThreadEvent("openAttachmentComments", origin.id, it, null) },
                            attachmentOpenStates = if (contentExtrasVisible) attachmentOpenStates else emptyMap(),
                            pendingActionCardIds = if (contentExtrasVisible) pendingActionCardIds else emptySet(),
                            onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
                            currentServerSlug = currentServerSlug,
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl,
                            richContentEnabled = contentExtrasVisible
                        )
                    }
                } ?: payload.originPreview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun ThreadMessageAvatar(
    avatarLabel: String,
    avatarWireValue: String?,
    showStatus: Boolean,
    onClick: () -> Unit
) {
    val avatarSource =
        remember(avatarWireValue) {
            avatarWireValue?.takeIf { it.isNotBlank() }
        }
    Box(
        modifier = Modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatarWithStatus(
            statusColor = if (showStatus) SlockColor.AccentLime else null,
            avatarSize = 40.dp,
            statusSize = 10.dp
        ) {
            SlockAvatar(
                avatarWireValue = avatarSource,
                label = avatarLabel,
                avatarSize = 40.dp,
                borderWidth = SlockStroke.Default
            )
        }
    }
}

@Composable
private fun ThreadMessageStaticAvatar(
    avatarLabel: String,
    avatarWireValue: String?,
    showStatus: Boolean
) {
    val avatarSource =
        remember(avatarWireValue) {
            avatarWireValue?.takeIf { it.isNotBlank() }
        }
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatarWithStatus(
            statusColor = if (showStatus) SlockColor.AccentLime else null,
            avatarSize = 40.dp,
            statusSize = 10.dp
        ) {
            SlockAvatar(
                avatarWireValue = avatarSource,
                label = avatarLabel,
                avatarSize = 40.dp,
                borderWidth = SlockStroke.Default
            )
        }
    }
}

private fun KuiklyThreadMessageItem.showsAgentStatusIndicator(): Boolean =
    authorKind.equals("agent", ignoreCase = true) || authorId.isLikelyThreadAgentId()

@Composable
private fun ThreadPixelAvatar(spec: ThreadPixelAvatarSpec) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(spec.background)
        spec.spans.forEach { span ->
            val left = threadPixelAvatarGridEdge(span.startColumnIndex, size.width)
            val right = threadPixelAvatarGridEdge(span.startColumnIndex + span.columnCount, size.width)
            val top = threadPixelAvatarGridEdge(span.rowIndex, size.height)
            val bottom = threadPixelAvatarGridEdge(span.rowIndex + 1, size.height)
            drawRect(
                color = span.color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top)
            )
        }
    }
}

private fun threadPixelAvatarGridEdge(index: Int, extent: Float): Float =
    when {
        index <= 0 -> 0f
        index >= ThreadPixelAvatarGridCellCount -> extent
        else -> (extent * index / ThreadPixelAvatarGridCellCount).roundToInt().toFloat()
    }

private data class ThreadPixelAvatarSpec(val background: Color, val grid: List<String>) {
    val spans: List<ThreadPixelAvatarSpan> =
        buildList {
            grid.forEachIndexed { rowIndex, row ->
                var spanColor: Color? = null
                var spanStart = 0
                var spanLength = 0
                fun flushSpan() {
                    val color = spanColor ?: return
                    add(
                        ThreadPixelAvatarSpan(
                            rowIndex = rowIndex,
                            startColumnIndex = spanStart,
                            columnCount = spanLength,
                            color = color
                        )
                    )
                    spanColor = null
                    spanLength = 0
                }
                row.forEachIndexed { columnIndex, key ->
                    val color = ThreadPixelAvatarPalette[key] ?: Color.Transparent
                    when {
                        color == Color.Transparent -> flushSpan()
                        color == spanColor -> spanLength += 1
                        else -> {
                            flushSpan()
                            spanColor = color
                            spanStart = columnIndex
                            spanLength = 1
                        }
                    }
                }
                flushSpan()
            }
        }
}

private data class ThreadPixelAvatarSpan(
    val rowIndex: Int,
    val startColumnIndex: Int,
    val columnCount: Int,
    val color: Color
)

private const val ThreadPixelAvatarGridCellCount = 8
private const val ThreadPixelAvatarProtocolPrefix = "pixel:"
private const val ThreadPixelAvatarRandomPrefix = "random:"
private const val ThreadPixelAvatarDefaultKey = "robot"

private val ThreadPixelAvatarPalette = mapOf(
    '_' to Color.Transparent,
    'K' to Color(0xFF141111),
    'W' to Color(0xFFFFFFFF),
    'P' to Color(0xFFFE7DA8),
    'O' to Color(0xFFF8A16F),
    'Y' to Color(0xFFFFD440),
    'L' to Color(0xFFA9D877),
    'C' to Color(0xFF27CCF3),
    'V' to Color(0xFFBBAFE6),
    'B' to Color(0xFF576574),
    'R' to Color(0xFFF97264),
    'G' to Color(0xFF9DCAAA)
)

private val ThreadPixelAvatarFixedSpecs = mapOf(
    "robot" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("_KKKKKK_", "KCKWWKCK", "KWKWWKWK", "KKKKKKKK", "_KWKKWK_", "_KKKKKK_", "_KK__KK_", "_KK__KK_")),
    "cat" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("K______K", "KK____KK", "KYKYYKYK", "KYYYYYYK", "KYKYYKYK", "_KYYYYK_", "__KYYK__", "___KK___")),
    "ghost" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('V'), listOf("__KKKK__", "_KWWWWK_", "KWKWWKWK", "KWWWWWWK", "KWWWWWWK", "KWWKKWWK", "KWKWWKWK", "K_K__K_K")),
    "skull" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('O'), listOf("_KKKKKK_", "KWWWWWWK", "KWKWWKWK", "KWWWWWWK", "_KWWWWK_", "_KWKWKK_", "__KKKK__", "___KK___")),
    "alien" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('L'), listOf("_KKKKKK_", "KGGGGGGK", "KGKKKKGK", "KGGGGGGK", "_KGGGGK_", "__KGGK__", "__KGGK__", "_KK__KK_")),
    "heart" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('P'), listOf("________", "_KK__KK_", "KRRKKRRK", "KRRRRRRK", "KRRRRRRK", "_KRRRRK_", "__KRRK__", "___KK___")),
    "star" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("___KK___", "___KK___", "KKKKKKKK", "_KYYYYK_", "__KYYK__", "_KYKKYK_", "KYK__KYK", "KK____KK")),
    "flame" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('O'), listOf("___K____", "__KRK___", "__KRKK__", "_KORRRK_", "_KOORRK_", "KYOOORK_", "KYYOOK__", "_KKKK___")),
    "diamond" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("___KK___", "__KCCK__", "_KCWCCK_", "KCWCCCCK", "KCCCCCCK", "_KCCCCK_", "__KCCK__", "___KK___")),
    "mushroom" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('P'), listOf("__KKKK__", "_KRWWRK_", "KRRWWRRK", "KRRRRRRK", "_KKKKKK_", "__KWWK__", "__KWWK__", "_KKKKKK_")),
    "eye" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('V'), listOf("________", "__KKKK__", "_KWWWWK_", "KWWKKWWK", "KWWKKWWK", "_KWWWWK_", "__KKKK__", "________")),
    "crown" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("________", "_K_KK_K_", "_KKKKKK_", "_KYYYYK_", "_KYYYYK_", "KKKKKKKK", "KYYYYYYK", "KKKKKKKK")),
    "cloud" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("________", "__KK____", "_KWWKK__", "KWWWWWK_", "KWWWWWWK", "KWWWWWWK", "_KKKKKK_", "________")),
    "sun" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('R'), listOf("_K_KK_K_", "K_KYYK_K", "_KYYYYK_", "KYYYYYYK", "KYYYYYYK", "_KYYYYK_", "K_KYYK_K", "_K_KK_K_")),
    "bell" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('B'), listOf("___KK___", "__KYYK__", "_KYYYYK_", "_KYYYYK_", "_KYYYYK_", "KYYYYYYK", "KKKKKKKK", "___KK___")),
    "tree" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('L'), listOf("___KK___", "__KGGK__", "_KGGGGK_", "KGGGGGGK", "KGGGGGGK", "_KKGGKK_", "__KOOK__", "___KK___"))
)

private val ThreadPixelAvatarRandomOptions = listOf(
    ThreadPixelAvatarPalette.getValue('C') to 'K',
    ThreadPixelAvatarPalette.getValue('Y') to 'K',
    ThreadPixelAvatarPalette.getValue('L') to 'K',
    ThreadPixelAvatarPalette.getValue('P') to 'W',
    ThreadPixelAvatarPalette.getValue('V') to 'K',
    Color(0xFF1E1E1C) to 'C',
    Color(0xFF1E1E1C) to 'G',
    Color(0xFF1E1E1C) to 'P',
    Color(0xFF1E1E1C) to 'Y',
    Color(0xFF1E1E1C) to 'V',
    ThreadPixelAvatarPalette.getValue('O') to 'K',
    ThreadPixelAvatarPalette.getValue('C') to 'W'
)

private fun String.toThreadPixelAvatarSpec(): ThreadPixelAvatarSpec? =
    removeThreadPrefixOrNull(ThreadPixelAvatarProtocolPrefix)?.let {
        ThreadPixelAvatarFixedSpecs[it] ?: ThreadPixelAvatarFixedSpecs.getValue(ThreadPixelAvatarDefaultKey)
    } ?: removeThreadPrefixOrNull(ThreadPixelAvatarRandomPrefix)?.let(::randomThreadPixelAvatar)

private fun String.removeThreadPrefixOrNull(prefix: String): String? =
    takeIf { startsWith(prefix) }?.removePrefix(prefix)?.ifBlank { null }

private fun randomThreadPixelAvatar(seed: String): ThreadPixelAvatarSpec {
    val random = ThreadPixelAvatarRandom(threadPixelHash(seed))
    val (background, fill) = ThreadPixelAvatarRandomOptions[(random.next() * ThreadPixelAvatarRandomOptions.size).toInt()]
    val grid = List(ThreadPixelAvatarGridCellCount) {
        val left = List(ThreadPixelAvatarGridCellCount / 2) {
            if (random.next() < 0.45) fill else '_'
        }
        (left + left.asReversed()).joinToString(separator = "")
    }
    return ThreadPixelAvatarSpec(background, grid)
}

private fun threadPixelHash(input: String): Int {
    var hash = 0
    input.forEach { character ->
        hash = (hash shl 5) - hash + character.code
    }
    return hash
}

private class ThreadPixelAvatarRandom(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 1831565813
        var mixed = (value xor (value ushr 15)) * (1 or value)
        mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed
        val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL
        return unsigned.toDouble() / 4294967296.0
    }
}

private fun KuiklyThreadMessageCommentRef.hasThreadCommentHostSource(): Boolean {
    val source = hostSource ?: return false
    if (hostMessageId.isNullOrBlank() || source.channelId.isBlank()) return false
    return when (source.type) {
        "channel" -> source.routeKind == "channel" || source.routeKind == "dm"
        "thread" ->
            (source.routeKind == "channel" || source.routeKind == "dm") &&
                (!source.parentMessageId.isNullOrBlank() || !source.rootThreadChannelId.isNullOrBlank())
        else -> false
    }
}

@Composable
private fun ThreadInlineMetaTag(label: String) {
    Text(
        text = label,
        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
        color = SlockColor.TextBlack,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceCream)
                .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun ThreadMessageCommentRefChip(
    ref: KuiklyThreadMessageCommentRef,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val label =
        buildString {
            append("re: ")
            append(ref.filename)
            ref.anchorLabel?.takeIf { it.isNotBlank() }?.let { anchor ->
                append(" · ")
                append(anchor)
            }
        }
    Row(
        modifier =
            modifier
                .then(if (enabled) Modifier.clickable(onClick = onOpen) else Modifier)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceCream.copy(alpha = if (enabled) 1f else 0.7f))
                .padding(horizontal = SlockSpacing.Xs, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.MessageSquare,
            modifier = Modifier.size(12.dp),
            color = if (enabled) "#FF111827" else "#FF6B7280"
        )
        Text(
            text = label,
            style =
                SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
                ),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private val SharedThreadAttachmentImageSize = 92.dp
private val ThreadAttachmentChipWidth = 176.dp
private val ThreadAttachmentChipHeight = 80.dp

private fun KuiklyThreadAttachmentItem.isImageAttachment(): Boolean =
    mimeType.startsWith("image/", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.isPdfAttachment(): Boolean =
    mimeType.substringBefore(';').trim().equals("application/pdf", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.normalizedMimeType(): String =
    mimeType.substringBefore(';').trim().lowercase()

private fun KuiklyThreadAttachmentItem.isMarkdownAttachment(): Boolean =
    normalizedMimeType().let { normalizedMime ->
        normalizedMime.equals("text/markdown", ignoreCase = true) ||
            normalizedMime.equals("text/x-markdown", ignoreCase = true)
    } ||
        name.endsWith(".md", ignoreCase = true) ||
        name.endsWith(".markdown", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.isPreviewableDocumentAttachment(): Boolean =
    isPdfAttachment() ||
        isMarkdownAttachment() ||
        normalizedMimeType() in setOf("text/plain", "text/csv", "application/csv", "application/json")

private fun KuiklyThreadAttachmentItem.isHtmlAttachment(): Boolean =
    normalizedMimeType() == "text/html" || name.endsWith(".html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.threadAttachmentUsesPreviewAffordance(): Boolean =
    isPreviewableDocumentAttachment() || isHtmlAttachment() || normalizedMimeType().startsWith("video/")

internal fun KuiklyThreadAttachmentItem.genericAttachmentMetaLine(): String =
    listOf(sizeLabel, mimeType)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

internal fun KuiklyThreadAttachmentItem.threadAttachmentChipMetaLine(): String =
    listOf(
        when {
            isHtmlAttachment() -> "HTML preview"
            mimeType.isNotBlank() -> mimeType
            else -> "file"
        },
        sizeLabel
    )
        .filter { it.isNotBlank() }
        .joinToString(" · ")

internal fun KuiklyThreadAttachmentItem.threadAttachmentSummaryLine(): String? =
    when {
        isPreviewableDocumentAttachment() -> "Document preview"
        else -> null
    }

private fun KuiklyThreadAttachmentItem.toThreadAttachmentPreviewPayload(
    kind: String,
    title: String,
    content: String = "",
    imageUrl: String? = null,
    message: String = "",
    error: String? = null,
    externalUrl: String? = null
): SlockFilePreviewPayload =
    SlockFilePreviewPayload(
        kind = kind,
        title = title,
        subtitle = name.ifBlank { "Attachment" },
        name = name.ifBlank { "Attachment" },
        path = name,
        meta = genericAttachmentMetaLine(),
        content = content,
        markdown = kind == "markdown",
        imageUrl = imageUrl,
        message = message,
        error = error,
        externalUrl = externalUrl,
        externalFileName = name.ifBlank { "attachment-${id.take(8)}" },
        externalMimeType = mimeType
    )

private fun KuiklyThreadAttachmentItem.threadAttachmentCommentCountLabel(): String? =
    commentCount
        .takeIf { it > 0 && !isImageAttachment() && !isPdfAttachment() }
        ?.let { count -> "$count ${if (count == 1) "comment" else "comments"}" }

@Composable
private fun ThreadMessageAttachments(
    attachments: List<KuiklyThreadAttachmentItem>,
    alignEnd: Boolean,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    commentsEnabled: Boolean = false,
    onOpenComments: (String) -> Unit = {},
    onOpenAttachment: (String) -> Unit
) {
    if (attachments.isEmpty()) return

    val imageAttachments = remember(attachments) { attachments.filter { it.isImageAttachment() } }
    val fileAttachments = remember(attachments) { attachments.filterNot { it.isImageAttachment() } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        if (imageAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        SlockSpacing.Sm,
                        if (alignEnd) Alignment.End else Alignment.Start
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(imageAttachments.size, key = { imageAttachments[it].id }) { index ->
                    ThreadImageAttachmentTile(
                        attachment = imageAttachments[index],
                        onOpen = { onOpenAttachment(imageAttachments[index].id) }
                    )
                }
            }
        }
        fileAttachments.forEach { attachment ->
            ThreadAttachmentChip(
                attachment = attachment,
                openState = attachmentOpenStates[attachment.id],
                commentsEnabled = commentsEnabled,
                onOpen = { onOpenAttachment(attachment.id) },
                onOpenComments = { onOpenComments(attachment.id) }
            )
        }
    }
}

@Composable
private fun ThreadImageAttachmentTile(
    attachment: KuiklyThreadAttachmentItem,
    onOpen: () -> Unit
) {
    Box(
        modifier = Modifier.size(SharedThreadAttachmentImageSize)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        val thumbnailUrl = attachment.thumbnailUrl?.takeIf { it.isNotBlank() }
        SlockAspectFitImagePreviewSurface(
            source = thumbnailUrl,
            contentDescription = attachment.name,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = attachment.name,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite.copy(alpha = 0.92f))
                .padding(horizontal = SlockSpacing.Xs, vertical = 3.dp)
        )
    }
}

@Composable
private fun ThreadAttachmentChip(
    attachment: KuiklyThreadAttachmentItem,
    openState: ThreadAttachmentOpenUiState?,
    commentsEnabled: Boolean = false,
    onOpen: () -> Unit,
    onOpenComments: () -> Unit = {}
) {
    val isOpening = openState.threadAttachmentIsOpening()
    val canOpenComments = commentsEnabled && !attachment.isImageAttachment() && !attachment.isPdfAttachment()
    val meta = attachment.threadAttachmentChipMetaLine()
    val statusLabel = openState.threadAttachmentStatusLabel()
    val summaryLabel = statusLabel ?: attachment.threadAttachmentSummaryLine()
    val affordanceIcon =
        if (attachment.threadAttachmentUsesPreviewAffordance()) SlockLucideIconName.Eye else SlockLucideIconName.Download
    Column(
        modifier = Modifier.width(ThreadAttachmentChipWidth),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Box(
            modifier =
                Modifier
                    .size(ThreadAttachmentChipWidth, ThreadAttachmentChipHeight)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.15f))
                    .background(if (isOpening) SlockColor.BrandYellow.copy(alpha = 0.3f) else SlockColor.SurfaceWhite)
                    .clickable(onClick = if (isOpening) ({}) else onOpen)
                    .padding(horizontal = 10.dp, vertical = SlockSpacing.Sm)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = attachment.name,
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack.copy(alpha = 0.45f)),
                            color = SlockColor.TextBlack.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                summaryLabel?.let { label ->
                    Text(
                        text = label,
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = SlockColor.TextBlack.copy(alpha = 0.7f)
                        ),
                        color = SlockColor.TextBlack.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 134.dp)
                    )
                }
            }
            SlockLucideIcon(
                affordanceIcon,
                modifier = Modifier.align(Alignment.BottomEnd).size(12.dp),
                color = "#99000000"
            )
            if (isOpening) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .height(6.dp)
                            .width(36.dp)
                            .background(SlockColor.BrandYellow),
                    contentAlignment = Alignment.Center
                ) {}
            }
        }
        if (canOpenComments) {
            SlockBrutalActionButton(
                label = attachment.threadAttachmentCommentCountLabel() ?: "Comments",
                onClick = onOpenComments,
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = SlockColor.SurfaceCream,
                minWidth = 0.dp,
                minHeight = 30.dp,
                leadingIcon = SlockLucideIconName.MessageCircle,
                shadowOffset = 0.dp,
                textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack)
            )
        }
    }
}

@Composable
private fun ThreadAttachmentCommentsDialog(
    state: ThreadAttachmentCommentsDialogState,
    onDismiss: () -> Unit,
    onFilterChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onResolve: (ThreadAttachmentCommentItem) -> Unit,
    onQuote: (ThreadAttachmentCommentItem) -> Unit
) {
    val page = state.page
    val comments =
        remember(state.filter, page?.comments) {
            val all = page?.comments.orEmpty()
            if (state.filter == "unresolved") {
                all.filterNot { it.isThreadAttachmentCommentResolved() }
            } else {
                all
            }
        }
    val viewerCanComment = page?.viewer?.canComment ?: state.status == "loading"
    SlockUnifiedDialog(
        title = "COMMENTS",
        onDismiss = onDismiss,
        minWidth = 312.dp,
        maxWidth = 360.dp,
        contentPadding = 18.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        contentSpacing = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThreadAttachmentCommentsHeader(state.attachment)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThreadAttachmentCommentsFilterChip(
                    label = "All",
                    selected = state.filter != "unresolved",
                    onClick = { onFilterChange("all") }
                )
                ThreadAttachmentCommentsFilterChip(
                    label = "Unresolved",
                    selected = state.filter == "unresolved",
                    onClick = { onFilterChange("unresolved") }
                )
            }
            when (state.status) {
                "loading" ->
                    ThreadStateMessage("Loading comments...", null)
                "error" ->
                    ThreadStateActionMessage(
                        title = "Failed to load comments",
                        detail = state.errorCode ?: "thread_attachment_comments_failed",
                        actionLabel = "Retry",
                        onClick = onRetry
                    )
                else ->
                    SlockModalListShell(height = threadAttachmentCommentsListHeight(comments.size)) {
                        if (comments.isEmpty()) {
                            item {
                                ThreadModalEmptyState(
                                    title = "NO COMMENTS",
                                    detail = if (state.filter == "unresolved") "ALL COMMENTS ARE RESOLVED." else "START A COMMENT BELOW."
                                )
                            }
                        } else {
                            items(comments.size, key = { comments[it].id }) { index ->
                                ThreadAttachmentCommentRow(
                                    comment = comments[index],
                                    canComment = viewerCanComment,
                                    resolving = comments[index].id in state.resolvingCommentIds,
                                    onResolve = { onResolve(comments[index]) },
                                    onQuote = { onQuote(comments[index]) }
                                )
                            }
                        }
                    }
            }
            state.errorCode?.takeIf { state.status != "error" && it.isNotBlank() }?.let { code ->
                Text(
                    text = code.uppercase(),
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ThreadAttachmentCommentComposer(
                state = state,
                canComment = page?.viewer?.canComment ?: true,
                reason = page?.viewer?.reason,
                onDraftChange = onDraftChange,
                onSend = onSend
            )
        }
    }
}

@Composable
private fun ThreadAttachmentCommentsHeader(attachment: KuiklyThreadAttachmentItem) {
    Row(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceCream)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(SlockLucideIconName.FileText, modifier = Modifier.size(15.dp), color = "#FF111827")
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = attachment.name,
                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(attachment.sizeLabel, attachment.mimeType).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThreadAttachmentCommentsFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    SlockToolbarChip(
        label = label,
        onClick = onClick,
        active = selected,
        uppercase = false,
        activeBackgroundColor = SlockColor.BrandYellow,
        inactiveBackgroundColor = SlockColor.SurfaceWhite,
        borderColor = SlockColor.BorderBlack
    )
}

@Composable
private fun ThreadAttachmentCommentRow(
    comment: ThreadAttachmentCommentItem,
    canComment: Boolean,
    resolving: Boolean,
    onResolve: () -> Unit,
    onQuote: () -> Unit
) {
    val resolved = comment.isThreadAttachmentCommentResolved()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = comment.senderName.ifBlank { "Unknown" },
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            comment.createdAt.takeIf { it.isNotBlank() }?.let { createdAt ->
                Text(
                    text = createdAt,
                    style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        comment.anchorLabel?.takeIf { it.isNotBlank() }?.let { label ->
            ThreadInlineMetaTag("Anchor: $label")
        }
        Text(
            text = comment.content,
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            ThreadAttachmentCommentTinyButton(
                label = "Quote",
                icon = SlockLucideIconName.MessageSquarePlus,
                enabled = canComment && comment.content.isNotBlank(),
                onClick = onQuote
            )
            ThreadAttachmentCommentTinyButton(
                label = if (resolved) "Resolved" else if (resolving) "Resolving" else "Resolve",
                icon = if (resolved) SlockLucideIconName.CircleCheckBig else SlockLucideIconName.Check,
                enabled = canComment && !resolved && !resolving,
                onClick = onResolve
            )
        }
    }
}

@Composable
private fun ThreadAttachmentCommentTinyButton(
    label: String,
    icon: SlockLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = icon,
        onClick = onClick,
        enabled = enabled,
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 28.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack)
    )
}

@Composable
private fun ThreadAttachmentCommentComposer(
    state: ThreadAttachmentCommentsDialogState,
    canComment: Boolean,
    reason: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val enabled = canComment && state.status == "loaded" && !state.sending
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            state.pendingAnchorLabel?.takeIf { it.isNotBlank() }?.let { label ->
                ThreadInlineMetaTag("Anchor: $label")
            }
            state.pendingQuoteLabel?.takeIf { it.isNotBlank() }?.let { label ->
                ThreadInlineMetaTag("Quote: $label")
            }
        }
        if (!canComment) {
            Text(
                text = threadAttachmentCommentBlockedCopy(reason),
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        SlockBrutalTextField(
            label = "COMMENT",
            value = state.draft,
            placeholder = "ADD COMMENT",
            enabled = enabled,
            maxLines = 4,
            fieldMinHeight = 82.dp,
            onValueChange = onDraftChange
        )
        SlockModalFullWidthActionButton(
            label = if (state.sending) "SENDING" else "SEND COMMENT",
            icon = if (state.sending) SlockLucideIconName.RefreshCw else SlockLucideIconName.Send,
            enabled = enabled && state.draft.isNotBlank(),
            color = SlockColor.AccentCyan,
            onClick = onSend
        )
    }
}

private fun ThreadAttachmentCommentItem.isThreadAttachmentCommentResolved(): Boolean =
    reactions.any { reaction -> reaction.emoji == "✅" || reaction.emoji == "☑️" }

private fun String?.attachmentCommentsEnabledForThread(): Boolean =
    this?.trim()?.equals("botiverse", ignoreCase = true) == true

private fun KuiklyThreadScreenPayload.findAttachmentCommentTarget(attachmentId: String): ThreadAttachmentCommentTarget? {
    val cleanAttachmentId = attachmentId.trim()
    if (cleanAttachmentId.isBlank()) return null
    val allMessages = listOfNotNull(originMessage) + this.messages
    allMessages.forEach { message ->
        val attachment = message.attachments.firstOrNull { it.id == cleanAttachmentId }
        if (attachment != null) {
            return ThreadAttachmentCommentTarget(attachment = attachment, parentMessage = message)
        }
    }
    return null
}

private fun threadAttachmentCommentsListHeight(count: Int): Dp =
    when {
        count <= 0 -> 112.dp
        count == 1 -> 132.dp
        else -> 230.dp
    }

private fun threadAttachmentCommentBlockedCopy(reason: String?): String =
    when (reason?.trim()?.lowercase()) {
        "archived" -> "THIS CHANNEL IS ARCHIVED."
        "not_member" -> "JOIN THIS CHANNEL BEFORE COMMENTING."
        "read_only" -> "COMMENTS ARE READ-ONLY."
        "unlinked" -> "THIS ATTACHMENT IS NOT LINKED TO A COMMENT THREAD."
        else -> "COMMENTS ARE NOT AVAILABLE."
    }

@Composable
private fun ThreadFilesTab(
    filesViewModel: SharedThreadFilesViewModel,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    onAuthRequired: (String?) -> Unit,
    onOpenAttachment: (KuiklyThreadAttachmentItem, ThreadImageAttachmentGroup?) -> Unit,
    onDownloadAttachment: (KuiklyThreadAttachmentItem) -> Unit
) {
    val filesState by filesViewModel.state.collectAsState()
    val payload = filesState.payload
    LaunchedEffect(payload.instanceId) {
        if (payload.shouldFetchInitialFiles()) {
            filesViewModel.dispatch(SharedThreadFilesIntent.Refresh("initial_tab"))
        }
    }
    LaunchedEffect(payload.status, payload.errorCode) {
        if (payload.status == "no_permission" || payload.status == "error") {
            onAuthRequired(payload.errorCode)
        }
    }
    ThreadFilesContent(payload = payload, attachmentOpenStates = attachmentOpenStates) { action, attachmentId ->
        when (action) {
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
            "loadMore" -> filesViewModel.dispatch(SharedThreadFilesIntent.LoadMore("load_more"))
            "retry" -> filesViewModel.dispatch(SharedThreadFilesIntent.Refresh("retry"))
        }
    }
}

@Composable
private fun ThreadTasksTab(
    tasksViewModel: SharedTasksViewModel?,
    channelId: ChannelId,
    openTaskEnabled: Boolean,
    onOpenTask: (String) -> Unit,
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
    SharedTasksPanel(
        state = state,
        showHeader = false,
        showChannelFilter = false,
        createTasksChannelId = channelId,
        onOpenTask = if (openTaskEnabled) {
            { task -> onOpenTask(task.id.value) }
        } else {
            null
        },
        onIntent = onIntent
    )
}

private fun KuiklyThreadTaskItem.toTaskSummary(): TaskSummary =
    TaskSummary(
        id = TaskId(id),
        title = title,
        status = status.toSharedThreadTaskStatus() ?: TaskStatus.Todo,
        taskNumber = taskNumber,
        messageId = messageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        assignee =
            assigneeName?.trim()?.takeIf { it.isNotBlank() }?.let { displayName ->
                MemberProfile(
                    id = MemberId("thread-task-assignee-${displayName.threadTaskAssigneeKey()}"),
                    displayName = displayName,
                    handle = displayName,
                    kind = MemberKind.Human
                )
            },
        updatedAtLabel = updatedAtLabel
    )

private fun String.threadTaskAssigneeKey(): String =
    trim()
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '@' || it == '.' || it == '-' || it == '_' }
        .ifBlank { "unknown" }

@Composable
private fun ThreadJoinFooter(label: String, onJoin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "You need to join before sending.",
                style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            Text(
                text = "This thread is visible, but sending is currently disabled.",
                style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        ThreadHeaderAction(label = label, onClick = onJoin)
    }
}

@Composable
private fun ThreadMembersModal(
    members: List<KuiklyThreadMemberItem>,
    membersCount: Int,
    canAddMembers: Boolean,
    removeMemberStatus: String,
    removingMemberId: String?,
    removeMemberErrorCode: String?,
    onDismiss: () -> Unit,
    onAddMember: () -> Unit,
    onRemoveMember: (String) -> Unit
) {
    // Memoize the agents/humans partition so recomposition that does
    // not change the members list does not re-run the filter. This
    // pairs with the loadMembers idempotency in SharedThreadStore:
    // once members are loaded for the current thread, the modal open /
    // close cycle does not trigger a refetch AND does not redo the
    // partition work either.
    val (agents, humans) = remember(members) {
        val a = members.filter { it.kind.equals("agent", ignoreCase = true) }
        val h = members.filter { !it.kind.equals("agent", ignoreCase = true) }
        a to h
    }
    val removeErrorText =
        when (removeMemberStatus) {
            "failed" -> "UNABLE TO REMOVE MEMBER: ${(removeMemberErrorCode ?: "member_remove_failed").uppercase()}"
            "no_permission" -> "UNABLE TO REMOVE MEMBER: NO PERMISSION"
            else -> null
        }
    SlockUnifiedDialog(
        title = "MEMBERS (${membersCount.takeIf { it > 0 } ?: members.size})",
        onDismiss = onDismiss,
        minWidth = 300.dp,
        maxWidth = 332.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!removeErrorText.isNullOrBlank()) {
                Text(
                    text = removeErrorText,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            }
            SlockModalListShell(height = threadMemberListHeight(agents.size, humans.size).dp) {
                if (members.isEmpty()) {
                    item {
                        ThreadModalEmptyState(
                            title = "NO MEMBERS",
                            detail = "REFRESH THIS CONVERSATION AND TRY AGAIN."
                        )
                    }
                } else {
                    if (agents.isNotEmpty()) {
                        item { SlockModalSectionHeader("AGENTS") }
                        items(agents.size, key = { agents[it].memberKey }) { index ->
                            val member = agents[index]
                            val removing = removeMemberStatus == "removing" && removingMemberId == member.id
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                sourceBadgeLabel = member.sourceServerLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                enabled = !removing,
                                trailingIcon = SlockLucideIconName.X.takeIf { member.canRemove && !removing },
                                trailingText = if (removing) "REMOVING" else "",
                                onTrailingClick = if (member.canRemove && !removing) ({ onRemoveMember(member.id) }) else null
                            )
                        }
                    }
                    if (humans.isNotEmpty()) {
                        item { SlockModalSectionHeader("HUMANS") }
                        items(humans.size, key = { humans[it].memberKey }) { index ->
                            val member = humans[index]
                            val removing = removeMemberStatus == "removing" && removingMemberId == member.id
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                sourceBadgeLabel = member.sourceServerLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                enabled = !removing,
                                trailingIcon = SlockLucideIconName.X.takeIf { member.canRemove && !removing },
                                trailingText = if (removing) "REMOVING" else "",
                                onTrailingClick = if (member.canRemove && !removing) ({ onRemoveMember(member.id) }) else null
                            )
                        }
                    }
                }
            }
            SlockModalFullWidthActionButton(
                label = "ADD MEMBER",
                icon = SlockLucideIconName.UserPlus,
                onClick = { if (canAddMembers) onAddMember() },
                color = SlockColor.AccentPink,
                enabled = canAddMembers
            )
        }
    }
}

@Composable
private fun ThreadAddMemberModal(
    members: List<KuiklyThreadMemberItem>,
    candidates: List<KuiklyThreadMemberItem>,
    canAddMembers: Boolean,
    addMemberStatus: String,
    addMemberErrorCode: String?,
    initialSelectedId: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (KuiklyThreadMemberItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember(candidates, initialSelectedId) { mutableStateOf(initialSelectedId) }
    var localError by remember { mutableStateOf(false) }
    val currentKeys = remember(members) { members.map { it.memberKey }.toSet() }
    val normalizedQuery = query.trim()
    val filteredCandidates =
        candidates
            .filter { member ->
                normalizedQuery.isBlank() ||
                    member.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    member.handle.contains(normalizedQuery, ignoreCase = true) ||
                    member.roleLabel.contains(normalizedQuery, ignoreCase = true) ||
                    member.id.contains(normalizedQuery, ignoreCase = true)
            }
            .distinctBy { it.memberKey }
    val agentCandidates = filteredCandidates.filter { it.kind.equals("agent", ignoreCase = true) }
    val humanCandidates = filteredCandidates.filter { !it.kind.equals("agent", ignoreCase = true) }
    val blocked = !canAddMembers || addMemberStatus == "no_permission"
    val adding = addMemberStatus == "adding"
    val errorText =
        when {
            blocked -> "YOU DO NOT HAVE PERMISSION TO ADD MEMBERS."
            localError -> "SELECT A MEMBER BEFORE ADDING."
            addMemberStatus == "failed" -> "UNABLE TO ADD MEMBER: ${(addMemberErrorCode ?: "member_add_failed").uppercase()}"
            else -> null
        }
    SlockUnifiedDialog(
        title = "ADD MEMBER",
        onDismiss = onDismiss,
        minWidth = 300.dp,
        maxWidth = 332.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            }
            SlockModalTextField(
                label = "SEARCH",
                value = query,
                placeholder = "NAME",
                leadingIcon = SlockLucideIconName.Search,
                enabled = !blocked,
                onValueChange = {
                    if (!blocked) {
                        query = it
                        selectedId = null
                        localError = false
                    }
                }
            )
            SlockModalListShell(height = threadMemberListHeight(agentCandidates.size, humanCandidates.size).dp) {
                if (filteredCandidates.isEmpty()) {
                    item {
                        ThreadModalEmptyState(
                            title = if (candidates.isEmpty()) "NO PEOPLE LOADED" else "NO MATCHING PEOPLE",
                            detail = if (candidates.isEmpty()) "REFRESH THIS CONVERSATION AND TRY AGAIN." else "TRY A DIFFERENT SEARCH."
                        )
                    }
                } else {
                    if (agentCandidates.isNotEmpty()) {
                        item { SlockModalSectionHeader("AGENTS") }
                        items(agentCandidates.size, key = { agentCandidates[it].memberKey }) { index ->
                            val member = agentCandidates[index]
                            val alreadyJoined = member.memberKey in currentKeys
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (member.id == selectedId) SlockLucideIconName.Check else null,
                                trailingText = if (alreadyJoined) "JOINED" else "",
                                onClick = {
                                    selectedId = member.id
                                    localError = false
                                    onSubmit(member)
                                }
                            )
                        }
                    }
                    if (humanCandidates.isNotEmpty()) {
                        item { SlockModalSectionHeader("HUMANS") }
                        items(humanCandidates.size, key = { humanCandidates[it].memberKey }) { index ->
                            val member = humanCandidates[index]
                            val alreadyJoined = member.memberKey in currentKeys
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (member.id == selectedId) SlockLucideIconName.Check else null,
                                trailingText = if (alreadyJoined) "JOINED" else "",
                                onClick = {
                                    selectedId = member.id
                                    localError = false
                                    onSubmit(member)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun threadMemberListHeight(agentCount: Int, humanCount: Int, maxHeight: Int = 288): Int {
    val sectionCount = listOf(agentCount, humanCount).count { it > 0 }
    val rowCount = agentCount + humanCount
    return (sectionCount * 30 + rowCount * 58 + 8).coerceIn(108, maxHeight)
}

private val KuiklyThreadMemberItem.memberKey: String
    get() = "${kind.lowercase()}:$id"

private fun KuiklyThreadMemberItem.modalAvatarWireValue(): String? =
    avatarUrl?.takeIf { it.isNotBlank() }

private fun KuiklyThreadMemberItem.modalAvatarLabel(): String =
    avatarLabel.ifBlank { displayName.ifBlank { handle.ifBlank { id } } }

private fun KuiklyThreadMemberItem.modalStatusColor(): Color? =
    if (kind.equals("agent", ignoreCase = true)) SlockColor.AccentLime else null

private enum class ThreadSettingsDangerAction(
    val id: String,
    val title: String,
    val icon: SlockLucideIconName,
    val buttonLabel: String,
    val color: Color
) {
    Visibility("visibility", "MAKE CHANNEL PRIVATE", SlockLucideIconName.Lock, "Make Private", SlockColor.AccentOrange),
    Archive("archive", "ARCHIVE CHANNEL", SlockLucideIconName.Archive, "Archive Channel", SlockColor.AccentOrange),
    Delete("delete", "DELETE CHANNEL", SlockLucideIconName.Trash, "Delete Channel", SlockColor.AccentRed)
}

internal fun defaultThreadSettingsChannelActionCapabilities(): List<KuiklyThreadSettingsAction> =
    listOf(
        KuiklyThreadSettingsAction(id = "visibility"),
        KuiklyThreadSettingsAction(id = "archive"),
        KuiklyThreadSettingsAction(id = "delete")
    )

private fun defaultThreadSettingsChannelActions(): List<ThreadSettingsActionUi> =
    listOf(
        ThreadSettingsActionUi(
            id = "visibility",
            title = "Make Private",
            subtitle = "Restrict this channel to selected members",
            icon = SlockLucideIconName.Lock,
            destructive = true
        ),
        ThreadSettingsActionUi(
            id = "archive",
            title = "Archive Channel",
            subtitle = "Freeze writes and hide from sidebars",
            icon = SlockLucideIconName.Archive,
            destructive = true
        ),
        ThreadSettingsActionUi(
            id = "delete",
            title = "Delete Channel",
            subtitle = "Permanently remove this channel",
            icon = SlockLucideIconName.Trash,
            destructive = true
        )
    )

internal fun List<KuiklyThreadSettingsAction>.normalizedThreadSettingsChannelActions(
    useDefaultWhenEmpty: Boolean = true
): List<ThreadSettingsActionUi> {
    val defaults = defaultThreadSettingsChannelActions()
    val defaultById = defaults.associateBy { it.id }
    val canonicalActions =
        mapNotNull { action ->
            action.canonicalThreadSettingsActionId()?.let { id -> id to action.enabled }
        }
    if (canonicalActions.isEmpty()) {
        return if (useDefaultWhenEmpty) defaults else emptyList()
    }
    return canonicalActions
        .filter { (_, enabled) -> enabled }
        .distinctBy { (id, _) -> id }
        .map { (id, _) -> defaultById.getValue(id) }
}

private fun KuiklyThreadSettingsAction.canonicalThreadSettingsActionId(): String? =
    when (id) {
        "visibility",
        "make-private",
        "make_public" -> "visibility"
        "archive" -> "archive"
        "delete" -> "delete"
        else -> null
    }

@Composable
private fun ThreadSettingsModal(
    channelName: String,
    subtitle: String?,
    actions: List<KuiklyThreadSettingsAction>,
    onDismiss: () -> Unit,
    onOpenAction: (String) -> Unit
) {
    var name by remember(channelName) { mutableStateOf(channelName) }
    var description by remember(subtitle) { mutableStateOf(subtitle.orEmpty()) }
    var dangerAction by remember { mutableStateOf<ThreadSettingsDangerAction?>(null) }
    var confirmationName by remember(dangerAction) { mutableStateOf("") }
    val effectiveActions = actions.normalizedThreadSettingsChannelActions()
    val dangerActions = effectiveActions.mapNotNull { action ->
        when (action.id) {
            "visibility",
            "make-private",
            "make_public" -> ThreadSettingsDangerAction.Visibility
            "archive" -> ThreadSettingsDangerAction.Archive
            "delete" -> ThreadSettingsDangerAction.Delete
            else -> null
        }?.let { it to action }
    }
    val activeDanger = dangerAction
    val archiveConfirmationMatches = confirmationName.trim() == channelName.trim()
    SlockUnifiedDialog(
        title = activeDanger?.title ?: "EDIT CHANNEL",
        onDismiss = onDismiss
    ) {
        if (activeDanger == null) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SlockBrutalTextField(
                    label = "Name",
                    value = name,
                    placeholder = "e.g. ai-research",
                    onValueChange = { name = it },
                    required = true,
                    textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
                )
                SlockBrutalTextField(
                    label = "Description",
                    value = description,
                    placeholder = "What is this channel about?",
                    onValueChange = { description = it },
                    optional = true,
                    fieldMinHeight = 66.dp,
                    maxLines = 2
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(modifier = Modifier.weight(1f))
                    SlockModalActionButton(label = "Cancel", icon = SlockLucideIconName.X, onClick = onDismiss)
                    SlockModalActionButton(
                        label = "Save Changes",
                        icon = SlockLucideIconName.Check,
                        color = SlockColor.AccentPink,
                        onClick = onDismiss
                    )
                }
                if (dangerActions.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(SlockColor.BorderBlack))
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        dangerActions.forEach { (danger, action) ->
                            SlockModalFullWidthActionButton(
                                label = action.title,
                                icon = action.icon,
                                color = danger.color,
                                onClick = { dangerAction = danger }
                            )
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (activeDanger) {
                    ThreadSettingsDangerAction.Visibility -> {
                        Text(
                            text = "Update #$channelName visibility?",
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                        )
                        Text(
                            text = "This changes who can find and join the channel.",
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextMutedGray
                        )
                    }
                    ThreadSettingsDangerAction.Archive,
                    ThreadSettingsDangerAction.Delete -> {
                        if (activeDanger == ThreadSettingsDangerAction.Delete) {
                            ThreadDeleteChannelWarning(channelName)
                        } else {
                            Text(
                                text = "Archive #$channelName? Members keep read access and history, but writes will be frozen and the channel will be hidden from sidebars.",
                                style = SlockType.Body,
                                color = SlockColor.TextBlack
                            )
                            Text(
                                text = "Type $channelName to confirm.",
                                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                                color = SlockColor.TextMutedGray
                            )
                            SlockBrutalTextField(
                                label = "Channel name",
                                value = confirmationName,
                                placeholder = channelName,
                                onValueChange = { confirmationName = it },
                                required = true,
                                fieldMinHeight = 42.dp
                            )
                            if (confirmationName.isNotBlank() && !archiveConfirmationMatches) {
                                Text(
                                    text = "Channel name does not match.",
                                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                                    color = SlockColor.AccentRed
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isDelete = activeDanger == ThreadSettingsDangerAction.Delete
                    SlockModalActionButton(
                        label = if (isDelete) "Cancel" else "Back",
                        icon = if (isDelete) SlockLucideIconName.X else SlockLucideIconName.ArrowLeft,
                        onClick = { dangerAction = null }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val enabled =
                        activeDanger == ThreadSettingsDangerAction.Visibility ||
                            activeDanger == ThreadSettingsDangerAction.Delete ||
                            archiveConfirmationMatches
                    SlockModalActionButton(
                        label = activeDanger.buttonLabel,
                        icon = activeDanger.icon,
                        color = if (enabled) activeDanger.color else SlockColor.SurfaceCream,
                        enabled = enabled,
                        onClick = {
                            if (enabled) {
                                onOpenAction(activeDanger.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadDeleteChannelWarning(channelName: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.AccentRed.copy(alpha = 0.22f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            name = SlockLucideIconName.TriangleAlert,
            modifier = Modifier.size(26.dp),
            color = "#FF000000"
        )
        Text(
            text = "Are you sure you want to delete \"$channelName\"?\nAll messages will be lost.",
            style = SlockType.Body,
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ThreadModalEmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = detail,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun ThreadStateMessage(title: String, detail: String?) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun ThreadStateActionMessage(
    title: String,
    detail: String?,
    actionLabel: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
            ThreadHeaderAction(label = actionLabel, onClick = onClick)
        }
    }
}
