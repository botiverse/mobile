package build.raft.app

import build.raft.app.components.SlockAnchoredMetaChip
import build.raft.app.components.SlockAttachmentPreviewCard
import build.raft.app.components.SlockAttachmentPreviewCardWidth
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalSearchField
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockRaftUiControlledMenu
import build.raft.app.components.SlockRaftUiMenuItem
import build.raft.app.components.SharedHomeSearchInput
import build.raft.app.components.SlockToolbarChip
import build.raft.app.base.Utils
import build.raft.app.base.putIfAbsent
import build.raft.app.base.bridgeModule
import build.raft.app.data.PlatformFile
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AuthLossSignal
import build.raft.app.data.ForwardMessageExecuteRequest
import build.raft.app.data.ForwardMessageSendMode
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.ForwardMessageTargetRequiredAction
import build.raft.app.data.NetworkSlockTasksApi
import build.raft.app.data.NetworkSlockThreadApi
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SlockEventBus
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
import build.raft.app.data.realtime.RealtimeRoutePolicy
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedMembersIntent
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.data.SharedMembersMemberSuggestion
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.SharedMembersStore
import build.raft.app.data.SharedHomeShellDestinationSearch
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
import build.raft.app.data.ThreadRowAffordancePreference
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.RealtimeActiveThreadSink
import build.raft.app.data.SharedMessageTranslationStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.PendingMentionAction
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.TaskFilter
import build.raft.app.data.TasksUiState
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isAuthSessionErrorCode
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.authLossTokenFingerprint
import build.raft.app.data.isSupportedCommitAction
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ConversationKind
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
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
import build.raft.app.markdown.threadMarkdownTagActivationKey
import build.raft.app.markdown.threadMarkdownTagDisplayLabel
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.MessageNew
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import com.tencent.kuikly.compose.foundation.layout.wrapContentHeight
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
import com.tencent.kuikly.compose.ui.draw.drawBehind
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.ImageBitmap
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
private const val ThreadLifecycleAction = "appLifecycle"
private const val ThreadLifecycleResume = "resume"
private const val ThreadLifecycleShow = "show"
private const val ThreadMessagesMaxBeyondBoundsItemCount = 4
private const val ThreadPerformanceSlowThresholdMs = 4L
private const val ThreadFocusedMessageHighlightDurationMs = 2_000L
private const val ThreadFocusedMessageHighlightFadeMs = 180
private const val ThreadFocusedMessageCenterAttempts = 6
private const val ThreadMentionSuggestionPrewarmDelayMs = 100L
private const val ThreadComposerMentionActionFadeDelayMs = 450L
private const val ThreadComposerMentionActionRemoveDelayMs = 750L
private const val ThreadMarkdownAttachmentPreviewMaxChars = 80_000
private const val ThreadSendSilentRetryDelayMs = 900L
private const val ThreadLocalizedFeedbackPlaceholder = "__RAFT_VALUE__"
internal const val ThreadInitialMarkdownPreparseMessageCount = 80
private const val ThreadMarkdownPreparseDebugEntryLimit = 80
private const val ThreadAttachmentUploadConcurrencyLimit = 12

internal enum class ThreadPickedAttachmentComposerAction {
    None,
    UploadToComposer
}

@Suppress("UNUSED_PARAMETER")
internal fun resolveThreadPickedAttachmentComposerAction(
    action: String,
    fileCount: Int,
    imageAnnotationEnabled: Boolean
): ThreadPickedAttachmentComposerAction {
    return if (fileCount <= 0) {
        ThreadPickedAttachmentComposerAction.None
    } else {
        ThreadPickedAttachmentComposerAction.UploadToComposer
    }
}
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

internal enum class ThreadTransientBackTarget(val label: String) {
    ShareSelection("thread_share_selection"),
    InlineSearch("thread_inline_search")
}

internal fun threadTransientBackTarget(
    activeModal: String?,
    shareSelectionActive: Boolean,
    inlineSearchVisible: Boolean
): ThreadTransientBackTarget? =
    when {
        activeModal != null -> null
        shareSelectionActive -> ThreadTransientBackTarget.ShareSelection
        inlineSearchVisible -> ThreadTransientBackTarget.InlineSearch
        else -> null
    }

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
                originPreview.isNullOrBlank() &&
                !hasProgressiveThreadLoadingShell()
        )

private fun KuiklyThreadScreenPayload.hasProgressiveThreadLoadingShell(): Boolean {
    if (status != "loading") return false
    val routeIdentity = resolveThreadRouteIdentity()
    val timelineKey = routeIdentity.timelineKey?.takeIf { it.isNotBlank() }
    if (timelineKey != null) return true
    val replyParentId = parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        parentChannelId?.isNotBlank() == true &&
        replyParentId != null
}

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

private fun newThreadSendRandomId(): String =
    "mobile-${Random.nextLong().toULong().toString(36)}-${Random.nextLong().toULong().toString(36)}"

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
private val ThreadComposerKeyboardVisibleGap = 8.dp
private val ThreadHeaderActionSize = 30.dp
private val ThreadHeaderActionShadowOffset = 2.dp
private val ThreadHeaderActionGroupSpacing = 4.dp
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

    // task #379: one-shot readiness signal for the visual-testing capture harness.
    // The reaction sprite atlas decodes asynchronously; in the headless capture
    // environment the snapshot can be taken before it finishes, so every reaction
    // renders as a raw-emoji fallback. Emitting this line once (when the hoisted
    // atlas ImageBitmap first becomes non-null) gives the capture side a logcat
    // token to wait on before snapshotting reaction-bearing cases.
    fun logAtlasReadyOnce(width: Int, height: Int) {
        if (loggedKeys.add("thread_reaction_atlas_ready")) {
            Utils.logToNative("thread_reaction_atlas_ready imageWidth=$width imageHeight=$height")
        }
    }
}

internal fun threadComposerBottomPadding(
    keyboardInsetDp: Float,
    bottomSafeArea: Dp,
    keyboardInsetExcludesBottomSafeArea: Boolean = false
): Dp {
    val keyboardInset = keyboardInsetDp.coerceAtLeast(0f).dp
    val keyboardGap = if (keyboardInsetDp > 0f) ThreadComposerKeyboardVisibleGap else 0.dp
    val safeAreaInset =
        if (keyboardInsetDp > 0f && !keyboardInsetExcludesBottomSafeArea) 0.dp else bottomSafeArea
    return ThreadComposerBottomBasePadding + ThreadComposerBottomSafeAreaLift + keyboardGap + keyboardInset + safeAreaInset
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
    val focusedMessageRequestNonce: Int = 0,
    val originStatus: String = "none",
    val originErrorCode: String? = null,
    val tasks: List<KuiklyThreadTaskItem> = emptyList(),
    val knownMarkdownChannelNames: List<String> = emptyList(),
    val knownMarkdownMentionNames: List<String> = emptyList(),
    val knownMarkdownTaskNumbers: List<Int> = emptyList(),
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
    val showInlineSaveAction: Boolean = false,
    val showReactionActions: Boolean = true,
    val showTaskOpenActions: Boolean = true,
    val showTaskStatusActions: Boolean = true,
    val joinLabel: String = "Join channel",
    val dmPeerMemberId: String? = null,
    val dmPeerName: String? = null,
    val dmPeerAvatarUrl: String? = null,
    val dmPeerAvatarLabel: String? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    val activityMuteSupported: Boolean = false
)

internal data class ThreadActivityMuteUiState(
    val channelId: String,
    val muted: Boolean,
    val supported: Boolean,
    val muteFromSeq: Long?
)

internal fun KuiklyThreadScreenPayload.threadActivityMuteState(
    projection: SharedConversationDirectoryProjection?
): ThreadActivityMuteUiState? {
    // Route payload self-identity is authoritative (§5): projection enriches mute data,
    // but must not rewrite what surface this page is.
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) return null
    val supported = projection?.activityMuteSupported ?: activityMuteSupported
    if (!supported) return null
    val resolvedJoined = projection?.joined ?: joined
    if (surfaceKind != KuiklyThreadSurfaceKind.DirectMessage && !resolvedJoined) return null
    val channelId =
        projection?.canonicalChannelId?.takeIf { it.isNotBlank() }
            ?: streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return null
    return ThreadActivityMuteUiState(
        channelId = channelId,
        muted = projection?.activityMuted ?: activityMuted,
        supported = supported,
        muteFromSeq = projection?.muteFromSeq ?: muteFromSeq
    )
}

internal fun KuiklyThreadScreenPayload.joinChannelTargetId(): ChannelId? =
    (parentChannelId?.takeIf { it.isNotBlank() }
        ?: sourceThreadId?.takeIf { it.isNotBlank() }
        ?: streamChannelId?.takeIf { it.isNotBlank() })
        ?.let(::ChannelId)

internal fun KuiklyThreadScreenPayload.threadForwardSourceChannelId(): ChannelId? =
    (sourceThreadId?.takeIf { it.isNotBlank() }
        ?: streamChannelId?.takeIf { it.isNotBlank() }
        ?: parentChannelId?.takeIf { it.isNotBlank() })
        ?.let(::ChannelId)

internal fun KuiklyThreadScreenPayload.activeRealtimeRoomIds(): List<String> =
    listOfNotNull(
        parentChannelId,
        sourceThreadId,
        streamChannelId
    )
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

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
    // #417 contract parse (accepts "direct"); no private DM vocab table (t432 PR-A).
    parseConversationKindOrNull(this) == ConversationKind.DirectMessage

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

internal fun KuiklyThreadScreenPayload.threadChannelSearchChannelId(): String? =
    sourceThreadId
        ?.trim()
        ?.takeIf { it.isNotBlank() && surfaceKind == KuiklyThreadSurfaceKind.Channel }

internal enum class ThreadHeaderSearchAction {
    ChannelSearch,
    InlineThreadSearch
}

internal fun KuiklyThreadScreenPayload.threadHeaderSearchAction(): ThreadHeaderSearchAction? =
    when {
        threadChannelSearchChannelId() != null -> ThreadHeaderSearchAction.ChannelSearch
        surfaceKind == KuiklyThreadSurfaceKind.ReplyThread -> ThreadHeaderSearchAction.InlineThreadSearch
        else -> null
    }

internal fun KuiklyThreadScreenPayload.toThreadChannelSearchPageData(): JSONObject? {
    val channelId = threadChannelSearchChannelId() ?: return null
    return JSONObject().apply {
        put("pageName", HOME_SEARCH_PAGE)
        put("destination", SharedHomeShellDestinationSearch)
        put(HOME_SEARCH_SELECTED_CHANNEL_ID, channelId)
        put(HOME_SEARCH_FOCUS_INPUT, true)
        put("localOffsetMinutes", localOffsetMinutes)
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeFormat)
    }
}

private fun Pager.openThreadChannelSearch(payload: KuiklyThreadScreenPayload): Boolean {
    val pageData = payload.toThreadChannelSearchPageData() ?: return false
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(HOME_SEARCH_PAGE, pageData)
    return true
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
    // Sender subtitle shown after the name, mirroring React's senderSubtitle
    // (agent/member description; humans fall back to role description).
    val authorDescription: String? = null,
    val authorKind: String = "",
    val isOwn: Boolean = false,
    val avatarLabel: String = "",
    val avatarWireValue: String? = null,
    val senderMembershipStatus: String? = null,
    val authorDeletedAt: String? = null,
    val authorAgentStatus: String? = null,
    val timestampLabel: String = "",
    val seq: Long? = null,
    val messageType: String = "Chat",
    val systemEventType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val body: String = "",
    val randomId: String? = null,
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
    val forwardBundle: ForwardMessageBundle? = null,
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
    val memberScopedMentions: Boolean = false,
    val randomId: String? = null
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
    session: StoredAuthSession?,
    timestampLabel: String = "Now"
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
        timestampLabel = timestampLabel,
        randomId = context.randomId?.takeIf { it.isNotBlank() },
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
    val context: KuiklyThreadPendingSendContext,
    val timestampLabel: String
)

private fun buildThreadPendingSendTimestampLabel(
    bridge: build.raft.app.base.BridgeModule,
    fallbackNowEpochMillis: Long?
): String {
    val bridgeEpochMillis = runCatching { bridge.currentTimeStamp() }.getOrDefault(0L)
    val epochMillis =
        bridgeEpochMillis.takeIf { it > 0L }
            ?: fallbackNowEpochMillis?.takeIf { it > 0L }
            ?: return "Now"
    return slockEpochMillisUtcIso(epochMillis)
}

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

