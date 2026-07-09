package build.raft.app.data

import build.raft.app.base.BridgeModule
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
import build.raft.app.KeyValuePixelAvatarStore
import build.raft.app.SharedThreadMessageStore
import build.raft.app.SlockPixelAvatarRuntime
import build.raft.app.data.realtime.MessageSeqEnvelope
import build.raft.app.data.realtime.MessageSeqRecoveryPolicy
import build.raft.app.data.realtime.MessageSeqRecoveryState
import build.raft.app.data.realtime.MessageSeqRepairBatch
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.refreshPixelAvatarsFromWeb
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
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

private const val SHARED_AUTH_LOG_TAG = "SlockSharedAuth"

// Pixel-avatar runtime refresh (task #352) waits out the startup window before
// fetching: first screen must not compete with this non-urgent request, and the
// per-client jitter keeps a web deploy from triggering a synchronized spike.
private const val PixelAvatarRefreshDelayMinMs = 10_000L
private const val PixelAvatarRefreshDelayMaxMs = 60_000L

private fun BridgeModule.logSharedAuthDiagnostic(prefix: String, message: String) {
    val line = "$prefix $message"
    slockLogInfo(SHARED_AUTH_LOG_TAG, line)
}

private fun sharedInfoLogger(tag: String, prefix: String = ""): (String) -> Unit =
    { message ->
        slockLogInfo(tag, if (prefix.isBlank()) message else "$prefix $message")
    }

private fun sharedWarnLogger(tag: String, prefix: String = ""): (String) -> Unit =
    { message ->
        slockLogWarn(tag, if (prefix.isBlank()) message else "$prefix $message")
    }

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    private val authKeyValueStore: SlockKeyValueStore,
    private val sharedPreferenceStore: SlockKeyValueStore,
    private val apiEnvironmentKeyValueStore: SlockKeyValueStore,
    val authStore: KeyValueAuthSessionStore,
    val authSessionEvents: KuiklyAuthSessionEvents,
    val authDataSource: SharedAuthDataSource,
    val networkClient: SlockNetworkClient,
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
    val messageTranslationStore: SharedMessageTranslationStore,
    val threadMessageStore: SharedThreadMessageStore,
    val foregroundReconciler: SharedForegroundReconciler,
    private val homeStoreScope: CoroutineScope,
    private val homeActivityStoreScope: CoroutineScope,
    val threadSendScope: CoroutineScope
) {
    private var homeStore: SharedHomeStore? = null
    private var homeActivityStore: SharedActivityStore? = null
    private var authSessionClearedSubscription: RealtimeSubscription? = null
    private var authSessionClearLogger: ((String) -> Unit)? = null
    private val activeAppShellPagerOwner = AppShellActivePagerOwner()

    val conversationIdentityProvider: SharedConversationIdentityProvider?
        get() = homeStore

    val sharedHomeStore: SharedHomeStore?
        get() = homeStore

    fun createAuthDataSourceForCurrentEnvironment(): SharedAuthDataSource =
        createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = apiEnvironmentStore,
            authStore = authStore
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

    fun createThreadLocalDataStore(currentServerId: suspend () -> ServerId?): ComposeLocalDataStore =
        SqlDelightComposeLocalDataStore(
            database = sqlDatabase,
            fallback = localDataStore,
            currentServerId = currentServerId
        )

    fun getOrCreateHomeStore(
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
        homeStore?.let { store ->
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
        return SharedHomeStore(
            authStore = authStore,
            dataSource = dataSource,
            scope = homeStoreScope,
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
        ).also { homeStore = it }
    }

    fun getOrCreateHomeActivityStore(
        inboxRepository: InboxRepository,
        readReceiptRepository: ReadReceiptRepository,
        conversationRepository: ConversationRepository,
        memberRepository: MemberRepository? = null,
        onUnreadCountsChanged: (() -> Unit)? = null,
        diagnosticsLogger: ((String) -> Unit)? = null
    ): SharedActivityStore {
        val existingStore =
            homeActivityStore
                ?: SlockSharedStoreRegistry.homeActivityStore
        existingStore
            ?.also { store ->
                store.updateRuntimeIntegrations(
                    authStore = authStore,
                    inboxRepository = inboxRepository,
                    readReceiptRepository = readReceiptRepository,
                    conversationRepository = conversationRepository,
                    memberRepository = memberRepository,
                    serverMembersStore = serverMembersStore,
                    tasksDataStore = tasksDataStore,
                    persistSlices = listOf(activityPersistSlice),
                    onUnreadCountsChanged = onUnreadCountsChanged,
                    diagnosticsLogger = diagnosticsLogger
                )
                homeActivityStore = store
                return store
            }
        return SharedActivityStore(
            authStore = authStore,
            inboxRepository = inboxRepository,
            readReceiptRepository = readReceiptRepository,
            conversationRepository = conversationRepository,
            memberRepository = memberRepository,
            serverMembersStore = serverMembersStore,
            tasksDataStore = tasksDataStore,
            persistSlices = listOf(activityPersistSlice),
            onUnreadCountsChanged = onUnreadCountsChanged ?: {},
            scope = homeActivityStoreScope,
            diagnosticsLogger = diagnosticsLogger ?: {}
        ).also {
            homeActivityStore = it
            SlockSharedStoreRegistry.homeActivityStore = it
        }
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
            val tasksDataStore = SharedTasksDataStore()
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
            val networkClient =
                SharedSlockNetworkClient(
                    sessionStore = authStore,
                    baseUrlProvider = { apiEnvironmentStore.current().apiBaseUrl },
                    warningLogger = sharedWarnLogger("SlockSharedNetwork", "shared_network"),
                    platformFileResolver = platformFileResolver
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
                networkClient = networkClient,
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
                        diagnosticsLogger = sharedInfoLogger("SlockComposerDraft")
                    ),
                activityPersistSlice =
                    createSharedActivityPersistSlice(
                        storage = SharedPreferencePersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = sharedInfoLogger("SlockActivityPersist", "shared_activity_persist")
                    ),
                homeSavedStateCache = SharedHomeSavedStateCache(),
                homeIdentityPersistSlice =
                    createSharedHomeIdentityPersistSlice(
                        storage = SharedPreferencePersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = sharedInfoLogger("SlockHomePersist", "shared_home_persist")
                    ),
                apiEnvironmentStore = apiEnvironmentStore,
                eventBus = eventBusRelay,
                realtimeDomainEventOwner =
                    SharedRealtimeDomainEventOwner(
                        tasksDataStore = tasksDataStore,
                        cursorStore = RealtimeAppliedSeqCursorStore(sharedPreferenceStore)
                    ),
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
                threadSendScope = CoroutineScope(SupervisorJob())
            )
        }
    }
}

