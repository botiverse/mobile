package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.SharedThreadComposerDraftPersistSlice
import ai.slock.compose.SharedThreadComposerDraftPersistence
import ai.slock.compose.model.AgentCreated
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerPlanUpdated
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskDeleted
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.network.SlockNetworkModule
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    val authStore: KuiklyMMKVAuthSessionStore,
    val authSessionEvents: KuiklyAuthSessionEvents,
    val authDataSource: SharedAuthDataSource,
    val networkClient: SlockNetworkClient,
    val localDataStore: ComposeLocalDataStore,
    val agentDetailStore: SharedAgentDetailStore,
    val tasksDataStore: SharedTasksDataStore,
    val conversationDirectoryStore: SharedConversationDirectoryStore,
    val serverMembersStore: SharedServerMembersStore,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val activityStateCache: SharedActivityStateCache,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    val apiEnvironmentStore: SharedApiEnvironmentStore,
    val realtimeDomainEventOwner: SharedRealtimeDomainEventOwner
) {
    private var homeActivityStore: SharedActivityStore? = null
    private var authSessionClearedCallbackRef: CallbackRef? = null

    fun createThreadLocalDataStore(currentServerId: suspend () -> ServerId?): ComposeLocalDataStore =
        SqlDelightComposeLocalDataStore(
            database = sqlDatabase,
            fallback = localDataStore,
            currentServerId = currentServerId
        )

    fun getOrCreateHomeActivityStore(
        inboxRepository: InboxRepository,
        readReceiptRepository: ReadReceiptRepository,
        conversationRepository: ConversationRepository,
        memberRepository: MemberRepository? = null,
        localWriteThrough: ComposeLocalWriteThrough? = null,
        activityStateCache: SharedActivityStateCache? = this.activityStateCache,
        onUnreadCountsChanged: (() -> Unit)? = null,
        scope: CoroutineScope
    ): SharedActivityStore {
        homeActivityStore
            ?.also { store ->
                store.updateRuntimeIntegrations(
                    localWriteThrough = localWriteThrough,
                    onUnreadCountsChanged = onUnreadCountsChanged,
                    scope = scope
                )
                return store
            }
        return SharedActivityStore(
            authStore = authStore,
            inboxRepository = inboxRepository,
            readReceiptRepository = readReceiptRepository,
            conversationRepository = conversationRepository,
            memberRepository = memberRepository,
            serverMembersStore = serverMembersStore,
            localWriteThrough = localWriteThrough,
            activityStateCache = activityStateCache,
            onUnreadCountsChanged = onUnreadCountsChanged ?: {},
            scope = scope
        ).also { homeActivityStore = it }
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
            val localDataStore = RelationalComposeLocalDataStore(relationalStore)
            val tasksDataStore = SharedTasksDataStore()
            val conversationDirectoryStore =
                SharedConversationDirectoryStore(
                    database = database,
                    logger = { message -> bridge.log("shared_conversation_directory $message") }
                )
            val authSessionEvents =
                KuiklyAuthSessionEvents(
                    pager = pager,
                    source = "pager:${pager.pagerId}"
                )
            val authStore =
                KuiklyMMKVAuthSessionStore(
                    store = KuiklyMMKVKeyValueStore(
                        pager = pager,
                        namespace = SLOCK_AUTH_SESSION_MMKV_NAMESPACE
                    ),
                    sessionClearEvents = authSessionEvents
                )
            val sharedPreferenceStore =
                KuiklyMMKVKeyValueStore(
                    pager = pager,
                    namespace = SlockLocalPreferenceStore.KuiklyNamespace
                )
            val networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME)
            val authDataSource =
                SharedAuthDataSource(
                    transport = KuiklySlockAuthTransport(networkModule = networkModule),
                    sessionStore = authStore,
                    diagnosticsLogger = { message -> bridge.log("shared_auth $message") }
                )
            val networkClient =
                KuiklySlockNetworkClient(
                    sessionStore = authStore,
                    networkModule = networkModule,
                    warningLogger = { message -> bridge.log("shared_network $message") }
                )
            return SlockSharedStoreProvider(
                sqlDatabase = database,
                authStore = authStore,
                authSessionEvents = authSessionEvents,
                authDataSource = authDataSource,
                networkClient = networkClient,
                localDataStore = localDataStore,
                agentDetailStore = SharedAgentDetailStore(
                    logger = { message -> bridge.log("shared_agent_detail_store $message") }
                ),
                tasksDataStore = tasksDataStore,
                conversationDirectoryStore = conversationDirectoryStore,
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
                activityStateCache = SharedActivityStateCache(),
                homeSavedStateCache = SharedHomeSavedStateCache(),
                apiEnvironmentStore =
                    SharedApiEnvironmentStore(
                        KuiklyMMKVKeyValueStore(
                            pager = pager,
                            namespace = SharedApiEnvironmentStore.Namespace
                        )
                    ),
                realtimeDomainEventOwner = SharedRealtimeDomainEventOwner(tasksDataStore)
            ).also { provider ->
                provider.installAuthSessionClearNotifyBridge { message ->
                    bridge.log("shared_auth_session $message")
                }
            }
        }
    }
}

