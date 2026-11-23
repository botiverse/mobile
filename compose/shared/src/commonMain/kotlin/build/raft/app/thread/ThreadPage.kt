package build.raft.app

import build.raft.app.links.ParsedRaftPermalink
import build.raft.app.assistant.LocalThreadAssistantOtter
import build.raft.app.assistant.LocalThreadAssistantPanelEmbed
import build.raft.app.assistant.newAssistantOtterHostId
import build.raft.app.assistant.rememberThreadAssistantOtter
import build.raft.app.assistant.threadForegroundBannerOnPage
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.resources.*
import build.raft.app.downloads.RaftDownloadCenterProcess
import build.raft.app.data.homeStateForPage
import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.data.RaftRatingPrompt
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.openWithinWideDetail
import build.raft.app.data.visibleSideThread
import build.raft.designsystem.components.raftPaneGridCellExpand
import build.raft.designsystem.focus.LocalRaftFocusModalToken
import build.raft.designsystem.focus.LocalRaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.focus.RaftFocusRequestSource
import build.raft.designsystem.focus.RaftFocusTicket
import build.raft.designsystem.focus.currentRaftFocusCoordinator
import build.raft.app.data.RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_EVENT
import build.raft.app.data.AdaptiveParentFocusOutcome
import build.raft.app.data.AdaptiveParentFocusRequest
import build.raft.app.data.parentFocusOutcomeAfterWait
import build.raft.app.data.acceptsParentFocus
import build.raft.app.data.toAdaptiveParentFocusRequestOrNull
import build.raft.app.data.AdaptiveThreadOpenRoute
import build.raft.app.data.routeThreadOpen
import build.raft.app.base.Utils
import build.raft.app.data.RaftHostNetworkEvents
import build.raft.app.data.PlatformFile
import build.raft.app.data.ThreadPendingSendInFlightRegistry
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AuthLossSignal
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.NetworkRaftConversationArtifactsApi
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.NetworkRaftTasksApi
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftQuotedPermalinkContextRead
import build.raft.app.data.RaftQuotedPermalinkHistoryLimitedMemo
import build.raft.app.data.RaftThreadApiClient
import build.raft.app.data.quotedPermalinkViewer
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.RaftEventBus
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.map
import build.raft.app.data.raftAppActionCardCapabilities
import build.raft.app.data.featureFlagClientOs
import build.raft.app.data.ThreadComposerDirectUploadClientRequestIds
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ownSentConversationIds
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpRepository
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftIntegrationsActionsPushNetworkApi
import build.raft.app.data.RaftHostInUse
import build.raft.app.data.isRaftHostVisible
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.canWriteTasksInChannel
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.unfollowThreadProjectingHome
import build.raft.app.data.SharedMembersIntent
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.SharedMembersStore
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TaskConversationDetailsRepository
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskConversationHeaderEvent
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskConversationHeaderViewModel
import build.raft.app.data.TasksUiState
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedAdaptiveScrollAnchor
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.RealtimeCanonicalFinalSinkSurfaceKey
import build.raft.app.data.RealtimeScopeAuthority
import build.raft.app.data.RealtimeRoomLeaseOwner
import build.raft.app.data.appLinkViewerKey
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isAuthSessionErrorCode
import build.raft.app.data.authLossTokenFingerprint
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.threadMessageBodyOwnerKey
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses
import build.raft.app.markdown.LocalRaftMarkdownTaskStatusesPending
import build.raft.app.markdown.LocalRaftMarkdownTaskRefStatuses
import build.raft.app.markdown.LocalRaftMessagePermalinkLabelResolver
import build.raft.app.markdown.LocalRaftMarkdownRenderConfig
import build.raft.app.markdown.LocalRaftMarkdownPlainCandidateSession
import build.raft.app.markdown.RaftMarkdownPlainCandidateSession
import build.raft.app.markdown.resolveThreadMarkdownSingleParagraphLightPathEnabled
import build.raft.app.markdown.threadMarkdownLightGateInputsOrNull
import build.raft.app.markdown.raftMarkdownProvidedValuesWith
import build.raft.app.markdown.raftTraceDiagnosticsEnabled
import build.raft.app.markdown.RaftMarkdownRenderConfig
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.ChannelId
import build.raft.app.data.RaftAgentControlsApi
import build.raft.app.model.Heartbeat
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.network.RaftRequestTimeoutException
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.data.currentFeatureFlagClientContext
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import build.raft.app.compat.ui.collectAsStateWhileStarted
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.module.PerformanceModule
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IPagerEventObserver
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.TimeMark
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.TimeSource

private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
internal const val THREAD_MEMBERS_PAGE = "compose_thread_members"
internal const val ThreadComposerMentionActionFadeDelayMs = 450L
internal const val ThreadComposerMentionActionRemoveDelayMs = 750L
private const val ThreadMarkdownAttachmentPreviewMaxChars = 80_000
private const val ThreadSendSilentRetryDelayMs = 900L
private const val ThreadSendMaxSilentRetries = 2

// task #1134: a send request whose socket stays open without a response beyond this bound is
// treated as a timeout so the row converges to failed + retry instead of hanging in "发送中"
// forever. Client-side cancellation may still leave a committed server request; the journaled
// randomId makes the follow-up retry idempotent server-side.
private const val ThreadSendRequestTimeoutMs = 30_000L
internal const val ThreadLocalizedFeedbackPlaceholder = "__RAFT_VALUE__"
internal const val ThreadRelativeTimestampSlotWidthDp = 112f

// task #387: when the channel initial-access lookup finds the home store still loading, it may
// await the in-flight home load this long before falling back to a direct repository fetch.
// Kept below ThreadInitialAccessLookupTimeoutMillis (1000ms) so the outer race retains headroom.
private const val ThreadHomeSnapshotAwaitTimeoutMs = 800L

internal data class ThreadSystemMessageTimestampLayoutSpec(
    val reserveFullMetadataGutter: Boolean,
    val maxWidthDp: Float
)

internal fun threadSystemMessageTimestampLayoutSpec(): ThreadSystemMessageTimestampLayoutSpec =
    ThreadSystemMessageTimestampLayoutSpec(
        reserveFullMetadataGutter = false,
        maxWidthDp = ThreadRelativeTimestampSlotWidthDp
    )

// The header and forward-bundle timestamps size to the rendered label instead
// of reserving the whole relative-time slot: a reserved gutter starves the
// sender subtitle's flexible width and leaves a blank band left of the time
// (task #1014), while the slot constant still bounds the widest live label.
internal fun threadMessageHeaderTimestampLayoutSpec(): ThreadSystemMessageTimestampLayoutSpec =
    ThreadSystemMessageTimestampLayoutSpec(
        reserveFullMetadataGutter = false,
        maxWidthDp = ThreadRelativeTimestampSlotWidthDp
    )

internal val ThreadLocalSendNextId = ThreadLocalSendIdGenerator()

/** task #36: longest wait for the keyboard to close before the main detail focuses a parent. */
private const val ADAPTIVE_PARENT_FOCUS_IME_WAIT_MS = 1_500L

@Page(THREAD_PAGE, supportInLocal = true)
internal class ComposeThreadPage : AuthenticatedProductPager(AuthenticatedProductRoute.Thread) {
    /**
     * Latest host window fact (A4 adoption, task #18). Standalone thread pages are the only
     * initiation source: the fact is captured here at the pager boundary and reduced by the
     * screen so a DualPane arrival can trigger one guarded prepare/commit transaction.
     */
    private var adaptivePaneState by mutableStateOf(RaftAdaptivePaneState())
    private var adaptivePaneEvent by mutableStateOf<RaftAdaptivePaneEvent?>(null)

    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            RaftInputFocusDismissHost { focusDismissModifier ->
                val content: @Composable () -> Unit = {
                    Box(modifier = focusDismissModifier.fillMaxSize()) {
                        ThreadPageScreen(
                            pageVisibleTick = raftPageVisibleResumeTick,
                            pageVisible = raftPageVisible,
                            adaptivePaneState = adaptivePaneState,
                            adaptivePaneEvent = adaptivePaneEvent,
                        )
                    }
                }
                if (LocalRaftFocusRegion.current == null) {
                    RaftFocusRegionHost(
                        region = RaftFocusRegion.Conversation,
                        modifier = Modifier.fillMaxSize(),
                        content = content,
                    )
                } else {
                    content()
                }
            }
        }
    }

    override fun onRaftAdaptivePaneChanged(eventData: JSONObject) {
        val event = eventData.toRaftAdaptivePaneEventOrNull() ?: return
        adaptivePaneState = reduceRaftAdaptivePane(adaptivePaneState, event)
        adaptivePaneEvent = event
    }
}

internal fun Pager.openThreadChannelSearch(payload: RaftThreadScreenPayload): Boolean {
    val pageData = payload.toThreadChannelSearchPageData() ?: return false
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(HOME_SEARCH_PAGE, pageData)
    return true
}

private fun RaftThreadScreenPayload.findThreadAuthorProfile(memberId: String): MemberProfile? =
    (listOfNotNull(originMessage) + messages)
        .firstOrNull { it.authorId == memberId }
        ?.toMemberProfile()

private fun MemberProfile.toThreadCandidateAgentDetailSnapshot(): AgentDetailSnapshot? {
    if (kind != MemberKind.Agent) return null
    val name = handle.ifBlank { displayName.ifBlank { id.value } }
    val label = displayName.ifBlank { name }
    val avatarWireValue = avatar?.wireValue
    return AgentDetailSnapshot(
        agentId = id,
        summary =
            AgentDetailSummary(
                id = id.value,
                name = name,
                displayName = label,
                runtime = roleDescription,
                avatarUrl = avatarWireValue
            ),
        directMessages = emptyList(),
        reminders = emptyList(),
        activity = emptyList(),
        skills = AgentSkillsSnapshot(agentId = id)
    )
}

internal fun buildThreadPendingSendMessage(
    localMessageId: String,
    context: RaftThreadPendingSendContext,
    session: StoredAuthSession?,
    timestampLabel: String = "Now",
    resolvedQuotes: Map<String, build.raft.app.model.ResolvedInlineQuote>? = null,
): RaftThreadMessageItem {
    val user = session?.user
    val authorName = user?.bestDisplayName ?: "You"
    return RaftThreadMessageItem(
        id = localMessageId,
        authorId = user?.id ?: "local-user",
        authorName = authorName,
        authorHandle = user?.name ?: user?.email ?: authorName,
        authorKind = "human",
        isOwn = true,
        avatarLabel = authorName.toRaftAvatarLabel(),
        avatarWireValue = user?.avatarUrl,
        timestampLabel = timestampLabel,
        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(timestampLabel),
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
                RaftThreadAttachmentItem(
                    id = attachment.id,
                    name = attachment.fileName,
                    mimeType = attachment.mimeType.orEmpty(),
                    sizeLabel = attachment.sizeLabel,
                    sizeBytes = attachment.sizeBytes,
                    thumbnailUrl = attachment.previewUri
                )
            },
        deliveryState = ThreadMessageDeliverySending,
        resolvedQuotes = resolvedQuotes,
        localSendOrder = localMessageId.substringAfterLast('-').toLongOrNull(),
        pendingSendContext = context,
        pendingSendErrorCode = null,
        pendingSendErrorMessage = null
    )
}

internal fun buildThreadPendingSendTimestampLabel(
    bridge: build.raft.app.base.BridgeModule,
    fallbackNowEpochMillis: Long?
): String {
    val bridgeEpochMillis = runCatching { bridge.currentTimeStamp() }.getOrDefault(0L)
    val epochMillis =
        bridgeEpochMillis.takeIf { it > 0L }
            ?: fallbackNowEpochMillis?.takeIf { it > 0L }
            ?: return "Now"
    return raftEpochMillisUtcIso(epochMillis)
}

internal data class ThreadMarkdownMentionOpenTarget(
    val identity: MemberDetailIdentity
)

internal fun <T> DataResult<T>.threadActionErrorCode(fallback: String): String =
    when (this) {
        is DataResult.Content -> fallback
        is DataResult.Empty -> reason.name.lowercase()
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
    }

private fun RaftThreadMemberItem.matchesThreadMentionLookupKey(key: String): Boolean =
    id.equals(key, ignoreCase = true) ||
        handle.removePrefix("@").equals(key, ignoreCase = true) ||
        displayName.equals(key, ignoreCase = true)

private fun MemberProfile.matchesThreadMentionLookupKey(key: String): Boolean =
    id.value.equals(key, ignoreCase = true) ||
        handle.removePrefix("@").equals(key, ignoreCase = true) ||
        displayName.equals(key, ignoreCase = true)

private fun RaftThreadScreenPayload.findMarkdownMentionMember(tag: ThreadMarkdownTag): RaftThreadMemberItem? {
    val key = tag.label.normalizedThreadMentionLookupKey()
    if (key.isBlank()) return null
    return members.firstOrNull { member -> member.matchesThreadMentionLookupKey(key) }
}

internal fun RaftThreadScreenPayload.resolveMarkdownMentionOpenTarget(
    label: String,
    serverMembers: List<MemberProfile>
): ThreadMarkdownMentionOpenTarget? {
    val key = label.normalizedThreadMentionLookupKey()
    if (key.isBlank()) return null
    findMarkdownMentionMember(ThreadMarkdownTag(label, ThreadMarkdownTagKind.UserMention))
        ?.memberDetailIdentity()
        ?.let { identity -> return ThreadMarkdownMentionOpenTarget(identity) }
    val matches =
        serverMembers
            .filter { member -> member.matchesThreadMentionLookupKey(key) }
            .distinctBy { member -> member.id.value.trim() }
    val match = matches.singleOrNull() ?: return null
    return ThreadMarkdownMentionOpenTarget(
        MemberDetailIdentity(match.id, match.kind, match.serverId)
    )
}

/** The Files tab's view model, where the page mounts it (its own function: see [rememberThreadLinkNavigator]). */
@Composable
private fun rememberThreadFilesViewModel(
    mount: Boolean,
    threadInstanceKey: String,
    dataSource: SharedThreadDataSource,
    bridge: build.raft.app.base.BridgeModule,
    sharedStores: build.raft.app.data.RaftSharedStoreProvider,
): SharedThreadFilesViewModel? =
    if (mount) {
        viewModel(key = "shared-thread-files-$threadInstanceKey") {
            SharedThreadFilesViewModel(
                dataSource,
                diagnosticsLogger = { bridge.log(it) },
                // The conversation's Artifacts lead its files (workspace_artifacts_v0, read in the tab).
                artifactsApi = NetworkRaftConversationArtifactsApi(sharedStores.networkClient)
            )
        }
    } else {
        null
    }

/**
 * The conversation page's link navigator: an Artifact link tapped in a message opens with this
 * conversation (`hostConversation`, [artifactOpenConversationId]), as its cards do. Its own function:
 * SharedThreadRouteContentContent is at its JVM method-size ceiling.
 */
@Composable
private fun rememberThreadLinkNavigator(
    pager: Pager,
    bridge: build.raft.app.base.BridgeModule,
    payload: RaftThreadScreenPayload,
): RaftLinkNavigator =
    rememberRaftLinkNavigator(pager, bridge, artifactConversationId = payload.artifactOpenConversationId())

private fun RaftThreadScreenPayload.threadRenderDiagnosticsSource(): String =
    threadRenderDiagnosticsSource(toThreadTimelineSlice())

private fun RaftThreadScreenPayload.threadRenderDiagnosticsSource(timelineSlice: ThreadTimelineSlice): String =
    when {
        status == "loading" -> "loading"
        status == "content" && timelineSlice.messages.isNotEmpty() -> "content"
        status == "content" -> "empty_content"
        isRenderableEmptyReplyThread() -> "empty_reply"
        errorCode != null -> "error"
        else -> status.ifBlank { "unknown" }
    }

internal fun RaftThreadScreenPayload.isRenderableEmptyReplyThread(): Boolean =
    status == "empty" && surfaceKind == RaftThreadSurfaceKind.ReplyThread

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
                val layoutMs = data?.pageLoadTime?.pageLayoutCost?.toLong()
                val drawMs = data?.pageLoadTime?.renderCost?.toLong()
                val budget =
                    evaluateThreadFirstContentPerformanceBudget(
                        layoutMs = layoutMs,
                        drawMs = drawMs
                    )
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
                            "status" to status,
                            "layoutBudgetMs" to ThreadFirstLayoutBudgetMs.toString(),
                            "drawBudgetMs" to ThreadFirstDrawBudgetMs.toString(),
                            "layoutWithinBudget" to budget.layoutWithinBudget.toString(),
                            "drawWithinBudget" to budget.drawWithinBudget.toString()
                        )
                )
                bridge.log("kuikly_perf_snapshot $summary")
                bridge.log(
                    "thread_first_content_budget phase=render layoutMs=${layoutMs ?: -1} " +
                        "drawMs=${drawMs ?: -1} layoutBudgetMs=$ThreadFirstLayoutBudgetMs " +
                        "drawBudgetMs=$ThreadFirstDrawBudgetMs pass=${budget.passed}"
                )
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

private fun RealtimeEvent.isTaskProjectionEvent(): Boolean =
    this is TaskCreated || this is TaskUpdated || this is TaskDeleted

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun ThreadPageScreen(
    pageVisibleTick: Int,
    pageVisible: Boolean,
    adaptivePaneState: RaftAdaptivePaneState = RaftAdaptivePaneState(),
    adaptivePaneEvent: RaftAdaptivePaneEvent? = null,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    var payloadText by remember { mutableStateOf(params.optString("payload")) }
    var instanceId by remember { mutableStateOf(params.optString("instanceId")) }
    var adaptiveCloseRequest by remember {
        mutableStateOf(params.toRaftAdaptiveDetailCloseRequestOrNull())
    }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != THREAD_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let { payloadText = it }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let { instanceId = it }
                    adaptiveCloseRequest =
                        updatedRaftAdaptiveDetailCloseRequest(adaptiveCloseRequest, eventData)
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
    val parsedPayload = payloadTrace.payload
    val baseTimeCapabilities =
        remember(parsedPayload.localOffsetMinutes, parsedPayload.timeFormat) {
            parsedPayload.toRaftTimeCapabilities()
        }
    val runtimeTimeFormat = RaftTimeFormatRuntime.observe(fallback = baseTimeCapabilities.timeFormat)
    val runtimeTimeZone = RaftTimeZoneRuntime.observe()
    val timeCapabilities =
        remember(baseTimeCapabilities, runtimeTimeFormat, runtimeTimeZone) {
            baseTimeCapabilities.withRuntimePreferences(
                timeFormat = runtimeTimeFormat,
                preferredTimeZoneId = runtimeTimeZone,
            )
        }
    val liveTimeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = timeCapabilities,
            pageVisibleTick = pageVisibleTick,
            active = pageVisible
        )
    val payload = parsedPayload
    LaunchedEffect(payloadText, instanceId) {
        bridge.log(
            "thread_launch_payload_parse source=pageData result=${payloadTrace.result} " +
                "responseBytes=${payloadTrace.responseBytes} durationMs=${payloadTrace.durationMs} dispatcher=caller"
        )
    }
    val storeScope = remember(params.toString(), pager.pagerId) {
        params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember { RaftSharedStoreRegistry.get(bridge) }

    // A4 adoption (task #18): only a standalone (native-stack) page initiates. The embedded
    // detail child is identified by RAFT_EMBEDDED_CHILD_PAGE_PARAM, injected by
    // raftEmbeddedChildPageData for every RaftChildPage mount — not by shell scope
    // presence, which every openRaftPage-pushed page also carries.
    val isStandaloneThreadPage =
        adaptiveCloseRequest == null &&
            !params.optBoolean(RAFT_EMBEDDED_CHILD_PAGE_PARAM, false)
    val adaptiveInitiationEnabled =
        params.optBoolean(RAFT_ADAPTIVE_INITIATION_ENABLED, false)
    val adaptiveInitiationCaptureHolder =
        remember { ThreadAdaptiveInitiationCaptureHolder() }
    val adaptiveNativeEditingStateReader =
        remember { RaftTextFieldNativeEditingStateReader() }
    val adaptiveDetailOpenInitiator =
        remember(storeScope.shellInstanceId, pager.pagerId, isStandaloneThreadPage) {
            if (!isStandaloneThreadPage) return@remember null
            RaftAdaptiveDetailOpenInitiator(
                shellInstanceId = storeScope.shellInstanceId,
                hostId = "pager:${pager.pagerId}",
                anchorStore = sharedStores.adaptiveScrollAnchorStore(storeScope),
                eventBus = sharedStores.eventBus,
                nowEpochMillis = { defaultNowMillis() },
                nativeAdaptiveNavigation = { action, requestId, token, expectedRefTag, expectedText ->
                    bridge.adaptiveNavigation(
                        action = action,
                        requestId = requestId,
                        token = token,
                        // Caller-cancelled prepare still owns its nonce: a late prepared
                        // reply must retire the accepted transaction, not orphan it into
                        // request_in_progress.
                        onLateReply = { reply ->
                            val lateToken = reply.optString("token").trim()
                            if (
                                action == "prepare" &&
                                reply.optString("status") == "prepared" &&
                                lateToken.isNotBlank()
                            ) {
                                bridge.dispatchAdaptiveCancel(requestId, lateToken)
                            }
                        },
                        expectedEditorRefTag = expectedRefTag,
                        expectedEditorText = expectedText,
                    )
                },
                dispatchAdaptiveCommit = { requestId, token ->
                    bridge.dispatchAdaptiveCommit(requestId, token)
                },
                dispatchAdaptiveCancel = { requestId, token ->
                    bridge.dispatchAdaptiveCancel(requestId, token)
                },
                captureLatestDraft = captureLatestDraftForAdoption@{ requestId ->
                    val capture =
                        adaptiveInitiationCaptureHolder.captureLatestDraft
                            ?: return@captureLatestDraftForAdoption null
                    capture(requestId)
                },
                captureAnchor = captureAnchorForAdoption@{ requestId ->
                    adaptiveInitiationCaptureHolder.captureAnchor?.invoke(requestId)
                },
                log = { message -> bridge.log("adaptive_initiate $message") },
                logWarn = { message -> bridge.logWarn("adaptive_initiate $message") },
            )
        }
    LaunchedEffect(adaptivePaneEvent, adaptiveInitiationEnabled, isStandaloneThreadPage) {
        val event = adaptivePaneEvent ?: return@LaunchedEffect
        if (!adaptiveInitiationEnabled || !isStandaloneThreadPage) return@LaunchedEffect
        val initiator = adaptiveDetailOpenInitiator ?: return@LaunchedEffect
        if (reduceRaftAdaptivePane(adaptivePaneState, event).layout !=
            RaftAdaptivePaneLayout.DualPane
        ) {
            return@LaunchedEffect
        }
        val launchPayload =
            parsedPayload.toWireThreadLaunchPayloadOrNull() ?: return@LaunchedEffect
        val identity =
            launchPayload.toRaftAdaptiveDetailIdentity(
                serverId =
                    threadParentNavigationServerId(null)
                        ?: storeScope.initialServerId
                        ?: run {
                            bridge.logWarn(
                                "adaptive_initiate_skip reason=no_server_id pager=${pager.pagerId}"
                            )
                            return@LaunchedEffect
                        }
            ) ?: return@LaunchedEffect
        val outcome =
            initiator.initiate(
                identity = identity,
                launchPayload = launchPayload,
                windowFact = event.toRaftAdaptiveOpenWindowFact(defaultNowMillis()),
                requestId = "adopt-${pager.pagerId}-${Uuid.random()}",
            )
        bridge.log("adaptive_initiate_outcome outcome=$outcome identity=${identity.key}")
    }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val sharedHomeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val threadPageCoroutineScope = rememberCoroutineScope()
    var authLossReportInProgress by remember { mutableStateOf(false) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val agentsApiClient = remember(networkClient) {
        RaftAgentsApiClient(
            transport = RaftAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("thread_agents_api $message") }
        )
    }
    val computersApiClient = remember(networkClient) {
        RaftComputersApiClient(RaftComputersNetworkApi(networkClient))
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
        remember(authStore, networkClient, serverMembersStore) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("thread_member_repository $message") },
                cachedMembersProvider = serverMembersStore::currentServerMembers
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
        remember(authStore, networkClient, channelRepository, memberRepository) {
            RealForwardMessageTargetRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                channelRepository = channelRepository,
                memberRepository = memberRepository,
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
                logger = { message -> bridge.log("thread_$message") },
                ownerProvider = {
                    sharedStores.computersOwner(storeScope)
                }
            )
        }
    val quotedPermalinkLoader: ThreadQuotedPermalinkLoader? =
        remember(networkClient, authStore) {
            val client = RaftThreadApiClient(NetworkRaftThreadApi(networkClient))
            val loader: ThreadQuotedPermalinkLoader = { target ->
                val messageId = target.messageId.value
                val channelId = target.channelId.value
                // #proj-mobile:#274: a quote behind the Free plan's history window is answered from
                // the memo, not asked again each time its row comes on screen.
                val read =
                    RaftQuotedPermalinkHistoryLimitedMemo.App.read(
                        viewer = authStore.readSession()?.quotedPermalinkViewer(),
                        channelId = channelId,
                        messageId = messageId,
                    ) { client.messageContext(messageId, channelId) }
                when (read) {
                    is RaftQuotedPermalinkContextRead.Page -> {
                        val payload = read.payload
                        val targetId = payload.targetMessageId?.value ?: messageId
                        payload.messages
                            .firstOrNull { it.id.value == targetId }
                            ?.toRaftThreadMessageItem(null)
                            ?.let { item -> ThreadQuotedPermalinkMessage(item, channelArchived = payload.channelArchived) }
                    }
                    // Behind the history window, as any other miss: no quote card, the link says
                    // it is unavailable (the app has no older-quote card yet).
                    is RaftQuotedPermalinkContextRead.HistoryLimited,
                    RaftQuotedPermalinkContextRead.Unavailable -> null
                }
            }
            loader
        }
    val selectedServerStore =
        remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val dataSource =
        remember(
            networkClient,
            authStore,
            selectedServerStore,
            memberRepository,
            serverMembersStore,
            channelRepository,
            sharedHomeStore,
            sharedStores.threadMessageStore,
            sharedStores.threadPendingSendJournal,
            sharedStores.apiEnvironmentStore,
            createAgentCoordinator
        ) {
            SharedThreadDataSource(
                networkApi = NetworkRaftThreadApi(networkClient),
                sessionStore = authStore,
                pendingSendJournal = sharedStores.threadPendingSendJournal,
                selectedServerStore = selectedServerStore,
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore,
                conversationRepository =
                    RealConversationRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        routeServerIdProvider = { selectedServerStore.selectedServerId.value },
                        warningLogger = { message -> bridge.log("thread_conversation_repository $message") }
                    ),
                channelRepository = channelRepository,
                homeSnapshotLookupProvider =
                    sharedHomeStore?.let { store ->
                        { serverId -> store.awaitSnapshotLookupFor(serverId, ThreadHomeSnapshotAwaitTimeoutMs) }
                    },
                channelProfileUpdateSink = { result ->
                    sharedHomeStore?.accept(
                        SharedHomeIntent.ChannelProfileUpdated(
                            channelId = result.channelId,
                            name = result.name,
                            description = result.description
                        )
                    )
                },
                messageStore = sharedStores.threadMessageStore,
                cacheEnvironmentIdProvider = {
                    sharedStores.apiEnvironmentStore.current().apiBaseUrl
                },
                apiEnvironmentIdProvider = {
                    sharedStores.apiEnvironmentStore.current().apiBaseUrl
                },
                replyCacheIdentityAuthority = {
                    sharedStores.captureThreadReplyCacheIdentity(storeScope)
                },
                replyCacheIdentityTransitionWaiter = { transitionAnchor ->
                    sharedStores.awaitThreadReplyCacheIdentityTransition(storeScope, transitionAnchor)
                },
                canonicalConversationIdentityPublisher = { provenance, detail ->
                    sharedStores.publishCanonicalConversationIdentity(storeScope, provenance, detail)
                },
                storedConversationDetails = sharedStores.storedConversationDetails,
                payloadStore = sharedStores.threadPayloadStore,
                durableLastKnownReader = sharedStores.threadDurableLastKnownReader,
                enteredScopeSnapshotLoader = sharedStores.threadEnteredScopeSnapshotLoader,
                durablePageExtender = sharedStores.threadDurablePageExtender,
                durableAccessEvictor = sharedStores.threadDurableAccessEvictor,
                agentsApiClient = RaftAgentsApiClient(
                    transport = RaftAgentsNetworkApi(networkClient),
                    diagnosticsLogger = { message -> bridge.log("thread_agents_api $message") }
                ),
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(
                    RaftIntegrationsActionsPushNetworkApi(networkClient)
                ),
                savedStatusRepository =
                    RealSavedRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        warningLogger = { message -> bridge.log("thread_saved_repository $message") }
                    ),
                readReceiptCoordinator = sharedStores.processReadReceiptCoordinator(),
                readReceiptOwnerFactory = sharedStores::readReceiptOwner,
                threadPageSizeProvider = { serverId ->
                    sharedStores.serverFeatureFlags.snapshotFor(serverId).mobileThreadPageSize()
                },
                createAgentCoordinator = createAgentCoordinator,
                diagnosticsLogger = { message -> bridge.log("thread_api $message") },
                // artin 10-23: the first frame may stand in with what Activity already shows for this
                // conversation; the store matches it to the route identity (no content in the route).
                activityOpenSeedSource = {
                    sharedStores.homeActivityStoreOrNull(storeScope)?.state?.value?.toThreadActivityOpenSeedSource()
                },
            )
        }
    val tasksDataSource =
        remember {
            SharedTasksDataSource(
                api = NetworkRaftTasksApi(networkClient)
            )
        }
    val routerModule = remember(pager) {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
    }
    val threadOpenFailureMessages = ThreadOpenFailure.entries.associateWith { stringResource(it.message) }
    val unableToOpenProfileLabel = stringResource(Res.string.thread_error_open_profile)
    fun failThreadOpen(failure: ThreadOpenFailure, payload: RaftThreadScreenPayload) {
        bridge.toast(threadOpenFailureMessages.getValue(failure))
        bridge.logWarn(threadOpenFailureLog(failure, payload))
    }
    fun openStandaloneThreadPayload(
        nextPayload: RaftThreadScreenPayload,
        serverIdOverride: String? = null,
        /** False: the page appears without the push (a task's first send, where it takes the pending page's place). */
        animated: Boolean = true,
    ) {
        val launchPayload = nextPayload.toWireThreadLaunchPayloadOrNull()
        if (launchPayload == null) {
            failThreadOpen(ThreadOpenFailure.MissingIdentity, nextPayload)
            return
        }
        val targetStoreScope =
            RaftShellStoreScope.create(
                shellInstanceId = storeScope.shellInstanceId,
                initialServerId = threadParentNavigationServerId(serverIdOverride) ?: storeScope.initialServerId
            )
        val openPage = {
            val workspaceStore = sharedStores.adaptiveWorkspaceStore(storeScope)
            val source = adaptiveCloseRequest
            val session = workspaceStore.snapshot()
            val pane = session.workspace
            val desktopModeEnabled =
                KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore()).readDesktopModeOnLargeScreenSync()
            // task #36: an adaptive page is validated before any route, so a retired main/side
            // page opens nothing even after desktop mode turns off, the pane goes compact or the
            // server changes.
            val route = session.routeThreadOpen(source, desktopModeEnabled, targetStoreScope.initialServerId)
            if (route == AdaptiveThreadOpenRoute.Ignored) {
                bridge.logWarn("thread_open_skip reason=stale_adaptive_source")
            } else if (route == AdaptiveThreadOpenRoute.WithinDetail && source != null && pane != null) {
                // A reply under the shown channel opens in the side column when it fits;
                // otherwise the in-pane replace; a source retired meanwhile changes nothing.
                val next = workspaceStore.update { current ->
                    current.openWithinWideDetail(source, launchPayload, pane.serverId, desktopModeEnabled)
                }
                if (next.launchPayload != launchPayload && next.visibleSideThread()?.payload != launchPayload) {
                    failThreadOpen(ThreadOpenFailure.StaleSource, nextPayload)
                }
            } else pager.openRaftPage(
                pageName = THREAD_PAGE,
                pageData = launchPayload.toThreadPageData().withRaftOpenAnimation(animated),
                storeScopeOverride = targetStoreScope,
                onMissingScope = {
                    failThreadOpen(ThreadOpenFailure.MissingScope, nextPayload)
                }
            )
        }
        if (nextPayload.sourceThreadId?.isNotBlank() == true &&
            (nextPayload.originMessage != null || nextPayload.messages.isNotEmpty())
        ) {
            dataSource.cachePayload(
                nextPayload,
                ownerScopeId =
                    dataSource.replyCacheProvenanceState.value
                        ?.threadPayloadOwnerScopeId
                        .orEmpty()
            )
            openPage()
            return
        }
        openPage()
    }
    val rootBack: () -> Unit = {
        val closeRequest = adaptiveCloseRequest
        if (closeRequest != null) {
            sharedStores.eventBus.post(
                eventName = RAFT_ADAPTIVE_DETAIL_CLOSE_EVENT,
                eventData = closeRequest.toJson(),
                crossProcess = true,
                source = "adaptive_thread_root_back",
            )
        } else {
            routerModule.closePage()
        }
    }
    val conversationUnavailableNav =
        ConversationUnavailableNav(
            wide = adaptivePaneState.layout == RaftAdaptivePaneLayout.DualPane,
            onBack = rootBack,
        )
    val threadOutlineApi = remember(networkClient) { build.raft.app.data.NetworkRaftThreadOutlineApi(networkClient) }
    // Search in this thread reads the whole thread; a failed read is an error toast with its trace id.
    val threadFindLoadFailedLabel = stringResource(Res.string.thread_find_load_failed)
    val threadFindEnvironment =
        remember(networkClient, bridge, threadFindLoadFailedLabel) {
            ThreadFindEnvironment(build.raft.app.data.NetworkRaftThreadFindApi(networkClient)) { error ->
                bridge.errorToast(threadFindLoadFailedLabel, error)
            }
        }
    // The head's title and its parent link (ThreadHeaderOriginalTitle.kt): one per thread page.
    val headerOriginalState = remember(payload.instanceId) { ThreadHeaderOriginalState() }
    val threadRuntimeUsageAccess = rememberShellRuntimeAccountUsageAccess()
    CompositionLocalProvider(
        LocalMembersRuntimeAccountUsageAccess provides threadRuntimeUsageAccess,
        LocalThreadHeaderOriginalState provides headerOriginalState,
        LocalConversationUnavailableNav provides conversationUnavailableNav,
        LocalThreadOutlineApi provides threadOutlineApi,
        LocalThreadFindEnvironment provides threadFindEnvironment,
        LocalThreadAdaptiveInitiationCapture provides adaptiveInitiationCaptureHolder,
        LocalThreadAdaptiveNativeEditingReader provides adaptiveNativeEditingStateReader,
        LocalThreadQuotedPermalinkLoader provides quotedPermalinkLoader,
        // Live quote replies read their resolved quotes here (ThreadQuoteReply.kt).
        LocalThreadResolvedQuotesStore provides sharedStores.resolvedQuotesStore,
    ) {
    SharedThreadRouteContent(
        initialPayload = payload,
        timeContextProvider = liveTimeContextProvider,
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
        active = pageVisible,
        adaptiveCloseRequest = adaptiveCloseRequest,
        onBack = rootBack,
        onOpenThreadPayload = { nextPayload -> openStandaloneThreadPayload(nextPayload) },
        onReplaceThreadPayload = {},
        onOpenThreadPayloadWithoutAnimation = { nextPayload -> openStandaloneThreadPayload(nextPayload, animated = false) },
        // The Tasks tab's rule (taskPendingOpensAsPage), read when the task is opened: its page,
        // but on the wide layout, where false leaves the task to the conversation's own surface.
        onOpenPendingTask = openPending@{ pendingTask, serverId, channelJoined, canWriteChannel ->
            val desktopModeEnabled =
                KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore()).readDesktopModeOnLargeScreenSync()
            if (!taskPendingOpensAsPage(desktopModeEnabled, adaptivePaneState)) return@openPending false
            val resolvedServerId = serverId?.takeIf { it.isNotBlank() } ?: storeScope.initialServerId
            val targetStoreScope =
                RaftShellStoreScope.create(
                    shellInstanceId = storeScope.shellInstanceId,
                    initialServerId = resolvedServerId,
                )
            val workspaceStore = sharedStores.adaptiveWorkspaceStore(storeScope)
            if (workspaceStore.snapshot().routeThreadOpen(adaptiveCloseRequest, desktopModeEnabled, targetStoreScope.initialServerId) ==
                AdaptiveThreadOpenRoute.Ignored
            ) {
                bridge.logWarn("task_pending_open_skip reason=stale_adaptive_source")
            } else {
                pager.openTaskPendingPage(
                    task = pendingTask,
                    serverId = resolvedServerId,
                    storeScope = targetStoreScope,
                    foregroundBannerStore = sharedStores.foregroundBannerStore(storeScope),
                    timeContext = liveTimeContextProvider.dateContext ?: timeCapabilities.staticContext(),
                    alignOwnMessagesRight = payload.alignOwnMessagesRight,
                    compactConsecutiveMessages = payload.compactConsecutiveMessages,
                    channelJoined = channelJoined,
                    canWriteChannel = canWriteChannel,
                )
            }
            true
        },
        onOpenParentThread = { currentPayload, currentSessionServerId ->
            val parentServerId = threadParentNavigationServerId(currentSessionServerId)
                ?: return@SharedThreadRouteContent false
            val request =
                currentPayload.resolveReplyParentConversationOpenRequest(
                    serverId = parentServerId,
                    conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope)
                ) ?: return@SharedThreadRouteContent false
            val parentPayload =
                buildAppShellThreadPayload(
                    conversation = request.conversationIdentity,
                    timeContext = currentPayload.toRaftTimeCapabilities().staticContext(),
                    alignOwnMessagesRight = currentPayload.alignOwnMessagesRight,
                    compactConsecutiveMessages = currentPayload.compactConsecutiveMessages,
                    // raft-design conversation.md "Threads": the place name and View in Channel open the
                    // conversation at the original message, highlighted; a deleted one (or one not loaded,
                    // e.g. behind the history window) opens the conversation alone.
                    anchorMessageId = headerOriginalState.locateMessageId(),
                    onWarn = bridge::logWarn
                ) ?: return@SharedThreadRouteContent false
            openStandaloneThreadPayload(parentPayload, serverIdOverride = parentServerId)
            true
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
                                ?.toAuthPendingOpenRequest(
                                    serverId = session?.currentServerId.orEmpty(),
                                    accountPrincipalId = session?.user?.id.orEmpty(),
                                )
                        sharedStores.authLossFunnel
                            .report(
                                AuthLossSignal(
                                    source = "thread:${payload.instanceId}",
                                    tokenFingerprint = session?.accessToken.authLossTokenFingerprint(),
                                    capturedSession = session,
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
        onOpenMemberProfile = { currentPayload, timelineSlice, identity ->
            val memberId = identity.memberId.value
            val authorProfile =
                timelineSlice.findThreadAuthorProfile(memberId)
                    ?: currentPayload.findThreadAuthorProfile(memberId)
            val selectedIdentity =
                identity.copy(
                    sourceServerId =
                        identity.sourceServerId
                            ?: authorProfile?.serverId
                            ?: currentPayload.members
                                .firstOrNull { it.id == memberId }
                                ?.sourceServerId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::ServerId)
                )
            val pageData = currentPayload.toThreadMemberDetailPageData(selectedIdentity)
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
                        val agentDetailStore = RaftSharedStoreRegistry.get(bridge).agentDetailStore
                        agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(candidate.agentId))
                        agentDetailStore.dispatch(
                            SharedAgentDetailIntent.SetDetail(
                                agentId = candidate.agentId,
                                detail = candidate,
                                serverId = selectedIdentity.sourceServerId
                            )
                        )
                    }
                pager.openRaftPage(
                    pageName = MEMBER_DETAIL_PAGE,
                    pageData = pageData,
                    onMissingScope = {
                        bridge.toast(unableToOpenProfileLabel)
                        bridge.logWarn(
                            "thread_member_profile_skip reason=missing_shell_scope memberId=$memberId " +
                                "instanceId=${currentPayload.instanceId}"
                        )
                    }
                )
            }
        },
        onOpenMembersPager = { currentPayload, serverId, channelId, currentStoreScope, openAddMember ->
            pager.openRaftPage(
                pageName = THREAD_MEMBERS_PAGE,
                pageData =
                    currentPayload.toThreadMembersPageData(serverId, channelId).apply {
                        // The settings drawer's add tile opens Add Member directly (web members strip).
                        if (openAddMember) put(THREAD_MEMBERS_INITIAL_VIEW_KEY, "add")
                    },
                storeScopeOverride = currentStoreScope,
                authenticatedRouteAuthorityRequired = true,
            )
        },
        onOpenChannelSettings = { serverId, channelId, currentStoreScope, linkKey ->
            pager.openRaftPage(
                pageName = CHANNEL_SETTINGS_PAGE,
                pageData = channelSettingsPageData(serverId, channelId, linkKey),
                storeScopeOverride = currentStoreScope,
                authenticatedRouteAuthorityRequired = true,
            )
        }
    )
    }
}

