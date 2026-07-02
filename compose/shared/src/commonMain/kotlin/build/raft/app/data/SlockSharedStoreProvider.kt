package build.raft.app.data

import build.raft.app.base.BridgeModule
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
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
import build.raft.app.network.SlockNetworkModule
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    private val authKeyValueStore: RebindableKuiklyMMKVKeyValueStore,
    private val sharedPreferenceStore: RebindableKuiklyMMKVKeyValueStore,
    private val apiEnvironmentKeyValueStore: RebindableKuiklyMMKVKeyValueStore,
    val authStore: KuiklyMMKVAuthSessionStore,
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
    val realtimeDomainEventOwner: SharedRealtimeDomainEventOwner,
    val threadSendScope: CoroutineScope
) {
    private var homeStore: SharedHomeStore? = null
    private var homeActivityStore: SharedActivityStore? = null
    private var authSessionClearedCallbackRef: CallbackRef? = null

    val conversationIdentityProvider: SharedConversationIdentityProvider?
        get() = homeStore

    fun updatePager(pager: Pager) {
        authKeyValueStore.updatePager(pager)
        sharedPreferenceStore.updatePager(pager)
        apiEnvironmentKeyValueStore.updatePager(pager)
        authSessionEvents.updatePager(pager)
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
        markChannelReadAll: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        markChannelUnread: suspend (ChannelId) -> DataResult<ReadReceiptResult> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        archiveChannel: suspend (ChannelId) -> DataResult<ChannelArchiveResult> = {
            DataResult.Empty(EmptyReason.NoChannels)
        },
        pinUpdateFailureCallback: (String) -> Unit = {},
        scope: CoroutineScope,
        diagnosticsLogger: (String) -> Unit = {}
    ): SharedHomeStore {
        homeStore?.let { return it }
        return SharedHomeStore(
            authStore = authStore,
            dataSource = dataSource,
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            loadSidebarOrder = loadSidebarOrder,
            updateSidebarOrder = updateSidebarOrder,
            markChannelReadAll = markChannelReadAll,
            markChannelUnread = markChannelUnread,
            archiveChannel = archiveChannel,
            pinUpdateFailureCallback = pinUpdateFailureCallback,
            persistSlices = listOf(homeIdentityPersistSlice)
        ).also { homeStore = it }
    }

    fun getOrCreateHomeActivityStore(
        inboxRepository: InboxRepository,
        readReceiptRepository: ReadReceiptRepository,
        conversationRepository: ConversationRepository,
        memberRepository: MemberRepository? = null,
        localWriteThrough: ComposeLocalWriteThrough? = null,
        onUnreadCountsChanged: (() -> Unit)? = null,
        scope: CoroutineScope,
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
                    localWriteThrough = localWriteThrough,
                    onUnreadCountsChanged = onUnreadCountsChanged,
                    scope = scope,
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
            localWriteThrough = localWriteThrough,
            persistSlices = listOf(activityPersistSlice),
            onUnreadCountsChanged = onUnreadCountsChanged ?: {},
            scope = scope,
            diagnosticsLogger = diagnosticsLogger ?: {}
        ).also {
            homeActivityStore = it
            SlockSharedStoreRegistry.homeActivityStore = it
        }
    }

    fun installAuthSessionClearNotifyBridge(logger: (String) -> Unit) {
        authSessionClearedCallbackRef?.let(authSessionEvents::removeSessionClearedListener)
        val scope = CoroutineScope(EmptyCoroutineContext)
        authSessionClearedCallbackRef =
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
        fun create(pager: Pager, bridge: BridgeModule): SlockSharedStoreProvider {
            val database = createSlockSqlDatabase(bridge)
            val relationalStore = SqlDelightRelationalStore(database)
            val localDataStore =
                RelationalComposeLocalDataStore(
                    store = relationalStore,
                    diagnosticsLogger = { message -> bridge.log("local_cache $message") }
                )
            val tasksDataStore = SharedTasksDataStore()
            val authSessionEvents =
                KuiklyAuthSessionEvents(
                    pager = pager,
                    source = "pager:${pager.pagerId}"
                )
            val authKeyValueStore =
                RebindableKuiklyMMKVKeyValueStore(
                    pager = pager,
                    namespace = SLOCK_AUTH_SESSION_MMKV_NAMESPACE
                )
            val authStore =
                KuiklyMMKVAuthSessionStore(
                    store = authKeyValueStore,
                    sessionClearEvents = authSessionEvents,
                    diagnosticsLogger = { message -> bridge.log("shared_auth_session $message") }
                )
            val sharedPreferenceStore =
                RebindableKuiklyMMKVKeyValueStore(
                    pager = pager,
                    namespace = SlockLocalPreferenceStore.KuiklyNamespace
                )
            val apiEnvironmentKeyValueStore =
                RebindableKuiklyMMKVKeyValueStore(
                    pager = pager,
                    namespace = SharedApiEnvironmentStore.Namespace
                )
            val apiEnvironmentStore =
                SharedApiEnvironmentStore(
                    apiEnvironmentKeyValueStore
                )
            val apiBaseUrl = apiEnvironmentStore.current().apiBaseUrl
            val networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME)
            val nativeNetworkFallback =
                KuiklySlockNetworkClient(
                    sessionStore = authStore,
                    networkModule = networkModule,
                    warningLogger = { message -> bridge.log("shared_network_native_fallback $message") }
                )
            val authDataSource =
                SharedAuthDataSource(
                    transport = SharedKtorSlockAuthTransport(
                        baseUrl = apiBaseUrl,
                        warningLogger = { message -> bridge.log("shared_auth_network $message") }
                    ),
                    sessionStore = authStore,
                    diagnosticsLogger = { message -> bridge.log("shared_auth $message") }
                )
            val networkClient =
                SharedKtorSlockNetworkClient(
                    sessionStore = authStore,
                    baseUrl = apiBaseUrl,
                    warningLogger = { message -> bridge.log("shared_network $message") },
                    nativeFileRefFallback = nativeNetworkFallback
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
                    logger = { message -> bridge.log("shared_agent_detail_store $message") }
                ),
                agentPanelTabOrderStore = SharedAgentPanelTabOrderStore(),
                tasksDataStore = tasksDataStore,
                serverMembersStore =
                    SharedServerMembersStore(
                        memberRepository =
                            RealMemberRepository(
                                sessionStore = authStore,
                                networkClient = networkClient,
                                logger = { message -> bridge.log("shared_server_members_repository $message") }
                            ),
                        logger = { message -> bridge.log(message) }
                    ),
                composerDraftPersistSlice =
                    SharedThreadComposerDraftPersistSlice(
                        SharedThreadComposerDraftPersistence(
                            sharedPreferenceStore,
                            diagnosticsLogger = { message -> bridge.log(message) }
                        ),
                        diagnosticsLogger = { message -> bridge.log(message) }
                    ),
                activityPersistSlice =
                    createSharedActivityPersistSlice(
                        storage = SharedPreferencePersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = { message -> bridge.log("shared_activity_persist $message") }
                    ),
                homeSavedStateCache = SharedHomeSavedStateCache(),
                homeIdentityPersistSlice =
                    createSharedHomeIdentityPersistSlice(
                        storage = SharedPreferencePersistStorage(sharedPreferenceStore),
                        diagnosticsLogger = { message -> bridge.log("shared_home_persist $message") }
                    ),
                apiEnvironmentStore = apiEnvironmentStore,
                realtimeDomainEventOwner = SharedRealtimeDomainEventOwner(tasksDataStore),
                threadSendScope = CoroutineScope(SupervisorJob())
            ).also { provider ->
                provider.installAuthSessionClearNotifyBridge { message ->
                    bridge.log("shared_auth_session $message")
                }
            }
        }
    }
}

