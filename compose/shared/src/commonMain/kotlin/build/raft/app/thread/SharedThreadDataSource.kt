package build.raft.app

import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.AttachmentUploadCapabilitiesCache
import build.raft.app.data.DataResult
import build.raft.app.data.isNonPermissionPlanOrQuota403Code
import build.raft.app.data.RaftJsonParseDispatcher
import build.raft.app.data.RaftMessageDbDispatcher
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeSnapshotLookup
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.appendSharedThreadSentMessageFromPage
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
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadSendMessageMention
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplyCacheIdentityTransitionAnchor
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.markdown.RaftMarkdownMessageRef
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    /** Samples the reply-cache identity and holds the owner it last captured; see [ThreadReplyIdentityCapture]. */
    private val identityCapture: ThreadReplyIdentityCapture =
        ThreadReplyIdentityCapture(
            provenanceOwner = provenanceOwner,
            messageStore = messageStore,
            replyCacheIdentityAuthority = replyCacheIdentityAuthority,
            replyCacheIdentityTransitionWaiter = replyCacheIdentityTransitionWaiter,
            replyCacheIdentitySourceCapturedHook = { onReplyCacheIdentitySourceCapturedForTest },
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
    /** The page and snapshot fetches a conversation opens and pages with; see [ThreadConversationFetch]. */
    private val conversationFetch: ThreadConversationFetch =
        ThreadConversationFetch(
            provenanceOwner = provenanceOwner,
            payloadCache = payloadCache,
            durableCacheGateway = durableCacheGateway,
            replyThreadPrewarm = replyThreadPrewarm,
            replyParentMessages = replyParentMessages,
            conversationRepository = conversationRepository,
            storedConversationDetails = storedConversationDetails,
            canonicalConversationIdentityPublisher = canonicalConversationIdentityPublisher,
            replyOriginHydrationGraceMillis = replyOriginHydrationGraceMillis,
            diagnosticsLogger = diagnosticsLogger,
            readReplyResolutionScope = ::readReplyResolutionScope,
            threadPageSize = ::threadPageSize,
            replyThreadAccessFlightKeyOf = ::replyThreadAccessFlightKey,
            seedAuthorProfiles = { serverId, payload, source -> seedThreadAuthorProfiles(serverId, payload, source = source) },
            replyThreadAccessValidatedHook = { onReplyThreadAccessValidatedForTest },
        )
    /** The fetches a page asks for (open, refetch, summaries, paging, reply thread); see [ThreadPayloadFetch]. */
    private val payloadFetch: ThreadPayloadFetch =
        ThreadPayloadFetch(
            provenanceOwner = provenanceOwner,
            payloadCache = payloadCache,
            replyThreadPrewarm = replyThreadPrewarm,
            conversationAccessResolution = conversationAccessResolution,
            conversationFetch = conversationFetch,
            api = api,
            conversationRepository = conversationRepository,
            messageStore = messageStore,
            diagnosticsLogger = diagnosticsLogger,
            readReplyResolutionScope = ::readReplyResolutionScope,
            captureReplyCacheProducerContext = ::captureReplyCacheProducerContext,
            threadPageSize = ::threadPageSize,
            cacheCanonicalPayloadOf = ::cacheCanonicalPayload,
            seedAuthorProfiles = { serverId, payload, source -> seedThreadAuthorProfiles(serverId, payload, source = source) },
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
    // Written only by [identityCapture]; read through it.
    private val cacheEnvironmentId: StateFlow<String> get() = identityCapture.cacheEnvironmentId
    private val cacheServerId: StateFlow<String> get() = identityCapture.cacheServerId
    private val cachePrincipalId: StateFlow<String> get() = identityCapture.cachePrincipalId
    private val cachePayloadOwnerScopeId: StateFlow<String> get() = identityCapture.cachePayloadOwnerScopeId
    internal val replyCacheProvenanceState: StateFlow<ReplyCacheProvenance?> =
        provenanceOwner.replyCacheProvenanceState
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

    private suspend fun readReplyResolutionScope(): ReplyResolutionScope? = identityCapture.readReplyResolutionScope()

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
        allowLegacyStandIn: Boolean = false,
    ): SharedThreadDurableOlderPage? =
        durableCacheGateway.durableOlderPageIfCurrent(
            routeIdentity,
            context,
            beforeSeq,
            expectedOwnerScopeId,
            replyParent,
            allowLegacyStandIn,
        )

    internal suspend fun durableNewerPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        afterSeq: Long,
        expectedOwnerScopeId: String?,
        replyParent: ThreadDurableReplyParent? = null,
    ): SharedThreadDurableNewerPage? =
        durableCacheGateway.durableNewerPageIfCurrent(
            routeIdentity,
            context,
            afterSeq,
            expectedOwnerScopeId,
            replyParent,
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

    suspend fun withHomeConversationContext(payload: RaftThreadScreenPayload): RaftThreadScreenPayload =
        conversationAccessResolution.withHomeConversationContext(payload)

    internal fun forgetReferenceTargetsOf(payload: RaftThreadScreenPayload): Int =
        markdownRefLaunch.forgetReferenceTargetsOf(payload)

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
        payloadFetch.fetchThreadPayload(basePayload, persistResult, reusePrewarm, replaceReplyAccessFailure)

    internal suspend fun fetchThreadPayloadForContext(
        basePayload: RaftThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false,
        replaceReplyAccessFailure: Boolean = false,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null,
        onSupersededCanonicalResult: (suspend (DataResult<RaftThreadScreenPayload>) -> Unit)? = null,
        onCanonicalRefetchCapped: (() -> Unit)? = null,
        foldsRealtimeIntoSupersededResult: ((Set<String>) -> Boolean)? = null,
    ): DataResult<RaftThreadScreenPayload> =
        payloadFetch.fetchThreadPayloadForContext(
            basePayload,
            expectedContext,
            persistResult,
            reusePrewarm,
            replaceReplyAccessFailure,
            onFocusedContextPage,
            onEnteredPage,
            onSupersededCanonicalResult,
            onCanonicalRefetchCapped,
            foldsRealtimeIntoSupersededResult,
        )

    suspend fun prewarmReplyThreadOnCurrentServer(
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
    ): DataResult<Unit> =
        replyThreadPrewarm.prewarmReplyThreadOnCurrentServer(parentChannelId, parentMessageId, threadChannelId)

    suspend fun prewarmReplyThread(
        marker: ActivityReplyThreadPrewarmMarker
    ): DataResult<Unit> = replyThreadPrewarm.prewarmReplyThread(marker)

    suspend fun fetchWebLinkThreadPayload(
        basePayload: RaftThreadScreenPayload,
        target: RaftThreadWebLinkTarget
    ): DataResult<RaftThreadScreenPayload> = payloadFetch.fetchWebLinkThreadPayload(basePayload, target)

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
    ): DataResult<RaftThreadScreenPayload> = payloadFetch.fetchFollowedThreadPayload(basePayload, onSummaries)

    internal suspend fun fetchFollowedThreadPayloadForContext(
        basePayload: RaftThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext?,
        onSummaries: (ThreadReplySummariesSnapshot, TimeSource.Monotonic.ValueTimeMark) -> Unit = { _, _ -> },
    ): DataResult<RaftThreadScreenPayload> =
        payloadFetch.fetchFollowedThreadPayloadForContext(basePayload, expectedContext, onSummaries)

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
    ): DataResult<SharedThreadPageFetchResult> = payloadFetch.fetchOlderThreadPayload(basePayload, beforeSeq)

    suspend fun fetchNewerThreadPayload(
        basePayload: RaftThreadScreenPayload,
        afterSeq: Long
    ): DataResult<SharedThreadPageFetchResult> = payloadFetch.fetchNewerThreadPayload(basePayload, afterSeq)

    suspend fun fetchGapCatchUpLatestPage(
        basePayload: RaftThreadScreenPayload,
        wide: Boolean,
    ): DataResult<SharedThreadPageFetchResult> = payloadFetch.fetchGapCatchUpLatestPage(basePayload, wide)

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
    ): DataResult<RaftThreadScreenPayload> = payloadFetch.createOrOpenReplyThread(basePayload, parentMessage)

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
    ): DataResult<RaftThreadScreenPayload> = payloadFetch.openMessageContext(messageId, basePayload)

    suspend fun openCommentHost(
        hostMessageId: String,
        basePayload: RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> = payloadFetch.openCommentHost(hostMessageId, basePayload)

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
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
    ): FocusedInitialContextOutcome =
        conversationFetch.fetchFocusedInitialThreadPayloadFromRepository(
            session,
            basePayload,
            routeThreadId,
            accessFallback,
            replyResolutionScope,
            onFocusedContextPage,
            requestPriority,
        )

    private suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        includeFollowedSummaries: Boolean = false,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        replyResolutionScope: ReplyResolutionScope,
        replaceReplyAccessFailure: Boolean = false,
        onRawReplyPage: ((threadChannelId: String?, page: RawMessagePage?) -> Unit)? = null,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null
    ): DataResult<RaftThreadScreenPayload> =
        conversationFetch.fetchInitialReplyThreadPayloadFromRepository(
            session,
            basePayload,
            includeFollowedSummaries,
            requestPriority,
            replyResolutionScope,
            replaceReplyAccessFailure,
            onRawReplyPage,
            onFocusedContextPage,
        )

    private suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        expectedProvenance: ReplyCacheProvenance,
        allowCapturedScopeResultAfterProvenanceChange: Boolean = false,
        loadedConversationId: String? = null,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> =
        conversationFetch.fetchThreadSnapshotFromRepository(
            fallbackCode,
            expectedProvenance,
            allowCapturedScopeResultAfterProvenanceChange,
            loadedConversationId,
            call,
        )

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