internal data class ThreadRealtimeCacheInvalidationSignal(
    val sequence: Long,
    val serverId: String,
    val threadIds: Set<String>,
    val broadcast: Boolean = false
)

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
    val emitThreadCacheInvalidation: (ThreadRealtimeCacheInvalidationSignal) -> Unit = {},
    val log: (String) -> Unit = {}
)

internal class SharedRealtimeDomainEventOwner(
    private val tasksDataStore: SharedTasksDataStore? = null
) : RealtimeDomainEventOwner {
    private var nextSequence = 0L
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
        if (event is MemberPresenceUpdated && event.kind == ai.slock.compose.model.MemberKind.Agent) {
            activeSinks?.applyAgentActivity?.invoke(event)
            activeSinks?.requestMemberDetailRefresh?.invoke()
        } else if (event.refreshesMemberDetail()) {
            activeSinks?.requestMemberDetailRefresh?.invoke()
        }
        activeSinks?.emitRouteMarker?.invoke(event)
        emitThreadCacheInvalidation(resolvedServerId, event, source, activeSinks)
    }

    private fun emitThreadCacheInvalidation(
        serverId: String,
        event: RealtimeEvent,
        source: String,
        activeSinks: RealtimeDomainEventSinks?
    ) {
        val threadIds = event.threadCacheInvalidationThreadIds()
        val broadcast = event.broadcastsThreadCacheInvalidation()
        if (threadIds.isEmpty() && !broadcast) {
            activeSinks?.log?.invoke("realtime_domain_accept source=$source serverId=$serverId threadCache=none")
            return
        }
        nextSequence += 1
        val signal =
            ThreadRealtimeCacheInvalidationSignal(
                sequence = nextSequence,
                serverId = serverId,
                threadIds = threadIds,
                broadcast = broadcast
            )
        activeSinks?.emitThreadCacheInvalidation?.invoke(signal)
        activeSinks?.log?.invoke(
            "realtime_domain_accept source=$source serverId=$serverId threadCache=invalidated " +
                "seq=$nextSequence ids=${threadIds.joinToString(",")} broadcast=$broadcast"
        )
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

private fun RealtimeEvent.threadCacheInvalidationThreadIds(): Set<String> =
    when (this) {
        is MessageNew -> setOf(message.cacheThreadId())
        is MessageUpdated -> setOf(message.cacheThreadId())
        is SyncResumeResponse -> messages.mapTo(linkedSetOf()) { it.cacheThreadId() }
        is ThreadUpdated -> setOf(threadChannelId.value, parentMessageId.value)
        is TaskCreated -> tasks.mapNotNullTo(linkedSetOf()) { it.threadId?.value ?: it.channelId?.value }
        is TaskUpdated -> setOfNotNull(task.threadId?.value ?: task.channelId?.value)
        else -> emptySet()
    }.filterTo(linkedSetOf()) { it.isNotBlank() }

private fun RealtimeEvent.broadcastsThreadCacheInvalidation(): Boolean =
    this is ThreadUpdated

private fun Message.cacheThreadId(): String =
    channelId?.value ?: threadId.value

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
            serverSeq?.let { "member:presence:$serverId:$it" }
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

    fun get(pager: Pager, bridge: BridgeModule): SlockSharedStoreProvider {
        provider?.let { return it }
        return SlockSharedStoreProvider.create(pager, bridge).also { provider = it }
    }
}
