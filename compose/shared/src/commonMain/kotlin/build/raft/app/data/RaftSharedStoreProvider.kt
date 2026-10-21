package build.raft.app.data

import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
import build.raft.app.host.HostCapabilities
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
import build.raft.app.CoroutineThreadComposerDraftPersistenceScheduler
import build.raft.app.ChannelSettingsSnapshotCache
import build.raft.app.KeyValuePixelAvatarStore
import build.raft.app.ProcessAttachmentAuthScopes
import build.raft.app.ReplyCacheProvenance
import build.raft.app.SharedThreadMessageStore
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.RaftPixelAvatarRuntime
import build.raft.app.RaftPlatformLogHint
import build.raft.app.SharedForegroundBannerStore
import build.raft.app.data.realtime.MessageSeqEnvelope
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.MessageSeqRecoveryPolicy
import build.raft.app.data.realtime.MessageSeqRecoveryState
import build.raft.app.data.realtime.MessageSeqRepairBatch
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.refreshPixelAvatarsFromWeb
import build.raft.app.raftLogError
import build.raft.app.raftLogInfo
import build.raft.app.raftLogWarn
import build.raft.app.model.AgentCreated
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.network.createPlatformRaftHttpExecutor
import build.raft.app.network.RaftCurlSocketIoPolicy
import build.raft.app.network.RaftHttp3Policy
import build.raft.app.network.RaftDohFallbackPolicy
import build.raft.app.network.RaftNetworkTransportController
import build.raft.app.network.installRaftNetworkTransportController
import build.raft.app.network.RaftNetworkKmmRecoveryRemoteFlagBridge
import build.raft.app.network.raftNetworkKmmRecoveryEnvironmentId
import build.raft.app.network.sync.AttachmentCommentsReadCacheV2
import build.raft.app.network.sync.RaftThreadReplyWindowCache
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CurrentGrantMessageShadowCommitLane
import build.raft.app.network.sync.DefaultFoldPersistenceAdapter
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.PreparedCurrentGrantMessageCommitDisposition
import build.raft.app.network.sync.SqlDelightFoldPersistStore
import build.raft.app.network.sync.SqlDelightFoldPersistTransactionRunner
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.SqlDelightStrictMessageScopeSnapshotReader
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1DestinationPublication
import build.raft.app.network.sync.ProcessReadStateV1DiagnosticOverride
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.network.sync.SqlDelightReadStateV1Store
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreMessageShadowObserver
import build.raft.app.network.sync.SyncCoreReactionShadowLane
import build.raft.app.network.sync.PartitionDenialReason
import com.tencent.kmm.network.export.VBTransportCurl
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

private fun logNetworkKmmRemoteConfig(message: String) {
    if (message.contains("persisted=false")) {
        sharedErrorLogger("SlockNetworkKmm", "networkkmm_remote_config")(message)
    } else {
        sharedInfoLogger("SlockNetworkKmm", "networkkmm_remote_config")(message)
    }
}

internal const val ServerFeatureFlagRefreshIntervalMillis = 60_000L

internal suspend fun refreshSelectedServerFeatureFlags(
    intervalMillis: Long = ServerFeatureFlagRefreshIntervalMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
    refresh: suspend () -> Unit
) {
    require(intervalMillis > 0L) { "feature flag refresh interval must be positive" }
    while (true) {
        refresh()
        wait(intervalMillis)
    }
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
): RealtimeAuthorityProvider =
    RealtimeAuthorityProvider(
        registry = registry,
        authStore = authStore,
        authEvents = authEvents,
        currentEnvironmentId = currentEnvironmentId,
        diagnosticsLogger = diagnosticsLogger,
        canonicalFinalIntegration = canonicalFinalIntegration,
        onAuthRevisionAdvanced = onAuthRevisionAdvanced,
    )

