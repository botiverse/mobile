package build.raft.app.data

import build.raft.app.base.BridgeModule
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
import build.raft.app.CoroutineThreadComposerDraftPersistenceScheduler
import build.raft.app.KeyValuePixelAvatarStore
import build.raft.app.SharedThreadMessageStore
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.SlockPixelAvatarRuntime
import build.raft.app.SlockPlatformLogHint
import build.raft.app.SharedForegroundBannerStore
import build.raft.app.data.realtime.MessageSeqEnvelope
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.MessageSeqRecoveryPolicy
import build.raft.app.data.realtime.MessageSeqRecoveryState
import build.raft.app.data.realtime.MessageSeqRepairBatch
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.refreshPixelAvatarsFromWeb
import build.raft.app.slockLogError
import build.raft.app.slockLogInfo
import build.raft.app.slockLogWarn
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
import build.raft.app.network.SlockRequestScheduler
import build.raft.app.network.createPlatformSlockHttpExecutor
import build.raft.app.network.SlockNetworkTransportController
import build.raft.app.network.installSlockNetworkTransportController
import build.raft.app.network.SlockNetworkKmmRecoveryRemoteFlagBridge
import build.raft.app.network.slockNetworkKmmRecoveryEnvironmentId
import build.raft.app.network.sync.AttachmentCommentsReadCacheV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CurrentGrantMessageShadowCommitLane
import build.raft.app.network.sync.DefaultFoldPersistenceAdapter
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.PreparedCurrentGrantMessageCommitDisposition
import build.raft.app.network.sync.SqlDelightFoldPersistStore
import build.raft.app.network.sync.SqlDelightFoldPersistTransactionRunner
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SqlDelightStrictMessageScopeSnapshotReader
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.SqlDelightReadStateV1Store
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreMessageShadowObserver
import build.raft.app.network.sync.SyncCoreReactionShadowLane
import build.raft.app.network.sync.PartitionDenialReason
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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

private fun BridgeModule.logSharedAuthDiagnostic(
    prefix: String,
    message: String,
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
) {
    val line = "$prefix $message"
    slockLogInfo(SHARED_AUTH_LOG_TAG, line, hint)
}

private fun sharedInfoLogger(
    tag: String,
    prefix: String = "",
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
): (String) -> Unit =
    { message ->
        slockLogInfo(tag, if (prefix.isBlank()) message else "$prefix $message", hint)
    }

private fun sharedWarnLogger(
    tag: String,
    prefix: String = "",
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
): (String) -> Unit =
    { message ->
        slockLogWarn(tag, if (prefix.isBlank()) message else "$prefix $message", hint)
    }

