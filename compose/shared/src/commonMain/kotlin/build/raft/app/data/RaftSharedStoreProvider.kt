package build.raft.app.data

import build.raft.app.RaftBesideMemoryRepository
import build.raft.app.RaftComputersRackToolbarStore
import build.raft.app.image.raftClearImageCaches
import build.raft.app.resources.raftSystemPreferredLanguageTagsOrEmpty
import build.raft.app.toJointConnectionsDurableRecord
import build.raft.app.toJointConnectionsSnapshot
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
import build.raft.app.host.HostCapabilities
import build.raft.app.RaftComposerDraftUploads
import build.raft.app.SharedThreadComposerDraftAccount
import build.raft.app.SharedThreadComposerDraftAccountStore
import build.raft.app.SharedThreadComposerDraftAccounts
import build.raft.app.SharedThreadComposerDraftJournalCleanup
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
import build.raft.app.CoroutineThreadComposerDraftPersistenceScheduler
import build.raft.app.ChannelSettingsSnapshotCache
import build.raft.app.ChannelSettingsResourceSpec
import build.raft.app.KeyValuePixelAvatarStore
import build.raft.app.ProcessAttachmentAuthScopes
import build.raft.app.ReplyCacheProvenance
import build.raft.app.SharedThreadMessageStore
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.sharedThreadTimelineOwnerScopeId
import build.raft.app.RaftPixelAvatarRuntime
import build.raft.app.RaftPlatformLogHint
import build.raft.app.SharedForegroundBannerStore
import build.raft.app.data.realtime.MessageSeqEnvelope
import build.raft.app.data.realtime.RaftRealtimeConnectionCauses
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.MessageSeqRecoveryPolicy
import build.raft.app.data.realtime.MessageSeqRecoveryState
import build.raft.app.data.realtime.MessageSeqRepairBatch
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.data.realtime.RaftRealtimeSessionHealth
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.refreshPixelAvatarsFromWeb
import build.raft.app.RaftLogLevel
import build.raft.app.raftAvatarImageCandidates
import build.raft.app.raftLogError
import build.raft.app.raftLogInfo
import build.raft.app.raftLogWarn
import build.raft.app.model.AgentActivitySubscribed
import build.raft.app.model.AgentCreated
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerFeaturesChanged
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.ServerAppearanceUpdated
import build.raft.app.model.UserProfileUpdated
import build.raft.app.model.ProductFeedbackUnreadUpdated
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.RaftServerThemeLook
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageType
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.network.createPlatformRaftHttpExecutor
import build.raft.app.network.raftNetworkKmmRecoveryEnvironmentId
import build.raft.app.network.raftNetworkLogLevel
import build.raft.app.network.sync.AttachmentCommentsReadCacheV2
import build.raft.app.network.sync.RaftThreadReplyWindowCache
import build.raft.app.network.sync.CanonicalFinalPreparedCommitStep
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2DurableAccessEvictor
import build.raft.app.network.sync.ThreadDurableAccessEvictor
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.PreparedCurrentGrantMessageCommitDisposition
import build.raft.app.network.sync.purgeSyncCoreMessagesV1Rows
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1DestinationPublication
import build.raft.app.network.sync.ProcessReadStateV1DiagnosticOverride
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.network.sync.SqlDelightReadStateV1Store
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreMessageShadowObserver
import build.raft.app.network.sync.SyncCoreReactionShadowLane
import build.raft.app.network.sync.PartitionDenialReason
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.coroutines.EmptyCoroutineContext

private const val SHARED_AUTH_LOG_TAG = "SlockSharedAuth"

// Pixel-avatar runtime refresh (task #352) waits out the startup window before
// fetching: first screen must not compete with this non-urgent request, and the
// per-client jitter keeps a web deploy from triggering a synchronized spike.
private const val PixelAvatarRefreshDelayMinMs = 10_000L
private const val PixelAvatarRefreshDelayMaxMs = 60_000L
private const val SharedCanonicalFinalSinkCapacity = 64
/** Diagnostics reason of the agents/Computers read after a switch no socket connect followed. */
private const val ServerSwitchCatchUpReason = "server_switch_catch_up"
/** Diagnostics reason of the periodic agents/Computers read while the app stays in front. */
private const val AgentStatusReconcileReason = "agent_status_reconcile"

private fun logSharedAuthDiagnostic(
    prefix: String,
    message: String,
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
) {
    val line = "$prefix $message"
    raftLogInfo(SHARED_AUTH_LOG_TAG, line, hint)
}

private fun sharedInfoLogger(
    tag: String,
    prefix: String = "",
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
): (String) -> Unit =
    { message ->
        raftLogInfo(tag, if (prefix.isBlank()) message else "$prefix $message", hint)
    }

/** Warn, except routine request start/finish lines, which are info ([raftNetworkLogLevel]). */
private fun sharedNetworkLogger(tag: String, prefix: String): (String) -> Unit =
    { message ->
        val line = "$prefix $message"
        when (raftNetworkLogLevel(message)) {
            RaftLogLevel.Info -> raftLogInfo(tag, line)
            else -> raftLogWarn(tag, line)
        }
    }

private fun sharedWarnLogger(
    tag: String,
    prefix: String = "",
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
): (String) -> Unit =
    { message ->
        raftLogWarn(tag, if (prefix.isBlank()) message else "$prefix $message", hint)
    }

private fun sharedErrorLogger(tag: String, prefix: String = ""): (String) -> Unit =
    { message ->
        raftLogError(tag, if (prefix.isBlank()) message else "$prefix $message")
    }

internal const val ServerFeatureFlagRefreshIntervalMillis = 60_000L

/**
 * Reads the selected server's flags at once, then every [intervalMillis] while the app is in the
 * foreground ([RaftProcessForegroundGate]): hidden, it reads nothing; back in front it reads once at
 * once if a read came due meanwhile.
 */
internal suspend fun refreshSelectedServerFeatureFlags(
    intervalMillis: Long = ServerFeatureFlagRefreshIntervalMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
    foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    refresh: suspend () -> Unit
) {
    require(intervalMillis > 0L) { "feature flag refresh interval must be positive" }
    repeatWhileProcessForeground(
        intervalMillis = intervalMillis,
        foreground = foreground,
        runImmediatelyOnStart = true,
        wait = wait,
        nowMillis = nowMillis,
        block = refresh,
    )
}

private const val ProcessRealtimeAuthorityScopeKey = "process-realtime-host"
private const val ProcessRealtimeAuthorityRegistrationId = "process-realtime-host-owner"

internal fun createSharedRealtimeAuthorityProvider(
    registry: RealtimeAuthorityRegistry,
    authStore: RaftAuthSessionStore,
    authEvents: RaftAuthSessionEventSource,
    currentEnvironmentId: () -> String,
    diagnosticsLogger: (String) -> Unit = {},
    canonicalFinalIntegration: RealtimeCanonicalFinalSinkIntegrationSeam? = null,
    // REQUIRED at the production creation seam: a provider that can publish
    // presence-capable grants must carry the binding-readiness hook —
    // test-only constructions use the RealtimeAuthorityProvider constructor
    // default directly.
    onAuthRevisionAdvanced: (RealtimeAuthRevision) -> Unit,
    // Production passes the typed account binding transition here (it then replaces
    // [onAuthRevisionAdvanced]); see processAccountBindingTransition.
    onAuthBindingAdvanced: ((RealtimeAuthRevision, RaftAuthSessionState) -> Unit)? = null,
): RealtimeAuthorityProvider =
    RealtimeAuthorityProvider(
        registry = registry,
        authStore = authStore,
        authEvents = authEvents,
        currentEnvironmentId = currentEnvironmentId,
        diagnosticsLogger = diagnosticsLogger,
        canonicalFinalIntegration = canonicalFinalIntegration,
        onAuthRevisionAdvanced = onAuthRevisionAdvanced,
        onAuthBindingAdvanced = onAuthBindingAdvanced,
    )

/**
 * The owner a realtime `channel:members-updated` acts for: the account of the grant it was admitted under
 * ([admission]), and only while the current auth binding ([context]) is still that grant's account AND
 * authority generation (the same check as the transport rebaseline). Otherwise null (a no-op): an account
 * switch racing the delivery never acts on the next account's lists.
 *
 * A null [admission] is a no-op too. That is the canonical-final surface delivery, which carries no grant and
 * so, like its presence and activity-unread writes, must not act; it only carries gateway-admitted message
 * frames, and a `channel:members-updated` (no message fold input) always takes the grant-admitted path.
 */
internal fun channelMembersUpdatedOwnerOrNull(
    context: ProcessPresenceAuthContext?,
    admission: RealtimePresenceAdmission?,
    serverId: String,
): AuthenticatedRouteOwner? {
    if (admission == null || context == null) return null
    if (context.accountId != admission.principalId || context.authorityGeneration != admission.authGeneration) return null
    val owner = context.ownerFor(serverId) ?: return null
    return AuthenticatedRouteOwner(owner.environmentId, owner.accountId, owner.serverId).normalizedOrNull()
}

/**
 * The provider's `channel:members-updated` apply: the owner is captured on the delivery path (see
 * [channelMembersUpdatedOwnerOrNull]); the reloads run off it in [scope] (network requests; a burst coalesces
 * per store), pinned to that owner, so an account switch before they run makes them a no-op.
 */
internal fun launchChannelMembersUpdated(
    scope: CoroutineScope,
    context: ProcessPresenceAuthContext?,
    admission: RealtimePresenceAdmission?,
    serverId: String,
    channelId: String?,
    stores: () -> List<SharedServerMembersStore>,
) {
    val owner = channelMembersUpdatedOwnerOrNull(context, admission, serverId) ?: return
    scope.launch {
        applyChannelMembersUpdated(
            stores(),
            ServerId(owner.serverId),
            channelId?.let(::ChannelId),
            expectedOwner = owner,
        )
    }
}

internal fun createSharedRealtimeDomainEventOwner(
    tasksDataStore: SharedTasksDataStore?,
    cursorStore: RealtimeAppliedSeqCursorStore?,
    authorityProvider: RealtimeAuthorityProvider,
    onAgentDeleted: (suspend (serverId: String, agentId: String) -> Unit)? = null,
    onChannelMembersUpdated: (suspend (serverId: String, channelId: String?, admission: RealtimePresenceAdmission?) -> Unit)? = null,
): SharedRealtimeDomainEventOwner =
    SharedRealtimeDomainEventOwner(
        tasksDataStore = tasksDataStore,
        onAgentDeleted = onAgentDeleted,
        onChannelMembersUpdated = onChannelMembersUpdated,
        cursorStore = cursorStore,
        runIfCurrentGrant = authorityProvider::runIfCurrentGrant,
        runWithCurrentMessageGrant = authorityProvider::runWithCurrentMessageGrant,
        canonicalFinalOwnerGateway = authorityProvider.canonicalFinalOwnerGatewayOrNull(),
    )

internal fun interface RaftSharedCanonicalFinalIntegrationFactory {
    fun create(
        scope: RaftSharedCanonicalFinalIntegrationFactoryScope,
    ): RealtimeCanonicalFinalSinkIntegrationSeam
}

/**
 * Production-owned dependencies for an optional canonical-final integration. The normal provider factory passes
 * no integration factory, so none of these dormant objects exist in production today. A bound host factory can
 * only ask this scope to compose the seam from the exact registry/auth/flags/directory/router graph created by the
 * one full [RaftSharedStoreProvider.create] call; it cannot replace the authority provider or any of its owners.
 */
internal class RaftSharedCanonicalFinalIntegrationFactoryScope internal constructor(
    val registry: RealtimeAuthorityRegistry,
    val authStore: KeyValueAuthSessionStore,
    val authEvents: RaftAuthSessionEventSource,
    val crossProcessAuthEvents: RaftAuthSessionEvents,
    val serverFeatureFlags: SharedServerFeatureFlags,
    val diagnosticsLogger: (String) -> Unit,
    val registrationDirectory: RealtimeCanonicalFinalSinkRegistrationDirectory,
    private val createLazyRouter: () -> RealtimeCanonicalFinalSinkLazyRouter,
) {
    fun createIntegration(t4FlagKey: String): RealtimeCanonicalFinalSinkIntegrationSeam =
        RealtimeCanonicalFinalSinkIntegrationSeam(
            snapshotForServer = { serverId -> serverFeatureFlags.snapshotFor(ServerId(serverId)) },
            registrationDirectory = registrationDirectory,
            t4FlagKey = t4FlagKey,
            lazyRouterFactory = createLazyRouter,
        )
}

internal data class RaftSharedStoreProviderRequest(
    val eventBus: RaftEventBus,
    val source: String,
    val host: HostCapabilities,
)

internal fun interface RaftSharedStoreProviderFactory {
    fun create(request: RaftSharedStoreProviderRequest): RaftSharedStoreProvider
}

internal class RaftSharedStoreFactoryBindingHandle internal constructor(
    internal val ownerToken: Any,
    internal val bindingToken: Any,
)

internal class RaftSharedStoreRegistryState<T : Any, A>(
    private val defaultFactory: (A) -> T,
    private val updateExisting: (T, A) -> Unit,
) {
    private data class BoundFactory<A, T : Any>(
        val token: Any,
        val factory: (A) -> T,
    )

    private val lock = SynchronizedObject()
    private val ownerToken = Any()
    private var boundFactory: BoundFactory<A, T>? = null

    /**
     * Written only under [lock]; read without it by [currentOrNull], so a UI thread asking whether
     * the store exists never waits behind a creation running on another thread (#113).
     */
    @Volatile
    private var instance: T? = null

    fun currentOrNull(): T? = instance

    fun bindFactoryBeforeFirstGet(factory: (A) -> T): RaftSharedStoreFactoryBindingHandle =
        synchronized(lock) {
            check(instance == null) { "shared store factory binding is sealed after first get" }
            val token = Any()
            boundFactory = BoundFactory(token = token, factory = factory)
            RaftSharedStoreFactoryBindingHandle(ownerToken = ownerToken, bindingToken = token)
        }

    fun unbindFactoryBeforeFirstGet(handle: RaftSharedStoreFactoryBindingHandle): Boolean =
        synchronized(lock) {
            if (instance != null || handle.ownerToken !== ownerToken) return@synchronized false
            val current = boundFactory ?: return@synchronized false
            if (current.token !== handle.bindingToken) return@synchronized false
            boundFactory = null
            true
        }

    fun get(arguments: A): T =
        synchronized(lock) {
            instance?.let { current ->
                updateExisting(current, arguments)
                return@synchronized current
            }
            create(arguments)
        }

    /**
     * The instance, created here when there is none, without [updateExisting]: for a creator off
     * the UI thread (the database launch gate), which leaves the per-caller update to its UI
     * thread's own [get] (#113).
     */
    fun getOrCreateWithoutUpdate(arguments: A): T =
        synchronized(lock) { instance ?: create(arguments) }

    private fun create(arguments: A): T {
        val created = (boundFactory?.factory ?: defaultFactory)(arguments)
        instance = created
        return created
    }

    /** Tests that build the provider (a page rendered whole) drop it again, so later tests start without one. */
    fun clearForTests() {
        synchronized(lock) {
            instance = null
            boundFactory = null
        }
    }
}

internal class RaftSharedStoreRegistryAccess<T : Any, A>(
    defaultFactory: (A) -> T,
    updateExisting: (T, A) -> Unit,
) {
    private val state = RaftSharedStoreRegistryState(defaultFactory, updateExisting)

    fun currentOrNull(): T? = state.currentOrNull()

    fun bindFactoryBeforeFirstGet(factory: (A) -> T): RaftSharedStoreFactoryBindingHandle =
        state.bindFactoryBeforeFirstGet(factory)

    fun unbindFactoryBeforeFirstGet(handle: RaftSharedStoreFactoryBindingHandle): Boolean =
        state.unbindFactoryBeforeFirstGet(handle)

    fun get(arguments: A): T = state.get(arguments)

    fun getOrCreateWithoutUpdate(arguments: A): T = state.getOrCreateWithoutUpdate(arguments)

    fun clearForTests() = state.clearForTests()
}

internal class ProcessReadReceiptOwnerLifecycle(
    private val readCurrentOwners: suspend () -> Set<ReadReceiptOwner>,
    private val readVisibleOrActiveOwners: () -> Set<ReadReceiptOwner>,
    private val revokeOwner: (ReadReceiptOwner) -> Unit,
) {
    private var knownOwners: Set<ReadReceiptOwner> = emptySet()
    private var revokedOwners: Set<ReadReceiptOwner> = emptySet()

    suspend fun initialize() {
        knownOwners = readCurrentOwners()
        revokedOwners = revokedOwners - knownOwners
    }

    suspend fun reconcile() {
        val currentOwners = readCurrentOwners()
        val newlyRevoked = ((knownOwners + readVisibleOrActiveOwners()) - currentOwners) - revokedOwners
        newlyRevoked.forEach(revokeOwner)
        revokedOwners = (revokedOwners + newlyRevoked) - currentOwners
        knownOwners = currentOwners
    }
}

internal fun CoroutineScope.observeProcessReadReceiptAuthLifecycle(
    events: SharedFlow<AuthSessionEvent>,
    lifecycle: ProcessReadReceiptOwnerLifecycle,
) = launch {
    lifecycle.initialize()
    events.collect { event ->
        if (event is AuthSessionEvent.Authenticated || event is AuthSessionEvent.Cleared) {
            lifecycle.reconcile()
        }
    }
}