/**
 * release/1.12.0 vc 1120118 hotfix: payload classes for [SharedThreadRouteContent].
 * The flat 27-parameter composable body pushed past the Android ART dex verifier
 * threshold (VerifyError on release builds); grouping into data + callbacks keeps the
 * implementation frame small enough to verify. Same shape as task #346's
 * ThreadPageContentData/ThreadPageContentCallbacks and PR #2453.
 */
/**
 * Indirection between the A4 initiator (constructed at the standalone ThreadPageScreen level)
 * and the thread surface internals that only exist deep inside composition: the native editor
 * reader, the message list state and the composer draft store. The deep surface registers
 * providers once those objects exist; the initiator invokes them per requestId. Keeping the
 * holder reference-stable across recomposition means the in-flight initiator always reaches
 * the *current* providers rather than a stale closure.
 */
internal class ThreadAdaptiveInitiationCaptureHolder {
    // Returns the persisted draft identity snapshot (text + draft key + instance) the
    // pre-commit fence compares against — equal text across different draft/session
    // identities is NOT a match. Null = the read/persist barrier failed.
    var captureLatestDraft: (suspend (requestId: String) -> SharedThreadComposerDraftSnapshot?)? = null
    var captureAnchor: ((requestId: String) -> SharedAdaptiveScrollAnchor?)? = null
}

/**
 * Identity-bearing capture of what the adoption actually persisted: the live editor text
 * bound to a specific draft key on a specific composer instance. A later capture equal in
 * every field proves no edit/draft-switch landed in the ACK/READY window; any difference
 * (including same text under a different draftKey or instanceId) is drift.
 */
internal data class SharedThreadComposerDraftSnapshot(
    val text: String,
    val draftKey: String?,
    val instanceId: String,
    // Kuikly nativeRef of the resolved AutoHeightTextAreaView the read came from; -1 when
    // the binding could not attribute one (then the capture is rejected at prepare).
    val editorNativeRef: Int,
)

/** Present only inside a standalone thread page wired for A4 adoption. */
internal val LocalThreadAdaptiveInitiationCapture =
    compositionLocalOf<ThreadAdaptiveInitiationCaptureHolder?> { null }

/**
 * Native-editing reader shared between the page-level adoption initiator and the composer
 * leaf so "latest draft" reads go through the same binding the editor writes — no second
 * UIKit walk, no stale value. Null outside an A4-wired standalone page.
 */
internal val LocalThreadAdaptiveNativeEditingReader =
    compositionLocalOf<RaftTextFieldNativeEditingStateReader?> { null }

// A jump to a message that had to load the messages around it (an unread-thread pill, the
// thread timeline, a search match, a message link, a file's original message) could not load
// them: say so, with the trace ID, and offer the same jump again. Kept out of
// SharedThreadRouteContentContent to hold that method under its JVM size ceiling.
@Composable
private fun ThreadJumpFailureToasts(
    threadViewModel: SharedThreadViewModel,
    bridge: build.raft.app.base.BridgeModule,
) {
    val jumpLoadFailedLabel by rememberUpdatedState(stringResource(Res.string.thread_jump_load_failed))
    val jumpRetryLabel by rememberUpdatedState(stringResource(Res.string.common_retry))
    LaunchedEffect(threadViewModel) {
        threadViewModel.jumpFailuresFlow().collect { failure ->
            bridge.log(
                "thread_jump_failed target=${failure.messageId.take(8)} unreadThreadPill=${failure.unreadThreadPill}"
            )
            bridge.errorToastWithRetry(
                content = jumpLoadFailedLabel,
                retryLabel = jumpRetryLabel,
                onRetry = {
                    threadViewModel.dispatch(
                        SharedThreadIntent.JumpToMessage(
                            messageId = failure.messageId,
                            switchToTab = failure.switchToTab,
                            unreadThreadPill = failure.unreadThreadPill,
                        )
                    )
                },
            )
        }
    }
}

internal data class SharedThreadRouteContentData(
    val initialPayload: RaftThreadScreenPayload,
    val timeContextProvider: RaftTimeContextProvider,
    val bridge: build.raft.app.base.BridgeModule,
    val dataSource: SharedThreadDataSource,
    val forwardTargetRepository: ForwardMessageTargetRepository?,
    val threadSendScope: CoroutineScope,
    val authStore: RaftAuthSessionStore,
    val tasksDataSource: SharedTasksDataSource,
    val tasksDataStore: SharedTasksDataStore?,
    val computersStore: SharedComputersStore?,
    val membersStore: SharedMembersStore?,
    val serverMembersStore: SharedServerMembersStore?,
    val conversationIdentityProvider: SharedConversationIdentityProvider?,
    val storeScope: RaftShellStoreScope,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val eventBus: RaftEventBus,
    val active: Boolean,
    val adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
)

/** Callback payload of [SharedThreadRouteContent]. See SharedThreadRouteContentData. */
private class SharedThreadRouteContentCallbacks(
    val onBack: () -> Unit,
    val onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit,
    val onReplaceThreadPayload: (RaftThreadScreenPayload) -> Unit,
    val onOpenThreadPayloadWithoutAnimation: (RaftThreadScreenPayload) -> Unit,
    val onOpenParentThread: (RaftThreadScreenPayload, String?) -> Boolean,
    val onAuthRequired: (String) -> Unit,
    val onResolvedAuxiliaryThreadIdChanged: (String?) -> Unit,
    val onOpenMemberProfile: (RaftThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity) -> Unit,
    val onOpenMembersPager: (RaftThreadScreenPayload, ServerId, ChannelId, RaftShellStoreScope, Boolean) -> Unit,
    /** The last argument is the opening conversation's [ThreadSettingsPageLinks] key. */
    val onOpenChannelSettings: (ServerId, ChannelId, RaftShellStoreScope, String) -> Unit,
    /**
     * Opens a task without a conversation as its own pushed stack page (#proj-mobile:#161) where
     * that is how it opens ([taskPendingOpensAsPage]) and says true; false, or none, opens it in
     * this page's task surface.
     */
    val onOpenPendingTask: ((TaskSummary, String?, Boolean?, Boolean?) -> Boolean)? = null,
)

@Composable
internal fun SharedThreadRouteContent(
    initialPayload: RaftThreadScreenPayload,
    timeContextProvider: RaftTimeContextProvider,
    bridge: build.raft.app.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    forwardTargetRepository: ForwardMessageTargetRepository? = null,
    threadSendScope: CoroutineScope,
    authStore: RaftAuthSessionStore,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore? = null,
    computersStore: SharedComputersStore? = null,
    membersStore: SharedMembersStore? = null,
    serverMembersStore: SharedServerMembersStore? = null,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    storeScope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
    composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    eventBus: RaftEventBus,
    onBack: () -> Unit,
    onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit = {},
    onReplaceThreadPayload: (RaftThreadScreenPayload) -> Unit = onOpenThreadPayload,
    /** A page that takes the place of what this page shows over it (the pending task page's first send). */
    onOpenThreadPayloadWithoutAnimation: (RaftThreadScreenPayload) -> Unit = onOpenThreadPayload,
    onOpenParentThread: (RaftThreadScreenPayload, String?) -> Boolean = { _, _ -> false },
    onAuthRequired: (String) -> Unit = {},
    active: Boolean = true,
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest? = null,
    onResolvedAuxiliaryThreadIdChanged: (String?) -> Unit = {},
    onOpenMemberProfile: (RaftThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity) -> Unit = { _, _, _ -> },
    onOpenMembersPager: (RaftThreadScreenPayload, ServerId, ChannelId, RaftShellStoreScope, Boolean) -> Unit = { _, _, _, _, _ -> },
    onOpenChannelSettings: (ServerId, ChannelId, RaftShellStoreScope, String) -> Unit = { _, _, _, _ -> },
    onOpenPendingTask: ((TaskSummary, String?, Boolean?, Boolean?) -> Boolean)? = null,
) {
    SharedThreadRouteContentContent(
        data =
            SharedThreadRouteContentData(
                initialPayload = initialPayload,
                timeContextProvider = timeContextProvider,
                bridge = bridge,
                dataSource = dataSource,
                forwardTargetRepository = forwardTargetRepository,
                threadSendScope = threadSendScope,
                authStore = authStore,
                tasksDataSource = tasksDataSource,
                tasksDataStore = tasksDataStore,
                computersStore = computersStore,
                membersStore = membersStore,
                serverMembersStore = serverMembersStore,
                conversationIdentityProvider = conversationIdentityProvider,
                storeScope = storeScope,
                composerDraftPersistSlice = composerDraftPersistSlice,
                eventBus = eventBus,
                active = active,
                adaptiveCloseRequest = adaptiveCloseRequest,
            ),
        callbacks =
            SharedThreadRouteContentCallbacks(
                onBack = onBack,
                onOpenThreadPayload = onOpenThreadPayload,
                onReplaceThreadPayload = onReplaceThreadPayload,
                onOpenThreadPayloadWithoutAnimation = onOpenThreadPayloadWithoutAnimation,
                onOpenParentThread = onOpenParentThread,
                onAuthRequired = onAuthRequired,
                onResolvedAuxiliaryThreadIdChanged = onResolvedAuxiliaryThreadIdChanged,
                onOpenMemberProfile = onOpenMemberProfile,
                onOpenMembersPager = onOpenMembersPager,
                onOpenChannelSettings = onOpenChannelSettings,
                onOpenPendingTask = onOpenPendingTask,
            ),
    )
}

/**
 * Data-layer plan, Tenny rule 2: a background refresh or a realtime catch-up of the open
 * conversation proved the account lost access (403 / 404, e.g. someone removed you from the
 * channel). The store already evicted its cached timeline; the page leaves with a notice. A channel
 * says "You're no longer in this channel" (web #12696 `layout.main.channelAccessLost`) and drops its
 * Activity rows like a Leave does; a DM or thread keeps the generic "no longer available".
 */
@Composable
private fun ThreadConversationAccessLostEffect(
    threadViewModel: SharedThreadViewModel,
    instanceKey: String,
    bridge: build.raft.app.base.BridgeModule,
    sharedStores: build.raft.app.data.RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    onBack: () -> Unit,
) {
    val conversationAccessLost by remember(threadViewModel) {
        threadViewModel.conversationAccessLostFlow()
    }.collectAsState(threadViewModel.state.value.conversationAccessLost)
    val channelAccessLostLabel = stringResource(Res.string.thread_channel_access_lost)
    val conversationUnavailableLabel = stringResource(Res.string.home_error_conversation_unavailable)
    LaunchedEffect(conversationAccessLost) {
        if (!conversationAccessLost) return@LaunchedEffect
        val lostState = threadViewModel.state.value
        // Only web's channel denial says the person left this channel (#12696 isChannelAccessDenial).
        val lostChannelId = lostState.payload.accessLostChannelIdOrNull()?.takeIf { lostState.channelAccessDenied }
        bridge.logWarn("thread_access_lost_leave instanceKey=$instanceKey channel=${lostChannelId != null}")
        bridge.toast(if (lostChannelId != null) channelAccessLostLabel else conversationUnavailableLabel)
        lostChannelId?.let { channelId -> sharedStores.publishChannelAccessLost(storeScope, channelId) }
        // As the Channel settings close path does: re-read Home so the conversation leaves the sidebar.
        sharedStores.sharedHomeStore(storeScope)?.dispatch(SharedHomeIntent.Refresh)
        onBack()
    }
}

/** The channel this page shows, when it is a channel (not a DM, reply thread or task conversation). */
internal fun RaftThreadScreenPayload.accessLostChannelIdOrNull(): ChannelId? =
    sourceThreadId
        ?.takeIf { id ->
            id.isNotBlank() &&
                surfaceKind == RaftThreadSurfaceKind.Channel &&
                channelKind.normalizedThreadChannelKind() != RaftTaskConversationChannelKind
        }
        ?.let(::ChannelId)

private data class ThreadProducerContextState(
    val session: StoredAuthSession?,
    val provenanceOwner: ReplyCacheProvenance?,
    val serverSlug: String?,
    val captured: Boolean,
)

@Composable
private fun rememberThreadProducerContext(
    threadViewModel: SharedThreadViewModel,
    dataSource: SharedThreadDataSource,
    threadInstanceKey: String,
): ThreadProducerContextState {
    // Since #5104, a cached conversation renders its full page on frame 1 (no LoadingShell wait).
    // `threadViewModel.ensureStarted()` already captured this page's authority-verified producer
    // context inline when serving that cached frame; seed from that capture (fenced against the
    // live provenance) so session/provenance/slug are ready on frame 1 without risking a stale
    // leftover from a previous page on a reused dataSource when start() suspended.
    val initial =
        remember(threadViewModel, dataSource, threadInstanceKey) {
            threadViewModel.initialProducerContext?.takeIf { context ->
                dataSource.isCurrentReplyCacheProvenance(context.provenance)
            }
        }
    var currentSession by remember(threadViewModel, dataSource, threadInstanceKey) {
        mutableStateOf(initial?.session)
    }
    var replyCacheProvenanceOwner by remember(threadViewModel, dataSource, threadInstanceKey) {
        mutableStateOf(initial?.provenance)
    }
    var currentServerSlug by remember(threadViewModel, dataSource, threadInstanceKey) {
        mutableStateOf(initial?.session?.currentServerSlug())
    }
    var captured by remember(threadViewModel, dataSource, threadInstanceKey) {
        mutableStateOf(initial != null)
    }
    LaunchedEffect(threadViewModel, dataSource, threadInstanceKey) {
        val producerContext = dataSource.captureReplyCacheProducerContext()
        currentSession = producerContext?.session
        replyCacheProvenanceOwner = producerContext?.provenance
        currentServerSlug = producerContext?.session?.currentServerSlug()
        captured = true
        // task #1153: the captured session snapshot can precede server-roster
        // population, leaving the slug permanently null and silently disabling
        // quoted permalink cards for the whole thread session. Re-read the
        // (freshly captured) session for a bounded window until it resolves.
        if (currentServerSlug == null) {
            currentServerSlug =
                resolveThreadServerSlugWithRetry {
                    dataSource
                        .captureReplyCacheProducerContext()
                        ?.session
                        ?.currentServerSlug()
                }
        }
    }
    return ThreadProducerContextState(
        session = currentSession,
        provenanceOwner = replyCacheProvenanceOwner,
        serverSlug = currentServerSlug,
        captured = captured,
    )
}