internal interface RealtimeDomainEventOwner {
    fun registerSinks(sinks: RealtimeDomainEventSinks)
    fun unregisterSinks(sinks: RealtimeDomainEventSinks)
    fun accept(serverId: String, event: RealtimeEvent, source: String = "unknown")
}

internal data class RealtimeDomainEventSinks(
    val applyHomeShell: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applySettings: (String, RealtimeEvent) -> Unit = { _, _ -> },
    val applyAgentActivity: (MemberPresenceUpdated) -> Unit = {},
    val requestMemberDetailRefresh: () -> Unit = {},
    val joinChannel: (String) -> Unit = {},
    val emitRouteMarker: (RealtimeEvent) -> Unit = {},
    val log: (String) -> Unit = {}
)

internal class SharedRealtimeDomainEventOwner(
    private val tasksDataStore: SharedTasksDataStore? = null
) : RealtimeDomainEventOwner {
    private var sinks: RealtimeDomainEventSinks? = null
    private val acceptedKeys = ArrayDeque<String>()
    private val acceptedKeySet = mutableSetOf<String>()

    override fun registerSinks(sinks: RealtimeDomainEventSinks) {
        this.sinks = sinks
    }

    override fun unregisterSinks(sinks: RealtimeDomainEventSinks) {
        if (this.sinks === sinks || this.sinks == sinks) {
            this.sinks = null
        }
    }

    override fun accept(serverId: String, event: RealtimeEvent, source: String) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val key = event.realtimeDomainKey(resolvedServerId)
        if (key != null && !rememberAcceptedKey(key)) {
            sinks?.log?.invoke("realtime_domain_accept_skip source=$source reason=duplicate key=$key")
            return
        }
        if (event is Heartbeat) {
            sinks?.log?.invoke("realtime_domain_accept source=$source serverId=$resolvedServerId projection=none type=heartbeat")
            return
        }
        val activeSinks = sinks
        event.patchSharedTaskProjection(resolvedServerId)
        activeSinks?.applyHomeShell?.invoke(resolvedServerId, event)
        activeSinks?.applySettings?.invoke(resolvedServerId, event)
        if (event is DirectMessageCreated) {
            activeSinks?.joinChannel?.invoke(event.channelId.value)
        }
        if (event is ServerPlanUpdated) {
            activeSinks?.log?.invoke("shared_realtime_server_plan_updated serverId=${event.serverId.value} plan=${event.plan}")
        }
        if (event is MemberPresenceUpdated && event.kind == build.raft.app.model.MemberKind.Agent) {
            activeSinks?.applyAgentActivity?.invoke(event)
        } else if (event.refreshesMemberDetail()) {
            activeSinks?.requestMemberDetailRefresh?.invoke()
        }
        activeSinks?.emitRouteMarker?.invoke(event)
        if (event !is MemberPresenceUpdated) {
            activeSinks?.log?.invoke(
                "realtime_domain_accept source=$source serverId=$resolvedServerId projection=domain"
            )
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

internal object SlockSharedStoreRegistry {
    private var provider: SlockSharedStoreProvider? = null
    internal var homeActivityStore: SharedActivityStore? = null

    fun get(pager: Pager, bridge: BridgeModule): SlockSharedStoreProvider {
        provider?.let {
            it.updatePager(pager)
            return it
        }
        return SlockSharedStoreProvider.create(pager, bridge).also {
            provider = it
        }
    }
}
