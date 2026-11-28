package build.raft.app.thread

import build.raft.app.links.ParsedRaftPermalink
import build.raft.app.assistant.LocalThreadAssistantPanelEmbed
import build.raft.app.assistant.threadForegroundBannerOnPage
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.resources.*
import build.raft.app.shouldShowAgentLoungeConversationSwitch
import build.raft.app.showTaskOperationFailedToast
import build.raft.app.submit
import build.raft.app.taskOpenSurface
import build.raft.app.taskOpenTarget
import build.raft.app.taskPendingOpensAsPage
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.thread.threadComposerDraftKey
import build.raft.app.thread.toRaftTimeCapabilities
import build.raft.app.threadAnnouncementChannelType
import build.raft.app.threadAttachmentUploadAttemptIdentity
import build.raft.app.threadComposerDraftKey
import build.raft.app.threadPayloadOwnerScopeId
import build.raft.app.toJson
import build.raft.app.toRaftAdaptiveDetailCloseRequestOrNull
import build.raft.app.toRaftAdaptiveDetailIdentity
import build.raft.app.shell.toRaftAdaptiveOpenWindowFact
import build.raft.app.shell.toRaftAdaptivePaneEventOrNull
import build.raft.app.toRaftAvatarLabel
import build.raft.app.toRaftTimeCapabilities
import build.raft.app.toThreadComposerSurface
import build.raft.app.traceId
import build.raft.app.updatedRaftAdaptiveDetailCloseRequest
import build.raft.app.shell.withRaftOpenAnimation
import build.raft.app.withRuntimePreferences
import build.raft.app.withTaskV2
import build.raft.app.withoutThreadAttachmentUploadFile
import build.raft.app.downloads.RaftDownloadCenterProcess
import build.raft.app.folderNotice
import build.raft.app.get
import build.raft.app.data.homeStateForPage
import build.raft.app.data.HomeFollowedThreadUnread
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
import build.raft.app.settings.buildAppShellThreadPayload
import build.raft.app.canRetainThreadPageFor
import build.raft.app.channelSettingsPageData
import build.raft.app.data.PlatformFile
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
import build.raft.app.data.ThreadComposerDirectUploadClientRequestIds
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.RaftIntegrationsActionsPushNetworkApi
import build.raft.app.data.isRaftHostVisible
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.canWriteTasksInChannel
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.unfollowThreadProjectingHome
import build.raft.app.data.SharedMembersIntent
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.SharedMembersStore
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
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isAuthSessionErrorCode
import build.raft.app.data.authLossTokenFingerprint
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.threadMessageBodyOwnerKey
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.markdown.LocalRaftMarkdownPlainCandidateSession
import build.raft.app.markdown.RaftMarkdownPlainCandidateSession
import build.raft.app.markdown.resolveThreadMarkdownSingleParagraphLightPathEnabled
import build.raft.app.markdown.threadMarkdownLightGateInputsOrNull
import build.raft.app.memberDetailIdentity
import build.raft.app.messages
import build.raft.app.markdown.raftTraceDiagnosticsEnabled
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.shell.nextForegroundBannerRouteOwnerId
import build.raft.app.nonBlank
import build.raft.app.normalizeThreadAttachmentUploadDraftKey
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.settings.observe
import build.raft.app.shell.openRaftPage
import build.raft.app.openTaskPendingPage
import build.raft.app.optBoolean
import build.raft.app.optString
import build.raft.app.parseRaftFilePreviewCompressedImageResult
import build.raft.app.post
import build.raft.app.settings.raftFeedbackCardEntryEnabled
import build.raft.app.raftFilePreviewAttachmentUrlCache
import build.raft.app.raftImageLightboxEnabled
import build.raft.app.raftPlatformHasFinePointer
import build.raft.app.raftPlatformSupportsPublicAttachmentDownloadReadback
import build.raft.app.raftPlatformSupportsSaveImageToPhotos
import build.raft.app.raftScopedAgentStatusResolver
import build.raft.app.shell.raftShellStoreScope
import build.raft.app.shell.raftTopSafeAreaPadding
import build.raft.app.raftViewerEditedImageFile
import build.raft.app.raftViewerImageEditAttachmentId
import build.raft.app.reduceRaftAdaptivePane
import build.raft.app.rememberAgentLoungeEnabled
import build.raft.app.rememberLiveRaftTimeContext
import build.raft.app.shell.rememberRaftLinkNavigator
import build.raft.app.members.rememberRaftMemberAppBot
import build.raft.app.members.rememberRaftMemberAvatarWireValue
import build.raft.app.rememberShellRuntimeAccountUsageAccess
import build.raft.app.rememberTaskChannelTasksViewModel
import build.raft.app.rememberThreadChannelAnnouncementStripSlot
import build.raft.app.rememberThreadChannelConversionStripSlot
import build.raft.app.rememberThreadComposerKeyboardHandoff
import build.raft.app.resolveTaskOpenConversationChannelId
import build.raft.app.resolveThreadComposer
import build.raft.app.resolveThreadRouteIdentity
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
import build.raft.app.currentAttachmentOwnerLease
import build.raft.app.currentRevisionForReplyCacheOwner
import build.raft.app.settings.currentServerSummary
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.TextFieldValue
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
import build.raft.app.AgentLoungeConversationSide
import build.raft.app.AgentLoungeConversationSwitch
import build.raft.app.AttachmentOperationToken
import build.raft.app.AuthenticatedProductPager
import build.raft.app.AuthenticatedProductRoute
import build.raft.app.CHANNEL_SETTINGS_PAGE
import build.raft.app.settings.HOME_SEARCH_PAGE
import build.raft.app.members.LocalMembersRuntimeAccountUsageAccess
import build.raft.app.shell.LocalRaftForegroundBannerHandoff
import build.raft.app.members.MEMBER_DETAIL_PAGE
import build.raft.app.MemberDetailIdentity
import build.raft.app.PerformanceSceneEffect
import build.raft.app.ProcessAttachmentAuthScopes
import build.raft.app.RAFT_ADAPTIVE_DETAIL_CLOSE_EVENT
import build.raft.app.RAFT_ADAPTIVE_INITIATION_ENABLED
import build.raft.app.RAFT_EMBEDDED_CHILD_PAGE_PARAM
import build.raft.app.RAFT_VIEWER_IMAGE_EDIT_EVENT
import build.raft.app.RaftAdaptiveDetailCloseRequest
import build.raft.app.shell.RaftAdaptiveDetailOpenInitiator
import build.raft.app.RaftAdaptivePaneEvent
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptivePaneState
import build.raft.app.RaftAgentActivitySubscriptionEffect
import build.raft.app.RaftChannelSideSection
import build.raft.app.RaftComposerDraftUploadApplier
import build.raft.app.RaftComposerDraftUploadOutcome
import build.raft.app.RaftFilePreviewAttachmentUrlCacheKey
import build.raft.app.RaftFilePreviewCompressedImageResult
import build.raft.app.shell.RaftForegroundMessageBanner
import build.raft.app.RaftImageLightbox
import build.raft.app.RaftImageLightboxRequest
import build.raft.app.RaftInputFocusDismissHost
import build.raft.app.shell.RaftLinkNavigator
import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.RaftTextFieldNativeEditingStateReader
import build.raft.app.RaftThreadComposerPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftTimeContextProvider
import build.raft.app.RaftViewerImageEditOpener
import build.raft.app.RaftViewerImageEditor
import build.raft.app.ReplyCacheProvenance
import build.raft.app.shell.SharedForegroundBannerStore
import build.raft.app.SharedThreadComposerDraftIntent
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftViewModel
import build.raft.app.TaskCreateFromMessageDialog
import build.raft.app.TaskCreateFromMessageFlow
import build.raft.app.TaskCreateFromMessageOutcome
import build.raft.app.TaskOpenGate
import build.raft.app.TaskOpenSurface
import build.raft.app.TaskOpenSurfaceFlow
import build.raft.app.TaskOpenSurfaceHost
import build.raft.app.TaskOpenSurfaceIncomingNavigationEffect
import build.raft.app.TaskOpenTarget
import build.raft.app.TaskPageEditing
import build.raft.app.ThreadAttachmentUploadAttemptIdentity
import build.raft.app.ThreadComposerDismissUnresolvedMentionsEvent
import build.raft.app.ThreadComposerDropAttachmentsAction
import build.raft.app.ThreadComposerFileDropFolderNotice
import build.raft.app.ThreadComposerFocusTargetKey
import build.raft.app.ThreadComposerImageEventSessionTracker
import build.raft.app.ThreadComposerSendAdmission
import build.raft.app.ThreadComposerSendRequest
import build.raft.app.ThreadComposerSuggestionDemand
import build.raft.app.ThreadPageLounge
import build.raft.app.ThreadRouteIdentity
import build.raft.app.agentLoungeDmAgentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    serverId: String?,
): RaftLinkNavigator =
    rememberRaftLinkNavigator(
        pager = pager,
        bridge = bridge,
        artifactConversationId = payload.artifactOpenConversationId(),
        installedAppServerId = serverId,
    )

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
    val selectedServerStore =
        remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val quotedPermalinkLookup: ThreadQuotedPermalinkLookup =
        remember(sharedStores, storeScope) {
            { target ->
                val viewer = sharedStores.currentQuotedPermalinkViewer(storeScope)
                if (viewer == null) {
                    ThreadQuotedPermalinkCacheLookup.Miss
                } else {
                    val cached =
                        sharedStores.quotedPermalinkCache.peekLookup(
                            accountUserId = viewer.userId,
                            serverId = viewer.serverId,
                            channelId = target.channelId.value,
                            messageId = target.messageId.value,
                        )
                    if (cached != ThreadQuotedPermalinkCacheLookup.Miss) {
                        cached
                    } else if (
                        RaftQuotedPermalinkHistoryLimitedMemo.App.answer(
                            viewer = viewer,
                            channelId = target.channelId.value,
                            messageId = target.messageId.value,
                        ) != null
                    ) {
                        ThreadQuotedPermalinkCacheLookup.KnownUnavailable
                    } else {
                        ThreadQuotedPermalinkCacheLookup.Miss
                    }
                }
            }
        }
    val quotedPermalinkLoader: ThreadQuotedPermalinkLoader? =
        remember(networkClient, authStore, sharedStores) {
            val client = RaftThreadApiClient(NetworkRaftThreadApi(networkClient))
            val loader: ThreadQuotedPermalinkLoader = { target ->
                val messageId = target.messageId.value
                val channelId = target.channelId.value
                sharedStores.quotedPermalinkCache.getOrFetchOutcome(
                    viewerProvider = { authStore.readSession()?.quotedPermalinkViewer() },
                    channelId = channelId,
                    messageId = messageId,
                ) { viewer ->
                    var failureHttpCode: Int? = null
                    // #proj-mobile:#274: a quote behind the Free plan's history window is answered from
                    // the memo, not asked again each time its row comes on screen.
                    val read =
                        RaftQuotedPermalinkHistoryLimitedMemo.App.read(
                            viewer = viewer,
                            channelId = channelId,
                            messageId = messageId,
                        ) {
                            client.messageContext(messageId, channelId).also { apiResult ->
                                if (apiResult is ThreadApiResult.Failure) {
                                    failureHttpCode = apiResult.code
                                }
                            }
                        }
                    when (read) {
                        is RaftQuotedPermalinkContextRead.Page -> {
                            val payload = read.payload
                            val targetId = payload.targetMessageId?.value ?: messageId
                            val found =
                                payload.messages
                                    .firstOrNull { it.id.value == targetId }
                                    ?.toRaftThreadMessageItem(null)
                                    ?.let { item -> ThreadQuotedPermalinkMessage(item, channelArchived = payload.channelArchived) }
                            if (found != null) {
                                ThreadQuotedPermalinkFetchOutcome.Loaded(found)
                            } else {
                                ThreadQuotedPermalinkFetchOutcome.DefinitiveUnavailable
                            }
                        }
                        // Behind the history window: already persisted in RaftQuotedPermalinkHistoryLimitedMemo
                        // (keyed by plan so a Free->Pro upgrade re-fetches immediately).
                        is RaftQuotedPermalinkContextRead.HistoryLimited ->
                            ThreadQuotedPermalinkFetchOutcome.TransientFailure
                        RaftQuotedPermalinkContextRead.Unavailable ->
                            if (failureHttpCode == 403 || failureHttpCode == 404) {
                                ThreadQuotedPermalinkFetchOutcome.DefinitiveUnavailable
                            } else {
                                ThreadQuotedPermalinkFetchOutcome.TransientFailure
                            }
                    }
                }
            }
            loader
        }
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
                immediateHomeSnapshotLookupProvider =
                    sharedHomeStore?.let { store ->
                        { serverId -> store.snapshotLookupFor(serverId) }
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
        LocalThreadQuotedPermalinkLookup provides quotedPermalinkLookup,
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
        onReplaceThreadPayload = { nextPayload ->
            if (adaptiveCloseRequest != null) {
                openStandaloneThreadPayload(nextPayload)
            } else {
                val launchPayload = nextPayload.toWireThreadLaunchPayloadOrNull()
                if (launchPayload == null) {
                    failThreadOpen(ThreadOpenFailure.MissingIdentity, nextPayload)
                } else {
                    val pageData = launchPayload.toThreadPageData()
                    pageData.optString("payload").takeIf { it.isNotBlank() }?.let { payloadText = it }
                    pageData.optString("instanceId").takeIf { it.isNotBlank() }?.let { instanceId = it }
                    val targetThreadId =
                        nextPayload.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: nextPayload.sourceThreadId?.takeIf { it.isNotBlank() }
                    if (targetThreadId != null) {
                        sharedStores.sharedHomeStore(storeScope)
                            ?.dispatch(SharedHomeIntent.SelectConversation(targetThreadId))
                    }
                }
            }
        },
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
internal fun ThreadJumpFailureToasts(
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

/**
 * Registers a live standalone conversation page with the shell's [RaftTopPageConversationFocusSignal]
 * so a deduplicated notification/link open (`already_top_page`) jumps to and highlights its target
 * message in place (loading anchored context if the message is not in the timeline yet and switching
 * back to the Chat tab). Kept outside [SharedThreadRouteContentContent] for JVM method size.
 */
@Composable
private fun ThreadTopPageFocusEffect(
    enabled: Boolean,
    foregroundBannerStore: SharedForegroundBannerStore,
    routeIdentity: ThreadRouteIdentity,
    threadViewModel: SharedThreadViewModel,
    keyboardInsetDp: () -> Float,
    dismissKeyboard: () -> Unit,
    coroutineScope: CoroutineScope,
    bridge: build.raft.app.base.BridgeModule,
) {
    val latestRouteIdentity by rememberUpdatedState(routeIdentity)
    val latestKeyboardInsetDp by rememberUpdatedState(keyboardInsetDp)
    val latestDismissKeyboard by rememberUpdatedState(dismissKeyboard)
    DisposableEffect(enabled, foregroundBannerStore, threadViewModel) {
        if (!enabled) return@DisposableEffect onDispose {}
        val unregister =
            foregroundBannerStore.topPageFocus.register(
                routeProvider = { latestRouteIdentity },
                target = { request ->
                    bridge.log(
                        "window_navigation_top_page_focus_apply source=${request.source} " +
                            "threadId=${latestRouteIdentity.timelineChannelId ?: latestRouteIdentity.instanceKey} " +
                            "focusedMessageId=${request.messageId} nonce=${request.nonce}"
                    )
                    val dispatchJump = {
                        threadViewModel.dispatch(
                            SharedThreadIntent.JumpToMessage(
                                messageId = request.messageId,
                                switchToTab = RaftThreadTab.Chat.wireValue,
                                unreadThreadPill = false,
                            )
                        )
                    }
                    if (latestKeyboardInsetDp() > 0f) {
                        latestDismissKeyboard()
                        coroutineScope.launch {
                            withTimeoutOrNull(ADAPTIVE_PARENT_FOCUS_IME_WAIT_MS) {
                                snapshotFlow { latestKeyboardInsetDp() }.first { it <= 0f }
                            }
                            dispatchJump()
                        }
                    } else {
                        dispatchJump()
                    }
                    true
                },
            )
        onDispose { unregister() }
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

internal data class ThreadTaskConversationState(
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
    val taskParentChannelIdRef = remember(taskConversationId) { mutableStateOf<String?>(null) }
    val latestTaskServerId by rememberUpdatedState(currentServerId)
    val taskConversationHeaderViewModel: TaskConversationHeaderViewModel? =
        taskConversationId?.let { conversationId ->
            viewModel(key = "task-conversation-header-${conversationId.value}") {
                TaskConversationHeaderViewModel(
                    conversationChannelId = conversationId,
                    loadRef = dataSource::loadTaskConversationRef,
                    follow = { id ->
                        dataSource.followTaskConversation(id).also { result ->
                            if (result is DataResult.Content || result is DataResult.Empty) {
                                latestTaskServerId?.takeIf { it.isNotBlank() }?.let { serverId ->
                                    sharedHomeStore?.dispatch(
                                        SharedHomeIntent.NoteLoungeThreadFollowed(
                                            serverId = serverId,
                                            parentChannelId = taskParentChannelIdRef.value,
                                            threadChannelId = (result as? DataResult.Content)?.data?.threadChannelId?.value ?: id.value,
                                        )
                                    )
                                }
                            }
                        }
                    },
                    unfollow = { conversationId ->
                        unfollowThreadProjectingHome(
                            conversationId,
                            project = { intent -> sharedHomeStore?.dispatch(intent) },
                            unfollow = dataSource::unfollowThread,
                            parentChannelId = taskParentChannelIdRef.value,
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
    taskParentChannelIdRef.value = taskConversationParentChannelId?.value
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
    val initialRouteIdentity =
        if (retainedRouteIdentity.canRetainThreadPageFor(incomingRouteIdentity)) {
            retainedRouteIdentity
        } else {
            incomingRouteIdentity
        }
    LaunchedEffect(incomingRouteIdentity) {
        if (!retainedRouteIdentity.canRetainThreadPageFor(incomingRouteIdentity)) {
            retainedRouteIdentity = incomingRouteIdentity
        }
    }
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
    val threadRouteHeaderProjection =
        rememberThreadRouteHeaderProjection(
            bridge = bridge,
            sharedStores = sharedStores,
            storeScope = storeScope,
            serverMembersStore = serverMembersStore,
            conversationIdentityProvider = conversationIdentityProvider,
            sharedHomeStore = sharedHomeStore,
            sharedHomeState = sharedHomeState,
            payload = payload,
            currentSession = currentSession,
            replyCacheProvenance = replyCacheProvenance,
        )
    val headerProjectionInput = threadRouteHeaderProjection.headerProjectionInput
    val conversationDirectoryLookupIds = threadRouteHeaderProjection.conversationDirectoryLookupIds
    val headerProjectionServerId = threadRouteHeaderProjection.headerProjectionServerId
    val memberAvatarResolver = threadRouteHeaderProjection.memberAvatarResolver
    val memberAppBotResolver = threadRouteHeaderProjection.memberAppBotResolver
    val knownEntityResolver = threadRouteHeaderProjection.knownEntityResolver
    val knownEntityAccountId = threadRouteHeaderProjection.knownEntityAccountId
    val directoryConversationProjectionFlow = threadRouteHeaderProjection.directoryConversationProjectionFlow
    val directoryConversationProjections by threadRouteHeaderProjection.directoryConversationProjectionsState
    val directoryConversationProjection = threadRouteHeaderProjection.directoryConversationProjection
    val billingDirectoryProjections by threadRouteHeaderProjection.billingDirectoryProjectionsState
    val jointMessageSurface = threadRouteHeaderProjection.jointMessageSurface
    val messageAvatarResolver = threadRouteHeaderProjection.messageAvatarResolver
    val headerProjectionSource = threadRouteHeaderProjection.headerProjectionSource
    val stableHeaderProjection = threadRouteHeaderProjection.stableHeaderProjection
    val composerJointChannelId = threadRouteHeaderProjection.composerJointChannelId
    val headerDisplayModel = threadRouteHeaderProjection.headerDisplayModel
    val mutePageLounge = threadRouteHeaderProjection.mutePageLounge
    val activityMuteState = threadRouteHeaderProjection.activityMuteState
    val activityMutedIndicatorVisible = threadRouteHeaderProjection.activityMutedIndicatorVisible
    val conversationPinState = threadRouteHeaderProjection.conversationPinState
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
    ThreadRouteRoomLeaseEffect(
        realtimeEvents = realtimeEvents,
        realtimeRoomLeaseRegistry = realtimeRoomLeaseRegistry,
        storeScope = storeScope,
        pager = pager,
        adaptiveCloseRequest = adaptiveCloseRequest,
        currentSession = currentSession,
        payload = payload,
        active = active,
    )
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
    ThreadOfflineStandInRecoveryEffects(
        bridge = bridge,
        threadViewModel = threadViewModel,
        sharedStores = sharedStores,
        realtimeEvents = realtimeEvents,
        coroutineScope = coroutineScope,
        latestCurrentSessionState = latestCurrentSessionState,
        payload = payload,
        active = active,
    )
    val threadVisibleResumeGate = remember(threadInstanceKey, storeScope.shellInstanceId) {
        ThreadVisibleResumeGate()
    }
    val threadPreviewOverlayLifecycleFilter = remember(threadInstanceKey, storeScope.shellInstanceId) {
        ThreadPreviewOverlayLifecycleFilter()
    }
    val threadHostForegroundState = remember(threadInstanceKey, storeScope.shellInstanceId) {
        mutableStateOf(isRaftHostVisible(storeScope.shellInstanceId))
    }
    var threadHostForeground by threadHostForegroundState
    val threadPointerSessionKeyState = remember(threadInstanceKey, storeScope.shellInstanceId) {
        mutableStateOf(0)
    }
    var threadPointerSessionKey by threadPointerSessionKeyState
    val threadMessageLongPressGestureOwnerStore = remember(threadInstanceKey) {
        ThreadMessageLongPressGestureOwnerStore(tapDispatchScope = coroutineScope)
    }
    val latestRouteActiveState = rememberUpdatedState(active)
    val latestForegroundRejoinRoomIdsState = rememberUpdatedState(payload.activeRealtimeRoomIds())
    val latestCoreThreadRealtimeEventHandlerState = rememberUpdatedState<suspend (RealtimeEvent) -> Unit>(
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

    ThreadRouteLifecycleEffects(
        bridge = bridge,
        threadViewModel = threadViewModel,
        sharedStores = sharedStores,
        realtimeEvents = realtimeEvents,
        coroutineScope = coroutineScope,
        storeScope = storeScope,
        threadInstanceKey = threadInstanceKey,
        currentSession = currentSession,
        payload = payload,
        active = active,
        mutePageLounge = mutePageLounge,
        threadVisibleResumeGate = threadVisibleResumeGate,
        threadPreviewOverlayLifecycleFilter = threadPreviewOverlayLifecycleFilter,
        threadHostForegroundState = threadHostForegroundState,
        threadPointerSessionKeyState = threadPointerSessionKeyState,
        latestCurrentSessionState = latestCurrentSessionState,
        latestRouteActiveState = latestRouteActiveState,
        latestForegroundRejoinRoomIdsState = latestForegroundRejoinRoomIdsState,
    )
    ThreadRouteRealtimeSinkEffects(
        realtimeDomainEventOwner = realtimeDomainEventOwner,
        authStore = authStore,
        coroutineScope = coroutineScope,
        canonicalFinalLifecycleEffects = canonicalFinalLifecycleEffects,
        canonicalFinalThreadSlot = canonicalFinalThreadSlot,
        canonicalFinalThreadSurfaceRouteKey = canonicalFinalThreadSurfaceRouteKey,
        latestCoreThreadRealtimeEventHandlerState = latestCoreThreadRealtimeEventHandlerState,
    )
    // Hoist the route's draft ViewModel and pending optimistic send slot above LoadingShell so
    // cold opens render the real optimistic composer on Frame 1 inside one persistent
    // ThreadRouteScaffold call site (no movableContentOf detach on LoadingShell -> FeatureSurfaces,
    // preserving focus, keyboard, and typed draft — Hands 2f972fb1 / #wg-mobile-feedback:#285).
    val composerDraftViewModel: SharedThreadComposerDraftViewModel =
        viewModel(key = "shared-thread-composer-$threadInstanceKey") {
            SharedThreadComposerDraftViewModel(persistSlices = listOf(composerDraftPersistSlice))
        }
    FlushComposerDraftOnLeave(composerDraftViewModel, threadInstanceKey)
    val pendingOptimisticSendState =
        remember(threadInstanceKey) { mutableStateOf<ThreadComposerSendRequest?>(null) }
    val stableComposerInstanceId =
        remember(threadInstanceKey) {
            payload.composer.instanceId.ifBlank { payload.instanceId }.ifBlank { threadInstanceKey }
        }
    val chatLayoutPreferenceStore =
        remember(pager) {
            KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
        }
    val composerSendKeyEnabled = rememberComposerSendKeyEnabled(chatLayoutPreferenceStore, eventBus)
    val composerSendKey = build.raft.app.data.RaftComposerSendKeyRuntime.observeSendKey()
    var loadingSlotsBuilder: (@Composable () -> ThreadRouteScaffoldSlots)? = null
    var featureSlotsBuilder: (@Composable () -> ThreadRouteScaffoldSlots)? = null
    if (routeMountPlan == ThreadRouteMountPlan.LoadingShell) {
        val loadingServerId = headerProjectionServerId?.value ?: currentSession?.currentServerId
        val loadingServerRole = currentSession?.servers?.firstOrNull { it.id == loadingServerId }?.role
        val loadingLoungeEnabled = rememberAgentLoungeEnabled(sharedStores.serverFeatureFlags, loadingServerId, loadingServerRole)
        val loadingAssistantAnswers by
            remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) }.answers.collectAsState()
        val loadingPersonalAssistantIds =
            remember(loadingAssistantAnswers) {
                loadingAssistantAnswers.values.mapNotNull { it.assistant?.agentId }.toSet()
            }
        val loadingIsLoungeConversation = payload.surfaceKind == RaftThreadSurfaceKind.Channel && mutePageLounge != null
        val loadingSwitchAgentId =
            if (loadingIsLoungeConversation) {
                mutePageLounge?.agentId
            } else {
                headerDisplayModel.dmAgentActivityMemberId() ?: payload.agentLoungeDmAgentId()
            }
        val loadingAppBot = rememberRaftMemberAppBot(loadingSwitchAgentId)
        val loadingMemberProfile =
            remember(serverMembersStore, loadingServerId, loadingSwitchAgentId) {
                val sid = loadingServerId?.takeIf { it.isNotBlank() }
                if (sid != null && loadingSwitchAgentId != null) {
                    serverMembersStore?.memberProfile(ServerId(sid), loadingSwitchAgentId)
                } else {
                    null
                }
            }
        val loadingShowSwitch =
            shouldShowAgentLoungeConversationSwitch(
                enabled = loadingLoungeEnabled,
                surfaceKind = payload.surfaceKind,
                isLoungeConversation = loadingIsLoungeConversation,
                agentId = loadingSwitchAgentId,
                personalAssistantAgentIds = loadingPersonalAssistantIds,
                isAppBot = loadingAppBot != null,
                isDeletedAgent = loadingMemberProfile?.agentDeletedAt?.isNotBlank() == true,
            ) && loadingMemberProfile?.assistantPausedAt == null
        val loadingSwitch =
            if (loadingShowSwitch) {
                AgentLoungeConversationSwitch(
                    activeSide =
                        if (loadingIsLoungeConversation) AgentLoungeConversationSide.Lounge else AgentLoungeConversationSide.Private,
                    switching = true,
                    onSelectLounge = {},
                    onSelectPrivate = {},
                )
            } else {
                null
            }
        val loadingLounge =
            if (mutePageLounge != null || loadingSwitch != null) {
                ThreadPageLounge(
                    lounge = mutePageLounge,
                    agentAvatarWireValue =
                        (mutePageLounge?.agentId ?: loadingSwitchAgentId)?.let {
                            rememberRaftMemberAvatarWireValue(memberAvatarResolver, it)
                        },
                    cacheServerId = loadingServerId,
                    canUnarchive = false,
                    unarchiving = false,
                    onUnarchive = {},
                    onOpenAgentProfile = null,
                    conversationSwitch = loadingSwitch,
                    agentDisplayNameOverride = loadingMemberProfile?.displayName,
                )
            } else {
                null
            }
        val loadingNowEpochMillis =
            payload.nowEpochMillis ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
        val loadingExternalFreezeState =
            externalFreezeState(
                surfaceKind = payload.surfaceKind,
                parentChannelId = payload.parentChannelId,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections,
                nowEpochMillis = loadingNowEpochMillis,
            )
        val loadingJointBillingNotice =
            jointBillingNoticeState(
                surfaceKind = payload.surfaceKind,
                parentChannelId = payload.parentChannelId,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections,
                nowEpochMillis = loadingNowEpochMillis,
            )
        loadingSlotsBuilder = {
            rememberThreadDeferredLoadingScaffoldSlots(
                payload = payload,
                displayModel = headerDisplayModel,
                memberAvatarResolver = memberAvatarResolver,
                onBack = onBack,
                showLoadingIndicator = timelineSlice.loadingIndicatorRevealed,
                lounge = loadingLounge,
                optimisticComposerHost =
                    ThreadLoadingOptimisticComposerHost(
                        accessResolution = renderSlice.accessResolution,
                        replyParentAuthority = renderSlice.replyParentAuthority,
                        composerDraftViewModel = composerDraftViewModel,
                        composerInstanceId = stableComposerInstanceId,
                        serverId = loadingServerId,
                        jointChannelId = composerJointChannelId,
                        composerLocked = threadLimitLocksWrites(loadingJointBillingNotice, loadingExternalFreezeState),
                        externalFrozen = loadingExternalFreezeState == ExternalFreezeState.Active,
                        composerSendKeyEnabled = composerSendKeyEnabled,
                        composerSendKey = composerSendKey,
                        onKeyboardHeightChange = { adaptivePageKeyboardInsetDp = it },
                        onSendRequest = { request ->
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.Change(
                                    seedText = request.value.text,
                                    seedAsTask = request.asTask,
                                )
                            )
                            val hasSendableDraft =
                                request.value.text.isNotBlank() ||
                                    composerDraftViewModel.state.value.uploadedAttachments.isNotEmpty()
                            if (hasSendableDraft) {
                                pendingOptimisticSendState.value = request
                                bridge.log(
                                    "thread_composer_send_deferred_for_access stage=loading_shell " +
                                        "instanceId=${payload.instanceId} textLen=${request.value.text.length}"
                                )
                                ThreadComposerSendAdmission.Deferred
                            } else {
                                ThreadComposerSendAdmission.Rejected
                            }
                        },
                        onEvent = { action, value, asTask, _ ->
                            when (action) {
                                "change", "staleEditorCommandIgnored" ->
                                    composerDraftViewModel.dispatch(
                                        SharedThreadComposerDraftIntent.Change(
                                            seedText = value,
                                            seedAsTask = asTask,
                                        )
                                    )
                                "toggleAsTask" ->
                                    composerDraftViewModel.dispatch(
                                        SharedThreadComposerDraftIntent.Change(seedAsTask = asTask)
                                    )
                                "send" -> {
                                    val currentDraft = composerDraftViewModel.state.value
                                    val sendText = value ?: currentDraft.seedText
                                    val sendAsTask = asTask ?: currentDraft.seedAsTask
                                    composerDraftViewModel.dispatch(
                                        SharedThreadComposerDraftIntent.Change(
                                            seedText = sendText,
                                            seedAsTask = sendAsTask,
                                        )
                                    )
                                    if (sendText.isNotBlank() || currentDraft.uploadedAttachments.isNotEmpty()) {
                                        pendingOptimisticSendState.value =
                                            ThreadComposerSendRequest(
                                                editorSessionId = 0L,
                                                value = TextFieldValue(sendText),
                                                asTask = sendAsTask,
                                            )
                                        bridge.log(
                                            "thread_composer_send_deferred_for_access stage=loading_shell " +
                                                "instanceId=${payload.instanceId} textLen=${sendText.length}"
                                        )
                                    }
                                }
                                "dismissMention" ->
                                    value?.takeIf { it.isNotBlank() }?.let { resolutionId ->
                                        composerDraftViewModel.dispatch(
                                            SharedThreadComposerDraftIntent.DismissMentionAction(resolutionId)
                                        )
                                    }
                                "dismissMentionNotice" ->
                                    composerDraftViewModel.dispatch(
                                        SharedThreadComposerDraftIntent.ClearMentionActionNotice
                                    )
                                ThreadComposerDismissUnresolvedMentionsEvent ->
                                    composerDraftViewModel.dispatch(
                                        SharedThreadComposerDraftIntent.DismissUnresolvedMentions
                                    )
                                else -> {
                                    if (threadComposerEventRequiresWriteAccess(action)) {
                                        bridge.log(
                                            "thread_composer_action_blocked action=$action reason=loading_shell_access_pending"
                                        )
                                    }
                                }
                            }
                        },
                    ),
            )
        }
    } else {
    val selectedThreadTabState = remember(threadInstanceKey, payload.selectedTab) {
        mutableStateOf(RaftThreadTab.fromWireValue(payload.selectedTab))
    }
    var selectedThreadTab by selectedThreadTabState
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
    ThreadHeaderRouteDiagnosticsEffect(
        bridge = bridge,
        currentSession = currentSession,
        headerProjectionInput = headerProjectionInput,
        conversationDirectoryLookupIds = conversationDirectoryLookupIds,
        directoryConversationProjections = directoryConversationProjections,
        directoryConversationProjection = directoryConversationProjection,
        lastAcceptedHeaderProjectionState = threadRouteHeaderProjection.lastAcceptedHeaderProjectionState,
        headerProjectionSource = headerProjectionSource,
        stableHeaderProjection = stableHeaderProjection,
        headerDisplayModel = headerDisplayModel,
        headerProjectionServerId = headerProjectionServerId,
        initialPayload = initialPayload,
        payload = payload,
    )
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
    ThreadTopPageFocusEffect(
        enabled = adaptiveCloseRequest == null && !inAssistantPanelEmbed,
        foregroundBannerStore = foregroundBannerStore,
        routeIdentity = payloadRouteIdentity,
        threadViewModel = threadViewModel,
        keyboardInsetDp = { adaptivePageKeyboardInsetDp },
        dismissKeyboard = {
            adaptiveFocusManager.clearFocus(force = true)
            adaptiveKeyboardController?.hide()
        },
        coroutineScope = coroutineScope,
        bridge = bridge,
    )
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
    val viewContextMessageState = remember(threadInstanceKey) { mutableStateOf<RaftThreadMessageItem?>(null) }
    val createAgentDialogStateHolder = remember(threadInstanceKey) { mutableStateOf<ThreadCreateAgentActionDialogState?>(null) }
    val migrateAgentDialogStateHolder = remember(threadInstanceKey) { mutableStateOf<ThreadMigrateAgentActionDialogState?>(null) }
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
    val restoredAttachmentUploadDraftKeyState = remember(threadInstanceKey) {
        mutableStateOf<String?>(null)
    }
    val restoredAttachmentUploadFileIdsState = remember(threadInstanceKey) {
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
    val labels = threadRouteLabels()
    val genericActionCardSuccessLabel = labels.genericActionCardSuccessLabel
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
    val sideCardsStateHolder = sideCardsStore.state.collectAsState()
    val sideCardsState by sideCardsStateHolder
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
    val threadRouteJump =
        rememberThreadRouteJump(
            bridge = bridge,
            threadViewModel = threadViewModel,
            payload = payload,
            timelineSlice = timelineSlice,
            sharedHomeState = sharedHomeState,
            currentServerId = currentServerId,
            threadTimelineKey = threadTimelineKey,
            pageReplyMetadataByParentId = pageReplyMetadataByParentId,
        )
    val jumpInputs = threadRouteJump.jumpInputs
    val replyMetadataByParentId = threadRouteJump.replyMetadataByParentId
    val threadRouteOwners =
        rememberThreadRouteOwners(
            sharedStores = sharedStores,
            storeScope = storeScope,
            currentSession = currentSession,
            currentServerId = currentServerId,
            replyCacheProvenance = replyCacheProvenance,
        )
    val taskProjectionOwnerIdentity = threadRouteOwners.taskProjectionOwnerIdentity
    val threadMembersServerId = threadRouteOwners.threadMembersServerId
    val attachmentUploads = threadRouteOwners.attachmentUploads
    val attachmentUploadOwnerResolved = threadRouteOwners.attachmentUploadOwnerResolved
    val attachmentUploadOrchestrator = threadRouteOwners.attachmentUploadOrchestrator
    val attachmentUploadSemaphore = threadRouteOwners.attachmentUploadSemaphore
    val threadRouteMembers =
        rememberThreadRouteMembers(
            serverMembersStore = serverMembersStore,
            baseRenderedPayload = baseRenderedPayload,
            threadMembersServerId = threadMembersServerId,
            active = active,
            sideCardsEnabled = sideCardsEnabled,
            sideCardsStateHolder = sideCardsStateHolder,
            selectedThreadTabState = selectedThreadTabState,
        )
    val threadMembersChannelId = threadRouteMembers.threadMembersChannelId
    val threadServerMembersEntry by threadRouteMembers.threadServerMembersEntryState
    val threadChannelMembersSlice by threadRouteMembers.threadChannelMembersSliceState
    val threadMembersProjection = threadRouteMembers.threadMembersProjection
    val threadRouteTaskProjection =
        rememberThreadRouteTaskProjection(
            bridge = bridge,
            tasksDataSource = tasksDataSource,
            tasksDataStore = tasksDataStore,
            threadInstanceKey = threadInstanceKey,
            timelineSlice = timelineSlice,
            payload = payload,
            baseRenderedPayload = baseRenderedPayload,
            currentSession = currentSession,
            currentServerId = currentServerId,
            taskProjectionOwnerIdentity = taskProjectionOwnerIdentity,
            taskChannelId = taskChannelId,
            markdownTaskContextChannelId = markdownTaskContextChannelId,
            selectedThreadTabState = selectedThreadTabState,
        )
    val taskProjectionSlice by threadRouteTaskProjection.taskProjectionSliceState
    val serverTaskProjectionSlice by threadRouteTaskProjection.serverTaskProjectionSliceState
    val markdownTaskContextProjectionSlice by threadRouteTaskProjection.markdownTaskContextProjectionSliceState
    val markdownTaskStatuses = threadRouteTaskProjection.markdownTaskStatuses
    val markdownTaskRefStatuses = threadRouteTaskProjection.markdownTaskRefStatuses
    val linkedTasksProjection = threadRouteTaskProjection.linkedTasksProjection
    val taskAssigneeOptions =
        rememberThreadTaskAssigneeOptions(
            threadChannelMembersSlice = threadChannelMembersSlice,
            threadMembersProjection = threadMembersProjection,
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
    val renderedThread =
        renderedPayload.withThreadWriteActionsEnabled(
            enabled = renderSlice.writeActionsEnabled && !threadLimitLocked,
            preserveSettingsAccess = renderSlice.writeActionsEnabled && threadLimitLocked,
        )
    val linkNavigator = rememberThreadLinkNavigator(pager, bridge, renderedThread, currentServerId)
    val threadViewerIsGuest = currentSession.threadViewerIsGuest(headerProjectionServerId?.value)
    val threadForwardSourceAvailable = renderedPayload.threadForwardSourceChannelId() != null
    val hideThreadReplyStart =
        rememberDmMainTimelineLabHidden(
            serverFeatureFlags = sharedStores.serverFeatureFlags,
            serverId = headerProjectionServerId,
            payload = renderedPayload,
            headerDmPeerIdentity = headerDisplayModel.dmPeerIdentity,
        )
    val effectiveMemberSuggestionScope =
        rememberThreadComposerMentionScope(
            serverFeatureFlags = sharedStores.serverFeatureFlags,
            serverId = headerProjectionServerId,
            serverMembersStore = serverMembersStore,
            scopeChannelId = memberSuggestionScopeChannelId,
            threadMembersChannelId = threadMembersChannelId,
            threadChannelMembersSlice = threadChannelMembersSlice,
            baseMemberScopedMentions = memberSuggestionScopedMentions,
            channelArchived = renderedPayload.channelArchived,
            channelAuthorityStore = sharedStores.channelAuthorityStore,
        )
    val renderedTimelineSlice =
        timelineSlice
            .withThreadWriteActionsEnabled(renderSlice.writeActionsEnabled && !threadLimitLocked)
            .withThreadViewerMessageActions(
                viewerIsGuest = threadViewerIsGuest,
                forwardSourceAvailable = threadForwardSourceAvailable,
                hideThreadReplyStart = hideThreadReplyStart,
            )
    val emptyMemberSuggestionSliceFlow = remember { MutableStateFlow(SharedMembersMemberSuggestionSlice()) }
    val memberSuggestionsFlow =
        remember(
            serverMembersStore,
            currentServerId,
            memberSuggestionScopeChannelId,
            effectiveMemberSuggestionScope,
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
                    memberScopedMentions = effectiveMemberSuggestionScope.memberScopedMentions,
                    excludeOutsiderGuests = effectiveMemberSuggestionScope.excludeOutsiderGuests,
                )
                    .onStart {
                        serverMembersStore.ensureMemberSuggestionsForServer(
                            serverId = serverId,
                            mentionChannelId = memberSuggestionScopeChannelId,
                            memberScopedMentions = effectiveMemberSuggestionScope.memberScopedMentions,
                            excludeOutsiderGuests = effectiveMemberSuggestionScope.excludeOutsiderGuests,
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
    // dm #119: an open keyboard goes down before the image picker / editor opens and comes back
    // when the user is back at the composer (ThreadComposerKeyboardHandoff.kt).
    val composerKeyboardHandoff =
        rememberThreadComposerKeyboardHandoff(key = threadInstanceKey) {
            composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RequestFocus)
        }
    ThreadComposerDraftUploadEffects(
        bridge = bridge,
        dataSource = dataSource,
        composerDraftViewModel = composerDraftViewModel,
        threadInstanceKey = threadInstanceKey,
        attachmentUploads = attachmentUploads,
        attachmentUploadOwnerResolved = attachmentUploadOwnerResolved,
        attachmentUploadOrchestrator = attachmentUploadOrchestrator,
        attachmentUploadFilesByLocalIdState = attachmentUploadFilesByLocalIdState,
        restoredAttachmentUploadDraftKeyState = restoredAttachmentUploadDraftKeyState,
        restoredAttachmentUploadFileIdsState = restoredAttachmentUploadFileIdsState,
    )
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

    val threadRouteActionCards =
        rememberThreadRouteActionCards(
            bridge = bridge,
            pager = pager,
            storeScope = storeScope,
            sharedStores = sharedStores,
            threadInstanceKey = threadInstanceKey,
            coroutineScope = coroutineScope,
            dataSource = dataSource,
            threadViewModel = threadViewModel,
            genericActionCardSuccessLabel = genericActionCardSuccessLabel,
            routeAuthRequired = { result -> routeAuthRequired(result) },
            renderedThread = renderedThread,
            replyMetadataByParentId = replyMetadataByParentId,
            onOpenThreadPayload = onOpenThreadPayload,
            currentServerSlug = currentServerSlug,
            sharedHomeState = sharedHomeState,
            currentSession = currentSession,
            currentServerId = currentServerId,
            threadServerMembersEntry = threadServerMembersEntry,
            feedbackCardEntryEnabled = feedbackCardEntryEnabled,
        )
    val actionCardStepUp = threadRouteActionCards.actionCardStepUp
    val actionCardStepUpState by threadRouteActionCards.actionCardStepUpStateHolder
    val installAppDialogStore = threadRouteActionCards.installAppDialogStore
    val installAppDialogStateHolder = threadRouteActionCards.installAppDialogStateHolder
    val actionCardSpecStore = threadRouteActionCards.actionCardSpecStore
    val actionCardSpecState by threadRouteActionCards.actionCardSpecStateHolder
    val actionCardSpecHost = threadRouteActionCards.actionCardSpecHost

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
                            memberScopedMentions = effectiveMemberSuggestionScope.memberScopedMentions,
                            force = true,
                            excludeOutsiderGuests = effectiveMemberSuggestionScope.excludeOutsiderGuests,
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
    val effectiveComposerChannelId =
        resolvedAuxiliaryThreadId ?: renderedPayload.optimisticComposerChannelId(resolvedAuxiliaryThreadId)
    val scopedBaseComposerState =
        remember(
            baseComposerState,
            stableComposerInstanceId,
            renderedPayload.canSend,
            effectiveComposerChannelId,
            resolvedAuxiliaryThreadId,
        ) {
            baseComposerState.copy(
                instanceId = stableComposerInstanceId,
                streamChannelId = effectiveComposerChannelId,
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
            effectiveComposerChannelId,
            renderedPayload.parentChannelId,
            composerJointChannelId,
            headerDisplayModel.title,
            headerDisplayModel.subtitle,
            renderedPayload.surfaceKind,
            renderedPayload.parentSurfaceKind,
            assistantPanelContext
        ) {
            scopedBaseComposerState.toThreadComposerSurface(
                channelId = effectiveComposerChannelId.orEmpty(),
                parentChannelId = renderedPayload.parentChannelId,
                jointChannelId = composerJointChannelId,
                mentionChannelLabel = headerDisplayModel.threadComposerMentionScopeChannelLabel(),
                memberSuggestionHeadersVisible = renderedPayload.threadComposerMemberSuggestionHeadersVisible()
            ).withTaskV2().copy(assistantContext = assistantPanelContext)
        }
    // Synchronously start the draft session before ThreadPageContent renders its first frame so
    // ThreadComposerRoute mounts the real editor on Frame 1 instead of flashing the resting-height
    // placeholder until a post-Frame-1 LaunchedEffect runs.
    val composerDraftKey = remember(composerSurface) { composerSurface.threadComposerDraftKey() }
    val composerDraftServerId = headerProjectionServerId?.value ?: currentServerId
    remember(composerDraftViewModel, scopedBaseComposerState.instanceId, composerDraftKey, composerDraftServerId) {
        ensureThreadComposerDraftStarted(
            composerDraftViewModel = composerDraftViewModel,
            composer = scopedBaseComposerState,
            draftKey = composerDraftKey,
            serverId = composerDraftServerId,
        )
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
    ThreadRenderReconcileEffect(
        bridge = bridge,
        renderedThread = renderedThread,
        renderedTimelineSlice = renderedTimelineSlice,
        performanceGenerationCounter = performanceGenerationCounter,
    )
    // task #232: rendered visible tail reported by the messages list; the read
    // marker must track what the user sees, not the loaded payload tail.
    val visibleReadTailIndexState = remember(threadInstanceKey) { mutableStateOf<Int?>(null) }
    var visibleReadTailIndex by visibleReadTailIndexState
    ThreadVisibleReadEffects(
        bridge = bridge,
        threadViewModel = threadViewModel,
        storeScope = storeScope,
        active = active,
        renderedThread = renderedThread,
        renderedTimelineSlice = renderedTimelineSlice,
        renderSliceState = renderSliceState,
        visibleReadTailIndexState = visibleReadTailIndexState,
        threadHostForegroundState = threadHostForegroundState,
    )
    // The pending local message send (ThreadRoutePendingSender.kt), built every composition by a
    // local function (a method of its own, so this one only loads what it captures for the call).
    // The route still calls it by this local function's name.
    fun buildThreadRoutePendingSender(): ThreadRoutePendingSender =
        ThreadRoutePendingSender(
            bridge = bridge,
            threadViewModel = threadViewModel,
            currentSession = currentSession,
            threadSendScope = threadSendScope,
            dataSource = dataSource,
            composerDraftViewModel = composerDraftViewModel,
            sharedHomeStore = sharedHomeStore,
            serverMembersStore = serverMembersStore,
            threadMembersServerId = threadMembersServerId,
            coroutineScope = coroutineScope,
        )
    val threadRoutePendingSender = buildThreadRoutePendingSender()
    fun sendPendingLocalMessage(
        localMessageId: String,
        context: RaftThreadPendingSendContext,
        startedFromComposer: Boolean,
        authority: ThreadLocalSendAuthority
    ): Unit =
        threadRoutePendingSender.sendPendingLocalMessage(localMessageId, context, startedFromComposer, authority)
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
            resolvedSendChannelId = renderedPayload.composerSendTargetChannelId(resolvedAuxiliaryThreadId),
            attachmentUploads = attachmentUploads,
            attachmentUploadOrchestrator = attachmentUploadOrchestrator,
            attachmentUploadSemaphore = attachmentUploadSemaphore,
            attachmentUploadFilesByLocalIdState = attachmentUploadFilesByLocalIdState,
            threadDirectUploadClientRequestIds = threadDirectUploadClientRequestIds,
            routeAuthRequiredFn = { result -> routeAuthRequired(result) },
            localizedAttachmentUploadFailedDetailFn = { detail -> localizedAttachmentUploadFailedDetail(detail) },
        )
    val threadRouteAttachmentUploader = buildThreadRouteAttachmentUploader()
    fun showAttachmentUploadFailure(
        errorCode: String,
        traceId: String? = null,
        monthlyUploadQuota: build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot? = null,
        failedFiles: List<build.raft.app.settings.RaftUpgradeUploadFile> = emptyList(),
    ): Unit =
        threadRouteAttachmentUploader.showAttachmentUploadFailure(
            errorCode = errorCode,
            traceId = traceId,
            monthlyUploadQuota = monthlyUploadQuota,
            failedFiles = failedFiles,
        )
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
        combine(
            composerDraftViewModel.state,
            attachmentUploads.byDraft,
            attachmentUploads.discardsByDraft,
        ) { draftState, uploads, discards ->
            val draftKey = normalizeThreadAttachmentUploadDraftKey(draftState.draftKey)
            Triple(
                draftKey,
                draftKey?.let { key -> uploads[key] }.orEmpty(),
                draftKey?.let { key -> discards[key] }.orEmpty(),
            )
        }.collect { (draftKey, uploads, discards) ->
            if (draftKey == null) return@collect
            if (uploads.isNotEmpty()) {
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
                        val failed = upload.outcome as? RaftComposerDraftUploadOutcome.Failed
                        showAttachmentUploadFailure(
                            errorCode = errorCode,
                            traceId = failed?.traceId,
                            monthlyUploadQuota = failed?.monthlyUploadQuota,
                            failedFiles =
                                upload.file
                                    ?.let { file ->
                                        listOf(
                                            build.raft.app.settings.RaftUpgradeUploadFile(
                                                name = file.fileName,
                                                sizeBytes = file.sizeBytes?.takeIf { it > 0L } ?: file.bytes.size.toLong(),
                                            )
                                        )
                                    }
                                    .orEmpty(),
                        )
                    },
                    onDelivered = { upload ->
                        attachmentUploads.markDelivered(draftKey, upload.localAttachmentId, upload.attemptToken)
                    }
                )
            }
            if (discards.isNotEmpty()) {
                composerDraftUploadApplier.applyDiscards(
                    draftKey = draftKey,
                    discards = discards,
                    currentState = { composerDraftViewModel.state.value },
                    acceptWithState = composerDraftViewModel::acceptWithState,
                    onSucceeded = { discard ->
                        attachmentUploads.remove(draftKey, discard.attachmentId)
                        attachmentUploadFilesByLocalId =
                            attachmentUploadFilesByLocalId.withoutThreadAttachmentUploadFile(discard.attachmentId, draftKey)
                        if (discard.fileRefToRetire != null) {
                            bridge.retireFilePreviewTemporaryFiles(
                                sessionId = "thread-composer-$draftKey",
                                fileRefs = listOf(discard.fileRefToRetire)
                            )
                        }
                        attachmentUploads.clearDiscard(draftKey, discard.attachmentId, discard.attemptToken)
                    },
                    onFailed = { discard, outcome ->
                        val handledAuth = outcome.noPermission?.let(::routeAuthRequired) == true
                        if (!handledAuth) {
                            bridge.errorToast(labels.attachmentDiscardFailedLabel, outcome.traceId)
                        }
                        attachmentUploads.markDiscardDelivered(draftKey, discard.attachmentId, discard.attemptToken)
                    },
                )
            }
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
            memberSuggestionScopedMentions = effectiveMemberSuggestionScope.memberScopedMentions,
            memberSuggestionExcludeOutsiderGuests = effectiveMemberSuggestionScope.excludeOutsiderGuests,
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
            pendingOptimisticSendState = pendingOptimisticSendState,
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
    val latestPerformComposerSendState = rememberUpdatedState<() -> Boolean> { performComposerSend() }
    fun handleComposerSendRequest(request: ThreadComposerSendRequest): ThreadComposerSendAdmission =
        threadRouteComposerActions.handleComposerSendRequest(request)
    val latestHandleComposerSendRequestState = rememberUpdatedState<(ThreadComposerSendRequest) -> ThreadComposerSendAdmission> { req ->
        handleComposerSendRequest(req)
    }
    ThreadComposerDeferredSendEffects(
        bridge = bridge,
        dataSource = dataSource,
        threadViewModel = threadViewModel,
        composerDraftViewModel = composerDraftViewModel,
        threadInstanceKey = threadInstanceKey,
        pendingOptimisticSendState = pendingOptimisticSendState,
        renderSliceState = renderSliceState,
        threadLimitLocked = threadLimitLocked,
        renderedThread = renderedThread,
        composerSurface = composerSurface,
        externalShareSendRequestedState = externalShareSendRequestedState,
        externalShareExpectedAuthorityState = externalShareExpectedAuthorityState,
        latestPerformComposerSendState = latestPerformComposerSendState,
        latestHandleComposerSendRequestState = latestHandleComposerSendRequestState,
    )
    suspend fun admitComposerPickedAttachments(action: String, response: JSONObject?): Unit =
        threadRouteComposerActions.admitComposerPickedAttachments(action, response)
    ThreadComposerImageEventEffect(
        bridge = bridge,
        eventBus = eventBus,
        threadInstanceKey = threadInstanceKey,
        coroutineScope = coroutineScope,
        threadSendScope = threadSendScope,
        composerImageEventBatchMutex = composerImageEventBatchMutex,
        composerImageEventBatches = composerImageEventBatches,
        composerImageEventSessionTracker = composerImageEventSessionTracker,
        latestUploadComposerAttachmentFilesState = latestUploadComposerAttachmentFilesState,
    )

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
            viewContextMessage = viewContextMessageState,
            attachmentCommentsDialogState = attachmentCommentsDialogStateHolder,
            installAppDialogState = installAppDialogStateHolder,
            createAgentDialogState = createAgentDialogStateHolder,
            migrateAgentDialogState = migrateAgentDialogStateHolder,
            createAgentComputerServerId = createAgentComputerServerIdState,
            createAgentComputerServerResolved = createAgentComputerServerResolvedState,
            pendingActionCardIds = pendingActionCardIdsState,
            renderSlice = renderSliceState,
            actionCardSpecStore = actionCardSpecStore,
            createAgentFormStore = createAgentFormStore,
            viewerUserId = currentSession?.user?.id,
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
            viewContextMessageState = viewContextMessageState,
            createAgentDialogStateHolder = createAgentDialogStateHolder,
            migrateAgentDialogStateHolder = migrateAgentDialogStateHolder,
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
            onJointSurface =
                resolveThreadJointSurface(
                    jointMessageSurface = jointMessageSurface,
                    taskChannelType = taskConversation.task?.channelType,
                    taskParentChannelId = taskConversation.parentChannelId,
                    currentServerId = currentServerId,
                    conversationIdentityProvider = data.conversationIdentityProvider,
                ),
        )
    val threadRouteEventHandler = buildThreadRouteEventHandler()
    val foregroundBannerSlot =
        ThreadRouteForegroundBannerSlot(
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
        )
    featureSlotsBuilder = {
        // OFF-zero-work provider set (task #20 P1, reviewed round 4): with
        // diagnostics OFF the census session Local is NOT part of the vararg
        // at all — the base array is used by identity, so the production
        // provider scope is unchanged by P1.
        val threadFeatureHosts =
            rememberThreadFeatureHosts(
                bridge = bridge,
                sharedStores = sharedStores,
                storeScope = storeScope,
                active = active,
                currentSession = currentSession,
                currentServerId = currentServerId,
                newMessageMenuEnabled = newMessageMenuEnabled,
                copiedLabel = copiedLabel,
                renderedThread = renderedThread,
                renderedTimelineSlice = renderedTimelineSlice,
                initialRouteIdentity = initialRouteIdentity,
                serverMembersStore = serverMembersStore,
                agentStatusResolver = agentStatusResolver,
                onOpenMemberProfile = onOpenMemberProfile,
                sharedHomeState = sharedHomeState,
                threadViewerIsGuest = threadViewerIsGuest,
                threadServerMembersEntry = threadServerMembersEntry,
                knownEntityResolver = knownEntityResolver,
                knownEntityAccountId = knownEntityAccountId,
                computersStore = computersStore,
                conversationDirectoryLookupIds = conversationDirectoryLookupIds,
                taskConversation = taskConversation,
                headerProjectionServerId = headerProjectionServerId,
                billingDirectoryProjections = billingDirectoryProjections,
                conversationIdentityProvider = conversationIdentityProvider,
                memberAvatarResolver = memberAvatarResolver,
                threadMembersServerId = threadMembersServerId,
                threadMembersChannelId = threadMembersChannelId,
                markdownTaskContextProjectionSlice = markdownTaskContextProjectionSlice,
                serverTaskProjectionSlice = serverTaskProjectionSlice,
                markdownTaskContextChannelId = markdownTaskContextChannelId,
                jointMessageSurface = jointMessageSurface,
                messageAvatarResolver = messageAvatarResolver,
                composerDraftViewModel = composerDraftViewModel,
                threadViewModel = threadViewModel,
                realtimeEvents = realtimeEvents,
            )
        val inPlaceTextSelectionHost = threadFeatureHosts.inPlaceTextSelectionHost
        val assistantOtter = threadFeatureHosts.assistantOtter
        val actionCardNames = threadFeatureHosts.actionCardNames
        val memberModelLabelResolver = threadFeatureHosts.memberModelLabelResolver
        val taskConversationId = threadFeatureHosts.taskConversationId
        val taskConversationHeaderViewModel = threadFeatureHosts.taskConversationHeaderViewModel
        val taskConversationHeaderState = threadFeatureHosts.taskConversationHeaderState
        val taskConversationParentChannelId = threadFeatureHosts.taskConversationParentChannelId
        val taskConversationTasksViewModel = threadFeatureHosts.taskConversationTasksViewModel
        val taskConversationTasksState = threadFeatureHosts.taskConversationTasksState
        val taskConversationTask = threadFeatureHosts.taskConversationTask
        val taskConversationDetailsViewModel = threadFeatureHosts.taskConversationDetailsViewModel
        val taskConversationDetailsState = threadFeatureHosts.taskConversationDetailsState
        val effectiveMessageAvatarResolver = threadFeatureHosts.effectiveMessageAvatarResolver
        val quoteReplyHost = threadFeatureHosts.quoteReplyHost
        val threadProvidedValues =
            rememberThreadProvidedValues(
                sharedStores = sharedStores,
                storeScope = storeScope,
                bridge = bridge,
                currentSession = currentSession,
                currentServerId = currentServerId,
                headerProjectionServerId = headerProjectionServerId,
                renderedThread = renderedThread,
                sharedHomeStore = sharedHomeStore,
                quoteReplyHost = quoteReplyHost,
                voiceDictationHost = threadFeatureHosts.voiceDictationHost,
                viewerServerLogos = threadFeatureHosts.viewerServerLogos,
                channelJointServers = threadFeatureHosts.channelJointServers,
                memberPreviewCardSource = threadFeatureHosts.memberPreviewCardSource,
                appLinkPreviewHost = threadFeatureHosts.appLinkPreviewHost,
                inPlaceTextSelectionHost = inPlaceTextSelectionHost,
                peerReadReceipts = threadFeatureHosts.peerReadReceipts,
                agentTyping = threadFeatureHosts.agentTyping,
                workerV1Enabled = threadFeatureHosts.workerV1Enabled,
                workerConversation = threadFeatureHosts.workerConversation,
                assistantOtter = assistantOtter,
                threadRouteAttachmentActions = threadRouteAttachmentActions,
                attachmentDownloadHost = attachmentDownloadHost,
                messagePermalinkLabelResolver = messagePermalinkLabelResolver,
                markdownTaskStatuses = markdownTaskStatuses,
                markdownTaskRefStatuses = markdownTaskRefStatuses,
                threadTaskTitlesByNumber = threadFeatureHosts.threadTaskTitlesByNumber,
                markdownTaskContextChannelId = markdownTaskContextChannelId,
                singleParagraphLightPathEnabled = singleParagraphLightPathEnabled,
                threadReverseLayoutEnabled = threadReverseLayoutEnabled,
                feedbackCardEntryEnabled = feedbackCardEntryEnabled,
                taskConversationJointSurface = threadFeatureHosts.taskConversationJointSurface,
                messageBodyOwnerKey = messageBodyOwnerKey,
                actionCardNames = actionCardNames,
                threadServerMembersEntry = threadServerMembersEntry,
                threadChannelMembersSlice = threadChannelMembersSlice,
                threadMembersServerId = threadMembersServerId,
                serverMembersStore = serverMembersStore,
                actionCardSpecHost = actionCardSpecHost,
                threadKeyboardTapDismissal = threadKeyboardTapDismissal,
                threadPointerSessionKey = threadPointerSessionKey,
                threadMessageLongPressGestureOwnerStore = threadMessageLongPressGestureOwnerStore,
                pendingReactionVisuals = pendingReactionVisuals,
                memberAppBotResolver = memberAppBotResolver,
                timeContextProvider = timeContextProvider,
                plainCandidateCensusSession = plainCandidateCensusSession,
            )
        val threadTaskConversationHead =
            rememberThreadTaskConversationHead(
                bridge = bridge,
                currentSession = currentSession,
                currentServerId = currentServerId,
                serverMembersStore = serverMembersStore,
                conversationIdentityProvider = conversationIdentityProvider,
                renderedThread = renderedThread,
                renderedTimelineSlice = renderedTimelineSlice,
                timeContextProvider = timeContextProvider,
                agentStatusResolver = agentStatusResolver,
                unableToOpenTaskLabel = unableToOpenTaskLabel,
                onOpenThreadPayload = onOpenThreadPayload,
                onOpenMemberProfile = onOpenMemberProfile,
                adaptivePageKeyboardInsetDpState = adaptivePageKeyboardInsetDpState,
                taskConversationId = taskConversationId,
                taskConversationHeaderViewModel = taskConversationHeaderViewModel,
                taskConversationHeaderState = taskConversationHeaderState,
                taskConversationParentChannelId = taskConversationParentChannelId,
                taskConversationTasksViewModel = taskConversationTasksViewModel,
                taskConversationTasksState = taskConversationTasksState,
                taskConversationTask = taskConversationTask,
                taskConversationDetailsViewModel = taskConversationDetailsViewModel,
                taskConversationDetailsState = taskConversationDetailsState,
            )
        val taskHeadScrollState = threadTaskConversationHead.taskHeadScrollState
        val taskConversationChrome = threadTaskConversationHead.taskConversationChrome
        val taskConversationBar = threadTaskConversationHead.taskConversationBar
        val taskConversationFloat = threadTaskConversationHead.taskConversationFloat
        val taskConversationHeadRow = threadTaskConversationHead.taskConversationHeadRow
        val threadSettingsLinks =
            rememberThreadSettingsLinks(
                bridge = bridge,
                sharedStores = sharedStores,
                storeScope = storeScope,
                active = active,
                pager = pager,
                currentSession = currentSession,
                threadViewModel = threadViewModel,
                renderSlice = renderSlice,
                payload = payload,
                renderedThread = renderedThread,
                sharedHomeStore = sharedHomeStore,
                sharedHomeStateFlow = sharedHomeStateFlow,
                sharedHomeState = sharedHomeState,
                serverMembersStore = serverMembersStore,
                threadMembersServerId = threadMembersServerId,
                threadMembersChannelId = threadMembersChannelId,
                threadChannelMembersSlice = threadChannelMembersSlice,
                agentStatusResolver = agentStatusResolver,
                headerDisplayModel = headerDisplayModel,
                headerProjectionInput = headerProjectionInput,
                conversationDirectoryLookupIds = conversationDirectoryLookupIds,
                directoryConversationProjectionFlow = directoryConversationProjectionFlow,
                directoryConversationProjections = directoryConversationProjections,
                conversationPinState = conversationPinState,
                activityMuteState = activityMuteState,
                longMessageCollapseConversationSetting = longMessageCollapseConversationSetting,
                taskConversationParentChannelId = taskConversationParentChannelId,
                realtimeRoomLeaseRegistry = realtimeRoomLeaseRegistry,
                settingsPageLinkKey = settingsPageLinkKey,
                onBack = onBack,
                onOpenThreadPayload = onOpenThreadPayload,
                onOpenMembersPager = onOpenMembersPager,
                onOpenChannelSettings = onOpenChannelSettings,
            )
        val channelSettingsPanel = threadSettingsLinks.channelSettingsPanel
        val openSettingsPage = threadSettingsLinks.openSettingsPage
        val threadLoungeSelectionQuote =
            rememberThreadLoungeSelectionQuote(
                bridge = bridge,
                sharedStores = sharedStores,
                storeScope = storeScope,
                authStore = authStore,
                currentSession = currentSession,
                currentServerId = currentServerId,
                renderSlice = renderSlice,
                payload = payload,
                renderedPayload = renderedPayload,
                renderedThread = renderedThread,
                renderedTimelineSlice = renderedTimelineSlice,
                sharedHomeStore = sharedHomeStore,
                sharedHomeState = sharedHomeState,
                serverMembersStore = serverMembersStore,
                memberAvatarResolver = memberAvatarResolver,
                threadLimitLocked = threadLimitLocked,
                selectedThreadTab = selectedThreadTab,
                composerDraftViewModel = composerDraftViewModel,
                quoteReplyHost = quoteReplyHost,
                inPlaceTextSelectionHost = inPlaceTextSelectionHost,
                onOpenThreadPayload = onOpenThreadPayload,
                onReplaceThreadPayload = onReplaceThreadPayload,
                onOpenMemberProfile = onOpenMemberProfile,
            )
        val threadLounge = threadLoungeSelectionQuote.threadLounge
        val selectionQuoteTarget = threadLoungeSelectionQuote.selectionQuoteTarget
        rememberThreadPageContentScaffoldSlots(
            providedValues = threadProvidedValues,
            foregroundBanner = foregroundBannerSlot,
            postScaffoldOverlays = {
                // Read through the local inside RaftForegroundBannerHost so standalone phone
                // ThreadPage receives the host's live effectiveHandoff (not null).
                val bannerHandoff = LocalRaftForegroundBannerHandoff.current
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
                build.raft.app.agents.StandaloneAgentVoiceCallOverlay()
            },
            lounge = threadLounge,
            onStartCall = threadLoungeSelectionQuote.onStartCall,
                initialComposerKeyboardInsetDp =
                    androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                        adaptivePageKeyboardInsetDp
                    },
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
            // Channel and DM announcements (channel_announcement_v0): the strip on a channel's or
            // DM's own page (not a reply thread or task conversation). Built here from values this
            // lambda already captures: it is at the JVM's 255-capture limit, and one more local
            // breaks it (BootstrapMethodError).
            channelAnnouncementStrip =
                rememberThreadChannelAnnouncementStripSlot(
                    sharedStores = sharedStores,
                    storeScope = storeScope,
                    bridge = bridge,
                    serverId = threadMembersServerId?.value,
                    channelId = threadMembersChannelId?.value,
                    channelType =
                        threadAnnouncementChannelType(
                            surfaceKind = renderedThread.surfaceKind,
                            channelKind = renderedThread.channelKind,
                            isTaskConversation = renderedThread.isTaskConversationSurface(),
                        ),
                    active = active && threadHostForeground,
                    channelArchived = renderedThread.channelArchived,
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
                        trackHeaderActions =
                            renderedThread.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                                !renderedThread.isTaskConversationSurface(),
                    )
                }
            },
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
            onComposerSendRequest = { request -> handleComposerSendRequest(request) },
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
                            if (renderedThread.composerSendTargetChannelId(resolvedAuxiliaryThreadId) == null) {
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
    }
    ThreadRouteScaffoldStage(
        isLoadingShell = routeMountPlan == ThreadRouteMountPlan.LoadingShell,
        loadingSlots = loadingSlotsBuilder,
        featureSlots = featureSlotsBuilder,
    )
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
internal fun rememberThreadChannelJointServers(
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
internal fun rememberInlineReplyMetadata(
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
internal fun ThreadAfterMotionEffect(
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

@Composable
private fun rememberDmMainTimelineLabHidden(
    serverFeatureFlags: build.raft.app.data.SharedServerFeatureFlags,
    serverId: ServerId?,
    payload: RaftThreadScreenPayload,
    headerDmPeerIdentity: MemberDetailIdentity?,
): Boolean {
    val flagRevision by serverFeatureFlags.snapshotRevision.collectAsState()
    return remember(
        serverFeatureFlags,
        serverId,
        flagRevision,
        payload.surfaceKind,
        payload.channelKind,
        payload.dmPeerIdentity?.kind,
        headerDmPeerIdentity?.kind,
    ) {
        threadDmMainTimelineLabHidesReplyStart(
            flagEnabled = serverFeatureFlags.isDmMainTimelineLabEnabled(serverId),
            surfaceKind = payload.surfaceKind,
            channelKind = payload.channelKind,
            dmPeerKind = headerDmPeerIdentity?.kind ?: payload.dmPeerIdentity?.kind,
        )
    }
}

@Composable
private fun rememberThreadComposerMentionScope(
    serverFeatureFlags: build.raft.app.data.SharedServerFeatureFlags,
    serverId: ServerId?,
    serverMembersStore: SharedServerMembersStore?,
    scopeChannelId: String?,
    threadMembersChannelId: ChannelId?,
    threadChannelMembersSlice: SharedServerChannelMembersSlice,
    baseMemberScopedMentions: Boolean,
    channelArchived: Boolean,
    channelAuthorityStore: build.raft.app.data.SharedChannelAuthorityStore? = null,
): ThreadComposerMentionScope {
    val flagRevision by serverFeatureFlags.snapshotRevision.collectAsState()
    val privateMentionAddEnabled =
        remember(serverFeatureFlags, serverId, flagRevision) {
            serverFeatureFlags.isPrivateMentionAddEnabled(serverId)
        }
    val usesAuthorityStore =
        baseMemberScopedMentions &&
            privateMentionAddEnabled &&
            !channelArchived &&
            serverId != null &&
            scopeChannelId != null &&
            channelAuthorityStore != null
    val viewerAuthority by
        remember(channelAuthorityStore, serverId, scopeChannelId, usesAuthorityStore) {
            if (usesAuthorityStore) {
                channelAuthorityStore!!.observe(serverId!!.value, scopeChannelId!!)
            } else {
                MutableStateFlow<build.raft.app.model.ChannelViewerAuthorityView>(
                    build.raft.app.model.ChannelViewerAuthorityView.Loading
                )
            }
        }.collectAsState(
            if (usesAuthorityStore) {
                channelAuthorityStore!!.viewOf(serverId!!.value, scopeChannelId!!)
            } else {
                build.raft.app.model.ChannelViewerAuthorityView.Loading
            }
        )
    LaunchedEffect(usesAuthorityStore, channelAuthorityStore, serverId, scopeChannelId, viewerAuthority) {
        if (usesAuthorityStore && viewerAuthority is build.raft.app.model.ChannelViewerAuthorityView.Loading) {
            channelAuthorityStore!!.requestChannelAuthority(serverId!!.value, scopeChannelId!!)
        }
    }
    val needsSeparateScopeSlice =
        !usesAuthorityStore &&
            baseMemberScopedMentions &&
            privateMentionAddEnabled &&
            !channelArchived &&
            scopeChannelId != null &&
            scopeChannelId != threadMembersChannelId?.value
    val scopeChannelIdObj = remember(scopeChannelId) { scopeChannelId?.let(::ChannelId) }
    val separateScopeSlice by
        remember(serverMembersStore, serverId, scopeChannelIdObj, needsSeparateScopeSlice) {
            if (needsSeparateScopeSlice && serverMembersStore != null && serverId != null && scopeChannelIdObj != null) {
                serverMembersStore.channelMemberSliceFlow(serverId, scopeChannelIdObj)
            } else {
                MutableStateFlow(SharedServerChannelMembersSlice(serverId = serverId?.value, channelId = scopeChannelId.orEmpty()))
            }
        }.collectAsState(
            if (needsSeparateScopeSlice && serverMembersStore != null && serverId != null && scopeChannelIdObj != null) {
                serverMembersStore.currentChannelMemberSlice(serverId, scopeChannelIdObj)
            } else {
                SharedServerChannelMembersSlice(serverId = serverId?.value, channelId = scopeChannelId.orEmpty())
            }
        )
    LaunchedEffect(needsSeparateScopeSlice, serverMembersStore, serverId, scopeChannelIdObj) {
        if (needsSeparateScopeSlice && serverMembersStore != null && serverId != null && scopeChannelIdObj != null) {
            serverMembersStore.loadChannelMembers(
                serverId = serverId,
                channelId = scopeChannelIdObj,
                priority = DataRequestPriority.Visible,
            )
        }
    }
    val effectiveSlice =
        if (scopeChannelId != null && scopeChannelId == threadMembersChannelId?.value) {
            threadChannelMembersSlice
        } else {
            separateScopeSlice
        }
    val canAddMembersKnown =
        if (usesAuthorityStore) {
            viewerAuthority is build.raft.app.model.ChannelViewerAuthorityView.Known
        } else {
            effectiveSlice.canAddMembersKnown
        }
    val canAddMembers =
        if (usesAuthorityStore) {
            build.raft.app.data.authorityAllows(viewerAuthority) { it.addChannelMembers }
        } else {
            effectiveSlice.canAddMembers
        }
    val addFromPrivateMention =
        threadCanAddFromPrivateMention(
            privateMentionAddEnabled = privateMentionAddEnabled,
            baseMemberScopedMentions = baseMemberScopedMentions,
            channelArchived = channelArchived,
            canAddMembersKnown = canAddMembersKnown,
            canAddMembers = canAddMembers,
        )
    return threadComposerEffectiveMentionScope(
        baseMemberScopedMentions = baseMemberScopedMentions,
        addFromPrivateMention = addFromPrivateMention,
    )
}
