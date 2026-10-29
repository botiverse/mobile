package build.raft.app.data

import build.raft.app.resources.raftSystemPreferredLanguageTagsOrEmpty
import build.raft.app.toJointConnectionsDurableRecord
import build.raft.app.toJointConnectionsSnapshot
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
import build.raft.app.host.HostCapabilities
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
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
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
    /** Durable server rosters shared by every members store (data-layer plan step 3). */
    private val serverMembersDurableStore: RaftServerMembersDurableStore,
    private val apiEnvironmentKeyValueStore: RaftKeyValueStore,
    val authStore: KeyValueAuthSessionStore,
    val authSessionEvents: RaftAuthSessionEvents,
    val authDataSource: SharedAuthDataSource,
    val authLossFunnel: SharedAuthLossFunnel,
    val authRuntimeCapabilityStore: SharedAuthRuntimeCapabilityStore,
    val networkClient: RaftNetworkClient,
    private val networkReachabilityGates: RaftHostReachabilityGates,
    private val scopedNetworkClientFactory: (RaftAuthSessionStore) -> RaftNetworkClient,
    private val ownerScopedNetworkClientFactory: (RaftAuthSessionStore, String) -> RaftNetworkClient,
    val serverFeatureFlags: SharedServerFeatureFlags,
    private val canonicalMessageV2Canary: CanonicalMessageV2ProductionCanary,
    private val canonicalMessageV2GenerationEngine: CanonicalMessageV2GenerationEngine,
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
    /**
     * Offline-first resources (persistence audit item 4): every resource's durable rows, wiped once on
     * auth-terminal cleanup and per server on lost access. New resources need no cleanup wiring.
     */
    internal val offlineResourceCache: RaftOfflineResourceCache,
    /** Channel / thread member lists offline (rows only, never permission flags). */
    private val channelMembersOffline: SharedChannelMembersOffline,
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
) {
    // task #166: display-only last-known unread counts and previews for the cached cold-start
    // Home. Declared first so every later initializer that builds the Home store sees it.
    private val homeRowHintPersistStorage = HomeRowHintPersistStorage(sharedPreferenceStore)
    /** Last-known workspace notification preference per (user, server); kept on sign-out, dropped with the server. */
    val settingsServerNotificationLastKnownStore = SettingsServerNotificationLastKnownStore(sharedPreferenceStore)
    private val searchPersonalizationRepository = PersistentSearchPersonalizationRepository(sharedPreferenceStore)
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
    private val shellRuntimeAccountUsageStores = RaftShellScopedStoreCache<SharedRuntimeAccountUsageStore>()

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
        )
    }
    val appLinkPreviewStore: SharedAppLinkPreviewStore by appLinkPreviewStoreLazy

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
        )
    }
    val artifactSummaryStore: SharedArtifactSummaryStore by artifactSummaryStoreLazy

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

    /** Realtime `agent:deleted` for the current account: its cached detail page (memory and disk) goes. */
    internal suspend fun evictAgentDetail(serverId: String, agentId: String) {
        val (environmentId, accountId) = currentAccountBindingOrNull() ?: return
        val owner = sharedAgentDetailOwner(environmentId, accountId, ServerId(serverId)) ?: return
        agentDetailCache.evict(owner.agentDetailCacheKey(MemberId(agentId)))
    }

    /** The account's local data on sign-out and on server-access loss (see the class). */
    private val accountLocalData =
        AccountLocalData(
            homeSavedStateCache = homeSavedStateCache,
            offlineResourceCache = offlineResourceCache,
            homeRowHints = homeRowHintPersistStorage,
            activityFirstPages = SharedActivityPersistStorage(sharedPreferenceStore),
            notificationPreferences = settingsServerNotificationLastKnownStore,
            messageEngine = canonicalMessageV2GenerationEngine,
            memberAvatarRefStore = memberAvatarRefStore,
            jointConnectionsCache = { jointConnectionsCache },
            agentDetailCache = agentDetailCache,
            replyWindowCache = threadDurableLastKnownReader.replyWindowCache,
            threadPayloadStore = threadPayloadStore,
            clearViewerMemoryCaches = {
                // App link previews and consent answers, and Artifact summaries: per viewer, session only.
                if (appLinkPreviewStoreLazy.isInitialized()) appLinkPreviewStore.clearAll()
                if (artifactSummaryStoreLazy.isInitialized()) artifactSummaryStore.clearAll()
                // The Artifact viewer's headers and its kept WebView (Tenny's cache rule 1).
                build.raft.app.RaftArtifactViewerMemory.clear()
            },
            logger = sharedInfoLogger("CanonicalMessageV2Store", "canonical_v2_access_evict"),
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

    private fun allComputersStores(): List<SharedComputersStore> =
        shellComputersStores.entries().map { (_, store) -> store }.distinct()
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
                // A RE-connect refetched only agents: a failed pull gets the full roster refetch back.
                onFailed = fallback,
            )
        if (!requested) fallback?.invoke()
    }

    /**
     * Incremental sync on: machine online status (`machine:status`) is not in the change log
     * (Scout 10-27), so a reconnect (after the jitter) and a foreground return read the machine
     * list again: every Computers list open for the server, and Home's machine notifications
     * (offline-computer alerts; read only for users who manage machines). Home reloads reuse what
     * this read ([SharedHomeDataSource.loadNotificationsAfterHomeLoad]).
     */
    internal suspend fun refreshMachinesForSync(serverId: String, reason: String) {
        val stores = syncComputersStores(serverId)
        stores.forEach { store -> runCatchingPreservingCancellation { store.reloadMachinesForSync(serverId) } }
        refreshHomeMachineNotifications(serverId, reason)
        sharedInfoLogger("SlockSync", "sync_changes")(
            "sync_machines_refresh serverId=$serverId computersStores=${stores.size} reason=$reason"
        )
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

    private val syncChangesEngine: RaftSyncChangesEngine by lazy {
        RaftSyncChangesEngine(
            api = RaftSyncChangesNetworkApi(networkClient),
            cursors = syncCursorStore,
            sink =
                RaftSyncChangesStoreRouter(
                    targets = syncStoreTargets,
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

    /** Whether [requestSyncChangesPull] would pull [serverId] now (signed in, selected, flag on). */
    internal fun syncChangesPullAllowed(serverId: String): Boolean {
        val scope = currentSyncScope(serverId) ?: return false
        return scopesForServer(scope.serverId).isNotEmpty() && syncChangesEnabled(scope)
    }

    private val syncPullJobsLock = SynchronizedObject()
    private val syncPullJobs = mutableMapOf<String, MutableSet<Job>>()

    /**
     * Server switch (Tenny 10-27): only the selected server pulls. A pull of a server no shell
     * selects any more is cancelled (its cursor is only written after a page is applied, so the
     * next pull of that server repeats at most one page).
     */
    private fun cancelUnselectedSyncPulls() {
        val cancelled =
            synchronized(syncPullJobsLock) {
                syncPullJobs.filterKeys { scopesForServer(it).isEmpty() }.values.flatMap { it.toList() }
            }
        cancelled.forEach { it.cancel() }
    }

    private fun currentSyncScope(serverId: String): RaftSyncScope? {
        val context = replyCacheAuthContext.value ?: return null
        return RaftSyncScope.of(context.environmentId, context.accountId, serverId)
    }

    private fun syncChangesEnabled(scope: RaftSyncScope): Boolean {
        val serverEffective =
            serverFeatureFlags.isEnabled(ServerId(scope.serverId), MOBILE_SYNC_CHANGES_FEATURE_FLAG_KEY)
        return ProcessDiagnosticFeatureFlagOverrides.registry
            .decide(
                key = MOBILE_SYNC_CHANGES_FEATURE_FLAG_KEY,
                serverEffective = serverEffective,
                scope = DiagnosticFeatureFlagScope(scope.environmentId, scope.accountId, scope.serverId),
            ).finalEffective
    }

    /**
     * Pulls [serverId]'s changes since its cursor ([RaftSyncChangesEngine]) when it is a server a shell
     * has selected (other servers keep their summary fetch) and `mobile_sync_changes_v0` is on.
     * Triggers: socket (re)connect, foreground return, server select. False when skipped.
     *
     * [onFailed] runs when the pull failed or timed out ([RaftSyncPullResult.Failed.needsFullRefetchFallback]):
     * the caller's full-list refetch it stood in for (web client, Scout 10-27).
     */
    internal fun requestSyncChangesPull(
        serverId: String,
        trigger: String,
        jitterSnapshots: Boolean = false,
        jitterPull: Boolean = false,
        onFailed: (() -> Unit)? = null,
    ): Boolean {
        val scope = currentSyncScope(serverId) ?: return false
        if (scopesForServer(scope.serverId).isEmpty()) return false
        if (!syncChangesEnabled(scope)) return false
        val job =
            threadSendScope.launch(start = CoroutineStart.LAZY) {
                when (val result = syncChangesEngine.pull(scope, trigger, jitterSnapshots, jitterPull)) {
                    is RaftSyncPullResult.Failed -> if (result.needsFullRefetchFallback) onFailed?.invoke()
                    // Stopped at the page cap with more waiting: go on in a follow-up pull.
                    is RaftSyncPullResult.Synced ->
                        if (result.truncated) requestSyncChangesPull(scope.serverId, trigger = "truncated_follow_up")
                }
            }
        synchronized(syncPullJobsLock) { syncPullJobs.getOrPut(scope.serverId) { mutableSetOf() } += job }
        job.invokeOnCompletion { synchronized(syncPullJobsLock) { syncPullJobs[scope.serverId]?.remove(job) } }
        job.start()
        return true
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

            override suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String) {
                syncServerMembersStores(serverId).forEach { store ->
                    store.refreshLoadedChannelMembers(ServerId(serverId), channelIds, reason)
                }
            }

            override suspend fun reloadRoster(serverId: String, reason: String): Boolean {
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
                return results.any { it is DataResult.Content || it is DataResult.Empty }
            }

            override suspend fun reloadMachines(serverId: String, reason: String): Boolean {
                val stores = syncComputersStores(serverId)
                // No Computers store open for the server: nothing in memory to refresh, its first load reads the server.
                if (stores.isEmpty()) return true
                return stores
                    .map { store -> store.reloadMachinesForSync(serverId) }
                    .any { it is DataResult.Content || it is DataResult.Empty }
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

    private val presenceTransportRosterRefetch: PresenceTransportRosterRefetch by lazy {
        PresenceTransportRosterRefetch(
            jitter = reconnectRefetchJitter,
            // A force caller otherwise joins any already-active pre-connect roster flight. Retire
            // that flight/cache once, before every shell joins the new current-transport request.
            invalidate = { owner, reason -> processServerMembersCore.invalidate(owner = owner, reason = reason) },
            storesForServer = { serverId ->
                val activeScopes = scopesForServer(serverId).toSet()
                buildList {
                    add(serverMembersStore)
                    shellServerMembersStores.entries().forEach { (scope, store) ->
                        if (scope in activeScopes) add(store)
                    }
                }.distinct()
            },
            isCurrentOwner = { owner -> replyCacheAuthContext.value?.ownerFor(owner.serverId) == owner },
            refreshMachines = { serverId -> refreshMachinesForSync(serverId, reason = PresenceTransportRosterRefetch.REASON) },
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
            replyCacheAuthContext.collect { context ->
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
                    allServerMembersStores().forEach { store ->
                        store.retireAccountData(
                            successorEnvironmentId = context?.environmentId,
                            successorAccountId = context?.accountId,
                            successorWriteGeneration = context?.writeGeneration
                        )
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

    /** The current API environment's id: the feature-flag evaluation context. */
    private fun updateNetworkKmmRecoveryEnvironment(): String {
        val environment = apiEnvironmentStore.current()
        return raftNetworkKmmRecoveryEnvironmentId(
            environmentKey = environment.key,
            apiBaseUrl = environment.apiBaseUrl
        )
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
     * One ingest for every owner (message storage Phase 3a): its ordered worker and duplicate-delivery memory span
     * the process and shell entries. Declared before `init`, which installs the gateway that reads it.
     */
    private val canonicalMessageV2ResumeIngest: CanonicalMessageV2ResumeIngest by lazy {
        CanonicalMessageV2ResumeIngest(
            tracker = canonicalMessageV2Canary,
            generationEngine = canonicalMessageV2GenerationEngine,
            isEnabledForServer = { serverId ->
                serverFeatureFlags.isEnabled(ServerId(serverId), SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)
            },
            // Lines already start with `canonical_v2_resume`.
            logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2Resume"),
            dispatcher = RaftMessageDbDispatcher,
        )
    }

    private val canonicalMessageV2RealtimeIngest: CanonicalMessageV2RealtimeIngest by lazy {
        CanonicalMessageV2RealtimeIngest(
            tracker = canonicalMessageV2Canary,
            generationEngine = canonicalMessageV2GenerationEngine,
            isEnabledForServer = { serverId ->
                serverFeatureFlags.isEnabled(ServerId(serverId), SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)
            },
            // Lines already start with `canonical_v2_realtime`.
            logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2Realtime"),
        )
    }

    init {
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
                    accountLocalData.onSignOut()
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
        installServerMembersBindingFollower()
        // Incremental sync: a foreground return (app lifecycle, not page visibility) pulls the
        // selected server's changes once (flag off: the shell's lifecycle path only, unchanged).
        foregroundReconciler.followAppLifecycle(
            appForeground = RaftStaleReadRecoverySignals.foreground,
            coversServer = ::syncChangesPullAllowed,
        )
        // A theme changed on another device while backgrounded: read the selected server's again.
        serverAppearanceCatchUp.followAppLifecycle(RaftStaleReadRecoverySignals.foreground) {
            authStore.readSession()?.currentServerId
        }
        // App start: read this environment's client config (and again on each environment change).
        clientConfigStore.start()
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
                threadPayloadStore.dropServer(grant.serverId)
                // Step 3: the server's members (memory in every shell, durable row, avatar refs).
                allServerMembersStores().forEach { store -> store.dropServer(ServerId(grant.serverId)) }
                // Step 4: the server's conversation identities (names, avatars, DM peers) in every shell.
                shellConversationIdentityHubs.entries().forEach { (_, hub) -> hub.dropServer(grant.serverId) }
                // Step 5: machines, joint connections and agent details of that server.
                val lostAccountId = replyCacheAuthContext.value?.accountId
                allComputersStores().forEach { store -> store.dropServer(grant.serverId) }
                if (allComputersStores().isEmpty()) computersDurableStore.evictServer(grant.serverId, lostAccountId)
                jointConnectionsCache.dropServer(grant.serverId, lostAccountId)
                agentDetailCache.dropServer(grant.serverId, lostAccountId)
                // Tasks lists leave memory before the offline wipe below, so the offline mirror cannot store them again.
                tasksDataStore.dropServer(grant.serverId)
                syncCursorStore.clearServer(grant.serverId, lostAccountId)
                // #45: that server's recent searches and frequent people / channels.
                searchPersonalizationRepository.dropServer(grant.serverId, lostAccountId, nowMillis = defaultNowMillis())
                // Offline resources, Home hints, Activity pages, notification preferences, reply windows
                // and the stored messages of that server (they outlive sign-out, so they must go here).
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
                    // Missed `server:features-changed` notices are not replayed: refetch on every (re)connect.
                    serverFeatureFlagChangeRefresher.onReconnected(ServerId(grant.serverId))
                    // Nor are `appearance:updated` ones: a theme changed elsewhere while offline.
                    serverAppearanceCatchUp.onReconnected(ServerId(grant.serverId))
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
                }
            },
        ).start()
        RaftHostNetworkEvents(eventBus).addAuthoritativeOnlineListener {
            // Task #150: a new default network (e.g. a VPN switched on/off) may resolve the host
            // again; stop short-circuiting requests at once instead of waiting for the next probe.
            networkReachabilityGates.onNetworkAvailable()
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
        SyncCoreMessageShadowFoldLane(
            featureFlags = serverFeatureFlags,
            logger = sharedInfoLogger("SyncCoreT0Shadow", "sync_core_t0_shadow"),
        )

    private fun buildReactionShadowLane(): SyncCoreReactionShadowLane =
        SyncCoreReactionShadowLane(
            featureFlags = serverFeatureFlags,
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
                                reconnectRefetchJitter.onServerSelected(serverId, reason = "server_selected")
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
                                        requestSyncChangesPull(serverId, trigger = "server_selected")
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
                    durableStore = serverMembersDurableStore,
                    channelMembersOffline = channelMembersOffline,
                    actorResolve = jointActorResolve(networkClient(scope)),
                    actorResolveScope = RaftStoreThreading.newScope(),
                    incrementalSyncCoversRoster = { serverId -> syncChangesPullAllowed(serverId.value) }
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

    /**
     * Runtime account usage reads and refreshes for this shell (the computer detail's runtime
     * chips): one cache and one refresh window per shell, whichever page asks.
     */
    internal fun runtimeAccountUsageStore(scope: RaftShellStoreScope): SharedRuntimeAccountUsageStore =
        shellRuntimeAccountUsageStores.getOrPut(scope) {
            SharedRuntimeAccountUsageStore(
                client = RaftComputersApiClient(RaftComputersNetworkApi(networkClient(scope))),
                currentIdentity = { computersOwner(scope) }
            )
        }

    fun computersStore(scope: RaftShellStoreScope): SharedComputersStore =
        shellComputersStores.getOrPut(scope) {
            SharedComputersStore(
                computersApiClient = RaftComputersApiClient(RaftComputersNetworkApi(networkClient(scope))),
                scope = threadSendScope,
                logger = sharedInfoLogger("SlockComputers", "shell_computers"),
                ownerProvider = { computersOwner(scope) },
                durableStore = computersDurableStore
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
                onConversationReadAllAcknowledged = ::publishConversationReadAllAck,
                onChannelLeft = { channelId -> publishChannelAccessLost(scope, channelId) },
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
                    serverMembersStore = serverMembersStore,
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
                onSummaryFact = activityUnreadSummaryStore::submitFact,
                onSummaryInvalidated = activityUnreadSummaryStore::submitInvalidation,
                activityObjectsRepository = NetworkActivityObjectsRepository(networkClient),
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
            onPrediction = { prediction ->
                // The user read it here: its system notification (Android) goes too.
                RaftConversationReadHost.conversationRead(prediction.owner.serverId, prediction.channelId.value)
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
        shellServerMembersStores.remove(scope)?.let { store ->
            store.clearScopedReadCaches()
            store.dispose()
        }
        shellRealtimeDomainEventOwners.remove(scope)
        shellAgentTypingStores.remove(scope)?.close()
        shellAgentDetailStores.remove(scope)
        shellComputersStores.remove(scope)?.dispatch(SharedComputersIntent.Clear)
        shellRuntimeAccountUsageStores.remove(scope)
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
            logSharedAuthDiagnostic(
                "shared_key_value",
                "storage_scope name=sharedPreference " +
                    "namespace=${RaftLocalPreferenceStore.KuiklyNamespace} " +
                    "scope=${sharedPreferenceStoreBinding.scope}"
            )
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
                            key != SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
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
                        val pulled =
                            provider.requestSyncChangesPull(
                                serverId.value,
                                trigger = "foreground",
                                // Pull failed / timed out: the full refresh after all, through the jitter.
                                onFailed = {
                                    provider.scheduleSyncFallbackRefetch(serverId.value, "foreground_fallback", fullRefresh)
                                },
                            )
                        if (pulled) {
                            // Agent presence and machine status are not in the change log (no jitter here).
                            coroutineScope {
                                launch { targetStore.refreshAgents(serverId = serverId, reason = "foreground_reconcile") }
                                launch { provider.refreshMachinesForSync(serverId.value, reason = "foreground_reconcile") }
                            }
                        } else {
                            fullRefresh()
                        }
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
            val storedConversationDetails =
                StoredConversationDetails(offlineResourceCache.resource(ConversationDetailResourceSpec))
            provider = RaftSharedStoreProvider(
                host = host,
                sqlDatabase = database,
                authKeyValueStore = authKeyValueStore,
                sharedPreferenceStore = sharedPreferenceStore,
                memberAvatarRefStore = memberAvatarRefStore,
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
                        storage = SharedActivityPersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = sharedInfoLogger("SlockActivityPersist", "shared_activity_persist"),
                        failureLogger = sharedErrorLogger("SlockActivityPersist", "shared_activity_persist")
                    ),
                homeSavedStateCache = SharedHomeSavedStateCache(offlineResourceCache.resource(SavedListResourceSpec)),
                offlineResourceCache = offlineResourceCache,
                channelMembersOffline = channelMembersOffline,
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
    /**
     * Realtime `agent:deleted` on a server of the current grant: the agent's cached detail page (memory and
     * its stored offline copy) goes, so a cold / offline open never shows a deleted agent.
     */
    private val onAgentDeleted: (suspend (serverId: String, agentId: String) -> Unit)? = null,
    /**
     * Realtime `channel:members-updated` on a server of the current grant (offline channel member lists), with
     * the admission identity frozen from that grant; null on the canonical-final surface delivery (no grant).
     */
    private val onChannelMembersUpdated: (suspend (serverId: String, channelId: String?, admission: RealtimePresenceAdmission?) -> Unit)? = null,
    private val cursorStore: RealtimeAppliedSeqCursorStore? = null,
    private val runIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean = { _, _ -> false },
    private val runWithCurrentMessageGrant:
        (suspend (RealtimeGrantRecord, suspend (CurrentMessageCommitContext) -> Unit) -> Boolean)? = null,
    private val canonicalFinalOwnerGateway: RealtimeCanonicalFinalOwnerGateway? = null,
    /** Phase 3a fail-open bound on how long display waits for the durable V2 commit. */
    private val canonicalMessageV2CommitTimeoutMs: Long = CANONICAL_MESSAGE_V2_REALTIME_COMMIT_TIMEOUT_MS,
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
    // T4 canonical-final commit step. Production installs none (the canonical-final gateway is unbound), so a
    // prepared frame falls back to the legacy path; Phase 4a binds the V2 store here.
    private var canonicalFinalCommitStep: CanonicalFinalPreparedCommitStep? = null
    private var reactionShadowLane: SyncCoreReactionShadowLane? = null
    private var canonicalMessageV2OwnerGateway: CanonicalMessageV2OwnerGateway? = null
    private var canonicalMessageV2ResumeGateway: RealtimeResumeCanonicalMessageV2Gateway? = null

    fun installMessageShadowFoldLane(lane: SyncCoreMessageShadowFoldLane) {
        messageShadowFoldLane = lane
    }

    fun installCanonicalFinalCommitStep(step: CanonicalFinalPreparedCommitStep) {
        canonicalFinalCommitStep = step
    }

    fun installReactionShadowLane(lane: SyncCoreReactionShadowLane) {
        reactionShadowLane = lane
    }

    fun installCanonicalMessageV2OwnerGateway(gateway: CanonicalMessageV2OwnerGateway) {
        canonicalMessageV2OwnerGateway = gateway
    }

    /** Message storage Phase 3b: stores a `sync:resume:response`'s messages before its applied seq is saved. */
    fun installCanonicalMessageV2ResumeGateway(gateway: RealtimeResumeCanonicalMessageV2Gateway) {
        canonicalMessageV2ResumeGateway = gateway
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

    // `appearance:updated` writes the member's theme onto the stored server of the account the
    // event's grant belongs to (never the live session's account). Process entry only: it runs
    // once per accepted frame, under the current grant, whether or not a shell is mounted.
    private var serverAppearanceSink: (suspend (principalId: String, event: ServerAppearanceUpdated) -> Unit)? = null

    fun installServerAppearanceSink(sink: suspend (principalId: String, event: ServerAppearanceUpdated) -> Unit) {
        serverAppearanceSink = sink
    }

    // `notification_prefs:updated` with a server mode is emitted to the viewer's own user room. Process entry
    // only, like the appearance sink: it refreshes the viewer's last-known workspace notification preference
    // under the grant's account whether or not a Settings page is mounted.
    private var serverNotificationPreferencesSink:
        (suspend (principalId: String, serverId: String, event: ServerNotificationPreferencesChanged) -> Unit)? = null

    fun installServerNotificationPreferencesSink(
        sink: suspend (principalId: String, serverId: String, event: ServerNotificationPreferencesChanged) -> Unit,
    ) {
        serverNotificationPreferencesSink = sink
    }

    override fun registerSinks(sinks: RealtimeDomainEventSinks) {
        this.sinks = sinks
    }

    override fun unregisterSinks(sinks: RealtimeDomainEventSinks) {
        if (this.sinks === sinks || this.sinks == sinks) {
            this.sinks = null
        }
    }

    /**
     * True while an AppShell's ready content has its guarded Domain / Home-unread sinks registered.
     * Before that, canonical-final AppShell surfaces fail closed (no delivered-ledger entry), so a
     * caller that must observe a delivery waits on this instead of a fixed delay.
     */
    val hasGuardedSinks: Boolean
        get() = guardedSinks != null

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
            // `appearance:updated` is per member, sent to their user room: it names whichever of their
            // servers changed, not the connection's. The sink writes only the grant's account's servers.
            if (acceptedEvent is ServerAppearanceUpdated) {
                serverAppearanceSink?.invoke(grant.principalId, acceptedEvent)
            }
            if (acceptedEvent is ServerNotificationPreferencesChanged && acceptedEvent.serverId.value == serverId) {
                serverNotificationPreferencesSink?.invoke(grant.principalId, serverId, acceptedEvent)
            }
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
        val resumeGateway = canonicalMessageV2ResumeGateway
        if (event is SyncResumeResponse && resumeGateway != null) {
            // Phase 3b: the replayed messages are stored (loose) before the legacy continuation saves the applied seq.
            var commit: (suspend () -> Unit)? = null
            val current = currentMessageRunner(grant) { commit = resumeGateway.captureUnderGrant(grant, event) }
            if (!current) return false
            val failure = commit?.let { runCanonicalMessageV2CommitFailOpen(grant, "resume currentSeq=${event.currentSeq}", it) }
            if (failure == "timeout") {
                // Fail-open: the applied seq is about to move while the detached write still runs.
                val line = "canonical_v2_resume outcome=cursor_ahead_of_write server=${grant.serverId} currentSeq=${event.currentSeq} " +
                    "rows=${event.messages.size}"
                guardedSinks?.log?.invoke(line) ?: sinks?.log?.invoke(line)
            }
            return runIfCurrentGrant(grant) {
                legacyContinuation(grant.serverId, event, source)
            }
        }
        val foldInput = event.canonicalMessageFoldInputOrNull()
        if (foldInput == null) {
            return runIfCurrentGrant(grant) {
                legacyContinuation(grant.serverId, event, source)
            }
        }
        // Message storage Phase 3a: the durable V2 commit is bound to the exact current grant (an in-memory read in
        // the slot) and its disk work runs after the slot released the authority registry, on every route; display
        // follows after a fresh grant check (the T4 gateway's own, or runIfCurrentGrant below).
        if (!commitCanonicalMessageV2AfterGrantCheck(currentMessageRunner, grant, foldInput, transportEpoch)) return false
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
                    legacyContinuation(context.partition.serverId, event, source)
                },
                commitPrepared = { context ->
                    canonicalFinalCommitStep?.commitPrepared(
                        partition = context.partition,
                        legacyKey = legacyKey,
                        foldInput = foldInput,
                    ) ?: PreparedCurrentGrantMessageCommitDisposition.LegacyFallback.DefinitelyPrewrite(
                        "lane_unavailable",
                    )
                },
            )
        }
        return runIfCurrentGrant(grant) {
            legacyContinuation(grant.serverId, event, source)
        }
    }

    /**
     * Phase 3a: binds the V2 commit inside the exact-grant slot ([CanonicalMessageV2OwnerGateway.captureUnderGrant]
     * reads only in-memory state there), then runs it fail-open after the slot. `false` = the grant was not current:
     * nothing is committed and the frame must not be displayed.
     */
    private suspend fun commitCanonicalMessageV2AfterGrantCheck(
        currentMessageRunner: suspend (RealtimeGrantRecord, suspend (CurrentMessageCommitContext) -> Unit) -> Boolean,
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): Boolean {
        var commit: (suspend () -> Unit)? = null
        val current = currentMessageRunner(grant) {
            commit = canonicalMessageV2OwnerGateway?.captureUnderGrant(grant, foldInput, transportEpoch)
        }
        if (!current) return false
        commit?.let { runCanonicalMessageV2CommitFailOpen(grant, "msg=${foldInput.row.id} seq=${foldInput.row.seq}", it) }
        return true
    }

    /**
     * Fail-open: display never waits longer than [canonicalMessageV2CommitTimeoutMs] on, and is never skipped by,
     * the durable write. A throw is logged and swallowed; past the timeout the frame is displayed while the
     * production ingest keeps the commit queued in order (it is never cancelled half-way, so the live coverage
     * tracker's continuity stays exact). Only the caller's own cancellation propagates.
     */
    private suspend fun runCanonicalMessageV2CommitFailOpen(
        grant: RealtimeGrantRecord,
        label: String,
        commit: suspend () -> Unit,
    ): String? {
        val failure = try {
            val completed = withTimeoutOrNull(canonicalMessageV2CommitTimeoutMs) {
                commit()
                true
            }
            if (completed == null) "timeout" else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            "error=${throwable::class.simpleName ?: "unknown"}"
        }
        if (failure != null) {
            val line = "canonical_v2_realtime outcome=display_only server=${grant.serverId} $label reason=$failure"
            guardedSinks?.log?.invoke(line) ?: sinks?.log?.invoke(line)
        }
        return failure
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
        event.deletedAgentIdOrNull()?.let { agentId -> onAgentDeleted?.invoke(resolvedServerId, agentId) }
        event.updatedChannelMembersOrNull()?.let { update ->
            onChannelMembersUpdated?.invoke(resolvedServerId, update.channelId, admission)
        }
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
            is MessageNew -> {
                // Task v2: a reply in a task's own conversation moves its reply count (web
                // socketBridge `applyTaskConversationReply`); lifecycle lines do not count.
                val context = message.conversationContext
                val taskId = context?.taskId
                if (context?.channelKind == ConversationKind.Task && taskId != null && message.messageType != MessageType.System) {
                    store.bumpTaskConversationReplyCount(serverId, taskId, message.id)
                }
            }
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

/** A realtime `channel:members-updated`: [channelId] null when the event names no channel (every channel). */
internal data class RealtimeChannelMembersUpdate(val channelId: String?)

/**
 * A realtime `channel:members-updated`, else null. One without a channel id applies to every channel of the
 * server (as ThreadPage reads it), never dropped.
 */
internal fun RealtimeEvent.updatedChannelMembersOrNull(): RealtimeChannelMembersUpdate? =
    (this as? RealtimeRefreshRequired)
        ?.takeIf { it.scope == RealtimeRefreshScope.ChannelMembers }
        ?.let { event -> RealtimeChannelMembersUpdate(event.channelId?.value?.trim()?.takeIf(String::isNotEmpty)) }

/** The agent of a realtime `agent:deleted`, else null. */
internal fun RealtimeEvent.deletedAgentIdOrNull(): String? =
    (this as? RealtimeRefreshRequired)
        ?.takeIf { it.scope == RealtimeRefreshScope.Agents && it.reason == build.raft.app.data.realtime.RealtimeSocketEvents.AgentDeleted }
        ?.let { event -> event.subjectId?.trim()?.takeIf(String::isNotEmpty) ?: event.memberId?.value?.trim()?.takeIf(String::isNotEmpty) }

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
        // in-flight loads, so bursts stay cheap. The unread store coalesces the
        // two subscribers' copies of one hint per round (mobile #3650); telling a
        // duplicate delivery from a genuinely repeated hint needs a server frame
        // id (#proj-mobile task #23).
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

/** [RaftServerThemeFollower] key of the process-level look (no shell selection). */
private object ProcessServerThemeFollowKey

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