private fun sharedErrorLogger(tag: String, prefix: String = ""): (String) -> Unit =
    { message ->
        slockLogError(tag, if (prefix.isBlank()) message else "$prefix $message")
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
    authStore: SlockAuthSessionStore,
    authEvents: SlockAuthSessionEventSource,
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

internal fun interface SlockSharedCanonicalFinalIntegrationFactory {
    fun create(
        scope: SlockSharedCanonicalFinalIntegrationFactoryScope,
    ): RealtimeCanonicalFinalSinkIntegrationSeam
}

/**
 * Production-owned dependencies for an optional canonical-final integration. The normal provider factory passes
 * no integration factory, so none of these dormant objects exist in production today. A bound host factory can
 * only ask this scope to compose the seam from the exact registry/auth/flags/directory/router graph created by the
 * one full [SlockSharedStoreProvider.create] call; it cannot replace the authority provider or any of its owners.
 */
internal class SlockSharedCanonicalFinalIntegrationFactoryScope internal constructor(
    val registry: RealtimeAuthorityRegistry,
    val authStore: KeyValueAuthSessionStore,
    val authEvents: SlockAuthSessionEventSource,
    val crossProcessAuthEvents: KuiklyAuthSessionEvents,
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

internal data class SlockSharedStoreProviderRequest(
    val eventBus: SlockEventBus,
    val source: String,
    val bridge: BridgeModule,
)

internal fun interface SlockSharedStoreProviderFactory {
    fun create(request: SlockSharedStoreProviderRequest): SlockSharedStoreProvider
}

internal class SlockSharedStoreFactoryBindingHandle internal constructor(
    internal val ownerToken: Any,
    internal val bindingToken: Any,
)

internal class SlockSharedStoreRegistryState<T : Any, A>(
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

    fun bindFactoryBeforeFirstGet(factory: (A) -> T): SlockSharedStoreFactoryBindingHandle =
        synchronized(lock) {
            check(instance == null) { "shared store factory binding is sealed after first get" }
            val token = Any()
            boundFactory = BoundFactory(token = token, factory = factory)
            SlockSharedStoreFactoryBindingHandle(ownerToken = ownerToken, bindingToken = token)
        }

    fun unbindFactoryBeforeFirstGet(handle: SlockSharedStoreFactoryBindingHandle): Boolean =
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

internal class SlockSharedStoreRegistryAccess<T : Any, A>(
    defaultFactory: (A) -> T,
    updateExisting: (T, A) -> Unit,
) {
    private val state = SlockSharedStoreRegistryState(defaultFactory, updateExisting)

    fun currentOrNull(): T? = state.currentOrNull()

    fun bindFactoryBeforeFirstGet(factory: (A) -> T): SlockSharedStoreFactoryBindingHandle =
        state.bindFactoryBeforeFirstGet(factory)

    fun unbindFactoryBeforeFirstGet(handle: SlockSharedStoreFactoryBindingHandle): Boolean =
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

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    private val authKeyValueStore: SlockKeyValueStore,
    private val sharedPreferenceStore: SlockKeyValueStore,
    private val apiEnvironmentKeyValueStore: SlockKeyValueStore,
    val authStore: KeyValueAuthSessionStore,
    val authSessionEvents: KuiklyAuthSessionEvents,
    val authDataSource: SharedAuthDataSource,
    val authLossFunnel: SharedAuthLossFunnel,
    val networkClient: SlockNetworkClient,
    val networkTransportPreferenceRepository: NetworkTransportPreferenceRepository,
    private val scopedNetworkClientFactory: (SlockAuthSessionStore) -> SlockNetworkClient,
    private val ownerScopedNetworkClientFactory: (SlockAuthSessionStore, String) -> SlockNetworkClient,
    val serverFeatureFlags: SharedServerFeatureFlags,
    private val currentGrantMessageShadowCommitLane: CurrentGrantMessageShadowCommitLane,
    private val canonicalMessageV2Canary: CanonicalMessageV2ProductionCanary,
    val localDataStore: ComposeLocalDataStore,
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
    private val memberPresenceRealtimeSink: (RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    private val activityPersistSlice: MviPersistSlice<SharedActivityState, SharedActivityIntent>,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    private val homeIdentityPersistSlice: MviPersistSlice<SharedHomeState, SharedHomeIntent>,
    val apiEnvironmentStore: SharedApiEnvironmentStore,
    val eventBus: SlockEventBusRelay,
    val realtimeDomainEventOwner: SharedRealtimeDomainEventOwner,
    val realtimeAuthorityProvider: RealtimeAuthorityProvider,
    val messageTranslationStore: SharedMessageTranslationStore,
    val threadMessageStore: SharedThreadMessageStore,
    val threadPayloadStore: SharedThreadPayloadStore,
    val foregroundReconciler: SharedForegroundReconciler,
    private val homeStoreScope: CoroutineScope,
    private val homeActivityStoreScope: CoroutineScope,
    val threadSendScope: CoroutineScope
) {
    private val selectedServerStores = SlockShellScopedStoreCache<SelectedServerStore>()
    private val shellAuthStores = SlockShellScopedStoreCache<SlockShellAuthSessionStore>()
    private val shellNetworkClients = SlockShellScopedStoreCache<SlockNetworkClient>()
    private val shellServerMembersStores = SlockShellScopedStoreCache<SharedServerMembersStore>()
    private val shellRealtimeDomainEventOwners = SlockShellScopedStoreCache<SharedRealtimeDomainEventOwner>()
    private val realtimeAuthorityRegistrations = SlockShellScopedStoreCache<String>()
    private val shellAgentDetailStores = SlockShellScopedStoreCache<SharedAgentDetailStore>()
    private val shellNavigationCoordinators = SlockShellScopedStoreCache<SharedWindowNavigationCoordinator>()
    private val shellForegroundBannerStores = SlockShellScopedStoreCache<SharedForegroundBannerStore>()
    private val processReadReceiptLock = SynchronizedObject()
    private var processReadReceiptCoordinator: ReadReceiptCoordinator? = null
    private val processReadReceiptRepositories = mutableMapOf<ReadReceiptOwner, RealReadReceiptRepository>()
    private val homeStores = SlockShellScopedStoreCache<SharedHomeStore>()
    private val homeActivityStores = SlockShellScopedStoreCache<SharedActivityStore>()
    private val homeStoreScopes = SlockShellScopedStoreCache<CoroutineScope>()
    private val homeActivityStoreScopes = SlockShellScopedStoreCache<CoroutineScope>()
    private val processReadStateV1Canary: ReadStateV1ProductionCanary by lazy {
        ReadStateV1ProductionCanary(
            isEnabledForServer = { serverId ->
                serverFeatureFlags.isEnabled(ServerId(serverId), SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY)
            },
            store = SqlDelightReadStateV1Store(sqlDatabase, now = ::defaultNowMillis),
            onProjection = { owner, _, fact ->
                homeStores.entries().forEach { (_, store) -> store.applyReadStateV1Projection(owner, fact) }
                homeActivityStores.entries().forEach { (_, store) -> store.applyReadStateV1Projection(owner, fact) }
            },
            onRebaseline = { owner, _, channelId ->
                homeStores.entries().forEach { (_, store) -> store.dispatch(SharedHomeIntent.ForegroundRefresh) }
                homeActivityStores.entries().forEach { (_, store) ->
                    store.requestReadStateV1Rebaseline(owner, channelId)
                }
                sharedInfoLogger("ReadStateV1Canary", "rebaseline")("serverId=${owner.serverId}")
            },
            logger = sharedInfoLogger("ReadStateV1Canary", "production_canary"),
        )
    }
    private var authSessionClearedSubscription: RealtimeSubscription? = null
    private var authSessionClearLogger: ((String) -> Unit)? = null
    private val activeAppShellPagerOwner = AppShellActivePagerOwner()
    private val processRealtimeAuthorityFlow =
        realtimeAuthorityProvider.authorityFlow(ProcessRealtimeAuthorityScopeKey)

    private fun updateNetworkKmmRecoveryEnvironment(): String {
        val environment = apiEnvironmentStore.current()
        val environmentId =
            slockNetworkKmmRecoveryEnvironmentId(
                environmentKey = environment.key,
                apiBaseUrl = environment.apiBaseUrl
            )
        runCatching {
            SlockNetworkKmmRecoveryRemoteFlagBridge.updateCurrentEnvironment(environmentId)
        }.onSuccess { diagnostics ->
            diagnostics?.let(::logNetworkKmmRemoteConfig)
        }.onFailure { error ->
            sharedWarnLogger("SlockNetworkKmm", "networkkmm_remote_config")(
                "environment_update_failed reason=${error::class.simpleName ?: "unknown"}"
            )
        }
        return environmentId
    }

    /**
     * Server feature-flag evaluation (sync-core rollout gate). Prefetched per shell when its selected
     * server changes (see [selectedServerStore]) — this is what makes `sync_core_messages_v0` traffic
     * appear at `feature-flags/evaluate`. Reads default OFF until resolved, so gated features stay dark.
     */
    /**
     * Flag-gated canonical projection gateway injected into [RealtimeEventMapper]. Off by default
     * (`sync_core_messages_v0`), so it is a single flag lookup on the hot path until the rollout enables it;
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
        updateNetworkKmmRecoveryEnvironment()
        // Attach canonical/reaction shadow mutation to the dedicated process
        // owner. Shell owners are projection-only and never repeat this work.
        realtimeDomainEventOwner.installMessageShadowFoldLane(buildMessageShadowFoldLane())
        realtimeDomainEventOwner.installReactionShadowLane(buildReactionShadowLane())
        realtimeDomainEventOwner.installCanonicalMessageV2OwnerGateway(
            buildCanonicalMessageV2OwnerGateway(authStore, networkClient),
        )
        realtimeAuthorityProvider.start(threadSendScope)
        threadSendScope.launch {
            realtimeAuthorityProvider.registerScope(
                scopeKey = ProcessRealtimeAuthorityScopeKey,
                registrationId = ProcessRealtimeAuthorityRegistrationId,
            )
        }
        threadSendScope.launch {
            authStore.sessionInvalidations.collect {
                clearActiveShellServerSelections()
                updateNetworkKmmRecoveryEnvironment()
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

    private fun buildCanonicalMessageV2OwnerGateway(
        sessionStore: SlockAuthSessionStore,
        client: SlockNetworkClient,
    ): CanonicalMessageV2OwnerGateway =
        ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = canonicalMessageV2Canary,
            authStore = sessionStore,
            networkClient = client,
            logger = sharedInfoLogger("CanonicalMessageV2Canary", "canonical_v2_canary"),
        )

    val conversationIdentityProvider: SharedConversationIdentityProvider?
        get() = conversationIdentityProvider(SlockShellStoreScope.Legacy)

    val sharedHomeStore: SharedHomeStore?
        get() = sharedHomeStore(SlockShellStoreScope.Legacy)

    fun conversationIdentityProvider(scope: SlockShellStoreScope): SharedConversationIdentityProvider? =
        homeStores.get(scope)

    fun sharedHomeStore(scope: SlockShellStoreScope): SharedHomeStore? =
        homeStores.get(scope)

    fun selectedServerStore(scope: SlockShellStoreScope): SelectedServerStore =
        selectedServerStores.getOrPut(scope) {
            SelectedServerStore(
                scope = scope,
                onSelectionChanged = { serverId ->
                    runCatching {
                        SlockNetworkKmmRecoveryRemoteFlagBridge.updateActiveSelection(
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

    fun scopesForServer(serverId: String): List<SlockShellStoreScope> {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        return selectedServerStores.entries().mapNotNull { (scope, selectionStore) ->
            scope.takeIf { selectionStore.isSelected(normalizedServerId) && !selectionStore.disposed.value }
        }
    }

    fun authStore(scope: SlockShellStoreScope): SlockAuthSessionStore =
        shellAuthStores.getOrPut(scope) {
            SlockShellAuthSessionStore(
                delegate = authStore,
                selectedServerStore = selectedServerStore(scope),
                onProcessSessionCleared = ::clearActiveShellServerSelections
            )
        }

    fun networkClient(scope: SlockShellStoreScope): SlockNetworkClient =
        if (scope == SlockShellStoreScope.Legacy) {
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
    fun serverMembersStore(scope: SlockShellStoreScope): SharedServerMembersStore =
        if (scope == SlockShellStoreScope.Legacy) {
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
                    processOwner = { serverId -> processServerMembersOwner(authStore(scope), serverId) }
                )
            }
        }

    private suspend fun processServerMembersOwner(
        sessionStore: SlockAuthSessionStore,
        serverId: ServerId
    ): ProcessServerMembersOwner? {
        val session = sessionStore.readSession() ?: return null
        val environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
        val accountId = session.user.id.trim()
        val normalizedServerId = serverId.value.trim()
        if (environmentId.isBlank() || accountId.isBlank() || normalizedServerId.isBlank()) return null
        return ProcessServerMembersOwner(environmentId, accountId, normalizedServerId)
    }

    fun realtimeDomainEventOwner(scope: SlockShellStoreScope): SharedRealtimeDomainEventOwner =
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
                    buildCanonicalMessageV2OwnerGateway(authStore(scope), networkClient(scope)),
                )
            }
        }

    fun processRealtimeDomainEventOwner(): SharedRealtimeDomainEventOwner = realtimeDomainEventOwner

    /**
     * Provider-owned authority flow for one mounted AppShell. Registration is
     * idempotent by shellInstanceId; callers only observe the flow and never
     * mint/rotate registry identities themselves.
     */
    fun realtimeAuthority(scope: SlockShellStoreScope): StateFlow<RealtimeScopeAuthority?> {
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

    fun agentDetailStore(scope: SlockShellStoreScope): SharedAgentDetailStore =
        if (scope == SlockShellStoreScope.Legacy) {
            agentDetailStore
        } else {
            shellAgentDetailStores.getOrPut(scope) {
                SharedAgentDetailStore(
                    logger = sharedInfoLogger("SlockAgentDetail", "shell_agent_detail_store")
                )
            }
        }

    /**
     * Owns pending external navigation for one document/AppShell. Recreating the page must not
     * replay an already opened notification route, while closing the shell drops its queue.
     */
    fun windowNavigationCoordinator(scope: SlockShellStoreScope): SharedWindowNavigationCoordinator =
        shellNavigationCoordinators.getOrPut(scope) { SharedWindowNavigationCoordinator() }

    fun foregroundBannerStore(scope: SlockShellStoreScope): SharedForegroundBannerStore =
        shellForegroundBannerStores.getOrPut(scope) { SharedForegroundBannerStore(threadSendScope) }

    fun createAuthDataSourceForCurrentEnvironment(): SharedAuthDataSource =
        createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = apiEnvironmentStore,
            authStore = authStore
        )

    fun createAuthDataSourceForCurrentEnvironment(scope: SlockShellStoreScope): SharedAuthDataSource =
        createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = apiEnvironmentStore,
            authStore = authStore(scope)
        )

    fun markActiveAppShellPager(pagerId: String) {
        activeAppShellPagerOwner.markActive(pagerId)
    }

    fun isActiveAppShellPager(pagerId: String): Boolean =
        activeAppShellPagerOwner.isActive(pagerId)

    fun updateEventBus(nextEventBus: SlockEventBus, source: String) {
        eventBus.update(nextEventBus)
        authSessionEvents.updateEventBus(eventBus, source)
        authSessionClearLogger?.let(::installAuthSessionClearNotifyBridge)
    }

    fun getOrCreateHomeStore(
        scope: SlockShellStoreScope = SlockShellStoreScope.Legacy,
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
                        if (scope == SlockShellStoreScope.Legacy) homeStoreScope else CoroutineScope(SupervisorJob())
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
                persistSlices = listOf(homeIdentityPersistSlice)
            )
        }
    }

    fun getOrCreateHomeActivityStore(
        scope: SlockShellStoreScope = SlockShellStoreScope.Legacy,
        inboxRepository: InboxRepository,
        readReceiptRepository: ReadReceiptRepository,
        conversationRepository: ConversationRepository,
        memberRepository: MemberRepository? = null,
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
                        if (scope == SlockShellStoreScope.Legacy) homeActivityStoreScope else CoroutineScope(SupervisorJob())
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
            requestRunner = { request, defaultRequest ->
                if (request.owner == ReadReceiptOwner.Legacy) {
                    defaultRequest()
                } else {
                    val serverId = ServerId(request.owner.serverId)
                    val ownerRepository = processReadReceiptRepository(request.owner)
                    when (request) {
                        is ReadReceiptRequest.ToSeq ->
                            ownerRepository.markChannelReadToSeq(serverId, request.channelId, request.seq)
                        is ReadReceiptRequest.All ->
                            ownerRepository.markChannelReadAll(serverId, request.channelId)
                    }
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
                homeStores.entries().forEach { (_, store) ->
                    store.dispatch(SharedHomeIntent.ApplyReadReceiptPrediction(prediction))
                }
                homeActivityStores.entries().forEach { (_, store) ->
                    store.dispatch(SharedActivityIntent.ApplyReadReceiptPrediction(prediction))
                }
            },
            onResult = { request, result ->
                val content = result as? DataResult.Content
                if (request.owner != ReadReceiptOwner.Legacy && content != null) {
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
                sharedInfoLogger("SlockReadReceipt", "process_read_receipt")(
                    "result owner=${request.owner.serverId}:${request.owner.accountId} " +
                        "channelId=${request.channelId.value} revision=${request.revision} " +
                        "result=${result::class.simpleName.orEmpty()}"
                )
            },
            onOwnerRevoked = { owner ->
                if (owner != ReadReceiptOwner.Legacy) processReadStateV1Canary.revoke(owner.toReadStateV1ProductionOwner())
            },
            onFailure = { request, result ->
                activityUnreadSummaryStore.invalidate(
                    request.owner.toProcessActivityUnreadOwner(),
                    reason = "read_failure:${request.revision}"
                )
                homeStores.entries().forEach { (_, store) ->
                    store.dispatch(SharedHomeIntent.ApplyReadReceiptFailure(request))
                }
                homeActivityStores.entries().forEach { (_, store) ->
                    store.dispatch(SharedActivityIntent.ApplyReadReceiptFailure(request))
                }
                sharedWarnLogger("SlockReadReceipt", "process_read_receipt")(
                    "failure owner=${request.owner.serverId}:${request.owner.accountId} " +
                        "channelId=${request.channelId.value} revision=${request.revision} " +
                        "result=${result::class.simpleName.orEmpty()}"
                )
            }
        )
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
        } else if (owner != ReadReceiptOwner.Legacy) {
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
        // A queued invalidation can resume after a new login. In that case the
        // process session is authoritative and the stale logout must not clear
        // the newly reconciled shell selections.
        if (authStore.readSession() != null) return
        selectedServerStores.clearActiveServerSelections()
        // Close the remaining check/clear race: if login completed while the
        // fan-out was clearing stores, immediately reconcile every live shell
        // from the new process session.
        authStore.readSession()?.let { restoredSession ->
            selectedServerStores.entries().forEach { (_, selectionStore) ->
                selectionStore.reconcile(restoredSession)
            }
        }
    }

    fun releaseShellStoreScope(scope: SlockShellStoreScope) {
        if (scope == SlockShellStoreScope.Legacy) return
        realtimeAuthorityRegistrations.remove(scope)?.let { registrationId ->
            threadSendScope.launch {
                realtimeAuthorityProvider.releaseScope(scope.shellInstanceId, registrationId)
            }
        }
        selectedServerStores.get(scope)?.dispose()
        homeStores.remove(scope)?.clear()
        homeActivityStores.remove(scope)?.clear()
        homeStoreScopes.remove(scope)?.cancel()
        homeActivityStoreScopes.remove(scope)?.cancel()
        shellNetworkClients.remove(scope)
        shellAuthStores.remove(scope)
        shellServerMembersStores.remove(scope)
        shellRealtimeDomainEventOwners.remove(scope)
        shellAgentDetailStores.remove(scope)
        shellNavigationCoordinators.remove(scope)?.clear()
        shellForegroundBannerStores.remove(scope)?.clear()
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
            eventBus: SlockEventBus,
            source: String,
            bridge: BridgeModule,
            canonicalFinalIntegrationFactory: SlockSharedCanonicalFinalIntegrationFactory? = null,
        ): SlockSharedStoreProvider {
            val eventBusRelay = SlockEventBusRelay(eventBus)
            val database = createSlockSqlDatabase(bridge)
            val relationalStore = SqlDelightRelationalStore(database)
            val localDataStore =
                RelationalComposeLocalDataStore(
                    store = relationalStore,
                    diagnosticsLogger = sharedInfoLogger("SlockLocalCache", "local_cache")
                )
            val authSessionEvents =
                KuiklyAuthSessionEvents(
                    eventBus = eventBusRelay,
                    source = source
                )
            val authKeyValueStoreBinding = SlockSharedAuthSessionKeyValueStoreProvider.create()
            val authKeyValueStore = authKeyValueStoreBinding.store
            bridge.logSharedAuthDiagnostic(
                "shared_auth_session",
                "auth_storage_scope scope=${authKeyValueStoreBinding.scope}"
            )
            val authStore =
                KeyValueAuthSessionStore(
                    store = authKeyValueStore,
                    diagnosticsLogger = { message ->
                        bridge.logSharedAuthDiagnostic("shared_auth_session", message)
                    },
                    criticalDiagnosticsLogger = { message ->
                        bridge.logSharedAuthDiagnostic(
                            "shared_auth_session",
                            message,
                            SlockPlatformLogHint.AuthCritical
                        )
                    }
                )
            val sharedPreferenceStoreBinding =
                SlockSharedKeyValueStoreProvider.create(
                    namespace = SlockLocalPreferenceStore.KuiklyNamespace,
                    storageName = "shared preference"
                )
            val sharedPreferenceStore = sharedPreferenceStoreBinding.store
            bridge.logSharedAuthDiagnostic(
                "shared_key_value",
                "storage_scope name=sharedPreference " +
                    "namespace=${SlockLocalPreferenceStore.KuiklyNamespace} " +
                    "scope=${sharedPreferenceStoreBinding.scope}"
            )
            val networkTransportController =
                SlockNetworkTransportController(
                    KeyValueNetworkTransportPreferenceStore(sharedPreferenceStore)
                )
            installSlockNetworkTransportController(networkTransportController)
            networkTransportController.getPreferenceSync().let { snapshot ->
                bridge.logSharedAuthDiagnostic(
                    "shared_network_transport",
                    "requestedHttp3=${snapshot.requestedHttp3Enabled} " +
                        "effectiveHttp3=${snapshot.effectiveHttp3Enabled} " +
                        "curlRequested=${snapshot.curlRequestedForNewCalls} " +
                        "availability=${snapshot.availability} generation=${snapshot.generation}"
                )
            }
            val apiEnvironmentKeyValueStoreBinding =
                SlockSharedKeyValueStoreProvider.create(
                    namespace = SharedApiEnvironmentStore.Namespace,
                    storageName = "API environment"
                )
            val apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStoreBinding.store
            bridge.logSharedAuthDiagnostic(
                "shared_key_value",
                "storage_scope name=apiEnvironment " +
                    "namespace=${SharedApiEnvironmentStore.Namespace} " +
                    "scope=${apiEnvironmentKeyValueStoreBinding.scope}"
            )
            val apiEnvironmentStore =
                SharedApiEnvironmentStore(
                    apiEnvironmentKeyValueStore
                )
            val tasksDataStore =
                SharedTasksDataStore(
                    ownerIdentityProvider = {
                        authStore.readSession()?.let { session ->
                            "${apiEnvironmentStore.current().apiBaseUrl.trimEnd('/')}|${session.user.id}"
                        }
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
            val platformFileResolverBinding = SlockSharedPlatformFileResolverProvider.createBinding()
            val platformFileResolver = platformFileResolverBinding.resolver
            bridge.logSharedAuthDiagnostic(
                "shared_network_file_resolver",
                "storage_scope=${platformFileResolverBinding.scope}"
            )
            val authDataSource =
                createSharedAuthDataSourceForCurrentEnvironment(
                    apiEnvironmentStore = apiEnvironmentStore,
                    authStore = authStore
                )
            val authLossFunnel =
                SharedAuthLossFunnel(
                    restoreSession = { authDataSource.restoreSession() },
                    clearSession = { reason, clearSource ->
                        authStore.clearSession(reason = reason, source = clearSource)
                    },
                    scope = CoroutineScope(SupervisorJob()),
                    diagnosticsLogger = sharedInfoLogger(SHARED_AUTH_LOG_TAG, "auth_loss_funnel")
                )
            val networkWarningLogger = sharedWarnLogger("SlockSharedNetwork", "shared_network")
            val networkRequestScheduler = SlockRequestScheduler()
            val networkExecutor = createPlatformSlockHttpExecutor(networkWarningLogger)
            val terminalAuthLossReporter: suspend (StoredAuthSession, String) -> Unit = { session, caller ->
                authLossFunnel.report(
                    AuthLossSignal(
                        source = caller,
                        tokenFingerprint = session.accessToken.authLossTokenFingerprint()
                    )
                ).await()
            }
            val scopedNetworkClientFactory: (SlockAuthSessionStore) -> SlockNetworkClient = { sessionStore ->
                SharedSlockNetworkClient(
                    sessionStore = sessionStore,
                    baseUrlProvider = { apiEnvironmentStore.current().apiBaseUrl },
                    warningLogger = networkWarningLogger,
                    criticalWarningLogger =
                        sharedWarnLogger(
                            "SlockSharedNetwork",
                            "shared_network",
                            SlockPlatformLogHint.AuthCritical
                        ),
                    terminalAuthLossReporter = terminalAuthLossReporter,
                    requestScheduler = networkRequestScheduler,
                    platformFileResolver = platformFileResolver,
                    executor = networkExecutor
                )
            }
            val ownerScopedNetworkClientFactory: (SlockAuthSessionStore, String) -> SlockNetworkClient =
                { sessionStore, ownerApiBaseUrl ->
                    SharedSlockNetworkClient(
                        sessionStore = sessionStore,
                        baseUrlProvider = { ownerApiBaseUrl },
                        warningLogger = networkWarningLogger,
                        criticalWarningLogger =
                            sharedWarnLogger(
                                "SlockSharedNetwork",
                                "shared_network",
                                SlockPlatformLogHint.AuthCritical
                            ),
                        terminalAuthLossReporter = terminalAuthLossReporter,
                        requestScheduler = networkRequestScheduler,
                        platformFileResolver = platformFileResolver,
                        executor = networkExecutor
                    )
                }
            val networkClient = scopedNetworkClientFactory(authStore)
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
                    SlockSharedKeyValueStoreProvider.create(
                        namespace = SLOCK_ACTIVITY_UNREAD_MMKV_NAMESPACE,
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
            CoroutineScope(SupervisorJob()).launch {
                authStore.authSessionEvents.collect { event ->
                    when (event) {
                        is AuthSessionEvent.Authenticated ->
                            {
                                processServerMembersCore.invalidateAll(reason = "authenticated")
                                activityUnreadSummaryStore.invalidateAll(reason = "authenticated")
                            }
                        is AuthSessionEvent.Cleared ->
                            {
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
                    api = SlockFeatureFlagsNetworkApi(networkClient),
                    diagnosticsLogger = sharedInfoLogger("SlockFeatureFlags", "feature_flags"),
                    onEvaluation = { serverId, key, enabled, reason, environmentId, evaluationGeneration ->
                        if (key == ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY && environmentId != null) {
                            SlockNetworkKmmRecoveryRemoteFlagBridge
                                .apply(
                                    environmentId = environmentId,
                                    evaluationGeneration = evaluationGeneration,
                                    serverId = serverId.value,
                                    enabled = enabled,
                                    reason = reason,
                                )
                                ?.let(::logNetworkKmmRemoteConfig)
                        }
                    },
                )
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
            val canonicalMessageV2GenerationEngine =
                CanonicalMessageV2GenerationEngine(
                    coordinator = CanonicalMessageV2BaselineCoordinator(),
                    store = SqlDelightCanonicalMessageV2Store(database, now = ::defaultNowMillis),
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
                    logger = sharedInfoLogger("CanonicalMessageV2Canary", "canonical_v2_canary"),
                )
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
                        SlockSharedCanonicalFinalIntegrationFactoryScope(
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
                        ),
                )
            // Pixel-avatar runtime table (task #352): apply the MMKV-cached document
            // synchronously (before first render), then fire-and-forget a refresh from the
            // deployed WEB app origin (webUrl, not the API base URL) — the same canonical
            // pixelAvatars.json the generator consumes. Bad data never crashes rendering;
            // the generated bundled table stays the fallback.
            val pixelAvatarStore = KeyValuePixelAvatarStore(sharedPreferenceStore)
            SlockPixelAvatarRuntime.applyRuntimeJson(pixelAvatarStore.readJson(), source = "cache")
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
                    }
                )
            lateinit var provider: SlockSharedStoreProvider
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
            provider = SlockSharedStoreProvider(
                sqlDatabase = database,
                authKeyValueStore = authKeyValueStore,
                sharedPreferenceStore = sharedPreferenceStore,
                apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStore,
                authStore = authStore,
                authSessionEvents = authSessionEvents,
                authDataSource = authDataSource,
                authLossFunnel = authLossFunnel,
                networkClient = networkClient,
                networkTransportPreferenceRepository = networkTransportController,
                scopedNetworkClientFactory = scopedNetworkClientFactory,
                ownerScopedNetworkClientFactory = ownerScopedNetworkClientFactory,
                serverFeatureFlags = serverFeatureFlags,
                currentGrantMessageShadowCommitLane = currentGrantMessageShadowCommitLane,
                canonicalMessageV2Canary = canonicalMessageV2Canary,
                localDataStore = localDataStore,
                agentDetailStore = SharedAgentDetailStore(
                    logger = sharedInfoLogger("SlockAgentDetail", "shared_agent_detail_store")
                ),
                agentPanelTabOrderStore = SharedAgentPanelTabOrderStore(),
                tasksDataStore = tasksDataStore,
                serverMembersStore = serverMembersStore,
                processServerMembersCore = processServerMembersCore,
                processMemberPresenceRegistry = processMemberPresenceRegistry,
                activityUnreadSummaryStore = activityUnreadSummaryStore,
                presenceOwnerResolver = resolvePresenceOwner,
                presenceOwnerFlowResolver = resolvePresenceOwnerFlow,
                presenceWriteResolver = resolvePresenceWrite,
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
                        api = NetworkSlockMessageTranslationApi(networkClient),
                        scope = CoroutineScope(SupervisorJob()),
                        translationMode = {
                            KeyValueChatLayoutPreferenceStore(sharedPreferenceStore).readTranslationModeSync()
                        },
                        translationTargetLanguage = {
                            KeyValueChatLayoutPreferenceStore(sharedPreferenceStore).readTranslationTargetLanguageSync()
                        },
                        fallbackLanguage = {
                            KeyValueLocalePreferenceStore(sharedPreferenceStore).readLocaleSync().languageTag
                        },
                        diagnosticsLogger = sharedInfoLogger("SlockMessageTranslation", "shared_message_translation")
                    ),
                threadMessageStore = SharedThreadMessageStore(),
                threadPayloadStore = SharedThreadPayloadStore(CoroutineScope(SupervisorJob())),
                foregroundReconciler = foregroundReconciler,
                homeStoreScope = CoroutineScope(SupervisorJob()),
                homeActivityStoreScope = CoroutineScope(SupervisorJob()),
                threadSendScope = threadSendScope
            )
            return provider.also {
                val pushLifecycleScope = CoroutineScope(SupervisorJob())
                SlockPushRegistrationLifecycle.observeSessionEvents(
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

internal fun createSharedAuthDataSourceForCurrentEnvironment(
    apiEnvironmentStore: SharedApiEnvironmentStore,
    authStore: SlockAuthSessionStore,
    transportFactory: (() -> String) -> SlockAuthTransport = { baseUrlProvider ->
        SharedSlockAuthTransport(
            baseUrlProvider = baseUrlProvider,
            warningLogger = { message ->
                slockLogWarn(SHARED_AUTH_LOG_TAG, "shared_auth_network $message")
            }
        )
    },
    diagnosticsLogger: (String) -> Unit = sharedInfoLogger(SHARED_AUTH_LOG_TAG, "shared_auth"),
    criticalDiagnosticsLogger: (String) -> Unit =
        sharedInfoLogger(
            SHARED_AUTH_LOG_TAG,
            "shared_auth",
            SlockPlatformLogHint.AuthCritical
        )
): SharedAuthDataSource =
    SharedAuthDataSource(
        transport = transportFactory { apiEnvironmentStore.current().apiBaseUrl },
        sessionStore = authStore,
        diagnosticsLogger = diagnosticsLogger,
        criticalDiagnosticsLogger = criticalDiagnosticsLogger
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
    val applySettings: (String, RealtimeEvent) -> Unit = { _, _ -> },
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
    val applySettings: suspend (String, RealtimeEvent) -> Unit = { _, _ -> },
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

    // Flag-gated `sync_core_messages_v0` shadow fold+persist lane. Null until the provider installs it (see
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
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.Selected,
            grant,
            event,
            source,
        ) { serverId, acceptedEvent, acceptedSource ->
            // The admission identity is frozen from the grant this event was
            // accepted under — the only entry allowed to write presence.
            acceptGuarded(serverId, acceptedEvent, acceptedSource, grant.presenceAdmission())
        }

    suspend fun acceptHomeUnreadProjection(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
    ): Boolean =
        runGuardedCanonicalShadowFirst(
            RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
            grant,
            event,
            source,
        ) { serverId, acceptedEvent, acceptedSource ->
            acceptGuardedHomeUnreadProjection(serverId, acceptedEvent, acceptedSource)
        }

    suspend fun acceptProcessMutation(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
    ): Boolean =
        runIfCurrentGrant(grant) {
            // Process transport grants do not own shell canonical-final
            // registrations. Mutate process-global cursor/tasks/presence once
            // here; each shell then runs its unchanged canonical/projection
            // path with its own shell-scoped grant.
            acceptGuarded(grant.serverId, event, source, grant.presenceAdmission())
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
        when (canonicalMessageV2OwnerGateway?.accept(grant, foldInput)) {
            CanonicalMessageV2CanaryOutcome.Committed ->
                return runIfCurrentGrant(grant) {
                    legacyContinuation(grant.serverId, event, source)
                }
            is CanonicalMessageV2CanaryOutcome.Held -> return true
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
        // `sync_core_messages_v0` SHADOW partition gate, BEFORE the sink fan-out and invisible to it. This
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
            acceptedEvent.patchSharedTaskProjection(resolvedServerId)
            activeSinks?.applyHomeShell?.invoke(resolvedServerId, acceptedEvent)
            activeSinks?.applySettings?.invoke(resolvedServerId, acceptedEvent)
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
        activeSinks?.applySettings?.invoke(resolvedServerId, event)
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

    private fun RealtimeEvent.patchSharedTaskProjection(serverId: String) {
        val store = tasksDataStore ?: return
        when (this) {
            is TaskCreated -> tasks.forEach { task -> store.upsertTask(serverId, task) }
            is TaskUpdated -> store.upsertTask(serverId, task)
            is TaskDeleted -> store.removeTask(serverId, taskId)
            else -> Unit
        }
    }
}

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
            "thread:updated:$serverId:${threadChannelId.value}:${parentMessageId.value}:$replyCount:${unreadCount ?: -1}:${firstUnreadMessageId?.value.orEmpty()}:${lastReplyAt.orEmpty()}"
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
            serverSeq?.let { "member:presence:$serverId:${memberId.value}:$it" }
                ?: "member:presence:$serverId:${memberId.value}:${presence.name}:${agentStatus?.name.orEmpty()}:${statusLabel.orEmpty()}"
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
    "${id.value}:${status.name}:${updatedAtLabel}:${assignee?.id?.value.orEmpty()}"

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

internal object SlockSharedStoreRegistry {
    private val productionFactory =
        SlockSharedStoreProviderFactory { request ->
            SlockSharedStoreProvider.create(
                eventBus = request.eventBus,
                source = request.source,
                bridge = request.bridge,
            )
        }
    private val access =
        SlockSharedStoreRegistryAccess<SlockSharedStoreProvider, SlockSharedStoreProviderRequest>(
            defaultFactory = productionFactory::create,
            updateExisting = { provider, request ->
                provider.updateEventBus(request.eventBus, request.source)
            },
        )

    fun currentOrNull(): SlockSharedStoreProvider? = access.currentOrNull()

    fun bindFactoryBeforeFirstGet(
        factory: SlockSharedStoreProviderFactory,
    ): SlockSharedStoreFactoryBindingHandle =
        access.bindFactoryBeforeFirstGet(factory::create)

    fun unbindFactoryBeforeFirstGet(handle: SlockSharedStoreFactoryBindingHandle): Boolean =
        access.unbindFactoryBeforeFirstGet(handle)

    fun get(bridge: BridgeModule): SlockSharedStoreProvider {
        val eventBus = SlockEventBuses.appScope()
        return get(eventBus, "shared-store-provider", bridge)
    }

    fun get(eventBus: SlockEventBus, source: String, bridge: BridgeModule): SlockSharedStoreProvider =
        access.get(
            SlockSharedStoreProviderRequest(
                eventBus = eventBus,
                source = source,
                bridge = bridge,
            ),
        )
}