@Composable
private fun ObserveThreadFirstContentPerformanceGate(
    bridge: build.raft.app.base.BridgeModule,
    payload: RaftThreadScreenPayload,
    timelineSlice: ThreadTimelineSlice,
    threadInstanceKey: String,
    threadTimelineKey: String?,
    threadReverseLayoutEnabled: Boolean,
    plainCandidateCensusSession: RaftMarkdownPlainCandidateSession?,
    threadOpenStartedAt: () -> TimeMark?,
) {
    var firstContentLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var performanceSnapshotLoggedThreadId by remember { mutableStateOf<String?>(null) }

    // Read-only evidence of the per-open decision (QA asked for one on every platform).
    LaunchedEffect(threadInstanceKey, threadReverseLayoutEnabled) {
        bridge.log(
            "thread_reverse_layout threadId=${(threadTimelineKey ?: threadInstanceKey).orEmpty()} " +
                "enabled=$threadReverseLayoutEnabled " +
                "buildType=${currentFeatureFlagClientContext().buildType ?: "none"}"
        )
    }
    LaunchedEffect(threadInstanceKey, threadTimelineKey, timelineSlice.status, timelineSlice.messages.size) {
        if (payload.isReadyForThreadPerformanceGate() && firstContentLoggedThreadId != threadInstanceKey) {
            firstContentLoggedThreadId = threadInstanceKey
            // Census open-level aggregate flush (task #20 Option B P1 v3):
            // effects run after the apply pass's RememberObserver callbacks,
            // so every committed row of this first content batch has already
            // submitted to this open's session instance.
            plainCandidateCensusSession?.flush()
            val payloadReadyMs = threadOpenStartedAt()?.elapsedNow()?.inWholeMilliseconds
            val payloadReadyBudget =
                evaluateThreadFirstContentPerformanceBudget(payloadReadyMs = payloadReadyMs)
            bridge.performanceMarker(
                phase = "thread_payload_ready",
                routeName = "kuikly_thread",
                event = "instant",
                threadId = threadTimelineKey ?: threadInstanceKey,
                durationMs = payloadReadyMs,
                metadata =
                    mapOf(
                        "status" to timelineSlice.status.ifBlank { "unknown" },
                        "source" to payload.threadRenderDiagnosticsSource(timelineSlice),
                        "messages" to timelineSlice.messages.size.toString(),
                        "budgetMs" to ThreadPayloadReadyBudgetMs.toString(),
                        "withinBudget" to payloadReadyBudget.payloadReadyWithinBudget.toString()
                    )
            )
            bridge.log(
                "thread_payload_ready_gate threadId=${(threadTimelineKey ?: threadInstanceKey).orEmpty()} " +
                    "status=${timelineSlice.status} messages=${timelineSlice.messages.size} " +
                    "source=${payload.threadRenderDiagnosticsSource(timelineSlice)} " +
                    "durationMs=${payloadReadyMs ?: -1} budgetMs=$ThreadPayloadReadyBudgetMs " +
                    "pass=${payloadReadyBudget.passed}"
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
}

private data class ThreadTaskConversationState(
    val conversationId: ChannelId?,
    val headerViewModel: TaskConversationHeaderViewModel?,
    val headerState: TaskConversationHeaderState?,
    val parentChannelId: ChannelId?,
    val tasksViewModel: SharedTasksViewModel?,
    val tasksState: TasksUiState?,
    val task: TaskSummary?,
    val detailsRepository: TaskConversationDetailsRepository,
    val detailsViewModel: TaskConversationDetailsViewModel?,
    val detailsState: TaskConversationDetailsState,
)

@Composable
private fun rememberThreadTaskConversationState(
    payload: RaftThreadScreenPayload,
    authStore: RaftAuthSessionStore,
    dataSource: SharedThreadDataSource,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?,
    sharedHomeStore: SharedHomeStore?,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: build.raft.app.base.BridgeModule,
    currentServerId: String?,
    onBack: () -> Unit,
): ThreadTaskConversationState {
    // Task v2 (web TaskConversationBar): a task conversation's bar resolves the task and its channel
    // from the conversation, then reads the task from that channel's shared task projection, so a
    // status / assignee write and a `task:updated` fold take the Tasks board's path.
    val taskConversationId =
        payload.resolvedAuxiliaryThreadId()
            ?.takeIf { payload.channelKind == RaftTaskConversationChannelKind }
            ?.let(::ChannelId)
    val taskConversationHeaderViewModel: TaskConversationHeaderViewModel? =
        taskConversationId?.let { conversationId ->
            viewModel(key = "task-conversation-header-${conversationId.value}") {
                TaskConversationHeaderViewModel(
                    conversationChannelId = conversationId,
                    loadRef = dataSource::loadTaskConversationRef,
                    follow = dataSource::followTaskConversation,
                    unfollow = { conversationId ->
                        unfollowThreadProjectingHome(
                            conversationId,
                            project = { intent -> sharedHomeStore?.dispatch(intent) },
                            unfollow = dataSource::unfollowThread,
                        )
                    },
                    log = { bridge.log(it) },
                    loadTask = { serverId, parentChannelId, taskNumber ->
                        tasksDataSource
                            .loadChannelTaskByNumber(ServerId(serverId), parentChannelId, taskNumber, DataRequestPriority.Background)
                            .map { it.task }
                    },
                )
            }
        }
    val taskConversationHeaderState =
        taskConversationHeaderViewModel?.state?.collectAsState()?.value
    LaunchedEffect(taskConversationHeaderViewModel) { taskConversationHeaderViewModel?.start() }
    val taskConversationParentChannelId = taskConversationHeaderState?.ref?.parentChannelId
    // The task channel's tasks store, shared with the pending task page ([TaskOpenSurfaceHost]).
    val taskConversationTasksViewModel: SharedTasksViewModel? =
        rememberTaskChannelTasksViewModel(taskConversationParentChannelId, authStore, tasksDataSource, tasksDataStore) { bridge.log(it) }
    val taskConversationTasksState = taskConversationTasksViewModel?.state?.collectAsState()?.value
    val taskConversationListedTask =
        taskConversationHeaderState?.ref?.taskId?.let { taskId ->
            (taskConversationTasksState?.loadState as? ScreenLoadState.Content)
                ?.data
                ?.tasks
                ?.firstOrNull { it.id == taskId }
        }
    // The channel list can be status-filtered, so a task moved to Done / Closed may leave it: the
    // bar keeps the last copy seen, and only `task:deleted` marks the task deleted.
    LaunchedEffect(taskConversationListedTask) {
        taskConversationListedTask?.let { taskConversationHeaderViewModel?.onTaskSeen(it) }
    }
    val taskConversationTask = taskConversationHeaderState?.barTask(taskConversationListedTask)
    val taskConversationDeletedLabel = stringResource(Res.string.task_conversation_deleted)
    val taskConversationFollowFailedLabel = stringResource(Res.string.task_conversation_follow_failed)
    val latestOnBack by rememberUpdatedState(onBack)
    val latestFollowFailedLabel by rememberUpdatedState(taskConversationFollowFailedLabel)
    LaunchedEffect(taskConversationHeaderState?.removed == true) {
        if (taskConversationHeaderState?.removed == true) {
            // Web closes the task dialog when its task is deleted; the page says why, then closes.
            bridge.log("task_conversation_removed conversationId=${taskConversationId?.value.orEmpty()}")
            bridge.toast(taskConversationDeletedLabel)
            latestOnBack()
        }
    }
    LaunchedEffect(taskConversationHeaderViewModel) {
        taskConversationHeaderViewModel?.events?.collect { event ->
            when (event) {
                is TaskConversationHeaderEvent.FollowFailed -> bridge.errorToast(latestFollowFailedLabel, event.toScreenError())
            }
        }
    }
    // Task v2 (web TaskConversationDialog head): the task's history, its source message and a pre-v2
    // task's earlier discussion, read for the task the head shows (again for each newer revision).
    val taskConversationDetailsServerId by rememberUpdatedState(currentServerId)
    val taskConversationDetailsRepository =
        remember(sharedStores, storeScope) { TaskConversationDetailsRepository(sharedStores.networkClient(storeScope)) }
    val taskConversationDetailsViewModel: TaskConversationDetailsViewModel? =
        taskConversationId?.let { conversationId ->
            viewModel(key = "task-conversation-details-${conversationId.value}") {
                fun server(): ServerId? = taskConversationDetailsServerId?.let(::ServerId)
                val noServer = DataResult.Error(ScreenError(code = "task_head_no_server", retryable = true, traceId = null))
                TaskConversationDetailsViewModel(
                    loadHistory = { taskId ->
                        server()?.let { taskConversationDetailsRepository.loadHistory(it, taskId) } ?: noServer
                    },
                    loadSource = { channelId, messageId ->
                        server()?.let { taskConversationDetailsRepository.loadSourceMessage(it, channelId, messageId) } ?: noServer
                    },
                    loadEarlierDiscussion = { channelId, hostMessageId ->
                        server()?.let { taskConversationDetailsRepository.loadEarlierDiscussion(it, channelId, hostMessageId) } ?: noServer
                    },
                    log = { bridge.log(it) },
                )
            }
        }
    val taskConversationDetailsState =
        taskConversationDetailsViewModel?.state?.collectAsState()?.value ?: TaskConversationDetailsState()
    LaunchedEffect(taskConversationDetailsViewModel, taskConversationTask, currentServerId) {
        if (currentServerId != null) taskConversationTask?.let { taskConversationDetailsViewModel?.onTask(it) }
    }
    return ThreadTaskConversationState(
        conversationId = taskConversationId,
        headerViewModel = taskConversationHeaderViewModel,
        headerState = taskConversationHeaderState,
        parentChannelId = taskConversationParentChannelId,
        tasksViewModel = taskConversationTasksViewModel,
        tasksState = taskConversationTasksState,
        task = taskConversationTask,
        detailsRepository = taskConversationDetailsRepository,
        detailsViewModel = taskConversationDetailsViewModel,
        detailsState = taskConversationDetailsState,
    )
}

@Composable
private fun SharedThreadRouteContentContent(
    data: SharedThreadRouteContentData,
    callbacks: SharedThreadRouteContentCallbacks,
) {
    val initialPayload = data.initialPayload
    val timeContextProvider = data.timeContextProvider
    val bridge = data.bridge
    val dataSource = data.dataSource
    val forwardTargetRepository = data.forwardTargetRepository
    val threadSendScope = data.threadSendScope
    val authStore = data.authStore
    val tasksDataSource = data.tasksDataSource
    val tasksDataStore = data.tasksDataStore
    val computersStore = data.computersStore
    val membersStore = data.membersStore
    val serverMembersStore = data.serverMembersStore
    val conversationIdentityProvider = data.conversationIdentityProvider
    val storeScope = data.storeScope
    val composerDraftPersistSlice = data.composerDraftPersistSlice
    val eventBus = data.eventBus
    val active = data.active
    val adaptiveCloseRequest = data.adaptiveCloseRequest
    val onBack = callbacks.onBack
    val onOpenThreadPayload = callbacks.onOpenThreadPayload
    val onReplaceThreadPayload = callbacks.onReplaceThreadPayload
    val onOpenThreadPayloadWithoutAnimation = callbacks.onOpenThreadPayloadWithoutAnimation
    val onOpenParentThread = callbacks.onOpenParentThread
    val onAuthRequired = callbacks.onAuthRequired
    val onResolvedAuxiliaryThreadIdChanged = callbacks.onResolvedAuxiliaryThreadIdChanged
    val onOpenMemberProfile = callbacks.onOpenMemberProfile
    val onOpenMembersPager = callbacks.onOpenMembersPager
    val onOpenChannelSettings = callbacks.onOpenChannelSettings
    val pager = LocalActivity.current.getPager() as Pager
    val externalAuthorHintTemplate =
        stringResource(Res.string.thread_external_author_hint, ThreadLocalizedFeedbackPlaceholder)
    val externalAuthorGenericHint = stringResource(Res.string.message_author_external_fallback)
    // task #395: external (Slack-bridge) authors have no member profile; the avatar/name
    // tap surfaces a provider identity hint instead of silently doing nothing.
    val onExternalAuthorHint: (RaftThreadMessageItem) -> Unit = { message ->
        bridge.toast(externalAuthorHintText(message, externalAuthorHintTemplate, externalAuthorGenericHint))
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    // task #180 / #189 step 5: the thread panel's overflow menu has Focus mode, which is the thread column's Expand
    // (rule 5, web RowExpandAction "the thread, whose Focus Mode it is"): the column fills the row, every other column
    // at 160; pressed again, Back or Esc, the row is restored. Offered only where the thread stands in a column of the
    // planned row with more than one column (or is the one expanded): its column's controls say so.
    val threadExpand = raftPaneGridCellExpand(RaftChannelSideSection.Thread.wire)?.takeIf { it.shown }
    val threadFocusModeActive = threadExpand?.pressed == true
    val threadFocusModeToggle: (() -> Unit)? = threadExpand?.let { expand -> { expand.toggle() } }
    val messageBodyFontSizeState by sharedStores.messageBodyFontSizeStore.state.collectAsState()
    val messageBodySettingsNetworkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val messageBodySettingsRepository =
        remember(authStore, messageBodySettingsNetworkClient) {
            RealSettingsRepository(
                sessionStore = authStore,
                networkClient = messageBodySettingsNetworkClient,
                logger = { message -> bridge.log("thread_message_body_font_repository $message") },
                warningLogger = { message -> bridge.logWarn("thread_message_body_font_repository $message") },
            )
        }
    val createAgentFormStore = remember(sharedStores, storeScope) { sharedStores.createAgentFormStore(storeScope) }
    val attachmentOwnerVersion by ProcessAttachmentAuthScopes.version.collectAsState()
    val attachmentOwner = remember(attachmentOwnerVersion, storeScope.shellInstanceId) {
        currentAttachmentOwnerLease(storeScope)
    }
    val attachmentDownloadOwnerKey = attachmentOwner?.principalKey?.value
    val attachmentDownloadHost =
        remember(attachmentDownloadOwnerKey) {
            attachmentDownloadOwnerKey?.let { ownerKey ->
                ThreadAttachmentDownloadHost { attachmentId ->
                    RaftDownloadCenterProcess.center.attachmentEntry(ownerKey, attachmentId)
                }
            }
        }
    val translationStore = remember(sharedStores) { sharedStores.messageTranslationStore }
    val realtimeDomainEventOwner =
        remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    val canonicalFinalLifecycleEffects =
        remember(sharedStores) {
            sharedStores.realtimeAuthorityProvider.canonicalFinalLifecycleEffectsOrNull()
        }
    val emptyCanonicalFinalAuthorityFlow =
        remember { MutableStateFlow<RealtimeScopeAuthority?>(null) }
    val canonicalFinalAuthorityFlow =
        remember(
            canonicalFinalLifecycleEffects,
            emptyCanonicalFinalAuthorityFlow,
            sharedStores,
            storeScope,
        ) {
            canonicalFinalLifecycleEffects?.let {
                sharedStores.realtimeAuthority(storeScope)
            } ?: emptyCanonicalFinalAuthorityFlow
        }
    val canonicalFinalAuthority by canonicalFinalAuthorityFlow.collectAsState()
    val foregroundBannerCurrentServerStore = remember(sharedStores) { sharedStores.localDataStore }
    val sharedHomeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    // Data-layer plan: frame 1 sees the Home store's current value (pin state, rows), not an empty state.
    val sharedHomeStateFlow = remember(sharedHomeStore) { sharedHomeStore.homeStateForPage() }
    val sharedHomeState by sharedHomeStateFlow.collectAsStateWhileStarted()
    val realtimeEvents = remember(eventBus) { RaftRealtimeEvents(eventBus) }
    val realtimeEventMapper =
        remember(sharedStores) {
            RealtimeEventMapper(
                messageShadowObserver = sharedStores.messageShadowObserver,
            )
        }
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
    // task #387: Start must not run inside SharedThreadViewModel.init — viewModelScope reads the
    // provider-assigned pagerId, which exists only after viewModel() returns. Dispatch it here,
    // still inside the same composition pass, so the warm path completes inline and the first
    // frame renders the cached timeline.
    remember(threadViewModel) { threadViewModel.ensureStarted() }
    val renderSliceState = remember(threadViewModel) {
        threadViewModel.renderSliceFlow()
    }.collectAsState(threadViewModel.currentRenderSlice)
    val renderSlice by renderSliceState
    ThreadConversationAccessLostEffect(
        threadViewModel = threadViewModel,
        instanceKey = initialRouteIdentity.instanceKey,
        bridge = bridge,
        sharedStores = sharedStores,
        storeScope = storeScope,
        onBack = onBack,
    )
    // task #36: the side thread's "open parent channel" keeps the column and hands focus to the
    // main detail: the side page drops its own input focus (keyboard down) and asks the main page,
    // by its key/generation, to scroll to and highlight the parent message. The composer is never
    // focused, so no keyboard pops up; retired pages ignore the request.
    val adaptiveFocusManager = LocalFocusManager.current
    val adaptiveKeyboardController = LocalSoftwareKeyboardController.current
    val adaptiveFocusWorkspaceStore = remember(sharedStores, storeScope) { sharedStores.adaptiveWorkspaceStore(storeScope) }
    val adaptivePageKeyboardInsetDpState = remember { mutableStateOf(0f) }
    var adaptivePageKeyboardInsetDp by adaptivePageKeyboardInsetDpState
    val threadKeyboardTapDismissal =
        remember(adaptiveFocusManager, adaptiveKeyboardController) {
            ThreadKeyboardTapDismissal(
                keyboardInsetDp = { adaptivePageKeyboardInsetDp },
                hideKeyboard = {
                    adaptiveFocusManager.clearFocus(force = true)
                    adaptiveKeyboardController?.hide()
                },
            )
        }
    var pendingAdaptiveParentFocus by remember { mutableStateOf<AdaptiveParentFocusRequest?>(null) }
    DisposableEffect(eventBus, adaptiveCloseRequest, threadViewModel) {
        val page = adaptiveCloseRequest
        val subscription =
            page?.let {
                eventBus.observe(
                    eventName = RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_EVENT,
                    crossProcess = true,
                ) { eventData ->
                    val request = eventData?.toAdaptiveParentFocusRequestOrNull() ?: return@observe
                    if (!adaptiveFocusWorkspaceStore.snapshot().acceptsParentFocus(page, request)) return@observe
                    pendingAdaptiveParentFocus = request
                }
            }
        onDispose { subscription?.close() }
    }
    // The focus anchor drops (does not defer) a request while the keyboard is open (task #990),
    // and the side page's keyboard is the window's, so wait for it to close before focusing.
    LaunchedEffect(pendingAdaptiveParentFocus) {
        val request = pendingAdaptiveParentFocus ?: return@LaunchedEffect
        val closed =
            withTimeoutOrNull(ADAPTIVE_PARENT_FOCUS_IME_WAIT_MS) {
                snapshotFlow { adaptivePageKeyboardInsetDp }.first { it <= 0f }
            } != null
        pendingAdaptiveParentFocus = null
        // Re-validate after the wait: a channel switch, fold or new selection meanwhile retires
        // the target, and a keyboard still open would only have the anchor drop it — both are
        // reported as not focused instead of dispatching.
        val outcome =
            adaptiveFocusWorkspaceStore.snapshot().parentFocusOutcomeAfterWait(adaptiveCloseRequest, request, closed)
        bridge.log(
            "adaptive_side_thread_parent_focus result=${outcome.logValue} generation=${request.target.generation}"
        )
        if (outcome == AdaptiveParentFocusOutcome.Applied) {
            // A message, or the history start for one behind the history window (ThreadHeaderOriginalTitle.kt).
            threadViewModel.dispatch(request.toThreadFocusIntent())
        }
    }
    val payload = renderSlice.payload
    val timelineSlice = renderSlice.timelineSlice
    // What the store knows of each parent's thread; shown with Home's count below (replyMetadataByParentId).
    val pageReplyMetadataByParentId = renderSlice.replyMetadataByParentId
    val highlightedMentions = renderSlice.highlightedMentions
    // Task #228 successor (direction A): the shared Home directory is the same
    // authority source the initial parent lookup consults. Forward the exact
    // parent row whenever it appears/changes so the store re-evaluates
    // event-driven; the store fences every hint by route, account state and
    // current authority status, so redundant dispatches are cheap no-ops.
    // Agent Lounge v0: a Lounge is in no Home list; once it is known (opened, its DM row, or the
    // parent detail this reply load reads by id) it is the parent row. No Join: replying before
    // joining is allowed, as in the Lounge itself; a Lounge you may only read stays read-only.
    val knownLounges by RaftAgentLounges.lounges.collectAsState()
    val initialThreadInstanceKey = initialRouteIdentity.instanceKey
    val canonicalFinalThreadSurfaceRouteKey =
        remember(initialPayload.instanceId, initialThreadInstanceKey) {
            initialPayload.instanceId.takeIf { it.isNotBlank() } ?: initialThreadInstanceKey
        }
    val payloadRouteIdentity = remember(payload) {
        payload.resolveThreadRouteIdentity()
    }
    val threadInstanceKey = initialThreadInstanceKey
    val threadTimelineKey =
        payloadRouteIdentity.timelineChannelId
            ?: initialRouteIdentity.timelineChannelId
    val resolvedAuxiliaryThreadId = payload.resolvedAuxiliaryThreadId()
    val producerContextState =
        rememberThreadProducerContext(
            threadViewModel = threadViewModel,
            dataSource = dataSource,
            threadInstanceKey = initialThreadInstanceKey,
        )
    val currentSession = producerContextState.session
    val replyParentHintServerId = currentSession?.currentServerId ?: storeScope.initialServerId
    LaunchedEffect(
        payload.surfaceKind,
        payload.parentChannelId,
        payload.parentMessageId,
        payload.originParentMessageId,
        sharedHomeState,
        knownLounges,
        replyParentHintServerId,
    ) {
        val hint =
            resolveReplyParentDirectoryHint(
                payload = payload,
                homeState = sharedHomeState,
                expectedServerId = replyParentHintServerId,
            ) ?: return@LaunchedEffect
        threadViewModel.dispatch(hint)
    }
    val replyCacheProvenanceOwner = producerContextState.provenanceOwner
    val currentServerSlug = producerContextState.serverSlug
    val producerContextCaptured = producerContextState.captured
    val messageBodyOwnerKey =
        remember(currentSession?.user?.id, sharedStores.apiEnvironmentStore.current().apiBaseUrl) {
            threadMessageBodyOwnerKey(sharedStores.apiEnvironmentStore.current().apiBaseUrl, currentSession?.user?.id)
        }
    LaunchedEffect(messageBodyOwnerKey, messageBodySettingsRepository) {
        val ownerKey = messageBodyOwnerKey ?: return@LaunchedEffect
        sharedStores.messageBodyFontSizeStore.ensureLoaded(ownerKey) {
            messageBodySettingsRepository.getAppearanceSettings()
        }
    }
    val selectedMessageBodyFontSize = messageBodyFontSizeState.selectedKey(messageBodyOwnerKey)
    val resolvedMessageBodyTextStyle = rememberRaftMessageBodyTextStyle(selectedMessageBodyFontSize)
    val publishedReplyCacheProvenance by dataSource.replyCacheProvenanceState.collectAsState()
    val replyCacheProvenance =
        publishedReplyCacheProvenance.currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
    var startedRouteIdentity by remember(initialThreadInstanceKey) {
        mutableStateOf<ThreadRouteIdentity?>(null)
    }
    var threadOpenStartedAt by remember(initialThreadInstanceKey) {
        mutableStateOf<TimeMark?>(null)
    }
    var firstRenderLoggedThreadId by remember { mutableStateOf<String?>(null) }
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
        threadOpenStartedAt = TimeSource.Monotonic.markNow()
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
        // task #387: Start was already dispatched in composition via ensureStarted() (inline on
        // the page thread), so this effect only records the open markers; re-dispatching Start
        // here would double the load.
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
            payload.dmPeerIdentity
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
    val headerProjectionServerId =
        remember(replyCacheProvenance?.serverId, currentSession?.currentServerId, storeScope.initialServerId) {
            threadHeaderProjectionServerId(
                sessionServerId =
                    replyCacheProvenance
                        ?.serverId
                        ?.takeIf { it.isNotBlank() }
                        ?: currentSession?.currentServerId,
                scopeInitialServerId = storeScope.initialServerId
            )?.let(::ServerId)
        }
    val memberAvatarResolver =
        remember(serverMembersStore, headerProjectionServerId) {
            raftMemberAvatarResolver(
                serverMembersStore = serverMembersStore,
                serverId = headerProjectionServerId?.value,
                diagnosticsLogger = { message -> bridge.log("thread_avatar_member $message") }
            )
        }
    val memberAppBotResolver =
        remember(serverMembersStore, headerProjectionServerId) {
            raftMemberAppBotResolver(
                serverMembersStore = serverMembersStore,
                serverId = headerProjectionServerId?.value,
            )
        }
    // First-frame header: whatever the app already holds in memory for this exact
    // account+server+conversation (Home sidebar row, identity hub). Canonical state still wins;
    // see displayModelWithKnownCandidate.
    val knownEntityResolver = remember(sharedStores, storeScope) { sharedStores.knownEntityResolver(storeScope) }
    val knownEntityAccountId =
        currentSession?.user?.id ?: sharedStores.currentAuthenticatedRouteOwner(storeScope)?.accountId
    val knownHeaderCandidate =
        remember(
            knownEntityResolver,
            knownEntityAccountId,
            headerProjectionServerId,
            headerProjectionInput,
            sharedHomeState.content
        ) {
            threadHeaderKnownCandidate(
                resolver = knownEntityResolver,
                accountId = knownEntityAccountId,
                serverId = headerProjectionServerId,
                input = headerProjectionInput
            )
        }
    val initialDirectoryConversationProjections =
        remember(
            conversationIdentityProvider,
            headerProjectionServerId,
            conversationDirectoryLookupIds
        ) {
            threadHeaderInitialProjectionMap(
                provider = conversationIdentityProvider,
                serverId = headerProjectionServerId,
                input = headerProjectionInput
            )
        }
    // Offline-first header: the stored channel detail (offline resource) stands in on a cold start or an
    // offline open until the network's detail arrives; it never replaces a detail already there.
    LaunchedEffect(sharedStores, storeScope, headerProjectionServerId, conversationDirectoryLookupIds) {
        val serverId = headerProjectionServerId?.value ?: return@LaunchedEffect
        if (conversationDirectoryLookupIds.isEmpty()) return@LaunchedEffect
        try {
            sharedStores.restoreStoredConversationIdentities(storeScope, serverId, conversationDirectoryLookupIds)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Best effort: the header falls back to its candidates.
        }
    }
    val directoryConversationProjectionFlow = remember(
        conversationIdentityProvider,
        headerProjectionServerId,
        headerProjectionInput.surfaceKind,
        conversationDirectoryLookupIds
    ) {
        if (
            conversationIdentityProvider != null &&
            headerProjectionServerId != null &&
            conversationDirectoryLookupIds.isNotEmpty()
        ) {
            conversationIdentityProvider
                .projectionFlow(headerProjectionServerId, conversationDirectoryLookupIds)
        } else {
            emptyConversationDirectoryProjectionFlow
        }
    }
    val headerProjectionStateOwner =
        remember(headerProjectionInput, initialDirectoryConversationProjections) {
            ThreadHeaderProjectionStateOwner(
                input = headerProjectionInput,
                initialProjections = initialDirectoryConversationProjections
            )
        }
    LaunchedEffect(headerProjectionStateOwner, directoryConversationProjectionFlow) {
        directoryConversationProjectionFlow.collect { projections ->
            val outcome = headerProjectionStateOwner.accept(projections)
            if (
                outcome != null &&
                headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.ReplyThread
            ) {
                bridge.log(
                    "thread_header_reply_diag stage=owner_accept " +
                        "serverId=${headerProjectionServerId?.value ?: "null"} " +
                        "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                        "incoming=${projections.headerProjectionMapDiagnostics()} " +
                        "outcome=${outcome.name} " +
                        "selected=${headerProjectionStateOwner.selectedProjection().headerProjectionDiagnostics()}"
                )
            }
        }
    }
    val directoryConversationProjections by headerProjectionStateOwner.state.collectAsStateWhileStarted()
    val directoryConversationProjection =
        remember(headerProjectionInput, conversationDirectoryLookupIds, directoryConversationProjections) {
            conversationDirectoryLookupIds
                .firstNotNullOfOrNull { id ->
                    directoryConversationProjections[id]?.takeIf { projection ->
                        headerProjectionInput.acceptsForDisplay(projection)
                    }
                }
        }
    // Unfiltered directory projections (header owner drops non-display rows, but a
    // reply thread's joint billing state lives on its parent channel projection).
    val billingDirectoryProjections by directoryConversationProjectionFlow.collectAsStateWhileStarted(
        initialDirectoryConversationProjections
    )
    // Joint channel (or a reply thread in one): message rows may resolve senders from the other
    // server, who are in neither of this server's rosters, through `POST /api/actors/resolve`.
    val jointMessageSurface =
        remember(
            billingDirectoryProjections,
            conversationDirectoryLookupIds,
            payload.channelKind,
            payload.parentSurfaceKind,
            payload.parentChannelKind,
            payload.parentChannelId
        ) {
            threadMessageSurfaceIsJoint(
                payload = payload,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections
            )
        }
    val messageAvatarResolver =
        remember(memberAvatarResolver, jointMessageSurface) {
            memberAvatarResolver.forJointSurface(jointMessageSurface)
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
        val accepted = directoryConversationProjection?.takeIf(headerProjectionInput::acceptsForDisplay)
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
            bridge.log(
                "thread_header_dm_diag stage=accept_check " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${previous.headerProjectionDiagnostics()} " +
                    "accepted=${accepted != null}"
            )
        }
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            bridge.log(
                "thread_header_reply_diag stage=accept_check " +
                    "serverId=${headerProjectionServerId?.value ?: "null"} " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${previous.headerProjectionDiagnostics()} " +
                    "accepted=${accepted != null}"
            )
        }
        if (accepted != null) {
            if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
                bridge.log(
                    "thread_header_dm_diag stage=accepted_store " +
                        "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                        "stored=${accepted.headerProjectionDiagnostics()}"
                )
            }
            lastAcceptedHeaderProjection = accepted
        }
    }
    // Public-page globe on the channel header icon: the server's publicPageLive comes from
    // the session server list (canonical store state), never from the route payload. The External
    // glyph needs no server fact.
    val headerServerPublicPageLive =
        currentSession?.servers?.firstOrNull { it.id == headerProjectionServerId?.value }?.publicPageLive == true
    val headerDisplayModel =
        remember(headerProjectionInput, stableHeaderProjection, payload, headerServerPublicPageLive, knownHeaderCandidate) {
            headerProjectionInput
                .displayModelWithKnownCandidate(
                    projection = stableHeaderProjection,
                    contentPayload = payload,
                    knownCandidate = knownHeaderCandidate
                )
                .copy(serverPublicPageLive = headerServerPublicPageLive)
        }
    // Agent Lounge v0: a Lounge's mute is the Lounge's own (no Home list holds it), so its ⋯ Mute
    // and muted mark follow what the sidebar row's menu and other devices set.
    val mutePageLounge =
        rememberAgentLounge(
            headerProjectionServerId?.value,
            payload.agentLoungeLookupChannelId()?.takeIf { payload.surfaceKind == RaftThreadSurfaceKind.Channel },
        )
    val activityMuteState =
        remember(payload, stableHeaderProjection, sharedHomeStore, mutePageLounge) {
            val state =
                if (mutePageLounge != null) {
                    mutePageLounge.agentLoungeActivityMuteState(joinedHint = stableHeaderProjection?.joined == true)
                } else {
                    payload.threadActivityMuteState(stableHeaderProjection)
                }
            state?.takeIf { sharedHomeStore != null }
        }
    val activityMutedIndicatorVisible =
        remember(payload, stableHeaderProjection, mutePageLounge) {
            mutePageLounge?.activityMuted ?: payload.threadActivityMutedIndicatorVisible(stableHeaderProjection)
        }
    val sidebarOrder =
        remember(sharedHomeState.content) {
            (sharedHomeState.content as? SharedHomeContentState.Content)
                ?.snapshot
                ?.sidebarOrder
        }
    val conversationPinState =
        remember(payload, stableHeaderProjection, sidebarOrder) {
            payload.threadConversationPinTarget(stableHeaderProjection)?.toUiState(sidebarOrder)
        }
    val routeMountPlan =
        payload.threadRouteMountPlan(
            timelineSlice = timelineSlice,
            accessResolution = renderSlice.accessResolution
        )
    // #171 diagnostics: the first content frame lands while the page is still sliding in (it used to
    // wait for the slide behind the loading shell). Isolate LocalRaftStackPageInMotion inside a leaf
    // composable kept across routeMountPlan changes so it logs at most once per threadInstanceKey
    // and never subscribes SharedThreadRouteContentContent to motion state.
    ThreadFirstRevealMotionLogger(
        threadInstanceKey = threadInstanceKey,
        ready = routeMountPlan == ThreadRouteMountPlan.FeatureSurfaces,
        threadId = (threadTimelineKey ?: threadInstanceKey).orEmpty(),
        accessResolutionName = renderSlice.accessResolution.toString(),
        homeLookupComplete = renderSlice.initialHomeAccessLookupComplete,
        revealMessages = timelineSlice.messages.size,
        log = bridge::log,
    )
    val realtimeRoomLeaseRegistry =
        remember(sharedStores, storeScope) { sharedStores.realtimeRoomLeaseRegistry(storeScope) }
    val roomLeaseActive = shouldHoldThreadRoomLease(adaptiveCloseRequest, active)
    DisposableEffect(
        realtimeEvents,
        realtimeRoomLeaseRegistry,
        storeScope.shellInstanceId,
        pager.pagerId,
        adaptiveCloseRequest?.generation,
        currentSession?.currentServerId,
        payload.parentChannelId,
        payload.sourceThreadId,
        payload.streamChannelId,
        roomLeaseActive,
    ) {
        val activeRoomIds = payload.activeRealtimeRoomIds()
        val roomServerId = currentSession?.currentServerId.orEmpty()
        val owner =
            roomServerId.takeIf(String::isNotBlank)?.let { serverId ->
                RealtimeRoomLeaseOwner(
                    shellInstanceId = storeScope.shellInstanceId,
                    serverId = serverId,
                    consumerId = "thread-pager-${pager.pagerId}",
                    generation = adaptiveCloseRequest?.generation ?: 1L,
                )
            }
        if (roomLeaseActive && owner != null) {
            // Task #14c: the reply thread's own room stays joined after the page closes (the parent's does not).
            payload.replyThreadRealtimeRoomIds().forEach { roomId ->
                realtimeRoomLeaseRegistry.keepJoinedUntilClear(owner.serverId, roomId)
            }
            realtimeEvents.applyRoomTransitions(realtimeRoomLeaseRegistry.acquire(owner, activeRoomIds))
        }
        onDispose {
            if (roomLeaseActive && owner != null) {
                realtimeEvents.applyRoomTransitions(realtimeRoomLeaseRegistry.release(owner))
            }
        }
    }
    LaunchedEffect(resolvedAuxiliaryThreadId, active) {
        if (active) {
            onResolvedAuxiliaryThreadIdChanged(resolvedAuxiliaryThreadId)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    ThreadAfterMotionEffect(sharedStores.activityUnreadSummaryStore, currentSession?.currentServerId) {
        val serverId = currentSession?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
            ?: return@ThreadAfterMotionEffect
        sharedStores.activityUnreadSummaryStore.ensure(ServerId(serverId), reason = "thread_route_attach")
    }
    val latestCurrentSessionState = rememberUpdatedState(currentSession)
    val latestCurrentSession by latestCurrentSessionState
    // An offline stand-in (durable last-known frame whose refresh failed) is only replaced by a network
    // answer; nothing re-asked after the network came back, so it stayed until the channel was re-entered
    // (HuaTuo, alpha 71). The host's authoritative online signal now re-requests it in place. A signal
    // that lands while a refresh is still running is remembered (LiBai #2782): that refresh was issued
    // offline and may fail back to the stand-in, so the retry fires once it settles.
    val offlineStandInRecovery = remember(threadViewModel) { OfflineStandInRecoveryGate() }
    // (the gate and attempt counter are per view model, so a new conversation starts clean)
    fun refreshOfflineStandIn(reason: String, current: RaftThreadScreenPayload) {
        bridge.log(
            "thread_offline_standin_refresh reason=$reason " +
                "threadId=${current.sourceThreadId.orEmpty()} status=${current.status}"
        )
        threadViewModel.dispatch(SharedThreadIntent.Refresh)
    }
    // Two recovery signals share one gate: the realtime transport reconnecting (the server is reachable
    // again — the one that matters; the system can report "online" while the server is not, and a
    // per-app block never changes the system state, HuaTuo alpha 74) and the host's authoritative
    // online signal as a supplement.
    DisposableEffect(threadViewModel, sharedStores.eventBus, realtimeEvents) {
        fun onRecovered(reason: String) {
            val current = threadViewModel.state.value.payload
            val refresh = offlineStandInRecovery.onNetworkRecovered(current)
            bridge.log(
                "thread_offline_recovery_signal reason=$reason action=${offlineStandInRecoveryAction(current)} " +
                    "refresh=$refresh status=${current.status} durable=${current.durableLastKnown} " +
                    "mainLoadFailed=${current.mainLoadFailed} error=${current.errorCode ?: "none"} " +
                    "refreshing=${current.backgroundRefreshing}"
            )
            if (refresh) refreshOfflineStandIn(reason, current)
        }
        val hostNetworkEvents = RaftHostNetworkEvents(sharedStores.eventBus)
        // The bus calls listeners on the posting thread (on iOS the realtime host's queue): move to the
        // page's scope on main, where the gate's other caller (onPayloadSettled) runs.
        val networkSubscription =
            hostNetworkEvents.addAuthoritativeOnlineListener { coroutineScope.launch { onRecovered("network_recovered") } }
        val realtimeSubscription =
            realtimeEvents.addListener { eventData ->
                if (isRealtimeTransportConnectedEvent(eventData, latestCurrentSession?.currentServerId)) {
                    coroutineScope.launch { onRecovered("realtime_connected") }
                }
            }
        onDispose {
            hostNetworkEvents.removeListener(networkSubscription)
            realtimeEvents.removeListener(realtimeSubscription)
        }
    }
    LaunchedEffect(
        offlineStandInRecovery,
        payload.durableLastKnown,
        payload.backgroundRefreshing,
        payload.status,
        payload.errorCode
    ) {
        val current = threadViewModel.state.value.payload
        if (offlineStandInRecovery.onPayloadSettled(current)) refreshOfflineStandIn("network_recovered_deferred", current)
    }
    // Signals are not enough on their own: Android never publishes the realtime reconnect to pages, and a
    // per-app block never changes the system network state (HuaTuo alpha 78 logs). While the visible page
    // shows a failed load, it also re-asks on a backoff; a successful answer stops it.
    var offlineRecoveryRetryAttempt by remember(threadViewModel) { mutableStateOf(0) }
    val offlineRecoveryAction = offlineStandInRecoveryAction(payload)
    // Keyed on the view model and the attempt too: a switch to another failed conversation, or a failure
    // that returns within one frame, must restart the timer (LiBai #2782).
    LaunchedEffect(threadViewModel, offlineRecoveryAction, active, offlineRecoveryRetryAttempt) {
        if (offlineRecoveryAction == OfflineStandInRecoveryAction.None) {
            offlineRecoveryRetryAttempt = 0
            return@LaunchedEffect
        }
        if (offlineRecoveryAction != OfflineStandInRecoveryAction.Refresh || !active) return@LaunchedEffect
        delay(offlineRecoveryRetryDelayMs(offlineRecoveryRetryAttempt))
        val current = threadViewModel.state.value.payload
        if (offlineStandInRecoveryAction(current) == OfflineStandInRecoveryAction.Refresh) {
            offlineRecoveryRetryAttempt += 1
            refreshOfflineStandIn("retry_backoff_$offlineRecoveryRetryAttempt", current)
        }
    }
    val threadVisibleResumeGate = remember(threadInstanceKey, storeScope.shellInstanceId) {
        ThreadVisibleResumeGate()
    }
    val threadPreviewOverlayLifecycleFilter = remember(threadInstanceKey, storeScope.shellInstanceId) {
        ThreadPreviewOverlayLifecycleFilter()
    }
    var threadHostForeground by remember(threadInstanceKey, storeScope.shellInstanceId) {
        mutableStateOf(isRaftHostVisible(storeScope.shellInstanceId))
    }
    var threadPointerSessionKey by remember(threadInstanceKey, storeScope.shellInstanceId) {
        mutableStateOf(0)
    }
    val threadMessageLongPressGestureOwnerStore = remember(threadInstanceKey) {
        ThreadMessageLongPressGestureOwnerStore(tapDispatchScope = coroutineScope)
    }
    val latestRouteActive by rememberUpdatedState(active)
    val latestForegroundRejoinRoomIds by rememberUpdatedState(payload.activeRealtimeRoomIds())
    val latestCoreThreadRealtimeEventHandler by rememberUpdatedState<suspend (RealtimeEvent) -> Unit>(
        { event ->
            if (event is Heartbeat) {
                threadViewModel.accept(SharedThreadIntent.RecoverRealtimeGap(event.seq))
            } else {
                val activeReplyParentMessageId =
                    initialPayload.originParentMessageId
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::MessageId)
                if (!event.isTaskProjectionEvent() && event !is MemberPresenceUpdated) {
                    threadViewModel.accept(
                        SharedThreadIntent.ApplyRealtimeEvent(
                            event = event,
                            activeReplyParentMessageId = activeReplyParentMessageId
                        )
                    )
                }
                // task #136: MemberPresenceUpdated now reaches the members store
                // via the provider-installed presence sink on this owner (store
                // lifetime, SSOT) — no page-level tee needed.
            }
        }
    )
    val canonicalFinalThreadSlot =
        remember(
            canonicalFinalAuthority,
            currentSession?.currentServerId,
            storeScope.initialServerId,
        ) {
            val serverId =
                currentSession?.currentServerId
                    ?.takeIf { it.isNotBlank() }
                    ?: storeScope.initialServerId?.takeIf { it.isNotBlank() }
            canonicalFinalAuthority
                ?.grant(serverId ?: return@remember null)
                ?.let { grant -> grant.scopeLeaseId to grant.serverId }
        }

    fun applyThreadVisibleResumePlan(plan: ThreadVisibleResumePlan) {
        threadPointerSessionKey = nextThreadPointerSessionKey(threadPointerSessionKey)
        if (plan.refreshVisibleConversation) {
            threadViewModel.dispatch(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
        }
        if (plan.rejoinVisibleRooms) {
            realtimeEvents.rejoinChannels(
                latestForegroundRejoinRoomIds,
                latestCurrentSession?.currentServerId
            )
        }
    }

    LaunchedEffect(active, threadVisibleResumeGate, threadVisibleResumeGate.pendingVisibleResume) {
        if (active) {
            threadVisibleResumeGate.onRouteBecameVisible(latestCurrentSession?.currentServerId)
                ?.let {
                    bridge.log(
                        "thread_lifecycle_resume_drained refresh=${it.refreshVisibleConversation} " +
                            "rejoin=${it.rejoinVisibleRooms} shell=${storeScope.shellInstanceId}"
                    )
                    applyThreadVisibleResumePlan(it)
                }
        }
    }
    ThreadConversationFreshnessCatchUpEffect(
        serverId = currentSession?.currentServerId,
        active = active,
        contentReady = payload.status == "content" && !payload.mainLoadFailed,
        resumeGate = threadVisibleResumeGate,
        onCatchUp = { trigger ->
            val serverId = latestCurrentSession?.currentServerId
            bridge.log(
                "thread_freshness_catch_up trigger=${trigger.wireName} shell=${storeScope.shellInstanceId}"
            )
            threadViewModel.dispatch(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
            if (trigger == build.raft.app.data.RaftFreshnessTrigger.Reconnect) {
                realtimeEvents.rejoinChannels(latestForegroundRejoinRoomIds, serverId)
            }
        },
    )

    // Pager lifecycle notifications remain mounted with the route core. A
    // loading -> resolved transition must not reset foreground reconciliation.
    DisposableEffect(realtimeEvents) {
        val callbackRef =
            realtimeEvents.addListener { eventData ->
                val eventDataSnapshot = eventData.toString()
                coroutineScope.launch {
                    val lifecycleData = JSONObject(eventDataSnapshot)
                    if (lifecycleData.optString(RaftRealtimeNotifyContract.KeyAction) != RaftRealtimeNotifyContract.ActionAppLifecycle) {
                        return@launch
                    }
                    val event = lifecycleData.optString(RaftRealtimeNotifyContract.KeyLifecycleEvent)
                    val pageName = lifecycleData.optString(RaftRealtimeNotifyContract.KeyPageName)
                    bridge.log(
                        "thread_lifecycle_received event=$event page=$pageName " +
                            "routeActive=$latestRouteActive shell=${storeScope.shellInstanceId}"
                    )
                    val shellTargeted =
                        appLifecycleEventTargetsShell(
                            eventShellInstanceId = lifecycleData.optString(RaftRealtimeNotifyContract.KeyShellInstanceId),
                            routeShellInstanceId = storeScope.shellInstanceId
                        )
                    // The file preview is its own page: opening and closing it pauses and resumes the
                    // thread host. Track that before the page filter so the return is not a resume.
                    val previewOverlayVerdict =
                        if (shellTargeted) {
                            threadPreviewOverlayLifecycleFilter.observe(
                                event = event,
                                pageName = pageName,
                                foregroundEpoch = lifecycleData.optLong(RaftRealtimeNotifyContract.KeyForegroundEpoch, 0L)
                            )
                        } else {
                            ThreadLifecycleVisibilityVerdict.Counts
                        }
                    if (!threadLifecycleEventTargetsThread(pageName)) {
                        bridge.log("thread_lifecycle_drop reason=page_target")
                        return@launch
                    }
                    if (!shellTargeted) {
                        bridge.log("thread_lifecycle_drop reason=shell_target")
                        return@launch
                    }
                    threadHostForeground = isRaftHostVisible(storeScope.shellInstanceId)
                    bridge.log("thread_lifecycle_visibility hostForeground=$threadHostForeground routeActive=$latestRouteActive")
                    if (
                        previewOverlayVerdict == ThreadLifecycleVisibilityVerdict.CoveredByPreview
                    ) {
                        bridge.log("thread_lifecycle_resume_skipped reason=preview_overlay event=$event")
                    } else if (
                        event == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                        event == RaftRealtimeNotifyContract.LifecycleVisibleShow
                    ) {
                        // Foreground is a drainer trigger: re-evaluate journaled sends whose
                        // network may have returned while the app was backgrounded.
                        threadViewModel.triggerPendingSendDrain("foreground_$event")
                        val resumePlan =
                            threadVisibleResumeGate.onLifecycleVisibleResume(
                                routeActive = latestRouteActive,
                                serverId = latestCurrentSession?.currentServerId,
                            )
                        if (resumePlan == null) {
                            bridge.log(
                                "thread_lifecycle_resume_latched_or_skipped event=$event " +
                                    "routeActive=$latestRouteActive pending=${threadVisibleResumeGate.pendingVisibleResume} " +
                                    "shell=${storeScope.shellInstanceId}"
                            )
                        } else {
                            bridge.log(
                                "thread_lifecycle_refresh_dispatch refresh=${resumePlan.refreshVisibleConversation} " +
                                    "rejoin=${resumePlan.rejoinVisibleRooms}"
                            )
                            applyThreadVisibleResumePlan(resumePlan)
                        }
                    }
                }
            }
        onDispose { realtimeEvents.removeListener(callbackRef) }
    }
    // The owner records accepted event keys before fan-out. Keep the canonical
    // Thread sink mounted during LoadingShell so accepted events are never lost
    // behind the route's feature-surface gate.
    DisposableEffect(realtimeDomainEventOwner, authStore, coroutineScope) {
        val sink =
            RealtimeGuardedActiveThreadSink { serverId, event ->
                val currentSessionServerId =
                    authStore.readSession()
                        ?.currentServerId
                        ?.takeIf { it.isNotBlank() }
                        ?: return@RealtimeGuardedActiveThreadSink
                if (currentSessionServerId != serverId) return@RealtimeGuardedActiveThreadSink
                if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@RealtimeGuardedActiveThreadSink
                latestCoreThreadRealtimeEventHandler(event)
            }
        realtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { realtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
    DisposableEffect(
        canonicalFinalLifecycleEffects,
        canonicalFinalThreadSlot,
        canonicalFinalThreadSurfaceRouteKey,
        authStore,
    ) {
        val threadInstanceKey = canonicalFinalThreadSurfaceRouteKey
        val handle =
            canonicalFinalThreadSlot?.let { (scopeLeaseId, serverId) ->
                canonicalFinalLifecycleEffects?.mount(
                    scopeLeaseId = scopeLeaseId,
                    serverId = serverId,
                    surfaceKey = RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore(threadInstanceKey),
                ) { event ->
                    val currentSessionServerId =
                        authStore.readSession()
                            ?.currentServerId
                            ?.takeIf { it.isNotBlank() }
                            ?: return@mount
                    if (currentSessionServerId != serverId) return@mount
                    if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@mount
                    latestCoreThreadRealtimeEventHandler(event)
                }
            }
        onDispose { canonicalFinalLifecycleEffects?.dispose(handle) }
    }
    // Loading still returns before feature-owner construction, but the one movable
    // scaffold instance keeps the route root/header composition when its lightweight
    // body is replaced by the feature body after canonical access resolves.
    val movableThreadRouteScaffold =
        remember(threadInstanceKey) {
            movableContentOf<ThreadRouteScaffoldSlots> { slots ->
                ThreadRouteScaffold(slots)
            }
        }
    if (routeMountPlan == ThreadRouteMountPlan.LoadingShell) {
        val loadingLounge =
            mutePageLounge?.let { lounge ->
                ThreadPageLounge(
                    lounge = lounge,
                    agentAvatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, lounge.agentId),
                    cacheServerId = headerProjectionServerId?.value,
                    canUnarchive = false,
                    unarchiving = false,
                    onUnarchive = {},
                    onOpenAgentProfile = null,
                    onOpenDmAgentLounge = null,
                    dmLoungeOpening = false,
                )
            }
        ThreadDeferredLoadingRouteContent(
            scaffold = movableThreadRouteScaffold,
            payload = payload,
            displayModel = headerDisplayModel,
            memberAvatarResolver = memberAvatarResolver,
            onBack = onBack,
            showLoadingIndicator = timelineSlice.loadingIndicatorRevealed,
            lounge = loadingLounge,
        )
        return
    }
    var selectedThreadTab by remember(threadInstanceKey, payload.selectedTab) {
        mutableStateOf(RaftThreadTab.fromWireValue(payload.selectedTab))
    }
    val availableThreadTabs =
        remember(payload.showTaskOpenActions, payload.showTaskStatusActions, payload.composer.showAsTaskToggle) {
            RaftThreadTab.entries.filter { tab ->
                tab != RaftThreadTab.Tasks || payload.supportsChannelTasks()
            }
        }
    LaunchedEffect(availableThreadTabs, selectedThreadTab) {
        if (selectedThreadTab !in availableThreadTabs) {
            selectedThreadTab = RaftThreadTab.Chat
            threadViewModel.dispatch(SharedThreadIntent.SelectTab(RaftThreadTab.Chat.wireValue))
        }
    }
    var composerSuggestionDemand by remember(threadInstanceKey) {
        mutableStateOf(ThreadComposerSuggestionDemand.None)
    }
    val featureOwnerMountPlan =
        threadFeatureOwnerMountPlan(
            routeMountPlan = routeMountPlan,
            selectedTab = selectedThreadTab.wireValue,
            joined = payload.joined && renderSlice.writeActionsEnabled,
            showComposer = payload.showComposer && renderSlice.writeActionsEnabled,
            suggestionDemand = composerSuggestionDemand
        )
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
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
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
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            bridge.log(
                "thread_header_reply_diag stage=render " +
                    "serverId=${headerProjectionServerId?.value ?: "null"} " +
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
    val foregroundBannerStore =
        remember(sharedStores, storeScope) {
            sharedStores.foregroundBannerStore(storeScope)
        }
    val foregroundMessageBanner by foregroundBannerStore.banner.collectAsStateWhileStarted()
    val foregroundBannerRouteOwnerId = remember { nextForegroundBannerRouteOwnerId("thread") }
    val inAssistantPanelEmbed = LocalThreadAssistantPanelEmbed.current != null
    DisposableEffect(active, payloadRouteIdentity, foregroundBannerStore, inAssistantPanelEmbed, foregroundBannerRouteOwnerId) {
        if (active) {
            foregroundBannerStore.setVisibleRoute(
                ownerId = foregroundBannerRouteOwnerId,
                route = payloadRouteIdentity,
                isOverlay = inAssistantPanelEmbed,
            )
        }
        onDispose { foregroundBannerStore.clearVisibleRoute(foregroundBannerRouteOwnerId) }
    }
    val pendingReactionKeysState = remember(threadInstanceKey) {
        mutableStateOf<Set<ThreadReactionPendingKey>>(emptySet())
    }
    val pendingReactionVisualsState = remember(threadInstanceKey) {
        mutableStateOf<Map<ThreadReactionPendingKey, ThreadReactionPendingVisual>>(emptyMap())
    }
    val pendingReactionVisuals by pendingReactionVisualsState
    val pendingActionCardIdsState = remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    val pendingActionCardIds by pendingActionCardIdsState
    // Task v2: "Create Task from Message" (the message it is open for, its create in flight, its failure).
    val createTaskFromMessage = remember(threadInstanceKey) { TaskCreateFromMessageFlow() }
    // Task v2: a task opened without a page of its own (its empty conversation, or a legacy panel).
    val taskOpenSurfaceFlow = remember(threadInstanceKey) { TaskOpenSurfaceFlow(foregroundBannerStore.taskPendingDrafts) }
    TaskOpenSurfaceIncomingNavigationEffect(taskOpenSurfaceFlow, foregroundBannerStore.incomingNavigation)
    val taskOpenGate = remember(threadInstanceKey) { TaskOpenGate() }
    val pendingThreadFollowMessageIdsState = remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    // Reference-chip opens are single-flight in their store (ignored repeat taps). The busy chip is
    // the reference resolver's fetch phase: a local hit never shows it, a fetch shows it throughout.
    val markdownRefOpenStore =
        remember(threadInstanceKey, dataSource) {
            ThreadMarkdownRefOpenStore(coroutineScope, fetches = dataSource.referenceResolver.inFlight)
        }
    val markdownReferencePending by markdownRefOpenStore.state.collectAsState()
    val textSelectionMessageState = remember(threadInstanceKey) { mutableStateOf<RaftThreadMessageItem?>(null) }
    val createAgentDialogStateHolder = remember(threadInstanceKey) { mutableStateOf<ThreadCreateAgentActionDialogState?>(null) }
    val markdownTaskOpenRequestGate = remember(threadInstanceKey) {
        MarkdownTaskNumberOpenRequestGate()
    }
    val createAgentComputerServerIdState =
        rememberUpdatedState(currentSession?.currentServerId?.takeIf { it.isNotBlank() })
    val createAgentComputerServerResolvedState =
        rememberUpdatedState(producerContextCaptured || currentSession != null)
    val attachmentOpenStatesHolder = remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadAttachmentOpenUiState>>(emptyMap())
    }
    val attachmentOpenStates by attachmentOpenStatesHolder
    val attachmentOperationTokensState = remember(threadInstanceKey) {
        mutableStateOf<Map<String, AttachmentOperationToken>>(emptyMap())
    }
    ThreadAttachmentOperationLifecycleEffects(
        threadInstanceKey = threadInstanceKey,
        attachmentOwner = attachmentOwner,
        attachmentOpenStatesHolder = attachmentOpenStatesHolder,
        attachmentOperationTokensState = attachmentOperationTokensState,
    )
    val nextAttachmentDownloadGenerationState = remember(threadInstanceKey) { mutableStateOf(0L) }
    val inlineVideoPreviewStatesHolder = remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadInlineVideoPreviewUiState>>(emptyMap())
    }
    var inlineVideoPreviewStates by inlineVideoPreviewStatesHolder
    val inlineVideoPreviewJobs = remember(threadInstanceKey) { mutableMapOf<String, Job>() }
    // attachmentId to wake one waiting retry, null to wake all of them (a realtime timeline update).
    val inlineVideoPreviewRetryNudges =
        remember(threadInstanceKey) { MutableSharedFlow<String?>(extraBufferCapacity = 16) }
    val inlineVideoPreviewLastTimelineMessages = remember(threadInstanceKey) { arrayOfNulls<Any>(1) }
    SideEffect {
        val messages = timelineSlice.messages
        if (messages !== inlineVideoPreviewLastTimelineMessages[0]) {
            inlineVideoPreviewLastTimelineMessages[0] = messages
            if (inlineVideoPreviewStates.values.any { it.status == ThreadInlineVideoPreviewStatus.Processing }) {
                inlineVideoPreviewRetryNudges.tryEmit(null)
            }
        }
    }
    val inlineAudioPreviewStatesHolder = remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadInlineAudioPreviewUiState>>(emptyMap())
    }
    var inlineAudioPreviewStates by inlineAudioPreviewStatesHolder
    val attachmentUploadFilesByLocalIdState = remember(threadInstanceKey) {
        mutableStateOf<Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>>(emptyMap())
    }
    var attachmentUploadFilesByLocalId by attachmentUploadFilesByLocalIdState
    val threadDirectUploadClientRequestIds = remember(threadInstanceKey) {
        ThreadComposerDirectUploadClientRequestIds()
    }
    // Rows restored from the durable draft are tracked separately from files admitted by the
    // currently mounted composer.  When a reused Thread instance moves to another draft key we
    // may retire only the previous restoration batch; blindly clearing this map would race an
    // upload which is still owned by the process-level orchestrator.
    var restoredAttachmentUploadDraftKey by remember(threadInstanceKey) {
        mutableStateOf<String?>(null)
    }
    var restoredAttachmentUploadFileIds by remember(threadInstanceKey) {
        mutableStateOf<Set<ThreadAttachmentUploadAttemptIdentity>>(emptySet())
    }
    val externalShareSendRequestedState = remember(threadInstanceKey) { mutableStateOf(false) }
    var externalShareSendRequested by externalShareSendRequestedState
    val pendingMentionActionBatchRequestGate =
        remember(threadInstanceKey) { PendingMentionActionBatchRequestGate() }
    val externalShareExpectedAuthorityState = remember(threadInstanceKey) {
        mutableStateOf<build.raft.app.data.RaftExpectedAuthBinding?>(null)
    }
    var externalShareExpectedAuthority by externalShareExpectedAuthorityState
    val composerImageActionCounterState = remember(threadInstanceKey) { mutableStateOf(0) }
    // Cross-process editor callbacks may arrive out of order and on separate event-bus turns.
    // Keep one process-local accumulator per mounted Thread and serialize offers/claims so a
    // duplicate cannot race the complete N/N handoff.
    val composerImageEventBatchMutex = remember(threadInstanceKey, eventBus) { Mutex() }
    val composerImageEventBatches =
        remember(threadInstanceKey, eventBus) {
            mutableMapOf<ThreadComposerImageEventBatchKey, ThreadComposerImageEventBatchAccumulator>()
        }
    val composerImageEventSessionTracker = remember(threadInstanceKey, eventBus) {
        ThreadComposerImageEventSessionTracker()
    }
    val chatLayoutPreferenceStore =
        remember(pager) {
            KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
        }
    val labels = threadRouteLabels()
    val genericActionCardSuccessLabel = labels.genericActionCardSuccessLabel
    val composerSendKeyEnabled = rememberComposerSendKeyEnabled(chatLayoutPreferenceStore, eventBus)
    val composerSendKey = build.raft.app.data.RaftComposerSendKeyRuntime.observeSendKey()
    val newMessageMenuEnabled = rememberNewMessageMenuEnabled(chatLayoutPreferenceStore, eventBus)
    val attachmentUploadFailedDetailTemplate = labels.attachmentUploadFailedDetailTemplate
    val unableToOpenTaskLabel = labels.unableToOpenTaskLabel
    val genericThreadErrorLabel = labels.genericThreadErrorLabel
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    val attachmentPickerUnavailableLabel = labels.attachmentPickerUnavailableLabel
    val copiedLabel = labels.copiedLabel
    val unableToCreateTaskLabel = labels.unableToCreateTaskLabel
    val unableToSendTaskMessageLabel = labels.unableToSendTaskMessageLabel
    val unableToUpdateTaskLabel = labels.unableToUpdateTaskLabel
    val unableToUpdateAssigneeLabel = labels.unableToUpdateAssigneeLabel
    val unableToJoinChannelLabel = labels.unableToJoinChannelLabel
    val joinedChannelLabel = labels.joinedChannelLabel
    val dropFoldersCantUploadLabel = labels.dropFoldersCantUploadLabel
    val dropFoldersSkippedLabel = labels.dropFoldersSkippedLabel
    val attachmentSkippedManyTemplate = labels.attachmentSkippedManyTemplate
    fun localizedAttachmentUploadFailedDetail(detail: String): String =
        attachmentUploadFailedDetailTemplate.replace(ThreadLocalizedFeedbackPlaceholder, detail)
    fun localizedAttachmentSkippedMany(count: Int): String =
        attachmentSkippedManyTemplate.replace(ThreadLocalizedFeedbackPlaceholder, count.toString())
    val attachmentCommentsDialogStateHolder = remember(threadInstanceKey) {
        mutableStateOf<ThreadAttachmentCommentsDialogState?>(null)
    }
    var attachmentCommentsDialogState by attachmentCommentsDialogStateHolder
    LaunchedEffect(renderSlice.writeActionsEnabled) {
        attachmentCommentsDialogState =
            attachmentCommentsDialogState.retainForThreadWriteAuthority(renderSlice.writeActionsEnabled)
    }

    // Diagnostics only (task #20 t4): marks the START of the first content
    // composition for this thread instance, splitting the previously unmarked
    // span between the store's thread_refresh_result emit and the first
    // per-row thread_markdown_body_recompose marker. remember{} runs during
    // composition, unlike the LaunchedEffect below which fires after it.
    remember(threadInstanceKey, payload.isReadyForThreadPerformanceGate()) {
        if (payload.isReadyForThreadPerformanceGate()) {
            bridge.log(
                "thread_content_compose_start threadId=${(threadTimelineKey ?: threadInstanceKey).orEmpty()} " +
                    "status=${timelineSlice.status} messages=${timelineSlice.messages.size}"
            )
        }
        true
    }
    // Option B P1 v3 census session (task #20, diagnostics only, reviewed):
    // one INSTANCE per thread open — construction is the reset, so there is
    // no global collector state and no callback-order dependency; an
    // abandoned pass discards the instance together with its rows. Provided
    // to the content subtree via LocalRaftMarkdownPlainCandidateSession
    // (below), so non-Thread markdown surfaces never participate, and two
    // interleaved opens hold independent instances. Flushed by the latched
    // LaunchedEffect (effects run after the apply's observer callbacks).
    val plainCandidateCensusSession =
        if (raftTraceDiagnosticsEnabled() && payload.isReadyForThreadPerformanceGate()) {
            remember(threadInstanceKey) { RaftMarkdownPlainCandidateSession() }
        } else {
            null
        }
    // #367 markdown light-path gate (default OFF): resolved ONCE per thread
    // route and provided as an ambient render config — the row-level
    // RaftMarkdownBody performs no registry or StateFlow work of its own.
    // Mirrors the MOBILE_ACTIVITY_STABLE_ORDER wiring (SharedInboxPanel).
    // Identity comes from the reply-cache provenance (the server the route
    // actually renders), never from session.currentServerId alone; an
    // un-captured provenance fails closed to OFF. gateInputs carries the
    // authority epoch, so the decision remember below re-fires when the same
    // identity triple rotates to a new generation.
    val markdownLightPathOverrideRevision by
        ProcessDiagnosticFeatureFlagOverrides.registry
            .revisionFor(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY)
            .collectAsState()
    val markdownLightGateInputs =
        remember(replyCacheProvenance) {
            threadMarkdownLightGateInputsOrNull(replyCacheProvenance)
        }
    val singleParagraphLightPathEnabled =
        markdownLightGateInputs?.let { gateInputs ->
            remember(markdownLightPathOverrideRevision, gateInputs) {
                resolveThreadMarkdownSingleParagraphLightPathEnabled(
                    registry = ProcessDiagnosticFeatureFlagOverrides.registry,
                    serverEffective =
                        sharedStores.serverFeatureFlags.isEnabled(
                            ServerId(gateInputs.serverId),
                            THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
                        ),
                    environmentId = gateInputs.environmentId,
                    accountId = gateInputs.accountId,
                    serverId = gateInputs.serverId
                )
            }
        } ?: false

    // The thread list is always laid out bottom-up (artin 2026-11-14: the Settings switch and the
    // mobile_thread_reverse_layout_v0 flag are gone; a stored choice from older builds is ignored).
    val threadReverseLayoutEnabled = true

    ObserveThreadFirstContentPerformanceGate(
        bridge = bridge,
        payload = payload,
        timelineSlice = timelineSlice,
        threadInstanceKey = threadInstanceKey,
        threadTimelineKey = threadTimelineKey,
        threadReverseLayoutEnabled = threadReverseLayoutEnabled,
        plainCandidateCensusSession = plainCandidateCensusSession,
        threadOpenStartedAt = { threadOpenStartedAt },
    )
    val taskChannelId =
        payload
            .resolvedTaskLookupChannelId(writeActionsEnabled = renderSlice.writeActionsEnabled)
            ?.let(::ChannelId)
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
    val currentServerId = currentSession?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
    val taskConversation =
        rememberThreadTaskConversationState(
            payload = payload,
            authStore = authStore,
            dataSource = dataSource,
            tasksDataSource = tasksDataSource,
            tasksDataStore = tasksDataStore,
            sharedHomeStore = sharedHomeStore,
            sharedStores = sharedStores,
            storeScope = storeScope,
            bridge = bridge,
            currentServerId = currentServerId,
            onBack = onBack,
        )
    val taskParentChannelId = taskConversation.parentChannelId?.value
    // Effective "Collapse long messages": this conversation's device-local override ?: the global switch.
    val longMessageCollapseConversationSetting =
        rememberRaftLongMessageCollapseConversationSetting(
            serverId = currentSession?.currentServerId,
            conversationId =
                payload.longMessageCollapseConversationId(
                    homeContent = sharedHomeState.content,
                    taskParentChannelId = taskConversation.parentChannelId,
                )
        )
    val longMessageCollapseEnabled = longMessageCollapseConversationSetting.effective
    var jointBillingDeadlineTick by remember(payload.instanceId) { mutableStateOf(0) }
    val billingNowEpochMillis =
        payload.nowEpochMillis ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
    val externalFreezeState =
        remember(
            billingDirectoryProjections,
            payload.surfaceKind,
            payload.parentChannelId,
            taskParentChannelId,
            conversationDirectoryLookupIds,
            jointBillingDeadlineTick,
            payload.nowEpochMillis,
        ) {
            externalFreezeState(
                surfaceKind = payload.surfaceKind,
                parentChannelId = payload.parentChannelId,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections,
                nowEpochMillis = billingNowEpochMillis,
                taskParentChannelId = taskParentChannelId,
            )
        }
    val jointBillingNotice =
        remember(
            billingDirectoryProjections,
            payload.surfaceKind,
            payload.parentChannelId,
            taskParentChannelId,
            conversationDirectoryLookupIds,
            jointBillingDeadlineTick,
            payload.nowEpochMillis,
        ) {
            jointBillingNoticeState(
                surfaceKind = payload.surfaceKind,
                parentChannelId = payload.parentChannelId,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections,
                nowEpochMillis = billingNowEpochMillis,
                taskParentChannelId = taskParentChannelId,
            )
        }
    val threadLimitLocked = threadLimitLocksWrites(jointBillingNotice, externalFreezeState)
    val jointGraceDeadlineLabel =
        remember(billingDirectoryProjections, payload.parentChannelId, taskParentChannelId, conversationDirectoryLookupIds) {
            (listOfNotNull(payload.parentChannelId, taskParentChannelId) + conversationDirectoryLookupIds)
                .firstNotNullOfOrNull { id ->
                    billingDirectoryProjections[id]
                        ?.takeIf { it.conversationKind == ConversationKind.Joint }
                        ?.jointOverLimitGraceEndsAt
                }
        }
    val nextChannelLimitDeadline =
        remember(externalFreezeState, jointBillingNotice, jointGraceDeadlineLabel) {
            nextChannelLimitDeadlineIso(externalFreezeState, jointBillingNotice, jointGraceDeadlineLabel)
        }
    // §18.8 / slock #15653: flip Upcoming/Grace -> Frozen/Locked locally at the deadline without
    // waiting for a new server snapshot. Recompute notice state via a state bump (+50ms margin
    // against sub-millisecond wakeup jitter).
    LaunchedEffect(nextChannelLimitDeadline, payload.instanceId) {
        val deadline =
            nextChannelLimitDeadline?.let { parseRaftTimestampEpochMillisOrNull(it, 0) }
        if (deadline != null) {
            val remaining =
                deadline -
                    (payload.nowEpochMillis
                        ?: kotlin.time.Clock.System.now().toEpochMilliseconds())
            if (remaining > 0) {
                delay(remaining + 50L)
                jointBillingDeadlineTick++
            }
        }
    }
    // The channel a bare `#N` chips and opens against (Web `taskContextChannelId`): the task's host
    // channel in a task conversation, and never gated on write access (unlike [taskChannelId]).
    val markdownTaskContextChannelId =
        payload.markdownTaskContextChannelId(taskConversation.parentChannelId?.value)?.let(::ChannelId)
    val memberSuggestionScopePayload =
        if (
            payload.threadComposerMentionScopeChannelId() != null ||
            payload.memberScopedMentions ||
            payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage
        ) {
            payload
        } else {
            initialPayload
    }
    val memberSuggestionScopeChannelId = memberSuggestionScopePayload.threadComposerMentionScopeChannelId()
    val memberSuggestionScopedMentions = memberSuggestionScopePayload.threadComposerMemberScopedMentions()
    // `feedback:submit` cards open the native feedback form on their draft once the viewing server is
    // known (every platform, 1.24; no server flag). A done card's "View in Settings" opens the native
    // ticket page.
    val feedbackCardEntryEnabled = raftFeedbackCardEntryEnabled(currentServerId)
    // Task v2: Tasks and Files are cards opened from the header, not tabs (ConversationSideCards.kt).
    val sideCardsEnabled =
        conversationSideCardsEnabled(
            showModeTabs = payload.showModeTabs,
            assistantPanelEmbed = inAssistantPanelEmbed
        )
    val sideCardsStore = LocalConversationSideCards.current
    val sideCardsState by sideCardsStore.state.collectAsState()
    // The person's open cards, kept across restarts (web keeps them across reloads).
    LaunchedEffect(sideCardsStore, messageBodyOwnerKey) { sideCardsStore.bindOwner(messageBodyOwnerKey) }
    // An old Tasks / Files tab link (route `selectedTab`, a `selectTab` event) opens that card over
    // the conversation; the page itself stays on Chat.
    LaunchedEffect(sideCardsEnabled, selectedThreadTab) {
        if (!sideCardsEnabled) return@LaunchedEffect
        val card = selectedThreadTab.conversationSideCard() ?: return@LaunchedEffect
        if (selectedThreadTab in availableThreadTabs) sideCardsStore.open(card)
        selectedThreadTab = RaftThreadTab.Chat
        threadViewModel.dispatch(SharedThreadIntent.SelectTab(RaftThreadTab.Chat.wireValue))
    }
    // Jump pills (ThreadJumpPills.kt): the unread-thread pill follows the web, a channel only;
    // its threads and the conversation's unread come from Home.
    val jumpHomeSnapshot =
        (sharedHomeState.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.takeIf { snapshot -> snapshot.server.id.value == currentServerId }
    val jumpChannelId = threadTimelineKey?.takeIf { it.isNotBlank() }
    val jumpSurfaceKind = payload.surfaceKind
    val jumpFollowedThreadUnread = jumpHomeSnapshot?.followedThreadUnread
    val jumpConversationUnreadCount =
        jumpHomeSnapshot?.let { snapshot ->
            snapshot.channels.firstOrNull { it.channelId.value == jumpChannelId }?.unreadCount
                ?: snapshot.directMessages.firstOrNull { it.channelId.value == jumpChannelId }?.unreadCount
        } ?: 0
    // Parents of unread threads the page has not loaded, from the followed-threads list (web
    // FollowedThread.parentMessageId): without them the pill had no target for an older parent.
    val jumpUnreadThreadParents by remember(threadViewModel) {
        threadViewModel.unreadThreadParentsFlow()
    }.collectAsState(threadViewModel.state.value.unreadThreadParents)
    val jumpFocusNonce by remember(threadViewModel) {
        threadViewModel.unreadThreadJumpFocusNonceFlow()
    }.collectAsState(threadViewModel.state.value.unreadThreadJumpFocusNonce)
    val jumpLoading by remember(threadViewModel) {
        threadViewModel.unreadThreadJumpLoadingFlow()
    }.collectAsState(threadViewModel.state.value.unreadThreadJumpLoading)
    val jumpInputs =
        remember(
            jumpChannelId,
            jumpSurfaceKind,
            jumpFollowedThreadUnread,
            jumpConversationUnreadCount,
            jumpUnreadThreadParents,
            jumpFocusNonce,
            jumpLoading,
        ) {
            ThreadJumpInputs(
                channelId = jumpChannelId,
                unreadThreadPillEnabled =
                    jumpChannelId != null && jumpSurfaceKind == RaftThreadSurfaceKind.Channel,
                homeFollowedThreadUnread =
                    jumpFollowedThreadUnread?.filterValues { thread -> thread.parentChannelId.value == jumpChannelId },
                conversationUnreadCount = jumpConversationUnreadCount,
                unreadThreadParents = jumpUnreadThreadParents.filterValues { parent -> parent.parentChannelId == jumpChannelId },
                jumpFocusNonce = jumpFocusNonce,
                jumpLoading = jumpLoading,
            )
        }
    // #wg-mobile-sync:#256: a parent's inline replies count its thread's new replies as the channel
    // row's thread mark does (Home's followed-thread unread), so a reply that arrives while the
    // channel is open shows under its parent, not only on the row (ThreadJumpPills.kt).
    val replyMetadataByParentId = rememberInlineReplyMetadata(pageReplyMetadataByParentId, jumpFollowedThreadUnread)
    // The unread threads whose parent is neither loaded nor resolved: look them up (single-flight
    // in the store; each id at most once per page). Keyed by the id set, not the message list.
    val jumpUnresolvedUnreadThreadIds =
        remember(jumpInputs, timelineSlice.messages, replyMetadataByParentId) {
            if (!jumpInputs.unreadThreadPillEnabled) {
                emptySet()
            } else {
                val candidates = threadJumpParentCandidates(timelineSlice.messages, replyMetadataByParentId)
                threadUnreadThreadIdsNeedingParents(
                    unreadThreadIds =
                        threadConversationUnreadThreadIds(
                            channelId = jumpInputs.channelId.orEmpty(),
                            homeFollowedThreadUnread = jumpInputs.homeFollowedThreadUnread,
                            loadedMessages = candidates,
                        ),
                    messages = candidates,
                    resolvedParents = jumpInputs.unreadThreadParents,
                )
            }
        }
    val markUnreadNothingLabel by rememberUpdatedState(stringResource(Res.string.home_action_mark_unread_nothing))
    ThreadJumpFailureToasts(threadViewModel, bridge)
    LaunchedEffect(threadViewModel) {
        threadViewModel.readToggleNoticesFlow().collect { notice ->
            when (notice) {
                RaftReadToggleNotice.MarkUnreadNothing -> bridge.toast(markUnreadNothingLabel)
            }
        }
    }
    LaunchedEffect(threadViewModel, jumpUnresolvedUnreadThreadIds) {
        if (jumpUnresolvedUnreadThreadIds.isNotEmpty()) {
            threadViewModel.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(jumpUnresolvedUnreadThreadIds))
        }
    }
    val taskProjectionOwnerIdentity =
        remember(sharedStores.apiEnvironmentStore, currentSession?.user?.id) {
            currentSession?.user?.id
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { userId ->
                    "${sharedStores.apiEnvironmentStore.current().apiBaseUrl.trimEnd('/')}|$userId"
                }
        }
    // The session is captured a few frames after the page composes. Until then the shell's server
    // keys the member directory, so @mentions in the first content frame are already chips with
    // their names; reading nothing there painted them as plain text and re-laid the rows out as
    // chips a few frames later (Compose 版 open jitter, artin 11-03).
    val threadMembersServerId = threadPageServerId(currentServerId, storeScope.initialServerId)?.let(::ServerId)
    // Composer attachment uploads belong to the draft, not to this page (artin: "不能背后上传吗"):
    // the process-wide owner keeps them going after the user leaves the conversation, and the
    // composer that opens this draft again picks up their progress or result. Keyed on this page's
    // own account and server (the shell scope's server, else the page's captured provenance), never
    // on whichever server the session selects later.
    // The account comes from the signed-in route owner at once, not only from the session this
    // page loads asynchronously: a cached conversation renders its full page on the first frame
    // (#5104), and an upload started from that frame (or by a handler it registered) must land in
    // the same store the composer watches. Before, the first frame keyed the store on account ""
    // and the tile stayed on "Checking file…" (Android 1.27.0, artin; reproduced by Reef).
    val attachmentUploadAccountId =
        (currentSession?.user?.id ?: sharedStores.currentAuthenticatedRouteOwner(storeScope)?.accountId)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    val attachmentUploadServerId =
        threadComposerUploadOwnerServerId(
            shellServerId = storeScope.initialServerId,
            pageServerId = replyCacheProvenance?.serverId,
            sessionServerId = currentServerId
        )
    val attachmentUploads =
        sharedStores.composerDraftUploads.forOwner(attachmentUploadAccountId, attachmentUploadServerId)
    // Until the page knows its account and server it cannot tell a draft-owned upload from an
    // orphan, so the orphan safety net waits for both.
    val attachmentUploadOwnerResolved = attachmentUploadAccountId != null && attachmentUploadServerId != null
    val attachmentUploadOrchestrator = attachmentUploads.orchestrator
    val attachmentUploadSemaphore = attachmentUploads.semaphore
    val threadMembersChannelId = baseRenderedPayload.sourceThreadId?.trim()?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val threadServerMembersEntry by remember(serverMembersStore, threadMembersServerId) {
        if (serverMembersStore != null && threadMembersServerId != null) {
            serverMembersStore.serverMembersEntryFlow(threadMembersServerId)
        } else {
            MutableStateFlow(SharedServerMembersEntry(serverId = threadMembersServerId?.value.orEmpty()))
        }
    }.collectAsState(
        // First frame: the directory already in memory, so @mentions paint names, not handles.
        threadMembersServerId?.let { serverId -> serverMembersStore?.currentServerMembersEntry(serverId) }
            ?: SharedServerMembersEntry(serverId = threadMembersServerId?.value.orEmpty())
    )
    LaunchedEffect(selectedThreadTab, serverMembersStore, threadMembersServerId, sideCardsEnabled && sideCardsState.tasks) {
        val store = serverMembersStore ?: return@LaunchedEffect
        val serverId = threadMembersServerId ?: return@LaunchedEffect
        if (selectedThreadTab == RaftThreadTab.Tasks || (sideCardsEnabled && sideCardsState.tasks)) {
            store.refreshMembers(serverId, DataRequestPriority.Visible)
        }
    }
    // Keep channelMemberSliceFlow keyed independently of `active` so covering this thread during
    // a push never tears down and recreates its member flow on Frame 1; gate only the refresh.
    val threadChannelMembersSlice by remember(
        serverMembersStore,
        threadMembersServerId,
        threadMembersChannelId,
    ) {
        if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.channelMemberSliceFlow(
                serverId = threadMembersServerId,
                channelId = threadMembersChannelId
            )
        } else {
            MutableStateFlow(
                SharedServerChannelMembersSlice(
                    serverId = threadMembersServerId?.value,
                    channelId = threadMembersChannelId?.value.orEmpty()
                )
            )
        }
    }.collectAsStateWhileStarted(
        if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.currentChannelMemberSlice(threadMembersServerId, threadMembersChannelId)
        } else {
            SharedServerChannelMembersSlice(
                serverId = threadMembersServerId?.value,
                channelId = threadMembersChannelId?.value.orEmpty()
            )
        }
    )
    LaunchedEffect(serverMembersStore, threadMembersServerId, threadMembersChannelId, active) {
        if (active && serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.loadChannelMembers(
                serverId = threadMembersServerId,
                channelId = threadMembersChannelId,
                priority = DataRequestPriority.Visible
            )
        }
    }
    val threadMembersProjection =
        remember(baseRenderedPayload, threadChannelMembersSlice, threadServerMembersEntry, threadMembersServerId) {
            threadMembersProjection(
                basePayload = baseRenderedPayload,
                channelMembersSlice = threadChannelMembersSlice,
                serverMembersEntry = threadServerMembersEntry,
                serverId = threadMembersServerId
            )
        }
    val taskProjectionReferences =
        remember(timelineSlice) {
            timelineSlice.threadLinkedTaskReferences()
        }
    val initialTaskProjectionSlice =
        remember(tasksDataStore, taskProjectionOwnerIdentity, currentServerId, taskChannelId?.value) {
            val channelId = taskChannelId
            if (tasksDataStore != null && channelId != null) {
                tasksDataStore.projectionForOwner(
                    expectedOwnerIdentity = taskProjectionOwnerIdentity,
                    serverId = currentServerId,
                    scope = SharedTasksScope.Channel(channelId)
                )
            } else {
                null
            } ?: SharedTasksProjectionSlice()
        }
    val taskProjectionSlice by remember(
        tasksDataStore,
        currentServerId,
        taskChannelId?.value,
        baseRenderedPayload.title
    ) {
        tasksDataStore
            ?.channelProjectionFlow(
                serverId = currentServerId,
                channelId = taskChannelId?.value,
                channelName = baseRenderedPayload.title
            )
            ?.distinctUntilChanged()
            ?: MutableStateFlow(SharedTasksProjectionSlice())
    }.collectAsStateWhileStarted(initialTaskProjectionSlice)
    val initialServerTaskProjectionSlice =
        remember(tasksDataStore, taskProjectionOwnerIdentity, currentServerId) {
            tasksDataStore?.projectionForOwner(
                expectedOwnerIdentity = taskProjectionOwnerIdentity,
                serverId = currentServerId,
                scope = SharedTasksScope.Server
            ) ?: SharedTasksProjectionSlice()
        }
    val serverTaskProjectionSlice by remember(tasksDataStore, currentServerId) {
        tasksDataStore
            ?.projectionFlow(
                serverId = currentServerId,
                scope = SharedTasksScope.Server
            )
            ?.distinctUntilChanged()
            ?: MutableStateFlow(SharedTasksProjectionSlice())
    }.collectAsStateWhileStarted(initialServerTaskProjectionSlice)
    var refreshedTaskProjectionChannelId by remember(threadInstanceKey, currentServerId) {
        mutableStateOf<String?>(null)
    }
    ThreadAfterMotionEffect(
        currentServerId,
        taskChannelId?.value,
        selectedThreadTab,
        payload.status,
        refreshedTaskProjectionChannelId
    ) {
        val serverId = currentServerId ?: return@ThreadAfterMotionEffect
        val resolvedTaskChannelId = taskChannelId ?: return@ThreadAfterMotionEffect
        val sharedStore = tasksDataStore ?: return@ThreadAfterMotionEffect
        val currentPayload = payload.copy(selectedTab = selectedThreadTab.wireValue)
        if (
            !shouldRefreshThreadTasksProjection(
                payload = currentPayload,
                taskChannelId = resolvedTaskChannelId,
                refreshStarted = refreshedTaskProjectionChannelId == resolvedTaskChannelId.value
            )
        ) {
            return@ThreadAfterMotionEffect
        }
        refreshedTaskProjectionChannelId = resolvedTaskChannelId.value
        bridge.log(
            "thread_tasks_projection_refresh_start threadId=${payload.sourceThreadId.orEmpty()} " +
                "channelId=${resolvedTaskChannelId.value} status=${taskProjectionSlice.status.name} " +
                "exactLoaded=${taskProjectionSlice.isExactScopeLoaded}"
        )
        when (
            val result =
                sharedStore.loadSnapshot(
                    serverId = serverId,
                    scope = SharedTasksScope.Channel(resolvedTaskChannelId),
                    forceRefresh = true
                ) {
                    tasksDataSource.loadChannelTasks(
                        serverId = ServerId(serverId),
                        channelId = resolvedTaskChannelId,
                        priority = DataRequestPriority.Background
                    )
                }
        ) {
            is DataResult.Content -> {
                bridge.log(
                    "thread_tasks_projection_refresh_result result=content " +
                        "channelId=${resolvedTaskChannelId.value} tasks=${result.data.tasks.size}"
                )
            }
            is DataResult.Empty -> {
                bridge.log(
                    "thread_tasks_projection_refresh_result result=empty " +
                        "channelId=${resolvedTaskChannelId.value}"
                )
            }
            is DataResult.Error ->
                bridge.log(
                    "thread_tasks_projection_refresh_result result=error " +
                        "channelId=${resolvedTaskChannelId.value} code=${result.error.code}"
                )
            is DataResult.NoPermission ->
                bridge.log(
                    "thread_tasks_projection_refresh_result result=no_permission " +
                        "channelId=${resolvedTaskChannelId.value} code=${result.blocker.code}"
                )
        }
    }
    // The task context channel's tasks (Web `taskByChannelNumber` for `taskContextChannelId`). In a
    // task conversation that is the host channel, whose tasks [taskConversationTasksViewModel] loads.
    val markdownTaskContextChannelName =
        baseRenderedPayload.title.takeUnless { payload.channelKind == RaftTaskConversationChannelKind }
    val markdownTaskContextProjectionSlice by remember(
        tasksDataStore,
        currentServerId,
        markdownTaskContextChannelId?.value,
        markdownTaskContextChannelName
    ) {
        markdownTaskContextChannelId?.let { channelId ->
            tasksDataStore
                ?.channelProjectionFlow(
                    serverId = currentServerId,
                    channelId = channelId.value,
                    channelName = markdownTaskContextChannelName
                )
                ?.distinctUntilChanged()
        } ?: MutableStateFlow(SharedTasksProjectionSlice())
    }.collectAsStateWhileStarted(SharedTasksProjectionSlice())
    // A reader without write access (or whose access is still resolving) has no [taskChannelId], so
    // nothing above loads the context channel's tasks; load them for its task refs.
    LaunchedEffect(currentServerId, markdownTaskContextChannelId?.value, taskChannelId?.value, payload.status) {
        val serverId = currentServerId ?: return@LaunchedEffect
        val contextChannelId = markdownTaskContextChannelId ?: return@LaunchedEffect
        val sharedStore = tasksDataStore ?: return@LaunchedEffect
        if (
            !shouldLoadMarkdownTaskContextTasks(
                payload = payload,
                contextChannelId = contextChannelId,
                taskChannelId = taskChannelId
            )
        ) {
            return@LaunchedEffect
        }
        val result =
            sharedStore.loadSnapshot(
                serverId = serverId,
                scope = SharedTasksScope.Channel(contextChannelId),
                forceRefresh = false
            ) {
                tasksDataSource.loadChannelTasks(
                    serverId = ServerId(serverId),
                    channelId = contextChannelId,
                    priority = DataRequestPriority.Background
                )
            }
        bridge.log(
            "thread_markdown_task_context_load channelId=${contextChannelId.value} " +
                "result=${result::class.simpleName}"
        )
    }
    val markdownTaskStatuses =
        remember(markdownTaskContextProjectionSlice, serverTaskProjectionSlice, markdownTaskContextChannelId) {
            threadMarkdownTaskStatusesByNumber(
                channelTasks = markdownTaskContextProjectionSlice.tasks,
                serverTasks = serverTaskProjectionSlice.tasks,
                currentChannelId = markdownTaskContextChannelId?.value,
                loaded = threadMarkdownTaskSourceLoaded(markdownTaskContextProjectionSlice, serverTaskProjectionSlice)
            )
        }
    val markdownTaskRefStatuses =
        remember(taskProjectionSlice, serverTaskProjectionSlice, payload.dmPeerName, payload.surfaceKind, payload.channelKind) {
            threadMarkdownTaskRefStatuses(
                channelTasks = taskProjectionSlice.tasks,
                serverTasks = serverTaskProjectionSlice.tasks,
                currentDirectMessagePeerName =
                    payload.dmPeerName.takeIf {
                        payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage || payload.channelKind == "dm"
                    }
            )
        }
    val sourceTaskListsEnabled = payload.supportsChannelTasks()
    val linkedTasksProjection =
        remember(
            taskProjectionSlice,
            serverTaskProjectionSlice,
            taskProjectionReferences,
            sourceTaskListsEnabled,
            currentSession?.user?.id
        ) {
            threadLinkedTasksProjection(
                channelProjectionSlice = taskProjectionSlice,
                serverProjectionSlice = serverTaskProjectionSlice,
                references = taskProjectionReferences
            ).copy(
                sourceTaskLists =
                    if (sourceTaskListsEnabled) {
                        taskProjectionSlice.tasks.threadSourceTaskLists()
                    } else {
                        emptyMap()
                    },
                viewerUserId = currentSession?.user?.id
            )
        }
    val taskAssigneeOptions =
        remember(threadChannelMembersSlice, threadMembersProjection) {
            taskAssigneeIdentityOptions(
                channelMembers = threadChannelMembersSlice.members,
                channelMembersLoaded =
                    threadChannelMembersSlice.status == SharedServerMembersLoadStatus.Loaded,
                payloadMembers = threadMembersProjection.members.mapNotNull { it.toMemberProfile() }
            )
        }
    // Channel refs (`#name`) are chips only for channels the reader knows. The store adds those names
    // once its Home lookup answers; the Home snapshot already in memory gives them to the first frame.
    val firstFrameHomeSnapshot =
        (sharedHomeState.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.takeIf { snapshot -> snapshot.server.id == threadMembersServerId }
    val renderedPayload =
        remember(baseRenderedPayload, threadMembersProjection, firstFrameHomeSnapshot) {
            baseRenderedPayload
                .withThreadMembersProjection(threadMembersProjection)
                .withFirstFrameKnownMarkdownChannelNames(firstFrameHomeSnapshot)
        }
    val renderedThread =
        renderedPayload.withThreadWriteActionsEnabled(
            enabled = renderSlice.writeActionsEnabled && !threadLimitLocked,
            preserveSettingsAccess = renderSlice.writeActionsEnabled && threadLimitLocked,
        )
    val linkNavigator = rememberThreadLinkNavigator(pager, bridge, renderedThread)
    val threadViewerIsGuest = currentSession.threadViewerIsGuest(headerProjectionServerId?.value)
    val threadForwardSourceAvailable = renderedPayload.threadForwardSourceChannelId() != null
    val renderedTimelineSlice =
        timelineSlice
            .withThreadWriteActionsEnabled(renderSlice.writeActionsEnabled && !threadLimitLocked)
            .withThreadViewerMessageActions(
                viewerIsGuest = threadViewerIsGuest,
                forwardSourceAvailable = threadForwardSourceAvailable
            )
    val emptyMemberSuggestionSliceFlow = remember { MutableStateFlow(SharedMembersMemberSuggestionSlice()) }
    val memberSuggestionsFlow =
        remember(
            serverMembersStore,
            currentServerId,
            memberSuggestionScopeChannelId,
            memberSuggestionScopedMentions,
            featureOwnerMountPlan.mountMemberSuggestionsOwner
        ) {
            val serverId = currentServerId?.let(::ServerId)
            if (
                featureOwnerMountPlan.mountMemberSuggestionsOwner &&
                serverMembersStore != null &&
                serverId != null
            ) {
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
    val channelSuggestionsFlow = remember(membersStore, featureOwnerMountPlan.mountChannelSuggestionsOwner) {
        membersStore
            ?.takeIf { featureOwnerMountPlan.mountChannelSuggestionsOwner }
            ?.channelSuggestionSliceFlow()
            ?.onStart {
                membersStore.dispatch(SharedMembersIntent.EnsureChannelSuggestions)
            }
            ?.distinctUntilChanged()
            ?: emptyChannelSuggestionsFlow
    }
    val filesViewModel = rememberThreadFilesViewModel(featureOwnerMountPlan.mountFilesOwner, threadInstanceKey, dataSource, bridge, sharedStores)
    val composerDraftViewModel: SharedThreadComposerDraftViewModel =
        viewModel(key = "shared-thread-composer-$threadInstanceKey") {
            SharedThreadComposerDraftViewModel(persistSlices = listOf(composerDraftPersistSlice))
        }
    FlushComposerDraftOnLeave(composerDraftViewModel, threadInstanceKey)
    // dm #119: an open keyboard goes down before the image picker / editor opens and comes back
    // when the user is back at the composer (ThreadComposerKeyboardHandoff.kt).
    val composerKeyboardHandoff =
        rememberThreadComposerKeyboardHandoff(key = threadInstanceKey) {
            composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RequestFocus)
        }
    // A4 adoption (task #18): register the draft-persist barrier + viewport anchor providers
    // with the holder the page-level initiator calls. Registration is keyed on the objects
    // themselves so a rotated detail never serves a stale provider.
    val adaptiveNativeEditingStateReader = LocalThreadAdaptiveNativeEditingReader.current
    val adaptiveInitiationCaptureHolder = LocalThreadAdaptiveInitiationCapture.current
    DisposableEffect(
        adaptiveInitiationCaptureHolder,
        adaptiveNativeEditingStateReader,
        composerDraftViewModel,
        threadInstanceKey,
    ) {
        val holder = adaptiveInitiationCaptureHolder
        val reader = adaptiveNativeEditingStateReader
        if (holder != null && reader != null) {
            holder.captureLatestDraft = captureLatestDraft@{ _ ->
                // Unavailable must abort the migration — proceeding with only the stale shared
                // draft would silently lose whatever is in the live editor (bridge contract).
                val identityBefore = composerDraftViewModel.state.value
                val nativeRead =
                    suspendCancellableCoroutine<RaftTextFieldNativeEditingStateReadResult.Available?> {
                            continuation ->
                        reader.readCurrentValue { readResult ->
                            continuation.resume(
                                readResult as? RaftTextFieldNativeEditingStateReadResult.Available
                            )
                        }
                    } ?: return@captureLatestDraft null
                val nativeText = nativeRead.value.text
                // The draft the native value belongs to is pinned by the state read above;
                // a composer draft switch/rebind mid-read would attribute the persisted text
                // to the wrong conversation.
                val identityAfterRead = composerDraftViewModel.state.value
                if (
                    identityAfterRead.draftKey != identityBefore.draftKey ||
                    identityAfterRead.instanceId != identityBefore.instanceId
                ) {
                    return@captureLatestDraft null
                }
                val changeReceipt =
                    runCatchingPreservingCancellation {
                        composerDraftViewModel.acceptQueued(
                            SharedThreadComposerDraftIntent.Change(seedText = nativeText)
                        )
                    }.getOrElse { return@captureLatestDraft null }
                // The receipt state must still carry the same draft identity we started from.
                // A rejected (accepted=false) receipt is a satisfied no-op only when the
                // serialized lane proves the store already carries the exact native text —
                // MviFoundation reports an equivalent-state transition as not-accepted with
                // state=previous. Any other rejection means the draft projection did not
                // accept our text, and persisting would durably store the wrong content.
                if (
                    changeReceipt.state.draftKey != identityBefore.draftKey ||
                    changeReceipt.state.instanceId != identityBefore.instanceId
                ) {
                    return@captureLatestDraft null
                }
                val changeSatisfied =
                    changeReceipt.accepted ||
                        changeReceipt.state.seedText == nativeText
                if (!changeSatisfied) {
                    return@captureLatestDraft null
                }
                val flushReceipt =
                    runCatchingPreservingCancellation {
                        composerDraftViewModel.acceptQueued(
                            SharedThreadComposerDraftIntent.Flush
                        )
                    }.getOrElse { return@captureLatestDraft null }
                if (
                    !flushReceipt.accepted ||
                    flushReceipt.state.draftKey != identityBefore.draftKey ||
                    flushReceipt.state.instanceId != identityBefore.instanceId
                ) {
                    return@captureLatestDraft null
                }
                // Identity-bearing snapshot: the pre-commit fence compares a later capture
                // field-for-field — same text under a different draft/instance is drift.
                SharedThreadComposerDraftSnapshot(
                    text = nativeText,
                    draftKey = identityBefore.draftKey,
                    instanceId = identityBefore.instanceId,
                    editorNativeRef = nativeRead.editorNativeRef,
                )
            }
        }
        onDispose {
            if (holder != null && holder.captureLatestDraft != null) {
                holder.captureLatestDraft = null
            }
        }
    }
    // Warm the upload-capability cache when the composer opens so a later pick adds its chip
    // without waiting on `attachments/upload-capabilities` (slow on weak networks).
    LaunchedEffect(threadInstanceKey) {
        runCatchingPreservingCancellation { dataSource.prefetchAttachmentUploadCapabilities() }
    }
    LaunchedEffect(composerDraftViewModel, threadInstanceKey, attachmentUploads, attachmentUploadOwnerResolved) {
        composerDraftViewModel.state.collect { draftState ->
            val draftKey = normalizeThreadAttachmentUploadDraftKey(draftState.draftKey)
            val previousRestoredKey = restoredAttachmentUploadDraftKey
            if (draftKey != null && previousRestoredKey != null && draftKey != previousRestoredKey) {
                // A reused Thread surface moved its composer store from draft A to draft B: A's
                // uploads keep going (they belong to draft A, not to this page) and publish only
                // into draft A's uploads, never into B.
                // The map key is the complete (draft,id) identity.  Retire every handle owned by
                // the old draft; a same-named local id admitted by the new draft is a different
                // key and therefore remains untouched.  This also prevents a late old-job
                // cleanup from deleting or shadowing the new draft's handle.
                attachmentUploadFilesByLocalId =
                    attachmentUploadFilesByLocalId.filterKeys { key -> key.draftKey != previousRestoredKey }
                restoredAttachmentUploadFileIds =
                    restoredAttachmentUploadFileIds.filter { key -> key.draftKey != previousRestoredKey }.toSet()
            }

            // A transient null key can be emitted while a route is being rebound.  Keep the last
            // non-empty key so the first real key after an initial null does not clear an active
            // session, while a genuine A -> B transition still retires the old restoration batch.
            if (draftKey == null) return@collect
            if (draftKey != previousRestoredKey) {
                restoredAttachmentUploadDraftKey = draftKey
            }

            val restoredFiles =
                draftState.uploadedAttachments
                    .mapNotNull { attachment ->
                        attachment.toRestoredThreadComposerPlatformFile()?.let { file ->
                            attachment.id to file
                        }
                    }
                    .toMap()
            if (restoredFiles.isNotEmpty()) {
                // Never shadow the handle a live attempt of this page registered; a draft-owned
                // upload started by a page that is gone gets the restored file (Retry after a
                // background failure).
                val safeRestoredFiles =
                    restoredFiles.filterKeys { id ->
                        attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, draftKey) == null
                    }
                if (safeRestoredFiles.isNotEmpty()) {
                    val keyedFiles =
                        safeRestoredFiles.mapNotNull { (id, file) ->
                            threadAttachmentUploadAttemptIdentity(id, draftKey)?.let { identity ->
                                identity to file
                            }
                        }.toMap()
                    attachmentUploadFilesByLocalId = attachmentUploadFilesByLocalId + keyedFiles
                    restoredAttachmentUploadFileIds = restoredAttachmentUploadFileIds + keyedFiles.keys
                }
            }
            // A restored row whose upload nothing owns any more (the process died meanwhile, or
            // the upload was cancelled) can never finish: fail it (upload_interrupted) so the chip
            // offers Retry with the file registered above, instead of blocking Send forever. The
            // draft's own uploads are left to the applier below.
            if (!attachmentUploadOwnerResolved) return@collect
            recoverOrphanedThreadComposerUploads(
                draftKey = draftKey,
                state = draftState,
                acceptWithState = composerDraftViewModel::acceptWithState,
                currentState = { composerDraftViewModel.state.value },
                orchestrator = attachmentUploadOrchestrator,
                log = { message -> bridge.logWarn(message) },
                ownedByDraftUpload = { id -> attachmentUploads.ownsUpload(draftKey, id) }
            )
        }
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

    // A card execute refused for a fresh second step (two-step verification on; approver-class
    // cards: Approve Login, Install App, and their spec-card forms): "Verify it's you", then the
    // card runs once more. One dialog for the typed commit, the install confirmation and spec cards.
    val actionCardStepUp =
        remember(sharedStores, storeScope, threadInstanceKey) {
            threadActionCardStepUpCoordinator(sharedStores.networkClient(storeScope))
        }
    val actionCardStepUpState by actionCardStepUp.state.collectAsState()
    // web InstallAppCardDialog: an install card's button opens this; only its Install executes.
    val installAppDialogStore =
        remember(threadInstanceKey) {
            threadInstallAppActionDialogStore(
                scope = coroutineScope,
                dataSource = dataSource,
                applyCard = { messageId, card -> threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(messageId, card)) },
                // The card's done line names what was installed.
                toastInstalled = { bridge.toast(genericActionCardSuccessLabel) },
                routeAuthRequired = { result -> routeAuthRequired(result) },
                stepUp = actionCardStepUp,
            )
        }
    val installAppDialogStateHolder = installAppDialogStore.state.collectAsState()

    // A4: spec card Buttons that run the card (execute, its confirm, a form) go through this store
    // to SharedThreadDataSource.executeActionCardSpec; links and the typed path don't
    // (threadActionCardSpecPress).
    val actionCardSpecStore =
        remember(threadInstanceKey) {
            ThreadActionCardSpecStore(
                scope = coroutineScope,
                execute = { messageId, card, form -> dataSource.executeActionCardSpec(messageId, card, form) },
                stepUp = actionCardStepUp,
                applyCard = { messageId, card -> threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(messageId, card)) },
                onNoPermission = { result -> routeAuthRequired(result) },
                loadWebUrl = { sharedStores.apiEnvironmentStore.current().webUrl },
                openInBrowser = { url ->
                    openThreadActionCardWebUrl(
                        url = url,
                        activeWebUrl = sharedStores.apiEnvironmentStore.current().webUrl,
                        pager = pager,
                        storeScope = storeScope,
                        bridge = bridge,
                    )
                },
                onApproved = RaftRatingPrompt::onActionCardApproved,
            )
        }
    // The page reads only the open confirm / form; each card reads its own slice (ThreadActionCardSpecView).
    val actionCardSpecDialogsFlow =
        remember(actionCardSpecStore) {
            actionCardSpecStore.state.map { ThreadActionCardSpecDialogs(it.confirm, it.form) }.distinctUntilChanged()
        }
    val actionCardSpecState by actionCardSpecDialogsFlow.collectAsState(ThreadActionCardSpecDialogs())
    val actionCardAlreadyExecutedLabel = stringResource(Res.string.thread_action_card_already_executed)
    val actionCardAlreadyExecutedByTemplate =
        stringResource(Res.string.thread_action_card_already_executed_by, ThreadLocalizedFeedbackPlaceholder)
    val actionCardOpenInWebFailedLabel = stringResource(Res.string.thread_action_card_open_in_web_failed)
    val actionCardFrozenClosedLabel = stringResource(Res.string.thread_action_card_frozen_closed)
    LaunchedEffect(actionCardSpecStore) {
        actionCardSpecStore.effects.collect { effect ->
            when (effect) {
                is ThreadActionCardSpecEffect.AlreadyExecuted ->
                    bridge.toast(
                        when {
                            effect.frozen -> actionCardFrozenClosedLabel
                            effect.executedByUserName != null ->
                                actionCardAlreadyExecutedByTemplate.replace(ThreadLocalizedFeedbackPlaceholder, effect.executedByUserName)
                            else -> actionCardAlreadyExecutedLabel
                        }
                    )
                ThreadActionCardSpecEffect.OpenInWebFailed -> bridge.toast(actionCardOpenInWebFailedLabel)
            }
        }
    }
    val latestActionCardThread by rememberUpdatedState(renderedThread)
    val latestActionCardServerSlug by rememberUpdatedState(currentServerSlug)
    // Home's DM rows: a navigate to a message in a DM opens the DM route.
    val actionCardDirectMessageIds =
        remember(sharedHomeState.content) {
            (sharedHomeState.content as? SharedHomeContentState.Content)?.baseSnapshot?.directMessages.orEmpty().mapTo(HashSet()) { it.channelId.value }
        }
    val latestActionCardDirectMessageIds by rememberUpdatedState(actionCardDirectMessageIds)
    // Not keyed on the store's state: each card selects its own slice of it.
    val actionCardSpecHost =
        remember(feedbackCardEntryEnabled, actionCardSpecStore, currentSession?.user?.id) {
            ThreadActionCardSpecHost(
                capabilities = raftAppActionCardCapabilities(feedbackFormAvailable = feedbackCardEntryEnabled),
                state = actionCardSpecStore.state,
                onIntent = actionCardSpecStore::dispatch,
                openInWeb = { messageId ->
                    actionCardSpecStore.dispatch(
                        ThreadActionCardSpecIntent.OpenInWeb(
                            messageId,
                            latestActionCardThread.threadMessageLink(messageId, latestActionCardServerSlug),
                        )
                    )
                },
                isDirectMessageChannel = { channelId -> channelId in latestActionCardDirectMessageIds },
                viewerUserId = currentSession?.user?.id,
                clientOs = featureFlagClientOs(),
                openFeedbackTicket = { ticketId ->
                    bridge.log("thread_feedback_ticket_open hasTicket=${ticketId != null}")
                    pager.openSettingsFeedbackPage(storeScope, ticketId = ticketId)
                },
                openPlan = {
                    bridge.log("thread_view_plan_open")
                    pager.openSettingsBillingPage(storeScope)
                },
                log = { line -> bridge.log(line) },
            )
        }

    LaunchedEffect(taskConversation.tasksViewModel, unableToUpdateTaskLabel) {
        taskConversation.tasksViewModel?.uiEvents?.collect { event ->
            if (event is SharedTasksEvent.OperationFailed) {
                bridge.showTaskOperationFailedToast(event, unableToUpdateTaskLabel, unableToUpdateAssigneeLabel) {
                    routeAuthRequired(it)
                }
            }
        }
    }

    LaunchedEffect(tasksViewModel, unableToUpdateTaskLabel) {
        tasksViewModel?.uiEvents?.collect { event ->
            if (event is SharedTasksEvent.OperationFailed) {
                bridge.showTaskOperationFailedToast(event, unableToUpdateTaskLabel, unableToUpdateAssigneeLabel) {
                    routeAuthRequired(it)
                }
            }
        }
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

    val latestThreadFeatureRealtimeEventHandler by rememberUpdatedState<suspend (String, RealtimeEvent) -> Unit>(
        { serverId, event ->
            if (event is TaskUpdated) {
                tasksViewModel?.applyRealtimeTaskUpdate(serverId, event.task)
                taskConversation.tasksViewModel?.applyRealtimeTaskUpdate(serverId, event.task)
                taskConversation.headerViewModel?.onTaskSeen(event.task)
            }
            if (event is TaskDeleted) {
                taskConversation.headerViewModel?.onTaskDeleted(event.taskId, event.conversationChannelId)
            }
            if (event is ThreadFollowersUpdated) {
                taskConversation.headerViewModel?.onFollowersUpdated(serverId, event.threadChannelId)
            }
            if (
                event.refreshesThreadMemberSuggestions(memberSuggestionScopeChannelId) &&
                (
                    featureOwnerMountPlan.mountMemberSuggestionsOwner ||
                        featureOwnerMountPlan.mountChannelSuggestionsOwner
                )
            ) {
                currentServerId?.let { serverId ->
                    if (featureOwnerMountPlan.mountMemberSuggestionsOwner) {
                        serverMembersStore?.ensureMemberSuggestionsForServer(
                            serverId = ServerId(serverId),
                            mentionChannelId = memberSuggestionScopeChannelId,
                            memberScopedMentions = memberSuggestionScopedMentions,
                            force = true
                        )
                    }
                }
                if (featureOwnerMountPlan.mountChannelSuggestionsOwner) {
                    membersStore?.acceptRealtimeChannelSuggestionsRefresh()
                }
            }
        }
    )
    suspend fun deliverThreadFeatureRealtimeEvent(serverId: String, event: RealtimeEvent) {
        latestThreadFeatureRealtimeEventHandler(serverId, event)
    }
    DisposableEffect(
        canonicalFinalLifecycleEffects,
        canonicalFinalThreadSlot,
        canonicalFinalThreadSurfaceRouteKey,
    ) {
        val threadInstanceKey = canonicalFinalThreadSurfaceRouteKey
        val handle =
            canonicalFinalThreadSlot?.let { (scopeLeaseId, serverId) ->
                canonicalFinalLifecycleEffects?.mount(
                    scopeLeaseId = scopeLeaseId,
                    serverId = serverId,
                    surfaceKey = RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature(threadInstanceKey),
                ) { event ->
                    if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@mount
                    deliverThreadFeatureRealtimeEvent(serverId, event)
                }
            }
        onDispose { canonicalFinalLifecycleEffects?.dispose(handle) }
    }
    // Feature-only reactions can mount with resolved content. The core sink
    // above already owns canonical Thread apply and gap recovery.
    DisposableEffect(realtimeDomainEventOwner, coroutineScope, currentServerId) {
        val sink =
            RealtimeGuardedActiveThreadSink { serverId, event ->
                if (currentServerId != serverId) return@RealtimeGuardedActiveThreadSink
                if (!shouldDeliverToActiveThreadRealtimeSignal(event)) return@RealtimeGuardedActiveThreadSink
                deliverThreadFeatureRealtimeEvent(serverId, event)
            }
        realtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { realtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
    val inlineImageFallbackStateHolder = remember(threadInstanceKey) {
        mutableStateOf(ThreadInlineImageFallbackState())
    }
    var inlineImageFallbackState by inlineImageFallbackStateHolder
    val inlineImageUrlBatcher =
        remember(threadInstanceKey, coroutineScope, data.dataSource, sharedStores, storeScope) {
            ThreadInlineAttachmentUrlBatcher(
                coroutineScope = coroutineScope,
                ownerProvider = { sharedStores.currentAuthenticatedRouteOwner(storeScope) },
                fetchBatch = { ids -> data.dataSource.attachmentUrls(DataRequestPriority.Visible, ids) },
                readState = { inlineImageFallbackStateHolder.value },
                writeState = { next -> inlineImageFallbackStateHolder.value = next },
                logger = { line -> data.bridge.log(line) },
            )
        }
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
    // The assistant's DM in its panel: the conversation the panel was opened from rides on this
    // composer (a chip, and its token on the next message; ThreadAssistantContext.kt). Carried by
    // the surface, which the send path already reads (see the JVM-limit note further down).
    val assistantPanelContext = LocalThreadAssistantPanelEmbed.current?.context
    val composerSurface =
        remember(
            scopedBaseComposerState,
            resolvedAuxiliaryThreadId,
            renderedPayload.parentChannelId,
            headerDisplayModel.title,
            headerDisplayModel.subtitle,
            renderedPayload.surfaceKind,
            renderedPayload.parentSurfaceKind,
            assistantPanelContext
        ) {
            scopedBaseComposerState.toThreadComposerSurface(
                channelId = resolvedAuxiliaryThreadId.orEmpty(),
                parentChannelId = renderedPayload.parentChannelId,
                mentionChannelLabel = headerDisplayModel.threadComposerMentionScopeChannelLabel(),
                memberSuggestionHeadersVisible = renderedPayload.threadComposerMemberSuggestionHeadersVisible()
            ).withTaskV2().copy(assistantContext = assistantPanelContext)
        }
    fun currentComposerPayload(): RaftThreadComposerPayload =
        scopedBaseComposerState.withThreadComposerDraftState(
            draftState = composerDraftViewModel.state.value,
            renderedPayload = renderedPayload,
            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId
        )
    val agentStatusResolver =
        remember(serverMembersStore, headerProjectionServerId) {
            raftScopedAgentStatusResolver(serverMembersStore, headerProjectionServerId?.value)
        }
    // An agent's DM shows its live activity in the header: detailed activity while the DM is open.
    RaftAgentActivitySubscriptionEffect(
        subscriptions = sharedStores.agentActivitySubscriptions,
        serverId = headerProjectionServerId?.value,
        agentIds = listOfNotNull(headerDisplayModel.dmAgentActivityMemberId()),
    )
    val messagePermalinkLabelResolver =
        remember(
            conversationIdentityProvider,
            currentSession?.currentServerId,
            currentServerSlug,
            renderedThread,
            stableHeaderProjection
        ) {
            { target: ParsedRaftPermalink ->
                val currentServerId = currentSession?.currentServerId?.takeIf(String::isNotBlank)
                val sameServer =
                    currentServerSlug?.takeIf(String::isNotBlank)?.let { slug ->
                        target.serverSlug.equals(slug, ignoreCase = true)
                    } ?: true
                if (!sameServer || currentServerId == null) {
                    null
                } else {
                    conversationIdentityProvider
                        ?.resolveProjection(ServerId(currentServerId), target.channelId.value)
                        ?.title
                        ?: target.channelId.value
                            .takeIf { id -> id in renderedThread.activeRealtimeRoomIds() }
                            ?.let { stableHeaderProjection?.title ?: renderedThread.title }
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
    // The desktop / wide image lightbox (RaftImageLightbox.kt): open while non-null.
    val imageLightboxWindowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    val imageLightboxEnabled = raftImageLightboxEnabled(raftPlatformHasFinePointer(), imageLightboxWindowWidthDp)
    val imageLightboxRequestState = remember(threadInstanceKey) { mutableStateOf<RaftImageLightboxRequest?>(null) }
    var imageLightboxRequest by imageLightboxRequestState
    // The image being edited from the image viewer (RaftViewerImageEdit.kt); null: none.
    var viewerImageEditAttachment by remember(threadInstanceKey) { mutableStateOf<RaftThreadAttachmentItem?>(null) }
    // The attachment open and comment actions (ThreadRouteAttachmentActions.kt), built every
    // composition by a local function (a method of its own, so this one only loads what it captures
    // for the call). The route still calls them by these local functions' names.
    fun buildThreadRouteAttachmentActions(): ThreadRouteAttachmentActions =
        ThreadRouteAttachmentActions(
            bridge = data.bridge,
            dataSource = data.dataSource,
            pager = pager,
            coroutineScope = coroutineScope,
            threadViewModel = threadViewModel,
            composerDraftViewModel = composerDraftViewModel,
            renderedTimelineSlice = renderedTimelineSlice,
            threadInstanceKey = threadInstanceKey,
            attachmentOwner = attachmentOwner,
            routeOwnerProvider = { sharedStores.currentAuthenticatedRouteOwner(storeScope) },
            inlineImageUrlBatcher = inlineImageUrlBatcher,
            labels = labels,
            imageLightboxEnabled = imageLightboxEnabled,
            attachmentCommentsDialogStateHolder = attachmentCommentsDialogStateHolder,
            inlineImageFallbackStateHolder = inlineImageFallbackStateHolder,
            imageLightboxRequestState = imageLightboxRequestState,
            attachmentOpenStatesHolder = attachmentOpenStatesHolder,
            attachmentOperationTokensState = attachmentOperationTokensState,
            nextAttachmentDownloadGenerationState = nextAttachmentDownloadGenerationState,
            inlineVideoPreviewStatesHolder = inlineVideoPreviewStatesHolder,
            inlineVideoPreviewJobs = inlineVideoPreviewJobs,
            inlineVideoPreviewRetryNudges = inlineVideoPreviewRetryNudges,
            inlineAudioPreviewStatesHolder = inlineAudioPreviewStatesHolder,
            attachmentUploadFilesByLocalIdState = attachmentUploadFilesByLocalIdState,
            composerImageActionCounterState = composerImageActionCounterState,
            composerImageEventSessionTracker = composerImageEventSessionTracker,
            routeAuthRequiredForCodeFn = { code -> routeAuthRequired(code) },
            routeAuthRequiredFn = { result -> routeAuthRequired(result) },
            currentComposerPayloadFn = { currentComposerPayload() },
        )
    val threadRouteAttachmentActions = buildThreadRouteAttachmentActions()
    fun requestInlineImageFallback(attachmentId: String): Unit =
        threadRouteAttachmentActions.requestInlineImageFallback(attachmentId)
    fun handleInlineImageFallbackError(attachmentId: String, failedUrl: String): Unit =
        threadRouteAttachmentActions.handleInlineImageFallbackError(attachmentId, failedUrl)
    fun openImagePreview(group: ThreadImageAttachmentGroup, conversationWide: Boolean = true): Unit =
        threadRouteAttachmentActions.openImagePreview(group, conversationWide)
    fun openAttachmentExternally(attachment: RaftThreadAttachmentItem): Unit =
        threadRouteAttachmentActions.openAttachmentExternally(attachment)
    fun saveAttachmentExternally(attachment: RaftThreadAttachmentItem): Unit =
        threadRouteAttachmentActions.saveAttachmentExternally(attachment)
    fun openAttachmentNoPreview(attachment: RaftThreadAttachmentItem): Boolean =
        threadRouteAttachmentActions.openAttachmentNoPreview(attachment)
    fun reloadAttachmentComments(): Unit = threadRouteAttachmentActions.reloadAttachmentComments()
    fun sendAttachmentComment(): Unit = threadRouteAttachmentActions.sendAttachmentComment()
    fun resolveAttachmentComment(comment: ThreadAttachmentCommentItem): Unit =
        threadRouteAttachmentActions.resolveAttachmentComment(comment)
    fun requestInlineAudioPreview(attachmentId: String, force: Boolean = false): Unit =
        threadRouteAttachmentActions.requestInlineAudioPreview(attachmentId, force)
    fun requestInlineVideoPreview(attachmentId: String, force: Boolean = false): Unit =
        threadRouteAttachmentActions.requestInlineVideoPreview(attachmentId, force)
    // Dialogs and pages over the thread are local composables only to keep this method small: each is a
    // JVM method of its own. Local composables are not restartable, so they run whenever this one does.
    @Composable
    fun ThreadImageLightboxSection() {
        imageLightboxRequest?.let { request ->
            RaftImageLightbox(
                request = request,
                resolveFullUrl = { attachmentId ->
                    val owner = sharedStores.currentAuthenticatedRouteOwner(storeScope)?.normalizedOrNull()
                    val cacheKey = owner?.let { RaftFilePreviewAttachmentUrlCacheKey(it, attachmentId.trim(), "inline") }
                    cacheKey?.let(raftFilePreviewAttachmentUrlCache()::get)?.let { return@RaftImageLightbox it }
                    when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline")) {
                        is ThreadApiResult.Success -> {
                            if (cacheKey != null) {
                                raftFilePreviewAttachmentUrlCache().put(
                                    key = cacheKey,
                                    url = response.data.url,
                                    expiresAtEpochMillis = parseRaftTimestampEpochMillisOrNull(response.data.expiresAt),
                                )
                            }
                            response.data.url
                        }
                        is ThreadApiResult.Failure -> {
                            bridge.log("thread_image_lightbox status=url_failed attachmentId=${attachmentId.take(8)} code=${response.code}")
                            null
                        }
                    }
                },
                onFullUrlError = { attachmentId, failedUrl ->
                    val owner = sharedStores.currentAuthenticatedRouteOwner(storeScope)?.normalizedOrNull()
                    if (owner != null) {
                        raftFilePreviewAttachmentUrlCache().invalidate(
                            key = RaftFilePreviewAttachmentUrlCacheKey(owner, attachmentId.trim(), "inline"),
                            expectedUrl = failedUrl,
                        )
                    }
                },
                onDismiss = { imageLightboxRequest = null },
                onDownload = { attachment -> saveAttachmentExternally(attachment) },
                onShare =
                    if (raftPlatformSupportsPublicAttachmentDownloadReadback()) {
                        { attachment -> threadRouteAttachmentActions.openAttachmentExternally(attachment, share = true) }
                    } else {
                        null
                    },
                onOpenWith = { attachment -> openAttachmentExternally(attachment) },
                onEdit = { attachment -> viewerImageEditAttachment = attachment },
                // The phone viewer's Save to Photos, on the bar wherever there is a photo library.
                onSaveToPhotos =
                    if (raftPlatformSupportsSaveImageToPhotos()) {
                        { attachment -> threadRouteAttachmentActions.saveImageToPhotos(attachment) }
                    } else {
                        null
                    }
            )
        }
    }
    ThreadImageLightboxSection()
    // Render-axis signature: only the fields the render log actually reads.
    // Members / settings / actions / realtime metadata / files payload
    // are intentionally excluded so they do not retrigger the render log.
    // See ThreadRenderSignature / Test.
    val performanceGenerationCounter = remember(threadInstanceKey) {
        ThreadPerformanceGenerationCounter()
    }
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
    val reconcileGeneration = remember(renderSignature) {
        performanceGenerationCounter.nextReconcile()
    }
    LaunchedEffect(renderSignature, reconcileGeneration) {
        bridge.performanceMarker(
            phase = "thread_reconcile_generation",
            routeName = "kuikly_thread",
            threadId = renderSignature.sourceThreadId,
            metadata =
                mapOf(
                    "generation" to reconcileGeneration.toString(),
                    "status" to renderSignature.status,
                    "messages" to renderSignature.messagesSize.toString()
                )
        )
        bridge.log(
            "thread_render instanceId=${renderSignature.instanceId} status=${renderSignature.status} tab=${renderSignature.selectedTab} " +
                "messages=${renderSignature.messagesSize} " +
                "showReplyActions=${renderSignature.showReplyActions} replyRows=${renderSignature.replyRowsSize} " +
                "firstReply=${if (renderSignature.firstReplyId8 == null) "none" else "${renderSignature.firstReplyId8}:${renderSignature.firstReplyCount}/${renderSignature.firstReplyUnreadCount}"}"
        )
    }
    // task #232: rendered visible tail reported by the messages list; the read
    // marker must track what the user sees, not the loaded payload tail.
    var visibleReadTailIndex by remember(threadInstanceKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(visibleReadTailIndex) {
        bridge.log(
            "thread_read_tail index=${visibleReadTailIndex?.toString() ?: "null"} " +
                "firstMessageListItemIndex=${renderedTimelineSlice.firstMessageListItemIndex()} " +
                "messageCount=${renderedTimelineSlice.messages.size}"
        )
    }
    val visibleReadMarkerKey =
        remember(
            renderedThread.sourceThreadId,
            renderedTimelineSlice.status,
            renderedTimelineSlice.messages.size,
            renderedTimelineSlice.messages.lastOrNull()?.id,
            renderedTimelineSlice.messages.lastOrNull()?.seq,
            visibleReadTailIndex
        ) {
            renderedTimelineSlice.visibleReadMarkerKey(
                sourceThreadId = renderedThread.sourceThreadId,
                visibleTailIndex = visibleReadTailIndex
            )
    }
    // Seen = read only while the window is in use (desktop: focused, input in the last 3 minutes).
    // Keyed on it so coming back reads what is in front of the person then (web seenReadCatchUp).
    val hostInUse by RaftHostInUse.inUse.collectAsState()
    LaunchedEffect(visibleReadMarkerKey, active, threadHostForeground, hostInUse, renderSlice.writeActionsEnabled) {
        if (
            renderSlice.writeActionsEnabled &&
            shouldMarkThreadVisibleRead(
                routeActive = active,
                observedHostForeground = threadHostForeground,
                currentHostVisibility = isRaftHostVisible(storeScope.shellInstanceId),
                markerKey = visibleReadMarkerKey,
                hostInUse = hostInUse
            )
        ) {
            threadViewModel.dispatch(SharedThreadIntent.MarkVisibleRead(visibleReadTailIndex))
        } else if (!active || !threadHostForeground || !hostInUse) {
            // fb-4440a18c: left, covered by another page, or in the background. The store's
            // deferred / queued reads for this page must not go out now.
            threadViewModel.dispatch(SharedThreadIntent.VisibleReadHidden)
        }
    }
    DisposableEffect(threadViewModel) {
        onDispose { threadViewModel.dispatch(SharedThreadIntent.VisibleReadHidden) }
    }
    fun sendPendingLocalMessage(
        localMessageId: String,
        context: RaftThreadPendingSendContext,
        startedFromComposer: Boolean,
        authority: ThreadLocalSendAuthority
    ) {
        val sendBasePayload = threadViewModel.state.value.payload
        val sendProvenance = authority.provenance
        val sendRoute = authority.route
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
                    "bodyLength" to context.body.length.toString()
                )
        )
        // Registers this send's randomId with the process-wide in-flight fence BEFORE the job
        // starts, so the pending-send drainer never treats a journaled row as orphaned while an
        // attempt owns it. invokeOnCompletion covers every return/cancellation path.
        ThreadPendingSendInFlightRegistry.begin(context.randomId)
        threadSendScope.launch {
            fun sendAuthorityStillCurrent(): Boolean =
                dataSource.isCurrentReplyCacheProvenanceForLocalSend(sendProvenance) &&
                    threadViewModel.state.value.payload
                        .resolveThreadRouteIdentity()
                        .isPendingSendEquivalentTo(sendRoute)

            if (!sendAuthorityStillCurrent()) {
                bridge.logWarn(
                    "thread_send_drop localMessageId=$localMessageId " +
                        "channelId=${context.channelId} reason=owner_changed_before_request"
                )
                return@launch
            }

            suspend fun markFailed(errorCode: String?, errorMessage: String? = null) {
                threadViewModel.accept(
                    SharedThreadIntent.ApplyLocalSendFailure(
                        messageId = localMessageId,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                        expectedProvenance = sendProvenance,
                        expectedRoute = sendRoute,
                        randomId = context.randomId,
                    )
                )
                if (startedFromComposer && sendAuthorityStillCurrent()) {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
                }
            }

            suspend fun keepSendingUnconfirmed(result: String, errorCode: String?, httpCode: Int? = null) {
                bridge.logWarn(
                    "thread_send_unconfirmed localMessageId=$localMessageId " +
                        "channelId=${context.channelId} result=$result code=${errorCode.orEmpty()} " +
                        "httpCode=${httpCode?.toString().orEmpty()} action=mark_failed"
                )
                // The request may already have committed server-side. A retry uses the same
                // randomId, so the server-side idempotency key prevents a duplicate message.
                markFailed(errorCode, "Delivery could not be confirmed. Tap retry to try again.")
            }

            val sendAttemptState =
                ThreadSendAttemptState(
                    randomId = context.randomId,
                    maxSilentRetries = ThreadSendMaxSilentRetries
                )
            while (true) {
                if (!sendAuthorityStillCurrent()) {
                    bridge.logWarn(
                        "thread_send_drop localMessageId=$localMessageId " +
                            "channelId=${context.channelId} reason=owner_changed_before_attempt"
                    )
                    return@launch
                }
                val attempt = sendAttemptState.attempt
                val sendResult =
                    runCatchingPreservingCancellation {
                        withTimeoutOrNull(ThreadSendRequestTimeoutMs) {
                            dataSource.sendMessage(
                                channelId = context.channelId,
                                body = context.body,
                                asTask = context.asTask,
                                attachmentIds = context.attachmentIds,
                                randomId = sendAttemptState.randomId,
                                mentions = context.mentions,
                            )
                        }
                            ?: throw RaftRequestTimeoutException(
                                "send request exceeded ${ThreadSendRequestTimeoutMs}ms"
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
                    if (sendAttemptState.advanceForSilentRetry(threadSendShouldSilentlyRetry(errorCode))) {
                        bridge.log(
                            "thread_send_silent_retry localMessageId=$localMessageId " +
                                "channelId=${context.channelId} reason=$errorCode attempt=$attempt"
                        )
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
                                "attempt" to attempt.toString()
                            )
                    )
                    if (threadSendShouldSilentlyRetry(errorCode)) {
                        keepSendingUnconfirmed("exception", errorCode)
                    } else {
                        markFailed(errorCode, threadSendExceptionErrorMessage(sendException))
                    }
                    return@launch
                }
                val sendResponse = sendResult.getOrThrow()
                val sentPage =
                    when (sendResponse) {
                        is ThreadApiResult.Success -> sendResponse.data
                        is ThreadApiResult.Failure -> {
                            val errorCode =
                                threadSendFailureErrorCode(
                                    contractCode = sendResponse.contractCode,
                                    errorBodyText = sendResponse.errorBodyText,
                                    httpCode = sendResponse.code
                                )
                            val errorMessage = threadFailureReasonMessage(sendResponse.errorBodyText)
                            if (sendAttemptState.advanceForSilentRetry(
                                    threadSendShouldSilentlyRetry(errorCode, sendResponse.code)
                                )
                            ) {
                                bridge.log(
                                    "thread_send_silent_retry localMessageId=$localMessageId " +
                                        "channelId=${context.channelId} reason=$errorCode httpCode=${sendResponse.code} attempt=$attempt"
                                )
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
                                        "attempt" to attempt.toString()
                                    )
                            )
                            if (threadSendShouldSilentlyRetry(errorCode, sendResponse.code)) {
                                keepSendingUnconfirmed("failure", errorCode, sendResponse.code)
                            } else {
                                markFailed(errorCode, errorMessage)
                            }
                            return@launch
                        }
                    }
                if (sentPage.messages.isEmpty()) {
                    if (sendAttemptState.advanceForSilentRetry(eligible = true)) {
                        bridge.log(
                            "thread_send_silent_retry localMessageId=$localMessageId " +
                                "channelId=${context.channelId} reason=empty_response attempt=$attempt"
                        )
                        delay(ThreadSendSilentRetryDelayMs)
                        continue
                    }
                    bridge.performanceMarker(
                        phase = "composer_send",
                        routeName = "kuikly_thread",
                        event = "end",
                        threadId = context.channelId,
                        durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                        metadata = mapOf("result" to "empty", "attempt" to attempt.toString())
                    )
                    keepSendingUnconfirmed("empty_response", "empty_response")
                    return@launch
                }
                threadViewModel.accept(
                    SharedThreadIntent.ReplaceLocalSendWithSentPage(
                        messageId = localMessageId,
                        page = sentPage,
                        expectedProvenance = sendProvenance,
                        expectedRoute = sendRoute
                    )
                )
                // Task #59: replying in a hidden DM shows it in Home's DM list right away.
                val ownSentChannelIds =
                    sentPage.ownSentConversationIds(
                        fallbackChannelId = context.channelId,
                        parentConversationId = sendBasePayload.parentChannelId.takeIf { sendRoute.surfaceKind == RaftThreadSurfaceKind.ReplyThread }
                    )
                if (ownSentChannelIds.isNotEmpty()) {
                    sharedHomeStore?.dispatch(
                        SharedHomeIntent.OwnMessagesSent(sendProvenance.serverId, ownSentChannelIds)
                    )
                }
                // Agent Lounge v0: your first post in a Lounge joins it (no Join step), so its row
                // shows under the agent's DM right away.
                if (sendRoute.surfaceKind == RaftThreadSurfaceKind.Channel) {
                    RaftAgentLounges.markJoined(sendProvenance.serverId, context.channelId)
                }
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
                            "attempt" to attempt.toString()
                        )
                )
                val pendingMentionActions = sentPage.pendingMentionActions.map { it.toRaftPendingMentionActionItem() }
                val externalShareTokens =
                    (context.externalShareSources.map { source -> source.token } +
                        context.attachments
                            .filter { attachment -> attachment.source == "external_share" }
                            .mapNotNull { attachment -> attachment.externalShareToken?.takeIf(String::isNotBlank) })
                        .distinct()
                if (externalShareTokens.isNotEmpty()) {
                    val envelopeRepository =
                        build.raft.app.data.RaftExternalShareEnvelopeRepositoryProvider.create()
                    externalShareTokens.forEach { token ->
                        runCatchingPreservingCancellation { envelopeRepository.delete(token) }
                            .onFailure { error ->
                                bridge.logWarn(
                                    "external_share_cleanup_failed error=${error::class.simpleName.orEmpty()}"
                                )
                            }
                    }
                }
                if (sendAuthorityStillCurrent()) {
                    if (startedFromComposer) {
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.CompleteSend(
                                pendingMentionActions = pendingMentionActions,
                                unresolvedMentionHandles = sentPage.unresolvedMentionHandles
                            )
                        )
                    } else {
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.ApplyPendingMentionActions(
                                pendingMentionActions = pendingMentionActions,
                                unresolvedMentionHandles = sentPage.unresolvedMentionHandles
                            )
                        )
                    }
                } else {
                    bridge.logWarn(
                        "thread_send_success_terminal_projection_dropped localMessageId=$localMessageId " +
                            "channelId=${context.channelId} reason=owner_changed_after_response"
                    )
                }
                if (context.ownedTemporaryFileRefs.isNotEmpty()) {
                    // The local image copy for the sent bubble must be taken before the files go.
                    build.raft.app.image.RaftSentAttachmentLocalImages.registry.awaitSeeded(
                        attachmentIds = context.attachmentIds,
                        timeoutMillis = ThreadSentImageLocalSeedRetireWaitMs
                    )
                    bridge.retireFilePreviewTemporaryFiles(
                        sessionId = "thread-send-$localMessageId",
                        fileRefs = context.ownedTemporaryFileRefs
                    )
                }
                return@launch
            }
        }.invokeOnCompletion { ThreadPendingSendInFlightRegistry.end(context.randomId) }
    }
    // The composer's attachment upload pipeline (ThreadRouteAttachmentUploader.kt), built every
    // composition by a local function (a method of its own, so this one only loads what it captures
    // for the call). The draft's uploads, their semaphore and the remembered upload files stay here.
    // The route still calls it by these local functions' names.
    fun buildThreadRouteAttachmentUploader(): ThreadRouteAttachmentUploader =
        ThreadRouteAttachmentUploader(
            bridge = data.bridge,
            dataSource = data.dataSource,
            pager = pager,
            storeScope = data.storeScope,
            labels = labels,
            userFacingErrorLabels = userFacingErrorLabels,
            latestCurrentSessionState = latestCurrentSessionState,
            composerDraftViewModel = composerDraftViewModel,
            composerSurface = composerSurface,
            attachmentUploads = attachmentUploads,
            attachmentUploadOrchestrator = attachmentUploadOrchestrator,
            attachmentUploadSemaphore = attachmentUploadSemaphore,
            attachmentUploadFilesByLocalIdState = attachmentUploadFilesByLocalIdState,
            threadDirectUploadClientRequestIds = threadDirectUploadClientRequestIds,
            routeAuthRequiredFn = { result -> routeAuthRequired(result) },
            localizedAttachmentUploadFailedDetailFn = { detail -> localizedAttachmentUploadFailedDetail(detail) },
        )
    val threadRouteAttachmentUploader = buildThreadRouteAttachmentUploader()
    fun showAttachmentUploadFailure(errorCode: String, traceId: String? = null): Unit =
        threadRouteAttachmentUploader.showAttachmentUploadFailure(errorCode, traceId)
    suspend fun uploadComposerAttachmentFiles(
        files: List<PlatformFile>,
        replaceAttachmentId: String? = null,
        requireAllFiles: Boolean = false,
        replaceAttachmentIdsByLocalId: Map<String, String> = emptyMap()
    ): Boolean =
        threadRouteAttachmentUploader.uploadComposerAttachmentFiles(
            files,
            replaceAttachmentId,
            requireAllFiles,
            replaceAttachmentIdsByLocalId
        )
    // Effects keyed only on the thread (the event-bus subscriptions below) live across
    // recompositions, so they must call the latest copy of the upload function. The first frame's
    // copy holds the upload store of an account the page does not know yet (no session loaded),
    // so its uploads landed where this composer never looks and the tile stayed on "Checking
    // file…" (Android 1.27.0, artin).
    // A lambda, not `::uploadComposerAttachmentFiles`: a local function reference compares equal
    // across compositions (it ignores what it captured), so rememberUpdatedState would keep the
    // first frame's copy (found by Reef in the alpha's dex).
    val latestUploadComposerAttachmentFilesState = rememberUpdatedState<
        suspend (List<PlatformFile>, String?, Boolean, Map<String, String>) -> Boolean
    > { files, replaceAttachmentId, requireAllFiles, replaceAttachmentIdsByLocalId ->
        uploadComposerAttachmentFiles(files, replaceAttachmentId, requireAllFiles, replaceAttachmentIdsByLocalId)
    }
    val latestUploadComposerAttachmentFiles by latestUploadComposerAttachmentFilesState
    // The draft's uploads, wherever they were started: progress, the ready attachment, or Retry.
    val composerDraftUploadApplier =
        remember(composerDraftViewModel) { RaftComposerDraftUploadApplier(log = { message -> bridge.logWarn(message) }) }
    LaunchedEffect(composerDraftViewModel, attachmentUploads) {
        combine(composerDraftViewModel.state, attachmentUploads.byDraft) { draftState, uploads ->
            val draftKey = normalizeThreadAttachmentUploadDraftKey(draftState.draftKey)
            draftKey to draftKey?.let { key -> uploads[key] }.orEmpty()
        }.collect { (draftKey, uploads) ->
            if (draftKey == null || uploads.isEmpty()) return@collect
            composerDraftUploadApplier.apply(
                draftKey = draftKey,
                uploads = uploads,
                currentState = { composerDraftViewModel.state.value },
                acceptWithState = composerDraftViewModel::acceptWithState,
                onAdopted = { upload ->
                    val file = upload.file
                    threadAttachmentUploadAttemptIdentity(upload.localAttachmentId, draftKey)?.let { identity ->
                        if (file != null && attachmentUploadFilesByLocalId[identity] == null) {
                            attachmentUploadFilesByLocalId = attachmentUploadFilesByLocalId + (identity to file)
                        }
                    }
                },
                onSucceeded = { upload ->
                    attachmentUploadFilesByLocalId =
                        attachmentUploadFilesByLocalId.withoutThreadAttachmentUploadFile(upload.localAttachmentId, draftKey)
                },
                onFailed = { upload, errorCode ->
                    showAttachmentUploadFailure(
                        errorCode,
                        (upload.outcome as? RaftComposerDraftUploadOutcome.Failed)?.traceId
                    )
                },
                onDelivered = { upload ->
                    attachmentUploads.markDelivered(draftKey, upload.localAttachmentId, upload.attemptToken)
                }
            )
        }
    }
    // Image viewer > Edit (artin, task #32): the edited copy goes into this conversation's
    // composer as a new attachment, like a pasted image; the original message is untouched.
    var viewerImageEditSequence by remember(threadInstanceKey) { mutableStateOf(0L) }
    val viewerImageEditMessages by rememberUpdatedState(
        listOfNotNull(renderedTimelineSlice.originMessage) + renderedTimelineSlice.messages
    )
    DisposableEffect(eventBus, threadInstanceKey) {
        // The phone viewer is its own page: its ⋯ > Edit asks the conversation showing the image.
        val subscription =
            eventBus.observe(RAFT_VIEWER_IMAGE_EDIT_EVENT) { data ->
                if (RaftViewerImageEditOpener.threadKey != threadInstanceKey) return@observe
                val attachmentId = raftViewerImageEditAttachmentId(data) ?: return@observe
                val attachment =
                    viewerImageEditMessages
                        .asSequence()
                        .filterNot { it.isRetracted }
                        .flatMap { it.attachments.asSequence() }
                        .firstOrNull { it.id == attachmentId && it.isImageAttachment() }
                        ?: return@observe
                bridge.log("thread_viewer_image_edit status=open attachmentId=${attachmentId.take(8)}")
                RaftViewerImageEditOpener.openedAttachmentId = attachmentId
                viewerImageEditAttachment = attachment
            }
        onDispose { subscription.close() }
    }
    @Composable
    fun ThreadViewerImageEditSection() {
        viewerImageEditAttachment?.let { attachment ->
            ThreadViewerImageEditHost(
                attachment = attachment,
                desktop = imageLightboxEnabled,
                threadInstanceKey = threadInstanceKey,
                composerInstanceId = currentComposerPayload().instanceId.ifBlank { payload.instanceId },
                bridge = bridge,
                dataSource = dataSource,
                composerDraftViewModel = composerDraftViewModel,
                coroutineScope = coroutineScope,
                genericThreadErrorLabel = genericThreadErrorLabel,
                nextEditSequence = {
                    viewerImageEditSequence += 1
                    viewerImageEditSequence
                },
                onCancel = { viewerImageEditAttachment = null },
                onDismissModals = {
                    viewerImageEditAttachment = null
                    imageLightboxRequest = null
                },
                uploadComposerAttachmentFiles = { files, replaceAttachmentId, requireAllFiles, replaceAttachmentIdsByLocalId ->
                    latestUploadComposerAttachmentFiles(
                        files,
                        replaceAttachmentId,
                        requireAllFiles,
                        replaceAttachmentIdsByLocalId,
                    )
                },
            )
        }
    }
    ThreadViewerImageEditSection()
    // The composer's send, annotation, picked files and events (ThreadRouteComposerActions.kt), built every
    // composition by a local function (a method of its own, so this one only loads what it captures for
    // the call). The states they read and write stay here.
    fun buildThreadRouteComposerActions(): ThreadRouteComposerActions =
        ThreadRouteComposerActions(
            bridge = data.bridge,
            dataSource = data.dataSource,
            membersStore = data.membersStore,
            serverMembersStore = data.serverMembersStore,
            composerDraftPersistSlice = data.composerDraftPersistSlice,
            coroutineScope = coroutineScope,
            threadViewModel = threadViewModel,
            renderSliceState = renderSliceState,
            payload = payload,
            threadInstanceKey = threadInstanceKey,
            currentSession = currentSession,
            currentServerId = currentServerId,
            replyCacheProvenance = replyCacheProvenance,
            threadMembersServerId = threadMembersServerId,
            threadLimitLocked = threadLimitLocked,
            memberSuggestionScopeChannelId = memberSuggestionScopeChannelId,
            memberSuggestionScopedMentions = memberSuggestionScopedMentions,
            labels = labels,
            userFacingErrorLabels = userFacingErrorLabels,
            composerDraftViewModel = composerDraftViewModel,
            composerKeyboardHandoff = composerKeyboardHandoff,
            composerSurface = composerSurface,
            attachmentUploads = attachmentUploads,
            attachmentUploadOrchestrator = attachmentUploadOrchestrator,
            pendingMentionActionBatchRequestGate = pendingMentionActionBatchRequestGate,
            threadRouteAttachmentActions = threadRouteAttachmentActions,
            threadRouteAttachmentUploader = threadRouteAttachmentUploader,
            adaptivePageKeyboardInsetDpState = adaptivePageKeyboardInsetDpState,
            attachmentUploadFilesByLocalIdState = attachmentUploadFilesByLocalIdState,
            externalShareSendRequestedState = externalShareSendRequestedState,
            externalShareExpectedAuthorityState = externalShareExpectedAuthorityState,
            composerImageActionCounterState = composerImageActionCounterState,
            latestUploadComposerAttachmentFilesState = latestUploadComposerAttachmentFilesState,
            currentComposerPayloadFn = { currentComposerPayload() },
            sendPendingLocalMessageFn = { localMessageId, context, startedFromComposer, authority ->
                sendPendingLocalMessage(localMessageId, context, startedFromComposer, authority)
            },
            localizedAttachmentUploadFailedDetailFn = { detail -> localizedAttachmentUploadFailedDetail(detail) },
            localizedAttachmentSkippedManyFn = { count -> localizedAttachmentSkippedMany(count) },
        )
    val threadRouteComposerActions = buildThreadRouteComposerActions()
    fun performComposerSend(): Boolean = threadRouteComposerActions.performComposerSend()
    // The external-share auto-send below runs in an effect keyed only on the thread: it must
    // send with the latest session, provenance and upload store, not the first frame's (null
    // before the session loads, so the queued share was silently never sent).
    val latestPerformComposerSend by rememberUpdatedState<() -> Boolean> { performComposerSend() }
    fun handleComposerSendRequest(request: ThreadComposerSendRequest): ThreadComposerSendAdmission =
        threadRouteComposerActions.handleComposerSendRequest(request)
    LaunchedEffect(composerDraftViewModel, threadInstanceKey) {
        composerDraftViewModel.state.collect { draftState ->
            if (!externalShareSendRequested) return@collect
            val externalAttachments =
                draftState.uploadedAttachments.filter { attachment ->
                    attachment.source == "external_share" &&
                        attachment.id !in draftState.removedAttachmentIds
                }
            when {
                externalAttachments.any { it.status == "failed" } -> externalShareSendRequested = false
                externalAttachments.any { it.status == "queued" || it.status == "uploading" } -> Unit
                externalAttachments.isNotEmpty() -> {
                    val expected = externalShareExpectedAuthority
                    val authorityCurrent =
                        expected != null && dataSource.matchesExternalShareSendAuthority(expected)
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                    if (authorityCurrent) {
                        latestPerformComposerSend()
                    } else {
                        bridge.logWarn(
                            "external_share_send_rejected reason=auth_generation_changed_after_upload"
                        )
                    }
                }
                else -> {
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                }
            }
        }
    }
    suspend fun admitComposerPickedAttachments(action: String, response: JSONObject?): Unit =
        threadRouteComposerActions.admitComposerPickedAttachments(action, response)
    DisposableEffect(eventBus, threadInstanceKey) {
        // Event-bus callbacks can be re-entrant and may be dispatched from different host turns.
        // Keep the callback side-effect-free: one sequential consumer owns parsing, accumulation,
        // registry admission, upload handoff and terminal cleanup.  In particular, a malformed
        // replay queued behind an already admitted gallery cannot abort that in-flight batch.
        val eventMailbox = Channel<String>(Channel.UNLIMITED)

        /**
         * Remove every in-flight accumulator for one preview session while the same mutex also
         * retires the registry session.  A malformed/conflicting member must poison the whole
         * N/N handoff; leaving a sibling accumulator alive would let a later callback claim an
         * otherwise orphaned temporary ref.
         */
        fun abortBatchSessionLocked(sessionId: String): Set<String> {
            val normalizedSessionId = sessionId.trim()
            // Once the complete batch has crossed into the process-owned uploader, a late
            // malformed/conflicting replay must not tear down the refs that uploader is reading.
            // Its NonCancellable handoff owns the terminal finish path instead.
            if (composerImageEventSessionTracker.isHandoffStarted(normalizedSessionId)) {
                return emptySet()
            }
            composerImageEventSessionTracker.release(normalizedSessionId)
            composerImageEventBatches.keys
                .filter { key ->
                    key.sessionId == normalizedSessionId && key.threadKey == threadInstanceKey
                }
                .forEach { key ->
                    composerImageEventBatches.remove(key)?.abort()
                }
            return raftFilePreviewAbortComposerImageEventSession(normalizedSessionId)
        }

        suspend fun abortBatchSession(sessionId: String, reason: String): Set<String> {
            val cleanupRefs =
                composerImageEventBatchMutex.withLock {
                    abortBatchSessionLocked(sessionId)
                }
            if (cleanupRefs.isNotEmpty()) {
                bridge.retireFilePreviewTemporaryFiles(
                    sessionId = sessionId.trim(),
                    fileRefs = cleanupRefs.toList()
                )
            }
            bridge.logWarn(
                "composer_image_event_batch_aborted sessionId=${sessionId.trim()} " +
                    "reason=$reason cleanupRefs=${cleanupRefs.size}"
            )
            return cleanupRefs
        }

        data class ComposerImageBatchHandoff(
            val items: List<ThreadComposerImageEventBatchItem> = emptyList(),
            val admissions: List<RaftFilePreviewComposerImageEventAdmission.Accepted> = emptyList(),
            val ready: Boolean = false,
            val cleanupRefs: Set<String> = emptySet()
        )

        suspend fun processComposerImageEvent(eventDataSnapshot: String) {
                    val eventData = runCatching { JSONObject(eventDataSnapshot) }.getOrNull() ?: return
                    // Parse through the shared wire-contract validator before applying this
                    // ThreadPage's session/ownership policy.  This keeps malformed callbacks
                    // from reaching the registry and ensures metadata semantics match Settings.
                    val parsed = parseRaftFilePreviewComposerImageEvent(eventData)
                    val rawThreadKey = eventData.optString("threadKey").trim()
                    if (parsed == null) {
                        // Event-bus delivery is process-wide.  Only retire an invalid event's
                        // session when its raw thread key identifies this page; an unrelated
                        // page must not abort a live session before its owner can consume it.
                        val previewSessionId = eventData.optString("previewSessionId").trim()
                        if (
                            rawThreadKey != threadInstanceKey ||
                                !composerImageEventSessionTracker.owns(previewSessionId)
                        ) {
                            return
                        }
                        abortBatchSession(previewSessionId, reason = "malformed")
                        return
                    }
                    if (
                        parsed.threadKey != threadInstanceKey ||
                            !composerImageEventSessionTracker.owns(parsed.previewSessionId)
                    ) {
                        return
                    }
                    val previewSessionId = parsed.previewSessionId.trim()
                    val groupId = parsed.groupId.orEmpty().trim()
                    val expectedCount = parsed.groupTotal ?: 1
                    val replaceAttachmentId =
                        eventData.optString("replaceAttachmentId").trim().takeIf { it.isNotBlank() }
                    val item =
                        ThreadComposerImageEventBatchItem(
                            eventData = eventData,
                            payload = parsed,
                            replaceAttachmentId = replaceAttachmentId
                        )
                    val handoff =
                        composerImageEventBatchMutex.withLock {
                            val key =
                                ThreadComposerImageEventBatchKey(
                                    sessionId = previewSessionId,
                                    threadKey = threadInstanceKey,
                                    groupId = groupId
                                )
                            // A page can be disposed after an event has entered the mailbox but
                            // before this serialized offer runs.  Do not create a fresh
                            // accumulator after the tracker has closed; otherwise a late event
                            // could still claim a registry row from a page which no longer owns
                            // it.
                            if (!composerImageEventSessionTracker.owns(previewSessionId)) {
                                // This session belongs to another mounted page (or has already
                                // been disposed).  Ignore the process-wide replay; aborting by
                                // thread key alone could retire that other page's registry/ref.
                                ComposerImageBatchHandoff()
                            } else {
                                val accumulator =
                                    composerImageEventBatches[key]
                                        ?: ThreadComposerImageEventBatchAccumulator(
                                            key = key,
                                            expectedCount = expectedCount
                                        ).also { created ->
                                            composerImageEventBatches[key] = created
                                        }
                            when (val offer = accumulator.offer(item)) {
                                ThreadComposerImageEventBatchOffer.Added,
                                ThreadComposerImageEventBatchOffer.Duplicate ->
                                    ComposerImageBatchHandoff()

                                ThreadComposerImageEventBatchOffer.Rejected ->
                                    ComposerImageBatchHandoff(
                                        ready = false,
                                        cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                    )

                                is ThreadComposerImageEventBatchOffer.Complete -> {
                                    val items = offer.events
                                    val files =
                                        items.map { batchItem ->
                                            PlatformFile(
                                                id = "edited-${batchItem.actionId}",
                                                fileName = batchItem.payload.fileName,
                                                mimeType = batchItem.payload.mimeType,
                                                sizeBytes = batchItem.payload.sizeBytes,
                                                previewUri = batchItem.payload.previewUri,
                                                fileRef = batchItem.payload.fileRef,
                                                source = batchItem.payload.source
                                            )
                                        }
                                    val fileIds = files.map(PlatformFile::id)
                                    val fileRefs = files.mapNotNull(PlatformFile::fileRef)
                                    val filesValid =
                                        files.size == expectedCount &&
                                            files.all { file ->
                                                file.id.isNotBlank() &&
                                                    !file.fileRef.isNullOrBlank() &&
                                                    file.isComposerImageOwnedTemporary()
                                            } &&
                                            fileIds.toSet().size == fileIds.size &&
                                            fileRefs.toSet().size == fileRefs.size
                                    if (!filesValid) {
                                        ComposerImageBatchHandoff(
                                            cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                        )
                                    } else {
                                        val claimedAdmissions =
                                            items.map { batchItem ->
                                                raftFilePreviewClaimComposerImageEvent(
                                                    eventData = batchItem.eventData,
                                                    expectedThreadKey = threadInstanceKey
                                                )
                                            }
                                        val acceptedAdmissions =
                                            claimedAdmissions.mapNotNull { admission ->
                                                admission as? RaftFilePreviewComposerImageEventAdmission.Accepted
                                            }
                                        val claimComplete =
                                            acceptedAdmissions.size == items.size &&
                                                acceptedAdmissions.lastOrNull()?.sessionComplete == true &&
                                                acceptedAdmissions.dropLast(1).none { admission ->
                                                    admission.sessionComplete
                                                }
                                        if (!claimComplete) {
                                            ComposerImageBatchHandoff(
                                                cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                            )
                                        } else if (!composerImageEventSessionTracker.markHandoffStarted(previewSessionId)) {
                                            ComposerImageBatchHandoff(
                                                cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                            )
                                        } else {
                                            // Keep the started session in the tracker until the
                                            // upload handoff's finally block.  Disposal needs to
                                            // distinguish this in-flight owner from a partial
                                            // accumulator and perform a post-join sweep.
                                            ComposerImageBatchHandoff(
                                                items = items,
                                                admissions = acceptedAdmissions,
                                                ready = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (handoff.cleanupRefs.isNotEmpty()) {
                        bridge.retireFilePreviewTemporaryFiles(
                            sessionId = previewSessionId,
                            fileRefs = handoff.cleanupRefs.toList()
                        )
                    }
                    if (!handoff.ready) return

                    val files =
                        handoff.items.map { batchItem ->
                            PlatformFile(
                                id = "edited-${batchItem.actionId}",
                                fileName = batchItem.payload.fileName,
                                mimeType = batchItem.payload.mimeType,
                                sizeBytes = batchItem.payload.sizeBytes,
                                previewUri = batchItem.payload.previewUri,
                                fileRef = batchItem.payload.fileRef,
                                source = batchItem.payload.source
                            )
                        }
                    val replaceAttachmentIdsByLocalId =
                        handoff.items.mapNotNull { batchItem ->
                            batchItem.replaceAttachmentId
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { replacement ->
                                    "edited-${batchItem.actionId}" to replacement
                                }
                        }.toMap()
                    var transferred = false
                    try {
                        transferred =
                            // Attempts run on the process send scope, so page teardown must not
                            // cancel this waiter before those attempts publish their outcomes.
                            // Otherwise the `finally` below would retire ownership while a real
                            // network transfer was still in flight.
                            withContext(NonCancellable) {
                                latestUploadComposerAttachmentFiles(
                                    files,
                                    null,
                                    // Per-file admission: one oversize or failed image must not
                                    // reject the rest. Direct vs multipart is chosen per file.
                                    false,
                                    replaceAttachmentIdsByLocalId
                                )
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "composer_image_event_batch_upload_failed " +
                                "sessionId=$previewSessionId error=${error::class.simpleName.orEmpty()}"
                        )
                    } finally {
                        // Disposal cancels this consumer while the upload handoff is unwinding.
                        // Mutex.withLock is cancellable, so the terminal registry transition must
                        // be protected just like the upload itself; otherwise a cancellation can
                        // strand the pending session and its native temporary refs.
                        withContext(NonCancellable) {
                            val cleanupRefs =
                                composerImageEventBatchMutex.withLock {
                                // The accumulator remains present during the async upload so
                                // identical replays are harmless. Remove it only at terminal
                                // handoff, while finishing the registry under the same lock.
                                composerImageEventBatches.keys
                                    .filter { key ->
                                        key.sessionId == previewSessionId &&
                                            key.threadKey == threadInstanceKey
                                    }
                                    .forEach { key ->
                                        composerImageEventBatches.remove(key)?.abort()
                                    }
                                raftFilePreviewFinishComposerImageEventSession(
                                    sessionId = previewSessionId,
                                    transferred = transferred
                                )
                                }
                            composerImageEventSessionTracker.release(previewSessionId)
                            if (cleanupRefs.isNotEmpty()) {
                                bridge.retireFilePreviewTemporaryFiles(
                                    sessionId = previewSessionId,
                                    fileRefs = cleanupRefs.toList()
                                )
                            }
                            if (!transferred) {
                                bridge.logWarn(
                                    "composer_image_event_batch_rejected sessionId=$previewSessionId " +
                                        "reason=upload_handoff_failed cleanupRefs=${cleanupRefs.size}"
                                )
                            }
                        }
                    }
        }

        val consumerJob =
            coroutineScope.launch {
                for (eventDataSnapshot in eventMailbox) {
                    try {
                        processComposerImageEvent(eventDataSnapshot)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "composer_image_event_consumer_failed " +
                                "error=${error::class.simpleName.orEmpty()}"
                        )
                    }
                }
            }
        val subscription =
            eventBus.observe(RAFT_COMPOSER_IMAGE_EDIT_EVENT, crossProcess = true) { data ->
                val eventDataSnapshot = data?.toString() ?: return@observe
                eventMailbox.trySend(eventDataSnapshot)
            }
        onDispose {
            subscription.close()
            eventMailbox.close()
            // Close admission before requesting consumer cancellation.  Cancellation is
            // cooperative; if the consumer is in a non-suspending reducer step, it can still
            // finish that step after `cancel()` returns.  The closed tracker makes such a late
            // step fail its ownership check instead of admitting a new batch during teardown.
            val closeResult = composerImageEventSessionTracker.close()
            consumerJob.cancel()
            threadSendScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    // The consumer may already be inside the process-owned NonCancellable upload
                    // handoff.  Wait for that handoff's finally block before sweeping its refs;
                    // aborting it first could delete a file while the uploader still reads it.
                    try {
                        consumerJob.cancelAndJoin()
                    } catch (_: CancellationException) {
                        // The enclosing NonCancellable cleanup remains authoritative even if the
                        // child reports its own cancellation while joining.
                    } catch (_: Throwable) {
                        // A failed join must not suppress the final registry/ledger sweep below.
                    }
                    val sessionIds = closeResult.abortSessionIds + closeResult.inFlightSessionIds
                    try {
                        composerImageEventBatchMutex.withLock {
                            abortThreadComposerImageEventSessions(
                                sessionIds = sessionIds,
                                threadKey = threadInstanceKey,
                                batches = composerImageEventBatches,
                                retire = { sessionId, refs ->
                                    bridge.retireFilePreviewTemporaryFiles(
                                        sessionId = sessionId,
                                        fileRefs = refs.toList()
                                    )
                                }
                            )
                        }
                    } finally {
                        sessionIds.forEach(composerImageEventSessionTracker::release)
                    }
                }
            }
        }
    }

    fun openCanonicalTaskWith(
        task: TaskSummary,
        source: String,
        resolvedConversationChannelId: ChannelId?,
        /** False: the conversation takes the pending task page's place without the push. */
        animated: Boolean = true,
    ) {
        val serverId = currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val loadingPayload =
            serverId?.let {
                renderedThread.taskThreadLaunchPayload(
                    task = task,
                    serverId = it,
                    conversationIdentityProvider = conversationIdentityProvider,
                    resolvedConversationChannelId = resolvedConversationChannelId,
                )
            }
        if (loadingPayload == null) {
            bridge.logWarn(
                "thread_task_open_rejected reason=canonical_identity_unresolved " +
                    "source=$source taskId=${task.id.value} serverId=${serverId?.value.orEmpty()} " +
                    "parentChannelId=${task.channelId?.value.orEmpty()} " +
                    "parentMessageId=${task.parentMessageId?.value.orEmpty()} " +
                    "knownThreadChannelId=${task.threadId?.value.orEmpty()}"
            )
            bridge.toast(unableToOpenTaskLabel)
        } else {
            bridge.log(
                "thread_task_open result=resolved source=$source taskId=${task.id.value} " +
                    "parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                    "parentMessageId=${loadingPayload.parentMessageId.orEmpty()} " +
                    "knownThreadChannelId=${loadingPayload.streamChannelId.orEmpty()} " +
                    "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
            )
            if (animated) onOpenThreadPayload(loadingPayload) else onOpenThreadPayloadWithoutAnimation(loadingPayload)
        }
    }
    val latestTaskOpenServerId by rememberUpdatedState(currentServerId)
    val openCanonicalTask: (TaskSummary, String) -> Unit = open@{ task, source ->
        // Web useOpenTask: the legacy panel, or the empty conversation of a task without one
        // (opening never creates it — pushed as its own page when the host supports it), or a page.
        task.taskOpenSurface()?.let { surface ->
            val openPending = callbacks.onOpenPendingTask
            if (surface is TaskOpenSurface.Pending && openPending != null) {
                val channelJoined =
                    task.channelId?.takeIf { it == renderedThread.joinChannelTargetId() }?.let { renderedThread.joined }
                val canWriteChannel =
                    renderedThread.surfaceKind != RaftThreadSurfaceKind.Channel ||
                        task.channelId == null ||
                        task.channelId != renderedThread.joinChannelTargetId() ||
                        renderedThread.channelTaskWritesShown()
                if (openPending(surface.task, currentServerId, channelJoined, canWriteChannel)) {
                    bridge.log("thread_task_open result=pending_page source=$source taskId=${task.id.value}")
                    return@open
                }
            }
            bridge.log("thread_task_open result=surface kind=${surface::class.simpleName} source=$source taskId=${task.id.value}")
            taskOpenSurfaceFlow.open(surface)
            return@open
        }
        if (!taskOpenGate.tryBegin(task.id.value)) {
            bridge.log("thread_task_open result=ignored reason=in_flight source=$source taskId=${task.id.value}")
            return@open
        }
        if (task.taskOpenTarget() == TaskOpenTarget.ResolveConversation) {
            // Task v2: a joint surface's local conversation id comes from the server first.
            coroutineScope.launch {
                try {
                    openCanonicalTaskWith(
                        task = task,
                        source = source,
                        resolvedConversationChannelId =
                            resolveTaskOpenConversationChannelId(
                                task = task,
                                serverId = currentServerId.orEmpty(),
                                tasksDataSource = tasksDataSource,
                                log = bridge::log,
                            ),
                    )
                } finally {
                    taskOpenGate.finish(task.id.value)
                }
            }
        } else {
            openCanonicalTaskWith(task, source, null)
            taskOpenGate.finish(task.id.value)
        }
    }

    @Composable
    fun ThreadTaskOpenSurfaceSection() {
        TaskOpenSurfaceHost(
            flow = taskOpenSurfaceFlow,
            detailsRepository = taskConversation.detailsRepository,
            membersStore = serverMembersStore,
            serverId = currentServerId,
            viewerUserId = currentSession?.user?.id,
            // The conversation page's head writes: the same task channel store, gates and failure toasts.
            editing =
                TaskPageEditing(
                    authStore = authStore,
                    tasksDataSource = tasksDataSource,
                    tasksDataStore = tasksDataStore,
                    onOperationFailed = { event ->
                        bridge.showTaskOperationFailedToast(event, unableToUpdateTaskLabel, unableToUpdateAssigneeLabel) {
                            routeAuthRequired(it)
                        }
                    },
                    log = { bridge.log(it) },
                ),
            hostVisible = active,
            onConversationArrived = { arrived ->
                bridge.log("thread_task_pending_conversation_arrived taskId=${arrived.id.value}")
                openCanonicalTask(arrived, "task_conversation_arrived")
            },
            timeContext = timeContextProvider.dateContext ?: renderedThread.toRaftTimeCapabilities().staticContext(),
            // Web canWriteTasksInChannel: known for the channel this page shows (its task actions' gate);
            // another channel is not known here and stays writable.
            canWriteTaskChannel = { channelId ->
                renderedThread.surfaceKind != RaftThreadSurfaceKind.Channel ||
                    channelId == null ||
                    channelId != renderedThread.joinChannelTargetId() ||
                    renderedThread.channelTaskWritesShown()
            },
            // Known for the channel this page shows once write access is resolved (web ThreadPanel
            // `parentJoined`); otherwise unknown (null) so a loading or unresolved page never
            // flashes "Join channel to reply".
            joined = { task ->
                task.channelId
                    ?.takeIf { it == renderedThread.joinChannelTargetId() && renderSlice.writeActionsEnabled }
                    ?.let { renderedThread.joined }
            },
            onJoin = { task ->
                val channelId = task.channelId
                // slock #12426: a join already in flight sends no second request.
                if (channelId != null && ThreadChannelJoinsInFlight.begin(channelId)) {
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.joinChannel(channelId)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(SharedThreadIntent.ApplyChannelJoined(result.data.channelId.value))
                                    currentSession?.currentServerId?.takeIf(String::isNotBlank)?.let { serverId ->
                                        realtimeRoomLeaseRegistry.markJoinedByUser(serverId, result.data.channelId.value)
                                        sharedHomeStore?.dispatch(channelJoinedHomeIntent(renderedThread, serverId, result.data.channelId))
                                        sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                                    }
                                    realtimeEvents.joinChannel(result.data.channelId.value, currentSession?.currentServerId)
                                    bridge.toast(joinedChannelLabel)
                                }
                                is DataResult.NoPermission ->
                                    if (!routeAuthRequired(result)) bridge.errorToast(unableToJoinChannelLabel, result.blocker)
                                is DataResult.Error -> bridge.errorToast(unableToJoinChannelLabel, result.error)
                                else -> bridge.toast(unableToJoinChannelLabel)
                            }
                        } finally {
                            ThreadChannelJoinsInFlight.end(channelId)
                        }
                    }
                }
            },
            onSend = { task, text ->
                val serverId = currentServerId?.takeIf { it.isNotBlank() }
                if (serverId != null) {
                    coroutineScope.launch {
                        val opened =
                            taskOpenSurfaceFlow.send(
                                text = text,
                                serverId = serverId,
                                tasksDataSource = tasksDataSource,
                                sendMessage = { channelId, body, randomId ->
                                    dataSource.sendMessage(channelId = channelId, body = body, asTask = false, randomId = randomId)
                                },
                                currentServerId = { latestTaskOpenServerId },
                                fallbackError = unableToSendTaskMessageLabel,
                            )
                        bridge.log("thread_task_first_send taskId=${task.id.value} opened=${opened != null}")
                        // The conversation takes the pending page's place: no push; the pending page goes once it is covered.
                        opened?.let { (sentTask, conversationId) ->
                            openCanonicalTaskWith(sentTask, "task_first_send", conversationId, animated = false)
                        }
                    }
                }
            },
        )
    }
    ThreadTaskOpenSurfaceSection()
    // web TaskConversationDialog binds to a conversation created elsewhere (`task:updated`): the open
    // sheet follows the store's copy of its task and gives way to the conversation page.
    val taskOpenSurfaceTaskId = taskOpenSurfaceFlow.surface?.task?.id
    LaunchedEffect(taskOpenSurfaceTaskId, taskProjectionSlice, serverTaskProjectionSlice) {
        val taskId = taskOpenSurfaceTaskId ?: return@LaunchedEffect
        val fresh =
            taskProjectionSlice.tasks.firstOrNull { it.id == taskId }
                ?: serverTaskProjectionSlice.tasks.firstOrNull { it.id == taskId }
        taskOpenSurfaceFlow.onTaskChanged(fresh)?.let { arrived ->
            bridge.log("thread_task_pending_conversation_arrived taskId=${arrived.id.value}")
            openCanonicalTask(arrived, "task_conversation_arrived")
        }
    }

    @Composable
    fun ThreadCreateTaskFromMessageSection() {
        createTaskFromMessage.source?.let { source ->
            TaskCreateFromMessageDialog(
                source = source,
                submitting = createTaskFromMessage.submitting,
                errorText = createTaskFromMessage.errorText,
                onDismiss = { createTaskFromMessage.dismiss() },
                // Task v2 Create Task's Status field (web: shown with the task v2 flag on).
                statusField = true,
                onSubmit = { titles, status ->
                    val serverId = currentServerId?.takeIf { it.isNotBlank() }
                    if (serverId != null && !createTaskFromMessage.submitting) {
                        coroutineScope.launch {
                            val outcome =
                                createTaskFromMessage.submit(titles, serverId, tasksDataSource, tasksDataStore, unableToCreateTaskLabel, status)
                            bridge.log(
                                "thread_task_create_from_message result=${outcome?.let { it::class.simpleName }} " +
                                    "messageId=${source.messageId.value}"
                            )
                            val blocker = (outcome as? TaskCreateFromMessageOutcome.Failed)?.blocker
                            if (blocker != null) routeAuthRequired(DataResult.NoPermission(blocker))
                        }
                    }
                },
            )
        }
    }
    ThreadCreateTaskFromMessageSection()

    val foregroundBannerServerId = currentSession?.currentServerId?.let(::ServerId)
    val foregroundBannerTopSafeArea = raftTopSafeAreaPadding()
    val onForegroundBannerClick: (RaftForegroundMessageBanner) -> Unit = { target ->
        foregroundBannerStore.dismiss(target.id)
        val request = target.openRequest
        val bannerPayload =
            buildAppShellThreadPayload(
                conversation = request.conversationIdentity,
                timeContext = renderedPayload.toRaftTimeCapabilities().staticContext(),
                alignOwnMessagesRight = renderedPayload.alignOwnMessagesRight,
                compactConsecutiveMessages = renderedPayload.compactConsecutiveMessages,
                anchorMessageId = request.anchorMessageId ?: target.focusedMessageId,
                originFallback = request.originFallback,
                onWarn = bridge::logWarn
            )
        if (bannerPayload == null) {
            bridge.logWarn("foreground_banner_open_rejected reason=reply_identity_incomplete")
        } else {
            onOpenThreadPayload(bannerPayload)
        }
    }
    val onForegroundBannerDismiss: (RaftForegroundMessageBanner) -> Unit = { target -> foregroundBannerStore.dismiss(target.id) }

    // The dialogs' state, read through one holder: the banner host's content lambda below is at
    // the JVM's captured-value limit. Rebuilt every composition; each field is remembered above.
    val threadRouteDialogStates =
        ThreadRouteDialogStates(
            textSelectionMessage = textSelectionMessageState,
            attachmentCommentsDialogState = attachmentCommentsDialogStateHolder,
            installAppDialogState = installAppDialogStateHolder,
            createAgentDialogState = createAgentDialogStateHolder,
            createAgentComputerServerId = createAgentComputerServerIdState,
            createAgentComputerServerResolved = createAgentComputerServerResolvedState,
            pendingActionCardIds = pendingActionCardIdsState,
            renderSlice = renderSliceState,
            actionCardSpecStore = actionCardSpecStore,
            createAgentFormStore = createAgentFormStore,
        )
    // The route's thread events, read through one object for the same reason (and so the content
    // lambda below captures it instead of the values it reads). Rebuilt every composition, by a
    // local function: a method of its own, so this one only loads what it captures for the call
    // (no remembered lambdas here). data and callbacks are immutable: data.bridge is `bridge`.
    val settingsPageLinkKey = "thread-settings:$threadInstanceKey"
    fun buildThreadRouteEventHandler(): ThreadRouteEventHandler =
        ThreadRouteEventHandler(
            bridge = data.bridge,
            dataSource = data.dataSource,
            tasksDataSource = data.tasksDataSource,
            tasksDataStore = data.tasksDataStore,
            serverMembersStore = data.serverMembersStore,
            conversationIdentityProvider = data.conversationIdentityProvider,
            storeScope = data.storeScope,
            eventBus = data.eventBus,
            adaptiveCloseRequest = data.adaptiveCloseRequest,
            onBack = callbacks.onBack,
            onOpenThreadPayload = callbacks.onOpenThreadPayload,
            onOpenParentThread = callbacks.onOpenParentThread,
            onOpenMemberProfile = callbacks.onOpenMemberProfile,
            onOpenMembersPager = callbacks.onOpenMembersPager,
            onOpenChannelSettings = callbacks.onOpenChannelSettings,
            pager = pager,
            sharedStores = sharedStores,
            sharedHomeStore = sharedHomeStore,
            realtimeEvents = realtimeEvents,
            threadViewModel = threadViewModel,
            renderSliceState = renderSliceState,
            adaptiveFocusManager = adaptiveFocusManager,
            adaptiveKeyboardController = adaptiveKeyboardController,
            adaptiveFocusWorkspaceStore = adaptiveFocusWorkspaceStore,
            payload = payload,
            replyMetadataByParentId = replyMetadataByParentId,
            currentSession = currentSession,
            replyCacheProvenanceOwner = replyCacheProvenanceOwner,
            currentServerSlug = currentServerSlug,
            replyCacheProvenance = replyCacheProvenance,
            realtimeRoomLeaseRegistry = realtimeRoomLeaseRegistry,
            coroutineScope = coroutineScope,
            pendingReactionKeysState = pendingReactionKeysState,
            pendingReactionVisualsState = pendingReactionVisualsState,
            pendingActionCardIdsState = pendingActionCardIdsState,
            createTaskFromMessage = createTaskFromMessage,
            pendingThreadFollowMessageIdsState = pendingThreadFollowMessageIdsState,
            markdownRefOpenStore = markdownRefOpenStore,
            textSelectionMessageState = textSelectionMessageState,
            createAgentDialogStateHolder = createAgentDialogStateHolder,
            markdownTaskOpenRequestGate = markdownTaskOpenRequestGate,
            labels = labels,
            taskChannelId = taskChannelId,
            tasksViewModel = tasksViewModel,
            currentServerId = currentServerId,
            markdownTaskContextChannelId = markdownTaskContextChannelId,
            threadMembersServerId = threadMembersServerId,
            threadMembersChannelId = threadMembersChannelId,
            linkedTasksProjection = linkedTasksProjection,
            taskAssigneeOptions = taskAssigneeOptions,
            renderedThread = renderedThread,
            linkNavigator = linkNavigator,
            renderedTimelineSlice = renderedTimelineSlice,
            filesViewModel = filesViewModel,
            routeAuthRequiredFn = { result -> routeAuthRequired(result) },
            actionCardStepUp = actionCardStepUp,
            installAppDialogStore = installAppDialogStore,
            actionCardSpecHost = actionCardSpecHost,
            openAttachmentCommentsFn = { attachmentId -> threadRouteAttachmentActions.openAttachmentComments(attachmentId) },
            openImagePreviewFn = { group -> threadRouteAttachmentActions.openImagePreview(group) },
            saveAttachmentExternallyFn = { attachment -> threadRouteAttachmentActions.saveAttachmentExternally(attachment) },
            openAttachmentNoPreviewFn = { attachment -> threadRouteAttachmentActions.openAttachmentNoPreview(attachment) },
            sendPendingLocalMessageFn = { localMessageId, context, startedFromComposer, authority ->
                sendPendingLocalMessage(
                    localMessageId = localMessageId,
                    context = context,
                    startedFromComposer = startedFromComposer,
                    authority = authority
                )
            },
            openCanonicalTask = openCanonicalTask,
            settingsPageLinkKey = settingsPageLinkKey,
            threadLimitLocked = threadLimitLocked,
        )
    val threadRouteEventHandler = buildThreadRouteEventHandler()
    RaftForegroundBannerHost(
        banner =
            foregroundMessageBanner?.takeIf {
                threadForegroundBannerOnPage(
                    inAssistantPanel = inAssistantPanelEmbed,
                    assistantPanelOpen = false,
                )
            },
        topSafeArea = foregroundBannerTopSafeArea,
        serverMembersStore = serverMembersStore,
        currentServerStore = foregroundBannerCurrentServerStore,
        currentServerIdOverride = foregroundBannerServerId,
        onBannerClick = onForegroundBannerClick,
        onBannerDismiss = onForegroundBannerDismiss,
    ) {
        // OFF-zero-work provider set (task #20 P1, reviewed round 4): with
        // diagnostics OFF the census session Local is NOT part of the vararg
        // at all — the base array is used by identity, so the production
        // provider scope is unchanged by P1.
        // New Message Menu toggle: message bodies become selectable in place (ThreadInPlaceTextSelection.kt).
        // Always on where the pointer selects text (desktop: drag, double-click, Cmd+C, like the web).
        val inPlaceSelectionEnabled = newMessageMenuEnabled || raftPlatformMessageTextPointerSelectable()
        val inPlaceTextSelectionHost =
            remember(inPlaceSelectionEnabled, bridge, copiedLabel) {
                ThreadInPlaceTextSelectionHost(
                    enabled = inPlaceSelectionEnabled,
                    // Web: copying a selection shows nothing (artin 2026-11-02).
                    copyText = { text -> bridge.copyToPasteboard(text) }
                )
            }
        // read_receipts_v0: other participants' read positions for this page's scope (null = no hydrate).
        val peerReadReceipts =
            rememberThreadPeerReadReceipts(
                payload = renderedThread,
                sharedStores = sharedStores,
                storeScope = storeScope,
                serverId = currentServerId,
                accountId = currentSession?.user?.id,
                active = active,
                serverMembersStore = serverMembersStore,
            )
        // The line above the composer: who is about to post into this conversation and, in a
        // channel, who of its agents is working (null = nobody). A Working now row opens the agent.
        val agentTyping =
            rememberThreadAgentTyping(
                payload = renderedThread,
                sharedStores = sharedStores,
                storeScope = storeScope,
                serverId = currentServerId,
                serverMembersStore = serverMembersStore,
                agentStatusResolver = agentStatusResolver,
                openAgent = { agentId ->
                    memberDetailIdentity(agentId, MemberKind.Agent, currentServerId)?.let { identity ->
                        onOpenMemberProfile(renderedThread, renderedTimelineSlice, identity)
                    }
                },
            )
        // The personal assistant's otter (web AssistantOtterAnchor): on this conversation's composer
        // when the assistant is ready with its DM, never on that DM or a thread in it.
        val assistantOtterHostId = remember { newAssistantOtterHostId() }
        val assistantOtterInSplitPane = threadInSplitDetailPane()
        val assistantOtterReplyThread = renderedThread.surfaceKind == RaftThreadSurfaceKind.ReplyThread
        val assistantOtter =
            rememberThreadAssistantOtter(
                store = remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) },
                featureFlags = sharedStores.serverFeatureFlags,
                serverMembersStore = serverMembersStore,
                homeState = sharedHomeState,
                serverId = currentServerId,
                userId = currentSession?.user?.id,
                viewerIsGuest = threadViewerIsGuest,
                conversationChannelId =
                    if (assistantOtterReplyThread) renderedThread.parentChannelId else renderedThread.streamChannelId,
                hostId = assistantOtterHostId,
                wide = assistantOtterInSplitPane,
                replyThread = assistantOtterReplyThread,
                pageActive = active,
                // A thread's composer also names the thread: opened from here, the assistant's DM
                // attaches it (web ThreadPanel's AssistantOtterAnchor threadParentMessageId/Text).
                threadParentMessageId =
                    renderedThread.parentMessageId?.takeIf { assistantOtterReplyThread && it.isNotBlank() }
                        ?: renderedThread.originParentMessageId?.takeIf { assistantOtterReplyThread && it.isNotBlank() },
                threadParentText =
                    renderedThread.originMessage
                        ?.takeIf { message ->
                            assistantOtterReplyThread &&
                                message.id == (renderedThread.parentMessageId ?: renderedThread.originParentMessageId)
                        }
                        ?.body,
            )
        // Read through the local, not captured (this lambda is at the JVM's captured-value limit).
        val bannerHandoff = LocalRaftForegroundBannerHandoff.current
        // A press opens the assistant over this page (web AssistantOtter's phone Drawer): its head,
        // then its DM as the conversation page itself (THREAD_PAGE embedded, without its head).
        assistantOtter?.let { otter ->
            ThreadRouteAssistantPanel(
                otter = otter,
                bannerHandoff = bannerHandoff,
                renderedThread = renderedThread,
                conversationIdentityProvider = conversationIdentityProvider,
                bridge = bridge,
                storeScope = storeScope,
            )
        }
        // App link previews (artin #20 lane L): this viewer on this Server, asked once per conversation.
        val appLinkPreviewViewerId = currentSession?.user?.id?.takeIf(String::isNotBlank)
        val appLinkPreviewConversationId = renderedThread.streamChannelId ?: initialRouteIdentity.instanceKey
        val appLinkPreviewHost =
            remember(sharedStores, appLinkPreviewViewerId, currentServerId, appLinkPreviewConversationId) {
                val viewerId = appLinkPreviewViewerId
                val serverId = currentServerId
                if (viewerId == null || serverId == null) {
                    null
                } else {
                    ThreadAppLinkPreviewHost(
                        store = sharedStores.appLinkPreviewStore,
                        viewerKey = appLinkViewerKey(viewerId, serverId),
                        serverId = serverId,
                        conversationId = appLinkPreviewConversationId,
                        toast = { message -> bridge.toast(message) },
                        toastWithIntent = { message, intent -> bridge.toastWithIntent(message, intent) },
                        errorToast = { message, traceId -> bridge.errorToast(message, traceId) },
                        copyText = { text -> bridge.copyToPasteboard(text) },
                        // Workspace Artifacts links (behind workspace_artifacts_v0, read per message).
                        artifacts =
                            ThreadArtifactLinkHost(
                                summaries = sharedStores.artifactSummaryStore,
                                appHosts = sharedStores.clientConfigStore.appHosts,
                                featureFlags = sharedStores.serverFeatureFlags,
                                webBaseUrl = sharedStores.apiEnvironmentStore.current().webUrl,
                                screenshots = sharedStores.artifactScreenshotStore,
                                readerId = viewerId,
                            ),
                        // The batched link-card read (App link cards v2).
                        batchedRead = true,
                    )
                }
            }
        // Action cards store ids; show the names this server's roster (and channels) already hold.
        val actionCardNames =
            rememberThreadActionCardNameLookup(
                members = threadServerMembersEntry,
                serverId = currentServerId,
                jointActors = serverMembersStore?.jointActors,
                knownEntities = knownEntityResolver,
                accountId = knownEntityAccountId,
                knownEntitiesRevision = sharedHomeState.content,
                // Computers are named from this server's list only (botiverse/slock #14234).
                computersStore = computersStore,
            )
        // The profile card an @mention in a message, or an author's avatar, opens under a mouse or
        // trackpad pointer (web MentionLink): a mention is the member its click opens.
        val currentMentionThread by rememberUpdatedState(renderedThread)
        // Connected servers of this external channel (or parent channel for reply threads / tasks):
        // feeds server logos & names into message authors, @ badges, and profile preview cards.
        val channelJointServers =
            rememberThreadChannelJointServers(
                lookupIds = conversationDirectoryLookupIds,
                parentChannelId = renderedThread.parentChannelId,
                taskParentChannelId = taskConversation.parentChannelId?.value,
                serverId = headerProjectionServerId?.value,
                projections = billingDirectoryProjections,
                conversationIdentityProvider = conversationIdentityProvider,
                homeContentRevision = sharedHomeState.content,
            )
        val memberModelLabelResolver =
            remember(serverMembersStore, headerProjectionServerId, channelJointServers) {
                raftMemberModelLabelResolver(
                    serverMembersStore = serverMembersStore,
                    serverId = headerProjectionServerId?.value,
                    knownServers = channelJointServers,
                )
            }
        val memberPreviewCardSource =
            remember(serverMembersStore, headerProjectionServerId, agentStatusResolver, memberAvatarResolver, threadMembersServerId, threadMembersChannelId, channelJointServers) {
                RaftMemberPreviewCardSource(
                    serverMembersStore = serverMembersStore,
                    serverId = headerProjectionServerId?.value,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    knownServers = channelJointServers,
                    resolveMention = { label ->
                        val serverMembers =
                            if (serverMembersStore == null || threadMembersServerId == null) {
                                emptyList()
                            } else {
                                serverMembersStore.currentServerMembers(threadMembersServerId) +
                                    threadMembersChannelId
                                        ?.let { serverMembersStore.currentChannelMembers(threadMembersServerId, it) }
                                        .orEmpty()
                            }
                        currentMentionThread.resolveMarkdownMentionOpenTarget(label, serverMembers)?.identity
                    },
                )
            }
        // Member labels (web #14535/#15817): viewer's joined servers overlaid with this external
        // channel's `jointServers[].serverAvatarUrl` so badges show other servers' logos too.
        val viewerServerLogos =
            remember(currentSession?.servers, channelJointServers) {
                raftOriginServerLogos(currentSession?.servers.orEmpty().map { it.id to it.avatarUrl }, channelJointServers)
            }
        val taskConversationId = taskConversation.conversationId
        val taskConversationHeaderViewModel = taskConversation.headerViewModel
        val taskConversationHeaderState = taskConversation.headerState
        val taskConversationParentChannelId = taskConversation.parentChannelId
        val taskConversationTasksViewModel = taskConversation.tasksViewModel
        val taskConversationTasksState = taskConversation.tasksState
        val taskConversationTask = taskConversation.task
        val taskConversationDetailsViewModel = taskConversation.detailsViewModel
        val taskConversationDetailsState = taskConversation.detailsState
        val taskConversationJointSurface =
            jointMessageSurface ||
                parseConversationKindOrNull(taskConversationTask?.channelType) == ConversationKind.Joint ||
                (taskConversationParentChannelId?.value?.let { id ->
                    currentServerId?.let(::ServerId)?.let { server ->
                        conversationIdentityProvider?.resolveProjection(server, id)?.conversationKind == ConversationKind.Joint
                    }
                } == true)
        val effectiveMessageAvatarResolver =
            remember(messageAvatarResolver, taskConversationJointSurface) {
                messageAvatarResolver.forJointSurface(taskConversationJointSurface)
            }
        val quoteReplyHost =
            rememberThreadQuoteReplyHost(
                featureFlags = sharedStores.serverFeatureFlags,
                serverId = currentServerId,
                renderedThread = renderedThread,
                timelineMessages = renderedTimelineSlice.messages,
                serverMembersStore = serverMembersStore,
                currentSession = currentSession,
                memberAvatarResolver = effectiveMessageAvatarResolver,
                composerDraftViewModel = composerDraftViewModel,
                threadViewModel = threadViewModel,
                bridge = bridge,
            )
        val threadProvidedValues = raftMarkdownProvidedValuesWith(
            arrayOf(
                LocalThreadQuoteReplyHost provides quoteReplyHost,
                LocalRaftViewerServerLogos provides viewerServerLogos,
                LocalRaftChannelJointServers provides channelJointServers,
                // One channel-kind glyph per channel (forward targets, composer suggestions). Read
                // from values this lambda already captures (see the JVM-limit note below).
                LocalRaftChannelKinds provides rememberRaftChannelKindsState(sharedHomeStore.homeStateForPage()),
                LocalRaftMemberPreviewCardSource provides memberPreviewCardSource,
                LocalThreadAppLinkPreviewHost provides appLinkPreviewHost,
                // The conversation its Artifacts (cards, links, Files) are opened from (`hostConversation`).
                // From values this lambda already captures (see the JVM-limit note below).
                LocalRaftArtifactOpenConversation provides renderedThread.artifactOpenConversationId(),
                LocalThreadInPlaceTextSelectionHost provides inPlaceTextSelectionHost,
                LocalThreadPeerReadReceipts provides peerReadReceipts,
                LocalThreadAgentTyping provides agentTyping,
                LocalThreadAssistantOtter provides assistantOtter,
                // The assistant DM's receipt and WeChat notice cards. Read from values this lambda
                // already captures (see the JVM-limit note below).
                LocalThreadAssistantDmCards provides
                    rememberThreadAssistantDmCardsHost(
                        sharedStores = sharedStores,
                        storeScope = storeScope,
                        serverId = currentServerId,
                        userId = currentSession?.user?.id,
                        conversationChannelId =
                            renderedThread.streamChannelId.takeIf { renderedThread.surfaceKind != RaftThreadSurfaceKind.ReplyThread },
                        bridge = bridge,
                    ),
                LocalThreadInlineVideoPreviewHost provides
                    ThreadInlineVideoPreviewHost(
                        states = inlineVideoPreviewStates,
                        requestPreview = ::requestInlineVideoPreview
                    ),
                LocalThreadInlineAudioPreviewHost provides
                    ThreadInlineAudioPreviewHost(
                        states = inlineAudioPreviewStates,
                        requestPreview = ::requestInlineAudioPreview
                    ),
                LocalThreadInlineImageFallbackHost provides
                    ThreadInlineImageFallbackHost(
                        state = inlineImageFallbackState,
                        requestFallback = ::requestInlineImageFallback,
                        onFallbackError = ::handleInlineImageFallbackError,
                    ),
                LocalThreadAttachmentDownloadHost provides attachmentDownloadHost,
                LocalRaftMessagePermalinkLabelResolver provides messagePermalinkLabelResolver,
                LocalRaftMarkdownTaskStatuses provides markdownTaskStatuses,
                LocalRaftMarkdownTaskRefStatuses provides markdownTaskRefStatuses,
                // The conversation has a task source that has not loaded yet: task refs keep the
                // status chip's shape in a neutral look until it does.
                LocalRaftMarkdownTaskStatusesPending provides
                    (markdownTaskStatuses == null && markdownTaskContextChannelId != null),
                // web MessageMarkdownBody: a Raft message permalink in a message body is a
                // PermalinkReferenceChip (link icon, the conversation, a mono "msg"), not a bare URL.
                LocalRaftMarkdownRenderConfig provides
                    RaftMarkdownRenderConfig(
                        renderMessagePermalinksAsTag = true,
                        singleParagraphLightPath = singleParagraphLightPathEnabled,
                    ),
                LocalThreadReverseLayout provides threadReverseLayoutEnabled,
                LocalThreadFeedbackCardEntryEnabled provides feedbackCardEntryEnabled,
                LocalThreadViewerServerId provides currentServerId,
                // A joint channel's conversation (including a task conversation in one, slock #14517):
                // every person's face (hand-off card, task head, typing, followers, composer chips)
                // reads the other server's people as its message rows do.
                LocalRaftJointActorSurface provides taskConversationJointSurface,
                // The Add Reaction picker keeps its skin tone and frequently used emoji per account.
                LocalRaftEmojiPickerOwnerKey provides messageBodyOwnerKey,
                LocalThreadActionCardNames provides actionCardNames,
                LocalThreadActionCardSpecHost provides actionCardSpecHost,
                LocalThreadKeyboardTapDismissal provides threadKeyboardTapDismissal,
                LocalThreadMessagePointerSessionKey provides threadPointerSessionKey,
                LocalThreadMessageLongPressGestureOwnerStore provides
                    threadMessageLongPressGestureOwnerStore,
                LocalThreadPendingReactionVisuals provides pendingReactionVisuals,
                LocalRaftMemberAppBotResolver provides memberAppBotResolver,
                LocalRaftTimeContextProvider provides timeContextProvider
            ),
            LocalRaftMarkdownPlainCandidateSession,
            plainCandidateCensusSession
        )
        // Task v2: the task channel's people and agents for the assignee picker (web
        // `useChannelMembers(task.channelId)`); a task conversation has no members of its own.
        val taskConversationMembersServerId = currentServerId?.let(::ServerId)
        val taskConversationMembersSlice by remember(serverMembersStore, taskConversationMembersServerId, taskConversationParentChannelId) {
            val store = serverMembersStore
            if (store != null && taskConversationMembersServerId != null && taskConversationParentChannelId != null) {
                store.channelMemberSliceFlow(taskConversationMembersServerId, taskConversationParentChannelId)
                    .onStart {
                        store.loadChannelMembers(taskConversationMembersServerId, taskConversationParentChannelId, DataRequestPriority.Visible)
                    }
            } else {
                MutableStateFlow(SharedServerChannelMembersSlice(serverId = currentServerId, channelId = taskConversationParentChannelId?.value.orEmpty()))
            }
        }.collectAsState(SharedServerChannelMembersSlice(serverId = currentServerId, channelId = taskConversationParentChannelId?.value.orEmpty()))
        // Web canWriteTasksInChannel (slock #15155): status, assignee, title / description edit and
        // Delete only where the reader may write the task's channel (joined, not archived). A channel
        // this page does not know stays writable; the server refuses with its sentence and trace id.
        val taskConversationChannelWritable by remember(conversationIdentityProvider, taskConversationMembersServerId, taskConversationParentChannelId) {
            val provider = conversationIdentityProvider
            val server = taskConversationMembersServerId
            val channelId = taskConversationParentChannelId
            if (provider == null || server == null || channelId == null) {
                MutableStateFlow(true)
            } else {
                provider.observeServer(server).map { directory -> directory.canWriteTasksInChannel(server, channelId) }.distinctUntilChanged()
            }
        }.collectAsState(true)
        // Task v2: leave the task for a place in its channel (web `onLeave` + `nav.toMessage`): its
        // source / host message, the channel itself, or a pre-v2 task's earlier discussion thread.
        fun openTaskConversationTarget(request: HomeMessageOpenRequest?, action: String) {
            val loadingPayload =
                request?.let { openRequest ->
                    buildAppShellThreadPayload(
                        conversation = openRequest.conversationIdentity,
                        timeContext = renderedThread.toRaftTimeCapabilities().staticContext(),
                        alignOwnMessagesRight = renderedThread.alignOwnMessagesRight,
                        compactConsecutiveMessages = renderedThread.compactConsecutiveMessages,
                        anchorMessageId = openRequest.anchorMessageId,
                        originFallback = openRequest.originFallback,
                        onWarn = bridge::logWarn
                    )
                }
            if (loadingPayload == null) {
                bridge.logWarn(
                    "task_conversation_open_rejected action=$action reason=identity_unresolved " +
                        "taskId=${taskConversationTask?.id?.value.orEmpty()}"
                )
                bridge.toast(unableToOpenTaskLabel)
                return
            }
            bridge.log(
                "task_conversation_open action=$action taskId=${taskConversationTask?.id?.value.orEmpty()} " +
                    "targetThreadId=${loadingPayload.sourceThreadId.orEmpty()} " +
                    "focusedMessageId=${loadingPayload.focusedMessageId.orEmpty()}"
            )
            onOpenThreadPayload(loadingPayload)
        }
        fun taskConversationDirectory(task: TaskSummary): SharedConversationDirectoryState =
            listOfNotNull(
                currentServerId?.let(::ServerId)?.let { server ->
                    task.channelId?.let { conversationIdentityProvider?.resolveProjection(server, it.value) }
                }
            ).toSharedConversationDirectoryState()
        val openTaskInChannel: ((TaskSummary, MessageId?, String) -> Unit) = { task, anchor, action ->
            openTaskConversationTarget(
                task.taskChannelOpenRequest(currentServerId.orEmpty(), anchor, taskConversationDirectory(task)),
                action,
            )
        }
        // Task v2 on a phone: the head is the conversation's first row; while that row is out of
        // view the top bar names the task's state and pulls the head down over the messages.
        val taskHeadScrollState = remember(taskConversationId) { TaskHeadScrollState() }
        // Back (and Escape) puts the pulled-down head back up before it leaves the page.
        RaftBackHandler(enabled = taskHeadScrollState.floatOpen, label = "task_head_float") {
            taskHeadScrollState.closeFloat()
        }
        val taskConversationChrome: TaskConversationHeaderChrome? =
            if (taskConversationTask != null && taskConversationHeaderState != null && !taskConversationHeaderState.removed) {
                taskConversationTask.taskConversationHeaderChrome(
                    state = taskConversationHeaderState,
                    summarized = taskHeadScrollState.collapsed,
                    headFloatOpen = taskHeadScrollState.floatOpen,
                    onToggleHeadFloat = { taskHeadScrollState.toggleFloat() },
                    onViewInChannel = {
                        openTaskInChannel(taskConversationTask, taskConversationTask.taskViewInChannelAnchor(), "view_in_channel")
                    },
                    onToggleFollow = { followed -> taskConversationHeaderViewModel?.toggleFollow(followed) },
                    serverId = currentServerId,
                    viewerUserId = currentSession?.user?.id,
                    // Web #14115: the creator, or a server owner / admin (`deleteAnyTask`); asks first.
                    onDeleteTask =
                        { taskHeadScrollState.deleteConfirming = true }.takeIf {
                            taskConversationTasksViewModel != null &&
                                taskConversationTask.canBeDeletedBy(
                                    currentSession?.user?.id,
                                    currentSession?.servers?.firstOrNull { it.id == currentServerId }?.role,
                                    canWriteChannel = taskConversationChannelWritable,
                                )
                        },
                )
            } else {
                null
            }
        // The page's live clock supplies "today": the static context has none, and without it the
        // head's Source and History times fell back to the raw wire timestamp (1.17.0 build 3 smoke).
        val taskConversationTimeContext =
            timeContextProvider.dateContext ?: renderedThread.toRaftTimeCapabilities().staticContext()
        // The head as the conversation's first row at every width, and pulled down from the bar
        // ([TaskHeadPlacement]); the page's placement holds its effects.
        val taskConversationHeadRoute: (@Composable (placement: TaskHeadPlacement) -> Unit)? =
            if (taskConversationTask != null && taskConversationHeaderState != null && !taskConversationHeaderState.removed) {
                { placement ->
                    ThreadRouteTaskHead(
                        placement = placement,
                        taskConversationTask = taskConversationTask,
                        taskConversationHeaderState = taskConversationHeaderState,
                        taskConversationDetailsState = taskConversationDetailsState,
                        taskConversationTasksState = taskConversationTasksState,
                        taskConversationMembersSlice = taskConversationMembersSlice,
                        taskConversationTimeContext = taskConversationTimeContext,
                        taskConversationTasksViewModel = taskConversationTasksViewModel,
                        taskConversationDetailsViewModel = taskConversationDetailsViewModel,
                        taskConversationHeaderViewModel = taskConversationHeaderViewModel,
                        taskHeadScrollState = taskHeadScrollState,
                        taskConversationChannelWritable = taskConversationChannelWritable,
                        adaptivePageKeyboardInsetDp = adaptivePageKeyboardInsetDp,
                        renderedThread = renderedThread,
                        renderedTimelineSlice = renderedTimelineSlice,
                        currentServerId = currentServerId,
                        onOpenMemberProfile = onOpenMemberProfile,
                        openTaskInChannel = openTaskInChannel,
                        onOpenTaskConversationTarget = ::openTaskConversationTarget,
                        onTaskConversationDirectory = ::taskConversationDirectory,
                        viewerUserId = currentSession?.user?.id,
                        agentStatusResolver = agentStatusResolver,
                    )
                }
            } else {
                null
            }
        val taskConversationBar: (@Composable () -> Unit)? = taskConversationHeadRoute?.let { route -> { route(TaskHeadPlacement.Page) } }
        val taskConversationFloat: (@Composable () -> Unit)? = taskConversationHeadRoute?.let { route -> { route(TaskHeadPlacement.PullDown) } }
        val taskConversationHeadRow =
            rememberTaskConversationHeadRow(taskHeadScrollState) { taskConversationHeadRoute?.invoke(TaskHeadPlacement.FirstRow) }
        // What the settings drawer (and the phone's settings page) do beyond the members strip and
        // the preferences: Info with the Slack pick, Guest access and every action through the
        // settings page's store, then what follows them. [onLeave] leaves the conversation after
        // Leave, Delete, Disconnect or Archive.
        fun completeChannelSettingsPanel(
            panel: ThreadChannelSettingsPanel,
            homeContent: SharedHomeContentState,
            onLeave: () -> Unit,
        ): ThreadChannelSettingsPanel {
            val serverId = threadMembersServerId
            val channelId = threadMembersChannelId
            val homeServer = (homeContent as? SharedHomeContentState.Content)?.snapshot?.server
            val routeOwner = sharedStores.currentAuthenticatedRouteOwner(storeScope)
            return panel.copy(
                // The settings page's store: Info with the Slack pick, Guest access and every action.
                storeFactory =
                    if (serverId != null && channelId != null && routeOwner != null && routeOwner.serverId == serverId.value) {
                        { scope ->
                            createChannelSettingsStore(ChannelSettingsRoute(serverId, channelId), routeOwner, sharedStores, storeScope, scope) {
                                bridge.log("channel_settings_store drawer $it")
                            }
                        }
                    } else {
                        null
                    },
                canStopAllAgents = panel.channel?.let { channelSettingsCanStopAllAgents(it, homeServer?.role) } == true,
                agentControlsApi = RaftAgentControlsApi(sharedStores.networkClient(storeScope)),
                hideMembersStrip =
                    channelSettingsHidesMembersStrip(panel.channel, homeServer?.role, homeServer?.hideHumansFromMembers == true),
                onProfileSaved = { name, description ->
                    channelId?.let { id ->
                        threadViewModel.dispatch(SharedThreadIntent.ApplyChannelProfile(id.value, name, description))
                        sharedHomeStore?.dispatch(SharedHomeIntent.ChannelProfileUpdated(channelId = id, name = name, description = description))
                    }
                },
                onChannelChanged = { sharedHomeStore?.dispatch(SharedHomeIntent.Refresh) },
                onChannelClosed = { action, accessLost ->
                    if (action == ChannelSettingsAction.Disconnect) {
                        // Web: say which server let go of which channel before moving the reader on.
                        channelSettingsDisconnectedToast(homeServer?.name, panel.channel?.name)?.let(bridge::toast)
                    }
                    if (accessLost) channelId?.let { sharedStores.publishChannelAccessLost(storeScope, it) }
                    sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                    // Leaving, deleting or archiving takes the person out of the channel (the old drawer did too).
                    if (accessLost || action == ChannelSettingsAction.Archive) onLeave()
                },
                onActionFailed = { message -> bridge.toast(message) },
                onOpenChannel = { conversation, unarchived ->
                    if (unarchived) sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                    val next =
                        buildAppShellThreadPayload(
                            conversation = conversation,
                            timeContext = renderedThread.toRaftTimeCapabilities().staticContext(),
                            alignOwnMessagesRight = renderedThread.alignOwnMessagesRight,
                            compactConsecutiveMessages = renderedThread.compactConsecutiveMessages,
                            onWarn = bridge::logWarn,
                        )
                    if (next != null) {
                        onOpenThreadPayload(next)
                    } else {
                        bridge.logWarn("channel_settings_archived_open_rejected reason=identity_unresolved")
                    }
                },
            )
        }
        // The settings drawer beyond the name form and actions (web ChannelOverflowMenu): the
        // members strip, Move to section, Notifications and Collapse long messages.
        val channelSettingsPanel =
            rememberThreadChannelSettingsPanel(
                homeContent = sharedHomeState.content,
                serverId = threadMembersServerId,
                channelId = threadMembersChannelId,
                memberSlice = threadChannelMembersSlice,
                pinState = conversationPinState,
                activityMuteState = activityMuteState,
                longMessageCollapse = longMessageCollapseConversationSetting,
                agentStatusResolver = agentStatusResolver,
                writeActionsEnabled = renderSlice.writeActionsEnabled,
                onHomeIntent = { intent -> sharedHomeStore?.dispatch(intent) },
                directMessage = renderedThread.surfaceKind == RaftThreadSurfaceKind.DirectMessage,
            )?.let { panel -> completeChannelSettingsPanel(panel, sharedHomeState.content, onLeave = onBack) }
        // A phone's settings page (ThreadSettingsPage.kt) reads this conversation through its link:
        // the same stores and callbacks as the drawer above, as live sources.
        val settingsPageLink = remember(settingsPageLinkKey) { ThreadSettingsPageLink() }
        DisposableEffect(settingsPageLinkKey, settingsPageLink) {
            ThreadSettingsPageLinks.register(settingsPageLinkKey, settingsPageLink)
            onDispose { ThreadSettingsPageLinks.unregister(settingsPageLinkKey, settingsPageLink) }
        }
        // Leave, Delete, Disconnect or Archive on the page: the conversation goes once it is back on screen.
        var leaveAfterSettingsPage by remember(settingsPageLinkKey) { mutableStateOf(false) }
        LaunchedEffect(leaveAfterSettingsPage, active) {
            if (leaveAfterSettingsPage && active) {
                leaveAfterSettingsPage = false
                onBack()
            }
        }
        val settingsPageMembers =
            remember(serverMembersStore, threadMembersServerId, threadMembersChannelId) {
                if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
                    serverMembersStore.channelMemberSliceFlow(serverId = threadMembersServerId, channelId = threadMembersChannelId)
                } else {
                    null
                }
            }
        val settingsPageRenderSlices = remember(threadViewModel) { threadViewModel.renderSliceFlow() }
        val settingsPageSources =
            ThreadSettingsPageSources(
                directMessage = renderedThread.surfaceKind == RaftThreadSurfaceKind.DirectMessage,
                title = headerDisplayModel.title,
                channelKind = headerDisplayModel.channelKind,
                subtitle = headerDisplayModel.subtitle,
                renderSlices = settingsPageRenderSlices,
                initialRenderSlice = renderSlice,
                homeState = sharedHomeStateFlow,
                hasHomeStore = sharedHomeStore != null,
                onHomeIntent = { intent -> sharedHomeStore?.dispatch(intent) },
                projections = directoryConversationProjectionFlow,
                initialProjections = directoryConversationProjections,
                selectProjection = { projections ->
                    conversationDirectoryLookupIds.firstNotNullOfOrNull { id ->
                        projections[id]?.takeIf { projection -> headerProjectionInput.acceptsForDisplay(projection) }
                    }
                },
                serverId = threadMembersServerId,
                channelId = threadMembersChannelId,
                members = settingsPageMembers ?: MutableStateFlow(threadChannelMembersSlice),
                initialMembers = threadChannelMembersSlice,
                agentStatusResolver = agentStatusResolver,
                collapseServerId = currentSession?.currentServerId,
                collapseConversationId =
                    payload.longMessageCollapseConversationId(
                        homeContent = sharedHomeState.content,
                        taskParentChannelId = taskConversationParentChannelId,
                    ),
                completePanel = { panel, homeContent, onLeave -> completeChannelSettingsPanel(panel, homeContent, onLeave) },
                onOpenMembers = { add ->
                    val serverId = threadMembersServerId
                    val channelId = threadMembersChannelId
                    if (serverId != null && channelId != null) onOpenMembersPager(renderedThread, serverId, channelId, storeScope, add)
                },
                onLeaveConversation = { leaveAfterSettingsPage = true },
                // The standalone settings page: delete, disconnect or a private leave goes back as the
                // drawer's Leave does; a public leave stays here with the composer's Join.
                onChannelSettingsClosed = { landing, channelId ->
                    if (landing != ChannelSettingsCloseLanding.StayInChannel) {
                        currentSession?.currentServerId?.takeIf(String::isNotBlank)?.let { serverId ->
                            realtimeRoomLeaseRegistry.forgetJoinedByUser(serverId, channelId.value)
                        }
                    }
                    when (landing) {
                        ChannelSettingsCloseLanding.StayInChannel -> Unit
                        ChannelSettingsCloseLanding.StayNotJoined ->
                            threadViewModel.dispatch(SharedThreadIntent.ApplyChannelLeft(channelId.value))
                        ChannelSettingsCloseLanding.LeaveConversation -> leaveAfterSettingsPage = true
                    }
                },
            )
        SideEffect { settingsPageLink.sources = settingsPageSources }
        val openSettingsPage: () -> Boolean = {
            val serverId = threadMembersServerId
            val channelId = threadMembersChannelId
            if (serverId != null && channelId != null && renderedThread.surfaceKind == RaftThreadSurfaceKind.Channel) {
                // A channel's settings are its details now (the old settings page only closes for a
                // channel): the announcement bar's settings open them the way the header does.
                onOpenChannelSettings(serverId, channelId, storeScope, settingsPageLinkKey)
                true
            } else serverId != null && channelId != null &&
                pager.openRaftPage(
                    pageName = THREAD_SETTINGS_PAGE,
                    pageData = threadSettingsPageData(settingsPageLinkKey, serverId, channelId),
                    storeScopeOverride = storeScope,
                    authenticatedRouteAuthorityRequired = true,
                    onMissingScope = { bridge.logWarn("thread_settings_page_open_skip reason=missing_scope") },
                )
        }
        // Agent Lounge v0: this page as (or inside) an agent's Lounge, and an agent DM's Lounge toggle.
        val loungeAssistantAnswers by
            remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) }.answers.collectAsState()
        val threadLounge =
            rememberThreadPageLounge(
                payload = renderedThread,
                bridge = bridge,
                storeScope = storeScope,
                flags = sharedStores.serverFeatureFlags,
                serverId = currentServerId,
                serverRole = currentSession?.servers?.firstOrNull { it.id == currentServerId }?.role,
                memberAvatarResolver = memberAvatarResolver,
                personalAssistantAgentIds =
                    remember(loungeAssistantAnswers) {
                        loungeAssistantAnswers.values.mapNotNull { it.assistant?.agentId }.toSet()
                    },
                onOpenConversation = { identity ->
                    buildAppShellThreadPayload(
                        conversation = identity,
                        timeContext = renderedPayload.toRaftTimeCapabilities().staticContext(),
                        alignOwnMessagesRight = renderedPayload.alignOwnMessagesRight,
                        compactConsecutiveMessages = renderedPayload.compactConsecutiveMessages,
                        onWarn = bridge::logWarn
                    )?.let(onOpenThreadPayload)
                        ?: bridge.logWarn("agent_lounge_open_rejected reason=identity_unresolved")
                },
                onOpenAgentProfile = { agentId ->
                    memberDetailIdentity(agentId, MemberKind.Agent, currentServerId)?.let { identity ->
                        onOpenMemberProfile(renderedThread, renderedTimelineSlice, identity)
                    }
                },
            )
        // Agent Lounge v0 (web slock #13880 `reopenRouteDm`): opening a closed Lounge, from any
        // entry point, takes it off `hiddenDmIds`, as opening a closed DM on the web does.
        val openedLoungeId =
            threadLounge.lounge?.channelId?.value?.takeIf { renderedThread.surfaceKind == RaftThreadSurfaceKind.Channel }
        LaunchedEffect(openedLoungeId, sharedHomeStore) {
            val loungeId = openedLoungeId ?: return@LaunchedEffect
            sharedHomeStore?.dispatch(SharedHomeIntent.ReopenClosedConversation(loungeId))
        }
        // JVM limit: this lambda captures about 255 locals of the route, the most an invokedynamic
        // lambda may take. One more fails at runtime (BootstrapMethodError "bad parameter count 256":
        // the page never composes and stays on Loading). Read new inputs from values already used
        // here, or group these captures into a state holder, before adding a local referenced below.
        CompositionLocalProvider(*threadProvidedValues) {
            ThreadPageContent(
                lounge = threadLounge,
                onComposerKeyboardInsetChanged = { adaptivePageKeyboardInsetDp = it },
                // task #180: forwarded from the route scope, which owns the workspace store.
                onToggleFocusMode = threadFocusModeToggle,
                focusModeActive = threadFocusModeActive,
            taskConversationBar = taskConversationBar,
            // A conversion to an external channel under way: one slot, worked out in
            // ChannelConversionStrip.kt from values this lambda already captures (JVM size and capture limits).
            channelConversionStrip =
                rememberThreadChannelConversionStripSlot(
                    sharedStores = sharedStores,
                    storeScope = storeScope,
                    bridge = bridge,
                    serverId = threadMembersServerId,
                    channelId = threadMembersChannelId,
                    payload = renderedThread,
                    session = currentSession,
                    active = active && threadHostForeground,
                ),
            // Channel announcements (channel_announcement_v0): the strip on a channel's own page (not a
            // thread, DM or task conversation). Built here from values this lambda already captures:
            // it is at the JVM's 255-capture limit, and one more local breaks it (BootstrapMethodError).
            channelAnnouncementStrip =
                rememberThreadChannelAnnouncementStripSlot(
                    sharedStores = sharedStores,
                    storeScope = storeScope,
                    bridge = bridge,
                    serverId = threadMembersServerId?.value,
                    channelId = threadMembersChannelId?.value,
                    channelType =
                        renderedThread.channelKind.takeIf {
                            renderedThread.surfaceKind == RaftThreadSurfaceKind.Channel && !renderedThread.isTaskConversationSurface()
                        },
                    active = active && threadHostForeground,
                    onOpenSettings = { openSettingsPage() },
                ),
            taskConversationChrome = taskConversationChrome,
            taskConversationHead = taskHeadScrollState.takeIf { taskConversationBar != null },
            taskConversationFloat = taskConversationFloat,
            taskConversationHeadRow = taskConversationHeadRow.takeIf { taskConversationBar != null },
            agentFollowersHeader = {
                // Task v2: a task conversation's bar carries its Agent followers too (web
                // ThreadAgentFollowers `subject="task"` on the conversation channel).
                if (renderedThread.surfaceKind == RaftThreadSurfaceKind.ReplyThread || renderedThread.isTaskConversationSurface()) {
                    ThreadAgentFollowersRoute(
                        threadId = renderedThread.streamChannelId,
                        serverId = currentServerId,
                        accountId = currentSession?.user?.id,
                        storeScope = storeScope,
                        active = active && threadHostForeground,
                        onOpenAgentProfile = { identity ->
                            onOpenMemberProfile(renderedThread, renderedTimelineSlice, identity)
                        },
                        agentStatusResolver = agentStatusResolver,
                    )
                }
            },
            scaffold = movableThreadRouteScaffold,
            threadInstanceKey = threadInstanceKey,
            routeActive = active,
            hostForeground = threadHostForeground,
            payload = renderedThread,
            messageBodyTextStyle = resolvedMessageBodyTextStyle,
            timelineSlice = renderedTimelineSlice,
            accessResolution = renderSlice.accessResolution,
            canonicalAccessResolved = renderSlice.canonicalAccessResolved,
            initialHomeAccessLookupComplete = renderSlice.initialHomeAccessLookupComplete,
            lastRefreshResultReason = renderSlice.lastRefreshResultReason,
            replyParentAuthority = renderSlice.replyParentAuthority,
            writeActionsEnabled = renderSlice.writeActionsEnabled,
            jointBillingNotice = jointBillingNotice,
            jointGraceDeadlineLabel = jointGraceDeadlineLabel,
            externalFreezeState = externalFreezeState,
            billingServerName =
                (headerProjectionServerId?.value?.let { sid ->
                    currentSession?.servers?.firstOrNull { it.id == sid }?.name
                } ?: currentSession?.currentServerSummary()?.name).orEmpty(),
            billingServerPlan =
                headerProjectionServerId?.value?.let { sid ->
                    currentSession?.servers?.firstOrNull { it.id == sid }?.plan
                } ?: currentSession?.currentServerSummary()?.plan,
            billingServerRole =
                headerProjectionServerId?.value?.let { sid ->
                    currentSession?.servers?.firstOrNull { it.id == sid }?.role
                } ?: currentSession?.currentServerSummary()?.role,
            channelProfileUpdateStatus = renderSlice.channelProfileUpdateStatus,
            channelProfileUpdateErrorCode = renderSlice.channelProfileUpdateErrorCode,
            replyMetadataByParentId = replyMetadataByParentId,
            headerDisplay = headerDisplayModel,
            composerSurface = composerSurface,
            baseComposerState = scopedBaseComposerState,
            composerDraftViewModel = composerDraftViewModel,
            composerDraftPersistSlice = composerDraftPersistSlice,
            resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
            tasksViewModel = tasksViewModel,
            taskChannelId = taskChannelId,
            // artin 2026-09-01/02: the task assignee picker must list CURRENT CHANNEL
            // members, never the server roster. Channel member slice once loaded;
            // before that, reuse the channel roster the thread payload already
            // carries (baseRenderedPayload.members via threadMembersProjection)
            // — synchronous, so the picker never flashes server-wide names.
            taskIdentityOptions = taskAssigneeOptions,
            linkedTasksProjection = linkedTasksProjection,
            filesViewModel = filesViewModel,
            memberSuggestionsFlow = memberSuggestionsFlow,
            channelSuggestionsFlow = channelSuggestionsFlow,
            selectedTab = selectedThreadTab,
            availableTabs = availableThreadTabs,
            sideCardsEnabled = sideCardsEnabled,
            onSelectedTabChanged = { selectedThreadTab = it },
            onSuggestionDemandChanged = { composerSuggestionDemand = it },
            activityMuteState = activityMuteState?.takeIf { renderSlice.writeActionsEnabled },
            activityMutedIndicatorVisible = activityMutedIndicatorVisible,
            onOpenSettingsPage = openSettingsPage,
            onToggleActivityMute =
                activityMuteState?.takeIf { renderSlice.writeActionsEnabled }?.let { muteState ->
                    {
                        sharedHomeStore?.dispatch(
                            SharedHomeIntent.SetChannelActivityMuted(
                                conversationId = muteState.channelId,
                                muted = !muteState.muted
                            )
                        )
                    }
                },
            conversationPinState = conversationPinState,
            forwardServerId = headerProjectionServerId,
            forwardTargetRepository = forwardTargetRepository,
            contentExtrasVisible = true,
            onOpenTaskSummary = openCanonicalTask,
            onTasksIntent = { intent ->
                if (
                    renderSlice.writeActionsEnabled ||
                    (intent !is SharedTasksRouteIntent.UpdateStatus && intent !is SharedTasksRouteIntent.CreateTasks)
                ) {
                    tasksViewModel?.dispatch(intent)
                }
            },
            onFilesAuthRequired = { code -> routeAuthRequired(code) },
            onOpenFilesAttachment = { attachment, imageGroup ->
                if (imageGroup != null) {
                    openImagePreview(imageGroup, conversationWide = false)
                } else {
                    openAttachmentNoPreview(attachment)
                }
            },
            onDownloadFilesAttachment = { attachment -> saveAttachmentExternally(attachment) },
            onOpenMemberProfile = { identity -> onOpenMemberProfile(renderedThread, renderedTimelineSlice, identity) },
            onExternalAuthor = onExternalAuthorHint,
            highlightedMentions = highlightedMentions,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = effectiveMessageAvatarResolver,
            memberModelLabelResolver = memberModelLabelResolver,
            diagnosticsLogger = { bridge.log(it) },
            onLayoutGeneration = { width, height ->
                performanceGenerationCounter.nextLayout(width, height)?.let { generation ->
                    bridge.performanceMarker(
                        phase = "thread_layout_generation",
                        routeName = "kuikly_thread",
                        threadId = renderedThread.sourceThreadId,
                        metadata =
                            mapOf(
                                "generation" to generation.generation.toString(),
                                "reconcileGeneration" to generation.reconcileGeneration.toString(),
                                "width" to width.toString(),
                                "height" to height.toString()
                            )
                    )
                }
            },
            onComposerLayoutProbe = { phase, metadata ->
                bridge.performanceMarker(
                    phase = phase,
                    routeName = "kuikly_thread",
                    threadId = renderedThread.sourceThreadId,
                    metadata = metadata
                )
            },
            onComposerPixelProbe = { x, y, width, height, generation ->
                bridge.probeThreadViewportPixels(x, y, width, height, generation)
            },
            pendingActionCardIds = pendingActionCardIds,
            markdownReferencePendingActionId = markdownReferencePending.busyActionId,
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
            mentionHandleResolver = { message ->
                message.threadMentionHandle(
                    canonicalMemberHandle =
                        threadMembersServerId?.let { serverId ->
                            serverMembersStore?.currentCanonicalMentionHandle(
                                serverId = serverId,
                                mentionChannelId = memberSuggestionScopeChannelId,
                                fallbackChannelId = threadMembersChannelId?.value,
                                memberId = message.authorId
                            )
                        }
                )
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
            onFirstContentReady = {
                threadViewModel.dispatch(SharedThreadIntent.FirstContentReady)
            },
            onVisibleReadTailChange = { index -> visibleReadTailIndex = index },
            onViewportFollowsLatestChange = { follows -> threadViewModel.setViewportFollowsLatest(follows) },
            visibleReadTailIndex = visibleReadTailIndex,
            adaptiveCloseRequest = adaptiveCloseRequest,
            adaptiveStoreScope = storeScope,
            adaptiveEventBus = eventBus,
            // From values this lambda already captures (see the JVM-limit note above).
            incomingNavigation = sharedStores.foregroundBannerStore(storeScope).incomingNavigation,
            onLoadNewer = {
                threadViewModel.loadNewerPage()
            },
            onJumpToLatest = {
                threadViewModel.jumpToLatest()
            },
            onBack = onBack,
            onUpdateChannelProfile = { channelId, originalName, originalDescription, name, description ->
                if (renderSlice.writeActionsEnabled) {
                    threadViewModel.dispatch(
                        SharedThreadIntent.UpdateChannelProfile(
                            channelId = channelId,
                            originalName = originalName,
                            originalDescription = originalDescription,
                            name = name,
                            description = description
                        )
                    )
                }
            },
            onClearChannelProfileUpdateStatus = {
                threadViewModel.dispatch(SharedThreadIntent.ClearChannelProfileUpdateStatus)
            },
            onForwardMessagesDelivered = { result ->
                applyForwardMessageDeliveries(
                    result = result,
                    messageStore = sharedStores.threadMessageStore,
                    onHomeRefresh = { sharedHomeStore?.dispatch(SharedHomeIntent.ForegroundRefresh) }
                )
            },
            onOpenForwardDestination = { banner ->
                val conversation = banner.forwardSuccessConversationIdentity()
                val bannerPayload =
                    conversation?.let {
                        buildAppShellThreadPayload(
                            conversation = it,
                            timeContext = renderedPayload.toRaftTimeCapabilities().staticContext(),
                            alignOwnMessagesRight = renderedPayload.alignOwnMessagesRight,
                            compactConsecutiveMessages = renderedPayload.compactConsecutiveMessages,
                            anchorMessageId = banner.messageId.value,
                            onWarn = bridge::logWarn
                        )
                    }
                if (bannerPayload == null) {
                    bridge.logWarn("thread_forward_success_open_rejected reason=identity_unresolved")
                } else {
                    onOpenThreadPayload(bannerPayload)
                }
            },
            onThreadEvent = ThreadEventHandler(dispatch = threadRouteEventHandler::handle),
            onComposerSendRequest = sendRequest@ { request ->
                if (!threadComposerEventAllowed(renderSlice.writeActionsEnabled && !threadLimitLocked, "send")) {
                    bridge.log("thread_composer_action_blocked action=send reason=${if (threadLimitLocked) "channel_limit_locked" else "read_only_access_fallback"}")
                    return@sendRequest ThreadComposerSendAdmission.Rejected
                }
                handleComposerSendRequest(request)
            },
            onComposerDropFiles = { drop ->
                // Files dragged onto the composer (web MessageInput handleDrop): the web's folder
                // notice, then the host reads them as a File pick and the picker's admission
                // takes them (sizes, skipped files, upload), straight into the tray.
                if (!threadComposerEventAllowed(renderSlice.writeActionsEnabled && !threadLimitLocked, ThreadComposerDropAttachmentsAction)) {
                    bridge.log("thread_composer_action_blocked action=$ThreadComposerDropAttachmentsAction reason=${if (threadLimitLocked) "channel_limit_locked" else "read_only_access_fallback"}")
                    drop.release()
                } else {
                    coroutineScope.launch {
                        try {
                            bridge.log("thread_attachment_drop files=${drop.fileRefs.size} folders=${drop.folderCount}")
                            when (drop.folderNotice()) {
                                ThreadComposerFileDropFolderNotice.FoldersCantUpload -> bridge.toast(dropFoldersCantUploadLabel)
                                ThreadComposerFileDropFolderNotice.FoldersSkipped -> bridge.toast(dropFoldersSkippedLabel)
                                ThreadComposerFileDropFolderNotice.None -> Unit
                            }
                            if (drop.fileRefs.isEmpty()) return@launch
                            if (composerSurface.sendChannelId == null) {
                                bridge.toast(attachmentPickerUnavailableLabel)
                                return@launch
                            }
                            val response = bridge.threadAttachmentDrop(drop.fileRefs)
                            admitComposerPickedAttachments(ThreadComposerDropAttachmentsAction, response)
                        } finally {
                            drop.release()
                        }
                    }
                }
            },
            onComposerEvent = { action, value, asTask, attachmentId ->
                threadRouteComposerActions.onComposerEvent(action, value, asTask, attachmentId)
            }
            )
        }
        // Where a quote of selected text lands (Select Text > Quote, the mouse selection's Quote).
        val selectionQuoteTarget =
            threadSelectTextQuoteTarget(
                surfaceKind = renderedThread.surfaceKind,
                composerAvailable =
                    !threadLimitLocked &&
                        shouldShowThreadComposerForReadyContent(
                            joined = payload.joined && renderSlice.writeActionsEnabled,
                            showComposer = payload.showComposer && renderSlice.writeActionsEnabled,
                            selectedTab = selectedThreadTab.wireValue
                        )
            )
        // The in-place selection toolbar's Quote: the same composer append as Select Text > Quote.
        val selectionQuoteAvailable = selectionQuoteTarget != ThreadSelectTextQuoteTarget.None
        val inPlaceSelectionQuote: ((String) -> Unit)? =
            remember(selectionQuoteAvailable, composerDraftViewModel) {
                if (selectionQuoteAvailable) {
                    { quote: String -> composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.AppendQuote(quote)) }
                } else {
                    null
                }
            }
        val inPlaceSelectionQuoteInsert: ((ThreadComposerQuoteInsert) -> Unit)? =
            remember(selectionQuoteAvailable, quoteReplyHost) {
                if (selectionQuoteAvailable) quoteReplyHost::requestInsertQuote else null
            }
        SideEffect {
            inPlaceTextSelectionHost.quoteIntoComposer = inPlaceSelectionQuote
            inPlaceTextSelectionHost.insertQuoteIntoComposer = inPlaceSelectionQuoteInsert
        }
        ThreadRouteDialogs(
            states = threadRouteDialogStates,
            data = data,
            labels = labels,
            selectionQuoteTarget = selectionQuoteTarget,
            quoteReplyHost = quoteReplyHost,
            payload = payload,
            resolvedMessageBodyTextStyle = resolvedMessageBodyTextStyle,
            composerDraftViewModel = composerDraftViewModel,
            threadViewModel = threadViewModel,
            renderedTimelineSlice = renderedTimelineSlice,
            linkedTasksProjection = linkedTasksProjection,
            installAppDialogStore = installAppDialogStore,
            actionCardSpecState = actionCardSpecState,
            actionCardNames = actionCardNames,
            currentServerId = currentServerId,
            sharedHomeState = sharedHomeState,
            threadServerMembersEntry = threadServerMembersEntry,
            threadMembersServerId = threadMembersServerId,
            threadMembersChannelId = threadMembersChannelId,
            actionCardStepUp = actionCardStepUp,
            actionCardStepUpState = actionCardStepUpState,
            coroutineScope = coroutineScope,
            pager = pager,
            onAuthRequired = onAuthRequired,
            routeAuthRequiredForCode = ::routeAuthRequired,
            routeAuthRequiredForResult = ::routeAuthRequired,
            onReloadAttachmentComments = ::reloadAttachmentComments,
            onSendAttachmentComment = ::sendAttachmentComment,
            onResolveAttachmentComment = ::resolveAttachmentComment,
        )
    }
}

