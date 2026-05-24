package ai.slock.android.core.data.realtime

import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageUpdated
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.ThreadUpdated
import ai.slock.android.core.model.TaskCreated
import ai.slock.android.core.model.TaskUpdated
import ai.slock.android.core.model.TaskDeleted
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.ServerId
import com.google.gson.JsonObject
import org.json.JSONObject

class SocketIoRealtimeClient(
    private val transport: RealtimeSocketTransport,
    private val eventMapper: RealtimeEventMapper = RealtimeEventMapper(),
    private val logger: (String) -> Unit = {},
    private val diagnostics: RealtimeClientDiagnostics? = null
) {
    private val listeners = mutableSetOf<(RealtimeEvent) -> Unit>()
    private val transportSubscriptions = mutableListOf<RealtimeSubscription>()
    private val joinedChannels = linkedSetOf<ChannelId>()
    private var lastSeq: Long = 0
    private var connectedServerId: String? = null
    // Deduplicate MemberPresenceUpdated: agent:activity fires multiple times per second for the
    // same agent. Skip dispatch when presence+kind is unchanged to avoid redundant recompositions.
    private val lastPresenceByMember =
            mutableMapOf<String, Pair<ai.slock.android.core.model.Presence, ai.slock.android.core.model.MemberKind?>>()
    var state: RealtimeConnectionState = RealtimeConnectionState.Disconnected
        private set

    fun connect(request: RealtimeConnectRequest) {
        if (connectedServerId != request.auth.serverId.value) {
            joinedChannels.clear()
            connectedServerId = request.auth.serverId.value
        }
        lastSeq = request.lastSeq
        state = RealtimeConnectionState.Connecting
        diagnostics?.onConnectRequested(request.auth.serverId, request.lastSeq)
        ensureTransportListeners()
        logger("client_connect_start serverId=${request.auth.serverId.value} lastSeq=${request.lastSeq}")
        transport.connect(
            mapOf(
                "token" to request.auth.token,
                "serverId" to request.auth.serverId.value
            )
        )
    }

    fun disconnect() {
        logger("client_disconnect serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size}")
        transport.disconnect()
        state = RealtimeConnectionState.Disconnected
        diagnostics?.onIntentionalDisconnect(
                reason = "client_disconnect",
                serverId = connectedServerId?.let(::ServerId)
        )
        lastPresenceByMember.clear()
    }

    fun joinChannel(channelId: ChannelId) {
        val added = joinedChannels.add(channelId)
        logger("join_room channelId=${channelId.value} replay=false added=$added")
        if (!added) return
        emitJoinChannel(channelId)
    }

    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription {
        listeners += listener
        return RealtimeSubscription { listeners -= listener }
    }

    private fun ensureTransportListeners() {
        if (transportSubscriptions.isNotEmpty()) return
        transportSubscriptions += transport.on(RealtimeSocketEvents.Connect) {
            state = RealtimeConnectionState.Connected
            diagnostics?.onConnected(connectedServerId?.let(::ServerId), lastSeq.takeIf { it > 0L })
            logger("client_connected serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size}")
            rejoinChannels(reason = "connect")
            emitSyncResume(reason = "connect")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.RoomsJoined) {
            state = RealtimeConnectionState.RoomsJoined
            diagnostics?.onConnected(connectedServerId?.let(::ServerId), lastSeq.takeIf { it > 0L })
            logger("rooms_joined serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size} lastSeq=$lastSeq")
            emitSyncResume(reason = "rooms_joined")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ConnectError) { payload ->
            state = RealtimeConnectionState.Failed(payload?.toString().orEmpty())
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = connectedServerId?.let(::ServerId)
            )
            logger("client_connect_error serverId=${connectedServerId.orEmpty()} reason=${payload?.javaClass?.simpleName.orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Disconnect) {
            state = RealtimeConnectionState.Disconnected
            diagnostics?.onDisconnected(
                    reason = "transport_disconnect",
                    serverId = connectedServerId?.let(::ServerId)
            )
            logger("client_disconnected serverId=${connectedServerId.orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Reconnect) {
            state = RealtimeConnectionState.Connected
            diagnostics?.onConnected(connectedServerId?.let(::ServerId), lastSeq.takeIf { it > 0L })
            logger("client_reconnected serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size} lastSeq=$lastSeq")
            rejoinChannels(reason = "reconnect")
            emitSyncResume(reason = "reconnect")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ReconnectAttempt) { payload ->
            logger("client_reconnect_attempt serverId=${connectedServerId.orEmpty()} payload=${payload?.toString().orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ReconnectError) { payload ->
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = connectedServerId?.let(::ServerId)
            )
            logger("client_reconnect_error serverId=${connectedServerId.orEmpty()} reason=${payload?.javaClass?.simpleName.orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ReconnectFailed) { payload ->
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = connectedServerId?.let(::ServerId)
            )
            logger("client_reconnect_failed serverId=${connectedServerId.orEmpty()} reason=${payload?.javaClass?.simpleName.orEmpty()}")
        }
        RealtimeWireEventNames.forEach { eventName ->
            transportSubscriptions += transport.on(eventName) { payload ->
                val payloadServerId = payload.serverIdOrNull()
                val currentServerId = connectedServerId
                if (payloadServerId != null && currentServerId != null && payloadServerId != currentServerId) {
                    logger(
                        "event_skip reason=server_mismatch eventName=$eventName payloadServerId=$payloadServerId connectedServerId=$currentServerId payloadType=${payload?.javaClass?.simpleName.orEmpty()}"
                    )
                    return@on
                }
                val event = eventMapper.map(eventName, payload)
                logger(
                    "event_received eventName=$eventName serverId=${connectedServerId.orEmpty()} mapped=${event?.realtimeDiagnosticName().orEmpty()} ${event?.realtimeDiagnosticFields().orEmpty()} payloadType=${payload?.javaClass?.simpleName.orEmpty()}"
                )
                event?.let(::dispatch)
            }
        }
    }

    private fun emitJoinChannel(channelId: ChannelId) {
        transport.emit(RealtimeSocketEvents.JoinChannel, mapOf("channelId" to channelId.value))
    }

    private fun rejoinChannels(reason: String) {
        joinedChannels.forEach { channelId ->
            logger("join_room channelId=${channelId.value} replay=true reason=$reason")
            emitJoinChannel(channelId)
        }
    }

    private fun emitSyncResume(reason: String) {
        if (lastSeq <= 0) return
        logger("sync_resume_emit serverId=${connectedServerId.orEmpty()} lastSeq=$lastSeq reason=$reason")
        transport.emit(RealtimeSocketEvents.SyncResume, RealtimeResumeRequest(lastSeq).toWireMap())
    }

    private fun dispatch(event: RealtimeEvent) {
        if (event is Heartbeat) lastSeq = maxOf(lastSeq, event.seq)
        if (event is MessageNew) event.message.seq?.let { lastSeq = maxOf(lastSeq, it) }
        if (event is MessageUpdated) event.message.seq?.let { lastSeq = maxOf(lastSeq, it) }
        if (event is SyncResumeResponse) lastSeq = maxOf(lastSeq, event.currentSeq)
        if (event is MemberPresenceUpdated) {
            val key = event.memberId.value
            val next = event.presence to event.kind
            if (lastPresenceByMember[key] == next) {
                logger("event_dedup type=member_presence_updated memberId=$key presence=${event.presence} kind=${event.kind?.name.orEmpty()}")
                return
            }
            lastPresenceByMember[key] = next
        }
        logger("event_dispatch type=${event.realtimeDiagnosticName()} seq=$lastSeq listeners=${listeners.size}")
        listeners.toList().forEach { it(event) }
    }

    companion object {
        const val DEFAULT_SOCKET_URL = "https://api.slock.ai"

        val RealtimeWireEventNames = listOf(
            RealtimeSocketEvents.MessageNew,
            RealtimeSocketEvents.DirectMessageNew,
            RealtimeSocketEvents.MessageUpdated,
            RealtimeSocketEvents.ThreadUpdated,
            RealtimeSocketEvents.TaskCreated,
            RealtimeSocketEvents.TaskUpdated,
            RealtimeSocketEvents.TaskDeleted,
            RealtimeSocketEvents.AgentActivity,
            RealtimeSocketEvents.ServerMemberAdded,
            RealtimeSocketEvents.ServerMemberUpdated,
            RealtimeSocketEvents.ServerMemberLeft,
            RealtimeSocketEvents.ServerMemberRemoved,
            RealtimeSocketEvents.SyncResumeResponse,
            RealtimeSocketEvents.Heartbeat
        )
    }

    /**
     * Canonical server contract: every realtime event payload carries top-level
     * camelCase `serverId`. We do not scan nested objects or accept aliases —
     * a mismatch indicates a server bug and should surface, not be papered over.
     */
    private fun Any?.serverIdOrNull(): String? = when (this) {
        null -> null
        is JSONObject -> optString("serverId").takeIf(String::isNotBlank)
        is JsonObject ->
            get("serverId")
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isString }
                ?.asString
                ?.takeIf(String::isNotBlank)
        is Map<*, *> -> (this["serverId"] as? String)?.takeIf(String::isNotBlank)
        else -> null
    }
}

