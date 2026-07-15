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
import build.raft.app.network.SlockNetworkKmmRecoveryRemoteFlagBridge
import build.raft.app.network.slockNetworkKmmRecoveryEnvironmentId
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreMessageShadowObserver
import build.raft.app.network.sync.SyncCoreReactionShadowLane
import kotlin.random.Random
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
import kotlin.coroutines.EmptyCoroutineContext

private const val SHARED_AUTH_LOG_TAG = "SlockSharedAuth"

// Pixel-avatar runtime refresh (task #352) waits out the startup window before
// fetching: first screen must not compete with this non-urgent request, and the
// per-client jitter keeps a web deploy from triggering a synchronized spike.
private const val PixelAvatarRefreshDelayMinMs = 10_000L
private const val PixelAvatarRefreshDelayMaxMs = 60_000L

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
    private val scopedNetworkClientFactory: (SlockAuthSessionStore) -> SlockNetworkClient,
    val localDataStore: ComposeLocalDataStore,
    val agentDetailStore: SharedAgentDetailStore,
    val agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    val tasksDataStore: SharedTasksDataStore,
    val serverMembersStore: SharedServerMembersStore,
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
    private val homeStores = SlockShellScopedStoreCache<SharedHomeStore>()
    private val homeActivityStores = SlockShellScopedStoreCache<SharedActivityStore>()
    private val homeStoreScopes = SlockShellScopedStoreCache<CoroutineScope>()
    private val homeActivityStoreScopes = SlockShellScopedStoreCache<CoroutineScope>()
    private var authSessionClearedSubscription: RealtimeSubscription? = null
    private var authSessionClearLogger: ((String) -> Unit)? = null
    private val activeAppShellPagerOwner = AppShellActivePagerOwner()

    private fun logNetworkKmmRemoteConfig(message: String) {
        if (message.contains("persisted=false")) {
            sharedErrorLogger("SlockNetworkKmm", "networkkmm_remote_config")(message)
        } else {
            sharedInfoLogger("SlockNetworkKmm", "networkkmm_remote_config")(message)
        }
    }

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
    val serverFeatureFlags: SharedServerFeatureFlags =
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
                            reason = reason
                        )
                        ?.let(::logNetworkKmmRemoteConfig)
                }
            },
        )

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

    init {
        updateNetworkKmmRecoveryEnvironment()
        // Attach the flag-gated `sync_core_messages_v0` shadow fold+persist lane to the legacy-scope owner.
        // (The owner is constructed in create() before this instance exists, so serverFeatureFlags can only
        // be wired here.) Shell-scoped owners get their own lane at construction in realtimeDomainEventOwner.
        realtimeDomainEventOwner.installMessageShadowFoldLane(buildMessageShadowFoldLane())
        realtimeDomainEventOwner.installReactionShadowLane(buildReactionShadowLane())
        realtimeAuthorityProvider.start(threadSendScope)
        threadSendScope.launch {
            authStore.sessionInvalidations.collect {
                clearActiveShellServerSelections()
                updateNetworkKmmRecoveryEnvironment()
            }
        }
    }

    /**
     * A fresh [SyncCoreMessageShadowFoldLane] per realtime owner (one owner = one shell/server selection), so
     * each owner's shadow SyncCore state is isolated. Built eagerly but dormant: the owner only invokes it
     * when the `sync_core_messages_v0` flag is on. Uses the provider's own [sqlDatabase] and the shared wall
     * clock ([defaultNowMillis]); the persist runs off the accept thread on a dedicated supervised scope.
     */
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
                // Prefetch registered server feature flags whenever this shell selects a server.
                // StateFlow conflates to distinct values, so this evaluates once per server change.
                // The collector lives on threadSendScope (not the shell scope), so cancel it when the
                // store is disposed, or it would outlive an evicted shell selection store.
                threadSendScope.launch {
                    val prefetching = launch {
                        selectionStore.currentServerId.collectLatest { serverId ->
                            if (!serverId.isNullOrBlank()) {
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
                    logger = sharedInfoLogger("SlockServerMembers")
                )
            }
        }

    fun realtimeDomainEventOwner(scope: SlockShellStoreScope): SharedRealtimeDomainEventOwner =
        if (scope == SlockShellStoreScope.Legacy) {
            realtimeDomainEventOwner
        } else {
            shellRealtimeDomainEventOwners.getOrPut(scope) {
                SharedRealtimeDomainEventOwner(
                    tasksDataStore = tasksDataStore,
                    cursorStore = RealtimeAppliedSeqCursorStore(sharedPreferenceStore),
                    runIfCurrentGrant = realtimeAuthorityProvider::runIfCurrentGrant,
                ).also {
                    it.installMessageShadowFoldLane(buildMessageShadowFoldLane())
                    it.installReactionShadowLane(buildReactionShadowLane())
                }
            }
        }

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
        loadSidebarOrder: suspend () -> DataResult<SettingsServerSidebarOrderSnapshot> = {
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
                    prewarmReplyThread = prewarmReplyThread,
                    onUnreadCountsChanged = onUnreadCountsChanged,
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
                persistSlices = listOf(activityPersistSlice),
                prewarmReplyThread = prewarmReplyThread ?: { DataResult.Empty(EmptyReason.NoMessages) },
                onUnreadCountsChanged = onUnreadCountsChanged ?: {},
                scope =
                    homeActivityStoreScopes.getOrPut(scope) {
                        if (scope == SlockShellStoreScope.Legacy) homeActivityStoreScope else CoroutineScope(SupervisorJob())
                    },
                diagnosticsLogger = diagnosticsLogger ?: {}
            )
        }
    }

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
        fun create(eventBus: SlockEventBus, source: String, bridge: BridgeModule): SlockSharedStoreProvider {
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
                    requestScheduler = networkRequestScheduler,
                    platformFileResolver = platformFileResolver,
                    executor = networkExecutor
                )
            }
            val networkClient = scopedNetworkClientFactory(authStore)
            val threadSendScope = CoroutineScope(SupervisorJob())
            val realtimeAuthorityRegistry =
                RealtimeAuthorityRegistry(
                    authRevisionStore = KeyValueRealtimeAuthRevisionStore(authKeyValueStore),
                    processInstanceId =
                        "mobile-${defaultNowMillis()}-${Random.nextLong()}",
                    diagnosticsLogger = sharedInfoLogger("RealtimeAuthority", "realtime_authority"),
                )
            val realtimeAuthorityProvider =
                RealtimeAuthorityProvider(
                    registry = realtimeAuthorityRegistry,
                    authStore = authStore,
                    authEvents = authStore,
                    diagnosticsLogger = sharedInfoLogger("RealtimeAuthority", "realtime_authority"),
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
                    memberRepository =
                        RealMemberRepository(
                            sessionStore = authStore,
                            networkClient = networkClient,
                            logger = sharedInfoLogger("SlockServerMembers", "shared_server_members_repository")
                        ),
                    logger = sharedInfoLogger("SlockServerMembers")
                )
            return SlockSharedStoreProvider(
                sqlDatabase = database,
                authKeyValueStore = authKeyValueStore,
                sharedPreferenceStore = sharedPreferenceStore,
                apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStore,
                authStore = authStore,
                authSessionEvents = authSessionEvents,
                authDataSource = authDataSource,
                authLossFunnel = authLossFunnel,
                networkClient = networkClient,
                scopedNetworkClientFactory = scopedNetworkClientFactory,
                localDataStore = localDataStore,
                agentDetailStore = SharedAgentDetailStore(
                    logger = sharedInfoLogger("SlockAgentDetail", "shared_agent_detail_store")
                ),
                agentPanelTabOrderStore = SharedAgentPanelTabOrderStore(),
                tasksDataStore = tasksDataStore,
                serverMembersStore = serverMembersStore,
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
                    SharedRealtimeDomainEventOwner(
                        tasksDataStore = tasksDataStore,
                        cursorStore = RealtimeAppliedSeqCursorStore(sharedPreferenceStore),
                        runIfCurrentGrant = realtimeAuthorityProvider::runIfCurrentGrant,
                    ),
                realtimeAuthorityProvider = realtimeAuthorityProvider,
                messageTranslationStore =
                    SharedMessageTranslationStore(
                        authStore = authStore,
                        api = NetworkSlockMessageTranslationApi(networkClient),
                        scope = CoroutineScope(SupervisorJob()),
                        translationMode = {
                            KeyValueChatLayoutPreferenceStore(sharedPreferenceStore).readTranslationModeSync()
                        },
                        fallbackLanguage = {
                            KeyValueLocalePreferenceStore(sharedPreferenceStore).readLocaleSync().languageTag
                        },
                        diagnosticsLogger = sharedInfoLogger("SlockMessageTranslation", "shared_message_translation")
                    ),
                threadMessageStore = SharedThreadMessageStore(),
                threadPayloadStore = SharedThreadPayloadStore(CoroutineScope(SupervisorJob())),
                foregroundReconciler =
                    SharedForegroundReconciler(
                        authStore = authStore,
                        refreshMembers = { serverId ->
                            serverMembersStore.refreshMembers(
                                serverId = serverId,
                                priority = DataRequestPriority.Background,
                                force = true
                            )
                        },
                        scope = CoroutineScope(SupervisorJob()),
                        diagnosticsLogger = sharedInfoLogger("SlockForegroundReconcile", "shared_foreground_reconcile")
                    ),
                homeStoreScope = CoroutineScope(SupervisorJob()),
                homeActivityStoreScope = CoroutineScope(SupervisorJob()),
                threadSendScope = threadSendScope
            ).also {
                val pushLifecycleScope = CoroutineScope(SupervisorJob())
                SlockPushRegistrationLifecycle.observeSessionEvents(
                    source = authStore.authSessionEvents,
                    scope = pushLifecycleScope
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
    // that don't exercise it). Invisible to sinks and fail-open — see foldPersistMessageShadow.
    private var messageShadowFoldLane: SyncCoreMessageShadowFoldLane? = null
    private var reactionShadowLane: SyncCoreReactionShadowLane? = null

    fun installMessageShadowFoldLane(lane: SyncCoreMessageShadowFoldLane) {
        messageShadowFoldLane = lane
    }

    fun installReactionShadowLane(lane: SyncCoreReactionShadowLane) {
        reactionShadowLane = lane
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
        runIfCurrentGrant(grant) {
            acceptGuarded(grant.serverId, event, source)
        }

    suspend fun acceptHomeUnreadProjection(
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String = "unknown",
    ): Boolean =
        runIfCurrentGrant(grant) {
            acceptGuardedHomeUnreadProjection(grant.serverId, event, source)
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
        // `sync_core_messages_v0` SHADOW fold+persist, BEFORE the sink fan-out and invisible to it. Flag-gated
        // and fail-open (foldPersistMessageShadow never throws), so the legacy path below is untouched.
        // Slice-2 lane insert point: when sole-apply becomes eligible (CANONICAL_FACT_SOLE_APPLY_ELIGIBLE,
        // V2 envelope), the per-scope MessageSoleApplyLane replaces this shadow persist HERE — message
        // frames route through fold+commit (one-txn) FIRST and only fan out to the sinks below on
        // CommitSucceeded. Dormant in slice-2: MessageSoleApplyLane.createIfEligible is structurally
        // false-gated, so no lane exists in production and this path stays byte-identical.
        messageShadowFoldLane?.let { lane ->
            if (lane.isEnabled()) foldPersistMessageShadow(lane, resolvedServerId, acceptedEvents)
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
                guardedSinks?.applyHomeUnreadProjection?.invoke(resolvedServerId, acceptedEvent)
                guardedSinks?.log?.invoke(
                    "realtime_domain_accept source=$source serverId=$resolvedServerId projection=home_unread_count"
                )
            }
        }
    }

    /**
     * SHADOW-of-record fold+persist for message-canonical frames (`sync_core_messages_v0`). For each accepted
     * MessageNew/MessageUpdated carrying a raw-wire canonical carry (decoded ONCE upstream in the projection
     * gateway), fold it through the shadow SyncCore and persist the channel's fact window via [lane]. The epoch
     * is the same per-scope recovery epoch the legacy cursor advanced just above (acceptedEventsForFold), so
     * the shadow cursor stays consistent with it. The entire body is guarded and swallows: this shadow persist
     * must NEVER block or throw into the legacy accept path, and it does not touch any sink output.
     */
    private fun foldPersistMessageShadow(
        lane: SyncCoreMessageShadowFoldLane,
        resolvedServerId: String,
        acceptedEvents: List<RealtimeEvent>,
    ) {
        try {
            val epoch = recoveryStates[resolvedServerId]?.epoch
            for (acceptedEvent in acceptedEvents) {
                val decoded =
                    when (acceptedEvent) {
                        is MessageNew -> acceptedEvent.decodedCanonical
                        is MessageUpdated -> acceptedEvent.decodedCanonical
                        else -> null
                    } ?: continue
                lane.foldAndPersist(
                    resolvedServerId = resolvedServerId,
                    decoded = decoded,
                    updated = acceptedEvent is MessageUpdated,
                    epoch = epoch,
                )
            }
        } catch (throwable: Throwable) {
            lane.logShadowFailure(resolvedServerId, throwable)
        }
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
        is RealtimeRefreshRequired ->
            "refresh:$serverId:${scope.name}:$reason:${channelId?.value.orEmpty()}:${memberId?.value.orEmpty()}:${subjectId.orEmpty()}"
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
    private var provider: SlockSharedStoreProvider? = null

    fun currentOrNull(): SlockSharedStoreProvider? = provider

    fun get(bridge: BridgeModule): SlockSharedStoreProvider {
        val eventBus = SlockEventBuses.appScope()
        return get(eventBus, "shared-store-provider", bridge)
    }

    fun get(eventBus: SlockEventBus, source: String, bridge: BridgeModule): SlockSharedStoreProvider {
        provider?.let {
            it.updateEventBus(eventBus, source)
            return it
        }
        return SlockSharedStoreProvider.create(eventBus, source, bridge).also {
            provider = it
        }
    }
}
