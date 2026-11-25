package build.raft.app

import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.AttachmentUploadCapabilitiesCache
import build.raft.app.data.DataResult
import build.raft.app.data.isNonPermissionPlanOrQuota403Code
import build.raft.app.data.RaftJsonParseDispatcher
import build.raft.app.data.RaftMessageDbDispatcher
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeSnapshotLookup
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.withInboxFetchReason
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DirectAttachmentUploadOperation
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ReadReceiptResult
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.ScreenError
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.MemberRepository
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpOutcome
import build.raft.app.data.SavedStatusRepository
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedForegroundLoadGate
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.RaftThreadApi
import build.raft.app.data.RaftThreadApiClient
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.ThreadAttachmentCommentAnchor
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.SharedReferenceResolver
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.ThreadListSnapshot
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadSendMessageMention
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplyCacheIdentityTransitionAnchor
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.data.map
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.markdown.RaftMarkdownMessageRef
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.CanonicalMessageV2ThreadJoinConfirmed
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadDurableAccessEvictor
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.RaftCachedThreadReplyWindow
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

private const val SharedThreadZeroReplyOriginHydrationGraceMillis = 300L
private val processAttachmentUploadCapabilitiesCache =
    AttachmentUploadCapabilitiesCache(nowMillis = ::defaultNowMillis)