internal fun createSharedRealtimeDomainEventOwner(
    tasksDataStore: SharedTasksDataStore?,
    cursorStore: RealtimeAppliedSeqCursorStore?,
    authorityProvider: RealtimeAuthorityProvider,
    currentGrantMessageShadowCommitLane: CurrentGrantMessageShadowCommitLane,
): SharedRealtimeDomainEventOwner =
    SharedRealtimeDomainEventOwner(
        tasksDataStore = tasksDataStore,
        cursorStore = cursorStore,
        runIfCurrentGrant = authorityProvider::runIfCurrentGrant,
        runWithCurrentMessageGrant = authorityProvider::runWithCurrentMessageGrant,
        canonicalFinalOwnerGateway = authorityProvider.canonicalFinalOwnerGatewayOrNull(),
    ).also { owner ->
        owner.installCurrentGrantMessageShadowCommitLane(currentGrantMessageShadowCommitLane)
    }

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
    private var instance: T? = null

    fun currentOrNull(): T? = synchronized(lock) { instance }

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
            val created = (boundFactory?.factory ?: defaultFactory)(arguments)
            instance = created
            created
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
    private val apiEnvironmentKeyValueStore: RaftKeyValueStore,
    val authStore: KeyValueAuthSessionStore,
    val authSessionEvents: RaftAuthSessionEvents,
    val authDataSource: SharedAuthDataSource,
    val authLossFunnel: SharedAuthLossFunnel,
    val authRuntimeCapabilityStore: SharedAuthRuntimeCapabilityStore,
    val networkClient: RaftNetworkClient,
    val networkTransportController: RaftNetworkTransportController,
    private val networkReachabilityGates: RaftHostReachabilityGates,
    private val dohFallbackPolicy: RaftDohFallbackPolicy,
    private val scopedNetworkClientFactory: (RaftAuthSessionStore) -> RaftNetworkClient,
    private val ownerScopedNetworkClientFactory: (RaftAuthSessionStore, String) -> RaftNetworkClient,
    val serverFeatureFlags: SharedServerFeatureFlags,
    private val currentGrantMessageShadowCommitLane: CurrentGrantMessageShadowCommitLane,
    private val canonicalMessageV2Canary: CanonicalMessageV2ProductionCanary,
    val localDataStore: ComposeLocalDataStore,
    internal val avatarCacheMetadataStore: RaftAvatarCacheMetadataStore,
    internal val avatarCacheCoordinator: RaftAvatarCacheCoordinator,
    internal val avatarCacheRuntime: RaftAvatarCacheRuntime,
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
    private val memberPresenceRealtimeSink: (RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    private val activityPersistSlice: MviPersistSlice<SharedActivityState, SharedActivityIntent>,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    private val homeIdentityPersistSlice: MviPersistSlice<SharedHomeState, SharedHomeIntent>,
    val apiEnvironmentStore: SharedApiEnvironmentStore,
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
    internal val threadPendingSendJournal: ThreadPendingSendJournal,
    val channelSettingsSnapshotCache: ChannelSettingsSnapshotCache,
    val foregroundReconciler: SharedForegroundReconciler,
    private val homeStoreScope: CoroutineScope,
    private val homeActivityStoreScope: CoroutineScope,
    val threadSendScope: CoroutineScope
) {
    // task #166: display-only last-known unread counts and previews for the cached cold-start
    // Home. Declared first so every later initializer that builds the Home store sees it.
    private val homeRowHintPersistStorage = HomeRowHintPersistStorage(sharedPreferenceStore)
    private val homeRowHintPersistSlice =
        createSharedHomeRowHintPersistSlice(
            storage = homeRowHintPersistStorage,
            diagnosticsLogger = sharedInfoLogger("SlockHomePersist", "shared_home_row_hints"),
            failureLogger = sharedErrorLogger("SlockHomePersist", "shared_home_row_hints")
        )
    private val selectedServerStores = RaftShellScopedStoreCache<SelectedServerStore>()
    private val selectedServerSelectionEpochStore =
        KeyValueSelectedServerSelectionEpochStore(sharedPreferenceStore)
    private val shellAuthStores = RaftShellScopedStoreCache<RaftShellAuthSessionStore>()
    private val shellNetworkClients = RaftShellScopedStoreCache<RaftNetworkClient>()
    private val shellServerMembersStores = RaftShellScopedStoreCache<SharedServerMembersStore>()
    private val shellRealtimeDomainEventOwners = RaftShellScopedStoreCache<SharedRealtimeDomainEventOwner>()
    private val realtimeAuthorityRegistrations = RaftShellScopedStoreCache<String>()
    private val shellAgentDetailStores = RaftShellScopedStoreCache<SharedAgentDetailStore>()
    private val shellComputersStores = RaftShellScopedStoreCache<SharedComputersStore>()
    private val shellCreateAgentFormStores = RaftShellScopedStoreCache<SharedCreateAgentFormStore>()
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

        // A force caller otherwise joins any already-active pre-connect
        // roster flight. Retire that flight/cache once, before every shell
        // joins the new current-transport request.
        processServerMembersCore.invalidate(
            owner = owner,
            reason = "presence_transport_connected"
        )
        val activeScopes = scopesForServer(grant.serverId).toSet()
        val stores =
            buildList {
                add(serverMembersStore)
                shellServerMembersStores.entries().forEach { (scope, store) ->
                    if (scope in activeScopes) add(store)
                }
            }.distinct()
        sharedInfoLogger("SlockPresence", "presence_transport_rebaseline")(
            "serverId=${grant.serverId} epoch=$transportEpoch result=${result.name} stores=${stores.size}"
        )
        coroutineScope {
            stores.forEach { store ->
                launch {
                    try {
                        store.refreshMembers(
                            serverId = ServerId(grant.serverId),
                            priority = DataRequestPriority.Background,
                            force = true,
                            reason = "presence_transport_connected"
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        sharedWarnLogger("SlockPresence", "presence_transport_rebaseline")(
                            "serverId=${grant.serverId} refresh_failed=${error::class.simpleName ?: "unknown"}"
                        )
                    }
                }
            }
        }
    }
    private var authSessionClearedSubscription: RealtimeSubscription? = null
    private var authSessionClearLogger: ((String) -> Unit)? = null
    private val activeAppShellPagerOwner = AppShellActivePagerOwner()
    private val processRealtimeAuthorityFlow =
        realtimeAuthorityProvider.authorityFlow(ProcessRealtimeAuthorityScopeKey)

    private fun updateNetworkKmmRecoveryEnvironment(): String {
        val environment = apiEnvironmentStore.current()
        val environmentId =
            raftNetworkKmmRecoveryEnvironmentId(
                environmentKey = environment.key,
                apiBaseUrl = environment.apiBaseUrl
            )
        runCatching {
            RaftNetworkKmmRecoveryRemoteFlagBridge.updateCurrentEnvironment(environmentId)
        }.onSuccess { diagnostics ->
            diagnostics?.let(::logNetworkKmmRemoteConfig)
        }.onFailure { error ->
            sharedWarnLogger("SlockNetworkKmm", "networkkmm_remote_config")(
                "environment_update_failed reason=${error::class.simpleName ?: "unknown"}"
            )
        }
        networkTransportController.updateRemoteEnvironment(environmentId)
            ?.let { sharedInfoLogger("SlockNetworkTransport", "remote_config")(it) }
        return environmentId
    }

    /**
     * Server feature-flag evaluation (sync-core rollout gate). Prefetched per shell when its selected
     * server changes (see [selectedServerStore]) — this is what makes `sync_core_messages_v1` traffic
     * appear at `feature-flags/evaluate`. Reads default OFF until resolved, so gated features stay dark.
     */
    /**
     * Flag-gated canonical projection gateway injected into [RealtimeEventMapper]. Off by default
     * (`sync_core_messages_v1`), so it is a single flag lookup on the hot path until the rollout enables it;
     * when on, it decodes each message frame ONCE, runs the shadow-compare over that decode and emits
     * divergence diagnostics without touching the store, then returns the decoded carry for the shadow
     * fold+persist lane. A provider-level singleton so all mapper instances share one gate.
     */
    val messageShadowObserver: MessageShadowObserver =
        SyncCoreMessageShadowObserver(
            featureFlags = serverFeatureFlags,
            diagnosticsLogger = sharedInfoLogger("SyncCoreShadow", "sync_core_shadow"),
        )

    /**
     * One provider-central current-grant shadow lane shared by every realtime owner backed by this database.
     * The server flag defaults OFF; when enabled, guarded canonical frames synchronously fold, commit, strict
     * read back, and atomically publish before the unchanged legacy continuation runs.
     */
    init {
        val processAuthTerminalGate = AuthSessionTerminalRouteGate()
        val processAuthTerminalSource =
            requireNotNull(authStore.authSessionTerminalSourceOrNull()) {
                "Production auth store must expose terminal revision authority"
            }
        suspend fun commitProcessAuthTerminalCleanup(expectedGeneration: Long) {
            val selectionReceipts = selectedServerStores.captureActiveServerSelectionReceipts()
            val committed =
                processAuthTerminalSource.runIfAuthSessionTerminalRevision(expectedGeneration) {
                    ProcessAttachmentAuthScopes.retireAccount()
                    homeSavedStateCache.clear()
                    // Cached previews are message content: they leave with the session. Losing a
                    // successor's hints to a late terminal event only costs one cold-start hint.
                    homeRowHintPersistStorage.clearAll()
                    // Avatar references are account-visible directory data: same lifetime.
                    memberAvatarRefStore.clearAll()
                    // Reply previews are message content: same lifetime (page keys are also
                    // principal-scoped, so a successor never reads them in the meantime).
                    threadDurableLastKnownReader.replyWindowCache?.clearAll()
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
                clearActiveServerSelectionsIfUnchanged(selectionReceipts)
            }
        }
        updateNetworkKmmRecoveryEnvironment()
        // Attach canonical/reaction shadow mutation to the dedicated process
        // owner. Shell owners are projection-only and never repeat this work.
        realtimeDomainEventOwner.installMessageShadowFoldLane(buildMessageShadowFoldLane())
        realtimeDomainEventOwner.installReactionShadowLane(buildReactionShadowLane())
        realtimeDomainEventOwner.installCanonicalMessageV2OwnerGateway(
            buildCanonicalMessageV2OwnerGateway(),
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
                }
            },
        ).start()
        threadSendScope.launch {
            // Task #153: a Local Feature Overrides change re-reads the DoH flag at once.
            ProcessDiagnosticFeatureFlagOverrides.registry
                .revisionFor(MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY)
                .collect {
                    dohFallbackPolicy.reevaluate("override")
                        ?.let { sharedInfoLogger("SlockNetworkTransport", "doh_fallback")(it) }
                }
        }
        RaftHostNetworkEvents(eventBus).addAuthoritativeOnlineListener {
            // Task #150: a new default network (e.g. a VPN switched on/off) may resolve the host
            // again; stop short-circuiting requests at once instead of waiting for the next probe.
            networkReachabilityGates.onNetworkAvailable()
            // Task #153: a new network resolves through the system first again, not via the DoH window.
            runCatching { VBTransportCurl.onNetworkChanged() }
            threadSendScope.launch {
                avatarCacheRuntime.recoverCurrentOwnerAfterNetworkAvailable()
            }
        }
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
        SyncCoreMessageShadowFoldLane.create(
            featureFlags = serverFeatureFlags,
            sqlDatabase = sqlDatabase,
            now = ::defaultNowMillis,
            scope = CoroutineScope(SupervisorJob()),
            logger = sharedInfoLogger("SyncCoreT0Shadow", "sync_core_t0_shadow"),
        )

    private fun buildReactionShadowLane(): SyncCoreReactionShadowLane =
        SyncCoreReactionShadowLane(
            featureFlags = serverFeatureFlags,
            logger = sharedInfoLogger("SyncCoreReactionShadow", "sync_core_reaction_shadow"),
        )

    /** Task #168 step 3: the realtime path never requests the network; one gateway shape for every owner. */
    private fun buildCanonicalMessageV2OwnerGateway(): CanonicalMessageV2OwnerGateway =
        ProviderBackedCanonicalMessageV2OwnerGateway(canary = canonicalMessageV2Canary)

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
        val projection = detail.toCanonicalConversationDirectoryProjection(serverId) ?: return false
        return conversationIdentityHub(scope).publishCanonical(
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
    }

    fun sharedHomeStore(scope: RaftShellStoreScope): SharedHomeStore? =
        homeStores.get(scope)

    /**
     * Returns the live shell owner scope for child navigation. A route's
     * initial server is a bootstrap hint and may be stale after an in-process
     * server switch; an existing selected-server store is authoritative.
     */
    internal fun currentNavigationStoreScope(scope: RaftShellStoreScope): RaftShellStoreScope =
        childNavigationStoreScope(scope, selectedServerStores.get(scope))

    fun selectedServerStore(scope: RaftShellStoreScope): SelectedServerStore =
        selectedServerStores.getOrPut(scope) {
            SelectedServerStore(
                scope = scope,
                selectionEpochStore = selectedServerSelectionEpochStore,
                environmentIdProvider = {
                    apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
                },
                onSelectionChanged = { serverId ->
                    runCatching {
                        RaftNetworkKmmRecoveryRemoteFlagBridge.updateActiveSelection(
                            scopeId = scope.shellInstanceId,
                            serverId = serverId
                        )
                    }.onSuccess { diagnostics ->
                        diagnostics?.let(::logNetworkKmmRemoteConfig)
                    }.onFailure { error ->
                        sharedWarnLogger("SlockNetworkKmm", "networkkmm_remote_config")(
                            "active_selection_failed reason=${error::class.simpleName ?: "unknown"}"
                        )
                    }
                    networkTransportController.updateRemoteActiveSelection(
                        scopeId = scope.shellInstanceId,
                        serverId = serverId
                    )?.let { sharedInfoLogger("SlockNetworkTransport", "remote_config")(it) }
                    dohFallbackPolicy.updateActiveSelection(
                        scopeId = scope.shellInstanceId,
                        serverId = serverId
                    )?.let { sharedInfoLogger("SlockNetworkTransport", "doh_fallback")(it) }
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
                                }
                            }
                        }
                    }
                    selectionStore.disposed.first { it }
                    prefetching.cancel()
                }
            }
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
                onProcessSessionCleared = ::clearActiveShellServerSelections
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
                    avatarRefStore = memberAvatarRefStore
                )
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
                currentGrantMessageShadowCommitLane = currentGrantMessageShadowCommitLane,
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

    /** The computers cache owner for this shell's current session. */
    internal suspend fun computersOwner(scope: RaftShellStoreScope): SharedComputersOwner? =
        authStore(scope).readSession()?.let { session ->
            sharedComputersOwner(session, apiEnvironmentStore.current().apiBaseUrl, authLoginGeneration(scope).current())
        }

    fun computersStore(scope: RaftShellStoreScope): SharedComputersStore =
        shellComputersStores.getOrPut(scope) {
            SharedComputersStore(
                computersApiClient = RaftComputersApiClient(RaftComputersNetworkApi(networkClient(scope))),
                scope = threadSendScope,
                logger = sharedInfoLogger("SlockComputers", "shell_computers"),
                ownerProvider = { computersOwner(scope) }
            )
        }

    fun createAgentFormStore(scope: RaftShellStoreScope): SharedCreateAgentFormStore =
        shellCreateAgentFormStores.getOrPut(scope) {
            val shellAuthStore = authStore(scope)
            val loginGeneration = authLoginGeneration(scope)
            val formLogger = sharedInfoLogger("SlockCreateAgentForm", "shared_create_agent_form_store")
            SharedCreateAgentFormStore(
                computersApiClient = RaftComputersApiClient(RaftComputersNetworkApi(networkClient(scope))),
                scope = threadSendScope,
                ownerProvider = {
                    shellAuthStore.readSession()?.let { session ->
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
                    logger = formLogger
                ),
                runtimeFormV2Enabled = { serverId ->
                    serverFeatureFlags.isEnabled(serverId, RUNTIME_FORM_V2_FEATURE_FLAG_KEY)
                }
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

    fun adaptiveWorkspaceStore(scope: RaftShellStoreScope): SharedAdaptiveWorkspaceStore =
        shellAdaptiveWorkspaceStores.getOrPut(scope) { SharedAdaptiveWorkspaceStore() }

    fun adaptiveScrollAnchorStore(scope: RaftShellStoreScope): SharedAdaptiveScrollAnchorStore =
        shellAdaptiveScrollAnchorStores.getOrPut(scope) { SharedAdaptiveScrollAnchorStore() }

    fun createAuthDataSourceForCurrentEnvironment(
        avatarCacheNativeCache: RaftAvatarNativeCache = UnsupportedRaftAvatarNativeCache
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
        archiveChannel: suspend (ChannelId) -> DataResult<ChannelArchiveResult> = {
            DataResult.Empty(EmptyReason.NoChannels)
        },
        setChannelActivityMuted: suspend (ChannelId, Boolean) -> DataResult<ChannelActivityMuteSettings> = { _, _ ->
            DataResult.Empty(EmptyReason.NoChannels)
        },
        pinUpdateFailureCallback: (String) -> Unit = {},
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
                archiveChannel = archiveChannel,
                setChannelActivityMuted = setChannelActivityMuted,
                readReceiptCoordinator = processReadReceiptCoordinator(),
                readReceiptOwnerFactory = ::readReceiptOwner,
                readStateV1Canary = processReadStateV1Canary,
                pinUpdateFailureCallback = pinUpdateFailureCallback
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
                loadSidebarOrder = loadSidebarOrder,
                updateSidebarOrder = updateSidebarOrder,
                loadServerOrder = loadServerOrder,
                updateServerOrder = updateServerOrder,
                markChannelReadAll = markChannelReadAll,
                markChannelUnread = markChannelUnread,
                archiveChannel = archiveChannel,
                setChannelActivityMuted = setChannelActivityMuted,
                perReceiverStateVersionStore = PerReceiverStateVersionStore(sharedPreferenceStore),
                readReceiptCoordinator = processReadReceiptCoordinator(),
                readReceiptOwnerFactory = ::readReceiptOwner,
                readStateV1Canary = processReadStateV1Canary,
                pinUpdateFailureCallback = pinUpdateFailureCallback,
                // Identity first: the hint slice attaches to the durable content it restores.
                persistSlices = listOf(homeIdentityPersistSlice, homeRowHintPersistSlice),
                conversationIdentityPublicationSink = publication@{ publication ->
                    // Capture the authority in the same synchronous turn as the Home state that
                    // produced this candidate. Looking it up later in a launched coroutine could
                    // re-endorse an old same-account row with a successor auth revision.
                    val context = replyCacheAuthContext.value ?: return@publication
                    val environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
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

    fun getOrCreateHomeActivityStore(
        scope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
        inboxRepository: InboxRepository,
        readReceiptRepository: ReadReceiptRepository,
        conversationRepository: ConversationRepository,
        memberRepository: MemberRepository? = null,
        channelRepository: ChannelRepository? = null,
        prewarmReplyThread: (suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit>)? = null,
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
                    serverMembersStore = serverMembersStore,
                    tasksDataStore = tasksDataStore,
                    channelRepository = channelRepository,
                    persistSlices = listOf(activityPersistSlice),
                    readReceiptCoordinator = processReadReceiptCoordinator(),
                    readReceiptOwnerFactory = ::readReceiptOwner,
                    readStateV1Canary = processReadStateV1Canary,
                    prewarmReplyThread = prewarmReplyThread,
                    replyThreadFreshnessLedger = threadPayloadStore.freshnessLedger,
                    onUnreadCountsChanged = onUnreadCountsChanged,
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
                prewarmReplyThread = prewarmReplyThread ?: { DataResult.Empty(EmptyReason.NoMessages) },
                replyThreadFreshnessLedger = threadPayloadStore.freshnessLedger,
                onUnreadCountsChanged = onUnreadCountsChanged ?: {},
                onSummaryFact = activityUnreadSummaryStore::submitFact,
                onSummaryInvalidated = activityUnreadSummaryStore::submitInvalidation,
                scope =
                    homeActivityStoreScopes.getOrPut(scope) {
                        if (scope == RaftShellStoreScope.Legacy) homeActivityStoreScope else RaftStoreThreading.newScope()
                    },
                diagnosticsLogger = diagnosticsLogger ?: {}
            )
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
            onPrediction = { prediction ->
                activityUnreadSummaryStore.invalidate(
                    prediction.owner.toProcessActivityUnreadOwner(),
                    reason = "read_prediction:${prediction.revision}"
                )
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
                            SharedActivityIntent.ApplyReadReceiptResult(request, result.isReadReceiptSuccess())
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
        shellServerMembersStores.remove(scope)?.clearScopedReadCaches()
        shellRealtimeDomainEventOwners.remove(scope)
        shellAgentDetailStores.remove(scope)
        shellComputersStores.remove(scope)?.dispatch(SharedComputersIntent.Clear)
        shellCreateAgentFormOwnerJobs.remove(scope)?.cancel()
        shellAuthLoginGenerationJobs.remove(scope)?.cancel()
        shellAuthLoginGenerations.remove(scope)
        shellCreateAgentFormStores.remove(scope)
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
            // Task #154: the Settings switch's explicit choice (absent = follow the server flag). Read live at
            // each connect/reconnect, so a toggle applies from the next connection.
            val realtimeTransportPreferenceStore = KeyValueRealtimeTransportPreferenceStore(sharedPreferenceStore)
            RaftCurlSocketIoPolicy.bindUserChoice {
                realtimeTransportPreferenceStore.readCurlSocketIoUserChoiceSync()
            }
            // Task #3: the HTTP/3 switch's explicit choice (absent = follow the server-sent transport
            // variant). Bound before the transport controller exists so its first snapshot honours it.
            val http3PreferenceStore = KeyValueHttp3PreferenceStore(sharedPreferenceStore)
            RaftHttp3Policy.bindUserChoice { http3PreferenceStore.readHttp3UserChoiceSync() }
            logSharedAuthDiagnostic(
                "shared_key_value",
                "storage_scope name=sharedPreference " +
                    "namespace=${RaftLocalPreferenceStore.KuiklyNamespace} " +
                    "scope=${sharedPreferenceStoreBinding.scope}"
            )
            val networkTransportController =
                RaftNetworkTransportController()
            installRaftNetworkTransportController(networkTransportController)
            networkTransportController.getPreferenceSync().let { snapshot ->
                logSharedAuthDiagnostic(
                    "shared_network_transport",
                    "requestedHttp3=${snapshot.requestedHttp3Enabled} " +
                        "effectiveHttp3=${snapshot.effectiveHttp3Enabled} " +
                        "curlRequested=${snapshot.curlRequestedForNewCalls} " +
                        "availability=${snapshot.availability} generation=${snapshot.generation}"
                )
            }
            val apiEnvironmentKeyValueStoreBinding =
                RaftSharedKeyValueStoreProvider.create(
                    namespace = SharedApiEnvironmentStore.Namespace,
                    storageName = "API environment"
                )
            val apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStoreBinding.store
            logSharedAuthDiagnostic(
                "shared_key_value",
                "storage_scope name=apiEnvironment " +
                    "namespace=${SharedApiEnvironmentStore.Namespace} " +
                    "scope=${apiEnvironmentKeyValueStoreBinding.scope}"
            )
            val apiEnvironmentStore =
                SharedApiEnvironmentStore(
                    apiEnvironmentKeyValueStore
                )
            // Content-addressed avatar keys are host-free only for the active environment's hosts.
            apiEnvironmentStore.current().let { RaftAvatarTrustedHosts.configure(it.apiBaseUrl, it.webUrl) }
            CoroutineScope(SupervisorJob()).launch {
                apiEnvironmentStore.state.collect { RaftAvatarTrustedHosts.configure(it.apiBaseUrl, it.webUrl) }
            }
            val tasksDataStore =
                SharedTasksDataStore(
                    ownerIdentityProvider = {
                        authStore.readSession()?.let { session ->
                            "${apiEnvironmentStore.current().apiBaseUrl.trimEnd('/')}|${session.user.id}"
                        }
                    },
                    currentServerIdProvider = {
                        authStore.readSession()?.currentServerId
                    }
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
            // task #323: keep avatar metadata SQLite I/O off the Kuikly frame thread.
            val avatarCacheMetadataStore =
                DispatchingRaftAvatarCacheMetadataStore(
                    delegate = SqlDelightRaftAvatarCacheMetadataStore(database),
                    dispatcher = RaftAvatarDbDispatcher
                )
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
            // task #343: seed the mounted session from durable rows once the owner resolves, so a
            // cold start paints cached avatars on the first frame instead of waiting per-row mounts.
            // Re-attempted on auth changes (login, server/binding switch); one run per owner.
            CoroutineScope(SupervisorJob()).launch {
                runCatchingPreservingCancellation {
                    avatarMountedBinding.prewarmOwner(authStore.readSession()?.currentServerId)
                }
                authStore.authSessionEvents.collect { event ->
                    when (event) {
                        is AuthSessionEvent.Authenticated,
                        is AuthSessionEvent.BindingMetadataChanged ->
                            runCatchingPreservingCancellation {
                                avatarMountedBinding.prewarmOwner(authStore.readSession()?.currentServerId)
                            }
                        is AuthSessionEvent.Cleared,
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
            val networkWarningLogger = sharedWarnLogger("SlockSharedNetwork", "shared_network")
            val networkRequestScheduler = RaftRequestScheduler()
            val networkExecutor = createPlatformRaftHttpExecutor(networkWarningLogger)
            val networkReachabilityGates = RaftHostReachabilityGates()
            val dohFallbackPolicy = RaftDohFallbackPolicy()
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
                    reachabilityGates = networkReachabilityGates
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
                        reachabilityGates = networkReachabilityGates
                    )
                }
            val networkClient = scopedNetworkClientFactory(authStore)
            // Product analytics (RFC-067) sends through the shared client at Background priority.
            RaftProductEvents.bindTransport(networkClient)
            val messageBodyFontSizeStore =
                SharedMessageBodyFontSizeStore(
                    scope = RaftStoreThreading.newScope(),
                    diagnosticsLogger = sharedInfoLogger("SlockMessageBodyFontSize", "store"),
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
            val processMemberPresenceRegistry = ProcessMemberPresenceRegistry()
            val presenceAuthContext = MutableStateFlow<ProcessPresenceAuthContext?>(null)
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
                    logger = sharedInfoLogger("SlockActivityUnread", "process_activity_unread")
                )
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
                                ProcessDiagnosticFeatureFlagOverrides.registry.clearObservations()
                                processServerMembersCore.invalidateAll(reason = "authenticated")
                                activityUnreadSummaryStore.invalidateAll(reason = "authenticated")
                            }
                        is AuthSessionEvent.Cleared ->
                            {
                                RaftProductEvents.setSignedOut()
                                serverFeatureFlagLastKnown.clearAll()
                                ProcessDiagnosticFeatureFlagOverrides.registry.clearObservations()
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
                    // Diagnostics "Local Feature Overrides" seam for `sync_core_messages_v1` ONLY: internal
                    // builds can ForceOn/ForceOff the whole offline-cache line on one binary. Not entitled
                    // (release, or the page never opened) => the registry returns the server value unchanged.
                    diagnosticOverride = { serverId, key, serverEffective ->
                        if (
                            key != SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY &&
                                key != MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY &&
                                key != MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY
                        ) {
                            serverEffective
                        } else {
                            val auth = presenceAuthContext.value
                            ProcessDiagnosticFeatureFlagOverrides.registry
                                .decide(
                                    key = key,
                                    serverEffective = serverEffective,
                                    scope =
                                        DiagnosticFeatureFlagScope(
                                            environmentId = auth?.environmentId.orEmpty(),
                                            accountId = auth?.accountId.orEmpty(),
                                            serverId = serverId?.value.orEmpty(),
                                        ),
                                )
                                .finalEffective
                        }
                    },
                    lastKnown = serverFeatureFlagLastKnown,
                    onEvaluation = { serverId, key, enabled, reason, variant, environmentId, evaluationGeneration ->
                        if (key == ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY && environmentId != null) {
                            RaftNetworkKmmRecoveryRemoteFlagBridge
                                .apply(
                                    environmentId = environmentId,
                                    evaluationGeneration = evaluationGeneration,
                                    serverId = serverId.value,
                                    enabled = enabled,
                                    reason = reason,
                                )
                                ?.let(::logNetworkKmmRemoteConfig)
                        }
                        if (key == MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY) {
                            dohFallbackPolicy.reevaluate("evaluation")
                                ?.let { sharedInfoLogger("SlockNetworkTransport", "doh_fallback")(it) }
                        }
                        if (key == MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY && environmentId != null) {
                            networkTransportController.applyRemoteEvaluation(
                                environmentId = environmentId,
                                evaluationGeneration = evaluationGeneration,
                                serverId = serverId.value,
                                enabled = enabled,
                                variant = variant
                            )?.let { sharedInfoLogger("SlockNetworkTransport", "remote_config")(it) }
                        }
                    },
                )
            // Task #153: the flag store's read covers online values, the last-known value (cold start
            // during a DNS outage) and internal-build Local Feature Overrides.
            dohFallbackPolicy.bind { serverId ->
                serverFeatureFlags.isEnabled(ServerId(serverId), MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY)
            }?.let { sharedInfoLogger("SlockNetworkTransport", "doh_fallback")(it) }
            // Task #154: the platform realtime transport reads this at each connect and reconnect.
            RaftCurlSocketIoPolicy.bindServerFlag { serverId ->
                serverFeatureFlags.isEnabled(ServerId(serverId), MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY)
            }
            val currentGrantMessageShadowCommitLane =
                CurrentGrantMessageShadowCommitLane(
                    strictReader = SqlDelightStrictMessageScopeSnapshotReader(database),
                    persistence =
                        DefaultFoldPersistenceAdapter(
                            runner = SqlDelightFoldPersistTransactionRunner(database),
                            store = SqlDelightFoldPersistStore(database, now = ::defaultNowMillis),
                        ),
                    isEnabledForServer = { serverId ->
                        serverFeatureFlags.isEnabled(
                            ServerId(serverId),
                            SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
                        )
                    },
                )
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
                    isEnabledForServer = { serverId ->
                        serverFeatureFlags.isEnabled(
                            ServerId(serverId),
                            SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
                        )
                    },
                    generationEngine = canonicalMessageV2GenerationEngine,
                    // Lines already start with `canonical_v2_canary` (task #168: no doubled prefix).
                    logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2Canary"),
                )
            // Reply-preview windows are viewer-scoped thread state, not V2 facts: they live beside
            // the V2 partition (page key = server + principal + parent scope) and are not gated by
            // the messages flag, so a network-only first frame can use them as well.
            val threadReplyWindowCache =
                RaftThreadReplyWindowCache(
                    storage = SqlDelightRaftThreadReplyWindowStorage(database, ::defaultNowMillis),
                    scope = CoroutineScope(SupervisorJob()),
                    diagnosticsLogger = sharedInfoLogger("ThreadReplyWindowCache"),
                )
            // Read side of the same partition the canary writes: same store instance, same per-server
            // flag, no baseline-coordinator side effects (task #1123).
            val threadDurableLastKnownReader =
                CanonicalMessageV2DurableLastKnownReader(
                    store = canonicalMessageV2Store,
                    isEnabledForServer = { serverId ->
                        serverFeatureFlags.isEnabled(
                            ServerId(serverId),
                            SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
                        )
                    },
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
                    onAuthRevisionAdvanced =
                        processPresenceBindingTransition(
                            presenceAuthContext = presenceAuthContext,
                            registry = processMemberPresenceRegistry,
                            currentEnvironmentId = {
                                apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
                            },
                            // Retire only the exact environment/principal/auth revision that
                            // previously owned sends.  A late terminal event can never erase a
                            // successor login's rows because its authGeneration is different.
                            onBindingRetired = { retired ->
                                val cleared = threadPendingSendJournal.clearForAuthRevision(
                                    environmentId = retired.environmentId,
                                    principalId = retired.accountId,
                                    authGeneration = retired.authorityGeneration,
                                )
                                if (!cleared) {
                                    realtimeAuthorityDiagnosticsLogger(
                                        "thread_pending_send_auth_revision_cleanup_failed " +
                                            "environmentId=${retired.environmentId} " +
                                            "principalId=${retired.accountId} " +
                                            "authGeneration=${retired.authorityGeneration}"
                                    )
                                }
                            },
                            // A cold process has no in-memory predecessor to retire.  Remove
                            // only rows from older generations and preserve the exact current
                            // generation, so a successor login cannot be erased by restoration.
                            onBindingBound = { bound ->
                                val cleared = threadPendingSendJournal.clearForPrincipalBeforeAuthRevision(
                                    environmentId = bound.environmentId,
                                    principalId = bound.accountId,
                                    authGeneration = bound.authorityGeneration,
                                )
                                if (!cleared) {
                                    realtimeAuthorityDiagnosticsLogger(
                                        "thread_pending_send_stale_generation_cleanup_failed " +
                                            "environmentId=${bound.environmentId} " +
                                            "principalId=${bound.accountId} " +
                                            "authGeneration=${bound.authorityGeneration}"
                                    )
                                }
                            },
                            // Same-binding token rotation (401 -> silent refresh): rehome the
                            // principal's journaled pending sends into the successor auth
                            // revision instead of retiring them, so an offline send admitted
                            // before the refresh stays reachable for drain/restore. Binding
                            // changes (logout/account switch) keep delete semantics via
                            // onBindingRetired above.
                            onBindingAuthRevisionRotated = { retired, bound ->
                                val rehomed = threadPendingSendJournal.rehomeAuthRevision(
                                    environmentId = retired.environmentId,
                                    principalId = retired.accountId,
                                    fromAuthGeneration = retired.authorityGeneration,
                                    toAuthGeneration = bound.authorityGeneration,
                                )
                                realtimeAuthorityDiagnosticsLogger(
                                    "thread_pending_send_auth_revision_rehomed " +
                                        "environmentId=${retired.environmentId} " +
                                        "principalId=${retired.accountId} " +
                                        "fromAuthGeneration=${retired.authorityGeneration} " +
                                        "toAuthGeneration=${bound.authorityGeneration} " +
                                        "rehomed=$rehomed"
                                )
                            },
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
                    avatarRefStore = memberAvatarRefStore
                )
            lateinit var provider: RaftSharedStoreProvider
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
                        targetStore.refreshMembers(
                            serverId = serverId,
                            priority = DataRequestPriority.Background,
                            force = true,
                            reason = "foreground_reconcile"
                        )
                    },
                    scope = CoroutineScope(SupervisorJob()),
                    diagnosticsLogger = sharedInfoLogger("SlockForegroundReconcile", "shared_foreground_reconcile")
                )
            // Write side of the same partition (task #1124): same canary (for "is this scope yours"),
            // same engine, same per-server flag, the process-level network client the canary's own
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
                    isEnabledForServer = { serverId ->
                        serverFeatureFlags.isEnabled(
                            ServerId(serverId),
                            SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
                        )
                    },
                    logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2EnterSnapshot"),
                    isKnownNonMember = { serverId, channelId -> provider.isKnownNonMemberConversation(serverId, channelId) },
                )
            // Paging write side (task #168): same canary, engine, flag and network client as the enter
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
                    isEnabledForServer = { serverId ->
                        serverFeatureFlags.isEnabled(
                            ServerId(serverId),
                            SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
                        )
                    },
                    // Lines already start with `canonical_v2_adjacent_page` / `canonical_v2_latest_page`: a prefix
                    // here doubled the latest-page line on device (task #168).
                    logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2AdjacentPage"),
                    isKnownNonMember = { serverId, channelId -> provider.isKnownNonMemberConversation(serverId, channelId) },
                )
            provider = RaftSharedStoreProvider(
                host = host,
                sqlDatabase = database,
                authKeyValueStore = authKeyValueStore,
                sharedPreferenceStore = sharedPreferenceStore,
                memberAvatarRefStore = memberAvatarRefStore,
                apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStore,
                authStore = authStore,
                authSessionEvents = authSessionEvents,
                authDataSource = authDataSource,
                authLossFunnel = authLossFunnel,
                authRuntimeCapabilityStore = SharedAuthRuntimeCapabilityStore(),
                networkClient = networkClient,
                networkTransportController = networkTransportController,
                networkReachabilityGates = networkReachabilityGates,
                dohFallbackPolicy = dohFallbackPolicy,
                scopedNetworkClientFactory = scopedNetworkClientFactory,
                ownerScopedNetworkClientFactory = ownerScopedNetworkClientFactory,
                serverFeatureFlags = serverFeatureFlags,
                currentGrantMessageShadowCommitLane = currentGrantMessageShadowCommitLane,
                canonicalMessageV2Canary = canonicalMessageV2Canary,
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
                memberPresenceRealtimeSink = memberPresenceRealtimeSink,
                composerDraftPersistSlice =
                    SharedThreadComposerDraftPersistSlice(
                        SharedThreadComposerDraftPersistence(
                            sharedPreferenceStore,
                            diagnosticsLogger = sharedInfoLogger("SlockComposerDraft")
                        ),
                        diagnosticsLogger = sharedInfoLogger("SlockComposerDraft"),
                        scheduler =
                            CoroutineThreadComposerDraftPersistenceScheduler(
                                CoroutineScope(SupervisorJob())
                            )
                    ),
                activityPersistSlice =
                    createSharedActivityPersistSlice(
                        storage = SharedPreferencePersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = sharedInfoLogger("SlockActivityPersist", "shared_activity_persist"),
                        failureLogger = sharedErrorLogger("SlockActivityPersist", "shared_activity_persist")
                    ),
                homeSavedStateCache = SharedHomeSavedStateCache(),
                homeIdentityPersistSlice =
                    createSharedHomeIdentityPersistSlice(
                        storage = SharedPreferencePersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = sharedInfoLogger("SlockHomePersist", "shared_home_persist"),
                        failureLogger = sharedErrorLogger("SlockHomePersist", "shared_home_persist")
                    ),
                apiEnvironmentStore = apiEnvironmentStore,
                eventBus = eventBusRelay,
                realtimeDomainEventOwner =
                    createSharedRealtimeDomainEventOwner(
                        tasksDataStore = tasksDataStore,
                        cursorStore = RealtimeAppliedSeqCursorStore(sharedPreferenceStore),
                        authorityProvider = realtimeAuthorityProvider,
                        currentGrantMessageShadowCommitLane = currentGrantMessageShadowCommitLane,
                    ).also { owner ->
                        // task #136: agent presence flows straight into the
                        // process registry's owner partition for the realtime
                        // owner's whole lifetime — never only while a page has
                        // sinks; stores read the registry, zero mirrors. The
                        // ONE production write edge, admission-frozen (see
                        // processMemberPresenceRealtimeSink).
                        owner.installMemberPresenceSink(memberPresenceRealtimeSink)
                        owner.installActivityUnreadSink(activityUnreadRealtimeSink)
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
                                .displayLanguageTag(currentSystemLanguageTag())
                        },
                        diagnosticsLogger = sharedInfoLogger("SlockMessageTranslation", "shared_message_translation")
                    ),
                messageBodyFontSizeStore = messageBodyFontSizeStore,
                threadMessageStore = SharedThreadMessageStore(),
                threadPayloadStore = SharedThreadPayloadStore(CoroutineScope(SupervisorJob())),
                threadDurableLastKnownReader = threadDurableLastKnownReader,
                threadEnteredScopeSnapshotLoader = threadEnteredScopeSnapshotLoader,
                threadDurablePageExtender = threadDurablePageExtender,
                threadPendingSendJournal = threadPendingSendJournal,
                channelSettingsSnapshotCache = ChannelSettingsSnapshotCache(),
                foregroundReconciler = foregroundReconciler,
                homeStoreScope = RaftStoreThreading.newScope(),
                homeActivityStoreScope = RaftStoreThreading.newScope(),
                threadSendScope = threadSendScope
            )
            return provider.also {
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

internal interface RealtimeDomainEventOwner {
    fun registerSinks(sinks: RealtimeDomainEventSinks)
    fun unregisterSinks(sinks: RealtimeDomainEventSinks)
    fun registerActiveThreadSink(sink: RealtimeActiveThreadSink)
    fun unregisterActiveThreadSink(sink: RealtimeActiveThreadSink)
    fun accept(serverId: String, event: RealtimeEvent, source: String = "unknown")
    fun acceptHomeUnreadProjection(serverId: String, event: RealtimeEvent, source: String = "unknown")
}

internal data class RealtimeDomainEventSinks(
    val applyHomeShell: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyHomeUnreadProjection: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyServerMembersRefresh: (String, RealtimeRefreshRequired) -> Unit = { _, _ -> },
    val applyAgentActivity: (MemberPresenceUpdated) -> Unit = {},
    val requestMemberDetailRefresh: () -> Unit = {},
    val joinChannel: (String) -> Unit = {},
    val emitRouteMarker: (RealtimeEvent) -> Unit = {},
    val log: (String) -> Unit = {}
)

internal data class RealtimeGuardedDomainEventSinks(
    val applyHomeShell: suspend (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyHomeUnreadProjection: suspend (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyServerMembersRefresh: suspend (String, RealtimeRefreshRequired) -> Unit = { _, _ -> },
    val applyAgentActivity: suspend (MemberPresenceUpdated) -> Unit = {},
    val requestMemberDetailRefresh: suspend () -> Unit = {},
    val joinChannel: suspend (String) -> Unit = {},
    val emitRouteMarker: suspend (RealtimeEvent) -> Unit = {},
    val log: suspend (String) -> Unit = {},
)

/**
 * Per-route sink for standalone Thread pagers. Registered while a Thread
 * route is mounted; the owner tees every accepted event (including
 * Heartbeat, which Thread needs for seq-gap recovery) into each registered
 * sink after dedup. Thread routes no longer decode raw realtime payloads
 * themselves, so the owner's dedup ledger is the single ingress decision
 * point for shell and thread consumers alike.
 */
internal fun interface RealtimeActiveThreadSink {
    fun deliver(serverId: String, event: RealtimeEvent)
}

internal fun interface RealtimeGuardedActiveThreadSink {
    suspend fun deliver(serverId: String, event: RealtimeEvent)
}

internal class SharedRealtimeDomainEventOwner(
    private val tasksDataStore: SharedTasksDataStore? = null,
    private val cursorStore: RealtimeAppliedSeqCursorStore? = null,
    private val runIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean = { _, _ -> false },
    private val runWithCurrentMessageGrant:
        (suspend (RealtimeGrantRecord, suspend (CurrentMessageCommitContext) -> Unit) -> Boolean)? = null,
    private val canonicalFinalOwnerGateway: RealtimeCanonicalFinalOwnerGateway? = null,
) : RealtimeDomainEventOwner {
    private var sinks: RealtimeDomainEventSinks? = null
    private var guardedSinks: RealtimeGuardedDomainEventSinks? = null
    private val activeThreadSinks = mutableListOf<RealtimeActiveThreadSink>()
    private val guardedActiveThreadSinks = mutableListOf<RealtimeGuardedActiveThreadSink>()
    private val acceptedKeys = ArrayDeque<String>()
    private val acceptedKeySet = mutableSetOf<String>()
    private val recoveryStates = mutableMapOf<String, MessageSeqRecoveryState<RealtimeEvent>>()

    // Flag-gated `sync_core_messages_v1` shadow fold+persist lane. Null until the provider installs it (see
    // installMessageShadowFoldLane); a null lane means the shadow path is entirely absent (e.g. unit tests
    // that don't exercise it). Invisible to sinks and fail-open; the legacy unowned path can only emit a
    // bounded partition-denial diagnostic and never persists.
    private var messageShadowFoldLane: SyncCoreMessageShadowFoldLane? = null
    // Provider-central T3 lane. Guarded canonical entries invoke it before acceptedKeys/recovery/sinks;
    // every owner sharing this database installs the same instance.
    private var currentGrantMessageShadowCommitLane: CurrentGrantMessageShadowCommitLane? = null
    private var reactionShadowLane: SyncCoreReactionShadowLane? = null
    private var canonicalMessageV2OwnerGateway: CanonicalMessageV2OwnerGateway? = null

    fun installMessageShadowFoldLane(lane: SyncCoreMessageShadowFoldLane) {
        messageShadowFoldLane = lane
    }

    fun installCurrentGrantMessageShadowCommitLane(lane: CurrentGrantMessageShadowCommitLane) {
        currentGrantMessageShadowCommitLane = lane
    }

    fun installReactionShadowLane(lane: SyncCoreReactionShadowLane) {
        reactionShadowLane = lane
    }

    fun installCanonicalMessageV2OwnerGateway(gateway: CanonicalMessageV2OwnerGateway) {
        canonicalMessageV2OwnerGateway = gateway
    }

    /** Task #167: forwards a new realtime transport epoch to the durable message canary. Caller checks the grant. */
    suspend fun noteTransportEpoch(grant: RealtimeGrantRecord, transportEpoch: Long) {
        canonicalMessageV2OwnerGateway?.noteTransportEpoch(grant, transportEpoch)
    }

    // task #136 (agent-status SSOT): agent presence facts must reach the
    // process presence registry for the OWNER's whole lifetime, not only while
    // a page composition happens to have sinks registered — the page-scoped
    // sink windows were exactly how machine-offline transitions got lost on
    // the Members surface. The sink receives the IMMUTABLE admission identity
    // derived from the grant the event was accepted under (post-merge audit
    // on PR #1054: resolving the owner from live auth state at delivery let a
    // late old-account callback be attributed to the new account). Entry
    // points without a grant have no principal and must NOT write presence.
    private var memberPresenceSink: ((RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit)? = null
    private var activityUnreadSink: ((ProcessActivityUnreadAdmission, RealtimeEvent) -> Unit)? = null

    fun installMemberPresenceSink(sink: (RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit) {
        memberPresenceSink = sink
    }

    fun installActivityUnreadSink(sink: (ProcessActivityUnreadAdmission, RealtimeEvent) -> Unit) {
        activityUnreadSink = sink
    }

    override fun registerSinks(sinks: RealtimeDomainEventSinks) {
        this.sinks = sinks
    }

    override fun unregisterSinks(sinks: RealtimeDomainEventSinks) {
        if (this.sinks === sinks || this.sinks == sinks) {
            this.sinks = null
        }
    }

    fun registerGuardedSinks(sinks: RealtimeGuardedDomainEventSinks) {
        guardedSinks = sinks
    }

    fun unregisterGuardedSinks(sinks: RealtimeGuardedDomainEventSinks) {
        if (guardedSinks === sinks || guardedSinks == sinks) {
            guardedSinks = null
        }
    }

    override fun registerActiveThreadSink(sink: RealtimeActiveThreadSink) {
        if (sink !in activeThreadSinks) {
            activeThreadSinks.add(sink)
        }
    }

    override fun unregisterActiveThreadSink(sink: RealtimeActiveThreadSink) {
        activeThreadSinks.remove(sink)
    }

    fun registerGuardedActiveThreadSink(sink: RealtimeGuardedActiveThreadSink) {
        if (sink !in guardedActiveThreadSinks) {
            guardedActiveThreadSinks.add(sink)
        }
    }

    fun unregisterGuardedActiveThreadSink(sink: RealtimeGuardedActiveThreadSink) {
        guardedActiveThreadSinks.remove(sink)
    }

    suspend fun accept(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        transportEpoch: Long? = null,
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.Selected,
            grant,
            event,
            source,
            transportEpoch,
        ) { serverId, acceptedEvent, acceptedSource ->
            // The admission identity is frozen from the grant this event was
            // accepted under — the only entry allowed to write presence.
            acceptGuarded(serverId, acceptedEvent, acceptedSource, grant.presenceAdmission())
        }

    suspend fun acceptHomeUnreadProjection(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        transportEpoch: Long? = null,
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
            grant,
            event,
            source,
            transportEpoch,
        ) { serverId, acceptedEvent, acceptedSource ->
            acceptGuardedHomeUnreadProjection(serverId, acceptedEvent, acceptedSource)
        }

    suspend fun acceptProcessMutation(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        presenceTransportEpoch: Long? = null,
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.Process,
            grant,
            event,
            source,
            presenceTransportEpoch,
        ) { serverId, acceptedEvent, acceptedSource ->
            // Process transport grants never capture shell surfaces. The
            // canonical commit still runs first, including with zero mounted
            // sinks, before process-global cursor/tasks/presence mutate once.
            acceptGuarded(
                serverId,
                acceptedEvent,
                acceptedSource,
                grant.presenceAdmission(presenceTransportEpoch)
            )
        }

    suspend fun acceptTerminalProcessMutation(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
        runTerminalIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean,
        terminalMutation: suspend () -> Unit,
    ): Boolean =
        runTerminalIfCurrentGrant(grant) {
            acceptGuarded(grant.serverId, event, source, grant.presenceAdmission())
            terminalMutation()
        }

    /**
     * T3 owner entry shared by selected-server and background-unread guarded projections. A nullable runner
     * preserves the exact legacy [runIfCurrentGrant] path for standalone/test owners; provider-owned owners
     * run the central canonical shadow lane first, then continue through the unchanged legacy path.
     */
    private suspend fun runGuardedCanonicalShadowFirst(
        entry: RealtimeCanonicalFinalOwnerEntry,
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String,
        transportEpoch: Long?,
        legacyContinuation: suspend (String, RealtimeEvent, String) -> Unit,
    ): Boolean {
        val currentMessageRunner = runWithCurrentMessageGrant
            ?: return runIfCurrentGrant(grant) {
                legacyContinuation(grant.serverId, event, source)
            }
        val foldInput = event.canonicalMessageFoldInputOrNull()
        if (foldInput == null) {
            return runIfCurrentGrant(grant) {
                legacyContinuation(grant.serverId, event, source)
            }
        }
        when (canonicalMessageV2OwnerGateway?.accept(grant, foldInput, transportEpoch)) {
            // Held = the durable write failed (nothing was written). Display must not fail with it
            // (task #168): the event takes the same post-canary legacy path as a committed one.
            // Pending = the event is buffered for the channel's loading enter snapshot: the canary returned
            // without loading anything, so the event is displayed now under the grant it arrived with.
            CanonicalMessageV2CanaryOutcome.Committed,
            CanonicalMessageV2CanaryOutcome.Pending,
            is CanonicalMessageV2CanaryOutcome.Held,
            ->
                return runIfCurrentGrant(grant) {
                    legacyContinuation(grant.serverId, event, source)
                }
            CanonicalMessageV2CanaryOutcome.NotSelected,
            null,
            -> Unit
        }
        val legacyKey = event.realtimeDomainKey(grant.serverId)?.let(::LegacyRealtimeDedupKey)
        val gateway = canonicalFinalOwnerGateway
        if (gateway != null && legacyKey != null) {
            return gateway.accept(
                entry = entry,
                grant = grant,
                event = event,
                source = source,
                legacyKey = legacyKey,
                foldInput = foldInput,
                legacyWithoutT3 = { context ->
                    legacyContinuation(context.partition.serverId, event, source)
                },
                legacyWithCurrentT3 = { context ->
                    runCurrentGrantMessageShadowThenLegacy(
                        context = context,
                        event = event,
                        source = source,
                        foldInput = foldInput,
                        legacyKey = legacyKey,
                        legacyContinuation = legacyContinuation,
                    )
                },
                commitPrepared = { context ->
                    currentGrantMessageShadowCommitLane?.commitPrepared(
                        partition = context.partition,
                        legacyKey = legacyKey,
                        foldInput = foldInput,
                    ) ?: PreparedCurrentGrantMessageCommitDisposition.LegacyFallback.DefinitelyPrewrite(
                        "lane_unavailable",
                    )
                },
            )
        }
        return currentMessageRunner(grant) { context ->
            runCurrentGrantMessageShadowThenLegacy(
                context = context,
                event = event,
                source = source,
                foldInput = foldInput,
                legacyKey = legacyKey,
                legacyContinuation = legacyContinuation,
            )
        }
    }

    private suspend fun runCurrentGrantMessageShadowThenLegacy(
        context: CurrentMessageCommitContext,
        event: RealtimeEvent,
        source: String,
        foldInput: CanonicalMessageFoldInput,
        legacyKey: LegacyRealtimeDedupKey?,
        legacyContinuation: suspend (String, RealtimeEvent, String) -> Unit,
    ) {
        val lane = currentGrantMessageShadowCommitLane
        if (lane != null && legacyKey != null) {
            try {
                lane.commit(
                    partition = context.partition,
                    legacyKey = legacyKey,
                    foldInput = foldInput,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                try {
                    guardedSinks?.log?.invoke(
                        "current_grant_message_shadow_failed source=$source " +
                            "serverId=${context.partition.serverId} " +
                            "error=${throwable::class.simpleName ?: "unknown"}"
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Diagnostics are best-effort; a logger failure must not block legacy fail-open.
                }
            }
        }
        legacyContinuation(context.partition.serverId, event, source)
    }

    override fun accept(serverId: String, event: RealtimeEvent, source: String) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            sinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        // `sync_core_messages_v1` SHADOW partition gate, BEFORE the sink fan-out and invisible to it. This
        // legacy overload has no principal, so only canonical MessageNew/Updated frames are counted and
        // denied; non-message and non-canonical events produce no diagnostic. The sink path stays untouched.
        // Slice-2 lane insert point: when sole-apply becomes eligible (CANONICAL_FACT_SOLE_APPLY_ELIGIBLE,
        // V2 envelope), the per-scope MessageSoleApplyLane replaces this shadow persist HERE — message
        // frames route through fold+commit (one-txn) FIRST and only fan out to the sinks below on
        // CommitSucceeded. Dormant in slice-2: MessageSoleApplyLane.createIfEligible is structurally
        // false-gated, so no lane exists in production and this path stays byte-identical.
        messageShadowFoldLane?.let { lane ->
            if (lane.isEnabled()) {
                val deniedFrameCount = acceptedEvents.count { acceptedEvent ->
                    when (acceptedEvent) {
                        is MessageNew -> acceptedEvent.decodedCanonical != null
                        is MessageUpdated -> acceptedEvent.decodedCanonical != null
                        else -> false
                    }
                }
                if (deniedFrameCount > 0) {
                    lane.partitionDenied(
                        serverId = resolvedServerId,
                        reason = PartitionDenialReason.PrincipalUnavailable,
                        deniedFrameCount = deniedFrameCount,
                    )
                }
            }
        }
        reactionShadowLane?.let { lane ->
            if (lane.isEnabled()) observeReactionShadow(lane, resolvedServerId, acceptedEvents)
        }
        val activeSinks = sinks
        // Tee to Thread routes before the Heartbeat early-return: Thread
        // consumes Heartbeat seq for realtime gap recovery. Snapshot the list
        // so a sink unregistering during delivery cannot corrupt iteration.
        acceptedEvents.forEach { acceptedEvent ->
            activeThreadSinks.toList().forEach { sink -> sink.deliver(resolvedServerId, acceptedEvent) }
            if (acceptedEvent is Heartbeat) {
                activeSinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=none type=heartbeat"
                )
                return@forEach
            }
            if (acceptedEvent.updatesSharedTaskProjection()) {
                activeSinks?.log?.invoke(
                    "tasks_realtime_drop source=$source serverId=$resolvedServerId reason=owner_admission_missing"
                )
            }
            activeSinks?.applyHomeShell?.invoke(resolvedServerId, acceptedEvent)
            if (acceptedEvent is DirectMessageCreated) {
                activeSinks?.joinChannel?.invoke(acceptedEvent.channelId.value)
            }
            if (acceptedEvent is ServerPlanUpdated) {
                activeSinks?.log?.invoke(
                    "shared_realtime_server_plan_updated serverId=${acceptedEvent.serverId.value} plan=${acceptedEvent.plan}"
                )
            }
            if (acceptedEvent is MemberPresenceUpdated && acceptedEvent.kind == build.raft.app.model.MemberKind.Agent) {
                // This legacy overload has no principal (see the shadow-lane
                // note above): without a grant there is no admission identity,
                // so it must never write the presence registry — a held
                // old-account callback released here after an account switch
                // would otherwise be attributed to whoever is signed in now.
                activeSinks?.log?.invoke(
                    "presence_write_dropped source=$source serverId=$resolvedServerId reason=no_admission"
                )
                activeSinks?.applyAgentActivity?.invoke(acceptedEvent)
            } else if (acceptedEvent.refreshesMemberDetail()) {
                if (acceptedEvent is RealtimeRefreshRequired) {
                    activeSinks?.applyServerMembersRefresh?.invoke(resolvedServerId, acceptedEvent)
                }
                activeSinks?.requestMemberDetailRefresh?.invoke()
            }
            activeSinks?.emitRouteMarker?.invoke(acceptedEvent)
            if (acceptedEvent !is MemberPresenceUpdated) {
                activeSinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=domain"
                )
            }
        }
    }

    override fun acceptHomeUnreadProjection(serverId: String, event: RealtimeEvent, source: String) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            sinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        acceptedEvents.forEach { acceptedEvent ->
            if (acceptedEvent !is Heartbeat) {
                sinks?.applyHomeUnreadProjection?.invoke(resolvedServerId, acceptedEvent)
                sinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=home_unread_count"
                )
            }
        }
    }

    private suspend fun acceptGuarded(
        serverId: String,
        event: RealtimeEvent,
        source: String,
        admission: RealtimePresenceAdmission?,
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            guardedSinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        val activeSinks = guardedSinks
        acceptedEvents.forEach { acceptedEvent ->
            guardedActiveThreadSinks.toList().forEach { sink -> sink.deliver(resolvedServerId, acceptedEvent) }
            if (acceptedEvent is Heartbeat) {
                activeSinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=none type=heartbeat"
                )
                return@forEach
            }
            deliverGuardedAppShellDomainFinal(resolvedServerId, acceptedEvent, source, activeSinks, admission)
            // Product-side apply receipt for the subscription audit (task #13): logged once per
            // event AFTER the guarded domain-sink dispatch returned — i.e. after applyHomeShell /
            // applyHomeUnreadProjection and the member/presence writes completed without throwing.
            // A duplicate redelivery is dropped upstream at rememberAcceptedKey
            // (realtime_domain_accept_skip) and never reaches this line, and a sink failure
            // propagates before it, so one line per (id, seq) is the applied-once witness on the
            // product store edge — not merely a pre-dispatch gate pass.
            when (acceptedEvent) {
                is MessageNew ->
                    activeSinks?.log?.invoke(
                        "realtime_domain_apply source=$source serverId=$resolvedServerId " +
                            "kind=message_new id=${acceptedEvent.message.id.value} seq=${acceptedEvent.message.seq ?: -1L}"
                    )
                is MessageUpdated ->
                    activeSinks?.log?.invoke(
                        "realtime_domain_apply source=$source serverId=$resolvedServerId " +
                            "kind=message_updated id=${acceptedEvent.message.id.value} seq=${acceptedEvent.message.seq ?: -1L}"
                    )
                else -> Unit
            }
        }
    }

    private suspend fun acceptGuardedHomeUnreadProjection(
        serverId: String,
        event: RealtimeEvent,
        source: String,
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            guardedSinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
        acceptedEvents.forEach { acceptedEvent ->
            if (acceptedEvent !is Heartbeat) {
                guardedSinks?.let { activeSinks ->
                    deliverGuardedAppShellHomeUnreadFinal(
                        sinks = activeSinks,
                        serverId = resolvedServerId,
                        event = acceptedEvent,
                        source = source,
                    )
                }
            }
        }
    }

    suspend fun deliverGuardedAppShellDomainFinal(
        sinks: RealtimeGuardedDomainEventSinks,
        serverId: String,
        event: RealtimeEvent,
        source: String = "canonical_final",
    ) {
        // Canonical-final surface delivery: gateway-admitted message frames
        // only — no grant reaches this entry, so it carries no presence
        // admission and can never write the presence registry.
        deliverGuardedAppShellDomainFinal(serverId, event, source, sinks, admission = null)
    }

    private suspend fun deliverGuardedAppShellDomainFinal(
        serverId: String,
        event: RealtimeEvent,
        source: String,
        activeSinks: RealtimeGuardedDomainEventSinks?,
        admission: RealtimePresenceAdmission?,
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        if (event is Heartbeat) return
        event.patchSharedTaskProjection(resolvedServerId)
        deliverGuardedAppShellProjectionFinal(resolvedServerId, event, source, activeSinks)
        if (event is MemberPresenceUpdated && event.kind == build.raft.app.model.MemberKind.Agent) {
            if (admission != null) {
                memberPresenceSink?.invoke(admission, event)
            } else {
                activeSinks?.log?.invoke(
                    "presence_write_dropped source=$source serverId=$resolvedServerId reason=no_admission"
                )
            }
        }
        if (admission != null) {
            activityUnreadSink?.invoke(admission.toActivityUnreadAdmission(), event)
        }
    }

    private suspend fun deliverGuardedAppShellProjectionFinal(
        resolvedServerId: String,
        event: RealtimeEvent,
        source: String,
        activeSinks: RealtimeGuardedDomainEventSinks?,
    ) {
        activeSinks?.applyHomeShell?.invoke(resolvedServerId, event)
        if (event is DirectMessageCreated) {
            activeSinks?.joinChannel?.invoke(event.channelId.value)
        }
        if (event is ServerPlanUpdated) {
            activeSinks?.log?.invoke(
                "shared_realtime_server_plan_updated serverId=${event.serverId.value} plan=${event.plan}"
            )
        }
        if (event is MemberPresenceUpdated && event.kind == build.raft.app.model.MemberKind.Agent) {
            activeSinks?.applyAgentActivity?.invoke(event)
        } else if (event.refreshesMemberDetail()) {
            if (event is RealtimeRefreshRequired) {
                activeSinks?.applyServerMembersRefresh?.invoke(resolvedServerId, event)
            }
            activeSinks?.requestMemberDetailRefresh?.invoke()
        }
        activeSinks?.emitRouteMarker?.invoke(event)
        if (event !is MemberPresenceUpdated) {
            activeSinks?.log?.invoke(
                "realtime_domain_accept source=$source serverId=$resolvedServerId projection=domain"
            )
        }
    }

    suspend fun deliverGuardedAppShellHomeUnreadFinal(
        sinks: RealtimeGuardedDomainEventSinks,
        serverId: String,
        event: RealtimeEvent,
        source: String = "canonical_final",
    ) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        if (event is Heartbeat) return
        sinks.applyHomeUnreadProjection.invoke(resolvedServerId, event)
        sinks.log.invoke(
            "realtime_domain_accept source=$source serverId=$resolvedServerId projection=home_unread_count"
        )
    }

    /** Normalized reaction SHADOW observation. No persistence, cache mutation, cursor, or sink output. */
    private fun observeReactionShadow(
        lane: SyncCoreReactionShadowLane,
        resolvedServerId: String,
        acceptedEvents: List<RealtimeEvent>,
    ) {
        try {
            for (acceptedEvent in acceptedEvents) {
                val decoded =
                    when (acceptedEvent) {
                        is MessageNew -> acceptedEvent.decodedCanonical
                        is MessageUpdated -> acceptedEvent.decodedCanonical
                        else -> null
                    } ?: continue
                lane.observe(resolvedServerId, decoded)
            }
        } catch (throwable: Throwable) {
            lane.logFailure(resolvedServerId, messageId = "unknown", throwable)
        }
    }

    private fun acceptedEventsForFold(serverId: String, event: RealtimeEvent, source: String): List<RealtimeEvent> {
        val store = cursorStore ?: return listOf(event)
        val server = ServerId(serverId)
        val currentState = recoveryStates.getOrPut(serverId) {
            MessageSeqRecoveryPolicy.initial(
                appliedSeq = store.readAppliedLastSeq(server),
                epoch = store.currentEpoch(server)
            )
        }
        val result =
            when (event) {
                is MessageNew,
                is MessageUpdated -> {
                    val frame = event.messageSeqEnvelope(currentState.epoch) ?: return listOf(event)
                    MessageSeqRecoveryPolicy.onFrame(currentState, frame)
                }
                is SyncResumeResponse ->
                    MessageSeqRecoveryPolicy.onRepairBatch(
                        currentState,
                        MessageSeqRepairBatch(
                            events = event.messages.mapNotNull { message ->
                                MessageSeqEnvelope(
                                    seq = message.seq?.takeIf { it > 0L } ?: return@mapNotNull null,
                                    epoch = currentState.epoch,
                                    event = MessageNew(message)
                                )
                            },
                            currentSeq = event.currentSeq,
                            hasMore = event.hasMore,
                            epoch = currentState.epoch
                        )
                    )
                else -> return listOf(event)
            }
        val nextState =
            result.state.copy(
                serverSeq = maxOf(result.state.serverSeq, result.state.appliedSeq),
                appliedEvents = emptyList()
            )
        recoveryStates[serverId] = nextState
        if (nextState.appliedSeq > currentState.appliedSeq) {
            store.saveAppliedLastSeq(server, nextState.appliedSeq, nextState.epoch)
        }
        val frameSeq =
            when (event) {
                is MessageNew -> event.message.seq
                is MessageUpdated -> event.message.seq
                else -> null
            }
        if (frameSeq != null && frameSeq > currentState.appliedSeq + 1L) {
            sinks?.log?.invoke(
                "realtime_domain_accept_repair_request source=$source serverId=$serverId " +
                    "reason=message_seq_gap serverSeq=${result.state.serverSeq} appliedSeq=${result.state.appliedSeq}"
            )
        }
        return when (event) {
            is MessageNew,
            is MessageUpdated,
            is SyncResumeResponse -> listOf(event)
            else -> result.state.appliedEvents
        }
    }

    private fun rememberAcceptedKey(key: String): Boolean {
        if (!acceptedKeySet.add(key)) return false
        acceptedKeys.addLast(key)
        while (acceptedKeys.size > AcceptedRealtimeKeyLimit) {
            acceptedKeySet.remove(acceptedKeys.removeFirst())
        }
        return true
    }

    private suspend fun RealtimeEvent.patchSharedTaskProjection(serverId: String) {
        val store = tasksDataStore ?: return
        suspend fun patch(task: TaskSummary) {
            when (store.upsertTaskForCurrentOwner(serverId, task)) {
                SharedTaskFactFoldOutcome.Accepted -> Unit
                SharedTaskFactFoldOutcome.StaleIgnored ->
                    sinks?.log?.invoke(
                        "tasks_canonical_stale_drop serverId=$serverId taskId=${task.id.value} " +
                            "revision=${task.revision ?: -1L}"
                    )
                SharedTaskFactFoldOutcome.Conflict ->
                    sinks?.log?.invoke(
                        "tasks_canonical_conflict serverId=$serverId taskId=${task.id.value} " +
                        "revision=${task.revision ?: -1L} requires=authoritative_refetch"
                    )
                null ->
                    sinks?.log?.invoke(
                        "tasks_realtime_drop serverId=$serverId taskId=${task.id.value} reason=owner_changed"
                    )
            }
        }
        when (this) {
            is TaskCreated -> tasks.forEach { task -> patch(task) }
            is TaskUpdated -> patch(task)
            is TaskDeleted -> {
                if (!store.removeTaskForCurrentOwner(serverId, taskId)) {
                    sinks?.log?.invoke(
                        "tasks_realtime_drop serverId=$serverId taskId=${taskId.value} reason=owner_changed"
                    )
                }
            }
            else -> Unit
        }
    }
}

private fun RealtimeEvent.updatesSharedTaskProjection(): Boolean =
    this is TaskCreated || this is TaskUpdated || this is TaskDeleted

private fun RealtimeEvent.refreshesMemberDetail(): Boolean =
    this is AgentCreated ||
        (
            this is RealtimeRefreshRequired &&
                (
                    scope == RealtimeRefreshScope.ServerMembers ||
                        scope == RealtimeRefreshScope.Agents ||
                        scope == RealtimeRefreshScope.Machines
                    )
            ) ||
        this is ServerPlanUpdated

private const val AcceptedRealtimeKeyLimit = 256

private fun RealtimeEvent.canonicalMessageFoldInputOrNull(): CanonicalMessageFoldInput? =
    when (this) {
        is MessageNew -> decodedCanonical?.let { carry ->
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, carry)
        }
        is MessageUpdated -> decodedCanonical?.let { carry ->
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.Updated, carry)
        }
        else -> null
    }

