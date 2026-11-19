package build.raft.app

import build.raft.app.links.ParsedRaftPermalink
import build.raft.app.assistant.LocalThreadAssistantOtter
import build.raft.app.assistant.LocalThreadAssistantPanelEmbed
import build.raft.app.assistant.assistantContextTokenForSend
import build.raft.app.assistant.withAssistantContextReference
import build.raft.app.assistant.newAssistantOtterHostId
import build.raft.app.assistant.rememberThreadAssistantOtter
import build.raft.app.assistant.threadForegroundBannerOnPage
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.resources.*
import build.raft.app.transfer.RaftTransferMeter
import build.raft.app.transfer.RaftTransferProgress
import build.raft.app.downloads.RaftDownloadCenterProcess
import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.homeStateForPage
import build.raft.app.data.RaftRatingPrompt
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.openWithinWideDetail
import build.raft.app.data.visibleSideThread
import build.raft.designsystem.components.raftPaneGridCellExpand
import build.raft.app.data.RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_EVENT
import build.raft.app.data.AdaptiveParentFocusOutcome
import build.raft.app.data.AdaptiveParentFocusRequest
import build.raft.app.data.parentFocusOutcomeAfterWait
import build.raft.app.data.acceptsParentFocus
import build.raft.app.data.sideThreadParentFocus
import build.raft.app.data.toAdaptiveParentFocusRequestOrNull
import build.raft.app.data.AdaptiveThreadOpenRoute
import build.raft.app.data.routeThreadOpen
import build.raft.app.base.Utils
import build.raft.app.base.reduceAttachmentDownloadProgress
import build.raft.app.data.RaftHostNetworkEvents
import build.raft.app.data.PlatformFile
import build.raft.app.data.ThreadPendingSendInFlightRegistry
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AuthLossSignal
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.NetworkRaftConversationArtifactsApi
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.data.NetworkRaftTasksApi
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftThreadApiClient
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
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.map
import build.raft.app.data.EXTERNAL_CHANNELS_FEATURE_FLAG_KEY
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.raftAppActionCardCapabilities
import build.raft.app.data.featureFlagClientOs
import build.raft.app.data.ThreadComposerDirectUploadDecision
import build.raft.app.data.ThreadComposerDirectUploadPhase
import build.raft.app.data.resolveThreadComposerDirectUpload
import build.raft.app.data.ThreadComposerDirectUploadClientRequestIds
import build.raft.app.data.newAttachmentUploadClientRequestId
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.TASK_V2_CONVERSATIONS_FEATURE_FLAG_KEY
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ownSentConversationIds
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.format.RaftFormat
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpOutcome
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
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.unfollowThreadProjectingHome
import build.raft.app.data.SharedMembersIntent
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.SharedMembersStore
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedReferenceFailure
import build.raft.app.data.sharedReferenceFailure
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TaskConversationDetailsRepository
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskConversationHeaderEvent
import build.raft.app.data.TaskConversationHeaderViewModel
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedAdaptiveScrollAnchor
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.isActionCardExpiredRefusal
import build.raft.app.data.markedExpired
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.RealtimeCanonicalFinalSinkSurfaceKey
import build.raft.app.data.RealtimeScopeAuthority
import build.raft.app.data.RealtimeRoomLeaseOwner
import build.raft.app.data.appLinkViewerKey
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isAuthSessionErrorCode
import build.raft.app.data.authLossTokenFingerprint
import build.raft.app.data.convertMessageToCanonicalTask
import build.raft.app.data.isSupportedCommitAction
import build.raft.app.data.isFeedbackSubmitAction
import build.raft.app.data.isInstallMarketplaceAppAction
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.threadMessageBodyOwnerKey
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses
import build.raft.app.markdown.LocalRaftMarkdownTaskRefStatuses
import build.raft.app.markdown.parseRaftMarkdownTaskRefLabel
import build.raft.app.markdown.parseRaftMarkdownMessageRefLabel
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
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.RaftAgentControlsApi
import build.raft.app.model.Heartbeat
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.TaskStatus
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
import androidx.compose.ui.text.input.TextFieldValue
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
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.TimeMark
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject

private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
internal const val THREAD_MEMBERS_PAGE = "compose_thread_members"
private const val ThreadComposerMentionActionFadeDelayMs = 450L
private const val ThreadComposerMentionActionRemoveDelayMs = 750L
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

