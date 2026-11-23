package build.raft.app

import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.AttachmentUploadCapabilitiesCache
import build.raft.app.data.DataResult
import build.raft.app.data.isNonPermissionPlanOrQuota403Code
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftJsonParseDispatcher
import build.raft.app.data.RaftMessageDbDispatcher
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeSnapshotLookup
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.ConversationRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.ProgressiveConversationAttachmentUploader
import build.raft.app.data.DirectAttachmentUploadOperation
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.matchesExpectedAuthority
import build.raft.app.data.normalizedEnvironmentId
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
import build.raft.app.data.SharedReferenceKey
import build.raft.app.data.SharedReferenceKind
import build.raft.app.data.SharedReferenceOutcome
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
import build.raft.app.data.toSharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.markdown.RaftMarkdownMessageRef
import build.raft.app.markdown.raftLoungeConversationAgentName
import build.raft.app.model.MemberProfile
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
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadDurableAccessEvictor
import build.raft.app.network.sync.ThreadDurableTimelineAnchor
import build.raft.app.network.sync.windowEnvelopes
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesScopeIdentity
import build.raft.app.network.sync.RaftCachedThreadReplyWindow
import build.raft.app.network.sync.toThreadMessageItem
import build.raft.app.model.messageStreamChannelId
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
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
    private val replyThreadResolveCache = mutableMapOf<ReplyThreadResolveKey, String>()
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
    private val cacheEnvironmentId = MutableStateFlow("")
    private val cacheServerId = MutableStateFlow("")
    private val cachePrincipalId = MutableStateFlow("")
    private val cachePayloadOwnerScopeId = MutableStateFlow("")
    private val replyCacheCaptureSequence = atomic(0L)
    internal val replyCacheProvenanceState: StateFlow<ReplyCacheProvenance?> =
        provenanceOwner.replyCacheProvenanceState
    // The lock and the provenance it guards are owned by [provenanceOwner]; the data source's
    // other lock-held cache paths take the same lock object and read the provenance through it.
    private val replyCacheProvenanceLock: SynchronizedObject get() = provenanceOwner.replyCacheProvenanceLock
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

    /**
     * Phase 3b: the durable partition's head epoch, read BEFORE a page's HTTP request and handed to the durable
     * writer with that page: a horizonReset in between voids the page's head claim (never its facts).
     */
    private fun durableHeadEpochOrNull(provenance: ReplyCacheProvenance): Long? {
        val partition = provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) ?: return null
        return durablePageExtender?.headEpoch(partition) ?: enteredScopeSnapshotLoader?.headEpoch(partition)
    }

    /** Phase 2c: the durable write revision of [provenance]'s partition, read BEFORE a page fetch (`revAtRequest`). */
    private suspend fun durableLocalRevOrNull(provenance: ReplyCacheProvenance): Long? {
        val partition = provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) ?: return null
        return durablePageExtender?.currentLocalRev(partition)
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

    /**
     * Durable last-known first frame for a top-level route (channel / DM) or, task #168 step 3, a
     * reply-thread route with its typed parent, read from the `sync-core.messages.v2` partition of
     * [context]'s identity. Returns `null` for every non-positive case (no reader, reply route
     * without its typed parent or whose stored parent differs, unrenderable route, incomplete identity,
     * identity moved during the disk read, flag off, no baseline, projection produced nothing) so the
     * caller falls through to the ordinary loading shell. The returned payload is a stand-in, not an
     * authority: paging is closed and `durableLastKnown` is set so the first network content slice
     * replaces it wholesale.
     */
    /**
     * Offline cache write entry (task #1124; message storage Phase 2b): called BEFORE the thread store's own page
     * request for [routeIdentity]. Same admission as [durableLastKnownPayloadIfCurrent] (renderable route, resolvable
     * channel id, an owning partition, current provenance). Returns the writer's ticket for
     * [persistEnteredPageIfCurrent], or `null` (nothing will be written for this request).
     */
    internal suspend fun beginEnteredPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent? = null,
    ): ThreadDurableEnterTicket? {
        val loader = enteredScopeSnapshotLoader ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return null
        val channelId = routeIdentity.timelineChannelId ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        return loader.beginEnter(partition, channelId, (admission as? DurableScopeAdmission.ReplyThread)?.parent)
    }

    /** Phase 2b: whether a durable page writer is wired (the write-through is attempted at all). */
    internal val hasEnteredPageWriter: Boolean get() = enteredScopeSnapshotLoader != null

    /** Phase 2b: the partition generation and write revision, read before a request that cannot take a ticket yet. */
    internal suspend fun durableRequestStampIfCurrent(context: ReplyCacheProducerContext): ThreadDurableRequestStamp? {
        val loader = enteredScopeSnapshotLoader ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        return loader.requestStamp(partition)
    }

    /**
     * Phase 2b: write the page the thread store fetched through to the durable partition. With [ticket] (taken
     * before the request) the page is that request's; without one (a reply thread whose stream the request itself
     * resolved) a ticket without a realtime baseline is taken now for [routeIdentity]'s resolved channel, from
     * [stamp] ([durableRequestStampIfCurrent], read before the request). Without a ticket and without a stamp of
     * the same partition nothing is written: a stamp read now, after the request, would miss a horizonReset during
     * it and let the page claim the head (#3875).
     */
    internal suspend fun persistEnteredPageIfCurrent(
        ticket: ThreadDurableEnterTicket?,
        stamp: ThreadDurableRequestStamp?,
        page: ThreadDurableEnteredPage,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent? = null,
    ) {
        val loader = enteredScopeSnapshotLoader ?: return
        val stillCurrent = { isCurrentReplyCacheProvenance(context.provenance) }
        val effective =
            ticket ?: run {
                // Never read at write time: the stamp must predate the request (see above).
                if (stamp == null) return
                if (!routeIdentity.canRenderTimelineCandidates) return
                val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return
                val partition =
                    context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                        ?: return
                if (stamp.partition != partition) return
                if (!stillCurrent()) return
                loader.beginEnter(
                    partition,
                    page.channelId,
                    (admission as? DurableScopeAdmission.ReplyThread)?.parent,
                    registerBaseline = false,
                    stamp = stamp,
                ) ?: return
            }
        loader.persistEnteredPage(effective, page, stillCurrent)
    }

    /** Phase 2b: [ticket]'s request produced no page to write. */
    internal suspend fun abandonEnteredPage(ticket: ThreadDurableEnterTicket) {
        enteredScopeSnapshotLoader?.abandonEnter(ticket)
    }

    /**
     * Which durable V2 scope family a route may use (task #168 step 3): channel / DM routes the
     * top-level window; a reply-thread route its `thread` scope, and only with its typed parent
     * (parent channel + root message). Anything else has no durable scope (`null`).
     */
    private fun durableScopeAdmission(
        routeIdentity: ThreadRouteIdentity,
        replyParent: ThreadDurableReplyParent?,
    ): DurableScopeAdmission? =
        when (routeIdentity.surfaceKind) {
            RaftThreadSurfaceKind.Channel, RaftThreadSurfaceKind.DirectMessage -> DurableScopeAdmission.TopLevel
            RaftThreadSurfaceKind.ReplyThread ->
                replyParent
                    ?.takeIf { it.parentChannelId != routeIdentity.timelineChannelId }
                    ?.let(DurableScopeAdmission::ReplyThread)
            else -> null
        }

    private sealed interface DurableScopeAdmission {
        data object TopLevel : DurableScopeAdmission

        data class ReplyThread(val parent: ThreadDurableReplyParent) : DurableScopeAdmission
    }

    /**
     * Offline cache paging entry (task #168): hand a fetched older / newer page to the durable window
     * extender, which appends it only when it is exactly adjacent to the stored window. Same admission
     * as [beginEnteredPageIfCurrent]; `stillCurrent` is re-read by the extender after its own
     * network round trip. Returns whether the extender was asked at all (diagnostics only).
     */
    internal suspend fun extendDurableWindowIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent? = null,
        headEpochAtRequest: Long? = null,
        revAtRequest: Long? = null,
    ): Boolean {
        val extender = durablePageExtender ?: return false
        if (!routeIdentity.canRenderTimelineCandidates) return false
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return false
        val channelId = routeIdentity.timelineChannelId ?: return false
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return false
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        extender.extendWithAdjacentPage(
            partition = partition,
            channelId = channelId,
            direction = direction,
            anchorSeq = anchorSeq,
            rawMessages = rawPage.messages,
            requestedLimit = rawPage.requestedLimit,
            replyParent = (admission as? DurableScopeAdmission.ReplyThread)?.parent,
            requestHeadEpoch = headEpochAtRequest,
            messageWindow = rawPage.messageWindow,
            historyLimited = rawPage.historyLimited,
            revAtRequest = revAtRequest,
            replyCountByParentId = rawPage.replyCountsByParentId,
        ) { isCurrentReplyCacheProvenance(context.provenance) }
        return true
    }

    /**
     * Task #14 part C ("every fact source must not be wasted"): hand the anchor context page an anchored open
     * fetched to the durable writer, which joins it to the stored window when it provably can (see
     * [ThreadDurablePageExtender.persistContextPage]). Same admission as [extendDurableWindowIfCurrent]; a
     * refusal on this side logs one `canonical_v2_context_page outcome=skipped` line, the writer logs its own.
     * Returns whether the writer was asked at all (diagnostics only).
     */
    internal suspend fun persistDurableContextPageIfCurrent(page: SharedThreadDurableContextPage): Boolean {
        val extender = durablePageExtender ?: return false
        val routeIdentity = page.routeIdentity
        val channelId = routeIdentity.timelineChannelId
        fun skip(reason: String): Boolean {
            diagnosticsLogger(
                "canonical_v2_context_page server=${page.context.provenance.serverId} scope=${channelId.orEmpty()} " +
                    "anchor=${page.anchorMessageId} outcome=skipped reason=$reason facts=0"
            )
            return false
        }
        if (!routeIdentity.canRenderTimelineCandidates || channelId == null) return skip("not_renderable")
        val admission =
            durableScopeAdmission(routeIdentity, page.replyParent)
                ?: return skip(
                    if (routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) "no_reply_parent" else "not_top_level"
                )
        val partition =
            page.context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return skip("no_partition")
        if (!isCurrentReplyCacheProvenance(page.context.provenance)) return skip("identity_changed")
        // The scope key is the response's, which may be a thread of this route's channel; never the route's by default.
        val response = page.rawPage.context ?: return skip("no_response_channel")
        val routeParent = (admission as? DurableScopeAdmission.ReplyThread)?.parent
        val targetParent = response.threadParentChannelId?.let { parentChannelId ->
            response.threadRootMessageId?.let { root -> ThreadDurableReplyParent(parentChannelId, root) }
        }
        extender.persistContextPage(
            partition = partition,
            channelId = response.channelId,
            anchorMessageId = response.targetMessageId ?: page.anchorMessageId,
            rawMessages = page.rawPage.messages,
            hasOlder = page.hasOlder,
            hasNewer = page.hasNewer,
            replyParent = targetParent ?: routeParent?.takeIf { response.channelId == channelId },
            requestHeadEpoch = page.headEpochAtRequest,
            historyLimited = page.rawPage.historyLimited,
            revAtRequest = page.revAtRequest,
            replyCountByParentId = page.rawPage.replyCountsByParentId,
        ) { isCurrentReplyCacheProvenance(page.context.provenance) }
        return true
    }

    /**
     * Offline cache gap catch-up entry (task #168 step 1): hand the latest page fetched by the gap
     * catch-up to the durable writer. It extends the stored window's newest edge when the page
     * overlaps it (a stored fact is on the page) and otherwise replaces the window with the page
     * (`window_replaced`). Same admission as [extendDurableWindowIfCurrent]. Returns whether the
     * writer was asked at all (diagnostics only).
     */
    internal suspend fun applyDurableLatestPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent? = null,
        baseline: CanonicalMessageV2EnterBaseline? = null,
        headEpochAtRequest: Long? = null,
        revAtRequest: Long? = null,
    ): Boolean =
        try {
            applyDurableLatestPage(routeIdentity, context, rawPage, replyParent, baseline, headEpochAtRequest, revAtRequest)
        } finally {
            // No-op once the page committed under it; otherwise the frames it buffered were never written.
            baseline?.let { abandonDurableLatestPageBaseline(it) }
        }

    private suspend fun applyDurableLatestPage(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent?,
        baseline: CanonicalMessageV2EnterBaseline?,
        headEpochAtRequest: Long?,
        revAtRequest: Long?,
    ): Boolean {
        val extender = durablePageExtender ?: return false
        if (!routeIdentity.canRenderTimelineCandidates) return false
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return false
        val channelId = routeIdentity.timelineChannelId ?: return false
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return false
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        extender.applyLatestPageAfterBaseline(
            partition = partition,
            channelId = channelId,
            rawMessages = rawPage.messages,
            requestedLimit = rawPage.requestedLimit,
            replyParent = (admission as? DurableScopeAdmission.ReplyThread)?.parent,
            // The writer uses it only for this exact partition + top-level channel.
            baseline = baseline,
            messageWindow = rawPage.messageWindow,
            historyLimited = rawPage.historyLimited,
            requestHeadEpoch = headEpochAtRequest,
            revAtRequest = revAtRequest,
        ) { isCurrentReplyCacheProvenance(context.provenance) }
        return true
    }

    /**
     * Task #168 step 3: the realtime baseline of a gap catch-up of the viewed channel / DM, registered
     * BEFORE its latest-page fetch (frames of the channel during the fetch are buffered, then replayed when
     * the page commits). `null` = none (no durable scope, no identity, or the writer refused: not a
     * member, or the connection has not seen `rooms:joined`): the catch-up commits as before. Task #14c: a
     * reply-thread route with its typed parent registers its thread scope's baseline the same way.
     */
    /**
     * Message storage Phase 2b: the current identity's durable write revision, read BEFORE a page fetch and handed to
     * the page's writer as `revAtRequest`. `null` = no writer / no identity (the writer then reads it itself).
     */
    internal suspend fun durableLocalRevIfCurrent(): Long? {
        val extender = durablePageExtender ?: return null
        val resolution = readReplyResolutionScope() ?: return null
        val partition =
            resolution.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        return extender.currentLocalRev(partition)
    }

    internal suspend fun beginDurableLatestPageBaselineIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        replyParent: ThreadDurableReplyParent? = null,
    ): CanonicalMessageV2EnterBaseline? {
        val extender = durablePageExtender ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return null
        val channelId = routeIdentity.timelineChannelId ?: return null
        val resolution = readReplyResolutionScope() ?: return null
        val partition =
            resolution.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(resolution.provenance)) return null
        return extender.beginLatestPageBaseline(
            partition,
            channelId,
            (admission as? DurableScopeAdmission.ReplyThread)?.parent,
        )
    }

    /**
     * Task #14c: the late-ack catch-up requests for this reply-thread route's own thread scope (same admission and
     * partition as [beginEnteredPageIfCurrent]); `null` for any other route or without a writer.
     */
    internal fun threadJoinConfirmationsIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent?,
    ): Flow<CanonicalMessageV2ThreadJoinConfirmed>? {
        val extender = durablePageExtender ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, replyParent) as? DurableScopeAdmission.ReplyThread ?: return null
        val channelId = routeIdentity.timelineChannelId ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        return extender.threadJoinConfirmations().filter { confirmed ->
            confirmed.partition == partition &&
                confirmed.threadScopeId == channelId &&
                confirmed.replyParentScopeId == admission.parent.parentChannelId
        }
    }

    internal suspend fun abandonDurableLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) {
        withContext(NonCancellable) { durablePageExtender?.abandonLatestPageBaseline(baseline) }
    }

    /**
     * Last known reply-preview windows of [page] (Hands dd79da45), or empty. Same identity rule as
     * the durable frame: the provenance must still be current after the disk read.
     */
    internal suspend fun cachedReplyWindowsIfCurrent(
        page: ThreadRepliesPageIdentity,
        context: ReplyCacheProducerContext,
    ): Map<String, RaftCachedThreadReplyWindow> {
        val cache = durableLastKnownReader?.replyWindowCache ?: return emptyMap()
        if (!isCurrentReplyCacheProvenance(context.provenance)) return emptyMap()
        val windows = cache.load(page)
        if (!isCurrentReplyCacheProvenance(context.provenance)) return emptyMap()
        return windows
    }

    /** Persists accepted reply-preview windows for the next cold start. */
    internal fun recordReplyWindows(projections: Collection<ThreadRepliesAcceptedProjection>) {
        val cache = durableLastKnownReader?.replyWindowCache ?: return
        // Only the current principal's windows are written: a summary that lands after a sign-out
        // or account switch must not re-create content the auth cleanup just wiped.
        val provenance = currentReplyCacheProvenance() ?: return
        val current =
            projections.filter { projection ->
                projection.identity.page.principalId == provenance.principalId &&
                    projection.identity.page.serverId == provenance.serverId
            }
        cache.record(current)
    }

    internal suspend fun touchDurableUserAccess(scope: SyncScopeKey) {
        val reader = durableLastKnownReader ?: return
        withContext(durableDbDispatcher) {
            reader.touchUserAccess(scope)
        }
    }

    internal suspend fun durableLastKnownPayloadIfCurrent(
        routePayload: RaftThreadScreenPayload,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        allowNewestFallbackForMissingAnchor: Boolean = true,
    ): RaftThreadScreenPayload? =
        durableLastKnownResultIfCurrent(
            routePayload = routePayload,
            routeIdentity = routeIdentity,
            context = context,
            allowNewestFallbackForMissingAnchor = allowNewestFallbackForMissingAnchor,
        )?.payload

    internal suspend fun durableLastKnownResultIfCurrent(
        routePayload: RaftThreadScreenPayload,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        allowNewestFallbackForMissingAnchor: Boolean = true,
    ): SharedThreadDurableLastKnownResult? {
        val reader = durableLastKnownReader ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, routePayload.durableReplyParentOrNull()) ?: return null
        val allowedScopeKinds = routeIdentity.durableScopeKinds()
        val channelId = routeIdentity.timelineChannelId ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        val pageSize = threadPageSize(partition.serverId)
        val focusedId = routePayload.focusedMessageId?.trim()?.takeIf { it.isNotEmpty() }
        // Phase 2d: prefer bounded SQLite timeline reads (newest page or anchored island window),
        // falling back to newest / legacy `read` only when allowed (cold offline opens so
        // `anchorPendingStandIn` stays available when the target message is not stored yet).
        suspend fun readTimelineFor(anchor: ThreadDurableTimelineAnchor) =
            when (admission) {
                DurableScopeAdmission.TopLevel ->
                    reader.readTimeline(partition, channelId, anchor, touchUserAccess = false)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadTimeline(partition, channelId, admission.parent, anchor, touchUserAccess = false)
            }
        val primaryAnchor =
            if (focusedId != null) {
                val beforeCount = (pageSize / 2).coerceAtLeast(1)
                ThreadDurableTimelineAnchor.AroundMessageId(
                    messageId = focusedId,
                    beforeCount = beforeCount,
                    afterCount = (pageSize - beforeCount).coerceAtLeast(1),
                )
            } else {
                ThreadDurableTimelineAnchor.Newest(limit = pageSize)
            }
        val anchoredTimelinePage = readTimelineFor(primaryAnchor)
        val fellBackToNewestForMissingAnchor =
            focusedId != null && anchoredTimelinePage == null && allowNewestFallbackForMissingAnchor
        val timelinePage =
            anchoredTimelinePage
                ?: if (fellBackToNewestForMissingAnchor) {
                    readTimelineFor(ThreadDurableTimelineAnchor.Newest(limit = pageSize))
                } else {
                    null
                }
        if (timelinePage != null) {
            if (!isCurrentReplyCacheProvenance(context.provenance)) return null
            if (timelinePage.scope.scopeKind !in allowedScopeKinds) {
                diagnosticsLogger(
                    "thread_durable_last_known threadId=$channelId outcome=surface_mismatch " +
                        "surface=${routeIdentity.surfaceKind} scopeKind=${timelinePage.scope.scopeKind}"
                )
                return null
            }
            if (timelinePage.envelopes.isEmpty()) {
                if (focusedId != null && !fellBackToNewestForMissingAnchor) return null
                diagnosticsLogger(
                    "thread_durable_last_known threadId=$channelId outcome=empty_frame scopeKind=${timelinePage.scope.scopeKind}"
                )
                return SharedThreadDurableLastKnownResult(
                    payload =
                        routePayload.asRefreshEmpty(routePayload).copy(
                            durableLastKnown = true,
                            durableReachedOldest = timelinePage.reachesOldest,
                            timelineGaps = emptyList(),
                        ),
                    scope = timelinePage.scope,
                )
            }
            val messages =
                timelinePage.envelopes
                    .mapNotNull { envelope ->
                        envelope.toThreadMessageItem(
                            currentUserId = context.provenance.principalId,
                            diagnostics = diagnosticsLogger,
                        )
                    }
                    .sortedByThreadTimeline()
            if (messages.isEmpty()) return null
            val gaps =
                reconcileThreadTimelineGaps(
                    existingGaps = emptyList(),
                    addedGaps = timelinePage.gaps.map { it.toThreadTimelineGap() },
                    messages = messages,
                )
            val canLoadMoreBefore = timelinePage.hasOlderStored || !timelinePage.reachesOldest
            val canLoadMoreAfter =
                if (focusedId != null && !fellBackToNewestForMissingAnchor) {
                    timelinePage.hasNewerStored || !timelinePage.reachesHead
                } else {
                    false
                }
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=${if (focusedId != null && !fellBackToNewestForMissingAnchor) "anchored_frame" else "frame"} " +
                    "scopeKind=${timelinePage.scope.scopeKind} messages=${messages.size} gaps=${gaps.size} " +
                    "latestSeq=${timelinePage.cursor.latestSeq} canLoadMoreBefore=$canLoadMoreBefore canLoadMoreAfter=$canLoadMoreAfter"
            )
            return SharedThreadDurableLastKnownResult(
                payload =
                    routePayload.copy(
                        status = "content",
                        backgroundRefreshing = false,
                        durableLastKnown = true,
                        messages = messages,
                        timelineGaps = gaps,
                        canLoadMoreBefore = canLoadMoreBefore,
                        durableReachedOldest = !canLoadMoreBefore,
                        canLoadMoreAfter = canLoadMoreAfter,
                        olderMessagesStatus = "idle",
                        newerMessagesStatus = "idle",
                        errorCode = null,
                    ),
                scope = timelinePage.scope,
            )
        }
        if (focusedId != null && !allowNewestFallbackForMissingAnchor) {
            return null
        }
        val frame =
            when (admission) {
                DurableScopeAdmission.TopLevel -> reader.read(partition, channelId)
                is DurableScopeAdmission.ReplyThread -> reader.readReplyThread(partition, channelId, admission.parent)
            } ?: return null
        // The disk read suspended; a sign-out / account switch in between must not let identity A's
        // rows act under identity B's route. Rendering is ALSO refused by the commit gate in start()
        // (`withCurrentReplyCacheProvenance`), but `seedThreadAuthorProfiles` runs upstream of that
        // gate: without this check A's author profiles would be written into the process-wide
        // `serverMembersStore` before the frame is rejected. This line guards the write, not the display.
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        if (frame.scope.scopeKind !in allowedScopeKinds) {
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=surface_mismatch " +
                    "surface=${routeIdentity.surfaceKind} scopeKind=${frame.scope.scopeKind}"
            )
            return null
        }
        if (frame.envelopes.isEmpty()) {
            // Task #1137: the channel was empty when last seen. Stand in with the ordinary empty
            // state (flagged as last-known) rather than falling through to "cannot load".
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=empty_frame scopeKind=${frame.scope.scopeKind}"
            )
            return SharedThreadDurableLastKnownResult(
                payload = routePayload.asRefreshEmpty(routePayload).copy(durableLastKnown = true),
                scope = null,
            )
        }
        // Stand in with the newest page only, the same size the network's first page has: projecting,
        // laying out and drawing the whole stored scope (up to 500 facts) held the first frame behind a
        // loading state for over a second on a cold open (artin, Hands 2d15915b: 147 rows ⇒ ~1.3 s).
        val window = frame.windowEnvelopes()
        val shownEnvelopes = window.sortedByDescending { it.fact.seq }.take(pageSize)
        val messages =
            shownEnvelopes
                .mapNotNull { envelope ->
                    envelope.toThreadMessageItem(
                        currentUserId = context.provenance.principalId,
                        diagnostics = diagnosticsLogger,
                    )
                }
                .sortedByThreadTimeline()
        if (messages.isEmpty()) return null
        // Task #168 step 2b: older paging stays open while the window holds older facts than the ones
        // shown, or the window itself does not reach the channel's start (an online retry may still
        // fetch them). Loading older on the stand-in reads the window first (`durableOlderPageIfCurrent`).
        val canLoadMoreBefore = window.size > shownEnvelopes.size || !frame.cursor.hasLoadedOldest
        diagnosticsLogger(
            "thread_durable_last_known threadId=$channelId outcome=frame scopeKind=${frame.scope.scopeKind} " +
                "messages=${messages.size} stored=${frame.envelopes.size} latestSeq=${frame.cursor.latestSeq} " +
                "canLoadMoreBefore=$canLoadMoreBefore"
        )
        return SharedThreadDurableLastKnownResult(
            payload =
                routePayload.copy(
                    status = "content",
                    backgroundRefreshing = false,
                    durableLastKnown = true,
                    messages = messages,
                    timelineGaps = emptyList(),
                    canLoadMoreBefore = canLoadMoreBefore,
                    durableReachedOldest = !canLoadMoreBefore,
                    canLoadMoreAfter = false,
                    olderMessagesStatus = "idle",
                    newerMessagesStatus = "idle",
                    errorCode = null,
                ),
            scope = null,
        )
    }

    /**
     * Older page from the durable V2 timeline (Phase 2d):
     * - When [crossGaps] is `false` (online live timeline), serves strictly from inside a proven
     *   contiguous SQLite segment (`insideProvenSegment == true`) without touching the network.
     * - When [crossGaps] is `true` (offline stand-in), crosses into lower stored islands and returns
     *   derived [SharedThreadDurableOlderPage.gaps].
     */
    internal suspend fun durableOlderPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        beforeSeq: Long,
        expectedOwnerScopeId: String?,
        replyParent: ThreadDurableReplyParent? = null,
        crossGaps: Boolean = true,
    ): SharedThreadDurableOlderPage? {
        val channelId = routeIdentity.timelineChannelId
        fun refuse(reason: String): SharedThreadDurableOlderPage? {
            diagnosticsLogger(
                "thread_durable_older_page threadId=${channelId.orEmpty()} served=0 reason=$reason beforeSeq=$beforeSeq crossGaps=$crossGaps"
            )
            return null
        }
        val reader = durableLastKnownReader ?: return refuse("no_reader")
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId
        if (ownerScopeId == null || ownerScopeId != expectedOwnerScopeId) return refuse("owner_changed")
        if (!routeIdentity.canRenderTimelineCandidates) return refuse("not_renderable")
        val admission =
            durableScopeAdmission(routeIdentity, replyParent)
                ?: return refuse(
                    if (routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) "no_reply_parent" else "not_top_level"
                )
        val allowedScopeKinds = routeIdentity.durableScopeKinds()
        if (channelId == null) return refuse("no_channel")
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return refuse("no_partition")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        val pageSize = threadPageSize(partition.serverId)
        val timelineAnchor =
            ThreadDurableTimelineAnchor.Before(
                beforeSeq = beforeSeq,
                limit = pageSize,
                crossGaps = crossGaps,
            )
        val timelinePage =
            when (admission) {
                DurableScopeAdmission.TopLevel ->
                    reader.readTimeline(partition, channelId, timelineAnchor, touchUserAccess = false)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadTimeline(partition, channelId, admission.parent, timelineAnchor, touchUserAccess = false)
            }
        if (timelinePage != null) {
            if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
            if (timelinePage.scope.scopeKind !in allowedScopeKinds) return refuse("surface_mismatch")
            if (!crossGaps && !timelinePage.insideProvenSegment) return refuse("segment_edge")
            val messages =
                timelinePage.envelopes.mapNotNull { envelope ->
                    envelope.toThreadMessageItem(
                        currentUserId = context.provenance.principalId,
                        diagnostics = diagnosticsLogger,
                    )
                }.sortedByThreadTimeline()
            if (messages.isEmpty()) {
                if (timelinePage.envelopes.isEmpty() && !crossGaps && timelinePage.insideProvenSegment && timelinePage.reachesOldest) {
                    diagnosticsLogger(
                        "thread_durable_older_page threadId=$channelId served=0 reason=proven_reached_oldest " +
                            "beforeSeq=$beforeSeq crossGaps=false scopeKind=${timelinePage.scope.scopeKind}"
                    )
                    return SharedThreadDurableOlderPage(
                        messages = emptyList(),
                        pageSize = pageSize,
                        canLoadMoreBefore = false,
                        reachedOldest = true,
                        gaps = emptyList(),
                        insideProvenSegment = true,
                        scope = timelinePage.scope,
                    )
                }
                return refuse(if (timelinePage.reachesOldest) "exhausted_at_oldest" else "exhausted")
            }
            val canLoadMoreBefore = timelinePage.hasOlderStored || !timelinePage.reachesOldest
            val gaps = if (crossGaps) timelinePage.gaps.map { it.toThreadTimelineGap() } else emptyList()
            diagnosticsLogger(
                "thread_durable_older_page threadId=$channelId served=${messages.size} reason=" +
                    (if (timelinePage.hasOlderStored) "more_stored" else if (timelinePage.reachesOldest) "reached_oldest" else "window_end") +
                    " beforeSeq=$beforeSeq crossGaps=$crossGaps gaps=${gaps.size} canLoadMoreBefore=$canLoadMoreBefore scopeKind=${timelinePage.scope.scopeKind}"
            )
            return SharedThreadDurableOlderPage(
                messages = messages,
                pageSize = pageSize,
                canLoadMoreBefore = canLoadMoreBefore,
                reachedOldest = !canLoadMoreBefore,
                gaps = gaps,
                insideProvenSegment = timelinePage.insideProvenSegment,
                scope = timelinePage.scope,
            )
        }
        if (!crossGaps) return refuse("no_timeline_reader")
        val page =
            when (admission) {
                DurableScopeAdmission.TopLevel -> reader.readBefore(partition, channelId, beforeSeq, pageSize)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadBefore(partition, channelId, admission.parent, beforeSeq, pageSize)
            } ?: return refuse("no_frame")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        if (page.scope.scopeKind !in allowedScopeKinds) return refuse("surface_mismatch")
        val messages =
            page.envelopes.mapNotNull { envelope ->
                envelope.toThreadMessageItem(
                    currentUserId = context.provenance.principalId,
                    diagnostics = diagnosticsLogger,
                )
            }.sortedByThreadTimeline()
        if (messages.isEmpty()) {
            return refuse(if (page.hasLoadedOldest) "exhausted_at_oldest" else "exhausted")
        }
        // More stored below this page ⇒ keep paging from disk; window exhausted but it does not reach
        // the channel's start ⇒ keep paging open for an online retry; otherwise this is the beginning.
        val canLoadMoreBefore = page.hasOlderStored || !page.hasLoadedOldest
        diagnosticsLogger(
            "thread_durable_older_page threadId=$channelId served=${messages.size} reason=" +
                (if (page.hasOlderStored) "more_stored" else if (page.hasLoadedOldest) "reached_oldest" else "window_end") +
                " beforeSeq=$beforeSeq canLoadMoreBefore=$canLoadMoreBefore scopeKind=${page.scope.scopeKind}"
        )
        return SharedThreadDurableOlderPage(
            messages = messages,
            pageSize = pageSize,
            canLoadMoreBefore = canLoadMoreBefore,
            reachedOldest = !canLoadMoreBefore,
        )
    }

    /**
     * Newer page from the durable V2 timeline (Phase 2d): symmetric mirror of [durableOlderPageIfCurrent].
     */
    internal suspend fun durableNewerPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        afterSeq: Long,
        expectedOwnerScopeId: String?,
        replyParent: ThreadDurableReplyParent? = null,
        crossGaps: Boolean = false,
    ): SharedThreadDurableNewerPage? {
        val channelId = routeIdentity.timelineChannelId
        fun refuse(reason: String): SharedThreadDurableNewerPage? {
            diagnosticsLogger(
                "thread_durable_newer_page threadId=${channelId.orEmpty()} served=0 reason=$reason afterSeq=$afterSeq crossGaps=$crossGaps"
            )
            return null
        }
        val reader = durableLastKnownReader ?: return refuse("no_reader")
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId
        if (ownerScopeId == null || ownerScopeId != expectedOwnerScopeId) return refuse("owner_changed")
        if (!routeIdentity.canRenderTimelineCandidates) return refuse("not_renderable")
        val admission =
            durableScopeAdmission(routeIdentity, replyParent)
                ?: return refuse(
                    if (routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) "no_reply_parent" else "not_top_level"
                )
        val allowedScopeKinds = routeIdentity.durableScopeKinds()
        if (channelId == null) return refuse("no_channel")
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return refuse("no_partition")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        val pageSize = threadPageSize(partition.serverId)
        val timelineAnchor =
            ThreadDurableTimelineAnchor.After(
                afterSeq = afterSeq,
                limit = pageSize,
                crossGaps = crossGaps,
            )
        val timelinePage =
            when (admission) {
                DurableScopeAdmission.TopLevel ->
                    reader.readTimeline(partition, channelId, timelineAnchor, touchUserAccess = false)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadTimeline(partition, channelId, admission.parent, timelineAnchor, touchUserAccess = false)
            } ?: return refuse("no_timeline_reader")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        if (timelinePage.scope.scopeKind !in allowedScopeKinds) return refuse("surface_mismatch")
        if (!crossGaps && !timelinePage.insideProvenSegment) return refuse("segment_edge")
        val messages =
            timelinePage.envelopes.mapNotNull { envelope ->
                envelope.toThreadMessageItem(
                    currentUserId = context.provenance.principalId,
                    diagnostics = diagnosticsLogger,
                )
            }.sortedByThreadTimeline()
        if (messages.isEmpty()) {
            // Unlike `reachesOldest` (history start is immutable), `reachesHead` only records that
            // highSeq was head when last verified. Online (`!crossGaps`) always falls through to
            // the network when no newer messages exist locally so server messages posted since then
            // are never hidden.
            return refuse(if (timelinePage.reachesHead) "exhausted_at_head" else "exhausted")
        }
        val canLoadMoreAfter =
            if (crossGaps) {
                timelinePage.hasNewerStored || !timelinePage.reachesHead
            } else {
                true
            }
        val gaps = if (crossGaps) timelinePage.gaps.map { it.toThreadTimelineGap() } else emptyList()
        diagnosticsLogger(
            "thread_durable_newer_page threadId=$channelId served=${messages.size} afterSeq=$afterSeq " +
                "crossGaps=$crossGaps gaps=${gaps.size} canLoadMoreAfter=$canLoadMoreAfter scopeKind=${timelinePage.scope.scopeKind}"
        )
        return SharedThreadDurableNewerPage(
            messages = messages,
            pageSize = pageSize,
            canLoadMoreAfter = canLoadMoreAfter,
            reachedHead = if (crossGaps) !canLoadMoreAfter else false,
            gaps = gaps,
            insideProvenSegment = timelinePage.insideProvenSegment,
            scope = timelinePage.scope,
        )
    }

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

    /**
     * Tenny rule 2: a background validation of [payload]'s conversation proved the account lost
     * access (see [isConversationAccessLost]). Evicts the in-memory timeline under the requesting
     * account's key and, when wired, the conversation's durable V2 window. The account fence is
     * the same as a write's: another account's cache is never touched.
     */
    internal suspend fun evictLostConversationAccess(
        payload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean {
        val threadId = payload.resolveThreadRouteIdentity().timelineChannelId ?: return false
        val ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId ?: return false
        val sameAccount =
            synchronized(replyCacheProvenanceLock) {
                replyCacheProvenance.value?.isSameTimelineCacheAccount(expectedProvenance) == true
            }
        if (!sameAccount) return false
        payloadCache.evictConversation(expectedProvenance.serverId, threadId, ownerScopeId)
        val evictor = durableAccessEvictor ?: return true
        val partition =
            expectedProvenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return true
        try {
            evictor.evictConversation(partition, threadId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            diagnosticsLogger(
                "thread_access_lost_durable_evict_failed server=${expectedProvenance.serverId} " +
                    "threadId=$threadId reason=${failure.message ?: failure::class.simpleName.orEmpty()}"
            )
        }
        return true
    }

    internal fun invalidateCachedPayload(
        payload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean = provenanceOwner.invalidateCachedPayload(payload, expectedProvenance)

    suspend fun withCachedReplyParentCandidate(basePayload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        val context = captureReplyCacheProducerContext() ?: return basePayload
        return withCachedReplyParentCandidate(basePayload, context)
    }

    internal fun withCachedReplyParentCandidate(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext
    ): RaftThreadScreenPayload {
        if (basePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return basePayload
        if (basePayload.originMessage != null || basePayload.originPreview?.isNotBlank() == true) return basePayload
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return basePayload
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return basePayload
        if (!isCurrentReplyCacheProvenance(context.provenance)) return basePayload
        val session = context.session
        val messageStoreScopeId = context.provenance.messageStoreScopeId ?: return basePayload
        val parent =
            getCachedReplyParentMessage(
                messageStoreScopeId = messageStoreScopeId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return basePayload
        return basePayload.copy(
            originMessage = parent.toRaftThreadMessageItem(session.user.id),
            originParentMessageId = parentMessageId,
            originStatus = "loaded",
            originErrorCode = null
        )
    }

    suspend fun resolveReplyOrigin(
        basePayload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance? = null
    ): SharedThreadOriginResolution {
        if (basePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) {
            return SharedThreadOriginResolution.Missing
        }
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: return SharedThreadOriginResolution.Error("thread_parent_channel_missing")
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return SharedThreadOriginResolution.Error("thread_parent_message_missing")
        val replyResolutionScope =
            readReplyResolutionScope()
                ?: return SharedThreadOriginResolution.Error("thread_parent_context_session_missing")
        if (expectedProvenance != null && replyResolutionScope.provenance != expectedProvenance) {
            return SharedThreadOriginResolution.Error("thread_reply_cache_provenance_stale")
        }
        val session = replyResolutionScope.session
        val selected = when (
            val resolution = resolveReplyParentMessageFromStore(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                expectedProvenance = replyResolutionScope.provenance
            )
        ) {
            is SharedThreadMessageResolution.Loaded ->
                SharedThreadOriginResolution.Loaded(
                    resolution.message.toRaftThreadMessageItem(session.user.id)
                )
            SharedThreadMessageResolution.Missing -> SharedThreadOriginResolution.Missing
            is SharedThreadMessageResolution.Error -> SharedThreadOriginResolution.Error(resolution.code, resolution.hiddenTarget)
        }
        return if (isCurrentReplyCacheProvenance(replyResolutionScope.provenance)) {
            selected
        } else {
            SharedThreadOriginResolution.Error("thread_reply_cache_provenance_stale")
        }
    }

    fun rememberReplyParentCandidate(
        provenance: ReplyCacheProvenance,
        serverId: String?,
        parentChannelId: String?,
        parentMessage: RaftThreadMessageItem,
        threadChannelId: String?,
        /**
         * The reply count the opener shows for [parentMessage]. A timeline row carries no reply facts
         * ([withoutThreadReplyMetadata]), so without it the remembered parent says "0 replies" and the
         * thread counts only the replies it has loaded. A candidate: the thread's own load replaces it.
         */
        replyCount: Int = 0
    ): Boolean {
        val channelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return false
        if (parentMessage.id.isBlank()) return false
        val exactServerId = serverId?.takeIf { it.isNotBlank() } ?: provenance.serverId.takeIf { it.isNotBlank() }
        if (exactServerId == null || exactServerId != provenance.serverId) return false
        val projectedParent =
            if (parentMessage.needsReplyOriginContextHydration()) {
                null
            } else {
                parentMessage.toMessage(
                    threadId = ThreadId(channelId.value),
                    channelId = channelId,
                    diagnostics = diagnosticsLogger,
                ) ?: return false
            }
        val accepted =
            synchronized(replyCacheProvenanceLock) {
                if (replyCacheProvenance.value != provenance) return@synchronized false
                threadChannelId?.takeIf { it.isNotBlank() }?.let { resolvedThreadChannelId ->
                    cacheReplyThreadResolution(
                        namespaceId = provenance.namespaceId,
                        parentChannelId = channelId.value,
                        parentMessageId = parentMessage.id,
                        threadChannelId = resolvedThreadChannelId
                    )
                }
                projectedParent?.let { parent ->
                    val scopeId =
                        provenance.messageStoreScopeId
                            ?: provenance.serverId.takeIf { messageStore == null && it.isNotBlank() }
                            ?: return@synchronized false
                    val knownReplyCount =
                        maxOf(
                            parent.replyCount,
                            replyCount,
                            messageResolver.getScoped(scopeId, channelId, parent.id)?.replyCount ?: 0
                        )
                    messageResolver.upsertScoped(
                        scopeId = scopeId,
                        channelId = channelId,
                        message = if (knownReplyCount == parent.replyCount) parent else parent.copy(replyCount = knownReplyCount)
                    )
                }
                true
            }
        if (!accepted) return false
        diagnosticsLogger(
            "thread_parent_context_store_seed parentChannelId=${channelId.value} " +
                "parentMessageId=${parentMessage.id} threadChannelId=${threadChannelId.orEmpty()}"
        )
        return true
    }

    /**
     * A [ThreadUpdated] reached the open page: the remembered parent (what the next open of its
     * thread resolves its origin from) follows the server's count too. Events can arrive out of
     * order, so the count only grows here, as the reply-count facts do. A parent that is not
     * remembered stays unknown; its thread reads it when opened.
     */
    fun rememberReplyParentReplyCount(parentChannelId: String?, parentMessageId: String, replyCount: Int) {
        val channelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return
        val messageId = parentMessageId.takeIf { it.isNotBlank() }?.let(::MessageId) ?: return
        synchronized(replyCacheProvenanceLock) {
            val provenance = replyCacheProvenance.value ?: return@synchronized
            val scopeId =
                provenance.messageStoreScopeId
                    ?: provenance.serverId.takeIf { messageStore == null && it.isNotBlank() }
                    ?: return@synchronized
            val remembered = messageResolver.getScoped(scopeId, channelId, messageId) ?: return@synchronized
            if (remembered.replyCount < replyCount) {
                messageResolver.upsertScoped(scopeId, channelId, remembered.copy(replyCount = replyCount))
            }
        }
    }

    suspend fun withConversationAccessFallback(basePayload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        val session = readSession() ?: return basePayload
        return basePayload.withConversationAccessFallback(resolveConversationAccessFallback(basePayload, session))
    }

    suspend fun withKnownMarkdownChannelNames(payload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        val session = readSession() ?: return payload
        return payload.withKnownMarkdownChannelNames(session)
    }

    suspend fun homeConversationContextResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution {
        val session = readSession() ?: return ThreadHomeContextResolution(payload = payload)
        val home = currentHomeSnapshot(session) ?: return ThreadHomeContextResolution(payload = payload)
        val accessFallback = resolveConversationAccessFallback(payload, home)
        return ThreadHomeContextResolution(
            payload =
                payload
                    .withConversationAccessFallback(accessFallback)
                    .withKnownMarkdownChannelNames(home),
            accessCandidate = accessFallback
        )
    }

    /**
     * Reply write authority belongs to the exact parent Channel/DM. Resolve it independently from
     * the full Home aggregate so auxiliary unread enrichment cannot consume the initial-access
     * timeout after the canonical conversation list is already available.
     */
    suspend fun initialConversationAccessResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution =
        when {
            payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread -> replyParentConversationAccessResolution(payload)
            payload.isTaskConversationSurface() -> taskConversationAccessResolution(payload)
            else -> homeConversationContextResolution(payload)
        }

    /**
     * Task v2: a task conversation has no Home row of its own. Its write authority is its task
     * channel's (`withTaskConversationContext`: the parent's joined + canSend), so the parent's Home
     * row is the same initial candidate a Channel route reads from its own row. Without it the
     * composer waited for messages/context -> channels/:id -> channels/:parent in series (Hands
     * fa151b10: 2.4-4.8 s after the messages showed). Only a joined + canSend parent is used; the
     * canonical load still wins and a later NoPermission still closes access.
     */
    private suspend fun taskConversationAccessResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution {
        val base = homeConversationContextResolution(payload)
        if (base.accessCandidate != null) return base
        val conversationId =
            listOfNotNull(payload.streamChannelId, payload.sourceThreadId)
                .firstOrNull { it.isNotBlank() }
                ?: return base
        val ref = (loadTaskConversationRef(ChannelId(conversationId)) as? DataResult.Content)?.data
        if (ref == null || ref.conversationChannelId.value != conversationId) return base
        val session = readSession() ?: return base
        val home = currentHomeSnapshot(session) ?: return base
        val parent =
            home.toSharedConversationDirectoryState()
                .resolve(ref.parentChannelId.value)
                ?.toConversationAccessCandidate()
        val writable = parent != null && parent.joined && parent.permissions.canSend && !parent.archived
        diagnosticsLogger(
            "thread_initial_task_access conversationId=$conversationId " +
                "parentChannelId=${ref.parentChannelId.value} parentRow=${parent != null} writable=$writable"
        )
        if (parent == null || !writable) return base
        return base.copy(
            accessCandidate =
                ConversationAccessCandidate(
                    ids = setOf(conversationId),
                    conversationKind = ConversationKind.Task,
                    surfaceKind = payload.surfaceKind,
                    joined = true,
                    archived = false,
                    permissions = parent.permissions.copy(canCreateTask = false),
                )
        )
    }

    private suspend fun replyParentConversationAccessResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution {
        val (parentId, parentKind) =
            payload.replyParentAccessIdentityOrNull()
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )
        val resolutionScope =
            readReplyResolutionScope()
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )
        val serverId =
            resolutionScope.provenance.serverId
                .takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )

        resolveHomeSnapshotLookup(serverId)
            ?.takeIf { lookup ->
                lookup.snapshot.server.id == serverId &&
                    lookup.snapshot.currentMember.id.value == resolutionScope.provenance.principalId
            }
            ?.let { lookup ->
                val home = lookup.snapshot
                val candidate =
                    payload.exactReplyParentAccessCandidate(
                        serverId = serverId,
                        parentId = parentId,
                        parentKind = parentKind,
                        conversations = home.channels + home.directMessages,
                    ) ?: return@let
                // A non-authoritative Home snapshot (durable identity cache or refresh-pending)
                // may grant fast positive write access, but must never short-circuit a negative
                // (unjoined or non-writable) parent row and lock the thread into Join without
                // checking the network directory.
                if (!lookup.authoritative && (!candidate.joined || !candidate.permissions.canSend)) {
                    return@let
                }
                val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
                return ThreadHomeContextResolution(
                    payload = payload.withKnownMarkdownChannelNames(home),
                    accessCandidate = candidate,
                    ownerCurrent = ownerCurrent,
                )
            }

        // Agent Lounge v0: a Lounge is in no Home list and `GET /channels` leaves it out, so the
        // directory can never answer for a thread in one. When this client knows the parent is a
        // Lounge, decide as the web does (useThreadParentChannel `parentJoined`): any member may
        // reply unless it is archived or the open answer said `canPost: false`; no Join step.
        agentLoungeReplyParentAccessCandidate(serverId, parentId, parentKind)?.let { candidate ->
            val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
            diagnosticsLogger(
                "thread_initial_parent_access source=agent_lounge serverId=${serverId.value} " +
                    "parentId=$parentId canSend=${candidate.permissions.canSend} ownerCurrent=$ownerCurrent"
            )
            return ThreadHomeContextResolution(
                payload = payload,
                accessCandidate = candidate,
                ownerCurrent = ownerCurrent,
                requiresBackgroundHomeContextHydration = true,
            )
        }

        val repository = channelRepository
        val conversations: List<HomeConversationRow> =
            if (repository == null) {
                emptyList()
            } else {
                try {
                    when (parentKind) {
                        ConversationKind.Channel,
                        ConversationKind.Joint ->
                            (repository.getChannels(serverId) as? DataResult.Content)
                                ?.data
                                ?.takeIf { snapshot -> snapshot.server.id == serverId }
                                ?.channels
                                .orEmpty()
                        ConversationKind.DirectMessage ->
                            (repository.getDirectMessages(serverId) as? DataResult.Content)
                                ?.data
                                ?.takeIf { snapshot -> snapshot.server.id == serverId }
                                ?.directMessages
                                .orEmpty()
                        ConversationKind.Thread,
                        ConversationKind.Inbox,
                        ConversationKind.System,
                        ConversationKind.Task -> emptyList()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
        val candidate =
            payload.exactReplyParentAccessCandidate(
                serverId = serverId,
                parentId = parentId,
                parentKind = parentKind,
                conversations = conversations,
            )
        diagnosticsLogger(
            "thread_initial_parent_access source=conversation_list serverId=${serverId.value} " +
                "parentId=$parentId parentKind=${parentKind.name} candidate=${candidate != null} " +
                "ownerCurrent=$ownerCurrent"
        )
        return ThreadHomeContextResolution(
            payload = payload,
            accessCandidate = candidate,
            ownerCurrent = ownerCurrent,
            requiresBackgroundHomeContextHydration = true,
        )
    }

    private fun agentLoungeReplyParentAccessCandidate(
        serverId: ServerId,
        parentId: String,
        parentKind: ConversationKind,
    ): ConversationAccessCandidate? {
        if (parentKind != ConversationKind.Channel) return null
        val lounge = RaftAgentLounges.lounge(serverId.value, parentId) ?: return null
        return agentLoungeParentAccessCandidate(parentId, postable = lounge.postable)
    }

    /**
     * Bounded fallback for a reply thread whose parent write authority is still undetermined
     * after [SharedThreadStore]'s fallback window: read the parent conversation itself
     * (`GET /channels/:id`, web `ensureChannel`) instead of waiting on a directory row that may
     * never come (a Lounge is in no list). A Lounge parent is decided by the Lounge rule (any
     * member may post unless archived / `canPost: false`); any other parent keeps the ordinary
     * joined + canSend check.
     */
    suspend fun replyParentAccessFallback(payload: RaftThreadScreenPayload): ReplyParentAccessFallbackResult {
        val (parentId, parentKind) =
            payload.replyParentAccessIdentityOrNull()
                ?: return ReplyParentAccessFallbackResult.Unavailable("parent_identity_missing")
        val repository =
            conversationRepository
                ?: return ReplyParentAccessFallbackResult.Unavailable("conversation_repository_missing")
        val resolutionScope =
            readReplyResolutionScope()
                ?: return ReplyParentAccessFallbackResult.Unavailable("resolution_scope_missing")
        val result =
            try {
                repository.getConversationDetail(ChannelId(parentId), DataRequestPriority.Foreground)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                return ReplyParentAccessFallbackResult.Failed(
                    "exception_${throwable::class.simpleName.orEmpty()}"
                )
            }
        if (!replyAccessResolutionOwnerStillCurrent(resolutionScope)) {
            return ReplyParentAccessFallbackResult.Unavailable("owner_changed")
        }
        return when (result) {
            is DataResult.Content -> {
                val detail = result.data
                val detailId = detail.channelId?.value ?: detail.threadId.value
                if (detailId != parentId) {
                    return ReplyParentAccessFallbackResult.Failed("parent_id_mismatch")
                }
                if (!detail.loungeAgentId.isNullOrBlank()) {
                    if (parentKind != ConversationKind.Channel) {
                        return ReplyParentAccessFallbackResult.Failed("parent_kind_mismatch")
                    }
                    val serverId = resolutionScope.provenance.serverId
                    val known = RaftAgentLounges.lounge(serverId, parentId)
                    val postable = !detail.archived && known?.canPost != false
                    ReplyParentAccessFallbackResult.Resolved(
                        candidate = agentLoungeParentAccessCandidate(parentId, postable = postable),
                        lounge = true,
                    )
                } else {
                    if (detail.kind != parentKind) {
                        return ReplyParentAccessFallbackResult.Failed("parent_kind_mismatch")
                    }
                    ReplyParentAccessFallbackResult.Resolved(
                        candidate =
                            ConversationAccessCandidate(
                                ids = setOf(parentId),
                                conversationKind = parentKind,
                                surfaceKind = "channel",
                                // Membership only (web useThreadParentChannel `parentJoined =
                                // parentChannel.joined`): an archived parent the viewer is a member of
                                // must not read as "not joined" and offer a Join (fb 93123592);
                                // `archived` still closes sending below.
                                joined = detail.joined,
                                archived = detail.archived,
                                permissions = detail.permissions,
                            ),
                        lounge = false,
                    )
                }
            }
            is DataResult.NoPermission -> ReplyParentAccessFallbackResult.Failed("no_permission")
            is DataResult.Empty -> ReplyParentAccessFallbackResult.Failed("empty_${result.reason.name}")
            is DataResult.Error -> ReplyParentAccessFallbackResult.Failed(result.error.code)
        }
    }

    private suspend fun replyAccessResolutionOwnerStillCurrent(
        expected: ReplyResolutionScope
    ): Boolean {
        val current = readReplyResolutionScope() ?: return false
        // A transparent token rotation (same binding/server/selection) keeps the owner; any
        // binding, principal, or server change still fails closed (task #2606).
        return current.provenance.isCurrentOrSuccessorForLocalSend(expected.provenance) &&
            isCurrentReplyCacheProvenanceForLocalSend(expected.provenance)
    }

    private fun RaftThreadScreenPayload.exactReplyParentAccessCandidate(
        serverId: ServerId,
        parentId: String,
        parentKind: ConversationKind,
        conversations: List<HomeConversationRow>,
    ): ConversationAccessCandidate? =
        conversations
            .asSequence()
            .mapNotNull { conversation ->
                conversation.toSharedConversationDirectoryProjection(serverId)
            }
            .firstOrNull { projection ->
                projection.canonicalChannelId == parentId &&
                    projection.conversationKind == parentKind
            }
            ?.toConversationAccessCandidate()
            ?.takeIf(::acceptsInitialConversationAccessCandidate)

    suspend fun withHomeConversationContext(payload: RaftThreadScreenPayload): RaftThreadScreenPayload =
        homeConversationContextResolution(payload).payload

    private fun cachedReplyThreadStreamId(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String
    ): String? {
        namespaceId ?: return null
        val key = ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)
        return replyThreadResolveCache[key]
            ?: messageStore?.getReplyThreadStream(namespaceId, parentChannelId, parentMessageId)
    }

    private fun RaftThreadScreenPayload.matchesCachedReplyThreadStreamResolution(
        namespaceId: String?
    ): Boolean {
        val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
        val parentMessage =
            parentMessageId?.takeIf { it.isNotBlank() }
                ?: originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return false
        val routeStream =
            streamChannelId?.takeIf { it.isNotBlank() }
                ?: sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return false
        return cachedReplyThreadStreamId(namespaceId, parentChannel, parentMessage) == routeStream
    }

    /**
     * A thread ref's target from what the device already holds: the page's own messages and
     * origin, the in-memory thread cache, the loaded timelines of cached conversations (their
     * messages carry the thread summaries), reply-thread resolutions already made, and the parent
     * messages the message store holds. Candidates from every source are pooled and deduped by
     * full message id: an 8-char prefix is a local hit only when exactly one message matches across
     * all of them. None, or two different messages, is a miss and fetches.
     */
    private fun localMarkdownThreadRefTarget(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        parentChannelId: String,
        threadShortId: String
    ): MarkdownThreadRefTarget? {
        val shortId = threadShortId.trim().lowercase()
        if (parentChannelId.isBlank() || shortId.isBlank()) return null
        val candidates = mutableListOf<MarkdownThreadRefTarget>()
        candidates += currentPayload.markdownThreadRefCandidates(parentChannelId, shortId)
        provenance.threadPayloadOwnerScopeId?.let { ownerScopeId ->
            candidates += cachedMarkdownThreadRefCandidates(provenance.serverId, ownerScopeId, parentChannelId, shortId)
            candidates +=
                payloadCache.values(provenance.serverId, ownerScopeId)
                    .flatMap { payload -> payload.markdownThreadRefCandidates(parentChannelId, shortId) }
        }
        provenance.namespaceId?.takeIf { it.isNotBlank() }?.let { namespaceId ->
            val resolutions =
                synchronized(replyCacheProvenanceLock) {
                    replyThreadResolveCache.entries
                        .filter { (key, _) ->
                            key.namespaceId == namespaceId &&
                                key.parentChannelId == parentChannelId &&
                                key.parentMessageId.lowercase().startsWith(shortId)
                        }
                        .map { (key, threadChannelId) -> key.parentMessageId to threadChannelId }
                } + messageStore?.findReplyThreadStreams(namespaceId, parentChannelId, shortId).orEmpty()
            candidates +=
                resolutions.map { (parentMessageId, threadChannelId) ->
                    MarkdownThreadRefTarget(parentMessageId = parentMessageId, threadChannelId = threadChannelId)
                }
        }
        provenance.messageStoreScopeId?.takeIf { it.isNotBlank() }?.let { scopeId ->
            candidates +=
                messageResolver.findScopedByIdPrefix(scopeId, ChannelId(parentChannelId), shortId).map { message ->
                    MarkdownThreadRefTarget(
                        parentMessageId = message.id.value,
                        threadChannelId = message.threadChannelId?.value?.takeIf { it.isNotBlank() }
                    )
                }
        }
        return candidates.singleMarkdownThreadRefTarget()
    }

    /**
     * A message of [parentChannelId]'s loaded timelines whose id starts with [messageShortId]: the
     * page's own and every cached timeline of that conversation, pooled. One distinct message only.
     */
    private fun localMarkdownTimelineRefTarget(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        parentChannelId: String,
        messageShortId: String
    ): MarkdownThreadRefTarget? {
        val candidates = currentPayload.markdownThreadRefCandidates(parentChannelId, messageShortId).toMutableList()
        provenance.threadPayloadOwnerScopeId?.let { ownerScopeId ->
            candidates +=
                payloadCache.values(provenance.serverId, ownerScopeId)
                    .flatMap { payload -> payload.markdownThreadRefCandidates(parentChannelId, messageShortId) }
        }
        return candidates.singleMarkdownThreadRefTarget()
    }

    private fun cachedMarkdownThreadRefCandidates(
        serverId: String,
        ownerScopeId: String,
        parentChannelId: String,
        threadShortId: String
    ): List<MarkdownThreadRefTarget> {
        val shortId = threadShortId.trim().lowercase()
        if (parentChannelId.isBlank() || shortId.isBlank()) return emptyList()
        return payloadCache.values(serverId, ownerScopeId)
            .mapNotNull { payload ->
                val parentMessageId =
                    payload.parentMessageId?.takeIf { it.isNotBlank() }
                        ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                if (
                    payload.parentChannelId != parentChannelId ||
                    !parentMessageId.lowercase().startsWith(shortId)
                ) {
                    return@mapNotNull null
                }
                MarkdownThreadRefTarget(
                    parentMessageId = parentMessageId,
                    threadChannelId =
                        payload.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                )
            }
    }

    /**
     * A reference's target page could not be read (refused or gone): every kept resolution that
     * points at it is forgotten, so the next tap on that reference asks the server again.
     */
    internal fun forgetReferenceTargetsOf(payload: RaftThreadScreenPayload): Int {
        val ids =
            listOfNotNull(
                payload.parentMessageId,
                payload.originParentMessageId,
                payload.streamChannelId,
                payload.sourceThreadId,
                payload.focusedMessageId
            ).filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return 0
        return referenceResolver.forgetTargets { _, target ->
            target is MarkdownThreadRefTarget &&
                (target.parentMessageId in ids || target.threadChannelId in ids || target.focusedMessageId in ids)
        }
    }

    /** The conversation directory a reference resolves names in: local, unless it has to be fetched. */
    private suspend fun referenceHomeSnapshot(session: StoredAuthSession, actionId: String?): HomeSnapshot? {
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
        resolveHomeSnapshotLookup(serverId)?.snapshot?.let { return it }
        // No home snapshot on the device: reading it is part of the reference's fetch.
        return referenceResolver.fetchPhase(actionId) { currentHomeSnapshot(session) }
    }

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != provenance) return@synchronized false
            cacheReplyThreadResolution(
                provenance.namespaceId,
                parentChannelId,
                parentMessageId,
                threadChannelId
            )
            true
        }

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        payload: RaftThreadScreenPayload
    ): Boolean {
        val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return true
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return true
        val threadChannelId =
            payload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return true
        return cacheReplyThreadResolution(
            provenance,
            parentChannelId,
            parentMessageId,
            threadChannelId
        )
    }

    private fun cacheReplyThreadResolution(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ) {
        namespaceId ?: return
        if (parentChannelId.isBlank() || parentMessageId.isBlank() || threadChannelId.isBlank()) return
        if (parentChannelId == threadChannelId) return
        replyThreadResolveCache[ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)] = threadChannelId
        messageStore?.upsertReplyThreadStream(namespaceId, parentChannelId, parentMessageId, threadChannelId)
    }

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

    /**
     * The unread-thread pill's prewarm (artin): [prewarmReplyThread] for a reply thread of the
     * current server, whose id the caller (a channel page) does not hold itself.
     */
    suspend fun prewarmReplyThreadOnCurrentServer(
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
    ): DataResult<Unit> {
        val serverId =
            readReplyResolutionScope()?.provenance?.serverId?.takeIf { it.isNotBlank() }
                ?: return authSessionRequiredResult()
        return prewarmReplyThread(
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = serverId,
                        parentChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        threadChannelId = threadChannelId,
                    )
            )
        )
    }

    suspend fun prewarmReplyThread(
        marker: ActivityReplyThreadPrewarmMarker
    ): DataResult<Unit> {
        val key = marker.key
        if (key.isConversation) return prewarmActivityConversation(key)
        if (
            key.serverId.isBlank() ||
            key.parentChannelId.isBlank() ||
            key.parentMessageId.isBlank() ||
            key.threadChannelId.isBlank()
        ) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_identity_missing", retryable = false))
        }
        val replyResolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = replyResolutionScope.session
        val payloadOwnerScopeId =
            replyResolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val selection = selectedServerStore
            ?: return DataResult.Error(ScreenError(code = "thread_prewarm_selection_unavailable", retryable = false))
        if (!selection.isSelected(key.serverId)) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_server_changed", retryable = false))
        }
        val basePayload =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = key.threadChannelId,
                streamChannelId = key.threadChannelId,
                parentChannelId = key.parentChannelId,
                parentMessageId = key.parentMessageId,
                originParentMessageId = key.parentMessageId
            )
        // Task #14b: the reply page this prewarm fetched, kept for the durable write below, and the thread
        // scope's commit generation read BEFORE the fetch (a write landing after it makes the page stale).
        var fetchedPage: Pair<String?, RawMessagePage?>? = null
        val durableCommitGeneration =
            replyResolutionScope.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?.let { partition -> enteredScopeSnapshotLoader?.replyThreadCommitGeneration(partition, key.threadChannelId) }
        val durableHeadEpoch = durableHeadEpochOrNull(replyResolutionScope.provenance)
        // Message storage Phase 2b: the write revision before the fetch too — a realtime loose write bumps no commit
        // generation, but its revision is newer than the page, so the page never overwrites it.
        val durableRevAtRequest =
            replyResolutionScope.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?.let { partition -> enteredScopeSnapshotLoader?.currentLocalRev(partition) }
        val result =
            payloadCache.prewarm(
                serverId = key.serverId,
                threadId = key.threadChannelId,
                ownerScopeId = payloadOwnerScopeId
            ) {
                val fetched =
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = basePayload,
                        requestPriority = DataRequestPriority.Background,
                        replyResolutionScope = replyResolutionScope,
                        onRawReplyPage =
                            if (durableCommitGeneration == null) {
                                null
                            } else {
                                { threadChannelId, page -> fetchedPage = threadChannelId to page }
                            }
                    )
                val payload =
                    when (fetched) {
                        is DataResult.Content -> fetched.data
                        is DataResult.Empty ->
                            if (fetched.reason == EmptyReason.NoMessages) {
                                basePayload.toEmptyReplyThreadPayload()
                            } else {
                                return@prewarm fetched
                            }
                        is DataResult.Error -> return@prewarm fetched
                        is DataResult.NoPermission -> return@prewarm fetched
                    }
                DataResult.Content(payload)
            }
        (result as? DataResult.Content)?.data?.let { payload ->
            if (!cacheReplyThreadResolution(replyResolutionScope.provenance, payload)) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            fetchedPage?.let { (threadChannelId, page) ->
                persistReplyThreadPrewarmPageIfCurrent(
                    basePayload = basePayload,
                    context = ReplyCacheProducerContext(session, replyResolutionScope.provenance),
                    resolvedThreadChannelId = threadChannelId,
                    rawPage = page,
                    expectedCommitGeneration = durableCommitGeneration ?: return@let,
                    headEpochAtRequest = durableHeadEpoch,
                    revAtRequest = durableRevAtRequest,
                )
            }
        }
        return when (result) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * artin #111: warm a channel / DM row of the Activity viewport. Fetches, at Background priority, the
     * exact focused `messages/context` (+ `channels/:id`) the Activity open of that row makes, into the
     * same app-scope payload cache and single-flight prewarm slot the open reads
     * ([SharedThreadPayloadStore.prewarm] / [SharedThreadPayloadStore.joinOrConsumePrewarmWithOutcome]).
     * Skips the request when the cache already holds this conversation's current content including the
     * anchor. The open still validates the warmed payload against its own route before using it.
     */
    private suspend fun prewarmActivityConversation(key: ActivityReplyThreadPrewarmKey): DataResult<Unit> {
        fun identityMissing() =
            DataResult.Error(ScreenError(code = "thread_prewarm_identity_missing", retryable = false))
        val surfaceKind =
            key.conversationSurfaceKind
                ?.takeIf { it == RaftThreadSurfaceKind.Channel || it == RaftThreadSurfaceKind.DirectMessage }
                ?: return identityMissing()
        val anchorMessageId = key.anchorMessageId?.takeIf { it.isNotBlank() } ?: return identityMissing()
        if (key.serverId.isBlank() || key.threadChannelId.isBlank()) return identityMissing()
        val resolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val payloadOwnerScopeId =
            resolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val selection = selectedServerStore
            ?: return DataResult.Error(ScreenError(code = "thread_prewarm_selection_unavailable", retryable = false))
        if (!selection.isSelected(key.serverId) || resolutionScope.provenance.serverId != key.serverId) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_server_changed", retryable = false))
        }
        val cached = cachedPayloadIfCurrent(key.threadChannelId, resolutionScope.provenance)
        if (cached != null && cached.messages.any { message -> message.id == anchorMessageId }) {
            diagnosticsLogger(
                "thread_conversation_prewarm_skip reason=cached_current threadId=${key.threadChannelId} " +
                    "surface=$surfaceKind anchor=$anchorMessageId"
            )
            return DataResult.Content(Unit)
        }
        val isDirectMessage = surfaceKind == RaftThreadSurfaceKind.DirectMessage
        // Mirrors the route payload of a non-task channel / DM open (ThreadRoutePayload), so the warmed
        // payload carries the same route-derived header flags as the open's own answer would.
        val basePayload =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = surfaceKind,
                channelKind = surfaceKind,
                sourceThreadId = key.threadChannelId,
                streamChannelId = key.threadChannelId,
                memberScopedMentions = isDirectMessage,
                focusedMessageId = anchorMessageId,
                selectedTab = "chat",
                navigationLabel = "Back",
                showModeTabs = true,
                showMembersAction = !isDirectMessage,
                showSettingsAction = !isDirectMessage,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
            )
        diagnosticsLogger(
            "thread_conversation_prewarm_start threadId=${key.threadChannelId} surface=$surfaceKind " +
                "anchor=$anchorMessageId priority=background"
        )
        val result =
            payloadCache.prewarm(
                serverId = key.serverId,
                threadId = key.threadChannelId,
                ownerScopeId = payloadOwnerScopeId
            ) {
                if (!isCurrentReplyCacheProvenance(resolutionScope.provenance)) {
                    return@prewarm staleReplyCacheProvenanceResult()
                }
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = ThreadId(key.threadChannelId),
                    accessFallback = null,
                    replyResolutionScope = resolutionScope,
                    requestPriority = DataRequestPriority.Background,
                )
                    // The context answered for another conversation (or not at all): nothing to warm.
                    ?: DataResult.Error(ScreenError(code = "thread_prewarm_context_mismatch", retryable = false))
            }
        diagnosticsLogger(
            "thread_conversation_prewarm_result threadId=${key.threadChannelId} surface=$surfaceKind " +
                "result=${result.threadDiagnosticsResult()}"
        )
        (result as? DataResult.Content)?.data?.let { payload ->
            seedThreadAuthorProfiles(session.currentServerId, payload, source = "conversation_prewarm")
        }
        return when (result) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * Task #14b ("every authoritative page is persisted"): hand the reply page an Activity prewarm just
     * fetched to the durable V2 writer as the thread scope's window, so opening the thread later renders
     * `thread_durable_last_known` even after the in-memory prewarm was invalidated or the process restarted.
     * Same admission as [persistEnteredPageIfCurrent] for the prewarm's reply route (renderable reply
     * route with its typed parent, an owning partition, current provenance); additionally the page must be
     * the one the server resolved for exactly this thread. The parent named here is only the route's claim —
     * the writer has the server confirm it. Every refusal on this side logs one `canonical_v2_prewarm_page`
     * line; the writer logs its own.
     */
    private suspend fun persistReplyThreadPrewarmPageIfCurrent(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
        resolvedThreadChannelId: String?,
        rawPage: RawMessagePage?,
        expectedCommitGeneration: Long,
        headEpochAtRequest: Long?,
        revAtRequest: Long?,
    ) {
        val loader = enteredScopeSnapshotLoader ?: return
        val threadChannelId = basePayload.streamChannelId.orEmpty()
        fun skip(reason: String) =
            diagnosticsLogger(
                "canonical_v2_prewarm_page server=${context.provenance.serverId} scope=$threadChannelId " +
                    "scopeKind=thread outcome=skipped reason=$reason facts=0"
            )
        if (rawPage == null) return skip("no_raw_page")
        if (resolvedThreadChannelId != threadChannelId) return skip("thread_mismatch")
        val routeIdentity = basePayload.resolveThreadRouteIdentity()
        if (!routeIdentity.canRenderTimelineCandidates || routeIdentity.timelineChannelId != threadChannelId) {
            return skip("not_renderable")
        }
        val replyParent =
            basePayload.durableReplyParentOrNull()
                ?: return skip("no_reply_parent")
        val admission =
            durableScopeAdmission(routeIdentity, replyParent) as? DurableScopeAdmission.ReplyThread
                ?: return skip("no_reply_parent")
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return skip("no_partition")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return skip("identity_changed")
        loader.persistReplyThreadPrewarmPage(
            partition = partition,
            threadChannelId = threadChannelId,
            replyParent = admission.parent,
            rawMessages = rawPage.messages,
            requestedLimit = rawPage.requestedLimit,
            expectedCommitGeneration = expectedCommitGeneration,
            messageWindow = rawPage.messageWindow,
            historyLimited = rawPage.historyLimited,
            requestHeadEpoch = headEpochAtRequest,
            revAtRequest = revAtRequest,
        ) { isCurrentReplyCacheProvenance(context.provenance) }
    }

    private suspend fun resolveReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> {
        val repository = conversationRepository ?: return DataResult.Content(basePayload)
        if (!basePayload.needsReplyThreadStreamResolution()) {
            return DataResult.Content(basePayload)
        }
        return createOrOpenReplyThreadStream(basePayload, repository, replyResolutionScope)
    }

    private suspend fun createOrOpenReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        repository: ConversationRepository? = conversationRepository,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> {
        val resolvedRepository =
            repository
                ?: return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
        val replyResolutionNamespaceId = replyResolutionScope.namespaceId
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        cachedReplyThreadStreamId(replyResolutionNamespaceId, parentChannelId, parentMessageId)?.let { threadChannelId ->
            return DataResult.Content(
                basePayload.withResolvedReplyThreadAction(
                    ThreadActionResult(
                        threadChannelId = ChannelId(threadChannelId),
                        changed = false,
                        parentMessageId = MessageId(parentMessageId),
                        replyCount = basePayload.originMessage?.replyCount ?: 0
                    )
                )
            )
        }
        val actionResult =
            try {
                resolvedRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessageId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true, traceId = null))
            }
        return when (actionResult) {
            is DataResult.Content -> {
                if (!cacheReplyThreadResolution(
                        replyResolutionScope.provenance,
                        parentChannelId,
                        parentMessageId,
                        actionResult.data.threadChannelId.value
                    )
                ) {
                    return DataResult.Error(
                        ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                    )
                }
                DataResult.Content(basePayload.withResolvedReplyThreadAction(actionResult.data))
            }
            is DataResult.Empty -> actionResult
            is DataResult.Error -> actionResult
            is DataResult.NoPermission -> actionResult
        }
    }

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

    /**
     * Linearizes the matching Store publication and reply-count fact write under
     * the same exact owner. `null` means owner rejection; `false` means the Store
     * declined a stale route; `true` means the owner-bound commit was admitted.
     */
    fun cacheReplyCountProjection(
        threadId: String,
        snapshot: ThreadReplySummariesSnapshot,
        observedAt: TimeSource.Monotonic.ValueTimeMark,
        serverId: String,
        principalId: String,
        expectedProvenance: ReplyCacheProvenance,
        commit: () -> Boolean,
    ): Boolean? {
        // The caller supplies the instant the canonical summaries request was
        // issued; the response reflects the server-side snapshot at request
        // time, so facts newer than that mark must not be overwritten.
        val facts =
            snapshot.threads.mapNotNull { summary ->
                val parentMessageId = summary.parentMessageId?.value ?: return@mapNotNull null
                parentMessageId to
                    SharedThreadReplyCountFact(
                        threadChannelId = (summary.threadChannelId ?: summary.channelId).value,
                        replyCount = summary.replyCount,
                        observedAt = observedAt
                    )
            }
        if (serverId != expectedProvenance.serverId || principalId != expectedProvenance.principalId) {
            return null
        }
        return synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized null
            if (!commit()) return@synchronized false
            if (facts.isNotEmpty()) {
                payloadCache.recordReplyCountFacts(
                    serverId = serverId,
                    threadId = threadId,
                    environmentId = expectedProvenance.environmentId,
                    principalId = principalId,
                    facts = facts
                )
            }
            true
        }
    }

    /**
     * Best-effort reply-count fact write for a realtime [ThreadUpdated] event.
     * Keeps the fact ledger fresh so a later canonical reprojection cannot
     * clobber a newer realtime reply count. Silently no-ops when the
     * provenance is unavailable or rotates mid-call.
     */
    fun recordReplyCountRealtimeFact(
        threadId: String,
        parentMessageId: String,
        fact: SharedThreadReplyCountFact,
    ) {
        val provenance = currentReplyCacheProvenance() ?: return
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != provenance) return@synchronized
            payloadCache.recordReplyCountFacts(
                serverId = provenance.serverId,
                threadId = threadId,
                environmentId = provenance.environmentId,
                principalId = provenance.principalId,
                facts = listOf(parentMessageId to fact)
            )
        }
    }

    /**
     * Hands bc6e392e: a [ThreadUpdated] whose parent lives in another conversation than the open
     * page (the server sends DM / private parents to `user:<id>`, so it reaches whichever page is in
     * the foreground). The open page's own handling keys its facts by the open page, so the parent's
     * conversation kept its old reply count and preview until a refetch.
     *
     * This lands the event on the parent's own conversation, named by the reply-window envelope's
     * `parentScopeId` (server: `parentMsgForThread.channelId`): the reply-count fact is recorded
     * under it, and its cached payload is patched in place at the current freshness generation.
     * Returns `null` when the event names no other conversation of the current server (the open
     * page owns it), otherwise whether a cached payload was patched.
     */
    internal fun applyThreadUpdatedToParentConversation(
        event: ThreadUpdated,
        openPageThreadId: String?,
    ): Boolean? {
        val envelope = event.syncCoreReplyWindow ?: return null
        val parentScopeId = envelope.parentScopeId.trim().takeIf { it.isNotBlank() } ?: return null
        if (parentScopeId == openPageThreadId) return null
        if (envelope.rootMessageId != event.parentMessageId.value) return null
        val provenance = currentReplyCacheProvenance() ?: return null
        if (envelope.parentScopeServerId != provenance.serverId) return null
        val parentMessageId = event.parentMessageId.value
        val observedAt = TimeSource.Monotonic.markNow()
        var acceptedWindow: ThreadRepliesAcceptedProjection? = null
        val patched =
            synchronized(replyCacheProvenanceLock) {
                if (replyCacheProvenance.value != provenance) return@synchronized false
                payloadCache.recordReplyCountFacts(
                    serverId = provenance.serverId,
                    threadId = parentScopeId,
                    environmentId = provenance.environmentId,
                    principalId = provenance.principalId,
                    facts =
                        listOf(
                            parentMessageId to
                                SharedThreadReplyCountFact(
                                    threadChannelId = event.threadChannelId.value,
                                    replyCount = event.replyCount,
                                    observedAt = observedAt,
                                )
                        ),
                )
                val ownerScopeId = provenance.threadPayloadOwnerScopeId ?: return@synchronized false
                val generation = payloadCache.captureGeneration(provenance.serverId, parentScopeId, ownerScopeId)
                val cached =
                    payloadCache.get(
                        provenance.serverId,
                        parentScopeId,
                        provenance.environmentId,
                        provenance.principalId,
                        ownerScopeId,
                    ) ?: return@synchronized false
                val next = cached.withOffPageThreadUpdated(event)
                if (next == cached) return@synchronized false
                if (!payloadCache.putIfCurrent(generation, next, ownerScopeId)) return@synchronized false
                // Persist the folded preview for the next cold start only when it grew from a window
                // this conversation already showed: a lone frame must not replace a fuller stored one.
                val before = (cached.messages + listOfNotNull(cached.originMessage)).firstOrNull { it.id == parentMessageId }
                val after = (next.messages + listOfNotNull(next.originMessage)).firstOrNull { it.id == parentMessageId }
                val model = after?.inlineReplies
                if (model != null && before?.inlineReplies?.replies?.isNotEmpty() == true) {
                    acceptedWindow =
                        ThreadRepliesAcceptedProjection(
                            identity =
                                ThreadRepliesScopeIdentity(
                                    page =
                                        ThreadRepliesPageIdentity(
                                            serverId = provenance.serverId,
                                            principalId = provenance.principalId,
                                            parentScopeKind = envelope.parentScopeKind,
                                            parentScopeId = parentScopeId,
                                        ),
                                    parentMessageId = parentMessageId,
                                    threadChannelId = event.threadChannelId.value,
                                ),
                            model = model,
                            revision = 0L,
                        )
                }
                true
            }
        acceptedWindow?.takeIf { it.identity.isValid() }?.let { recordReplyWindows(listOf(it)) }
        return patched
    }

    /**
     * #proj-mobile #120 / #wg-mobile-sync #88: the sender's own reply, confirmed by the send's HTTP
     * answer, lands on the parent conversation's cached payload (reply count, inline preview) the way
     * web's thread panel updates the parent summary when a send resolves. Until now the parent only
     * moved on `thread:updated`; a joint channel's frame is fanned out per server projection to that
     * server's `channel:<local parent>` room, and mobile holds a socket for the current server only,
     * so a reply sent from a window whose server is not the socket's (or whose projection frame never
     * reached it) left the parent's reply count and preview stale.
     *
     * Keyed by the reply page's own parent channel under [provenance] (the server that page belongs to),
     * not by the realtime envelope. [replyCountAtLeast] is what the reply page itself proves; the
     * parent's count also grows by one when its inline window shows this reply is newer than anything
     * it has counted. A later `thread:updated` still overwrites with the server's total.
     */
    internal fun applySentReplyToParentConversation(
        provenance: ReplyCacheProvenance,
        parentScopeId: String,
        parentMessageId: String,
        threadChannelId: String,
        reply: ReplyPreview,
        replyCountAtLeast: Int,
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(provenance) != true) {
                return@synchronized false
            }
            val current = replyCacheProvenance.value ?: return@synchronized false
            val ownerScopeId = current.threadPayloadOwnerScopeId ?: return@synchronized false
            val generation = payloadCache.captureGeneration(current.serverId, parentScopeId, ownerScopeId)
            val cached =
                payloadCache.get(
                    current.serverId,
                    parentScopeId,
                    current.environmentId,
                    current.principalId,
                    ownerScopeId,
                ) ?: return@synchronized false
            val next = cached.withSentReplyOnParent(parentMessageId, threadChannelId, reply, replyCountAtLeast)
            if (next == cached) return@synchronized false
            if (!payloadCache.putIfCurrent(generation, next, ownerScopeId)) return@synchronized false
            // The cache read re-applies reply-count facts; record the new total as the newest one so an
            // older summary/realtime fact cannot pull the parent back to its pre-send count.
            val nextParent = (next.messages + listOfNotNull(next.originMessage)).firstOrNull { it.id == parentMessageId }
            if (nextParent != null) {
                payloadCache.recordReplyCountFacts(
                    serverId = current.serverId,
                    threadId = parentScopeId,
                    environmentId = current.environmentId,
                    principalId = current.principalId,
                    facts =
                        listOf(
                            parentMessageId to
                                SharedThreadReplyCountFact(
                                    threadChannelId = nextParent.threadChannelId ?: threadChannelId,
                                    replyCount = nextParent.replyCount,
                                    observedAt = TimeSource.Monotonic.markNow(),
                                )
                        ),
                )
            }
            true
        }

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
    ): RaftThreadScreenPayload? {
        val session = readSession() ?: return null
        val home = currentHomeSnapshot(session) ?: return null
        return buildMarkdownChannelLaunchPayload(
            currentPayload = currentPayload,
            home = home,
            channelLabel = channelLabel,
            requiredKind = requiredKind,
            knownMemberProfilesById =
                if (requiredKind == ConversationKind.DirectMessage || raftLoungeConversationAgentName(channelLabel) != null) {
                    currentKnownMarkdownMemberProfilesById(home)
                } else {
                    emptyMap()
                }
        )
    }

    /**
     * The local channel a conversation-qualified task ref (`#proj:#7`, `dm:@peer:#7`,
     * `lounge:@agent:#7`) names, the scope its task number resolves in (Web `findTaskRefChannel`).
     * Null: the reader has no such conversation.
     */
    suspend fun markdownTaskRefChannelId(
        currentPayload: RaftThreadScreenPayload,
        conversationLabel: String,
        requiredKind: ConversationKind? = null,
        /** The chip this open started from: a directory fetch is drawn on it. */
        actionId: String? = null
    ): ChannelId? {
        val session = readSession() ?: return null
        val home = referenceHomeSnapshot(session, actionId) ?: return null
        val conversation =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = conversationLabel,
                requiredKind = requiredKind,
                knownMemberProfilesById =
                    if (requiredKind == ConversationKind.DirectMessage || raftLoungeConversationAgentName(conversationLabel) != null) {
                        currentKnownMarkdownMemberProfilesById(home)
                    } else {
                        emptyMap()
                    },
                currentPayload = currentPayload
            ) ?: return null
        return conversation.markdownTaskRefChannelId()
    }

    suspend fun markdownThreadRefLaunchPayload(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        channelLabel: String,
        threadShortId: String?,
        requiredParentKind: ConversationKind? = null,
        /** The message to focus in the thread when the caller knows it (a `#channel:short msg=id` ref). */
        focusedMessageId: String? = null,
        /** The chip this open started from: [referenceResolver]'s fetch phase is drawn on it. */
        actionId: String? = null
    ): DataResult<RaftThreadScreenPayload> {
        val shortId =
            threadShortId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        if (resolutionScope.provenance != provenance || !isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        onMarkdownReplyCacheScopeCapturedForTest?.invoke()
        val session = resolutionScope.session
        val home =
            referenceHomeSnapshot(session, actionId)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val channel =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = channelLabel,
                requiredKind = requiredParentKind,
                knownMemberProfilesById = knownMemberProfilesById,
                currentPayload = currentPayload,
                threadShortId = shortId
            )
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val parentChannelId =
            channel.resolveThreadRouteIdentity().timelineChannelId
                ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val target =
            when (
                val outcome =
                    referenceResolver.resolve(
                        key = SharedReferenceKey.of(provenance.namespaceId, SharedReferenceKind.Thread, parentChannelId, shortId),
                        actionId = actionId,
                        local = { localMarkdownThreadRefTarget(provenance, currentPayload, parentChannelId, shortId) },
                        // A message without a thread yet must not stop its thread opening later.
                        cacheable = { target -> !target.threadChannelId.isNullOrBlank() },
                        fetch = {
                            when (
                                val result =
                                    fetchThreadSnapshotFromRepository(
                                        fallbackCode = "thread_markdown_ref_context_network",
                                        expectedProvenance = provenance,
                                        // Only the ref's target: the opened page loads its own header detail.
                                        call = { repository ->
                                            repository.resolveMessageContext(MessageId(shortId), ChannelId(parentChannelId))
                                        }
                                    )
                            ) {
                                is DataResult.Content ->
                                    result.data.markdownThreadRefTarget(parentChannelId, shortId)
                                        ?.let { DataResult.Content(it) }
                                        ?: DataResult.Empty(EmptyReason.NoMessages)
                                is DataResult.Empty -> result
                                is DataResult.Error -> result
                                is DataResult.NoPermission -> result
                            }
                        }
                    )
            ) {
                is SharedReferenceOutcome.Open -> outcome.target
                is SharedReferenceOutcome.Failed -> return outcome.result
            }
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = channelLabel,
                threadShortId = shortId,
                requiredParentKind = requiredParentKind,
                knownMemberProfilesById = knownMemberProfilesById,
                parentMessageId = target.parentMessageId,
                focusedMessageId = focusedMessageId ?: target.focusedMessageId,
                threadChannelId = target.threadChannelId
            )
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        target.threadChannelId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                cacheReplyThreadResolution(
                    provenance,
                    parentChannelId,
                    target.parentMessageId,
                    it
                )
            }
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return DataResult.Content(payload)
    }

    /**
     * Opens what a message ref names (Web `handleOpenMessageRef`): the channel or DM is resolved by
     * name in the current server, and the id, which may be short, by the message's context. A reply
     * opens its thread focused on it; a top-level message opens its conversation anchored on it,
     * unless the ref names a thread, which then opens with the message focused.
     */
    suspend fun markdownMessageRefLaunchPayload(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        ref: RaftMarkdownMessageRef,
        /** The chip this open started from: [referenceResolver]'s fetch phase is drawn on it. */
        actionId: String? = null
    ): DataResult<RaftThreadScreenPayload> {
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        if (resolutionScope.provenance != provenance || !isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val home =
            referenceHomeSnapshot(resolutionScope.session, actionId)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val requiredKind = ConversationKind.DirectMessage.takeIf { ref.directMessagePeerName != null }
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val channel =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = ref.conversationLabel,
                requiredKind = requiredKind,
                knownMemberProfilesById = knownMemberProfilesById,
                currentPayload = currentPayload,
                threadShortId = ref.threadShortId ?: ref.messageId
            )
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val parentChannelId =
            channel.resolveThreadRouteIdentity().timelineChannelId
                ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val target =
            when (
                val outcome =
                    referenceResolver.resolve(
                        key = SharedReferenceKey.of(provenance.namespaceId, SharedReferenceKind.Message, parentChannelId, ref.messageId),
                        actionId = actionId,
                        // A message in a loaded timeline of its conversation is a top-level message there.
                        local = {
                            localMarkdownTimelineRefTarget(provenance, currentPayload, parentChannelId, ref.messageId)
                                ?.copy(threadChannelId = null)
                        },
                        fetch = {
                            when (
                                val result =
                                    fetchThreadSnapshotFromRepository(
                                        fallbackCode = "thread_markdown_message_ref_context_network",
                                        expectedProvenance = provenance,
                                        call = { repository ->
                                            repository.resolveMessageContext(MessageId(ref.messageId), ChannelId(parentChannelId))
                                        }
                                    )
                            ) {
                                is DataResult.Content ->
                                    result.data.markdownThreadRefTarget(parentChannelId, ref.messageId)
                                        ?.let { DataResult.Content(it) }
                                        ?: DataResult.Empty(EmptyReason.NoMessages)
                                is DataResult.Empty -> result
                                is DataResult.Error -> result
                                is DataResult.NoPermission -> result
                            }
                        }
                    )
            ) {
                is SharedReferenceOutcome.Open -> outcome.target
                is SharedReferenceOutcome.Failed -> return outcome.result
            }
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        if (target.focusedMessageId == null && target.threadChannelId == null) {
            val messageId = target.parentMessageId
            ref.threadShortId?.let { threadShortId ->
                return markdownThreadRefLaunchPayload(
                    provenance = provenance,
                    currentPayload = currentPayload,
                    channelLabel = ref.conversationLabel,
                    threadShortId = threadShortId,
                    requiredParentKind = requiredKind,
                    // The thread's own parent: opening the thread is the jump.
                    focusedMessageId = messageId.takeUnless { it.startsWith(threadShortId, ignoreCase = true) },
                    actionId = actionId
                )
            }
            return buildMarkdownChannelLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = ref.conversationLabel,
                requiredKind = requiredKind,
                knownMemberProfilesById = knownMemberProfilesById,
                anchorMessageId = messageId
            )?.let { DataResult.Content(it) }
                ?: DataResult.Empty(EmptyReason.NoMessages)
        }
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = ref.conversationLabel,
                threadShortId = ref.threadShortId ?: ref.messageId,
                requiredParentKind = requiredKind,
                knownMemberProfilesById = knownMemberProfilesById,
                parentMessageId = target.parentMessageId,
                focusedMessageId = target.focusedMessageId,
                threadChannelId = target.threadChannelId
            )
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        target.threadChannelId
            ?.takeIf { it.isNotBlank() }
            ?.let { cacheReplyThreadResolution(provenance, parentChannelId, target.parentMessageId, it) }
        return DataResult.Content(payload)
    }

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
    ): DataResult<AttachmentUploadResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "attachment_upload_unavailable", retryable = false))
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "missing_channel_id", retryable = false))
        }
        if (files.isEmpty()) {
            return DataResult.Error(ScreenError(code = "attachment_read_failed", retryable = false))
        }
        val request =
            AttachmentUploadRequest(
                channelId = ChannelId(channelId),
                files = files
            )
        val progressive = repository as? ProgressiveConversationAttachmentUploader
        return if (onProgress != null && progressive != null) {
            progressive.uploadAttachmentsWithProgress(request, onProgress)
        } else {
            repository.uploadAttachments(request)
        }
    }

    /**
     * Capture one complete authority witness and bind it to one direct-session coordinator.  The
     * returned operation must be reused for capability admission, every upload mutation and all
     * later compensation; this data source intentionally exposes no reconstruct-on-cleanup seam.
     */
    suspend fun captureDirectAttachmentUploadOperation(
        diagnosticsLogger: (String) -> Unit = {},
    ): DirectAttachmentUploadOperation? {
        val repository =
            conversationRepository
                ?: return null
        val authority = captureExternalShareSendAuthority() ?: return null
        return (repository as? RealConversationRepository)
            ?.directAttachmentUploadOperation(authority, diagnosticsLogger)
    }

    /** Fresh cached capabilities for [operation]'s authority; never suspends or hits the network. */
    fun peekAttachmentUploadCapabilities(
        operation: DirectAttachmentUploadOperation,
    ): AttachmentUploadCapabilities? = attachmentUploadCapabilitiesCache.peek(operation.authority)

    /** Cached (TTL) or single-flight fetched capabilities for the operation's authority. */
    suspend fun attachmentUploadCapabilities(
        operation: DirectAttachmentUploadOperation,
    ): DataResult<AttachmentUploadCapabilities> =
        attachmentUploadCapabilitiesCache.getOrFetch(operation.authority) { operation.capabilities() }

    /** Warm the capability cache when the composer opens so the first pick needs no round trip. */
    suspend fun prefetchAttachmentUploadCapabilities() {
        val operation = captureDirectAttachmentUploadOperation() ?: return
        attachmentUploadCapabilities(operation)
    }

    suspend fun attachmentUploadMaxBytes(
        expectedAuthority: RaftExpectedAuthBinding? = null,
    ): DataResult<Long> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(
                    ScreenError(code = "attachment_upload_capabilities_unavailable", retryable = true)
                )
        val boundRepository =
            expectedAuthority?.let { authority ->
                (repository as? RealConversationRepository)?.boundTo(authority)
                    ?: return DataResult.Error(
                        ScreenError(
                            code = "attachment_upload_authority_binding_unsupported",
                            retryable = false,
                        )
                    )
            } ?: repository
        return boundRepository.getAttachmentUploadCapabilities().map { capabilities -> capabilities.maxBytes }
    }

    suspend fun captureExternalShareSendAuthority(): RaftExpectedAuthBinding? {
        val session = sessionStore.readSession() ?: return null
        val environmentId = apiEnvironmentIdProvider().normalizedEnvironmentId().takeIf { it.isNotBlank() } ?: return null
        val accountId = session.user.id.trim().takeIf { it.isNotBlank() } ?: return null
        val accessToken = session.accessToken.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = session.refreshToken.takeIf { it.isNotBlank() } ?: return null
        val serverId = session.currentServerId?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (session.servers.none { server -> server.id.trim() == serverId }) return null
        return RaftExpectedAuthBinding(
            accountId = accountId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            serverId = serverId,
            environmentId = environmentId,
        )
    }

    suspend fun matchesExternalShareSendAuthority(expected: RaftExpectedAuthBinding): Boolean {
        val session = sessionStore.readSession() ?: return false
        val environmentId = apiEnvironmentIdProvider().normalizedEnvironmentId()
        val expectedEnvironment = expected.environmentId.normalizedEnvironmentId()
        return expectedEnvironment.isNotBlank() &&
            environmentId == expectedEnvironment &&
            session.matchesExpectedAuthority(
                expected = expected,
                effectiveServerId = expected.serverId,
                effectiveEnvironmentId = environmentId,
            ) &&
            session.servers.any { server -> server.id.trim() == expected.serverId.trim() }
    }

    suspend fun attachmentUrl(
        priority: DataRequestPriority,
        attachmentId: String,
        disposition: String? = null
    ): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentUrl(priority, attachmentId, disposition)

    suspend fun attachmentUrls(
        priority: DataRequestPriority,
        attachmentIds: List<String>,
    ): ThreadApiResult<Map<String, ThreadAttachmentOpenTarget>> =
        api.attachmentUrls(priority, attachmentIds)

    suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentHtmlPreviewUrl(attachmentId)

    suspend fun fetchFilesPayload(
        basePayload: RaftThreadScreenPayload,
        cursor: String? = null,
        currentPayload: RaftThreadFilesPayload? = null
    ): DataResult<RaftThreadFilesPayload> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_files_unavailable", retryable = false, traceId = null))
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false, traceId = null))
        return when (val result = repository.getChannelFiles(ChannelId(threadId), cursor = cursor)) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.toRaftThreadFilesPayload(
                        basePayload = basePayload,
                        currentPayload = currentPayload,
                        append = cursor != null
                    )
                )
            is DataResult.Empty ->
                DataResult.Content(
                    currentPayload
                        ?.takeIf { cursor != null && it.items.isNotEmpty() }
                        ?.copy(
                            status = "content",
                            hasMore = false,
                            loadingMore = false,
                            nextCursor = null,
                            errorCode = null,
                            errorServerMessage = null,
                            errorTraceId = null
                        )
                        ?: RaftThreadFilesPayload(
                            instanceId = basePayload.instanceId,
                            status = "empty",
                            items = emptyList(),
                            hasMore = false,
                            loadingMore = false,
                            nextCursor = null
                        )
                )
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

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
        val focusedContext =
            if (includeFollowedSummaries) {
                null
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
        if (focusedContext != null) {
            return focusedContext
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
                repository.getThread(
                    threadId = threadId,
                    includeFollowedSummaries = includeFollowedSummaries,
                    limit = pageSize,
                    captureRawMessagePage = onEnteredPage != null
                )
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
                basePayload.hasUntrustedReplyThreadStreamHint() &&
                !initialData.data.parentPairMatchesRoute(basePayload) ->
                fetchResolvedReplyThreadSnapshot(
                    session = session,
                    basePayload = basePayload,
                    replyResolutionScope = replyResolutionScope
                ).map { (resolvedBase, snapshot) ->
                    snapshot
                        ?.withReplyParentMessageFromContext(resolvedBase, replyResolutionScope = replyResolutionScope)
                        ?.toRaftThreadPayload(resolvedBase, session.user.id)
                        ?: resolvedBase.toEmptyReplyThreadPayload()
                }
            initialData != null ->
                DataResult.Content(
                    initialData.data
                        .withReplyParentMessageFromContext(basePayload, replyResolutionScope = replyResolutionScope)
                        .toRaftThreadPayload(basePayload, session.user.id)
                )
            else -> initialResult as DataResult<RaftThreadScreenPayload>
        }
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
    ): DataResult<RaftThreadScreenPayload>? {
        val focusedMessageId = basePayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return null
        val replyParentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
        if (basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && focusedMessageId == replyParentMessageId) {
            // The parent message lives in the parent channel, not the thread stream: requesting
            // `messages/context/<parentMessageId>?channelId=<threadChannelId>` always 404s. Fall
            // through to `resolveReplyParentMessageFromStore` + `getThread` while keeping focus.
            return null
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
        val context = (contextResult as? DataResult.Content)?.data ?: return null
        if (!context.matchesFocusedThreadRoute(routeThreadId, focusedMessageId)) {
            return null
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
        return DataResult.Content(
            context
                .withCachedReplyParentMessage(
                    basePayload = basePayload,
                    messageStoreScopeId = replyResolutionScope.messageStoreScopeId
                )
                .withConversationAccessFallback(accessFallback)
                .toRaftThreadPayload(basePayload, session.user.id)
        )
    }

    private fun ThreadSnapshot.withCachedReplyParentMessage(
        basePayload: RaftThreadScreenPayload,
        messageStoreScopeId: String?
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
                conversation.kind == ConversationKind.Thread
        if (!isReplyThread) return this
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
                ?: return this
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentChannelId?.value
                ?: parentMessage?.channelId?.value
                ?: return this
        val parent =
            getCachedReplyParentMessage(
                messageStoreScopeId = messageStoreScopeId ?: return this,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return this
        return copy(parentMessage = parent)
    }

    private suspend fun ThreadSnapshot.withReplyParentMessageFromContext(
        basePayload: RaftThreadScreenPayload,
        allowNetworkHydration: Boolean = true,
        replyResolutionScope: ReplyResolutionScope
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
                conversation.kind == ConversationKind.Thread
        if (!isReplyThread) return this
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
                ?: return this
        val existingParentMessage = parentMessage?.takeIf { it.id.value == parentMessageId }
        if (existingParentMessage != null && !existingParentMessage.needsReplyOriginContextHydration()) {
            return this
        }
        if (
            basePayload.originStatus == "loaded" &&
            basePayload.originMessage?.id == parentMessageId &&
            !basePayload.originMessage.needsReplyOriginContextHydration()
        ) {
            val originParentChannelId =
                basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                    ?: conversation.thread?.parentChannelId?.value
                    ?: parentMessage?.channelId?.value
                    ?: return this
            return copy(
                parentMessage =
                    basePayload.originMessage.toMessage(
                        threadId = ThreadId(originParentChannelId),
                        channelId = ChannelId(originParentChannelId),
                        diagnostics = diagnosticsLogger,
                    )
            )
        }
        val messageParentCandidate = messages.firstOrNull { message -> message.id.value == parentMessageId }
        if (messageParentCandidate != null && !messageParentCandidate.needsReplyOriginContextHydration()) {
            return this
        }
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentChannelId?.value
                ?: parentMessage?.channelId?.value
        if (!allowNetworkHydration) {
            return this
        }
        val resolvedParentChannelId = parentChannelId ?: return this
        val parent =
            getReplyParentMessageFromStore(
                parentChannelId = resolvedParentChannelId,
                parentMessageId = parentMessageId,
                messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                expectedProvenance = replyResolutionScope.provenance
            )
                ?: return this
        return copy(parentMessage = parent)
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
        val focusedContext =
            if (includeFollowedSummaries || routeThreadId == null) {
                null
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
        if (focusedContext != null) {
            return@coroutineScope focusedContext
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
                        basePayload
                    } else {
                        basePayload.copy(
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

    private suspend fun getReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): Message? =
        when (
            val resolution = resolveReplyParentMessageFromStore(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                requestPriority = requestPriority,
                messageStoreScopeId = messageStoreScopeId,
                expectedProvenance = expectedProvenance
            )
        ) {
            is SharedThreadMessageResolution.Loaded -> resolution.message
            SharedThreadMessageResolution.Missing,
            is SharedThreadMessageResolution.Error -> null
        }

    private suspend fun resolveReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): SharedThreadMessageResolution {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        val cachedParent = messageStoreScopeId?.let { scopeId ->
            messageResolver.getScoped(scopeId, parentChannel, parentMessage)
        }
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
        suspend fun fetchParent(): SharedThreadMessageResolution {
            val startedAt = TimeSource.Monotonic.markNow()
            val contextResult =
                fetchThreadSnapshotFromRepository(
                    fallbackCode = "thread_parent_context",
                    expectedProvenance = expectedProvenance,
                    allowCapturedScopeResultAfterProvenanceChange = true
                ) { repository ->
                    repository.getMessageContext(parentMessage, parentChannel, requestPriority)
                }
            diagnosticsLogger(
                "thread_parent_context_parallel_result parentChannelId=$parentChannelId " +
                    "parentMessageId=$parentMessageId result=${contextResult.threadDiagnosticsResult()} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return when (contextResult) {
                is DataResult.Content -> {
                    val message =
                        contextResult.data.parentMessage?.takeIf { message -> message.id == parentMessage }
                            ?: contextResult.data.messages.firstOrNull { message -> message.id == parentMessage }
                    message
                        ?.let { resolved -> SharedThreadMessageResolution.Loaded(resolved) }
                        ?: SharedThreadMessageResolution.Missing
                }
                is DataResult.Empty -> SharedThreadMessageResolution.Missing
                is DataResult.Error ->
                    SharedThreadMessageResolution.Error(
                        contextResult.error.code,
                        contextResult.error.threadParentHiddenTargetOrNull(parentMessageId)
                    )
                is DataResult.NoPermission -> SharedThreadMessageResolution.Error(contextResult.blocker.code)
            }
        }
        val parent =
            if (messageStoreScopeId == null) {
                fetchParent()
            } else {
                messageResolver.resolveScoped(messageStoreScopeId, parentChannel, parentMessage, ::fetchParent)
            }
        if (cachedParent != null && parent is SharedThreadMessageResolution.Loaded && parent.message == cachedParent) {
            diagnosticsLogger(
                "thread_parent_context_store_hit parentChannelId=$parentChannelId parentMessageId=$parentMessageId"
            )
        }
        return when (parent) {
            is SharedThreadMessageResolution.Loaded -> {
                parent.message
                    .takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
                    ?.let { resolved -> SharedThreadMessageResolution.Loaded(resolved) }
                    ?: SharedThreadMessageResolution.Error("thread_parent_context_identity_mismatch")
            }
            SharedThreadMessageResolution.Missing -> parent
            is SharedThreadMessageResolution.Error -> parent
        }
    }

    private fun getCachedReplyParentMessage(
        messageStoreScopeId: String,
        parentChannelId: String,
        parentMessageId: String
    ): Message? {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        return messageResolver.getScoped(messageStoreScopeId, parentChannel, parentMessage)
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
    }

    private fun ThreadSnapshot.rememberReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String,
        messageStoreScopeId: String?
    ): ThreadSnapshot {
        val parentChannel = ChannelId(parentChannelId)
        val targetMessageId = MessageId(parentMessageId)
        val candidate =
            parentMessage?.takeIf { message -> message.id == targetMessageId }
                ?: messages.firstOrNull { message -> message.id == targetMessageId }
                ?: return this
        if (candidate.matchesReplyOriginParent(parentChannel, targetMessageId)) {
            val scopeId = messageStoreScopeId ?: return this
            messageResolver.upsertScoped(scopeId, parentChannel, candidate)
        }
        return this
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

    private fun RaftThreadScreenPayload.toEmptyReplyThreadPayload(): RaftThreadScreenPayload =
        copy(
            status = "empty",
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            emptyTitle = ThreadReplyEmptyTitle,
            emptyDetail = ThreadReplyEmptyDetail
        )

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

    private suspend fun resolveConversationAccessFallback(
        basePayload: RaftThreadScreenPayload,
        session: StoredAuthSession
    ): ConversationAccessCandidate? {
        val home = currentHomeSnapshot(session) ?: return null
        return resolveConversationAccessFallback(basePayload, home)
    }

    private fun resolveConversationAccessFallback(
        basePayload: RaftThreadScreenPayload,
        home: HomeSnapshot
    ): ConversationAccessCandidate? {
        val lookupIds = basePayload.conversationAccessFallbackIds()
        if (lookupIds.isEmpty()) return null
        return home.toSharedConversationDirectoryState()
            .let { directory -> lookupIds.firstNotNullOfOrNull(directory::resolve) }
            ?.toConversationAccessCandidate()
            ?.takeIf { candidate -> basePayload.acceptsInitialConversationAccessCandidate(candidate) }
    }

    private suspend fun RaftThreadScreenPayload.withKnownMarkdownChannelNames(
        session: StoredAuthSession
    ): RaftThreadScreenPayload {
        val home = currentHomeSnapshot(session) ?: return this
        return withKnownMarkdownChannelNames(home)
    }

    private fun RaftThreadScreenPayload.withKnownMarkdownChannelNames(
        home: HomeSnapshot
    ): RaftThreadScreenPayload {
        val channelNames = home.knownMarkdownChannelNames()
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val directMessagePeerNames =
            home.knownMarkdownDirectMessagePeerNames(knownMemberProfilesById)
        return if (
            (channelNames.isEmpty() || channelNames == knownMarkdownChannelNames) &&
            directMessagePeerNames == knownMarkdownDirectMessagePeerNames
        ) {
            this
        } else {
            copy(
                knownMarkdownChannelNames = channelNames.ifEmpty { knownMarkdownChannelNames },
                knownMarkdownDirectMessagePeerNames = directMessagePeerNames
            )
        }
    }

    private fun currentKnownMarkdownMemberProfilesById(home: HomeSnapshot): Map<String, MemberProfile> =
        serverMembersStore?.currentKnownMemberProfilesById(home.server.id).orEmpty()

    private suspend fun resolveHomeSnapshotLookup(serverId: ServerId): HomeSnapshotLookup? =
        homeSnapshotLookupProvider?.invoke(serverId)
            ?: homeSnapshotProvider?.invoke(serverId)?.let { snapshot ->
                HomeSnapshotLookup(snapshot = snapshot, authoritative = true)
            }

    private suspend fun currentHomeSnapshot(session: StoredAuthSession): HomeSnapshot? {
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
        resolveHomeSnapshotLookup(serverId)?.snapshot?.let { snapshot ->
            diagnosticsLogger("thread_home_context source=shared_home_store serverId=${serverId.value}")
            return snapshot
        }
        val repository = channelRepository ?: return null
        diagnosticsLogger("thread_home_context source=repository serverId=${serverId.value}")
        return (repository.getHome(serverId) as? DataResult.Content)?.data
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