internal fun createSharedAuthDataSourceForCurrentEnvironment(
    apiEnvironmentStore: SharedApiEnvironmentStore,
    authStore: SlockAuthSessionStore,
    transportFactory: (String) -> SlockAuthTransport = { baseUrl ->
        SharedSlockAuthTransport(
            baseUrl = baseUrl,
            warningLogger = { message ->
                slockLogWarn(SHARED_AUTH_LOG_TAG, "shared_auth_network $message")
            }
        )
    },
    diagnosticsLogger: (String) -> Unit = sharedInfoLogger(SHARED_AUTH_LOG_TAG, "shared_auth")
): SharedAuthDataSource =
    SharedAuthDataSource(
        transport = transportFactory(apiEnvironmentStore.current().apiBaseUrl),
        sessionStore = authStore,
        diagnosticsLogger = diagnosticsLogger
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
}

internal data class RealtimeDomainEventSinks(
    val applyHomeShell: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applySettings: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyServerMembersRefresh: (String, RealtimeRefreshRequired) -> Unit = { _, _ -> },
    val applyAgentActivity: (MemberPresenceUpdated) -> Unit = {},
    val requestMemberDetailRefresh: () -> Unit = {},
    val joinChannel: (String) -> Unit = {},
    val emitRouteMarker: (RealtimeEvent) -> Unit = {},
    val log: (String) -> Unit = {}
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

internal class SharedRealtimeDomainEventOwner(
    private val tasksDataStore: SharedTasksDataStore? = null,
    private val cursorStore: RealtimeAppliedSeqCursorStore? = null
) : RealtimeDomainEventOwner {
    private var sinks: RealtimeDomainEventSinks? = null
    private val activeThreadSinks = mutableListOf<RealtimeActiveThreadSink>()
    private val acceptedKeys = ArrayDeque<String>()
    private val acceptedKeySet = mutableSetOf<String>()
    private val recoveryStates = mutableMapOf<String, MessageSeqRecoveryState<RealtimeEvent>>()

    override fun registerSinks(sinks: RealtimeDomainEventSinks) {
        this.sinks = sinks
    }

    override fun unregisterSinks(sinks: RealtimeDomainEventSinks) {
        if (this.sinks === sinks || this.sinks == sinks) {
            this.sinks = null
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

    override fun accept(serverId: String, event: RealtimeEvent, source: String) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            sinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        val acceptedEvents = acceptedEventsForFold(resolvedServerId, event, source)
        if (acceptedEvents.isEmpty()) return
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
    internal var homeActivityStore: SharedActivityStore? = null

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