private fun RaftThreadScreenPayload.threadComposerMentionScopeChannelId(): String? {
    val resolvedComposer = resolveThreadComposer()
    return resolvedComposer.mentionChannelId.nonBlank()
        ?: resolvedAuxiliaryThreadId()
        ?: resolvedComposer.streamChannelId.nonBlank()
        ?: streamChannelId.nonBlank()
        ?: sourceThreadId.nonBlank()
        ?: parentChannelId.nonBlank()
}

private const val ThreadFirstContentTaskJoinDelayMs = 96L

/**
 * Typed text is written in the background within ~40 ms (task #246); going to the background, like
 * leaving the page, also writes what is still pending at once. Its own function: the route
 * content composable is at its JVM method-size ceiling.
 */
@Composable
private fun FlushComposerDraftOnLeave(
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    threadInstanceKey: String,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(composerDraftViewModel, threadInstanceKey, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.Flush)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.Flush)
        }
    }
}

/**
 * The thread's action-card step-up (out of SharedThreadRouteContentContent, whose JVM method size
 * is ratcheted: scripts/ci/check_jvm_method_size.py).
 */
private fun threadActionCardStepUpCoordinator(networkClient: RaftNetworkClient): SecondFactorStepUpCoordinator {
    val repository = SecondFactorStepUpRepository(networkClient)
    return SecondFactorStepUpCoordinator(
        stepUp = repository::stepUp,
        passkeyOptions = repository::passkeyAssertionOptions,
        webStepUp = repository.webStepUp(),
    )
}

