package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.data.realtime.RealtimeRoutePolicy
import ai.slock.compose.model.AgentCreated
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberPresenceUpdated
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    val authStore: KuiklyAuthSessionStore,
    val localDataStore: ComposeLocalDataStore,
    val agentDetailStore: SharedAgentDetailStore,
    val tasksDataStore: SharedTasksDataStore,
    val realtimeDomainEventOwner: SharedRealtimeDomainEventOwner
) {
    fun createThreadLocalDataStore(currentServerId: suspend () -> ServerId?): ComposeLocalDataStore =
        SqlDelightComposeLocalDataStore(
            database = sqlDatabase,
            fallback = localDataStore,
            currentServerId = currentServerId
        )

    companion object {
        fun create(bridge: BridgeModule): SlockSharedStoreProvider {
            val database = createSlockSqlDatabase(bridge)
            val relationalStore = SqlDelightRelationalStore(database)
            val localDataStore = RelationalComposeLocalDataStore(relationalStore)
            val tasksDataStore = SharedTasksDataStore()
            return SlockSharedStoreProvider(
                sqlDatabase = database,
                authStore = KuiklyAuthSessionStore(relationalStore),
                localDataStore = localDataStore,
                agentDetailStore = SharedAgentDetailStore(
                    logger = { message -> bridge.log("shared_agent_detail_store $message") }
                ),
                tasksDataStore = tasksDataStore,
                realtimeDomainEventOwner = SharedRealtimeDomainEventOwner(tasksDataStore)
            )
        }
    }
}

internal data class ThreadRealtimeProjectionSignal(
    val sequence: Long,
    val serverId: String,
    val event: RealtimeEvent
)

internal interface RealtimeDomainEventOwner {
    val threadProjections: StateFlow<ThreadRealtimeProjectionSignal?>

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
    private var nextSequence = 0L
    private var sinks: RealtimeDomainEventSinks? = null
    private val acceptedKeys = ArrayDeque<String>()
    private val acceptedKeySet = mutableSetOf<String>()
    private val _threadProjections = MutableStateFlow<ThreadRealtimeProjectionSignal?>(null)
    override val threadProjections: StateFlow<ThreadRealtimeProjectionSignal?> = _threadProjections

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
        if (!event.shouldProjectToThread()) {
            activeSinks?.log?.invoke("realtime_domain_accept source=$source serverId=$resolvedServerId projection=none")
            return
        }
        nextSequence += 1
        _threadProjections.value =
            ThreadRealtimeProjectionSignal(
                sequence = nextSequence,
                serverId = resolvedServerId,
                event = event
            )
        activeSinks?.log?.invoke("realtime_domain_accept source=$source serverId=$resolvedServerId projection=thread seq=$nextSequence")
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

private fun RealtimeEvent.shouldProjectToThread(): Boolean =
    this is MemberPresenceUpdated || RealtimeRoutePolicy.shouldForwardToActiveThread(this)

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

    fun get(bridge: BridgeModule): SlockSharedStoreProvider {
        provider?.let { return it }
        return SlockSharedStoreProvider.create(bridge).also { provider = it }
    }
}
