package ai.slock.android.core.data.realtime

import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent

class SocketIoRealtimeClient(
    private val transport: RealtimeSocketTransport,
    private val eventMapper: RealtimeEventMapper = RealtimeEventMapper(),
    private val logger: (String) -> Unit = {}
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
        lastPresenceByMember.clear()
    }

    fun joinChannel(channelId: ChannelId) {
        val added = joinedChannels.add(channelId)
        logger("join_room channelId=${channelId.value} replay=false added=$added")
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
            logger("client_connected serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size}")
            rejoinChannels(reason = "connect")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.RoomsJoined) {
            state = RealtimeConnectionState.RoomsJoined
            logger("rooms_joined serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size} lastSeq=$lastSeq")
            rejoinChannels(reason = "rooms_joined")
            if (lastSeq > 0) {
                logger("sync_resume_emit serverId=${connectedServerId.orEmpty()} lastSeq=$lastSeq")
                transport.emit(RealtimeSocketEvents.SyncResume, RealtimeResumeRequest(lastSeq).toWireMap())
            }
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ConnectError) { payload ->
            state = RealtimeConnectionState.Failed(payload?.toString().orEmpty())
            logger("client_connect_error serverId=${connectedServerId.orEmpty()} reason=${payload?.javaClass?.simpleName.orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Disconnect) {
            state = RealtimeConnectionState.Disconnected
            logger("client_disconnected serverId=${connectedServerId.orEmpty()}")
        }
        RealtimeWireEventNames.forEach { eventName ->
            transportSubscriptions += transport.on(eventName) { payload ->
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

    private fun dispatch(event: RealtimeEvent) {
        if (event is RealtimeEvent.Heartbeat) lastSeq = maxOf(lastSeq, event.seq)
        if (event is RealtimeEvent.SyncResumeResponse) lastSeq = maxOf(lastSeq, event.currentSeq)
        if (event is RealtimeEvent.MemberPresenceUpdated) {
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
}

private fun RealtimeEvent.realtimeDiagnosticName(): String = when (this) {
    is RealtimeEvent.MessageNew -> "message_new"
    is RealtimeEvent.MessageUpdated -> "message_updated"
    is RealtimeEvent.ThreadUpdated -> "thread_updated"
    is RealtimeEvent.TaskCreated -> "task_created"
    is RealtimeEvent.TaskUpdated -> "task_updated"
    is RealtimeEvent.TaskDeleted -> "task_deleted"
    is RealtimeEvent.MemberPresenceUpdated -> "member_presence_updated"
    is RealtimeEvent.SyncResumeResponse -> "sync_resume_response"
    is RealtimeEvent.Heartbeat -> "heartbeat"
}

private fun RealtimeEvent.realtimeDiagnosticFields(): String = when (this) {
    is RealtimeEvent.MessageNew ->
        "threadId=${message.threadId.value} channelId=${message.channelId?.value.orEmpty()} messageId=${message.id.value} seq=${message.seq ?: 0}"
    is RealtimeEvent.MessageUpdated ->
        "threadId=${message.threadId.value} channelId=${message.channelId?.value.orEmpty()} messageId=${message.id.value} seq=${message.seq ?: 0}"
    is RealtimeEvent.ThreadUpdated ->
        "threadId=${threadChannelId?.value.orEmpty()} messageId=${parentMessageId?.value.orEmpty()} replyCount=$replyCount"
    is RealtimeEvent.TaskCreated -> "tasks=${tasks.size}"
    is RealtimeEvent.TaskUpdated -> "taskId=${task.id.value}"
    is RealtimeEvent.TaskDeleted -> "taskId=${taskId.value}"
    is RealtimeEvent.MemberPresenceUpdated ->
        "memberId=${memberId.value} presence=$presence kind=${kind?.name.orEmpty()}"
    is RealtimeEvent.SyncResumeResponse ->
        "currentSeq=$currentSeq messages=${messages.size} hasMore=$hasMore"
    is RealtimeEvent.Heartbeat -> "seq=$seq"
}