private fun RealtimeEvent.realtimeDomainKey(serverId: String): String? =
    when (this) {
        is MessageNew ->
            "message:new:$serverId:${message.id.value}:${message.seq ?: -1L}"
        is MessageUpdated ->
            "message:updated:$serverId:${message.id.value}:${message.seq ?: -1L}:${message.updatedAtLabel.orEmpty()}"
        is DirectMessageCreated ->
            "dm:new:$serverId:${channelId.value}"
        is ThreadUpdated ->
            "thread:updated:$serverId:${threadChannelId.value}:${parentMessageId.value}:$replyCount:${unreadCount ?: -1}:" +
                "${firstUnreadMessageId?.value.orEmpty()}:${lastReplyAt.orEmpty()}:" +
                "${latestReply?.messageId.orEmpty()}:${latestReply?.seq ?: -1L}"
        is TaskCreated ->
            "task:created:$serverId:${tasks.joinToString("|") { it.taskDomainKey() }}"
        is TaskUpdated ->
            "task:updated:$serverId:${task.taskDomainKey()}"
        is TaskDeleted ->
            "task:deleted:$serverId:${taskId.value}"
        is MemberPresenceUpdated ->
            // agent:activity serverSeq is monotonic per agent, not per server.
            // Include memberId so different agents with the same seq do not
            // suppress each other's presence updates in the app-shell fanout.
            // agent:seen is seq-less; lastSeenAt is the key so a new
            // observation is never deduped away.
            serverSeq?.let { "member:presence:$serverId:${memberId.value}:$it" }
                ?: "member:presence:$serverId:${memberId.value}:${presence.name}:${agentStatus?.name.orEmpty()}:${statusLabel.orEmpty()}:${detailKind.orEmpty()}:${activityToolName.orEmpty()}:${lastSeenAt.orEmpty()}"
        // task #136: refresh hints are idempotent TRIGGERS, not facts, and the
        // event carries no seq — a stable key made the accepted-key ledger
        // swallow every later identical hint (artin's device logs:
        // accept_skip reason=duplicate key=refresh:…:Agents:agent:session:…),
        // so a second state change for the same agent never re-fetched. No key
        // → every hint processed; the downstream refetch already dedupes
        // in-flight loads, so bursts stay cheap.
        is RealtimeRefreshRequired -> null
        is ServerPlanUpdated ->
            "server:plan:$serverId:${this.serverId.value}:$plan"
        is AgentCreated ->
            "agent:created:$serverId:${agent.id.value}"
        is SyncResumeResponse ->
            "sync:resume:$serverId:$currentSeq:${messages.joinToString("|") { it.id.value }}"
        is Heartbeat ->
            "heartbeat:$serverId:$seq"
        else -> null
    }

private fun TaskSummary.taskDomainKey(): String =
    "${id.value}:${revision ?: -1L}:${status.name}:${updatedAtLabel}:${assignee?.id?.value.orEmpty()}"

private fun RealtimeEvent.messageSeqEnvelope(epoch: String): MessageSeqEnvelope<RealtimeEvent>? =
    when (this) {
        is MessageNew ->
            message.seq
                ?.takeIf { it > 0L }
                ?.let { seq -> MessageSeqEnvelope(seq = seq, epoch = epoch, event = this) }
        is MessageUpdated ->
            message.seq
                ?.takeIf { it > 0L }
                ?.let { seq -> MessageSeqEnvelope(seq = seq, epoch = epoch, event = this) }
        else -> null
    }

internal object RaftSharedStoreRegistry {
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
    fun get(host: HostCapabilities): RaftSharedStoreProvider {
        val eventBus = RaftEventBuses.appScope()
        return get(eventBus, "shared-store-provider", host)
    }

    fun get(eventBus: RaftEventBus, source: String, host: HostCapabilities): RaftSharedStoreProvider =
        access.get(
            RaftSharedStoreProviderRequest(
                eventBus = eventBus,
                source = source,
                host = host,
            ),
        )
}
