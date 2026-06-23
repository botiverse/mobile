package ai.slock.compose.data

import ai.slock.compose.model.AgentCreated
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.ServerDeleted
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerMembershipRemoved
import ai.slock.compose.model.ServerPlanUpdated
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskDeleted
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadUpdated
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SharedRealtimeAcceptResult(
    val accepted: Int,
    val duplicates: Int,
    val wrote: Boolean
) {
    val duplicateOnly: Boolean
        get() = accepted == 0 && duplicates > 0
}

data class SharedRealtimeProjection(
    val serverId: ServerId,
    val event: RealtimeEvent,
    val wrote: Boolean,
    val duplicate: Boolean
)

class SharedRealtimeStore(
    private val localWriteThrough: ComposeLocalWriteThrough,
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val seenEventKeys = linkedSetOf<String>()
    private val eventKeyOrder = ArrayDeque<String>()
    private val _projections =
        MutableSharedFlow<SharedRealtimeProjection>(
            replay = 0,
            extraBufferCapacity = 64
        )

    val projections: SharedFlow<SharedRealtimeProjection> =
        _projections.asSharedFlow()

    suspend fun acceptRealtimeEvent(
        serverId: ServerId,
        event: RealtimeEvent,
        activeConversationId: String? = null
    ): SharedRealtimeAcceptResult =
        acceptRealtimeEvents(serverId, listOf(event), activeConversationId)

    suspend fun acceptRealtimeEvents(
        serverId: ServerId,
        events: List<RealtimeEvent>,
        activeConversationId: String? = null
    ): SharedRealtimeAcceptResult {
        if (events.isEmpty()) return SharedRealtimeAcceptResult(accepted = 0, duplicates = 0, wrote = false)
        val acceptedEvents = mutableListOf<RealtimeEvent>()
        val duplicateEvents = mutableListOf<RealtimeEvent>()
        mutex.withLock {
            events.forEach { event ->
                val key = event.realtimeStoreKey(serverId)
                if (key in seenEventKeys) {
                    duplicateEvents += event
                } else {
                    rememberEventKey(key)
                    acceptedEvents += event
                }
            }
        }
        val wrote =
            if (acceptedEvents.isNotEmpty()) {
                localWriteThrough.writeRealtimeEvents(serverId, acceptedEvents, activeConversationId)
            } else {
                false
            }
        acceptedEvents.forEach { event ->
            _projections.tryEmit(
                SharedRealtimeProjection(
                    serverId = serverId,
                    event = event,
                    wrote = wrote,
                    duplicate = false
                )
            )
        }
        duplicateEvents.forEach { event ->
            _projections.tryEmit(
                SharedRealtimeProjection(
                    serverId = serverId,
                    event = event,
                    wrote = false,
                    duplicate = true
                )
            )
        }
        logger(
            "shared_realtime_accept serverId=${serverId.value} accepted=${acceptedEvents.size} " +
                "duplicates=${duplicateEvents.size} wrote=$wrote types=${events.joinToString(",") { it.realtimeStoreType() }}"
        )
        return SharedRealtimeAcceptResult(
            accepted = acceptedEvents.size,
            duplicates = duplicateEvents.size,
            wrote = wrote
        )
    }

    private fun rememberEventKey(key: String) {
        if (seenEventKeys.add(key)) {
            eventKeyOrder.addLast(key)
        }
        while (eventKeyOrder.size > MaxRememberedEventKeys) {
            seenEventKeys.remove(eventKeyOrder.removeFirst())
        }
    }

    private companion object {
        const val MaxRememberedEventKeys = 512
    }
}

private fun RealtimeEvent.realtimeStoreKey(serverId: ServerId): String =
    buildString {
        append(serverId.value)
        append(':')
        append(
            when (this@realtimeStoreKey) {
                is MessageNew -> "message:new:${message.id.value}:${message.seq ?: message.hashCode()}"
                is MessageUpdated -> "message:updated:${message.id.value}:${message.seq ?: message.hashCode()}"
                is DirectMessageCreated -> "dm:new:${channelId.value}"
                is ThreadUpdated ->
                    "thread:updated:${parentMessageId.value}:${threadChannelId.value}:$replyCount:" +
                        "${unreadCount ?: "na"}:${firstUnreadMessageId?.value ?: "na"}:${lastReplyAt ?: "na"}"
                is TaskCreated -> "task:created:${tasks.joinToString("|") { "${it.id.value}:${it.status}:${it.updatedAtLabel}" }}"
                is TaskUpdated -> "task:updated:${task.id.value}:${task.status}:${task.updatedAtLabel}"
                is TaskDeleted -> "task:deleted:${taskId.value}"
                is MemberPresenceUpdated -> "member:presence:${memberId.value}:${presence}:${serverSeq ?: "na"}"
                is RealtimeRefreshRequired ->
                    "refresh:$scope:$reason:${serverId.value}:${channelId?.value ?: "na"}:" +
                        "${memberId?.value ?: "na"}:${subjectId ?: "na"}"
                is ServerMembershipRemoved -> "server:membership_removed:${serverId.value}"
                is ServerDeleted -> "server:deleted:${serverId.value}"
                is ServerPlanUpdated -> "server:plan:${serverId.value}:$plan"
                is AgentCreated -> "agent:created:${agent.id.value}"
                is SyncResumeResponse ->
                    "sync:resume:$currentSeq:$hasMore:${messages.joinToString("|") { "${it.id.value}:${it.seq ?: "na"}" }}"
                is Heartbeat -> "heartbeat:$seq"
            }
        )
    }

private fun RealtimeEvent.realtimeStoreType(): String =
    when (this) {
        is MessageNew -> "message:new"
        is MessageUpdated -> "message:updated"
        is DirectMessageCreated -> "dm:new"
        is ThreadUpdated -> "thread:updated"
        is TaskCreated -> "task:created"
        is TaskUpdated -> "task:updated"
        is TaskDeleted -> "task:deleted"
        is MemberPresenceUpdated -> "member:presence"
        is RealtimeRefreshRequired -> "refresh:$scope"
        is ServerMembershipRemoved -> "server:membership_removed"
        is ServerDeleted -> "server:deleted"
        is ServerPlanUpdated -> "server:plan"
        is AgentCreated -> "agent:created"
        is SyncResumeResponse -> "sync:resume"
        is Heartbeat -> "heartbeat"
    }