internal class RaftSharedStoreProvider private constructor(
    private val host: HostCapabilities,
    private val sqlDatabase: RaftSqlDatabase,
    private val authKeyValueStore: RaftKeyValueStore,
    private val sharedPreferenceStore: RaftKeyValueStore,
    /** Durable member avatar references shared by every members store (Hands dd79da45). */
    private val memberAvatarRefStore: RaftMemberAvatarRefStore,
    /** Durable joint-channel actor references shared by every members store (#proj-mobile:#290). */
    private val jointActorRefStore: RaftJointActorRefStore,
    /** Durable server rosters shared by every members store (data-layer plan step 3). */
    private val serverMembersDurableStore: RaftServerMembersDurableStore,
    private val apiEnvironmentKeyValueStore: RaftKeyValueStore,
    override val authStore: KeyValueAuthSessionStore,
    val authSessionEvents: RaftAuthSessionEvents,
    val authDataSource: SharedAuthDataSource,
    val authLossFunnel: SharedAuthLossFunnel,
    val authRuntimeCapabilityStore: SharedAuthRuntimeCapabilityStore,
    override val networkClient: RaftNetworkClient,
    private val networkReachabilityGates: RaftHostReachabilityGates,
    private val scopedNetworkClientFactory: (RaftAuthSessionStore) -> RaftNetworkClient,
    private val ownerScopedNetworkClientFactory: (RaftAuthSessionStore, String) -> RaftNetworkClient,
    override val serverFeatureFlags: SharedServerFeatureFlags,
    private val canonicalMessageV2Canary: CanonicalMessageV2ProductionCanary,
    private val canonicalMessageV2GenerationEngine: CanonicalMessageV2GenerationEngine,
    val localDataStore: ComposeLocalDataStore,
    internal val avatarCacheMetadataStore: RaftAvatarCacheMetadataStore,
    internal val avatarCacheCoordinator: RaftAvatarCacheCoordinator,
    override val avatarCacheRuntime: RaftAvatarCacheRuntime,
    internal val avatarMountedBinding: RaftAvatarMountedBinding,
    val agentDetailStore: SharedAgentDetailStore,
    val agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    val tasksDataStore: SharedTasksDataStore,
    val serverMembersStore: SharedServerMembersStore,
    private val processServerMembersCore: ProcessServerMembersCore,
    private val processMemberPresenceRegistry: ProcessMemberPresenceRegistry,
    val activityUnreadSummaryStore: ProcessActivityUnreadStore,
    private val presenceOwnerResolver: (String) -> ProcessServerMembersOwner?,
    private val presenceOwnerFlowResolver: (String) -> Flow<ProcessServerMembersOwner?>,
    private val presenceWriteResolver: (String) -> ProcessPresenceWrite?,
    private val replyCacheAuthContext: StateFlow<ProcessPresenceAuthContext?>,
    /** [replyCacheAuthContext], typed: destructive followers key on this (architecture review B). */
    private val accountBinding: StateFlow<RaftAccountBinding<ProcessPresenceAuthContext>>,
    private val memberPresenceRealtimeSink: (RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    private val activityPersistStorage: SharedActivityPersistStorage,
    private val activityPersistSlice: MviPersistSlice<SharedActivityState, SharedActivityIntent>,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    /**
     * Offline-first resources (persistence audit item 4): every resource's durable rows, wiped once on
     * auth-terminal cleanup and per server on lost access. New resources need no cleanup wiring.
     */
    internal val offlineResourceCache: RaftOfflineResourceCache,
    /** Channel / thread member lists offline (rows only, never permission flags). */
    private val channelMembersOffline: SharedChannelMembersOffline,
    private val homeIdentityPersistSlice: MviPersistSlice<SharedHomeState, SharedHomeIntent>,
    override val apiEnvironmentStore: SharedApiEnvironmentStore,
    val eventBus: RaftEventBusRelay,
    val realtimeDomainEventOwner: SharedRealtimeDomainEventOwner,
    val realtimeAuthorityProvider: RealtimeAuthorityProvider,
    val messageTranslationStore: SharedMessageTranslationStore,
    val messageBodyFontSizeStore: SharedMessageBodyFontSizeStore,
    val threadMessageStore: SharedThreadMessageStore,
    val threadPayloadStore: SharedThreadPayloadStore,
    /** Offline cache read entry (task #1123): durable V2 last-known frame for thread screens. */
    internal val threadDurableLastKnownReader: ThreadDurableLastKnownReader,
    /** Offline cache write entry (task #1124): entered channel's REST page → V2 baseline. */
    internal val threadEnteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader,
    /** Offline cache paging entry (task #168): adjacent older / newer pages → the same V2 window. */
    internal val threadDurablePageExtender: ThreadDurablePageExtender,
    /** Lost-access eviction (data-layer plan, Tenny rule 2) for the same V2 partition. */
    internal val threadDurableAccessEvictor: ThreadDurableAccessEvictor,
    internal val threadPendingSendJournal: ThreadPendingSendJournal,
    val channelSettingsSnapshotCache: ChannelSettingsSnapshotCache,
    /** The header's `GET channels/{id}` detail as an offline resource (stand-in on a cold / offline open). */
    internal val storedConversationDetails: StoredConversationDetails,
    val foregroundReconciler: SharedForegroundReconciler,
    private val homeStoreScope: CoroutineScope,
    private val homeActivityStoreScope: CoroutineScope,
    val threadSendScope: CoroutineScope
) : RaftDataStoreProvider {
    /**
     * Composer attachment uploads, owned by their draft (server + conversation draft key) for the
     * life of the process rather than by the conversation page: leaving the conversation does not
     * stop them. Cancelled on remove / send, sign-out and lost server access; not persisted.
     */
    internal val composerDraftUploads = RaftComposerDraftUploads(threadSendScope)
    /** `slock #16493`: `channel:joined` acks for open Agent Lounge tail catch-up. */
    internal val channelJoinedSignals = RaftChannelJoinedSignals()
    // task #166: display-only last-known unread counts and previews for the cached cold-start
    // Home. Declared first so every later initializer that builds the Home store sees it.
    private val homeRowHintPersistStorage = HomeRowHintPersistStorage(sharedPreferenceStore)
    /** Reads not yet acknowledged, per (environment, account, server, channel). Kept on sign-out, dropped with the server. */
    private val readReceiptPendingStore = ReadReceiptPendingStore(sharedPreferenceStore)
    /** Last-known workspace notification preference per (user, server); kept on sign-out, dropped with the server. */
    val settingsServerNotificationLastKnownStore = SettingsServerNotificationLastKnownStore(sharedPreferenceStore)
    private val searchPersonalizationRepository = PersistentSearchPersonalizationRepository(sharedPreferenceStore)
    private val homeRowHintPersistSlice =
        createSharedHomeRowHintPersistSlice(
            storage = homeRowHintPersistStorage,
            diagnosticsLogger = sharedInfoLogger("SlockHomePersist", "shared_home_row_hints"),
            failureLogger = sharedErrorLogger("SlockHomePersist", "shared_home_row_hints"),
            scrollDeferScope = threadSendScope,
        )
    private val selectedServerStores = RaftShellScopedStoreCache<SelectedServerStore>()
    private val selectedServerSelectionEpochStore =
        KeyValueSelectedServerSelectionEpochStore(sharedPreferenceStore)
    private val shellAuthStores = RaftShellScopedStoreCache<RaftShellAuthSessionStore>()
    private val shellNetworkClients = RaftShellScopedStoreCache<RaftNetworkClient>()
    private val shellServerMembersStores = RaftShellScopedStoreCache<SharedServerMembersStore>()
    private val liveAgentActivityStores = RaftShellScopedStoreCache<SharedLiveAgentActivityStore>()
    private val shellRealtimeDomainEventOwners = RaftShellScopedStoreCache<SharedRealtimeDomainEventOwner>()
    private val realtimeAuthorityRegistrations = RaftShellScopedStoreCache<String>()
    private val shellAgentDetailStores = RaftShellScopedStoreCache<SharedAgentDetailStore>()
    private val shellComputersStores = RaftShellScopedStoreCache<SharedComputersStore>()
    private val shellRuntimeAccountUsageStores = RaftShellScopedStoreCache<SharedRuntimeAccountUsageStore>()
    private val shellChannelAnnouncementStores = RaftShellScopedStoreCache<SharedChannelAnnouncementStore>()
    private val shellChannelConversionStores = RaftShellScopedStoreCache<SharedChannelConversionStore>()
    private val shellMyAssistantStores = RaftShellScopedStoreCache<SharedMyAssistantStore>()
    private val shellComputersRackToolbarStores = RaftShellScopedStoreCache<RaftComputersRackToolbarStore>()

    /** Durable machine lists shared by every shell's computers store (data-layer plan step 5). */
    private val computersDurableStore by lazy {
        RaftKeyedDurableSnapshotStore(
            storage = SqlDelightRaftDurableRowStorage(sqlDatabase, RaftComputersSnapshotScope),
            serializer = RaftComputersDurableRecord.serializer(),
            label = "server_computers",
            logger = sharedInfoLogger("SlockComputers", "computers_durable"),
        )
    }

    /**
     * App link previews of the messages on screen and the viewer's answers to their prompts
     * (artin #20 lane L): one per process, in memory for the session, keyed by viewer and Server.
     */
    private val appLinkPreviewStoreLazy = lazy {
        SharedAppLinkPreviewStore(
            api = NetworkRaftAppLinkPreviewApi(networkClient),
            scope = RaftStoreThreading.newScope(),
            resolveIcon = { icon -> resolveAppLinkIconUrl(icon, apiEnvironmentStore.current().apiBaseUrl) },
            diagnosticsLogger = sharedInfoLogger("SlockAppLinkPreviews", "shared_app_link_previews"),
            // As the server's read: Artifact links never take one of the first three App links.
            artifactHosts = { clientConfigStore.appHosts.value },
            offlineResource = offlineResourceCache.resource(AppLinkPreviewResourceSpec),
            currentAccountId = { currentAccountBindingOrNull()?.second ?: authStore.readCommittedSessionSnapshot()?.user?.id },
        )
    }
    val appLinkPreviewStore: SharedAppLinkPreviewStore by appLinkPreviewStoreLazy

    /**
     * Per-server in-memory cache of installed Raft App origins (`GET integrations/clients` +
     * installed entries of `GET integrations/marketplace`, #proj-workspace:#356 Decision #9).
     * Scoped by `(accountId, environmentKey, serverId)` and cleared on sign-out / account or
     * environment switches / server-access loss.
     */
    private val installedAppOriginsCacheLazy = lazy {
        val api =
            build.raft.app.NetworkRaftBrowserSignInApi(
                networkClient = networkClient,
                resolveLogoUrl = { logo -> resolveAppLinkIconUrl(logo, apiEnvironmentStore.current().apiBaseUrl) },
            )
        build.raft.app.RaftInstalledAppOriginsCache(
            fetchOrigins = api::fetchInstalledAppOrigins,
            scope = RaftStoreThreading.newScope(),
        )
    }
    internal val installedAppOriginsCache: build.raft.app.RaftInstalledAppOriginsCache by installedAppOriginsCacheLazy

    internal fun currentInstalledAppAccountIdSync(): String? =
        (currentAccountBindingOrNull()?.second ?: authStore.readCommittedSessionSnapshot()?.user?.id)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    internal suspend fun resolveInstalledAppAccountId(): String? =
        currentInstalledAppAccountIdSync()
            ?: authStore.readSession()?.user?.id?.trim()?.takeIf { it.isNotEmpty() }

    internal fun currentInstalledAppEnvironmentKey(): String {
        val env = apiEnvironmentStore.current()
        return "${env.webUrl}|${env.apiBaseUrl}"
    }

    internal fun currentInstalledAppScopeSync(serverId: String?): build.raft.app.RaftInstalledAppScope? {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val accountId = currentInstalledAppAccountIdSync() ?: return null
        return build.raft.app.RaftInstalledAppScope(
            accountId = accountId,
            environmentKey = currentInstalledAppEnvironmentKey(),
            serverId = cleanServerId,
        )
    }

    internal fun onInstalledAppAccountOrEnvironmentChanged(
        accountId: String?,
        environmentKey: String?,
    ) {
        if (installedAppOriginsCacheLazy.isInitialized()) {
            installedAppOriginsCache.onAccountOrEnvironmentChanged(accountId, environmentKey)
        }
    }

    internal fun invalidateInstalledAppOriginsCache() {
        if (installedAppOriginsCacheLazy.isInitialized()) {
            installedAppOriginsCache.clearAll()
        }
    }

    internal fun threadInstalledAppOriginResolver(
        accountId: String,
        serverId: String,
    ): build.raft.app.ThreadInstalledAppOriginResolver {
        val cleanAccountId = accountId.trim()
        val cleanServerId = serverId.trim()
        return build.raft.app.ThreadInstalledAppOriginResolver(
            lookupSync = { origin ->
                val scope =
                    build.raft.app.RaftInstalledAppScope(
                        accountId = cleanAccountId,
                        environmentKey = currentInstalledAppEnvironmentKey(),
                        serverId = cleanServerId,
                    )
                installedAppOriginsCache.lookupSync(scope, origin)
            },
            lookupAsync = { origin ->
                val scope =
                    build.raft.app.RaftInstalledAppScope(
                        accountId = cleanAccountId,
                        environmentKey = currentInstalledAppEnvironmentKey(),
                        serverId = cleanServerId,
                    )
                installedAppOriginsCache.lookup(scope, origin)
            },
        )
    }

    /**
     * Deploy-level client config of the current API environment (`GET /api/public/client-config`):
     * Raft's own app hosts, which Artifact links are recognised on. Read on app start and on every
     * environment change, cached per environment on disk.
     */
    val clientConfigStore: SharedClientConfigStore by lazy {
        SharedClientConfigStore(
            api = NetworkRaftClientConfigApi(networkClient),
            preferences = sharedPreferenceStore,
            currentEnvironment = { apiEnvironmentStore.current().apiBaseUrl },
            environmentChanges = apiEnvironmentStore.state.map { it.apiBaseUrl },
            scope = threadSendScope,
            logger = sharedInfoLogger("SlockClientConfig", "client_config"),
        )
    }

    /**
     * Workspace Artifacts: the summaries of the Artifact links on screen (`POST /api/objects/summaries`),
     * batched and kept briefly, in memory for this viewer.
     */
    private val artifactSummaryStoreLazy = lazy {
        val clock = kotlin.time.TimeSource.Monotonic.markNow()
        SharedArtifactSummaryStore(
            api = NetworkRaftArtifactSummariesApi(networkClient),
            scope = RaftStoreThreading.newScope(),
            nowMillis = { clock.elapsedNow().inWholeMilliseconds },
            diagnosticsLogger = sharedInfoLogger("SlockArtifacts", "artifact_summaries"),
            offlineResource = offlineResourceCache.resource(ArtifactSummaryResourceSpec),
            currentAccountId = { currentAccountBindingOrNull()?.second ?: authStore.readCommittedSessionSnapshot()?.user?.id },
        )
    }
    val artifactSummaryStore: SharedArtifactSummaryStore by artifactSummaryStoreLazy

    /**
     * Workspace Artifacts: the reader's own Follow / Unfollow answers shared across Artifacts Home
     * lists and message cards (`PUT`/`DELETE /api/artifacts/:id/subscription`, slock #16357).
     */
    private val artifactFollowStoreLazy = lazy {
        val dashboardApi = NetworkRaftArtifactsDashboardApi(networkClient)
        SharedArtifactFollowStore(
            setFollowingRequest = { serverId, artifactId, following ->
                dashboardApi.updateArtifactFollowing(serverId, artifactId, following)
            },
            onFollowUpdated = { serverId, artifactId, following ->
                if (artifactSummaryStoreLazy.isInitialized()) {
                    artifactSummaryStore.patchFollowing(serverId, artifactId, following)
                }
            },
        )
    }
    internal val artifactFollowStore: SharedArtifactFollowStore by artifactFollowStoreLazy

    private val artifactScreenshotDiskCache: CoilArtifactScreenshotDiskCache by lazy {
        CoilArtifactScreenshotDiskCache(sharedPreferenceStore)
    }

    /**
     * Workspace Artifacts: the publish check's screenshots on the Artifact cards on screen
     * (`GET /api/objects/artifact/{id}/files/{fileId}`), with per-(env, account, server) disk cache
     * backed by Coil's DiskCache (#proj-mobile:#294).
     */
    private val artifactScreenshotStoreLazy = lazy {
        val clock = kotlin.time.TimeSource.Monotonic.markNow()
        SharedArtifactScreenshotStore(
            fetcher = NetworkArtifactScreenshotFetcher(networkClient),
            scope = RaftStoreThreading.newScope(),
            nowMillis = { clock.elapsedNow().inWholeMilliseconds },
            diskCache = artifactScreenshotDiskCache,
            currentOwner = {
                val binding = currentAccountBindingOrNull()
                val env = binding?.first ?: apiEnvironmentStore.current().apiBaseUrl
                val accountId = binding?.second ?: authStore.readCommittedSessionSnapshot()?.user?.id
                if (env.isNotBlank() && !accountId.isNullOrBlank()) {
                    ArtifactScreenshotDiskOwner(environmentId = env, accountId = accountId)
                } else {
                    null
                }
            },
        )
    }
    val artifactScreenshotStore: SharedArtifactScreenshotStore by artifactScreenshotStoreLazy

    /**
     * Quote replies of the messages on screen that came without their resolved quotes (a live
     * `message:new`): `GET /api/messages/:id/resolved-quotes`, in memory for this viewer.
     */
    private val resolvedQuotesStoreLazy = lazy {
        SharedResolvedQuotesStore(
            api = NetworkRaftResolvedQuotesApi(networkClient),
            scope = RaftStoreThreading.newScope(),
            diagnosticsLogger = sharedInfoLogger("RaftQuoteReply"),
        )
    }
    val resolvedQuotesStore: SharedResolvedQuotesStore by resolvedQuotesStoreLazy

    /** Joint channel Connected-servers pages (data-layer plan step 5): memory + durable. */
    internal val jointConnectionsCache: build.raft.app.RaftJointConnectionsCache by lazy {
        RaftKeyedSnapshotCache(
            durable =
                RaftKeyedDurableSnapshotStore(
                    storage = SqlDelightRaftDurableRowStorage(sqlDatabase, build.raft.app.RaftJointConnectionsSnapshotScope),
                    serializer = build.raft.app.RaftJointConnectionsDurableRecord.serializer(),
                    label = "joint_connections",
                    maxRowsPerServer = JointConnectionsMaxPerServer,
                    maxRowsPerAccount = JointConnectionsMaxPerAccount,
                    maxRows = JointConnectionsMaxRows,
                    logger = sharedInfoLogger("SlockJointConnections", "joint_connections_durable"),
                ),
            toDurable = { snapshot -> snapshot.toJointConnectionsDurableRecord() },
            fromDurable = { record -> record.toJointConnectionsSnapshot() },
            maxPerServer = JointConnectionsMaxPerServer,
        )
    }

    /**
     * Agent detail pages (data-layer plan step 5): per-server LRU in memory, a whitelisted record on disk
     * (offline resource; never secrets) for a cold / offline open.
     */
    internal val agentDetailCache: RaftAgentDetailCache =
        raftAgentDetailCache(offlineResourceCache.resource(AgentDetailResourceSpec))

    /** Realtime `agent:updated` for open agent Profiles (they re-read `GET /agents/:id`). */
    internal val agentUpdatedSignals: RaftAgentUpdatedSignals = RaftAgentUpdatedSignals()

    /**
     * Realtime `channel:members-updated`: every members store reloads its held list or drops a stored one
     * ([channelId] null: every list of the server). [admission]: the grant the event was admitted under
     * (see [channelMembersUpdatedOwnerOrNull]).
     */
    internal suspend fun applyChannelMembersUpdated(
        serverId: String,
        channelId: String?,
        admission: RealtimePresenceAdmission?,
    ) {
        launchChannelMembersUpdated(
            scope = threadSendScope,
            context = replyCacheAuthContext.value,
            admission = admission,
            serverId = serverId,
            channelId = channelId,
            stores = ::allServerMembersStores,
        )
    }

    /**
     * Realtime `user:profile-updated` (botiverse/slock #12688): the person is patched in place in every
     * members store (their faces and names in messages, member lists, Home rows and profile cards read
     * from there), and in the stored user when it is the grant's own account ([principalId]), so the
     * account's own profile follows a change made on another device. No network read.
     */
    internal suspend fun applyUserProfileUpdated(principalId: String, event: UserProfileUpdated) {
        allServerMembersStores().forEach { store -> store.applyUserProfileUpdated(event) }
        authStore.applyRealtimeUserProfileUpdated(principalId, event)
    }

    /**
     * Realtime `actor:updated` (slock `actorResolveContract.ts`): an agent of another server, seen on
     * [serverId] through a joint channel, changed its name or avatar. Its resolved row is replaced in
     * every members store's joint-actor directory (each applies it only to its current owner's scope).
     */
    internal fun applyActorUpdated(serverId: String, event: build.raft.app.model.ActorUpdated) {
        val actor =
            ResolvedActor(
                kind = event.kind,
                id = event.id,
                homeServerId = event.homeServerId,
                name = event.name,
                displayName = event.displayName,
                avatarUrl = event.avatarUrl,
                gravatarHash = null,
            )
        allServerMembersStores().forEach { store -> store.jointActors?.applyActorUpdated(ServerId(serverId), actor) }
    }

    /** Realtime `agent:deleted` for the current account: its cached detail page (memory and disk) goes. */
    internal suspend fun evictAgentDetail(serverId: String, agentId: String) {
        val (environmentId, accountId) = currentAccountBindingOrNull() ?: return
        val owner = sharedAgentDetailOwner(environmentId, accountId, ServerId(serverId)) ?: return
        agentDetailCache.evict(owner.agentDetailCacheKey(MemberId(agentId)))
    }

    /**
     * What was open beside each conversation on the wide layout (RaftBesideMemory.kt), per person and server on this
     * device: one instance, so its reads and writes share one lock.
     */
    internal val besideMemoryRepository = RaftBesideMemoryRepository(sharedPreferenceStore)

    /** Per-(account, server) persisted Artifacts home filters and settled-seen state (#proj-workspace:#381). */
    internal val artifactsHomeFilterRepository = ArtifactsHomeFilterRepository(sharedPreferenceStore)
    internal val artifactsHomeSettledSeenRepository = ArtifactsHomeSettledSeenRepository(sharedPreferenceStore)

    /** The account's local data on sign-out and on server-access loss (see the class). */
    private val accountLocalData =
        AccountLocalData(
            homeSavedStateCache = homeSavedStateCache,
            offlineResourceCache = offlineResourceCache,
            homeRowHints = homeRowHintPersistStorage,
            activityFirstPages = activityPersistStorage,
            notificationPreferences = settingsServerNotificationLastKnownStore,
            messageEngine = canonicalMessageV2GenerationEngine,
            memberAvatarRefStore = memberAvatarRefStore,
            jointActorRefStore = jointActorRefStore,
            jointConnectionsCache = { jointConnectionsCache },
            agentDetailCache = agentDetailCache,
            replyWindowCache = threadDurableLastKnownReader.replyWindowCache,
            threadPayloadStore = threadPayloadStore,
            clearViewerMemoryCaches = {
                // App link previews and consent answers, and Artifact summaries: per viewer, session only.
                if (appLinkPreviewStoreLazy.isInitialized()) appLinkPreviewStore.clearAll()
                if (installedAppOriginsCacheLazy.isInitialized()) installedAppOriginsCache.clearAll()
                if (artifactSummaryStoreLazy.isInitialized()) artifactSummaryStore.clearAll()
                if (artifactFollowStoreLazy.isInitialized()) artifactFollowStore.clearAll()
                if (artifactScreenshotStoreLazy.isInitialized()) artifactScreenshotStore.clearAll()
                artifactScreenshotDiskCache.clearAllAsync(threadSendScope)
                // Quote replies' resolved quotes and composer quote metadata: per viewer, session only.
                if (resolvedQuotesStoreLazy.isInitialized()) resolvedQuotesStore.clearAll()
                build.raft.app.ThreadComposerQuoteMetaCache.clear()
                // The Artifact viewer's headers and its kept WebView (Tenny's cache rule 1).
                build.raft.app.RaftArtifactViewerMemory.clear()
                build.raft.app.raftWebViewKeepAliveClear()
                RaftModelLabelCatalogs.clear()
            },
            logger = sharedInfoLogger("CanonicalMessageV2Store", "canonical_v2_access_evict"),
            besideMemory = besideMemoryRepository,
            artifactsHomeFilters = artifactsHomeFilterRepository,
            artifactsHomeSettledSeen = artifactsHomeSettledSeenRepository,
        )

    /**
     * The process auth binding (environment, account) right now, for synchronous first-frame cache
     * reads; null before the binding is known.
     */
    internal fun currentAccountBindingOrNull(): Pair<String, String>? =
        replyCacheAuthContext.value?.let { context ->
            val environment = context.environmentId.trim().trimEnd('/').takeIf { it.isNotBlank() }
            val account = context.accountId.trim().takeIf { it.isNotBlank() }
            if (environment == null || account == null) null else environment to account
        }

    /**
     * #proj-mobile:#290: recently used server logos and member/joint-actor http(s) avatar URLs to
     * promote from Coil's disk cache into its memory cache at startup so frame 1 paints directly
     * from memory without flashing placeholders.
     */
    internal fun startupPrewarmAvatarUrls(
        maxMemberUrls: Int = 48,
        maxJointUrls: Int = 16,
    ): List<String> {
        val session = authStore.readCommittedSessionSnapshot() ?: return emptyList()
        val apiBaseUrl = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
        val accountId = session.user.id.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val ownerKey = currentAccountBindingOrNull()?.let { (env, acc) -> "$env|$acc" } ?: "$apiBaseUrl|$accountId"
        val rawUrls = ArrayList<String>()
        val selectedId = session.currentServerId
        fun String?.addIfHttpOrRelative() {
            val clean = this?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (
                clean.startsWith("http://", ignoreCase = true) ||
                clean.startsWith("https://", ignoreCase = true) ||
                clean.startsWith("/api/avatars/", ignoreCase = true) ||
                clean.startsWith("/avatars/", ignoreCase = true)
            ) {
                rawUrls.add(clean)
            }
        }
        session.servers
            .sortedByDescending { it.id == selectedId }
            .forEach { server -> server.avatarUrl.addIfHttpOrRelative() }
        session.user.avatarUrl.addIfHttpOrRelative()
        rawUrls += memberAvatarRefStore.recentHttpAvatarUrls(maxMemberUrls, ownerKey = ownerKey)
        rawUrls += jointActorRefStore.recentHttpAvatarUrls(maxJointUrls, ownerKey = ownerKey)
        val resolved = LinkedHashSet<String>()
        for (raw in rawUrls) {
            val candidate = raftAvatarImageCandidates(raw, apiBaseUrl).firstOrNull() ?: continue
            if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
                resolved += candidate
            }
        }
        return resolved.toList()
    }

    private fun allComputersStores(): List<SharedComputersStore> =
        shellComputersStores.entries().map { (_, store) -> store }.distinct()
    private val shellCreateAgentFormStores = RaftShellScopedStoreCache<SharedCreateAgentFormStore>()
    private val shellServerRuntimeCatalogRepositories = RaftShellScopedStoreCache<SharedServerRuntimeCatalogRepository>()
    private val shellServerModelLabelCatalogRepositories = RaftShellScopedStoreCache<SharedServerModelLabelCatalogRepository>()
    private val shellCreateAgentFormOwnerJobs = RaftShellScopedStoreCache<Job>()
    private val shellAuthLoginGenerations = RaftShellScopedStoreCache<SharedAuthLoginGeneration>()
    private val shellAuthLoginGenerationJobs = RaftShellScopedStoreCache<Job>()
    private val shellNavigationCoordinators = RaftShellScopedStoreCache<SharedWindowNavigationCoordinator>()
    private val shellConversationForwardHistories = RaftShellScopedStoreCache<SharedConversationForwardHistory>()
    private val shellForegroundBannerStores = RaftShellScopedStoreCache<SharedForegroundBannerStore>()
    private val shellConversationIdentityHubs =
        RaftShellScopedStoreCache<SharedConversationIdentityHub>()
    private val shellRealtimeRoomLeaseRegistries =
        RaftShellScopedStoreCache<SharedRealtimeRoomLeaseRegistry>()
    private val shellAdaptiveWorkspaceStores =
        RaftShellScopedStoreCache<SharedAdaptiveWorkspaceStore>()
    private val shellAdaptiveScrollAnchorStores =
        RaftShellScopedStoreCache<SharedAdaptiveScrollAnchorStore>()
    private val shellPeerReadReceiptStores =
        RaftShellScopedStoreCache<SharedPeerReadReceiptStore>()
    private val shellAgentTypingStores =
        RaftShellScopedStoreCache<SharedAgentTypingStore>()
    private val processReadReceiptLock = SynchronizedObject()
    private var processReadReceiptCoordinator: ReadReceiptCoordinator? = null
    private val processReadReceiptRepositories = mutableMapOf<ReadReceiptOwner, RealReadReceiptRepository>()
    private val homeStores = RaftShellScopedStoreCache<SharedHomeStore>()
    private val homeActivityStores = RaftShellScopedStoreCache<SharedActivityStore>()
    private val homeStoreScopes = RaftShellScopedStoreCache<CoroutineScope>()
    private val homeActivityStoreScopes = RaftShellScopedStoreCache<CoroutineScope>()
    private val processReadStateV1Canary: ReadStateV1ProductionCanary by lazy {
        ReadStateV1ProductionCanary(
            isEnabledForServer = { owner, channelId ->
                val serverEffective =
                    serverFeatureFlags.isEnabled(ServerId(owner.serverId), SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY)
                ProcessReadStateV1DiagnosticOverride.controller
                    .decide(serverEffective, owner, channelId)
                    .finalEffective
            },
            store = SqlDelightReadStateV1Store(sqlDatabase, now = ::defaultNowMillis),
            onProjection = { _, _, _ -> },
            onProjectionWithEvidence = { owner, _, fact ->
                val currentHomeStores = homeStores.entries()
                val currentActivityStores = homeActivityStores.entries()
                currentHomeStores.forEach { (_, store) -> store.applyReadStateV1Projection(owner, fact) }
                currentActivityStores.forEach { (_, store) -> store.applyReadStateV1Projection(owner, fact) }
                ReadStateV1DestinationPublication(
                    homeStoreCount = currentHomeStores.size,
                    activityStoreCount = currentActivityStores.size,
                )
            },
            publicationGate = { owner, channelId, publish ->
                val serverEffective =
                    serverFeatureFlags.isEnabled(ServerId(owner.serverId), SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY)
                ProcessReadStateV1DiagnosticOverride.controller.runPublicationIfEffective(
                    serverEffective = serverEffective,
                    owner = owner,
                    channelId = channelId,
                    publish = publish,
                )
            },
            onRebaseline = { owner, _, channelId ->
                homeStores.entries().forEach { (_, store) -> store.dispatch(SharedHomeIntent.ForegroundRefresh) }
                homeActivityStores.entries().forEach { (_, store) ->
                    store.requestReadStateV1Rebaseline(owner, channelId)
                }
                sharedInfoLogger("ReadStateV1Canary", "rebaseline")("serverId=${owner.serverId}")
            },
            logger = sharedInfoLogger("ReadStateV1Canary", "production_canary"),
        ).also { canary ->
            ProcessReadStateV1DiagnosticOverride.controller.setOnChanged(canary::refreshEffectiveState)
        }
    }

    private suspend fun handlePresenceTransportConnected(
        grant: RealtimeGrantRecord,
        transportEpoch: Long
    ) {
        // Task #167: the durable message window must not append the new socket's frames after the old
        // tail. Only a current grant may move the epoch, so a late signal from a retired identity is ignored.
        realtimeAuthorityProvider.runIfCurrentGrant(grant) {
            realtimeDomainEventOwner.noteTransportEpoch(grant, transportEpoch)
            // A new socket has no agent activity subscriptions: ask for the open screens' agents again.
            agentActivitySubscriptions.onConnected(grant.serverId)
            // #198: the socket is back (servers without `rooms:joined` send nothing later); pages of
            // this server re-check what they show. Its `rooms:joined` carries the same epoch: one catch-up.
            RaftFreshnessSignal.onReconnected(
                grant.serverId,
                transportEpoch,
                afterDrop = RaftRealtimeConnectionCauses.connectedAfterDrop(grant.serverId),
            )
        }
        var rebaselineResult: ProcessPresenceTransportRebaselineResult? = null
        var rebaselineOwner: ProcessServerMembersOwner? = null
        val admitted =
            realtimeAuthorityProvider.runIfCurrentGrant(grant) {
                val context = replyCacheAuthContext.value
                if (
                    context == null ||
                    context.authorityGeneration != grant.authGeneration ||
                    context.accountId != grant.principalId
                ) {
                    return@runIfCurrentGrant
                }
                val owner = context.ownerFor(grant.serverId) ?: return@runIfCurrentGrant
                rebaselineOwner = owner
                rebaselineResult =
                    processMemberPresenceRegistry.rebaselineTransport(
                        owner = owner,
                        writeGeneration = context.writeGeneration,
                        transport =
                            ProcessPresenceTransportEpoch(
                                connectionFamilyId = grant.connectionFamilyId,
                                epoch = transportEpoch
                            )
                    )
            }
        val result = rebaselineResult
        if (
            !admitted ||
            result == null ||
            result == ProcessPresenceTransportRebaselineResult.Rejected
        ) {
            return
        }
        val owner = rebaselineOwner ?: return
        // Epoch + rebaseline above and the roster flight/cache retirement stay immediate; only the
        // roster refetch takes the reconnect jitter (Tenny 10-27, web #11453).
        val transport = ProcessPresenceTransportEpoch(connectionFamilyId = grant.connectionFamilyId, epoch = transportEpoch)
        // Decided once for this connect: the pull below and the roster refetch's scope (agents only).
        val syncOn = syncChangesPullAllowed(owner.serverId)
        val rosterDecision =
            presenceTransportRosterRefetch.onTransportRebaselined(
                owner = owner,
                transport = transport,
                rebaselineResult = result,
                agentsOnlyOnReconnect = syncOn,
            )
        if (!syncOn) return
        // Incremental sync (mobile_sync_changes_v0): what changed during the outage. A RE-connect
        // pull waits 0–2 s and its snapshot reloads (first sync, reset) take the roster's 0–20 s.
        val reconnect = rosterDecision != RaftReconnectRefetchDecision.Immediate
        // The connect right after a server switch is a reconnect (agents-only refetch) but not jittered.
        val jittered = reconnect && rosterDecision != RaftReconnectRefetchDecision.ImmediateAfterSwitch
        val fallback: (() -> Unit)? =
            if (reconnect) ({ presenceTransportRosterRefetch.onSyncPullFailed(owner, transport) }) else null
        val requested =
            requestSyncChangesPull(
                serverId = owner.serverId,
                trigger = "reconnect",
                jitterSnapshots = jittered,
                jitterPull = jittered,
                // A RE-connect refetched only agents: a pull that dropped the cursor gets the full
                // roster refetch back (a first failure keeps it and refetches nothing, #proj-mobile:#112).
                onFailed = fallback,
            )
        if (!requested) fallback?.invoke()
    }

    private val machinesSyncRefreshCoalescer: RaftRecentServerRefreshCoalescer by lazy {
        RaftRecentServerRefreshCoalescer(
            scope = threadSendScope,
            logger = sharedInfoLogger("SlockSync", "sync_changes"),
        )
    }

    /**
     * Incremental sync on: machine online status (`machine:status`) is not in the change log
     * (Scout 10-27), so a reconnect (after the jitter) and a foreground return read the machine
     * list again: every Computers list open for the server, and Home's machine notifications
     * (offline-computer alerts; read only for users who manage machines). Home reloads reuse what
     * this read ([SharedHomeDataSource.loadNotificationsAfterHomeLoad]).
     */
    internal suspend fun refreshMachinesForSync(
        serverId: String,
        reason: String,
        coalesceFreshWithin: Duration = Duration.ZERO,
        notStartedBefore: kotlin.time.ComparableTimeMark? = null,
    ): Boolean {
        val runResult =
            machinesSyncRefreshCoalescer.runOrCoalesceWithResult(
                serverId = serverId,
                reason = reason,
                coalesceFreshWithin = coalesceFreshWithin,
                notStartedBefore = notStartedBefore,
            ) {
                val stores = syncComputersStores(serverId)
                var allSucceeded = true
                stores.forEach { store ->
                    val ok =
                        runCatchingPreservingCancellation { store.reloadMachinesForSyncSucceeded(serverId) }
                            .getOrDefault(false)
                    if (!ok) allSucceeded = false
                }
                refreshHomeMachineNotifications(serverId, reason)
                sharedInfoLogger("SlockSync", "sync_changes")(
                    "sync_machines_refresh serverId=$serverId computersStores=${stores.size} ok=$allSucceeded reason=$reason"
                )
                allSucceeded
            }
        if (runResult.outcome != RaftRecentServerRefreshCoalescer.Outcome.Executed) {
            sharedInfoLogger("SlockSync", "sync_changes")(
                "sync_machines_refresh serverId=$serverId outcome=${runResult.outcome.name.lowercase()} ok=${runResult.succeeded} reason=$reason"
            )
        }
        return runResult.succeeded
    }

    /**
     * Home's machine notifications of every shell showing [serverId]: a Machines-scope refresh, which
     * drops the machine list Home holds and (Home visible) reads it again; a hidden Home reads it on
     * its return reload.
     */
    private fun refreshHomeMachineNotifications(serverId: String, reason: String) {
        val activeScopes = scopesForServer(serverId).toSet()
        val event =
            RealtimeRefreshRequired(scope = RealtimeRefreshScope.Machines, reason = reason, serverId = ServerId(serverId))
        homeStores.entries().forEach { (scope, store) ->
            if (scope in activeScopes) store.dispatch(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
        }
    }

    /** A full-list refetch standing in for a failed incremental pull: always after the reconnect jitter. */
    internal fun scheduleSyncFallbackRefetch(serverId: String, source: String, refetch: suspend () -> Unit) {
        reconnectRefetchJitter.scheduleDelayed(serverId = serverId, source = source, refetch = refetch)
    }

    /** `GET /api/sync/changes` cursors per environment, account and server (same lifetime as the account caches). */
    private val syncCursorStore: RaftSyncCursorStore by lazy { RaftSyncCursorStore(sharedPreferenceStore) }

    /**
     * The sync reloads that failed, for the next pull to read again. Shared with the Home and
     * Activity stores, whose list reads settle the reloads sync dispatched to them.
     */
    private val syncReloadLedger: RaftSyncReloadLedger by lazy { RaftSyncReloadLedger() }

    private val syncChangesEngine: RaftSyncChangesEngine by lazy {
        RaftSyncChangesEngine(
            api =
                RaftSyncChangesNetworkApi(
                    networkClient,
                    // `sync_inbox_rows_v0` on for the server: ask for its Activity rows (inbox_row).
                    capabilities = { serverId ->
                        if (serverFeatureFlags.isEnabled(serverId, SYNC_INBOX_ROWS_FEATURE_FLAG_KEY)) {
                            setOf(SYNC_CAPABILITY_INBOX_ROW)
                        } else {
                            emptySet()
                        }
                    },
                ),
            cursors = syncCursorStore,
            sink =
                RaftSyncChangesStoreRouter(
                    targets = syncStoreTargets,
                    ledger = syncReloadLedger,
                    logger = sharedInfoLogger("SlockSync", "sync_changes"),
                ),
            // Same account and the server still selected by a shell: a server switch retires the pull.
            isCurrent = { scope ->
                currentSyncScope(scope.serverId) == scope && scopesForServer(scope.serverId).isNotEmpty()
            },
            snapshotJitterDelay = { delay(reconnectRefetchJitterMillis(kotlin.random.Random.Default)) },
            pullJitterDelay = {
                delay(
                    reconnectRefetchJitterMillis(
                        kotlin.random.Random.Default,
                        maxMillis = SYNC_CHANGES_RECONNECT_PULL_JITTER_MAX_MILLIS
                    )
                )
            },
            logger = sharedInfoLogger("SlockSync", "sync_changes"),
        )
    }

    /** Whether [requestSyncChangesPull] would pull [serverId] now (signed in, selected). */
    internal fun syncChangesPullAllowed(serverId: String): Boolean {
        val scope = currentSyncScope(serverId) ?: return false
        return scopesForServer(scope.serverId).isNotEmpty()
    }

    /** The one retry after a first transient pull failure, per scope (#proj-mobile:#112). */
    private val syncTransientRetry: RaftSyncTransientRetry by lazy {
        RaftSyncTransientRetry(scope = threadSendScope, logger = sharedInfoLogger("SlockSync", "sync_changes"))
    }

    /** The waits a pull's answer asks for (`retryAfterMs`, a delayed `inboxReset`), per scope. */
    private val syncRetryAfter: RaftSyncRetryAfter by lazy {
        RaftSyncRetryAfter(
            scope = threadSendScope,
            isForeground = { RaftStaleReadRecoverySignals.foreground.value },
            logger = sharedInfoLogger("SlockSync", "sync_changes"),
        )
    }

    /**
     * One debounced pull (trigger `realtime`) for the realtime events Activity cannot settle from its
     * own read: a live message for a row it lacks, a `notification:retract` ([requestRealtimeSyncPull]).
     */
    private val syncRealtimePull: RaftSyncRealtimePull by lazy {
        RaftSyncRealtimePull(
            scope = threadSendScope,
            pull = { scope, trigger ->
                if (currentSyncScope(scope.serverId) == scope) launchSyncChangesPull(scope.serverId, trigger = trigger) else null
            },
            isForeground = { RaftStaleReadRecoverySignals.foreground.value },
            logger = sharedInfoLogger("SlockSync", "sync_changes"),
        )
    }

    /**
     * Activity could not settle a realtime event ([reason]) from its own read: one debounced pull of
     * [serverId] ([RaftSyncRealtimePull]). Only for a server a shell has selected, signed in, with the
     * inbox_row capability on (without it the pull carries no Activity rows). Called under the
     * Activity store's realtime lock: it only queues.
     */
    internal fun requestRealtimeSyncPull(serverId: String, reason: String) {
        val scope = currentSyncScope(serverId) ?: return
        if (scopesForServer(scope.serverId).isEmpty()) return
        if (!serverFeatureFlags.isEnabled(ServerId(serverId), SYNC_INBOX_ROWS_FEATURE_FLAG_KEY)) return
        syncRealtimePull.request(scope, reason)
    }

    private val syncPullJobsLock = SynchronizedObject()
    private val syncPullJobs = mutableMapOf<String, MutableSet<Job>>()

    /**
     * Server switch (Tenny 10-27): only the selected server pulls. A pull of a server no shell
     * selects any more is cancelled, and so is its waiting transient retry (its cursor is only
     * written once a whole pull is done, so the next pull of that server asks for that pull's pages
     * again; applying them twice is harmless).
     */
    private fun cancelUnselectedSyncPulls() {
        val cancelled =
            synchronized(syncPullJobsLock) {
                syncPullJobs.filterKeys { scopesForServer(it).isEmpty() }.values.flatMap { it.toList() }
            }
        cancelled.forEach { it.cancel() }
        syncTransientRetry.cancelWhere(reason = "server_unselected") { scopesForServer(it.serverId).isEmpty() }
        syncRetryAfter.cancelWhere(reason = "server_unselected") { scopesForServer(it.serverId).isEmpty() }
        syncRealtimePull.cancelWhere(reason = "server_unselected") { scopesForServer(it.serverId).isEmpty() }
    }

    private val syncPullBindingGate = RaftSyncPullBindingGate()

    /** A pull asked for before the binding is known runs once the account is bound (review B, site 4). */
    private fun requestSyncChangesPullWhenBound(serverId: String, trigger: String) {
        if (requestSyncChangesPull(serverId, trigger = trigger)) return
        if (syncPullBindingGate.deferUntilBound(serverId, trigger, accountBinding.value)) {
            sharedInfoLogger("SlockSync", "sync_changes")(
                "sync_pull_deferred reason=binding_unknown server=$serverId trigger=$trigger"
            )
            // The binding may have become known in between: run now rather than wait for a change.
            runDeferredSyncPulls(accountBinding.value)
        }
    }

    private fun runDeferredSyncPulls(binding: RaftAccountBinding<ProcessPresenceAuthContext>) {
        syncPullBindingGate.onBinding(binding).forEach { pull ->
            requestSyncChangesPull(pull.serverId, trigger = pull.trigger)
        }
    }

    private fun currentSyncScope(serverId: String): RaftSyncScope? {
        val context = replyCacheAuthContext.value ?: return null
        return RaftSyncScope.of(context.environmentId, context.accountId, serverId)
    }

    /**
     * Pulls [serverId]'s changes since its cursor ([RaftSyncChangesEngine]) when it is a server a shell
     * has selected (other servers keep their summary fetch).
     * Triggers: socket (re)connect, foreground return, server select, realtime events Activity cannot
     * settle ([requestRealtimeSyncPull]). False when skipped.
     *
     * [onFailed] runs when the pull failed and dropped the cursor
     * ([RaftSyncPullResult.Failed.needsFullRefetchFallback]): the caller's full-list refetch it stood
     * in for (web client, Scout 10-27). A first transient failure on a cursor keeps it and does not
     * run [onFailed]: one retry pull 30–60 s later ([RaftSyncTransientRetry]) carries [onFailed] on,
     * unless a normal trigger pulls first (#proj-mobile:#112). Callers must not refetch on their own
     * around this; a false return (no pull) is theirs to handle. [transientRetry]: this is that one
     * retry (it schedules none of its own).
     */
    internal fun requestSyncChangesPull(
        serverId: String,
        trigger: String,
        jitterSnapshots: Boolean = false,
        jitterPull: Boolean = false,
        onFailed: (() -> Unit)? = null,
        transientRetry: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
        onResult: ((RaftSyncPullResult?) -> Unit)? = null,
    ): Boolean = launchSyncChangesPull(serverId, trigger, jitterSnapshots, jitterPull, onFailed, transientRetry, onFinished, onResult) != null

    /** [requestSyncChangesPull], returning the pull's job (null when skipped). */
    private fun launchSyncChangesPull(
        serverId: String,
        trigger: String,
        jitterSnapshots: Boolean = false,
        jitterPull: Boolean = false,
        onFailed: (() -> Unit)? = null,
        transientRetry: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
        onResult: ((RaftSyncPullResult?) -> Unit)? = null,
    ): Job? {
        val scope = currentSyncScope(serverId) ?: return null
        if (scopesForServer(scope.serverId).isEmpty()) return null
        // A normal trigger pulling first makes a waiting retry moot (#proj-mobile:#112).
        if (!transientRetry) syncTransientRetry.onTriggerPull(scope)
        syncRetryAfter.onTriggerPull(scope)
        // It reads after every realtime hint so far: a waiting realtime pull is moot too.
        syncRealtimePull.onTriggerPull(scope)
        val finishedNotified = kotlinx.atomicfu.atomic(false)
        val notifyFinished: (Boolean, RaftSyncPullResult?) -> Unit = { ok, pullResult ->
            if (finishedNotified.compareAndSet(expect = false, update = true)) {
                onResult?.invoke(pullResult)
                onFinished?.invoke(ok)
            }
        }
        val job =
            threadSendScope.launch(start = CoroutineStart.LAZY) {
                var succeeded = false
                var pullResult: RaftSyncPullResult? = null
                try {
                    val result = syncChangesEngine.pull(scope, trigger, jitterSnapshots, jitterPull)
                    pullResult = result
                    succeeded = result is RaftSyncPullResult.Synced
                    when (result) {
                        is RaftSyncPullResult.Failed -> if (result.needsFullRefetchFallback) onFailed?.invoke()
                        // Stopped at the page cap with more waiting: go on in a follow-up pull, unless
                        // this pull left the cursor where it was (it would only ask the same again).
                        is RaftSyncPullResult.Synced ->
                            if (result.needsFollowUp) requestSyncChangesPull(scope.serverId, trigger = "truncated_follow_up")
                    }
                    // A first transient failure kept the cursor and refetched nothing: one retry 30–60 s
                    // later, with this caller's fallback for when it fails again (#proj-mobile:#112).
                    // inbox_row capability: a held row (`retryAfterMs`) pulls again later; a delayed inboxReset
                    // retakes the Activity list then.
                    syncRetryAfter.onPullFinished(
                        scope,
                        result,
                        repull = {
                            if (currentSyncScope(scope.serverId) == scope) requestSyncChangesPull(scope.serverId, trigger = "retry_after")
                        },
                        reloadInbox = {
                            if (currentSyncScope(scope.serverId) == scope) {
                                syncStoreTargets.refreshActivity(scope.serverId, reason = "sync_inbox_reset_delayed")
                            }
                        },
                    )
                    syncTransientRetry.onPullFinished(scope, result, wasRetry = transientRetry) {
                        if (currentSyncScope(scope.serverId) == scope) {
                            requestSyncChangesPull(scope.serverId, trigger = "transient_retry", onFailed = onFailed, transientRetry = true)
                        }
                    }
                } finally {
                    notifyFinished(succeeded, pullResult)
                }
            }
        synchronized(syncPullJobsLock) { syncPullJobs.getOrPut(scope.serverId) { mutableSetOf() } += job }
        job.invokeOnCompletion {
            synchronized(syncPullJobsLock) { syncPullJobs[scope.serverId]?.remove(job) }
            notifyFinished(false, null)
        }
        job.start()
        return job
    }

    internal fun refreshActivityForSyncFallback(serverId: String, reason: String) {
        syncStoreTargets.refreshActivity(serverId, reason)
    }

    internal suspend fun pullSyncChangesSucceeded(serverId: String, trigger: String): Boolean {
        val outcome = kotlinx.coroutines.CompletableDeferred<Boolean>()
        launchSyncChangesPull(serverId, trigger = trigger, onFinished = { outcome.complete(it) }) ?: return false
        return outcome.await()
    }

    private fun syncServerMembersStores(serverId: String): List<SharedServerMembersStore> {
        val activeScopes = scopesForServer(serverId).toSet()
        return buildList {
            add(serverMembersStore)
            shellServerMembersStores.entries().forEach { (scope, store) -> if (scope in activeScopes) add(store) }
        }.distinct()
    }

    private fun syncComputersStores(serverId: String): List<SharedComputersStore> {
        val activeScopes = scopesForServer(serverId).toSet()
        return shellComputersStores.entries().filter { (scope, _) -> scope in activeScopes }.map { it.second }.distinct()
    }

    /** The stores incremental sync writes into: every store of a shell showing the server, through its reducer. */
    private val syncStoreTargets: RaftSyncStoreTargets =
        object : RaftSyncStoreTargets {
            override fun rosterServer(serverId: String): ServerSummary? =
                syncServerMembersStores(serverId).firstNotNullOfOrNull { store ->
                    store.currentServerMembersEntry(ServerId(serverId))
                        .takeIf { it.status == SharedServerMembersLoadStatus.Loaded }
                        ?.server
                }

            override suspend fun supersedeRosterLoad(serverId: String, reason: String) {
                replyCacheAuthContext.value?.ownerFor(serverId)?.let { owner ->
                    processServerMembersCore.invalidate(owner = owner, reason = reason)
                }
            }

            override fun machineNames(serverId: String): Map<String, String> =
                syncComputersStores(serverId).firstNotNullOfOrNull { store ->
                    store.currentMachineNames(serverId).takeIf { it.isNotEmpty() }
                } ?: syncServerMembersStores(serverId).firstNotNullOfOrNull { store ->
                    store.currentServerMembers(ServerId(serverId)).knownComputerNames().takeIf { it.isNotEmpty() }
                }.orEmpty()

            override suspend fun applyRosterRows(
                serverId: String,
                upserts: List<MemberProfile>,
                deletes: List<Pair<MemberId, MemberKind>>
            ) {
                syncServerMembersStores(serverId).forEach { store ->
                    store.applySyncedRosterRows(ServerId(serverId), upserts, deletes)
                }
            }

            override suspend fun applyMachineRows(serverId: String, upserts: List<ComputerSummary>, deletes: Set<String>) {
                syncComputersStores(serverId).forEach { store -> store.applySyncedMachineRows(serverId, upserts, deletes) }
                // Home's machine notifications are built from the machine list: read it again (and
                // drop the copy sync-on Home reloads reuse).
                refreshHomeMachineNotifications(serverId, reason = "sync_changes")
            }

            override fun refreshHome(serverId: String, reason: String) {
                val activeScopes = scopesForServer(serverId).toSet()
                val event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.Home,
                        reason = reason,
                        serverId = ServerId(serverId),
                        supersedesInFlightLoad = true
                    )
                homeStores.entries().forEach { (scope, store) ->
                    if (scope in activeScopes) store.dispatch(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
                }
            }

            override fun refreshActivity(serverId: String, reason: String) {
                val activeScopes = scopesForServer(serverId).toSet()
                val event = build.raft.app.model.ActivityChanged(ServerId(serverId), source = reason)
                homeActivityStores.entries().forEach { (scope, store) ->
                    if (scope in activeScopes) store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))
                }
                activityUnreadSummaryStore.refresh(ServerId(serverId), reason = reason)
            }

            override fun applyInboxRows(serverId: String, changes: List<RaftSyncInboxRowChange>): RaftSyncInboxRowsOutcome {
                val activeScopes = scopesForServer(serverId).toSet()
                val outcomes =
                    homeActivityStores.entries()
                        .filter { (scope, _) -> scope in activeScopes }
                        .map { (_, store) -> store.applySyncInboxRows(serverId, changes) }
                // A store that could not take them reads its list through the `conversation` changes, as
                // before the capability (and so do the others: a duplicate read is harmless).
                val applied = outcomes.filterIsInstance<RaftSyncInboxRowsOutcome.Applied>()
                if (applied.isEmpty() || applied.size != outcomes.size) return RaftSyncInboxRowsOutcome.NotApplied
                // #proj-mobile:#163: every active Activity store applied `inbox_row` in place and
                // published updated unread counts via `onSummaryFact`; do not fire a redundant
                // `GET /api/channels/inbox?limit=1` summary poll here.
                return RaftSyncInboxRowsOutcome.Applied(applied.map { it.settledIds }.reduce { a, b -> a intersect b })
            }

            override fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome {
                val activeScopes = scopesForServer(serverId).toSet()
                // Every store showing the server applies it; one that can't tell reads the lists (all of them).
                val outcomes =
                    homeActivityStores.entries()
                        .filter { (scope, _) -> scope in activeScopes }
                        .map { (_, store) -> store.applyRemoteDone(channelId, doneFrontierSeq) }
                return when {
                    outcomes.isEmpty() || ActivityRemoteDoneOutcome.CannotTell in outcomes -> ActivityRemoteDoneOutcome.CannotTell
                    ActivityRemoteDoneOutcome.KeptNewerActivity in outcomes -> ActivityRemoteDoneOutcome.KeptNewerActivity
                    else -> ActivityRemoteDoneOutcome.Removed
                }
            }

            override suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String): Boolean =
                syncServerMembersStores(serverId)
                    .map { store -> store.refreshLoadedChannelMembers(ServerId(serverId), channelIds, reason) }
                    .all { it }

            override suspend fun reloadRoster(serverId: String, reason: String): RaftSyncSnapshotRead {
                // A forced load joins a roster flight already running, read before this change: retire
                // it first (generation bump, its late answer is dropped), then one fresh forced read
                // through the process roster core that every shell store publishes (Tenny 10-27).
                replyCacheAuthContext.value?.ownerFor(serverId)?.let { owner ->
                    processServerMembersCore.invalidate(owner = owner, reason = reason)
                }
                val results =
                    syncServerMembersStores(serverId).map { store ->
                        runCatchingPreservingCancellation {
                            store.refreshMembers(
                                serverId = ServerId(serverId),
                                priority = DataRequestPriority.Background,
                                force = true,
                                reason = reason
                            )
                        }.getOrNull()
                    }
                // A guest's `servers/:id/members` is a 403: not readable, not a failure (#proj-mobile:#112).
                return results.syncSnapshotRead()
            }

            override suspend fun applyMessageRows(serverId: String, messages: List<Message>) {
                // The open conversation pages of every shell showing the server, as a `message:updated`
                // reaches them (their reducer replaces the message by id; one not held is left alone).
                val activeScopes = scopesForServer(serverId).toSet()
                shellRealtimeDomainEventOwners.entries()
                    .filter { (scope, _) -> scope in activeScopes }
                    .forEach { (_, owner) ->
                        messages.forEach { message -> owner.deliverSyncedMessageUpdate(serverId, MessageUpdated(message)) }
                    }
            }

            override suspend fun reloadMachines(serverId: String, reason: String): RaftSyncSnapshotRead {
                val stores = syncComputersStores(serverId)
                // No Computers store open for the server: nothing in memory to refresh, its first load reads the server.
                if (stores.isEmpty()) return RaftSyncSnapshotRead.Taken
                // A viewer without `viewMachines` gets a 403: not readable, as for the roster (#proj-mobile:#112).
                return stores
                    .map { store ->
                        runCatchingPreservingCancellation { store.reloadMachinesForSync(serverId) }.getOrNull()
                    }
                    .syncSnapshotRead()
            }
        }

    /**
     * Full-list refetches after a socket RE-connect wait one uniform 0–20 s delay per client
     * ([RaftReconnectRefetchJitter]); first connects, sign-in and a server switch fetch at once.
     */
    private val reconnectRefetchJitter: RaftReconnectRefetchJitter by lazy {
        RaftReconnectRefetchJitter(
            scope = threadSendScope,
            logger = sharedInfoLogger("SlockRealtime", "reconnect_refetch_jitter"),
        )
    }

    /**
     * The user switched to [serverId] by bringing up its own shell window. That window's selection
     * may not change (a reused window already holds [serverId]), so the selection collector below
     * does not see the switch: tell the reconnect jitter directly, so the connect that follows
     * refetches at once ([RaftReconnectRefetchDecision.ImmediateAfterSwitch]).
     */
    internal fun noteServerSwitchedToWindow(serverId: String) {
        noteServerSelected(serverId, reason = "server_switch_window")
    }

    /**
     * The user switched to [serverId]. Its socket's connect normally follows and refetches at once
     * ([RaftReconnectRefetchDecision.ImmediateAfterSwitch]); when none follows (the server kept its own
     * socket connected while another server was in front), the jitter's catch-up reads the agents and
     * Computers again, so every list shows the status the agent's profile reads (Hands 46b6cebe).
     */
    private fun noteServerSelected(serverId: String, reason: String) {
        reconnectRefetchJitter.onServerSelected(serverId, reason = reason) {
            refreshServerLiveStatus(serverId, reason = ServerSwitchCatchUpReason)
        }
    }

    /**
     * `/agents` + the machine list of [serverId] in every store showing it, as a sync-on reconnect
     * does ([PresenceTransportRosterRefetch]): agent presence and machine status are not in the
     * change log. A read this store started repairs a status a missed push left stale.
     */
    private suspend fun refreshServerLiveStatus(
        serverId: String,
        reason: String,
        coalesceFreshWithin: Duration = Duration.ZERO,
    ) {
        coroutineScope {
            launch { refreshMachinesForSync(serverId, reason = reason, coalesceFreshWithin = coalesceFreshWithin) }
            serverMembersStoresShowing(serverId).forEach { store ->
                launch {
                    store.refreshAgents(
                        serverId = ServerId(serverId),
                        reason = reason,
                        coalesceFreshWithin = coalesceFreshWithin,
                    )
                }
            }
        }
    }

    /**
     * Web's 10-minute status reconcile while the app stays in front ([RaftAgentStatusReconcile]):
     * the same `/agents` + machine read, for each server on screen whose socket has connected.
     */
    private val agentStatusReconcile: RaftAgentStatusReconcile by lazy {
        RaftAgentStatusReconcile(
            scope = threadSendScope,
            foreground = RaftStaleReadRecoverySignals.foreground,
            serverIds = {
                selectedServerStores.entries()
                    .mapNotNull { (_, store) ->
                        store.selectedServerId.value?.trim()?.takeIf { it.isNotBlank() && !store.disposed.value }
                    }
                    .distinct()
                    .filter(reconnectRefetchJitter::hasConnected)
            },
            reconcile = { serverId ->
                refreshServerLiveStatus(
                    serverId = serverId,
                    reason = AgentStatusReconcileReason,
                    coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                )
            },
            logger = sharedInfoLogger("SlockRealtime", "agent_status_reconcile"),
        )
    }

    /** The process store and every shell store whose shell selects [serverId]. */
    private fun serverMembersStoresShowing(serverId: String): List<SharedServerMembersStore> {
        val activeScopes = scopesForServer(serverId).toSet()
        return buildList {
            add(serverMembersStore)
            shellServerMembersStores.entries().forEach { (scope, store) ->
                if (scope in activeScopes) add(store)
            }
        }.distinct()
    }

    /**
     * Stores actively showing [serverId] for `machine:status` agent-presence recovery: empty when
     * no non-disposed shell selects [serverId], avoiding background wakes of unselected servers,
     * and skipping the legacy process store when shell stores cover it and it has no loaded roster.
     */
    private fun activeServerMembersStoresForMachineStatus(serverId: String): List<SharedServerMembersStore> {
        val activeScopes = scopesForServer(serverId).toSet()
        if (activeScopes.isEmpty()) return emptyList()
        val shellStores =
            shellServerMembersStores.entries()
                .filter { (scope, _) -> scope in activeScopes }
                .map { it.second }
                .distinct()
        return buildList {
            addAll(shellStores)
            if (shellStores.isEmpty() || serverMembersStore.hasLoadedNonDurableRoster(ServerId(serverId))) {
                add(serverMembersStore)
            }
        }.distinct()
    }

    private val machineStatusAgentRefresher: MachineStatusAgentRefresher by lazy {
        MachineStatusAgentRefresher(
            scope = threadSendScope,
            storesForActiveServer = ::activeServerMembersStoresForMachineStatus,
            logger = sharedInfoLogger("SlockPresence", "machine_status_agents_refresh"),
        )
    }

    private fun loadedOrObservedComputersServerIds(): Set<String> {
        val selectedByScope =
            selectedServerStores.entries()
                .mapNotNull { (scope, selectionStore) ->
                    if (selectionStore.disposed.value) return@mapNotNull null
                    val serverId = selectionStore.selectedServerId.value?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    scope to serverId
                }
                .toMap()
        return shellComputersStores.entries()
            .mapNotNullTo(LinkedHashSet()) { (scope, store) ->
                val serverId = selectedByScope[scope] ?: return@mapNotNullTo null
                serverId.takeIf { store.hasLoadedOrObservedServer(serverId) }
            }
    }

    private val computersLiveRefreshCoordinator: ComputersLiveRefreshCoordinator by lazy {
        ComputersLiveRefreshCoordinator(
            scope = threadSendScope,
            loadedOrObservedServerIds = ::loadedOrObservedComputersServerIds,
            hasLoadedOrObservedServer = { serverId ->
                syncComputersStores(serverId).any { it.hasLoadedOrObservedServer(serverId) }
            },
            hasActiveObservers = { serverId ->
                syncComputersStores(serverId).any { it.hasActiveObservers(serverId) }
            },
            refreshMachines = { serverId, reason, coalesceFreshWithin, notStartedBefore ->
                refreshMachinesForSync(
                    serverId = serverId,
                    reason = reason,
                    coalesceFreshWithin = coalesceFreshWithin,
                    notStartedBefore = notStartedBefore,
                )
            },
            syncChangesPullAllowed = ::syncChangesPullAllowed,
            pullSyncChanges = ::pullSyncChangesSucceeded,
            logger = sharedInfoLogger("SlockComputers", "computers_live_refresh"),
        )
    }

    internal fun onMachineStatusRealtimeEvent(serverId: String) {
        machineStatusAgentRefresher.onMachineStatus(serverId)
    }

    internal fun onMachinesRealtimeEvent(serverId: String, reason: String) {
        computersLiveRefreshCoordinator.onMachinesRealtimeEvent(serverId, reason)
    }

    internal fun onExternalForegroundRefreshStarted(
        serverId: String,
        returnBoundary: kotlin.time.ComparableTimeMark? = null,
    ): Any? =
        computersLiveRefreshCoordinator.onExternalForegroundRefreshStarted(serverId, returnBoundary)

    internal fun onExternalForegroundRefreshCompleted(serverId: String, token: Any?, succeeded: Boolean) {
        computersLiveRefreshCoordinator.onExternalForegroundRefreshCompleted(serverId, token, succeeded)
    }

    private val presenceTransportRosterRefetch: PresenceTransportRosterRefetch by lazy {
        PresenceTransportRosterRefetch(
            jitter = reconnectRefetchJitter,
            // A force caller otherwise joins any already-active pre-connect roster flight. Retire
            // that flight/cache once, before every shell joins the new current-transport request.
            invalidate = { owner, reason -> processServerMembersCore.invalidate(owner = owner, reason = reason) },
            storesForServer = ::serverMembersStoresShowing,
            isCurrentOwner = { owner -> replyCacheAuthContext.value?.ownerFor(owner.serverId) == owner },
            refreshMachines = { serverId -> refreshMachinesForSync(serverId, reason = PresenceTransportRosterRefetch.REASON) },
            refreshMachinesWithCoalesce = { serverId, coalesceFreshWithin, notStartedBefore ->
                refreshMachinesForSync(
                    serverId = serverId,
                    reason = PresenceTransportRosterRefetch.REASON,
                    coalesceFreshWithin = coalesceFreshWithin,
                    notStartedBefore = notStartedBefore,
                )
            },
            logger = sharedInfoLogger("SlockPresence", "presence_transport_rebaseline"),
            warnLogger = sharedWarnLogger("SlockPresence", "presence_transport_rebaseline"),
            retryScope = threadSendScope,
        )
    }

    private fun allServerMembersStores(): List<SharedServerMembersStore> =
        buildList {
            add(serverMembersStore)
            shellServerMembersStores.entries().forEach { (_, store) -> add(store) }
        }.distinct()

    /**
     * Data-layer plan step 3, members follow the presence auth binding:
     * - a retired binding (logout, account or environment switch; never a server switch or a token
     *   rotation, which keep the binding) drops every roster held in memory, so none stays readable
     *   by server id under the successor account;
     * - a newly bound account seeds each shell's selected server from the durable roster, so the
     *   cold-start first frames (Home rows, Members, Thread mentions) paint cached members before
     *   the directory refetch.
     */
    private fun installServerMembersBindingFollower() {
        threadSendScope.launch {
            var previous: ProcessPresenceAuthContext? = null
            accountBinding.collect { binding ->
                runDeferredSyncPulls(binding)
                // Typed (architecture review B, site 3): only a sign-out or another account retires
                // the rosters. Unknown (not read yet) and Corrupt (unreadable session) change nothing.
                val context =
                    when (binding) {
                        is RaftAccountBinding.SignedIn -> binding.account
                        RaftAccountBinding.SignedOut -> null
                        RaftAccountBinding.Unknown, is RaftAccountBinding.Corrupt -> return@collect
                    }
                val before = previous
                previous = context
                // Step 4: Home rows staged before this binding existed (cold start) are applied
                // now, for this environment + account only.
                if (context != null) {
                    shellConversationIdentityHubs.entries().forEach { (_, hub) -> hub.applyPendingCandidatesFor(context) }
                }
                val sameBinding =
                    before != null && context != null &&
                        before.environmentId == context.environmentId &&
                        before.accountId == context.accountId &&
                        before.writeGeneration == context.writeGeneration
                if (sameBinding) return@collect
                if (before != null) {
                    // Sign-out, account or environment switch: nothing waiting may run for the old
                    // binding, and the successor's first connects fetch at once.
                    reconnectRefetchJitter.reset(reason = if (context == null) "signed_out" else "binding_changed")
                    machinesSyncRefreshCoalescer.invalidateAll()
                    machineStatusAgentRefresher.cancelAll()
                    computersLiveRefreshCoordinator.cancelAll()
                    syncTransientRetry.cancelAll(reason = if (context == null) "signed_out" else "binding_changed")
                    syncRetryAfter.cancelAll(reason = if (context == null) "signed_out" else "binding_changed")
                    syncRealtimePull.cancelAll(reason = if (context == null) "signed_out" else "binding_changed")
                    allServerMembersStores().forEach { store ->
                        store.retireAccountData(
                            successorEnvironmentId = context?.environmentId,
                            successorAccountId = context?.accountId,
                            successorWriteGeneration = context?.writeGeneration
                        )
                    }
                    shellPeerReadReceiptStores.entries().forEach { (_, store) ->
                        store.dispatch(PeerReadReceiptIntent.BindOwner(null))
                    }
                }
                if (context != null) {
                    val selected =
                        selectedServerStores.entries()
                            .mapNotNull { (scope, store) ->
                                store.selectedServerId.value?.trim()?.takeIf { it.isNotBlank() }?.let { scope to it }
                            }
                    selected.forEach { (scope, serverId) ->
                        listOf(serverMembersStore, serverMembersStore(scope)).distinct().forEach { store ->
                            launch {
                                try {
                                    store.hydrateFromDurable(ServerId(serverId))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Throwable) {
                                    sharedWarnLogger("SlockServerMembers", "server_members_durable")(
                                        "seed_failed type=${error::class.simpleName ?: "unknown"}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private var authSessionClearedSubscription: RealtimeSubscription? = null
    private var authSessionClearLogger: ((String) -> Unit)? = null
    private val activeAppShellPagerOwner = AppShellActivePagerOwner()
    private val serverThemeFollower =
        RaftServerThemeFollower(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            apply = RaftAppAppearanceRuntime::applyServerThemeLook,
        )
    private val processRealtimeAuthorityFlow =
        realtimeAuthorityProvider.authorityFlow(ProcessRealtimeAuthorityScopeKey)

    /**
     * `server:features-changed` and every socket (re)connect refetch that Server's whole flag set
     * after a random 0-10 s ([ServerFeatureFlagChangeRefresher]); a failed refetch keeps the values.
     */
    private val serverFeatureFlagChangeRefresher: ServerFeatureFlagChangeRefresher by lazy {
        ServerFeatureFlagChangeRefresher(
            scope = threadSendScope,
            refetch = { serverId ->
                serverFeatureFlags.prefetch(
                    serverId = serverId,
                    evaluationContext = updateNetworkKmmRecoveryEnvironment(),
                    forceRefresh = true,
                )
            },
            logger = sharedInfoLogger("SlockFeatureFlags", "feature_flags"),
        )
    }

    /** The agents whose detailed `agent:activity` the open screens want, per server socket. */
    val agentActivitySubscriptions: AgentActivitySubscriptions by lazy {
        AgentActivitySubscriptions(
            scope = threadSendScope,
            send = { serverId, subscribe, agentIds ->
                RaftRealtimeEvents(eventBus).postAgentActivitySubscription(serverId, subscribe, agentIds)
            },
        )
    }

    /**
     * Who is working right now on each server (Live agent activity), from the presence facts and
     * members the shell's [serverMembersStore] already has; one per shell, like that store.
     */
    fun liveAgentActivity(scope: RaftShellStoreScope): SharedLiveAgentActivityStore =
        liveAgentActivityStores.getOrPut(scope) {
            val members = serverMembersStore(scope)
            SharedLiveAgentActivityStore(
                scope = threadSendScope,
                workingAgents = { serverId -> members.workingAgentsFlow(build.raft.app.model.ServerId(serverId)) },
            )
        }

    /**
     * The signed-in person's unread feedback replies summed over every Hands app (slock #14722,
     * #14732): the Settings and Feedback row dot. It holds each app's total from the merged list's
     * first page (`unread_by_source`, read on start / sign-in and every realtime (re)connect), and a
     * push or a ticket read replaces one app's ([RaftFeedbackUnreadStore]).
     */
    internal val feedbackUnread: RaftFeedbackUnreadStore by lazy {
        val logger = sharedInfoLogger("SlockFeedbackUnread")
        val clientKind = build.raft.app.raftFeedbackWebViewClientKind()
        val repository = NetworkSettingsFeedbackTicketsRepository(networkClient, clientKind, logger)
        RaftFeedbackUnreadStore(
            clientKind = clientKind,
            scope = threadSendScope,
            readTotal = repository::unreadTotal,
            logger = logger,
        )
    }

    /**
     * Missed `appearance:updated` notices are not replayed: every socket (re)connect and every
     * return to the foreground reads the server's appearance again ([ServerAppearanceCatchUp]).
     */
    private val serverAppearanceCatchUp: ServerAppearanceCatchUp by lazy {
        val repository = NetworkServerAppearanceRepository(networkClient, authStore)
        ServerAppearanceCatchUp(
            scope = threadSendScope,
            load = { serverId -> repository.load(serverId) },
            logger = sharedInfoLogger("SlockServerTheme", "server_appearance_catch_up"),
        )
    }

    /**
     * The wide rail's tab order saved on the account (slock #10279): seeded by the session's
     * `GET auth/me`, read again on a return to the foreground while the rail shows, saved by a drop.
     */
    val railTabOrderStore: SharedRailTabOrderStore by lazy {
        SharedRailTabOrderStore(
            dataSource = NetworkRailTabOrderDataSource(networkClient),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            logger = sharedInfoLogger("SlockRailTabOrder", "rail_tab_order"),
        )
    }

    /**
     * Whether the assistant's otter shows over every page: an account setting (`auth/me`
     * `assistantOtterVisible`), read once per account by the otter or the owner's section.
     */
    val assistantOtterPrefsStore: SharedAssistantOtterPrefsStore by lazy {
        SharedAssistantOtterPrefsStore(
            dataSource = NetworkAssistantOtterPrefsDataSource(networkClient),
            scope = threadSendScope,
            logger = sharedInfoLogger("SlockAssistantOtterPrefs"),
        )
    }

    /** The current API environment's id: the feature-flag evaluation context. */
    private fun updateNetworkKmmRecoveryEnvironment(): String {
        val environment = apiEnvironmentStore.current()
        return raftNetworkKmmRecoveryEnvironmentId(
            environmentKey = environment.key,
            apiBaseUrl = environment.apiBaseUrl
        )
    }

    /**
     * Canonical projection gateway injected into [RealtimeEventMapper]. It decodes each message frame ONCE,
     * runs the shadow-compare over that decode and emits divergence diagnostics without touching the store,
     * then returns the decoded carry for the shadow fold+persist lane. A provider-level singleton so all
     * mapper instances share one gateway.
     */
    val messageShadowObserver: MessageShadowObserver =
        SyncCoreMessageShadowObserver(
            diagnosticsLogger = sharedInfoLogger("SyncCoreShadow", "sync_core_shadow"),
        )

    /**
     * One ingest for every owner (message storage Phase 3a): its ordered worker and duplicate-delivery memory span
     * the process and shell entries. Declared before `init`, which installs the gateway that reads it.
     */
    private val canonicalMessageV2ResumeIngest: CanonicalMessageV2ResumeIngest by lazy {
        CanonicalMessageV2ResumeIngest(
            tracker = canonicalMessageV2Canary,
            generationEngine = canonicalMessageV2GenerationEngine,
            // Lines already start with `canonical_v2_resume`.
            logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2Resume"),
            dispatcher = RaftMessageDbDispatcher,
        )
    }

    private val canonicalMessageV2RealtimeIngest: CanonicalMessageV2RealtimeIngest by lazy {
        CanonicalMessageV2RealtimeIngest(
            tracker = canonicalMessageV2Canary,
            generationEngine = canonicalMessageV2GenerationEngine,
            // Lines already start with `canonical_v2_realtime`.
            logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2Realtime"),
            onConversationPoisoned = { partition, conversationId, cause ->
                threadPayloadStore.markConversationStale(
                    serverId = partition.serverId,
                    threadId = conversationId,
                    ownerScopeId =
                        sharedThreadTimelineOwnerScopeId(partition.environmentId, partition.principalId, partition.serverId),
                    cause = "realtime_poisoned:$cause",
                )
            },
        )
    }

    /**
     * 1.30 web-aligned Activity in-place + per-row position path (#proj-mobile:#163, Tenny cb559277):
     * default ON via `mobile_activity_inplace_v1`, which is the sole 1.30 off-switch.
     */
    private fun inboxInPlaceEnabled(serverId: String): Boolean =
        serverFeatureFlags.isActivityInPlaceEnabled(ServerId(serverId))

    /** Per-row position rule (#triage:#244 / #proj-mobile:#163); ships default-on together with [inboxInPlaceEnabled] in 1.30. */
    private fun inboxRowPositionEnabled(serverId: String): Boolean =
        serverFeatureFlags.isActivityInPlaceEnabled(ServerId(serverId))

    /** `sync_inbox_rows_v0` enabled and incremental sync allowed for [serverId]. */
    private fun syncInboxRowsEnabled(serverId: String): Boolean =
        syncChangesPullAllowed(serverId) &&
            serverFeatureFlags.isEnabled(ServerId(serverId), SYNC_INBOX_ROWS_FEATURE_FLAG_KEY)

    /** Local Home + ThreadPayload metadata for unlisted `thread:updated` frames (#proj-mobile:#163). */
    private fun knownThreadContextForActivity(
        scope: RaftShellStoreScope,
        serverId: String,
        threadChannelId: String,
        parentMessageId: String,
        parentChannelId: String?,
    ): ActivityUnlistedThreadContext {
        val homeSnapshot =
            (homeStores.get(scope)?.state?.value?.content as? SharedHomeContentState.Content)
                ?.snapshot
                ?.takeIf { it.server.id.value == serverId }
        val parentRow =
            if (homeSnapshot != null && !parentChannelId.isNullOrBlank()) {
                homeSnapshot.channels.firstOrNull { it.channelId.value == parentChannelId }
                    ?: homeSnapshot.directMessages.firstOrNull { it.channelId.value == parentChannelId }
            } else {
                null
            }
        val parentChannel =
            parentRow?.let { row ->
                val type =
                    row.channelType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                        ?: if (row.kind == ConversationKind.DirectMessage) "dm" else "channel"
                ActivityKnownParentChannel(
                    id = row.channelId?.value ?: row.threadId.value,
                    name = row.name,
                    type = type,
                    archived = row.archived,
                )
            }
        val cachedParent =
            if (!parentChannelId.isNullOrBlank()) {
                threadPayloadStore.cachedParentMessageForActivity(serverId, parentChannelId, parentMessageId)
            } else {
                null
            }
        val followedByHome = homeSnapshot?.followedThreadUnread?.containsKey(threadChannelId) == true
        val followedByPayload = cachedParent?.second == true
        val follow =
            if (followedByHome || followedByPayload) {
                ActivityKnownThreadFollow.Followed
            } else {
                ActivityKnownThreadFollow.Unknown
            }
        return ActivityUnlistedThreadContext(
            follow = follow,
            parentChannel = parentChannel,
            parentMessage = cachedParent?.first,
        )
    }

    init {
        // Minimum supported build: back in the foreground, read the gate flag again.
        RaftMinBuildGate.flagRefetch = { serverId -> serverFeatureFlagChangeRefresher.onForeground(serverId) }
        // Inbox refresh step 4, rule 6: with the flag on, a server whose Activity All list is loaded
        // takes its badge from that list instead of the summary read.
        // `inbox_row_position_v1`: while that list is shown it is the badge's only writer (no summary
        // read for any reason; the shown list reads itself on the return and on a refresh).
        activityUnreadSummaryStore.deriveBadgeFromList(
            derives = { owner ->
                inboxInPlaceEnabled(owner.serverId) &&
                    homeActivityStores.entries().any { (_, store) -> store.state.value.derivesBadgeFor(owner) }
            },
            owns = { owner ->
                inboxInPlaceEnabled(owner.serverId) &&
                    inboxRowPositionEnabled(owner.serverId) &&
                    activityListOwnsBadge(
                        owner = owner,
                        stores = homeActivityStores.entries(),
                        liveScopes = liveActivityRealtimeScopes(owner.serverId)
                    )
            }
        )
        // Message storage Phase 0a: the V1 shadow writers are gone and nothing ever read their rows, so drop
        // every surviving `sync-core.messages.v1` row once per start (idempotent; V2 and read-state untouched).
        threadSendScope.launch {
            runCatchingPreservingCancellation { purgeSyncCoreMessagesV1Rows(sqlDatabase) }
                .onFailure { throwable ->
                    sharedErrorLogger("SyncCoreV1Purge", "sync_core_v1_purge")(
                        "sync_core_v1_purge_failed error=${throwable::class.simpleName ?: "unknown"}",
                    )
                }
        }
        val processAuthTerminalGate = AuthSessionTerminalRouteGate()
        val processAuthTerminalSource =
            requireNotNull(authStore.authSessionTerminalSourceOrNull()) {
                "Production auth store must expose terminal revision authority"
            }
        suspend fun commitProcessAuthTerminalCleanup(expectedGeneration: Long) {
            val selectionReceipts = selectedServerStores.captureActiveServerSelectionReceipts()
            val committed =
                processAuthTerminalSource.runIfAuthSessionTerminalRevision(expectedGeneration) {
                    // Sign-out keeps the account's stored data (keyed by account; owner decision
                    // 2026-10-27) and resets every in-memory copy: see AccountLocalData.
                    // Fence deferred persist writes first so no in-flight write lands after sign-out.
                    (activityPersistSlice as? RaftScrollDeferredPersistControl)?.discardPending()
                    (homeIdentityPersistSlice as? RaftScrollDeferredPersistControl)?.discardPending()
                    (homeRowHintPersistSlice as? RaftScrollDeferredPersistControl)?.discardPending()
                    accountLocalData.onSignOut()
                    // The signed-out account's attachment uploads and pending Download Center taps stop with it.
                    composerDraftUploads.cancelAll()
                    build.raft.app.RaftDownloadCenterLaunchInbox.Process.clear()
                    // Pending-send rows are retired by the identity-bound
                    // processPresenceBindingTransition hook.  This terminal
                    // route deliberately has no captured environment/principal
                    // authority, so it must not perform a database-wide sweep:
                    // a late terminal event could otherwise erase another
                    // account, environment, or successor generation.
                }
            if (committed) {
                // task #329: memoized per-member read flows are owner-bound recipes;
                // drop them on auth-terminal cleanup so a successor generation never
                // inherits cross-account member read edges.
                serverMembersStore.clearScopedReadCaches()
                shellServerMembersStores.entries().forEach { (_, store) ->
                    store.clearScopedReadCaches()
                }
                shellConversationIdentityHubs.entries().forEach { (_, hub) -> hub.clear() }
                shellPeerReadReceiptStores.entries().forEach { (_, store) ->
                    store.dispatch(PeerReadReceiptIntent.BindOwner(null))
                }
                clearActiveServerSelectionsIfUnchanged(selectionReceipts)
                runCatchingPreservingCancellation { build.raft.app.raftClearWebViewAuthStorage() }
            }
        }
        updateNetworkKmmRecoveryEnvironment()
        installServerMembersBindingFollower()
        // Incremental sync: a foreground return (app lifecycle, not page visibility) pulls the
        // selected server's changes once (flag off: the shell's lifecycle path only, unchanged).
        foregroundReconciler.followAppLifecycle(
            appForeground = RaftStaleReadRecoverySignals.foreground,
            coversServer = ::syncChangesPullAllowed,
        )
        // Agent status pushes are best effort: read agents and Computers again every 10 minutes
        // while the app stays in front, as the web does (Hands c76f162d, 46b6cebe).
        agentStatusReconcile.start()
        // Computers single-source freshness (foreground, network-restored, active-observer reconnect):
        // shares machinesSyncRefreshCoalescer with foregroundReconciler so one return reads /machines once.
        computersLiveRefreshCoordinator.startFreshness(RaftFreshnessSignal.events)
        // Channel members freshness for actively observed slices (#wg-mobile-feedback:#274 Phase 1).
        serverMembersStore.startChannelMembersFreshness(threadSendScope)
        // A theme changed on another device while backgrounded: read the selected server's again.
        serverAppearanceCatchUp.followAppLifecycle(RaftStaleReadRecoverySignals.foreground) {
            authStore.readSession()?.currentServerId
        }
        // Another device may have reordered the rail's tabs while backgrounded: read them again.
        railTabOrderStore.followAppLifecycle(RaftStaleReadRecoverySignals.foreground)
        // App start: read this environment's client config (and again on each environment change).
        clientConfigStore.start()
        // The feedback unread dot: read for whoever is signed in (now and after each sign-in); none signed out.
        feedbackUnread.follow(
            raftStoredSessionFlow(authStore.authSessionEvents) { authStore.readSession() }.map { it?.user?.id }
        )
        // Attach canonical/reaction shadow mutation to the dedicated process
        // owner. Shell owners are projection-only and never repeat this work.
        realtimeDomainEventOwner.installMessageShadowFoldLane(buildMessageShadowFoldLane())
        realtimeDomainEventOwner.installReactionShadowLane(buildReactionShadowLane())
        realtimeDomainEventOwner.installCanonicalMessageV2OwnerGateway(
            buildCanonicalMessageV2OwnerGateway(),
        )
        // Phase 3b: only the process owner saves the realtime applied seq, so only it stores resume replays.
        realtimeDomainEventOwner.installCanonicalMessageV2ResumeGateway(
            RealtimeResumeCanonicalMessageV2Gateway(canonicalMessageV2ResumeIngest),
        )
        realtimeAuthorityProvider.start(threadSendScope)
        ProcessRealtimeIngressCoordinator(
            events = RaftRealtimeEvents(eventBus),
            authorityProvider = realtimeAuthorityProvider,
            authorityFlow = processRealtimeAuthorityFlow,
            domainOwner = realtimeDomainEventOwner,
            authStore = authStore,
            environmentStore = apiEnvironmentStore,
            mapper = RealtimeEventMapper(messageShadowObserver = messageShadowObserver),
            nativeHost = BridgeProcessRealtimeNativeHost(host),
            scope = threadSendScope,
            onPresenceTransportConnected = ::handlePresenceTransportConnected,
            // Data-layer plan, Tenny rule 2: removed from (or deleted) server -> drop that server's
            // timeline partition, in memory and on disk. Called only after the terminal mutation was
            // accepted under the then-current grant (the grant itself is revoked by that mutation).
            onServerAccessLost = { grant ->
                reconnectRefetchJitter.dropServer(grant.serverId, reason = "server_access_lost")
                machinesSyncRefreshCoalescer.invalidate(grant.serverId)
                machineStatusAgentRefresher.cancel(grant.serverId)
                computersLiveRefreshCoordinator.cancel(grant.serverId)
                // Minimum supported build: that server's gate reading no longer counts.
                RaftMinBuildGate.onServerLeft(ServerId(grant.serverId))
                threadPayloadStore.dropServer(grant.serverId)
                composerDraftUploads.dropServer(grant.serverId)
                readReceiptPendingStore.dropServer(grant.serverId, replyCacheAuthContext.value?.accountId)
                // Step 3: the server's members (memory in every shell, durable row, avatar refs).
                allServerMembersStores().forEach { store -> store.dropServer(ServerId(grant.serverId)) }
                // Step 4: the server's conversation identities (names, avatars, DM peers) in every shell.
                shellConversationIdentityHubs.entries().forEach { (_, hub) -> hub.dropServer(grant.serverId) }
                shellPeerReadReceiptStores.entries().forEach { (_, store) ->
                    if (store.state.value.owner?.serverId == grant.serverId) {
                        store.dispatch(PeerReadReceiptIntent.BindOwner(null))
                    }
                }
                // Step 5: machines, joint connections and agent details of that server.
                val lostAccountId = replyCacheAuthContext.value?.accountId
                allComputersStores().forEach { store -> store.dropServer(grant.serverId) }
                if (allComputersStores().isEmpty()) computersDurableStore.evictServer(grant.serverId, lostAccountId)
                jointConnectionsCache.dropServer(grant.serverId, lostAccountId)
                agentDetailCache.dropServer(grant.serverId, lostAccountId)
                // Tasks lists leave memory before the offline wipe below, so the offline mirror cannot store them again.
                tasksDataStore.dropServer(grant.serverId)
                syncCursorStore.clearServer(grant.serverId, lostAccountId)
                syncTransientRetry.cancelWhere(reason = "server_access_lost") { it.serverId == grant.serverId }
                syncRetryAfter.cancelWhere(reason = "server_access_lost") { it.serverId == grant.serverId }
                syncRealtimePull.cancelWhere(reason = "server_access_lost") { it.serverId == grant.serverId }
                // #45: that server's recent searches and frequent people / channels.
                searchPersonalizationRepository.dropServer(grant.serverId, lostAccountId, nowMillis = defaultNowMillis())
                // #294: drop lost-server Artifact summaries, App link previews, and persisted Artifact screenshots
                // (using the shared disk cache instance so in-flight writes are fenced even before store init).
                if (artifactSummaryStoreLazy.isInitialized()) {
                    artifactSummaryStore.dropServer(grant.serverId)
                }
                if (artifactFollowStoreLazy.isInitialized()) {
                    artifactFollowStore.dropServer(grant.serverId)
                }
                if (appLinkPreviewStoreLazy.isInitialized()) {
                    appLinkPreviewStore.dropServer(grant.serverId)
                }
                if (installedAppOriginsCacheLazy.isInitialized()) {
                    installedAppOriginsCache.dropServer(grant.serverId)
                }
                val lostEnvironmentId = replyCacheAuthContext.value?.environmentId ?: apiEnvironmentStore.current().apiBaseUrl
                if (artifactScreenshotStoreLazy.isInitialized()) {
                    artifactScreenshotStore.dropServer(grant.serverId, lostAccountId, lostEnvironmentId)
                } else {
                    artifactScreenshotDiskCache.clearServer(grant.serverId, lostAccountId, lostEnvironmentId)
                }
                // Offline resources, Home hints, Activity pages, notification preferences, reply windows
                // and the stored messages of that server (they outlive sign-out, so they must go here).
                // Fence deferred scroll-coalesced persist slices before deleting from disk so an
                // in-flight or queued flush cannot resurrect the lost server (#wg-mobile-feedback:#271).
                (activityPersistSlice as? RaftScrollDeferredPersistControl)?.dropServer(grant.serverId, lostAccountId)
                (homeIdentityPersistSlice as? RaftScrollDeferredPersistControl)?.dropServer(grant.serverId, lostAccountId)
                (homeRowHintPersistSlice as? RaftScrollDeferredPersistControl)?.dropServer(grant.serverId, lostAccountId)
                accountLocalData.onServerAccessLost(
                    serverId = grant.serverId,
                    accountId = lostAccountId,
                    messagePartition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID),
                )
            },
            // Task #168: the durable message canary records which connection has its member rooms joined;
            // only a current grant may record it (as the transport epoch, #2991).
            onRoomsJoined = { grant, transportEpoch ->
                sharedInfoLogger("SlockRealtime", "rooms_joined")(
                    "serverId=${grant.serverId} transportEpoch=$transportEpoch"
                )
                realtimeAuthorityProvider.runIfCurrentGrant(grant) {
                    canonicalMessageV2Canary.noteRoomsJoined(
                        grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID),
                        transportEpoch,
                    )
                    // Task #164: realtime is back; a stale unread / Activity read tries again now.
                    RaftStaleReadRecoverySignals.onRealtimeRoomsJoined()
                    // #198: pages and stores of this server re-check what they show; after a drop the app
                    // did not cause (a deploy), some of them spread that out.
                    RaftFreshnessSignal.onReconnected(
                        grant.serverId,
                        transportEpoch,
                        afterDrop = RaftRealtimeConnectionCauses.connectedAfterDrop(grant.serverId),
                    )
                    // Missed `server:features-changed` notices are not replayed: refetch on every (re)connect.
                    serverFeatureFlagChangeRefresher.onReconnected(ServerId(grant.serverId))
                    // Nor are `appearance:updated` ones: a theme changed elsewhere while offline.
                    serverAppearanceCatchUp.onReconnected(ServerId(grant.serverId))
                    // Nor are the feedback unread totals: read this app's again.
                    feedbackUnread.onReconnected()
                    // Reads made offline go out now.
                    processReadReceiptCoordinator().replayPendingReads()
                }
            },
            onAccepted = { grant, event ->
                if (event is ServerFeaturesChanged && event.serverId.value == grant.serverId) {
                    serverFeatureFlagChangeRefresher.onFeaturesChanged(event.serverId, event.keys)
                }
                // web: `activity:changed` drops that server's kept object summaries (invalidateObjectSummaries).
                if (event is build.raft.app.model.ActivityChanged && artifactSummaryStoreLazy.isInitialized()) {
                    artifactSummaryStore.invalidateServer(event.serverId.value)
                }
            },
            // Task #14c: the server's answer to a `join:channel` (a reply thread's room): the canary may make a
            // later enter of that thread continuous. Only a current grant may record it.
            onChannelJoinAck = { grant, transportEpoch, channelId, joined ->
                sharedInfoLogger("SlockRealtime", "channel_join_ack")(
                    "serverId=${grant.serverId} transportEpoch=$transportEpoch channelId=$channelId joined=$joined"
                )
                realtimeAuthorityProvider.runIfCurrentGrant(grant) {
                    canonicalMessageV2Canary.noteChannelJoinAck(
                        grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID),
                        channelId,
                        transportEpoch,
                        joined,
                    )
                    if (joined) {
                        channelJoinedSignals.publish(grant.serverId, channelId)
                    }
                }
            },
            // Phase 3: with multi-session realtime on, token rotation keeps every window's server.
            multiSessionEnabled = { build.raft.app.raftMultiServerRealtimeEnabled() },
        ).start()
        RaftHostNetworkEvents(eventBus).addAuthoritativeOnlineListener {
            // Task #150: a new default network (e.g. a VPN switched on/off) may resolve the host
            // again; stop short-circuiting requests at once instead of waiting for the next probe.
            networkReachabilityGates.onNetworkAvailable()
            // #198: reads that failed while offline may succeed now; pages re-check what they show.
            RaftFreshnessSignal.onNetworkRestored()
            threadSendScope.launch {
                avatarCacheRuntime.recoverCurrentOwnerAfterNetworkAvailable()
            }
            processReadReceiptCoordinator().replayPendingReads()
        }
        // #proj-mobile:#172: time the OS reports no network is offline_ms, not disconnected_ms.
        RaftHostNetworkEvents(eventBus).addListener { signal -> RaftRealtimeSessionHealth.onHostNetwork(signal.online) }
        // Reads a previous process could not send. A session not restored yet keeps them for the
        // first realtime connect.
        threadSendScope.launch { processReadReceiptCoordinator().replayPendingReads() }
        threadSendScope.launch {
            realtimeAuthorityProvider.registerScope(
                scopeKey = ProcessRealtimeAuthorityScopeKey,
                registrationId = ProcessRealtimeAuthorityRegistrationId,
            )
        }
        threadSendScope.launch {
            authStore.sessionInvalidations.collect {
                val receipts = selectedServerStores.captureActiveServerSelectionReceipts()
                val snapshot = processAuthTerminalSource.readAuthSessionTerminalSnapshot()
                val stillCurrent =
                    processAuthTerminalSource.runIfAuthSessionTerminalRevision(snapshot.generation) {}
                if (stillCurrent) {
                    shellConversationIdentityHubs.entries().forEach { (_, hub) -> hub.clear() }
                    clearActiveServerSelectionsIfUnchanged(receipts)
                }
                updateNetworkKmmRecoveryEnvironment()
            }
        }
        threadSendScope.launch {
            processAuthTerminalSource.authSessionTerminalEvents
                .onSubscription {
                    val snapshot = processAuthTerminalSource.readAuthSessionTerminalSnapshot()
                    if (processAuthTerminalGate.reconcile(snapshot)) {
                        commitProcessAuthTerminalCleanup(snapshot.generation)
                    }
                }
                .collect { event ->
                if (processAuthTerminalGate.admit(event) == null) {
                    return@collect
                }
                commitProcessAuthTerminalCleanup(event.generation)
            }
        }
    }

    /** Legacy/unowned shadow lane retained on the process owner. */
    private fun buildMessageShadowFoldLane(): SyncCoreMessageShadowFoldLane =
        SyncCoreMessageShadowFoldLane(
            logger = sharedInfoLogger("SyncCoreT0Shadow", "sync_core_t0_shadow"),
        )

    private fun buildReactionShadowLane(): SyncCoreReactionShadowLane =
        SyncCoreReactionShadowLane(
            logger = sharedInfoLogger("SyncCoreReactionShadow", "sync_core_reaction_shadow"),
        )

    /**
     * Task #168 step 3: the realtime path never requests the network; one gateway shape for every owner. Message
     * storage Phase 3a: every frame is stored (the canary only decides the head-segment claim).
     */
    private fun buildCanonicalMessageV2OwnerGateway(): CanonicalMessageV2OwnerGateway =
        RealtimeIngestCanonicalMessageV2OwnerGateway(ingest = canonicalMessageV2RealtimeIngest, canary = canonicalMessageV2Canary)


    /**
     * Task #168 step 3: whether the user is known NOT to be a member of (serverId, channelId), read from every
     * shell's conversation directory (the same projection #2994's room-leave rule reads). A row that says
     * joined anywhere, a channel the user joined from a page in any shell, or no row at all = member.
     */
    internal fun isKnownNonMemberConversation(serverId: String, channelId: String): Boolean {
        if (shellRealtimeRoomLeaseRegistries.entries().any { (_, registry) -> registry.isJoinedByUser(serverId, channelId) }) {
            return false
        }
        val projections = shellConversationIdentityHubs.entries().mapNotNull { (_, hub) ->
            hub.resolveProjection(ServerId(serverId), channelId)
        }
        return projections.isNotEmpty() && projections.none { it.joined }
    }

    val conversationIdentityProvider: SharedConversationIdentityProvider?
        get() = conversationIdentityProvider(RaftShellStoreScope.Legacy)

    val sharedHomeStore: SharedHomeStore?
        get() = sharedHomeStore(RaftShellStoreScope.Legacy)

    fun conversationIdentityProvider(scope: RaftShellStoreScope): SharedConversationIdentityProvider? =
        conversationIdentityHub(scope)

    private fun conversationIdentityHub(scope: RaftShellStoreScope): SharedConversationIdentityHub =
        shellConversationIdentityHubs.getOrPut(scope) {
            SharedConversationIdentityHub(
                diagnosticsLogger =
                    sharedInfoLogger("SlockConversationDirectory", "conversation_directory_hub")
            )
        }

    internal suspend fun publishCanonicalConversationIdentity(
        scope: RaftShellStoreScope,
        provenance: ReplyCacheProvenance,
        detail: build.raft.app.model.ConversationSummary,
    ): Boolean {
        val current = captureThreadReplyCacheIdentity(scope) ?: return false
        if (
            current.environmentId != provenance.environmentId ||
            current.principalId != provenance.principalId ||
            current.serverId != provenance.serverId ||
            current.authGeneration != provenance.authGeneration ||
            current.selectionGeneration != provenance.selectionGeneration
        ) {
            sharedWarnLogger("SlockConversationDirectory", "conversation_directory_canonical_drop")(
                "reason=authority_changed serverId=${provenance.serverId}"
            )
            return false
        }
        val serverId = ServerId(provenance.serverId)
        // Agent Lounge v0: a Lounge loaded by id (GET /channels/:id) is known from here on, though no
        // channel list holds it.
        if (!detail.loungeAgentId.isNullOrBlank()) {
            RaftAgentLounges.bindAccount(provenance.principalId)
            RaftAgentLounges.rememberRow(provenance.serverId, detail.toHomeChannelRow())
        }
        val projection = detail.toCanonicalConversationDirectoryProjection(serverId) ?: return false
        val published =
            conversationIdentityHub(scope).publishCanonical(
                authority =
                    SharedConversationIdentityAuthority(
                        environmentId = provenance.environmentId,
                        accountId = provenance.principalId,
                        serverId = provenance.serverId,
                        authGeneration = provenance.authGeneration,
                        selectionGeneration = provenance.selectionGeneration,
                    ),
                projection = projection,
            )
        // The thread data source stores the accepted detail (fenced from its request's start).
        return published
    }

    /**
     * The stored header detail of [channelIds] (offline resource) into this shell's identity hub, for a page
     * opened on a cold start or offline; the network's detail (when it arrives) replaces it, and a detail
     * already there is never overwritten. Returns how many were published.
     */
    internal suspend fun restoreStoredConversationIdentities(
        scope: RaftShellStoreScope,
        serverId: String,
        channelIds: Collection<String>,
    ): Int {
        val identity = captureThreadReplyCacheIdentity(scope) ?: return 0
        if (identity.serverId != serverId.trim()) return 0
        val owner =
            StoredConversationDetailOwner(
                environmentId = identity.environmentId,
                accountId = identity.principalId,
                serverId = identity.serverId,
                authGeneration = identity.authGeneration,
                selectionGeneration = identity.selectionGeneration,
            )
        var published = 0
        for (channelId in channelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val detail = storedConversationDetails.read(owner, channelId) ?: continue
            // The account / selection may have moved while the row was read.
            if (captureThreadReplyCacheIdentity(scope) != identity) return published
            if (storedConversationDetails.restore(owner, channelId, conversationIdentityHub(scope), detail)) {
                published += 1
            }
        }
        return published
    }

    fun sharedHomeStore(scope: RaftShellStoreScope): SharedHomeStore? =
        homeStores.get(scope)

    /** In-memory candidate lookup for pushed pages' first frame (see [RaftKnownEntityResolver]). */
    internal fun knownEntityResolver(scope: RaftShellStoreScope): RaftKnownEntityResolver =
        raftKnownEntityResolver(
            identityHub = { conversationIdentityHub(scope) },
            homeStore = { sharedHomeStore(scope) },
        )

    /**
     * Returns the live shell owner scope for child navigation. A route's
     * initial server is a bootstrap hint and may be stale after an in-process
     * server switch; an existing selected-server store is authoritative.
     */
    internal fun currentNavigationStoreScope(scope: RaftShellStoreScope): RaftShellStoreScope =
        childNavigationStoreScope(scope, selectedServerStores.get(scope))

    /**
     * Synchronous first-frame read of the shell's selected server without creating a selection
     * store: the live selection when the shell has one, else the scope's bootstrap server.
     */
    internal fun currentSelectedServerIdOrNull(scope: RaftShellStoreScope): String? =
        (selectedServerStores.get(scope)?.currentServerId?.value ?: scope.initialServerId)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun selectedServerStore(scope: RaftShellStoreScope): SelectedServerStore =
        selectedServerStores.getOrPut(scope) {
            SelectedServerStore(
                scope = scope,
                selectionEpochStore = selectedServerSelectionEpochStore,
                environmentIdProvider = {
                    apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
                },
                onSelectionChanged = { serverId ->
                }
            ).also { selectionStore ->
                // Prefetch registered server feature flags whenever this shell selects a server, then
                // periodically refresh the same selection so operator variant changes propagate without
                // requiring a server reselect or process restart. collectLatest cancels the old loop before
                // a new selected server can publish into this shell's snapshot path.
                // The collector lives on threadSendScope (not the shell scope), so cancel it when the
                // store is disposed, or it would outlive an evicted shell selection store.
                threadSendScope.launch {
                    val prefetching = launch {
                        selectionStore.currentServerId.collectLatest { serverId ->
                            if (!serverId.isNullOrBlank()) {
                                // A server switch fetches at once: a roster refetch still waiting for
                                // the reconnect jitter of this server runs now, and the socket connect
                                // that follows the switch is not jittered (Hands 81ad5dae).
                                noteServerSelected(serverId, reason = "server_selected")
                                cancelUnselectedSyncPulls()
                                var syncPullRequested = false
                                refreshSelectedServerFeatureFlags {
                                    try {
                                        val environmentId = updateNetworkKmmRecoveryEnvironment()
                                        serverFeatureFlags.prefetch(
                                            serverId = ServerId(serverId),
                                            evaluationContext = environmentId
                                        )
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (error: Throwable) {
                                        sharedWarnLogger("SlockFeatureFlags", "feature_flags")(
                                            "prefetch_failed server=$serverId " +
                                                "reason=${error::class.simpleName ?: "unknown"}"
                                        )
                                    }
                                    // Server select: one incremental pull once this server's flags are read.
                                    if (!syncPullRequested) {
                                        syncPullRequested = true
                                        requestSyncChangesPullWhenBound(serverId, trigger = "server_selected")
                                    }
                                }
                            }
                        }
                    }
                    // The selected server's runtime catalog, for every runtime label in this shell
                    // (its "(deprecated)" is that server's answer): the cached answer at once, then
                    // the server's, once per selection (one request per server in flight).
                    val runtimeCatalog = launch {
                        selectionStore.currentServerId.collectLatest { serverId ->
                            val clean = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return@collectLatest
                            val repository = serverRuntimeCatalogRepository(scope)
                            repository.cached(clean)
                            repository.load(clean, DataRequestPriority.Background)
                        }
                    }
                    // The selected server's model-label catalog (slock #15780, web watchModelLabelCatalog):
                    // daemon-reported model labels per machine/runtime, shared across author rows, profiles,
                    // preview cards, members list, and computers pages.
                    val modelLabelCatalog = launch {
                        selectionStore.currentServerId.collectLatest { serverId ->
                            val clean = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return@collectLatest
                            serverModelLabelCatalogRepository(scope).load(clean, DataRequestPriority.Background)
                        }
                    }
                    selectionStore.disposed.first { it }
                    prefetching.cancel()
                    runtimeCatalog.cancel()
                    modelLabelCatalog.cancel()
                }
            }
        }

    /**
     * The shell scopes showing [serverId] whose AppShell has its realtime sinks registered (a mounted,
     * ready window): only their Activity list gets the live events that keep a hidden list's badge.
     */
    private fun liveActivityRealtimeScopes(serverId: String): Set<RaftShellStoreScope> =
        scopesForServer(serverId).filterTo(mutableSetOf()) { scope ->
            shellRealtimeDomainEventOwners.get(scope)?.hasGuardedSinks == true
        }

    fun scopesForServer(serverId: String): List<RaftShellStoreScope> {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        return selectedServerStores.entries().mapNotNull { (scope, selectionStore) ->
            scope.takeIf { selectionStore.isSelected(normalizedServerId) && !selectionStore.disposed.value }
        }
    }

    fun authStore(scope: RaftShellStoreScope): RaftAuthSessionStore =
        shellAuthStores.getOrPut(scope) {
            RaftShellAuthSessionStore(
                delegate = authStore,
                selectedServerStore = selectedServerStore(scope),
                eventScope = threadSendScope,
                onProcessSessionCleared = {
                    // Sign-out: held `local_read` lowerings go now, as lowerings (slock #15197 follow-up).
                    homeActivityStores.entries().forEach { (_, store) -> store.flushHeldListRowsOnSignout() }
                    clearActiveShellServerSelections()
                }
            )
        }

    internal suspend fun captureThreadReplyCacheIdentity(
        scope: RaftShellStoreScope,
    ): ThreadReplyCacheIdentitySnapshot? {
        // start() is UNDISTPATCHED, but an actual host can ask Thread for its first payload before
        // the SharedFlow subscription finishes its initial suspend reconciliation. Only repair the
        // genuinely uninitialized/null binding here: a non-null mismatched environment/session must
        // remain fail-closed throughout the settings environment-change gap.
        if (replyCacheAuthContext.value == null && !realtimeAuthorityProvider.ensureCurrentAuthBinding()) {
            return null
        }
        return ThreadReplyCacheIdentityAuthority(
            sessionStore = authStore,
            selectedServerStore = selectedServerStore(scope),
            authContextProvider = { replyCacheAuthContext.value },
            environmentIdProvider = {
                apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
            },
        ).capture()
    }

    /**
     * Synchronous identity-only owner used only while serializing a child
     * route. The destination still performs the full async session/generation
     * capture before any cache, network, or mutation work.
     */
    internal fun currentAuthenticatedRouteOwner(
        scope: RaftShellStoreScope,
    ): AuthenticatedRouteOwner? {
        val authContext = replyCacheAuthContext.value ?: return null
        val environmentId = apiEnvironmentStore.current().apiBaseUrl.normalizedEnvironmentId()
        if (environmentId.isBlank() || environmentId != authContext.environmentId.normalizedEnvironmentId()) {
            return null
        }
        val selectedServerStore = selectedServerStores.get(scope)
        val serverId =
            if (selectedServerStore != null) {
                selectedServerStore.selectedServerId.value
            } else {
                scope.initialServerId
            } ?: return null
        return AuthenticatedRouteOwner(
            environmentId = environmentId,
            accountId = authContext.accountId,
            serverId = serverId,
        ).normalizedOrNull()
    }

    /**
     * The member's look (theme + decorations) on this shell's selected server, from the stored
     * session: the persisted value at once on a cold start, then every change (server list
     * refresh, appearance-settings save, `appearance:updated`, server switch). Raft Yellow while
     * signed out. Not applied to the UI here.
     */
    fun serverThemeLookFlow(scope: RaftShellStoreScope): Flow<RaftServerThemeLook> =
        raftServerThemeLookFlow(
            sessions = raftStoredSessionFlow(authStore.authSessionEvents) { authStore.readSession() },
            selectedServerId = selectedServerStore(scope).selectedServerId,
        )

    /**
     * The process-level look: the stored session's own current server (the next launch's
     * default), for when no app shell is on screen (cold start before the shell, the login page
     * after a sign-out, which reads Raft Yellow).
     */
    internal fun processServerThemeLookFlow(): Flow<RaftServerThemeLook> =
        raftServerThemeLookFlow(
            sessions = raftStoredSessionFlow(authStore.authSessionEvents) { authStore.readSession() },
            selectedServerId = flowOf(null),
        )

    /**
     * Wires the viewer's `notification_prefs:updated` (server mode) push to a background, single-flight
     * refetch of that server's preference for the grant's account; the repository writes the last-known
     * copy on success, so the copy stays current while no Settings page is open.
     */
    internal fun installServerNotificationPreferencesRealtimeRefresh() {
        val repository =
            RealSettingsRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = sharedInfoLogger("SlockSettingsRepository", "server_notification_realtime_refresh"),
                warningLogger = sharedWarnLogger("SlockSettingsRepository", "server_notification_realtime_refresh"),
                serverNotificationLastKnownStore = settingsServerNotificationLastKnownStore,
            )
        val refresher =
            ServerNotificationPreferencesRealtimeRefresher(
                scope = RaftStoreThreading.newScope(),
                refresh = { principalId, serverId ->
                    repository.refreshServerNotificationPreferences(principalId, serverId)
                },
                logger = sharedInfoLogger("SlockSettingsRealtime", "server_notification_realtime_refresh"),
            )
        realtimeDomainEventOwner.installServerNotificationPreferencesSink(refresher::onEvent)
    }

    /** Wears the process-level look app-wide until an app shell takes over ([followServerTheme]). */
    internal fun followProcessServerTheme() {
        serverThemeFollower.follow(ProcessServerThemeFollowKey, processServerThemeLookFlow().flowOn(Dispatchers.Default))
    }

    /**
     * Wears the look of [scope]'s selected server app-wide from now on (the shell on screen):
     * server switches, sign-out, plan changes and `appearance:updated` follow through the stored
     * session. Session reads stay off the main thread; the look is applied on it.
     */
    internal fun followServerTheme(scope: RaftShellStoreScope) {
        serverThemeFollower.follow(scope, serverThemeLookFlow(scope).flowOn(Dispatchers.Default))
    }

    internal fun authenticatedRouteOwnerFlow(
        scope: RaftShellStoreScope,
    ): Flow<AuthenticatedRouteOwner?> =
        combine(
            apiEnvironmentStore.state,
            replyCacheAuthContext,
            selectedServerStore(scope).selectedServerId,
        ) { environment, authContext, serverId ->
            val environmentId = environment.apiBaseUrl.normalizedEnvironmentId()
            if (
                authContext == null ||
                environmentId.isBlank() ||
                environmentId != authContext.environmentId.normalizedEnvironmentId()
            ) {
                null
            } else {
                AuthenticatedRouteOwner(
                    environmentId = environmentId,
                    accountId = authContext.accountId,
                    serverId = serverId.orEmpty(),
                ).normalizedOrNull()
            }
        }.distinctUntilChanged()

    internal fun authenticatedRouteAuthorityGateway(
        scope: RaftShellStoreScope,
    ): AuthenticatedRouteAuthorityGateway =
        object : AuthenticatedRouteAuthorityGateway {
            override suspend fun capture(
                owner: AuthenticatedRouteOwner,
            ): AuthenticatedRouteAuthorityReceipt? =
                captureThreadReplyCacheIdentity(scope)
                    ?.toAuthenticatedRouteAuthorityReceipt(owner)

            override suspend fun isCurrent(
                receipt: AuthenticatedRouteAuthorityReceipt,
            ): Boolean =
                captureThreadReplyCacheIdentity(scope)
                    ?.matchesAuthenticatedRouteAuthority(receipt) == true

            override fun networkClient(
                receipt: AuthenticatedRouteAuthorityReceipt,
            ): RaftNetworkClient =
                networkClient(scope).boundTo(receipt.expectedAuthBinding)
        }

    internal suspend fun awaitThreadReplyCacheIdentityTransition(
        scope: RaftShellStoreScope,
        transitionAnchor: ThreadReplyCacheIdentityTransitionAnchor?,
    ): Boolean =
        awaitThreadReplyCacheIdentityTransition(
            authContext = replyCacheAuthContext,
            sessionStore = authStore,
            selectedServerStore = selectedServerStore(scope),
            transitionAnchor = transitionAnchor,
            environmentIdProvider = {
                apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
            },
        )

    fun networkClient(scope: RaftShellStoreScope): RaftNetworkClient =
        if (scope == RaftShellStoreScope.Legacy) {
            networkClient
        } else {
            shellNetworkClients.getOrPut(scope) {
                scopedNetworkClientFactory(authStore(scope))
            }
        }

    /**
     * The roster's cached projection is server keyed, but mutations such as an
     * agent update do not carry a server override. AppShell therefore must use
     * a repository bound to its own shell session instead of the process
     * last-used selection.
     */
    fun serverMembersStore(scope: RaftShellStoreScope): SharedServerMembersStore =
        if (scope == RaftShellStoreScope.Legacy) {
            serverMembersStore
        } else {
            shellServerMembersStores.getOrPut(scope) {
                SharedServerMembersStore(
                    memberRepository =
                        RealMemberRepository(
                            sessionStore = authStore(scope),
                            networkClient = networkClient(scope),
                            logger = sharedInfoLogger("SlockServerMembers", "shell_server_members_repository")
                        ),
                    logger = sharedInfoLogger("SlockServerMembers"),
                    processCore = processServerMembersCore,
                    presenceRegistry = processMemberPresenceRegistry,
                    presenceOwner = { serverId -> presenceOwnerResolver(serverId.value) },
                    presenceOwnerFlow = { serverId -> presenceOwnerFlowResolver(serverId.value) },
                    presenceWrite = { serverId -> presenceWriteResolver(serverId.value) },
                    processOwner = { serverId -> processServerMembersOwner(authStore(scope), serverId) },
                    avatarRefStore = memberAvatarRefStore,
                    jointActorRefStore = jointActorRefStore,
                    durableStore = serverMembersDurableStore,
                    channelMembersOffline = channelMembersOffline,
                    actorResolve = jointActorResolve(networkClient(scope)),
                    actorResolveScope = RaftStoreThreading.newScope(),
                    incrementalSyncCoversRoster = { serverId -> syncChangesPullAllowed(serverId.value) }
                ).also { store ->
                    store.startChannelMembersFreshness(threadSendScope)
                }
            }
        }

    private suspend fun processServerMembersOwner(
        sessionStore: RaftAuthSessionStore,
        serverId: ServerId
    ): ProcessServerMembersOwner? {
        val session = sessionStore.readSession() ?: return null
        val environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
        val accountId = session.user.id.trim()
        val normalizedServerId = serverId.value.trim()
        if (environmentId.isBlank() || accountId.isBlank() || normalizedServerId.isBlank()) return null
        return ProcessServerMembersOwner(environmentId, accountId, normalizedServerId)
    }

    fun realtimeDomainEventOwner(scope: RaftShellStoreScope): SharedRealtimeDomainEventOwner =
        shellRealtimeDomainEventOwners.getOrPut(scope) {
            createSharedRealtimeDomainEventOwner(
                tasksDataStore = null,
                cursorStore = null,
                authorityProvider = realtimeAuthorityProvider,
            ).also {
                // Canonical V2 admission remains shell/session bound, while
                // process-global task/cursor/presence mutation is owned only
                // by processRealtimeDomainEventOwner().
                it.installCanonicalMessageV2OwnerGateway(
                    buildCanonicalMessageV2OwnerGateway(),
                )
            }
        }

    fun processRealtimeDomainEventOwner(): SharedRealtimeDomainEventOwner = realtimeDomainEventOwner

    /**
     * Provider-owned authority flow for one mounted AppShell. Registration is
     * idempotent by shellInstanceId; callers only observe the flow and never
     * mint/rotate registry identities themselves.
     */
    fun realtimeAuthority(scope: RaftShellStoreScope): StateFlow<RealtimeScopeAuthority?> {
        val flow = realtimeAuthorityProvider.authorityFlow(scope.shellInstanceId)
        val registrationId =
            realtimeAuthorityRegistrations.getOrPut(scope) {
                "${scope.shellInstanceId}:${defaultNowMillis()}:${Random.nextLong()}"
            }
        threadSendScope.launch {
            realtimeAuthorityProvider.registerScope(scope.shellInstanceId, registrationId)
        }
        return flow
    }

    /**
     * Stable process transport authority. Native socket liveness is reconciled
     * from this lease; AppShell leases remain consumer/canonical-sink scopes.
     */
    fun processRealtimeAuthority(): StateFlow<RealtimeScopeAuthority?> = processRealtimeAuthorityFlow

    fun agentDetailStore(scope: RaftShellStoreScope): SharedAgentDetailStore =
        if (scope == RaftShellStoreScope.Legacy) {
            agentDetailStore
        } else {
            shellAgentDetailStores.getOrPut(scope) {
                SharedAgentDetailStore(
                    logger = sharedInfoLogger("SlockAgentDetail", "shell_agent_detail_store")
                )
            }
        }

    /**
     * One Computer projection per mounted shell. Home and secondary Search routes
     * must observe the same owner-fenced SharedComputersStore rather than creating
     * route-local copies of the machine directory.
     */
    /** Moves on login / session clear only, never on a token refresh; key client caches on it, not on tokens. */
    internal fun authLoginGeneration(scope: RaftShellStoreScope): SharedAuthLoginGeneration =
        shellAuthLoginGenerations.getOrPut(scope) {
            SharedAuthLoginGeneration().also { generation ->
                (authStore(scope) as? RaftAuthSessionEventSource)?.let { events ->
                    shellAuthLoginGenerationJobs.getOrPut(scope) {
                        threadSendScope.launch { events.authSessionEvents.collect(generation::onAuthSessionEvent) }
                    }
                }
            }
        }

    /** Message storage Phase 2c: where a message link or the agent overview offers the context pages it read. */
    internal fun messageContextPageSink(scope: RaftShellStoreScope): RaftMessageContextPageSink =
        DurableMessageContextPageSink(
            extender = threadDurablePageExtender,
            scope = threadSendScope,
            currentIdentity = {
                captureThreadReplyCacheIdentity(scope)?.let { identity ->
                    Triple(identity.environmentId, identity.principalId, identity.serverId)
                }
            },
            currentAccount = ::currentAccountBindingOrNull,
            logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2ContextPage"),
        )

    /** The computers cache owner for this shell's current session. */
    internal suspend fun computersOwner(scope: RaftShellStoreScope): SharedComputersOwner? =
        authStore(scope).readSession()?.let { session ->
            sharedComputersOwner(session, apiEnvironmentStore.current().apiBaseUrl, authLoginGeneration(scope).current())
        }

    private suspend fun computersOwnerBinding(scope: RaftShellStoreScope): RaftAccountBinding<SharedComputersOwner> =
        authStore(scope).readAccountBinding { session ->
            sharedComputersOwner(session, apiEnvironmentStore.current().apiBaseUrl, authLoginGeneration(scope).current())
        }

    /**
     * Runtime account usage reads and refreshes for this shell (the computer detail's runtime
     * chips): one cache and one refresh window per shell, whichever page asks.
     */
    /**
     * The Computers page's toolbar (#proj-mobile:#60): one per shell window for the app session,
     * like the web's toolbar store, so the search and filters are still there when the page is
     * opened again; Group, Sort and View are kept on this device. Pure local state, no requests.
     */
    internal fun computersRackToolbarStore(scope: RaftShellStoreScope): RaftComputersRackToolbarStore =
        shellComputersRackToolbarStores.getOrPut(scope) {
            RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(sharedPreferenceStore))
        }

    /**
     * Channel announcements of this shell scope (SharedChannelAnnouncementStore.kt): one store, so
     * the conversation's strip and the channel details read and write the same slice. Created by the
     * first page that shows one, and from then on fed by the shell's realtime owner whichever page is
     * open (as the web's socket bridge feeds its channel rows): an announcement changed while its
     * channel is closed is read when it opens.
     */
    internal fun channelAnnouncementStore(scope: RaftShellStoreScope): SharedChannelAnnouncementStore =
        shellChannelAnnouncementStores.getOrPut(scope) {
            SharedChannelAnnouncementStore(
                repository = ChannelAnnouncementRepository(NetworkRaftChannelAnnouncementApi(networkClient(scope))),
                scope = threadSendScope,
                logger = sharedInfoLogger("SlockChannelAnnouncement", "shared_channel_announcement"),
            ).also { store ->
                realtimeDomainEventOwner(scope).registerGuardedActiveThreadSink(
                    RealtimeGuardedActiveThreadSink { serverId, event -> store.dispatch(ChannelAnnouncementIntent.Realtime(serverId, event)) },
                )
            }
        }

    /** This scope's channel announcement store if a page made one (the Artifact viewer tells it what was seen). */
    internal fun existingChannelAnnouncementStore(scope: RaftShellStoreScope): SharedChannelAnnouncementStore? =
        shellChannelAnnouncementStores.get(scope)

    /**
     * Channel conversions of this shell scope (SharedChannelConversionStore.kt): the conversation's
     * conversion strip, one slice per channel shown.
     */
    internal fun channelConversionStore(scope: RaftShellStoreScope): SharedChannelConversionStore =
        shellChannelConversionStores.getOrPut(scope) {
            SharedChannelConversionStore(
                repository = ChannelConversionRepository(NetworkRaftChannelConversionApi(networkClient(scope))),
                scope = threadSendScope,
                logger = sharedInfoLogger("RaftChannelConversion", "shared_channel_conversion"),
            )
        }

    internal fun runtimeAccountUsageStore(scope: RaftShellStoreScope): SharedRuntimeAccountUsageStore =
        shellRuntimeAccountUsageStores.getOrPut(scope) {
            SharedRuntimeAccountUsageStore(
                client = RaftComputersApiClient(RaftComputersNetworkApi(networkClient(scope))),
                currentIdentity = { computersOwner(scope) },
                detachedScope = threadSendScope,
            )
        }

    fun computersStore(scope: RaftShellStoreScope): SharedComputersStore =
        shellComputersStores.getOrPut(scope) {
            SharedComputersStore(
                computersApiClient = RaftComputersApiClient(RaftComputersNetworkApi(networkClient(scope))),
                scope = threadSendScope,
                logger = sharedInfoLogger("SlockComputers", "shell_computers"),
                ownerProvider = { computersOwner(scope) },
                // Typed (architecture review B, site 9): an unreadable session is not a sign-out.
                ownerBinding = { computersOwnerBinding(scope) },
                durableStore = computersDurableStore
            )
        }

    /**
     * The server runtime catalog (`GET servers/:id/runtimes`) of this shell scope: one repository,
     * so the create dialog, the Members page and the Computers pages share its per-server cache and
     * in-flight request.
     */
    internal fun serverRuntimeCatalogRepository(scope: RaftShellStoreScope): SharedServerRuntimeCatalogRepository =
        shellServerRuntimeCatalogRepositories.getOrPut(scope) {
            val shellAuthStore = authStore(scope)
            SharedServerRuntimeCatalogRepository(
                api = RaftServerRuntimeCatalogNetworkApi(networkClient(scope)),
                scope = threadSendScope,
                cache = ServerRuntimeCatalogCache(sharedPreferenceStore),
                scopeProvider = {
                    shellAuthStore.readSession()?.let { session ->
                        val environment = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
                        val account = session.user.id.trim()
                        if (environment.isBlank() || account.isBlank()) null else "$environment|$account"
                    }
                },
                logger = sharedInfoLogger("SlockRuntimeCatalog", "shared_server_runtime_catalog")
            )
        }

    /**
     * Makes [serverId]'s runtime catalog what its runtime labels read ([RaftRuntimeCatalogNames]):
     * its cached answer at once, then one Background request per server in this process
     * ([SharedServerRuntimeCatalogRepository.ensureLoaded]), through a shell showing that server
     * when there is one. For labels shown outside the pages that load the catalog themselves.
     */
    internal fun ensureServerRuntimeCatalog(serverId: String) {
        val clean = serverId.trim().takeIf { it.isNotBlank() } ?: return
        if (RaftRuntimeCatalogNames.catalog(clean)?.source == RuntimeCatalogSource.Server) return
        val repository = serverRuntimeCatalogRepository(scopesForServer(clean).firstOrNull() ?: RaftShellStoreScope.Legacy)
        threadSendScope.launch { repository.ensureLoaded(clean, DataRequestPriority.Background) }
    }

    /**
     * The server model-label catalog (`GET servers/:id/model-label-catalog`, slock #15780) of this
     * shell scope: shared by author rows, agent profiles, preview cards, and live-detect refreshes.
     */
    internal fun serverModelLabelCatalogRepository(scope: RaftShellStoreScope): SharedServerModelLabelCatalogRepository =
        shellServerModelLabelCatalogRepositories.getOrPut(scope) {
            SharedServerModelLabelCatalogRepository(
                api = RaftServerModelLabelCatalogNetworkApi(networkClient(scope)),
                scope = threadSendScope,
                logger = sharedInfoLogger("SlockModelLabelCatalog", "shared_server_model_label_catalog")
            )
        }

    override fun ensureServerModelLabelCatalog(serverId: String) {
        val clean = serverId.trim().takeIf { it.isNotBlank() } ?: return
        if (RaftModelLabelCatalogs.currentCatalog(clean) != null) return
        val repository = serverModelLabelCatalogRepository(scopesForServer(clean).firstOrNull() ?: RaftShellStoreScope.Legacy)
        threadSendScope.launch { repository.load(clean, DataRequestPriority.Background) }
    }

    override fun refreshServerModelLabelCatalogAfterLiveDetect(serverId: String) {
        val clean = serverId.trim().takeIf { it.isNotBlank() } ?: return
        val repository = serverModelLabelCatalogRepository(scopesForServer(clean).firstOrNull() ?: RaftShellStoreScope.Legacy)
        repository.refreshAfterLiveDetect(clean)
    }

    fun createAgentFormStore(scope: RaftShellStoreScope): SharedCreateAgentFormStore =
        shellCreateAgentFormStores.getOrPut(scope) {
            val shellAuthStore = authStore(scope)
            val loginGeneration = authLoginGeneration(scope)
            val formLogger = sharedInfoLogger("SlockCreateAgentForm", "shared_create_agent_form_store")
            SharedCreateAgentFormStore(
                computersApiClient = RaftComputersApiClient(RaftComputersNetworkApi(networkClient(scope))),
                scope = threadSendScope,
                hostedRuntimeFlag = { serverId ->
                    serverFeatureFlags.isEnabled(serverId, ANTIPROTON_HOSTED_RUNTIME_FEATURE_FLAG_KEY)
                },
                ownerProvider = {
                    shellAuthStore.readSession()?.let { session ->
                        sharedCreateAgentFormOwner(
                            session = session,
                            environmentId = apiEnvironmentStore.current().apiBaseUrl,
                            loginGeneration = loginGeneration.current()
                        )
                    }
                },
                // Typed (architecture review B, site 9): an unreadable session is not a sign-out.
                ownerBinding = {
                    shellAuthStore.readAccountBinding { session ->
                        sharedCreateAgentFormOwner(
                            session = session,
                            environmentId = apiEnvironmentStore.current().apiBaseUrl,
                            loginGeneration = loginGeneration.current()
                        )
                    }
                },
                logger = formLogger,
                runtimeFormV2Loader = RuntimeFormV2Loader(
                    computers = RaftComputersNetworkApi(networkClient(scope)),
                    agents = RaftAgentsNetworkApi(networkClient(scope)),
                    logger = formLogger,
                    onLiveModelDetect = { sid -> serverModelLabelCatalogRepository(scope).refreshAfterLiveDetect(sid.value) }
                ),
                serverRuntimeCatalogs = serverRuntimeCatalogRepository(scope),
                lastConfigs = CreateAgentLastConfigRepository(sharedPreferenceStore)
            ).also { store ->
                (shellAuthStore as? RaftAuthSessionEventSource)?.let { events ->
                    shellCreateAgentFormOwnerJobs.getOrPut(scope) {
                        threadSendScope.launch {
                            events.authSessionEvents.collect { event ->
                                loginGeneration.onAuthSessionEvent(event)
                                store.reconcileOwner()
                            }
                        }
                    }
                }
            }
        }

    /**
     * Owns pending external navigation for one document/AppShell. Recreating the page must not
     * replay an already opened notification route, while closing the shell drops its queue.
     */
    fun windowNavigationCoordinator(scope: RaftShellStoreScope): SharedWindowNavigationCoordinator =
        shellNavigationCoordinators.getOrPut(scope) { SharedWindowNavigationCoordinator() }

    /** Identity-only, one-step Forward history owned by one document/AppShell. */
    fun conversationForwardHistory(scope: RaftShellStoreScope): SharedConversationForwardHistory =
        shellConversationForwardHistories.getOrPut(scope) { SharedConversationForwardHistory() }

    fun foregroundBannerStore(scope: RaftShellStoreScope): SharedForegroundBannerStore =
        shellForegroundBannerStores.getOrPut(scope) { SharedForegroundBannerStore(threadSendScope) }

    fun realtimeRoomLeaseRegistry(scope: RaftShellStoreScope): SharedRealtimeRoomLeaseRegistry =
        shellRealtimeRoomLeaseRegistries.getOrPut(scope) {
            SharedRealtimeRoomLeaseRegistry(
                shellInstanceId = scope.shellInstanceId,
                leaveAllowed = { serverId, roomId ->
                    realtimeRoomLeaveAllowed(conversationIdentityProvider(scope), serverId, roomId)
                },
                // Task #168: the socket stops receiving that room's frames; its offline-cache window must
                // not be appended onto any more.
                onLeave = canonicalMessageV2Canary::noteRoomLeft,
            )
        }

    /**
     * "My assistant" per server for this shell (web `assistantClient.ts`): one read per person and
     * server whichever conversation asks, and the composer the assistant's otter lies on.
     */
    fun myAssistantStore(scope: RaftShellStoreScope): SharedMyAssistantStore =
        shellMyAssistantStores.getOrPut(scope) {
            SharedMyAssistantStore(
                api = RaftMyAssistantNetworkApi(networkClient(scope)),
                scope = threadSendScope,
                logger = sharedInfoLogger("SlockMyAssistant"),
                otterPrefs = assistantOtterPrefsStore,
            )
        }

    fun adaptiveWorkspaceStore(scope: RaftShellStoreScope): SharedAdaptiveWorkspaceStore =
        shellAdaptiveWorkspaceStores.getOrPut(scope) { SharedAdaptiveWorkspaceStore() }

    fun adaptiveScrollAnchorStore(scope: RaftShellStoreScope): SharedAdaptiveScrollAnchorStore =
        shellAdaptiveScrollAnchorStores.getOrPut(scope) { SharedAdaptiveScrollAnchorStore() }

    /** read_receipts_v0: other participants' read positions per scope, cached for this shell's owner. */
    fun peerReadReceiptStore(scope: RaftShellStoreScope): SharedPeerReadReceiptStore =
        shellPeerReadReceiptStores.getOrPut(scope) {
            SharedPeerReadReceiptStore(
                scope = threadSendScope,
                fetchHydrate = { owner, scopeId ->
                    fetchPeerReadReceiptHydrate(networkClient(scope), ServerId(owner.serverId), scopeId)
                },
                logger = sharedInfoLogger("SlockPeerReadReceipts", "peer_read_receipts"),
            )
        }

    /**
     * Agent typing line: who is about to post where, for this shell's selected server. Created by the
     * first conversation page and from then on fed by the shell's realtime owner whichever page is
     * open, as the web's socket bridge feeds its store.
     */
    fun agentTypingStore(scope: RaftShellStoreScope): SharedAgentTypingStore =
        shellAgentTypingStores.getOrPut(scope) {
            SharedAgentTypingStore(scope = threadSendScope, now = ::defaultNowMillis).also { store ->
                realtimeDomainEventOwner(scope).registerGuardedActiveThreadSink(
                    RealtimeGuardedActiveThreadSink { serverId, event -> store.consume(serverId, event) },
                )
            }
        }

    override fun createAuthDataSourceForCurrentEnvironment(
        avatarCacheNativeCache: RaftAvatarNativeCache
    ): SharedAuthDataSource =
        createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = apiEnvironmentStore,
            authStore = authStore,
            avatarCacheRuntime = avatarCacheRuntime,
            avatarCacheNativeCache = avatarCacheNativeCache
        )

    fun createAuthDataSourceForCurrentEnvironment(
        scope: RaftShellStoreScope,
        avatarCacheNativeCache: RaftAvatarNativeCache = UnsupportedRaftAvatarNativeCache
    ): SharedAuthDataSource =
        createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = apiEnvironmentStore,
            authStore = authStore(scope),
            avatarCacheRuntime = avatarCacheRuntime,
            avatarCacheNativeCache = avatarCacheNativeCache
        )

    fun markActiveAppShellPager(pagerId: String) {
        activeAppShellPagerOwner.markActive(pagerId)
    }

    fun isActiveAppShellPager(pagerId: String): Boolean =
        activeAppShellPagerOwner.isActive(pagerId)

    fun updateEventBus(nextEventBus: RaftEventBus, source: String) {
        eventBus.update(nextEventBus)
        authSessionEvents.updateEventBus(eventBus, source)
        authSessionClearLogger?.let(::installAuthSessionClearNotifyBridge)
    }

    fun getOrCreateHomeStore(
        scope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
        dataSource: SharedHomeDataSource,
        loadSidebarOrder: suspend (ServerId) -> DataResult<SettingsServerSidebarOrderSnapshot> = { _ ->
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        updateSidebarOrder: suspend (SettingsServerSidebarOrderUpdateRequest) -> DataResult<SettingsServerSidebarOrderSnapshot> = {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        loadServerOrder: suspend () -> DataResult<SettingsServerOrderSnapshot> = {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        updateServerOrder: suspend (SettingsServerOrderUpdateRequest) -> DataResult<SettingsServerOrderSnapshot> = {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        },
        markChannelReadAll: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        markChannelUnread: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        markConversationReadAll: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        archiveChannel: suspend (ChannelId) -> DataResult<ChannelArchiveResult> = {
            DataResult.Empty(EmptyReason.NoChannels)
        },
        leaveChannel: suspend (ChannelId) -> DataResult<ChannelLeaveResult> = {
            DataResult.Empty(EmptyReason.NoChannels)
        },
        setChannelActivityMuted: suspend (ChannelId, Boolean) -> DataResult<ChannelActivityMuteSettings> = { _, _ ->
            DataResult.Empty(EmptyReason.NoChannels)
        },
        pinUpdateFailureCallback: (String) -> Unit = {},
        pinUpdateFailureWithTraceCallback: ((String, String?) -> Unit)? = null,
        diagnosticsLogger: (String) -> Unit = {}
    ): SharedHomeStore {
        homeStores.get(scope)?.let { store ->
            store.updateRuntimeIntegrations(
                dataSource = dataSource,
                diagnosticsLogger = diagnosticsLogger,
                loadSidebarOrder = loadSidebarOrder,
                updateSidebarOrder = updateSidebarOrder,
                loadServerOrder = loadServerOrder,
                updateServerOrder = updateServerOrder,
                markChannelReadAll = markChannelReadAll,
                markChannelUnread = markChannelUnread,
                markConversationReadAll = markConversationReadAll,
                archiveChannel = archiveChannel,
                leaveChannel = leaveChannel,
                setChannelActivityMuted = setChannelActivityMuted,
                readReceiptCoordinator = processReadReceiptCoordinator(),
                readReceiptOwnerFactory = ::readReceiptOwner,
                readStateV1Canary = processReadStateV1Canary,
                pinUpdateFailureCallback = pinUpdateFailureCallback,
                pinUpdateFailureWithTraceCallback = pinUpdateFailureWithTraceCallback,
                onConversationReadAllAcknowledged = ::publishConversationReadAllAck,
                onChannelLeft = { channelId -> publishChannelAccessLost(scope, channelId) }
            )
            return store
        }
        return homeStores.getOrPut(scope) {
            SharedHomeStore(
                authStore = authStore(scope),
                dataSource = dataSource,
                scope =
                    homeStoreScopes.getOrPut(scope) {
                        if (scope == RaftShellStoreScope.Legacy) homeStoreScope else RaftStoreThreading.newScope()
                    },
                diagnosticsLogger = diagnosticsLogger,
                syncReloads = syncReloadLedger,
                loadSidebarOrder = loadSidebarOrder,
                updateSidebarOrder = updateSidebarOrder,
                loadServerOrder = loadServerOrder,
                updateServerOrder = updateServerOrder,
                markChannelReadAll = markChannelReadAll,
                markChannelUnread = markChannelUnread,
                markConversationReadAll = markConversationReadAll,
                archiveChannel = archiveChannel,
                leaveChannel = leaveChannel,
                setChannelActivityMuted = setChannelActivityMuted,
                perReceiverStateVersionStore = PerReceiverStateVersionStore(sharedPreferenceStore),
                readReceiptCoordinator = processReadReceiptCoordinator(),
                readReceiptOwnerFactory = ::readReceiptOwner,
                readStateV1Canary = processReadStateV1Canary,
                pinUpdateFailureCallback = pinUpdateFailureCallback,
                pinUpdateFailureWithTraceCallback = pinUpdateFailureWithTraceCallback,
                onConversationReadAllAcknowledged = ::publishConversationReadAllAck,
                onChannelLeft = { channelId -> publishChannelAccessLost(scope, channelId) },
                selectedConversationSeen = { raftHostShowsContent(scope.shellInstanceId) },
                openSideThread = { adaptiveWorkspaceStore(scope).snapshot().openSideThreadMatch() },
                isAgentLoungeEnabled = { serverId ->
                    serverFeatureFlags.isEnabled(serverId, AGENT_LOUNGE_FEATURE_FLAG_KEY)
                },
                resolveExternalThreadParentChannelId = { serverId, threadChannelId ->
                    homeActivityStores.get(scope)?.state?.value
                        ?.takeIf { it.ownerServerId == serverId.value }
                        ?.items
                        ?.firstOrNull { it.threadChannelId?.value == threadChannelId }
                        ?.parentChannelId
                        ?.value
                        ?: tasksDataStore.parentChannelIdForTaskConversation(serverId.value, threadChannelId)
                },
                // Identity first: the hint slice attaches to the durable content it restores.
                persistSlices = listOf(homeIdentityPersistSlice, homeRowHintPersistSlice),
                conversationIdentityPublicationSink = publication@{ publication ->
                    // Capture the authority in the same synchronous turn as the Home state that
                    // produced this candidate. Looking it up later in a launched coroutine could
                    // re-endorse an old same-account row with a successor auth revision.
                    val environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
                    val context = replyCacheAuthContext.value
                    if (context == null) {
                        // Data-layer plan step 4: a cold start restores Home's durable rows before
                        // the auth binding exists. Home will not send the same rows again, so stage
                        // them; the binding follower applies them to the matching account only.
                        val hub = conversationIdentityHub(scope)
                        hub.stagePendingCandidate(
                            environmentId = environmentId,
                            accountId = publication.accountId,
                            serverId = publication.serverId.value,
                            revision = publication.revision,
                            next = publication.state,
                        )
                        // The binding may have landed between the read above and the stage.
                        replyCacheAuthContext.value?.let { bound -> hub.applyPendingCandidatesFor(bound) }
                        return@publication
                    }
                    if (
                        environmentId != context.environmentId ||
                        publication.accountId != context.accountId
                    ) {
                        return@publication
                    }
                    conversationIdentityHub(scope).publishCandidate(
                        authority =
                            SharedConversationIdentityAuthority(
                                environmentId = environmentId,
                                accountId = context.accountId,
                                serverId = publication.serverId.value,
                                authGeneration = context.authorityGeneration,
                            ),
                        revision = publication.revision,
                        next = publication.state,
                    )
                },
            )
        }
    }

    /** The Activity store already created for [scope], or null; never creates one. */
    fun homeActivityStoreOrNull(scope: RaftShellStoreScope): SharedActivityStore? = homeActivityStores.get(scope)

    fun getOrCreateHomeActivityStore(
        scope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
        inboxRepository: InboxRepository,
        readReceiptRepository: ReadReceiptRepository,
        conversationRepository: ConversationRepository,
        memberRepository: MemberRepository? = null,
        channelRepository: ChannelRepository? = null,
        prewarmReplyThread: (suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit>)? = null,
        replyThreadContentCached: (suspend (ActivityReplyThreadPrewarmMarker) -> Boolean)? = null,
        onUnreadCountsChanged: (() -> Unit)? = null,
        diagnosticsLogger: ((String) -> Unit)? = null
    ): SharedActivityStore {
        homeActivityStores.get(scope)
            ?.also { store ->
                store.updateRuntimeIntegrations(
                    authStore = authStore(scope),
                    inboxRepository = inboxRepository,
                    readReceiptRepository = readReceiptRepository,
                    conversationRepository = conversationRepository,
                    memberRepository = memberRepository,
                    serverMembersStore = serverMembersStore(scope),
                    tasksDataStore = tasksDataStore,
                    channelRepository = channelRepository,
                    persistSlices = listOf(activityPersistSlice),
                    readReceiptCoordinator = processReadReceiptCoordinator(),
                    readReceiptOwnerFactory = ::readReceiptOwner,
                    readStateV1Canary = processReadStateV1Canary,
                    prewarmReplyThread = prewarmReplyThread,
                    replyThreadContentCached = replyThreadContentCached,
                    replyThreadFreshnessLedger = threadPayloadStore.freshnessLedger,
                    foregroundLoadGate = threadPayloadStore.foregroundLoadGate,
                    onUnreadCountsChanged = onUnreadCountsChanged,
                    onThreadUnfollowProjection = { intent -> homeStores.get(scope)?.dispatch(intent) },
                    onThreadFollowProjection = { intent -> homeStores.get(scope)?.dispatch(intent) },
                    onSummaryFact = activityUnreadSummaryStore::submitFact,
                    onSummaryInvalidated = activityUnreadSummaryStore::submitInvalidation,
                    diagnosticsLogger = diagnosticsLogger
                )
                return store
            }
        return homeActivityStores.getOrPut(scope) {
            SharedActivityStore(
                authStore = authStore(scope),
                inboxRepository = inboxRepository,
                readReceiptRepository = readReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore(scope),
                tasksDataStore = tasksDataStore,
                channelRepository = channelRepository,
                perReceiverStateVersionStore = PerReceiverStateVersionStore(sharedPreferenceStore),
                readReceiptCoordinator = processReadReceiptCoordinator(),
                readReceiptOwnerFactory = ::readReceiptOwner,
                readStateV1Canary = processReadStateV1Canary,
                persistSlices = listOf(activityPersistSlice),
                filterPreferences = ActivityFilterPreferenceRepository(sharedPreferenceStore),
                prewarmReplyThread = prewarmReplyThread ?: { DataResult.Empty(EmptyReason.NoMessages) },
                replyThreadContentCached = replyThreadContentCached ?: { false },
                replyThreadFreshnessLedger = threadPayloadStore.freshnessLedger,
                foregroundLoadGate = threadPayloadStore.foregroundLoadGate,
                onUnreadCountsChanged = onUnreadCountsChanged ?: {},
                onThreadUnfollowProjection = { intent -> homeStores.get(scope)?.dispatch(intent) },
                onThreadFollowProjection = { intent -> homeStores.get(scope)?.dispatch(intent) },
                onSummaryFact = activityUnreadSummaryStore::submitFact,
                onSummaryInvalidated = activityUnreadSummaryStore::submitInvalidation,
                onBadgeReleased = { serverId ->
                    activityUnreadSummaryStore.refresh(ServerId(serverId), reason = ActivityBadgeReleasedSummaryReason)
                },
                activityObjectsRepository = NetworkActivityObjectsRepository(networkClient(scope)),
                hiddenRealtimeBackgroundRefreshIntervalMillis = ActivityHiddenRealtimeBackgroundRefreshIntervalMillis,
                syncReloads = syncReloadLedger,
                inboxInPlaceEnabled = ::inboxInPlaceEnabled,
                conversationsReadOnSight = raftConversationReadOnSight::readingChannels,
                inPlaceCheckSink = RaftClientTraceEvents::emitInPlaceCheck,
                listRowLoweredSink = RaftClientTraceEvents::emitListRowLowered,
                inboxRowPositionEnabled = ::inboxRowPositionEnabled,
                onSyncPullHint = ::requestRealtimeSyncPull,
                syncInboxRowsEnabled = ::syncInboxRowsEnabled,
                knownThreadContext = { serverId, threadChannelId, parentMessageId, parentChannelId ->
                    knownThreadContextForActivity(scope, serverId, threadChannelId, parentMessageId, parentChannelId)
                },
                scope =
                    homeActivityStoreScopes.getOrPut(scope) {
                        if (scope == RaftShellStoreScope.Legacy) homeActivityStoreScope else RaftStoreThreading.newScope()
                    },
                diagnosticsLogger = diagnosticsLogger ?: {}
            )
        }
    }

    /**
     * A Home "Mark All as Read" ack reaches Activity the way the server's read-state push does: the
     * Activity unread summary is invalidated and each Activity store applies the versioned scopes
     * (web `markConversationRead` -> `applyReadStateUpdate`).
     */
    /**
     * The user no longer has [channelId] (left it, deleted or disconnected it): Activity's Unread
     * keeps rows read this visit, and a channel the user no longer has must not stay. Channel
     * settings and the Home row menu's Leave both hand off here.
     */
    fun publishChannelAccessLost(scope: RaftShellStoreScope, channelId: ChannelId) {
        homeActivityStoreOrNull(scope)?.dispatch(SharedActivityIntent.ChannelAccessLost(setOf(channelId.value)))
    }

    private fun publishConversationReadAllAck(accountId: String, event: PerReceiverReadStateChanged) {
        val serverId = event.serverId?.value ?: return
        activityUnreadSummaryStore.invalidate(
            readReceiptOwner(serverId, accountId).toProcessActivityUnreadOwner(),
            reason = "conversation_read_all"
        )
        homeActivityStores.entries().forEach { (shellScope, store) ->
            runOnStoreScope(homeActivityStoreScopes.get(shellScope)) {
                store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))
            }
        }
    }

    fun processReadReceiptCoordinator(): ReadReceiptCoordinator =
        synchronized(processReadReceiptLock) {
            processReadReceiptCoordinator
                ?: createProcessReadReceiptCoordinator().also { coordinator ->
                    processReadReceiptCoordinator = coordinator
                }
        }

    private fun createProcessReadReceiptCoordinator(): ReadReceiptCoordinator {
        val repository =
            RealReadReceiptRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = sharedInfoLogger("SlockReadReceipt", "process_read_receipt")
            )
        return ReadReceiptCoordinator(
            scope = threadSendScope,
            repository = repository,
            requestRunner = { request, _ ->
                val serverId = ServerId(request.owner.serverId)
                val ownerRepository = processReadReceiptRepository(request.owner)
                when (request) {
                    is ReadReceiptRequest.ToSeq ->
                        ownerRepository.markChannelReadToSeq(serverId, request.channelId, request.seq)
                    is ReadReceiptRequest.All ->
                        ownerRepository.markChannelReadAll(serverId, request.channelId)
                }
            },
            ownerValidator = { owner ->
                val session = authStore.readSession()
                apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/') == owner.environmentId &&
                    session != null &&
                    session.user.id == owner.accountId &&
                    session.servers.any { server -> server.id == owner.serverId }
            },
            pendingStore = readReceiptPendingStore,
            replayFrontierLoader = { owner ->
                processReadReceiptRepository(owner).loadFreshReadFrontiers(ServerId(owner.serverId))
            },
            onReplayDropped = { _, _ ->
                // Home was showing that read: the server's count comes back now.
                homeStores.entries().forEach { (shellScope, store) ->
                    runOnStoreScope(homeStoreScopes.get(shellScope)) { store.dispatch(SharedHomeIntent.Refresh) }
                }
            },
            ownerAccessLost = { owner ->
                val session = authStore.readSession()
                apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/') == owner.environmentId &&
                    session != null &&
                    session.user.id == owner.accountId &&
                    session.servers.isNotEmpty() &&
                    session.servers.none { server -> server.id == owner.serverId }
            },
            onPrediction = { prediction ->
                // The user read it here: its system notification (Android) goes too.
                RaftConversationReadHost.conversationRead(prediction.owner.serverId, prediction.channelId.value)
                // No summary read here: the prediction adjusts the count locally (Activity's fact) and the
                // receipt's result ("read_result") is the mark-read's one summary read.
                homeStores.entries().forEach { (shellScope, store) ->
                    runOnStoreScope(homeStoreScopes.get(shellScope)) {
                        store.dispatch(SharedHomeIntent.ApplyReadReceiptPrediction(prediction))
                    }
                }
                homeActivityStores.entries().forEach { (shellScope, store) ->
                    runOnStoreScope(homeActivityStoreScopes.get(shellScope)) {
                        store.dispatch(SharedActivityIntent.ApplyReadReceiptPrediction(prediction))
                    }
                }
            },
            onResult = { request, result ->
                val content = result as? DataResult.Content
                if (content != null) {
                    threadSendScope.launch {
                        processReadStateV1Canary.applyMutationAck(
                            owner = request.owner.toReadStateV1ProductionOwner(),
                            channelId = request.channelId.value,
                            readStateVersion = content.data.readStateVersion,
                            maxReadSeq = content.data.maxReadSeq,
                        )
                    }
                }
                activityUnreadSummaryStore.invalidate(
                    request.owner.toProcessActivityUnreadOwner(),
                    reason = "read_result:${request.revision}"
                )
                // The Activity local read hold starts at server confirmation.
                homeActivityStores.entries().forEach { (shellScope, store) ->
                    runOnStoreScope(homeActivityStoreScopes.get(shellScope)) {
                        store.dispatch(
                            SharedActivityIntent.ApplyReadReceiptResult(
                                request = request,
                                succeeded = result.isReadReceiptSuccess(),
                                // The read ack: settles held `local_read` lowerings (slock #15197).
                                maxReadSeq = content?.data?.maxReadSeq
                            )
                        )
                    }
                }
                sharedInfoLogger("SlockReadReceipt", "process_read_receipt")(
                    "result owner=${request.owner.serverId}:${request.owner.accountId} " +
                        "channelId=${request.channelId.value} revision=${request.revision} " +
                        "result=${result::class.simpleName.orEmpty()}"
                )
            },
            onOwnerRevoked = { owner ->
                processReadStateV1Canary.revoke(owner.toReadStateV1ProductionOwner())
            },
            onFailure = { request, result ->
                activityUnreadSummaryStore.invalidate(
                    request.owner.toProcessActivityUnreadOwner(),
                    reason = "read_failure:${request.revision}"
                )
                homeStores.entries().forEach { (shellScope, store) ->
                    runOnStoreScope(homeStoreScopes.get(shellScope)) {
                        store.dispatch(SharedHomeIntent.ApplyReadReceiptFailure(request))
                    }
                }
                homeActivityStores.entries().forEach { (shellScope, store) ->
                    runOnStoreScope(homeActivityStoreScopes.get(shellScope)) {
                        store.dispatch(SharedActivityIntent.ApplyReadReceiptFailure(request))
                    }
                }
                sharedWarnLogger("SlockReadReceipt", "process_read_receipt")(
                    "failure owner=${request.owner.serverId}:${request.owner.accountId} " +
                        "channelId=${request.channelId.value} revision=${request.revision} " +
                        "result=${result::class.simpleName.orEmpty()}"
                )
            }
        )
    }

    /**
     * The read-receipt coordinator reports predictions/failures on its caller's thread (the thread
     * store's page scope: Kuikly's context thread before, the main thread under CMP). Home and
     * activity stores apply those intents synchronously, so hop onto the store's own scope (the
     * serial store dispatcher in production) instead of writing their state from that thread. The
     * serial dispatcher runs tasks in order, so a prediction still lands before its failure.
     */
    private fun runOnStoreScope(storeScope: CoroutineScope?, block: () -> Unit) {
        if (storeScope == null) block() else storeScope.launch { block() }
    }

    private fun processReadReceiptRepository(owner: ReadReceiptOwner): RealReadReceiptRepository =
        synchronized(processReadReceiptLock) {
            processReadReceiptRepositories.getOrPut(owner) {
                val ownerSessionStore = ReadReceiptOwnerAuthSessionStore(authStore, owner)
                RealReadReceiptRepository(
                    sessionStore = ownerSessionStore,
                    networkClient = ownerScopedNetworkClientFactory(ownerSessionStore, owner.environmentId),
                    logger = sharedInfoLogger("SlockReadReceipt", "process_read_receipt")
                )
            }
        }

    private suspend fun currentProcessReadReceiptOwners(): Set<ReadReceiptOwner> {
        val session = authStore.readSession() ?: return emptySet()
        val environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
        return session.servers.mapTo(linkedSetOf()) { server ->
            ReadReceiptOwner(environmentId, session.user.id, server.id)
        }
    }

    private fun visibleOrActiveProcessReadReceiptOwners(): Set<ReadReceiptOwner> =
        synchronized(processReadReceiptLock) { processReadReceiptRepositories.keys.toSet() } +
            listOfNotNull(processReadStateV1Canary.selectedOwner()?.toReadReceiptOwner())

    private fun revokeProcessReadReceiptOwner(owner: ReadReceiptOwner) {
        val coordinator = synchronized(processReadReceiptLock) {
            processReadReceiptRepositories.remove(owner)
            processReadReceiptCoordinator
        }
        if (coordinator != null) {
            coordinator.revoke(owner)
        } else {
            processReadStateV1Canary.revoke(owner.toReadStateV1ProductionOwner())
        }
    }

    internal fun hardClearProcessReadReceiptCoreForTeardown(): Boolean {
        if (homeStores.entries().isNotEmpty() || homeActivityStores.entries().isNotEmpty()) return false
        val coordinator =
            synchronized(processReadReceiptLock) {
                processReadReceiptRepositories.clear()
                processReadReceiptCoordinator
            }
        coordinator?.clear()
        processReadStateV1Canary.clear()
        return true
    }

    fun readReceiptOwner(
        serverId: String,
        accountId: String
    ): ReadReceiptOwner =
        ReadReceiptOwner(
            environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/'),
            accountId = accountId.trim(),
            serverId = serverId.trim()
        )

    private suspend fun clearActiveShellServerSelections() {
        reconcileOrClearActiveShellServerSelections(
            authStore = authStore,
            selectedServerStores = selectedServerStores,
        )
    }

    fun releaseShellStoreScope(scope: RaftShellStoreScope) {
        if (scope == RaftShellStoreScope.Legacy) return
        shellRealtimeRoomLeaseRegistries.remove(scope)?.clear()?.let { transitions ->
            RaftRealtimeEvents(eventBus).applyRoomTransitions(transitions)
        }
        shellAdaptiveWorkspaceStores.remove(scope)?.clear()
        realtimeAuthorityRegistrations.remove(scope)?.let { registrationId ->
            threadSendScope.launch {
                val released =
                    realtimeAuthorityProvider.releaseScope(scope.shellInstanceId, registrationId)
                // Evidence line for the subscription-holding audit: emitted at the point the
                // authority registry actually cleared the lease, not at call time. A missing
                // registration (already dead or never registered) reports result=missed.
                runCatching {
                    sharedInfoLogger("RealtimeAuthority", "realtime_authority")(
                        "realtime_authority_release result=${if (released) "ok" else "missed"} " +
                            "scope=${scope.shellInstanceId}"
                    )
                }
            }
        }
        selectedServerStores.get(scope)?.dispose()
        homeStores.remove(scope)?.clear()
        homeActivityStores.remove(scope)?.clear()
        homeStoreScopes.remove(scope)?.cancel()
        homeActivityStoreScopes.remove(scope)?.cancel()
        shellNetworkClients.remove(scope)
        shellAuthStores.remove(scope)
        liveAgentActivityStores.remove(scope)
        shellServerMembersStores.remove(scope)?.let { store ->
            store.clearScopedReadCaches()
            store.dispose()
        }
        shellRealtimeDomainEventOwners.remove(scope)
        shellPeerReadReceiptStores.remove(scope)?.dispatch(PeerReadReceiptIntent.BindOwner(null))
        shellAgentTypingStores.remove(scope)?.close()
        shellAgentDetailStores.remove(scope)
        shellComputersStores.remove(scope)?.dispatch(SharedComputersIntent.Clear)
        shellRuntimeAccountUsageStores.remove(scope)
        shellChannelAnnouncementStores.remove(scope)?.dispatch(ChannelAnnouncementIntent.Reset)
        shellChannelConversionStores.remove(scope)?.dispatch(ChannelConversionIntent.Reset)
        shellMyAssistantStores.remove(scope)
        shellComputersRackToolbarStores.remove(scope)
        shellCreateAgentFormOwnerJobs.remove(scope)?.cancel()
        shellAuthLoginGenerationJobs.remove(scope)?.cancel()
        shellAuthLoginGenerations.remove(scope)
        shellCreateAgentFormStores.remove(scope)
        shellServerRuntimeCatalogRepositories.remove(scope)
        shellServerModelLabelCatalogRepositories.remove(scope)
        shellNavigationCoordinators.remove(scope)?.clear()
        shellConversationForwardHistories.remove(scope)?.release()
        shellForegroundBannerStores.remove(scope)?.clear()
        shellConversationIdentityHubs.remove(scope)?.clear()
        selectedServerStores.remove(scope)
    }

    fun installAuthSessionClearNotifyBridge(logger: (String) -> Unit) {
        authSessionClearLogger = logger
        authSessionClearedSubscription?.let(authSessionEvents::removeSessionClearedListener)
        val scope = CoroutineScope(EmptyCoroutineContext)
        authSessionClearedSubscription =
            authSessionEvents.addSessionClearedListener { signal ->
                if (authSessionEvents.isLocalSource(signal)) {
                    return@addSessionClearedListener
                }
                logger("auth_session_clear_notify_received source=${signal.source} reason=${signal.reason}")
                scope.launch {
                    runCatchingPreservingCancellation {
                        authStore.clearSessionFromSessionClearedNotify(
                            reason = signal.reason,
                            previousSessionFingerprint = signal.previousSessionFingerprint
                        )
                    }.onSuccess { applied ->
                        logger(
                            "auth_session_clear_notify_result result=success applied=$applied " +
                                "source=${signal.source} reason=${signal.reason}"
                        )
                    }.onFailure { throwable ->
                        logger(
                            "auth_session_clear_notify_result result=failure source=${signal.source} " +
                                "reason=${signal.reason} error=${throwable.message.orEmpty()}"
                        )
                    }
                }
            }
    }

    companion object {
        fun create(
            eventBus: RaftEventBus,
            source: String,
            host: HostCapabilities,
            canonicalFinalIntegrationFactory: RaftSharedCanonicalFinalIntegrationFactory? = null,
        ): RaftSharedStoreProvider {
            val eventBusRelay = RaftEventBusRelay(eventBus)
            val database = createRaftSqlDatabase()
            val threadPendingSendJournal = ThreadPendingSendJournal(database)
            val relationalStore = SqlDelightRelationalStore(database)
            val localDataStore =
                RelationalComposeLocalDataStore(
                    store = relationalStore,
                    diagnosticsLogger = sharedInfoLogger("SlockLocalCache", "local_cache")
                )
            val authSessionEvents =
                RaftAuthSessionEvents(
                    eventBus = eventBusRelay,
                    source = source
                )
            val authKeyValueStoreBinding = RaftSharedAuthSessionKeyValueStoreProvider.create()
            val authKeyValueStore = authKeyValueStoreBinding.store
            logSharedAuthDiagnostic(
                "shared_auth_session",
                "auth_storage_scope scope=${authKeyValueStoreBinding.scope}"
            )
            val authStore =
                KeyValueAuthSessionStore(
                    store = authKeyValueStore,
                    diagnosticsLogger = { message ->
                        logSharedAuthDiagnostic("shared_auth_session", message)
                    },
                    criticalDiagnosticsLogger = { message ->
                        logSharedAuthDiagnostic(
                            "shared_auth_session",
                            message,
                            RaftPlatformLogHint.AuthCritical
                        )
                    }
                )
            val sharedPreferenceStoreBinding =
                RaftSharedKeyValueStoreProvider.create(
                    namespace = RaftLocalPreferenceStore.KuiklyNamespace,
                    storageName = "shared preference"
                )
            val sharedPreferenceStore = sharedPreferenceStoreBinding.store
            logSharedAuthDiagnostic(
                "shared_key_value",
                "storage_scope name=sharedPreference " +
                    "namespace=${RaftLocalPreferenceStore.KuiklyNamespace} " +
                    "scope=${sharedPreferenceStoreBinding.scope}"
            )
            // Shared with the environment indicator, which reads it without building this provider.
            val apiEnvironmentKeyValueStoreBinding = RaftAppScopeApiEnvironment.binding()
            val apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStoreBinding.store
            logSharedAuthDiagnostic(
                "shared_key_value",
                "storage_scope name=apiEnvironment " +
                    "namespace=${SharedApiEnvironmentStore.Namespace} " +
                    "scope=${apiEnvironmentKeyValueStoreBinding.scope}"
            )
            val apiEnvironmentStore = RaftAppScopeApiEnvironment.store()
            // Content-addressed avatar keys are host-free only for the active environment's hosts.
            apiEnvironmentStore.current().let { RaftAvatarTrustedHosts.configure(it.apiBaseUrl, it.webUrl) }
            CoroutineScope(SupervisorJob()).launch {
                apiEnvironmentStore.state.collect { RaftAvatarTrustedHosts.configure(it.apiBaseUrl, it.webUrl) }
            }
            val offlineResourceCache =
                RaftOfflineResourceCache(
                    rows = SqlDelightRaftOfflineResourceRows(database),
                    environmentId = { apiEnvironmentStore.current().apiBaseUrl },
                    logger = sharedInfoLogger("SlockOfflineResources", "offline_resources"),
                )
            val tasksDataStore =
                SharedTasksDataStore(
                    ownerIdentityProvider = {
                        authStore.readSession()?.let { session ->
                            "${apiEnvironmentStore.current().apiBaseUrl.trimEnd('/')}|${session.user.id}"
                        }
                    },
                    currentServerIdProvider = {
                        authStore.readSession()?.currentServerId
                    },
                    offlineLists = SharedTasksOfflineLists(offlineResourceCache.resource(TaskListResourceSpec)),
                )
            CoroutineScope(SupervisorJob()).launch {
                authStore.authSessionEvents.collect { event ->
                    when (event) {
                        is AuthSessionEvent.Authenticated,
                        is AuthSessionEvent.Cleared -> tasksDataStore.clearOwnerData()
                        is AuthSessionEvent.BindingMetadataChanged,
                        is AuthSessionEvent.TokensRotated -> Unit
                    }
                }
            }
            val platformFileResolverBinding = RaftSharedPlatformFileResolverProvider.createBinding()
            val platformFileResolver = platformFileResolverBinding.resolver
            logSharedAuthDiagnostic(
                "shared_network_file_resolver",
                "storage_scope=${platformFileResolverBinding.scope}"
            )
            // #proj-mobile:#294: slock-avatar-render-v1 tables and startup prewarm are retired;
            // Coil's disk cache is the sole avatar byte/image cache.
            val avatarCacheMetadataStore: RaftAvatarCacheMetadataStore = NoOpRaftAvatarCacheMetadataStore
            val avatarCacheCoordinator =
                RaftAvatarCacheCoordinator(avatarCacheMetadataStore, ::defaultNowMillis)
            val avatarOwnerResolver: suspend (String?) -> RaftAvatarCacheOwner? = { requestedServerId ->
                raftCurrentAvatarCacheOwner(
                    environmentId = apiEnvironmentStore.current().apiBaseUrl,
                    session = authStore.readSession(),
                    requestedServerId = requestedServerId
                )
            }
            val avatarMountedBinding =
                RaftAvatarMountedBinding(
                    coordinator = avatarCacheCoordinator,
                    currentOwner = avatarOwnerResolver,
                    diagnostics =
                        { message ->
                            if ("result=ok" in message) {
                                raftLogInfo("SlockAvatar", "avatar_mounted_binding $message")
                            } else {
                                raftLogWarn("SlockAvatar", "avatar_mounted_binding $message")
                            }
                        }
                )
            val avatarCacheRuntime =
                RaftAvatarCacheRuntime(
                    coordinator = avatarCacheCoordinator,
                    currentOwner = avatarOwnerResolver,
                    mountedBinding = avatarMountedBinding,
                    logger = sharedWarnLogger("SlockAvatarCache", "avatar_cache_runtime")
                )
            CoroutineScope(SupervisorJob()).launch {
                authStore.authSessionEvents.collect { event ->
                    when (event) {
                        // A later sign-in may open the account deletion page, or ask for SSO, again.
                        is AuthSessionEvent.Cleared -> {
                            RaftAccountDeletionPendingSignal.Process.reset()
                            RaftSsoRequiredSignal.Process.reset()
                        }
                        is AuthSessionEvent.Authenticated,
                        is AuthSessionEvent.BindingMetadataChanged,
                        is AuthSessionEvent.TokensRotated -> Unit
                    }
                }
            }
            val authDataSource =
                createSharedAuthDataSourceForCurrentEnvironment(
                    apiEnvironmentStore = apiEnvironmentStore,
                    authStore = authStore,
                    avatarCacheRuntime = avatarCacheRuntime
                )
            val authLossFunnel =
                SharedAuthLossFunnel(
                    restoreSession = { authDataSource.restoreSession() },
                    clearSessionIfMatches = { session, reason, clearSource ->
                        authStore.clearSessionIfMatchesOutcome(
                            expectedAccountId = session.user.id,
                            expectedAccessToken = session.accessToken,
                            expectedRefreshToken = session.refreshToken,
                            reason = reason,
                            source = clearSource,
                        )
                    },
                    scope = CoroutineScope(SupervisorJob()),
                    diagnosticsLogger = sharedInfoLogger(SHARED_AUTH_LOG_TAG, "auth_loss_funnel")
                )
            val networkWarningLogger = sharedNetworkLogger("SlockSharedNetwork", "shared_network")
            val networkRequestScheduler = RaftRequestScheduler()
            val networkExecutor = createPlatformRaftHttpExecutor(networkWarningLogger)
            val networkReachabilityGates = RaftHostReachabilityGates()
            val networkInFlightGets = RaftInFlightGetDedupe()
            val networkChannelDetailCoalescer = RaftChannelDetailGetCoalescer()
            val terminalAuthLossReporter: suspend (StoredAuthSession, String) -> Unit = { session, caller ->
                authLossFunnel.report(
                    AuthLossSignal(
                        source = caller,
                        tokenFingerprint = session.accessToken.authLossTokenFingerprint(),
                        capturedSession = session,
                    )
                ).await()
            }
            val scopedNetworkClientFactory: (RaftAuthSessionStore) -> RaftNetworkClient = { sessionStore ->
                SharedRaftNetworkClient(
                    sessionStore = sessionStore,
                    baseUrlProvider = { apiEnvironmentStore.current().apiBaseUrl },
                    warningLogger = networkWarningLogger,
                    criticalWarningLogger =
                        sharedWarnLogger(
                            "SlockSharedNetwork",
                            "shared_network",
                            RaftPlatformLogHint.AuthCritical
                        ),
                    terminalAuthLossReporter = terminalAuthLossReporter,
                    requestScheduler = networkRequestScheduler,
                    platformFileResolver = platformFileResolver,
                    executor = networkExecutor,
                    reachabilityGates = networkReachabilityGates,
                    inFlightGets = networkInFlightGets,
                    channelDetailCoalescer = networkChannelDetailCoalescer
                )
            }
            val ownerScopedNetworkClientFactory: (RaftAuthSessionStore, String) -> RaftNetworkClient =
                { sessionStore, ownerApiBaseUrl ->
                    SharedRaftNetworkClient(
                        sessionStore = sessionStore,
                        baseUrlProvider = { ownerApiBaseUrl },
                        warningLogger = networkWarningLogger,
                        criticalWarningLogger =
                            sharedWarnLogger(
                                "SlockSharedNetwork",
                                "shared_network",
                                RaftPlatformLogHint.AuthCritical
                            ),
                        terminalAuthLossReporter = terminalAuthLossReporter,
                        requestScheduler = networkRequestScheduler,
                        platformFileResolver = platformFileResolver,
                        executor = networkExecutor,
                        reachabilityGates = networkReachabilityGates,
                        inFlightGets = networkInFlightGets,
                        channelDetailCoalescer = networkChannelDetailCoalescer
                    )
                }
            val networkClient = scopedNetworkClientFactory(authStore)
            // Product analytics (RFC-067) sends through the shared client at Background priority.
            RaftProductEvents.bindTransport(networkClient)
            // Client trace events (`/api/web-traces`, as web): `activity:inplace_check`. A batch goes
            // out only while the session this client authenticates with is the account that recorded it.
            RaftClientTraceEvents.bindTransport(networkClient) { authStore.readSession()?.user?.id }
            val messageBodyFontSizeStore =
                SharedMessageBodyFontSizeStore(
                    scope = RaftStoreThreading.newScope(),
                    diagnosticsLogger = sharedInfoLogger("SlockMessageBodyFontSize", "store"),
                    lastKnown = MessageBodyFontSizeLastKnownStore(sharedPreferenceStore),
                )
            val processServerMembersRepository =
                RealMemberRepository(
                    sessionStore = authStore,
                    networkClient = networkClient,
                    logger = sharedInfoLogger("SlockServerMembers", "process_server_members_repository")
                )
            val processServerMembersCore =
                ProcessServerMembersCore(
                    fetchMembers = processServerMembersRepository::getMembers,
                    fetchAgents = processServerMembersRepository::getServerAgents,
                    logger = sharedInfoLogger("SlockServerMembers", "process_server_members")
                )
            // task #136 locked SSOT: ONE process registry, owner-scoped keys,
            // zero per-shell mirrors. The auth binding is an OBSERVABLE ATOMIC
            // process state (review blocker: a plain cached var froze read
            // flows at construction — pre-auth mounts stayed empty forever and
            // A→B account switches left mounted surfaces on A — and let late
            // old-auth events be attributed to the current account):
            //  - READ edges flatMapLatest over [presenceOwnerFlow], so every
            //    mounted flow rebinds automatically on login / account switch /
            //    environment change;
            //  - the WRITE edge freezes one atomic context snapshot per event
            //    delivery: between Cleared and the next Authenticated the
            //    context is null and late events are DROPPED, never re-read
            //    against the new account.
            val processMemberPresenceRegistry =
                ProcessMemberPresenceRegistry(rejectLogger = sharedInfoLogger("SlockPresence"))
            val presenceAuthContext = MutableStateFlow<ProcessPresenceAuthContext?>(null)
            // The same binding, typed (architecture review B): Unknown until the stored session is
            // read, then SignedIn / SignedOut / Corrupt. Followers that delete or retire data key on
            // this, never on presenceAuthContext being null (null is also "not known yet").
            val accountBinding =
                MutableStateFlow<RaftAccountBinding<ProcessPresenceAuthContext>>(RaftAccountBinding.Unknown)
            val resolvePresenceOwner: (String) -> ProcessServerMembersOwner? = { rawServerId ->
                presenceAuthContext.value?.ownerFor(rawServerId)
            }
            val resolvePresenceOwnerFlow: (String) -> Flow<ProcessServerMembersOwner?> = { rawServerId ->
                presenceAuthContext
                    .map { context -> context?.ownerFor(rawServerId) }
                    .distinctUntilChanged()
            }
            // Owner + generation frozen from ONE immutable context snapshot —
            // snapshot-driven store writes stamp the generation so a write
            // racing an auth clear is rejected inside the registry CAS.
            val resolvePresenceWrite: (String) -> ProcessPresenceWrite? = { rawServerId ->
                presenceAuthContext.value?.writeFor(rawServerId)
            }
            val resolveActivityUnreadOwner: (String) -> ProcessActivityUnreadOwner? = { rawServerId ->
                presenceAuthContext.value?.activityUnreadOwnerFor(rawServerId)
            }
            val resolveActivityUnreadOwnerFlow: (String) -> Flow<ProcessActivityUnreadOwner?> = { rawServerId ->
                presenceAuthContext
                    .map { context -> context?.activityUnreadOwnerFor(rawServerId) }
                    .distinctUntilChanged()
            }
            val processActivityUnreadRepository =
                ProcessActivityUnreadRepository(
                    sessionStore = authStore,
                    networkClient = networkClient,
                    logger = sharedInfoLogger("SlockActivityUnread", "process_activity_unread_repository"),
                    warningLogger = sharedWarnLogger("SlockActivityUnread", "process_activity_unread_repository")
                )
            val processActivityUnreadProjectionCache =
                ProcessActivityUnreadProjectionCache(
                    RaftSharedKeyValueStoreProvider.create(
                        namespace = RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE,
                        storageName = "Activity unread summary"
                    ).store
                )
            val activityUnreadSummaryStore =
                ProcessActivityUnreadStore(
                    fetchSummary = processActivityUnreadRepository::load,
                    ownerResolver = resolveActivityUnreadOwner,
                    ownerFlowResolver = resolveActivityUnreadOwnerFlow,
                    projectionCache = processActivityUnreadProjectionCache,
                    scope = CoroutineScope(SupervisorJob()),
                    realtimeMinIntervalMillis = ActivityUnreadRealtimeMinIntervalMillis,
                    logger = sharedInfoLogger("SlockActivityUnread", "process_activity_unread")
                ).also { store -> store.followAppLifecycle() }
            // ONE sink instance shared by the legacy and every scoped realtime
            // owner — the single production presence write edge.
            val memberPresenceRealtimeSink =
                processMemberPresenceRealtimeSink(
                    currentPresenceAuthContext = { presenceAuthContext.value },
                    registry = processMemberPresenceRegistry,
                    diagnosticsLogger = sharedInfoLogger("SlockPresence", "presence_write_edge"),
                )
            val activityUnreadRealtimeSink =
                processActivityUnreadRealtimeSink(
                    currentAuthContext = { presenceAuthContext.value },
                    store = activityUnreadSummaryStore,
                    diagnosticsLogger = sharedInfoLogger("SlockActivityUnread", "process_activity_unread_realtime")
                )
            // Roster invalidation stays on typed auth events. Presence
            // context/clear does NOT live here: it is published exclusively
            // from the authority registry's authRevision stream (see the
            // collector installed right after the registry below), so the
            // context's authorityGeneration and its retired-then-published
            // registry writeGeneration always come from ONE coordinated
            // transition — a second writer here could interleave and publish
            // a torn (authority generation, registry generation) pair.
            // task #1133: last server evaluation of the offline-cache flag, so an offline cold start gates
            // like the last online session. Keyed by (environment, account, server) — not by token.
            val serverFeatureFlagLastKnown =
                ServerFeatureFlagLastKnownStore(
                    store = sharedPreferenceStore,
                    currentBinding = { presenceAuthContext.value?.toServerFeatureFlagLastKnownBinding() },
                )
            CoroutineScope(SupervisorJob()).launch {
                authStore.authSessionEvents.collect { event ->
                    when (event) {
                        is AuthSessionEvent.Authenticated ->
                            {
                                RaftShareSuggestionsHostProvider.clear("auth_session_authenticated")
                                ProcessDiagnosticFeatureFlagOverrides.registry.clearObservations()
                                build.raft.app.RaftScreenshotFeedbackRuntime.notifyEligibilitySignalChanged()
                                processServerMembersCore.invalidateAll(reason = "authenticated")
                                activityUnreadSummaryStore.invalidateAll(reason = "authenticated")
                            }
                        is AuthSessionEvent.Cleared ->
                            {
                                RaftShareSuggestionsHostProvider.clear("auth_session_cleared")
                                RaftProductEvents.setSignedOut()
                                serverFeatureFlagLastKnown.clearAll()
                                RaftMinBuildGate.onSignedOut()
                                ProcessDiagnosticFeatureFlagOverrides.registry.clearObservations()
                                build.raft.app.RaftScreenshotFeedbackRuntime.notifyEligibilitySignalChanged()
                                build.raft.app.RaftScreenshotFeedbackRuntime.controller.onEligibilityLost()
                                RaftFcmProbeHost.onSignedOut()
                                processServerMembersCore.invalidateAll(reason = "cleared")
                                activityUnreadSummaryStore.invalidateAll(reason = "cleared")
                            }
                        is AuthSessionEvent.BindingMetadataChanged,
                        is AuthSessionEvent.TokensRotated -> Unit
                    }
                }
            }
            val serverFeatureFlags =
                SharedServerFeatureFlags(
                    api = RaftFeatureFlagsNetworkApi(networkClient),
                    diagnosticsLogger = sharedInfoLogger("SlockFeatureFlags", "feature_flags"),
                    lastKnown = serverFeatureFlagLastKnown,
                    // The Navigation 3 stack and iOS native navigation switches are read once at app
                    // launch: keep their last values.
                    onEvaluation = { serverId, key, enabled, _, variant, _, _ ->
                        // Minimum supported build: the gate follows every evaluation of its flag.
                        RaftMinBuildGate.recordServerEvaluation(serverId, key, enabled, variant)
                        build.raft.app.nav.RaftNav3StackFlag.recordServerEvaluation(sharedPreferenceStore, key, enabled)
                        build.raft.app.nav.RaftIosNativeNavFlag.recordServerEvaluation(sharedPreferenceStore, key, enabled)
                        if (key == MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY) RaftAppSettingsSnapshotReport.onIosNativeNavFlagRead()
                        if (key == MOBILE_FCM_PROBE_FEATURE_FLAG_KEY) RaftFcmProbeHost.onFeatureFlagChanged()
                    },
                )
            // An offline cold start may read last-known flags before the binding is known (they read
            // as defaults then); re-read them whenever the binding they are keyed by changes.
            CoroutineScope(SupervisorJob()).launch {
                accountBinding
                    .map { binding -> binding.accountOrNull?.toServerFeatureFlagLastKnownBinding() }
                    .distinctUntilChanged()
                    .collect {
                        serverFeatureFlags.onLastKnownBindingChanged()
                        build.raft.app.RaftScreenshotFeedbackRuntime.notifyEligibilitySignalChanged()
                        RaftFcmProbeHost.onFeatureFlagChanged()
                    }
            }
            CoroutineScope(SupervisorJob()).launch {
                serverFeatureFlags.snapshotRevision.collect {
                    build.raft.app.RaftScreenshotFeedbackRuntime.notifyEligibilitySignalChanged()
                    RaftFcmProbeHost.onFeatureFlagChanged()
                }
            }
            val attachmentCommentsReadCacheV2 = AttachmentCommentsReadCacheV2()
            val canonicalMessageV2Store =
                SqlDelightCanonicalMessageV2Store(
                    database,
                    now = ::defaultNowMillis,
                    logger = sharedInfoLogger("CanonicalMessageV2Store", "canonical_v2_retention"),
                    // Lines already start with `canonical_v2_partial_completed`.
                    factLogger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2Store"),
                )
            val canonicalMessageV2GenerationEngine =
                CanonicalMessageV2GenerationEngine(
                    coordinator = CanonicalMessageV2BaselineCoordinator(),
                    store = canonicalMessageV2Store,
                    snapshotCommitObserver =
                        CanonicalMessageV2SnapshotCommitObserver(attachmentCommentsReadCacheV2::invalidateParent),
                )
            val canonicalMessageV2Canary =
                CanonicalMessageV2ProductionCanary(
                    generationEngine = canonicalMessageV2GenerationEngine,
                    // Lines already start with `canonical_v2_canary` (task #168: no doubled prefix).
                    logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2Canary"),
                )
            // Reply-preview windows are viewer-scoped thread state, not V2 facts: they live beside
            // the V2 partition (page key = server + principal + parent scope), so a network-only first
            // frame can use them as well.
            val threadReplyWindowCache =
                RaftThreadReplyWindowCache(
                    storage = SqlDelightRaftThreadReplyWindowStorage(database, ::defaultNowMillis),
                    scope = CoroutineScope(SupervisorJob()),
                    diagnosticsLogger = sharedInfoLogger("ThreadReplyWindowCache"),
                )
            // Read side of the same partition the canary writes: same store instance, no
            // baseline-coordinator side effects (task #1123).
            val threadDurableLastKnownReader =
                CanonicalMessageV2DurableLastKnownReader(
                    store = canonicalMessageV2Store,
                    logger = sharedInfoLogger("ThreadDurableLastKnown", "thread_durable_last_known"),
                    replyWindowCache = threadReplyWindowCache,
                )
            // Parallel on purpose: sends, realtime ingest/decode and authority work are not store-state
            // writes. Only the stores built on RaftStoreThreading.newScope() (home, activity, translation,
            // font size) run on the serial store dispatcher; their dispatch() hops intents onto it.
            val threadSendScope = CoroutineScope(SupervisorJob())
            val realtimeAuthorityDiagnosticsLogger =
                sharedInfoLogger("RealtimeAuthority", "realtime_authority")
            val realtimeAuthorityRegistry =
                RealtimeAuthorityRegistry(
                    authRevisionStore = KeyValueRealtimeAuthRevisionStore(authKeyValueStore),
                    processInstanceId =
                        "mobile-${defaultNowMillis()}-${Random.nextLong()}",
                    diagnosticsLogger = realtimeAuthorityDiagnosticsLogger,
                )
            val canonicalFinalIntegration =
                canonicalFinalIntegrationFactory?.let { factory ->
                    val registrationDirectory = RealtimeCanonicalFinalSinkRegistrationDirectory()
                    val canonicalFinalScope = CoroutineScope(SupervisorJob())
                    factory.create(
                        RaftSharedCanonicalFinalIntegrationFactoryScope(
                            registry = realtimeAuthorityRegistry,
                            authStore = authStore,
                            authEvents = authStore,
                            crossProcessAuthEvents = authSessionEvents,
                            serverFeatureFlags = serverFeatureFlags,
                            diagnosticsLogger = realtimeAuthorityDiagnosticsLogger,
                            registrationDirectory = registrationDirectory,
                            createLazyRouter = {
                                RealtimeCanonicalFinalSinkLazyRouter(
                                    runIfCurrentGrant = realtimeAuthorityRegistry::runIfCurrentGrant,
                                    sidecarFactory = {
                                        RealtimeCanonicalFinalSinkSidecar(
                                            scope = canonicalFinalScope,
                                            capacity = SharedCanonicalFinalSinkCapacity,
                                            runIfCurrentGrant = realtimeAuthorityRegistry::runIfCurrentGrant,
                                            registrationDirectory = registrationDirectory,
                                        )
                                    },
                                )
                            },
                        ),
                    )
                }
            val realtimeAuthorityProvider =
                createSharedRealtimeAuthorityProvider(
                    registry = realtimeAuthorityRegistry,
                    authStore = authStore,
                    authEvents = authStore,
                    currentEnvironmentId = {
                        apiEnvironmentStore.current().apiBaseUrl.normalizedEnvironmentId()
                    },
                    diagnosticsLogger = realtimeAuthorityDiagnosticsLogger,
                    canonicalFinalIntegration = canonicalFinalIntegration,
                    // Presence binding readiness (task #136): the ONE writer of
                    // presenceAuthContext/clearAll, synchronous with every auth
                    // revision advance and ordered before grant publication —
                    // see processPresenceBindingTransition.
                    onAuthRevisionAdvanced = {},
                    onAuthBindingAdvanced =
                        processAccountBindingTransitionWithPendingSends(
                            presenceAuthContext = presenceAuthContext,
                            accountBinding = accountBinding,
                            registry = processMemberPresenceRegistry,
                            journal = threadPendingSendJournal,
                            currentEnvironmentId = {
                                apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
                            },
                            diagnosticsLogger = realtimeAuthorityDiagnosticsLogger,
                        ),
                )
            // Pixel-avatar runtime table (task #352): apply the MMKV-cached document
            // synchronously (before first render), then fire-and-forget a refresh from the
            // deployed WEB app origin (webUrl, not the API base URL) — the same canonical
            // pixelAvatars.json the generator consumes. Bad data never crashes rendering;
            // the generated bundled table stays the fallback.
            val pixelAvatarStore = KeyValuePixelAvatarStore(sharedPreferenceStore)
            RaftPixelAvatarRuntime.applyRuntimeJson(pixelAvatarStore.readJson(), source = "cache")
            CoroutineScope(SupervisorJob()).launch {
                // Jittered delay: the refresh is not first-screen data (the cached/bundled
                // table is already applied above), so keep it off the startup window where
                // it would contend with channel/message fetches — and spread clients out
                // so a web deploy doesn't produce a synchronized fetch spike.
                delay(Random.nextLong(PixelAvatarRefreshDelayMinMs, PixelAvatarRefreshDelayMaxMs))
                refreshPixelAvatarsFromWeb(
                    networkClient = networkClient,
                    store = pixelAvatarStore,
                    webBaseUrl = apiEnvironmentStore.current().webUrl
                )
            }
            val memberAvatarRefStore = RaftMemberAvatarRefStore(sharedPreferenceStore)
            val jointActorRefStore = RaftJointActorRefStore(sharedPreferenceStore)
            val serverMembersDurableStore =
                RaftServerMembersDurableStore(
                    storage = SqlDelightRaftServerMembersDurableStorage(database),
                    logger = sharedInfoLogger("SlockServerMembers", "server_members_durable"),
                )
            lateinit var provider: RaftSharedStoreProvider
            val channelMembersOffline =
                SharedChannelMembersOffline(
                    resource = offlineResourceCache.resource(ChannelMembersResourceSpec),
                    logger = sharedInfoLogger("SlockOfflineResources", "channel_members_offline"),
                )
            val serverMembersStore =
                SharedServerMembersStore(
                    memberRepository = processServerMembersRepository,
                    logger = sharedInfoLogger("SlockServerMembers"),
                    processCore = processServerMembersCore,
                    presenceRegistry = processMemberPresenceRegistry,
                    presenceOwner = { serverId -> resolvePresenceOwner(serverId.value) },
                    presenceOwnerFlow = { serverId -> resolvePresenceOwnerFlow(serverId.value) },
                    presenceWrite = { serverId -> resolvePresenceWrite(serverId.value) },
                    processOwner = { serverId ->
                        authStore.readSession()?.let { session ->
                            val environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
                            val accountId = session.user.id.trim()
                            val normalizedServerId = serverId.value.trim()
                            if (environmentId.isBlank() || accountId.isBlank() || normalizedServerId.isBlank()) {
                                null
                            } else {
                                ProcessServerMembersOwner(environmentId, accountId, normalizedServerId)
                            }
                        }
                    },
                    avatarRefStore = memberAvatarRefStore,
                    jointActorRefStore = jointActorRefStore,
                    durableStore = serverMembersDurableStore,
                    channelMembersOffline = channelMembersOffline,
                    actorResolve = jointActorResolve(networkClient),
                    actorResolveScope = RaftStoreThreading.newScope(),
                    incrementalSyncCoversRoster = { serverId -> provider.syncChangesPullAllowed(serverId.value) }
                )
            val foregroundReconciler =
                SharedForegroundReconciler(
                    authStore = authStore,
                    refreshMembers = { serverId ->
                        val shellScope = provider.scopesForServer(serverId.value).firstOrNull()
                        val targetStore = shellScope?.let(provider::serverMembersStore) ?: serverMembersStore
                        sharedInfoLogger("SlockForegroundReconcile", "shared_foreground_reconcile")(
                            "foreground_reconcile_member_owner serverId=${serverId.value} " +
                                "owner=${shellScope?.shellInstanceId ?: "legacy_fallback"}"
                        )
                        // Incremental sync (flag on): what changed while backgrounded comes row by row
                        // from the pull; only agent presence (not in the change log) is read again.
                        val fullRefresh: suspend () -> Unit = {
                            targetStore.refreshMembers(
                                serverId = serverId,
                                priority = DataRequestPriority.Background,
                                force = true,
                                reason = "foreground_reconcile"
                            )
                        }
                        val returnBoundary = kotlin.time.TimeSource.Monotonic.markNow()
                        val syncOutcome = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        val pulled =
                            provider.requestSyncChangesPull(
                                serverId.value,
                                trigger = "foreground",
                                // Pull failed and dropped the cursor: the full refresh after all, through
                                // the jitter (a first transient failure does not, #proj-mobile:#112).
                                onFailed = {
                                    provider.scheduleSyncFallbackRefetch(serverId.value, "foreground_fallback") {
                                        fullRefresh()
                                        provider.refreshActivityForSyncFallback(serverId.value, "foreground_sync_fallback")
                                    }
                                },
                                onFinished = { ok -> syncOutcome.complete(ok) },
                                onResult = { result ->
                                    if (result != null) {
                                        val synced = result as? RaftSyncPullResult.Synced
                                        RaftClientTraceEvents.emitForegroundSyncUpdated(
                                            serverId = serverId.value,
                                            telemetry =
                                                ActivityForegroundSyncUpdated(
                                                    serverId = serverId.value,
                                                    durationMillis = returnBoundary.elapsedNow().inWholeMilliseconds,
                                                    inboxRowsApplied = synced?.inboxRowsApplied ?: 0,
                                                    syncedOk = synced != null,
                                                    timestampMillis = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
                                                ),
                                        )
                                    }
                                },
                            )
                        if (pulled) {
                            // Agent presence and machine status are not in the change log (no jitter here);
                            // share the 25 s coalesce window with reconnect refetch so both do not pull
                            // /agents and /machines back-to-back on the same foreground return, while
                            // [returnBoundary] prevents a pre-background read from suppressing this return.
                            val externalToken = provider.onExternalForegroundRefreshStarted(serverId.value, returnBoundary)
                            var foregroundOk = false
                            try {
                                var machinesOk = false
                                coroutineScope {
                                    launch {
                                        targetStore.refreshAgents(
                                            serverId = serverId,
                                            reason = "foreground_reconcile",
                                            coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                                        )
                                    }
                                    launch {
                                        machinesOk =
                                            provider.refreshMachinesForSync(
                                                serverId = serverId.value,
                                                reason = "foreground_reconcile",
                                                coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                                                notStartedBefore = returnBoundary,
                                            )
                                    }
                                }
                                val syncOk = syncOutcome.await()
                                foregroundOk = syncOk && machinesOk
                            } finally {
                                provider.onExternalForegroundRefreshCompleted(serverId.value, externalToken, foregroundOk)
                            }
                        } else {
                            fullRefresh()
                        }
                    },
                    scope = CoroutineScope(SupervisorJob()),
                    diagnosticsLogger = sharedInfoLogger("SlockForegroundReconcile", "shared_foreground_reconcile")
                )
            // Write side of the same partition (task #1124): same canary (for "is this scope yours"),
            // same engine, the process-level network client the canary's own
            // snapshot loads use.
            val threadEnteredScopeSnapshotLoader =
                CanonicalMessageV2EnteredScopeLoader(
                    canary = canonicalMessageV2Canary,
                    generationEngine = canonicalMessageV2GenerationEngine,
                    fetcher =
                        CanonicalMessageV2RestSnapshotFetcher(
                            networkClient = networkClient,
                            logger = sharedInfoLogger("CanonicalMessageV2EnterSnapshot", "canonical_v2_enter_snapshot"),
                        ),
                    logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2EnterSnapshot"),
                    isKnownNonMember = { serverId, channelId -> provider.isKnownNonMemberConversation(serverId, channelId) },
                )
            // Paging write side (task #168): same canary, engine and network client as the enter
            // loader; extends the window that loader created, never creates one.
            val threadDurablePageExtender =
                CanonicalMessageV2AdjacentPageWriter(
                    canary = canonicalMessageV2Canary,
                    generationEngine = canonicalMessageV2GenerationEngine,
                    fetcher =
                        CanonicalMessageV2RestSnapshotFetcher(
                            networkClient = networkClient,
                            logger = sharedInfoLogger("CanonicalMessageV2AdjacentPage", "canonical_v2_adjacent_page"),
                        ),
                    // Lines already start with `canonical_v2_adjacent_page` / `canonical_v2_latest_page`: a prefix
                    // here doubled the latest-page line on device (task #168).
                    logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2AdjacentPage"),
                    isKnownNonMember = { serverId, channelId -> provider.isKnownNonMemberConversation(serverId, channelId) },
                )
            val storedConversationDetails =
                StoredConversationDetails(offlineResourceCache.resource(ConversationDetailResourceSpec))
            // V-102: composer drafts are stored per signed-in account (environment + user) and
            // deleted when that account's session ends; see SharedThreadComposerDraftAccounts.
            val composerDraftLogger = sharedInfoLogger("SlockComposerDraft")
            val composerDraftAccountStore =
                SharedThreadComposerDraftAccountStore(
                    base = sharedPreferenceStore,
                    liveAccount = { presenceAuthContext.value?.toComposerDraftAccount() },
                    diagnosticsLogger = composerDraftLogger
                )
            val composerDraftAccounts =
                SharedThreadComposerDraftAccounts(
                    base = sharedPreferenceStore,
                    store = composerDraftAccountStore,
                    persistenceFor = { view ->
                        SharedThreadComposerDraftPersistence(view, diagnosticsLogger = composerDraftLogger)
                    },
                    diagnosticsLogger = composerDraftLogger
                )
            val composerDraftPersistSlice =
                SharedThreadComposerDraftPersistSlice(
                    SharedThreadComposerDraftPersistence(
                        composerDraftAccountStore,
                        diagnosticsLogger = composerDraftLogger
                    ),
                    diagnosticsLogger = composerDraftLogger,
                    // Typed text is written off the main thread, at once after a quiet period and
                    // at most every 40 ms while typing (task #246: a crash right after typing).
                    scheduler =
                        CoroutineThreadComposerDraftPersistenceScheduler(
                            CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        ),
                    // Account-scoped: nothing is read until the binding follower below binds an
                    // account (or finds nobody signed in), so "no drafts" is not known before.
                    recordsKnownAtStart = false
                )
            CoroutineScope(SupervisorJob()).launch {
                // Leftover crash-journal rows of internal 1.27 test builds, once per device (drop after 1.28).
                SharedThreadComposerDraftJournalCleanup.runOnce(sharedPreferenceStore, composerDraftLogger)
                composerDraftAccounts.onStoredSessionReadAtStart(authStore)
                // Typed: Unknown (not read yet) and Corrupt (unreadable) never delete drafts; only
                // SignedOut or another account does.
                accountBinding
                    .map { binding -> binding.map { context -> context.toComposerDraftAccount() } }
                    .distinctUntilChanged()
                    .collect { binding ->
                        runCatching { composerDraftAccounts.onAccountBinding(binding, composerDraftPersistSlice) }
                            .onFailure { error ->
                                composerDraftLogger(
                                    "thread_composer_draft_account_binding_failed " +
                                        "error=${error::class.simpleName.orEmpty()}"
                                )
                            }
                    }
            }
            val sharedActivityPersistStorage = SharedActivityPersistStorage(sharedPreferenceStore)
            provider = RaftSharedStoreProvider(
                host = host,
                sqlDatabase = database,
                authKeyValueStore = authKeyValueStore,
                sharedPreferenceStore = sharedPreferenceStore,
                memberAvatarRefStore = memberAvatarRefStore,
                jointActorRefStore = jointActorRefStore,
                serverMembersDurableStore = serverMembersDurableStore,
                apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStore,
                authStore = authStore,
                authSessionEvents = authSessionEvents,
                authDataSource = authDataSource,
                authLossFunnel = authLossFunnel,
                authRuntimeCapabilityStore = SharedAuthRuntimeCapabilityStore(),
                networkClient = networkClient,
                networkReachabilityGates = networkReachabilityGates,
                scopedNetworkClientFactory = scopedNetworkClientFactory,
                ownerScopedNetworkClientFactory = ownerScopedNetworkClientFactory,
                serverFeatureFlags = serverFeatureFlags,
                canonicalMessageV2Canary = canonicalMessageV2Canary,
                canonicalMessageV2GenerationEngine = canonicalMessageV2GenerationEngine,
                localDataStore = localDataStore,
                avatarCacheMetadataStore = avatarCacheMetadataStore,
                avatarCacheCoordinator = avatarCacheCoordinator,
                avatarCacheRuntime = avatarCacheRuntime,
                avatarMountedBinding = avatarMountedBinding,
                agentDetailStore = SharedAgentDetailStore(
                    logger = sharedInfoLogger("SlockAgentDetail", "shared_agent_detail_store")
                ),
                agentPanelTabOrderStore =
                    SharedAgentPanelTabOrderStore(
                        cache = KeyValueAgentPanelTabOrderPreferenceRepository(sharedPreferenceStore)
                    ),
                tasksDataStore = tasksDataStore,
                serverMembersStore = serverMembersStore,
                processServerMembersCore = processServerMembersCore,
                processMemberPresenceRegistry = processMemberPresenceRegistry,
                activityUnreadSummaryStore = activityUnreadSummaryStore,
                presenceOwnerResolver = resolvePresenceOwner,
                presenceOwnerFlowResolver = resolvePresenceOwnerFlow,
                presenceWriteResolver = resolvePresenceWrite,
                replyCacheAuthContext = presenceAuthContext,
                accountBinding = accountBinding,
                memberPresenceRealtimeSink = memberPresenceRealtimeSink,
                composerDraftPersistSlice = composerDraftPersistSlice,
                activityPersistStorage = sharedActivityPersistStorage,
                activityPersistSlice =
                    createSharedActivityPersistSlice(
                        storage = sharedActivityPersistStorage,
                        diagnosticsLogger = sharedInfoLogger("SlockActivityPersist", "shared_activity_persist"),
                        failureLogger = sharedErrorLogger("SlockActivityPersist", "shared_activity_persist"),
                        scrollDeferScope = threadSendScope,
                    ),
                homeSavedStateCache = SharedHomeSavedStateCache(offlineResourceCache.resource(SavedListResourceSpec)),
                offlineResourceCache = offlineResourceCache,
                channelMembersOffline = channelMembersOffline,
                homeIdentityPersistSlice =
                    createSharedHomeIdentityPersistSlice(
                        storage = SharedPreferencePersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = sharedInfoLogger("SlockHomePersist", "shared_home_persist"),
                        failureLogger = sharedErrorLogger("SlockHomePersist", "shared_home_persist"),
                        scrollDeferScope = threadSendScope,
                    ),
                apiEnvironmentStore = apiEnvironmentStore,
                eventBus = eventBusRelay,
                realtimeDomainEventOwner =
                    createSharedRealtimeDomainEventOwner(
                        tasksDataStore = tasksDataStore,
                        cursorStore = RealtimeAppliedSeqCursorStore(sharedPreferenceStore),
                        authorityProvider = realtimeAuthorityProvider,
                        onAgentDeleted = { serverId, agentId -> provider.evictAgentDetail(serverId, agentId) },
                        onChannelMembersUpdated = { serverId, channelId, admission ->
                            provider.applyChannelMembersUpdated(serverId, channelId, admission)
                        },
                    ).also { owner ->
                        // task #136: agent presence flows straight into the
                        // process registry's owner partition for the realtime
                        // owner's whole lifetime — never only while a page has
                        // sinks; stores read the registry, zero mirrors. The
                        // ONE production write edge, admission-frozen (see
                        // processMemberPresenceRealtimeSink).
                        owner.installMemberPresenceSink(memberPresenceRealtimeSink)
                        owner.installActivityUnreadSink(activityUnreadRealtimeSink)
                        owner.installServerAppearanceSink { principalId, event ->
                            authStore.applyRealtimeServerAppearance(principalId, event)
                        }
                        owner.installUserProfileUpdatedSink { principalId, event ->
                            provider.applyUserProfileUpdated(principalId, event)
                        }
                        owner.installFeedbackUnreadSink { principalId, event ->
                            provider.feedbackUnread.applyRealtime(principalId, event)
                        }
                        owner.installActorUpdatedSink { serverId, event ->
                            provider.applyActorUpdated(serverId, event)
                        }
                        owner.installAgentActivitySubscribedSink { serverId, event ->
                            provider.agentActivitySubscriptions.onSubscribed(serverId, event.agentIds)
                        }
                        owner.installMachineStatusSink { serverId, _ ->
                            provider.onMachineStatusRealtimeEvent(serverId)
                        }
                        owner.installMachinesRealtimeSink { serverId, event ->
                            provider.onMachinesRealtimeEvent(serverId, event.reason)
                        }
                    },
                realtimeAuthorityProvider = realtimeAuthorityProvider,
                messageTranslationStore =
                    SharedMessageTranslationStore(
                        authStore = authStore,
                        api = NetworkRaftMessageTranslationApi(networkClient),
                        scope = RaftStoreThreading.newScope(),
                        translationMode = {
                            KeyValueChatLayoutPreferenceStore(sharedPreferenceStore).readTranslationModeSync()
                        },
                        translationTargetLanguage = {
                            KeyValueChatLayoutPreferenceStore(sharedPreferenceStore).readTranslationTargetLanguageSync()
                        },
                        displayLanguage = {
                            KeyValueLocalePreferenceStore(sharedPreferenceStore)
                                .readLocaleSync()
                                .displayLanguageTag(raftSystemPreferredLanguageTagsOrEmpty())
                        },
                        diagnosticsLogger = sharedInfoLogger("SlockMessageTranslation", "shared_message_translation")
                    ),
                messageBodyFontSizeStore = messageBodyFontSizeStore,
                threadMessageStore = SharedThreadMessageStore(),
                threadPayloadStore = SharedThreadPayloadStore(CoroutineScope(SupervisorJob())),
                threadDurableLastKnownReader = threadDurableLastKnownReader,
                threadEnteredScopeSnapshotLoader = threadEnteredScopeSnapshotLoader,
                threadDurablePageExtender = threadDurablePageExtender,
                threadDurableAccessEvictor =
                    CanonicalMessageV2DurableAccessEvictor(
                        engine = canonicalMessageV2GenerationEngine,
                        logger = sharedInfoLogger("CanonicalMessageV2Store", "canonical_v2_access_evict"),
                    ),
                threadPendingSendJournal = threadPendingSendJournal,
                channelSettingsSnapshotCache =
                    ChannelSettingsSnapshotCache(
                        stored = offlineResourceCache.resource(ChannelSettingsResourceSpec),
                        storedDetails = storedConversationDetails,
                    ),
                storedConversationDetails = storedConversationDetails,
                foregroundReconciler = foregroundReconciler,
                homeStoreScope = RaftStoreThreading.newScope(),
                homeActivityStoreScope = RaftStoreThreading.newScope(),
                threadSendScope = threadSendScope
            )
            return provider.also {
                provider.followProcessServerTheme()
                provider.installServerNotificationPreferencesRealtimeRefresh()
                val pushLifecycleScope = CoroutineScope(SupervisorJob())
                RaftPushRegistrationLifecycle.observeSessionEvents(
                    source = authStore.authSessionEvents,
                    scope = pushLifecycleScope
                )
                pushLifecycleScope.observeProcessReadReceiptAuthLifecycle(
                    events = authStore.authSessionEvents,
                    lifecycle =
                        ProcessReadReceiptOwnerLifecycle(
                            readCurrentOwners = provider::currentProcessReadReceiptOwners,
                            readVisibleOrActiveOwners = provider::visibleOrActiveProcessReadReceiptOwners,
                            revokeOwner = provider::revokeProcessReadReceiptOwner,
                        ),
                )
            }
        }
    }
}

/**
 * Converges the legitimate same-environment authority transition where durable principal B is
 * already committed while either the provider-owned immutable context or this shell's selected
 * server owner still names A. The caller must retire any previously published producer token before
 * invoking this waiter. Environment mismatches, missing sessions, missing contexts, disposed routes,
 * and sessions without a valid selected server are not repaired here and remain fail-closed.
 */
internal suspend fun awaitThreadReplyCacheIdentityTransition(
    authContext: StateFlow<ProcessPresenceAuthContext?>,
    sessionStore: RaftAuthSessionStore,
    selectedServerStore: SelectedServerStore,
    transitionAnchor: ThreadReplyCacheIdentityTransitionAnchor?,
    environmentIdProvider: () -> String,
    beforeSelectedServerReconcile: suspend () -> Unit = {},
): Boolean {
    while (true) {
        val environmentId = environmentIdProvider().trim().trimEnd('/')
        val session = sessionStore.readSession() ?: return false
        val principalId = session.user.id.trim().takeIf { it.isNotBlank() } ?: return false
        val observedContext = authContext.value ?: return false
        if (environmentId.isBlank() || observedContext.environmentId != environmentId) return false
        if (observedContext.accountId != principalId) {
            authContext.first { current -> current != observedContext }
            continue
        }
        if (selectedServerStore.disposed.value) return false

        val anchor = transitionAnchor ?: return false
        if (
            anchor.environmentId != environmentId ||
            anchor.principalId == principalId ||
            observedContext.authorityGeneration <= anchor.authGeneration
        ) {
            return false
        }

        // Capture remains strictly fail-closed on an A-owned route. Only after the old producer
        // token has been retired, session/context agree on B, and the retired A anchor proves this
        // exact account transition may the provider-owned readiness transition move the route to B.
        beforeSelectedServerReconcile()
        val selection =
            selectedServerStore.convergeReplyCacheAuthorityTransition(
                expectedPreviousOwner = anchor.principalId,
                session = session,
                authGeneration = observedContext.authorityGeneration,
            ) ?: return false
        if (selection.serverId.isNullOrBlank() || selectedServerStore.disposed.value) return false

        val confirmedEnvironmentId = environmentIdProvider().trim().trimEnd('/')
        val confirmedSession = sessionStore.readSession()
        val confirmedContext = authContext.value
        if (
            confirmedEnvironmentId == environmentId &&
            confirmedSession == session &&
            confirmedContext == observedContext
        ) {
            return true
        }
        // A concurrent environment/session/context transition invalidated the reconciliation.
        // Resample the whole provider-owned tuple; the next iteration preserves all fail-close gates.
    }
}

/**
 * Handles a possibly queued process-session invalidation without letting an older Cleared delivery
 * strand live shells on the departed principal. A non-null process session is authoritative: every
 * live shell is reconciled to it. Only a still-null session authorizes clearing active selections.
 */
internal suspend fun reconcileOrClearActiveShellServerSelections(
    authStore: RaftAuthSessionStore,
    selectedServerStores: RaftShellScopedStoreCache<SelectedServerStore>,
) {
    authStore.readSession()?.let { currentSession ->
        selectedServerStores.entries().forEach { (_, selectionStore) ->
            selectionStore.reconcile(currentSession)
        }
        return
    }
    selectedServerStores.clearActiveServerSelections()
    // Close the remaining check/clear race: if login completed while the fan-out was clearing
    // stores, immediately reconcile every live shell from the new process session.
    authStore.readSession()?.let { restoredSession ->
        selectedServerStores.entries().forEach { (_, selectionStore) ->
            selectionStore.reconcile(restoredSession)
        }
    }
}

internal fun createSharedAuthDataSourceForCurrentEnvironment(
    apiEnvironmentStore: SharedApiEnvironmentStore,
    authStore: RaftAuthSessionStore,
    avatarCacheRuntime: RaftAvatarCacheRuntime? = null,
    avatarCacheNativeCache: RaftAvatarNativeCache = UnsupportedRaftAvatarNativeCache,
    transportFactory: (() -> String) -> RaftAuthTransport = { baseUrlProvider ->
        SharedRaftAuthTransport(
            baseUrlProvider = baseUrlProvider,
            warningLogger = { message ->
                raftLogWarn(SHARED_AUTH_LOG_TAG, "shared_auth_network $message")
            }
        )
    },
    diagnosticsLogger: (String) -> Unit = sharedInfoLogger(SHARED_AUTH_LOG_TAG, "shared_auth"),
    criticalDiagnosticsLogger: (String) -> Unit =
        sharedInfoLogger(
            SHARED_AUTH_LOG_TAG,
            "shared_auth",
            RaftPlatformLogHint.AuthCritical
        )
): SharedAuthDataSource =
    SharedAuthDataSource(
        transport = transportFactory { apiEnvironmentStore.current().apiBaseUrl },
        sessionStore = authStore,
        diagnosticsLogger = diagnosticsLogger,
        criticalDiagnosticsLogger = criticalDiagnosticsLogger
    ).withAvatarCacheRuntime(
        runtime = avatarCacheRuntime,
        nativeCache = avatarCacheNativeCache,
        environmentId = { apiEnvironmentStore.current().apiBaseUrl }
    )

private fun ReadReceiptOwner.toProcessActivityUnreadOwner(): ProcessActivityUnreadOwner =
    ProcessActivityUnreadOwner(
        environmentId = environmentId,
        accountId = accountId,
        serverId = serverId
    )

internal class AppShellActivePagerOwner {
    private var activePagerId: String? = null

    fun markActive(pagerId: String) {
        activePagerId = pagerId.takeIf { it.isNotBlank() }
    }

    fun isActive(pagerId: String): Boolean =
        activePagerId == null || activePagerId == pagerId
}

/** [RaftServerThemeFollower] key of the process-level look (no shell selection). */
private object ProcessServerThemeFollowKey

internal object RaftSharedStoreRegistry {
    init {
        RaftDataStoreRegistry.bindCurrentProvider(::currentOrNull)
        RaftImageCacheHooks.clearImageCaches = ::raftClearImageCaches
        RaftWebViewAuthStorageHooks.clearAuthStorage = { build.raft.app.raftClearWebViewAuthStorage() }
    }

    private val productionFactory =
        RaftSharedStoreProviderFactory { request ->
            RaftSharedStoreProvider.create(
                eventBus = request.eventBus,
                source = request.source,
                host = request.host,
            )
        }
    private val access =
        RaftSharedStoreRegistryAccess<RaftSharedStoreProvider, RaftSharedStoreProviderRequest>(
            defaultFactory = productionFactory::create,
            updateExisting = { provider, request ->
                provider.updateEventBus(request.eventBus, request.source)
            },
        )

    fun currentOrNull(): RaftSharedStoreProvider? = access.currentOrNull()

    /** See [RaftSharedStoreRegistryState.clearForTests]: unit tests only. */
    internal fun clearForTests() = access.clearForTests()

    fun bindFactoryBeforeFirstGet(
        factory: RaftSharedStoreProviderFactory,
    ): RaftSharedStoreFactoryBindingHandle =
        access.bindFactoryBeforeFirstGet(factory::create)

    fun unbindFactoryBeforeFirstGet(handle: RaftSharedStoreFactoryBindingHandle): Boolean =
        access.unbindFactoryBeforeFirstGet(handle)

    /**
     * The process-wide store provider for any host. The first caller's [host] backs the provider's
     * native calls; Kuikly pages pass their bridge through `get(bridge: BridgeModule)`.
     */
    fun get(host: HostCapabilities): RaftSharedStoreProvider = access.get(processRequest(host))

    fun get(eventBus: RaftEventBus, source: String, host: HostCapabilities): RaftSharedStoreProvider =
        access.get(
            RaftSharedStoreProviderRequest(
                eventBus = eventBus,
                source = source,
                host = host,
            ),
        )

    /**
     * [get]'s first creation for [host], for the database launch gate's off-main build (#113): the
     * same request, but an instance another caller already created is returned without
     * `updateEventBus`, which stays on the UI thread (the gate's [get] once the build is back).
     */
    fun getOrCreateWithoutUpdate(host: HostCapabilities): RaftSharedStoreProvider =
        access.getOrCreateWithoutUpdate(processRequest(host))

    // Also reached from the launch gate's off-main build (getOrCreateWithoutUpdate on
    // Dispatchers.Default): RaftEventBuses.appScope() is a default (SYNCHRONIZED) `by lazy`, so a
    // concurrent first call from the UI thread still yields one bus (#4485 review, #proj-mobile:#113).
    private fun processRequest(host: HostCapabilities): RaftSharedStoreProviderRequest =
        RaftSharedStoreProviderRequest(
            eventBus = RaftEventBuses.appScope(),
            source = "shared-store-provider",
            host = host,
        )
}

/** Data-layer plan step 4: apply Home rows staged before [context] was bound, under it. */
private fun SharedConversationIdentityHub.applyPendingCandidatesFor(context: ProcessPresenceAuthContext): Int =
    applyPendingCandidates(
        environmentId = context.environmentId,
        accountId = context.accountId,
        authGeneration = context.authorityGeneration,
    )

/** Joint connections cache caps (data-layer plan step 5, Tenny rule 3). */
private const val JointConnectionsMaxPerServer = 32
private const val JointConnectionsMaxPerAccount = 64
private const val JointConnectionsMaxRows = 128

/**
 * Joint-channel sender identities fill avatars only: never user-blocking, so they wait behind
 * Foreground / Visible work.
 */
private fun jointActorResolve(
    networkClient: RaftNetworkClient
): suspend (ServerId, List<ActorResolveRef>) -> ActorResolveOutcome = { serverId, refs ->
    networkClient.resolveActors(DataRequestPriority.Background, serverId, refs)
}

/** The composer-draft owner of this auth binding (environment + account); null when unbound. */
private fun ProcessPresenceAuthContext.toComposerDraftAccount(): SharedThreadComposerDraftAccount? =
    SharedThreadComposerDraftAccount.of(environmentId = environmentId, accountId = accountId)

/**
 * `inbox_row_position_v1`: an Activity list owns [owner]'s badge only in a window that gets the live
 * events ([liveScopes]: the server is selected there, not disposed, and its realtime sinks are
 * registered). A list in a closed window, or one whose shell has no sinks, gets no +1s, so it must
 * not suppress the summary read.
 */
internal fun activityListOwnsBadge(
    owner: ProcessActivityUnreadOwner,
    stores: List<Pair<RaftShellStoreScope, SharedActivityStore>>,
    liveScopes: Set<RaftShellStoreScope>
): Boolean = stores.any { (scope, store) -> scope in liveScopes && store.ownsBadgeFor(owner) }