internal class SharedThreadDataSource(
    private val api: RaftThreadApi,
    private val sessionStore: RaftAuthSessionStore,
    private val pendingSendJournal: ThreadPendingSendJournal? = null,
    private val selectedServerStore: SelectedServerStore? = null,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val conversationRepository: ConversationRepository? = null,
    private val channelRepository: ChannelRepository? = null,
    private val homeSnapshotProvider: (suspend (ServerId) -> HomeSnapshot?)? = null,
    private val homeSnapshotLookupProvider: (suspend (ServerId) -> HomeSnapshotLookup?)? = null,
    private val immediateHomeSnapshotLookupProvider: ((ServerId) -> HomeSnapshotLookup?)? = null,
    private val channelProfileUpdateSink: suspend (ChannelProfileUpdateResult) -> Unit = {},
    private val messageStore: SharedThreadMessageStore? = null,
    // REQUIRED: the cache environment is an identity dimension of every reply cache. There is no
    // blank default; a caller that cannot name its environment owns no cache (task #1121).
    private val cacheEnvironmentIdProvider: () -> String,
    /** Current API environment used to bind direct-session uploads to one route authority. */
    private val apiEnvironmentIdProvider: () -> String = { cacheEnvironmentIdProvider() },
    /** Upload limits are per server and rarely change; share one TTL cache across thread pages. */
    private val attachmentUploadCapabilitiesCache: AttachmentUploadCapabilitiesCache =
        processAttachmentUploadCapabilitiesCache,
    // REQUIRED (task #1122): reply-cache identity comes only from the provider-owned authority
    // (`ThreadReplyCacheIdentityAuthority`). There is no private fallback resolver any more, so a
    // data source that cannot name its authority cannot be constructed.
    private val replyCacheIdentityAuthority: suspend () -> ThreadReplyCacheIdentitySnapshot?,
    private val replyCacheIdentityTransitionWaiter:
        (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)? = null,
    payloadStore: SharedThreadPayloadStore? = null,
    /**
     * Offline cache read entry (task #1123). Absent means "no durable first frame"; the store then
     * behaves exactly as before this seam existed. Production wires the V2 reader from the provider.
     */
    private val durableLastKnownReader: ThreadDurableLastKnownReader? = null,
    /**
     * Offline cache write entry (task #1124): on entering a channel / DM, its current REST page is
     * committed to the V2 partition so the reader above has something to show next time. Absent
     * means "never write"; production wires the V2 loader from the provider.
     */
    private val enteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader? = null,
    /**
     * Offline cache paging entry (task #168): an older / newer page that is exactly adjacent to the
     * stored V2 window is appended to it. Absent means "never write"; production wires the V2
     * adjacent-page writer from the provider.
     */
    private val durablePageExtender: ThreadDurablePageExtender? = null,
    /**
     * Lost-access eviction (data-layer plan, Tenny rule 2): drops a conversation's durable V2 window
     * after a background validation answered 403 / 404. Absent means memory-only eviction.
     */
    private val durableAccessEvictor: ThreadDurableAccessEvictor? = null,
    private val agentsApiClient: RaftAgentsApiClient? = null,
    private val actionCardsApiClient: RaftIntegrationsActionsPushApiClient? = null,
    private val savedStatusRepository: SavedStatusRepository? = null,
    private val readReceiptCoordinator: ReadReceiptCoordinator? = null,
    private val readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    private val threadPageSizeProvider: (ServerId?) -> Int? = { null },
    private val replyOriginHydrationGraceMillis: Long = SharedThreadZeroReplyOriginHydrationGraceMillis,
    private val canonicalConversationIdentityPublisher:
        suspend (ReplyCacheProvenance, build.raft.app.model.ConversationSummary) -> Boolean = { _, _ -> false },
    /**
     * The header's stored channel detail (offline resource): the accepted canonical detail is stored, fenced
     * from when its request started; a conversation that is gone (404 / lost permission) loses its copy.
     */
    private val storedConversationDetails: build.raft.app.data.StoredConversationDetails? = null,
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
    /**
     * Task #43: shared create-agent coordinator used by the action-card
     * path. Mirrors the field on `SharedMembersDataSource`; the actual
     * body construction + API call lives in
     * [build.raft.app.data.SharedCreateAgentCoordinator] so the
     * Members page and Thread action card share the same code path.
     */
    private val createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator? = null,
    /**
     * What the Activity list holds right now (artin 10-23). Read at page start as a candidate
     * snapshot for the first frame; see [threadActivityOpenSeedMessages]. Absent means no seed.
     */
    private val activityOpenSeedSource: (() -> ThreadActivityOpenSeedSource?)? = null,
    /**
     * Off-main dispatcher for durable SQLite writes (such as `touchUserAccess` LRU refreshes) so
     * [SharedThreadStore] never runs synchronous SQLite transactions on `viewModelScope` / Main.
     */
    private val durableDbDispatcher: CoroutineDispatcher = RaftMessageDbDispatcher,
) {
    /**
     * Public accessor for the [build.raft.app.data.SharedCreateAgentCoordinator]
     * field. Used by callers (e.g. `SettingsHomePage` which constructs
     * `SharedThreadDataSource` and forwards to `SharedThreadRouteContent`)
     * that need the coordinator to wire up the action-card create-agent
     * dialog. Returns null when the data source was constructed without
     * a coordinator; in that case the create-agent action card falls back
     * to the legacy name/description/machineId-only path.
     */
    val createAgentCoordinatorOrNull: build.raft.app.data.SharedCreateAgentCoordinator?
        get() = createAgentCoordinator

    internal fun seedThreadAuthorProfiles(
        serverId: String?,
        payload: RaftThreadScreenPayload,
        source: String
    ) {
        val scopedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        serverMembersStore?.seedThreadAuthorProfiles(
            serverId = scopedServerId,
            messages = payload.threadAuthorProfileCandidates(),
            source = source,
            diagnosticsLogger = diagnosticsLogger
        )
    }

    internal fun seedRealtimeThreadAuthorProfiles(
        serverId: String?,
        event: RealtimeEvent,
        currentUserId: String?
    ) {
        val scopedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        val messageItems =
            when (event) {
                is MessageNew -> listOf(event.message)
                is MessageUpdated -> listOf(event.message)
                is SyncResumeResponse -> event.messages
                else -> emptyList()
            }.map { message -> message.toRaftThreadMessageItem(currentUserId) }
        serverMembersStore?.seedThreadAuthorProfiles(
            serverId = scopedServerId,
            messages = messageItems,
            source = "realtime",
            diagnosticsLogger = diagnosticsLogger
        )
    }

    internal fun seedAcceptedRealtimeThreadReplyAuthorProfile(
        serverId: String?,
        reply: build.raft.app.network.sync.ReplyPreview
    ) {
        val scopedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        serverMembersStore?.seedAcceptedThreadReplyAuthorProfile(
            serverId = scopedServerId,
            reply = reply,
            source = "realtime_inline",
            diagnosticsLogger = diagnosticsLogger
        )
    }

    constructor(
        networkApi: SharedThreadNetworkApi,
        sessionStore: RaftAuthSessionStore,
        pendingSendJournal: ThreadPendingSendJournal? = null,
        selectedServerStore: SelectedServerStore? = null,
        memberRepository: MemberRepository? = null,
        serverMembersStore: SharedServerMembersStore? = null,
        conversationRepository: ConversationRepository? = null,
        channelRepository: ChannelRepository? = null,
        homeSnapshotProvider: (suspend (ServerId) -> HomeSnapshot?)? = null,
        homeSnapshotLookupProvider: (suspend (ServerId) -> HomeSnapshotLookup?)? = null,
        immediateHomeSnapshotLookupProvider: ((ServerId) -> HomeSnapshotLookup?)? = null,
        channelProfileUpdateSink: suspend (ChannelProfileUpdateResult) -> Unit = {},
        messageStore: SharedThreadMessageStore? = null,
        cacheEnvironmentIdProvider: () -> String,
        apiEnvironmentIdProvider: () -> String = cacheEnvironmentIdProvider,
        replyCacheIdentityAuthority: suspend () -> ThreadReplyCacheIdentitySnapshot?,
        replyCacheIdentityTransitionWaiter:
            (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)? = null,
        payloadStore: SharedThreadPayloadStore? = null,
        durableLastKnownReader: ThreadDurableLastKnownReader? = null,
        enteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader? = null,
        durablePageExtender: ThreadDurablePageExtender? = null,
        durableAccessEvictor: ThreadDurableAccessEvictor? = null,
        agentsApiClient: RaftAgentsApiClient? = null,
        actionCardsApiClient: RaftIntegrationsActionsPushApiClient? = null,
        savedStatusRepository: SavedStatusRepository? = null,
        readReceiptCoordinator: ReadReceiptCoordinator? = null,
        readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
        threadPageSizeProvider: (ServerId?) -> Int? = { null },
        replyOriginHydrationGraceMillis: Long = SharedThreadZeroReplyOriginHydrationGraceMillis,
        canonicalConversationIdentityPublisher:
            suspend (ReplyCacheProvenance, build.raft.app.model.ConversationSummary) -> Boolean = { _, _ -> false },
        storedConversationDetails: build.raft.app.data.StoredConversationDetails? = null,
        createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator? = null,
        diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
        activityOpenSeedSource: (() -> ThreadActivityOpenSeedSource?)? = null,
        threadParseDispatcher: CoroutineDispatcher = RaftJsonParseDispatcher,
        durableDbDispatcher: CoroutineDispatcher = RaftMessageDbDispatcher,
    ) : this(
        api = RaftThreadApiClient(networkApi, diagnosticsLogger, threadParseDispatcher),
        sessionStore = sessionStore,
        pendingSendJournal = pendingSendJournal,
        selectedServerStore = selectedServerStore,
        memberRepository = memberRepository,
        serverMembersStore = serverMembersStore,
        conversationRepository = conversationRepository,
        channelRepository = channelRepository,
        homeSnapshotProvider = homeSnapshotProvider,
        homeSnapshotLookupProvider = homeSnapshotLookupProvider,
        immediateHomeSnapshotLookupProvider = immediateHomeSnapshotLookupProvider,
        channelProfileUpdateSink = channelProfileUpdateSink,
        messageStore = messageStore,
        cacheEnvironmentIdProvider = cacheEnvironmentIdProvider,
        apiEnvironmentIdProvider = apiEnvironmentIdProvider,
        replyCacheIdentityAuthority = replyCacheIdentityAuthority,
        replyCacheIdentityTransitionWaiter = replyCacheIdentityTransitionWaiter,
        payloadStore = payloadStore,
        durableLastKnownReader = durableLastKnownReader,
        enteredScopeSnapshotLoader = enteredScopeSnapshotLoader,
        durablePageExtender = durablePageExtender,
        durableAccessEvictor = durableAccessEvictor,
        agentsApiClient = agentsApiClient,
        actionCardsApiClient = actionCardsApiClient,
        savedStatusRepository = savedStatusRepository,
        readReceiptCoordinator = readReceiptCoordinator,
        readReceiptOwnerFactory = readReceiptOwnerFactory,
        threadPageSizeProvider = threadPageSizeProvider,
        replyOriginHydrationGraceMillis = replyOriginHydrationGraceMillis,
        canonicalConversationIdentityPublisher = canonicalConversationIdentityPublisher,
        storedConversationDetails = storedConversationDetails,
        diagnosticsLogger = diagnosticsLogger,
        createAgentCoordinator = createAgentCoordinator,
        activityOpenSeedSource = activityOpenSeedSource,
        durableDbDispatcher = durableDbDispatcher,
    )

    /**
     * The messages Activity holds for this route's conversation (artin 10-23), matched to the route
     * identity and to the current owner; empty when nothing matches or the owner changed.
     */
    internal fun activityOpenSeedMessagesIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
    ): List<RaftThreadMessageItem> {
        val source = activityOpenSeedSource?.invoke() ?: return emptyList()
        if (!isCurrentReplyCacheProvenance(context.provenance)) return emptyList()
        return threadActivityOpenSeedMessages(
            source = source,
            routeIdentity = routeIdentity,
            serverId = context.provenance.serverId,
            principalId = context.provenance.principalId,
            // Task #80: the member directory names a seeded sender (human or agent) before the
            // canonical load does; the Activity row itself may carry no name at all.
            memberNames =
                context.provenance.serverId.trim().takeIf { it.isNotBlank() }
                    ?.let { serverId -> serverMembersStore?.currentKnownMemberProfilesById(ServerId(serverId)) }
                    ?.mapValues { (_, profile) -> profile.displayName }
                    .orEmpty(),
        )
    }

    private val payloadCache = payloadStore ?: SharedThreadPayloadStore()
    /** Shared with Activity reply prewarm through the process-wide [SharedThreadPayloadStore]. */
    internal val foregroundLoadGate: SharedForegroundLoadGate get() = payloadCache.foregroundLoadGate
    /** Process-wide with [payloadCache]: a reference resolved on one page is local on the next. */
    internal val referenceResolver: SharedReferenceResolver get() = payloadCache.referenceResolver
    private val messageResolver = messageStore ?: SharedThreadMessageStore()
    /** Reply-cache provenance (and its lock) and pending-send journal access; see [ThreadReplyCacheProvenanceOwner]. */
    private val provenanceOwner =
        ThreadReplyCacheProvenanceOwner(
            pendingSendJournal = pendingSendJournal,
            payloadCache = payloadCache,
            diagnosticsLogger = diagnosticsLogger,
        )
    /** Action-card commands (commit, reconfirm, execute, mark executed); see [ThreadActionCardCommands]. */
    private val actionCardCommands =
        ThreadActionCardCommands(
            agentsApiClient = agentsApiClient,
            actionCardsApiClient = actionCardsApiClient,
            createAgentCoordinator = createAgentCoordinator,
            channelRepository = channelRepository,
            memberRepository = memberRepository,
            serverMembersStore = serverMembersStore,
            readSession = ::readSession,
        )
    /** Channel-member commands (join, profile, members, leave, archive, delete); see [ThreadChannelMemberCommands]. */
    private val channelMemberCommands =
        ThreadChannelMemberCommands(
            memberRepository = memberRepository,
            serverMembersStore = serverMembersStore,
            channelRepository = channelRepository,
            readSession = ::readSession,
        )
    /** Single-item actions (save, react, attachment comments, follow, mark unread); see [ThreadItemActions]. */
    private val itemActions =
        ThreadItemActions(
            api = api,
            conversationRepository = conversationRepository,
            selectedServerStore = selectedServerStore,
            readReceiptCoordinator = readReceiptCoordinator,
            readReceiptOwnerFactory = readReceiptOwnerFactory,
            readSession = ::readSession,
        )
    /** Durable page reads, writes and eviction, and which scopes they may use; see [ThreadDurableCacheGateway]. */
    private val durableCacheGateway: ThreadDurableCacheGateway =
        ThreadDurableCacheGateway(
            provenanceOwner = provenanceOwner,
            payloadCache = payloadCache,
            durableLastKnownReader = durableLastKnownReader,
            enteredScopeSnapshotLoader = enteredScopeSnapshotLoader,
            durablePageExtender = durablePageExtender,
            durableAccessEvictor = durableAccessEvictor,
            durableDbDispatcher = durableDbDispatcher,
            diagnosticsLogger = diagnosticsLogger,
            readReplyResolutionScope = ::readReplyResolutionScope,
            threadPageSize = ::threadPageSize,
        )
    /** Reply-count facts and the cached parent-conversation updates; see [ThreadReplyCountProjection]. */
    private val replyCountProjection =
        ThreadReplyCountProjection(
            provenanceOwner = provenanceOwner,
            payloadCache = payloadCache,
            recordReplyWindows = ::recordReplyWindows,
        )
    /** Reply-thread resolutions (sole owner of their cache) and prewarm; see [ThreadReplyThreadPrewarm]. */
    private val replyThreadPrewarm: ThreadReplyThreadPrewarm =
        ThreadReplyThreadPrewarm(
            provenanceOwner = provenanceOwner,
            payloadCache = payloadCache,
            messageStore = messageStore,
            conversationRepository = conversationRepository,
            selectedServerStore = selectedServerStore,
            enteredScopeSnapshotLoader = enteredScopeSnapshotLoader,
            diagnosticsLogger = diagnosticsLogger,
            readReplyResolutionScope = ::readReplyResolutionScope,
            durableHeadEpochOrNull = durableCacheGateway::durableHeadEpochOrNull,
            cachedPayloadIfCurrent = ::cachedPayloadIfCurrent,
            seedAuthorProfiles = { serverId, payload, source -> seedThreadAuthorProfiles(serverId, payload, source = source) },
            fetchInitialReplyThreadPayload = { session, basePayload, requestPriority, replyResolutionScope, onRawReplyPage ->
                fetchInitialReplyThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    requestPriority = requestPriority,
                    replyResolutionScope = replyResolutionScope,
                    onRawReplyPage = onRawReplyPage,
                )
            },
            fetchFocusedInitialThreadPayload = { session, basePayload, routeThreadId, accessFallback, replyResolutionScope, requestPriority ->
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = routeThreadId,
                    accessFallback = accessFallback,
                    replyResolutionScope = replyResolutionScope,
                    requestPriority = requestPriority,
                )
            },
            persistReplyThreadPrewarmPage = durableCacheGateway::persistReplyThreadPrewarmPageIfCurrent,
        )
    /** Who may read and write a conversation when it opens, and the Home row behind it; see [ThreadConversationAccessResolution]. */
    private val conversationAccessResolution: ThreadConversationAccessResolution =
        ThreadConversationAccessResolution(
            provenanceOwner = provenanceOwner,
            serverMembersStore = serverMembersStore,
            conversationRepository = conversationRepository,
            channelRepository = channelRepository,
            homeSnapshotProvider = homeSnapshotProvider,
            homeSnapshotLookupProvider = homeSnapshotLookupProvider,
            immediateHomeSnapshotLookupProvider = immediateHomeSnapshotLookupProvider,
            diagnosticsLogger = diagnosticsLogger,
            readSession = ::readSession,
            readReplyResolutionScope = ::readReplyResolutionScope,
            loadTaskConversationRef = ::loadTaskConversationRef,
        )
    /** Reply-parent lookup in the message store and its context request, and what is remembered of it; see [ThreadReplyParentMessages]. */
    private val replyParentMessages: ThreadReplyParentMessages =
        ThreadReplyParentMessages(
            provenanceOwner = provenanceOwner,
            messageStore = messageStore,
            messageResolver = messageResolver,
            replyThreadPrewarm = replyThreadPrewarm,
            diagnosticsLogger = diagnosticsLogger,
            captureReplyCacheProducerContext = ::captureReplyCacheProducerContext,
            readReplyResolutionScope = ::readReplyResolutionScope,
            fetchThreadSnapshot = { fallbackCode, expectedProvenance, allowCapturedScopeResultAfterProvenanceChange, call ->
                fetchThreadSnapshotFromRepository(
                    fallbackCode = fallbackCode,
                    expectedProvenance = expectedProvenance,
                    allowCapturedScopeResultAfterProvenanceChange = allowCapturedScopeResultAfterProvenanceChange,
                    call = call,
                )
            },
        )
    /** Attachment upload, its authority and limits, attachment URLs and the files list; see [ThreadAttachmentTransfers]. */
    private val attachmentTransfers: ThreadAttachmentTransfers =
        ThreadAttachmentTransfers(
            api = api,
            sessionStore = sessionStore,
            conversationRepository = conversationRepository,
            apiEnvironmentIdProvider = apiEnvironmentIdProvider,
            attachmentUploadCapabilitiesCache = attachmentUploadCapabilitiesCache,
        )
    /** What a Markdown channel, task, thread or message reference opens; see [ThreadMarkdownRefLaunch]. */
    private val markdownRefLaunch: ThreadMarkdownRefLaunch =
        ThreadMarkdownRefLaunch(
            provenanceOwner = provenanceOwner,
            payloadCache = payloadCache,
            messageStore = messageStore,
            messageResolver = messageResolver,
            replyThreadPrewarm = replyThreadPrewarm,
            readSession = ::readSession,
            readReplyResolutionScope = ::readReplyResolutionScope,
            resolveHomeSnapshotLookup = conversationAccessResolution::resolveHomeSnapshotLookup,
            currentHomeSnapshot = conversationAccessResolution::currentHomeSnapshot,
            currentKnownMarkdownMemberProfilesById = conversationAccessResolution::currentKnownMarkdownMemberProfilesById,
            fetchThreadSnapshot = { fallbackCode, expectedProvenance, call ->
                fetchThreadSnapshotFromRepository(
                    fallbackCode = fallbackCode,
                    expectedProvenance = expectedProvenance,
                    call = call,
                )
            },
            markdownReplyCacheScopeCapturedHook = { onMarkdownReplyCacheScopeCapturedForTest },
        )
    private val cacheEnvironmentId = MutableStateFlow("")
    private val cacheServerId = MutableStateFlow("")
    private val cachePrincipalId = MutableStateFlow("")
    private val cachePayloadOwnerScopeId = MutableStateFlow("")
    private val replyCacheCaptureSequence = atomic(0L)
    internal val replyCacheProvenanceState: StateFlow<ReplyCacheProvenance?> =
        provenanceOwner.replyCacheProvenanceState
    // The lock and the provenance it guards are owned by [provenanceOwner]; read through it.
    private val replyCacheProvenance: StateFlow<ReplyCacheProvenance?> get() = provenanceOwner.replyCacheProvenanceState
    internal var onReplyCacheIdentitySourceCapturedForTest: (suspend () -> Unit)? = null
    internal var onMarkdownReplyCacheScopeCapturedForTest: (suspend () -> Unit)? = null
    internal var onReplyThreadAccessValidatedForTest: (suspend () -> Unit)? = null
    internal var onReplyCacheFirstFrameAdmissionForTest: (suspend () -> Unit)? = null

    private fun threadPageSize(serverId: String?): Int =
        SharedThreadPaging.resolve(
            threadPageSizeProvider(serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId))
        )

    suspend fun readSession(): StoredAuthSession? = readReplyResolutionScope()?.session

    internal suspend fun captureReplyCacheProducerContext(): ReplyCacheProducerContext? =
        readReplyResolutionScope()?.let { scope ->
            ReplyCacheProducerContext(scope.session, scope.provenance)
        }

    private fun durableHeadEpochOrNull(provenance: ReplyCacheProvenance): Long? =
        durableCacheGateway.durableHeadEpochOrNull(provenance)

    private suspend fun durableLocalRevOrNull(provenance: ReplyCacheProvenance): Long? =
        durableCacheGateway.durableLocalRevOrNull(provenance)

    internal fun pendingSendJournalOrNull(): ThreadPendingSendJournal? = provenanceOwner.pendingSendJournalOrNull()

    internal fun persistPendingLocalSend(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        message: RaftThreadMessageItem,
    ): Boolean = provenanceOwner.persistPendingLocalSend(provenance, route, message)

    internal fun clearPendingLocalSend(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
    ): ThreadPendingSendJournal.ConsumeResult = provenanceOwner.clearPendingLocalSend(provenance, route, localId)

    internal fun clearPendingLocalSendForLocalSend(
        expectedProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
    ): ThreadPendingSendJournal.ConsumeResult =
        provenanceOwner.clearPendingLocalSendForLocalSend(expectedProvenance, route, localId)

    internal val hasDurablePendingSendJournal: Boolean
        get() = provenanceOwner.hasDurablePendingSendJournal

    internal fun markPendingLocalSendFailedForSendOwner(
        sendProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        randomId: String?,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean =
        provenanceOwner.markPendingLocalSendFailedForSendOwner(sendProvenance, route, localId, randomId, errorCode, errorMessage)

    internal fun markPendingLocalSendFailedForLocalSend(
        expectedProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean =
        provenanceOwner.markPendingLocalSendFailedForLocalSend(expectedProvenance, route, localId, errorCode, errorMessage)

    internal fun restorePendingLocalSends(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        canonicalPayload: RaftThreadScreenPayload,
    ): RaftThreadScreenPayload = provenanceOwner.restorePendingLocalSends(provenance, route, canonicalPayload)

    private suspend fun readReplyResolutionScope(): ReplyResolutionScope? {
        val captureSequence = replyCacheCaptureSequence.incrementAndGet()
        var transitionAttempts = 0
        while (true) {
            var source: ThreadReplyCacheIdentitySnapshot? = null
            var sampleAttempts = 0
            while (source == null && sampleAttempts < 4) {
                sampleAttempts += 1
                val candidate = readReplyCacheIdentitySource()
                onReplyCacheIdentitySourceCapturedForTest?.invoke()
                val confirmed = readReplyCacheIdentitySource()
                if (candidate == confirmed) {
                    source = confirmed
                    break
                }
            }
            if (source != null) {
                val provenance = publishReplyCacheProvenance(source, captureSequence)
                cacheEnvironmentId.value = provenance.environmentId
                cacheServerId.value = provenance.serverId
                cachePrincipalId.value = provenance.principalId
                cachePayloadOwnerScopeId.value = provenance.threadPayloadOwnerScopeId.orEmpty()
                return ReplyResolutionScope(
                    session = source.session,
                    provenance = provenance,
                    sharedMessageStore = messageStore != null
                )
            }

            // A durable session can become visible before the provider-owned auth-revision collector
            // publishes its matching immutable context or before this shell's selected-server owner
            // converges. Retire the old token before waiting so stale page producers stay zero-write,
            // then pass that retired authority as the only license for an A→B owner transition. Stable
            // hybrids, environment mismatches, and missing authority remain fail-closed.
            val transitionAnchor =
                replyCacheProvenance.value?.let { current ->
                    ThreadReplyCacheIdentityTransitionAnchor(
                        environmentId = current.environmentId,
                        principalId = current.principalId,
                        authGeneration = current.authGeneration,
                        selectionGeneration = current.selectionGeneration,
                    )
                }
            clearReplyCacheProvenance(captureSequence)
            val shouldRetry =
                transitionAttempts < 4 &&
                    replyCacheIdentityTransitionWaiter?.invoke(transitionAnchor) == true
            if (!shouldRetry) return null
            transitionAttempts += 1
        }
    }

    private suspend fun readReplyCacheIdentitySource(): ThreadReplyCacheIdentitySnapshot? =
        replyCacheIdentityAuthority()

    private fun publishReplyCacheProvenance(
        source: ThreadReplyCacheIdentitySnapshot,
        captureSequence: Long
    ): ReplyCacheProvenance = provenanceOwner.publishReplyCacheProvenance(source, captureSequence)

    private fun clearReplyCacheProvenance(captureSequence: Long) {
        provenanceOwner.clearReplyCacheProvenance(captureSequence)
        cacheEnvironmentId.value = ""
        cacheServerId.value = ""
        cachePrincipalId.value = ""
        cachePayloadOwnerScopeId.value = ""
    }

    internal fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenance(expected)

    internal fun isCurrentReplyCacheProvenanceForLocalSend(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenanceForLocalSend(expected)

    internal fun currentReplyCacheProvenanceForLocalSend(
        expected: ReplyCacheProvenance
    ): ReplyCacheProvenance? = provenanceOwner.currentReplyCacheProvenanceForLocalSend(expected)

    internal fun currentReplyCacheProvenance(): ReplyCacheProvenance? = provenanceOwner.currentReplyCacheProvenance()

    internal fun <T> withCurrentReplyCacheProvenance(
        expected: ReplyCacheProvenance,
        block: () -> T
    ): T? = provenanceOwner.withCurrentReplyCacheProvenance(expected, block)

    internal fun applyReplyCountFacts(
        threadId: String,
        expected: ReplyCacheProvenance,
        payload: RaftThreadScreenPayload,
    ): RaftThreadScreenPayload? = provenanceOwner.applyReplyCountFacts(threadId, expected, payload)

    internal fun <T> withCurrentReplyCacheProvenanceForLocalSend(
        expected: ReplyCacheProvenance,
        block: (ReplyCacheProvenance) -> T
    ): T? = provenanceOwner.withCurrentReplyCacheProvenanceForLocalSend(expected, block)

    fun cachePayload(
        payload: RaftThreadScreenPayload,
        serverId: String = cacheServerId.value,
        expectedGeneration: SharedThreadFreshnessGeneration? = null,
        ownerScopeId: String = cachePayloadOwnerScopeId.value,
        expectedProvenance: ReplyCacheProvenance? = null
    ): Boolean = provenanceOwner.cachePayload(payload, serverId, expectedGeneration, ownerScopeId, expectedProvenance)

    fun cachedPayload(
        threadId: String,
        ownerScopeId: String = cachePayloadOwnerScopeId.value,
        serverId: String = cacheServerId.value,
        principalId: String = cachePrincipalId.value,
        environmentId: String = cacheEnvironmentId.value
    ): RaftThreadScreenPayload? =
        ownerScopeId
            .takeIf { ownerScopeId -> ownerScopeId.isNotBlank() }
            ?.let { ownerScopeId ->
                payloadCache.get(
                    serverId,
                    threadId,
                    environmentId,
                    principalId,
                    ownerScopeId
                )
            }

    /**
     * True when the shared memory cache holds this reply thread's content at its CURRENT freshness
     * generation, i.e. a foreground open already loaded it and nothing has changed since. Activity
     * prewarm uses it to drop only a queued prewarm whose work is genuinely done.
     */
    internal suspend fun hasCurrentReplyThreadContent(marker: ActivityReplyThreadPrewarmMarker): Boolean {
        val key = marker.key
        if (key.serverId.isBlank() || key.threadChannelId.isBlank()) return false
        val provenance = readReplyResolutionScope()?.provenance ?: return false
        if (provenance.serverId != key.serverId) return false
        return cachedPayloadIfCurrent(key.threadChannelId, provenance) != null
    }

    internal fun cachedPayloadIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance
    ): RaftThreadScreenPayload? = provenanceOwner.cachedPayloadIfCurrent(threadId, expectedProvenance)

    /** Hands 4cf4c781: records (or drops) that this owner's canonical access to [threadId] resolved. */
    internal fun rememberResolvedThreadAccess(
        threadId: String,
        provenance: ReplyCacheProvenance,
        resolved: Boolean,
    ) {
        val ownerScopeId = provenance.threadPayloadOwnerScopeId ?: return
        payloadCache.setResolvedAccess(provenance.serverId, threadId, ownerScopeId, resolved)
    }

    internal fun hasResolvedThreadAccessIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean = provenanceOwner.hasResolvedThreadAccessIfCurrent(threadId, expectedProvenance)

    internal fun supersededCachedPayloadIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance
    ): RaftThreadScreenPayload? = provenanceOwner.supersededCachedPayloadIfCurrent(threadId, expectedProvenance)

    internal fun staleConversationCauseIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance
    ): String? = provenanceOwner.staleConversationCauseIfCurrent(threadId, expectedProvenance)

    /** A canonical refresh of [threadId] landed: its local copies are current again. */
    internal fun clearStaleConversation(threadId: String, provenance: ReplyCacheProvenance) {
        val ownerScopeId = provenance.threadPayloadOwnerScopeId ?: return
        payloadCache.clearConversationStale(provenance.serverId, threadId, ownerScopeId)
    }

    internal suspend fun beginEnteredPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent? = null,
    ): ThreadDurableEnterTicket? = durableCacheGateway.beginEnteredPageIfCurrent(routeIdentity, context, replyParent)

    internal val hasEnteredPageWriter: Boolean get() = durableCacheGateway.hasEnteredPageWriter

    internal suspend fun durableRequestStampIfCurrent(context: ReplyCacheProducerContext): ThreadDurableRequestStamp? =
        durableCacheGateway.durableRequestStampIfCurrent(context)

    internal suspend fun persistEnteredPageIfCurrent(
        ticket: ThreadDurableEnterTicket?,
        stamp: ThreadDurableRequestStamp?,
        page: ThreadDurableEnteredPage,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent? = null,
    ) = durableCacheGateway.persistEnteredPageIfCurrent(ticket, stamp, page, routeIdentity, context, replyParent)

    internal suspend fun abandonEnteredPage(ticket: ThreadDurableEnterTicket) = durableCacheGateway.abandonEnteredPage(ticket)

    internal suspend fun extendDurableWindowIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent? = null,
        headEpochAtRequest: Long? = null,
        revAtRequest: Long? = null,
    ): Boolean =
        durableCacheGateway.extendDurableWindowIfCurrent(
            routeIdentity,
            context,
            direction,
            anchorSeq,
            rawPage,
            replyParent,
            headEpochAtRequest,
            revAtRequest,
        )

    internal suspend fun persistDurableContextPageIfCurrent(page: SharedThreadDurableContextPage): Boolean =
        durableCacheGateway.persistDurableContextPageIfCurrent(page)

    internal suspend fun applyDurableLatestPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent? = null,
        baseline: CanonicalMessageV2EnterBaseline? = null,
        headEpochAtRequest: Long? = null,
        revAtRequest: Long? = null,
    ): Boolean =
        durableCacheGateway.applyDurableLatestPageIfCurrent(
            routeIdentity,
            context,
            rawPage,
            replyParent,
            baseline,
            headEpochAtRequest,
            revAtRequest,
        )

    internal suspend fun durableLocalRevIfCurrent(): Long? = durableCacheGateway.durableLocalRevIfCurrent()

    internal suspend fun beginDurableLatestPageBaselineIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        replyParent: ThreadDurableReplyParent? = null,
    ): CanonicalMessageV2EnterBaseline? = durableCacheGateway.beginDurableLatestPageBaselineIfCurrent(routeIdentity, replyParent)

    internal fun threadJoinConfirmationsIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent?,
    ): Flow<CanonicalMessageV2ThreadJoinConfirmed>? = durableCacheGateway.threadJoinConfirmationsIfCurrent(routeIdentity, context, replyParent)

    internal suspend fun abandonDurableLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) =
        durableCacheGateway.abandonDurableLatestPageBaseline(baseline)

    internal suspend fun cachedReplyWindowsIfCurrent(
        page: ThreadRepliesPageIdentity,
        context: ReplyCacheProducerContext,
    ): Map<String, RaftCachedThreadReplyWindow> = durableCacheGateway.cachedReplyWindowsIfCurrent(page, context)

    internal fun recordReplyWindows(projections: Collection<ThreadRepliesAcceptedProjection>) =
        durableCacheGateway.recordReplyWindows(projections)

    internal suspend fun touchDurableUserAccess(scope: SyncScopeKey) = durableCacheGateway.touchDurableUserAccess(scope)

    internal suspend fun durableLastKnownPayloadIfCurrent(
        routePayload: RaftThreadScreenPayload,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        allowNewestFallbackForMissingAnchor: Boolean = true,
    ): RaftThreadScreenPayload? =
        durableCacheGateway.durableLastKnownPayloadIfCurrent(
            routePayload,
            routeIdentity,
            context,
            allowNewestFallbackForMissingAnchor,
        )

    internal suspend fun durableLastKnownResultIfCurrent(
        routePayload: RaftThreadScreenPayload,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        allowNewestFallbackForMissingAnchor: Boolean = true,
    ): SharedThreadDurableLastKnownResult? =
        durableCacheGateway.durableLastKnownResultIfCurrent(
            routePayload,
            routeIdentity,
            context,
            allowNewestFallbackForMissingAnchor,
        )

    internal suspend fun durableOlderPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        beforeSeq: Long,
        expectedOwnerScopeId: String?,
        replyParent: ThreadDurableReplyParent? = null,
        crossGaps: Boolean = false,
        allowLegacyStandIn: Boolean = false,
    ): SharedThreadDurableOlderPage? =
        durableCacheGateway.durableOlderPageIfCurrent(
            routeIdentity,
            context,
            beforeSeq,
            expectedOwnerScopeId,
            replyParent,
            crossGaps,
            allowLegacyStandIn,
        )

    internal suspend fun durableNewerPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        afterSeq: Long,
        expectedOwnerScopeId: String?,
        replyParent: ThreadDurableReplyParent? = null,
        crossGaps: Boolean = false,
    ): SharedThreadDurableNewerPage? =
        durableCacheGateway.durableNewerPageIfCurrent(
            routeIdentity,
            context,
            afterSeq,
            expectedOwnerScopeId,
            replyParent,
            crossGaps,
        )

    suspend fun rememberedReplyThreadAccessFailure(
        basePayload: RaftThreadScreenPayload
    ): ScreenError? {
        val context = captureReplyCacheProducerContext() ?: return null
        return rememberedReplyThreadAccessFailure(basePayload, context)
    }

    internal suspend fun rememberedReplyThreadAccessFailure(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext
    ): ScreenError? {
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        val resolutionScope =
            ReplyResolutionScope(
                session = context.session,
                provenance = context.provenance,
                sharedMessageStore = messageStore != null
            )
        val key = replyThreadAccessFlightKey(resolutionScope, basePayload) ?: return null
        return payloadCache.replyThreadAccessFailure(key)
    }

    internal suspend fun clearRememberedReplyThreadAccessFailure(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
    ): Boolean {
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        val resolutionScope =
            ReplyResolutionScope(
                session = context.session,
                provenance = context.provenance,
                sharedMessageStore = messageStore != null,
            )
        val key = replyThreadAccessFlightKey(resolutionScope, basePayload) ?: return true
        payloadCache.clearReplyThreadAccessFailure(key)
        return isCurrentReplyCacheProvenance(context.provenance)
    }

    private fun replyThreadAccessFlightKey(
        resolutionScope: ReplyResolutionScope,
        basePayload: RaftThreadScreenPayload
    ): SharedReplyThreadAccessFlightKey? {
        if (basePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return null
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return null
        val provenance = resolutionScope.provenance
        val authBinding =
            listOf(
                provenance.generation.toString(),
                provenance.authGeneration.toString(),
                provenance.selectionGeneration.toString(),
                resolutionScope.session.accessToken
            ).joinToString("\u001f")
        return sharedReplyThreadAccessFlightKey(
            environmentId = provenance.environmentId,
            principalId = provenance.principalId,
            serverId = provenance.serverId,
            authBinding = authBinding,
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId
        )
    }

    private suspend fun replyThreadAccessAuthorityStillCurrent(
        expectedKey: SharedReplyThreadAccessFlightKey,
        expectedProvenance: ReplyCacheProvenance,
        basePayload: RaftThreadScreenPayload
    ): Boolean {
        val currentScope = readReplyResolutionScope() ?: return false
        return currentScope.provenance == expectedProvenance &&
            replyThreadAccessFlightKey(currentScope, basePayload) == expectedKey
    }

    // Reserved for UNRECOVERABLE scenarios only (task #562 boundary):
    // permission/visibility loss, epoch/server switches, identity proof
    // failures. Ordinary realtime traffic warms the cache with the live
    // store's merged snapshot instead (see applyRealtimeEvent). Narrow by
    // design: removes exactly one thread's candidate; never touches durable
    // stores, the message resolver, or the active screen's in-memory state.
    fun invalidateCachedPayload(payload: RaftThreadScreenPayload) {
        val threadId = payload.resolveThreadRouteIdentity().timelineChannelId ?: return
        val ownerScopeId = cachePayloadOwnerScopeId.value.takeIf { it.isNotBlank() } ?: return
        payloadCache.remove(cacheServerId.value, threadId, ownerScopeId)
    }

    internal suspend fun evictLostConversationAccess(
        payload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean = durableCacheGateway.evictLostConversationAccess(payload, expectedProvenance)

    internal fun invalidateCachedPayload(
        payload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean = provenanceOwner.invalidateCachedPayload(payload, expectedProvenance)

    suspend fun withCachedReplyParentCandidate(basePayload: RaftThreadScreenPayload): RaftThreadScreenPayload =
        replyParentMessages.withCachedReplyParentCandidate(basePayload)

    internal fun withCachedReplyParentCandidate(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext
    ): RaftThreadScreenPayload = replyParentMessages.withCachedReplyParentCandidate(basePayload, context)

    suspend fun resolveReplyOrigin(
        basePayload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance? = null
    ): SharedThreadOriginResolution = replyParentMessages.resolveReplyOrigin(basePayload, expectedProvenance)

    fun rememberReplyParentCandidate(
        provenance: ReplyCacheProvenance,
        serverId: String?,
        parentChannelId: String?,
        parentMessage: RaftThreadMessageItem,
        threadChannelId: String?,
        replyCount: Int = 0
    ): Boolean =
        replyParentMessages.rememberReplyParentCandidate(provenance, serverId, parentChannelId, parentMessage, threadChannelId, replyCount)

    fun rememberReplyParentReplyCount(parentChannelId: String?, parentMessageId: String, replyCount: Int) =
        replyParentMessages.rememberReplyParentReplyCount(parentChannelId, parentMessageId, replyCount)

    suspend fun withConversationAccessFallback(basePayload: RaftThreadScreenPayload): RaftThreadScreenPayload =
        conversationAccessResolution.withConversationAccessFallback(basePayload)

    suspend fun withKnownMarkdownChannelNames(payload: RaftThreadScreenPayload): RaftThreadScreenPayload =
        conversationAccessResolution.withKnownMarkdownChannelNames(payload)

    suspend fun homeConversationContextResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution = conversationAccessResolution.homeConversationContextResolution(payload)

    suspend fun initialConversationAccessResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution = conversationAccessResolution.initialConversationAccessResolution(payload)

    suspend fun replyParentAccessFallback(payload: RaftThreadScreenPayload): ReplyParentAccessFallbackResult =
        conversationAccessResolution.replyParentAccessFallback(payload)

    private suspend fun replyAccessResolutionOwnerStillCurrent(
        expected: ReplyResolutionScope
    ): Boolean = conversationAccessResolution.replyAccessResolutionOwnerStillCurrent(expected)

    suspend fun withHomeConversationContext(payload: RaftThreadScreenPayload): RaftThreadScreenPayload =
        conversationAccessResolution.withHomeConversationContext(payload)

    private fun cachedReplyThreadStreamId(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String
    ): String? = replyThreadPrewarm.cachedReplyThreadStreamId(namespaceId, parentChannelId, parentMessageId)

    private fun RaftThreadScreenPayload.matchesCachedReplyThreadStreamResolution(
        namespaceId: String?
    ): Boolean =
        with(replyThreadPrewarm) {
            this@matchesCachedReplyThreadStreamResolution.matchesCachedReplyThreadStreamResolution(namespaceId)
        }

    internal fun forgetReferenceTargetsOf(payload: RaftThreadScreenPayload): Int =
        markdownRefLaunch.forgetReferenceTargetsOf(payload)

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ): Boolean =
        replyThreadPrewarm.cacheReplyThreadResolution(provenance, parentChannelId, parentMessageId, threadChannelId)

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        payload: RaftThreadScreenPayload
    ): Boolean = replyThreadPrewarm.cacheReplyThreadResolution(provenance, payload)

    suspend fun joinChannel(channelId: ChannelId) = channelMemberCommands.joinChannel(channelId)

    suspend fun updateChannelProfile(
        channelId: ChannelId,
        name: String,
        description: String
    ): DataResult<ChannelProfileUpdateResult> = channelMemberCommands.updateChannelProfile(channelId, name, description)

    /** The followed threads with unread replies on the current server (unread-thread pill parents). */
    internal suspend fun fetchUnreadFollowedThreads(): DataResult<ThreadListSnapshot>? {
        val repository = conversationRepository ?: return null
        val context = captureReplyCacheProducerContext() ?: return null
        val serverId = context.provenance.serverId.takeIf { it.isNotBlank() } ?: return null
        return repository.getUnreadFollowedThreads(ServerId(serverId))
    }

    suspend fun publishChannelProfileUpdate(result: ChannelProfileUpdateResult) {
        channelProfileUpdateSink(result)
    }

    suspend fun cacheCanonicalPayload(
        payload: RaftThreadScreenPayload,
        expectedOwnerScopeId: String? = null,
        expectedProvenance: ReplyCacheProvenance? = null
    ): RaftThreadScreenPayload {
        val context = captureReplyCacheProducerContext() ?: return payload
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId ?: return payload
        if (expectedOwnerScopeId != null && expectedOwnerScopeId != ownerScopeId) return payload
        if (expectedProvenance != null && expectedProvenance != context.provenance) return payload
        cachePayload(
            payload = payload,
            serverId = context.provenance.serverId,
            ownerScopeId = ownerScopeId,
            expectedProvenance = expectedProvenance
        )
        return payload
    }

    suspend fun cacheLocalSendFailure(
        basePayload: RaftThreadScreenPayload,
        localMessage: RaftThreadMessageItem,
        errorCode: String?,
        errorMessage: String?,
        expectedOwnerScopeId: String? = null,
        expectedProvenance: ReplyCacheProvenance? = null
    ): RaftThreadScreenPayload {
        val failedPayload =
            basePayload
                .withLocalSendStarted(localMessage)
                .withLocalSendFailure(localMessage.id, errorCode, errorMessage)
        cacheCanonicalPayload(failedPayload, expectedOwnerScopeId, expectedProvenance)
        return failedPayload
    }

    suspend fun cacheLocalSendSuccess(
        basePayload: RaftThreadScreenPayload,
        localMessage: RaftThreadMessageItem,
        sentPage: ThreadMessagesPage,
        expectedOwnerScopeId: String? = null,
        expectedProvenance: ReplyCacheProvenance? = null
    ): RaftThreadScreenPayload {
        if (sentPage.messages.isEmpty()) return basePayload
        val context = captureReplyCacheProducerContext() ?: return basePayload
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId ?: return basePayload
        if (expectedOwnerScopeId != null && expectedOwnerScopeId != ownerScopeId) return basePayload
        if (expectedProvenance != null && expectedProvenance != context.provenance) return basePayload
        val session = context.session
        val threadId = basePayload.resolveThreadRouteIdentity().timelineChannelId
        val latestCachedPayload =
            threadId
                ?.let { cachedThreadId ->
                    cachedPayload(
                        threadId = cachedThreadId,
                        ownerScopeId = ownerScopeId,
                        serverId = context.provenance.serverId,
                        principalId = context.provenance.principalId
                    )
                }
                ?.takeIf { cached -> cached.isUsableCachedPayloadForRoute(basePayload) }
                ?.withRouteContext(basePayload)
        // Concurrent sends complete independently. Always fold the next
        // canonical response into the latest cached timeline instead of the
        // send-start snapshot, otherwise the later cache write can erase a
        // sibling send that completed while this request was in flight.
        val pendingPayload = (latestCachedPayload ?: basePayload).withLocalSendStarted(localMessage)
        val appended =
            appendSharedThreadSentMessageFromPage(
                basePayload = pendingPayload,
                page = sentPage,
                session = session,
                trustedReplyThreadChannelId = localMessage.pendingSendContext?.streamChannelId,
                diagnostics = diagnosticsLogger,
            )
        val replaced =
            appended
                .withoutLocalSendMessage(localMessage.id)
                .preserveStableTimelineFrom(pendingPayload)
        return cacheCanonicalPayload(replaced, ownerScopeId, expectedProvenance)
    }

    suspend fun commitAgentCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> = actionCardCommands.commitAgentCreateActionCard(messageId, card)

    suspend fun commitAgentCreateActionCardFromDialog(
        messageId: String,
        card: ThreadActionCard,
        submit: SharedCreateAgentDialogSubmit
    ): DataResult<ThreadActionCard> = actionCardCommands.commitAgentCreateActionCardFromDialog(messageId, card, submit)

    suspend fun markAgentMigrationActionCardExecuted(
        messageId: String,
        card: ThreadActionCard,
        agentId: String,
        targetMachineId: String,
    ): DataResult<ThreadActionCard> = actionCardCommands.markAgentMigrationActionCardExecuted(messageId, card, agentId, targetMachineId)

    suspend fun commitThreadActionCard(
        payload: RaftThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard,
        onChannelCreated: (HomeChannelRow) -> Unit = {},
    ): DataResult<ThreadActionCard> = actionCardCommands.commitThreadActionCard(payload, messageId, card, onChannelCreated)

    suspend fun commitThreadActionCard(
        payload: RaftThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard,
        stepUp: SecondFactorStepUpCoordinator,
        onChannelCreated: (HomeChannelRow) -> Unit = {},
    ): SecondFactorStepUpOutcome<ThreadActionCard> =
        actionCardCommands.commitThreadActionCard(payload, messageId, card, stepUp, onChannelCreated)

    suspend fun confirmInstallMarketplaceAppActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> = actionCardCommands.confirmInstallMarketplaceAppActionCard(messageId, card)

    suspend fun executeActionCardSpec(
        messageId: String,
        card: ThreadActionCard,
        form: ExecuteActionFormBody? = null,
    ): DataResult<ActionCardExecuteOutcome> = actionCardCommands.executeActionCardSpec(messageId, card, form)

    suspend fun closeQuestionActionCard(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<build.raft.app.data.ActionCardCloseOutcome> = actionCardCommands.closeQuestionActionCard(messageId, card)

    suspend fun checkSeatRequest(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<build.raft.app.data.SeatRequestCheckOutcome> = actionCardCommands.checkSeatRequest(messageId, card)

    suspend fun completeSeatRequest(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<build.raft.app.data.SeatRequestCompleteOutcome> = actionCardCommands.completeSeatRequest(messageId, card)

    suspend fun fetchThreadPayload(
        basePayload: RaftThreadScreenPayload,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false,
        replaceReplyAccessFailure: Boolean = false
    ): DataResult<RaftThreadScreenPayload> =
        fetchThreadPayloadWithResolutionScope(
            basePayload = basePayload,
            persistResult = persistResult,
            reusePrewarm = reusePrewarm,
            replyResolutionScope = null,
            replaceReplyAccessFailure = replaceReplyAccessFailure
        )

    /**
     * Runs a visible Store refresh under the exact owner captured before the refresh began.
     * Re-sampling here could turn an A-owned Store request into a B-owned network request while
     * the surrounding Store generation still belongs to A.
     */
    internal suspend fun fetchThreadPayloadForContext(
        basePayload: RaftThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false,
        replaceReplyAccessFailure: Boolean = false,
        /** Task #14 part C: receives the anchor context page of a focused open, for the durable writer. */
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        /** Message storage Phase 2b: receives the request's own latest page, for the durable write-through. */
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null,
        /**
         * Receives the first canonical result whose freshness generation moved while it was fetched
         * (it is refetched; see [SharedThreadStaleCanonicalRefetchMaxExtraRounds]). Its content is not
         * final, but its conversation access is server truth the caller may act on right away.
         */
        onSupersededCanonicalResult: (suspend (DataResult<RaftThreadScreenPayload>) -> Unit)? = null,
        /**
         * Called when the stale-generation refetch hit its cap: the returned result is superseded
         * content (not cached here) that the caller must not present as final.
         */
        onCanonicalRefetchCapped: (() -> Unit)? = null,
        /**
         * artin #110: asked with the message ids when only live message events moved the freshness
         * generation during the request. True when the caller holds every one of those events for
         * this conversation and folds them onto the answer itself; the answer is then returned as is
         * (not cached under a generation it does not match) instead of being fetched again.
         */
        foldsRealtimeIntoSupersededResult: ((Set<String>) -> Boolean)? = null,
    ): DataResult<RaftThreadScreenPayload> {
        if (!isCurrentReplyCacheProvenanceForLocalSend(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return fetchThreadPayloadWithResolutionScope(
            basePayload = basePayload,
            persistResult = persistResult,
            reusePrewarm = reusePrewarm,
            replyResolutionScope =
                ReplyResolutionScope(
                    session = expectedContext.session,
                    provenance = expectedContext.provenance,
                    sharedMessageStore = messageStore != null,
                ),
            replaceReplyAccessFailure = replaceReplyAccessFailure,
            onFocusedContextPage = onFocusedContextPage,
            onEnteredPage = onEnteredPage,
            onSupersededCanonicalResult = onSupersededCanonicalResult,
            onCanonicalRefetchCapped = onCanonicalRefetchCapped,
            foldsRealtimeIntoSupersededResult = foldsRealtimeIntoSupersededResult,
        )
    }

    private suspend fun fetchThreadPayloadWithResolutionScope(
        basePayload: RaftThreadScreenPayload,
        persistResult: Boolean,
        reusePrewarm: Boolean,
        replyResolutionScope: ReplyResolutionScope?,
        replaceReplyAccessFailure: Boolean = false,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null,
        onSupersededCanonicalResult: (suspend (DataResult<RaftThreadScreenPayload>) -> Unit)? = null,
        onCanonicalRefetchCapped: (() -> Unit)? = null,
        foldsRealtimeIntoSupersededResult: ((Set<String>) -> Boolean)? = null,
    ): DataResult<RaftThreadScreenPayload> {
        val resolutionScope = replyResolutionScope ?: readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val replyResolutionNamespaceId = resolutionScope.namespaceId
        val payloadOwnerScopeId =
            resolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val shouldFetchReplyByParent =
            conversationRepository != null &&
                basePayload.shouldFetchReplyThreadByParentPair() &&
                !basePayload.matchesCachedReplyThreadStreamResolution(replyResolutionNamespaceId)
        val resolvedBasePayload =
            if (shouldFetchReplyByParent) {
                basePayload
            } else {
                when (val resolved = resolveReplyThreadStream(basePayload, resolutionScope)) {
                    is DataResult.Content -> resolved.data
                    is DataResult.Empty -> return resolved
                    is DataResult.Error -> return resolved
                    is DataResult.NoPermission -> return resolved
                }
            }
        val threadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
        var failedPrewarm = false
        if (reusePrewarm && threadId != null) {
            val serverId = session.currentServerId.orEmpty()
            fun logReuse(outcome: String, reason: String) =
                diagnosticsLogger(
                    "thread_refresh_reuse_prewarm threadId=$threadId outcome=$outcome reason=$reason"
                )
            if (payloadCache.promoteQueuedPrewarm(serverId, threadId, payloadOwnerScopeId)) {
                diagnosticsLogger?.invoke(
                    "thread_prewarm_promote threadId=$threadId from=background_queue to=foreground"
                )
                logReuse("own_fetch", "prewarm_queued_promoted")
                failedPrewarm = true
            } else {
                val joined = payloadCache.joinOrConsumePrewarmWithOutcome(serverId, threadId, payloadOwnerScopeId)
                when (val warmed = joined.result?.takeUnlessWarmedConversationMisses(resolvedBasePayload)) {
                    is DataResult.Content -> {
                        if (!cacheReplyThreadResolution(resolutionScope.provenance, warmed.data)) {
                            return staleReplyCacheProvenanceResult()
                        }
                        logReuse(joined.outcome.diagnosticsName, "prewarm_content")
                        seedThreadAuthorProfiles(session.currentServerId, warmed.data, source = "prewarm")
                        return warmed
                    }
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> {
                        logReuse("own_fetch", "prewarm_failed_${joined.outcome.diagnosticsName}")
                        failedPrewarm = true
                    }
                    // JoinTimedOut leaves failedPrewarm false on purpose: the timed-out prewarm was detached
                    // (it can no longer commit), so there is nothing to take over; the open fetches itself.
                    // A warmed channel / DM page that does not answer this route (see
                    // takeUnlessWarmedConversationMisses) lands here too: it stays a first-frame candidate only.
                    null ->
                        logReuse(
                            "own_fetch",
                            if (joined.result is DataResult.Content) "prewarm_route_mismatch" else joined.outcome.diagnosticsName
                        )
                }
            }
        }
        suspend fun fetchCanonicalPayload(): DataResult<RaftThreadScreenPayload> =
            if (conversationRepository != null) {
                if (shouldFetchReplyByParent) {
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload,
                        replyResolutionScope = resolutionScope,
                        replaceReplyAccessFailure = replaceReplyAccessFailure,
                        onRawReplyPage =
                            onEnteredPage?.let { sink ->
                                { threadChannelId, page ->
                                    if (threadChannelId != null && page != null) {
                                        sink(page.toEnteredPage(threadChannelId, channelType = null))
                                    }
                                }
                            },
                        onFocusedContextPage = onFocusedContextPage
                    )
                } else {
                    val resolvedThreadId =
                        threadId
                            ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false, traceId = null))
                    fetchInitialThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload,
                        threadId = ThreadId(resolvedThreadId),
                        replyResolutionScope = resolutionScope,
                        onFocusedContextPage = onFocusedContextPage,
                        onEnteredPage = onEnteredPage
                    )
                }
            } else {
                val resolvedThreadId =
                    threadId
                        ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false, traceId = null))
                val response =
                    try {
                        val pageSize = threadPageSize(session.currentServerId)
                        diagnosticsLogger(
                            "thread_page_size source=initial_api server=${session.currentServerId.orEmpty()} " +
                                "pageSize=$pageSize"
                        )
                        api.channelMessages(resolvedThreadId, limit = pageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_network", retryable = true, traceId = null))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_network")
                    }
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = resolvedBasePayload,
                        page = page,
                        session = session,
                        diagnostics = diagnosticsLogger,
                    )
                )
            }
        var canonicalRequestGeneration: SharedThreadFreshnessGeneration? = null
        suspend fun fetchCanonicalPayloadForCurrentGeneration(): DataResult<RaftThreadScreenPayload> {
            val resolvedThreadId = threadId ?: return fetchCanonicalPayload()
            var extraRound = 0
            while (true) {
                if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                    return staleReplyCacheProvenanceResult()
                }
                val generation =
                    payloadCache.captureGeneration(
                        session.currentServerId.orEmpty(),
                        resolvedThreadId,
                        payloadOwnerScopeId
                    )
                val result = fetchCanonicalPayload()
                if (payloadCache.freshnessLedger.isCurrent(generation)) {
                    canonicalRequestGeneration = generation
                    return result
                }
                if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                    return staleReplyCacheProvenanceResult()
                }
                // Hands 7e198cab: say what moved the generation, so a refresh that never settles
                // names its cause (an Activity summary, a server-wide invalidation, a removal, ...).
                val supersededBy =
                    payloadCache.captureGeneration(
                        session.currentServerId.orEmpty(),
                        resolvedThreadId,
                        payloadOwnerScopeId
                    )
                // artin #110: only live events the page also received moved it (web merges its answer
                // with the socket events in one store): keep this answer, the caller folds them onto it.
                val realtimeMessageIds =
                    if (foldsRealtimeIntoSupersededResult != null) {
                        payloadCache.freshnessLedger.realtimeMessageIdsSince(generation)
                    } else {
                        null
                    }
                if (
                    foldsRealtimeIntoSupersededResult != null &&
                    realtimeMessageIds != null &&
                    foldsRealtimeIntoSupersededResult(realtimeMessageIds)
                ) {
                    diagnosticsLogger(
                        "thread_canonical_superseded_merged threadId=$resolvedThreadId round=${extraRound + 1} " +
                            "cause=${supersededBy.cause}"
                    )
                    return result
                }
                diagnosticsLogger(
                    "thread_canonical_superseded threadId=$resolvedThreadId round=${extraRound + 1} " +
                        "cause=${supersededBy.cause}"
                )
                if (extraRound == 0) {
                    // The content may miss what moved the generation; the access it carries does not.
                    onSupersededCanonicalResult?.invoke(result)
                }
                if (extraRound >= SharedThreadStaleCanonicalRefetchMaxExtraRounds) {
                    // Stop refetching. canonicalRequestGeneration stays null, so this superseded
                    // result is neither cached nor recorded under a generation it does not match, and
                    // the caller is told it is not final (a delete / edit that landed mid-fetch may be
                    // missing from it); the store keeps its visible content and refreshes once more.
                    diagnosticsLogger(
                        "thread_canonical_refetch_capped threadId=$resolvedThreadId rounds=${extraRound + 1}"
                    )
                    onCanonicalRefetchCapped?.invoke()
                    return result
                }
                extraRound++
                delay(SharedThreadStaleCanonicalRefetchBackoffMillis * extraRound)
            }
        }
        val payloadResult =
            if (failedPrewarm && threadId != null) {
                payloadCache.takeOverFailedPrewarm(
                    serverId = session.currentServerId.orEmpty(),
                    threadId = threadId,
                    ownerScopeId = payloadOwnerScopeId,
                    fetch = {
                        if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                            staleReplyCacheProvenanceResult()
                        } else {
                            fetchCanonicalPayload()
                        }
                    }
                )
            } else {
                fetchCanonicalPayloadForCurrentGeneration()
            }
        val payload =
            when (payloadResult) {
                is DataResult.Content -> payloadResult.data
                is DataResult.Empty ->
                    if (resolvedBasePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                        payloadResult.reason == EmptyReason.NoMessages
                    ) {
                        resolvedBasePayload.toEmptyReplyThreadPayload()
                    } else {
                        return payloadResult
                    }
                is DataResult.Error -> return payloadResult
                is DataResult.NoPermission -> return payloadResult
            }
                .let { canonicalPayload ->
                    canonicalPayload.cacheThreadId()?.let { resolvedThreadId ->
                        payloadCache.applyReplyCountFacts(
                            serverId = session.currentServerId.orEmpty(),
                            threadId = resolvedThreadId,
                            environmentId = resolutionScope.provenance.environmentId,
                            principalId = session.user.id,
                            payload = canonicalPayload
                        )
                    } ?: canonicalPayload
                }
        if (!replyAccessResolutionOwnerStillCurrent(resolutionScope)) {
            // The page that asked is no longer current (server switch, token rotation, or account
            // change). The response is still canonical data of the server it was requested from:
            // keep it in that server's timeline cache unless the account/environment changed
            // (Tenny rule 1; [cachePayload] applies the account fence and the generation fence).
            if (persistResult && canonicalRequestGeneration != null) {
                cachePayload(
                    payload = payload,
                    serverId = resolutionScope.provenance.serverId,
                    expectedGeneration = canonicalRequestGeneration,
                    ownerScopeId = payloadOwnerScopeId,
                    expectedProvenance = resolutionScope.provenance
                )
            }
            return staleReplyCacheProvenanceResult()
        }
        val cacheProvenance =
            currentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)
                ?: return staleReplyCacheProvenanceResult()
        if (!cacheReplyThreadResolution(cacheProvenance, payload)) {
            return staleReplyCacheProvenanceResult()
        }
        if (canonicalRequestGeneration != null) {
            payloadCache.recordCanonicalSuccess(canonicalRequestGeneration, payloadOwnerScopeId)
        }
        if (persistResult && canonicalRequestGeneration != null) {
            cachePayload(
                payload = payload,
                serverId = session.currentServerId.orEmpty(),
                expectedGeneration = canonicalRequestGeneration,
                ownerScopeId = payloadOwnerScopeId
            )
        }
        seedThreadAuthorProfiles(session.currentServerId, payload, source = "initial")
        return DataResult.Content(payload)
    }

    suspend fun prewarmReplyThreadOnCurrentServer(
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
    ): DataResult<Unit> =
        replyThreadPrewarm.prewarmReplyThreadOnCurrentServer(parentChannelId, parentMessageId, threadChannelId)

    suspend fun prewarmReplyThread(
        marker: ActivityReplyThreadPrewarmMarker
    ): DataResult<Unit> = replyThreadPrewarm.prewarmReplyThread(marker)

    private suspend fun resolveReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> =
        replyThreadPrewarm.resolveReplyThreadStream(basePayload, replyResolutionScope)

    private suspend fun createOrOpenReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        repository: ConversationRepository? = conversationRepository,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> =
        replyThreadPrewarm.createOrOpenReplyThreadStream(basePayload, repository, replyResolutionScope)

    suspend fun fetchWebLinkThreadPayload(
        basePayload: RaftThreadScreenPayload,
        target: RaftThreadWebLinkTarget
    ): DataResult<RaftThreadScreenPayload> {
        val messageId = target.messageId?.takeIf { it.isNotBlank() }
        if (messageId == null || conversationRepository == null) {
            return fetchThreadPayload(basePayload)
        }
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val contextResult =
            fetchThreadPayloadFromRepository(
                session = session,
                expectedProvenance = resolutionScope.provenance,
                fallbackCode = "thread_weblink_context",
                call = { repository -> repository.getMessageContext(MessageId(messageId)) },
                transform = { snapshot -> snapshot.toRaftThreadPayload(basePayload, session.user.id) }
            )
        return when (contextResult) {
            is DataResult.Content -> contextResult
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> fetchThreadPayload(basePayload)
        }
    }

    suspend fun enrichSavedStatus(payload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        val context = captureReplyCacheProducerContext() ?: return payload
        return when (val result = enrichSavedStatus(payload, context)) {
            is DataResult.Content -> result.data
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> payload
        }
    }

    /**
     * Enrich saved state using the producer's already-captured owner. The secondary
     * timeline must never re-sample the process-global session between the summary
     * request and this lookup: doing so can turn an A result into a B projection.
     */
    internal suspend fun enrichSavedStatus(
        payload: RaftThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext,
    ): DataResult<RaftThreadScreenPayload> {
        if (!isCurrentReplyCacheProvenance(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val serverId =
            expectedContext.provenance.serverId.takeIf { it.isNotBlank() }?.let(::ServerId)
                ?: return DataResult.Content(payload)
        val result = payload.withSavedStatus(serverId)
        if (!isCurrentReplyCacheProvenance(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return DataResult.Content(result)
    }

    suspend fun fetchFollowedThreadPayload(
        basePayload: RaftThreadScreenPayload,
        onSummaries: (ThreadReplySummariesSnapshot, TimeSource.Monotonic.ValueTimeMark) -> Unit = { _, _ -> },
    ): DataResult<RaftThreadScreenPayload> {
        return fetchFollowedThreadPayloadForContext(
            basePayload = basePayload,
            expectedContext = null,
            onSummaries = onSummaries,
        )
    }

    internal suspend fun fetchFollowedThreadPayloadForContext(
        basePayload: RaftThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext?,
        onSummaries: (ThreadReplySummariesSnapshot, TimeSource.Monotonic.ValueTimeMark) -> Unit = { _, _ -> },
    ): DataResult<RaftThreadScreenPayload> {
        if (expectedContext != null && !isCurrentReplyCacheProvenance(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        if (conversationRepository == null) return DataResult.Content(basePayload)
        if (basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            return DataResult.Content(basePayload)
        }
        val context = expectedContext ?: captureReplyCacheProducerContext()
            ?: return authSessionRequiredResult()
        if (!isCurrentReplyCacheProvenance(context.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val resolvedThreadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        // Only server messages can be thread parents. A local send row (sending, or failed with
        // Retry) carries a `local-send-…` id, and the server refuses the whole request when one id
        // is not a message id (400 `thread_parent_message_ids_invalid`, slock `channels.ts`
        // `parseThreadSummaryParentMessageIds`). A refresh that runs while a send is in flight (the
        // catch-up after a new message backfills the summaries) then got only the followed list,
        // which has no reply previews (artin, #wg-mobile-sync: inline replies gone after a send).
        val parentMessageIds =
            basePayload.messages
                .filterNot { message -> message.hasLocalSendState() }
                .map { MessageId(it.id) }
        // Mark the instant the canonical summaries request is issued, not when
        // the response lands: facts recorded by realtime events that arrive
        // while this request is in flight must stay ahead of its (older)
        // server-side snapshot.
        val summariesRequestedAt = TimeSource.Monotonic.markNow()
        val result = conversationRepository
            .getThreadReplySummaries(
                threadId = ThreadId(resolvedThreadId),
                parentMessageIds = parentMessageIds
            )
        if (!isCurrentReplyCacheProvenance(context.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return when (result) {
            is DataResult.Content -> {
                onSummaries(result.data, summariesRequestedAt)
                if (!isCurrentReplyCacheProvenance(context.provenance)) {
                    staleReplyCacheProvenanceResult()
                } else {
                    // `withReplySummaries` unconditionally overwrites each
                    // message's replyCount with the canonical snapshot. Realtime
                    // reply-count facts recorded after the request was issued
                    // (newer observedAt) must still win, so re-apply the fact
                    // ledger before publishing the payload.
                    val projected = basePayload.withReplySummaries(result.data)
                    DataResult.Content(
                        payloadCache.applyReplyCountFacts(
                            serverId = context.provenance.serverId,
                            threadId = resolvedThreadId,
                            environmentId = context.provenance.environmentId,
                            principalId = context.provenance.principalId,
                            payload = projected
                        )
                    )
                }
            }
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    fun cacheReplyCountProjection(
        threadId: String,
        snapshot: ThreadReplySummariesSnapshot,
        observedAt: TimeSource.Monotonic.ValueTimeMark,
        serverId: String,
        principalId: String,
        expectedProvenance: ReplyCacheProvenance,
        commit: () -> Boolean,
    ): Boolean? =
        replyCountProjection.cacheReplyCountProjection(threadId, snapshot, observedAt, serverId, principalId, expectedProvenance, commit)

    fun recordReplyCountRealtimeFact(
        threadId: String,
        parentMessageId: String,
        fact: SharedThreadReplyCountFact,
    ) = replyCountProjection.recordReplyCountRealtimeFact(threadId, parentMessageId, fact)

    internal fun applyThreadUpdatedToParentConversation(
        event: ThreadUpdated,
        openPageThreadId: String?,
    ): Boolean? = replyCountProjection.applyThreadUpdatedToParentConversation(event, openPageThreadId)

    internal fun applySentReplyToParentConversation(
        provenance: ReplyCacheProvenance,
        parentScopeId: String,
        parentMessageId: String,
        threadChannelId: String,
        reply: ReplyPreview,
        replyCountAtLeast: Int,
    ): Boolean =
        replyCountProjection.applySentReplyToParentConversation(
            provenance,
            parentScopeId,
            parentMessageId,
            threadChannelId,
            reply,
            replyCountAtLeast,
        )

    /**
     * Outcome of an attempt to enrich the current thread payload with
     * fresh member / candidate data. The tri-state lets the caller
     * distinguish a real "no change" success from a transient failure
     * (network / repository exception, NoPermission, Error) so that
     * idempotent LoadMembers can decide whether to enter Loaded or
     * Error.
     */
    internal sealed class MembersEnrichOutcome {
        data class Updated(val payload: RaftThreadScreenPayload) : MembersEnrichOutcome()
        data object Unchanged : MembersEnrichOutcome()
        data class Failed(val reason: String) : MembersEnrichOutcome()
    }

    suspend fun enrichThreadMembers(payload: RaftThreadScreenPayload): MembersEnrichOutcome =
        channelMemberCommands.enrichThreadMembers(payload)

    suspend fun addChannelMember(
        payload: RaftThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<RaftThreadScreenPayload> = channelMemberCommands.addChannelMember(payload, memberId, kind)

    suspend fun markdownChannelLaunchPayload(
        currentPayload: RaftThreadScreenPayload,
        channelLabel: String,
        requiredKind: ConversationKind? = null
    ): RaftThreadScreenPayload? =
        markdownRefLaunch.markdownChannelLaunchPayload(currentPayload, channelLabel, requiredKind)

    suspend fun markdownTaskRefChannelId(
        currentPayload: RaftThreadScreenPayload,
        conversationLabel: String,
        requiredKind: ConversationKind? = null,
        actionId: String? = null
    ): ChannelId? =
        markdownRefLaunch.markdownTaskRefChannelId(currentPayload, conversationLabel, requiredKind, actionId)

    suspend fun markdownThreadRefLaunchPayload(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        channelLabel: String,
        threadShortId: String?,
        requiredParentKind: ConversationKind? = null,
        focusedMessageId: String? = null,
        actionId: String? = null
    ): DataResult<RaftThreadScreenPayload> =
        markdownRefLaunch.markdownThreadRefLaunchPayload(
            provenance,
            currentPayload,
            channelLabel,
            threadShortId,
            requiredParentKind,
            focusedMessageId,
            actionId,
        )

    suspend fun markdownMessageRefLaunchPayload(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        ref: RaftMarkdownMessageRef,
        actionId: String? = null
    ): DataResult<RaftThreadScreenPayload> =
        markdownRefLaunch.markdownMessageRefLaunchPayload(provenance, currentPayload, ref, actionId)

    suspend fun removeChannelMember(
        payload: RaftThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<RaftThreadScreenPayload> = channelMemberCommands.removeChannelMember(payload, memberId, kind)

    suspend fun leaveChannel(payload: RaftThreadScreenPayload): DataResult<Unit> = channelMemberCommands.leaveChannel(payload)

    suspend fun archiveChannel(payload: RaftThreadScreenPayload): DataResult<Unit> = channelMemberCommands.archiveChannel(payload)

    suspend fun deleteChannel(payload: RaftThreadScreenPayload): DataResult<Unit> = channelMemberCommands.deleteChannel(payload)

    suspend fun fetchOlderThreadPayload(
        basePayload: RaftThreadScreenPayload,
        beforeSeq: Long
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "older",
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadOlderMessages(threadId, beforeSeq, pageSize)
            },
            call = { threadId, pageSize ->
                api.channelMessages(threadId, limit = pageSize, beforeSeq = beforeSeq)
            }
        )

    suspend fun fetchNewerThreadPayload(
        basePayload: RaftThreadScreenPayload,
        afterSeq: Long
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "newer",
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadNewerMessages(threadId, afterSeq, pageSize)
            },
            call = { threadId, pageSize ->
                api.channelMessages(threadId, limit = pageSize, afterSeq = afterSeq)
            }
        )

    /**
     * Task #168 step 1: one step of the gap catch-up (disconnect / resume / in-foreground realtime gap).
     *
     * An uncursored `messages/channel/{id}?limit=` latest page: full nodes, the same shape the enter
     * snapshot persists (the `/messages/sync` delta rows lack `randomId` and can never reach the
     * durable V2 cache, and the delta has no completeness signal). [wide] = `false` asks for the thread
     * page size (the cheap common case), `true` for [SharedThreadGapCatchUpPageLimit] (the enter
     * snapshot's page). Whether a page joins the timeline is decided by the caller from an explicit
     * fact (does the page contain the anchor message), never by seq arithmetic.
     */
    suspend fun fetchGapCatchUpLatestPage(
        basePayload: RaftThreadScreenPayload,
        wide: Boolean,
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = if (wide) "gap_catchup_wide" else "gap_catchup",
            pageSizeOverride = SharedThreadGapCatchUpPageLimit.takeIf { wide },
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadLatestMessages(threadId, pageSize)
            },
            call = { threadId, pageSize ->
                api.gapCatchUpChannelMessages(threadId, limit = pageSize)
            }
        )

    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String> = emptyList(),
        randomId: String? = null,
        /** Structured `mentions` exactly as the composer recorded them (a retry resends this same list). */
        mentions: List<ThreadSendMessageMention> = emptyList(),
    ): ThreadApiResult<ThreadMessagesPage> =
        if (mentions.isEmpty()) {
            api.sendMessage(channelId = channelId, body = body, asTask = asTask, attachmentIds = attachmentIds, randomId = randomId)
        } else {
            api.sendMessageWithMentions(
                channelId = channelId,
                body = body,
                asTask = asTask,
                attachmentIds = attachmentIds,
                randomId = randomId,
                mentions = mentions,
            )
        }

    suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): ThreadApiResult<List<PendingMentionActionExecuteResult>> =
        api.executePendingMentionActions(action = action, resolutionIds = resolutionIds)

    suspend fun uploadAttachments(
        channelId: String,
        files: List<PlatformFile>,
        onProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)? = null
    ): DataResult<AttachmentUploadResult> = attachmentTransfers.uploadAttachments(channelId, files, onProgress)

    suspend fun captureDirectAttachmentUploadOperation(
        diagnosticsLogger: (String) -> Unit = {},
    ): DirectAttachmentUploadOperation? = attachmentTransfers.captureDirectAttachmentUploadOperation(diagnosticsLogger)

    fun peekAttachmentUploadCapabilities(
        operation: DirectAttachmentUploadOperation,
    ): AttachmentUploadCapabilities? = attachmentTransfers.peekAttachmentUploadCapabilities(operation)

    suspend fun attachmentUploadCapabilities(
        operation: DirectAttachmentUploadOperation,
    ): DataResult<AttachmentUploadCapabilities> = attachmentTransfers.attachmentUploadCapabilities(operation)

    suspend fun prefetchAttachmentUploadCapabilities() = attachmentTransfers.prefetchAttachmentUploadCapabilities()

    suspend fun attachmentUploadMaxBytes(
        expectedAuthority: RaftExpectedAuthBinding? = null,
    ): DataResult<Long> = attachmentTransfers.attachmentUploadMaxBytes(expectedAuthority)

    suspend fun captureExternalShareSendAuthority(): RaftExpectedAuthBinding? =
        attachmentTransfers.captureExternalShareSendAuthority()

    suspend fun matchesExternalShareSendAuthority(expected: RaftExpectedAuthBinding): Boolean =
        attachmentTransfers.matchesExternalShareSendAuthority(expected)

    suspend fun attachmentUrl(
        priority: DataRequestPriority,
        attachmentId: String,
        disposition: String? = null
    ): ThreadApiResult<ThreadAttachmentOpenTarget> = attachmentTransfers.attachmentUrl(priority, attachmentId, disposition)

    suspend fun attachmentUrls(
        priority: DataRequestPriority,
        attachmentIds: List<String>,
    ): ThreadApiResult<Map<String, ThreadAttachmentOpenTarget>> = attachmentTransfers.attachmentUrls(priority, attachmentIds)

    suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        attachmentTransfers.attachmentHtmlPreviewUrl(attachmentId)

    suspend fun fetchFilesPayload(
        basePayload: RaftThreadScreenPayload,
        cursor: String? = null,
        currentPayload: RaftThreadFilesPayload? = null
    ): DataResult<RaftThreadFilesPayload> = attachmentTransfers.fetchFilesPayload(basePayload, cursor, currentPayload)

    suspend fun saveMessage(messageId: String): DataResult<Unit> = itemActions.saveMessage(messageId)

    suspend fun unsaveMessage(messageId: String): DataResult<Unit> = itemActions.unsaveMessage(messageId)

    suspend fun setMessageReaction(messageId: String, emoji: String, selected: Boolean): DataResult<MessageMutationSummary> =
        itemActions.setMessageReaction(messageId, emoji, selected)

    suspend fun loadAttachmentComments(attachmentId: String): DataResult<ThreadAttachmentCommentsPage> =
        itemActions.loadAttachmentComments(attachmentId)

    suspend fun sendAttachmentComment(
        attachmentId: String,
        body: String,
        anchor: ThreadAttachmentCommentAnchor? = null
    ): DataResult<Unit> = itemActions.sendAttachmentComment(attachmentId, body, anchor)

    suspend fun createOrOpenReplyThread(
        basePayload: RaftThreadScreenPayload,
        parentMessage: RaftThreadMessageItem
    ): DataResult<RaftThreadScreenPayload> {
        val replyResolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val replyResolutionNamespaceId = replyResolutionScope.namespaceId
        val parentChannelId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        cachedReplyThreadStreamId(replyResolutionNamespaceId, parentChannelId, parentMessage.id)?.let { threadChannelId ->
            return loadResolvedReplyThread(
                basePayload = basePayload,
                parentMessage = parentMessage,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessage.id,
                threadChannelId = threadChannelId,
                replyCount = parentMessage.replyCount,
                replyResolutionScope = replyResolutionScope
            )
        }
        val actionResult =
            if (conversationRepository != null) {
                try {
                    conversationRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessage.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true, traceId = null))
                }
            } else {
                val response =
                    try {
                        api.createOrOpenThread(parentChannelId, parentMessage.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true, traceId = null))
                    }
                when (response) {
                    is ThreadApiResult.Success -> DataResult.Content(response.data)
                    is ThreadApiResult.Failure -> return response.toThreadDataError("thread_reply_network")
                }
            }
        val action =
            when (actionResult) {
                is DataResult.Content -> actionResult.data
                is DataResult.Empty -> {
                    return DataResult.Empty(EmptyReason.NoMessages)
                }
                is DataResult.Error -> return actionResult
                is DataResult.NoPermission -> return actionResult
            }
        val threadChannelId = action.threadChannelId.value
        val resolvedParentMessageId = action.parentMessageId?.value ?: parentMessage.id
        if (!cacheReplyThreadResolution(
                replyResolutionScope.provenance,
                parentChannelId,
                resolvedParentMessageId,
                threadChannelId
            )
        ) {
            return DataResult.Error(
                ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
            )
        }
        return loadResolvedReplyThread(
            basePayload = basePayload,
            parentMessage = parentMessage,
            parentChannelId = parentChannelId,
            parentMessageId = resolvedParentMessageId,
            threadChannelId = threadChannelId,
            replyCount = action.replyCount,
            replyResolutionScope = replyResolutionScope
        )
    }

    suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> = itemActions.followThread(parentMessageId)

    suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> = itemActions.unfollowThread(threadChannelId)

    suspend fun followTaskConversation(conversationChannelId: ChannelId): DataResult<ThreadActionResult> =
        itemActions.followTaskConversation(conversationChannelId)

    suspend fun loadTaskConversationTitle(conversationChannelId: ChannelId): String? =
        itemActions.loadTaskConversationTitle(conversationChannelId)

    /** Task v2: the task and parent channel behind a task conversation channel. */
    suspend fun loadTaskConversationRef(conversationChannelId: ChannelId): DataResult<TaskConversationChannelRef> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "task_conversation_ref_network", retryable = true))
        suspend fun load(): DataResult<TaskConversationChannelRef> =
            try {
                repository.loadTaskConversationRef(conversationChannelId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(ScreenError(code = "task_conversation_ref_network", retryable = true, traceId = null))
            }
        val session = readSession()
        val serverId = session?.currentServerId?.takeIf { it.isNotBlank() } ?: return load()
        val key =
            SharedTaskConversationRefKey(
                environmentId = cacheEnvironmentIdProvider(),
                principalId = session.user.id,
                serverId = serverId,
                conversationChannelId = conversationChannelId.value,
            )
        // The ref never changes: a reopen reads it from the process-wide cache, and the shared fetch
        // outlives a caller that stops waiting (see [SharedThreadPayloadStore.taskConversationRef]).
        return payloadCache.taskConversationRef(key) { load() }
    }

    private suspend fun loadResolvedReplyThread(
        basePayload: RaftThreadScreenPayload,
        parentMessage: RaftThreadMessageItem,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
        replyCount: Int,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> {
        val parentSurfaceKind =
            basePayload.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: when {
                    basePayload.surfaceKind == RaftThreadSurfaceKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                    basePayload.channelKind.equals("dm", ignoreCase = true) -> RaftThreadSurfaceKind.DirectMessage
                    basePayload.memberScopedMentions && basePayload.parentChannelId.isNullOrBlank() -> RaftThreadSurfaceKind.DirectMessage
                    else -> RaftThreadSurfaceKind.Channel
                }
        val resolvedOrigin =
            parentMessage.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(parentMessage.replyCount, replyCount)
            )
        val replyBase =
            basePayload.copy(
                instanceId = "shared-shell-thread-$threadChannelId",
                title = ThreadReplyPageTitle,
                subtitle = basePayload.replyThreadHeaderSubtitle(),
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = threadChannelId,
                streamChannelId = threadChannelId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                parentSurfaceKind = parentSurfaceKind,
                parentNavigationHint = basePayload.parentNavigationHint,
                navigationLabel = "Close",
                selectedTab = "chat",
                showModeTabs = false,
                showOpenInChannelAction = true,
                focusedMessageId = basePayload.focusedMessageId,
                originMessage = resolvedOrigin,
                originParentMessageId = parentMessageId,
                originStatus = "loaded",
                messages = emptyList(),
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                emptyTitle = ThreadReplyEmptyTitle,
                emptyDetail = ThreadReplyEmptyDetail,
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = parentMessage.id,
                        enabled = basePayload.canSend,
                        mentionChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        streamChannelId = threadChannelId
                    )
            )
        return when (
            val loaded =
                fetchThreadPayloadWithResolutionScope(
                    basePayload = replyBase,
                    persistResult = false,
                    reusePrewarm = false,
                    replyResolutionScope = replyResolutionScope
                )
        ) {
            is DataResult.Content -> {
                val payload =
                    loaded.data.copy(
                        surfaceKind = replyBase.surfaceKind,
                        streamChannelId = replyBase.streamChannelId,
                        parentChannelId = replyBase.parentChannelId,
                        parentMessageId = replyBase.parentMessageId,
                        parentSurfaceKind = replyBase.parentSurfaceKind,
                        focusedMessageId = loaded.data.focusedMessageId ?: replyBase.focusedMessageId,
                        focusedMessageRequestNonce = replyBase.focusedMessageRequestNonce,
                        composer = loaded.data.composer.takeUnless { it.isDefaultThreadComposer() } ?: replyBase.composer,
                        originMessage = loaded.data.originMessage ?: resolvedOrigin,
                        originParentMessageId = loaded.data.originParentMessageId ?: parentMessageId,
                        originStatus = if (loaded.data.originMessage != null) loaded.data.originStatus else "loaded"
                    ).asLoadedEmptyReplyThreadPayload()
                val selected =
                    cacheCanonicalPayload(
                        payload,
                        replyResolutionScope.provenance.threadPayloadOwnerScopeId
                    )
                DataResult.Content(selected)
            }
            is DataResult.Empty -> {
                val payload = replyBase.toEmptyReplyThreadPayload()
                val selected =
                    cacheCanonicalPayload(
                        payload,
                        replyResolutionScope.provenance.threadPayloadOwnerScopeId
                    )
                DataResult.Content(selected)
            }
            is DataResult.Error -> loaded
            is DataResult.NoPermission -> loaded
        }
    }

    suspend fun markVisibleChannelRead(
        channelId: String,
        latestSeq: Long?,
        expectedProvenance: ReplyCacheProvenance? = null
    ): DataResult<ReadReceiptResult> {
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        }
        if (expectedProvenance != null && !isCurrentReplyCacheProvenance(expectedProvenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val coordinator = readReceiptCoordinator
        val ownerFactory = readReceiptOwnerFactory
        if (coordinator != null && ownerFactory != null) {
            val session = readSession() ?: return authSessionRequiredResult()
            if (expectedProvenance != null && !isCurrentReplyCacheProvenance(expectedProvenance)) {
                return staleReplyCacheProvenanceResult()
            }
            val serverId =
                selectedServerStore?.selectedServerId?.value
                    ?: session.currentServerId
                    ?: return DataResult.Error(ScreenError(code = "server_missing", retryable = false))
            val owner = ownerFactory(serverId, session.user.id)
            return coordinator.awaitReadToSeq(owner, ChannelId(channelId), latestSeq ?: 0L)
        }
        if (expectedProvenance != null && !isCurrentReplyCacheProvenance(expectedProvenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val response =
            try {
                if (latestSeq != null && latestSeq > 0L) {
                    api.markChannelReadToSeq(channelId, latestSeq)
                } else {
                    api.markChannelReadAll(channelId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_read_receipt_network", retryable = true, traceId = null))
            }
        return if (response is ThreadApiResult.Success) {
            DataResult.Content(
                ReadReceiptResult(
                    channelId = ChannelId(channelId),
                    seq = latestSeq?.takeIf { it > 0L },
                    changed = true
                )
            )
        } else {
            (response as ThreadApiResult.Failure).toThreadDataError("thread_read_receipt_network")
        }
    }

    suspend fun markThreadChannelUnread(threadChannelId: String): DataResult<ReadReceiptResult> = itemActions.markThreadChannelUnread(threadChannelId)

    suspend fun openMessageContext(
        messageId: String,
        basePayload: RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> {
        val trimmedMessageId = messageId.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        return fetchThreadPayloadFromRepository(
            session = session,
            expectedProvenance = resolutionScope.provenance,
            fallbackCode = "thread_parent_context_network",
            call = { repository -> repository.getMessageContext(MessageId(trimmedMessageId)) },
            transform = { snapshot ->
                snapshot.toRaftThreadPayload(basePayload, session.user.id)
                    .copy(showOpenInChannelAction = false)
            }
        )
    }

    suspend fun openCommentHost(
        hostMessageId: String,
        basePayload: RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> {
        val streamThreadId = basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        if (basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && streamThreadId != null) {
            return fetchThreadPayload(basePayload, persistResult = false)
        }
        return openMessageContext(hostMessageId, basePayload)
    }

    private suspend fun fetchThreadPage(
        basePayload: RaftThreadScreenPayload,
        source: String,
        pageSizeOverride: Int? = null,
        repositoryCall: suspend (ConversationRepository, ThreadId, Int) -> DataResult<ThreadSnapshot>,
        call: suspend (threadId: String, pageSize: Int) -> ThreadApiResult<ThreadMessagesPage>
    ): DataResult<SharedThreadPageFetchResult> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val pageSize = pageSizeOverride ?: threadPageSize(session.currentServerId)
        diagnosticsLogger(
            "thread_page_size source=$source server=${session.currentServerId.orEmpty()} pageSize=$pageSize"
        )
        var rawPage = RawMessagePageWitness.None
        var rawServerRowCount = 0
        // Phase 3b: read before the request (with the identity): a horizonReset during it voids the page's head claim.
        val headEpochAtRequest = durableHeadEpochOrNull(resolutionScope.provenance)
        val revAtRequest = durableLocalRevOrNull(resolutionScope.provenance)
        val result =
            if (conversationRepository != null) {
                fetchThreadPayloadFromRepository(
                    session = session,
                    expectedProvenance = resolutionScope.provenance,
                    fallbackCode = "thread_${source}_network",
                    call = { repository -> repositoryCall(repository, ThreadId(threadId), pageSize) },
                    transform = { snapshot ->
                        rawPage = snapshot.rawMessagePage
                        rawServerRowCount = snapshot.rawMessagePage.page?.messages?.size ?: snapshot.rawMessageCount
                        snapshot.toRaftThreadPayload(basePayload, session.user.id)
                    }
                )
            } else {
                val response =
                    try {
                        call(threadId, pageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_${source}_network", retryable = true, traceId = null))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_${source}_network")
                    }
                rawServerRowCount = page.messages.size
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = basePayload,
                        page = page,
                        session = session,
                        diagnostics = diagnosticsLogger,
                    )
                )
            }
        return when (result) {
            is DataResult.Content -> {
                seedThreadAuthorProfiles(session.currentServerId, result.data, source = source)
                DataResult.Content(
                    SharedThreadPageFetchResult(
                        payload = result.data,
                        requestedPageSize = pageSize,
                        rawServerRowCount = rawServerRowCount,
                        durablePage =
                            rawPage.page?.let { page ->
                                SharedThreadDurablePage(
                                    context = ReplyCacheProducerContext(session, resolutionScope.provenance),
                                    rawPage = page,
                                    headEpochAtRequest = headEpochAtRequest,
                                    revAtRequest = revAtRequest,
                                )
                            }
                    )
                )
            }
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun fetchThreadPayloadFromRepository(
        session: StoredAuthSession,
        expectedProvenance: ReplyCacheProvenance,
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        transform: (ThreadSnapshot) -> RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> {
        val result =
            fetchThreadSnapshotFromRepository(
                fallbackCode = fallbackCode,
                expectedProvenance = expectedProvenance,
                call = call
            )
        return result.map { snapshot ->
            transform(snapshot)
                .asLoadedEmptyReplyThreadPayload()
        }
    }

    private suspend fun fetchInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        threadId: ThreadId,
        replyResolutionScope: ReplyResolutionScope,
        includeFollowedSummaries: Boolean = false,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null
    ): DataResult<RaftThreadScreenPayload> {
        val focusedContextOutcome =
            if (includeFollowedSummaries) {
                FocusedInitialContextOutcome.NotAttemptedOrTransient
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = threadId,
                    accessFallback = null,
                    replyResolutionScope = replyResolutionScope,
                    onFocusedContextPage = onFocusedContextPage
                )
            }
        if (focusedContextOutcome is FocusedInitialContextOutcome.Loaded) {
            return focusedContextOutcome.result
        }
        val focusMissing = focusedContextOutcome is FocusedInitialContextOutcome.TargetMissing
        val fallbackBase =
            if (focusMissing) {
                basePayload.copy(focusedMessageId = null, focusedMessageRequestNonce = 0)
            } else {
                basePayload
            }
        val initialResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_network",
                expectedProvenance = replyResolutionScope.provenance,
                loadedConversationId = threadId.value,
            ) { repository ->
                val pageSize = threadPageSize(session.currentServerId)
                diagnosticsLogger?.invoke(
                    "thread_page_size source=initial_repository server=${session.currentServerId.orEmpty()} " +
                        "pageSize=$pageSize"
                )
                withInboxFetchReason(if (includeFollowedSummaries) InboxFetchReason.Initial else null) {
                    repository.getThread(
                        threadId = threadId,
                        includeFollowedSummaries = includeFollowedSummaries,
                        limit = pageSize,
                        captureRawMessagePage = onEnteredPage != null
                    )
                }
            }
        val initialData = initialResult as? DataResult.Content
        // Message storage Phase 2b: the page this request fetched is the durable write (with the channel detail the
        // same load fetched: its kind names the scope).
        initialData?.data?.let { snapshot ->
            val raw = snapshot.rawMessagePage.page ?: return@let
            onEnteredPage?.invoke(
                raw.toEnteredPage(
                    channelId = threadId.value,
                    channelType =
                        snapshot.canonicalConversationDetail
                            ?.takeIf { detail -> detail.channelId?.value == null || detail.channelId?.value == threadId.value }
                            ?.channelType,
                )
            )
        }
        // If the route's stream hint is untrusted (stream != parent channel) and the
        // thread-channel snapshot's reported parent pair differs from the route,
        // fall back to the resolve path which POSTs createOrOpenThread to
        // reconcile. This is the v0.2 defense-in-depth: the snapshot may not
        // carry parent metadata at all (treated as trust), but if it does and
        // disagrees with the canonical route parent pair, we don't show it.
        return when {
            initialData != null &&
                fallbackBase.hasUntrustedReplyThreadStreamHint() &&
                !initialData.data.parentPairMatchesRoute(fallbackBase) ->
                fetchResolvedReplyThreadSnapshot(
                    session = session,
                    basePayload = fallbackBase,
                    replyResolutionScope = replyResolutionScope
                ).map { (resolvedBase, snapshot) ->
                    (snapshot
                        ?.withReplyParentMessageFromContext(resolvedBase, replyResolutionScope = replyResolutionScope)
                        ?.toRaftThreadPayload(resolvedBase, session.user.id)
                        ?: resolvedBase.toEmptyReplyThreadPayload())
                        .let { if (focusMissing) it.copy(focusedTargetMissing = true) else it }
                }
            initialData != null ->
                DataResult.Content(
                    initialData.data
                        .withReplyParentMessageFromContext(fallbackBase, replyResolutionScope = replyResolutionScope)
                        .toRaftThreadPayload(fallbackBase, session.user.id)
                        .let { if (focusMissing) it.copy(focusedTargetMissing = true) else it }
                )
            else -> initialResult as DataResult<RaftThreadScreenPayload>
        }
    }

    internal sealed interface FocusedInitialContextOutcome {
        data class Loaded(val result: DataResult<RaftThreadScreenPayload>) : FocusedInitialContextOutcome
        data class TargetMissing(val cause: String) : FocusedInitialContextOutcome
        data object NotAttemptedOrTransient : FocusedInitialContextOutcome
    }

    private suspend fun fetchFocusedInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        routeThreadId: ThreadId,
        accessFallback: ConversationAccessCandidate?,
        replyResolutionScope: ReplyResolutionScope,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        /** artin #111: the Activity conversation prewarm asks at Background; every open stays Foreground. */
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
    ): FocusedInitialContextOutcome {
        val focusedMessageId = basePayload.focusedMessageId?.takeIf { it.isNotBlank() }
            ?: return FocusedInitialContextOutcome.NotAttemptedOrTransient
        val replyParentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
        if (basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && focusedMessageId == replyParentMessageId) {
            // The parent message lives in the parent channel, not the thread stream: requesting
            // `messages/context/<parentMessageId>?channelId=<threadChannelId>` always 404s. Fall
            // through to `resolveReplyParentMessageFromStore` + `getThread` while keeping focus.
            return FocusedInitialContextOutcome.NotAttemptedOrTransient
        }
        val focusedContextChannelId = basePayload.focusedMessageContextChannelIdOrNull()
        // Phase 3b: read before the request: a horizonReset during it voids the page's head claim.
        val headEpochAtRequest = durableHeadEpochOrNull(replyResolutionScope.provenance)
        val revAtRequest = durableLocalRevOrNull(replyResolutionScope.provenance)
        val contextResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_focused_context",
                expectedProvenance = replyResolutionScope.provenance
            ) { repository ->
                // The route names the conversation, so its header detail is read beside the context.
                repository.getMessageContextInConversation(
                    messageId = MessageId(focusedMessageId),
                    channelId = focusedContextChannelId,
                    expectedConversationId = ChannelId(routeThreadId.value),
                    priority = requestPriority,
                )
            }
        val context =
            when (contextResult) {
                is DataResult.Content -> contextResult.data
                is DataResult.Error -> {
                    if (isFinalFocusedMessageNotFoundError(contextResult.error)) {
                        val cause = contextResult.error.serverCode ?: contextResult.error.code
                        diagnosticsLogger(
                            "thread_focused_context_missing scope=${routeThreadId.value} " +
                                "anchor=$focusedMessageId cause=$cause quiet=true"
                        )
                        return FocusedInitialContextOutcome.TargetMissing(cause)
                    }
                    return FocusedInitialContextOutcome.NotAttemptedOrTransient
                }
                else -> return FocusedInitialContextOutcome.NotAttemptedOrTransient
            }
        if (!context.matchesFocusedThreadRoute(routeThreadId, focusedMessageId)) {
            return FocusedInitialContextOutcome.NotAttemptedOrTransient
        }
        // Task #14 part C: the context page is a fact source too — hand it to the durable writer (the
        // caller decides when; the writer decides whether it joins the stored window).
        onFocusedContextPage?.let { sink ->
            val rawPage = context.rawMessagePage.page
            if (rawPage == null) {
                diagnosticsLogger(
                    "canonical_v2_context_page server=${replyResolutionScope.provenance.serverId} " +
                        "scope=${routeThreadId.value} anchor=$focusedMessageId outcome=skipped reason=no_raw_page facts=0"
                )
            } else {
                sink(
                    SharedThreadDurableContextPage(
                        context = ReplyCacheProducerContext(session, replyResolutionScope.provenance),
                        routeIdentity = basePayload.resolveThreadRouteIdentity(),
                        replyParent = basePayload.durableReplyParentOrNull(),
                        anchorMessageId = focusedMessageId,
                        rawPage = rawPage,
                        hasOlder = context.canLoadMoreBefore,
                        hasNewer = context.canLoadMoreAfter,
                        headEpochAtRequest = headEpochAtRequest,
                        revAtRequest = revAtRequest,
                    )
                )
            }
        }
        return FocusedInitialContextOutcome.Loaded(
            DataResult.Content(
                context
                    .withCachedReplyParentMessage(
                        basePayload = basePayload,
                        messageStoreScopeId = replyResolutionScope.messageStoreScopeId
                    )
                    .withConversationAccessFallback(accessFallback)
                    .toRaftThreadPayload(basePayload, session.user.id)
            )
        )
    }

    private fun ThreadSnapshot.withCachedReplyParentMessage(
        basePayload: RaftThreadScreenPayload,
        messageStoreScopeId: String?
    ): ThreadSnapshot =
        with(replyParentMessages) { this@withCachedReplyParentMessage.withCachedReplyParentMessage(basePayload, messageStoreScopeId) }

    private suspend fun ThreadSnapshot.withReplyParentMessageFromContext(
        basePayload: RaftThreadScreenPayload,
        allowNetworkHydration: Boolean = true,
        replyResolutionScope: ReplyResolutionScope
    ): ThreadSnapshot =
        with(replyParentMessages) {
            this@withReplyParentMessageFromContext.withReplyParentMessageFromContext(
                basePayload,
                allowNetworkHydration,
                replyResolutionScope,
            )
        }

    private suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        includeFollowedSummaries: Boolean = false,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        replyResolutionScope: ReplyResolutionScope,
        replaceReplyAccessFailure: Boolean = false,
        /**
         * Task #14b: when set, the reply page's raw `messages` array is requested and handed here with
         * the thread channel id the server resolved (both `null`-able: a joined access flight that did not
         * capture it, or a snapshot without its stream id), before the payload is built.
         */
        onRawReplyPage: ((threadChannelId: String?, page: RawMessagePage?) -> Unit)? = null,
        /** Task #14 part C: receives the anchor context page of a focused open (see [SharedThreadDurableContextPage]). */
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null
    ): DataResult<RaftThreadScreenPayload> = coroutineScope {
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val routeThreadId =
            basePayload.streamChannelId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
                ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        val focusedContextOutcome =
            if (includeFollowedSummaries || routeThreadId == null) {
                FocusedInitialContextOutcome.NotAttemptedOrTransient
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = routeThreadId,
                    accessFallback = null,
                    replyResolutionScope = replyResolutionScope,
                    onFocusedContextPage = onFocusedContextPage
                )
            }
        if (focusedContextOutcome is FocusedInitialContextOutcome.Loaded) {
            return@coroutineScope focusedContextOutcome.result
        }
        val focusMissing = focusedContextOutcome is FocusedInitialContextOutcome.TargetMissing
        val effectiveBase =
            if (focusMissing) {
                basePayload.copy(focusedMessageId = null, focusedMessageRequestNonce = 0)
            } else {
                basePayload
            }
        val parentContextStartedAt = TimeSource.Monotonic.markNow()
        val parentContextDeferred =
            async {
                resolveReplyParentMessageFromStore(
                    parentChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    requestPriority = requestPriority,
                    messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                    expectedProvenance = replyResolutionScope.provenance
                )
            }
        val accessFlightKey = replyThreadAccessFlightKey(replyResolutionScope, basePayload)
        // Message storage Phase 2b: set only when THIS caller's request ran. A joined access flight (another
        // caller's request, possibly sent before this caller took its durable ticket) never hands its page over.
        var ownFetch = false
        suspend fun fetchInitialSnapshot(): DataResult<ThreadSnapshot> {
            ownFetch = true
            if (accessFlightKey != null &&
                !replyThreadAccessAuthorityStillCurrent(
                    expectedKey = accessFlightKey,
                    expectedProvenance = replyResolutionScope.provenance,
                    basePayload = basePayload
                )
            ) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            val result =
                fetchThreadSnapshotFromRepository(
                    fallbackCode = "thread_network_reply",
                    expectedProvenance = replyResolutionScope.provenance
                ) { repository ->
                    val pageSize = threadPageSize(session.currentServerId)
                    diagnosticsLogger(
                        "thread_page_size source=initial_reply server=${session.currentServerId.orEmpty()} " +
                            "pageSize=$pageSize"
                    )
                    repository.getReplyThread(
                        parentChannelId = ChannelId(parentChannelId),
                        parentMessageId = MessageId(parentMessageId),
                        includeFollowedSummaries = includeFollowedSummaries,
                        limit = pageSize,
                        priority = requestPriority,
                        captureRawMessagePage = onRawReplyPage != null
                    )
                }
            if (accessFlightKey != null &&
                !replyThreadAccessAuthorityStillCurrent(
                    expectedKey = accessFlightKey,
                    expectedProvenance = replyResolutionScope.provenance,
                    basePayload = basePayload
                )
            ) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            return result
        }
        val initialResult =
            if (accessFlightKey == null) {
                fetchInitialSnapshot()
            } else {
                payloadCache.joinOrStartReplyThreadAccessFlight(
                    key = accessFlightKey,
                    replaceFailure = replaceReplyAccessFailure,
                    fetch = ::fetchInitialSnapshot
                )
            }
        return@coroutineScope when (initialResult) {
            is DataResult.Content -> {
                onReplyThreadAccessValidatedForTest?.invoke()
                onRawReplyPage?.invoke(
                    initialResult.data.conversation.thread?.streamChannelId?.value?.takeIf { it.isNotBlank() },
                    initialResult.data.rawMessagePage.page?.takeIf { ownFetch }
                )
                val hasReplyContent = initialResult.data.hasRenderableReplyThreadMessages(basePayload)
                val parentContextRemainingGraceMillis =
                    (replyOriginHydrationGraceMillis -
                        parentContextStartedAt.elapsedNow().inWholeMilliseconds)
                        .coerceAtLeast(0L)
                val parentResolution =
                    if (parentContextDeferred.isCompleted) {
                        parentContextDeferred.await()
                    } else {
                        withTimeoutOrNull(parentContextRemainingGraceMillis) {
                            parentContextDeferred.await()
                        }
                    }
                // A grace timeout only means that the canonical parent lookup has not
                // completed yet. It is not evidence that the parent message is absent.
                // The scoped message-store producer may still complete after this
                // caller is cancelled, and the visible route will hydrate that result.
                val parentContextTimedOut = parentResolution == null
                if (parentContextTimedOut) {
                    parentContextDeferred.cancel()
                    diagnosticsLogger(
                        "thread_parent_context_grace_expired parentChannelId=$parentChannelId " +
                            "parentMessageId=$parentMessageId " +
                            "graceMs=$replyOriginHydrationGraceMillis " +
                            "hasReplyContent=$hasReplyContent"
                    )
                }
                val parentMessage =
                    (parentResolution as? SharedThreadMessageResolution.Loaded)?.message
                val resolvedThreadId =
                    initialResult.data.conversation.thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.channelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.threadId.value.takeIf { it.isNotBlank() }
                        ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                        ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                val resolvedBase =
                    if (resolvedThreadId == null) {
                        effectiveBase
                    } else {
                        effectiveBase.copy(
                            sourceThreadId = resolvedThreadId,
                            streamChannelId = resolvedThreadId
                        )
                    }
                val originResolvedBase =
                    when (parentResolution) {
                        is SharedThreadMessageResolution.Loaded -> resolvedBase
                        SharedThreadMessageResolution.Missing ->
                            resolvedBase.copy(
                                originStatus = "missing",
                                originErrorCode = null
                            )
                        is SharedThreadMessageResolution.Error ->
                            resolvedBase.copy(
                                originStatus = "error",
                                originErrorCode = parentResolution.code,
                                originHiddenTarget = parentResolution.hiddenTarget
                            )
                        null ->
                            resolvedBase.copy(
                                originStatus = "loading",
                                originErrorCode = null
                            )
                    }
                val payload =
                    initialResult.data
                        .withReplyParentMessage(parentChannelId, parentMessageId, parentMessage)
                        .withReplyThreadReplyCountOnParent()
                        .rememberReplyParentMessage(
                            parentChannelId,
                            parentMessageId,
                            replyResolutionScope.messageStoreScopeId
                        )
                        .withReplyParentMessageFromContext(
                            basePayload = originResolvedBase,
                            allowNetworkHydration = false,
                            replyResolutionScope = replyResolutionScope
                        )
                        .toRaftThreadPayload(originResolvedBase, session.user.id)
                        .let { if (focusMissing) it.copy(focusedTargetMissing = true) else it }
                DataResult.Content(
                    if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && payload.messages.isEmpty()) {
                        payload.toEmptyReplyThreadPayload()
                    } else {
                        payload
                    }
                )
            }
            is DataResult.Empty -> {
                parentContextDeferred?.cancel()
                initialResult
            }
            is DataResult.Error -> {
                parentContextDeferred?.cancel()
                initialResult
            }
            is DataResult.NoPermission -> {
                parentContextDeferred?.cancel()
                initialResult
            }
        }
    }

    private suspend fun resolveReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): SharedThreadMessageResolution =
        replyParentMessages.resolveReplyParentMessageFromStore(
            parentChannelId,
            parentMessageId,
            requestPriority,
            messageStoreScopeId,
            expectedProvenance,
        )

    private fun ThreadSnapshot.rememberReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String,
        messageStoreScopeId: String?
    ): ThreadSnapshot =
        with(replyParentMessages) {
            this@rememberReplyParentMessage.rememberReplyParentMessage(parentChannelId, parentMessageId, messageStoreScopeId)
        }

    private suspend fun fetchResolvedReplyThreadSnapshot(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<Pair<RaftThreadScreenPayload, ThreadSnapshot?>> {
        val resolvedBasePayload =
            when (
                val resolved =
                    createOrOpenReplyThreadStream(
                        basePayload = basePayload,
                        replyResolutionScope = replyResolutionScope
                    )
            ) {
                is DataResult.Content -> resolved.data
                is DataResult.Empty -> return resolved
                is DataResult.Error -> return resolved
                is DataResult.NoPermission -> return resolved
            }
        val resolvedThreadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val resolvedResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_network",
                expectedProvenance = replyResolutionScope.provenance
            ) { repository ->
                val pageSize = threadPageSize(session.currentServerId)
                diagnosticsLogger(
                    "thread_page_size source=resolved_reply server=${session.currentServerId.orEmpty()} " +
                        "pageSize=$pageSize"
                )
                repository.getThread(
                    threadId = ThreadId(resolvedThreadId),
                    includeFollowedSummaries = false,
                    limit = pageSize
                )
            }
        return when (resolvedResult) {
            is DataResult.Content ->
                DataResult.Content(resolvedBasePayload to resolvedResult.data)
            is DataResult.Empty ->
                if (resolvedResult.reason == EmptyReason.NoMessages) {
                    DataResult.Content(resolvedBasePayload to null)
                } else {
                    resolvedResult
                }
            is DataResult.Error -> resolvedResult
            is DataResult.NoPermission -> resolvedResult
        }
    }

    private suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        expectedProvenance: ReplyCacheProvenance,
        allowCapturedScopeResultAfterProvenanceChange: Boolean = false,
        /** The conversation this request loads: gone (404 / lost permission) = its stored header detail goes. */
        loadedConversationId: String? = null,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
        // Offline-first header: the stored detail is fenced from now (the request's start), not its answer.
        val detailFetchStartedAt = storedConversationDetails?.fetchStarted()
        val result =
            try {
                call(repository)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = fallbackCode, retryable = true, traceId = null))
            }
        val detail =
            (result as? DataResult.Content)
                ?.data
                ?.canonicalConversationDetail
                ?.takeIf { candidate ->
                    candidate.membershipKnown &&
                        !candidate.channelType.equals("thread", ignoreCase = true) &&
                        candidate.kind != ConversationKind.Thread
                }
        if (detail != null) {
            if (!isCurrentReplyCacheProvenance(expectedProvenance)) {
                diagnosticsLogger(
                    "conversation_directory_canonical_drop reason=stale_producer " +
                        "serverId=${expectedProvenance.serverId} channelId=${detail.channelId?.value.orEmpty()}"
                )
            } else {
                try {
                    val published = canonicalConversationIdentityPublisher(expectedProvenance, detail)
                    if (published) {
                        storedConversationDetails?.recordCanonical(
                            owner = expectedProvenance.storedConversationDetailOwner(),
                            detail = detail,
                            startedAt = detailFetchStartedAt,
                        )
                    }
                    diagnosticsLogger(
                        "conversation_directory_canonical_publish " +
                            "serverId=${expectedProvenance.serverId} channelId=${detail.channelId?.value.orEmpty()} " +
                            "accepted=$published"
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    diagnosticsLogger(
                        "conversation_directory_canonical_publish_failed " +
                            "serverId=${expectedProvenance.serverId} error=${throwable::class.simpleName.orEmpty()}"
                    )
                }
            }
        }
        val goneConversationId = loadedConversationId?.trim()?.takeIf { it.isNotEmpty() && result.isConversationGone() }
        if (goneConversationId != null && storedConversationDetails != null && isCurrentReplyCacheProvenance(expectedProvenance)) {
            // Deleted / no longer visible: its name and topic must not come back on a cold open.
            storedConversationDetails.remove(expectedProvenance.storedConversationDetailOwner(), goneConversationId)
        }
        if (
            !allowCapturedScopeResultAfterProvenanceChange &&
            !isCurrentReplyCacheProvenance(expectedProvenance)
        ) {
            return staleReplyCacheProvenanceResult()
        }
        return result
    }

    private suspend fun RaftThreadScreenPayload.withSavedStatus(
        serverId: ServerId
    ): RaftThreadScreenPayload {
        val repository = savedStatusRepository ?: return this
        val messageIds =
            (messages.map { it.id } + listOfNotNull(originMessage?.id))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                // Local sends (sending / failed / journal-restored) have no server row, so they can never be
                // saved, and their ids are not UUIDs: older servers fail the whole batch with a 500 (task #335).
                .filterNot { it.startsWith(ThreadTransientLocalSendIdPrefix) }
                .distinct()
                .map(::MessageId)
        if (messageIds.isEmpty()) return this
        val result =
            try {
                repository.checkSavedMessages(serverId, messageIds)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger(
                    "thread_saved_status_refresh_failed errorType=${throwable.safeThreadDiagnosticsType()}"
                )
                return this
            }
        return when (result) {
            is DataResult.Content -> withSavedMessageIds(result.data.savedIds.map { it.value }.toSet())
            is DataResult.Empty -> this
            is DataResult.Error -> {
                diagnosticsLogger("thread_saved_status_refresh_failed result=error code=${result.error.code}")
                this
            }
            is DataResult.NoPermission -> {
                diagnosticsLogger("thread_saved_status_refresh_failed result=no_permission code=${result.blocker.code}")
                this
            }
        }
    }
}