private val ThreadLocalSendNextId = ThreadLocalSendIdGenerator()

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
                Box(modifier = focusDismissModifier.fillMaxSize()) {
                    ThreadPageScreen(
                        pageVisibleTick = raftPageVisibleResumeTick,
                        pageVisible = raftPageVisible,
                        adaptivePaneState = adaptivePaneState,
                        adaptivePaneEvent = adaptivePaneEvent,
                    )
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

private fun Pager.openThreadChannelSearch(payload: RaftThreadScreenPayload): Boolean {
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

private fun buildThreadPendingSendMessage(
    localMessageId: String,
    context: RaftThreadPendingSendContext,
    session: StoredAuthSession?,
    timestampLabel: String = "Now"
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
        localSendOrder = localMessageId.substringAfterLast('-').toLongOrNull(),
        pendingSendContext = context,
        pendingSendErrorCode = null,
        pendingSendErrorMessage = null
    )
}

private fun buildThreadPendingSendTimestampLabel(
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

private data class ThreadMarkdownMentionOpenTarget(
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

private fun RaftThreadScreenPayload.resolveMarkdownMentionOpenTarget(
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
    val liveTimeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = parsedPayload.toRaftTimeCapabilities(),
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
        remember(networkClient) {
            val client = RaftThreadApiClient(NetworkRaftThreadApi(networkClient))
            val loader: ThreadQuotedPermalinkLoader = { target ->
                when (val result = client.messageContext(target.messageId.value, target.channelId.value)) {
                    is ThreadApiResult.Success -> {
                        val payload = result.data
                        val targetId = payload.targetMessageId?.value ?: target.messageId.value
                        payload.messages
                            .firstOrNull { it.id.value == targetId }
                            ?.toRaftThreadMessageItem(null)
                            ?.let { item -> ThreadQuotedPermalinkMessage(item, channelArchived = payload.channelArchived) }
                    }
                    is ThreadApiResult.Failure -> null
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
                homeSnapshotProvider =
                    sharedHomeStore?.let { store ->
                        { serverId -> store.awaitSnapshotFor(serverId, ThreadHomeSnapshotAwaitTimeoutMs) }
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
    CompositionLocalProvider(
        LocalThreadHeaderOriginalState provides headerOriginalState,
        LocalConversationUnavailableNav provides conversationUnavailableNav,
        LocalThreadOutlineApi provides threadOutlineApi,
        LocalThreadFindEnvironment provides threadFindEnvironment,
        LocalThreadAdaptiveInitiationCapture provides adaptiveInitiationCaptureHolder,
        LocalThreadAdaptiveNativeEditingReader provides adaptiveNativeEditingStateReader,
        LocalThreadQuotedPermalinkLoader provides quotedPermalinkLoader,
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
    onOpenChannelSettings: (ServerId, ChannelId, RaftShellStoreScope, String) -> Unit = { _, _, _, _ -> }
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
    val sharedHomeState by sharedHomeStateFlow.collectAsState()
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
    var adaptivePageKeyboardInsetDp by remember { mutableStateOf(0f) }
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
    val replyMetadataByParentId = renderSlice.replyMetadataByParentId
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
    LaunchedEffect(
        payload.surfaceKind,
        payload.parentChannelId,
        payload.parentMessageId,
        payload.originParentMessageId,
        sharedHomeState,
        knownLounges,
    ) {
        if (payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return@LaunchedEffect
        val parentChannelId =
            payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return@LaunchedEffect
        val homeContent = sharedHomeState.content as? SharedHomeContentState.Content
        val snapshot = homeContent?.baseSnapshot
        val parentRow =
            snapshot?.channels?.firstOrNull { it.channelId?.value == parentChannelId }
                ?: snapshot?.directMessages?.firstOrNull { it.channelId?.value == parentChannelId }
        val parentLounge = if (parentRow == null) RaftAgentLounges.loungeAnyServer(parentChannelId) else null
        if (parentLounge != null) {
            threadViewModel.dispatch(
                SharedThreadIntent.ApplyParentDirectoryHint(
                    parentChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    present = true,
                    joined = parentLounge.postable,
                    canSend = parentLounge.postable,
                    authoritative = true,
                )
            )
            return@LaunchedEffect
        }
        threadViewModel.dispatch(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                present = parentRow != null,
                joined = parentRow?.joined == true,
                canSend = parentRow?.permissions?.canSend == true && parentRow.archived == false,
                // Only a live network snapshot (not the durable identity cache,
                // and not one still awaiting refresh) may attest that a missing
                // parent row means the channel was deleted.
                authoritative =
                    homeContent != null &&
                        !homeContent.isDurableIdentityCache &&
                        !homeContent.needsNetworkRefresh,
            )
        )
    }
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
    var currentSession by remember(initialThreadInstanceKey) {
        mutableStateOf<StoredAuthSession?>(null)
    }
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
    var replyCacheProvenanceOwner by remember(initialThreadInstanceKey) {
        mutableStateOf<ReplyCacheProvenance?>(null)
    }
    val publishedReplyCacheProvenance by dataSource.replyCacheProvenanceState.collectAsState()
    val replyCacheProvenance =
        publishedReplyCacheProvenance.currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
    var currentServerSlug by remember(initialThreadInstanceKey) {
        mutableStateOf<String?>(null)
    }
    var startedRouteIdentity by remember(initialThreadInstanceKey) {
        mutableStateOf<ThreadRouteIdentity?>(null)
    }
    var threadOpenStartedAt by remember(initialThreadInstanceKey) {
        mutableStateOf<TimeMark?>(null)
    }
    var firstRenderLoggedThreadId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dataSource, initialThreadInstanceKey) {
        val producerContext = dataSource.captureReplyCacheProducerContext()
        currentSession = producerContext?.session
        replyCacheProvenanceOwner = producerContext?.provenance
        currentServerSlug = producerContext?.session?.currentServerSlug()
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
    val memberModelLabelResolver =
        remember(serverMembersStore, headerProjectionServerId) {
            raftMemberModelLabelResolver(
                serverMembersStore = serverMembersStore,
                serverId = headerProjectionServerId?.value,
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
    val directoryConversationProjections by headerProjectionStateOwner.state.collectAsState()
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
    val billingDirectoryProjections by directoryConversationProjectionFlow.collectAsState(
        initialDirectoryConversationProjections
    )
    var jointBillingDeadlineTick by remember(payload.instanceId) { mutableStateOf(0) }
    val jointBillingNotice =
        remember(
            billingDirectoryProjections,
            payload.surfaceKind,
            payload.parentChannelId,
            conversationDirectoryLookupIds,
            jointBillingDeadlineTick
        ) {
            jointBillingNoticeState(
                surfaceKind = payload.surfaceKind,
                parentChannelId = payload.parentChannelId,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections,
                nowEpochMillis =
                    payload.nowEpochMillis
                        ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
            )
        }
    val jointGraceDeadlineLabel =
        remember(billingDirectoryProjections, conversationDirectoryLookupIds) {
            (listOfNotNull(payload.parentChannelId) + conversationDirectoryLookupIds)
                .firstNotNullOfOrNull { id ->
                    billingDirectoryProjections[id]
                        ?.takeIf { it.conversationKind == ConversationKind.Joint }
                        ?.jointOverLimitGraceEndsAt
                }
        }
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
    // §18.8: flip Grace -> Locked locally at the deadline without waiting for a
    // new server snapshot. Recompute `jointBillingNotice` via a state bump.
    LaunchedEffect(jointGraceDeadlineLabel, payload.instanceId) {
        val deadline =
            jointGraceDeadlineLabel?.let { parseRaftTimestampEpochMillisOrNull(it, 0) }
        if (deadline != null) {
            val remaining =
                deadline -
                    (payload.nowEpochMillis
                        ?: kotlin.time.Clock.System.now().toEpochMilliseconds())
            if (remaining > 0) {
                delay(remaining)
                jointBillingDeadlineTick++
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
    // glyph needs no server fact: it is not gated by external_channels_v1 (web plan §2a).
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
    var threadFirstContentRevealed by remember(threadInstanceKey) { mutableStateOf(false) }
    val threadPageInMotion = LocalRaftStackPageInMotion.current
    val holdFirstContentReveal =
        threadHoldsFirstContentReveal(
            contentRevealed = threadFirstContentRevealed,
            accessResolution = renderSlice.accessResolution,
            initialHomeAccessLookupComplete = renderSlice.initialHomeAccessLookupComplete,
            pageInMotion = threadPageInMotion
        )
    if (!holdFirstContentReveal && !threadFirstContentRevealed) {
        SideEffect { threadFirstContentRevealed = true }
    }
    // Diagnostics: content was ready mid slide and its first reveal waits for the push to settle.
    val revealWaitsForPush =
        holdFirstContentReveal &&
            !threadHoldsFirstContentReveal(
                contentRevealed = threadFirstContentRevealed,
                accessResolution = renderSlice.accessResolution,
                initialHomeAccessLookupComplete = renderSlice.initialHomeAccessLookupComplete
            ) &&
            !payload.shouldRenderThreadLoadingShell(timelineSlice, renderSlice.accessResolution)
    LaunchedEffect(threadInstanceKey, revealWaitsForPush) {
        if (revealWaitsForPush) {
            bridge.log(
                "thread_first_reveal_waits_for_push threadId=${(threadTimelineKey ?: threadInstanceKey).orEmpty()} " +
                    "messages=${timelineSlice.messages.size}"
            )
        }
    }
    val routeMountPlan =
        payload.threadRouteMountPlan(
            timelineSlice = timelineSlice,
            accessResolution = renderSlice.accessResolution,
            holdFirstContentReveal = holdFirstContentReveal
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
    LaunchedEffect(sharedStores.activityUnreadSummaryStore, currentSession?.currentServerId) {
        val serverId = currentSession?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        sharedStores.activityUnreadSummaryStore.ensure(ServerId(serverId), reason = "thread_route_attach")
    }
    val latestCurrentSession by rememberUpdatedState(currentSession)
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
            threadVisibleResumeGate.onRouteBecameVisible()
                ?.let {
                    bridge.log(
                        "thread_lifecycle_resume_drained refresh=${it.refreshVisibleConversation} " +
                            "rejoin=${it.rejoinVisibleRooms} shell=${storeScope.shellInstanceId}"
                    )
                    applyThreadVisibleResumePlan(it)
                }
        }
    }

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
                            threadVisibleResumeGate.onLifecycleVisibleResume(latestRouteActive)
                        if (resumePlan == null) {
                            bridge.log(
                                "thread_lifecycle_resume_latched event=$event " +
                                    "routeActive=$latestRouteActive shell=${storeScope.shellInstanceId}"
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
        ThreadDeferredLoadingRouteContent(
            scaffold = movableThreadRouteScaffold,
            payload = payload,
            displayModel = headerDisplayModel,
            memberAvatarResolver = memberAvatarResolver,
            onBack = onBack
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
    val foregroundMessageBanner by foregroundBannerStore.banner.collectAsState()
    val foregroundBannerRouteOwnerId = "thread:$initialThreadInstanceKey"
    DisposableEffect(active, payloadRouteIdentity, foregroundBannerStore) {
        if (active) {
            foregroundBannerStore.setVisibleRoute(foregroundBannerRouteOwnerId, payloadRouteIdentity)
        }
        onDispose { foregroundBannerStore.clearVisibleRoute(foregroundBannerRouteOwnerId) }
    }
    var pendingReactionKeys by remember(threadInstanceKey) {
        mutableStateOf<Set<ThreadReactionPendingKey>>(emptySet())
    }
    var pendingReactionVisuals by remember(threadInstanceKey) {
        mutableStateOf<Map<ThreadReactionPendingKey, ThreadReactionPendingVisual>>(emptyMap())
    }
    val pendingActionCardIdsState = remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingActionCardIds by pendingActionCardIdsState
    var pendingTaskConvertMessageIds by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    // Task v2: "Create Task from Message" (the message it is open for, its create in flight, its failure).
    val createTaskFromMessage = remember(threadInstanceKey) { TaskCreateFromMessageFlow() }
    // Task v2: a task opened without a page of its own (its empty conversation, or a legacy panel).
    val taskOpenSurfaceFlow = remember(threadInstanceKey) { TaskOpenSurfaceFlow(foregroundBannerStore.taskPendingDrafts) }
    TaskOpenSurfaceIncomingNavigationEffect(taskOpenSurfaceFlow, foregroundBannerStore.incomingNavigation)
    val taskOpenGate = remember(threadInstanceKey) { TaskOpenGate() }
    var pendingThreadFollowMessageIds by remember(threadInstanceKey) { mutableStateOf<Set<String>>(emptySet()) }
    // Reference-chip opens are single-flight in their store (ignored repeat taps). The busy chip is
    // the reference resolver's fetch phase: a local hit never shows it, a fetch shows it throughout.
    val markdownRefOpenStore =
        remember(threadInstanceKey, dataSource) {
            ThreadMarkdownRefOpenStore(coroutineScope, fetches = dataSource.referenceResolver.inFlight)
        }
    val markdownReferencePending by markdownRefOpenStore.state.collectAsState()
    fun beginMarkdownReferencePending(actionId: String?): ThreadMarkdownReferencePendingToken? =
        markdownRefOpenStore.begin(actionId)
    fun finishMarkdownReferencePending(token: ThreadMarkdownReferencePendingToken?) {
        markdownRefOpenStore.finish(token)
    }
    val textSelectionMessageState = remember(threadInstanceKey) { mutableStateOf<RaftThreadMessageItem?>(null) }
    var textSelectionMessage by textSelectionMessageState
    val createAgentDialogStateHolder = remember(threadInstanceKey) { mutableStateOf<ThreadCreateAgentActionDialogState?>(null) }
    var createAgentDialogState by createAgentDialogStateHolder
    val markdownTaskOpenRequestGate = remember(threadInstanceKey) {
        MarkdownTaskNumberOpenRequestGate()
    }
    val createAgentComputerServerIdState = remember(threadInstanceKey) { mutableStateOf<String?>(null) }
    var createAgentComputerServerId by createAgentComputerServerIdState
    val createAgentComputerServerResolvedState = remember(threadInstanceKey) { mutableStateOf(false) }
    var createAgentComputerServerResolved by createAgentComputerServerResolvedState
    LaunchedEffect(threadInstanceKey, currentSession?.currentServerId) {
        createAgentComputerServerId = currentSession?.currentServerId?.takeIf { it.isNotBlank() }
        createAgentComputerServerResolved = true
    }
    var attachmentOpenStates by remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadAttachmentOpenUiState>>(emptyMap())
    }
    var attachmentOperationTokens by remember(threadInstanceKey) {
        mutableStateOf<Map<String, AttachmentOperationToken>>(emptyMap())
    }
    LaunchedEffect(attachmentOwner?.scopeId, attachmentOwner?.generation, threadInstanceKey) {
        val currentOwner = attachmentOwner
        val hasForeign = attachmentOperationTokens.values.any { it.owner != currentOwner }
        if (hasForeign) {
            attachmentOperationTokens = emptyMap()
            attachmentOpenStates = emptyMap()
        }
    }
    var nextAttachmentDownloadGeneration by remember(threadInstanceKey) { mutableStateOf(0L) }
    var inlineVideoPreviewStates by remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadInlineVideoPreviewUiState>>(emptyMap())
    }
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
    var inlineAudioPreviewStates by remember(threadInstanceKey) {
        mutableStateOf<Map<String, ThreadInlineAudioPreviewUiState>>(emptyMap())
    }
    var attachmentUploadFilesByLocalId by remember(threadInstanceKey) {
        mutableStateOf<Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>>(emptyMap())
    }
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
    var externalShareSendRequested by remember(threadInstanceKey) { mutableStateOf(false) }
    val pendingMentionActionBatchRequestGate =
        remember(threadInstanceKey) { PendingMentionActionBatchRequestGate() }
    var externalShareExpectedAuthority by remember(threadInstanceKey) {
        mutableStateOf<build.raft.app.data.RaftExpectedAuthBinding?>(null)
    }
    var composerImageActionCounter by remember(threadInstanceKey) { mutableStateOf(0) }
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
    fun openTrackedComposerImagePreview(payload: RaftFilePreviewPayload): Boolean {
        var registeredSessionId = ""
        var registrationRejected = false
        val opened =
            pager.openRaftFilePreviewPage(
                payload,
                onEphemeralSessionRegistered = { sessionId ->
                    registeredSessionId = sessionId
                    val accepted = registerThreadComposerImagePreviewSession(
                        sessionId = sessionId,
                        tracker = composerImageEventSessionTracker,
                        abort = { rejectedSessionId ->
                            registrationRejected = true
                            // The preview opener may race page disposal between registry admission
                            // and its callback. Retire the process-owned row immediately; returning
                            // false alone is not enough if the opener has already crossed its open
                            // boundary.
                            val cleanupRefs = raftFilePreviewAbortComposerImageEventSession(rejectedSessionId)
                            if (cleanupRefs.isNotEmpty()) {
                                bridge.retireFilePreviewTemporaryFiles(
                                    sessionId = rejectedSessionId,
                                    fileRefs = cleanupRefs.toList()
                                )
                            }
                        }
                    )
                    accepted
                }
            )
        if ((registrationRejected || !opened) && registeredSessionId.isNotBlank()) {
            // The preview opener retires its registry/ledger on a failed route open; this page
            // still owns the local tracker witness and must release it as well.
            composerImageEventSessionTracker.release(registeredSessionId)
        }
        if (opened && registeredSessionId.isNotBlank() &&
            composerImageEventSessionTracker.isClosed() &&
            !composerImageEventSessionTracker.owns(registeredSessionId) &&
            !composerImageEventSessionTracker.isHandoffStarted(registeredSessionId)
        ) {
            // Disposal can close the tracker immediately after the opener callback returns but
            // before the router reports success. Do a second ownership check after the boundary
            // so a successfully opened preview can never retain an ownerless registry row.
            val cleanupRefs = raftFilePreviewAbortComposerImageEventSession(registeredSessionId)
            if (cleanupRefs.isNotEmpty()) {
                bridge.retireFilePreviewTemporaryFiles(
                    sessionId = registeredSessionId,
                    fileRefs = cleanupRefs.toList()
                )
            }
            composerImageEventSessionTracker.release(registeredSessionId)
            return false
        }
        return opened
    }
    val chatLayoutPreferenceStore =
        remember(pager) {
            KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
        }
    val labels = threadRouteLabels()
    val actionCardSuccessLabels = labels.actionCardSuccessLabels
    val actionCardFailureLabels = labels.actionCardFailureLabels
    val genericActionCardSuccessLabel = labels.genericActionCardSuccessLabel
    val genericActionCardFailureLabel = labels.genericActionCardFailureLabel
    val actionCardReconfirmFailedLabel = labels.actionCardReconfirmFailedLabel
    val composerSendKeyEnabled = chatLayoutPreferenceStore.readComposerSendKeyEnabledSync()
    // Effective "Collapse long messages": this conversation's device-local override ?: the global switch.
    val longMessageCollapseConversationSetting =
        rememberRaftLongMessageCollapseConversationSetting(
            serverId = currentSession?.currentServerId,
            conversationId = payload.longMessageCollapseConversationId()
        )
    val longMessageCollapseEnabled = longMessageCollapseConversationSetting.effective
    val newMessageMenuEnabled = chatLayoutPreferenceStore.readNewMessageMenuEnabledSync()
    val commentsUnavailableLabel = labels.commentsUnavailableLabel
    val unableToSendCommentLabel = labels.unableToSendCommentLabel
    val unableToResolveCommentLabel = labels.unableToResolveCommentLabel
    val unableToOpenAttachmentLabel = labels.unableToOpenAttachmentLabel
    val unableToDownloadAttachmentLabel = labels.unableToDownloadAttachmentLabel
    val attachmentOpenResultTerms = labels.attachmentOpenResultTerms
    val attachmentSavedToDownloadsLabel = labels.attachmentSavedToDownloadsLabel
    val attachmentSavedToFilesLabel = labels.attachmentSavedToFilesLabel
    val attachmentSavedToPhotosLabel = labels.attachmentSavedToPhotosLabel
    val attachmentDownloadedLabel = labels.attachmentDownloadedLabel
    val attachmentSavedLabel =
        raftFilePreviewSavedMessage(
            exportPrivateDownload = false,
            toPhotos = false,
            supportsPublicDownloadReadback = raftPlatformSupportsPublicAttachmentDownloadReadback(),
            supportsPrivateAttachmentExport = raftPlatformSupportsPrivateAttachmentExport(),
            downloadSaved = attachmentSavedToDownloadsLabel,
            privateDownloadSaved = attachmentDownloadedLabel,
            fileExportSaved = attachmentSavedToFilesLabel,
            imageSavedToPhotos = attachmentSavedToPhotosLabel
        )
    val attachmentUploadFailedLabel = labels.attachmentUploadFailedLabel
    val preparingAttachmentPreviewLabel = labels.preparingAttachmentPreviewLabel
    val attachmentUploadFailedDetailTemplate = labels.attachmentUploadFailedDetailTemplate
    val attachmentLimitUnavailableLabel = labels.attachmentLimitUnavailableLabel
    val attachmentTooLargeTemplate = labels.attachmentTooLargeTemplate
    val unableToOpenRepliesLabel = labels.unableToOpenRepliesLabel
    val unableToOpenParentMessageLabel = labels.unableToOpenParentMessageLabel
    val unableToOpenTaskLabel = labels.unableToOpenTaskLabel
    val unableToOpenTargetTemplate = labels.unableToOpenTargetTemplate
    val unableToOpenTaskNumberTemplate = labels.unableToOpenTaskNumberTemplate
    val referenceNotFoundTemplate = labels.referenceNotFoundTemplate
    val referenceNoAccessTemplate = labels.referenceNoAccessTemplate
    val referenceOtherServerTemplate = labels.referenceOtherServerTemplate
    val genericThreadErrorLabel = labels.genericThreadErrorLabel
    val pendingMentionActionBatchStrings = labels.pendingMentionActionBatchStrings
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    val attachmentPickerUnavailableLabel = labels.attachmentPickerUnavailableLabel
    val fileSourceUnavailableLabel = labels.fileSourceUnavailableLabel
    val copiedLabel = labels.copiedLabel
    val failureDetailsCopiedLabel = labels.failureDetailsCopiedLabel
    val linksCopiedLabel = labels.linksCopiedLabel
    val forwardCompletedTemplate = labels.forwardCompletedTemplate
    val forwardJoinFirstTemplate = labels.forwardJoinFirstTemplate
    val unableToCopyLinkLabel = labels.unableToCopyLinkLabel
    val noTextToSelectLabel = labels.noTextToSelectLabel
    val followedThreadLabel = labels.followedThreadLabel
    val unableToFollowThreadLabel = labels.unableToFollowThreadLabel
    val unfollowedThreadLabel = labels.unfollowedThreadLabel
    val unableToUnfollowThreadLabel = labels.unableToUnfollowThreadLabel
    val searchUnavailableLabel = labels.searchUnavailableLabel
    val actionUnavailableLabel = labels.actionUnavailableLabel
    val unableToUpdateReactionLabel = labels.unableToUpdateReactionLabel
    val commentSourceUnavailableLabel = labels.commentSourceUnavailableLabel
    val memberNotFoundTemplate = labels.memberNotFoundTemplate
    val memberAddedLabel = labels.memberAddedLabel
    val unableToAddMemberLabel = labels.unableToAddMemberLabel
    val memberRemovedLabel = labels.memberRemovedLabel
    val unableToRemoveMemberLabel = labels.unableToRemoveMemberLabel
    val channelArchivedLabel = labels.channelArchivedLabel
    val unableToArchiveChannelLabel = labels.unableToArchiveChannelLabel
    val channelDeletedLabel = labels.channelDeletedLabel
    val unableToDeleteChannelLabel = labels.unableToDeleteChannelLabel
    val unableToConvertMessageLabel = labels.unableToConvertMessageLabel
    val taskCreatedLabel = labels.taskCreatedLabel
    val unableToCreateTaskLabel = labels.unableToCreateTaskLabel
    val unableToSendTaskMessageLabel = labels.unableToSendTaskMessageLabel
    val unsupportedTaskStatusLabel = labels.unsupportedTaskStatusLabel
    val unableToUpdateTaskLabel = labels.unableToUpdateTaskLabel
    val unableToUpdateAssigneeLabel = labels.unableToUpdateAssigneeLabel
    val unableToJoinChannelLabel = labels.unableToJoinChannelLabel
    val joinedChannelLabel = labels.joinedChannelLabel
    val attachmentRetryUnavailableLabel = labels.attachmentRetryUnavailableLabel
    val mergedForwardModeLabel = labels.mergedForwardModeLabel
    val individualForwardModeLabel = labels.individualForwardModeLabel
    val imageSavedLabel = labels.imageSavedLabel
    val unableToExportImageLabel = labels.unableToExportImageLabel
    val unableToForwardLabel = labels.unableToForwardLabel
    val unableToSaveMessageLabel = labels.unableToSaveMessageLabel
    val unableToRemoveSavedMessageLabel = labels.unableToRemoveSavedMessageLabel
    val attachmentSkippedOneLabel = labels.attachmentSkippedOneLabel
    val dropFoldersCantUploadLabel = labels.dropFoldersCantUploadLabel
    val dropFoldersSkippedLabel = labels.dropFoldersSkippedLabel
    val attachmentSkippedManyTemplate = labels.attachmentSkippedManyTemplate
    fun localizedAttachmentUploadFailedDetail(detail: String): String =
        attachmentUploadFailedDetailTemplate.replace(ThreadLocalizedFeedbackPlaceholder, detail)
    fun localizedAttachmentTooLarge(fileSize: Long, maxBytes: Long): String =
        attachmentTooLargeTemplate
            .replace(ThreadLocalizedFeedbackPlaceholder, RaftFormat.bytes(fileSize))
            .replace("__RAFT_ATTACHMENT_LIMIT__", RaftFormat.byteLimit(maxBytes))
    val unableToOpenThreadRefLabel = labels.unableToOpenThreadRefLabel
    fun localizedUnableToOpenTarget(target: String): String =
        unableToOpenTargetTemplate.replace(ThreadLocalizedFeedbackPlaceholder, target)
    fun localizedUnableToOpenTaskNumber(taskNumber: Int): String =
        unableToOpenTaskNumberTemplate.replace(ThreadLocalizedFeedbackPlaceholder, taskNumber.toString())
    /** The toast of a reference that did not open, naming why ([fallback] when no reason applies). */
    fun localizedReferenceFailure(failure: SharedReferenceFailure, target: String, fallback: String): String =
        when (failure) {
            SharedReferenceFailure.NotFound -> referenceNotFoundTemplate
            SharedReferenceFailure.NoAccess -> referenceNoAccessTemplate
            SharedReferenceFailure.OtherServer -> referenceOtherServerTemplate
            SharedReferenceFailure.AuthRequired,
            SharedReferenceFailure.Unavailable -> null
        }?.replace(ThreadLocalizedFeedbackPlaceholder, target) ?: fallback
    fun localizedForwardModeLabel(mode: String?): String =
        if (mode?.trim()?.lowercase() == ThreadForwardSelectionMode.Individual.wireValue) {
            individualForwardModeLabel
        } else {
            mergedForwardModeLabel
        }
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
    val attachmentCommentsDialogStateHolder = remember(threadInstanceKey) {
        mutableStateOf<ThreadAttachmentCommentsDialogState?>(null)
    }
    var attachmentCommentsDialogState by attachmentCommentsDialogStateHolder
    LaunchedEffect(renderSlice.writeActionsEnabled) {
        attachmentCommentsDialogState =
            attachmentCommentsDialogState.retainForThreadWriteAuthority(renderSlice.writeActionsEnabled)
    }
    var firstContentLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var performanceSnapshotLoggedThreadId by remember { mutableStateOf<String?>(null) }

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
            val payloadReadyMs = threadOpenStartedAt?.elapsedNow()?.inWholeMilliseconds
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
    // The channel a bare `#N` chips and opens against (Web `taskContextChannelId`): the task's host
    // channel in a task conversation, and never gated on write access (unlike [taskChannelId]).
    val markdownTaskContextChannelId =
        payload.markdownTaskContextChannelId(taskConversationParentChannelId?.value)?.let(::ChannelId)
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
    LaunchedEffect(taskConversationHeaderState?.removed == true) {
        if (taskConversationHeaderState?.removed == true) {
            // Web closes the task dialog when its task is deleted; the page says why, then closes.
            bridge.log("task_conversation_removed conversationId=${taskConversationId?.value.orEmpty()}")
            bridge.toast(taskConversationDeletedLabel)
            onBack()
        }
    }
    LaunchedEffect(taskConversationHeaderViewModel) {
        taskConversationHeaderViewModel?.events?.collect { event ->
            when (event) {
                is TaskConversationHeaderEvent.FollowFailed -> bridge.toast(taskConversationFollowFailedLabel)
            }
        }
    }
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
    val currentServerId = currentSession?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
    // Task v2 (web TaskConversationDialog head): the task's history, its source message and a pre-v2
    // task's earlier discussion, read for the task the head shows (again for each newer revision).
    val taskConversationDetailsServerId by rememberUpdatedState(currentServerId)
    val taskConversationDetailsRepository =
        remember(sharedStores, storeScope) { TaskConversationDetailsRepository(sharedStores.networkClient(storeScope)) }
    val taskConversationDetailsViewModel: TaskConversationDetailsViewModel? =
        taskConversationId?.let { conversationId ->
            viewModel(key = "task-conversation-details-${conversationId.value}") {
                fun server(): ServerId? = taskConversationDetailsServerId?.let(::ServerId)
                val noServer = DataResult.Error(ScreenError(code = "task_head_no_server", retryable = true))
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
    val featureFlagSnapshotRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
    // `feedback:submit` cards open the native feedback form on their draft once the viewing server is
    // known (every platform, 1.24; no server flag). A done card's "View in Settings" opens the native
    // ticket page.
    val feedbackCardEntryEnabled = raftFeedbackCardEntryEnabled(currentServerId)
    // Task v2 (`task_v2_conversations_v0`, as web reads it per server): every task lists under its
    // source message and the composer drops "As Task".
    // The session is captured a few frames after the page composes; until then the shell's server
    // decides, or the first frame laid out the old Chat / Tasks / Files tab row and the list jumped
    // when it went away (Compose 版 open jitter).
    val taskV2FlagServerId = threadPageServerId(currentServerId, storeScope.initialServerId)
    val taskV2Enabled =
        remember(featureFlagSnapshotRevision, taskV2FlagServerId) {
            taskV2FlagServerId != null &&
                sharedStores.serverFeatureFlags.isEnabled(
                    ServerId(taskV2FlagServerId),
                    TASK_V2_CONVERSATIONS_FEATURE_FLAG_KEY
                )
        }
    // Task v2: Tasks and Files are cards opened from the header, not tabs (ConversationSideCards.kt).
    val sideCardsEnabled =
        conversationSideCardsEnabled(
            taskV2Enabled = taskV2Enabled,
            showModeTabs = payload.showModeTabs,
            assistantPanelEmbed = LocalThreadAssistantPanelEmbed.current != null
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
    val attachmentUploadAccountId = currentSession?.user?.id?.trim()?.takeIf(String::isNotBlank)
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
        if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.currentChannelMemberSlice(threadMembersServerId, threadMembersChannelId)
        } else {
            SharedServerChannelMembersSlice(
                serverId = threadMembersServerId?.value,
                channelId = threadMembersChannelId?.value.orEmpty()
            )
        }
    )
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
    }.collectAsState(initialTaskProjectionSlice)
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
    }.collectAsState(initialServerTaskProjectionSlice)
    var refreshedTaskProjectionChannelId by remember(threadInstanceKey, currentServerId) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(
        currentServerId,
        taskChannelId?.value,
        selectedThreadTab,
        payload.status,
        refreshedTaskProjectionChannelId
    ) {
        val serverId = currentServerId ?: return@LaunchedEffect
        val resolvedTaskChannelId = taskChannelId ?: return@LaunchedEffect
        val sharedStore = tasksDataStore ?: return@LaunchedEffect
        val currentPayload = payload.copy(selectedTab = selectedThreadTab.wireValue)
        if (
            !shouldRefreshThreadTasksProjection(
                payload = currentPayload,
                taskChannelId = resolvedTaskChannelId,
                refreshStarted = refreshedTaskProjectionChannelId == resolvedTaskChannelId.value
            )
        ) {
            return@LaunchedEffect
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
    }.collectAsState(SharedTasksProjectionSlice())
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
            taskV2Enabled,
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
                        taskProjectionSlice.tasks.threadSourceTaskLists(taskV2Enabled)
                    } else {
                        emptyMap()
                    },
                viewerUserId = currentSession?.user?.id,
                taskV2Enabled = taskV2Enabled
            )
        }
    val taskAssigneeOptions =
        taskAssigneeIdentityOptions(
            channelMembers = threadChannelMembersSlice.members,
            channelMembersLoaded =
                threadChannelMembersSlice.status == SharedServerMembersLoadStatus.Loaded,
            payloadMembers = threadMembersProjection.members.mapNotNull { it.toMemberProfile() }
        )
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
    val renderedThread = renderedPayload.withThreadWriteActionsEnabled(renderSlice.writeActionsEnabled && jointBillingNotice != JointBillingNotice.Locked)
    val linkNavigator = rememberThreadLinkNavigator(pager, bridge, renderedThread)
    val threadViewerIsGuest = currentSession.threadViewerIsGuest(headerProjectionServerId?.value)
    val threadForwardSourceAvailable = renderedPayload.threadForwardSourceChannelId() != null
    val renderedTimelineSlice =
        timelineSlice
            .withThreadWriteActionsEnabled(renderSlice.writeActionsEnabled && jointBillingNotice != JointBillingNotice.Locked)
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

    val actionCardSpecCopiedLabel = stringResource(Res.string.common_copied)
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
                loadWebUrl = {
                    (
                        BridgeSettingsPlatformCapabilities(bridge = bridge, copiedMessage = actionCardSpecCopiedLabel)
                            .loadDeveloper() as? DataResult.Content
                    )?.data?.webUrl
                },
                openInBrowser = { url ->
                    bridge.log("thread_action_card_open_in_web")
                    bridge.hostCapabilities.openUrlInBrowser(url)
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

    LaunchedEffect(taskConversationTasksViewModel, unableToUpdateTaskLabel) {
        taskConversationTasksViewModel?.uiEvents?.collect { event ->
            when (event) {
                is SharedTasksEvent.OperationFailed -> {
                    when {
                        event.code.startsWith("tasks_status") && !routeAuthRequired(event.code) ->
                            bridge.toast(unableToUpdateTaskLabel)
                        event.code.startsWith("tasks_assign") && !routeAuthRequired(event.code) ->
                            bridge.toast(unableToUpdateAssigneeLabel)
                    }
                }
            }
        }
    }

    LaunchedEffect(tasksViewModel, unableToUpdateTaskLabel) {
        tasksViewModel?.uiEvents?.collect { event ->
            when (event) {
                is SharedTasksEvent.OperationFailed -> {
                    when {
                        event.code.startsWith("tasks_status") && !routeAuthRequired(event.code) ->
                            bridge.toast(unableToUpdateTaskLabel)
                        event.code.startsWith("tasks_assign") && !routeAuthRequired(event.code) ->
                            bridge.toast(unableToUpdateAssigneeLabel)
                    }
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
                taskConversationTasksViewModel?.applyRealtimeTaskUpdate(serverId, event.task)
                taskConversationHeaderViewModel?.onTaskSeen(event.task)
            }
            if (event is TaskDeleted) {
                taskConversationHeaderViewModel?.onTaskDeleted(event.taskId, event.conversationChannelId)
            }
            if (event is ThreadFollowersUpdated) {
                taskConversationHeaderViewModel?.onFollowersUpdated(serverId, event.threadChannelId)
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
    var inlineImageFallbackState by remember(threadInstanceKey) {
        mutableStateOf(ThreadInlineImageFallbackState())
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
            taskV2Enabled,
            assistantPanelContext
        ) {
            scopedBaseComposerState.toThreadComposerSurface(
                channelId = resolvedAuxiliaryThreadId.orEmpty(),
                parentChannelId = renderedPayload.parentChannelId,
                mentionChannelLabel = headerDisplayModel.threadComposerMentionScopeChannelLabel(),
                memberSuggestionHeadersVisible = renderedPayload.threadComposerMemberSuggestionHeadersVisible()
            ).withTaskV2(taskV2Enabled).copy(assistantContext = assistantPanelContext)
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
    // Attachment comments are on for every server (web, end of June); the server still decides who may comment.
    val attachmentCommentsEnabled = true
    fun threadWritesEnabledNow(): Boolean = threadViewModel.state.value.writeActionsEnabled

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
                            resolvingCommentIds = emptySet()
                        ).clearAttachmentCommentsError()
                    is DataResult.Empty ->
                        state.copy(
                            status = "loaded",
                            page = ThreadAttachmentCommentsPage(comments = emptyList(), threadChannelId = null, viewer = null),
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).clearAttachmentCommentsError()
                    is DataResult.Error ->
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).withAttachmentCommentsError(
                            errorCode = result.error.code,
                            serverMessage = result.error.serverMessage
                        )
                    is DataResult.NoPermission -> {
                        if (routeAuthRequired(result)) return@launch
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).withAttachmentCommentsError(
                            errorCode = result.blocker.code,
                            serverMessage = null
                        )
                    }
                }
            setAttachmentCommentsState(nextState)
        }
    }

    fun openAttachmentComments(attachmentId: String) {
        if (!threadWritesEnabledNow() || !attachmentCommentsEnabled) return
        val target = renderedTimelineSlice.findAttachmentCommentTarget(attachmentId)
        if (target == null) {
            bridge.toast(commentsUnavailableLabel)
            return
        }
        if (!target.attachment.supportsThreadAttachmentComments()) return
        target.attachment.toThreadAttachmentCommentsPreviewPayload(attachmentCommentsEnabled)?.let { previewPayload ->
            pager.openRaftFilePreviewPage(previewPayload)
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
        val state = current.copy(status = "loading").clearAttachmentCommentsError()
        attachmentCommentsDialogState = state
        loadAttachmentCommentsIntoDialog(state)
    }

    fun sendAttachmentComment() {
        if (!threadWritesEnabledNow()) {
            attachmentCommentsDialogState = null
            return
        }
        val current = attachmentCommentsDialogState ?: return
        val cleanDraft = current.draft.text.trim()
        if (cleanDraft.isBlank() || current.sending) return
        attachmentCommentsDialogState = current.copy(sending = true).clearAttachmentCommentsError()
        coroutineScope.launch {
            when (
                val result =
                    runThreadWriteActionIfEnabled(
                        writeActionsEnabled = ::threadWritesEnabledNow,
                        blockedCode = "thread_attachment_comment_write_disabled"
                    ) {
                        dataSource.sendAttachmentComment(current.attachment.id, cleanDraft)
                    }
            ) {
                is DataResult.Content -> {
                    val loadingState =
                        current.withAttachmentCommentDraftProjection("").copy(
                            status = "loading",
                            sending = false,
                            pendingQuoteLabel = null
                        ).clearAttachmentCommentsError()
                    attachmentCommentsDialogState = loadingState
                    loadAttachmentCommentsIntoDialog(loadingState)
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    if ((result as? DataResult.Error)?.error?.code == "thread_attachment_comment_write_disabled") {
                        attachmentCommentsDialogState = null
                        return@launch
                    }
                    val code = (result as? DataResult.Error)?.error?.code ?: "thread_attachment_comment_send_failed"
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false)
                            ?.withAttachmentCommentsError(
                                errorCode = code,
                                serverMessage = (result as? DataResult.Error)?.error?.serverMessage
                            )
                    bridge.toast(unableToSendCommentLabel)
                }
                is DataResult.NoPermission -> {
                    if (routeAuthRequired(result)) return@launch
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false)
                            ?.withAttachmentCommentsError(
                                errorCode = result.blocker.code,
                                serverMessage = null
                            )
                    bridge.toast(unableToSendCommentLabel)
                }
            }
        }
    }

    fun resolveAttachmentComment(comment: ThreadAttachmentCommentItem) {
        if (!threadWritesEnabledNow()) {
            attachmentCommentsDialogState = null
            return
        }
        val current = attachmentCommentsDialogState ?: return
        if (comment.id.isBlank() || comment.isThreadAttachmentCommentResolved()) return
        if (comment.id in current.resolvingCommentIds) return
        attachmentCommentsDialogState =
            current.copy(resolvingCommentIds = current.resolvingCommentIds + comment.id)
                .clearAttachmentCommentsError()
        coroutineScope.launch {
            when (
                val result =
                    runThreadWriteActionIfEnabled(
                        writeActionsEnabled = ::threadWritesEnabledNow,
                        blockedCode = "thread_attachment_comment_write_disabled"
                    ) {
                        dataSource.setMessageReaction(comment.id, "✅", selected = true)
                    }
            ) {
                is DataResult.Content -> {
                    val loadingState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(status = "loading", resolvingCommentIds = emptySet())
                            ?.clearAttachmentCommentsError()
                            ?: return@launch
                    attachmentCommentsDialogState = loadingState
                    loadAttachmentCommentsIntoDialog(loadingState)
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    if ((result as? DataResult.Error)?.error?.code == "thread_attachment_comment_write_disabled") {
                        attachmentCommentsDialogState = null
                        return@launch
                    }
                    val code = (result as? DataResult.Error)?.error?.code ?: "thread_attachment_comment_resolve_failed"
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.let { state ->
                                state.copy(
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id
                                ).withAttachmentCommentsError(
                                    errorCode = code,
                                    serverMessage = (result as? DataResult.Error)?.error?.serverMessage
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
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id
                                ).withAttachmentCommentsError(
                                    errorCode = result.blocker.code,
                                    serverMessage = null
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
    // The desktop / wide image lightbox (RaftImageLightbox.kt): open while non-null.
    val imageLightboxWindowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    val imageLightboxEnabled = raftImageLightboxEnabled(raftPlatformHasFinePointer(), imageLightboxWindowWidthDp)
    var imageLightboxRequest by remember(threadInstanceKey) { mutableStateOf<RaftImageLightboxRequest?>(null) }
    // The image being edited from the image viewer (RaftViewerImageEdit.kt); null: none.
    var viewerImageEditAttachment by remember(threadInstanceKey) { mutableStateOf<RaftThreadAttachmentItem?>(null) }
    fun requestInlineImageFallback(attachmentId: String) {
        val (nextState, shouldRequest) = inlineImageFallbackState.beginRequest(attachmentId)
        inlineImageFallbackState = nextState
        if (!shouldRequest) return
        bridge.log("thread_inline_image_fallback status=start attachmentId=${attachmentId.take(8)}")
        coroutineScope.launch {
            when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline")) {
                is ThreadApiResult.Success -> {
                    inlineImageFallbackState =
                        inlineImageFallbackState.resolve(attachmentId, response.data.url)
                    bridge.log("thread_inline_image_fallback status=resolved attachmentId=${attachmentId.take(8)}")
                }
                is ThreadApiResult.Failure -> {
                    bridge.log(
                        "thread_inline_image_fallback status=failed attachmentId=${attachmentId.take(8)} " +
                            "code=${response.code} contractCode=${response.contractCode.orEmpty()}"
                    )
                }
            }
        }
    }
    /**
     * Desktop and wide windows open images in the lightbox over the app (artin 10-29); phones
     * keep the full-page viewer. [conversationWide]: step through every loaded image of the
     * conversation (a click in the timeline) rather than only [group] (the Files tab's list).
     */
    fun openImagePreview(group: ThreadImageAttachmentGroup, conversationWide: Boolean = true) {
        if (group.images.isEmpty()) return
        val currentIndex = group.currentIndex.coerceIn(0, group.images.lastIndex)
        val attachment = group.images[currentIndex]
        if (imageLightboxEnabled) {
            imageLightboxRequest =
                (if (conversationWide) {
                    raftImageLightboxRequestFor(
                        messages = listOfNotNull(renderedTimelineSlice.originMessage) + renderedTimelineSlice.messages,
                        attachmentId = attachment.id,
                        isImage = { it.isImageAttachment() }
                    )
                } else {
                    null
                }) ?: RaftImageLightboxRequest(group.images.map { RaftImageLightboxItem(it) }, currentIndex)
            bridge.log("thread_image_lightbox status=open count=${imageLightboxRequest?.items?.size ?: 0}")
            return
        }
        RaftViewerImageEditOpener.threadKey = threadInstanceKey
        pager.openRaftFilePreviewPage(
            attachment.toThreadAttachmentLazyPreviewPayload(
                kind = "image",
                imageGallery = group.images,
                imageGalleryIndex = currentIndex,
                attachmentCommentsEnabled = attachmentCommentsEnabled
            )
        )
    }
    fun openAttachmentExternally(attachment: RaftThreadAttachmentItem) {
        if (attachment.id.isBlank() || attachmentOpenStates[attachment.id].threadAttachmentIsOpening()) return
        val owner = attachmentOwner ?: return
        nextAttachmentDownloadGeneration += 1L
        val downloadId = attachment.threadAttachmentDownloadId(nextAttachmentDownloadGeneration)
        val operation = AttachmentOperationToken(owner, downloadId, downloadId)
        attachmentOperationTokens = attachmentOperationTokens + (attachment.id to operation)
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(
                    status = "downloading",
                    downloadId = downloadId,
                    progress = RaftFilePreviewInitialDownloadProgress.copy(downloadId = downloadId)
                ))
        coroutineScope.launch {
            val result =
                openSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment,
                    owner = owner,
                    downloadId = downloadId,
                    onProgress = { progress ->
                        val token = attachmentOperationTokens[attachment.id]
                        if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) {
                            return@openSharedThreadAttachment
                        }
                        val current = attachmentOpenStates[attachment.id]
                        if (current?.downloadId != downloadId) return@openSharedThreadAttachment
                        val reduced =
                            reduceAttachmentDownloadProgress(
                                current = current.progress,
                                incoming = progress,
                                expectedDownloadId = downloadId
                            )
                        if (reduced === current.progress) return@openSharedThreadAttachment
                        attachmentOpenStates =
                            attachmentOpenStates +
                                (attachment.id to current.copy(
                                    status = if (reduced?.status == "paused") "paused" else "downloading",
                                    progress = reduced
                                ))
                    }
                )
            val token = attachmentOperationTokens[attachment.id]
            if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) return@launch
            if (result.status == "opened" || result.status == "save_cancelled") {
                attachmentOpenStates = attachmentOpenStates - attachment.id
                attachmentOperationTokens = attachmentOperationTokens - attachment.id
            } else {
                attachmentOpenStates =
                    attachmentOpenStates +
                        (attachment.id to ThreadAttachmentOpenUiState(
                            status = result.status,
                            downloadId = downloadId
                        ))
                if (result.status != "cancelled") {
                    bridge.toast(
                        raftAttachmentOpenResultMessage(result.status, attachmentOpenResultTerms)
                            ?: unableToOpenAttachmentLabel
                    )
                }
                attachmentOperationTokens = attachmentOperationTokens - attachment.id
            }
        }
    }
    fun saveAttachmentExternally(attachment: RaftThreadAttachmentItem) {
        if (attachment.id.isBlank() || attachmentOpenStates[attachment.id].threadAttachmentIsOpening()) return
        val owner = attachmentOwner ?: return
        nextAttachmentDownloadGeneration += 1L
        val downloadId = attachment.threadAttachmentDownloadId(nextAttachmentDownloadGeneration)
        val operation = AttachmentOperationToken(owner, downloadId, downloadId)
        attachmentOperationTokens = attachmentOperationTokens + (attachment.id to operation)
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(
                    status = "downloading",
                    downloadId = downloadId,
                    progress = RaftFilePreviewInitialDownloadProgress.copy(downloadId = downloadId)
                ))
        coroutineScope.launch {
            val result =
                saveSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment,
                    owner = owner,
                    downloadId = downloadId,
                    onProgress = { progress ->
                        val token = attachmentOperationTokens[attachment.id]
                        if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) {
                            return@saveSharedThreadAttachment
                        }
                        val current = attachmentOpenStates[attachment.id]
                        if (current?.downloadId != downloadId) return@saveSharedThreadAttachment
                        val reduced =
                            reduceAttachmentDownloadProgress(
                                current = current.progress,
                                incoming = progress,
                                expectedDownloadId = downloadId
                            )
                        if (reduced === current.progress) return@saveSharedThreadAttachment
                        attachmentOpenStates =
                            attachmentOpenStates +
                                (attachment.id to current.copy(
                                    status = if (reduced?.status == "paused") "paused" else "downloading",
                                    progress = reduced
                                ))
                    }
                )
            val token = attachmentOperationTokens[attachment.id]
            if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) return@launch
            when (result.status) {
                "saved" -> {
                    attachmentOpenStates = attachmentOpenStates - attachment.id
                    attachmentOperationTokens = attachmentOperationTokens - attachment.id
                    bridge.toast(
                        raftThreadAttachmentSavedMessage(
                            nativeMessage = attachmentSavedLabel,
                            fallbackMessage = attachmentDownloadedLabel
                        )
                    )
                }
                "cancelled" -> {
                    attachmentOpenStates = attachmentOpenStates - attachment.id
                    attachmentOperationTokens = attachmentOperationTokens - attachment.id
                }
                else -> {
                    attachmentOpenStates =
                        attachmentOpenStates +
                            (attachment.id to ThreadAttachmentOpenUiState(
                                status = result.status,
                                downloadId = downloadId
                            ))
                    val failure =
                        raftAttachmentSaveFailure(result.status, RaftAttachmentSaveDestination.Downloads)
                            ?: RaftAttachmentSaveFailure.UnableToDownload
                    bridge.log(
                        "thread_attachment_save_failed attachment=${attachment.id.take(8)} " +
                            "status=${raftAttachmentOpenDiagnosticStatus(result.status)} " +
                            "errorKind=${raftAttachmentOpenDiagnosticErrorKind(result.errorKind)} " +
                            "failure=${failure.name} message=${raftAttachmentHostMessageForLog(result.message)}"
                    )
                    bridge.toast(
                        when (failure) {
                            RaftAttachmentSaveFailure.DownloadFailed -> attachmentOpenResultTerms.downloadFailed
                            RaftAttachmentSaveFailure.StorageUnavailable -> attachmentOpenResultTerms.storageUnavailable
                            else -> unableToDownloadAttachmentLabel
                        }
                    )
                    attachmentOperationTokens = attachmentOperationTokens - attachment.id
                }
            }
        }
    }
    /** True when the file page opened. */
    fun openAttachmentNoPreview(attachment: RaftThreadAttachmentItem): Boolean {
        if (attachment.id.isBlank()) return false
        // A tap never downloads in the conversation: previewable files open their preview, any
        // other file its file page, where Download shows progress (task #75).
        return pager.openRaftFilePreviewPage(
            attachment.toThreadAttachmentLazyPreviewPayload(
                kind = attachment.threadAttachmentOpenRoute().requestedPreviewKind,
                attachmentCommentsEnabled = attachmentCommentsEnabled
            )
        )
    }
    imageLightboxRequest?.let { request ->
        RaftImageLightbox(
            request = request,
            resolveFullUrl = { attachmentId ->
                when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline")) {
                    is ThreadApiResult.Success -> response.data.url
                    is ThreadApiResult.Failure -> {
                        bridge.log("thread_image_lightbox status=url_failed attachmentId=${attachmentId.take(8)} code=${response.code}")
                        null
                    }
                }
            },
            onDismiss = { imageLightboxRequest = null },
            onDownload = { attachment -> saveAttachmentExternally(attachment) },
            onOpenWith = { attachment -> openAttachmentExternally(attachment) },
            onEdit = { attachment -> viewerImageEditAttachment = attachment }
        )
    }
    fun requestInlineAudioPreview(attachmentId: String, force: Boolean = false) {
        if (attachmentId.isBlank()) return
        if (inlineAudioPreviewStates[attachmentId]?.status == ThreadInlineAudioPreviewStatus.Loading) return
        if (!force && !inlineAudioPreviewStates.canRequestInlineAudioPreview(attachmentId)) return
        inlineAudioPreviewStates =
            inlineAudioPreviewStates +
                (attachmentId to ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Loading))
        coroutineScope.launch {
            when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline")) {
                is ThreadApiResult.Success -> {
                    val url = response.data.url.trim()
                    inlineAudioPreviewStates =
                        inlineAudioPreviewStates +
                            (attachmentId to
                                if (url.isNotBlank()) {
                                    ThreadInlineAudioPreviewUiState(
                                        status = ThreadInlineAudioPreviewStatus.Ready,
                                        url = url,
                                        expiresAt = response.data.expiresAt
                                    )
                                } else {
                                    ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Error)
                                })
                }
                is ThreadApiResult.Failure -> {
                    inlineAudioPreviewStates =
                        inlineAudioPreviewStates +
                            (attachmentId to ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Error))
                    if (response.code == 401) {
                        routeAuthRequired("auth_required")
                    }
                }
            }
        }
    }
    fun requestInlineVideoPreview(attachmentId: String, force: Boolean = false) {
        if (attachmentId.isBlank()) return
        val running = inlineVideoPreviewJobs[attachmentId]?.isActive == true
        if (running) {
            // A retry is waiting out its backoff: a forced request (tap, realtime update) runs it now.
            if (force) inlineVideoPreviewRetryNudges.tryEmit(attachmentId)
            return
        }
        if (!force && !inlineVideoPreviewStates.canRequestInlineVideoPreview(attachmentId)) return
        inlineVideoPreviewJobs[attachmentId] =
            coroutineScope.launch {
                val outcome =
                    runThreadInlineVideoPreviewFetch(
                        policy = ThreadInlineVideoRetryPolicy.Default,
                        fetch = {
                            dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline").toInlineVideoFetchOutcome()
                        },
                        awaitRetry = { delayMs ->
                            withTimeoutOrNull(delayMs) {
                                inlineVideoPreviewRetryNudges.first { it == attachmentId || it == null }
                            }
                        },
                        onState = { state ->
                            inlineVideoPreviewStates = inlineVideoPreviewStates + (attachmentId to state)
                        }
                    )
                if (outcome is ThreadInlineVideoFetchOutcome.Failed && outcome.code == 401) {
                    routeAuthRequired("auth_required")
                }
            }
    }

    /** True when a page (the preview, the image editor) opened over the conversation. */
    fun openComposerAttachmentPreview(attachmentId: String): Boolean {
        val composerPayload = currentComposerPayload()
        val attachment = composerPayload.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return false
        val currentDraftState = composerDraftViewModel.state.value
        val name = attachment.fileName.ifBlank { "Attachment" }
        val meta = attachment.displaySizeLabel()
        if (!attachment.isReadyThreadAttachmentForSend()) {
            return pager.openRaftFilePreviewPage(
                RaftFilePreviewPayload(
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
        }
        val threadAttachment =
            RaftThreadAttachmentItem(
                id = attachment.id,
                name = name,
                mimeType = attachment.mimeType.orEmpty(),
                sizeLabel = attachment.sizeLabel,
                sizeBytes = attachment.sizeBytes,
                thumbnailUrl = attachment.previewUri
        )
        val composerImageGroup = composerPayload.pendingAttachments.composerPendingImageAttachmentGroup(attachmentId)
        // RaftPendingAttachmentItem is intentionally a render-only projection and therefore
        // does not carry the platform file handle. Resolve each gallery item back to the
        // session-owned draft/upload descriptor before opening an editable preview; otherwise
        // the gallery payload would contain only thumbnails and the Done/session registry could
        // never admit the resulting events.
        val draftAttachmentsById = currentDraftState.uploadedAttachments.associateBy { it.id }
        val currentDraftKey = normalizeThreadAttachmentUploadDraftKey(currentDraftState.draftKey)
        fun composerImageFileFor(id: String): PlatformFile? =
            attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, currentDraftKey)
                ?.takeIf { file -> !file.fileRef.isNullOrBlank() }
                ?: draftAttachmentsById[id]
                    ?.toComposerImagePlatformFile()
                    ?.takeIf { file -> !file.fileRef.isNullOrBlank() }
        return if (composerImageGroup != null && composerImageGroup.images.size > 1) {
            val galleryFiles =
                composerImageGroup.images.mapNotNull { image -> composerImageFileFor(image.id) }
            // A ready tile without a local file ref is not safely editable. Keep it as an
            // ordinary preview rather than creating a composer session whose Done action can
            // never produce a deliverable event.
            if (galleryFiles.size != composerImageGroup.images.size || galleryFiles.isEmpty()) {
                return pager.openRaftFilePreviewPage(
                    threadAttachment.toThreadAttachmentLazyPreviewPayload(
                        kind = "image",
                        imageGallery = composerImageGroup.images,
                        imageGalleryIndex = composerImageGroup.currentIndex
                    ).copy(imageUrl = attachment.previewUri?.takeIf { it.isNotBlank() })
                )
            }
            composerImageActionCounter += 1
            val baseActionId =
                "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${attachmentId.take(12)}"
            openTrackedComposerImagePreview(
                threadComposerUploadedImagePreviewPayload(
                    threadInstanceKey = threadInstanceKey,
                    actionId = baseActionId,
                    files = galleryFiles,
                    replacementAttachmentIds = composerImageGroup.images.map { it.id },
                    selectedIndex = composerImageGroup.currentIndex,
                    originalImage = currentDraftState.originalImage
                ) ?: return false
            )
        } else if (threadAttachment.isImageAttachment()) {
            composerImageActionCounter += 1
            val composerImageActionId = "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${threadAttachment.id.take(12)}"
            val composerImageFile = composerImageFileFor(attachment.id)
            // A server-ready tile can still be previewable after its local picker ref has been
            // retired.  It is not safely editable/compressible in that state, so keep it a plain
            // read-only preview instead of creating a Done session that can never emit an event.
            if (composerImageFile == null) {
                return pager.openRaftFilePreviewPage(
                    threadAttachment.toThreadAttachmentLazyPreviewPayload(
                        kind = "image"
                    ).copy(imageUrl = attachment.previewUri?.takeIf { it.isNotBlank() })
                )
            }
            openTrackedComposerImagePreview(
                threadComposerUploadedImagePreviewPayload(
                    threadInstanceKey = threadInstanceKey,
                    actionId = composerImageActionId,
                    files = listOf(composerImageFile),
                    replacementAttachmentIds = listOf(attachment.id),
                    selectedIndex = 0,
                    originalImage = currentDraftState.originalImage
                ) ?: return false
            )
        } else {
            openAttachmentNoPreview(threadAttachment)
        }
    }
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
    /**
     * A draft-owned upload failed and its row now offers Retry: the upgrade dialog when the month's
     * uploads are used up (`FILE_UPLOAD_QUOTA_EXCEEDED`, web MessageInput `upload_monthly_quota`;
     * nothing opens on a paid plan), and the failure toast.
     */
    fun showAttachmentUploadFailure(errorCode: String) {
        if (raftUpgradeGateForErrorCode(errorCode) == RaftUpgradeGate.UploadMonthlyQuota) {
            pager.openRaftUpgradeDialog(RaftUpgradeGate.UploadMonthlyQuota, storeScope)
        }
        bridge.toast(
            localizedAttachmentUploadFailedDetail(
                userFacingErrorLabels.forErrorCode(errorCode) ?: genericThreadErrorLabel
            )
        )
    }
    suspend fun admittedComposerAttachmentFiles(
        files: List<PlatformFile>,
        maxBytesOverride: Long? = null,
        skipClientSizeGate: Boolean = false,
    ): List<PlatformFile> {
        if (skipClientSizeGate) return files
        val maxBytes =
            maxBytesOverride ?: when (val capability = dataSource.attachmentUploadMaxBytes()) {
                is DataResult.Content -> capability.data
                is DataResult.NoPermission -> {
                    routeAuthRequired(capability)
                    bridge.toast(attachmentLimitUnavailableLabel)
                    return emptyList()
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    bridge.toast(attachmentLimitUnavailableLabel)
                    return emptyList()
                }
            }
        val sizeDecision = applyServerAttachmentUploadLimit(files, maxBytes)
        if (sizeDecision.rejected.isNotEmpty()) {
            val largest = sizeDecision.largestRejectedBytes ?: 0L
            bridge.toast(localizedAttachmentTooLarge(largest, maxBytes))
            // Free's per-file ceiling: the upgrade dialog too (web `upload_file_size`).
            if (raftUploadSizeLimitIsFree(maxBytes)) {
                pager.openRaftUpgradeDialog(RaftUpgradeGate.UploadFileSize, storeScope)
            }
            bridge.log(
                "thread_composer_attachment_size_rejected rejected=${sizeDecision.rejected.size} " +
                    "largestBytes=$largest maxBytes=$maxBytes"
            )
        }
        return sizeDecision.accepted
    }
    suspend fun uploadComposerAttachmentFiles(
        files: List<PlatformFile>,
        replaceAttachmentId: String? = null,
        requireAllFiles: Boolean = false,
        replaceAttachmentIdsByLocalId: Map<String, String> = emptyMap()
    ): Boolean {
        // Upload callbacks must retain the store-owned draft identity they were admitted under.
        // A route can remain mounted while the composer store transitions to another key; using
        // the key captured here makes every progress/success/failure publication fail closed.
        val draftKey = normalizeThreadAttachmentUploadDraftKey(composerDraftViewModel.state.value.draftKey)
            ?: return false
        val channelId = composerSurface.sendChannelId
        if (channelId == null) {
            bridge.toast(attachmentPickerUnavailableLabel)
            return false
        }
        // Capture one complete authority witness for the all-files/direct-session path and reuse
        // it for capabilities, upload, and compensation. This prevents a session/account/server
        // switch between those calls from silently falling back to an unbound repository.
        val directUploadOperation =
            dataSource.captureDirectAttachmentUploadOperation { message -> bridge.log(message) }
        if (requireAllFiles && directUploadOperation == null) {
            bridge.logWarn("thread_composer_attachment_batch_rejected reason=authority_unavailable")
            return false
        }
        // Never await the capabilities request on the per-file pick path: on a weak network it
        // takes seconds and would hold the composer chip back.  Use the cached value if fresh;
        // otherwise the background upload task resolves it after the chip is admitted.  The
        // all-files (Gallery) path must have an explicit receipt before admission, so it awaits.
        val directCapabilities: AttachmentUploadCapabilities? =
            if (directUploadOperation == null) {
                null
            } else if (requireAllFiles) {
                val probe = dataSource.attachmentUploadCapabilities(directUploadOperation)
                if (probe is DataResult.NoPermission) {
                    routeAuthRequired(probe)
                    bridge.toast(attachmentLimitUnavailableLabel)
                    return false
                }
                if ((probe as? DataResult.Error)?.error?.code.equals("cancelled", ignoreCase = true)) {
                    return false
                }
                (probe as? DataResult.Content)?.data
            } else {
                dataSource.peekAttachmentUploadCapabilities(directUploadOperation)
            }
        // Per-file Thread path fail-opens to multipart when capabilities cannot be read.
        // Gallery N/N remains fail-closed: it cannot compensate without an explicit capability.
        if (requireAllFiles && directCapabilities == null) {
            bridge.toast(attachmentLimitUnavailableLabel)
            return false
        }
        val admittedFiles = admittedComposerAttachmentFiles(
            files = files,
            maxBytesOverride = directCapabilities?.maxBytes,
            // Caller sizeBytes is an untrusted hint. Direct and fail-open paths
            // decide TooLarge/threshold only after authority-bound prepare/stat
            // (or skip the gate entirely when capabilities cannot be read).
            skipClientSizeGate = true,
        )
        if (admittedFiles.isEmpty() || (requireAllFiles && admittedFiles.size != files.size)) {
            if (requireAllFiles && admittedFiles.size != files.size) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=size_gate " +
                        "expected=${files.size} admitted=${admittedFiles.size}"
                )
            }
            return false
        }
        if (requireAllFiles && admittedFiles.any { file -> file.id.isBlank() }) {
            bridge.logWarn("thread_composer_attachment_batch_rejected reason=blank_local_id")
            return false
        }
        val activeUploadFileRefs =
            attachmentUploadOrchestrator.activeAttemptIds(draftKey)
                .filter { id -> attachmentUploadOrchestrator.isActive(id, draftKey) }
                .mapNotNull {
                    id -> attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, draftKey)?.fileRef?.takeIf { it.isNotBlank() }
                }
                .toSet()
        val activeUploadIds = attachmentUploadOrchestrator.activeAttemptIds(draftKey)
        val acceptedFileRefs = mutableSetOf<String>()
        val acceptedLocalIds = mutableSetOf<String>()
        val acceptedFiles =
            admittedFiles.filter { file ->
                val fileRef = file.fileRef?.takeIf { it.isNotBlank() }
                val isDuplicate =
                    file.id in activeUploadIds ||
                        !acceptedLocalIds.add(file.id) ||
                    fileRef != null &&
                        (fileRef in activeUploadFileRefs || !acceptedFileRefs.add(fileRef))
                !isDuplicate
            }
        if (requireAllFiles && acceptedFiles.size != admittedFiles.size) {
            bridge.logWarn(
                "thread_composer_attachment_batch_rejected reason=duplicate_or_active " +
                    "expected=${admittedFiles.size} accepted=${acceptedFiles.size}"
            )
            return false
        }
        if (acceptedFiles.size != admittedFiles.size) {
            bridge.log(
                "thread_composer_attachment_duplicate_skip skipped=${admittedFiles.size - acceptedFiles.size} " +
                    "activeUploads=${activeUploadFileRefs.size}"
            )
        }
        val existingAttachmentsById =
            composerDraftViewModel.state.value.uploadedAttachments.associateBy { attachment -> attachment.id }
        val localAttachments =
            acceptedFiles
                .filter { file -> file.id.isNotBlank() }
                .map { file ->
                    existingAttachmentsById[file.id]
                        ?.copy(
                            fileName = file.fileName,
                            sizeBytes = file.sizeBytes ?: 0L,
                            mimeType = file.mimeType?.takeIf(String::isNotBlank),
                            previewUri = file.composerLocalPreviewUri(),
                            status = "uploading",
                            errorCode = null,
                            fileRef = file.fileRef,
                            source = file.source ?: existingAttachmentsById[file.id]?.source,
                            uploadTransfer = null
                        )
                        ?: SharedThreadComposerAttachment(
                            id = file.id,
                            fileName = file.fileName,
                            sizeLabel = "",
                            sizeBytes = file.sizeBytes ?: 0L,
                            mimeType = file.mimeType?.takeIf(String::isNotBlank),
                            previewUri = file.composerLocalPreviewUri(),
                            status = "uploading",
                            fileRef = file.fileRef,
                            source = file.source
                        )
                }
        if (requireAllFiles && localAttachments.size != acceptedFiles.size) {
            bridge.logWarn(
                "thread_composer_attachment_batch_rejected reason=local_projection " +
                    "expected=${acceptedFiles.size} actual=${localAttachments.size}"
            )
            return false
        }
        val replacementSlotsByLocalId =
            acceptedFiles.associate { file ->
                file.id to
                    threadComposerReplacementSlotId(
                        replaceAttachmentId =
                            replaceAttachmentIdsByLocalId[file.id] ?: replaceAttachmentId,
                        localAttachmentId = file.id
                    )
            }
        var admittedLocalAdmissions: Map<String, SharedThreadComposerAttachmentAdmission> = emptyMap()
        if (localAttachments.isNotEmpty()) {
            val keyedFiles =
                acceptedFiles
                    .filter { file -> file.id.isNotBlank() }
                    .mapNotNull { file ->
                        threadAttachmentUploadAttemptIdentity(file.id, draftKey)?.let { identity ->
                            identity to file
                        }
                    }
                    .toMap()
            attachmentUploadFilesByLocalId =
                attachmentUploadFilesByLocalId + keyedFiles
            val admissionReceipt =
                try {
                    withContext(NonCancellable) {
                        composerDraftViewModel.acceptQueued(
                            SharedThreadComposerDraftIntent.AddLocalAttachments(
                                attachments = localAttachments,
                                expectedDraftKey = draftKey,
                                replacementAttachmentIdsByLocalId = replacementSlotsByLocalId
                            )
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    bridge.logWarn(
                        "thread_composer_attachment_admission_exception " +
                            "error=${error::class.simpleName.orEmpty()}"
                    )
                    null
                }
            val admitted =
                admissionReceipt?.accepted == true &&
                    normalizeThreadAttachmentUploadDraftKey(admissionReceipt.state.draftKey) == draftKey &&
                    threadComposerAttachmentAdmissionReceiptIsValid(
                        accepted = admissionReceipt.accepted,
                        before = admissionReceipt.previousState,
                        after = admissionReceipt.state,
                        expectedDraftKey = draftKey,
                        localAttachments = localAttachments,
                        replacementAttachmentIdsByLocalId = replacementSlotsByLocalId
                    ) &&
                    localAttachments.all { expected ->
                        admissionReceipt.state.attachmentAdmissionsByLocalId[expected.id]
                            ?.replacementAttachmentId
                            ?.trim()
                            ?.ifBlank { expected.id } == replacementSlotsByLocalId[expected.id]
                    } &&
                    localAttachments.all { expected ->
                        admissionReceipt.state.uploadedAttachments.any { actual ->
                            actual.id == expected.id &&
                                actual.status == "uploading" &&
                                actual.fileRef?.trim() == expected.fileRef?.trim() &&
                                actual.source?.trim() == expected.source?.trim()
                        }
                    } &&
                    localAttachments.none { expected ->
                        expected.id in admissionReceipt.state.removedAttachmentIds
                    }
            if (!admitted) {
                attachmentUploadFilesByLocalId =
                    attachmentUploadFilesByLocalId.filterKeys { identity ->
                        !(identity.draftKey == draftKey && identity.localAttachmentId in acceptedLocalIds)
                    }
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=admission_receipt " +
                        "draftKey=$draftKey"
                )
                return false
            }
            admittedLocalAdmissions =
                localAttachments
                    .mapNotNull { attachment ->
                        admissionReceipt.state.attachmentAdmissionsByLocalId[attachment.id]
                            ?.let { admission -> attachment.id to admission }
                    }
                    .toMap()
            bridge.log(
                "thread_composer_attachment_local_queue fileCount=${localAttachments.size} " +
                    "ids=${localAttachments.joinToString(",") { it.id }}"
            )
        }

        if (requireAllFiles) {
            // Gallery handoff is an atomic N/N surface.  Do not launch one legacy multipart
            // attempt per file: a sibling can fail after an earlier server attachment has been
            // published, and the legacy route has no retract contract.  The direct-session
            // coordinator stages every member, compensates every created session on any failure,
            // and returns ready attachments only after the whole batch is complete.
            // Gallery on this train calls with requireAllFiles=false, so this branch stays dormant.
            val acceptedLocalIds = acceptedFiles.map(PlatformFile::id).toSet()
            suspend fun markGalleryBatchFailed(errorCode: String): Boolean {
                val currentFailureState = composerDraftViewModel.state.value
                val markableIds =
                    acceptedLocalIds
                        .filter { id ->
                            normalizeThreadAttachmentUploadDraftKey(currentFailureState.draftKey) == draftKey &&
                                id !in currentFailureState.removedAttachmentIds &&
                                currentFailureState.attachmentAdmissionsByLocalId[id] == admittedLocalAdmissions[id] &&
                                currentFailureState.uploadedAttachments.any { attachment ->
                                    attachment.id == id && attachment.status == "uploading"
                                }
                        }
                        .toSet()
                val markableAdmissions =
                    markableIds.mapNotNull { id -> admittedLocalAdmissions[id]?.let { id to it } }.toMap()
                val receipt =
                    if (markableIds.isEmpty()) {
                        null
                    } else try {
                        withContext(NonCancellable) {
                            composerDraftViewModel.acceptQueued(
                                SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                                    attachmentIds = markableIds,
                                    errorCode = errorCode,
                                    expectedDraftKey = draftKey,
                                    expectedAdmissions = markableAdmissions
                                )
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "thread_composer_attachment_batch_failure_projection_exception " +
                                "error=${error::class.simpleName.orEmpty()}"
                        )
                        null
                    }
                val receiptState = receipt?.state
                val accepted =
                    markableIds.isEmpty() ||
                        (
                                receipt?.accepted == true &&
                                normalizeThreadAttachmentUploadDraftKey(receiptState?.draftKey) == draftKey &&
                                markableIds.all { id ->
                                    receiptState?.uploadedAttachments.orEmpty().any { attachment ->
                                        attachment.id == id && attachment.status == "failed" &&
                                            attachment.errorCode == errorCode
                                    }
                                }
                        )
                if (!accepted) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_failure_projection_rejected " +
                            "draftKey=$draftKey"
                    )
                }
                bridge.toast(
                    localizedAttachmentUploadFailedDetail(
                        userFacingErrorLabels.forErrorCode(errorCode) ?: genericThreadErrorLabel
                    )
                )
                return accepted
            }

            suspend fun compensateGalleryBatch(
                batch: build.raft.app.data.DirectAttachmentUploadBatch,
                reason: String,
            ): Boolean {
                val compensated =
                    try {
                        withContext(NonCancellable) {
                            requireNotNull(directUploadOperation).compensate(batch)
                        }
                    } catch (cancelled: CancellationException) {
                        bridge.logWarn(
                            "thread_composer_attachment_batch_compensation_cancelled " +
                                "reason=$reason"
                        )
                        false
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "thread_composer_attachment_batch_compensation_exception " +
                                "reason=$reason error=${error::class.simpleName.orEmpty()}"
                        )
                        false
                    }
                if (!compensated) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_compensation_failed reason=$reason"
                    )
                }
                return compensated
            }

            val batchResult =
                try {
                    withContext(Dispatchers.Default) {
                        attachmentUploadSemaphore.withPermit {
                            requireNotNull(directUploadOperation).upload(
                                channelId = ChannelId(channelId),
                                files = acceptedFiles,
                                admittedCapabilities = requireNotNull(directCapabilities),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        markGalleryBatchFailed("attachment_upload_batch_cancelled")
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_upload_exception " +
                            "error=${error::class.simpleName.orEmpty()}"
                    )
                    markGalleryBatchFailed("attachment_upload_batch_failed")
                    return false
                }

            val batch =
                when (batchResult) {
                    is DataResult.Content -> batchResult.data
                    is DataResult.NoPermission -> {
                        routeAuthRequired(batchResult)
                        markGalleryBatchFailed(batchResult.blocker.code)
                        return false
                    }
                    is DataResult.Error -> {
                        markGalleryBatchFailed(batchResult.error.code)
                        return false
                    }
                    is DataResult.Empty -> {
                        markGalleryBatchFailed("attachment_upload_batch_empty")
                        return false
                    }
                }

            val uploads = batch.uploads
            val uploadIds = uploads.map { upload -> upload.uploadId }
            val uploadedAttachmentIds = uploads.map { upload -> upload.attachment.id.value }
            val batchContractValid =
                uploads.size == acceptedFiles.size &&
                    uploads.map { upload -> upload.localId }.toSet() == acceptedLocalIds &&
                    uploads.map { upload -> upload.localId }.size == acceptedLocalIds.size &&
                    uploadIds.toSet().size == uploadIds.size &&
                    batch.sessionIds.toSet() == uploadIds.toSet() &&
                    uploadedAttachmentIds.all { id -> id.isNotBlank() } &&
                    uploadedAttachmentIds.toSet().size == uploadedAttachmentIds.size
            if (!batchContractValid) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=contract " +
                        "expected=${acceptedFiles.size} actual=${uploads.size}"
                )
                // Compensation is cleanup evidence only. Preserve the primary contract failure
                // even when a cancel/status operation is incomplete; replacing it with a generic
                // cleanup code would hide the operation that actually rejected the batch.
                compensateGalleryBatch(batch, reason = "contract")
                markGalleryBatchFailed("attachment_upload_session_contract_invalid")
                return false
            }

            val fileByLocalId = acceptedFiles.associateBy(PlatformFile::id)
            val admittedAttachmentsById = localAttachments.associateBy { attachment -> attachment.id }
            val readyAttachments =
                uploads.mapNotNull { upload ->
                    val file = fileByLocalId[upload.localId] ?: return@mapNotNull null
                    val sourceAttachment = admittedAttachmentsById[upload.localId]
                    val serverAttachment = upload.attachment
                    SharedThreadComposerAttachment(
                        id = serverAttachment.id.value,
                        fileName = serverAttachment.name,
                        sizeLabel = serverAttachment.sizeLabel,
                        sizeBytes = serverAttachment.sizeBytes,
                        mimeType = serverAttachment.mimeType.takeIf { it.isNotBlank() },
                        previewUri = file.composerLocalPreviewUri(),
                        status = "ready",
                        fileRef =
                            sourceAttachment
                                ?.takeIf { source -> source.source in ThreadComposerOwnedTemporarySources }
                                ?.fileRef,
                        source = sourceAttachment?.source ?: file.source,
                        externalShareToken = sourceAttachment?.externalShareToken,
                        externalShareAccountId = sourceAttachment?.externalShareAccountId,
                        externalShareServerId = sourceAttachment?.externalShareServerId,
                        // The server confirmed the upload: the transfer is done.
                        uploadTransfer = sourceAttachment?.uploadTransfer?.completed(file.sizeBytes)
                    )
                }
            if (readyAttachments.size != acceptedFiles.size) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=local_mapping " +
                        "expected=${acceptedFiles.size} actual=${readyAttachments.size}"
                )
                compensateGalleryBatch(batch, reason = "local_mapping")
                markGalleryBatchFailed("attachment_upload_batch_incomplete")
                return false
            }

            val replacementsByUploadedId =
                uploads.associate { upload ->
                    upload.attachment.id.value to
                        threadComposerReplacementSlotId(
                            replaceAttachmentId =
                                replaceAttachmentIdsByLocalId[upload.localId]
                                    ?: replaceAttachmentId,
                            localAttachmentId = upload.localId
                        )
                }
            val expectedReplacementAttachmentIdsByLocalId =
                acceptedLocalIds.associateWith { localId ->
                    threadComposerReplacementSlotId(
                        replaceAttachmentId =
                            replaceAttachmentIdsByLocalId[localId]
                                ?: replaceAttachmentId,
                        localAttachmentId = localId
                    )
                }
            val expectedServerAttachmentIdsByLocalId =
                uploads
                    .groupBy { upload -> upload.localId }
                    .mapValues { (_, localUploads) ->
                        localUploads.map { upload -> upload.attachment.id.value }.toSet()
                    }
            // This is the sole Gallery projection/ownership publication.  No per-file callback
            // may transfer a temporary ref before this aggregate commit point.
            val commitReceipt =
                try {
                    composerDraftViewModel.acceptQueued(
                        SharedThreadComposerDraftIntent.AddUploadedAttachments(
                            attachments = readyAttachments,
                            replacedAttachmentIds = acceptedLocalIds,
                            replacedAttachmentIdsByUploadedId = replacementsByUploadedId,
                            expectedDraftKey = draftKey,
                            expectedAdmissions = admittedLocalAdmissions,
                            expectedServerAttachmentIdsByLocalId = expectedServerAttachmentIdsByLocalId,
                            expectedReplacementAttachmentIdsByLocalId = expectedReplacementAttachmentIdsByLocalId
                        )
                    )
                } catch (cancelled: CancellationException) {
                    compensateGalleryBatch(batch, reason = "aggregate_cancelled")
                    markGalleryBatchFailed("attachment_upload_batch_failed")
                    throw cancelled
                } catch (error: Throwable) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_projection_exception " +
                            "error=${error::class.simpleName.orEmpty()}"
                    )
                    compensateGalleryBatch(batch, reason = "aggregate_projection")
                    markGalleryBatchFailed("attachment_upload_batch_failed")
                    return false
                }
            val receiptState = commitReceipt.state
            val receiptPostconditionHolds =
                threadComposerAggregateReceiptIsValid(
                    accepted = commitReceipt.accepted,
                    before = commitReceipt.previousState,
                    after = receiptState,
                    expectedDraftKey = draftKey,
                    readyAttachments = readyAttachments,
                    expectedLocalAttachmentIds = acceptedLocalIds,
                    expectedReplacementAttachmentIds = expectedReplacementAttachmentIdsByLocalId.values
                )
            if (!receiptPostconditionHolds) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=aggregate_receipt " +
                        "accepted=${commitReceipt.accepted} expected=${readyAttachments.size} " +
                        "actual=${receiptState.uploadedAttachments.size}"
                )
                compensateGalleryBatch(batch, reason = "aggregate_receipt")
                markGalleryBatchFailed("attachment_upload_batch_failed")
                return false
            }
            // The receipt is the exact serialized commit boundary.  Once accepted, a later draft
            // switch cannot retroactively make this projection rejectable; compensating here
            // would retract server reservations already referenced by committed durable state.
            // Ownership is retired only after this aggregate commit and there is no per-file
            // transfer/deletion callback on the Gallery path.
            attachmentUploadFilesByLocalId =
                attachmentUploadFilesByLocalId.filterKeys { identity ->
                    !(identity.draftKey == draftKey && identity.localAttachmentId in acceptedLocalIds)
                }
            runCatching {
                bridge.log(
                    "thread_composer_attachment_batch_upload_committed fileCount=${readyAttachments.size} " +
                        "ids=${readyAttachments.joinToString(",") { attachment -> attachment.id }}"
                )
            }
            return true
        }

        // Gallery on this train calls with requireAllFiles=false, so this remaining
        // per-file multipart path is the 1.10.0-shaped uploader. The aggregate
        // direct-session branch above stays dormant.
        var launchedCount = 0
        // The draft owns these uploads (process-wide), so they keep going when the user leaves
        // the conversation; results go to the draft's uploads and the composer showing this draft
        // applies them (see composerDraftUploadApplier).
        val draftUploads = attachmentUploads
        for (file in acceptedFiles) {
            val localAttachmentId = file.id.takeIf { it.isNotBlank() } ?: continue
            val admission = admittedLocalAdmissions[localAttachmentId] ?: continue
            val admittedSourceAttachment = localAttachments.firstOrNull { it.id == localAttachmentId } ?: continue
            val identity = threadAttachmentUploadAttemptIdentity(localAttachmentId, draftKey)
            val clientRequestId =
                if (identity != null) {
                    threadDirectUploadClientRequestIds.idFor(identity)
                } else {
                    newAttachmentUploadClientRequestId()
                }
            val attemptToken =
                draftUploads.launch(
                    draftKey = draftKey,
                    row = admittedSourceAttachment,
                    file = file,
                    launchAdmission = admission
                ) { uploadAttemptToken ->
                    fun publishTerminalFailure(errorCode: String) {
                        draftUploads.fail(draftKey, localAttachmentId, uploadAttemptToken, errorCode)
                    }
                    try {
                        bridge.log(
                            "thread_composer_attachment_upload_start attachmentId=$localAttachmentId fileName=${file.fileName}"
                        )
                        // The chip is already on screen; resolve upload limits in the background
                        // (cache hit, or one shared request) and fail open to multipart on error.
                        var taskCapabilities = directCapabilities
                        if (taskCapabilities == null && directUploadOperation != null) {
                            when (val fetched = dataSource.attachmentUploadCapabilities(directUploadOperation)) {
                                is DataResult.Content -> taskCapabilities = fetched.data
                                is DataResult.NoPermission -> routeAuthRequired(fetched)
                                is DataResult.Error,
                                is DataResult.Empty -> Unit
                            }
                        }
                        val uploadResult =
                            withContext(Dispatchers.Default) {
                                attachmentUploadSemaphore.withPermit {
                                    val resolvedUpload =
                                        when (
                                            val resolved =
                                                resolveThreadComposerDirectUpload(
                                                    file = file,
                                                    capabilities = taskCapabilities,
                                                    cancelled = false,
                                                    prepare = { candidate ->
                                                        val operation = directUploadOperation
                                                        if (operation == null) {
                                                            DataResult.Error(
                                                                ScreenError(
                                                                    code = "attachment_size_unavailable",
                                                                    retryable = true,
                                                                )
                                                            )
                                                        } else {
                                                            operation.prepare(candidate)
                                                        }
                                                    },
                                                )
                                        ) {
                                            is DataResult.Content -> resolved.data
                                            is DataResult.Error ->
                                                return@withPermit resolved
                                            is DataResult.NoPermission ->
                                                return@withPermit resolved
                                            is DataResult.Empty ->
                                                return@withPermit DataResult.Error(
                                                    ScreenError(
                                                        code = "attachment_size_unavailable",
                                                        retryable = false,
                                                    )
                                                )
                                        }
                                    val uploadFile = resolvedUpload.file
                                    val decision = resolvedUpload.decision
                                    // This attempt's upload meter. The engine reports the bytes it wrote; the percent stays
                                    // at 99 until the server confirmed the upload (the ready attachment is the 100%). Until the
                                    // first publish the chip says "Checking file…" (size, limits, upload session).
                                    val uploadMeter = RaftTransferMeter(bytesTotal = uploadFile.sizeBytes)
                                    val uploadMeterLock = SynchronizedObject()
                                    fun publishTransfer(step: RaftTransferMeter.() -> RaftTransferProgress) {
                                        val transfer = synchronized(uploadMeterLock) { uploadMeter.step() }
                                        draftUploads.progress(draftKey, localAttachmentId, uploadAttemptToken, transfer)
                                    }
                                    fun publishPhaseProgress(phase: ThreadComposerDirectUploadPhase) {
                                        when (phase) {
                                            // Still checking: the upload session is being created.
                                            ThreadComposerDirectUploadPhase.Creating -> Unit
                                            ThreadComposerDirectUploadPhase.Putting -> publishTransfer { total(uploadFile.sizeBytes) }
                                            // Every byte is with the storage; the server verifies before it confirms.
                                            ThreadComposerDirectUploadPhase.Verifying -> publishTransfer { finishing() }
                                        }
                                    }
                                    when (decision) {
                                        ThreadComposerDirectUploadDecision.Cancelled ->
                                            DataResult.Error(ScreenError(code = "cancelled", retryable = false))
                                        ThreadComposerDirectUploadDecision.TooLarge ->
                                            DataResult.Error(
                                                ScreenError(code = "UPLOAD_TOO_LARGE", retryable = false)
                                            )
                                        ThreadComposerDirectUploadDecision.Direct -> {
                                            val operation = directUploadOperation
                                            if (operation == null) {
                                                publishTransfer { total(uploadFile.sizeBytes) }
                                                dataSource.uploadAttachments(
                                                    channelId = channelId,
                                                    files = listOf(uploadFile),
                                                    onProgress = { progress -> publishTransfer { bytes(progress.bytesTransferred, progress.bytesTotal) } }
                                                )
                                            } else {
                                                when (
                                                    val one =
                                                        operation.uploadOneThreadFile(
                                                            channelId = ChannelId(channelId),
                                                            file = uploadFile,
                                                            clientRequestId = clientRequestId,
                                                            onPhase = ::publishPhaseProgress,
                                                            onUploadProgress = { progress -> publishTransfer { bytes(progress.bytesTransferred, progress.bytesTotal) } },
                                                        )
                                                ) {
                                                    is DataResult.Content ->
                                                        DataResult.Content(AttachmentUploadResult(listOf(one.data)))
                                                    is DataResult.Error -> one
                                                    is DataResult.NoPermission -> one
                                                    is DataResult.Empty -> one
                                                }
                                            }
                                        }
                                        ThreadComposerDirectUploadDecision.LegacyMultipart -> {
                                            publishTransfer { total(uploadFile.sizeBytes) }
                                            dataSource.uploadAttachments(
                                                channelId = channelId,
                                                files = listOf(uploadFile),
                                                onProgress = { progress -> publishTransfer { bytes(progress.bytesTransferred, progress.bytesTotal) } }
                                            )
                                        }
                                    }
                                }
                            }
                        val published =
                            draftUploads.orchestrator.publishIfAccepted(
                                localAttachmentId,
                                draftKey,
                                uploadAttemptToken
                            ) {
                                when (uploadResult) {
                                    is DataResult.Content -> {
                                        val previewUri = file.composerLocalPreviewUri()
                                        val sourceAttachment = admittedSourceAttachment
                                        val uploaded =
                                            uploadResult.data.attachments.map { attachment ->
                                                SharedThreadComposerAttachment(
                                                    id = attachment.id.value,
                                                    fileName = attachment.name,
                                                    sizeLabel = attachment.sizeLabel,
                                                    sizeBytes = attachment.sizeBytes,
                                                    mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                                    previewUri = previewUri,
                                                    status = "ready",
                                                    fileRef =
                                                        sourceAttachment
                                                            ?.takeIf { source ->
                                                                source.source in ThreadComposerOwnedTemporarySources
                                                            }
                                                            ?.fileRef,
                                                    source = sourceAttachment?.source,
                                                    externalShareToken = sourceAttachment?.externalShareToken,
                                                    externalShareAccountId = sourceAttachment?.externalShareAccountId,
                                                    externalShareServerId = sourceAttachment?.externalShareServerId,
                                                    // The server confirmed the upload: the transfer is done.
                                                    uploadTransfer = sourceAttachment?.uploadTransfer?.completed()
                                                )
                                            }
                                        if (
                                            uploaded.isEmpty() ||
                                            uploaded.none { attachment ->
                                                attachment.id.isNotBlank() && !attachment.id.startsWith("picked-")
                                            }
                                        ) {
                                            publishTerminalFailure("empty_upload_result")
                                        } else {
                                            // The composer showing this draft (this page, or the
                                            // one that opens it after the user came back) puts
                                            // these in place of the local row.
                                            draftUploads.succeed(draftKey, localAttachmentId, uploadAttemptToken, uploaded)
                                        }
                                    }
                                    is DataResult.Error ->
                                        publishTerminalFailure(uploadResult.error.code)
                                    is DataResult.NoPermission -> {
                                        routeAuthRequired(uploadResult)
                                        publishTerminalFailure(uploadResult.blocker.code)
                                    }
                                    is DataResult.Empty ->
                                        publishTerminalFailure("empty_upload_result")
                                }
                            }
                        if (!published) {
                            bridge.log(
                                "thread_composer_attachment_upload_drop attachmentId=$localAttachmentId " +
                                    "attempt=$uploadAttemptToken reason=stale_or_terminal"
                            )
                        }
                    } catch (_: CancellationException) {
                        // The user removed this uploading tile; RemoveAttachment already hid it.
                        // A gallery caller still receives a false completion below, so its
                        // session cannot be committed as if cancellation were a transfer.
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "thread_composer_attachment_upload_exception " +
                                "attachmentId=$localAttachmentId " +
                                "error=${error::class.simpleName.orEmpty()}"
                        )
                        publishTerminalFailure("attachment_upload_failed")
                    }
                }
            if (attemptToken != null) {
                launchedCount += 1
            }
        }
        return acceptedFiles.isNotEmpty() && acceptedFiles.size == files.size && launchedCount == acceptedFiles.size
    }
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
                onFailed = { _, errorCode -> showAttachmentUploadFailure(errorCode) },
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
    viewerImageEditAttachment?.let { attachment ->
        RaftViewerImageEditor(
            attachment = attachment,
            desktop = imageLightboxEnabled,
            resolveFullUrl = { attachmentId ->
                when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline")) {
                    is ThreadApiResult.Success -> response.data.url
                    is ThreadApiResult.Failure -> {
                        bridge.log("thread_viewer_image_edit status=url_failed attachmentId=${attachmentId.take(8)} code=${response.code}")
                        null
                    }
                }
            },
            onCancel = { viewerImageEditAttachment = null },
            onSaveFailed = { bridge.toast(genericThreadErrorLabel) },
            onEdited = { fileRef ->
                viewerImageEditSequence += 1
                val edited = raftViewerEditedImageFile(fileRef, attachment.name, viewerImageEditSequence)
                viewerImageEditAttachment = null
                imageLightboxRequest = null
                bridge.log("thread_viewer_image_edit status=done attachmentId=${attachment.id.take(8)}")
                coroutineScope.launch {
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
                    uploadComposerAttachmentFiles(prepared)
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RequestFocus)
                }
            },
        )
    }
    fun performComposerSend(): Boolean {
        val currentComposer = currentComposerPayload()
        val currentDraftState = composerDraftViewModel.state.value
        val composerInstanceId = currentComposer.instanceId.ifBlank { payload.instanceId }
        val threadId = composerSurface.sendChannelId
        val externalShareAttachments =
            currentComposer.pendingAttachments.filter { attachment -> attachment.source == "external_share" }
        val externalShareSources = currentDraftState.externalShareSources
        val session = currentSession
        if (
            externalShareSources.any { source ->
                session == null || source.accountId != session.user.id || source.serverId != session.currentServerId
            } || externalShareAttachments.any { attachment ->
                session == null ||
                    attachment.externalShareAccountId != session.user.id ||
                    attachment.externalShareServerId != session.currentServerId
            }
        ) {
            bridge.logWarn("external_share_send_rejected reason=authority_changed_before_message_send")
            return false
        }
        val draftText = currentDraftState.seedText.trim()
        val attachmentIds = currentComposer.pendingAttachments.readyThreadAttachmentIdsForSend()
        val hasBlockingAttachmentUpload = currentComposer.pendingAttachments.hasBlockingThreadAttachmentUpload()
        val hasFailedAttachmentUpload = currentComposer.pendingAttachments.hasFailedThreadAttachmentUpload()
        val gateBlockReason =
            when {
                !payload.canSend -> "payload_can_send_false"
                threadId == null -> "missing_send_channel"
                hasFailedAttachmentUpload -> "attachment_upload_failed"
                hasBlockingAttachmentUpload -> "attachment_upload_in_progress"
                draftText.isBlank() && attachmentIds.isEmpty() -> "empty_draft"
                else -> null
            }
        if (gateBlockReason != null) {
            bridge.log(
                "thread_composer_send_gate result=blocked reason=$gateBlockReason " +
                    "surface=${payload.surfaceKind} status=${payload.status} canSend=${payload.canSend} " +
                    "sendChannelId=${threadId.orEmpty()} hasText=${draftText.isNotBlank()} " +
                    "attachments=${attachmentIds.size} blockingAttachments=$hasBlockingAttachmentUpload " +
                    "failedAttachments=$hasFailedAttachmentUpload " +
                    "attachmentStates=${currentComposer.pendingAttachments.threadComposerSendGateAttachmentStates()}"
            )
            return false
        }
        val sendAuthority =
            dataSource.captureThreadLocalSendAuthority(
                state = threadViewModel.state.value,
                mountedOwnerProvenance = replyCacheProvenance
            )
                ?: run {
                    bridge.logWarn("thread_composer_send_rejected reason=missing_owner_provenance")
                    return false
                }
        val pendingAttachments = currentComposer.pendingAttachments
        val ownedTemporaryFileRefs =
            threadPendingSendOwnedTemporaryFileRefs(
                attachments = currentDraftState.uploadedAttachments,
                attachmentIds = attachmentIds
            )
        // The assistant's DM in its panel: a message with text carries the conversation the panel
        // was opened from, its token on a line of its own (web MessageInput withComposerReference).
        val assistantContextAttachment = composerSurface.assistantContext
        val assistantContextToken = assistantContextTokenForSend(draftText, assistantContextAttachment?.context)
        val composedBody =
            threadComposerMessageBodyForSend(
                draftText = draftText,
                attachmentCount = pendingAttachments.size
            )
        val pendingContext =
            RaftThreadPendingSendContext(
                channelId = threadId ?: return false,
                body = withAssistantContextReference(composedBody, assistantContextToken),
                asTask = currentDraftState.seedAsTask && composerSurface.sendsAsTask,
                attachmentIds = attachmentIds,
                attachments = pendingAttachments,
                ownedTemporaryFileRefs = ownedTemporaryFileRefs,
                externalShareSources = externalShareSources,
                mentionChannelId = composerSurface.mentionScopeChannelId,
                parentMessageId = composerSurface.parentMessageId,
                streamChannelId = composerSurface.streamChannelId,
                memberScopedMentions = payload.memberScopedMentions,
                randomId = newThreadSendRandomId(),
                // Only picks whose `@handle` is still in the text go out; the list is journaled as is.
                mentions = currentDraftState.structuredMentions.visibleStructuredMentionsIn(draftText),
            )
        val localMessageId = ThreadLocalSendNextId.next(composerInstanceId)
        val localTimestampLabel = buildThreadPendingSendTimestampLabel(bridge, payload.nowEpochMillis)
        val localMessage =
            buildThreadPendingSendMessage(
                localMessageId = localMessageId,
                context = pendingContext,
                session = currentSession,
                timestampLabel = localTimestampLabel
            )
        if (
            !threadViewModel.admitLocalSend(
                message = localMessage,
                expectedProvenance = sendAuthority.provenance,
                expectedRoute = sendAuthority.route,
                composerSeed =
                    ThreadLocalSendComposerSeed(
                        // What the composer held: the reference token was never in it.
                        sentBody = composedBody,
                        sentAttachmentIds = pendingContext.attachmentIds.toSet()
                    )
            )
        ) {
            bridge.logWarn("thread_composer_send_rejected reason=owner_changed_before_admission")
            return false
        }
        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        // The message took the reference (it is in the journaled body, retries included): the chip clears.
        if (assistantContextToken != null) assistantContextAttachment?.detach()
        // The journal row now owns crash protection for this send (restart adoption,
        // failed row with retry), so the persisted composer draft must be cleared at
        // admission — otherwise a mid-send process kill leaves a second copy of the same
        // text that reloads into the composer and invites a duplicate send (#1134 cell 3,
        // Argus ruling). Journal-write failure keeps the draft because admission already
        // returned false above. CompleteSend only clears the durable record; the send
        // completion below still dispatches its own CompleteSend for mention actions.
        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        // The draft went out with its ready attachments: forget its uploads (the send gate let it
        // through only with none in flight).
        attachmentUploads.retireDraft(currentDraftState.draftKey)
        // Before the request: the canonical echo may beat the HTTP response, and it must already
        // resolve these attachment ids to the local image instead of downloading it again.
        build.raft.app.image.RaftSentAttachmentLocalImages.registry.register(
            threadSentAttachmentLocalImageSources(
                attachments = currentDraftState.uploadedAttachments,
                attachmentIds = attachmentIds
            )
        )
        sendPendingLocalMessage(
            localMessageId = localMessageId,
            context = pendingContext,
            startedFromComposer = true,
            authority = sendAuthority
        )
        return true
    }
    fun handleComposerSendRequest(request: ThreadComposerSendRequest): ThreadComposerSendAdmission {
        if (externalShareSendRequested) {
            bridge.log("external_share_send_duplicate_drop reason=upload_chain_active")
            return ThreadComposerSendAdmission.Deferred
        }
        // Reconcile the explicit native tap-time snapshot before any host gate reads the draft.
        // This closes the window where UIKit already displays a final character while its normal
        // text-change callback is still queued for Compose.
        composerDraftViewModel.dispatch(
            SharedThreadComposerDraftIntent.Change(
                seedText = request.value.text,
                seedAsTask = request.asTask,
            )
        )
        val currentDraftState = composerDraftViewModel.state.value
        bridge.log(
            "thread_composer_send_snapshot session=${request.editorSessionId} " +
                "textLen=${request.value.text.length} textBlank=${request.value.text.isBlank()} " +
                "asTask=${request.asTask}"
        )
        val queuedExternalAttachments =
            currentDraftState.uploadedAttachments.filter { attachment ->
                attachment.source == "external_share" &&
                    attachment.status == "queued" &&
                    attachment.id !in currentDraftState.removedAttachmentIds
            }
        val externalShareSources = currentDraftState.externalShareSources
        if (externalShareSources.isNotEmpty()) {
            externalShareSendRequested = true
            coroutineScope.launch {
                val expected = dataSource.captureExternalShareSendAuthority()
                val authorityMatches =
                    expected != null &&
                        externalShareSources.all { source ->
                            source.accountId == expected.accountId &&
                                source.serverId == expected.serverId
                        }
                if (!authorityMatches) {
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                    bridge.logWarn("external_share_send_rejected reason=authority_changed")
                    bridge.toast(genericThreadErrorLabel)
                    return@launch
                }
                externalShareExpectedAuthority = expected
                if (queuedExternalAttachments.isNotEmpty()) {
                    val files =
                        queuedExternalAttachments.mapNotNull { attachment ->
                            attachmentUploadFilesByLocalId.threadAttachmentUploadFile(
                                attachment.id,
                                normalizeThreadAttachmentUploadDraftKey(currentDraftState.draftKey)
                            )
                        }
                    if (files.size != queuedExternalAttachments.size) {
                        externalShareSendRequested = false
                        externalShareExpectedAuthority = null
                        bridge.logWarn("external_share_send_rejected reason=missing_file_ref")
                        bridge.toast(attachmentRetryUnavailableLabel)
                        return@launch
                    }
                    val uploadStarted = uploadComposerAttachmentFiles(files)
                    if (!uploadStarted) {
                        externalShareSendRequested = false
                        externalShareExpectedAuthority = null
                    }
                } else {
                    val stillCurrent = dataSource.matchesExternalShareSendAuthority(expected)
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                    if (stillCurrent) {
                        performComposerSend()
                    } else {
                        bridge.logWarn(
                            "external_share_send_rejected reason=auth_generation_changed_before_send"
                        )
                    }
                }
            }
            return ThreadComposerSendAdmission.Deferred
        }
        return if (performComposerSend()) {
            ThreadComposerSendAdmission.Started
        } else {
            ThreadComposerSendAdmission.Rejected
        }
    }
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
                        performComposerSend()
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
    fun openComposerImageAnnotation(files: List<PlatformFile>) {
        val firstFile = files.firstOrNull() ?: return
        val currentDraftState = composerDraftViewModel.state.value
        composerImageActionCounter += 1
        val actionId = "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${firstFile.id.take(12)}"
        val opened =
            openTrackedComposerImagePreview(
                threadComposerImageAnnotationPayload(
                    threadInstanceKey = threadInstanceKey,
                    actionId = actionId,
                    files = files,
                    originalImage = currentDraftState.originalImage,
                ) ?: return
            )
        if (opened) composerKeyboardHandoff.pageOpened()
    }
    /**
     * A host files envelope from the attach buttons' picker or a drop on the composer
     * (`threadAttachmentDrop`): one admission path for both, so a dropped file is admitted, sized
     * and reported exactly like a picked one.
     */
    suspend fun admitComposerPickedAttachments(action: String, response: JSONObject?) {
        val files = parseSharedThreadPickedPlatformFiles(response)
        bridge.log(
            "thread_attachment_picker_result action=$action code=${response?.optInt("code", -1) ?: -1} " +
                "files=${files.size} errorCode=${response?.optString("errorCode").orEmpty()} " +
                "bodyBytes=${response?.optString("bodyText").orEmpty().encodeToByteArray().size}"
        )
        // Native generic pickers may finish a mixed selection with a readable
        // subset and a compression error (code 206).  Never upload that subset:
        // doing so would silently drop the failed image while the user believes
        // the whole selection was admitted.  Retire every ref exposed by the
        // envelope before returning, including refs from successful files and
        // any future error/fileRefs compatibility fields.
        if (threadAttachmentPickerHasCompressionFailure(response)) {
            val cleanupRefs = threadAttachmentPickerCompressionFailureFileRefs(response)
            if (cleanupRefs.isNotEmpty()) {
                bridge.retireFilePreviewTemporaryFiles(
                    sessionId = "thread-picker-compression-$threadInstanceKey",
                    fileRefs = cleanupRefs.toList()
                )
            }
            bridge.logWarn(
                "thread_attachment_picker_rejected reason=image_compression_failed " +
                    "cleanupRefs=${cleanupRefs.size}"
            )
            bridge.toast(
                localizedAttachmentUploadFailedDetail(
                    userFacingErrorLabels.forErrorCode(
                        RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE
                    ) ?: genericThreadErrorLabel
                )
            )
            return
        }
        if (files.isEmpty()) {
            val errorCode = response?.optString("errorCode")?.takeIf { it.isNotBlank() }
            when {
                errorCode != null ->
                    bridge.toast(
                        localizedAttachmentUploadFailedDetail(
                            userFacingErrorLabels.forErrorCode(errorCode) ?: genericThreadErrorLabel
                        )
                    )
                response == null ->
                    bridge.toast(attachmentPickerUnavailableLabel)
            }
            return
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
                allFilesAreImages = raftComposerImagePickerFilesAreStrictImages(files),
            )
        ) {
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation ->
                // The picker-reported bytes are the source image, not the final
                // editor output.  Defer the server-size gate until the confirmed
                // PNG/original or compressor-produced JPEG reaches upload.
                openComposerImageAnnotation(files)
            ThreadPickedAttachmentComposerAction.UploadToComposer -> uploadComposerAttachmentFiles(files)
            ThreadPickedAttachmentComposerAction.None -> Unit
        }
    }
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
                                uploadComposerAttachmentFiles(
                                    files = files,
                                    // Per-file admission: one oversize or failed image must not
                                    // reject the rest. Direct vs multipart is chosen per file.
                                    requireAllFiles = false,
                                    replaceAttachmentIdsByLocalId = replaceAttachmentIdsByLocalId
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
        // (opening never creates it), or a page.
        task.taskOpenSurface(taskV2Enabled)?.let { surface ->
            bridge.log("thread_task_open result=surface kind=${surface::class.simpleName} source=$source taskId=${task.id.value}")
            taskOpenSurfaceFlow.open(surface)
            return@open
        }
        if (!taskOpenGate.tryBegin(task.id.value)) {
            bridge.log("thread_task_open result=ignored reason=in_flight source=$source taskId=${task.id.value}")
            return@open
        }
        if (task.taskOpenTarget(taskV2Enabled) == TaskOpenTarget.ResolveConversation) {
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
                                taskV2Enabled = taskV2Enabled,
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

    TaskOpenSurfaceHost(
        flow = taskOpenSurfaceFlow,
        detailsRepository = taskConversationDetailsRepository,
        membersStore = serverMembersStore,
        serverId = currentServerId,
        // The conversation page's head writes: the same task channel store, gates and failure toasts.
        editing =
            TaskPageEditing(
                authStore = authStore,
                tasksDataSource = tasksDataSource,
                tasksDataStore = tasksDataStore,
                onFailed = { code ->
                    when {
                        code.startsWith("tasks_status") && !routeAuthRequired(code) -> bridge.toast(unableToUpdateTaskLabel)
                        code.startsWith("tasks_assign") && !routeAuthRequired(code) -> bridge.toast(unableToUpdateAssigneeLabel)
                    }
                },
                log = { bridge.log(it) },
            ),
        hostVisible = active,
        taskV2Enabled = taskV2Enabled,
        onConversationArrived = { arrived ->
            bridge.log("thread_task_pending_conversation_arrived taskId=${arrived.id.value}")
            openCanonicalTask(arrived, "task_conversation_arrived")
        },
        timeContext = timeContextProvider.current ?: renderedThread.toRaftTimeCapabilities().staticContext(),
        // Web canWriteTasksInChannel: known for the channel this page shows (its task actions' gate);
        // another channel is not known here and stays writable.
        canWriteTaskChannel = { channelId ->
            renderedThread.surfaceKind != RaftThreadSurfaceKind.Channel ||
                channelId == null ||
                channelId != renderedThread.joinChannelTargetId() ||
                renderedThread.channelTaskWritesShown()
        },
        // Known for the channel this page shows (web ThreadPanel `parentJoined`); else not known here.
        joined = { task ->
            task.channelId?.takeIf { it == renderedThread.joinChannelTargetId() }?.let { renderedThread.joined }
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
                                if (!routeAuthRequired(result)) bridge.errorToast(unableToJoinChannelLabel, result.blocker.traceId)
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
    // web TaskConversationDialog binds to a conversation created elsewhere (`task:updated`): the open
    // sheet follows the store's copy of its task and gives way to the conversation page.
    val taskOpenSurfaceTaskId = taskOpenSurfaceFlow.surface?.task?.id
    LaunchedEffect(taskOpenSurfaceTaskId, taskProjectionSlice, serverTaskProjectionSlice, taskV2Enabled) {
        val taskId = taskOpenSurfaceTaskId ?: return@LaunchedEffect
        val fresh =
            taskProjectionSlice.tasks.firstOrNull { it.id == taskId }
                ?: serverTaskProjectionSlice.tasks.firstOrNull { it.id == taskId }
        taskOpenSurfaceFlow.onTaskChanged(fresh, taskV2Enabled)?.let { arrived ->
            bridge.log("thread_task_pending_conversation_arrived taskId=${arrived.id.value}")
            openCanonicalTask(arrived, "task_conversation_arrived")
        }
    }

    createTaskFromMessage.source?.let { source ->
        TaskCreateFromMessageDialog(
            source = source,
            submitting = createTaskFromMessage.submitting,
            errorText = createTaskFromMessage.errorText,
            onDismiss = { createTaskFromMessage.dismiss() },
            // Task v2 Create Task's Status field (web: shown with the task v2 flag on).
            statusField = taskV2Enabled,
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

    // Whether this page's assistant panel is open (set from the content, where the otter lives):
    // the page's own banner then gives way to the panel window's (threadForegroundBannerOnPage).
    val assistantPanelOpenOnPage = remember { mutableStateOf(false) }
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
    RaftForegroundBannerHost(
        banner =
            foregroundMessageBanner?.takeIf {
                threadForegroundBannerOnPage(
                    inAssistantPanel = LocalThreadAssistantPanelEmbed.current != null,
                    assistantPanelOpen = assistantPanelOpenOnPage.value,
                )
            },
        topSafeArea = foregroundBannerTopSafeArea,
        serverMembersStore = serverMembersStore,
        currentServerStore = foregroundBannerCurrentServerStore,
        currentServerIdOverride = foregroundBannerServerId,
        onBannerClick = onForegroundBannerClick,
        onBannerDismiss = onForegroundBannerDismiss,
        handoff =
            RaftForegroundBannerHandoff(
                banner = foregroundMessageBanner,
                topSafeArea = foregroundBannerTopSafeArea,
                serverMembersStore = serverMembersStore,
                currentServerId = foregroundBannerServerId,
                onClick = onForegroundBannerClick,
                onDismiss = onForegroundBannerDismiss,
                coveredByWindow = assistantPanelOpenOnPage,
            ),
    ) {
        // OFF-zero-work provider set (task #20 P1, reviewed round 4): with
        // diagnostics OFF the census session Local is NOT part of the vararg
        // at all — the base array is used by identity, so the production
        // provider scope is unchanged by P1.
        // New Message Menu toggle: message bodies become selectable in place (ThreadInPlaceTextSelection.kt).
        // Always on where the pointer selects text (desktop: drag, double-click, Cmd+C, like the web).
        val inPlaceTextSelectionHost =
            remember(newMessageMenuEnabled, bridge, copiedLabel) {
                ThreadInPlaceTextSelectionHost(
                    enabled = newMessageMenuEnabled || raftPlatformMessageTextPointerSelectable(),
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
                        // Workspace Artifacts links (behind workspace_artifacts_v0, read per message).
                        artifacts =
                            ThreadArtifactLinkHost(
                                summaries = sharedStores.artifactSummaryStore,
                                appHosts = sharedStores.clientConfigStore.appHosts,
                                featureFlags = sharedStores.serverFeatureFlags,
                                webBaseUrl = sharedStores.apiEnvironmentStore.current().webUrl,
                                screenshots = sharedStores.artifactScreenshotStore,
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
        val memberPreviewCardSource =
            remember(serverMembersStore, headerProjectionServerId, agentStatusResolver, memberAvatarResolver, threadMembersServerId, threadMembersChannelId) {
                RaftMemberPreviewCardSource(
                    serverMembersStore = serverMembersStore,
                    serverId = headerProjectionServerId?.value,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
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
        // Member labels (web #14535): another server's tile shows its logo only when the viewer is
        // also a member of it (the session's servers); otherwise its initial.
        val viewerServerLogos =
            remember(currentSession?.servers) {
                raftViewerServerLogos(currentSession?.servers.orEmpty().map { it.id to it.avatarUrl })
            }
        val threadProvidedValues = raftMarkdownProvidedValuesWith(
            arrayOf(
                LocalRaftViewerServerLogos provides viewerServerLogos,
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
                        requestFallback = ::requestInlineImageFallback
                    ),
                LocalThreadAttachmentDownloadHost provides attachmentDownloadHost,
                LocalRaftMessagePermalinkLabelResolver provides messagePermalinkLabelResolver,
                LocalRaftMarkdownTaskStatuses provides markdownTaskStatuses,
                LocalRaftMarkdownTaskRefStatuses provides markdownTaskRefStatuses,
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
            timeContextProvider.current ?: renderedThread.toRaftTimeCapabilities().staticContext()
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
                    )
                }
            } else {
                null
            }
        val taskConversationBar: (@Composable () -> Unit)? = taskConversationHeadRoute?.let { route -> { route(TaskHeadPlacement.Page) } }
        val taskConversationFloat: (@Composable () -> Unit)? = taskConversationHeadRoute?.let { route -> { route(TaskHeadPlacement.PullDown) } }
        val taskConversationHeadRow =
            rememberTaskConversationHeadRow(taskHeadScrollState) { taskConversationHeadRoute?.invoke(TaskHeadPlacement.FirstRow) }
        // Web reads external_channels_v1 for the current server: the one whose copy is open.
        val externalChannelsEnabled =
            rememberServerFeatureLatched(sharedStores.serverFeatureFlags, threadMembersServerId, EXTERNAL_CHANNELS_FEATURE_FLAG_KEY)
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
                externalChannelsEnabled = externalChannelsEnabled,
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
                    if (externalChannelsEnabled && action == ChannelSettingsAction.Disconnect) {
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
        val settingsPageLinkKey = "thread-settings:$threadInstanceKey"
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
                collapseConversationId = payload.longMessageCollapseConversationId(),
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
            memberAvatarResolver = messageAvatarResolver,
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
            pendingTaskConvertMessageIds = pendingTaskConvertMessageIds,
            markdownReferencePendingActionId = markdownReferencePending.busyActionId,
            attachmentOpenStates = attachmentOpenStates,
            composerSendKeyEnabled = composerSendKeyEnabled,
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
            onThreadEvent = ThreadEventHandler(dispatch = ThreadPageContent@{ action, itemId, attachmentId, tab, markdownActionId, eventThreadChannelId ->
                // An in-app open of a Raft web link target (a permalink, a spec card's channel).
                fun openThreadWebLinkTargetInApp(target: RaftThreadWebLinkTarget) {
                    val serverId = currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
                    val directory =
                        listOfNotNull(
                            serverId?.let { conversationIdentityProvider?.resolveProjection(it, target.channelId) }
                        ).toSharedConversationDirectoryState()
                    val request =
                        target.resolveMarkdownOpenRequest(
                            serverId = serverId?.value.orEmpty(),
                            conversationDirectory = directory
                        )
                    val loadingPayload = request?.let { openRequest ->
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
                            "thread_markdown_open_rejected reason=identity_unresolved " +
                                "channelId=${target.channelId} parentMessageId=${target.threadParentMessageId.orEmpty()}"
                        )
                        bridge.toast(localizedUnableToOpenTarget(target.channelId.take(32)))
                        return
                    }
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
                if (action == ThreadOpenPlanBillingEvent) {
                    bridge.log("thread_history_paywall_open_billing offersUpgrade=${settingsBillingOffersUpgrade()}")
                    // Upgrade to Pro: the upgrade dialog for the 30-day window (web HistoryPaywall
                    // `history_30d`). iOS view-only Billing: "View your plan", the view-only page.
                    if (settingsBillingOffersUpgrade()) {
                        pager.openRaftUpgradeDialog(RaftUpgradeGate.History30d, storeScope)
                    } else {
                        pager.openSettingsBillingPage(storeScope)
                    }
                    return@ThreadPageContent
                }
                if (action == ThreadOpenUpgradeEvent) {
                    // A banner's Upgrade (web ChannelLimitBanners): [itemId] is the gate's wire name.
                    RaftUpgradeGate.entries.firstOrNull { it.wire == itemId }?.let { gate ->
                        pager.openRaftUpgradeDialog(gate, storeScope)
                    }
                    return@ThreadPageContent
                }
                if (handleThreadStoreRetryEvent(action, threadViewModel::dispatch)) {
                    return@ThreadPageContent
                }
                if (!threadEventAllowed(renderSlice.writeActionsEnabled, action)) {
                    bridge.log("thread_write_action_blocked action=$action reason=read_only_access_fallback")
                    return@ThreadPageContent
                }
                when {
                    action == "openAttachment" && !attachmentId.isNullOrBlank() -> {
                        val imageGroup =
                            renderedTimelineSlice.findThreadImageAttachmentGroup(attachmentId)
                        if (imageGroup != null) {
                            openImagePreview(imageGroup)
                        } else {
                            val attachment =
                                renderedTimelineSlice.findThreadAttachment(attachmentId)
                                    ?: RaftThreadAttachmentItem(id = attachmentId, name = "Attachment")
                            openAttachmentNoPreview(attachment)
                        }
                    }
                    action == "downloadAttachment" && !attachmentId.isNullOrBlank() -> {
                        val attachment =
                            renderedTimelineSlice.findThreadAttachment(attachmentId)
                                ?: RaftThreadAttachmentItem(id = attachmentId, name = "Attachment")
                        saveAttachmentExternally(attachment)
                    }
                    action == "openFileSource" && !itemId.isNullOrBlank() -> {
                        val fileItem =
                            filesViewModel?.state?.value?.payload?.items?.firstOrNull { it.attachmentId == itemId }
                        val sourceMessageId = fileItem?.threadFileSourceFocusMessageId()
                        if (sourceMessageId == null) {
                            bridge.toast(fileSourceUnavailableLabel)
                            return@ThreadPageContent
                        }
                        // "Jump to Original Message": the file's message is often outside the loaded
                        // window; a jump loads around it first (the page shows the load) and focuses it.
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = sourceMessageId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                                unreadThreadPill = false,
                            )
                        )
                    }
                    action == ThreadOpenUnreadThreadPastHistoryAction && !itemId.isNullOrBlank() &&
                        !eventThreadChannelId.isNullOrBlank() -> {
                        // Web #11494: the parent is past the plan's history window (the channel shows
                        // the paywall there) but replies are inside it: open the thread, which reads it.
                        // Its parent is not loaded, so nothing seeds the reply page; it loads canonically.
                        bridge.log(
                            "thread_unread_thread_jump_open_past_history parent=${itemId.take(8)} " +
                                "thread=${eventThreadChannelId.take(8)}"
                        )
                        sharedHomeStore?.dispatch(SharedHomeIntent.MarkFollowedThreadRead(eventThreadChannelId))
                        onOpenThreadPayload(
                            renderedThread.replyThreadLoadingPayload(
                                parentMessageId = itemId,
                                parentChannelId = renderedThread.replyParentChannelIdForNestedOpen(),
                                threadChannelId = eventThreadChannelId,
                            )
                        )
                    }
                    action == ThreadJumpToUnreadThreadPastHistoryAction && !itemId.isNullOrBlank() &&
                        !eventThreadChannelId.isNullOrBlank() -> {
                        // Web #11494: the parent and its replies are past the window; the jump lands on
                        // the paywall, where the thread can't be opened, so read it now: the count drops
                        // and the next tap moves on instead of staying on this thread.
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = itemId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                            )
                        )
                        sharedHomeStore?.dispatch(SharedHomeIntent.MarkFollowedThreadRead(eventThreadChannelId))
                    }
                    action == ThreadJumpToUnreadThreadParentAction && !itemId.isNullOrBlank() -> {
                        // The unread-thread pill: focus (scroll to and highlight) the parent
                        // message through the route focus session, as a search hit does; a parent
                        // that is not loaded is loaded around first (web jumpToMessage). The target
                        // thread's replies are then prewarmed so opening it renders at once (artin).
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = itemId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                                prewarmThreadChannelId = eventThreadChannelId,
                            )
                        )
                    }
                    (action == ThreadOutlineJumpAction || action == ThreadFindJumpAction) && !itemId.isNullOrBlank() -> {
                        // The thread timeline (botiverse/slock #13534), and Search in this thread's
                        // match the page hasn't loaded (web useThreadReplyContext): focus the message
                        // as a search hit does, loading around it first when it isn't loaded; top-aligned.
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = itemId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                                unreadThreadPill = false,
                            )
                        )
                    }
                    action == "openAttachmentComments" && !attachmentId.isNullOrBlank() -> {
                        openAttachmentComments(attachmentId)
                    }
                    action == "copyMessage" && !itemId.isNullOrBlank() -> {
                    val body = renderedTimelineSlice.threadMessageById(itemId)?.body.orEmpty()
                    // Web MessageItem Copy Markdown: the menu closes, no toast (artin 2026-11-02).
                    if (body.isNotBlank()) bridge.copyToPasteboard(body)
                }
                    action == "copyLocalSendFailureDetails" && !itemId.isNullOrBlank() -> {
                        val failedMessage = renderedTimelineSlice.threadMessageById(itemId)
                        val detail = failedMessage?.let {
                            threadFailureDiagnosticDetail(it.pendingSendErrorCode, it.pendingSendErrorMessage)
                        }
                        if (!detail.isNullOrBlank()) {
                            bridge.copyToPasteboard(detail)
                            bridge.toast(failureDetailsCopiedLabel)
                        }
                    }
                action == "copyShareSelectionMarkdown" && !itemId.isNullOrBlank() -> {
                    bridge.copyToPasteboard(itemId)
                    bridge.briefToast(copiedLabel)
                }
                action == "copyShareSelectionLinks" && !itemId.isNullOrBlank() -> {
                    bridge.copyToPasteboard(itemId)
                    bridge.toast(linksCopiedLabel)
                }
                action == "shareSelectionImageSaved" -> {
                    bridge.toast(imageSavedLabel)
                }
                action == "shareSelectionExportFailed" -> {
                    bridge.toast(unableToExportImageLabel)
                }
                action == "forwardMessagesSent" -> {
                    val target = itemId?.takeIf { it.isNotBlank() }
                    bridge.toast(
                        if (target != null) {
                            localizedForwardCompleted(localizedForwardModeLabel(tab), target)
                        } else {
                            Res.string.thread_forward_badge.desc().resolve()
                        }
                    )
                }
                action == "forwardMessagesFailed" -> {
                    // The event carries a failure code; the toast says it in this locale and only
                    // the log keeps the code itself.
                    val failure = threadForwardFailure(itemId)
                    if (failure == ThreadForwardFailure.Generic) {
                        bridge.log("thread_forward_failed code=${itemId.orEmpty()}")
                    }
                    bridge.toast(failure.messageRes.desc().resolve())
                }
                action == "forwardJoinConfirmationRequired" -> {
                    bridge.toast(
                        itemId?.takeIf { it.isNotBlank() }?.let { localizedForwardJoinFirst(it) } ?: unableToForwardLabel
                    )
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
                        // Web MessageItem Copy Link: no toast.
                        bridge.copyToPasteboard(permalink)
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
                    if (threadMessageOpenIgnoredForSurface(renderedThread.surfaceKind, renderedThread.channelKind)) {
                        bridge.log(
                            "thread_reply_open result=ignored_already_in_reply_thread " +
                                "messageId=$itemId instance=${renderedThread.instanceId}"
                        )
                        return@ThreadPageContent
                    }
                    val parentMessage =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (parentMessage == null) {
                        bridge.toast(unableToOpenRepliesLabel)
                        return@ThreadPageContent
                    }
                    val parentReplyMetadata = replyMetadataByParentId.threadReplyMetadataFor(parentMessage)
                    val parentChannelId = renderedThread.replyParentChannelIdForNestedOpen()
                    val provenance =
                        dataSource.replyCacheProvenanceState.value
                            .currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
                    if (
                        provenance == null ||
                        !dataSource.rememberReplyParentCandidate(
                            provenance = provenance,
                            serverId = currentServerId,
                            parentChannelId = parentChannelId,
                            parentMessage = parentMessage,
                            threadChannelId = parentReplyMetadata.threadChannelId
                        )
                    ) {
                        bridge.logWarn("thread_reply_parent_seed_rejected reason=stale_provenance")
                        bridge.toast(unableToOpenRepliesLabel)
                        return@ThreadPageContent
                    }
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
                    parentReplyMetadata.threadChannelId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { threadChannelId ->
                            coroutineScope.launch {
                                // Opening the whole reply scope clears the whole inline unread projection.
                                // The bounded preview cannot prove the authoritative latest seq (newer system
                                // rows may be intentionally hidden), so dispatch the canonical read-all intent.
                                val result = dataSource.markVisibleChannelRead(threadChannelId, latestSeq = null)
                                bridge.log(
                                    "thread_inline_reply_read_dispatch channelId=$threadChannelId " +
                                        "target=all result=${result::class.simpleName.orEmpty()}"
                                )
                            }
                        }
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
                            // Web #11687: the thread's unread leaves Home at once, back if this fails.
                            val unfollowResult =
                                unfollowThreadProjectingHome(
                                    ChannelId(threadChannelId),
                                    project = { intent -> sharedHomeStore?.dispatch(intent) },
                                    unfollow = dataSource::unfollowThread,
                                )
                            when (val result = unfollowResult) {
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
                action == MARK_THREAD_UNREAD_ACTION && !attachmentId.isNullOrBlank() -> {
                    // botiverse/slock #11687: the reads of this page are held for it until it leaves
                    // (#14222: a followed thread marked from a channel's list is written, not held).
                    threadViewModel.dispatch(SharedThreadIntent.MarkThreadUnread(attachmentId))
                }
                action == MARK_CONVERSATION_UNREAD_ACTION -> {
                    // botiverse/slock #14222: this page's channel or DM, held unread while it stays here.
                    threadViewModel.dispatch(SharedThreadIntent.MarkConversationUnread)
                }
                action == "openParentMessage" -> {
                    val sideParentFocus =
                        adaptiveCloseRequest?.let { source ->
                            adaptiveFocusWorkspaceStore.snapshot().sideThreadParentFocus(source)
                        }
                    if (sideParentFocus != null) {
                        adaptiveFocusManager.clearFocus(force = true)
                        adaptiveKeyboardController?.hide()
                        // The original, its history start (behind the window), or nothing (deleted): ThreadHeaderOriginalTitle.kt.
                        postSideThreadParentFocus(sideParentFocus, renderedThread, bridge, eventBus)
                    } else if (!onOpenParentThread(renderedThread, currentSession?.currentServerId)) {
                        bridge.logWarn(
                            "thread_parent_navigation_rejected reason=parent_conversation_unresolved " +
                                "threadId=${renderedThread.sourceThreadId.orEmpty()} " +
                                "parentChannelId=${renderedThread.parentChannelId.orEmpty()}"
                        )
                        bridge.toast(unableToOpenParentMessageLabel)
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
                    val savedOwnerSession = currentSession
                    coroutineScope.launch {
                        val result =
                            if (nextSaved) {
                                dataSource.saveMessage(itemId)
                            } else {
                                dataSource.unsaveMessage(itemId)
                            }
                        when (result) {
                            is DataResult.Content -> {
                                // Activity's Saved filter reloads on an accepted save or unsave.
                                sharedStores.homeSavedStateCache.recordSavedWrite()
                                // A confirmed unsave leaves the Saved list's stored copy (a save shows up
                                // with the list's next refresh: the row needs the server's saved entry).
                                val savedServerId = savedOwnerSession?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
                                if (!nextSaved && savedOwnerSession != null && savedServerId != null) {
                                    sharedStores.homeSavedStateCache.recordMessageUnsaved(
                                        serverId = savedServerId,
                                        userId = savedOwnerSession.user.id,
                                        messageId = MessageId(itemId),
                                    )
                                }
                            }
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
                    val actionCardMessage = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    val actionCard = actionCardMessage?.threadActionCard()
                    if (actionCard == null) {
                        bridge.toast(actionUnavailableLabel)
                        return@ThreadPageContent
                    }
                    // web ActionCard: a frozen card's button is disabled; nothing runs.
                    if (actionCard.isFrozen) return@ThreadPageContent
                    // Expired (by state, or past `expiresAt` on this clock) or withdrawn: no run, and no
                    // create-agent / create-channel / add-members dialog (they create, then mark it executed).
                    if (threadActionCardCommitBlockedByEnd(actionCard, defaultNowMillis())) return@ThreadPageContent
                    // A reconfirm card's button reconfirms it (back to prepared), whatever its type.
                    if (actionCard.needsReconfirmation) {
                        pendingActionCardIds = pendingActionCardIds + itemId
                        coroutineScope.launch {
                            try {
                                when (val result = dataSource.commitThreadActionCard(renderedThread, itemId, actionCard)) {
                                    is DataResult.Content ->
                                        threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(itemId, result.data))
                                    is DataResult.Empty -> bridge.toast(actionCardReconfirmFailedLabel)
                                    is DataResult.Error ->
                                        if (result.error.isActionCardExpiredRefusal()) {
                                            // The server refused it as expired (409): the card says so itself.
                                            threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(itemId, actionCard.markedExpired()))
                                        } else {
                                            bridge.toast(actionCardReconfirmFailedLabel)
                                        }
                                    is DataResult.NoPermission -> {
                                        if (routeAuthRequired(result)) return@launch
                                        bridge.toast(actionCardReconfirmFailedLabel)
                                    }
                                }
                            } finally {
                                pendingActionCardIds = pendingActionCardIds - itemId
                            }
                        }
                        return@ThreadPageContent
                    }
                    if (actionCard.isFeedbackSubmitAction()) {
                        val feedbackCardEntry =
                            raftFeedbackCardEntryOrNull(
                                messageId = itemId,
                                card = actionCard,
                                viewingServerId = currentServerId,
                            )
                        if (feedbackCardEntry == null) {
                            actionCardSpecHost.openUnsupportedInWeb(itemId, actionCard.type)
                        } else {
                            // The native form on the card's draft (the web page's card flow).
                            bridge.log("thread_feedback_card_open messageId=$itemId")
                            pager.openSettingsFeedbackCardPage(storeScope, feedbackCardEntry)
                        }
                        return@ThreadPageContent
                    }
                    if (actionCard.isInstallMarketplaceAppAction()) {
                        installAppDialogStore.dispatch(ThreadInstallAppActionIntent.Open(itemId, actionCard))
                        return@ThreadPageContent
                    }
                    if (!actionCard.isSupportedCommitAction()) {
                        // Not runnable in the app: its message on the web, traced by type.
                        actionCardSpecHost.openUnsupportedInWeb(itemId, actionCard.type)
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
                            var createdChannel: HomeChannelRow? = null
                            val outcome =
                                dataSource.commitThreadActionCard(renderedThread, itemId, actionCard, actionCardStepUp) { channel ->
                                    createdChannel = channel
                                    // As submitCreateChannel: Home lists it now, whatever marking the card answers.
                                    sharedHomeStore?.dispatch(SharedHomeIntent.ChannelCreated(channel))
                                    sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                                }
                            // "Verify it's you" closed: the card stays as it was, nothing is shown.
                            val result = (outcome as? SecondFactorStepUpOutcome.Completed)?.result ?: return@launch
                            when (result) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyActionCard(itemId, result.data)
                                    )
                                    bridge.toast(actionCardSuccessLabels[actionCard.type] ?: genericActionCardSuccessLabel)
                                }
                                is DataResult.Empty -> {
                                    bridge.toast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel)
                                }
                                is DataResult.Error -> {
                                    if (result.error.isActionCardExpiredRefusal()) {
                                        // The server refused it as expired (409): the card says so itself.
                                        threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(itemId, actionCard.markedExpired()))
                                    } else {
                                        bridge.toast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel)
                                    }
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel)
                                }
                            }
                            // Web CreateChannelDialog `finish`: the new channel opens; marking the card doesn't hold it.
                            createdChannel?.let { channel ->
                                val next =
                                    buildAppShellThreadPayload(
                                        conversation = channel.toThreadLaunchConversationIdentity(),
                                        timeContext = renderedThread.toRaftTimeCapabilities().staticContext(),
                                        alignOwnMessagesRight = renderedThread.alignOwnMessagesRight,
                                        compactConsecutiveMessages = renderedThread.compactConsecutiveMessages,
                                        onWarn = bridge::logWarn,
                                    )
                                if (next != null) {
                                    onOpenThreadPayload(next)
                                } else {
                                    bridge.logWarn("action_card_created_channel_open_rejected reason=identity_unresolved")
                                }
                            }
                        } finally {
                            pendingActionCardIds = pendingActionCardIds - itemId
                        }
                    }
                }
                action == "toggleReaction" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    val reactionKey = threadReactionPendingKey(itemId, tab)
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
                    pendingReactionVisuals =
                        pendingReactionVisuals +
                            (reactionKey to previousReactions.toPendingReactionVisual(tab, nextSelected))
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
                            pendingReactionVisuals = pendingReactionVisuals - reactionKey
                        }
                    }
                }
                action == "openSourceTask" && !itemId.isNullOrBlank() -> {
                    // Task v2: a row of the list under a message opens its task (web useOpenTask):
                    // its own conversation, or a pre-v2 task's host thread.
                    val task = linkedTasksProjection.sourceTaskById(itemId)
                    if (task == null) {
                        bridge.logWarn("thread_task_open_rejected reason=source_task_missing taskId=$itemId")
                        bridge.toast(unableToOpenTaskLabel)
                    } else {
                        openCanonicalTask(task, "message_task_list")
                    }
                }
                action == "openTask" && !itemId.isNullOrBlank() -> {
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    try {
                        markdownTaskOpenRequestGate.nextRequest()
                        val canonicalTask = linkedTasksProjection.taskById(itemId)
                        val parentMessage = renderedTimelineSlice.findTaskParentMessage(itemId, linkedTasksProjection)
                        val parentReplyMetadata =
                            parentMessage?.let { message ->
                                replyMetadataByParentId.threadReplyMetadataFor(message)
                            }
                        val parentChannelId = renderedThread.replyParentChannelIdForNestedOpen()
                        val provenance =
                            dataSource.replyCacheProvenanceState.value
                                .currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
                        val loadedParentProvenanceAccepted =
                            parentMessage != null &&
                                parentReplyMetadata != null &&
                                provenance != null &&
                                dataSource.rememberReplyParentCandidate(
                                    provenance = provenance,
                                    serverId = currentServerId,
                                    parentChannelId = parentChannelId,
                                    parentMessage = parentMessage,
                                    threadChannelId = parentReplyMetadata.threadChannelId
                                )
                        if (parentMessage != null && !loadedParentProvenanceAccepted) {
                            bridge.logWarn("thread_task_parent_seed_rejected reason=stale_provenance")
                        }
                        var useLoadedParent = false
                        arbitrateThreadTaskOpen(
                            loadedParentAvailable = parentMessage != null,
                            loadedParentProvenanceAccepted = loadedParentProvenanceAccepted,
                            canonicalTask = canonicalTask
                        ).dispatch(
                            onUseLoadedParent = { useLoadedParent = true },
                            onOpenCanonical = { task, reason ->
                                bridge.log(
                                    "thread_task_open result=fallback_to_canonical reason=${reason.diagnosticValue} " +
                                        "taskId=$itemId"
                                )
                                openCanonicalTask(
                                    task,
                                    if (markdownActionId == null) "inline_task" else "markdown_task"
                                )
                            },
                            onFailClosed = { reason ->
                                bridge.logWarn(
                                    "thread_task_open_rejected reason=${reason.diagnosticValue} taskId=$itemId"
                                )
                                bridge.toast(unableToOpenTaskLabel)
                            }
                        )
                        if (!useLoadedParent) {
                            return@ThreadPageContent
                        }
                        val loadedParentMessage = parentMessage ?: return@ThreadPageContent
                        val loadedParentReplyMetadata = parentReplyMetadata ?: return@ThreadPageContent
                        val loadingPayload =
                            renderedThread.replyThreadLoadingPayload(
                                parentMessageId = loadedParentMessage.id,
                                parentChannelId = parentChannelId,
                                threadChannelId = loadedParentReplyMetadata.threadChannelId,
                                focusedMessageId = loadedParentReplyMetadata.firstUnreadReplyMessageId
                            )
                        threadViewModel.dispatch(
                            SharedThreadIntent.ApplyReplyThreadRead(
                                parentMessageId = loadedParentMessage.id,
                                threadChannelId = loadedParentReplyMetadata.threadChannelId
                            )
                        )
                        bridge.log(
                            "thread_reply_open action=task parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                                "parentMessageId=${loadedParentMessage.id} taskId=$itemId replyCount=${loadedParentReplyMetadata.replyCount} " +
                                "knownThreadChannelId=${loadedParentReplyMetadata.threadChannelId.orEmpty()} " +
                                "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                        )
                        onOpenThreadPayload(loadingPayload)
                    } finally {
                        finishMarkdownReferencePending(markdownPendingToken)
                    }
                }
                action == "openMarkdownTaskNumber" && !itemId.isNullOrBlank() -> {
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    // `#7`, or a conversation-qualified `#proj:#7` / `dm:@peer:#7` (Web task refs).
                    val qualifiedTaskRef = parseRaftMarkdownTaskRefLabel(itemId)?.takeIf { it.qualified }
                    val taskNumber = qualifiedTaskRef?.taskNumber ?: itemId.removePrefix("#").trim().toIntOrNull()
                    if (taskNumber == null || currentServerId.isNullOrBlank()) {
                        finishMarkdownReferencePending(markdownPendingToken)
                        bridge.log("thread_markdown_task_resolve result=unresolvable label=${itemId.take(32)}")
                        return@ThreadPageContent
                    }
                    val requestGeneration = markdownTaskOpenRequestGate.nextRequest()
                    val resolveStartedAt = TimeSource.Monotonic.markNow()
                    bridge.log(
                        "thread_markdown_task_resolve result=start taskNumber=$taskNumber " +
                            "generation=$requestGeneration"
                    )
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val resolveChannelId =
                                if (qualifiedTaskRef == null) {
                                    markdownTaskContextChannelId
                                } else {
                                    dataSource.markdownTaskRefChannelId(
                                        actionId = markdownActionId,
                                        currentPayload = renderedThread,
                                        conversationLabel =
                                            qualifiedTaskRef.channelName ?: qualifiedTaskRef.directMessagePeerName.orEmpty(),
                                        requiredKind =
                                            if (qualifiedTaskRef.directMessagePeerName != null) {
                                                ConversationKind.DirectMessage
                                            } else {
                                                null
                                            }
                                    )
                                }
                            val resolved =
                                openMarkdownTaskNumberPayload(
                                    serverId = ServerId(currentServerId),
                                    taskNumber = taskNumber,
                                    currentPayload = renderedThread,
                                    currentTaskChannelId = resolveChannelId,
                                    tasksDataSource = tasksDataSource,
                                    tasksDataStore = tasksDataStore,
                                    taskV2Enabled = taskV2Enabled,
                                    conversationIdentityProvider = conversationIdentityProvider,
                                    referenceResolver = dataSource.referenceResolver,
                                    actionId = markdownActionId
                                )
                            val result = markdownTaskOpenRequestGate.accept(requestGeneration, resolved)
                            if (result == null) {
                                bridge.log(
                                    "thread_markdown_task_resolve result=stale taskNumber=$taskNumber " +
                                        "generation=$requestGeneration " +
                                        "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                )
                                return@launch
                            }
                            when (result) {
                                is MarkdownTaskNumberOpenResult.Found -> {
                                    bridge.log(
                                        "thread_markdown_task_open taskNumber=$taskNumber taskId=${result.task.id.value} " +
                                            "source=${result.source.name} generation=$requestGeneration " +
                                            "resolveDurationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds} " +
                                            "parentChannelId=${result.payload.parentChannelId.orEmpty()} " +
                                            "parentMessageId=${result.payload.parentMessageId.orEmpty()} " +
                                            "threadId=${result.payload.streamChannelId.orEmpty()}"
                                    )
                                    onOpenThreadPayload(result.payload)
                                }
                                is MarkdownTaskNumberOpenResult.OpensInPlace -> {
                                    openCanonicalTask(result.task, "markdown_task")
                                }
                                MarkdownTaskNumberOpenResult.NotFound -> {
                                    // Web `openTaskByChannelNumber`: a ref that names no task of
                                    // its conversation opens nothing and says nothing.
                                    bridge.log(
                                        "thread_markdown_task_resolve result=not_found taskNumber=$taskNumber " +
                                            "generation=$requestGeneration " +
                                            "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                    )
                                }
                                MarkdownTaskNumberOpenResult.AuthRequired -> {
                                    bridge.log(
                                        "thread_markdown_task_resolve result=auth_required taskNumber=$taskNumber " +
                                            "generation=$requestGeneration " +
                                            "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                    )
                                    routeAuthRequired(
                                        DataResult.NoPermission(PermissionBlocker(code = "tasks_401"))
                                    )
                                }
                                MarkdownTaskNumberOpenResult.Failed -> {
                                    bridge.log(
                                        "thread_markdown_task_resolve result=failed taskNumber=$taskNumber " +
                                            "generation=$requestGeneration " +
                                            "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                    )
                                    bridge.errorToast(localizedUnableToOpenTaskNumber(taskNumber))
                                }
                            }
                        } finally {
                            finishMarkdownReferencePending(markdownPendingToken)
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
                    val open = raftMarkdownUrlOpen(itemId, sharedStores.apiEnvironmentStore.current().webUrl)
                    val serverId = currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
                    if (open.target != null && open.opensOnThisPage { channelId -> serverId?.let { conversationIdentityProvider?.resolveProjection(it, channelId) } != null }) {
                        openThreadWebLinkTargetInApp(open.target)
                    } else {
                        // Any other Raft link (a conversation the shell resolves, a page without a
                        // route here) or an outside one: the link navigator's rule.
                        linkNavigator.open(open.url)
                    }
                }
                // A4: a spec card's navigate to a channel, as a channel link opens it.
                action == "openActionCardChannel" && !itemId.isNullOrBlank() ->
                    openThreadWebLinkTargetInApp(RaftThreadWebLinkTarget(channelId = itemId))
                action == "openMarkdownTag" && !itemId.isNullOrBlank() -> {
                    val localTarget = renderedThread.resolveMarkdownMentionOpenTarget(itemId, emptyList())
                    if (localTarget != null) {
                        onOpenMemberProfile(renderedThread, renderedTimelineSlice, localTarget.identity)
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
                            onOpenMemberProfile(renderedThread, renderedTimelineSlice, target.identity)
                        }
                    }
                }
                (action == "openMarkdownChannel" || action == "openMarkdownDirectMessage") && !itemId.isNullOrBlank() -> {
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    val openDirectMessage = action == "openMarkdownDirectMessage"
                    coroutineScope.launch {
                        try {
                            val loadingPayload =
                                dataSource.markdownChannelLaunchPayload(
                                    currentPayload = renderedThread,
                                    channelLabel = itemId,
                                    requiredKind =
                                        if (openDirectMessage) {
                                            ConversationKind.DirectMessage
                                        } else {
                                            null
                                        }
                                )
                            if (loadingPayload == null) {
                                bridge.errorToast(
                                    localizedUnableToOpenTarget(
                                        if (openDirectMessage) {
                                            "dm:@${itemId.take(28)}"
                                        } else {
                                            "#${itemId.removePrefix("#").take(31)}"
                                        }
                                    )
                                )
                            } else {
                                val currentRouteIdentity = renderedThread.resolveThreadRouteIdentity()
                                val targetRouteIdentity = loadingPayload.resolveThreadRouteIdentity()
                                if (currentRouteIdentity.canRetainThreadPageFor(targetRouteIdentity)) {
                                    val targetFocusedMessageId = loadingPayload.focusedMessageId
                                    if (!targetFocusedMessageId.isNullOrBlank()) {
                                        threadViewModel.dispatch(
                                            SharedThreadIntent.FocusMessage(
                                                messageId = targetFocusedMessageId,
                                                switchToTab = loadingPayload.selectedTab
                                            )
                                        )
                                    }
                                } else {
                                    onOpenThreadPayload(loadingPayload)
                                }
                            }
                        } finally {
                            finishMarkdownReferencePending(markdownPendingToken)
                        }
                    }
                }
                (action == "openMarkdownThreadRef" || action == "openMarkdownDirectMessageThreadRef" || action == "openMarkdownMessageRef") &&
                    !itemId.isNullOrBlank() -> {
                    val openMessageRef = action == "openMarkdownMessageRef"
                    val unableToOpenRefLabel =
                        if (openMessageRef) localizedUnableToOpenTarget(itemId.take(32)) else unableToOpenThreadRefLabel
                    val refToastTarget =
                        if (openMessageRef || tab.isNullOrBlank()) {
                            itemId.take(32)
                        } else {
                            "#${itemId.removePrefix("#").take(31)}:${tab.take(12)}"
                        }
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    // How long the open waits for its target: a local hit opens with no spinner;
                    // only a miss fetches messages/context, and the chip is busy for that fetch alone.
                    val resolveStartedAt = TimeSource.Monotonic.markNow()
                    var resolveOutcome = "cancelled"
                    bridge.log("thread_markdown_ref_resolve result=start label=${itemId.take(32)}")
                    coroutineScope.launch {
                        try {
                            val provenance =
                                dataSource.replyCacheProvenanceState.value
                                    .currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
                            if (provenance == null) {
                                resolveOutcome = "no_provenance"
                                bridge.toast(unableToOpenRefLabel)
                                return@launch
                            }
                            when (
                                val result =
                                    if (openMessageRef) {
                                        parseRaftMarkdownMessageRefLabel(itemId)
                                            ?.let { ref ->
                                                dataSource.markdownMessageRefLaunchPayload(
                                                    provenance = provenance,
                                                    currentPayload = renderedThread,
                                                    ref = ref,
                                                    actionId = markdownActionId
                                                )
                                            }
                                            ?: DataResult.Empty(EmptyReason.NoMessages)
                                    } else {
                                        dataSource.markdownThreadRefLaunchPayload(
                                            provenance = provenance,
                                            currentPayload = renderedThread,
                                            channelLabel = itemId,
                                            threadShortId = tab,
                                            requiredParentKind =
                                                if (action == "openMarkdownDirectMessageThreadRef") {
                                                    ConversationKind.DirectMessage
                                                } else {
                                                    null
                                                },
                                            actionId = markdownActionId
                                        )
                                    }
                            ) {
                                is DataResult.Content -> {
                                    resolveOutcome = "opened"
                                    bridge.log(
                                        "thread_markdown_ref_open label=${itemId.take(32)} " +
                                            "parentChannelId=${result.data.parentChannelId.orEmpty()} " +
                                            "parentMessageId=${result.data.parentMessageId.orEmpty()} " +
                                            "threadId=${result.data.streamChannelId.orEmpty()} " +
                                            "focusedMessageId=${result.data.focusedMessageId.orEmpty()}"
                                    )
                                    val currentRouteIdentity = renderedThread.resolveThreadRouteIdentity()
                                    val targetRouteIdentity = result.data.resolveThreadRouteIdentity()
                                    if (currentRouteIdentity.canRetainThreadPageFor(targetRouteIdentity)) {
                                        val targetFocusedMessageId = result.data.focusedMessageId
                                        if (!targetFocusedMessageId.isNullOrBlank()) {
                                            threadViewModel.dispatch(
                                                if (openMessageRef) {
                                                    // The named message may be outside the loaded window.
                                                    SharedThreadIntent.JumpToMessage(
                                                        messageId = targetFocusedMessageId,
                                                        switchToTab = result.data.selectedTab
                                                    )
                                                } else {
                                                    SharedThreadIntent.FocusMessage(
                                                        messageId = targetFocusedMessageId,
                                                        switchToTab = result.data.selectedTab
                                                    )
                                                }
                                            )
                                        }
                                    } else {
                                        onOpenThreadPayload(result.data)
                                    }
                                }
                                // A failure never navigates: the toast names why and the page stays.
                                is DataResult.Empty -> {
                                    val failure = result.sharedReferenceFailure()
                                    resolveOutcome = if (failure == SharedReferenceFailure.OtherServer) "other_server" else "not_found"
                                    bridge.errorToast(localizedReferenceFailure(failure, refToastTarget, unableToOpenRefLabel))
                                }
                                is DataResult.Error -> {
                                    resolveOutcome = "error"
                                    bridge.errorToast(unableToOpenRefLabel, result.error)
                                }
                                is DataResult.NoPermission -> {
                                    resolveOutcome = "no_permission"
                                    if (!routeAuthRequired(result)) {
                                        bridge.errorToast(
                                            localizedReferenceFailure(
                                                result.sharedReferenceFailure(),
                                                refToastTarget,
                                                unableToOpenRefLabel
                                            )
                                        )
                                    }
                                }
                            }
                        } finally {
                            finishMarkdownReferencePending(markdownPendingToken)
                            bridge.log(
                                "thread_markdown_ref_resolve result=$resolveOutcome label=${itemId.take(32)} " +
                                    "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                            )
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
                action == "convertMessageToTask" && !itemId.isNullOrBlank() && taskV2Enabled -> {
                    // Task v2 (web MessageItem `openCreateFromMessage`): tasks are created FROM the
                    // message (several, under it), never converted from it.
                    val message = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (message == null || !renderedTimelineSlice.canConvertMessagesToTasks || !message.canCreateTaskFromMessage() || taskChannelId == null) {
                        bridge.toast(unableToCreateTaskLabel)
                        return@ThreadPageContent
                    }
                    createTaskFromMessage.open(
                        TaskCreateSourceMessage(
                            channelId = taskChannelId,
                            messageId = MessageId(message.id),
                            senderName = message.threadAvatarName().ifBlank { message.shownAuthorName() },
                            text = message.body,
                        )
                    )
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
                            when (
                                val result =
                                    convertMessageToCanonicalTask(
                                        serverId = currentServerId?.let(::ServerId),
                                        messageId = MessageId(itemId),
                                        tasksDataSource = tasksDataSource,
                                        tasksDataStore = tasksDataStore,
                                        diagnosticsLogger = { bridge.log(it) }
                                    )
                            ) {
                                is DataResult.Content -> {
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
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, status)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
                }
                // A move into Backlog with the reason asked for it (web TaskBacklogReasonPopover);
                // tab carries the reason, "" for none.
                action == "moveTaskToBacklog" && !itemId.isNullOrBlank() -> {
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, TaskStatus.Backlog, tab)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
                }
                // Task #258: inline task detail panel assignee row. tab carries the
                // assignee filter id ("unassigned" sentinel or member filter id);
                // resolve against the same current-channel options rendered by the picker.
                action == "setTaskAssignee" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    val assignee =
                        if (tab == TasksUnassignedAssigneeFilterId) {
                            null
                        } else {
                            resolveThreadTaskAssignee(tab, taskAssigneeOptions)
                        }
                    if (assignee == null && tab != TasksUnassignedAssigneeFilterId) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    val intent = linkedTasksProjection.assignUpdateIntent(itemId, assignee)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
                }
                action == "markTaskDone" && !itemId.isNullOrBlank() -> {
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, TaskStatus.Done)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
                }
                action == "reopenTask" && !itemId.isNullOrBlank() -> {
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, TaskStatus.Todo)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
                }
                action == "joinChannel" -> {
                    // slock #12426: a join already in flight sends no second request.
                    val joinKey = renderedThread.joinChannelTargetId()
                    if (joinKey != null && !ThreadChannelJoinsInFlight.begin(joinKey)) return@ThreadPageContent
                    coroutineScope.launch {
                        try {
                            // A task conversation's Join joins its task channel, not the conversation.
                            when (
                                val outcome =
                                    renderedThread.joinThreadChannel(dataSource::loadTaskConversationRef, dataSource::joinChannel)
                            ) {
                                is ThreadJoinChannelOutcome.Joined -> {
                                    val joinedId = outcome.channel.joinedChannelId
                                    threadViewModel.dispatch(SharedThreadIntent.ApplyChannelJoined(outcome.channel.pageChannelId.value))
                                    currentSession?.currentServerId?.takeIf(String::isNotBlank)?.let { serverId ->
                                        // Now a member: closing this page must not leave the room (the
                                        // conversation directory may still list the channel as not joined).
                                        realtimeRoomLeaseRegistry.markJoinedByUser(serverId, joinedId.value)
                                        sharedHomeStore?.dispatch(channelJoinedHomeIntent(renderedThread, serverId, joinedId))
                                        sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                                    }
                                    realtimeEvents.joinChannel(joinedId.value, currentSession?.currentServerId)
                                    bridge.toast(joinedChannelLabel)
                                }
                                ThreadJoinChannelOutcome.Unresolved -> bridge.toast(unableToJoinChannelLabel)
                                is ThreadJoinChannelOutcome.Failed ->
                                    when (val result = outcome.result) {
                                        is DataResult.NoPermission ->
                                            if (!routeAuthRequired(result)) bridge.errorToast(unableToJoinChannelLabel, result.blocker.traceId)
                                        is DataResult.Error -> bridge.errorToast(unableToJoinChannelLabel, result.error)
                                        else -> bridge.toast(unableToJoinChannelLabel)
                                    }
                            }
                        } finally {
                            joinKey?.let(ThreadChannelJoinsInFlight::end)
                        }
                    }
                }
                action == "selectTab" && !tab.isNullOrBlank() -> {
                    threadViewModel.dispatch(SharedThreadIntent.SelectTab(tab))
                }
                action == "openMembersPager" -> {
                    val serverId = threadMembersServerId
                    val channelId = threadMembersChannelId
                    if (serverId == null || channelId == null) {
                        bridge.logWarn(
                            "thread_members_pager_open_skip reason=missing_scope " +
                                "serverId=${currentSession?.currentServerId.orEmpty()} " +
                                "channelId=${renderedThread.sourceThreadId.orEmpty()}"
                        )
                        return@ThreadPageContent
                    }
                    onOpenMembersPager(renderedThread, serverId, channelId, storeScope, itemId == "add")
                }
                action == "openChannelSettings" -> {
                    val serverId = threadMembersServerId
                    val channelId = threadMembersChannelId
                    if (serverId == null || channelId == null) {
                        bridge.logWarn(
                            "channel_settings_open_skip reason=missing_scope " +
                                "serverId=${currentSession?.currentServerId.orEmpty()} " +
                                "channelId=${renderedThread.sourceThreadId.orEmpty()}"
                        )
                        return@ThreadPageContent
                    }
                    onOpenChannelSettings(serverId, channelId, storeScope, settingsPageLinkKey)
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
                action == "loadOlder" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadOlder)
                }
                action == "loadNewer" -> {
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
                        threadViewModel.dispatch(SharedThreadIntent.CancelLocalSend(message.id))
                    }
                }
                action == "retryLocalSend" && !itemId.isNullOrBlank() -> {
                    val message = renderedTimelineSlice.threadMessageById(itemId) ?: return@ThreadPageContent
                    val pendingContext = message.pendingSendContext ?: return@ThreadPageContent
                    if (!message.canRetryLocalSend()) {
                        return@ThreadPageContent
                    }
                    val sendAuthority =
                        dataSource.captureThreadLocalSendAuthority(
                            state = threadViewModel.state.value,
                            mountedOwnerProvenance = replyCacheProvenance
                        )
                            ?: run {
                                bridge.logWarn("thread_local_send_retry_rejected reason=missing_owner_provenance")
                                return@ThreadPageContent
                            }
                    val admitted =
                        threadViewModel.admitLocalSend(
                            message =
                                message
                                    .restampedForLocalSendRetry(
                                        buildThreadPendingSendTimestampLabel(bridge, payload.nowEpochMillis)
                                    )
                                    .copy(
                                        deliveryState = ThreadMessageDeliverySending,
                                        pendingSendErrorCode = null,
                                        pendingSendErrorMessage = null
                                    ),
                            expectedProvenance = sendAuthority.provenance,
                            expectedRoute = sendAuthority.route
                        )
                    if (!admitted) {
                        bridge.logWarn("thread_local_send_retry_rejected reason=owner_changed_before_admission")
                        return@ThreadPageContent
                    }
                    sendPendingLocalMessage(
                        localMessageId = message.id,
                        context = pendingContext,
                        startedFromComposer = false,
                        authority = sendAuthority
                    )
                }
                else -> {
                    bridge.log(
                        "thread_event_unsupported instanceId=${payload.instanceId} action=$action itemId=${itemId.orEmpty()} attachmentId=${attachmentId.orEmpty()} tab=${tab.orEmpty()}"
                    )
                }
                }
            }),
            onComposerSendRequest = sendRequest@ { request ->
                if (!threadComposerEventAllowed(renderSlice.writeActionsEnabled, "send")) {
                    bridge.log("thread_composer_action_blocked action=send reason=read_only_access_fallback")
                    return@sendRequest ThreadComposerSendAdmission.Rejected
                }
                handleComposerSendRequest(request)
            },
            onComposerDropFiles = { drop ->
                // Files dragged onto the composer (web MessageInput handleDrop): the web's folder
                // notice, then the host reads them as a File pick and the picker's admission
                // takes them (sizes, skipped files, upload), straight into the tray.
                if (!threadComposerEventAllowed(renderSlice.writeActionsEnabled, ThreadComposerDropAttachmentsAction)) {
                    bridge.log("thread_composer_action_blocked action=$ThreadComposerDropAttachmentsAction reason=read_only_access_fallback")
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
                if (!threadComposerEventAllowed(renderSlice.writeActionsEnabled, action)) {
                    bridge.log("thread_composer_action_blocked action=$action reason=read_only_access_fallback")
                    return@ThreadPageContent
                }
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
                "sendSnapshotUnavailable" -> {
                    bridge.logWarn(
                        "thread_composer_send_snapshot_unavailable " +
                            "instanceId=$composerInstanceId reason=${value.orEmpty()}"
                    )
                    bridge.toast(genericThreadErrorLabel)
                }
                "staleEditorCommandIgnored" -> {
                    bridge.log(
                        "thread_composer_editor_command_ignored instanceId=$composerInstanceId " +
                            "reason=local_editor_advanced localTextLen=${value?.length ?: -1} localAsTask=$asTask"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(
                            seedText = value,
                            seedAsTask = asTask,
                        )
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
                ThreadComposerSelectMentionEvent -> {
                    val mention = decodeThreadComposerMentionEvent(value)
                    bridge.log(
                        "thread_composer_select_mention instanceId=$composerInstanceId " +
                            "type=${mention?.type.orEmpty()} draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                    mention?.let { composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.SelectMention(it)) }
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
                "toggleOriginalImage" -> {
                    val enabled = asTask ?: !currentDraftState.originalImage
                    bridge.log(
                        "thread_composer_toggle_original_image instanceId=$composerInstanceId " +
                            "originalImage=$enabled draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.SetOriginalImage(enabled)
                    )
                }
                "send" -> {
                    handleComposerSendRequest(
                        ThreadComposerSendRequest(
                            editorSessionId = 0L,
                            value = TextFieldValue(value ?: currentDraftState.seedText),
                            asTask = asTask ?: currentDraftState.seedAsTask,
                        )
                    )
                }
                "dismissMention" -> {
                    value?.takeIf { it.isNotBlank() }?.let { resolutionId ->
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.DismissMentionAction(resolutionId))
                    }
                }
                "dismissMentionNotice" -> {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.ClearMentionActionNotice)
                }
                ThreadComposerDismissUnresolvedMentionsEvent -> {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.DismissUnresolvedMentions)
                }
                "addAllMentions" -> {
                    if (!pendingMentionActionBatchRequestGate.tryBegin()) return@ThreadPageContent
                    val eligibleActions =
                        eligiblePendingMentionActionBatch(
                            actions = currentDraftState.pendingMentionActions,
                            actionStates = currentDraftState.mentionActionStates,
                            removingIds = currentDraftState.removingMentionActionIds,
                            executingIds = currentDraftState.executingMentionActionIds
                        )
                    if (eligibleActions.size < 2) {
                        pendingMentionActionBatchRequestGate.finish()
                        return@ThreadPageContent
                    }
                    val resolutionIds = eligibleActions.map { it.resolutionId.trim() }
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.BeginMentionActionBatch(resolutionIds)
                    )
                    coroutineScope.launch {
                        try {
                            val requestAttempt =
                                runCatchingPreservingCancellation {
                                    dataSource.executePendingMentionActions(
                                        action = "add",
                                        resolutionIds = resolutionIds
                                    )
                                }
                            val requestResult = requestAttempt.getOrNull()
                            val requestFailureNotice =
                                localizedPendingMentionActionRequestFailure(
                                    result = requestResult,
                                    requestThrew = requestAttempt.isFailure,
                                    strings = pendingMentionActionBatchStrings
                                )
                            val results =
                                (requestResult as? ThreadApiResult.Success)?.data.orEmpty()
                            val outcome = pendingMentionActionBatchOutcome(resolutionIds, results)
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.FinishMentionActionBatch(
                                    requestedIds = outcome.requestedIds,
                                    deliveredIds = outcome.deliveredIds,
                                    noticeTitle = outcome.localizedNoticeTitle(pendingMentionActionBatchStrings),
                                    notice =
                                        requestFailureNotice
                                            ?: outcome.localizedFailureNotice(pendingMentionActionBatchStrings)
                                )
                            )
                            if (outcome.deliveredIds.isNotEmpty()) {
                                refreshComposerMemberSuggestionsAfterMembershipMutation()
                            }
                        } finally {
                            pendingMentionActionBatchRequestGate.finish()
                        }
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
                    val localState = if (verb == "add") "added" else "notified"
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.BeginMentionAction(
                            resolutionId = resolutionId,
                            state = localState
                        )
                    )
                    coroutineScope.launch {
                        val requestAttempt =
                            runCatchingPreservingCancellation {
                                dataSource.executePendingMentionActions(
                                    action = verb,
                                    resolutionIds = listOf(resolutionId)
                                )
                            }
                        val result = requestAttempt.getOrNull()
                        val rowResult =
                            (result as? ThreadApiResult.Success)
                                ?.data
                                ?.firstOrNull { it.resolutionId == resolutionId }
                        val succeeded = mentionActionSucceeded(verb, rowResult)
                        val notice =
                            if (succeeded) {
                                pendingAction.mentionActionDeliveredNotice(verb, composerSurface.mentionScopeChannelLabel())
                            } else {
                                localizedPendingMentionActionRequestFailure(
                                    result = result,
                                    requestThrew = requestAttempt.isFailure,
                                    strings = pendingMentionActionBatchStrings
                                ) ?: formatMentionActionStatusNotice(verb, rowResult, pendingMentionActionBatchStrings)
                            }
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.FinishMentionAction(
                                resolutionId = resolutionId,
                                notice = notice,
                                delivered = succeeded,
                                state = localState
                            )
                        )
                        if (succeeded && verb == "add") {
                            refreshComposerMemberSuggestionsAfterMembershipMutation()
                        }
                        if (succeeded) {
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
                ThreadComposerPasteAttachmentsAction -> {
                    // Desktop paste of files / an image: straight into the tray, as the web
                    // composer's paste does (no annotation editor). A copied image is compressed
                    // by the host (2048 px / JPEG 82, as editor output on mobile) unless the
                    // draft asks for the original; Finder files stay their own bytes, as a File
                    // pick does on mobile.
                    // iOS: pasted images open in the image editor, as picked photos do.
                    val pasteOriginalImage = currentDraftState.originalImage
                    // Images the host already read inside its paste action (no clipboard read here).
                    val hostPasted = ThreadComposerImagePasteRequests.takePending()
                    coroutineScope.launch {
                        if (composerSurface.sendChannelId == null) {
                            bridge.toast(attachmentPickerUnavailableLabel)
                            return@launch
                        }
                        val files =
                            (hostPasted ?: withContext(Dispatchers.Default) { raftPlatformReadClipboardAttachments() })
                                .take(ThreadComposerImagePasteMaxImages.takeIf { raftPlatformPastedImagesOpenEditor() } ?: Int.MAX_VALUE)
                        bridge.log("thread_attachment_paste files=${files.size} host=${hostPasted != null}")
                        if (files.isEmpty()) return@launch
                        if (raftPlatformPastedImagesOpenEditor() && raftComposerImagePickerFilesAreStrictImages(files)) {
                            // The picked-photo path: the editor first, compressed after Done
                            // unless the draft asks for the original. The keyboard the paste
                            // came from goes down over the editor and comes back after it (dm #119).
                            composerKeyboardHandoff.leave(keyboardOpen = adaptivePageKeyboardInsetDp > 0f)
                            openComposerImageAnnotation(files)
                            composerKeyboardHandoff.settle()
                            return@launch
                        }
                        val prepared =
                            threadComposerCompressPastedImages(
                                files = files,
                                originalImage = pasteOriginalImage,
                                compress = { file ->
                                    val response =
                                        bridge.compressThreadImage(
                                            fileRef = file.fileRef.orEmpty(),
                                            fileName = file.fileName.ifBlank { "image.png" },
                                            mimeType = file.mimeType?.ifBlank { null } ?: "image/png",
                                        )
                                    (parseRaftFilePreviewCompressedImageResult(response)
                                        as? RaftFilePreviewCompressedImageResult.Success)?.file
                                },
                                retire = { refs ->
                                    bridge.retireFilePreviewTemporaryFiles(
                                        sessionId = "thread-paste-compression-$threadInstanceKey",
                                        fileRefs = refs
                                    )
                                },
                                log = bridge::log,
                            )
                        uploadComposerAttachmentFiles(prepared)
                    }
                }
                ThreadComposerAttachImageAction, ThreadComposerCaptureImageAction, "attachFile" -> {
                    // Freeze the session's image policy at picker admission.  The picker may
                    // complete after a recomposition (or a new toggle event), but its result must
                    // retain the policy that was visible when the user opened it.
                    val compressPickerImages =
                        shouldCompressThreadAttachmentPickerImages(
                            action = action,
                            originalImage = currentDraftState.originalImage
                        )
                    // A picked photo opens the image editor: the keyboard goes down before the
                    // picker, or coming back to the window raises it over the editor (dm #119).
                    // A file pick goes straight into the tray and keeps the keyboard as it is.
                    val opensImageEditor = action != "attachFile"
                    if (opensImageEditor) composerKeyboardHandoff.leave(keyboardOpen = adaptivePageKeyboardInsetDp > 0f)
                    coroutineScope.launch {
                        try {
                            val channelId = composerSurface.sendChannelId
                            if (channelId == null) {
                                bridge.toast(attachmentPickerUnavailableLabel)
                                return@launch
                            }
                            val response =
                                bridge.threadAttachmentUpload(
                                    channelId = channelId,
                                    imageOnly = opensImageEditor,
                                    capture = action == ThreadComposerCaptureImageAction,
                                    // Image picker/camera results enter the annotation editor first.
                                    // Do not encode before the editor decision: picker JPEG -> editor
                                    // PNG -> JPEG would incur two lossy generations. Generic file
                                    // picks also stay uncompressed so a JPEG remains source bytes
                                    // and the shared size gate sees the real file.
                                    compressImages = compressPickerImages,
                                )
                            admitComposerPickedAttachments(action, response)
                        } finally {
                            // Cancelled, rejected or straight into the tray: back at the composer.
                            if (opensImageEditor) composerKeyboardHandoff.settle()
                        }
                    }
                }
                "removeAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        val draftKey = normalizeThreadAttachmentUploadDraftKey(composerDraftViewModel.state.value.draftKey)
                        val removal =
                            resolveThreadComposerAttachmentRemoval(
                                attachments = composerDraftViewModel.state.value.uploadedAttachments,
                                removedAttachmentIds = composerDraftViewModel.state.value.removedAttachmentIds,
                                attachmentId = id,
                                draftKey = draftKey
                            )
                        if (!removal.accepted) {
                            bridge.logWarn(
                                "thread_composer_attachment_remove_rejected attachmentId=$id reason=${removal.reason}"
                            )
                            return@ThreadPageContent
                        }
                        // Removing the tile is what cancels its upload (leaving the conversation does not).
                        attachmentUploads.remove(draftKey, id)
                        attachmentUploadFilesByLocalId =
                            attachmentUploadFilesByLocalId.withoutThreadAttachmentUploadFile(id, draftKey)
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(id))
                        if (removal.fileRefToRetire != null) {
                            bridge.retireFilePreviewTemporaryFiles(
                                sessionId = "thread-composer-${draftKey.orEmpty()}",
                                fileRefs = listOf(removal.fileRefToRetire)
                            )
                        }
                    }
                }
                "retryAttachments" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        val draftKey = normalizeThreadAttachmentUploadDraftKey(composerDraftViewModel.state.value.draftKey)
                        if (draftKey == null) {
                            bridge.log("thread_composer_attachment_retry result=missing_draft_key attachmentId=$id")
                            bridge.toast(attachmentRetryUnavailableLabel)
                            return@ThreadPageContent
                        }
                        val file = attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, draftKey)
                        if (file == null) {
                            bridge.log(
                                "thread_composer_attachment_retry result=missing_file_ref attachmentId=$id"
                            )
                            bridge.toast(attachmentRetryUnavailableLabel)
                            return@ThreadPageContent
                        }
                        if (attachmentUploadOrchestrator.isActive(id, draftKey)) {
                            bridge.log(
                                "thread_composer_attachment_retry result=already_uploading attachmentId=$id"
                            )
                            return@ThreadPageContent
                        }
                        bridge.log(
                            "thread_composer_attachment_retry result=start attachmentId=$id fileName=${file.fileName}"
                        )
                        // Preserve an editor's remote replacement slot across a failed attempt.
                        // RetryAttachment clears the old admission so the next AddLocal mint gets
                        // a fresh generation; capture the slot before that clear, otherwise a
                        // successful retry would append beside the original remote row.
                        val replacementSlot =
                            composerDraftViewModel.state.value.attachmentAdmissionsByLocalId[id]
                                ?.replacementAttachmentId
                                ?.trim()
                                ?.takeIf { it.isNotBlank() && it != id }
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(id))
                        coroutineScope.launch {
                            uploadComposerAttachmentFiles(
                                files = listOf(file),
                                replaceAttachmentId = replacementSlot
                            )
                        }
                    }
                }
                "previewAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        // The tile opens its preview or the image editor: the keyboard goes down
                        // first and comes back when that page closes (dm #119).
                        composerKeyboardHandoff.leave(keyboardOpen = adaptivePageKeyboardInsetDp > 0f)
                        if (openComposerAttachmentPreview(id)) composerKeyboardHandoff.pageOpened()
                        composerKeyboardHandoff.settle()
                    }
                }
                "blur" -> {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.Flush)
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
        // Where a quote of selected text lands (Select Text > Quote, the mouse selection's Quote).
        val selectionQuoteTarget =
            threadSelectTextQuoteTarget(
                surfaceKind = renderedThread.surfaceKind,
                composerAvailable =
                    jointBillingNotice != JointBillingNotice.Locked &&
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
        SideEffect { inPlaceTextSelectionHost.quoteIntoComposer = inPlaceSelectionQuote }
        ThreadRouteDialogs(
            states = threadRouteDialogStates,
            data = data,
            labels = labels,
            selectionQuoteTarget = selectionQuoteTarget,
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
private fun RaftThreadAttachmentItem.threadAttachmentDownloadId(generation: Long): String =
    listOf("attempt-$generation", "thread_attachment", id.ifBlank { name.ifBlank { "attachment" } }, name.ifBlank { "attachment" })
        .joinToString(":")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' || it == '.' }
        .take(160)
        .ifBlank { "thread-attachment-download" }

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
    return SecondFactorStepUpCoordinator(stepUp = repository::stepUp, passkeyOptions = repository::passkeyAssertionOptions)
}