/**
 * Opens an action card's "Open in web" link with the App Store 3.1.1 guard and own-origin
 * authenticated WebView (out of SharedThreadRouteContentContent to stay below its JVM method-size cap).
 */
private fun openThreadActionCardWebUrl(
    url: String,
    activeWebUrl: String,
    pager: Pager,
    storeScope: RaftShellStoreScope,
    bridge: build.raft.app.base.BridgeModule,
) {
    bridge.log("thread_action_card_open_in_web")
    if (
        raftPlatformFollowsAppStoreBillingRules() &&
        raftUrlIsAppStoreRestrictedPurchaseUrl(url, activeWebUrl)
    ) {
        bridge.logWarn("thread_action_card_appstore_purchase_blocked url=${raftOwnWebUrlForLog(url)}")
        if (raftUrlIsOwnBillingPath(url, activeWebUrl)) {
            pager.openSettingsBillingPage(storeScope)
        }
    } else if (!pager.openRaftOwnWebPage(url, storeScope, activeWebBaseUrl = activeWebUrl)) {
        bridge.hostCapabilities.openUrlInBrowser(url)
    }
}

/**
 * Connected servers of the active external channel (or parent channel for reply threads and task
 * conversations), out of SharedThreadRouteContentContent to stay below its JVM method-size cap.
 */