internal data class ThreadAttachmentCommentsDialogState(
    val attachment: KuiklyThreadAttachmentItem,
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
    if (knownMarkdownMentionNames.isNotEmpty()) {
        buildSet {
            knownMarkdownMentionNames.forEach { name -> addKnownThreadMentionValue(name) }
        }
    } else {
        buildSet {
            members.forEach { member -> addKnownThreadMentionValues(member) }
            memberCandidates.forEach { member -> addKnownThreadMentionValues(member) }
            originMessage?.let { message -> addKnownThreadMentionValues(message) }
            messages.forEach { message -> addKnownThreadMentionValues(message) }
        }
    }

private fun KuiklyThreadScreenPayload.toKnownMarkdownTaskNumberSetOrNull(): Set<Int>? =
    knownMarkdownTaskNumbers.takeIf { it.isNotEmpty() }
        ?.filterTo(mutableSetOf()) { it > 0 }

private fun threadMarkdownTagContextSignature(
    highlightedMentions: Set<String>,
    knownChannelNames: Set<String>,
    knownMentionNames: Set<String>,
    knownTaskNumbers: Set<Int>?
): String =
    "highlighted=${highlightedMentions.size}:${highlightedMentions.hashCode().toString(16)} " +
        "channels=${knownChannelNames.size}:${knownChannelNames.hashCode().toString(16)} " +
        "mentions=${knownMentionNames.size}:${knownMentionNames.hashCode().toString(16)} " +
        "tasks=${knownTaskNumbers?.size ?: -1}:${knownTaskNumbers?.hashCode()?.toString(16) ?: "all"}"

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
    knownMentionNames: Set<String>,
    knownTaskNumbers: Set<Int>? = null
): Boolean =
    when (kind) {
        ThreadMarkdownTagKind.Task ->
            knownTaskNumbers?.contains(label.removePrefix("#").toIntOrNull()) ?: true
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

internal fun KuiklyThreadScreenPayload.supportsChannelTasks(): Boolean =
    composer.showAsTaskToggle || showTaskOpenActions || showTaskStatusActions

internal sealed interface MarkdownTaskNumberOpenResult {
    data class Found(val task: TaskSummary, val payload: KuiklyThreadScreenPayload) : MarkdownTaskNumberOpenResult
    data class Ambiguous(val taskNumber: Int, val candidates: List<TaskSummary>) : MarkdownTaskNumberOpenResult
    data object NotFound : MarkdownTaskNumberOpenResult
    data object AuthRequired : MarkdownTaskNumberOpenResult
    data object Failed : MarkdownTaskNumberOpenResult
}

internal suspend fun openMarkdownTaskNumberPayload(
    serverId: ServerId,
    taskNumber: Int,
    currentPayload: KuiklyThreadScreenPayload,
    currentTaskChannelId: ChannelId?,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?
): MarkdownTaskNumberOpenResult {
    currentTaskChannelId?.let { channelId ->
        when (
            val channelResult =
                tasksDataSource.loadChannelTaskByNumber(
                    channelId = channelId,
                    taskNumber = taskNumber,
                    priority = DataRequestPriority.Foreground
                )
        ) {
            is DataResult.Content -> {
                val task = channelResult.data.task
                val payload =
                    currentPayload.markdownTaskNumberLaunchPayload(task)
                        ?: return MarkdownTaskNumberOpenResult.Failed
                tasksDataStore?.upsertTask(serverId.value, task)
                return MarkdownTaskNumberOpenResult.Found(task, payload)
            }
            is DataResult.NoPermission -> return MarkdownTaskNumberOpenResult.AuthRequired
            is DataResult.Empty,
            is DataResult.Error -> Unit
        }
    }
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
    val target = task.toTaskThreadLaunchTarget() ?: return null
    val launchPayload =
        buildStandaloneConversationThreadLaunchPayload(
            conversation = target.conversation,
            timeContext =
                SlockTimeContext(
                    localOffsetMinutes = localOffsetMinutes,
                    todayEpochDay = todayEpochDay,
                    nowEpochMillis = nowEpochMillis,
                    timeFormat = timeFormat
                ),
            anchorMessageId = target.focusedMessageId,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages
        )
    return launchPayload.toInitialPayloadOrNull(launchPayload.instanceId)
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
    shouldStartThreadTasksLoad(payload, taskChannelId) &&
        startedThreadId != taskChannelId?.value

internal fun shouldStartThreadTasksLoad(
    payload: KuiklyThreadScreenPayload,
    taskChannelId: ChannelId?
): Boolean =
    taskChannelId != null &&
        payload.status == "content" &&
        payload.supportsChannelTasks() &&
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

internal fun KuiklyThreadScreenPayload.replyParentChannelIdForNestedOpen(): String? =
    if (isReplyThreadWithExplicitParentPair()) {
        parentChannelId.nonBlank()
    } else {
        sourceThreadId.nonBlank()
            ?: streamChannelId.nonBlank()
            ?: parentChannelId.nonBlank()
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

@Composable
private fun KuiklyThreadTab.toSlockTabSpec(): SlockTabSpec<KuiklyThreadTab> =
    SlockTabSpec(
        key = this,
        label =
            when (this) {
                KuiklyThreadTab.Chat -> stringResource(MR.strings.thread_tab_chat)
                KuiklyThreadTab.Tasks -> stringResource(MR.strings.thread_tab_tasks)
                KuiklyThreadTab.Files -> stringResource(MR.strings.thread_tab_files)
            },
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
    val storeScope = remember(params.toString(), pager.pagerId) {
        params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val threadPageCoroutineScope = rememberCoroutineScope()
    var authLossReportInProgress by remember { mutableStateOf(false) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
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
    val forwardTargetRepository =
        remember(authStore, networkClient, channelRepository) {
            RealForwardMessageTargetRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                channelRepository = channelRepository,
                warningLogger = { message -> bridge.log("thread_forward_target_repository $message") }
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
            memberRepository,
            serverMembersStore,
            channelRepository,
            sharedStores.threadMessageStore,
            createAgentCoordinator
        ) {
            SharedThreadDataSource(
                networkApi = NetworkSlockThreadApi(networkClient),
                sessionStore = authStore,
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore,
                conversationRepository =
                    RealConversationRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        warningLogger = { message -> bridge.log("thread_conversation_repository $message") }
                    ),
                channelRepository = channelRepository,
                messageStore = sharedStores.threadMessageStore,
                payloadStore = sharedStores.threadPayloadStore,
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
    val unableToOpenThreadLabel = stringResource(MR.strings.thread_error_open_thread)
    val unableToOpenProfileLabel = stringResource(MR.strings.thread_error_open_profile)
    fun openStandaloneThreadPayload(nextPayload: KuiklyThreadScreenPayload) {
        val launchPayload = nextPayload.toWireThreadLaunchPayloadOrNull()
        if (launchPayload == null) {
            bridge.toast(unableToOpenThreadLabel)
            bridge.logWarn("thread_open_skip reason=missing_route_identity instanceId=${nextPayload.instanceId}")
            return
        }
        val pageData =
            launchPayload.toThreadPageData()
                .put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
                .also { data -> storeScope.initialServerId?.let { data.put(SLOCK_SHELL_SERVER_ID, it) } }
        val openPage = {
            routerModule.openPage(THREAD_PAGE, pageData)
        }
        if (nextPayload.sourceThreadId?.isNotBlank() == true &&
            (nextPayload.originMessage != null || nextPayload.messages.isNotEmpty())
        ) {
            dataSource.cachePayload(nextPayload)
            openPage()
            return
        }
        routerModule.openPage(THREAD_PAGE, pageData)
    }
    SharedThreadRouteContent(
        initialPayload = payload,
        bridge = bridge,
        dataSource = dataSource,
        forwardTargetRepository = forwardTargetRepository,
        threadSendScope = sharedStores.threadSendScope,
        authStore = authStore,
        tasksDataSource = tasksDataSource,
        tasksDataStore = sharedStores.tasksDataStore,
        computersStore = computersStore,
        membersStore = membersStore,
        serverMembersStore = serverMembersStore,
        conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
        storeScope = storeScope,
        composerDraftPersistSlice = sharedStores.composerDraftPersistSlice,
        eventBus = sharedStores.eventBus,
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
            if (!authLossReportInProgress) {
                authLossReportInProgress = true
                threadPageCoroutineScope.launch {
                    try {
                        val session =
                            runCatchingPreservingCancellation { authStore.readSession() }
                                .getOrNull()
                        val pendingOpenRequest =
                            payload
                                .toWireThreadLaunchPayloadOrNull()
                                ?.toAuthPendingOpenRequest(session?.currentServerId.orEmpty())
                        sharedStores.authLossFunnel
                            .report(
                                AuthLossSignal(
                                    source = "thread:${payload.instanceId}",
                                    tokenFingerprint = session?.accessToken.authLossTokenFingerprint(),
                                    pendingOpenRequest = pendingOpenRequest
                                )
                            ).await()
                    } finally {
                        authLossReportInProgress = false
                    }
                }
            } else {
                bridge.logWarn(
                    "thread_auth_loss_report_skip reason=in_progress code=$code instanceId=${payload.instanceId}"
                )
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
                bridge.toast(unableToOpenProfileLabel)
                bridge.logWarn(
                    "thread_member_profile_skip reason=missing_member memberId=$memberId " +
                    "instanceId=${currentPayload.instanceId}"
                )
            } else {
                authorProfile
                    ?.toThreadCandidateAgentDetailSnapshot()
                    ?.let { candidate ->
                        val agentDetailStore = SlockSharedStoreRegistry.get(bridge).agentDetailStore
                        agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(candidate.agentId))
                        agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetail(candidate.agentId, candidate))
                    }
                routerModule.openPage(MEMBER_DETAIL_PAGE, pageData)
            }
        }
    )
}

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
private fun ThreadMessageVisualScreenContent() {
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
    val knownMarkdownTaskNumbers =
        remember(payload.knownMarkdownTaskNumbers) {
            payload.toKnownMarkdownTaskNumberSetOrNull()
        }
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean =
        remember(knownMarkdownChannelNames, knownMarkdownMentionNames, knownMarkdownTaskNumbers) {
            { tag -> tag.isKnownThreadMarkdownTag(knownMarkdownChannelNames, knownMarkdownMentionNames, knownMarkdownTaskNumbers) }
        }
    val markdownTagContextSignature =
        remember(highlightedMentions, knownMarkdownChannelNames, knownMarkdownMentionNames, knownMarkdownTaskNumbers) {
            threadMarkdownTagContextSignature(
                highlightedMentions = highlightedMentions,
                knownChannelNames = knownMarkdownChannelNames,
                knownMentionNames = knownMarkdownMentionNames,
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
        Column(modifier = Modifier.width(390.dp).wrapContentHeight(align = Alignment.Top, unbounded = true).background(SlockColor.SurfaceWhite).padding(SlockSpacing.Md)) {
            messages.forEach { selectedMessage ->
                ThreadMessageCard(
                    message = selectedMessage,
                    replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(selectedMessage),
                    highlightedMentions = highlightedMentions,
                    shareSelectionActive = true,
                    selectedForShare = true,
                    onToggleShareSelection = {},
                    alignOwnMessageRight = payload.alignOwnMessagesRight && selectedMessage.isOwn,
                    showReplyAction = payload.showReplyActions,
                    taskOpenEnabled = payload.showTaskOpenActions,
                    taskStatusActionsEnabled = payload.showTaskStatusActions,
                    showReactionAddAction = payload.showReactionActions,
                    timestampLabel = payload.formatTimestampLabel(selectedMessage.timestampLabel),
                    onShowActions = {},
                    onOpenMember = {},
                    onOpenThread = {},
                    onOpenTask = {},
                    onOpenTaskStatusMenu = {},
                    isMarkdownTagValid = isMarkdownTagValid,
                    markdownTagContextSignature = markdownTagContextSignature,
                    onToggleReaction = {},
                    onOpenAttachment = {},
                    onOpenMarkdownTag = {},
                    onOpenMarkdownUrl = {},
                    outerPadding = 0.dp
                )
            }
            Spacer(Modifier.height(SlockSpacing.Sm))
            ThreadShareSelectionToolbar(
                selectedCount = messages.size,
                selectableCount = messages.size,
                onCancel = {},
                onSelectAll = {},
                onForward = {},
                onExportImage = {},
                onCopyLinks = {}
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
                    replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(visualMessage),
                    highlightedMentions = highlightedMentions,
                    alignOwnMessageRight = false,
                    showReplyAction = false,
                    taskOpenEnabled = false,
                    taskStatusActionsEnabled = false,
                    showReactionAddAction = false,
                    timestampLabel = payload.formatTimestampLabel(visualMessage.timestampLabel),
                    onShowActions = {},
                    onOpenMember = {},
                    onOpenThread = {},
                    onOpenTask = {},
                    onOpenTaskStatusMenu = {},
                    isMarkdownTagValid = isMarkdownTagValid,
                    markdownTagContextSignature = markdownTagContextSignature,
                    onToggleReaction = {},
                    onOpenAttachment = {},
                    onOpenMarkdownTag = {},
                    onOpenMarkdownUrl = {},
                    outerPadding = 0.dp
                )
            }
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
        if (message != null) {
            ThreadMessageCard(
                message = message,
                replyMetadata = visualReplyMetadataByParentId.threadReplyMetadataFor(message),
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
                showReactionAddAction = payload.showReactionActions,
                isMarkdownTagValid = isMarkdownTagValid,
                markdownTagContextSignature = markdownTagContextSignature,
                timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = {},
                onOpenMarkdownUrl = {},
                outerPadding = 0.dp
            )
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
    // React's thread-header element includes the Chat/Tasks/Files mode-tab strip
    // that the real page renders via SlockTabbedScaffold directly below
    // ThreadPageHeader, so the fixture renders the same SlockTopTabBar row with
    // the thread tab specs (Chat selected). The explicit 120dp canvas matches the
    // react element height (1170x360px @3x = 390x120dp); the header+tabs measure
    // 62+32=94dp, so the white slack at the bottom exposes the real height delta
    // (task #370).
    Column(modifier = Modifier.width(390.dp).height(120.dp).background(SlockColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = { _, _, _ -> },
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {}
        )
        SlockTopTabBar(
            items = KuiklyThreadTab.entries.map { it.toSlockTabSpec().toTopTabBarItem() },
            selectedKey = KuiklyThreadTab.Chat.toString(),
            onSelectedKeyChange = {},
            itemKeyPrefix = "thread-tab"
        )
    }
}

@Composable
internal fun ThreadActivityMuteVisualScreen() {
    val unmutedPayload =
        remember {
            KuiklyThreadScreenPayload(
                instanceId = "visual-thread-activity-mute-unmuted",
                title = "#visual-testing",
                subtitle = "Visual parity and capture automation",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = "channel-visual-testing",
                streamChannelId = "channel-visual-testing",
                selectedTab = "chat",
                showModeTabs = true,
                showSettingsAction = true,
                showMembersAction = true,
                membersCount = 8,
                activityMuted = false,
                activityMuteSupported = true
            )
        }
    val mutedPayload =
        remember {
            unmutedPayload.copy(
                instanceId = "visual-thread-activity-mute-muted",
                title = "#announcements",
                subtitle = "Release notes and high-signal updates",
                sourceThreadId = "channel-announcements",
                streamChannelId = "channel-announcements",
                membersCount = 3,
                activityMuted = true,
                muteFromSeq = 45
            )
        }
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(SlockColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = unmutedPayload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = { _, _, _ -> },
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
            onToggleActivityMute = {}
        )
        ThreadPageHeader(
            payload = mutedPayload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = { _, _, _ -> },
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
            onToggleActivityMute = {}
        )
        SlockStructuralHomeShell(
            modifier = Modifier.height(700.dp),
            currentServerName = "Raft Mobile",
            currentServerId = "server-visual",
            servers =
                listOf(
                    SlockServerSwitcherItem(
                        id = "server-visual",
                        name = "Raft Mobile",
                        slug = "mobile"
                    )
                ),
            channels =
                listOf(
                    SlockHomeConversationItem(
                        id = "channel-loud",
                        name = "critical",
                        kind = "channel",
                        unreadCount = 7,
                        joined = true,
                        activityMuted = false
                    ),
                    SlockHomeConversationItem(
                        id = "channel-muted",
                        name = "announcements",
                        kind = "channel",
                        unreadCount = 7,
                        joined = true,
                        activityMuted = true
                    )
                ),
            directMessages = emptyList(),
            showBottomBar = false,
            bottomContentPadding = 0.dp,
            outerPadding = 0.dp
        )
    }
}

@Composable
internal fun ThreadReactionAtlasVisualScreen() {
    // task #376 (Quiver 389d855b): this standalone visual screen renders reaction
    // glyphs outside ThreadMessagesList, so it provides its own hoisted atlas
    // image for LocalThreadReactionAtlasImage (glyphs no longer self-load).
    ThreadReactionAtlasProvider {
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
                    ThreadMessageReactionRow(
                        reactionAtlasImage = LocalThreadReactionAtlasImage.current,
                        onReact = {}
                    )
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
internal fun ChannelMembersPanelJointChannelVisualScreen() {
    val members = remember { channelPanelJointChannelVisualMembers() }
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
            subtitle = stringResource(MR.strings.channel_description_design),
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

// Canonical #design roster (task #370): mirrors the react channel-members
// fixture (packages/web/visual-testing/VisualTestingCases.tsx, channel-members
// kind) — agent Cindy with her activity detail, human artin as Owner, both
// removable, no remote-server badge. Identity/avatar/copy values come from
// fixtureData.json via VisualFixtureData.
private fun channelPanelVisualMembers(): List<KuiklyThreadMemberItem> =
    listOf(
        KuiklyThreadMemberItem(
            id = "agent-cindy",
            displayName = "Cindy",
            handle = "Cindy",
            roleLabel = "Capturing visual testing baselines",
            avatarUrl = "pixel:robot",
            avatarLabel = "CI",
            kind = "agent",
            agentStatus = AgentStatus.Working,
            canRemove = true
        ),
        KuiklyThreadMemberItem(
            id = "visual-user",
            displayName = "artin",
            handle = "artin",
            roleLabel = "Owner",
            avatarLabel = "AR",
            kind = "human",
            canRemove = true
        )
    )

// Joint-channel example split out of components.channel.members.panel into its
// own case components.channel.members.panel.joint-channel (task #370 scope
// change): remote-server members carry the Botiverse source badge and cannot
// be removed. The react counterpart + manifest entry land separately, so this
// case is react-missing until then.
private fun channelPanelJointChannelVisualMembers(): List<KuiklyThreadMemberItem> =
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

// Canonical add-member candidates (task #370): react shows agent Product UX
// Designer plus humans artin and Designer (Designer preselected). The artin
// candidate row intentionally uses a local id that differs from the member
// roster's "visual-user" so the android row renders plain like react's
// (a matching memberKey would add the JOINED marker, which react does not
// show for this fixture).
private fun channelPanelVisualCandidates(): List<KuiklyThreadMemberItem> =
    listOf(
        KuiklyThreadMemberItem(
            id = "agent-product-ux",
            displayName = "Product UX Designer",
            handle = "Product-UX-Designer",
            roleLabel = "",
            avatarUrl = "pixel:paint",
            avatarLabel = "PU",
            kind = "agent",
            agentStatus = AgentStatus.Online
        ),
        KuiklyThreadMemberItem(
            id = "user_artin",
            displayName = "artin",
            handle = "artin",
            roleLabel = "",
            avatarLabel = "AR",
            kind = "human"
        ),
        KuiklyThreadMemberItem(
            id = "user_designer",
            displayName = "Designer",
            handle = "designer",
            roleLabel = "",
            avatarLabel = "DE",
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
    activeMarkdownTagKey: String? = null,
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
                            style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack, lineHeight = 20.sp),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp)
                        )
                        message.threadAuthorBadgeLabel()?.let { label ->
                            ThreadMessageAuthorBadge(label)
                        }
                        message.authorDescription?.let { description ->
                            ThreadMessageAuthorSubtitle(
                                description,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Text(
                            text = timestampLabel,
                            style =
                                SlockType.Caption.copy(
                                    fontFamily = SlockMonoFamily,
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
                                activeTagKey = activeMarkdownTagKey,
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
    forwardTargetRepository: ForwardMessageTargetRepository? = null,
    threadSendScope: CoroutineScope,
    authStore: SlockAuthSessionStore,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore? = null,
    computersStore: SharedComputersStore? = null,
    membersStore: SharedMembersStore? = null,
    serverMembersStore: SharedServerMembersStore? = null,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    storeScope: SlockShellStoreScope = SlockShellStoreScope.Legacy,
    composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    eventBus: SlockEventBus,
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
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val translationStore = remember(sharedStores) { sharedStores.messageTranslationStore }
    val realtimeDomainEventOwner =
        remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    val foregroundReconciler = remember(sharedStores) { sharedStores.foregroundReconciler }
    val foregroundBannerCurrentServerStore = remember(sharedStores) { sharedStores.localDataStore }
    val sharedHomeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val realtimeEvents = remember(eventBus) { KuiklyRealtimeEvents(eventBus) }
    val realtimeEventMapper = remember { RealtimeEventMapper() }
    PerformanceSceneEffect(bridge, "kuikly_thread", active = active)
    val incomingRouteIdentity = remember(initialPayload) {
        initialPayload.resolveThreadRouteIdentity()
    }
    var retainedRouteIdentity by remember {
        mutableStateOf(incomingRouteIdentity)
    }
    LaunchedEffect(incomingRouteIdentity) {
        if (!retainedRouteIdentity.canRetainThreadPageFor(incomingRouteIdentity)) {
            retainedRouteIdentity = incomingRouteIdentity
        }
    }
    val initialRouteIdentity = retainedRouteIdentity
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
    val headerProjectionRoutePayload =
        remember(
            payload.surfaceKind,
            payload.channelKind,
            payload.sourceThreadId,
            payload.streamChannelId,
            payload.parentChannelId,
            payload.parentMessageId,
            payload.parentSurfaceKind,
            payload.parentChannelKind,
            payload.dmPeerMemberId
        ) {
            payload.threadHeaderProjectionRoutePayload()
        }
    val headerProjectionInput =
        remember(headerProjectionRoutePayload) {
            headerProjectionRoutePayload.threadHeaderProjectionInput()
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
    val activityMuteState =
        remember(payload, stableHeaderProjection, sharedHomeStore) {
            payload.threadActivityMuteState(stableHeaderProjection)
                ?.takeIf { sharedHomeStore != null }
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
    val foregroundConversationDirectory =
        remember(directoryConversationProjections) {
            slockConversationDirectoryStateFromProjections(directoryConversationProjections.values)
        }
    var foregroundMessageBanner by remember(initialThreadInstanceKey) {
        mutableStateOf<SlockForegroundMessageBanner?>(null)
    }
    val latestCurrentSession by rememberUpdatedState(currentSession)
    val latestPayloadRouteIdentity by rememberUpdatedState(payloadRouteIdentity)
    val latestForegroundConversationDirectory by rememberUpdatedState(foregroundConversationDirectory)
    val threadInstanceKey = initialThreadInstanceKey
    val threadTimelineKey =
        payloadRouteIdentity.timelineChannelId
            ?: initialRouteIdentity.timelineChannelId
    val resolvedAuxiliaryThreadId = payload.resolvedAuxiliaryThreadId()
    var threadLifecycleSeen by remember(threadInstanceKey) { mutableStateOf(false) }
    DisposableEffect(
        realtimeEvents,
        currentSession?.currentServerId,
        payload.parentChannelId,
        payload.sourceThreadId,
        payload.streamChannelId
    ) {
        val activeRoomIds = payload.activeRealtimeRoomIds()
        val roomServerId = currentSession?.currentServerId
        realtimeEvents.joinChannels(activeRoomIds, roomServerId)
        onDispose {
            realtimeEvents.leaveChannels(activeRoomIds, roomServerId)
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
    var inlineVideoPreviewStates by remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadInlineVideoPreviewUiState>>(emptyMap())
    }
    var attachmentUploadJobs by remember(threadInstanceKey) {
        mutableStateOf<Map<String, Job>>(emptyMap())
    }
    var attachmentUploadFilesByLocalId by remember(threadInstanceKey) {
        mutableStateOf<Map<String, PlatformFile>>(emptyMap())
    }
    val attachmentUploadSemaphore = remember(threadInstanceKey) {
        Semaphore(ThreadAttachmentUploadConcurrencyLimit)
    }
    var composerImageActionCounter by remember(threadInstanceKey) { mutableStateOf(0) }
    val chatLayoutPreferenceStore =
        remember(pager) {
            KeyValueChatLayoutPreferenceStore(slockSharedPreferenceStore())
        }
    fun imageAnnotationEnabled(): Boolean =
        chatLayoutPreferenceStore.readImageAnnotationEnabledSync()
    fun foregroundMessageBannersEnabled(): Boolean =
        chatLayoutPreferenceStore.readForegroundMessageBannersEnabledSync()
    val actionCardSuccessLabels =
        mapOf(
            "channel:create" to stringResource(MR.strings.thread_action_card_success_channel),
            "agent:create" to stringResource(MR.strings.thread_action_card_success_agent),
            "channel:add_member" to stringResource(MR.strings.thread_action_card_success_members),
            "integration:approve_agent_login" to stringResource(MR.strings.thread_action_card_login_approved)
        )
    val actionCardFailureLabels =
        mapOf(
            "channel:create" to stringResource(MR.strings.thread_action_card_failure_channel),
            "agent:create" to stringResource(MR.strings.thread_action_card_failure_agent),
            "channel:add_member" to stringResource(MR.strings.thread_action_card_failure_members),
            "integration:approve_agent_login" to stringResource(MR.strings.thread_action_card_failure_login)
        )
    val genericActionCardSuccessLabel = stringResource(MR.strings.thread_action_card_success_action)
    val genericActionCardFailureLabel = stringResource(MR.strings.thread_action_card_failure_action)
    val composerSendKeyEnabled = chatLayoutPreferenceStore.readComposerSendKeyEnabledSync()
    val commentsUnavailableLabel = stringResource(MR.strings.thread_error_comments_unavailable)
    val unableToSendCommentLabel = stringResource(MR.strings.thread_error_send_comment)
    val unableToResolveCommentLabel = stringResource(MR.strings.thread_error_resolve_comment)
    val unableToLoadImageLabel = stringResource(MR.strings.thread_error_load_image)
    val unableToOpenAttachmentLabel = stringResource(MR.strings.thread_error_open_attachment)
    val attachmentUploadFailedLabel = stringResource(MR.strings.thread_error_attachment_upload)
    val preparingAttachmentPreviewLabel = stringResource(MR.strings.thread_state_preparing_attachment)
    val attachmentUploadFailedDetailTemplate =
        stringResource(MR.strings.thread_error_attachment_upload_detail, ThreadLocalizedFeedbackPlaceholder)
    val unableToOpenRepliesLabel = stringResource(MR.strings.thread_error_open_replies)
    val unableToOpenParentMessageLabel = stringResource(MR.strings.thread_error_open_parent_message)
    val unableToOpenTaskLabel = stringResource(MR.strings.thread_error_open_task)
    val unableToOpenTargetTemplate =
        stringResource(MR.strings.thread_error_open_target, ThreadLocalizedFeedbackPlaceholder)
    val unableToOpenTaskNumberTemplate =
        stringResource(MR.strings.thread_error_open_task_number, ThreadLocalizedFeedbackPlaceholder)
    val genericThreadErrorLabel = stringResource(MR.strings.common_error)
    val attachmentPickerUnavailableLabel = stringResource(MR.strings.thread_error_attachment_picker)
    val fileSourceUnavailableLabel = stringResource(MR.strings.thread_error_file_source)
    val copiedLabel = stringResource(MR.strings.thread_feedback_copied)
    val linksCopiedLabel = stringResource(MR.strings.thread_feedback_links_copied)
    val forwardNeedsApiTemplate =
        stringResource(
            MR.strings.thread_forward_needs_api,
            ThreadLocalizedFeedbackPlaceholder,
            "__RAFT_TARGET__"
        )
    val forwardCompletedTemplate =
        stringResource(
            MR.strings.thread_forward_completed,
            ThreadLocalizedFeedbackPlaceholder,
            "__RAFT_TARGET__"
        )
    val forwardJoinFirstTemplate =
        stringResource(MR.strings.thread_forward_join_first, ThreadLocalizedFeedbackPlaceholder)
    val forwardGroupUnavailableLabel = stringResource(MR.strings.thread_forward_group_unavailable)
    val forwardMultiSelectUnavailableLabel = stringResource(MR.strings.thread_forward_multi_select_unavailable)
    val unableToCopyLinkLabel = stringResource(MR.strings.thread_error_copy_link)
    val noTextToSelectLabel = stringResource(MR.strings.thread_error_no_text_select)
    val followedThreadLabel = stringResource(MR.strings.thread_feedback_followed)
    val unableToFollowThreadLabel = stringResource(MR.strings.thread_error_follow)
    val unfollowedThreadLabel = stringResource(MR.strings.thread_feedback_unfollowed)
    val unableToUnfollowThreadLabel = stringResource(MR.strings.thread_error_unfollow)
    val searchUnavailableLabel = stringResource(MR.strings.thread_error_search_unavailable)
    val actionUnavailableLabel = stringResource(MR.strings.thread_error_action_unavailable)
    val actionUnsupportedLabel = stringResource(MR.strings.thread_error_action_unsupported)
    val unableToUpdateReactionLabel = stringResource(MR.strings.thread_error_update_reaction)
    val commentSourceUnavailableLabel = stringResource(MR.strings.thread_error_comment_source)
    val memberNotFoundTemplate =
        stringResource(MR.strings.thread_error_member_not_found, ThreadLocalizedFeedbackPlaceholder)
    val memberAddedLabel = stringResource(MR.strings.thread_feedback_member_added)
    val unableToAddMemberLabel = stringResource(MR.strings.thread_error_add_member)
    val memberRemovedLabel = stringResource(MR.strings.thread_feedback_member_removed)
    val unableToRemoveMemberLabel = stringResource(MR.strings.thread_error_remove_member)
    val visibilityNotWiredLabel = stringResource(MR.strings.thread_error_visibility_not_wired)
    val channelArchivedLabel = stringResource(MR.strings.thread_feedback_channel_archived)
    val unableToArchiveChannelLabel = stringResource(MR.strings.thread_error_archive_channel)
    val channelDeletedLabel = stringResource(MR.strings.thread_feedback_channel_deleted)
    val unableToDeleteChannelLabel = stringResource(MR.strings.thread_error_delete_channel)
    val unableToConvertMessageLabel = stringResource(MR.strings.thread_error_convert_message)
    val taskCreatedLabel = stringResource(MR.strings.thread_feedback_task_created)
    val unsupportedTaskStatusLabel = stringResource(MR.strings.thread_error_task_status_unsupported)
    val taskUpdatedLabel = stringResource(MR.strings.thread_feedback_task_updated)
    val unableToUpdateTaskLabel = stringResource(MR.strings.thread_error_update_task)
    val unableToJoinChannelLabel = stringResource(MR.strings.thread_error_join_channel)
    val joinedChannelLabel = stringResource(MR.strings.thread_feedback_joined_channel)
    val attachmentRetryUnavailableLabel = stringResource(MR.strings.thread_error_attachment_retry)
    val mergedForwardModeLabel = stringResource(MR.strings.thread_forward_mode_merged)
    val individualForwardModeLabel = stringResource(MR.strings.thread_forward_mode_individual)
    val imageSavedLabel = stringResource(MR.strings.thread_feedback_image_saved)
    val unableToExportImageLabel = stringResource(MR.strings.thread_error_export_image)
    val unableToForwardLabel = stringResource(MR.strings.thread_error_forward)
    val unableToSaveMessageLabel = stringResource(MR.strings.thread_error_save_message)
    val unableToRemoveSavedMessageLabel = stringResource(MR.strings.thread_error_remove_saved_message)
    val attachmentSkippedOneLabel = stringResource(MR.strings.thread_attachment_skipped_one)
    val attachmentSkippedManyTemplate =
        stringResource(MR.strings.thread_attachment_skipped_many, ThreadLocalizedFeedbackPlaceholder)
    fun localizedAttachmentUploadFailedDetail(detail: String): String =
        attachmentUploadFailedDetailTemplate.replace(ThreadLocalizedFeedbackPlaceholder, detail)
    fun localizedUnableToOpenTarget(target: String): String =
        unableToOpenTargetTemplate.replace(ThreadLocalizedFeedbackPlaceholder, target)
    fun localizedUnableToOpenTaskNumber(taskNumber: Int): String =
        unableToOpenTaskNumberTemplate.replace(ThreadLocalizedFeedbackPlaceholder, taskNumber.toString())
    fun localizedForwardModeLabel(mode: String?): String =
        if (mode?.trim()?.lowercase() == ThreadForwardSelectionMode.Individual.wireValue) {
            individualForwardModeLabel
        } else {
            mergedForwardModeLabel
        }
    fun localizedForwardNeedsApi(mode: String, target: String): String =
        forwardNeedsApiTemplate
            .replace(ThreadLocalizedFeedbackPlaceholder, mode)
            .replace("__RAFT_TARGET__", target)
    fun localizedForwardCompleted(mode: String, target: String): String =
        forwardCompletedTemplate
            .replace(ThreadLocalizedFeedbackPlaceholder, mode)
            .replace("__RAFT_TARGET__", target)
    fun localizedForwardJoinFirst(target: String): String =
        forwardJoinFirstTemplate.replace(ThreadLocalizedFeedbackPlaceholder, target)
    fun localizedMemberNotFound(target: String): String =
        memberNotFoundTemplate.replace(ThreadLocalizedFeedbackPlaceholder, target)
    fun localizedAttachmentSkippedMany(count: Int): String =
        attachmentSkippedManyTemplate.replace(ThreadLocalizedFeedbackPlaceholder, count.toString())
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

    DisposableEffect(threadInstanceKey) {
        onDispose {
            currentAttachmentUploadJobs.values.forEach { job -> job.cancel() }
        }
    }

    LaunchedEffect(authStore, initialThreadInstanceKey) {
        val session = authStore.readSession()
        currentSession = session
        currentServerSlug = session?.currentServerSlug()
        createAgentComputerServerId = session?.currentServerId?.takeIf { it.isNotBlank() }
        createAgentComputerServerResolved = true
    }
    var startedRouteIdentity by remember(initialThreadInstanceKey) {
        mutableStateOf<ThreadRouteIdentity?>(null)
    }
    LaunchedEffect(initialPayload, initialThreadInstanceKey) {
        if (!initialRouteIdentity.canRetainThreadPageFor(incomingRouteIdentity)) {
            return@LaunchedEffect
        }
        val alreadyStartedRouteIdentity = startedRouteIdentity
        if (alreadyStartedRouteIdentity != null &&
            alreadyStartedRouteIdentity.canRetainThreadPageFor(incomingRouteIdentity)
        ) {
            threadViewModel.dispatch(SharedThreadIntent.ApplyRouteIntent(initialPayload))
            return@LaunchedEffect
        }
        startedRouteIdentity = incomingRouteIdentity
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
        inlineVideoPreviewStates = emptyMap()
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
                    parentChannelId = renderedPayload.parentChannelId,
                    memberSuggestionHeadersVisible = renderedPayload.threadComposerMemberSuggestionHeadersVisible()
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
    // Pager lifecycle notifications are Thread-pager-local and stay on the raw
    // listener; realtime domain events arrive through the shared
    // SharedRealtimeDomainEventOwner sink below so the owner's dedup ledger is
    // the single ingress decision point (#196, replaces per-route raw decode).
    // resolvedAuxiliaryThreadId is provably within activeRealtimeRoomIds()
    // (timelineChannelId = streamChannelId ?: sourceThreadId), so the rejoin
    // set is exactly the mount-time join/leave set — keeping them identical
    // avoids force-joining a room that never gets a paired leave.
    val latestForegroundRejoinRoomIds by rememberUpdatedState(payload.activeRealtimeRoomIds())
    DisposableEffect(realtimeEvents, active) {
        val callbackRef =
            realtimeEvents.addListener { eventData ->
                val eventDataSnapshot = eventData.toString()
                coroutineScope.launch {
                    val lifecycleData = JSONObject(eventDataSnapshot)
                    if (lifecycleData.optString("action") != ThreadLifecycleAction) {
                        return@launch
                    }
                    val event = lifecycleData.optString("event")
                    val pageName = lifecycleData.optString("pageName")
                    if (pageName == THREAD_PAGE && (event == ThreadLifecycleResume || event == ThreadLifecycleShow)) {
                        if (threadLifecycleSeen && active) {
                            threadViewModel.dispatch(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
                            // Foreground reconciliation: local joined-room state may
                            // be stale after background/reconnect — force re-emit
                            // join:channel for the visible thread rooms (server-side
                            // join is idempotent), and schedule the coalesced
                            // per-server member/status refresh.
                            realtimeEvents.rejoinChannels(
                                latestForegroundRejoinRoomIds,
                                latestCurrentSession?.currentServerId
                            )
                            foregroundReconciler.onForeground("thread_$event")
                        } else {
                            threadLifecycleSeen = true
                        }
                    }
                }
            }
        onDispose { realtimeEvents.removeListener(callbackRef) }
    }
    DisposableEffect(realtimeDomainEventOwner, authStore, coroutineScope) {
        val sink =
            RealtimeActiveThreadSink { serverId, event ->
                coroutineScope.launch {
                    val currentSessionServerId =
                        authStore.readSession()
                            ?.currentServerId
                            ?.takeIf { it.isNotBlank() }
                            ?: return@launch
                    if (currentSessionServerId != serverId) return@launch
                    if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@launch
                    latestThreadRealtimeEventHandler(event)
                    if (event is MessageNew) {
                        val session = latestCurrentSession
                        foregroundMessageBanner =
                            if (foregroundMessageBannersEnabled()) {
                                slockForegroundMessageBannerProjection(
                                    serverId = serverId,
                                    currentServerId = session?.currentServerId ?: currentSessionServerId,
                                    message = event.message,
                                    currentMemberId = session?.user?.id,
                                    visibleConversationKey = null,
                                    visibleRouteIdentity = latestPayloadRouteIdentity,
                                    conversationDirectory = latestForegroundConversationDirectory
                                ) ?: foregroundMessageBanner
                            } else {
                                null
                            }
                    }
                }
            }
        realtimeDomainEventOwner.registerActiveThreadSink(sink)
        onDispose { realtimeDomainEventOwner.unregisterActiveThreadSink(sink) }
    }
    LaunchedEffect(foregroundMessageBanner?.id) {
        val bannerId = foregroundMessageBanner?.id ?: return@LaunchedEffect
        delay(slockForegroundBannerAutoDismissMs())
        if (foregroundMessageBanner?.id == bannerId) {
            foregroundMessageBanner = null
        }
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
            renderedPayload.surfaceKind,
            renderedPayload.parentSurfaceKind
        ) {
            scopedBaseComposerState.toThreadComposerSurface(
                channelId = resolvedAuxiliaryThreadId.orEmpty(),
                parentChannelId = renderedPayload.parentChannelId,
                mentionChannelLabel = headerDisplayModel.threadComposerMentionScopeChannelLabel(),
                memberSuggestionHeadersVisible = renderedPayload.threadComposerMemberSuggestionHeadersVisible()
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
            bridge.toast(commentsUnavailableLabel)
            return
        }
        target.attachment.toThreadAttachmentCommentsPreviewPayload(attachmentCommentsEnabled)?.let { previewPayload ->
            pager.openSlockFilePreviewPage(previewPayload)
            return
        }
        val state =
            ThreadAttachmentCommentsDialogState(
                attachment = target.attachment,
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
                    bridge.toast(unableToSendCommentLabel)
                }
                is DataResult.NoPermission -> {
                    if (routeAuthRequired(result)) return@launch
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false, errorCode = result.blocker.code)
                    bridge.toast(unableToSendCommentLabel)
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
                    bridge.toast(unableToResolveCommentLabel)
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
                    bridge.toast(unableToResolveCommentLabel)
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
                        bridge.toast(unableToLoadImageLabel)
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
                imageGalleryIndex = currentIndex,
                attachmentCommentsEnabled = attachmentCommentsEnabled
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
                    bridge.toast(result.message.ifBlank { unableToOpenAttachmentLabel })
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
        pager.openSlockFilePreviewPage(
            attachment.toThreadAttachmentLazyPreviewPayload(
                kind = lazyPreviewKind,
                attachmentCommentsEnabled = attachmentCommentsEnabled
            )
        )
    }
    fun requestInlineVideoPreview(attachmentId: String, force: Boolean = false) {
        if (attachmentId.isBlank()) return
        if (inlineVideoPreviewStates[attachmentId]?.status == ThreadInlineVideoPreviewStatus.Loading) return
        if (!force && !inlineVideoPreviewStates.canRequestInlineVideoPreview(attachmentId)) return
        inlineVideoPreviewStates =
            inlineVideoPreviewStates +
                (attachmentId to ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Loading))
        coroutineScope.launch {
            when (val response = dataSource.attachmentUrl(attachmentId, disposition = "inline")) {
                is ThreadApiResult.Success -> {
                    val url = response.data.url.trim()
                    inlineVideoPreviewStates =
                        inlineVideoPreviewStates +
                            (attachmentId to
                                if (url.isNotBlank()) {
                                    ThreadInlineVideoPreviewUiState(
                                        status = ThreadInlineVideoPreviewStatus.Ready,
                                        url = url,
                                        expiresAt = response.data.expiresAt
                                    )
                                } else {
                                    ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Error)
                                })
                }
                is ThreadApiResult.Failure -> {
                    inlineVideoPreviewStates =
                        inlineVideoPreviewStates +
                            (attachmentId to ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Error))
                    if (response.code == 401) {
                        routeAuthRequired("auth_required")
                    }
                }
            }
        }
    }

    fun openComposerAttachmentPreview(attachmentId: String) {
        val attachment = currentComposerPayload().pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        val name = attachment.fileName.ifBlank { "Attachment" }
        val meta = listOf(attachment.sizeLabel, attachment.mimeType.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        if (!attachment.isReadyThreadAttachmentForSend()) {
            pager.openSlockFilePreviewPage(
                SlockFilePreviewPayload(
                    kind = if (attachment.status == "failed") "error" else "loading",
                    requestedKind = "composer_attachment",
                    title = name,
                    subtitle = meta,
                    name = name,
                    path = name,
                    meta = meta,
                    message =
                        when (attachment.status) {
                            "failed" ->
                                listOf(
                                    attachmentUploadFailedLabel,
                                    threadFailureReasonLabel(attachment.errorCode)
                                ).filterNotNull().joinToString(": ") + ". Retry the upload from the composer."
                            else -> preparingAttachmentPreviewLabel
                        },
                    messageDesc = null,
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
        val composerImageGroup = currentComposerPayload().pendingAttachments.composerPendingImageAttachmentGroup(attachmentId)
        if (composerImageGroup != null && composerImageGroup.images.size > 1) {
            pager.openSlockFilePreviewPage(
                threadAttachment.toThreadAttachmentLazyPreviewPayload(
                    kind = "image",
                    imageGallery = composerImageGroup.images,
                    imageGalleryIndex = composerImageGroup.currentIndex
                ).copy(
                    imageUrl = attachment.previewUri?.takeIf { it.isNotBlank() }
                )
            )
        } else if (threadAttachment.isImageAttachment() && imageAnnotationEnabled()) {
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
        val sendBasePayload = threadViewModel.state.value.payload
        val localMessage =
            sendBasePayload.messages.firstOrNull { it.id == localMessageId }
                ?: buildThreadPendingSendMessage(
                    localMessageId = localMessageId,
                    context = context,
                    session = currentSession,
                    timestampLabel = buildThreadPendingSendTimestampLabel(bridge, sendBasePayload.nowEpochMillis)
                )
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
        threadSendScope.launch {
            suspend fun markFailed(errorCode: String?, errorMessage: String? = null) {
                dataSource.cacheLocalSendFailure(
                    basePayload = sendBasePayload,
                    localMessage = localMessage,
                    errorCode = errorCode,
                    errorMessage = errorMessage
                )
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

            var attempt = 1
            while (true) {
                val sendResult =
                    runCatchingPreservingCancellation {
                        dataSource.sendMessage(
                            channelId = context.channelId,
                            body = context.body,
                            asTask = context.asTask,
                            attachmentIds = context.attachmentIds,
                            randomId = context.randomId
                        )
                    }
                val sendException = sendResult.exceptionOrNull()
                if (sendException != null) {
                    val errorCode = threadSendExceptionErrorCode(sendException)
                    val errorType = sendException::class.simpleName.orEmpty().ifBlank { "Throwable" }
                    val durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds
                    bridge.log(
                        "thread_send_exception localMessageId=$localMessageId " +
                            "channelId=${context.channelId} errorType=$errorType " +
                            "mapped=$errorCode attempt=$attempt durationMs=$durationMs"
                    )
                    if (attempt == 1 && threadSendShouldSilentlyRetry(errorCode)) {
                        bridge.log(
                            "thread_send_silent_retry localMessageId=$localMessageId " +
                                "channelId=${context.channelId} reason=$errorCode attempt=$attempt"
                        )
                        attempt += 1
                        delay(ThreadSendSilentRetryDelayMs)
                        continue
                    }
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
                                "attempt" to attempt.toString(),
                                "localMessageId" to localMessageId
                            )
                    )
                    markFailed(errorCode, threadSendExceptionErrorMessage(sendException))
                    return@launch
                }
                val sendResponse = sendResult.getOrThrow()
                val sentPage =
                    when (sendResponse) {
                        is ThreadApiResult.Success -> sendResponse.data
                        is ThreadApiResult.Failure -> {
                            val errorCode =
                                sendResponse.contractCode
                                    ?: threadFailureDiagnosticCode(sendResponse.errorBodyText)
                                    ?: "http_${sendResponse.code}"
                            val errorMessage = threadFailureReasonMessage(sendResponse.errorBodyText)
                            if (attempt == 1 && threadSendShouldSilentlyRetry(errorCode, sendResponse.code)) {
                                bridge.log(
                                    "thread_send_silent_retry localMessageId=$localMessageId " +
                                        "channelId=${context.channelId} reason=$errorCode httpCode=${sendResponse.code} attempt=$attempt"
                                )
                                attempt += 1
                                delay(ThreadSendSilentRetryDelayMs)
                                continue
                            }
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
                                        "attempt" to attempt.toString(),
                                        "localMessageId" to localMessageId
                                    )
                            )
                            markFailed(errorCode, errorMessage)
                            return@launch
                        }
                    }
                if (sentPage.messages.isEmpty()) {
                    if (attempt == 1) {
                        bridge.log(
                            "thread_send_silent_retry localMessageId=$localMessageId " +
                                "channelId=${context.channelId} reason=empty_response attempt=$attempt"
                        )
                        attempt += 1
                        delay(ThreadSendSilentRetryDelayMs)
                        continue
                    }
                    bridge.performanceMarker(
                        phase = "composer_send",
                        routeName = "kuikly_thread",
                        event = "end",
                        threadId = context.channelId,
                        durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                        metadata = mapOf("result" to "empty", "attempt" to attempt.toString(), "localMessageId" to localMessageId)
                    )
                    markFailed("empty_response")
                    return@launch
                }
                dataSource.cacheLocalSendSuccess(
                    basePayload = sendBasePayload,
                    localMessage = localMessage,
                    sentPage = sentPage
                )
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
                            "attempt" to attempt.toString(),
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
                return@launch
            }
        }
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
            bridge.toast(
            localizedAttachmentUploadFailedDetail(
                threadUserFacingErrorCodeLabel(errorCode) ?: genericThreadErrorLabel
            )
            )
        }
    }
    fun uploadComposerAttachmentFiles(files: List<PlatformFile>, replaceAttachmentId: String? = null) {
        val channelId = composerSurface.sendChannelId
        if (channelId == null) {
            bridge.toast(attachmentPickerUnavailableLabel)
            return
        }
        val activeUploadFileRefs =
            attachmentUploadJobs
                .filterValues { job -> job.isActive }
                .keys
                .mapNotNull { id -> attachmentUploadFilesByLocalId[id]?.fileRef?.takeIf { it.isNotBlank() } }
                .toSet()
        val acceptedFileRefs = mutableSetOf<String>()
        val acceptedFiles =
            files.filter { file ->
                val fileRef = file.fileRef?.takeIf { it.isNotBlank() }
                val isDuplicate =
                    fileRef != null &&
                        (fileRef in activeUploadFileRefs || !acceptedFileRefs.add(fileRef))
                !isDuplicate
            }
        if (acceptedFiles.size != files.size) {
            bridge.log(
                "thread_composer_attachment_duplicate_skip skipped=${files.size - acceptedFiles.size} " +
                    "activeUploads=${activeUploadFileRefs.size}"
            )
        }
        val localAttachments =
            acceptedFiles
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
                    acceptedFiles
                        .filter { file -> file.id.isNotBlank() }
                        .associateBy { file -> file.id }
            composerDraftViewModel.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(localAttachments)
            )
            bridge.log(
                "thread_composer_attachment_local_queue fileCount=${localAttachments.size} " +
                    "ids=${localAttachments.joinToString(",") { it.id }}"
            )
        }
        acceptedFiles.forEach { file ->
            val localAttachmentId = file.id.takeIf { it.isNotBlank() } ?: return@forEach
            val uploadJob =
                coroutineScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        bridge.log(
                            "thread_composer_attachment_upload_start attachmentId=$localAttachmentId fileName=${file.fileName}"
                        )
                        val uploadResult =
                            withContext(Dispatchers.Default) {
                                attachmentUploadSemaphore.withPermit {
                                    dataSource.uploadAttachments(channelId, listOf(file))
                                }
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
    DisposableEffect(eventBus, threadInstanceKey) {
        val subscription =
            eventBus.observe(SLOCK_COMPOSER_IMAGE_EDIT_EVENT, crossProcess = true) { data ->
                val eventDataSnapshot = data?.toString() ?: return@observe
                coroutineScope.launch {
                    val eventData = JSONObject(eventDataSnapshot)
                    if (eventData.optString("threadKey").orEmpty() != threadInstanceKey) return@launch
                    val fileRef = eventData.optString("fileRef").takeIf { it.isNotBlank() } ?: return@launch
                    val actionId = eventData.optString("actionId").takeIf { it.isNotBlank() } ?: fileRef.hashCode().toString()
                    val file =
                        PlatformFile(
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
            }
        onDispose {
            subscription.close()
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
                    session = currentSession,
                    timestampLabel = pending.timestampLabel
                )
            )
        )
        sendPendingLocalMessage(pending.localMessageId, readyContext, startedFromComposer = true)
    }

    suspend fun refreshComposerMemberSuggestions(reason: String) {
        val store = serverMembersStore ?: return
        val serverId = threadMembersServerId ?: currentServerId?.let(::ServerId) ?: return
        bridge.log(
            "thread_composer_member_suggestions_refresh reason=$reason " +
                "serverId=${serverId.value} mentionChannelId=${memberSuggestionScopeChannelId.orEmpty()} " +
                "memberScoped=$memberSuggestionScopedMentions"
        )
        store.ensureMemberSuggestionsForServer(
            serverId = serverId,
            mentionChannelId = memberSuggestionScopeChannelId,
            memberScopedMentions = memberSuggestionScopedMentions,
            force = true
        )
    }

    suspend fun refreshComposerMemberSuggestionsAfterMembershipMutation() {
        refreshComposerMemberSuggestions(reason = "membership_mutation")
    }

    SlockForegroundBannerHost(
        banner = foregroundMessageBanner,
        topSafeArea = slockTopSafeAreaPadding(),
        serverMembersStore = serverMembersStore,
        currentServerStore = foregroundBannerCurrentServerStore,
        currentServerIdOverride = currentSession?.currentServerId?.let(::ServerId),
        onBannerClick = { target ->
            foregroundMessageBanner = null
            val request = target.openRequest
            onOpenThreadPayload(
                buildAppShellThreadPayload(
                    conversation = request.conversationIdentity,
                    timeContext = renderedPayload.toSlockTimeContext(),
                    alignOwnMessagesRight = renderedPayload.alignOwnMessagesRight,
                    compactConsecutiveMessages = renderedPayload.compactConsecutiveMessages,
                    threadRowAffordances =
                        listOfNotNull(
                            ThreadRowAffordancePreference.Reactions.takeIf { renderedPayload.showReactionActions },
                            ThreadRowAffordancePreference.Saved.takeIf { renderedPayload.showInlineSaveAction }
                        ).toSet(),
                    anchorMessageId = request.anchorMessageId ?: target.focusedMessageId,
                    originFallback = request.originFallback
                )
            )
        },
        onBannerDismiss = {
            foregroundMessageBanner = null
        }
    ) {
        CompositionLocalProvider(
            LocalThreadInlineVideoPreviewHost provides
                ThreadInlineVideoPreviewHost(
                    states = inlineVideoPreviewStates,
                    requestPreview = ::requestInlineVideoPreview
                )
        ) {
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
            activityMuteState = activityMuteState,
            onToggleActivityMute =
                activityMuteState?.let { muteState ->
                    {
                        sharedHomeStore?.dispatch(
                            SharedHomeIntent.SetChannelActivityMuted(
                                conversationId = muteState.channelId,
                                muted = !muteState.muted
                            )
                        )
                    }
                },
            forwardServerId = currentSession?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId),
            forwardTargetRepository = forwardTargetRepository,
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
            composerSendKeyEnabled = composerSendKeyEnabled,
            currentServerSlug = currentServerSlug,
            translationStore = translationStore,
            mentionHandleResolver = { message ->
                message.threadMentionHandle(serverMembersState, threadMembersServerId)
            },
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
                            bridge.toast(fileSourceUnavailableLabel)
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
                        bridge.toast(copiedLabel)
                    }
                }
                action == "copyShareSelectionMarkdown" && !itemId.isNullOrBlank() -> {
                    bridge.copyToPasteboard(itemId)
                    bridge.toast(copiedLabel)
                }
                action == "copyShareSelectionLinks" && !itemId.isNullOrBlank() -> {
                    bridge.copyToPasteboard(itemId)
                    bridge.toast(linksCopiedLabel)
                }
                action == "shareSelectionImageSaved" -> {
                    bridge.toast(itemId?.takeIf { it.isNotBlank() } ?: imageSavedLabel)
                }
                action == "shareSelectionExportFailed" -> {
                    bridge.toast(itemId?.takeIf { it.isNotBlank() } ?: unableToExportImageLabel)
                }
                action == "forwardTargetSelected" -> {
                    val target = itemId?.takeIf { it.isNotBlank() } ?: "selected target"
                    val modeLabel = localizedForwardModeLabel(tab)
                    bridge.toast(localizedForwardNeedsApi(modeLabel, target))
                }
                action == "forwardMessagesSent" -> {
                    val target = itemId?.takeIf { it.isNotBlank() } ?: "selected target"
                    val modeLabel = localizedForwardModeLabel(tab)
                    bridge.toast(localizedForwardCompleted(modeLabel, target))
                }
                action == "forwardMessagesFailed" -> {
                    bridge.toast(itemId?.takeIf { it.isNotBlank() } ?: unableToForwardLabel)
                }
                action == "forwardJoinConfirmationRequired" -> {
                    val target = itemId?.takeIf { it.isNotBlank() } ?: "that channel"
                    bridge.toast(localizedForwardJoinFirst(target))
                }
                action == "forwardCreateGroupRequested" -> {
                    bridge.toast(forwardGroupUnavailableLabel)
                }
                action == "forwardMultiSelectRequested" -> {
                    bridge.toast(forwardMultiSelectUnavailableLabel)
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
                        bridge.toast(copiedLabel)
                    } else {
                        bridge.toast(unableToCopyLinkLabel)
                    }
                }
                action == "openSelectMessageText" && !itemId.isNullOrBlank() -> {
                    val message = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (message?.body.isNullOrBlank()) {
                        bridge.toast(noTextToSelectLabel)
                    } else {
                        textSelectionMessage = message
                    }
                }
                action == "openMessageThread" && !itemId.isNullOrBlank() -> {
                    val parentMessage =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (parentMessage == null) {
                        bridge.toast(unableToOpenRepliesLabel)
                        return@ThreadPageContent
                    }
                    val parentReplyMetadata = replyMetadataByParentId.threadReplyMetadataFor(parentMessage)
                    val parentChannelId = renderedThread.replyParentChannelIdForNestedOpen()
                    dataSource.rememberReplyParentCandidate(
                        parentChannelId = parentChannelId,
                        parentMessage = parentMessage,
                        threadChannelId = parentReplyMetadata.threadChannelId
                    )
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
                                    bridge.toast(followedThreadLabel)
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast(unableToFollowThreadLabel)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast(unableToFollowThreadLabel)
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
                                    bridge.toast(unfollowedThreadLabel)
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast(unableToUnfollowThreadLabel)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast(unableToUnfollowThreadLabel)
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
                            bridge.toast(unableToOpenParentMessageLabel)
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
                action == "openChannelSearch" -> {
                    if (!pager.openThreadChannelSearch(renderedThread)) {
                        bridge.toast(searchUnavailableLabel)
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
                                bridge.toast(if (nextSaved) unableToSaveMessageLabel else unableToRemoveSavedMessageLabel)
                            }
                            is DataResult.NoPermission -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, previousSaved))
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast(if (nextSaved) unableToSaveMessageLabel else unableToRemoveSavedMessageLabel)
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
                        bridge.toast(actionUnavailableLabel)
                        return@ThreadPageContent
                    }
                    if (!actionCard.isSupportedCommitAction()) {
                        bridge.toast(actionUnsupportedLabel)
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
                                    bridge.toast(actionCardSuccessLabels[actionCard.type] ?: genericActionCardSuccessLabel)
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel)
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
                                    bridge.toast(unableToUpdateReactionLabel)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageReactions(itemId, previousReactions)
                                    )
                                    bridge.toast(unableToUpdateReactionLabel)
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
                        bridge.toast(unableToOpenTaskLabel)
                        return@ThreadPageContent
                    }
                    val parentReplyMetadata = replyMetadataByParentId.threadReplyMetadataFor(parentMessage)
                    val parentChannelId = renderedThread.replyParentChannelIdForNestedOpen()
                    dataSource.rememberReplyParentCandidate(
                        parentChannelId = parentChannelId,
                        parentMessage = parentMessage,
                        threadChannelId = parentReplyMetadata.threadChannelId
                    )
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
                        bridge.toast(localizedUnableToOpenTarget(itemId.take(32)))
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
                                bridge.toast(localizedUnableToOpenTaskNumber(taskNumber))
                            }
                            MarkdownTaskNumberOpenResult.AuthRequired -> {
                                routeAuthRequired(
                                    DataResult.NoPermission(PermissionBlocker(code = "tasks_401"))
                                )
                            }
                            MarkdownTaskNumberOpenResult.Failed -> {
                                bridge.toast(localizedUnableToOpenTaskNumber(taskNumber))
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
                        bridge.toast(commentSourceUnavailableLabel)
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
                        bridge.toast(localizedUnableToOpenTarget(itemId.take(32)))
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
                            bridge.toast(localizedMemberNotFound(itemId.take(32)))
                        } else {
                            onOpenMemberProfile(renderedThread, renderedTimelineSlice, target.memberId, target.memberKind, target.sourceServerId)
                        }
                    }
                }
                action == "openMarkdownChannel" && !itemId.isNullOrBlank() -> {
                    coroutineScope.launch {
                        val loadingPayload = dataSource.markdownChannelLaunchPayload(renderedThread, itemId)
                        if (loadingPayload == null) {
                            bridge.toast(localizedUnableToOpenTarget("#${itemId.removePrefix("#").take(31)}"))
                        } else {
                            onOpenThreadPayload(loadingPayload)
                        }
                    }
                }
                action == "openMarkdownThreadRef" && !itemId.isNullOrBlank() -> {
                    coroutineScope.launch {
                        when (
                            val result =
                                dataSource.markdownThreadRefLaunchPayload(
                                    currentPayload = renderedThread,
                                    channelLabel = itemId,
                                    threadShortId = tab
                                )
                        ) {
                            is DataResult.Content -> {
                                bridge.log(
                                    "thread_markdown_ref_open label=${itemId.take(32)} " +
                                        "parentChannelId=${result.data.parentChannelId.orEmpty()} " +
                                        "parentMessageId=${result.data.parentMessageId.orEmpty()} " +
                                        "threadId=${result.data.streamChannelId.orEmpty()} " +
                                        "focusedMessageId=${result.data.focusedMessageId.orEmpty()}"
                                )
                                onOpenThreadPayload(result.data)
                            }
                            is DataResult.Empty -> {
                                bridge.toast(localizedUnableToOpenTarget(itemId.take(32)))
                            }
                            is DataResult.Error -> {
                                bridge.toast(localizedUnableToOpenTarget(itemId.take(32)))
                            }
                            is DataResult.NoPermission -> {
                                routeAuthRequired(result)
                            }
                        }
                    }
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
                                bridge.toast(memberAddedLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.toast(unableToAddMemberLabel)
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.toast(unableToAddMemberLabel)
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
                                bridge.toast(memberRemovedLabel)
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
                                bridge.toast(unableToRemoveMemberLabel)
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
                                bridge.toast(unableToRemoveMemberLabel)
                            }
                        }
                    }
                }
                action == "settingsAction" && itemId == "visibility" -> {
                    bridge.toast(visibilityNotWiredLabel)
                }
                action == "settingsAction" && itemId == "archive" -> {
                    val previousPayload = renderedThread
                    coroutineScope.launch {
                        when (val result = dataSource.archiveChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast(channelArchivedLabel)
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast(unableToArchiveChannelLabel)
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast(unableToArchiveChannelLabel)
                            }
                        }
                    }
                }
                action == "settingsAction" && itemId == "delete" -> {
                    val previousPayload = renderedThread
                    coroutineScope.launch {
                        when (val result = dataSource.deleteChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast(channelDeletedLabel)
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast(unableToDeleteChannelLabel)
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast(unableToDeleteChannelLabel)
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
                        bridge.toast(unableToConvertMessageLabel)
                        return@ThreadPageContent
                    }
                    pendingTaskConvertMessageIds = pendingTaskConvertMessageIds + itemId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.convertMessageToTask(itemId)) {
                                is DataResult.Content -> {
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskUpdate(result.data))
                                    bridge.toast(taskCreatedLabel)
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast(unableToConvertMessageLabel)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast(unableToConvertMessageLabel)
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
                        bridge.toast(unsupportedTaskStatusLabel)
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
                                bridge.toast(taskUpdatedLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast(unableToUpdateTaskLabel)
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast(unableToUpdateTaskLabel)
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
                                bridge.toast(taskUpdatedLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast(unableToUpdateTaskLabel)
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast(unableToUpdateTaskLabel)
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
                                bridge.toast(taskUpdatedLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast(unableToUpdateTaskLabel)
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                previousStatusLabel?.toSharedThreadTaskStatus()?.let { previousStatus ->
                                    tasksViewModel?.dispatch(SharedTasksRouteIntent.ApplyTaskStatus(itemId, previousStatus))
                                }
                                bridge.toast(unableToUpdateTaskLabel)
                            }
                        }
                    }
                }
                action == "joinChannel" -> {
                    val channelId = renderedThread.joinChannelTargetId()
                    if (channelId == null) {
                        bridge.toast(unableToJoinChannelLabel)
                        return@ThreadPageContent
                    }
                    coroutineScope.launch {
                        when (val result = dataSource.joinChannel(channelId)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyChannelJoined(result.data.channelId.value))
                                realtimeEvents.joinChannel(
                                    result.data.channelId.value,
                                    currentSession?.currentServerId
                                )
                                bridge.toast(joinedChannelLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast(unableToJoinChannelLabel)
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast(unableToJoinChannelLabel)
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
                "refreshMemberSuggestions" -> {
                    coroutineScope.launch {
                        refreshComposerMemberSuggestions(reason = "panel_open")
                    }
                }
                "refreshChannelSuggestions" -> {
                    bridge.log("thread_composer_channel_suggestions_refresh reason=panel_open")
                    membersStore?.dispatch(SharedMembersIntent.RefreshChannelSuggestions)
                }
                "staleChangeIgnored" -> {
                    bridge.log(
                        "thread_composer_stale_change_ignored instanceId=$composerInstanceId " +
                            "valueLen=${value?.length ?: -1} valueBlank=${value?.isBlank()} asTask=$asTask " +
                            "stateTextLen=${currentDraftState.seedText.length} stateTextBlank=${currentDraftState.seedText.isBlank()} " +
                            "stateAsTask=${currentDraftState.seedAsTask} draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                }
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
                            memberScopedMentions = payload.memberScopedMentions,
                            randomId = newThreadSendRandomId()
                        )
                    val localMessageId = ThreadLocalSendNextId.value(composerInstanceId)
                    val localTimestampLabel =
                        buildThreadPendingSendTimestampLabel(bridge, payload.nowEpochMillis)
                    threadViewModel.dispatch(SharedThreadIntent.PrepareForLocalSend)
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyLocalSendStarted(
                            buildThreadPendingSendMessage(
                                localMessageId = localMessageId,
                                context = pendingContext,
                                session = currentSession,
                                timestampLabel = localTimestampLabel
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
                                context = pendingContext,
                                timestampLabel = localTimestampLabel
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
                "dismissMentionNotice" -> {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.ClearMentionActionNotice)
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
                    val localState = if (verb == "add") "added" else "notified"
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.BeginMentionAction(
                            resolutionId = resolutionId,
                            state = localState
                        )
                    )
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
                                delivered = delivered,
                                state = localState
                            )
                        )
                        if (delivered && verb == "add") {
                            refreshComposerMemberSuggestionsAfterMembershipMutation()
                        }
                        if (delivered) {
                            delay(ThreadComposerMentionActionFadeDelayMs)
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.MarkMentionActionRemoving(resolutionId)
                            )
                            delay(ThreadComposerMentionActionRemoveDelayMs - ThreadComposerMentionActionFadeDelayMs)
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.RemoveMentionAction(resolutionId)
                            )
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
                            bridge.toast(attachmentPickerUnavailableLabel)
                            return@launch
                        }
                        val response =
                            bridge.threadAttachmentUpload(
                                channelId = channelId,
                                imageOnly = action == "attachImage"
                            )
                        val files = parseSharedThreadPickedPlatformFiles(response)
                        bridge.log(
                            "thread_attachment_picker_result action=$action code=${response?.optInt("code", -1) ?: -1} " +
                                "files=${files.size} errorCode=${response?.optString("errorCode").orEmpty()} " +
                                "bodyBytes=${response?.optString("bodyText").orEmpty().encodeToByteArray().size}"
                        )
                        if (files.isEmpty()) {
                            val errorCode = response?.optString("errorCode")?.takeIf { it.isNotBlank() }
                            when {
                                errorCode != null ->
                                    bridge.toast(
                                        localizedAttachmentUploadFailedDetail(
                                            threadUserFacingErrorCodeLabel(errorCode) ?: genericThreadErrorLabel
                                        )
                                    )
                                response == null ->
                                    bridge.toast(attachmentPickerUnavailableLabel)
                            }
                            return@launch
                        }
                        val skippedCount = response?.optInt("skippedCount", 0) ?: 0
                        if (skippedCount > 0) {
                            bridge.toast(
                                if (skippedCount == 1) {
                                    attachmentSkippedOneLabel
                                } else {
                                    localizedAttachmentSkippedMany(skippedCount)
                                }
                            )
                        }
                        when (
                            resolveThreadPickedAttachmentComposerAction(
                                action = action,
                                fileCount = files.size,
                                imageAnnotationEnabled = action == "attachImage" && imageAnnotationEnabled()
                            )
                        ) {
                            ThreadPickedAttachmentComposerAction.UploadToComposer -> uploadComposerAttachmentFiles(files)
                            ThreadPickedAttachmentComposerAction.None -> Unit
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
                            bridge.toast(attachmentRetryUnavailableLabel)
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
        }
        markdownTaskCandidateModalState?.let { modalState ->
            ThreadMarkdownTaskCandidateModal(
                state = modalState,
                currentTaskChannelId = taskChannelId,
                onDismiss = { markdownTaskCandidateModalState = null },
                onSelect = { task ->
                    val loadingPayload = renderedThread.markdownTaskNumberLaunchPayload(task)
                    if (loadingPayload == null) {
                        bridge.toast(localizedUnableToOpenTaskNumber(modalState.taskNumber))
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
                    bridge.toast(copiedLabel)
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
                                    bridge.toast(actionCardSuccessLabels[submitCard.type] ?: genericActionCardSuccessLabel)
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
        title = stringResource(MR.strings.thread_open_task_number, state.taskNumber),
        onDismiss = onDismiss,
        minWidth = 320.dp,
        maxWidth = 392.dp,
        contentPadding = 20.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = state.localizedThreadMarkdownTaskCandidateHelperText(),
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack.copy(alpha = 0.58f)
            )
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(threadMarkdownTaskCandidateListHeight(state.candidates.size)),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.candidates.isEmpty()) {
                    item {
                        ThreadModalEmptyState(
                            title = stringResource(MR.strings.thread_state_no_tasks),
                            detail = stringResource(MR.strings.thread_members_refresh_detail)
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
}

@Composable
private fun ThreadMarkdownTaskCandidateRow(
    task: TaskSummary,
    currentChannel: Boolean,
    onClick: () -> Unit
) {
    val background = if (currentChannel) SlockColor.BrandYellow.copy(alpha = 0.18f) else SlockColor.SurfaceWhite
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 82.dp)
                    .background(background)
                    .border(
                        SlockStroke.Default,
                        if (currentChannel) SlockColor.BorderBlack else SlockColor.TextBlack.copy(alpha = 0.26f)
                    )
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "#${task.taskNumber ?: "?"}",
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.52f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = task.localizedThreadMarkdownTaskCandidateChannelLabel(),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.52f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = task.title.ifBlank { stringResource(MR.strings.thread_task_candidate_untitled) },
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = task.localizedThreadMarkdownTaskCandidateDetail(currentChannel),
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.48f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                val statusMeta = task.status.sharedTaskStatusChipMeta()
                SlockAnchoredMetaChip(
                    label = statusMeta.label,
                    iconName = statusMeta.icon,
                    iconSize = statusMeta.iconSize,
                    color = statusMeta.color,
                    modifier = Modifier.widthIn(max = 112.dp),
                    onClick = null
                )
                if (currentChannel) {
                    SlockAnchoredMetaChip(
                        label = stringResource(MR.strings.thread_task_candidate_current),
                        iconName = SlockLucideIconName.Hash,
                        iconSize = 10.dp,
                        color = SlockColor.BrandYellow,
                        modifier = Modifier.widthIn(max = 112.dp),
                        onClick = null
                    )
                }
            }
        }
    }
}

private fun ThreadMarkdownTaskCandidateModalState.threadMarkdownTaskCandidateHelperText(): String =
    when (candidates.size) {
        0 -> "No matching task loaded for #$taskNumber."
        1 -> "Open the matching task."
        else -> "Choose which #$taskNumber task to open."
    }

@Composable
private fun ThreadMarkdownTaskCandidateModalState.localizedThreadMarkdownTaskCandidateHelperText(): String =
    when (candidates.size) {
        0 -> stringResource(MR.strings.thread_task_candidate_none, taskNumber)
        1 -> stringResource(MR.strings.thread_task_candidate_open_match)
        else -> stringResource(MR.strings.thread_task_candidate_choose, taskNumber)
    }

private fun TaskSummary.threadMarkdownTaskCandidateChannelLabel(): String =
    channelName?.trim()?.takeIf { it.isNotBlank() }?.let { "#$it" } ?: "Unknown channel"

@Composable
private fun TaskSummary.localizedThreadMarkdownTaskCandidateChannelLabel(): String =
    channelName?.trim()?.takeIf { it.isNotBlank() }?.let { "#$it" }
        ?: stringResource(MR.strings.thread_task_candidate_unknown_channel)

private fun TaskSummary.threadMarkdownTaskCandidateDetail(currentChannel: Boolean): String =
    if (currentChannel) {
        "Current conversation"
    } else {
        "Tap to open this task thread"
    }

@Composable
private fun TaskSummary.localizedThreadMarkdownTaskCandidateDetail(currentChannel: Boolean): String =
    if (currentChannel) {
        stringResource(MR.strings.thread_task_candidate_current_conversation)
    } else {
        stringResource(MR.strings.thread_task_candidate_tap_open)
    }

private fun threadMarkdownTaskCandidateListHeight(count: Int): Dp =
    (count * 80 + 12).coerceIn(156, 420).dp

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
        errorMessage = dialogState.errorCode?.let { threadCreateAgentDialogErrorCopy(it) },
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
        machinesErrorMessage = machinesErrorCode?.let { threadCreateAgentMachinesErrorCopy(it) },
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
        segment == KuiklyThreadSurfaceKind.Channel || segment == KuiklyThreadSurfaceKind.DirectMessage
    }
    if (routeIndex < 0) return null
    val routeKind = segments.getOrNull(routeIndex)?.lowercase()
        ?.takeIf { it == KuiklyThreadSurfaceKind.Channel || it == KuiklyThreadSurfaceKind.DirectMessage }
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
                if (target.routeKind == KuiklyThreadSurfaceKind.DirectMessage) {
                    KuiklyThreadSurfaceKind.DirectMessage
                } else {
                    KuiklyThreadSurfaceKind.Channel
                },
            parentChannelKind =
                if (target.routeKind == KuiklyThreadSurfaceKind.DirectMessage) {
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
    val opensReplyThread = rootThreadId != null || source.type == KuiklyThreadSurfaceKind.ReplyThread
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
        if (source.routeKind == KuiklyThreadSurfaceKind.DirectMessage) {
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
            .filter { slockFilePreviewIsImageAttachment(fileName = it.name, mimeType = it.mimeType) }
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

internal fun List<KuiklyPendingAttachmentItem>.composerPendingImageAttachmentGroup(
    attachmentId: String
): ThreadImageAttachmentGroup? {
    val cleanAttachmentId = attachmentId.trim()
    if (cleanAttachmentId.isBlank()) return null
    val images =
        asSequence()
            .filter { attachment ->
                attachment.status == "ready" &&
                    attachment.isComposerImageAttachment() &&
                    attachment.previewUri?.isNotBlank() == true
            }
            .map { attachment ->
                KuiklyThreadAttachmentItem(
                    id = attachment.id,
                    name = attachment.fileName.ifBlank { "Attachment" },
                    mimeType = attachment.mimeType.orEmpty(),
                    sizeLabel = attachment.sizeLabel,
                    thumbnailUrl = attachment.previewUri
                )
            }
            .toList()
    val currentIndex = images.indexOfFirst { it.id == cleanAttachmentId }
    return if (currentIndex >= 0) {
        ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex)
    } else {
        null
    }
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
    val currentPreviewState by rememberUpdatedState(state)
    val currentOnSelectIndex by rememberUpdatedState(onSelectIndex)
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val swipeThresholdPx =
        with(LocalDensity.current) {
            56.dp.toPx()
        }
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    SlockBackHandler(
        enabled = !menuOpen,
        label = "thread_image_preview",
        onBack = onDismiss
    )
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SlockTopPageHeader(
                title = stringResource(MR.strings.thread_image_preview_title),
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
                                                    dragDistance <= -swipeThresholdPx && currentPreviewState.currentIndex < currentPreviewState.images.lastIndex -> {
                                                        currentOnSelectIndex(currentPreviewState.currentIndex + 1)
                                                    }

                                                    dragDistance >= swipeThresholdPx && currentPreviewState.currentIndex > 0 -> {
                                                        currentOnSelectIndex(currentPreviewState.currentIndex - 1)
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
                                title =
                                    if (failedWithoutImage) {
                                        stringResource(MR.strings.thread_image_preview_unavailable)
                                    } else {
                                        stringResource(MR.strings.thread_image_preview_loading)
                                    },
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
            SlockRaftUiControlledMenu(
                expanded = menuOpen,
                anchor = menuAnchor,
                items =
                    listOf(
                        SlockRaftUiMenuItem(
                            id = ThreadAttachmentPreviewOpenWithActionId,
                            label = stringResource(MR.strings.thread_image_preview_open_with),
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
                width = 180.dp
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
            text = stringResource(MR.strings.thread_image_preview_loading_full),
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
        this.parentSurfaceKind.parsedThreadParentSurfaceKindOrNull()
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
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_SAVED = "saved"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_DELETED_HUMAN = "deleted_human"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT = "rich_content"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_SELECTION = "share_selection"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_SHARE_PREVIEW = "share_preview"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE = "long_inline_code"
private const val THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP = "wide_markdown_wrap"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_TASK524_MARKDOWN_VISIBLE = "task524_markdown_visible"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_ACTION_CARD_CATALOG = "action_card_catalog"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD = "approve_login_action_card"
internal const val THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD_EXECUTED = "approve_login_action_card_executed"

private fun threadMessageVisualWidth(visualFixture: String): Dp =
    if (visualFixture == THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP) {
        920.dp
    } else {
        390.dp
    }

internal fun threadVisualFixture(
    fixture: String,
    instanceId: String
): KuiklyThreadScreenPayload {
    val resolvedInstanceId = instanceId.ifBlank { "visual-thread-$fixture" }
    return when (fixture) {
        THREAD_MESSAGE_VISUAL_FIXTURE_SAVED -> {
            val base = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, resolvedInstanceId)
            base.copy(
                showMessageSaveAction = true,
                showInlineSaveAction = false,
                messages =
                    base.messages.mapIndexed { index, message ->
                        if (index == 0) message.copy(isSaved = true) else message
                    }
            )
        }
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
                showReplyActions = true,
                alignOwnMessagesRight = true,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("product"),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "msg-agent-reply",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            // react fixtureData.json seeds Cindy activity=working
                            // (yellow pulsing dot); "online" rendered the lime dot.
                            authorAgentStatus = "working",
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
                                    KuiklyThreadReactionItem(emoji = "👍", count = 1, selected = false)
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
                knownMarkdownChannelNames = listOf("design"),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "msg_rich_visual",
                            authorId = "agent-cindy",
                            authorName = "Cindy",
                            authorDescription = "Keeps visual testing release notes aligned.",
                            authorKind = "agent",
                            authorAgentStatus = "online",
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
                            authorDescription = "Keeps visual testing release notes aligned.",
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
                subtitle = "Full markdown + long inline code fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown",
                sourceThreadId = "channel-markdown",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = true,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = true,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("markdown专修"),
                knownMarkdownMentionNames = listOf("artin"),
                knownMarkdownTaskNumbers = listOf(42, 43),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
                                    "Command should wrap with the same yellow fill and black frame: `./gradlew :shared:testDebugUnitTest --tests build.raft.app.markdown.SlockMarkdownTest --tests build.raft.app.visual.KmpVisualScreenshotCaptureTest.captureThreadMessageRowLongInlineCode`.\n\n" +
                                    "> Quote block keeps normal markdown flow beside inline `code` and Slock tags.\n\n" +
                                    "- Link: https://raft.build/visual-testing\n" +
                                    "- File: `compose/shared/src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt`",
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
                                    KuiklyThreadReactionItem(emoji = "👍", count = 3, selected = false),
                                    KuiklyThreadReactionItem(emoji = "✅", count = 1, selected = true)
                                ),
                            attachments =
                                listOf(
                                    KuiklyThreadAttachmentItem(
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
                        KuiklyThreadTaskItem(
                            id = "task_42",
                            title = "Markdown inline code uses standard CODE_SPAN renderer",
                            status = "in_review",
                            taskNumber = 42,
                            messageId = "msg_long_inline_code_visual",
                            assigneeId = "agent-cindy",
                            assigneeName = "Cindy",
                            updatedAtLabel = "06/25 05:55"
                        ),
                        KuiklyThreadTaskItem(
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
                        ),
                        KuiklyThreadMemberItem(
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
                    KuiklyThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#markdown",
                subtitle = "Wide markdown wrap fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                streamChannelId = "channel-markdown-wide",
                sourceThreadId = "channel-markdown-wide",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("markdown专修"),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
        THREAD_MESSAGE_VISUAL_FIXTURE_TASK524_MARKDOWN_VISIBLE ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                title = "#首页专修",
                subtitle = "Task #524 markdown visibility fixture",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                streamChannelId = "thread-task524-visible",
                sourceThreadId = "thread-task524-visible",
                parentChannelId = "channel-home",
                parentMessageId = "parent-task524-visible",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                alignOwnMessagesRight = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                knownMarkdownChannelNames = listOf("首页专修"),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
                        KuiklyThreadMemberItem(
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
                    KuiklyThreadComposerPayload(
                        instanceId = resolvedInstanceId,
                        enabled = false
                    )
            )
        THREAD_MESSAGE_VISUAL_FIXTURE_ACTION_CARD_CATALOG ->
            threadActionCardVisualFixture(resolvedInstanceId, threadActionCardCatalogMessages())
        THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD ->
            threadActionCardVisualFixture(resolvedInstanceId, approveLoginActionCardVisualMessages(executed = false))
        THREAD_MESSAGE_VISUAL_FIXTURE_APPROVE_LOGIN_ACTION_CARD_EXECUTED ->
            threadActionCardVisualFixture(resolvedInstanceId, approveLoginActionCardVisualMessages(executed = true))
        else ->
            KuiklyThreadScreenPayload(
                instanceId = resolvedInstanceId,
                status = "error",
                errorCode = "visual_fixture_unknown"
            )
    }
}

private fun threadActionCardVisualFixture(
    resolvedInstanceId: String,
    messages: List<KuiklyThreadMessageItem>
): KuiklyThreadScreenPayload =
    KuiklyThreadScreenPayload(
        instanceId = resolvedInstanceId,
        title = "#visual-testing",
        subtitle = "Thread action card visual fixture",
        status = "content",
        surfaceKind = KuiklyThreadSurfaceKind.Channel,
        streamChannelId = "channel-visual-testing",
        sourceThreadId = "channel-visual-testing",
        selectedTab = "chat",
        showModeTabs = false,
        showReplyActions = false,
        alignOwnMessagesRight = false,
        showMessageSaveAction = false,
        showTaskOpenActions = false,
        showTaskStatusActions = false,
        messages = messages,
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

private fun threadActionCardCatalogMessages(): List<KuiklyThreadMessageItem> =
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
        )
    )

private fun approveLoginActionCardVisualMessages(executed: Boolean): List<KuiklyThreadMessageItem> {
    val duplicateDraftHint =
        "LiBai is requesting human approval to use Quiver. Server-installed apps do not need this approval; Marketplace apps do."
    val prepared =
        ThreadActionCard(
            type = "integration:approve_agent_login",
            state = "prepared",
            name = "LiBai",
            requestId = "login-request-visual",
            agentId = "agent-libai",
            agentName = "LiBai",
            clientId = "client-quiver",
            clientKey = "quiver",
            clientName = "Quiver",
            scopes = listOf("identity", "openid", "profile"),
            draftHint = duplicateDraftHint
        )
    val card =
        if (executed) {
            prepared.copy(
                state = "executed",
                executedByUserName = "artin",
                resultKind = "agent-integration-login",
                resultName = "Quiver"
            )
        } else {
            prepared
        }
    return listOf(
        threadActionCardVisualMessage(
            id = if (executed) "msg-action-card-approve-login-executed" else "msg-action-card-approve-login-prepared",
            body = if (executed) "Quiver login approved for @LiBai" else "Approve Quiver for @LiBai",
            card = card
        )
    )
}

private fun threadActionCardVisualMessage(
    id: String,
    body: String,
    card: ThreadActionCard
): KuiklyThreadMessageItem =
    KuiklyThreadMessageItem(
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

private fun KuiklyThreadScreenPayload.threadComposerMentionScopeChannelId(): String? {
    val resolvedComposer = resolveThreadComposer()
    return resolvedComposer.mentionChannelId.nonBlank()
        ?: resolvedAuxiliaryThreadId()
        ?: resolvedComposer.streamChannelId.nonBlank()
        ?: streamChannelId.nonBlank()
        ?: sourceThreadId.nonBlank()
        ?: parentChannelId.nonBlank()
}

internal fun KuiklyThreadScreenPayload.threadComposerMemberScopedMentions(): Boolean =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> false
        KuiklyThreadSurfaceKind.ReplyThread -> false
        else -> memberScopedMentions
    }

internal fun KuiklyThreadScreenPayload.threadComposerMemberSuggestionHeadersVisible(): Boolean =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> false
        KuiklyThreadSurfaceKind.ReplyThread ->
            parentSurfaceKind.parsedThreadParentSurfaceKindOrNull() != KuiklyThreadSurfaceKind.DirectMessage
        else -> true
    }

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

private fun String.requiresViewerSelfMentionContext(): Boolean =
    this == THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE ||
        this == THREAD_MESSAGE_VISUAL_FIXTURE_WIDE_MARKDOWN_WRAP

internal fun PendingMentionAction.toKuiklyPendingMentionActionItem(): KuiklyPendingMentionActionItem =
    KuiklyPendingMentionActionItem(
        resolutionId = resolutionId,
        messageId = messageId?.value,
        channelId = channelId?.value,
        channelName = channelName,
        targetType = targetType,
        targetHandle = targetHandle,
        targetAvatarUrl = targetAvatarUrl,
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
        mentionActionStates = draftState.mentionActionStates,
        removingMentionActionIds = draftState.removingMentionActionIds.toList(),
        executingMentionActionIds = draftState.executingMentionActionIds.toList(),
        executingMentionActionStates = draftState.executingMentionActionStates,
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
    activityMuteState: ThreadActivityMuteUiState? = null,
    onToggleActivityMute: (() -> Unit)? = null,
    forwardServerId: ServerId?,
    forwardTargetRepository: ForwardMessageTargetRepository?,
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
    composerSendKeyEnabled: Boolean = true,
    currentServerSlug: String? = null,
    translationStore: SharedMessageTranslationStore? = null,
    mentionHandleResolver: (KuiklyThreadMessageItem) -> String? = { message -> message.threadMentionHandle() },
    onExportShareImage: suspend (ThreadShareCaptureRect?) -> JSONObject?,
    onSaveShareImage: suspend (String) -> JSONObject?,
    onBack: () -> Unit,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit
) {
    val renderEarlyLoadingShell = payload.shouldRenderThreadEarlyLoadingShell(timelineSlice)
    val shareExportScope = rememberCoroutineScope()
    val markdownTagFeedbackScope = rememberCoroutineScope()
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
    var visibleTasksStartThreadId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedTab, taskChannelId?.value, payload.status, tasksViewModel) {
        val resolvedTaskChannelId = taskChannelId ?: return@LaunchedEffect
        tasksViewModel ?: return@LaunchedEffect
        if (selectedTab != KuiklyThreadTab.Tasks) {
            return@LaunchedEffect
        }
        if (visibleTasksStartThreadId == resolvedTaskChannelId.value) {
            return@LaunchedEffect
        }
        if (!shouldStartThreadTasksLoad(payload, resolvedTaskChannelId)) {
            return@LaunchedEffect
        }
        visibleTasksStartThreadId = resolvedTaskChannelId.value
        diagnosticsLogger(
            "thread_tasks_join_start threadId=${payload.sourceThreadId.orEmpty()} reason=selected_tasks_tab"
        )
        onTasksIntent(SharedTasksRouteIntent.Start)
    }
    var activeModal by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var activeForwardBundle by remember(threadInstanceKey) { mutableStateOf<ForwardMessageBundle?>(null) }
    var activeForwardSelectionMode by remember(threadInstanceKey) { mutableStateOf(ThreadForwardSelectionMode.Merged) }
    var selectedShareMessageIds by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    val selectedShareBounds = remember(threadInstanceKey) { mutableMapOf<String, ThreadShareCaptureRect>() }
    var sharePreviewImage by remember(threadInstanceKey) { mutableStateOf<ThreadSharePreviewImage?>(null) }
    var sharePreviewLoading by remember(threadInstanceKey) { mutableStateOf(false) }
    var shareCaptureMode by remember(threadInstanceKey) { mutableStateOf(false) }
    var shareCaptureTargetMessageId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var inlineSearchVisible by remember(threadInstanceKey) { mutableStateOf(false) }
    var forwardTargetQuery by remember(threadInstanceKey) { mutableStateOf("") }
    var forwardTargets by remember(threadInstanceKey) { mutableStateOf<List<ForwardMessageTarget>>(emptyList()) }
    var forwardTargetStatus by remember(threadInstanceKey) { mutableStateOf(SharedMembersLoadStatus.Idle) }
    var forwardTargetErrorCode by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var forwardSendingTargetId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var forwardSelectedTarget by remember(threadInstanceKey) { mutableStateOf<ForwardMessageTarget?>(null) }
    var forwardNote by remember(threadInstanceKey) { mutableStateOf("") }
    var insertMentionRequestNonce by remember(threadInstanceKey) { mutableStateOf(0) }
    var insertMentionRequest by remember(threadInstanceKey) { mutableStateOf<ThreadComposerInsertMentionRequest?>(null) }
    val shareSelectionActive = selectedShareMessageIds.isNotEmpty() && selectedTab == KuiklyThreadTab.Chat
    val selectableShareMessageIds =
        remember(timelineSlice) {
            timelineSlice.threadSelectableShareMessageIds()
        }
    val selectedShareMessages =
        remember(timelineSlice, selectedShareMessageIds) {
            timelineSlice.threadSelectedShareMessages(selectedShareMessageIds)
        }
    val forwardMessageSelection =
        remember(selectedShareMessages) {
            selectedShareMessages.threadForwardMessageSelection()
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
    val forwardTargetItems =
        remember(forwardTargets) {
            forwardTargets.map { it.toThreadForwardTargetItem() }
        }
    LaunchedEffect(activeModal, forwardTargetQuery, forwardServerId, forwardTargetRepository, selectedShareMessageIds) {
        if (activeModal != "forwardTargets") return@LaunchedEffect
        val repository = forwardTargetRepository
        val serverId = forwardServerId
        if (repository == null || serverId == null) {
            forwardTargets = emptyList()
            forwardTargetStatus = SharedMembersLoadStatus.Error
            forwardTargetErrorCode = "forward_target_repository_unavailable"
            return@LaunchedEffect
        }
        forwardTargetStatus = SharedMembersLoadStatus.InFlight
        forwardTargetErrorCode = null
        val result =
            if (forwardTargetQuery.isBlank()) {
                repository.recentTargets(serverId, limit = 20)
            } else {
                repository.searchTargets(serverId, forwardTargetQuery, limit = 30)
            }
        when (result) {
            is DataResult.Content -> {
                forwardTargets = result.data
                forwardTargetStatus = SharedMembersLoadStatus.Loaded
                forwardTargetErrorCode = null
            }
            is DataResult.Empty -> {
                forwardTargets = emptyList()
                forwardTargetStatus = SharedMembersLoadStatus.Loaded
                forwardTargetErrorCode = null
            }
            is DataResult.Error -> {
                forwardTargets = emptyList()
                forwardTargetStatus = SharedMembersLoadStatus.Error
                forwardTargetErrorCode = result.error.code
            }
            is DataResult.NoPermission -> {
                forwardTargets = emptyList()
                forwardTargetStatus = SharedMembersLoadStatus.Error
                forwardTargetErrorCode = result.blocker.code
            }
        }
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
    val transientBackTarget =
        threadTransientBackTarget(
            activeModal = activeModal,
            shareSelectionActive = shareSelectionActive,
            inlineSearchVisible = inlineSearchVisible
        )
    SlockBackHandler(
        enabled = transientBackTarget != null,
        label = transientBackTarget?.label
    ) {
        when (transientBackTarget) {
            ThreadTransientBackTarget.ShareSelection -> clearShareSelection()
            ThreadTransientBackTarget.InlineSearch -> inlineSearchVisible = false
            null -> Unit
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
            val targetIndex = timelineSlice.threadMessageListItemIndex(message.id)
            if (targetIndex < 0) return@forEach
            selectedShareBounds.clear()
            shareCaptureTargetMessageId = message.id
            delay(ThreadShareCaptureScrollSettleMs + 64L)
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
        if (fragments.size != selectedShareMessages.size) return null
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
    val composerKeyboardInsetExcludesBottomSafeArea = slockKeyboardInsetExcludesBottomSafeArea()
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
    val activeDraftRecords by composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    var activeMarkdownTagKey by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var activeMarkdownTagPressId by remember(threadInstanceKey) { mutableStateOf(0) }
    val openMarkdownTag: (ThreadMarkdownTag) -> Unit = { tag ->
        val activeKey = threadMarkdownTagActivationKey(tag)
        val pressId = activeMarkdownTagPressId + 1
        activeMarkdownTagPressId = pressId
        activeMarkdownTagKey = activeKey
        markdownTagFeedbackScope.launch {
            delay(ThreadMarkdownTagActiveFeedbackMs)
            if (activeMarkdownTagPressId == pressId && activeMarkdownTagKey == activeKey) {
                activeMarkdownTagKey = null
            }
        }
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
        remember(payload.knownMarkdownMentionNames, payload.members, payload.memberCandidates, payload.originMessage, payload.messages) {
            payload.toKnownMarkdownMentionNameSet()
        }
    val knownMarkdownTaskNumbers =
        remember(payload.knownMarkdownTaskNumbers) {
            payload.toKnownMarkdownTaskNumberSetOrNull()
        }
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean =
        remember(knownMarkdownChannelNames, knownMarkdownMentionNames, knownMarkdownTaskNumbers) {
            { tag -> tag.isKnownThreadMarkdownTag(knownMarkdownChannelNames, knownMarkdownMentionNames, knownMarkdownTaskNumbers) }
        }
    val markdownTagContextSignature =
        remember(highlightedMentions, knownMarkdownChannelNames, knownMarkdownMentionNames, knownMarkdownTaskNumbers) {
            threadMarkdownTagContextSignature(
                highlightedMentions = highlightedMentions,
                knownChannelNames = knownMarkdownChannelNames,
                knownMentionNames = knownMarkdownMentionNames,
                knownTaskNumbers = knownMarkdownTaskNumbers
            )
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
    var threadHeaderScrollToTopRequest by remember(threadInstanceKey) { mutableStateOf(0) }
    val renderThreadTabContent: @Composable (KuiklyThreadTab, Modifier) -> Unit = { tab, contentModifier ->
        Box(
            modifier = contentModifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
        ) {
            if (renderEarlyLoadingShell) {
                ThreadStateMessage(stringResource(MR.strings.thread_state_loading), null)
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
                        inlineSearchVisible = inlineSearchVisible,
                        isMarkdownTagValid = isMarkdownTagValid,
                        markdownTagContextSignature = markdownTagContextSignature,
                        resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                        activeMarkdownTagKey = activeMarkdownTagKey,
                        onThreadEvent = onThreadEvent,
                        onOpenMember = { memberId -> onOpenMemberProfile(memberId, null, null) },
                        onMentionAuthor = { message ->
                            val handle = mentionHandleResolver(message)
                            if (showComposerForSelectedTab && handle != null) {
                                insertMentionRequestNonce += 1
                                insertMentionRequest =
                                    ThreadComposerInsertMentionRequest(
                                        nonce = insertMentionRequestNonce,
                                        handle = handle
                                    )
                            }
                        },
                        onOpenMarkdownTag = openMarkdownTag,
                        onOpenMarkdownUrl = openMarkdownUrl,
                        diagnosticsLogger = diagnosticsLogger,
                        shareSelectionActive = shareSelectionActive && !shareCaptureMode,
                        shareSelectionCaptureActive = shareCaptureMode,
                        shareSelectionCaptureAnchorMessageId =
                            shareCaptureTargetMessageId ?: selectedShareMessages.firstOrNull()?.id,
                        headerScrollToTopRequest = threadHeaderScrollToTopRequest,
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
                        onOpenForwardBundle = { bundle ->
                            activeForwardBundle = bundle
                            activeModal = "forwardBundle"
                        },
                        translationStore = translationStore,
                        onThreadContentReady = {}
                    )
                KuiklyThreadTab.Tasks ->
                    ThreadTasksTab(
                        tasksViewModel = tasksViewModel,
                        channelId = taskPanelChannelId,
                        openTaskEnabled = payload.showTaskOpenActions,
                        draftProjection = draftProjection,
                        timeContext = payload.toSlockTimeContext(),
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
                activityMuteState = activityMuteState,
                memberAvatarResolver = memberAvatarResolver,
                onBack = onBack,
                onOpenInChannel = {
                    onThreadEvent("openParentMessage", payload.originParentMessageId, null, null)
                },
                onOpenMemberProfile = onOpenMemberProfile,
                onOpenSearch = {
                    when (payload.threadHeaderSearchAction()) {
                        ThreadHeaderSearchAction.ChannelSearch ->
                            onThreadEvent("openChannelSearch", payload.threadChannelSearchChannelId(), null, null)
                        ThreadHeaderSearchAction.InlineThreadSearch -> {
                            if (selectedTab != KuiklyThreadTab.Chat) {
                                selectedTab = KuiklyThreadTab.Chat
                                onThreadEvent("selectTab", null, null, KuiklyThreadTab.Chat.wireValue)
                            }
                            inlineSearchVisible = !inlineSearchVisible
                        }
                        null -> Unit
                    }
                },
                onShowMembers = {
                    activeModal = "members"
                    onThreadEvent("loadMembers", null, null, null)
                },
                onShowSettings = {
                    activeModal = "settings"
                },
                onToggleActivityMute = onToggleActivityMute,
                onTitleClick =
                    if (threadHeaderScrollToTopEnabled(payload.surfaceKind, selectedTab.wireValue)) {
                        { threadHeaderScrollToTopRequest += 1 }
                    } else {
                        null
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
                    label = localizedThreadJoinLabel(payload.joinLabel),
                    onJoin = { onThreadEvent("joinChannel", null, null, null) }
                )
            } else if (shareSelectionActive) {
                if (!shareCaptureMode) {
                    ThreadShareSelectionToolbar(
                        selectedCount = selectedShareMessages.size,
                        selectableCount = selectableShareMessageIds.size,
                        onCancel = clearShareSelection,
                        onSelectAll = {
                            selectedShareMessageIds = selectableShareMessageIds
                        },
                        onForward = {
                            if (forwardMessageSelection.forwardableMessages.isEmpty()) {
                                onThreadEvent(
                                    "forwardMessagesFailed",
                                    forwardMessageSelection.threadForwardSelectionFailureMessage(),
                                    null,
                                    null
                                )
                            } else {
                                activeForwardSelectionMode = ThreadForwardSelectionMode.Merged
                                forwardTargetQuery = ""
                                forwardSelectedTarget = null
                                forwardNote = ""
                                activeModal = "forwardTargets"
                            }
                        },
                        onExportImage = {
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
                        onCopyLinks = {
                            if (selectedShareLinks.isNotBlank()) {
                                onThreadEvent("copyShareSelectionLinks", selectedShareLinks, null, null)
                            } else if (selectedShareMarkdown.isNotBlank()) {
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
                                bottom =
                                    threadComposerBottomPadding(
                                        keyboardInsetDp = composerKeyboardInsetDp,
                                        bottomSafeArea = composerBottomSafeArea,
                                        keyboardInsetExcludesBottomSafeArea =
                                            composerKeyboardInsetExcludesBottomSafeArea
                                    )
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
                            insertMentionRequest = insertMentionRequest,
                            onInsertMentionConsumed = { request ->
                                if (insertMentionRequest?.nonce == request.nonce) {
                                    insertMentionRequest = null
                                }
                            },
                            composerSendKeyEnabled = composerSendKeyEnabled,
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
            "forwardBundle" ->
                activeForwardBundle?.let { bundle ->
                    ThreadForwardBundleModal(
                        bundle = bundle,
                        memberAvatarResolver = memberAvatarResolver,
                        onDismiss = {
                            activeModal = null
                            activeForwardBundle = null
                        },
                        onOpenMember = { item ->
                            onOpenMemberProfile(item.authorId, item.forwardMemberKind(), null)
                        }
                    )
                }
            "forwardTargets" ->
                ThreadForwardTargetModal(
                    selectedCount = forwardMessageSelection.forwardableMessages.size,
                    skippedCount = forwardMessageSelection.skippedCount,
                    nestedForwardCount = forwardMessageSelection.nestedForwardCount,
                    query = forwardTargetQuery,
                    loading = forwardTargetStatus == SharedMembersLoadStatus.InFlight,
                    targets = forwardTargetItems,
                    selectedTargetId = forwardSelectedTarget?.let { "${it.kind.name}:${it.id}" },
                    errorCode = forwardTargetErrorCode,
                    sendingTargetId = forwardSendingTargetId,
                    note = forwardNote,
                    onQueryChange = { forwardTargetQuery = it },
                    onNoteChange = { forwardNote = it },
                    onDismiss = { activeModal = null },
                    onSelectTarget = { target ->
                        forwardSelectedTarget = target.forwardTarget
                    },
                    onSend = {
                        val repository = forwardTargetRepository
                        val sourceChannelId = payload.threadForwardSourceChannelId()
                        val forwardTarget = forwardSelectedTarget
                        when {
                            repository == null ->
                                onThreadEvent("forwardMessagesFailed", "Forward API unavailable", null, null)
                            sourceChannelId == null ->
                                onThreadEvent("forwardMessagesFailed", "Source conversation unavailable", null, null)
                            forwardTarget == null ->
                                onThreadEvent("forwardMessagesFailed", "Forward target unavailable", null, null)
                            forwardSendingTargetId == null -> {
                                forwardSendingTargetId = "${forwardTarget.kind.name}:${forwardTarget.id}"
                                shareExportScope.launch {
                                    try {
                                        when (val resolved = repository.resolveTargetChannel(forwardTarget)) {
                                            is DataResult.Content -> {
                                                val request =
                                                    ForwardMessageExecuteRequest(
                                                        sourceChannelId = sourceChannelId,
                                                        targetChannelId = resolved.data,
                                                        messageIds = forwardMessageSelection.forwardableMessages.map { it.id },
                                                        mode = activeForwardSelectionMode.toForwardMessageSendMode(),
                                                        note = forwardNote
                                                    )
                                                when (val forwarded = repository.forwardMessages(request)) {
                                                    is DataResult.Content -> {
                                                        activeModal = null
                                                        forwardSelectedTarget = null
                                                        forwardNote = ""
                                                        clearShareSelection()
                                                        onThreadEvent(
                                                            "forwardMessagesSent",
                                                            forwardTarget.title,
                                                            null,
                                                            activeForwardSelectionMode.wireValue
                                                        )
                                                    }
                                                    is DataResult.Empty ->
                                                        onThreadEvent("forwardMessagesFailed", "No selected messages", null, null)
                                                    is DataResult.Error ->
                                                        onThreadEvent(
                                                            "forwardMessagesFailed",
                                                            forwarded.error.code.threadForwardErrorMessage(),
                                                            null,
                                                            null
                                                        )
                                                    is DataResult.NoPermission ->
                                                        onThreadEvent(
                                                            "forwardMessagesFailed",
                                                            forwarded.blocker.code.threadForwardErrorMessage(),
                                                            null,
                                                            null
                                                        )
                                                }
                                            }
                                            is DataResult.Empty ->
                                                onThreadEvent("forwardMessagesFailed", "Forward target unavailable", null, null)
                                            is DataResult.Error ->
                                                onThreadEvent("forwardMessagesFailed", resolved.error.code, null, null)
                                            is DataResult.NoPermission ->
                                                onThreadEvent("forwardMessagesFailed", resolved.blocker.code, null, null)
                                        }
                                    } finally {
                                        forwardSendingTargetId = null
                                    }
                                }
                            }
                        }
                    }
                )
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
    activityMuteState: ThreadActivityMuteUiState? = payload.threadActivityMuteState(null),
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    applyTopSafeArea: Boolean = true,
    onBack: () -> Unit,
    onOpenInChannel: () -> Unit,
    onOpenMemberProfile: (String, MemberKind?, String?) -> Unit,
    onOpenSearch: () -> Unit,
    onShowMembers: () -> Unit,
    onShowSettings: () -> Unit,
    onToggleActivityMute: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null
) {
    val topSafeArea = if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp
    val localizedHeaderTitle =
        if (
            headerDisplay.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
            headerDisplay.title == ThreadReplyPageTitle
        ) {
            stringResource(MR.strings.thread_reply_title)
        } else {
            headerDisplay.title
        }
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
                    onClick =
                        headerDisplay.dmPeerMemberId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { memberId -> { onOpenMemberProfile(memberId, null, null) } }
                )
            } else if (payload.showModeTabs) {
                ThreadChannelTile(headerDisplay.channelKind)
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(enabled = onTitleClick != null) { onTitleClick?.invoke() },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = localizedHeaderTitle,
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
            if (
                payload.threadHeaderSearchAction() != null ||
                (activityMuteState != null && onToggleActivityMute != null) ||
                payload.showSettingsAction ||
                payload.showMembersAction ||
                payload.showOpenInChannelAction
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ThreadHeaderActionGroupSpacing)
                ) {
                    if (payload.threadHeaderSearchAction() != null) {
                        ThreadHeaderIconButton(icon = SlockLucideIconName.Search, onClick = onOpenSearch)
                    }
                    if (activityMuteState != null && onToggleActivityMute != null) {
                        ThreadHeaderIconButton(
                            icon = if (activityMuteState.muted) SlockLucideIconName.BellOff else SlockLucideIconName.Bell,
                            onClick = onToggleActivityMute
                        )
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
            borderWidth = SlockStroke.Default
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
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    composerSendKeyEnabled: Boolean = true,
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
        originMessage = renderedPayload.originMessage,
        prioritizeThreadParticipants = renderedPayload.shouldPrioritizeThreadMentionParticipants(),
        memberSuggestionsFlow = memberSuggestionsFlow,
        channelSuggestionsFlow = channelSuggestionsFlow,
        agentStatusResolver = agentStatusResolver,
        onEvent = onEvent,
        insertMentionRequest = insertMentionRequest,
        onInsertMentionConsumed = onInsertMentionConsumed,
        composerSendKeyEnabled = composerSendKeyEnabled,
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
    originMessage: KuiklyThreadMessageItem?,
    prioritizeThreadParticipants: Boolean,
    memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    agentStatusResolver: SlockAgentStatusResolver,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    composerSendKeyEnabled: Boolean = true,
    onKeyboardHeightChange: (Float) -> Unit,
    contentPaddingTop: Dp,
    contentPaddingBottom: Dp
) {
    val memberSuggestionSlice by memberSuggestionsFlow.collectAsState(SharedMembersMemberSuggestionSlice())
    val channelSuggestionSlice by channelSuggestionsFlow.collectAsState(SharedMembersChannelSuggestionSlice())
    val loadedMemberSuggestions =
        remember(memberSuggestionSlice.suggestions, threadMessages, originMessage, prioritizeThreadParticipants) {
            val threadParticipantRanks =
                threadMentionParticipantRanks(
                    threadMessages = threadMessages,
                    originMessage = originMessage,
                    prioritizeThreadParticipants = prioritizeThreadParticipants
                )
            memberSuggestionSlice.suggestions
                .sortForThreadMentionParticipants(threadParticipantRanks)
                .map { suggestion ->
                    KuiklyComposerMemberItem(
                        displayName = suggestion.displayName,
                        handle = suggestion.handle,
                        inMentionChannel = suggestion.inMentionChannel,
                        inThread = suggestion.isThreadMentionParticipant(threadParticipantRanks),
                        sourceServerLabel = suggestion.sourceServerLabel,
                        kind = suggestion.kind,
                        description = suggestion.description,
                        avatarWireValue = suggestion.avatarWireValue,
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
        threadComposerRenderedSuggestions(
            loadedSuggestions = loadedMemberSuggestions,
            retainedSuggestions = retainedMemberSuggestions,
            fallbackSuggestions = state.memberSuggestions,
            status = memberSuggestionSlice.status
        )
    val renderedChannelSuggestions =
        threadComposerRenderedSuggestions(
            loadedSuggestions = loadedChannelSuggestions,
            retainedSuggestions = retainedChannelSuggestions,
            fallbackSuggestions = state.channelSuggestions,
            status = channelSuggestionSlice.status
        )
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
        agentStatusResolver = agentStatusResolver,
        insertMentionRequest = insertMentionRequest,
        onInsertMentionConsumed = onInsertMentionConsumed,
        composerSendKeyEnabled = composerSendKeyEnabled,
    )
}

internal fun KuiklyThreadScreenPayload.shouldPrioritizeThreadMentionParticipants(): Boolean =
    surfaceKind == KuiklyThreadSurfaceKind.DirectMessage ||
        parentChannelId.nonBlank() != null

internal fun List<SharedMembersMemberSuggestion>.sortForThreadMentionParticipants(
    threadMessages: List<KuiklyThreadMessageItem>,
    originMessage: KuiklyThreadMessageItem? = null,
    prioritizeThreadParticipants: Boolean
): List<SharedMembersMemberSuggestion> {
    val ranks =
        threadMentionParticipantRanks(
            threadMessages = threadMessages,
            originMessage = originMessage,
            prioritizeThreadParticipants = prioritizeThreadParticipants
        )
    return sortForThreadMentionParticipants(ranks)
}

private fun List<SharedMembersMemberSuggestion>.sortForThreadMentionParticipants(
    ranks: Map<String, Int>
): List<SharedMembersMemberSuggestion> {
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
    val inThread = filter { it.isThreadMentionParticipant(ranks) }.sortGroup()
    val inThreadKeys = inThread.mapTo(mutableSetOf()) { it.threadMentionSuggestionKey() }
    val inChannel =
        filter { suggestion -> suggestion.inMentionChannel && suggestion.threadMentionSuggestionKey() !in inThreadKeys }
            .sortGroup()
    val inChannelKeys = inChannel.mapTo(mutableSetOf()) { it.threadMentionSuggestionKey() }
    val notInChannel =
        filter { suggestion ->
            val key = suggestion.threadMentionSuggestionKey()
            key !in inThreadKeys && key !in inChannelKeys
        }.sortGroup()
    return inThread + inChannel + notInChannel
}

private fun threadMentionParticipantRanks(
    threadMessages: List<KuiklyThreadMessageItem>,
    originMessage: KuiklyThreadMessageItem?,
    prioritizeThreadParticipants: Boolean
): Map<String, Int> {
    if (!prioritizeThreadParticipants || (threadMessages.isEmpty() && originMessage == null)) return emptyMap()
    return (listOfNotNull(originMessage) + threadMessages).recentThreadParticipantRanks()
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
            ranks.putIfAbsent(handleKey, rank)
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

private fun SharedMembersMemberSuggestion.isThreadMentionParticipant(ranks: Map<String, Int>): Boolean =
    threadParticipantRank(ranks) != Int.MAX_VALUE

private fun SharedMembersMemberSuggestion.threadMentionSuggestionKey(): String =
    memberId?.trim()?.takeIf { it.isNotBlank() }
        ?: handle.trim().lowercase().takeIf { it.isNotBlank() }
        ?: displayName.trim().lowercase()

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

internal fun threadHeaderVisibleActionGapDp(): Float =
    (ThreadHeaderActionGroupSpacing + ThreadHeaderActionShadowOffset).value

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

internal fun threadHeaderScrollToTopEnabled(surfaceKind: String?, selectedTab: String?): Boolean =
    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        selectedTab?.trim()?.lowercase() == "chat"

internal fun threadHeaderScrollToTopTargetIndex(lazyItemCount: Int): Int? =
    if (lazyItemCount > 0) 0 else null

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
    inlineSearchVisible: Boolean = false,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    markdownTagContextSignature: String = "",
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    activeMarkdownTagKey: String? = null,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onMentionAuthor: (KuiklyThreadMessageItem) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
    shareSelectionActive: Boolean = false,
    shareSelectionCaptureActive: Boolean = false,
    shareSelectionCaptureAnchorMessageId: String? = null,
    headerScrollToTopRequest: Int = 0,
    selectedShareMessageIds: Set<String> = emptySet(),
    onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit = { _, _ -> },
    onEnterShareSelection: (String) -> Unit = {},
    onToggleShareSelection: (String) -> Unit = {},
    onOpenForwardBundle: (ForwardMessageBundle) -> Unit = {},
    translationStore: SharedMessageTranslationStore? = null,
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
                    inlineSearchVisible = inlineSearchVisible,
                    isMarkdownTagValid = isMarkdownTagValid,
                    markdownTagContextSignature = markdownTagContextSignature,
                    resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                    activeMarkdownTagKey = activeMarkdownTagKey,
                    onThreadEvent = onThreadEvent,
                    onOpenMember = onOpenMember,
                    onMentionAuthor = onMentionAuthor,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger,
                    shareSelectionActive = shareSelectionActive,
                    shareSelectionCaptureActive = shareSelectionCaptureActive,
                    shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                    headerScrollToTopRequest = headerScrollToTopRequest,
                    selectedShareMessageIds = selectedShareMessageIds,
                    onShareSelectionBounds = onShareSelectionBounds,
                    onEnterShareSelection = onEnterShareSelection,
                    onToggleShareSelection = onToggleShareSelection,
                    onOpenForwardBundle = onOpenForwardBundle,
                    translationStore = translationStore,
                    onThreadContentReady = onThreadContentReady
                )
            } else {
                ThreadStateMessage(stringResource(MR.strings.thread_state_loading), null)
            }
        "error" ->
            ThreadStateActionMessage(
                title = stringResource(MR.strings.thread_error_load),
                detail =
                    threadUserFacingErrorCodeLabel(renderConfig.errorCode)
                        ?: stringResource(MR.strings.common_error),
                actionLabel = stringResource(MR.strings.common_retry),
                onClick = { onThreadEvent("retryThreadLoad", null, null, null) }
            )
        "no_permission" ->
            ThreadStateMessage(
                stringResource(MR.strings.thread_state_no_permission),
                threadUserFacingErrorCodeLabel(renderConfig.errorCode)
                    ?: stringResource(MR.strings.thread_error_no_permission)
            )
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
                    inlineSearchVisible = inlineSearchVisible,
                    isMarkdownTagValid = isMarkdownTagValid,
                    markdownTagContextSignature = markdownTagContextSignature,
                    resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                    activeMarkdownTagKey = activeMarkdownTagKey,
                    onThreadEvent = onThreadEvent,
                    onOpenMember = onOpenMember,
                    onMentionAuthor = onMentionAuthor,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger,
                    shareSelectionActive = shareSelectionActive,
                    shareSelectionCaptureActive = shareSelectionCaptureActive,
                    shareSelectionCaptureAnchorMessageId = shareSelectionCaptureAnchorMessageId,
                    headerScrollToTopRequest = headerScrollToTopRequest,
                    selectedShareMessageIds = selectedShareMessageIds,
                    onShareSelectionBounds = onShareSelectionBounds,
                    onEnterShareSelection = onEnterShareSelection,
                    onToggleShareSelection = onToggleShareSelection,
                    onOpenForwardBundle = onOpenForwardBundle,
                    translationStore = translationStore,
                    onThreadContentReady = onThreadContentReady
                )
            } else {
                LaunchedEffect(threadInstanceKey, timelineSlice.status) {
                    onThreadContentReady()
                }
                ThreadStateMessage(
                    localizedThreadStateTitle(timelineSlice.emptyTitle),
                    localizedThreadStateDetail(timelineSlice.emptyDetail)
                )
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
                inlineSearchVisible = inlineSearchVisible,
                isMarkdownTagValid = isMarkdownTagValid,
                markdownTagContextSignature = markdownTagContextSignature,
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
                headerScrollToTopRequest = headerScrollToTopRequest,
                selectedShareMessageIds = selectedShareMessageIds,
                onShareSelectionBounds = onShareSelectionBounds,
                onEnterShareSelection = onEnterShareSelection,
                onToggleShareSelection = onToggleShareSelection,
                onOpenForwardBundle = onOpenForwardBundle,
                translationStore = translationStore,
                onThreadContentReady = onThreadContentReady
            )
    }
}

private data class ThreadMarkdownPreparsePlan(
    val key: String,
    val priorityCandidates: List<ThreadMarkdownPreparseCandidate>,
    val deferredCandidates: List<ThreadMarkdownPreparseCandidate>,
    val priorityParserCandidateCount: Int,
    val deferredParserCandidateCount: Int,
    val debugEntries: List<String> = emptyList()
) {
    val candidates: List<ThreadMarkdownPreparseCandidate> = priorityCandidates + deferredCandidates
    val parserCandidateCount: Int = priorityParserCandidateCount + deferredParserCandidateCount
}

private data class ThreadMarkdownVisiblePreparsePlan(
    val key: String,
    val candidates: List<ThreadMarkdownPreparseCandidate>
)

private data class ThreadMarkdownPreparseCandidate(
    val id: String,
    val body: String,
    val messageId: String? = id
)

private const val ThreadMarkdownPriorityPreparseMessageCount = 4
private const val ThreadMarkdownFocusedPreparseRadius = 25
private const val ThreadMarkdownInitialPreparseStartFrames = 2

private suspend fun awaitThreadMarkdownPreparseFrameSlot() {
    withFrameNanos { }
}

private suspend fun awaitThreadMarkdownInitialPreparseStartSlot() {
    repeat(ThreadMarkdownInitialPreparseStartFrames) {
        awaitThreadMarkdownPreparseFrameSlot()
    }
}

private fun List<ThreadMarkdownPreparseCandidate>.parserCandidateCount(): Int =
    mapNotNull { candidate -> candidate.body.preparedSlockMarkdownFullRendererContentOrNull() }
        .distinct()
        .size

private fun ThreadTimelineSlice.threadInitialMarkdownPreparsePlan(
    contentExtrasVisible: Boolean,
    expandedLongMessageIds: Set<String> = emptySet()
): ThreadMarkdownPreparsePlan {
    if (!contentExtrasVisible || (messages.isEmpty() && originMessage == null)) {
        return emptyThreadMarkdownPreparsePlan()
    }
    val orderedCandidates = threadMarkdownOrderedPreparseCandidates(expandedLongMessageIds)
    val parserCandidateCount = orderedCandidates.parserCandidateCount()
    if (parserCandidateCount == 0) {
        return emptyThreadMarkdownPreparsePlan()
    }
    val priorityCandidates = orderedCandidates.threadMarkdownPriorityPreparseCandidates()
    val priorityCandidateIds = priorityCandidates.mapTo(mutableSetOf()) { candidate -> candidate.id }
    val deferredCandidates = orderedCandidates.filterNot { candidate -> candidate.id in priorityCandidateIds }
    return ThreadMarkdownPreparsePlan(
        key =
            orderedCandidates.joinToString(separator = "|") { candidate ->
                "${candidate.id}:${candidate.body.length}:${candidate.body.hashCode()}"
            },
        priorityCandidates = priorityCandidates,
        deferredCandidates = deferredCandidates,
        priorityParserCandidateCount = priorityCandidates.parserCandidateCount(),
        deferredParserCandidateCount = deferredCandidates.parserCandidateCount(),
        debugEntries =
            orderedCandidates
                .take(ThreadMarkdownPreparseDebugEntryLimit)
                .map { candidate ->
                    "id=${candidate.id.take(15)} len=${candidate.body.length} hash=${candidate.body.hashCode().toString(16)}"
                }
    )
}

private fun emptyThreadMarkdownPreparsePlan(): ThreadMarkdownPreparsePlan =
    ThreadMarkdownPreparsePlan(
        key = "",
        priorityCandidates = emptyList(),
        deferredCandidates = emptyList(),
        priorityParserCandidateCount = 0,
        deferredParserCandidateCount = 0
    )

private fun ThreadTimelineSlice.threadMarkdownOrderedPreparseCandidates(
    expandedLongMessageIds: Set<String>
): List<ThreadMarkdownPreparseCandidate> {
    val messageCandidates =
        messages.map { message ->
            ThreadMarkdownPreparseCandidate(
                id = message.id,
                body = message.threadMarkdownPreparseBody(expandedLongMessageIds),
                messageId = message.id
            )
        }
    val originCandidate =
        originMessage?.let { origin ->
            ThreadMarkdownPreparseCandidate(
                id = "origin:${origin.id}",
                body = origin.threadMarkdownPreparseBody(expandedLongMessageIds),
                messageId = origin.id
            )
        }
    val focusedId = focusedMessageId?.takeIf { it.isNotBlank() }
    val focusedIndex = focusedId?.let { id -> messages.indexOfFirst { message -> message.id == id } } ?: -1
    val focusedMessageOrder =
        if (focusedIndex >= 0) {
            centeredThreadMessageIndexes(focusedIndex, messages.size, ThreadMarkdownFocusedPreparseRadius)
                .map { index -> messageCandidates[index] }
        } else {
            emptyList()
        }
    val latestMessageOrder = messageCandidates.asReversed()
    val ordered =
        buildList {
            if (focusedId != null && originCandidate?.messageId == focusedId) {
                add(originCandidate)
            }
            addAll(focusedMessageOrder)
            addAll(latestMessageOrder)
            if (originCandidate != null) {
                add(originCandidate)
            }
        }
    return ordered.distinctBy { candidate -> candidate.id }
}

private fun KuiklyThreadMessageItem.threadMarkdownPreparseBody(
    expandedLongMessageIds: Set<String>
): String =
    if (id in expandedLongMessageIds) {
        body
    } else {
        threadLongMessageRenderPlan(body).collapsedBody
    }

private fun List<ThreadMarkdownPreparseCandidate>.threadMarkdownPriorityPreparseCandidates(): List<ThreadMarkdownPreparseCandidate> {
    val originCandidate = firstOrNull { candidate -> candidate.id.startsWith("origin:") }
    val messageCandidates =
        filterNot { candidate -> candidate.id.startsWith("origin:") }
            .take(ThreadMarkdownPriorityPreparseMessageCount)
    return buildList {
        originCandidate?.let(::add)
        addAll(messageCandidates)
    }.distinctBy { candidate -> candidate.id }
}

private fun centeredThreadMessageIndexes(
    anchorIndex: Int,
    size: Int,
    radius: Int
): List<Int> {
    if (anchorIndex !in 0 until size || radius < 0) return emptyList()
    val indexes = mutableListOf(anchorIndex)
    for (offset in 1..radius) {
        val before = anchorIndex - offset
        if (before >= 0) indexes += before
        val after = anchorIndex + offset
        if (after < size) indexes += after
    }
    return indexes
}

internal fun ThreadTimelineSlice.initialMarkdownPreparsePriorityCandidateIds(
    contentExtrasVisible: Boolean
): List<String> =
    threadInitialMarkdownPreparsePlan(contentExtrasVisible).priorityCandidates.map { it.id }

internal fun ThreadTimelineSlice.initialMarkdownPreparseCandidateIds(
    contentExtrasVisible: Boolean
): List<String> =
    threadInitialMarkdownPreparsePlan(contentExtrasVisible).candidates.map { it.id }

internal fun ThreadTimelineSlice.visibleMarkdownPreparseCandidateIds(
    firstVisibleItemIndex: Int,
    lastVisibleItemIndex: Int,
    overscanItems: Int,
    contentExtrasVisible: Boolean
): List<String> =
    threadVisibleMarkdownPreparsePlan(
        firstVisibleItemIndex = firstVisibleItemIndex,
        lastVisibleItemIndex = lastVisibleItemIndex,
        overscanItems = overscanItems,
        contentExtrasVisible = contentExtrasVisible
    ).candidates.map { it.id }

private fun ThreadTimelineSlice.threadVisibleMarkdownPreparsePlan(
    firstVisibleItemIndex: Int,
    lastVisibleItemIndex: Int,
    overscanItems: Int,
    contentExtrasVisible: Boolean,
    expandedLongMessageIds: Set<String> = emptySet()
): ThreadMarkdownVisiblePreparsePlan {
    if (!contentExtrasVisible ||
        firstVisibleItemIndex < 0 ||
        lastVisibleItemIndex < firstVisibleItemIndex ||
        (messages.isEmpty() && originMessage == null)
    ) {
        return emptyThreadMarkdownVisiblePreparsePlan()
    }

    val expandedFirst = (firstVisibleItemIndex - overscanItems.coerceAtLeast(0)).coerceAtLeast(0)
    val expandedLast = lastVisibleItemIndex + overscanItems.coerceAtLeast(0)
    val candidates =
        buildList {
            if (hasThreadOriginItem() &&
                originMessage != null &&
                ThreadOriginListItemIndex in expandedFirst..expandedLast
            ) {
                add(
                    ThreadMarkdownPreparseCandidate(
                        id = "origin:${originMessage.id}",
                        body = originMessage.threadMarkdownPreparseBody(expandedLongMessageIds),
                        messageId = originMessage.id
                    )
                )
            }
            val firstMessageItemIndex = firstMessageListItemIndex()
            val firstMessageIndex = (expandedFirst - firstMessageItemIndex).coerceAtLeast(0)
            val lastMessageIndex = (expandedLast - firstMessageItemIndex).coerceAtMost(messages.lastIndex)
            if (firstMessageIndex <= lastMessageIndex) {
                for (index in firstMessageIndex..lastMessageIndex) {
                    val message = messages[index]
                    add(
                        ThreadMarkdownPreparseCandidate(
                            id = message.id,
                            body = message.threadMarkdownPreparseBody(expandedLongMessageIds),
                            messageId = message.id
                        )
                    )
                }
            }
        }
            .distinctBy { candidate -> candidate.id }
    if (candidates.isEmpty()) {
        return emptyThreadMarkdownVisiblePreparsePlan()
    }
    return ThreadMarkdownVisiblePreparsePlan(
        key =
            candidates.joinToString(separator = "|") { candidate ->
                "${candidate.id}:${candidate.body.length}:${candidate.body.hashCode()}"
            },
        candidates = candidates
    )
}

private fun emptyThreadMarkdownVisiblePreparsePlan(): ThreadMarkdownVisiblePreparsePlan =
    ThreadMarkdownVisiblePreparsePlan(key = "", candidates = emptyList())

private const val ThreadOriginListItemIndex = 0

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
    inlineSearchVisible: Boolean = false,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    markdownTagContextSignature: String = "",
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    activeMarkdownTagKey: String? = null,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onMentionAuthor: (KuiklyThreadMessageItem) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
    shareSelectionActive: Boolean = false,
    shareSelectionCaptureActive: Boolean = false,
    shareSelectionCaptureAnchorMessageId: String? = null,
    headerScrollToTopRequest: Int = 0,
    selectedShareMessageIds: Set<String> = emptySet(),
    onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit = { _, _ -> },
    onEnterShareSelection: (String) -> Unit = {},
    onToggleShareSelection: (String) -> Unit = {},
    onOpenForwardBundle: (ForwardMessageBundle) -> Unit = {},
    translationStore: SharedMessageTranslationStore? = null,
    onThreadContentReady: () -> Unit = {}
) {
    if (timelineSlice.messages.isEmpty() && timelineSlice.originStatus == "none") {
        LaunchedEffect(threadInstanceKey, timelineSlice.status) {
            onThreadContentReady()
        }
        if (timelineSlice.status == "loading") {
            ThreadStateMessage("Loading...", null)
        } else {
            ThreadStateMessage(
                localizedThreadStateTitle(timelineSlice.emptyTitle),
                localizedThreadStateDetail(timelineSlice.emptyDetail)
            )
        }
        return
    }
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
    val showReplyLoadingSkeleton = renderPayload.status == "loading" && renderPayload.messages.isEmpty()
    val showReplyEmptyState =
        renderPayload.messages.isEmpty() && renderPayload.originStatus != "none" && !showReplyLoadingSkeleton
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
                showReplyEmptyState = showReplyEmptyState || showReplyLoadingSkeleton,
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
    // task #569: long-press on a reaction chip shows who reacted.
    var reactionDetails by remember(threadInstanceKey) { mutableStateOf<ThreadReactionDetailsRequest?>(null) }
    var taskStatusMenuMessageId by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var taskStatusMenuAnchor by remember(threadInstanceKey) { mutableStateOf(Offset(16f, 16f)) }
    var allowBottomAutoAnchor by remember(threadInstanceKey) {
        mutableStateOf(true)
    }
    var expandedLongMessageIds by remember(threadInstanceKey) {
        mutableStateOf<Set<String>>(emptySet())
    }
    val longMessageItemHeights = remember(threadInstanceKey) { mutableMapOf<String, Int>() }
    val toggleLongMessage: (String, Int) -> Unit = { messageId, listItemIndex ->
        val collapsing = messageId in expandedLongMessageIds
        val firstVisibleItemIndex = listState.firstVisibleItemIndex
        val firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
        expandedLongMessageIds =
            threadExpandedLongMessageIdsAfterToggle(expandedLongMessageIds, messageId)
        if (
            collapsing &&
            firstVisibleItemIndex == listItemIndex &&
            firstVisibleItemScrollOffset > 0
        ) {
            coroutineScope.launch {
                listState.awaitThreadMessagesLayoutFrame()
                listState.awaitThreadMessagesLayoutFrame()
                val anchorOffset =
                    threadLongMessageCollapseAnchorOffset(
                        firstVisibleItemIndex = firstVisibleItemIndex,
                        toggledItemIndex = listItemIndex,
                        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
                        collapsedItemHeight = longMessageItemHeights[messageId] ?: 0
                    ) ?: return@launch
                if (!listState.isScrollInProgress && listState.canScrollToThreadItem(listItemIndex)) {
                    listState.scrollToItem(listItemIndex, anchorOffset)
                }
            }
        }
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
    // Focused-message anchoring is one-shot per explicit request (message id +
    // request nonce). Timeline growth (secondary refresh hydrating the origin,
    // realtime arrivals, older pages) re-runs the effect only to retry a target
    // that was not yet measurable — never to re-scroll a consumed request, and
    // never against the user: one user drag stamps the pending identity as
    // interrupted and retires it (task #554 — thread jumped back to the focused
    // message ~1s after entry, cancelling the active drag).
    var consumedFocusedMessageAnchorIdentity by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var scrollInterruptedFocusedMessageAnchorIdentity by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    // task #567 anchor session bookkeeping. Elapsed time accumulates across
    // effect restarts (structural data changes restart the anchor effect; the
    // hard cap must span restarts or a busy thread could hold the one-shot
    // open forever). Reset when a new anchor identity arrives (re-tap).
    var focusedAnchorSessionIdentity by remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var focusedAnchorSessionElapsedMs by remember(threadInstanceKey) { mutableStateOf(0L) }
    var focusedAnchorSessionReanchors by remember(threadInstanceKey) { mutableStateOf(0) }
    var bottomAnchorFocusedYieldElapsedMs by remember(threadInstanceKey) { mutableStateOf(0L) }
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
    var inlineSearchQuery by remember(threadInstanceKey) { mutableStateOf("") }
    var inlineSearchCurrentIndex by remember(threadInstanceKey) { mutableStateOf(0) }
    val inlineSearchMatches =
        remember(
            inlineSearchVisible,
            inlineSearchQuery,
            renderPayload.originStatus,
            renderPayload.originMessage?.id,
            renderPayload.originMessage?.body,
            renderPayload.messages
        ) {
            if (inlineSearchVisible) {
                renderPayload.threadInlineSearchMatches(inlineSearchQuery)
            } else {
                emptyList()
            }
        }
    LaunchedEffect(inlineSearchVisible) {
        if (!inlineSearchVisible) {
            inlineSearchQuery = ""
            inlineSearchCurrentIndex = 0
        }
    }
    LaunchedEffect(inlineSearchMatches.size, inlineSearchQuery) {
        inlineSearchCurrentIndex = inlineSearchCurrentIndex.coerceIn(0, (inlineSearchMatches.size - 1).coerceAtLeast(0))
    }
    val activeInlineSearchMatch =
        if (inlineSearchVisible && inlineSearchMatches.isNotEmpty()) {
            inlineSearchMatches[inlineSearchCurrentIndex.coerceIn(0, inlineSearchMatches.lastIndex)]
        } else {
            null
        }
    val activeInlineSearchMessageId = activeInlineSearchMatch?.messageId
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
        remember(
            timelineSlice.initialMarkdownPreparseCandidateSignature(contentExtrasVisible),
            focusedMessageTargetSignature,
            contentExtrasVisible,
            expandedLongMessageIds
        ) {
            renderPayload.threadInitialMarkdownPreparsePlan(
                contentExtrasVisible = contentExtrasVisible,
                expandedLongMessageIds = expandedLongMessageIds
            )
        }
    LaunchedEffect(threadInstanceKey, initialMarkdownPreparsePlan.key, focusedMessageTargetSignature) {
        if (initialMarkdownPreparsePlan.key.isEmpty()) {
            diagnosticsLogger(
                "thread_markdown_preparse_plan scope=loaded_latest_first result=empty " +
                    "messages=${renderPayload.messages.size} extrasVisible=$contentExtrasVisible"
            )
            return@LaunchedEffect
        }
        if (initialMarkdownPreparsePlan.priorityCandidates.isEmpty()) {
            return@LaunchedEffect
        }
        val startedAt = TimeSource.Monotonic.markNow()
        val result =
            prepareSlockMarkdownFullRendererBodies(
                bodies = initialMarkdownPreparsePlan.priorityCandidates.map { candidate -> candidate.body },
                maxBodies = initialMarkdownPreparsePlan.priorityCandidates.size
            )
        diagnosticsLogger(
            "thread_markdown_preparse scope=priority_anchor " +
                "bodyCandidates=${initialMarkdownPreparsePlan.priorityCandidates.size} " +
                "parserCandidates=${result.requestedCount} parsed=${result.parsedCount} " +
                "cacheHit=${result.cacheHitCount} messages=${renderPayload.messages.size} " +
                "focused=${renderPayload.focusedMessageId?.isNotBlank() == true} " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
    }
    LaunchedEffect(
        threadInstanceKey,
        initialMarkdownPreparsePlan.key,
        focusedMessageTargetSignature,
        initialBottomAnchorReady
    ) {
        if (initialMarkdownPreparsePlan.key.isEmpty() || initialMarkdownPreparsePlan.deferredCandidates.isEmpty()) {
            return@LaunchedEffect
        }
        if (!initialBottomAnchorReady) {
            diagnosticsLogger(
                "thread_markdown_preparse_plan scope=loaded_latest_first result=deferred " +
                    "messages=${renderPayload.messages.size} latestItemIndex=$latestItemIndex " +
                    "focused=${renderPayload.focusedMessageId?.isNotBlank() == true}"
            )
            return@LaunchedEffect
        }
        awaitThreadMarkdownInitialPreparseStartSlot()
        diagnosticsLogger(
            "thread_markdown_preparse_plan scope=loaded_latest_first " +
                "bodyCandidates=${initialMarkdownPreparsePlan.deferredCandidates.size} " +
                "parserCandidates=${initialMarkdownPreparsePlan.deferredParserCandidateCount} " +
                "priorityBodyCandidates=${initialMarkdownPreparsePlan.priorityCandidates.size} " +
                "batchSize=$ThreadInitialMarkdownPreparseMessageCount messages=${renderPayload.messages.size} " +
                "origin=${renderPayload.originMessage != null} " +
                "entriesShown=${initialMarkdownPreparsePlan.debugEntries.size} " +
                "entries=${initialMarkdownPreparsePlan.debugEntries.joinToString(separator = ";")}"
        )
        val startedAt = TimeSource.Monotonic.markNow()
        try {
            var parsedCount = 0
            var cacheHitCount = 0
            var requestedCount = 0
            val batches = initialMarkdownPreparsePlan.deferredCandidates.chunked(ThreadInitialMarkdownPreparseMessageCount)
            batches.forEachIndexed { index, batch ->
                if (index > 0) {
                    awaitThreadMarkdownPreparseFrameSlot()
                }
                val batchStartedAt = TimeSource.Monotonic.markNow()
                val result =
                    prepareSlockMarkdownFullRendererBodies(
                        bodies = batch.map { candidate -> candidate.body },
                        maxBodies = batch.size
                    )
                requestedCount += result.requestedCount
                parsedCount += result.parsedCount
                cacheHitCount += result.cacheHitCount
                val batchDurationMs = batchStartedAt.elapsedNow().inWholeMilliseconds
                diagnosticsLogger(
                    "thread_markdown_preparse_batch scope=loaded_latest_first " +
                        "batch=${index + 1}/${batches.size} bodyCandidates=${batch.size} " +
                        "parserCandidates=${result.requestedCount} parsed=${result.parsedCount} " +
                        "cacheHit=${result.cacheHitCount} messages=${renderPayload.messages.size} " +
                        "durationMs=$batchDurationMs"
                )
            }
            val durationMs = startedAt.elapsedNow().inWholeMilliseconds
            diagnosticsLogger(
                "thread_markdown_preparse scope=loaded_latest_first " +
                    "bodyCandidates=${initialMarkdownPreparsePlan.deferredCandidates.size} " +
                    "parserCandidates=${initialMarkdownPreparsePlan.deferredParserCandidateCount} " +
                    "priorityBodyCandidates=${initialMarkdownPreparsePlan.priorityCandidates.size} " +
                    "batchParserRequests=$requestedCount parsed=$parsedCount cacheHit=$cacheHitCount " +
                    "batches=${batches.size} " +
                    "messages=${renderPayload.messages.size} durationMs=$durationMs"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            diagnosticsLogger(
                "thread_markdown_preparse scope=loaded_latest_first result=failed " +
                    "messages=${renderPayload.messages.size} error=${error::class.simpleName}"
            )
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        initialMarkdownPreparsePlan.key,
        contentExtrasVisible,
        lazyItemCount,
        expandedLongMessageIds
    ) {
        if (!contentExtrasVisible || initialMarkdownPreparsePlan.key.isEmpty()) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                emptyThreadMarkdownVisiblePreparsePlan()
            } else {
                renderPayload.threadVisibleMarkdownPreparsePlan(
                    firstVisibleItemIndex = visibleItems.minOf { item -> item.index },
                    lastVisibleItemIndex = visibleItems.maxOf { item -> item.index },
                    overscanItems = threadMessagesBeyondBoundsItemCount(lazyItemCount),
                    contentExtrasVisible = contentExtrasVisible,
                    expandedLongMessageIds = expandedLongMessageIds
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
                    prepareSlockMarkdownFullRendererBodies(
                        bodies = visiblePreparsePlan.candidates.map { candidate -> candidate.body },
                        maxBodies = visiblePreparsePlan.candidates.size
                    )
                diagnosticsLogger(
                    "thread_markdown_preparse scope=visible_overscan " +
                        "bodyCandidates=${visiblePreparsePlan.candidates.size} " +
                        "parserCandidates=${result.requestedCount} parsed=${result.parsedCount} " +
                        "cacheHit=${result.cacheHitCount} messages=${renderPayload.messages.size} " +
                        "overscan=${threadMessagesBeyondBoundsItemCount(lazyItemCount)} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
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
    LaunchedEffect(threadInstanceKey, headerScrollToTopRequest, lazyItemCount) {
        val targetIndex = threadHeaderScrollToTopTargetIndex(lazyItemCount)
        if (headerScrollToTopRequest <= 0 || targetIndex == null) {
            return@LaunchedEffect
        }
        pendingFocusedMessageAnchorIdentityState.value?.let { pendingIdentity ->
            scrollInterruptedFocusedMessageAnchorIdentity = pendingIdentity
        }
        latestLocalSendBottomAnchorActiveKey = null
        pendingKeyboardBottomAnchor = false
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        userScrollGeneration += 1
        diagnosticsLogger(
            "thread_header_scroll_to_top request=$headerScrollToTopRequest " +
                "firstVisible=${listState.firstVisibleItemIndex} totalItems=$lazyItemCount"
        )
        listState.awaitThreadMessagesLayoutFrame()
        listState.animateScrollToTopExpressive()
    }
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
            ThreadBottomAutoAnchorAvailabilityState(
                currentAllow = allowBottomAutoAnchor,
                isScrollInProgress = listState.isScrollInProgress,
                isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(latestItemIndex),
                focusedAnchorOwnsViewport =
                    focusedMessageAnchorOwnsViewport(
                        pendingIdentity = pendingFocusedMessageAnchorIdentityState.value,
                        consumedIdentity = consumedFocusedMessageAnchorIdentity,
                        scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                        yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
                    )
            )
        }
            .distinctUntilChanged()
            .collect { state ->
                val nextAllow = resolveThreadBottomAutoAnchorAllowed(state)
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
                isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(latestItemIndex),
                focusedAnchorOwnsViewport =
                    focusedMessageAnchorOwnsViewport(
                        pendingIdentity = pendingFocusedMessageAnchorIdentityState.value,
                        consumedIdentity = consumedFocusedMessageAnchorIdentity,
                        scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                        yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
                    )
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
            // Restoring the anchor cancels an active drag; a dragging user is
            // already re-positioning themselves, so let the gesture win and
            // skip the programmatic restore (task #554 pattern scan).
            if (anchor != null && !listState.isScrollInProgress) {
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
        renderPayload.focusedMessageRequestNonce,
        focusedMessageTargetSignature,
        renderPayload.originStatus,
        lazyItemCount
    ) {
        val targetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        // task #567: the origin/parent row above the timeline resolves
        // asynchronously; anchoring against its loading placeholder measures a
        // viewport that then shifts when the resolved row grows. Wait for a
        // terminal origin status — the effect re-fires on the key change.
        if (!focusedMessageAnchorOriginSettled(renderPayload.originStatus)) {
            return@LaunchedEffect
        }
        val targetIdentity =
            focusedMessageAnchorIdentity(
                messageId = targetMessageId,
                requestNonce = renderPayload.focusedMessageRequestNonce
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
        if (!listState.isThreadItemVisible(targetIndex)) {
            listState.scrollToItem(targetIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
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
            val currentOffset = listState.focusedAnchorTargetOffset(targetIndex)
            val action =
                focusedAnchorSessionAction(
                    userInteracting = listState.isScrollInProgress,
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
                            "elapsedMs=$focusedAnchorSessionElapsedMs messageId=$targetMessageId"
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
                    listState.scrollThreadItemToFocusedViewport(targetIndex)
                    anchoredOffset = listState.focusedAnchorTargetOffset(targetIndex)
                    quietMs = 0L
                }
                FocusedAnchorSessionAction.Continue -> {
                    quietMs += FocusedAnchorSessionRecheckIntervalMs
                }
            }
        }
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
        listState.scrollToItem(match.listItemIndex)
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
        latestItemIndex
    ) {
        if (latestItemIndex < 0) return@LaunchedEffect
        val focusedMessageTargetIndex =
            renderPayload.focusedMessageListItemIndex()
        if (focusedMessageTargetIndex >= 0) {
            return@LaunchedEffect
        }
        // task #567: while a focused-message anchor request is pending, the
        // bottom anchor must not touch the viewport at all — it was the second
        // scroll actor racing the focused anchor on cold entry
        // (anchorInitialThreadBottomToLatest is a multi-step suspend that can
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
        // A drag during the initial-anchor window wins: reveal the list where
        // the user already is instead of yanking to the bottom (task #554
        // pattern scan — entry-window race).
        if (!initialBottomAnchorReady && listState.isScrollInProgress) {
            initialBottomAnchorReady = true
            return@LaunchedEffect
        }
        if (!initialBottomAnchorReady && !listState.isThreadItemVisible(latestItemIndex)) {
            listState.scrollToItem(latestItemIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
            return@LaunchedEffect
        }
        if (!initialBottomAnchorReady) {
            while (!initialBottomAnchorReady) {
                if (listState.isScrollInProgress) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
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
    ThreadReactionAtlasProvider {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .touchListener { type, _ ->
                        if (type == TouchType.Move) {
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
                                userScrollGeneration += 1
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
                verticalArrangement = Arrangement.Bottom,
                beyondBoundsItemCount = threadMessagesBeyondBoundsItemCount(lazyItemCount)
            ) {
            if (renderPayload.hasThreadOriginItem()) {
                item(key = ThreadOriginListItemKey) {
                    ThreadOriginCard(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    renderPayload.originMessage?.id?.let { messageId ->
                                        longMessageItemHeights[messageId] = coordinates.size.height
                                    }
                                },
                        renderConfig = renderConfig,
                        timelineSlice = renderPayload,
                        focusedMessageId = activeFocusedMessageId ?: activeInlineSearchMessageId,
                        linkedTasksProjection = linkedTasksProjection,
                        highlightedMentions = highlightedMentions,
                        pendingActionCardIds = pendingActionCardIds,
                        attachmentOpenStates = attachmentOpenStates,
                        attachmentCommentsEnabled = attachmentCommentsEnabled,
                        currentServerSlug = currentServerSlug,
                        contentExtrasVisible = contentExtrasVisible,
                        isMarkdownTagValid = isMarkdownTagValid,
                        markdownTagContextSignature = markdownTagContextSignature,
                        resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                        activeMarkdownTagKey = activeMarkdownTagKey,
                        diagnosticsLogger = diagnosticsLogger,
                        onThreadEvent = onThreadEvent,
                        onOpenMember = onOpenMember,
                        onShowReactionDetails = { reaction, anchor ->
                            reactionDetails =
                                ThreadReactionDetailsRequest(
                                    emoji = reaction.emoji,
                                    names = reaction.reactorNames,
                                    count = reaction.count,
                                    anchor = anchor
                                )
                        },
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
                        onToggleShareSelection = onToggleShareSelection,
                        longMessageExpanded = renderPayload.originMessage?.id in expandedLongMessageIds,
                        onToggleLongMessage = { messageId ->
                            toggleLongMessage(messageId, ThreadOriginListItemIndex)
                        }
                    )
                }
            }
            if (renderPayload.hasThreadHistoryBoundaryItem()) {
                item(key = ThreadOlderMessagesListItemKey) {
                    when {
                        renderPayload.canLoadMoreBefore && renderPayload.olderMessagesStatus == "error" ->
                            ThreadInlineActionRow(
                                label = stringResource(MR.strings.thread_retry_older),
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
                    ThreadStateMessage(
                        localizedThreadStateTitle(renderPayload.emptyTitle),
                        localizedThreadStateDetail(renderPayload.emptyDetail)
                    )
                }
            }
            if (showReplyLoadingSkeleton) {
                item(key = ThreadReplyLoadingListItemKey) {
                    ThreadStateMessage("Loading...", null)
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
                val dateDividerLabel =
                    remember(
                        previousMessage?.timestampLabel,
                        message.timestampLabel,
                        renderConfig.timeContext.localOffsetMinutes,
                        renderConfig.timeContext.todayEpochDay,
                        renderConfig.timeContext.nowEpochMillis
                    ) {
                        threadDateDividerLabel(
                            previous = previousMessage,
                            next = message,
                            timeContext = renderConfig.timeContext
                        )
                    }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                longMessageItemHeights[message.id] = coordinates.size.height
                            }
                ) {
                    dateDividerLabel?.let { label ->
                        ThreadDateDivider(label)
                    }
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
                            val translationUi =
                                if (translationStore != null) {
                                    resolveThreadMessageTranslationUi(displayMessage, translationState)
                                } else {
                                    null
                                }
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
                                focused = activeFocusedMessageId == message.id || activeInlineSearchMessageId == message.id,
                                shareSelectionActive = shareSelectionActive,
                                selectedForShare = message.id in selectedShareMessageIds,
                                onToggleShareSelection = { onToggleShareSelection(message.id) },
                                showSaveAction = !captureShareBounds,
                                showInlineSaveAction = renderPayload.showInlineSaveAction,
                                isMessageListScrolling = listState.isScrollInProgress,
                                alignOwnMessageRight = renderPayload.alignOwnMessagesRight && message.isOwn,
                                compactConsecutiveMessages = renderPayload.compactConsecutiveMessages,
                                suppressRepeatedAuthor = suppressRepeatedAuthor,
                                showReplyAction = renderPayload.showReplyActions,
                                resolvedTaskAssigneeName = linkedTaskAssigneeName,
                                resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                                markdownTagContextSignature = markdownTagContextSignature,
                                activeMarkdownTagKey = activeMarkdownTagKey,
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
                                onShowReactionDetails = { reaction, anchor ->
                                    reactionDetails =
                                        ThreadReactionDetailsRequest(
                                            emoji = reaction.emoji,
                                            names = reaction.reactorNames,
                                            count = reaction.count,
                                            anchor = anchor
                                        )
                                },
                                showReactionAddAction = renderPayload.showReactionActions && !message.hasLocalSendState(),
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
                                richContentEnabled = isRichContentEnabled,
                                translation = translationUi,
                                onToggleTranslationOriginal = {
                                    translationStore?.setShowOriginal(
                                        message.id,
                                        !(translationUi?.showOriginal ?: false)
                                    )
                                },
                                onRetryTranslation = {
                                    translationStore?.retryTranslation(displayMessage.toTranslationCandidate())
                                },
                                onOpenForwardBundle = onOpenForwardBundle,
                                longMessageCollapseEnabled = !shareSelectionCaptureActive,
                                longMessageExpanded = message.id in expandedLongMessageIds,
                                onToggleLongMessage = {
                                    toggleLongMessage(
                                        message.id,
                                        renderPayload.threadMessageListItemIndex(message.id)
                                    )
                                }
                            )
                        }
                    }
                }
            }
            if (renderPayload.canLoadMoreAfter) {
                item(key = ThreadNewerMessagesListItemKey) {
                    val actionSpec = threadNewerMessagesActionSpec(renderPayload.newerMessagesStatus)
                    ThreadInlineActionRow(
                        label = actionSpec.label.localized(),
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
                        .height(
                            ThreadBottomScrollPaddingHeight +
                                if (inlineSearchVisible) ThreadInlineSearchBarBottomInset else 0.dp
                        )
                )
            }
        }
        if (inlineSearchVisible) {
            ThreadInlineSearchBar(
                query = inlineSearchQuery,
                matchCount = inlineSearchMatches.size,
                currentIndex = inlineSearchCurrentIndex,
                onQueryChange = {
                    inlineSearchQuery = it
                    inlineSearchCurrentIndex = 0
                },
                onPrevious = {
                    if (inlineSearchMatches.isNotEmpty()) {
                        inlineSearchCurrentIndex =
                            if (inlineSearchCurrentIndex <= 0) inlineSearchMatches.lastIndex else inlineSearchCurrentIndex - 1
                    }
                },
                onNext = {
                    if (inlineSearchMatches.isNotEmpty()) {
                        inlineSearchCurrentIndex =
                            if (inlineSearchCurrentIndex >= inlineSearchMatches.lastIndex) 0 else inlineSearchCurrentIndex + 1
                    }
                },
                onClear = {
                    inlineSearchQuery = ""
                    inlineSearchCurrentIndex = 0
                },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .zIndex(ThreadInlineSearchBarZIndex)
            )
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
        reactionDetails?.let { details ->
            ThreadReactionDetailsTooltip(
                details = details,
                onDismiss = { reactionDetails = null }
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
                    showReactionActions = renderPayload.showReactionActions && !message.hasLocalSendState(),
                    showCopyLinkAction = !message.hasLocalSendState(),
                    showShareMessagesAction = !message.hasLocalSendState(),
                    showCancelAction = isSendingLocalSend,
                    showDeleteAction = isFailedLocalSend,
                    showTranslateAction =
                        translationStore != null && message.isTranslationEligible(translationState.settings),
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
                    onTranslate = {
                        actionMessageId = null
                        translationStore?.translateNow(message.toTranslationCandidate())
                    },
                    diagnosticsLogger = diagnosticsLogger
                )
            }
        }
        taskStatusMenuMessageId?.let { messageId ->
            renderPayload.threadActionMessage(messageId, linkedTasksProjection)?.let { message ->
                val selectedStatus = message.taskStatus.threadTaskStatusOption()
                SlockTaskStatusMenuOverlay(
                    anchor = taskStatusMenuAnchor,
                    items = ThreadTaskStatusMenuItems,
                    selectedId = selectedStatus.key,
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
}

@Composable
private fun ThreadInlineSearchBar(
    query: String,
    matchCount: Int,
    currentIndex: Int,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val normalizedCurrent = if (matchCount > 0) currentIndex.coerceIn(0, matchCount - 1) + 1 else 0
    Column(modifier = modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            SharedHomeSearchInput(
                query = query,
                loading = false,
                interactive = true,
                onQueryChange = onQueryChange,
                onSubmit = {},
                onClear = onClear,
                autoFocus = true,
                placeholder = stringResource(MR.strings.thread_placeholder_search),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .height(ThreadHeaderActionSize)
                        .widthIn(min = 40.dp)
                        .background(SlockColor.SurfaceWhite)
                        .padding(horizontal = SlockSpacing.Xs),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$normalizedCurrent/$matchCount",
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ThreadHeaderIconButton(icon = SlockLucideIconName.ChevronUp, onClick = onPrevious)
            ThreadHeaderIconButton(icon = SlockLucideIconName.ChevronDown, onClick = onNext)
        }
    }
}

private const val ThreadOlderMessagesListItemKey = "thread-page:older-messages"
private const val ThreadNewerMessagesListItemKey = "thread-page:newer-messages"
private const val ThreadOriginListItemKey = "thread-page:origin"
private const val ThreadReplyEmptyListItemKey = "thread-page:reply-empty"
private const val ThreadReplyLoadingListItemKey = "thread-page:reply-loading"
private const val ThreadBottomScrollPaddingListItemKey = "thread-page:bottom-scroll-padding"
private val ThreadBottomScrollPaddingHeight = 16.dp
private val ThreadInlineSearchBarBottomInset = 58.dp
private const val ThreadInlineSearchBarZIndex = 2f
internal data class ThreadInlineActionSpec(
    val label: StringDesc,
    val event: String,
    val leadingIcon: SlockLucideIconName? = null
)

internal data class ThreadHistoryStatusSpec(
    val label: StringDesc,
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

internal const val ThreadNestedForwardUnsupportedMessage =
    "Forwarded messages can't be forwarded. Select the original message instead."
internal const val ThreadUnsupportedSourceMessage =
    "Tasks, system messages, and action messages can't be forwarded. Select an ordinary message instead."

internal data class ThreadForwardMessageSelection(
    val forwardableMessages: List<KuiklyThreadMessageItem>,
    val skippedCount: Int,
    val nestedForwardCount: Int
)

internal fun List<KuiklyThreadMessageItem>.threadForwardMessageSelection(): ThreadForwardMessageSelection {
    val forwardableMessages = filter { message -> message.isForwardableOrdinaryChatMessage() }
    return ThreadForwardMessageSelection(
        forwardableMessages = forwardableMessages,
        skippedCount = size - forwardableMessages.size,
        nestedForwardCount = count { message -> message.forwardBundle != null }
    )
}

private fun KuiklyThreadMessageItem.isForwardableOrdinaryChatMessage(): Boolean =
    messageType.equals("Chat", ignoreCase = true) &&
        deliveryState == ThreadMessageDeliverySent &&
        forwardBundle == null &&
        taskId == null &&
        taskNumber == null &&
        taskStatus == null &&
        metadata["kind"] != "action-card"

internal fun String.threadForwardErrorMessage(): String =
    when (trim().lowercase()) {
        "unsupport",
        "unsupported",
        "forwarded_source_not_supported" -> ThreadNestedForwardUnsupportedMessage
        "unsupported_source_message" -> ThreadUnsupportedSourceMessage
        else -> this
    }

internal fun ThreadForwardMessageSelection.threadForwardSelectionFailureMessage(): String =
    if (nestedForwardCount == skippedCount) {
        ThreadNestedForwardUnsupportedMessage
    } else {
        ThreadUnsupportedSourceMessage
    }

internal fun threadForwardSkippedWarning(
    skippedCount: Int,
    nestedForwardCount: Int
): String? {
    if (skippedCount <= 0) return null
    val skippedLabel = if (skippedCount == 1) "1 selected message was" else "$skippedCount selected messages were"
    return if (nestedForwardCount == skippedCount) {
        "$skippedLabel skipped because forwarded messages can't be forwarded again."
    } else {
        "$skippedLabel skipped. Forwarded bundles, tasks, system messages, and action messages can't be forwarded."
    }
}

private fun ThreadTimelineSlice.threadSelectableShareMessageIds(): Set<String> =
    (listOfNotNull(originMessage) + messages)
        .filterNot { it.isSystemMessage() }
        .map { it.id }
        .toSet()

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
        ThreadHistoryStatusSpec(label = MR.strings.thread_state_loading_older.desc())
    } else {
        ThreadHistoryStatusSpec(label = MR.strings.thread_state_beginning_conversation.desc(), secondaryLabel = secondaryLabel)
    }

internal fun threadNewerMessagesActionSpec(newerMessagesStatus: String): ThreadInlineActionSpec =
    when (newerMessagesStatus) {
        "loading" -> ThreadInlineActionSpec(label = MR.strings.thread_state_loading_new.desc(), event = "loadNewer")
        "error" -> ThreadInlineActionSpec(label = MR.strings.thread_retry_new.desc(), event = "retryLoadNewer")
        else -> ThreadInlineActionSpec(label = MR.strings.thread_load_new.desc(), event = "loadNewer")
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

internal data class ThreadMessageMarkdownRenderSignature(
    val messageId: String,
    val bodyLength: Int,
    val bodyHash: Int
) {
    val inlineContentScope: String =
        "$messageId:$bodyLength:${bodyHash.toString(16)}"
}

internal fun KuiklyThreadMessageItem.threadMarkdownRenderSignature(): ThreadMessageMarkdownRenderSignature =
    ThreadMessageMarkdownRenderSignature(
        messageId = id,
        bodyLength = body.length,
        bodyHash = body.hashCode()
    )

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

// A focused-message anchor request is identified by (message id, request
// nonce) ONLY. Timeline signature / item count deliberately do not participate:
// they change on secondary refresh, realtime arrivals, and older pages, and a
// consumed request must not re-scroll on any of those (task #554). They still
// key the LaunchedEffect so an unmeasured target gets retried as items load.
internal fun focusedMessageAnchorIdentity(
    messageId: String,
    requestNonce: Int
): String =
    "${messageId.trim()}:$requestNonce"

internal fun shouldRunFocusedMessageAnchor(
    targetIdentity: String,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?
): Boolean =
    targetIdentity != consumedIdentity && targetIdentity != scrollInterruptedIdentity

// task #567 (Quiver cddfcd85): on reply-thread surfaces the origin/parent row
// starts as a "loading" placeholder and is replaced by the resolved parent
// message after an async fetch. A focused anchor consumed during "loading"
// positions the viewport against pre-resolution content and the one-shot
// never re-runs — first entry lands a few items above the target, while a
// second entry reads the parent from the store cache (origin already
// "loaded"/"missing" before the anchor) and is correct. Every terminal status
// ("none" on channel surfaces, "loaded", "missing", errors) is settled; only
// the transient "loading" holds the anchor back.
internal fun focusedMessageAnchorOriginSettled(originStatus: String): Boolean =
    originStatus != "loading"

// task #567 anchor-session tuning. Rationale, so future re-tuning needs no
// archaeology:
// - Recheck 250ms: a couple of frames past the layout-stability delay — fast
//   enough to correct drift before a user reads it as "wrong position",
//   cheap enough to poll for at most the cap.
// - Quiet 500ms: two consecutive clean rechecks; entry content that has not
//   moved for half a second has finished its late measurements (origin row,
//   image dimensions, markdown) in every trace we have.
// - Hard cap 1500ms: upper bound so a busy thread (typing indicators,
//   realtime arrivals restarting the effect) cannot hold the one-shot open
//   forever; covers the longest first-entry settle observed in the cddfcd85
//   device log (~1.4s from first probe to settled probe).
// - Bottom-yield cap 3000ms: how long the initial bottom anchor stays out of
//   the focused anchor's way before concluding the target will never become
//   anchorable and falling back — 2× the session cap plus fetch slack.
// - Drift tolerance 4px: item offsets are ints and re-layout rounding can
//   jitter by a pixel or two; anything beyond this is a real shift.
internal const val FocusedAnchorSessionRecheckIntervalMs = 250L
internal const val FocusedAnchorSessionQuietMs = 500L
internal const val FocusedAnchorSessionHardCapMs = 1_500L
internal const val FocusedAnchorBottomYieldCapMs = 3_000L
internal const val FocusedAnchorDriftTolerancePx = 4

internal enum class FocusedAnchorSessionAction { Interrupt, Consume, Reanchor, Continue }

// The session policy as a pure state machine (task #567). Precedence:
// user interaction always wins (#554 semantics), then the hard cap (never
// hold the one-shot open forever), then drift correction, then quiet
// consumption.
internal fun focusedAnchorSessionAction(
    userInteracting: Boolean,
    drifted: Boolean,
    quietMs: Long,
    elapsedMs: Long
): FocusedAnchorSessionAction =
    when {
        userInteracting -> FocusedAnchorSessionAction.Interrupt
        elapsedMs >= FocusedAnchorSessionHardCapMs -> FocusedAnchorSessionAction.Consume
        drifted -> FocusedAnchorSessionAction.Reanchor
        quietMs >= FocusedAnchorSessionQuietMs -> FocusedAnchorSessionAction.Consume
        else -> FocusedAnchorSessionAction.Continue
    }

// Drift predicate: the target leaving the viewport entirely is always drift;
// otherwise compare against the offset captured right after anchoring.
internal fun focusedAnchorTargetDrifted(anchoredOffset: Int?, currentOffset: Int?): Boolean =
    when {
        currentOffset == null -> true
        anchoredOffset == null -> false
        else -> {
            val delta = currentOffset - anchoredOffset
            delta > FocusedAnchorDriftTolerancePx || delta < -FocusedAnchorDriftTolerancePx
        }
    }

// Marker magnitude: -1 encodes "target left the viewport" so the log line
// stays greppable next to pixel deltas.
internal fun focusedAnchorDriftMagnitude(anchoredOffset: Int?, currentOffset: Int?): Int =
    if (anchoredOffset != null && currentOffset != null) {
        val delta = currentOffset - anchoredOffset
        if (delta >= 0) delta else -delta
    } else {
        -1
    }

// task #567: the initial bottom anchor yields the viewport to a pending
// focused anchor (bounded by FocusedAnchorBottomYieldCapMs).
internal fun shouldYieldBottomAnchorToFocusedAnchor(
    pendingIdentity: String?,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?,
    yieldElapsedMs: Long
): Boolean =
    pendingIdentity != null &&
        shouldRunFocusedMessageAnchor(
            targetIdentity = pendingIdentity,
            consumedIdentity = consumedIdentity,
            scrollInterruptedIdentity = scrollInterruptedIdentity
        ) &&
        yieldElapsedMs < FocusedAnchorBottomYieldCapMs

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

internal fun focusedMessageAnchorOwnsViewport(
    pendingIdentity: String?,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?,
    yieldElapsedMs: Long
): Boolean =
    shouldYieldBottomAnchorToFocusedAnchor(
        pendingIdentity = pendingIdentity,
        consumedIdentity = consumedIdentity,
        scrollInterruptedIdentity = scrollInterruptedIdentity,
        yieldElapsedMs = yieldElapsedMs
    )

internal data class ThreadBottomAutoAnchorAvailabilityState(
    val currentAllow: Boolean,
    val isScrollInProgress: Boolean,
    val isAtRenderedBottom: Boolean,
    val focusedAnchorOwnsViewport: Boolean
)

internal fun resolveThreadBottomAutoAnchorAllowed(
    state: ThreadBottomAutoAnchorAvailabilityState
): Boolean =
    when {
        state.focusedAnchorOwnsViewport -> false
        state.isScrollInProgress && !state.isAtRenderedBottom -> false
        state.isAtRenderedBottom -> true
        else -> state.currentAllow
    }

internal data class ThreadBottomAnchorCorrectionState(
    val allowBottomAutoAnchor: Boolean,
    val isScrollInProgress: Boolean,
    val isAtRenderedBottom: Boolean,
    val focusedAnchorOwnsViewport: Boolean = false
)

internal fun shouldAutoCorrectThreadBottomAnchor(state: ThreadBottomAnchorCorrectionState): Boolean =
    state.allowBottomAutoAnchor &&
        !state.focusedAnchorOwnsViewport &&
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
    return shouldThreadAutoLoadOlderMessages(
        boundaryIndex = boundaryIndex,
        firstVisibleIndex = firstVisibleIndex
    )
}

internal fun shouldThreadAutoLoadOlderMessages(boundaryIndex: Int, firstVisibleIndex: Int): Boolean {
    if (boundaryIndex < 0 || firstVisibleIndex < 0) return false
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

// task #567: the target's current offset inside the viewport, or null when it
// is not among the visible items (= fully drifted).
private fun LazyListState.focusedAnchorTargetOffset(targetIndex: Int): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.offset

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
    return itemSize - viewportHeight
}

internal fun threadFocusedItemScrollOffset(
    itemSize: Int,
    viewportHeight: Int
): Int {
    return 0
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
        label = stringResource(MR.strings.thread_action_back_to_bottom),
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
private const val ThreadMarkdownTagActiveFeedbackMs = 900L
private const val ThreadFirstContentTaskJoinDelayMs = 96L
private const val ThreadBackgroundTasksJoinDelayMs = 360L
private const val ThreadLayoutStabilityMaxFrames = 30
private const val ThreadLayoutStabilityRequiredFrames = 3
private const val ThreadBackToBottomAnchorAttempts = 12
private const val ThreadKeyboardBottomAnchorAttempts = 8
private const val ThreadOlderAutoLoadVisibleItemThreshold = 8
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
                text = spec.label.localized(),
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
private fun ThreadDateDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.weight(1f).height(SlockStroke.Thin).background(SlockColor.TextMutedGray.copy(alpha = 0.35f)))
        Text(
            text = label,
            style = threadDateDividerTextStyle(),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(Modifier.weight(1f).height(SlockStroke.Thin).background(SlockColor.TextMutedGray.copy(alpha = 0.35f)))
    }
}

@Composable
private fun ThreadLongMessageToggle(
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = stringResource(if (expanded) MR.strings.thread_show_less else MR.strings.thread_show_more),
            style =
                SlockType.Caption
                    .copy(fontSize = 11.sp, lineHeight = 15.sp, color = SlockColor.TextBlack)
                    .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        SlockLucideIcon(
            if (expanded) SlockLucideIconName.ChevronUp else SlockLucideIconName.ChevronDown,
            modifier = Modifier.size(12.dp),
            color = "#FF000000"
        )
    }
}

internal fun threadDateDividerTextStyle(): TextStyle =
    SlockType.Caption.copy(
        fontFamily = slockMonoFamilyForWeight(FontWeight.Bold),
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = SlockColor.TextMutedGray,
        letterSpacing = 0.sp
    )

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
    markdownTagContextSignature: String = "",
    taskOpenEnabled: Boolean = true,
    taskStatusActionsEnabled: Boolean = true,
    showSaveAction: Boolean = true,
    showInlineSaveAction: Boolean = false,
    showReactionAddAction: Boolean = false,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    activeMarkdownTagKey: String? = null,
    timestampLabel: String = message.timestampLabel,
    compactTimestampLabel: String = timestampLabel,
    onShowActions: (Offset) -> Unit,
    onOpenMember: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
    onToggleReaction: (String) -> Unit,
    onShowReactionDetails: ((KuiklyThreadReactionItem, Offset) -> Unit)? = null,
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
    onMentionAuthor: (KuiklyThreadMessageItem) -> Unit = {},
    richContentEnabled: Boolean = true,
    focused: Boolean = false,
    isMessageListScrolling: Boolean = false,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    captureLongPressAnchor: Boolean = true,
    shareSelectionActive: Boolean = false,
    selectedForShare: Boolean = false,
    onToggleShareSelection: () -> Unit = {},
    translation: ThreadMessageTranslationUi? = null,
    onToggleTranslationOriginal: () -> Unit = {},
    onRetryTranslation: () -> Unit = {},
    onOpenForwardBundle: (ForwardMessageBundle) -> Unit = {},
    longMessageCollapseEnabled: Boolean = false,
    longMessageExpanded: Boolean = false,
    onToggleLongMessage: () -> Unit = {},
    outerPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Sm
) {
    val localizedAuthorName =
        if (message.isOwn && message.authorName == "You") {
            stringResource(MR.strings.thread_author_you)
        } else {
            message.authorName
        }
    val localizedTimestampLabel = localizedThreadTimestampLabel(timestampLabel)
    val localizedCompactTimestampLabel = localizedThreadTimestampLabel(compactTimestampLabel)
    val horizontalArrangement =
        if (alignOwnMessageRight) Arrangement.spacedBy(12.dp, Alignment.End) else Arrangement.spacedBy(12.dp)
    val contentAlignment = if (alignOwnMessageRight) Alignment.End else Alignment.Start
    val messageMaxWidthFraction = if (alignOwnMessageRight) 0.82f else 1f
    val agentStatus =
        if (message.showsAgentStatusIndicator()) {
            rememberSlockAgentStatusIndicatorState(agentStatusResolver, message.authorId)
                ?: message.threadAuthorAgentStatusIndicatorState()
        } else {
            null
        }
    val avatarWireValue =
        rememberSlockMemberAvatarWireValue(memberAvatarResolver, message.authorId)
            ?: message.resolvedThreadAvatarWireValue()
    val authorBadgeLabel = message.threadAuthorBadgeLabel()
    val focusAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = ThreadFocusedMessageHighlightFadeMs),
        label = "thread-message-focus-highlight"
    )
    var pressed by remember(message.id) { mutableStateOf(false) }
    var pressRippleOrigin by remember(message.id) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(message.id, pressed) {
        if (!pressed && pressRippleOrigin != null) {
            delay(ThreadMessageRippleFadeMs.toLong() + ThreadMessageRippleResetMarginMs)
            pressRippleOrigin = null
        }
    }
    val showAuthorChrome = !compactConsecutiveMessages || !suppressRepeatedAuthor
    val showTimestampInHeader = !compactConsecutiveMessages || showAuthorChrome
    val showTimestampInAvatarSlot =
        compactConsecutiveMessages && !showAuthorChrome && pressed && compactTimestampLabel.isNotBlank()
    val hasThreadAttachments = richContentEnabled && message.attachments.isNotEmpty()
    val showSavedBadge =
        shouldShowThreadMessageSavedBadge(
            showSaveAction = showSaveAction,
            richContentEnabled = richContentEnabled,
            hasLocalSendState = message.hasLocalSendState(),
            isSaved = message.isSaved
        )
    val reserveInlineTrailingSpace = richContentEnabled && message.isSendingLocalSend()
    val hasFooterMeta =
        richContentEnabled &&
            !message.hasLocalSendState() &&
            message.hasThreadFooterMeta(replyMetadata, hasReplyDraft, showSavedBadge)
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
                    .background(threadMessageFocusHighlightColor(focusAlpha))
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
                        timestampLabel = localizedCompactTimestampLabel,
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = avatarWireValue,
                        status = agentStatus,
                        statusPulsing = !isMessageListScrolling,
                        muted = authorBadgeLabel != null,
                        onClick = {
                            if (message.canOpenThreadAuthorProfile(shareSelectionActive)) {
                                onOpenMember(message.authorId)
                            }
                        },
                        onLongPress = { onMentionAuthor(message) }
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
                            .threadMessagePressRipple(pressed = pressed, origin = pressRippleOrigin)
                            .threadMessageLongPressActions(
                                messageId = message.id,
                                captureLongPressAnchor = captureLongPressAnchor,
                                onShowActions = onShowActions,
                                onPressChanged = { isPressed, position ->
                                    pressed = isPressed
                                    if (position != null) {
                                        pressRippleOrigin = position
                                    }
                                }
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
                                        text = localizedAuthorName,
                                        style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack, lineHeight = 20.sp),
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
                                    message.authorDescription?.let { description ->
                                        ThreadMessageAuthorSubtitle(
                                            description,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                    if (showTimestampInHeader) {
                                        ThreadMessageTimestampLabel(localizedTimestampLabel)
                                    }
                                }
                            }
                        }
                        val forwardBundle = message.forwardBundle
                        if (richContentEnabled && actionCard != null) {
                            ThreadActionCardView(
                                card = actionCard,
                                pending = message.id in pendingActionCardIds,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                performanceKey = messagePerformanceKey,
                                diagnosticsLogger = diagnosticsLogger,
                                onCommit = { onCommitActionCard(message.id) }
                            )
                        } else if (richContentEnabled && forwardBundle != null) {
                            ThreadForwardBundleCard(
                                bundle = forwardBundle,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                onOpen = { onOpenForwardBundle(forwardBundle) }
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
                            Column(
                                modifier =
                                    Modifier
                                        .threadMessageContentWidth(messageMaxWidthFraction)
                                        .background(Color.Transparent),
                                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                                horizontalAlignment = contentAlignment
                            ) {
                                val renderedBody = translation?.displayBody ?: message.body
                                val longMessagePlan =
                                    remember(renderedBody) {
                                        threadLongMessageRenderPlan(renderedBody)
                                    }
                                val showLongMessageToggle =
                                    longMessageCollapseEnabled && longMessagePlan.collapsible
                                val bodyForRender =
                                    if (showLongMessageToggle && !longMessageExpanded) {
                                        longMessagePlan.collapsedBody
                                    } else {
                                        renderedBody
                                    }
                                if (translation?.pendingPlaceholder == true) {
                                    ThreadMessageTranslationPendingPlaceholder()
                                } else if (showLongMessageToggle && !longMessageExpanded) {
                                    Text(
                                        text = longMessagePlan.collapsedDisplayText,
                                        style = ThreadMessageBodyTextStyle,
                                        color = SlockColor.TextBlack,
                                        maxLines = ThreadLongMessagePreviewLineLimit,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else if (richContentEnabled) {
                                    val markdownRenderSignature =
                                        remember(message.id, bodyForRender) {
                                            if (bodyForRender == message.body) {
                                                message.threadMarkdownRenderSignature()
                                            } else {
                                                message.copy(body = bodyForRender).threadMarkdownRenderSignature()
                                            }
                                        }
                                    diagnosticsLogger(
                                        "thread_markdown_row key=${messagePerformanceKey.orEmpty()} " +
                                            "message=${message.id.take(8)} bodyLen=${bodyForRender.length} " +
                                            "sourceBodyLen=${renderedBody.length} collapsed=${showLongMessageToggle && !longMessageExpanded} " +
                                            "bodyHash=${bodyForRender.hashCode().toString(16)} context=$markdownTagContextSignature"
                                    )
                                    key(markdownRenderSignature, markdownTagContextSignature, activeMarkdownTagKey) {
                                        SlockMarkdownBody(
                                            body = bodyForRender,
                                            textStyle = ThreadMessageBodyTextStyle,
                                            highlightedMentions = highlightedMentions,
                                            isTagValid = isMarkdownTagValid,
                                            resolveTagLabel = resolveMarkdownTagLabel,
                                            inlineContentScope = markdownRenderSignature.inlineContentScope,
                                            activeTagKey = activeMarkdownTagKey,
                                            onOpenTag = onOpenMarkdownTag,
                                            onOpenUrl = onOpenMarkdownUrl,
                                            performanceKey = messagePerformanceKey,
                                            performanceLogger = diagnosticsLogger
                                        )
                                    }
                                } else {
                                    Text(
                                        text = bodyForRender,
                                        style = ThreadMessageBodyTextStyle,
                                        color = SlockColor.TextBlack
                                    )
                                }
                                if (showLongMessageToggle && translation?.pendingPlaceholder != true) {
                                    ThreadLongMessageToggle(
                                        expanded = longMessageExpanded,
                                        onClick = onToggleLongMessage
                                    )
                                }
                            }
                        }
                        if (actionCard == null) {
                            translation?.indicator?.let { indicator ->
                                Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                                    ThreadMessageTranslationIndicator(
                                        indicator = indicator,
                                        onToggleOriginal = onToggleTranslationOriginal,
                                        onRetry = onRetryTranslation
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
                                    diagnosticsLogger = diagnosticsLogger,
                                    alignEnd = alignOwnMessageRight,
                                    onOpenThread = { onOpenThread(message.id) },
                                    onOpenTask = onOpenTask,
                                    onShowActions = onShowActions,
                                    onOpenTaskStatusMenu = onOpenTaskStatusMenu,
                                    showSavedBadge = showSavedBadge,
                                    showReactionAddAction = showReactionAddAction,
                                    onToggleReaction = onToggleReaction,
                                    onShowReactionDetails = onShowReactionDetails
                                )
                            }
                        }
                    }
                    if (richContentEnabled) {
                        when {
                            message.isSendingLocalSend() -> {
                                ThreadMessageSendingIndicator(modifier = Modifier.align(Alignment.TopEnd))
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
                        status = agentStatus,
                        statusPulsing = !isMessageListScrolling,
                        muted = authorBadgeLabel != null,
                        onClick = {
                            if (message.canOpenThreadAuthorProfile(shareSelectionActive)) {
                                onOpenMember(message.authorId)
                            }
                        },
                        onLongPress = { onMentionAuthor(message) }
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

internal fun threadMessageFocusHighlightColor(focusAlpha: Float): Color {
    val normalizedAlpha = focusAlpha.coerceIn(0f, 1f)
    return if (normalizedAlpha > 0f) {
        SlockColor.AccentCyan.copy(alpha = 0.12f * normalizedAlpha)
    } else {
        SlockColor.SurfaceWhite
    }
}

internal fun shouldShowThreadMessageSavedBadge(
    showSaveAction: Boolean,
    richContentEnabled: Boolean,
    hasLocalSendState: Boolean,
    isSaved: Boolean
): Boolean =
    showSaveAction &&
        richContentEnabled &&
        !hasLocalSendState &&
        isSaved

internal val ThreadMessageSavedBadgeColor = SlockColor.AccentOrange.copy(alpha = 0.15f)
internal const val ThreadMessageSavedBadgeIconColorArgb = SlockColor.AccentOrangeArgb

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
    val localizedTitle = card.localizedActionCardTitle()
    val localizedDetails = card.localizedActionCardDetails()
    val localizedPendingLabel = card.localizedActionCardPendingLabel()
    val localizedVerb = card.localizedActionCardVerb()
    val localizedCommittedLabel = card.localizedActionCardCommittedLabel()
    val renderCopy =
        remember(
            card,
            performanceKey,
            localizedTitle,
            localizedDetails,
            localizedPendingLabel,
            localizedVerb,
            localizedCommittedLabel
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            ThreadActionCardRenderCopy(
                title = localizedTitle,
                details = localizedDetails,
                pendingLabel = localizedPendingLabel,
                verb = localizedVerb,
                committedLabel = localizedCommittedLabel
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
        if (card.isIntegrationApproveLoginAction()) {
            ThreadIntegrationLoginActionCardBody(card)
        } else if (details.isNotBlank()) {
            Text(
                text = details,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        card.actionCardDraftHint()?.let { hint ->
            ThreadActionCardHintBox(text = hint, italic = false)
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
private fun ThreadIntegrationLoginActionCardBody(card: ThreadActionCard) {
    val service = card.clientName?.takeIf { it.isNotBlank() }
        ?: stringResource(MR.strings.thread_action_card_app)
    val agent = card.agentName?.takeIf { it.isNotBlank() }
        ?: card.name.takeIf { it.isNotBlank() }
        ?: stringResource(MR.strings.thread_action_card_agent)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ThreadActionCardMetaLine(label = stringResource(MR.strings.thread_action_card_label_service), value = service)
        ThreadActionCardMetaLine(label = stringResource(MR.strings.thread_action_card_label_agent), value = "@${agent.removePrefix("@")}")
        Text(
            text = stringResource(MR.strings.thread_action_card_label_scopes),
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        val scopes = card.scopes.takeIf { it.isNotEmpty() } ?: listOf("openid", "profile", "identity")
        scopes.forEach { scope ->
            ThreadIntegrationScopeCard(scope)
        }
        ThreadActionCardHintBox(
            text = stringResource(
                MR.strings.thread_action_card_approval_hint,
                agent.removePrefix("@"),
                service
            ),
            italic = true
        )
    }
}

@Composable
private fun ThreadActionCardHintBox(text: String, italic: Boolean) {
    val style =
        if (italic) {
            SlockType.Caption.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = SlockColor.TextMutedGray,
                fontStyle = com.tencent.kuikly.compose.ui.text.font.FontStyle.Italic
            )
        } else {
            SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray)
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = style,
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun ThreadActionCardMetaLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.width(58.dp)
        )
        Text(
            text = value,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ThreadIntegrationScopeCard(scope: String) {
    val normalized = scope.trim()
    val description = localizedIntegrationScopeDescription(normalized)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.18f))
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = normalized,
            style =
                SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = SlockColor.TextMutedGray,
                    fontFamily = slockMonoFamilyForWeight(FontWeight.Bold)
                ),
            color = SlockColor.TextMutedGray
        )
        Text(
            text = description,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
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
            text = stringResource(MR.strings.common_done),
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
        "integration:approve_agent_login" -> "Approve Login"
        else -> "Action"
    }

@Composable
private fun ThreadActionCard.localizedActionCardVerb(): String =
    when (type) {
        "channel:create" -> stringResource(MR.strings.thread_action_card_create_channel)
        "agent:create" -> stringResource(MR.strings.thread_action_card_create_agent)
        "channel:add_member" -> stringResource(MR.strings.thread_action_card_add_members)
        "integration:approve_agent_login" -> stringResource(MR.strings.thread_action_card_approve_login)
        else -> stringResource(MR.strings.thread_action_card_action)
    }

@Composable
private fun ThreadActionCard.localizedActionCardPendingLabel(): String =
    when (type) {
        "channel:add_member" -> stringResource(MR.strings.thread_action_card_adding)
        "integration:approve_agent_login" -> stringResource(MR.strings.thread_action_card_approving)
        else -> stringResource(MR.strings.thread_action_card_creating)
    }

@Composable
private fun ThreadActionCard.localizedActionCardTitle(): String =
    when (type) {
        "channel:create" -> {
            val localizedVisibility =
                when (visibility?.lowercase()) {
                    "private" -> stringResource(MR.strings.thread_action_card_private)
                    else -> stringResource(MR.strings.thread_action_card_public)
                }
            stringResource(
                MR.strings.thread_action_card_title_create_channel,
                localizedVisibility,
                name.removePrefix("#")
            )
        }
        "agent:create" ->
            stringResource(MR.strings.thread_action_card_title_create_agent, name.removePrefix("@"))
        "channel:add_member" -> {
            val count = humans.size + agents.size
            val target = channel?.let { "#${it.removePrefix("#")}" }
                ?: stringResource(MR.strings.thread_action_card_channel)
            if (count == 1) {
                stringResource(MR.strings.thread_action_card_title_add_member, target)
            } else {
                stringResource(MR.strings.thread_action_card_title_add_members, count, target)
            }
        }
        "integration:approve_agent_login" -> {
            val service = clientName?.takeIf { it.isNotBlank() }
                ?: stringResource(MR.strings.thread_action_card_app)
            val agent = agentName?.takeIf { it.isNotBlank() }
                ?: name.takeIf { it.isNotBlank() }
                ?: stringResource(MR.strings.thread_action_card_agent)
            stringResource(
                MR.strings.thread_action_card_title_approve_login,
                service,
                agent.removePrefix("@")
            )
        }
        else -> localizedActionCardVerb()
    }

@Composable
private fun ThreadActionCard.localizedActionCardDetails(): String =
    buildList {
        description?.takeIf { it.isNotBlank() }?.let {
            add(stringResource(MR.strings.thread_action_card_detail_description, it))
        }
        if (type.equals("agent:create", ignoreCase = true)) {
            requiredComputer?.takeIf { it.isNotBlank() }?.let {
                add(stringResource(MR.strings.thread_action_card_detail_required_computer, it))
            }
            if (requiredComputer.isNullOrBlank()) {
                suggestedComputer?.takeIf { it.isNotBlank() }?.let {
                    add(stringResource(MR.strings.thread_action_card_detail_suggested_computer, it))
                }
            }
            if (description.isNullOrBlank()) {
                add(stringResource(MR.strings.thread_action_card_detail_no_description))
            }
        }
        if (initialHumans.isNotEmpty()) {
            add(stringResource(MR.strings.thread_action_card_detail_humans, initialHumans.joinToString(", ")))
        }
        if (initialAgents.isNotEmpty()) {
            add(stringResource(MR.strings.thread_action_card_detail_agents, initialAgents.joinToString(", ")))
        }
        if (humans.isNotEmpty()) {
            add(stringResource(MR.strings.thread_action_card_detail_humans, humans.joinToString(", ")))
        }
        if (agents.isNotEmpty()) {
            add(stringResource(MR.strings.thread_action_card_detail_agents, agents.joinToString(", ")))
        }
        if (type.equals("integration:approve_agent_login", ignoreCase = true)) {
            clientName?.takeIf { it.isNotBlank() }?.let {
                add(stringResource(MR.strings.thread_action_card_detail_service, it))
            }
            agentName?.takeIf { it.isNotBlank() }?.let {
                add(stringResource(MR.strings.thread_action_card_detail_agent, it.removePrefix("@")))
            }
            val scopeLine = scopes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"
            add(stringResource(MR.strings.thread_action_card_detail_scopes, scopeLine))
        }
    }.joinToString("\n")

private fun ThreadActionCard.actionCardPendingLabel(): String =
    when (type) {
        "channel:add_member" -> "Adding..."
        "integration:approve_agent_login" -> "Approving..."
        else -> "Creating..."
    }

private fun ThreadActionCard.actionCardSuccessToast(): String =
    when (type) {
        "channel:create" -> "Channel created"
        "agent:create" -> "Agent created"
        "channel:add_member" -> "Members added"
        "integration:approve_agent_login" -> "Login approved"
        else -> "Action completed"
    }

private fun ThreadActionCard.actionCardFailureToast(): String =
    when (type) {
        "channel:create" -> "Unable to create channel"
        "agent:create" -> "Unable to create agent"
        "channel:add_member" -> "Unable to add members"
        "integration:approve_agent_login" -> "Unable to approve login"
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
        "integration:approve_agent_login" -> {
            val service = clientName?.takeIf { it.isNotBlank() } ?: "app"
            val agent = agentName?.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() } ?: "agent"
            "Approve $service for @${agent.removePrefix("@")}"
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
        if (type.equals("integration:approve_agent_login", ignoreCase = true)) {
            clientName?.takeIf { it.isNotBlank() }?.let { add("Service: $it") }
            agentName?.takeIf { it.isNotBlank() }?.let { add("Agent: @${it.removePrefix("@")}") }
            val scopeLine = scopes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"
            add("Scopes: $scopeLine")
        }
    }.joinToString("\n")

internal fun ThreadActionCard.actionCardDraftHint(): String? =
    draftHint
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { isIntegrationApproveLoginAction() }

private fun ThreadActionCard.isIntegrationApproveLoginAction(): Boolean =
    type.equals("integration:approve_agent_login", ignoreCase = true)

private fun integrationScopeDescription(scope: String): String =
    when (scope.trim().lowercase()) {
        "identity" ->
            "Reads who you are - human or agent, your Raft subject, and which server/principal you are acting as. No content access."
        "openid" ->
            "Signs you in with Raft and issues a stable identity token. Authentication only."
        "profile" ->
            "Reads your basic profile fields, such as display name. No access to messages, files, or agents."
        else ->
            "Allows the app to request this named permission from Raft."
    }

@Composable
private fun localizedIntegrationScopeDescription(scope: String): String =
    when (scope.trim().lowercase()) {
        "identity" -> stringResource(MR.strings.thread_action_card_scope_identity)
        "openid" -> stringResource(MR.strings.thread_action_card_scope_openid)
        "profile" -> stringResource(MR.strings.thread_action_card_scope_profile)
        else -> stringResource(MR.strings.thread_action_card_scope_other)
    }

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
            "agent-integration-login" -> {
                val service = resultName?.takeIf { it.isNotBlank() } ?: clientName
                val agent = agentName?.takeIf { it.isNotBlank() }
                when {
                    !service.isNullOrBlank() && !agent.isNullOrBlank() -> "$service approved for @${agent.removePrefix("@")}"
                    !service.isNullOrBlank() -> "$service approved"
                    else -> "Login approved"
                }
            }
            else -> null
        }
        if (!result.isNullOrBlank()) {
            if (isNotBlank()) append(" -> ") else append("Result: ")
            append(result)
        }
    }

@Composable
private fun ThreadActionCard.localizedActionCardCommittedLabel(): String {
    val committedBy = executedByUserName?.takeIf { it.isNotBlank() }?.let {
        stringResource(MR.strings.thread_action_card_committed_by, it)
    }
    val result =
        when (resultKind) {
            "agent" -> resultName?.let { "@${it.removePrefix("@")}" }
            "channel" -> resultName?.let { "#${it.removePrefix("#")}" }
            "channel-members" -> {
                val total = resultAddedHumanIds.size + resultAddedAgentIds.size
                resultChannelName?.let {
                    stringResource(MR.strings.thread_action_card_members_added, total, it.removePrefix("#"))
                }
            }
            "agent-integration-login" -> {
                val service = resultName?.takeIf { it.isNotBlank() } ?: clientName
                val agent = agentName?.takeIf { it.isNotBlank() }
                when {
                    !service.isNullOrBlank() && !agent.isNullOrBlank() ->
                        stringResource(
                            MR.strings.thread_action_card_service_approved_for,
                            service,
                            agent.removePrefix("@")
                        )
                    !service.isNullOrBlank() ->
                        stringResource(MR.strings.thread_action_card_service_approved, service)
                    else -> stringResource(MR.strings.thread_action_card_login_approved)
                }
            }
            else -> null
        }
    return when {
        committedBy != null && result != null -> "$committedBy -> $result"
        committedBy != null -> committedBy
        result != null -> stringResource(MR.strings.thread_action_card_result, result)
        else -> ""
    }
}

@Composable
private fun threadCreateAgentDialogErrorCopy(code: String): String =
    when (code) {
        "agent_name_required",
        "action_card_agent_name_missing" -> stringResource(MR.strings.thread_action_card_error_agent_name_required)
        "action_card_agents_api_missing" -> stringResource(MR.strings.thread_action_card_error_agent_unavailable)
        "action_card_actions_api_missing" -> stringResource(MR.strings.thread_action_card_error_completion_unavailable)
        "action_card_mark_executed_empty",
        "action_mark_executed_contract_mismatch" -> stringResource(MR.strings.thread_action_card_error_mark_done)
        "agent_create_empty" -> stringResource(MR.strings.thread_action_card_error_agent_not_created)
        else -> stringResource(MR.strings.thread_action_card_error_agent_create, code)
    }

@Composable
private fun threadCreateAgentMachinesErrorCopy(code: String): String =
    when (code) {
        "server_id_missing" -> stringResource(MR.strings.thread_action_card_error_select_server)
        "auth_required",
        "auth_session_missing",
        "auth_session_expired" -> stringResource(MR.strings.thread_action_card_error_sign_in_computers)
        "server_forbidden" -> stringResource(MR.strings.thread_action_card_error_computers_permission)
        else -> stringResource(MR.strings.thread_action_card_error_load_computers, code)
    }

@Composable
private fun ThreadReactionChip(
    reaction: KuiklyThreadReactionItem,
    onClick: (() -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null
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
    if (onLongPress != null) {
        // task #569: tap keeps toggling the reaction; long-press surfaces the
        // reactor tooltip. Both live on one gesture detector so the long
        // press cannot fire a toggle. The anchor is reported in root pixels
        // (same contract as the message long-press menu).
        val rootPos = remember { SlockMutableOffsetRef() }
        Box(
            modifier = Modifier
                .onGloballyPositioned { rootPos.update(it.positionInRoot()) }
                .pointerInput(reaction.emoji, onClick != null) {
                    detectTapGestures(
                        onTap = { onClick?.invoke() },
                        onLongPress = { localOffset ->
                            val base = rootPos.value.takeIf { rootPos.hasValue } ?: Offset.Zero
                            onLongPress(Offset(base.x + localOffset.x, base.y + localOffset.y))
                        }
                    )
                }
                .then(chipModifier),
            contentAlignment = Alignment.Center
        ) {
            chipContent()
        }
    } else if (onClick != null) {
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

// task #569: everything the tooltip needs, captured at long-press time.
internal data class ThreadReactionDetailsRequest(
    val emoji: String,
    val names: List<String>,
    val count: Int,
    val anchor: Offset
)

// Label formatting for the reactor tooltip: first N names + "+K" for the
// rest (mobile pendant of the web hover tooltip). Falls back to a bare count
// when the payload carries no names (older servers).
internal fun threadReactionDetailsLabel(
    names: List<String>,
    count: Int,
    maxNames: Int = ThreadReactionDetailsMaxNames
): String {
    val clean = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (clean.isEmpty()) {
        return if (count == 1) "1 person" else "$count people"
    }
    val shown = clean.take(maxNames)
    val hidden = maxOf(count, clean.size) - shown.size
    val joined = shown.joinToString(", ")
    return if (hidden > 0) "$joined +$hidden" else joined
}

internal const val ThreadReactionDetailsMaxNames = 4

@Composable
private fun ThreadReactionAddChip(onClick: (Offset) -> Unit) {
    Button(
        onClick2 = onClick,
        modifier = Modifier.background(Color.Transparent)
    ) {
        ThreadReactionAddChipChrome()
    }
}

// task #569: who-reacted tooltip, long-press pendant of the web hover
// tooltip. Reuses the anchored-popover infra (viewport clamping, outside-tap
// dismiss with the long-press activation window, Back participation).
@Composable
private fun ThreadReactionDetailsTooltip(
    details: ThreadReactionDetailsRequest,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val label = threadReactionDetailsLabel(details.names, details.count)
    val estimatedSize =
        with(density) {
            Size(
                width =
                    (ThreadReactionDetailsGlyphSize + ThreadReactionDetailsHorizontalPadding * 2 + 10.dp).toPx() +
                        label.length * ThreadReactionDetailsLabelCharWidth.toPx(),
                height = ThreadReactionDetailsHeight.toPx()
            )
        }
    SlockAnchoredPopover(
        anchor = details.anchor,
        contentSize = estimatedSize,
        onDismiss = onDismiss,
        verticalPlacement = SlockFloatingVerticalPlacement.Auto
    ) { popoverModifier, _ ->
        Row(
            modifier = popoverModifier
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .slockFloatingContentClickTarget()
                .height(ThreadReactionDetailsHeight)
                .padding(horizontal = ThreadReactionDetailsHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThreadQuickReactionGlyph(
                threadDisplayReactionEmoji(details.emoji),
                modifier = Modifier.size(ThreadReactionDetailsGlyphSize)
            )
            Text(
                text = label,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val ThreadReactionDetailsHeight = 28.dp
private val ThreadReactionDetailsGlyphSize = 15.dp
private val ThreadReactionDetailsHorizontalPadding = 8.dp
// Rough per-character width at 12sp bold caption — only used to pre-size the
// popover for viewport clamping; the row itself wraps its real content.
private val ThreadReactionDetailsLabelCharWidth = 7.dp

@Composable
private fun ThreadReactionAddChipChrome() {
    Box(
        modifier = Modifier
            .height(20.dp)
            .background(Color(0xFFEEEDE6), RoundedCornerShape(SlockRadius.Sm))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(SlockLucideIconName.Plus, modifier = Modifier.size(13.dp), color = "#FF000000")
    }
}

private fun Modifier.threadMessageContentWidth(maxWidthFraction: Float): Modifier =
    if (maxWidthFraction < 1f) {
        fillMaxWidth(maxWidthFraction)
    } else {
        fillMaxWidth()
    }

// Mobile press feedback (task #555): the finger-down point washes gray and a
// ripple spreads across the row — replacing the web-style press border. The
// indication is delayed past touch-slop so a scroll that starts on a message
// never flashes it: a drag steals the pointer and cancels the press scope
// before the delay elapses.
private const val ThreadMessagePressIndicationDelayMs = 120L
private const val ThreadMessageRippleExpandMs = 260
private const val ThreadMessageRippleFadeMs = 240
private const val ThreadMessageRippleResetMarginMs = 60L
private const val ThreadMessageRippleAlpha = 0.10f
private val ThreadMessageRippleMinRadius = 24.dp

@Composable
private fun Modifier.threadMessagePressRipple(
    pressed: Boolean,
    origin: Offset?
): Modifier {
    val expansion by animateFloatAsState(
        targetValue = if (origin != null) 1f else 0f,
        animationSpec = tween(durationMillis = if (origin != null) ThreadMessageRippleExpandMs else 0),
        label = "thread-message-ripple-expansion"
    )
    val washAlpha by animateFloatAsState(
        targetValue = if (pressed && origin != null) ThreadMessageRippleAlpha else 0f,
        animationSpec =
            tween(durationMillis = if (pressed) ThreadMessageRippleExpandMs else ThreadMessageRippleFadeMs),
        label = "thread-message-ripple-alpha"
    )
    if (origin == null) return this
    return drawBehind {
        if (washAlpha <= 0f || expansion <= 0f) return@drawBehind
        val coverRadius = hypot(size.width, size.height)
        val minRadius = ThreadMessageRippleMinRadius.toPx()
        val radius = minRadius + (coverRadius - minRadius) * expansion
        drawCircle(
            color = Color.Black.copy(alpha = washAlpha),
            radius = radius,
            center = origin
        )
    }
}

@Composable
private fun Modifier.threadMessageLongPressActions(
    messageId: String,
    captureLongPressAnchor: Boolean = true,
    onShowActions: (Offset) -> Unit,
    onPressChanged: (Boolean, Offset?) -> Unit = { _, _ -> }
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
                onPress = { downPosition ->
                    var indicated = false
                    try {
                        // Scrolls steal the pointer and cancel this scope
                        // within the delay — press feedback never shows for
                        // them (task #555).
                        delay(ThreadMessagePressIndicationDelayMs)
                        indicated = true
                        currentOnPressChanged(true, downPosition)
                        tryAwaitRelease()
                    } finally {
                        if (indicated) {
                            currentOnPressChanged(false, null)
                        }
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
        avatarWireValue = avatarWireValue
    )
    return resolution.effectiveAvatarWireValue
}

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

internal fun KuiklyThreadMessageItem.threadAuthorAgentStatusIndicatorState(): SlockAgentStatusIndicatorState? {
    if (!showsAgentStatusIndicator()) return null
    val status = AgentStatus.fromWireValue(authorAgentStatus) ?: return null
    return slockAgentStatusIndicatorState(
        status = status,
        presence = status.presence
    )
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

internal fun KuiklyThreadMessageItem.threadMentionHandle(
    serverMembersState: SharedServerMembersState = SharedServerMembersState(),
    serverId: ServerId? = null
): String? {
    if (threadAuthorBadgeLabel() != null) return null
    val cleanAuthorId = authorId.trim().takeIf { it.isNotBlank() }
    serverMembersState
        .threadMentionAuthorHandle(serverId, cleanAuthorId)
        ?.let { return it }
    val explicitAuthorHandle = authorHandle.threadMentionExplicitAuthorHandle(authorName)
    if (explicitAuthorHandle != null) return explicitAuthorHandle
    return cleanAuthorId
}

private fun SharedServerMembersState.threadMentionAuthorHandle(
    serverId: ServerId?,
    authorId: String?
): String? =
    memberProfile(serverId, authorId)
        ?.handle
        .threadMentionHandleValue()

private fun String?.threadMentionHandleValue(): String? =
    this
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf { it.isNotBlank() }

private fun String.threadMentionExplicitAuthorHandle(authorName: String): String? {
    val handle = threadMentionHandleValue() ?: return null
    val presentationName = authorName.threadMentionHandleValue()
    if (presentationName != null && handle == presentationName) return null
    if (handle.any { it.isWhitespace() }) return null
    return handle
}

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

internal fun threadDateDividerLabel(
    previous: KuiklyThreadMessageItem?,
    next: KuiklyThreadMessageItem,
    timeContext: SlockTimeContext
): String? {
    val localOffsetMinutes = timeContext.localOffsetMinutes
    val nextEpochDay = next.threadMessageLocalEpochDayOrNull(localOffsetMinutes) ?: return null
    val previousEpochDay = previous?.threadMessageLocalEpochDayOrNull(localOffsetMinutes)
    if (previousEpochDay == nextEpochDay) return null
    val todayEpochDay =
        timeContext.todayEpochDay
            ?: timeContext.nowEpochMillis?.let { threadLocalEpochDay(it, localOffsetMinutes) }
    return threadDateDividerLabel(nextEpochDay, todayEpochDay)
}

private fun KuiklyThreadMessageItem.isCompactContinuationBefore(
    next: KuiklyThreadMessageItem,
    localOffsetMinutes: Int
): Boolean {
    if (isSystemMessage() || next.isSystemMessage()) return false
    val identityKey = authorIdentityKey()
    if (identityKey.isBlank() || identityKey != next.authorIdentityKey()) return false

    val currentEpochMillis = parseThreadMessageEpochMillisOrNull(localOffsetMinutes)
    val nextEpochMillis = next.parseThreadMessageEpochMillisOrNull(localOffsetMinutes)
    if (currentEpochMillis == null || nextEpochMillis == null) return false

    val elapsedMillis = nextEpochMillis - currentEpochMillis
    if (elapsedMillis !in 0..ThreadCompactContinuationWindowMillis) return false

    return threadLocalEpochDay(currentEpochMillis, localOffsetMinutes) ==
        threadLocalEpochDay(nextEpochMillis, localOffsetMinutes)
}

private fun KuiklyThreadMessageItem.parseThreadMessageEpochMillisOrNull(localOffsetMinutes: Int): Long? =
    parseSlockTimestampEpochMillisOrNull(timestampLabel, localOffsetMinutes)

private fun KuiklyThreadMessageItem.threadMessageLocalEpochDayOrNull(localOffsetMinutes: Int): Long? =
    parseThreadMessageEpochMillisOrNull(localOffsetMinutes)?.let { threadLocalEpochDay(it, localOffsetMinutes) }

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

private fun threadFloorMod(value: Long, divisor: Long): Long =
    value - threadFloorDiv(value, divisor) * divisor

private fun threadDateDividerLabel(epochDay: Long, todayEpochDay: Long?): String =
    when {
        todayEpochDay != null && epochDay == todayEpochDay -> "TODAY"
        todayEpochDay != null && epochDay == todayEpochDay - 1L -> "YESTERDAY"
        else -> {
            val date = threadCivilDateFromEpochDay(epochDay)
            "${ThreadWeekdayNames[threadWeekdayIndex(epochDay)]}, ${ThreadMonthNames[date.month - 1]} ${date.day}"
        }
    }

private data class ThreadCivilDate(
    val year: Int,
    val month: Int,
    val day: Int
)

private fun threadCivilDateFromEpochDay(epochDay: Long): ThreadCivilDate {
    val zeroDay = epochDay + 719_468L
    val era = threadFloorDiv(zeroDay, 146_097L)
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    val adjustedYear = year + if (month <= 2L) 1L else 0L
    return ThreadCivilDate(adjustedYear.toInt(), month.toInt(), day.toInt())
}

private fun threadWeekdayIndex(epochDay: Long): Int =
    threadFloorMod(epochDay + 4L, 7L).toInt()

private val ThreadWeekdayNames =
    listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")

private val ThreadMonthNames =
    listOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")

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
    diagnosticsLogger: (String) -> Unit = {},
    alignEnd: Boolean,
    onOpenThread: () -> Unit,
    onOpenTask: (String) -> Unit,
    onShowActions: (Offset) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
    showSavedBadge: Boolean = false,
    showReactionAddAction: Boolean = false,
    onToggleReaction: ((String) -> Unit)? = null,
    onShowReactionDetails: ((KuiklyThreadReactionItem, Offset) -> Unit)? = null
) {
    val hasTask = message.hasThreadTaskFooterMeta()
    val hasReplies = replyMetadata.replyCount > 0 || hasReplyDraft
    val visibleReactions = message.reactions.filter { it.count > 0 }
    val hasReactions = visibleReactions.isNotEmpty()
    val showReactionAddChip = showReactionAddAction && hasReactions
    if (!hasTask && !hasReplies && !showSavedBadge && !hasReactions && !showReactionAddChip) return

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
            val taskClickIdentity =
                threadTaskStatusChipClickIdentity(
                    message = message,
                    selectedStatus = selectedStatus,
                    taskStatusActionsEnabled = taskStatusActionsEnabled,
                    taskOpenEnabled = taskOpenEnabled
                )
            SlockAnchoredMetaChip(
                label = label,
                iconName = selectedStatus.icon,
                iconSize = selectedStatus.iconSize,
                color = selectedStatus.color,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 260.dp),
                clickIdentity = taskClickIdentity,
                onClick = taskChipClick
            )
        }
        if (hasReplies) {
            val replyLabel = threadMessageRepliesMetaLabel(replyMetadata)
            val replyClickIdentity =
                "thread-reply:${message.id}:${replyMetadata.threadChannelId.orEmpty()}:" +
                    "${replyMetadata.replyCount}:${replyMetadata.unreadReplyCount}:$hasReplyDraft"
            SlockAnchoredMetaChip(
                label = replyLabel,
                iconName = SlockLucideIconName.MessageSquare,
                iconSize = 12.dp,
                trailingIconName = SlockLucideIconName.Pencil.takeIf { hasReplyDraft },
                trailingIconSize = 12.dp,
                color = threadRepliesMetaChipColor(replyMetadata),
                modifier = Modifier.widthIn(max = 220.dp),
                pressFeedback = false,
                clickIdentity = replyClickIdentity,
                onClick = if (showReplyAction) {
                    { _ ->
                        diagnosticsLogger(
                            "thread_reply_chip_click parentMessageId=${message.id} " +
                                "knownThreadChannelId=${replyMetadata.threadChannelId.orEmpty()} " +
                                "replyCount=${replyMetadata.replyCount} clickIdentity=$replyClickIdentity"
                        )
                        onOpenThread()
                    }
                } else {
                    null
                }
            )
        }
        if (showSavedBadge) {
            SlockAnchoredMetaChip(
                label = stringResource(MR.strings.thread_label_saved),
                iconName = SlockLucideIconName.Bookmark,
                iconSize = 11.dp,
                iconColorArgb = ThreadMessageSavedBadgeIconColorArgb,
                iconFillArgb = ThreadMessageSavedBadgeIconColorArgb,
                color = ThreadMessageSavedBadgeColor,
                modifier = Modifier.widthIn(max = 96.dp),
                pressFeedback = false,
                clickIdentity = "thread-saved:${message.id}",
                onClick = null
            )
        }
        if (hasReactions) {
            visibleReactions.forEach { reaction ->
                key("reaction:${message.id}:${reaction.emoji}") {
                    ThreadReactionChip(
                        reaction = reaction,
                        onClick = onToggleReaction?.let { callback -> { callback(reaction.emoji) } },
                        onLongPress =
                            onShowReactionDetails?.let { callback ->
                                { anchor -> callback(reaction, anchor) }
                            }
                    )
                }
            }
        }
        if (showReactionAddChip) {
            ThreadReactionAddChip(onClick = onShowActions)
        }
    }
}

internal fun KuiklyThreadMessageItem.hasThreadFooterMeta(
    hasReplyDraft: Boolean = false,
    showSavedBadge: Boolean = false
): Boolean =
    hasThreadFooterMeta(toThreadReplyMetadata(), hasReplyDraft, showSavedBadge)

internal fun KuiklyThreadMessageItem.hasThreadFooterMeta(
    replyMetadata: ThreadReplyMetadata,
    hasReplyDraft: Boolean = false,
    showSavedBadge: Boolean = false
): Boolean =
    hasThreadTaskFooterMeta() ||
        replyMetadata.replyCount > 0 ||
        hasReplyDraft ||
        showSavedBadge ||
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

internal fun threadRepliesMetaChipColor(replyMetadata: ThreadReplyMetadata): Color =
    if (replyMetadata.unreadReplyCount > 0) {
        SlockColor.AccentCyan.copy(alpha = 0.2f)
    } else {
        SlockColor.SurfaceWhite
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

private val ThreadTaskStatusMenuItems: List<SlockBaseMenuItem> =
    SharedTaskStatusChipMetas.map { option ->
        SlockBaseMenuItem(id = option.key, label = option.label)
    }

internal fun threadTaskStatusChipClickIdentity(
    message: KuiklyThreadMessageItem,
    selectedStatus: SharedTaskStatusChipMeta,
    taskStatusActionsEnabled: Boolean,
    taskOpenEnabled: Boolean
): String =
    "thread-task-status:${message.id}:${message.taskId.orEmpty()}:" +
        "${selectedStatus.key}:$taskStatusActionsEnabled:$taskOpenEnabled"

internal fun String?.threadTaskStatusOption(): SharedTaskStatusChipMeta =
    sharedTaskStatusChipMetaOrDefault()

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
    val label: StringDesc,
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
    showTranslateAction: Boolean = false,
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
    onDelete: () -> Unit,
    onTranslate: () -> Unit = {}
): List<ThreadMessageMenuAction> =
    buildList {
        if (showCopyLinkAction) {
            add(
                ThreadMessageMenuAction(
                    label = MR.strings.thread_action_copy_link.desc(),
                    icon = SlockLucideIconName.Link,
                    group = "copy",
                    onClick = onCopyLink
                )
            )
        }
        add(ThreadMessageMenuAction(MR.strings.thread_action_copy_markdown.desc(), SlockLucideIconName.Copy, "copy", onClick = onCopy))
        add(ThreadMessageMenuAction(MR.strings.thread_action_select_text.desc(), SlockLucideIconName.FileText, "copy", onClick = onSelectText))
        if (showTranslateAction) {
            add(
                ThreadMessageMenuAction(
                    label = MR.strings.thread_action_translate.desc(),
                    icon = SlockLucideIconName.Languages,
                    group = "copy",
                    onClick = onTranslate
                )
            )
        }
        if (showShareMessagesAction) {
            add(
                ThreadMessageMenuAction(
                    label = MR.strings.thread_action_select_message.desc(),
                    icon = SlockLucideIconName.CircleCheck,
                    group = "copy",
                    onClick = onShareMessages
                )
            )
        }
        if (showReplyAction) {
            add(ThreadMessageMenuAction(MR.strings.thread_action_open_thread.desc(), SlockLucideIconName.MessageSquare, "message", onClick = onReply))
        }
        if (showSaveAction) {
            add(
                ThreadMessageMenuAction(
                    label =
                        if (message.isSaved) {
                            MR.strings.thread_action_remove_from_saved.desc()
                        } else {
                            MR.strings.thread_action_save_message.desc()
                        },
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
                    label =
                        if (replyMetadata.isThreadFollowed) {
                            MR.strings.thread_action_unfollow_thread.desc()
                        } else {
                            MR.strings.thread_action_follow_thread.desc()
                        },
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
                    label = MR.strings.common_delete.desc(),
                    icon = SlockLucideIconName.Trash,
                    group = "destructive",
                    onClick = onDelete
                )
            )
        }
        if (showCancelAction) {
            add(
                ThreadMessageMenuAction(
                    label = MR.strings.common_cancel.desc(),
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
                    label =
                        if (isDone) {
                            MR.strings.thread_action_reopen_task.desc()
                        } else {
                            MR.strings.thread_action_mark_done.desc()
                        },
                    icon = if (isDone) SlockLucideIconName.RefreshCw else SlockLucideIconName.CircleCheckBig,
                    group = "task",
                    onClick = { onTaskStatusChange(if (isDone) "reopenTask" else "markTaskDone") }
                )
            )
        }
        if (showConvertToTaskAction && message.canConvertToTask()) {
            add(
                ThreadMessageMenuAction(
                    label = MR.strings.thread_action_convert_to_task.desc(),
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
    showTranslateAction: Boolean = false,
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
    onTranslate: () -> Unit = {},
    diagnosticsLogger: (String) -> Unit = {}
) {
    val reactionAtlasImage = LocalThreadReactionAtlasImage.current
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
            showTranslateAction,
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
            onDelete,
            onTranslate
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
                showTranslateAction = showTranslateAction,
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
                onDelete = onDelete,
                onTranslate = onTranslate
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
            reactionAtlasImage = reactionAtlasImage,
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
        Box(modifier = Modifier.fillMaxSize()) {
            // Keep the native dispatch boundary behind the selectable content.
            // A capture node on the Column itself prevents its text field and
            // toolbar actions from receiving the gesture on Android.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(SlockColor.SurfaceWhite)
                        .slockNativeDispatchCapture()
            )
            Column(
                modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
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
                            fontFamily = SlockMonoFamily,
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
}

@Composable
private fun ThreadMessageMenuChrome(
    actions: List<ThreadMessageMenuAction>,
    showReactionActions: Boolean,
    reactionAtlasImage: ImageBitmap?,
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
                ThreadMessageReactionRow(
                    reactionAtlasImage = reactionAtlasImage,
                    onReact = onReact
                )
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
private fun ThreadMessageReactionRow(
    reactionAtlasImage: ImageBitmap?,
    onReact: (String) -> Unit
) {
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
                ThreadQuickReactionGlyph(
                    reaction,
                    modifier = Modifier.size(18.dp),
                    reactionAtlasImage = reactionAtlasImage
                )
            }
        }
    }
}

private fun threadDisplayReactionEmoji(emoji: String): String =
    emoji

// task #376 (Quiver 389d855b): the decoded reaction sprite atlas is hoisted to
// a stable ThreadPage mount point and provided here so glyphs consume the ready
// ImageBitmap instead of each starting their own async `imageResource` load. A
// null value means "not decoded yet" (temporary) and updates to the ready bitmap
// via recomposition once the long-lived root finishes decoding — it must never
// harden into a permanent raw-emoji fallback.
internal val LocalThreadReactionAtlasImage = compositionLocalOf<ImageBitmap?> { null }

@Composable
private fun ThreadReactionAtlasProvider(content: @Composable () -> Unit) {
    // task #376/#380: decode the reaction sprite atlas once at a stable root and
    // provide the ready ImageBitmap down so both Thread content and standalone
    // visual fixtures resolve glyphs from the shared bitmap instead of each
    // starting a short-lived async `imageResource` load. The visual fixtures also
    // emit task #379's ready token so the capture harness can wait for the atlas
    // before snapshotting reaction-bearing cases.
    // task #421: the atlas DrawableResource is null on iOS (asset not bundled),
    // so only load it where available; otherwise provide a null atlas and let
    // reaction rendering fall back to emoji glyphs.
    val atlasDrawable = ThreadReactionSpriteAtlasDrawableResource
    val threadReactionAtlasImage = if (atlasDrawable != null) imageResource(atlasDrawable) else null
    val readyThreadReactionAtlas =
        threadReactionAtlasImage?.takeIf { it.width > 0 && it.height > 0 }
    if (readyThreadReactionAtlas != null) {
        SideEffect {
            ThreadReactionGlyphDiagnostics.logAtlasReadyOnce(
                readyThreadReactionAtlas.width,
                readyThreadReactionAtlas.height
            )
        }
    }
    CompositionLocalProvider(LocalThreadReactionAtlasImage provides readyThreadReactionAtlas) {
        content()
    }
}

@Composable
private fun ThreadQuickReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier,
    reactionAtlasImage: ImageBitmap? = null
) {
    // task #421 / #423: use the sprite path only when the atlas bitmap has
    // actually decoded. Since task #423 the MR bundle resolves on iOS too (so
    // `spriteAtlasAvailable` is now true there), but the atlas ImageBitmap may
    // still fail to decode in some environments — gating on mere bundle
    // availability then drew a *blank* atlas rect instead of falling back to the
    // emoji glyph. Gate on the decoded image (the same value the sprite glyph
    // draws), so an undecoded atlas falls through to the emoji, never blank.
    val atlasImage = reactionAtlasImage ?: LocalThreadReactionAtlasImage.current
    val sprite = if (atlasImage != null) ThreadReactionSpriteCatalogV1.resolve(emoji) else null
    if (sprite != null) {
        ThreadReactionSpriteGlyph(
            sprite = sprite,
            modifier = modifier,
            reactionAtlasImage = atlasImage
        )
        return
    }
    ThreadReactionGlyphDiagnosticLog(
        path = "legacy_no_sprite",
        emoji = emoji,
        detail = if (atlasImage == null) "reason=atlas_not_decoded" else "reason=no_manifest_match"
    )
    ThreadTextReactionGlyph(emoji = emoji, modifier = modifier)
}

@Composable
private fun ThreadReactionSpriteGlyph(
    sprite: ThreadReactionSprite,
    modifier: Modifier = Modifier,
    reactionAtlasImage: ImageBitmap? = null
) {
    // task #376 (Quiver 389d855b): consume the atlas image hoisted to the
    // ThreadPage root via LocalThreadReactionAtlasImage instead of starting a
    // fresh async `imageResource` load here. In a short-lived composition
    // subtree (the long-press action-menu overlay) a per-glyph `imageResource`
    // returned a 0x0 first frame and stuck on the raw-emoji fallback; reading
    // the provided ready bitmap avoids that timing hole. A null value here is
    // only a transient "not decoded yet" state — the root keeps the atlas
    // mounted and this consumer recomposes to the sprite once it is ready.
    val image = reactionAtlasImage ?: LocalThreadReactionAtlasImage.current
    if (image == null) {
        ThreadReactionGlyphDiagnosticLog(
            path = "sprite_atlas_not_ready",
            emoji = sprite.value,
            detail = "sprite=${sprite.name} imageWidth=0 imageHeight=0"
        )
        Canvas(modifier = modifier) {}
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
private fun ThreadTextReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier
) {
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
                text = action.label.localized(),
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
    modifier: Modifier = Modifier,
    renderConfig: ThreadTimelineRenderConfig,
    timelineSlice: ThreadTimelineSlice,
    focusedMessageId: String? = null,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection(),
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    attachmentCommentsEnabled: Boolean = false,
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    markdownTagContextSignature: String = "",
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    activeMarkdownTagKey: String? = null,
    diagnosticsLogger: (String) -> Unit = {},
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMember: (String) -> Unit,
    onShowActions: (KuiklyThreadMessageItem, Offset) -> Unit,
    onOpenTaskStatusMenu: (KuiklyThreadMessageItem, Offset) -> Unit,
    onShowReactionDetails: ((KuiklyThreadReactionItem, Offset) -> Unit)? = null,
    onMentionAuthor: (KuiklyThreadMessageItem) -> Unit,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    shareSelectionActive: Boolean = false,
    selectedShareMessageIds: Set<String> = emptySet(),
    onToggleShareSelection: (String) -> Unit = {},
    longMessageExpanded: Boolean = false,
    onToggleLongMessage: (String) -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (timelineSlice.originStatus) {
            "loading" -> {
                Text(
                    text = stringResource(MR.strings.thread_state_refreshing_original),
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
                        text = renderConfig.originPreview?.ifBlank {
                            stringResource(MR.strings.thread_state_original_unavailable)
                        } ?: stringResource(MR.strings.thread_state_original_unavailable),
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack
                    )
                    renderConfig.originErrorCode?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = stringResource(
                                MR.strings.thread_error_refresh_original,
                                threadUserFacingErrorCodeLabel(it) ?: stringResource(MR.strings.common_error)
                            ),
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
                            markdownTagContextSignature = markdownTagContextSignature,
                            activeMarkdownTagKey = activeMarkdownTagKey,
                            taskOpenEnabled = timelineSlice.showTaskOpenActions && contentExtrasVisible,
                            taskStatusActionsEnabled = timelineSlice.showTaskStatusActions && contentExtrasVisible,
                            showSaveAction = timelineSlice.showMessageSaveAction,
                            showInlineSaveAction = timelineSlice.showInlineSaveAction,
                            isMarkdownTagValid = isMarkdownTagValid,
                            onShowActions = { anchor -> onShowActions(origin, anchor) },
                            onOpenMember = onOpenMember,
                            onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor -> onOpenTaskStatusMenu(origin, anchor) },
                            onToggleReaction = { emoji -> onThreadEvent("toggleReaction", origin.id, null, emoji) },
                            onShowReactionDetails = onShowReactionDetails,
                            showReactionAddAction = timelineSlice.showReactionActions && contentExtrasVisible,
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
                            richContentEnabled = contentExtrasVisible,
                            focused = focusedMessageId == origin.id,
                            longMessageCollapseEnabled = true,
                            longMessageExpanded = longMessageExpanded,
                            onToggleLongMessage = { onToggleLongMessage(origin.id) }
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

// React parity: MessageItem's senderSubtitle — 12px mono at 40% black,
// truncating flexibly between the name/badge and the timestamp.
@Composable
private fun ThreadMessageAuthorSubtitle(description: String, modifier: Modifier = Modifier) {
    Text(
        text = description,
        style =
            SlockType.Caption.copy(
                fontFamily = SlockMonoFamily,
                color = SlockColor.TextBlack.copy(alpha = 0.4f)
            ),
        color = SlockColor.TextBlack.copy(alpha = 0.4f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun ThreadMessageTimestampLabel(timestampLabel: String) {
    Text(
        text = timestampLabel,
        style =
            SlockType.Caption.copy(
                fontFamily = SlockMonoFamily,
                color = SlockColor.TextBlack.copy(alpha = 0.4f)
            ),
        color = SlockColor.TextBlack.copy(alpha = 0.4f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun localizedThreadTimestampLabel(value: String): String =
    if (value == "Now") {
        stringResource(MR.strings.thread_timestamp_now)
    } else {
        value
    }

@Composable
private fun ThreadMessageAvatarSlot(
    showAvatar: Boolean,
    showTimestamp: Boolean,
    timestampLabel: String,
    avatarLabel: String,
    avatarWireValue: String?,
    status: SlockAgentStatusIndicatorState?,
    statusPulsing: Boolean = true,
    muted: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val slotHeight =
        when {
            showAvatar -> 40.dp
            showTimestamp -> 18.dp
            else -> 0.dp
        }
    Box(
        modifier = Modifier.width(36.dp).height(slotHeight),
        contentAlignment = Alignment.TopCenter
    ) {
        when {
            showAvatar ->
                // React parity: MessageSenderAvatar carries mt-0.5 (2px) so the
                // avatar top optically aligns with the name glyphs.
                Box(modifier = Modifier.padding(top = 2.dp)) {
                    ThreadMessageAvatar(
                        avatarLabel = avatarLabel,
                        avatarWireValue = avatarWireValue,
                        status = status,
                        statusPulsing = statusPulsing,
                        muted = muted,
                        onClick = onClick,
                        onLongPress = onLongPress
                    )
                }
            showTimestamp ->
                Text(
                    text = timestampLabel,
                    style =
                        SlockType.Caption.copy(
                            fontFamily = SlockMonoFamily,
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
    status: SlockAgentStatusIndicatorState?,
    statusPulsing: Boolean = true,
    muted: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val avatarSource =
        remember(avatarWireValue) {
            avatarWireValue?.takeIf { it.isNotBlank() }
        }
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { currentOnClick() },
                        onLongPress = { currentOnLongPress() }
                    )
                },
        contentAlignment = Alignment.Center
    ) {
        SlockAvatarWithStatus(
            statusColor = if (muted) null else status?.color,
            avatarSize = 36.dp,
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
                    avatarSize = 36.dp,
                    borderWidth = SlockStroke.Default
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
    status: SlockAgentStatusIndicatorState?,
    statusPulsing: Boolean = true
) {
    val avatarSource =
        remember(avatarWireValue) {
            avatarWireValue?.takeIf { it.isNotBlank() }
        }
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatarWithStatus(
            statusColor = status?.color,
            avatarSize = 36.dp,
            // #90 React parity: bump chat thread avatar overlay status dot
            // 10dp → 11dp to match React `StatusDot size="lg"` (size-[11px]).
            statusSize = 11.dp,
            statusPulsing = status?.pulsing == true && statusPulsing
        ) {
            SlockAvatar(
                avatarWireValue = avatarSource,
                label = avatarLabel,
                avatarSize = 36.dp,
                borderWidth = SlockStroke.Default
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
    trim()
        .takeIf { it.isNotBlank() }
        ?.toThreadPixelAvatarSpecFromNormalized()

private fun String.toThreadPixelAvatarSpecFromNormalized(): ThreadPixelAvatarSpec? =
    removeThreadPrefixOrNull(ThreadPixelAvatarProtocolPrefix)?.let { key ->
        key.removeThreadPrefixOrNull(ThreadPixelAvatarRandomPrefix)?.let { seed ->
            return seed.takeIf { it.isNotBlank() }?.let(::randomThreadPixelAvatar)
        }
        ThreadPixelAvatarFixedSpecs[key] ?: ThreadPixelAvatarFixedSpecs.getValue(ThreadPixelAvatarDefaultKey)
    }

private fun String.removeThreadPrefixOrNull(prefix: String): String? =
    takeIf { startsWith(prefix) }?.removePrefix(prefix)

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
        KuiklyThreadSurfaceKind.Channel -> source.routeKind == KuiklyThreadSurfaceKind.Channel || source.routeKind == KuiklyThreadSurfaceKind.DirectMessage
        KuiklyThreadSurfaceKind.ReplyThread ->
            (source.routeKind == KuiklyThreadSurfaceKind.Channel || source.routeKind == KuiklyThreadSurfaceKind.DirectMessage) &&
                (!source.parentMessageId.isNullOrBlank() || !source.rootThreadChannelId.isNullOrBlank())
        else -> false
    }
}

@Composable
private fun ThreadInlineMetaTag(label: String) {
    Box(
        modifier =
            Modifier
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceCream)
                .padding(horizontal = ThreadInlineMetaTagHorizontalPadding, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Keep Anchor/Quote tag text aligned with SlockBrutalTextField's default horizontalPadding.
private val ThreadInlineMetaTagHorizontalPadding: Dp = 10.dp

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
    slockFilePreviewIsImageAttachment(fileName = name, mimeType = mimeType)

private fun KuiklyThreadAttachmentItem.isPdfAttachment(): Boolean =
    mimeType.substringBefore(';').trim().equals("application/pdf", ignoreCase = true)

private fun KuiklyThreadAttachmentItem.normalizedMimeType(): String =
    mimeType.substringBefore(';').trim().lowercase()

internal fun KuiklyThreadAttachmentItem.isMarkdownAttachment(): Boolean =
    slockFilePreviewIsMarkdownAttachment(fileName = name, mimeType = mimeType)

internal fun KuiklyThreadAttachmentItem.isTextLikeAttachment(): Boolean =
    !isMarkdownAttachment() && slockFilePreviewIsTextLikeAttachment(fileName = name, mimeType = mimeType)

internal fun KuiklyThreadAttachmentItem.isMp4VideoAttachment(): Boolean =
    slockFilePreviewIsMp4VideoAttachment(fileName = name, mimeType = mimeType)

private fun KuiklyThreadAttachmentItem.isPreviewableDocumentAttachment(): Boolean =
    isPdfAttachment() ||
        isMarkdownAttachment() ||
        isTextLikeAttachment()

internal fun KuiklyThreadAttachmentItem.isHtmlAttachment(): Boolean =
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
        requestedKind = kind,
        title = title,
        subtitle = name.ifBlank { "Attachment" },
        name = name.ifBlank { "Attachment" },
        path = name,
        meta = genericAttachmentMetaLine(),
        content = content,
        markdown = kind == "markdown",
        imageUrl = imageUrl,
        message = message,
        messageDesc = null,
        error = error,
        externalUrl = externalUrl,
        externalFileName = name.ifBlank { "attachment-${id.take(8)}" },
        externalMimeType = mimeType
    )

private fun PlatformFile.composerLocalPreviewUri(): String? =
    fileRef?.takeIf { it.isNotBlank() }
        ?: previewUri?.takeIf { it.isNotBlank() }

internal fun KuiklyThreadAttachmentItem.threadAttachmentCommentCountLabel(): String? =
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
    if (attachment.isMp4VideoAttachment()) {
        ThreadInlineVideoAttachmentCard(
            attachment = attachment,
            commentsEnabled = commentsEnabled,
            onOpenPreview = onOpen,
            onOpenComments = onOpenComments
        )
        return
    }
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
internal fun ThreadAttachmentCommentsDialog(
    state: ThreadAttachmentCommentsDialogState,
    onDismiss: () -> Unit,
    onFilterChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onResolve: (ThreadAttachmentCommentItem) -> Unit,
    onQuote: (ThreadAttachmentCommentItem) -> Unit
) {
    SlockUnifiedDialog(
        title = stringResource(MR.strings.thread_comments_title),
        onDismiss = onDismiss,
        minWidth = 312.dp,
        maxWidth = 360.dp,
        contentPadding = 18.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        contentSpacing = 12.dp
    ) {
        ThreadAttachmentCommentsContent(
            state = state,
            onFilterChange = onFilterChange,
            onDraftChange = onDraftChange,
            onRetry = onRetry,
            onSend = onSend,
            onResolve = onResolve,
            onQuote = onQuote
        )
    }
}

@Composable
internal fun ThreadAttachmentCommentsContent(
    state: ThreadAttachmentCommentsDialogState,
    onFilterChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onResolve: (ThreadAttachmentCommentItem) -> Unit,
    onQuote: (ThreadAttachmentCommentItem) -> Unit,
    containerTapRequestsFocus: Boolean = false,
    compact: Boolean = false
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
    val commentsListHeight =
        if (compact) {
            when {
                comments.isEmpty() -> 84.dp
                comments.size == 1 -> 150.dp
                else -> 180.dp
            }
        } else {
            threadAttachmentCommentsListHeight(comments.size)
        }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThreadAttachmentCommentsHeader(state.attachment)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThreadAttachmentCommentsFilterChip(
                label = stringResource(MR.strings.thread_comments_filter_all),
                selected = state.filter != "unresolved",
                onClick = { onFilterChange("all") }
            )
            ThreadAttachmentCommentsFilterChip(
                label = stringResource(MR.strings.thread_comments_filter_unresolved),
                selected = state.filter == "unresolved",
                onClick = { onFilterChange("unresolved") }
            )
        }
        when (state.status) {
            "loading" ->
                ThreadStateMessage(stringResource(MR.strings.thread_comments_loading), null)
            "error" ->
                ThreadStateActionMessage(
                    title = stringResource(MR.strings.thread_comments_load_failed),
                    detail = threadUserFacingErrorCodeLabel(state.errorCode) ?: stringResource(MR.strings.common_error),
                    actionLabel = stringResource(MR.strings.common_retry),
                    onClick = onRetry
                )
            else ->
                SlockModalListShell(height = commentsListHeight) {
                    if (comments.isEmpty()) {
                        item {
                            ThreadModalEmptyState(
                                title = stringResource(MR.strings.thread_state_no_comments),
                                detail =
                                    if (state.filter == "unresolved") {
                                        stringResource(MR.strings.thread_comments_all_resolved)
                                    } else {
                                        stringResource(MR.strings.thread_comments_start_below)
                                    }
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
                text = threadUserFacingErrorCodeLabel(code)?.uppercase() ?: stringResource(MR.strings.common_error),
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
            onSend = onSend,
            containerTapRequestsFocus = containerTapRequestsFocus,
            compact = compact
        )
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
                text = comment.senderName.ifBlank { stringResource(MR.strings.thread_comments_unknown_author) },
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            comment.createdAt.takeIf { it.isNotBlank() }?.let { createdAt ->
                Text(
                    text = createdAt,
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        comment.anchorLabel?.takeIf { it.isNotBlank() }?.let { label ->
            ThreadInlineMetaTag(stringResource(MR.strings.thread_comments_anchor, label))
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
                label = stringResource(MR.strings.thread_action_quote),
                icon = SlockLucideIconName.MessageSquarePlus,
                enabled = canComment && comment.content.isNotBlank(),
                onClick = onQuote
            )
            ThreadAttachmentCommentTinyButton(
                label =
                    when {
                        resolved -> stringResource(MR.strings.thread_comments_resolved)
                        resolving -> stringResource(MR.strings.thread_comments_resolving)
                        else -> stringResource(MR.strings.thread_comments_resolve)
                    },
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
    onSend: () -> Unit,
    containerTapRequestsFocus: Boolean = false,
    compact: Boolean = false
) {
    val enabled = canComment && state.status == "loaded" && !state.sending
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            state.pendingAnchorLabel?.takeIf { it.isNotBlank() }?.let { label ->
                ThreadInlineMetaTag(stringResource(MR.strings.thread_comments_anchor, label))
            }
            state.pendingQuoteLabel?.takeIf { it.isNotBlank() }?.let { label ->
                ThreadInlineMetaTag(stringResource(MR.strings.thread_comments_quote, label))
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
            label = stringResource(MR.strings.thread_comments_label),
            value = state.draft,
            placeholder = stringResource(MR.strings.thread_comments_placeholder),
            enabled = enabled,
            maxLines = if (compact) 3 else 4,
            fieldMinHeight = if (compact) 64.dp else 82.dp,
            containerTapRequestsFocus = containerTapRequestsFocus,
            onValueChange = onDraftChange
        )
        SlockModalFullWidthActionButton(
            label =
                if (state.sending) {
                    stringResource(MR.strings.thread_comments_sending)
                } else {
                    stringResource(MR.strings.thread_comments_send)
                },
            icon = if (state.sending) SlockLucideIconName.RefreshCw else SlockLucideIconName.Send,
            enabled = enabled && state.draft.isNotBlank(),
            color = SlockColor.AccentCyan,
            iconSpinning = state.sending,
            onClick = onSend
        )
    }
}

internal fun ThreadAttachmentCommentItem.isThreadAttachmentCommentResolved(): Boolean =
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

@Composable
private fun threadAttachmentCommentBlockedCopy(reason: String?): String =
    when (reason?.trim()?.lowercase()) {
        "archived" -> stringResource(MR.strings.thread_comments_blocked_archived)
        "not_member" -> stringResource(MR.strings.thread_comments_blocked_not_member)
        "read_only" -> stringResource(MR.strings.thread_comments_blocked_read_only)
        "unlinked" -> stringResource(MR.strings.thread_comments_blocked_unlinked)
        else -> stringResource(MR.strings.thread_comments_blocked_unavailable)
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
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    timeContext: SlockTimeContext? = null,
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
        draftProjection = draftProjection,
        timeContext = timeContext,
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
private fun ThreadForwardBundleCard(
    bundle: ForwardMessageBundle,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    Button(
        onClick = onOpen,
        pressFeedback = false,
        modifier = modifier
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.width(3.dp).height(20.dp).background(SlockColor.BrandYellow))
                Text(
                    text = bundle.localizedForwardTitle(),
                    style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 15.sp, lineHeight = 19.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SlockLucideIcon(SlockLucideIconName.ChevronRight, modifier = Modifier.size(15.dp), color = "#FF000000")
            }
            bundle.messages.take(4).forEach { item ->
                Text(
                    text = item.localizedForwardPreviewLine(),
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val hiddenCount = (bundle.displayCount() - bundle.messages.take(4).size).coerceAtLeast(0)
            if (hiddenCount > 0) {
                Text(
                    text = stringResource(MR.strings.thread_more_messages, hiddenCount),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ThreadForwardBundleModal(
    bundle: ForwardMessageBundle,
    memberAvatarResolver: SlockMemberAvatarResolver,
    onDismiss: () -> Unit,
    onOpenMember: (ForwardMessageItem) -> Unit
) {
    SlockUnifiedDialog(
        title = bundle.localizedForwardTitle(),
        onDismiss = onDismiss,
        minWidth = 320.dp,
        maxWidth = 420.dp,
        contentPadding = 0.dp,
        headerPadding = 16.dp,
        titleFontSize = 16,
        titleLineHeight = 20,
        contentSpacing = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    if (bundle.displayCount() == 1) {
                        stringResource(MR.strings.thread_message_count_one)
                    } else {
                        stringResource(MR.strings.thread_message_count_many, bundle.displayCount())
                    },
                // Forward-bundle count is content, not tabular metadata: match the react
                // ForwardedBundleCard convention (proportional/bold count; Space Mono is
                // reserved for the per-row timestamp only). See visual case components.thread.forward-bundle.
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            )
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(bundle.messages.size, key = { bundle.messages[it].id }) { index ->
                        ThreadForwardBundleMessageRow(
                            item = bundle.messages[index],
                            memberAvatarResolver = memberAvatarResolver,
                            onOpenMember = onOpenMember
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadForwardBundleMessageRow(
    item: ForwardMessageItem,
    memberAvatarResolver: SlockMemberAvatarResolver,
    onOpenMember: (ForwardMessageItem) -> Unit
) {
    val avatarWireValue =
        rememberSlockMemberAvatarWireValue(memberAvatarResolver, item.authorId)
            ?: item.avatarWireValue
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = { onOpenMember(item) },
            pressFeedback = false,
            modifier = Modifier.size(32.dp).background(Color.Transparent)
        ) {
            SlockAvatar(
                avatarWireValue = avatarWireValue,
                label = item.avatarLabel.ifBlank { item.authorName.take(1) },
                avatarSize = 32.dp
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.authorName.ifBlank { stringResource(MR.strings.thread_comments_unknown_author) },
                    style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenMember(item) }.weight(1f, fill = false)
                )
                item.timestampLabel.takeIf { it.isNotBlank() }?.let { timestamp ->
                    Text(
                        text = timestamp,
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 10.sp, lineHeight = 13.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (item.body.isNotBlank()) {
                Text(
                    text = item.body,
                    style = ThreadMessageBodyTextStyle,
                    color = SlockColor.TextBlack
                )
            }
            if (item.attachmentCount > 0) {
                Text(
                    text = item.localizedForwardAttachmentLabel(),
                    // Attachment label is content (react renders per-file chips, non-mono);
                    // keep it proportional. Space Mono stays on the timestamp only.
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

private fun ForwardMessageBundle.forwardTitle(): String =
    title.takeIf { it.isNotBlank() }
        ?: sourceTitle.takeIf { it.isNotBlank() }?.let { "$it chat history" }
        ?: "Chat history"

@Composable
private fun ForwardMessageBundle.localizedForwardTitle(): String =
    title.takeIf { it.isNotBlank() }
        ?: sourceTitle.takeIf { it.isNotBlank() }?.let {
            stringResource(MR.strings.thread_forward_chat_history_named, it)
        }
        ?: stringResource(MR.strings.thread_forward_chat_history)

private fun ForwardMessageBundle.displayCount(): Int =
    totalCount?.takeIf { it > 0 } ?: messages.size

private fun ForwardMessageItem.forwardPreviewLine(): String {
    val text = body.trim().ifBlank { forwardAttachmentLabel() }
    return "${authorName.ifBlank { "Unknown" }}: $text"
}

@Composable
private fun ForwardMessageItem.localizedForwardPreviewLine(): String {
    val text = body.trim().ifBlank { localizedForwardAttachmentLabel() }
    val author = authorName.ifBlank { stringResource(MR.strings.thread_comments_unknown_author) }
    return "$author: $text"
}

private fun ForwardMessageItem.forwardAttachmentLabel(): String =
    when (attachmentCount) {
        0 -> ""
        1 -> "[1 attachment]"
        else -> "[$attachmentCount attachments]"
    }

@Composable
private fun ForwardMessageItem.localizedForwardAttachmentLabel(): String =
    when (attachmentCount) {
        0 -> ""
        1 -> stringResource(MR.strings.thread_forward_attachment_one)
        else -> stringResource(MR.strings.thread_forward_attachment_many, attachmentCount)
    }

private fun ForwardMessageItem.forwardMemberKind(): MemberKind =
    when (authorKind.trim().lowercase()) {
        "agent" -> MemberKind.Agent
        "system" -> MemberKind.System
        else -> MemberKind.Human
    }

private enum class ThreadForwardSelectionMode(val wireValue: String, val label: String) {
    Merged("merged", "Merged"),
    Individual("individual", "Individual")
}

private fun ThreadForwardSelectionMode.toForwardMessageSendMode(): ForwardMessageSendMode =
    when (this) {
        ThreadForwardSelectionMode.Merged -> ForwardMessageSendMode.Merged
        ThreadForwardSelectionMode.Individual -> ForwardMessageSendMode.Individual
    }

private fun String?.threadForwardSelectionModeLabel(): String =
    when (this?.trim()?.lowercase()) {
        ThreadForwardSelectionMode.Individual.wireValue -> ThreadForwardSelectionMode.Individual.label
        else -> ThreadForwardSelectionMode.Merged.label
    }

private data class ThreadForwardTargetItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: ThreadForwardTargetKind,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.take(1).uppercase(),
    val forwardTarget: ForwardMessageTarget? = null
)

private enum class ThreadForwardTargetKind {
    Channel,
    DirectMessage,
    PublicChannelUnjoined,
    Member
}

private fun ForwardMessageTarget.toThreadForwardTargetItem(): ThreadForwardTargetItem =
    ThreadForwardTargetItem(
        id = "${kind.name}:$id",
        title = title,
        subtitle = threadForwardTargetSubtitle(),
        kind =
            when (kind) {
                ForwardMessageTargetKind.RecentChannel -> ThreadForwardTargetKind.Channel
                ForwardMessageTargetKind.RecentDirectMessage -> ThreadForwardTargetKind.DirectMessage
                ForwardMessageTargetKind.PublicChannelUnjoined -> ThreadForwardTargetKind.PublicChannelUnjoined
                ForwardMessageTargetKind.HumanDirectMessageCandidate,
                ForwardMessageTargetKind.AgentDirectMessageCandidate ->
                    ThreadForwardTargetKind.Member
            },
        avatarWireValue = avatarWireValue,
        avatarLabel = avatarLabel,
        forwardTarget = this
    )

private fun ForwardMessageTarget.threadForwardTargetSubtitle(): String =
    when (requiredAction) {
        ForwardMessageTargetRequiredAction.JoinChannel ->
            subtitle.ifBlank { "Join channel before forwarding" }
        ForwardMessageTargetRequiredAction.CreateDirectMessage ->
            subtitle.ifBlank { "Start DM before forwarding" }
        ForwardMessageTargetRequiredAction.None ->
            subtitle.ifBlank {
                when (kind) {
                    ForwardMessageTargetKind.RecentChannel -> "Recent channel"
                    ForwardMessageTargetKind.RecentDirectMessage -> "Recent DM"
                    ForwardMessageTargetKind.PublicChannelUnjoined -> "Public channel"
                    ForwardMessageTargetKind.HumanDirectMessageCandidate -> "Member"
                    ForwardMessageTargetKind.AgentDirectMessageCandidate -> "Agent"
                }
            }
    }

@Composable
private fun ThreadForwardTargetItem.localizedForwardTargetSubtitle(): String =
    when (subtitle) {
        "Join channel before forwarding" -> stringResource(MR.strings.thread_forward_join_channel_first)
        "Start DM before forwarding" -> stringResource(MR.strings.thread_forward_start_dm_first)
        "Recent channel" -> stringResource(MR.strings.thread_forward_recent_channel)
        "Recent DM" -> stringResource(MR.strings.thread_forward_recent_dm)
        "Public channel" -> stringResource(MR.strings.thread_forward_public_channel)
        "Member" -> stringResource(MR.strings.thread_forward_member)
        "Agent" -> stringResource(MR.strings.thread_forward_agent)
        else -> subtitle
    }

// Visual-testing fixture (task #560): renders the Forward target modal with a
// seeded destination list so the header/content alignment (title + close vs the
// 16dp-inset rows below) is captured and can't regress unseen.
@Page("compose_thread_forward_modal_visual", supportInLocal = true)
internal class ComposeThreadForwardModalVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadForwardModalVisualScreen() }
    }
}

@Composable
internal fun ThreadForwardModalVisualScreen() {
    val targets =
        remember {
            listOf(
                ThreadForwardTargetItem(
                    id = "channel-design",
                    title = "design",
                    subtitle = "Product and UI decisions",
                    kind = ThreadForwardTargetKind.Channel
                ),
                ThreadForwardTargetItem(
                    id = "dm-artin",
                    title = "artin",
                    subtitle = "Direct message",
                    kind = ThreadForwardTargetKind.DirectMessage
                ),
                ThreadForwardTargetItem(
                    id = "channel-android",
                    title = "android-artifacts",
                    subtitle = "Public channel",
                    kind = ThreadForwardTargetKind.PublicChannelUnjoined
                ),
                ThreadForwardTargetItem(
                    id = "member-cindy",
                    title = "Cindy",
                    subtitle = "Keeps visual testing release notes aligned",
                    kind = ThreadForwardTargetKind.Member
                )
            )
        }
    ThreadForwardTargetModal(
        selectedCount = 2,
        skippedCount = 0,
        nestedForwardCount = 0,
        query = "",
        loading = false,
        targets = targets,
        selectedTargetId = "channel-design",
        errorCode = null,
        sendingTargetId = null,
        note = "",
        onQueryChange = {},
        onNoteChange = {},
        onDismiss = {},
        onSelectTarget = {},
        onSend = {}
    )
}

@Page("compose_thread_comment_anchor_visual", supportInLocal = true)
internal class ComposeThreadCommentAnchorVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadCommentAnchorVisualScreen() }
    }
}

@Composable
internal fun ThreadCommentAnchorVisualScreen() {
    var state by
        remember {
            mutableStateOf(
                ThreadAttachmentCommentsDialogState(
                    attachment =
                        KuiklyThreadAttachmentItem(
                            id = "att-anchor-1",
                            name = "agent-tabs-full-page-review.md",
                            mimeType = "text/markdown",
                            sizeLabel = "23.0 KB"
                        ),
                    status = "loaded",
                    page =
                        ThreadAttachmentCommentsPage(
                            comments =
                                listOf(
                                    ThreadAttachmentCommentItem(
                                        id = "comment-anchor-1",
                                        channelId = "channel-anchor-1",
                                        senderType = "user",
                                        senderId = "user-anchor-2",
                                        senderName = "Artea",
                                        content = "Please keep the tag inset consistent with the field below.",
                                        createdAt = "18:32",
                                        reactions = emptyList(),
                                        anchorLabel = "agent-tabs-full-page-review.md"
                                    )
                                ),
                            threadChannelId = null,
                            viewer = null
                        ),
                    pendingAnchorLabel = "agent-tabs-full-page-review.md"
                )
            )
        }
    SlockFilePreviewPageScaffold(
        title = "FILE PREVIEW",
        subtitle = "agent-tabs-full-page-review.md",
        iconName = SlockLucideIconName.FileText,
        onBack = {},
        contentPadding = 0.dp
    ) {
        SlockFilePreviewPayloadContent(
            payload =
                SlockFilePreviewPayload(
                    kind = "markdown",
                    title = "FILE PREVIEW",
                    name = "agent-tabs-full-page-review.md",
                    content = "# Review\n\nKeep attachment comments on this standalone preview surface.",
                    markdown = true
                ),
            onOpenUrl = {},
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        SlockFilePreviewAttachmentCommentsPanel(
            state = state,
            onClose = {},
            onFilterChange = { state = state.copy(filter = it) },
            onDraftChange = { state = state.copy(draft = it) },
            onRetry = {},
            onSend = {},
            onResolve = {},
            onQuote = {}
        )
    }
}

@Page("compose_thread_forward_bundle_visual", supportInLocal = true)
internal class ComposeThreadForwardBundleVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadForwardBundleVisualScreen() }
    }
}

@Composable
internal fun ThreadForwardBundleVisualScreen() {
    // Renders ThreadForwardBundleModal so the forward-bundle metadata is pinned
    // by a fixture (task #427). Space Mono is reserved for the per-row timestamp
    // only, matching the react ForwardedBundleCard convention; the "N messages"
    // count and the attachment label are proportional content (react renders the
    // count in bold and attachments as per-file chips, none of them mono).
    // Seeded with a mixed bundle (with/without body, with/without attachments) so
    // the count, timestamp, and attachment-label paths all render.
    val bundle =
        remember {
            ForwardMessageBundle(
                id = "bundle-visual-1",
                title = "",
                sourceTitle = "#design",
                totalCount = 3,
                messages =
                    listOf(
                        ForwardMessageItem(
                            id = "fwd-1",
                            authorId = "user-cindy",
                            authorName = "Cindy",
                            authorKind = "agent",
                            avatarLabel = "C",
                            timestampLabel = "10:38",
                            body = "Captured the Android visual artifact and queued React parity.",
                            attachmentCount = 0
                        ),
                        ForwardMessageItem(
                            id = "fwd-2",
                            authorId = "user-artin",
                            authorName = "artin",
                            authorKind = "human",
                            avatarLabel = "a",
                            timestampLabel = "10:31",
                            body = "",
                            attachmentCount = 2
                        ),
                        ForwardMessageItem(
                            id = "fwd-3",
                            authorId = "user-ad",
                            authorName = "Android Developer",
                            authorKind = "agent",
                            avatarLabel = "A",
                            timestampLabel = "09:58",
                            body = "Search parity now covers sender filters and time ranges.",
                            attachmentCount = 1
                        )
                    )
            )
        }
    ThreadForwardBundleModal(
        bundle = bundle,
        memberAvatarResolver = EmptySlockMemberAvatarResolver,
        onDismiss = {},
        onOpenMember = {}
    )
}

@Composable
private fun ThreadForwardTargetModal(
    selectedCount: Int,
    skippedCount: Int,
    nestedForwardCount: Int,
    query: String,
    loading: Boolean,
    targets: List<ThreadForwardTargetItem>,
    selectedTargetId: String?,
    errorCode: String?,
    sendingTargetId: String?,
    note: String,
    onQueryChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectTarget: (ThreadForwardTargetItem) -> Unit,
    onSend: () -> Unit
) {
    SlockUnifiedDialog(
        title = stringResource(MR.strings.thread_action_forward),
        onDismiss = onDismiss,
        minWidth = 320.dp,
        maxWidth = 430.dp,
        contentPadding = 0.dp,
        headerPadding = 16.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        contentSpacing = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(MR.strings.thread_forward_selected, selectedCount),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            threadForwardSkippedWarning(skippedCount, nestedForwardCount)?.let { warning ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                            .background(SlockColor.BrandYellow.copy(alpha = 0.18f))
                            .border(SlockStroke.Default, SlockColor.BrandYellow)
                            .padding(10.dp)
                ) {
                    Text(
                        text = warning,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = SlockColor.TextBlack
                    )
                }
            }
            SharedHomeSearchInput(
                query = query,
                loading = loading,
                interactive = true,
                onQueryChange = onQueryChange,
                onSubmit = {},
                onClear = { onQueryChange("") },
                placeholder = stringResource(MR.strings.thread_action_search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text =
                    if (query.isBlank()) {
                        stringResource(MR.strings.thread_forward_destinations)
                    } else {
                        stringResource(MR.strings.thread_forward_search_results)
                    },
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
            )
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp)) {
                if (targets.isEmpty()) {
                    ThreadModalEmptyState(
                        title =
                            when {
                                loading -> stringResource(MR.strings.thread_forward_loading_targets)
                                errorCode != null -> stringResource(MR.strings.thread_forward_targets_unavailable)
                                query.isBlank() -> stringResource(MR.strings.thread_forward_no_recent_targets)
                                else -> stringResource(MR.strings.thread_forward_no_targets)
                            },
                        detail =
                            when {
                                loading -> stringResource(MR.strings.thread_forward_fetching_targets)
                                errorCode != null ->
                                    threadUserFacingErrorCodeLabel(errorCode)?.uppercase()
                                        ?: stringResource(MR.strings.common_error)
                                query.isBlank() -> stringResource(MR.strings.thread_forward_recent_targets_hint)
                                else -> stringResource(MR.strings.thread_add_member_try_different_search)
                            }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(targets.size, key = { targets[it].id }) { index ->
                            ThreadForwardTargetRow(
                                target = targets[index],
                                selected = selectedTargetId == targets[index].id,
                                sending = sendingTargetId == targets[index].id,
                                onClick = { onSelectTarget(targets[index]) }
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SlockColor.BorderBlack.copy(alpha = 0.12f)))
            SlockBrutalTextField(
                label = stringResource(MR.strings.thread_state_note),
                value = note,
                placeholder =
                    if (selectedCount == 1) {
                        stringResource(MR.strings.thread_forward_placeholder_one)
                    } else {
                        stringResource(MR.strings.thread_forward_placeholder_many, selectedCount)
                    },
                maxLines = 2,
                fieldMinHeight = 62.dp,
                onValueChange = onNoteChange,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Design-system brutal-shadow action buttons (task #560): the raw
                // Button+border above had no drop shadow, unlike the rest of the app.
                SlockModalActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    onClick = onDismiss
                )
                SlockModalActionButton(
                    label =
                        if (sendingTargetId == null) {
                            stringResource(MR.strings.common_send)
                        } else {
                            stringResource(MR.strings.thread_forward_sending)
                        },
                    icon = if (sendingTargetId == null) SlockLucideIconName.Send else SlockLucideIconName.RefreshCw,
                    onClick = onSend,
                    color = SlockColor.AccentPink,
                    enabled = selectedTargetId != null && sendingTargetId == null
                )
            }
        }
    }
}

@Composable
private fun ThreadForwardTargetRow(
    target: ThreadForwardTargetItem,
    selected: Boolean,
    sending: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        pressFeedback = false,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (target.kind) {
                ThreadForwardTargetKind.Channel,
                ThreadForwardTargetKind.PublicChannelUnjoined,
                ThreadForwardTargetKind.DirectMessage ->
                    Box(
                        modifier =
                            Modifier
                                .size(38.dp)
                                .background(SlockColor.SurfaceCream)
                                .border(SlockStroke.Default, SlockColor.BrandYellow),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockLucideIcon(
                            when (target.kind) {
                                ThreadForwardTargetKind.Channel,
                                ThreadForwardTargetKind.PublicChannelUnjoined ->
                                    SlockLucideIconName.Hash
                                else -> SlockLucideIconName.MessageCircle
                            },
                            modifier = Modifier.size(18.dp),
                            color = "#FF111111"
                        )
                    }
                ThreadForwardTargetKind.Member ->
                    SlockAvatar(
                        avatarWireValue = target.avatarWireValue,
                        label = target.avatarLabel,
                        avatarSize = 38.dp,
                        borderWidth = 0.dp
                    )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = target.title,
                    style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 21.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (target.subtitle.isNotBlank()) {
                    Text(
                    text = target.localizedForwardTargetSubtitle(),
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (sending) {
                Text(
                    text = stringResource(MR.strings.thread_forward_sending),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            } else if (selected) {
                SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(16.dp), color = "#FF111111")
            }
        }
    }
}

@Composable
private fun ThreadShareSelectionToolbar(
    selectedCount: Int,
    selectableCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onForward: () -> Unit,
    onExportImage: () -> Unit,
    onCopyLinks: () -> Unit
) {
    val hasSelection = selectedCount > 0
    val bottomSafeArea = slockBottomSafeAreaPadding()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.BrandYellow)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + bottomSafeArea),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(MR.strings.thread_forward_selected, selectedCount),
                style = SlockType.Caption.copy(
                    fontFamily = slockMonoFamilyForWeight(FontWeight.Bold),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = SlockColor.TextBlack.copy(alpha = 0.70f),
                maxLines = 1
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.X,
                    onClick = onCancel,
                    size = 28.dp,
                    shadowOffset = SlockShadow.BrutalSmall,
                    iconSize = 14.dp
                )
                ThreadShareSelectionActionButton(
                    label = stringResource(MR.strings.thread_comments_filter_all),
                    icon = SlockLucideIconName.Check,
                    onClick = onSelectAll,
                    enabled = hasSelection && selectedCount < selectableCount
                )
                ThreadShareSelectionActionButton(
                    label = stringResource(MR.strings.thread_action_forward),
                    icon = SlockLucideIconName.Send,
                    onClick = onForward,
                    enabled = hasSelection
                )
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Copy,
                    onClick = onCopyLinks,
                    enabled = hasSelection,
                    size = 28.dp,
                    shadowOffset = SlockShadow.BrutalSmall,
                    iconSize = 14.dp
                )
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Download,
                    onClick = onExportImage,
                    enabled = hasSelection,
                    size = 28.dp,
                    shadowOffset = SlockShadow.BrutalSmall,
                    iconSize = 14.dp,
                    backgroundColor = SlockColor.AccentPink,
                    disabledBackgroundColor = SlockColor.AccentPink
                )
            }
        }
    }
}

@Composable
private fun ThreadShareSelectionActionButton(
    label: String,
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = false,
        modifier =
            Modifier
                .height(28.dp)
                .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SlockLucideIcon(icon, modifier = Modifier.size(13.dp), color = "#FF111111")
            Text(
                text = label,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
        title = stringResource(MR.strings.thread_share_preview_title),
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
                    title = stringResource(MR.strings.thread_share_export_failed),
                    detail = stringResource(MR.strings.thread_share_export_failed_detail)
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
                        contentDescription = stringResource(MR.strings.thread_share_image_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                SlockModalActionButton(
                    label = stringResource(MR.strings.thread_share_copy_markdown),
                    icon = SlockLucideIconName.Copy,
                    onClick = onCopyMarkdown,
                    enabled = messages.isNotEmpty()
                )
                SlockModalActionButton(
                    label = stringResource(MR.strings.thread_share_download),
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
    val removeFailedLabel = stringResource(MR.strings.thread_members_remove_failed)
    val genericErrorLabel = stringResource(MR.strings.common_error)
    val removeErrorText =
        when (removeMemberStatus) {
            "failed" ->
                "$removeFailedLabel: " +
                    (threadUserFacingErrorCodeLabel(removeMemberErrorCode ?: "member_remove_failed")
                        ?: genericErrorLabel).uppercase()
            "no_permission" -> stringResource(MR.strings.thread_members_remove_no_permission)
            else -> null
        }
    val resolvedMemberCount = membersCount.takeIf { it > 0 } ?: members.size
    SlockUnifiedDialog(
        title = stringResource(MR.strings.thread_members_title, resolvedMemberCount),
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
                            title = stringResource(MR.strings.thread_state_no_members),
                            detail = stringResource(MR.strings.thread_members_refresh_detail)
                        )
                    }
                } else {
                    if (agents.isNotEmpty()) {
                        item { SlockModalSectionHeader(stringResource(MR.strings.thread_members_agents)) }
                        items(agents.size, key = { agents[it].memberKey }) { index ->
                            val member = agents[index]
                            val removing = removeMemberStatus == "removing" && removingMemberId == member.id
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.localizedModalRoleLabel(),
                                sourceBadgeLabel = member.sourceServerLabel,
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                enabled = !removing,
                                trailingIcon = SlockLucideIconName.X.takeIf { member.canRemove && !removing },
                                trailingText = if (removing) stringResource(MR.strings.thread_members_removing) else "",
                                onTrailingClick = if (member.canRemove && !removing) ({ onRemoveMember(member.id) }) else null,
                                onClick = { onOpenMember(member) }
                            )
                        }
                    }
                    if (humans.isNotEmpty()) {
                        item { SlockModalSectionHeader(stringResource(MR.strings.thread_members_humans)) }
                        items(humans.size, key = { humans[it].memberKey }) { index ->
                            val member = humans[index]
                            val removing = removeMemberStatus == "removing" && removingMemberId == member.id
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.localizedModalRoleLabel(),
                                sourceBadgeLabel = member.sourceServerLabel,
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                enabled = !removing,
                                trailingIcon = SlockLucideIconName.X.takeIf { member.canRemove && !removing },
                                trailingText = if (removing) stringResource(MR.strings.thread_members_removing) else "",
                                onTrailingClick = if (member.canRemove && !removing) ({ onRemoveMember(member.id) }) else null,
                                onClick = { onOpenMember(member) }
                            )
                        }
                    }
                }
            }
            SlockModalFullWidthActionButton(
                label = stringResource(MR.strings.thread_action_add_member),
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
    val filteredCandidates =
        filterPeopleSuggestions(
            query = query,
            suggestions = candidates,
            handle = { member -> member.handle },
            displayName = { member -> member.displayName },
            description = { member -> member.roleLabel },
            sourceServerLabel = { member -> member.sourceServerLabel },
            legacyId = { member -> member.id }
        )
            .distinctBy { it.memberKey }
    val agentCandidates = filteredCandidates.filter { it.kind.equals("agent", ignoreCase = true) }
    val humanCandidates = filteredCandidates.filter { !it.kind.equals("agent", ignoreCase = true) }
    val blocked = !canAddMembers || addMemberStatus == "no_permission"
    val adding = addMemberStatus == "adding"
    val addFailedLabel = stringResource(MR.strings.thread_add_member_failed)
    val genericErrorLabel = stringResource(MR.strings.common_error)
    val errorText =
        when {
            blocked -> stringResource(MR.strings.thread_add_member_no_permission)
            localError -> stringResource(MR.strings.thread_add_member_select_before_adding)
            addMemberStatus == "failed" ->
                "$addFailedLabel: " +
                    (threadUserFacingErrorCodeLabel(addMemberErrorCode ?: "member_add_failed")
                        ?: genericErrorLabel).uppercase()
            else -> null
        }
    SlockUnifiedDialog(
        title = stringResource(MR.strings.thread_action_add_member),
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
                text = stringResource(MR.strings.thread_add_member_search),
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            SlockBrutalSearchField(
                value = query,
                placeholder = stringResource(MR.strings.thread_add_member_name_placeholder),
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
                            title =
                                if (candidates.isEmpty()) {
                                    stringResource(MR.strings.thread_add_member_no_people_loaded)
                                } else {
                                    stringResource(MR.strings.thread_add_member_no_matching_people)
                                },
                            detail =
                                if (candidates.isEmpty()) {
                                    stringResource(MR.strings.thread_members_refresh_detail)
                                } else {
                                    stringResource(MR.strings.thread_add_member_try_different_search)
                                }
                        )
                    }
                } else {
                    if (agentCandidates.isNotEmpty()) {
                        item { SlockModalSectionHeader(stringResource(MR.strings.thread_members_agents)) }
                        items(agentCandidates.size, key = { agentCandidates[it].memberKey }) { index ->
                            val member = agentCandidates[index]
                            val alreadyJoined = member.memberKey in currentKeys
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.localizedModalRoleLabel(),
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (showSelectedCheckmark && member.id == selectedId) SlockLucideIconName.Check else null,
                                trailingText = if (alreadyJoined) stringResource(MR.strings.thread_add_member_joined) else "",
                                onClick = {
                                    selectedId = member.id
                                    localError = false
                                    onSubmit(member)
                                }
                            )
                        }
                    }
                    if (humanCandidates.isNotEmpty()) {
                        item { SlockModalSectionHeader(stringResource(MR.strings.thread_members_humans)) }
                        items(humanCandidates.size, key = { humanCandidates[it].memberKey }) { index ->
                            val member = humanCandidates[index]
                            val alreadyJoined = member.memberKey in currentKeys
                            val status = member.modalAgentStatusIndicatorState(agentStatusResolver)
                            val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id)
                                ?: member.modalAvatarWireValue()
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.localizedModalRoleLabel(),
                                avatarWireValue = avatarWireValue,
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = status?.color,
                                statusPulsing = status?.pulsing == true,
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (showSelectedCheckmark && member.id == selectedId) SlockLucideIconName.Check else null,
                                trailingText = if (alreadyJoined) stringResource(MR.strings.thread_add_member_joined) else "",
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
private fun KuiklyThreadMemberItem.localizedModalRoleLabel(): String =
    when {
        roleLabel.equals("Agent", ignoreCase = true) -> stringResource(MR.strings.thread_member_role_agent)
        roleLabel.equals("Member", ignoreCase = true) -> stringResource(MR.strings.thread_member_role_member)
        roleLabel.equals("System", ignoreCase = true) -> stringResource(MR.strings.thread_member_role_system)
        else -> roleLabel
    }

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

@Composable
private fun ThreadSettingsActionUi.localizedThreadSettingsAction(): ThreadSettingsActionUi =
    when (id) {
        "visibility" ->
            copy(
                title = stringResource(MR.strings.thread_settings_make_private),
                subtitle = stringResource(MR.strings.thread_settings_make_private_detail)
            )
        "archive" ->
            copy(
                title = stringResource(MR.strings.thread_settings_archive),
                subtitle = stringResource(MR.strings.thread_settings_archive_detail)
            )
        "delete" ->
            copy(
                title = stringResource(MR.strings.thread_settings_delete),
                subtitle = stringResource(MR.strings.thread_settings_delete_detail)
            )
        else -> this
    }

@Composable
private fun ThreadSettingsDangerAction.localizedTitle(): String =
    when (this) {
        ThreadSettingsDangerAction.Visibility -> stringResource(MR.strings.thread_settings_make_private_title)
        ThreadSettingsDangerAction.Archive -> stringResource(MR.strings.thread_settings_archive_title)
        ThreadSettingsDangerAction.Delete -> stringResource(MR.strings.thread_settings_delete_title)
    }

@Composable
private fun ThreadSettingsDangerAction.localizedButtonLabel(): String =
    when (this) {
        ThreadSettingsDangerAction.Visibility -> stringResource(MR.strings.thread_settings_make_private)
        ThreadSettingsDangerAction.Archive -> stringResource(MR.strings.thread_settings_archive)
        ThreadSettingsDangerAction.Delete -> stringResource(MR.strings.thread_settings_delete)
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
    val effectiveActions = actions.normalizedThreadSettingsChannelActions().map { it.localizedThreadSettingsAction() }
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
        title = activeDanger?.localizedTitle() ?: stringResource(MR.strings.thread_settings_edit_channel),
        onDismiss = onDismiss
    ) {
        if (activeDanger == null) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SlockBrutalTextField(
                    label = stringResource(MR.strings.thread_settings_name),
                    value = name,
                    placeholder = stringResource(MR.strings.thread_settings_name_placeholder),
                    onValueChange = { name = it },
                    required = true,
                    textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
                )
                SlockBrutalTextField(
                    label = stringResource(MR.strings.thread_settings_description),
                    value = description,
                    placeholder = stringResource(MR.strings.thread_settings_description_placeholder),
                    onValueChange = { description = it },
                    optional = true,
                    fieldMinHeight = 66.dp,
                    maxLines = 2
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(modifier = Modifier.weight(1f))
                    // React's Edit Channel form buttons carry no leading icons
                    // (btn-brutal text-only Cancel / Save Changes) — task #348.
                    SlockModalActionButton(label = stringResource(MR.strings.common_cancel), onClick = onDismiss)
                    SlockModalActionButton(
                        label = stringResource(MR.strings.thread_action_save_changes),
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
                            text = stringResource(MR.strings.thread_settings_visibility_question, channelName),
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                        )
                        Text(
                            text = stringResource(MR.strings.thread_settings_visibility_detail),
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
                                text = stringResource(MR.strings.thread_settings_archive_question, channelName),
                                style = SlockType.Body,
                                color = SlockColor.TextBlack
                            )
                            Text(
                                text = stringResource(MR.strings.thread_settings_type_to_confirm, channelName),
                                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                                color = SlockColor.TextMutedGray
                            )
                            SlockBrutalTextField(
                                label = stringResource(MR.strings.thread_settings_channel_name),
                                value = confirmationName,
                                placeholder = channelName,
                                onValueChange = { confirmationName = it },
                                required = true,
                                fieldMinHeight = 42.dp
                            )
                            if (confirmationName.isNotBlank() && !archiveConfirmationMatches) {
                                Text(
                                    text = stringResource(MR.strings.thread_settings_channel_name_mismatch),
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
                        label =
                            if (isDelete) {
                                stringResource(MR.strings.common_cancel)
                            } else {
                                stringResource(MR.strings.common_back)
                            },
                        icon = if (isDelete) SlockLucideIconName.X else SlockLucideIconName.ArrowLeft,
                        onClick = { dangerAction = null }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val enabled =
                        activeDanger == ThreadSettingsDangerAction.Visibility ||
                            activeDanger == ThreadSettingsDangerAction.Delete ||
                            archiveConfirmationMatches
                    SlockModalActionButton(
                        label = activeDanger.localizedButtonLabel(),
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
            text = stringResource(MR.strings.thread_settings_delete_warning, channelName),
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
private fun localizedThreadStateTitle(value: String): String =
    when (value) {
        ThreadReplyEmptyTitle -> stringResource(MR.strings.thread_state_no_replies)
        "No messages yet" -> stringResource(MR.strings.thread_state_no_messages)
        "Loading..." -> stringResource(MR.strings.thread_state_loading)
        else -> value
    }

@Composable
private fun localizedThreadStateDetail(value: String?): String? =
    when (value) {
        "This conversation has no messages yet." -> stringResource(MR.strings.thread_state_no_messages_detail)
        else -> value
    }

@Composable
private fun localizedThreadJoinLabel(value: String): String =
    if (value == "Join channel") {
        stringResource(MR.strings.thread_action_join_channel)
    } else {
        value
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
