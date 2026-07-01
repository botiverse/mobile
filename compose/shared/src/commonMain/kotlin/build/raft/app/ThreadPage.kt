package build.raft.app

import build.raft.app.components.SlockAnchoredMetaChip
import build.raft.app.components.SlockAttachmentPreviewCard
import build.raft.app.components.SlockAttachmentPreviewCardWidth
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalSearchField
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockToolbarChip
import build.raft.app.base.Utils
import build.raft.app.base.bridgeModule
import build.raft.app.data.AttachmentUploadFile
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.parseSharedThreadPickedAttachmentFiles
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.ComposeLocalWriteThrough
import build.raft.app.data.NetworkSlockTasksApi
import build.raft.app.data.NetworkSlockThreadApi
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.ThreadApiResult
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
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeRoutePolicy
import build.raft.app.data.kuiklySharedPreferenceStore
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedMembersIntent
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.data.SharedMembersMemberSuggestion
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.SharedMembersStore
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.PendingMentionAction
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.TaskFilter
import build.raft.app.data.TasksUiState
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isAuthSessionErrorCode
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.isSupportedCommitAction
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.prepareSlockMarkdownFullRendererBodies
import build.raft.app.markdown.preparedSlockMarkdownFullRendererContentOrNull
import build.raft.app.markdown.threadMarkdownTagDisplayLabel
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.isGravatarAvatarWireValue
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
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.model.toGravatarAvatarUrl
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.animation.core.animateFloatAsState
import com.tencent.kuikly.compose.animation.core.tween
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
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.resources.imageResource
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
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.unit.toIntSize
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.module.PerformanceModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
private const val ThreadMessagesMaxBeyondBoundsItemCount = 2
private const val ThreadPerformanceSlowThresholdMs = 4L
private const val ThreadFocusedMessageHighlightDurationMs = 2_000L
private const val ThreadFocusedMessageHighlightFadeMs = 180
private const val ThreadFocusedMessageCenterAttempts = 6
private const val ThreadMentionSuggestionPrewarmDelayMs = 100L
private const val ThreadMarkdownAttachmentPreviewMaxChars = 80_000
internal const val ThreadInitialMarkdownPreparseMessageCount = 20
private const val ThreadAttachmentUploadConcurrencyLimit = 12
private val ThreadMessageBodyTextStyle =
    SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack)

private data class ThreadMembersProjection(
    val membersCount: Int,
    val members: List<KuiklyThreadMemberItem>,
    val memberCandidates: List<KuiklyThreadMemberItem>,
    val canAddMembers: Boolean
)

private data class ThreadShareCaptureRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val path: String = "",
    val frame: Boolean = true
)

private data class ThreadSharePreviewImage(
    val uri: String,
    val path: String,
    val width: Int,
    val height: Int
)

internal fun shouldLogThreadPerformance(durationMs: Long): Boolean =
    DEBUG_THREAD_VIRTUAL_RENDER_LOG || durationMs >= ThreadPerformanceSlowThresholdMs

internal fun KuiklyThreadScreenPayload.shouldRenderThreadEarlyLoadingShell(): Boolean =
    shouldRenderThreadEarlyLoadingShell(toThreadTimelineSlice())

internal fun KuiklyThreadScreenPayload.shouldRenderThreadEarlyLoadingShell(
    timelineSlice: ThreadTimelineSlice
): Boolean =
    (backgroundRefreshing && timelineSlice.messages.isEmpty() && timelineSlice.originMessage == null && originPreview.isNullOrBlank()) ||
        (
            status == "loading" &&
                timelineSlice.messages.isEmpty() &&
                timelineSlice.originMessage == null &&
                originPreview.isNullOrBlank()
        )

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
        instanceId: String,
        timelineSlice: ThreadTimelineSlice,
        lazyItemCount: Int,
        stats: ThreadLazyWindowStats
    ): String =
        "thread_first_viewport_probe result=$result sessionMarker=$sessionMarker " +
            "threadId=${timelineSlice.sourceThreadId.orEmpty()} instanceId=$instanceId " +
            "originStatus=${timelineSlice.originStatus} hasOrigin=${timelineSlice.originMessage != null} " +
            "messagesTotal=${timelineSlice.messages.size} totalItems=$lazyItemCount " +
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
private val ThreadComposerBottomBasePadding = 12.dp
private val ThreadComposerBottomSafeAreaLift = 2.dp
private val ThreadHeaderActionSize = 30.dp
private val ThreadHeaderActionShadowOffset = 2.dp
private val ThreadHeaderActionIconSize = 15.dp
private val ThreadHeaderActionLabelMinWidth = 46.dp
private val ThreadHeaderChannelTileSize = 34.dp
private val ThreadHeaderChannelIconSize = 16.dp

private object ThreadReactionGlyphDiagnostics {
    private val loggedKeys = mutableSetOf<String>()

    fun logOnce(path: String, emoji: String, detail: String) {
        val key = "$path:$emoji:$detail"
        if (loggedKeys.add(key)) {
            Utils.logToNative("thread_reaction_glyph path=$path emoji=$emoji $detail")
        }
    }
}

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
    if (threadHeaderIdentityLikeTitle(name)) return null
    return "#$name"
}

internal fun existingThreadChannelHeaderSubtitle(subtitle: String?): String? =
    subtitle?.trim()?.takeIf { it.startsWith("#") }?.let(::threadChannelHeaderSubtitle)

internal fun threadHeaderDisplayTitle(
    title: String?,
    surfaceKind: String = KuiklyThreadSurfaceKind.Channel
): String =
    threadHeaderSafeTitleOrNull(title) ?: threadHeaderFallbackTitle(surfaceKind)

internal enum class StandaloneThreadAuthRequiredAction {
    PreserveSession,
    OpenLogin
}

internal fun standaloneThreadAuthRequiredAction(
    session: StoredAuthSession?
): StandaloneThreadAuthRequiredAction =
    if (session == null) {
        StandaloneThreadAuthRequiredAction.OpenLogin
    } else {
        StandaloneThreadAuthRequiredAction.PreserveSession
    }

@Page(THREAD_PAGE, supportInLocal = true)
internal class ComposeThreadPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadPageScreen() }
    }
}

@Serializable
internal data class KuiklyThreadScreenPayload(
    val instanceId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val status: String = "loading",
    val backgroundRefreshing: Boolean = false,
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
    val channelKind: String = "channel",
    val sourceThreadId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val parentSurfaceKind: String? = null,
    val parentChannelKind: String? = null,
    val parentNavigationHint: String? = null,
    val memberScopedMentions: Boolean = false,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val showModeTabs: Boolean = true,
    val showReplyActions: Boolean = true,
    val alignOwnMessagesRight: Boolean = false,
    val compactConsecutiveMessages: Boolean = false,
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
    val dmPeerMemberId: String? = null,
    val dmPeerName: String? = null,
    val dmPeerAvatarUrl: String? = null,
    val dmPeerAvatarLabel: String? = null,
    val dmPeerGravatarUrl: String? = null
)

internal fun KuiklyThreadScreenPayload.joinChannelTargetId(): ChannelId? =
    (parentChannelId?.takeIf { it.isNotBlank() }
        ?: sourceThreadId?.takeIf { it.isNotBlank() }
        ?: streamChannelId?.takeIf { it.isNotBlank() })
        ?.let(::ChannelId)

internal fun KuiklyThreadScreenPayload.threadMessagePermalink(
    message: KuiklyThreadMessageItem,
    serverSlug: String?,
    webBaseUrl: String? = null
): String? {
    val cleanServerSlug = serverSlug?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val cleanMessageId = message.id.trim().takeIf { it.isNotBlank() } ?: return null
    val replyParentMessageId =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            originParentMessageId?.takeIf { it.isNotBlank() }
                ?: parentMessageId?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    val channelForLink =
        if (replyParentMessageId != null) {
            parentChannelId?.takeIf { it.isNotBlank() }
        } else {
            sourceThreadId?.takeIf { it.isNotBlank() }
                ?: streamChannelId?.takeIf { it.isNotBlank() }
                ?: parentChannelId?.takeIf { it.isNotBlank() }
        } ?: return null
    val routeKind =
        if (
            (replyParentMessageId != null && parentChannelKind.isThreadPermalinkDmKind()) ||
            (replyParentMessageId == null && (surfaceKind == KuiklyThreadSurfaceKind.DirectMessage || channelKind.isThreadPermalinkDmKind()))
        ) {
            "dm"
        } else {
            "channel"
        }
    val params =
        if (replyParentMessageId != null) {
            "msg=$cleanMessageId&thread=$channelForLink%3A$replyParentMessageId"
        } else {
            "msg=$cleanMessageId"
        }
    return "${sharedSettingsResolvedWebBaseUrl(webBaseUrl)}/s/$cleanServerSlug/$routeKind/$channelForLink?$params"
}

private fun String?.isThreadPermalinkDmKind(): Boolean =
    when (this?.trim()?.lowercase()) {
        "dm", "direct_message", "direct-message", "directmessage" -> true
        else -> false
    }

internal fun KuiklyThreadScreenPayload.consumeComposerSeedForSend(
    sentBody: String,
    sentAttachmentIds: Set<String>
): KuiklyThreadScreenPayload {
    val sentBodyTrimmed = sentBody.trim()
    val nextSeedText =
        if (composer.seedText.trim() == sentBodyTrimmed) {
            ""
        } else {
            composer.seedText
        }
    val nextPendingAttachments =
        if (sentAttachmentIds.isEmpty()) {
            composer.pendingAttachments
        } else {
            composer.pendingAttachments.filterNot { attachment -> attachment.id in sentAttachmentIds }
        }
    if (nextSeedText == composer.seedText && nextPendingAttachments == composer.pendingAttachments) {
        return this
    }
    return copy(
        composer =
            composer.copy(
                seedText = nextSeedText,
                seedAsTask = if (nextSeedText.isBlank()) false else composer.seedAsTask,
                syncNonce = composer.syncNonce + 1,
                pendingAttachments = nextPendingAttachments
            )
    )
}

internal fun KuiklyThreadScreenPayload.toThreadMemberDetailPageData(
    selectedMemberId: String,
    selectedMemberKind: MemberKind? = null,
    sourceServerId: String? = null
): JSONObject? {
    val selected = selectedMemberId.takeIf { it.isNotBlank() } ?: return null
    return JSONObject().apply {
        put("pageName", MEMBER_DETAIL_PAGE)
        put("instanceId", "thread-member-detail-${instanceId.ifBlank { sourceThreadId.orEmpty() }}-$selected")
        put("memberId", selected)
        selectedMemberKind?.let { put("memberKind", it.toThreadMemberDetailKindWireValue()) }
        sourceServerId?.trim()?.takeIf { it.isNotBlank() }?.let { put("sourceServerId", it) }
        put("localOffsetMinutes", localOffsetMinutes)
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeFormat)
    }
}

private fun MemberKind.toThreadMemberDetailKindWireValue(): String =
    when (this) {
        MemberKind.Agent -> "agent"
        MemberKind.Human -> "human"
        MemberKind.System -> "system"
    }

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
        handle = authorHandle.ifBlank { authorName.ifBlank { authorId } },
        kind = threadAuthorKind(),
        gravatarHash = gravatarHash,
        avatar = avatarWireValue?.takeIf { it.isNotBlank() }?.let(AvatarReference::fromWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.ifBlank { authorId }.take(1).uppercase() },
        agentStatus = AgentStatus.fromWireValue(authorAgentStatus)
    )

private fun KuiklyThreadMemberItem.threadMemberKind(): MemberKind =
    if (kind.equals("agent", ignoreCase = true)) MemberKind.Agent else MemberKind.Human

private fun KuiklyThreadMessageItem.threadAuthorKind(): MemberKind =
    if (authorKind.equals("agent", ignoreCase = true)) MemberKind.Agent else MemberKind.Human

private fun MemberProfile.toThreadCandidateAgentDetailSnapshot(): AgentDetailSnapshot? {
    if (kind != MemberKind.Agent) return null
    val name = handle.ifBlank { displayName.ifBlank { id.value } }
    val label = displayName.ifBlank { name }
    val avatarWireValue = avatar?.wireValue ?: avatarUrl
    return AgentDetailSnapshot(
        agentId = id,
        summary =
            AgentDetailSummary(
                id = id.value,
                name = name,
                displayName = label,
                status = agentStatus?.label,
                runtime = roleDescription,
                avatarUrl = avatarWireValue
            ),
        directMessages = emptyList(),
        reminders = emptyList(),
        activity = emptyList(),
        skills = AgentSkillsSnapshot(agentId = id)
    )
}

internal fun KuiklyThreadScreenPayload.replyThreadHeaderSubtitle(): String? =
    threadChannelHeaderSubtitle(title)

@Serializable
internal data class KuiklyThreadMessageItem(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String = "",
    val authorKind: String = "",
    val isOwn: Boolean = false,
    val avatarLabel: String = "",
    val avatarWireValue: String? = null,
    val gravatarHash: String? = null,
    val senderMembershipStatus: String? = null,
    val authorDeletedAt: String? = null,
    val authorAgentStatus: String? = null,
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
    val isThreadFollowed: Boolean = false,
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
    val pendingSendErrorCode: String? = null,
    val pendingSendErrorMessage: String? = null
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

internal fun threadComposerMessageBodyForSend(draftText: String, attachmentCount: Int): String {
    val cleanDraft = draftText.trim()
    if (cleanDraft.isNotBlank() || attachmentCount <= 0) return cleanDraft
    val suffix = if (attachmentCount == 1) "attachment" else "attachments"
    return "[$attachmentCount $suffix]"
}

internal fun KuiklyThreadMessageItem.hasLocalSendState(): Boolean =
    deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) ||
        deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) ||
        pendingSendContext != null

private fun KuiklyThreadMessageItem.canRetryLocalSend(): Boolean =
    isFailedLocalSend()

private fun KuiklyThreadMessageItem.isFailedLocalSend(): Boolean =
    deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) && pendingSendContext != null

private fun KuiklyThreadMessageItem.isSendingLocalSend(): Boolean =
    deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) && pendingSendContext != null

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
        authorHandle = user?.name ?: user?.email ?: authorName,
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
                    sizeLabel = attachment.sizeLabel,
                    thumbnailUrl = attachment.previewUri
                )
            },
        deliveryState = ThreadMessageDeliverySending,
        pendingSendContext = context,
        pendingSendErrorCode = null,
        pendingSendErrorMessage = null
    )
}

private data class ThreadDeferredAttachmentSend(
    val localMessageId: String,
    val context: KuiklyThreadPendingSendContext
)