internal fun threadChannelJointServers(
    lookupIds: List<String>,
    parentChannelId: String?,
    taskParentChannelId: String?,
    serverId: String?,
    projections: Map<String, SharedConversationDirectoryProjection>,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
): List<build.raft.app.model.MemberServerRef> {
    val candidates = buildList {
        parentChannelId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        taskParentChannelId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        addAll(lookupIds)
    }
    for (id in candidates) {
        val joint = projections[id]?.jointServers
        if (!joint.isNullOrEmpty()) return joint
    }
    val server = serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId) ?: return emptyList()
    for (id in candidates) {
        val joint = conversationIdentityProvider?.resolveProjection(server, id)?.jointServers
        if (!joint.isNullOrEmpty()) return joint
    }
    return emptyList()
}

@Composable
private fun rememberThreadChannelJointServers(
    lookupIds: List<String>,
    parentChannelId: String?,
    taskParentChannelId: String?,
    serverId: String?,
    projections: Map<String, SharedConversationDirectoryProjection>,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
    homeContentRevision: Any? = null,
): List<build.raft.app.model.MemberServerRef> =
    remember(lookupIds, parentChannelId, taskParentChannelId, serverId, projections, conversationIdentityProvider, homeContentRevision) {
        threadChannelJointServers(
            lookupIds = lookupIds,
            parentChannelId = parentChannelId,
            taskParentChannelId = taskParentChannelId,
            serverId = serverId,
            projections = projections,
            conversationIdentityProvider = conversationIdentityProvider,
        )
    }