internal fun ThreadApiResult.Failure.toThreadDataError(fallbackCode: String): DataResult<Nothing> =
    toThreadAttachmentCommentsFailureProjection().let { projection ->
        when (code) {
            // Permission loss must surface as NoPermission, not a transient error:
            // downstream treats NoPermission as unrecoverable (e.g. task #562 drops
            // the first-frame cache candidate) while plain errors keep warm state.
            401 ->
                DataResult.NoPermission(
                    PermissionBlocker(
                        code = "auth_required",
                        requiredCapability = "auth_session",
                        serverMessage = projection.serverMessage,
                        traceId = projection.traceId
                    )
                )
            403 -> {
                val normalizedContractCode = contractCode?.trim()?.takeIf { it.isNotBlank() }
                val resolvedServerCode = normalizedContractCode ?: projection.errorCode.takeUnless { it == "http_$code" }
                if (resolvedServerCode != null && isNonPermissionPlanOrQuota403Code(resolvedServerCode)) {
                    DataResult.Error(
                        ScreenError(
                            code = resolvedServerCode,
                            retryable = false,
                            serverMessage = projection.serverMessage,
                            serverCode = resolvedServerCode,
                            traceId = projection.traceId
                        )
                    )
                } else {
                    DataResult.NoPermission(
                        PermissionBlocker(
                            code = normalizedContractCode ?: "thread_forbidden",
                            requiredCapability = "thread_access",
                            serverMessage = projection.serverMessage,
                            serverCode = resolvedServerCode,
                            traceId = projection.traceId
                        )
                    )
                }
            }
            else ->
                DataResult.Error(
                    ScreenError(
                        code =
                            contractCode
                                ?: projection.errorCode.takeUnless { it == "http_$code" }
                                ?: fallbackCode,
                        retryable = contractCode == null,
                        serverMessage = projection.serverMessage,
                        traceId = projection.traceId
                    )
                )
        }
    }

internal fun ThreadApiResult<Unit>.toUnitDataResult(fallbackCode: String): DataResult<Unit> =
    when (this) {
        is ThreadApiResult.Success -> DataResult.Content(Unit)
        is ThreadApiResult.Failure -> toThreadDataError(fallbackCode)
    }