private data class ThreadLocalSendJob(
    val job: Job,
    val startedFromComposer: Boolean
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
    val selected: Boolean = false,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList()
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

private data class ThreadMarkdownTaskCandidateModalState(
    val taskNumber: Int,
    val candidates: List<TaskSummary>
)

private data class ThreadMarkdownMentionOpenTarget(
    val memberId: String,
    val memberKind: MemberKind,
    val sourceServerId: String? = null
)

internal data class ThreadImageAttachmentGroup(
    val images: List<KuiklyThreadAttachmentItem>,
    val currentIndex: Int
)

internal data class ThreadAttachmentCommentTarget(
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
    val messageId: String? = null,
    val routeKind: String = KuiklyThreadSurfaceKind.Channel,
    val threadParentMessageId: String? = null
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
    val canRemove: Boolean = true,
    val presence: Presence? = null,
    val agentStatus: AgentStatus? = null
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

private fun String.normalizedThreadMentionLookupKey(): String =
    trim().removePrefix("@").trim().lowercase()

private fun KuiklyThreadMemberItem.matchesThreadMentionLookupKey(key: String): Boolean =
    id.equals(key, ignoreCase = true) ||
        handle.removePrefix("@").equals(key, ignoreCase = true) ||
        displayName.equals(key, ignoreCase = true)

private fun MemberProfile.matchesThreadMentionLookupKey(key: String): Boolean =
    id.value.equals(key, ignoreCase = true) ||
        handle.removePrefix("@").equals(key, ignoreCase = true) ||
        displayName.equals(key, ignoreCase = true)

private fun KuiklyThreadScreenPayload.findMarkdownMentionMember(tag: ThreadMarkdownTag): KuiklyThreadMemberItem? {
    val key = tag.label.normalizedThreadMentionLookupKey()
    if (key.isBlank()) return null
    return members.firstOrNull { member -> member.matchesThreadMentionLookupKey(key) }
}

private fun KuiklyThreadScreenPayload.resolveMarkdownMentionOpenTarget(
    label: String,
    serverMembers: List<MemberProfile>
): ThreadMarkdownMentionOpenTarget? {
    val key = label.normalizedThreadMentionLookupKey()
    if (key.isBlank()) return null
    findMarkdownMentionMember(ThreadMarkdownTag(label, ThreadMarkdownTagKind.UserMention))?.let { member ->
        return ThreadMarkdownMentionOpenTarget(member.id, member.threadMemberKind(), member.sourceServerId)
    }
    val matches =
        serverMembers
            .filter { member -> member.matchesThreadMentionLookupKey(key) }
            .distinctBy { member -> member.id.value.trim() }
    val match = matches.singleOrNull() ?: return null
    return ThreadMarkdownMentionOpenTarget(match.id.value, match.kind, match.serverId?.value)
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

private fun KuiklyThreadScreenPayload.toKnownMarkdownMentionNameSet(): Set<String> =
    buildSet {
        members.forEach { member -> addKnownThreadMentionValues(member) }
        memberCandidates.forEach { member -> addKnownThreadMentionValues(member) }
        originMessage?.let { message -> addKnownThreadMentionValues(message) }
        messages.forEach { message -> addKnownThreadMentionValues(message) }
    }

private fun MutableSet<String>.addKnownThreadMentionValues(member: KuiklyThreadMemberItem) {
    addKnownThreadMentionValue(member.id)
    addKnownThreadMentionValue(member.handle)
    addKnownThreadMentionValue(member.displayName)
}

private fun MutableSet<String>.addKnownThreadMentionValues(message: KuiklyThreadMessageItem) {
    addKnownThreadMentionValue(message.authorId)
    addKnownThreadMentionValue(message.authorHandle)
    addKnownThreadMentionValue(message.authorName)
}

private fun MutableSet<String>.addKnownThreadMentionValue(value: String?) {
    val key = value?.normalizedThreadMentionLookupKey().orEmpty()
    if (key.isNotBlank()) add(key)
}

private fun ThreadMarkdownTag.isKnownThreadMarkdownTag(
    knownChannelNames: Set<String>,
    knownMentionNames: Set<String>
): Boolean =
    when (kind) {
        ThreadMarkdownTagKind.Task -> true
        ThreadMarkdownTagKind.UserMention -> label.normalizedThreadMentionLookupKey() in knownMentionNames
        ThreadMarkdownTagKind.Channel,
        ThreadMarkdownTagKind.ChannelThread -> {
            val key =
                (channelName ?: label.removePrefix("#").substringBefore(":"))
                    .trim()
                    .lowercase()
            key.isNotBlank() && key in knownChannelNames
        }
    }

private fun defaultThreadMarkdownTagIsValid(tag: ThreadMarkdownTag): Boolean =
    tag.kind == ThreadMarkdownTagKind.Task

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

internal data class ThreadTimelineRenderConfig(
    val backgroundRefreshing: Boolean,
    val errorCode: String?,
    val originPreview: String?,
    val originErrorCode: String?,
    val timeContext: SlockTimeContext,
    val members: List<KuiklyThreadMemberItem>
)

internal fun KuiklyThreadScreenPayload.toThreadTimelineRenderConfig(): ThreadTimelineRenderConfig =
    ThreadTimelineRenderConfig(
        backgroundRefreshing = backgroundRefreshing,
        errorCode = errorCode,
        originPreview = originPreview,
        originErrorCode = originErrorCode,
        timeContext = toSlockTimeContext(),
        members = members
    )

internal fun ThreadTimelineRenderConfig.formatTimestampLabel(value: String): String =
    formatSlockTimestampLabel(value, timeContext)

internal fun ThreadTimelineRenderConfig.formatCompactTimestampLabel(value: String): String {
    val epochMillis =
        parseSlockTimestampEpochMillisOrNull(value, timeContext.localOffsetMinutes)
            ?: return formatTimestampLabel(value)
    val localEpochDay = threadLocalEpochDay(epochMillis, timeContext.localOffsetMinutes)
    return formatSlockTimestampEpochMillis(
        epochMillis = epochMillis,
        localOffsetMinutes = timeContext.localOffsetMinutes,
        todayEpochDay = localEpochDay,
        timeFormat = timeContext.timeFormat
    )
}

internal fun ThreadTimelineRenderConfig.resolveTaskAssigneeDisplayName(
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

private fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V) {
    if (!containsKey(key)) {
        put(key, value)
    }
}

internal fun KuiklyThreadScreenPayload.supportsChannelTasks(): Boolean =
    composer.showAsTaskToggle || showTaskOpenActions || showTaskStatusActions

private sealed interface MarkdownTaskNumberOpenResult {
    data class Found(val task: TaskSummary, val payload: KuiklyThreadScreenPayload) : MarkdownTaskNumberOpenResult
    data class Ambiguous(val taskNumber: Int, val candidates: List<TaskSummary>) : MarkdownTaskNumberOpenResult
    data object NotFound : MarkdownTaskNumberOpenResult
    data object AuthRequired : MarkdownTaskNumberOpenResult
    data object Failed : MarkdownTaskNumberOpenResult
}

private suspend fun openMarkdownTaskNumberPayload(
    serverId: ServerId,
    taskNumber: Int,
    currentPayload: KuiklyThreadScreenPayload,
    currentTaskChannelId: ChannelId?,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?
): MarkdownTaskNumberOpenResult {
    val snapshot =
        when (
            val result =
                tasksDataSource.loadTasks(
                    serverId = serverId,
                    filter = TaskFilter(),
                    priority = DataRequestPriority.Foreground
                )
        ) {
            is DataResult.Content -> result.data
            is DataResult.Empty -> return MarkdownTaskNumberOpenResult.NotFound
            is DataResult.Error -> return MarkdownTaskNumberOpenResult.Failed
            is DataResult.NoPermission -> return MarkdownTaskNumberOpenResult.AuthRequired
        }
    tasksDataStore?.publishSnapshot(serverId.value, SharedTasksScope.Server, snapshot)
    val matches = snapshot.tasks.filter { task -> task.taskNumber == taskNumber }
    val selected =
        when (matches.size) {
            0 -> return MarkdownTaskNumberOpenResult.NotFound
            1 -> matches.single()
            else -> {
                val currentChannelMatches =
                    currentTaskChannelId?.let { channelId ->
                        matches.filter { task -> task.channelId == channelId }
                    }.orEmpty()
                if (currentChannelMatches.size == 1) {
                    currentChannelMatches.single()
                } else {
                    return MarkdownTaskNumberOpenResult.Ambiguous(
                        taskNumber = taskNumber,
                        candidates = matches.threadMarkdownTaskCandidates(currentTaskChannelId)
                    )
                }
            }
        }
    val payload =
        currentPayload.markdownTaskNumberLaunchPayload(selected)
            ?: return MarkdownTaskNumberOpenResult.Failed
    tasksDataStore?.upsertTask(serverId.value, selected)
    return MarkdownTaskNumberOpenResult.Found(selected, payload)
}

private fun List<TaskSummary>.threadMarkdownTaskCandidates(currentTaskChannelId: ChannelId?): List<TaskSummary> =
    sortedWith(
        compareByDescending<TaskSummary> { task ->
            currentTaskChannelId != null && task.channelId == currentTaskChannelId
        }
            .thenBy { task -> task.channelName.orEmpty().lowercase() }
            .thenBy { task -> task.title.lowercase() }
            .thenBy { task -> task.id.value }
    )

internal fun KuiklyThreadScreenPayload.markdownTaskNumberLaunchPayload(task: TaskSummary): KuiklyThreadScreenPayload? {
    val parentChannelId = task.channelId?.value?.takeIf { it.isNotBlank() } ?: return null
    val parentMessageId =
        task.parentMessageId?.value?.takeIf { it.isNotBlank() }
            ?: task.messageId?.value?.takeIf { it.isNotBlank() }
            ?: return null
    val threadChannelId = task.threadId?.value?.takeIf { it.isNotBlank() }
    val instanceId =
        threadChannelId?.let { "shared-shell-thread-$it" }
            ?: "shared-shell-thread-$parentChannelId-$parentMessageId"
    return WireThreadLaunchPayload(
        instanceId = instanceId,
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        threadId = threadChannelId,
        sourceThreadId = threadChannelId,
        streamChannelId = threadChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
        originParentMessageId = parentMessageId,
        selectedTab = KuiklyThreadTab.Chat.wireValue,
        navigationLabel = "Back",
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    ).toInitialPayload(instanceId)
}

internal fun KuiklyThreadScreenPayload.canConvertMessagesToTasks(): Boolean =
    showTaskStatusActions &&
        !surfaceKind.equals(KuiklyThreadSurfaceKind.ReplyThread, ignoreCase = true) &&
        parentMessageId.isNullOrBlank() &&
        originParentMessageId.isNullOrBlank()

internal fun KuiklyThreadMessageItem.canConvertToTask(): Boolean =
    !isSystemMessage() &&
        taskId.isNullOrBlank() &&
        taskNumber == null &&
        !hasLocalSendState()

internal fun shouldStartThreadTasksJoin(
    payload: KuiklyThreadScreenPayload,
    startedThreadId: String?,
    taskChannelId: ChannelId?
): Boolean =
    taskChannelId != null &&
        payload.status == "content" &&
        payload.supportsChannelTasks() &&
        startedThreadId != taskChannelId.value &&
        payload.resolvedTaskLookupChannelId()?.let { it == taskChannelId.value } == true

internal fun KuiklyThreadScreenPayload.resolvedAuxiliaryThreadId(): String? {
    val timelineChannelId = resolveThreadRouteIdentity().timelineChannelId ?: return null
    if (!isReplyThreadWithExplicitParentPair()) return timelineChannelId
    if (status == "loading") return null
    return timelineChannelId
}

internal fun KuiklyThreadScreenPayload.resolvedTaskLookupChannelId(): String? {
    val timelineChannelId = resolvedAuxiliaryThreadId() ?: return null
    if (!isReplyThreadWithExplicitParentPair()) return timelineChannelId
    return parentChannelId.nonBlank() ?: timelineChannelId
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
    threadRenderDiagnosticsSource(toThreadTimelineSlice())

private fun KuiklyThreadScreenPayload.threadRenderDiagnosticsSource(timelineSlice: ThreadTimelineSlice): String =
    when {
        status == "loading" -> "loading"
        status == "content" && timelineSlice.messages.isNotEmpty() -> "content"
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
    bridge: build.raft.app.base.BridgeModule,
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

internal fun shouldShowThreadComposerForReadyContent(
    joined: Boolean,
    showComposer: Boolean,
    selectedTab: String?
): Boolean =
    shouldShowThreadComposerForTab(joined, showComposer, selectedTab)

internal fun KuiklyThreadScreenPayload.hasRenderableContentForComposer(): Boolean =
    hasRenderableContentForComposer(toThreadTimelineSlice())

internal fun KuiklyThreadScreenPayload.hasRenderableContentForComposer(timelineSlice: ThreadTimelineSlice): Boolean =
    !shouldRenderThreadEarlyLoadingShell(timelineSlice) &&
        status != "no_permission" &&
        status != "error"

internal fun shouldDeliverToActiveThreadRealtimeSignal(event: RealtimeEvent): Boolean =
    RealtimeRoutePolicy.shouldForwardToActiveThread(event) || event is Heartbeat

private fun RealtimeEvent.isTaskProjectionEvent(): Boolean =
    this is TaskCreated || this is TaskUpdated || this is TaskDeleted

private val KuiklyThreadTab.icon: SlockLucideIconName
    get() =
        when (this) {
            KuiklyThreadTab.Chat -> SlockLucideIconName.MessageSquare
            KuiklyThreadTab.Tasks -> SlockLucideIconName.ListTodo
            KuiklyThreadTab.Files -> SlockLucideIconName.Paperclip
        }

private fun KuiklyThreadTab.toSlockTabSpec(): SlockTabSpec<KuiklyThreadTab> =
    SlockTabSpec(
        key = this,
        label = label,
        iconName = icon
    )

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
    val payloadTrace = remember(payloadText, instanceId) {
        parseThreadScreenPayloadWithTrace(
            payload = payloadText,
            instanceId = instanceId
        )
    }
    val payload = payloadTrace.payload
    LaunchedEffect(payloadText, instanceId) {
        bridge.log(
            "thread_launch_payload_parse source=pageData result=${payloadTrace.result} " +
                "responseBytes=${payloadTrace.responseBytes} durationMs=${payloadTrace.durationMs} dispatcher=caller"
        )
    }
    val sharedStores = remember { SlockSharedStoreRegistry.get(pager, bridge) }
    val authStore = sharedStores.authStore
    val threadPageCoroutineScope = rememberCoroutineScope()
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
    val networkClient = sharedStores.networkClient
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
        build.raft.app.data.SharedCreateAgentCoordinator(
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
        remember(authStore, channelRepository, routeScope) {
            SharedMembersStore(
                authStore = authStore,
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
        remember(
            networkClient,
            authStore,
            localWriteThrough,
            memberRepository,
            sharedStores.serverMembersStore,
            channelRepository,
            createAgentCoordinator
        ) {
            SharedThreadDataSource(
                networkApi = NetworkSlockThreadApi(networkClient),
                sessionStore = authStore,
                localWriteThrough = localWriteThrough,
                memberRepository = memberRepository,
                serverMembersStore = sharedStores.serverMembersStore,
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
    fun openStandaloneThreadPayload(nextPayload: KuiklyThreadScreenPayload) {
        val launchPayload = nextPayload.toWireThreadLaunchPayload()
        if (!launchPayload.hasRouteIdentity()) {
            bridge.toast("Unable to open thread")
            bridge.logWarn("thread_open_skip reason=missing_route_identity instanceId=${nextPayload.instanceId}")
            return
        }
        val openPage = {
            routerModule.openPage(THREAD_PAGE, launchPayload.toThreadPageData())
        }
        if (nextPayload.sourceThreadId?.isNotBlank() == true &&
            (nextPayload.originMessage != null || nextPayload.messages.isNotEmpty())
        ) {
            dataSource.cachePayload(nextPayload)
            openPage()
            return
        }
        routerModule.openPage(THREAD_PAGE, launchPayload.toThreadPageData())
    }
    SharedThreadRouteContent(
        initialPayload = payload,
        bridge = bridge,
        dataSource = dataSource,
        authStore = authStore,
        tasksDataSource = tasksDataSource,
        tasksDataStore = sharedStores.tasksDataStore,
        computersStore = computersStore,
        membersStore = membersStore,
        serverMembersStore = sharedStores.serverMembersStore,
        conversationIdentityProvider = sharedStores.conversationIdentityProvider,
        composerDraftPersistSlice = sharedStores.composerDraftPersistSlice,
        onBack = {
            routerModule.closePage()
        },
        onOpenThreadPayload = ::openStandaloneThreadPayload,
        onReplaceThreadPayload = {},
        onOpenParentThread = {
            if (payload.parentNavigationHint.threadParentNavigationHintOrNull() == ThreadParentNavigationPrevious) {
                bridge.log(
                    "thread_parent_navigation action=close_previous " +
                        "threadId=${payload.sourceThreadId.orEmpty()} parentChannelId=${payload.parentChannelId.orEmpty()}"
                )
                routerModule.closePage()
                true
            } else {
                false
            }
        },
        onAuthRequired = { code ->
            bridge.logWarn("thread_auth_required code=$code route=standalone instanceId=${payload.instanceId}")
            threadPageCoroutineScope.launch {
                val session =
                    runCatchingPreservingCancellation { authStore.readSession() }
                        .getOrNull()
                when (standaloneThreadAuthRequiredAction(session)) {
                    StandaloneThreadAuthRequiredAction.PreserveSession -> {
                        bridge.logWarn(
                            "thread_auth_required_session_preserved code=$code " +
                                "route=standalone instanceId=${payload.instanceId} " +
                                "serverId=${session?.currentServerId.orEmpty()}"
                        )
                        bridge.toast("Unable to load thread. Try again.")
                    }
                    StandaloneThreadAuthRequiredAction.OpenLogin -> {
                        bridge.toast("Session expired")
                        routerModule.openPage(
                            LOGIN_PAGE,
                            JSONObject().apply { put("pageName", LOGIN_PAGE) }
                        )
                    }
                }
            }
        },
        onOpenMemberProfile = { currentPayload, timelineSlice, memberId, selectedMemberKind, sourceServerId ->
            val authorProfile =
                timelineSlice.findThreadAuthorProfile(memberId)
                    ?: currentPayload.findThreadAuthorProfile(memberId)
            val selectedSourceServerId =
                sourceServerId
                    ?: authorProfile?.serverId?.value
                    ?: currentPayload.members.firstOrNull { it.id == memberId }?.sourceServerId
            val pageData = currentPayload.toThreadMemberDetailPageData(
                selectedMemberId = memberId,
                selectedMemberKind = selectedMemberKind ?: authorProfile?.kind,
                sourceServerId = selectedSourceServerId
            )
            if (pageData == null) {
                bridge.toast("Unable to open profile")
                bridge.logWarn(
                    "thread_member_profile_skip reason=missing_member memberId=$memberId " +
                    "instanceId=${currentPayload.instanceId}"
                )
            } else {
                authorProfile
                    ?.toThreadCandidateAgentDetailSnapshot()
                    ?.let { candidate ->
                        val agentDetailStore = SlockSharedStoreRegistry.get(pager, bridge).agentDetailStore
                        agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(candidate.agentId))
                        agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetail(candidate.agentId, candidate))
                    }
                routerModule.openPage(MEMBER_DETAIL_PAGE, pageData)
            }
        }
    )
}

@Composable
internal fun ThreadMessageVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val visualFixture = params.optString("visualFixture")
    val menuVariant = params.optString("menuVariant")
    val instanceId = params.optString("instanceId")
    val payload =
        remember(visualFixture, instanceId) {
            threadVisualFixture(fixture = visualFixture.ifBlank { THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT }, instanceId = instanceId)
        }
    val highlightedMentions =
        remember(visualFixture) {
            if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT) setOf("artin") else emptySet()
        }
    if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_SELECTION) {
        val messages = payload.toThreadTimelineSlice().messages
        Column(modifier = Modifier.width(390.dp).background(SlockColor.SurfaceWhite).padding(SlockSpacing.Md)) {
            messages.forEach { selectedMessage ->
                ThreadMessageCard(
                    message = selectedMessage,
                    highlightedMentions = highlightedMentions,
                    shareSelectionActive = true,
                    selectedForShare = true,
                    onToggleShareSelection = {},
                    alignOwnMessageRight = payload.alignOwnMessagesRight && selectedMessage.isOwn,
                    showReplyAction = payload.showReplyActions,
                    taskOpenEnabled = payload.showTaskOpenActions,
                    taskStatusActionsEnabled = payload.showTaskStatusActions,
                    timestampLabel = payload.formatTimestampLabel(selectedMessage.timestampLabel),
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
            Spacer(Modifier.height(SlockSpacing.Sm))
            ThreadShareSelectionToolbar(
                selectedCount = messages.size,
                onCancel = {},
                onShare = {},
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
    val baseMessage = payload.toThreadTimelineSlice().messages.firstOrNull()
    val message =
        remember(baseMessage, menuVariant) {
            if (baseMessage != null && menuVariant == "task" && baseMessage.taskId == null) {
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
    val menuActions =
        remember(message, menuVariant) {
            if (message == null || menuVariant.isBlank()) {
                emptyList()
            } else {
                threadMessageMenuActions(
                    message = message,
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
    Column(modifier = Modifier.width(390.dp).background(SlockColor.SurfaceWhite).padding(SlockSpacing.Md)) {
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
                resolveMarkdownTagLabel =
                    remember(payload.members) {
                        { tag: ThreadMarkdownTag -> payload.resolveMarkdownTagDisplayLabel(tag) }
                    },
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
            if (menuActions.isNotEmpty()) {
                Spacer(Modifier.height(SlockSpacing.Sm))
                ThreadMessageMenuChrome(
                    actions = menuActions,
                    showReactionActions = true,
                    onReact = {},
                    modifier = Modifier
                )
            }
        }
    }
}

@Composable
internal fun ThreadHeaderVisualScreen() {
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
            onOpenMemberProfile = { _, _, _ -> },
            onShowMembers = {},
            onShowSettings = {}
        )
    }
}

@Composable
internal fun ThreadReactionAtlasVisualScreen() {
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        ThreadReactionVisualSection("Atlas sprites") {
            ThreadReactionAtlasImagePreview()
        }
        ThreadReactionVisualSection("Quick row") {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF7F4EA), RoundedCornerShape(SlockRadius.Sm))
                        .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Sm))
                        .padding(vertical = SlockSpacing.Xs)
            ) {
                ThreadMessageReactionRow(onReact = {})
            }
        }
        ThreadReactionVisualSection("Chips") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                ThreadReactionChip(KuiklyThreadReactionItem(emoji = "👍", count = 3, selected = true))
                ThreadReactionChip(KuiklyThreadReactionItem(emoji = "❤️", count = 2, selected = false))
                ThreadReactionChip(KuiklyThreadReactionItem(emoji = "✅", count = 1, selected = true))
            }
        }
        ThreadReactionVisualSection("Fallback") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                ThreadQuickReactionGlyph("☑️", modifier = Modifier.size(22.dp))
                ThreadQuickReactionGlyph("🧪", modifier = Modifier.size(22.dp))
                ThreadReactionChip(KuiklyThreadReactionItem(emoji = "🧪", count = 1, selected = false))
            }
        }
    }
}

@Composable
private fun ThreadReactionVisualSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
        Text(
            text = title,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.58f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
private fun ThreadReactionAtlasImagePreview() {
    Box(
        modifier =
            Modifier
                .width(252.dp)
                .height(36.dp)
                .background(SlockColor.SurfaceCream),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThreadReactionSpriteCatalogV1.reactions.forEach { sprite ->
                ThreadQuickReactionGlyph(sprite.value, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun ChannelMembersPanelVisualScreen() {
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
            onOpenMember = {},
            onRemoveMember = {}
        )
    }
}

@Composable
internal fun ChannelSettingsPanelVisualScreen() {
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
internal fun ChannelAddMemberPanelVisualScreen() {
    val members = remember { channelPanelVisualMembers() }
    val candidates = remember { channelPanelVisualCandidates() }
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
            onOpenMember = {},
            onRemoveMember = {}
        )
        ThreadAddMemberModal(
            members = members,
            candidates = candidates,
            canAddMembers = true,
            addMemberStatus = "idle",
            addMemberErrorCode = null,
            initialSelectedId = "user_designer",
            showSelectedCheckmark = false,
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
            kind = "agent",
            sourceServerLabel = "Botiverse",
            canRemove = false
        ),
        KuiklyThreadMemberItem(
            id = "user_artin",
            displayName = "Artin",
            handle = "artin",
            roleLabel = "Project owner",
            avatarLabel = "A",
            kind = "human",
            sourceServerLabel = "Botiverse",
            canRemove = false
        )
    )

private fun channelPanelVisualCandidates(): List<KuiklyThreadMemberItem> =
    listOf(
        KuiklyThreadMemberItem(
            id = "agent_product_ux",
            displayName = "Product UX Designer",
            handle = "Product-UX-Designer",
            roleLabel = "",
            avatarLabel = "PU",
            kind = "agent",
            agentStatus = AgentStatus.Online
        ),
        KuiklyThreadMemberItem(
            id = "user_designer",
            displayName = "Designer",
            handle = "designer",
            roleLabel = "",
            avatarLabel = "D",
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
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
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
                    gravatarUrl = message.threadAvatarGravatarUrl(),
                    status = null
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
                        message.threadAuthorBadgeLabel()?.let { label ->
                            ThreadMessageAuthorBadge(label)
                        }
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
                                isTagValid = isMarkdownTagValid,
                                resolveTagLabel = resolveMarkdownTagLabel,
                                inlineContentScope = message.id,
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
    bridge: build.raft.app.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    authStore: SlockAuthSessionStore,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore? = null,
    computersStore: SharedComputersStore? = null,
    membersStore: SharedMembersStore? = null,
    serverMembersStore: SharedServerMembersStore? = null,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    onBack: () -> Unit,
    onOpenThreadPayload: (KuiklyThreadScreenPayload) -> Unit = {},
    onReplaceThreadPayload: (KuiklyThreadScreenPayload) -> Unit = onOpenThreadPayload,
    onOpenParentThread: () -> Boolean = { false },
    onAuthRequired: (String) -> Unit = {},
    active: Boolean = true,
    onResolvedAuxiliaryThreadIdChanged: (String?) -> Unit = {},
    onOpenMemberProfile: (KuiklyThreadScreenPayload, ThreadTimelineSlice, String, MemberKind?, String?) -> Unit = { _, _, _, _, _ -> }
) {
    val pager = LocalActivity.current.getPager() as Pager
    val notifyModule = remember(pager) { pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME) }
    val realtimeEvents = remember(pager) { KuiklyRealtimeEvents(pager) }
    val realtimeEventMapper = remember { RealtimeEventMapper() }
    PerformanceSceneEffect(bridge, "kuikly_thread", active = active)
    val initialRouteIdentity = remember(initialPayload) {
        initialPayload.resolveThreadRouteIdentity()
    }
    val threadViewModel: SharedThreadViewModel =
        viewModel(key = "shared-thread-${initialRouteIdentity.instanceKey}") {
            SharedThreadViewModel(
                dataSource = dataSource,
                initialPayload = initialPayload,
                diagnosticsLogger = { bridge.log(it) }
            )
        }
    val payload by remember(threadViewModel) {
        threadViewModel.payloadFlow()
    }.collectAsState(threadViewModel.currentPayload)
    val timelineSlice by remember(threadViewModel) {
        threadViewModel.timelineSliceFlow()
    }.collectAsState(threadViewModel.currentTimelineSlice)
    val replyMetadataByParentId by remember(threadViewModel) {
        threadViewModel.replyMetadataFlow()
    }.collectAsState(threadViewModel.currentReplyMetadataByParentId)
    val highlightedMentions by remember(threadViewModel) {
        threadViewModel.highlightedMentionsFlow()
    }.collectAsState(threadViewModel.currentHighlightedMentions)
    val initialThreadInstanceKey = initialRouteIdentity.instanceKey
    var currentSession by remember(initialThreadInstanceKey) {
        mutableStateOf<StoredAuthSession?>(null)
    }
    var currentServerSlug by remember(initialThreadInstanceKey) {
        mutableStateOf<String?>(null)
    }
    val headerProjectionInput =
        remember(initialPayload) {
            initialPayload.threadHeaderProjectionInput()
        }
    val conversationDirectoryLookupIds = headerProjectionInput.lookupIds
    val emptyConversationDirectoryProjectionFlow =
        remember { MutableStateFlow(emptyMap<String, SharedConversationDirectoryProjection>()) }
    val directoryConversationProjections by remember(
        conversationIdentityProvider,
        currentSession?.currentServerId,
        headerProjectionInput.surfaceKind,
        conversationDirectoryLookupIds
    ) {
        val serverId = currentSession?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        if (
            conversationIdentityProvider != null &&
            serverId != null &&
            conversationDirectoryLookupIds.isNotEmpty()
        ) {
            conversationIdentityProvider
                .projectionFlow(serverId, conversationDirectoryLookupIds)
        } else {
            emptyConversationDirectoryProjectionFlow
        }
    }.collectAsState(emptyMap())
    val directoryConversationProjection =
        remember(headerProjectionInput, conversationDirectoryLookupIds, directoryConversationProjections) {
            conversationDirectoryLookupIds
                .firstNotNullOfOrNull { id ->
                    directoryConversationProjections[id]?.takeIf { projection ->
                        headerProjectionInput.accepts(projection)
                    }
                }
        }
    var lastAcceptedHeaderProjection by remember(headerProjectionInput) {
        mutableStateOf<SharedConversationDirectoryProjection?>(null)
    }
    val headerProjectionSource =
        remember(headerProjectionInput, directoryConversationProjection, lastAcceptedHeaderProjection) {
            headerProjectionInput.stableProjectionSource(
                current = directoryConversationProjection,
                previous = lastAcceptedHeaderProjection
            )
        }
    val stableHeaderProjection =
        remember(headerProjectionInput, directoryConversationProjection, lastAcceptedHeaderProjection) {
            headerProjectionInput.stableProjection(
                current = directoryConversationProjection,
                previous = lastAcceptedHeaderProjection
            )
        }
    LaunchedEffect(headerProjectionInput, directoryConversationProjection) {
        val previous = lastAcceptedHeaderProjection
        val accepted = directoryConversationProjection?.takeIf(headerProjectionInput::accepts)
        if (headerProjectionInput.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) {
            bridge.log(
                "thread_header_dm_diag stage=accept_check " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${previous.headerProjectionDiagnostics()} " +
                    "accepted=${accepted != null}"
            )
        }
        if (accepted != null) {
            if (headerProjectionInput.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) {
                bridge.log(
                    "thread_header_dm_diag stage=accepted_store " +
                        "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                        "stored=${accepted.headerProjectionDiagnostics()}"
                )
            }
            lastAcceptedHeaderProjection = accepted
        }
    }
    val headerDisplayModel =
        remember(headerProjectionInput, stableHeaderProjection) {
            headerProjectionInput.displayModel(stableHeaderProjection)
        }
    val headerProjectionMapDiagnostics =
        remember(directoryConversationProjections) {
            directoryConversationProjections.headerProjectionMapDiagnostics()
        }
    val initialHeaderRouteDiagnostics =
        remember(initialPayload) {
            initialPayload.headerRouteDiagnostics()
        }
    val payloadHeaderRouteDiagnostics =
        remember(payload) {
            payload.headerRouteDiagnostics()
        }
    LaunchedEffect(
        currentSession?.currentServerId,
        headerProjectionInput,
        conversationDirectoryLookupIds,
        headerProjectionMapDiagnostics,
        directoryConversationProjection,
        lastAcceptedHeaderProjection,
        headerProjectionSource,
        stableHeaderProjection,
        headerDisplayModel,
        initialHeaderRouteDiagnostics,
        payloadHeaderRouteDiagnostics
    ) {
        if (headerProjectionInput.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) {
            bridge.log(
                "thread_header_dm_diag stage=render " +
                    "serverId=${currentSession?.currentServerId ?: "null"} " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "initialRoute=$initialHeaderRouteDiagnostics " +
                    "stateRoute=$payloadHeaderRouteDiagnostics " +
                    "projectionMap=$headerProjectionMapDiagnostics " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${lastAcceptedHeaderProjection.headerProjectionDiagnostics()} " +
                    "stableSource=$headerProjectionSource " +
                    "stable=${stableHeaderProjection.headerProjectionDiagnostics()} " +
                    "final=${headerDisplayModel.headerDisplayDiagnostics()}"
            )
        }
    }
    val baseRenderedPayload = remember(payload) {
        payload
            .withSharedShellEventCapabilities()
    }
    val payloadRouteIdentity = remember(payload) {
        payload.resolveThreadRouteIdentity()
    }
    val threadInstanceKey = payloadRouteIdentity.instanceKey
    val threadTimelineKey =
        payloadRouteIdentity.timelineChannelId
            ?: initialRouteIdentity.timelineChannelId
    val resolvedAuxiliaryThreadId = payload.resolvedAuxiliaryThreadId()
    DisposableEffect(
        realtimeEvents,
        payload.sourceThreadId,
        payload.streamChannelId
    ) {
        val activeRoomIds =
            listOfNotNull(
                payload.sourceThreadId,
                payload.streamChannelId
            ).distinct()
        realtimeEvents.joinChannels(activeRoomIds)
        onDispose {
            realtimeEvents.leaveChannels(activeRoomIds)
        }
    }
    LaunchedEffect(resolvedAuxiliaryThreadId, active) {
        if (active) {
            onResolvedAuxiliaryThreadIdChanged(resolvedAuxiliaryThreadId)
        }
    }
    var pendingReactionKeys by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingActionCardIds by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingTaskConvertMessageIds by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingThreadFollowMessageIds by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var textSelectionMessage by remember(threadInstanceKey) { mutableStateOf<KuiklyThreadMessageItem?>(null) }
    var createAgentDialogState by remember(threadInstanceKey) { mutableStateOf<ThreadCreateAgentActionDialogState?>(null) }
    var markdownTaskCandidateModalState by remember(threadInstanceKey) {
        mutableStateOf<ThreadMarkdownTaskCandidateModalState?>(null)
    }
    var createAgentComputerServerId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var createAgentComputerServerResolved by remember(threadInstanceKey) { mutableStateOf(false) }
    var attachmentOpenStates by remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadAttachmentOpenUiState>>(emptyMap())
    }
    var attachmentUploadJobs by remember(threadInstanceKey) {
        mutableStateOf<Map<String, Job>>(emptyMap())
    }
    var localSendJobs by remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadLocalSendJob>>(emptyMap())
    }
    var attachmentUploadFilesByLocalId by remember(threadInstanceKey) {
        mutableStateOf<Map<String, AttachmentUploadFile>>(emptyMap())
    }
    val attachmentUploadSemaphore = remember(threadInstanceKey) {
        Semaphore(ThreadAttachmentUploadConcurrencyLimit)
    }
    var composerImageActionCounter by remember(threadInstanceKey) { mutableStateOf(0) }
    val chatLayoutPreferenceStore =
        remember(pager) {
            KeyValueChatLayoutPreferenceStore(kuiklySharedPreferenceStore(pager))
        }
    fun imageAnnotationEnabled(): Boolean =
        chatLayoutPreferenceStore.readImageAnnotationEnabledSync()
    var attachmentCommentsDialogState by remember(threadInstanceKey) {
        mutableStateOf<ThreadAttachmentCommentsDialogState?>(null)
    }
    var firstRenderLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var firstContentLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var performanceSnapshotLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var startedTasksJoinThreadId by remember { mutableStateOf<String?>(null) }
    var lastTasksJoinResultKey by remember { mutableStateOf<String?>(null) }
    var tasksJoinStartedAt by remember { mutableStateOf<TimeMark?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val currentAttachmentUploadJobs by rememberUpdatedState(attachmentUploadJobs)
    val currentLocalSendJobs by rememberUpdatedState(localSendJobs)

    DisposableEffect(threadInstanceKey) {
        onDispose {
            currentAttachmentUploadJobs.values.forEach { job -> job.cancel() }
            currentLocalSendJobs.values.forEach { sendJob -> sendJob.job.cancel() }
        }
    }

    LaunchedEffect(authStore, initialThreadInstanceKey) {
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
            threadId = initialRouteIdentity.instanceKey,
            metadata =
                mapOf(
                    "status" to initialPayload.status.ifBlank { "unknown" },
                    "source" to initialPayload.threadRenderDiagnosticsSource()
                )
        )
        pendingActionCardIds = emptySet()
        pendingTaskConvertMessageIds = emptySet()
        pendingThreadFollowMessageIds = emptySet()
        createAgentDialogState = null
        attachmentOpenStates = emptyMap()
        attachmentCommentsDialogState = null
        threadViewModel.dispatch(SharedThreadIntent.Start(initialPayload))
    }
    LaunchedEffect(threadInstanceKey, threadTimelineKey, timelineSlice.status, timelineSlice.messages.size) {
        if (firstRenderLoggedThreadId != threadInstanceKey) {
            firstRenderLoggedThreadId = threadInstanceKey
            bridge.performanceMarker(
                phase = "thread_first_render",
                routeName = "kuikly_thread",
                event = "instant",
                threadId = threadTimelineKey ?: threadInstanceKey,
                metadata =
                    mapOf(
                        "status" to timelineSlice.status.ifBlank { "unknown" },
                        "messages" to timelineSlice.messages.size.toString(),
                        "source" to payload.threadRenderDiagnosticsSource(timelineSlice)
                    )
            )
            bridge.log(
                "thread_first_render threadId=${(threadTimelineKey ?: threadInstanceKey).orEmpty()} status=${timelineSlice.status} " +
                    "messages=${timelineSlice.messages.size} source=${payload.threadRenderDiagnosticsSource(timelineSlice)}"
            )
        }
        if (payload.isReadyForThreadPerformanceGate() && firstContentLoggedThreadId != threadInstanceKey) {
            firstContentLoggedThreadId = threadInstanceKey
            bridge.performanceMarker(
                phase = "thread_payload_ready",
                routeName = "kuikly_thread",
                event = "instant",
                threadId = threadTimelineKey ?: threadInstanceKey,
                metadata =
                    mapOf(
                        "status" to timelineSlice.status.ifBlank { "unknown" },
                        "source" to payload.threadRenderDiagnosticsSource(timelineSlice),
                        "messages" to timelineSlice.messages.size.toString()
                    )
            )
            bridge.log(
                "thread_payload_ready_gate threadId=${(threadTimelineKey ?: threadInstanceKey).orEmpty()} " +
                    "status=${timelineSlice.status} messages=${timelineSlice.messages.size} " +
                    "source=${payload.threadRenderDiagnosticsSource(timelineSlice)}"
            )
            if (performanceSnapshotLoggedThreadId != threadInstanceKey) {
                performanceSnapshotLoggedThreadId = threadInstanceKey
                delay(ThreadFirstContentTaskJoinDelayMs)
                val firstFrameReason =
                    if (payload.isRenderableEmptyReplyThread()) {
                        "thread_first_empty_reply"
                    } else {
                        "thread_first_content"
                    }
                requestKuiklyThreadPerformanceSnapshot(
                    bridge = bridge,
                    threadId = (threadTimelineKey ?: threadInstanceKey).orEmpty(),
                    reason = firstFrameReason,
                    status = timelineSlice.status.ifBlank { "unknown" }
                )
            }
        }
    }
    val taskChannelId = payload.resolvedTaskLookupChannelId()?.let(::ChannelId)
    val tasksViewModel: SharedTasksViewModel? =
        taskChannelId?.let { resolvedTaskChannelId ->
            viewModel(key = "shared-thread-tasks-${resolvedTaskChannelId.value}") {
                SharedTasksViewModel(
                    authStore = authStore,
                    dataSource = tasksDataSource,
                    sharedTasksDataStore = tasksDataStore,
                    scope = SharedTasksScope.Channel(resolvedTaskChannelId),
                    diagnosticsLogger = { bridge.log(it) }
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
    val currentServerId = currentSession?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
    val threadMembersServerId = currentServerId?.let(::ServerId)
    val threadMembersChannelId = baseRenderedPayload.sourceThreadId?.trim()?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val serverMembersState by remember(serverMembersStore) {
        serverMembersStore?.state ?: MutableStateFlow(SharedServerMembersState())
    }.collectAsState(SharedServerMembersState())
    val threadChannelMembersSlice by remember(
        serverMembersStore,
        threadMembersServerId,
        threadMembersChannelId,
        active
    ) {
        if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.channelMemberSliceFlow(
                serverId = threadMembersServerId,
                channelId = threadMembersChannelId
            )
                .onStart {
                    if (active) {
                        serverMembersStore.loadChannelMembers(
                            serverId = threadMembersServerId,
                            channelId = threadMembersChannelId,
                            priority = DataRequestPriority.Visible
                        )
                    }
                }
        } else {
            MutableStateFlow(
                SharedServerChannelMembersSlice(
                    serverId = threadMembersServerId?.value,
                    channelId = threadMembersChannelId?.value.orEmpty()
                )
            )
        }
    }.collectAsState(
        SharedServerChannelMembersSlice(
            serverId = threadMembersServerId?.value,
            channelId = threadMembersChannelId?.value.orEmpty()
        )
    )
    val threadMembersProjection =
        remember(baseRenderedPayload, threadChannelMembersSlice, serverMembersState, threadMembersServerId) {
            threadMembersProjection(
                basePayload = baseRenderedPayload,
                channelMembersSlice = threadChannelMembersSlice,
                serverMembersState = serverMembersState,
                serverId = threadMembersServerId
            )
        }
    val taskProjectionReferences =
        remember(timelineSlice) {
            timelineSlice.threadLinkedTaskReferences()
        }
    val linkedTasksProjection by remember(
        tasksDataStore,
        currentServerId,
        taskChannelId?.value,
        baseRenderedPayload.title,
        taskProjectionReferences
    ) {
        tasksDataStore
            ?.channelProjectionFlow(
                serverId = currentServerId,
                channelId = taskChannelId?.value,
                channelName = baseRenderedPayload.title
            )
            ?.map { slice -> slice.threadLinkedTasksProjection(taskProjectionReferences) }
            ?.distinctUntilChanged()
            ?: MutableStateFlow(ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap()))
    }.collectAsState(ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap()))
    val renderedPayload =
        remember(baseRenderedPayload, threadMembersProjection) {
            baseRenderedPayload.withThreadMembersProjection(threadMembersProjection)
        }
    val renderedTimelineSlice = timelineSlice
    val emptyMemberSuggestionSliceFlow = remember { MutableStateFlow(SharedMembersMemberSuggestionSlice()) }
    val memberSuggestionsFlow =
        remember(
            serverMembersStore,
            currentServerId,
            memberSuggestionScopeChannelId,
            memberSuggestionScopedMentions
        ) {
            val serverId = currentServerId?.let(::ServerId)
            if (serverMembersStore != null && serverId != null) {
                serverMembersStore.memberSuggestionSliceFlow(
                    serverId = serverId,
                    mentionChannelId = memberSuggestionScopeChannelId,
                    memberScopedMentions = memberSuggestionScopedMentions
                )
                    .onStart {
                        serverMembersStore.ensureMemberSuggestionsForServer(
                            serverId = serverId,
                            mentionChannelId = memberSuggestionScopeChannelId,
                            memberScopedMentions = memberSuggestionScopedMentions
                        )
                    }
                    .distinctUntilChanged()
            } else {
                emptyMemberSuggestionSliceFlow
            }
        }
    val emptyChannelSuggestionsFlow = remember { MutableStateFlow(SharedMembersChannelSuggestionSlice()) }
    val channelSuggestionsFlow = remember(membersStore) {
        membersStore?.channelSuggestionSliceFlow()
            ?.onStart {
                membersStore.dispatch(SharedMembersIntent.EnsureChannelSuggestions)
            }
            ?.distinctUntilChanged()
            ?: emptyChannelSuggestionsFlow
    }
    LaunchedEffect(
        serverMembersStore,
        membersStore,
        currentServerId,
        memberSuggestionScopeChannelId,
        memberSuggestionScopedMentions
    ) {
        delay(ThreadMentionSuggestionPrewarmDelayMs)
        val serverId = currentServerId?.let(::ServerId)
        if (serverMembersStore != null && serverId != null) {
            serverMembersStore.ensureMemberSuggestionsForServer(
                serverId = serverId,
                mentionChannelId = memberSuggestionScopeChannelId,
                memberScopedMentions = memberSuggestionScopedMentions
            )
        }
        membersStore?.dispatch(SharedMembersIntent.EnsureChannelSuggestions)
    }
    val filesViewModel: SharedThreadFilesViewModel =
        viewModel(key = "shared-thread-files-$threadInstanceKey") {
            SharedThreadFilesViewModel(dataSource, diagnosticsLogger = { bridge.log(it) })
        }
    val composerDraftViewModel: SharedThreadComposerDraftViewModel =
        viewModel(key = "shared-thread-composer-$threadInstanceKey") {
            SharedThreadComposerDraftViewModel(persistSlices = listOf(composerDraftPersistSlice))
        }
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

    LaunchedEffect(composerDraftViewModel, threadInstanceKey, resolvedAuxiliaryThreadId) {
        val composer =
            renderedPayload
                .resolveThreadComposer(displayName = headerDisplayModel.title)
                .copy(streamChannelId = resolvedAuxiliaryThreadId)
        val draftKey =
            composer
                .toThreadComposerSurface(
                    channelId = resolvedAuxiliaryThreadId.orEmpty(),
                    parentChannelId = renderedPayload.parentChannelId
                )
                .threadComposerDraftKey()
        composerDraftViewModel.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = composer,
                draftKey = draftKey
            )
        )
    }

    val latestThreadRealtimeEventHandler by rememberUpdatedState<suspend (RealtimeEvent) -> Unit>(
        { event ->
            if (event is Heartbeat) {
                threadViewModel.dispatch(SharedThreadIntent.RecoverRealtimeGap(event.seq))
            } else {
                val activeReplyParentMessageId =
                    initialPayload.originParentMessageId
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::MessageId)
                if (!event.isTaskProjectionEvent() && event !is MemberPresenceUpdated) {
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyRealtimeEvent(
                            event = event,
                            activeReplyParentMessageId = activeReplyParentMessageId
                        )
                    )
                }
                if (event is MemberPresenceUpdated) {
                    serverMembersStore?.applyPresence(event)
                }
                if (event is TaskUpdated) {
                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskUpdate(event.task))
                }
                if (event.refreshesThreadMemberSuggestions(memberSuggestionScopeChannelId)) {
                    currentServerId?.let { serverId ->
                        serverMembersStore?.ensureMemberSuggestionsForServer(
                            serverId = ServerId(serverId),
                            mentionChannelId = memberSuggestionScopeChannelId,
                            memberScopedMentions = memberSuggestionScopedMentions,
                            force = true
                        )
                    }
                    membersStore?.dispatch(
                        SharedMembersIntent.RefreshChannelSuggestions
                    )
                }
            }
        }
    )
    DisposableEffect(realtimeEvents, realtimeEventMapper, authStore, coroutineScope, bridge) {
        val callbackRef =
            realtimeEvents.addListener { eventData ->
                coroutineScope.launch {
                    val event = decodeSharedShellRealtimeEvent(eventData, realtimeEventMapper, bridge::log) ?: return@launch
                    val session = authStore.readSession()
                    val serverId = resolveSharedShellRealtimeServerId(eventData, event, session) ?: return@launch
                    val currentSessionServerId =
                        session
                            ?.currentServerId
                            ?.takeIf { it.isNotBlank() }
                            ?: return@launch
                    if (currentSessionServerId != serverId) return@launch
                    if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@launch
                    latestThreadRealtimeEventHandler(event)
                }
            }
        onDispose { realtimeEvents.removeListener(callbackRef) }
    }
    LaunchedEffect(threadTimelineKey, taskChannelId?.value, payload.status) {
        if (!shouldStartThreadTasksJoin(payload, startedTasksJoinThreadId, taskChannelId)) {
            return@LaunchedEffect
        }
        delay(ThreadBackgroundTasksJoinDelayMs)
        if (!shouldStartThreadTasksJoin(payload, startedTasksJoinThreadId, taskChannelId)) {
            return@LaunchedEffect
        }
        startedTasksJoinThreadId = taskChannelId?.value
        lastTasksJoinResultKey = null
        tasksJoinStartedAt = TimeSource.Monotonic.markNow()
        bridge.log(
            "thread_tasks_join_start threadId=${threadTimelineKey.orEmpty()} reason=after_first_content"
        )
        tasksViewModel?.dispatch(SharedTasksRouteIntent.StartBackground)
    }
    ThreadTasksJoinResultLogger(
        tasksViewModel = tasksViewModel,
        taskChannelId = taskChannelId,
        startedTasksJoinThreadId = startedTasksJoinThreadId,
        lastTasksJoinResultKey = lastTasksJoinResultKey,
        tasksJoinStartedAt = tasksJoinStartedAt,
        threadRouteKey = threadTimelineKey,
        onLogged = { resultKey -> lastTasksJoinResultKey = resultKey },
        log = { message -> bridge.log(message) }
    )

    var imagePreviewState by remember(threadInstanceKey) { mutableStateOf<ThreadImagePreviewState?>(null) }
    var imagePreviewUrlCache by remember(threadInstanceKey) { mutableStateOf<Map<String, ThreadImagePreviewUrl>>(emptyMap()) }
    val baseComposerState =
        remember(
            renderedPayload.composer,
            renderedPayload.instanceId,
            headerDisplayModel.title,
            renderedPayload.surfaceKind,
            renderedPayload.sourceThreadId,
            renderedPayload.streamChannelId,
            renderedPayload.parentChannelId,
            renderedPayload.parentMessageId,
            renderedPayload.originParentMessageId,
            renderedPayload.canSend
        ) {
            renderedPayload.resolveThreadComposer(displayName = headerDisplayModel.title)
        }
    val scopedBaseComposerState =
        remember(baseComposerState, renderedPayload.canSend, resolvedAuxiliaryThreadId) {
            baseComposerState.copy(
                streamChannelId = resolvedAuxiliaryThreadId,
                enabled = renderedPayload.canSend && resolvedAuxiliaryThreadId != null
            )
        }
    val composerSurface =
        remember(
            scopedBaseComposerState,
            resolvedAuxiliaryThreadId,
            renderedPayload.parentChannelId,
            headerDisplayModel.title,
            headerDisplayModel.subtitle,
            renderedPayload.surfaceKind
        ) {
            scopedBaseComposerState.toThreadComposerSurface(
                channelId = resolvedAuxiliaryThreadId.orEmpty(),
                parentChannelId = renderedPayload.parentChannelId,
                mentionChannelLabel = headerDisplayModel.threadComposerMentionScopeChannelLabel()
            )
        }
    fun currentComposerPayload(): KuiklyThreadComposerPayload =
        scopedBaseComposerState.withThreadComposerDraftState(
            draftState = composerDraftViewModel.state.value,
            renderedPayload = renderedPayload,
            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId
        )
    var deferredAttachmentSend by remember(threadInstanceKey) {
        mutableStateOf<ThreadDeferredAttachmentSend?>(null)
    }
    val agentStatusResolver =
        remember(serverMembersStore, currentSession?.currentServerId) {
            slockAgentStatusResolver(serverMembersStore, currentSession?.currentServerId)
        }
    val memberAvatarResolver =
        remember(serverMembersStore, currentSession?.currentServerId) {
            slockMemberAvatarResolver(serverMembersStore, currentSession?.currentServerId)
        }
    val renderedThread = renderedPayload
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
        val target = renderedTimelineSlice.findAttachmentCommentTarget(attachmentId)
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
        renderedTimelineSlice.olderMessagesStatus,
        renderedTimelineSlice.newerMessagesStatus
    ) {
        if (
            renderedThread.status == "no_permission" ||
            renderedThread.status == "error" ||
            renderedTimelineSlice.olderMessagesStatus == "error" ||
            renderedTimelineSlice.newerMessagesStatus == "error"
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
        pager.openSlockFilePreviewPage(
            attachment.toThreadAttachmentLazyPreviewPayload(
                kind = "image",
                imageGallery = group.images,
                imageGalleryIndex = currentIndex
            )
        )
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
        val downloadId = attachment.threadAttachmentDownloadId()
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(
                    status = "downloading",
                    downloadId = downloadId,
                    progress = SlockFilePreviewInitialDownloadProgress.copy(downloadId = downloadId)
                ))
        coroutineScope.launch {
            val result =
                openSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment,
                    downloadId = downloadId,
                    onProgress = { progress ->
                        if (progress.downloadId.isNotBlank() && progress.downloadId != downloadId) return@openSharedThreadAttachment
                        val current = attachmentOpenStates[attachment.id]
                        if (current?.downloadId != downloadId) return@openSharedThreadAttachment
                        attachmentOpenStates =
                            attachmentOpenStates +
                                (attachment.id to current.copy(
                                    status = if (progress.status == "paused") "paused" else "downloading",
                                    progress = progress.copy(downloadId = progress.downloadId.ifBlank { downloadId })
                                ))
                    }
                )
            if (result.status == "opened") {
                attachmentOpenStates = attachmentOpenStates - attachment.id
            } else {
                attachmentOpenStates =
                    attachmentOpenStates +
                        (attachment.id to ThreadAttachmentOpenUiState(
                            status = result.status,
                            message = result.message,
                            downloadId = downloadId
                        ))
                if (result.status != "cancelled") {
                    bridge.toast(result.message.ifBlank { "Unable to open attachment" })
                }
            }
        }
    }
    fun openAttachmentNoPreview(attachment: KuiklyThreadAttachmentItem) {
        if (attachment.id.isBlank()) return
        imagePreviewState = null
        val lazyPreviewKind =
            when {
                attachment.isMarkdownAttachment() -> "markdown"
                attachment.isTextLikeAttachment() || attachment.isHtmlAttachment() -> "text"
                attachment.isMp4VideoAttachment() -> "video"
                else -> "file"
            }
        pager.openSlockFilePreviewPage(attachment.toThreadAttachmentLazyPreviewPayload(kind = lazyPreviewKind))
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
                            "failed" ->
                                listOf(
                                    "Attachment upload failed",
                                    threadFailureReasonLabel(attachment.errorCode)
                                ).filterNotNull().joinToString(": ") + ". Retry the upload from the composer."
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
                sizeLabel = attachment.sizeLabel,
                thumbnailUrl = attachment.previewUri
        )
        if (threadAttachment.isImageAttachment() && imageAnnotationEnabled()) {
            composerImageActionCounter += 1
            val composerImageActionId = "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${threadAttachment.id.take(12)}"
            pager.openSlockFilePreviewPage(
                threadAttachment.toThreadAttachmentLazyPreviewPayload(kind = "image").copy(
                    imageUrl = attachment.previewUri?.takeIf { it.isNotBlank() },
                    composerImageThreadKey = threadInstanceKey,
                    composerImageActionId = composerImageActionId,
                    composerImageFileName = name,
                    composerImageMimeType = attachment.mimeType.orEmpty(),
                    composerImageReplaceAttachmentId = attachment.id,
                    composerImageCanEdit = true
                )
            )
        } else if (threadAttachment.isImageAttachment()) {
            pager.openSlockFilePreviewPage(
                threadAttachment.toThreadAttachmentLazyPreviewPayload(kind = "image").copy(
                    imageUrl = attachment.previewUri?.takeIf { it.isNotBlank() }
                )
            )
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
        renderedThread.selectedTab,
        renderedThread.showReplyActions,
        renderedTimelineSlice.status,
        renderedTimelineSlice.messages.size
    ) {
        renderedTimelineSlice.toRenderSignature(
            instanceId = renderedThread.instanceId,
            sourceThreadId = renderedThread.sourceThreadId,
            selectedTab = renderedThread.selectedTab
        )
    }
    LaunchedEffect(renderSignature) {
        bridge.log(
            "thread_render instanceId=${renderSignature.instanceId} status=${renderSignature.status} tab=${renderSignature.selectedTab} " +
                "messages=${renderSignature.messagesSize} " +
                "showReplyActions=${renderSignature.showReplyActions} replyRows=${renderSignature.replyRowsSize} " +
                "firstReply=${if (renderSignature.firstReplyId8 == null) "none" else "${renderSignature.firstReplyId8}:${renderSignature.firstReplyCount}/${renderSignature.firstReplyUnreadCount}"}"
        )
    }
    val visibleReadAllMarkerKey =
        remember(
            renderedThread.sourceThreadId,
            renderedTimelineSlice.status,
            renderedTimelineSlice.messages.size,
            renderedTimelineSlice.messages.lastOrNull()?.id,
            renderedTimelineSlice.messages.lastOrNull()?.seq
        ) {
            renderedTimelineSlice.visibleReadAllMarkerKey(renderedThread.sourceThreadId)
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
                    "bodyLength" to context.body.length.toString(),
                    "localMessageId" to localMessageId,
                    "parentMessageId" to context.parentMessageId.orEmpty(),
                    "streamChannelId" to context.streamChannelId.orEmpty(),
                    "mentionChannelId" to context.mentionChannelId.orEmpty()
                )
        )
        localSendJobs[localMessageId]?.job?.cancel()
        val sendJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                fun markFailed(errorCode: String?, errorMessage: String? = null) {
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyLocalSendFailure(
                            messageId = localMessageId,
                            errorCode = errorCode,
                            errorMessage = errorMessage
                        )
                    )
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
                        val errorCode = threadSendExceptionErrorCode(it)
                        val errorType = it::class.simpleName.orEmpty().ifBlank { "Throwable" }
                        val durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds
                        bridge.log(
                            "thread_send_exception localMessageId=$localMessageId " +
                                "channelId=${context.channelId} errorType=$errorType " +
                                "mapped=$errorCode durationMs=$durationMs"
                        )
                        bridge.performanceMarker(
                            phase = "composer_send",
                            routeName = "kuikly_thread",
                            event = "end",
                            threadId = context.channelId,
                            durationMs = durationMs,
                            metadata =
                                mapOf(
                                    "result" to "exception",
                                    "code" to errorCode,
                                    "errorType" to errorType,
                                    "localMessageId" to localMessageId
                                )
                        )
                        markFailed(errorCode, threadSendExceptionErrorMessage(it))
                        return@launch
                    }
                val sentPage =
                    when (sendResponse) {
                        is ThreadApiResult.Success -> sendResponse.data
                        is ThreadApiResult.Failure -> {
                            val errorCode =
                                sendResponse.contractCode
                                    ?: threadFailureDiagnosticCode(sendResponse.errorBodyText)
                                    ?: "http_${sendResponse.code}"
                            val errorMessage = threadFailureReasonMessage(sendResponse.errorBodyText)
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
                                        "httpCode" to sendResponse.code.toString(),
                                        "contractCode" to sendResponse.contractCode.orEmpty(),
                                        "errorBytes" to (sendResponse.errorBodyText?.length ?: 0).toString(),
                                        "localMessageId" to localMessageId
                                    )
                            )
                            markFailed(errorCode, errorMessage)
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
            } finally {
                localSendJobs = localSendJobs - localMessageId
            }
        }
        localSendJobs =
            localSendJobs +
                (localMessageId to ThreadLocalSendJob(sendJob, startedFromComposer))
        sendJob.start()
    }
    fun markDeferredAttachmentSendFailed(errorCode: String?, errorMessage: String? = null) {
        val pending = deferredAttachmentSend ?: return
        deferredAttachmentSend = null
        threadViewModel.dispatch(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = pending.localMessageId,
                errorCode = errorCode,
                errorMessage = errorMessage
            )
        )
        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
    }
    fun handleAttachmentUploadFailure(errorCode: String, attachmentIds: Set<String> = emptySet()) {
        if (deferredAttachmentSend != null) {
            markDeferredAttachmentSendFailed(errorCode)
        } else {
            if (attachmentIds.isNotEmpty()) {
                composerDraftViewModel.dispatch(
                    SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                        attachmentIds = attachmentIds,
                        errorCode = errorCode
                    )
                )
            }
            bridge.toast("Attachment upload failed: ${threadFailureReasonLabel(errorCode) ?: errorCode}")
        }
    }
    fun uploadComposerAttachmentFiles(files: List<AttachmentUploadFile>, replaceAttachmentId: String? = null) {
        val channelId = composerSurface.sendChannelId
        if (channelId == null) {
            bridge.toast("Attachment picker unavailable")
            return
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
                        previewUri = file.composerLocalPreviewUri(),
                        status = "uploading"
                    )
                }
        if (localAttachments.isNotEmpty()) {
            attachmentUploadFilesByLocalId =
                attachmentUploadFilesByLocalId +
                    files
                        .filter { file -> file.id.isNotBlank() }
                        .associateBy { file -> file.id }
            composerDraftViewModel.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(localAttachments)
            )
        }
        files.forEach { file ->
            val localAttachmentId = file.id.takeIf { it.isNotBlank() } ?: return@forEach
            val uploadJob =
                coroutineScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val uploadResult =
                            attachmentUploadSemaphore.withPermit {
                                dataSource.uploadAttachments(channelId, listOf(file))
                            }
                        when (uploadResult) {
                            is DataResult.Content -> {
                                val previewUri = file.composerLocalPreviewUri()
                                val uploaded =
                                    uploadResult.data.attachments.map { attachment ->
                                        SharedThreadComposerAttachment(
                                            id = attachment.id.value,
                                            fileName = attachment.name,
                                            sizeLabel = attachment.sizeLabel,
                                            mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                            previewUri = previewUri,
                                            status = "ready"
                                        )
                                    }
                                if (uploaded.isEmpty()) {
                                    handleAttachmentUploadFailure("empty_upload_result", setOf(localAttachmentId))
                                    return@launch
                                }
                                replaceAttachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(id))
                                }
                                composerDraftViewModel.dispatch(
                                    SharedThreadComposerDraftIntent.AddUploadedAttachments(
                                        attachments = uploaded,
                                        replacedAttachmentIds = setOf(localAttachmentId),
                                        replacedAttachmentIdsByUploadedId =
                                            uploaded.associate { attachment -> attachment.id to localAttachmentId }
                                    )
                                )
                                attachmentUploadFilesByLocalId = attachmentUploadFilesByLocalId - localAttachmentId
                            }
                            is DataResult.Error ->
                                handleAttachmentUploadFailure(uploadResult.error.code, setOf(localAttachmentId))
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(uploadResult)) return@launch
                                handleAttachmentUploadFailure(uploadResult.blocker.code, setOf(localAttachmentId))
                            }
                            is DataResult.Empty ->
                                handleAttachmentUploadFailure("empty_upload_result", setOf(localAttachmentId))
                        }
                    } catch (_: CancellationException) {
                        // The user removed this uploading tile; RemoveAttachment already hid it.
                    } finally {
                        attachmentUploadJobs = attachmentUploadJobs - localAttachmentId
                    }
                }
            attachmentUploadJobs = attachmentUploadJobs + (localAttachmentId to uploadJob)
            uploadJob.start()
        }
    }
    fun nextComposerImageActionId(seed: String): String {
        composerImageActionCounter += 1
        return "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${seed.take(12)}"
    }
    fun openComposerImageTransfer(file: AttachmentUploadFile) {
        val actionId = nextComposerImageActionId(file.id)
        val previewUri = file.composerLocalPreviewUri().orEmpty()
        pager.openSlockFilePreviewPage(
            SlockFilePreviewPayload(
                kind = "image",
                title = "Image preview",
                subtitle = file.fileName,
                name = file.fileName,
                path = file.fileName,
                meta = listOf(file.sizeBytes?.let(::formatAttachmentSizeLabel).orEmpty(), file.mimeType.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                imageUrl = previewUri.takeIf { it.isNotBlank() },
                message = if (previewUri.isBlank()) "Image preview is unavailable." else "",
                externalFileName = file.fileName,
                externalMimeType = file.mimeType.orEmpty(),
                composerImageThreadKey = threadInstanceKey,
                composerImageActionId = actionId,
                composerImageFileRef = file.fileRef.orEmpty(),
                composerImageFileName = file.fileName,
                composerImageMimeType = file.mimeType.orEmpty(),
                composerImageSizeBytes = file.sizeBytes,
                composerImagePreviewUri = previewUri,
                composerImageCanComplete = true,
                composerImageCanEdit = true
            )
        )
    }
    DisposableEffect(notifyModule, threadInstanceKey) {
        val callbackRef =
            notifyModule.addNotify(SLOCK_COMPOSER_IMAGE_EDIT_EVENT, crossProcess = true) { data ->
                val eventData = data ?: return@addNotify
                if (eventData.optString("threadKey").orEmpty() != threadInstanceKey) return@addNotify
                val fileRef = eventData.optString("fileRef").takeIf { it.isNotBlank() } ?: return@addNotify
                val actionId = eventData.optString("actionId").takeIf { it.isNotBlank() } ?: fileRef.hashCode().toString()
                val file =
                    AttachmentUploadFile(
                        id = "edited-$actionId",
                        fileName = eventData.optString("fileName").takeIf { it.isNotBlank() } ?: "image.png",
                        mimeType = eventData.optString("mimeType").takeIf { it.isNotBlank() } ?: "image/png",
                        sizeBytes = eventData.optLong("sizeBytes", 0L).takeIf { it > 0L },
                        previewUri = eventData.optString("previewUri").takeIf { it.isNotBlank() } ?: fileRef,
                        fileRef = fileRef
                    )
                val replaceAttachmentId = eventData.optString("replaceAttachmentId").takeIf { it.isNotBlank() }
                uploadComposerAttachmentFiles(listOf(file), replaceAttachmentId = replaceAttachmentId)
            }
        onDispose {
            notifyModule.removeNotify(SLOCK_COMPOSER_IMAGE_EDIT_EVENT, callbackRef)
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

    suspend fun refreshComposerMemberSuggestionsAfterMembershipMutation() {
        val store = serverMembersStore ?: return
        val serverId = threadMembersServerId ?: currentServerId?.let(::ServerId) ?: return
        store.ensureMemberSuggestionsForServer(
            serverId = serverId,
            mentionChannelId = memberSuggestionScopeChannelId,
            memberScopedMentions = memberSuggestionScopedMentions,
            force = true
        )
    }

    fun patchComposerMemberSuggestionsAfterMembershipMutation(
        member: KuiklyThreadMemberItem?,
        inMentionChannel: Boolean
    ): Boolean {
        val store = serverMembersStore ?: return false
        val serverId = threadMembersServerId ?: currentServerId?.let(::ServerId) ?: return false
        val profile = member?.toMemberProfile() ?: return false
        return store.patchMentionChannelMemberSuggestion(
            serverId = serverId,
            mentionChannelId = memberSuggestionScopeChannelId,
            memberScopedMentions = memberSuggestionScopedMentions,
            member = profile,
            inMentionChannel = inMentionChannel
        )
    }

    fun removeComposerMemberSuggestionsAfterMembershipMutation(
        memberId: String,
        memberKind: MemberKind?
    ): Boolean {
        val store = serverMembersStore ?: return false
        val serverId = threadMembersServerId ?: currentServerId?.let(::ServerId) ?: return false
        return store.removeMentionChannelMemberSuggestion(
            serverId = serverId,
            mentionChannelId = memberSuggestionScopeChannelId,
            memberScopedMentions = memberSuggestionScopedMentions,
            memberId = memberId,
            memberKind = memberKind
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ThreadPageContent(
            threadInstanceKey = threadInstanceKey,
            payload = renderedThread,
            timelineSlice = renderedTimelineSlice,
            replyMetadataByParentId = replyMetadataByParentId,
            headerDisplay = headerDisplayModel,
            composerSurface = composerSurface,
            baseComposerState = scopedBaseComposerState,
            composerDraftViewModel = composerDraftViewModel,
            composerDraftPersistSlice = composerDraftPersistSlice,
            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
            tasksViewModel = tasksViewModel,
            taskChannelId = taskChannelId,
            linkedTasksProjection = linkedTasksProjection,
            filesViewModel = filesViewModel,
            memberSuggestionsFlow = memberSuggestionsFlow,
            channelSuggestionsFlow = channelSuggestionsFlow,
            contentExtrasVisible = true,
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
            onOpenMemberProfile = { memberId, memberKind, sourceServerId -> onOpenMemberProfile(renderedThread, renderedTimelineSlice, memberId, memberKind, sourceServerId) },
            highlightedMentions = highlightedMentions,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = memberAvatarResolver,
            diagnosticsLogger = { bridge.log(it) },
            pendingActionCardIds = pendingActionCardIds,
            pendingTaskConvertMessageIds = pendingTaskConvertMessageIds,
            attachmentOpenStates = attachmentOpenStates,
            attachmentCommentsEnabled = attachmentCommentsEnabled,
            currentServerSlug = currentServerSlug,
            onExportShareImage = { rect ->
                bridge.exportThreadShareImage(
                    x = rect?.x,
                    y = rect?.y,
                    width = rect?.width,
                    height = rect?.height,
                    fragments = rect?.path,
                    frame = rect?.frame ?: true
                )
            },
            onSaveShareImage = { path ->
                bridge.saveThreadShareImage(path)
            },
            onBack = onBack,
            onThreadEvent = { action, itemId, attachmentId, tab ->
                when {
                    action == "openAttachment" && !attachmentId.isNullOrBlank() -> {
                        val imageGroup =
                            renderedTimelineSlice.findThreadImageAttachmentGroup(attachmentId)
                        if (imageGroup != null) {
                            openImagePreview(imageGroup)
                        } else {
                            val attachment =
                                renderedTimelineSlice.findThreadAttachment(attachmentId)
                                    ?: KuiklyThreadAttachmentItem(id = attachmentId, name = "Attachment")
                            openAttachmentNoPreview(attachment)
                        }
                    }
                    action == "downloadAttachment" && !attachmentId.isNullOrBlank() -> {
                        val attachment =
                            renderedTimelineSlice.findThreadAttachment(attachmentId)
                                ?: KuiklyThreadAttachmentItem(id = attachmentId, name = "Attachment")
                        openAttachmentExternally(attachment)
                    }
                    action == "openFileSource" && !itemId.isNullOrBlank() -> {
                        val fileItem =
                            filesViewModel.state.value.payload.items.firstOrNull { it.attachmentId == itemId }
                        val sourceMessageId = fileItem?.threadFileSourceFocusMessageId()
                        if (sourceMessageId == null) {
                            bridge.toast("File source unavailable")
                            return@ThreadPageContent
                        }
                        threadViewModel.dispatch(
                            SharedThreadIntent.FocusMessage(
                                messageId = sourceMessageId,
                                switchToTab = KuiklyThreadTab.Chat.wireValue
                            )
                        )
                    }
                    action == "openAttachmentComments" && !attachmentId.isNullOrBlank() -> {
                        openAttachmentComments(attachmentId)
                    }
                    action == "copyMessage" && !itemId.isNullOrBlank() -> {
                    val body = renderedTimelineSlice.threadMessageById(itemId)?.body.orEmpty()
                    if (body.isNotBlank()) {
                        bridge.copyToPasteboard(body)
                        bridge.toast("Copied")
                    }
                }
                action == "copyShareSelectionMarkdown" && !itemId.isNullOrBlank() -> {
                    bridge.copyToPasteboard(itemId)
                    bridge.toast("Copied")
                }
                action == "shareSelectionImageSaved" -> {
                    bridge.toast(itemId?.takeIf { it.isNotBlank() } ?: "Image saved")
                }
                action == "shareSelectionExportFailed" -> {
                    bridge.toast(itemId?.takeIf { it.isNotBlank() } ?: "Unable to export image")
                }
                action == "copyMessageLink" && !itemId.isNullOrBlank() -> {
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
                        bridge.copyToPasteboard(permalink)
                        bridge.toast("Copied")
                    } else {
                        bridge.toast("Unable to copy link")
                    }
                }
                action == "openSelectMessageText" && !itemId.isNullOrBlank() -> {
                    val message = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (message?.body.isNullOrBlank()) {
                        bridge.toast("No text to select")
                    } else {
                        textSelectionMessage = message
                    }
                }
                action == "openMessageThread" && !itemId.isNullOrBlank() -> {
                    val parentMessage =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (parentMessage == null) {
                        bridge.toast("Unable to open replies")
                        return@ThreadPageContent
                    }
                    val parentReplyMetadata = replyMetadataByParentId.threadReplyMetadataFor(parentMessage)
                    val loadingPayload =
                        renderedThread.replyThreadLoadingPayload(
                            parentMessageId = parentMessage.id,
                            parentChannelId = renderedThread.sourceThreadId,
                            threadChannelId = parentReplyMetadata.threadChannelId,
                            focusedMessageId = parentReplyMetadata.firstUnreadReplyMessageId
                        )
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyReplyThreadRead(
                            parentMessageId = parentMessage.id,
                            threadChannelId = parentReplyMetadata.threadChannelId
                        )
                    )
                    bridge.log(
                        "thread_reply_open action=message_chip parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                            "parentMessageId=${parentMessage.id} replyCount=${parentReplyMetadata.replyCount} " +
                            "unreadReplyCount=${parentReplyMetadata.unreadReplyCount} " +
                            "knownThreadChannelId=${parentReplyMetadata.threadChannelId.orEmpty()} " +
                            "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                    )
                    onOpenThreadPayload(loadingPayload)
                }
                action == "followMessageThread" && !itemId.isNullOrBlank() -> {
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
                                    bridge.toast("Followed thread")
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast("Unable to follow thread")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast("Unable to follow thread")
                                }
                            }
                        } finally {
                            pendingThreadFollowMessageIds = pendingThreadFollowMessageIds - itemId
                        }
                    }
                }
                action == "unfollowMessageThread" && !itemId.isNullOrBlank() && !attachmentId.isNullOrBlank() -> {
                    val threadChannelId = attachmentId
                    if (threadChannelId in pendingThreadFollowMessageIds) {
                        return@ThreadPageContent
                    }
                    pendingThreadFollowMessageIds = pendingThreadFollowMessageIds + threadChannelId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.unfollowThread(ChannelId(threadChannelId))) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageThreadFollowed(
                                            parentMessageId = itemId,
                                            threadChannelId = threadChannelId,
                                            followed = false
                                        )
                                    )
                                    bridge.toast("Unfollowed thread")
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast("Unable to unfollow thread")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast("Unable to unfollow thread")
                                }
                            }
                        } finally {
                            pendingThreadFollowMessageIds = pendingThreadFollowMessageIds - threadChannelId
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
                        bridge.log(
                            "thread_route_open action=parent_message parentMessageId=$parentMessageId " +
                                "targetThreadId=${loadingPayload.sourceThreadId.orEmpty()} " +
                                "focusedMessageId=${loadingPayload.focusedMessageId.orEmpty()} " +
                                "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                        )
                        onOpenThreadPayload(loadingPayload)
                    }
                }
                action == "toggleMessageSaved" && !itemId.isNullOrBlank() -> {
                    val previousSaved =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)?.isSaved
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
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)?.threadActionCard()
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
                    val toggleTarget = renderedTimelineSlice.threadReactionToggleTarget(itemId, tab)
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
                    val parentMessage = renderedTimelineSlice.findTaskParentMessage(itemId, linkedTasksProjection)
                    if (parentMessage == null) {
                        bridge.toast("Unable to open task")
                        return@ThreadPageContent
                    }
                    val parentReplyMetadata = replyMetadataByParentId.threadReplyMetadataFor(parentMessage)
                    val loadingPayload =
                        renderedThread.replyThreadLoadingPayload(
                            parentMessageId = parentMessage.id,
                            parentChannelId = renderedThread.sourceThreadId,
                            threadChannelId = parentReplyMetadata.threadChannelId,
                            focusedMessageId = parentReplyMetadata.firstUnreadReplyMessageId
                        )
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyReplyThreadRead(
                            parentMessageId = parentMessage.id,
                            threadChannelId = parentReplyMetadata.threadChannelId
                        )
                    )
                    bridge.log(
                        "thread_reply_open action=task parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                            "parentMessageId=${parentMessage.id} taskId=$itemId replyCount=${parentReplyMetadata.replyCount} " +
                            "knownThreadChannelId=${parentReplyMetadata.threadChannelId.orEmpty()} " +
                            "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                    )
                    onOpenThreadPayload(loadingPayload)
                }
                action == "openMarkdownTaskNumber" && !itemId.isNullOrBlank() -> {
                    val taskNumber = itemId.removePrefix("#").trim().toIntOrNull()
                    if (taskNumber == null || currentServerId.isNullOrBlank()) {
                        bridge.toast("Unable to open ${itemId.take(32)}")
                        return@ThreadPageContent
                    }
                    coroutineScope.launch {
                        when (
                            val result =
                                openMarkdownTaskNumberPayload(
                                    serverId = ServerId(currentServerId),
                                    taskNumber = taskNumber,
                                    currentPayload = renderedThread,
                                    currentTaskChannelId = taskChannelId,
                                    tasksDataSource = tasksDataSource,
                                    tasksDataStore = tasksDataStore
                                )
                        ) {
                            is MarkdownTaskNumberOpenResult.Found -> {
                                bridge.log(
                                    "thread_markdown_task_open taskNumber=$taskNumber taskId=${result.task.id.value} " +
                                        "parentChannelId=${result.payload.parentChannelId.orEmpty()} " +
                                        "parentMessageId=${result.payload.parentMessageId.orEmpty()} " +
                                        "threadId=${result.payload.streamChannelId.orEmpty()}"
                                )
                                onOpenThreadPayload(result.payload)
                            }
                            is MarkdownTaskNumberOpenResult.Ambiguous -> {
                                markdownTaskCandidateModalState =
                                    ThreadMarkdownTaskCandidateModalState(
                                        taskNumber = result.taskNumber,
                                        candidates = result.candidates
                                    )
                            }
                            MarkdownTaskNumberOpenResult.NotFound -> {
                                bridge.toast("Unable to open #$taskNumber")
                            }
                            MarkdownTaskNumberOpenResult.AuthRequired -> {
                                routeAuthRequired(
                                    DataResult.NoPermission(PermissionBlocker(code = "tasks_401"))
                                )
                            }
                            MarkdownTaskNumberOpenResult.Failed -> {
                                bridge.toast("Unable to open #$taskNumber")
                            }
                        }
                    }
                }
                action == "openCommentHost" && !itemId.isNullOrBlank() -> {
                    val commentRef =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)?.commentRef
                    val hostMessageId = commentRef?.hostMessageId?.takeIf { it.isNotBlank() }
                    val loadingPayload = commentRef?.let { renderedThread.commentHostLoadingPayload(it) }
                    if (hostMessageId == null || loadingPayload == null) {
                        bridge.toast("Comment source unavailable")
                        return@ThreadPageContent
                    }
                    bridge.log(
                        "thread_route_open action=comment_host hostMessageId=$hostMessageId " +
                            "targetThreadId=${loadingPayload.sourceThreadId.orEmpty()} " +
                            "parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                            "parentMessageId=${loadingPayload.parentMessageId.orEmpty()} " +
                            "focusedMessageId=${loadingPayload.focusedMessageId.orEmpty()} " +
                            "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                    )
                    onOpenThreadPayload(loadingPayload)
                }
                action == "openMarkdownUrl" && !itemId.isNullOrBlank() -> {
                    val target = itemId.toSlockThreadWebLinkTarget()
                    if (target == null) {
                        bridge.openUrl(itemId, null)
                    } else {
                        val loadingPayload = renderedThread.webLinkLoadingPayload(target)
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
                }
                action == "openMarkdownTag" && !itemId.isNullOrBlank() -> {
                    val localTarget = renderedThread.resolveMarkdownMentionOpenTarget(itemId, emptyList())
                    if (localTarget != null) {
                        onOpenMemberProfile(renderedThread, renderedTimelineSlice, localTarget.memberId, localTarget.memberKind, localTarget.sourceServerId)
                        return@ThreadPageContent
                    }
                    if (serverMembersStore == null || threadMembersServerId == null) {
                        bridge.toast("Unable to open ${itemId.take(32)}")
                        return@ThreadPageContent
                    }
                    coroutineScope.launch {
                        val membersResult =
                            serverMembersStore.refreshMembers(
                                serverId = threadMembersServerId,
                                priority = DataRequestPriority.Visible
                            )
                        if (membersResult is DataResult.NoPermission && routeAuthRequired(membersResult)) {
                            return@launch
                        }
                        if (threadMembersChannelId != null) {
                            val channelMembersResult =
                                serverMembersStore.loadChannelMembers(
                                    serverId = threadMembersServerId,
                                    channelId = threadMembersChannelId,
                                    priority = DataRequestPriority.Visible
                                )
                            if (channelMembersResult is DataResult.NoPermission && routeAuthRequired(channelMembersResult)) {
                                return@launch
                            }
                        }
                        val serverMembers =
                            serverMembersStore.currentServerMembers(threadMembersServerId) +
                                threadMembersChannelId
                                    ?.let { channelId ->
                                        serverMembersStore.currentChannelMembers(threadMembersServerId, channelId)
                                    }
                                    .orEmpty()
                        val target =
                            renderedThread.resolveMarkdownMentionOpenTarget(
                                label = itemId,
                                serverMembers = serverMembers
                            )
                        if (target == null) {
                            bridge.toast("No member found for ${itemId.take(32)}")
                        } else {
                            onOpenMemberProfile(renderedThread, renderedTimelineSlice, target.memberId, target.memberKind, target.sourceServerId)
                        }
                    }
                }
                action == "openMarkdownChannel" && !itemId.isNullOrBlank() -> {
                    coroutineScope.launch {
                        val loadingPayload = dataSource.markdownChannelLaunchPayload(renderedThread, itemId)
                        if (loadingPayload == null) {
                            bridge.toast("Unable to open #${itemId.removePrefix("#").take(31)}")
                        } else {
                            onOpenThreadPayload(loadingPayload)
                        }
                    }
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
                                val addedMember = result.data.members.firstOrNull { it.id == itemId }
                                if (!patchComposerMemberSuggestionsAfterMembershipMutation(addedMember, inMentionChannel = true)) {
                                    refreshComposerMemberSuggestionsAfterMembershipMutation()
                                }
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
                                if (!removeComposerMemberSuggestionsAfterMembershipMutation(itemId, member?.threadMemberKind())) {
                                    refreshComposerMemberSuggestionsAfterMembershipMutation()
                                }
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
                action == "convertMessageToTask" && !itemId.isNullOrBlank() -> {
                    if (itemId in pendingTaskConvertMessageIds) {
                        return@ThreadPageContent
                    }
                    val message = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (message == null || !renderedTimelineSlice.canConvertMessagesToTasks || !message.canConvertToTask()) {
                        bridge.toast("Unable to convert message")
                        return@ThreadPageContent
                    }
                    pendingTaskConvertMessageIds = pendingTaskConvertMessageIds + itemId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.convertMessageToTask(itemId)) {
                                is DataResult.Content -> {
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskUpdate(result.data))
                                    bridge.toast("Task created")
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast("Unable to convert message")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast("Unable to convert message")
                                }
                            }
                        } finally {
                            pendingTaskConvertMessageIds = pendingTaskConvertMessageIds - itemId
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
                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, status))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, status)) {
                            is DataResult.Content -> {
                                result.data?.let { task ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskUpdate(task))
                                }
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "markTaskDone" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedThread
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, TaskStatus.Done))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Done)) {
                            is DataResult.Content -> {
                                result.data?.let { task ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskUpdate(task))
                                }
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "reopenTask" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedThread
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, TaskStatus.Todo))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Todo)) {
                            is DataResult.Content -> {
                                result.data?.let { task ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskUpdate(task))
                                }
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "joinChannel" -> {
                    val channelId = renderedThread.joinChannelTargetId()
                    if (channelId == null) {
                        bridge.toast("Unable to join channel")
                        return@ThreadPageContent
                    }
                    coroutineScope.launch {
                        when (val result = dataSource.joinChannel(channelId)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyChannelJoined(result.data.channelId.value))
                                realtimeEvents.joinChannel(result.data.channelId.value)
                                bridge.toast("Joined channel")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast("Unable to join channel")
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast("Unable to join channel")
                            }
                        }
                    }
                }
                action == "selectTab" && !tab.isNullOrBlank() -> {
                    threadViewModel.dispatch(SharedThreadIntent.SelectTab(tab))
                }
                action == "loadMembers" -> {
                    if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
                        coroutineScope.launch {
                            serverMembersStore.loadChannelMembers(
                                serverId = threadMembersServerId,
                                channelId = threadMembersChannelId,
                                priority = DataRequestPriority.Visible,
                                force = true
                            )
                        }
                    } else {
                        threadViewModel.dispatch(SharedThreadIntent.LoadMembers)
                    }
                }
                action == "loadOlder" || action == "retryLoadOlder" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadOlder)
                }
                action == "loadNewer" || action == "retryLoadNewer" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadNewer)
                }
                action == "deleteLocalFailedSend" && !itemId.isNullOrBlank() -> {
                    val message = renderedTimelineSlice.threadMessageById(itemId) ?: return@ThreadPageContent
                    if (message.isFailedLocalSend()) {
                        threadViewModel.dispatch(SharedThreadIntent.DismissLocalFailedSend(message.id))
                    }
                }
                action == "cancelLocalSend" && !itemId.isNullOrBlank() -> {
                    val message = renderedTimelineSlice.threadMessageById(itemId) ?: return@ThreadPageContent
                    if (message.isSendingLocalSend()) {
                        localSendJobs[itemId]?.let { sendJob ->
                            sendJob.job.cancel()
                            localSendJobs = localSendJobs - itemId
                            if (sendJob.startedFromComposer) {
                                composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
                            }
                        }
                        deferredAttachmentSend
                            ?.takeIf { pending -> pending.localMessageId == itemId }
                            ?.let { pending ->
                                val pendingAttachmentIds = pending.context.attachments.map { attachment -> attachment.id }.toSet()
                                pendingAttachmentIds.forEach { attachmentId -> attachmentUploadJobs[attachmentId]?.cancel() }
                                attachmentUploadJobs = attachmentUploadJobs - pendingAttachmentIds
                                attachmentUploadFilesByLocalId = attachmentUploadFilesByLocalId - pendingAttachmentIds
                                deferredAttachmentSend = null
                                composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
                            }
                        threadViewModel.dispatch(SharedThreadIntent.CancelLocalSend(message.id))
                    }
                }
                action == "retryLocalSend" && !itemId.isNullOrBlank() -> {
                    val message = renderedTimelineSlice.threadMessageById(itemId) ?: return@ThreadPageContent
                    val pendingContext = message.pendingSendContext ?: return@ThreadPageContent
                    if (!message.canRetryLocalSend()) {
                        return@ThreadPageContent
                    }
                    threadViewModel.dispatch(SharedThreadIntent.PrepareForLocalSend)
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyLocalSendStarted(
                            message.copy(
                                deliveryState = ThreadMessageDeliverySending,
                                pendingSendErrorCode = null,
                                pendingSendErrorMessage = null
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
                    bridge.log(
                        "thread_composer_change instanceId=$composerInstanceId " +
                            "valueLen=${value?.length ?: -1} valueBlank=${value?.isBlank()} asTask=$asTask " +
                            "stateTextLen=${currentDraftState.seedText.length} stateTextBlank=${currentDraftState.seedText.isBlank()} " +
                            "stateAsTask=${currentDraftState.seedAsTask} draftKey=${currentDraftState.draftKey.orEmpty()} " +
                            "surfaceSendChannelId=${composerSurface.sendChannelId.orEmpty()} " +
                            "surfaceParentMessageId=${composerSurface.parentMessageId.orEmpty()}"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(
                            seedText = value,
                            seedAsTask = asTask
                        )
                    )
                    val nextDraftState = composerDraftViewModel.state.value
                    bridge.log(
                        "thread_composer_change_result instanceId=$composerInstanceId " +
                            "textLen=${nextDraftState.seedText.length} textBlank=${nextDraftState.seedText.isBlank()} " +
                            "asTask=${nextDraftState.seedAsTask} draftKey=${nextDraftState.draftKey.orEmpty()} " +
                            "activeRecords=${composerDraftPersistSlice.activeDraftRecordsState.value.toThreadComposerDraftDiagnosticsSummary()}"
                    )
                }
                "toggleAsTask" -> {
                    bridge.log(
                        "thread_composer_toggle_as_task instanceId=$composerInstanceId asTask=$asTask " +
                            "stateTextLen=${currentDraftState.seedText.length} stateTextBlank=${currentDraftState.seedText.isBlank()} " +
                            "draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(seedAsTask = asTask)
                    )
                }
                "send" -> {
                    val threadId = composerSurface.sendChannelId
                    val draftText = currentDraftState.seedText.trim()
                    val attachmentIds = currentComposer.pendingAttachments.readyThreadAttachmentIdsForSend()
                    val hasBlockingAttachmentUpload = currentComposer.pendingAttachments.hasBlockingThreadAttachmentUpload()
                    val gateBlockReason =
                        when {
                            !payload.canSend -> "payload_can_send_false"
                            threadId == null -> "missing_send_channel"
                            draftText.isBlank() && attachmentIds.isEmpty() && !hasBlockingAttachmentUpload -> "empty_draft"
                            else -> null
                        }
                    if (gateBlockReason != null) {
                        bridge.log(
                            "thread_composer_send_gate result=blocked reason=$gateBlockReason " +
                                "surface=${payload.surfaceKind} status=${payload.status} canSend=${payload.canSend} " +
                                "sourceThreadId=${payload.sourceThreadId.orEmpty()} streamChannelId=${payload.streamChannelId.orEmpty()} " +
                                "resolvedAuxiliaryThreadId=${resolvedAuxiliaryThreadId.orEmpty()} sendChannelId=${threadId.orEmpty()} " +
                                "parentChannelId=${payload.parentChannelId.orEmpty()} parentMessageId=${payload.parentMessageId.orEmpty()} " +
                                "composerStreamChannelId=${composerSurface.streamChannelId.orEmpty()} " +
                                "hasText=${draftText.isNotBlank()} attachments=${attachmentIds.size} " +
                                "blockingAttachments=$hasBlockingAttachmentUpload"
                        )
                        return@ThreadPageContent
                    }
                    bridge.log(
                        "thread_composer_send_gate result=allowed " +
                            "surface=${payload.surfaceKind} status=${payload.status} canSend=${payload.canSend} " +
                            "sourceThreadId=${payload.sourceThreadId.orEmpty()} streamChannelId=${payload.streamChannelId.orEmpty()} " +
                            "resolvedAuxiliaryThreadId=${resolvedAuxiliaryThreadId.orEmpty()} sendChannelId=${threadId.orEmpty()} " +
                            "parentChannelId=${payload.parentChannelId.orEmpty()} parentMessageId=${payload.parentMessageId.orEmpty()} " +
                            "composerStreamChannelId=${composerSurface.streamChannelId.orEmpty()} " +
                            "hasText=${draftText.isNotBlank()} attachments=${attachmentIds.size} " +
                            "blockingAttachments=$hasBlockingAttachmentUpload"
                    )
                    val draftAsTask = currentDraftState.seedAsTask
                    val pendingAttachments =
                        if (hasBlockingAttachmentUpload) {
                            currentComposer.pendingAttachments.withThreadAttachmentUploadWaitingState()
                        } else {
                            currentComposer.pendingAttachments
                        }
                    val messageBody =
                        threadComposerMessageBodyForSend(
                            draftText = draftText,
                            attachmentCount = pendingAttachments.size
                        )
                    val pendingContext =
                        KuiklyThreadPendingSendContext(
                            channelId = threadId ?: return@ThreadPageContent,
                            body = messageBody,
                            asTask = draftAsTask,
                            attachmentIds = attachmentIds,
                            attachments = pendingAttachments,
                            mentionChannelId = composerSurface.mentionScopeChannelId,
                            parentMessageId = composerSurface.parentMessageId,
                            streamChannelId = composerSurface.streamChannelId,
                            memberScopedMentions = payload.memberScopedMentions
                        )
                    val localMessageId = ThreadLocalSendNextId.value(composerInstanceId)
                    threadViewModel.dispatch(SharedThreadIntent.PrepareForLocalSend)
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
                    threadViewModel.dispatch(
                        SharedThreadIntent.ConsumeComposerSeedForSend(
                            sentBody = pendingContext.body,
                            sentAttachmentIds = pendingContext.attachmentIds.toSet()
                        )
                    )
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
                    val verb = if (action == "inviteMention") "add" else "notify"
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
                        if (delivered && verb == "add") {
                            refreshComposerMemberSuggestionsAfterMembershipMutation()
                        }
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
                                    bridge.toast("Attachment upload failed: ${threadFailureReasonLabel(errorCode) ?: errorCode}")
                                response == null ->
                                    bridge.toast("Attachment picker unavailable")
                            }
                            return@launch
                        }
                        if (action == "attachImage" && files.size == 1 && imageAnnotationEnabled()) {
                            openComposerImageTransfer(files.first())
                        } else {
                            uploadComposerAttachmentFiles(files)
                        }
                    }
                }
                "removeAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        attachmentUploadJobs[id]?.cancel()
                        attachmentUploadJobs = attachmentUploadJobs - id
                        attachmentUploadFilesByLocalId = attachmentUploadFilesByLocalId - id
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(id))
                    }
                }
                "retryAttachments" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        val file = attachmentUploadFilesByLocalId[id]
                        if (file == null) {
                            bridge.log(
                                "thread_composer_attachment_retry result=missing_file_ref attachmentId=$id"
                            )
                            bridge.toast("Attachment retry unavailable. Remove and attach again.")
                            return@ThreadPageContent
                        }
                        if (attachmentUploadJobs[id]?.isActive == true) {
                            bridge.log(
                                "thread_composer_attachment_retry result=already_uploading attachmentId=$id"
                            )
                            return@ThreadPageContent
                        }
                        bridge.log(
                            "thread_composer_attachment_retry result=start attachmentId=$id fileName=${file.fileName}"
                        )
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(id))
                        uploadComposerAttachmentFiles(listOf(file))
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
        markdownTaskCandidateModalState?.let { modalState ->
            ThreadMarkdownTaskCandidateModal(
                state = modalState,
                currentTaskChannelId = taskChannelId,
                onDismiss = { markdownTaskCandidateModalState = null },
                onSelect = { task ->
                    val loadingPayload = renderedThread.markdownTaskNumberLaunchPayload(task)
                    if (loadingPayload == null) {
                        bridge.toast("Unable to open #${modalState.taskNumber}")
                    } else {
                        currentServerId?.let { serverId ->
                            tasksDataStore?.upsertTask(serverId, task)
                        }
                        bridge.log(
                            "thread_markdown_task_select taskNumber=${modalState.taskNumber} taskId=${task.id.value} " +
                                "parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                                "parentMessageId=${loadingPayload.parentMessageId.orEmpty()} " +
                                "threadId=${loadingPayload.streamChannelId.orEmpty()}"
                        )
                        markdownTaskCandidateModalState = null
                        onOpenThreadPayload(loadingPayload)
                    }
                }
            )
        }
        textSelectionMessage?.let { message ->
            ThreadMessageTextSelectionDialog(
                message = message,
                onCopyAll = {
                    bridge.copyToPasteboard(message.body)
                    bridge.toast("Copied")
                },
                onDismiss = { textSelectionMessage = null }
            )
        }
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
            ThreadCreateAgentActionDialogHost(
                dialogState = dialogState,
                computersStore = computersStore,
                computerServerId = createAgentComputerServerId,
                computerServerResolved = createAgentComputerServerResolved,
                onAuthRequired = ::routeAuthRequired,
                onDismiss = {
                    if (!dialogState.submitting) {
                        createAgentDialogState = null
                    }
                },
                onSubmit = submitCreateAgent@{ request ->
                    if (dialogState.submitting) {
                        return@submitCreateAgent
                    }
                    val normalizedName = request.name.trim().removePrefix("@")
                    if (normalizedName.isBlank()) {
                        createAgentDialogState = dialogState.copy(errorCode = "agent_name_required")
                        return@submitCreateAgent
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

@Composable
private fun ThreadMarkdownTaskCandidateModal(
    state: ThreadMarkdownTaskCandidateModalState,
    currentTaskChannelId: ChannelId?,
    onDismiss: () -> Unit,
    onSelect: (TaskSummary) -> Unit
) {
    SlockUnifiedDialog(
        title = "OPEN TASK #${state.taskNumber}",
        onDismiss = onDismiss,
        minWidth = 300.dp,
        maxWidth = 360.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        SlockModalListShell(height = threadMarkdownTaskCandidateListHeight(state.candidates.size)) {
            if (state.candidates.isEmpty()) {
                item {
                    ThreadModalEmptyState(
                        title = "NO TASKS",
                        detail = "REFRESH THIS CONVERSATION AND TRY AGAIN."
                    )
                }
            } else {
                items(state.candidates.size, key = { state.candidates[it].id.value }) { index ->
                    val task = state.candidates[index]
                    ThreadMarkdownTaskCandidateRow(
                        task = task,
                        currentChannel = currentTaskChannelId != null && task.channelId == currentTaskChannelId,
                        onClick = { onSelect(task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMarkdownTaskCandidateRow(
    task: TaskSummary,
    currentChannel: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (currentChannel) SlockColor.SurfaceCream else SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = task.title.ifBlank { "Untitled task" },
                style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = task.threadMarkdownTaskCandidateSubtitle(currentChannel),
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = task.status.threadMarkdownTaskStatusLabel().uppercase(),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun TaskSummary.threadMarkdownTaskCandidateSubtitle(currentChannel: Boolean): String {
    val channel = channelName?.trim()?.takeIf { it.isNotBlank() }?.let { "#$it" } ?: "Unknown channel"
    return if (currentChannel) "$channel - current channel" else channel
}

private fun TaskStatus.threadMarkdownTaskStatusLabel(): String =
    when (this) {
        TaskStatus.Todo -> "Todo"
        TaskStatus.InProgress -> "Doing"
        TaskStatus.InReview -> "Review"
        TaskStatus.Done -> "Done"
        TaskStatus.Closed -> "Closed"
    }

private fun threadMarkdownTaskCandidateListHeight(count: Int): Dp =
    (count * 64 + 8).coerceIn(108, 288).dp

@Composable
private fun ThreadCreateAgentActionDialogHost(
    dialogState: ThreadCreateAgentActionDialogState,
    computersStore: SharedComputersStore?,
    computerServerId: String?,
    computerServerResolved: Boolean,
    onAuthRequired: (String?) -> Boolean,
    onDismiss: () -> Unit,
    onSubmit: (SharedCreateAgentDialogSubmit) -> Unit
) {
    val emptyComputerPickerSliceFlow = remember { MutableStateFlow(SharedComputersPickerSlice()) }
    val computerPickerSlice by remember(computersStore, computerServerId) {
        computersStore?.computerPickerSliceFlow(computerServerId) ?: emptyComputerPickerSliceFlow
    }.collectAsState(SharedComputersPickerSlice())

    LaunchedEffect(
        computersStore,
        dialogState.messageId,
        computerServerId,
        computerServerResolved
    ) {
        if (!computerServerResolved) return@LaunchedEffect
        val serverId = computerServerId
        if (serverId.isNullOrBlank()) return@LaunchedEffect
        computersStore?.dispatch(SharedComputersIntent.EnsureComputerPicker(serverId))
    }

    LaunchedEffect(dialogState.messageId, computerPickerSlice.errorCode) {
        val code = computerPickerSlice.errorCode ?: return@LaunchedEffect
        onAuthRequired(code)
    }

    val machinesErrorCode =
        when {
            computersStore != null &&
                computerServerResolved &&
                computerServerId.isNullOrBlank() -> "server_id_missing"
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
                (!computerServerResolved ||
                    computerPickerSlice.status == SharedComputersLoadStatus.InFlight),
        machinesErrorMessage = machinesErrorCode?.let(::threadCreateAgentMachinesErrorCopy),
        onDismiss = onDismiss,
        onSubmit = onSubmit
    )
}

private suspend fun openSharedThreadAttachment(
    bridge: build.raft.app.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    attachment: KuiklyThreadAttachmentItem,
    downloadId: String? = null,
    onProgress: ((build.raft.app.base.BridgeAttachmentDownloadProgress) -> Unit)? = null
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
                    mimeType = attachment.mimeType,
                    downloadId = downloadId,
                    onProgress = onProgress
                )
            when (nativeResult?.optString("status").orEmpty()) {
                "opened" -> ThreadAttachmentOpenResult(status = "opened")
                "cancelled" ->
                    ThreadAttachmentOpenResult(
                        status = "cancelled",
                        message = nativeResult?.optString("message").orEmpty().ifBlank { "Download stopped" }
                    )
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
    if (host.substringBefore(':').lowercase() !in setOf("app.slock.ai", "staging.slock.ai", "app.raft.build")) return null
    val pathAndQuery = if (pathStart >= 0) withoutScheme.substring(pathStart) else ""
    val path = pathAndQuery.substringBefore('?')
    val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    val segments = path.split('/').filter { it.isNotBlank() }
    val routeIndex = segments.indexOfFirst {
        val segment = it.lowercase()
        segment == "channel" || segment == "dm"
    }
    if (routeIndex < 0) return null
    val routeKind = segments.getOrNull(routeIndex)?.lowercase()
        ?.takeIf { it == "channel" || it == "dm" }
        ?: return null
    val channelId = segments.getOrNull(routeIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
    val threadParam = query.queryParamValue("thread")
    val threadParentMessageId =
        threadParam?.split(':', limit = 2)
            ?.takeIf { it.size == 2 && it[0] == channelId && it[1].isNotBlank() }
            ?.get(1)
            ?: if (threadParam == null) {
                null
            } else {
                return null
            }
    val messageId = query.queryParamValue("msg")?.takeIf { it.isNotBlank() } ?: threadParentMessageId
    return SlockThreadWebLinkTarget(
        channelId = channelId,
        messageId = messageId,
        routeKind = routeKind,
        threadParentMessageId = threadParentMessageId
    )
}

private fun String.queryParamValue(name: String): String? =
    split('&')
        .firstNotNullOfOrNull { part ->
            val pieces = part.split('=', limit = 2)
            pieces.getOrNull(1)?.takeIf { pieces.firstOrNull() == name && it.isNotBlank() }
        }
        ?.slockPercentDecode()

private fun String.slockPercentDecode(): String {
    if ('%' !in this && '+' !in this) return this
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val hex = substring(index + 1, index + 3)
            val decoded = hex.toIntOrNull(16)
            if (decoded != null) {
                builder.append(decoded.toChar())
                index += 3
                continue
            }
        }
        builder.append(if (char == '+') ' ' else char)
        index += 1
    }
    return builder.toString()
}

internal fun KuiklyThreadScreenPayload.webLinkLoadingPayload(target: SlockThreadWebLinkTarget): KuiklyThreadScreenPayload {
    val threadParentMessageId = target.threadParentMessageId?.takeIf { it.isNotBlank() }
    if (threadParentMessageId != null) {
        val focusedMessageId = target.messageId?.takeIf { it.isNotBlank() }
        val composer =
            buildThreadSurfaceComposerPayload(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                displayName = ThreadReplyPageTitle,
                fallbackId = threadParentMessageId,
                enabled = canSend,
                mentionChannelId = target.channelId,
                parentMessageId = threadParentMessageId,
                streamChannelId = null
            )
        return copy(
            instanceId = "shared-shell-thread-pending-$threadParentMessageId",
            title = ThreadReplyPageTitle,
            subtitle = replyThreadHeaderSubtitle(),
            status = "loading",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = null,
            streamChannelId = null,
            parentChannelId = target.channelId,
            parentMessageId = threadParentMessageId,
            parentSurfaceKind =
                if (target.routeKind == "dm") {
                    KuiklyThreadSurfaceKind.DirectMessage
                } else {
                    KuiklyThreadSurfaceKind.Channel
                },
            parentChannelKind =
                if (target.routeKind == "dm") {
                    KuiklyThreadSurfaceKind.DirectMessage
                } else {
                    KuiklyThreadSurfaceKind.Channel
                },
            originParentMessageId = threadParentMessageId,
            focusedMessageId = focusedMessageId,
            navigationLabel = "Back",
            selectedTab = KuiklyThreadTab.Chat.wireValue,
            showModeTabs = false,
            showReplyActions = false,
            showOpenInChannelAction = true,
            originMessage = null,
            originPreview = null,
            originStatus = "loading",
            originErrorCode = null,
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            errorCode = null,
            composer = composer
        ).withLaunchHeaderContext()
    }
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
        focusedMessageId = target.messageId?.takeIf { it.isNotBlank() },
        selectedTab = KuiklyThreadTab.Chat.wireValue,
        messages = emptyList(),
        originMessage = null,
        originPreview = null,
        originParentMessageId = null,
        originStatus = "none",
        originErrorCode = null,
        errorCode = null,
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
        val streamId = threadStreamId
        return copy(
            instanceId = streamId?.let { "shared-shell-thread-$it" } ?: "shared-shell-thread-pending-$parentMessageId",
            title = ThreadReplyPageTitle,
            subtitle = replyThreadHeaderSubtitle(),
            status = "loading",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = streamId,
            streamChannelId = streamId,
            parentChannelId = source.channelId,
            parentMessageId = parentMessageId,
            parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
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
                title = "Image preview",
                subtitle = null,
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
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
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
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
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

internal fun KuiklyThreadScreenPayload.replyThreadLoadingPayload(
    parentMessageId: String,
    parentChannelId: String?,
    threadChannelId: String?,
    focusedMessageId: String? = null
): KuiklyThreadScreenPayload {
    val knownParentMessageId = parentMessageId.takeIf { it.isNotBlank() }
    val knownThreadChannelId = threadChannelId?.takeIf { it.isNotBlank() }
    val resolvedParentSurfaceKind =
        when {
            surfaceKind == KuiklyThreadSurfaceKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            channelKind.equals("dm", ignoreCase = true) -> KuiklyThreadSurfaceKind.DirectMessage
            memberScopedMentions && parentChannelId.isNullOrBlank() -> KuiklyThreadSurfaceKind.DirectMessage
            else -> KuiklyThreadSurfaceKind.Channel
        }
    val resolvedParentChannelKind =
        if (resolvedParentSurfaceKind == KuiklyThreadSurfaceKind.Channel) {
            channelKind.normalizedThreadChannelKind()
        } else {
            null
        }
    val composer =
        buildThreadSurfaceComposerPayload(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            displayName = ThreadReplyPageTitle,
            fallbackId = knownParentMessageId.orEmpty(),
            enabled = canSend,
            mentionChannelId = parentChannelId,
            parentMessageId = knownParentMessageId,
            streamChannelId = knownThreadChannelId
        )
    return copy(
        instanceId = knownThreadChannelId?.let { "shared-shell-thread-$it" } ?: "shared-shell-thread-pending-$parentMessageId",
        title = ThreadReplyPageTitle,
        subtitle = replyThreadHeaderSubtitle(),
        status = "loading",
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        sourceThreadId = knownThreadChannelId,
        streamChannelId = knownThreadChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = knownParentMessageId,
        parentSurfaceKind = resolvedParentSurfaceKind,
        parentChannelKind = resolvedParentChannelKind,
        parentNavigationHint = ThreadParentNavigationPrevious,
        focusedMessageId = focusedMessageId?.takeIf { it.isNotBlank() },
        navigationLabel = "Close",
        selectedTab = "chat",
        showModeTabs = false,
        showReplyActions = false,
        showOpenInChannelAction = true,
        originMessage = null,
        originParentMessageId = knownParentMessageId,
        originStatus = "none",
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        emptyTitle = "Loading...",
        emptyDetail = "",
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
        this.parentSurfaceKind.threadLaunchParentSurfaceKindOrNull()
            ?: if (channelKind.equals("dm", ignoreCase = true) || memberScopedMentions && parentThreadId == null) {
                KuiklyThreadSurfaceKind.DirectMessage
            } else {
                KuiklyThreadSurfaceKind.Channel
            }
    val resolvedParentChannelKind =
        if (parentSurfaceKind == KuiklyThreadSurfaceKind.Channel) {
            parentChannelKind.normalizedThreadChannelKind()
                ?: channelKind.normalizedThreadChannelKind()
                ?: KuiklyThreadSurfaceKind.Channel
        } else {
            KuiklyThreadSurfaceKind.DirectMessage
        }
    return copy(
        instanceId = parentThreadId?.let { "shared-shell-thread-$it" }
            ?: focusedParentMessageId?.let { "shared-shell-parent-message-$it" }
            ?: "shared-shell-parent-message",
        title = SharedThreadHeaderContextStore.launchContext(parentSurfaceKind, null).title,
        subtitle = null,
        status = "loading",
        surfaceKind = parentSurfaceKind,
        channelKind = resolvedParentChannelKind,
        sourceThreadId = parentThreadId,
        streamChannelId = parentThreadId,
        parentChannelId = null,
        parentMessageId = null,
        parentSurfaceKind = null,
        parentChannelKind = null,
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
        composer =
            KuiklyThreadComposerPayload(
                surfaceKind = parentSurfaceKind,
                enabled = canSend,
                streamChannelId = parentThreadId
            ),
        emptyTitle = "Loading...",
        emptyDetail = ""
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

internal const val THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT = "default"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_DELETED_HUMAN = "deleted_human"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT = "rich_content"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_SELECTION = "share_selection"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_PREVIEW = "share_preview"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE = "long_inline_code"

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
                                    KuiklyThreadReactionItem(emoji = "👍", count = 2, selected = false)
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
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_DELETED_HUMAN ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Deleted human author fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
                                    KuiklyThreadReactionItem(emoji = "👀", count = 1, selected = false)
                                )
                        )
                    ),
                members =
                    listOf(
                        KuiklyThreadMemberItem(
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
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_SELECTION,
        THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_PREVIEW ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#design",
                subtitle = "Share selected messages fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                streamChannelId = "channel-design",
                sourceThreadId = "channel-design",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "msg-share-visual-a",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "06/27 09:20",
                            messageType = "Chat",
                            body = "Selected the two discussion points that should become a summarized handoff message."
                        ),
                        KuiklyThreadMessageItem(
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
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Long inline code fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "msg_long_inline_code_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorKind = "agent",
                            avatarLabel = "CI",
                            timestampLabel = "06/25 05:55",
                            messageType = "Chat",
                            body =
                                "Long inline code preview: `reply/channel-markdown/msg-inline-visual/" +
                                    "composer-draft-scroll-realtime-read-receipt/owner-key-consumes-thread-route-identity-only/" +
                                    "without-source-thread-fallback/max-width-wrap-proof-alpha-beta-gamma-delta-epsilon-zeta-eta-theta-iota-kappa-lambda-mu` " +
                                    "Command preview: `./gradlew :shared:testDebugUnitTest --tests build.raft.app.data.InboxUiStateTest` " +
                                    "and file preview: `compose/shared/src/commonTest/kotlin/build/raft/app/data/InboxUiStateTest.kt` " +
                                    "should wrap as inline code with the same yellow box and black frame style as short inline code.",
                            threadChannelId = "thread-msg-long-inline-code-visual"
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
                        )
                    ),
                showComposer = false,
                joined = true,
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
        channelId = channelId?.value,
        channelName = channelName,
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
    return if (action == "invite" || action == "add") {
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
        else -> "This mention action could not be applied. Refresh the thread and try again."
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
                previewUri = attachment.previewUri,
                status = attachment.status,
                errorCode = attachment.errorCode
            )
        } + draftState.uploadedAttachments)
            .distinctBy { attachment -> attachment.id }
            .filterNot { attachment -> attachment.id in draftState.removedAttachmentIds }
    return copy(
        instanceId = instanceId.ifBlank { renderedPayload.instanceId },
        seedText = draftState.seedText,
        seedAsTask = draftState.seedAsTask,
        syncNonce = draftState.syncNonce,
        focusRequestNonce = draftState.focusRequestNonce,
        pendingAttachments =
            pendingAttachments.map { attachment ->
                KuiklyPendingAttachmentItem(
                    id = attachment.id,
                    fileName = attachment.fileName,
                    sizeLabel = attachment.sizeLabel,
                    mimeType = attachment.mimeType,
                    previewUri = attachment.previewUri,
                    status = attachment.status,
                    errorCode = attachment.errorCode
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
    LaunchedEffect(tasksViewModel, startedTasksJoinThreadId, taskChannelId?.value) {
        val resolvedTaskChannelId = taskChannelId ?: return@LaunchedEffect
        if (startedTasksJoinThreadId != resolvedTaskChannelId.value) {
            return@LaunchedEffect
        }
        tasksViewModel ?: return@LaunchedEffect
        var latestLoggedKey = lastTasksJoinResultKey
        tasksViewModel.state.collect { tasksState ->
            val (result, taskCount) = tasksState.threadTasksJoinDiagnosticsResult() ?: return@collect
            val resultKey = "${resolvedTaskChannelId.value}:$result:$taskCount"
            if (latestLoggedKey == resultKey) {
                return@collect
            }
            latestLoggedKey = resultKey
            onLogged(resultKey)
            val durationMs = tasksJoinStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L
            log(
                "thread_tasks_join_result threadId=${threadRouteKey.orEmpty()} result=$result " +
                    "tasks=$taskCount durationMs=$durationMs"
            )
        }
    }
}

@Composable
private fun ThreadPageContent(
    threadInstanceKey: String,
    payload: KuiklyThreadScreenPayload,
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    headerDisplay: ThreadHeaderDisplayModel,
    composerSurface: ThreadComposerSurface,
    baseComposerState: KuiklyThreadComposerPayload,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    resolvedAuxiliaryThreadId: String?,
    tasksViewModel: SharedTasksViewModel?,
    taskChannelId: ChannelId?,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    filesViewModel: SharedThreadFilesViewModel,
    memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    contentExtrasVisible: Boolean,
    onTasksIntent: (SharedTasksRouteIntent) -> Unit,
    onFilesAuthRequired: (String?) -> Unit,
    onOpenFilesAttachment: (KuiklyThreadAttachmentItem, ThreadImageAttachmentGroup?) -> Unit,
    onDownloadFilesAttachment: (KuiklyThreadAttachmentItem) -> Unit,
    onOpenMemberProfile: (String, MemberKind?, String?) -> Unit,
    highlightedMentions: Set<String>,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    diagnosticsLogger: (String) -> Unit = {},
    pendingActionCardIds: Set<String> = emptySet(),
    pendingTaskConvertMessageIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    onExportShareImage: suspend (ThreadShareCaptureRect?) -> JSONObject?,
    onSaveShareImage: suspend (String) -> JSONObject?,
    onBack: () -> Unit,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit
) {
    val renderEarlyLoadingShell = payload.shouldRenderThreadEarlyLoadingShell(timelineSlice)
    val shareExportScope = rememberCoroutineScope()
    var selectedTab by remember(payload.selectedTab) {
        mutableStateOf(KuiklyThreadTab.fromWireValue(payload.selectedTab))
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
                "branch=${if (renderEarlyLoadingShell) "early_loading" else "tab_content"}"
        )
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
    var activeModal by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var selectedShareMessageIds by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    val selectedShareBounds = remember(threadInstanceKey) { mutableMapOf<String, ThreadShareCaptureRect>() }
    var sharePreviewImage by remember(threadInstanceKey) { mutableStateOf<ThreadSharePreviewImage?>(null) }
    var sharePreviewLoading by remember(threadInstanceKey) { mutableStateOf(false) }
    var shareCaptureMode by remember(threadInstanceKey) { mutableStateOf(false) }
    var shareCaptureTargetMessageId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    val shareSelectionActive = selectedShareMessageIds.isNotEmpty() && selectedTab == KuiklyThreadTab.Chat
    val selectedShareMessages =
        remember(timelineSlice, selectedShareMessageIds) {
            timelineSlice.threadSelectedShareMessages(selectedShareMessageIds)
        }
    val selectedShareMarkdown =
        remember(selectedShareMessages) {
            selectedShareMessages.threadShareMarkdown()
        }
    val clearShareSelection = {
        selectedShareMessageIds = emptySet()
        selectedShareBounds.clear()
        sharePreviewImage = null
        sharePreviewLoading = false
        shareCaptureMode = false
        shareCaptureTargetMessageId = null
        if (activeModal == "sharePreview") {
            activeModal = null
        }
    }
    fun selectedShareCaptureRect(): ThreadShareCaptureRect? {
        val bounds = selectedShareMessageIds.mapNotNull { selectedShareBounds[it] }
        if (bounds.isEmpty()) return null
        var left = bounds.first().x
        var top = bounds.first().y
        var right = bounds.first().x + bounds.first().width
        var bottom = bounds.first().y + bounds.first().height
        bounds.drop(1).forEach { rect ->
            if (rect.x < left) left = rect.x
            if (rect.y < top) top = rect.y
            if (rect.x + rect.width > right) right = rect.x + rect.width
            if (rect.y + rect.height > bottom) bottom = rect.y + rect.height
        }
        return ThreadShareCaptureRect(
            x = left,
            y = top,
            width = (right - left).coerceAtLeast(1f),
            height = (bottom - top).coerceAtLeast(1f)
        )
    }
    suspend fun captureSelectedShareFragments(): ThreadShareCaptureRect? {
        val fragments = mutableListOf<String>()
        selectedShareMessages.forEach { message ->
            selectedShareBounds.remove(message.id)
            val targetIndex = timelineSlice.threadMessageListItemIndex(message.id)
            if (targetIndex < 0) return@forEach
            shareCaptureTargetMessageId = message.id
            var attempts = 0
            while (attempts < 24 && selectedShareBounds[message.id] == null) {
                delay(32)
                attempts += 1
            }
            val rect = selectedShareBounds[message.id] ?: return@forEach
            val result = runCatching { onExportShareImage(rect.copy(frame = false)) }.getOrNull()
            val path = result?.optString("path").orEmpty()
            if (result?.optString("status") == "ok" && path.isNotBlank()) {
                fragments += path
            }
        }
        if (fragments.isEmpty()) return null
        return ThreadShareCaptureRect(
            x = 0f,
            y = 0f,
            width = 0f,
            height = 0f,
            path = fragments.joinToString(separator = "\n")
        )
    }
    var composerKeyboardInsetDp by remember(threadInstanceKey) { mutableStateOf(0f) }
    val composerBottomSafeArea = slockBottomSafeAreaPadding()
    val showComposerForSelectedTab =
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
    val openMarkdownTag: (ThreadMarkdownTag) -> Unit = { tag ->
        when (tag.kind) {
            ThreadMarkdownTagKind.UserMention -> {
                onThreadEvent("openMarkdownTag", tag.label, null, tag.kind.name)
            }
            ThreadMarkdownTagKind.Task -> {
                val taskId = timelineSlice.findMarkdownTaskId(tag, linkedTasksProjection)
                if (!taskId.isNullOrBlank()) {
                    onThreadEvent("openTask", taskId, null, null)
                } else {
                    onThreadEvent("openMarkdownTaskNumber", tag.label, null, null)
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
    val knownMarkdownMentionNames =
        remember(payload.members, payload.memberCandidates, payload.originMessage, payload.messages) {
            payload.toKnownMarkdownMentionNameSet()
        }
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean =
        remember(knownMarkdownChannelNames, knownMarkdownMentionNames) {
            { tag -> tag.isKnownThreadMarkdownTag(knownMarkdownChannelNames, knownMarkdownMentionNames) }
        }
    val resolveMarkdownTagLabel =
        remember(payload.members) {
            { tag: ThreadMarkdownTag -> payload.resolveMarkdownTagDisplayLabel(tag) }
        }
    val timelineRenderConfig =
        remember(
            payload.backgroundRefreshing,
            payload.errorCode,
            payload.originPreview,
            payload.originErrorCode,
            payload.localOffsetMinutes,
            payload.todayEpochDay,
            payload.nowEpochMillis,
            payload.timeFormat,
            payload.members
        ) {
            payload.toThreadTimelineRenderConfig()
        }
    val selectThreadTab: (KuiklyThreadTab) -> Unit = { tab ->
        selectedTab = tab
        if (tab != KuiklyThreadTab.Chat) {
            clearShareSelection()
        }
        onThreadEvent("selectTab", null, null, tab.wireValue)
    }
    val renderThreadTabContent: @Composable (KuiklyThreadTab, Modifier) -> Unit = { tab, contentModifier ->
        Box(
            modifier = contentModifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
        ) {
            if (renderEarlyLoadingShell) {
                ThreadStateMessage("Loading...", null)
            } else when (tab) {
                KuiklyThreadTab.Chat ->
                    ThreadChatTab(
                        threadInstanceKey = threadInstanceKey,
                        renderConfig = timelineRenderConfig,
                        timelineSlice = timelineSlice,
                        replyMetadataByParentId = replyMetadataByParentId,
                        composerDraftPersistSlice = composerDraftPersistSlice,
                        composerKeyboardInsetDp = composerKeyboardInsetDp,
                        linkedTasksProjection = linkedTasksProjection,
                        highlightedMentions = highlightedMentions,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        pendingActionCardIds = pendingActionCardIds,
                        pendingTaskConvertMessageIds = pendingTaskConvertMessageIds,
                        attachmentOpenStates = attachmentOpenStates,
                        attachmentCommentsEnabled = attachmentCommentsEnabled,
                        currentServerSlug = currentServerSlug,
                        contentExtrasVisible = contentExtrasVisible,
                        isMarkdownTagValid = isMarkdownTagValid,
                        resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                        onThreadEvent = onThreadEvent,
                        onOpenMember = { memberId -> onOpenMemberProfile(memberId, null, null) },
                        onMentionAuthor = { handle ->
                            if (showComposerForSelectedTab) {
                                composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.InsertMention(handle))
                            }
                        },
                        onOpenMarkdownTag = openMarkdownTag,
                        onOpenMarkdownUrl = openMarkdownUrl,
                        diagnosticsLogger = diagnosticsLogger,
                        shareSelectionActive = shareSelectionActive && !shareCaptureMode,
                        shareSelectionCaptureActive = shareCaptureMode,
                        shareSelectionCaptureAnchorMessageId =
                            shareCaptureTargetMessageId ?: selectedShareMessages.firstOrNull()?.id,
                        selectedShareMessageIds = selectedShareMessageIds,
                        onShareSelectionBounds = { messageId, rect ->
                            if (rect == null) {
                                selectedShareBounds.remove(messageId)
                            } else {
                                selectedShareBounds[messageId] = rect
                            }
                        },
                        onEnterShareSelection = { messageId ->
                            selectedShareMessageIds = setOf(messageId)
                            activeModal = null
                        },
                        onToggleShareSelection = { messageId ->
                            selectedShareMessageIds =
                                if (messageId in selectedShareMessageIds) {
                                    selectedShareMessageIds - messageId
                                } else {
                                    selectedShareMessageIds + messageId
                                }
                        },
                        onThreadContentReady = {}
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
                        threadInstanceKey = threadInstanceKey,
                        basePayload = payload,
                        filesStartKey = resolvedAuxiliaryThreadId,
                        filesViewModel = filesViewModel,
                        attachmentOpenStates = attachmentOpenStates,
                        onAuthRequired = onFilesAuthRequired,
                        onOpenAttachment = onOpenFilesAttachment,
                        onDownloadAttachment = onDownloadFilesAttachment,
                        onOpenFileSource = { fileItem -> onThreadEvent("openFileSource", fileItem.attachmentId, null, null) }
                    )
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(
            modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
        ) {
            ThreadPageHeader(
                payload = payload,
                headerDisplay = headerDisplay,
                memberAvatarResolver = memberAvatarResolver,
                onBack = onBack,
                onOpenInChannel = {
                    onThreadEvent("openParentMessage", payload.originParentMessageId, null, null)
                },
                onOpenMemberProfile = onOpenMemberProfile,
                onShowMembers = {
                    activeModal = "members"
                    onThreadEvent("loadMembers", null, null, null)
                },
                onShowSettings = {
                    activeModal = "settings"
                }
            )
            if (payload.showModeTabs) {
                SlockTabbedScaffold(
                    tabs = availableTabs.map { it.toSlockTabSpec() },
                    selectedKey = selectedTab,
                    onSelectedKeyChange = selectThreadTab,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    itemKeyPrefix = "thread-tab",
                    content = renderThreadTabContent
                )
            } else {
                renderThreadTabContent(
                    selectedTab,
                    Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceWhite)
                )
            }
            if (!payload.joined) {
                ThreadJoinFooter(
                    label = payload.joinLabel,
                    onJoin = { onThreadEvent("joinChannel", null, null, null) }
                )
            } else if (shareSelectionActive) {
                if (!shareCaptureMode) {
                    ThreadShareSelectionToolbar(
                        selectedCount = selectedShareMessages.size,
                        onCancel = clearShareSelection,
                        onShare = {
                            if (selectedShareMessages.isNotEmpty() && !sharePreviewLoading) {
                                sharePreviewLoading = true
                                sharePreviewImage = null
                                selectedShareBounds.clear()
                                shareCaptureMode = true
                                shareExportScope.launch {
                                    var result: JSONObject? = null
                                    try {
                                        val captureRect =
                                            if (selectedShareMessages.size > 1) {
                                                captureSelectedShareFragments()
                                            } else {
                                                var attempts = 0
                                                while (
                                                    attempts < 24 &&
                                                        selectedShareMessageIds.any { selectedShareBounds[it] == null }
                                                ) {
                                                    delay(32)
                                                    attempts += 1
                                                }
                                                selectedShareCaptureRect()
                                            }
                                        result = runCatching { onExportShareImage(captureRect) }.getOrNull()
                                    } finally {
                                        shareCaptureMode = false
                                        shareCaptureTargetMessageId = null
                                        sharePreviewLoading = false
                                    }
                                    if (result?.optString("status") == "ok") {
                                        sharePreviewImage =
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
                        onCopyMarkdown = {
                            if (selectedShareMarkdown.isNotBlank()) {
                                onThreadEvent("copyShareSelectionMarkdown", selectedShareMarkdown, null, null)
                            }
                        }
                    )
                }
            } else if (payload.hasRenderableContentForComposer(timelineSlice) && showComposerForSelectedTab) {
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
                            timelineSlice = timelineSlice,
                            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
                            surface = composerSurface,
                            memberSuggestionsFlow = memberSuggestionsFlow,
                            channelSuggestionsFlow = channelSuggestionsFlow,
                            agentStatusResolver = agentStatusResolver,
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
        if (activeModal == "addMember") {
            ThreadMembersModal(
                members = payload.members,
                membersCount = payload.membersCount,
                canAddMembers = payload.canAddMembers,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                onDismiss = { activeModal = null },
                onAddMember = {
                    activeModal = "addMember"
                },
                onOpenMember = { member ->
                    onOpenMemberProfile(member.id, member.threadMemberKind(), member.sourceServerId)
                },
                removeMemberStatus = payload.removeMemberStatus,
                removingMemberId = payload.removingMemberId,
                removeMemberErrorCode = payload.removeMemberErrorCode,
                onRemoveMember = { memberId ->
                    onThreadEvent("removeMember", memberId, null, null)
                }
            )
        }
        when (activeModal) {
            "members" ->
                ThreadMembersModal(
                    members = payload.members,
                    membersCount = payload.membersCount,
                    canAddMembers = payload.canAddMembers,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    onDismiss = { activeModal = null },
                    onAddMember = {
                        activeModal = "addMember"
                    },
                    onOpenMember = { member ->
                        onOpenMemberProfile(member.id, member.threadMemberKind(), member.sourceServerId)
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
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    addMemberStatus = payload.addMemberStatus,
                    addMemberErrorCode = payload.addMemberErrorCode,
                    onDismiss = { activeModal = "members" },
                    onSubmit = { member ->
                        onThreadEvent("submitAddMember", member.id, null, member.kind)
                    }
                )
            "settings" ->
                ThreadSettingsModal(
                    channelName = headerDisplay.title,
                    subtitle = headerDisplay.subtitle,
                    actions = payload.settingsActions,
                    onDismiss = { activeModal = null },
                    onOpenAction = { actionId ->
                        activeModal = null
                        onThreadEvent("settingsAction", actionId, null, null)
                    }
                )
            "sharePreview" ->
                ThreadSharePreviewModal(
                    messages = selectedShareMessages,
                    image = sharePreviewImage,
                    onDismiss = { activeModal = null },
                    onCopyMarkdown = {
                        if (selectedShareMarkdown.isNotBlank()) {
                            onThreadEvent("copyShareSelectionMarkdown", selectedShareMarkdown, null, null)
                        }
                    },
                    onDownloadImage = {
                        val imagePath = sharePreviewImage?.path
                        if (!imagePath.isNullOrBlank()) {
                            shareExportScope.launch {
                                val result = runCatching { onSaveShareImage(imagePath) }.getOrNull()
                                onThreadEvent(
                                    if (result?.optString("status") == "ok") {
                                        "shareSelectionImageSaved"
                                    } else {
                                        "shareSelectionExportFailed"
                                    },
                                    result?.optString("message")?.takeIf { it.isNotBlank() },
                                    null,
                                    null
                                )
                            }
                        }
                    }
                )
        }
    }
}

@Composable
private fun ThreadPageHeader(
    payload: KuiklyThreadScreenPayload,
    headerDisplay: ThreadHeaderDisplayModel = payload.threadHeaderDisplayModel(null),
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onBack: () -> Unit,
    onOpenInChannel: () -> Unit,
    onOpenMemberProfile: (String, MemberKind?, String?) -> Unit,
    onShowMembers: () -> Unit,
    onShowSettings: () -> Unit
) {
    val topSafeArea = slockTopSafeAreaPadding()
    val resolvedDmPeerAvatarWireValue =
        rememberSlockMemberAvatarWireValue(
            memberAvatarResolver,
            headerDisplay.dmPeerMemberId?.takeIf { it.isNotBlank() }
        )
    Column(modifier = Modifier.fillMaxWidth().height(topSafeArea + SlockSize.PageHeaderHeight).background(SlockColor.SurfaceWhite)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .height(topSafeArea + SlockSize.PageHeaderContentHeight)
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
            if (headerDisplay.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) {
                ThreadDmPeerAvatarTile(
                    avatarWireValue = resolvedDmPeerAvatarWireValue ?: headerDisplay.dmPeerAvatarWireValue,
                    avatarLabel = headerDisplay.dmPeerAvatarLabel,
                    gravatarUrl = headerDisplay.dmPeerGravatarUrl,
                    onClick =
                        headerDisplay.dmPeerMemberId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { memberId -> { onOpenMemberProfile(memberId, null, null) } }
                )
            } else if (payload.showModeTabs) {
                ThreadChannelTile(headerDisplay.channelKind)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = headerDisplay.title,
                        style = SlockType.ButtonBold.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                headerDisplay.subtitle?.takeIf { it.isNotBlank() }?.let {
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
    gravatarUrl: String? = null,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier =
            Modifier
                .size(ThreadHeaderChannelTileSize)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        SlockAvatar(
            avatarWireValue = avatarWireValue,
            label = avatarLabel,
            avatarSize = ThreadHeaderChannelTileSize,
            borderWidth = SlockStroke.Default,
            gravatarUrl = gravatarUrl
        )
    }
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
    timelineSlice: ThreadTimelineSlice,
    resolvedAuxiliaryThreadId: String?,
    surface: ThreadComposerSurface,
    memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    agentStatusResolver: SlockAgentStatusResolver,
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
        threadMessages = timelineSlice.messages,
        prioritizeThreadParticipants = renderedPayload.shouldPrioritizeThreadMentionParticipants(),
        memberSuggestionsFlow = memberSuggestionsFlow,
        channelSuggestionsFlow = channelSuggestionsFlow,
        agentStatusResolver = agentStatusResolver,
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
    threadMessages: List<KuiklyThreadMessageItem>,
    prioritizeThreadParticipants: Boolean,
    memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    agentStatusResolver: SlockAgentStatusResolver,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit,
    contentPaddingTop: Dp,
    contentPaddingBottom: Dp
) {
    val memberSuggestionSlice by memberSuggestionsFlow.collectAsState(SharedMembersMemberSuggestionSlice())
    val channelSuggestionSlice by channelSuggestionsFlow.collectAsState(SharedMembersChannelSuggestionSlice())
    val loadedMemberSuggestions =
        remember(memberSuggestionSlice.suggestions, threadMessages, prioritizeThreadParticipants) {
            memberSuggestionSlice.suggestions
                .sortForThreadMentionParticipants(
                    threadMessages = threadMessages,
                    prioritizeThreadParticipants = prioritizeThreadParticipants
                )
                .map { suggestion ->
                    KuiklyComposerMemberItem(
                        displayName = suggestion.displayName,
                        handle = suggestion.handle,
                        inMentionChannel = suggestion.inMentionChannel,
                        sourceServerLabel = suggestion.sourceServerLabel,
                        kind = suggestion.kind,
                        description = suggestion.description,
                        avatarWireValue = suggestion.avatarWireValue,
                        gravatarHash = suggestion.gravatarHash,
                        avatarLabel = suggestion.avatarLabel,
                        memberId = suggestion.memberId
                    )
                }
        }
    val loadedChannelSuggestions =
        remember(channelSuggestionSlice.suggestions) {
            channelSuggestionSlice.suggestions.map { suggestion ->
                KuiklyComposerChannelItem(
                    name = suggestion.name,
                    subtitle = suggestion.subtitle
                )
            }
        }
    var retainedMemberSuggestions by remember(surface) { mutableStateOf(emptyList<KuiklyComposerMemberItem>()) }
    var retainedChannelSuggestions by remember(surface) { mutableStateOf(emptyList<KuiklyComposerChannelItem>()) }
    LaunchedEffect(loadedMemberSuggestions, memberSuggestionSlice.status) {
        if (loadedMemberSuggestions.isNotEmpty() || memberSuggestionSlice.status == SharedMembersLoadStatus.Loaded) {
            retainedMemberSuggestions = loadedMemberSuggestions
        }
    }
    LaunchedEffect(loadedChannelSuggestions, channelSuggestionSlice.status) {
        if (loadedChannelSuggestions.isNotEmpty() || channelSuggestionSlice.status == SharedMembersLoadStatus.Loaded) {
            retainedChannelSuggestions = loadedChannelSuggestions
        }
    }
    val renderedMemberSuggestions =
        loadedMemberSuggestions.takeIf { it.isNotEmpty() || memberSuggestionSlice.status == SharedMembersLoadStatus.Loaded }
            ?: retainedMemberSuggestions.takeIf { it.isNotEmpty() }
            ?: state.memberSuggestions
    val renderedChannelSuggestions =
        loadedChannelSuggestions.takeIf { it.isNotEmpty() || channelSuggestionSlice.status == SharedMembersLoadStatus.Loaded }
            ?: retainedChannelSuggestions.takeIf { it.isNotEmpty() }
            ?: state.channelSuggestions
    val renderedState =
        remember(state, memberSuggestionSlice, renderedMemberSuggestions, channelSuggestionSlice, renderedChannelSuggestions) {
            state.copy(
                memberSuggestions = renderedMemberSuggestions,
                memberSuggestionsStatus = memberSuggestionSlice.status,
                memberSuggestionsErrorCode = memberSuggestionSlice.errorCode,
                channelSuggestions = renderedChannelSuggestions,
                channelSuggestionsStatus = channelSuggestionSlice.status,
                channelSuggestionsErrorCode = channelSuggestionSlice.errorCode
            )
        }
    ThreadComposerContent(
        surface = surface,
        state = renderedState,
        onEvent = onEvent,
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = contentPaddingTop,
        contentPaddingBottom = contentPaddingBottom,
        agentStatusResolver = agentStatusResolver
    )
}

internal fun KuiklyThreadScreenPayload.shouldPrioritizeThreadMentionParticipants(): Boolean =
    parentChannelId.nonBlank() != null && surfaceKind != KuiklyThreadSurfaceKind.DirectMessage

internal fun List<SharedMembersMemberSuggestion>.sortForThreadMentionParticipants(
    threadMessages: List<KuiklyThreadMessageItem>,
    prioritizeThreadParticipants: Boolean
): List<SharedMembersMemberSuggestion> {
    if (!prioritizeThreadParticipants || threadMessages.isEmpty()) return this
    val ranks = threadMessages.recentThreadParticipantRanks()
    if (ranks.isEmpty()) return this
    fun List<SharedMembersMemberSuggestion>.sortGroup(): List<SharedMembersMemberSuggestion> =
        withIndex()
            .sortedWith(
                compareBy<IndexedValue<SharedMembersMemberSuggestion>>(
                    { indexed -> indexed.value.threadParticipantRank(ranks) },
                    { indexed -> indexed.index }
                )
            )
            .map { indexed -> indexed.value }
    val inChannel = filter { it.inMentionChannel }.sortGroup()
    val notInChannel = filterNot { it.inMentionChannel }.sortGroup()
    return inChannel + notInChannel
}

private fun List<KuiklyThreadMessageItem>.recentThreadParticipantRanks(): Map<String, Int> {
    val ranks = LinkedHashMap<String, Int>()
    val seenAuthorIds = mutableSetOf<String>()
    var nextRank = 0
    for (message in asReversed()) {
        val authorId = message.authorId.trim().takeIf { it.isNotBlank() } ?: continue
        if (!seenAuthorIds.add(authorId)) continue
        val rank = nextRank
        nextRank += 1
        ranks[authorId.threadParticipantIdKey()] = rank
        message.authorHandle.threadParticipantHandleKey()?.let { handleKey ->
            ranks.putIfAbsentCompat(handleKey, rank)
        }
    }
    return ranks
}

private fun SharedMembersMemberSuggestion.threadParticipantRank(ranks: Map<String, Int>): Int =
    threadParticipantKeys()
        .asSequence()
        .mapNotNull { key -> ranks[key] }
        .minOrNull()
        ?: Int.MAX_VALUE

private fun SharedMembersMemberSuggestion.threadParticipantKeys(): List<String> =
    buildList {
        memberId?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.threadParticipantIdKey()) }
        handle.threadParticipantHandleKey()?.let { add(it) }
    }

private fun String.threadParticipantIdKey(): String = "id:${trim()}"

private fun String?.threadParticipantHandleKey(): String? =
    this
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.let { "handle:$it" }

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
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
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
        items = tabs.map { it.toSlockTabSpec().toTopTabBarItem() },
        selectedKey = selectedTab.toString(),
        onSelectedKeyChange = { key ->
            tabs.firstOrNull { it.toString() == key }?.let(onSelect)
        },
        itemKeyPrefix = "thread-tab"
    )
}

@Composable
private fun ThreadChatTab(
    threadInstanceKey: String,
    renderConfig: ThreadTimelineRenderConfig,
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    composerKeyboardInsetDp: Float,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    highlightedMentions: Set<String>,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    pendingActionCardIds: Set<String> = emptySet(),
    pendingTaskConvertMessageIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onMentionAuthor: (String) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
    shareSelectionActive: Boolean = false,
    shareSelectionCaptureActive: Boolean = false,
    shareSelectionCaptureAnchorMessageId: String? = null,
    selectedShareMessageIds: Set<String> = emptySet(),
    onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit = { _, _ -> },
    onEnterShareSelection: (String) -> Unit = {},
    onToggleShareSelection: (String) -> Unit = {},
    onThreadContentReady: () -> Unit = {}
) {
    val activeDraftRecords by composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
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
                    renderConfig = renderConfig,
                    timelineSlice = timelineSlice,
                    replyMetadataByParentId = replyMetadataByParentId,
                    activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksProjection = linkedTasksProjection,
                    highlightedMentions = highlightedMentions,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    pendingActionCardIds = pendingActionCardIds,
                    pendingTaskConvertMessageIds = pendingTaskConvertMessageIds,
                    attachmentOpenStates = attachmentOpenStates,
                    attachmentCommentsEnabled = attachmentCommentsEnabled,
                    currentServerSlug = currentServerSlug,
                    contentExtrasVisible = contentExtrasVisible,
                    isMarkdownTagValid = isMarkdownTagValid,
                    resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                    onThreadEvent = onThreadEvent,
                    onOpenMember = onOpenMember,
                    onMentionAuthor = onMentionAuthor,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger,
                    shareSelectionActive = shareSelectionActive,
                    shareSelectionCaptureActive = shareSelectionCaptureActive,
                    shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                    selectedShareMessageIds = selectedShareMessageIds,
                    onShareSelectionBounds = onShareSelectionBounds,
                    onEnterShareSelection = onEnterShareSelection,
                    onToggleShareSelection = onToggleShareSelection,
                    onThreadContentReady = onThreadContentReady
                )
            } else {
                ThreadStateMessage("Loading...", null)
            }
        "error" ->
            ThreadStateActionMessage(
                title = "Failed to load thread",
                detail = renderConfig.errorCode ?: "unknown_error",
                actionLabel = "Retry",
                onClick = { onThreadEvent("retryThreadLoad", null, null, null) }
            )
        "no_permission" -> ThreadStateMessage("No permission", renderConfig.errorCode ?: "missing capability")
        "empty" ->
            if (timelineSlice.originStatus != "none") {
                ThreadMessagesList(
                    threadInstanceKey = threadInstanceKey,
                    renderConfig = renderConfig,
                    timelineSlice = timelineSlice,
                    replyMetadataByParentId = replyMetadataByParentId,
                    activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksProjection = linkedTasksProjection,
                    highlightedMentions = highlightedMentions,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    pendingActionCardIds = pendingActionCardIds,
                    pendingTaskConvertMessageIds = pendingTaskConvertMessageIds,
                    attachmentOpenStates = attachmentOpenStates,
                    attachmentCommentsEnabled = attachmentCommentsEnabled,
                    currentServerSlug = currentServerSlug,
                    contentExtrasVisible = contentExtrasVisible,
                    isMarkdownTagValid = isMarkdownTagValid,
                    resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                    onThreadEvent = onThreadEvent,
                    onOpenMember = onOpenMember,
                    onMentionAuthor = onMentionAuthor,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger,
                    shareSelectionActive = shareSelectionActive,
                    shareSelectionCaptureActive = shareSelectionCaptureActive,
                    shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                    selectedShareMessageIds = selectedShareMessageIds,
                    onShareSelectionBounds = onShareSelectionBounds,
                    onEnterShareSelection = onEnterShareSelection,
                    onToggleShareSelection = onToggleShareSelection,
                    onThreadContentReady = onThreadContentReady
                )
            } else {
                LaunchedEffect(threadInstanceKey, timelineSlice.status) {
                    onThreadContentReady()
                }
                ThreadStateMessage(timelineSlice.emptyTitle, timelineSlice.emptyDetail)
            }
        else ->
            ThreadMessagesList(
                threadInstanceKey = threadInstanceKey,
                renderConfig = renderConfig,
                timelineSlice = timelineSlice,
                replyMetadataByParentId = replyMetadataByParentId,
                activeReplyDraftParentMessageIds = activeReplyDraftParentMessageIds,
                composerKeyboardInsetDp = composerKeyboardInsetDp,
                linkedTasksProjection = linkedTasksProjection,
                highlightedMentions = highlightedMentions,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                pendingActionCardIds = pendingActionCardIds,
                pendingTaskConvertMessageIds = pendingTaskConvertMessageIds,
                attachmentOpenStates = attachmentOpenStates,
                attachmentCommentsEnabled = attachmentCommentsEnabled,
                currentServerSlug = currentServerSlug,
                contentExtrasVisible = contentExtrasVisible,
                isMarkdownTagValid = isMarkdownTagValid,
                resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                onThreadEvent = onThreadEvent,
                onOpenMember = onOpenMember,
                onMentionAuthor = onMentionAuthor,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                diagnosticsLogger = diagnosticsLogger,
                shareSelectionActive = shareSelectionActive,
                shareSelectionCaptureActive = shareSelectionCaptureActive,
                shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                selectedShareMessageIds = selectedShareMessageIds,
                onShareSelectionBounds = onShareSelectionBounds,
                onEnterShareSelection = onEnterShareSelection,
                onToggleShareSelection = onToggleShareSelection,
                onThreadContentReady = onThreadContentReady
            )
    }
}

private data class ThreadMarkdownPreparsePlan(
    val key: String,
    val bodies: List<String>
)

private fun ThreadTimelineSlice.threadInitialMarkdownPreparsePlan(
    contentExtrasVisible: Boolean
): ThreadMarkdownPreparsePlan {
    if (!contentExtrasVisible || messages.isEmpty()) {
        return ThreadMarkdownPreparsePlan(key = "", bodies = emptyList())
    }
    val latestBodies =
        messages
            .takeLast(ThreadInitialMarkdownPreparseMessageCount)
            .map { it.body }
    val fullRendererContents =
        latestBodies
            .mapNotNull { body -> body.preparedSlockMarkdownFullRendererContentOrNull() }
            .distinct()
    if (fullRendererContents.isEmpty()) {
        return ThreadMarkdownPreparsePlan(key = "", bodies = emptyList())
    }
    return ThreadMarkdownPreparsePlan(
        key = fullRendererContents.joinToString(separator = "\u001f"),
        bodies = latestBodies
    )
}

@Composable
private fun ThreadMessagesList(
    threadInstanceKey: String,
    renderConfig: ThreadTimelineRenderConfig,
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    activeReplyDraftParentMessageIds: Set<String>,
    composerKeyboardInsetDp: Float,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    highlightedMentions: Set<String>,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    pendingActionCardIds: Set<String> = emptySet(),
    pendingTaskConvertMessageIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onMentionAuthor: (String) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
    shareSelectionActive: Boolean = false,
    shareSelectionCaptureActive: Boolean = false,
    shareSelectionCaptureAnchorMessageId: String? = null,
    selectedShareMessageIds: Set<String> = emptySet(),
    onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit = { _, _ -> },
    onEnterShareSelection: (String) -> Unit = {},
    onToggleShareSelection: (String) -> Unit = {},
    onThreadContentReady: () -> Unit = {}
) {
    if (timelineSlice.messages.isEmpty() && timelineSlice.originStatus == "none") {
        LaunchedEffect(threadInstanceKey, timelineSlice.status) {
            onThreadContentReady()
        }
        ThreadStateMessage(timelineSlice.emptyTitle, timelineSlice.emptyDetail)
        return
    }
    val messageIndexSignature =
        remember(timelineSlice.messages) {
            timelineSlice.messageIndexSignature()
        }
    val focusedMessageIndex =
        remember(timelineSlice.focusedMessageId, messageIndexSignature) {
            timelineSlice.focusedMessageIndex()
        }
    val renderPayload = timelineSlice
    val showReplyEmptyState = renderPayload.messages.isEmpty() && renderPayload.originStatus != "none"
    val lazyItemCount =
        remember(
            renderPayload.originStatus,
            renderPayload.canLoadMoreBefore,
            renderPayload.messages.size,
            renderPayload.canLoadMoreAfter,
            showReplyEmptyState
        ) {
            threadMessagesLazyItemCount(
                originStatus = renderPayload.originStatus,
                showHistoryBoundary = renderPayload.hasThreadHistoryBoundaryItem(),
                messagesCount = renderPayload.messages.size,
                canLoadMoreAfter = renderPayload.canLoadMoreAfter,
                showReplyEmptyState = showReplyEmptyState,
                showBottomScrollPadding = true
            )
        }
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
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex =
            threadInitialFirstVisibleItemIndex(latestItemIndex = latestItemIndex)
    )
    val captureMessageAnchors = !listState.isScrollInProgress
    val coroutineScope = rememberCoroutineScope()
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
    var firstViewportProbeActive by remember(threadInstanceKey) { mutableStateOf(true) }
    var actionMessageId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var actionMenuAnchor by remember(threadInstanceKey) { mutableStateOf(Offset(16f, 16f)) }
    var taskStatusMenuMessageId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var taskStatusMenuAnchor by remember(threadInstanceKey) { mutableStateOf(Offset(16f, 16f)) }
    var allowBottomAutoAnchor by remember(threadInstanceKey) {
        mutableStateOf(true)
    }
    var initialBottomAnchorReady by remember(threadInstanceKey) {
        mutableStateOf(false)
    }
    var latestLocalSendBottomAnchorActiveKey by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var latestLocalSendBottomAnchorStartUserScrollGeneration by remember(threadInstanceKey) { mutableStateOf(0) }
    var userScrollGeneration by remember(threadInstanceKey) { mutableStateOf(0) }
    var lastComposerKeyboardInsetDp by remember(threadInstanceKey) { mutableStateOf(composerKeyboardInsetDp) }
    var pendingKeyboardBottomAnchor by remember(threadInstanceKey) { mutableStateOf(false) }
    var activeFocusedMessageId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var consumedFocusedMessageId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
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
    val initialMarkdownPreparsePlan =
        remember(timelineSlice.initialMarkdownPreparseCandidateSignature(contentExtrasVisible), contentExtrasVisible) {
            renderPayload.threadInitialMarkdownPreparsePlan(contentExtrasVisible)
        }
    var readyMarkdownPreparseKeys by remember(threadInstanceKey) { mutableStateOf(emptySet<String>()) }
    val initialMarkdownPreparseReady =
        initialMarkdownPreparsePlan.key.isEmpty() ||
            initialMarkdownPreparsePlan.key in readyMarkdownPreparseKeys
    LaunchedEffect(threadInstanceKey, initialMarkdownPreparsePlan.key) {
        if (initialMarkdownPreparsePlan.key.isEmpty()) {
            return@LaunchedEffect
        }
        val startedAt = TimeSource.Monotonic.markNow()
        try {
            val result =
                prepareSlockMarkdownFullRendererBodies(
                    bodies = initialMarkdownPreparsePlan.bodies,
                    maxBodies = ThreadInitialMarkdownPreparseMessageCount
                )
            readyMarkdownPreparseKeys = readyMarkdownPreparseKeys + initialMarkdownPreparsePlan.key
            val durationMs = startedAt.elapsedNow().inWholeMilliseconds
            diagnosticsLogger(
                "thread_markdown_preparse window=initial_latest requested=${result.requestedCount} " +
                    "parsed=${result.parsedCount} cacheHit=${result.cacheHitCount} " +
                    "messages=${renderPayload.messages.size} durationMs=$durationMs"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            readyMarkdownPreparseKeys = readyMarkdownPreparseKeys + initialMarkdownPreparsePlan.key
            diagnosticsLogger(
                "thread_markdown_preparse window=initial_latest result=failed " +
                    "messages=${renderPayload.messages.size} error=${error::class.simpleName}"
            )
        }
    }
    if (!initialBottomAnchorReady && !revealLatestLocalSendBeforeInitialAnchor && !initialMarkdownPreparseReady) {
        ThreadStateMessage("Loading...", null)
        return
    }
    val initialBottomAnchorPending =
        !initialBottomAnchorReady && latestItemIndex >= 0 && !revealLatestLocalSendBeforeInitialAnchor
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
    LaunchedEffect(threadInstanceKey, latestItemIndex, initialBottomAnchorReady, revealLatestLocalSendBeforeInitialAnchor) {
        if (initialBottomAnchorReady || latestItemIndex < 0 || revealLatestLocalSendBeforeInitialAnchor) {
            onThreadContentReady()
        }
    }
    val currentPayloadState = rememberUpdatedState(renderPayload)
    val currentLatestItemIndexState = rememberUpdatedState(latestItemIndex)
    LaunchedEffect(
        threadInstanceKey,
        shareSelectionCaptureActive,
        shareSelectionCaptureAnchorMessageId,
        messageIndexSignature,
        lazyItemCount
    ) {
        val anchorMessageId =
            shareSelectionCaptureAnchorMessageId
                ?.takeIf { shareSelectionCaptureActive && it.isNotBlank() }
                ?: return@LaunchedEffect
        val targetIndex = renderPayload.threadMessageListItemIndex(anchorMessageId)
        if (!listState.canScrollToThreadItem(targetIndex)) {
            return@LaunchedEffect
        }
        selectedShareMessageIds.forEach { messageId ->
            onShareSelectionBounds(messageId, null)
        }
        listState.scrollToItem(targetIndex, ThreadShareCaptureTopScrollOffsetPx)
        listState.awaitThreadMessagesLayoutFrame()
        delay(ThreadShareCaptureScrollSettleMs)
    }
    var pendingOlderPageAnchor by remember(threadInstanceKey) { mutableStateOf<ThreadVisibleMessageAnchor?>(null) }
    var previousOlderMessagesStatus by remember(threadInstanceKey) { mutableStateOf(renderPayload.olderMessagesStatus) }
    LaunchedEffect(threadInstanceKey, renderPayload.messages.size, lazyItemCount) {
        repeat(ThreadBottomAnchorMinimumAttempts + 1) { attempt ->
            listState.awaitThreadMessagesLayoutFrame()
            val stats = listState.threadLazyWindowStats(renderPayload, lazyItemCount)
            if (stats.visibleItems > 0) {
                diagnosticsLogger(stats.toDiagnosticsString(result = "laid_out"))
                if (firstViewportProbe.markLogged()) {
                    diagnosticsLogger(
                        firstViewportProbe.toDiagnosticsString(
                            result = "laid_out",
                            instanceId = threadInstanceKey,
                            timelineSlice = renderPayload,
                            lazyItemCount = lazyItemCount,
                            stats = stats
                        )
                    )
                    firstViewportProbeActive = false
                }
                return@LaunchedEffect
            }
            if (attempt >= ThreadBottomAnchorMinimumAttempts) {
                diagnosticsLogger(stats.toDiagnosticsString(result = "missing_layout"))
                if (firstViewportProbe.markLogged()) {
                    diagnosticsLogger(
                        firstViewportProbe.toDiagnosticsString(
                            result = "missing_layout",
                            instanceId = threadInstanceKey,
                            timelineSlice = renderPayload,
                            lazyItemCount = lazyItemCount,
                            stats = stats
                        )
                    )
                    firstViewportProbeActive = false
                }
            }
            delay(ThreadLayoutStabilityFrameDelayMs)
        }
    }
    LaunchedEffect(threadInstanceKey, latestItemIndex) {
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
    LaunchedEffect(threadInstanceKey, latestItemIndex, initialBottomAnchorReady) {
        if (latestItemIndex < 0 || !initialBottomAnchorReady) return@LaunchedEffect
        snapshotFlow {
            ThreadBottomAnchorCorrectionState(
                allowBottomAutoAnchor = allowBottomAutoAnchor,
                isScrollInProgress = listState.isScrollInProgress,
                isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(latestItemIndex)
            )
        }
            .distinctUntilChanged()
            .collect { state ->
                if (shouldAutoCorrectThreadBottomAnchor(state)) {
                    listState.scrollLatestItemToRenderedBottom(latestItemIndex)
                }
            }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.canLoadMoreBefore,
        renderPayload.olderMessagesStatus,
        initialBottomAnchorReady,
        renderPayload.messages.size
    ) {
        if (!initialBottomAnchorReady ||
            !renderPayload.canLoadMoreBefore ||
            renderPayload.olderMessagesStatus != "idle"
        ) {
            return@LaunchedEffect
        }
        val boundaryIndex = renderPayload.threadHistoryBoundaryListItemIndex()
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
        threadInstanceKey,
        renderPayload.olderMessagesStatus,
        renderPayload.messages.size,
        renderPayload.messages.firstOrNull()?.id
    ) {
        val previousStatus = previousOlderMessagesStatus
        if (renderPayload.olderMessagesStatus == "loading" && previousStatus != "loading") {
            pendingOlderPageAnchor = listState.threadFirstVisibleMessageAnchor(renderPayload)
            allowBottomAutoAnchor = false
        } else if (previousStatus == "loading" && renderPayload.olderMessagesStatus != "loading") {
            val anchor = pendingOlderPageAnchor
            pendingOlderPageAnchor = null
            allowBottomAutoAnchor = false
            if (anchor != null) {
                val targetIndex = renderPayload.threadMessageListItemIndex(anchor.messageId)
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex, -anchor.offset)
                }
            }
        }
        previousOlderMessagesStatus = renderPayload.olderMessagesStatus
    }
    LaunchedEffect(threadInstanceKey, latestLocalSendBottomAnchorKey, latestItemIndex) {
        if (!shouldAnchorLatestLocalSendToBottom(latestLocalSendBottomAnchorKey, latestItemIndex)) {
            return@LaunchedEffect
        }
        latestLocalSendBottomAnchorStartUserScrollGeneration = userScrollGeneration
        latestLocalSendBottomAnchorActiveKey = latestLocalSendBottomAnchorKey
        allowBottomAutoAnchor = true
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
                currentUserScrollGeneration = userScrollGeneration,
                isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(currentLatestItemIndexState.value),
                isSendingAnchorVisible = currentPayloadState.value.hasSendingLocalSendAnchor(anchorKey),
                attempt = attempt
            )
        ) {
            listState.scrollLatestItemToRenderedBottom(currentLatestItemIndexState.value)
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
        renderPayload.messages.size,
        lazyItemCount
    ) {
        val targetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (targetMessageId == consumedFocusedMessageId && activeFocusedMessageId == null) {
            return@LaunchedEffect
        }
        val targetIndex = renderPayload.threadMessageListItemIndex(targetMessageId)
        if (targetIndex < 0) {
            return@LaunchedEffect
        }
        listState.awaitThreadMessagesLayoutFrame()
        if (!listState.canScrollToThreadItem(targetIndex)) {
            return@LaunchedEffect
        }
        if (!listState.isThreadItemVisible(targetIndex)) {
            listState.scrollToItem(targetIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
            return@LaunchedEffect
        }
        activeFocusedMessageId = targetMessageId
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        val didFocus =
            listState.scrollThreadItemToFocusedViewport(
                itemIndex = targetIndex
            )
        if (!didFocus) {
            diagnosticsLogger(
                "thread_focused_message_anchor result=scroll_skipped_unmeasured " +
                    "messageId=$targetMessageId targetIndex=$targetIndex"
            )
            activeFocusedMessageId = null
            return@LaunchedEffect
        }
        consumedFocusedMessageId = targetMessageId
        delay(ThreadFocusedMessageHighlightDurationMs)
        if (activeFocusedMessageId == targetMessageId) {
            activeFocusedMessageId = null
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.focusedMessageId,
        latestMessageLayoutKey,
        latestItemIndex
    ) {
        if (latestItemIndex < 0) return@LaunchedEffect
        val focusedMessageTargetIndex =
            renderPayload.focusedMessageId
                ?.takeIf { it.isNotBlank() }
                ?.let { renderPayload.threadMessageListItemIndex(it) }
                ?: -1
        if (focusedMessageTargetIndex >= 0) {
            return@LaunchedEffect
        }
        if (!listState.isThreadItemVisible(latestItemIndex)) {
            listState.scrollToItem(latestItemIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
            return@LaunchedEffect
        }
        if (!initialBottomAnchorReady) {
            while (!initialBottomAnchorReady) {
                val anchored =
                    listState.anchorInitialThreadBottomToLatest(
                        latestItemIndex = latestItemIndex
                    )
                if (anchored) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                diagnosticsLogger(
                    "thread_initial_bottom_anchor result=retry latestItemIndex=$latestItemIndex " +
                        "totalItems=${listState.layoutInfo.totalItemsCount} " +
                        "visibleItems=${listState.layoutInfo.visibleItemsInfo.size}"
                )
                delay(ThreadInitialBottomAnchorRetryDelayMs)
            }
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
    LaunchedEffect(threadInstanceKey, composerKeyboardInsetDp, latestItemIndex) {
        val shouldAnchor =
            shouldAnchorThreadKeyboardBottomOnInsetChange(
                ThreadKeyboardBottomAnchorState(
                    previousKeyboardInsetDp = lastComposerKeyboardInsetDp,
                    nextKeyboardInsetDp = composerKeyboardInsetDp,
                    initialBottomAnchorReady = initialBottomAnchorReady,
                    latestItemIndex = latestItemIndex,
                    allowBottomAutoAnchor = allowBottomAutoAnchor
                )
            )
        lastComposerKeyboardInsetDp = composerKeyboardInsetDp
        if (!shouldAnchor) {
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
                    .touchListener { type, _ ->
                        if (type == TouchType.Move && latestLocalSendBottomAnchorActiveKey != null) {
                            latestLocalSendBottomAnchorActiveKey = null
                            allowBottomAutoAnchor = false
                            userScrollGeneration += 1
                        }
                    }
                    .alpha(
                        threadMessagesListAlpha(
                            initialBottomAnchorReady = initialBottomAnchorReady,
                            latestItemIndex = latestItemIndex,
                            revealLatestLocalSendBeforeInitialAnchor = revealLatestLocalSendBeforeInitialAnchor
                        )
                    ),
                verticalArrangement = Arrangement.Bottom,
                beyondBoundsItemCount = threadMessagesBeyondBoundsItemCount(lazyItemCount)
            ) {
            if (renderPayload.hasThreadOriginItem()) {
                item(key = ThreadOriginListItemKey) {
                    ThreadOriginCard(
                        renderConfig = renderConfig,
                        timelineSlice = renderPayload,
                        linkedTasksProjection = linkedTasksProjection,
                        highlightedMentions = highlightedMentions,
                        pendingActionCardIds = pendingActionCardIds,
                        attachmentOpenStates = attachmentOpenStates,
                        attachmentCommentsEnabled = attachmentCommentsEnabled,
                        currentServerSlug = currentServerSlug,
                        contentExtrasVisible = contentExtrasVisible,
                        isMarkdownTagValid = isMarkdownTagValid,
                        resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                        diagnosticsLogger = diagnosticsLogger,
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
                        onMentionAuthor = onMentionAuthor,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        onOpenMarkdownTag = onOpenMarkdownTag,
                        onOpenMarkdownUrl = onOpenMarkdownUrl,
                        shareSelectionActive = shareSelectionActive,
                        selectedShareMessageIds = selectedShareMessageIds,
                        onToggleShareSelection = onToggleShareSelection
                    )
                }
            }
            if (renderPayload.hasThreadHistoryBoundaryItem()) {
                item(key = ThreadOlderMessagesListItemKey) {
                    when {
                        renderPayload.canLoadMoreBefore && renderPayload.olderMessagesStatus == "error" ->
                            ThreadInlineActionRow(
                                label = "Retry older messages",
                                onClick = { onThreadEvent("retryLoadOlder", null, null, null) }
                            )
                        renderPayload.canLoadMoreBefore ->
                            ThreadHistoryStatusRow(threadOlderMessagesStatusSpec(renderPayload))
                        else ->
                            ThreadHistoryStatusRow(threadOlderMessagesStatusSpec(renderPayload))
                    }
                }
            }
            if (showReplyEmptyState) {
                item(key = ThreadReplyEmptyListItemKey) {
                    ThreadStateMessage(renderPayload.emptyTitle, renderPayload.emptyDetail)
                }
            }
            items(renderPayload.messages.size, key = { renderPayload.messages[it].messageListItemKey() }) { index ->
                val message = renderPayload.messages[index]
                val absoluteMessageIndex = index
                val previousMessage = renderPayload.messages.getOrNull(index - 1)
                SideEffect {
                    firstViewportProbe.recordComposedMessage(message.id, absoluteMessageIndex)
                }
                val messagePerformanceKey =
                    remember(message.id, absoluteMessageIndex) {
                        threadMessagePerformanceKey(message.id, absoluteMessageIndex)
                    }
                val isSystemMessage = remember(message.messageType) { message.isSystemMessage() }
                val previousCompactAuthorSignature = previousMessage?.threadCompactAuthorSignature()
                val messageCompactAuthorSignature = message.threadCompactAuthorSignature()
                val suppressRepeatedAuthor =
                    remember(
                        renderPayload.compactConsecutiveMessages,
                        renderConfig.timeContext.localOffsetMinutes,
                        previousCompactAuthorSignature,
                        messageCompactAuthorSignature
                    ) {
                        renderPayload.compactConsecutiveMessages &&
                            shouldSuppressCompactThreadAuthor(
                                previous = previousMessage,
                                next = message,
                                localOffsetMinutes = renderConfig.timeContext.localOffsetMinutes
                            )
                    }
                val isRichContentEnabled = contentExtrasVisible
                val actionCardSignature =
                    if (isRichContentEnabled) {
                        remember(message.id, message.metadata) { message.threadActionCardSignature() }
                    } else {
                        null
                    }
                val actionCard =
                    if (isRichContentEnabled) {
                        remember(actionCardSignature) {
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
                                        "index=$absoluteMessageIndex id=${message.id} messageType=${message.messageType} " +
                                        "bodyLength=${message.body.length} isSystem=${isSystemMessage} " +
                                        "hasActionCard=${actionCard != null} " +
                                        "attachmentCount=${message.attachments.size} " +
                                        "threadId=${renderPayload.sourceThreadId.orEmpty()} " +
                                        "messagesTotal=${renderPayload.messages.size} richContentEnabled=$isRichContentEnabled " +
                                        "renderMessages=${renderPayload.messages.size}"
                                )
                    }
                }
                val linkedTask = linkedTasksProjection.lookup.threadLinkedTaskForMessage(message)
                val replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message)
                val displayMessage =
                    remember(message, linkedTasksProjection) {
                        val startedAt = TimeSource.Monotonic.markNow()
                        message.withLinkedTaskProjection(linkedTasksProjection)
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
                val captureShareBounds = shareSelectionCaptureActive && message.id in selectedShareMessageIds
                Box(
                    modifier =
                        Modifier
                            .then(
                                if (firstViewportProbeActive) {
                                    Modifier.onGloballyPositioned {
                                        firstViewportProbe.recordPlacedMessage(message.id, absoluteMessageIndex)
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (captureShareBounds) {
                                    Modifier.onGloballyPositioned { coordinates ->
                                        val pos = coordinates.positionInRoot()
                                        onShareSelectionBounds(
                                            message.id,
                                            ThreadShareCaptureRect(
                                                x = pos.x,
                                                y = pos.y,
                                                width = coordinates.size.width.toFloat(),
                                                height = coordinates.size.height.toFloat()
                                            )
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                ) {
                    if (!captureShareBounds) {
                        SideEffect {
                            onShareSelectionBounds(message.id, null)
                        }
                    }
                    if (isSystemMessage && actionCard == null) {
                        ThreadSystemMessageRow(
                            message = displayMessage,
                            timestampLabel = renderConfig.formatTimestampLabel(message.timestampLabel)
                        )
                    } else {
                        ThreadMessageCard(
                            message = displayMessage,
                            replyMetadata = replyMetadata,
                            hasReplyDraft = activeReplyDraftParentMessageIds.hasReplyComposerDraft(message.id),
                            actionCard = actionCard,
                            messageIndex = absoluteMessageIndex,
                            diagnosticsLogger = diagnosticsLogger,
                            highlightedMentions = highlightedMentions,
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver,
                            focused = activeFocusedMessageId == message.id,
                            shareSelectionActive = shareSelectionActive,
                            selectedForShare = message.id in selectedShareMessageIds,
                            onToggleShareSelection = { onToggleShareSelection(message.id) },
                            showSaveAction = !captureShareBounds,
                            isMessageListScrolling = listState.isScrollInProgress,
                            alignOwnMessageRight = renderPayload.alignOwnMessagesRight && message.isOwn,
                            compactConsecutiveMessages = renderPayload.compactConsecutiveMessages,
                            suppressRepeatedAuthor = suppressRepeatedAuthor,
                            showReplyAction = renderPayload.showReplyActions,
                            resolvedTaskAssigneeName = linkedTaskAssigneeName,
                            resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                            taskOpenEnabled = renderPayload.showTaskOpenActions && isRichContentEnabled,
                            taskStatusActionsEnabled = renderPayload.showTaskStatusActions && isRichContentEnabled,
                            captureLongPressAnchor = captureMessageAnchors,
                            isMarkdownTagValid = isMarkdownTagValid,
                            onShowActions = { anchor ->
                                if (!message.hasLocalSendState() || message.isFailedLocalSend() || message.isSendingLocalSend()) {
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
                            timestampLabel = renderConfig.formatTimestampLabel(message.timestampLabel),
                            compactTimestampLabel = renderConfig.formatCompactTimestampLabel(message.timestampLabel),
                            onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                            attachmentCommentsEnabled = attachmentCommentsEnabled,
                            onOpenAttachmentComments = { onThreadEvent("openAttachmentComments", message.id, it, null) },
                            onRetryLocalSend = { onThreadEvent("retryLocalSend", message.id, null, null) },
                            attachmentOpenStates = if (isRichContentEnabled) attachmentOpenStates else emptyMap(),
                            pendingActionCardIds = if (isRichContentEnabled) pendingActionCardIds else emptySet(),
                            onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
                            currentServerSlug = currentServerSlug,
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl,
                            onMentionAuthor = onMentionAuthor,
                            richContentEnabled = isRichContentEnabled
                        )
                    }
                }
            }
            if (renderPayload.canLoadMoreAfter) {
                item(key = ThreadNewerMessagesListItemKey) {
                    val actionSpec = threadNewerMessagesActionSpec(renderPayload.newerMessagesStatus)
                    ThreadInlineActionRow(
                        label = actionSpec.label,
                        leadingIcon = actionSpec.leadingIcon,
                        onClick = {
                            onThreadEvent(
                                actionSpec.event,
                                null,
                                null,
                                null
                            )
                        }
                    )
                }
            }
            item(key = ThreadBottomScrollPaddingListItemKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ThreadBottomScrollPaddingHeight)
                )
            }
        }
        if (!shareSelectionCaptureActive && shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = initialBottomAnchorReady,
                    pendingKeyboardBottomAnchor = pendingKeyboardBottomAnchor,
                    canScrollTowardBottom = listState.canScrollForward,
                    latestItemOffBottom = listState.shouldShowThreadBackToBottom(latestItemIndex)
                )
            )
        ) {
            ThreadBackToBottomButton(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SlockSpacing.Md),
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
            renderPayload.threadActionMessage(messageId, linkedTasksProjection)?.let { message ->
                val isFailedLocalSend = message.isFailedLocalSend()
                val isSendingLocalSend = message.isSendingLocalSend()
                val replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message)
                ThreadMessageActionMenu(
                    message = message,
                    replyMetadata = replyMetadata,
                    anchor = actionMenuAnchor,
                    showReplyAction = renderPayload.showReplyActions && !message.hasLocalSendState(),
                    showSaveAction = renderPayload.showMessageSaveAction && !message.hasLocalSendState(),
                    showTaskStatusAction = renderPayload.showTaskStatusActions && !message.hasLocalSendState(),
                    showConvertToTaskAction =
                        renderPayload.canConvertMessagesToTasks && !message.hasLocalSendState() &&
                            message.id !in pendingTaskConvertMessageIds,
                    showReactionActions = !message.hasLocalSendState(),
                    showCopyLinkAction = !message.hasLocalSendState(),
                    showShareMessagesAction = !message.hasLocalSendState(),
                    showCancelAction = isSendingLocalSend,
                    showDeleteAction = isFailedLocalSend,
                    onDismiss = { actionMessageId = null },
                    onCopyLink = {
                        actionMessageId = null
                        onThreadEvent("copyMessageLink", message.id, null, null)
                    },
                    onReply = {
                        actionMessageId = null
                        onThreadEvent("openMessageThread", message.id, null, null)
                    },
                    onCopy = {
                        actionMessageId = null
                        onThreadEvent("copyMessage", message.id, null, null)
                    },
                    onSelectText = {
                        actionMessageId = null
                        onThreadEvent("openSelectMessageText", message.id, null, null)
                    },
                    onShareMessages = {
                        actionMessageId = null
                        onEnterShareSelection(message.id)
                    },
                    onSave = {
                        actionMessageId = null
                        onThreadEvent("toggleMessageSaved", message.id, null, null)
                    },
                    onFollowThread = {
                        actionMessageId = null
                        onThreadEvent("followMessageThread", message.id, null, null)
                    },
                    onUnfollowThread = { threadChannelId ->
                        actionMessageId = null
                        onThreadEvent("unfollowMessageThread", message.id, threadChannelId, null)
                    },
                    onReact = { emoji ->
                        actionMessageId = null
                        onThreadEvent("toggleReaction", message.id, null, emoji)
                    },
                    onTaskStatusChange = { action ->
                        actionMessageId = null
                        onThreadEvent(action, message.taskId, null, null)
                    },
                    onConvertToTask = {
                        actionMessageId = null
                        onThreadEvent("convertMessageToTask", message.id, null, null)
                    },
                    onCancel = {
                        actionMessageId = null
                        onThreadEvent("cancelLocalSend", message.id, null, null)
                    },
                    onDelete = {
                        actionMessageId = null
                        onThreadEvent("deleteLocalFailedSend", message.id, null, null)
                    },
                    diagnosticsLogger = diagnosticsLogger
                )
            }
        }
        taskStatusMenuMessageId?.let { messageId ->
            renderPayload.threadActionMessage(messageId, linkedTasksProjection)?.let { message ->
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
private const val ThreadOriginListItemKey = "thread-page:origin"
private const val ThreadReplyEmptyListItemKey = "thread-page:reply-empty"
private const val ThreadBottomScrollPaddingListItemKey = "thread-page:bottom-scroll-padding"
private val ThreadBottomScrollPaddingHeight = 16.dp
internal data class ThreadInlineActionSpec(
    val label: String,
    val event: String,
    val leadingIcon: SlockLucideIconName? = null
)

internal data class ThreadHistoryStatusSpec(
    val label: String,
    val leadingIcon: SlockLucideIconName? = null,
    val secondaryLabel: String? = null
)

private fun ThreadTimelineSlice.threadSelectedShareMessages(
    selectedIds: Set<String>
): List<KuiklyThreadMessageItem> {
    if (selectedIds.isEmpty()) return emptyList()
    return (listOfNotNull(originMessage) + messages)
        .filter { message -> message.id in selectedIds && !message.isSystemMessage() }
        .distinctBy { message -> message.id }
}

private fun ThreadTimelineSlice.threadSelectedShareMarkdown(selectedIds: Set<String>): String =
    threadSelectedShareMessages(selectedIds).threadShareMarkdown()

private fun List<KuiklyThreadMessageItem>.threadShareMarkdown(): String =
    joinToString("\n\n") { message ->
        "**${message.authorName.ifBlank { "Unknown" }}**: ${message.body.trim()}"
    }

internal fun threadOlderMessagesStatusSpec(payload: KuiklyThreadScreenPayload): ThreadHistoryStatusSpec =
    threadOlderMessagesStatusSpec(payload.toThreadTimelineSlice())

internal fun threadOlderMessagesStatusSpec(timelineSlice: ThreadTimelineSlice): ThreadHistoryStatusSpec =
    threadOlderMessagesStatusSpec(
        canLoadMoreBefore = timelineSlice.canLoadMoreBefore,
        secondaryLabel = timelineSlice.beginningReplyCountLabel
    )

internal fun threadOlderMessagesStatusSpec(
    canLoadMoreBefore: Boolean,
    secondaryLabel: String? = null
): ThreadHistoryStatusSpec =
    if (canLoadMoreBefore) {
        ThreadHistoryStatusSpec(label = "Loading older messages…")
    } else {
        ThreadHistoryStatusSpec(label = "Beginning of conversation", secondaryLabel = secondaryLabel)
    }

internal fun threadNewerMessagesActionSpec(newerMessagesStatus: String): ThreadInlineActionSpec =
    when (newerMessagesStatus) {
        "loading" -> ThreadInlineActionSpec(label = "Loading new messages…", event = "loadNewer")
        "error" -> ThreadInlineActionSpec(label = "Retry new messages", event = "retryLoadNewer")
        else -> ThreadInlineActionSpec(label = "Load new messages", event = "loadNewer")
    }

internal fun threadMessagesLazyItemCount(
    originStatus: String,
    showHistoryBoundary: Boolean,
    messagesCount: Int,
    canLoadMoreAfter: Boolean,
    showReplyEmptyState: Boolean = false,
    showBottomScrollPadding: Boolean = false
): Int =
    (if (originStatus != "none") 1 else 0) +
        (if (showHistoryBoundary) 1 else 0) +
        messagesCount.coerceAtLeast(0) +
        (if (canLoadMoreAfter) 1 else 0) +
        (if (showReplyEmptyState) 1 else 0) +
        (if (showBottomScrollPadding) 1 else 0)

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
    timelineSlice: ThreadTimelineSlice,
    lazyItemCount: Int
): ThreadLazyWindowStats {
    val visibleItems = layoutInfo.visibleItemsInfo
    val firstVisibleItem = visibleItems.firstOrNull()?.index ?: -1
    val lastVisibleItem = visibleItems.lastOrNull()?.index ?: -1
    val firstMessageListItemIndex = timelineSlice.firstMessageListItemIndex()
    val visibleMessageIndexes =
        visibleItems.mapNotNull { item ->
            (item.index - firstMessageListItemIndex)
                .takeIf { it in timelineSlice.messages.indices }
        }
    return ThreadLazyWindowStats(
        totalItems = lazyItemCount,
        totalMessages = timelineSlice.messages.size,
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

internal fun threadInitialFirstVisibleItemIndex(latestItemIndex: Int): Int =
    latestItemIndex.coerceAtLeast(0)

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

internal data class ThreadCompactAuthorSignature(
    val isSystemMessage: Boolean,
    val authorIdentityKey: String,
    val timestampLabel: String
)

internal fun KuiklyThreadMessageItem.threadCompactAuthorSignature(): ThreadCompactAuthorSignature =
    ThreadCompactAuthorSignature(
        isSystemMessage = isSystemMessage(),
        authorIdentityKey = authorIdentityKey(),
        timestampLabel = timestampLabel
    )

internal fun KuiklyThreadScreenPayload.threadActionMessage(
    messageId: String,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection()
): KuiklyThreadMessageItem? =
    toThreadTimelineSlice().threadActionMessage(messageId, linkedTasksProjection)

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
        append(latest.threadReactionLayoutSignature())
    }
}

internal fun shouldAnchorLatestLocalSendToBottom(
    latestLocalSendBottomAnchorKey: String?,
    latestItemIndex: Int
): Boolean =
    latestLocalSendBottomAnchorKey != null && latestItemIndex >= 0

internal fun shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
    initialBottomAnchorReady: Boolean,
    latestLocalSendBottomAnchorKey: String?,
    latestItemIndex: Int,
    messageCount: Int
): Boolean =
    !initialBottomAnchorReady &&
        latestLocalSendBottomAnchorKey != null &&
        latestItemIndex >= 0 &&
        messageCount == 1

internal fun shouldContinueLatestLocalSendBottomAnchor(
    activeAnchorKey: String?,
    targetAnchorKey: String?,
    startUserScrollGeneration: Int,
    currentUserScrollGeneration: Int,
    isAtRenderedBottom: Boolean,
    isSendingAnchorVisible: Boolean,
    attempt: Int
): Boolean {
    if (targetAnchorKey == null || activeAnchorKey != targetAnchorKey) return false
    if (currentUserScrollGeneration != startUserScrollGeneration) return false
    return isSendingAnchorVisible || !isAtRenderedBottom || attempt < ThreadBottomAnchorMinimumAttempts
}

internal data class ThreadBottomAnchorCorrectionState(
    val allowBottomAutoAnchor: Boolean,
    val isScrollInProgress: Boolean,
    val isAtRenderedBottom: Boolean
)

internal fun shouldAutoCorrectThreadBottomAnchor(state: ThreadBottomAnchorCorrectionState): Boolean =
    state.allowBottomAutoAnchor &&
        !state.isScrollInProgress &&
        !state.isAtRenderedBottom

internal data class ThreadKeyboardBottomAnchorState(
    val previousKeyboardInsetDp: Float,
    val nextKeyboardInsetDp: Float,
    val initialBottomAnchorReady: Boolean,
    val latestItemIndex: Int,
    val allowBottomAutoAnchor: Boolean
)

internal fun shouldAnchorThreadKeyboardBottomOnInsetChange(state: ThreadKeyboardBottomAnchorState): Boolean =
    state.previousKeyboardInsetDp != state.nextKeyboardInsetDp &&
        state.initialBottomAnchorReady &&
        state.latestItemIndex >= 0 &&
        state.allowBottomAutoAnchor

internal data class ThreadBackToBottomButtonState(
    val initialBottomAnchorReady: Boolean,
    val pendingKeyboardBottomAnchor: Boolean,
    val canScrollTowardBottom: Boolean = true,
    val latestItemOffBottom: Boolean
)

internal fun shouldShowThreadBackToBottomButton(state: ThreadBackToBottomButtonState): Boolean =
    state.initialBottomAnchorReady &&
        !state.pendingKeyboardBottomAnchor &&
        state.canScrollTowardBottom &&
        state.latestItemOffBottom

internal data class ThreadInitialBottomAnchorState(
    val canScrollToLatest: Boolean,
    val isAtRenderedBottom: Boolean,
    val attempt: Int
)

internal fun shouldRevealInitialThreadBottomAnchor(state: ThreadInitialBottomAnchorState): Boolean =
    state.canScrollToLatest &&
        state.isAtRenderedBottom &&
        state.attempt >= ThreadBottomAnchorMinimumAttempts

internal fun threadMessagesListAlpha(
    initialBottomAnchorReady: Boolean,
    latestItemIndex: Int,
    revealLatestLocalSendBeforeInitialAnchor: Boolean
): Float = 1f

internal fun KuiklyThreadScreenPayload.hasSendingLocalSendAnchor(anchorKey: String?): Boolean {
    val messageId = anchorKey?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return false
    return messages.any { message ->
        message.id == messageId &&
            message.deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) &&
            message.pendingSendContext != null
    }
}

internal fun KuiklyThreadScreenPayload.latestLocalSendBottomAnchorKey(): String? {
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

internal fun KuiklyThreadMessageItem.threadReactionLayoutSignature(): String =
    reactions
        .filter { reaction -> reaction.count > 0 }
        .joinToString(separator = "|") { reaction ->
            "${reaction.emoji}:${reaction.count}:${reaction.selected}"
        }

internal fun KuiklyThreadScreenPayload.visibleReadAllMarkerKey(): String? {
    return toThreadTimelineSlice().visibleReadAllMarkerKey(sourceThreadId)
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
    timelineSlice: ThreadTimelineSlice
): ThreadVisibleMessageAnchor? {
    val firstMessageListItemIndex = timelineSlice.firstMessageListItemIndex()
    val messageCount = timelineSlice.messages.size
    if (messageCount <= 0) return null
    val item =
        layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                item.index >= firstMessageListItemIndex &&
                    item.index < firstMessageListItemIndex + messageCount
            }
            ?: return null
    val message = timelineSlice.messages.getOrNull(item.index - firstMessageListItemIndex) ?: return null
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

private suspend fun LazyListState.anchorInitialThreadBottomToLatest(
    latestItemIndex: Int
): Boolean {
    if (latestItemIndex < 0) return true
    repeat(ThreadInitialBottomAnchorAttempts) { attempt ->
        awaitThreadMessagesLayoutFrame()
        val canScrollToLatest = canScrollToThreadItem(latestItemIndex)
        if (!canScrollToLatest) {
            delay(ThreadLayoutStabilityFrameDelayMs)
            return@repeat
        }

        if (!isThreadItemVisible(latestItemIndex)) {
            scrollToItem(latestItemIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
        }
        awaitThreadMessagesLayoutStability(latestItemIndex)
        scrollLatestItemToRenderedBottom(latestItemIndex)
        delay(ThreadLayoutStabilityFrameDelayMs)

        val anchorState =
            ThreadInitialBottomAnchorState(
                canScrollToLatest = canScrollToLatest,
                isAtRenderedBottom = isLatestItemAtRenderedBottom(latestItemIndex),
                attempt = attempt
            )
        if (shouldRevealInitialThreadBottomAnchor(anchorState)) {
            return true
        }
    }
    if (!canScrollToThreadItem(latestItemIndex)) {
        return false
    }
    scrollToItem(latestItemIndex)
    awaitThreadMessagesLayoutStability(latestItemIndex)
    scrollLatestItemToRenderedBottom(latestItemIndex)
    delay(ThreadLayoutStabilityFrameDelayMs)
    return isLatestItemAtRenderedBottom(latestItemIndex)
}

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

private suspend fun LazyListState.scrollThreadItemToFocusedViewport(itemIndex: Int): Boolean {
    if (itemIndex < 0 || layoutInfo.totalItemsCount <= itemIndex) {
        return false
    }
    var didScroll = false
    repeat(ThreadFocusedMessageCenterAttempts) {
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        if (item == null) {
            scrollToItem(itemIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
            return@repeat
        }
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val offset =
            threadFocusedItemScrollOffset(
                itemSize = item.size,
                viewportHeight = viewportHeight
            )
        scrollToItem(itemIndex, offset)
        didScroll = true
        delay(ThreadLayoutStabilityFrameDelayMs)
    }
    return didScroll
}

private suspend fun LazyListState.awaitThreadMessagesLayoutStability(
    latestItemIndex: Int
): Boolean {
    var previous: ThreadMessagesLayoutStabilitySnapshot? = null
    var stableFrames = 0
    repeat(ThreadLayoutStabilityMaxFrames) {
        val current = threadMessagesLayoutStabilitySnapshot(latestItemIndex)
        if (current.canAnchor && current == previous) {
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

internal fun threadFocusedItemScrollOffset(
    itemSize: Int,
    viewportHeight: Int
): Int {
    if (itemSize <= 0 || viewportHeight <= 0) return 0
    if (itemSize >= viewportHeight) return 0
    return (itemSize - viewportHeight) / 2
}

private fun LazyListState.shouldShowThreadBackToBottom(latestItemIndex: Int): Boolean {
    if (latestItemIndex <= 0) return false
    if (!canScrollForward) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= latestItemIndex) return false
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val latestBottom = latestItem?.let { it.offset + it.size } ?: return true
    return latestBottom < layoutInfo.viewportEndOffset - ThreadBackToBottomTolerancePx
}

private fun LazyListState.isLatestItemAtRenderedBottom(latestItemIndex: Int): Boolean {
    if (!canScrollForward) return true
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
private const val ThreadInitialBottomAnchorAttempts = 12
private const val ThreadInitialBottomAnchorRetryDelayMs = 80L
private const val ThreadLayoutStabilityFrameDelayMs = 16L
private const val ThreadFirstContentTaskJoinDelayMs = 96L
private const val ThreadBackgroundTasksJoinDelayMs = 360L
private const val ThreadLayoutStabilityMaxFrames = 30
private const val ThreadLayoutStabilityRequiredFrames = 3
private const val ThreadBackToBottomAnchorAttempts = 12
private const val ThreadKeyboardBottomAnchorAttempts = 8
private const val ThreadOlderAutoLoadVisibleItemThreshold = 1
private const val ThreadShareCaptureTopScrollOffsetPx = -12
private const val ThreadShareCaptureScrollSettleMs = 96L
private val ThreadSystemMessageTimeColor = SlockColor.TextBlack.copy(alpha = 0.4f)
private val ThreadSystemMessageBodyColor = SlockColor.TextBlack.copy(alpha = 0.5f)

@Composable
private fun ThreadSystemMessageRow(
    message: KuiklyThreadMessageItem,
    timestampLabel: String
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val systemBody =
        remember(message.body, message.metadata, message.systemEventType, timestampLabel, expanded) {
            if (expanded) {
                message.expandedSystemSummary(timestampLabel)
            } else {
                message.collapsedSystemSummary(timestampLabel)
            }
        }
    val annotatedSystemBody =
        remember(systemBody, timestampLabel) {
            buildAnnotatedString {
                pushStyle(SpanStyle(color = ThreadSystemMessageTimeColor))
                append(timestampLabel)
                pop()
                val body = systemBody.removePrefix(timestampLabel).trimStart()
                if (body.isNotBlank()) {
                    append(" ")
                    pushStyle(SpanStyle(color = ThreadSystemMessageBodyColor))
                    append(body)
                    pop()
                }
            }
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .clickable { expanded = !expanded }
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = annotatedSystemBody,
            style = SlockType.Caption.copy(color = ThreadSystemMessageBodyColor),
            color = ThreadSystemMessageBodyColor,
            textAlign = TextAlign.Center,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ThreadInlineActionRow(
    label: String,
    leadingIcon: SlockLucideIconName? = null,
    onClick: () -> Unit
) {
    SlockBrutalActionButton(
        label = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 36.dp,
        leadingIcon = leadingIcon,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(fontSize = 12.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun ThreadHistoryStatusRow(
    spec: ThreadHistoryStatusSpec
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            spec.leadingIcon?.let { iconName ->
                SlockLucideIcon(
                    iconName,
                    modifier = Modifier.size(14.dp),
                    color = "#FF6B7280"
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = spec.label,
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        if (!spec.secondaryLabel.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = spec.secondaryLabel,
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun ThreadMessageCard(
    message: KuiklyThreadMessageItem,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    hasReplyDraft: Boolean = false,
    actionCard: ThreadActionCard? = message.threadActionCard(),
    messageIndex: Int = -1,
    diagnosticsLogger: (String) -> Unit = {},
    highlightedMentions: Set<String> = emptySet(),
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    alignOwnMessageRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    suppressRepeatedAuthor: Boolean = false,
    showReplyAction: Boolean = true,
    resolvedTaskAssigneeName: String? = null,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    taskOpenEnabled: Boolean = true,
    taskStatusActionsEnabled: Boolean = true,
    showSaveAction: Boolean = true,
    showInlineSaveAction: Boolean = false,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    timestampLabel: String = message.timestampLabel,
    compactTimestampLabel: String = timestampLabel,
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
    onMentionAuthor: (String) -> Unit = {},
    richContentEnabled: Boolean = true,
    focused: Boolean = false,
    isMessageListScrolling: Boolean = false,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    captureLongPressAnchor: Boolean = true,
    shareSelectionActive: Boolean = false,
    selectedForShare: Boolean = false,
    onToggleShareSelection: () -> Unit = {},
    outerPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Sm
) {
    val horizontalArrangement =
        if (alignOwnMessageRight) Arrangement.spacedBy(12.dp, Alignment.End) else Arrangement.spacedBy(12.dp)
    val contentAlignment = if (alignOwnMessageRight) Alignment.End else Alignment.Start
    val messageMaxWidthFraction = if (alignOwnMessageRight) 0.82f else 1f
    val agentStatus =
        if (message.showsAgentStatusIndicator()) {
            rememberSlockAgentStatusIndicatorState(agentStatusResolver, message.authorId)
        } else {
            null
        }
    val avatarWireValue =
        rememberSlockMemberAvatarWireValue(memberAvatarResolver, message.authorId)
            ?: message.resolvedThreadAvatarWireValue()
    val avatarGravatarUrl = message.threadAvatarGravatarUrl()
    val authorBadgeLabel = message.threadAuthorBadgeLabel()
    val focusAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = ThreadFocusedMessageHighlightFadeMs),
        label = "thread-message-focus-highlight"
    )
    var pressed by remember(message.id) { mutableStateOf(false) }
    val showAuthorChrome = !compactConsecutiveMessages || !suppressRepeatedAuthor
    val showTimestampInHeader = !compactConsecutiveMessages || showAuthorChrome
    val showTimestampInAvatarSlot =
        compactConsecutiveMessages && !showAuthorChrome && pressed && compactTimestampLabel.isNotBlank()
    val hasThreadAttachments = richContentEnabled && message.attachments.isNotEmpty()
    val showInlineBookmarkAction =
        shouldShowThreadMessageInlineBookmark(
            showSaveAction = showSaveAction,
            showInlineSaveAction = showInlineSaveAction,
            richContentEnabled = richContentEnabled,
            hasLocalSendState = message.hasLocalSendState()
        )
    val reserveInlineTrailingSpace = richContentEnabled && (message.isSendingLocalSend() || showInlineBookmarkAction)
    val hasFooterMeta =
        richContentEnabled &&
            !message.hasLocalSendState() &&
            message.hasThreadFooterMeta(replyMetadata, hasReplyDraft)
    val messagePerformanceKey =
        remember(message.id, messageIndex) {
            threadMessagePerformanceKey(message.id, messageIndex)
        }
    Box(
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
                    .background(
                        if (focusAlpha > 0f) {
                            SlockColor.AccentCyan.copy(alpha = 0.12f * focusAlpha)
                        } else {
                            SlockColor.SurfaceWhite
                        }
                    )
                    .threadMessagePressOutline(pressed)
                    .padding(horizontal = SlockSpacing.Xs, vertical = SlockSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = horizontalArrangement
            ) {
                if (shareSelectionActive && !alignOwnMessageRight) {
                    ThreadMessageSelectionToggle(
                        checked = selectedForShare,
                        onClick = onToggleShareSelection,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (!alignOwnMessageRight) {
                    ThreadMessageAvatarSlot(
                        showAvatar = showAuthorChrome,
                        showTimestamp = showTimestampInAvatarSlot,
                        timestampLabel = compactTimestampLabel,
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = avatarWireValue,
                        gravatarUrl = avatarGravatarUrl,
                        status = agentStatus,
                        statusPulsing = !isMessageListScrolling,
                        muted = authorBadgeLabel != null,
                        onClick = {
                            if (message.canOpenThreadAuthorProfile(shareSelectionActive)) {
                                onOpenMember(message.authorId)
                            }
                        },
                        onLongPress = { message.threadMentionHandle()?.let(onMentionAuthor) }
                    )
                }
                val contentModifier =
                    if (shareSelectionActive) {
                        Modifier
                            .weight(1f)
                            .clickable(onClick = onToggleShareSelection)
                    } else {
                        Modifier
                            .weight(1f)
                            .threadMessageLongPressActions(
                                messageId = message.id,
                                captureLongPressAnchor = captureLongPressAnchor,
                                onShowActions = onShowActions,
                                onPressChanged = { pressed = it }
                            )
                    }
                Box(
                    modifier = contentModifier
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                        horizontalAlignment = contentAlignment
                    ) {
                        if (showAuthorChrome) {
                            Button(
                                modifier =
                                    Modifier
                                        .threadMessageContentWidth(messageMaxWidthFraction)
                                        .padding(end = if (reserveInlineTrailingSpace) 30.dp else 0.dp)
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
                                        modifier =
                                            Modifier
                                                .widthIn(max = 220.dp)
                                                .clickable {
                                                    if (message.canOpenThreadAuthorProfile(shareSelectionActive)) {
                                                        onOpenMember(message.authorId)
                                                    }
                                                }
                                    )
                                    authorBadgeLabel?.let { label ->
                                        ThreadMessageAuthorBadge(label)
                                    }
                                    if (showTimestampInHeader) {
                                        ThreadMessageTimestampLabel(timestampLabel)
                                    }
                                }
                            }
                        }
                        if (richContentEnabled && actionCard != null) {
                            ThreadActionCardView(
                                card = actionCard,
                                pending = message.id in pendingActionCardIds,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                performanceKey = messagePerformanceKey,
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
                                        textStyle = ThreadMessageBodyTextStyle,
                                        highlightedMentions = highlightedMentions,
                                        isTagValid = isMarkdownTagValid,
                                        resolveTagLabel = resolveMarkdownTagLabel,
                                        inlineContentScope = message.id,
                                        onOpenTag = onOpenMarkdownTag,
                                        onOpenUrl = onOpenMarkdownUrl,
                                        performanceKey = messagePerformanceKey,
                                        performanceLogger = diagnosticsLogger
                                    )
                                } else {
                                    Text(
                                        text = message.body,
                                        style = ThreadMessageBodyTextStyle,
                                        color = SlockColor.TextBlack
                                    )
                                }
                            }
                        }
                        if (hasThreadAttachments) {
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
                        if (message.isFailedLocalSend()) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                ThreadMessageLocalSendStatus(
                                    message = message,
                                    alignEnd = alignOwnMessageRight,
                                    onRetry = onRetryLocalSend
                                )
                            }
                        }
                        if (hasFooterMeta) {
                            Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                ThreadMessageFooterMeta(
                                    message = message,
                                    replyMetadata = replyMetadata,
                                    hasReplyDraft = hasReplyDraft,
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
                    if (richContentEnabled) {
                        when {
                            message.isSendingLocalSend() -> {
                                ThreadMessageSendingIndicator(modifier = Modifier.align(Alignment.TopEnd))
                            }
                            showInlineBookmarkAction -> {
                                ThreadMessageBookmarkButton(
                                    saved = message.isSaved,
                                    onClick = onToggleSaved,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                }
                if (alignOwnMessageRight) {
                    ThreadMessageAvatarSlot(
                        showAvatar = showAuthorChrome,
                        showTimestamp = showTimestampInAvatarSlot,
                        timestampLabel = compactTimestampLabel,
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = avatarWireValue,
                        gravatarUrl = avatarGravatarUrl,
                        status = agentStatus,
                        statusPulsing = !isMessageListScrolling,
                        muted = authorBadgeLabel != null,
                        onClick = {
                            if (message.canOpenThreadAuthorProfile(shareSelectionActive)) {
                                onOpenMember(message.authorId)
                            }
                        },
                        onLongPress = { message.threadMentionHandle()?.let(onMentionAuthor) }
                    )
                }
                if (shareSelectionActive && alignOwnMessageRight) {
                    ThreadMessageSelectionToggle(
                        checked = selectedForShare,
                        onClick = onToggleShareSelection,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

internal fun shouldShowThreadMessageInlineBookmark(
    showSaveAction: Boolean,
    showInlineSaveAction: Boolean,
    richContentEnabled: Boolean,
    hasLocalSendState: Boolean
): Boolean =
    showSaveAction &&
        showInlineSaveAction &&
        richContentEnabled &&
        !hasLocalSendState

@Composable
private fun ThreadMessageSelectionToggle(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        pressFeedback = false,
        modifier =
            modifier
                .size(28.dp)
                .background(Color.Transparent)
    ) {
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .background(if (checked) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                SlockLucideIcon(
                    SlockLucideIconName.Check,
                    modifier = Modifier.size(14.dp),
                    color = "#FF000000"
                )
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
            threadSendFailureStatusLabel(
                errorCode = message.pendingSendErrorCode,
                errorMessage = message.pendingSendErrorMessage
            )
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
                    color = if (isFailed) "#FFF97264" else SlockColor.TextMutedGrayArgb,
                    spinning = !isFailed
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
private fun ThreadMessageSendingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(24.dp)
                .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            SlockLucideIconName.LoaderCircle,
            modifier = Modifier.size(14.dp),
            color = SlockColor.TextMutedGrayArgb,
            spinning = true
        )
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

private fun Modifier.threadMessagePressOutline(pressed: Boolean): Modifier =
    border(
        SlockStroke.Default,
        if (pressed) SlockColor.BorderBlack else SlockColor.BorderBlack.copy(alpha = 0f)
    )

@Composable
private fun Modifier.threadMessageLongPressActions(
    messageId: String,
    captureLongPressAnchor: Boolean = true,
    onShowActions: (Offset) -> Unit,
    onPressChanged: (Boolean) -> Unit = {}
): Modifier {
    val currentOnShowActions by rememberUpdatedState(onShowActions)
    val currentOnPressChanged by rememberUpdatedState(onPressChanged)
    val rootPos = remember { SlockMutableOffsetRef() }
    return this
        .then(
            if (captureLongPressAnchor) {
                Modifier.onGloballyPositioned { rootPos.update(it.positionInRoot()) }
            } else {
                Modifier
            }
        )
        .pointerInput(messageId) {
            detectTapGestures(
                onPress = {
                    currentOnPressChanged(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        currentOnPressChanged(false)
                    }
                },
                onLongPress = { localOffset ->
                    val base = rootPos.value.takeIf { rootPos.hasValue } ?: Offset.Zero
                    currentOnShowActions(Offset(base.x + localOffset.x, base.y + localOffset.y))
                }
            )
        }
}

internal fun KuiklyThreadMessageItem.resolvedThreadAvatarWireValue(): String? {
    if (threadAuthorBadgeLabel() != null) return null
    val isAgent = isThreadAgentAuthor()
    val resolution = resolveAvatarSlot(
        kind = if (isAgent) MemberKind.Agent else MemberKind.Human,
        id = authorId.ifBlank { authorName },
        avatarWireValue = avatarWireValue,
        gravatarHash = gravatarHash
    )
    val effectiveAvatarWireValue = resolution.effectiveAvatarWireValue
    if (effectiveAvatarWireValue?.isGravatarAvatarWireValue() == true) {
        logThreadAvatarDiagnostic(
            reason = if (isAgent) "render_agent_gravatar" else "render_human_gravatar",
            message = this,
            resolvedAvatarWireValue = effectiveAvatarWireValue,
            profileKind = null,
            profileAvatarWireValue = null,
            profileAvatarUrl = null,
            profileGravatarHash = null,
            homeAvatarWireValue = null
        )
    }
    return effectiveAvatarWireValue
}

internal fun KuiklyThreadMessageItem.threadAvatarGravatarUrl(): String? =
    gravatarHash
        ?.takeIf { threadAuthorBadgeLabel() == null }
        ?.takeIf { it.isNotBlank() }
        ?.toGravatarAvatarUrl()

private fun String.isLikelyThreadAgentId(): Boolean =
    startsWith("agent", ignoreCase = true) ||
        startsWith("ai-", ignoreCase = true) ||
        contains("_agent", ignoreCase = true) ||
        contains("-agent", ignoreCase = true)

@Composable
private fun ThreadMessageAuthorBadge(label: String) {
    Box(
        modifier =
            Modifier
                .heightIn(min = 18.dp)
                .background(SlockColor.EmptyStateGray)
                .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
                .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style =
                SlockType.Caption
                    .withSlockFontWeight(FontWeight.Bold)
                    .copy(fontSize = 10.sp, color = SlockColor.TextBlack.copy(alpha = 0.6f)),
            color = SlockColor.TextBlack.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun KuiklyThreadMessageItem.threadAuthorBadgeLabel(): String? =
    when {
        isDeletedThreadAuthor() -> "DELETED"
        isRemovedThreadHuman() -> "REMOVED"
        else -> null
    }

internal fun KuiklyThreadMessageItem.canOpenThreadAuthorProfile(): Boolean =
    threadAuthorBadgeLabel() == null

internal fun KuiklyThreadMessageItem.canOpenThreadAuthorProfile(shareSelectionActive: Boolean): Boolean =
    !shareSelectionActive && canOpenThreadAuthorProfile()

internal data class ThreadAgentStatusLookup(
    private val statusByMemberId: Map<String, AgentStatus>
) {
    fun indicatorStateForMemberId(memberId: String?): SlockAgentStatusIndicatorState? {
        val status = memberId.threadProjectionMemberId()?.let(statusByMemberId::get) ?: return null
        return slockAgentStatusIndicatorState(
            status = status,
            presence = status.presence
        )
    }

    companion object {
        fun from(
            serverMembersState: SharedServerMembersState,
            serverId: String?
        ): ThreadAgentStatusLookup {
            val statusByMemberId = linkedMapOf<String, AgentStatus>()
            serverMembersState.recordThreadAgentStatuses(serverId, statusByMemberId)
            return ThreadAgentStatusLookup(
                statusByMemberId = statusByMemberId.toMap()
            )
        }
    }
}

private fun SharedServerMembersState.recordThreadAgentStatuses(
    serverId: String?,
    statusByMemberId: MutableMap<String, AgentStatus>
) {
    presenceByMemberId.forEach { (memberId, event) ->
        val status = event.agentStatus ?: return@forEach
        memberId.threadProjectionMemberId()?.let { statusByMemberId[it] = status }
    }
    val cleanServerId = serverId.threadProjectionMemberId()
    val entries =
        if (cleanServerId != null) {
            listOfNotNull(entriesByServerId[cleanServerId])
        } else {
            entriesByServerId.values.toList()
        }
    entries
        .flatMap { entry -> entry.members }
        .forEach { member ->
            val status = presenceFor(member.id.value)?.agentStatus ?: member.agentStatus ?: return@forEach
            if (member.kind != MemberKind.Agent) return@forEach
            member.id.value.threadProjectionMemberId()?.let { memberId ->
                if (memberId !in statusByMemberId) {
                    statusByMemberId[memberId] = status
                }
            }
        }
}

private fun String?.threadProjectionMemberId(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun KuiklyThreadMessageItem.threadMentionHandle(): String? =
    authorHandle
        .ifBlank { authorName }
        .ifBlank { authorId }
        .trim()
        .removePrefix("@")
        .takeIf { it.isNotBlank() }
        ?.takeIf { threadAuthorBadgeLabel() == null }

private fun KuiklyThreadMessageItem.isDeletedThreadAuthor(): Boolean =
    !authorDeletedAt.isNullOrBlank() || senderMembershipStatus.equals("deleted", ignoreCase = true)

private fun KuiklyThreadMessageItem.isRemovedThreadHuman(): Boolean =
    !isThreadAgentAuthor() && senderMembershipStatus.equals("removed", ignoreCase = true)

private fun KuiklyThreadMessageItem.isThreadAgentAuthor(): Boolean =
    authorKind.equals("agent", ignoreCase = true) || authorId.isLikelyThreadAgentId()

internal fun shouldSuppressCompactThreadAuthor(
    previous: KuiklyThreadMessageItem?,
    next: KuiklyThreadMessageItem,
    localOffsetMinutes: Int
): Boolean =
    previous?.isCompactContinuationBefore(next, localOffsetMinutes) == true

private fun KuiklyThreadMessageItem.isCompactContinuationBefore(
    next: KuiklyThreadMessageItem,
    localOffsetMinutes: Int
): Boolean {
    if (isSystemMessage() || next.isSystemMessage()) return false
    val identityKey = authorIdentityKey()
    if (identityKey.isBlank() || identityKey != next.authorIdentityKey()) return false

    val currentEpochMillis = parseThreadMessageEpochMillisOrNull(localOffsetMinutes)
    val nextEpochMillis = next.parseThreadMessageEpochMillisOrNull(localOffsetMinutes)
    if (currentEpochMillis == null || nextEpochMillis == null) return true

    val elapsedMillis = nextEpochMillis - currentEpochMillis
    if (elapsedMillis !in 0..ThreadCompactContinuationWindowMillis) return false

    return threadLocalEpochDay(currentEpochMillis, localOffsetMinutes) ==
        threadLocalEpochDay(nextEpochMillis, localOffsetMinutes)
}

private fun KuiklyThreadMessageItem.parseThreadMessageEpochMillisOrNull(localOffsetMinutes: Int): Long? =
    parseSlockTimestampEpochMillisOrNull(timestampLabel, localOffsetMinutes)

private fun KuiklyThreadMessageItem.authorIdentityKey(): String =
    authorId.takeIf { it.isNotBlank() }
        ?: authorHandle.takeIf { it.isNotBlank() }
        ?: authorName.takeIf { it.isNotBlank() }
        ?: ""

private fun threadLocalEpochDay(epochMillis: Long, localOffsetMinutes: Int): Long =
    threadFloorDiv(epochMillis + localOffsetMinutes.toLong() * ThreadMillisPerMinute, ThreadMillisPerDay)

private fun threadFloorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && ((remainder < 0) != (divisor < 0))) quotient - 1 else quotient
}

private const val ThreadMillisPerMinute = 60_000L
private const val ThreadMillisPerDay = 86_400_000L
private const val ThreadCompactContinuationWindowMillis = 30L * ThreadMillisPerMinute

@Composable
private fun ThreadMessageFooterMeta(
    message: KuiklyThreadMessageItem,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    hasReplyDraft: Boolean = false,
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
    val hasTask = message.hasThreadTaskFooterMeta()
    val hasReplies = replyMetadata.replyCount > 0 || hasReplyDraft
    val visibleReactions = message.reactions.filter { it.count > 0 }
    val hasReactions = visibleReactions.isNotEmpty()
    if (!hasTask && !hasReplies && !hasReactions) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (alignEnd) Arrangement.spacedBy(6.dp, Alignment.End) else Arrangement.spacedBy(6.dp)
    ) {
        if (hasTask) {
            val label = threadMessageTaskMetaLabel(message, resolvedTaskAssigneeName) ?: "Task"
            val selectedStatus = message.taskStatus.threadTaskStatusOption()
            val taskChipClick: ((Offset) -> Unit)? =
                when {
                    taskStatusActionsEnabled -> onOpenTaskStatusMenu
                    taskOpenEnabled -> { _ -> message.taskId?.let(onOpenTask) }
                    else -> null
                }
            SlockAnchoredMetaChip(
                label = label,
                iconName = selectedStatus.icon,
                iconSize = selectedStatus.iconSize,
                color = selectedStatus.color,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 260.dp),
                onClick = taskChipClick
            )
        }
        if (hasReplies) {
            val replyLabel = threadMessageRepliesMetaLabel(replyMetadata)
            SlockAnchoredMetaChip(
                label = replyLabel,
                iconName = SlockLucideIconName.MessageSquare,
                iconSize = 12.dp,
                trailingIconName = SlockLucideIconName.Pencil.takeIf { hasReplyDraft },
                trailingIconSize = 12.dp,
                color = SlockColor.SurfaceWhite,
                modifier = Modifier.widthIn(max = 220.dp),
                pressFeedback = false,
                onClick = if (showReplyAction) { _ -> onOpenThread() } else null
            )
        }
        if (hasReactions) {
            visibleReactions.forEach { reaction ->
                key("reaction:${message.id}:${reaction.emoji}") {
                    ThreadReactionChip(
                        reaction = reaction,
                        onClick = onToggleReaction?.let { callback -> { callback(reaction.emoji) } }
                    )
                }
            }
        }
    }
}

internal fun KuiklyThreadMessageItem.hasThreadFooterMeta(hasReplyDraft: Boolean = false): Boolean =
    hasThreadFooterMeta(toThreadReplyMetadata(), hasReplyDraft)

internal fun KuiklyThreadMessageItem.hasThreadFooterMeta(
    replyMetadata: ThreadReplyMetadata,
    hasReplyDraft: Boolean = false
): Boolean =
    hasThreadTaskFooterMeta() ||
        replyMetadata.replyCount > 0 ||
        hasReplyDraft ||
        reactions.any { it.count > 0 }

private fun KuiklyThreadMessageItem.hasThreadTaskFooterMeta(): Boolean =
    !taskId.isNullOrBlank() ||
        taskNumber != null ||
        !taskStatus.isNullOrBlank() ||
        !taskAssigneeName.isNullOrBlank()

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

internal fun threadMessageRepliesMetaLabel(replyMetadata: ThreadReplyMetadata): String =
    buildString {
        append(replyMetadata.replyCount)
        append(if (replyMetadata.replyCount == 1) " reply" else " replies")
        if (replyMetadata.unreadReplyCount > 0) {
            append(" · ")
            append(replyMetadata.unreadReplyCount)
            append(" new")
        }
    }

internal fun Set<SharedThreadComposerDraftRecord>.toReplyComposerDraftParentMessageIds(): Set<String> =
    mapNotNull { record ->
        record.parentMessageId?.takeIf { !record.isRootComposer && it.isNotBlank() }
    }.toSet()

internal fun Set<String>.hasReplyComposerDraft(parentMessageId: String): Boolean =
    parentMessageId in this

internal fun KuiklyThreadScreenPayload.threadBeginningReplyCountLabel(): String? {
    val replyCount = originMessage?.replyCount ?: return null
    return buildString {
        append(replyCount.coerceAtLeast(0))
        append(if (replyCount == 1) " reply" else " replies")
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

internal fun KuiklyThreadMessageItem.systemContentSummary(): String =
    body.sanitizeThreadSystemText()

internal fun KuiklyThreadMessageItem.expandedSystemContent(): String =
    listOf(systemContentSummary(), expandedSystemDetails())
        .filter { it.isNotBlank() }
        .joinToString("\n")

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
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

private val ThreadMessageMenuWidth = 240.dp
private val ThreadMessageMenuFallbackHeight = 250.dp
private val ThreadMessageMenuAnchorGap = 8.dp
private val ThreadMessageMenuHorizontalNudge = 16.dp

private fun threadMessageMenuActions(
    message: KuiklyThreadMessageItem,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    showReplyAction: Boolean,
    showSaveAction: Boolean,
    showTaskStatusAction: Boolean,
    showConvertToTaskAction: Boolean,
    showCopyLinkAction: Boolean,
    showShareMessagesAction: Boolean,
    showCancelAction: Boolean,
    showDeleteAction: Boolean,
    onReply: () -> Unit,
    onCopyLink: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onShareMessages: () -> Unit,
    onSave: () -> Unit,
    onFollowThread: () -> Unit,
    onUnfollowThread: (String) -> Unit,
    onTaskStatusChange: (String) -> Unit,
    onConvertToTask: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
): List<ThreadMessageMenuAction> =
    buildList {
        if (showCopyLinkAction) {
            add(
                ThreadMessageMenuAction(
                    label = "Copy Link",
                    icon = SlockLucideIconName.Link,
                    group = "copy",
                    onClick = onCopyLink
                )
            )
        }
        add(ThreadMessageMenuAction("Copy Markdown", SlockLucideIconName.Copy, "copy", onClick = onCopy))
        add(ThreadMessageMenuAction("Select Text", SlockLucideIconName.FileText, "copy", onClick = onSelectText))
        if (showShareMessagesAction) {
            add(
                ThreadMessageMenuAction(
                    label = "Share Messages...",
                    icon = SlockLucideIconName.Share2,
                    group = "copy",
                    onClick = onShareMessages
                )
            )
        }
        if (showReplyAction) {
            add(ThreadMessageMenuAction("Open Thread", SlockLucideIconName.MessageSquare, "message", onClick = onReply))
        }
        if (showSaveAction) {
            add(
                ThreadMessageMenuAction(
                    label = if (message.isSaved) "Remove from Saved" else "Save Message",
                    icon = SlockLucideIconName.Bookmark,
                    group = "message",
                    onClick = onSave
                )
            )
        }
        val followThreadChannelId = replyMetadata.threadChannelId?.takeIf { it.isNotBlank() }
        if (followThreadChannelId != null || replyMetadata.replyCount > 0 || replyMetadata.isThreadFollowed) {
            add(
                ThreadMessageMenuAction(
                    label = if (replyMetadata.isThreadFollowed) "Unfollow Thread" else "Follow Thread",
                    icon = SlockLucideIconName.MessageSquare,
                    group = "message",
                    onClick = {
                        if (replyMetadata.isThreadFollowed && followThreadChannelId != null) {
                            onUnfollowThread(followThreadChannelId)
                        } else {
                            onFollowThread()
                        }
                    }
                )
            )
        }
        if (showDeleteAction) {
            add(
                ThreadMessageMenuAction(
                    label = "Delete",
                    icon = SlockLucideIconName.Trash,
                    group = "destructive",
                    onClick = onDelete
                )
            )
        }
        if (showCancelAction) {
            add(
                ThreadMessageMenuAction(
                    label = "Cancel",
                    icon = SlockLucideIconName.X,
                    group = "destructive",
                    onClick = onCancel
                )
            )
        }
        message.taskId?.takeIf { showTaskStatusAction && it.isNotBlank() }?.let {
            val isDone = message.taskStatus?.trim()?.lowercase() in setOf("done", "closed")
            add(
                ThreadMessageMenuAction(
                    label = if (isDone) "Reopen Task" else "Mark as Done",
                    icon = if (isDone) SlockLucideIconName.RefreshCw else SlockLucideIconName.CircleCheckBig,
                    group = "task",
                    onClick = { onTaskStatusChange(if (isDone) "reopenTask" else "markTaskDone") }
                )
            )
        }
        if (showConvertToTaskAction && message.canConvertToTask()) {
            add(
                ThreadMessageMenuAction(
                    label = "Convert to Task",
                    icon = SlockLucideIconName.ClipboardCheck,
                    group = "task",
                    onClick = onConvertToTask
                )
            )
        }
    }

@Composable
private fun ThreadMessageActionMenu(
    message: KuiklyThreadMessageItem,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    anchor: Offset,
    showReplyAction: Boolean,
    showSaveAction: Boolean,
    showTaskStatusAction: Boolean,
    showConvertToTaskAction: Boolean,
    showReactionActions: Boolean,
    showCopyLinkAction: Boolean,
    showShareMessagesAction: Boolean,
    showCancelAction: Boolean,
    showDeleteAction: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopyLink: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onShareMessages: () -> Unit,
    onSave: () -> Unit,
    onFollowThread: () -> Unit,
    onUnfollowThread: (String) -> Unit,
    onReact: (String) -> Unit,
    onTaskStatusChange: (String) -> Unit,
    onConvertToTask: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    val actions =
        remember(
            message,
            replyMetadata,
            showReplyAction,
            showSaveAction,
            showTaskStatusAction,
            showConvertToTaskAction,
            showCopyLinkAction,
            showShareMessagesAction,
            showCancelAction,
            showDeleteAction,
            onReply,
            onCopyLink,
            onCopy,
            onSelectText,
            onShareMessages,
            onSave,
            onFollowThread,
            onUnfollowThread,
            onTaskStatusChange,
            onConvertToTask,
            onCancel,
            onDelete
        ) {
            threadMessageMenuActions(
                message = message,
                replyMetadata = replyMetadata,
                showReplyAction = showReplyAction,
                showSaveAction = showSaveAction,
                showTaskStatusAction = showTaskStatusAction,
                showConvertToTaskAction = showConvertToTaskAction,
                showCopyLinkAction = showCopyLinkAction,
                showShareMessagesAction = showShareMessagesAction,
                showCancelAction = showCancelAction,
                showDeleteAction = showDeleteAction,
                onReply = onReply,
                onCopyLink = onCopyLink,
                onCopy = onCopy,
                onSelectText = onSelectText,
                onShareMessages = onShareMessages,
                onSave = onSave,
                onFollowThread = onFollowThread,
                onUnfollowThread = onUnfollowThread,
                onTaskStatusChange = onTaskStatusChange,
                onConvertToTask = onConvertToTask,
                onCancel = onCancel,
                onDelete = onDelete
            )
        }
    var menuSize by remember { mutableStateOf(Size.Zero) }
    val density = LocalDensity.current
    val menuWidth = menuSize.width.takeIf { it > 0f } ?: with(density) { ThreadMessageMenuWidth.toPx() }
    val menuHeight = menuSize.height.takeIf { it > 0f } ?: with(density) { ThreadMessageMenuFallbackHeight.toPx() }
    val anchorGapPx = with(density) { ThreadMessageMenuAnchorGap.toPx() }
    val horizontalNudgePx = with(density) { ThreadMessageMenuHorizontalNudge.toPx() }
    val shadowOffsetPx = with(density) { 4.dp.toPx() }
    val contentSize = Size(
        width = menuWidth + shadowOffsetPx,
        height = menuHeight + shadowOffsetPx
    )
    SlockAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        viewportGap = anchorGapPx,
        horizontalOffset = -horizontalNudgePx,
        verticalOffset = anchorGapPx,
        horizontalAutoFlip = true,
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
        ThreadMessageMenuChrome(
            actions = actions,
            showReactionActions = showReactionActions,
            onReact = onReact,
            modifier =
                popoverModifier
                    .onGloballyPositioned { coordinates ->
                        menuSize = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                    }
        )
    }
}

@Composable
private fun ThreadMessageTextSelectionDialog(
    message: KuiklyThreadMessageItem,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val originalText = message.body
    val topSafeArea = slockTopSafeAreaPadding()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    var fieldValue by remember(message.id, originalText) {
        mutableStateOf(TextFieldValue(originalText, selection = TextRange(0, originalText.length)))
    }
    Modal(onWillDismiss = { onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topSafeArea + 56.dp)
                    .padding(start = 16.dp, top = topSafeArea + 10.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Copy,
                    onClick = onCopyAll,
                    backgroundColor = SlockColor.BrandYellow,
                    size = 36.dp,
                    shadowOffset = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.X,
                    onClick = onDismiss,
                    size = 36.dp,
                    shadowOffset = 2.dp
                )
            }
            TextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                autoFocus = true,
                maxLines = Int.MAX_VALUE,
                systemNewlineAction = true,
                contentAlignment = Alignment.TopStart,
                textStyle =
                    SlockType.Body.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = SlockColor.TextBlack
                    ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomSafeArea + 16.dp)
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun ThreadMessageMenuChrome(
    actions: List<ThreadMessageMenuAction>,
    showReactionActions: Boolean,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(end = 4.dp, bottom = 4.dp)
            .width(ThreadMessageMenuWidth)
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
            if (showReactionActions) {
                ThreadMessageReactionRow(onReact = onReact)
                ThreadMessageMenuDivider()
            }
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

@Composable
private fun ThreadMessageReactionRow(onReact: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 12.dp),
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
    emoji

@Composable
private fun ThreadQuickReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier
) {
    val sprite = ThreadReactionSpriteCatalogV1.resolve(emoji)
    if (sprite != null) {
        ThreadReactionSpriteGlyph(sprite = sprite, modifier = modifier)
        return
    }
    ThreadReactionGlyphDiagnosticLog(
        path = "legacy_no_sprite",
        emoji = emoji,
        detail = "reason=no_manifest_match"
    )
    ThreadLegacyQuickReactionGlyph(emoji = emoji, modifier = modifier)
}

@Composable
private fun ThreadReactionSpriteGlyph(
    sprite: ThreadReactionSprite,
    modifier: Modifier = Modifier
) {
    val image = imageResource(ThreadReactionSpriteAtlasDrawableResource)
    val imageReady = image.width > 0 && image.height > 0
    if (!imageReady) {
        ThreadReactionGlyphDiagnosticLog(
            path = "sprite_atlas_not_ready",
            emoji = sprite.value,
            detail = "sprite=${sprite.name} imageWidth=${image.width} imageHeight=${image.height}"
        )
        ThreadLegacyQuickReactionGlyph(emoji = sprite.value, modifier = modifier)
        return
    }
    ThreadReactionGlyphDiagnosticLog(
        path = "sprite_atlas_draw",
        emoji = sprite.value,
        detail = "sprite=${sprite.name} atlas=${ThreadReactionSpriteCatalogV1.atlas.imageName}"
    )
    val rect = sprite.rect
    Canvas(modifier = modifier) {
        drawImage(
            image = image,
            srcOffset = IntOffset(rect.x, rect.y),
            srcSize = IntSize(rect.width, rect.height),
            dstOffset = IntOffset.Zero,
            dstSize = size.toIntSize()
        )
    }
}

@Composable
private fun ThreadReactionGlyphDiagnosticLog(
    path: String,
    emoji: String,
    detail: String
) {
    SideEffect {
        ThreadReactionGlyphDiagnostics.logOnce(path = path, emoji = emoji, detail = detail)
    }
}

@Composable
private fun ThreadLegacyQuickReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier
) {
    if (!threadHasCustomReactionGlyph(emoji)) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = emoji,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                    fontSize = if (emoji.length > 1) 10.sp else 13.sp,
                    lineHeight = 12.sp
                ),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }
        return
    }
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
            else -> Unit
        }
    }
}

private fun threadHasCustomReactionGlyph(emoji: String): Boolean =
    when (emoji) {
        "👍",
        "❤️",
        "❤",
        "🎉",
        "👀",
        "🔥",
        "😂",
        "✅",
        "☑",
        "☑️",
        "✔",
        "✔️",
        "✓" -> true
        else -> false
    }

@Composable
private fun ThreadMessageMenuItem(action: ThreadMessageMenuAction) {
    Button(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SlockLucideIcon(
                action.icon,
                modifier = Modifier.size(14.dp),
                color = if (action.enabled) "#FF000000" else "#FF6B625F"
            )
            Text(
                text = action.label,
                style = (if (action.enabled) SlockType.Button else SlockType.Body)
                    .copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = if (action.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
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
    renderConfig: ThreadTimelineRenderConfig,
    timelineSlice: ThreadTimelineSlice,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection(),
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    diagnosticsLogger: (String) -> Unit = {},
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onShowActions: (KuiklyThreadMessageItem, Offset) -> Unit,
    onOpenTaskStatusMenu: (KuiklyThreadMessageItem, Offset) -> Unit,
    onMentionAuthor: (String) -> Unit,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    shareSelectionActive: Boolean = false,
    selectedShareMessageIds: Set<String> = emptySet(),
    onToggleShareSelection: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (timelineSlice.originStatus) {
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
                        text = renderConfig.originPreview?.ifBlank { "Original message is unavailable." }
                            ?: "Original message is unavailable.",
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack
                    )
                    renderConfig.originErrorCode?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "Refresh failed: $it",
                            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray
                        )
                    }
                }
            }
            else -> {
                timelineSlice.originMessage?.let { origin ->
                    val linkedTask = linkedTasksProjection.lookup.threadLinkedTaskForMessage(origin)
                    val displayOrigin = origin.withLinkedTaskProjection(linkedTasksProjection)
                    val linkedTaskAssigneeName = linkedTask?.threadLinkedTaskAssigneeDisplayName()
                    val originActionCard = if (contentExtrasVisible) origin.threadActionCard() else null
                    if (displayOrigin.isSystemMessage() && originActionCard == null) {
                        ThreadSystemMessageRow(
                            message = displayOrigin,
                            timestampLabel = renderConfig.formatTimestampLabel(origin.timestampLabel)
                        )
                    } else {
                        ThreadMessageCard(
                            message = displayOrigin,
                            hasReplyDraft = false,
                            actionCard = originActionCard,
                            highlightedMentions = highlightedMentions,
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver,
                            shareSelectionActive = shareSelectionActive,
                            selectedForShare = origin.id in selectedShareMessageIds,
                            onToggleShareSelection = { onToggleShareSelection(origin.id) },
                            showReplyAction = false,
                            resolvedTaskAssigneeName =
                                linkedTaskAssigneeName
                                    ?: renderConfig.resolveTaskAssigneeDisplayName(
                                        value = displayOrigin.taskAssigneeName,
                                        id = displayOrigin.taskAssigneeId
                                    ),
                            resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                            taskOpenEnabled = timelineSlice.showTaskOpenActions && contentExtrasVisible,
                            taskStatusActionsEnabled = timelineSlice.showTaskStatusActions && contentExtrasVisible,
                            showSaveAction = timelineSlice.showMessageSaveAction,
                            isMarkdownTagValid = isMarkdownTagValid,
                            onShowActions = { anchor -> onShowActions(origin, anchor) },
                            onOpenMember = onOpenMember,
                            onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor -> onOpenTaskStatusMenu(origin, anchor) },
                            onToggleReaction = { emoji -> onThreadEvent("toggleReaction", origin.id, null, emoji) },
                            onToggleSaved = { onThreadEvent("toggleMessageSaved", origin.id, null, null) },
                            onOpenCommentHost = { onThreadEvent("openCommentHost", origin.id, null, null) },
                            timestampLabel = renderConfig.formatTimestampLabel(origin.timestampLabel),
                            onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                            attachmentCommentsEnabled = attachmentCommentsEnabled,
                            onOpenAttachmentComments = { onThreadEvent("openAttachmentComments", origin.id, it, null) },
                            attachmentOpenStates = if (contentExtrasVisible) attachmentOpenStates else emptyMap(),
                            pendingActionCardIds = if (contentExtrasVisible) pendingActionCardIds else emptySet(),
                            onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
                            currentServerSlug = currentServerSlug,
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl,
                            onMentionAuthor = onMentionAuthor,
                            richContentEnabled = contentExtrasVisible
                        )
                    }
                } ?: renderConfig.originPreview?.takeIf { it.isNotBlank() }?.let {
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
private fun ThreadMessageTimestampLabel(timestampLabel: String) {
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

@Composable
private fun ThreadMessageAvatarSlot(
    showAvatar: Boolean,
    showTimestamp: Boolean,
    timestampLabel: String,
    avatarLabel: String,
    avatarWireValue: String?,
    gravatarUrl: String? = null,
    status: SlockAgentStatusIndicatorState?,
    statusPulsing: Boolean = true,
    muted: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val slotHeight =
        when {
            showAvatar -> 44.dp
            showTimestamp -> 18.dp
            else -> 0.dp
        }
    Box(
        modifier = Modifier.width(40.dp).height(slotHeight),
        contentAlignment = Alignment.TopCenter
    ) {
        when {
            showAvatar ->
                ThreadMessageAvatar(
                    avatarLabel = avatarLabel,
                    avatarWireValue = avatarWireValue,
                    gravatarUrl = gravatarUrl,
                    status = status,
                    statusPulsing = statusPulsing,
                    muted = muted,
                    onClick = onClick,
                    onLongPress = onLongPress
                )
            showTimestamp ->
                Text(
                    text = timestampLabel,
                    style =
                        SlockType.Caption.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = SlockColor.TextBlack.copy(alpha = 0.38f)
                        ),
                    color = SlockColor.TextBlack.copy(alpha = 0.38f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
        }
    }
}

@Composable
private fun ThreadMessageAvatar(
    avatarLabel: String,
    avatarWireValue: String?,
    gravatarUrl: String? = null,
    status: SlockAgentStatusIndicatorState?,
    statusPulsing: Boolean = true,
    muted: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val avatarSource =
        remember(avatarWireValue) {
            avatarWireValue?.takeIf { it.isNotBlank() }
        }
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .pointerInput(onClick, onLongPress) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongPress() }
                    )
                },
        contentAlignment = Alignment.Center
    ) {
        SlockAvatarWithStatus(
            statusColor = if (muted) null else status?.color,
            avatarSize = 40.dp,
            // #90 React parity: bump chat thread avatar overlay status dot
            // 10dp → 11dp to match React `StatusDot size="lg"` (size-[11px]).
            statusSize = 11.dp,
            statusPulsing = status?.pulsing == true && statusPulsing
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                SlockAvatar(
                    avatarWireValue = avatarSource,
                    label = avatarLabel,
                    modifier = if (muted) Modifier.alpha(0.62f) else Modifier,
                    avatarSize = 40.dp,
                    borderWidth = SlockStroke.Default,
                    gravatarUrl = gravatarUrl
                )
                if (muted) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(SlockColor.EmptyStateGray.copy(alpha = 0.38f))
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageStaticAvatar(
    avatarLabel: String,
    avatarWireValue: String?,
    gravatarUrl: String? = null,
    status: SlockAgentStatusIndicatorState?,
    statusPulsing: Boolean = true
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
            statusColor = status?.color,
            avatarSize = 40.dp,
            // #90 React parity: bump chat thread avatar overlay status dot
            // 10dp → 11dp to match React `StatusDot size="lg"` (size-[11px]).
            statusSize = 11.dp,
            statusPulsing = status?.pulsing == true && statusPulsing
        ) {
            SlockAvatar(
                avatarWireValue = avatarSource,
                label = avatarLabel,
                avatarSize = 40.dp,
                borderWidth = SlockStroke.Default,
                gravatarUrl = gravatarUrl
            )
        }
    }
}

private fun KuiklyThreadMessageItem.showsAgentStatusIndicator(): Boolean =
    isThreadAgentAuthor() && threadAuthorBadgeLabel() == null

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

internal fun KuiklyThreadAttachmentItem.isImageAttachment(): Boolean =
    mimeType.startsWith("image/", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.isPdfAttachment(): Boolean =
    mimeType.substringBefore(';').trim().equals("application/pdf", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.normalizedMimeType(): String =
    mimeType.substringBefore(';').trim().lowercase()

private fun KuiklyThreadAttachmentItem.isMarkdownAttachment(): Boolean =
    slockFilePreviewIsMarkdownAttachment(fileName = name, mimeType = mimeType)

private fun KuiklyThreadAttachmentItem.isTextLikeAttachment(): Boolean =
    !isMarkdownAttachment() && slockFilePreviewIsTextLikeAttachment(fileName = name, mimeType = mimeType)

private fun KuiklyThreadAttachmentItem.isMp4VideoAttachment(): Boolean =
    slockFilePreviewIsMp4VideoAttachment(fileName = name, mimeType = mimeType)

private fun KuiklyThreadAttachmentItem.isPreviewableDocumentAttachment(): Boolean =
    isPdfAttachment() ||
        isMarkdownAttachment() ||
        isTextLikeAttachment()

private fun KuiklyThreadAttachmentItem.isHtmlAttachment(): Boolean =
    normalizedMimeType() == "text/html" || name.endsWith(".html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.threadAttachmentUsesPreviewAffordance(): Boolean =
    isPreviewableDocumentAttachment() || isHtmlAttachment() || isMp4VideoAttachment()

internal fun KuiklyThreadAttachmentItem.genericAttachmentMetaLine(): String =
    listOf(sizeLabel, mimeType)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

private fun KuiklyThreadAttachmentItem.threadAttachmentDownloadId(): String =
    listOf("thread_attachment", id.ifBlank { name.ifBlank { "attachment" } }, name.ifBlank { "attachment" })
        .joinToString(":")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' || it == '.' }
        .take(160)
        .ifBlank { "thread-attachment-download" }

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
        isMp4VideoAttachment() -> "Video preview"
        isTextLikeAttachment() || isHtmlAttachment() -> "Text preview"
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

private fun AttachmentUploadFile.composerLocalPreviewUri(): String? =
    fileRef?.takeIf { it.isNotBlank() }
        ?: previewUri?.takeIf { it.isNotBlank() }

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
        modifier = Modifier.width(SlockAttachmentPreviewCardWidth),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockAttachmentPreviewCard(
            title = attachment.name,
            meta = meta,
            summary = summaryLabel,
            affordanceIcon = affordanceIcon,
            loading = isOpening,
            onClick = if (isOpening) ({}) else onOpen
        )
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
            iconSpinning = state.sending,
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
    threadInstanceKey: String,
    basePayload: KuiklyThreadScreenPayload,
    filesStartKey: String?,
    filesViewModel: SharedThreadFilesViewModel,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    onAuthRequired: (String?) -> Unit,
    onOpenAttachment: (KuiklyThreadAttachmentItem, ThreadImageAttachmentGroup?) -> Unit,
    onDownloadAttachment: (KuiklyThreadAttachmentItem) -> Unit,
    onOpenFileSource: (KuiklyThreadFileItem) -> Unit
) {
    LaunchedEffect(
        threadInstanceKey,
        filesStartKey,
        basePayload.sourceThreadId,
        basePayload.streamChannelId,
        basePayload.instanceId
    ) {
        if (filesStartKey == null) return@LaunchedEffect
        filesViewModel.dispatch(
            SharedThreadFilesIntent.Start(
                basePayload = basePayload
            )
        )
        if (filesViewModel.state.value.payload.shouldFetchInitialFiles()) {
            filesViewModel.dispatch(SharedThreadFilesIntent.Refresh("initial_tab"))
        }
    }
    val filesState by filesViewModel.state.collectAsState()
    val payload = filesState.payload
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
            "source" -> {
                val cleanAttachmentId = attachmentId?.takeIf { it.isNotBlank() } ?: return@ThreadFilesContent
                val fileItem = payload.items.firstOrNull { it.attachmentId == cleanAttachmentId } ?: return@ThreadFilesContent
                onOpenFileSource(fileItem)
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


private fun String.threadTaskAssigneeKey(): String =
    trim()
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '@' || it == '.' || it == '-' || it == '_' }
        .ifBlank { "unknown" }

@Composable
private fun ThreadJoinFooter(label: String, onJoin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockBrutalActionButton(
            label = label,
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = SlockColor.AccentPink,
            minWidth = 0.dp,
            minHeight = 40.dp,
            leadingIcon = SlockLucideIconName.LogIn,
            textStyle = SlockType.Button.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
        )
    }
}

@Composable
private fun ThreadShareSelectionToolbar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onCopyMarkdown: () -> Unit
) {
    val hasSelection = selectedCount > 0
    val bottomSafeArea = slockBottomSafeAreaPadding()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceWhite)
                .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + bottomSafeArea),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (selectedCount == 1) "1 message selected" else "$selectedCount messages selected",
                    style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.X,
                onClick = onCancel,
                size = 34.dp,
                shadowOffset = 2.dp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockBrutalActionButton(
                label = "Share",
                leadingIcon = SlockLucideIconName.Share2,
                onClick = onShare,
                enabled = hasSelection,
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
                minHeight = 38.dp,
                backgroundColor = SlockColor.AccentPink,
                textStyle = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp)
            )
            SlockBrutalActionButton(
                label = "Copy MD",
                leadingIcon = SlockLucideIconName.Copy,
                onClick = onCopyMarkdown,
                enabled = hasSelection,
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
                minHeight = 38.dp,
                backgroundColor = SlockColor.SurfaceWhite,
                textStyle = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp)
            )
        }
    }
}

@Composable
private fun ThreadSharePreviewModal(
    messages: List<KuiklyThreadMessageItem>,
    image: ThreadSharePreviewImage?,
    onDismiss: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onDownloadImage: () -> Unit
) {
    SlockUnifiedDialog(
        title = "Share preview",
        onDismiss = onDismiss,
        minWidth = 320.dp,
        maxWidth = 360.dp,
        contentPadding = 16.dp,
        titleFontSize = 16,
        titleLineHeight = 20,
        contentSpacing = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (image?.uri.isNullOrBlank()) {
                ThreadModalEmptyState(
                    title = "IMAGE EXPORT FAILED",
                    detail = "TRY AGAIN AFTER THE SELECTED MESSAGES ARE VISIBLE."
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(threadSharePreviewImageHeight(image).dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(8.dp)
                ) {
                    SlockCachedImage(
                        source = image.uri,
                        contentDescription = "Selected messages image preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                SlockModalActionButton(
                    label = "Copy MD",
                    icon = SlockLucideIconName.Copy,
                    onClick = onCopyMarkdown,
                    enabled = messages.isNotEmpty()
                )
                SlockModalActionButton(
                    label = "Download",
                    icon = SlockLucideIconName.Download,
                    onClick = onDownloadImage,
                    enabled = !image?.path.isNullOrBlank()
                )
            }
        }
    }
}

private fun threadSharePreviewImageHeight(image: ThreadSharePreviewImage?): Int {
    val width = image?.width?.takeIf { it > 0 } ?: return 280
    val height = image.height.takeIf { it > 0 } ?: return 280
    return ((height.toFloat() / width.toFloat()) * 320f).roundToInt().coerceIn(180, 440)
}

@Composable
private fun ThreadMembersModal(
    members: List<KuiklyThreadMemberItem>,
    membersCount: Int,
    canAddMembers: Boolean,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    removeMemberStatus: String,
    removingMemberId: String?,
    removeMemberErrorCode: String?,
    onDismiss: () -> Unit,
    onAddMember: () -> Unit,
    onOpenMember: (KuiklyThreadMemberItem) -> Unit,
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
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                sourceBadgeLabel = member.sourceServerLabel,
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                enabled = !removing,
                                trailingIcon = SlockLucideIconName.X.takeIf { member.canRemove && !removing },
                                trailingText = if (removing) "REMOVING" else "",
                                onTrailingClick = if (member.canRemove && !removing) ({ onRemoveMember(member.id) }) else null,
                                onClick = { onOpenMember(member) }
                            )
                        }
                    }
                    if (humans.isNotEmpty()) {
                        item { SlockModalSectionHeader("HUMANS") }
                        items(humans.size, key = { humans[it].memberKey }) { index ->
                            val member = humans[index]
                            val removing = removeMemberStatus == "removing" && removingMemberId == member.id
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                sourceBadgeLabel = member.sourceServerLabel,
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                enabled = !removing,
                                trailingIcon = SlockLucideIconName.X.takeIf { member.canRemove && !removing },
                                trailingText = if (removing) "REMOVING" else "",
                                onTrailingClick = if (member.canRemove && !removing) ({ onRemoveMember(member.id) }) else null,
                                onClick = { onOpenMember(member) }
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
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    addMemberStatus: String,
    addMemberErrorCode: String?,
    initialSelectedId: String? = null,
    showSelectedCheckmark: Boolean = true,
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
        minWidth = 356.dp,
        maxWidth = 356.dp,
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
            Text(
                text = "SEARCH",
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            SlockBrutalSearchField(
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
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (showSelectedCheckmark && member.id == selectedId) SlockLucideIconName.Check else null,
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
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (showSelectedCheckmark && member.id == selectedId) SlockLucideIconName.Check else null,
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

private fun KuiklyThreadScreenPayload.withThreadMembersProjection(
    projection: ThreadMembersProjection
): KuiklyThreadScreenPayload =
    copy(
        membersCount = projection.membersCount,
        members = projection.members,
        memberCandidates = projection.memberCandidates,
        canAddMembers = projection.canAddMembers
    )

private fun threadMembersProjection(
    basePayload: KuiklyThreadScreenPayload,
    channelMembersSlice: SharedServerChannelMembersSlice,
    serverMembersState: SharedServerMembersState,
    serverId: ServerId?
): ThreadMembersProjection {
    val loadedMembers =
        channelMembersSlice.members
            .distinctBy { it.threadMemberKey }
            .map { it.toKuiklyThreadMemberItem(serverId) }
    val members =
        if (channelMembersSlice.status == SharedServerMembersLoadStatus.Loaded) {
            loadedMembers
        } else {
            basePayload.members
        }
    val memberKeys = members.map { it.memberKey }.toSet()
    val serverMembers =
        serverId
            ?.let { serverMembersState.entryFor(it)?.members }
            .orEmpty()
    val candidates =
        if (serverMembers.isEmpty()) {
            basePayload.memberCandidates
        } else {
            serverMembers
                .filterNot { it.threadMemberKey in memberKeys }
                .map { it.toKuiklyThreadMemberItem(serverId) }
        }
    return ThreadMembersProjection(
        membersCount =
            when {
                channelMembersSlice.status == SharedServerMembersLoadStatus.Loaded -> members.size
                basePayload.membersCount > 0 -> basePayload.membersCount
                else -> members.size
            },
        members = members,
        memberCandidates = candidates,
        canAddMembers =
            if (channelMembersSlice.status == SharedServerMembersLoadStatus.Loaded) {
                channelMembersSlice.canAddMembers
            } else {
                basePayload.canAddMembers
            }
    )
}

private val MemberProfile.threadMemberKey: String
    get() = "${kind.name.lowercase()}:${id.value.trim()}"

private fun MemberProfile.toKuiklyThreadMemberItem(localServerId: ServerId? = null): KuiklyThreadMemberItem =
    KuiklyThreadMemberItem(
        id = id.value,
        displayName = displayName,
        handle = handle,
        roleLabel =
            roleDescription
                ?: when (kind) {
                    MemberKind.Agent -> "Agent"
                    MemberKind.Human -> "Member"
                    MemberKind.System -> "System"
                },
        avatarUrl = avatarWireValueForAvatarSlot(),
        avatarLabel = avatarLabel,
        kind = kind.name.lowercase(),
        sourceServerId = serverId?.value,
        sourceServerLabel = remoteSourceServerLabel(localServerId),
        canRemove = isLocalToServer(localServerId),
        presence = presence.takeIf { kind == MemberKind.Agent && (agentStatus != null || presence != Presence.Offline) },
        agentStatus = agentStatus.takeIf { kind == MemberKind.Agent }
    )

private fun MemberProfile.isLocalToServer(localServerId: ServerId?): Boolean {
    val sourceId = serverId?.value?.takeIf { it.isNotBlank() } ?: return true
    val localId = localServerId?.value?.takeIf { it.isNotBlank() } ?: return true
    return sourceId == localId
}

private fun MemberProfile.remoteSourceServerLabel(localServerId: ServerId?): String? {
    if (isLocalToServer(localServerId)) return null
    return serverName?.takeIf { it.isNotBlank() } ?: serverSlug?.takeIf { it.isNotBlank() }
}

private fun KuiklyThreadMemberItem.modalAvatarWireValue(): String? =
    avatarUrl?.takeIf { it.isNotBlank() }

private fun KuiklyThreadMemberItem.modalAvatarLabel(): String =
    avatarLabel.ifBlank { displayName.ifBlank { handle.ifBlank { id } } }

@Composable
private fun KuiklyThreadMemberItem.modalAgentStatusIndicatorState(
    agentStatusResolver: SlockAgentStatusResolver
): SlockAgentStatusIndicatorState? {
    if (!kind.equals("agent", ignoreCase = true)) return null
    return rememberSlockAgentStatusIndicatorState(agentStatusResolver, id)
        ?: slockAgentStatusIndicatorState(status = null, presence = Presence.Offline)
}

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