/**
 * [page]'s reply metadata with each thread's unread raised to Home's count for it
 * ([withHomeFollowedThreadUnread]), kept per input pair. Outside `SharedThreadRouteContentContent` to
 * keep that route below its JVM method-size cap.
 */
@Composable
private fun rememberInlineReplyMetadata(
    page: Map<String, ThreadReplyMetadata>,
    followedThreadUnread: Map<String, HomeFollowedThreadUnread>?,
): Map<String, ThreadReplyMetadata> =
    remember(page, followedThreadUnread) { page.withHomeFollowedThreadUnread(followedThreadUnread) }

/**
 * Hosts [RaftViewerImageEditor] outside `SharedThreadRouteContentContent` (keeping that route
 * below its JVM method-size cap) and routes the post-edit deferred refocus through
 * [build.raft.designsystem.focus.RaftFocusCoordinator] after the editor/lightbox modals dispose.
 */
@Composable
private fun ThreadViewerImageEditHost(
    attachment: RaftThreadAttachmentItem,
    desktop: Boolean,
    threadInstanceKey: String,
    composerInstanceId: String,
    bridge: build.raft.app.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    coroutineScope: CoroutineScope,
    genericThreadErrorLabel: String,
    nextEditSequence: () -> Long,
    onCancel: () -> Unit,
    onDismissModals: () -> Unit,
    uploadComposerAttachmentFiles: suspend (List<PlatformFile>, String?, Boolean, Map<String, String>) -> Boolean,
) {
    val focusCoordinator = currentRaftFocusCoordinator()
    val focusRegion = LocalRaftFocusRegion.current
    val focusModalToken = LocalRaftFocusModalToken.current
    RaftViewerImageEditor(
        attachment = attachment,
        desktop = desktop,
        resolveFullUrl = { attachmentId ->
            when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline")) {
                is ThreadApiResult.Success -> response.data.url
                is ThreadApiResult.Failure -> {
                    bridge.log("thread_viewer_image_edit status=url_failed attachmentId=${attachmentId.take(8)} code=${response.code}")
                    null
                }
            }
        },
        onCancel = onCancel,
        onSaveFailed = { bridge.toast(genericThreadErrorLabel) },
        onEdited = { fileRef ->
            val edited = raftViewerEditedImageFile(fileRef, attachment.name, nextEditSequence())
            val startInteractionEpoch = focusCoordinator.interactionEpoch
            val targetKey = ThreadComposerFocusTargetKey(composerInstanceId)
            onDismissModals()
            bridge.log("thread_viewer_image_edit status=done attachmentId=${attachment.id.take(8)}")
            coroutineScope.launch {
                // Wait up to two frames for the dismissed editor/lightbox modals to dispose and
                // release their modal tokens (and let any OS window refocus settle) before
                // capturing the deferred focus ticket's focusEpoch (audit S3 / rule 3).
                var settleFrames = 0
                while (settleFrames < 2 && focusCoordinator.interactionEpoch == startInteractionEpoch) {
                    withFrameNanos { }
                    settleFrames += 1
                }
                val deferredFocusTicket: RaftFocusTicket? =
                    if (
                        focusCoordinator.interactionEpoch == startInteractionEpoch &&
                        focusCoordinator.topModalToken == focusModalToken
                    ) {
                        focusCoordinator.request(
                            targetKey = targetKey,
                            region = focusRegion,
                            source = RaftFocusRequestSource.System("image_edit_complete"),
                            isText = true,
                            modalToken = focusModalToken,
                        )
                    } else {
                        null
                    }
                var dispatchedFocus = false
                try {
                    val prepared =
                        threadComposerCompressPastedImages(
                            files = listOf(edited),
                            originalImage = composerDraftViewModel.state.value.originalImage,
                            compress = { file ->
                                val response =
                                    bridge.compressThreadImage(
                                        fileRef = file.fileRef.orEmpty(),
                                        fileName = file.fileName,
                                        mimeType = file.mimeType?.ifBlank { null } ?: "image/png",
                                    )
                                (parseRaftFilePreviewCompressedImageResult(response)
                                    as? RaftFilePreviewCompressedImageResult.Success)?.file
                            },
                            retire = { refs ->
                                bridge.retireFilePreviewTemporaryFiles(
                                    sessionId = "thread-viewer-edit-$threadInstanceKey",
                                    fileRefs = refs
                                )
                            },
                            log = bridge::log,
                        )
                    val admitted = uploadComposerAttachmentFiles(prepared, null, false, emptyMap())
                    if (admitted && deferredFocusTicket != null) {
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.RequestDeferredFocus(deferredFocusTicket)
                        )
                        dispatchedFocus = true
                    }
                } finally {
                    if (!dispatchedFocus && deferredFocusTicket != null && focusCoordinator.isLatest(deferredFocusTicket)) {
                        focusCoordinator.cancelPending(deferredFocusTicket.targetKey)
                    }
                }
            }
        },
    )
}