private fun RealtimeEvent.realtimeDiagnosticName(): String = when (this) {
    is MessageNew -> "message_new"
    is MessageUpdated -> "message_updated"
    is DirectMessageCreated -> "direct_message_created"
    is ThreadUpdated -> "thread_updated"
    is TaskCreated -> "task_created"
    is TaskUpdated -> "task_updated"
    is TaskDeleted -> "task_deleted"
    is MemberPresenceUpdated -> "member_presence_updated"
    is SyncResumeResponse -> "sync_resume_response"
    is Heartbeat -> "heartbeat"
}

private fun RealtimeEvent.realtimeDiagnosticFields(): String = when (this) {
    is MessageNew ->
        "threadId=${message.threadId.value} channelId=${message.channelId?.value.orEmpty()} messageId=${message.id.value} seq=${message.seq ?: 0}"
    is MessageUpdated ->
        "threadId=${message.threadId.value} channelId=${message.channelId?.value.orEmpty()} messageId=${message.id.value} seq=${message.seq ?: 0}"
    is DirectMessageCreated -> "channelId=${channelId.value}"
    is ThreadUpdated ->
        "threadId=${threadChannelId?.value.orEmpty()} messageId=${parentMessageId?.value.orEmpty()} replyCount=$replyCount"
    is TaskCreated -> "tasks=${tasks.size}"
    is TaskUpdated -> "taskId=${task.id.value}"
    is TaskDeleted -> "taskId=${taskId.value}"
    is MemberPresenceUpdated ->
        "memberId=${memberId.value} presence=$presence kind=${kind?.name.orEmpty()}"
    is SyncResumeResponse ->
        "currentSeq=$currentSeq messages=${messages.size} hasMore=$hasMore"
    is Heartbeat -> "seq=$seq"
}