/**
 * Logs `thread_first_reveal` once without subscribing [SharedThreadRouteContentContent] to
 * [LocalRaftStackPageInMotion], so settling the push slide never triggers a full-page recomposition.
 */
@Composable
private fun ThreadFirstRevealMotionLogger(
    threadInstanceKey: String,
    ready: Boolean,
    threadId: String,
    accessResolutionName: String,
    homeLookupComplete: Boolean,
    revealMessages: Int,
    log: (String) -> Unit,
) {
    var logged by remember(threadInstanceKey) { mutableStateOf(false) }
    if (logged || !ready) return
    val revealedInMotion = LocalRaftStackPageInMotion.current
    SideEffect {
        logged = true
        log(
            "thread_first_reveal threadId=$threadId " +
                "inMotion=$revealedInMotion access=$accessResolutionName homeLookupComplete=$homeLookupComplete " +
                "messages=$revealMessages"
        )
        if (revealedInMotion) {
            log("thread_first_reveal_in_motion threadId=$threadId messages=$revealMessages")
        }
    }
}

/**
 * Runs a non-visual [LaunchedEffect] once the initial push slide settles (`LocalRaftStackPageInMotion`
 * turns false). Latches one-way so pop-back animations never cancel or replay already-started work,
 * and reading the CompositionLocal in this leaf keeps the enclosing thread route out of motion
 * invalidations.
 */
@Composable
private fun ThreadAfterMotionEffect(
    vararg keys: Any?,
    block: suspend CoroutineScope.() -> Unit,
) {
    val settledLatch = remember { booleanArrayOf(false) }
    val settledOnce = settledLatch[0] || !LocalRaftStackPageInMotion.current
    if (settledOnce && !settledLatch[0]) {
        SideEffect { settledLatch[0] = true }
    }
    val currentBlock by rememberUpdatedState(block)
    if (settledOnce) {
        LaunchedEffect(*keys) {
            currentBlock()
        }
    }
}
